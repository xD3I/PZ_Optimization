package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Restart game" of the update dialog (media/lua/client/pzopt/pzopt_mainscreen_update.lua): the classes an update
 * replaced load on the next start, so instead of quitting and leaving the relaunch to the player, a small helper
 * process waits for this one to end and starts the same program again with the same arguments, working directory
 * and environment (Steam's SteamAppId and overlay variables included). The Lua then quits the game the stock way
 * (options saved, FMOD and the GL context closed).
 *
 * Linux / macOS: {@code /bin/sh} polls {@code kill -0 <pid>} every 50 ms and execs the command
 * ({@link ProcessHandle.Info#command()}: /proc/self/exe or the Mach-O path, and the arguments the JVM reads from
 * /proc/self/cmdline or KERN_PROCARGS2). Windows keeps no argument list for another process that Java can read, so a
 * hidden PowerShell reads this process's command line from WMI, says so on its output (this method waits for that
 * line, so the game never quits before the helper knows what to start), waits for the process, applies the launcher JSON
 * this session staged (pzopt.LauncherJson: the running game holds the JSON, so its edits wait for the exit; the new
 * process must read them) and starts it again. The waiting part runs detached (LauncherJson.startDetached), so it holds
 * none of the game's handles: the outer PowerShell Java starts reads the command line, starts it and says "ready".
 */
public final class Restart {
   private Restart() {
   }

   /** Starts the helper; true when it is waiting for this process to end. */
   public static boolean relaunch() {
      long pid = ProcessHandle.current().pid();
      Path cwd = Path.of("").toAbsolutePath();
      try {
         if (File.separatorChar == '\\') {
            return windows(pid, cwd);
         }
         ProcessHandle.Info info = ProcessHandle.current().info();
         String exe = info.command().orElse(null);
         if (exe == null) {
            Log.warn("restart: this process's executable is unknown");
            return false;
         }
         List<String> cmd = new ArrayList<>();
         cmd.add("/bin/sh");
         cmd.add("-c");
         // $0 = pid; give up waiting after 120 s (a process nobody reaps), then start anyway
         cmd.add("n=0; while kill -0 \"$0\" 2>/dev/null && [ $n -lt 2400 ]; do sleep 0.05; n=$((n+1)); done; exec \"$@\"");
         cmd.add(Long.toString(pid));
         cmd.add(exe);
         for (String a : info.arguments().orElse(new String[0])) {
            cmd.add(a);
         }
         ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
         mark(pb);
         pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
         pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
         pb.redirectError(ProcessBuilder.Redirect.DISCARD);
         pb.start();
         Log.info("restart: helper waits for pid " + pid + " to end, then starts " + exe + " (" + (cmd.size() - 5) + " arguments) in " + cwd);
         return true;
      } catch (Exception e) {
         Log.warn("restart: could not start the helper: " + e);
         return false;
      }
   }

   /** The new process learns it was restarted and when the button was pressed (the log's restart timing). */
   private static void mark(ProcessBuilder pb) {
      pb.environment().put(ENV_FROM, Long.toString(ProcessHandle.current().pid()));
      pb.environment().put(ENV_AT, Long.toString(System.currentTimeMillis()));
   }

   static final String ENV_FROM = "PZOPT_RESTARTED_FROM";
   static final String ENV_AT = "PZOPT_RESTARTED_AT";

   /** True in a process that Restart game started. */
   public static boolean restarted() {
      return System.getenv(ENV_FROM) != null;
   }

   /** Milliseconds since Restart game was pressed in the previous process, or -1. */
   public static long msSincePressed() {
      try {
         return System.currentTimeMillis() - Long.parseLong(System.getenv(ENV_AT));
      } catch (Exception e) {
         return -1;
      }
   }

   /** Logs how long the old process took to end and the new one to start (once, early in the boot). */
   public static void logRestarted() {
      if (!restarted()) {
         return;
      }
      long at;
      try {
         at = Long.parseLong(System.getenv(ENV_AT));
      } catch (Exception e) {
         return;
      }
      long started = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(-1L);
      Log.info("restart: started by Restart game in pid " + System.getenv(ENV_FROM) + "; this process began "
            + (started > 0 ? (started - at) + " ms" : "?") + " after the press, the boot reached the updater " + (System.currentTimeMillis() - at) + " ms after it");
   }

   private static boolean windows(long pid, Path cwd) throws Exception {
      Files.createDirectories(LauncherJson.helperLog.getParent());
      ProcessBuilder pb = new ProcessBuilder(LauncherJson.powershellCommand(windowsScript(pid, cwd))).directory(cwd.toFile());
      return startWindowsHelper(pb, pid);
   }

   /**
    * The Windows helper, outer part (started by Java, so it inherits the game's handles and must not stay): this process's
    * command line from WMI, baked into the inner part as literals, the inner part started detached
    * (LauncherJson.startDetached: no inherited handle on the launcher JSON), then "ready" on its output and exit.
    */
   static String windowsScript(long pid, Path cwd) {
      return String.join("\n",
            // progress for the game's log if "ready" never comes (written past the pipeline, flushed at once)
            "[Console]::Out.WriteLine('step wmi'); [Console]::Out.Flush()",
            "$p = Get-CimInstance Win32_Process -Filter \"ProcessId=" + pid + "\"",
            "if (-not $p) { Write-Output 'none'; exit 1 }",
            "$exe = $p.ExecutablePath",
            "$cl = $p.CommandLine",
            "if ($cl.StartsWith('\"')) { $rest = $cl.Substring($cl.IndexOf('\"', 1) + 1) } else { $i = $cl.IndexOf(' '); if ($i -lt 0) { $rest = '' } else { $rest = $cl.Substring($i) } }",
            "$rest = $rest.Trim()",
            // a single-quoted literal: PowerShell ends one at ' and at the typographic single quotes (LauncherJson.psQuote)
            "function PzoptQ([string]$s) { \"'\" + ($s -replace \"['\\u2018\\u2019\\u201A\\u201B]\", '$0$0') + \"'\" }",
            "$pzoptInner = '$exe = ' + (PzoptQ $exe) + \"`n\" + '$rest = ' + (PzoptQ $rest) + \"`n\" + " + LauncherJson.psQuote(windowsInner(pid, cwd)),
            "[Console]::Out.WriteLine('step start'); [Console]::Out.Flush()",
            LauncherJson.startDetached("$pzoptInner"),
            "Write-Output 'ready'",
            "[Console]::Out.Flush()");
   }

   /**
    * The Windows helper, inner part ($exe and $rest are set above it): waits for the game, applies the launcher JSON this
    * session staged before the new process reads it (the game folder is the working directory, as for GcChoice; else the
    * new session would hold the old JSON to its end), starts the game again; notes each step in launcher-helper.log.
    */
   static String windowsInner(long pid, Path cwd) {
      String dir = LauncherJson.psQuote(cwd);
      return String.join("\n",
            LauncherJson.helperPrologue("restart", pid),
            "Wait-Process -Id " + pid + " -Timeout 120",
            "if (Get-Process -Id " + pid + ") { PzoptLog 'restart: pid " + pid + " still running after 120 s, starting anyway' } else { PzoptLog 'restart: pid "
                  + pid + " ended' }",
            LauncherJson.applyPendingScript(cwd, "restart"),
            "if ($rest) { Start-Process -FilePath $exe -ArgumentList $rest -WorkingDirectory " + dir + " } else { Start-Process -FilePath $exe -WorkingDirectory " + dir + " }",
            "PzoptLog \"restart: started $exe\"");
   }

   /**
    * How long the game waits for the helper's "ready": a cold PowerShell start, the WMI query and the detached start of
    * the waiting part. Usually 1-3 s; an old disk or an antivirus scanning powershell.exe can take far longer, and the
    * game must not quit before the helper is ready, so the headroom is generous.
    */
   static final int READY_TIMEOUT_S = 60;

   private static boolean startWindowsHelper(ProcessBuilder pb, long pid) throws Exception {
      mark(pb);
      pb.redirectErrorStream(true);
      long t0 = System.nanoTime();
      Process helper = pb.start();
      helper.getOutputStream().close();
      // wait until the helper has our command line and has started its waiting part; the game must not quit before
      BufferedReader out = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
      String[] step = {"start of PowerShell"};
      List<String> other = java.util.Collections.synchronizedList(new ArrayList<>());
      CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
         try {
            String line;
            while ((line = out.readLine()) != null) {
               String s = line.strip();
               if (s.equals("ready") || s.equals("none")) {
                  return s;
               }
               if (s.startsWith("step ")) {
                  step[0] = s.substring(5);
               } else {
                  String r = readable(s);
                  if (!r.isEmpty()) {
                     other.add(r.length() > 300 ? r.substring(0, 300) + "..." : r);
                  }
               }
            }
         } catch (Exception e) {
            other.add(e.toString());
         }
         return "end";
      }, job -> {
         Thread t = new Thread(job, "pzopt-restart");
         t.setDaemon(true);
         t.start();
      });
      String result;
      try {
         result = ready.get(READY_TIMEOUT_S, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
         result = "timeout";
      } catch (Exception e) {
         result = "error " + e;
      }
      long ms = (System.nanoTime() - t0) / 1_000_000;
      if (result.equals("ready")) {
         Log.info("restart: helper (pid " + helper.pid() + ") ready after " + ms + " ms: it has the command line; its detached part waits for pid "
               + pid + ", applies a staged " + LauncherJson.NAME + " and starts the game again; log " + LauncherJson.helperLog);
         return true;
      }
      String why;
      if (result.equals("none")) {
         why = "WMI (Get-CimInstance Win32_Process) found no process " + pid;
      } else if (result.equals("timeout")) {
         why = "no 'ready' within " + READY_TIMEOUT_S + " s, the helper was still at step '" + step[0] + "'";
      } else {
         Integer code = helper.waitFor(5, TimeUnit.SECONDS) ? helper.exitValue() : null;
         why = "the helper " + (code != null ? "exited with code " + code : "closed its output") + " before 'ready', at step '" + step[0] + "'";
      }
      List<String> tail;
      synchronized (other) {
         tail = new ArrayList<>(other.subList(Math.max(0, other.size() - 8), other.size()));
      }
      Log.warn("restart: the helper did not get ready after " + ms + " ms: " + why + (tail.isEmpty() ? "" : "; its output: " + String.join(" | ", tail)));
      helper.destroy();
      return false;
   }

   private static final java.util.regex.Pattern CLIXML_ERROR = java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>");

   /**
    * A helper output line for the log. PowerShell writes errors (and progress records) to a redirected stream as CLIXML
    * ("#< CLIXML", then one {@code <Objs>} line): keep the text of its error records, drop the rest.
    */
   static String readable(String s) {
      if (s.equals("#< CLIXML")) {
         return "";
      }
      if (!s.startsWith("<Objs")) {
         return s;
      }
      StringBuilder b = new StringBuilder();
      java.util.regex.Matcher m = CLIXML_ERROR.matcher(s);
      while (m.find()) {
         b.append(m.group(1).replace("_x000D__x000A_", " ")).append(' ');
      }
      return b.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .replaceAll("\\s+", " ").strip();
   }
}
