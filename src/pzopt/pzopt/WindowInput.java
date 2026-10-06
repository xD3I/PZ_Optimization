package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjglx.opengl.Display;

/** Win32 input helpers; all methods run on the HWND/GLFW owner thread. */
public final class WindowInput {
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
   private static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
   private static final Arena arena = Arena.global();
   private static MethodHandle getMessagePos, screenToClient, getClientRect, clientToScreen, clipCursor;
   private static final MemorySegment point = arena.allocate(8, 4);
   private static final MemorySegment rect = arena.allocate(16, 4);
   private static long clippedHwnd;
   private static int cursorMode = -1;
   private static boolean clipDirty = true;

   private WindowInput() { }

   /** Returns framebuffer-pixel coordinates, packed as x in high 32 bits and y in low 32 bits. */
   public static long messagePosition() {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Win32 input position requires GLFW owner");
      initialize();
      try {
         int packed = (int)getMessagePos.invokeExact();
         point.set(I, 0, (short)(packed & 0xffff));
         point.set(I, 4, (short)(packed >>> 16));
         long hwnd = GLFWNativeWin32.glfwGetWin32Window(Display.getWindow());
         if ((int)screenToClient.invokeExact(hwnd, point) == 0) throw new IllegalStateException("ScreenToClient failed");
         int x = point.get(I, 0), y = point.get(I, 4);
         double sx = Display.getFramebufferScaleX(), sy = Display.getFramebufferScaleY();
         return ((long)(int)(x * sx) << 32) | ((int)(y * sy) & 0xffffffffL);
      } catch (Throwable e) { throw new IllegalStateException("Unable to obtain Win32 message coordinates", e); }
   }

   /** Synchronize the main-window cache after an owner-side GLFW backend changes it directly. */
   static void syncCursorMode() {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Cursor mode synchronization requires GLFW owner");
      cursorMode = GLFW.glfwGetInputMode(Display.getWindow(), GLFW.GLFW_CURSOR);
   }

   public static void windowChanged() { clipDirty = true; }

   public static void prepare() {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Win32 input initialization requires GLFW owner");
      initialize();
   }

   /** Keep the real pointer absolute; lock by clipping rather than GLFW's recentered disabled cursor. */
   public static void updateCursor(int glfwMode, boolean lockToWindow) {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Cursor update requires GLFW owner");
      initialize();
      long window = Display.getWindow();
      int mode = glfwMode == GLFW.GLFW_CURSOR_DISABLED ? GLFW.GLFW_CURSOR_HIDDEN : glfwMode;
      if (cursorMode != mode) {
         GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, mode);
         cursorMode = mode;
      }
      try {
         if (!lockToWindow) {
            releaseCursor();
            return;
         }
         if (clippedHwnd != 0L && !clipDirty) return;
         long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
         if ((int)getClientRect.invokeExact(hwnd, rect) == 0) throw new IllegalStateException("GetClientRect failed");
         point.set(I, 0, rect.get(I, 0)); point.set(I, 4, rect.get(I, 4));
         if ((int)clientToScreen.invokeExact(hwnd, point) == 0) throw new IllegalStateException("ClientToScreen failed");
         int left = point.get(I, 0), top = point.get(I, 4);
         point.set(I, 0, rect.get(I, 8)); point.set(I, 4, rect.get(I, 12));
         if ((int)clientToScreen.invokeExact(hwnd, point) == 0) throw new IllegalStateException("ClientToScreen failed");
         rect.set(I, 0, left); rect.set(I, 4, top);
         rect.set(I, 8, point.get(I, 0)); rect.set(I, 12, point.get(I, 4));
         if ((int)clipCursor.invokeExact(rect) == 0) throw new IllegalStateException("ClipCursor failed");
         clippedHwnd = hwnd;
         clipDirty = false;
      } catch (Throwable e) { throw new IllegalStateException("Unable to update Win32 cursor clip", e); }
   }

   public static void releaseCursor() {
      if (!InputThread.isOwnerThread()) throw new IllegalStateException("Cursor release requires GLFW owner");
      initialize();
      try {
         if (clippedHwnd != 0L && (int)clipCursor.invokeExact(MemorySegment.NULL) == 0) {
            throw new IllegalStateException("ClipCursor release failed");
         }
         clippedHwnd = 0L;
         clipDirty = true;
      }
      catch (Throwable e) { throw new IllegalStateException("Unable to release Win32 cursor clip", e); }
   }

   private static void initialize() {
      if (getMessagePos != null) return;
      try {
         Linker linker = Linker.nativeLinker();
         SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", arena);
         getMessagePos = linker.downcallHandle(user32.find("GetMessagePos").orElseThrow(), FunctionDescriptor.of(I));
         screenToClient = linker.downcallHandle(user32.find("ScreenToClient").orElseThrow(), FunctionDescriptor.of(I, J, P));
         getClientRect = linker.downcallHandle(user32.find("GetClientRect").orElseThrow(), FunctionDescriptor.of(I, J, P));
         clientToScreen = linker.downcallHandle(user32.find("ClientToScreen").orElseThrow(), FunctionDescriptor.of(I, J, P));
         clipCursor = linker.downcallHandle(user32.find("ClipCursor").orElseThrow(), FunctionDescriptor.of(I, P));
      } catch (Throwable e) { throw new IllegalStateException("Unable to bind Win32 user32 input functions", e); }
   }
}
