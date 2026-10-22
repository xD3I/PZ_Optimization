package pzopt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import zombie.core.SpriteRenderer;
import zombie.ZomboidFileSystem;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.ImageData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.math.PZMath;
import zombie.core.utils.WrappedBuffer;

/**
 * Animated GIFs as game textures, for the stock-vs-optimized previews of the "Optimizations" options tab
 * ({@code media/lua/client/pzopt/pzopt_optimizations_options.lua}, the pair of clips under
 * {@code media/ui/pzopt/compare/}). The game only loads PNG (and its own pack formats), so a GIF is decoded here
 * with ImageIO on a daemon thread, composited frame by frame (GIF frames are deltas with a disposal rule each),
 * and turned into one {@link Texture} per frame on the game thread as the Lua asks for frames: at most
 * {@link #UPLOADS_PER_CALL} per call, so a clip fades in over a few UI frames instead of one long one. A
 * frame's texture is created uncompressed whatever the "texture compression" option says (the driver's
 * on-upload compression would cost milliseconds per frame on the game thread).
 *
 * <p>Memory: a texture is padded to a power of two, so frames are capped at {@link #MAX_WIDTH} px wide (a
 * wider GIF is scaled) and {@link #MAX_FRAMES} frames (a longer GIF is thinned, the skipped frames' delays
 * folded into the kept ones), i.e. at most 512x256x4 bytes x 96 = 48 MB of VRAM per clip (the shipped clips are
 * 24 fps x 4 s). Only the last
 * {@link #KEEP_CLIPS} clips asked for stay loaded; the rest are destroyed on the render thread. Nothing here
 * runs unless the tab is open, and {@link #releaseAll()} frees everything when the options screen closes.
 *
 * <p>AVIF (2026-10-20, docs/findings-clip-format-2026-10-20.md): when {@code <clip>.avif} sits beside the GIF the Lua
 * names, that file is played instead: decoded on the same thread by {@link ClipDecoder} (javif, pure Java AV1), frame by
 * frame, at most {@link #AVIF_AHEAD} decoded frames waiting; each frame becomes its own texture through one queued
 * render-thread job at a time (never a blocking call on the game thread), GL_RGBA8, or GL_RGB9_E5 with values above
 * the UI white when the game's HDR output is on ({@link Hdr#active}: a PQ file then shows its highlights; with HDR off
 * it gets the GIFs' tone map). The clip shows its first frame until every frame is on the GPU, then plays on the
 * caller's clock with the file's frame times. A missing AVIF, a decode error or a failed upload falls back to the GIF.
 * {@code -Dpzopt.clipAvif=false} plays the GIFs only. The same VRAM bound: 96 frames of at most 1024 px a side, two clips.
 *
 * <p>Exposed to Lua through the {@code PerformanceSettings} override ({@code getPzoptGifFrame} and friends).
 */
public final class GifTextures {
   public static final int MAX_WIDTH = 512;
   public static final int MAX_FRAMES = 96;
   public static final int KEEP_CLIPS = 2;
   static final int UPLOADS_PER_CALL = 4;
   static final int DEFAULT_DELAY_MS = 100;
   /** Decoded AVIF frames allowed to wait for their upload (RAM bound: 16 x 512 x 216 x 4 bytes = 7 MB). */
   static final int AVIF_AHEAD = 16;
   static final boolean AVIF = !"false".equalsIgnoreCase(System.getProperty("pzopt.clipAvif", "true"));

   /** A decoded clip: composited RGBA frames (row-major, {@code width*4} bytes a row) and their delays. */
   public static final class Decoded {
      public final int width;
      public final int height;
      public final byte[][] rgba;
      public final int[] delaysMs;

      Decoded(int width, int height, byte[][] rgba, int[] delaysMs) {
         this.width = width;
         this.height = height;
         this.rgba = rgba;
         this.delaysMs = delaysMs;
      }

      public int totalMs() {
         int t = 0;
         for (int d : delaysMs) {
            t += d;
         }
         return Math.max(t, 1);
      }
   }

   private static final class Clip {
      final String path;
      volatile String state = "loading"; // loading | ready | missing | error
      volatile Decoded decoded;
      Texture[] frames;
      int uploaded;
      long lastUsedMs;
      /** The AVIF form while it is used; null for a GIF (or after the AVIF failed). */
      volatile AvifStream avif;
      /** An AVIF stream that failed after its textures were made: freed by the game thread. */
      volatile AvifStream failedAvif;

      Clip(String path) {
         this.path = path;
      }
   }

   /** An AVIF clip on its way to the GPU: the decode thread fills {@link #decoded}, the game thread makes textures. */
   static final class AvifStream {
      final String file;
      final boolean hdrOut;
      volatile int width;
      volatile int height;
      volatile int[] delaysMs;
      volatile boolean rgb9e5;
      volatile String describe = "";
      volatile boolean began;
      volatile boolean decodeDone;
      volatile boolean failed;
      volatile boolean glFailed;
      volatile boolean cancelled;
      final ConcurrentLinkedQueue<int[]> decoded = new ConcurrentLinkedQueue<>();
      final Semaphore room = new Semaphore(AVIF_AHEAD);
      // game thread
      ClipTexture[] textures;
      int handed; // frames whose upload job was queued
      // render thread
      volatile int onGpu;
      volatile boolean uploading;
      volatile boolean checkedGl;
      // stats
      volatile long decodeStartNs;
      volatile long decodeNs;
      volatile long uploadNs;
      volatile long uploadMaxNs;
      volatile int uploadJobs;
      long gameNs;
      long gameMaxNs;
      boolean logged;
      /** Game thread: the clock origin of the playback, -1 until the clip plays (see {@link #playable}). */
      long playStartMs = -1;
      long playAfterNs;

      AvifStream(String file, boolean hdrOut) {
         this.file = file;
         this.hdrOut = hdrOut;
      }

      int totalMs() {
         int t = 0;
         for (int d : delaysMs) {
            t += d;
         }
         return Math.max(t, 1);
      }
   }

   /**
    * A clip frame's texture: the game's own TextureID of the frame's format (created empty by its queued render-thread
    * job, flags FBO-style: no pixel buffer, clamped, linear) and the Texture the Lua draws; the pixels follow with
    * glTexSubImage2D in {@link #upload}, queued after it.
    */
   static final class ClipTexture {
      final TextureID id;
      final Texture texture;

      ClipTexture(int width, int height, boolean rgb9e5) {
         id = new TextureID(width, height, 16 | 32, rgb9e5 ? GL11.GL_RGB : GL11.GL_RGBA, rgb9e5 ? GL30.GL_RGB9_E5 : GL11.GL_RGBA8);
         texture = new Texture(id, "pzopt-clip");
      }

      /** Render thread: free the GL texture. */
      void destroyTexture() {
         texture.destroy();
      }
   }

   private static final Map<String, Clip> clips = new HashMap<>(); // game thread only
   private static ExecutorService decoder;

   private GifTextures() {
   }

   private static synchronized ExecutorService decoder() {
      if (decoder == null) {
         // two threads: the stock and the optimized side of a pair decode at once (an AVIF decode is single-threaded)
         decoder = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "pzopt-gif-decode");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
         });
      }
      return decoder;
   }

   /**
    * Where a game-dir-relative path ({@code media/ui/...}) lives: the game's media folder (the Steam layout, or the
    * .app's on macOS) is {@code ZomboidFileSystem}'s work dir, so the leading {@code media/} is taken off.
    */
   static File resolve(String rel) {
      File f = new File(rel);
      if (f.isAbsolute()) {
         return f;
      }
      try {
         if (ZomboidFileSystem.instance != null && rel.startsWith("media/")) {
            File media = ZomboidFileSystem.instance.getMediaFile(rel.substring("media/".length()));
            if (media != null) {
               return media;
            }
         }
      } catch (Throwable ignored) {
         // the file system is not set up this early; fall through to the working directory
      }
      return f;
   }

   /** "loading", "ready", "missing" or "error"; asking for a clip starts its decode. */
   public static String state(String path) {
      Clip c = clip(path, System.currentTimeMillis());
      AvifStream a = c.avif;
      if (a != null) {
         return a.onGpu > 0 ? "ready" : "loading";
      }
      return c.state;
   }

   public static int width(String path) {
      AvifStream a = clip(path, System.currentTimeMillis()).avif;
      if (a != null) {
         return a.began ? a.width : 0;
      }
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.width;
   }

   public static int height(String path) {
      AvifStream a = clip(path, System.currentTimeMillis()).avif;
      if (a != null) {
         return a.began ? a.height : 0;
      }
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.height;
   }

   public static int frameCount(String path) {
      AvifStream a = clip(path, System.currentTimeMillis()).avif;
      if (a != null) {
         return a.began ? a.delaysMs.length : 0;
      }
      Decoded d = clip(path, System.currentTimeMillis()).decoded;
      return d == null ? 0 : d.rgba.length;
   }

   /**
    * The frame to show at {@code nowMs} (any clock; the clip loops over its own total delay), or null while it
    * decodes or when the file is missing. Uploads a few pending frames per call.
    */
   public static Texture frame(String path, long nowMs) {
      Clip c = clip(path, nowMs);
      AvifStream a = c.avif;
      if (a != null) {
         return avifFrame(c, a, nowMs);
      }
      Decoded d = c.decoded;
      if (d == null || c.frames == null) {
         return null;
      }
      if (c.uploaded < c.frames.length) {
         upload(c, d);
      }
      if (c.uploaded == 0) {
         return null;
      }
      int index = frameAt(d, nowMs);
      if (index >= c.uploaded) {
         index = c.uploaded - 1;
      }
      return c.frames[index];
   }

   static int frameAt(Decoded d, long nowMs) {
      long t = Math.floorMod(nowMs, (long) d.totalMs());
      for (int i = 0; i < d.delaysMs.length; i++) {
         t -= d.delaysMs[i];
         if (t < 0) {
            return i;
         }
      }
      return d.delaysMs.length - 1;
   }

   private static Clip clip(String path, long nowMs) {
      Clip c = clips.get(path);
      if (c == null) {
         c = new Clip(path);
         clips.put(path, c);
         final Clip started = c;
         File f = resolve(path);
         File fa = AVIF ? resolve(avifPath(path)) : null;
         if (fa != null && fa.isFile()) {
            AvifStream s = new AvifStream(fa.getPath(), hdrOut());
            c.avif = s;
            decoder().execute(() -> {
               try {
                  decodeAvif(s, java.nio.file.Files.readAllBytes(fa.toPath()));
               } catch (Throwable t) {
                  if (s.cancelled) {
                     return;
                  }
                  Log.warn("clip " + avifPath(path) + ": " + t + "; playing the GIF");
                  s.failed = true;
                  started.failedAvif = s;
                  started.avif = null;
                  decodeGif(started, f, path);
               }
            });
         } else if (!f.isFile()) {
            c.state = "missing";
         } else {
            decoder().execute(() -> decodeGif(started, f, path));
         }
      }
      AvifStream failed = c.failedAvif;
      if (failed != null) {
         c.failedAvif = null;
         releaseStream(failed);
      }
      AvifStream glFailed = c.avif;
      if (glFailed != null && glFailed.glFailed) { // the driver refused the upload: the GIF instead
         c.avif = null;
         releaseStream(glFailed);
         final Clip fallback = c;
         decoder().execute(() -> decodeGif(fallback, resolve(fallback.path), fallback.path));
      }
      c.lastUsedMs = nowMs;
      if (c.avif != null && c.avif.began && c.avif.textures == null) {
         AvifStream s = c.avif;
         s.textures = new ClipTexture[s.delaysMs.length];
         evict(nowMs);
      }
      if (c.decoded != null && c.frames == null) {
         c.frames = new Texture[c.decoded.rgba.length];
         evict(nowMs);
      }
      return c;
   }

   /** Keep the {@link #KEEP_CLIPS} most recently used clips with textures; free the rest. */
   private static void evict(long nowMs) {
      while (true) {
         Clip oldest = null;
         int held = 0;
         for (Clip c : clips.values()) {
            AvifStream a = c.avif;
            if (c.frames == null && (a == null || a.textures == null)) {
               continue;
            }
            held++;
            if (oldest == null || c.lastUsedMs < oldest.lastUsedMs) {
               oldest = c;
            }
         }
         if (held <= KEEP_CLIPS || oldest == null) {
            return;
         }
         release(oldest.path);
      }
   }

   private static void upload(Clip c, Decoded d) {
      boolean compress = TextureID.useCompressionOption;
      TextureID.useCompressionOption = false;
      try {
         for (int n = 0; n < UPLOADS_PER_CALL && c.uploaded < c.frames.length; n++) {
            int i = c.uploaded;
            c.frames[i] = texture(c.path + "#" + i, d.width, d.height, d.rgba[i]);
            d.rgba[i] = null; // the pixels live on the GPU now
            c.uploaded++;
         }
      } finally {
         TextureID.useCompressionOption = compress;
      }
   }

   /** One game texture from RGBA rows; blocks on the render thread once (the upload), like a Steam avatar does. */
   private static Texture texture(String name, int width, int height, byte[] rgba) {
      int widthHw = Math.max(2, PZMath.smallestEncompassingPowerOfTwo(width)); // ImageUtils.getNextPowerOfTwoHW (gone in 42.21)
      int heightHw = Math.max(2, PZMath.smallestEncompassingPowerOfTwo(height)); // ImageUtils.getNextPowerOfTwoHW (gone in 42.21)
      WrappedBuffer wb = DirectBufferAllocator.allocate(widthHw * heightHw * 4);
      ByteBuffer buf = wb.getBuffer();
      buf.clear();
      int row = width * 4;
      int stride = widthHw * 4;
      for (int y = 0; y < height; y++) {
         buf.position(y * stride);
         buf.put(rgba, y * row, row);
      }
      buf.position(0);
      buf.limit(widthHw * heightHw * 4);
      ImageData img = new ImageData(width, height, wb);
      TextureID id = new TextureID(img); // uploads on the render thread and disposes the buffer
      return new Texture(id, name);
   }

   /** Free a clip's textures (on the render thread) and forget it; a later ask decodes it again. */
   public static void release(String path) {
      Clip c = clips.remove(path);
      if (c == null) {
         return;
      }
      AvifStream a = c.avif;
      if (a != null) {
         c.avif = null;
         releaseStream(a);
      }
      AvifStream failed = c.failedAvif;
      if (failed != null) {
         c.failedAvif = null;
         releaseStream(failed);
      }
      if (c.frames == null) {
         return;
      }
      final Texture[] frames = c.frames;
      c.frames = null;
      c.decoded = null;
      RenderThread.queueInvokeOnRenderContext(() -> {
         for (Texture t : frames) {
            if (t != null) {
               t.destroy();
            }
         }
      });
   }

   public static void releaseAll() {
      for (String p : new ArrayList<>(clips.keySet())) {
         release(p);
      }
      clips.clear();
   }

   /** Every clip's textures that are loaded right now (for the tab's footer and the log). */
   public static int loadedFrames() {
      int n = 0;
      for (Clip c : clips.values()) {
         n += c.uploaded;
         AvifStream a = c.avif;
         if (a != null) {
            n += a.onGpu;
         }
      }
      return n;
   }

   /** {@code media/ui/pzopt/compare/x-opt.gif} -> {@code media/ui/pzopt/compare/x-opt.avif}. */
   static String avifPath(String gifPath) {
      int dot = gifPath.lastIndexOf('.');
      int slash = gifPath.lastIndexOf('/');
      return (dot > slash ? gifPath.substring(0, dot) : gifPath) + ".avif";
   }

   /** HDR textures only into the FP16 back buffer (not the macOS 8-bit alpha-gain bridge, where the UI stays SDR). */
   static boolean hdrOut() {
      try {
         return Hdr.active && !Hdr.alphaGain;
      } catch (Throwable t) {
         return false;
      }
   }

   private static void decodeGif(Clip c, File f, String path) {
      try (InputStream in = new java.io.FileInputStream(f)) {
         Decoded d = decode(in, MAX_WIDTH, MAX_FRAMES);
         c.decoded = d;
         c.state = "ready";
      } catch (Throwable t) {
         Log.warn("gif " + path + ": " + t);
         c.state = f.isFile() ? "error" : "missing";
      }
   }

   /** The decode thread's side of an AVIF clip: frames into {@code s.decoded}, at most {@link #AVIF_AHEAD} waiting. */
   static void decodeAvif(AvifStream s, byte[] file) throws Exception {
      CorePlacement.background();
      s.decodeStartNs = System.nanoTime();
      java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
      long cpu0 = mx.isCurrentThreadCpuTimeSupported() ? mx.getCurrentThreadCpuTime() : 0;
      ClipDecoder.decode(file, s.hdrOut, MAX_FRAMES, new ClipDecoder.Sink() {
         @Override
         public void begin(int width, int height, int[] delaysMs, boolean rgb9e5, String describe) {
            s.width = width;
            s.height = height;
            s.delaysMs = delaysMs;
            s.rgb9e5 = rgb9e5;
            s.describe = describe;
            s.began = true;
         }

         @Override
         public void frame(int index, int[] pixels) throws Exception {
            while (!s.room.tryAcquire(100, TimeUnit.MILLISECONDS)) {
               if (s.cancelled) {
                  throw new InterruptedException("clip released");
               }
            }
            if (s.cancelled) {
               throw new InterruptedException("clip released");
            }
            s.decoded.add(pixels);
         }
      });
      s.decodeNs = mx.isCurrentThreadCpuTimeSupported() ? mx.getCurrentThreadCpuTime() - cpu0 : System.nanoTime() - s.decodeStartNs;
      s.decodeDone = true;
   }

   /** Game thread: hand waiting frames to the render thread, then the frame for the clock (see {@link #playable}). */
   private static Texture avifFrame(Clip c, AvifStream s, long nowMs) {
      if (s.textures == null) {
         return null;
      }
      long t0 = System.nanoTime();
      pump(s);
      if (s.decoded.isEmpty()) { // this clip has nothing waiting: move another clip's decoded frames along (the decode
         for (Clip o : clips.values()) { // threads wait on them, so a clip nobody draws must not stall the others)
            AvifStream oa = o.avif;
            if (oa != null && oa != s && oa.began && !oa.decoded.isEmpty()) {
               if (oa.textures == null) {
                  oa.textures = new ClipTexture[oa.delaysMs.length];
                  evict(nowMs);
               }
               pump(oa);
               break;
            }
         }
      }
      Texture out = null;
      int ready = s.onGpu;
      if (s.failed || s.glFailed) {
         out = null;
      } else if (ready > 0) {
         if (s.playStartMs < 0 && playable(s, ready, System.nanoTime())) {
            s.playStartMs = nowMs;
            s.playAfterNs = System.nanoTime() - s.decodeStartNs;
            for (Clip o : clips.values()) { // a clip of the same length already playing (the other side of the pair): its clock
               AvifStream oa = o.avif;
               if (oa != null && oa != s && oa.playStartMs >= 0 && oa.delaysMs != null && oa.totalMs() == s.totalMs()) {
                  s.playStartMs = oa.playStartMs;
                  break;
               }
            }
         }
         int f = s.playStartMs < 0 ? 0 : frameAt(s.delaysMs, s.totalMs(), nowMs - s.playStartMs);
         out = s.textures[Math.min(f, ready - 1)].texture;
      }
      long dt = System.nanoTime() - t0;
      s.gameNs += dt;
      s.gameMaxNs = Math.max(s.gameMaxNs, dt);
      if (ready >= s.textures.length && !s.logged) {
         s.logged = true;
         Log.info(String.format("clip %s: %s, decode %.0f ms CPU (%.1f ms a frame), playing after %.0f ms, last upload "
                     + "at %.0f ms; render thread %.1f ms in %d uploads (max %.2f ms); game thread %.2f ms (max %.3f ms a call)",
               new File(s.file).getName(), s.describe, s.decodeNs / 1e6, s.decodeNs / 1e6 / Math.max(1, s.textures.length),
               s.playAfterNs / 1e6, (System.nanoTime() - s.decodeStartNs) / 1e6, s.uploadNs / 1e6, s.uploadJobs, s.uploadMaxNs / 1e6,
               s.gameNs / 1e6, s.gameMaxNs / 1e6));
      }
      return out;
   }

   /**
    * Whether a clip with {@code ready} of its frames on the GPU can start playing: all of them, or the decode so far is
    * fast enough that the rest arrives before the playhead needs it (with a quarter to spare). A slow CPU starts later
    * instead of stuttering through its first loop; a fast one starts at the first frame.
    */
   static boolean playable(AvifStream s, int ready, long nowNs) {
      int n = s.delaysMs.length;
      if (ready >= n) {
         return true;
      }
      if (s.decodeDone && s.decoded.isEmpty() && s.handed >= n) {
         return false; // all handed, waiting for the render thread: next frame
      }
      double msPerFrame = (nowNs - s.decodeStartNs) / 1e6 / ready;
      long left = 0;
      for (int i = ready; i < n; i++) {
         left += s.delaysMs[i];
      }
      return (n - ready) * msPerFrame * 1.25 <= left;
   }

   /** Queue the next upload job of a stream (one in flight at a time). */
   private static void pump(AvifStream s) {
      if (s.uploading || s.textures == null || s.handed >= s.textures.length || s.decoded.isEmpty()) {
         return;
      }
      int n = 0;
      int[][] px = new int[UPLOADS_PER_CALL][];
      ClipTexture[] tex = new ClipTexture[UPLOADS_PER_CALL];
      while (n < UPLOADS_PER_CALL && s.handed < s.textures.length) {
         int[] p = s.decoded.poll();
         if (p == null) {
            break;
         }
         ClipTexture t = new ClipTexture(s.width, s.height, s.rgb9e5);
         s.textures[s.handed++] = t;
         px[n] = p;
         tex[n++] = t;
      }
      if (n > 0) {
         s.uploading = true;
         final int count = n;
         RenderThread.queueInvokeOnRenderContext(() -> upload(s, tex, px, count));
      }
   }

   /** Render thread: the textures of up to {@link #UPLOADS_PER_CALL} frames. */
   private static void upload(AvifStream s, ClipTexture[] tex, int[][] px, int count) {
      long t0 = System.nanoTime();
      try {
         if (s.cancelled || s.failed || s.glFailed) {
            return;
         }
         if (!s.checkedGl) {
            while (GL11.glGetError() != 0) {
               // errors left by someone else
            }
         }
         int w = s.width, h = s.height;
         java.nio.IntBuffer buf = MemoryUtil.memAllocInt(w * h);
         try {
            for (int i = 0; i < count; i++) {
               buf.clear();
               buf.put(px[i]).flip();
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, Texture.lastTextureID = tex[i].id.getID());
               SpriteRenderer.ringBuffer.restoreBoundTextures = true;
               if (s.rgb9e5) {
                  GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGB, GL30.GL_UNSIGNED_INT_5_9_9_9_REV, buf);
               } else {
                  GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
               }
               px[i] = null;
               if (!s.checkedGl) { // once a clip: one driver round trip, so a format the driver refuses falls back to the GIF
                  s.checkedGl = true;
                  int err = GL11.glGetError();
                  if (err != 0) {
                     Log.warn("clip " + s.file + ": GL error 0x" + Integer.toHexString(err) + " on upload; playing the GIF");
                     s.glFailed = true;
                     return;
                  }
               }
               s.onGpu++;
            }
         } finally {
            MemoryUtil.memFree(buf);
         }
      } finally {
         s.room.release(count);
         long dt = System.nanoTime() - t0;
         s.uploadNs += dt;
         s.uploadMaxNs = Math.max(s.uploadMaxNs, dt);
         s.uploadJobs++;
         s.uploading = false;
      }
   }

   /** Stop a stream's decode and free its textures on the render thread. */
   private static void releaseStream(AvifStream s) {
      s.cancelled = true;
      s.decoded.clear();
      final ClipTexture[] tex = s.textures;
      s.textures = null;
      if (tex == null) {
         return;
      }
      RenderThread.queueInvokeOnRenderContext(() -> {
         for (ClipTexture t : tex) {
            if (t != null) {
               t.destroyTexture();
            }
         }
      });
   }

   static int frameAt(int[] delaysMs, int totalMs, long nowMs) {
      long t = Math.floorMod(nowMs, (long) totalMs);
      for (int i = 0; i < delaysMs.length; i++) {
         t -= delaysMs[i];
         if (t < 0) {
            return i;
         }
      }
      return delaysMs.length - 1;
   }

   // ---------------------------------------------------------------- decoding (no game classes; unit-tested)

   /**
    * Decode an animated GIF into composited RGBA frames. Frames wider than {@code maxWidth} are scaled down
    * (aspect kept); more than {@code maxFrames} frames are thinned evenly, the dropped frames' delays added to
    * the kept frame before them. A frame with no delay gets {@link #DEFAULT_DELAY_MS}.
    */
   public static Decoded decode(InputStream in, int maxWidth, int maxFrames) throws IOException {
      Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
      if (!readers.hasNext()) {
         throw new IOException("no GIF reader in this JRE");
      }
      ImageReader reader = readers.next();
      try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
         reader.setInput(iis, false, false);
         int count = reader.getNumImages(true);
         if (count <= 0) {
            throw new IOException("no frames");
         }
         int screenW = 0;
         int screenH = 0;
         IIOMetadata stream = reader.getStreamMetadata();
         if (stream != null) {
            Node root = stream.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            if (lsd != null) {
               screenW = attr(lsd, "logicalScreenWidth", 0);
               screenH = attr(lsd, "logicalScreenHeight", 0);
            }
         }
         // pass 1: frame geometry, so the canvas covers every frame even when the header lies
         BufferedImage first = reader.read(0);
         Node meta0 = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
         Node desc0 = child(meta0, "ImageDescriptor");
         int w = Math.max(screenW, attr(desc0, "imageLeftPosition", 0) + first.getWidth());
         int h = Math.max(screenH, attr(desc0, "imageTopPosition", 0) + first.getHeight());
         BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
         Graphics2D g = canvas.createGraphics();
         int outW = w;
         int outH = h;
         if (w > maxWidth) {
            outW = maxWidth;
            outH = Math.max(1, (int) Math.round(h * (double) maxWidth / w));
         }
         List<byte[]> frames = new ArrayList<>();
         List<Integer> delays = new ArrayList<>();
         BufferedImage previous = null;
         for (int i = 0; i < count; i++) {
            BufferedImage frame = i == 0 ? first : reader.read(i);
            Node meta = i == 0 ? meta0 : reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(meta, "ImageDescriptor");
            Node gce = child(meta, "GraphicControlExtension");
            int left = attr(desc, "imageLeftPosition", 0);
            int top = attr(desc, "imageTopPosition", 0);
            int delay = gce == null ? 0 : attr(gce, "delayTime", 0) * 10;
            String disposal = gce == null ? "none" : gce.getAttributes().getNamedItem("disposalMethod").getNodeValue();
            if (delay <= 0) {
               delay = DEFAULT_DELAY_MS;
            }
            if ("restoreToPrevious".equals(disposal)) {
               previous = copy(canvas);
            }
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.drawImage(frame, left, top, null);
            boolean keep = i == 0 || (long) i * maxFrames / count != (long) (i - 1) * maxFrames / count;
            if (keep && frames.size() < maxFrames) {
               frames.add(rgba(canvas, outW, outH));
               delays.add(delay);
            } else {
               delays.set(delays.size() - 1, delays.get(delays.size() - 1) + delay);
            }
            if ("restoreToBackgroundColor".equals(disposal)) {
               g.setComposite(java.awt.AlphaComposite.Clear);
               g.fillRect(left, top, frame.getWidth(), frame.getHeight());
            } else if ("restoreToPrevious".equals(disposal) && previous != null) {
               g.setComposite(java.awt.AlphaComposite.Src);
               g.drawImage(previous, 0, 0, null);
            }
         }
         g.dispose();
         int[] d = new int[delays.size()];
         for (int i = 0; i < d.length; i++) {
            d[i] = delays.get(i);
         }
         return new Decoded(outW, outH, frames.toArray(new byte[0][]), d);
      } finally {
         reader.dispose();
      }
   }

   private static BufferedImage copy(BufferedImage src) {
      BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = c.createGraphics();
      g.setComposite(java.awt.AlphaComposite.Src);
      g.drawImage(src, 0, 0, null);
      g.dispose();
      return c;
   }

   /** The canvas as RGBA bytes at {@code outW x outH} (bilinear when scaled); opaque black where nothing was drawn. */
   private static byte[] rgba(BufferedImage canvas, int outW, int outH) {
      BufferedImage src = canvas;
      if (outW != canvas.getWidth() || outH != canvas.getHeight()) {
         src = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
         Graphics2D g = src.createGraphics();
         g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
         g.drawImage(canvas, 0, 0, outW, outH, null);
         g.dispose();
      }
      int[] argb = src.getRGB(0, 0, outW, outH, null, 0, outW);
      byte[] out = new byte[outW * outH * 4];
      for (int i = 0, o = 0; i < argb.length; i++, o += 4) {
         int p = argb[i];
         int a = p >>> 24;
         if (a == 0) {
            p = 0xFF000000;
            a = 255;
         }
         out[o] = (byte) (p >> 16);
         out[o + 1] = (byte) (p >> 8);
         out[o + 2] = (byte) p;
         out[o + 3] = (byte) a;
      }
      return out;
   }

   private static Node child(Node parent, String name) {
      if (parent == null) {
         return null;
      }
      NodeList kids = parent.getChildNodes();
      for (int i = 0; i < kids.getLength(); i++) {
         if (name.equals(kids.item(i).getNodeName())) {
            return kids.item(i);
         }
      }
      return null;
   }

   private static int attr(Node node, String name, int fallback) {
      if (node == null || node.getAttributes() == null) {
         return fallback;
      }
      Node a = node.getAttributes().getNamedItem(name);
      if (a == null) {
         return fallback;
      }
      try {
         return Integer.parseInt(a.getNodeValue());
      } catch (NumberFormatException e) {
         return fallback;
      }
   }

   /** Build a small GIF in memory (tests): frames of solid colours, {@code delayCs} centiseconds each. */
   static byte[] encode(int width, int height, int[] colours, int delayCs) throws IOException {
      javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
         writer.setOutput(ios);
         writer.prepareWriteSequence(null);
         for (int colour : colours) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new java.awt.Color(colour));
            g.fillRect(0, 0, width, height);
            g.dispose();
            javax.imageio.ImageTypeSpecifier type = javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img);
            IIOMetadata meta = writer.getDefaultImageMetadata(type, writer.getDefaultWriteParam());
            String format = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
            IIOMetadataNode gce = (IIOMetadataNode) child(root, "GraphicControlExtension");
            if (gce == null) {
               gce = new IIOMetadataNode("GraphicControlExtension");
               root.appendChild(gce);
            }
            gce.setAttribute("disposalMethod", "none");
            gce.setAttribute("userInputFlag", "FALSE");
            gce.setAttribute("transparentColorFlag", "FALSE");
            gce.setAttribute("delayTime", Integer.toString(delayCs));
            gce.setAttribute("transparentColorIndex", "0");
            meta.setFromTree(format, root);
            writer.writeToSequence(new javax.imageio.IIOImage(img, null, meta), writer.getDefaultWriteParam());
         }
         writer.endWriteSequence();
      } finally {
         writer.dispose();
      }
      return bytes.toByteArray();
   }
}
