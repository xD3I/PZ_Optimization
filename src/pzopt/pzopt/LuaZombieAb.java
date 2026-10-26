package pzopt;

/**
 * Within-run A/B of the mod Lua per-zombie pass (2026-10-25, worktree lua-zombie), on GtAb's alternation like ZombAb: a name
 * here in {@code devGtAlternateKeys} (or {@code devGtAlternateKeys2}) maps to GtAb bit 40 (its timers on, the bit nobody
 * reads) and {@link #on} reads that group's phase. "luaZombie" alternates the whole pass (the off half is the stock dispatch:
 * every handler, every update), "luaCollide" the collide-event dedupe, "luaZombieDirty" the dirty skip alone,
 * "luaZombieCheap" the cheap-handler exemption alone.
 */
public final class LuaZombieAb {
   private LuaZombieAb() {
   }

   private static final String[] NAMES = {"luaZombie", "luaCollide", "luaZombieDirty", "luaZombieCheap"};
   public static final long STAGGER = 1L, COLLIDE = 1L << 1, DIRTY = 1L << 2, CHEAP = 1L << 3;

   /** GtAb.parse: a name of this pass shares GtAb bit 40; 0 for any other name. */
   static long bit(String name) {
      return own(name) != 0L ? 1L << 40 : 0L;
   }

   private static long own(String name) {
      for (int i = 0; i < NAMES.length; i++) {
         if (NAMES[i].equals(name)) {
            return 1L << i;
         }
      }
      return 0L;
   }

   private static long parse(String keys) {
      long m = 0L;
      if (keys == null || keys.isEmpty()) {
         return 0L;
      }
      for (String k : keys.split(",")) {
         m |= own(k.trim());
      }
      return m;
   }

   private static final long MASK1 = Config.DEV_GT_ALTERNATE > 0 ? parse(Config.DEV_GT_ALTERNATE_KEYS) : 0L;
   private static final long MASK2 = Config.DEV_GT_ALTERNATE2 > 0 ? parse(Config.DEV_GT_ALTERNATE_KEYS2) : 0L;

   /** False in the off half of the alternation group a listed key belongs to. */
   public static boolean on(long bit) {
      return ((MASK1 & bit) == 0L || !GtAb.offPhase1()) && ((MASK2 & bit) == 0L || !GtAb.offPhase2());
   }

   /** Is the bit in either alternation group. */
   public static boolean listed(long bit) {
      return ((MASK1 | MASK2) & bit) != 0L;
   }
}
