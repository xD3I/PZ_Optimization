package pzopt;

import java.util.concurrent.locks.LockSupport;
import zombie.vispoly.VisibilityPolygon2;

/**
 * The vision cone's polygon computed while the game thread renders the world ({@code visPolyAsync}, 2026-09-27; the
 * plan-resource-use item 3a).
 *
 * <p>{@code VisibilityPolygon2.renderMain} (late in the tile render, at the camera's level) recomputes the shadow polygon
 * of the player's vision cone whenever the player moved or turned, i.e. every frame while walking, driving or spinning: a
 * walk over the loaded chunks' wall lists (rebuilt when a neighbour loaded) and the view-cone tests. Its inputs are the
 * frame's camera, the player's position, facing and vision cone, the chunks' on-screen levels and wall lists: all final
 * once the cell render's checks ran, and nothing in the tile render changes them. So the game thread starts it on this
 * pass's thread right before performRenderTiles and renderMain takes the finished drawer (the stock calculation, the same
 * drawer object); a worker never creates a chunk's render levels (a chunk without them has never been on screen). One
 * player only (the drawer's static scratch).
 */
public final class VisPolyAsync {
   private VisPolyAsync() {
   }

   private static Thread thread;
   private static volatile Object job; // the drawer being computed
   private static volatile int jobPlayer;
   private static volatile Object done; // the drawer finished last
   private static volatile Throwable failure;
   private static boolean failed;
   private static Object started; // game thread: the drawer started this frame
   public static long starts, joins, waits, inline;

   private static boolean enabled() {
      return Config.VIS_POLY_ASYNC && !failed && Overrides.enabled();
   }

   /** Game thread, right before performRenderTiles. */
   public static void start(int playerIndex) {
      if (started != null) { // last frame's polygon was never taken (renderMain skipped): let it finish before reusing the thread
         Object prev = started;
         while (done != prev && job != null) {
            ShortWait.pause(20_000L);
         }
      }
      started = null;
      if (!enabled() || !GtAb.on(GtAb.VIS_POLY)) {
         return;
      }
      Object drawer = VisibilityPolygon2.getInstance().pzoptAsyncDrawer(playerIndex);
      if (drawer == null) {
         return;
      }
      if (thread == null) {
         thread = new Thread(VisPolyAsync::loop, "pzopt-vispoly");
         thread.setDaemon(true);
         thread.start();
      }
      starts++;
      started = drawer;
      done = null;
      jobPlayer = playerIndex;
      job = drawer;
      LockSupport.unpark(thread);
   }

   private static void loop() {
      CorePlacement.frameCritical(); // macOS: workerQosMac (utility by default)
      while (true) {
         Object d = job;
         if (d == null) {
            LockSupport.park();
            continue;
         }
         CorePlacement.frameCritical(); // f11-mac: workerQosMac alternation
         try {
            VisibilityPolygon2.pzoptCalculate(d, jobPlayer);
         } catch (Throwable t) {
            failure = t;
         }
         job = null;
         done = d;
      }
   }

   /** Game thread, renderMain: true when {@code drawer} was computed on the worker this frame (waits for it if needed). */
   public static boolean join(Object drawer) {
      if (started == null || started != drawer) {
         inline++;
         return false;
      }
      started = null;
      joins++;
      if (done != drawer) {
         waits++;
         int spins = 0;
         while (done != drawer) {
            if (++spins < 200) {
               Thread.onSpinWait();
            } else {
               ShortWait.pause(20_000L);
            }
         }
      }
      if (failure != null) {
         failed = true;
         Log.warn("visPolyAsync: the polygon failed on its thread, the game thread computes it from now on: " + failure);
         failure = null;
         return false; // renderMain computes it itself this frame
      }
      return true;
   }

   public static String describe() {
      return "visPoly starts=" + starts + " joins=" + joins + " waits=" + waits + " inline=" + inline + (failed ? " FAILED" : "");
   }
}
