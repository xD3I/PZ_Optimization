package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * The GL 4.3-4.6 API pass (2026-11-17, {@code docs/findings-gl-modern-2026-11-17.md}): what direct state access and
 * multi-draw indirect buy on the render thread, as measured keys (off by default; macOS stops at GL 4.1, so both need
 * {@link #available()} and fall back to the stock path).
 *
 * <p>{@code compositeMdi}: the chunk-level composite. Stock starts the chunk shader once per chunk-level texture (its
 * DEPTH sampler and chunkDepth uniforms, the pzopt hooks), sets blend / depth state, binds the colour and depth textures and
 * draws one quad from the sprite ring buffer, ~200 times a frame. Here the game thread collects one record per texture
 * (screen rectangle, texture coordinates, chunk depth, the two textures and the colour's filters) and queues one generic
 * drawer; the render thread uploads the records into a shader storage buffer and draws them with
 * {@code glMultiDrawArraysIndirect}, 16 textures per call (units 0-15 colour, 16-31 depth, bound with one
 * {@code glBindTextures} each; a static indirect buffer whose baseInstance is the record index, gl_DrawID picks the unit
 * pair). The colour's min / mag filters come from sampler objects (bound with {@code glBindSamplers}) so the textures' own
 * state stays what the stock path left; the depth samples with its own state like stock. Only when the composite is the
 * stock program (no per-pixel lighting, relief, reflections, sun / cloud shadows, sway, god rays, sprite filter).
 *
 * <p>{@code glDsa}: the vision-cone polygon (VisibilityPolygon2.Drawer.PolygonData.draw), the render thread's biggest
 * bind-to-edit site on the flip (~15 us a frame): bind, glBufferSubData, three enables, three attribute pointers, draw,
 * three disables, unbind. With DSA a VAO made once with {@code glCreateVertexArrays} / {@code glVertexArrayAttribFormat}
 * holds the layout; a frame is {@code glNamedBufferSubData}, bind VAO, draw, bind VAO 0.
 *
 * <p>{@code devGlAlternate=<ms>}: the keys listed in {@code devGlAlternateKeys} switch off and on (ABBA) within one run, and
 * the GPU sections {@code composite} / {@code vispoly} carry {@code .on} / {@code .off} ({@code gpuSections=true}: GPU
 * time and the render thread's time between the markers, {@code harness/glab.py}).
 */
public final class GlModern {
   private GlModern() {
   }

   private static final int K = Math.max(1, Math.min(16, Config.DEV_MDI_K)); // textures a multi-draw call (units 0..K-1 colour, K..2K-1 depth)
   private static final int REC_FLOATS = 12; // vec4 rect (x0, y0, x2, y2), vec4 uv (u start, v start, u end, v end), vec4 (chunkDepth, 0, 0, 0)
   private static final int MAX_RECS = 4096;
   // record buffer slots, one a frame round robin, written through a persistent coherent mapping: 8 slots against the
   // at most 4 frames in flight that persistentVboFrameSync (persistentVboSlots 4) allows, so no fence of our own.
   // A glNamedBufferSubData into it cost +227 us of GPU a frame on radeonsi (runs glm-ab1/2/k1): the upload into a buffer
   // still in use is staged and synchronised before the shader reads it.
   private static final int SLOTS = 8;

   // ---- availability ----
   private static volatile Boolean avail;
   private static volatile boolean probeQueued;

   /**
    * GL 4.5 + multi-bind + shader draw parameters. Decided on the render thread (the game thread has no GL context): a
    * game-thread call before that queues a probe drawer once and answers false.
    */
   public static boolean available() {
      Boolean a = avail;
      if (a != null) {
         return a;
      }
      GLCapabilities c;
      try {
         c = GL.getCapabilities();
      } catch (Throwable t) {
         c = null;
      }
      if (c == null) {
         if (!probeQueued && (Config.COMPOSITE_MDI || Config.GL_DSA || ALT)) {
            probeQueued = true;
            SpriteRenderer.instance.drawGeneric(new TextureDraw.GenericDrawer() {
               @Override
               public void render() {
                  available();
               }
            });
         }
         return false;
      }
      a = c.OpenGL45 && c.OpenGL44 && (c.OpenGL46 || c.GL_ARB_shader_draw_parameters) && !CoreGl.active;
      avail = a;
      Log.info("gl modern: GL 4.5 / multi-bind / draw parameters " + (a ? "available" : "not available") + "; compositeMdi=" + Config.COMPOSITE_MDI + " glDsa=" + Config.GL_DSA
         + (ALT ? ", alternating " + Config.DEV_GL_ALTERNATE_KEYS + " every " + Config.DEV_GL_ALTERNATE + " ms" : ""));
      return a;
   }

   // ---- the within-run alternation (game thread decides per frame) ----
   private static final boolean ALT = Config.DEV_GL_ALTERNATE > 0;
   private static final boolean ALT_MDI = ALT && ("," + Config.DEV_GL_ALTERNATE_KEYS.replace(" ", "") + ",").contains(",compositeMdi,");
   private static final boolean ALT_DSA = ALT && ("," + Config.DEV_GL_ALTERNATE_KEYS.replace(" ", "") + ",").contains(",glDsa,");
   private static long t0;
   private static int phaseFrame = Integer.MIN_VALUE;
   private static boolean phaseOff;

   /** Game thread: false in the off half of this frame (ABBA every devGlAlternate ms from the first call). */
   private static boolean phaseOn() {
      if (!ALT) {
         return true;
      }
      int f = zombie.iso.IsoWorld.instance == null ? 0 : zombie.iso.IsoWorld.instance.getFrameNo();
      if (f != phaseFrame) {
         phaseFrame = f;
         long now = System.currentTimeMillis();
         if (t0 == 0L) {
            t0 = now;
            Log.info("gl modern: alternating " + Config.DEV_GL_ALTERNATE_KEYS + " every " + Config.DEV_GL_ALTERNATE + " ms from epoch_ms " + t0 + " (on first)");
         }
         long period = (now - t0) / Config.DEV_GL_ALTERNATE;
         phaseOff = ((period + 1L) >> 1 & 1L) == 1L;
      }
      return !phaseOff;
   }

   /** Game thread: the composite goes through the multi-draw path this frame. */
   public static boolean mdiNow() {
      return (Config.COMPOSITE_MDI || ALT_MDI) && Overrides.enabled() && available() && stockComposite() && (!ALT_MDI || phaseOn());
   }

   /** Game thread: DSA for the vision cone this frame (the render thread follows the marker {@link #frameMarker} queues). */
   public static boolean dsaNow() {
      return (Config.GL_DSA || ALT_DSA) && Overrides.enabled() && available() && (!ALT_DSA || phaseOn());
   }

   /** A GPU section's name with this frame's half when its key alternates (unchanged otherwise). */
   public static String section(String base, boolean dsaSection) {
      if (dsaSection ? !ALT_DSA : !ALT_MDI) {
         return base;
      }
      return phaseOn() ? base + ".on" : base + ".off";
   }

   /** The chunk composite is the game's own program (none of the pzopt patches that rewrite it is active). */
   private static boolean stockComposite() {
      return !Config.PIXEL_LIGHT && !Config.RELIEF && !Config.SSR && !Config.SUN_SHADOWS && !Config.FOLIAGE_SWAY && !Config.GOD_RAYS
         && "stock".equals(Config.SPRITE_FILTER) && !Config.AO;
   }

   // ---- render-thread flag for glDsa, set in stream order ----
   private static volatile boolean rtDsa;
   private static final ArrayList<Flag> flagPool = new ArrayList<>();

   private static final class Flag extends TextureDraw.GenericDrawer {
      boolean dsa;

      @Override
      public void render() {
         rtDsa = this.dsa;
      }

      @Override
      public void postRender() {
         synchronized (flagPool) {
            flagPool.add(this);
         }
      }
   }

   /** Game thread, once a frame before the world draws: the render thread's DSA choice for this frame. */
   public static void frameMarker() {
      if (!(Config.GL_DSA || ALT_DSA)) {
         return;
      }
      Flag f;
      synchronized (flagPool) {
         f = flagPool.isEmpty() ? new Flag() : flagPool.remove(flagPool.size() - 1);
      }
      f.dsa = dsaNow();
      SpriteRenderer.instance.drawGeneric(f);
   }

   /** Render thread: the vision cone's upload / draw takes the DSA path. */
   public static boolean rtDsa() {
      return rtDsa;
   }

   // ---- glDsa: the vision-cone polygon ----
   private static int visVao;

   /**
    * Render thread: VisibilityPolygon2.Drawer.PolygonData.draw with direct state access. {@code buffer} is the polygon's
    * own GL buffer (made by the stock path, GL_DYNAMIC_DRAW, at least {@code data.limit()} floats); the layout (3 floats
    * position, 1, 1; stride 20) is the stock one, held by a VAO made once.
    */
   public static void visPolyDraw(int buffer, FloatBuffer data) {
      if (visVao == 0) {
         visVao = GL45.glCreateVertexArrays();
         GL45.glVertexArrayAttribFormat(visVao, 0, 3, GL11.GL_FLOAT, false, 0);
         GL45.glVertexArrayAttribFormat(visVao, 1, 1, GL11.GL_FLOAT, false, 12);
         GL45.glVertexArrayAttribFormat(visVao, 2, 1, GL11.GL_FLOAT, false, 16);
         for (int i = 0; i < 3; i++) {
            GL45.glVertexArrayAttribBinding(visVao, i, 0);
            GL45.glEnableVertexArrayAttrib(visVao, i);
         }
      }
      GL45.glNamedBufferSubData(buffer, 0L, data);
      GL45.glVertexArrayVertexBuffer(visVao, 0, buffer, 0L, 20); // the polygon's buffer can change (one per player / polygon)
      GL30.glBindVertexArray(visVao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, data.limit() / 5);
      GL30.glBindVertexArray(0);
      // the stock draw leaves VAO 0 with attributes 0-2 disabled and no array buffer bound: the same here, so the next
      // draws see the state they saw before
      GL20.glDisableVertexAttribArray(0);
      GL20.glDisableVertexAttribArray(1);
      GL20.glDisableVertexAttribArray(2);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      visDraws++;
   }

   private static long visDraws;

   // ---- compositeMdi: game thread ----
   private static final ArrayList<Batch> batchPool = new ArrayList<>();

   private static final class Batch extends TextureDraw.GenericDrawer {
      float[] recs = new float[64 * REC_FLOATS];
      Texture[] color = new Texture[64];
      Texture[] depth = new Texture[64];
      byte[] filter = new byte[64]; // bit 0 mipmapped min filter, bit 1 linear mag filter
      int n;

      void add(float x0, float y0, float x2, float y2, Texture tex, Texture depthTex, float chunkDepth, boolean mip, boolean magLinear) {
         if (this.n == this.color.length) {
            int cap = this.n * 2;
            this.recs = java.util.Arrays.copyOf(this.recs, cap * REC_FLOATS);
            this.color = java.util.Arrays.copyOf(this.color, cap);
            this.depth = java.util.Arrays.copyOf(this.depth, cap);
            this.filter = java.util.Arrays.copyOf(this.filter, cap);
         }
         float us = tex.getXStart();
         float ue = tex.getXEnd();
         if (tex.flip) { // the ring buffer swaps u0 / u1 and u2 / u3 of a flipped texture
            float t = us;
            us = ue;
            ue = t;
         }
         int o = this.n * REC_FLOATS;
         float[] r = this.recs;
         r[o] = x0;
         r[o + 1] = y0;
         r[o + 2] = x2;
         r[o + 3] = y2;
         r[o + 4] = us;
         r[o + 5] = tex.getYStart();
         r[o + 6] = ue;
         r[o + 7] = tex.getYEnd();
         r[o + 8] = chunkDepth;
         r[o + 9] = 0.0F;
         r[o + 10] = 0.0F;
         r[o + 11] = 0.0F;
         this.color[this.n] = tex;
         this.depth[this.n] = depthTex;
         this.filter[this.n] = (byte)((mip ? 1 : 0) | (magLinear ? 2 : 0));
         this.n++;
      }

      @Override
      public void render() {
         drawBatch(this);
      }

      @Override
      public void postRender() {
         java.util.Arrays.fill(this.color, 0, this.n, null);
         java.util.Arrays.fill(this.depth, 0, this.n, null);
         this.n = 0;
         synchronized (batchPool) {
            batchPool.add(this);
         }
      }
   }

   private static Batch building;

   /** Game thread: a new composite batch (FBORenderCell.pzoptCompositeMdi). */
   public static void begin() {
      synchronized (batchPool) {
         building = batchPool.isEmpty() ? new Batch() : batchPool.remove(batchPool.size() - 1);
      }
      building.n = 0;
   }

   /** Game thread: one chunk-level texture (the quad SpriteRenderer.render would have drawn). */
   public static void add(float x, float y, float w, float h, Texture tex, Texture depthTex, float chunkDepth, boolean mip, boolean magLinear) {
      building.add(x, y, x + w, y + h, tex, depthTex, chunkDepth, mip, magLinear);
   }

   /** Game thread: queue the batch (nothing when empty). */
   public static void end() {
      Batch b = building;
      building = null;
      if (b.n == 0) {
         synchronized (batchPool) {
            batchPool.add(b);
         }
         return;
      }
      SpriteRenderer.instance.drawGeneric(b);
      mdiFrames++;
      mdiRecs += b.n;
   }

   private static long mdiFrames, mdiRecs, mdiCalls, lastLogNs;

   // ---- compositeMdi: render thread ----
   private static IntBuffer directFirst, directCount;
   private static final boolean DIRECT = Config.DEV_MDI_DIRECT;
   private static final boolean NO_SAMPLER = Config.DEV_MDI_NO_SAMPLER;
   private static int paramLogs;
   private static int uRecBase;
   private static int program = -1, uMvp, vao, recBuf, cmdBuf, frameSlot;
   private static final int[] samplers = new int[4];
   private static FloatBuffer recMap;
   private static IntBuffer texIds, samplerIds;
   private static final Matrix4f MVP = new Matrix4f();
   private static final Matrix4f MV = new Matrix4f();
   private static final FloatBuffer MAT = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
   private static boolean failed;

   private static String vertexSource() {
      return "#version 460\n"
         + "struct Rec { vec4 rect; vec4 uv; vec4 misc; };\n"
         + "layout(std430, binding = 7) readonly buffer PzoptChunkRecs { Rec recs[]; };\n"
         + "uniform mat4 ModelViewProjection;\n"
         + "uniform int recBase;\n"
         + "out vec2 texCoord;\n"
         + "flat out int slot;\n"
         + "flat out float chunkDepth;\n"
         + "void main() {\n"
         + "   Rec r = recs[recBase + gl_DrawID];\n"
         + "   int c = gl_VertexID == 3 ? 0 : (gl_VertexID == 4 ? 2 : (gl_VertexID == 5 ? 3 : gl_VertexID));\n" // 0,1,2, 0,2,3: the ring buffer's two triangles
         + "   bool left = c == 0 || c == 3;\n"
         + "   bool top = c < 2;\n"
         + "   texCoord = vec2(left ? r.uv.x : r.uv.z, top ? r.uv.y : r.uv.w);\n"
         + "   slot = gl_DrawID;\n"
         + "   chunkDepth = r.misc.x;\n"
         + "   gl_Position = ModelViewProjection * vec4(left ? r.rect.x : r.rect.z, top ? r.rect.y : r.rect.w, 0.0, 1.0);\n"
         + "}\n";
   }

   private static String fragmentSource() {
      StringBuilder s = new StringBuilder("#version 460\n");
      s.append("uniform sampler2D DIFFUSE[").append(K).append("];\n");
      s.append("uniform sampler2D DEPTH[").append(K).append("];\n");
      s.append("in vec2 texCoord;\nflat in int slot;\nflat in float chunkDepth;\nlayout(location = 0) out vec4 fragColor;\n");
      if (Config.DEV_MDI_FS_FLAT) { // dev: no texture reads (wrong picture; splits per-pixel from per-draw cost)
         s.append("void main() {\n   vec4 c = vec4(0.5, 0.5, 0.5, 1.0) * float(slot + 1) / 16.0;\n   float d = 0.001;\n   {\n");
      } else if (K == 1) { // devMdiK=1: the stock composite's fetches, no switch
         s.append("void main() {\n   vec4 c = texture(DIFFUSE[0], texCoord, 0.0);\n   float d = texture(DEPTH[0], texCoord, 0.0).r;\n   {\n");
      } else {
         // derivatives once, outside the switch (textureGrad inside): run glm-ab1 had implicit-derivative fetches inside it,
         // glm-ab2 this form, same GPU cost
         s.append("void main() {\n   vec4 c = vec4(0.0);\n   float d = 0.0;\n   vec2 gx = dFdx(texCoord);\n   vec2 gy = dFdy(texCoord);\n");
         // a constant index per case: correct whatever invocations of different draws share a wave
         s.append("   switch (slot) {\n");
         for (int i = 0; i < K; i++) {
            s.append("   case ").append(i).append(": c = textureGrad(DIFFUSE[").append(i).append("], texCoord, gx, gy); d = textureGrad(DEPTH[").append(i)
               .append("], texCoord, gx, gy).r; break;\n");
         }
      }
      s.append("   }\n   gl_FragDepth = chunkDepth + d;\n   fragColor = c;\n}\n");
      return s.toString();
   }

   private static boolean init(Texture firstColor) {
      if (failed) {
         return false;
      }
      if (program >= 0) {
         return true;
      }
      try {
         program = Shaders.program("pzopt_chunkMdi", vertexSource(), fragmentSource());
         if (program <= 0) {
            throw new IllegalStateException("program did not link");
         }
         uMvp = GL20.glGetUniformLocation(program, "ModelViewProjection");
         uRecBase = GL20.glGetUniformLocation(program, "recBase");
         IntBuffer fb = ByteBuffer.allocateDirect(K * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
         IntBuffer c6 = ByteBuffer.allocateDirect(K * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < K; i++) {
            fb.put(0);
            c6.put(6);
         }
         directFirst = fb.flip();
         directCount = c6.flip();
         for (int i = 0; i < K; i++) {
            GL41Compat.programUniform1i(program, GL20.glGetUniformLocation(program, "DIFFUSE[" + i + "]"), i);
            GL41Compat.programUniform1i(program, GL20.glGetUniformLocation(program, "DEPTH[" + i + "]"), K + i);
         }
         vao = GL45.glCreateVertexArrays();
         recBuf = GL45.glCreateBuffers();
         long recSize = (long)SLOTS * MAX_RECS * REC_FLOATS * 4L;
         int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
         GL45.glNamedBufferStorage(recBuf, recSize, flags);
         ByteBuffer mapped = GL45.glMapNamedBufferRange(recBuf, 0L, recSize, flags);
         if (mapped == null) {
            throw new IllegalStateException("record buffer not mapped");
         }
         recMap = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();
         cmdBuf = GL45.glCreateBuffers();
         IntBuffer cmds = ByteBuffer.allocateDirect(MAX_RECS * 16).order(ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < MAX_RECS; i++) {
            cmds.put(6).put(1).put(0).put(0); // count, instanceCount, first, baseInstance (the record is recBase + gl_DrawID)
         }
         cmds.flip();
         GL45.glNamedBufferStorage(cmdBuf, cmds, 0);
         texIds = ByteBuffer.allocateDirect(2 * K * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
         samplerIds = ByteBuffer.allocateDirect(K * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
         // the colour's wrap / anisotropy as the chunk textures have them (asked once; all are made alike)
         int id = firstColor.getID();
         int wrapS = GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_WRAP_S);
         int wrapT = GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_WRAP_T);
         float aniso = GL.getCapabilities().GL_ARB_texture_filter_anisotropic ? GL45.glGetTextureParameterf(id, 0x84FE) : 1.0F;
         for (int f = 0; f < 4; f++) {
            int s = GL45.glCreateSamplers();
            GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MIN_FILTER, (f & 1) != 0 ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST);
            GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MAG_FILTER, (f & 2) != 0 ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_S, wrapS);
            GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_T, wrapT);
            if (aniso > 1.0F) {
               GL33.glSamplerParameterf(s, 0x84FE, aniso);
            }
            samplers[f] = s;
         }
         Log.info("gl modern: compositeMdi program " + program + ", " + K + " textures a multi-draw, colour wrap 0x" + Integer.toHexString(wrapS) + "/0x"
            + Integer.toHexString(wrapT) + " anisotropy " + aniso);
         return true;
      } catch (Throwable t) {
         failed = true;
         Log.warn("gl modern: compositeMdi off for the session: " + t);
         return false;
      }
   }

   /** glProgramUniform1i (GL 4.1 DSA for uniforms) kept apart so the class verifies on a 2.1 context. */
   private static final class GL41Compat {
      static void programUniform1i(int prog, int loc, int v) {
         if (loc >= 0) {
            org.lwjgl.opengl.GL41.glProgramUniform1i(prog, loc, v);
         }
      }
   }

   private static void drawBatch(Batch b) {
      int n = Math.min(b.n, MAX_RECS);
      // textures not created yet (deferred creation) are made by a bind, as the stock draw would
      for (int i = 0; i < n; i++) {
         if (b.color[i].getID() <= 0) {
            b.color[i].bind();
         }
         if (b.depth[i] != null && b.depth[i].getID() <= 0) {
            b.depth[i].bind();
         }
      }
      if (!init(b.color[0])) {
         return;
      }
      if (Config.INSTRUMENT && paramLogs < 3) { // what the textures carry (the stock draw samples them with it)
         paramLogs++;
         int c = b.color[0].getID(), d = b.depth[0] == null ? 0 : b.depth[0].getID();
         Log.info("gl modern: texture state colour min 0x" + Integer.toHexString(GL45.glGetTextureParameteri(c, GL11.GL_TEXTURE_MIN_FILTER)) + " mag 0x"
            + Integer.toHexString(GL45.glGetTextureParameteri(c, GL11.GL_TEXTURE_MAG_FILTER)) + " base " + GL45.glGetTextureParameteri(c, GL12.GL_TEXTURE_BASE_LEVEL)
            + " max level " + GL45.glGetTextureParameteri(c, GL12.GL_TEXTURE_MAX_LEVEL) + " max lod " + GL45.glGetTextureParameterf(c, GL12.GL_TEXTURE_MAX_LOD)
            + " size " + GL45.glGetTextureLevelParameteri(c, 0, GL11.GL_TEXTURE_WIDTH) + "x" + GL45.glGetTextureLevelParameteri(c, 0, GL11.GL_TEXTURE_HEIGHT)
            + " format 0x" + Integer.toHexString(GL45.glGetTextureLevelParameteri(c, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT))
            + "; record filter bits " + b.filter[0] + "; depth min 0x" + (d == 0 ? "-" : Integer.toHexString(GL45.glGetTextureParameteri(d, GL11.GL_TEXTURE_MIN_FILTER)))
            + " format 0x" + (d == 0 ? "-" : Integer.toHexString(GL45.glGetTextureLevelParameteri(d, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT))));
      }
      long t = System.nanoTime();
      // the stock draw's ModelViewProjection (VertexBufferObject.setModelViewProjection)
      Core core = Core.getInstance();
      if (core.modelViewMatrixStack.isEmpty()) {
         MVP.identity();
      } else {
         MV.set(core.modelViewMatrixStack.peek());
         MVP.set(core.projectionMatrixStack.peek()).mul(MV);
      }
      MVP.get(MAT);
      // records into this frame's slot
      long slotOff = (long)frameSlot * MAX_RECS * REC_FLOATS * 4L;
      recMap.clear().position(frameSlot * MAX_RECS * REC_FLOATS);
      recMap.put(b.recs, 0, n * REC_FLOATS);
      frameSlot = (frameSlot + 1) % SLOTS;
      GL20.glUseProgram(program);
      GL20.glUniformMatrix4fv(uMvp, false, MAT);
      GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 7, recBuf, slotOff, (long)n * REC_FLOATS * 4L);
      GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
      GL30.glBindVertexArray(vao);
      for (int g = 0; g < n; g += K) {
         int m = Math.min(K, n - g);
         texIds.clear();
         samplerIds.clear();
         for (int i = 0; i < K; i++) { // units of a short last group: 0 (unused by its draws)
            texIds.put(i < m ? b.color[g + i].getID() : 0);
            samplerIds.put(i < m ? samplers[b.filter[g + i]] : 0);
         }
         for (int i = 0; i < K; i++) {
            texIds.put(i < m && b.depth[g + i] != null ? b.depth[g + i].getID() : 0);
         }
         texIds.flip();
         samplerIds.flip();
         GL44.glBindTextures(0, texIds); // colour 0..K-1, depth K..2K-1: one call
         if (!NO_SAMPLER) {
            GL44.glBindSamplers(0, samplerIds);
         }
         GL20.glUniform1i(uRecBase, g);
         if (DIRECT) { // devMdiDirect: the same batch as a direct multi-draw (no indirect argument fetch)
            directFirst.limit(m);
            directCount.limit(m);
            GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, directFirst, directCount);
         } else {
            GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0L, m, 16);
         }
         mdiCalls++;
      }
      // leave the state the sprite ring buffer and VBORenderer expect (as FogPass does)
      GL30.glBindVertexArray(0);
      GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
      GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 7, 0);
      // the textures stay bound (the next draws bind what they read; a deleted texture unbinds itself); the samplers go,
      // they would override the textures' own filters for every later draw on units 0..K-1
      GL44.nglBindSamplers(0, K, 0L);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      ShaderHelper.forgetCurrentlyBound();
      ShaderHelper.glUseProgramObjectARB(0);
      GLStateRenderThread.restore();
      Texture.lastTextureID = 0;
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      SpriteRenderer.ringBuffer.shaderChangedTexture1();
      rtNs += System.nanoTime() - t;
      long now = System.nanoTime();
      if (Config.INSTRUMENT && now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info("gl modern: compositeMdi frames=" + mdiFrames + " textures=" + mdiRecs + " multi-draws=" + mdiCalls + " render-thread us/frame="
               + (mdiFrames == 0 ? 0 : rtNs / 1000L / mdiFrames) + "; glDsa vision-cone draws=" + visDraws);
         }
         lastLogNs = now;
         mdiFrames = mdiRecs = mdiCalls = rtNs = visDraws = 0L;
      }
   }

   private static long rtNs;
}
