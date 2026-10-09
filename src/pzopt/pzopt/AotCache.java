package pzopt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * aotCache: a JDK AOT cache (JEP 483 / 514 / 515 in the game's Java 25: the classes the game loads, already parsed and
 * linked, plus the method profiles of a whole session) so every launch starts with warm code. A world load is almost
 * entirely code that runs once per load; cold, the chunk recalc alone spent its first half-second in the interpreter
 * (2026-09-22: Continue -> world 2.64 -> 2.02 s and launch -> menu 5.4 -> 4.8 s with the cache).
 *
 * Constraints: the JVM refuses to write a cache while a non-empty directory is on the class path, and the launcher JSON
 * lists "." (the game folder, where the loose override classes live) first. So the cache mode runs the overrides from
 * pzopt/aot/pzopt.jar instead, built here from the files the installer recorded in pzopt-installed.txt. And HotSpot
 * writes the cache only on an orderly exit, which the native launcher never does: a recording session ends in
 * System.exit (onGameExit, from GameWindow.exit).
 *
 * The cycle, one step per launch, driven from the launcher JSON (ProjectZomboid64.json) that the native launcher reads:
 *   loose (as installed) -> build the jar, JSON "record": classpath [jar, game jar], -XX:AOTCacheOutput
 *   record               -> the cache is written at exit; JSON "use": -XX:AOTCache
 *   use                  -> nothing, unless the JVM's AOT log shows the cache was not used (game update, reinstall,
 *                           missing file) or the installed files changed: then back to record with a rebuilt jar.
 * The JSON is backed up once (ProjectZomboid64.json.pzopt-backup), written atomically and read back before it replaces
 * the original. Off (aotCache=false, the master switch, a build mismatch) or in a harness run, a JSON in the cache mode
 * is put back to the loose form. The installers (scripts/pzopt.sh, install.sh, install.ps1) and pzopt.Updater do the
 * same before they touch the loose files, so a stale jar can never shadow a newer install.
 */
public final class AotCache {
   static final String JAR = "pzopt/aot/pzopt.jar";
   static final String CACHE = "pzopt/aot/pzopt.aot";
   static final String LOG = "pzopt/aot/aot.log";
   static final String STATE = "pzopt/aot/state.properties";
   private static final String OPT_OUT = "-XX:AOTCacheOutput=" + CACHE;
   private static final String OPT_USE = "-XX:AOTCache=" + CACHE;
   private static final String OPT_LOG = "-Xlog:aot=info:file=" + LOG + "::filecount=0";

   enum Mode { LOOSE, RECORD, USE }

   private static final Mode MODE = detectMode();
   private static volatile boolean started;

   private AotCache() {
   }

   private static Mode detectMode() {
      try {
         for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-XX:AOTCacheOutput=") || a.equals("-XX:AOTMode=record")) {
               return Mode.RECORD;
            }
            if (a.startsWith("-XX:AOTCache=")) {
               return Mode.USE;
            }
         }
      } catch (Throwable ignored) {
      }
      return Mode.LOOSE;
   }

   /** From GameWindow.exit: a recording JVM exits through System.exit so it writes the cache. */
   public static void onGameExit() {
      if (MODE == Mode.RECORD) {
         Log.info("aot: recording session, exiting through System.exit so the JVM writes the AOT cache");
         System.exit(0);
      }
   }

   /** From boot (GameWindow.enter): decide the next launch's form on a daemon thread. */
   public static synchronized void start() {
      if (started || "1".equals(HarnessFlags.get("launcher_read_only"))) {
         return;
      }
      started = true;
      Thread t = new Thread(() -> {
         try {
            step();
         } catch (Throwable e) {
            Log.warn("aot: " + e);
         }
         GcChoice.step(); // the launcher's collector (gcMode), on this thread so the two JSON writers never overlap
      }, "pzopt-aot");
      t.setDaemon(true);
      thread = t;
      t.start();
   }

   private static volatile Thread thread;

   /** pzopt.Uninstall: waits (at most {@code ms}) for the boot's launcher-JSON writes, so none lands after its undo. */
   static void awaitBootWrites(long ms) {
      Thread t = thread;
      if (t != null) {
         try {
            t.join(ms);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   private static boolean wanted() {
      return Config.AOT_CACHE && Overrides.enabled() && (HarnessFlags.get("mode") == null || Config.DEV_AOT_CACHE_HARNESS);
   }

   static void step() throws Exception {
      Path game = Updater.gameDir();
      Path json = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(json)) {
         return; // macOS (Info.plist launcher) or an unknown layout: leave it alone
      }
      if (!wanted()) {
         if (resetLauncher(game)) {
            Log.info("aot: cache mode off; launcher back to the loose classes for the next launch");
         }
         return;
      }
      String fp = fingerprint(game);
      Properties state = readState(game);
      boolean sameInstall = fp.equals(state.getProperty("fingerprint"));
      switch (MODE) {
         case LOOSE -> {
            buildJar(game);
            writeLauncher(game, OPT_OUT);
            writeState(game, fp, "record");
            Log.info("aot: built " + JAR + "; the next launch records the AOT cache (written when the game quits)");
         }
         case RECORD -> {
            if (!sameInstall) {
               buildJar(game);
               writeLauncher(game, OPT_OUT);
               writeState(game, fp, "record");
               Log.info("aot: the install changed during a recording session; rebuilt the jar, recording again next launch");
            } else {
               writeLauncher(game, OPT_USE);
               writeState(game, fp, "use");
               Log.info("aot: recording this session; the next launch uses the cache");
            }
         }
         case USE -> {
            String log = readLog(game);
            boolean used = log.contains("Using AOT-linked classes: true") && !log.contains("Specified AOT cache not found");
            if (!used || !sameInstall) {
               Files.deleteIfExists(game.resolve(CACHE));
               if (!sameInstall) {
                  buildJar(game);
               }
               writeLauncher(game, OPT_OUT);
               writeState(game, fp, "record");
               Log.info("aot: cache " + (used ? "belongs to another install" : "not used by the JVM") + "; recording again next launch");
            } else {
               Log.info("aot: running from the AOT cache");
            }
         }
      }
   }

   /** Installed files + game jar + Java: a change in any of them means a new jar and a new cache. */
   static String fingerprint(Path game) throws Exception {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      Path manifest = game.resolve("pzopt-installed.txt");
      if (Files.isRegularFile(manifest)) {
         md.update(Files.readAllBytes(manifest));
      }
      Path gameJar = game.resolve("projectzomboid.jar");
      if (Files.isRegularFile(gameJar)) {
         md.update((Files.size(gameJar) + "|" + Files.getLastModifiedTime(gameJar).toMillis()).getBytes(StandardCharsets.UTF_8));
      }
      md.update(System.getProperty("java.vm.version", "").getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) {
         sb.append(String.format("%02x", b));
      }
      return sb.toString();
   }

   /** The installed class files (and pzopt's resources) from pzopt-installed.txt, not media or natives. */
   static List<String> jarEntries(Path game) throws IOException {
      List<String> out = new ArrayList<>();
      Path manifest = game.resolve("pzopt-installed.txt");
      for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
         if (line.isBlank() || line.startsWith("#")) {
            continue;
         }
         String rel = line.split(" ")[0];
         if (rel.startsWith("media/") || rel.startsWith("natives/")) {
            continue;
         }
         out.add(rel);
      }
      return out;
   }

   static void buildJar(Path game) throws IOException {
      Path jar = game.resolve(JAR);
      Files.createDirectories(jar.getParent());
      Path tmp = jar.resolveSibling("pzopt.jar.tmp");
      try (OutputStream fos = Files.newOutputStream(tmp); JarOutputStream out = new JarOutputStream(fos)) {
         for (String rel : jarEntries(game)) {
            Path f = game.resolve(rel);
            if (!Files.isRegularFile(f)) {
               throw new IOException("installed file missing: " + rel);
            }
            JarEntry e = new JarEntry(rel.replace('\\', '/'));
            e.setTime(Files.getLastModifiedTime(f).toMillis());
            out.putNextEntry(e);
            try (InputStream in = Files.newInputStream(f)) {
               in.transferTo(out);
            }
            out.closeEntry();
         }
      }
      Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
   }

   /** Set the launcher to the cache form with the given AOT option (record or use). */
   static void writeLauncher(Path game, String aotOption) throws IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
      stripAot(j);
      JSONArray cp = j.getJSONArray("classpath");
      JSONArray ncp = new JSONArray();
      ncp.put(JAR);
      for (int i = 0; i < cp.length(); i++) {
         String e = cp.getString(i);
         if (!e.equals(".") && !e.equals(JAR)) {
            ncp.put(e);
         }
      }
      j.put("classpath", ncp);
      forEachVmArgs(j, args -> {
         args.put(aotOption);
         args.put(OPT_LOG);
      });
      save(game, j);
   }

   /** Put a cache-form launcher back to the loose form ("." first, no AOT options); false if it was loose already. */
   public static boolean resetLauncher(Path game) throws IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      if (!Files.isRegularFile(json)) {
         return false;
      }
      JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
      if (!isCacheForm(j)) {
         return false;
      }
      stripAot(j);
      JSONArray cp = j.getJSONArray("classpath");
      JSONArray ncp = new JSONArray();
      ncp.put(".");
      for (int i = 0; i < cp.length(); i++) {
         String e = cp.getString(i);
         if (!e.equals(".") && !e.equals(JAR)) {
            ncp.put(e);
         }
      }
      j.put("classpath", ncp);
      save(game, j);
      return true;
   }

   /** pzopt.Updater, before it replaces the loose files: loose launcher, no jar, no cache. */
   public static void onInstallChanging(Path game) {
      try {
         resetLauncher(game);
         Files.deleteIfExists(game.resolve(JAR));
         Files.deleteIfExists(game.resolve(CACHE));
         Files.deleteIfExists(game.resolve(STATE));
      } catch (Throwable t) {
         Log.warn("aot: reset before update: " + t);
      }
   }

   static boolean isCacheForm(JSONObject j) {
      JSONArray cp = j.optJSONArray("classpath");
      if (cp != null) {
         for (int i = 0; i < cp.length(); i++) {
            if (JAR.equals(cp.optString(i))) {
               return true;
            }
         }
      }
      boolean[] found = {false};
      forEachVmArgs(j, args -> {
         for (int i = 0; i < args.length(); i++) {
            if (isAotArg(args.optString(i))) {
               found[0] = true;
            }
         }
      });
      return found[0];
   }

   private static boolean isAotArg(String a) {
      return a.startsWith("-XX:AOTCache") || a.equals(OPT_LOG);
   }

   private static void stripAot(JSONObject j) {
      forEachVmArgs(j, args -> {
         for (int i = args.length() - 1; i >= 0; i--) {
            if (isAotArg(args.optString(i))) {
               args.remove(i);
            }
         }
      });
   }

   /** The top-level vmArgs; per-platform sections ("windows": {"vmArgs": ...}) are left as they are. */
   private static void forEachVmArgs(JSONObject j, java.util.function.Consumer<JSONArray> f) {
      JSONArray top = j.optJSONArray("vmArgs");
      if (top != null) {
         f.accept(top);
      }
   }

   private static void save(Path game, JSONObject j) throws IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      Path backup = game.resolve("ProjectZomboid64.json.pzopt-backup");
      if (!Files.exists(backup)) {
         Files.copy(json, backup);
      }
      String text = j.toString(1);
      Path tmp = game.resolve("ProjectZomboid64.json.pzopt-tmp");
      Files.writeString(tmp, text + "\n", StandardCharsets.UTF_8);
      JSONObject check = new JSONObject(Files.readString(tmp, StandardCharsets.UTF_8));
      if (!check.has("mainClass") || check.getJSONArray("classpath").length() == 0 || check.getJSONArray("vmArgs").length() == 0) {
         Files.deleteIfExists(tmp);
         throw new IOException("launcher JSON check failed; left unchanged");
      }
      Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
   }

   private static Properties readState(Path game) {
      Properties p = new Properties();
      Path f = game.resolve(STATE);
      if (Files.isRegularFile(f)) {
         try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
         } catch (IOException ignored) {
         }
      }
      return p;
   }

   private static void writeState(Path game, String fp, String phase) throws IOException {
      Properties p = new Properties();
      p.setProperty("fingerprint", fp);
      p.setProperty("phase", phase);
      Path f = game.resolve(STATE);
      Files.createDirectories(f.getParent());
      try (OutputStream out = Files.newOutputStream(f)) {
         p.store(out, "pzopt AOT cache state (pzopt.AotCache)");
      }
   }

   private static String readLog(Path game) {
      try {
         return Files.readString(game.resolve(LOG), StandardCharsets.ISO_8859_1);
      } catch (IOException e) {
         return "";
      }
   }
}
