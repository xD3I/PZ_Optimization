package pzopt;

import zombie.characterTextures.CharacterSmartTexture;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstanceTextureCreator;

/**
 * zombieFirstSightSprite (f8-sight pass 2026-10-14; on by default since 2026-10-15, maintainer, round 11): when a horde
 * comes into view, ~130-370 zombies take a 3D model within a second and each makes its clothing composite on the render
 * thread in the frame it is first drawn (~120 us each, up to 88 in one frame: 10 ms). Stock draws nothing of a zombie
 * until its composite is made (ModelSlotRenderData.checkReady: not ready and nothing ready from before).
 *
 * <p>With the key on (with or without zombieLodDynamic: without it, round 11, the cull loop runs only the LOD's crossfade
 * clock and fade, {@code ZombieLod.clock / sightFade / endFade}, and the sprite is lit only for the new zombies,
 * {@link #newSprite}), the cull loop (nearest first) grants the first
 * composites of the frame up to {@code zombieCompositeBudgetUs} of render-thread time (the count follows the measured
 * cost of one, at least one a frame); a zombie past it is drawn this frame as its lit flat sprite (the LOD's atlas
 * sprite at {@code zombieLodSpriteLight}) and its model draw skips the composite (ModelSlotRenderData.pzoptSkipTexture:
 * nothing drawn, as stock while not ready). When its turn comes its model fades in over the sprite like the LOD's
 * sprite -> model switch ({@code zombieLodFadeMs}). Only a model instance that never had a composite waits: a zombie
 * whose clothes change (blood, holes) keeps the stock order.
 *
 * <p>Phasing fix (2026-10-18, Workshop reports "zombies briefly naked"): the flat sprite is the game's generic
 * DeadBodyAtlas body ({@code IsoZombie.renderTextureInsteadOfModel}: male / female, no clothes), so a waiting zombie
 * was a lit grey-white mannequin. Two ways in: (1) a hit, a hat or helmet falling off, a death or a corpse drag runs
 * {@code ModelManager.Reset}, which gives the zombie a fresh model instance (plain body texture, ready list cleared), so a
 * zombie that had been drawn clothed counted as "never had a composite" and showed the bare sprite, then crossfaded back;
 * (2) a horde's first sight held every zombie past the frame's grants as that sprite, ~1.5 s at 3 a frame. Now (1) a
 * zombie drawn with its clothing composite in the last {@link #CLOTHED_KEEP_NS} never waits (stock order: its new
 * composite is made in the frame it is drawn, nothing of it drawn before, as stock), and (2) without zombieLodDynamic a
 * waiting zombie draws nothing (stock's not-ready state: no sprite, no crossfade, the model at its stock alpha once its
 * composite is made) and waits at most {@code zombieFirstSightMaxWaitMs} (the grants a frame rise past the budgets to the even rate that meets
 * every waiting zombie's deadline). With
 * zombieLodDynamic the LOD's own sprite is held as before (its sprites are the same body by design).
 *
 * <p>The census (devFirstSightCensus, or instrumented runs with the key on): composites a frame and their
 * render-thread time a frame (histograms, max), grants, deferred zombie-frames, the cost estimate.
 */
public final class FirstSight {
   private FirstSight() {
   }

   public static final boolean ON = Config.ZOMBIE_FIRST_SIGHT_SPRITE && Overrides.enabled();
   public static final boolean CENSUS = Config.DEV_FIRST_SIGHT_CENSUS || Config.INSTRUMENT && ON;
   public static final boolean TIMING = ON || CENSUS;

   // ---- game thread ----
   /** the cull pass this frame (ModelSlotRenderData.init compares a zombie's deferral stamp with it) */
   public static volatile int frame;
   private static int grants;
   private static int limit = 1;
   private static boolean culling;
   public static long grantedTotal, deferredTotal, maxDeferred;
   private static int deferredThisFrame;
   /** the cull pass's clock (beginCull) */
   private static long cullNs;
   /** a zombie drawn with its clothing composite this recently is never held (phasing fix, 2026-10-18) */
   static final long CLOTHED_KEEP_NS = 2_000_000_000L;
   /** zombieFirstSightMaxWaitMs: no zombie waits longer for its first composite (0 = no bound) */
   private static final long MAX_WAIT_NS = Config.ZOMBIE_FIRST_SIGHT_MAX_WAIT_MS * 1_000_000L;
   /** census: zombie-frames / events of a zombie that had been clothed and came up without a composite (a model reset), kept in stock order; grants past the max wait; LOD sprites held */
   public static long keptFrames, keptEvents, forcedTotal, spriteHeld;

   // ---- render thread ----
   /** the render-thread cost of one composite (ns), exponential average of the made ones */
   private static volatile long costNs = 120_000L;

   public static boolean active() {
      return ON && !zombie.network.GameServer.server && !zombie.network.GameClient.client; // (round 11: no longer needs zombieLodDynamic; single player as the LOD)
   }

   /**
    * zombieLodSpriteLight without zombieLodDynamic: this zombie's flat sprite is drawn under its model in a crossfade, lit
    * like the LOD's sprites. Since the phasing fix (2026-10-18) a first-sight wait without the LOD draws no sprite, and a
    * zombie put off its model by zombieModelAddBudgetUs is the stock (unlit) far sprite, as with this key off.
    */
   public static boolean newSprite(IsoZombie z) {
      return z.pzoptLodSpriteAlpha > 0.0F; // (phasing fix 2026-10-18: a zombie put off its model by zombieModelAddBudgetUs is the stock far sprite again, unlit, as with this key off)
   }

   /** Cull loop, once a frame before the loop (zombieLodDynamic on, or {@link #active} without it). */
   public static void beginCull() {
      if (!ON) {
         return;
      }
      if (deferredThisFrame > maxDeferred) {
         maxDeferred = deferredThisFrame;
      }
      deferredThisFrame = 0;
      frame++;
      cullNs = System.nanoTime();
      if (lastCullNs != 0L) {
         long d = Math.max(1_000_000L, Math.min(100_000_000L, cullNs - lastCullNs));
         frameDtNs += (d - frameDtNs) / 8L;
      }
      lastCullNs = cullNs;
      grants = 0;
      long c = Math.max(5_000L, costNs);
      limit = (int)Math.max(1L, Config.ZOMBIE_COMPOSITE_BUDGET_US * 1000L / c);
      culling = true;
   }

   /** Cull loop, after a zombie took (or kept) its 3D slot: its place in the nearest-first order. */
   public static void won(IsoZombie z, int rank) {
      z.pzoptSightRank = rank;
   }

   private static final java.util.ArrayList<IsoZombie> pend = new java.util.ArrayList<>();
   private static int[] framesLeft = new int[512];
   /** the cull passes' interval (ns), exponential average (zombieFirstSightMaxWaitMs pacing) */
   private static long frameDtNs = 16_666_667L;
   private static long lastCullNs;

   /** CharDraw.start, before its tests: whether any zombie about to draw its model waits for its first composite. */
   public static boolean anyPending(java.util.List<IsoZombie> candidates) {
      if (!culling) {
         return false;
      }
      boolean any = false;
      for (int i = 0; i < candidates.size(); i++) { // every one (no early out): pending() stamps the clothed ones
         if (pending(candidates.get(i))) {
            any = true;
         }
      }
      return any;
   }
   private static final java.util.Comparator<IsoZombie> BY_RANK = java.util.Comparator.comparingInt(z -> z.pzoptSightRank);

   /**
    * CharDraw.start (game thread), the zombies about to draw their model this frame, before their draw data is built:
    * the first composites of the frame go to the nearest waiting ones (cull rank) up to the budget, the rest wait as
    * sprites. Granting here, among the zombies really drawn, keeps a grant from going to one off screen.
    */
   public static void grant(java.util.ArrayList<IsoZombie> drawn) {
      if (!culling || !active()) {
         return;
      }
      pend.clear();
      for (int i = 0; i < drawn.size(); i++) {
         IsoZombie z = drawn.get(i);
         if (pending(z)) {
            pend.add(z);
         }
      }
      if (pend.size() > limit) {
         pend.sort(BY_RANK);
      }
      boolean lod = ZombieLod.active();
      for (int i = 0; i < pend.size(); i++) {
         IsoZombie z = pend.get(i);
         if (z.pzoptSightPend != frame - 1) {
            z.pzoptSightWaitNs = cullNs; // a new wait (not waiting last frame)
         }
         z.pzoptSightPend = frame;
         if (lod) {
            // zombieLodDynamic: the waiting zombie is the LOD's sprite this frame, the sprite -> model crossfade held at its
            // start (the granted one too: its model fades in from the frame after its composite is made)
            z.pzoptLodFade = 2;
            z.pzoptLodFadeStartNs = ZombieLod.frameNs();
            z.pzoptLodModelAlpha = 0.0F;
            z.pzoptLodSpriteAlpha = 1.0F;
            spriteHeld++;
         } else {
            // without the LOD (the default): nothing of it is drawn while it waits, as stock while a composite is not
            // ready; no sprite (the game's generic unclothed body) and no crossfade
            z.pzoptLodFade = 0;
            z.pzoptLodModelAlpha = 1.0F;
            z.pzoptLodSpriteAlpha = 0.0F;
         }
      }
      // zombieFirstSightMaxWaitMs: the grants this frame are at least the rate that lets every waiting zombie have its
      // composite by its deadline (earliest-deadline feasibility: k zombies due within f frames need k / f a frame), so a
      // burst is spread evenly up to the deadline instead of all at once when it falls due
      int need = 0;
      long dt = Math.max(1_000_000L, frameDtNs);
      if (MAX_WAIT_NS > 0L && pend.size() > limit) {
         int n = pend.size();
         if (framesLeft.length < n) {
            framesLeft = new int[n * 2];
         }
         for (int i = 0; i < n; i++) {
            IsoZombie z = pend.get(i);
            framesLeft[i] = (int)Math.max(1L, Math.min(100_000L, (z.pzoptSightWaitNs + MAX_WAIT_NS - cullNs) / dt));
         }
         java.util.Arrays.sort(framesLeft, 0, n);
         double r = 0.0;
         for (int i = 0; i < n; i++) {
            r = Math.max(r, (i + 1) / (double)framesLeft[i]);
         }
         need = (int)Math.ceil(r - 1.0e-9);
      }
      int quota = Math.max(limit, need);
      boolean paced = need > limit; // past the budgets: the grants are made whatever the render thread's budget
      // the zombies due by the next frame first, then the nearest
      for (int i = 0; i < pend.size(); i++) {
         IsoZombie z = pend.get(i);
         if (MAX_WAIT_NS > 0L && z.pzoptSightWaitNs + MAX_WAIT_NS <= cullNs + dt) {
            grants++;
            grantedTotal++;
            z.pzoptSightForce = frame; // made this frame whatever the render thread's budget
            forcedTotal++;
            z.pzoptSightDefer = -1;
         } else {
            z.pzoptSightDefer = frame; // provisional, the second pass may grant it
         }
      }
      for (int i = 0; i < pend.size(); i++) {
         IsoZombie z = pend.get(i);
         if (z.pzoptSightDefer != frame) {
            continue;
         }
         if (grants < quota) {
            grants++;
            grantedTotal++;
            z.pzoptSightDefer = -1;
            if (paced) {
               z.pzoptSightForce = frame;
               forcedTotal++;
            }
         } else {
            deferredTotal++;
            deferredThisFrame++;
         }
      }
      pend.clear();
   }

   /** The zombie's model instance has never had its clothing composite (stock would draw nothing of it until it has). */
   static boolean pending(IsoZombie z) {
      if (z.legsSprite == null) {
         return false;
      }
      ModelManager.ModelSlot slot = z.legsSprite.modelSlot;
      if (slot == null || slot.model == null) {
         return false;
      }
      if (slot.model.tex instanceof CharacterSmartTexture) {
         z.pzoptClothedNs = cullNs; // drawn clothed (phasing fix)
         return false;
      }
      if (z.isReanimatedForGrappleOnly()) {
         return false; // a corpse being dragged is a new zombie made from the body (IsoDeadBody.reanimateZombieForGrapple): stock order, never a wait (phasing fix)
      }
      boolean waits;
      if (z.pzoptUpdateModelTextures()) {
         waits = true; // the render pre-pass makes its texture creator this frame
      } else {
         ModelInstanceTextureCreator tc = z.getTextureCreator();
         waits = tc != null && !tc.isRendered();
      }
      if (waits && z.pzoptClothedNs != 0L && cullNs - z.pzoptClothedNs < CLOTHED_KEEP_NS) {
         // phasing fix: a model reset (hit, hat falling off, death, corpse drag) gave a clothed zombie a fresh instance;
         // stock order (its composite now, nothing of it drawn before), never the bare sprite
         if (z.pzoptSightKept != frame) {
            if (z.pzoptSightKept != frame - 1) {
               keptEvents++;
            }
            z.pzoptSightKept = frame;
            keptFrames++;
         }
         return false;
      }
      return waits;
   }

   /** Cull loop: this zombie was drawn as its sprite last frame waiting for its composite (losing its slot, it stays the sprite: no fade-out of a model that never drew). */
   public static boolean waiting(IsoZombie z) {
      return ON && z.pzoptSightPend >= frame - 1 && z.pzoptSightPend >= 0;
   }

   /** ModelSlotRenderData.init: this character's first composite may be made this frame within the render thread's budget. */
   public static boolean eligible(IsoGameCharacter chr) {
      return ON && chr instanceof IsoZombie z && z.pzoptSightPend == frame && z.pzoptSightForce != frame; // (a zombie past zombieFirstSightMaxWaitMs is made whatever the budget)
   }

   private static final long BUDGET_NS = Config.ZOMBIE_COMPOSITE_BUDGET_US * 1000L;
   public static long renderWaits;

   /** ModelSlotRenderData.checkReady (render thread): the frame's first composites already took the budget (at least one is made a frame). */
   public static boolean overBudget() {
      if (frameComposites > 0 && (frameCompositeNs >= BUDGET_NS
            || Config.ZOMBIE_COMPOSITE_MAX_PER_FRAME > 0 && frameComposites >= Config.ZOMBIE_COMPOSITE_MAX_PER_FRAME)) { // f9-burst: also a count cap (the composites' GPU time is not in the render-thread budget)
         renderWaits++;
         return true;
      }
      return false;
   }

   /** ModelSlotRenderData.init (game thread or a draw-prep worker): this character's composite waits this frame. */
   public static boolean deferred(IsoGameCharacter chr) {
      return ON && chr instanceof IsoZombie z && z.pzoptSightDefer == frame;
   }

   // ---- render thread ----
   private static int frameComposites;
   private static long frameCompositeNs;
   private static final long[] histCount = new long[8]; // 0, 1, 2-3, 4-7, 8-15, 16-31, 32-63, 64+ composites a frame
   private static final long[] histMs = new long[7]; // <0.25, <0.5, <1, <2, <4, <8, >=8 ms a frame
   private static long composites, compositeNs, maxFrameComposites, maxFrameNs, notReady;
   private static long lastLog = System.nanoTime();

   /** render thread counters (TextureCombiner): what a slow composite did */
   public static long combines, newTex, newFbo;
   private static long c0, t0n, f0;
   private static int slowLogged;

   /** ModelSlotRenderData.checkReady, before a texture creator's render(). */
   public static void composing() {
      if (TIMING) GpuSections.markNow("moving.composite", false); // (gpuSections, f9-burst: the clothing composites' own GPU time, nested in moving)
      c0 = combines;
      t0n = newTex;
      f0 = newFbo;
   }

   /** ModelSlotRenderData.checkReady, after a texture creator's render(). */
   public static void composed(long ns, boolean rendered) {
      GpuSections.markNow("moving.composite", true);
      if (CENSUS && ns > 2_000_000L && slowLogged < 40) {
         slowLogged++;
         Log.info("first sight: slow composite " + ns / 1000L + " us, rendered " + rendered + ", combines " + (combines - c0) + ", new textures " + (newTex - t0n) + ", new framebuffers " + (newFbo - f0) + ", frame composites before " + frameComposites);
      }
      if (!rendered) {
         notReady++;
         return;
      }
      composites++;
      compositeNs += ns;
      frameComposites++;
      frameCompositeNs += ns;
      costNs = costNs + (Math.min(ns, 500_000L) - costNs) / 8L; // (a first-of-its-kind composite can take ms: capped so one does not stop the next frames)
   }

   private static boolean prewarmed;

   /** renderPrewarm: render thread, the first swap once the world exists. */
   private static void prewarm() {
      prewarmed = true;
      long t0 = System.nanoTime();
      try {
         int parts = Config.DEV_PREWARM_PARTS; // dev bisection: 1 horde batch programs / buffers, 2 atlas batch, 4 combiner pool, 8 composite shaders
         if ((parts & 1) != 0) {
            HordeBatch.prewarm();
         }
         if ((parts & 2) != 0) {
            AtlasBatch.prewarm();
         }
         if ((parts & 4) != 0 && CombinerPool.ON) {
            CombinerPool.budgetBytes();
            zombie.core.textures.TextureCombiner.instance.pzoptPrewarm(); // the composite sizes the reserve then fills
         }
         if ((parts & 8) != 0) {
            zombie.core.textures.SmartTexture st = new zombie.core.textures.SmartTexture();
            st.add(zombie.core.textures.Texture.getErrorTexture()); // the combiner's static shaders (made at the first add)
            st.clear();
         }
         if (Config.RENDER_PREWARM_OUTLINES) {
            prewarmOutlines();
         }
      } catch (Throwable t) {
         Log.warn("render prewarm: " + t);
      }
      Log.info("render prewarm: " + (System.nanoTime() - t0) / 1000L + " us");
   }

   /**
    * renderPrewarmOutlines (tail6 2026-10-14): the object / model outline pass's lazy setup, as its first use would do it: the
    * three screen-sized framebuffers (ModelOutlines.checkFBOs) and its three programs (ModelOutlines.endFrame makes them
    * together when the first is null). Its first use on the Louisville route (s1.6) was a 10-11 ms render-thread frame.
    */
   private static void prewarmOutlines() throws ReflectiveOperationException {
      zombie.core.skinnedmodel.model.ModelOutlines mo = zombie.core.skinnedmodel.model.ModelOutlines.instance;
      mo.checkFBOs();
      String[][] programs = {{"thickenHShader", "aim_outline_h"}, {"thickenVShader", "aim_outline_v"}, {"blitShader", "aim_outline_blit"}};
      java.lang.reflect.Field first = zombie.core.skinnedmodel.model.ModelOutlines.class.getDeclaredField(programs[0][0]);
      first.setAccessible(true);
      if (first.get(mo) == null) {
         for (String[] p : programs) {
            java.lang.reflect.Field f = zombie.core.skinnedmodel.model.ModelOutlines.class.getDeclaredField(p[0]);
            f.setAccessible(true);
            f.set(mo, zombie.core.opengl.ShaderProgram.createShaderProgram(p[1], false, false, true));
         }
      }
      // the character outline's solid-colour shaders (ModelSlotRenderData.performRenderCharacterOutline makes both when the first is null)
      if (zombie.core.skinnedmodel.model.ModelSlotRenderData.solidColor == null) {
         zombie.core.skinnedmodel.model.ModelSlotRenderData.solidColor = new zombie.core.skinnedmodel.shader.Shader("aim_outline_solid", false, false);
         zombie.core.skinnedmodel.model.ModelSlotRenderData.solidColorStatic = new zombie.core.skinnedmodel.shader.Shader("aim_outline_solid", true, false);
      }
      // the object outline's sprite shader (FBORenderObjectOutline.Drawer2.render makes it when null)
      Class<?> d2 = Class.forName("zombie.iso.fboRenderChunk.FBORenderObjectOutline$Drawer2");
      java.lang.reflect.Field os = d2.getDeclaredField("outlineShader");
      os.setAccessible(true);
      if (os.get(null) == null) {
         os.set(null, new zombie.core.skinnedmodel.shader.Shader("vboRenderer_SpriteOutline", true, false));
      }
   }

   /** Render thread, right after the swap. */
   public static void afterSwap() {
      if (Config.RENDER_PREWARM && !prewarmed && Overrides.enabled() && zombie.iso.IsoWorld.instance != null && zombie.iso.IsoWorld.instance.currentCell != null) {
         prewarm();
      }
      if (!TIMING) {
         return;
      }
      int c = frameComposites;
      long ns = frameCompositeNs;
      frameComposites = 0;
      frameCompositeNs = 0L;
      if (!CENSUS) {
         return;
      }
      histCount[c == 0 ? 0 : Math.min(7, 32 - Integer.numberOfLeadingZeros(c))]++;
      if (c > 0) {
         double ms = ns / 1.0e6;
         histMs[ms < 0.25 ? 0 : ms < 0.5 ? 1 : ms < 1 ? 2 : ms < 2 ? 3 : ms < 4 ? 4 : ms < 8 ? 5 : 6]++;
      }
      if (c > maxFrameComposites) {
         maxFrameComposites = c;
      }
      if (ns > maxFrameNs) {
         maxFrameNs = ns;
      }
      long now = System.nanoTime();
      if (now - lastLog < 10_000_000_000L) {
         return;
      }
      lastLog = now;
      Log.info("first sight: composites " + composites + " (" + compositeNs / 1_000_000L + " ms render thread, mean "
         + (composites == 0 ? 0 : compositeNs / composites / 1000L) + " us, cost estimate " + costNs / 1000L + " us), not ready " + notReady
         + ", per frame max " + maxFrameComposites + " / " + String.format(java.util.Locale.ROOT, "%.2f", maxFrameNs / 1.0e6)
         + " ms; frames with 0/1/2-3/4-7/8-15/16-31/32-63/64+ composites " + join(histCount) + "; frames by composite time <0.25/<0.5/<1/<2/<4/<8/8+ ms "
         + join(histMs) + (ON ? "; granted " + grantedTotal + ", deferred zombie-frames " + deferredTotal + " (max " + maxDeferred + " a frame), render-budget waits " + renderWaits + ", limit now " + limit
         + "; clothed zombies after a model reset kept in stock order " + keptEvents + " (" + keptFrames + " zombie-frames), granted past the budgets for the max wait " + forcedTotal + ", LOD sprites held " + spriteHeld : ""));
   }

   private static String join(long[] a) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < a.length; i++) {
         if (i > 0) {
            sb.append('/');
         }
         sb.append(a[i]);
      }
      return sb.toString();
   }
}
