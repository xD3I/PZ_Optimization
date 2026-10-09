package pzopt;

/** One window-thread producer, one game-thread consumer. No shared mutable event slots. */
final class SubframeBuffer {
   final int[] xs, ys, held, pressed, released, wheelSteps;
   final double[] wheels;
   final long[] nanos, sequences, rightSince;
   int count, frameX, frameY, frameHeld, framePressed, frameReleased, frameWheelSteps;
   long frameRightSince, frameTime, frameSerial;
   boolean reset, frameHasPosition;
   private static final int WHEEL_DELTA = 120;
   /** Consumer-owned partial notch (1/120 units) carried across records and frames, as Windows advises for high-resolution wheels. */
   private int wheelRemainder;

   private final int capacity, mask;
   private final int[] qx, qy, qheld, qpressed, qreleased;
   private final double[] qwheel;
   private final long[] qnanos, qsequence, qrightSince;
   private volatile long written, read;
   private volatile long resetSerial, acknowledgedReset;
   private volatile long latestPosition;
   private volatile int latestButtons;
   private volatile boolean focused = true, positioned;
   private int px, py, physicalButtons, suppressedButtons;
   private long sequence, rightDown;

   SubframeBuffer(int capacity) {
      if (capacity < 2 || (capacity & (capacity - 1)) != 0) throw new IllegalArgumentException("capacity must be a power of two");
      this.capacity = capacity;
      mask = capacity - 1;
      xs = new int[capacity]; ys = new int[capacity]; held = new int[capacity];
      pressed = new int[capacity]; released = new int[capacity]; wheelSteps = new int[capacity];
      wheels = new double[capacity]; nanos = new long[capacity];
      sequences = new long[capacity]; rightSince = new long[capacity];
      qx = new int[capacity]; qy = new int[capacity]; qheld = new int[capacity];
      qpressed = new int[capacity]; qreleased = new int[capacity];
      qwheel = new double[capacity]; qnanos = new long[capacity];
      qsequence = new long[capacity]; qrightSince = new long[capacity];
   }

   void seed(int x, int y, int buttons, boolean focused) {
      position(x, y);
      physicalButtons = suppressedButtons = buttons & 255;
      latestButtons = 0; // a button held before activation is not a new press
      this.focused = focused;
      if (!focused) requestReset();
   }

   private void position(int x, int y) {
      px = x;
      py = y;
      latestPosition = ((long)x << 32) | (y & 0xffffffffL);
      positioned = true;
   }

   void move(int x, int y, long now) {
      position(x, y);
      offer(0, 0, 0.0, now);
   }

   void button(int button, boolean down, int x, int y, long now) {
      if (button < 0 || button >= 8) return;
      position(x, y);
      int bit = 1 << button;
      boolean wasDown = (physicalButtons & bit) != 0;
      boolean suppressed = (suppressedButtons & bit) != 0;
      if (down) {
         physicalButtons |= bit;
      } else {
         physicalButtons &= ~bit;
         suppressedButtons &= ~bit;
      }
      if (wasDown == down) return;
      if (button == 1) rightDown = down && !suppressed ? now : 0;
      offer(down && !suppressed ? bit : 0, !down && !suppressed ? bit : 0, 0.0, now);
   }

   void wheel(double delta, int x, int y, long now) {
      if (delta == 0.0) return;
      position(x, y);
      offer(0, 0, delta, now);
   }

   void focus(boolean value) {
      if (focused == value) return;
      focused = value;
      if (!value) requestReset();
   }

   private void requestReset() {
      suppressedButtons |= physicalButtons;
      rightDown = 0;
      latestButtons = 0;
      // While unacknowledged, the producer writes no slots. The consumer may
      // discard the whole old prefix, then publish read before acknowledging.
      if (resetSerial == acknowledgedReset) resetSerial++;
   }

   private void offer(int press, int release, double wheel, long now) {
      sequence++;
      if (!focused || resetSerial != acknowledgedReset) {
         suppressedButtons |= physicalButtons;
         latestButtons = 0;
         return;
      }
      int buttons = physicalButtons & ~suppressedButtons;
      latestButtons = buttons;
      long w = written;
      if (w - read >= capacity) {
         requestReset();
         return;
      }
      int i = (int)w & mask;
      qx[i] = px; qy[i] = py; qheld[i] = buttons;
      qpressed[i] = press; qreleased[i] = release; qwheel[i] = wheel;
      qnanos[i] = now; qsequence[i] = sequence; qrightSince[i] = rightDown;
      written = w + 1; // release: publish the complete record
   }

   void drain(long now) {
      count = framePressed = frameReleased = 0;
      reset = false;
      frameTime = now;
      long serial = resetSerial;
      if (serial != acknowledgedReset) {
         discard(serial);
         return;
      }
      long end = written; // acquire once: events after this boundary belong to the next frame
      long r = read;
      int n = (int)(end - r);
      frameWheelSteps = 0;
      for (int i = 0; i < n; i++) {
         int slot = (int)(r + i) & mask;
         xs[i] = qx[slot]; ys[i] = qy[slot]; held[i] = qheld[slot];
         pressed[i] = qpressed[slot]; released[i] = qreleased[slot];
         wheels[i] = qwheel[slot]; nanos[i] = qnanos[slot];
         sequences[i] = qsequence[slot]; rightSince[i] = qrightSince[slot];
         framePressed |= pressed[i];
         frameReleased |= released[i];
         wheelSteps[i] = wheelStep(wheels[i]);
         frameWheelSteps += wheelSteps[i];
      }
      // No producer slot can be reused until the entire consumer-owned copy exists.
      read = end;
      if (serial != resetSerial) {
         discard(resetSerial);
         return;
      }
      count = n;
      frameSerial = serial;
      if (n != 0) {
         int last = n - 1;
         frameX = xs[last]; frameY = ys[last]; frameHeld = held[last];
         frameRightSince = rightSince[last];
         frameTime = Math.max(now, nanos[last]);
         frameHasPosition = true;
      } else if (!frameHasPosition && positioned) {
         long xy = latestPosition;
         frameX = (int)(xy >> 32); frameY = (int)xy;
         frameHasPosition = true;
      }
   }

   private void discard(long serial) {
      count = frameHeld = framePressed = frameReleased = 0;
      frameRightSince = 0;
      frameWheelSteps = 0;
      wheelRemainder = 0;
      reset = true;
      read = written;
      frameSerial = serial;
      acknowledgedReset = serial;
      // A producer may publish a final position while observing the old acknowledgement.
      // Read it after acknowledging, or that suppressed last move can remain stuck forever.
      long xy = latestPosition;
      frameX = (int)(xy >> 32); frameY = (int)xy;
      frameHasPosition = positioned;
   }

   /** Whole notches completed by this record; a partial notch carries over until the direction reverses. */
   private int wheelStep(double delta) {
      int units = (int)Math.round(delta * WHEEL_DELTA);
      if (units == 0) return 0;
      if (wheelRemainder != 0 && (wheelRemainder > 0) != (units > 0)) wheelRemainder = 0;
      wheelRemainder += units;
      int steps = wheelRemainder / WHEEL_DELTA;
      wheelRemainder -= steps * WHEEL_DELTA;
      return steps;
   }

   /** Frames whose records are not replayed must not leave a partial notch for later input. */
   void resetWheel() { wheelRemainder = 0; }

   boolean hasPendingReset() { return frameSerial != resetSerial || !focused; }
   boolean focused() { return focused; }
   boolean hasPosition() { return positioned; }
   long latestPosition() { return latestPosition; }
   int latestButtons() { return latestButtons; }
   int producerX() { return px; }
   int producerY() { return py; }
}
