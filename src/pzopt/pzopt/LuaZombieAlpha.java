package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * {@code devLuaZombieAlpha} (2026-10-25, lua-zombie, the flicker check): does a deferred OnZombieUpdate handler make zombies
 * flicker between the mod's state and the game's? A mod that sets a zombie's visibility every update (ZombieVisibilityFix:
 * setTargetAlpha) and the game's own vision (which sets the target alpha too, every update) agree each frame in stock; with
 * the handler deferred, the frames between its calls show the game's value, the next call the mod's again. Each frame, for the
 * on-screen zombies of player 0, this counts the target alpha's and the drawn alpha's reversals (a change whose direction is
 * the opposite of that zombie's previous change, within 30 frames): a flicker is a train of reversals. Columns alpha_n
 * (zombies sampled), alpha_chg (target alpha changes), alpha_rev (target reversals), draw_rev (drawn alpha reversals) of
 * pzopt-luazombie.out. Game thread, at the frame start; dev only.
 */
final class LuaZombieAlpha {
   private LuaZombieAlpha() {
   }

   static final boolean ON = Config.DEV_LUA_ZOMBIE_ALPHA;
   static long n, chg, rev, drawRev;
   private static final int WINDOW = 30;

   /** lastTarget, lastTargetDir (+1 / -1 / 0), lastTargetFrame, lastAlpha, lastAlphaDir, lastAlphaFrame. */
   private static IdentityHashMap<IsoZombie, float[]> cur = new IdentityHashMap<>(), prev = new IdentityHashMap<>();
   private static int frame;

   static void frame() {
      n = chg = rev = drawRev = 0;
      if (!ON || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      frame++;
      ArrayList<IsoZombie> zs = IsoWorld.instance.currentCell.getZombieList();
      IdentityHashMap<IsoZombie, float[]> t = prev;
      prev = cur;
      cur = t;
      cur.clear();
      for (int i = 0, k = zs.size(); i < k; i++) {
         IsoZombie z = zs.get(i);
         IsoGridSquare sq = z.getCurrentSquare();
         if (sq == null || !sq.IsOnScreen()) {
            continue;
         }
         float ta = z.getTargetAlpha(0), a = z.getAlpha(0);
         float[] s = prev.get(z);
         if (s == null) {
            s = new float[] {ta, 0f, frame, a, 0f, frame};
         } else {
            n++;
            float d = ta - s[0];
            if (Math.abs(d) > 1e-3f) {
               chg++;
               float dir = Math.signum(d);
               if (s[1] != 0f && dir != s[1] && frame - s[2] <= WINDOW) {
                  rev++;
               }
               s[0] = ta;
               s[1] = dir;
               s[2] = frame;
            }
            float da = a - s[3];
            if (Math.abs(da) > 1e-3f) {
               float dir = Math.signum(da);
               if (s[4] != 0f && dir != s[4] && frame - s[5] <= WINDOW) {
                  drawRev++;
               }
               s[4] = dir;
               s[5] = frame;
            }
            s[3] = a;
         }
         cur.put(z, s);
      }
   }
}
