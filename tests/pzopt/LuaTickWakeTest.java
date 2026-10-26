package pzopt;

import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * luaTickInputWake (pzopt.LuaTick): under the cap (60 Hz at 240 fps = a call every 4 frames) an input poller runs in every
 * frame with an input change, whatever its place in the stagger, and at its interval otherwise; a handler without the
 * flag never runs because of input. Runs in its own JVM: the cap and the key are read once, when LuaTick loads.
 */
public class LuaTickWakeTest {
   public static void main(String[] args) {
      System.setProperty("pzopt.luaTickHz", "60");
      System.setProperty("pzopt.luaTickInputWake", "true");
      Check.check(LuaTick.HZ == 60 && LuaTick.INPUT_WAKE, "cap and key set");
      LuaTick.periodNs = 1e9 / 240.0;
      java.util.Random r = new java.util.Random(7);
      LuaTick.Slot s = new LuaTick.Slot("OnTick", 0, new ArrayList<>());
      List<LuaTick.H> hs = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
         LuaTick.H h = new LuaTick.H(new LuaClosure(new Prototype(), null));
         h.pin = LuaTick.PIN_NONE;
         h.samples = LuaTick.WARM_CALLS;
         h.ewmaNs = (1 + r.nextDouble() * 300) * 1000.0;
         h.wake = i % 2 == 0;
         hs.add(h);
      }
      int frames = 2400;
      long[] runs = new long[2];
      long inputFrames = 0, missed = 0, forcedPlain = 0;
      for (long f = 1; f <= frames; f++) {
         LuaTick.frameNo = f;
         boolean input = r.nextDouble() < 0.05;
         LuaTick.inputNow = input;
         if (input) {
            inputFrames++;
         }
         for (LuaTick.H h : hs) {
            boolean dueBefore = !(h.due > f + 0.5 && h.due - f <= LuaTick.maxAhead(LuaTick.interval(h)) + 1.0);
            boolean run = LuaTick.decide(s, h, null);
            if (run) {
               h.lastRunFrame = f;
               runs[h.wake ? 1 : 0]++;
               if (!h.wake && !dueBefore) {
                  forcedPlain++;
               }
            }
            if (h.wake && input && !run) {
               missed++;
            }
         }
      }
      Check.check(missed == 0, missed + " input frames where an input poller did not run");
      Check.check(forcedPlain == 0, forcedPlain + " runs of plain handlers before their frame");
      double plainRate = runs[0] / (100.0 * frames);
      Check.check(Math.abs(plainRate - 0.25) < 0.0125, "plain handlers run every " + 1 / plainRate + " frames, want 4");
      double wakeRate = runs[1] / (100.0 * frames);
      double inputShare = inputFrames / (double)frames;
      // at most one run per frame: the input frames plus the schedule's frames between them (an input run restarts the period)
      Check.check(wakeRate >= inputShare && wakeRate <= inputShare + 0.25 + 0.01, "input pollers run in " + wakeRate + " of the frames, input in " + inputShare);
      System.out.printf("LuaTickWakeTest: input pollers ran in every one of %d input frames (%.3f of all frames, plain %.3f), %d wake runs%n",
            inputFrames, wakeRate, plainRate, LuaTick.wakeRuns);
   }
}
