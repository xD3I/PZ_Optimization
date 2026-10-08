package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * pzopt.Restart: a child JVM calls {@link Restart#relaunch()} and exits; the helper must start the same program again,
 * after the child ended, with the same arguments (one with a space and a quote), working directory and the restart
 * marks in its environment. On Windows (the PowerShell helper) the folder also holds a staged launcher
 * (ProjectZomboid64.json.pzopt-pending): the new process must find it applied when it starts; once more in a folder
 * whose name has ' and the typographic quotes PowerShell also ends a string at.
 */
public class RestartTest {
   static final String LIVE = "{\"mainClass\":\"m\",\"classpath\":[\"pzopt/aot/pzopt.jar\",\"projectzomboid.jar\"],\"vmArgs\":[\"-XX:AOTCache=pzopt/aot/pzopt.aot\"]}";
   static final String STAGED = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],\"vmArgs\":[\"-Xmx1g\"]}";

   public static void main(String[] args) throws Exception {
      if (args.length > 0 && args[0].equals("child")) {
         child(args);
         return;
      }
      if (java.io.File.separatorChar == '\\') {
         int failures = windows(Files.createTempDirectory("pzopt-restart-test")) + windows(Files.createTempDirectory("pzopt restart it's ‘q’ "));
         if (failures > 0) {
            throw new AssertionError(failures + " check(s) failed");
         }
         System.out.println("RestartTest ok (Windows: the staged launcher was in place when the new process started; helper ready after "
               + String.join(" / ", READY_MS) + " ms)");
         return;
      }
      Path tmp = Files.createTempDirectory("pzopt-restart-test");
      Path marker = tmp.resolve("restarted.txt");
      String java = ProcessHandle.current().info().command().orElseThrow();
      ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "pzopt.RestartTest", "child",
            marker.toString(), "an arg with spaces", "it's quoted").directory(tmp.toFile()).inheritIO();
      pb.environment().remove(Restart.ENV_FROM);
      pb.environment().remove(Restart.ENV_AT);
      Process first = pb.start();
      if (first.waitFor() != 0) {
         throw new AssertionError("first process failed");
      }
      long firstEndedMs = System.currentTimeMillis();
      for (int i = 0; i < 200 && !Files.exists(marker); i++) {
         Thread.sleep(50);
      }
      if (!Files.exists(marker)) {
         throw new AssertionError("no restarted process within 10 s");
      }
      List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
      int failures = 0;
      String[] want = {"child", marker.toString(), "an arg with spaces", "it's quoted"};
      for (int i = 0; i < want.length; i++) {
         if (!want[i].equals(lines.get(i))) {
            System.err.println("FAIL: argument " + i + " is " + lines.get(i) + ", want " + want[i]);
            failures++;
         }
      }
      if (!lines.get(4).equals(tmp.toRealPath().toString()) && !lines.get(4).equals(tmp.toString())) {
         System.err.println("FAIL: working directory " + lines.get(4));
         failures++;
      }
      if (!lines.get(5).equals(Long.toString(first.pid()))) {
         System.err.println("FAIL: " + Restart.ENV_FROM + "=" + lines.get(5) + ", want " + first.pid());
         failures++;
      }
      if (lines.get(6).equals("alive")) {
         System.err.println("FAIL: the new process started while the old one was alive");
         failures++;
      }
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("RestartTest ok (the new process wrote its marker " + (Long.parseLong(lines.get(7)) - firstEndedMs)
            + " ms after the first one ended, JVM start included)");
   }

   /** Windows: one restart in {@code tmp} with a staged launcher; the number of failed checks. */
   static int windows(Path tmp) throws Exception {
      Path marker = tmp.resolve("restarted.txt");
      Path json = tmp.resolve("ProjectZomboid64.json");
      Path pending = tmp.resolve("ProjectZomboid64.json.pzopt-pending");
      Files.writeString(json, LIVE + "\n", StandardCharsets.UTF_8);
      Files.writeString(pending, STAGED + "\n", StandardCharsets.UTF_8);
      Files.setLastModifiedTime(pending, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(json).toMillis() + 1000));
      // javaw: Start-Process gives a console program a window of its own, a GUI one (the game, javaw) none
      Path javaExe = Path.of(ProcessHandle.current().info().command().orElseThrow());
      Path javaw = javaExe.resolveSibling("javaw.exe");
      // the child runs in tmp: the class path absolute
      StringBuilder cp = new StringBuilder();
      for (String e : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
         cp.append(cp.length() > 0 ? java.io.File.pathSeparator : "").append(Path.of(e).toAbsolutePath());
      }
      // the options file (and so the helpers' launcher-helper.log beside it) in tmp, not the user's Zomboid folder
      Path options = tmp.resolve("options.ini");
      // the stand-in game's output (pzopt.Log prints to stdout outside the game) kept for the report
      Path firstOut = tmp.resolve("first.out");
      ProcessBuilder pb = new ProcessBuilder(Files.isRegularFile(javaw) ? javaw.toString() : javaExe.toString(), "-Dpzopt.userOptionsFile=" + options,
            "-cp", cp.toString(), "pzopt.RestartTest", "child", marker.toString(), "an arg with spaces", "it's quoted").directory(tmp.toFile())
            .redirectErrorStream(true).redirectOutput(firstOut.toFile());
      pb.environment().remove(Restart.ENV_FROM);
      pb.environment().remove(Restart.ENV_AT);
      Process first = pb.start();
      int code = first.waitFor();
      java.util.regex.Matcher ready = java.util.regex.Pattern.compile("ready after (\\d+) ms").matcher(tail(firstOut, 100));
      if (ready.find()) {
         READY_MS.add(ready.group(1));
      }
      if (code != 0) {
         throw new AssertionError("first process failed in " + tmp + " with exit code " + code + " (3: Restart.relaunch() returned false)"
               + "\n--- its output:\n" + tail(firstOut, 20) + "\n--- launcher-helper.log:\n" + tail(tmp.resolve("launcher-helper.log"), 20));
      }
      for (int i = 0; i < 800 && !Files.exists(marker); i++) {
         Thread.sleep(50);
      }
      if (!Files.exists(marker)) {
         System.err.println("FAIL: no restarted process within 40 s in " + tmp + "\n--- the first process's output:\n" + tail(firstOut, 20)
               + "\n--- launcher-helper.log:\n" + tail(tmp.resolve("launcher-helper.log"), 20));
         return 1;
      }
      List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
      int failures = 0;
      String[] want = {"child", marker.toString(), "an arg with spaces", "it's quoted"};
      for (int i = 0; i < want.length; i++) {
         if (!want[i].equals(lines.get(i))) {
            System.err.println("FAIL: argument " + i + " is " + lines.get(i) + ", want " + want[i]);
            failures++;
         }
      }
      if (!lines.get(4).equalsIgnoreCase(tmp.toRealPath().toString()) && !lines.get(4).equalsIgnoreCase(tmp.toString())) {
         System.err.println("FAIL: working directory " + lines.get(4));
         failures++;
      }
      if (!lines.get(5).equals(Long.toString(first.pid())) || !lines.get(6).equals("ended")) {
         System.err.println("FAIL: restarted from " + lines.get(5) + " (want " + first.pid() + "), old process " + lines.get(6));
         failures++;
      }
      if (!lines.get(8).equals(STAGED) || !lines.get(9).equals("no pending")) {
         System.err.println("FAIL: the new process started with launcher " + lines.get(8) + " / " + lines.get(9) + ", want the staged one applied");
         failures++;
      }
      // the detached helper's notes (its last line follows the start of the new process)
      Path helperLog = tmp.resolve("launcher-helper.log");
      String log = "";
      for (int i = 0; i < 100 && !log.contains("restart: started"); i++) {
         log = Files.exists(helperLog) ? new String(Files.readAllBytes(helperLog), StandardCharsets.UTF_8) : "";
         Thread.sleep(50);
      }
      if (!log.matches("(?s).*restart: helper pid \\d+ waits for pid " + first.pid() + "\\b.*") || !log.contains("restart: pid " + first.pid() + " ended")
            || !log.contains("restart: ProjectZomboid64.json.pzopt-pending applied") || !log.contains("restart: started")) {
         System.err.println("FAIL: launcher-helper.log: " + log);
         failures++;
      }
      Updater.deleteTree(tmp);
      return failures;
   }

   /** The ready times Restart logged ("ready after N ms"), one per Windows run. */
   static final List<String> READY_MS = new java.util.ArrayList<>();

   /** The last {@code n} lines of a file (decoded leniently), or "(none)". */
   static String tail(Path f, int n) throws Exception {
      if (!Files.exists(f)) {
         return "(none)";
      }
      List<String> lines = List.of(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\r?\n"));
      return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
   }

   static void child(String[] args) throws Exception {
      Path marker = Path.of(args[1]);
      if (!Restart.restarted()) {
         if (!Restart.relaunch()) {
            System.exit(3);
         }
         Thread.sleep(300); // like the game's quit: the helper must wait for this process to end
         System.exit(0);
      }
      String from = System.getenv(Restart.ENV_FROM);
      boolean oldAlive = ProcessHandle.of(Long.parseLong(from)).map(ProcessHandle::isAlive).orElse(false);
      // the launcher JSON as this process found it at its start (Windows: the staged one must be in place by then)
      Path json = Path.of("ProjectZomboid64.json");
      String seen = Files.exists(json) ? Files.readString(json, StandardCharsets.UTF_8).strip() : "none";
      boolean pending = Files.exists(Path.of("ProjectZomboid64.json.pzopt-pending"));
      Path tmp = marker.resolveSibling("restarted.tmp");
      Files.writeString(tmp, String.join("\n", args) + "\n" + Path.of("").toAbsolutePath() + "\n" + from + "\n"
            + (oldAlive ? "alive" : "ended") + "\n" + System.currentTimeMillis() + "\n" + seen + "\n" + (pending ? "pending" : "no pending") + "\n",
            StandardCharsets.UTF_8);
      Files.move(tmp, marker);
   }
}
