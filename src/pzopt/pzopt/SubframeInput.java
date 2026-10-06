package pzopt;

import java.util.Arrays;
import zombie.input.Mouse;

/** Window callbacks publish; the simulation freezes one finite prefix before updating input/UI. */
public final class SubframeInput {
   public static final int CAPACITY = 32768;
   private static final SubframeBuffer BUFFER = new SubframeBuffer(CAPACITY);
   public static final int[] xs = BUFFER.xs, ys = BUFFER.ys, held = BUFFER.held;
   public static final int[] pressed = BUFFER.pressed, released = BUFFER.released;
   public static final int[] wheelSteps = BUFFER.wheelSteps;
   public static final long[] nanos = BUFFER.nanos, sequences = BUFFER.sequences;
   public static final boolean[] consumed = new boolean[CAPACITY];
   private static final int[] captureMasks = new int[CAPACITY];
   public static int count;
   public static boolean reset;
   private static final long WALL_MILLIS_OFFSET = System.currentTimeMillis() - System.nanoTime() / 1_000_000L;
   private static long frameNumber;
   private static int previousX, previousY, remainingWheel;
   private static volatile Thread eventThread;
   private static int eventIndex = -1;
   private static int savedDown, savedPrevious, savedCapture;
   private static boolean restoreCapture;
   private static boolean viewportBlocked;
   private static int viewportSuppressedButtons;

   private SubframeInput() {}

   public static boolean active() { return InputThread.active(); }

   public static void seed(double x, double y, int buttons, boolean focused) {
      BUFFER.seed((int)x, (int)y, buttons, focused);
   }

   public static void offerMove(double x, double y) {
      BUFFER.move((int)x, (int)y, System.nanoTime());
   }

   public static void offerButton(int button, boolean down) {
      offerButtonAt(button, down, BUFFER.producerX(), BUFFER.producerY());
   }

   public static void offerButtonAt(int button, boolean down, int x, int y) {
      BUFFER.button(button, down, x, y, System.nanoTime());
   }

   public static void offerWheel(double delta) {
      offerWheelAt(delta, BUFFER.producerX(), BUFFER.producerY());
   }

   public static void offerWheelAt(double delta, int x, int y) {
      BUFFER.wheel(delta, x, y, System.nanoTime());
   }

   public static void focus(boolean focused) { BUFFER.focus(focused); }

   public static void beginFrame() {
      if (eventIndex != -1) throw new IllegalStateException("input frame inside event scope");
      previousX = BUFFER.frameX;
      previousY = BUFFER.frameY;
      BUFFER.drain(System.nanoTime());
      boolean wasViewportBlocked = viewportBlocked;
      viewportBlocked = zombie.core.Core.isUseGameViewport()
         && !zombie.debug.DebugContext.instance.focusedGameViewport;
      count = viewportBlocked ? 0 : BUFFER.count;
      reset = BUFFER.reset || viewportBlocked;
      if (viewportBlocked && !wasViewportBlocked) InputThread.invoke(zombie.input.GameKeyboard::pzoptFocusLost);
      if (reset || BUFFER.hasPendingReset()) SubframeCombat.cancelPending();
      if (viewportBlocked) viewportSuppressedButtons |= BUFFER.frameHeld;
      for (int i = 0; i < count && viewportSuppressedButtons != 0; i++) {
         int releasedHere = released[i];
         pressed[i] &= ~viewportSuppressedButtons;
         released[i] &= ~viewportSuppressedButtons;
         held[i] &= ~viewportSuppressedButtons;
         viewportSuppressedButtons &= ~releasedHere;
      }
      viewportSuppressedButtons &= BUFFER.frameHeld;
      if (viewportBlocked) BUFFER.resetWheel();
      remainingWheel = viewportBlocked ? 0 : BUFFER.frameWheelSteps;
      Arrays.fill(consumed, 0, count, false);
      Arrays.fill(captureMasks, 0, count, 0);
      frameNumber++;
   }

   public static long frameNumber() { return frameNumber; }
   public static boolean hasPendingReset() { return viewportBlocked || BUFFER.hasPendingReset(); }
   public static boolean hasPosition() { return BUFFER.frameHasPosition; }
   public static boolean focused() { return BUFFER.focused(); }
   public static int x() { return BUFFER.frameX; }
   public static int y() { return BUFFER.frameY; }
   public static int buttons() { return reset ? 0 : BUFFER.frameHeld & ~viewportSuppressedButtons; }
   public static int framePressed() { return BUFFER.framePressed; }
   public static int frameReleased() { return BUFFER.frameReleased; }
   public static long latestPosition() { return BUFFER.latestPosition(); }
   public static int latestX() { return (int)(latestPosition() >> 32); }
   public static int latestY() { return (int)latestPosition(); }
   /** Whole wheel notches completed in this frame; partial high-resolution notches carry into later records. */
   public static int wheel() { return BUFFER.frameWheelSteps; }
   public static int dx() { return inEvent() ? eventX() - (eventIndex == 0 ? previousX : xs[eventIndex - 1]) : x() - previousX; }
   public static int dy() { return inEvent() ? eventY() - (eventIndex == 0 ? previousY : ys[eventIndex - 1]) : y() - previousY; }
   public static int takeWheel() {
      if (inEvent()) return eventWheelSteps();
      int value = remainingWheel;
      remainingWheel = 0;
      return value;
   }
   public static int latestButtons() { return BUFFER.latestButtons(); }
   public static boolean inEvent() { return eventThread == Thread.currentThread(); }
   public static int eventX() { return xs[eventIndex]; }
   public static int eventY() { return ys[eventIndex]; }
   public static int eventHeld() { return held[eventIndex]; }
   public static int eventPressed() { return pressed[eventIndex]; }
   public static int eventReleased() { return released[eventIndex]; }
   public static int eventWheelSteps() { return wheelSteps[eventIndex]; }
   public static long eventSequence() { return sequences[eventIndex]; }
   public static long eventNanos() { return nanos[eventIndex]; }
   public static long eventMillis() { return WALL_MILLIS_OFFSET + eventNanos() / 1_000_000L; }

   public static long rightHeldNanos() {
      long since = inEvent() ? BUFFER.rightSince[eventIndex] : BUFFER.frameRightSince;
      long now = inEvent() ? nanos[eventIndex] : BUFFER.frameTime;
      return since == 0 ? 0 : Math.max(0, now - since);
   }

   public static void consume(int index) { consumed[index] = true; }

   public static void capture(int index) { captureMasks[index] = mask(Mouse.uiCaptured); }

   public static void beginEvent(int index) {
      if (eventIndex != -1) throw new IllegalStateException("nested input event scope");
      if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
      savedDown = mask(Mouse.buttonDownStates);
      savedPrevious = mask(Mouse.buttonPrevStates);
      eventIndex = index;
      restoreCapture = false;
      Mouse.pzoptButtonState(held[index], held[index] ^ pressed[index] ^ released[index]);
      eventThread = Thread.currentThread();
   }

   public static void beginActionEvent(int index) {
      beginEvent(index);
      savedCapture = mask(Mouse.uiCaptured);
      restoreCapture = true;
      setMask(Mouse.uiCaptured, captureMasks[index]);
   }

   public static void endEvent() {
      if (!inEvent()) throw new IllegalStateException("input event owned by another thread");
      Mouse.pzoptButtonState(savedDown, savedPrevious);
      if (restoreCapture) setMask(Mouse.uiCaptured, savedCapture);
      eventThread = null;
      eventIndex = -1;
   }

   private static int mask(boolean[] values) {
      int mask = 0;
      if (values != null) {
         for (int i = 0; i < values.length; i++) if (values[i]) mask |= 1 << i;
      }
      return mask;
   }

   private static void setMask(boolean[] values, int mask) {
      for (int i = 0; i < values.length; i++) values[i] = (mask & (1 << i)) != 0;
   }
}
