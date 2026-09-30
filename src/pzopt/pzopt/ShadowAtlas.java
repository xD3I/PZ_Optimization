package pzopt;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import zombie.core.Core;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.TextureDraw;

/**
 * Per-object sun shadow maps of the characters and animals (Config {@code sunShadowAtlas}... {@code sunShadowMeshes}):
 * right after a caster's model is drawn into the world, the same draw runs again from the sun into the caster's own
 * 128 x 128 tile of a 2048 x 2048 depth atlas, an orthographic view along the sun centred on the caster's bounding
 * capsule. The caster shadow pass ({@link CapsuleShadow}) then looks each receiver up in the caster's tile (a
 * percentage-closer soft shadow: the blocker's distance gives the penumbra's width): the shadow of everything the
 * model draws (limbs, hair, clothes, bags, weapons, an animal's legs and tail), seen from the sun, not the camera.
 *
 * <p>The model draws its parts with the game's own shaders and state (ModelSlotRenderData.render): the atlas framebuffer
 * has a depth attachment only, the tracked GL state (GLStateRenderThread, re-applied after every part) is set for the
 * depth render and put back after. {@link SunCamera} replaces the character camera for that draw: the sun's view of the
 * model's own world frame (the one Core.DoPushIsoStuff builds: x west, y up, z north, squares) instead of the isometric
 * one. The model's depth offset (ModelSlotRenderData.squareDepth, turned into the shaders' targetDepth) is set so the
 * offset is none.
 *
 * <p>Order on the render thread (the sprite stream): {@link Begin} (queued with the frame's caster pass) clears the atlas
 * and takes the frame's sun; each DrawModel with a tile (TextureDraw.pzoptShadowTile) renders its tile; the caster pass
 * (queued after the moving objects) samples the atlas.
 */
public final class ShadowAtlas {
   static final int SIZE = 2048;
   static final int TILE = 128;
   static final int PER_ROW = SIZE / TILE;
   static final int MAX_TILES = PER_ROW * PER_ROW;
   /** The depth range of a tile along the sun: +-DEPTH squares from the caster's centre. */
   static final float DEPTH = 4.0F;

   private static volatile boolean failed;
   private static int fbo;
   private static int depthTex;
   private static int samplerRaw;
   private static int samplerCmp;
   private static boolean frameOn; // render thread: the frame's Begin ran and the atlas is clear
   /** Render thread: this frame's rotation from the model's world frame to the sun's view (rows: across, up, towards the sun). */
   static final float[] R = new float[9];
   private static final SunCamera CAMERA = new SunCamera();
   private static long rendered;
   private static long renderNs;
   private static long frames;
   private static long drawNs; // (the model's own draw of the parts, inside renderNs)
   private static long setupNs, clearNs, restoreNs, flushes; // a flush's own steps
   private static long parts;

   private ShadowAtlas() {
   }

   static String stats() {
      return "shadow atlas: frames=" + frames + " casters drawn=" + rendered + (rendered > 0 ? String.format(java.util.Locale.ROOT, " (%.1f us render thread each, %.1f us of it the model's draw, %.1f parts a caster; a flush's setup %.1f clear %.1f restore %.1f us, %.2f draws a flush)", renderNs / 1e3 / rendered, drawNs / 1e3 / rendered, parts / (double)rendered, setupNs / 1e3 / Math.max(1, flushes), clearNs / 1e3 / Math.max(1, flushes), restoreNs / 1e3 / Math.max(1, flushes), rendered / (double)Math.max(1, flushes)) : "") + (lampViews > 0 ? " lamp views=" + lampViews : "") + (failed ? " FAILED" : "");
   }

   static boolean usable() {
      return !failed && Config.SUN_SHADOW_MESHES;
   }

   /** Render thread: every GL entry point init() and the pass use exists in this context. */
   private static boolean glSupported() {
      org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
      return (c.OpenGL33 || c.GL_ARB_sampler_objects)
         && (c.OpenGL31 || c.GL_ARB_draw_instanced)
         && (c.OpenGL30 || c.GL_ARB_vertex_array_object && c.GL_ARB_framebuffer_object && c.GL_ARB_depth_buffer_float);
   }

   /** The rotation taking the model's world frame (x west, y up, z north; squares) to the sun's view, for the sun (world: x east, y south, z up). */
   static void rotation(float sx, float sy, float sz, float[] r) {
      // the sun in the model's world frame: x = -east, y = up, z = -south
      float lx = -sx, ly = sz, lz = -sy;
      float ll = (float)Math.sqrt(lx * lx + ly * ly + lz * lz);
      lx /= ll;
      ly /= ll;
      lz /= ll;
      // across: horizontal, perpendicular to the sun (up x L); a sun straight up takes x
      float ax = lz, ay = 0F, az = -lx;
      float al = (float)Math.sqrt(ax * ax + az * az);
      if (al < 1e-4F) {
         ax = 1F;
         az = 0F;
         al = 1F;
      }
      ax /= al;
      az /= al;
      // up in the view: L x across
      float ux = ly * az - lz * ay, uy = lz * ax - lx * az, uz = lx * ay - ly * ax;
      r[0] = ax;
      r[1] = ay;
      r[2] = az;
      r[3] = ux;
      r[4] = uy;
      r[5] = uz;
      r[6] = lx;
      r[7] = ly;
      r[8] = lz;
   }

   /** Queued by CapsuleShadow.queue on the game thread: clears the atlas and takes the frame's sun on the render thread. */
   static final class Begin extends TextureDraw.GenericDrawer {
      float sx;
      float sy;
      float sz;
      boolean on;

      @Override
      public void render() {
         frameOn = false;
         recycle(); // a frame whose pass did not run (dev alternation): its queue held slots released since
         if (!this.on || failed) {
            return;
         }
         try {
            if (fbo == 0 && !glSupported()) {
               // LWJGL aborts the JVM on a GL function the context lacks (no exception to catch): macOS's GL 2.1 context
               // has no sampler objects, so glGenSamplers in init() ended the game on world entry with sun shadows on
               failed = true;
               Log.warn("shadow atlas: needs OpenGL 3.3 or its extensions (sampler objects, instanced draws), this context is "
                  + GL11.glGetString(GL11.GL_VERSION) + "; off");
               return;
            }
            if (fbo == 0 && !init()) {
               failed = true;
               Log.warn("shadow atlas: setup failed; the characters' shadows stay capsules");
               return;
            }
            rotation(this.sx, this.sy, this.sz, R);
            frameOn = true; // (tiles keep their pose from frame to frame; a draw clears its own tile)
            frames++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("shadow atlas: " + t + "; off for the rest of the session");
         }
      }
   }

   private static final Begin[] BEGINS = {new Begin(), new Begin(), new Begin(), new Begin()};
   private static int beginIndex;

   /** Game thread (CapsuleShadow.queue): the drawer that starts this frame's atlas. */
   static Begin begin(float sx, float sy, float sz, boolean on) {
      Begin b = BEGINS[beginIndex++ & 3];
      b.sx = sx;
      b.sy = sy;
      b.sz = sz;
      b.on = on;
      return b;
   }

   // ------------------------------------------------------------------------------------------------ the caster's draw

   private static int cFbo = -1, cFboLast, cAge;

   /**
    * The framebuffer bound now, read back once and again only when the game binds another (TextureFBO.lastID) or every 120
    * calls (GodRays' cache): a glGet on the render thread waits for NVIDIA's threaded driver to drain.
    */
   static int worldFbo() {
      int last = zombie.core.textures.TextureFBO.lastID;
      if (cFbo < 0 || last != cFboLast || ++cAge > 120) {
         cFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         cFboLast = last;
         cAge = 0;
      }
      return cFbo;
   }
   private static Field outlineField;
   private static boolean outlineTried;

   /** A caster's sun draw waiting for the frame's flush: its slot (valid until the frame's postRender) and camera. */
   private static final class Pending {
      ModelSlotRenderData slot;
      int tile;
      boolean vehicle;
      boolean lamp; // a lamp's view (sunShadowLampMeshes): a perspective from lx, ly, lz (world, z metric) at the tile's centre
      float cx, cy, cz, half, camX, camY, camZ, angle, lx, ly, lz;
   }

   private static final java.util.ArrayList<Pending> PENDING = new java.util.ArrayList<>();
   private static final java.util.ArrayList<Pending> POOL = new java.util.ArrayList<>();

   /**
    * Render thread, TextureDraw's DrawModel right after the model's own draw: the caster's sun draw is queued (its slot and
    * the character camera's position and facing now); {@link #flush} draws the frame's queue in one bind of the atlas.
    * cx, cy, cz: the tile's centre (world squares, z metric); half: the tile's half size in squares.
    */
   public static void renderCaster(TextureDraw texd, int tile, float cx, float cy, float cz, float half) {
      if (!frameOn || failed || tile < 0 || tile >= MAX_TILES || !(texd.drawer instanceof ModelSlotRenderData slot)) {
         return;
      }
      ModelCamera cam = ModelCamera.instance;
      if (cam == null || cam.inVehicle || slot.renderToTexture) {
         return;
      }
      Pending p = POOL.isEmpty() ? new Pending() : POOL.remove(POOL.size() - 1);
      p.slot = slot;
      p.tile = tile;
      p.cx = cx;
      p.cy = cy;
      p.cz = cz;
      p.half = half;
      p.camX = cam.x;
      p.camY = cam.y;
      p.camZ = cam.z;
      p.angle = cam.useAngle;
      p.vehicle = slot.character == null;
      p.lamp = false;
      PENDING.add(p);
   }

   /**
    * Render thread, TextureDraw's DrawModel after the sun's: the caster's lamp views (sunShadowLampMeshes) queued like its
    * sun draw. texd.pzoptLampDraw holds per view: tile, centre x, y, z, half size, lamp x, y, z (world squares, z metric).
    */
   public static void renderLamps(TextureDraw texd) {
      if (!frameOn || failed || !(texd.drawer instanceof ModelSlotRenderData slot) || texd.pzoptLampDraw == null) {
         return;
      }
      ModelCamera cam = ModelCamera.instance;
      if (cam == null || cam.inVehicle || slot.renderToTexture || slot.character == null) {
         return;
      }
      float[] v = texd.pzoptLampDraw;
      for (int j = 0; j < texd.pzoptLampN; j++) {
         int o = j * 8;
         int tile = (int)v[o];
         if (tile < 0 || tile >= MAX_TILES) {
            continue;
         }
         Pending p = POOL.isEmpty() ? new Pending() : POOL.remove(POOL.size() - 1);
         p.slot = slot;
         p.tile = tile;
         p.cx = v[o + 1];
         p.cy = v[o + 2];
         p.cz = v[o + 3];
         p.half = v[o + 4];
         p.lx = v[o + 5];
         p.ly = v[o + 6];
         p.lz = v[o + 7];
         p.camX = cam.x;
         p.camY = cam.y;
         p.camZ = cam.z;
         p.angle = cam.useAngle;
         p.vehicle = false;
         p.lamp = true;
         PENDING.add(p);
         lampViews++;
      }
   }

   private static long lampViews;
   private static int devLate, devFull;

   /**
    * A lamp view's frustum round a caster's bounding sphere (radius half, its centre dist from the lamp): tan of the half
    * angle, near, far. The caster pass (CapsuleShadow's lampVis) computes the same from the same numbers.
    */
   static float lampTan(float dist, float half) {
      return half / (float)Math.sqrt(Math.max(dist * dist - half * half, 1e-4F));
   }

   /**
    * Render thread, the caster pass (after the frame's moving objects, before their slots are released): the queued sun
    * draws in one bind of the atlas (a render target switch per caster cost the GPU ~30 us each, sh-cost-mesh4): the tiles
    * cleared by one instanced draw at the far depth, then each model drawn from the sun into its tile. No glGet: the
    * world framebuffer from the cache, the viewport (the upscaler's jittered one included) and the scissor box on the
    * attribute stack.
    */
   static void flush(boolean midWorld) {
      if (PENDING.isEmpty()) {
         return;
      }
      if (failed || !frameOn) {
         recycle();
         return;
      }
      long t0 = System.nanoTime();
      // after the world pass the game's own tracked binding (TextureFBO.lastID) is the one to put back: no glGet (a query
      // here missed the mid-world cache every frame, a ~90 us wait for the threaded driver, sh-cost-mesh7)
      // in the middle of the world (sunShadowMeshSameFrame, before the caster pass) the bound framebuffer can be one the
      // game's tracking does not know (the upscaler's): the pass's own cache of it
      int previousFbo = midWorld ? worldFbo() : zombie.core.textures.TextureFBO.lastID;
      ModelCamera prev = ModelCamera.instance;
      GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
      Tracked saved = Tracked.save();
      OccludedOutline.pauseCapture(); // Shadow-view depths are not world-camera occluders.
      try {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GLStateRenderThread.ScissorTest.set(false);
         GLStateRenderThread.StencilTest.set(false);
         GLStateRenderThread.DepthTest.set(true);
         GLStateRenderThread.DepthMask.set(true);
         GLStateRenderThread.ColorMask.set(false, false, false, false);
         GLStateRenderThread.Blend.set(false);
         // the tiles' last poses out: one instanced quad per tile at the far depth (depth test always)
         GLStateRenderThread.DepthFunc.set(GL11.GL_ALWAYS);
         GL11.glViewport(0, 0, SIZE, SIZE);
         long c0 = System.nanoTime();
         setupNs += c0 - t0;
         clearTiles();
         clearNs += System.nanoTime() - c0;
         GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
         for (int i = 0; i < PENDING.size(); i++) {
            Pending p = PENDING.get(i);
            GL11.glViewport((p.tile % PER_ROW) * TILE, (p.tile / PER_ROW) * TILE, TILE, TILE);
            float squareDepth = p.slot.squareDepth;
            boolean outline = outline(p.slot, false);
            CAMERA.setUp(p, p.slot);
            ModelCamera.instance = CAMERA;
            long r0 = System.nanoTime();
            try {
               synchronized (p.slot) {
                  p.slot.render(); // the model's parts, from the sun
               }
            } finally {
               p.slot.squareDepth = squareDepth;
               outline(p.slot, outline);
            }
            drawNs += System.nanoTime() - r0;
            parts += p.slot.getModelData().size();
            rendered++;
            if (Config.DEV_SHADOW_ATLAS_DUMP > 0 && (rendered <= 6 || p.lamp || frames >= Config.DEV_SHADOW_ATLAS_DUMP - 1 && devLate++ < 16)) {
               // dev: what the draw left in its tile (a read back: a sync, dev only)
               java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(TILE * TILE);
               GL11.glReadPixels((p.tile % PER_ROW) * TILE, (p.tile / PER_ROW) * TILE, TILE, TILE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, px);
               float mn = 1F, mx = 0F;
               int drawn = 0;
               for (int k = 0; k < TILE * TILE; k++) {
                  mn = Math.min(mn, px.get(k));
                  if (px.get(k) < 0.9999F) {
                     drawn++;
                     mx = Math.max(mx, px.get(k));
                  }
               }
               if (p.lamp && (drawn > TILE * TILE / 3 || frames >= Config.DEV_SHADOW_ATLAS_DUMP - 1 && devLate < 16) && devFull++ < 24) {
                  float ldx = p.lx - p.cx, ldy = p.ly - p.cy, ldz = p.lz - p.cz;
                  Log.info(String.format(java.util.Locale.ROOT, "shadow atlas: dev lamp view tile %d: lamp %.2f,%.2f,%.2f from the centre (dist %.2f, half %.2f), depth %.4f..%.4f over %d texels, %s, %d parts",
                     p.tile, ldx, ldy, ldz, (float)Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz), p.half, mn, mx, drawn,
                     p.slot.character == null ? "?" : p.slot.character.getClass().getSimpleName(), p.slot.getModelData().size()));
               }
               if (!(p.lamp && rendered > 6)) Log.info("shadow atlas: dev draw " + rendered + " tile " + p.tile + (p.vehicle ? " (vehicle)" : "") + " half " + p.half + " at " + p.cx + "," + p.cy + ","
                  + p.cz + ": origin ndc z " + CAMERA.lastOriginZ + ", Begin calls " + CAMERA.begins + ", GL error " + GL11.glGetError() + ", tile min depth " + mn
                  + ", texels drawn " + drawn + ", " + p.slot.getModelData().size() + " parts");
            }
         }
      } finally {
         long e0 = System.nanoTime();
         ModelCamera.instance = prev;
         saved.restore();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
         OccludedOutline.resumeCapture();
         GL11.glPopAttrib();
         GL11.glDepthRange(0.0, 1.0);
         recycle();
         restoreNs += System.nanoTime() - e0;
         flushes++;
      }
      renderNs += System.nanoTime() - t0;
   }

   /** Queued at the screen composite (CapsuleShadow.queueAtlasFlush): the frame's sun draws (none left after FLUSH_WORLD). */
   static final TextureDraw.GenericDrawer FLUSH = new Flush(false);
   /** Queued right before the caster pass (sunShadowMeshSameFrame): the frame's sun draws, read by the pass that follows. */
   static final TextureDraw.GenericDrawer FLUSH_WORLD = new Flush(true);

   private static final class Flush extends TextureDraw.GenericDrawer {
      private final boolean midWorld;

      Flush(boolean midWorld) {
         this.midWorld = midWorld;
      }

      @Override
      public void render() {
         try {
            flush(this.midWorld);
         } catch (Throwable t) {
            failed = true;
            recycle();
            Log.warn("shadow atlas: flush " + t + "; off for the rest of the session");
         }
      }
   }

   private static void recycle() {
      for (int i = 0; i < PENDING.size(); i++) {
         Pending p = PENDING.get(i);
         p.slot = null;
         POOL.add(p);
      }
      PENDING.clear();
   }

   private static int clearProgram;
   private static int clearVao;
   private static int uClearTiles;
   private static final java.nio.FloatBuffer CLEAR_TILES = org.lwjgl.BufferUtils.createFloatBuffer(256);

   /** The pending casters' tiles at the far depth: instanced quads, the tile of instance i from a uniform array. */
   private static void clearTiles() {
      if (clearProgram == 0) {
         clearProgram = AmbientOcclusion.link(CLEAR_VERT, CLEAR_FRAG);
         clearVao = GL30.glGenVertexArrays();
         uClearTiles = GL20.glGetUniformLocation(clearProgram, "tiles");
      }
      if (clearProgram == 0) {
         return;
      }
      int n = Math.min(256, PENDING.size());
      CLEAR_TILES.clear();
      for (int i = 0; i < n; i++) {
         CLEAR_TILES.put(PENDING.get(i).tile);
      }
      CLEAR_TILES.flip();
      GL20.glUseProgram(clearProgram);
      GL20.glUniform1fv(uClearTiles, CLEAR_TILES);
      GL30.glBindVertexArray(clearVao);
      org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, n);
      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      zombie.core.ShaderHelper.forgetCurrentlyBound();
   }

   private static final String CLEAR_VERT = String.join("\n",
      "#version 140",
      "uniform float tiles[256];",
      "void main() {",
      "   float t = tiles[gl_InstanceID];",
      "   int v = gl_VertexID;",
      "   vec2 k = vec2((v == 1 || v == 2) ? 1.0 : 0.0, (v >= 2) ? 1.0 : 0.0);",
      "   vec2 org = vec2(mod(t, " + PER_ROW + ".0), floor(t / " + PER_ROW + ".0)) / " + PER_ROW + ".0;",
      "   vec2 p = (org + k / " + PER_ROW + ".0) * 2.0 - 1.0;",
      "   gl_Position = vec4(p, 1.0, 1.0);", // the far plane: depth 1
      "}");

   private static final String CLEAR_FRAG = String.join("\n",
      "#version 140",
      "void main() {",
      "}");

   /** The slot's player outline flag (private): off for the sun draw, back after. Returns the value it had. */
   private static boolean outline(ModelSlotRenderData slot, boolean value) {
      try {
         if (!outlineTried) {
            outlineTried = true;
            outlineField = ModelSlotRenderData.class.getDeclaredField("characterOutline");
            outlineField.setAccessible(true);
         }
         if (outlineField == null) {
            return false;
         }
         boolean was = outlineField.getBoolean(slot);
         outlineField.setBoolean(slot, value);
         return was;
      } catch (Throwable t) {
         outlineField = null;
         return false;
      }
   }

   /**
    * The sun's camera for one caster's draw: an orthographic box of +-half squares across and +-DEPTH along the sun,
    * centred on the tile's centre, in the model's world frame as Core.DoPushIsoStuff places the model (then the same
    * model transform: the 1.5 scale, the character's facing, the -0.48 offset).
    */
   static final class SunCamera extends ModelCamera {
      private float half;
      private float ox; // the tile's centre in the model's world frame (from the character's position)
      private float oy;
      private float oz;
      private ModelSlotRenderData slot;
      private boolean vehicle;
      private boolean lamp;
      private float mlx, mly, mlz; // a lamp view: the lamp in the model's world frame, from the tile's centre
      private final Matrix4f scratch = new Matrix4f();
      float lastOriginZ;
      long begins;

      void setUp(Pending p, ModelSlotRenderData slot) {
         this.useAngle = p.angle;
         this.vehicle = p.vehicle;
         this.useWorldIso = true;
         this.inVehicle = false;
         this.x = p.camX;
         this.y = p.camY;
         this.z = p.camZ;
         this.depthMask = true;
         this.slot = slot;
         this.half = p.half;
         this.ox = -(p.cx - p.camX);
         this.oy = p.cz - p.camZ * 2.4494897F;
         this.oz = -(p.cy - p.camY);
         this.lamp = p.lamp;
         this.mlx = -(p.lx - p.cx);
         this.mly = p.lz - p.cz;
         this.mlz = -(p.ly - p.cy);
      }

      @Override
      public void Begin() {
         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         Matrix4f mv = Core.getInstance().modelViewMatrixStack.alloc();
         if (this.lamp) {
            // from the lamp at the caster's centre, the frustum just round its bounding sphere (lampTan)
            float dist = (float)Math.sqrt(this.mlx * this.mlx + this.mly * this.mly + this.mlz * this.mlz);
            float t = lampTan(dist, this.half);
            projection.setPerspective(2.0F * (float)Math.atan(t), 1.0F, Math.max(0.02F, dist - this.half), dist + this.half);
            boolean vertical = Math.abs(this.mly) > 0.99F * dist;
            mv.setLookAt(this.mlx, this.mly, this.mlz, 0F, 0F, 0F, 0F, vertical ? 0F : 1F, vertical ? 1F : 0F);
         } else {
            projection.setOrtho(-this.half, this.half, -this.half, this.half, -DEPTH, DEPTH);
            float[] r = R;
            // rows of R: view = R (w - o); joml's set takes the columns
            mv.set(r[0], r[3], r[6], 0F, r[1], r[4], r[7], 0F, r[2], r[5], r[8], 0F, 0F, 0F, 0F, 1F);
         }
         Core.getInstance().projectionMatrixStack.push(projection);
         mv.translate(-this.ox, -this.oy, -this.oz);
         if (this.vehicle) {
            mv.scale(-1.0F, 1.0F, 1.0F);
            mv.rotate(this.useAngle + (float)Math.PI, 0.0F, 1.0F, 0.0F);
         } else {
            mv.scale(-1.5F, 1.5F, 1.5F);
            mv.rotate(this.useAngle + (float)Math.PI, 0.0F, 1.0F, 0.0F);
            if (Config.DEV_SHADOW_ATLAS_DROP) {
               mv.translate(0.0F, -0.48F, 0.0F); // (dev: Core.DoPushIsoStuff's offset)
            }
         }
         Core.getInstance().modelViewMatrixStack.push(mv);
         // the shaders add targetDepth - 0.5, a character's targetDepth = squareDepth - (origin's depth + 1) / 2 + 0.5 - 1e-4,
         // a vehicle part's the same without the 1e-4, its origin moved up by centerOfMassY first (Model.DrawVehicle): none
         Matrix4f pv = this.scratch.set(projection).mul(mv);
         if (this.vehicle && this.slot != null) {
            pv.translate(0.0F, this.slot.centerOfMassY, 0.0F);
         }
         // (VertexBufferObject.getDepthValueAt: the origin's clip z, no divide; the ortho view's w is 1)
         float originZ = this.lamp ? pv.m32() : pv.m32() / pv.m33();
         this.lastOriginZ = originZ;
         this.begins++;
         if (this.slot != null) {
            this.slot.squareDepth = (originZ + 1.0F) / 2.0F + (this.vehicle ? 0.0F : 1.0E-4F);
         }
         GL11.glDepthRange(0.0, 1.0);
         GL11.glDepthMask(true);
      }

      @Override
      public void End() {
         Core.getInstance().projectionMatrixStack.pop();
         Core.getInstance().modelViewMatrixStack.pop();
      }
   }

   /** The tracked GL state (GLStateRenderThread) of the world pass, put back after a sun draw (its values are package-private). */
   private static final class Tracked {
      private static Field current;
      private static Field bVal;
      private static Field[] b4;
      private static Field iVal;
      private static boolean ok;
      private static boolean tried;
      private static final Tracked ONE = new Tracked();
      boolean scissor;
      boolean stencil;
      boolean depthTest;
      boolean depthMask;
      int depthFunc;
      boolean blend;
      final boolean[] color = new boolean[4];

      static Tracked save() {
         Tracked t = ONE;
         try {
            if (!tried) {
               tried = true;
               current = zombie.core.opengl.IOpenGLState.class.getDeclaredField("currentValue");
               current.setAccessible(true);
               bVal = Class.forName("zombie.core.opengl.GLState$CBooleanValue").getDeclaredField("value");
               bVal.setAccessible(true);
               iVal = Class.forName("zombie.core.opengl.GLState$CIntValue").getDeclaredField("value");
               iVal.setAccessible(true);
               Class<?> c4 = Class.forName("zombie.core.opengl.GLState$C4BooleansValue");
               b4 = new Field[] {c4.getDeclaredField("a"), c4.getDeclaredField("b"), c4.getDeclaredField("c"), c4.getDeclaredField("d")};
               for (Field f : b4) {
                  f.setAccessible(true);
               }
               ok = true;
            }
            if (ok) {
               t.scissor = bVal.getBoolean(current.get(GLStateRenderThread.ScissorTest));
               t.stencil = bVal.getBoolean(current.get(GLStateRenderThread.StencilTest));
               t.depthTest = bVal.getBoolean(current.get(GLStateRenderThread.DepthTest));
               t.depthMask = bVal.getBoolean(current.get(GLStateRenderThread.DepthMask));
               t.blend = bVal.getBoolean(current.get(GLStateRenderThread.Blend));
               t.depthFunc = iVal.getInt(current.get(GLStateRenderThread.DepthFunc));
               Object cv = current.get(GLStateRenderThread.ColorMask);
               for (int i = 0; i < 4; i++) {
                  t.color[i] = b4[i].getBoolean(cv);
               }
            }
         } catch (Throwable e) {
            ok = false;
            Log.warn("shadow atlas: tracked GL state unreadable: " + e);
         }
         return t;
      }

      void restore() {
         if (!ok) {
            GLStateRenderThread.restore();
            return;
         }
         GLStateRenderThread.ScissorTest.set(this.scissor);
         GLStateRenderThread.StencilTest.set(this.stencil);
         GLStateRenderThread.DepthTest.set(this.depthTest);
         GLStateRenderThread.DepthMask.set(this.depthMask);
         GLStateRenderThread.DepthFunc.set(this.depthFunc);
         GLStateRenderThread.Blend.set(this.blend);
         GLStateRenderThread.ColorMask.set(this.color[0], this.color[1], this.color[2], this.color[3]);
         GLStateRenderThread.restore();
      }
   }

   // ------------------------------------------------------------------------------------------------ the pass's side

   /** Render thread (the caster pass): the atlas on two units, raw depth and the depth compare. False: no atlas this frame. */
   static boolean bind(int rawUnit, int cmpUnit) {
      if (!frameOn || failed || depthTex == 0) {
         return false;
      }
      if (Config.DEV_SHADOW_ATLAS_DUMP > 0 && frames == Config.DEV_SHADOW_ATLAS_DUMP) {
         dump();
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + rawUnit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL33.glBindSampler(rawUnit, samplerRaw);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + cmpUnit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL33.glBindSampler(cmpUnit, samplerCmp);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   static void unbind(int rawUnit, int cmpUnit) {
      GL33.glBindSampler(rawUnit, 0);
      GL33.glBindSampler(cmpUnit, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + rawUnit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + cmpUnit);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   /** Dev (devShadowAtlasDump=N): the atlas of frame N as an 8-bit PGM (0 = nearest the sun, 255 = empty) in the cache dir. */
   private static void dump() {
      try {
         java.nio.FloatBuffer b = org.lwjgl.BufferUtils.createFloatBuffer(SIZE * SIZE);
         int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
         GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, b);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
         byte[] out = new byte[SIZE * SIZE];
         int filled = 0;
         for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
               float v = b.get((SIZE - 1 - y) * SIZE + x);
               out[y * SIZE + x] = (byte)Math.round(Math.max(0F, Math.min(1F, v)) * 255F);
               if (v < 0.9999F) {
                  filled++;
               }
            }
         }
         java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-shadow-atlas.pgm");
         try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
            os.write(("P5\n" + SIZE + " " + SIZE + "\n255\n").getBytes());
            os.write(out);
         }
         Log.info("shadow atlas: dumped frame " + frames + " (" + filled + " texels drawn, " + rendered + " casters drawn so far) to " + f);
      } catch (Throwable t) {
         Log.warn("shadow atlas: dump failed: " + t);
      }
   }

   private static boolean init() {
      int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      depthTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, SIZE, SIZE, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
      int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
      GL11.glDrawBuffer(GL11.GL_NONE);
      GL11.glReadBuffer(GL11.GL_NONE);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
         boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDepthMask(true);
         GL11.glClearDepth(1.0);
         GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
         if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
         }
         GLStateRenderThread.DepthMask.restore();
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("shadow atlas: framebuffer incomplete " + status);
         return false;
      }
      samplerRaw = GL33.glGenSamplers();
      GL33.glSamplerParameteri(samplerRaw, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL33.glSamplerParameteri(samplerRaw, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL33.glSamplerParameteri(samplerRaw, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL33.glSamplerParameteri(samplerRaw, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      samplerCmp = GL33.glGenSamplers();
      GL33.glSamplerParameteri(samplerCmp, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL33.glSamplerParameteri(samplerCmp, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL33.glSamplerParameteri(samplerCmp, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL33.glSamplerParameteri(samplerCmp, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL33.glSamplerParameteri(samplerCmp, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
      GL33.glSamplerParameteri(samplerCmp, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
      Log.info("shadow atlas: " + SIZE + "x" + SIZE + " depth, " + MAX_TILES + " tiles of " + TILE + "x" + TILE + " ready");
      return true;
   }
}
