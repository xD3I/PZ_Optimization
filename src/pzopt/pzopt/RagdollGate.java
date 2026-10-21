package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * {@code ragdollGate} (2026-10-12): the "is this character a ragdoll" questions answered without touching the
 * character's animation player when no ragdoll exists.
 *
 * <p>Stock {@code IsoGameCharacter.isRagdoll()} is {@code hasAnimationPlayer() && getAnimationPlayer().isRagdolling()},
 * and {@code getAnimationPlayer()} first asks {@code ModelManager.getBodyModel(this)} (the character's animal / skeleton /
 * gender flags, the last one in its {@code SurvivorDesc}) whether the player must be replaced. The Louisville horde asks it
 * for every zombie once per frame in the update scheduler's batch filter and once per neighbour in every zombie's separation
 * pass (and again in postupdate's collision and the animation variables): tens of thousands of calls a frame, each a walk
 * through three other objects (descriptor, animation player, its ragdoll controller) of one of ~4,000 zombies. The answer is
 * {@code false} unless the animation player holds a ragdoll controller, and those are rare (a ragdoll is a zombie
 * falling under a hit or a car, a few at a time, capped by the game's active-ragdoll limit).
 *
 * <p>So: {@link #LIVE} counts the animation players whose {@code ragdollController} field is set, maintained at the two
 * places that write it ({@code AnimationPlayer.initRagdollController} / {@code releaseRagdollController}). While it is
 * zero every character's answer is {@code false} without reading anything of the character; while it is not, a
 * character whose animation player has no controller still answers {@code false} from the player's field. Only a
 * character with a controller takes the stock path. The returned value is always stock's: stock's answer is
 * {@code false} whenever the player it ends up asking has no controller, and a replaced player is a new one from the pool
 * (no controller). What the gate skips is the replacement itself, which stock does as a side effect of the question when
 * the character's body model changed since its player was made; the next {@code getAnimationPlayer()} (the character's
 * own update, the animation update, every model path) makes it as stock would. {@code devRagdollGateCheck=true} evaluates
 * stock's answer beside every gated one and counts the differences and the skipped replacements
 * ({@code ragdoll_gate} in {@code zombie_batches=} of pzopt-bench.out).
 */
public final class RagdollGate {
   private RagdollGate() {
   }

   /** Animation players holding a ragdoll controller (field non-null), from any thread. */
   private static final AtomicInteger LIVE = new AtomicInteger();

   public static final boolean KEY = Config.RAGDOLL_GATE;
   private static final boolean DEV = Config.DEV_RAGDOLL_GATE_CHECK;

   private static final LongAdder calls = new LongAdder();
   private static final LongAdder gatedGlobal = new LongAdder();
   private static final LongAdder gatedPlayer = new LongAdder();
   private static final LongAdder slow = new LongAdder();
   private static final LongAdder mismatches = new LongAdder();
   private static final LongAdder swapsSkipped = new LongAdder();
   private static int maxLive;

   /** Whether the gate answers for this call: the key, and the build's optimizations on. */
   public static boolean on() {
      return KEY && Overrides.enabled() && GtAb.on(GtAb.RAGDOLL_GATE);
   }

   /** No animation player anywhere holds a ragdoll controller. */
   public static boolean none() {
      return LIVE.get() == 0;
   }

   /** {@link #none()} for a caller about to test {@code ap}'s controller (dev rig: counts a held controller as a mismatch). */
   public static boolean noneFor(AnimationPlayer ap) {
      if (LIVE.get() != 0) {
         return false;
      }
      if (DEV) {
         calls.increment();
         gatedGlobal.increment();
         if (ap != null && (ap.getRagdollController() != null || ap.isRagdollSimulationActive())) {
            mismatches.increment();
         }
      }
      return true;
   }

   /** AnimationPlayer: its ragdollController field went from {@code had} to {@code has}. */
   public static void changed(boolean had, boolean has) {
      if (had != has) {
         int n = has ? LIVE.incrementAndGet() : LIVE.decrementAndGet();
         if (n > maxLive) {
            maxLive = n;
         }
      }
   }

   /**
    * isRagdoll / isRagdollSimulationActive / isFullyRagdolling, gated: true when the answer is stock's {@code false}
    * without asking the player ({@code ap} is the character's field, never getAnimationPlayer()).
    */
   public static boolean gatedFalse(IsoGameCharacter chr, AnimationPlayer ap, int which) {
      if (ap == null) {
         return true; // stock: hasAnimationPlayer() is false
      }
      if (LIVE.get() == 0) {
         if (DEV) {
            check(chr, ap, which, true);
         }
         return true;
      }
      if (ap.getRagdollController() == null) {
         if (DEV) {
            check(chr, ap, which, false);
         }
         return true;
      }
      if (DEV) {
         calls.increment();
         slow.increment();
      }
      return false;
   }

   /** Stock's answer, evaluated without its side effect (dev rig only). */
   private static void check(IsoGameCharacter chr, AnimationPlayer ap, int which, boolean global) {
      calls.increment();
      (global ? gatedGlobal : gatedPlayer).increment();
      if (ap.getModel() != ModelManager.instance.getBodyModel(chr)) {
         swapsSkipped.increment(); // stock would replace the player here and ask the new one (no controller: false)
         return;
      }
      boolean stock = switch (which) {
         case 0 -> ap.isRagdolling();
         case 1 -> ap.isRagdollSimulationActive() && chr.canRagdoll();
         default -> ap.isFullyRagdolling();
      };
      if (stock) {
         mismatches.increment();
      }
   }

   public static String describe() {
      return "ragdoll gate: on=" + on() + " live=" + LIVE.get() + " max_live=" + maxLive
            + (DEV ? " calls=" + calls.sum() + " global=" + gatedGlobal.sum() + " player=" + gatedPlayer.sum() + " slow=" + slow.sum()
                  + " swaps_skipped=" + swapsSkipped.sum() + " mismatches=" + mismatches.sum() : "");
   }
}
