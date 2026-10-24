package pzopt;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import zombie.GameTime;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

/**
 * Wind-blown leaves (ground cover, 2026-10-24, key {@code groundCoverWind}, off by default): in autumn wind a few leaves
 * lift off the covered ground and tumble downwind, hopping and spinning, and settle again. The baked cover is not touched
 * (a leaf that flies is one more leaf, not one taken from the picture): the world keeps its zero-cost bake, and this is
 * the only per-frame part of the ground cover.
 *
 * Game thread (Glints.queue's spot in FBORenderCell, after the floor and the items, before the puddles): at most
 * {@link #MAX} leaves simulated in world squares, spawned on squares that took cover within the view, more with more wind
 * and more fallen leaves; nothing at all (no draw queued) when the key is off, outside the leaf season or in calm air.
 * Render thread: one instanced draw of their quads (uniform arrays, no buffers), depth-tested against the floor at the
 * leaf's height (it hides behind what stands in front), lit by its square's light.
 */
public final class WindLeaves {
   private WindLeaves() {
   }

   static final int MAX = 32;
   private static final float[] x = new float[MAX], y = new float[MAX], h = new float[MAX], vx = new float[MAX], vy = new float[MAX];
   private static final float[] phase = new float[MAX], spin = new float[MAX], ang = new float[MAX], life = new float[MAX], age = new float[MAX];
   private static final float[] r = new float[MAX], g = new float[MAX], b = new float[MAX];
   private static int n;
   private static float spawnAcc;
   private static int rng = 0x2545f491;
   public static long frames, spawned, drawn;
   private static boolean failed;

   static boolean on() {
      return Config.GROUND_COVER_WIND && GroundCover.on() && !failed && !CoreGl.legacyMac();
   }

   private static float rnd() {
      rng ^= rng << 13;
      rng ^= rng >>> 17;
      rng ^= rng << 5;
      return (rng >>> 8) * (1F / 16777216F);
   }

   public static String stats() {
      return "wind leaves: frames=" + frames + " spawned=" + spawned + " drawn=" + drawn + (failed ? " (failed)" : "");
   }

   /** Game thread, once a frame at the floor's end: step the leaves, spawn new ones, queue the draw. */
   public static void queue(int playerIndex) {
      if (!on() || playerIndex != 0) {
         return;
      }
      try {
         step(playerIndex);
      } catch (RuntimeException e) {
         failed = true;
         Log.warn("wind leaves: failed, off: " + e);
      }
   }

   private static void step(int playerIndex) {
      ClimateManager cm = ClimateManager.getInstance();
      GroundCover.Season s = GroundCover.season;
      float kph = cm != null ? cm.getWindspeedKph() : 0F;
      if (Config.DEV_WIND_LEAVES_KPH >= 0) {
         kph = Config.DEV_WIND_LEAVES_KPH;
      }
      float leaves = Math.max(0F, s.leafFall - 0.1F) * (1F - s.rain); // wet leaves stay down
      boolean season = leaves > 0.05F && kph > 8F;
      if (!season && n == 0) {
         return;
      }
      float dt = GameTime.isGamePaused() ? 0F : Math.min(0.1F, GameTime.getInstance().getThirtyFPSMultiplier() / 30F);
      double toward = Math.toRadians((cm != null ? cm.getWindAngleDegrees() : 90F) - 45.0); // the bearing it blows to (CloudShadow)
      float wx = (float)Math.sin(toward), wy = (float)-Math.cos(toward);
      float speed = Math.max(0F, kph - 6F) / 3.6F * 0.35F; // squares a second along the ground (gusts slower than the wind)
      // step
      for (int i = 0; i < n; i++) {
         age[i] += dt;
         float gust = 0.6F + 0.8F * (0.5F + 0.5F * (float)Math.sin(age[i] * 2.3F + phase[i] * 6.28F));
         float k = Math.min(1F, age[i] * 2F) * Math.max(0F, 1F - Math.max(0F, age[i] - life[i] + 0.6F) / 0.6F);
         x[i] += (wx * speed * gust + vx[i]) * dt * k;
         y[i] += (wy * speed * gust + vy[i]) * dt * k;
         h[i] = k * (0.15F + 0.25F * speed / 3F) * Math.abs((float)Math.sin(age[i] * (2.2F + phase[i]) + phase[i] * 3F));
         ang[i] += spin[i] * dt * k;
         if (age[i] >= life[i]) {
            n--;
            copy(n, i);
            i--;
         }
      }
      // spawn: up to ~(leaf fall x wind) leaves a second near the player, on squares that took cover
      if (season) {
         spawnAcc += dt * leaves * Math.min(3F, (kph - 8F) / 12F) * 6F;
         float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
         zombie.iso.PlayerCamera cam = IsoCamera.cameras[playerIndex];
         float view = (cam.offscreenWidth + 2F * cam.offscreenHeight) * cam.zoom / (64F * Core.tileScale) * 0.5F;
         for (int tries = 0; spawnAcc >= 1F && n < MAX && tries < 8; tries++) {
            float px = cx + (rnd() * 2F - 1F) * view, py = cy + (rnd() * 2F - 1F) * view;
            IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare((int)Math.floor(px), (int)Math.floor(py), 0);
            if (sq == null || sq.chunk == null || !sq.isOutside() || !GroundCover.hasCover(sq.chunk, sq.x, sq.y)) {
               continue;
            }
            spawnAcc -= 1F;
            int i = n++;
            x[i] = px;
            y[i] = py;
            h[i] = 0F;
            vx[i] = (rnd() - 0.5F) * 0.4F;
            vy[i] = (rnd() - 0.5F) * 0.4F;
            phase[i] = rnd();
            spin[i] = (rnd() - 0.5F) * 9F;
            ang[i] = rnd() * 6.28F;
            age[i] = 0F;
            life[i] = 1.5F + 2.5F * rnd();
            float[] c = GroundCover.LEAF[(int)(rnd() * 4F) & 3];
            float old = s.leafAge * (0.4F + 0.6F * rnd());
            r[i] = c[0] + (GroundCover.OLD[0] - c[0]) * old;
            g[i] = c[1] + (GroundCover.OLD[1] - c[1]) * old;
            b[i] = c[2] + (GroundCover.OLD[2] - c[2]) * old;
            spawned++;
         }
         spawnAcc = Math.min(spawnAcc, 2F);
      }
      if (n == 0) {
         return;
      }
      frames++;
      // this frame's instances
      Frame f = FRAMES[frameIx++ & 3];
      int ts = Core.tileScale;
      float camZ = IsoCamera.frameState.camCharacterZ;
      int camCX = (int)Math.floor(IsoCamera.frameState.camCharacterX / 8F), camCY = (int)Math.floor(IsoCamera.frameState.camCharacterY / 8F);
      f.n = 0;
      for (int i = 0; i < n; i++) {
         if (camZ < 0F) {
            break; // under ground: no wind
         }
         int sxq = (int)Math.floor(x[i]), syq = (int)Math.floor(y[i]);
         IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(sxq, syq, 0);
         if (sq == null || !sq.isOutside()) {
            age[i] = Math.max(age[i], life[i] - 0.3F); // blown against a wall or indoors: it settles at once
            continue;
         }
         float lum = 1F;
         int l0 = sq.getVertLight(0, playerIndex), l2 = sq.getVertLight(2, playerIndex);
         lum = Math.min(1F, 0.5F * (Glints.luma(l0) + Glints.luma(l2)) * 1.6F);
         float k = Math.min(1F, age[i] * 3F) * Math.max(0F, Math.min(1F, (life[i] - age[i]) / 0.4F));
         int o = f.n * 12;
         float hz = h[i] / Glints.METRIC_Z; // levels
         f.d[o] = (x[i] - y[i]) * (32F * ts);
         f.d[o + 1] = (x[i] + y[i]) * (16F * ts) - hz * (96F * ts);
         f.d[o + 2] = IsoDepthHelper.getChunkDepthData(camCX, camCY, sxq >> 3, syq >> 3, 0).depthStart
            + IsoDepthHelper.calculateDepth(x[i], y[i], 0F) - hz * 0.0028867084F - BloodDecals.K * 2F;
         f.d[o + 3] = 0.11F * 64F * ts; // a leaf ~0.11 squares long
         f.d[o + 4] = (float)Math.cos(ang[i]);
         f.d[o + 5] = (float)Math.sin(ang[i]);
         f.d[o + 6] = k;
         f.d[o + 7] = 0.35F + 0.65F * Math.abs((float)Math.sin(ang[i] * 0.7F + phase[i] * 5F)); // the flat face turning to the camera
         f.d[o + 8] = r[i] * lum;
         f.d[o + 9] = g[i] * lum;
         f.d[o + 10] = b[i] * lum;
         f.d[o + 11] = phase[i];
         f.n++;
         drawn++;
      }
      if (f.n == 0) {
         return;
      }
      zombie.iso.PlayerCamera camera = IsoCamera.cameras[playerIndex];
      f.jx = camera.fixJigglyModelsX * camera.zoom;
      f.jy = camera.fixJigglyModelsY * camera.zoom;
      Pass p = PASSES[passIx++ & 3];
      p.frame = f;
      String sec = Ground.section("ground.wind");
      GpuSections.begin(sec);
      SpriteRenderer.instance.drawGeneric(p);
      GpuSections.end(sec);
   }

   private static void copy(int from, int to) {
      x[to] = x[from];
      y[to] = y[from];
      h[to] = h[from];
      vx[to] = vx[from];
      vy[to] = vy[from];
      phase[to] = phase[from];
      spin[to] = spin[from];
      ang[to] = ang[from];
      life[to] = life[from];
      age[to] = age[from];
      r[to] = r[from];
      g[to] = g[from];
      b[to] = b[from];
   }

   static final class Frame {
      final float[] d = new float[MAX * 12];
      int n;
      float jx, jy;
   }

   static final class Pass extends TextureDraw.GenericDrawer {
      Frame frame;

      @Override
      public void render() {
         Gpu.draw(this.frame);
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static final Pass[] PASSES = {new Pass(), new Pass(), new Pass(), new Pass()};
   private static int frameIx, passIx;

   // ------------------------------------------------------------------------------------------------ render thread

   static final String VERT = String.join("\n",
      "#version 330",
      "uniform mat4 uMVP;",
      "uniform vec2 uJ;",
      "uniform vec4 uA[" + MAX + "]; // screen x, y (absolute px), depth, size px",
      "uniform vec4 uB[" + MAX + "]; // cos, sin, alpha, face",
      "uniform vec4 uC[" + MAX + "]; // rgb (lit), phase",
      "out vec2 vUv;",
      "flat out vec4 vCol;",
      "flat out float vFace;",
      "void main() {",
      "   vec4 a = uA[gl_InstanceID], b = uB[gl_InstanceID], c = uC[gl_InstanceID];",
      "   int k = gl_VertexID;",
      "   vec2 q = vec2(k == 1 || k == 2 ? 1.0 : -1.0, k >= 2 ? 1.0 : -1.0);",
      "   vUv = q;",
      "   // the leaf's long axis turned by its spin; seen from the iso camera it is squashed by its tilt (face)",
      "   vec2 l = vec2(q.x, q.y * 0.5 * b.w) * a.w * 0.5;",
      "   vec2 p = vec2(b.x * l.x - b.y * l.y, b.y * l.x + b.x * l.y);",
      "   gl_Position = uMVP * vec4(a.xy + uJ + p, 0.0, 1.0);",
      "   gl_Position.z = clamp(a.z * 2.0 - 1.0, -1.0, 1.0) * gl_Position.w; // clamped here, no GL_DEPTH_CLAMP (Glints: gcv-cost-state)",
      "   vCol = vec4(c.rgb, b.z);",
      "   vFace = b.w;",
      "}");

   static final String FRAG = String.join("\n",
      "#version 330",
      "in vec2 vUv;",
      "flat in vec4 vCol;",
      "flat in float vFace;",
      "out vec4 fragColor;",
      "void main() {",
      "   // a pointed oval with a midrib: |v| under the leaf's half width (1 - u^2)^0.8",
      "   float u = vUv.x, v = vUv.y;",
      "   float w = pow(max(1.0 - u * u, 0.0), 0.8) * 0.95;",
      "   float d = abs(v) - w;",
      "   float aa = fwidth(d) + 1e-4;",
      "   float a = clamp(0.5 - d / aa, 0.0, 1.0) * vCol.a;",
      "   if (a <= 0.003) discard;",
      "   float rib = 1.0 - 0.25 * (1.0 - smoothstep(0.0, 0.12, abs(v)));",
      "   vec3 col = vCol.rgb * rib * (0.75 + 0.25 * vFace);",
      "   fragColor = vec4(col * a, a);",
      "}");

   static final class Gpu {
      private static int prog, vao, uMVP, uJ, uA, uB, uC;
      private static final FloatBuffer mat = BufferUtils.createFloatBuffer(16);
      private static final FloatBuffer va = BufferUtils.createFloatBuffer(MAX * 4), vb = BufferUtils.createFloatBuffer(MAX * 4), vc = BufferUtils.createFloatBuffer(MAX * 4);
      private static final Matrix4f mvp = new Matrix4f();

      static void warm() {
         if (Config.GROUND_COVER_WIND && Overrides.enabled()) {
            init();
         }
      }

      static boolean init() {
         if (prog == 0) {
            prog = Shaders.program("ground wind leaves", VERT, FRAG);
            if (prog == 0) {
               prog = -1;
               Log.warn("wind leaves: the program did not build, off");
               failed = true;
            } else {
               uMVP = GL20.glGetUniformLocation(prog, "uMVP");
               uJ = GL20.glGetUniformLocation(prog, "uJ");
               uA = GL20.glGetUniformLocation(prog, "uA");
               uB = GL20.glGetUniformLocation(prog, "uB");
               uC = GL20.glGetUniformLocation(prog, "uC");
               vao = GL30.glGenVertexArrays();
            }
         }
         return prog > 0;
      }

      static void draw(Frame f) {
         if (f == null || f.n == 0) {
            return;
         }
         try {
            if (!init()) {
               return;
            }
            zombie.core.ShaderHelper.glUseProgramObjectARB(prog);
            int pi = SpriteRenderer.instance.getRenderingPlayerIndex();
            zombie.iso.PlayerCamera cam = SpriteRenderer.instance.getRenderingPlayerCamera(pi);
            mvp.setOrtho(cam.getOffX(), cam.getOffX() + cam.offscreenWidth, cam.getOffY() + cam.offscreenHeight, cam.getOffY(), -1.0F, 1.0F);
            mat.clear();
            mvp.get(mat);
            GL20.glUniformMatrix4fv(uMVP, false, mat);
            GL20.glUniform2f(uJ, f.jx, f.jy);
            va.clear();
            vb.clear();
            vc.clear();
            for (int i = 0; i < f.n; i++) {
               int o = i * 12;
               va.put(f.d, o, 4);
               vb.put(f.d, o + 4, 4);
               vc.put(f.d, o + 8, 4);
            }
            va.flip();
            vb.flip();
            vc.flip();
            GL20.glUniform4fv(uA, va);
            GL20.glUniform4fv(uB, vb);
            GL20.glUniform4fv(uC, vc);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            String dv = Ground.variant();
            boolean clampHw = "wclamp".equals(dv); // dev: the hardware depth clamp of the first version
            if (clampHw) {
               GL11.glEnable(GL32.GL_DEPTH_CLAMP);
            }
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            if (!"wnodraw".equals(dv)) { // dev: everything but the draw call
               GL30.glBindVertexArray(vao);
               GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.n);
               GL30.glBindVertexArray(0);
            }
            if (clampHw) {
               GL11.glDisable(GL32.GL_DEPTH_CLAMP);
            }
         } catch (Throwable t) {
            failed = true;
            Log.warn("wind leaves: draw failed, off: " + t);
         } finally {
            zombie.core.ShaderHelper.glUseProgramObjectARB(0);
            Texture.lastTextureID = -1;
            GL11.glDepthMask(true);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GLStateRenderThread.restore();
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
      }
   }
}
