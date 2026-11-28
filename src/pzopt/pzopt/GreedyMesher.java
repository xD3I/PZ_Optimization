package pzopt;

/**
 * Binary greedy meshing (Erik Johansson's binary-greedy-meshing v2, MIT, github.com/cgerikj/binary-greedy-meshing, with
 * Ethan Gore's and Finding Fortune's v2 changes; ported 2026-11-28). The faces of an occupancy are culled 64 at a time with
 * bit operations on 64-bit columns, then merged into large quads: a face goes forward while the next row has the same face
 * (and type), then right while the neighbours started on the same row (and are of the same type).
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #mesh}: the original 3D mesher, line for line: a 64^3 ZXY voxel block with a one-voxel apron, its 64 x 64
 *       opaque columns, six faces of a 62^3 mesh, 8 bytes a quad (6 bit x, y, z, w, h; type in the high word). Kept as the
 *       reference: GreedyMesherTest checks it against a brute-force face count, GreedyMesherBench against the C++ timings.</li>
 *   <li>{@link #mergePlane}: step 3 of the same algorithm on one plane of 64-bit rows, without the type lookup (each type
 *       has its own mask, so equal bits are equal types): what {@link OccMesh} needs for the god rays' occupancy, whose
 *       blockers are planes (the N / W edges of squares, floors) rather than cubes.</li>
 * </ul>
 */
public final class GreedyMesher {
   private GreedyMesher() {
   }

   public static final int CS = 62, CS_P = CS + 2, CS_2 = CS * CS, CS_P2 = CS_P * CS_P, CS_P3 = CS_P * CS_P * CS_P;
   private static final long P_MASK = ~(1L << 63 | 1L);

   /** The 3D mesher's scratch and output (one per thread). */
   public static final class Mesh {
      public final long[] faceMasks = new long[CS_2 * 6];
      public final byte[] forwardMerged = new byte[CS_2];
      public final byte[] rightMerged = new byte[CS];
      public long[] quads = new long[4096];
      public int count;
      public final int[] faceBegin = new int[6], faceLength = new int[6];

      void add(long q) {
         if (count == quads.length) {
            quads = java.util.Arrays.copyOf(quads, count * 2);
         }
         quads[count++] = q;
      }
   }

   static int axisIndex(int axis, int a, int b, int c) {
      if (axis == 0) {
         return b + a * CS_P + c * CS_P2;
      } else if (axis == 1) {
         return b + c * CS_P + a * CS_P2;
      }
      return c + a * CS_P + b * CS_P2;
   }

   /** The packed quad: 6 bits each of x, y, z, width, height; the voxel type in the high word. */
   public static long quad(long x, long y, long z, long w, long h, long type) {
      return type << 32 | h << 24 | w << 18 | z << 12 | y << 6 | x;
   }

   /** Bits [0, n) set (n in 0..64; Java's shifts take the count mod 64, so 1L << 64 is 1, not 0). */
   static long low(int n) {
      return n >= 64 ? -1L : (1L << n) - 1L;
   }

   /**
    * The original mesher. voxels: 64^3, ZXY (index = z + x * 64 + y * 64^2, as the C++ getAxisIndex reads it), 0 = air,
    * with the neighbours' voxels in the apron; opaqueMask: 64 x 64 columns (index a * 64 + b), bit c set where the voxel is
    * opaque. Returns the quad count (m.quads), faces 0-5 in m.faceBegin / m.faceLength.
    */
   public static int mesh(byte[] voxels, long[] opaqueMask, Mesh m) {
      m.count = 0;
      long[] faceMasks = m.faceMasks;
      byte[] forwardMerged = m.forwardMerged, rightMerged = m.rightMerged;
      // hidden face culling: 64 faces a column at a time
      for (int a = 1; a < CS_P - 1; a++) {
         int aCS_P = a * CS_P;
         for (int b = 1; b < CS_P - 1; b++) {
            long columnBits = opaqueMask[a * CS_P + b] & P_MASK;
            int baIndex = b - 1 + (a - 1) * CS;
            int abIndex = a - 1 + (b - 1) * CS;
            faceMasks[baIndex] = (columnBits & ~opaqueMask[aCS_P + CS_P + b]) >>> 1;
            faceMasks[baIndex + CS_2] = (columnBits & ~opaqueMask[aCS_P - CS_P + b]) >>> 1;
            faceMasks[abIndex + 2 * CS_2] = (columnBits & ~opaqueMask[aCS_P + b + 1]) >>> 1;
            faceMasks[abIndex + 3 * CS_2] = (columnBits & ~opaqueMask[aCS_P + b - 1]) >>> 1;
            faceMasks[baIndex + 4 * CS_2] = columnBits & ~(opaqueMask[aCS_P + b] >>> 1);
            faceMasks[baIndex + 5 * CS_2] = columnBits & ~(opaqueMask[aCS_P + b] << 1);
         }
      }
      // faces 0-3: merged forward along the rows, then right along the bits
      for (int face = 0; face < 4; face++) {
         int axis = face / 2;
         int begin = m.count;
         for (int layer = 0; layer < CS; layer++) {
            int bitsLocation = layer * CS + face * CS_2;
            for (int forward = 0; forward < CS; forward++) {
               long bitsHere = faceMasks[forward + bitsLocation];
               if (bitsHere == 0L) {
                  continue;
               }
               long bitsNext = forward + 1 < CS ? faceMasks[forward + 1 + bitsLocation] : 0L;
               int rightMergedN = 1;
               while (bitsHere != 0L) {
                  int bitPos = Long.numberOfTrailingZeros(bitsHere);
                  byte type = voxels[axisIndex(axis, forward + 1, bitPos + 1, layer + 1)];
                  if ((bitsNext >>> bitPos & 1L) != 0L && type == voxels[axisIndex(axis, forward + 2, bitPos + 1, layer + 1)]) {
                     forwardMerged[bitPos]++;
                     bitsHere &= ~(1L << bitPos);
                     continue;
                  }
                  for (int right = bitPos + 1; right < CS; right++) {
                     if ((bitsHere >>> right & 1L) == 0L || forwardMerged[bitPos] != forwardMerged[right]
                        || type != voxels[axisIndex(axis, forward + 1, right + 1, layer + 1)]) {
                        break;
                     }
                     forwardMerged[right] = 0;
                     rightMergedN++;
                  }
                  bitsHere &= ~low(bitPos + rightMergedN);
                  int fm = forwardMerged[bitPos] & 0xFF;
                  int meshFront = forward - fm, meshLeft = bitPos, meshUp = layer + (~face & 1);
                  int meshWidth = rightMergedN, meshLength = fm + 1;
                  forwardMerged[bitPos] = 0;
                  rightMergedN = 1;
                  long t = type & 0xFF;
                  if (face < 2) {
                     m.add(quad(meshFront + (face == 1 ? meshLength : 0), meshUp, meshLeft, meshLength, meshWidth, t));
                  } else {
                     m.add(quad(meshUp, meshFront + (face == 2 ? meshLength : 0), meshLeft, meshLength, meshWidth, t));
                  }
               }
            }
         }
         m.faceBegin[face] = begin;
         m.faceLength[face] = m.count - begin;
      }
      // faces 4-5: the bits run along the columns; merged forward across rows, then right across columns
      for (int face = 4; face < 6; face++) {
         int axis = face / 2;
         int begin = m.count;
         for (int forward = 0; forward < CS; forward++) {
            int bitsLocation = forward * CS + face * CS_2;
            int bitsForwardLocation = (forward + 1) * CS + face * CS_2;
            for (int right = 0; right < CS; right++) {
               long bitsHere = faceMasks[right + bitsLocation];
               if (bitsHere == 0L) {
                  continue;
               }
               long bitsForward = forward < CS - 1 ? faceMasks[right + bitsForwardLocation] : 0L;
               long bitsRight = right < CS - 1 ? faceMasks[right + 1 + bitsLocation] : 0L;
               int rightCS = right * CS;
               while (bitsHere != 0L) {
                  int bitPos = Long.numberOfTrailingZeros(bitsHere);
                  bitsHere &= ~(1L << bitPos);
                  byte type = voxels[axisIndex(axis, right + 1, forward + 1, bitPos)];
                  int fi = rightCS + bitPos - 1, ri = bitPos - 1;
                  if (rightMerged[ri] == 0 && (bitsForward >>> bitPos & 1L) != 0L && type == voxels[axisIndex(axis, right + 1, forward + 2, bitPos)]) {
                     forwardMerged[fi]++;
                     continue;
                  }
                  if ((bitsRight >>> bitPos & 1L) != 0L && forwardMerged[fi] == forwardMerged[rightCS + CS + bitPos - 1]
                     && type == voxels[axisIndex(axis, right + 2, forward + 1, bitPos)]) {
                     forwardMerged[fi] = 0;
                     rightMerged[ri]++;
                     continue;
                  }
                  int rm = rightMerged[ri] & 0xFF, fm = forwardMerged[fi] & 0xFF;
                  int meshLeft = right - rm, meshFront = forward - fm, meshUp = bitPos - 1 + (~face & 1);
                  int meshWidth = 1 + rm, meshLength = 1 + fm;
                  forwardMerged[fi] = 0;
                  rightMerged[ri] = 0;
                  m.add(quad(meshLeft + (face == 4 ? meshWidth : 0), meshFront, meshUp, meshWidth, meshLength, type & 0xFF));
               }
            }
         }
         m.faceBegin[face] = begin;
         m.faceLength[face] = m.count - begin;
      }
      return m.count;
   }

   /** Receives one merged rectangle of a plane: rows [row, row + rows), bits [bit, bit + bits). */
   public interface RectSink {
      void rect(int row, int bit, int rows, int bits);
   }

   /**
    * Step 3 of the mesher on one plane: rows[off .. off + n) are 64-bit masks over the plane's "right" axis, row r + 1 the
    * next row "forward". Each set bit is covered by exactly one rectangle (GreedyMesherTest); with forward false only runs
    * within a row are merged (rectangles one row tall). fm: 64 bytes of scratch, all zero on entry and on return. The rows
    * are left unchanged. Returns the rectangle count.
    */
   public static int mergePlane(long[] rows, int off, int n, boolean forward, byte[] fm, RectSink out) {
      int quads = 0;
      for (int r = 0; r < n; r++) {
         long bitsHere = rows[off + r];
         if (bitsHere == 0L) {
            continue;
         }
         long bitsNext = forward && r + 1 < n ? rows[off + r + 1] : 0L;
         while (bitsHere != 0L) {
            int bitPos = Long.numberOfTrailingZeros(bitsHere);
            if ((bitsNext >>> bitPos & 1L) != 0L) {
               fm[bitPos]++; // the face continues in the next row: carried
               bitsHere &= ~(1L << bitPos);
               continue;
            }
            int width = 1;
            byte start = fm[bitPos];
            // right while the neighbour is set and started on the same row; the run's bits are always contiguous here, so
            // the C++ loop's bit-at-a-time walk becomes a count of the trailing ones above bitPos, then the row check
            int runEnd = bitPos + Long.numberOfTrailingZeros(~(bitsHere >>> bitPos));
            for (int right = bitPos + 1; right < runEnd && fm[right] == start; right++) {
               fm[right] = 0;
               width++;
            }
            bitsHere &= ~low(bitPos + width);
            int len = (start & 0xFF) + 1;
            fm[bitPos] = 0;
            out.rect(r - len + 1, bitPos, len, width);
            quads++;
         }
      }
      return quads;
   }
}
