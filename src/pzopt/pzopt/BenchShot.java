package pzopt;

import java.io.File;

/**
 * The built-in benchmark's screenshots (technique 28: one per pass after the timed window; the screen checks'
 * dialogs): the finished frame read from the back buffer on the render thread right before the swap and written as a
 * PNG, a pass's into its run folder as shot-NN.png, the screen checks' to Zomboid/Screenshots. Core.TakeFullScreenshot
 * waits on the game thread for the render thread, which runs the job only once the frame hand-off moves on; called at a route end or from a UI draw callback it ran at the process exit,
 * after the world was gone (a black picture), or never (the flip, 2026-10-18).
 */
public final class BenchShot {
   private static volatile File pending;

   private BenchShot() {
   }

   /** Any thread: the next presented frame goes to {@code file} (a PNG). */
   static void request(File file) {
      pending = file;
   }

   /** Zomboid/Screenshots/{@code name}. */
   static File screenshots(String name) {
      return new File(new File(UserOptions.zomboidDir(), "Screenshots"), name);
   }

   /** Render thread, right before the swap (Pacing.beforeSwap). */
   static void beforeSwap() {
      File file = pending;
      if (file == null) {
         return;
      }
      pending = null;
      try {
         int w = zombie.core.Core.getInstance().getScreenWidth(), h = zombie.core.Core.getInstance().getScreenHeight();
         java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
         org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, 0);
         org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_BACK);
         org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
         org.lwjgl.opengl.GL11.glReadPixels(0, 0, w, h, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
         java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
         for (int y = 0; y < h; y++) {
            int row = (h - 1 - y) * w * 4; // GL rows run bottom-up
            for (int x = 0; x < w; x++) {
               int i = row + x * 4;
               img.setRGB(x, y, (px.get(i) & 0xff) << 16 | (px.get(i + 1) & 0xff) << 8 | (px.get(i + 2) & 0xff));
            }
         }
         file.getParentFile().mkdirs();
         javax.imageio.ImageIO.write(img, "png", file);
         Log.info("benchmark: screenshot " + file.getName() + " (" + file.getParent() + ")");
      } catch (Throwable t) {
         Log.warn("benchmark: screenshot " + file + " failed: " + t);
      }
   }
}
