package pzopt;

/**
 * The god rays' occupancy ({@link GodRays}: per square and level the floor, roof, N / W edge as wall / window / doorway) as
 * a mesh of merged quads, by {@link GreedyMesher#mergePlane} (binary greedy meshing, 2026-11-28; key {@code occMesh}).
 *
 * <p>Region: 64 x 64 squares (8 x 8 chunks) x {@link GodRays#OCC_L} levels, so one row of a plane is one 64-bit mask.
 * Its blockers as the DDA's ({@code GodRays.COMMON_GLSL} edgeBlocks / sunVis) see them:
 * <ul>
 *   <li>a floor or roof at level L: a horizontal quad at height L (levels), merged in x and y per level;</li>
 *   <li>a wall on a square's N (W) edge: a vertical quad on the line y (x), merged along the line and up the levels;</li>
 *   <li>a window: its parts below the sill (0.30) and above the head (0.84), a doorway its part above 0.80: merged along the
 *       line only (a window over a window is not one quad).</li>
 * </ul>
 *   <li>tree crowns (density 1..15): boxes one level tall, merged in x and y per level and density (each box is closed,
 *       so an additive pass of +-density x depth over its faces gives the optical length through the crowns).</li>
 * Quads, 4 ints each (16 bytes, read by vertex pulling): x, y (absolute squares), z | kind << 8 | type << 10 | density <<
 * 12 (z: level index 0..15 in the occupancy; kind 0 horizontal, 1 N edge, 2 W edge, 3 crown box; type 1 wall, 2 window, 3
 * doorway, 0 floor / roof / crown), w | h << 8 (squares along x or the line; squares along y or levels up).
 */
public final class OccMesh {
   private OccMesh() {
   }

   public static final int R = 64, RC = R / 8, L = GodRays.OCC_L;
   public static final int K_FLOOR = 0, K_N = 1, K_W = 2, K_CROWN = 3;
   /** devOccMeshNaive: one quad per face (the merge off), the A/B of what greedy meshing saves the passes that draw it. */
   static boolean naiveMode = Config.DEV_OCC_MESH_NAIVE;

   /** One region's mesh and its scratch (reused). */
   public static final class Out {
      public int[] q = new int[4096 * 4];
      public int n; // quads
      public int naive; // quads a mesh of one quad per square face would have
      public int x0, y0; // the region's first square
      final long[] h = new long[L * R]; // floors / roofs: [lz][y] bits x
      final long[] nE = new long[3 * R * L]; // N edges: [type - 1][y][lz] bits x
      final long[] wE = new long[3 * R * L]; // W edges: [type - 1][x][lz] bits y
      final long[] cr = new long[L * 16 * R]; // crowns: [lz][density][y] bits x
      final int[] crUsed = new int[L]; // the densities a level holds (bit d)
      final byte[] fm = new byte[64];
      public int crowns; // crown boxes
      private int type, line;

      void add(int x, int y, int z, int kind, int type, int w, int hh) {
         if (this.n * 4 == this.q.length) {
            this.q = java.util.Arrays.copyOf(this.q, this.q.length * 2);
         }
         int i = this.n++ * 4;
         this.q[i] = x;
         this.q[i + 1] = y;
         this.q[i + 2] = z | kind << 8 | type << 10;
         this.q[i + 3] = w | hh << 8;
      }

      final GreedyMesher.RectSink floorSink = (row, bit, rows, bits) -> this.add(this.x0 + bit, this.y0 + row, this.line, K_FLOOR, 0, bits, rows);
      final GreedyMesher.RectSink nSink = (row, bit, rows, bits) -> this.add(this.x0 + bit, this.y0 + this.line, row, K_N, this.type, bits, rows);
      final GreedyMesher.RectSink wSink = (row, bit, rows, bits) -> this.add(this.x0 + this.line, this.y0 + bit, row, K_W, this.type, bits, rows);
      final GreedyMesher.RectSink crownSink = (row, bit, rows, bits) -> {
         this.add(this.x0 + bit, this.y0 + row, this.line, K_CROWN, 0, bits, rows);
         this.q[this.n * 4 - 2] |= this.type << 12;
         this.crowns++;
      };
   }

   /** GreedyMesher.mergePlane, or one rect per bit (devOccMeshNaive). */
   static void merge(long[] rows, int off, int n, boolean forward, byte[] fm, GreedyMesher.RectSink out) {
      if (!naiveMode) {
         GreedyMesher.mergePlane(rows, off, n, forward, fm, out);
         return;
      }
      for (int r = 0; r < n; r++) {
         for (long b = rows[off + r]; b != 0L; b &= b - 1L) {
            out.rect(r, Long.numberOfTrailingZeros(b), 1, 1);
         }
      }
   }

   /**
    * Meshes the region whose first square is (x0, y0): chunks[cy * 8 + cx] is the occupancy of chunk (x0 / 8 + cx, y0 / 8
    * + cy) as GodRays.build makes it (index (lz * 8 + y) * 8 + x), null when empty. Returns the quad count.
    */
   public static int mesh(int[][] chunks, int x0, int y0, Out o) {
      o.n = 0;
      o.naive = 0;
      o.crowns = 0;
      o.x0 = x0;
      o.y0 = y0;
      long[] h = o.h, nE = o.nE, wE = o.wE;
      java.util.Arrays.fill(h, 0L);
      java.util.Arrays.fill(nE, 0L);
      java.util.Arrays.fill(wE, 0L);
      // the masks: 8 bits a chunk row, OR'ed into the region's 64-bit rows
      for (int cy = 0; cy < RC; cy++) {
         for (int cx = 0; cx < RC; cx++) {
            int[] d = chunks[cy * RC + cx];
            if (d == null) {
               continue;
            }
            for (int lz = 0; lz < L; lz++) {
               for (int y = 0; y < 8; y++) {
                  int base = (lz * 8 + y) * 8, ry = cy * 8 + y;
                  for (int x = 0; x < 8; x++) {
                     int b = d[base + x];
                     if (b == 0) {
                        continue;
                     }
                     int rx = cx * 8 + x;
                     if ((b & (GodRays.B_FLOOR | GodRays.B_ROOF)) != 0) {
                        h[lz * R + ry] |= 1L << rx;
                     }
                     int en = b >> 1 & 3, ew = b >> 3 & 3;
                     if (en != 0) {
                        nE[((en - 1) * R + ry) * L + lz] |= 1L << rx;
                     }
                     if (ew != 0) {
                        wE[((ew - 1) * R + rx) * L + lz] |= 1L << ry;
                     }
                     int fol = b >> 8 & 15;
                     if (fol != 0) {
                        o.cr[(lz * 16 + fol) * R + ry] |= 1L << rx;
                        o.crUsed[lz] |= 1 << fol;
                     }
                  }
               }
            }
         }
      }
      // naive: one quad per face (a window two)
      for (long v : h) {
         o.naive += Long.bitCount(v);
      }
      for (int t = 0; t < 3; t++) {
         int per = t == 1 ? 2 : 1;
         for (int i = t * R * L; i < (t + 1) * R * L; i++) {
            o.naive += per * (Long.bitCount(nE[i]) + Long.bitCount(wE[i]));
         }
      }
      // floors and roofs: per level, rows y, bits x, merged both ways
      for (int lz = 0; lz < L; lz++) {
         o.line = lz;
         merge(h, lz * R, R, true, o.fm, o.floorSink);
      }
      // crowns: per level and density, as floors (boxes one level tall); their rows cleared after (most are never used)
      for (int lz = 0; lz < L; lz++) {
         int used = o.crUsed[lz];
         o.crUsed[lz] = 0;
         o.line = lz;
         for (; used != 0; used &= used - 1) {
            int d = Integer.numberOfTrailingZeros(used);
            int off = (lz * 16 + d) * R;
            for (int i = off; i < off + R; i++) {
               o.naive += Long.bitCount(o.cr[i]);
            }
            o.type = d;
            merge(o.cr, off, R, true, o.fm, o.crownSink);
            java.util.Arrays.fill(o.cr, off, off + R, 0L);
         }
      }
      // edges: per type and line, rows the levels, bits along the line; walls merge up the levels, windows / doorways not
      for (int t = 1; t <= 3; t++) {
         o.type = t;
         boolean up = t == GodRays.E_WALL;
         for (int line = 0; line < R; line++) {
            o.line = line;
            int off = ((t - 1) * R + line) * L;
            merge(nE, off, L, up, o.fm, o.nSink);
            merge(wE, off, L, up, o.fm, o.wSink);
         }
      }
      return o.n;
   }

   /** The quad count a mesh of o's quads draws (a window quad is two: below the sill, above the head). */
   public static int drawn(Out o) {
      int d = 0;
      for (int i = 0; i < o.n; i++) {
         d += (o.q[i * 4 + 2] >> 10 & 3) == GodRays.E_WINDOW && (o.q[i * 4 + 2] >> 8 & 3) != K_FLOOR ? 2 : 1;
      }
      return d;
   }

   // ------------------------------------------------------------------------------------------------ the store (game thread)

   /** Whether a pass wants the mesh: god rays on (a live key) with godRaysVis=mesh* (the default), or devOccMeshStats. */
   public static boolean wanted() {
      return Config.OCC_MESH && (Config.GOD_RAYS || Config.DEV_OCC_MESH_STATS);
   }

   private static long key(int rx, int ry) {
      return (long)rx << 32 ^ ry & 0xFFFFFFFFL;
   }

   private static final java.util.HashMap<Long, int[]> regions = new java.util.HashMap<>(); // region -> its quads (n * 4)
   private static final java.util.HashSet<Long> dirty = new java.util.HashSet<>();
   private static final Out OUT = new Out();
   private static final int[][] CHUNKS = new int[RC * RC][];
   private static long serial, snapSerial = -1L;
   private static int[] snap = new int[0];
   private static int snapN, snapZ0;
   // stats (devOccMeshStats / the god rays' stats line)
   static long meshes, meshNs, meshMaxNs, quadsMade, naiveMade, framesWithMesh, crownsMade;
   static int liveQuads, liveNaive;

   /** GodRays built chunk (wx, wy)'s occupancy this frame: its region is meshed again. */
   static void chunkBuilt(int wx, int wy) {
      dirty.add(key(Math.floorDiv(wx, 8), Math.floorDiv(wy, 8)));
   }

   /** Every region again (the occupancy's level range moved). */
   static void allDirty() {
      dirty.addAll(regions.keySet());
   }

   /**
    * After GodRays' occupancy scan: the dirty regions meshed (all of them: a region costs ~10-60 us, see the stats), the ones
    * that left the window (chunks camW +-16) dropped.
    */
   static void update(int camWx, int camWy) {
      int half = GodRays.OCC_CHUNKS / 2;
      int rx0 = Math.floorDiv(camWx - half, 8), rx1 = Math.floorDiv(camWx + half - 1, 8);
      int ry0 = Math.floorDiv(camWy - half, 8), ry1 = Math.floorDiv(camWy + half - 1, 8);
      boolean changed = regions.entrySet().removeIf(e -> {
         int rx = (int)(e.getKey() >> 32), ry = (int)(long)e.getKey();
         return rx < rx0 || rx > rx1 || ry < ry0 || ry > ry1;
      });
      if (!dirty.isEmpty()) {
         long t0 = System.nanoTime();
         for (long k : dirty) {
            int rx = (int)(k >> 32), ry = (int)k;
            if (rx < rx0 || rx > rx1 || ry < ry0 || ry > ry1) {
               continue;
            }
            boolean any = false;
            for (int cy = 0; cy < RC; cy++) {
               for (int cx = 0; cx < RC; cx++) {
                  int[] d = GodRays.slotDataFor(rx * 8 + cx, ry * 8 + cy);
                  CHUNKS[cy * RC + cx] = d;
                  any |= d != null;
               }
            }
            long m0 = System.nanoTime();
            int n = any ? mesh(CHUNKS, rx * 64, ry * 64, OUT) : 0;
            long dt = System.nanoTime() - m0;
            java.util.Arrays.fill(CHUNKS, null);
            meshes++;
            meshNs += dt;
            meshMaxNs = Math.max(meshMaxNs, dt);
            quadsMade += n;
            naiveMade += any ? OUT.naive : 0;
            crownsMade += any ? OUT.crowns : 0;
            if (n == 0) {
               regions.remove(k);
            } else {
               regions.put(k, java.util.Arrays.copyOf(OUT.q, n * 4));
            }
            changed = true;
         }
         dirty.clear();
         framesWithMesh++;
         storeNs += System.nanoTime() - t0;
      }
      if (changed) {
         serial++;
      }
   }

   static long storeNs;

   /** The window's quads as one array (a new array whenever they changed: the render thread keeps the one it was handed). */
   static int[] snapshot() {
      if (snapSerial != serial) {
         snapSerial = serial;
         int n = 0;
         for (int[] q : regions.values()) {
            n += q.length;
         }
         int[] s = new int[n];
         int at = 0;
         for (int[] q : regions.values()) {
            System.arraycopy(q, 0, s, at, q.length);
            at += q.length;
         }
         snap = s;
         snapN = n / 4;
         snapZ0 = GodRays.occZ0();
         liveQuads = snapN;
      }
      return snap;
   }

   static long serial() {
      return serial;
   }

   static int snapshotZ0() {
      return snapZ0;
   }

   /** The stats since the last call. */
   static String stats() {
      String s = String.format(java.util.Locale.ROOT, "occ mesh%s: %d regions meshed, %.1f us each (max %.1f), %.1f us/frame meshing in %d frames, quads %d vs %d naive faces (%.1fx, %d crown boxes), live %d quads",
         naiveMode ? " (naive)" : "", meshes, meshes > 0 ? meshNs / 1000.0 / meshes : 0.0, meshMaxNs / 1000.0, framesWithMesh > 0 ? storeNs / 1000.0 / framesWithMesh : 0.0, framesWithMesh,
         quadsMade, naiveMade, quadsMade > 0 ? naiveMade / (double)quadsMade : 0.0, crownsMade, liveQuads);
      meshes = meshNs = meshMaxNs = quadsMade = naiveMade = framesWithMesh = crownsMade = storeNs = 0L;
      return s;
   }
}
