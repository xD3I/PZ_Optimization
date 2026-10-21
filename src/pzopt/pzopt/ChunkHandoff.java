package pzopt;

/**
 * chunkHandoffSlack (2026-09-24, the Rosewood drive): a streamed chunk's hand-off to the world (IsoChunk.doLoadGridsquare:
 * loot, erosion, neighbour recalc, pathfinding) is 1-5 ms on the game thread, a tenth of the game thread in the drive's
 * late frames. The queue is short while driving (~60 chunks a second, one every few frames), so after a frame whose game
 * step used more than chunkHandoffSlackPct of the cap interval the hand-off waits, at most chunkHandoffMaxWait frames and
 * never while chunkHandoffSlackQueue or more chunks are queued, and lands in a frame with room for it.
 * Instrumented runs log the hand-off times (census below).
 */
public final class ChunkHandoff {
   private static int waited;
   private static long n, sumNs, maxNs, over2, over4, deferredFrames;
   private static long lastLogNs;

   private ChunkHandoff() {
   }

   /** Game thread, IsoChunkMap.updateInternal with a non-empty queue: true = hand nothing off this frame. */
   public static boolean defer(int queued) {
      if (!Config.CHUNK_HANDOFF_SLACK || !Overrides.enabled()) {
         return false;
      }
      long interval = Pacing.capIntervalNs();
      if (interval <= 0L || queued >= Config.CHUNK_HANDOFF_SLACK_QUEUE || waited >= Config.CHUNK_HANDOFF_MAX_WAIT
            || FrameCap.lastStepNs <= interval * Config.CHUNK_HANDOFF_SLACK_PCT / 100) {
         waited = 0;
         return false;
      }
      waited++;
      deferredFrames++;
      return true;
   }

   public static boolean inSlack; // IsoChunkMap's slack producer runs this hand-off (f8-handoff census)
   public static String lastShape = ""; // devHandoffLog: the chunk's levels / squares / objects / new, set before its hand-off
   private static long waitN, waitSumNs, waitMaxNs, wait250, wait500, wait1000, wait2000;

   /** IsoChunkMap.pzoptHandOffChunk: how long the chunk waited in the hand-off queue (f8-handoff census, instrumented runs). */
   public static void waited(long queuedNs) {
      if (!Config.INSTRUMENT || queuedNs == 0L) {
         return;
      }
      long w = System.nanoTime() - queuedNs;
      waitN++;
      waitSumNs += w;
      waitMaxNs = Math.max(waitMaxNs, w);
      if (w > 250_000_000L) wait250++;
      if (w > 500_000_000L) wait500++;
      if (w > 1_000_000_000L) wait1000++;
      if (w > 2_000_000_000L) wait2000++;
   }
   private static long slackN, slackSumNs, frameN, frameSumNs, curFrameNs, framesWith, frames1, frames2, frames4, maxFrameNs;
   private static int curFrame = -1, curFrameChunks, maxFrameChunks;

   private static void frameTally() {
      if (curFrameNs > 0L) {
         framesWith++;
         if (curFrameNs > 1_000_000L) frames1++;
         if (curFrameNs > 2_000_000L) frames2++;
         if (curFrameNs > 4_000_000L) frames4++;
         maxFrameNs = Math.max(maxFrameNs, curFrameNs);
         maxFrameChunks = Math.max(maxFrameChunks, curFrameChunks);
      }
      curFrameNs = 0L;
      curFrameChunks = 0;
   }

   public static void done(long ns) {
      if (!Config.INSTRUMENT) {
         return;
      }
      if (Config.DEV_HANDOFF_LOG) {
         StringBuilder b = new StringBuilder("handoff chunk: ").append(inSlack ? "slack" : "frame").append(" q ").append(zombie.iso.IsoChunk.loadGridSquare.size())
            .append(" us ").append(ns / 1000);
         for (int i = 0; i < HandoffParts.CUR.length; i++) {
            b.append(' ').append(HandoffParts.NAMES[i]).append(' ').append(HandoffParts.CUR[i] / 1000);
         }
         b.append(' ').append(lastShape);
         Log.info(b.toString());
      }
      if (inSlack) {
         slackN++;
         slackSumNs += ns;
      } else {
         frameN++;
         frameSumNs += ns;
         int f = SlackWork.frame();
         if (f != curFrame) {
            frameTally();
            curFrame = f;
         }
         curFrameNs += ns;
         curFrameChunks++;
      }
      n++;
      sumNs += ns;
      maxNs = Math.max(maxNs, ns);
      if (ns > 2_000_000L) over2++;
      if (ns > 4_000_000L) over4++;
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info("chunk hand-off: " + n + " chunks, mean " + (n == 0 ? 0 : sumNs / n / 1000) + " us, max " + maxNs / 1000 + " us, > 2 ms " + over2 + ", > 4 ms " + over4
               + ", frames deferred " + deferredFrames + "; in frame " + frameN + " chunks " + frameSumNs / 1000 + " us (frames with " + framesWith
               + ", over 1/2/4 ms " + frames1 + "/" + frames2 + "/" + frames4 + ", max " + maxFrameNs / 1000 + " us / " + maxFrameChunks + " chunks), in slack "
               + slackN + " chunks " + slackSumNs / 1000 + " us; queue wait mean " + (waitN == 0 ? 0 : waitSumNs / waitN / 1_000_000L) + " ms max "
               + waitMaxNs / 1_000_000L + " ms, over 0.25/0.5/1/2 s " + wait250 + "/" + wait500 + "/" + wait1000 + "/" + wait2000 + "; " + HandoffParts.drain() + "; " + HandoffRecalc.census());
         }
         lastLogNs = now;
         n = sumNs = maxNs = over2 = over4 = deferredFrames = 0L;
      }
   }
}
