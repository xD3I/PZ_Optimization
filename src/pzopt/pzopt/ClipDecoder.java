package pzopt;

import pzopt.vendor.avif.AvifColorInfo;
import pzopt.vendor.avif.AvifFrame;
import pzopt.vendor.avif.AvifImageInfo;
import pzopt.vendor.avif.AvifImageReader;
import pzopt.vendor.avif.AvifSequenceInfo;

/**
 * AVIF clips and pictures for the options screen (docs/findings-clip-format-2026-10-20.md): decoded with javif (pure
 * Java AV1, src/vendor/javif, MPL-2.0) on the caller's thread (pzopt.GifTextures' decode thread), one frame at a time,
 * each converted by {@link ClipColor} and handed to a {@link Sink} as packed pixels ready for a GL upload.
 *
 * <p>A PQ file (CICP transfer 16) is the HDR form: with {@code hdrOut} it becomes GL_RGB9_E5 values above 1 where the
 * picture is brighter than the UI white, otherwise the GIFs' tone map (RGBA8). Any other transfer is taken as SDR and
 * passed through (RGBA8). A still image is a one-frame clip.
 *
 * <p>Limits: frames larger than {@link #MAX_DIM} on a side are refused (the caller falls back to the GIF); more than
 * {@code maxFrames} frames are thinned evenly, the dropped frames' durations folded into the kept ones (an AV1 sequence
 * is inter-coded, so every frame is still decoded). No game classes: unit-tested (tests/pzopt/ClipDecoderTest).
 */
public final class ClipDecoder {
   private ClipDecoder() {
   }

   public static final int MAX_DIM = 1024;
   static final int DEFAULT_DELAY_MS = 100;

   public interface Sink {
      /** Before the first frame. {@code rgb9e5}: the pixels are GL_RGB9_E5, else RGBA8 (8_8_8_8_REV). */
      void begin(int width, int height, int[] delaysMs, boolean rgb9e5, String describe) throws Exception;

      /** One kept frame, in order; the array is the sink's to keep. */
      void frame(int index, int[] pixels) throws Exception;
   }

   /** Whether a CICP transfer is PQ (SMPTE ST 2084). */
   static boolean isPq(AvifColorInfo c) {
      return c != null && c.transferCharacteristics() == 16;
   }

   /** Decode {@code file} into {@code sink}; returns the number of frames decoded (kept or not). */
   public static int decode(byte[] file, boolean hdrOut, int maxFrames, Sink sink) throws Exception {
      try (AvifImageReader reader = AvifImageReader.open(file)) {
         AvifImageInfo info = reader.info();
         int w = info.width(), h = info.height();
         if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM) {
            throw new IllegalArgumentException("avif " + w + "x" + h + " is over " + MAX_DIM + " px a side");
         }
         boolean pq = isPq(info.colorInfo());
         boolean rgb9e5 = pq && hdrOut;
         AvifSequenceInfo seq = info.sequenceInfo();
         int count = Math.max(1, seq != null ? seq.frameCount() : info.frameCount());
         int[] delays = delaysMs(seq, count);
         int kept = Math.min(count, Math.max(1, maxFrames));
         int[] keptDelays = new int[kept];
         int[] keepIndex = new int[count]; // kept slot of each frame, -1 = folded into the previous kept one
         int slot = -1;
         for (int i = 0; i < count; i++) {
            boolean keep = i == 0 || (long) i * kept / count != (long) (i - 1) * kept / count;
            if (keep && slot + 1 < kept) {
               slot++;
               keepIndex[i] = slot;
            } else {
               keepIndex[i] = -1;
            }
            keptDelays[Math.max(slot, 0)] += delays[i];
         }
         String describe = "avif " + w + "x" + h + " " + info.bitDepth().bits() + "-bit " + info.chromaFormat()
               + (pq ? " PQ" : " SDR") + ", " + count + " frames" + (count != kept ? " (" + kept + " kept)" : "")
               + (rgb9e5 ? ", HDR textures" : "");
         sink.begin(w, h, keptDelays, rgb9e5, describe);
         int n = 0;
         AvifFrame f;
         while ((f = reader.readFrame()) != null && n < count) {
            int k = keepIndex[n++];
            if (k < 0) {
               continue;
            }
            if (f.width() != w || f.height() != h) {
               throw new IllegalStateException("avif frame " + (n - 1) + " is " + f.width() + "x" + f.height());
            }
            int[] out = new int[w * h];
            if (f.bitDepth().bits() > 8) {
               long[] px = f.longPixels();
               if (!pq) {
                  ClipColor.sdr16(px, out, out.length);
               } else if (rgb9e5) {
                  ClipColor.toHdr(px, out, out.length);
               } else {
                  ClipColor.toSdr(px, out, out.length);
               }
            } else {
               int[] px = f.intPixels();
               if (pq) { // 8-bit PQ: widen and convert like the 10-bit form
                  long[] wide = new long[px.length];
                  for (int i = 0; i < px.length; i++) {
                     int p = px[i];
                     wide[i] = (long) ((p >>> 16 & 0xFF) * 257) << 32 | (long) ((p >>> 8 & 0xFF) * 257) << 16 | (p & 0xFF) * 257L;
                  }
                  if (rgb9e5) {
                     ClipColor.toHdr(wide, out, out.length);
                  } else {
                     ClipColor.toSdr(wide, out, out.length);
                  }
               } else {
                  ClipColor.sdr8(px, out, out.length);
               }
            }
            sink.frame(k, out);
         }
         return n;
      }
   }

   /**
    * Per-frame display times in whole ms from the sequence's durations, rounded on the running total so the clip keeps
    * real time (24 fps = 42, 41, 42, ...). A still image, or a sequence without durations, gets {@link #DEFAULT_DELAY_MS}.
    */
   static int[] delaysMs(AvifSequenceInfo seq, int count) {
      int[] out = new int[count];
      int[] d = seq == null ? null : seq.frameDurations();
      int scale = seq == null ? 0 : seq.mediaTimescale();
      if (d == null || d.length < count || scale <= 0) {
         java.util.Arrays.fill(out, DEFAULT_DELAY_MS);
         return out;
      }
      long t = 0;
      for (int i = 0; i < count; i++) {
         long a = Math.round(t * 1000.0 / scale);
         t += Math.max(0, d[i]);
         long b = Math.round(t * 1000.0 / scale);
         out[i] = (int) Math.max(1, b - a);
      }
      return out;
   }
}
