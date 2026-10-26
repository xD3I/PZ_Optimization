package pzopt;

/**
 * Within-run A/B of lua-stream's keys (2026-10-26), alternated by GtAb's groups like pzopt.ZombAb: every name here maps to
 * GtAb bit 40 in GtAb's parse (its timers on) and {@link #on} reads the phase of the group the name is listed in
 * ({@code devGtAlternateKeys} / {@code devGtAlternateKeys2}). {@code harness/gtab.py} splits the frames by phase.
 * <ul>
 * <li>{@code luaLoadBudget}: the "off" half hands chunks off the stock way (pzopt.LuaStream switches only between chunks:
 * a chunk it started finishes in its slices);
 * <li>{@code luaLoadPrefilter}: the "off" half runs every LoadGridsquare handler (no skip, no learning).
 * </ul>
 */
public final class LuaStreamAb {
   private LuaStreamAb() {
   }

   private static final String[] NAMES = {"luaLoadBudget", "luaLoadPrefilter"};
   public static final long BUDGET = 1L, PREFILTER = 1L << 1;

   /** GtAb.parse: a name of this pass shares GtAb bit 40 (timers on); 0 for any other name. */
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
}
