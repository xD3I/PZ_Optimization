package pzopt;

/**
 * luaTickTimeComp=fps (2026-10-25, pzopt.LuaTick): while a spaced-out Lua handler runs, GameTime's frame-time getters
 * (getMultiplier, getUnmoddedMultiplier, getRealworldSecondsSinceLastUpdate, getMultiplierInMenu, and the ones built on
 * them: getTimeDelta, getGameWorldSecondsSinceLastUpdate, getMultipliedSecondsSinceLastUpdate, getThirtyFPSMultiplier,
 * getInvMultiplier) read the sum of fpsMultiplier over the frames since that handler's last call instead of this
 * frame's, so a mod that scales its per-call work by the multiplier advances the same per second as when it ran every
 * frame. Only on the thread that set it (the game thread, which runs Lua): a frame worker or the render thread reading
 * GameTime meanwhile sees the field. The GameTime override wraps its fpsMultiplier reads in {@link #fps}.
 */
public final class LuaTickTime {
   private LuaTickTime() {
   }

   /** True while a compensated handler runs (plain field: only its owner thread acts on it, others fall through the owner test). */
   static boolean active;
   static Thread owner;
   static float sum;

   /** GameTime: the fpsMultiplier a getter uses on this thread now. */
   public static float fps(float field) {
      return active && Thread.currentThread() == owner ? sum : field;
   }
}
