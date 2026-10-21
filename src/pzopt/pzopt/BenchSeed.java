package pzopt;

/**
 * Technique 2: the game's random numbers seeded at fixed points of a benchmark pass (world creation, world ready, route
 * start), so zombie spawns, parked cars, wandering and animation variants start from the same sequence every pass.
 * The game's generators ({@code RandStandard}, {@code RandLua}) keep their {@code CellularAutomatonRNG} in a protected
 * field; it is replaced by one built from a fixed seed (the stock class, so the cost per number is unchanged). The world
 * generator's own seed is set by the Lua (WorldGenParams seed string) before createWorld. Multithreaded chunk loading
 * still draws in a varying order, so passes are close, not bit-identical; the passes' spread is reported.
 */
public final class BenchSeed {
   private static java.lang.reflect.Field field;

   private BenchSeed() {
   }

   static void seed(String point) {
      long base = 0x5A0B7E57L ^ point.hashCode();
      try {
         if (field == null) {
            field = Class.forName("zombie.core.random.RandAbstract").getDeclaredField("rand");
            field.setAccessible(true);
         }
         set("zombie.core.random.RandStandard", base);
         set("zombie.core.random.RandLua", base * 31 + 7);
         Log.info("benchmark: random numbers seeded at " + point);
      } catch (Throwable t) {
         Log.warn("benchmark: could not seed the game's random numbers (" + point + "): " + t);
      }
   }

   private static void set(String cls, long seed) throws Exception {
      Object inst = Class.forName(cls).getField("INSTANCE").get(null);
      byte[] b = new byte[4];
      for (int i = 0; i < 4; i++) {
         b[i] = (byte)(seed >>> (8 * i));
      }
      Object rng;
      try {
         rng = Class.forName("org.uncommons.maths.random.CellularAutomatonRNG").getConstructor(byte[].class).newInstance((Object)b);
      } catch (ReflectiveOperationException e) {
         rng = new java.util.Random(seed);
      }
      field.set(inst, rng);
   }
}
