package pzopt;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import zombie.GameTime;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.objects.IsoTree;
import zombie.iso.weather.ClimateManager;

/**
 * Ground cover by season (2026-10-24; key {@code groundScatter}, the "Ground and grass" subcategory): leaves under the trees
 * and banked in the gutters and against walls and fences in autumn, flowers in the spring grass, litter by the roads,
 * twigs and needles in the woods, frost in the shade on cold mornings. Baked into the chunk textures with the floor, so the
 * frame pays nothing for it.
 *
 * <h2>Rules (Horizon Zero Dawn's GPU placement, van Muijden GDC 2017, on the CPU at bake time)</h2>
 * A density per kind at every point of the floor, from rules on what the game already knows:
 * <ul>
 *   <li>trees: a Gaussian crown per tree (radius from its size), shifted downwind of the trunk, per species colour (red
 *       maple red, linden / birch / yellowwood / redbud / silverbell yellow, dogwood purple-red, hawthorn orange; pine,
 *       hemlock and holly drop needles, not leaves);</li>
 *   <li>wind: the drift of the week (Kentucky's south-westerlies turned by a hash of the week), which puts the leaves
 *       downwind of the crowns, in the gutter on the far side of the road and against the upwind face of walls and
 *       fences;</li>
 *   <li>road masks: gutters (a road square's edge against a curb, a sidewalk or grass), sidewalks, road centres (swept by
 *       wind and traffic: few);</li>
 *   <li>the zone (forest, farm, town), the months since the outbreak (nobody sweeps: leaves and litter build up), the month
 *       (leaf fall September to December, old matted leaves in winter, flowers April to June), the temperature and the
 *       shade (frost).</li>
 * </ul>
 * Deterministic and locally stable: candidates come from a world-anchored set (jittered grid or a progressive blue-noise
 * tile, {@code groundCoverPlacement}), each kept when its own threshold is under the density there (HZD's dithering), so
 * the same leaf lies in the same place every visit and chunk borders are invisible.
 *
 * <h2>Drawing</h2>
 * Into the chunk texture right after the floor tiles and before the snow, the blood and everything standing on the floor
 * (the depth test off, as stock draws blood: nothing is written to the depth, so objects keep the floor's): one instanced
 * draw per texture page. Procedural shapes (leaf, petal flower, twig, needles, frost) in the fragment shader, and the
 * game's own decals for leaf clumps and litter ({@code d_floorleaves_1}, {@code d_trash_1}, {@code d_generic_1}). Lit by the
 * square's four corner lights at the instance's spot, as the floor is (white with pixelLight: the composite lights it).
 * A season step re-bakes the levels that hold cover a few per frame (no single-frame change).
 */
public final class GroundCover {
   private GroundCover() {
   }

   private static volatile boolean failed = CoreGl.legacyMac();
   public static long bakes, bakesSkipped, builds, instancesDrawn, rebakesIssued, buildNs, hoodNs, cornersNs, buildMinNs = Long.MAX_VALUE, buildLateNs, buildLate;

   public static boolean on() {
      return Ground.on(Ground.K_SCATTER) && Overrides.enabled() && !failed;
   }

   /** The bake hook (ground-common's floor pass: after the floor tiles, before the snow, the blood and the objects). */
   static final GroundBake.FloorPass PASS = new GroundBake.FloorPass() {
      @Override
      public String id() {
         return "groundCover";
      }

      @Override
      public boolean active() {
         frame();
         return on();
      }

      @Override
      public void draw(IsoChunk c, int level, int playerIndex) {
         bake(c, level, playerIndex);
      }
   };

   private static boolean registered;

   static {
      register(); // loaded by Ground.FEATURES the first frame a ground key is on
      // tracks crush the cover: a chunk whose ground state changed re-bakes (the shader reads the state: prints in the frost,
      // leaves pressed into the ruts)
      Ground.onFrame(p -> {
         if (on() && Ground.on(Ground.K_TRACKS)) {
            GroundState.wantFullRebakes(); // changed chunks re-bake whole (the cover reads the state), once, instead of the decal re-bake
            GroundCover.tracksRebakes++;
         }
      });
   }

   public static long tracksRebakes;

   /** World entry (NoLoadingScreen): the floor pass joins ground-common's bake. */
   public static void register() {
      if (!registered) {
         registered = true;
         GroundBake.register(PASS);
      }
   }

   /** devGroundCycle's current name when it is one of {@code names}, else null. */
   static String cycled(String... names) {
      String v = Ground.variant();
      if (v != null) {
         for (String n : names) {
            if (n.equals(v)) {
               return v;
            }
         }
      }
      return null;
   }

   static String placement() {
      String v = cycled("jitter", "bluenoise");
      return v != null ? v : Config.GROUND_COVER_PLACEMENT;
   }

   static String assets() {
      String v = cycled("mixed", "procedural", "sprites");
      return v != null ? v : Config.GROUND_COVER_ASSETS;
   }

   public static String stats() {
      return "ground cover: bakes=" + bakes + " skipped=" + bakesSkipped + " builds=" + builds + " build_us=" + (builds > 0 ? buildNs / 1000 / builds : 0) + " (hood " + (builds > 0 ? hoodNs / 1000 / builds : 0) + " corners " + (builds > 0 ? cornersNs / 1000 / builds : 0) + ") build_min_us=" + (builds > 0 ? buildMinNs / 1000 : 0) + " build_late_us=" + (buildLate > 0 ? buildLateNs / 1000 / buildLate : 0)
            + " async=" + asyncBuilds + " sync=" + syncBuilds + " submit_us=" + (submits > 0 ? submitNs / 1000 / submits : 0) + " gathers=" + gathers + " gather_us=" + (gathers > 0 ? gatherNs / 1000 / gathers : 0) + " land_rebakes=" + landRebakes + " stale_draws=" + staleDraws + " dropped=" + droppedBuilds
            + " instances=" + instancesDrawn + " rebakes=" + rebakesIssued + " tracksWantFrames=" + tracksRebakes + " step=" + step + " frostStep=" + frostStep + " month=" + season.month
            + String.format(java.util.Locale.ROOT, " fall=%.2f flowers=%.2f frost=%.2f tmin=%.1f", season.leafFall, season.flowers, season.frost, season.tMin)
            + (failed ? " (failed)" : "");
   }

   // ------------------------------------------------------------------------------------------------ hashing (world-anchored)

   static int mix(int x, int y, int s) {
      return GroundHash.hash2(x, y, GroundHash.SEED_SCATTER + s * 0x9e3779b9);
   }

   static float hf(int x, int y, int s) {
      return GroundHash.u01(mix(x, y, s));
   }

   /** Smooth value noise of the world (squares), period-free. */
   static float vnoise(float x, float y, int s) {
      int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
      float fx = x - ix, fy = y - iy;
      fx = fx * fx * (3F - 2F * fx);
      fy = fy * fy * (3F - 2F * fy);
      float a = hf(ix, iy, s), b = hf(ix + 1, iy, s), c = hf(ix, iy + 1, s), d = hf(ix + 1, iy + 1, s);
      return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
   }

   static float fbm(float x, float y, int s) {
      return 0.5F * vnoise(x, y, s) + 0.3F * vnoise(x * 2.03F, y * 2.03F, s + 1) + 0.2F * vnoise(x * 4.1F, y * 4.1F, s + 2);
   }

   static float smooth(float a, float b, float x) {
      float t = Math.max(0F, Math.min(1F, (x - a) / (b - a)));
      return t * t * (3F - 2F * t);
   }

   // ------------------------------------------------------------------------------------------------ the season

   /** What the date, the hour and the weather say, once a frame (cheap) and stepped for the bakes. */
   static final class Season {
      int month, day, year;
      float hour, tempC, tMin = 99F, cloud, rain;
      float leafFall; // 0..1 fresh leaves on the ground
      float leafAge; // 0 fresh colours .. 1 old, matted, brown
      float flowers; // 0..1
      float accum; // 0..1 months without anybody sweeping
      float frost; // 0..1 the morning's frost potential (night minimum, clear sky)
      float driftX = 0.70710678F, driftY = -0.70710678F;
      float sunX, sunY, sunZ = 1F; // the sun now (Sky)
      float sunUpHours; // hours since sunrise (0 before)
      String placement = "bluenoise", assets = "mixed"; // a builder's settings (a snapshot's own; the live season reads Config)
      boolean frostOn = true;
      int density = 100;
      boolean live;

      String placement() {
         return this.live ? GroundCover.placement() : this.placement;
      }

      String assets() {
         return this.live ? GroundCover.assets() : this.assets;
      }

      boolean frostOn() {
         return this.live ? Config.GROUND_COVER_FROST : this.frostOn;
      }

      int density() {
         return this.live ? Config.GROUND_SCATTER_DENSITY_PCT : this.density;
      }
   }

   static final Season season = newLive();

   private static Season newLive() {
      Season s = new Season();
      s.live = true;
      return s;
   }
   /** The season a builder reads: the live one on the game thread, a snapshot on the cover worker. */
   private static final ThreadLocal<Season> CUR = ThreadLocal.withInitial(() -> season);

   static Season S() {
      return CUR.get();
   }

   static Season snapshot() {
      Season a = season, b = new Season();
      b.month = a.month;
      b.day = a.day;
      b.year = a.year;
      b.hour = a.hour;
      b.tempC = a.tempC;
      b.tMin = a.tMin;
      b.cloud = a.cloud;
      b.rain = a.rain;
      b.leafFall = a.leafFall;
      b.leafAge = a.leafAge;
      b.flowers = a.flowers;
      b.accum = a.accum;
      b.frost = a.frost;
      b.driftX = a.driftX;
      b.driftY = a.driftY;
      b.sunX = a.sunX;
      b.sunY = a.sunY;
      b.sunZ = a.sunZ;
      b.sunUpHours = a.sunUpHours;
      b.placement = placement();
      b.assets = assets();
      b.frostOn = Config.GROUND_COVER_FROST;
      b.density = Config.GROUND_SCATTER_DENSITY_PCT;
      return b;
   }
   static int step = Integer.MIN_VALUE, frostStep = Integer.MIN_VALUE;
   private static float lastTempHour = -1F;

   /** Game thread, once a frame (FBORenderCell before the bakes): the season's numbers, and the re-bakes of a new step. */
   public static void frame() {
      if (!on() && !Glints.on()) {
         return;
      }
      try {
         updateSeason();
         if (on()) {
            land();
            rebakeStep();
            prefetch();
         }
         sweep();
      } catch (RuntimeException e) {
         failed = true;
         Log.warn("ground cover: season update failed, ground cover off: " + e);
      }
   }

   static void updateSeason() {
      Season s = season;
      GameTime gt = GameTime.getInstance();
      ClimateManager cm = ClimateManager.getInstance();
      if (gt == null || cm == null) {
         return;
      }
      s.year = gt.getYear();
      s.month = gt.getMonth();
      s.day = gt.getDay();
      s.hour = gt.getTimeOfDay();
      if (!Config.DEV_SKY_DATE.isEmpty()) {
         try {
            java.time.LocalDate d = java.time.LocalDate.parse(Config.DEV_SKY_DATE.trim());
            s.year = d.getYear();
            s.month = d.getMonthValue() - 1;
            s.day = d.getDayOfMonth() - 1;
         } catch (RuntimeException e) {
            // keep the game's date
         }
      }
      s.tempC = cm.getTemperature();
      if (Config.DEV_GROUND_TEMP > -100) {
         s.tempC = Config.DEV_GROUND_TEMP;
      }
      s.cloud = Math.max(0F, Math.min(1F, cm.getCloudIntensity()));
      s.rain = Math.max(0F, Math.min(1F, cm.getRainIntensity()));
      // the night's minimum: reset each evening at 18 h, lowered through the night and the morning
      if (lastTempHour >= 0F && lastTempHour < 18F && s.hour >= 18F) {
         s.tMin = s.tempC;
      }
      s.tMin = Math.min(s.tMin, s.tempC);
      if (s.tMin > 90F) {
         s.tMin = s.tempC;
      }
      lastTempHour = s.hour;
      float doy = s.month * 30.44F + s.day; // day of the year, 0 = Jan 1
      curves(s, doy);
      double months = gt.getWorldAgeHours() / 24.0 / 30.0;
      s.accum = (float)Math.min(1.0, 0.25 + months / 6.0);
      // the week's drift: Kentucky's prevailing south-westerlies (toward the north-east), turned by up to +-50 degrees a week
      int week = (s.year * 366 + (int)doy) / 7;
      float ang = (float)Math.toRadians(-45.0 + (hf(week, 7, 913) - 0.5F) * 100F);
      s.driftX = (float)Math.cos(ang);
      s.driftY = (float)Math.sin(ang);
      Sky.update(-1F);
      // the key light: the sun, or at night the moon (its shade decides where the moonlit frost glints)
      boolean moon = Sky.sun[2] <= 0.02 && Sky.moon[2] > 0.05;
      s.sunX = (float)(moon ? Sky.moon[0] : Sky.sun[0]);
      s.sunY = (float)(moon ? Sky.moon[1] : Sky.sun[1]);
      s.sunZ = (float)(moon ? Sky.moon[2] : Sky.sun[2]);
      float dawn = 6.0F, dusk = 19.0F;
      if (cm.getSeason() != null) {
         dawn = cm.getSeason().getDawn();
         dusk = cm.getSeason().getDusk();
      }
      s.sunUpHours = s.hour > dawn && s.hour < dusk ? s.hour - dawn : 0F;
      // radiative frost: a cold, clear night (the night's minimum under ~2 C), gone above ~7 C or in rain
      float f = smooth(3.5F, -1.5F, s.tMin) * (1F - 0.8F * smooth(0.5F, 0.9F, s.cloud)) * smooth(8F, 4F, s.tempC) * (1F - s.rain);
      if (Config.DEV_FROST_FORCE) {
         f = 1F;
      }
      s.frost = Math.max(0F, Math.min(1F, f));
   }

   /** The month curves (pure: GroundCoverTest): leaf fall, the fallen leaves' age, flowers, from the day of the year. */
   static void curves(Season s, float doy) {
      // leaf fall: none in summer, from mid September, peak late October / November, the ground's leaves decaying by spring
      float fall = smooth(255F, 290F, doy) * (1F - smooth(335F, 360F, doy) * 0.35F);
      float winterLeft = doy < 120F ? 0.45F * (1F - smooth(60F, 125F, doy)) : 0F;
      s.leafFall = Math.max(fall, winterLeft) + 0.06F; // a few all year (a dry summer leaf, last year's)
      s.leafAge = doy >= 255F ? smooth(290F, 345F, doy) : doy < 130F ? 1F : 0.5F;
      s.flowers = smooth(90F, 110F, doy) * (1F - smooth(165F, 190F, doy));
   }

   /** The bake key of the season (a new value re-bakes the cover: weekly) and of the frost (every 15 game minutes while it lasts). */
   static int seasonKey() {
      Season s = season;
      int doy = (int)(s.month * 30.44F + s.day);
      return s.year * 64 + doy / 7;
   }

   static int frostKey() {
      Season s = season;
      if (s.frost <= 0.01F) {
         return 0;
      }
      return 1 + (int)(s.frost * 8F) * 1000 + (int)(s.hour * 4F);
   }

   // ------------------------------------------------------------------------------------------------ re-bakes of a step

   private static final ArrayList<IsoChunk> pending = new ArrayList<>();
   private static final ArrayList<int[]> pendingLevels = new ArrayList<>();
   private static long lastRebakeMs;

   /** A landed build's re-bake, through the step's nearest-first, budgeted list. */
   static void queueRebake(IsoChunk c) {
      for (int i = 0; i < pending.size(); i++) {
         if (pending.get(i) == c && pendingLevels.get(i)[0] == 0) {
            return;
         }
      }
      pending.add(c);
      pendingLevels.add(new int[] {0});
   }

   private static void rebakeStep() {
      int sk = seasonKey(), fk = frostKey();
      boolean seasonChanged = step != Integer.MIN_VALUE && sk != step;
      boolean frostChanged = frostStep != Integer.MIN_VALUE && fk != frostStep;
      boolean settings = lastSettings != Integer.MIN_VALUE && settingsKey() != lastSettings;
      step = sk;
      frostStep = fk;
      lastSettings = settingsKey();
      if (seasonChanged || frostChanged || settings) {
         // every chunk level that holds (or can now hold) cover is re-baked, a few a frame, nearest first
         pending.clear();
         pendingLevels.clear();
         forEachLoaded(c -> {
            ChunkCover cc = slot(c);
            if (cc == null) {
               return;
            }
            for (int li = 0; li < 64; li++) {
               LevelCover lc = cc.levels[li];
               if (lc == null) {
                  continue;
               }
               boolean need = settings || seasonChanged && lc.n > 0 || frostChanged && (lc.frostN > 0 || fk != 0 && lc.outdoor);
               if (need && li == 32 && async()) {
                  cc.baked = true; // the prefetch rebuilds it; its landing re-bakes
                  continue;
               }
               if (need) {
                  pending.add(c);
                  pendingLevels.add(new int[] {li - 32});
               }
            }
         });
      }
      if (pending.isEmpty()) {
         return;
      }
      // spread: a chunk level every 1000 / rate ms (default 4 a frame at most)
      long now = System.currentTimeMillis();
      int budget = Math.max(1, Config.GROUND_REBAKE_PER_FRAME);
      if (now - lastRebakeMs < 16) {
         return;
      }
      lastRebakeMs = now;
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      for (int b = 0; b < budget && !pending.isEmpty(); b++) {
         int best = 0;
         float bd = Float.MAX_VALUE;
         for (int i = 0; i < pending.size(); i++) {
            IsoChunk c = pending.get(i);
            float dx = c.wx * 8 + 4 - cx, dy = c.wy * 8 + 4 - cy;
            float d = dx * dx + dy * dy;
            if (d < bd) {
               bd = d;
               best = i;
            }
         }
         IsoChunk c = pending.remove(best);
         int level = pendingLevels.remove(best)[0];
         if (level == 0) {
            GroundBake.rebakeChunk(c.wx, c.wy, "ground cover step", true);
            rebakesIssued++;
         }
      }
   }

   private static int lastSettings = Integer.MIN_VALUE;

   static int settingsKey() {
      return Config.GROUND_SCATTER_DENSITY_PCT * 31 + placement().hashCode() * 7 + assets().hashCode() * 13
         + (Config.GROUND_COVER_FROST ? 1 : 0) + (Config.GROUND_SCATTER ? 2 : 0) + Config.DEV_GROUND_COVER_VIEW * 101;
   }

   // ------------------------------------------------------------------------------------------------ the squares around a chunk

   static final int M_NONE = 0, M_GRASS = 1, M_DIRT = 2, M_ROAD = 3, M_WALK = 4, M_BUILT = 5, M_GRAVEL = 6;

   /** Material of a square's floor (interim, by sprite name; pzopt.GroundTiles once ground-common's API is merged). */
   static int material(IsoGridSquare sq) {
      if (sq == null) {
         return M_NONE;
      }
      IsoObject floor = sq.getFloor();
      if (floor == null || floor.sprite == null || floor.sprite.name == null) {
         return M_NONE;
      }
      if (!sq.isOutside() || sq.has(IsoFlagType.water)) {
         return M_NONE;
      }
      String n = floor.sprite.name;
      int code = GroundTiles.square(sq);
      if ((code & GroundTiles.F_EXCLUDED) != 0) {
         return M_NONE; // farmland, dug soil, water
      }
      switch (GroundTiles.material(code)) {
         case GroundTiles.GRASS:
         case GroundTiles.GRASS_OVERLAY:
         case GroundTiles.VEGETATION:
            return M_GRASS;
         case GroundTiles.GRAVEL:
            return M_GRAVEL;
         case GroundTiles.NONE:
            break;
         default:
            return M_DIRT; // dirt, sand, clay, dark dirt (snow: the game's)
      }
      Integer cached = NAME_MAT.get(floor.sprite);
      if (cached != null) {
         return cached;
      }
      int m = nameMaterial(n);
      NAME_MAT.put(floor.sprite, m);
      return m;
   }

   /** Paved / built floors by sprite name, memoised per sprite (the gather runs on the game thread). */
   private static final java.util.IdentityHashMap<Object, Integer> NAME_MAT = new java.util.IdentityHashMap<>();

   private static int nameMaterial(String n) {
      if (n.startsWith("blends_street_01_") || n.startsWith("floors_exterior_street")) {
         int i = index(n);
         // blends_street_01: asphalt rows; floors_exterior_street: sidewalk / pavement
         return n.startsWith("floors_exterior_street") ? M_WALK : i >= 64 && i < 80 ? M_WALK : M_ROAD;
      }
      if (n.startsWith("floors_exterior_tilesandstone") || n.contains("sidewalk") || n.startsWith("street_trafficlines")) {
         return M_WALK;
      }
      if (n.startsWith("carpentry_") || n.startsWith("floors_interior") || n.startsWith("constructedobjects")) {
         return M_BUILT;
      }
      return M_BUILT;
   }

   private static int index(String n) {
      int u = n.lastIndexOf('_');
      try {
         return Integer.parseInt(n.substring(u + 1));
      } catch (RuntimeException e) {
         return 0;
      }
   }

   public static boolean isRoad(IsoGridSquare sq) {
      int m = material(sq);
      return m == M_ROAD || m == M_WALK;
   }

   /** One tree near a chunk: crown centre (squares), crown radius, height (squares), species colour index. */
   static final class Tree {
      float x, y, r, h;
      int species; // 0 deciduous yellow, 1 red, 2 orange, 3 purple-red, 4 evergreen (needles)
   }

   private static final java.util.HashMap<String, Integer> SPECIES = new java.util.HashMap<>();

   static int species(String name) {
      if (name == null) {
         return 0;
      }
      Integer v = SPECIES.get(name);
      if (v == null) {
         v = species0(name);
         SPECIES.put(name, v);
      }
      return v;
   }

   private static int species0(String name) {
      if (name.contains("pine") || name.contains("hemlock") || name.contains("holly")) {
         return 4;
      }
      if (name.contains("redmaple")) {
         return 1;
      }
      if (name.contains("hawthorn")) {
         return 2;
      }
      if (name.contains("dogwood")) {
         return 3;
      }
      return 0;
   }

   /** The neighbourhood of a chunk level: materials and edges of 24 x 24 squares (the chunk +- 8), trees within 8 squares. */
   static final class Hood {
      static final int R = 8, W = 8 + 2 * R;
      int ox, oy, z;
      final byte[] mat = new byte[W * W];
      final byte[] edge = new byte[W * W]; // bit 0 a wall / fence on the north edge, bit 1 west, bit 2 a building here
      final byte[] height = new byte[W * W]; // obstacle height in quarter squares (shade)
      final ArrayList<Tree> trees = new ArrayList<>();
      int forest, town, farm;

      int m(int x, int y) {
         int ix = x - this.ox, iy = y - this.oy;
         return ix < 0 || iy < 0 || ix >= W || iy >= W ? M_NONE : this.mat[ix + iy * W];
      }

      int e(int x, int y) {
         int ix = x - this.ox, iy = y - this.oy;
         return ix < 0 || iy < 0 || ix >= W || iy >= W ? 0 : this.edge[ix + iy * W];
      }

      float h(int x, int y) {
         int ix = x - this.ox, iy = y - this.oy;
         return ix < 0 || iy < 0 || ix >= W || iy >= W ? 0F : this.height[ix + iy * W] * 0.25F;
      }
   }

   /** A builder's scratch (the game thread's and the worker's own). */
   static final class Scratch {
      final Hood hood = new Hood();
      final Corners corners = new Corners();
      final int[] sp = new int[1];
      final ArrayList<float[]> frost = new ArrayList<>();
      long[] sortKeys = new long[256];
      float[] sortTmp = new float[256 * FLOATS];
   }

   private static final ThreadLocal<Scratch> SCR = ThreadLocal.withInitial(Scratch::new);

   /** One chunk's level-0 squares as the rules need them, gathered on the game thread (the worker reads only these). */
   static final class ChunkInfo {
      int wx, wy;
      final byte[] mat = new byte[64], edge = new byte[64], height = new byte[64];
      Tree[] trees = new Tree[0];
      int forest, town, farm;
      long ms; // when gathered
   }

   public static long gathers;

   /** Game thread: chunk c's squares (64 lookups) and its zone. */
   public static long gatherNs;

   static ChunkInfo gather(IsoChunk c) {
      long g0 = System.nanoTime();
      ChunkInfo info = new ChunkInfo();
      info.wx = c.wx;
      info.wy = c.wy;
      info.ms = System.currentTimeMillis();
      ArrayList<Tree> trees = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
         int x = c.wx * 8 + (i & 7), y = c.wy * 8 + (i >> 3);
         IsoGridSquare sq = c.getGridSquare(i & 7, i >> 3, 0);
         info.mat[i] = (byte)material(sq);
         int e = 0;
         float ht = 0F;
         if (sq != null) {
            if (sq.has(IsoFlagType.collideN)) {
               e |= 1;
            }
            if (sq.has(IsoFlagType.collideW)) {
               e |= 2;
            }
            if (!sq.isOutside()) {
               e |= 4;
               zombie.iso.BuildingDef bd = sq.getBuildingDef();
               ht = bd != null ? (bd.getMaxLevel() + 1) * Glints.METRIC_Z + 1.2F : Glints.METRIC_Z;
            } else if ((e & 3) != 0) {
               ht = 1.0F; // a fence or a low wall
            }
            IsoObject[] objs = sq.getObjects().getElements();
            int n = sq.getObjects().size();
            for (int k = 0; k < n; k++) {
               if (objs[k] instanceof IsoTree t && t.sprite != null) {
                  Tree tr = new Tree();
                  int size = Math.max(1, t.getSize());
                  tr.x = x + 0.5F;
                  tr.y = y + 0.5F;
                  tr.r = 0.9F + 0.42F * size;
                  tr.h = 2.0F + 1.1F * size;
                  tr.species = species(t.sprite.name);
                  trees.add(tr);
               }
            }
         }
         info.edge[i] = (byte)e;
         info.height[i] = (byte)Math.min(127, Math.round(ht * 4F));
      }
      info.trees = trees.toArray(new Tree[0]);
      // the zone (forest / town / farm), sampled at five points
      zombie.iso.IsoMetaGrid mg = IsoWorld.instance.getMetaGrid();
      for (int i = 0; i < 5; i++) {
         int x = c.wx * 8 + (i == 0 ? 4 : i == 1 ? 0 : i == 2 ? 7 : i == 3 ? 0 : 7), y = c.wy * 8 + (i == 0 ? 4 : i < 3 ? 0 : 7);
         zombie.iso.zones.Zone zn = mg != null ? mg.getZoneAt(x, y, 0) : null;
         String t = zn != null ? zn.getType() : null;
         if (t == null) {
            continue;
         }
         if (t.contains("Forest") || t.equals("Vegitation")) {
            info.forest++;
         } else if (t.contains("Town") || t.equals("TrailerPark") || t.equals("Nav")) {
            info.town++;
         } else if (t.contains("Farm") || t.equals("Ranch")) {
            info.farm++;
         }
      }
      gathers++;
      gatherNs += System.nanoTime() - g0;
      return info;
   }

   /** Game thread: the 3 x 3 chunks' infos round c (index (dx + 1) + (dy + 1) * 3), gathered where missing; c's own afresh. */
   static ChunkInfo[] nine(IsoChunk c) {
      ChunkInfo[] out = new ChunkInfo[9];
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            IsoChunk n = dx == 0 && dy == 0 ? c : cell.getChunk(c.wx + dx, c.wy + dy);
            if (n == null) {
               continue;
            }
            ChunkCover cc = coverOf(n);
            if (cc.info == null || cc.info.wx != n.wx || cc.info.wy != n.wy || n == c || System.currentTimeMillis() - cc.info.ms > 10_000L) {
               cc.info = gather(n);
            }
            out[(dx + 1) + (dy + 1) * 3] = cc.info;
         }
      }
      return out;
   }

   /** The neighbourhood from the 3 x 3 chunks' infos (any thread). */
   static Hood hood(ChunkInfo[] nine, int wx, int wy) {
      Hood h = SCR.get().hood;
      h.ox = wx * 8 - Hood.R;
      h.oy = wy * 8 - Hood.R;
      h.z = 0;
      h.trees.clear();
      java.util.Arrays.fill(h.mat, (byte)0);
      java.util.Arrays.fill(h.edge, (byte)0);
      java.util.Arrays.fill(h.height, (byte)0);
      for (int ci = 0; ci < 9; ci++) {
         ChunkInfo info = nine[ci];
         if (info == null) {
            continue;
         }
         int bx = (ci % 3) * 8, by = (ci / 3) * 8;
         for (int i = 0; i < 64; i++) {
            int k = bx + (i & 7) + (by + (i >> 3)) * Hood.W;
            h.mat[k] = info.mat[i];
            h.edge[k] = info.edge[i];
            h.height[k] = info.height[i];
         }
         for (Tree t : info.trees) {
            h.trees.add(t);
         }
      }
      ChunkInfo me = nine[4];
      h.forest = me != null ? me.forest : 0;
      h.town = me != null ? me.town : 0;
      h.farm = me != null ? me.farm : 0;
      return h;
   }

   // ------------------------------------------------------------------------------------------------ densities

   /** Crown cover at a floor point: the sum of the trees' downwind-shifted Gaussians (and the nearest tree's species). */
   static float canopy(Hood h, float x, float y, float drift, int[] speciesOut) {
      float sum = 0F, best = 0F;
      int sp = 0;
      Season s = S();
      for (int i = 0; i < h.trees.size(); i++) {
         Tree t = h.trees.get(i);
         float cx = t.x + s.driftX * t.r * drift, cy = t.y + s.driftY * t.r * drift;
         float dx = x - cx, dy = y - cy;
         float d2 = dx * dx + dy * dy;
         float s2 = t.r * t.r;
         if (d2 > 9F * s2) {
            continue;
         }
         float g = (float)Math.exp(-d2 / (2F * s2));
         sum += g;
         if (g > best) {
            best = g;
            sp = t.species;
         }
      }
      if (speciesOut != null) {
         speciesOut[0] = sp;
      }
      return sum;
   }

   /** Gutter: a road square's distance to an edge it shares with a non-road square (curb, sidewalk, grass), Gaussian. */
   static float gutter(Hood h, float x, float y) {
      int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
      if (h.m(ix, iy) != M_ROAD) {
         return 0F;
      }
      float fx = x - ix, fy = y - iy;
      float g = 0F;
      // the four edges: a neighbour that is not road makes the edge a curb line
      if (h.m(ix, iy - 1) != M_ROAD) {
         g = Math.max(g, gauss(fy, 0.16F));
      }
      if (h.m(ix, iy + 1) != M_ROAD) {
         g = Math.max(g, gauss(1F - fy, 0.16F));
      }
      if (h.m(ix - 1, iy) != M_ROAD) {
         g = Math.max(g, gauss(fx, 0.16F));
      }
      if (h.m(ix + 1, iy) != M_ROAD) {
         g = Math.max(g, gauss(1F - fx, 0.16F));
      }
      return g;
   }

   /**
    * Leaves banked against walls and fences: a square edge with a wall / fence, on the side the wind blows toward it (the
    * upwind face catches what the wind pushes), Gaussian in the distance to the edge.
    */
   static float bank(Hood h, float x, float y) {
      int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
      float fx = x - ix, fy = y - iy;
      Season s = S();
      float b = 0F;
      // north edge of this square (wall on this square's N side): the wind blowing north (driftY < 0) piles here
      if ((h.e(ix, iy) & 1) != 0) {
         b = Math.max(b, gauss(fy, 0.2F) * (0.35F + 0.65F * Math.max(0F, -s.driftY)));
      }
      if ((h.e(ix, iy + 1) & 1) != 0) { // the south neighbour's north wall = this square's south edge
         b = Math.max(b, gauss(1F - fy, 0.2F) * (0.35F + 0.65F * Math.max(0F, s.driftY)));
      }
      if ((h.e(ix, iy) & 2) != 0) {
         b = Math.max(b, gauss(fx, 0.2F) * (0.35F + 0.65F * Math.max(0F, -s.driftX)));
      }
      if ((h.e(ix + 1, iy) & 2) != 0) {
         b = Math.max(b, gauss(1F - fx, 0.2F) * (0.35F + 0.65F * Math.max(0F, s.driftX)));
      }
      // against a building's outer wall (an indoor neighbour square)
      if ((h.e(ix, iy - 1) & 4) != 0) {
         b = Math.max(b, gauss(fy, 0.2F) * 0.7F);
      }
      if ((h.e(ix - 1, iy) & 4) != 0) {
         b = Math.max(b, gauss(fx, 0.2F) * 0.7F);
      }
      if ((h.e(ix, iy + 1) & 4) != 0) {
         b = Math.max(b, gauss(1F - fy, 0.2F) * 0.7F);
      }
      if ((h.e(ix + 1, iy) & 4) != 0) {
         b = Math.max(b, gauss(1F - fx, 0.2F) * 0.7F);
      }
      return b;
   }

   static float gauss(float d, float s) {
      return (float)Math.exp(-d * d / (2F * s * s));
   }

   /** Distance (squares, up to 3) to the nearest road square, from the 7 x 7 around. */
   static float roadNear(Hood h, int ix, int iy) {
      float best = 9F;
      for (int dy = -3; dy <= 3; dy++) {
         for (int dx = -3; dx <= 3; dx++) {
            int m = h.m(ix + dx, iy + dy);
            if (m == M_ROAD) {
               best = Math.min(best, (float)Math.sqrt(dx * dx + dy * dy));
            }
         }
      }
      return best;
   }

   // ------------------------------------------------------------------------------------------------ sun and frost

   /**
    * How much of the sun reaches a floor point now (0 shade .. 1 full sun): buildings, walls and fences along the sun's
    * azimuth on the grid, and the tree crowns the sun shines through (leafless crowns in winter let more through).
    */
   static float sunVis(Hood h, float x, float y) {
      Season s = S();
      if (s.sunZ <= 0.02F) {
         return 0F;
      }
      float hor = (float)Math.sqrt(s.sunX * s.sunX + s.sunY * s.sunY);
      float tanE = s.sunZ / Math.max(hor, 1e-3F);
      float dx = s.sunX / Math.max(hor, 1e-3F), dy = s.sunY / Math.max(hor, 1e-3F);
      float vis = 1F;
      // obstacles: march toward the sun while an obstacle at the grid's tallest could still shade us
      float maxD = Math.min(Hood.R - 0.5F, 14F / Math.max(tanE, 0.05F));
      for (float d = 0.5F; d <= maxD; d += 0.5F) {
         float px = x + dx * d, py = y + dy * d;
         float ht = h.h((int)Math.floor(px), (int)Math.floor(py));
         if (ht > d * tanE) {
            vis = 0F;
            break;
         }
      }
      if (vis > 0F && !h.trees.isEmpty()) {
         // crowns: the canopy at the point where the ray crosses the crowns' middle height
         float leafy = s.month >= 10 || s.month <= 2 ? 0.45F : 1.2F;
         float sum = 0F;
         for (int i = 0; i < h.trees.size(); i++) {
            Tree t = h.trees.get(i);
            float off = t.h * 0.7F / Math.max(tanE, 0.05F);
            float qx = x + dx * off - t.x, qy = y + dy * off - t.y;
            float d2 = qx * qx + qy * qy;
            if (d2 < 9F * t.r * t.r) {
               sum += (float)Math.exp(-d2 / (2F * t.r * t.r * 0.6F));
            }
         }
         vis *= (float)Math.exp(-leafy * 1.6F * sum);
      }
      return vis;
   }

   /** Frost's hold on a surface: grass and leaves most, bare ground, pavement, asphalt (warm, dark) least. */
   static float surf(int mat) {
      return mat == M_GRASS ? 1F : mat == M_DIRT || mat == M_GRAVEL ? 0.7F : mat == M_WALK ? 0.45F : mat == M_ROAD ? 0.3F : mat == M_BUILT ? 0.6F : 0F;
   }

   /**
    * The chunk's 9 x 9 square corners: crown cover (drifted and not), the nearest crown's species, the meadow mask, the sun
    * visibility and the frost. Shared corners make every rule continuous across squares and chunks; candidates interpolate.
    */
   static final class Corners {
      final float[] can = new float[81], open = new float[81], meadow = new float[81], sun = new float[81], frost = new float[81];
      final int[] species = new int[81];

      float bil(float[] a, float fx, float fy) {
         int ix = Math.min(7, (int)fx), iy = Math.min(7, (int)fy);
         float tx = fx - ix, ty = fy - iy;
         int k = ix + iy * 9;
         return (a[k] * (1F - tx) + a[k + 1] * tx) * (1F - ty) + (a[k + 9] * (1F - tx) + a[k + 10] * tx) * ty;
      }

      int nearestSpecies(float fx, float fy) {
         return this.species[Math.min(8, Math.round(fx)) + Math.min(8, Math.round(fy)) * 9];
      }
   }

   static Corners corners(Hood h, int wx, int wy) {
      Corners k = SCR.get().corners;
      int[] sp = SCR.get().sp;
      Season s = S();
      boolean frostOn = s.frost > 0F && s.frostOn();
      for (int cy = 0; cy < 9; cy++) {
         for (int cx = 0; cx < 9; cx++) {
            int i = cx + cy * 9;
            int X = wx * 8 + cx, Y = wy * 8 + cy;
            k.can[i] = canopy(h, X, Y, 0.45F, sp);
            k.species[i] = sp[0];
            k.open[i] = 1F - 0.7F * Math.min(1F, canopy(h, X, Y, 0F, null));
            k.meadow[i] = smooth(0.45F, 0.62F, fbm(X * 0.35F + 9F, Y * 0.35F + 2F, 21));
            float sun = sunVis(h, X, Y);
            k.sun[i] = sun;
            if (frostOn) {
               // the four squares that share the corner: their surfaces' mean
               float sf = 0.25F * (surf(h.m(X - 1, Y - 1)) + surf(h.m(X, Y - 1)) + surf(h.m(X - 1, Y)) + surf(h.m(X, Y)));
               // the morning's sun: exposure = visibility x hours since sunrise; ~1.5 h of direct sun clears it
               float melt = smooth(0.4F, 1.6F, sun * s.sunUpHours * 1.2F + s.sunUpHours * 0.12F);
               float n = 0.55F + 0.9F * fbm(X * 0.45F + 3.1F, Y * 0.45F - 7.7F, 77);
               k.frost[i] = Math.max(0F, Math.min(1F, s.frost * k.open[i] * sf * (1F - melt) * n));
            } else {
               k.frost[i] = 0F;
            }
         }
      }
      return k;
   }

   /** For Glints: a square's sun visibility and frost as the cover's last build saw them (no cover built: full sun, no frost). */
   public static float squareSun(IsoChunk c, int level, int i) {
      LevelCover lc = built(c, level);
      return lc == null ? 1F : (lc.sunSq[i] & 255) / 255F;
   }

   public static float squareFrost(IsoChunk c, int level, int i) {
      LevelCover lc = built(c, level);
      return lc == null ? 0F : (lc.frostSq[i] & 255) / 255F;
   }

   private static LevelCover built(IsoChunk c, int level) {
      if (level < -32 || level > 31) {
         return null;
      }
      ChunkCover cc = slot(c);
      if (cc == null) {
         return null;
      }
      LevelCover lc = cc.levels[level + 32];
      return lc != null && lc.key != Integer.MIN_VALUE ? lc : null;
   }

   // ------------------------------------------------------------------------------------------------ placement

   static final int T_LEAF = 0, T_FLOWER = 1, T_TWIG = 2, T_SPRITE = 3, T_NEEDLES = 4, T_FROST = 5, T_PEBBLE = 6;
   static final int FLOATS = 16; // four RGBA32F texels an instance

   /** Progressive blue-noise points of a 4 x 4 square torus (Mitchell's best candidate): any prefix is well spread. */
   static final class BlueNoise {
      static final int TILE = 4, N = 36 * TILE * TILE;
      static float[] px, py;
      static int[][] bySquare; // per square of the tile: indices of its points (rank = index / N)

      static synchronized void init() {
         if (px != null) {
            return;
         }
         float[] x = new float[N], y = new float[N];
         java.util.SplittableRandom r = new java.util.SplittableRandom(0x5eed1e5L);
         for (int i = 0; i < N; i++) {
            int cand = Math.min(10 * (i + 1), 64); // Mitchell: candidates grow with the set (any prefix stays well spread)
            float bx = 0F, by = 0F, bd = -1F;
            for (int c = 0; c < cand; c++) {
               float cx = (float)r.nextDouble() * TILE, cy = (float)r.nextDouble() * TILE;
               float dmin = Float.MAX_VALUE;
               for (int j = 0; j < i; j++) {
                  float dx = Math.abs(cx - x[j]), dy = Math.abs(cy - y[j]);
                  dx = Math.min(dx, TILE - dx);
                  dy = Math.min(dy, TILE - dy);
                  dmin = Math.min(dmin, dx * dx + dy * dy);
               }
               if (dmin > bd) {
                  bd = dmin;
                  bx = cx;
                  by = cy;
               }
            }
            x[i] = bx;
            y[i] = by;
         }
         int[][] lists = new int[TILE * TILE][];
         int[] counts = new int[TILE * TILE];
         for (int i = 0; i < N; i++) {
            counts[(int)x[i] + (int)y[i] * TILE]++;
         }
         for (int k = 0; k < lists.length; k++) {
            lists[k] = new int[counts[k]];
            counts[k] = 0;
         }
         for (int i = 0; i < N; i++) {
            int k = (int)x[i] + (int)y[i] * TILE;
            lists[k][counts[k]++] = i;
         }
         py = y;
         bySquare = lists;
         px = x;
      }
   }

   /** One chunk level's cover: its instances (built once per season / frost step and settings), drawn by every bake. */
   static final class LevelCover {
      float[] data = new float[64 * FLOATS];
      int n, frostN;
      int key = Integer.MIN_VALUE, fkey = Integer.MIN_VALUE, skey = Integer.MIN_VALUE;
      boolean outdoor;
      final byte[] sunSq = new byte[64], frostSq = new byte[64]; // per square, for Glints
      long squares; // squares that took cover (re-checked each bake: a floor built since drops its cover)
      final int[] runTex = new int[16], runFrom = new int[16], runTo = new int[16];
      int runs;

      void grow(int need) {
         if (need * FLOATS > this.data.length) {
            this.data = java.util.Arrays.copyOf(this.data, Math.max(need * FLOATS, this.data.length * 2));
         }
      }
   }

   static final class ChunkCover {
      final LevelCover[] levels = new LevelCover[64];
      int wx = Integer.MIN_VALUE, wy;
      long seen; // ms: last baked, or last found fresh by the prefetch (the chunk is still loaded)
      ChunkInfo info; // level 0's squares as last gathered
      boolean inFlight; // a worker build of level 0 is queued or running
      boolean rebakeOnLand; // a bake drew the stale (or no) cover meanwhile: re-bake when the build lands
      boolean baked; // level 0 was baked since its cover was built
   }

   static ChunkCover coverOf(IsoChunk c) {
      ChunkCover cc = slot(c);
      if (cc == null) {
         cc = new ChunkCover();
         cc.wx = c.wx;
         cc.wy = c.wy;
         GroundChunk.put(c, SLOT, cc);
      }
      return cc;
   }

   // ------------------------------------------------------------------------------------------------ builds off the game thread

   /** A level-0 build for the worker: the gathered squares, the season snapshot, the keys it answers. */
   static final class Job {
      IsoChunk chunk;
      ChunkCover cc;
      ChunkInfo[] nine;
      Season season;
      int key, fkey, skey, gen;
      final LevelCover out = new LevelCover();
      long ns;
   }

   private static final java.util.concurrent.ConcurrentLinkedQueue<Job> landed = new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static java.util.concurrent.ExecutorService worker;
   private static int inFlight;
   public static long asyncBuilds, syncBuilds, landRebakes, staleDraws, droppedBuilds, asyncNs;

   static boolean async() {
      return Config.GROUND_COVER_ASYNC;
   }

   private static java.util.concurrent.ExecutorService worker() {
      if (worker == null) {
         worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pzopt-ground-cover");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
         });
      }
      return worker;
   }

   /** Game thread: queue chunk c's level-0 build on the worker (its squares and its neighbours' gathered here). */
   public static long submits, submitNs;

   static void submit(IsoChunk c, ChunkCover cc) {
      long t0 = System.nanoTime();
      Sprites.resolve();
      Job j = new Job();
      j.chunk = c;
      j.cc = cc;
      j.gen = GroundChunk.gen(c);
      j.nine = nine(c);
      j.season = snapshot();
      j.key = seasonKey();
      j.fkey = frostKey();
      j.skey = settingsKey();
      cc.inFlight = true;
      inFlight++;
      submits++;
      submitNs += System.nanoTime() - t0;
      worker().execute(() -> {
         CUR.set(j.season);
         long w0 = System.nanoTime();
         try {
            build(j.nine, j.cc.wx, j.cc.wy, 0, j.out);
         } catch (RuntimeException e) {
            j.out.n = j.out.frostN = j.out.runs = 0; // failed: lands empty
            Log.warn("ground cover: build failed: " + e);
         } finally {
            CUR.remove();
         }
         j.ns = System.nanoTime() - w0;
         landed.add(j);
      });
   }

   /** Game thread, once a frame: install the landed builds (re-baking a chunk that drew a stale cover), then prefetch. */
   static void land() {
      Job j;
      int k = seasonKey(), fk = frostKey(), sk = settingsKey();
      while ((j = landed.poll()) != null) {
         inFlight--;
         ChunkCover cc = j.cc;
         cc.inFlight = false;
         IsoChunk c = j.chunk;
         if (GroundChunk.gen(c) != j.gen || GroundChunk.get(c, SLOT) != cc || cc.wx != c.wx || cc.wy != c.wy) {
            droppedBuilds++; // the chunk left (or its slot was reused) meanwhile
            continue;
         }
         asyncBuilds++;
         asyncNs += j.ns;
         buildNs += j.ns;
         builds++;
         if (builds > 40) {
            buildLateNs += j.ns;
            buildLate++;
         }
         LevelCover prev = cc.levels[32];
         boolean changed = prev == null || prev.n != j.out.n || prev.n > 0;
         j.out.key = j.key;
         j.out.fkey = j.fkey;
         j.out.skey = j.skey;
         cc.levels[32] = j.out;
         if (j.key != k || j.fkey != fk || j.skey != sk) {
            continue; // stale already: the prefetch builds it again (and its landing re-bakes)
         }
         if (cc.rebakeOnLand || cc.baked && changed) {
            cc.rebakeOnLand = false;
            queueRebake(c);
            landRebakes++;
         }
         cc.baked = false;
      }
   }

   private static int prefetchFrame;

   /** Game thread: the nearest loaded chunks whose level-0 cover is missing or stale go to the worker, two at a time. */
   static void prefetch() {
      if (!async() || inFlight >= 2 || (++prefetchFrame & 3) != 0) {
         return;
      }
      zombie.iso.IsoChunkMap cm = IsoWorld.instance.currentCell.getChunkMap(0);
      if (cm == null) {
         return;
      }
      int k = seasonKey(), fk = frostKey(), sk = settingsKey();
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      int w = zombie.iso.IsoChunkMap.chunkGridWidth;
      long now = System.currentTimeMillis();
      for (int round = inFlight; round < 2; round++) {
         IsoChunk best = null;
         float bd = Float.MAX_VALUE;
         for (int gy = 0; gy < w; gy++) {
            for (int gx = 0; gx < w; gx++) {
               IsoChunk c = cm.getChunk(gx, gy);
               if (c == null || !c.loaded) {
                  continue;
               }
               ChunkCover cc = slot(c);
               if (cc != null) {
                  if (cc.inFlight) {
                     continue;
                  }
                  LevelCover lc = cc.levels[32];
                  if (lc != null && lc.key == k && lc.fkey == fk && lc.skey == sk) {
                     cc.seen = now;
                     continue;
                  }
               }
               float dx = c.wx * 8 + 4 - cx, dy = c.wy * 8 + 4 - cy;
               float d = dx * dx + dy * dy;
               if (d < bd) {
                  bd = d;
                  best = c;
               }
            }
         }
         if (best == null) {
            return;
         }
         ChunkCover cc = coverOf(best);
         cc.seen = System.currentTimeMillis();
         submit(best, cc);
      }
   }

   static LevelCover cover(IsoChunk c, int level) {
      ChunkCover cc = coverOf(c);
      cc.seen = System.currentTimeMillis();
      int li = level + 32;
      LevelCover lc = cc.levels[li];
      int k = seasonKey(), fk = frostKey(), sk = settingsKey();
      boolean fresh = lc != null && lc.key == k && lc.fkey == fk && lc.skey == sk;
      if (fresh) {
         cc.baked = true;
         return lc;
      }
      if (async() && level == 0) {
         // the worker builds it (prefetched, or now): the bake draws what there is and re-bakes when the build lands
         if (!cc.inFlight) {
            submit(c, cc);
         }
         cc.rebakeOnLand = true;
         if (lc != null) {
            staleDraws++;
         }
         return lc;
      }
      if (lc == null) {
         lc = cc.levels[li] = new LevelCover();
      }
      long t0 = System.nanoTime();
      build(nine(c), c.wx, c.wy, level, lc);
      long dt = System.nanoTime() - t0;
      syncBuilds++;
      buildNs += dt;
      builds++;
      buildMinNs = Math.min(buildMinNs, dt);
      if (builds > 40) { // past the JIT's warm-up
         buildLateNs += dt;
         buildLate++;
      }
      lc.key = k;
      lc.fkey = fk;
      lc.skey = sk;
      cc.baked = true;
      return lc;
   }

   /** The cover rides on its chunk (GroundChunk slot: dropped when the game reuses the chunk). */
   static final int SLOT = GroundChunk.id("ground cover");

   static ChunkCover slot(IsoChunk c) {
      ChunkCover cc = (ChunkCover)GroundChunk.get(c, SLOT);
      return cc != null && cc.wx == c.wx && cc.wy == c.wy ? cc : null;
   }

   /** Game thread: every loaded chunk of every player's chunk map, once. */
   static void forEachLoaded(java.util.function.Consumer<IsoChunk> f) {
      zombie.iso.IsoCell cell = IsoWorld.instance.currentCell;
      int w = zombie.iso.IsoChunkMap.chunkGridWidth;
      java.util.Set<IsoChunk> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (int pl = 0; pl < 4; pl++) {
         zombie.iso.IsoChunkMap cm = cell.getChunkMap(pl);
         if (cm == null || cm.ignore) {
            continue;
         }
         for (int gy = 0; gy < w; gy++) {
            for (int gx = 0; gx < w; gx++) {
               IsoChunk c = cm.getChunk(gx, gy);
               if (c != null && c.loaded && done.add(c)) {
                  f.accept(c);
               }
            }
         }
      }
   }



   /** Leaf colours per species (yellow, red, orange, purple-red), fresh; old leaves fade to brown. */
   static final float[][] LEAF = {
      {0.86F, 0.66F, 0.18F}, {0.80F, 0.26F, 0.12F}, {0.85F, 0.45F, 0.15F}, {0.55F, 0.18F, 0.22F}, {0.42F, 0.36F, 0.22F}};
   static final float[] OLD = {0.36F, 0.25F, 0.15F};

   static void build(ChunkInfo[] nine, int wx, int wy, int level, LevelCover lc) {
      lc.n = 0;
      lc.frostN = 0;
      lc.squares = 0L;
      lc.outdoor = false;
      long t0 = System.nanoTime();
      Hood h = hood(nine, wx, wy);
      long t1 = System.nanoTime();
      Season s = S();
      Corners K = corners(h, wx, wy);
      long t2 = System.nanoTime();
      hoodNs += t1 - t0;
      cornersNs += t2 - t1;
      for (int i = 0; i < 64; i++) {
         int k0 = (i & 7) + (i >> 3) * 9;
         lc.sunSq[i] = (byte)Math.round(64F * (K.sun[k0] + K.sun[k0 + 1] + K.sun[k0 + 9] + K.sun[k0 + 10]) - 0.5F);
         lc.frostSq[i] = (byte)Math.round(64F * (K.frost[k0] + K.frost[k0 + 1] + K.frost[k0 + 9] + K.frost[k0 + 10]) - 0.5F);
      }
      float dens = s.density() / 100F;
      boolean blue = "bluenoise".equals(s.placement());
      boolean useSprites = !"procedural".equals(s.assets());
      boolean useShapes = !"sprites".equals(s.assets());
      if (blue) {
         BlueNoise.init();
      }
      boolean scatterOn = true;
      int townBias = h.town > 0 ? 1 : 0;
      float forest = h.forest / 5F, farm = h.farm / 5F, town = h.town / 5F;
      // frost first in the list but drawn last (a second run): collected separately
      ArrayList<float[]> frostQuads = SCR.get().frost;
      frostQuads.clear();
      for (int sy = 0; sy < 8; sy++) {
         for (int sx = 0; sx < 8; sx++) {
            int x = wx * 8 + sx, y = wy * 8 + sy;
            int mat = h.m(x, y);
            if (mat == M_NONE) {
               continue;
            }
            lc.outdoor = true;
            if (mat == M_BUILT) {
               // player-built and decked floors: no cover (frost only)
               addFrostQuad(K, sx, sy, x, y, mat, frostQuads);
               continue;
            }
            float rn = roadNear(h, x, y);
            // an upper bound of the square's total density (the rules' maxima over its corners): candidates ranked above it
            // are rejected before any rule runs (blue noise: the square's list is in rank order, so the loop stops there)
            int k0 = sx + sy * 9;
            float canMax = Math.max(Math.max(K.can[k0], K.can[k0 + 1]), Math.max(K.can[k0 + 9], K.can[k0 + 10])) * 1.1F + 0.02F;
            boolean everAny = K.species[k0] == 4 || K.species[k0 + 1] == 4 || K.species[k0 + 9] == 4 || K.species[k0 + 10] == 4;
            boolean gutAny = mat == M_ROAD && (h.m(x, y - 1) != M_ROAD || h.m(x, y + 1) != M_ROAD || h.m(x - 1, y) != M_ROAD || h.m(x + 1, y) != M_ROAD);
            boolean bankAny = (h.e(x, y) & 3) != 0 || (h.e(x, y + 1) & 1) != 0 || (h.e(x + 1, y) & 2) != 0
               || ((h.e(x, y - 1) | h.e(x - 1, y) | h.e(x, y + 1) | h.e(x + 1, y)) & 4) != 0;
            float aroundMax = Math.min(1F, h.trees.size() / 6F) * (0.4F + 0.6F * s.accum);
            float leafMax = s.leafFall * (0.55F * Math.min(1.5F, canMax) * (mat == M_ROAD ? 0.22F + 0.35F * s.accum : mat == M_WALK ? 0.55F : 1F)
               + aroundMax * ((gutAny ? 1.05F : 0F) + (bankAny ? 0.85F : 0F))) * (0.6F + 0.8F * forest);
            float meadowMax = Math.max(Math.max(K.meadow[k0], K.meadow[k0 + 1]), Math.max(K.meadow[k0 + 9], K.meadow[k0 + 10]));
            float bound = leafMax + (everAny ? 0.35F : 0F)
               + (mat == M_GRASS ? s.flowers * meadowMax * (0.45F + farm + 0.4F * (1 - townBias)) * 0.55F : 0F)
               + s.accum * (town * 0.5F + 0.15F) * 0.2F
               + (0.012F + 0.05F * forest) * Math.min(1F, canMax)
               + (mat == M_GRAVEL || mat == M_DIRT ? 0.04F : 0F);
            bound *= dens;
            if (scatterOn) {
               int ncand = blue ? BlueNoise.bySquare[Math.floorMod(x, BlueNoise.TILE) + Math.floorMod(y, BlueNoise.TILE) * BlueNoise.TILE].length : 36;
               for (int k = 0; k < ncand; k++) {
                  float px, py, thr;
                  int id;
                  if (blue) {
                     int pi = BlueNoise.bySquare[Math.floorMod(x, BlueNoise.TILE) + Math.floorMod(y, BlueNoise.TILE) * BlueNoise.TILE][k];
                     px = x + (BlueNoise.px[pi] - (float)Math.floor(BlueNoise.px[pi]));
                     py = y + (BlueNoise.py[pi] - (float)Math.floor(BlueNoise.py[pi]));
                     thr = (pi + 0.5F) / BlueNoise.N; // the dither rank
                     id = mix(x, y, pi);
                  } else {
                     int gx = k % 6, gy = k / 6;
                     px = x + (gx + hf(x * 6 + gx, y * 6 + gy, 11)) / 6F;
                     py = y + (gy + hf(x * 6 + gx, y * 6 + gy, 12)) / 6F;
                     thr = hf(x * 6 + gx, y * 6 + gy, 13);
                     id = mix(x * 6 + gx, y * 6 + gy, 14);
                  }
                  if (thr > bound) {
                     if (blue) {
                        break;
                     }
                     continue;
                  }
                  float lx = px - wx * 8, ly = py - wy * 8;
                  float canopy = K.bil(K.can, lx, ly);
                  int species = K.nearestSpecies(lx, ly);
                  float gut = gutter(h, px, py);
                  float bnk = bank(h, px, py);
                  float onRoad = mat == M_ROAD ? 1F : 0F;
                  float leafSrc = Math.min(1.5F, canopy) * (mat == M_ROAD ? 0.22F + 0.35F * s.accum : mat == M_WALK ? 0.55F : 1F);
                  // gutters and banks collect the leaves of the whole street: scaled by the trees around (any within 8)
                  float around = Math.min(1F, h.trees.size() / 6F) * (0.4F + 0.6F * s.accum);
                  float leafD = s.leafFall * (0.55F * leafSrc + around * (1.05F * gut + 0.85F * bnk)) * (0.6F + 0.8F * forest);
                  boolean ever = species == 4 && canopy > 0.2F;
                  float needleD = ever ? 0.35F * Math.min(1F, canopy) : 0F;
                  if (ever) {
                     leafD *= 0.25F;
                  }
                  float meadow = K.bil(K.meadow, lx, ly);
                  float flowerD = mat == M_GRASS ? s.flowers * meadow * (1F - Math.min(1F, canopy) * 0.6F) * (0.45F + farm + 0.4F * (1 - townBias)) * smooth(1.2F, 2.5F, rn) * 0.55F : 0F;
                  float litterD = s.accum * (town * 0.5F + 0.15F) * ((mat == M_WALK ? 0.05F : 0F) + (mat == M_GRASS && rn < 2F ? 0.025F : 0F) + onRoad * gut * 0.08F + bnk * 0.03F);
                  float twigD = (0.012F + 0.05F * forest) * Math.min(1F, canopy) * (mat == M_GRASS || mat == M_DIRT ? 1F : 0.3F);
                  float pebD = mat == M_GRAVEL || mat == M_DIRT ? 0.04F : 0F;
                  float r = thr / dens;
                  int type;
                  float acc = leafD;
                  if (r < acc) {
                     type = T_LEAF;
                  } else if (r < (acc += needleD)) {
                     type = T_NEEDLES;
                  } else if (r < (acc += flowerD)) {
                     type = T_FLOWER;
                  } else if (r < (acc += litterD)) {
                     type = T_SPRITE;
                  } else if (r < (acc += twigD)) {
                     type = T_TWIG;
                  } else if (r < (acc += pebD)) {
                     type = T_PEBBLE;
                  } else {
                     continue;
                  }
                  float u1 = hf(id, 1, 31), u2 = hf(id, 2, 31), u3 = hf(id, 3, 31);
                  int spriteIdx = -1;
                  float size, rot = u1 * 6.2831855F, cr, cg, cb, alpha = 1F;
                  if (type == T_LEAF) {
                     // a dense spot (bank, gutter, under a big crown) sometimes takes a leaf clump decal instead
                     boolean clump = useSprites && leafD > 0.9F && s.leafAge < 0.85F && u3 < 0.10F;
                     if (clump || !useShapes) {
                        spriteIdx = Sprites.leafClump(id);
                        type = T_SPRITE;
                        size = 0.32F + 0.18F * u2;
                        float old = s.leafAge;
                        cr = 1F - 0.45F * old;
                        cg = 1F - 0.5F * old;
                        cb = 1F - 0.5F * old;
                     } else {
                        float[] fresh = LEAF[species == 4 ? 4 : species];
                        // mixed autumn: a third of the leaves take another species' colour (other trees around)
                        if (u2 < 0.33F) {
                           fresh = LEAF[(species + 1 + (int)(u3 * 3F)) % 4];
                        }
                        float age = Math.min(1F, s.leafAge + 0.35F * u3);
                        cr = fresh[0] + (OLD[0] - fresh[0]) * age;
                        cg = fresh[1] + (OLD[1] - fresh[1]) * age;
                        cb = fresh[2] + (OLD[2] - fresh[2]) * age;
                        float v = 0.82F + 0.3F * u2;
                        cr *= v;
                        cg *= v;
                        cb *= v;
                        size = 0.055F + 0.045F * u2;
                     }
                  } else if (type == T_NEEDLES) {
                     cr = 0.42F;
                     cg = 0.30F;
                     cb = 0.17F;
                     size = 0.07F + 0.04F * u2;
                  } else if (type == T_FLOWER) {
                     int f = (int)(u2 * 4F);
                     cr = f == 0 ? 0.95F : f == 1 ? 0.96F : f == 2 ? 0.70F : 0.92F;
                     cg = f == 0 ? 0.94F : f == 1 ? 0.82F : f == 2 ? 0.55F : 0.62F;
                     cb = f == 0 ? 0.88F : f == 1 ? 0.25F : f == 2 ? 0.86F : 0.72F;
                     size = 0.035F + 0.025F * u3;
                  } else if (type == T_SPRITE) {
                     if (!useSprites) {
                        type = T_TWIG; // procedural only: a scrap of paper is a pale twig-like strip
                        cr = 0.85F;
                        cg = 0.83F;
                        cb = 0.78F;
                        size = 0.06F;
                     } else {
                        spriteIdx = Sprites.litter(id);
                        cr = cg = cb = 1F;
                        size = 0.3F + 0.15F * u2;
                     }
                  } else if (type == T_TWIG) {
                     cr = 0.35F;
                     cg = 0.25F;
                     cb = 0.16F;
                     size = 0.09F + 0.08F * u2;
                  } else {
                     float g = 0.45F + 0.25F * u2;
                     cr = g;
                     cg = g * 0.97F;
                     cb = g * 0.92F;
                     size = 0.025F + 0.02F * u3;
                  }
                  if (type == T_SPRITE && (spriteIdx < 0 || !Sprites.ready(spriteIdx))) {
                     continue;
                  }
                  lc.grow(lc.n + 1);
                  int o = lc.n * FLOATS;
                  float[] d = lc.data;
                  d[o] = px - wx * 8;
                  d[o + 1] = py - wy * 8;
                  d[o + 2] = level;
                  d[o + 3] = type;
                  d[o + 4] = size;
                  d[o + 5] = rot;
                  d[o + 6] = spriteIdx;
                  d[o + 7] = alpha;
                  d[o + 8] = cr;
                  d[o + 9] = cg;
                  d[o + 10] = cb;
                  d[o + 11] = u3;
                  d[o + 12] = sx + sy * 8; // its square (the light and the floor check per bake)
                  d[o + 13] = px - x;
                  d[o + 14] = py - y;
                  d[o + 15] = 0F;
                  lc.n++;
                  lc.squares |= 1L << (sx + sy * 8);
               }
            }
            addFrostQuad(K, sx, sy, x, y, mat, frostQuads);
         }
      }
      // sprites grouped by texture page after the shapes (stable within each group): runs
      sortRuns(lc);
      // frost quads last: they coat the cover
      for (int i = 0; i < frostQuads.size(); i++) {
         float[] q = frostQuads.get(i);
         lc.grow(lc.n + 1);
         System.arraycopy(q, 0, lc.data, lc.n * FLOATS, FLOATS);
         lc.data[lc.n * FLOATS] -= wx * 8;
         lc.data[lc.n * FLOATS + 1] -= wy * 8;
         lc.data[lc.n * FLOATS + 2] = level;
         lc.n++;
         lc.frostN++;
      }
      if (lc.frostN > 0) {
         int last = lc.runs - 1;
         if (last >= 0 && lc.runTex[last] == 0) {
            lc.runTo[last] = lc.n;
         } else if (lc.runs < lc.runTex.length) {
            lc.runTex[lc.runs] = 0;
            lc.runFrom[lc.runs] = lc.n - lc.frostN;
            lc.runTo[lc.runs] = lc.n;
            lc.runs++;
         }
      }
   }

   private static void addFrostQuad(Corners K, int sx, int sy, int x, int y, int mat, ArrayList<float[]> out) {
      Season s = S();
      if (s.frost <= 0F || !s.frostOn()) {
         return;
      }
      // frost at the four corners, shared with the neighbours: the quad interpolates them
      int k = sx + sy * 9;
      float f0 = K.frost[k], f1 = K.frost[k + 1], f2 = K.frost[k + 10], f3 = K.frost[k + 9];
      if (f0 + f1 + f2 + f3 < 0.04F) {
         return;
      }
      float[] q = new float[FLOATS];
      q[0] = x;
      q[1] = y;
      q[3] = T_FROST;
      q[4] = 1F;
      q[5] = 0F;
      // the chunk's border edges: the quad reaches a little past them, over the neighbour textures' floor overlap
      q[6] = (sx == 0 ? 1 : 0) | (sx == 7 ? 2 : 0) | (sy == 0 ? 4 : 0) | (sy == 7 ? 8 : 0);
      q[7] = 1F;
      q[8] = f0;
      q[9] = f1;
      q[10] = f2;
      q[11] = f3;
      q[12] = sx + sy * 8;
      q[13] = 0F;
      q[14] = 0F;
      q[15] = mat;
      out.add(q);
   }

   /** Instances in runs by texture page: shapes first (page 0), then each sprite page; the order inside a page kept. */
   private static void sortRuns(LevelCover lc) {
      int n = lc.n;
      Scratch scr = SCR.get();
      if (scr.sortKeys.length < n) {
         scr.sortKeys = new long[Math.max(n, scr.sortKeys.length * 2)];
         scr.sortTmp = new float[scr.sortKeys.length * FLOATS];
      }
      long[] sortKeys = scr.sortKeys;
      float[] sortTmp = scr.sortTmp;
      for (int i = 0; i < n; i++) {
         int page = (int)lc.data[i * FLOATS + 3] == T_SPRITE ? Sprites.page((int)lc.data[i * FLOATS + 6]) : 0;
         sortKeys[i] = ((long)page << 32) | i;
      }
      java.util.Arrays.sort(sortKeys, 0, n);
      System.arraycopy(lc.data, 0, sortTmp, 0, n * FLOATS);
      lc.runs = 0;
      int prev = -1;
      for (int i = 0; i < n; i++) {
         int src = (int)(sortKeys[i] & 0xffffffffL);
         System.arraycopy(sortTmp, src * FLOATS, lc.data, i * FLOATS, FLOATS);
         int page = (int)(sortKeys[i] >>> 32);
         if (page != prev && lc.runs < lc.runTex.length) {
            lc.runTex[lc.runs] = page;
            lc.runFrom[lc.runs] = i;
            lc.runTo[lc.runs] = i + 1;
            lc.runs++;
            prev = page;
         } else if (lc.runs > 0) {
            lc.runTo[lc.runs - 1] = i + 1;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the game's decals

   /** The decals used as cover: leaf clumps and small litter, their texture pages and geometry (resolved lazily). */
   static final class Sprites {
      static final String[] NAMES;
      static final int CLUMPS = 12;
      static final int[] LITTER = {0, 1, 2, 9, 15, 16, 21, 22, 23, 24, 25};

      static {
         NAMES = new String[CLUMPS + LITTER.length];
         for (int i = 0; i < CLUMPS; i++) {
            NAMES[i] = "d_floorleaves_1_" + i;
         }
         for (int i = 0; i < LITTER.length; i++) {
            NAMES[CLUMPS + i] = "d_trash_1_" + LITTER[i];
         }
      }

      static final Texture[] tex = new Texture[NAMES.length];
      static final int[] pageId = new int[NAMES.length];
      /** per sprite: uv rect (u0, v0, u1, v1), geometry (trimmed x, y offset from the anchor, w, h) in texture pixels */
      static final float[] uv = new float[NAMES.length * 4], geom = new float[NAMES.length * 4];
      static boolean resolved;

      static int leafClump(int id) {
         return (id >>> 3) % CLUMPS;
      }

      static int litter(int id) {
         return CLUMPS + (id >>> 3) % LITTER.length;
      }

      static boolean ready(int i) {
         resolve();
         return i >= 0 && i < NAMES.length && pageId[i] != 0;
      }

      static int page(int i) {
         return i >= 0 && i < NAMES.length ? pageId[i] : 0;
      }

      static void resolve() {
         if (resolved) {
            return;
         }
         resolved = true;
         for (int i = 0; i < NAMES.length; i++) {
            Texture t = Texture.getSharedTexture(NAMES[i]);
            if (t == null || t.getTextureId() == null) {
               continue;
            }
            tex[i] = t;
            pageId[i] = t.getTextureId().getID();
            uv[i * 4] = t.getXStart();
            uv[i * 4 + 1] = t.getYStart();
            uv[i * 4 + 2] = t.getXEnd();
            uv[i * 4 + 3] = t.getYEnd();
            // the frame's floor diamond centre is the anchor: (widthOrig / 2, heightOrig - widthOrig / 4)
            float ax = t.getWidthOrig() * 0.5F, ay = t.getHeightOrig() - t.getWidthOrig() * 0.25F;
            geom[i * 4] = t.getOffsetX() - ax;
            geom[i * 4 + 1] = t.getOffsetY() - ay;
            geom[i * 4 + 2] = t.getWidth();
            geom[i * 4 + 3] = t.getHeight();
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ bake (game thread)

   /**
    * FBORenderCell's floor pass, after the floor tiles and before the snow: this chunk level's cover into the bound chunk
    * texture. Returns at once when there is none.
    */
   public static void bake(IsoChunk c, int level, int playerIndex) {
      if (!on() || FBORenderChunkManager.instance.renderChunk == null) {
         return;
      }
      if (level != 0) {
         return; // natural ground and streets are level 0 (ground-common re-bakes level 0)
      }
      if (step == Integer.MIN_VALUE) {
         updateSeason();
         step = seasonKey();
         frostStep = frostKey();
         lastSettings = settingsKey();
      }
      bakes++;
      LevelCover lc = cover(c, level);
      if (lc == null || lc.n == 0) {
         bakesSkipped++;
         return;
      }
      Drawer d = Drawer.alloc();
      FBORenderChunkManager m = FBORenderChunkManager.instance;
      d.setup(c, lc, level, playerIndex, m.renderChunk.chunk.wx * 8, m.renderChunk.chunk.wy * 8, m.getXOffset(), m.getYOffset());
      if (d.n == 0) {
         d.postRender();
         bakesSkipped++;
         return;
      }
      String sec = Ground.section("ground.cover");
      GpuSections.begin(sec);
      SpriteRenderer.instance.drawGeneric(d);
      GpuSections.end(sec);
      instancesDrawn += d.n;
   }

   /** One chunk level's cover drawn into the bound chunk texture (render thread). */
   static final class Drawer extends TextureDraw.GenericDrawer {
      private static final ArrayList<Drawer> pool = new ArrayList<>();
      float[] data = new float[64 * FLOATS];
      int n;
      final int[] runTex = new int[16], runFrom = new int[16], runTo = new int[16];
      int runs;
      float cx, cy, bx, by, goX, goY;
      int ts;

      static Drawer alloc() {
         synchronized (pool) {
            if (!pool.isEmpty()) {
               return pool.remove(pool.size() - 1);
            }
         }
         return new Drawer();
      }

      void setup(IsoChunk c, LevelCover lc, int level, int playerIndex, int bakeX8, int bakeY8, float goX, float goY) {
         if (this.data.length < lc.n * FLOATS) {
            this.data = new float[Math.max(lc.n * FLOATS, this.data.length * 2)];
         }
         // copy, dropping squares whose floor stopped being natural (a floor built since the build), with the light now
         float[] light = LIGHT;
         long lit = 0L;
         int n = 0;
         this.runs = 0;
         for (int r = 0; r < lc.runs; r++) {
            int from = n;
            for (int i = lc.runFrom[r]; i < lc.runTo[r]; i++) {
               int o = i * FLOATS;
               int s = (int)lc.data[o + 12];
               if ((lit >>> s & 1L) == 0L) {
                  lit |= 1L << s;
                  squareLight(c, s & 7, s >> 3, level, playerIndex, light, s);
               }
               if (light[s * 13 + 12] < 0.5F) {
                  continue; // the square is gone or no longer takes cover
               }
               int q = n * FLOATS;
               System.arraycopy(lc.data, o, this.data, q, FLOATS);
               // the light at the spot: bilinear of the square's four corners (N, E, S, W = vert lights 0, 1, 2, 3)
               float fx = lc.data[o + 13], fy = lc.data[o + 14];
               if ((int)lc.data[o + 3] == T_FROST) {
                  fx = 0.5F;
                  fy = 0.5F;
               }
               float w0 = (1F - fx) * (1F - fy), w1 = fx * (1F - fy), w2 = fx * fy, w3 = (1F - fx) * fy;
               int b = s * 13;
               this.data[q + 13] = light[b] * w0 + light[b + 3] * w1 + light[b + 6] * w2 + light[b + 9] * w3;
               this.data[q + 14] = light[b + 1] * w0 + light[b + 4] * w1 + light[b + 7] * w2 + light[b + 10] * w3;
               this.data[q + 15] = light[b + 2] * w0 + light[b + 5] * w1 + light[b + 8] * w2 + light[b + 11] * w3;
               n++;
            }
            if (n > from) {
               this.runTex[this.runs] = lc.runTex[r];
               this.runFrom[this.runs] = from;
               this.runTo[this.runs] = n;
               this.runs++;
            }
         }
         this.n = n;
         this.cx = c.wx * 8;
         this.cy = c.wy * 8;
         this.bx = bakeX8;
         this.by = bakeY8;
         this.goX = goX;
         this.goY = goY;
         this.ts = Core.tileScale;
      }

      @Override
      public void render() {
         Gpu.draw(this);
      }

      @Override
      public void postRender() {
         synchronized (pool) {
            if (pool.size() < 64) {
               pool.add(this);
            }
         }
      }
   }

   private static final float[] LIGHT = new float[64 * 13];

   /** The square's four corner lights (rgb each) and whether it still takes cover (index 12), into out[s * 13 ...]. */
   static void squareLight(IsoChunk c, int lx, int ly, int level, int playerIndex, float[] out, int s) {
      IsoGridSquare sq = c.getGridSquare(lx, ly, level);
      int b = s * 13;
      if (sq == null || sq.getFloor() == null || material(sq) == M_NONE) {
         out[b + 12] = 0F;
         return;
      }
      out[b + 12] = 1F;
      for (int k = 0; k < 4; k++) {
         int l = sq.getVertLight(k, playerIndex);
         out[b + k * 3] = (l & 255) / 255F;
         out[b + k * 3 + 1] = (l >> 8 & 255) / 255F;
         out[b + k * 3 + 2] = (l >> 16 & 255) / 255F;
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final String VERT = String.join("\n",
         "#version 330",
         "uniform sampler2D uData;",
         "uniform int uBase;",
         "uniform mat4 uMVP;",
         "uniform vec4 uOrigin; // the cover's chunk x8, y8; the baked chunk x8, y8",
         "uniform vec4 uGo;     // goX, goY, tile scale, -",
         "uniform vec4 uSprUv[32];",
         "uniform vec4 uSprGeom[32];",
         "out vec2 vL;          // local coordinates: -1..1 over the shape (0..1 over a frost square), or the sprite's uv",
         "out vec2 vW;          // world position (squares, relative to the cover's chunk)",
         "flat out vec4 vCol;",
         "flat out vec4 vLight;",
         "flat out vec4 vFrost;",
         "flat out ivec2 vType; // type, variant",
         "void main() {",
         "   int i = uBase + gl_InstanceID;",
         "   vec4 a = texelFetch(uData, ivec2(i * 4, 0), 0);",
         "   vec4 b = texelFetch(uData, ivec2(i * 4 + 1, 0), 0);",
         "   vec4 c = texelFetch(uData, ivec2(i * 4 + 2, 0), 0);",
         "   vec4 d = texelFetch(uData, ivec2(i * 4 + 3, 0), 0);",
         "   int type = int(a.w + 0.5);",
         "   int v = gl_VertexID;",
         "   vec2 q = v == 0 ? vec2(-1.0, -1.0) : v == 1 ? vec2(1.0, -1.0) : v == 2 ? vec2(1.0, 1.0) : vec2(-1.0, 1.0);",
         "   float ts = uGo.z;",
         "   vec2 wpos; // world floor point of this corner, relative to the baked chunk",
         "   vec2 base = a.xy + uOrigin.xy - uOrigin.zw;",
         "   vec2 scr;",
         "   if (type == 3) {",
         "      // a decal: its trimmed rectangle around the anchor, scaled, in screen pixels",
         "      int s = int(b.z + 0.5);",
         "      vec4 g = uSprGeom[s];",
         "      vec2 anchor = vec2((base.x - base.y) * 32.0 * ts, (base.x + base.y) * 16.0 * ts - a.z * 96.0 * ts);",
         "      vec2 c01 = q * 0.5 + 0.5;",
         "      scr = anchor + (g.xy + g.zw * c01) * (b.x * ts / 2.0) / 0.5 * 0.5;",
         "      vec4 uv = uSprUv[s];",
         "      vL = vec2(mix(uv.x, uv.z, c01.x), mix(uv.y, uv.w, c01.y));",
         "      wpos = base;",
         "   } else if (type == 5) {",
         "      // frost: the whole square (a.xy is its north corner), a little past the chunk's border edges (b.z's bits)",
         "      int ef = int(b.z + 0.5);",
         "      float mg = 0.025;",
         "      vec2 lo = vec2((ef & 1) != 0 ? -mg : 0.0, (ef & 4) != 0 ? -mg : 0.0);",
         "      vec2 hi = vec2((ef & 2) != 0 ? 1.0 + mg : 1.0, (ef & 8) != 0 ? 1.0 + mg : 1.0);",
         "      vec2 o = mix(lo, hi, q * 0.5 + 0.5);",
         "      wpos = base + o;",
         "      scr = vec2((wpos.x - wpos.y) * 32.0 * ts, (wpos.x + wpos.y) * 16.0 * ts - a.z * 96.0 * ts);",
         "      vL = o;",
         "   } else {",
         "      // a shape lying on the floor: a rectangle in the floor plane, rotated, half-size b.x (squares), 0.55 wide",
         "      float cs = cos(b.y), sn = sin(b.y);",
         "      vec2 e = q * vec2(b.x, b.x * (type == 2 ? 0.22 : type == 1 ? 1.0 : type == 6 ? 0.8 : 0.62));",
         "      wpos = base + vec2(cs * e.x - sn * e.y, sn * e.x + cs * e.y);",
         "      scr = vec2((wpos.x - wpos.y) * 32.0 * ts, (wpos.x + wpos.y) * 16.0 * ts - a.z * 96.0 * ts);",
         "      vL = q;",
         "   }",
         "   gl_Position = uMVP * vec4(scr + uGo.xy, 0.0, 1.0);",
         "   gl_Position.z = 0.0;",
         "   vW = wpos + uOrigin.zw;",
         "   vCol = vec4(c.rgb, b.w);",
         "   vLight = vec4(d.yzw, c.w);",
         "   vFrost = c;",
         "   vType = ivec2(type, int(c.w * 7.0));",
         "}");

   static final String FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D DIFFUSE;",
         "uniform vec4 uView; // dev view, density pct, -, -",
         "in vec2 vL;",
         "in vec2 vW;",
         "flat in vec4 vCol;",
         "flat in vec4 vLight;",
         "flat in vec4 vFrost;",
         "flat in ivec2 vType;",
         "out vec4 fragColor;",
         "#define texture2D texture",
         GroundState.GLSL,
         "// the ground state (pzopt.GroundState: ruts, prints, compaction) where the cover lies: 0 untouched .. 1 trodden flat",
         "float crushed() { vec4 st = pzgStateAt(vW); return clamp(max(-st.r, 0.0) * 4.0 + st.g * 1.2, 0.0, 1.0); }", // a rut (h < 0) or worn ground
         "float h21(vec2 p) { uvec2 u = uvec2(ivec2(floor(p))); uint h = u.x * 0x27d4eb2du ^ u.y * 0x165667b1u; h ^= h >> 15u; h *= 0x85ebca6bu; h ^= h >> 13u; return float(h >> 8u) / 16777216.0; }",
         "float vn(vec2 p) { vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);",
         "   return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y); }",
         "float aa(float d) { float w = max(fwidth(d), 1e-4); return clamp(0.5 - d / w, 0.0, 1.0); }",
         "void main() {",
         "   int type = vType.x;",
         "   vec3 col = vCol.rgb;",
         "   float a = 0.0;",
         "   if (type == 3) {",
         "      vec4 t = texture(DIFFUSE, vL);",
         "      if (t.a < 0.02) discard;",
         "      fragColor = vec4(t.rgb * col * vLight.rgb, t.a * vCol.a);",
         "      return;",
         "   } else if (type == 5) {",
         "      // frost: rime, not snow: a pale silver film whose strength follows the corners' amounts, in patches (where the",
         "      // grass stands proud and dry), with a fine streaky grain of frozen blades; the glints are drawn by pzopt.Glints",
         "      vec2 o = clamp(vL, 0.0, 1.0);",
         "      float f = mix(mix(vFrost.x, vFrost.y, o.x), mix(vFrost.w, vFrost.z, o.x), o.y);",
         "      float patch = vn(vW * 1.7 + 2.1) * 0.65 + vn(vW * 4.3 - 5.2) * 0.35;",
         "      float cover = smoothstep(0.08, 0.85, f * (0.45 + 0.85 * patch));",
         "      float ang = vn(vW * 0.8 + 11.0) * 6.2831853;",
         "      vec2 dir = vec2(cos(ang), sin(ang));",
         "      float streak = vn(vec2(dot(vW, dir) * 38.0, dot(vW, vec2(-dir.y, dir.x)) * 7.0));",
         "      float grain = vn(vW * 52.0 + 7.3);",
         "      float tex = 0.5 + 0.5 * smoothstep(0.3, 0.85, streak * 0.6 + grain * 0.4);",
         "      a = clamp(cover * tex * 0.62, 0.0, 0.7) * (1.0 - 0.85 * crushed()); // prints and ruts through the frost",
         "      col = mix(vec3(0.80, 0.85, 0.91), vec3(0.94, 0.96, 0.99), grain);",
         "      if (a < 0.01) discard;",
         "      fragColor = vec4(col * vLight.rgb * a, a);",
         "      return;",
         "   } else if (type == 0) {",
         "      // a leaf: an almond with a pointed tip along x, a midrib, a stem; one half catches more light (a curl)",
         "      vec2 p = vL;",
         "      float w = pow(max(1.0 - p.x * p.x, 0.0), 0.75) * (1.0 - 0.25 * p.x);",
         "      float d = abs(p.y) - w;",
         "      a = aa(d * 0.55);",
         "      float stem = aa(abs(p.y) - 0.06) * step(0.85, p.x) * step(p.x, 1.0);",
         "      a = max(a, stem);",
         "      float rib = 1.0 - 0.28 * aa(abs(p.y) - 0.05);",
         "      float edge = 1.0 - 0.25 * smoothstep(-0.25, 0.0, d);",
         "      float curl = p.y > 0.0 ? 1.08 : 0.9;",
         "      col *= rib * edge * curl;",
         "   } else if (type == 1) {",
         "      // a small flower: five petals and a yellow heart",
         "      vec2 p = vL;",
         "      float r = length(p), th = atan(p.y, p.x) + float(vType.y);",
         "      float petal = 0.55 + 0.45 * abs(cos(2.5 * th));",
         "      a = aa(r - petal * 0.95);",
         "      if (r < 0.32) col = vec3(0.95, 0.78, 0.2);",
         "      col *= 0.85 + 0.15 * (1.0 - r);",
         "   } else if (type == 2) {",
         "      // a twig: a thin stick with a side branch",
         "      vec2 p = vL;",
         "      float main = abs(p.y) - 0.55 * (1.0 - 0.3 * abs(p.x));",
         "      a = aa(main);",
         "      col *= 0.8 + 0.4 * smoothstep(-0.5, 0.5, p.y);",
         "   } else if (type == 4) {",
         "      // needles: a few thin strokes radiating",
         "      vec2 p = vL;",
         "      float th = atan(p.y, p.x), r = length(p);",
         "      float k = abs(fract(th * 0.95 + float(vType.y) * 0.1) - 0.5);",
         "      a = aa(k - 0.06) * step(r, 1.0) * step(0.15, r);",
         "   } else {",
         "      // a pebble: a rounded blob with a lit top",
         "      vec2 p = vL;",
         "      float r = length(p * vec2(1.0, 1.25));",
         "      a = aa(r - 0.9);",
         "      col *= 0.8 + 0.35 * smoothstep(0.5, -0.6, p.y);",
         "   }",
         "   if (a <= 0.01) discard;",
         "   if (type <= 1) col *= 1.0 - 0.5 * crushed(); // leaves and petals pressed into a rut go dark",
         "   if (uView.x == 1.0) col = type == 0 ? vec3(1, 0.5, 0) : type == 1 ? vec3(1, 0, 1) : type == 4 ? vec3(0, 1, 0) : vec3(0, 0.6, 1);",
         "   fragColor = vec4(col * vLight.rgb * a, a);",
         "}");

   static final class Gpu {
      static int program, tex, vao, texWidth;
      static int uData, uBase, uMVP, uOrigin, uGo, uSprUv, uSprGeom, uDiffuse, uView;
      static FloatBuffer upload;
      static final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      static final FloatBuffer spr = BufferUtils.createFloatBuffer(32 * 4);
      static final Matrix4f mvp = new Matrix4f();

      static void warm() {
         if ((Config.GROUND_SCATTER || Config.GROUND_COVER_FROST) && Overrides.enabled()) {
            init();
         }
      }

      static boolean init() {
         if (program != 0) {
            return program > 0;
         }
         int p = Shaders.program("ground cover", VERT, FRAG);
         if (p == 0) {
            program = -1;
            failed = true;
            Log.warn("ground cover: the program did not build, ground cover off");
            return false;
         }
         program = p;
         uData = GL20.glGetUniformLocation(p, "uData");
         uBase = GL20.glGetUniformLocation(p, "uBase");
         uMVP = GL20.glGetUniformLocation(p, "uMVP");
         uOrigin = GL20.glGetUniformLocation(p, "uOrigin");
         uGo = GL20.glGetUniformLocation(p, "uGo");
         uSprUv = GL20.glGetUniformLocation(p, "uSprUv");
         uSprGeom = GL20.glGetUniformLocation(p, "uSprGeom");
         uDiffuse = GL20.glGetUniformLocation(p, "DIFFUSE");
         uView = GL20.glGetUniformLocation(p, "uView");
         vao = GL30.glGenVertexArrays();
         Log.info("ground cover: program " + p);
         return true;
      }

      static void draw(Drawer d) {
         if (d.n == 0) {
            return;
         }
         try {
            if (!init()) {
               return;
            }
            int texels = d.n * 4;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            if (tex == 0 || texWidth < texels) {
               if (tex != 0) {
                  GL11.glDeleteTextures(tex);
               }
               texWidth = Math.max(4096, Integer.highestOneBit(texels) << 1);
               tex = GL11.glGenTextures();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
               GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, texWidth, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
               GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            if (upload == null || upload.capacity() < texels * 4) {
               upload = BufferUtils.createFloatBuffer(Math.max(texels * 4, 4096 * 4));
            }
            upload.clear();
            upload.put(d.data, 0, d.n * FLOATS).flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, texels, 1, GL11.GL_RGBA, GL11.GL_FLOAT, upload);
            GL20.glUseProgram(program);
            GL20.glUniform1i(uData, 1);
            GroundState.bindTexture();
            GroundState.applyUniforms(program);
            GL20.glUniform1i(uDiffuse, 0);
            mvp.set(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
            mat.clear();
            mvp.get(mat);
            GL20.glUniformMatrix4fv(uMVP, false, mat);
            GL20.glUniform4f(uOrigin, d.cx, d.cy, d.bx, d.by);
            GL20.glUniform4f(uGo, d.goX, d.goY, d.ts, 0F);
            GL20.glUniform4f(uView, Config.DEV_GROUND_COVER_VIEW, Config.GROUND_SCATTER_DENSITY_PCT, 0F, 0F);
            Sprites.resolve();
            spr.clear();
            for (int i = 0; i < 32; i++) {
               for (int k = 0; k < 4; k++) {
                  spr.put(i < Sprites.NAMES.length ? Sprites.uv[i * 4 + k] : 0F);
               }
            }
            spr.flip();
            GL20.glUniform4fv(uSprUv, spr);
            spr.clear();
            for (int i = 0; i < 32; i++) {
               for (int k = 0; k < 4; k++) {
                  spr.put(i < Sprites.NAMES.length ? Sprites.geom[i * 4 + k] : 0F);
               }
            }
            spr.flip();
            GL20.glUniform4fv(uSprGeom, spr);
            // stock blood's state: no depth test (no depth write), premultiplied colour over the floor, alpha kept
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            for (int r = 0; r < d.runs; r++) {
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, d.runTex[r]);
               GL20.glUniform1i(uBase, d.runFrom[r]);
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, d.runTo[r] - d.runFrom[r]);
            }
            GL30.glBindVertexArray(0);
         } catch (Throwable t) {
            failed = true;
            Log.warn("ground cover: draw failed, ground cover off: " + t);
         } finally {
            GL20.glUseProgram(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
            GLStateRenderThread.restore();
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
      }
   }

   /** The covers ride on their chunks (GroundChunk): nothing to sweep. */
   public static void sweep() {
   }
}
