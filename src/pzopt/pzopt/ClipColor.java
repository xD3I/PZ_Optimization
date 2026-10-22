package pzopt;

/**
 * Colour conversion of the menu's AVIF clips (pzopt.ClipDecoder, docs/findings-clip-format-2026-10-20.md): decoded RGB
 * of the file's own transfer and primaries -> what the options screen draws.
 *
 * <p>The clips are cut from the harness's HDR recordings (PQ / BT.2020) with the game's SDR white at ~200 nits, the
 * reference harness/menu-gifs.py has always tone-mapped from ({@code zscale npl=200}). Two outputs:
 * <ul>
 * <li>{@link #toSdr}: exactly the GIFs' tone map ({@code zscale ... t=linear:npl=200, tonemap=hable, zscale p=bt709
 *     t=bt709}): ffmpeg's hable with its defaults (desaturation 2 on r + g + b, peak 10 = 2,000 nits),
 *     BT.2020 -> BT.709, clipped, encoded with zimg's BT.709 curve (the BT.1886 inverse, x^(1/2.4)). Checked against
 *     ffmpeg 9.0.2's output (tests/pzopt/ClipColorTest). So with HDR output off an AVIF
 *     clip looks like its GIF, minus the 96 colours.</li>
 * <li>{@link #toHdr}: the same picture without the shoulder, for {@link Hdr}'s FP16 back buffer, where the UI is drawn
 *     gamma-encoded relative to the UI white and values above 1 are brighter than it: linear light scaled by the hable
 *     curve's slope at black ({@link #K}), so shadows and midtones match the SDR picture and highlights keep going (a
 *     2,000-nit glint ~3.5x the UI white, the swap pass rolls the top into the panel's peak). Packed as GL_RGB9_E5.</li>
 * </ul>
 * No game classes: unit-tested (tests/pzopt/ClipColorTest).
 */
public final class ClipColor {
   private ClipColor() {
   }

   /** Linear 1.0 in nits (the GIF chain's npl). */
   static final float SDR_WHITE_NITS = 200F;
   static final float HABLE_PEAK = 10F;
   static final float DESAT = 2F;
   static final float A = 0.15F, B = 0.50F, C = 0.10F, D = 0.20F, E = 0.02F, F = 0.30F;
   static final float HABLE_PEAK_VALUE = hable(HABLE_PEAK);
   /** The SDR curve's slope at black: hable'(0) / hable(peak) = 0.3934. */
   static final float K = (B * (C * F - E) / (D * F * F)) / HABLE_PEAK_VALUE;
   // BT.2020 -> BT.709 (linear)
   static final float M00 = 1.6605F, M01 = -0.5876F, M02 = -0.0728F;
   static final float M10 = -0.1246F, M11 = 1.1329F, M12 = -0.0083F;
   static final float M20 = -0.0182F, M21 = -0.1006F, M22 = 1.1187F;
   /**
    * The "luma" of ffmpeg's tonemap desaturation. zscale tags its RGB output AVCOL_SPC_RGB, whose luma coefficients in
    * libavutil/csp.c are (1, 1, 1): the GIFs' chain desaturates on r + g + b, not on the BT.2020 luma (checked against
    * ffmpeg 9.0.2 on 4,096 random colours: max error 5e-7).
    */
   static final float LR = 1F, LG = 1F, LB = 1F;

   /** PQ code (16-bit, top 12 bits) -> linear light in units of {@link #SDR_WHITE_NITS}. */
   private static final float[] PQ = new float[4096];
   /** sqrt-indexed x^(1/2.4) over [0, 1] (SDR) and [0, HDR_RANGE] (HDR). */
   private static final int ENC_N = 4096;
   private static final float[] ENC_SDR = new float[ENC_N + 1];
   static final float HDR_RANGE = 32F;
   private static final int ENC_HDR_N = 16384;
   private static final float[] ENC_HDR = new float[ENC_HDR_N + 1];

   static {
      double m1 = 2610.0 / 16384, m2 = 2523.0 / 4096 * 128, c1 = 3424.0 / 4096, c2 = 2413.0 / 4096 * 32, c3 = 2392.0 / 4096 * 32;
      for (int i = 0; i < PQ.length; i++) {
         double e = (i + 0.5) / PQ.length;
         if (i == 0) {
            e = 0;
         }
         double p = Math.pow(e, 1 / m2);
         double nits = Math.pow(Math.max(p - c1, 0) / (c2 - c3 * p), 1 / m1) * 10000;
         PQ[i] = (float) (nits / SDR_WHITE_NITS);
      }
      for (int i = 0; i <= ENC_N; i++) {
         double s = (double) i / ENC_N;
         ENC_SDR[i] = (float) Math.pow(s * s, 1 / 2.4);
      }
      for (int i = 0; i <= ENC_HDR_N; i++) {
         double s = (double) i / ENC_HDR_N;
         ENC_HDR[i] = (float) Math.pow(s * s * HDR_RANGE, 1 / 2.4);
      }
   }

   static float hable(float x) {
      return (x * (x * A + C * B) + D * E) / (x * (x * A + B) + D * F) - E / F;
   }

   /** Linear light (x {@link #SDR_WHITE_NITS}) of a 16-bit PQ code value. */
   static float pq(int code16) {
      return PQ[(code16 & 0xFFFF) >>> 4];
   }

   static float encSdr(float x) {
      if (!(x > 0F)) {
         return 0F;
      }
      if (x >= 1F) {
         return 1F;
      }
      return ENC_SDR[(int) (Math.sqrt(x) * ENC_N + 0.5)];
   }

   static float encHdr(float x) {
      if (!(x > 0F)) {
         return 0F;
      }
      if (x >= HDR_RANGE) {
         return ENC_HDR[ENC_HDR_N];
      }
      return ENC_HDR[(int) (Math.sqrt(x / HDR_RANGE) * ENC_HDR_N + 0.5)];
   }

   /**
    * One frame of 16-bit PQ / BT.2020 pixels ({@code 0xAAAA_RRRR_GGGG_BBBB}, javif's packing) -> RGBA8 packed for
    * GL_RGBA / GL_UNSIGNED_INT_8_8_8_8_REV ({@code A<<24 | B<<16 | G<<8 | R}), the GIFs' tone map.
    */
   public static void toSdr(long[] src, int[] dst, int count) {
      for (int i = 0; i < count; i++) {
         long p = src[i];
         float r = pq((int) (p >>> 32)), g = pq((int) (p >>> 16)), b = pq((int) p);
         float luma = LR * r + LG * g + LB * b;
         if (luma > DESAT) { // ffmpeg's tonemap desaturation (only the brightest pixels)
            float ob = (luma - DESAT) / luma;
            r += (luma - r) * ob;
            g += (luma - g) * ob;
            b += (luma - b) * ob;
         }
         float sig = Math.max(Math.max(r, g), Math.max(b, 1e-6F));
         float s = hable(sig) / HABLE_PEAK_VALUE / sig;
         r *= s;
         g *= s;
         b *= s;
         float r7 = M00 * r + M01 * g + M02 * b, g7 = M10 * r + M11 * g + M12 * b, b7 = M20 * r + M21 * g + M22 * b;
         dst[i] = 0xFF000000 | to8(encSdr(b7)) << 16 | to8(encSdr(g7)) << 8 | to8(encSdr(r7));
      }
   }

   /** One frame of 16-bit PQ / BT.2020 pixels -> GL_RGB9_E5 of the HDR picture (see the class comment). */
   public static void toHdr(long[] src, int[] dst, int count) {
      for (int i = 0; i < count; i++) {
         long p = src[i];
         float r = pq((int) (p >>> 32)) * K, g = pq((int) (p >>> 16)) * K, b = pq((int) p) * K;
         float r7 = M00 * r + M01 * g + M02 * b, g7 = M10 * r + M11 * g + M12 * b, b7 = M20 * r + M21 * g + M22 * b;
         dst[i] = rgb9e5(encHdr(r7), encHdr(g7), encHdr(b7));
      }
   }

   /** 8-bit SDR pixels as javif packs them ({@code 0xAARRGGBB}) -> RGBA8 for GL_UNSIGNED_INT_8_8_8_8_REV, opaque. */
   public static void sdr8(int[] src, int[] dst, int count) {
      for (int i = 0; i < count; i++) {
         int p = src[i];
         dst[i] = 0xFF000000 | (p & 0xFF) << 16 | (p & 0xFF00) | (p >>> 16) & 0xFF;
      }
   }

   /** 16-bit SDR pixels (a 10/12-bit SDR file) -> RGBA8 for GL_UNSIGNED_INT_8_8_8_8_REV, opaque. */
   public static void sdr16(long[] src, int[] dst, int count) {
      for (int i = 0; i < count; i++) {
         long p = src[i];
         int r = (int) (p >>> 32) & 0xFFFF, g = (int) (p >>> 16) & 0xFFFF, b = (int) p & 0xFFFF;
         dst[i] = 0xFF000000 | ((b + 128) / 257) << 16 | ((g + 128) / 257) << 8 | (r + 128) / 257;
      }
   }

   static int to8(float v) {
      return (int) (v * 255F + 0.5F);
   }

   /** GL_RGB9_E5 / GL_UNSIGNED_INT_5_9_9_9_REV packing (EXT_texture_shared_exponent's algorithm). */
   static int rgb9e5(float r, float g, float b) {
      final float max = 65408F; // (2^9 - 1) / 2^9 * 2^(31 - 15)
      r = r > 0F ? Math.min(r, max) : 0F;
      g = g > 0F ? Math.min(g, max) : 0F;
      b = b > 0F ? Math.min(b, max) : 0F;
      float m = Math.max(r, Math.max(g, b));
      int e = Math.max(-16, Math.getExponent(m < Float.MIN_NORMAL ? Float.MIN_NORMAL : m)) + 1 + 15;
      int mm = (int) Math.floor(m / Math.scalb(1F, e - 15 - 9) + 0.5F);
      if (mm == 512) {
         e++;
      }
      float scale = Math.scalb(1F, e - 15 - 9);
      int rm = (int) Math.floor(r / scale + 0.5F);
      int gm = (int) Math.floor(g / scale + 0.5F);
      int bm = (int) Math.floor(b / scale + 0.5F);
      return Math.min(rm, 511) | Math.min(gm, 511) << 9 | Math.min(bm, 511) << 18 | e << 27;
   }

   /** Decode a GL_RGB9_E5 value (tests). */
   static float[] rgb9e5Decode(int v) {
      float scale = Math.scalb(1F, (v >>> 27) - 15 - 9);
      return new float[]{(v & 511) * scale, (v >>> 9 & 511) * scale, (v >>> 18 & 511) * scale};
   }
}
