package pzopt;

/**
 * luaJitBoxAlloc: how compiled Lua code (luaJit) and the generated Java calls (luaJavaFast) box a number.
 *
 * <p>Stock boxes every number through {@code BoxedStaticValues.toDouble}: integers and halves in +-10000 come from four
 * 10,000-entry arrays of shared Doubles (160 KB of references, 640 KB of objects), so boxing a loop counter or an
 * arithmetic result is a range test, an abs, two int conversions and a load from a table that is rarely in the cache,
 * and the JIT can never remove the box (a load from a static array escapes). Here a small integer (0..1023: loop
 * counters, indices, counts) comes from a 1024-entry table of stock's own objects, which stays in the cache, and any
 * other number is a fresh Double (a TLAB bump; C2 removes it when the compiled body unboxes it again).
 *
 * <p>Same value as stock for every input: the cache only changed identity, never the value, except -0.0, which
 * stock turns into +0.0 (-0.0 is inside its range and integral); {@code d + 0.0} does the same (IEEE: -0.0 + 0.0 =
 * +0.0, every other value unchanged, NaN stays NaN). Lua compares numbers by value (BaseLib.luaEquals, table keys by
 * Double.equals), so the identity is not observable from Lua.
 */
public final class LuaBox {
   private LuaBox() {
   }

   public static final boolean ALLOC = Config.LUA_JIT_BOX_ALLOC;

   /** Integers 0..1023, the very objects BoxedStaticValues hands out (a 20 KB table that stays in the cache). */
   static final Double[] SMALL = new Double[1024];

   static {
      for (int i = 0; i < SMALL.length; i++) {
         SMALL[i] = zombie.core.BoxedStaticValues.toDouble(i);
      }
   }

   /**
    * luaJitBoxPlain: every number a fresh Double (no table): one allocation and nothing to merge with, so C2's escape
    * analysis can remove a box that never leaves the compiled code it was inlined into (an argument a Java call unboxes
    * again, a result the Lua code only computes with).
    */
   static final boolean PLAIN = Config.LUA_JIT_BOX_PLAIN;

   public static Double box(double d) {
      if (PLAIN) {
         return Double.valueOf(d + 0.0);
      }
      int i = (int)d;
      if (i == d && (i & ~1023) == 0) {
         return SMALL[i]; // -0.0 too: (int) -0.0 == 0 == -0.0, and SMALL[0] is +0.0 as stock's
      }
      return Double.valueOf(d + 0.0);
   }
}
