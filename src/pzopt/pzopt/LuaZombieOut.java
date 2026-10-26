package pzopt;

/**
 * {@code pzopt-luazombie.out} (luaZombieOut, measurement; on by itself with devLuaZombieVariants / devLuaZombieCheck): one row per
 * frame, written at the next frame start on the game thread. Columns: epoch_ms, ab (1 = the pass's "on" half of a GtAb
 * alternation or no alternation, 0 = off), dispatch_ns (game thread in LuaZombie.dispatch: the handlers plus our loop),
 * replay_ns (UpdateBatch's whole Lua replay loop: every captured event of the flight, ours included), replay_events (records
 * replayed), calls (handler calls made), skipped (handler calls deferred), stag_ns (ns in the deferred handlers, with
 * luaZombieCost), updates (OnZombieUpdate hook calls), due (zombies whose deferred handlers came due), idle (zombies with
 * nothing to run: not captured), batch_ns (the batch API's call), collide (collide events fired), collide_dup (collide
 * events deduped), scale (luaZombieBudgetUs' interval scale), near_calls (handler calls on near-tier zombies),
 * hot_calls (calls a learned handler made because its last call changed the zombie), alpha_n / alpha_chg / alpha_rev / draw_rev
 * (devLuaZombieAlpha, pzopt.LuaZombieAlpha: on-screen zombies sampled, target alpha changes, target and drawn alpha reversals),
 * stale_ms (the mean over the deferred handlers of their mean interval between two calls on a zombie, ms).
 */
public final class LuaZombieOut {
   private LuaZombieOut() {
   }

   static final boolean ON = Config.LUA_ZOMBIE_OUT || LuaZombie.VARIANTS.length > 0 || Config.DEV_LUA_ZOMBIE_CHECK > 0 || Config.DEV_LUA_ZOMBIE_ALPHA; // measurement only (not for players)
   public static long replayNs, replayEvents; // UpdateBatch (game thread)
   public static long collide, collideDup; // LuaCollide (game thread)
   private static final StringBuilder LOG = new StringBuilder(1 << 16);
   private static java.io.Writer out;
   private static int rows;
   private static long lastUpdates, lastDue, lastIdle;
   private static boolean lastAb = true;
   private static long lastFlush;

   /** Game thread, frame start: the row of the frame that just ended. */
   static void frame() {
      if (!ON) {
         return;
      }
      if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
         reset();
         return;
      }
      LuaZombieAlpha.frame();
      long u = LuaZombie.updates.sum(), d = LuaZombie.dueN.sum(), i = LuaZombie.idleN.sum();
      LOG.append(System.currentTimeMillis()).append(' ').append(LuaZombie.VARIANTS.length > 0 ? LuaZombie.variant : lastAb ? 1 : 0).append(' ').append(LuaZombie.frameDispatchNs).append(' ')
         .append(replayNs).append(' ').append(replayEvents).append(' ').append(LuaZombie.frameCalls).append(' ').append(LuaZombie.frameSkipped)
         .append(' ').append(LuaZombie.frameStagNs).append(' ').append(u - lastUpdates).append(' ').append(d - lastDue).append(' ')
         .append(i - lastIdle).append(' ').append(LuaZombie.batchNs).append(' ').append(collide).append(' ').append(collideDup).append(' ').append(String.format("%.2f", LuaZombie.budgetScale())).append(' ').append(LuaZombie.frameNearCalls).append(' ').append(LuaZombie.frameHotCalls)
         .append(' ').append(LuaZombieAlpha.n).append(' ').append(LuaZombieAlpha.chg).append(' ').append(LuaZombieAlpha.rev).append(' ').append(LuaZombieAlpha.drawRev)
         .append(' ').append(String.format("%.1f", LuaZombie.frameStaleMs())).append('\n');
      lastUpdates = u;
      lastDue = d;
      lastIdle = i;
      long now = System.currentTimeMillis();
      if (++rows % 250 == 0 || now - lastFlush > 2000L) { // every 2 s too: a pass's process ends without a last flush
         lastFlush = now;
         flush();
      }
      reset();
      lastAb = LuaZombieAb.on(~0L); // GtAb.frame ran first in Pacing.stepStart: this frame's phase (of whichever name is listed)
   }

   private static void reset() {
      LuaZombie.sessionCalls += LuaZombie.frameCalls;
      LuaZombie.sessionSkipped += LuaZombie.frameSkipped;
      LuaZombie.frameDispatchNs = 0L;
      LuaZombie.frameCalls = 0L;
      LuaZombie.frameSkipped = 0L;
      LuaZombie.frameStagNs = 0L;
      LuaZombie.frameNearCalls = 0L;
      LuaZombie.frameHotCalls = 0L;
      LuaZombie.batchNs = 0L;
      replayNs = 0L;
      replayEvents = 0L;
      collide = 0L;
      collideDup = 0L;
   }

   private static void flush() {
      try {
         if (out == null) {
            java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-luazombie.out");
            out = new java.io.BufferedWriter(new java.io.FileWriter(f, true)); // appended: every pass of a benchmark chain (one process each) in one file
            out.write("# epoch_ms ab dispatch_ns replay_ns replay_events calls skipped stag_ns updates due idle batch_ns collide collide_dup scale near_calls hot_calls alpha_n alpha_chg alpha_rev draw_rev stale_ms  (luaZombieHz="
               + Config.LUA_ZOMBIE_HZ + " lod=" + Config.LUA_ZOMBIE_LOD + " writers=" + Config.LUA_ZOMBIE_WRITERS + " captureDue="
               + Config.LUA_ZOMBIE_CAPTURE_DUE + " timeComp=" + Config.LUA_ZOMBIE_TIME_COMP + " budgetUs=" + Config.LUA_ZOMBIE_BUDGET_US
               + " dirtySkip=" + Config.LUA_ZOMBIE_DIRTY_SKIP + " batchApi=" + Config.LUA_ZOMBIE_BATCH_API + " check=" + Config.DEV_LUA_ZOMBIE_CHECK
               + " costShare=" + Config.LUA_ZOMBIE_COST_SHARE + " collideDedupe=" + Config.LUA_COLLIDE_DEDUPE + " alternate=" + Config.DEV_GT_ALTERNATE + "/" + Config.DEV_GT_ALTERNATE_KEYS + " variants=" + Config.DEV_LUA_ZOMBIE_VARIANTS + "/" + Config.DEV_LUA_ZOMBIE_CYCLE_MS + ")\n");
         }
         out.write(LOG.toString());
         out.flush();
      } catch (java.io.IOException e) {
         Log.warn("lua zombie out: " + e);
      }
      LOG.setLength(0);
   }
}
