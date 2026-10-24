package pzopt;

import zombie.characters.IsoPlayer;
import zombie.gameStates.GameState;
import zombie.iso.IsoChunk;
import zombie.gameStates.IngameState;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.ui.UIManager;

/**
 * noLoadingScreen (2026-09-22, maintainer's request): single player shows a plain black screen while the world loads
 * (GameLoadingState.render) instead of the loading screen, and the world then appears at once: the 0.35 s fade from
 * black that IngameState.enter starts is cancelled right after the state machine enters it, before its first frame. The
 * world then appears from the player's chunk outwards (pzopt.CenterFirstLoad: chunks loaded, handed over and lit nearest
 * first). Also the load-trace markers "world visible" / "world complete", and the lighting thread's pace while the world
 * comes up.
 */
public final class NoLoadingScreen {
   private static GameState last;

   private NoLoadingScreen() {
   }

   public static boolean active() {
      return Config.NO_LOADING_SCREEN && Overrides.enabled() && !GameClient.client && !GameServer.server;
   }

   /** From GameWindow.logic right after states.update(), on the main thread. */
   public static void afterStateUpdate(GameState current) {
      ResumeShot.onFrame();
      if (current == last) {
         if (current instanceof IngameState) {
            watchVisible();
         }
         return;
      }
      last = current;
      CutawayMask.prefetch(); // the first state change (main menu): decoded long before the first cutaway
      ModelShaders.collectWarmup();
      if (current instanceof zombie.gameStates.GameLoadingState) {
         ModelShaders.warmup();
      }
      if (current instanceof IngameState) {
         if (Config.SHADER_WARMUP && Overrides.enabled()) {
            zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(GodRays.Gl::warm);
            zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(BloodWet.Gpu::warm);
            zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(GrassBlades.Gpu::warm);
         }
         CenterFirstLoad.onWorldEntered();
         ResumeShot.onWorldEntered();
         visibleState = 0;
         enteredMs = System.currentTimeMillis();
         if (active() && savedLightingFps < 0 && zombie.core.PerformanceSettings.lightingFps < ENTRY_LIGHTING_FPS) {
            savedLightingFps = zombie.core.PerformanceSettings.lightingFps; // field write, not setLightingFPS: options never see it
            zombie.core.PerformanceSettings.lightingFps = ENTRY_LIGHTING_FPS;
         }
      }
      if (current instanceof IngameState && active()) {
         UIManager.setFadeInTime(0);
         UIManager.setFadeAlpha(0);
      }
   }

   private static int visibleState; // 0 = watching the player's chunk, 1 = watching every loaded chunk, 2 = done
   private static long enteredMs;
   // the lighting thread paces itself at PerformanceSettings.lightingFps (the player's option, 15 by default): 66 ms a
   // cycle, and the first pass over the whole chunk map takes many cycles; while the world comes up it runs at ENTRY_LIGHTING_FPS
   private static final int ENTRY_LIGHTING_FPS = 240;
   private static final long MAX_BOOST_MS = 3000L;
   private static int savedLightingFps = -1;

   private static void restoreLighting() {
      if (savedLightingFps > 0) {
         zombie.core.PerformanceSettings.lightingFps = savedLightingFps;
         savedLightingFps = -1;
      }
   }

   /** Load-trace markers: "world visible" (the player's chunk is lit, so drawn) and "world complete" (every loaded chunk). */
   private static void watchVisible() {
      if (savedLightingFps > 0 && System.currentTimeMillis() - enteredMs > MAX_BOOST_MS) {
         restoreLighting();
      }
      if (visibleState >= 2) {
         return;
      }
      IsoPlayer p = IsoPlayer.getInstance();
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      if (p == null || cell == null) {
         return;
      }
      zombie.iso.IsoChunkMap cm = cell.chunkMap[0];
      int w = zombie.iso.IsoChunkMap.chunkGridWidth;
      int lit = 0;
      int unlit = 0;
      for (int y = 0; y < w; y++) {
         for (int x = 0; x < w; x++) {
            IsoChunk c = cm.getChunk(x, y);
            if (c != null && c.loaded) {
               if (c.lightingNeverDone[0]) {
                  unlit++;
               } else {
                  lit++;
               }
            }
         }
      }
      ResumeShot.setWorldCoverage((float)lit / (float)(w * w)); // the shot fades as the chunk map lights up
      if (visibleState == 0) {
         IsoChunk c = cm.getChunkForGridSquare((int)p.getX(), (int)p.getY());
         if (c != null && !c.lightingNeverDone[0]) {
            visibleState = 1;
            LoadTrace.step("world visible (" + (System.currentTimeMillis() - enteredMs) + " ms after entering the world)");
            LuaEventProfile.dump("world visible");
         }
         return;
      }
      int loaded = lit;
      if (unlit == 0 && loaded >= w * w * 3 / 4) {
         visibleState = 2;
         restoreLighting();
         ResumeShot.setWorldCoverage(1.0F);
         LoadTrace.step("world complete: " + loaded + " chunks lit (" + (System.currentTimeMillis() - enteredMs) + " ms after entering the world)");
         LuaEventProfile.dump("world complete");
      }
   }
}
