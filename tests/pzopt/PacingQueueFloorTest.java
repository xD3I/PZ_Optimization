package pzopt;

/**
 * pzopt.Pacing's frame queue D: the fixed D (frameQueueMinPct) only at caps of frameQueueFixedMinFps or more (round 11), and
 * below it the low-cap rules (frameQueueLowCapCalmMs / DecayPct / MarginPct): D restarts from its floor after the
 * queue turns on or the cap changes, ignores the start bursts, follows a burst within a few frames and comes back down within
 * seconds. Drives Pacing.addQueueSample + Pacing.queueD the way queueHold does, one call pair per frame.
 */
public class PacingQueueFloorTest {
   static long now = 1_000_000_000_000L;

   /** One frame at this cap with this slot -> ready lag (ns); returns D (ns). */
   static long frame(long interval, long lag) {
      now += interval;
      Pacing.addQueueSample(lag, now);
      return Pacing.queueD(now, interval);
   }

   static final long MS = 1_000_000L;

   public static void main(String[] args) {
      Check.check(Config.FRAME_QUEUE_FIXED_MIN_FPS == 100, "frameQueueFixedMinFps defaults to 100");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 120) == Config.FRAME_QUEUE_MIN_PCT, "120 fps cap: frameQueueMinPct (fixed D)");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 240) == Config.FRAME_QUEUE_MIN_PCT, "240 fps cap: frameQueueMinPct");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 100) == Config.FRAME_QUEUE_MIN_PCT, "100 fps cap: the threshold itself is fixed");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 99) == Config.FRAME_QUEUE_LOW_CAP_MIN_PCT, "99 fps cap: the low-cap floor");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 60) == 60, "60 fps cap: the adaptive 60 % floor (10 ms)");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 30) == 60, "30 fps cap: the adaptive 60 % floor (20 ms)");
      Check.check(Config.FRAME_QUEUE_LOW_CAP_CALM_MS == 1000 && Config.FRAME_QUEUE_LOW_CAP_DECAY_PCT == 25
            && Config.FRAME_QUEUE_LOW_CAP_MARGIN_PCT == 20 && Config.FRAME_QUEUE_SLEW_US == 500, "low-cap defaults: calm 1000 ms, decay 25 %/s, margin 20 %, slew 500 us");
      Check.check(!Pacing.lowCap(1_000_000_000L / 120) && Pacing.lowCap(1_000_000_000L / 60), "lowCap: 120 no, 60 yes");

      // 120 fps: the fixed D from the first frame, through bursts and a queue restart, never a calm wait
      long i120 = 1_000_000_000L / 120, fixed = i120 * 260 / 100;
      java.util.Random r = new java.util.Random(1);
      boolean allFixed = true;
      for (int f = 0; f < 1200; f++) {
         if (f == 600) {
            Pacing.queueTurnedOn();
         }
         long d = frame(i120, (f < 200 ? 80 + r.nextInt(150) : 4 + r.nextInt(14)) * MS);
         allFixed &= d == fixed && !Pacing.queueCalmWait();
      }
      Check.check(allFixed, "120 fps cap: D fixed at 21.67 ms every frame (start bursts, calm, queue on again)");

      // 60 fps: the cap change restarts D at its 10 ms floor; the slew brings it down from 21.67 at 0.5 ms a frame
      long i60 = 1_000_000_000L / 60, lo60 = i60 * 60 / 100, slew = 500_000L;
      long prev = fixed, maxStep = 0;
      for (int f = 0; f < 30; f++) {
         long d = frame(i60, 120 * MS);
         maxStep = Math.max(maxStep, Math.abs(d - prev));
         prev = d;
      }
      Check.check(prev == lo60 && maxStep <= slew, "120 -> 60 cap: D slews to the 10 ms floor (" + prev / 1e6 + " ms, step " + maxStep / 1e6 + ")");
      // the world's start bursts (round 11: D went to the 43 ms ceiling and fell 1 ms/s): D stays at the floor, no samples kept
      boolean atFloor = true;
      for (int f = 0; f < 180; f++) {
         atFloor &= frame(i60, (40 + r.nextInt(200)) * MS) == lo60;
      }
      Check.check(atFloor && Pacing.queueCalmWait(), "60 fps: 3 s of start bursts keep D at its floor (calm wait)");
      int calmAt = -1;
      for (int f = 0; f < 120; f++) {
         atFloor &= frame(i60, (4 + r.nextInt(3)) * MS) == lo60; // 4-6 ms + 0.3 + 3.33 (20 %) < 10
         if (calmAt < 0 && !Pacing.queueCalmWait()) {
            calmAt = f;
         }
      }
      Check.check(atFloor && calmAt >= 59 && calmAt <= 61, "60 fps: D learns after 1 s of calm frames (frame " + calmAt + "), still at the floor");
      // a burst of 25 ms frames: D follows at 0.5 ms a frame up to p99 + 0.3 ms + 20 %
      prev = lo60;
      maxStep = 0;
      int risen = -1;
      long want = 25 * MS + 300_000L + i60 * 20 / 100;
      for (int f = 0; f < 60; f++) {
         long d = frame(i60, 25 * MS);
         maxStep = Math.max(maxStep, Math.abs(d - prev));
         prev = d;
         if (risen < 0 && d == want) {
            risen = f;
         }
      }
      Check.check(maxStep <= slew && risen >= 0 && risen <= 40, "60 fps: a 25 ms burst raises D to " + want / 1e6 + " ms in " + risen + " frames, step <= 0.5 ms");
      // calm again: D falls at most 25 % of the interval a second once the window forgot the burst (4 s), at the floor within ~8.5 s
      long top = prev, maxFallPerS = 0, d0 = prev;
      int back = -1;
      for (int f = 0; f < 900; f++) {
         long d = frame(i60, 5 * MS);
         if (f % 60 == 59) {
            maxFallPerS = Math.max(maxFallPerS, d0 - d);
            d0 = d;
         }
         if (back < 0 && d == lo60) {
            back = f;
         }
      }
      long rate = i60 * 25 / 100;
      Check.check(back > 0 && back <= 570 && maxFallPerS <= rate + 50_000L,
            "60 fps: D back at the floor " + back / 60.0 + " s after the burst (from " + top / 1e6 + " ms), fall <= " + rate / 1e6 + " ms/s (" + maxFallPerS / 1e6 + ")");
      // the queue turns on again (pause menu closed): restart, calm wait, window empty
      Pacing.queueTurnedOn();
      frame(i60, 30 * MS);
      Check.check(Pacing.queueCalmWait(), "60 fps: queue on again -> calm wait");

      // 30 fps: the cap change restarts at the 20 ms floor (round 11: 87 ms, ~67 s to come down); start bursts ignored
      long i30 = 1_000_000_000L / 30, lo30 = i30 * 60 / 100;
      long d = 0;
      for (int f = 0; f < 25; f++) {
         d = frame(i30, (50 + r.nextInt(200)) * MS);
      }
      Check.check(d == lo30 && Pacing.queueCalmWait(), "60 -> 30 cap: D at the 20 ms floor (" + d / 1e6 + " ms) during the bursts");
      for (int f = 0; f < 65; f++) {
         d = Math.max(d, frame(i30, (60 + r.nextInt(100)) * MS));
      }
      boolean low30 = true;
      for (int f = 0; f < 150; f++) {
         low30 &= frame(i30, (6 + r.nextInt(6)) * MS) == lo30;
      }
      Check.check(d == lo30 && low30 && !Pacing.queueCalmWait(), "30 fps: D stays at the 20 ms floor through the start bursts and after (calm wait over)");
      // a scene that never calms down: D learns after at most 10 s anyway
      Pacing.queueTurnedOn();
      int learned = -1;
      for (int f = 0; f < 400; f++) {
         frame(i30, 40 * MS);
         if (learned < 0 && f > 0 && !Pacing.queueCalmWait()) {
            learned = f;
         }
      }
      Check.check(learned >= 299 && learned <= 301, "30 fps: no calm stretch -> D learns after 10 s (frame " + learned + ")");
      System.out.println("PacingQueueFloorTest ok");
   }
}
