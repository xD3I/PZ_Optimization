package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * The horde's flat atlas zombies drawn in runs ({@code atlasBatch}, horde round 2, 2026-10-14).
 *
 * <p>A zombie without a 3D model is a {@code DeadBodyAtlas$BodyTextureDepthDrawer} command: one quad of the shared body
 * atlas with a depth texture. Stock draws each one as its own little scene: its depth texture bound on unit 1, the depth
 * state, a VBORenderer run (a buffer re-specified for four vertices, client states, the program started twice, the
 * matrices, two uniforms by name, the vertex pointers), then the unbinds, program 0 and a re-issue of the thirteen cached
 * GL states; the game thread puts a {@code StartShader(0)} command in front of each. ~200-500 of them in the Louisville
 * horde seconds cost ~1.3 ms of GPU a frame (devHordeModes a) and ~0.3 ms of the render thread.
 *
 * <p>Here a run of such commands (nothing between them but the StartShader(0) commands, which only select program 0 and
 * clear unit 1's enable, both overwritten by every quad) is drawn at its end, in the stock order, from one buffer upload:
 * the first quad under the state it would have met (its stock preamble), then one re-issue of the cached states (what
 * every quad after the first meets in stock, after the previous quad's tail) and its own depth state again, then per quad
 * only what changes: the textures when the atlas page changes, the two depth uniforms, the draw (GL_QUADS, the same four
 * vertices in the same order). The run ends with the stock tails, so everything after it finds the GL state the last
 * stock quad leaves. Same program, same uniforms, same vertices, same fixed-function state per quad: the same pixels.
 * Proof rig {@code devAtlasCheck=N}.
 *
 * <p>Only inside the world pass's moving objects (pzopt.HordeBatch's pass markers), GL 4.3, never on macOS / GL 2.1, and
 * only while the VBORenderer holds nothing of its own (else the stock render, which would flush that too).
 */
public final class AtlasBatch {
   private static final boolean DEV_MODES = !Config.DEV_HORDE_MODES.isEmpty();
   /** atlasBatch on (devHordeModes: only in its mode d frames, exact + atlas batch). */
   public static final boolean ON = Config.ATLAS_BATCH || DEV_MODES;
   private static final int MAX = 4096;
   private static final int FLOATS_PER_VERTEX = 9; // VBORenderer.formatPositionColorUv: position 3, colour 4, uv 2
   private static final int STRIDE = FLOATS_PER_VERTEX * 4;

   private AtlasBatch() {
   }

   /** Quads waiting for the run's end (render thread). */
   public static int pending;
   private static boolean failed;
   private static boolean initTried;
   private static int vao;
   private static int vbo;
   private static FloatBuffer verts;
   private static final float[] Q = new float[14];
   private static final Texture[] T = new Texture[2];
   private static final float[] depthNear = new float[MAX];
   private static final float[] depthFar = new float[MAX];
   private static final Texture[] tex = new Texture[MAX];
   private static final Texture[] depthTex = new Texture[MAX];
   private static final Object[] drawers = new Object[MAX];
   // stats
   private static long statRuns;
   private static long statQuads;
   private static long statStock;
   private static int statMaxRun;
   private static long lastLogNs;

   // the quads' vertices go straight into a persistent mapped buffer: four segments of MAX quads, one a moving pass in
   // turn behind a fence (the run's draw then needs no upload; a buffer re-specified per run cost more GPU than it saved)
   private static final int SEGS = 4;
   private static final long[] segFence = new long[SEGS];
   private static int seg = -1;
   private static long segPass = -1L;
   private static int segQuads; // quads written into the current segment this pass
   private static int runFirst; // the pending run's first quad, in the current segment
   private static long fenceWaits;

   /** renderPrewarm (f8-sight): the persistent vertex buffer made once the world exists instead of at the first atlas zombie. */
   public static void prewarm() {
      if (ON && Overrides.enabled()) {
         init();
      }
   }

   private static boolean init() {
      if (vao != 0) {
         return true;
      }
      if (initTried) {
         return false;
      }
      initTried = true;
      if (!GL.getCapabilities().OpenGL44 || Core.getInstance().getUseOpenGL21() || CoreGl.legacyMac() || CoreGl.active) {
         failed = true;
         Log.info("atlas batch: needs GL 4.4 (persistent buffers); the stock atlas draws stay");
         return false;
      }
      long bytes = (long)SEGS * MAX * 4 * STRIDE;
      int flags = GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
      vbo = GL15.glGenBuffers();
      vao = GL30.glGenVertexArrays();
      GL30.glBindVertexArray(vao);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      org.lwjgl.opengl.GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, bytes, flags);
      ByteBuffer m = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0L, bytes, flags);
      if (m == null) {
         GL30.glBindVertexArray(0);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         failed = true;
         Log.warn("atlas batch: the vertex buffer did not map; the stock atlas draws stay");
         return false;
      }
      verts = m.order(ByteOrder.nativeOrder()).asFloatBuffer();
      // VBORenderer.setVertexAttribArrays for formatPositionColorUv: floats, the colour flagged normalized (no effect on floats)
      GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, true, STRIDE, 12L);
      GL20.glEnableVertexAttribArray(1);
      GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 28L);
      GL20.glEnableVertexAttribArray(2);
      GL30.glBindVertexArray(0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      Log.info("atlas batch: on");
      return true;
   }

   /** The current pass's segment (a new pass takes the next one, waiting for its fence from four passes back). */
   private static boolean segment() {
      long pass = HordeBatch.passIndex();
      if (pass == segPass) {
         return segQuads < MAX;
      }
      segPass = pass;
      seg = (seg + 1) % SEGS;
      segQuads = 0;
      long f = segFence[seg];
      if (f != 0L) {
         int r = org.lwjgl.opengl.GL32.glClientWaitSync(f, 0, 0L);
         if (r != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED && r != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
            fenceWaits++;
            org.lwjgl.opengl.GL32.glClientWaitSync(f, org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 200_000_000L);
         }
         org.lwjgl.opengl.GL32.glDeleteSync(f);
         segFence[seg] = 0L;
      }
      return true;
   }

   /** Render thread, the end of the moving pass (pzopt.HordeBatch): the pass's last run and its segment's fence. */
   public static void endPass() {
      if (pending > 0 || HordeBatch.winFirst != 0) {
         flush();
      }
      if (seg >= 0 && segPass == HordeBatch.passIndex() && segQuads > 0 && segFence[seg] == 0L) {
         segFence[seg] = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      }
   }

   /**
    * Render thread, TextureDraw's DrawModel with a generic drawer, before anything else of it: true = taken (queued, or a
    * no-op as the stock render would be), false = the stock path.
    */
   public static boolean offer(TextureDraw texd) {
      if (!activeNow() || failed || !HordeBatch.inPassNow() || Sway.mrt || !Overrides.enabled() || !DeadBodyAtlas.pzoptIsAtlasDrawer(texd.drawer)) {
         return false; // (another drawer: beforeOp has decided)
      }
      int r = DeadBodyAtlas.pzoptAtlasQuad(texd.drawer, Q, T);
      if (r == 0) {
         if (pending > 0 || HordeBatch.winFirst != 0) {
            flush(); // the stock render of this one goes after what is pending
         }
         return false;
      }
      if (r == 1) {
         return true; // stock: nothing drawn, no GL call
      }
      boolean window = HordeBatch.windowNow();
      if ((pending == 0 || window) && !VBORenderer.getInstance().pzoptIdle() || !init() || !segment()) {
         if (pending > 0 || HordeBatch.winFirst != 0) {
            flush();
         }
         statStock++;
         return false;
      }
      VBORenderer vbor = VBORenderer.getInstance();
      float dx = vbor.pzoptDx(), dy = vbor.pzoptDy(), dz = vbor.pzoptDz();
      float x0 = Q[0], y0 = Q[1], x1 = Q[0] + Q[2], y1 = Q[1] + Q[3];
      if (window) {
         // the quad's screen rectangle (NDC) through the world pass's matrices (VertexBufferObject.setModelViewProjection's)
         Core core = Core.getInstance();
         if (core.modelViewMatrixStack.isEmpty()) {
            WMVP.identity();
         } else {
            core.projectionMatrixStack.peek().mul(core.modelViewMatrixStack.peek(), WMVP);
         }
         float ax0 = WMVP.m00() * (dx + x0) + WMVP.m10() * (dy + y0) + WMVP.m30();
         float ay0 = WMVP.m01() * (dx + x0) + WMVP.m11() * (dy + y0) + WMVP.m31();
         float ax1 = WMVP.m00() * (dx + x1) + WMVP.m10() * (dy + y1) + WMVP.m30();
         float ay1 = WMVP.m01() * (dx + x1) + WMVP.m11() * (dy + y1) + WMVP.m31();
         HordeBatch.placeQuad(Math.min(ax0, ax1), Math.min(ay0, ay1), Math.max(ax0, ax1), Math.max(ay0, ay1));
         HordeBatch.windowAddQuad(texd.drawer);
      }
      if (pending == 0) {
         runFirst = segQuads;
      }
      int k = pending;
      float u0 = Q[4], v0 = Q[5], u1 = Q[6], v1 = Q[7];
      int f = (seg * MAX + runFirst + k) * 4 * FLOATS_PER_VERTEX;
      // VBORenderer.addQuad(x1, y1, u0, v0, x1 + w, y1 + h, u1, v1, 0, r, g, b, a): (x0,y0) (x1,y0) (x1,y1) (x0,y1), offsets added
      f = vertex(f, dx + x0, dy + y0, dz + 0.0F, u0, v0);
      f = vertex(f, dx + x1, dy + y0, dz + 0.0F, u1, v0);
      f = vertex(f, dx + x1, dy + y1, dz + 0.0F, u1, v1);
      vertex(f, dx + x0, dy + y1, dz + 0.0F, u0, v1);
      depthNear[k] = Q[12];
      depthFar[k] = Q[13];
      tex[k] = T[0];
      depthTex[k] = T[1];
      drawers[k] = texd.drawer;
      pending++;
      segQuads++;
      return true;
   }

   private static int vertex(int f, float x, float y, float z, float u, float v) {
      FloatBuffer b = verts;
      b.put(f, x);
      b.put(f + 1, y);
      b.put(f + 2, z);
      b.put(f + 3, Q[8]);
      b.put(f + 4, Q[9]);
      b.put(f + 5, Q[10]);
      b.put(f + 6, Q[11]);
      b.put(f + 7, u);
      b.put(f + 8, v);
      return f + FLOATS_PER_VERTEX;
   }

   /** Render thread, TextureDraw.run of any command while quads are pending: a run ends at anything but StartShader(0) or another atlas quad. */
   public static void beforeOp(TextureDraw texd) {
      if (texd.type == TextureDraw.Type.StartShader && texd.a == 0 && texd.drawer == null) {
         return;
      }
      if (texd.type == TextureDraw.Type.DrawModel && texd.drawer != null) {
         Object d = texd.drawer;
         if (DeadBodyAtlas.pzoptIsAtlasDrawer(d)) {
            return; // the offer decides (an outline draw flushes there: it goes the stock way)
         }
         if (HordeBatch.winFirst != 0 && (d instanceof zombie.core.skinnedmodel.model.ModelSlotRenderData || d instanceof zombie.core.skinnedmodel.ModelCameraRenderData
            || d instanceof HordeDraw.Neutral || GpuSections.isMarker(d))) {
            return; // hordeBatchOrder=window: a zombie's camera record / model draw (HordeBatch.offer decides), a dev marker
         }
      }
      flush();
   }

   private static final org.joml.Matrix4f WMVP = new org.joml.Matrix4f();

   /** atlasBatch on for this frame (devHordeModes d / e). */
   static boolean activeNow() {
      return DEV_MODES ? HordeDraw.mode >= 13 && HordeDraw.mode <= 16 : ON;
   }

   /** Render thread, a sprite batch of the ring buffer is about to draw. */
   public static void beforeSprites() {
      flush();
   }

   public static void flush() {
      if (HordeBatch.winFirst != 0) {
         HordeBatch.windowFlush();
         return;
      }
      flushRun();
   }

   /** The pending run drawn now (the window draws its group with this). */
   static void flushRun() {
      int n = pending;
      if (n == 0) {
         return;
      }
      MovingProf.enter(MovingProf.ATLAS_FLUSH);
      pending = 0;
      boolean check = checkWanted(n);
      if (check) {
         check = FbCheck.save();
      }
      draw(n);
      if (check) {
         checkCompare(n);
      }
      for (int i = 0; i < n; i++) {
         tex[i] = null;
         depthTex[i] = null;
         drawers[i] = null;
      }
      statRuns++;
      statQuads += n;
      statMaxRun = Math.max(statMaxRun, n);
      MovingProf.exit();
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         lastLogNs = now;
         Log.info("atlas batch: runs=" + statRuns + " quads=" + statQuads + " maxRun=" + statMaxRun + " stock=" + statStock + " fenceWaits=" + fenceWaits
            + (Config.DEV_ATLAS_CHECK > 0 ? " check: runs=" + chkRuns + " vacuous=" + chkVacuous + " drawnPixels=" + chkDrawn + " colourDiff=" + chkColour + " depthDiff=" + chkDepth + " lastMs=" + chkLastMs : ""));
      }
   }

   private static void draw(int n) {
      ShaderProgram prog = DeadBodyAtlas.pzoptAtlasProgram();
      int firstVertex = (seg * MAX + runFirst) * 4; // the run's vertices are in the mapped buffer already
      // the first quad's stock preamble: BodyTextureDepthDrawer.render's depth texture and depth state
      GL13.glActiveTexture(33985);
      GL11.glEnable(3553);
      GL11.glBindTexture(3553, depthTex[0].getID());
      GL13.glActiveTexture(33984);
      GL11.glDepthMask(true);
      GL11.glDepthFunc(515);
      // VBORenderer.flush / startRun
      GL13.glActiveTexture(33984);
      GL11.glDisableClientState(32884);
      GL11.glDisableClientState(32886);
      GL11.glDisableClientState(32888);
      GL11.glEnable(2848);
      GL11.glEnable(3553);
      tex[0].getTextureId().bind();
      GL11.glEnable(2929);
      prog.Start();
      VertexBufferObject.setModelViewProjection(prog);
      GL11.glLineWidth(1.0F);
      int locNear = uniform(prog, "zDepthBlendZ");
      int locFar = uniform(prog, "zDepthBlendToZ");
      GL30.glBindVertexArray(vao);
      Texture boundTex = tex[0];
      Texture boundDepth = depthTex[0];
      for (int i = 0; i < n; i++) {
         if (i == 1) {
            // what every later quad meets in stock: the previous quad's tail re-issued the cached states, then its own
            // preamble set the depth mask / function and the run the depth test
            GLStateRenderThread.restore();
            GL11.glDepthMask(true);
            GL11.glDepthFunc(515);
            GL11.glEnable(2929);
         }
         if (depthTex[i] != boundDepth) {
            GL13.glActiveTexture(33985);
            GL11.glBindTexture(3553, depthTex[i].getID());
            GL13.glActiveTexture(33984);
            boundDepth = depthTex[i];
         }
         if (tex[i] != boundTex) {
            Texture.lastTextureID = -1;
            tex[i].getTextureId().bind();
            boundTex = tex[i];
         }
         if (locNear >= 0) {
            GL20.glUniform1f(locNear, depthNear[i]);
         }
         if (locFar >= 0) {
            GL20.glUniform1f(locFar, depthFar[i]);
         }
         GL11.glDrawArrays(GL11.GL_QUADS, firstVertex + i * 4, 4);
      }
      GL30.glBindVertexArray(0);
      // VBORenderer.flush's tail
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
      prog.End();
      GL11.glEnable(2929);
      GL11.glDisable(2848);
      for (int i = 0; i < 5; i++) {
         GL20.glEnableVertexAttribArray(i);
      }
      GL13.glActiveTexture(33984);
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      // BodyTextureDepthDrawer.render's tail
      GL13.glActiveTexture(33985);
      GL11.glBindTexture(3553, 0);
      GL11.glDisable(3553);
      GL13.glActiveTexture(33984);
      ShaderHelper.glUseProgramObjectARB(0);
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      GLStateRenderThread.restore();
   }

   private static int uniform(ShaderProgram prog, String name) {
      ShaderProgram.Uniform u = prog.getUniform(name, GL11.GL_FLOAT);
      return u == null ? -1 : u.loc;
   }

   // ---- dev check (devAtlasCheck=N): every N-th run of two or more quads drawn both ways and the framebuffers compared ----
   private static long chkRuns;
   private static long chkVacuous;
   private static long chkDrawn;
   private static long chkColour;
   private static long chkDepth;
   private static long chkLastMs;
   private static long chkIndex;
   private static boolean chkOff;

   private static boolean checkWanted(int n) {
      return Config.DEV_ATLAS_CHECK > 0 && n >= 2 && !chkOff && ++chkIndex % Config.DEV_ATLAS_CHECK == 0;
   }

   private static void checkCompare(int n) {
      long t0 = System.nanoTime();
      long[] r = FbCheck.compareWith(() -> {
         for (int i = 0; i < n; i++) {
            DeadBodyAtlas.pzoptAtlasRender(drawers[i]);
         }
      });
      chkRuns++;
      if (r[0] == 0L) {
         chkVacuous++;
      }
      chkDrawn += r[0];
      chkColour += r[1];
      chkDepth += r[2];
      if (r[1] != 0L || r[2] != 0L) {
         Log.info("atlas batch check: run of " + n + " quads: " + r[1] + " colour / " + r[2] + " depth pixels differ, box " + r[3] + "," + r[4] + "-" + r[5] + "," + r[6]);
      }
      chkLastMs = (System.nanoTime() - t0) / 1_000_000L;
      if (chkLastMs > 1000L) {
         chkOff = true; // a check slower than a second stops the rig
         Log.info("atlas batch check: " + chkLastMs + " ms, the check is off");
      }
   }
}
