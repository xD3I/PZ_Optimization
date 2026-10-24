import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Offline rig for grass blades (pzopt.GrassBlades): replays a devGrassDumpAt dump (the world colour + depth under the
 * blades, the view, the grass squares, the wind) in a hidden GLFW window, runs the same generation + draw
 * (GrassBlades.Gpu.run) and writes the picture and the GPU time, so the look and the cost iterate without the game.
 *
 *    java -Dpzopt.userOptionsFile=build/empty-options.ini -cp "build/classes:<game>/projectzomboid.jar" tools/GrassRig.java \
 *       <dump dir> <tag> <out prefix> [--knobs "key:value;..."] [--frames N] [--crop W] [--scale S] [--time S] [--off]
 *
 * Writes <out>-full.png (quarter size), <out>-crop.png (W x W/2 px around the player at 1:1, scaled S times) and prints
 * the GPU time of the generation + draw (median of N frames; the background is restored by a blit outside the timing).
 */
public class GrassRig {
   public static void main(String[] a) throws Exception {
      Path dir = Path.of(a[0]);
      String tag = a[1];
      String out = a[2];
      String knobs = "";
      int frames = 60, crop = 900, scale = 2;
      float time = -1F;
      boolean off = false, trail = false;
      int load = 1;
      for (int i = 3; i < a.length; i++) {
         switch (a[i]) {
            case "--knobs" -> knobs = a[++i];
            case "--frames" -> frames = Integer.parseInt(a[++i]);
            case "--crop" -> crop = Integer.parseInt(a[++i]);
            case "--scale" -> scale = Integer.parseInt(a[++i]);
            case "--time" -> time = Float.parseFloat(a[++i]);
            case "--off" -> off = true;
            case "--trail" -> trail = true;
            case "--load" -> load = Integer.parseInt(a[++i]);
            default -> throw new IllegalArgumentException(a[i]);
         }
      }
      HashMap<String, String> v = new HashMap<>();
      for (String line : Files.readAllLines(dir.resolve(tag + "-view.txt"))) {
         int eq = line.indexOf('=');
         if (eq > 0) {
            v.put(line.substring(0, eq), line.substring(eq + 1));
         }
      }
      int w = Integer.parseInt(v.get("w")), h = Integer.parseInt(v.get("h")), vx = Integer.parseInt(v.get("vx")), vy = Integer.parseInt(v.get("vy"));
      if (!GLFW.glfwInit()) {
         throw new IllegalStateException("glfwInit");
      }
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
      long win = GLFW.glfwCreateWindow(64, 64, "grassrig", 0, 0);
      GLFW.glfwMakeContextCurrent(win);
      GL.createCapabilities();
      System.out.println("GL " + GL11.glGetString(GL11.GL_RENDERER));
      int fw = w + vx, fh = h + vy;
      // the pristine world (source of each frame's blit) and the target
      int[] src = fbo(fw, fh), dst = fbo(fw, fh);
      byte[] cb = Files.readAllBytes(dir.resolve(tag + "-color.bin"));
      ByteBuffer cbuf = BufferUtils.createByteBuffer(cb.length).put(cb).flip();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, src[1]);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, vx, vy, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cbuf);
      byte[] dbytes = Files.readAllBytes(dir.resolve(tag + "-depth.bin"));
      FloatBuffer dbuf = BufferUtils.createFloatBuffer(w * h).put(ByteBuffer.wrap(dbytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()).flip();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, src[2]);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, vx, vy, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, dbuf);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      byte[] sqb = Files.readAllBytes(dir.resolve(tag + "-squares.bin"));
      int n = sqb.length / 4;
      int[] squares = new int[n];
      ByteBuffer.wrap(sqb).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(squares);

      pzopt.GrassBlades.Gpu.rig = true;
      pzopt.GrassBlades.Params p = pzopt.GrassBlades.rigParams();
      p.colorTex = src[1]; // the generation reads the pristine world (the game reads the target before drawing into it)
      p.depthTex = src[2];
      p.vp[0] = vx;
      p.vp[1] = vy;
      p.vp[2] = w;
      p.vp[3] = h;
      String[] mk = {"kA", "cA", "kB", "cB", "kC", "cC"};
      for (int i = 0; i < 6; i++) {
         p.map[i] = Float.parseFloat(v.get(mk[i]));
      }
      p.squares = squares;
      p.n = n;
      p.ox = Integer.parseInt(v.get("ox"));
      p.oy = Integer.parseInt(v.get("oy"));
      p.cx = Float.parseFloat(v.get("cx"));
      p.cy = Float.parseFloat(v.get("cy"));
      p.zoom = Float.parseFloat(v.get("zoom"));
      p.ts = Float.parseFloat(v.get("ts"));
      p.sunX = Float.parseFloat(v.get("sunX"));
      p.sunY = Float.parseFloat(v.get("sunY"));
      p.sunZ = Float.parseFloat(v.get("sunZ"));
      p.windOn = Boolean.parseBoolean(v.get("windOn"));
      p.amp = Float.parseFloat(v.get("amp"));
      p.lean = Float.parseFloat(v.get("lean"));
      p.dirX = Float.parseFloat(v.get("dirX"));
      p.osc = Float.parseFloat(v.get("osc"));
      p.gustU = Float.parseFloat(v.get("gustU"));
      p.gustV = Float.parseFloat(v.get("gustV"));
      String[] ph = v.get("ph").split(",");
      for (int i = 0; i < 4; i++) {
         p.ph[i] = Float.parseFloat(ph[i]);
      }
      if (time >= 0F) {
         // the wind advanced by `time` seconds (grass oscillator 1.3 Hz, gusts drifting at the dump's wind)
         p.ph[0] = (float)((p.ph[0] + time * 2.0 * Math.PI * 1.3) % (2.0 * Math.PI));
         p.gustU = (float)(((p.gustU - p.dirX * time * 3.0 / 8.0) % 32.0 + 32.0) % 32.0);
      }
      p.o.apply(knobs);
      if (trail) {
         // a walker's trail along x through the player (trampled 5 s ago), a cut patch north-east of him (60 s ago)
         java.util.ArrayList<float[]> st = new java.util.ArrayList<>();
         for (float x = -5F; x <= 5F; x += 0.25F) {
            st.add(new float[] {p.ox + p.cx + x, p.oy + p.cy + 1.5F, 0.32F, 0F});
         }
         for (float x = 2F; x <= 4F; x += 0.4F) {
            st.add(new float[] {p.ox + p.cx + x, p.oy + p.cy - 3F, 10.45F, 0F});
         }
         p.stamps = new float[st.size() * 4];
         for (int i = 0; i < st.size(); i++) {
            System.arraycopy(st.get(i), 0, p.stamps, i * 4, 4);
         }
         p.nStamps = Math.min(st.size(), 64);
         p.now = 100F;
      }
      int[] q = new int[2];
      GL15.glGenQueries(q);
      long[] ns = new long[frames];
      int warm = 400; // the GPU ramps its clocks first (idle P5 ~400 MHz)
      for (int f0 = -warm; f0 < frames; f0++) {
         int f = Math.max(0, f0);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src[0]);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dst[0]);
         for (int k = 0; k < load; k++) {
            GL30.glBlitFramebuffer(0, 0, fw, fh, 0, 0, fw, fh, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST); // keeps the clocks up (outside the timing)
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dst[0]);
         GL11.glViewport(vx, vy, w, h);
         GL33.glQueryCounter(q[0], GL33.GL_TIMESTAMP);
         if (!off) {
            if (trail && f0 == -warm + 1) {
               p.nStamps = 0; // stamped once (at now = 100 s); the frames read it 5 s later
               p.now = 105F;
            }
            pzopt.GrassBlades.Gpu.run(p);
         }
         GL33.glQueryCounter(q[1], GL33.GL_TIMESTAMP);
         if (f0 >= 0) ns[f] = GL33.glGetQueryObjecti64(q[1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(q[0], GL15.GL_QUERY_RESULT);
      }
      java.util.Arrays.sort(ns);
      System.out.printf(java.util.Locale.ROOT, "squares %d, gpu us median %.1f p10 %.1f p90 %.1f (frames %d)%n", n, ns[frames / 2] / 1e3, ns[frames / 10] / 1e3, ns[frames * 9 / 10] / 1e3, frames);
      // read back
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, dst[0]);
      ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
      GL11.glReadPixels(vx, vy, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
      BufferedImage full = new BufferedImage(w / 4, h / 4, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < h / 4; y++) {
         for (int x = 0; x < w / 4; x++) {
            full.setRGB(x, y, rgb(px, w, h, x * 4, y * 4));
         }
      }
      javax.imageio.ImageIO.write(full, "png", new File(out + "-full.png"));
      // the player's root on screen (window px, from the top)
      float u = p.cx - p.cy, vv = p.cx + p.cy;
      float pxX = (u - p.map[1]) / p.map[0] - vx, pxY = h - 1 - ((vv - p.map[3]) / p.map[2] - vy);
      int cw = crop, ch = crop / 2;
      int x0 = Math.max(0, Math.min(w - cw, (int)pxX - cw / 2)), y0 = Math.max(0, Math.min(h - ch, (int)pxY - ch / 2));
      BufferedImage c = new BufferedImage(cw * scale, ch * scale, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < ch * scale; y++) {
         for (int x = 0; x < cw * scale; x++) {
            c.setRGB(x, y, rgb(px, w, h, x0 + x / scale, y0 + y / scale));
         }
      }
      javax.imageio.ImageIO.write(c, "png", new File(out + "-crop.png"));
      System.out.println("wrote " + out + "-full.png, " + out + "-crop.png (crop at " + x0 + "," + y0 + ")");
      GLFW.glfwTerminate();
   }

   /** Pixel (x, y from the top) of a bottom-up RGBA readback. */
   static int rgb(ByteBuffer px, int w, int h, int x, int y) {
      int i = ((h - 1 - y) * w + x) * 4;
      return (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255);
   }

   static int[] fbo(int w, int h) {
      int c = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, c);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      int d = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, d);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      int f = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, c, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, d, 0);
      if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
         throw new IllegalStateException("fbo incomplete");
      }
      return new int[] {f, c, d};
   }
}
