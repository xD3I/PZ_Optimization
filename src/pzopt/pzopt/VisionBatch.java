package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.LosUtil;

/**
 * lightingVisionParallel (2026-09-22, the Dell hitch pass): LightingJNI.updateChunk asks every square of a dirty chunk
 * level for ten neighbour vision tests (eight directions, up, down; a diagonal test is up to six more lookups) before
 * handing the square to the native lighting. On the 4-core Dell those tests were half of the game thread's 12 %
 * "lighting jni" share while driving. They only read squares and their objects, so this computes them for every dirty
 * level of the frame on the FrameBatch workers first; updateChunk then reads the bits. One int per square: bits 0-7 =
 * not blocked towards LightingJNI.DIRECTIONS[i], bit 8 = up, bit 9 = down, bit 31 = computed.
 */
public final class VisionBatch {
   public static final boolean ENABLED = Config.LIGHTING_VISION_PARALLEL;
   private static final IdentityHashMap<IsoChunkLevel, int[]> bits = new IdentityHashMap<>();
   private static final ArrayList<IsoChunkLevel> levels = new ArrayList<>();
   private static final ArrayList<int[]> pool = new ArrayList<>();
   public static long batches, levelsDone, checks, mismatches;

   private VisionBatch() {
   }

   /** This frame batches (the key on and, under devGtAlternate, its phase on: GtAb bit 50). */
   public static boolean active() {
      return ENABLED && GtAb.on(GtAb.VISION_BATCH);
   }

   /** Game thread, before the chunk loop: precompute the dirty levels of the chunks lighting will update. */
   public static void prepare(IsoChunk[] chunks, int count, int playerIndex, IsoDirections[] dirs) {
      clear();
      for (int c = 0; c < count; c++) {
         IsoChunk ch = chunks[c];
         for (int z = ch.getMinLevel(); z <= ch.getMaxLevel(); z++) {
            IsoChunkLevel lv = ch.getLevelData(z);
            if (lv != null && lv.lightCheck[playerIndex]) {
               int[] a = pool.isEmpty() ? new int[64] : pool.remove(pool.size() - 1);
               bits.put(lv, a);
               levels.add(lv);
            }
         }
      }
      if (levels.size() < 2) {
         clear();
         return;
      }
      batches++;
      levelsDone += levels.size();
      if ((batches & 255) == 0) {
         Log.info("vision batch: " + batches + " batches, " + levelsDone + " levels, dev checks " + checks + ", mismatches " + mismatches);
      }
      Throwable t = FrameBatch.run(levels.size(), i -> {
         IsoChunkLevel lv = levels.get(i);
         int[] a = bits.get(lv);
         for (int s = 0; s < 64; s++) {
            IsoGridSquare sq = lv.squares[s];
            a[s] = sq == null ? 0 : compute(sq, dirs);
         }
      });
      if (t != null) {
         Log.warn("vision batch failed, computing on the game thread: " + t);
         clear();
      }
   }

   public static int compute(IsoGridSquare sq, IsoDirections[] dirs) {
      int v = 1 << 31;
      for (int i = 0; i < dirs.length; i++) {
         IsoDirections dir = dirs[i];
         if (sq.testVisionAdjacent(dir.dx(), dir.dy(), 0, true, false) != LosUtil.TestResults.Blocked) {
            v |= 1 << i;
         }
      }
      if (sq.testVisionAdjacent(0, 0, 1, true, false) != LosUtil.TestResults.Blocked) {
         v |= 1 << 8;
      }
      if (sq.testVisionAdjacent(0, 0, -1, true, false) != LosUtil.TestResults.Blocked) {
         v |= 1 << 9;
      }
      return v;
   }

   /** The precomputed bits of a square, or 0 when its level was not batched this frame. */
   public static int get(IsoChunkLevel lv, int squareIndex) {
      int[] a = bits.get(lv);
      return a == null ? 0 : a[squareIndex];
   }

   public static void clear() {
      for (IsoChunkLevel lv : levels) {
         pool.add(bits.get(lv));
      }
      bits.clear();
      levels.clear();
   }
}
