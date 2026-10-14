package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * pzopt.AotCache's file side: the jar holds exactly the installed class files (no media / natives), the launcher JSON
 * goes loose -> record -> use -> loose with every other key and argument unchanged, and a loose JSON is left alone.
 */
public class AotCacheTest {
   private static final String STOCK = "{\n\t\"mainClass\": \"zombie/gameStates/MainScreenState\",\n\t\"classpath\": [\n\t\t\".\",\n"
         + "\t\t\"projectzomboid.jar\"\n\t],\n\t\"vmArgs\": [\n\t\t\"-Xms4096m\",\n\t\t\"-Djava.library.path=natives/\",\n"
         + "\t\t\"-XX:+UseG1GC\"\n\t]\n}\n";

   public static void main(String[] args) throws Exception {
      Path game = Files.createTempDirectory("pzopt-aot-test");
      Files.writeString(game.resolve("ProjectZomboid64.json"), STOCK);
      Files.createDirectories(game.resolve("zombie/iso"));
      Files.createDirectories(game.resolve("pzopt"));
      Files.createDirectories(game.resolve("media/lua/client/pzopt"));
      Files.writeString(game.resolve("zombie/iso/IsoWorld.class"), "a");
      Files.writeString(game.resolve("pzopt/AotCache.class"), "b");
      Files.writeString(game.resolve("pzopt/build-info.properties"), "c");
      Files.writeString(game.resolve("media/lua/client/pzopt/x.lua"), "d");
      Files.writeString(game.resolve("pzopt-installed.txt"), "# files written\n# revision=x\n"
            + "media/lua/client/pzopt/x.lua 1\npzopt/AotCache.class 2\npzopt/build-info.properties 3\nzombie/iso/IsoWorld.class 4\n");

      AotCache.buildJar(game);
      try (JarFile jf = new JarFile(game.resolve(AotCache.JAR).toFile())) {
         Check.check(jf.getEntry("zombie/iso/IsoWorld.class") != null, "jar holds the override class");
         Check.check(jf.getEntry("pzopt/build-info.properties") != null, "jar holds pzopt's resource");
         Check.check(jf.getEntry("media/lua/client/pzopt/x.lua") == null, "jar leaves media out");
         Check.check(jf.size() == 3, "jar has exactly the three class-path files, has " + jf.size());
      }

      // another mod's loose classes (Better Car Physics copies zombie/ into the game folder): in the jar and the fingerprint
      String fp = AotCache.fingerprint(game);
      Files.createDirectories(game.resolve("zombie/vehicles"));
      Files.createDirectories(game.resolve("jre64/lib"));
      Files.writeString(game.resolve("zombie/vehicles/BaseVehicle.class"), "e");
      Files.writeString(game.resolve("zombie/iso/IsoWorld$1.class"), "f");
      Files.writeString(game.resolve("jre64/lib/Skip.class"), "g");
      Files.writeString(game.resolve("media/lua/client/pzopt/Skip.class"), "h");
      Check.check(AotCache.foreignClasses(game).equals(java.util.List.of("zombie/iso/IsoWorld$1.class", "zombie/vehicles/BaseVehicle.class")),
            "foreign classes: " + AotCache.foreignClasses(game));
      Check.check(!AotCache.fingerprint(game).equals(fp), "a copied mod class changes the fingerprint");
      AotCache.buildJar(game);
      try (JarFile jf = new JarFile(game.resolve(AotCache.JAR).toFile())) {
         Check.check(jf.getEntry("zombie/vehicles/BaseVehicle.class") != null, "jar holds the other mod's class");
         Check.check(jf.size() == 5, "jar has the three installed files and the two foreign classes, has " + jf.size());
      }

      Check.check(!AotCache.resetLauncher(game), "a loose launcher is left alone");
      AotCache.writeLauncher(game, "-XX:AOTCacheOutput=" + AotCache.CACHE);
      JSONObject j = read(game);
      JSONArray cp = j.getJSONArray("classpath");
      Check.check(cp.length() == 2 && cp.getString(0).equals(AotCache.JAR) && cp.getString(1).equals("projectzomboid.jar"),
            "record classpath is [jar, game jar]: " + cp);
      Check.check(has(j, "-XX:AOTCacheOutput=" + AotCache.CACHE) && has(j, "-Xlog:aot=info:file=" + AotCache.LOG + "::filecount=0"), "record options");
      Check.check(has(j, "-Xms4096m") && has(j, "-Djava.library.path=natives/") && has(j, "-XX:+UseG1GC"), "stock options kept");
      Check.check(j.getString("mainClass").equals("zombie/gameStates/MainScreenState"), "mainClass kept");
      Check.check(Files.exists(game.resolve("ProjectZomboid64.json.pzopt-backup")), "backup written");
      Check.check(Files.readString(game.resolve("ProjectZomboid64.json.pzopt-backup")).equals(STOCK), "backup is the original");

      AotCache.writeLauncher(game, "-XX:AOTCache=" + AotCache.CACHE);
      j = read(game);
      Check.check(has(j, "-XX:AOTCache=" + AotCache.CACHE) && !has(j, "-XX:AOTCacheOutput=" + AotCache.CACHE), "use replaces record");
      Check.check(j.getJSONArray("vmArgs").length() == 5, "no duplicated options: " + j.getJSONArray("vmArgs"));
      Check.check(j.getJSONArray("classpath").length() == 2, "no duplicated jar");

      Check.check(AotCache.resetLauncher(game), "cache form is reset");
      j = read(game);
      JSONObject stock = new JSONObject(STOCK);
      Check.check(j.getJSONArray("classpath").similar(stock.getJSONArray("classpath")), "loose classpath back: " + j.getJSONArray("classpath"));
      Check.check(j.getJSONArray("vmArgs").similar(stock.getJSONArray("vmArgs")), "stock options back: " + j.getJSONArray("vmArgs"));
      // a recording that leaves no cache (the JVM crashing while it writes one) is counted; two in a row for the same install end the cycle
      Check.check(AotCache.failuresAfter(false, true, 0) == 1, "a cache not used for the same install is one failed recording");
      Check.check(AotCache.failuresAfter(false, true, 1) == 2, "failures accumulate");
      Check.check(AotCache.failuresAfter(true, true, 1) == 0, "a used cache clears the count");
      Check.check(AotCache.failuresAfter(false, false, 5) == 0, "another install starts over");
      Check.check(!AotCache.giveUp(1) && AotCache.giveUp(AotCache.MAX_FAILED), "gives up at MAX_FAILED, not before");
      AotCache.writeState(game, "fp1", AotCache.PHASE_OFF, 2);
      java.util.Properties st = AotCache.readState(game);
      Check.check("fp1".equals(st.getProperty("fingerprint")) && "off".equals(st.getProperty("phase")) && AotCache.failures(st) == 2,
            "state round trip: " + st);
      java.util.Properties old = new java.util.Properties(); // a state file written before the counter existed
      old.setProperty("phase", "record");
      Check.check(AotCache.failures(old) == 0, "no failures key reads 0");
      old.setProperty("failures", "x");
      Check.check(AotCache.failures(old) == 0, "a garbled failures value reads 0");

      System.out.println("AotCacheTest: jar, record, use, reset, crash-loop guard ok");
   }

   private static JSONObject read(Path game) throws Exception {
      return new JSONObject(Files.readString(game.resolve("ProjectZomboid64.json"), StandardCharsets.UTF_8));
   }

   private static boolean has(JSONObject j, String arg) {
      JSONArray a = j.getJSONArray("vmArgs");
      for (int i = 0; i < a.length(); i++) {
         if (a.getString(i).equals(arg)) {
            return true;
         }
      }
      return false;
   }
}
