package pzopt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The launcher JSON (ProjectZomboid64.json, read by the native launcher at launch) for its in-game writers, AotCache and
 * GcChoice (and through them Uninstall): the one place that reads and writes it.
 *
 * A save backs the file up once (ProjectZomboid64.json.pzopt-backup), writes ProjectZomboid64.json.pzopt-tmp, reads it
 * back and moves it over the JSON. On Windows the running game holds the JSON (ProjectZomboid64.exe reads it at startup
 * and the JVM runs inside that process), and Windows refuses to replace a file another handle holds without delete
 * sharing: the move failed with AccessDeniedException at every boot. Linux and macOS rename over an open file. So on
 * Windows a refused replace is staged as ProjectZomboid64.json.pzopt-pending and a hidden PowerShell, started once per
 * process, waits for this process to end and applies the pending file. {@link #read} returns the pending copy when there
 * is one, so the boot's second writer (and the next boot, if the helper could not apply it) builds on the staged change
 * instead of the live file.
 *
 * Every helper that waits for the game to end runs the same step, {@link #applyPendingScript}: this exit helper,
 * pzopt.Uninstall's (before it deletes anything, so the restored launcher is in place before pzopt/aot goes) and
 * pzopt.Restart's (before it starts the game again, so the new launch reads the staged form). Whichever runs first
 * applies it; the others find it gone. A pending file older than the live JSON was overtaken (an installer or Steam
 * rewrote the launcher after the staging): readers and helpers drop it instead of putting it back.
 *
 * The helpers must inherit no handles: the native launcher reads the JSON through the C runtime, whose handles are
 * inheritable, and Java starts a process with handle inheritance, so a helper started directly kept the launcher's
 * handle on the JSON open and blocked its own replace (real session, 2026-10-01: the staged launcher was never applied).
 * Java therefore starts a short-lived PowerShell whose only work is Start-Process of the real helper; Start-Process
 * without -NoNewWindow or a redirect goes through ShellExecuteEx, which inherits no handles ({@link #startDetached}).
 * Each helper notes when it started, when the game ended and what became of the pending file in
 * Zomboid/pzopt/launcher-helper.log ({@link #helperLog}, kept under 64 KB).
 */
final class LauncherJson {
   static final String NAME = "ProjectZomboid64.json";
   static final String BACKUP = NAME + ".pzopt-backup";
   static final String TMP = NAME + ".pzopt-tmp";
   static final String PENDING = NAME + ".pzopt-pending";
   private static final boolean WINDOWS = File.separatorChar == '\\';
   private static final int LOG_CAP = 64 * 1024;
   private static final int LOG_KEEP = 32 * 1024;

   /** The process the exit helper waits for: this one, which holds the JSON; a test points it at another. */
   static long helperPid = ProcessHandle.current().pid();
   /** The outer process of the helper {@link #arm} started, for the test. */
   static volatile Process armedHelper;
   /** The interpreter Java starts (the short-lived outer PowerShell); a test points it at a missing one. */
   static String powershell = "powershell.exe";
   /** The helpers' log, beside options.ini (pzopt.UserOptions' folder); a test points it at a temporary file. */
   static Path helperLog = UserOptions.file().toPath().toAbsolutePath().resolveSibling("launcher-helper.log");
   private static boolean armed;

   private LauncherJson() {
   }

   /** The launcher as the next launch will see it: the staged copy when there is a valid, current one, else the live JSON. */
   static synchronized JSONObject read(Path game) throws IOException {
      JSONObject staged = pending(game);
      if (staged != null) {
         arm(game);
         return staged;
      }
      return new JSONObject(Files.readString(game.resolve(NAME), StandardCharsets.UTF_8));
   }

   /**
    * Writes the launcher; true when the JSON was replaced, false when the running game holds it (Windows) and the change
    * is staged until the game exits. The tmp file never remains.
    */
   static synchronized boolean save(Path game, JSONObject j) throws IOException {
      Path json = game.resolve(NAME);
      Path backup = game.resolve(BACKUP);
      if (!Files.exists(backup)) {
         Files.copy(json, backup);
      }
      Path tmp = game.resolve(TMP);
      Path pending = game.resolve(PENDING);
      try {
         Files.writeString(tmp, j.toString(1) + "\n", StandardCharsets.UTF_8);
         JSONObject check = parse(tmp);
         if (check == null || !valid(check)) {
            throw new IOException("launcher JSON check failed; left unchanged");
         }
         try {
            Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (FileSystemException e) {
            if (!WINDOWS) {
               throw e;
            }
            try {
               Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException s) {
               e.addSuppressed(s);
               throw e;
            }
            Log.info("launcher JSON: " + NAME + " is in use by the running game; the change is staged as " + PENDING
                  + " and applied when the game exits");
            arm(game);
            return false;
         }
      } catch (IOException | RuntimeException e) {
         try {
            Files.deleteIfExists(tmp);
         } catch (IOException d) {
            e.addSuppressed(d);
         }
         throw e;
      }
      try {
         Files.deleteIfExists(pending); // the JSON holds the newest form now
      } catch (IOException e) {
         Log.warn("launcher JSON: " + NAME + " is written, but " + PENDING + " could not be deleted and would shadow it (" + e
               + "); being older than the JSON, it is dropped when next read or applied");
      }
      return true;
   }

   /** The staged launcher, or null when there is none, it is not a valid launcher JSON, or the live JSON overtook it. */
   private static JSONObject pending(Path game) {
      Path p = game.resolve(PENDING);
      if (!Files.isRegularFile(p)) {
         return null;
      }
      Path json = game.resolve(NAME);
      try {
         if (Files.isRegularFile(json) && Files.getLastModifiedTime(json).compareTo(Files.getLastModifiedTime(p)) > 0) {
            String why = NAME + " was rewritten after the change staged as " + PENDING + " (an installer, Steam)";
            try {
               Files.deleteIfExists(p);
               Log.info("launcher JSON: " + why + "; the staged copy is dropped");
            } catch (IOException e) {
               Log.warn("launcher JSON: " + why + "; the staged copy is ignored but could not be deleted: " + e);
            }
            return null;
         }
      } catch (IOException ignored) {
      }
      try {
         JSONObject j = parse(p);
         if (j != null && valid(j)) {
            return j;
         }
      } catch (IOException ignored) {
      }
      Log.warn("launcher JSON: " + PENDING + " is not a valid launcher JSON; reading " + NAME);
      return null;
   }

   private static JSONObject parse(Path f) throws IOException {
      try {
         return new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
      } catch (JSONException e) {
         return null;
      }
   }

   static boolean valid(JSONObject j) {
      JSONArray cp = j.optJSONArray("classpath");
      JSONArray vm = j.optJSONArray("vmArgs");
      return j.has("mainClass") && cp != null && cp.length() > 0 && vm != null && vm.length() > 0;
   }

   /** Starts the exit helper once per process (Windows only); a start that failed is tried again by the next save. */
   private static void arm(Path game) {
      if (armed || !WINDOWS) {
         return;
      }
      try {
         Process p = startExitHelper(game, helperPid);
         armed = true;
         armedHelper = p;
         Log.info("launcher JSON: exit helper started (detached, through pid " + p.pid() + "): it waits for pid " + helperPid
               + " to end, then applies " + PENDING + "; log " + helperLog);
      } catch (Exception e) {
         Log.warn("launcher JSON: could not start the exit helper (" + e + "); " + PENDING + " stays staged, the next save tries again");
      }
   }

   /** Starts the exit helper detached ({@link #startDetached}); returns the short-lived outer process. */
   static Process startExitHelper(Path game, long pid) throws IOException {
      Path dir = game.toAbsolutePath();
      ProcessBuilder pb = new ProcessBuilder(powershellCommand(detachedOuter(script(pid, dir)))).directory(dir.toFile());
      pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      Files.createDirectories(helperLog.getParent());
      return pb.start();
   }

   /** The exit helper (inner): waits for {@code pid} with no timeout (a session lasts hours), then the shared apply step. */
   static String script(long pid, Path game) {
      return String.join("\n",
            helperPrologue("exit", pid),
            "Wait-Process -Id " + pid,
            "PzoptLog 'exit: pid " + pid + " ended'",
            applyPendingScript(game, "exit"));
   }

   /**
    * How Java runs a helper's script: hidden Windows PowerShell, the script as -EncodedCommand (UTF-16LE base64), as in
    * pzopt.Restart / pzopt.Uninstall before (the caller sets the input, output and directory).
    */
   static List<String> powershellCommand(String script) {
      return List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
   }

   /**
    * The PowerShell statement that starts the script held by the PowerShell expression {@code innerExpr} in a hidden
    * PowerShell that inherits no handles: Start-Process without -NoNewWindow, -Redirect* or -Credential goes through
    * ShellExecuteEx (no handle inheritance), unlike Java's CreateProcess, which hands a child every inheritable handle of
    * the game process, the native launcher's handle on the JSON included. Its environment is the caller's.
    */
   static String startDetached(String innerExpr) {
      return "Start-Process -FilePath powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',"
            + "'-EncodedCommand',([Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes(" + innerExpr + ")))";
   }

   /** The short-lived outer script for a fixed inner one: the inner as a literal, started detached, then exit. */
   static String detachedOuter(String inner) {
      return "$ErrorActionPreference = 'Stop'\n$pzoptInner = " + psQuote(inner) + "\n" + startDetached("$pzoptInner");
   }

   /**
    * The start of every helper's inner script: errors silent, {@code PzoptLog} (one dated line to {@link #helperLog}, the
    * file cut to its last 32 KB once past 64 KB, a few tries since two helpers may write at once), the start line.
    */
   static String helperPrologue(String tag, long pid) {
      return String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "$pzoptLog = " + psQuote(helperLog),
            "function PzoptLog([string]$m) {",
            "  for ($t = 0; $t -lt 5; $t++) {",
            "    try {",
            "      [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($pzoptLog))",
            "      $f = New-Object System.IO.FileInfo $pzoptLog",
            "      if ($f.Exists -and $f.Length -gt " + LOG_CAP + ") { $s = [System.IO.File]::ReadAllText($pzoptLog); $s = $s.Substring($s.IndexOf(\"`n\", "
                  + "[Math]::Max(0, $s.Length - " + LOG_KEEP + ")) + 1); [System.IO.File]::WriteAllText($pzoptLog, $s) }",
            "      [System.IO.File]::AppendAllText($pzoptLog, (Get-Date -Format s) + ' ' + $m + \"`r`n\")",
            "      return",
            "    } catch { Start-Sleep -Milliseconds 50 }",
            "  }",
            "}",
            "PzoptLog \"" + tag + ": helper pid $PID waits for pid " + pid + "\"");
   }

   /**
    * The PowerShell that applies the staged launcher of {@code game}, for a helper that has waited for the game to end
    * (needs {@link #helperPrologue}); it leaves {@code $pzoptPendingLeft} true when the pending file is still there
    * afterwards and logs the outcome. One call replaces the JSON ([System.IO.File]::Replace, the Win32 ReplaceFile); a
    * few tries, since a handle can outlive the process by a moment. The backup name is {@code [NullString]::Value}:
    * PowerShell passes {@code $null} to a string parameter as "", which File.Replace refuses every time. Without a backup
    * name ReplaceFile can fail after removing the JSON (ERROR_UNABLE_TO_MOVE_REPLACEMENT), so a missing JSON gets the
    * pending file moved into its place. A pending file older than the JSON was overtaken and is deleted instead. Nothing
    * to do once the pending file is gone (a later save of the same session landed, or another helper applied it). .NET
    * calls only: they throw into the catch whatever the caller's $ErrorActionPreference.
    */
   static String applyPendingScript(Path game, String tag) {
      Path dir = game.toAbsolutePath();
      return String.join("\n",
            "$pzoptPending = " + psQuote(dir.resolve(PENDING)),
            "$pzoptJson = " + psQuote(dir.resolve(NAME)),
            "$pzoptDone = 'none staged'",
            "$pzoptError = ''",
            "for ($pzoptTry = 0; $pzoptTry -lt 40; $pzoptTry++) {",
            "  if (-not [System.IO.File]::Exists($pzoptPending)) { if ($pzoptTry -gt 0) { $pzoptDone = 'gone (another helper applied it)' }; break }",
            "  try {",
            "    if (-not [System.IO.File]::Exists($pzoptJson)) { [System.IO.File]::Move($pzoptPending, $pzoptJson); $pzoptDone = 'moved into place (no "
                  + NAME + ")' }",
            "    elseif ([System.IO.File]::GetLastWriteTimeUtc($pzoptJson) -gt [System.IO.File]::GetLastWriteTimeUtc($pzoptPending)) { "
                  + "[System.IO.File]::Delete($pzoptPending); $pzoptDone = 'older than " + NAME + ": deleted' }",
            "    else { [System.IO.File]::Replace($pzoptPending, $pzoptJson, [NullString]::Value); $pzoptDone = 'applied' }",
            "    break",
            "  } catch { $e = $_.Exception; if ($e.InnerException) { $e = $e.InnerException }; $pzoptError = $e.Message; Start-Sleep -Milliseconds 250 }",
            "}",
            "$pzoptPendingLeft = [System.IO.File]::Exists($pzoptPending)",
            "if ($pzoptPendingLeft) { $pzoptDone = \"left after $pzoptTry tries: $pzoptError\" }",
            "PzoptLog \"" + tag + ": " + PENDING + " $pzoptDone\"");
   }

   static String psQuote(Path p) {
      String encoded = Base64.getEncoder().encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));
      return "([System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + encoded + "')))";
   }

   /** A PowerShell ASCII single-quoted literal. Smart quotes inside it are ordinary path characters. */
   static String psQuote(String s) {
      StringBuilder b = new StringBuilder(s.length() + 8).append('\'');
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         b.append(c);
         if (c == '\'') {
            b.append(c);
         }
      }
      return b.append('\'').toString();
   }
}
