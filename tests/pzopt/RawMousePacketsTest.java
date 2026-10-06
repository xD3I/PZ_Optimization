package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public final class RawMousePacketsTest {
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);
   private static final ValueLayout.OfShort S = ValueLayout.JAVA_SHORT.withOrder(ByteOrder.LITTLE_ENDIAN).withByteAlignment(1);

   private RawMousePacketsTest() { }

   public static void main(String[] args) {
      orderedRecordsAndPadding();
      exactEndRecord();
      rejectsMalformedBatches();
      swappedButtonsBecomeLogical();
      scalesRelativeMotionWithSignedRemainders();
      accelerationCurveIsBoundedAndMonotone();
      estimatesLowAndHighCollectTimeRates();
      liveMotionSettingsResetEstimator();
      accelerationOptionsAreLiveAndDefaultOff();
   }

   private static void swappedButtonsBecomeLogical() {
      int physical = 0x1 | 0x8 | 0x10 | 0x40 | 0x400; // left down, right up, middle down, X1 down, wheel
      Check.check(RawMouse.logicalButtonFlags(physical, false) == physical,
            "unswapped Windows keeps raw button flags");
      Check.check(RawMouse.logicalButtonFlags(physical, true) == (0x4 | 0x2 | 0x10 | 0x40 | 0x400),
            "swapped Windows turns physical left/right transitions into logical right/left, others unchanged");
   }

   private static void scalesRelativeMotionWithSignedRemainders() {
      RawMouse.AxisMotion axis = new RawMouse.AxisMotion();
      Check.check(axis.apply(1, 10) == 0 && axis.apply(1, 10) == 1, "0.5 sensitivity retains half-count movement");
      Check.check(axis.apply(-1, 10) == 0 && axis.apply(-1, 10) == -1, "opposite signed counts cancel without drift");
      Check.check(axis.apply(2, 40) == 4, "2.0 sensitivity doubles counts");
      Check.check(axis.apply(1, 10) == 0 && axis.apply(1, 10) == 1, "sensitivity change discards old-scale remainder");
      axis.reset();
      Check.check(axis.apply(-1, 10) == 0 && axis.apply(-1, 10) == -1, "negative fractions accumulate symmetrically");
      axis.reset();
      long off = 0;
      for (int i = 0; i < 20; i++) off += axis.apply(1, 10);
      Check.check(off == 10, "disabled 1x path is the existing exact linear sensitivity mapping");
      axis.reset();
      long accelerated = 0;
      for (int i = 0; i < 4; i++) accelerated += axis.apply(1, 10, 1.5);
      Check.check(accelerated == 3, "positive fractional accelerated counts are carried, not discarded");
      axis.reset();
      long reverse = 0;
      for (int i = 0; i < 4; i++) reverse += axis.apply(-1, 10, 1.5);
      Check.check(reverse == -3, "negative fractional accelerated counts are symmetric");
      axis.reset();
      Check.check(axis.apply(1, 10, 1.5) == 0 && axis.apply(-1, 10, 1.5) == 0,
            "direction reversal cancels the signed accelerated fractional remainder");
   }

   private static void accelerationCurveIsBoundedAndMonotone() {
      Check.check(RawMouse.AccelerationEstimator.curveGain(1000.0, 1000, 50, 200) == 1.0,
            "gain is continuous and exactly 1x at onset");
      Check.check(RawMouse.AccelerationEstimator.curveGain(2000.0, 1000, 50, 200) == 1.5,
            "slope adds 50 percent per extra 1,000 counts/s");
      double previous = 1.0;
      for (int rate = 1000; rate <= 10000; rate += 250) {
         double gain = RawMouse.AccelerationEstimator.curveGain(rate, 1000, 50, 200);
         Check.check(gain >= previous && gain <= 2.0, "curve is monotone and bounded at " + rate + " counts/s");
         previous = gain;
      }
      Check.check(RawMouse.AccelerationEstimator.curveGain(10000, 1000, 50, 200) == 2.0,
            "curve reaches the configured 2x cap");
      Check.check(RawMouse.AccelerationEstimator.curveGain(999, 1000, 50, 200) == 1.0,
            "below onset stays linear");
   }

   private static void estimatesLowAndHighCollectTimeRates() {
      RawMouse.AccelerationEstimator estimator = new RawMouse.AccelerationEstimator();
      long start = 1_000_000_000L;
      estimator.begin(start, true);
      estimator.end(start, 4, true, 1000, 50, 200);
      Check.check(estimator.gain() == 1.0, "one coalesced motion batch cannot infer speed from a tiny interval");
      estimator.begin(start + 8_000_000L, true);
      estimator.end(start + 8_000_000L, 4, true, 1000, 50, 200);
      Check.check(estimator.gain() == 1.0, "1,000 collect-time counts/s is exactly the onset");
      RawMouse.AxisMotion lowX = new RawMouse.AxisMotion(), lowY = new RawMouse.AxisMotion();
      Check.check(lowX.apply(20, 20, estimator.gain()) == 20 && lowY.apply(-12, 20, estimator.gain()) == -12,
            "at onset native relative movement stays (20,-12)");
      estimator.begin(start + 8_000_001L, true);
      Check.check(estimator.gain() == 1.0, "rate estimate is used for the following batch");

      estimator.reset();
      estimator.begin(start, true);
      estimator.end(start, 8, true, 1000, 50, 200);
      estimator.begin(start + 8_000_000L, true);
      estimator.end(start + 8_000_000L, 8, true, 1000, 50, 200);
      Check.check(estimator.gain() == 1.5, "16 counts accumulated over 8 ms estimates 2,000 counts/s");
      estimator.begin(start + 8_000_001L, true);
      Check.check(estimator.gain() == 1.5, "high-speed estimate persists into the next batch");
      RawMouse.AxisMotion highX = new RawMouse.AxisMotion(), highY = new RawMouse.AxisMotion();
      Check.check(highX.apply(20, 20, estimator.gain()) == 30 && highY.apply(-12, 20, estimator.gain()) == -18,
            "2,000 counts/s maps native (20,-12) to (30,-18)");

      estimator.reset();
      estimator.begin(start, true);
      estimator.end(start, 24, true, 1000, 50, 200);
      estimator.begin(start + 8_000_000L, true);
      estimator.end(start + 8_000_000L, 24, true, 1000, 50, 200);
      Check.check(estimator.gain() == 2.0, "6,000 collect-time counts/s is capped at 2x");
      RawMouse.AxisMotion capX = new RawMouse.AxisMotion(), capY = new RawMouse.AxisMotion();
      Check.check(capX.apply(20, 20, estimator.gain()) == 40 && capY.apply(-12, 20, estimator.gain()) == -24,
            "6,000 counts/s maps native (20,-12) to the capped (40,-24)");
      Check.check(estimator.begin(start + 60_000_000L, true) == 1.0,
            "a gap over 50 ms discards stale speed and returns gain to 1x");
   }

   private static void liveMotionSettingsResetEstimator() {
      RawMouse.MotionSettings settings = new RawMouse.MotionSettings();
      RawMouse.AccelerationEstimator estimator = new RawMouse.AccelerationEstimator();
      long start = 1_000_000_000L;
      estimator.begin(start, true);
      estimator.end(start, 8, true, 1000, 50, 200);
      estimator.begin(start + 8_000_000L, true);
      estimator.end(start + 8_000_000L, 8, true, 1000, 50, 200);
      Check.check(estimator.gain() == 1.5, "fixture starts with an active acceleration estimate");
      Check.check(!settings.changed(20, true, 1000, 50, 200), "initial config snapshot does not signal a transition");
      boolean changed = settings.changed(20, true, 1000, 75, 200);
      if (changed) estimator.reset();
      Check.check(changed && estimator.gain() == 1.0, "live curve change clears rate history and resets gain");
      Check.check(settings.changed(20, false, 1000, 75, 200), "turning acceleration off is a live config transition");
   }

   private static void accelerationOptionsAreLiveAndDefaultOff() {
      Check.check(Config.knows("mouseAcceleration") && "false".equals(Config.defaultValue("mouseAcceleration")),
            "acceleration option is registered and defaults off");
      for (String key : new String[] {"mouseAcceleration", "mouseAccelerationOnsetCps",
            "mouseAccelerationSlopePctPerKcps", "mouseAccelerationCapPct"}) {
         Check.check(Config.knows(key) && Config.isLive(key), key + " is recognized and live");
      }
      Check.check("1000".equals(Config.defaultValue("mouseAccelerationOnsetCps"))
            && "50".equals(Config.defaultValue("mouseAccelerationSlopePctPerKcps"))
            && "200".equals(Config.defaultValue("mouseAccelerationCapPct")), "curve defaults have documented units");
   }

   private static void orderedRecordsAndPadding() {
      try (Arena arena = Arena.ofConfined()) {
         // 48-byte mouse, 25-byte non-mouse padded to 32, then 48-byte mouse at exact end.
         MemorySegment bytes = arena.allocate(128);
         mouse(bytes, 0, 0x1234, 0x4321, -120, -7, 23, 0x1122334455667788L);
         header(bytes, 48, 25, 0x123456789abcdef0L);
         bytes.set(I, 48, 1); // RIM_TYPEKEYBOARD: valid header-only non-mouse record
         mouse(bytes, 80, 0x8001, 0x0004, 120, Integer.MIN_VALUE, Integer.MAX_VALUE, -1L);

         int[] seen = {0};
         RawMousePackets.dispatch(bytes, 128, 3, (flags, buttons, data, x, y, device) -> {
            if (seen[0]++ == 0) {
               Check.check(flags == 0x1234 && buttons == 0x4321, "first mouse unsigned flags and buttons");
               Check.check(data == -120 && x == -7 && y == 23, "first mouse signed wheel and movement");
               Check.check(device == 0x1122334455667788L, "first mouse device handle");
            } else {
               Check.check(flags == 0x8001 && buttons == 4, "second mouse unsigned flags and buttons");
               Check.check(data == 120 && x == Integer.MIN_VALUE && y == Integer.MAX_VALUE, "second mouse signed data and movement");
               Check.check(device == -1L, "second mouse device handle");
            }
         });
         Check.check(seen[0] == 2, "non-mouse skipped and mouse records dispatched in order");
      }
   }

   private static void exactEndRecord() {
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment bytes = arena.allocate(48);
         mouse(bytes, 0, 1, 2, -1, -3, -4, 5);
         int[] seen = {0};
         RawMousePackets.dispatch(bytes, 48, 1, (flags, buttons, data, x, y, device) -> seen[0]++);
         Check.check(seen[0] == 1, "valid final record needs no trailing alignment padding");
      }
   }

   private static void rejectsMalformedBatches() {
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment bytes = arena.allocate(96);
         header(bytes, 0, 0, 0);
         rejects(bytes, 48, 1, "zero record size");
         header(bytes, 0, 23, 0);
         rejects(bytes, 48, 1, "undersized record header");
         header(bytes, 0, 47, 0);
         rejects(bytes, 48, 1, "truncated mouse payload");

         mouse(bytes, 0, 0, 0, 0, 0, 0, 0);
         rejects(bytes, 47, 1, "byteCount truncates mouse record");
         rejects(bytes, 96, 2, "count requests missing second record");
         rejects(bytes, 48, 2, "count requests record beyond byteCount");
         rejects(bytes.asSlice(0, 47), 48, 1, "byteCount exceeds segment bound");
         rejects(bytes, -1, 1, "negative byteCount");
         rejects(bytes, 48, -1, "negative count");

         header(bytes, 0, 25, 0);
         bytes.set(I, 0, 1); // A non-mouse record may contain only its header
         rejects(bytes, 49, 2, "missing alignment padding and next record");
      }
   }

   private static void rejects(MemorySegment bytes, int byteCount, int count, String what) {
      boolean rejected = false;
      try {
         RawMousePackets.dispatch(bytes, byteCount, count, (flags, buttons, data, x, y, device) -> { });
      } catch (IllegalArgumentException expected) {
         rejected = true;
      }
      Check.check(rejected, what);
   }

   private static void mouse(MemorySegment bytes, long offset, int flags, int buttons, int data, int x, int y, long device) {
      header(bytes, offset, 48, device);
      bytes.set(S, offset + 24, (short)flags);
      bytes.set(S, offset + 28, (short)buttons);
      bytes.set(S, offset + 30, (short)data);
      bytes.set(I, offset + 36, x);
      bytes.set(I, offset + 40, y);
   }

   private static void header(MemorySegment bytes, long offset, int size, long device) {
      bytes.set(I, offset, 0);
      bytes.set(I, offset + 4, size);
      bytes.set(J, offset + 8, device);
   }
}
