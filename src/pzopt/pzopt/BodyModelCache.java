package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import zombie.characters.IsoGameCharacter;
import zombie.characters.SurvivorDesc;
import zombie.core.Core;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;

/**
 * {@code bodyModelCache} (2026-10-12, the follow-up of {@link RagdollGate}): a character's body model and "its animation
 * player is made for it" kept on the character itself, so {@code getAnimationPlayer()} and the entity-update filter's
 * stale-player check stop reading two other objects per call.
 *
 * <p>Stock {@code getAnimationPlayer()} starts with {@code ModelManager.getBodyModel(this)} (animal flag, skeleton flag,
 * the gender in the character's {@code SurvivorDesc}: one cache miss on the descriptor) and compares it with the
 * player's model (a second miss on the player); the async profiler put ~1.4 % of the Louisville game thread there
 * (isRagdoll's real cost, then the filter's own check once the ragdoll gate removed isRagdoll). The body model is a pure
 * function of inputs that change rarely and only through a few writes, so it is memoised per character with exact
 * invalidation:
 * <ul>
 *   <li>the descriptor reference (a character field, compared at every use; a new descriptor misses);</li>
 *   <li>the descriptor's gender: {@code SurvivorDesc.setCharacterGender} / {@code load} bump {@link #epoch} when that
 *       descriptor has ever been used as a key ({@code pzoptBmKeyed}), which invalidates every entry;</li>
 *   <li>the zombie skeleton flag: {@code IsoZombie.setSkeleton} clears that character's entry;</li>
 *   <li>the four body models: {@code ModelManager.create} bumps the epoch;</li>
 *   <li>animals (their visual's model) and debug sessions ({@code forceSkeleton}) are never cached.</li>
 * </ul>
 * The player check: {@code pzoptBmPlayer} is the animation player found to carry the cached model (written with the
 * entry, cleared when the entry is rewritten, when the character replaces or releases its player and in
 * {@code AnimationPlayer.onReleased}); a player's model is only written at {@code alloc} and {@code onReleased}, so while
 * the character still holds that same player under a valid entry stock's comparison is known to be equal.
 * {@code devBodyModelCheck=true} recomputes stock's answer beside every cached one and counts mismatches.
 */
public final class BodyModelCache {
   private BodyModelCache() {
   }

   public static final boolean KEY = Config.BODY_MODEL_CACHE;
   private static final boolean DEV = Config.DEV_BODY_MODEL_CHECK;
   private static final AtomicInteger EPOCH = new AtomicInteger(1);

   private static final LongAdder hits = new LongAdder();
   private static final LongAdder misses = new LongAdder();
   private static final LongAdder playerHits = new LongAdder();
   private static final LongAdder mismatches = new LongAdder();
   private static final LongAdder bumps = new LongAdder();

   public static boolean on() {
      return KEY && Overrides.enabled() && !Core.debug && GtAb.on(GtAb.BODY_MODEL_CACHE);
   }

   /** A body-model input changed for characters we cannot name: every entry is stale. */
   public static void bump() {
      EPOCH.incrementAndGet();
      bumps.increment();
   }

   /** The entry of {@code chr} holds its current body model. */
   public static boolean valid(IsoGameCharacter chr) {
      return chr.pzoptBmEpoch == EPOCH.get() && chr.pzoptBmDesc == chr.getDescriptor() && chr.pzoptBmModel != null;
   }

   /** ModelManager.getBodyModel, cached; {@code null} = compute it stock's way and call {@link #store}. */
   public static Model cached(IsoGameCharacter chr) {
      if (chr.isAnimal() || !on()) {
         return null;
      }
      if (valid(chr)) {
         Model m = chr.pzoptBmModel;
         if (DEV) {
            hits.increment();
            if (m != stockBodyModel(chr)) {
               mismatches.increment();
            }
         }
         return m;
      }
      return null;
   }

   /** The epoch to store with an entry: read before the inputs, so a bump during the computation misses next time. */
   public static int begin(IsoGameCharacter chr) {
      if (!KEY || chr.isAnimal() || !on()) {
         return 0;
      }
      int e = EPOCH.get();
      SurvivorDesc d = chr.getDescriptor();
      if (d != null && !d.pzoptBmKeyed) {
         d.pzoptBmKeyed = true;
      }
      return e;
   }

   public static Model store(IsoGameCharacter chr, int epoch, Model m) {
      if (DEV) {
         misses.increment();
      }
      if (chr.isAnimal() || !on()) {
         return m;
      }
      chr.pzoptBmPlayer = null;
      chr.pzoptBmModel = m;
      chr.pzoptBmDesc = chr.getDescriptor();
      chr.pzoptBmEpoch = epoch;
      return m;
   }

   /** getAnimationPlayer's fast path: the held player is known to carry the body model. */
   public static boolean playerSettled(IsoGameCharacter chr, AnimationPlayer ap) {
      if (ap == null || ap != chr.pzoptBmPlayer || chr.isAnimal() || !on() || !valid(chr)) {
         return false;
      }
      if (DEV) {
         playerHits.increment();
         if (ap.getModel() != stockBodyModel(chr)) {
            mismatches.increment();
         }
      }
      return true;
   }

   /** After getAnimationPlayer's stock body: remember the player when it carries the cached model. */
   public static void notePlayer(IsoGameCharacter chr, AnimationPlayer ap, Model model) {
      if (ap != null && model != null && ap.getModel() == model && chr.pzoptBmModel == model && on() && valid(chr)) {
         chr.pzoptBmPlayer = ap;
      }
   }

   /** Stock ModelManager.getBodyModel, side-effect free outside debug sessions (dev check only). */
   private static Model stockBodyModel(IsoGameCharacter chr) {
      return ModelManager.instance.pzoptStockBodyModel(chr);
   }

   public static String describe() {
      return "body model cache: on=" + on() + " epoch=" + EPOCH.get() + " bumps=" + bumps.sum()
            + (DEV ? " hits=" + hits.sum() + " misses=" + misses.sum() + " player_hits=" + playerHits.sum() + " mismatches=" + mismatches.sum() : "");
   }
}
