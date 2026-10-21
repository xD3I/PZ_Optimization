package pzopt;

import zombie.characters.IsoZombie;

import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Dynamic zombie detail (`zombieLodDynamic`, 2026-09-24): how many zombies are drawn as 3D models and how many of those
 * blend their animations follow the frame cap instead of the fixed stock 510 / 20.
 *
 * <p>{@code IsoWorld.sceneCullZombies} sorts the on-screen zombies nearest first and gives the first 510 a model (the
 * rest are culled to the flat atlas sprite) and the first {@code PerformanceSettings.numberZombiesBlended} (20) animation
 * blending. A horde makes both limits expensive on the game thread (bones, skinning, the character draw) and the GPU.
 * Here one quality level between 0 and 1 maps linearly onto both: {@code zombieLodMin3d .. 510} models and
 * {@code zombieLodMinBlend .. numberZombiesBlended} blended.
 *
 * <p>The level follows the game-thread frame step ({@code FrameCap.lastStepNs}: the work of a frame without the
 * limiter's wait, so the headroom is visible while the game holds its cap). Every frame's step is kept; every
 * {@link #DECIDE_NS} the window's median and 90th percentile are compared with the cap's frame budget: a median above
 * {@link #OVER} of it (most frames miss the cap) lowers the level, more the further over, and blocks climbing for
 * {@link #CLIMB_HOLD_NS}; a 90th percentile below {@link #HEADROOM} (even the slow frames fit) raises it one small
 * step; anything else holds. A lone hitch moves nothing, and the level does not swing the same zombies between model
 * and sprite every few seconds (the first version, an 8-frame average, went 0 -> 1 -> 0 within 5 s on Louisville). The target is the
 * cap in force ({@code FrameCap.lockNow()}: the player's, the VRR cap or {@code frameCapFps}); uncapped it is
 * {@code zombieLodUncappedFps}, and 0 there means stock detail while uncapped.
 *
 * <p>Game thread only (called from sceneCullZombies). Stock values as ceilings: another mod lowering the 510 or the
 * blend count (ZBBetterFPS) still wins where it is lower.
 */
public final class ZombieLod {
   /** stock number of zombies drawn as 3D models */
   public static final int STOCK_3D = 510;
   static final long DECIDE_NS = 250_000_000L;
   /** median step above this share of the budget: most frames miss the cap, lower the detail */
   static final float OVER = 0.97F;
   /** 90th-percentile step below this share: even the slow frames fit, raise the detail */
   static final float HEADROOM = 0.85F;
   static final float UP_STEP = 0.03F;
   static final float DOWN_STEP = 0.08F;
   static final long LOG_NS = 5_000_000_000L;
   /** after a cut, no climbing for this long */
   static final long CLIMB_HOLD_NS = 2_000_000_000L;
   private static final long[] window = new long[256];
   private static int windowCount;
   private static long climbAfterNs;
   private static double lastP50Ns;

   private static float level = 1.0F;
   private static long lastDecideNs;
   private static long lastLogNs;
   private static int lastTargetFps;

   private ZombieLod() {
   }

   public static boolean active() {
      return Config.ZOMBIE_LOD_DYNAMIC && Overrides.enabled() && !GameServer.server && !GameClient.client;
   }

   // ---- the boundary (2026-10-13): hysteresis and a crossfade between the 3D model and the flat sprite ----
   private static long frameNs;

   /** this frame's cull-pass time (the crossfades' clock) */
   public static long frameNs() {
      return frameNs;
   }

   /**
    * zombieLodSpriteLight: the factor on the square's light this zombie's flat sprite is drawn in (0 = stock: black). With
    * zombieLodDynamic every flat sprite; without it only a sprite drawn under a crossfading model ({@link FirstSight#newSprite}):
    * the stock far sprites stay black.
    */
   public static float spriteLight(IsoZombie z) {
      if (Config.ZOMBIE_LOD_SPRITE_LIGHT <= 0) {
         return 0.0F;
      }
      return active() || FirstSight.active() && FirstSight.newSprite(z) ? Config.ZOMBIE_LOD_SPRITE_LIGHT / 100.0F : 0.0F;
   }

   /** zombieFirstSightSprite without zombieLodDynamic (round 11): once a frame before the cull loop, the crossfades' clock. */
   public static void clock() {
      frameNs = System.nanoTime();
   }

   /** zombieFirstSightSprite without zombieLodDynamic: a zombie in the cull loop's 3D places; a first-sight crossfade advances. */
   public static void sightFade(IsoZombie z) {
      if (z.pzoptLodFade != 0) {
         factors(z);
      }
   }

   /** zombieFirstSightSprite without zombieLodDynamic: a zombie drawn without its model this frame drops any crossfade. */
   public static void endFade(IsoZombie z) {
      if (z.pzoptLodFade != 0 || z.pzoptLodSpriteAlpha != 0.0F) {
         clear(z);
      }
   }

   /** zombieLodHysteresis: whether this zombie is drawn in 3D right now (it keeps its model a few places past the cap). */
   public static boolean drawnIn3d(IsoZombie z) {
      return !z.isSceneCulled() && z.hasActiveModel();
   }

   private static float fadeT(IsoZombie z) {
      long dur = Config.ZOMBIE_LOD_FADE_MS * 1_000_000L;
      return dur <= 0L ? 1.0F : Math.max(0.0F, Math.min(1.0F, (float)(frameNs - z.pzoptLodFadeStartNs) / (float)dur));
   }

   private static void factors(IsoZombie z) {
      float t = fadeT(z);
      if (z.pzoptLodFade == 2) {
         if (t >= 1.0F) {
            clear(z);
            return;
         }
         z.pzoptLodModelAlpha = t;
         z.pzoptLodSpriteAlpha = 1.0F - t;
      } else if (z.pzoptLodFade == 1) {
         z.pzoptLodModelAlpha = 1.0F - t;
         z.pzoptLodSpriteAlpha = t;
      } else {
         clear(z);
      }
   }

   private static void clear(IsoZombie z) {
      z.pzoptLodFade = 0;
      z.pzoptLodModelAlpha = 1.0F;
      z.pzoptLodSpriteAlpha = 0.0F;
   }

   /** Cull loop: this zombie gets (or keeps) a 3D slot. One that was a sprite, or fading out, fades its model in. */
   public static void won(IsoZombie z) {
      if (z.isSceneCulled() && z.pzoptLodFade != 1 && LOD_SEEN.contains(z)) {
         promotions++; // a sprite last pass gets its model
      }
      if (Config.ZOMBIE_LOD_FADE_MS <= 0) {
         LOD_SEEN.remove(z);
         clear(z);
         return;
      }
      if (z.pzoptLodFade == 1) {
         float t = fadeT(z); // reverse from where the fade-out is
         z.pzoptLodFade = 2;
         z.pzoptLodFadeStartNs = frameNs - (long)((1.0F - t) * Config.ZOMBIE_LOD_FADE_MS * 1_000_000L);
      } else if (z.pzoptLodFade == 0 && z.isSceneCulled() && LOD_SEEN.contains(z)) {
         z.pzoptLodFade = 2; // drawn as a sprite last frame
         z.pzoptLodFadeStartNs = frameNs;
         LOD_SEEN.remove(z);
      }
      factors(z);
   }

   /** Cull loop: this zombie lost its slot; true while its model fades out over the sprite (it stays drawn in 3D). */
   public static boolean keepFading(IsoZombie z) {
      if (Config.ZOMBIE_LOD_FADE_MS <= 0) {
         return false;
      }
      if (z.pzoptLodFade != 1) {
         if (z.isSceneCulled() || !z.hasActiveModel()) {
            return false;
         }
         demotions++;
         z.pzoptLodFade = 1;
         z.pzoptLodFadeStartNs = frameNs;
      }
      if (fadeT(z) >= 1.0F) {
         return false;
      }
      factors(z);
      return true;
   }

   private static long promotions;
   private static long demotions;

   /** Cull loop: a sprite (no fade, or the fade-out is over). */
   public static void lost(IsoZombie z) {
      if (Config.ZOMBIE_LOD_FADE_MS <= 0 && !z.isSceneCulled() && z.hasActiveModel()) {
         demotions++; // (counted here when there is no fade)
      }
      clear(z);
      LOD_SEEN.add(z);
   }

   /** Zombies drawn as sprites in the previous cull pass (a model appearing for one of them fades in; a new one just appears). */
   private static final java.util.Set<IsoZombie> LOD_SEEN = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

   /** Once per frame, before the cull loop. */
   public static void update() {
      frameNs = System.nanoTime();
      if (LOD_SEEN.size() > 8192) {
         LOD_SEEN.clear();
      }
      long step = FrameCap.lastStepNs;
      if (step > 0L && windowCount < window.length) {
         window[windowCount++] = step;
      }
      long now = System.nanoTime();
      if (now - lastDecideNs < DECIDE_NS) {
         return;
      }
      lastDecideNs = now;
      int fps = targetFps();
      lastTargetFps = fps;
      double p50 = 0.0;
      double p90 = 0.0;
      if (windowCount > 0) {
         java.util.Arrays.sort(window, 0, windowCount);
         p50 = window[(windowCount - 1) / 2];
         p90 = window[Math.min(windowCount - 1, (int)(windowCount * 0.9))];
      }
      windowCount = 0;
      lastP50Ns = p50;
      float before = level;
      level = next(level, p50, p90, fps, now >= climbAfterNs);
      if (level < before) {
         climbAfterNs = now + CLIMB_HOLD_NS;
      }
      if (now - lastLogNs >= LOG_NS) {
         lastLogNs = now;
         Log.info(describe());
      }
   }

   /** The cap the detail aims for, 0 = none (stock detail). */
   static int targetFps() {
      if (FrameCap.uncappedNow()) {
         return Math.max(0, Config.ZOMBIE_LOD_UNCAPPED_FPS);
      }
      return FrameCap.lockNow();
   }

   /**
    * The controller step: the next level from the current one, the window's median and 90th-percentile frame step,
    * the target fps and whether climbing is allowed (not within {@link #CLIMB_HOLD_NS} of a cut).
    */
   static float next(float level, double p50Ns, double p90Ns, int targetFps, boolean mayClimb) {
      if (targetFps <= 0) {
         return Math.min(1.0F, level + UP_STEP); // no target: back to stock detail
      }
      if (p50Ns <= 0.0) {
         return level;
      }
      double budget = 1.0e9 / targetFps;
      double ratio = p50Ns / budget;
      if (ratio > OVER) {
         // the further over the budget, the bigger the cut (at most three steps at once)
         float cut = DOWN_STEP * (float)Math.min(3.0, 1.0 + (ratio - OVER) / 0.25);
         return Math.max(0.0F, level - cut);
      }
      if (mayClimb && p90Ns / budget < HEADROOM) {
         return Math.min(1.0F, level + UP_STEP);
      }
      return level;
   }

   /** Zombies drawn as 3D models at the current level (at most the stock 510). */
   public static int max3d() {
      if (Config.ZOMBIE_LOD_FIXED_3D > 0) {
         return Math.min(STOCK_3D, Config.ZOMBIE_LOD_FIXED_3D); // zombieLodFixed3d: a fixed count instead of the controller
      }
      return scale(level, Config.ZOMBIE_LOD_MIN_3D, STOCK_3D);
   }

   /** Zombies that blend animations at the current level (at most {@code stockBlended}). */
   public static int blended(int stockBlended) {
      return scale(level, Config.ZOMBIE_LOD_MIN_BLEND, stockBlended);
   }

   static int scale(float level, int min, int max) {
      int lo = Math.max(0, Math.min(min, max));
      return lo + Math.round(level * (max - lo));
   }

   static float level() {
      return level;
   }

   public static String describe() {
      return String.format(java.util.Locale.ROOT, "zombie lod: model<->sprite switches so far %d up / %d down; level %.2f, 3d %d, blended %d, median step %.2f ms, target %s", promotions, demotions,
         level, max3d(), blended(zombie.core.PerformanceSettings.numberZombiesBlended), lastP50Ns / 1.0e6,
         lastTargetFps > 0 ? lastTargetFps + " fps (" + String.format(java.util.Locale.ROOT, "%.2f", 1000.0 / lastTargetFps) + " ms)" : "none (stock detail)");
   }
}
