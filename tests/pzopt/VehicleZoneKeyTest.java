package pzopt;

import java.util.ArrayList;
import java.util.Random;
import zombie.core.random.RandStandard;
import zombie.iso.IsoMetaGrid;
import zombie.iso.zones.VehicleZone;

/** The optimized vehicle-zone dedupe must retain exactly the same zone objects as stock. */
public class VehicleZoneKeyTest {
   private static int checks;

   private static VehicleZone zone(int x, int y, int z, int width, int height) {
      return new VehicleZone("zone", "Vehicles", x, y, z, width, height, null);
   }

   private static void verify(String name, VehicleZone... input) {
      ArrayList<VehicleZone> expected = new ArrayList<>();
      for (VehicleZone candidate : input) {
         boolean duplicate = false;
         for (VehicleZone previous : expected) {
            if (candidate.getX() == previous.getX() && candidate.getY() == previous.getY()
                  && candidate.w == previous.w && candidate.h == previous.h) {
               duplicate = true;
               break;
            }
         }
         if (!duplicate) expected.add(candidate);
      }
      IsoMetaGrid grid = new IsoMetaGrid();
      java.util.Collections.addAll(grid.vehiclesZones, input);
      grid.checkVehiclesZones();
      Check.check(grid.vehiclesZones.size() == expected.size(), name + ": expected "
            + expected.size() + " zones, got " + grid.vehiclesZones.size());
      checks++;
      for (int i = 0; i < expected.size(); i++) {
         Check.check(grid.vehiclesZones.get(i) == expected.get(i), name + ": first occurrence/order at " + i);
         checks++;
      }
   }

   public static void main(String[] args) {
      System.setProperty("pzopt.loaderCpuFixes", "true");
      RandStandard.INSTANCE.init();
      Check.check(Config.LOADER_CPU_FIXES && Overrides.enabled(), "optimized path is enabled");
      verify("x high bits", zone(0, 0, 0, 8, 8), zone(65536, 0, 0, 8, 8));
      verify("y high bits", zone(0, 0, 0, 8, 8), zone(0, 65536, 0, 8, 8));
      verify("width high bits", zone(0, 0, 0, 8, 8), zone(0, 0, 0, 65544, 8));
      verify("height high bits", zone(0, 0, 0, 8, 8), zone(0, 0, 0, 8, 65544));
      verify("signed x", zone(-1, 0, 0, 8, 8), zone(65535, 0, 0, 8, 8));
      verify("signed y", zone(0, -1, 0, 8, 8), zone(0, 65535, 0, 8, 8));
      verify("extreme x", zone(Integer.MIN_VALUE, 0, 0, 8, 8), zone(0, 0, 0, 8, 8),
            zone(Integer.MAX_VALUE, 0, 0, 8, 8), zone(65535, 0, 0, 8, 8));
      verify("extreme y", zone(0, Integer.MIN_VALUE, 0, 8, 8), zone(0, 0, 0, 8, 8),
            zone(0, Integer.MAX_VALUE, 0, 8, 8), zone(0, 65535, 0, 8, 8));
      // These unequal records have the same generated hash code (1 * 31 + 32 == 2 * 31 + 1).
      verify("ordinary hash collision", zone(0, 0, 0, 1, 32), zone(0, 0, 0, 2, 1));
      VehicleZone first = zone(12, 34, 0, 8, 8);
      VehicleZone duplicate = zone(12, 34, 0, 8, 8);
      duplicate.name = "different name";
      duplicate.type = "different type";
      verify("exact duplicate retains first", first, duplicate, first);
      verify("stock ignores z", first, zone(12, 34, 1, 8, 8));
      verify("empty");
      verify("single", first);

      Random random = new Random(65536);
      ArrayList<VehicleZone> mixed = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
         int x = random.nextInt(), y = random.nextInt();
         mixed.add(zone(x, y, 0, 8, 8));
         mixed.add(zone(x ^ 0x10000, y, 0, 8, 8));
         mixed.add(zone(x, y ^ 0x10000, 0, 8, 8));
         mixed.add(zone(x, y, 1, 8, 8));
      }
      verify("mixed full-width coordinates and real duplicates", mixed.toArray(VehicleZone[]::new));
      System.out.println("VehicleZoneKeyTest: " + checks + " size/identity/order checks match stock");
   }
}
