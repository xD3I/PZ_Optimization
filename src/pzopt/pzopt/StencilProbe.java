package pzopt;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * devStencilProbe: reads the whole stencil of the bound framebuffer on the render thread at named points of the frame
 * (after IsoCell.drawStencilMask, before the per-level loop, at a per-frame tree batch), every 2 s per point: how many
 * pixels carry the cutaway's tree bit (128), their box, the GL state at the mask draw. The framebuffer is screen-sized at
 * every zoom (the projection divides the offscreen coordinates by the zoom): never probe in offscreen pixels.
 * Dev only: glReadPixels stalls the pipeline.
 */
public final class StencilProbe extends TextureDraw.GenericDrawer {
   private static final ByteBuffer ONE = MemoryUtil.memAlloc(65536);
   private static final java.util.Map<String, int[]> SEEN = new java.util.TreeMap<>(); // point -> {reads, with bit 128, last value, last fbo}
   private static long nextLogMs;
   private static String lastState = "";

   private final String point;
   private final int x;
   private final int y;

   private StencilProbe(String point, int x, int y) {
      this.point = point;
      this.x = x;
      this.y = y;
   }

   /** Game thread: queue a read at this point of the sprite renderer's command stream (offscreen pixel x, y; y down). */
   public static void queue(String point, int x, int yDown, int offscreenHeight) {
      if (!Config.DEV_STENCIL_PROBE) {
         return;
      }
      SpriteRenderer.instance.drawGeneric(new StencilProbe(point, x, offscreenHeight - 1 - yDown));
   }

   /** Render thread, at once (inside another drawer). */
   public static void readNow(String point, int x, int yDown, int offscreenHeight) {
      new StencilProbe(point, x, offscreenHeight - 1 - yDown).render();
   }

   private static final java.util.Map<String, Long> NEXT_FULL = new java.util.HashMap<>();
   private static java.nio.ByteBuffer full;

   /** devStencilProbe: the whole stencil of the bound framebuffer, every 2 s: bit-128 pixel count and bounding box. */
   private void fullRead() {
      long now = System.currentTimeMillis();
      if (now < NEXT_FULL.getOrDefault(this.point, 0L)) {
         return;
      }
      NEXT_FULL.put(this.point, now + 2000L);
      int[] vp = new int[4];
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
      int w = vp[2], h = vp[3];
      if (full == null || full.capacity() < w * h) {
         full = MemoryUtil.memAlloc(w * h);
      }
      full.clear();
      GL11.glReadPixels(vp[0], vp[1], w, h, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, full);
      long n = 0;
      int x1 = w, y1 = h, x2 = -1, y2 = -1;
      int[] values = new int[256];
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int v = full.get(y * w + x) & 0xFF;
            values[v]++;
            if ((v & 128) != 0) {
               n++;
               if (x < x1) x1 = x;
               if (x > x2) x2 = x;
               if (y < y1) y1 = y;
               if (y > y2) y2 = y;
            }
         }
      }
      StringBuilder top = new StringBuilder();
      for (int v = 0; v < 256; v++) {
         if (values[v] > 0) top.append(v).append(':').append(values[v]).append(' ');
      }
      Log.info("stencil probe full " + this.point + ": viewport " + vp[0] + "," + vp[1] + " " + w + "x" + h + ", bit128 px=" + n
         + (n > 0 ? " bbox x " + x1 + ".." + x2 + " y(gl) " + y1 + ".." + y2 : "") + ", probe point " + this.x + "," + this.y
         + ", stencil writemask=" + GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK) + ", fbo=" + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
         + ", values " + top);
   }

   private static java.io.Writer perFrame;
   private static final ByteBuffer ROW = MemoryUtil.memAlloc(8192);

   /** Every call: the bit-128 count along a row and a column through the probe point (pzopt-stencil.out, 2026-10-25). */
   private int lineRead(int fbo) {
      int[] vp = new int[4];
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
      int rw = Math.min(1600, vp[2]);
      int rx = Math.max(vp[0], Math.min(vp[0] + vp[2] - rw, this.x - rw / 2));
      int ch = Math.min(900, vp[3]);
      int cy = Math.max(vp[1], Math.min(vp[1] + vp[3] - ch, this.y - ch / 2));
      int ry = Math.max(vp[1], Math.min(vp[1] + vp[3] - 1, this.y));
      int cx = Math.max(vp[0], Math.min(vp[0] + vp[2] - 1, this.x));
      ROW.clear();
      GL11.glReadPixels(rx, ry, rw, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, ROW);
      int row = 0;
      for (int i = 0; i < rw; i++) if ((ROW.get(i) & 128) != 0) row++;
      ROW.clear();
      GL11.glReadPixels(cx, cy, 1, ch, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, ROW);
      int col = 0;
      for (int i = 0; i < ch; i++) if ((ROW.get(i) & 128) != 0) col++;
      try {
         if (perFrame == null) {
            perFrame = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-stencil.out")));
            perFrame.write("# epoch_ms point row128 col128 fbo stencilTest writemask viewport\n");
         }
         perFrame.write(System.currentTimeMillis() + " " + this.point + " " + row + " " + col + " " + fbo + " " + (GL11.glIsEnabled(GL11.GL_STENCIL_TEST) ? 1 : 0)
            + " " + GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK) + " " + vp[2] + "x" + vp[3]
            + " depth=" + (GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0) + "/" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
            + " alpha=" + (GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 1 : 0) + "/" + GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC) + "/" + GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF)
            + " scissor=" + (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0) + " blend=" + (GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0)
            + " program=" + GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM) + " sfunc=" + GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
            + "/" + GL11.glGetInteger(GL11.GL_STENCIL_REF) + " sop=" + GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)
            + " depthClamp=" + (GL11.glIsEnabled(0x864F) ? 1 : 0) + "\n");
         if (this.point.startsWith("c")) perFrame.flush();
      } catch (java.io.IOException e) {
         // dev only
      }
      return row | col << 16;
   }

   @Override
   public void render() {
      this.fullRead();
      int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int v = this.lineRead(fbo);
      String state = " depthTest=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST) + " depthFunc=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
         + " alphaTest=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST) + " stencilTest=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
         + " program=" + GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM) + " depthClear=" + GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
      if (this.point.startsWith("a")) lastState = state;
      synchronized (SEEN) {
         int[] s = SEEN.computeIfAbsent(this.point, k -> new int[4]);
         s[0]++;
         if (v != 0) {
            s[1]++;
         }
         s[2] = v;
         s[3] = fbo;
         long now = System.currentTimeMillis();
         if (now >= nextLogMs) {
            nextLogMs = now + 2000L;
            StringBuilder sb = new StringBuilder("stencil probe:");
            for (java.util.Map.Entry<String, int[]> e : SEEN.entrySet()) {
               int[] t = e.getValue();
               sb.append(' ').append(e.getKey()).append(" reads=").append(t[0]).append(" frames with bit128=").append(t[1]).append(" last row px=").append(t[2] & 65535).append(" col px=").append(t[2] >>> 16).append(" fbo=").append(t[3]);
            }
            Log.info(sb.toString() + " | after mask:" + lastState);
         }
      }
   }
}
