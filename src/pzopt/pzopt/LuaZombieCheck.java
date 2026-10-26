package pzopt;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.characters.IsoZombie;

/**
 * The zombie's observable state around one mod handler call (pzopt.LuaZombie, 2026-10-25): a snapshot before and after the
 * call, compared field by field. Three users:
 * <ul>
 * <li>{@code luaZombieWriters=learn}: a writer handler that changed the zombie in a call runs every update on that zombie for
 * {@code luaZombieHotMs} (what the handler sets is not left to fight the game's own update between deferred calls);</li>
 * <li>{@code devLuaZombieCheck=N} (the dual-evaluation rig): on one zombie in N each frame a handler the plan would skip runs
 * anyway (the stock call) and the diff says whether skipping it would have lost a write: per handler "sampled", "would lose",
 * and which fields;</li>
 * <li>{@code luaZombieDirtySkip}: a due zombie whose snapshot equals the one at its last deferred call is skipped.</li>
 * </ul>
 * Fields: position (x, y, z), the state, the target, health, useless / crawling / fake dead / knocked down / on fire /
 * become-crawler, the speed mod, the first player's alpha and target alpha, the vehicle collision filter
 * (vehicle4testCollision), and a hash of the mod data table's first 64 entries (nested tables by identity and size). The
 * animation variables (setVariable) are not read: a handler that only sets variables reads as "no change" (the scan still
 * names it a writer).
 */
public final class LuaZombieCheck {
   private LuaZombieCheck() {
   }

   static final String[] FIELDS = {"pos", "state", "target", "health", "flags", "speed", "alpha", "vehicle", "modData"};

   public static final class Snap {
      float x, y, z, health, speed, alpha, targetAlpha;
      Object state, target, vehicle;
      int flags, mod;

      void take(IsoZombie zo) {
         this.x = zo.getX();
         this.y = zo.getY();
         this.z = zo.getZ();
         this.state = zo.getCurrentState();
         this.target = zo.getTarget();
         this.health = zo.getHealth();
         this.flags = (zo.isUseless() ? 1 : 0) | (zo.isCrawling() ? 2 : 0) | (zo.isFakeDead() ? 4 : 0) | (zo.isKnockedDown() ? 8 : 0)
            | (zo.isOnFire() ? 16 : 0) | (zo.isBecomeCrawler() ? 32 : 0);
         this.speed = zo.getSpeedMod();
         this.alpha = zo.getAlpha(0);
         this.targetAlpha = zo.getTargetAlpha(0);
         this.vehicle = zo.pzoptVehicle4Test();
         this.mod = modHash(zo.table);
      }

      /** Bit i set: FIELDS[i] differs. */
      int diff(Snap o) {
         int d = 0;
         if (this.x != o.x || this.y != o.y || this.z != o.z) {
            d |= 1;
         }
         if (this.state != o.state) {
            d |= 2;
         }
         if (this.target != o.target) {
            d |= 4;
         }
         if (this.health != o.health) {
            d |= 8;
         }
         if (this.flags != o.flags) {
            d |= 16;
         }
         if (this.speed != o.speed) {
            d |= 32;
         }
         if (this.alpha != o.alpha || this.targetAlpha != o.targetAlpha) {
            d |= 64;
         }
         if (this.vehicle != o.vehicle) {
            d |= 128;
         }
         if (this.mod != o.mod) {
            d |= 256;
         }
         return d;
      }

      /** luaZombieDirtySkip: nothing a handler could react to changed since the snapshot (position by square). */
      static boolean sameAs(Snap s, IsoZombie zo) {
         return (int)s.x == (int)zo.getX() && (int)s.y == (int)zo.getY() && (int)s.z == (int)zo.getZ() && s.state == zo.getCurrentState()
            && s.target == zo.getTarget() && s.health == zo.getHealth() && s.mod == modHash(zo.table);
      }
   }

   static int modHash(KahluaTable t) {
      if (t == null) {
         return 0;
      }
      int h = 1;
      int n = 0;
      KahluaTableIterator it = t.iterator();
      while (it.advance() && n++ < 64) {
         Object k = it.getKey();
         Object v = it.getValue();
         h = h * 31 + (k == null ? 0 : k.hashCode());
         h = h * 31 + valueHash(v);
      }
      return h * 31 + t.size();
   }

   private static int valueHash(Object v) {
      if (v == null) {
         return 0;
      }
      if (v instanceof Double || v instanceof String || v instanceof Boolean) {
         return v.hashCode();
      }
      if (v instanceof KahluaTable t) {
         return System.identityHashCode(t) * 17 + t.size();
      }
      return System.identityHashCode(v);
   }

   private static final Snap PRE = new Snap(), POST = new Snap();

   /** Game thread: the snapshot before a handler call. */
   static void before(IsoZombie z) {
      PRE.take(z);
   }

   // rig counters per handler slot (game thread)
   private static final long[] sampled = new long[64], lost = new long[64];
   private static final long[][] lostField = new long[64][FIELDS.length];
   private static final String[] names = new String[64];

   /** Game thread: the snapshot after the call; {@code shadow} = the plan would have skipped it (the rig's sample). */
   static int after(IsoZombie z, LuaZombie.Handler h, boolean shadow) {
      POST.take(z);
      int d = PRE.diff(POST);
      if (shadow) {
         int s = h.slot;
         names[s] = h.origin + " " + h.where;
         sampled[s]++;
         if (d != 0) {
            lost[s]++;
            for (int i = 0; i < FIELDS.length; i++) {
               if ((d & 1 << i) != 0) {
                  lostField[s][i]++;
               }
            }
         }
      }
      return d;
   }

   static void log() {
      if (LuaZombie.CHECK <= 0) {
         return;
      }
      StringBuilder sb = new StringBuilder("lua zombie check: skipped calls run anyway, and how many of them changed the zombie (would be lost by the skip)");
      for (int s = 0; s < 64; s++) {
         if (sampled[s] == 0) {
            continue;
         }
         sb.append(String.format("%n  sampled=%d changed=%d (%.1f %%)", sampled[s], lost[s], 100.0 * lost[s] / sampled[s]));
         for (int i = 0; i < FIELDS.length; i++) {
            if (lostField[s][i] > 0) {
               sb.append(' ').append(FIELDS[i]).append('=').append(lostField[s][i]);
            }
         }
         sb.append("  ").append(names[s]);
      }
      Log.info(sb.toString());
   }
}
