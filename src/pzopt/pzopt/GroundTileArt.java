package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.SplittableRandom;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.textures.Texture;

/**
 * Ground without the grid: the material art the bake samples (GroundTiling). Built once per session from the game's own
 * natural floor sprites (what the player sees: a mod that repaints grass gets its own art, and no game art ships with
 * pzopt), on a worker thread after one read-back of their texture pages on the render thread.
 *
 * <p>Per material layer: its whole-square variants dealt into an N x N exemplar in iso layout (a 64ts N x 32ts N period:
 * the diamond lattice a = (32ts, 16ts), b = (32ts, -16ts) tiles it exactly, so no texel is resampled), the seams between
 * the variants repaired in the gradient domain (periodic FFT Poisson; across a seam the guidance is the mean of the two
 * variants' own gradients there, so the seam keeps the texture's gradient statistics: seam |grad| / interior 1.26 ->
 * 0.99 on grass, where zero guidance over-smoothed to 0.51), a height channel (luminance over the layer's 1..99 %
 * range), and for histogram-preserving blending the PCA-decorrelated, per-channel Gaussianised exemplar with its
 * inverse-CDF LUT (harness/ground-tiling/art.py and rig.py are the offline reference of every step).
 */
public final class GroundTileArt {
   private GroundTileArt() {
   }

   /** One material layer: its class (GroundTiles material | tone << 4) and the whole-square sprites it is made of. */
   static final class LayerDef {
      final String name;
      final int material, tone;
      final String[] sprites;

      LayerDef(String name, int material, int tone, String... sprites) {
         this.name = name;
         this.material = material;
         this.tone = tone;
         this.sprites = sprites;
      }
   }

   static final String BN = "blends_natural_01_";
   /** The layers (GroundTiles materials: 1 grass, 2 dirt, 3 sand, 4 gravel, 8 clay, 9 dark dirt). */
   static final LayerDef[] LAYERS = {
      new LayerDef("sand", 3, 0, BN + 0, BN + 5, BN + 6, BN + 7),
      new LayerDef("grassLight", 1, 0, BN + 16, BN + 21, BN + 22, BN + 23),
      new LayerDef("grassMedium", 1, 1, BN + 32, BN + 37, BN + 38, BN + 39),
      new LayerDef("grassDark", 1, 2, BN + 48, BN + 53, BN + 54, BN + 55),
      new LayerDef("dirt", 2, 0, BN + 64, BN + 69, BN + 70, BN + 71),
      new LayerDef("darkDirt", 9, 0, BN + 80, BN + 85, BN + 86),
      new LayerDef("clay", 8, 0, BN + 96, BN + 101, BN + 102, BN + 103),
      new LayerDef("gravel", 4, 0, "floors_exterior_natural_01_13"),
      new LayerDef("gravelStreet", 4, 1, "blends_street_01_48", "blends_street_01_53", "blends_street_01_54", "blends_street_01_55"),
   };
   /** The grass layers (macro dryness applies to them): bit per layer. */
   public static final int GRASSY = 0b1110;
   /** The tone-0 layer of each material (the base under a blend piece: its height), -1 for none. */
   public static final int[] MATERIAL_LAYER = {-1, 1, 4, 0, 7, -1, -1, -1, 6, 5, -1, -1, -1, -1, -1, -1};

   /**
    * The sprite table: every natural floor sprite the patch re-samples, with its layer and piece kind (0 a whole square,
    * 1..4 the half toward the north / south / west / east corner, 5..8 the strip along the -y / -x / +x / +y edge;
    * blends_natural_01 +1..+4 and +8..+11 / +12..+15, checked against the sprites). The shader finds the drawn sprite by
    * its page rect and confirms it with three probe texels of the bound page. Left out (they stay stock): the dug soil
    * (blends_natural_01_87, farmland), floors_exterior_natural_01_16..19 (ploughed rows), rows past 111.
    */
   static final class Spr {
      final String name;
      final int layer, kind;
      float x0, y0, x1, y1;
      final float[] probe = new float[12]; // 3 rgba
      final int[] pxy = new int[6]; // their page texels
      boolean ok;

      Spr(String name, int layer, int kind) {
         this.name = name;
         this.layer = layer;
         this.kind = kind;
      }
   }

   static ArrayList<Spr> sprites() {
      ArrayList<Spr> l = new ArrayList<>();
      int[] rowLayer = {0, 1, 2, 3, 4, 5, 6};
      for (int row = 0; row < 7; row++) {
         for (int k = 0; k < 16; k++) {
            int idx = row * 16 + k;
            if (idx == 87) {
               continue;
            }
            int kind = k == 0 || k >= 5 && k <= 7 ? 0 : k <= 4 ? k : 5 + (k - 8) % 4;
            l.add(new Spr(BN + idx, rowLayer[row], kind));
         }
      }
      l.add(new Spr("floors_exterior_natural_01_0", 1, 0));
      l.add(new Spr("floors_exterior_natural_01_1", 2, 0));
      l.add(new Spr("floors_exterior_natural_01_2", 3, 0));
      l.add(new Spr("floors_exterior_natural_01_13", 7, 0));
      l.add(new Spr("floors_exterior_natural_01_24", 0, 0));
      for (int i : new int[] {48, 53, 54, 55}) {
         l.add(new Spr("blends_street_01_" + i, 8, 0));
      }
      return l;
   }

   /** Max table rows (the GLSL array size). */
   public static final int SPR_MAX = 128;
   /**
    * The sprite lookup grid (2026-10-24, the bake cost: the shader walked all SPR_MAX rects per fragment): the page's uv
    * square cut into SPR_GRID x SPR_GRID cells, each listing up to four table rows whose rect touches it (-1 none; -2 in
    * the first slot: more than four, the shader walks the table).
    */
   public static final int SPR_GRID = 128;

   /** pzGtSprTex: rows 0..SPR_MAX-1 the probes (texels 0..4), rows SPR_MAX.. the lookup grid (RGBA = four rows). */
   static float[] sprTexData(float[] probes, float[] rects, int[] codes, int n) {
      int w = SPR_GRID;
      float[] d = new float[w * (SPR_MAX + SPR_GRID) * 4];
      for (int i = 0; i < SPR_MAX; i++) {
         for (int k = 0; k < 20; k++) {
            d[(i * w) * 4 + k] = probes[i * 20 + k];
         }
      }
      float[] g = sprGrid(rects, codes, n);
      System.arraycopy(g, 0, d, w * SPR_MAX * 4, g.length);
      return d;
   }

   static float[] sprGrid(float[] rects, int[] codes, int n) {
      int G = SPR_GRID;
      float[] g = new float[G * G * 4];
      Arrays.fill(g, -1F);
      int[] cnt = new int[G * G];
      for (int i = 0; i < n; i++) {
         if (codes[i] < 0) {
            continue;
         }
         int cx0 = Math.max(0, (int)Math.floor(rects[i * 4] * G)), cy0 = Math.max(0, (int)Math.floor(rects[i * 4 + 1] * G));
         int cx1 = Math.min(G - 1, (int)Math.floor(rects[i * 4 + 2] * G)), cy1 = Math.min(G - 1, (int)Math.floor(rects[i * 4 + 3] * G));
         for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
               int c = cy * G + cx;
               if (cnt[c] < 4) {
                  g[c * 4 + cnt[c]] = i;
               } else {
                  g[c * 4] = -2F;
               }
               cnt[c]++;
            }
         }
      }
      return g;
   }
   /** The table as uploaded: rects (x0, y0, x1, y1 in page uv), codes (layer | kind << 4, -1 unused). */
   public static float[] sprRects = new float[SPR_MAX * 4];
   public static int[] sprCodes = new int[SPR_MAX];
   public static volatile int sprTex, sprCount;
   private static float[] sprProbes; // SPR_MAX x 5 rgba: probe colours 0..2, texel 3 (x1, y1, x2, y2), texel 4 (x3, y3)
   /** Exemplar size in squares (N x N lattice period, 2 N^2 diamond slots). */
   static final int N = 2;
   static final int LUT_N = 256, LUT_W = LUT_N + 4;

   /** The layer of a class byte (material | tone << 4), -1 for none: index material + 16 * min(tone, 3). */
   public static final int[] LAYER_OF = new int[64];

   static {
      Arrays.fill(LAYER_OF, -1);
      for (int i = 0; i < LAYERS.length; i++) {
         LayerDef d = LAYERS[i];
         LAYER_OF[d.material + 16 * Math.min(d.tone, 3)] = i;
      }
      // tones a material has no own layer for fall back to its tone 0 layer
      for (int m = 0; m < 16; m++) {
         for (int t = 1; t < 4; t++) {
            if (LAYER_OF[m + 16 * t] < 0) {
               LAYER_OF[m + 16 * t] = LAYER_OF[m];
            }
         }
      }
   }

   public static int layerOf(int classByte) {
      return LAYER_OF[(classByte & 15) + 16 * Math.min((classByte >> 4) & 15, 3)];
   }

   // ------------------------------------------------------------------------------------------------ state

   public static volatile int artTex, gaussTex, lutTex; // GL_TEXTURE_2D ids, layers stacked vertically (render thread), 0 until uploaded
   public static volatile int periodW, periodH, layers;
   public static volatile boolean failed;
   private static volatile Built built; // the worker's result, waiting for the upload
   private static boolean started;
   public static long buildMs, readbackMs, uploadMs;
   public static float[] seamRaw, seamFixed;
   public static int[] seamMode; // per layer: 0 mean guidance, 1 one-sided // per layer: seam energy before / after the repair
   public static final float[] DRY_TINT = {1F, 1F, 1F}; // dry grass (grassDark... the game's dry tone) over green grass

   static final class Built {
      int w, h, n;
      byte[] art, gauss;
      float[] lut;
   }

   /** Render thread: true once the art is on the GPU. Starts the read-back and the build on first call. */
   public static boolean ensure() {
      if (artTex != 0) {
         return true;
      }
      if (failed) {
         return false;
      }
      Built b = built;
      if (b != null) {
         upload(b);
         built = null;
         return artTex != 0;
      }
      if (!started) {
         started = true;
         try {
            long t0 = System.nanoTime();
            final float[][][] tiles = readTiles();
            readbackMs = (System.nanoTime() - t0) / 1000000L;
            if (tiles == null) {
               failed = true;
               return false;
            }
            Thread th = new Thread(() -> {
               try {
                  long t1 = System.nanoTime();
                  Built bb = build(tiles, Core.tileScale);
                  buildMs = (System.nanoTime() - t1) / 1000000L;
                  if (Config.DEV_GROUND_TILING_DUMP) {
                     dump(bb);
                  }
                  built = bb;
                  Log.info("ground tiling: art built: " + layers + " layers " + periodW + "x" + periodH + " in " + buildMs
                     + " ms (read-back " + readbackMs + " ms); seams " + Arrays.toString(seamRaw) + " -> " + Arrays.toString(seamFixed)
                     + " (one-sided " + Arrays.toString(seamMode) + ")");
               } catch (Throwable t) {
                  failed = true;
                  Log.warn("ground tiling: art build failed: " + t);
               }
            }, "pzopt-ground-art");
            th.setDaemon(true);
            th.start();
         } catch (Throwable t) {
            failed = true;
            Log.warn("ground tiling: art read-back failed: " + t);
         }
      }
      return false;
   }

   // ------------------------------------------------------------------------------------------------ read-back (render thread)

   /** Per layer, per sprite: the diamond rows (32ts x 64ts RGBA floats 0..1, row-major, 4 per px); null on failure. */
   static float[][][] readTiles() {
      int ts = Core.tileScale;
      int dw = 64 * ts, dh = 32 * ts;
      HashMap<Integer, ByteBuffer> pages = new HashMap<>();
      HashMap<Integer, int[]> pageSize = new HashMap<>();
      float[][][] out = new float[LAYERS.length][][];
      try {
         for (int l = 0; l < LAYERS.length; l++) {
            ArrayList<float[]> got = new ArrayList<>();
            for (String name : LAYERS[l].sprites) {
               Texture t = Texture.getSharedTexture(name);
               if (t == null || t.getTextureId() == null || t.getID() <= 0) {
                  continue;
               }
               int id = t.getID();
               ByteBuffer page = pages.get(id);
               if (page == null) {
                  int pw = t.getWidthHW(), ph = t.getHeightHW();
                  page = BufferUtils.createByteBuffer(pw * ph * 4);
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                  GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, page);
                  Texture.lastTextureID = -1;
                  pages.put(id, page);
                  pageSize.put(id, new int[] {pw, ph});
               }
               int[] ps = pageSize.get(id);
               float[] d = diamondOf(t, page, ps[0], ps[1], dw, dh);
               if (d != null) {
                  got.add(d);
               }
            }
            out[l] = got.toArray(new float[0][]);
         }
         ArrayList<Spr> tab = sprites();
         Arrays.fill(sprCodes, -1);
         float[] probes = new float[SPR_MAX * 5 * 4];
         int n = 0;
         for (Spr e : tab) {
            Texture t = Texture.getSharedTexture(e.name);
            if (t == null || t.getTextureId() == null || t.getID() <= 0 || n >= SPR_MAX) {
               continue;
            }
            int id = t.getID();
            ByteBuffer page = pages.get(id);
            if (page == null) {
               int pw = t.getWidthHW(), ph = t.getHeightHW();
               page = BufferUtils.createByteBuffer(pw * ph * 4);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
               GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, page);
               Texture.lastTextureID = -1;
               pages.put(id, page);
               pageSize.put(id, new int[] {pw, ph});
            }
            int[] ps = pageSize.get(id);
            if (!probe(t, page, ps[0], ps[1], e)) {
               continue;
            }
            sprRects[n * 4] = e.x0;
            sprRects[n * 4 + 1] = e.y0;
            sprRects[n * 4 + 2] = e.x1;
            sprRects[n * 4 + 3] = e.y1;
            sprCodes[n] = e.layer | e.kind << 4;
            for (int k = 0; k < 12; k++) {
               probes[n * 20 + k] = e.probe[k];
            }
            for (int k = 0; k < 6; k++) {
               probes[n * 20 + 12 + k] = e.pxy[k];
            }
            n++;
         }
         sprCount = n;
         sprProbes = probes;
      } finally {
         Texture.lastTextureID = -1;
      }
      return out;
   }

   /** The sprite's page rect (uv) and three opaque probe texels spread over it; false if it has none. */
   static boolean probe(Texture t, ByteBuffer page, int pw, int ph, Spr e) {
      int sx = t.getX(), sy = t.getY(), w = t.getWidth(), h = t.getHeight();
      if (sx < 0 || sy < 0) {
         sx = Math.round(t.getXStart() * pw);
         sy = Math.round(t.getYStart() * ph);
      }
      if (w <= 2 || h <= 2) {
         return false;
      }
      e.x0 = Math.min(t.getXStart(), t.getXEnd());
      e.x1 = Math.max(t.getXStart(), t.getXEnd());
      e.y0 = Math.min(t.getYStart(), t.getYEnd());
      e.y1 = Math.max(t.getYStart(), t.getYEnd());
      double[][] want = {{0.25, 0.5}, {0.5, 0.45}, {0.75, 0.55}};
      for (int k = 0; k < 3; k++) {
         int best = -1;
         double bd = 1e9;
         for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
               int p = ((sy + y) * pw + sx + x) * 4;
               if ((page.get(p + 3) & 255) < 250) {
                  continue;
               }
               double dx = (x - want[k][0] * w) / w, dy = (y - want[k][1] * h) / h;
               double d = dx * dx + dy * dy;
               if (d < bd) {
                  bd = d;
                  best = (sy + y) * pw + sx + x;
               }
            }
         }
         if (best < 0) {
            return false;
         }
         for (int c = 0; c < 4; c++) {
            e.probe[k * 4 + c] = (page.get(best * 4 + c) & 255) / 255F;
         }
         e.pxy[k * 2] = best % pw;
         e.pxy[k * 2 + 1] = best / pw;
      }
      e.ok = true;
      return true;
   }

   /** The diamond rows of a floor sprite (the last 32ts rows of its frame) out of its page; null if the frame is odd. */
   static float[] diamondOf(Texture t, ByteBuffer page, int pw, int ph, int dw, int dh) {
      int fw = t.getWidthOrig(), fh = t.getHeightOrig();
      if (fw != dw) {
         return null;
      }
      int ox = (int)t.getOffsetX(), oy = (int)t.getOffsetY(), w = t.getWidth(), h = t.getHeight();
      int sx = t.getX(), sy = t.getY();
      if (sx < 0 || sy < 0) {
         sx = Math.round(t.getXStart() * pw);
         sy = Math.round(t.getYStart() * ph);
      }
      float[] d = new float[dw * dh * 4];
      for (int y = 0; y < dh; y++) {
         int fy = fh - dh + y - oy;
         if (fy < 0 || fy >= h) {
            continue;
         }
         for (int x = 0; x < dw; x++) {
            int fx = x - ox;
            if (fx < 0 || fx >= w) {
               continue;
            }
            int p = ((sy + fy) * pw + (sx + fx)) * 4;
            int q = (y * dw + x) * 4;
            for (int c = 0; c < 4; c++) {
               d[q + c] = (page.get(p + c) & 255) / 255F;
            }
         }
      }
      return d;
   }

   // ------------------------------------------------------------------------------------------------ build (worker)

   /** The diamond mask of 2x / 1x tiles: row y covers [c - 1 - 2r, c + 1 + 2r) around column c = 32ts - 1, r = min(y, dh - 1 - y). */
   static boolean[] diamondMask(int ts) {
      int dw = 64 * ts, dh = 32 * ts, c = 32 * ts - 1;
      boolean[] m = new boolean[dw * dh];
      for (int y = 0; y < dh; y++) {
         int r = y < dh / 2 ? y : dh - 1 - y;
         for (int x = c - 1 - 2 * r; x < c + 1 + 2 * r; x++) {
            if (x >= 0 && x < dw) {
               m[y * dw + x] = true;
            }
         }
      }
      return m;
   }

   static int[][] slots(int n) {
      int[][] s = new int[2 * n * n][];
      int k = 0;
      for (int i = 0; i < 2 * n; i++) {
         for (int j = 0; j < n; j++) {
            s[k++] = new int[] {i, j};
         }
      }
      return s;
   }

   static int canon(int[][] s, int n, int i, int j) {
      for (int k = 0; k < s.length; k++) {
         int di = i - s[k][0], dj = j - s[k][1];
         if (Math.floorMod(di - dj, 2 * n) == 0 && Math.floorMod(di + dj, 2 * n) == 0) {
            return k;
         }
      }
      throw new IllegalStateException("coset");
   }

   /** A slot -> variant layout with as few equal neighbours as possible (deterministic). */
   static int[] deal(int nvar, int n, long seed) {
      int[][] s = slots(n);
      ArrayList<int[]> pairs = new ArrayList<>();
      for (int k = 0; k < s.length; k++) {
         int[][] d = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
         for (int[] dd : d) {
            int m = canon(s, n, s[k][0] + dd[0], s[k][1] + dd[1]);
            if (k < m) {
               pairs.add(new int[] {k, m});
            }
         }
      }
      SplittableRandom rnd = new SplittableRandom(seed);
      int[] best = null;
      int bad = Integer.MAX_VALUE;
      for (int it = 0; it < 4000 && bad > 0; it++) {
         int[] lay = new int[s.length];
         for (int k = 0; k < lay.length; k++) {
            lay[k] = k % nvar;
         }
         for (int k = lay.length - 1; k > 0; k--) {
            int r = rnd.nextInt(k + 1);
            int t = lay[k];
            lay[k] = lay[r];
            lay[r] = t;
         }
         int b = 0;
         for (int[] p : pairs) {
            if (lay[p[0]] == lay[p[1]]) {
               b++;
            }
         }
         if (b < bad) {
            bad = b;
            best = lay;
         }
      }
      return best;
   }

   static Built build(float[][][] tiles, int ts) {
      int dw = 64 * ts, dh = 32 * ts, pw = dw * N, ph = dh * N;
      boolean[] mask = diamondMask(ts);
      int L = tiles.length;
      Built b = new Built();
      b.w = pw;
      b.h = ph;
      b.n = L;
      b.art = new byte[L * pw * ph * 4];
      b.gauss = new byte[L * pw * ph * 4];
      b.lut = new float[L * 4 * LUT_W * 4];
      seamRaw = new float[L];
      seamFixed = new float[L];
      seamMode = new int[L];
      float[][] means = new float[L][];
      for (int l = 0; l < L; l++) {
         float[][] t = tiles[l];
         if (t == null || t.length == 0) {
            continue; // the layer stays black: GroundTiling never samples a layer without art (layerReady)
         }
         int[] lay = deal(t.length, N, 0x9E3779B97F4A7C15L + l);
         int[][] s = slots(N);
         float[] img = new float[pw * ph * 3];
         int[] lab = new int[pw * ph];
         Arrays.fill(lab, -1);
         for (int k = 0; k < s.length; k++) {
            float[] tile = t[lay[k]];
            int ox = 32 * ts * (s[k][0] - s[k][1]), oy = 16 * ts * (s[k][0] + s[k][1]);
            for (int y = 0; y < dh; y++) {
               for (int x = 0; x < dw; x++) {
                  if (!mask[y * dw + x]) {
                     continue;
                  }
                  int X = Math.floorMod(x + ox, pw), Y = Math.floorMod(y + oy, ph);
                  int q = (y * dw + x) * 4, p = Y * pw + X;
                  img[p * 3] = tile[q];
                  img[p * 3 + 1] = tile[q + 1];
                  img[p * 3 + 2] = tile[q + 2];
                  lab[p] = k;
               }
            }
         }
         // the per-slot self images: the slot's tile laid on its own one-square period
         float[][] self = new float[s.length][];
         for (int k = 0; k < s.length; k++) {
            float[] one = new float[dw * dh * 3];
            float[] tile = t[lay[k]];
            for (int[] o : new int[][] {{0, 0}, {32 * ts, 16 * ts}}) {
               for (int y = 0; y < dh; y++) {
                  for (int x = 0; x < dw; x++) {
                     if (mask[y * dw + x]) {
                        int X = Math.floorMod(x + o[0], dw), Y = Math.floorMod(y + o[1], dh);
                        int q = (y * dw + x) * 4, p = (Y * dw + X) * 3;
                        one[p] = tile[q];
                        one[p + 1] = tile[q + 1];
                        one[p + 2] = tile[q + 2];
                     }
                  }
               }
            }
            self[k] = one;
         }
         seamRaw[l] = seamEnergy(img, lab, pw, ph);
         // two guidances across a seam: the mean of the two slots' own gradients (best where the variants are one
         // painting with small changes, the game's grass) or one slot's, alternating (independent variants: the mean
         // halves the gradient there); the layer keeps the one whose seams come closest to the interior's statistics
         float[] one = img.clone();
         poissonSelf(img, lab, self, pw, ph, dw, dh, false);
         poissonSelf(one, lab, self, pw, ph, dw, dh, true);
         float eAvg = seamEnergy(img, lab, pw, ph), eOne = seamEnergy(one, lab, pw, ph);
         if (Math.abs(Math.log(eOne)) < Math.abs(Math.log(eAvg))) {
            img = one;
         }
         seamFixed[l] = Math.min(Math.abs(Math.log(eOne)), Math.abs(Math.log(eAvg))) == Math.abs(Math.log(eOne)) ? eOne : eAvg;
         seamMode[l] = img == one ? 1 : 0;
         float[] h = heights(img, pw * ph);
         means[l] = mean(img, pw * ph);
         int base = l * pw * ph * 4;
         for (int p = 0; p < pw * ph; p++) {
            for (int c = 0; c < 3; c++) {
               b.art[base + p * 4 + c] = (byte)Math.round(clamp01(img[p * 3 + c]) * 255F);
            }
            b.art[base + p * 4 + 3] = (byte)Math.round(h[p] * 255F);
         }
         gaussianise(img, h, pw * ph, b.gauss, base, b.lut, l * 4 * LUT_W * 4);
      }
      // dry grass tint: the game's dark (dry) grass tone over its light tone
      if (L > 3 && means[1] != null && means[3] != null) {
         for (int c = 0; c < 3; c++) {
            DRY_TINT[c] = means[3][c] / Math.max(means[1][c], 1e-3F);
         }
      }
      periodW = pw;
      periodH = ph;
      layers = L;
      return b;
   }

   /** dev (devGroundTilingDump): every layer's art and Gaussian exemplar as PNGs, and the sprite table, under Zomboid/pzopt/ground-tiling/. */
   static void dump(Built b) {
      try {
         java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt" + java.io.File.separator + "ground-tiling");
         dir.mkdirs();
         for (int l = 0; l < b.n; l++) {
            for (int g = 0; g < 2; g++) {
               byte[] src = g == 0 ? b.art : b.gauss;
               java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(b.w, b.h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
               for (int y = 0; y < b.h; y++) {
                  for (int x = 0; x < b.w; x++) {
                     int p = ((l * b.h + y) * b.w + x) * 4;
                     img.setRGB(x, y, 0xFF000000 | (src[p] & 255) << 16 | (src[p + 1] & 255) << 8 | (src[p + 2] & 255));
                  }
               }
               javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, (g == 0 ? "art-" : "gauss-") + LAYERS[l].name + ".png"));
            }
         }
         StringBuilder sb = new StringBuilder();
         for (int i = 0; i < sprCount; i++) {
            sb.append(String.format("%d code %d layer %d kind %d rect %.5f %.5f %.5f %.5f%n", i, sprCodes[i], sprCodes[i] & 15, sprCodes[i] >> 4,
               sprRects[i * 4], sprRects[i * 4 + 1], sprRects[i * 4 + 2], sprRects[i * 4 + 3]));
         }
         java.nio.file.Files.writeString(new java.io.File(dir, "sprites.txt").toPath(), sb.toString());
         Log.info("ground tiling: art dumped to " + dir);
      } catch (Throwable t) {
         Log.warn("ground tiling: dump failed: " + t);
      }
   }

   static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   static float[] mean(float[] img, int n) {
      double[] m = new double[3];
      for (int p = 0; p < n; p++) {
         for (int c = 0; c < 3; c++) {
            m[c] += img[p * 3 + c];
         }
      }
      return new float[] {(float)(m[0] / n), (float)(m[1] / n), (float)(m[2] / n)};
   }

   static float luma(float[] img, int p) {
      return 0.299F * img[p * 3] + 0.587F * img[p * 3 + 1] + 0.114F * img[p * 3 + 2];
   }

   /** mean |luma gradient| across slot seams / inside slots, per direction (1.0 = no seam statistically). */
   static float seamEnergy(float[] img, int[] lab, int w, int h) {
      double sx = 0, nx = 0, ix = 0, mx = 0, sy = 0, ny = 0, iy = 0, my = 0;
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int p = y * w + x, px = y * w + (x + 1) % w, py = ((y + 1) % h) * w + x;
            float l = luma(img, p);
            double gx = Math.abs(luma(img, px) - l), gy = Math.abs(luma(img, py) - l);
            if (lab[p] != lab[px]) {
               sx += gx;
               nx++;
            } else {
               ix += gx;
               mx++;
            }
            if (lab[p] != lab[py]) {
               sy += gy;
               ny++;
            } else {
               iy += gy;
               my++;
            }
         }
      }
      double a = nx > 0 ? (sx / nx) / Math.max(ix / Math.max(mx, 1), 1e-9) : 1, b = ny > 0 ? (sy / ny) / Math.max(iy / Math.max(my, 1), 1e-9) : 1;
      return (float)(0.5 * (a + b));
   }

   /**
    * Gradient-domain seam repair with periodic boundaries (FFT Poisson): guidance = the image gradient inside a slot, the
    * mean of the two slots' own (self-period) gradients across a seam; each channel's mean is kept.
    */
   static void poissonSelf(float[] img, int[] lab, float[][] self, int w, int h, int dw, int dh, boolean oneSided) {
      double[] re = new double[w * h], im = new double[w * h];
      double[] den = new double[w * h];
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            den[y * w + x] = (2 * Math.cos(2 * Math.PI * x / w) - 2) + (2 * Math.cos(2 * Math.PI * y / h) - 2);
         }
      }
      double[] gx = new double[w * h], gy = new double[w * h];
      for (int c = 0; c < 3; c++) {
         double mean = 0;
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               int p = y * w + x, px = y * w + (x + 1) % w, py = ((y + 1) % h) * w + x;
               double u = img[p * 3 + c];
               mean += u;
               if (lab[p] == lab[px]) {
                  gx[p] = img[px * 3 + c] - u;
               } else {
                  gx[p] = oneSided ? selfGrad(self[lab[((x ^ y) & 1) == 0 ? p : px]], dw, dh, x, y, 1, 0, c)
                     : 0.5 * (selfGrad(self[lab[p]], dw, dh, x, y, 1, 0, c) + selfGrad(self[lab[px]], dw, dh, x, y, 1, 0, c));
               }
               if (lab[p] == lab[py]) {
                  gy[p] = img[py * 3 + c] - u;
               } else {
                  gy[p] = oneSided ? selfGrad(self[lab[((x ^ y) & 1) == 0 ? p : py]], dw, dh, x, y, 0, 1, c)
                     : 0.5 * (selfGrad(self[lab[p]], dw, dh, x, y, 0, 1, c) + selfGrad(self[lab[py]], dw, dh, x, y, 0, 1, c));
               }
            }
         }
         mean /= w * h;
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               int p = y * w + x;
               int lx = y * w + (x + w - 1) % w, ly = ((y + h - 1) % h) * w + x;
               re[p] = (gx[p] - gx[lx]) + (gy[p] - gy[ly]);
               im[p] = 0;
            }
         }
         fft2(re, im, w, h, false);
         for (int p = 1; p < w * h; p++) {
            re[p] /= den[p];
            im[p] /= den[p];
         }
         re[0] = mean * w * h;
         im[0] = 0;
         fft2(re, im, w, h, true);
         for (int p = 0; p < w * h; p++) {
            img[p * 3 + c] = clamp01((float)re[p]);
         }
      }
   }

   /** a slot's own gradient at (x, y) toward (x + dx, y + dy), read from its self-period image */
   static double selfGrad(float[] one, int dw, int dh, int x, int y, int dx, int dy, int c) {
      int x0 = Math.floorMod(x, dw), y0 = Math.floorMod(y, dh), x1 = Math.floorMod(x + dx, dw), y1 = Math.floorMod(y + dy, dh);
      return one[(y1 * dw + x1) * 3 + c] - one[(y0 * dw + x0) * 3 + c];
   }

   /** in-place 2D FFT (power-of-two sizes); inverse scales by 1 / (w h) */
   static void fft2(double[] re, double[] im, int w, int h, boolean inverse) {
      double[] r = new double[Math.max(w, h)], i = new double[Math.max(w, h)];
      for (int y = 0; y < h; y++) {
         System.arraycopy(re, y * w, r, 0, w);
         System.arraycopy(im, y * w, i, 0, w);
         fft(r, i, w, inverse);
         System.arraycopy(r, 0, re, y * w, w);
         System.arraycopy(i, 0, im, y * w, w);
      }
      for (int x = 0; x < w; x++) {
         for (int y = 0; y < h; y++) {
            r[y] = re[y * w + x];
            i[y] = im[y * w + x];
         }
         fft(r, i, h, inverse);
         for (int y = 0; y < h; y++) {
            re[y * w + x] = r[y];
            im[y * w + x] = i[y];
         }
      }
      if (inverse) {
         double s = 1.0 / (w * h);
         for (int p = 0; p < w * h; p++) {
            re[p] *= s;
            im[p] *= s;
         }
      }
   }

   static void fft(double[] re, double[] im, int n, boolean inverse) {
      for (int i = 1, j = 0; i < n; i++) {
         int bit = n >> 1;
         for (; (j & bit) != 0; bit >>= 1) {
            j ^= bit;
         }
         j ^= bit;
         if (i < j) {
            double t = re[i];
            re[i] = re[j];
            re[j] = t;
            t = im[i];
            im[i] = im[j];
            im[j] = t;
         }
      }
      for (int len = 2; len <= n; len <<= 1) {
         double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
         double wr = Math.cos(ang), wi = Math.sin(ang);
         for (int i = 0; i < n; i += len) {
            double cr = 1, ci = 0;
            for (int k = 0; k < len / 2; k++) {
               int a = i + k, b = i + k + len / 2;
               double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
               re[b] = re[a] - tr;
               im[b] = im[a] - ti;
               re[a] += tr;
               im[a] += ti;
               double nr = cr * wr - ci * wi;
               ci = cr * wi + ci * wr;
               cr = nr;
            }
         }
      }
   }

   /** height: luminance over the layer's 1..99 % range */
   static float[] heights(float[] img, int n) {
      float[] l = new float[n];
      for (int p = 0; p < n; p++) {
         l[p] = luma(img, p);
      }
      float[] s = l.clone();
      Arrays.sort(s);
      float lo = quantile(s, 0.01), hi = quantile(s, 0.99);
      float k = 1F / Math.max(hi - lo, 1e-4F);
      for (int p = 0; p < n; p++) {
         l[p] = clamp01((l[p] - lo) * k);
      }
      return l;
   }

   /** numpy's default ('linear') quantile of sorted values */
   static float quantile(float[] sorted, double q) {
      double idx = q * (sorted.length - 1);
      int i = (int)Math.floor(idx);
      if (i >= sorted.length - 1) {
         return sorted[sorted.length - 1];
      }
      double f = idx - i;
      return (float)(sorted[i] + (sorted[i + 1] - sorted[i]) * f);
   }

   /**
    * Heitz and Neyret, full version: PCA-decorrelate the rgb, histogram-transform each channel to a Gaussian (mean 0.5,
    * sd 1/6); LUT rows 0..2 the inverse CDF (Gaussian value i / LUT_N -> decorrelated value), row 3 the basis columns
    * (texels 0..2) and the mean (texel 3).
    */
   static void gaussianise(float[] img, float[] h, int n, byte[] out, int base, float[] lut, int lbase) {
      double[] mu = new double[3];
      for (int p = 0; p < n; p++) {
         for (int c = 0; c < 3; c++) {
            mu[c] += img[p * 3 + c];
         }
      }
      for (int c = 0; c < 3; c++) {
         mu[c] /= n;
      }
      double[][] cov = new double[3][3];
      for (int p = 0; p < n; p++) {
         for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
               cov[a][b] += (img[p * 3 + a] - mu[a]) * (img[p * 3 + b] - mu[b]);
            }
         }
      }
      for (int a = 0; a < 3; a++) {
         for (int b = 0; b < 3; b++) {
            cov[a][b] /= n - 1;
         }
      }
      double[][] E = jacobiEigenvectors(cov); // columns
      float[][] d = new float[3][n];
      for (int p = 0; p < n; p++) {
         for (int k = 0; k < 3; k++) {
            double v = 0;
            for (int c = 0; c < 3; c++) {
               v += (img[p * 3 + c] - mu[c]) * E[c][k];
            }
            d[k][p] = (float)v;
         }
      }
      for (int k = 0; k < 3; k++) {
         Integer[] order = new Integer[n];
         for (int p = 0; p < n; p++) {
            order[p] = p;
         }
         final float[] dk = d[k];
         Arrays.sort(order, (x, y) -> Float.compare(dk[x], dk[y]));
         for (int r = 0; r < n; r++) {
            double u = (r + 0.5) / n;
            double g = 0.5 + invNorm(u) / 6.0;
            out[base + order[r] * 4 + k] = (byte)Math.round(Math.min(1, Math.max(0, g)) * 255.0);
         }
         float[] sorted = dk.clone();
         Arrays.sort(sorted);
         for (int i = 0; i <= LUT_N; i++) {
            double x = (double)i / LUT_N;
            double q = Math.min(1, Math.max(0, norm((x - 0.5) * 6.0)));
            lut[lbase + (k * LUT_W + i) * 4] = quantile(sorted, q);
         }
      }
      for (int p = 0; p < n; p++) {
         out[base + p * 4 + 3] = (byte)Math.round(h[p] * 255F);
      }
      int r3 = lbase + 3 * LUT_W * 4;
      for (int k = 0; k < 3; k++) {
         for (int c = 0; c < 3; c++) {
            lut[r3 + k * 4 + c] = (float)E[c][k];
         }
      }
      for (int c = 0; c < 3; c++) {
         lut[r3 + 3 * 4 + c] = (float)mu[c];
      }
   }

   /** eigenvectors (columns) of a symmetric 3x3 matrix, cyclic Jacobi */
   static double[][] jacobiEigenvectors(double[][] a0) {
      double[][] a = new double[3][3];
      for (int i = 0; i < 3; i++) {
         a[i] = a0[i].clone();
      }
      double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
      for (int sweep = 0; sweep < 50; sweep++) {
         double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
         if (off < 1e-15) {
            break;
         }
         for (int p = 0; p < 2; p++) {
            for (int q = p + 1; q < 3; q++) {
               if (Math.abs(a[p][q]) < 1e-18) {
                  continue;
               }
               double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
               double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
               if (theta == 0) {
                  t = 1;
               }
               double c = 1 / Math.sqrt(t * t + 1), s = t * c;
               for (int k = 0; k < 3; k++) {
                  double akp = a[k][p], akq = a[k][q];
                  a[k][p] = c * akp - s * akq;
                  a[k][q] = s * akp + c * akq;
               }
               for (int k = 0; k < 3; k++) {
                  double apk = a[p][k], aqk = a[q][k];
                  a[p][k] = c * apk - s * aqk;
                  a[q][k] = s * apk + c * aqk;
               }
               for (int k = 0; k < 3; k++) {
                  double vkp = v[k][p], vkq = v[k][q];
                  v[k][p] = c * vkp - s * vkq;
                  v[k][q] = s * vkp + c * vkq;
               }
            }
         }
      }
      return v;
   }

   /** standard normal CDF (erf via Abramowitz-Stegun 7.1.26 refined by one Newton-free series is enough here) */
   static double norm(double x) {
      return 0.5 * erfc(-x / Math.sqrt(2));
   }

   static double erfc(double x) {
      // Numerical Recipes erfcc (fractional error < 1.2e-7)
      double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
      double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
         + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
      return x >= 0 ? r : 2 - r;
   }

   /** inverse standard normal CDF (Acklam, relative error < 1.2e-9 after one Halley step) */
   static double invNorm(double p) {
      double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
      double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
      double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
      double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
      double q, r, x;
      if (p < 0.02425) {
         q = Math.sqrt(-2 * Math.log(p));
         x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
      } else if (p > 1 - 0.02425) {
         q = Math.sqrt(-2 * Math.log(1 - p));
         x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
      } else {
         q = p - 0.5;
         r = q * q;
         x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
      }
      double e = 0.5 * erfc(-x / Math.sqrt(2)) - p;
      double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
      return x - u / (1 + x * u / 2);
   }

   // ------------------------------------------------------------------------------------------------ upload (render thread)

   static void upload(Built b) {
      long t0 = System.nanoTime();
      try {
         // the layers stacked vertically (layer L at rows L * h): plain 2D textures (GroundTiling.GLSL says why)
         artTex = tex2d(b.w, b.h * b.n, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE, buf(b.art), null, Math.min(4, Integer.numberOfTrailingZeros(b.h)));
         gaussTex = tex2d(b.w, b.h * b.n, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE, buf(b.gauss), null, 0);
         FloatBuffer fb = BufferUtils.createFloatBuffer(b.lut.length);
         fb.put(b.lut).flip();
         lutTex = tex2d(LUT_W, 4 * b.n, GL30.GL_RGBA32F, GL11.GL_FLOAT, null, fb, 0);
         if (sprProbes != null) {
            float[] all = sprTexData(sprProbes, sprRects, sprCodes, sprCount);
            FloatBuffer ab = BufferUtils.createFloatBuffer(all.length);
            ab.put(all).flip();
            sprTex = tex2d(SPR_GRID, SPR_MAX + SPR_GRID, GL30.GL_RGBA32F, GL11.GL_FLOAT, null, ab, 0);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("ground tiling: art upload failed: " + t);
      } finally {
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         Texture.lastTextureID = -1;
      }
      uploadMs = (System.nanoTime() - t0) / 1000000L;
   }

   static ByteBuffer buf(byte[] a) {
      ByteBuffer bb = BufferUtils.createByteBuffer(a.length);
      bb.put(a).flip();
      return bb;
   }

   /** A 2D texture, nearest, {@code maxLevel} mip levels kept (0: none). */
   static int tex2d(int w, int h, int internal, int type, ByteBuffer bytes, FloatBuffer floats, int maxLevel) {
      int id = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
      if (bytes != null) {
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, type, bytes);
      } else {
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, type, floats);
      }
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, maxLevel > 0 ? GL11.GL_NEAREST_MIPMAP_NEAREST : GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, Math.max(0, maxLevel));
      if (maxLevel > 0) {
         GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
      }
      return id;
   }

   /** Render thread: bind the sprite table on {@code unit} and the art, Gaussian and LUT textures on unit + 1 .. + 3 (active unit back to 0). */
   public static void bind(int unit) {
      int[] ids = {sprTex, artTex, gaussTex, lutTex};
      for (int i = 0; i < 4; i++) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit + i);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, ids[i]);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   public static String stats() {
      return "ground art: layers " + layers + " " + periodW + "x" + periodH + ", read-back " + readbackMs + " ms, build " + buildMs
         + " ms, upload " + uploadMs + " ms, sprites " + sprCount + (failed ? " (FAILED)" : artTex == 0 ? " (not ready)" : "");
   }
}
