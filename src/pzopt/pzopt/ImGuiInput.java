package pzopt;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiPlatformIO;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.callback.ImPlatformFuncViewport;
import imgui.callback.ImPlatformFuncViewportFloat;
import imgui.callback.ImPlatformFuncViewportImVec2;
import imgui.callback.ImPlatformFuncViewportString;
import imgui.callback.ImPlatformFuncViewportSuppBoolean;
import imgui.callback.ImPlatformFuncViewportSuppFloat;
import imgui.callback.ImPlatformFuncViewportSuppImVec2;
import imgui.callback.ImStrConsumer;
import imgui.callback.ImStrSupplier;
import imgui.flag.ImGuiBackendFlags;
import imgui.glfw.ImGuiImplGlfw;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import zombie.core.Core;

/**
 * GLFW callbacks only enqueue primitive records. ImGui frames run on the game thread;
 * owner-side platform work executes while that thread waits in InputThread.call.
 * Platform rendering is invoked synchronously after endFrame, so the game cannot begin
 * the next frame while the renderer uses viewport state. Never hold a cross-thread lock
 * across a game frame: game-side GL requests would deadlock the renderer behind it.
 */
public final class ImGuiInput {
   private static final int CAPACITY = 8192, MASK = CAPACITY - 1;
   private static final int BUTTON = 1, SCROLL = 2, KEY = 3, CHAR = 4, FOCUS = 5, ENTER = 6,
      VP_BUTTON = 7, VP_SCROLL = 8, VP_KEY = 9, VP_CHAR = 10, VP_FOCUS = 11, VP_CLOSE = 12, VP_MOVE = 13, VP_RESIZE = 14;
   private static volatile boolean frameOpen;
   private static Thread frameThread;
   private static final int[] type = new int[CAPACITY], a = new int[CAPACITY], b = new int[CAPACITY], c = new int[CAPACITY], d = new int[CAPACITY];
   private static final long[] window = new long[CAPACITY];
   private static final double[] x = new double[CAPACITY], y = new double[CAPACITY], wheelX = new double[CAPACITY], wheelY = new double[CAPACITY];
   private static volatile int write, read;
   private static volatile int lostSemanticSequence, seenLostSemanticSequence;
   private static final int[] width = new int[1], height = new int[1], fbWidth = new int[1], fbHeight = new int[1];
   private static final int[] windowX = new int[1], windowY = new int[1];
   private static final double[] cursorX = new double[1], cursorY = new double[1];
   private static final int[] hoveredViewportId = new int[1];
   private static final boolean[] buttons = new boolean[5], keys = new boolean[512];
   private static final boolean[] changedKeys = new boolean[512];
   private static boolean actionPosition, cancelled;
   private static float actionX, actionY;
   private static long actionWindow;
   private static java.lang.invoke.MethodHandle updateMonitors, updateMouseCursor, updateGamepads;
   private static java.lang.invoke.VarHandle monitorsDirty;
   private static volatile boolean initialized, focused = true, hasPosition;
   private static long mainWindow;
   private static ImGuiImplGlfw glfw;
   private static double lastTime;

   private static final java.util.ArrayList<ViewportData> ownedWindows = new java.util.ArrayList<>();
   private static boolean mainFocused;
   private ImGuiInput() { }
   public static void beginFrame() {
      if (frameOpen) throw new IllegalStateException("Nested ImGui frame");
      frameThread = Thread.currentThread();
      frameOpen = true;
   }
   public static void endFrame() { frameOpen = false; }

   /** Owner-thread init: the backend false flag suppresses mouse/key/char/focus callback chaining. */
   public static void init(long handle, ImGuiImplGlfw backend) {
      requireOwner();
      if (initialized) throw new IllegalStateException("ImGui input bridge already initialized");
      mainWindow = handle;
      glfw = backend;
      if (!glfw.init(handle, false)) throw new IllegalStateException("Unable to initialize ImGui GLFW backend");
      // Game-viewport content owns its drags; only the title bar may move the containing ImGui window.
      if (Core.isUseGameViewport()) ImGui.getIO().setConfigWindowsMoveFromTitleBarOnly(true);
      try {
         var lookup = java.lang.invoke.MethodHandles.privateLookupIn(ImGuiImplGlfw.class, java.lang.invoke.MethodHandles.lookup());
         var type = java.lang.invoke.MethodType.methodType(void.class);
         updateMonitors = lookup.findVirtual(ImGuiImplGlfw.class, "updateMonitors", type);
         updateMouseCursor = lookup.findVirtual(ImGuiImplGlfw.class, "updateMouseCursor", type);
         updateGamepads = lookup.findVirtual(ImGuiImplGlfw.class, "updateGamepads", type);
         monitorsDirty = lookup.findVarHandle(ImGuiImplGlfw.class, "wantUpdateMonitors", boolean.class);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException("Unsupported ImGui GLFW backend", e);
      }
      ImGui.getIO().setGetClipboardTextFn(new ImStrSupplier() {
         @Override public String get() {
            return InputThread.call(() -> { String value = GLFW.glfwGetClipboardString(mainWindow); return value == null ? "" : value; });
         }
      });
      ImGui.getIO().setSetClipboardTextFn(new ImStrConsumer() {
         @Override public void accept(String text) { InputThread.invoke(() -> GLFW.glfwSetClipboardString(mainWindow, text)); }
      });
      if (Core.isUseViewports()) installViewportPlatform();
      initialized = true;
   }

   // Display main-window callback forwarding. All are primitive stores only.
   public static void onMouseButton(int button, int action, int mods) { offerButton(BUTTON, mainWindow, button, action, mods); }
   public static void onScroll(double horizontal, double vertical) { offerScroll(SCROLL, mainWindow, horizontal, vertical); }
   /** Owner-thread raw mouse bridge; positions are in the main window framebuffer. */
   public static void onRawMouseButton(long glfwWindow, int button, boolean down, int framebufferX, int framebufferY) {
      if (!initialized) return;
      requireOwner();
      double x = framebufferX / org.lwjglx.opengl.Display.getFramebufferScaleX();
      double y = framebufferY / org.lwjglx.opengl.Display.getFramebufferScaleY();
      if (Core.isUseViewports()) { x += org.lwjglx.opengl.Display.getX(); y += org.lwjglx.opengl.Display.getY(); }
      offer(glfwWindow == mainWindow ? BUTTON : VP_BUTTON, glfwWindow, button, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0, 0, x, y);
   }
   /** Owner-thread raw mouse bridge; positions are in the main window framebuffer. */
   public static void onRawScroll(long glfwWindow, double horizontal, double vertical, int framebufferX, int framebufferY) {
      if (!initialized) return;
      requireOwner();
      double x = framebufferX / org.lwjglx.opengl.Display.getFramebufferScaleX();
      double y = framebufferY / org.lwjglx.opengl.Display.getFramebufferScaleY();
      if (Core.isUseViewports()) { x += org.lwjglx.opengl.Display.getX(); y += org.lwjglx.opengl.Display.getY(); }
      offer(glfwWindow == mainWindow ? SCROLL : VP_SCROLL, glfwWindow, 0, 0, 0, 0, x, y, horizontal, vertical);
   }

   /** Owner-thread target selection for one raw mouse batch. */
   public static long rawMouseWindow() {
      if (!initialized) return org.lwjglx.opengl.Display.getWindow();
      requireOwner();
      if (GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE) return mainWindow;
      for (int i = 0; i < ownedWindows.size(); i++) {
         long handle = ownedWindows.get(i).window;
         if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE) return handle;
      }
      if (GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) return mainWindow;
      for (int i = 0; i < ownedWindows.size(); i++) {
         long handle = ownedWindows.get(i).window;
         if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) return handle;
      }
      return mainWindow;
   }
   public static void onKey(int key, int scan, int action, int mods) { offer(KEY, 0L, key, scan, action, mods, 0, 0); }
   public static void onChar(int codepoint) { offer(CHAR, 0L, codepoint, 0, 0, 0, 0, 0); }
   public static void onFocus(boolean value) {
      mainFocused = value;
      publishFocus();
      offer(FOCUS, 0L, value ? 1 : 0, 0, 0, 0, 0, 0);
   }
   public static void onCursorEnter(boolean value) { offer(ENTER, 0L, value ? 1 : 0, 0, 0, 0, 0, 0); }

   private static void offerButton(int type, long window, int button, int action, int mods) {
      offerPoint(type, window, button, action, mods, 0, 0.0, 0.0);
   }
   private static void offerScroll(int type, long window, double horizontal, double vertical) {
      offerPoint(type, window, 0, 0, 0, 0, horizontal, vertical);
   }
   private static void offerPoint(int type, long window, int p1, int p2, int p3, int p4, double horizontal, double vertical) {
      long point = WindowInput.messagePosition();
      double px = (int)(point >> 32) / org.lwjglx.opengl.Display.getFramebufferScaleX();
      double py = (int)point / org.lwjglx.opengl.Display.getFramebufferScaleY();
      if (Core.isUseViewports()) {
         px += org.lwjglx.opengl.Display.getX();
         py += org.lwjglx.opengl.Display.getY();
      }
      offer(type, window, p1, p2, p3, p4, px, py, horizontal, vertical);
   }

   private static void publishFocus() {
      if (!InputThread.active()) return;
      boolean anyFocused = mainFocused;
      for (int i = 0; i < ownedWindows.size(); i++) anyFocused |= ownedWindows.get(i).focused;
      if (Core.isUseViewports()) {
         SubframeInput.focus(anyFocused);
         RawMouse.focusChanged(anyFocused);
      }
      // Cancel before deferred button/wheel actions, including loss+regain within one frame.
      if (!anyFocused) lostSemanticSequence++;
   }

   private static void offer(int eventType, long target, int p1, int p2, int p3, int p4, double px, double py) {
      offer(eventType, target, p1, p2, p3, p4, px, py, 0.0, 0.0);
   }
   private static void offer(int eventType, long target, int p1, int p2, int p3, int p4, double px, double py, double horizontal, double vertical) {
      if (lostSemanticSequence != seenLostSemanticSequence) return;
      int at = write, next = (at + 1) & MASK;
      if (next == read) {
         lostSemanticSequence++;
         return;
      }
      type[at] = eventType; window[at] = target; a[at] = p1; b[at] = p2; c[at] = p3; d[at] = p4;
      x[at] = px; y[at] = py; wheelX[at] = horizontal; wheelY[at] = vertical;
      write = next;
   }

   /** Game-thread only; process callback records, then query dimensions/cursor on owner. */
   public static void newFrame() {
      if (!initialized) return;
      if (!frameOpen || frameThread != Thread.currentThread()) throw new IllegalStateException("ImGuiInput.newFrame requires beginFrame");
      java.util.Arrays.fill(changedKeys, false);
      actionPosition = false;
      cancelled = false;
      drain();
      InputThread.call(() -> {
         updateNativeBackend();
         GLFW.glfwGetWindowSize(mainWindow, width, height);
         GLFW.glfwGetFramebufferSize(mainWindow, fbWidth, fbHeight);
         GLFW.glfwGetWindowPos(mainWindow, windowX, windowY);
         GLFW.glfwGetCursorPos(mainWindow, cursorX, cursorY);
         cursorX[0] += windowX[0]; cursorY[0] += windowY[0];
         hoveredViewportId[0] = 0;
         boolean anyFocused = false;
         ImGuiPlatformIO platform = ImGui.getPlatformIO();
         for (int i = 0; i < platform.getViewportsSize(); i++) {
            ImGuiViewport viewport = platform.getViewports(i);
            long handle = viewport.getPlatformHandle();
            if (handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) anyFocused = true;
            if (handle != 0L && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE) {
               GLFW.glfwGetCursorPos(handle, cursorX, cursorY);
               cursorX[0] += Core.isUseViewports() ? viewport.getPosX() : windowX[0];
               cursorY[0] += Core.isUseViewports() ? viewport.getPosY() : windowY[0];
               hoveredViewportId[0] = viewport.getID();
            }
         }
         hasPosition = hoveredViewportId[0] != 0;
         focused = anyFocused && !cancelled;
         return null;
      });
      ImGuiIO io = ImGui.getIO();
      io.addFocusEvent(focused);
      io.setDisplaySize(width[0], height[0]);
      if (width[0] > 0 && height[0] > 0) io.setDisplayFramebufferScale((float)fbWidth[0] / width[0], (float)fbHeight[0] / height[0]);
      double now = System.nanoTime() * 1.0e-9;
      io.setDeltaTime(lastTime > 0.0 ? (float)(now - lastTime) : 1.0f / 60.0f); lastTime = now;
      for (int i = 0; i < buttons.length; i++) io.setMouseDown(i, buttons[i]);
      float mouseX = (float)(Core.isUseViewports() ? cursorX[0] : cursorX[0] - windowX[0]);
      float mouseY = (float)(Core.isUseViewports() ? cursorY[0] : cursorY[0] - windowY[0]);
      if (actionPosition) {
         mouseX = actionX;
         mouseY = actionY;
         hasPosition = true;
         ImGuiPlatformIO platform = ImGui.getPlatformIO();
         for (int i = 0; i < platform.getViewportsSize(); i++) {
            ImGuiViewport viewport = platform.getViewports(i);
            if (viewport.getPlatformHandle() == actionWindow) hoveredViewportId[0] = viewport.getID();
         }
      }
      io.setMousePos(hasPosition && focused ? mouseX : -Float.MAX_VALUE, hasPosition && focused ? mouseY : -Float.MAX_VALUE);
      if (!focused) {
         resetInput(io);
         read = write;
      }
      io.setMouseHoveredViewport(hoveredViewportId[0]);
      io.setKeyCtrl(keys[GLFW.GLFW_KEY_LEFT_CONTROL] || keys[GLFW.GLFW_KEY_RIGHT_CONTROL]);
      io.setKeyShift(keys[GLFW.GLFW_KEY_LEFT_SHIFT] || keys[GLFW.GLFW_KEY_RIGHT_SHIFT]);
      io.setKeyAlt(keys[GLFW.GLFW_KEY_LEFT_ALT] || keys[GLFW.GLFW_KEY_RIGHT_ALT]);
      io.setKeySuper(keys[GLFW.GLFW_KEY_LEFT_SUPER] || keys[GLFW.GLFW_KEY_RIGHT_SUPER]);
   }

   /** Game thread after ImGui.render; platform GLFW callbacks marshal to owner. */
   public static void updatePlatformWindows() {
      if (!initialized || !Core.isUseViewports()) return;
      if (!frameOpen || frameThread != Thread.currentThread()) throw new IllegalStateException("platform update outside ImGui frame");
      ImGui.updatePlatformWindows();
   }

   /** Renderer thread only, with renderer context current. */
   public static void renderPlatformWindows() {
      if (!initialized || !Core.isUseViewports()) return;
      if (frameOpen) throw new IllegalStateException("Platform rendering before game ImGui frame ended");
      ImGui.renderPlatformWindowsDefault();
   }

   public static boolean focused() { return focused; }
   public static boolean hasPosition() { return hasPosition; }
   /** Parent MouseState can use DebugContext's game-viewport transform with global ImGui coordinates. */
   public static float cursorScreenX() { return (float)cursorX[0]; }
   public static float cursorScreenY() { return (float)cursorY[0]; }
   /** Convert framebuffer pixels to ImGui coordinates: client-local unless multi-viewports are enabled. */
   public static float eventScreenX(int framebufferX) { return (Core.isUseViewports() ? windowX[0] : 0) + (fbWidth[0] > 0 ? framebufferX * ((float)width[0] / fbWidth[0]) : framebufferX); }
   public static float eventScreenY(int framebufferY) { return (Core.isUseViewports() ? windowY[0] : 0) + (fbHeight[0] > 0 ? framebufferY * ((float)height[0] / fbHeight[0]) : framebufferY); }

   public static void dispose() {
      if (!initialized) return;
      InputThread.invoke(() -> {
         glfw.dispose(); glfw = null; mainWindow = 0L; initialized = false;
         frameOpen = false;
      });
   }

   private static void drain() {
      ImGuiIO io = ImGui.getIO();
      if (lostSemanticSequence != seenLostSemanticSequence) {
         int lost = lostSemanticSequence;
         cancelled = true;
         resetInput(io);
         io.setMouseWheel(0); io.setMouseWheelH(0);
         read = write;
         seenLostSemanticSequence = lost;
         return;
      }
      int at = read, end = write;
      while (at != end) {
         int eventType = type[at], p1 = a[at], p2 = b[at], p3 = c[at], p4 = d[at];
         long target = window[at]; double px = x[at], py = y[at];
         if ((eventType == KEY || eventType == VP_KEY) && p1 >= 0 && p1 < changedKeys.length) {
            if (changedKeys[p1]) break;
            changedKeys[p1] = true;
         }
         switch (eventType) {
            case BUTTON -> setButton(p1, p2);
            case SCROLL -> { io.setMouseWheelH(io.getMouseWheelH() + (float)wheelX[at]); io.setMouseWheel(io.getMouseWheel() + (float)wheelY[at]); }
            case KEY -> keyEvent(mainWindow, p1, p2, p3, p4);
            case CHAR -> io.addInputCharacter(p1);
            case FOCUS -> { focused = p1 != 0; io.addFocusEvent(focused); if (!focused) resetInput(io); }
            case ENTER -> { if (p1 == 0) hasPosition = false; }
            case VP_BUTTON -> setButton(p1, p2);
            case VP_SCROLL -> { io.setMouseWheelH(io.getMouseWheelH() + (float)wheelX[at]); io.setMouseWheel(io.getMouseWheel() + (float)wheelY[at]); }
            case VP_KEY -> keyEvent(target, p1, p2, p3, p4);
            case VP_CHAR -> io.addInputCharacter(p1);
            case VP_FOCUS -> { focused = p1 != 0; io.addFocusEvent(focused); if (!focused) resetInput(io); }
            case VP_CLOSE -> requestViewport(target, VP_CLOSE);
            case VP_MOVE -> requestViewport(target, VP_MOVE);
            case VP_RESIZE -> requestViewport(target, VP_RESIZE);
            default -> throw new IllegalStateException("Unknown ImGui event " + eventType);
         }
         at = (at + 1) & MASK;
         // This binding exposes legacy frame-state mouse IO. Advance one pointer action
         // per frame so each edge or wheel delta uses its recorded point; a release follows
         // its press on the next frame.
         if (eventType == BUTTON || eventType == SCROLL || eventType == VP_BUTTON || eventType == VP_SCROLL) {
            actionPosition = true;
            actionX = (float)px; actionY = (float)py; actionWindow = target;
            break;
         }
      }
      read = at;
   }

   private static void requestViewport(long handle, int request) {
      ImGuiPlatformIO platform = ImGui.getPlatformIO();
      for (int i = 1; i < platform.getViewportsSize(); i++) {
         ImGuiViewport viewport = platform.getViewports(i);
         if (viewport.getPlatformHandle() != handle) continue;
         if (request == VP_CLOSE) viewport.setPlatformRequestClose(true);
         else if (request == VP_MOVE) viewport.setPlatformRequestMove(true);
         else viewport.setPlatformRequestResize(true);
         return;
      }
   }
   private static void setButton(int button, int action) {
      if (button >= 0 && button < buttons.length) buttons[button] = action != GLFW.GLFW_RELEASE;
   }

   // The game's public backend newFrame explicitly invokes RenderThread. Reuse its named
   // platform helpers on the owner while the game thread waits for their completion.
   private static void updateNativeBackend() {
      try {
         if ((boolean)monitorsDirty.get(glfw)) updateMonitors.invokeExact(glfw);
         updateMouseCursor.invokeExact(glfw);
         WindowInput.syncCursorMode();
         updateGamepads.invokeExact(glfw);
      } catch (Throwable e) {
         throw new IllegalStateException("Updating ImGui owner-side platform state", e);
      }
   }

   private static void keyEvent(long target, int key, int scan, int action, int mods) {
      if (key >= 0 && key < keys.length) keys[key] = action != GLFW.GLFW_RELEASE;
      glfw.keyCallback(target, key, scan, action, mods);
   }
   private static void resetInput(ImGuiIO io) {
      io.clearInputKeys(); for (int i = 0; i < keys.length; i++) keys[i] = false;
      for (int i = 0; i < buttons.length; i++) { buttons[i] = false; io.setMouseDown(i, false); }
   }
   private static void requireOwner() { if (!InputThread.isOwnerThread()) throw new IllegalStateException("ImGui GLFW init must run on window owner"); }

   private static void installViewportPlatform() {
      ImGui.getIO().addBackendFlags(ImGuiBackendFlags.PlatformHasViewports);
      ImGui.getIO().addBackendFlags(ImGuiBackendFlags.HasMouseHoveredViewport);
      ImGuiPlatformIO p = ImGui.getPlatformIO();
      p.setPlatformCreateWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { owner(() -> createWindow(v)); } });
      p.setPlatformDestroyWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { owner(() -> destroyWindow(v)); } });
      p.setPlatformRenderWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { GLFW.glfwMakeContextCurrent(handle(v)); } });
      p.setPlatformSwapBuffers(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { GLFW.glfwSwapBuffers(handle(v)); } });
      p.setPlatformShowWindow(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { owner(() -> GLFW.glfwShowWindow(handle(v))); } });
      p.setPlatformGetWindowPos(new ImPlatformFuncViewportSuppImVec2() {
         final int[] px = new int[1], py = new int[1];
         @Override public void get(ImGuiViewport v, ImVec2 out) { owner(() -> { GLFW.glfwGetWindowPos(handle(v), px, py); out.x = px[0]; out.y = py[0]; }); }
      });
      p.setPlatformSetWindowPos(new ImPlatformFuncViewportImVec2() { @Override public void accept(ImGuiViewport v, ImVec2 pos) { owner(() -> GLFW.glfwSetWindowPos(handle(v), (int)pos.x, (int)pos.y)); } });
      p.setPlatformGetWindowSize(new ImPlatformFuncViewportSuppImVec2() {
         final int[] w = new int[1], h = new int[1];
         @Override public void get(ImGuiViewport v, ImVec2 out) { owner(() -> { GLFW.glfwGetWindowSize(handle(v), w, h); out.x = w[0]; out.y = h[0]; }); }
      });
      p.setPlatformSetWindowSize(new ImPlatformFuncViewportImVec2() { @Override public void accept(ImGuiViewport v, ImVec2 size) { owner(() -> GLFW.glfwSetWindowSize(handle(v), (int)size.x, (int)size.y)); } });
      p.setPlatformSetWindowTitle(new ImPlatformFuncViewportString() { @Override public void accept(ImGuiViewport v, String title) { owner(() -> GLFW.glfwSetWindowTitle(handle(v), title)); } });
      p.setPlatformSetWindowFocus(new ImPlatformFuncViewport() { @Override public void accept(ImGuiViewport v) { owner(() -> GLFW.glfwFocusWindow(handle(v))); } });
      p.setPlatformGetWindowFocus(new ImPlatformFuncViewportSuppBoolean() { @Override public boolean get(ImGuiViewport v) { return InputThread.call(() -> GLFW.glfwGetWindowAttrib(handle(v), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE); } });
      p.setPlatformGetWindowMinimized(new ImPlatformFuncViewportSuppBoolean() { @Override public boolean get(ImGuiViewport v) { return InputThread.call(() -> GLFW.glfwGetWindowAttrib(handle(v), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE); } });
      p.setPlatformSetWindowAlpha(new ImPlatformFuncViewportFloat() { @Override public void accept(ImGuiViewport v, float alpha) { owner(() -> GLFW.glfwSetWindowOpacity(handle(v), alpha)); } });
      p.setPlatformGetWindowDpiScale(new ImPlatformFuncViewportSuppFloat() {
         final float[] sx = new float[1], sy = new float[1];
         @Override public float get(ImGuiViewport v) { return InputThread.call(() -> { GLFW.glfwGetWindowContentScale(handle(v), sx, sy); return sx[0]; }); }
      });
   }

   private static void createWindow(ImGuiViewport v) {
      GLFW.glfwGetWindowSize(mainWindow, width, height);
      int contextMajor = GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_CONTEXT_VERSION_MAJOR);
      int contextMinor = GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_CONTEXT_VERSION_MINOR);
      int profile = GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_OPENGL_PROFILE);
      int forwardCompatible = GLFW.glfwGetWindowAttrib(mainWindow, GLFW.GLFW_OPENGL_FORWARD_COMPAT);
      GLFW.glfwDefaultWindowHints();
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, contextMajor);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, contextMinor);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, profile);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, forwardCompatible);
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE); GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
      GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, v.hasFlags(8) ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
      long w = GLFW.glfwCreateWindow(Math.max(1, (int)v.getSizeX()), Math.max(1, (int)v.getSizeY()), "ImGui viewport", 0L, mainWindow);
      if (w == 0L) throw new IllegalStateException("Unable to create ImGui platform viewport");
      ViewportData data = new ViewportData(w);
      ownedWindows.add(data);
      v.setPlatformUserData(data); v.setPlatformHandle(w);
      if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WIN32) v.setPlatformHandleRaw(GLFWNativeWin32.glfwGetWin32Window(w));
      GLFW.glfwSetWindowPos(w, (int)v.getPosX(), (int)v.getPosY());
      GLFW.glfwSetCursorPosCallback(w, (id, px, py) -> {
         if (RawMouse.active()) return;
         if (Core.isUseGameViewport()) {
            long pos = WindowInput.messagePosition();
            SubframeInput.offerMove((int)(pos >> 32), (int)pos);
         }
      });
      GLFW.glfwSetMouseButtonCallback(w, (id, button, action, mods) -> {
         if (RawMouse.active()) return;
         offerButton(VP_BUTTON, id, button, action, mods);
         if (Core.isUseGameViewport()) {
            long pos = WindowInput.messagePosition();
            SubframeInput.offerButtonAt(button, action == GLFW.GLFW_PRESS, (int)(pos >> 32), (int)pos);
         }
      });
      GLFW.glfwSetScrollCallback(w, (id, sx, sy) -> {
         if (RawMouse.active()) return;
         offerScroll(VP_SCROLL, id, sx, sy);
         if (Core.isUseGameViewport()) {
            long pos = WindowInput.messagePosition();
            SubframeInput.offerWheelAt(sy, (int)(pos >> 32), (int)pos);
         }
      });
      GLFW.glfwSetKeyCallback(w, (id, key, scan, action, mods) -> {
         offer(VP_KEY, id, key, scan, action, mods, 0, 0);
         if (Core.isUseGameViewport()) zombie.input.GameKeyboard.pzoptKeyEvent(key, action);
      });
      GLFW.glfwSetCharCallback(w, (id, cp) -> {
         offer(VP_CHAR, id, cp, 0, 0, 0, 0, 0);
         if (Core.isUseGameViewport()) zombie.input.GameKeyboard.pzoptCharEvent((char)cp);
      });
      GLFW.glfwSetWindowFocusCallback(w, (id, value) -> {
         data.focused = value;
         publishFocus();
         if (!value) zombie.input.GameKeyboard.pzoptFocusLost();
         offer(VP_FOCUS, id, value ? 1 : 0, 0, 0, 0, 0, 0);
      });
      GLFW.glfwSetWindowCloseCallback(w, id -> offer(VP_CLOSE, id, 0, 0, 0, 0, 0, 0));
      GLFW.glfwSetWindowPosCallback(w, (id, px, py) -> offer(VP_MOVE, id, 0, 0, 0, 0, 0, 0));
      GLFW.glfwSetWindowSizeCallback(w, (id, ww, hh) -> offer(VP_RESIZE, id, 0, 0, 0, 0, 0, 0));
   }
   private static void destroyWindow(ImGuiViewport v) {
      long w = handle(v);
      if (w != 0L && v.getPlatformUserData() instanceof ViewportData data && data.owned) {
         org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(w); GLFW.glfwDestroyWindow(w);
         ownedWindows.remove(data);
         publishFocus();
      }
      v.setPlatformUserData(null); v.setPlatformHandle(0L); v.setPlatformHandleRaw(0L);
   }
   private static long handle(ImGuiViewport v) { Object data = v.getPlatformUserData(); return data instanceof ViewportData vd ? vd.window : v.getPlatformHandle(); }
   private static void owner(Runnable task) { InputThread.invoke(task); }
   private static final class ViewportData {
      final long window;
      final boolean owned = true;
      boolean focused;
      ViewportData(long window) { this.window = window; }
   }
}
