package pzopt;

import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.IsoPropertyType;

/**
 * propIdCache (f8-handoff 2026-10-14): PropertyContainer.has / get(IsoPropertyType) looked the property's id up by name in
 * TilePropertyAliasMap's HashMap on every call (getIDFromPropertyName: 0.8 % of the Louisville game thread, 10 % of a chunk
 * hand-off through RecalcProperties' has(FUEL_AMOUNT)). The id of a name only changes when the map is generated again
 * (TilePropertyAliasMap.Generate, called by IsoWorld.init; IsoWorld calls {@link #generated} right after), so the ids are kept
 * per IsoPropertyType for the current generation. Any thread: a holder per generation, entries filled lazily (an int each,
 * written with the same value by whoever looks it up first).
 */
public final class PropIds {
   private PropIds() {
   }

   private static final int UNSET = Integer.MIN_VALUE;
   private static volatile int gen;
   private static volatile Holder holder;

   private static final class Holder {
      final int gen;
      final int[] ids;

      Holder(int gen) {
         this.gen = gen;
         this.ids = new int[IsoPropertyType.values().length];
         java.util.Arrays.fill(this.ids, UNSET);
      }
   }

   /** IsoWorld, right after TilePropertyAliasMap.Generate: the ids may have changed. */
   public static void generated() {
      gen++;
   }

   public static int id(IsoPropertyType t) {
      Holder h = holder;
      int g = gen;
      if (h == null || h.gen != g) {
         h = new Holder(g);
         holder = h;
      }
      int o = t.ordinal();
      int v = h.ids[o];
      if (v == UNSET) {
         v = TilePropertyAliasMap.instance.getIDFromPropertyName(t.getName());
         h.ids[o] = v;
      } else if (Config.DEV_PROP_ID_CHECK) {
         checks.incrementAndGet();
         if (v != TilePropertyAliasMap.instance.getIDFromPropertyName(t.getName()) && mismatches.incrementAndGet() <= 5) {
            Log.warn("prop id cache: " + t.getName() + " cached " + v + " map " + TilePropertyAliasMap.instance.getIDFromPropertyName(t.getName()));
         }
         if ((checks.get() & 0xFFFFF) == 0) {
            Log.info("prop id cache check: " + checks.get() + " lookups compared, " + mismatches.get() + " mismatches");
         }
      }
      return v;
   }

   public static final java.util.concurrent.atomic.AtomicLong checks = new java.util.concurrent.atomic.AtomicLong();
   public static final java.util.concurrent.atomic.AtomicLong mismatches = new java.util.concurrent.atomic.AtomicLong();
}
