package pzopt;

/**
 * LuaJitDiffTest with the in-game differential rig on for every compiled entry (devLuaJitDual=1): the rig must not
 * change any result, error or report (the shadow's writes undone, its reports suppressed) and must find no
 * difference between the compiled shadow and the interpreted run; with the self-test property it must report every
 * sample whose first result it corrupts.
 */
public final class LuaJitDualTest {
   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.devLuaJitDual", "1");
      LuaJitDiffTest.main(args);
      Check.check(LuaJitDual.compared.get() > 500, "devLuaJitDual compared only " + LuaJitDual.compared.get());
      System.out.println("LuaJitDualTest: " + LuaJitDual.summary().trim());
   }
}
