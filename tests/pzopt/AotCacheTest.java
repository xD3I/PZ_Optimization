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

      agents();
      cycleWithAgent(game);
      failedRecording(game);
      System.out.println("AotCacheTest: jar, record, use, reset, agents, failed recording ok");
   }

   /** A JVMTI or Java agent on the command line is named; a recording JVM exits for the cache only without one. */
   static void agents() {
      Check.check("-agentlib:zbNative".equals(AotCache.agent(java.util.List.of("-Xmx12288m", "-agentlib:zbNative", "-XX:+UseZGC"))), "-agentlib found");
      Check.check("-javaagent:pzmulticore-agent.jar".equals(AotCache.agent(java.util.List.of("-javaagent:pzmulticore-agent.jar=x"))), "-javaagent found, options cut");
      Check.check("-agentpath:C:/a/b.dll".equals(AotCache.agent(java.util.List.of("-agentpath:C:/a/b.dll=opt"))), "-agentpath found");
      Check.check(AotCache.agent(java.util.List.of("-Xmx3072m", "-Dzomboid.steam=1")) == null, "no agent");
      Check.check(AotCache.exitForCache(AotCache.Mode.RECORD, null), "recording without an agent exits for the cache");
      Check.check(!AotCache.exitForCache(AotCache.Mode.RECORD, "-agentlib:zbNative"), "recording with an agent skips the cache write");
      Check.check(!AotCache.exitForCache(AotCache.Mode.USE, null) && !AotCache.exitForCache(AotCache.Mode.LOOSE, null), "only a recording JVM");
   }

   /** With an agent loaded (ZombieBuddy's zbNative): never recorded, and a cache-form launcher goes back to loose. */
   static void cycleWithAgent(Path game) throws Exception {
      String agent = "-agentlib:zbNative";
      String loose = Files.readString(game.resolve("ProjectZomboid64.json"));
      AotCache.step(game, AotCache.Mode.LOOSE, agent, true);
      Check.check(Files.readString(game.resolve("ProjectZomboid64.json")).equals(loose), "agent: a loose launcher stays loose");
      AotCache.writeLauncher(game, "-XX:AOTCacheOutput=" + AotCache.CACHE);
      Files.writeString(game.resolve(AotCache.CACHE), "cache");
      AotCache.step(game, AotCache.Mode.RECORD, agent, true);
      JSONObject j = read(game);
      Check.check(!AotCache.isCacheForm(j) && j.getJSONArray("classpath").getString(0).equals("."), "agent: a recording launcher goes loose: " + j);
      Check.check(!Files.exists(game.resolve(AotCache.CACHE)), "agent: the unused cache is deleted");
      AotCache.writeLauncher(game, "-XX:AOTCache=" + AotCache.CACHE);
      AotCache.step(game, AotCache.Mode.USE, agent, true);
      Check.check(!AotCache.isCacheForm(read(game)), "agent: a use-form launcher goes loose");
   }

   /** A recording session that ended without a cache (the JVM crashed writing it) is not repeated for the same install. */
   static void failedRecording(Path game) throws Exception {
      Files.deleteIfExists(game.resolve(AotCache.STATE));
      AotCache.step(game, AotCache.Mode.LOOSE, null, true);
      Check.check(has(read(game), "-XX:AOTCacheOutput=" + AotCache.CACHE), "no agent: loose -> record");
      AotCache.step(game, AotCache.Mode.RECORD, null, true);
      Check.check(has(read(game), "-XX:AOTCache=" + AotCache.CACHE), "record -> use");
      // the recording JVM died in its cache writer: no cache, the use launch's JVM says so
      Files.deleteIfExists(game.resolve(AotCache.CACHE));
      Files.writeString(game.resolve(AotCache.LOG), "[0.010s][error][aot] An error has occurred while processing the AOT cache\n"
            + "[0.010s][error][aot] Specified AOT cache not found (pzopt/aot/pzopt.aot)\n");
      AotCache.step(game, AotCache.Mode.USE, null, true);
      Check.check(!AotCache.isCacheForm(read(game)), "failed recording: launcher back to loose, not recording again");
      AotCache.step(game, AotCache.Mode.LOOSE, null, true);
      Check.check(!AotCache.isCacheForm(read(game)), "failed recording: the next loose boot does not record again");
      // a new install (or Java) gets a new try
      Files.writeString(game.resolve("pzopt-installed.txt"), Files.readString(game.resolve("pzopt-installed.txt")) + "# reinstalled\n");
      AotCache.step(game, AotCache.Mode.LOOSE, null, true);
      Check.check(has(read(game), "-XX:AOTCacheOutput=" + AotCache.CACHE), "another install records again");
      // a cache the JVM rejected while the file is there is still re-recorded (game update, Java update)
      AotCache.step(game, AotCache.Mode.RECORD, null, true);
      Files.writeString(game.resolve(AotCache.CACHE), "cache");
      Files.writeString(game.resolve(AotCache.LOG), "[0.010s][info][aot] Using AOT-linked classes: false\n");
      AotCache.step(game, AotCache.Mode.USE, null, true);
      Check.check(has(read(game), "-XX:AOTCacheOutput=" + AotCache.CACHE), "a rejected cache is recorded again");
      AotCache.resetLauncher(game);
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
