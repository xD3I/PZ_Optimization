package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The launcher undo of install.ps1, install.sh and scripts/pzopt.sh, each function cut out of its script and run alone
 * on a temporary copy of the real Windows launcher structure (no installer runs end to end): GcChoice's switch sits in
 * windows."10.0.17134".vmArgs ([-XX:+UseG1GC, -Dpzopt.gc=g1]) beside the stock windows."6.1" [-XX:+UseG1GC]; the undo
 * must give "10.0.17134" its [-XX:+UseZGC] back and leave "6.1" and the top level as they are (also with the pause target
 * and the top-level jitSteady flags). The launcher reset (Reset-Aot / reset_aot) runs once the game is closed and must
 * delete a staged ProjectZomboid64.json.pzopt-pending and a leftover .pzopt-tmp first, so neither can land over it.
 * Interpreters: python3 (or py -3) for the bash scripts' Python, Windows PowerShell (or pwsh) for install.ps1, bash
 * (Git for Windows' on Windows) for reset_aot; a missing one skips its part, which the output says.
 */
public class InstallerLauncherTest {
   static final String TOP = "[\"-Djava.awt.headless=true\",\"--enable-native-access=ALL-UNNAMED\",\"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED\","
         + "\"-Xmx12288m\",\"-Dzomboid.steam=1\",\"-Dzomboid.znetlog=1\",\"-Djava.library.path=win64/;.\",\"-XX:-CreateCoredumpOnCrash\","
         + "\"-XX:-OmitStackTraceInFastThrow\",\"-agentlib:zbNative\"]";
   /** The real launcher after GcChoice's switch. */
   static final String SWITCHED = "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],\"vmArgs\":" + TOP
         + ",\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseG1GC\",\"-Dpzopt.gc=g1\"]}}}";
   /** The same with the pause target, jitSteady at the top level and AotCache's jar form. */
   static final String SWITCHED_AOT = "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\"pzopt/aot/pzopt.jar\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":" + TOP.replace("]", ",\"-Dpzopt.jit=steady\",\"-XX:PerMethodTrapLimit=0\",\"-XX:PerBytecodeTrapLimit=0\","
               + "\"-XX:AOTCache=pzopt/aot/pzopt.aot\",\"-Xlog:aot=info:file=pzopt/aot/aot.log::filecount=0\"]")
         + ",\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseG1GC\",\"-XX:MaxGCPauseMillis=50\",\"-Dpzopt.gc=g1,pause\"]}}}";

   static int failures;
   static final List<String> skipped = new ArrayList<>();

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) throws Exception {
      Path repo = repo();
      Path base = Files.createTempDirectory("pzopt-installer-test");
      try {
         List<String> python = interpreter(List.of(List.of("python3"), List.of("py", "-3"), List.of("python")), "--version");
         for (String script : new String[] {"install.sh", "scripts/pzopt.sh"}) {
            String py = heredoc(lines(repo.resolve(script)), "reset_gc() {");
            if (python == null) {
               skipped.add(script + " reset_gc (no python3)");
               continue;
            }
            Path pyFile = base.resolve(script.replace('/', '-') + ".py");
            Files.writeString(pyFile, py, StandardCharsets.UTF_8);
            for (String fixture : new String[] {SWITCHED, SWITCHED_AOT}) {
               Path g = Files.createTempDirectory(base, "py");
               Path json = g.resolve("ProjectZomboid64.json");
               Files.writeString(json, fixture, StandardCharsets.UTF_8);
               List<String> cmd = new ArrayList<>(python);
               cmd.add(pyFile.toString());
               cmd.add(json.toString());
               run(cmd, null, script + " reset_gc");
               gcUndone(json, script + " reset_gc" + (fixture == SWITCHED ? "" : " (pause, jitSteady)"), false);
            }
         }
         powershell(repo, base);
         bash(repo, base, python != null);
      } finally {
         Updater.deleteTree(base);
      }
      for (String s : skipped) {
         System.out.println("skipped: " + s);
      }
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("InstallerLauncherTest ok");
   }

   /** install.ps1: Reset-Aot then Reset-Gc, as -Uninstall runs them once the game is closed. */
   static void powershell(Path repo, Path base) throws Exception {
      List<String> ps = File.separatorChar == '\\' ? List.of("powershell.exe") : interpreter(List.of(List.of("pwsh")), "-Version");
      if (ps == null) {
         skipped.add("install.ps1 (no PowerShell)");
         return;
      }
      List<String> src = lines(repo.resolve("install.ps1"));
      String functions = function(src, "function Reset-Aot {") + "\n" + function(src, "function Reset-Gc {") + "\n";
      Path g = Files.createDirectories(base.resolve("ps game"));
      Path json = g.resolve("ProjectZomboid64.json");
      Files.writeString(json, SWITCHED_AOT, StandardCharsets.UTF_8);
      staged(g);
      Path script = base.resolve("reset.ps1");
      Files.writeString(script, "\uFEFF$ErrorActionPreference = 'Stop'\n$Dir = '" + g.toString().replace("'", "''") + "'\n$Json = Join-Path $Dir 'ProjectZomboid64.json'\n"
            + functions + "Reset-Aot\nReset-Gc\n", StandardCharsets.UTF_8);
      List<String> cmd = new ArrayList<>(ps);
      cmd.addAll(List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
      run(cmd, null, "install.ps1 Reset-Aot / Reset-Gc");
      stagedGone(g, "install.ps1 Reset-Aot");
      gcUndone(json, "install.ps1 Reset-Gc (pause, jitSteady)", true);
   }

   /** install.sh and scripts/pzopt.sh: reset_aot, the first step of every install / uninstall. */
   static void bash(Path repo, Path base, boolean python) throws Exception {
      String bash = "bash";
      if (File.separatorChar == '\\') {
         Path git = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Git", "bin", "bash.exe");
         if (!Files.isRegularFile(git)) {
            skipped.add("reset_aot (no Git for Windows bash)");
            return;
         }
         bash = git.toString();
      }
      for (String[] s : new String[][] {{"install.sh", "JSON", "dir"}, {"scripts/pzopt.sh", "LAUNCHER_JSON", "PZ_DIR"}}) {
         Path g = Files.createDirectories(base.resolve("sh game " + s[0].replace('/', '-')));
         Path json = g.resolve("ProjectZomboid64.json");
         Files.writeString(json, SWITCHED_AOT, StandardCharsets.UTF_8);
         Files.createDirectories(g.resolve("pzopt/aot"));
         Files.writeString(g.resolve("pzopt/aot/pzopt.jar"), "jar");
         staged(g);
         String dir = g.toString().replace('\\', '/');
         String sh = s[1] + "='" + dir + "/ProjectZomboid64.json'\n" + s[2] + "='" + dir + "'\n" + function(lines(repo.resolve(s[0])), "reset_aot() {")
               + "\nreset_aot\n";
         Path file = base.resolve(s[0].replace('/', '-') + "-reset_aot.sh");
         Files.writeString(file, sh, StandardCharsets.UTF_8);
         run(List.of(bash, file.toString().replace('\\', '/')), null, s[0] + " reset_aot");
         stagedGone(g, s[0] + " reset_aot");
         check(!Files.exists(g.resolve("pzopt/aot")), s[0] + " reset_aot removed pzopt/aot");
         if (python) {
            JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
            check(j.getJSONArray("classpath").toList().equals(List.of(".", "projectzomboid.jar")), s[0] + " reset_aot: loose classpath: " + j);
         }
      }
   }

   static void staged(Path g) throws Exception {
      Files.writeString(g.resolve("ProjectZomboid64.json.pzopt-pending"), SWITCHED, StandardCharsets.UTF_8);
      Files.writeString(g.resolve("ProjectZomboid64.json.pzopt-tmp"), SWITCHED, StandardCharsets.UTF_8);
   }

   static void stagedGone(Path g, String who) {
      check(!Files.exists(g.resolve("ProjectZomboid64.json.pzopt-pending")), who + " deleted the staged ProjectZomboid64.json.pzopt-pending");
      check(!Files.exists(g.resolve("ProjectZomboid64.json.pzopt-tmp")), who + " deleted the leftover ProjectZomboid64.json.pzopt-tmp");
   }

   /** 10.0.17134 back to ZGC, 6.1 and the top level as in the stock launcher; with {@code aotToo} also the loose classpath. */
   static void gcUndone(Path json, String who, boolean aotToo) throws Exception {
      JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8).replace("\uFEFF", ""));
      JSONObject win = j.getJSONObject("windows");
      check(win.getJSONObject("10.0.17134").getJSONArray("vmArgs").toList().equals(List.of("-XX:+UseZGC")),
            who + ": windows.10.0.17134 back to [-XX:+UseZGC]: " + win.getJSONObject("10.0.17134"));
      check(win.getJSONObject("6.1").getJSONArray("vmArgs").toList().equals(List.of("-XX:+UseG1GC")), who + ": windows.6.1 untouched: " + win.getJSONObject("6.1"));
      List<Object> top = j.getJSONArray("vmArgs").toList();
      List<Object> want = new JSONArray(TOP).toList();
      if (!aotToo && j.getJSONArray("classpath").toList().contains("pzopt/aot/pzopt.jar")) {
         want = new ArrayList<>(want); // reset_gc alone leaves AotCache's options to reset_aot
         want.add("-XX:AOTCache=pzopt/aot/pzopt.aot");
         want.add("-Xlog:aot=info:file=pzopt/aot/aot.log::filecount=0");
      }
      check(top.equals(want), who + ": the top level as in the stock launcher: " + top);
      if (aotToo) {
         check(j.getJSONArray("classpath").toList().equals(List.of(".", "projectzomboid.jar")), who + ": loose classpath: " + j.getJSONArray("classpath"));
      }
   }

   static void run(List<String> cmd, Path dir, String who) throws Exception {
      ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
      if (dir != null) {
         pb.directory(dir.toFile());
      }
      Process p = pb.start();
      p.getOutputStream().close();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      boolean ended = p.waitFor(60, TimeUnit.SECONDS);
      check(ended && p.exitValue() == 0, who + " ran (exit " + (ended ? p.exitValue() : "timeout") + "): " + out.strip());
      if (!out.isBlank()) {
         System.out.println(who + ": " + out.strip());
      }
   }

   /** The first command that runs ({@code probe} as its argument) and exits 0, or null. */
   static List<String> interpreter(List<List<String>> candidates, String probe) {
      for (List<String> c : candidates) {
         try {
            List<String> cmd = new ArrayList<>(c);
            cmd.add(probe);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
               return c;
            }
         } catch (Exception ignored) {
         }
      }
      return null;
   }

   static List<String> lines(Path f) throws Exception {
      return Files.readAllLines(f, StandardCharsets.UTF_8).stream().map(l -> l.replace("\r", "")).toList();
   }

   /** The lines from {@code header} to the next line that is a lone "}" (a top-level function of the script). */
   static String function(List<String> src, String header) {
      int from = -1;
      for (int i = 0; i < src.size(); i++) {
         if (src.get(i).startsWith(header)) {
            from = i;
            break;
         }
      }
      if (from < 0) {
         throw new AssertionError("no " + header + " in the script");
      }
      for (int i = from + 1; i < src.size(); i++) {
         if (src.get(i).equals("}")) {
            return String.join("\n", src.subList(from, i + 1));
         }
      }
      throw new AssertionError("no end of " + header);
   }

   /** The Python heredoc ({@code <<'PYEOF'} .. {@code PYEOF}) inside the function {@code header}. */
   static String heredoc(List<String> src, String header) {
      List<String> f = List.of(function(src, header).split("\n", -1));
      int from = -1;
      for (int i = 0; i < f.size(); i++) {
         if (f.get(i).endsWith("<<'PYEOF'")) {
            from = i + 1;
            break;
         }
      }
      for (int i = from; from > 0 && i < f.size(); i++) {
         if (f.get(i).equals("PYEOF")) {
            return String.join("\n", f.subList(from, i)) + "\n";
         }
      }
      throw new AssertionError("no PYEOF heredoc in " + header);
   }

   /** The repository root (the folder holding install.ps1): above this class's build/tests, else above the working directory. */
   static Path repo() throws Exception {
      Path classes = Path.of(InstallerLauncherTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      for (Path start : new Path[] {classes, Path.of("").toAbsolutePath()}) {
         for (Path d = start; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("install.ps1")) && Files.isRegularFile(d.resolve("scripts/pzopt.sh"))) {
               return d;
            }
         }
      }
      throw new AssertionError("no install.ps1 above " + classes + " or " + Path.of("").toAbsolutePath());
   }
}
