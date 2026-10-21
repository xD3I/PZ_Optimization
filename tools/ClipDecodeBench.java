import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java decode cost of the menu clip candidates (docs/findings-clip-format-2026-10-20.md): times decoding every frame
 * of a .gif (pzopt.GifTextures.decode, ImageIO), .jxl (jxlatte), .avif (javif) or .webp (TwelveMonkeys) file with the
 * JVM it runs on, and can dump the frames as raw rgb48le (the file's own transfer: PQ code values for an HDR file) so
 * harness/clip-format-eval.py compares them with the reference decoders (libjxl / dav1d via ffmpeg).
 *
 *   java -cp <classes of the decoders + pzopt>:. ClipDecodeBench <file> [iterations=5] [dump.rgb48]
 *
 * Prints one line: format, frames, width x height, first (cold) decode ms, median warm decode ms, ms per frame.
 */
public final class ClipDecodeBench {
   interface Decoder {
      /** Decodes every frame; returns the frame count. With dump != null writes rgb48le frames. */
      int decode(byte[] file, OutputStream dump, int[] size) throws Exception;
   }

   public static void main(String[] a) throws Exception {
      Path file = Path.of(a[0]);
      int iterations = a.length > 1 ? Integer.parseInt(a[1]) : 5;
      String dumpPath = a.length > 2 ? a[2] : null;
      byte[] bytes = Files.readAllBytes(file);
      String name = file.getFileName().toString();
      String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
      Decoder d = switch (ext) {
         case "gif" -> ClipDecodeBench::gif;
         case "jxl" -> ClipDecodeBench::jxl;
         case "avif" -> ClipDecodeBench::avif;
         case "webp" -> ClipDecodeBench::webp;
         default -> throw new IllegalArgumentException(ext);
      };
      int[] size = new int[2];
      int frames = 0;
      double cold = 0;
      double[] warm = new double[Math.max(1, iterations)];
      for (int i = 0; i <= iterations; i++) {
         OutputStream dump = i == 0 && dumpPath != null ? new java.io.BufferedOutputStream(new FileOutputStream(dumpPath), 1 << 20) : null;
         long t = System.nanoTime();
         frames = d.decode(bytes, dump, size);
         double ms = (System.nanoTime() - t) / 1e6;
         if (dump != null) {
            dump.close();
         }
         if (i == 0) {
            cold = ms;
         } else {
            warm[i - 1] = ms;
         }
      }
      Arrays.sort(warm);
      double med = iterations > 0 ? warm[warm.length / 2] : cold;
      System.out.printf("format=%s frames=%d size=%dx%d bytes=%d cold_ms=%.1f warm_ms=%.1f ms_per_frame=%.2f%n", ext, frames,
            size[0], size[1], bytes.length, cold, med, med / Math.max(1, frames));
   }

   static void put16(OutputStream o, int v) throws IOException {
      o.write(v & 0xFF);
      o.write((v >>> 8) & 0xFF);
   }

   static int gif(byte[] file, OutputStream dump, int[] size) throws Exception {
      pzopt.GifTextures.Decoded dec = pzopt.GifTextures.decode(new ByteArrayInputStream(file), 4096, 100000);
      size[0] = dec.width;
      size[1] = dec.height;
      if (dump != null) {
         for (byte[] f : dec.rgba) {
            for (int i = 0; i < f.length; i += 4) {
               for (int c = 0; c < 3; c++) {
                  put16(dump, (f[i + c] & 0xFF) * 257);
               }
            }
         }
      }
      return dec.rgba.length;
   }

   static int jxl(byte[] file, OutputStream dump, int[] size) throws Exception {
      com.traneptora.jxlatte.JXLDecoder dec = new com.traneptora.jxlatte.JXLDecoder(new ByteArrayInputStream(file));
      int n = 0;
      com.traneptora.jxlatte.JXLImage img;
      while ((img = dec.decode()) != null) {
         n++;
         // the file's own primaries / transfer (what PNGWriter writes for an HDR file)
         com.traneptora.jxlatte.JXLImage t = img.transform(img.getHeader().getColorEncoding().prim,
               img.getHeader().getColorEncoding().white, img.getTaggedTransfer(), com.traneptora.jxlatte.JXLOptions.PEAK_DETECT_OFF);
         com.traneptora.jxlatte.util.ImageBuffer[] b = t.getBuffer(false);
         size[0] = t.getWidth();
         size[1] = t.getHeight();
         float[][][] f = new float[3][][];
         for (int c = 0; c < 3; c++) {
            if (b[c].isInt()) {
               b[c].castToFloat(t.getTaggedBitDepth(c));
            }
            f[c] = b[c].getFloatBuffer();
         }
         if (dump != null) {
            for (int y = 0; y < size[1]; y++) {
               for (int x = 0; x < size[0]; x++) {
                  for (int c = 0; c < 3; c++) {
                     put16(dump, Math.max(0, Math.min(65535, Math.round(f[c][y][x] * 65535F))));
                  }
               }
            }
         }
      }
      return n;
   }

   static int avif(byte[] file, OutputStream dump, int[] size) throws Exception {
      int n = 0;
      try (org.glavo.avif.AvifImageReader r = org.glavo.avif.AvifImageReader.open(file)) {
         org.glavo.avif.AvifFrame f;
         while ((f = r.readFrame()) != null) {
            n++;
            size[0] = f.width();
            size[1] = f.height();
            boolean wide = f.bitDepth().bits() > 8;
            int[] ip = wide ? null : f.intPixels();
            long[] lp = wide ? f.longPixels() : null;
            if (dump != null) {
               int px = size[0] * size[1];
               for (int i = 0; i < px; i++) {
                  if (wide) {
                     long p = lp[i];
                     put16(dump, (int) (p >>> 32) & 0xFFFF);
                     put16(dump, (int) (p >>> 16) & 0xFFFF);
                     put16(dump, (int) p & 0xFFFF);
                  } else {
                     int p = ip[i];
                     put16(dump, ((p >>> 16) & 0xFF) * 257);
                     put16(dump, ((p >>> 8) & 0xFF) * 257);
                     put16(dump, (p & 0xFF) * 257);
                  }
               }
            }
         }
      }
      return n;
   }

   static int webp(byte[] file, OutputStream dump, int[] size) throws Exception {
      javax.imageio.ImageIO.scanForPlugins();
      javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReadersByFormatName("webp").next();
      int n;
      try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
         reader.setInput(in);
         n = reader.getNumImages(true);
         for (int i = 0; i < n; i++) {
            java.awt.image.BufferedImage im = reader.read(i);
            size[0] = im.getWidth();
            size[1] = im.getHeight();
            if (dump != null) {
               int[] argb = im.getRGB(0, 0, size[0], size[1], null, 0, size[0]);
               for (int p : argb) {
                  put16(dump, ((p >>> 16) & 0xFF) * 257);
                  put16(dump, ((p >>> 8) & 0xFF) * 257);
                  put16(dump, (p & 0xFF) * 257);
               }
            }
         }
      } finally {
         reader.dispose();
      }
      return n;
   }
}
