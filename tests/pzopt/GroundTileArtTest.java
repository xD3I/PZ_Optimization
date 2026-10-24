package pzopt;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.SplittableRandom;
import javax.imageio.ImageIO;

/**
 * Ground without the grid, the runtime art builder (GroundTileArt, the port of harness/ground-tiling/art.py + rig.py):
 * the diamond mask is one lattice cell, the slots cover the iso period exactly once, the FFT inverts, the seam repair
 * brings the seam statistics back to the interior's, the Gaussianised exemplar maps back through its LUT, and the class
 * table points every natural material at a layer. With the sprites the offline rig extracted (build/ground-tiling/sprites)
 * it also builds the real layers and prints their seam energies (compare with art.py's).
 */
public class GroundTileArtTest {
   public static void main(String[] args) throws Exception {
      for (int ts = 1; ts <= 2; ts++) {
         boolean[] m = GroundTileArt.diamondMask(ts);
         int n = 0;
         for (boolean b : m) {
            n += b ? 1 : 0;
         }
         Check.check(n == 64 * ts * 32 * ts / 2, "diamond mask is one lattice cell at ts " + ts + ": " + n);
      }
      double[] re = new double[64 * 32], im = new double[64 * 32], keep = new double[64 * 32];
      SplittableRandom r = new SplittableRandom(7);
      for (int i = 0; i < re.length; i++) {
         re[i] = keep[i] = r.nextDouble();
      }
      GroundTileArt.fft2(re, im, 64, 32, false);
      GroundTileArt.fft2(re, im, 64, 32, true);
      for (int i = 0; i < re.length; i++) {
         Check.check(Math.abs(re[i] - keep[i]) < 1e-9 && Math.abs(im[i]) < 1e-9, "fft round trip");
      }
      for (double p : new double[] {1e-6, 0.01, 0.3, 0.5, 0.77, 0.999}) {
         Check.check(Math.abs(GroundTileArt.norm(GroundTileArt.invNorm(p)) - p) < 1e-6, "invNorm inverts norm at " + p);
      }
      // synthetic layer: four variants of smooth noise at different brightness (seams obvious), ts 2
      int ts = 2, dw = 128, dh = 64;
      float[][][] tiles = new float[1][4][];
      for (int v = 0; v < 4; v++) {
         float[] t = new float[dw * dh * 4];
         // a texture periodic on the diamond lattice (wave vectors (p / 128, q / 64) with p + q even), like the
         // game's whole-square variants that tile with themselves
         SplittableRandom rv = new SplittableRandom(100 + v);
         int nw = 40;
         int[][] pq = new int[nw][];
         double[] ph = new double[nw];
         for (int w = 0; w < nw; w++) {
            int pp, qq;
            do {
               pp = rv.nextInt(-10, 11);
               qq = rv.nextInt(-10, 11);
            } while ((pp + qq & 1) != 0 || pp == 0 && qq == 0);
            pq[w] = new int[] {pp, qq};
            ph[w] = rv.nextDouble() * 2 * Math.PI;
         }
         for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
               double f = 0;
               for (int w = 0; w < nw; w++) {
                  f += Math.cos(2 * Math.PI * (pq[w][0] * x / 128.0 + pq[w][1] * y / 64.0) + ph[w]);
               }
               float s = (float)(0.5 + f / (2 * Math.sqrt(nw)));
               int k = 1;
               float base = 0.25F + 0.12F * v;
               int q = (y * dw + x) * 4;
               t[q] = base + 0.2F * (s / k - 0.5F);
               t[q + 1] = base * 1.2F + 0.25F * (s / k - 0.5F);
               t[q + 2] = base * 0.6F + 0.1F * (s / k - 0.5F);
               t[q + 3] = 1F;
            }
         }
         tiles[0][v] = t;
      }
      GroundTileArt.Built b = GroundTileArt.build(tiles, ts);
      Check.check(b.w == 128 * GroundTileArt.N && b.h == 64 * GroundTileArt.N && b.n == 1, "period " + b.w + "x" + b.h);
      float raw = GroundTileArt.seamRaw[0], fixed = GroundTileArt.seamFixed[0];
      System.out.println("synthetic seams " + raw + " -> " + fixed);
      Check.check(raw > 1.5F && fixed > 0.8F && fixed < 1.25F, "seam repair: " + raw + " -> " + fixed);
      // the Gaussianised exemplar decodes back to the art through the LUT (as gtHpb does with one sample)
      double err = 0;
      int np = b.w * b.h;
      int lw = GroundTileArt.LUT_W, ln = GroundTileArt.LUT_N;
      for (int p = 0; p < np; p++) {
         double[] d = new double[3];
         for (int k = 0; k < 3; k++) {
            double g = (b.gauss[p * 4 + k] & 255) / 255.0;
            d[k] = b.lut[(k * lw + (int)(g * ln + 0.5)) * 4];
         }
         for (int c = 0; c < 3; c++) {
            double v = b.lut[(3 * lw + 3) * 4 + c];
            for (int k = 0; k < 3; k++) {
               v += b.lut[(3 * lw + k) * 4 + c] * d[k];
            }
            err += Math.abs(Math.min(1, Math.max(0, v)) - (b.art[p * 4 + c] & 255) / 255.0);
         }
      }
      err /= np * 3;
      System.out.println("hpb decode mean |error| " + err * 255 + " / 255");
      Check.check(err * 255 < 3.0, "gaussianised exemplar decodes back: " + err * 255);
      for (int[] mt : new int[][] {{1, 0}, {1, 1}, {1, 2}, {2, 0}, {3, 0}, {4, 0}, {8, 0}, {9, 0}, {2, 2}}) {
         int l = GroundTileArt.layerOf(mt[0] | mt[1] << 4);
         Check.check(l >= 0 && GroundTileArt.LAYERS[l].material == mt[0], "material " + mt[0] + " tone " + mt[1] + " has a layer: " + l);
      }
      Check.check(GroundTileArt.layerOf(0) < 0 && GroundTileArt.layerOf(5) < 0, "non-natural classes have no layer");
      File dir = new File("build/ground-tiling/sprites");
      if (dir.isDirectory()) {
         float[][][] real = new float[GroundTileArt.LAYERS.length][][];
         for (int l = 0; l < real.length; l++) {
            String[] names = GroundTileArt.LAYERS[l].sprites;
            java.util.ArrayList<float[]> got = new java.util.ArrayList<>();
            for (String nm : names) {
               File f = new File(dir, nm + ".png");
               if (!f.exists()) {
                  continue;
               }
               BufferedImage img = ImageIO.read(f);
               float[] d = new float[dw * dh * 4];
               for (int y = 0; y < dh; y++) {
                  for (int x = 0; x < dw; x++) {
                     int argb = img.getRGB(x, img.getHeight() - dh + y);
                     int q = (y * dw + x) * 4;
                     d[q] = (argb >> 16 & 255) / 255F;
                     d[q + 1] = (argb >> 8 & 255) / 255F;
                     d[q + 2] = (argb & 255) / 255F;
                     d[q + 3] = (argb >>> 24) / 255F;
                  }
               }
               got.add(d);
            }
            real[l] = got.toArray(new float[0][]);
         }
         long t0 = System.nanoTime();
         GroundTileArt.build(real, 2);
         long ms = (System.nanoTime() - t0) / 1000000L;
         StringBuilder s = new StringBuilder("real layers built in " + ms + " ms:");
         for (int l = 0; l < real.length; l++) {
            s.append(String.format(" %s %d var %.2f->%.2f%s", GroundTileArt.LAYERS[l].name, real[l].length, GroundTileArt.seamRaw[l], GroundTileArt.seamFixed[l], GroundTileArt.seamMode[l] == 1 ? "(one-sided)" : ""));
         }
         System.out.println(s);
      }
      System.out.println("GroundTileArtTest ok");
   }
}
