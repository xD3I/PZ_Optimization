package pzopt;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.BallisticsController;
import zombie.input.AimingReticle;
import zombie.iso.IsoUtils;
import zombie.iso.Vector2;
import zombie.iso.Vector3;

/** Fixed-storage B aim intent; native animation still supplies the muzzle origin and elevation at emission. */
public final class SubframeCombat {
   private static IsoPlayer candidateOwner;
   private static IsoPlayer acceptedOwner;
   private static boolean candidateActive;
   private static boolean candidateValid;
   private static boolean acceptedValid;
   private static float candidateAimX;
   private static float candidateAimY;
   private static float acceptedAimX;
   private static float acceptedAimY;
   private static float candidateAimingX;
   private static float candidateAimingY;
   private static float acceptedAimingX;
   private static float acceptedAimingY;
   private static float savedForwardX;
   private static float savedForwardY;
   private static int aimScopeDepth;
   private static IsoGameCharacter aimScopeOwner;
   private static final Vector2 tempAim = new Vector2();

   private SubframeCombat() {
   }

   /** Capture native reticle projection even when a newly equipped weapon has no model yet. */
   public static void beginCandidate(IsoPlayer player) {
      candidateOwner = player;
      candidateActive = player != null && SubframeInput.active();
      candidateValid = false;
      if (!candidateActive) {
         return;
      }

      player.getAimVector(tempAim);
      candidateAimX = tempAim.x;
      candidateAimY = tempAim.y;
      candidateValid = tempAim.getLengthSquared() > 0.0F;
      int playerIndex = IsoPlayer.getPlayerIndex(player);
      int x = AimingReticle.getX(playerIndex);
      int y = AimingReticle.getY(playerIndex);
      candidateAimingX = IsoUtils.XToIso(x, y, 0.0F);
      candidateAimingY = IsoUtils.YToIso(x, y, 0.0F);
      BallisticsController controller = player.getBallisticsController();
      if (controller != null) {
         controller.update();
      }
   }

   /** Commit only after the parent observes isAttackStarted changing false to true. */
   public static void commitCandidate() {
      if (!candidateActive || !candidateValid) {
         return;
      }

      acceptedOwner = candidateOwner;
      acceptedAimX = candidateAimX;
      acceptedAimY = candidateAimY;
      acceptedAimingX = candidateAimingX;
      acceptedAimingY = candidateAimingY;
      acceptedValid = true;
   }

   /** Ends the private candidate without disturbing an accepted attack. */
   public static void endCandidate() {
      IsoPlayer player = candidateOwner;
      candidateActive = false;
      candidateValid = false;
      candidateOwner = null;
      if (acceptedValid && acceptedOwner == player) {
         BallisticsController controller = player.getBallisticsController();
         if (controller != null) {
            controller.update();
         }
      }
   }

   private static boolean acceptedFor(IsoGameCharacter character) {
      return acceptedValid && acceptedOwner == character && character instanceof IsoPlayer player && player.isAttackStarted();
   }

   /** Rotate the native muzzle pose to B, preserving its offset, elevation, and animation aim error. */
   public static void orientAction(IsoGameCharacter character, Vector3 position, Vector3 direction, float modelAngle) {
      float x;
      float y;
      if (candidateActive && candidateValid && candidateOwner == character) {
         x = candidateAimX;
         y = candidateAimY;
      } else if (!candidateActive && acceptedFor(character)) {
         x = acceptedAimX;
         y = acceptedAimY;
      } else {
         return;
      }

      float modelX = (float)Math.cos(modelAngle);
      float modelY = (float)Math.sin(modelAngle);
      float cos = modelX * x + modelY * y;
      float sin = modelX * y - modelY * x;
      float offsetX = position.x - character.getX();
      float offsetY = position.y - character.getY();
      position.set(character.getX() + cos * offsetX - sin * offsetY,
         character.getY() + sin * offsetX + cos * offsetY, position.z);
      direction.set(cos * direction.x - sin * direction.y, sin * direction.x + cos * direction.y, direction.z);
   }

   /** Keep the native world-space reticle target at B even if the cursor or camera moves afterward. */
   public static void applyAiming(IsoGameCharacter character, Vector3 position) {
      if (candidateActive && candidateValid && candidateOwner == character) {
         position.set(candidateAimingX, candidateAimingY, 0.0F);
      } else if (!candidateActive && acceptedFor(character)) {
         position.set(acceptedAimingX, acceptedAimingY, 0.0F);
      }
   }

   /** Temporarily give native admission, hit selection, and collision the event's facing vector. */
   public static boolean beginAcceptedAimScope(IsoGameCharacter character) {
      float aimX;
      float aimY;
      if (candidateActive && candidateOwner == character && candidateValid) {
         aimX = candidateAimX;
         aimY = candidateAimY;
      } else if (!candidateActive && acceptedFor(character)) {
         aimX = acceptedAimX;
         aimY = acceptedAimY;
      } else {
         return false;
      }

      if (aimScopeDepth++ == 0) {
         savedForwardX = character.getForwardDirectionX();
         savedForwardY = character.getForwardDirectionY();
         aimScopeOwner = character;
      }
      character.getForwardDirection().set(aimX, aimY);
      return true;
   }

   public static void endAcceptedAimScope(boolean scoped) {
      if (scoped && aimScopeDepth != 0 && --aimScopeDepth == 0) {
         aimScopeOwner.getForwardDirection().set(savedForwardX, savedForwardY);
         aimScopeOwner = null;
      }
   }

   public static void clear(IsoPlayer player) {
      if (player == null) {
         return;
      }
      if (candidateOwner == player) {
         endCandidate();
      }
      if (acceptedOwner == player) {
         acceptedOwner = null;
         acceptedValid = false;
      }
   }

   /** Invalidate every action snapshot when the input stream resets. */
   public static void cancelPending() {
      candidateActive = false;
      candidateValid = false;
      candidateOwner = null;
      acceptedOwner = null;
      acceptedValid = false;
   }
}
