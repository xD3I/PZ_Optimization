package pzopt;

/**
 * luaStringFast: Kahlua's pattern matcher (StringLib) allocates its position objects plainly instead of through the
 * game's synchronized ObjectPool. Not final so LuaStringFastTest can compare both paths in one JVM.
 *
 * <p>The one answer that can differ: a back-reference past the pattern's open captures ({@code %1} with no capture;
 * real Lua raises "invalid capture index"). Kahlua's capture check is inverted there and reads the capture a pooled
 * MatchState kept from an earlier call, whose pooled position object a later call may have reused, so stock's answer
 * depends on what string calls ran before (two stock runs with different call histories differ too). Neither the game's
 * Lua nor the 54-mod list has a back-reference in a pattern (2026-10-26).
 */
public final class LuaStringFast {
   public static boolean on = Config.LUA_STRING_FAST;

   /** on, and in the "on" half of an in-run luaJit A/B (devGtAlternateKeys=luaJit flips the string paths too). */
   public static boolean on() {
      return on && !(LuaJit.AB && !ZombAb.on(ZombAb.LUA_JIT));
   }

   private LuaStringFast() {
   }
}
