package pzopt;

/**
 * chunkHandoffPaceMs (chunk-arrival pass, 2026-10-13, Louisville locked 120). Walking through downtown Louisville the
 * streamer delivers a row of ~70 chunks every ~1.3 s; with chunkHandoffDivisor they join the world within ~10 frames and
 * everything a new chunk brings follows in the next ~600 ms (the hand-off itself, its lighting, the zombies the population
 * turns real, its first bakes and cutaway data): the game thread's CPU per frame goes 8.8 / 7.3 / 6.7 / 6.0 ms in the four
 * 100 ms after a row and 5.2-5.4 ms in the rest of the cycle, and 45 / 28 / 23 / 19 % of those frames miss 9 ms against 7 %.
 *
 * <p>With the key on, the queued chunks join at a steady rate instead: the queue's largest size since it was last empty
 * plus 8 (so a lone chunk never waits the whole pace), spread over {@code chunkHandoffPaceMs}. A credit of chunks accrues
 * every frame from the frame's real time and a frame hands off its whole credit. A backlog of
 * {@code chunkHandoffPaceBacklog} or more chunks (a world load, a teleport) drops the pace for as long as it lasts, and so
 * does a queue that has not been empty for 1.5 paces (a steady stream, e.g. a car: the added wait stays under 1.5 paces). The same chunks join in the same order; only the frame each one joins in moves, which the stock
 * game already leaves to the streamer thread's timing (chunkHandoffDivisor is the shipped precedent). Applies to the
 * in-frame hand-off and to slackWork's slack hand-off alike. Off by default.
 */
public final class ChunkPace {
   private ChunkPace() {
   }

   private static double credit;
   private static int peak; // the queue's largest size since it was last empty
   private static long lastNs;
   private static int frameNo = Integer.MIN_VALUE;
   private static long emptyNs; // the last frame the queue was empty
   private static boolean steady; // the queue has not emptied for 1.5 paces: a steady stream (a car), not a row burst
   public static long paced, frames, backlogFrames, steadyFrames;

   public static boolean enabled() {
      return Config.CHUNK_HANDOFF_PACE_MS > 0 && Overrides.enabled() && GtAb.on(GtAb.CHUNK_PACE); // GtAb bit 51: devGtAlternateKeys=chunkHandoffPace
   }

   /** Game thread, once a frame (IsoChunkMap.updateInternal) with the number of chunks waiting: accrue the frame's credit. */
   public static void frame(int queued) {
      if (!enabled()) {
         return;
      }
      int f = zombie.iso.IsoWorld.instance.getFrameNo();
      if (f == frameNo) {
         return;
      }
      frameNo = f;
      long now = System.nanoTime();
      double dtMs = lastNs == 0L ? 0.0 : Math.min(100.0, (now - lastNs) / 1e6);
      lastNs = now;
      if (queued <= 0) {
         peak = 0;
         credit = 0.0;
         emptyNs = now;
         steady = false;
         return;
      }
      frames++;
      steady = emptyNs != 0L && now - emptyNs > Config.CHUNK_HANDOFF_PACE_MS * 1_500_000L;
      if (steady) {
         steadyFrames++;
      }
      peak = Math.max(peak, queued);
      credit = Math.min(credit + (peak + 8) * dtMs / Config.CHUNK_HANDOFF_PACE_MS, Math.max(1.0, queued)); // +8: a lone chunk waits a ninth of the pace, not all of it
   }

   /** Is the pace suspended this frame (a backlog: load, teleport, a fast car). */
   public static boolean backlog(int queued) {
      return queued >= Config.CHUNK_HANDOFF_PACE_BACKLOG || steady;
   }

   /** The in-frame hand-off: how many of {@code count} chunks may join this frame. */
   public static int limit(int count, int queued) {
      if (!enabled() || count <= 0) {
         return count;
      }
      if (backlog(queued)) {
         backlogFrames++;
         return count;
      }
      int n = Math.min(count, (int)credit);
      credit -= n;
      paced += count - n;
      return n;
   }

   /** The slack hand-off: may one more chunk join now. */
   public static boolean mayTake(int queued) {
      return !enabled() || backlog(queued) || credit >= 1.0;
   }

   /** The slack hand-off ran one chunk. */
   public static void took() {
      if (enabled() && credit >= 1.0) {
         credit -= 1.0;
      }
   }
}
