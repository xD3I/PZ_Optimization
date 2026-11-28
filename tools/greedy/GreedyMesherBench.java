package pzopt;

/**
 * Times GreedyMesher.mesh (the Java port of binary-greedy-meshing v2) on the same 64^3 blocks as the C++ original
 * (tools/greedy/bgm_bench.cpp): writes them to <dir>/block-<i>.bin (64^3 voxels then 64^2 masks, little endian) and prints
 * us per block (median of 2000 runs after warm-up), quads and faces.
 * Usage: java -cp build/classes:build/tools pzopt.GreedyMesherBench <dir>
 */
public final class GreedyMesherBench {
   public static void main(String[] args) throws Exception {
      java.io.File dir = new java.io.File(args.length > 0 ? args[0] : ".");
      dir.mkdirs();
      GreedyMesher.Mesh m = new GreedyMesher.Mesh();
      String[] names = {"terrain-a", "terrain-b", "terrain-c", "random-1/3", "checker"};
      for (int i = 0; i < names.length; i++) {
         long[] mask = new long[GreedyMesher.CS_P2];
         byte[] v = block(i, mask);
         java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length + mask.length * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
         bb.put(v);
         for (long l : mask) bb.putLong(l);
         java.nio.file.Files.write(new java.io.File(dir, "block-" + i + ".bin").toPath(), bb.array());
         for (int w = 0; w < 3000; w++) GreedyMesher.mesh(v, mask, m);
         long[] t = new long[2000];
         for (int r = 0; r < t.length; r++) {
            long t0 = System.nanoTime();
            GreedyMesher.mesh(v, mask, m);
            t[r] = System.nanoTime() - t0;
         }
         java.util.Arrays.sort(t);
         System.out.printf(java.util.Locale.ROOT, "java %-11s median %7.1f us  p90 %7.1f us  quads %6d%n", names[i], t[t.length / 2] / 1000.0, t[t.length * 9 / 10] / 1000.0, m.count);
      }
   }

   static byte[] block(int kind, long[] mask) {
      byte[] v = new byte[GreedyMesher.CS_P3];
      java.util.Random rnd = new java.util.Random(kind);
      for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
         int hgt = (int)(28 + (10 + kind * 4) * Math.sin(x * (0.06 + kind * 0.03)) * Math.cos(z * (0.07 + kind * 0.02)) + 5 * Math.sin((x + 2 * z) * 0.21));
         for (int y = 0; y < 64; y++) {
            boolean s;
            if (kind <= 2) s = y < hgt && !(y > 8 && y < 18 && (x * 7 + z * 13 + y * 5) % 11 == 0);
            else if (kind == 3) s = rnd.nextInt(3) == 0;
            else s = ((x + y + z) & 1) == 0;
            if (s) {
               v[z + x * 64 + y * 4096] = (byte)(kind <= 2 ? (y > hgt - 2 ? 1 : y > hgt - 6 ? 2 : 3) : 1);
               mask[x * 64 + z] |= 1L << y;
            }
         }
      }
      return v;
   }
}
