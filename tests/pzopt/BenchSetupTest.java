package pzopt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The built-in benchmark's run setups: setup.txt read back, the history summary, two runs compared. */
public class BenchSetupTest {
   static String[] row(List<String[]> rows, String section, String key) {
      for (String[] r : rows) {
         if (r[1].equals(section) && r[2].equals(key)) {
            return r;
         }
      }
      return null;
   }

   public static void main(String[] args) throws Exception {
      java.nio.file.Path home = java.nio.file.Files.createTempDirectory("pzopt-setup-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid

      String a = "20261018-100000", b = "20261018-110000";
      BenchPlan.runDir(a).mkdirs();
      BenchPlan.runDir(b).mkdirs();
      java.nio.file.Files.writeString(new java.io.File(BenchPlan.runDir(a), BenchSetup.FILE).toPath(),
            "R\tmode\tab\nR\tpasses\t2\nO\tsunShadows\ttrue\tfalse\nG\tvsync\ttrue\nG\twidth\t2560\nM\tmodA\tMod A\t1.0\nM\tmodB\tMod B\t\n");
      java.nio.file.Files.writeString(new java.io.File(BenchPlan.runDir(b), BenchSetup.FILE).toPath(),
            "R\tmode\tab\nR\tpasses\t3\nO\tgodRays\ttrue\tfalse\nG\tvsync\tfalse\nG\twidth\t2560\nM\tmodA\tMod A\t1.1\nJ\tsomeagent.jar\n");

      Check.check(BenchSetup.read("20261018-090000") == null, "a run without setup.txt reads null");
      Check.check(BenchSetup.summary(a).equals("1 setting changed, 2 mods"), "summary a: " + BenchSetup.summary(a));
      Check.check(BenchSetup.summary(b).equals("1 setting changed, 1 mod, 1 Java mod"), "summary b: " + BenchSetup.summary(b));
      Check.check(BenchSetup.summary("20261018-090000").isEmpty(), "no summary without a setup");

      Map<String, String> sysA = new LinkedHashMap<>(), sysB = new LinkedHashMap<>();
      sysA.put("gpu", "RTX 4090");
      sysB.put("gpu", "RTX 4090");
      sysA.put("gl", "4.6 580.1");
      sysB.put("gl", "4.6 580.2");
      List<String[]> rows = BenchSetup.compare(BenchSetup.read(a), BenchSetup.read(b), sysA, sysB);
      String[] passes = row(rows, "run", "passes");
      Check.check(passes != null && passes[4].equals("2") && passes[5].equals("3") && passes[6].equals("0"), "passes differ");
      String[] mode = row(rows, "run", "mode");
      Check.check(mode != null && mode[6].equals("1"), "mode the same");
      String[] sun = row(rows, "opt", "sunShadows"), god = row(rows, "opt", "godRays");
      Check.check(sun != null && sun[4].equals("true") && sun[5].equals("false (default)"), "a setting changed in one run only reads its default in the other: " + String.join("|", sun));
      Check.check(god != null && god[4].equals("false (default)") && god[5].equals("true"), "and the other way round");
      String[] vsync = row(rows, "game", "vsync"), width = row(rows, "game", "width");
      Check.check(vsync != null && vsync[3].equals("Vsync") && vsync[6].equals("0") && width[6].equals("1"), "game settings compared with their names");
      String[] modA = row(rows, "mods", "modA"), modB = row(rows, "mods", "modB"), agent = row(rows, "mods", "java:someagent.jar");
      Check.check(modA != null && modA[4].equals("on (1.0)") && modA[5].equals("on (1.1)") && modA[6].equals("0"), "a mod's version change shows");
      Check.check(modB != null && modB[4].equals("on") && modB[5].equals("off"), "a mod removed shows off");
      Check.check(agent != null && agent[4].equals("off") && agent[5].equals("on"), "a Java agent added shows");
      String[] gl = row(rows, "sys", "gl"), gpu = row(rows, "sys", "gpu");
      Check.check(gl != null && gl[6].equals("0") && gpu[6].equals("1"), "driver change shows, same GPU does not");

      List<String[]> old = BenchSetup.compare(null, BenchSetup.read(b), sysA, sysB);
      String[] oldVsync = row(old, "game", "vsync");
      Check.check(oldVsync != null && oldVsync[4].equals("not recorded") && oldVsync[5].equals("false"), "an older run reads not recorded");
      System.out.println("BenchSetupTest ok");
   }
}
