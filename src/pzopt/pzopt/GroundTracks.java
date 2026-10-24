package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.weather.ClimateManager;

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
 * <p>The map, its window, the binning, the dispatches, the decay clock, the re-bakes and the persistence are
 * pzopt.GroundState's (ground-common), the sources pzopt.GroundMovers'; this class gives GroundState its rules
 * ({@link #RULES}: GroundTracksGlsl.RULES behind GroundState's gs* entry points), sets the sources from its keys and draws
 * the look in the bake (an after-snow floor pass).
 */
public final class GroundTracks {
   private GroundTracks() {
   }

   static final int TPS = GroundState.TPS, TN = GroundState.TN, CS = GroundState.CS;

   public static long frames, bakeDraws;
   public static long gameNs;
   static volatile boolean failed;

   /** The key after the ground masters and the dev alternation's off half (Ground.on). */
   public static boolean on() {
      return Ground.on(Ground.K_TRACKS) && Overrides.enabled() && !failed && !CoreGl.legacyMac();
   }

   public static String stats() {
      return "ground tracks: " + (on() ? "on" : "off") + " frames=" + frames + " bakeDraws=" + bakeDraws + " game_us/frame="
         + (frames == 0 ? 0 : gameNs / 1000L / frames) + (failed ? " (failed)" : "") + " | " + GroundState.stats();
   }

   // ------------------------------------------------------------------------------------------------ the rules (GroundState)

   /** GroundTracksGlsl.RULES behind GroundState's entry points: Sumner / O'Brien / Hodgins' cut, berms and repose, the decay. */
   static final GroundState.Rules RULES = new GroundState.Rules() {
      @Override
      public String id() {
         return "tracks";
      }

      @Override
      public String glsl() {
         return "#define GT_TPS GS_TPS\n#define GT_TN GS_TN\n" + GroundTracksGlsl.RULES + "\n" + String.join("\n",
            "GtPrim gtPrimAt(int i) { GtPrim q; q.a = gsPrimA(i); q.b = gsPrimB(i); q.c = gsPrimC(i); return q; }",
            "vec4 gsStampTexel(vec4 s, vec2 p, int mat, int count) { return gtStampTexel(s, p, mat, count); }",
            "vec4 gsDecayTexel(vec4 s, int mat, float dt, vec4 weather) { return gtDecay(s, mat, dt, weather); }",
            "float gsRelaxHeight(float h, vec4 nb, int mat) { return gtRelax(h, nb, GT_M0[mat].z); }",
            "");
      }

      @Override
      public boolean relax() {
         return true;
      }

      @Override
      public double decayEveryHours() {
         return Config.GROUND_TRACKS_DECAY_MINUTES / 60.0 / Config.DEV_GROUND_TRACKS_TIME_SCALE;
      }

      @Override
      public float decayDt(double gameHours) {
         return (float)(gameHours * Config.DEV_GROUND_TRACKS_TIME_SCALE * 2.0 / Math.max(1, Config.GROUND_TRACKS_DAYS));
      }

      @Override
      public boolean rebakes() {
         String l = look();
         return "bake".equals(l) || "patch".equals(l); // (composite: drawn each frame, nothing to re-bake) // (devGroundCycle stamp: the stamping alone)
      }

      @Override
      public long rebakeMs() {
         return Config.GROUND_TRACKS_REBAKE_MS;
      }
   };

   /** The look, drawn over the snow in each chunk texture's bake. */
   static final GroundBake.FloorPass PASS = new GroundBake.FloorPass() {
      @Override
      public String id() {
         return "tracks";
      }

      @Override
      public boolean active() {
         return on() && "bake".equals(look());
      }

      @Override
      public void draw(IsoChunk c, int level, int playerIndex) {
         bakeFloor(c, level);
      }

      @Override
      public int phase() {
         return GroundBake.PHASE_AFTER_SNOW;
      }
   };

   /**
    * The look's way in: bake (a draw over each chunk texture's floors and snow, {@link #PASS}), patch (the floor tile program's
    * colour hook, {@link #PATCH}: no extra draw, but under the snow), composite (over the picture each frame), stamp (none:
    * the stamping alone). devGroundCycle variants of these names override the key in-run.
    */
   /**
    * Water in the ruts: bake (a flat murky surface drawn with the look), puddles (the game's puddle pass fills the ruts: its
    * ripples, raindrop rings and reflections; needs ripples on, which patches the puddle program), off. devGroundCycle
    * variants puddlewater / nowater override the key.
    */
   static String water(String v) {
      if ("puddlewater".equals(v)) {
         return "puddles";
      }
      if ("nowater".equals(v)) {
         return "off";
      }
      return Config.GROUND_TRACKS_WATER;
   }

   static String look() {
      String v = Ground.variant();
      if ("stamp".equals(v) || "patch".equals(v) || "bake".equals(v) || "composite".equals(v)) {
         return v;
      }
      return Config.GROUND_TRACKS_LOOK;
   }

   /** The light, the weather and the sky of the frame for the patch's uniforms (game thread writes, render thread reads). */
   private static volatile float[] patchUniforms = new float[11];

   /** The look in the floor tile program (the patch variant): the same factor, multiplied into every natural floor piece. */
   static final GroundBake.FloorPatch PATCH = new GroundBake.FloorPatch() {
      @Override
      public String id() {
         return "tracks";
      }

      @Override
      public boolean active() {
         return on() && "patch".equals(look());
      }

      @Override
      public String declarations() {
         String h = header(Ground.variant());
         h = h.substring(h.indexOf('\n') + 1); // (the floor program has its own #version)
         return h + "uniform vec4 gtPLight;\nuniform vec4 gtPWeather;\nuniform vec3 gtPSky;\n" + GroundTracksGlsl.LOOK + "\n" + String.join("\n",
            "vec4 gtS(vec2 p) { return pzgStateAt(p); }",
            "vec4 gtPatchColor(vec4 c) {",
            "   int m = pzgMaterial();", // GroundTiles: 1 grass .. 4 gravel, 8 clay, 9 dark dirt; snow is drawn after the floors
            "   m = (m == 8 || m == 9) ? 2 : m;",
            "   if (m < 1 || m > 4 || c.a <= 0.0) return c;",
            "   vec3 f = gtFactor(pzgW.xy, m, gtPLight.xyz, gtPLight.w, gtPWeather.x, gtPWeather.y, gtPSky);",
            "   return vec4(min(c.rgb * f, vec3(c.a)), c.a);",
            "}",
            "");
      }

      @Override
      public String color() {
         return "gtPatchColor";
      }

      @Override
      public void bakeStart(int program, zombie.iso.fboRenderChunk.FBORenderChunk rc) {
         float[] u = patchUniforms;
         GL20.glUniform4f(GL20.glGetUniformLocation(program, "gtPLight"), u[0], u[1], u[2], u[3]);
         GL20.glUniform4f(GL20.glGetUniformLocation(program, "gtPWeather"), u[4], u[5], 0F, 0F);
         GL20.glUniform3f(GL20.glGetUniformLocation(program, "gtPSky"), u[6], u[7], u[8]);
      }
   };

   static {
      GroundBake.register(PATCH);
      GroundBake.register(PASS);
      Ground.onFrame(GroundTracks::frameStart);
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   private static long lastFrameCount = -1L;
   private static long lastNanos;
   private static boolean rulesSet;
   private static String lastVariant;
   private static float wet;

   /** Ground.frameStart's feature hook (before the movers and GroundState): the rules, the sources, the wetness. */
   public static void frameStart(int playerIndex) {
      if (!Config.GROUND_TRACKS) {
         if (rulesSet) {
            rulesSet = false;
            GroundState.setRules(null);
         }
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
      if (!rulesSet) {
         rulesSet = true;
         GroundState.setRules(RULES);
      }
      GroundState.use();
      String v = Ground.variant();
      if (v != null && !v.equals(lastVariant)) {
         lastVariant = v;
         Log.info("ground tracks: variant " + v + " epoch_ms " + System.currentTimeMillis());
         GroundBake.rebakeAll("tracks variant", false);
      }
      GroundState.Sources src = GroundState.SOURCES;
      src.vehicles = Config.GROUND_TRACKS_VEHICLES;
      src.feet = Config.GROUND_TRACKS_FEET;
      src.bodies = Config.GROUND_TRACKS_BODIES;
      src.strengthPct = Config.GROUND_TRACKS_STRENGTH_PCT;
      wetness();
      puddleWet = wet;
      zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.players[playerIndex];
      if (Config.DEV_GROUND_TRACKS_RIG > 0 && pl != null) {
         rig(pl);
      }
      gameNs += System.nanoTime() - t0;
      if (Config.DEV_GROUND_TRACKS_LOG && frames % 600L == 0L) {
         Log.info(stats());
      }
   }

   /** The ground's wetness (0..1): rain wets it within the hour, it dries over hours (slower in the cold). */
   private static void wetness() {
      float rain = GroundState.WEATHER[0], temp = GroundState.WEATHER[2];
      float gh = (float)(zombie.GameTime.getInstance().getGameWorldSecondsSinceLastUpdate() / 3600.0);
      if (!(gh > 0F) || gh > 1F) {
         gh = 0F;
      }
      float tauDry = temp < 5F ? 18F : temp < 15F ? 10F : 5F;
      wet += rain * (1F - wet) * Math.min(1F, gh / 0.75F) - wet * Math.min(1F, gh / tauDry);
      wet = clamp01(wet);
      if (Config.DEV_GROUND_TRACKS_WET_PCT >= 0) {
         wet = Math.min(100, Config.DEV_GROUND_TRACKS_WET_PCT) / 100F;
      }
   }

   static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   // ------------------------------------------------------------------------------------------------ the look rig

   private static double rigX = Double.NaN, rigY;
   private static long rigT0;
   private static final double[] rigLast = new double[16];
   private static boolean rigBody;
   private static final float[] rigWalk = new float[6];
   private static final int[] rigFoot = new int[3];

   /** devGroundTracksRig: a scripted scene beside the player, the same every run (time from its first frame). */
   private static void rig(zombie.characters.IsoPlayer pl) {
      long now = System.currentTimeMillis();
      if (Double.isNaN(rigX) || Math.abs(pl.getX() - rigX) > 30.0 || Math.abs(pl.getY() - rigY) > 30.0) { // (a teleport starts it again)
         rigBody = false;
         java.util.Arrays.fill(rigWalk, 0F);
         rigX = Math.floor(pl.getX()) + 0.5;
         rigY = Math.floor(pl.getY()) + 0.5;
         rigT0 = now;
         java.util.Arrays.fill(rigLast, Double.NaN);
         Log.info("ground tracks rig: anchor " + rigX + "," + rigY);
      }
      double t = (now - rigT0) / 1000.0;
      if (Config.DEV_GROUND_TRACKS_RIG_SECONDS > 0 && t > Config.DEV_GROUND_TRACKS_RIG_SECONDS) {
         return; // (devGroundTracksRigSeconds: the scene stops, the decay goes on: the recovery rig)
      }
      // the car: an S from 9 squares west to 9 east, 3 squares north of the player, 4 squares a second, its rear sliding
      // through the bend (slip 0.7 there); 4 wheels, track 1.5, wheelbase 2.6
      double s = Math.min(t * 4.0, 20.0);
      for (int w = 0; w < 4; w++) {
         double sw = s - (w >= 2 ? 2.6 : 0.0);
         if (sw < 0) {
            continue;
         }
         double cx = rigX - 9.0 + sw, cy = rigY - 3.0 - 1.6 * Math.sin(Math.min(sw, 18.0) / 18.0 * Math.PI * 2.0);
         double dx = 1.0, dy = -1.6 * Math.PI * 2.0 / 18.0 * Math.cos(Math.min(sw, 18.0) / 18.0 * Math.PI * 2.0);
         double l = Math.hypot(dx, dy);
         double side = (w & 1) == 0 ? 0.75 : -0.75;
         double x = cx - dy / l * side, y = cy + dx / l * side;
         float slip = (w >= 2 && sw > 7 && sw < 11) ? 0.7F : 0F;
         if (!Double.isNaN(rigLast[w * 2]) && Math.hypot(x - rigLast[w * 2], y - rigLast[w * 2 + 1]) > 0.02) {
            GroundState.wheel(rigLast[w * 2], rigLast[w * 2 + 1], x, y, 0.10F, 0.10F * (1F + 0.6F * slip), 0.035F * (1F + slip), slip);
         } else if (!Double.isNaN(rigLast[w * 2])) {
            continue;
         }
         rigLast[w * 2] = x;
         rigLast[w * 2 + 1] = y;
      }
      // three walkers 3, 4.5 and 6 squares south, west to east at walking pace, prints every 0.42
      for (int k = 0; k < 3; k++) {
         double wx = rigX - 8.0 + Math.min(t * 2.2, 16.0), wy = rigY + 3.0 + 1.5 * k + 0.4 * Math.sin(t * 0.7 + k);
         float lx = rigWalk[k * 2], ly = rigWalk[k * 2 + 1];
         if (lx == 0F) {
            rigWalk[k * 2] = (float)wx;
            rigWalk[k * 2 + 1] = (float)wy;
            continue;
         }
         double d = Math.hypot(wx - lx, wy - ly);
         if (d >= 0.42) {
            float ux = (float)((wx - lx) / d), uy = (float)((wy - ly) / d);
            rigFoot[k] ^= 1;
            float sd = rigFoot[k] == 0 ? 0.09F : -0.09F;
            GroundState.ellipse(wx - uy * sd, wy + ux * sd, ux, uy, 0.14F, 0.055F, 0.045F, 0.012F, 1F, 1F);
            rigWalk[k * 2] = (float)wx;
            rigWalk[k * 2 + 1] = (float)wy;
         }
      }
      // a body 4 squares west of the player, a drag 6 squares south-west
      if (!rigBody && t > 1.0) {
         rigBody = true;
         GroundState.ellipse(rigX - 4.0, rigY + 0.5, 0.8F, 0.6F, 0.85F, 0.28F, 0.035F, 0.01F, 1F, 0F);
      }
      if (t > 1.0 && t < 4.0) {
         double a = (t - 1.0) / 3.0;
         GroundState.drag(rigX - 9.0 + 4.0 * a, rigY + 8.0, rigX - 9.0 + 4.0 * a + 0.1, rigY + 8.0, 0.22F);
      }
   }

   // ------------------------------------------------------------------------------------------------ the bake pass

   private static final float[] LIGHT = new float[4];

   /** the after-snow floor pass (GroundBake.PHASE_AFTER_SNOW: FBORenderCell.pzoptBakeSnow's end, game thread, in the bake stream): the tracks drawn over the chunk level's floors. */
   public static void bakeFloor(IsoChunk c, int level) {
      if (level != 0 || !on() || c == null || !GroundState.isLive(c.wx, c.wy)) {
         return; // (outside the map's window, or nothing pressed into this chunk)
      }
      byte[] mats = GroundState.surfaces(c.wx, c.wy, System.currentTimeMillis());
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

   // ------------------------------------------------------------------------------------------------ render thread

   private static boolean ok;
   private static int bakeProg, vao;
   private static int bMVP, bGo, bState, bUv, bMat, bLight, bWeather, bSky;
   private static final java.nio.IntBuffer INTS = BufferUtils.createIntBuffer(8);
   private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
   private static final Matrix4f MVP = new Matrix4f();

   /**
    * The look's defines from the keys; devGroundCycle variants override them in-run: nopom (relief offset sampling off),
    * noshadow, noao, flat (lighting only: neither the offset sampling nor the shadow march), colour (the colour response alone,
    * no relight at all).
    */
   static String header() {
      return header(Ground.variant());
   }

   /** The bake program's defines for dev variant {@code v} (null: the keys alone; tests link every variant). */
   static String header(String v) {
      boolean pom = Config.GROUND_TRACKS_POM && !"nopom".equals(v) && !"flat".equals(v) && !"colour".equals(v);
      int shadow = "noshadow".equals(v) || "flat".equals(v) || "colour".equals(v) ? 0 : Config.GROUND_TRACKS_SHADOW_STEPS;
      boolean ao = Config.GROUND_TRACKS_AO && !"noao".equals(v) && !"colour".equals(v);
      return "#version 430\n#define GT_TPS " + TPS + ".0\n#define GT_TN " + TN + "\n#define GT_POM " + (pom ? 1 : 0) + "\n#define GT_SHADOW " + shadow
         + "\n#define GT_AO " + (ao ? 1 : 0) + "\n#define GT_RELIGHT " + ("colour".equals(v) ? 0 : 1) + "\n#define GT_WATER " + ("bake".equals(water(v)) ? 1 : 0)
         + "\n";
   }

   static String bakeVert(String h) {
      return h + GroundTracksGlsl.BAKE_VERT;
   }

   static String bakeFrag(String h) {
      return h + GroundTracksGlsl.BAKE_FRAG.replace("// (gt_look.glsl follows)", GroundTracksGlsl.LOOK);
   }

   private static final java.util.HashMap<String, int[]> PROGRAMS = new java.util.HashMap<>();
   private static String bakeHeader;

   /** The bake program for the current defines (a key or a dev variant change compiles another, once). */
   private static boolean init() {
      String h = header();
      if (h.equals(bakeHeader)) {
         return ok;
      }
      bakeHeader = h;
      int[] cached = PROGRAMS.get(h);
      if (cached == null) {
         int p = link(bakeVert(h), bakeFrag(h), "ground tracks bake");
         cached = new int[] {p};
         PROGRAMS.put(h, cached);
         if (p != 0) {
            Log.info("ground tracks: bake program for " + h.replace("\n", " ").replace("#define ", ""));
         }
      }
      bakeProg = cached[0];
      ok = bakeProg != 0;
      if (!ok) {
         failed = true;
         Log.warn("ground tracks: the bake program failed, off");
         return false;
      }
      bMVP = GL20.glGetUniformLocation(bakeProg, "uMVP");
      bGo = GL20.glGetUniformLocation(bakeProg, "uGo");
      bState = GL20.glGetUniformLocation(bakeProg, "uState");
      bUv = GL20.glGetUniformLocation(bakeProg, "uUv");
      bMat = GL20.glGetUniformLocation(bakeProg, "uMat");
      bLight = GL20.glGetUniformLocation(bakeProg, "uLight");
      bWeather = GL20.glGetUniformLocation(bakeProg, "uWeather");
      bSky = GL20.glGetUniformLocation(bakeProg, "uSky");
      if (vao == 0) {
         vao = GL30.glGenVertexArrays();
      }
      return true;
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

   static void renderBake(Bake b) {
      if (failed || GroundState.texture() == 0 || !init()) {
         return;
      }
      GpuSections.markNow("ground.bake", false); // render thread, inside the drawer
      try {
         GL20.glUseProgram(bakeProg);
         MVP.set(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
         MAT.clear();
         MVP.get(MAT);
         GL20.glUniformMatrix4fv(bMVP, false, MAT);
         GL20.glUniform4f(bGo, b.goX, b.goY, b.ts, b.level);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, GroundState.texture());
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
         GpuSections.markNow("ground.bake", true);
      }
   }

   // ------------------------------------------------------------------------------------------------ the composite look

   /**
    * The composite variant (groundTracksLook=composite): the same factor drawn over the picture every frame, right after the
    * chunk composite, on the level-0 floor diamond of every live chunk on screen. The diamond's depth is the floor's own
    * (IsoDepthHelper's depth is linear in x + y at z 0: PixelLight.DEPTH_PER_XY), a hair toward the camera, against the
    * scene depth with the hardware test: whatever stands on the ground (baked trees, furniture, fences) is in front and keeps
    * its colour, no depth read in the shader. No re-bake when a track changes (stamps show the frame they land).
    */
   public static void afterComposite(int playerIndex) {
      if (!on() || !"composite".equals(look()) || playerIndex != 0) {
         return;
      }
      int wx0 = GroundState.windowX(), wy0 = GroundState.windowY();
      if (wx0 == Integer.MIN_VALUE) {
         return;
      }
      Comp f = Comp.alloc();
      Core core = Core.getInstance();
      f.zoom = core.getZoom(playerIndex);
      f.ts = Core.tileScale;
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      f.offX = IsoCamera.getOffX();
      f.offY = IsoCamera.getOffY();
      int ox = (int)Math.floor(IsoCamera.frameState.camCharacterX), oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      f.ox = ox;
      f.oy = oy;
      f.d0 = zombie.iso.IsoDepthHelper.getSquareDepthData(ox, oy, ox, oy, 0.0F).depthStart;
      Bake lb = f.light;
      light(lb);
      lb.wet = wet;
      lb.snowDepth = snowDepth();
      // the screen in (x - y, x + y) relative to the origin, for the cull (zoomed screen px over the tile size, a chunk's margin)
      double a32 = 32.0 * f.ts, a16 = 16.0 * f.ts;
      double aMin = (f.offX) / a32 - (ox - oy), aMax = (f.offX + f.screenW * f.zoom) / a32 - (ox - oy);
      double bMin = (f.offY) / a16 - (ox + oy), bMax = (f.offY + f.screenH * f.zoom) / a16 - (ox + oy);
      long now = System.currentTimeMillis();
      f.n = 0;
      for (int cy = wy0; cy < wy0 + GroundState.SLOTS; cy++) {
         for (int cx = wx0; cx < wx0 + GroundState.SLOTS; cx++) {
            if (!GroundState.isLive(cx, cy) || f.n >= Comp.MAX) {
               continue;
            }
            double x = cx * CS - ox, y = cy * CS - oy;
            // the diamond's A = x - y spans [x - y - 8, x - y + 8], B = x + y spans [x + y, x + y + 16]
            if (x - y + CS < aMin - 1 || x - y - CS > aMax + 1 || x + y + 2 * CS < bMin - 1 || x + y > bMax + 7) {
               continue;
            }
            byte[] mats = GroundState.surfaces(cx, cy, now);
            int k = f.n++;
            int[] m = f.mat[k];
            java.util.Arrays.fill(m, 0);
            boolean any = false;
            for (int i = 0; i < 64; i++) {
               m[i >> 3] |= (mats[i] & 15) << ((i & 7) * 4);
               any |= mats[i] != 0;
            }
            if (!any) {
               f.n--;
               continue;
            }
            f.cx[k] = (float)x;
            f.cy[k] = (float)y;
            f.uvX[k] = ((cx * CS * TPS) & (TN - 1)) / (float)TN;
            f.uvY[k] = ((cy * CS * TPS) & (TN - 1)) / (float)TN;
         }
      }
      if (f.n == 0) {
         f.release();
         return;
      }
      compFrames++;
      compChunks += f.n;
      SpriteRenderer.instance.drawGeneric(f);
   }

   static long compFrames, compChunks;

   static final class Comp extends TextureDraw.GenericDrawer {
      static final int MAX = 64;
      private static final ArrayList<Comp> cpool = new ArrayList<>();
      final Bake light = new Bake();
      final float[] cx = new float[MAX], cy = new float[MAX], uvX = new float[MAX], uvY = new float[MAX];
      final int[][] mat = new int[MAX][8];
      int n, ts, ox, oy, screenW, screenH;
      float zoom, offX, offY, d0;

      static Comp alloc() {
         synchronized (cpool) {
            return cpool.isEmpty() ? new Comp() : cpool.remove(cpool.size() - 1);
         }
      }

      void release() {
         synchronized (cpool) {
            if (cpool.size() < 4) {
               cpool.add(this);
            }
         }
      }

      @Override
      public void render() {
         GroundTracks.renderComposite(this);
      }

      @Override
      public void postRender() {
         release();
      }
   }

   static final String COMP_VERT = String.join("\n",
      "uniform vec4 uMapA;", // x - y = uMapA.x * px + uMapA.y, x + y = uMapA.z * py + uMapA.w (window px, relative to the origin square)
      "uniform vec4 uVp;", // viewport x, y, w, h
      "uniform vec4 uC;", // the chunk's corner relative to the origin (x, y), the origin's floor depth, the depth per unit of x + y
      "uniform float uBias;", // squares of x + y toward the camera
      "out vec2 vP;",
      "void main() {",
      "   int c = gl_VertexID;",
      "   vec2 p = vec2((c == 1 || c == 3) ? 8.0 : 0.0, (c >= 2) ? 8.0 : 0.0);",
      "   vec2 w = uC.xy + p;",
      "   float A = w.x - w.y, B = w.x + w.y;",
      "   vec2 px = vec2((A - uMapA.y) / uMapA.x, (B - uMapA.w) / uMapA.z);",
      "   vec2 ndc = (px - uVp.xy) / uVp.zw * 2.0 - 1.0;",
      "   float d = uC.z - (B + uBias) * uC.w;",
      "   gl_Position = vec4(ndc, 2.0 * d - 1.0, 1.0);",
      "   vP = p;",
      "}",
      "");

   private static final java.util.HashMap<String, int[]> COMP_PROGRAMS = new java.util.HashMap<>();
   private static int compProg;
   private static int cMapA, cVp, cC, cBias, cState, cUv, cMat, cLight, cWeather, cSky;
   private static int compFbo = -1, compFboLast = Integer.MIN_VALUE, compAge;
   private static final int[] VPI = new int[4];

   private static boolean initComposite() {
      String h = header();
      int[] cached = COMP_PROGRAMS.get(h);
      if (cached == null) {
         int p = link(h + COMP_VERT, bakeFrag(h), "ground tracks composite");
         cached = new int[] {p};
         COMP_PROGRAMS.put(h, cached);
         if (p != 0) {
            Log.info("ground tracks: composite program for " + h.replace("\n", " ").replace("#define ", ""));
         }
      }
      compProg = cached[0];
      if (compProg == 0) {
         return false;
      }
      cMapA = GL20.glGetUniformLocation(compProg, "uMapA");
      cVp = GL20.glGetUniformLocation(compProg, "uVp");
      cC = GL20.glGetUniformLocation(compProg, "uC");
      cBias = GL20.glGetUniformLocation(compProg, "uBias");
      cState = GL20.glGetUniformLocation(compProg, "uState");
      cUv = GL20.glGetUniformLocation(compProg, "uUv");
      cMat = GL20.glGetUniformLocation(compProg, "uMat");
      cLight = GL20.glGetUniformLocation(compProg, "uLight");
      cWeather = GL20.glGetUniformLocation(compProg, "uWeather");
      cSky = GL20.glGetUniformLocation(compProg, "uSky");
      if (vao == 0) {
         vao = GL30.glGenVertexArrays();
      }
      return true;
   }

   static void renderComposite(Comp f) {
      if (failed || GroundState.texture() == 0 || !initComposite()) {
         return;
      }
      GpuSections.markNow("ground.comp", false);
      try {
         // the viewport of the world framebuffer: asked once per framebuffer (and every 120 frames), never per frame
         int last = zombie.core.textures.TextureFBO.lastID;
         if (compFbo < 0 || last != compFboLast || ++compAge > 120) {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
            compFbo = 1;
            compFboLast = last;
            compAge = 0;
         }
         double vx = VPI[0], vy = VPI[1], vw = Math.max(1, VPI[2]), vh = Math.max(1, VPI[3]);
         double sxPerPx = f.screenW / vw, syPerPx = f.screenH / vh;
         double a32 = 32.0 * f.ts, a16 = 16.0 * f.ts;
         float kA = (float)(sxPerPx * f.zoom / a32);
         float cA = (float)(((-vx * sxPerPx) * f.zoom + f.offX) / a32 - (f.ox - f.oy));
         float kB = (float)(-syPerPx * f.zoom / a16);
         float cB = (float)((((vy + vh) * syPerPx) * f.zoom + f.offY) / a16 - (f.ox + f.oy));
         GL20.glUseProgram(compProg);
         GL20.glUniform4f(cMapA, kA, cA, kB, cB);
         GL20.glUniform4f(cVp, (float)vx, (float)vy, (float)vw, (float)vh);
         GL20.glUniform1f(cBias, Config.DEV_GROUND_TRACKS_COMP_BIAS_PCT / 100F);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, GroundState.texture());
         GL20.glUniform1i(cState, 3);
         Bake l = f.light;
         GL20.glUniform4f(cLight, l.lx, l.ly, l.lz, l.share);
         GL20.glUniform4f(cWeather, l.wet, l.snowDepth, Config.DEV_GROUND_TRACKS_VIEW, 0F);
         GL20.glUniform3f(cSky, l.skyR, l.skyG, l.skyB);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthFunc(GL11.GL_LEQUAL);
         GL11.glDepthMask(false);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR, GL11.GL_ZERO, GL11.GL_ONE);
         GL11.glColorMask(true, true, true, false);
         GL30.glBindVertexArray(vao);
         for (int i = 0; i < f.n; i++) {
            GL20.glUniform4f(cC, f.cx[i], f.cy[i], f.d0, (float)PixelLight.DEPTH_PER_XY);
            GL20.glUniform4f(cUv, f.uvX[i], f.uvY[i], TPS / (float)TN, 0F);
            INTS.clear();
            INTS.put(f.mat[i], 0, 8).flip();
            GL20.glUniform4iv(cMat, INTS);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
         }
         GL30.glBindVertexArray(0);
      } catch (Throwable t) {
         failed = true;
         Log.warn("ground tracks: composite failed, off: " + t);
      } finally {
         GL11.glColorMask(true, true, true, true);
         GL20.glUseProgram(0);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         Texture.lastTextureID = -1;
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         GpuSections.markNow("ground.comp", true);
      }
   }

   // ------------------------------------------------------------------------------------------------ water in the ruts, by the puddle pass

   /**
    * GLSL for the puddle unit (Ripples.patchText includes it; GLSL 1.20): {@code pzGtLevel(fragCoord, levelf)} raises the
    * puddle level where a rut is deeper than the water the ground holds, so the game's own puddle draws its water there, with
    * the ripples, the raindrop rings, the wakes and the reflections of the rest of the puddles.
    */
   public static String puddleGlsl() {
      return GroundState.GLSL + String.join("\n",
         "uniform vec4 pzGtW; // the world square of the view's px (0, 0) at level 0, 1 / (32 tile scale), on (0: off)",
         "uniform vec4 pzGtWet; // x the ground's wetness 0..1, y the water level below the rest (squares) at it",
         "float pzGtLevel(vec2 fc, float levelf) {",
         "   if (pzGtW.w < 0.5 || puddlesZCoordinate > 0.5) return levelf;",
         "   vec2 d = fc * WOffset.zw * pzGtW.z;",
         "   vec2 w = pzGtW.xy + vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5);",
         "   float depth = -pzgStateAt(w).x;",
         "   float a = smoothstep(pzGtWet.y, pzGtWet.y + 0.012, depth) * clamp(pzGtWet.x * 1.5, 0.0, 1.0);",
         "   return max(levelf, a * 0.36 / max(puddlesSize, 0.05)); // 0.35: the stock's full reflection",
         "}",
         "");
   }

   private static final java.util.HashMap<Integer, int[]> PUDDLE_LOCS = new java.util.HashMap<>();
   static volatile float puddleWet;
   static long puddleDraws;

   /** Render thread, the puddle program bound (Ripples.puddleUniforms): the rut water's uniforms, the ground map on its unit. */
   public static void puddleUniforms(int prog) {
      int[] l = PUDDLE_LOCS.get(prog);
      if (l == null) {
         l = new int[] {GL20.glGetUniformLocation(prog, "pzGtW"), GL20.glGetUniformLocation(prog, "pzGtWet")};
         PUDDLE_LOCS.put(prog, l);
      }
      if (l[0] < 0) {
         return;
      }
      float wetNow = puddleWet;
      if (failed || !on() || !"puddles".equals(water(Ground.variant())) || wetNow <= 0F || !GroundState.bindTexture()) {
         GL20.glUniform4f(l[0], 0F, 0F, 0F, 0F);
         return;
      }
      GroundState.applyUniforms(prog);
      org.joml.Vector4f off = zombie.iso.IsoPuddles.getInstance().getShaderOffset();
      double a = off.x / (32.0 * Core.tileScale), b = off.y / (16.0 * Core.tileScale);
      GL20.glUniform4f(l[0], (float)((a + b) * 0.5), (float)((b - a) * 0.5), 1F / (32F * Core.tileScale), 1F);
      GL20.glUniform4f(l[1], wetNow, 0.035F + (0.012F - 0.035F) * wetNow, 0F, 0F);
      puddleDraws++;
   }
}
