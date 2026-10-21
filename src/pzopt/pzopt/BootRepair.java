package pzopt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Boot repair (2026-10-07): the first thing MainScreenState.main does, before any game code runs.
 *
 * The loose classes always shadow the jar's (the stock launcher lists "." first), so an install the game can no longer
 * run stays in charge: Steam's "Verify integrity" never removes files it did not install, and the in-game Uninstall is
 * out of reach when the game dies at start. Two cases are fixed here, then the game is started again:
 * <ul>
 * <li>Build mismatch (a game update, e.g. 42.20 to 42.21): the overrides' own stock paths were compiled against the old
 * jar and crash on the new one (NoSuchMethodError on ZomboidFileSystem.getModIDs, Workshop reports of 2026-09-28 /
 * 10-04). The installed files go; when the Steam Workshop copy is a build for this game whose stock-class hashes match
 * the jar, it is installed in their place, else the next start is the stock game and the Workshop item's install
 * helper says why.</li>
 * <li>An uninstall the helper did not finish (pressed in the menu, files still there at the next start: the helper's
 * list Zomboid/pzopt/uninstall-files.txt still exists): the files go now.</li>
 * </ul>
 * Developer installs (manifest written by scripts/pzopt.sh) are left alone on a mismatch; -Dpzopt.bootRepair=false
 * turns it off. A process that Restart started never repairs again (no loop).
 *
 * Everything that comes from the old install (Uninstall's plan, the Workshop copy lookup, the launcher JSON undo,
 * Restart) is loaded before the first file changes; the file work itself uses only the JDK. Logged to
 * Zomboid/pzopt/boot-repair.log; Zomboid/Lua/pzopt-boot-repair.txt tells the install helper (Lua reads only Zomboid/Lua/).
 */
public final class BootRepair {
   static final String LOG_NAME = "boot-repair.log";
   static final String NOTICE_NAME = "pzopt-boot-repair.txt";
   /** How long a fresh uninstall list may belong to a helper that is still working (it starts when the old game ends). */
   static final long HELPER_GRACE_MS = 20_000;

   private BootRepair() {
   }

   /** True when the installed files were removed or replaced: main must end now (the game is being started again). */
   public static boolean run(String[] args) {
      Path zomboid = zomboidDir(args);
      Path base = zomboid.resolve("pzopt");
      boolean touched = false;
      try {
         // the normal start: two cheap checks, nothing else loaded or read
         Path pending = base.resolve(Uninstall.FILES_NAME);
         boolean mismatch = !Overrides.buildMatches();
         boolean uninstall = Files.isRegularFile(pending);
         if (!mismatch && !uninstall) {
            return false;
         }
         Path game = Updater.gameDir();
         if (uninstall && !pendingIsCurrent(pending, game.resolve(Updater.MANIFEST))) {
            // the list of an uninstall that finished long ago, and this is a newer install: not this install's request
            Files.deleteIfExists(pending);
            Files.deleteIfExists(base.resolve(Uninstall.DIRS_NAME));
            uninstall = false;
            if (!mismatch) {
               return false;
            }
         }
         if ("false".equalsIgnoreCase(System.getProperty("pzopt.bootRepair"))) {
            log(base, "skipped (-Dpzopt.bootRepair=false): " + (mismatch ? "build mismatch" : "pending uninstall"));
            return false;
         }
         if (Restart.restarted()) {
            log(base, "skipped: this process was started by a restart (no second repair in a row)");
            return false;
         }
         String header = firstLine(game.resolve(Updater.MANIFEST));
         if (!uninstall && header.contains("scripts/pzopt.sh")) {
            log(base, "build mismatch on a developer install (scripts/pzopt.sh), left as it is; rebuild and reinstall");
            return false;
         }
         String rev = Overrides.jarRevision();
         String target = BuildInfo.targetRevision();
         String from = target + " (" + BuildInfo.get("commit") + ")";

         if (uninstall) {
            // a helper still working (the old game has just ended) gets a moment; whether it finishes or not, the
            // classes this process loaded are the old install's, so this process must not go on either way
            waitForHelper(pending);
         }
         Updater.Release copy = null;
         if (mismatch && !uninstall) {
            try {
               copy = Updater.findWorkshopCopy(game, rev);
            } catch (Exception e) {
               copy = null;
            }
            if (copy != null && !stockMatches(copy.localDir, game.resolve("projectzomboid.jar"))) {
               log(base, "the Steam Workshop copy " + copy.localDir + " is for revision " + rev
                     + " but its stock classes differ from this jar (a same-revision hotfix?): not installed");
               copy = null;
            }
         }

         // --- everything from the old install is loaded from here on: no pzopt class may load after files change ---
         Class.forName("pzopt.Restart");
         Class.forName("pzopt.Log");
         Set<Path> files = new LinkedHashSet<>();
         List<Path> dirs = new ArrayList<>();
         if (Files.isRegularFile(game.resolve(Updater.MANIFEST)) || Files.isRegularFile(game.resolve(Updater.FILE_LIST))) {
            Uninstall.Plan plan = Uninstall.plan(game);
            files.addAll(plan.files());
            dirs.addAll(plan.dirs());
         }
         if (uninstall) {
            for (String line : readLines(pending)) {
               if (!line.isBlank()) {
                  files.add(Path.of(line.strip()));
               }
            }
            for (String line : readLines(base.resolve(Uninstall.DIRS_NAME))) {
               if (!line.isBlank()) {
                  dirs.add(Path.of(line.strip()));
               }
            }
         }
         List<String> copyFiles = copy != null ? releaseList(copy.localDir) : null;
         boolean aot = false;
         boolean gc = false;
         try {
            aot = AotCache.resetLauncher(game);
            gc = copy == null && GcChoice.undo(game);
         } catch (Exception e) {
            log(base, "launcher JSON undo failed: " + e);
         }

         touched = true;
         List<String> left = remove(game, files, dirs);
         String action;
         String reason;
         if (uninstall) {
            Files.deleteIfExists(pending);
            Files.deleteIfExists(base.resolve(Uninstall.DIRS_NAME));
            appendLine(base.resolve(Uninstall.LOG_NAME), Instant.now().truncatedTo(ChronoUnit.SECONDS)
                  + " uninstall finished at the next start (the helper had not removed the files); " + left.size() + " files could not be removed");
            action = "uninstalled";
            reason = "Uninstall PZ Optimization was pressed; the files were still there at the next start and are removed now.";
         } else if (copy != null && copyFiles != null) {
            String err = install(copy.localDir, game, rev, copyFiles);
            if (err == null) {
               action = "updated";
               reason = "The game updated to revision " + rev + "; the installed build was for " + target
                     + ". The build for " + rev + " from the Steam Workshop copy is installed in its place.";
            } else {
               log(base, "installing the Steam Workshop copy failed (" + err + "); the game runs stock");
               action = "removed";
               reason = "The game updated to revision " + rev + "; the installed build was for " + target
                     + " and could not run on it, so it was removed. Installing the Workshop copy failed: " + err;
            }
         } else {
            action = "removed";
            reason = "The game updated to revision " + rev + "; the installed build was for " + target
                  + " and could not run on it, so it was removed and the game runs stock. Install again once the Workshop item has the build for "
                  + rev + ".";
         }
         log(base, action + ": " + reason + " Game folder " + game + "; " + files.size() + " files listed, " + left.size()
               + " could not be removed" + (left.isEmpty() ? "" : " (first: " + left.get(0) + ")")
               + (aot ? "; launcher back to the loose classes" : "") + (gc ? "; launcher's collector / JIT / heap flags undone" : ""));
         notice(zomboid, action, reason, rev, from, copy != null ? copy.commit : "");
         String bench = BenchPlan.abandon(zomboid.toFile(), "the install was " + action + " by the boot repair");
         if (bench != null) {
            log(base, bench);
         }
         boolean relaunched = Restart.relaunch();
         log(base, relaunched ? "the game is started again" : "could not start the game again: start it from Steam");
         return true;
      } catch (Throwable t) {
         log(base, "failed: " + t);
         // half removed is worse than either state: end this process, the next start finishes the job or runs stock
         return touched;
      }
   }

   /** Zomboid's folder: -cachedir= as the game reads it (MainScreenState.main), else ~/Zomboid. */
   static Path zomboidDir(String[] args) {
      if (args != null) {
         for (String a : args) {
            if (a != null && a.startsWith("-cachedir=")) {
               String v = a.replace("-cachedir=", "").trim();
               if (!v.isEmpty()) {
                  return Path.of(v);
               }
            }
         }
      }
      return Path.of(System.getProperty("user.home"), "Zomboid");
   }

   /** The uninstall list belongs to this install unless the install (its manifest) is newer than the list. */
   static boolean pendingIsCurrent(Path pending, Path manifest) {
      try {
         return !Files.isRegularFile(manifest) || Files.getLastModifiedTime(manifest).compareTo(Files.getLastModifiedTime(pending)) <= 0;
      } catch (IOException e) {
         return true;
      }
   }

   private static void waitForHelper(Path pending) throws InterruptedException {
      long end = System.currentTimeMillis() + HELPER_GRACE_MS;
      try {
         long age = System.currentTimeMillis() - Files.getLastModifiedTime(pending).toMillis();
         if (age > HELPER_GRACE_MS) {
            return;
         }
      } catch (IOException e) {
         return;
      }
      while (Files.isRegularFile(pending) && System.currentTimeMillis() < end) {
         Thread.sleep(250);
      }
   }

   /**
    * Deletes {@code files} (the overrides first, the pzopt package next, the manifest and file list last, so a removal
    * cut short is still finished by the next start or an uninstaller), then {@code dirs} that are left empty. Only paths
    * inside {@code game}; returns the ones that could not be deleted.
    */
   static List<String> remove(Path game, Set<Path> files, List<Path> dirs) {
      Path root = game.toAbsolutePath().normalize();
      // the lists hold the path the game had (its working folder: the physical path) or the one a script wrote (maybe
      // through a symlinked library folder): a path under either spelling of the folder counts as inside it
      Path real = root;
      try {
         real = game.toRealPath();
      } catch (IOException ignored) {
      }
      Path pkg = root.resolve("pzopt");
      Path manifest = root.resolve(Updater.MANIFEST);
      Path list = root.resolve(Updater.FILE_LIST);
      List<Path> first = new ArrayList<>();
      List<Path> second = new ArrayList<>();
      List<Path> last = new ArrayList<>();
      for (Path f : files) {
         Path n = f.toAbsolutePath().normalize();
         if (!real.equals(root) && n.startsWith(real) && !n.equals(real)) {
            n = root.resolve(real.relativize(n));
         }
         if (!n.startsWith(root) || n.equals(root)) {
            continue;
         }
         if (n.equals(manifest) || n.equals(list)) {
            last.add(n);
         } else if (n.startsWith(pkg)) {
            second.add(n);
         } else {
            first.add(n);
         }
      }
      List<String> left = new ArrayList<>();
      for (List<Path> group : List.of(first, second, last)) {
         for (Path f : group) {
            try {
               Files.deleteIfExists(f);
            } catch (IOException e) {
               left.add(root.relativize(f).toString());
            }
         }
      }
      List<Path> sorted = new ArrayList<>();
      for (Path d : dirs) {
         Path n = d.toAbsolutePath().normalize();
         if (!real.equals(root) && n.startsWith(real) && !n.equals(real)) {
            n = root.resolve(real.relativize(n));
         }
         if (n.startsWith(root) && !n.equals(root)) {
            sorted.add(n);
         }
      }
      sorted.sort(java.util.Comparator.comparingInt(Path::getNameCount).reversed());
      for (Path d : sorted) {
         try (var s = Files.list(d)) {
            if (s.findAny().isPresent()) {
               continue;
            }
         } catch (IOException e) {
            continue;
         }
         try {
            Files.deleteIfExists(d);
         } catch (IOException ignored) {
         }
      }
      return left;
   }

   /** The release's file list (pzopt-files.txt of an unpacked copy), safe relative paths only. */
   static List<String> releaseList(Path classes) throws IOException {
      List<String> out = new ArrayList<>();
      for (String line : readLines(classes.resolve(Updater.FILE_LIST))) {
         String rel = line.strip();
         if (rel.isEmpty() || rel.startsWith("#") || rel.startsWith("/") || rel.contains("..") || rel.contains("\\")) {
            continue;
         }
         out.add(rel);
      }
      return out;
   }

   /**
    * Copies the unpacked release in {@code src} into {@code game} the way the installers do: the manifest first (each
    * file "-", so a copy cut short can still be removed), the pzopt package before the overrides that call it, then the
    * manifest with each file's sha256. Null when done; on an error what was copied is removed again and the reason
    * returned.
    */
   static String install(Path src, Path game, String rev, List<String> rels) {
      Path manifest = game.resolve(Updater.MANIFEST);
      String stamp = "# revision=" + rev + " installed=" + Instant.now().truncatedTo(ChronoUnit.SECONDS);
      String head = "# files written by the boot repair (pzopt.BootRepair) from the Steam Workshop copy - do not edit\n" + stamp + "\n";
      List<String> ordered = new ArrayList<>();
      for (String rel : rels) {
         if (rel.startsWith("pzopt/")) {
            ordered.add(rel);
         }
      }
      for (String rel : rels) {
         if (!rel.startsWith("pzopt/")) {
            ordered.add(rel);
         }
      }
      List<Path> written = new ArrayList<>();
      try {
         StringBuilder unfinished = new StringBuilder(head).append("# unfinished: the install stopped before the end\n");
         for (String rel : rels) {
            unfinished.append(rel).append(" -\n");
         }
         Files.writeString(manifest, unfinished.toString(), StandardCharsets.UTF_8);
         StringBuilder done = new StringBuilder(head);
         for (String rel : ordered) {
            Path dst = game.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src.resolve(rel), dst, StandardCopyOption.REPLACE_EXISTING);
            written.add(dst);
         }
         for (String rel : rels) {
            done.append(rel).append(' ').append(sha256(game.resolve(rel))).append('\n');
         }
         Files.writeString(manifest, done.toString(), StandardCharsets.UTF_8);
         return null;
      } catch (Exception e) {
         for (Path p : written) {
            try {
               Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
         }
         try {
            Files.deleteIfExists(manifest);
         } catch (IOException ignored) {
         }
         return String.valueOf(e);
      }
   }

   /**
    * The unpacked release's build-info names the stock classes it was compiled against (stock.&lt;class&gt;=sha256):
    * true when every one listed for an override matches the jar's copy (what Overrides.check tests at the next start).
    */
   static boolean stockMatches(Path classes, Path jar) {
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(classes.resolve("pzopt").resolve("build-info.properties"))) {
         p.load(in);
      } catch (IOException e) {
         return false;
      }
      String overrides = p.getProperty("overrides", "");
      try (JarFile j = new JarFile(jar.toFile())) {
         for (String cls : overrides.split(",")) {
            if (cls.isBlank()) {
               continue;
            }
            String expected = p.getProperty("stock." + cls.replace('/', '.'));
            JarEntry e = j.getJarEntry(cls + ".class");
            if (expected == null || e == null) {
               continue;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = j.getInputStream(e)) {
               md.update(in.readAllBytes());
            }
            if (!expected.equals(hex(md.digest()))) {
               return false;
            }
         }
         return true;
      } catch (Exception e) {
         return false;
      }
   }

   private static void notice(Path zomboid, String action, String reason, String game, String from, String to) {
      try {
         Path lua = zomboid.resolve("Lua");
         Files.createDirectories(lua);
         Files.writeString(lua.resolve(NOTICE_NAME), "action=" + action + "\nreason=" + reason + "\ngame=" + game + "\nfrom=" + from
               + "\nto=" + to + "\nat=" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\nshown=0\n", StandardCharsets.UTF_8);
      } catch (IOException ignored) {
      }
   }

   static void log(Path base, String msg) {
      String line = Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + msg;
      System.out.println("[pzopt] boot repair: " + msg);
      try {
         Files.createDirectories(base);
         appendLine(base.resolve(LOG_NAME), line);
      } catch (IOException ignored) {
      }
   }

   private static void appendLine(Path f, String line) throws IOException {
      Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
   }

   private static List<String> readLines(Path f) {
      try {
         return Files.readAllLines(f, StandardCharsets.UTF_8);
      } catch (IOException e) {
         return List.of();
      }
   }

   private static String firstLine(Path f) {
      List<String> l = readLines(f);
      return l.isEmpty() ? "" : l.get(0);
   }

   static String sha256(Path p) throws Exception {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(p)) {
         byte[] buf = new byte[1 << 16];
         int n;
         while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
         }
      }
      return hex(md.digest());
   }

   private static String hex(byte[] b) {
      StringBuilder sb = new StringBuilder(b.length * 2);
      for (byte x : b) {
         sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
      }
      return sb.toString();
   }
}
