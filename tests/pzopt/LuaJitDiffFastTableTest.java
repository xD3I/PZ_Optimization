package pzopt;

/**
 * LuaJitDiffTest with luaFastTable on (the game's setting beside the compiler): the compiled code's LuaMap paths (the
 * position caches of LuaJit.getStr / setStr, the metatable-miss cache luaJitMissCache) against the interpreter.
 */
public final class LuaJitDiffFastTableTest {
   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.luaFastTable", "true");
      LuaJitDiffTest.main(args);
   }
}
