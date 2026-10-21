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
      Check.check(j.getJSONArray("vmArgs").length() == 5 + (pzopt.Config.AOT_NO_INDY ? 2 : 0), "no duplicated options: " + j.getJSONArray("vmArgs"));
      // aotNoIndy (on by default since 2026-10-15): the unlock + -AOTInvokeDynamicLinking pair, in that order, in the cache form
      JSONArray va = j.getJSONArray("vmArgs");
      int ni = va.toList().indexOf(AotCache.OPT_NO_INDY);
      Check.check(!pzopt.Config.AOT_NO_INDY || ni > 0 && AotCache.OPT_UNLOCK_DIAG.equals(va.getString(ni - 1)), "aotNoIndy pair written: " + va);
      Check.check(j.getJSONArray("classpath").length() == 2, "no duplicated jar");

      Check.check(AotCache.resetLauncher(game), "cache form is reset");
      j = read(game);
      JSONObject stock = new JSONObject(STOCK);
      Check.check(j.getJSONArray("classpath").similar(stock.getJSONArray("classpath")), "loose classpath back: " + j.getJSONArray("classpath"));
      Check.check(j.getJSONArray("vmArgs").similar(stock.getJSONArray("vmArgs")), "stock options back: " + j.getJSONArray("vmArgs"));

      // aotNoIndy (f9-burst): its unlock + -AOTInvokeDynamicLinking pair goes with the reset
      JSONObject k = new JSONObject(STOCK);
      k.getJSONArray("vmArgs").put(AotCache.OPT_UNLOCK_DIAG).put(AotCache.OPT_NO_INDY).put("-XX:AOTCache=" + AotCache.CACHE);
      Files.writeString(game.resolve("ProjectZomboid64.json"), k.toString(), StandardCharsets.UTF_8);
      Check.check(AotCache.resetLauncher(game), "aotNoIndy form is reset");
      j = read(game);
      Check.check(j.getJSONArray("vmArgs").similar(stock.getJSONArray("vmArgs")), "aotNoIndy pair stripped: " + j.getJSONArray("vmArgs"));
      System.out.println("AotCacheTest: jar, record, use, reset ok");
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
