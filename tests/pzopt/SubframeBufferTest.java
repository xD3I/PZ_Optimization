package pzopt;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SubframeBufferTest {
   public static void main(String[] args) throws Exception {
      shortClickAtActionPoint();
      frozenPrefixSurvivesSlotReuse();
      overflowCancelsWithoutActivation();
      focusCancelsFrozenActions();
      rightHoldUsesActionTime();
      heldAtStartupIsNotAPress();
      highResolutionWheelCompletesWholeNotches();
      concurrentRecordsStayCoherent();
      System.out.println("SubframeBufferTest ok");
   }

   private static void shortClickAtActionPoint() {
      SubframeBuffer b = new SubframeBuffer(16);
      b.seed(10, 20, 0, true);
      // No move callback at B: Win32 may coalesce it, but the button message has B.
      b.button(0, true, 100, 200, 1000);
      b.wheel(-0.5, 150, 250, 1100);
      b.button(0, false, 100, 200, 1200);
      b.move(300, 400, 1300);
      b.drain(1400);
      Check.check(b.count == 4, "press, wheel, release and final motion are retained");
      Check.check(b.pressed[0] == 1 && b.held[0] == 1 && b.xs[0] == 100 && b.ys[0] == 200,
            "short press uses B, not the previous callback A or current pointer C");
      Check.check(b.wheels[1] == -0.5 && b.xs[1] == 150 && b.ys[1] == 250,
            "fractional wheel retains its own point and ordering");
      Check.check(b.released[2] == 1 && b.held[2] == 0 && b.nanos[2] == 1200,
            "short release is a distinct timestamped transition");
      Check.check(b.frameX == 300 && b.frameY == 400 && b.frameHeld == 0,
            "latest C state is restored after action dispatch");
      Check.check(b.framePressed == 1 && b.frameReleased == 1,
            "both transitions survive a press/release inside one simulation frame");
   }

   private static void frozenPrefixSurvivesSlotReuse() {
      SubframeBuffer b = new SubframeBuffer(4);
      b.seed(0, 0, 0, true);
      b.move(1, -1, 1);
      b.drain(2);
      for (int i = 2; i <= 5; i++) b.move(i, -i, i);
      Check.check(b.count == 1 && b.xs[0] == 1 && b.ys[0] == -1 && b.frameX == 1,
            "producer wraparound cannot mutate the frozen frame or expose future input");
      b.drain(6);
      Check.check(b.count == 4 && b.xs[0] == 2 && b.xs[3] == 5 && b.frameX == 5,
            "the next frame takes the complete next prefix, including a full ring");
      b.drain(7);
      Check.check(b.count == 0 && b.frameX == 5 && b.framePressed == 0 && b.frameWheelSteps == 0,
            "an empty frame keeps pointer state without replaying old actions");
   }

   private static void highResolutionWheelCompletesWholeNotches() {
      SubframeBuffer b = new SubframeBuffer(32);
      b.seed(0, 0, 0, true);
      for (int i = 0; i < 3; i++) b.wheel(0.25, 1, 1, i + 1);
      b.drain(10);
      Check.check(b.count == 3 && b.wheelSteps[0] == 0 && b.wheelSteps[2] == 0 && b.frameWheelSteps == 0,
            "partial high-resolution records are not each a whole zoom/scroll step");
      b.wheel(0.25, 1, 1, 11);
      b.wheel(2.0, 1, 1, 12);
      b.drain(20);
      Check.check(b.wheelSteps[0] == 1 && b.wheelSteps[1] == 2 && b.frameWheelSteps == 3,
            "the remainder carries across frames and multi-notch records keep their count");
      b.wheel(0.5, 1, 1, 21);
      b.wheel(-0.75, 1, 1, 22);
      b.wheel(-0.25, 1, 1, 23);
      b.drain(30);
      Check.check(b.wheelSteps[1] == 0 && b.wheelSteps[2] == -1 && b.frameWheelSteps == -1,
            "reversing direction drops the opposite partial notch instead of cancelling against it");
      b.wheel(-0.5, 1, 1, 31);
      b.focus(false);
      b.drain(40);
      b.focus(true);
      b.drain(41);
      b.wheel(-0.5, 1, 1, 42);
      b.drain(50);
      Check.check(b.wheelSteps[0] == 0 && b.frameWheelSteps == 0,
            "a cancelled frame discards its partial notch");
   }

   private static void overflowCancelsWithoutActivation() {
      SubframeBuffer b = new SubframeBuffer(4);
      b.seed(0, 0, 0, true);
      b.button(0, true, 1, 2, 1);
      for (int i = 2; i <= 5; i++) b.move(i, -i, i);
      b.drain(6);
      Check.check(b.reset && b.count == 0 && b.frameHeld == 0 && b.frameReleased == 0,
            "overflow cancels the entire pending gesture, never an activating synthetic release");
      b.move(6, -6, 7);
      b.button(0, false, 6, -6, 8);
      b.drain(9);
      Check.check(b.framePressed == 0 && b.frameReleased == 0 && b.frameHeld == 0,
            "a button held across overflow is suppressed through its actual release");
      b.button(0, true, 7, -7, 10);
      b.drain(11);
      Check.check(b.count == 1 && b.pressed[0] == 1 && b.frameHeld == 1,
            "a fresh post-cancellation press is accepted");
   }

   private static void focusCancelsFrozenActions() {
      SubframeBuffer b = new SubframeBuffer(8);
      b.seed(0, 0, 0, true);
      b.button(0, true, 10, 20, 1);
      b.drain(2);
      b.focus(false);
      Check.check(b.hasPendingReset(), "focus loss invalidates even an already-frozen action");
      b.move(30, 40, 3);
      b.drain(4);
      Check.check(b.reset && b.count == 0 && b.frameHeld == 0,
            "unfocused callbacks do not recreate a held gesture");
      b.focus(true);
      b.move(40, 50, 5);
      b.button(0, false, 40, 50, 6);
      b.drain(7);
      Check.check(b.frameHeld == 0 && b.framePressed == 0 && b.frameReleased == 0,
            "focus regain does not activate the stale held button or its release");
      b.button(0, true, 60, 70, 8);
      b.drain(9);
      Check.check(b.pressed[0] == 1 && b.frameX == 60 && !b.hasPendingReset(),
            "new focused click remains usable after reset");
   }

   private static void rightHoldUsesActionTime() {
      SubframeBuffer b = new SubframeBuffer(8);
      b.seed(0, 0, 0, true);
      b.button(1, true, 1, 2, 1_000_000_000L);
      b.button(0, true, 3, 4, 1_200_000_000L);
      b.button(1, false, 5, 6, 1_210_000_000L);
      b.drain(1_500_000_000L);
      Check.check(b.nanos[1] - b.rightSince[1] == 200_000_000L && b.held[1] == 3,
            "attack B has its real 200ms right-hold, not C's released state/frame duration");
      Check.check(b.frameRightSince == 0 && b.frameHeld == 1,
            "final state independently reflects right release at C");
   }

   private static void heldAtStartupIsNotAPress() {
      SubframeBuffer b = new SubframeBuffer(8);
      b.seed(10, 20, 3, true);
      b.move(11, 21, 1);
      b.button(0, false, 11, 21, 2);
      b.button(1, false, 11, 21, 3);
      b.drain(4);
      Check.check(b.frameHeld == 0 && b.framePressed == 0 && b.frameReleased == 0,
            "activation with held buttons cannot click or release UI/world targets");
   }

   private static void concurrentRecordsStayCoherent() throws Exception {
      SubframeBuffer b = new SubframeBuffer(256);
      b.seed(0, ~0, 0, true);
      AtomicBoolean done = new AtomicBoolean();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      final int last = 200_000;
      Thread producer = new Thread(() -> {
         try {
            for (int i = 1; i <= last; i++) b.move(i, ~i, i * 125_000L);
         } catch (Throwable t) {
            failure.set(t);
         } finally {
            done.set(true);
         }
      }, "subframe-test-producer");
      producer.start();
      do {
         b.drain(Long.MAX_VALUE);
         for (int i = 0; i < b.count; i++) {
            Check.check(b.ys[i] == ~b.xs[i] && b.nanos[i] == b.xs[i] * 125_000L
                  && b.sequences[i] == b.xs[i] && b.held[i] == 0,
                  "publication and slot reuse cannot tear coordinates, time or state");
         }
      } while (!done.get());
      producer.join();
      if (failure.get() != null) throw new AssertionError("producer failed", failure.get());
      b.drain(Long.MAX_VALUE);
      Check.check(b.frameX == last && b.frameY == ~last,
            "the final published pointer survives concurrent drain/overflow cancellation");
   }
}
