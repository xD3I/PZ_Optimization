package pzopt;

import java.util.ArrayDeque;
import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;

/** Owns the GLFW event loop on the thread that initialized the window. */
public final class InputThread {
   private static final Object queueLock = new Object();
   private static final ArrayDeque<Call<?>> queue = new ArrayDeque<>();
   private static volatile Thread owner;
   private static volatile boolean enabled;
   private static volatile boolean stopping;
   private static volatile boolean started;
   private static volatile boolean sampleRequested = true;
   private static long nextMaintenance;

   private InputThread() { }

   /** Record ownership without reading Config before MainScreenState parses -cachedir. */
   public static void establishOwner() { owner = Thread.currentThread(); }

   /** Called on the original GLFW/window thread before the render context is transferred. */
   public static void initialize() {
      if (owner != null && !isOwnerThread()) throw new IllegalStateException("GLFW owner changed during initialization");
      establishOwner();
      stopping = false;
      started = false;
      enabled = Config.INPUT_THREAD && Overrides.enabled() && !zombie.network.GameServer.server
         && System.getProperty("os.name", "").startsWith("Win");
   }

   public static boolean active() { return enabled && started; }
   public static boolean shouldUse() { return enabled; }
   public static boolean isOwnerThread() { return Thread.currentThread() == owner; }

   /** Entered by the original main thread after GameWindow.InitGameThread. */
   public static void runOwnerLoop(Runnable startRenderer) {
      if (!enabled) { startRenderer.run(); return; }
      if (!isOwnerThread()) throw new IllegalStateException("GLFW owner loop entered from another thread");
      WindowInput.prepare();
      Display.seedInputState();
      owner.setName("pzopt-input");
      RawMouse.initialize();
      started = true;
      Throwable failure = null;
      try {
         startRenderer.run();
         while (!stopping) {
            drain();
            if (stopping) break;
            RawMouse.waitEvents();
            if (stopping) break;
            if (Display.isCreated()) Display.processMessages();
         }
      } catch (Throwable t) {
         failure = t;
         throw t;
      } finally {
         synchronized (queueLock) {
            stopping = true;
            Call<?> call;
            while ((call = queue.pollFirst()) != null) {
               call.fail(failure != null ? failure : new IllegalStateException("GLFW owner stopped"));
            }
         }
         try { RawMouse.close(); }
         finally { WindowInput.releaseCursor(); }
      }
   }

   public static void invoke(Runnable task) {
      call(() -> { task.run(); return null; });
   }

   public static <T> T call(java.util.function.Supplier<T> task) {
      if (!active() || isOwnerThread()) return task.get();
      Call<T> call = new Call<>(task);
      synchronized (queueLock) {
         if (stopping) throw new IllegalStateException("GLFW owner is stopping");
         queue.addLast(call);
      }
      wake();
      boolean interrupted = false;
      synchronized (call) {
         while (!call.done) {
            try { call.wait(); } catch (InterruptedException e) { interrupted = true; }
         }
      }
      if (interrupted) Thread.currentThread().interrupt();
      if (call.failure instanceof RuntimeException e) throw e;
      if (call.failure instanceof Error e) throw e;
      if (call.failure != null) throw new RuntimeException(call.failure);
      return call.result;
   }

   public static void wake() {
      if (enabled && owner != null) GLFW.glfwPostEmptyEvent();
   }

   public static void stop() {
      stopping = true;
      wake();
   }

   /** A request for the next snapshot, never a synchronous game/render-thread poll. */
   public static void requestSample() {
      if (!active() || sampleRequested) return;
      sampleRequested = true;
      wake();
   }

   public static boolean sampleDue() {
      long now = System.nanoTime();
      if (!sampleRequested && now < nextMaintenance) return false;
      sampleRequested = false;
      nextMaintenance = now + 10_000_000L;
      return true;
   }

   private static void drain() {
      for (;;) {
         Call<?> call;
         synchronized (queueLock) { call = queue.pollFirst(); }
         if (call == null) return;
         call.run();
      }
   }

   private static final class Call<T> {
      final java.util.function.Supplier<T> task;
      volatile boolean done;
      T result;
      Throwable failure;
      Call(java.util.function.Supplier<T> task) { this.task = task; }
      void run() {
         try { result = task.get(); } catch (Throwable t) { failure = t; }
         synchronized (this) { done = true; notifyAll(); }
      }
      void fail(Throwable t) {
         failure = t;
         synchronized (this) { done = true; notifyAll(); }
      }
   }
}
