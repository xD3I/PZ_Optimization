package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjglx.opengl.Display;
import zombie.core.Core;

/** Owner-thread buffered Win32 mouse input. GLFW retains keyboard and window-message dispatch only. */
public final class RawMouse {
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
   private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
   private static final int WM_INPUT = 0xff, HEADER_SIZE = 24, BUFFER_SIZE = 65536, SM_SWAPBUTTON = 23;
   private static final RawMousePackets.Sink sink = RawMouse::mouse;
   private static Arena arena;
   private static MemorySegment buffer, single, size, device, point, clip, client, pid;
   private static MethodHandle register, readBuffer, readData, setWindowProc, callWindowProc, defWindowProc;
   private static MethodHandle waitMessages, getCursorPos, setCursorPos, clientToScreen, getAsyncKeyState;
   private static MethodHandle getClipCursor, getClientRect, getForegroundWindow, getWindowThread, getThreadId;
   private static MethodHandle getSystemMetrics, isWindow, getLastError;
   private static long hwnd, originalProc, targetWindow, targetHwnd;
   private static int ownerId, cursorX, cursorY, held;
   private static int left, top, right, bottom, clientLeft, clientTop, clientRight, clientBottom;
   private static int mainLeft, mainTop;
   private static int desktopLeft, desktopTop, desktopWidth, desktopHeight, primaryWidth, primaryHeight;
   private static double scaleX, scaleY;
   private static boolean positionValid, accepting, moved, inClient, registered;
   private static volatile boolean active;
   private static Throwable callbackFailure;
   private static long bufferedPackets, messagePackets, nonzeroDevicePackets, zeroDevicePackets;
   private static int largestBatch;
   private static boolean reportedBuffer;
   private static boolean batchOpen, batchAcceleration, windowFocused, swapButtons;
   private static double batchGain = 1.0, batchMovementCounts;
   private static int batchOnsetCps, batchSlopePctPerKcps, batchCapPct;
   private static final MotionSettings motionSettings = new MotionSettings();
   private static final AxisMotion motionX = new AxisMotion(), motionY = new AxisMotion();
   private static final AccelerationEstimator accelerationEstimator = new AccelerationEstimator();

   private RawMouse() { }
   /** Signed fractional cursor motion; the 1x path keeps the original exact twentieth-count arithmetic. */
   static final class AxisMotion {
      private double remainder;
      private int sensitivity = 20;

      long apply(int delta, int sensitivityUnits) {
         if (sensitivity != sensitivityUnits) {
            sensitivity = sensitivityUnits;
            remainder = 0;
         }
         long total = (long)delta * sensitivityUnits + (long)remainder;
         long moved = total / 20;
         remainder = total % 20;
         return moved;
      }

      long apply(int delta, int sensitivityUnits, double gain) {
         if (sensitivity != sensitivityUnits) {
            sensitivity = sensitivityUnits;
            remainder = 0;
         }
         double total = (long)delta * sensitivityUnits * gain + remainder;
         long moved = (long)(total / 20.0);
         remainder = total - moved * 20.0;
         return moved;
      }

      void reset() { remainder = 0; }
   }

   /** Bounded continuous speed curve and fixed-window collect-time estimator, isolated for deterministic tests. */
   static final class AccelerationEstimator {
      static final long WINDOW_NANOS = 8_000_000L, STALL_NANOS = 50_000_000L;
      private long windowStart, lastBatchEnd;
      private double windowCounts, gain = 1.0;
      private int movingBatches;

      static double curveGain(double rateCps, int onsetCps, int slopePctPerKcps, int capPct) {
         double excess = Math.max(0.0, rateCps - onsetCps);
         double added = (slopePctPerKcps / 100.0) * (excess / 1000.0);
         return 1.0 + Math.min((capPct - 100) / 100.0, added);
      }

      double begin(long now, boolean enabled) {
         if (!enabled) {
            reset();
            return 1.0;
         }
         if (lastBatchEnd != 0 && (now - lastBatchEnd > STALL_NANOS || now < lastBatchEnd)) reset();
         if (windowStart == 0) windowStart = now;
         return gain;
      }

      void end(long now, double movementCounts, boolean enabled, int onsetCps, int slopePctPerKcps, int capPct) {
         if (!enabled) {
            reset();
            return;
         }
         if (windowStart == 0) windowStart = now;
         if (movementCounts > 0) {
            windowCounts += movementCounts;
            movingBatches++;
         }
         long elapsed = now - windowStart;
         if (elapsed > STALL_NANOS || elapsed < 0) {
            reset();
         } else if (elapsed >= WINDOW_NANOS && movingBatches >= 2) {
            double rate = windowCounts * 1_000_000_000.0 / elapsed;
            gain = curveGain(rate, onsetCps, slopePctPerKcps, capPct);
            windowStart = now;
            windowCounts = 0;
            movingBatches = 0;
         }
         lastBatchEnd = now;
      }

      double gain() { return gain; }
      void reset() {
         windowStart = lastBatchEnd = 0;
         windowCounts = 0;
         movingBatches = 0;
         gain = 1.0;
      }
   }
   static final class MotionSettings {
      private boolean initialized, acceleration;
      private int sensitivity, onset, slope, cap;

      boolean changed(int newSensitivity, boolean newAcceleration, int newOnset, int newSlope, int newCap) {
         boolean changed = initialized && (sensitivity != newSensitivity || acceleration != newAcceleration
               || (newAcceleration && (onset != newOnset || slope != newSlope || cap != newCap)));
         initialized = true;
         sensitivity = newSensitivity;
         acceleration = newAcceleration;
         onset = newOnset;
         slope = newSlope;
         cap = newCap;
         return changed;
      }
   }

   private static void resetMotion() {
      motionX.reset();
      motionY.reset();
      accelerationEstimator.reset();
   }
   public static boolean active() { return active; }

   /**
    * Raw Input reports physical buttons; window messages (the stock GLFW path) apply "switch primary and secondary
    * buttons". Device-less packets (precision touchpads, injected input) are already logical.
    */
   static int logicalButtonFlags(int buttonFlags, boolean swap) {
      return swap ? (buttonFlags & ~0xf) | (buttonFlags & 0x3) << 2 | (buttonFlags & 0xc) >> 2 : buttonFlags;
   }

   /** Reconcile releases missed while foreground-only registration was inactive, without inventing a press. */
   public static void focusChanged(boolean focused) {
      if (!active) return;
      requireOwner();
      if (windowFocused == focused) return;
      windowFocused = focused;
      positionValid = false;
      resetMotion();
      if (!focused) return;
      try {
         // GetAsyncKeyState also reads physical buttons: logical left is VK_RBUTTON when the buttons are swapped.
         int swap = (int)getSystemMetrics.invokeExact(SM_SWAPBUTTON) != 0 ? 1 : 0;
         for (int button = 0; button < 5; button++) {
            int vk = button < 2 ? (button ^ swap) + 1 : button + 2;
            if ((short)getAsyncKeyState.invokeExact(vk) >= 0) {
               SubframeInput.offerButtonAt(button, false, SubframeInput.latestX(), SubframeInput.latestY());
            }
         }
      } catch (Throwable e) { throw new IllegalStateException("Unable to reconcile raw mouse focus", e); }
   }

   public static void initialize() {
      requireOwner();
      if (active) throw new IllegalStateException("Raw mouse already initialized");
      if (P.byteSize() != 8) throw new IllegalStateException("Buffered raw mouse requires Windows x64");
      arena = Arena.ofConfined();
      try {
         Linker linker = Linker.nativeLinker();
         SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
         SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", arena);
         register = bind(linker, user32, "RegisterRawInputDevices", I, P, I, I);
         readBuffer = bind(linker, user32, "GetRawInputBuffer", I, P, P, I);
         readData = bind(linker, user32, "GetRawInputData", I, J, I, P, P, I);
         setWindowProc = bind(linker, user32, "SetWindowLongPtrW", J, J, I, J);
         callWindowProc = bind(linker, user32, "CallWindowProcW", J, J, J, I, J, J);
         defWindowProc = bind(linker, user32, "DefWindowProcW", J, J, I, J, J);
         waitMessages = bind(linker, user32, "MsgWaitForMultipleObjectsEx", I, I, P, I, I, I);
         getCursorPos = bind(linker, user32, "GetCursorPos", I, P);
         setCursorPos = bind(linker, user32, "SetCursorPos", I, I, I);
         getAsyncKeyState = bind(linker, user32, "GetAsyncKeyState", ValueLayout.JAVA_SHORT, I);
         clientToScreen = bind(linker, user32, "ClientToScreen", I, J, P);
         getClipCursor = bind(linker, user32, "GetClipCursor", I, P);
         getClientRect = bind(linker, user32, "GetClientRect", I, J, P);
         getForegroundWindow = bind(linker, user32, "GetForegroundWindow", J);
         getWindowThread = bind(linker, user32, "GetWindowThreadProcessId", I, J, P);
         getThreadId = bind(linker, kernel32, "GetCurrentThreadId", I);
         getSystemMetrics = bind(linker, user32, "GetSystemMetrics", I, I);
         isWindow = bind(linker, user32, "IsWindow", I, J);
         getLastError = bind(linker, kernel32, "GetLastError", I);
         buffer = arena.allocate(BUFFER_SIZE, 8);
         single = arena.allocate(BUFFER_SIZE, 8);
         size = arena.allocate(4, 4);
         device = arena.allocate(16, 8);
         point = arena.allocate(8, 4);
         clip = arena.allocate(16, 4);
         client = arena.allocate(16, 4);
         pid = arena.allocate(4, 4);
         ownerId = (int)getThreadId.invokeExact();
         hwnd = GLFWNativeWin32.glfwGetWin32Window(Display.getWindow());
         var callback = MethodHandles.lookup().findStatic(RawMouse.class, "windowProc",
            MethodType.methodType(long.class, long.class, int.class, long.class, long.class));
         long proc = linker.upcallStub(callback, FunctionDescriptor.of(J, J, I, J, J), arena).address();
         originalProc = (long)setWindowProc.invokeExact(hwnd, -4, proc);
         if (originalProc == 0) throw error("SetWindowLongPtrW");
         device.set(ValueLayout.JAVA_SHORT, 0, (short)1);
         device.set(ValueLayout.JAVA_SHORT, 2, (short)2);
         // Keep legacy messages for native title bars, activation and GLFW's keyboard/window bookkeeping.
         // Mouse callbacks are gated off; registration is foreground-only (no INPUTSINK/CAPTUREMOUSE).
         device.set(I, 4, 0);
         device.set(J, 8, hwnd);
         if ((int)register.invokeExact(device, 1, 16) == 0) throw error("RegisterRawInputDevices");
         registered = true;
         refreshDesktop();
         if ((int)getCursorPos.invokeExact(point) == 0) throw error("GetCursorPos");
         cursorX = point.get(I, 0); cursorY = point.get(I, 4);
         resetMotion();
         positionValid = true;
         windowFocused = GLFW.glfwGetWindowAttrib(Display.getWindow(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
         active = true;
         System.out.println("[pzopt-raw] registered mouse: GetRawInputBuffer, foreground-only, owner=" + Thread.currentThread().getName());
      } catch (Throwable e) {
         close();
         throw new IllegalStateException("Unable to initialize buffered raw mouse", e);
      }
   }

   /** Wait without letting GLFW remove WM_INPUT first; the WndProc also handles arrivals during GLFW's pump. */
   public static void waitEvents() {
      requireOwner();
      try {
         if ((int)waitMessages.invokeExact(0, MemorySegment.NULL, 10, 0x04ff, 4) == -1) throw error("MsgWaitForMultipleObjectsEx");
         drainBuffer();
         finishBatch();
         GLFW.glfwPollEvents();
         if (callbackFailure != null) throw new IllegalStateException("Raw mouse window callback failed", callbackFailure);
      } catch (Throwable e) { throw new IllegalStateException("Buffered raw mouse event pump failed", e); }
   }

   private static long windowProc(long window, int message, long wParam, long lParam) {
      try {
         if (message == WM_INPUT && active) {
            // This message was already removed from the queue: GetRawInputBuffer cannot read its HRAWINPUT.
            beginBatch();
            size.set(I, 0, BUFFER_SIZE);
            int bytes = (int)readData.invokeExact(lParam, 0x10000003, single, size, HEADER_SIZE);
            if (bytes == -1) throw error("GetRawInputData");
            RawMousePackets.dispatch(single, bytes, 1, sink);
            messagePackets++;
            drainBuffer();
            finishBatch();
            return (long)defWindowProc.invokeExact(window, message, wParam, lParam);
         }
         if (message == 0x007e) refreshDesktop(); // WM_DISPLAYCHANGE
         return (long)callWindowProc.invokeExact(originalProc, window, message, wParam, lParam);
      } catch (Throwable e) {
         // Exceptions must not cross an FFM upcall. The owner loop fails explicitly after native dispatch returns.
         callbackFailure = e;
         try { return (long)defWindowProc.invokeExact(window, message, wParam, lParam); }
         catch (Throwable ignored) { return 0L; }
      }
   }

   private static void drainBuffer() throws Throwable {
      // Finite work per pump keeps keyboard, focus and native window messages serviceable under a continuous stream.
      for (int batch = 0; batch < 16; batch++) {
         size.set(I, 0, BUFFER_SIZE);
         int count = (int)readBuffer.invokeExact(buffer, size, HEADER_SIZE);
         if (count == -1) throw error("GetRawInputBuffer");
         if (count == 0) return;
         if (!batchOpen) beginBatch();
         RawMousePackets.dispatch(buffer, BUFFER_SIZE, count, sink);
         bufferedPackets += count;
         largestBatch = Math.max(largestBatch, count);
         if (!reportedBuffer) {
            reportedBuffer = true;
            System.out.println("[pzopt-raw] GetRawInputBuffer returned " + count + " native records");
         }
      }
   }

   private static void beginBatch() throws Throwable {
      batchOpen = true;
      moved = false;
      swapButtons = (int)getSystemMetrics.invokeExact(SM_SWAPBUTTON) != 0;
      boolean accelerate = Config.MOUSE_ACCELERATION;
      int sensitivity = Config.MOUSE_SENSITIVITY_TWENTIETHS;
      int onset = Config.MOUSE_ACCELERATION_ONSET_CPS;
      int slope = Config.MOUSE_ACCELERATION_SLOPE_PCT_PER_KCPS;
      int cap = Config.MOUSE_ACCELERATION_CAP_PCT;
      batchOnsetCps = onset;
      batchSlopePctPerKcps = slope;
      batchCapPct = cap;
      if (motionSettings.changed(sensitivity, accelerate, onset, slope, cap)) resetMotion();
      batchAcceleration = accelerate;
      batchMovementCounts = 0;
      if (accelerate) batchGain = accelerationEstimator.begin(System.nanoTime(), true);
      else batchGain = 1.0;
      long foreground = (long)getForegroundWindow.invokeExact();
      accepting = foreground != 0 && (int)getWindowThread.invokeExact(foreground, pid) == ownerId;
      if (!accepting) { positionValid = false; held = 0; resetMotion(); return; }
      targetWindow = Core.isImGui() ? ImGuiInput.rawMouseWindow() : Display.getWindow();
      targetHwnd = GLFWNativeWin32.glfwGetWin32Window(targetWindow);
      if (!positionValid) {
         if ((int)getCursorPos.invokeExact(point) == 0) throw error("GetCursorPos");
         cursorX = point.get(I, 0); cursorY = point.get(I, 4);
         resetMotion();
         positionValid = true;
      }
      if ((int)getClipCursor.invokeExact(clip) == 0) throw error("GetClipCursor");
      left = clip.get(I, 0); top = clip.get(I, 4); right = clip.get(I, 8); bottom = clip.get(I, 12);
      if ((int)getClientRect.invokeExact(targetHwnd, client) == 0) throw error("GetClientRect");
      point.set(I, 0, 0); point.set(I, 4, 0);
      if ((int)clientToScreen.invokeExact(targetHwnd, point) == 0) throw error("ClientToScreen");
      clientLeft = point.get(I, 0); clientTop = point.get(I, 4);
      clientRight = clientLeft + client.get(I, 8); clientBottom = clientTop + client.get(I, 12);
      mainLeft = clientLeft; mainTop = clientTop;
      if (targetHwnd != hwnd) {
         point.set(I, 0, 0); point.set(I, 4, 0);
         if ((int)clientToScreen.invokeExact(hwnd, point) == 0) throw error("ClientToScreen(main)");
         mainLeft = point.get(I, 0); mainTop = point.get(I, 4);
      }
      scaleX = Display.getFramebufferScaleX(); scaleY = Display.getFramebufferScaleY();
   }

   private static void mouse(int flags, int buttons, int buttonData, int x, int y, long handle) {
      if (handle == 0) zeroDevicePackets++; else nonzeroDevicePackets++;
      if (!accepting) return;
      buttons = logicalButtonFlags(buttons, swapButtons && handle != 0);
      try {
         long nextX, nextY;
         if ((flags & 1) != 0) {
            boolean virtual = (flags & 2) != 0;
            cursorX = (virtual ? desktopLeft : 0) + (int)((long)x * (virtual ? desktopWidth : primaryWidth) / 65536L);
            cursorY = (virtual ? desktopTop : 0) + (int)((long)y * (virtual ? desktopHeight : primaryHeight) / 65536L);
            resetMotion();
         } else {
            int sensitivity = Config.MOUSE_SENSITIVITY_TWENTIETHS;
            if (batchAcceleration) {
               batchMovementCounts += Math.hypot((double)x, (double)y);
               nextX = (long)cursorX + motionX.apply(x, sensitivity, batchGain);
               nextY = (long)cursorY + motionY.apply(y, sensitivity, batchGain);
            } else {
               nextX = (long)cursorX + motionX.apply(x, sensitivity);
               nextY = (long)cursorY + motionY.apply(y, sensitivity);
            }
            cursorX = (int)Math.clamp(nextX, left, (long)right - 1);
            cursorY = (int)Math.clamp(nextY, top, (long)bottom - 1);
            if (cursorX != nextX) motionX.reset();
            if (cursorY != nextY) motionY.reset();
         }
         int clippedX = Math.clamp(cursorX, left, right - 1);
         int clippedY = Math.clamp(cursorY, top, bottom - 1);
         if (clippedX != cursorX) motionX.reset();
         if (clippedY != cursorY) motionY.reset();
         cursorX = clippedX;
         cursorY = clippedY;
         inClient = cursorX >= clientLeft && cursorX < clientRight && cursorY >= clientTop && cursorY < clientBottom;
         if (!inClient && held == 0 && (buttons & 0x2aa) == 0) { positionValid = false; resetMotion(); return; }
         int fx = (int)((cursorX - mainLeft) * scaleX), fy = (int)((cursorY - mainTop) * scaleY);
         SubframeInput.offerMove(fx, fy);
         moved = true;
         for (int button = 0; button < 5; button++) {
            int bit = 1 << button;
            if ((buttons & (1 << (button * 2))) != 0 && inClient) {
               held |= bit;
               SubframeInput.offerButtonAt(button, true, fx, fy);
               if (Core.isImGui()) ImGuiInput.onRawMouseButton(targetWindow, button, true, fx, fy);
            }
            if ((buttons & (2 << (button * 2))) != 0) {
               held &= ~bit;
               SubframeInput.offerButtonAt(button, false, fx, fy);
               if (Core.isImGui()) ImGuiInput.onRawMouseButton(targetWindow, button, false, fx, fy);
            }
         }
         if ((buttons & 0x400) != 0) {
            double wheel = buttonData / 120.0;
            SubframeInput.offerWheelAt(wheel, fx, fy);
            if (Core.isImGui()) ImGuiInput.onRawScroll(targetWindow, 0, wheel, fx, fy);
         }
         if ((buttons & 0x800) != 0 && Core.isImGui()) ImGuiInput.onRawScroll(targetWindow, -buttonData / 120.0, 0, fx, fy);
      } catch (Throwable e) { throw new IllegalStateException("Unable to publish raw mouse packet", e); }
   }

   private static void finishBatch() throws Throwable {
      if (!batchOpen) return;
      batchOpen = false;
      if (batchAcceleration) {
         long now = System.nanoTime();
         accelerationEstimator.end(now, batchMovementCounts, accepting, batchOnsetCps, batchSlopePctPerKcps, batchCapPct);
      }
      // Use the same raw cursor for the visible pointer and every buffered action, not the OS-accelerated endpoint.
      if (accepting && moved) {
         if ((int)setCursorPos.invokeExact(cursorX, cursorY) == 0) throw error("SetCursorPos");
      }
   }

   private static void refreshDesktop() throws Throwable {
      desktopLeft = (int)getSystemMetrics.invokeExact(76); desktopTop = (int)getSystemMetrics.invokeExact(77);
      desktopWidth = (int)getSystemMetrics.invokeExact(78); desktopHeight = (int)getSystemMetrics.invokeExact(79);
      primaryWidth = (int)getSystemMetrics.invokeExact(0); primaryHeight = (int)getSystemMetrics.invokeExact(1);
   }

   public static void close() {
      requireOwner();
      active = false;
      if (arena == null) return;
      try {
         if (registered) {
            device.set(I, 4, 1); // RIDEV_REMOVE requires a null HWND.
            device.set(J, 8, 0L);
            if ((int)register.invokeExact(device, 1, 16) == 0) throw error("RegisterRawInputDevices(remove)");
            registered = false;
         }
         if (originalProc != 0 && (int)isWindow.invokeExact(hwnd) != 0) {
            if ((long)setWindowProc.invokeExact(hwnd, -4, originalProc) == 0L) throw error("SetWindowLongPtrW(restore)");
         }
         originalProc = 0;
         System.out.println("[pzopt-raw] stopped buffered=" + bufferedPackets + " message=" + messagePackets
            + " largest_batch=" + largestBatch + " nonzero_device=" + nonzeroDevicePackets + " zero_device=" + zeroDevicePackets);
         arena.close();
         arena = null;
      } catch (Throwable e) {
         // Keep the arena alive if native code may still hold the upcall address.
         throw new IllegalStateException("Unable to remove buffered raw mouse collector", e);
      }
   }

   private static MethodHandle bind(Linker linker, SymbolLookup symbols, String name,
         java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
      return linker.downcallHandle(symbols.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
   }
   private static IllegalStateException error(String operation) throws Throwable {
      return new IllegalStateException(operation + " failed, Win32 error=" + (int)getLastError.invokeExact());
   }
   private static void requireOwner() {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Raw mouse requires the GLFW owner thread");
   }
}
