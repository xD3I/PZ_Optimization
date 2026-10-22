package pzopt;

/**
 * The AVIF clips' colour conversion (pzopt.ClipColor): the SDR side reproduces harness/menu-gifs.py's ffmpeg tone map
 * (pixels taken from a real enh-hdr-day frame through ffmpeg's zscale / tonemap=hable chain), the HDR side keeps the
 * SDR picture's slope at black and lets highlights exceed the UI white, and the GL_RGB9_E5 packing round-trips.
 */
public class ClipColorTest {
   // PQ code values (16-bit R, G, B) and ffmpeg's 8-bit output for them (docs/findings-clip-format-2026-10-20.md)
   static final int[][] FFMPEG = {
         {0x4C91, 0x4CCD, 0x49E1, 49, 50, 46},
         {0x6B74, 0x723C, 0x7333, 78, 99, 99},
         {0x8395, 0x8ADA, 0x8CD2, 111, 140, 144},
         {0x93F2, 0x9B13, 0x9ECC, 191, 201, 204},
         {0xA65B, 0xAD88, 0xB0AC, 234, 239, 240},
         {0xAC33, 0xB354, 0xB70E, 243, 247, 249},
   };

   static long px(int r, int g, int b) {
      return 0xFFFFL << 48 | (long) r << 32 | (long) g << 16 | b;
   }

   static int pqCode(double nits) {
      double m1 = 2610.0 / 16384, m2 = 2523.0 / 4096 * 128, c1 = 3424.0 / 4096, c2 = 2413.0 / 4096 * 32, c3 = 2392.0 / 4096 * 32;
      double y = Math.pow(nits / 10000, m1);
      return (int) Math.round(Math.pow((c1 + c2 * y) / (1 + c3 * y), m2) * 65535);
   }

   public static void main(String[] args) {
      Check.check(Math.abs(ClipColor.K - 0.3934F) < 0.0005F, "K, the hable slope at black over hable(10): " + ClipColor.K);
      Check.check(Math.abs(ClipColor.pq(0xFFFF) - 50F) < 0.5F, "PQ 1.0 = 10,000 nits = 50 x 200: " + ClipColor.pq(0xFFFF));
      Check.check(ClipColor.pq(0) == 0F, "PQ 0 is black");

      long[] src = new long[FFMPEG.length];
      for (int i = 0; i < src.length; i++) {
         src[i] = px(FFMPEG[i][0], FFMPEG[i][1], FFMPEG[i][2]);
      }
      int[] out = new int[src.length];
      ClipColor.toSdr(src, out, src.length);
      for (int i = 0; i < src.length; i++) {
         int r = out[i] & 0xFF, g = out[i] >>> 8 & 0xFF, b = out[i] >>> 16 & 0xFF, a = out[i] >>> 24;
         int[] want = FFMPEG[i];
         int err = Math.max(Math.abs(r - want[3]), Math.max(Math.abs(g - want[4]), Math.abs(b - want[5])));
         Check.check(err <= 2 && a == 255, "pixel " + i + " like ffmpeg: " + r + "," + g + "," + b + " vs " + want[3] + "," + want[4] + "," + want[5]);
      }

      // HDR: below the shoulder the HDR picture matches the SDR one; above it, it keeps rising past the UI white
      int pq100 = pqCode(100);
      int pq2000 = pqCode(2000);
      Check.check(Math.abs(ClipColor.pq(pq100) * 200 - 100) < 1.5F, "PQ LUT at 100 nits: " + ClipColor.pq(pq100) * 200);
      long[] two = {px(pq100, pq100, pq100), px(pq2000, pq2000, pq2000)};
      int[] sdr = new int[2];
      int[] hdr = new int[2];
      ClipColor.toSdr(two, sdr, 2);
      ClipColor.toHdr(two, hdr, 2);
      float sdrMid = (sdr[0] & 0xFF) / 255F;
      float hdrMid = ClipColor.rgb9e5Decode(hdr[0])[0];
      Check.check(Math.abs(sdrMid - hdrMid) < 0.03F, "100 nits: HDR " + hdrMid + " ~ SDR " + sdrMid);
      float hdrHigh = ClipColor.rgb9e5Decode(hdr[1])[0];
      Check.check((sdr[1] & 0xFF) >= 250 && hdrHigh > 1.5F, "2,000 nits: SDR at the top " + (sdr[1] & 0xFF) + ", HDR above the UI white " + hdrHigh);
      Check.check(Math.abs(hdrHigh - (float) Math.pow(ClipColor.K * ClipColor.pq(pq2000), 1 / 2.4)) < 0.02F, "HDR = (K x L)^(1/2.4)");

      // RGB9_E5 round trip
      float[][] cases = {{0, 0, 0}, {1, 1, 1}, {0.5F, 0.25F, 0.125F}, {3.4F, 0.01F, 1.2F}, {0.001F, 0.002F, 0.003F}};
      for (float[] c : cases) {
         float[] d = ClipColor.rgb9e5Decode(ClipColor.rgb9e5(c[0], c[1], c[2]));
         float m = Math.max(c[0], Math.max(c[1], c[2]));
         for (int k = 0; k < 3; k++) {
            Check.check(Math.abs(d[k] - c[k]) <= m / 256F + 1e-7F, "rgb9e5 " + c[0] + "," + c[1] + "," + c[2] + " -> " + d[0] + "," + d[1] + "," + d[2]);
         }
      }
      Check.check(ClipColor.rgb9e5(-1, Float.NaN, 0) == 0, "negative / NaN clamp to 0");

      // 8-bit SDR passes through: 0xAARRGGBB -> A<<24 | B<<16 | G<<8 | R
      int[] s8 = new int[1];
      ClipColor.sdr8(new int[]{0x00123456}, s8, 1);
      Check.check(s8[0] == 0xFF563412, "sdr8 repack, opaque: " + Integer.toHexString(s8[0]));
      System.out.println("ClipColorTest OK");
   }
}
