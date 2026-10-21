package pzopt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Options > Optimizations > "Uninstall PZ Optimization": removes from the game itself what the installers or the in-game
 * updater put into the game folder, so leaving needs no terminal and still works after the Workshop item (and the
 * installer in it) is gone.
 *
 * The files cannot go while this JVM runs: the classes it has not loaded yet would come from projectzomboid.jar beside
 * the overridden ones already loaded, and on Windows the AOT jar (pzopt/aot/pzopt.jar, on the class path) and a loaded
 * DLSS library are locked. So the press undoes the launcher edits now (AotCache's jar form, GcChoice's collector and JIT
 * flags; the launcher reads its JSON at launch only), writes the list of files, and starts a helper that waits for this
 * process to end and then deletes them; the Lua quits the game the stock way. The list: the manifest's paths
 * (pzopt-installed.txt, else the release's pzopt-files.txt), both lists, the AOT folder, the DLSS files the Enhancements
 * tab's button fetched (natives/pzopt-dlss-installed.txt), then every folder they leave empty. Settings and caches under
 * Zomboid/pzopt/ stay, as with the installers (a reinstall keeps them). The helper appends to Zomboid/pzopt/uninstall.log.
 *
 * Helper as in pzopt.Restart: /bin/sh polling {@code kill -0}, or a hidden PowerShell polling Get-Process; it gives up
 * without deleting anything when the game is still running after {@link #WAIT_S} seconds. On Windows the helper is a
 * script file (Zomboid/pzopt/uninstall-helper.ps1) run with -File, not an -EncodedCommand: an encoded, hidden PowerShell
 * started by a game is what antivirus heuristics stop (2026-10-07, Workshop reports of a press that removed nothing).
 * Both helpers log "helper started" first, so uninstall.log tells a helper that never ran from one that failed. When
 * the files are still there at the next start (the list file left behind), pzopt.BootRepair removes them then.
 */
public final class Uninstall {
   static final String LOG_NAME = "uninstall.log";
   static final String FILES_NAME = "uninstall-files.txt";
   static final String DIRS_NAME = "uninstall-dirs.txt";
   static final String HELPER_PS1 = "uninstall-helper.ps1";
   static final int WAIT_S = 600;

   private static volatile String message = "";

   private Uninstall() {
   }

   /** Why the button is off, or "" when an uninstall can start. */
   public static String unavailableReason() {
      if (Harness.REQUESTED || Harness.active()) {
         return "not during a harness run";
      }
      Path dir = Updater.gameDir();
      if (!Files.isRegularFile(dir.resolve(Updater.MANIFEST)) && !Files.isRegularFile(dir.resolve(Updater.FILE_LIST))) {
         return "no " + Updater.MANIFEST + " or " + Updater.FILE_LIST + " in " + dir + ": the files were copied by hand, remove them the same way";
      }
      return "";
   }

   public static String message() {
      return message;
   }

   /** Undoes the launcher edits and starts the helper; true when it is waiting for the game to end. */
   public static synchronized boolean start() {
      String why = unavailableReason();
      if (!why.isEmpty()) {
         message = why;
         return false;
      }
      Path dir = Updater.gameDir();
      try {
         AotCache.awaitBootWrites(10_000); // the boot's own JSON writes first, so none lands after the undo
         boolean aot = AotCache.resetLauncher(dir);
         boolean gc = GcChoice.undo(dir);
         Plan plan = plan(dir);
         // a benchmark mid-run would resume at the next install (its plan lives in Zomboid/pzopt, not in the game folder) and no
         // pzopt process follows an uninstall to put the player's files back: end it here (Workshop 2026-10-18)
         BenchPlan.abortRunning("PZ Optimization uninstalled");
         Path base = Updater.cacheFile().getParent();
         Files.createDirectories(base);
         Path files = base.resolve(FILES_NAME);
         Path dirs = base.resolve(DIRS_NAME);
         Path log = base.resolve(LOG_NAME);
         Files.write(files, lines(plan.files()), StandardCharsets.UTF_8);
         Files.write(dirs, lines(plan.dirs()), StandardCharsets.UTF_8);
         Files.writeString(log, Instant.now().truncatedTo(ChronoUnit.SECONDS) + " uninstall requested in " + dir + ": " + plan.files().size()
               + " files" + (aot ? ", launcher back to the loose classes" : "") + (gc ? ", launcher's own collector / JIT flags again" : "")
               + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
         long pid = ProcessHandle.current().pid();
         boolean windows = File.separatorChar == '\\';
         if (windows) {
            Files.writeString(base.resolve(HELPER_PS1), windowsScript(pid, files, dirs, log), StandardCharsets.UTF_8);
         }
         ProcessBuilder pb = new ProcessBuilder(helper(windows, pid, files, dirs, log)).directory(base.toFile());
         pb.environment().remove("LD_PRELOAD"); // the Linux launcher's libPZXInitThreads64.so prints a line from every command the helper runs
         pb.redirectInput(ProcessBuilder.Redirect.from(new File(File.separatorChar == '\\' ? "NUL" : "/dev/null")));
         pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
         pb.redirectError(ProcessBuilder.Redirect.DISCARD);
         pb.start();
         message = plan.files().size() + " files are removed as soon as the game has closed";
         Log.info("uninstall: helper waits for pid " + pid + " to end, then removes " + plan.files().size() + " files from " + dir
               + (aot ? "; launcher back to the loose classes" : "") + (gc ? "; launcher's collector / JIT flags undone" : "") + "; log " + log);
         return true;
      } catch (Exception e) {
         message = "uninstall failed: " + e.getMessage();
         Log.warn("uninstall failed: " + e);
         return false;
      }
   }

   record Plan(List<Path> files, List<Path> dirs) {
   }

   /** The existing files to delete and the folders to remove if they end up empty (deepest first), all inside {@code dir}. */
   static Plan plan(Path dir) throws IOException {
      Path root = dir.toAbsolutePath().normalize();
      Set<Path> files = new LinkedHashSet<>();
      for (String rel : Updater.previousFiles(root)) {
         files.add(root.resolve(rel));
      }
      files.add(root.resolve(Updater.MANIFEST));
      files.add(root.resolve(Updater.FILE_LIST));
      files.add(root.resolve("ProjectZomboid64.json.pzopt-tmp"));
      Path aot = root.resolve("pzopt").resolve("aot");
      if (Files.isDirectory(aot)) {
         try (var s = Files.walk(aot)) {
            s.filter(Files::isRegularFile).forEach(files::add);
         }
      }
      Path dlss = root.resolve("natives").resolve(UpscalerDeps.MARKER);
      if (Files.isRegularFile(dlss)) {
         for (String line : Files.readAllLines(dlss, StandardCharsets.UTF_8)) {
            String name = line.strip();
            if (!name.isEmpty() && !name.startsWith("#") && !name.contains("=") && !name.contains("/") && !name.contains("\\") && !name.contains("..")) {
               files.add(dlss.getParent().resolve(name));
            }
         }
         files.add(dlss);
      }
      // the overrides first, the pzopt package they call last: the Windows helper deletes one file at a time, and a
      // launch in the middle must not find an override without it (NoClassDefFoundError: pzopt/Hdr in Display.create)
      Path pkg = root.resolve("pzopt");
      List<Path> ordered = new ArrayList<>(files);
      ordered.sort(Comparator.comparing((Path f) -> f.normalize().startsWith(pkg)));
      List<Path> out = new ArrayList<>();
      Set<Path> dirs = new LinkedHashSet<>();
      for (Path f : ordered) {
         Path n = f.normalize();
         if (!n.startsWith(root) || n.equals(root) || !Files.isRegularFile(n)) {
            continue;
         }
         out.add(n);
         for (Path d = n.getParent(); d != null && !d.equals(root) && d.startsWith(root); d = d.getParent()) {
            dirs.add(d);
         }
      }
      List<Path> dirList = new ArrayList<>(dirs);
      dirList.sort(Comparator.comparingInt(Path::getNameCount).reversed());
      return new Plan(out, dirList);
   }

   private static List<String> lines(List<Path> paths) {
      List<String> out = new ArrayList<>(paths.size());
      for (Path p : paths) {
         out.add(p.toString());
      }
      return out;
   }

   /** The helper's command line: wait for {@code pid}, delete the listed files, remove the listed folders once empty, log. */
   static List<String> helper(boolean windows, long pid, Path files, Path dirs, Path log) {
      if (!windows) {
         return List.of("/bin/sh", "-c", String.join("\n",
               "pid=$0; files=$1; dirs=$2; log=$3; n=0",
               "echo \"$(date) helper started, waiting for pid $pid\" >> \"$log\"",
               "while kill -0 \"$pid\" 2>/dev/null; do",
               "  if [ $n -ge " + WAIT_S * 20 + " ]; then echo \"$(date) the game (pid $pid) was still running after " + WAIT_S
                     + " s: nothing removed\" >> \"$log\"; exit 1; fi",
               "  sleep 0.05; n=$((n+1))",
               "done",
               // one rm / rmdir for all (a process per file took 1.9 s on the flip: a relaunch in that window found half
               // an install), then the check with the shell's own test
               "tr '\\n' '\\0' < \"$files\" | xargs -0 rm -f --",
               "left=0",
               "while IFS= read -r f; do if [ -e \"$f\" ]; then echo \"left: $f\" >> \"$log\"; left=$((left+1)); fi; done < \"$files\"",
               "tr '\\n' '\\0' < \"$dirs\" | xargs -0 rmdir -- 2>/dev/null",
               "echo \"$(date) uninstall finished; $left files could not be removed\" >> \"$log\"",
               // a file that could not go stays listed: the next start (pzopt.BootRepair) tries again
               "if [ $left -eq 0 ]; then rm -f -- \"$files\" \"$dirs\"; fi"),
               Long.toString(pid), files.toString(), dirs.toString(), log.toString());
      }
      return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", files.resolveSibling(HELPER_PS1).toString());
   }

   /** The Windows helper's script (written beside the lists as {@link #HELPER_PS1}; it deletes itself at the end). */
   static String windowsScript(long pid, Path files, Path dirs, Path log) {
      return String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "$log = " + quote(log),
            "Add-Content -LiteralPath $log -Encoding UTF8 \"$(Get-Date -Format s) helper started, waiting for pid " + pid + "\"",
            // polled like the sh helper: Wait-Process started by the game returned at once while the game still ran
            // (Windows test 2026-10-11: "still running after 600 s" one second after the helper started, nothing removed)
            "$n = 0",
            "while (Get-Process -Id " + pid + ") {",
            "  if ($n -ge " + WAIT_S * 20 + ") { Add-Content -LiteralPath $log -Encoding UTF8 \"$(Get-Date -Format s) the game (pid " + pid
                  + ") was still running after " + WAIT_S + " s: nothing removed\"; exit 1 }",
            "  Start-Sleep -Milliseconds 50; $n++",
            "}",
            "$left = 0",
            "foreach ($f in Get-Content -LiteralPath " + quote(files) + " -Encoding UTF8) {",
            "  if (-not $f) { continue }",
            // a handle can outlive the process by a moment (antivirus, the Steam overlay): a few tries
            "  for ($i = 0; $i -lt 20 -and (Test-Path -LiteralPath $f); $i++) { Remove-Item -LiteralPath $f -Force; if (Test-Path -LiteralPath $f) { Start-Sleep -Milliseconds 250 } }",
            "  if (Test-Path -LiteralPath $f) { Add-Content -LiteralPath $log -Encoding UTF8 \"left: $f\"; $left++ }",
            "}",
            "foreach ($d in Get-Content -LiteralPath " + quote(dirs) + " -Encoding UTF8) {",
            "  if ($d -and (Test-Path -LiteralPath $d) -and -not (Get-ChildItem -LiteralPath $d -Force)) { Remove-Item -LiteralPath $d -Force }",
            "}",
            "Add-Content -LiteralPath $log -Encoding UTF8 \"$(Get-Date -Format s) uninstall finished; $left files could not be removed\"",
            // a file that could not go stays listed: the next start (pzopt.BootRepair) tries again
            "if ($left -eq 0) { Remove-Item -LiteralPath " + quote(files) + ", " + quote(dirs) + " -Force }",
            "Remove-Item -LiteralPath $PSCommandPath -Force");
   }

   private static String quote(Path p) {
      return "'" + p.toString().replace("'", "''") + "'";
   }
}
