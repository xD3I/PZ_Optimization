package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/**
 * Dev rig helper (horde round 2): prove that a batched draw leaves the same framebuffer as the stock draws it replaces.
 * {@link #save()} copies the bound draw framebuffer (colour + depth/stencil textures) to a scratch framebuffer before the
 * batch draws; {@link #compareWith(Runnable)} reads what the batch drew, puts the saved copy back, runs the stock draws,
 * reads again and counts the pixels whose colour or depth differ (the stock result stays in the framebuffer). Render
 * thread only, a full-frame readback twice (~0.1 s at 5K): callers run it every N-th time and stop it when slow.
 */
final class FbCheck {
   private static int fbo;
   private static int colour;
   private static int depth;
   private static int w;
   private static int h;
   private static int colourFmt;
   private static int depthFmt;
   private static int src;
   private static boolean disabled;
   private static ByteBuffer readA;
   private static ByteBuffer readB;
   private static FloatBuffer depthA;
   private static FloatBuffer depthB;
   private static final int PIXEL_PACK_BUFFER = 0x88EB;
   private static final int PIXEL_PACK_BUFFER_BINDING = 0x88ED;

   private FbCheck() {
   }

   /** Copies the bound draw framebuffer aside; false = not checkable (default framebuffer, renderbuffers, incomplete). */
   static boolean save() {
      if (disabled) {
         return false;
      }
      src = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      if (src == 0) {
         return false;
      }
      int cType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      int cName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      int dType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      int dName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      if (cType != GL11.GL_TEXTURE || dType != GL11.GL_TEXTURE) {
         return false;
      }
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, cName);
      int tw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
      int th = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
      int cf = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, dName);
      int df = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      if (cf == GL11.GL_RGBA) {
         cf = GL11.GL_RGBA8;
      } else if (cf == GL11.GL_RGB) {
         cf = GL11.GL_RGB8;
      }
      if (fbo == 0 || tw != w || th != h || cf != colourFmt || df != depthFmt) {
         if (fbo != 0) {
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(colour);
            GL11.glDeleteTextures(depth);
         }
         w = tw;
         h = th;
         colourFmt = cf;
         depthFmt = df;
         colour = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, colour);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, cf, w, h);
         depth = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, df, w, h);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
         int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
         fbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
         GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colour, 0);
         boolean ds = df == GL30.GL_DEPTH24_STENCIL8 || df == GL30.GL_DEPTH32F_STENCIL8;
         GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, ds ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
         int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Log.warn("framebuffer check: scratch framebuffer incomplete (0x" + Integer.toHexString(status) + "); the check is off");
            disabled = true;
            return false;
         }
         readA = ByteBuffer.allocateDirect(w * h * 4);
         readB = ByteBuffer.allocateDirect(w * h * 4);
         depthA = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
         depthB = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
      }
      blit(src, fbo);
      return true;
   }

   /**
    * After the batch drew: {drawn pixels (batch vs saved), colour diffs, depth diffs, box x0, y0, x1, y1} of the batch's
    * picture against {@code stock} drawn on the saved framebuffer.
    */
   static long[] compareWith(Runnable stock) {
      read(readB, depthB);
      blit(fbo, src);
      read(readA, depthA);
      long drawn = 0L;
      int n = w * h;
      for (int i = 0; i < n; i++) {
         if (readA.getInt(i * 4) != readB.getInt(i * 4) || Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i))) {
            drawn++;
         }
      }
      stock.run();
      read(readA, depthA);
      long cd = 0L, dd = 0L;
      int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
      for (int i = 0; i < n; i++) {
         boolean c = readA.getInt(i * 4) != readB.getInt(i * 4);
         boolean d = Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i));
         if (c) {
            cd++;
         }
         if (d) {
            dd++;
         }
         if (c || d) {
            int x = i % w, y = i / w;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
         }
      }
      return new long[]{drawn, cd, dd, x0, y0, x1, y1};
   }

   private static void blit(int from, int to) {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      boolean sc = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      boolean dm = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
      GL11.glDepthMask(true);
      int sm = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
      GL11.glStencilMask(0xFF);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
      GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT, GL11.GL_NEAREST);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
      GL11.glStencilMask(sm);
      GL11.glDepthMask(dm);
      if (sc) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
   }

   private static void read(ByteBuffer c, FloatBuffer d) {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevPack = GL11.glGetInteger(PIXEL_PACK_BUFFER_BINDING);
      GL15.glBindBuffer(PIXEL_PACK_BUFFER, 0);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      c.clear();
      GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, c);
      d.clear();
      GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, d);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL15.glBindBuffer(PIXEL_PACK_BUFFER, prevPack);
   }
}
