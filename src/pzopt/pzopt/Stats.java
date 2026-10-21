package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoChunk;

/**
 * Per-chunk load/recalc timings and per-frame durations, written to
 * pzopt-chunks.out and pzopt-frames.out in the Zomboid user directory when
 * Config.INSTRUMENT is on. Off, every hook is a single static boolean test.
 *
 * pzopt-chunks.out columns (tab-separated, times in microseconds):
 *   tEnqueue wx wy minLevel maxLevel jobType thread queueWait loadTime recalcWait recalcTime publishWait
 *     tEnqueue    = enqueue time since Stats init (game start), microseconds
 *     queueWait   = enqueue (addJob) -> disk load start, on the streamer thread
 *     loadTime    = LoadChunk + VehiclesDB2.loadChunk
 *     recalcWait  = load end -> recalc start (0 when inline; pool queueing otherwise)
 *     recalcTime  = loadInWorldStreamerThread
 *     publishWait = recalc end -> chunk handed to the game thread queue
 * pzopt-frames.out: one frame duration in microseconds per line, game thread.
 *   Each flushed block of frames is preceded by "# anchor <epochUs> <startUs> <frameId>":
 *   the wall-clock time (microseconds since the Unix epoch) at which the
 *   block's first frame started, the same instant as microseconds since
 *   Stats init (the clock the "# route-start/route-end <us> <frameId>" markers
 *   use), and the game's frame counter for that frame (IsoCamera.frameState.frameCount,
 *   which the game's own GameProfiler recording is keyed by).
 *   Durations within a block are exact against the block start (no rounding
 *   drift), so frame i starts at epochUs + sum(durations before i). This is
 *   what lets a JFR sample be assigned to a frame (harness/attribute.py).
 */
public final class Stats {
   public static final boolean ENABLED = Config.INSTRUMENT;
   private static final boolean HOOK_FRAMES = ENABLED || Harness.REQUESTED;
   private static final ConcurrentHashMap<IsoChunk, Long> enqueued = new ConcurrentHashMap<>();
   private static final Object lock = new Object();
   private static final ArrayList<String> chunkLines = new ArrayList<>();
   private static int[] frameUs = new int[4096];
   private static int frameCount = 0;
   private static long blockStartNs = 0L; // nanoTime at which the first pending frame started
   private static long blockCumUs = 0L;   // microseconds from blockStartNs already accounted to pending frames
   private static int blockFrameId = 0;   // game frame counter of the first pending frame
   private static long lastFrameNs = 0L;
   private static int lastFrameId = Integer.MIN_VALUE;
   private static long lastFlushNs = System.nanoTime();
   private static boolean headerWritten = false;
   private static final long T0 = System.nanoTime();
   private static int chunkCount = 0;

   private Stats() {
   }

   /** Timings for one chunk, filled in along the streaming path. */
   public static final class Timing {
      public long enqueuedNs;
      public long loadStartNs;
      public long loadEndNs;
      public long recalcStartNs;
      public long recalcEndNs;
      public long publishNs;
      public String thread = "";
   }

   public static void onEnqueued(IsoChunk chunk) {
      if (ENABLED) {
         enqueued.put(chunk, System.nanoTime());
      }
   }

   /** Chunks the streamer has started loading since boot, counted whether or not the logs are on (benchmark). */
   private static final java.util.concurrent.atomic.AtomicInteger loaded = new java.util.concurrent.atomic.AtomicInteger();

   public static int loadedChunks() {
      return loaded.get();
   }

   public static Timing begin(IsoChunk chunk) {
      loaded.incrementAndGet();
      Timing t = new Timing();
      Long e = enqueued.remove(chunk);
      t.loadStartNs = System.nanoTime();
      t.enqueuedNs = e != null ? e : t.loadStartNs;
      return t;
   }

   public static void done(IsoChunk chunk, Timing t) {
      if (!ENABLED) {
         return;
      }
      String line = us(t.enqueuedNs - T0) + "\t" + chunk.wx + "\t" + chunk.wy + "\t" + chunk.getMinLevel() + "\t" + chunk.getMaxLevel() + "\t"
            + chunk.jobType + "\t" + t.thread + "\t"
            + us(t.loadStartNs - t.enqueuedNs) + "\t" + us(t.loadEndNs - t.loadStartNs) + "\t"
            + us(t.recalcStartNs - t.loadEndNs) + "\t" + us(t.recalcEndNs - t.recalcStartNs) + "\t"
            + us(t.publishNs - t.recalcEndNs);
      synchronized (lock) {
         chunkLines.add(line);
         chunkCount++;
         maybeFlush(false);
      }
   }

   public static int chunkCount() {
      synchronized (lock) {
         return chunkCount;
      }
   }

   /** Writes a marker line into both output files so analysis can align to route start/end. */
   public static void mark(String label) {
      if (!ENABLED) {
         return;
      }
      synchronized (lock) {
         maybeFlush(true);
         chunkLines.add("# " + label + " " + us(System.nanoTime() - T0));
         markers.add("# " + label + " " + us(System.nanoTime() - T0) + " " + lastFrameId);
         maybeFlush(true);
      }
   }

   private static final ArrayList<String> markers = new ArrayList<>();

   /** Called from IsoChunk.update() (every loaded chunk, every frame); only the first call per frame does anything. */
   public static void frameTick(int frameId) {
      if (!HOOK_FRAMES || frameId == lastFrameId) {
         return;
      }
      long now = System.nanoTime();
      if (Harness.REQUESTED) {
         Harness.onFrame(now);
      }
      if (!ENABLED) {
         lastFrameId = frameId;
         return;
      }
      synchronized (lock) {
         if (frameId == lastFrameId) {
            return;
         }
         if (lastFrameId != Integer.MIN_VALUE && lastFrameNs != 0L) {
            if (frameCount == frameUs.length) {
               frameUs = java.util.Arrays.copyOf(frameUs, frameUs.length * 2);
            }
            if (frameCount == 0) {
               blockStartNs = lastFrameNs;
               blockCumUs = 0L;
               blockFrameId = lastFrameId;
            }
            long cumUs = (now - blockStartNs) / 1000L;
            frameUs[frameCount++] = (int)Math.min(Integer.MAX_VALUE, cumUs - blockCumUs);
            blockCumUs = cumUs;
         }
         lastFrameId = frameId;
         lastFrameNs = now;
         maybeFlush(false);
      }
   }

   /** Write everything pending; called from the streamer thread on stop and on quit. */
   public static void flush() {
      if (!ENABLED) {
         return;
      }
      synchronized (lock) {
         maybeFlush(true);
      }
   }

   private static long us(long ns) {
      return ns / 1000L;
   }

   private static void maybeFlush(boolean force) {
      long now = System.nanoTime();
      if (!force && now - lastFlushNs < 5_000_000_000L && chunkLines.size() < 512 && frameCount < 4096) {
         return;
      }
      lastFlushNs = now;
      // wall clock read next to the monotonic one (before any file I/O) so the anchor pairs the two clocks tightly
      java.time.Instant inst = frameCount > 0 ? java.time.Instant.now() : null;
      File dir = new File(ZomboidFileSystem.instance.getCacheDir());
      try {
         if (!chunkLines.isEmpty()) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "pzopt-chunks.out"), true))) {
               if (!headerWritten) {
                  w.write("tEnqueueUs\twx\twy\tminLevel\tmaxLevel\tjobType\tthread\tqueueWaitUs\tloadUs\trecalcWaitUs\trecalcUs\tpublishWaitUs\n");
                  headerWritten = true;
               }
               for (String l : chunkLines) {
                  w.write(l);
                  w.write('\n');
               }
            }
            chunkLines.clear();
         }
         if (frameCount > 0 || !markers.isEmpty()) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "pzopt-frames.out"), true))) {
               for (String m : markers) {
                  w.write(m);
                  w.write('\n');
               }
               markers.clear();
               if (inst != null) {
                  // the block start is projected back from the wall-clock read on the monotonic clock
                  long epochUs = inst.getEpochSecond() * 1_000_000L + inst.getNano() / 1000L - (now - blockStartNs) / 1000L;
                  w.write("# anchor " + epochUs + " " + us(blockStartNs - T0) + " " + blockFrameId + "\n");
               }
               for (int i = 0; i < frameCount; i++) {
                  w.write(Integer.toString(frameUs[i]));
                  w.write('\n');
               }
            }
            frameCount = 0;
         }
      } catch (IOException e) {
         Log.warn("could not write stats: " + e);
      }
   }
}
