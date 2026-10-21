package pzopt;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.LightingJNI;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;

/**
 * God rays (Config {@code godRays}, docs/findings-god-rays-2026-09-27.md): the key light (sun, or the moon at night) lit in
 * the haze of the open air and in the dust of rooms, shadowed by walls, roofs and upper floors, let through by windows and
 * open doorways, dappled by tree crowns.
 *
 * <p>The camera is orthographic, so every screen pixel's view ray is a fixed world line, and the rays of a screen column
 * of pixels at one depth are a plane: a volume whose x and y are the screen's world coordinates (u = x - y, v = x + y - 6z,
 * see {@link Ssr.View}) and whose third axis is the height z holds each view ray as one column ("rectified" froxels: the
 * world-aligned grid under an ortho camera). The volume is anchored to the world (cells of 2^n world units, toroidal), so
 * it is stable while the camera pans and only the columns that come into view are computed.
 *
 * <ol>
 *   <li>Occupancy (game thread, per chunk, budgeted): one R16UI texel per square and level: the floor plane under it, the
 *       N and W edges (wall / window / open doorway / nothing), the room flag, roof tiles, tree crown density. 256 x 256
 *       squares x 16 levels round the camera, toroidal.</li>
 *   <li>Visibility (compute): for every cell, a 2D DDA along the key light through that grid: edges crossed at the ray's
 *       height (a window lets it through between sill and head), floors and roofs crossed going up, crowns attenuate with
 *       a noise for the gaps between the leaves.</li>
 *   <li>Integration (compute, one thread per column): from the top of the haze down, the light scattered towards the camera
 *       by each slice ({@code V (1 - e^(-sigma dl))} under the transmittance above it; Hillaire's energy-conserving step),
 *       sigma the room dust or the height-falling haze of the weather. Stored per cell: inscatter, transmittance, the sun on
 *       an indoor surface there.</li>
 *   <li>Screen (the stock world composite, screen.frag): one depth fetch and one trilinear tap per pixel:
 *       {@code c' = c T + E p(theta) F} (+ the sunlit patch indoors), the phase and light colour constant per frame.</li>
 * </ol>
 * Recomputed only when the light steps, the grid changes (zoom, levels), the camera brings new columns in or a chunk's
 * occupancy changes: a still frame pays the screen pass's two taps. Other methods ({@code godRaysMethod}) are the
 * alternatives measured against it (docs).
 */
public final class GodRays {
   private GodRays() {
   }

   static final int OCC_N = 256, OCC_L = 16, OCC_CHUNKS = OCC_N / 8;
   static final int VOL_UNIT = 17, DEPTH_UNIT = 18, OCC_UNIT = 19, AUX_UNIT = 20, TOP_UNIT = 21, MARCH_V_UNIT = 22, MARCH_MM_UNIT = 23, MARCH_HIST_UNIT = 24, PLANE_UNIT = 25, APC_WORLD_UNIT = 26, CLOUD_UNIT = 27, LOCAL_UNIT = 28;
   static final float LEVEL = 2.4494897F; // squares of height per level
   static final float VIEW_PATH = 4.8989795F; // squares of view ray per level of height (3 across x and y, 2.449 up)
   // occupancy bits
   static final int B_FLOOR = 1, B_ROOM = 32, B_ROOF = 64;
   static final int B_GLASS_N = 1 << 12, B_GLASS_W = 1 << 13;
   // voxel lighting only: a see-through fence on the N / W edge; a low fence on an open N / W edge (B_LOW_W shares bit 13
   // with B_GLASS_W, which only a window or door edge carries)
   static final int B_TRANS_N = 1 << 14, B_TRANS_W = 1 << 15, B_LOW_N = 1 << 7, B_LOW_W = 1 << 13; // the N / W edge's aperture is a closed door's glass (CPU only: the volume sees a window)
   static final int EDGE_GLASS = 4; // edge(): the aperture is a closed door's glass
   static final int E_OPEN = 0, E_WALL = 1, E_WINDOW = 2, E_DOOR = 3;

   private static volatile boolean failed;
   private static volatile String why = "";

   public static boolean wanted() {
      return Config.GOD_RAYS && Overrides.enabled() && !CoreGl.legacyMac() && !failed;
   }

   // ------------------------------------------------------------------------------------------------ the key light

   /** This frame's light (game thread): direction to it (world squares, unit), its colour x strength (display linear), phase, body. */
   static final float[] light = new float[4];
   static final float[] lightColor = new float[3];
   static float phase = 1F, sigmaOut, sigmaIn, strengthNow, stockFog;
   static int body; // 0 sun, 1 moon
   private static long sweepT0;

   private static void keyLight() {
      float hour = Config.DEV_SUN_HOUR >= 0F ? Config.DEV_SUN_HOUR : -1F;
      if (Config.DEV_SUN_HOUR >= 0F && Config.DEV_GOD_RAYS_HOUR_SPEED > 0F) {
         if (sweepT0 == 0L) {
            sweepT0 = System.nanoTime();
         }
         hour = (Config.DEV_SUN_HOUR + Config.DEV_GOD_RAYS_HOUR_SPEED * (System.nanoTime() - sweepT0) / 1e9F) % 24F;
      }
      Sky.update(hour);
      ClimateManager cm = ClimateManager.getInstance();
      float cloud = cm != null ? clamp01(cm.getCloudIntensity()) : 0F;
      float rain = cm != null ? clamp01(cm.getPrecipitationIntensity()) : 0F;
      float fog = cm != null ? clamp01(cm.getFogIntensity()) : 0F;
      // with cloud shadows the gaps keep the whole sun (the clouds shade where they are); overcast leaves no gap
      float clear = CloudShadow.enabled() ? (1F - smooth(0.8F, 1.0F, cloud)) : (1F - 0.8F * cloud);
      clear *= (1F - 0.6F * rain) * (1F - 0.5F * fog);
      double sunEl = Sky.sunElevDeg, moonEl = Sky.moonElevDeg;
      float sunRise = (float)clamp((sunEl - 1.0) / 7.0, 0.0, 1.0);
      float dark = (float)clamp((-sunEl - 4.0) / 8.0, 0.0, 1.0);
      float moonRise = (float)clamp((moonEl - 1.0) / 7.0, 0.0, 1.0);
      float sunS = sunRise * clear;
      float moonS = dark * moonRise * clear * (float)Math.sqrt(Math.max(0.0, Sky.moonBrightness)) * 0.12F;
      double[] w;
      float[] col = lightColor;
      if (sunS >= moonS && sunS > 0F) {
         w = Sky.sun;
         body = 0;
         // the sun's colour through the air mass (Kasten-Young), Rayleigh-ish per channel: white at noon, orange at a low sun
         double el = Math.max(0.5, sunEl);
         double m = 1.0 / (Math.sin(Math.toRadians(el)) + 0.50572 * Math.pow(el + 6.07995, -1.6364));
         col[0] = (float)Math.exp(-0.035 * m);
         col[1] = (float)Math.exp(-0.075 * m);
         col[2] = (float)Math.exp(-0.16 * m);
         float n = Math.max(col[0], Math.max(col[1], col[2]));
         for (int i = 0; i < 3; i++) {
            col[i] = col[i] / n * sunS;
         }
      } else {
         w = Sky.moon;
         body = 1;
         col[0] = 0.72F * moonS;
         col[1] = 0.82F * moonS;
         col[2] = 1.0F * moonS;
      }
      light[0] = (float)w[0];
      light[1] = (float)w[1];
      light[2] = (float)w[2];
      strengthNow = Math.max(sunS, moonS);
      // phase: the angle between the light's travel (-L) and the way to the camera (0.612, 0.612, 0.5), a forward lobe
      // (Henyey-Greenstein g 0.7, dust and haze are Mie scatterers) over an isotropic floor; x 4 pi (isotropic = 1)
      double cos = -(w[0] * 0.6123724 + w[1] * 0.6123724 + w[2] * 0.5);
      double g = 0.7;
      double hg = (1 - g * g) / Math.pow(1 + g * g - 2 * g * cos, 1.5);
      phase = (float)(0.35 * hg + 0.65);
      // haze: the weather's (fog, rain, the mist of the hours round sunrise) over a clear-day floor; dust in rooms
      float dawnMist = hourMist();
      sigmaOut = 0.0004F * (1F + 45F * fog + 12F * rain + 6F * dawnMist) * Config.GOD_RAYS_HAZE_PCT / 100F; // a clear day: a trace, under the gate (the veil washed the picture out at 0.0022)
      if (sigmaOut > 0F) {
         sigmaOut = (float)Math.exp(Math.round(Math.log(sigmaOut) * 20.0) / 20.0); // 5 % steps: the mist of the morning changes a little every frame, each change re-integrated the volume (30 us)
      }
      stockFog = fog;
      sigmaIn = 0.03F * Config.GOD_RAYS_DUST_PCT / 100F;
      float e = 7.5F * Config.GOD_RAYS_STRENGTH_PCT / 100F; // the light's radiance scale in display-linear units (sunlit ground ~0.6)
      for (int i = 0; i < 3; i++) {
         col[i] *= e;
      }
   }

   /** This frame's key light into {@link #light} (harness rigs: the door the light would come in through). */
   static void lightNow() {
      keyLight();
   }

   private static float hourMist() {
      GameTime gt = GameTime.getInstance();
      float h = gt != null ? gt.getTimeOfDay() : 12F;
      double rise = Sky.noonHour - 6.5; // roughly: the season's sunrise
      float d = (float)Math.abs(h - (rise + 0.8));
      return clamp01(1F - d / 2.2F);
   }

   // ------------------------------------------------------------------------------------------------ occupancy (game thread)

   private static final long[] slotOwner = new long[OCC_CHUNKS * OCC_CHUNKS]; // (wx, wy) packed, Long.MIN_VALUE = none
   private static final long[] slotBuiltMs = new long[OCC_CHUNKS * OCC_CHUNKS];
   private static final int[] slotZ0 = new int[OCC_CHUNKS * OCC_CHUNKS];
   /** the occupancy of each slot as uploaded (the light volumes' CPU walks read it) */
   static final int[][] slotData = new int[OCC_CHUNKS * OCC_CHUNKS][];
   static final int[][] slotApertures = new int[OCC_CHUNKS * OCC_CHUNKS][]; // packed x | y << 3 | level index << 6 | north << 12
   static final IsoObject[][] slotWatch = new IsoObject[OCC_CHUNKS * OCC_CHUNKS][]; // godRaysDoorWatch: the doors and curtained windows of the slot's edges
   static final byte[][] slotWatchState = new byte[OCC_CHUNKS * OCC_CHUNKS][]; // their state when the slot was built (watchState)
   static final int[] slotWatchDoors = new int[OCC_CHUNKS * OCC_CHUNKS]; // how many of them (first) are doors
   private static IsoObject[] watchBuf = new IsoObject[32];
   private static int watchN;
   static long doorChanges; // dev: watched doors / curtains that changed (each rebuilt its chunk)
   static long buildSerial;
   private static final java.util.HashSet<Long> dirtyChunks = new java.util.HashSet<>();
   private static int occZ0 = -4;

   /** Whether the last occupancy scan built every chunk of the window (game thread). */
   static boolean occScanComplete() {
      return !scanIncomplete && scanCamWx != Integer.MIN_VALUE;
   }

   /** The occupancy's first level (game thread). */
   static int occZ0() {
      return occZ0;
   }
   private static int[] spiral;
   static long chunkBuilds, chunkBuildNs;
   static int maxTopLevel = 1; // the tallest occupancy level with anything in it (the DDA's exit height)
   static final int[] levelTops = new int[OCC_CHUNKS * OCC_CHUNKS]; // per slot: the level under which its occupancy can stop a ray
   static volatile boolean topsDirty;

   static {
      java.util.Arrays.fill(slotOwner, Long.MIN_VALUE);
   }

   private static long key(int wx, int wy) {
      return (long)wx << 32 ^ wy & 0xFFFFFFFFL;
   }

   /** A chunk's geometry changed (a geometry bake of one of its levels: a door, a window, a wall): rebuild its occupancy. */
   public static void chunkChanged(IsoChunk c) {
      if (c != null && (Config.GOD_RAYS || EntityShadow.wantsOccupancy() || VoxelLight.wantsOccupancy())) {
         dirtyChunks.add(key(c.wx, c.wy));
      }
   }

   private static int[] spiral() {
      if (spiral == null) {
         int r = OCC_CHUNKS / 2;
         ArrayList<int[]> l = new ArrayList<>();
         for (int dy = -r; dy < r; dy++) {
            for (int dx = -r; dx < r; dx++) {
               l.add(new int[] {dx, dy});
            }
         }
         l.sort((a, b) -> Integer.compare(Math.max(Math.abs(a[0]), Math.abs(a[1])) * 1000 + a[0] * a[0] + a[1] * a[1],
            Math.max(Math.abs(b[0]), Math.abs(b[1])) * 1000 + b[0] * b[0] + b[1] * b[1]));
         spiral = new int[l.size() * 2];
         for (int i = 0; i < l.size(); i++) {
            spiral[i * 2] = l.get(i)[0];
            spiral[i * 2 + 1] = l.get(i)[1];
         }
      }
      return spiral;
   }

   /** The chunks of the window round the camera that need their occupancy (nearest first), into the frame, within the budget. */
   private static final boolean[] slotEmpty = new boolean[OCC_CHUNKS * OCC_CHUNKS]; // built while its chunk was not loaded
   private static int scanCamWx = Integer.MIN_VALUE, scanCamWy;
   private static boolean scanIncomplete = true;
   private static long scanFrame;

   /**
    * The chunks of the window round the camera that need their occupancy (nearest first), into the frame, within the budget.
    * Scanned when the camera's chunk changes, a chunk's geometry changed or the last scan ran out of budget, else every 10
    * frames (a chunk that loads after its slot was filled empty, the 30 s refresh of the near ones): the 1,024 slots are not
    * walked every frame.
    */
   private static void maintainOccupancy(Frame f, int camWx, int camWy, int wantZ0) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      if (wantZ0 != occZ0) {
         occZ0 = wantZ0;
         java.util.Arrays.fill(slotOwner, Long.MIN_VALUE); // every slot again for the new level range
         scanIncomplete = true;
      }
      scanFrame++;
      if (Config.GOD_RAYS_DOOR_WATCH) {
         long p0 = System.nanoTime();
         pollWatched(camWx, camWy);
         pollNs += System.nanoTime() - p0;
      }
      DoorLight.expire(sq -> dirtyChunks.add(key(sq.x >> 3, sq.y >> 3)));
      boolean due = scanIncomplete || !dirtyChunks.isEmpty() || camWx != scanCamWx || camWy != scanCamWy || scanFrame % 10 == 0;
      if (!due) {
         return;
      }
      scanCamWx = camWx;
      scanCamWy = camWy;
      long t0 = System.nanoTime();
      long budget = Math.max(50, Config.GOD_RAYS_CHUNK_BUDGET_US) * 1000L;
      long now = System.currentTimeMillis();
      int[] sp = spiral();
      int built = 0;
      scanIncomplete = false;
      for (int i = 0; i < sp.length; i += 2) {
         int wx = camWx + sp[i], wy = camWy + sp[i + 1];
         int slot = (wy & OCC_CHUNKS - 1) * OCC_CHUNKS + (wx & OCC_CHUNKS - 1);
         long k = key(wx, wy);
         boolean dirty = !dirtyChunks.isEmpty() && dirtyChunks.remove(k);
         boolean stale = slotOwner[slot] != k || slotZ0[slot] != occZ0 || dirty
            || now - slotBuiltMs[slot] > 30_000L && Math.max(Math.abs(sp[i]), Math.abs(sp[i + 1])) <= 6; // near ones refresh (doors, curtains)
         IsoChunk c = null;
         if (!stale && slotEmpty[slot]) {
            c = cell.getChunk(wx, wy);
            stale = c != null; // loaded since its slot was filled empty
         }
         if (!stale) {
            continue;
         }
         if (c == null) {
            c = cell.getChunk(wx, wy);
         }
         if (c == null && slotOwner[slot] == k && slotEmpty[slot]) {
            continue; // not loaded and already empty
         }
         int[] data = build(cell, c, wx, wy);
         VoxelLight.occChanged(wx, wy, slotOwner[slot] == k && slotZ0[slot] == occZ0 ? slotData[slot] : null, data); // voxel lighting: its bricks in reach again when it changed
         if (DoorRig.door() != null && DoorRig.door().getSquare() != null && DoorRig.door().getSquare().x >> 3 == wx && DoorRig.door().getSquare().y >> 3 == wy) {
            Log.info("god rays: rig door chunk occupancy built (" + (dirty ? "dirty" : "stale") + ", door " + (DoorRig.door().isOpen() ? "open" : "closed") + ") epoch_ms=" + System.currentTimeMillis());
         }
         slotOwner[slot] = k;
         slotBuiltMs[slot] = now;
         slotZ0[slot] = occZ0;
         slotData[slot] = data;
         slotEmpty[slot] = c == null;
         buildSerial++;
         f.uploads.add(new Upload(wx, wy, data));
         built++;
         if (System.nanoTime() - t0 > budget || f.uploads.size() >= 64) {
            scanIncomplete = true;
            break;
         }
      }
      if (built > 0) {
         chunkBuilds += built;
         chunkBuildNs += System.nanoTime() - t0;
         int top = 1;
         for (int t : levelTops) {
            top = Math.max(top, t);
         }
         maxTopLevel = top;
      }
      if (topsDirty) {
         topsDirty = false;
         byte[] t = new byte[levelTops.length];
         for (int i = 0; i < t.length; i++) {
            t[i] = (byte)Math.max(0, Math.min(255, levelTops[i] - occZ0));
         }
         f.tops = t;
      }
   }

   /**
    * godRaysDoorWatch: the doors (open, curtain, barricade) and curtained windows of the slots within 6 chunks of the
    * camera against their state when the slot was built; a change marks the chunk dirty, rebuilt this frame. (42.21 draws
    * doors per frame: a toggle bakes nothing, so the bake hook never saw it and the light waited for the 30 s refresh.)
    */
   private static void pollWatched(int camWx, int camWy) {
      for (int dy = -6; dy <= 6; dy++) {
         for (int dx = -6; dx <= 6; dx++) {
            int wx = camWx + dx, wy = camWy + dy;
            int slot = (wy & OCC_CHUNKS - 1) * OCC_CHUNKS + (wx & OCC_CHUNKS - 1);
            if (Math.max(Math.abs(dx), Math.abs(dy)) > 2 && (slot & 7) != (scanFrame & 7)) {
               continue; // (beyond 2 chunks: every 8th frame)
            }
            IsoObject[] w = slotWatch[slot];
            if (w == null || slotOwner[slot] != key(wx, wy)) {
               continue;
            }
            byte[] st = slotWatchState[slot];
            // (curtains, barricades: each slot within 2 chunks every 64th frame, they search object lists; farther out the bake
            // hook and the 30 s refresh see them)
            boolean full = Math.max(Math.abs(dx), Math.abs(dy)) <= 2 && (slot & 63) == (scanFrame & 63);
            int count = full ? w.length : slotWatchDoors[slot]; // (the doors first: between full checks only they are looked at)
            for (int i = 0; i < count; i++) {
               byte now = full ? watchState(w[i]) : (byte)(st[i] & ~1 | openBit(w[i]));
               if (now != st[i]) {
                  if (((now ^ st[i]) & 1) != 0) {
                     DoorLight.toggled(w[i], (now & 1) != 0);
                  }
                  st[i] = now; // (the rebuild records it again; a second change before then is seen too)
                  dirtyChunks.add(key(wx, wy));
                  doorChanges++;
                  if (DoorRig.door() == w[i]) {
                     Log.info("god rays: rig door change seen (" + (DoorRig.door().isOpen() ? "open" : "closed") + ") epoch_ms=" + System.currentTimeMillis());
                  }
                  break;
               }
            }
         }
      }
   }

   /** The open state alone (a field read): every frame. */
   private static int openBit(IsoObject o) {
      return o instanceof zombie.iso.objects.IsoDoor d ? d.IsOpen() ? 1 : 0 : o instanceof zombie.iso.objects.IsoThumpable t && t.IsOpen() ? 1 : 0;
   }

   /** What the light sees of a watched door or window: open, curtain drawn, barricaded, gone from its square. */
   static byte watchState(IsoObject o) {
      int s = o.getSquare() == null ? 64 : 0;
      if (o instanceof zombie.iso.objects.IsoDoor d) {
         s |= (d.IsOpen() ? 1 : 0) | (d.HasCurtains() != null && !d.isCurtainOpen() ? 2 : 0) | (d.isBarricaded() ? 4 : 0);
      } else if (o instanceof zombie.iso.objects.IsoThumpable t) {
         IsoCurtain cu = t.HasCurtains();
         s |= (t.IsOpen() ? 1 : 0) | (cu != null && !cu.IsOpen() ? 2 : 0) | (t.isBarricaded() ? 4 : 0);
      } else if (o instanceof IsoWindow w) {
         IsoCurtain cu = w.HasCurtains();
         s |= (cu != null && !cu.IsOpen() ? 2 : 0) | (w.isBarricaded() ? 4 : 0);
      }
      return (byte)s;
   }

   private static void watch(IsoObject o) {
      if (o == null || !Config.GOD_RAYS_DOOR_WATCH) {
         return;
      }
      for (int i = 0; i < watchN; i++) {
         if (watchBuf[i] == o) {
            return;
         }
      }
      if (watchN == watchBuf.length) {
         watchBuf = java.util.Arrays.copyOf(watchBuf, watchN * 2);
      }
      watchBuf[watchN++] = o;
   }

   static final class Upload {
      final int wx, wy;
      final int[] data;

      Upload(int wx, int wy, int[] data) {
         this.wx = wx;
         this.wy = wy;
         this.data = data;
      }
   }

   /** One chunk's 8 x 8 x OCC_L occupancy texels (x fastest, then y, then level), levels occZ0 .. occZ0 + 15. */
   static int[] build(IsoCell cell, IsoChunk c, int wx, int wy) {
      int[] out = new int[64 * OCC_L];
      int slot = (wy & OCC_CHUNKS - 1) * OCC_CHUNKS + (wx & OCC_CHUNKS - 1);
      levelTops[slot] = 0;
      if (c == null) {
         slotWatch[slot] = null;
         slotWatchState[slot] = null;
         return out;
      }
      int top = 0;
      watchN = 0;
      int[] glassBands = null; // the door glass bands of the apertures (index: texel * 2 + (W ? 1 : 0))
      for (int lz = 0; lz < OCC_L; lz++) {
         int z = occZ0 + lz;
         if (z < c.minLevel || z > c.maxLevel) {
            continue;
         }
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = c.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               int b = 0;
               if (sq.getFloor() != null || sq.has(IsoFlagType.solidfloor)) {
                  b |= B_FLOOR;
               }
               int en = edge(sq, true), bandN = lastGlassBand, ew = edge(sq, false), bandW = lastGlassBand;
               b |= (en & 3) << 1 | (ew & 3) << 3;
               // voxel lighting (pzopt.VoxelLight; the god rays read neither): a see-through fence on the N / W edge (bars,
               // chain link: part of the light through), a low fence on an open N / W edge (only its bottom stops light)
               if (sq.has(IsoFlagType.WallNTrans) || sq.has(IsoFlagType.TallHoppableN)) {
                  b |= B_TRANS_N;
               }
               if (sq.has(IsoFlagType.WallWTrans) || sq.has(IsoFlagType.TallHoppableW)) {
                  b |= B_TRANS_W;
               }
               if ((en & 3) == E_OPEN && sq.has(IsoFlagType.HoppableN)) {
                  b |= B_LOW_N;
               }
               // a free-standing solid fence (a fence panel, no room on either side) on a wall edge: low + see-through bits
               // together, which no other wall edge carries; it stops light only up to its top (VoxelLight.TRACE_GLSL)
               if ((en & 3) == E_WALL && (b & B_TRANS_N) == 0 && solidFence(sq, true)) {
                  b |= B_LOW_N | B_TRANS_N;
               }
               if ((ew & 3) == E_WALL && (b & B_TRANS_W) == 0 && solidFence(sq, false)) {
                  b |= B_LOW_W | B_TRANS_W;
               }
               if ((ew & 3) == E_OPEN && (ew & EDGE_GLASS) == 0 && sq.has(IsoFlagType.HoppableW)) {
                  b |= B_LOW_W;
               }
               if ((en & EDGE_GLASS) != 0 || (ew & EDGE_GLASS) != 0) {
                  if (glassBands == null) {
                     glassBands = new int[64 * OCC_L * 2];
                  }
                  int gi = ((lz * 8 + y) * 8 + x) * 2;
                  if ((en & EDGE_GLASS) != 0) {
                     b |= B_GLASS_N;
                     glassBands[gi] = bandN;
                  }
                  if ((ew & EDGE_GLASS) != 0) {
                     b |= B_GLASS_W;
                     glassBands[gi + 1] = bandW;
                  }
               }
               if (sq.getRoom() != null) {
                  b |= B_ROOM | roomHash(sq.getRoom(), z) << 16;
               }
               if (roof(sq)) {
                  b |= B_ROOF;
               }
               out[(lz * 8 + y) * 8 + x] = b;
            }
         }
      }
      crowns(cell, c, out);
      // the windows and doorways of the chunk (packed x, y, level index, north): the light volumes' candidates
      int na = 0;
      int[] ap = new int[16];
      for (int i = 0; i < out.length; i++) {
         int b = out[i];
         for (int e = 0; e < 2; e++) {
            int type = e == 0 ? b >> 1 & 3 : b >> 3 & 3;
            if (type == E_WINDOW || type == E_DOOR) {
               if (na == ap.length) {
                  ap = java.util.Arrays.copyOf(ap, na * 2);
               }
               int gb = glassBands != null && (b & (e == 0 ? B_GLASS_N : B_GLASS_W)) != 0 ? glassBands[i * 2 + e] : 0;
               ap[na++] = (i & 7) | (i >> 3 & 7) << 3 | (i >> 6) << 6 | e << 12 | gb << 16; // + a closed door's glass band
            }
         }
      }
      slotApertures[slot] = java.util.Arrays.copyOf(ap, na);
      if (watchN > 0) {
         IsoObject[] w = new IsoObject[watchN];
         int nd = 0;
         for (int i = 0; i < watchN; i++) {
            if (!(watchBuf[i] instanceof IsoWindow)) {
               w[nd++] = watchBuf[i];
            }
         }
         slotWatchDoors[slot] = nd;
         for (int i = 0, k = nd; i < watchN; i++) {
            if (watchBuf[i] instanceof IsoWindow) {
               w[k++] = watchBuf[i];
            }
         }
         byte[] st = new byte[watchN];
         for (int i = 0; i < watchN; i++) {
            st[i] = watchState(w[i]);
            watchBuf[i] = null;
         }
         slotWatch[slot] = w;
         slotWatchState[slot] = st;
         watchN = 0;
      } else {
         slotWatch[slot] = null;
         slotWatchState[slot] = null;
      }
      // the chunk's top: nothing above it can stop a ray (a floor at L stops what is under L; walls, roofs, crowns fill L)
      for (int lz = 0; lz < OCC_L; lz++) {
         for (int i = 0; i < 64; i++) {
            int b = out[lz * 64 + i];
            if ((b & (6 | 24 | B_ROOF | 15 << 8)) != 0) {
               top = Math.max(top, occZ0 + lz + 1);
            } else if ((b & B_FLOOR) != 0) {
               top = Math.max(top, occZ0 + lz);
            }
         }
      }
      levelTops[slot] = top;
      topsDirty = true;
      return out;
   }

   /**
    * The edge's type for the light: a wall, a window (glass or an empty frame, not curtained or barricaded), an open doorway,
    * a closed door's glass (E_WINDOW | EDGE_GLASS: the window in a door; E_DOOR | EDGE_GLASS: a sliding glass door). A window
    * or doorway between a room and the outside whose outside square is under a roof (a porch, an awning, a floor above) is a
    * wall for the light (godRaysRoofRule).
    */
   private static int edge(IsoGridSquare sq, boolean north) {
      int e = edgeType(sq, north);
      int t = e & 3;
      if ((t == E_WINDOW || t == E_DOOR) && Config.GOD_RAYS_ROOF_RULE && !Config.GOD_RAYS_ROOF_EXACT && outsideCovered(sq, north)) {
         return E_WALL;
      }
      return e;
   }

   static long coveredApertures; // dev: windows / doorways the roof rule closed (occupancy builds)

   /** The square across the edge from sq and sq itself: the one without a room, if exactly one has none, has a floor or roof right above it. */
   static boolean outsideCovered(IsoGridSquare sq, boolean north) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return false;
      }
      int x = sq.getX(), y = sq.getY(), z = sq.getZ();
      IsoGridSquare nb = cell.getGridSquare(north ? x : x - 1, north ? y - 1 : y, z);
      boolean roomHere = sq.getRoom() != null, roomNb = nb != null && nb.getRoom() != null;
      if (nb == null || roomHere == roomNb) {
         return false; // between two rooms (or two outside squares): not a way in from outside
      }
      IsoGridSquare out = roomHere ? nb : sq;
      IsoGridSquare above = cell.getGridSquare(out.getX(), out.getY(), z + 1);
      boolean covered = above != null && covers(above);
      if (covered) {
         coveredApertures++;
         if (Config.DEV_GOD_RAYS_AP_LOG > 0 && coveredApertures <= 400) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < above.getObjects().size(); k++) {
               IsoObject ob = above.getObjects().get(k);
               sb.append(' ').append(ob != null && ob.getSprite() != null ? ob.getSprite().getName() : "?");
            }
            Log.info("god rays: roof rule: " + x + "," + y + "," + z + (north ? " N" : " W") + " outside " + out.getX() + "," + out.getY() + " covered by" + sb);
         }
      }
      return covered;
   }

   /**
    * Something on the square one level up that roofs the square under it: a floor (a balcony, an upper storey) or a roof
    * surface (a porch roof, an awning, a roof slope reaching over it). Not the pieces that stand on the wall line at that
    * level: the gable walls ("walls_exterior_roofs_"), the roof trims ("roofs_accents_"), rooftop furniture.
    */
   private static boolean covers(IsoGridSquare above) {
      if (above.getFloor() != null || above.has(IsoFlagType.solidfloor)) {
         return true;
      }
      zombie.util.list.PZArrayList<IsoObject> objects = above.getObjects();
      for (int k = 0; k < objects.size(); k++) {
         IsoObject o = objects.get(k);
         String n = o != null && o.getSprite() != null ? o.getSprite().getName() : null;
         if (n != null && n.startsWith("roofs_") && !n.startsWith("roofs_accents") || o != null && ChunkAo.ownSheetRoof(o.getSprite())) {
            return true; // a tent's roof over the square too
         }
      }
      return false;
   }

   /**
    * The wall on sq's N / W edge is a fence ("fencing_" sheets), or the edge's wall flag comes from an object that is not a
    * wall (a free-standing panel), and neither square beside it is in a room.
    */
   static boolean solidFence(IsoGridSquare sq, boolean north) {
      if (sq.getRoom() != null) {
         return false;
      }
      IsoObject w = sq.getWall(north);
      String n = w != null && w.getSprite() != null ? w.getSprite().getName() : null;
      if (w != null && (n == null || !n.startsWith("fencing_"))) {
         return false;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare nb = cell == null ? null : cell.getGridSquare(north ? sq.getX() : sq.getX() - 1, north ? sq.getY() - 1 : sq.getY(), sq.getZ());
      return nb == null || nb.getRoom() == null;
   }

   private static int edgeType(IsoGridSquare sq, boolean north) {
      if (sq.has(north ? IsoFlagType.DoorWallN : IsoFlagType.DoorWallW) || sq.has(north ? IsoFlagType.doorN : IsoFlagType.doorW)) {
         IsoObject d = sq.getDoor(north ? zombie.iso.objects.GridSquareEdgeFacingDirection.NORTH_SOUTH : zombie.iso.objects.GridSquareEdgeFacingDirection.EAST_WEST); // 42.21: edge enum (north = NORTH_SOUTH)
         if (d == null) {
            return E_DOOR; // an empty door frame
         }
         watch(d);
         boolean open = d instanceof zombie.iso.objects.IsoDoor door ? door.IsOpen()
            : d instanceof zombie.iso.objects.IsoThumpable th && th.IsOpen();
         if (open || DoorLight.closing(d)) {
            return E_DOOR; // (a door swinging shut lets the light through until it has: godRaysDoorSwingMs)
         }
         int g = Config.GOD_RAYS_DOOR_GLASS ? doorGlass(d) : -1;
         if (g < 0) {
            return E_WALL;
         }
         lastGlassBand = g;
         // the volume sees glass from the floor (a glass door) as a doorway, a window in the door as a window
         return ((g >> 8 & 15) <= 1 ? E_DOOR : E_WINDOW) | EDGE_GLASS;
      }
      if (sq.has(north ? IsoFlagType.WindowN : IsoFlagType.WindowW) || sq.has(north ? IsoFlagType.windowN : IsoFlagType.windowW)) {
         IsoWindow w = sq.getWindow(north ? zombie.iso.objects.GridSquareEdgeFacingDirection.NORTH_SOUTH : zombie.iso.objects.GridSquareEdgeFacingDirection.EAST_WEST); // 42.21: edge enum
         if (w != null) {
            if (w.HasCurtains() != null || w.isBarricaded()) {
               watch(w);
            }
            if (w.isBarricaded()) {
               return E_WALL;
            }
            IsoCurtain cu = w.HasCurtains();
            if (cu != null && !cu.IsOpen()) {
               return E_WALL;
            }
         }
         return E_WINDOW;
      }
      if (sq.has(north ? IsoFlagType.WallN : IsoFlagType.WallW) || sq.has(IsoFlagType.WallNW) || sq.has(north ? IsoFlagType.cutN : IsoFlagType.cutW)) {
         return E_WALL;
      }
      return E_OPEN;
   }

   static int lastGlassBand; // edgeType's last door glass band (game thread: the occupancy build)
   static final int DEFAULT_GLASS_BAND = 5 | 10 << 4 | 7 << 8 | 11 << 12; // a door's window the art table does not know (a mod's door): 0.33..0.67 across, 0.47..0.73 up

   /**
    * The glass band of a closed door the light goes through (DoorGlass's packed band), -1 for none: the game's "doorTrans"
    * tiles (the doors it lets the player see through and hang a curtain in: a window in the door, glass doors), not
    * curtained (closed), not barricaded, not a garage door or a fence gate.
    */
   private static int doorGlass(IsoObject d) {
      zombie.core.properties.PropertyContainer p = d.getProperties();
      if (p == null || !p.has(zombie.core.properties.IsoPropertyType.DOOR_TRANS) || p.has(zombie.core.properties.IsoPropertyType.GARAGE_DOOR)) {
         return -1;
      }
      if (d instanceof zombie.iso.objects.IsoDoor door) {
         if (door.isBarricaded() || door.HasCurtains() != null && !door.isCurtainOpen()) {
            return -1;
         }
      } else if (d instanceof zombie.iso.objects.IsoThumpable th) {
         IsoCurtain cu = th.HasCurtains();
         if (th.isBarricaded() || cu != null && !cu.IsOpen()) {
            return -1;
         }
      } else {
         return -1;
      }
      String name = d.getSprite() != null ? d.getSprite().getName() : null;
      if (name != null && (name.startsWith("fixtures_doors_fences") || name.startsWith("walls_logs"))) {
         return -1; // fence gates: no room behind them
      }
      int b = DoorGlass.band(name);
      return b >= 0 ? b : DEFAULT_GLASS_BAND;
   }

   private static boolean roof(IsoGridSquare sq) {
      zombie.util.list.PZArrayList<IsoObject> objects = sq.getObjects();
      for (int k = 0; k < objects.size(); k++) {
         IsoObject o = objects.get(k);
         if (o != null && ChunkAo.roofSprite(o.getSprite())) { // the tents' roof tiles too (WestRoof-typed, no RoofGroup)
            return true;
         }
      }
      return false;
   }

   /** 1..65535 for a room on a level (0 = none): the integration's dust stays within one room. */
   static int roomHash(zombie.iso.areas.IsoRoom r, int z) {
      long id = r.getRoomDef() != null ? r.getRoomDef().getID() : System.identityHashCode(r);
      long h = (id * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
      h ^= h >>> 29;
      int v = (int)(h & 0xFFFF);
      return v == 0 ? 1 : v;
   }

   private static final java.util.HashMap<Long, float[]> TREES = new java.util.HashMap<>();

   /** A chunk's trees (x, y, z, height in levels), cached with its build. */
   private static float[] trees(IsoChunk c) {
      float[] out = new float[16];
      int n = 0;
      for (int z = c.minLevel; z <= c.maxLevel; z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = c.getGridSquare(x, y, z);
               IsoTree t = sq == null ? null : sq.getTree();
               if (t == null) {
                  continue;
               }
               if (n + 4 > out.length) {
                  out = java.util.Arrays.copyOf(out, out.length * 2);
               }
               out[n] = sq.x;
               out[n + 1] = sq.y;
               out[n + 2] = z;
               out[n + 3] = treeLevels(t);
               n += 4;
            }
         }
      }
      out = java.util.Arrays.copyOf(out, n);
      if (TREES.size() > 4096) {
         TREES.clear();
      }
      TREES.put(key(c.wx, c.wy), out);
      return out;
   }

   private static float treeLevels(IsoTree t) {
      zombie.core.textures.Texture tex = t.getSprite() != null ? t.getSprite().getTextureForCurrentFrame(t.getDir(), t) : null;
      String tn = tex != null && tex.getName() != null ? tex.getName() : t.getSprite() != null ? t.getSprite().name : null;
      float floors = tn == null ? 3F : tn.contains("JUMBOXXL") ? 15F : tn.contains("JUMBOXL") ? 11F : tn.contains("JUMBO") ? 7F : 3F;
      return floors / 3F;
   }

   /**
    * The crowns of the trees of this chunk and its neighbours as foliage density (bits 8-11) in this chunk's texels: the
    * crown ellipsoid of ChunkAo's proxies (centre on the tree card at the square's south-east corner, 0.6 of the sprite's
    * height up, 0.4 of it tall), density falling to the rim.
    */
   private static void crowns(IsoCell cell, IsoChunk c, int[] out) {
      int x0 = c.wx * 8, y0 = c.wy * 8;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk nc = dx == 0 && dy == 0 ? c : cell.getChunk(c.wx + dx, c.wy + dy);
            if (nc == null) {
               continue;
            }
            float[] t = dx == 0 && dy == 0 ? trees(nc) : TREES.containsKey(key(nc.wx, nc.wy)) ? TREES.get(key(nc.wx, nc.wy)) : trees(nc);
            for (int k = 0; k + 3 < t.length; k += 4) {
               float levels = t[k + 3];
               float rh = levels >= 4.5F ? 3.3F : levels >= 3.5F ? 2.4F : levels >= 2F ? 1.5F : 0.9F;
               float cx = t[k] + 1F, cy = t[k + 1] + 1F;
               float cz = t[k + 2] + 0.62F * levels, rv = 0.36F * levels;
               int sx0 = (int)Math.floor(cx - rh), sx1 = (int)Math.floor(cx + rh), sy0 = (int)Math.floor(cy - rh), sy1 = (int)Math.floor(cy + rh);
               for (int sy = Math.max(sy0, y0); sy <= Math.min(sy1, y0 + 7); sy++) {
                  for (int sx = Math.max(sx0, x0); sx <= Math.min(sx1, x0 + 7); sx++) {
                     float ddx = sx + 0.5F - cx, ddy = sy + 0.5F - cy;
                     float hr = (ddx * ddx + ddy * ddy) / (rh * rh);
                     if (hr >= 1F) {
                        continue;
                     }
                     for (int lz = 0; lz < OCC_L; lz++) {
                        float zc = occZ0 + lz + 0.5F;
                        float vr = (zc - cz) / (rv + 0.5F);
                        float r = hr + vr * vr;
                        if (r >= 1F) {
                           continue;
                        }
                        int d = Math.min(15, Math.round(15F * (1F - r * r)));
                        int idx = (lz * 8 + (sy - y0)) * 8 + (sx - x0);
                        int cur = out[idx] >> 8 & 15;
                        if (d > cur) {
                           out[idx] = out[idx] & ~(15 << 8) | d << 8;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ light volumes (apertures)

   /** The windows and doorways of chunk (cx, cy) as built (packed, see build), null when its slot holds another chunk. */
   static int[] aperturesOf(int cx, int cy) {
      int slot = (cy & OCC_CHUNKS - 1) * OCC_CHUNKS + (cx & OCC_CHUNKS - 1);
      return slotOwner[slot] == key(cx, cy) ? slotApertures[slot] : null;
   }

   /** The occupancy texel of square (x, y) at level z, from the CPU copies (0 when that chunk is not in the window). */
   static int occCpu(int x, int y, int z) {
      int lz = z - occZ0;
      if (lz < 0 || lz >= OCC_L) {
         return 0;
      }
      int wx = x >> 3, wy = y >> 3;
      int slot = (wy & OCC_CHUNKS - 1) * OCC_CHUNKS + (wx & OCC_CHUNKS - 1);
      int[] d = slotData[slot];
      if (d == null || slotOwner[slot] != key(wx, wy)) {
         return 0;
      }
      return d[(lz * 8 + (y & 7)) * 8 + (x & 7)];
   }

   /** The square's N (W) edge is an open doorway without glass (the occupancy): a wide door's next tile. */
   static boolean openDoorway(int x, int y, int z, boolean north) {
      int o = occCpu(x, y, z);
      return (north ? o >> 1 & 3 : o >> 3 & 3) == E_DOOR && (o & (north ? B_GLASS_N : B_GLASS_W)) == 0;
   }

   private static boolean edgeBlocksCpu(int e, float f) {
      return e == E_WALL || e == E_WINDOW && (f < 0.30F || f > 0.84F) || e == E_DOOR && f > 0.80F;
   }

   /**
    * The CPU twin of the compute shader's DDA: from (x, y, z levels) along the horizontal direction (dx, dy) (unit), rising
    * slope levels per square across. toLight: the key light's visibility (0..1). !toLight: the distance (squares across) to
    * the first wall that stops a ray going down the light's way (the slope negative), capped at maxT.
    */
   static float walk(float x, float y, float z, float dx, float dy, float slope, float maxT, boolean toLight) {
      int sx = (int)Math.floor(x), sy = (int)Math.floor(y), lvl = (int)Math.floor(z);
      float ix = 1F / Math.max(Math.abs(dx), 1e-5F), iy = 1F / Math.max(Math.abs(dy), 1e-5F);
      float tx = (dx >= 0F ? 1F - (x - sx) : x - sx) * ix, ty = (dy >= 0F ? 1F - (y - sy) : y - sy) * iy;
      float t = 0F, vis = 1F;
      for (int i = 0; i < 96; i++) {
         float tn = Math.min(tx, ty);
         float zn = z + tn * slope;
         int ln = (int)Math.floor(zn);
         if (toLight) {
            for (int L = lvl + 1; L <= ln; L++) {
               if ((occCpu(sx, sy, L) & (B_FLOOR | B_ROOF)) != 0) {
                  return 0F;
               }
            }
         } // (down the light's way only walls count: each corner of a light volume stops at the floor on its own)
         lvl = ln;
         t = tn;
         if (t > maxT || toLight && zn > maxTopLevel + 1) {
            return toLight ? vis : maxT;
         }
         float f = zn - ln;
         if (tx < ty) {
            int e = occCpu(dx >= 0F ? sx + 1 : sx, sy, ln) >> 3 & 3;
            if (edgeBlocksCpu(e, f)) {
               return toLight ? 0F : t;
            }
            sx += dx >= 0F ? 1 : -1;
            tx += ix;
         } else {
            int e = occCpu(sx, dy >= 0F ? sy + 1 : sy, ln) >> 1 & 3;
            if (edgeBlocksCpu(e, f)) {
               return toLight ? 0F : t;
            }
            sy += dy >= 0F ? 1 : -1;
            ty += iy;
         }
      }
      return toLight ? vis : t;
   }

   /**
    * How far a ray from (x, y, height cz levels) inside a room goes down the light's way (dx, dy) (unit, horizontal; it falls
    * slope levels a square) on level z before a wall stops it at its height or it leaves the building (a square without a
    * room), capped at maxT (its floor).
    */
   static final float SOFT_W0 = 0.04F, SOFT_K = 0.07F, SOFT_FADE = 0.015F; // the penumbra's half width at the aperture (squares) and its growth a square from it (godRaysSoftPct 100)

   static float reach(float x, float y, float cz, int z, float dx, float dy, float slope, float maxT) {
      int sx = (int)Math.floor(x), sy = (int)Math.floor(y);
      float ix = 1F / Math.max(Math.abs(dx), 1e-5F), iy = 1F / Math.max(Math.abs(dy), 1e-5F);
      float tx = (dx >= 0F ? 1F - (x - sx) : x - sx) * ix, ty = (dy >= 0F ? 1F - (y - sy) : y - sy) * iy;
      for (int i = 0; i < 64; i++) {
         float tn = Math.min(tx, ty);
         if (tn >= maxT) {
            return maxT;
         }
         float f = cz - slope * tn - z;
         int e;
         if (tx < ty) {
            e = occCpu(dx >= 0F ? sx + 1 : sx, sy, z) >> 3 & 3;
            sx += dx >= 0F ? 1 : -1;
            tx += ix;
         } else {
            e = occCpu(sx, dy >= 0F ? sy + 1 : sy, z) >> 1 & 3;
            sy += dy >= 0F ? 1 : -1;
            ty += iy;
         }
         if (edgeBlocksCpu(e, f) || (occCpu(sx, sy, z) & B_ROOM) == 0) {
            return tn;
         }
      }
      return maxT;
   }

   static final int PRISM_FLOATS = 30 * 4; // up to 10 triangles (the faces toward the camera, at most 5): x, y (relative to the view origin), z (levels), the prism's index
   static final int PLANE_FLOATS = 11 * 4; // 7 half-spaces (nx, ny, nz, d; squares) + (light, 0, 0, 0)
   private static float[] prisms = new float[PRISM_FLOATS * 64];
   private static float[] planes = new float[PLANE_FLOATS * 64];
   private static zombie.iso.BuildingDef[] prismDef = new zombie.iso.BuildingDef[64]; // each prism's room's building (null: none)
   private static int[] prismLevel = new int[64];
   static int prismCount;
   private static long prismKey = Long.MIN_VALUE;
   static long prismBuilds, aperturesSeen, prismBuildNs;
   private static int prismRefX, prismRefY;
   private static int[] prismLeaf = new int[64]; // the door of each prism with a leaf (x | y << 15... packed, see buildPrisms), -1 none: a swing patches its leaf in place
   private static final float[] leafBuf = new float[8], overhang = new float[4];
   private static int[] leafAt = new int[64 * 4];
   private static boolean puffLast;
   private static float[] bnc = new float[64 * 6]; // per prism: its sunlit patch on the floor (x, y from the reference, level, radius, size x light, room hash); radius 0: none

   /**
    * godRaysBounce: the prism's sunlit patch on its floor (the quad where the rays through the aperture's four corners end:
    * on the floor, or at the wall that stops them) as a disk of the same area at its centroid, for the light it bounces into
    * the room.
    */
   private static void bouncePatch(float[] hits, int z, int pi, int roomOcc, float vis) {
      if (bnc.length < (pi + 1) * 6) {
         bnc = java.util.Arrays.copyOf(bnc, (pi + 64) * 6);
      }
      int n = 4;
      float[] pts = hits;
      bnc[pi * 6 + 3] = 0F;
      if (n < 3 || (roomOcc & B_ROOM) == 0) {
         return;
      }
      // the hull's area and centroid (the points are a convex prism's floor section, in some order: sort by angle)
      float mx = 0F, my = 0F;
      for (int i = 0; i < n; i++) {
         mx += pts[i * 2] / n;
         my += pts[i * 2 + 1] / n;
      }
      final float cx0 = mx, cy0 = my;
      Integer[] ord = new Integer[n];
      for (int i = 0; i < n; i++) {
         ord[i] = i;
      }
      java.util.Arrays.sort(ord, (a, b) -> Double.compare(Math.atan2(pts[a * 2 + 1] - cy0, pts[a * 2] - cx0), Math.atan2(pts[b * 2 + 1] - cy0, pts[b * 2] - cx0)));
      float area = 0F, ax = 0F, ay = 0F;
      for (int k = 0; k < n; k++) {
         int a = ord[k], b = ord[(k + 1) % n];
         float cr = pts[a * 2] * pts[b * 2 + 1] - pts[b * 2] * pts[a * 2 + 1];
         area += cr;
         ax += (pts[a * 2] + pts[b * 2]) * cr;
         ay += (pts[a * 2 + 1] + pts[b * 2 + 1]) * cr;
      }
      area *= 0.5F;
      if (Math.abs(area) < 0.02F) {
         return;
      }
      bnc[pi * 6] = ax / (6F * area);
      bnc[pi * 6 + 1] = ay / (6F * area);
      bnc[pi * 6 + 2] = z;
      bnc[pi * 6 + 3] = (float)Math.sqrt(Math.abs(area) / Math.PI);
      bnc[pi * 6 + 4] = vis * Math.abs(area);
      bnc[pi * 6 + 5] = roomOcc >>> 16;
   }

   private static final float[] patchPts = new float[8]; // buildPrisms: the aperture corners' rays where they end (floor or wall), from the reference

   /**
    * A door swinging (godRaysDoorSwingMs): the leaves of the built volumes again, in place (the volumes themselves do not
    * change); true when one moved (the frame's plane copy and upload follow).
    */
   private static boolean swingLeaves() {
      if (!DoorLight.anySwing()) {
         return false;
      }
      boolean moved = false;
      for (int pi = 0; pi < prismCount; pi++) {
         if (prismLeaf[pi] < 0) {
            continue;
         }
         if (DoorLight.leaf(leafAt[pi * 4], leafAt[pi * 4 + 1], leafAt[pi * 4 + 2], leafAt[pi * 4 + 3] == 1, prismRefX, prismRefY, leafBuf)) {
            int pa = pi * PLANE_FLOATS + 32;
            for (int q = 0; q < 8; q++) {
               moved |= planes[pa + q] != leafBuf[q];
               planes[pa + q] = leafBuf[q];
            }
         }
      }
      return moved;
   }

   /**
    * The light volumes of the frame: every window or open doorway in view that the key light comes through from outside
    * into a room, its aperture (the glass between sill and head, a door's opening) extruded down the light's way to the
    * floor (or to the first wall on the way), as 12 triangles with each face's sign (+ where the view ray enters the prism,
    * - where it leaves) x the light getting through the aperture's outer side (a CPU DDA). Cached until the light, the
    * occupancy or the view origin changes.
    */
   private static void buildPrisms(Frame f, float x0, float y0, float x1, float y1) {
      int cx0 = (int)Math.floor(x0) >> 3, cx1 = (int)Math.floor(x1) >> 3, cy0 = (int)Math.floor(y0) >> 3, cy1 = (int)Math.floor(y1) >> 3;
      int camLevel = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
      long k = ((((((f.lightKey * 31L + buildSerial) * 131L + f.refX) * 7919L + f.refY) * 17L + cx0 * 3L + cy1) * 13L + camLevel) * 307L + Config.GOD_RAYS_SOFT_PCT) * 5L + (Config.GOD_RAYS_DOOR_LEAF ? 1 : 0) + (Config.GOD_RAYS_BOUNCE_PCT > 0 ? 2 : 0); // (the menu's live keys that shape the volumes)
      if (k == prismKey) {
         return;
      }
      long t0 = System.nanoTime();
      prismKey = k;
      prismBuilds++;
      prismCount = 0;
      prismRefX = f.refX;
      prismRefY = f.refY;
      float lx = f.lx, ly = f.ly, lz = f.lz;
      double h = Math.hypot(lx, ly);
      if (lz < 0.03F || h < 1e-3) {
         return;
      }
      float dx = (float)(lx / h), dy = (float)(ly / h), slope = (float)(lz / h / LEVEL);
      boolean apLog = prismBuilds <= Config.DEV_GOD_RAYS_AP_LOG;
      if (apLog) {
         Log.info(String.format(java.util.Locale.ROOT, "god rays: prism build %d light %s (%.3f, %.3f, %.3f) slope %.3f/square cam level %d chunks %d..%d x %d..%d",
            prismBuilds, body == 0 ? "sun" : "moon", lx, ly, lz, slope, camLevel, cx0, cx1, cy0, cy1));
      }
      int zMin = Math.max(occZ0, (int)Math.floor(f.zLo)), zMax = Math.min(occZ0 + OCC_L - 1, camLevel);
      for (int cy = cy0; cy <= cy1; cy++) {
         for (int cx = cx0; cx <= cx1; cx++) {
            int slot = (cy & OCC_CHUNKS - 1) * OCC_CHUNKS + (cx & OCC_CHUNKS - 1);
            int[] list = slotApertures[slot];
            if (list == null || slotOwner[slot] != key(cx, cy)) {
               continue;
            }
            for (int packed : list) {
               int x = cx * 8 + (packed & 7), y = cy * 8 + (packed >> 3 & 7), z = occZ0 + (packed >> 6 & 63);
               boolean north = (packed >> 12 & 1) == 0;
               if (z < zMin || z > zMax) {
                  continue;
               }
               int o = occCpu(x, y, z);
               int type = north ? o >> 1 & 3 : o >> 3 & 3;
               if (type != E_WINDOW && type != E_DOOR) {
                  continue;
               }
               {
                  aperturesSeen++;
                  // the two sides: A the west / north square, B this one; the light crosses from outside into a room
                  int oa = north ? occCpu(x, y - 1, z) : occCpu(x - 1, y, z);
                  boolean roomA = (oa & B_ROOM) != 0, roomB = (o & B_ROOM) != 0;
                  float along = north ? dy : dx; // < 0: the light is on the A side (travels A -> B)
                  boolean intoB = along < -0.05F && roomB && !roomA, intoA = along > 0.05F && roomA && !roomB;
                  boolean rigDoor = DoorRig.at(x, y, z, north);
                  if (rigDoor) {
                     Log.info(String.format(java.util.Locale.ROOT, "god rays: rig door aperture type %d roomA %b roomB %b along %.2f %s build %d epoch_ms=%d", type, roomA, roomB, along,
                        intoB ? "intoB" : intoA ? "intoA" : "skip", prismBuilds, System.currentTimeMillis()));
                  }
                  if (apLog) {
                     int ox0 = north ? x : intoA ? x : x - 1, oy0 = north ? (intoA ? y : y - 1) : y; // the outside square (if lit)
                     Log.info("god rays: aperture " + x + "," + y + "," + z + (north ? " N" : " W") + " type " + type + " roomA " + roomA + " roomB " + roomB
                        + String.format(java.util.Locale.ROOT, " along %.2f", along) + (intoB ? " intoB" : intoA ? " intoA" : " skip")
                        + " above(A) " + Integer.toHexString(north ? occCpu(x, y - 1, z + 1) : occCpu(x - 1, y, z + 1) & 0xff)
                        + " above(B) " + Integer.toHexString(occCpu(x, y, z + 1) & 0xff) + " out " + ox0 + "," + oy0);
                  }
                  if (!intoB && !intoA) {
                     continue;
                  }
                  // the aperture: a W edge spans y..y+1 at x, an N edge x..x+1 at y
                  // (a closed door: its glass, measured from the art, DoorGlass)
                  int gb = packed >>> 16;
                  boolean glass = (o & (north ? B_GLASS_N : B_GLASS_W)) != 0 && gb != 0;
                  float m = type == E_WINDOW ? 0.1F : 0.15F;
                  int run = 1; // godRaysDoorMerge: the doorways of one wide door (a garage door, a double door) as one opening
                  if (type == E_DOOR && !glass && Config.GOD_RAYS_DOOR_MERGE) {
                     if (openDoorway(north ? x - 1 : x, north ? y : y - 1, z, north)) {
                        continue; // the run's first tile made the volume
                     }
                     while (run < 8 && openDoorway(north ? x + run : x, north ? y : y + run, z, north)) {
                        run++;
                     }
                  }
                  float aLo = glass ? (gb & 15) / 15F : m, aHi = glass ? (gb >> 4 & 15) / 15F : run - m;
                  float zs = glass ? (gb >> 8 & 15) / 15F : type == E_WINDOW ? 0.30F : 0.0F;
                  float zh = glass ? (gb >> 12 & 15) / 15F : type == E_WINDOW ? 0.84F : 0.80F;
                  // the outer side's light: from just outside the aperture's middle towards the light
                  float ox = north ? x + 0.5F * (aLo + aHi) : x + (intoB ? -0.02F : 0.02F), oy = north ? y + (intoB ? -0.02F : 0.02F) : y + 0.5F * (aLo + aHi);
                  // godRaysRoofExact: a roof or floor right above the outside (a porch, an awning): how many squares out it
                  // reaches; the light comes in under its edge (a plane of the volume) and is looked for out there
                  int coverK = 0;
                  float inX = north ? 0F : intoB ? 1F : -1F, inY = north ? intoB ? 1F : -1F : 0F;
                  if (Config.GOD_RAYS_ROOF_EXACT) {
                     int cxq = (int)Math.floor(north ? x + 0.5F * (aLo + aHi) : intoB ? x - 1 : x), cyq = (int)Math.floor(north ? intoB ? y - 1 : y : y + 0.5F * (aLo + aHi));
                     while (coverK < 4 && (occCpu(cxq - (int)inX * coverK, cyq - (int)inY * coverK, z + 1) & (B_FLOOR | B_ROOF)) != 0) {
                        coverK++;
                     }
                  }
                  float vis = coverK > 0
                     ? walk((north ? x + 0.5F * (aLo + aHi) : x) - inX * (coverK + 0.05F), (north ? y : y + 0.5F * (aLo + aHi)) - inY * (coverK + 0.05F), z + 0.25F, dx, dy, slope, 64F, true)
                     : walk(ox, oy, z + 0.5F * (zs + zh), dx, dy, slope, 64F, true);
                  if (coverK > 0) {
                     float a = Math.abs(north ? dy : dx), sk = slope * LEVEL / Math.max(a, 0.05F);
                     float w0 = (north ? y : x) - (north ? f.refY : f.refX); // the wall line, from the reference
                     float inW = (north ? inY : inX) * w0;
                     overhang[0] = -sk * inX;
                     overhang[1] = -sk * inY;
                     overhang[2] = -1F;
                     overhang[3] = (z + 1) * LEVEL + sk * (inW - coverK);
                  }
                  if (vis < 0.05F) {
                     if (rigDoor) {
                        Log.info(String.format(java.util.Locale.ROOT, "god rays: rig door dark vis %.2f", vis));
                     }
                     if (apLog) {
                        Log.info(String.format(java.util.Locale.ROOT, "god rays:   dark vis %.2f", vis));
                     }
                     continue;
                  }
                  // how far the light goes into the room before a wall or the floor stops it (from the aperture's middle)
                  float amid = 0.5F * (aLo + aHi);
                  float amx = north ? x + amid : x, amy = north ? y : y + amid; // the aperture's middle
                  float ix = north ? amx : x + (intoB ? 0.02F : -0.02F), iy = north ? y + (intoB ? 0.02F : -0.02F) : amy;
                  float tWall = walk(ix, iy, z + 0.5F * (zs + zh), -dx, -dy, -slope, 24F, false);
                  float cap = Math.max(0.05F, tWall + 0.02F);
                  if (Config.GOD_RAYS_AP_CLIP) {
                     // the nearest wall or edge of the building that a ray through the aperture (its two ends and middle, at
                     // mid height and at the head) meets before the floor: the middle ray alone let a low sun's upper rays
                     // through the far wall. (Walls are full height: a ray from the sill meets nothing the one above it on the
                     // same path does not meet first.)
                     float near = 24F;
                     for (int ia = 0; ia < 3; ia++) {
                        float a = ia == 0 ? aLo : ia == 1 ? 0.5F * (aLo + aHi) : aHi;
                        float rx = north ? x + a : ix, ry = north ? iy : y + a;
                        float off = dx * (rx - amx) + dy * (ry - amy); // this ray's start ahead of the middle's, down the light
                        for (int iz = 1; iz < 3; iz++) {
                           float rz = z + zs + (zh - zs) * 0.5F * iz;
                           float tFloor = (rz - z) / slope;
                           float tMax = Math.min(tFloor, near + off); // past that it cannot shorten the volume
                           float tr = reach(rx, ry, rz, z, -dx, -dy, slope, tMax);
                           if (tr < tMax) {
                              near = tr - off; // measured from the aperture's middle
                           }
                        }
                     }
                     cap = Math.max(0.05F, near);
                  }
                  float[] c = new float[24]; // near corners 0-3 then far corners 4-7: x, y, z
                  // soft edges (godRaysSoftPct): the shader ramps the light across each side over the penumbra, SOFT_W0
                  // either side of the frame's edge at the aperture and SOFT_K more a square away from it, so the shafts
                  // and their patches blur with the distance from the window; the drawn faces grow to cover it
                  float w0 = SOFT_W0 * Config.GOD_RAYS_SOFT_PCT / 100F, wk = SOFT_K * Config.GOD_RAYS_SOFT_PCT / 100F;
                  float across = north ? Math.abs(dy) : Math.abs(dx); // a square down the light's way: this far from the wall
                  for (int i = 0; i < 4; i++) {
                     float a = (i == 0 || i == 3) ? aLo : aHi; // along the edge
                     float cz = z + (i < 2 ? zs : zh);
                     float qx = north ? x + a : x, qy = north ? y : y + a;
                     float tFloor = (cz - z) / slope; // squares across until it reaches the floor
                     float t = Math.min(tFloor, Config.GOD_RAYS_AP_CLIP ? Math.max(0.02F, cap + dx * (qx - amx) + dy * (qy - amy)) : cap);
                     float sa = (i == 0 || i == 3) ? -1F : 1F, sz = i < 2 ? -1F : 1F;
                     float wn = 1.5F * w0, wf = 1.5F * (w0 + wk * across * t); // (conservative: the faces must cover the soft hull)
                     c[i * 3] = qx + (north ? sa * wn : 0F) - f.refX;
                     c[i * 3 + 1] = qy + (north ? 0F : sa * wn) - f.refY;
                     // (never under the floor: the faces are depth-tested against the scene, a face below the floor would
                     // drop the shaft's pixels above it; the floor half-space ends the shaft there anyway)
                     c[i * 3 + 2] = Math.max(z, cz + sz * wn / LEVEL);
                     c[12 + i * 3] = qx + (north ? sa * wf : 0F) - dx * t - f.refX;
                     c[12 + i * 3 + 1] = qy + (north ? 0F : sa * wf) - dy * t - f.refY;
                     c[12 + i * 3 + 2] = Math.max(z, cz + sz * wf / LEVEL - slope * t);
                     patchPts[i * 2] = qx - dx * t - f.refX; // (the hard ray's end: on the floor, or at the wall that stops it)
                     patchPts[i * 2 + 1] = qy - dy * t - f.refY;
                  }
                  int before = prismCount;
                  boolean leaf = type == E_DOOR && !glass && run == 1 && DoorLight.leaf(x, y, z, north, f.refX, f.refY, leafBuf);
                  addPrism(c, vis, z, amx - f.refX, amy - f.refY, cap, dx, dy, slope, leaf ? leafBuf : null, coverK > 0 ? overhang : null);
                  if (prismCount > before && Config.GOD_RAYS_BOUNCE_PCT > 0) {
                     bouncePatch(patchPts, z, before, north ? (intoB ? occCpu(x, y, z) : occCpu(x, y - 1, z)) : (intoB ? occCpu(x, y, z) : occCpu(x - 1, y, z)), vis);
                  }
                  if (prismCount > before) {
                     if (prismLeaf.length < prismCount) {
                        prismLeaf = java.util.Arrays.copyOf(prismLeaf, prisms.length / PRISM_FLOATS + 64);
                        leafAt = java.util.Arrays.copyOf(leafAt, prismLeaf.length * 4);
                     }
                     prismLeaf[before] = leaf ? before : -1;
                     if (leaf) {
                        leafAt[before * 4] = x;
                        leafAt[before * 4 + 1] = y;
                        leafAt[before * 4 + 2] = z;
                        leafAt[before * 4 + 3] = north ? 1 : 0;
                     }
                  }
                  if (rigDoor) {
                     Log.info(String.format(java.util.Locale.ROOT, "god rays: rig door lit vis %.2f cap %.2f across %.2f..%.2f up %.2f..%.2f prism %s", vis, cap, aLo, aHi, zs, zh, prismCount > before ? "yes" : "flat"));
                  }
                  if (apLog) {
                     Log.info(String.format(java.util.Locale.ROOT, "god rays:   lit vis %.2f tWall %.2f cap %.2f glass %b across %.2f..%.2f up %.2f..%.2f prism %s", vis, tWall, cap, glass, aLo, aHi, zs, zh, prismCount > before ? "yes" : "flat"));
                  }
                  if (prismCount > before) {
                     // the room's building: its interior shows only while the game cuts it away (else its roof or the
                     // floor above covers the light volume: culled on the CPU, see queueInner)
                     int rx = intoB ? x : north ? x : x - 1, ry = intoB ? y : north ? y - 1 : y;
                     IsoCell cell = IsoWorld.instance.currentCell;
                     IsoGridSquare rsq = cell != null ? cell.getGridSquare(rx, ry, z) : null;
                     if (prismDef.length < prisms.length / PRISM_FLOATS) {
                        prismDef = java.util.Arrays.copyOf(prismDef, prisms.length / PRISM_FLOATS);
                        prismLevel = java.util.Arrays.copyOf(prismLevel, prisms.length / PRISM_FLOATS);
                     }
                     prismDef[before] = rsq != null && rsq.getBuilding() != null ? rsq.getBuilding().getDef() : null;
                     prismLevel[before] = z;
                  }
               }
            }
         }
      }
      prismBuildNs += System.nanoTime() - t0;
   }

   /**
    * The 6 faces of the prism (near quad 0-3, far quad 4-7 with i + 4 the far end of corner i) with their sign. The faces
    * are wound consistently (a hexahedron's standard cycles), turned outward as a whole by the prism's signed volume, so a
    * thin or twisted prism (a window against a wall, corners stopping at different lengths) keeps its signs right: each
    * face's own centroid test had flipped some and left the +/- sums uncancelled (garbage blocks).
    */
   private static void addPrism(float[] c, float vis, int level, float ax, float ay, float tCap, float dx, float dy, float slope, float[] leaf, float[] over) {
      if (prismCount >= 4096) {
         return;
      }
      if ((prismCount + 1) * PRISM_FLOATS > prisms.length) {
         prisms = java.util.Arrays.copyOf(prisms, prisms.length * 2);
      }
      if ((prismCount + 1) * PLANE_FLOATS > planes.length) {
         planes = java.util.Arrays.copyOf(planes, planes.length * 2);
      }
      int[][] faces = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}};
      // signed volume (x6) of the closed surface: sum over the faces' triangles of p0 . (p1 x p2), z in squares
      double vol = 0.0;
      double mx = 0.0, my = 0.0, mz = 0.0;
      for (int i = 0; i < 8; i++) {
         mx += c[i * 3] / 8.0;
         my += c[i * 3 + 1] / 8.0;
         mz += c[i * 3 + 2] * LEVEL / 8.0;
      }
      for (int[] fc : faces) {
         for (int t = 1; t < 3; t++) {
            int i0 = fc[0], i1 = fc[t], i2 = fc[t + 1];
            double ax0 = c[i0 * 3], ay0 = c[i0 * 3 + 1], az0 = c[i0 * 3 + 2] * LEVEL;
            double bx = c[i1 * 3], by = c[i1 * 3 + 1], bz = c[i1 * 3 + 2] * LEVEL;
            double cx = c[i2 * 3], cy = c[i2 * 3 + 1], cz = c[i2 * 3 + 2] * LEVEL;
            vol += ax0 * (by * cz - bz * cy) - ay0 * (bx * cz - bz * cx) + az0 * (bx * cy - by * cx);
         }
      }
      if (Math.abs(vol) < 1e-6) {
         return; // flat: nothing inside
      }
      float flip = vol > 0.0 ? 1F : -1F;
      int idx = prismCount;
      // the prism as the intersection of half-spaces n . P + d >= 0 (squares, relative to the view origin): the aperture's
      // plane, the four sides (each spanned by an aperture edge and the light's travel), the floor, the cap where the
      // light meets a wall; every normal turned toward the prism's middle
      int pa = idx * PLANE_FLOATS;
      double ex = -dx, ey = -dy, ez = -slope * LEVEL; // the travel per square across
      int[][] sides = {{0, 1}, {1, 2}, {2, 3}, {3, 0}};
      double[][] pl = new double[7][];
      pl[0] = plane(c, 0, 1, 3, mx, my, mz);
      for (int k = 0; k < 4; k++) {
         int i = sides[k][0], j = sides[k][1];
         double ux = c[j * 3] - c[i * 3], uy = c[j * 3 + 1] - c[i * 3 + 1], uz = (c[j * 3 + 2] - c[i * 3 + 2]) * LEVEL;
         double nx = uy * ez - uz * ey, ny = uz * ex - ux * ez, nz = ux * ey - uy * ex;
         pl[1 + k] = orient(nx, ny, nz, c[i * 3], c[i * 3 + 1], c[i * 3 + 2] * LEVEL, mx, my, mz);
      }
      pl[5] = new double[] {0.0, 0.0, 1.0, -level * LEVEL}; // above the floor
      pl[6] = new double[] {dx, dy, 0.0, tCap - (ax * dx + ay * dy)}; // not past the wall the light meets
      for (int k = 0; k < 7; k++) {
         for (int q = 0; q < 4; q++) {
            planes[pa++] = (float)pl[k][q];
         }
      }
      planes[pa++] = vis;
      planes[pa++] = ax; // the aperture's middle (from the reference square), its level: the clouds' shade is looked up there every frame
      planes[pa++] = ay;
      planes[pa++] = level + 0.55F;
      // an open door's leaf (DoorLight.leaf): pivot x, y, bottom, top (squares); direction x, y, length, glass band (0 length: none)
      for (int q = 0; q < 8; q++) {
         planes[pa++] = leaf != null ? leaf[q] : 0F;
      }
      // a porch roof's edge (godRaysRoofExact): the light passes under it (none: always inside)
      for (int q = 0; q < 4; q++) {
         planes[pa++] = over != null ? over[q] : q == 3 ? 1F : 0F;
      }
      // the faces the view ray enters by (their outward normal toward the camera): each pixel of the prism once
      int at = idx * PRISM_FLOATS;
      int emitted = 0;
      for (int[] fc : faces) {
         float nx = 0F, ny = 0F, nz = 0F;
         for (int t = 1; t < 3; t++) {
            int i0 = fc[0], i1 = fc[t], i2 = fc[t + 1];
            float ux = c[i1 * 3] - c[i0 * 3], uy = c[i1 * 3 + 1] - c[i0 * 3 + 1], uz = (c[i1 * 3 + 2] - c[i0 * 3 + 2]) * LEVEL;
            float wx = c[i2 * 3] - c[i0 * 3], wy = c[i2 * 3 + 1] - c[i0 * 3 + 1], wz = (c[i2 * 3 + 2] - c[i0 * 3 + 2]) * LEVEL;
            nx += uy * wz - uz * wy;
            ny += uz * wx - ux * wz;
            nz += ux * wy - uy * wx;
         }
         if ((nx * 0.6124F + ny * 0.6124F + nz * 0.5F) * flip <= 0F) {
            continue;
         }
         int[] tri = {fc[0], fc[1], fc[2], fc[0], fc[2], fc[3]};
         for (int q : tri) {
            prisms[at++] = c[q * 3];
            prisms[at++] = c[q * 3 + 1];
            prisms[at++] = c[q * 3 + 2];
            prisms[at++] = idx;
         }
         emitted++;
      }
      // pad to the fixed stride with degenerate triangles (no fragments)
      while (at < idx * PRISM_FLOATS + PRISM_FLOATS) {
         prisms[at] = c[0];
         prisms[at + 1] = c[1];
         prisms[at + 2] = c[2];
         prisms[at + 3] = idx;
         at += 4;
      }
      prismCount++;
   }

   /** The plane through corners i, j, k (z scaled to squares), its normal turned toward (mx, my, mz). */
   private static double[] plane(float[] c, int i, int j, int k, double mx, double my, double mz) {
      double ux = c[j * 3] - c[i * 3], uy = c[j * 3 + 1] - c[i * 3 + 1], uz = (c[j * 3 + 2] - c[i * 3 + 2]) * LEVEL;
      double vx = c[k * 3] - c[i * 3], vy = c[k * 3 + 1] - c[i * 3 + 1], vz = (c[k * 3 + 2] - c[i * 3 + 2]) * LEVEL;
      return orient(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx, c[i * 3], c[i * 3 + 1], c[i * 3 + 2] * LEVEL, mx, my, mz);
   }

   private static double[] orient(double nx, double ny, double nz, double px, double py, double pz, double mx, double my, double mz) {
      double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (len < 1e-9) {
         return new double[] {0.0, 0.0, 0.0, 1.0}; // degenerate: always inside
      }
      nx /= len;
      ny /= len;
      nz /= len;
      double d = -(nx * px + ny * py + nz * pz);
      if (nx * mx + ny * my + nz * mz + d < 0.0) {
         nx = -nx;
         ny = -ny;
         nz = -nz;
         d = -d;
      }
      return new double[] {nx, ny, nz, d};
   }

   // ------------------------------------------------------------------------------------------------ per frame (game thread)

   static final class Frame extends TextureDraw.GenericDrawer {
      final Ssr.View view = new Ssr.View();
      boolean on, devOn = true, lightsOnly, froxelLights;
      boolean doorFx = true; // devGodRaysDoorAlternate's half: the door features (leaf, spill) on
      final float[] cap = new float[MAX_CAPS * 8]; // godRaysCharShadows: the characters' capsules in the light volumes (a xyz r, b xyz -; squares from the prisms' reference)
      int caps;
      final float[] bounce = new float[MAX_BOUNCES * 8]; // godRaysBounce: per patch (x, y from the prisms' reference, floor z squares, radius), (radiance rgb, room hash)
      int bounces;
      boolean occOnly; // entity shadows without the god rays: the occupancy alone
      final ArrayList<Upload> uploads = new ArrayList<>();
      int occZ0;
      int refX, refY; // the camera chunk's corner: the compute works relative to it
      float cu, cv, cz, zLo;
      int nx, ny, nz, i0, j0;
      float lx, ly, lz, slope;
      long lightKey;
      float sigmaOut, sigmaIn, hazeH, patch, dustGain;
      float r, g, b; // the light's colour x strength x phase
      int maxTop;
      boolean dump;
      String dumpTag;
      float[] prismData = new float[0];
      float[] planeData = new float[0];
      int prismOrgX, prismOrgY;
      final float[] la = new float[MAX_LIGHTS * 4], lb = new float[MAX_LIGHTS * 4], lc = new float[MAX_LIGHTS * 4];
      final float[] lp = new float[MAX_LIGHTS * 5 * 4]; // a spill's frustum through its opening (godRaysSpill): the opening's plane, its four sides
      final float[] lq = new float[MAX_LIGHTS * 4]; // a spill's opening's two ends (x, y from the reference square): its footprint
      int lights;
      byte[] tops; // the chunks' tops (levels above occZ0) when they changed this frame
      int prisms;
      boolean apertures;
      boolean haze; // the outdoor haze can show (else: no volume, no buffer, no buffer tap)
      int view_;
      int player;
      long prismGen = -1L; // the prism build its prismData / planeData copy (the render thread uploads them only when it changes)
      boolean planesDynamic; // the planes' light changes every frame (cloud shadows)

      @Override
      public void render() {
         long t0 = System.nanoTime();
         Gl.frame(this);
         this.uploads.clear();
         long dt = System.nanoTime() - t0;
         Gl.rtNs += dt;
         Gl.rtFrames++;
         Gl.rtHalf[this.doorFx ? 1 : 0] += dt;
         Gl.rtHalfN[this.doorFx ? 1 : 0]++;
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   static long frames;
   private static long toggleT0;
   private static boolean devOff;
   private static long doorT0;
   static boolean doorFxOn = true; // devGodRaysDoorAlternate: this frame's half (the door features' within-run A/B)
   private static long worldUpNs;
   private static float[] dumpAt;
   private static int dumpNext;
   private static float cuHeld = -1F;

   /** Game thread, FBORenderCell right before the fog: this frame's light, grid and occupancy for the render thread. */
   static long queueNs, occNs, prismNs, lightNs, queueFrames, spillNs, capNs, capFrames, capCount;
   static final long[] qHalf = new long[2], qHalfN = new long[2]; // devGodRaysDoorAlternate: game thread ns by half

   public static void queue(int playerIndex) {
      long q0 = System.nanoTime();
      try {
         queueInner(playerIndex);
      } finally {
         long dq = System.nanoTime() - q0;
         queueNs += dq;
         queueFrames++;
         qHalf[doorFxOn ? 1 : 0] += dq;
         qHalfN[doorFxOn ? 1 : 0]++;
      }
   }

   /**
    * Entity shadows with the god rays off (game thread): the occupancy round the camera kept the same way (same window,
    * same levels, same budget), uploaded by the render thread; no light, no volume.
    */
   private static void occupancyOnly(int playerIndex) {
      Frame f = FRAMES[frameIndex++ & 3];
      f.uploads.clear();
      f.on = false;
      f.lightsOnly = false;
      f.occOnly = true;
      f.player = playerIndex & 3;
      f.view.capture(playerIndex);
      int camLevel = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
      int camWx = Math.floorDiv(f.view.ox, 8), camWy = Math.floorDiv(f.view.oy, 8);
      f.refX = camWx * 8;
      f.refY = camWy * 8;
      long o0 = System.nanoTime();
      maintainOccupancy(f, camWx, camWy, Math.min(-1, camLevel - 4));
      occNs += System.nanoTime() - o0;
      f.occZ0 = occZ0;
      f.maxTop = Math.min(occZ0 + OCC_L, maxTopLevel + 1);
      SpriteRenderer.instance.drawGeneric(f);
   }

   private static int heldNx, heldNy;
   private static float heldCu = -1F;

   private static void queueInner(int playerIndex) {
      if (!wanted() && (EntityShadow.wantsOccupancy() || VoxelLight.wantsOccupancy())) {
         occupancyOnly(playerIndex);
         return;
      }
      if (!wanted()) {
         if (Gl.everOn) {
            Frame f = FRAMES[frameIndex++ & 3];
            f.on = false;
            f.uploads.clear();
            SpriteRenderer.instance.drawGeneric(f);
         }
         return;
      }
      frames++;
      Frame f = FRAMES[frameIndex++ & 3];
      f.uploads.clear();
      f.occOnly = false;
      f.player = playerIndex & 3;
      keyLight();
      f.on = strengthNow > 0.003F;
      f.lightsOnly = !f.on && Config.GOD_RAYS_LOCAL;
      if (Config.DEV_GOD_RAYS_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
            Log.info("god rays: alternating every " + Config.DEV_GOD_RAYS_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         boolean off = (now - toggleT0) / Config.DEV_GOD_RAYS_ALTERNATE % 2L == 1L;
         if (off != devOff) {
            devOff = off;
         }
      }
      f.devOn = !devOff;
      if (Config.DEV_GOD_RAYS_DOOR_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (doorT0 == 0L) {
            doorT0 = now;
            Log.info("god rays: door features alternating every " + Config.DEV_GOD_RAYS_DOOR_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         doorFxOn = (now - doorT0) / Config.DEV_GOD_RAYS_DOOR_ALTERNATE % 2L == 0L;
      }
      f.doorFx = doorFxOn;
      f.view.capture(playerIndex);
      // the grid: cells of ~godRaysCellPx screen pixels, sizes quantised to powers of two (with hysteresis) so a zoom only
      // rebuilds the volume when it crosses one
      float kA = f.view.zoom / (32F * f.view.ts); // u per screen pixel (the world FBO is screen-sized)
      float want = Math.max(1, Config.GOD_RAYS_CELL_PX) * kA;
      float q = (float)Math.pow(2.0, Math.round(Math.log(want) / Math.log(2.0)));
      if (cuHeld < 0F || want > cuHeld * 1.6F || want < cuHeld * 0.625F) {
         cuHeld = Math.max(0.0625F, Math.min(4F, q));
      }
      f.cu = cuHeld;
      f.cv = 2F * cuHeld;
      int slices = Math.max(2, Math.min(32, Config.GOD_RAYS_SLICES));
      f.cz = 1F / slices;
      int camLevel = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
      int zLo = camLevel < 0 ? camLevel : camLevel < Config.GOD_RAYS_LEVELS ? 0 : camLevel - Config.GOD_RAYS_LEVELS + 1;
      f.zLo = zLo;
      f.nz = Math.max(4, Math.min(128, Config.GOD_RAYS_LEVELS * slices));
      float uw = f.view.screenW * kA, vh = f.view.screenH * f.view.zoom / (16F * f.view.ts);
      // the grid's size in steps of 16 cells, grown at once and shrunk only when a quarter too big: the camera's zoom eases
      // (in a car it breathes all the time) and every one-column change reallocated and recomputed the whole volume
      // (651 times in 45 s in the maintainer's save)
      int needX = (int)Math.ceil(uw / f.cu) + 4, needY = (int)Math.ceil(vh / f.cv) + 4;
      if (heldNx < needX || needX < heldNx * 3 / 4 || heldCu != f.cu) {
         heldNx = (needX + 15) & ~15;
      }
      if (heldNy < needY || needY < heldNy * 3 / 4 || heldCu != f.cu) {
         heldNy = (needY + 15) & ~15;
      }
      heldCu = f.cu;
      f.nx = heldNx;
      f.ny = heldNy;
      int camWx = Math.floorDiv(f.view.ox, 8), camWy = Math.floorDiv(f.view.oy, 8);
      f.refX = camWx * 8;
      f.refY = camWy * 8;
      // the screen's top-left in (u, v): the view's mapping at pixel (0, top)
      double uLeft = f.view.offX / (32.0 * f.view.ts); // absolute (Ssr.View.mapping: u at the viewport's left edge)
      double vTop = f.view.offY / (16.0 * f.view.ts);
      f.i0 = (int)Math.floor(uLeft / f.cu) - 2;
      f.j0 = (int)Math.floor(vTop / f.cv) - 2;
      int wantZ0 = Math.min(-1, camLevel - 4);
      long o0 = System.nanoTime();
      maintainOccupancy(f, camWx, camWy, wantZ0);
      occNs += System.nanoTime() - o0;
      f.occZ0 = occZ0;
      f.maxTop = Math.min(occZ0 + OCC_L, maxTopLevel + 1);
      f.lx = light[0];
      f.ly = light[1];
      f.lz = light[2];
      double h = Math.hypot(light[0], light[1]);
      f.apertures = Config.GOD_RAYS_APERTURES && f.on;
      // the haze's largest in-scatter (a column through all of it) against a 1 % floor: a clear day has none worth a pass
      float hazeMax = Math.max(lightColor[0], Math.max(lightColor[1], lightColor[2])) * phase * (1F - (float)Math.exp(-sigmaOut * 1.2F * VIEW_PATH));
      f.haze = f.on && hazeMax > 0.015F || !f.apertures || Config.DEV_GOD_RAYS_VIEW > 0 && Config.DEV_GOD_RAYS_VIEW < 5;
      f.froxelLights = false;
      f.slope = h < 1e-3 ? 0F : (float)(light[2] / h / LEVEL);
      // the light's direction quantised (a new visibility when it turned ~0.4 deg)
      f.lightKey = Math.round(Math.atan2(light[1], light[0]) * 150.0) * 100003L + Math.round(Math.asin(clamp(light[2], -1, 1)) * 150.0);
      f.sigmaOut = sigmaOut;
      f.sigmaIn = sigmaIn;
      f.hazeH = 1.2F;
      f.patch = 1.6F * Config.GOD_RAYS_PATCH_PCT / 100F * strengthNow;
      f.dustGain = 1F;
      f.r = lightColor[0] * phase;
      f.g = lightColor[1] * phase;
      f.b = lightColor[2] * phase;
      f.view_ = Config.DEV_GOD_RAYS_VIEW;
      f.prisms = 0;
      if (f.apertures) {
         // the screen's world rectangle (conservative: its (u, v) corners at the volume's lowest and highest level)
         double u0 = f.i0 * (double)f.cu, u1 = (f.i0 + f.nx) * (double)f.cu, v0 = f.j0 * (double)f.cv, v1 = (f.j0 + f.ny) * (double)f.cv;
         double zA = f.zLo, zB = f.zLo + f.nz * f.cz;
         double x0 = (v0 + 6 * zA + u0) / 2, x1 = (v1 + 6 * zB + u1) / 2, y0 = (v0 + 6 * zA - u1) / 2, y1 = (v1 + 6 * zB - u0) / 2;
         long p0 = System.nanoTime();
         buildPrisms(f, (float)x0, (float)y0, (float)x1, (float)y1);
         boolean leavesMoved = swingLeaves();
         boolean puff = DoorLight.anyPuff();
         prismNs += System.nanoTime() - p0;
         if (prismCount > 0) {
            boolean clouds = CloudShadow.enabled();
            // the rooms on screen: a building's interior shows only while the game cuts it away, on the player's level
            // (anywhere else its roof or the floor above covers it); the others are not drawn at all (in a street every
            // light volume on screen is under a roof)
            int playerZ = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
            zombie.iso.fboRenderChunk.FBORenderCutaways cut = Config.GOD_RAYS_AP_CULL ? zombie.iso.fboRenderChunk.FBORenderCutaways.getInstance() : null;
            long key = prismBuilds * 1000003L;
            int shown = 0;
            for (int pi = 0; pi < prismCount; pi++) {
               boolean vis = cut == null || prismDef[pi] == null || prismLevel[pi] == playerZ && cut.isBuildingCollapsed(prismDef[pi]);
               if (vis) {
                  key = key * 31L + pi + 1;
                  shown++;
               }
            }
            if (f.prismGen != key || clouds || leavesMoved || puff || puffLast) { // (each of the four frame slots keeps its copy until the set changes)
               if (f.prismData.length < shown * PRISM_FLOATS) {
                  f.prismData = new float[prisms.length];
               }
               if (f.planeData.length < shown * PLANE_FLOATS) {
                  f.planeData = new float[planes.length];
               }
               int j = 0;
               for (int pi = 0; pi < prismCount; pi++) {
                  if (!(cut == null || prismDef[pi] == null || prismLevel[pi] == playerZ && cut.isBuildingCollapsed(prismDef[pi]))) {
                     continue;
                  }
                  System.arraycopy(prisms, pi * PRISM_FLOATS, f.prismData, j * PRISM_FLOATS, PRISM_FLOATS);
                  for (int v = j * PRISM_FLOATS + 3; v < (j + 1) * PRISM_FLOATS; v += 4) {
                     f.prismData[v] = j; // the vertices' prism index: its planes' place in the compacted list
                  }
                  System.arraycopy(planes, pi * PLANE_FLOATS, f.planeData, j * PLANE_FLOATS, PLANE_FLOATS);
                  f.planeData[j * PLANE_FLOATS + 29] = 0F; // (pl7.y / .z hold the aperture's middle for the CPU's cloud lookup; on the GPU they are
                  f.planeData[j * PLANE_FLOATS + 30] = -1F; // the characters' capsule mask and the door dust's age: none until set below)
                  if (clouds) {
                     int b = j * PLANE_FLOATS + 28, a = pi * PLANE_FLOATS + 28;
                     f.planeData[b] = planes[a] * CloudShadow.transmittanceAt(planes[a + 1] + prismRefX, planes[a + 2] + prismRefY, planes[a + 3]);
                  }
                  j++;
               }
               f.prismGen = key;
            }
            f.planesDynamic = clouds || leavesMoved || puff || puffLast;
            if (puff || puffLast) {
               // the dust a door stirs (godRaysDoorDustPct): seconds since its door opened, into the copy's pl7.z (the shader's
               // only use of it; -1 none)
               int j = 0;
               for (int pi = 0; pi < prismCount; pi++) {
                  if (!(cut == null || prismDef[pi] == null || prismLevel[pi] == playerZ && cut.isBuildingCollapsed(prismDef[pi]))) {
                     continue;
                  }
                  f.planeData[j * PLANE_FLOATS + 30] = puff && prismLeaf[pi] >= 0 ? DoorLight.openedAgo(leafAt[pi * 4], leafAt[pi * 4 + 1], leafAt[pi * 4 + 2], leafAt[pi * 4 + 3] == 1) : -1F;
                  j++;
               }
            }
            puffLast = puff;
            f.prisms = shown;
            f.prismOrgX = prismRefX - f.view.ox; // the prisms' reference square relative to the view origin
            f.prismOrgY = prismRefY - f.view.oy;
            f.bounces = 0;
            if (Config.GOD_RAYS_BOUNCE_PCT > 0) {
               // the biggest sunlit patches of the volumes shown (their light after the clouds)
               int j = 0;
               for (int pi = 0; pi < prismCount && f.bounces < MAX_BOUNCES; pi++) {
                  if (!(cut == null || prismDef[pi] == null || prismLevel[pi] == playerZ && cut.isBuildingCollapsed(prismDef[pi]))) {
                     continue;
                  }
                  float vis = f.planeData[j * PLANE_FLOATS + 28];
                  j++;
                  if (bnc.length <= pi * 6 + 5 || bnc[pi * 6 + 3] <= 0F || vis <= 0.05F) {
                     continue;
                  }
                  float w = bnc[pi * 6 + 4] / Math.max(planes[pi * PLANE_FLOATS + 28], 1e-3F) * vis; // (the cloud shade of this frame)
                  float g = w * 0.35F * Config.GOD_RAYS_BOUNCE_PCT / 100F * 0.22F; // floor albedo ~0.35; the disk's radiance a unit of patch
                  int k = f.bounces * 8;
                  f.bounce[k] = bnc[pi * 6];
                  f.bounce[k + 1] = bnc[pi * 6 + 1];
                  f.bounce[k + 2] = bnc[pi * 6 + 2] * LEVEL;
                  f.bounce[k + 3] = bnc[pi * 6 + 3];
                  f.bounce[k + 4] = lightColor[0] * g / Math.max(1e-3F, bnc[pi * 6 + 3] * bnc[pi * 6 + 3]);
                  f.bounce[k + 5] = lightColor[1] * g / Math.max(1e-3F, bnc[pi * 6 + 3] * bnc[pi * 6 + 3]);
                  f.bounce[k + 6] = lightColor[2] * g / Math.max(1e-3F, bnc[pi * 6 + 3] * bnc[pi * 6 + 3]);
                  f.bounce[k + 7] = bnc[pi * 6 + 5];
                  f.bounces++;
               }
            }
            f.caps = 0;
            if (Config.GOD_RAYS_CHAR_SHADOWS) {
               long c0 = System.nanoTime();
               characterCaps(f);
               capNs += System.nanoTime() - c0;
            }
         }
      }
      long l0 = System.nanoTime();
      f.lights = Config.GOD_RAYS_LOCAL && f.on || Config.GOD_RAYS_LOCAL && !f.on && Config.GOD_RAYS_LOCAL_AT_NIGHT ? gatherLights(f) : 0;
      lightNs += System.nanoTime() - l0;
      if (f.lights > 0 && "froxel".equals(Config.GOD_RAYS_LOCAL_METHOD)) {
         // froxel injection: the lights ride the volume and the god ray buffer (the full pipeline, even without a sun)
         f.froxelLights = true;
         f.haze = true;
         if (!f.on) {
            f.on = true;
            f.r = f.g = f.b = 0F; // no key light's in-scatter
            f.patch = 0F;
            f.apertures = false;
         }
      }
      f.dump = false;
      if (!Config.DEV_GOD_RAYS_DUMP_AT.isEmpty()) {
         long now = System.nanoTime();
         if (worldUpNs == 0L) {
            worldUpNs = now;
            String[] parts = Config.DEV_GOD_RAYS_DUMP_AT.split(",");
            dumpAt = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
               dumpAt[i] = Float.parseFloat(parts[i].trim());
            }
         }
         if (dumpNext < dumpAt.length && (now - worldUpNs) / 1e9 >= dumpAt[dumpNext]) {
            f.dump = true;
            f.dumpTag = "t" + (int)dumpAt[dumpNext++];
         }
      }
      if (frames % 1200 == 1) {
         Log.info("god rays: " + stats());
      }
      SpriteRenderer.instance.drawGeneric(f);
   }

   static final int MAX_LIGHTS = 16;
   static final int MAX_CAPS = 16; // godRaysCharShadows: capsules a frame (two a character)
   static final int MAX_BOUNCES = 8; // godRaysBounce: sunlit patches a frame
   private static long capMaskHash;

   /**
    * godRaysCharShadows: the characters standing where they can shade a light volume on screen, as two capsules each
    * (body with head, legs), and per volume the bit mask of the capsules whose shadow (swept from them away from the light)
    * reaches its box: written into the frame's plane copy (pl7.y; the shader reads only pl7.x there), uploaded when a mask
    * changes. The capsules themselves go up as uniforms every frame (they move).
    */
   private static void characterCaps(Frame f) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null || f.prisms == 0) {
         return;
      }
      double h = Math.hypot(f.lx, f.ly);
      float ldx = (float)(f.lx), ldy = (float)(f.ly), ldz = f.lz; // to the light (squares)
      // the volumes' boxes (squares from the prisms' reference), from their drawn faces
      int vpp = PRISM_FLOATS / 4;
      if (pbox.length < f.prisms * 6) {
         pbox = new float[f.prisms * 6 + 60];
      }
      if (boxGen != f.prismGen) { // (the boxes of this set of volumes: once a build)
      boxGen = f.prismGen;
      ax0 = Float.MAX_VALUE; ay0 = Float.MAX_VALUE; ax1 = -Float.MAX_VALUE; ay1 = -Float.MAX_VALUE;
      for (int pi = 0; pi < f.prisms; pi++) {
         float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
         for (int v = pi * vpp; v < pi * vpp + vpp; v++) {
            float x = f.prismData[v * 4], y = f.prismData[v * 4 + 1], z = f.prismData[v * 4 + 2] * LEVEL;
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            z0 = Math.min(z0, z); z1 = Math.max(z1, z);
         }
         pbox[pi * 6] = x0; pbox[pi * 6 + 1] = y0; pbox[pi * 6 + 2] = z0; pbox[pi * 6 + 3] = x1; pbox[pi * 6 + 4] = y1; pbox[pi * 6 + 5] = z1;
         ax0 = Math.min(ax0, x0); ay0 = Math.min(ay0, y0); ax1 = Math.max(ax1, x1); ay1 = Math.max(ay1, y1);
      }
      }
      // a character shades a volume when it stands in it or between it and the light: within the volumes' box grown toward the light
      float reachS = 3F; // (squares: a body 3 squares up-light of a volume can still shade its upper part)
      int n = 0;
      // the characters that can shade a volume: chosen every 8th frame from the players and the cell's zombies within the
      // volumes' box grown toward the light (the list walk is the cost), their capsules from where they stand every frame
      long cp0 = System.nanoTime();
      if (capPick++ % 8 == 0 || capPickKey != prismBuilds) {
         capPickKey = prismBuilds;
         capChars.clear();
         java.util.ArrayList<zombie.characters.IsoZombie> zs = cell.getZombieList();
         int np = IsoPlayer.players.length;
         float gx0 = ax0 - 0.5F + Math.min(0F, ldx) * reachS - 1F, gx1 = ax1 + 0.5F + Math.max(0F, ldx) * reachS + 1F; // (+1: they walk meanwhile)
         float gy0 = ay0 - 0.5F + Math.min(0F, ldy) * reachS - 1F, gy1 = ay1 + 0.5F + Math.max(0F, ldy) * reachS + 1F;
         for (int i = 0; i < np + zs.size() && capChars.size() < MAX_CAPS / 2; i++) {
            IsoMovingObject o = i < np ? IsoPlayer.players[i] : zs.get(i - np);
            if (o == null) {
               continue;
            }
            float x = o.getX() - prismRefX, y = o.getY() - prismRefY;
            if (x >= gx0 && x <= gx1 && y >= gy0 && y <= gy1 && o instanceof IsoGameCharacter ch) {
               capChars.add(ch);
            }
         }
      }
      capPickNs += System.nanoTime() - cp0;
      for (int i = 0; i < capChars.size() && n + 2 <= MAX_CAPS; i++) {
         IsoGameCharacter ch = capChars.get(i);
         if (ch.getCurrentSquare() == null || ch.isInvisible() && !(ch instanceof IsoPlayer) || ch.getVehicle() != null) {
            continue;
         }
         n = addCaps(f, ch, n);
      }
      f.caps = n;
      capFrames++;
      capCount += n;
      // the masks: a capsule's shadow reaches a volume when the segment from it away from the light (6 squares, or to its
      // floor) passes the volume's box grown by its radius; again only when a capsule moved a tenth of a square (or the set)
      long ck = boxGen * 31L + n;
      for (int i = 0; i < n * 8; i++) {
         ck = ck * 1000003L + Math.round(f.cap[i] * 10F);
      }
      if (ck == maskKey && lastMasks.length >= f.prisms) {
         for (int pi = 0; pi < f.prisms; pi++) {
            f.planeData[pi * PLANE_FLOATS + 29] = lastMasks[pi];
         }
         return;
      }
      maskKey = ck;
      if (lastMasks.length < f.prisms) {
         lastMasks = new int[f.prisms + 32];
      }
      long hash = 1469598103934665603L;
      for (int pi = 0; pi < f.prisms; pi++) {
         int mask = 0;
         for (int ci = 0; ci < n; ci++) {
            int k = ci * 8;
            float r = f.cap[k + 3] + 0.25F;
            float cx = 0.5F * (f.cap[k] + f.cap[k + 4]), cy = 0.5F * (f.cap[k + 1] + f.cap[k + 5]), cz = Math.max(f.cap[k + 2], f.cap[k + 6]);
            float t = 6F;
            if (ldz > 1e-3F) {
               t = Math.min(t, (cz - f.cap[k + 2] + 1.5F) / ldz); // (down-light of it the shadow reaches the floor within this)
            }
            float ex = cx - ldx * t, ey = cy - ldy * t;
            float sx0 = Math.min(cx, ex) - r, sx1 = Math.max(cx, ex) + r, sy0 = Math.min(cy, ey) - r, sy1 = Math.max(cy, ey) + r;
            if (sx1 >= pbox[pi * 6] && sx0 <= pbox[pi * 6 + 3] && sy1 >= pbox[pi * 6 + 1] && sy0 <= pbox[pi * 6 + 4]) {
               mask |= 1 << ci;
            }
         }
         f.planeData[pi * PLANE_FLOATS + 29] = mask;
         lastMasks[pi] = mask;
         hash = (hash ^ mask) * 1099511628211L;
      }
      if (hash != capMaskHash) {
         capMaskHash = hash;
         f.planesDynamic = true;
      }
   }

   private static float[] pbox = new float[64 * 6];
   private static long boxGen = Long.MIN_VALUE, maskKey = Long.MIN_VALUE;
   private static float ax0, ay0, ax1, ay1; // the shown volumes' box (squares from the prisms' reference)
   private static int[] lastMasks = new int[64];
   private static final java.util.ArrayList<IsoGameCharacter> capChars = new java.util.ArrayList<>();
   private static long capPick, capPickKey = -1L;
   static long capPickNs, pollNs;

   /** A character's capsules (lying: one along its facing; standing: body with head, legs), from the prisms' reference. */
   private static int addCaps(Frame f, IsoGameCharacter ch, int n) {
      float x = ch.getX() - prismRefX, y = ch.getY() - prismRefY, z = ch.getZ() * LEVEL;
      boolean down = ch.isOnFloor() || ch instanceof zombie.characters.IsoZombie zz && zz.isCrawling(); // (fields: isProne walks the state machine)
      float[] c = f.cap;
      int k = n * 8;
      if (down) {
         zombie.iso.Vector2 dir = ch.getForwardDirection();
         float fx = dir != null ? dir.x : 1F, fy = dir != null ? dir.y : 0F;
         c[k] = x - fx * 0.6F; c[k + 1] = y - fy * 0.6F; c[k + 2] = z + 0.18F; c[k + 3] = 0.2F;
         c[k + 4] = x + fx * 0.6F; c[k + 5] = y + fy * 0.6F; c[k + 6] = z + 0.18F; c[k + 7] = 0F;
         return n + 1;
      }
      // standing: the body and head, the legs (a 1.8 m figure is ~1.5 squares tall)
      c[k] = x; c[k + 1] = y; c[k + 2] = z + 0.85F; c[k + 3] = 0.21F;
      c[k + 4] = x; c[k + 5] = y; c[k + 6] = z + 1.38F; c[k + 7] = 0F;
      c[k + 8] = x; c[k + 9] = y; c[k + 10] = z + 0.1F; c[k + 11] = 0.15F;
      c[k + 12] = x; c[k + 13] = y; c[k + 14] = z + 0.8F; c[k + 15] = 0F;
      return n + 2;
   }
   static final int DRAWN_LIGHTS = 8; // the nearest this many visible ones
   private static final float[] candD = new float[1024];
   private static final int[] candI = new int[1024];
   private static final float[][] cand = new float[1024][], candPool = new float[1024][11];
   // the cell's lampposts near the camera (the cell lists all of them: thousands in a town), re-listed as the camera moves
   private static final java.util.ArrayList<zombie.iso.IsoLightSource> nearLamps = new java.util.ArrayList<>();
   private static float nearCx = Float.NaN, nearCy, nearView;
   private static int nearSize = -1, nearAge;
   private static Object nearList;

   private static float[] cand(int n, float a, float b, float c, float d, float e, float f, float g, float h, float i, float j, float k) {
      float[] v = candPool[n];
      v[0] = a; v[1] = b; v[2] = c; v[3] = d; v[4] = e; v[5] = f; v[6] = g; v[7] = h; v[8] = i; v[9] = j; v[10] = k;
      return v;
   }

   /**
    * The local lights near the view for their airlight (torches and vehicle lights as the game sends them to the lighting
    * native, then the active lamps and fires), nearest first: a = (x, y relative to the prisms' reference square, z
    * levels, reach), b = (axis x, axis y, cone cosine or -2 for a point light, the medium's density there), c = (colour x
    * strength, kind). The density: a room's dust indoors, the weather's haze outside (fog and rain light the beams).
    */
   private static int gatherLights(Frame f) {
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float view = (f.view.screenW + 2F * f.view.screenH) * f.view.zoom / (64F * f.view.ts) + 4F;
      ClimateManager cm = ClimateManager.getInstance();
      float fog = cm != null ? clamp01(cm.getFogIntensity()) : 0F, rain = cm != null ? clamp01(cm.getPrecipitationIntensity()) : 0F;
      float outside = 0.0015F * (1F + 40F * fog + 25F * rain) * Config.GOD_RAYS_HAZE_PCT / 100F;
      // indoors the air is clear air (no weather): the room dust of the sunbeams (0.03 a square, 20x) lit a whole ball of air
      // round every lamp in a room or under a shelter roof, the maintainer's "floating orbs of light"; dust only shows in a
      // shaft of sun, a lamp in clear air does not glow
      float inside = 0.0015F * Config.GOD_RAYS_HAZE_PCT / 100F;
      int n = 0;
      float dayNow = cm != null ? clamp01(cm.getDayLightStrength()) : 1F;
      if (dayNow > 0.6F) {
         return 0; // in daylight a torch's or a lamp's glow does not show
      }
      // the brightest glow any light could have in this air (the white light's peak, below): under the gate, none is drawn
      // and nothing is scanned (a clear night)
      if (Math.max(outside, inside) * (float)Math.PI / Math.max(0.05F, Config.GOD_RAYS_LOCAL_CORE_PCT / 100F) * Config.GOD_RAYS_LOCAL_PCT / 100F < 0.012F) {
         return 0;
      }
      java.util.ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      TorchSource.refresh(torches); // torchSource: this frame's lens
      for (int i = 0; i < torches.size() && n < cand.length; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         float tx = TorchSource.x(t), ty = TorchSource.y(t); // torchSource: the drawn item's lens (else the native's position)
         if (t.id == 0 || Math.abs(tx - cx) > view + t.dist || Math.abs(ty - cy) > view + t.dist) {
            continue;
         }
         float len = (float)Math.hypot(t.angleX, t.angleY);
         boolean vehicle = t.id >= 4096;
         float z = t.z + (vehicle ? 0.22F : TorchSource.height(t, 0.45F));
         cand[n] = cand(n, tx, ty, z, Math.max(1F, Math.min(10F, t.dist)), len > 1e-4F ? t.angleX / len : 0F, len > 1e-4F ? t.angleY / len : 0F,
            t.cone ? Math.max(0.2F, t.dot) : -2F, t.r * t.strength, t.g * t.strength, t.b * t.strength, vehicle ? 2F : 1F);
         candD[n] = (tx - cx) * (tx - cx) + (ty - cy) * (ty - cy);
         n++;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      java.util.Stack<zombie.iso.IsoLightSource> all = cell != null ? cell.getLamppostPositions() : null;
      if (all == null) {
         nearLamps.clear();
      } else if (all != nearList || all.size() != nearSize || ++nearAge > 60 || Math.abs(cx - nearCx) > 4F || Math.abs(cy - nearCy) > 4F || view > nearView) {
         // re-list with a margin (4 squares of camera travel, the widest reach) so the per-frame test below stays exact
         nearList = all;
         nearSize = all.size();
         nearAge = 0;
         nearCx = cx;
         nearCy = cy;
         nearView = view + 2F;
         nearLamps.clear();
         for (int i = 0; i < all.size(); i++) {
            zombie.iso.IsoLightSource l = all.get(i);
            if (Math.abs(l.x + 0.5F - cx) <= nearView + 10F && Math.abs(l.y + 0.5F - cy) <= nearView + 10F) {
               nearLamps.add(l);
            }
         }
      }
      java.util.ArrayList<zombie.iso.IsoLightSource> list = nearLamps;
      for (int i = 0; i < list.size() && n < cand.length; i++) {
         zombie.iso.IsoLightSource l = list.get(i);
         if (!l.active || l.radius <= 0) {
            continue;
         }
         float dx = l.x + 0.5F - cx, dy = l.y + 0.5F - cy;
         float r = Math.min(l.radius, 6); // the airlight's reach (1 / d^2: the glow beyond it does not show; the light itself reaches further)
         if (Math.abs(dx) > view + r || Math.abs(dy) > view + r) {
            continue;
         }
         cand[n] = cand(n, l.x + 0.5F, l.y + 0.5F, l.z + 0.75F, r, 0F, 0F, -2F, Math.min(1F, l.r * 2F), Math.min(1F, l.g * 2F), Math.min(1F, l.b * 2F), 0F);
         candD[n] = dx * dx + dy * dy;
         n++;
      }
      int count = 0;
      while (count < DRAWN_LIGHTS && n > 0) {
         int best = 0;
         for (int i = 1; i < n; i++) {
            if (candD[i] < candD[best]) {
               best = i;
            }
         }
         float[] c = cand[best];
         cand[best] = cand[--n];
         candD[best] = candD[n];
         int k = count * 4;
         int sx = (int)Math.floor(c[0]), sy = (int)Math.floor(c[1]), sz = (int)Math.floor(c[2]);
         boolean room = (occCpu(sx, sy, sz) & B_ROOM) != 0;
         f.la[k] = c[0] - f.refX;
         f.la[k + 1] = c[1] - f.refY;
         f.la[k + 2] = c[2];
         f.la[k + 3] = c[3];
         f.lb[k] = c[4];
         f.lb[k + 1] = c[5];
         f.lb[k + 2] = c[6];
         f.lb[k + 3] = room ? inside : outside;
         float g = Config.GOD_RAYS_LOCAL_PCT / 100F;
         // the brightest its airlight gets (a ray grazing the light at half a square: pi / 0.5 of 1 / d^2 along it): a lamp
         // in clear night air (a trace of haze) would not show and is not drawn
         float peak = f.lb[k + 3] * (float)Math.PI / Math.max(0.05F, Config.GOD_RAYS_LOCAL_CORE_PCT / 100F) * Math.max(c[7], Math.max(c[8], c[9])) * g;
         if (peak < 0.012F) {
            continue;
         }
         f.lc[k] = c[7] * g;
         f.lc[k + 1] = c[8] * g;
         f.lc[k + 2] = c[9] * g;
         f.lc[k + 3] = c[10];
         count++;
      }
      if (Config.GOD_RAYS_SPILL && f.doorFx) {
         long s0 = System.nanoTime();
         count = DoorLight.spills(f, count, outside);
         spillNs += System.nanoTime() - s0;
      }
      return count;
   }

   static String stats() {
      long qf = Math.max(1L, queueFrames);
      String cpu = String.format(java.util.Locale.ROOT, "game thread %.1f us/frame (occupancy %.1f, prisms %.1f, lights %.1f, spills %.1f, %d spills, %d builds, capsules %.1f us (pick %.1f) %.1f a frame, door poll %.1f us), ", queueNs / 1000.0 / qf, occNs / 1000.0 / qf,
         prismNs / 1000.0 / qf, lightNs / 1000.0 / qf, spillNs / 1000.0 / qf, DoorLight.spillsMade, DoorLight.cacheBuilds, capNs / 1000.0 / qf, capPickNs / 1000.0 / qf, capCount / (double)Math.max(1L, capFrames), pollNs / 1000.0 / qf);
      queueNs = occNs = prismNs = lightNs = queueFrames = spillNs = capNs = capFrames = capCount = capPickNs = pollNs = 0L;
      DoorLight.spillsMade = DoorLight.cacheBuilds = 0L;
      if (Config.DEV_GOD_RAYS_DOOR_ALTERNATE > 0) {
         cpu += String.format(java.util.Locale.ROOT, "door halves: game thread %.1f / %.1f us, render thread %.1f / %.1f us (on / off), ", qHalf[1] / 1000.0 / Math.max(1L, qHalfN[1]),
            qHalf[0] / 1000.0 / Math.max(1L, qHalfN[0]), Gl.rtHalf[1] / 1000.0 / Math.max(1L, Gl.rtHalfN[1]), Gl.rtHalf[0] / 1000.0 / Math.max(1L, Gl.rtHalfN[0]));
         qHalf[0] = qHalf[1] = qHalfN[0] = qHalfN[1] = Gl.rtHalf[0] = Gl.rtHalf[1] = Gl.rtHalfN[0] = Gl.rtHalfN[1] = 0L;
      }
      return cpu + String.format(java.util.Locale.ROOT, "door changes %d swings %d, prism builds %d (%.0f us each), ", doorChanges, DoorLight.swings, prismBuilds, prismBuilds > 0 ? prismBuildNs / 1000.0 / prismBuilds : 0.0) + String.format(java.util.Locale.ROOT, "%s method %s light %s (%.2f,%.2f,%.2f) strength %.2f phase %.2f sigma out %.4f in %.3f | occupancy z0 %d top %d builds %d (%.0f us each) | %s%s",
         wanted() ? "on" : "off", Config.GOD_RAYS_METHOD, body == 0 ? "sun" : "moon", light[0], light[1], light[2], strengthNow, phase, sigmaOut, sigmaIn, occZ0, maxTopLevel,
         chunkBuilds, chunkBuilds > 0 ? chunkBuildNs / 1000.0 / chunkBuilds : 0.0, Gl.stats(), failed ? " FAILED " + why : "");
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final class Gl {
      static boolean everOn;
      static int occTex, vTex, fTex, topTex, mmTex, sTex;
      /** The occupancy as last uploaded (render thread; pzopt.EntityShadow reads it): its lowest level, the exit height. */
      static int occZ0Now = -4;
      static long occOnlyFrames, occUploads;
      static float occMaxTop = 1F;
      static int vProg, fProg, mmProg, volFbo;
      /** No compute shaders (GL < 4.3, macOS): the volume is written by fragment passes, one draw per slice. */
      static boolean fragVolume;
      static int nx, ny, nz;
      static int lastI0 = Integer.MIN_VALUE, lastJ0 = Integer.MIN_VALUE;
      static float lastCu, lastCz, lastZLo;
      static int lastOccZ0 = Integer.MIN_VALUE, lastRefX, lastRefY;
      static long lastLightKey = Long.MIN_VALUE;
      static float lastSigmaOut = -1F, lastSigmaIn = -1F;
      static long columnsComputed, dispatches, fullUpdates;
      // the screen pass's state (the last world frame)
      static volatile boolean screenOn;
      static int depthTex, depthW, depthH;
      static final float[] mapX = new float[4], mapY = new float[4], mapZ = new float[4], color = new float[4], params = new float[4], lightDir = new float[4], jac = new float[4];
      static float groundTc;
      static int pendingFullRows; // progressive sun-step update: rows still to redo
      static boolean volumeStale;
      static boolean hazeOn, shadeNow, fusedIntoFog, fusedLast;
      static long fusedFrames;
      static int progressiveRow;

      static long rtNs, rtFrames, fogNs;
      static final long[] rtHalf = new long[2], rtHalfN = new long[2]; // devGodRaysDoorAlternate: render thread ns by half

      static String stats() {
         long rf = Math.max(1L, rtFrames);
         String rt = String.format(java.util.Locale.ROOT, "render thread %.1f us/frame, local lights %.1f a frame over %.0f kpx, light volume boxes %.0f kpx, ", rtNs / 1000.0 / rf,
            llQuads / (double)Math.max(1L, llFrames), llPixels / 1000.0 / Math.max(1L, llFrames), apBoxPixels / 1000.0 / Math.max(1L, apFrames));
         apBoxPixels = apFrames = 0L;
         long rtFrames0 = Math.max(1L, rtFrames);
         double bq = bncQuads / (double)rtFrames0, bp = bncPixels / 1000.0 / Math.max(1L, bncFrames);
         bncQuads = bncPixels = bncFrames = 0L;
         rtNs = rtFrames = fogNs = 0L;
         llQuads = llPixels = llFrames = 0L;
         return rt + String.format(java.util.Locale.ROOT, "bounces %.1f a frame over %.0f kpx, volume %dx%dx%d, columns computed %d in %d dispatches (%d full), light volumes %d on screen (%d built, %d builds), buffer %dx%d, fog-fused frames %d", bq, bp, nx, ny, nz, columnsComputed, dispatches, fullUpdates,
            prismsOnScreen, prismCount, prismBuilds, lowW, lowH, fusedFrames);
      }

      static void frame(Frame f) {
         Timing.suffix = Config.DEV_GOD_RAYS_DOOR_ALTERNATE > 0 ? f.doorFx ? ".d1" : ".d0" : "";
         // 1. occupancy uploads, whatever the frame does next (a frame without light still built them; entity shadows read them)
         if (f.occOnly) {
            occOnlyFrames++;
         }
         if ((!f.uploads.isEmpty() || f.tops != null) && ensureOcc()) {
            occUploads += f.uploads.size();
            uploadOcc(f);
            VoxelLight.Gl.occSerial++; // voxel lighting: its torch maps trace again
         }
         if (occTex != 0) {
            occZ0Now = f.occZ0;
            occMaxTop = f.maxTop;
         }
         if (f.occOnly) {
            screenOn = false;
            return;
         }
         if (!f.devOn && Config.DEV_GOD_RAYS_ALTERNATE > 0 && Config.DEV_GOD_RAYS_ALTERNATE_ALL) {
            screenOn = false; // dev: the off half of an alternation does no god ray work at all (a within-run frame-time A/B)
            apOn = false;
            hazeOn = false;
            fusedIntoFog = false;
            if (Config.DEV_GOD_RAYS_TOUCH_DEPTH) {
               touchDepth(f); // dev: ... except one depth texel read (does the first depth read of a frame cost by itself?)
            }
            return;
         }
         if (!f.on && !(f.lightsOnly && f.lights > 0) || failed) {
            screenOn = false;
            return;
         }
         if (!f.on) {
            // night without moon: only the local lights' airlight
            try {
               if (ensure(f)) {
                  int prevFbo = currentFbo(f);
                  screenState(f, prevFbo);
                  hazeOn = false;
                  apOn = false;
                  if (screenOn && f.devOn) {
                     if (lateOk()) {
                        lateLocal[f.player] = f; // drawn at the screen composite (lateDraw)
                        lateFboOf[f.player] = prevFbo;
                     } else {
                        Timing.begin("local", true);
                        localLights(f, prevFbo);
                        Timing.end(null);
                     }
                  }
                  screenOn = false; // nothing for the composite
                  unbindDepth();
               }
            } catch (Throwable t) {
               failed = true;
               why = t.toString();
               Log.warn("god rays: local lights failed, off: " + t);
            }
            return;
         }
         try {
            everOn = true;
            if (!ensure(f)) {
               screenOn = false;
               return;
            }
            Timing.begin("update", true);
            int prevFbo = currentFbo(f);
            // 2. which columns need their visibility and integration
            boolean gridChanged = f.cu != lastCu || f.cz != lastCz || f.zLo != lastZLo || f.nx != nx || f.ny != ny || f.nz != nz || f.occZ0 != lastOccZ0;
            if (f.nx != nx || f.ny != ny || f.nz != nz) {
               allocVolumes(f.nx, f.ny, f.nz);
            }
            regions.clear();
            boolean full = gridChanged || Math.abs(f.i0 - lastI0) >= nx || Math.abs(f.j0 - lastJ0) >= ny;
            boolean lightChanged = f.lightKey != lastLightKey;
            boolean sigmaChanged = f.sigmaOut != lastSigmaOut || f.sigmaIn != lastSigmaIn;
            if (full) {
               regions.add(new int[] {f.i0, f.j0, nx, ny, 1});
               fullUpdates++;
               pendingFullRows = 0;
            } else {
               // columns that came into view
               if (f.i0 > lastI0) {
                  regions.add(new int[] {lastI0 + nx, f.j0, f.i0 - lastI0, ny, 1});
               } else if (f.i0 < lastI0) {
                  regions.add(new int[] {f.i0, f.j0, lastI0 - f.i0, ny, 1});
               }
               if (f.j0 > lastJ0) {
                  regions.add(new int[] {f.i0, lastJ0 + ny, nx, f.j0 - lastJ0, 1});
               } else if (f.j0 < lastJ0) {
                  regions.add(new int[] {f.i0, f.j0, nx, lastJ0 - f.j0, 1});
               }
               if (lightChanged) {
                  if (Config.GOD_RAYS_STEP_FRAMES > 1) {
                     pendingFullRows = ny; // progressive: a band of rows a frame
                     progressiveRow = 0;
                  } else {
                     regions.clear();
                     regions.add(new int[] {f.i0, f.j0, nx, ny, 1});
                     fullUpdates++;
                  }
               } else if (sigmaChanged) {
                  regions.add(new int[] {f.i0, f.j0, nx, ny, 0}); // the integration alone
               }
               if (pendingFullRows > 0) {
                  int rows = Math.max(1, Math.min(pendingFullRows, (ny + Config.GOD_RAYS_STEP_FRAMES - 1) / Config.GOD_RAYS_STEP_FRAMES));
                  regions.add(new int[] {f.i0, f.j0 + progressiveRow, nx, rows, 1});
                  progressiveRow += rows;
                  pendingFullRows -= rows;
               }
               // chunks whose occupancy changed: the columns their geometry can shade (down-light of them)
               if (!f.uploads.isEmpty() && !(regions.size() == 1 && regions.get(0)[2] == nx && regions.get(0)[3] == ny)) {
                  chunkRegions(f);
               }
            }
            lastI0 = f.i0;
            lastJ0 = f.j0;
            lastCu = f.cu;
            lastCz = f.cz;
            lastZLo = f.zLo;
            lastOccZ0 = f.occZ0;
            lastLightKey = f.lightKey;
            lastSigmaOut = f.sigmaOut;
            lastSigmaIn = f.sigmaIn;
            lastRefX = f.refX;
            lastRefY = f.refY;
            Timing.end("update");
            if (f.froxelLights || localPrev != null) {
               Timing.begin("local.froxel", true);
               localFroxel(f);
               Timing.end(null);
            }
            if (!f.haze) {
               regions.clear(); // no haze to show: the volume waits (a full update when it is needed again)
               volumeStale = true;
            } else if (volumeStale) {
               regions.clear();
               regions.add(new int[] {f.i0, f.j0, nx, ny, 1});
               volumeStale = false;
               pendingFullRows = 0;
               fullUpdates++;
            }
            if ((Config.DEV_GOD_RAYS_SKIP & 4) != 0) {
               regions.clear(); // dev: no volume compute (cost attribution)
            }
            if (!regions.isEmpty()) {
               long cols = 0L;
               for (int[] r : regions) {
                  cols += (long)r[2] * r[3];
               }
               Timing.begin(cols >= (long)nx * ny ? "compute.full" : cols >= (long)nx * ny / 16 ? "compute.band" : "compute.small", true);
               compute(f);
               Timing.end(null);
            }
            // 3. the screen pass's mapping, from this frame's world viewport and depth
            screenState(f, prevFbo);
            // 4. the light volumes of the apertures (their own target, read by the screen pass inside their bounds)
            apOn = false;
            if (screenOn && f.apertures && f.prisms > 0 && Config.GOD_RAYS_AP_DIRECT && lateOk()) {
               lateAp[f.player] = f; // drawn at the screen composite (lateDraw)
               lateFboOf[f.player] = prevFbo;
            } else if (screenOn && f.apertures && f.prisms > 0 && Config.GOD_RAYS_AP_DIRECT) {
               Timing.begin("apertures.direct", true);
               apDirect(f, prevFbo);
               Timing.end(null);
            } else if (screenOn && f.apertures && f.prisms > 0) {
               Timing.begin("apertures", true);
               apertures(f, prevFbo);
               Timing.end(null);
               if (apOn && f.devOn) {
                  Timing.begin("apertures.composite", true);
                  apComposite(f, prevFbo);
                  Timing.end(null);
               }
            }
            // 4b. the local lights' airlight (torches, headlights, lamps), straight into the world picture
            if (screenOn && f.lights > 0 && f.devOn && !f.froxelLights) {
               if (lateOk()) {
                  lateLocal[f.player] = f;
                  lateFboOf[f.player] = prevFbo;
               } else {
                  Timing.begin("local", true);
                  localLights(f, prevFbo);
                  Timing.end(null);
               }
            }
            // 5. the quarter-size god ray buffer the composite reads (no depth read there)
            hazeOn = f.haze;
            fusedLast = fusedIntoFog;
            fusedIntoFog = false;
            shadeNow = "shade".equals(Config.GOD_RAYS_HAZE_MODE) || "auto".equals(Config.GOD_RAYS_HAZE_MODE) && stockFog > 0.05F;
            // (the game's fog composite took the shade last frame: it will again; no buffer pass of our own)
            boolean fogTakes = fusedLast && shadeNow && fogFuse() && "volume".equals(Config.GOD_RAYS_METHOD) && !f.froxelLights;
            froxelLast = f.froxelLights;
            // (the chunk composite added the haze this frame: no buffer)
            if (screenOn && f.haze && !fogTakes && !chunkHazeNow && ((Config.DEV_GOD_RAYS_SKIP & 1) == 0 || lowTex == 0)) {
               String m = Config.GOD_RAYS_METHOD;
               Timing.begin("low." + m, true);
               if ("blur".equals(m)) {
                  blurPass(f, prevFbo);
               } else if ("march".equals(m) || "minmax".equals(m)) {
                  marchPass(f, prevFbo, "minmax".equals(m));
               } else if ("epipolar".equals(m)) {
                  epipolarPass(f, prevFbo);
               } else {
                  lowPass(f, prevFbo);
               }
               Timing.end(null);
            }
            if (f.dump) {
               dump(f, prevFbo);
            }
            unbindDepth();
         } catch (Throwable t) {
            failed = true;
            why = t.toString();
            screenOn = false;
            Log.warn("god rays: render failed, off: " + t);
            t.printStackTrace();
         }
      }

      // The world FBO and viewport, read back once and then again only when the game binds another FBO (TextureFBO.lastID)
      // or every 120 frames: a glGet on the render thread waits for NVIDIA's threaded driver to drain (GPU bubbles)
      private static final int[] cFbo = {-1, -1, -1, -1}, cFboLast = new int[4], cAge = new int[4];
      private static final int[][] cVp = new int[4][4];
      private static final boolean[] cVpStale = {true, true, true, true};

      private static int currentFbo(Frame f) {
         int p = f.player, last = zombie.core.textures.TextureFBO.lastID;
         if (!Config.GOD_RAYS_GL_CACHE || cFbo[p] < 0 || last != cFboLast[p] || ++cAge[p] > 120) {
            cFbo[p] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            cFboLast[p] = last;
            cAge[p] = 0;
            cVpStale[p] = true;
         }
         return cFbo[p];
      }

      // --- late draw: the light volumes and the local lights sample the scene depth; drawn in the middle of the world
      // (before the fog) while that depth is the world FBO's attachment, the driver drains the whole world pipeline first
      // (~10 us whatever the pass does). At the screen composite the world is finished and that wait happens anyway: they
      // draw there, into a colour-only FBO over the world picture (no depth attached: no feedback, no depth test).
      static final Frame[] lateAp = new Frame[4], lateLocal = new Frame[4];
      static final float[][] mapAOf = new float[4][6], vpNowOf = new float[4][4];
      static final int[] depthTexOf = new int[4];
      static final int[] lateFboOf = new int[4];
      static boolean lateNow;
      private static int lateColorFbo, lateColorTex;

      static boolean lateOk() {
         return Config.GOD_RAYS_LATE_DRAW && !RenderScale.active();
      }

      /** Render thread, the screen composite's start: this frame's pending light volumes and local lights. */
      static void lateDraw() {
         for (int p = 0; p < 4; p++) {
            Frame a = lateAp[p], l = lateLocal[p];
            lateAp[p] = null;
            lateLocal[p] = null;
            if (a == null && l == null || failed) {
               continue;
            }
            try {
               int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
               int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
               int[] pvp = new int[4];
               GL11.glGetIntegerv(GL11.GL_VIEWPORT, pvp);
               GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, lateFboOf[p]);
               int ct = colorTexture(lateFboOf[p]);
               if (ct == 0) {
                  GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
                  continue;
               }
               if (lateColorFbo == 0) {
                  lateColorFbo = GL30.glGenFramebuffers();
               }
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lateColorFbo);
               if (ct != lateColorTex) {
                  lateColorTex = ct;
                  GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, ct, 0);
               }
               Frame f = a != null ? a : l;
               GL11.glViewport((int)vpNowOf[p][0], (int)vpNowOf[p][1], (int)vpNowOf[p][2], (int)vpNowOf[p][3]);
               System.arraycopy(vpNowOf[p], 0, vpNow, 0, 4);
               System.arraycopy(mapAOf[p], 0, mapA, 0, 6);
               depthTex = depthTexOf[p];
               lateNow = true;
               if (a != null) {
                  Timing.begin("apertures.late", true);
                  apDirect(a, lateColorFbo);
                  Timing.end(null);
               }
               if (l != null) {
                  Timing.begin("local.late", true);
                  localLights(l, lateColorFbo);
                  Timing.end(null);
               }
               lateNow = false;
               unbindDepth();
               GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
               GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
               GL11.glViewport(pvp[0], pvp[1], pvp[2], pvp[3]);
            } catch (Throwable t) {
               lateNow = false;
               failed = true;
               why = t.toString();
               Log.warn("god rays: late draw failed, off: " + t);
            }
         }
      }

      /**
       * The scene depth off our sampler unit: left bound there while the game goes on drawing into it (it is the world
       * FBO's depth attachment), every later world draw is a potential feedback loop to the driver (~9 us a frame here).
       */
      static void unbindDepth() {
         if (Config.GOD_RAYS_UNBIND_DEPTH) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
      }

      private static int touchProg, touchVao;

      /** dev: one fragment that reads one texel of the scene depth (the first depth read of the frame, nothing else). */
      private static void touchDepth(Frame f) {
         int dt = FogPass.sceneDepthTexture(currentFbo(f));
         if (dt == 0) {
            return;
         }
         if (touchProg == 0) {
            touchProg = FogPass.link("#version 330\nvoid main() { gl_Position = vec4(-0.9999, -0.9999, 0.0, 1.0); gl_PointSize = 1.0; }\n",
               "#version 330\nuniform sampler2D uD;\nout vec4 o;\nvoid main() { o = vec4(texelFetch(uD, ivec2(0), 0).r); }\n", new String[0], new String[] {"o"});
            touchVao = GL30.glGenVertexArrays();
         }
         GL20.glUseProgram(touchProg);
         GL20.glUniform1i(GL20.glGetUniformLocation(touchProg, "uD"), DEPTH_UNIT);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, dt);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glColorMask(false, false, false, false);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL30.glBindVertexArray(touchVao);
         GL11.glDrawArrays(GL11.GL_POINTS, 0, 1);
         GL30.glBindVertexArray(0);
         unbindDepth();
         GL11.glColorMask(true, true, true, true);
         GL11.glDepthMask(true);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         zombie.core.opengl.GLStateRenderThread.restore();
      }

      static boolean froxelLast; // last frame's local lights rode the volume and the buffer

      /** The world viewport of a player (read back once, again when the game binds another FBO: currentFbo). */
      static int[] viewport(int p) {
         if (cVpStale[p]) {
            cVpStale[p] = false;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, cVp[p]);
         }
         return cVp[p];
      }

      private static final ArrayList<int[]> regions = new ArrayList<>();
      private static IntBuffer ub;

      private static IntBuffer uploadBuf() {
         if (ub == null) {
            ub = BufferUtils.createIntBuffer(64 * OCC_L);
         }
         return ub;
      }

      /** A changed chunk shades the columns of its own squares and those down-light of it within the reach of its height. */
      private static void chunkRegions(Frame f) {
         double h = Math.hypot(f.lx, f.ly);
         float tanEl = h < 1e-3 ? 100F : (float)(f.lz / h);
         float reach = Math.min(48F, (f.maxTop - f.zLo) * LEVEL / Math.max(0.05F, tanEl));
         float dx = h < 1e-3 ? 0F : (float)(-f.lx / h) * reach, dy = h < 1e-3 ? 0F : (float)(-f.ly / h) * reach;
         int added = 0;
         for (Upload u : f.uploads) {
            float x0 = u.wx * 8F, y0 = u.wy * 8F, x1 = x0 + 8F, y1 = y0 + 8F;
            float ex0 = Math.min(x0, x0 + dx), ex1 = Math.max(x1, x1 + dx), ey0 = Math.min(y0, y0 + dy), ey1 = Math.max(y1, y1 + dy);
            double umin = ex0 - ey1, umax = ex1 - ey0;
            double zTop = f.zLo + f.nz * f.cz;
            double vmin = ex0 + ey0 - 6.0 * zTop, vmax = ex1 + ey1 - 6.0 * f.zLo;
            int ia = Math.max(f.i0, (int)Math.floor(umin / f.cu)), ib = Math.min(f.i0 + nx - 1, (int)Math.floor(umax / f.cu));
            int ja = Math.max(f.j0, (int)Math.floor(vmin / f.cv)), jb = Math.min(f.j0 + ny - 1, (int)Math.floor(vmax / f.cv));
            if (ia > ib || ja > jb) {
               continue;
            }
            regions.add(new int[] {ia, ja, ib - ia + 1, jb - ja + 1, 1});
            if (++added > 24) {
               regions.clear();
               regions.add(new int[] {f.i0, f.j0, nx, ny, 1});
               fullUpdates++;
               return;
            }
         }
      }

      /** The frame's chunk occupancy and chunk tops into their textures (render thread). */
      private static void uploadOcc(Frame f) {
         if (!f.uploads.isEmpty()) {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
            IntBuffer sb = uploadBuf();
            for (Upload u : f.uploads) {
               sb.clear();
               sb.put(u.data).flip();
               GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, (u.wx & OCC_CHUNKS - 1) * 8, (u.wy & OCC_CHUNKS - 1) * 8, 0, 8, 8, OCC_L, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, sb);
            }
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         }
         if (f.tops != null) {
            if (topTex == 0) {
               topTex = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, topTex);
               GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R8UI, OCC_CHUNKS, OCC_CHUNKS);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            }
            ByteBuffer tb = BufferUtils.createByteBuffer(f.tops.length);
            tb.put(f.tops).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, topTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, OCC_CHUNKS, OCC_CHUNKS, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, tb);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            f.tops = null;
         }
      }

      /** The occupancy texture (render thread); false when it cannot be made. */
      static boolean ensureOcc() {
         if (occTex != 0) {
            return true;
         }
         if (failed || CoreGl.legacyMac()) {
            return false;
         }
         occTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_R32UI, OCC_N, OCC_N, OCC_L);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         Log.info("god rays: occupancy " + OCC_N + "x" + OCC_N + "x" + OCC_L + " R32UI ready");
         return true;
      }

      private static boolean ensure(Frame f) {
         if (vProg != 0) {
            return true;
         }
         fragVolume = !GL.getCapabilities().OpenGL43;
         if (fragVolume) {
            // no compute (macOS's OpenGL 4.1 core context): the same cells by fragment passes into the volume's slices
            vProg = FogPass.link(VOL_VERT, VIS_FRAG, new String[0], new String[] {"oV"});
            fProg = FogPass.link(VOL_VERT, INTEGRATE_FRAG, new String[0], new String[] {"oF", "oS"});
            mmProg = FogPass.link(VOL_VERT, MM_FRAG, new String[0], new String[] {"oMM"});
            if (vProg == 0 || fProg == 0 || mmProg == 0) {
               failed = true;
               why = "volume fragment shaders did not compile";
               return false;
            }
            volFbo = GL30.glGenFramebuffers();
         } else {
            vProg = computeProgram(VIS_CS, "visibility");
            fProg = computeProgram(INTEGRATE_CS, "integration");
         }
         if (vProg == 0 || fProg == 0) {
            failed = true;
            why = "compute shaders did not compile";
            return false;
         }
         ensureOcc();
         Log.info("god rays: " + (fragVolume ? "fragment" : "compute") + " programs " + vProg + "/" + fProg);
         return true;
      }

      private static void allocVolumes(int x, int y, int z) {
         if (vTex != 0) {
            GL11.glDeleteTextures(vTex);
            GL11.glDeleteTextures(fTex);
            GL11.glDeleteTextures(mmTex);
            GL11.glDeleteTextures(sTex);
         }
         nx = x;
         ny = y;
         nz = z;
         vTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, vTex);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_RG8, x, y, z); // V, the room tag (the march's media)
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         mmTex = GL11.glGenTextures(); // minmax: V's min and max over 8 slices of a column
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, mmTex);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL11.GL_RGBA8, x, y, Math.max(1, (z + 7) / 8)); // min V, max V, max room tag
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         // the fog shade's volume: 1 byte a cell instead of the 8 of F
         sTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, sTex);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_R8, x, y, z);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         fTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, fTex);
         GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_RGBA16F, x, y, z);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         Log.info("god rays: volume " + x + "x" + y + "x" + z + " (" + (x * y * z * 9L / 1024) + " KB)");
      }

      private static final int[] U_V = new int[8], U_F = new int[8];
      private static boolean located;
      private static final String[] NAMES = {"uRegion", "uN", "uRef", "uCell", "uSun", "uLim", "uSigma", "uOcc"};

      private static void locations(int prog, int[] out) {
         for (int i = 0; i < NAMES.length; i++) {
            out[i] = GL20.glGetUniformLocation(prog, NAMES[i]);
         }
      }

      private static void compute(Frame f) {
         if (fragVolume) {
            computeFrag(f);
            return;
         }
         if (!located) {
            located = true;
            locations(vProg, U_V);
            locations(fProg, U_F);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, topTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         for (int pass = 0; pass < 2; pass++) {
            int prog = pass == 0 ? vProg : fProg;
            int[] u = pass == 0 ? U_V : U_F;
            GL20.glUseProgram(prog);
            GL20.glUniform4i(u[1], nx, ny, nz, f.occZ0);
            int iRef = Math.round((f.refX - f.refY) / f.cu), jRef = Math.round((f.refX + f.refY) / f.cv);
            GL20.glUniform4i(u[2], f.refX, f.refY, iRef, jRef);
            GL20.glUniform4f(u[3], f.cu, f.cv, f.cz, f.zLo);
            GL20.glUniform4f(u[4], (float)(f.lx / Math.max(1e-6, Math.hypot(f.lx, f.ly))), (float)(f.ly / Math.max(1e-6, Math.hypot(f.lx, f.ly))), f.slope, Math.hypot(f.lx, f.ly) < 1e-3 ? 1F : 0F);
            GL20.glUniform4f(u[5], f.maxTop, 64F, 0.55F, (float)(frames & 1023));
            GL20.glUniform4f(u[6], f.sigmaOut, f.sigmaIn, f.hazeH, 0F);
            GL20.glUniform1i(u[7], OCC_UNIT);
            if (pass == 0) {
               GL42.glBindImageTexture(0, vTex, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG8);
            } else {
               GL42.glBindImageTexture(0, vTex, 0, true, 0, GL15.GL_READ_ONLY, GL30.GL_RG8);
               GL42.glBindImageTexture(1, fTex, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
               GL42.glBindImageTexture(2, mmTex, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
               GL42.glBindImageTexture(3, sTex, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
            }
            for (int[] r : regions) {
               if (pass == 0 && r[4] == 0) {
                  continue; // the integration alone
               }
               GL20.glUniform4i(u[0], r[0], r[1], r[2], r[3]);
               if (pass == 0) {
                  GL43.glDispatchCompute((r[2] + 7) / 8, (r[3] + 7) / 8, (nz + 3) / 4);
                  columnsComputed += (long)r[2] * r[3];
               } else {
                  GL43.glDispatchCompute((r[2] + 7) / 8, (r[3] + 7) / 8, 1);
               }
               dispatches++;
            }
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         }
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL42.glBindImageTexture(2, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL42.glBindImageTexture(3, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
      }

      private static final int[] U_MM = new int[8];
      private static int uVk, uFk, uMk, uFv, uMv;

      /**
       * compute() without compute shaders: VIS_FRAG into each slice of vTex, then INTEGRATE_FRAG into each slice of fTex and
       * sTex (two targets) walking its column from the top down to the slice, then MM_FRAG into each 8-slice block of mmTex.
       * A region wraps around the volume (cells are stored modulo its size), so it is drawn as up to four rectangles, each
       * with the shift from the stored texel back to its cell.
       */
      private static void computeFrag(Frame f) {
         if (!located) {
            located = true;
            locations(vProg, U_V);
            locations(fProg, U_F);
            locations(mmProg, U_MM);
            uVk = GL20.glGetUniformLocation(vProg, "uShiftK");
            uFk = GL20.glGetUniformLocation(fProg, "uShiftK");
            uMk = GL20.glGetUniformLocation(mmProg, "uShiftK");
            uFv = GL20.glGetUniformLocation(fProg, "uVTex");
            uMv = GL20.glGetUniformLocation(mmProg, "uVTex");
         }
         int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         int[] vp = new int[4];
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, topTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_V_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, vTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, volFbo);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glColorMask(true, true, true, true);
         GL30.glBindVertexArray(0);
         for (int pass = 0; pass < 3; pass++) {
            int prog = pass == 0 ? vProg : pass == 1 ? fProg : mmProg;
            int[] u = pass == 0 ? U_V : pass == 1 ? U_F : U_MM;
            int uK = pass == 0 ? uVk : pass == 1 ? uFk : uMk;
            GL20.glUseProgram(prog);
            GL20.glUniform4i(u[1], nx, ny, nz, f.occZ0);
            int iRef = Math.round((f.refX - f.refY) / f.cu), jRef = Math.round((f.refX + f.refY) / f.cv);
            GL20.glUniform4i(u[2], f.refX, f.refY, iRef, jRef);
            GL20.glUniform4f(u[3], f.cu, f.cv, f.cz, f.zLo);
            GL20.glUniform4f(u[4], (float)(f.lx / Math.max(1e-6, Math.hypot(f.lx, f.ly))), (float)(f.ly / Math.max(1e-6, Math.hypot(f.lx, f.ly))), f.slope, Math.hypot(f.lx, f.ly) < 1e-3 ? 1F : 0F);
            GL20.glUniform4f(u[5], f.maxTop, 64F, 0.55F, (float)(frames & 1023));
            GL20.glUniform4f(u[6], f.sigmaOut, f.sigmaIn, f.hazeH, 0F);
            GL20.glUniform1i(u[7], OCC_UNIT);
            if (pass > 0) {
               GL20.glUniform1i(pass == 1 ? uFv : uMv, MARCH_V_UNIT);
            }
            int layers = pass == 2 ? Math.max(1, (nz + 7) / 8) : nz;
            for (int k = 0; k < layers; k++) {
               if (pass == 0) {
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, vTex, 0, k);
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, 0, 0, 0);
                  GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
               } else if (pass == 1) {
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, fTex, 0, k);
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, sTex, 0, k);
                  GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
               } else {
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, mmTex, 0, k);
                  GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, 0, 0, 0);
                  GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
               }
               for (int[] r : regions) {
                  if (pass == 0 && r[4] == 0) {
                     continue; // the integration alone
                  }
                  GL20.glUniform4i(u[0], r[0], r[1], r[2], r[3]);
                  for (int cx = r[0]; cx < r[0] + r[2]; ) {
                     int sx = Math.floorMod(cx, nx), wx = Math.min(nx - sx, r[0] + r[2] - cx);
                     for (int cy = r[1]; cy < r[1] + r[3]; ) {
                        int sy = Math.floorMod(cy, ny), wy = Math.min(ny - sy, r[1] + r[3] - cy);
                        GL11.glViewport(sx, sy, wx, wy);
                        GL30.glUniform3i(uK, cx - sx, cy - sy, k);
                        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                        cy += wy;
                     }
                     cx += wx;
                  }
                  if (pass == 0 && k == 0) {
                     columnsComputed += (long)r[2] * r[3];
                  }
                  if (k == 0) {
                     dispatches++;
                  }
               }
            }
         }
         GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, 0, 0, 0);
         GL30.glFramebufferTextureLayer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, 0, 0, 0);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);
         GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      /** The screen pass's taps: depth uv from the composite's uv, the volume coordinates as linear functions of (uv, depth). */
      private static void screenState(Frame f, int fbo) {
         int dt = FogPass.sceneDepthTexture(fbo);
         if (dt == 0) {
            screenOn = false;
            return;
         }
         depthTex = dt;
         depthW = FogPass.sceneDepthWidth(fbo);
         depthH = FogPass.sceneDepthHeight(fbo);
         float[] vp = new float[4];
         int[] vpi = cVp[f.player];
         if (cVpStale[f.player]) {
            cVpStale[f.player] = false;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vpi);
         }
         vp[0] = vpi[0];
         vp[1] = vpi[1];
         vp[2] = vpi[2];
         vp[3] = vpi[3];
         float[] m = new float[6];
         f.view.mapping(vp, m);
         System.arraycopy(m, 0, mapA, 0, 6);
         System.arraycopy(vp, 0, vpNow, 0, 4);
         System.arraycopy(m, 0, mapAOf[f.player], 0, 6);
         System.arraycopy(vp, 0, vpNowOf[f.player], 0, 4);
         depthTexOf[f.player] = dt;
         // u = kA px + cA (px = uv.x depthW), v = kB py + cB, w = kC d + cC, relative to (ox, oy); z = (w - v) / 8
         double kA = m[0], cA = m[1], kB = m[2], cB = m[3], kC = m[4], cC = m[5];
         double offU = floorMod((f.view.ox - f.view.oy) / (double)f.cu, nx), offV = floorMod((f.view.ox + f.view.oy) / (double)f.cv, ny);
         // tc.x = (u / cu + offU) / nx
         mapX[0] = (float)(kA * depthW / f.cu / nx);
         mapX[1] = 0F;
         mapX[2] = 0F;
         mapX[3] = (float)((cA / f.cu + offU) / nx);
         mapY[0] = 0F;
         mapY[1] = (float)(kB * depthH / f.cv / ny);
         mapY[2] = 0F;
         mapY[3] = (float)((cB / f.cv + offV) / ny);
         // tc.z = (z - zLo) / (nz cz) + half a cell up (the lit side of the surface), z = (kC d + cC - kB py - cB) / 8
         double zs = 1.0 / (nz * f.cz);
         mapZ[0] = 0F;
         mapZ[1] = (float)(-kB * depthH / 8.0 * zs);
         mapZ[2] = (float)(kC / 8.0 * zs);
         mapZ[3] = (float)(((cC - cB) / 8.0 - f.zLo) * zs + 0.5 / nz);
         groundTc = (float)((0.0 - f.zLo) * zs + 0.5 / nz);
         color[0] = f.r;
         color[1] = f.g;
         color[2] = f.b;
         color[3] = f.dustGain;
         lightDir[0] = f.lx;
         lightDir[1] = f.ly;
         lightDir[2] = f.lz;
         jac[0] = (float)(1.0 / (f.cu * nx));
         jac[1] = (float)(1.0 / (f.cv * ny));
         jac[2] = (float)(6.0 / (f.cv * ny) / LEVEL);
         jac[3] = (float)(1.0 / (LEVEL * nz * f.cz));
         params[0] = f.devOn ? 1F : 0F;
         params[1] = f.view_;
         params[2] = f.patch;
         params[3] = groundTc;
         screenOn = true;
      }

      static boolean apOn;
      static int prismsOnScreen, planeBuf, planeTex;
      private static FloatBuffer planeFb;
      static int apTex, apFbo, apW, apH, apProg, apVbo;
      static final float[] apBounds = new float[4], apScale = new float[4], clearColor = new float[4];
      private static FloatBuffer apBuf;
      private static int uApA, uApB, uApV, uApD, uApL, uApP, uApO;

      /** Rasterise the frame's light volumes: R the view path inside them in front of the surface, G the surface inside one. */
      private static void apertures(Frame f, int fbo) {
         if (apProg == 0) {
            apProg = FogPass.link(AP_VERT, AP_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (apProg == 0) {
               Log.warn("god rays: light volume shaders did not compile; volume only");
               Config.GOD_RAYS_APERTURES = false;
               return;
            }
            uApA = GL20.glGetUniformLocation(apProg, "uMapA");
            uApB = GL20.glGetUniformLocation(apProg, "uMapB");
            uApV = GL20.glGetUniformLocation(apProg, "uVp");
            uApD = GL20.glGetUniformLocation(apProg, "uDepth");
            uApL = GL20.glGetUniformLocation(apProg, "uLight");
            uApP = GL20.glGetUniformLocation(apProg, "uPlanes");
            uApO = GL20.glGetUniformLocation(apProg, "uOrg");
            apVbo = GL15.glGenBuffers();
         }
         int vw = (int)vpNow[2], vh = (int)vpNow[3];
         int aw = Math.max(1, (vw + 1) / 2), ah = Math.max(1, (vh + 1) / 2);
         if (apTex == 0 || apW != aw || apH != ah) {
            if (apTex != 0) {
               GL11.glDeleteTextures(apTex);
               GL30.glDeleteFramebuffers(apFbo);
            }
            apW = aw;
            apH = ah;
            apTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, apTex);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RG16F, apW, apH);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            apFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, apFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, apTex, 0);
            int st = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (st == GL30.GL_FRAMEBUFFER_COMPLETE) {
               GL11.glColorMask(true, true, true, true);
               GL11.glDisable(GL11.GL_SCISSOR_TEST);
               GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
               GL11.glClearColor(0F, 0F, 0F, 0F);
               GL11.glClear(GL11.GL_COLOR_BUFFER_BIT); // storage starts undefined (it showed old UI shapes)
               GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            if (st != GL30.GL_FRAMEBUFFER_COMPLETE) {
               Log.warn("god rays: light volume target incomplete (0x" + Integer.toHexString(st) + "); volume only");
               Config.GOD_RAYS_APERTURES = false;
               return;
            }
            Log.info("god rays: light volume target " + apW + "x" + apH + " RG16F (half of the view)");
         }
         // the prisms' screen bounds (window px): the scissor of the clear and the screen pass's test
         float kA = mapA[0], cA = mapA[1], kB = mapA[2], cB = mapA[3];
         // the bounds of the prisms on screen (the CPU built them for a conservative world rectangle; the rasteriser clips
         // the rest for nothing)
         float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
         float vx0 = vpNow[0], vy0 = vpNow[1], vx1 = vpNow[0] + vpNow[2], vy1 = vpNow[1] + vpNow[3];
         int kept = 0;
         int vpp = PRISM_FLOATS / 4;
         for (int pi = 0; pi < f.prisms; pi++) {
            float px0 = Float.MAX_VALUE, py0 = Float.MAX_VALUE, px1 = -Float.MAX_VALUE, py1 = -Float.MAX_VALUE;
            for (int i = pi * vpp; i < pi * vpp + vpp; i++) {
               float x = f.prismData[i * 4] + f.prismOrgX, y = f.prismData[i * 4 + 1] + f.prismOrgY, z = f.prismData[i * 4 + 2];
               float px = (x - y - cA) / kA, py = (x + y - 6F * z - cB) / kB;
               px0 = Math.min(px0, px);
               px1 = Math.max(px1, px);
               py0 = Math.min(py0, py);
               py1 = Math.max(py1, py);
            }
            if (px1 < vx0 || px0 > vx1 || py1 < vy0 || py0 > vy1) {
               continue;
            }
            kept++;
            bx0 = Math.min(bx0, Math.max(px0, vx0));
            bx1 = Math.max(bx1, Math.min(px1, vx1));
            by0 = Math.min(by0, Math.max(py0, vy0));
            by1 = Math.max(by1, Math.min(py1, vy1));
         }
         prismsOnScreen = kept;
         if (kept == 0) {
            return;
         }
         int n = f.prisms * vpp;
         // the half-spaces, a texture buffer the fragments read by the prism's index
         if (planeBuf == 0) {
            planeBuf = GL15.glGenBuffers();
            planeTex = GL11.glGenTextures();
         }
         if (planeFb == null || planeFb.capacity() < f.prisms * PLANE_FLOATS) {
            planeFb = BufferUtils.createFloatBuffer(Math.max(f.prisms * PLANE_FLOATS, PLANE_FLOATS * 256));
         }
         planeFb.clear();
         planeFb.put(f.planeData, 0, f.prisms * PLANE_FLOATS).flip();
         GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, planeBuf);
         GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, planeFb, GL15.GL_STREAM_DRAW);
         GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + PLANE_UNIT);
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, planeTex);
         GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, planeBuf);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         // (in the half-size target's texels: window px relative to the viewport, halved)
         int sx0 = Math.max(0, (int)Math.floor((bx0 - vpNow[0]) / 2F) - 1), sy0 = Math.max(0, (int)Math.floor((by0 - vpNow[1]) / 2F) - 1);
         int sx1 = Math.min(apW, (int)Math.ceil((bx1 - vpNow[0]) / 2F) + 1), sy1 = Math.min(apH, (int)Math.ceil((by1 - vpNow[1]) / 2F) + 1);
         if (sx1 <= sx0 || sy1 <= sy0) {
            return;
         }
         if (apBuf == null || apBuf.capacity() < n * 4) {
            apBuf = BufferUtils.createFloatBuffer(Math.max(n * 4, 36 * 4 * 64));
         }
         apBuf.clear();
         apBuf.put(f.prismData, 0, n * 4).flip();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, apFbo);
         GL11.glColorMask(true, true, true, true); // (before the clear: the game may have left colour writes masked)
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         GL11.glScissor(sx0, sy0, sx1 - sx0, sy1 - sy0);
         GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
         GL11.glClearColor(0F, 0F, 0F, 0F);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
         GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST); // the sprite renderer leaves glAlphaFunc(GREATER, 0) on (it dropped every prism fragment); GLStateRenderThread.restore() puts it back
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendEquation(GL14.GL_FUNC_ADD);
         GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
         GL20.glUseProgram(apProg);
         GL20.glUniform4f(uApA, kA, cA, kB, cB);
         GL20.glUniform4f(uApB, mapA[4], mapA[5], vpNow[0], vpNow[1]);
         GL20.glUniform4f(uApV, vpNow[2], vpNow[3], 2F, 0F); // the viewport's size, full-size px per target texel
         GL11.glViewport(0, 0, apW, apH);
         GL20.glUniform1i(uApD, DEPTH_UNIT);
         GL20.glUniform4f(uApL, f.lx, f.ly, f.lz, 0F);
         GL20.glUniform1i(uApP, PLANE_UNIT);
         GL20.glUniform4f(uApO, f.prismOrgX, f.prismOrgY, 0F, 0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, apVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, apBuf, GL15.GL_STREAM_DRAW);
         for (int i = 0; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0L);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, n);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         // back to the world's framebuffer and the state the sprite renderer expects
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport((int)vpNow[0], (int)vpNow[1], vw, vh);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         apBounds[0] = (float)sx0 / apW; // in the target's uv (= the view's uv)
         apBounds[1] = (float)sy0 / apH;
         apBounds[2] = (float)sx1 / apW;
         apBounds[3] = (float)sy1 / apH;
         apScale[0] = f.sigmaIn * 0.6123724F * f.dustGain; // w units -> squares of view path -> inscatter
         apScale[1] = 1F;
         apScale[2] = 1F;
         apOn = true;
      }

      static int localProg, localTex;
      static int[] localPrev;
      private static int[] uLoc;
      static boolean localOn;

      /** Froxel local lights: this frame's and last frame's footprints (cells), the lights' in-scatter injected and accumulated. */
      private static void localFroxel(Frame f) {
         if (localProg == 0) {
            localProg = computeProgram(LOCAL_CS, "local lights");
            if (localProg == 0) {
               Config.GOD_RAYS_LOCAL_METHOD = "analytic";
               return;
            }
            uLoc = new int[] {GL20.glGetUniformLocation(localProg, "uRegion"), GL20.glGetUniformLocation(localProg, "uN"), GL20.glGetUniformLocation(localProg, "uRef"),
               GL20.glGetUniformLocation(localProg, "uCell"), GL20.glGetUniformLocation(localProg, "uNL"), GL20.glGetUniformLocation(localProg, "uLA"),
               GL20.glGetUniformLocation(localProg, "uLB"), GL20.glGetUniformLocation(localProg, "uLC"), GL20.glGetUniformLocation(localProg, "uOcc"),
               GL20.glGetUniformLocation(localProg, "uTop")};
         }
         if (localTex == 0 || localDims[0] != nx || localDims[1] != ny || localDims[2] != nz) {
            if (localTex != 0) {
               GL11.glDeleteTextures(localTex);
            }
            localTex = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, localTex);
            GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_RGBA16F, nx, ny, nz);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL44.glClearTexImage(localTex, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
            localDims[0] = nx;
            localDims[1] = ny;
            localDims[2] = nz;
            localPrev = null;
         }
         // the lights' footprints in cells (absolute), this frame's union with the last one's
         int ia = Integer.MAX_VALUE, ib = Integer.MIN_VALUE, ja = Integer.MAX_VALUE, jb = Integer.MIN_VALUE;
         if (f.froxelLights) {
            for (int i = 0; i < f.lights; i++) {
               float x = f.la[i * 4] + f.refX, y = f.la[i * 4 + 1] + f.refY, z = f.la[i * 4 + 2], r = f.la[i * 4 + 3];
               float lq = (float)Math.floor(z + 0.01F);
               double u0 = (x - r) - (y + r), u1 = (x + r) - (y - r), v0 = (x - r) + (y - r) - 6.0 * (lq + 1.0), v1 = (x + r) + (y + r) - 6.0 * lq;
               ia = Math.min(ia, (int)Math.floor(u0 / f.cu));
               ib = Math.max(ib, (int)Math.floor(u1 / f.cu));
               ja = Math.min(ja, (int)Math.floor(v0 / f.cv));
               jb = Math.max(jb, (int)Math.floor(v1 / f.cv));
            }
         }
         int[] now = ia <= ib ? new int[] {ia, ja, ib, jb} : null;
         int[] reg = now;
         if (localPrev != null) {
            reg = reg == null ? localPrev : new int[] {Math.min(reg[0], localPrev[0]), Math.min(reg[1], localPrev[1]), Math.max(reg[2], localPrev[2]), Math.max(reg[3], localPrev[3])};
         }
         localPrev = now;
         localOn = now != null;
         if (reg == null) {
            return;
         }
         // clipped to the volume's window
         int r0 = Math.max(reg[0], f.i0), r1 = Math.min(reg[2], f.i0 + nx - 1), s0 = Math.max(reg[1], f.j0), s1 = Math.min(reg[3], f.j0 + ny - 1);
         if (r0 > r1 || s0 > s1) {
            return;
         }
         GL20.glUseProgram(localProg);
         GL20.glUniform4i(uLoc[0], r0, s0, r1 - r0 + 1, s1 - s0 + 1);
         GL20.glUniform4i(uLoc[1], nx, ny, nz, f.occZ0);
         int iRef = Math.round((f.refX - f.refY) / f.cu), jRef = Math.round((f.refX + f.refY) / f.cv);
         GL20.glUniform4i(uLoc[2], f.refX, f.refY, iRef, jRef);
         GL20.glUniform4f(uLoc[3], f.cu, f.cv, f.cz, f.zLo);
         GL20.glUniform1i(uLoc[4], f.froxelLights ? f.lights : 0);
         GL20.glUniform4fv(uLoc[5], f.la);
         GL20.glUniform4fv(uLoc[6], f.lb);
         GL20.glUniform4fv(uLoc[7], f.lc);
         GL20.glUniform1i(uLoc[8], OCC_UNIT);
         GL20.glUniform1i(uLoc[9], TOP_UNIT);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, topTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL42.glBindImageTexture(3, localTex, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
         GL43.glDispatchCompute((r1 - r0 + 8) / 8, (s1 - s0 + 8) / 8, 1);
         GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
         GL42.glBindImageTexture(3, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
      }

      static final int[] localDims = new int[3];

      static int apdProg;
      private static int[] uApd;

      /** Render thread, at world entry (shaderWarmup): the direct volume program built before its first draw (~40 ms cold). */
      static void warm() {
         if (apdProg == 0 && Config.GOD_RAYS_AP_DIRECT && wanted()) {
            apdInit();
         }
      }

      private static boolean apdInit() {
         if (apdProg == 0) {
            apdProg = FogPass.link(AP_VERT, APD_FRAG, new String[] {"aPos"}, null);
            if (apdProg == 0) {
               Log.warn("god rays: direct light volume shader did not compile; the two-pass path");
               Config.GOD_RAYS_AP_DIRECT = false;
               return false;
            }
            uApd = new int[] {GL20.glGetUniformLocation(apdProg, "uMapA"), GL20.glGetUniformLocation(apdProg, "uMapB"), GL20.glGetUniformLocation(apdProg, "uVp"),
               GL20.glGetUniformLocation(apdProg, "uDepth"), GL20.glGetUniformLocation(apdProg, "uLight"), GL20.glGetUniformLocation(apdProg, "uPlanes"),
               GL20.glGetUniformLocation(apdProg, "uC"), GL20.glGetUniformLocation(apdProg, "uS"), GL20.glGetUniformLocation(apdProg, "uOrg"),
               GL20.glGetUniformLocation(apdProg, "uSoft"), GL20.glGetUniformLocation(apdProg, "uDoor"), GL20.glGetUniformLocation(apdProg, "uCap")};
            if (apVbo == 0) {
               apVbo = GL15.glGenBuffers();
            }
         }
         return true;
      }

      /**
       * The light volumes straight into the world picture, one draw: each prism's camera-facing faces, the pixel's view
       * column clipped against its half-spaces, dual-source blending {@code beam (1 - dst) + dst sqrt(1 + gain lit)}: a
       * screen blend for the dust in the shaft (display space: over black it is the beam, over white nothing), the sunlit
       * patch as the exact display-space multiplier. Overlapping prisms blend one after the other. No target, no clear, no
       * composite.
       */
      private static void apDirect(Frame f, int fbo) {
         if (!apdInit()) {
            return;
         }
         float kA = mapA[0], cA = mapA[1], kB = mapA[2], cB = mapA[3];
         int vpp = PRISM_FLOATS / 4;
         int n = f.prisms * vpp;
         int kept = 0;
         for (int pi = 0; pi < f.prisms; pi++) {
            float px0 = Float.MAX_VALUE, py0 = Float.MAX_VALUE, px1 = -Float.MAX_VALUE, py1 = -Float.MAX_VALUE;
            for (int i = pi * vpp; i < pi * vpp + vpp; i++) {
               float x = f.prismData[i * 4] + f.prismOrgX, y = f.prismData[i * 4 + 1] + f.prismOrgY, z = f.prismData[i * 4 + 2];
               float px = (x - y - cA) / kA, py = (x + y - 6F * z - cB) / kB;
               px0 = Math.min(px0, px);
               px1 = Math.max(px1, px);
               py0 = Math.min(py0, py);
               py1 = Math.max(py1, py);
            }
            if (!(px1 < vpNow[0] || px0 > vpNow[0] + vpNow[2] || py1 < vpNow[1] || py0 > vpNow[1] + vpNow[3])) {
               kept++;
               apBoxPixels += (long)((Math.min(px1, vpNow[0] + vpNow[2]) - Math.max(px0, vpNow[0])) * (Math.min(py1, vpNow[1] + vpNow[3]) - Math.max(py0, vpNow[1])));
            }
         }
         prismsOnScreen = kept;
         if (kept == 0) {
            return;
         }
         apFrames++;
         if (planeBuf == 0) {
            planeBuf = GL15.glGenBuffers();
            planeTex = GL11.glGenTextures();
         }
         if (planeFb == null || planeFb.capacity() < f.prisms * PLANE_FLOATS) {
            planeFb = BufferUtils.createFloatBuffer(Math.max(f.prisms * PLANE_FLOATS, PLANE_FLOATS * 256));
         }
         // the prisms and their planes go up only when rebuilt (or every frame under cloud shadows: the planes' light)
         boolean fresh = f.prismGen != apdGen;
         if (fresh || f.planesDynamic) {
            planeFb.clear();
            planeFb.put(f.planeData, 0, f.prisms * PLANE_FLOATS).flip();
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, planeBuf);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, planeFb, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + PLANE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, planeTex);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, planeBuf);
         } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + PLANE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, planeTex);
         }
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         if (apdVao == 0) {
            apdVao = GL30.glGenVertexArrays();
         }
         GL30.glBindVertexArray(apdVao); // its own attribute state: the game's (VAO 0) stays untouched
         if (fresh) {
            if (apBuf == null || apBuf.capacity() < n * 4) {
               apBuf = BufferUtils.createFloatBuffer(Math.max(n * 4, PRISM_FLOATS * 64));
            }
            apBuf.clear();
            apBuf.put(f.prismData, 0, n * 4).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, apVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, apBuf, GL15.GL_STREAM_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0L);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            apdGen = f.prismGen;
         }
         // the scene's depth tests the faces (read only: no depth writes, so sampling it meanwhile is defined); depth clamp:
         // a prism reaching past the depth range is never clipped
         boolean zTest = Config.GOD_RAYS_AP_DEPTH_TEST && mapA[4] != 0F && !lateNow; // (late: a colour-only target, no depth)
         if (zTest) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(mapA[4] < 0F ? GL11.GL_LEQUAL : GL11.GL_GEQUAL); // nearer = larger w
            GL11.glEnable(GL32.GL_DEPTH_CLAMP);
         } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
         }
         GL11.glDepthMask(false);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendEquation(GL14.GL_FUNC_ADD);
         GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL33.GL_SRC1_COLOR, GL11.GL_ZERO, GL11.GL_ONE);
         GL11.glColorMask(true, true, true, false);
         GL20.glUseProgram(apdProg);
         GL20.glUniform4f(uApd[0], kA, cA, kB, cB);
         GL20.glUniform4f(uApd[1], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         GL20.glUniform4f(uApd[2], vpNow[2], vpNow[3], Config.DEV_GOD_RAYS_SOFT_VIEW ? 3F : (Config.DEV_GOD_RAYS_SKIP & 16) != 0 ? 2F : 1F, zTest ? 0.5F : 0F); // w: half a square of bias toward the camera; z 2 (dev): no depth read
         GL20.glUniform1i(uApd[3], DEPTH_UNIT);
         GL20.glUniform4f(uApd[4], f.lx, f.ly, f.lz, 0F);
         GL20.glUniform1i(uApd[5], PLANE_UNIT);
         GL20.glUniform4f(uApd[6], color[0], color[1], color[2], 0F);
         GL20.glUniform4f(uApd[7], f.sigmaIn * 0.6123724F * f.dustGain, f.patch, f.devOn ? 1F : 0F,
            Config.GOD_RAYS_MOTES ? (float)((System.nanoTime() / 1e9) % 10000.0) + 1F : 0F); // w: the motes' clock (0: none)
         float soft = Config.GOD_RAYS_SOFT_PCT / 100F;
         GL20.glUniform4f(uApd[9], SOFT_W0 * soft, SOFT_K * soft, SOFT_FADE * soft, Config.GOD_RAYS_GLINT_PCT / 100F);
         GL20.glUniform4f(uApd[10], f.doorFx ? 1F : 0F, f.doorFx && f.caps > 0 ? 1F : 0F, f.caps, f.doorFx ? Config.GOD_RAYS_DOOR_DUST_PCT / 100F * 1.6F : 0F);
         if (f.caps > 0 && uApd[11] >= 0) {
            GL20.glUniform4fv(uApd[11], f.cap);
         }
         GL20.glUniform4f(uApd[8], f.prismOrgX, f.prismOrgY, f.prismOrgX + f.view.ox - (f.prismOrgY + f.view.oy), f.prismOrgX + f.view.ox + f.prismOrgY + f.view.oy); // zw: the reference square's absolute u, v
         if ((Config.DEV_GOD_RAYS_SKIP & 64) != 0) {
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE); // dev: a plain additive blend (the dual-source blend's own cost)
         }
         boolean coarse = coarseBegin();
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, (Config.DEV_GOD_RAYS_SKIP & 32) != 0 ? 0 : n); // dev 32: the pass with nothing drawn
         coarseEnd(coarse);
         if (f.bounces > 0 && f.doorFx) {
            bounces(f);
         }
         GL30.glBindVertexArray(0);
         if (zTest) {
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
         }
         GL11.glColorMask(true, true, true, true);
         GL11.glDepthMask(true);
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore(); // (the depth func and test too)
         if (fresh) {
            SpriteRenderer.ringBuffer.restoreVbos = true; // (the upload rebound GL_ARRAY_BUFFER; the attributes live in our VAO)
         }
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         apOn = false; // nothing for the composite to read
      }

      static int bncProg, bncVbo;
      private static int[] uBnc;
      private static FloatBuffer bncBuf;
      static long bncFrames, bncPixels, bncQuads;

      /**
       * godRaysBounce, in the light volume pass (its state; the multiplying blend): each sunlit patch as a disk light facing up
       * at its centroid, one screen box a patch over its reach (3 squares, its level); the surfaces above it in its room take
       * its light (the disk's form factor, the surface's facing from the depth), multiplied onto the picture.
       */
      private static void bounces(Frame f) {
         if (bncProg == 0) {
            bncProg = FogPass.link(LL_VERT, BOUNCE_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (bncProg == 0) {
               Log.warn("god rays: bounce shader did not compile; off");
               Config.GOD_RAYS_BOUNCE_OFF = true;
               bncProg = -1;
               return;
            }
            uBnc = new int[] {GL20.glGetUniformLocation(bncProg, "uMapA"), GL20.glGetUniformLocation(bncProg, "uMapB"), GL20.glGetUniformLocation(bncProg, "uVp"),
               GL20.glGetUniformLocation(bncProg, "uDepth"), GL20.glGetUniformLocation(bncProg, "uOccB"), GL20.glGetUniformLocation(bncProg, "uB"),
               GL20.glGetUniformLocation(bncProg, "uBC"), GL20.glGetUniformLocation(bncProg, "uOrgB"), GL20.glGetUniformLocation(bncProg, "uRefB")};
            bncVbo = GL15.glGenBuffers();
            bncBuf = BufferUtils.createFloatBuffer(MAX_BOUNCES * 6 * 3);
         }
         if (bncProg < 0 || occTex == 0) {
            return;
         }
         float kA = mapA[0], cA = mapA[1], kB = mapA[2], cB = mapA[3];
         bncBuf.clear();
         int quads = 0;
         for (int i = 0; i < f.bounces; i++) {
            float x = f.bounce[i * 8] + f.prismOrgX, y = f.bounce[i * 8 + 1] + f.prismOrgY, lq = f.bounce[i * 8 + 2] / LEVEL, R = 3F;
            float u0 = x - R - (y + R), u1 = x + R - (y - R), v0 = x - R + y - R - 6F * (lq + 1F), v1 = x + R + y + R - 6F * lq;
            float px0 = Math.max(vpNow[0], Math.min((u0 - cA) / kA, (u1 - cA) / kA)), px1 = Math.min(vpNow[0] + vpNow[2], Math.max((u0 - cA) / kA, (u1 - cA) / kA));
            float py0 = Math.max(vpNow[1], Math.min((v0 - cB) / kB, (v1 - cB) / kB)), py1 = Math.min(vpNow[1] + vpNow[3], Math.max((v0 - cB) / kB, (v1 - cB) / kB));
            if (px1 <= px0 || py1 <= py0) {
               continue;
            }
            bncPixels += (long)((px1 - px0) * (py1 - py0));
            bncBuf.put(px0).put(py0).put(i).put(px1).put(py0).put(i).put(px1).put(py1).put(i);
            bncBuf.put(px0).put(py0).put(i).put(px1).put(py1).put(i).put(px0).put(py1).put(i);
            quads++;
         }
         bncQuads += quads;
         if (quads == 0) {
            return;
         }
         bncFrames++;
         bncBuf.flip();
         GL20.glUseProgram(bncProg);
         GL20.glUniform4f(uBnc[0], kA, cA, kB, cB);
         GL20.glUniform4f(uBnc[1], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         GL20.glUniform4f(uBnc[2], vpNow[2], vpNow[3], Config.DEV_GOD_RAYS_BOUNCE_VIEW ? 2F : 1F, 0F);
         GL20.glUniform1i(uBnc[3], DEPTH_UNIT);
         GL20.glUniform1i(uBnc[4], OCC_UNIT);
         GL20.glUniform4fv(uBnc[5], java.util.Arrays.copyOfRange(bncB(f), 0, MAX_BOUNCES * 4));
         GL20.glUniform4fv(uBnc[6], java.util.Arrays.copyOfRange(bncB(f), MAX_BOUNCES * 4, MAX_BOUNCES * 8));
         GL20.glUniform4f(uBnc[7], f.prismOrgX, f.prismOrgY, 0F, 0F);
         GL20.glUniform4i(uBnc[8], f.prismOrgX + f.view.ox, f.prismOrgY + f.view.oy, occZ0Now, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ONE); // a multiplier
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bncVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bncBuf, GL15.GL_STREAM_DRAW);
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, quads * 6);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         // (the light volumes' attribute pointer in their VAO again for the next frame's draw)
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, apVbo);
         GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0L);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      }

      private static final float[] bncTmp = new float[MAX_BOUNCES * 8];

      /** The bounce uniforms: B (x, y, floor z, radius) for every patch, then C (radiance, room hash). */
      private static float[] bncB(Frame f) {
         java.util.Arrays.fill(bncTmp, 0F);
         for (int i = 0; i < f.bounces; i++) {
            System.arraycopy(f.bounce, i * 8, bncTmp, i * 4, 4);
            System.arraycopy(f.bounce, i * 8 + 4, bncTmp, MAX_BOUNCES * 4 + i * 4, 4);
         }
         return bncTmp;
      }

      // --- coarse shading (NV_shading_rate_image): the smooth passes shade once per 2x2 (4x4) pixels, the blend stays per
      // pixel (the beams and the airlight have no detail finer than that; the sunlit patch's edge takes 2 px steps)
      private static int vrsTex;
      private static boolean vrsChecked, vrsOk;
      private static final int[] VRS_2 = {org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_2X2_PIXELS_NV},
         VRS_4 = {org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_1_INVOCATION_PER_4X4_PIXELS_NV};

      static boolean coarseBegin() {
         int rate = Config.GOD_RAYS_COARSE;
         if (rate <= 1) {
            return false;
         }
         if (!vrsChecked) {
            vrsChecked = true;
            vrsOk = GL.getCapabilities().GL_NV_shading_rate_image;
            if (vrsOk) {
               vrsTex = GL11.glGenTextures(); // one palette entry (0) everywhere: 1024 x 512 texels of 16 px cover 16384 x 8192
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, vrsTex);
               GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R8UI, 1024, 512);
               ByteBuffer zero = BufferUtils.createByteBuffer(1024 * 512);
               GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1024, 512, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, zero);
               GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }
            Log.info("god rays: coarse shading " + (vrsOk ? "on (NV_shading_rate_image, " + rate + "x" + rate + ")" : "unavailable (no NV_shading_rate_image): full rate"));
         }
         if (!vrsOk) {
            return false;
         }
         org.lwjgl.opengl.NVShadingRateImage.glBindShadingRateImageNV(vrsTex);
         org.lwjgl.opengl.NVShadingRateImage.glShadingRateImagePaletteNV(0, 0, rate >= 4 ? VRS_4 : VRS_2);
         GL11.glEnable(org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_NV);
         return true;
      }

      static void coarseEnd(boolean on) {
         if (on) {
            GL11.glDisable(org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_NV);
            org.lwjgl.opengl.NVShadingRateImage.glBindShadingRateImageNV(0);
         }
      }

      private static int apdVao;
      static long apBoxPixels, apFrames;
      private static long apdGen = Long.MIN_VALUE;

      static int llProg, llVbo;
      static final int LL_SIDES = 16;
      private static final float[] llXy = new float[(LL_SIDES + 8) * 2], llPts = new float[(LL_SIDES + 8) * 4], llPoly = new float[(LL_SIDES + 8) * 4 + 8],
         llClip = new float[(LL_SIDES + 8) * 4 + 16], llTmp = new float[(LL_SIDES + 8) * 4 + 16];

      /** Sutherland-Hodgman of a convex polygon (x, y pairs) against a convex polygon's edges (either winding); in place. */
      private static int clipConvex(float[] p, int n, float[] c) {
         int cn = c.length / 2;
         double area = 0.0;
         for (int k = 0; k < cn; k++) {
            area += c[k * 2] * c[(k + 1) % cn * 2 + 1] - c[(k + 1) % cn * 2] * c[k * 2 + 1];
         }
         float sgn = area >= 0.0 ? 1F : -1F;
         for (int e = 0; e < cn && n > 0; e++) {
            float ex = c[e * 2], ey = c[e * 2 + 1], dx = c[(e + 1) % cn * 2] - ex, dy = c[(e + 1) % cn * 2 + 1] - ey;
            int m = 0;
            for (int k = 0; k < n; k++) {
               float ax = p[k * 2], ay = p[k * 2 + 1], bx = p[(k + 1) % n * 2], by = p[(k + 1) % n * 2 + 1];
               float da = sgn * (dx * (ay - ey) - dy * (ax - ex)), db = sgn * (dx * (by - ey) - dy * (bx - ex));
               if (da >= 0F) {
                  llTmp[m++] = ax;
                  llTmp[m++] = ay;
               }
               if (da >= 0F != db >= 0F) {
                  float t = da / (da - db);
                  llTmp[m++] = ax + (bx - ax) * t;
                  llTmp[m++] = ay + (by - ay) * t;
               }
            }
            n = m / 2;
            System.arraycopy(llTmp, 0, p, 0, m);
         }
         return n;
      }

      /** Andrew's monotone chain: the convex hull of n points (x, y pairs in p) into out, its point count returned. */
      private static final int[] hullIdx = new int[128], hullH = new int[256];

      private static int hull(float[] p, int n, float[] out) {
         int[] idx = hullIdx, h = hullH;
         for (int k = 0; k < n; k++) { // insertion sort by x, then y (a few dozen points)
            int v = k, t = k - 1;
            while (t >= 0 && (p[idx[t] * 2] > p[v * 2] || p[idx[t] * 2] == p[v * 2] && p[idx[t] * 2 + 1] > p[v * 2 + 1])) {
               idx[t + 1] = idx[t];
               t--;
            }
            idx[t + 1] = v;
         }
         int k = 0;
         for (int t = 0; t < n; t++) {
            while (k >= 2 && cross(p, h[k - 2], h[k - 1], idx[t]) <= 0F) {
               k--;
            }
            h[k++] = idx[t];
         }
         for (int t = n - 2, lo = k + 1; t >= 0; t--) {
            while (k >= lo && cross(p, h[k - 2], h[k - 1], idx[t]) <= 0F) {
               k--;
            }
            h[k++] = idx[t];
         }
         k--; // (the last point is the first)
         for (int t = 0; t < k; t++) {
            out[t * 2] = p[h[t] * 2];
            out[t * 2 + 1] = p[h[t] * 2 + 1];
         }
         return k;
      }

      private static float cross(float[] p, int o, int a, int b) {
         return (p[a * 2] - p[o * 2]) * (p[b * 2 + 1] - p[o * 2 + 1]) - (p[a * 2 + 1] - p[o * 2 + 1]) * (p[b * 2] - p[o * 2]);
      }

      private static final float[] spPts = new float[64];
      private static int spTris;

      /** A spill's frustum outline (window px) into llPoly, its point count returned. */
      private static int spillOutline(Frame f, int i, float lx, float ly, float lzLevels, float reach, float ox, float oy, float kA, float cA, float kB, float cB) {
         float ax0 = f.lq[i * 4] + ox, ay0 = f.lq[i * 4 + 1] + oy, ax1 = f.lq[i * 4 + 2] + ox, ay1 = f.lq[i * 4 + 3] + oy;
         float zs = f.lb[i * 4], zh = f.lb[i * 4 + 1];
         float lzs = lzLevels * LEVEL, floor = (float)Math.floor(lzLevels + 0.01F) * LEVEL;
         int m = 0;
         for (int a = 0; a <= 2; a++) {
            for (int b = 0; b <= 2; b++) {
               float qx = ax0 + (ax1 - ax0) * a * 0.5F, qy = ay0 + (ay1 - ay0) * a * 0.5F, qz = (zs + (zh - zs) * b * 0.5F) * LEVEL;
               boolean corner = a != 1 && b != 1;
               float dx = qx - lx, dy = qy - ly, dz = qz - lzs;
               float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
               float t = reach * 1.08F / Math.max(len, 1e-3F);
               if (dz < -1e-4F) {
                  t = Math.min(t, (floor - lzs) / dz);
               }
               float ex = lx + dx * t, ey = ly + dy * t, ez = lzs + dz * t;
               if (corner) {
                  spPts[m++] = ((qx - qy) - cA) / kA;
                  spPts[m++] = ((qx + qy - 6F * qz / LEVEL) - cB) / kB;
               }
               spPts[m++] = ((ex - ey) - cA) / kA;
               spPts[m++] = ((ex + ey - 6F * ez / LEVEL) - cB) / kB;
            }
         }
         return hull(spPts, m / 2, llPoly);
      }

      /** The spill's outline clipped to the light's screen box, as triangles into llBuf. */
      private static void spillTris(int n, float px0, float py0, float px1, float py1, int i) {
         int m = clipPoly(llPoly, n, px0, py0, px1, py1);
         if (m < 3) {
            return;
         }
         float area = 0F;
         for (int k = 0; k < m; k++) {
            int k2 = (k + 1) % m;
            area += llClip[k * 2] * llClip[k2 * 2 + 1] - llClip[k2 * 2] * llClip[k * 2 + 1];
         }
         llPixels += (long)Math.abs(area * 0.5F);
         for (int k = 1; k + 1 < m; k++) {
            llBuf.put(llClip[0]).put(llClip[1]).put(i);
            llBuf.put(llClip[k * 2]).put(llClip[k * 2 + 1]).put(i);
            llBuf.put(llClip[k * 2 + 2]).put(llClip[k * 2 + 3]).put(i);
            spTris++;
         }
      }

      /** Sutherland-Hodgman of a convex polygon (x, y pairs in llPoly) against a box; the result in llClip, its point count returned. */
      private static int clipPoly(float[] in, int n, float x0, float y0, float x1, float y1) {
         System.arraycopy(in, 0, llClip, 0, n * 2);
         for (int e = 0; e < 4 && n > 0; e++) {
            float[] src = llClip;
            int m = 0;
            for (int k = 0; k < n; k++) {
               float ax = src[k * 2], ay = src[k * 2 + 1], bx = src[(k + 1) % n * 2], by = src[(k + 1) % n * 2 + 1];
               float da = e == 0 ? ax - x0 : e == 1 ? x1 - ax : e == 2 ? ay - y0 : y1 - ay;
               float db = e == 0 ? bx - x0 : e == 1 ? x1 - bx : e == 2 ? by - y0 : y1 - by;
               if (da >= 0F) {
                  llTmp[m++] = ax;
                  llTmp[m++] = ay;
               }
               if (da >= 0F != db >= 0F) {
                  float t = da / (da - db);
                  llTmp[m++] = ax + (bx - ax) * t;
                  llTmp[m++] = ay + (by - ay) * t;
               }
            }
            n = m / 2;
            System.arraycopy(llTmp, 0, llClip, 0, m);
         }
         return n;
      }
      private static int[] uLl;
      private static FloatBuffer llBuf;
      static long llQuads, llPixels, llFrames;

      /**
       * The local lights' airlight (Sun, Ramamoorthi, Narasimhan & Nayar 2005: a point light's single scattering along a ray
       * has a closed form), one screen quad a light over its reach, drawn straight into the world picture with a screen
       * blend; the pixel's view column is clipped to the light's reach, its level and, for a torch or a headlight, its cone.
       */
      private static void localLights(Frame f, int fbo) {
         if (llProg == 0) {
            llProg = FogPass.link(LL_VERT, LL_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (llProg == 0) {
               Log.warn("god rays: local light shaders did not compile; off");
               Config.GOD_RAYS_LOCAL = false;
               return;
            }
            uLl = new int[] {GL20.glGetUniformLocation(llProg, "uMapA"), GL20.glGetUniformLocation(llProg, "uMapB"), GL20.glGetUniformLocation(llProg, "uVp"),
               GL20.glGetUniformLocation(llProg, "uDepth"), GL20.glGetUniformLocation(llProg, "uLA"), GL20.glGetUniformLocation(llProg, "uLB"),
               GL20.glGetUniformLocation(llProg, "uLC"), GL20.glGetUniformLocation(llProg, "uOrgL"), GL20.glGetUniformLocation(llProg, "uLP")};
            llVbo = GL15.glGenBuffers();
         }
         float kA = mapA[0], cA = mapA[1], kB = mapA[2], cB = mapA[3];
         float ox = f.refX - f.view.ox, oy = f.refY - f.view.oy;
         if (llBuf == null) {
            llBuf = BufferUtils.createFloatBuffer(MAX_LIGHTS * (LL_SIDES + 6) * 3 * 3);
         }
         llBuf.clear();
         int quads = 0, tris = 0;
         for (int i = 0; i < f.lights; i++) {
            float x = f.la[i * 4] + ox, y = f.la[i * 4 + 1] + oy, z = f.la[i * 4 + 2], r = f.la[i * 4 + 3];
            float lq = (float)Math.floor(z + 0.01F);
            // the reach's world box: a point light's sphere; a spot's cone (apex, and its far disk r tan(theta) round the
            // axis): each within the light's level
            float bx0 = x - r, bx1 = x + r, by0 = y - r, by1 = y + r;
            float cosc = f.lb[i * 4 + 2];
            if (cosc > -1.5F) {
               float ax = f.lb[i * 4], ay = f.lb[i * 4 + 1];
               float rad = Math.min(r, r * (float)Math.sqrt(Math.max(0F, 1F - cosc * cosc)) / Math.max(0.2F, cosc));
               float fx = x + ax * r, fy = y + ay * r;
               // the cone's box, within the reach's sphere (a wide cone's far disk would reach past it)
               bx0 = Math.max(x - r, Math.min(x, fx) - rad);
               bx1 = Math.min(x + r, Math.max(x, fx) + rad);
               by0 = Math.max(y - r, Math.min(y, fy) - rad);
               by1 = Math.min(y + r, Math.max(y, fy) + rad);
            }
            // (u, v) of that box over the level's height: u = x - y, v = x + y - 6 z
            float u0 = bx0 - by1, u1 = bx1 - by0, v0 = bx0 + by0 - 6F * (lq + 1F), v1 = bx1 + by1 - 6F * lq;
            float px0 = Math.min((u0 - cA) / kA, (u1 - cA) / kA), px1 = Math.max((u0 - cA) / kA, (u1 - cA) / kA);
            float py0 = Math.min((v0 - cB) / kB, (v1 - cB) / kB), py1 = Math.max((v0 - cB) / kB, (v1 - cB) / kB);
            px0 = Math.max(px0, vpNow[0]);
            py0 = Math.max(py0, vpNow[1]);
            px1 = Math.min(px1, vpNow[0] + vpNow[2]);
            py1 = Math.min(py1, vpNow[1] + vpNow[3]);
            if (px1 <= px0 || py1 <= py0) {
               continue;
            }
            // the reach's own footprint, not its box: in the level's plane the circle of the reach (a 16-gon round it), for a
            // spot cut by its cone (the triangle apex, far disk; r tan(theta) wide), then swept over the level's height
            // (v = x + y - 6 z) as the convex hull of that polygon at the floor and at the top
            int n = 0;
            float rc = r * 1.0196F; // (1 / cos(pi / 16): the 16-gon round the circle)
            if (f.lc[i * 4 + 3] > 2.5F) {
               // a spill (godRaysSpill): its frustum's own outline on the screen, the convex hull of the opening's corners and
               // where the rays from the light through them (and through the middle of its edges and its centre) reach the
               // ground or the reach (a little past it: the sphere's cap); no level sweep
               int m2 = spillOutline(f, i, x, y, z, r, ox, oy, kA, cA, kB, cB);
               if (m2 < 3) {
                  continue;
               }
               spillTris(m2, px0, py0, px1, py1, i);
               quads++;
               continue;
            }
            for (int k = 0; k < LL_SIDES; k++) {
               double th = (k + 0.5) * 2.0 * Math.PI / LL_SIDES;
               llXy[n++] = x + rc * (float)Math.cos(th);
               llXy[n++] = y + rc * (float)Math.sin(th);
            }
            n /= 2;
            if (cosc > -1.5F) {
               float ax = f.lb[i * 4], ay = f.lb[i * 4 + 1];
               float al = (float)Math.hypot(ax, ay);
               if (al > 1e-4F) {
                  ax /= al;
                  ay /= al;
                  float tan = Math.min(50F, (float)Math.sqrt(Math.max(0F, 1F - cosc * cosc)) / Math.max(0.02F, cosc));
                  float fx = x + ax * r * 1.02F, fy = y + ay * r * 1.02F, w = r * 1.02F * tan;
                  float[] tri = {x - ax * 0.05F, y - ay * 0.05F, fx - ay * w, fy + ax * w, fx + ay * w, fy - ax * w};
                  n = clipConvex(llXy, n, tri);
               }
            }
            if (n < 3) {
               continue;
            }
            int m = 0;
            for (int k = 0; k < n; k++) {
               float wx = llXy[k * 2], wy = llXy[k * 2 + 1], cu = wx - wy, sv = wx + wy;
               llPts[m++] = (cu - cA) / kA;
               llPts[m++] = (sv - 6F * lq - cB) / kB;
               llPts[m++] = (cu - cA) / kA;
               llPts[m++] = (sv - 6F * (lq + 1F) - cB) / kB;
            }
            m = hull(llPts, m / 2, llPoly) * 2;
            m = clipPoly(llPoly, m / 2, px0, py0, px1, py1);
            if (m < 3) {
               continue;
            }
            float area = 0F;
            for (int k = 0; k < m; k++) {
               int k2 = (k + 1) % m;
               area += llClip[k * 2] * llClip[k2 * 2 + 1] - llClip[k2 * 2] * llClip[k * 2 + 1];
            }
            llPixels += (long)Math.abs(area * 0.5F);
            for (int k = 1; k + 1 < m; k++) {
               llBuf.put(llClip[0]).put(llClip[1]).put(i);
               llBuf.put(llClip[k * 2]).put(llClip[k * 2 + 1]).put(i);
               llBuf.put(llClip[k * 2 + 2]).put(llClip[k * 2 + 3]).put(i);
               tris++;
            }
            quads++;
         }
         tris += spTris;
         spTris = 0;
         if (quads == 0) {
            return;
         }
         llQuads += quads;
         llFrames++;
         llBuf.flip();
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendEquation(GL14.GL_FUNC_ADD);
         GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE); // screen
         GL11.glColorMask(true, true, true, false);
         GL20.glUseProgram(llProg);
         GL20.glUniform4f(uLl[0], kA, cA, kB, cB);
         GL20.glUniform4f(uLl[1], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         GL20.glUniform4f(uLl[2], vpNow[2], vpNow[3], 1F, 0F);
         GL20.glUniform1i(uLl[3], DEPTH_UNIT);
         GL20.glUniform4fv(uLl[4], f.la);
         GL20.glUniform4fv(uLl[5], f.lb);
         GL20.glUniform4fv(uLl[6], f.lc);
         if (uLl[8] >= 0) {
            GL20.glUniform4fv(uLl[8], f.lp);
         }
         GL20.glUniform4f(uLl[7], ox, oy, Math.max(0.05F, Config.GOD_RAYS_LOCAL_CORE_PCT / 100F), 0F); // z: the lights' core radius (squares)
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, llVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, llBuf, GL15.GL_STREAM_DRAW);
         for (int i = 0; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
         boolean coarse = coarseBegin();
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, tris * 3);
         coarseEnd(coarse);
         GL11.glColorMask(true, true, true, true);
         GL11.glDepthMask(true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      static int apcProg;
      private static int[] uApc;

      /**
       * The light volumes onto the world picture, before anything else draws over it: a texture barrier, then one quad over
       * their bounds that reads the target (half size) and, where a prism covers the pixel, the world colour at that very
       * pixel (each pixel read and written once: the barrier makes that defined) and writes
       * {@code sqrt(c^2 (1 + gain lit) + C dust)}; pixels no prism covers discard after one fetch. Cost follows the beams'
       * area; nothing when no sunlit window is on screen.
       */
      private static void apComposite(Frame f, int fbo) {
         int colorTex = colorTexture(fbo);
         if (colorTex == 0 || !(GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_texture_barrier || GL.getCapabilities().GL_NV_texture_barrier)) {
            return;
         }
         if (apcProg == 0) {
            apcProg = FogPass.link(QUAD_VERT, APC_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (apcProg == 0) {
               Log.warn("god rays: light volume composite did not compile");
               Config.GOD_RAYS_APERTURES = false;
               return;
            }
            uApc = new int[] {GL20.glGetUniformLocation(apcProg, "uWorld"), GL20.glGetUniformLocation(apcProg, "uAp"), GL20.glGetUniformLocation(apcProg, "uVp"),
               GL20.glGetUniformLocation(apcProg, "uC"), GL20.glGetUniformLocation(apcProg, "uS")};
         }
         if (quadVbo == 0) {
            quadVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1F, -1F, 3F, -1F, -1F, 3F}, GL15.GL_STATIC_DRAW);
         }
         if (GL.getCapabilities().OpenGL45) {
            org.lwjgl.opengl.GL45.glTextureBarrier();
         } else if (GL.getCapabilities().GL_ARB_texture_barrier) {
            org.lwjgl.opengl.ARBTextureBarrier.glTextureBarrier();
         } else {
            org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV();
         }
         int vx = (int)vpNow[0], vy = (int)vpNow[1], vw = (int)vpNow[2], vh = (int)vpNow[3];
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         int sx0 = vx + (int)Math.floor(apBounds[0] * vw), sy0 = vy + (int)Math.floor(apBounds[1] * vh);
         int sx1 = vx + (int)Math.ceil(apBounds[2] * vw), sy1 = vy + (int)Math.ceil(apBounds[3] * vh);
         GL11.glScissor(sx0, sy0, Math.max(0, sx1 - sx0), Math.max(0, sy1 - sy0));
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glColorMask(true, true, true, false);
         GL20.glUseProgram(apcProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + APC_WORLD_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, apTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(uApc[0], APC_WORLD_UNIT);
         GL20.glUniform1i(uApc[1], AUX_UNIT);
         GL20.glUniform4f(uApc[2], vx, vy, vw, vh);
         GL20.glUniform4f(uApc[3], color[0], color[1], color[2], 0F);
         GL20.glUniform4f(uApc[4], apScale[0], f.patch, f.view_, 0F);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
         for (int i = 0; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glColorMask(true, true, true, true);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      /** The god ray buffer by a compute dispatch (the same uniforms as lowPass; the buffer already exists). */
      private static void lowCompute(Frame f) {
         int vw = (int)vpNow[2], vh = (int)vpNow[3];
         int[] u = uLowCs;
         GL20.glUseProgram(lowCsProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, fTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(u[0], VOL_UNIT);
         GL20.glUniform1i(u[1], DEPTH_UNIT);
         boolean roomLight = !(Config.GOD_RAYS_APERTURES && f.apertures);
         GL20.glUniform4f(u[2], (float)vw / lowW, (float)vh / lowH, roomLight ? 1F : 0F, 0F);
         GL20.glUniform4f(u[3], mapX[0], mapX[1], mapX[2], mapX[3]);
         GL20.glUniform4f(u[4], mapY[0], mapY[1], mapY[2], mapY[3]);
         GL20.glUniform4f(u[5], mapZ[0], mapZ[1], mapZ[2], mapZ[3]);
         GL20.glUniform4f(u[6], color[0], color[1], color[2], groundTc);
         GL20.glUniform4f(u[7], lightDir[0], lightDir[1], lightDir[2], f.patch);
         boolean shade = "shade".equals(Config.GOD_RAYS_HAZE_MODE) || "auto".equals(Config.GOD_RAYS_HAZE_MODE) && stockFog > 0.05F;
         GL20.glUniform4f(u[8], f.view_, shade ? 1F : 0F, 2.2F, 0F);
         GL20.glUniform4f(u[9], mapA[0], mapA[1], mapA[2], mapA[3]);
         GL20.glUniform4f(u[10], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         if (localOn && localTex != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LOCAL_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, localTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(u[16], LOCAL_UNIT);
            GL20.glUniform4f(u[17], 1F, 0F, 0F, 0F);
         } else {
            GL20.glUniform4f(u[17], 0F, 0F, 0F, 0F);
         }
         int cloudTex = CloudShadow.hazeField(f.view.ox, f.view.oy, cl);
         if (cloudTex != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLOUD_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(u[11], CLOUD_UNIT);
            GL20.glUniform4f(u[12], cl[0], cl[1], cl[2], cl[3]);
            GL20.glUniform4f(u[13], cl[4], cl[5], cl[6], cl[7]);
            GL20.glUniform4f(u[14], cl[7], cl[8], cl[9], cl[10]);
            GL20.glUniform4f(u[15], cl[11], f.zLo + 1.2F, 0F, 0F);
         } else {
            GL20.glUniform4f(u[12], 0F, 0F, 0F, 0F);
         }
         GL20.glUniform2i(u[LOW_NAMES.length], lowW, lowH);
         GL42.glBindImageTexture(4, lowTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
         GL43.glDispatchCompute((lowW + 7) / 8, (lowH + 7) / 8, 1);
         GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
         GL42.glBindImageTexture(4, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
      }

      static final float[] mapA = new float[6], vpNow = new float[4];
      static int lowTex, lowFbo, lowW, lowH, lowProg, quadVbo;
      private static int[] uLow;
      private static final String[] LOW_NAMES = {"uVol", "uDepth", "uLow", "uX", "uY", "uZ", "uC", "uL", "uDev", "uMapA", "uMapB", "uCloud", "uCl0", "uCl1", "uCl2", "uCl3", "uLocal", "uLocOn"};
      private static final float[] cl = new float[12];

      /** The quarter-size buffer: in-scatter and the surface multiplier from the volume at each block's middle pixel. */
      static int lowCsProg;
      private static int[] uLowCs;

      private static void lowPass(Frame f, int fbo) {
         int div0 = Math.max(2, Math.min(16, Config.GOD_RAYS_BUFFER_DIV));
         boolean sized = lowTex != 0 && lowW == Math.max(1, ((int)vpNow[2] + div0 - 1) / div0) && lowH == Math.max(1, ((int)vpNow[3] + div0 - 1) / div0);
         if (Config.GOD_RAYS_LOW_COMPUTE && sized && lowCsProg >= 0) {
            if (lowCsProg == 0) {
               lowCsProg = computeProgram(LOW_CS, "god ray buffer (compute)");
               if (lowCsProg == 0) {
                  lowCsProg = -1;
               } else {
                  uLowCs = new int[LOW_NAMES.length + 1];
                  for (int i = 0; i < LOW_NAMES.length; i++) {
                     uLowCs[i] = GL20.glGetUniformLocation(lowCsProg, LOW_NAMES[i]);
                  }
                  uLowCs[LOW_NAMES.length] = GL20.glGetUniformLocation(lowCsProg, "uLowSize");
               }
            }
            if (lowCsProg > 0) {
               lowCompute(f);
               return;
            }
         }
         if (lowProg == 0) {
            lowProg = FogPass.link(QUAD_VERT, LOW_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (lowProg == 0) {
               failed = true;
               why = "god ray buffer shader did not compile";
               screenOn = false;
               return;
            }
            uLow = new int[LOW_NAMES.length];
            for (int i = 0; i < LOW_NAMES.length; i++) {
               uLow[i] = GL20.glGetUniformLocation(lowProg, LOW_NAMES[i]);
            }
            quadVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1F, -1F, 3F, -1F, -1F, 3F}, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         }
         int vw = (int)vpNow[2], vh = (int)vpNow[3];
         int div = Math.max(2, Math.min(16, Config.GOD_RAYS_BUFFER_DIV));
         int lw = Math.max(1, (vw + div - 1) / div), lh = Math.max(1, (vh + div - 1) / div);
         if (lowTex == 0 || lw != lowW || lh != lowH) {
            if (lowTex != 0) {
               GL11.glDeleteTextures(lowTex);
               GL30.glDeleteFramebuffers(lowFbo);
            }
            lowW = lw;
            lowH = lh;
            lowTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowTex);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, lw, lh);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            lowFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, lowTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            Log.info("god rays: buffer " + lw + "x" + lh + " RGBA8 (1/" + div + " of " + vw + "x" + vh + ")");
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFbo);
         GL11.glViewport(0, 0, lw, lh);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glColorMask(true, true, true, true);
         GL20.glUseProgram(lowProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, fTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(uLow[0], VOL_UNIT);
         GL20.glUniform1i(uLow[1], DEPTH_UNIT);
         boolean roomLight = !(Config.GOD_RAYS_APERTURES && f.apertures);
         GL20.glUniform4f(uLow[2], (float)vw / lw, (float)vh / lh, roomLight ? 1F : 0F, 0F);
         GL20.glUniform4f(uLow[3], mapX[0], mapX[1], mapX[2], mapX[3]);
         GL20.glUniform4f(uLow[4], mapY[0], mapY[1], mapY[2], mapY[3]);
         GL20.glUniform4f(uLow[5], mapZ[0], mapZ[1], mapZ[2], mapZ[3]);
         GL20.glUniform4f(uLow[6], color[0], color[1], color[2], groundTc);
         GL20.glUniform4f(uLow[7], lightDir[0], lightDir[1], lightDir[2], f.patch);
         boolean shade = "shade".equals(Config.GOD_RAYS_HAZE_MODE) || "auto".equals(Config.GOD_RAYS_HAZE_MODE) && stockFog > 0.05F;
         GL20.glUniform4f(uLow[8], f.view_, shade ? 1F : 0F, 2.2F, 0F); // y shade mode, z its strength (the missing light's share of the fog's brightness)
         GL20.glUniform4f(uLow[9], mapA[0], mapA[1], mapA[2], mapA[3]);
         GL20.glUniform4f(uLow[10], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         if (localOn && localTex != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + LOCAL_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, localTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(uLow[16], LOCAL_UNIT);
            GL20.glUniform4f(uLow[17], 1F, 0F, 0F, 0F);
         } else {
            GL20.glUniform4f(uLow[17], 0F, 0F, 0F, 0F);
         }
         int cloudTex = CloudShadow.hazeField(f.view.ox, f.view.oy, cl); // (the compute path sets the same uniforms on its program)
         if (cloudTex != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLOUD_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(uLow[11], CLOUD_UNIT);
            GL20.glUniform4f(uLow[12], cl[0], cl[1], cl[2], cl[3]);
            GL20.glUniform4f(uLow[13], cl[4], cl[5], cl[6], cl[7]);
            GL20.glUniform4f(uLow[14], cl[7], cl[8], cl[9], cl[10]);
            GL20.glUniform4f(uLow[15], cl[11], f.zLo + 1.2F, 0F, 0F);
         } else {
            GL20.glUniform4f(uLow[12], 0F, 0F, 0F, 0F);
         }
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
         for (int i = 0; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport((int)vpNow[0], (int)vpNow[1], vw, vh);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      // --- method blur: screen-space directional blur of the lit surfaces (Mitchell 2007; Sousa's three passes)
      static int blurProg, maskProg, blurTex, blurFbo;
      private static int[] uBlur, uMask;
      private static final java.util.HashMap<Integer, Integer> FBO_COLOR = new java.util.HashMap<>();

      /** The world framebuffer's colour texture (asked once per framebuffer). */
      private static int colorTexture(int fbo) {
         Integer t = FBO_COLOR.get(fbo);
         if (t == null) {
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            t = type == GL11.GL_TEXTURE ? GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) : 0;
            FBO_COLOR.put(fbo, t);
         }
         return t;
      }

      /**
       * The god ray buffer from the picture alone: a bright pass of the world colour at the buffer's size (the lit
       * surfaces, windows), then three passes of 8 taps along the light's screen direction toward the light, 1, 8 and 64
       * texels apart (512 effective taps), each tap weighted by a decay; rgb the streaks, a 1 (nothing darkens).
       */
      private static void blurPass(Frame f, int fbo) {
         int colorTex = colorTexture(fbo);
         if (colorTex == 0) {
            lowPass(f, fbo);
            return;
         }
         if (maskProg == 0) {
            maskProg = FogPass.link(QUAD_VERT, BLUR_MASK_FRAG, new String[] {"aPos"}, new String[] {"o"});
            blurProg = FogPass.link(QUAD_VERT, BLUR_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (maskProg == 0 || blurProg == 0) {
               Log.warn("god rays: blur shaders did not compile; volume");
               Config.GOD_RAYS_METHOD = "volume";
               return;
            }
            uMask = new int[] {GL20.glGetUniformLocation(maskProg, "uColor"), GL20.glGetUniformLocation(maskProg, "uLow"), GL20.glGetUniformLocation(maskProg, "uC"),
               GL20.glGetUniformLocation(maskProg, "uInv")};
            uBlur = new int[] {GL20.glGetUniformLocation(blurProg, "uSrc"), GL20.glGetUniformLocation(blurProg, "uStep"), GL20.glGetUniformLocation(blurProg, "uFinal")};
         }
         lowPass(f, fbo); // the buffer's size and first contents (and the quad); overwritten below
         if (blurTex == 0 || lowW != blurW || lowH != blurH) {
            if (blurTex != 0) {
               GL11.glDeleteTextures(blurTex);
               GL30.glDeleteFramebuffers(blurFbo);
            }
            blurW = lowW;
            blurH = lowH;
            blurTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, lowW, lowH);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            blurFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, blurTex, 0);
         }
         // the light's travel on screen, in buffer texels per unit (u = x - y, v = x + y - 6 z)
         double du = -f.lx + f.ly, dv = -f.lx - f.ly + 6.0 * f.lz / LEVEL;
         double sx = du / mapA[0] / (vpNow[2] / lowW), sy = dv / mapA[2] / (vpNow[3] / lowH);
         double len = Math.hypot(sx, sy);
         if (len < 1e-6) {
            return;
         }
         float tx = (float)(-sx / len / lowW), ty = (float)(-sy / len / lowH); // toward the light, in uv per texel
         GL11.glViewport(0, 0, lowW, lowH);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
         for (int i = 0; i < 5; i++) {
            GL20.glDisableVertexAttribArray(i);
         }
         GL20.glEnableVertexAttribArray(0);
         GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
         // mask into lowTex (the world colour at the buffer's size, its bright part)
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFbo);
         GL20.glUseProgram(maskProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
         GL20.glUniform1i(uMask[0], AUX_UNIT);
         GL20.glUniform4f(uMask[1], vpNow[0], vpNow[1], vpNow[2], vpNow[3]);
         GL20.glUniform4f(uMask[2], color[0], color[1], color[2], 0F);
         GL20.glUniform4f(uMask[3], 1F / lowW, 1F / lowH, 0F, 0F);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         // three passes, ping-pong lowTex -> blurTex -> lowTex -> blurTex, the last back into lowTex
         GL20.glUseProgram(blurProg);
         GL20.glUniform1i(uBlur[0], AUX_UNIT);
         float[] steps = {1F, 8F, 64F};
         int[] src = {lowTex, blurTex, lowTex};
         int[] dst = {blurFbo, lowFbo, blurFbo};
         for (int k = 0; k < 3; k++) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dst[k]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, src[k]);
            GL20.glUniform4f(uBlur[1], tx * steps[k], ty * steps[k], k == 2 ? 1F : 0F, 0F);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         }
         // the result is in blurTex: swap the names so the composite reads it
         int t = lowTex;
         lowTex = blurTex;
         blurTex = t;
         t = lowFbo;
         lowFbo = blurFbo;
         blurFbo = t;
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport((int)vpNow[0], (int)vpNow[1], (int)vpNow[2], (int)vpNow[3]);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      static int blurW, blurH;

      // --- method epipolar (Engelhardt & Dachsbacher 2010; the ortho view and the directional light make every epipolar
      // line parallel to the light's screen direction)
      static int epiSampleProg, epiResolveProg, epiTex, epiFbo, epiW, epiH, epiResolveFbo;
      private static final String[] EPI_NAMES = {"uV", "uDepth", "uX", "uY", "uZ", "uC", "uSig", "uGrid", "uMarchN", "uEpi", "uEpi2", "uVp0", "uSamples"};
      private static int[] uEpiS, uEpiR;
      static final int EPI_SPACING = 8;

      private static void epipolarPass(Frame f, int fbo) {
         if (epiSampleProg == 0) {
            epiSampleProg = FogPass.link(QUAD_VERT, EPI_SAMPLE_FRAG, new String[] {"aPos"}, new String[] {"o"});
            epiResolveProg = FogPass.link(QUAD_VERT, EPI_RESOLVE_FRAG, new String[] {"aPos"}, new String[] {"o"});
            if (epiSampleProg == 0 || epiResolveProg == 0) {
               Log.warn("god rays: epipolar shaders did not compile; volume");
               Config.GOD_RAYS_METHOD = "volume";
               return;
            }
            uEpiS = new int[EPI_NAMES.length];
            uEpiR = new int[EPI_NAMES.length];
            for (int i = 0; i < EPI_NAMES.length; i++) {
               uEpiS[i] = GL20.glGetUniformLocation(epiSampleProg, EPI_NAMES[i]);
               uEpiR[i] = GL20.glGetUniformLocation(epiResolveProg, EPI_NAMES[i]);
            }
         }
         lowPass(f, fbo); // the buffer's size (and the quad)
         // the light's travel on the buffer: its direction d, the lines' normal n; the buffer's corners in (s, t)
         double du = -f.lx + f.ly, dv = -f.lx - f.ly + 6.0 * f.lz / LEVEL;
         double sx = du / mapA[0] / (vpNow[2] / lowW), sy = dv / mapA[2] / (vpNow[3] / lowH);
         double len = Math.hypot(sx, sy);
         if (len < 1e-6) {
            return;
         }
         float dx = (float)(sx / len), dy = (float)(sy / len), nx = -dy, ny = dx;
         float smin = Float.MAX_VALUE, smax = -Float.MAX_VALUE, tmin = Float.MAX_VALUE, tmax = -Float.MAX_VALUE;
         for (int c = 0; c < 4; c++) {
            float x = (c & 1) == 0 ? 0F : lowW, y = (c & 2) == 0 ? 0F : lowH;
            float ss = x * dx + y * dy, tt = x * nx + y * ny;
            smin = Math.min(smin, ss);
            smax = Math.max(smax, ss);
            tmin = Math.min(tmin, tt);
            tmax = Math.max(tmax, tt);
         }
         smin -= EPI_SPACING;
         tmin -= 1F;
         int ew = (int)Math.ceil((smax - smin) / EPI_SPACING) + 2, eh = (int)Math.ceil(tmax - tmin) + 2;
         if (epiTex == 0 || ew > epiW || eh > epiH) {
            if (epiTex != 0) {
               GL11.glDeleteTextures(epiTex);
               GL30.glDeleteFramebuffers(epiFbo);
            }
            epiW = Math.max(ew, epiW);
            epiH = Math.max(eh, epiH);
            epiTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, epiTex);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, epiW, epiH);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            epiFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, epiFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, epiTex, 0);
            Log.info("god rays: epipolar samples " + epiW + "x" + epiH + " (lines x samples of " + lowW + "x" + lowH + ")");
         }
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_V_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, vTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_HIST_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, epiTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         boolean roomLight = !(Config.GOD_RAYS_APERTURES && f.apertures);
         float px = vpNow[2] / lowW;
         for (int pass = 0; pass < 2; pass++) {
            int prog = pass == 0 ? epiSampleProg : epiResolveProg;
            int[] u = pass == 0 ? uEpiS : uEpiR;
            if (pass == 0) {
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, epiFbo);
               GL11.glViewport(0, 0, ew, eh);
            } else {
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lowFbo);
               GL11.glViewport(0, 0, lowW, lowH);
            }
            GL20.glUseProgram(prog);
            GL20.glUniform1i(u[0], MARCH_V_UNIT);
            GL20.glUniform1i(u[1], DEPTH_UNIT);
            GL20.glUniform4f(u[2], mapX[0], mapX[1], mapX[2], mapX[3]);
            GL20.glUniform4f(u[3], mapY[0], mapY[1], mapY[2], mapY[3]);
            GL20.glUniform4f(u[4], mapZ[0], mapZ[1], mapZ[2], mapZ[3]);
            GL20.glUniform4f(u[5], color[0], color[1], color[2], groundTc);
            GL20.glUniform4f(u[6], f.sigmaOut, f.sigmaIn, f.hazeH, 0F);
            GL20.glUniform4f(u[7], nz, f.cz, f.zLo, 0F);
            GL20.glUniform4f(u[8], Math.max(2, Math.min(64, Config.GOD_RAYS_MARCH_SAMPLES * 2)), roomLight ? 1F : 0F, 0F, 0F);
            GL20.glUniform4f(u[9], dx, dy, smin, tmin);
            GL20.glUniform4f(u[10], EPI_SPACING, lowW, lowH, px);
            GL20.glUniform4f(u[11], vpNow[0], vpNow[1], 0F, 0F);
            if (u[12] >= 0) {
               GL20.glUniform1i(u[12], MARCH_HIST_UNIT);
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport((int)vpNow[0], (int)vpNow[1], (int)vpNow[2], (int)vpNow[3]);
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      // --- methods march / minmax: the view column marched per buffer texel (Toth & Umenhoffer 2009; Playdead's INSIDE
      // 2016: jittered, temporally accumulated; minmax: Chen et al. 2011's min-max blocks integrated analytically)
      static int marchProg, marchFbo, histFbo0, histFbo1;
      static final int[] histTex = new int[2];
      static int histCur, histW, histH;
      private static int[] uMarch;
      private static final String[] MARCH_NAMES = {"uV", "uMM", "uDepth", "uHist", "uLow", "uX", "uY", "uZ", "uC", "uL", "uMarch", "uPrev", "uSig", "uGrid", "uMapA", "uMapB"};
      private static final double[] prevMap = new double[8]; // kA, cA + (ox - oy), kB, cB + (ox + oy), vx, vy, block x, block y
      private static boolean prevValid;
      private static int prevLw, prevLh;

      private static void marchPass(Frame f, int fbo, boolean minmax) {
         if (marchProg == 0) {
            marchProg = FogPass.link(QUAD_VERT, MARCH_FRAG, new String[] {"aPos"}, new String[] {"o", "hist"});
            if (marchProg == 0) {
               Log.warn("god rays: march shader did not compile; volume");
               Config.GOD_RAYS_METHOD = "volume";
               return;
            }
            uMarch = new int[MARCH_NAMES.length];
            for (int i = 0; i < MARCH_NAMES.length; i++) {
               uMarch[i] = GL20.glGetUniformLocation(marchProg, MARCH_NAMES[i]);
            }
         }
         lowPass(f, fbo); // the buffer's size (and the quad); its contents are replaced below
         if (histTex[0] == 0 || histW != lowW || histH != lowH) {
            for (int i = 0; i < 2; i++) {
               if (histTex[i] != 0) {
                  GL11.glDeleteTextures(histTex[i]);
               }
               histTex[i] = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex[i]);
               GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, lowW, lowH);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
            if (marchFbo == 0) {
               marchFbo = GL30.glGenFramebuffers();
            }
            histW = lowW;
            histH = lowH;
            prevValid = false;
         }
         int cur = histCur, prev = 1 - histCur;
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, marchFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, lowTex, 0);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, histTex[cur], 0);
         GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
         GL11.glViewport(0, 0, lowW, lowH);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL20.glUseProgram(marchProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_V_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, vTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_MM_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, mmTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + MARCH_HIST_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex[prev]);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUniform1i(uMarch[0], MARCH_V_UNIT);
         GL20.glUniform1i(uMarch[1], MARCH_MM_UNIT);
         GL20.glUniform1i(uMarch[2], DEPTH_UNIT);
         GL20.glUniform1i(uMarch[3], MARCH_HIST_UNIT);
         float bx = vpNow[2] / lowW, by = vpNow[3] / lowH;
         boolean roomLight = !(Config.GOD_RAYS_APERTURES && f.apertures);
         GL20.glUniform4f(uMarch[4], bx, by, roomLight ? 1F : 0F, Math.max(2, Math.min(64, Config.GOD_RAYS_MARCH_SAMPLES)));
         GL20.glUniform4f(uMarch[5], mapX[0], mapX[1], mapX[2], mapX[3]);
         GL20.glUniform4f(uMarch[6], mapY[0], mapY[1], mapY[2], mapY[3]);
         GL20.glUniform4f(uMarch[7], mapZ[0], mapZ[1], mapZ[2], mapZ[3]);
         GL20.glUniform4f(uMarch[8], color[0], color[1], color[2], groundTc);
         GL20.glUniform4f(uMarch[9], lightDir[0], lightDir[1], lightDir[2], f.patch);
         // the current texel's world column in the previous frame's buffer (ortho: the same (u, v); the depth test in the
         // shader rejects a history of another surface)
         double kA = mapA[0], cAabs = mapA[1] + (f.view.ox - f.view.oy), kB = mapA[2], cBabs = mapA[3] + (f.view.ox + f.view.oy);
         boolean valid = prevValid && Config.GOD_RAYS_TEMPORAL && prevLw == lowW && prevLh == lowH;
         float sx = 0F, ox = 0F, sy = 0F, oy = 0F;
         if (valid) {
            double pkA = prevMap[0], pcA = prevMap[1], pkB = prevMap[2], pcB = prevMap[3], pvx = prevMap[4], pvy = prevMap[5], pbx = prevMap[6], pby = prevMap[7];
            sx = (float)(kA * bx / (pkA * pbx * lowW));
            ox = (float)((kA * vpNow[0] + cAabs - pcA) / (pkA * pbx * lowW) - pvx / (pbx * lowW));
            sy = (float)(kB * by / (pkB * pby * lowH));
            oy = (float)((kB * vpNow[1] + cBabs - pcB) / (pkB * pby * lowH) - pvy / (pby * lowH));
         }
         GL20.glUniform4f(uMarch[10], (float)((frames * 0.61803398875) % 1.0), minmax ? 1F : 0F, Math.max(0.02F, Math.min(1F, Config.GOD_RAYS_TEMPORAL_PCT / 100F)), valid ? 1F : 0F);
         GL20.glUniform4f(uMarch[11], sx, sy, ox, oy);
         GL20.glUniform4f(uMarch[12], f.sigmaOut, f.sigmaIn, f.hazeH, 0F);
         GL20.glUniform4f(uMarch[13], nz, f.cz, f.zLo, 0F);
         GL20.glUniform4f(uMarch[14], mapA[0], mapA[1], mapA[2], mapA[3]);
         GL20.glUniform4f(uMarch[15], mapA[4], mapA[5], vpNow[0], vpNow[1]);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         prevMap[0] = kA;
         prevMap[1] = cAabs;
         prevMap[2] = kB;
         prevMap[3] = cBabs;
         prevMap[4] = vpNow[0];
         prevMap[5] = vpNow[1];
         prevMap[6] = bx;
         prevMap[7] = by;
         prevLw = lowW;
         prevLh = lowH;
         prevValid = true;
         histCur = prev;
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport((int)vpNow[0], (int)vpNow[1], (int)vpNow[2], (int)vpNow[3]);
         ShaderHelper.forgetCurrentlyBound();
         ShaderHelper.glUseProgramObjectARB(0);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         zombie.core.opengl.GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      static int computeProgram(String src, String name) {
         if (!GL.getCapabilities().OpenGL43) {
            return 0; // no compute shaders (macOS 4.1 core): the callers have a fragment or analytic path
         }
         int sh = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
         GL20.glShaderSource(sh, src);
         GL20.glCompileShader(sh);
         if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            Log.warn("god rays: " + name + " shader: " + GL20.glGetShaderInfoLog(sh, 8192));
            GL20.glDeleteShader(sh);
            return 0;
         }
         int p = GL20.glCreateProgram();
         GL20.glAttachShader(p, sh);
         GL20.glLinkProgram(p);
         GL20.glDeleteShader(sh);
         if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            Log.warn("god rays: " + name + " link: " + GL20.glGetProgramInfoLog(p, 8192));
            GL20.glDeleteProgram(p);
            return 0;
         }
         return p;
      }
   }

   // ------------------------------------------------------------------------------------------------ the screen pass

   private static volatile boolean patched;
   private static int program, uP, uD, uC, uAB, uAS, uSLow, uSAp;

   /** Render thread, WeatherShader.startRenderThread (the composite program {@code prog} bound): the volume and its mapping. */
   public static void worldUniforms(int prog) {
      if (!patched || prog == 0) {
         return;
      }
      if (prog != program) {
         program = prog;
         uP = GL20.glGetUniformLocation(prog, "pzGrP");
         uD = GL20.glGetUniformLocation(prog, "pzGrD");
         uC = GL20.glGetUniformLocation(prog, "pzGrC");
         uAB = GL20.glGetUniformLocation(prog, "pzGrAB");
         uAS = GL20.glGetUniformLocation(prog, "pzGrAS");
         uSLow = GL20.glGetUniformLocation(prog, "pzGrLow");
         uSAp = GL20.glGetUniformLocation(prog, "pzGrAp");
         Shaders.stockSamplerUnits(prog, "god rays"); // the game's renumbering in the driver's order may have moved DIFFUSE off unit 0
         if (uSLow >= 0) {
            GL20.glUniform1i(uSLow, VOL_UNIT); // ours off the game's units also while the tap is off
         }
         if (uSAp >= 0) {
            GL20.glUniform1i(uSAp, AUX_UNIT);
         }
      }
      if (uP < 0) {
         return;
      }
      boolean on = Gl.screenOn && Gl.hazeOn && !Gl.fusedIntoFog && !chunkHazeNow && Gl.lowTex != 0 && wanted() && !RenderScale.active() && Gl.params[0] > 0F && (Config.DEV_GOD_RAYS_SKIP & 2) == 0; // the haze only (the light volumes draw themselves; the fog composite may have taken the shade)
      if (!on) {
         GL20.glUniform4f(uP, 0F, 0F, 0F, 0F);
         return;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, Gl.lowTex);
      boolean ap = false; // the light volumes composite themselves (apComposite)
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      // the game's ShaderProgram hands every sampler2D the next unit after compiling (layout(binding) does not survive it)
      GL20.glUniform1i(uSLow, VOL_UNIT);
      GL20.glUniform1i(uSAp, AUX_UNIT);
      GL20.glUniform4f(uP, 1F, Gl.params[1], Gl.params[2], Gl.hazeOn ? 1F : 0F);
      // the low buffer covers the world viewport: its uv from the composite's (the world texture's) uv
      GL20.glUniform4f(uD, Gl.vpNow[0], Gl.vpNow[1], Gl.vpNow[2], Gl.vpNow[3]);
      GL20.glUniform4f(uC, Gl.color[0], Gl.color[1], Gl.color[2], 0F);
      if (ap) {
         GL20.glUniform4f(uAB, Gl.apBounds[0], Gl.apBounds[1], Gl.apBounds[2], Gl.apBounds[3]);
      } else {
         GL20.glUniform4f(uAB, 2F, 2F, -1F, -1F); // nothing inside
      }
      GL20.glUniform4f(uAS, Gl.apScale[0], ap ? 1F : 0F, 0F, 0F);
   }

   private static final java.util.HashMap<Integer, int[]> FOG_LOC = new java.util.HashMap<>();
   private static int fogShadeProg, fogShadeVao;
   private static int[] uFs;

   /**
    * Render thread, FogPass after its rectangles, before its composite (godRaysFogShade=lowres): the shade of the air the
    * sun misses applied to the fog buffers themselves, per texel at its farthest depth (the far buffer) and its nearest
    * (the near one): {@code a' = 1 - k (1 - a)}, k = sqrt(1 - 2.2 s^2), through the blend unit (alpha only:
    * {@code (1 - k) 1 + a k}). The composite's depth-aware upsampling carries it to the screen with no fetch of its own
    * (the per-pixel tap was ~25 us at 5120x2160); a fragment pass, not compute: the graphics-compute switch and its
    * barriers cost more than the work (11 us for 1280x540 x 2 as compute).
    */
   public static void fogShade(int nearFbo, int farFbo, int nearDepth, int farDepth, float pxX, float pxY, int orgX, int orgY, int dsW, int dsH) {
      boolean on = Gl.screenOn && Gl.hazeOn && Gl.shadeNow && Gl.sTex != 0 && Gl.params[0] > 0F && fogFuse() && Gl.depthTex != 0
         && !"pixel".equals(Config.GOD_RAYS_FOG_SHADE) && dsW > 0 && dsH > 0 && (Config.DEV_GOD_RAYS_SKIP & 8) == 0;
      if (!on) {
         return;
      }
      if (fogShadeProg == 0) {
         fogShadeProg = FogPass.link(FULL_TRI_VERT, FOG_SHADE_FRAG, new String[0], new String[] {"o"});
         if (fogShadeProg == 0) {
            Log.warn("god rays: fog shade shader did not compile; the per-pixel tap");
            Config.GOD_RAYS_FOG_SHADE = "pixel";
            return;
         }
         uFs = new int[] {GL20.glGetUniformLocation(fogShadeProg, "uInfo"), GL20.glGetUniformLocation(fogShadeProg, "uOrg"), GL20.glGetUniformLocation(fogShadeProg, "uX"),
            GL20.glGetUniformLocation(fogShadeProg, "uY"), GL20.glGetUniformLocation(fogShadeProg, "uZ"), GL20.glGetUniformLocation(fogShadeProg, "uG"),
            GL20.glGetUniformLocation(fogShadeProg, "uD"), GL20.glGetUniformLocation(fogShadeProg, "uS")};
         fogShadeVao = GL30.glGenVertexArrays();
      }
      Timing.begin("fog.shade", true);
      GL20.glUseProgram(fogShadeProg);
      GL20.glUniform4f(uFs[0], 0F, 0F, pxX, pxY);
      GL20.glUniform4f(uFs[1], orgX, orgY, 1F / dsW, 1F / dsH);
      GL20.glUniform4f(uFs[2], Gl.mapX[0], Gl.mapX[1], Gl.mapX[2], Gl.mapX[3]);
      GL20.glUniform4f(uFs[3], Gl.mapY[0], Gl.mapY[1], Gl.mapY[2], Gl.mapY[3]);
      GL20.glUniform4f(uFs[4], Gl.mapZ[0], Gl.mapZ[1], Gl.mapZ[2], Gl.mapZ[3]);
      GL20.glUniform4f(uFs[5], Gl.groundTc, 2.2F, 0F, 0F);
      GL20.glUniform1i(uFs[6], FS_DEPTH_UNIT);
      GL20.glUniform1i(uFs[7], VOL_UNIT);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
      GL11.glBindTexture(GL12.GL_TEXTURE_3D, Gl.sTex);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_ALPHA_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glEnable(GL11.GL_BLEND);
      GL14.glBlendEquation(GL14.GL_FUNC_ADD);
      GL14.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GL11.glColorMask(false, false, false, true);
      GL30.glBindVertexArray(fogShadeVao);
      // (the viewport is the fog buffer's already; sampling a buffer's own depth attachment is defined: no depth writes)
      if (farFbo != 0 && farDepth != 0) {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, farFbo);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + FS_DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, farDepth);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFbo);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + FS_DEPTH_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearDepth);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      GL30.glBindVertexArray(0);
      GL11.glColorMask(true, true, true, true);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
      GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL20.glUseProgram(0);
      ShaderHelper.forgetCurrentlyBound();
      Timing.end(null);
      Gl.fusedIntoFog = true;
      Gl.fusedFrames++;
   }

   static final int FS_DEPTH_UNIT = 18; // (DEPTH_UNIT: free at this point of the frame)

   /**
    * godRaysFogFuse, except on macOS's OpenGL 4.1 core context: there the shade fused into the fog buffer drew chunk-shaped
    * brightness steps in storms (2026-10-01, runs mac-macgl-gr-*; detaching the sampled depth did not help), while the world
    * composite's own tap is clean, and costs little at a Mac's resolution.
    */
   static boolean fogFuse() {
      return Config.GOD_RAYS_FOG_FUSE && !CoreGl.active;
   }

   /** A full-screen triangle, no attributes (gl_VertexID). */
   static final String FULL_TRI_VERT = String.join("\n",
      "#version 330",
      "void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }",
      "");

   static final String FOG_SHADE_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uD;", // this fog buffer's depth: the texel's nearest scene depth (near) or its farthest (far)
      "uniform sampler3D uS;", // sqrt of the air the sun misses (the integration's shade volume)
      "uniform vec4 uInfo;", // -, -, screen px per texel x, y
      "uniform vec4 uOrg;", // the scene depth's px of the viewport's origin; 1 / its size
      "uniform vec4 uX;",
      "uniform vec4 uY;",
      "uniform vec4 uZ;",
      "uniform vec4 uG;", // the ground's z tc, strength
      "out vec4 o;",
      "void main() {",
      "   ivec2 t = ivec2(gl_FragCoord.xy);",
      "   vec2 uv = ((vec2(t) + 0.5) * uInfo.zw + uOrg.xy) * uOrg.zw;",
      "   float d = texelFetch(uD, t, 0).r;",
      "   vec4 q = vec4(uv, d, 1.0);",
      "   vec3 tc = vec3(dot(uX, q), dot(uY, q), d >= 0.99999 ? uG.x : dot(uZ, q));",
      "   float s = texture(uS, tc).r;",
      "   o = vec4(0.0, 0.0, 0.0, 1.0 - sqrt(clamp(1.0 - uG.y * s * s, 0.0, 1.0)));", // alpha 1 - k: the blend makes a' = (1 - k) + a k
      "}",
      "");

   /**
    * Render thread, FogPass's composite (its program bound, before its draw): when this frame's haze is the shade of the
    * game's fog (shade mode: a pure multiplier), the fog composite applies it (one tap in a pass that runs anyway) and the
    * world composite skips its own tap. Otherwise the uniform turns it off there.
    */
   public static void fogComposite(int prog, int unit) {
      int[] l = FOG_LOC.get(prog);
      if (l == null) {
         l = new int[] {GL20.glGetUniformLocation(prog, "GodRays"), GL20.glGetUniformLocation(prog, "godRays"), GL20.glGetUniformLocation(prog, "godRaysX"),
            GL20.glGetUniformLocation(prog, "godRaysY"), GL20.glGetUniformLocation(prog, "godRaysZ")};
         FOG_LOC.put(prog, l);
      }
      if (l[1] < 0) {
         return;
      }
      if (l[0] >= 0) {
         GL20.glUniform1i(l[0], unit); // its own unit also when off: the sampler3D on a sampler2D's unit 0 fails the draw on Mesa
      }
      boolean on = Gl.screenOn && Gl.hazeOn && Gl.shadeNow && Gl.sTex != 0 && Gl.params[0] > 0F && fogFuse() && Gl.depthTex != 0
         && "pixel".equals(Config.GOD_RAYS_FOG_SHADE); // (lowres: fogShade applied it to the fog buffer already)
      if (!on) {
         GL20.glUniform4f(l[1], 0F, 0F, 0F, 0F);
         return;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
      GL11.glBindTexture(GL12.GL_TEXTURE_3D, Gl.sTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL20.glUniform4f(l[1], 1F, 2.2F, Gl.groundTc, 0F);
      GL20.glUniform4f(l[2], Gl.mapX[0], Gl.mapX[1], Gl.mapX[2], Gl.mapX[3]);
      GL20.glUniform4f(l[3], Gl.mapY[0], Gl.mapY[1], Gl.mapY[2], Gl.mapY[3]);
      GL20.glUniform4f(l[4], Gl.mapZ[0], Gl.mapZ[1], Gl.mapZ[2], Gl.mapZ[3]);
      Gl.fusedIntoFog = true;
      Gl.fusedFrames++;
   }

   /** Game thread, MultiTextureFBO2 around the screen composite (devGodRaysTiming): its GPU time split by the alternation. */
   public static void screenBegin() {
      if (Config.GOD_RAYS && Config.GOD_RAYS_LATE_DRAW) {
         SpriteRenderer.instance.drawGeneric(LATE_DRAW); // the light volumes and local lights over the finished world
      }
      if (Config.DEV_GOD_RAYS_TIMING && Config.GOD_RAYS) {
         SpriteRenderer.instance.drawGeneric(devOff ? SCREEN_BEGIN_OFF : SCREEN_BEGIN_ON);
      }
   }

   private static final TextureDraw.GenericDrawer LATE_DRAW = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Gl.lateDraw();
      }
   };

   public static void screenEnd() {
      if (Config.DEV_GOD_RAYS_TIMING && Config.GOD_RAYS) {
         SpriteRenderer.instance.drawGeneric(SCREEN_END);
      }
   }

   private static final TextureDraw.GenericDrawer SCREEN_BEGIN_ON = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.begin("screen.on", false);
      }
   };
   private static final TextureDraw.GenericDrawer SCREEN_BEGIN_OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.begin("screen.off", false);
      }
   };
   private static final TextureDraw.GenericDrawer SCREEN_END = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         Timing.end(null);
      }
   };

   /**
    * ShaderUnit hook (before Grade's): the world composite's bicubic fetch of the world picture gets the god rays: the stock
    * {@code textureBicubic} is renamed and a wrapper of the same signature adds them, so the stock main and the colour
    * grade's fused path (which calls it too) both see them.
    */
   // ------------------------------------------------------------------------------------------------ haze in the chunk composite

   private static volatile boolean chunkPatched;
   static volatile boolean chunkHazeNow; // this frame's chunk composite adds the outdoor haze (no buffer pass, no screen tap)
   private static int chunkSerial;
   static final float[] cX = new float[4], cY = new float[4], cZ = new float[4], cC = new float[4];
   private static final java.util.HashMap<Integer, int[]> CHUNK_LOC = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, Integer> CHUNK_APPLIED = new java.util.HashMap<>();
   static final int HAZE_UNIT = 29;
   private static final java.util.Set<String> STOCK_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet(); // (pixelLight compiles a composite per variant: one line a file)

   /**
    * ShaderUnit hook, after the cloud shadows' patch and before the reflections' (which renames our main in turn): the
    * chunk composite (stock chunkShader.frag, GLSL 1.20, or pixelLight's programs) adds the outdoor haze to each fragment
    * from its own depth: {@code c' = sqrt(c^2 T + C F)} (T, F the haze's transmittance and in-scatter down the view column to
    * the fragment, the volume's R and G). The fragment's world position is exact (its chunk texture's depth), no scene
    * depth read and no pass of its own: the buffer pass and the screen composite's tap (~48 us at 5120x2160) are skipped.
    * What the composite does not draw (characters, vehicles, water) has no haze: in clear air a few percent.
    */
   public static String patchChunk(String fileName, String code) {
      if (fileName == null || code == null || CoreGl.legacyMac() || !Overrides.enabled() || !"chunk".equals(Config.GOD_RAYS_HAZE_COMPOSITE)) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (!(f.endsWith("/chunkShader.frag") || f.endsWith("/pzopt_chunkBase.frag") || f.endsWith("/pzopt_chunkStock.frag"))) {
         return code;
      }
      if (!Config.GOD_RAYS) {
         // off at launch: the game's composite stays stock (AMD on Windows drew the whole world black with the patch in and
         // god rays off, 2026-09-27); turned on later, the haze joins the composite at the next launch
         if (STOCK_LOGGED.add(f)) {
            Log.info("god rays: off at launch, " + fileName + " stays stock");
         }
         return code;
      }
      int nl = code.indexOf('\n');
      String first = nl < 0 ? "" : code.substring(0, nl).trim();
      boolean old = first.startsWith("#version 1");
      String out = code.contains("out vec4 fragColor;") ? "fragColor" : "gl_FragColor";
      if (!first.startsWith("#version") || code.indexOf("void main()") < 0 || !code.contains("gl_FragDepth") || !code.contains("chunkDepth")) {
         Log.warn("god rays: " + fileName + " is not a composite shader we know, no haze in the chunk composite");
         return code;
      }
      // the head stays first (the cloud patch's #version / #extension lines), then our macros
      int bodyAt = nl;
      while (true) {
         int n2 = code.indexOf('\n', bodyAt + 1);
         String line = n2 < 0 ? "" : code.substring(bodyAt + 1, n2).trim();
         if (n2 < 0 || !(line.startsWith("#version") || line.startsWith("#extension"))) {
            break;
         }
         bodyAt = n2;
      }
      // the sampler3D needs its own unit from the link on: the game validates the program with every sampler on unit 0,
      // and a sampler2D and a sampler3D on one unit fail on Mesa (the composite dropped: the whole baked world black)
      int version = 0;
      try {
         version = Integer.parseInt(first.substring(8).trim().split("\\s+")[0]);
      } catch (RuntimeException e) {
         // (unknown: the extension line is harmless)
      }
      String head = code.substring(0, bodyAt);
      if (version > 0 && version < 130) {
         // our sampler's layout binding: on the stock GLSL 1.20 composite the 420pack extension does not apply and the patch
         // never compiled (no haze in the chunk composite without pixelLight or cloud shadows, 2026-10-18; NVIDIA refuses it at
         // 1.30 and 1.40 too); 1.50 compatibility keeps gl_FragColor, varying, texture2D / texture3D (as the cloud patch's 4.20)
         head = head.replaceFirst("#version\\s+1[0-2]0", "#version 150 compatibility");
         version = 150;
      }
      if (version < 420 && !head.contains("GL_ARB_shading_language_420pack")) {
         head += "\n#extension GL_ARB_shading_language_420pack : enable";
      }
      String c = head + "\n#define PZG_OUT " + out + "\n#define PZG_TEX3 " + (old ? "texture3D" : "texture")
         + code.substring(bodyAt).replace("void main()", "void pzGrHazeInner()") + "\n" + CHUNK_GLSL + "\n";
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("god rays: " + fileName + " haze patch does not compile, stays as it was: " + log);
         return code;
      }
      chunkPatched = true;
      Log.info("god rays: haze patched into " + fileName);
      return c;
   }

   static final String CHUNK_GLSL = String.join("\n",
      "layout(binding = " + HAZE_UNIT + ") uniform sampler3D pzGrHV;", // the integration's volume (R in-scatter, G transmittance)
      "uniform vec4 pzGrHX;", // volume tc = dot(X / Y / Z, (window x, window y, depth, 1))
      "uniform vec4 pzGrHY;",
      "uniform vec4 pzGrHZ;",
      "uniform vec4 pzGrHC;", // rgb the lit air's light (the light's colour x phase x godRaysHazeAddPct), w the shade's strength (0: off)
      "void main() {",
      "   pzGrHazeInner();",
      "   if (pzGrHC.w <= 0.0 || PZG_OUT.a < 0.004 || gl_FragDepth - chunkDepth >= 0.9999) return;", // (padding; tile seams without depth)
      "   vec4 q = vec4(gl_FragCoord.xy, gl_FragDepth, 1.0);",
      "   vec2 h = PZG_TEX3(pzGrHV, vec3(dot(pzGrHX, q), dot(pzGrHY, q), dot(pzGrHZ, q))).rg;",
      // the air in front of the fragment that the sun does not reach, (1 - T) - F, darkens it (the shafts of shade the
      // buildings and trees cast through the haze); the lit air adds a little of its light (a full veil washed the
      // picture out: the game's picture stands for lit clear air)
      "   float miss = max((1.0 - h.g) - h.r, 0.0);",
      "   vec3 c = PZG_OUT.rgb / PZG_OUT.a;", // (premultiplied)
      "   PZG_OUT.rgb = sqrt(abs(c * c * max(1.0 - pzGrHC.w * miss, 0.0) + pzGrHC.rgb * h.r)) * PZG_OUT.a;",
      "}");

   /** Game thread, FBORenderCell right before the chunk composite: this frame's camera for the haze there. */
   public static void beforeComposite(int playerIndex) {
      if (!chunkPatched || !wanted()) {
         if (chunkHazeNow) {
            SpriteRenderer.instance.drawGeneric(CHUNK_OFF);
         }
         return;
      }
      ChunkFrame cf = CHUNK_FRAMES[chunkFrameIndex++ & 3];
      cf.view.capture(playerIndex);
      cf.player = playerIndex & 3;
      cf.devOn = !devOff || Config.DEV_GOD_RAYS_ALTERNATE <= 0 || !Config.DEV_GOD_RAYS_ALTERNATE_ALL;
      SpriteRenderer.instance.drawGeneric(cf);
   }

   private static final TextureDraw.GenericDrawer CHUNK_OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         chunkHazeNow = false;
         chunkSerial++; // (the programs' uniforms go to off at their next draw)
      }
   };

   private static final class ChunkFrame extends TextureDraw.GenericDrawer {
      final Ssr.View view = new Ssr.View();
      int player;
      boolean devOn;

      @Override
      public void render() {
         // the volume as it stands (world-anchored: last frame's content, this frame's camera); the add mode only (the
         // fog's shade rides the fog pass), the volume method, no froxel lights (they ride the buffer)
         // (in the game's fog the shade rides the fog pass: fogShade, which covers the characters too)
         boolean on = this.devOn && Gl.everOn && !failed && Gl.hazeOn && !(stockFog > 0.05F && fogFuse()) && Gl.fTex != 0 && Gl.nx > 0 && "volume".equals(Config.GOD_RAYS_METHOD)
            && !Gl.froxelLast && !RenderScale.active() && Gl.params[1] == 0F && (Config.DEV_GOD_RAYS_SKIP & 2) == 0;
         chunkHazeNow = on;
         chunkSerial++;
         if (!on) {
            return;
         }
         int[] vpi = Gl.viewport(this.player);
         float[] vp = {vpi[0], vpi[1], vpi[2], vpi[3]};
         float[] m = new float[6];
         this.view.mapping(vp, m);
         double kA = m[0], cA = m[1], kB = m[2], cB = m[3], kC = m[4], cCc = m[5];
         double cu = Gl.lastCu, cv = 2.0 * cu, cz = Gl.lastCz, zLo = Gl.lastZLo;
         int nx = Gl.nx, ny = Gl.ny, nz = Gl.nz;
         double offU = floorMod((this.view.ox - this.view.oy) / cu, nx), offV = floorMod((this.view.ox + this.view.oy) / cv, ny);
         cX[0] = (float)(kA / cu / nx);
         cX[1] = 0F;
         cX[2] = 0F;
         cX[3] = (float)((cA / cu + offU) / nx);
         cY[0] = 0F;
         cY[1] = (float)(kB / cv / ny);
         cY[2] = 0F;
         cY[3] = (float)((cB / cv + offV) / ny);
         double zs = 1.0 / (nz * cz);
         cZ[0] = 0F;
         cZ[1] = (float)(-kB / 8.0 * zs);
         cZ[2] = (float)(kC / 8.0 * zs);
         cZ[3] = (float)(((cCc - cB) / 8.0 - zLo) * zs + 0.5 / nz);
         float add = Config.GOD_RAYS_HAZE_ADD_PCT / 100F;
         cC[0] = Gl.color[0] * add;
         cC[1] = Gl.color[1] * add;
         cC[2] = Gl.color[2] * add;
         cC[3] = Math.max(1e-4F, 2.2F * Config.GOD_RAYS_SHADE_PCT / 100F);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + HAZE_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, Gl.fTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
   }

   private static final ChunkFrame[] CHUNK_FRAMES = {new ChunkFrame(), new ChunkFrame(), new ChunkFrame(), new ChunkFrame()};
   private static int chunkFrameIndex;

   /** ChunkRenderShader.startRenderThread, every chunk texture the composite draws: the haze uniforms once per program per frame. */
   private static int chunkLastProg = -1, chunkLastSerial = -1;

   public static void chunkDraw() {
      if (!chunkPatched) {
         return;
      }
      int prog = Ssr.boundProgram();
      if (prog == chunkLastProg && chunkSerial == chunkLastSerial) {
         return; // (most chunk draws of a frame: the same program, already set)
      }
      chunkLastProg = prog;
      chunkLastSerial = chunkSerial;
      int[] l = CHUNK_LOC.get(prog);
      if (l == null) {
         l = new int[] {GL20.glGetUniformLocation(prog, "pzGrHV"), GL20.glGetUniformLocation(prog, "pzGrHX"), GL20.glGetUniformLocation(prog, "pzGrHY"),
            GL20.glGetUniformLocation(prog, "pzGrHZ"), GL20.glGetUniformLocation(prog, "pzGrHC")};
         CHUNK_LOC.put(prog, l);
      }
      if (l[4] < 0) {
         return; // not a patched program
      }
      Integer applied = CHUNK_APPLIED.get(prog);
      if (applied != null && applied == chunkSerial) {
         return;
      }
      CHUNK_APPLIED.put(prog, chunkSerial);
      GL20.glUniform1i(l[0], HAZE_UNIT); // (also with the haze off: a sampler3D left on a sampler2D's unit fails the draw on Mesa)
      if (!chunkHazeNow) {
         GL20.glUniform4f(l[4], 0F, 0F, 0F, 0F);
         return;
      }
      GL20.glUniform4f(l[1], cX[0], cX[1], cX[2], cX[3]);
      GL20.glUniform4f(l[2], cY[0], cY[1], cY[2], cY[3]);
      GL20.glUniform4f(l[3], cZ[0], cZ[1], cZ[2], cZ[3]);
      GL20.glUniform4f(l[4], cC[0], cC[1], cC[2], cC[3]);
   }

   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || CoreGl.legacyMac() || !Overrides.enabled()) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (!f.endsWith("/screen.frag")) {
         return code;
      }
      if (!Config.GOD_RAYS) {
         // off at launch: the world composite stays stock (the whole world drew black on AMD / Windows with the patch in,
         // 2026-09-27; likely its two extra sampler2Ds moving the game's DIFFUSE off unit 0, see Shaders.stockSamplerUnits);
         // turned on later, the composite's tap comes at the next launch
         if (STOCK_LOGGED.add(f)) {
            Log.info("god rays: off at launch, " + fileName + " stays stock");
         }
         return code;
      }
      String sig = "vec4 textureBicubic(sampler2D sampler, vec2 texCoords)";
      int at = code.indexOf(sig);
      if (at < 0 || !code.startsWith("#version")) {
         Log.warn("god rays: screen.frag has changed (no textureBicubic), no god rays");
         return code;
      }
      int open = code.indexOf('{', at);
      int depth = 0, end = -1;
      for (int i = open; i < code.length(); i++) {
         char ch = code.charAt(i);
         if (ch == '{') {
            depth++;
         } else if (ch == '}' && --depth == 0) {
            end = i + 1;
            break;
         }
      }
      if (end < 0) {
         return code;
      }
      int eol = code.indexOf('\n');
      String c = code.substring(0, eol + 1) + "#extension GL_ARB_shading_language_420pack : enable\n"
         + code.substring(eol + 1, at) + "vec4 pzGrBicubicStock(sampler2D sampler, vec2 texCoords)" + code.substring(at + sig.length(), end) + "\n" + SCREEN_GLSL
         + code.substring(end);
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("god rays: patched screen.frag does not compile, stays as it was: " + log);
         return code;
      }
      patched = true;
      Log.info("god rays: world composite patched");
      return c;
   }

   /**
    * The composite's part: no depth read (a full-size depth fetch was most of its cost, ~40 us at 5120x2160): one bilinear
    * tap of the quarter-size god ray buffer (in-scatter, the multiplier of the surface: haze transmittance + lit patch) and,
    * inside the light volumes' bounds only, their target (the dust along the view path in them, the surface lit by one).
    */
   static final String SCREEN_GLSL = String.join("\n",
      "// pzopt: god rays (pzopt.GodRays): the scattered key light in front of the pixel, from a quarter-size buffer",
      "layout(binding = " + VOL_UNIT + ") uniform sampler2D pzGrLow;", // rgb in-scatter (display linear), a the surface's multiplier
      "layout(binding = " + AUX_UNIT + ") uniform sampler2D pzGrAp;", // the light volumes: R view path inside them (w units x light), G the surface lit
      "uniform vec4 pzGrP;", // x on, y dev view, z patch gain, w the haze buffer holds something
      "uniform vec4 pzGrD;", // the world viewport in the world texture's texels: x, y, w, h (the buffers cover it)
      "uniform vec4 pzGrC;", // rgb the light's colour x strength x phase
      "uniform vec4 pzGrAB;", // the light volumes' bounds in uv
      "uniform vec4 pzGrAS;", // x their path -> dust in-scatter, y on
      "vec3 pzGodRays(vec3 c, vec2 uv, sampler2D world) {",
      "   if (pzGrP.x <= 0.0) return c;",
      "   vec2 texSize = vec2(textureSize(world, 0));", // (after the early return: as an argument it cost the stock path ~5 us)
      // the view's uv from the texel the composite samples (the world texture's own size: its allocation is not the
      // depth attachment's, which is larger)
      "   vec2 vuv = (uv * texSize - pzGrD.xy) / pzGrD.zw;",
      "   vec4 g = texture(pzGrLow, vuv);",
      "   g.rgb = g.rgb * g.rgb * 2.0; g.a *= 2.0;", // RGBA8: rgb sqrt(in-scatter / 2), a the multiplier / 2
      "   float dust = 0.0, lit = 0.0;",
      "   if (pzGrP.y > 4.5) {", // what the composite samples: 5 its view uv, 6 the light volumes' target raw, 7 the buffer raw
      "      if (pzGrP.y < 5.5) return vec3(fract(vuv * 4.0), vuv.x > 1.0 || vuv.y > 1.0 || vuv.x < 0.0 || vuv.y < 0.0 ? 1.0 : 0.0);",
      "      if (pzGrP.y < 6.5) { vec2 a = texture(pzGrAp, vuv).rg; return vec3(clamp(a.r * 0.2, 0.0, 1.0), clamp(a.g, 0.0, 1.0), 0.0); }",
      "      return texture(pzGrLow, vuv).rgb * 4.0;",
      "   }",
      "   if (pzGrP.y > 0.5) return sqrt(abs(g.rgb + pzGrC.rgb * dust * 8.0 + vec3(lit * (pzGrP.y > 1.5 && pzGrP.y < 2.5 ? 1.0 : 0.0))));",
      "   vec3 lin = c * c;",
      "   lin = lin * (g.a + pzGrP.z * lit) + g.rgb + pzGrC.rgb * dust;",
      "   return sqrt(abs(lin));", // (screen.frag's util/math declares max(float, float): it hides the built-in vector max)
      "}",
      "vec4 textureBicubic(sampler2D sampler, vec2 texCoords) {",
      "   vec4 c = pzGrBicubicStock(sampler, texCoords);",
      "   c.rgb = pzGodRays(c.rgb, texCoords, sampler);",
      "   return c;",
      "}",
      "");

   static final String QUAD_VERT = String.join("\n",
      "#version 330",
      "layout(location = 0) in vec2 aPos;",
      "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }",
      "");

   /** The world (x, y relative to the view origin, z levels -> squares) of a window pixel of the world framebuffer. */
   static final String WORLD_GLSL = String.join("\n",
      "uniform vec4 uMapA;", // kA, cA, kB, cB
      "uniform vec4 uMapB;", // kC, cC, viewport x, y
      "vec3 worldAt(vec2 px, float d) {",
      "   float u = uMapA.x * px.x + uMapA.y, v = uMapA.z * px.y + uMapA.w, w = uMapB.x * d + uMapB.y;",
      "   float z = (w - v) * 0.125, s = v + 6.0 * z;",
      "   return vec3((s + u) * 0.5, (s - u) * 0.5, z * 2.4494897);",
      "}",
      // the surface's facing to the light from the depth of the pixel and its nearer neighbours in x and y
      "float facingAt(sampler2D depth, ivec2 p, float d, vec3 L) {",
      "   vec3 P = worldAt(vec2(p) + 0.5, d);",
      "   float dxp = texelFetch(depth, p + ivec2(1, 0), 0).r, dxm = texelFetch(depth, p - ivec2(1, 0), 0).r;",
      "   float dyp = texelFetch(depth, p + ivec2(0, 1), 0).r, dym = texelFetch(depth, p - ivec2(0, 1), 0).r;",
      "   vec3 tx = abs(dxp - d) < abs(dxm - d) ? worldAt(vec2(p) + vec2(1.5, 0.5), dxp) - P : P - worldAt(vec2(p) + vec2(-0.5, 0.5), dxm);",
      "   vec3 ty = abs(dyp - d) < abs(dym - d) ? worldAt(vec2(p) + vec2(0.5, 1.5), dyp) - P : P - worldAt(vec2(p) + vec2(0.5, -0.5), dym);",
      "   vec3 n = cross(tx, ty);",
      "   n *= dot(n, vec3(0.6124, 0.6124, 0.5)) < 0.0 ? -1.0 : 1.0;",
      "   return clamp(dot(n, L) * inversesqrt(dot(n, n) + 1e-30), 0.0, 1.0);",
      "}");

   /**
    * The quarter-size god ray buffer (fog hook, after the volume's updates): each texel takes the depth of its block's
    * middle pixel, taps the volume there (half a slice up: the lit side of the surface) and the facing; rgb the haze's
    * in-scatter (+ the volume's room dust when the light volumes are off), a the surface's multiplier (the haze's
    * transmittance + the volume's lit patch).
    */
   static final String LOW_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler3D uVol;",
      "uniform sampler2D uDepth;",
      "uniform vec4 uLow;", // xy window px per low texel, zw: 1 = the volume's room light (light volumes off)
      "uniform vec4 uX;", // volume tc = dot(X/Y/Z, (duv.x, duv.y, depth, 1)), duv the depth texture's uv
      "uniform vec4 uY;",
      "uniform vec4 uZ;",
      "uniform vec4 uC;", // rgb the light's colour, w the ground's tc.z
      "uniform vec4 uL;", // the direction to the light (world squares), w patch gain
      "uniform vec4 uDev;", // x dev view
      "uniform sampler3D uLocal;", // froxel local lights (rgb cumulative in-scatter)
      "uniform vec4 uLocOn;",
      "uniform sampler2D uCloud;", // the cloud shadows' field (R base, G detail); uCl0.x = 0: no clouds
      "uniform vec4 uCl0;", // base uv: u = x * x0 + z * y0 + z0, v = y * x0 + z * w0 + x1 (see CloudShadow.hazeField)
      "uniform vec4 uCl1;", // v offset, detail scale, detail u and v offsets
      "uniform vec4 uCl2;", // -, 1 - cover, 1 / (cover edge), erosion
      "uniform vec4 uCl3;", // opacity / (1 - e^-3), the haze's middle height (levels)
      WORLD_GLSL,
      "float cloudT(vec3 P) {", // P: world squares from the view origin, z levels
      "   if (uCl0.x <= 0.0) return 1.0;",
      "   vec2 uv = vec2(P.x * uCl0.x + P.z * uCl0.y + uCl0.z, P.y * uCl0.x + P.z * uCl0.w + uCl1.x);",
      "   float d = clamp((texture(uCloud, uv).r - uCl2.y) * uCl2.z, 0.0, 1.0);",
      "   if (d <= 0.0) return 1.0;",
      "   float e = texture(uCloud, uv * uCl1.y + uCl1.zw).g * uCl2.w;",
      "   d = clamp((d - e) / (1.0 - e), 0.0, 1.0);",
      "   return 1.0 - uCl3.x * (1.0 - exp(-3.0 * d));",
      "}",
      "out vec4 o;",
      "void main() {",
      "   ivec2 p = ivec2(uMapB.zw + floor(gl_FragCoord.xy) * uLow.xy + floor(uLow.xy * 0.5));",
      "   ivec2 ds = textureSize(uDepth, 0);",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   vec4 q = vec4((vec2(p) + 0.5) / vec2(ds), d, 1.0);",
      "   vec3 tc = vec3(dot(uX, q), dot(uY, q), d >= 0.99999 ? uC.w : dot(uZ, q));",
      "   vec4 f = texture(uVol, tc);",
      "   float lit = 0.0, dust = 0.0;",
      "   if (uLow.z > 0.5 && f.b > 0.0) { lit = f.b * facingAt(uDepth, p, d, uL.xyz); }",
      // the clouds hold the sun back from the whole column where one stands over its middle (crepuscular rays of the
      // clouds: their shadows run through the haze; a cloud is far larger than a column's reach through it)
      "   if (uCl0.x > 0.0) {",
      "      float u0 = uMapA.x * (float(p.x) + 0.5) + uMapA.y, v0 = uMapA.z * (float(p.y) + 0.5) + uMapA.w, zm = uCl3.y;",
      "      float s0 = v0 + 6.0 * zm;",
      "      f.r *= cloudT(vec3((s0 + u0) * 0.5, (s0 - u0) * 0.5, zm));",
      "   }",
      "   if (uLow.z > 0.5) dust = f.a;",
      "   if (uDev.x > 0.5 && uDev.x < 4.5) {",
      "      if (uDev.x < 1.5) { o = vec4(sqrt(clamp(uC.rgb * (f.r + dust) * 4.0, 0.0, 1.0)), 0.0); return; }",
      "      if (uDev.x < 2.5) { o = vec4(sqrt(vec3(lit) * 0.5), 0.0); return; }",
      "      if (uDev.x < 3.5) { o = vec4(sqrt(vec3(f.g * f.g) * 0.5), 0.0); return; }",
      "      o = vec4(sqrt(vec3(facingAt(uDepth, p, d, uL.xyz)) * 0.5), 0.0); return;",
      "   }",
      // shade mode (the game's own fog draws the average in-scatter already): the haze's light missing where the air is in
      // shadow, (1 - T) - F, as a darkening of the picture; add mode: the in-scatter on top
      "   if (uDev.y > 0.5) { float miss = max((1.0 - f.g) - f.r, 0.0); o = vec4(0.0, 0.0, 0.0, clamp((1.0 - uDev.z * miss + uL.w * lit) * 0.5, 0.0, 1.0)); return; }",
      "   vec3 loc = uLocOn.x > 0.5 ? texture(uLocal, tc).rgb : vec3(0.0);", // froxel local lights: their in-scatter down to the surface
      "   o = vec4(sqrt(clamp((uC.rgb * (f.r + dust) + loc) * 0.5, 0.0, 1.0)), clamp((f.g + uL.w * lit) * 0.5, 0.0, 1.0));",
      "}",
      "");

   /**
    * Method blur, pass 1: the bright part of the world picture at the buffer's size (four bilinear taps over the block),
    * rgb sqrt of the linear colour above the threshold (the RGBA8 buffer's encoding).
    */
   static final String BLUR_MASK_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uColor;",
      "uniform vec4 uLow;", // the viewport in the world texture's texels: x, y, w, h
      "uniform vec4 uC;", // the light's colour x strength x phase (its brightness gates the mask)
      "uniform vec4 uInv;", // 1 / the buffer's size
      "out vec4 o;",
      "void main() {",
      "   vec3 c = vec3(0.0);",
      "   for (int i = 0; i < 4; i++) {",
      "      vec2 off = (vec2(i & 1, i >> 1) - 0.5) * 0.5;",
      "      vec3 t = texture(uColor, (uLow.xy + uLow.zw * (gl_FragCoord.xy + off) * uInv.xy) / vec2(textureSize(uColor, 0))).rgb;",
      "      c += t * t;",
      "   }",
      "   c *= 0.25;",
      "   float lum = dot(c, vec3(0.299, 0.587, 0.114));",
      "   float m = clamp((lum - 0.30) / 0.45, 0.0, 1.0) * clamp(dot(uC.rgb, vec3(0.3)), 0.0, 1.0);",
      "   o = vec4(sqrt(c * m), 1.0);",
      "}",
      "");

   /** Method blur, passes 2-4: 8 taps toward the light, uStep apart (uv), decaying; the last one scales and encodes. */
   static final String BLUR_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uSrc;",
      "uniform vec4 uStep;", // xy the step (uv), z 1 = the last pass
      "out vec4 o;",
      "void main() {",
      "   vec2 uv = gl_FragCoord.xy / vec2(textureSize(uSrc, 0));",
      "   vec3 acc = vec3(0.0);",
      "   float w = 1.0, ws = 0.0;",
      "   for (int i = 0; i < 8; i++) {",
      "      vec3 c = texture(uSrc, uv + uStep.xy * float(i)).rgb;",
      "      acc += c * c * w; ws += w; w *= 0.86;",
      "   }",
      "   vec3 r = acc / ws;",
      "   o = uStep.z > 0.5 ? vec4(sqrt(clamp(r * 0.45, 0.0, 1.0)), 0.5) : vec4(sqrt(r), 1.0);",
      "}",
      "");

   /**
    * Methods march / minmax: the view column of each buffer texel marched from the top of the haze down to its surface
    * through the visibility volume (N samples, interleaved-gradient-noise jitter rotated per frame by the golden ratio),
    * the same media as the integration (outdoor haze where the room tag is 0; the surface room's dust up to its level's
    * top), accumulated over frames by the world column (the ortho view: the same (u, v) is the same ray; a history of
    * another surface is dropped by its height). minmax: blocks of 8 slices whose V is constant (and outdoors) integrate
    * analytically in one step (Chen et al. 2011's 1D min-max mipmaps in rectified space).
    */
   /** The view column's march (method march's body without the history), for the epipolar method's two passes. */
   static final String MARCH_FN_GLSL = String.join("\n",
      "uniform sampler3D uV;", // V, room tag
      "uniform sampler2D uDepth;",
      "uniform vec4 uX;",
      "uniform vec4 uY;",
      "uniform vec4 uZ;",
      "uniform vec4 uC;", // light colour, ground tc.z
      "uniform vec4 uSig;", // haze sigma, dust sigma, haze height
      "uniform vec4 uGrid;", // nz, cz, zLo
      "uniform vec4 uMarchN;", // x samples, y 1 = rooms' dust here
      "float tczE(float z) { return (z - uGrid.z) / (uGrid.x * uGrid.y); }",
      // the in-scatter (display linear) of the view column through window pixel p, its surface height in zs, T in tr
      "vec3 marchAt(ivec2 p, float jit, out float zs, out float tr) {",
      "   ivec2 ds = textureSize(uDepth, 0);",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   vec4 q = vec4((vec2(p) + 0.5) / vec2(ds), d, 1.0);",
      "   vec2 txy = vec2(dot(uX, q), dot(uY, q));",
      "   float tzs = d >= 0.99999 ? uC.w : dot(uZ, q);",
      "   zs = uGrid.z + (tzs * uGrid.x - 0.5) * uGrid.y;",
      "   ivec3 vs = textureSize(uV, 0);",
      "   ivec3 ts = ivec3(ivec2(mod(floor(txy * vec2(vs.xy)), vec2(vs.xy))), clamp(int(floor(tzs * float(vs.z))), 0, vs.z - 1));",
      "   float tagS = texelFetch(uV, ts, 0).g;",
      "   float zTop = uGrid.z + uGrid.x * uGrid.y, zExit = floor(zs + 0.02) + 1.0;",
      "   int N = int(uMarchN.x);",
      "   float T = 1.0, F = 0.0;",
      "   float span = max(zTop - zs, 0.0), dz = span / float(N), dl = dz * 4.8989795;",
      "   for (int k = N - 1; k >= 0; k--) {",
      "      float z = zs + (float(k) + jit) * dz;",
      "      vec2 vt = texture(uV, vec3(txy, tczE(z))).rg;",
      "      float sigma = vt.g < 0.5 / 255.0 ? uSig.x * exp(-max(z, 0.0) / uSig.z)",
      "         : (uMarchN.y > 0.5 && abs(vt.g - tagS) < 0.5 / 255.0 && z < zExit ? uSig.y : 0.0);",
      "      float a = exp(-sigma * dl);",
      "      F += T * vt.r * (1.0 - a);",
      "      T *= a;",
      "   }",
      "   tr = T;",
      "   return uC.rgb * F;",
      "}");

   /** Method epipolar, pass 1: the coarse samples, 8 buffer texels apart along lines parallel to the light's screen direction. */
   static final String EPI_SAMPLE_FRAG = String.join("\n",
      "#version 330",
      MARCH_FN_GLSL,
      "uniform vec4 uEpi;", // light's direction on the buffer (unit, texels), s min, t min
      "uniform vec4 uEpi2;", // spacing along the lines (texels), buffer w, h, px per buffer texel
      "uniform vec4 uVp0;", // the world viewport's origin (window px)
      "out vec4 o;",
      "void main() {",
      "   vec2 d = uEpi.xy, n = vec2(-d.y, d.x);",
      "   float s = uEpi.z + floor(gl_FragCoord.x) * uEpi2.x, t = uEpi.w + floor(gl_FragCoord.y);",
      "   vec2 b = s * d + t * n;", // buffer texel coordinates (centres at .5)
      "   if (b.x < 0.0 || b.y < 0.0 || b.x >= uEpi2.y || b.y >= uEpi2.z) { o = vec4(0.0, 0.0, 0.0, -1e6); return; }",
      "   ivec2 p = ivec2(uVp0.xy + floor(b) * uEpi2.w + floor(uEpi2.w * 0.5));",
      "   float zs, tr;",
      "   vec3 c = marchAt(p, 0.5, zs, tr);",
      "   o = vec4(c, zs);",
      "}",
      "");

   /**
    * Method epipolar, pass 2 (the god ray buffer): each texel's line and place along it; between two samples of the line
    * whose surfaces lie at its own height (within 0.12 levels) the in-scatter is interpolated, else (a depth discontinuity
    * along the line) the texel marches its own column.
    */
   static final String EPI_RESOLVE_FRAG = String.join("\n",
      "#version 330",
      MARCH_FN_GLSL,
      "uniform sampler2D uSamples;",
      "uniform vec4 uEpi;",
      "uniform vec4 uEpi2;",
      "uniform vec4 uVp0;",
      "out vec4 o;",
      "void main() {",
      "   vec2 d = uEpi.xy, n = vec2(-d.y, d.x);",
      "   vec2 b = floor(gl_FragCoord.xy) + 0.5;",
      "   float s = dot(b, d), t = dot(b, n);",
      "   int line = int(floor(t - uEpi.w + 0.5));",
      "   float kf = (s - uEpi.z) / uEpi2.x;",
      "   int k0 = int(floor(kf));",
      "   float fk = kf - float(k0);",
      "   vec4 A = texelFetch(uSamples, ivec2(k0, line), 0), B = texelFetch(uSamples, ivec2(k0 + 1, line), 0);",
      "   ivec2 p = ivec2(uVp0.xy + floor(gl_FragCoord.xy) * uEpi2.w + floor(uEpi2.w * 0.5));",
      "   float d0 = texelFetch(uDepth, p, 0).r;",
      "   vec4 q = vec4((vec2(p) + 0.5) / vec2(textureSize(uDepth, 0)), d0, 1.0);",
      "   float zs = uGrid.z + ((d0 >= 0.99999 ? uC.w : dot(uZ, q)) * uGrid.x - 0.5) * uGrid.y;",
      "   bool okA = abs(A.a - zs) < 0.12, okB = abs(B.a - zs) < 0.12;",
      "   vec3 c;",
      "   float tr = 1.0;",
      "   if (okA && okB) c = mix(A.rgb, B.rgb, fk);",
      "   else if (okA && fk < 0.5) c = A.rgb;",
      "   else if (okB && fk >= 0.5) c = B.rgb;",
      "   else { float z2; c = marchAt(p, 0.5, z2, tr); }", // the refinement: a march of its own
      "   o = vec4(sqrt(clamp(c * 0.5, 0.0, 1.0)), 0.5);",
      "}",
      "");

   static final String MARCH_FRAG = String.join("\n",
      "#version 330",
      "layout(location = 0) out vec4 o;",
      "layout(location = 1) out vec4 hist;",
      "uniform sampler3D uV;", // V, room tag
      "uniform sampler3D uMM;", // min V, max V, max tag per 8 slices
      "uniform sampler2D uDepth;",
      "uniform sampler2D uHist;",
      "uniform vec4 uLow;", // px per texel, 1 = rooms' dust here (light volumes off), samples
      "uniform vec4 uX;",
      "uniform vec4 uY;",
      "uniform vec4 uZ;",
      "uniform vec4 uC;", // light colour, ground tc.z
      "uniform vec4 uL;", // light dir, patch gain
      "uniform vec4 uMarch;", // jitter offset, minmax, new sample weight, history valid
      "uniform vec4 uPrev;", // previous buffer uv = gl_FragCoord.xy * xy + zw
      "uniform vec4 uSig;", // haze sigma, dust sigma, haze height
      "uniform vec4 uGrid;", // nz, cz, zLo
      WORLD_GLSL,
      "float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }",
      "float tcz(float z) { return (z - uGrid.z) / (uGrid.x * uGrid.y); }",
      "float hazeTau(float z0, float z1) {", // the haze's optical depth over the view path between heights z0 < z1 (levels)
      "   float H = uSig.z;",
      "   return uSig.x * H * (exp(-max(z0, 0.0) / H) - exp(-max(z1, 0.0) / H)) * 4.8989795;",
      "}",
      "void main() {",
      "   ivec2 p = ivec2(uMapB.zw + floor(gl_FragCoord.xy) * uLow.xy + floor(uLow.xy * 0.5));",
      "   ivec2 ds = textureSize(uDepth, 0);",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   vec4 q = vec4((vec2(p) + 0.5) / vec2(ds), d, 1.0);",
      "   vec2 txy = vec2(dot(uX, q), dot(uY, q));",
      "   float tzs = d >= 0.99999 ? uC.w : dot(uZ, q);",
      "   float zs = uGrid.z + (tzs * uGrid.x - 0.5) * uGrid.y;", // the surface's height (levels)
      "   ivec3 vs = textureSize(uV, 0);",
      "   ivec3 ts = ivec3(ivec2(mod(floor(txy * vec2(vs.xy)), vec2(vs.xy))), clamp(int(floor(tzs * float(vs.z))), 0, vs.z - 1));",
      "   float tagS = texelFetch(uV, ts, 0).g;",
      "   float zTop = uGrid.z + uGrid.x * uGrid.y, zExit = floor(zs + 0.02) + 1.0;",
      "   int N = int(uLow.w);",
      "   float jit = fract(ign(gl_FragCoord.xy) + uMarch.x);",
      "   float T = 1.0, F = 0.0, D = 0.0;",
      "   float top = zTop;",
      "   if (uMarch.y > 0.5) {", // minmax: whole blocks of 8 slices from the top while they are constant and outdoors
      "      float bz = 8.0 * uGrid.y;",
      "      for (int b = int(uGrid.x / 8.0) - 1; b >= 0; b--) {",
      "         float z0 = uGrid.z + float(b) * bz, z1 = z0 + bz;",
      "         if (z0 <= zs) break;",
      "         vec4 mm = texture(uMM, vec3(txy, (float(b) + 0.5) / (uGrid.x / 8.0)));",
      "         if (mm.b > 0.5 / 255.0 || mm.g - mm.r > 1.5 / 255.0) break;", // rooms or a shadow edge inside: march it
      "         float a = exp(-hazeTau(z0, z1));",
      "         F += T * mm.r * (1.0 - a); T *= a;",
      "         top = z0;",
      "      }",
      "   }",
      "   float span = max(top - zs, 0.0), dz = span / float(N), dl = dz * 4.8989795;",
      "   for (int k = N - 1; k >= 0; k--) {",
      "      float z = zs + (float(k) + jit) * dz;",
      "      vec2 vt = texture(uV, vec3(txy, tcz(z))).rg;",
      "      float sigma = 0.0;",
      "      bool dust = false;",
      "      if (vt.g < 0.5 / 255.0) sigma = uSig.x * exp(-max(z, 0.0) / uSig.z);",
      "      else if (uLow.z > 0.5 && abs(vt.g - tagS) < 0.5 / 255.0 && z < zExit) { sigma = uSig.y; dust = true; }",
      "      float a = exp(-sigma * dl);",
      "      float inc = T * vt.r * (1.0 - a);",
      "      if (dust) D += inc; else F += inc;",
      "      T *= a;",
      "   }",
      "   vec3 cur = uC.rgb * (F + D);",
      "   vec2 puv = gl_FragCoord.xy * uPrev.xy + uPrev.zw;",
      "   vec4 h = texture(uHist, puv);",
      "   float w = uMarch.z;",
      "   if (uMarch.w < 0.5 || puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0 || abs(h.a - zs) > 0.15) w = 1.0;",
      "   vec3 res = mix(h.rgb, cur, w);",
      "   hist = vec4(res, zs);",
      "   o = vec4(sqrt(clamp(res * 0.5, 0.0, 1.0)), clamp(T * 0.5, 0.0, 1.0));",
      "}",
      "");

   /**
    * The god ray buffer as a compute dispatch (godRaysLowCompute): LOW_FRAG's code with its main wrapped and the texel written
    * through an image, so the pass needs no framebuffer switch (NVIDIA's fixed cost of a pass was most of its 22 us in game).
    */
   static final String LOW_CS = LOW_FRAG
      .replace("#version 330", "#version 430\nlayout(local_size_x = 8, local_size_y = 8) in;\nlayout(rgba8, binding = 4) uniform writeonly image2D uOut;\nuniform ivec2 uLowSize;")
      .replace("gl_FragCoord.xy", "(vec2(gl_GlobalInvocationID.xy) + 0.5)")
      .replace("out vec4 o;", "vec4 o;")
      .replace("void main() {", "void lowMain() {")
      + "void main() {\n   if (int(gl_GlobalInvocationID.x) >= uLowSize.x || int(gl_GlobalInvocationID.y) >= uLowSize.y) return;\n   lowMain();\n   imageStore(uOut, ivec2(gl_GlobalInvocationID.xy), o);\n}\n";

   /** Local lights: a screen rectangle a light (window px), its index. */
   static final String LL_VERT = String.join("\n",
      "#version 330",
      "layout(location = 0) in vec3 aPos;",
      "uniform vec4 uMapB;", // (kC, cC,) viewport x, y
      "uniform vec4 uVp;", // viewport w, h
      "flat out int vL;",
      "void main() {",
      "   vL = int(aPos.z + 0.5);",
      "   gl_Position = vec4(2.0 * (aPos.xy - uMapB.zw) / uVp.xy - 1.0, 0.0, 1.0);",
      "}",
      "");

   /**
    * A local light's airlight on the pixel: its view column P0 + z D (D = (3, 3, 2.449) squares a level) clipped to the
    * light's reach R, its level (the air of its room or street), the part in front of the surface and, for a torch or a
    * headlight, its cone; over that stretch the closed form of the single scattering of a point light, less a constant
    * 1 / R^2 so it fades to nothing at the reach: atan terms / h. Screen-blended.
    */
   static final String LL_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uDepth;",
      "uniform vec4 uLA[" + MAX_LIGHTS + "];", // x, y (from the lights' reference square), z levels, reach
      "uniform vec4 uLB[" + MAX_LIGHTS + "];", // axis x, y, cone cosine (-2: a point light), medium density
      "uniform vec4 uLC[" + MAX_LIGHTS + "];", // colour x strength, kind
      "uniform vec4 uOrgL;", // the lights' reference square from the view origin
      "uniform vec4 uLP[" + (MAX_LIGHTS * 5) + "];", // a spill's frustum: its opening's plane, then its four sides (n, d; squares from the reference square)
      "uniform vec4 uMapA;",
      "uniform vec4 uMapB;",
      "flat in int vL;",
      "out vec4 o;",
      "void main() {",
      "   ivec2 p = ivec2(gl_FragCoord.xy);",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   vec2 px = vec2(p) + 0.5;",
      "   float u = uMapA.x * px.x + uMapA.y, v = uMapA.z * px.y + uMapA.w;",
      "   vec3 P0 = vec3((v + u) * 0.5 - uOrgL.x, (v - u) * 0.5 - uOrgL.y, 0.0), D = vec3(3.0, 3.0, 2.4494897);",
      "   float zs = d >= 0.99999 ? -1e6 : (uMapB.x * d + uMapB.y - v) * 0.125;",
      "   vec4 A = uLA[vL], B = uLB[vL], C = uLC[vL];",
      "   vec3 W = P0 - vec3(A.xy, A.z * 2.4494897);",
      "   float R = A.w, a = dot(D, D), b = 2.0 * dot(D, W), c = dot(W, W);",
      "   float disc = b * b - 4.0 * a * (c - R * R);",
      "   if (disc <= 0.0) discard;",
      "   float sq = sqrt(disc);",
      "   float lq = floor(A.z + 0.01);",
      "   float z1 = max((-b - sq) / (2.0 * a), max(lq, zs)), z2 = min((-b + sq) / (2.0 * a), lq + 1.0);",
      "   if (B.z > -1.5) {", // the cone: ((W + zD).ax)^2 >= cc |W + zD|^2 on the forward side
      "      vec3 ax = normalize(vec3(B.x, B.y, -0.12));",
      "      float cc = B.z * B.z, wa = dot(W, ax), da = dot(D, ax);",
      "      float qa = da * da - cc * a, qb = 2.0 * wa * da - cc * b, qc = wa * wa - cc * c;",
      "      float qd = qb * qb - 4.0 * qa * qc;",
      "      if (abs(qa) < 1e-6) { if (abs(qb) < 1e-6) discard; float r0 = -qc / qb; if (qb > 0.0) z1 = max(z1, r0); else z2 = min(z2, r0); }",
      "      else if (qd < 0.0) { if (qa < 0.0) discard; }",
      "      else {",
      "         float s2 = sqrt(qd), r1 = (-qb - s2) / (2.0 * qa), r2 = (-qb + s2) / (2.0 * qa);",
      "         float lo = min(r1, r2), hi = max(r1, r2);",
      "         if (qa < 0.0) { z1 = max(z1, lo); z2 = min(z2, hi); }",
      "         else {", // outside [lo, hi]: keep the side in front of the light
      "            float mid = 0.5 * (z1 + z2);",
      "            if (wa + mid * da >= 0.0) { if (mid <= lo) z2 = min(z2, lo); else z1 = max(z1, hi); }",
      "            else discard;",
      "         }",
      "      }",
      "      if (abs(da) > 1e-6) { float zf = -wa / da; if (da > 0.0) z1 = max(z1, zf); else z2 = min(z2, zf); }",
      "      else if (wa < 0.0) discard;",
      "   }",
      "   float soft = 1.0;",
      "   int sb = vL * 5;",
      "   if (C.w > 2.5) {", // a room's light spilling out through an open door or a window (godRaysSpill): its frustum through the opening, widened by the penumbra
      "      for (int i = 0; i < 5; i++) {",
      "         vec4 pl = uLP[sb + i]; pl.w += i == 0 ? 0.0 : 0.25;",
      "         float Ap = dot(pl.xyz, P0) + pl.w, Bp = dot(pl.xyz, D);",
      "         if (abs(Bp) < 1e-7) { if (Ap < 0.0) discard; }",
      "         else if (Bp > 0.0) z1 = max(z1, -Ap / Bp); else z2 = min(z2, -Ap / Bp);",
      "      }",
      "      if (z2 <= z1) discard;",
      // the light is a room's ceiling light, not a point: the frustum's sides ramp over a penumbra that widens with the
      // distance from the opening (4 samples along the stretch)
      "      soft = 0.0;",
      "      for (int k = 0; k < 2; k++) {",
      "         vec3 P = P0 + D * (z1 + (float(k) * 0.5 + 0.25) * (z2 - z1));",
      "         float Dd = max(dot(uLP[sb].xyz, P) + uLP[sb].w, 0.0), Wd = 0.05 + 0.12 * Dd, r = 1.0;",
      "         for (int i = 1; i < 5; i++) r *= smoothstep(-Wd, Wd, dot(uLP[sb + i].xyz, P) + uLP[sb + i].w);",
      "         soft += 0.5 * r;",
      "      }",
      "   }",
      "   if (z2 <= z1) discard;",
      // h: the view column's distance from the light, widened by the light's core (uOrgL.z, squares): a point light's
      // closed form goes as 1 / h, a white-hot point floating at the bulb wherever a column passes through a lamp or a
      // headlight (the maintainer's "floating orbs"); a finite source keeps the glow a soft falloff with no core
      "   float zc = -b / (2.0 * a), h = sqrt(max(c - b * b / (4.0 * a), 0.0) + uOrgL.z * uOrgL.z), ra = sqrt(a);",
      "   float I = (atan(ra * (z2 - zc) / h) - atan(ra * (z1 - zc) / h)) / h - ra * (z2 - z1) / (R * R);",
      "   float ins = B.w * max(I, 0.0) * soft;",
      "   o = vec4(sqrt(clamp(C.rgb * ins, 0.0, 1.0)), 0.0);",
      "}",
      "");

   /**
    * godRaysBounce: a sunlit patch's light on the surfaces round it (one box a patch). The patch is a disk of radius r facing
    * up at its centroid C: a surface point P above it in the same room (the occupancy's room hash of P's square) takes
    * E = L r^2 cos_s cos_r / (d^2 + r^2) (the disk's form factor), cos_s from the patch's up, cos_r the surface's facing to
    * C from the depth; fading out by the reach. The picture is multiplied by sqrt(1 + E) (display space).
    */
   static final String BOUNCE_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uDepth;",
      "uniform usampler3D uOccB;",
      "uniform vec4 uB[" + MAX_BOUNCES + "];",
      "uniform vec4 uBC[" + MAX_BOUNCES + "];",
      "uniform vec4 uOrgB;", // the prisms' reference from the view origin
      "uniform ivec4 uRefB;", // the prisms' reference (absolute squares), the occupancy's lowest level
      "uniform vec4 uVp;", // z: 2 the dev view (devGodRaysBounceView)
      WORLD_GLSL,
      "flat in int vL;",
      "out vec4 o;",
      "void main() {",
      "   ivec2 p = ivec2(gl_FragCoord.xy);",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   bool dv = uVp.z > 1.5;", // dev (devGodRaysBounceView): blue out of reach / below the patch, red another room, green lit
      "   if (d >= 0.99999) discard;",
      "   vec3 P = worldAt(vec2(p) + 0.5, d) - vec3(uOrgB.xy, 0.0);",
      "   vec4 B = uB[vL], C = uBC[vL];",
      "   vec3 w = B.xyz - P;",
      "   float d2 = dot(w, w);",
      "   if (d2 > 9.0) { if (dv) { o = vec4(0.85, 0.85, 1.0, 1.0); return; } discard; }",
      "   float cs = (P.z - B.z) * inversesqrt(d2 + 1e-4);",
      "   if (cs <= 0.0) { if (dv) { o = vec4(0.7, 0.7, 1.0, 1.0); return; } discard; }",
      "   vec3 Pq = P + normalize(w) * 0.06;", // (a wall pixel's square: a hair toward the patch, inside the room)
      "   ivec2 sq = ivec2(floor(Pq.xy));",
      "   int lz = int(floor(B.z / 2.4494897 + 0.01)) - uRefB.z;",
      "   uint oc = texelFetch(uOccB, ivec3((uRefB.x + sq.x) & " + (OCC_N - 1) + ", (uRefB.y + sq.y) & " + (OCC_N - 1) + ", clamp(lz, 0, " + (OCC_L - 1) + ")), 0).r;",
      "   if ((oc >> 16u) != uint(C.w + 0.5)) { if (dv) { o = vec4(1.0, 0.6, 0.6, 1.0); return; } discard; }",
      "   float cr = facingAt(uDepth, p, d, normalize(w));",
      "   float fade = 1.0 - d2 / 9.0;",
      "   vec3 E = C.rgb * B.w * B.w * cs * cr / (d2 + B.w * B.w) * fade * fade;",
      "   o = dv ? vec4(0.6, 1.0, 0.6, 1.0) : vec4(sqrt(1.0 + E), 1.0);",
      "}",
      "");

   /** The light volumes straight into the world picture (apDirect): dual-source output, index 0 the beam, 1 the multiplier. */
   static final String APD_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uDepth;",
      "uniform vec4 uLight;",
      "uniform vec4 uVp;",
      "uniform vec4 uC;", // the light's colour x strength x phase
      "uniform vec4 uS;", // x path -> dust in-scatter, y patch gain, z on (the dev alternation), w the dust motes' clock (0: none)
      "uniform vec4 uOrg;",
      "uniform vec4 uDoor;", // x the door leaves on (devGodRaysDoorAlternate), y the characters' capsules
      "uniform vec4 uCap[" + (MAX_CAPS * 2) + "];", // godRaysCharShadows: per capsule a (xyz, r) and b (xyz, -), squares from the prisms' reference
      "uniform vec4 uSoft;", // x the penumbra's half width at the aperture, y its growth a square from it (squares), z the fade a square along the beam, w the glints' gain
      WORLD_GLSL,
      "flat in vec4 pl0, pl1, pl2, pl3, pl4, pl5, pl6, pl7, pl8, pl9, pl10;",
      "layout(location = 0, index = 0) out vec4 beam;",
      "layout(location = 0, index = 1) out vec4 mul;",
      // how much of the beam's light reaches P: across each of the four sides (the frame's edges carried down the light's
      // way) the light ramps over the penumbra, W either side of the hard edge, W growing with the distance D from the
      // aperture; the shaft thins out a little along its way. (The sides moved out by W are the soft hull: soft().)
      "float ramp(vec4 pl, vec3 P, float W) { float s = dot(pl.xyz, P) + pl.w; return W < 1e-4 ? step(0.0, s) : smoothstep(-W, W, s); }",
      // an open door's leaf (pl8 pivot, bottom, top; pl9 direction, length, glass band): the light from P toward the key
      // light crosses the leaf's plane; inside the leaf (soft by the penumbra at that distance) it is shaded, a glass band
      // lets 80 % through (godRaysDoorLeaf)
      "float leafT(vec3 P) {",
      "   if (pl9.z <= 0.0 || uDoor.x < 0.5) return 1.0;",
      "   vec2 n = vec2(-pl9.y, pl9.x);",
      "   float dn = dot(n, uLight.xy);",
      "   if (abs(dn) < 1e-4) return 1.0;",
      "   float s = dot(n, pl8.xy - P.xy) / dn;",
      "   if (s <= 0.0) return 1.0;",
      "   vec3 H = P + s * uLight.xyz;",
      "   float a = dot(H.xy - pl8.xy, pl9.xy), W = uSoft.x + uSoft.y * s * length(uLight.xy) + 0.01;",
      "   float in_ = smoothstep(-W, W, a) * smoothstep(-W, W, pl9.z - a) * smoothstep(-W, W, H.z - pl8.z) * smoothstep(-W, W, pl8.w - H.z);",
      "   if (pl9.w >= 0.0) {",
      "      uint g = uint(pl9.w + 0.5);",
      "      vec4 gb = vec4(float(g & 15u), float((g >> 4u) & 15u), float((g >> 8u) & 15u), float((g >> 12u) & 15u)) / 15.0;",
      "      float ua = a / max(pl9.z, 1e-3), uz = (H.z - pl8.z) / max(pl8.w - pl8.z, 1e-3) * 0.8;", // (the band's height is in levels; the leaf is 0.8 of one)
      "      in_ *= 1.0 - 0.8 * smoothstep(gb.x - 0.03, gb.x + 0.03, ua) * smoothstep(gb.y + 0.03, gb.y - 0.03, ua) * smoothstep(gb.z - 0.03, gb.z + 0.03, uz) * smoothstep(gb.w + 0.03, gb.w - 0.03, uz);",
      "   }",
      "   return 1.0 - in_;",
      "}",
      // the characters in the light (godRaysCharShadows): the ray from P toward the light against each capsule of the
      // volume's mask (pl7.y): its closest approach to the capsule's axis, soft over a penumbra that widens with the way
      "float capsT(vec3 P) {",
      "   uint m = uint(pl7.y + 0.5);",
      "   if (m == 0u || uDoor.y < 0.5) return 1.0;",
      "   float t = 1.0;",
      "   vec3 L = uLight.xyz;",
      "   int nc = int(uDoor.z + 0.5);",
      "   for (int i = 0; i < nc; i++) {",
      "      if ((m & (1u << uint(i))) == 0u) continue;",
      "      vec4 A = uCap[i * 2], B = uCap[i * 2 + 1];",
      "      vec3 ba = B.xyz - A.xyz, oa = P - A.xyz;",
      "      float baba = dot(ba, ba), bard = dot(ba, L), baoa = dot(ba, oa), rdoa = dot(L, oa);",
      "      float den = baba - bard * bard;",
      // the ray P + s L (s >= 0, toward the light) against the axis A + h ba: the closest approach
      "      float s = den > 1e-6 ? (bard * baoa - baba * rdoa) / den : -rdoa;",
      "      s = max(s, 0.0);",
      "      float h = clamp((baoa + s * bard) / max(baba, 1e-6), 0.0, 1.0);",
      "      s = max(h * bard - rdoa, 0.0);",
      "      vec3 q = oa + s * L - h * ba;",
      "      float d = length(q), w = 0.03 + 0.09 * s;",
      "      float sh = 1.0 - smoothstep(A.w - w, A.w + w, d);",
      "      t *= 1.0 - 0.92 * sh;",
      "   }",
      "   return t;",
      "}",
      // value noise of the stirred dust: three octaves of a hashed lattice, advected down the light's way (the draft
      // through the doorway, ~0.45 squares a second) and rising a little
      "float hash3(vec3 p) { uvec3 q = uvec3(ivec3(floor(p)) + 32768); q = q * 1664525u + 1013904223u; q.x += q.y * q.z; q.y += q.z * q.x; q.z += q.x * q.y; q ^= q >> 16u; return float((q.x + q.y + q.z) & 0xFFFFu) / 65535.0; }",
      "float vnoise(vec3 p) { vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);",
      "   return mix(mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),",
      "              mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z); }",
      "float swirl(vec3 P, float t) {",
      "   vec3 q = P - vec3(-uLight.xy * 0.45, 0.06) * t;",
      "   float n = vnoise(q * 2.2) * 0.6 + vnoise(q * 4.7 + 7.1) * 0.3 + vnoise(q * 9.3 + 3.7) * 0.1;",
      "   return max(0.0, 2.2 * n - 0.55);",
      "}",
      "bool doorish;", // this volume has a door leaf or capsules in reach (set once a fragment: a window's volume skips both)
      "float cover(vec3 P) {",
      "   float D = max(dot(pl0.xyz, P) + pl0.w, 0.0), W = uSoft.x + uSoft.y * D;",
      "   float c = exp(-uSoft.z * D) * ramp(pl1, P, W) * ramp(pl2, P, W) * ramp(pl3, P, W) * ramp(pl4, P, W);",
      "   return doorish ? c * leafT(P) * capsT(P) : c;",
      "}",
      "vec4 soft(vec4 pl) { return pl + uSoft.y * pl0 + vec4(0.0, 0.0, 0.0, uSoft.x); }", // a side moved out by W = x + y D
      "void main() {",
      "   if (uS.z < 0.5) { beam = vec4(0.0); mul = vec4(1.0); return; }",
      "   doorish = (pl9.z > 0.0 && uDoor.x > 0.5) || (pl7.y > 0.5 && uDoor.y > 0.5);",
      "   ivec2 p = ivec2(gl_FragCoord.xy);",
      "   float d = uVp.z > 1.5 && uVp.z < 2.5 ? 1.0 : texelFetch(uDepth, p, 0).r;", // (z 2, dev: no depth read)
      "   vec2 px = vec2(p) + 0.5;",
      "   float u = uMapA.x * px.x + uMapA.y, v = uMapA.z * px.y + uMapA.w;",
      "   vec3 P0 = vec3((v + u) * 0.5 - uOrg.x, (v - u) * 0.5 - uOrg.y, 0.0), dP = vec3(3.0, 3.0, 2.4494897);", // (the half-spaces are relative to the prisms' reference square)
      "   float zs = d >= 0.99999 ? -1e6 : (uMapB.x * d + uMapB.y - v) * 0.125;",
      "   float zlo = -1e6, zhi = 1e6;",
      "   vec4 PL[8] = vec4[8](pl0, soft(pl1), soft(pl2), soft(pl3), soft(pl4), pl5, pl6, pl10);",
      "   for (int i = 0; i < 8; i++) {",
      "      vec4 pl = PL[i];",
      "      float A = dot(pl.xyz, P0) + pl.w, B = dot(pl.xyz, dP);",
      "      if (abs(B) < 1e-7) { if (A < 0.0) { zhi = zlo - 1.0; } }",
      "      else if (B > 0.0) zlo = max(zlo, -A / B); else zhi = min(zhi, -A / B);",
      "   }",
      "   float vis = pl7.x;",
      "   float za = max(zlo, zs), path = 0.0;",
      // a door just opened (pl7.z seconds ago, godRaysDoorDustPct): the air it stirred is dustier, in swirls drifting in
      // through the doorway with the draft, settling over a few seconds
      "   float puffA = pl7.z >= 0.0 ? uDoor.w * exp(-pl7.z / 2.8) * smoothstep(0.0, 0.35, pl7.z) : 0.0;",
      "   if (zhi > za) {", // the in-scatter along the column through the soft beam: 6 midpoints
      "      float dz = (zhi - za) / 6.0;",
      "      for (int k = 0; k < 6; k++) {",
      "         vec3 Pk = P0 + dP * (za + (float(k) + 0.5) * dz);",
      "         float c = cover(Pk);",
      "         if (puffA > 0.01) c *= 1.0 + puffA * swirl(Pk, pl7.z);",
      "         path += c;",
      "      }",
      "      path *= dz * 8.0;",
      "   }",
      "   float lit = 0.0;",
      "   if (zs >= zlo && zs <= zhi && vis > 0.0) {", // (a branch: the four depth taps only inside)
      "      vec3 Ps = P0 + dP * zs;", // the surface the shaft lands on: the patch blurs like the shaft (not on the window's own wall)
      "      lit = vis * cover(Ps) * smoothstep(0.0, 0.12, dot(pl0.xyz, Ps) + pl0.w) * facingAt(uDepth, p, d, uLight.xyz);",
      "   }",
      "   float ins = vis * path * uS.x;",
      // dust motes: one speck per world (u, v) cell of 0.12 x 0.24, at a hashed height drifting slowly, lit where it lies
      // in the beam in front of the surface (one hash per fragment); each one tumbles, so now and then it catches the
      // light and glints: a bright core with a soft halo for a fraction of a second, at its own pace
      "   if (uS.w > 0.0 && path > 0.0) {",
      "      vec2 cu = vec2(u - uOrg.x + uOrg.y + uOrg.z, v - uOrg.x - uOrg.y + uOrg.w) / vec2(0.12, 0.24);", // the world's own (u, v): the specks stay put across chunk crossings
      "      vec2 ci = floor(cu), cf = cu - ci;",
      // (pcg3d of the cell: the cells' indices run to ~1e5 here, where a sin() hash repeats along rows of cells)
      "      uvec3 hv = uvec3(ivec3(ci, 7)) * 1664525u + 1013904223u;",
      "      hv.x += hv.y * hv.z; hv.y += hv.z * hv.x; hv.z += hv.x * hv.y; hv ^= hv >> 16u;",
      "      hv.x += hv.y * hv.z; hv.y += hv.z * hv.x; hv.z += hv.x * hv.y;",
      "      vec3 hh = vec3(hv >> 8u) * (1.0 / 16777216.0);",
      "      float zm = floor(zlo) + fract(hh.z + uS.w * (0.004 + 0.006 * hh.x));", // the speck's height, drifting
      "      vec2 at = vec2(0.5) + 0.35 * sin(vec2(uS.w * 0.21, uS.w * 0.17) + hh.yz * 6.2831);",
      "      float r = length((cf - at) * vec2(0.12, 0.24)) / 0.018;",
      "      if (hh.x < 0.26 + 0.35 * min(puffA, 1.0) && zm > max(zlo, zs) && zm < zhi && r < 4.0) {",
      "         float c = cover(P0 + dP * zm);",
      "         float tw = sin(uS.w * (1.1 + 2.6 * fract(hh.y * 7.13)) + hh.z * 6.2831);",
      "         float flash = pow(max(tw, 0.0), 24.0);",
      "         float speck = exp(-2.5 * r * r), halo = exp(-0.45 * r * r);",
      "         ins += vis * c * uS.x * (1.6 * speck + uSoft.w * flash * (14.0 * speck + 2.5 * halo));",
      "      }",
      "   }",
      "   beam = vec4(sqrt(uC.rgb * ins), 0.0);",
      "   mul = vec4(vec3(sqrt(1.0 + uS.y * lit)), 1.0);",
      "   if (uVp.z > 2.5) {", // dev (devGodRaysSoftView): red = the mean cover along the column inside the hull, green = the hull's stretch
      "      float st = max(zhi - za, 0.0);",
      "      beam = vec4(st > 1e-4 ? path / (st * 8.0) : 0.0, min(st, 1.0) * 0.5, 0.0, 0.0); mul = vec4(1.0);",
      "   }",
      "}",
      "");

   /** The light volumes onto the world picture (apComposite): exact in display-linear, one read and one write a covered pixel. */
   static final String APC_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uWorld;", // the world framebuffer's colour (read at the pixel written: texture barrier)
      "uniform sampler2D uAp;", // the light volumes (half size): R path x light, G surface lit x facing
      "uniform vec4 uVp;", // the world viewport (window px)
      "uniform vec4 uC;", // the light's colour x strength x phase
      "uniform vec4 uS;", // x path -> dust in-scatter, y patch gain, z dev view
      "out vec4 o;",
      "void main() {",
      "   vec2 a = texture(uAp, (gl_FragCoord.xy - uVp.xy) / uVp.zw).rg;",
      "   if (a.r <= 1e-4 && a.g <= 1e-4) discard;",
      "   vec3 c = texelFetch(uWorld, ivec2(gl_FragCoord.xy), 0).rgb;",
      "   float dust = min(a.r, 24.0) * uS.x, lit = clamp(a.g, 0.0, 1.0);",
      "   if (uS.z > 0.5) { o = vec4(sqrt(uC.rgb * dust * 8.0) + vec3(uS.z > 1.5 ? lit : 0.0), 1.0); return; }",
      "   o = vec4(sqrt(c * c * (1.0 + uS.y * lit) + uC.rgb * dust), 1.0);",
      "}",
      "");

   /** Light volume prisms (their camera-facing faces): the view origin's world (x, y, z levels) -> the world framebuffer's window. */
   static final String AP_VERT = String.join("\n",
      "#version 330",
      "layout(location = 0) in vec4 aPos;", // x, y relative to the view origin, z levels, the prism's index
      "uniform vec4 uMapA;", // kA, cA, kB, cB
      "uniform vec4 uMapB;", // kC, cC, viewport x, y
      "uniform vec4 uVp;", // viewport w, h; w: the depth-tested draw's bias toward the camera (w units), 0 = no depth
      "uniform samplerBuffer uPlanes;",
      "flat out vec4 pl0, pl1, pl2, pl3, pl4, pl5, pl6, pl7, pl8, pl9, pl10;", // the prism's half-spaces and light, a door leaf, a porch roof's edge (fetched per vertex, not per fragment)
      "uniform vec4 uOrg;", // the prisms' reference square relative to the view origin
      "void main() {",
      "   float wx = aPos.x + uOrg.x, wy = aPos.y + uOrg.y;",
      "   float u = wx - wy, v = wx + wy - 6.0 * aPos.z;",
      "   int b = int(aPos.w + 0.5) * 11;",
      "   pl0 = texelFetch(uPlanes, b); pl1 = texelFetch(uPlanes, b + 1); pl2 = texelFetch(uPlanes, b + 2); pl3 = texelFetch(uPlanes, b + 3);",
      "   pl4 = texelFetch(uPlanes, b + 4); pl5 = texelFetch(uPlanes, b + 5); pl6 = texelFetch(uPlanes, b + 6); pl7 = texelFetch(uPlanes, b + 7);",
      "   pl8 = texelFetch(uPlanes, b + 8); pl9 = texelFetch(uPlanes, b + 9); pl10 = texelFetch(uPlanes, b + 10);",
      "   vec2 px = vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      // the face's own scene depth (w = x + y + 2 z; the scene's depth is linear in w): the hardware depth test drops the
      // fragments behind a roof or a wall before they run (the rooms of the buildings not cut away: most prisms on screen)
      "   float zc = uVp.w != 0.0 ? 2.0 * ((wx + wy + 2.0 * aPos.z + uVp.w) - uMapB.y) / uMapB.x - 1.0 : 0.0;",
      "   gl_Position = vec4(2.0 * (px - uMapB.zw) / uVp.xy - 1.0, zc, 1.0);",
      "}",
      "");

   /**
    * Analytic light volumes: the pixel's view column (the world line of constant u, v; z its parameter) clipped against
    * the prism's 7 half-spaces (Cyrus-Beck) gives the stretch [z_lo, z_hi] inside it; R the stretch above the surface
    * (w units: 8 per level), G the prism's light x the surface's facing when the surface lies inside it (the sunlit
    * patch, exact). One fragment per prism and pixel (only its camera-facing faces are drawn): nothing to cancel.
    */
   static final String AP_FRAG = String.join("\n",
      "#version 330",
      "uniform sampler2D uDepth;",
      "flat in vec4 pl0, pl1, pl2, pl3, pl4, pl5, pl6, pl7, pl8, pl9, pl10;", // 7 half-spaces (n, d; squares from the view origin), (light, 0, 0, 0), a door leaf, a porch roof's edge
      "uniform vec4 uLight;", // the direction to the light (world squares)
      "uniform vec4 uVp;", // viewport w, h, full-size px per target texel
      "uniform vec4 uOrg;",
      WORLD_GLSL,
      "out vec4 o;",
      "void main() {",
      "   ivec2 p = ivec2(uMapB.zw + floor(gl_FragCoord.xy) * uVp.z + floor(uVp.z * 0.5));",
      "   float d = texelFetch(uDepth, p, 0).r;",
      "   vec2 px = vec2(p) + 0.5;",
      "   float u = uMapA.x * px.x + uMapA.y, v = uMapA.z * px.y + uMapA.w;",
      "   vec3 P0 = vec3((v + u) * 0.5 - uOrg.x, (v - u) * 0.5 - uOrg.y, 0.0), dP = vec3(3.0, 3.0, 2.4494897);", // (the half-spaces are relative to the prisms' reference square) // the column: P0 + z dP (z in levels)
      "   float zs = d >= 0.99999 ? -1e6 : (uMapB.x * d + uMapB.y - v) * 0.125;",
      "   float zlo = -1e6, zhi = 1e6;",
      "   vec4 PL[8] = vec4[8](pl0, pl1, pl2, pl3, pl4, pl5, pl6, pl10);",
      "   for (int i = 0; i < 8; i++) {",
      "      vec4 pl = PL[i];",
      "      float A = dot(pl.xyz, P0) + pl.w, B = dot(pl.xyz, dP);",
      "      if (abs(B) < 1e-7) { if (A < 0.0) { zhi = zlo - 1.0; } }",
      "      else if (B > 0.0) zlo = max(zlo, -A / B); else zhi = min(zhi, -A / B);",
      "   }",
      "   float vis = pl7.x;",
      "   float path = max(zhi - max(zlo, zs), 0.0) * 8.0;",
      "   float lit = zs >= zlo && zs <= zhi ? vis * facingAt(uDepth, p, d, uLight.xyz) : 0.0;",
      "   o = vec4(vis * path, lit, 0.0, 1.0);",
      "}",
      "");

   // ------------------------------------------------------------------------------------------------ compute shaders

   /** The occupancy lookups and the key light's DDA through them, shared by the compute programs. */
   static final String COMMON_GLSL = String.join("\n",
      "uniform ivec4 uRegion;", // i0, j0 (absolute cells), w, h
      "uniform ivec4 uN;", // nx, ny, nz, occupancy's lowest level
      "uniform ivec4 uRef;", // refX, refY (squares), iRef, jRef (their cells)
      "uniform vec4 uCell;", // cu, cv, cz, zLo
      "uniform vec4 uSun;", // horizontal direction to the light (unit), levels risen per square across, 1 = overhead
      "uniform vec4 uLim;", // exit height (levels), max distance (squares), foliage extinction per square at full density, frame
      "uniform vec4 uSigma;", // haze extinction per square outdoors (at the ground), dust in rooms, haze height (levels), -
      "layout(binding = " + OCC_UNIT + ") uniform usampler3D uOcc;",
      "const float LH = 2.4494897;",
      "int imod(int a, int n) { return a - n * int(floor((float(a) + 0.5) / float(n))); }", // (% is undefined for negative operands: the cell indices are)
      "uint occ(ivec2 sq, int lvl) {",
      "   int lz = lvl - uN.w;",
      "   if (lz < 0 || lz >= " + OCC_L + ") return 0u;",
      "   return texelFetch(uOcc, ivec3((uRef.x + sq.x) & " + (OCC_N - 1) + ", (uRef.y + sq.y) & " + (OCC_N - 1) + ", lz), 0).r;",
      "}",
      "vec3 cellPos(ivec3 c) {", // (x, y relative to the ref square, z levels) of the cell's centre
      "   float u = (float(c.x - uRef.z) + 0.5) * uCell.x;",
      "   float v = (float(c.y - uRef.w) + 0.5) * uCell.y;",
      "   float z = uCell.w + (float(c.z) + 0.5) * uCell.z;",
      "   float s = v + 6.0 * z;",
      "   return vec3((s + u) * 0.5, (s - u) * 0.5, z);",
      "}",
      "bool edgeBlocks(uint e, float f) {", // e: 0 open, 1 wall, 2 window, 3 open doorway; f: the height in the level
      "   if (e == 0u) return false;",
      "   if (e == 1u) return true;",
      "   if (e == 2u) return f < 0.30 || f > 0.84;",
      "   return f > 0.80;",
      "}",
      "float hash3(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.x + p.y) * p.z); }",
      "float leafNoise(vec3 p) {", // value noise, ~0.7 squares: the gaps between the leaves
      "   vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);",
      "   float a = mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y);",
      "   float b = mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y);",
      "   return mix(a, b, f.z);",
      "}",
      "layout(binding = " + TOP_UNIT + ") uniform usampler2D uTop;", // per chunk slot: the level (above the occupancy's lowest) under which it can stop a ray
      "float chunkTop(ivec2 sq) {",
      "   ivec2 c = ivec2(((uRef.x + sq.x) >> 3) & " + (OCC_CHUNKS - 1) + ", ((uRef.y + sq.y) >> 3) & " + (OCC_CHUNKS - 1) + ");",
      "   return float(texelFetch(uTop, c, 0).r) + float(uN.w);",
      "}",
      // the way from p to q is clear (walls, windows at their height, floors crossed either way); both ref-relative, z levels
      "float pointVis(vec3 p, vec3 q) {",
      "   vec3 w = q - p;",
      "   float hl = length(w.xy);",
      "   ivec2 sq = ivec2(floor(p.xy));",
      "   ivec2 sqq = ivec2(floor(q.xy));",
      "   int lvl = int(floor(p.z));",
      "   if (hl < 1e-4) {",
      "      int l1 = int(floor(q.z));",
      "      for (int L = min(lvl, l1) + 1; L <= max(lvl, l1); L++) { if ((occ(sq, L) & 65u) != 0u) return 0.0; }",
      "      return 1.0;",
      "   }",
      "   vec2 d = w.xy / hl;",
      "   float slope = w.z / hl;",
      "   vec2 inv = 1.0 / max(abs(d), vec2(1e-5));",
      "   vec2 fr = p.xy - vec2(sq);",
      "   vec2 tMax = vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "   ivec2 stp = ivec2(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1);",
      "   for (int i = 0; i < 40; i++) {",
      "      float tn = min(min(tMax.x, tMax.y), hl);",
      "      float zn = p.z + tn * slope;",
      "      int ln = int(floor(zn));",
      "      for (int L = lvl + 1; L <= ln; L++) { if ((occ(sq, L) & 65u) != 0u) return 0.0; }", // up through a floor
      "      for (int L = lvl; L > ln; L--) { if ((occ(sq, L) & 1u) != 0u) return 0.0; }", // down through this level's floor
      "      lvl = ln;",
      "      if (tn >= hl) return 1.0;",
      "      float f = zn - float(ln);",
      "      if (tMax.x < tMax.y) {",
      "         uint e = (occ(d.x >= 0.0 ? sq + ivec2(1, 0) : sq, ln) >> 3) & 3u;",
      "         if (edgeBlocks(e, f)) return 0.0;",
      "         sq.x += stp.x; tMax.x += inv.x;",
      "      } else {",
      "         uint e = (occ(d.y >= 0.0 ? sq + ivec2(0, 1) : sq, ln) >> 1) & 3u;",
      "         if (edgeBlocks(e, f)) return 0.0;",
      "         sq.y += stp.y; tMax.y += inv.y;",
      "      }",
      "   }",
      "   return 1.0;",
      "}",
      "float sunVis(vec3 p) {", // p: x, y relative to the ref square, z in levels
      "   ivec2 sq = ivec2(floor(p.xy));",
      "   int lvl = int(floor(p.z));",
      "   if (uSun.w > 0.5) {",
      "      for (int L = lvl + 1; float(L) <= uLim.x; L++) { uint o = occ(sq, L); if ((o & 65u) != 0u) return 0.0; }",
      "      return 1.0;",
      "   }",
      "   vec2 d = uSun.xy;",
      "   float slope = uSun.z;",
      "   vec2 inv = 1.0 / max(abs(d), vec2(1e-5));",
      "   vec2 fr = p.xy - vec2(sq);",
      "   vec2 tMax = vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "   ivec2 stp = ivec2(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1);",
      "   float t = 0.0, vis = 1.0;",
      "   bool jumped = false;",
      "   for (int i = 0; i < 128; i++) {",
      // the two-level walk (Chen et al.'s min-max idea on the grid): above everything its chunk holds, the ray skips to
      // where it leaves the chunk (it only rises); the square DDA starts again from there
      "      if (!jumped && p.z + t * slope >= chunkTop(sq)) {",
      "         vec2 at = p.xy + d * t;",
      // the chunk of the square the walk is in (at a border the position alone floors into the chunk just left)
      "         ivec2 c0 = ((sq + (uRef.xy & 7)) >> 3) * 8 - (uRef.xy & 7);", // its corner, ref-relative
      "         vec2 tx = vec2(d.x >= 0.0 ? float(c0.x + 8) - at.x : at.x - float(c0.x), d.y >= 0.0 ? float(c0.y + 8) - at.y : at.y - float(c0.y)) * inv;",
      // to just short of the chunk's border: the square step below then crosses it (the edge there is the next chunk's)
      "         t += max(0.0, min(tx.x, tx.y) - 1e-3);",
      "         jumped = true;",
      "         if (p.z + t * slope > uLim.x || t > uLim.y) return vis;",
      "         at = p.xy + d * t;",
      "         sq = clamp(ivec2(floor(at)), c0, c0 + 7);",
      "         lvl = int(floor(p.z + t * slope));",
      "         fr = at - vec2(sq);",
      "         tMax = t + vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "         continue;",
      "      }",
      "      jumped = false;",
      "      float tn = min(tMax.x, tMax.y);",
      "      float zn = p.z + tn * slope;",
      "      int ln = int(floor(zn));",
      "      uint o = occ(sq, lvl);",
      "      uint fol = (o >> 8) & 15u;",
      "      if (fol != 0u) {", // a crown: its density x the leaf noise at the segment's middle
      "         vec3 m = vec3(p.xy + d * (0.5 * (t + tn)), (p.z + 0.5 * (t + tn) * slope) * LH);",
      "         float n = leafNoise(m * 1.4 + vec3(uRef.xy & 1023, 0.0));",
      "         vis *= exp(-uLim.z * float(fol) * max(0.0, n * 1.8 - 0.55) * (tn - t) * 1.2);",
      "      }",
      "      if ((o & 64u) != 0u && p.z + t * slope - float(lvl) < 0.5) return 0.0;", // inside a roof's slope
      "      for (int L = lvl + 1; L <= ln; L++) { uint oc = occ(sq, L); if ((oc & 65u) != 0u) return 0.0; }", // a floor or roof above
      "      lvl = ln;",
      "      t = tn;",
      "      if (zn > uLim.x || t > uLim.y) return vis;",
      "      float f = zn - float(ln);",
      "      if (tMax.x < tMax.y) {",
      "         uint e = (occ(d.x >= 0.0 ? sq + ivec2(1, 0) : sq, ln) >> 3) & 3u;",
      "         if (edgeBlocks(e, f)) return 0.0;",
      "         sq.x += stp.x; tMax.x += inv.x;",
      "      } else {",
      "         uint e = (occ(d.y >= 0.0 ? sq + ivec2(0, 1) : sq, ln) >> 1) & 3u;",
      "         if (edgeBlocks(e, f)) return 0.0;",
      "         sq.y += stp.y; tMax.y += inv.y;",
      "      }",
      "      if (vis < 0.02) return 0.0;",
      "   }",
      "   return vis;",
      "}");

   /**
    * Local lights by froxel injection (Wronski 2014): one thread per view column of the lights' footprints; down the
    * slices of each light's level, the light's in-scatter at the cell (1 / d^2 - 1 / R^2 within its reach and cone, the
    * medium's density) x the way to the light being clear (pointVis: walls, windows, floors), accumulated from the top: the
    * cumulative in-scatter per cell (rgb), which the god ray buffer taps at the surface. Columns no light reaches get 0 (the
    * previous frame's footprints are cleared that way).
    */
   static final String LOCAL_CORE_SQ = "2.25"; // the froxel injection's light core, squares^2 (1.5 squares, as godRaysLocalCorePct's default)
   static final String LOCAL_CS = String.join("\n",
      "#version 430",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(rgba16f, binding = 3) uniform writeonly image3D uL;",
      COMMON_GLSL,
      "uniform int uNL;",
      "uniform vec4 uLA[" + MAX_LIGHTS + "];", // x, y (ref-relative), z levels, reach
      "uniform vec4 uLB[" + MAX_LIGHTS + "];", // axis x, y, cone cosine (-2: point), density
      "uniform vec4 uLC[" + MAX_LIGHTS + "];", // colour x strength
      "void main() {",
      "   ivec2 g = ivec2(gl_GlobalInvocationID.xy);",
      "   if (g.x >= uRegion.z || g.y >= uRegion.w) return;",
      "   ivec2 c = ivec2(uRegion.x + g.x, uRegion.y + g.y);",
      "   ivec2 st = ivec2(imod(c.x, uN.x), imod(c.y, uN.y));",
      "   vec3 F = vec3(0.0);",
      "   float dl = uCell.z * " + VIEW_PATH + ";",
      "   for (int k = uN.z - 1; k >= 0; k--) {",
      "      vec3 p = cellPos(ivec3(c, k));",
      "      vec3 P = vec3(p.xy, p.z * LH);",
      "      for (int i = 0; i < uNL; i++) {",
      "         vec4 A = uLA[i], B = uLB[i];",
      "         if (floor(p.z) != floor(A.z + 0.01)) continue;", // its level's air only
      "         vec3 Q = vec3(A.xy, A.z * LH);",
      "         vec3 w = P - Q;",
      "         float d2 = dot(w, w);",
      "         if (d2 >= A.w * A.w) continue;",
      "         if (B.z > -1.5) { vec3 ax = normalize(vec3(B.x, B.y, -0.12)); float cd = dot(w, ax); if (cd <= 0.0 || cd * cd < B.z * B.z * d2) continue; }",
      "         float g2 = max(1.0 / (d2 + " + LOCAL_CORE_SQ + ") - 1.0 / (A.w * A.w), 0.0);", // (the light's core: no point singularity)
      "         float vis = pointVis(p, vec3(A.xy, A.z));",
      "         F += uLC[i].rgb * (B.w * g2 * vis * dl);",
      "      }",
      "      imageStore(uL, ivec3(st, k), vec4(F, 0.0));",
      "   }",
      "}",
      "");

   // ------------------------------------------------------------------------------------------------ the volume without compute

   /** A triangle over the viewport (the slice rectangle being written). */
   static final String VOL_VERT = String.join("\n",
      "#version 330",
      "void main() { gl_Position = vec4(vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0, 0.0, 1.0); }",
      "");

   /** VIS_CS for one slice: the texel is the fragment, its cell = texel + shift (the volume stores cells modulo its size). */
   static final String VIS_FRAG = String.join("\n",
      "#version 330",
      COMMON_GLSL,
      "uniform ivec3 uShiftK;",
      "out vec4 oV;",
      "void main() {",
      "   ivec2 st = ivec2(gl_FragCoord.xy);",
      "   vec3 p = cellPos(ivec3(st + uShiftK.xy, uShiftK.z));",
      "   uint o = occ(ivec2(floor(p.xy)), int(floor(p.z)));",
      "   float tag = (o & 32u) != 0u ? float(1u + ((o >> 16) % 255u)) / 255.0 : 0.0;",
      "   oV = vec4(sunVis(p), tag, 0.0, 0.0);",
      "}",
      "");

   /** INTEGRATE_CS for one slice: the column walked from the top down to it (the compute pass's state at that slice). */
   static final String INTEGRATE_FRAG = String.join("\n",
      "#version 330",
      COMMON_GLSL,
      "uniform ivec3 uShiftK;",
      "uniform sampler3D uVTex;",
      "out vec4 oF;",
      "out vec4 oS;",
      "void main() {",
      "   ivec2 st = ivec2(gl_FragCoord.xy);",
      "   ivec2 c = st + uShiftK.xy;",
      "   int K = uShiftK.z;",
      "   float F = 0.0, T = 1.0, D = 0.0;",
      "   uint lastRoom = 0u;",
      "   float dl = uCell.z * " + VIEW_PATH + " * 0.5;",
      "   float aIn = exp(-uSigma.y * dl);",
      "   oF = vec4(0.0, 1.0, 0.0, 0.0);",
      "   oS = vec4(0.0);",
      "   for (int k = uN.z - 1; k >= K; k--) {",
      "      vec3 p = cellPos(ivec3(c, k));",
      "      uint o = occ(ivec2(floor(p.xy)), int(floor(p.z)));",
      "      uint room = (o & 32u) != 0u ? max(1u, o >> 16) : 0u;",
      "      float V = texelFetch(uVTex, ivec3(st, k), 0).r;",
      "      if (room != lastRoom) { D = 0.0; lastRoom = room; }",
      "      if (room == 0u) {",
      "         float a = exp(-uSigma.x * exp(-max(p.z, 0.0) / uSigma.z) * dl);",
      "         F += T * V * (1.0 - a); T *= a;",
      "         if (k == K) { oF = vec4(F, T, 0.0, 0.0); oS = vec4(sqrt(clamp((1.0 - T) - F, 0.0, 1.0))); return; }",
      "         F += T * V * (1.0 - a); T *= a;",
      "      } else {",
      "         D += V * (1.0 - aIn);",
      "         if (k == K) { oF = vec4(F, T, V, D); oS = vec4(sqrt(clamp((1.0 - T) - F, 0.0, 1.0))); return; }",
      "         D += V * (1.0 - aIn);",
      "      }",
      "   }",
      "}",
      "");

   /** INTEGRATE_CS's minmax for one block of 8 slices: V's min and max and the highest room tag. */
   static final String MM_FRAG = String.join("\n",
      "#version 330",
      COMMON_GLSL,
      "uniform ivec3 uShiftK;",
      "uniform sampler3D uVTex;",
      "out vec4 oMM;",
      "void main() {",
      "   ivec2 st = ivec2(gl_FragCoord.xy);",
      "   int b = uShiftK.z;",
      "   float lo = 1.0, hi = 0.0, tag = 0.0;",
      "   for (int k = min(b * 8 + 7, uN.z - 1); k >= b * 8; k--) {",
      "      vec2 vt = texelFetch(uVTex, ivec3(st, k), 0).rg;",
      "      lo = min(lo, vt.r); hi = max(hi, vt.r); tag = max(tag, vt.g);",
      "   }",
      "   oMM = vec4(lo, hi, tag, 0.0);",
      "}",
      "");

   static final String VIS_CS = String.join("\n",
      "#version 430",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;",
      "layout(rg8, binding = 0) uniform writeonly image3D uV;",
      COMMON_GLSL,
      "void main() {",
      "   ivec3 g = ivec3(gl_GlobalInvocationID);",
      "   if (g.x >= uRegion.z || g.y >= uRegion.w || g.z >= uN.z) return;",
      "   ivec3 c = ivec3(uRegion.x + g.x, uRegion.y + g.y, g.z);",
      "   vec3 p = cellPos(c);",
      "   uint o = occ(ivec2(floor(p.xy)), int(floor(p.z)));",
      "   float tag = (o & 32u) != 0u ? float(1u + ((o >> 16) % 255u)) / 255.0 : 0.0;", // the room (0 outdoors) for the march
      "   imageStore(uV, ivec3(imod(c.x, uN.x), imod(c.y, uN.y), c.z), vec4(sunVis(p), tag, 0.0, 0.0));",
      "}",
      "");

   /**
    * One thread per column, from the top of the haze down. R: the outdoor haze's inscatter from the top to the cell (room
    * cells add none: the haze stays outside); G: its transmittance; B: the key light's visibility where the cell is in a
    * room (the sunlit patches); A: the room dust's inscatter from the cell up to where the column leaves the cell's room
    * (reset at every change of room: a pixel sees the dust of its own room only, not that of a cut-away room in front of
    * it or of the hidden floor above).
    */
   static final String INTEGRATE_CS = String.join("\n",
      "#version 430",
      "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
      "layout(rg8, binding = 0) uniform readonly image3D uV;",
      "layout(rgba8, binding = 2) uniform writeonly image3D uMM;",
      "layout(rgba16f, binding = 1) uniform writeonly image3D uF;",
      "layout(r8, binding = 3) uniform writeonly image3D uS;", // the fog shade's air the sun misses, sqrt((1 - T) - F): one byte a cell
      COMMON_GLSL,
      "void main() {",
      "   ivec2 g = ivec2(gl_GlobalInvocationID.xy);",
      "   if (g.x >= uRegion.z || g.y >= uRegion.w) return;",
      "   ivec2 c = ivec2(uRegion.x + g.x, uRegion.y + g.y);",
      "   ivec2 st = ivec2(imod(c.x, uN.x), imod(c.y, uN.y));",
      "   float F = 0.0, T = 1.0, D = 0.0, mmLo = 1.0, mmHi = 0.0, mmTag = 0.0;",
      "   uint lastRoom = 0u;",
      "   float dl = uCell.z * " + VIEW_PATH + " * 0.5;", // half a slice of view ray, squares
      "   float aIn = exp(-uSigma.y * dl);",
      "   for (int k = uN.z - 1; k >= 0; k--) {",
      "      vec3 p = cellPos(ivec3(c, k));",
      "      uint o = occ(ivec2(floor(p.xy)), int(floor(p.z)));",
      "      uint room = (o & 32u) != 0u ? max(1u, o >> 16) : 0u;",
      "      vec2 vt = imageLoad(uV, ivec3(st, k)).rg;",
      "      float V = vt.r;",
      "      if ((k & 7) == 7 || k == uN.z - 1) { mmLo = 1.0; mmHi = 0.0; mmTag = 0.0; }",
      "      mmLo = min(mmLo, V); mmHi = max(mmHi, V); mmTag = max(mmTag, vt.g);",
      "      if ((k & 7) == 0) imageStore(uMM, ivec3(st, k >> 3), vec4(mmLo, mmHi, mmTag, 0.0));",
      "      if (room != lastRoom) { D = 0.0; lastRoom = room; }",
      "      if (room == 0u) {",
      "         float a = exp(-uSigma.x * exp(-max(p.z, 0.0) / uSigma.z) * dl);",
      "         F += T * V * (1.0 - a); T *= a;",
      "         imageStore(uF, ivec3(st, k), vec4(F, T, 0.0, 0.0));",
      "         imageStore(uS, ivec3(st, k), vec4(sqrt(clamp((1.0 - T) - F, 0.0, 1.0))));",
      "         F += T * V * (1.0 - a); T *= a;",
      "      } else {",
      "         D += V * (1.0 - aIn);",
      "         imageStore(uF, ivec3(st, k), vec4(F, T, V, D));",
      "         imageStore(uS, ivec3(st, k), vec4(sqrt(clamp((1.0 - T) - F, 0.0, 1.0))));",
      "         D += V * (1.0 - aIn);",
      "      }",
      "   }",
      "}",
      "");

   // ------------------------------------------------------------------------------------------------ timing (dev)

   /** devGodRaysTiming: GL timestamps around named spans (the updates; the screen pass on / off), medians every 5 s. */
   static final class Timing {
      private static final int SLOTS = 64;
      private static int[] q;
      private static final boolean[] busy = new boolean[SLOTS];
      private static final String[] name = new String[SLOTS];
      private static int open = -1, next;
      private static final java.util.HashMap<String, ArrayList<Long>> all = new java.util.HashMap<>();
      private static long lastLog;
      static String suffix = ""; // devGodRaysDoorAlternate: ".d1" / ".d0", this frame's half

      static void begin(String n, boolean nested) {
         if (!Config.DEV_GOD_RAYS_TIMING) {
            return;
         }
         if (q == null) {
            q = new int[SLOTS * 2];
            for (int i = 0; i < q.length; i++) {
               q[i] = GL15.glGenQueries();
            }
         }
         collect();
         int s = next;
         if (busy[s]) {
            open = -1;
            return;
         }
         next = (next + 1) % SLOTS;
         GL33.glQueryCounter(q[s * 2], GL33.GL_TIMESTAMP);
         name[s] = n + suffix;
         open = s;
      }

      static void end(String n) {
         if (!Config.DEV_GOD_RAYS_TIMING || open < 0 || q == null) {
            return;
         }
         GL33.glQueryCounter(q[open * 2 + 1], GL33.GL_TIMESTAMP);
         busy[open] = true;
         open = -1;
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
            StringBuilder sb = new StringBuilder("god rays timing:");
            for (java.util.Map.Entry<String, ArrayList<Long>> e : new java.util.TreeMap<>(all).entrySet()) {
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
            Log.info(sb.toString());
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ dump (dev)

   /** devGodRaysDumpAt: the world colour and depth, the occupancy and the frame's state for harness/godrays/rig.py. */
   private static void dump(Frame f, int fbo) {
      try {
         File dir = new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-godrays");
         dir.mkdirs();
         int[] vp = new int[4];
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
         int vw = vp[2], vh = vp[3];
         int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
         FloatBuffer db = BufferUtils.createFloatBuffer(vw * vh);
         GL11.glReadPixels(vp[0], vp[1], vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, db);
         ByteBuffer dbb = ByteBuffer.allocate(vw * vh * 4).order(ByteOrder.LITTLE_ENDIAN);
         dbb.asFloatBuffer().put(db);
         Files.write(new File(dir, f.dumpTag + "-depth.bin").toPath(), dbb.array());
         ByteBuffer cb = BufferUtils.createByteBuffer(vw * vh * 4);
         GL11.glReadPixels(vp[0], vp[1], vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cb);
         byte[] ca = new byte[vw * vh * 4];
         cb.get(ca);
         Files.write(new File(dir, f.dumpTag + "-color.bin").toPath(), ca);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
         IntBuffer ob = BufferUtils.createIntBuffer(OCC_N * OCC_N * OCC_L);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, Gl.occTex);
         GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, ob);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         ByteBuffer obb = ByteBuffer.allocate(OCC_N * OCC_N * OCC_L * 4).order(ByteOrder.LITTLE_ENDIAN);
         obb.asIntBuffer().put(ob);
         Files.write(new File(dir, f.dumpTag + "-occ.bin").toPath(), obb.array());
         float[] vpf = {vp[0], vp[1], vw, vh};
         float[] m = new float[6];
         f.view.mapping(vpf, m);
         StringBuilder sb = new StringBuilder();
         sb.append("w=").append(vw).append("\nh=").append(vh).append("\nvx=").append(vp[0]).append("\nvy=").append(vp[1]).append('\n');
         sb.append("kA=").append(m[0]).append("\ncA=").append(m[1]).append("\nkB=").append(m[2]).append("\ncB=").append(m[3]).append("\nkC=").append(m[4]).append("\ncC=").append(m[5]).append('\n');
         sb.append("ox=").append(f.view.ox).append("\noy=").append(f.view.oy).append("\nzoom=").append(f.view.zoom).append("\nts=").append(f.view.ts).append('\n');
         sb.append("refX=").append(f.refX).append("\nrefY=").append(f.refY).append("\noccZ0=").append(f.occZ0).append("\nmaxTop=").append(f.maxTop).append('\n');
         sb.append("cu=").append(f.cu).append("\ncv=").append(f.cv).append("\ncz=").append(f.cz).append("\nzLo=").append(f.zLo).append('\n');
         sb.append("nx=").append(Gl.nx).append("\nny=").append(Gl.ny).append("\nnz=").append(Gl.nz).append("\ni0=").append(f.i0).append("\nj0=").append(f.j0).append('\n');
         sb.append("lx=").append(f.lx).append("\nly=").append(f.ly).append("\nlz=").append(f.lz).append("\nslope=").append(f.slope).append('\n');
         sb.append("sigmaOut=").append(f.sigmaOut).append("\nsigmaIn=").append(f.sigmaIn).append("\nhazeH=").append(f.hazeH).append("\npatch=").append(f.patch).append('\n');
         sb.append("r=").append(f.r).append("\ng=").append(f.g).append("\nb=").append(f.b).append("\nphase=").append(phase).append("\nbody=").append(body).append('\n');
         sb.append("hour=").append(GameTime.getInstance() != null ? GameTime.getInstance().getTimeOfDay() : -1F).append('\n');
         IsoPlayer p = IsoPlayer.getInstance();
         if (p != null) {
            sb.append("px=").append(p.getX()).append("\npy=").append(p.getY()).append("\npz=").append(p.getZ()).append('\n');
         }
         // the light volume target and the god ray buffer as the composite reads them (this frame's)
         if (Gl.apTex != 0 && Gl.apOn) {
            FloatBuffer ab = BufferUtils.createFloatBuffer(Gl.apW * Gl.apH * 2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Gl.apTex);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG, GL11.GL_FLOAT, ab);
            ByteBuffer abb = ByteBuffer.allocate(Gl.apW * Gl.apH * 8).order(ByteOrder.LITTLE_ENDIAN);
            abb.asFloatBuffer().put(ab);
            Files.write(new File(dir, f.dumpTag + "-ap.bin").toPath(), abb.array());
            sb.append("apW=").append(Gl.apW).append("\napH=").append(Gl.apH).append("\napBounds=").append(Gl.apBounds[0]).append(',').append(Gl.apBounds[1]).append(',')
               .append(Gl.apBounds[2]).append(',').append(Gl.apBounds[3]).append('\n');
         }
         if (Gl.lowTex != 0) {
            ByteBuffer lb = BufferUtils.createByteBuffer(Gl.lowW * Gl.lowH * 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Gl.lowTex);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lb);
            byte[] la = new byte[Gl.lowW * Gl.lowH * 4];
            lb.get(la);
            Files.write(new File(dir, f.dumpTag + "-low.bin").toPath(), la);
            sb.append("lowW=").append(Gl.lowW).append("\nlowH=").append(Gl.lowH).append('\n');
         }
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         sb.append("depthW=").append(Gl.depthW).append("\ndepthH=").append(Gl.depthH).append("\ndepthTex=").append(Gl.depthTex).append('\n');
         sb.append("lights=").append(f.lights).append("\nlightsFroxel=").append(f.froxelLights).append('\n');
         for (int i = 0; i < f.lights; i++) {
            sb.append("la").append(i).append('=').append(f.la[i * 4]).append(',').append(f.la[i * 4 + 1]).append(',').append(f.la[i * 4 + 2]).append(',').append(f.la[i * 4 + 3]).append('\n');
            sb.append("lb").append(i).append('=').append(f.lb[i * 4]).append(',').append(f.lb[i * 4 + 1]).append(',').append(f.lb[i * 4 + 2]).append(',').append(f.lb[i * 4 + 3]).append('\n');
            sb.append("lc").append(i).append('=').append(f.lc[i * 4]).append(',').append(f.lc[i * 4 + 1]).append(',').append(f.lc[i * 4 + 2]).append(',').append(f.lc[i * 4 + 3]).append('\n');
         }
         sb.append("prismOrgX=").append(f.prismOrgX).append("\nprismOrgY=").append(f.prismOrgY).append('\n');
         sb.append("prisms=").append(f.prisms).append("\nsigmaInScale=").append(0.6123724F).append("\ndustGain=").append(f.dustGain).append('\n');
         if (f.prisms > 0) {
            ByteBuffer pb = ByteBuffer.allocate(f.prisms * PRISM_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN);
            pb.asFloatBuffer().put(f.prismData, 0, f.prisms * PRISM_FLOATS);
            Files.write(new File(dir, f.dumpTag + "-prisms.bin").toPath(), pb.array());
            ByteBuffer qb = ByteBuffer.allocate(f.prisms * PLANE_FLOATS * 4).order(ByteOrder.LITTLE_ENDIAN);
            qb.asFloatBuffer().put(f.planeData, 0, f.prisms * PLANE_FLOATS);
            Files.write(new File(dir, f.dumpTag + "-planes.bin").toPath(), qb.array());
         }
         Files.writeString(new File(dir, f.dumpTag + "-view.txt").toPath(), sb.toString());
         Log.info("god rays: dump " + f.dumpTag + " " + vw + "x" + vh + " to " + dir);
      } catch (Throwable t) {
         Log.warn("god rays: dump failed: " + t);
      }
   }

   // ------------------------------------------------------------------------------------------------ rig: find=sunwindow

   /**
    * Harness route start with {@code find=sunwindow}: the player into the room within 80 squares whose windows let the key
    * light in the most (a window edge with the room on one side, the outside on the other, the light coming from outside),
    * three squares in from the best window. Logged, so a run can pin the spot with start=.
    */
   public static int[] findSunWindow(IsoPlayer p) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null || p == null) {
         return null;
      }
      keyLight();
      float lx = light[0], ly = light[1];
      int px = p.getXi(), py = p.getYi();
      java.util.HashMap<Object, int[]> rooms = new java.util.HashMap<>();
      int[] best = null;
      Object bestRoom = null;
      for (int z = 0; z <= 1; z++) {
         for (int y = py - 80; y <= py + 80; y++) {
            for (int x = px - 80; x <= px + 80; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               for (int e = 0; e < 2; e++) {
                  boolean north = e == 0;
                  if (!(sq.has(north ? IsoFlagType.WindowN : IsoFlagType.WindowW) || sq.has(north ? IsoFlagType.windowN : IsoFlagType.windowW))) {
                     continue;
                  }
                  if (edge(sq, north) != E_WINDOW) {
                     continue;
                  }
                  IsoGridSquare other = cell.getGridSquare(north ? x : x - 1, north ? y - 1 : y, z);
                  boolean inHere = sq.getRoom() != null, inOther = other != null && other.getRoom() != null;
                  if (inHere == inOther) {
                     continue;
                  }
                  // the light must come from the outside side: towards -x / -y if the room is on this square's side
                  float along = north ? ly : lx;
                  boolean ok = inHere ? along < -0.25F : along > 0.25F;
                  if (!ok) {
                     continue;
                  }
                  IsoGridSquare in = inHere ? sq : other;
                  Object room = in.getRoom();
                  int[] r = rooms.computeIfAbsent(room, k -> new int[4]);
                  r[0]++;
                  if (r[0] == 1) {
                     // the spot: three squares into the room from the window
                     int ix = in.x, iy = in.y;
                     int sx = north ? 0 : inHere ? 1 : -1, sy = north ? inHere ? 1 : -1 : 0;
                     for (int k = 0; k < 3; k++) {
                        IsoGridSquare nsq = cell.getGridSquare(ix + sx, iy + sy, z);
                        if (nsq == null || nsq.getRoom() != room) {
                           break;
                        }
                        ix += sx;
                        iy += sy;
                     }
                     r[1] = ix;
                     r[2] = iy;
                     r[3] = z;
                  }
                  if (best == null || r[0] > best[0] || r[0] == best[0] && Math.abs(r[1] - px) + Math.abs(r[2] - py) < Math.abs(best[1] - px) + Math.abs(best[2] - py)) {
                     best = r;
                     bestRoom = room;
                  }
               }
            }
         }
      }
      if (best == null) {
         Log.warn("god rays: find=sunwindow: no room with a sunlit window within 80 squares of " + px + "," + py + " (light " + lx + "," + ly + ")");
         return null;
      }
      IsoGridSquare target = cell.getGridSquare(best[1], best[2], best[3]);
      if (target == null) {
         return null;
      }
      p.setX(best[1] + 0.5F);
      p.setY(best[2] + 0.5F);
      p.setZ(best[3]);
      p.setLastX(best[1] + 0.5F);
      p.setLastY(best[2] + 0.5F);
      p.setLastZ(best[3]);
      p.setCurrent(target);
      Log.info("god rays: find=sunwindow: " + best[0] + " sunlit windows in room " + (bestRoom instanceof zombie.iso.areas.IsoRoom ir ? ir.getName() : "?") + ", player to "
         + best[1] + "," + best[2] + "," + best[3] + " (light " + lx + "," + ly + "," + light[2] + ")");
      return new int[] {best[1], best[2], best[3]};
   }

   /**
    * Harness route start with {@code find=forest}: the player to the middle of the loaded 3 x 3 chunks with the most trees
    * (a free square: no tree, no wall), for the canopy's shafts. Logged.
    */
   public static int[] findForest(IsoPlayer p) {
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null || p == null) {
         return null;
      }
      int pwx = p.getXi() >> 3, pwy = p.getYi() >> 3;
      int R = 12;
      int[][] count = new int[2 * R + 1][2 * R + 1];
      for (int dy = -R; dy <= R; dy++) {
         for (int dx = -R; dx <= R; dx++) {
            IsoChunk c = cell.getChunk(pwx + dx, pwy + dy);
            if (c == null) {
               continue;
            }
            int n = 0;
            for (int y = 0; y < 8; y++) {
               for (int x = 0; x < 8; x++) {
                  IsoGridSquare sq = c.getGridSquare(x, y, 0);
                  if (sq != null && sq.getTree() != null) {
                     n++;
                  }
               }
            }
            count[dy + R][dx + R] = n;
         }
      }
      int best = -1, bx = 0, by = 0;
      for (int dy = -R + 1; dy <= R - 1; dy++) {
         for (int dx = -R + 1; dx <= R - 1; dx++) {
            int n = 0;
            for (int j = -1; j <= 1; j++) {
               for (int i = -1; i <= 1; i++) {
                  n += count[dy + j + R][dx + i + R];
               }
            }
            if (n > best) {
               best = n;
               bx = dx;
               by = dy;
            }
         }
      }
      if (best <= 0) {
         Log.warn("god rays: find=forest: no trees in the loaded chunks round " + p.getXi() + "," + p.getYi());
         return null;
      }
      int cx = (pwx + bx) * 8 + 4, cy = (pwy + by) * 8 + 4;
      for (int r = 0; r < 6; r++) {
         for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, 0);
               if (sq != null && sq.getTree() == null && sq.getFloor() != null && !sq.has(IsoFlagType.solid) && !sq.has(IsoFlagType.solidtrans)) {
                  p.setX(x + 0.5F);
                  p.setY(y + 0.5F);
                  p.setZ(0);
                  p.setLastX(x + 0.5F);
                  p.setLastY(y + 0.5F);
                  p.setLastZ(0);
                  p.setCurrent(sq);
                  Log.info("god rays: find=forest: " + best + " trees in the 3x3 chunks round " + x + "," + y + ", player moved there");
                  return new int[] {x, y, 0};
               }
            }
         }
      }
      return null;
   }

   // ------------------------------------------------------------------------------------------------ helpers

   private static double floorMod(double v, double p) {
      double r = v % p;
      return r < 0.0 ? r + p : r;
   }

   private static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   private static double clamp(double v, double lo, double hi) {
      return v < lo ? lo : v > hi ? hi : v;
   }

   private static float smooth(float e0, float e1, float x) {
      float t = clamp01((x - e0) / (e1 - e0));
      return t * t * (3F - 2F * t);
   }
}
