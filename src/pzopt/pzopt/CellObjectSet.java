package pzopt;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;

/**
 * The cell's moving-object set with a change counter ({@code animalLosWalk}, tail pass 2026-10-13).
 *
 * <p>{@code IsoCell.objectList} is a plain {@code HashSet}; every animal's {@code updateLOS} walks it once a frame (the
 * Louisville horde: ~2,600 entries, 5 % of the game thread). {@link pzopt.AnimalLosWalk} keeps the set's iteration order
 * in an array and reuses it while the set is unchanged. This subclass is the set the cell gets instead, swapped in while it
 * is still empty (the cell's constructor, from {@code IsoChunkMap}'s), so its table grows exactly like the stock set's and
 * iterates in the same order. Only {@link #add} is overridden: {@code version} counts successful adds; any removal
 * (remove, clear, an iterator's remove, removeIf / removeAll / retainAll, which all go through them) lowers the size, so
 * {@code (version, size)} differs after every structural change and is the same only when the set is unchanged.
 */
public final class CellObjectSet extends HashSet<IsoMovingObject> {
   private static final long serialVersionUID = 1L;
   public int version;

   @Override
   public boolean add(IsoMovingObject e) {
      boolean added = super.add(e);
      if (added) {
         version++;
      }
      return added;
   }

   private static Field field;
   private static boolean failed;

   /** IsoCell's constructor (via IsoChunkMap's): the cell's objectList becomes a CellObjectSet while it is empty. */
   public static void install(IsoCell cell) {
      if (failed || cell == null || !Config.ANIMAL_LOS_WALK || !Overrides.enabled()) {
         return;
      }
      try {
         if (field == null) {
            field = IsoCell.class.getDeclaredField("objectList");
            field.setAccessible(true);
         }
         Object cur = field.get(cell);
         if (cur instanceof CellObjectSet || !(cur instanceof HashSet<?> set) || !set.isEmpty() || cur.getClass() != HashSet.class) {
            return;
         }
         field.set(cell, new CellObjectSet());
      } catch (Throwable t) {
         failed = true;
         Log.warn("animalLosWalk: cell object set not replaced (" + t + "); animals walk the stock set");
      }
   }

   /** The set as a CellObjectSet, or null (stock set, feature off). */
   public static CellObjectSet of(Set<IsoMovingObject> s) {
      return s instanceof CellObjectSet c ? c : null;
   }
}
