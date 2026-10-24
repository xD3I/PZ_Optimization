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
         return Config.GROUND_TRACKS_DECAY_MINUTES / 60.0;
      }

      @Override
      public boolean rebakes() {
         return "bake".equals(Config.GROUND_TRACKS_LOOK);
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
         return on() && "bake".equals(Config.GROUND_TRACKS_LOOK);
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

   static {
      GroundBake.register(PASS);
      Ground.onFrame(GroundTracks::frameStart);
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   private static long lastFrameCount = -1L;
   private static long lastNanos;
   private static boolean rulesSet;
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
      GroundState.Sources src = GroundState.SOURCES;
      src.vehicles = Config.GROUND_TRACKS_VEHICLES;
      src.feet = Config.GROUND_TRACKS_FEET;
      src.bodies = Config.GROUND_TRACKS_BODIES;
      src.strengthPct = Config.GROUND_TRACKS_STRENGTH_PCT;
      wetness();
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

   private static boolean tried, ok;
   private static int bakeProg, vao;
   private static int bMVP, bGo, bState, bUv, bMat, bLight, bWeather, bSky;
   private static final java.nio.IntBuffer INTS = BufferUtils.createIntBuffer(8);
   private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
   private static final Matrix4f MVP = new Matrix4f();

   static String header() {
      return "#version 430\n#define GT_TPS " + TPS + ".0\n#define GT_TN " + TN + "\n#define GT_POM " + (Config.GROUND_TRACKS_POM ? 1 : 0) + "\n#define GT_SHADOW "
         + Config.GROUND_TRACKS_SHADOW_STEPS + "\n#define GT_AO " + (Config.GROUND_TRACKS_AO ? 1 : 0) + "\n";
   }

   private static boolean init() {
      if (tried) {
         return ok;
      }
      tried = true;
      String frag = header() + GroundTracksGlsl.BAKE_FRAG.replace("// (gt_look.glsl follows)", GroundTracksGlsl.LOOK);
      bakeProg = link(header() + GroundTracksGlsl.BAKE_VERT, frag, "ground tracks bake");
      if (bakeProg == 0) {
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
      vao = GL30.glGenVertexArrays();
      ok = true;
      Log.info("ground tracks: bake pass ready (map: GroundState, " + TPS + " texels a square)");
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
      GpuSections.begin("ground.bake");
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
         GpuSections.end("ground.bake");
      }
   }
}
