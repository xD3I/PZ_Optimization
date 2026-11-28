package pzopt;

import java.util.HashMap;
import java.util.Random;

/**
 * Binary greedy meshing: every face is covered exactly once by the merged quads, nothing else is (the plane merge, the
 * occupancy mesh), and the 3D port's quads cover the brute-force count of visible faces per direction.
 */
public class GreedyMesherTest {
   public static void main(String[] args) {
      planes();
      mesh3d();
      occupancy();
      System.out.println("GreedyMesherTest OK");
   }

   static void planes() {
      Random rnd = new Random(42);
      byte[] fm = new byte[64];
      for (int iter = 0; iter < 3000; iter++) {
         int n = 1 + rnd.nextInt(64);
         long[] rows = new long[n + 3];
         int off = rnd.nextInt(3);
         int style = iter % 4;
         for (int r = 0; r < n; r++) {
            long v = style == 0 ? rnd.nextLong() : style == 1 ? rnd.nextLong() & rnd.nextLong() & rnd.nextLong() : style == 2 ? -1L : 0L;
            if (style == 3) { // rectangles
               for (int k = 0; k < 3; k++) {
                  int a = rnd.nextInt(64), b = a + rnd.nextInt(64 - a);
                  if (rnd.nextBoolean()) {
                     v |= GreedyMesher.low(b + 1) & ~GreedyMesher.low(a);
                  }
               }
            }
            rows[off + r] = v;
         }
         long[] copy = rows.clone();
         for (boolean fwd : new boolean[] {true, false}) {
            long[] cover = new long[n];
            int[] bad = {0};
            int[] count = {0};
            int q = GreedyMesher.mergePlane(rows, off, n, fwd, fm, (row, bit, rs, bits) -> {
               count[0]++;
               if (rs < 1 || bits < 1 || row < 0 || row + rs > n || bit < 0 || bit + bits > 64 || !fwd && rs != 1) {
                  bad[0]++;
                  return;
               }
               long m = GreedyMesher.low(bit + bits) & ~GreedyMesher.low(bit);
               for (int r = row; r < row + rs; r++) {
                  if ((cover[r] & m) != 0L) {
                     bad[0]++; // covered twice
                  }
                  cover[r] |= m;
               }
            });
            Check.check(bad[0] == 0, "plane: rects in range and disjoint (iter " + iter + ")");
            Check.check(q == count[0], "plane: count returned");
            int bits = 0;
            for (int r = 0; r < n; r++) {
               Check.check(cover[r] == rows[off + r], "plane: exact cover row " + r + " iter " + iter + " fwd " + fwd);
               bits += Long.bitCount(rows[off + r]);
            }
            Check.check(q <= bits, "plane: no more rects than bits");
            for (byte b : fm) {
               Check.check(b == 0, "plane: scratch left zero");
            }
         }
         Check.check(java.util.Arrays.equals(rows, copy), "plane: rows unchanged");
      }
      // a full plane is one rect forward, one a row without
      long[] full = new long[16];
      java.util.Arrays.fill(full, -1L);
      Check.check(GreedyMesher.mergePlane(full, 0, 16, true, fm, (a, b, c, d) -> {}) == 1, "full plane = 1 rect");
      Check.check(GreedyMesher.mergePlane(full, 0, 16, false, fm, (a, b, c, d) -> {}) == 16, "full plane row by row = 16");
   }

   /** A 64^3 ZXY block: terrain (height by a hash), a few types, caves. */
   static byte[] terrain(long seed, long[] mask) {
      byte[] v = new byte[GreedyMesher.CS_P3];
      Random rnd = new Random(seed);
      double fx = 0.05 + rnd.nextDouble() * 0.1, fz = 0.05 + rnd.nextDouble() * 0.1;
      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            int hgt = (int)(28 + 14 * Math.sin(x * fx) * Math.cos(z * fz) + 6 * Math.sin((x + z) * 0.3));
            for (int y = 0; y < 64; y++) {
               boolean solid = y < hgt && !(y > 10 && y < 20 && ((x * 7 + z * 13 + y * 5) % 11 == 0));
               if (solid) {
                  byte type = (byte)(y > hgt - 2 ? 1 : y > hgt - 6 ? 2 : 3);
                  v[z + x * 64 + y * 4096] = type;
                  mask[x * 64 + z] |= 1L << y; // getAxisIndex(0, a=x, b=z, c=y)
               }
            }
         }
      }
      return v;
   }

   static void mesh3d() {
      GreedyMesher.Mesh m = new GreedyMesher.Mesh();
      for (int seed = 0; seed < 6; seed++) {
         long[] mask = new long[GreedyMesher.CS_P2];
         byte[] v = seed == 5 ? randomBlock(mask) : terrain(seed, mask);
         int q = GreedyMesher.mesh(v, mask, m);
         long[] want = new long[6];
         for (int a = 1; a < 63; a++) {
            for (int b = 1; b < 63; b++) {
               for (int c = 1; c < 63; c++) {
                  if (!op(mask, a, b, c)) {
                     continue;
                  }
                  want[0] += op(mask, a + 1, b, c) ? 0 : 1;
                  want[1] += op(mask, a - 1, b, c) ? 0 : 1;
                  want[2] += op(mask, a, b + 1, c) ? 0 : 1;
                  want[3] += op(mask, a, b - 1, c) ? 0 : 1;
                  want[4] += op(mask, a, b, c + 1) ? 0 : 1;
                  want[5] += op(mask, a, b, c - 1) ? 0 : 1;
               }
            }
         }
         long faces = 0;
         for (int f = 0; f < 6; f++) {
            long area = 0;
            for (int i = m.faceBegin[f]; i < m.faceBegin[f] + m.faceLength[f]; i++) {
               long qd = m.quads[i];
               area += (qd >>> 18 & 63) * (qd >>> 24 & 63);
            }
            Check.check(area == want[f], "3D: face " + f + " area " + area + " = visible faces " + want[f] + " (seed " + seed + ")");
            faces += want[f];
         }
         Check.check(q < faces, "3D: merged (" + q + " quads for " + faces + " faces)");
      }
   }

   static byte[] randomBlock(long[] mask) {
      byte[] v = new byte[GreedyMesher.CS_P3];
      Random rnd = new Random(7);
      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            for (int y = 0; y < 64; y++) {
               if (rnd.nextInt(3) == 0) {
                  v[z + x * 64 + y * 4096] = (byte)(1 + rnd.nextInt(2));
                  mask[x * 64 + z] |= 1L << y;
               }
            }
         }
      }
      return v;
   }

   static boolean op(long[] mask, int a, int b, int c) {
      return (mask[a * 64 + b] >>> c & 1L) != 0L;
   }

   /** The occupancy mesh covers each blocker exactly once, with its kind and type. */
   static void occupancy() {
      Random rnd = new Random(3);
      OccMesh.Out o = new OccMesh.Out();
      for (int iter = 0; iter < 40; iter++) {
         int[][] chunks = new int[64][];
         for (int c = 0; c < 64; c++) {
            if (rnd.nextInt(5) == 0) {
               continue;
            }
            int[] d = new int[64 * OccMesh.L];
            for (int i = 0; i < d.length; i++) {
               int lz = i >> 6;
               if (lz > 4 + iter % 6) {
                  continue;
               }
               int b = 0;
               int x = i & 7, y = i >> 3 & 7;
               if (lz == 0 || rnd.nextInt(4) == 0 || iter % 3 == 0 && x > 1 && x < 6 && y > 1 && y < 6) {
                  b |= rnd.nextInt(8) == 0 ? GodRays.B_ROOF : GodRays.B_FLOOR;
               }
               if (y == 2 || rnd.nextInt(6) == 0) {
                  b |= (rnd.nextInt(6) == 0 ? 1 + rnd.nextInt(3) : 1) << 1;
               }
               if (x == 5 || rnd.nextInt(6) == 0) {
                  b |= (rnd.nextInt(6) == 0 ? 1 + rnd.nextInt(3) : 1) << 3;
               }
               if (rnd.nextInt(10) == 0) {
                  b |= GodRays.B_ROOM | 123 << 16; // bits the mesh ignores (room, hash)
               }
               if (rnd.nextInt(8) == 0) {
                  b |= (1 + rnd.nextInt(rnd.nextBoolean() ? 2 : 15)) << 8; // a tree crown, density 1..15 (few densities: boxes merge)
               }
               d[i] = b;
            }
            chunks[c] = d;
         }
         int x0 = 640 + iter * 64, y0 = -128;
         int n = OccMesh.mesh(chunks, x0, y0, o);
         HashMap<Long, Integer> got = new HashMap<>();
         for (int i = 0; i < n; i++) {
            int x = o.q[i * 4], y = o.q[i * 4 + 1], zw = o.q[i * 4 + 2], wh = o.q[i * 4 + 3];
            int z = zw & 255, kind = zw >> 8 & 3, type = zw >> 10 & 3, w = wh & 255, h = wh >> 8 & 255;
            if (kind == OccMesh.K_CROWN) {
               type = zw >> 12 & 15; // a crown box: its density, merged in x and y like a floor
               Check.check((zw >> 10 & 3) == 0 && type >= 1, "occ: crown density");
            }
            Check.check(w >= 1 && h >= 1, "occ: quad size");
            Check.check(kind == OccMesh.K_FLOOR || kind == OccMesh.K_CROWN || type == GodRays.E_WALL || h == 1, "occ: windows / doorways one level tall");
            for (int a = 0; a < w; a++) {
               for (int b = 0; b < h; b++) {
                  int sx, sy, sz;
                  if (kind == OccMesh.K_FLOOR || kind == OccMesh.K_CROWN) {
                     sx = x + a; sy = y + b; sz = z;
                  } else if (kind == OccMesh.K_N) {
                     sx = x + a; sy = y; sz = z + b;
                  } else {
                     sx = x; sy = y + a; sz = z + b;
                  }
                  long key = (((long)(sx - x0) * 64 + (sy - y0)) * 16 + sz) * 4 + kind;
                  Check.check(got.put(key, type) == null, "occ: face covered once");
               }
            }
         }
         int want = 0;
         for (int c = 0; c < 64; c++) {
            int[] d = chunks[c];
            if (d == null) {
               continue;
            }
            for (int i = 0; i < d.length; i++) {
               int b = d[i];
               int rx = (c & 7) * 8 + (i & 7), ry = (c >> 3) * 8 + (i >> 3 & 7), lz = i >> 6;
               long base = (((long)rx * 64 + ry) * 16 + lz) * 4;
               if ((b & (GodRays.B_FLOOR | GodRays.B_ROOF)) != 0) {
                  Check.check(Integer.valueOf(0).equals(got.get(base + OccMesh.K_FLOOR)), "occ: floor at " + rx + "," + ry + "," + lz);
                  want++;
               }
               if ((b >> 1 & 3) != 0) {
                  Check.check(Integer.valueOf(b >> 1 & 3).equals(got.get(base + OccMesh.K_N)), "occ: N edge type");
                  want++;
               }
               if ((b >> 3 & 3) != 0) {
                  Check.check(Integer.valueOf(b >> 3 & 3).equals(got.get(base + OccMesh.K_W)), "occ: W edge type");
                  want++;
               }
               if ((b >> 8 & 15) != 0) {
                  Check.check(Integer.valueOf(b >> 8 & 15).equals(got.get(base + OccMesh.K_CROWN)), "occ: crown density at " + rx + "," + ry + "," + lz);
                  want++;
               }
            }
         }
         Check.check(got.size() == want, "occ: nothing extra covered (" + got.size() + " vs " + want + ")");
         Check.check(n <= want, "occ: merged");
      }
   }
}
