package pzopt;

import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;

/**
 * The collide events (2026-10-25, worktree lua-zombie, idea 9): IsoMovingObject.collideWith fires OnCharacterCollide (both
 * characters) or OnObjectCollide for every collision it is told of: CollisionManager calls both a.collideWith(b) and
 * b.collideWith(a), the movement's own wall / object test and IsoThumpable add more, and a pair pushing into each other is
 * reported again by every resolution pass of the frame. {@code luaCollideDedupe}: an (object, other) pair fires once a frame
 * (the first report; both directions stay separate events, as a mod may only handle "self"). Game thread only (collideWith is
 * a PostupdateBatch hazard). Every report is counted (pzopt-luazombie.out collide / collide_dup) whether the key is on or not.
 */
public final class LuaCollide {
   private LuaCollide() {
   }

   private static final boolean ON = Config.LUA_COLLIDE_DEDUPE;
   private static Object[] ka = new Object[1024], kb = new Object[1024];
   private static int[] stamp = new int[1024];
   private static int used, frame = 1;
   private static int lastFrame = -1;

   /** IsoMovingObject.collideWith. */
   public static void collide(IsoMovingObject self, IsoObject obj) {
      LuaZombieOut.collide++;
      if (ON && LuaZombieAb.on(LuaZombieAb.COLLIDE) && seen(self, obj)) {
         LuaZombieOut.collideDup++;
         return;
      }
      if (self instanceof IsoGameCharacter && obj instanceof IsoGameCharacter) {
         LuaEventManager.triggerEvent("OnCharacterCollide", self, obj);
      } else {
         LuaEventManager.triggerEvent("OnObjectCollide", self, obj);
      }
   }

   static boolean seen(Object a, Object b) {
      int f = LuaZombie.frameNo;
      if (f != lastFrame) {
         lastFrame = f;
         frame++;
         used = 0;
         if (frame == 0) {
            java.util.Arrays.fill(stamp, 0);
            frame = 1;
         }
      }
      if (used * 2 >= stamp.length) {
         grow();
      }
      int mask = stamp.length - 1;
      int i = (System.identityHashCode(a) * 31 + System.identityHashCode(b)) * 0x9E3779B9 >>> 7 & mask;
      while (stamp[i] == frame) {
         if (ka[i] == a && kb[i] == b) {
            return true;
         }
         i = i + 1 & mask;
      }
      stamp[i] = frame;
      ka[i] = a;
      kb[i] = b;
      used++;
      return false;
   }

   private static void grow() {
      Object[] oa = ka, ob = kb;
      int[] os = stamp;
      int n = os.length * 2;
      ka = new Object[n];
      kb = new Object[n];
      stamp = new int[n];
      used = 0;
      int mask = n - 1;
      for (int j = 0; j < os.length; j++) {
         if (os[j] == frame) {
            int i = (System.identityHashCode(oa[j]) * 31 + System.identityHashCode(ob[j])) * 0x9E3779B9 >>> 7 & mask;
            while (stamp[i] == frame) {
               i = i + 1 & mask;
            }
            stamp[i] = frame;
            ka[i] = oa[j];
            kb[i] = ob[j];
            used++;
         }
      }
   }
}
