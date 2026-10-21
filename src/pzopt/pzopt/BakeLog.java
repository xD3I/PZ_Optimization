package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import zombie.ZomboidFileSystem;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Per-frame chunk-bake census for instrumented runs (Zomboid/pzopt-bakes.out, harness/frame-causes.py): every chunk-level
 * bake the game thread records is classed by the strongest reason among its dirty flags, and one row per frame gives the
 * counts, so a frame that bakes 40 levels says why. Rows: "ns create object cutaway trees redraw lighting other", ns =
 * System.nanoTime() when the next frame's cell render starts (inside the game step that recorded the bakes); after it one
 * "b ns wx wy level flags playerX playerY" row per bake of that frame (flags = the level's dirty bits before the bake).
 */
public final class BakeLog {
   public static final boolean ON = Config.INSTRUMENT && Overrides.enabled();
   static final int CREATE = 0, OBJECT = 1, CUTAWAY = 2, TREES = 3, REDRAW = 4, LIGHTING = 5, OTHER = 6;
   private static final int[] counts = new int[7];
   private static BufferedWriter log;
   private static boolean failed;
   private static long lastFlushNs;

   private BakeLog() {
   }

   private static final StringBuilder rows = new StringBuilder(1 << 12);

   /** Game thread, before the level's bake clears its dirty flags. */
   public static void bake(zombie.iso.IsoChunk c, FBORenderLevels levels, int level, float zoom) {
      long flags = 0L;
      for (int bit = 0; bit < 16; bit++) {
         if (levels.isDirty(level, 1L << bit, zoom)) {
            flags |= 1L << bit;
         }
      }
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
      rows.append("b ").append(System.nanoTime()).append(' ').append(c.wx).append(' ').append(c.wy).append(' ').append(level).append(' ').append(flags)
         .append(' ').append(p == null ? 0 : (int)p.getX()).append(' ').append(p == null ? 0 : (int)p.getY()).append('\n');
      int k;
      if (levels.isDirty(level, 512L, zoom)) {
         k = CREATE;
      } else if (levels.isDirty(level, 1L | 2L | 4L | 8L | 16L | 64L | 128L | 256L, zoom)) {
         k = OBJECT;
      } else if (levels.isDirty(level, 2048L | 8192L | 16384L, zoom)) {
         k = CUTAWAY;
      } else if (levels.isDirty(level, 4096L, zoom)) {
         k = TREES;
      } else if (levels.isDirty(level, 1024L, zoom)) {
         k = REDRAW;
      } else if (levels.isDirty(level, 32L, zoom)) {
         k = LIGHTING;
      } else {
         k = OTHER;
      }
      counts[k]++;
      bakeKind = k;
      inBake = true;
   }

   /** Game thread: a level whose texture was made by beginRenderChunkLevel without being dirty first (counted as other, flag bit 20). */
   public static void hidden(zombie.iso.IsoChunk c, int level) {
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
      rows.append("b ").append(System.nanoTime()).append(' ').append(c.wx).append(' ').append(c.wy).append(' ').append(level).append(' ').append(1L << 20)
         .append(' ').append(p == null ? 0 : (int)p.getX()).append(' ').append(p == null ? 0 : (int)p.getY()).append('\n');
      counts[OTHER]++;
      bakeKind = OTHER;
      inBake = true;
   }

   // ---- bake timing (2026-10-12, the Louisville bake bursts): per level baked, the game thread's time from the bake's start to the
   // level's end, split at the phase marks; per frame the chunk loop, the bakes' total / max, the pre-loop passes that feed the bakes
   // and the post-loop flushes; on the render thread the time between a bake's begin and end markers (its GL submission).
   // Rows (pzopt-bakes.out, after the frame's census row; frame-causes.py reads only digit-first rows):
   //   "bt ns wx wy level kind totalNs ph0 ph1 ph2 ph3 ph4"  ph = start..info, info loop, floor/snow/blood, vegetation/corpses/objects, rest
   //   "t ns frameNo loopNs bakeNs bakeMaxNs levels cleanNs pre0..pre3 post0..post2 kind0..kind6 hold" (hold: devBakeHoldFrames block, -1 = off)
   //   "ra startNs ns kind"  render-thread time of one chunk AO job (kind: ChunkAo.Job kinds)
   //   "rb frameNo ns kind wx wy level startNs"  render-thread submission of one baked level (startNs = System.nanoTime at its begin marker)
   public static final int PRE_SCHED = 0, PRE_PREPARE = 1, PRE_OCCLUSION = 2, PRE_LIGHTING = 3;
   public static final int POST_AO = 0, POST_RELIEF = 1, POST_PPL = 2;
   static int bakeKind;
   static boolean inBake;
   private static long levelT0, lastMark;
   private static int markIdx;
   private static final long[] phase = new long[5];
   private static long loopT0, loopNs, bakeNs, bakeMaxNs, cleanNs;
   private static int levels;
   private static final long[] kindNs = new long[7];
   private static final long[] pre = new long[4], preNext = new long[4], post = new long[3];

   /** Game thread: renderOneChunk, before renderOneLevel. */
   public static void levelBegin() {
      inBake = false;
      levelT0 = lastMark = System.nanoTime();
      markIdx = 0;
      java.util.Arrays.fill(phase, 0L);
   }

   /** Game thread, inside a bake: the phase {@code i - 1} ended now. */
   public static void mark(int i) {
      if (!inBake) {
         return;
      }
      long now = System.nanoTime();
      phase[Math.min(i - 1, 4)] += now - lastMark;
      lastMark = now;
      markIdx = i;
   }

   /** Game thread: renderOneChunk, after renderOneLevel. */
   public static void levelEnd(zombie.iso.IsoChunk c, int level) {
      long now = System.nanoTime();
      long dt = now - levelT0;
      if (!inBake) {
         cleanNs += dt;
         return;
      }
      phase[Math.min(markIdx, 4)] += now - lastMark;
      inBake = false;
      bakeNs += dt;
      bakeMaxNs = Math.max(bakeMaxNs, dt);
      levels++;
      kindNs[bakeKind] += dt;
      rows.append("bt ").append(now).append(' ').append(c.wx).append(' ').append(c.wy).append(' ').append(level).append(' ').append(bakeKind).append(' ').append(dt);
      for (long p : phase) {
         rows.append(' ').append(p);
      }
      rows.append('\n');
   }

   public static void loopBegin() {
      loopT0 = System.nanoTime();
   }

   public static void loopEnd() {
      loopNs += System.nanoTime() - loopT0;
   }

   /** A pass of this frame before the chunk loop (counted with the frame whose loop follows). */
   public static void preAdd(int i, long t0) {
      preNext[i] += System.nanoTime() - t0;
   }

   /** A flush after this frame's chunk loop. */
   public static void postAdd(int i, long t0) {
      post[i] += System.nanoTime() - t0;
   }

   private static final java.util.concurrent.ConcurrentLinkedQueue<long[]> rtDone = new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static final java.util.ArrayList<RtMarker> rtPool = new java.util.ArrayList<>();
   private static long rtT0;

   private static final class RtMarker extends zombie.core.textures.TextureDraw.GenericDrawer {
      boolean end;
      int frameNo, kind, wx, wy, level;

      @Override
      public void render() {
         long now = System.nanoTime();
         if (!this.end) {
            rtT0 = now;
         } else if (rtT0 != 0L) {
            rtDone.add(new long[]{this.frameNo, now - rtT0, this.kind, this.wx, this.wy, this.level, rtT0});
            rtT0 = 0L;
         }
      }

      @Override
      public void postRender() {
         synchronized (rtPool) {
            rtPool.add(this);
         }
      }
   }

   /** Render thread: one chunk AO job took the time since {@code t0} (written as "ra startNs ns kind", joined to frames by time). */
   public static void rtAo(long t0, int kind) {
      long now = System.nanoTime();
      rtDone.add(new long[]{-1L, now - t0, kind, 0L, 0L, 0L, t0});
   }

   /** Game thread: a marker in the draw stream at a bake's start ({@code end} false) or end. */
   public static void rtMark(boolean end, zombie.iso.IsoChunk c, int level) {
      RtMarker m;
      synchronized (rtPool) {
         m = rtPool.isEmpty() ? new RtMarker() : rtPool.remove(rtPool.size() - 1);
      }
      m.end = end;
      m.frameNo = zombie.iso.IsoWorld.instance.getFrameNo();
      m.kind = bakeKind;
      m.wx = c.wx;
      m.wy = c.wy;
      m.level = level;
      zombie.core.SpriteRenderer.instance.drawGeneric(m);
   }

   private static int hold = -1, holdNext = -1;

   /** devBakeHoldFrames: the plan of this frame held its bakes (true) or not; the frame's timing row carries it. */
   public static void holdBlock(boolean held) {
      holdNext = held ? 1 : 0;
   }

   private static void timingRow(StringBuilder b, long now) {
      b.append("t ").append(now).append(' ').append(zombie.iso.IsoWorld.instance.getFrameNo() - 1).append(' ').append(loopNs).append(' ').append(bakeNs)
         .append(' ').append(bakeMaxNs).append(' ').append(levels).append(' ').append(cleanNs);
      for (long v : pre) {
         b.append(' ').append(v);
      }
      for (long v : post) {
         b.append(' ').append(v);
      }
      for (long v : kindNs) {
         b.append(' ').append(v);
      }
      b.append(' ').append(hold).append('\n');
      hold = holdNext;
      holdNext = -1;
      System.arraycopy(preNext, 0, pre, 0, pre.length);
      java.util.Arrays.fill(preNext, 0L);
      java.util.Arrays.fill(post, 0L);
      java.util.Arrays.fill(kindNs, 0L);
      loopNs = bakeNs = bakeMaxNs = cleanNs = 0L;
      levels = 0;
      for (long[] r; (r = rtDone.poll()) != null; ) {
         if (r[0] < 0L) {
            b.append("ra ").append(r[6]).append(' ').append(r[1]).append(' ').append(r[2]).append('\n');
            continue;
         }
         b.append("rb ").append(r[0]).append(' ').append(r[1]).append(' ').append(r[2]).append(' ').append(r[3]).append(' ').append(r[4]).append(' ').append(r[5]).append(' ').append(r[6]).append('\n');
      }
   }

   /** Game thread, once per frame at the start of the cell render: writes the previous frame's row. */
   public static void frame() {
      long now = System.nanoTime();
      try {
         if (log == null) {
            if (failed) {
               return;
            }
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, "pzopt-bakes.out"), false), 1 << 16);
            log.write("# ns create object cutaway trees redraw lighting other (bakes recorded by the frame that ended at ns)\n");
         }
         StringBuilder b = new StringBuilder(48).append(now);
         for (int i = 0; i < counts.length; i++) {
            b.append(' ').append(counts[i]);
            counts[i] = 0;
         }
         b.append('\n');
         timingRow(b, now);
         log.write(b.toString());
         if (rows.length() > 0) {
            log.write(rows.toString());
            rows.setLength(0);
         }
         if (now - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = now;
         }
      } catch (IOException | RuntimeException e) {
         failed = true;
         log = null;
      }
   }
}
