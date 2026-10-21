package pzopt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Predicate;

import zombie.vehicles.BaseVehicle;

/**
 * {@code IsoChunk.vehicles} as a list that reports its structural changes ({@code nearVehicleGrid}, 2026-10-13).
 *
 * <p>{@link VehicleNear} answers "no chunk round this character holds a vehicle" from a per-frame grid of the chunk map;
 * the grid is valid only while no chunk's vehicle list changed size. Every mutator that can change the size bumps
 * {@link VehicleNear#changed()} before and after the change (before: a reader that rebuilds while the change is half done
 * sees the counter move again afterwards). The list behaves exactly like {@code ArrayList} otherwise; the iterator's and
 * the sub-list's removals go through these methods ({@code remove(int)}, {@code add(int, E)}, {@code removeRange}).
 */
public final class VehicleList extends ArrayList<BaseVehicle> {
   private static final long serialVersionUID = 1L;

   public VehicleList() {
      super();
   }

   @Override
   public boolean add(BaseVehicle v) {
      VehicleNear.changed();
      try {
         return super.add(v);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public void add(int index, BaseVehicle v) {
      VehicleNear.changed();
      try {
         super.add(index, v);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean addAll(Collection<? extends BaseVehicle> c) {
      VehicleNear.changed();
      try {
         return super.addAll(c);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean addAll(int index, Collection<? extends BaseVehicle> c) {
      VehicleNear.changed();
      try {
         return super.addAll(index, c);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public BaseVehicle remove(int index) {
      VehicleNear.changed();
      try {
         return super.remove(index);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean remove(Object o) {
      VehicleNear.changed();
      try {
         return super.remove(o);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean removeAll(Collection<?> c) {
      VehicleNear.changed();
      try {
         return super.removeAll(c);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean retainAll(Collection<?> c) {
      VehicleNear.changed();
      try {
         return super.retainAll(c);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public boolean removeIf(Predicate<? super BaseVehicle> filter) {
      VehicleNear.changed();
      try {
         return super.removeIf(filter);
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   public void clear() {
      VehicleNear.changed();
      try {
         super.clear();
      } finally {
         VehicleNear.changed();
      }
   }

   @Override
   protected void removeRange(int fromIndex, int toIndex) {
      VehicleNear.changed();
      try {
         super.removeRange(fromIndex, toIndex);
      } finally {
         VehicleNear.changed();
      }
   }
}
