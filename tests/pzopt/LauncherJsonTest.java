package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * pzopt.LauncherJson on a temporary game folder. Nothing holding the JSON: a save replaces it (the backup is the
 * original, no tmp, no pending file). Windows, the JSON held open without delete sharing the way the running game holds
 * it: the plain replace is refused (the bug), a save stages the change as .pzopt-pending and returns false, read()
 * returns the staged copy so the boot's second writer builds on the first one's change, and the exit helper moves it
 * over the JSON once the watched process has ended (retrying while a handle outlives it). Elsewhere the hold does not
 * block a rename. An invalid pending file is ignored, and a save that lands removes any pending file. A pending file
 * older than the live JSON (something rewrote the launcher after the staging) is dropped by read() and by the helper.
 * A helper that cannot start is not counted as armed: the next save starts one. A pending file a landed save cannot
 * delete is logged, not thrown. The folders' names hold ' and the typographic quotes PowerShell also ends a string at.
 */
public class LauncherJsonTest {
   private static final boolean WINDOWS = File.separatorChar == '\\';
   private static final String STOCK = "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Djava.awt.headless=true\",\"-Xmx3072m\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}";

   public static void main(String[] args) throws Exception {
      Path base = Files.createTempDirectory("pzopt launcher it's ‘q’ ");
      LauncherJson.helperLog = base.resolve("launcher-helper.log");
      Process dummy = null;
      try {
         if (WINDOWS) {
            // the helper a staged save arms waits for this process instead of the test JVM
            dummy = quiet(new ProcessBuilder("ping", "-n", "120", "127.0.0.1")).start();
            LauncherJson.helperPid = dummy.pid();
         }
         Path g = game(base.resolve("held"));
         JSONObject j1 = noHolder(g);
         invalidPending(game(base.resolve("invalid")));
         stalePending(game(base.resolve("stale-read")));
         if (WINDOWS) {
            inheritedHandleOnWindows(game(base.resolve("inherited")));
            heldOnWindows(g, j1, dummy);
            missingJsonOnWindows(game(base.resolve("missing")));
            staleHelperOnWindows(game(base.resolve("stale-helper")));
            undeletablePendingOnWindows(game(base.resolve("undeletable")));
         } else {
            heldElsewhere(g, j1);
         }
         saveRemovesPending(game(base.resolve("landed")));
      } finally {
         if (dummy != null) {
            dummy.destroy();
            innerEnds("exit", dummy.pid(), 20);
         }
         Updater.deleteTree(base);
      }
      System.out.println("LauncherJsonTest ok");
   }

   /** 1. Nothing holds the JSON: the save lands. */
   static JSONObject noHolder(Path g) throws Exception {
      JSONObject j1 = LauncherJson.read(g);
      Check.check(j1.similar(new JSONObject(STOCK)), "read returns the live JSON");
      j1.getJSONArray("vmArgs").put("-Dpzopt.test=one");
      Check.check(LauncherJson.save(g, j1), "nothing holds the JSON: save replaces it and returns true");
      Check.check(json(g).similar(j1), "the JSON is the saved one: " + json(g));
      Check.check(!Files.exists(pending(g)), "no pending file after a save that landed");
      Check.check(!Files.exists(tmp(g)), "no tmp file after a save that landed");
      Check.check(Files.readString(g.resolve("ProjectZomboid64.json.pzopt-backup"), StandardCharsets.UTF_8).equals(STOCK),
            "the backup holds the original");
      return j1;
   }

   /** 2. Windows: the JSON held the way the running game holds it. */
   static void heldOnWindows(Path g, JSONObject j1, Process dummy) throws Exception {
      Path json = g.resolve("ProjectZomboid64.json");
      Path pending = pending(g);
      Path tmp = tmp(g);
      String staged;
      try (FileInputStream hold = new FileInputStream(json.toFile())) { // no FILE_SHARE_DELETE, as the launcher
         Files.writeString(tmp, j1.toString(1) + "\n", StandardCharsets.UTF_8);
         boolean denied = false;
         try {
            Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AccessDeniedException e) {
            denied = true;
         }
         Files.deleteIfExists(tmp);
         Check.check(denied, "the hold reproduces the bug: the plain replace is refused with AccessDeniedException");

         // the helper cannot start: the change is staged, nothing counts as armed, a WARN says so; the next save starts it
         String ps = LauncherJson.powershell;
         LauncherJson.powershell = "pzopt-no-such-powershell.exe";
         JSONObject j0 = new JSONObject(json(g).toString());
         boolean[] landed = {true};
         String log = logOf(() -> landed[0] = LauncherJson.save(g, j0));
         LauncherJson.powershell = ps;
         Check.check(!landed[0] && Files.exists(pending), "a held JSON with no helper: the change is still staged");
         Check.check(LauncherJson.armedHelper == null, "a helper that did not start is not counted as armed");
         Check.check(log.contains("WARN") && log.contains("exit helper"), "the failed start is logged as a warning: " + log);
         Check.check(!LauncherJson.save(g, j0) && LauncherJson.armedHelper != null, "the next save starts the helper");

         JSONObject j2 = LauncherJson.read(g);
         j2.getJSONArray("vmArgs").put("-Dpzopt.test=two");
         Check.check(!LauncherJson.save(g, j2), "a held JSON: save stages the change and returns false");
         Check.check(json(g).similar(j1), "the live JSON is unchanged: " + json(g));
         Check.check(Files.exists(pending) && read(pending).similar(j2), "the pending file holds the change");
         Check.check(!Files.exists(tmp), "no tmp file after a staged save");
         Check.check(LauncherJson.read(g).similar(j2), "read returns the staged change");
         Process armed = LauncherJson.armedHelper;
         Check.check(armed != null && innerAlive("exit", dummy.pid()), "the staged save armed the exit helper, which waits detached");

         JSONObject j3 = LauncherJson.read(g);
         j3.getJSONArray("vmArgs").put("-Dpzopt.test=three");
         Check.check(!LauncherJson.save(g, j3), "the second writer is staged too");
         JSONObject p = read(pending);
         Check.check(has(p, "-Dpzopt.test=one") && has(p, "-Dpzopt.test=two") && has(p, "-Dpzopt.test=three"),
               "the pending file holds both writers' changes: " + p);
         Check.check(json(g).similar(j1), "the live JSON is still unchanged");
         Check.check(!Files.exists(tmp), "no tmp file after the second staged save");
         Check.check(LauncherJson.armedHelper == armed, "the helper is armed once per process");
         staged = Files.readString(pending, StandardCharsets.UTF_8);
      }

      // the game ends: the helper moves the pending file over the JSON (a helper that did not wait moved it ~2.5 s after
      // its start here, outer and inner PowerShell included, so the check at 5 s, with the watched process alive ~7 s,
      // tells the two apart)
      Process child = quiet(new ProcessBuilder("ping", "-n", "8", "127.0.0.1")).start();
      LauncherJson.startExitHelper(g, child.pid());
      Thread.sleep(5000);
      boolean alive = child.isAlive();
      boolean stillPending = Files.exists(pending);
      Check.check(alive, "the watched process outlives the first check");
      Check.check(stillPending && json(g).similar(j1), "nothing moves while the watched process runs");
      Check.check(child.waitFor(20, TimeUnit.SECONDS), "the watched process ends");
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline
            && (Files.exists(pending) || !Files.readString(json, StandardCharsets.UTF_8).equals(staged))) {
         Thread.sleep(100);
      }
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(staged), "the helper moved the staged JSON in: " + json(g));
      Check.check(!Files.exists(pending), "the pending file is gone");
      Check.check(innerEnds("exit", child.pid(), 20), "the helper ends");
      String log = helperLog();
      Check.check(log.contains("exit: pid " + child.pid() + " ended") && log.contains("exit: ProjectZomboid64.json.pzopt-pending applied"),
            "the helper log has the end of the watched process and the outcome: " + log);

      // the helper the staged save armed: its process ends, nothing is pending, the JSON stays
      dummy.destroy();
      Check.check(innerEnds("exit", dummy.pid(), 20), "the armed helper ends after its process");
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(staged), "the armed helper left the applied JSON alone");
      Check.check(helperLog().contains("exit: ProjectZomboid64.json.pzopt-pending none staged"), "the armed helper logged that nothing was staged");

      // a handle that outlives the watched process: the helper keeps trying and applies the change once it is released
      String restaged;
      Process child2;
      try (FileInputStream hold = new FileInputStream(json.toFile())) {
         JSONObject j4 = LauncherJson.read(g);
         j4.getJSONArray("vmArgs").put("-Dpzopt.test=four");
         Check.check(!LauncherJson.save(g, j4), "held again: the change is staged");
         restaged = Files.readString(pending, StandardCharsets.UTF_8);
         child2 = quiet(new ProcessBuilder("ping", "-n", "2", "127.0.0.1")).start();
         LauncherJson.startExitHelper(g, child2.pid());
         Check.check(child2.waitFor(20, TimeUnit.SECONDS), "the second watched process ends");
         Check.check(innerPid("exit", child2.pid()) > 0, "the second helper started");
         Thread.sleep(3000);
         Check.check(innerAlive("exit", child2.pid()) && Files.exists(pending) && Files.readString(json, StandardCharsets.UTF_8).equals(staged),
               "while the JSON is still held the helper keeps trying and changes nothing");
      }
      deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline
            && (Files.exists(pending) || !Files.readString(json, StandardCharsets.UTF_8).equals(restaged))) {
         Thread.sleep(100);
      }
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(restaged), "released: the helper moved the change in");
      Check.check(!Files.exists(pending), "released: the pending file is gone");
      Check.check(innerEnds("exit", child2.pid(), 20), "the retrying helper ends");
   }

   /** The helper log as text (it may be half written: decoded leniently). */
   static String helperLog() throws Exception {
      Path log = LauncherJson.helperLog;
      return Files.exists(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
   }

   /** The detached helper's own pid from its start line ("<tag>: helper pid N waits for pid W"), within 20 s; else -1. */
   static long innerPid(String tag, long watched) throws Exception {
      java.util.regex.Pattern p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(tag) + ": helper pid (\\d+) waits for pid " + watched + "(?!\\d)");
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline) {
         java.util.regex.Matcher m = p.matcher(helperLog());
         if (m.find()) {
            return Long.parseLong(m.group(1));
         }
         Thread.sleep(50);
      }
      return -1;
   }

   static boolean innerAlive(String tag, long watched) throws Exception {
      long pid = innerPid(tag, watched);
      return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
   }

   /** True when the detached helper started and ended within {@code seconds}. */
   static boolean innerEnds(String tag, long watched, long seconds) throws Exception {
      long pid = innerPid(tag, watched);
      if (pid < 0) {
         return false;
      }
      java.util.Optional<ProcessHandle> h = ProcessHandle.of(pid);
      if (h.isEmpty()) {
         return true;
      }
      try {
         h.get().onExit().get(seconds, TimeUnit.SECONDS);
         return true;
      } catch (java.util.concurrent.TimeoutException e) {
         return false;
      }
   }

   /**
    * Windows: the JSON is gone and the pending file is left (ReplaceFile without a backup name can fail after removing
    * the replaced file): the helper moves the pending file into its place.
    */
   static void missingJsonOnWindows(Path g) throws Exception {
      Path json = g.resolve("ProjectZomboid64.json");
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=restored");
      String text = staged.toString(1) + "\n";
      Files.writeString(pending(g), text, StandardCharsets.UTF_8);
      Files.delete(json);
      Process child = quiet(new ProcessBuilder("ping", "-n", "2", "127.0.0.1")).start();
      LauncherJson.startExitHelper(g, child.pid());
      Check.check(child.waitFor(20, TimeUnit.SECONDS), "the watched process ends");
      Check.check(innerEnds("exit", child.pid(), 30), "the helper ends");
      Check.check(Files.exists(json) && Files.readString(json, StandardCharsets.UTF_8).equals(text),
            "the helper moved the pending file into the missing JSON's place");
      Check.check(!Files.exists(pending(g)), "the pending file is gone");
      Check.check(helperLog().contains("exit: ProjectZomboid64.json.pzopt-pending moved into place"), "the log says so: " + helperLog());
   }

   /**
    * Windows: the native launcher reads the JSON through the C runtime, whose handles are inheritable, and Java starts a
    * child process with handle inheritance; a helper started that way kept the launcher's handle on the JSON and blocked
    * its own replace for all its tries (real session, 2026-10-01 12:29). Here a handle opened like the C runtime's
    * (read, sharing read and write but not delete, inheritable) is open while the helper starts and closed when the
    * "game" ends: the helper must have inherited nothing, so the staged launcher goes in.
    */
   static void inheritedHandleOnWindows(Path g) throws Exception {
      Path json = g.resolve("ProjectZomboid64.json");
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=inherited");
      String text = staged.toString(1) + "\n";
      Files.writeString(pending(g), text, StandardCharsets.UTF_8);
      Files.setLastModifiedTime(pending(g), FileTime.fromMillis(Files.getLastModifiedTime(json).toMillis() + 1000));
      Process child = quiet(new ProcessBuilder("ping", "-n", "3", "127.0.0.1")).start();
      java.lang.foreign.MemorySegment handle = Win32.openInheritable(json);
      try {
         LauncherJson.startExitHelper(g, child.pid()); // created while the inheritable handle is open
      } finally {
         Win32.close(handle); // the "game" ends: its own handle goes
      }
      Check.check(child.waitFor(20, TimeUnit.SECONDS), "inherited: the watched process ends");
      long deadline = System.currentTimeMillis() + 30_000;
      while (System.currentTimeMillis() < deadline
            && (Files.exists(pending(g)) || !Files.readString(json, StandardCharsets.UTF_8).equals(text))) {
         Thread.sleep(100);
      }
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(text) && !Files.exists(pending(g)),
            "inherited: a helper started while an inheritable handle on the JSON was open applied the staged launcher: " + json(g));
      Check.check(innerEnds("exit", child.pid(), 20) && helperLog().contains("exit: pid " + child.pid() + " ended"),
            "inherited: the helper logged its start and the watched process's end: " + helperLog());
   }

   /** kernel32 through java.lang.foreign: a handle on a file opened the way the C runtime's fopen opens one. */
   static final class Win32 {
      private static final java.lang.foreign.Linker LINKER = java.lang.foreign.Linker.nativeLinker();
      private static final java.lang.foreign.SymbolLookup KERNEL32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32",
            java.lang.foreign.Arena.global());
      private static final java.lang.invoke.MethodHandle CREATE_FILE = LINKER.downcallHandle(KERNEL32.find("CreateFileW").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS,
                  java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS,
                  java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS));
      private static final java.lang.invoke.MethodHandle SET_INFORMATION = LINKER.downcallHandle(KERNEL32.find("SetHandleInformation").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS,
                  java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));
      private static final java.lang.invoke.MethodHandle CLOSE = LINKER.downcallHandle(KERNEL32.find("CloseHandle").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS));

      /** GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE (no delete sharing), OPEN_EXISTING, then HANDLE_FLAG_INHERIT. */
      static java.lang.foreign.MemorySegment openInheritable(Path f) throws Exception {
         try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.MemorySegment name = a.allocateFrom(f.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            java.lang.foreign.MemorySegment h = (java.lang.foreign.MemorySegment) CREATE_FILE.invokeExact(name, 0x80000000, 3,
                  java.lang.foreign.MemorySegment.NULL, 3, 0x80, java.lang.foreign.MemorySegment.NULL);
            if (h.address() == -1L) {
               throw new java.io.IOException("CreateFileW failed: " + f);
            }
            if ((int) SET_INFORMATION.invokeExact(h, 1, 1) == 0) {
               throw new java.io.IOException("SetHandleInformation failed: " + f);
            }
            return h;
         } catch (Exception e) {
            throw e;
         } catch (Throwable t) {
            throw new RuntimeException(t);
         }
      }

      static void close(java.lang.foreign.MemorySegment h) {
         try {
            int ok = (int) CLOSE.invokeExact(h);
         } catch (Throwable t) {
            throw new RuntimeException(t);
         }
      }
   }

   /** read(): a pending file older than the live JSON was overtaken (an installer, Steam): dropped, the live JSON read. */
   static void stalePending(Path g) throws Exception {
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=overtaken");
      Files.writeString(pending(g), staged.toString(1), StandardCharsets.UTF_8);
      FileTime t = Files.getLastModifiedTime(pending(g));
      Files.setLastModifiedTime(g.resolve("ProjectZomboid64.json"), FileTime.fromMillis(t.toMillis() + 10_000));
      JSONObject[] got = new JSONObject[1];
      String log = logOf(() -> got[0] = LauncherJson.read(g));
      Check.check(got[0].similar(new JSONObject(STOCK)), "a pending file older than the JSON: read returns the live JSON: " + got[0]);
      Check.check(!Files.exists(pending(g)), "the overtaken pending file is deleted");
      Check.check(log.contains("pzopt-pending") && log.lines().count() == 1, "one log line says so: " + log);
      Check.check(LauncherJson.armedHelper == null, "an overtaken pending file arms no helper");
   }

   /** Windows: the helper finds a pending file older than the JSON: it deletes it and leaves the JSON alone. */
   static void staleHelperOnWindows(Path g) throws Exception {
      Path json = g.resolve("ProjectZomboid64.json");
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=overtaken");
      Files.writeString(pending(g), staged.toString(1), StandardCharsets.UTF_8);
      Files.setLastModifiedTime(json, FileTime.fromMillis(Files.getLastModifiedTime(pending(g)).toMillis() + 10_000));
      String live = Files.readString(json, StandardCharsets.UTF_8);
      Process child = quiet(new ProcessBuilder("ping", "-n", "2", "127.0.0.1")).start();
      LauncherJson.startExitHelper(g, child.pid());
      Check.check(child.waitFor(20, TimeUnit.SECONDS) && innerEnds("exit", child.pid(), 30), "the watched process and the helper end");
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(live), "the helper left the newer JSON alone");
      Check.check(!Files.exists(pending(g)), "the helper deleted the overtaken pending file");
      Check.check(helperLog().contains("exit: ProjectZomboid64.json.pzopt-pending older than ProjectZomboid64.json: deleted"),
            "the log says so: " + helperLog());
   }

   /**
    * Windows: a save lands but the pending file cannot be deleted (held without delete sharing): logged, not thrown,
    * and the leftover is overtaken by the JSON the save wrote, so the next read drops it.
    */
   static void undeletablePendingOnWindows(Path g) throws Exception {
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=leftover");
      Files.writeString(pending(g), staged.toString(1), StandardCharsets.UTF_8);
      Files.setLastModifiedTime(pending(g), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
      JSONObject j = new JSONObject(STOCK);
      j.getJSONArray("vmArgs").put("-Dpzopt.test=landed-anyway");
      boolean[] landed = {false};
      String log;
      try (FileInputStream hold = new FileInputStream(pending(g).toFile())) {
         log = logOf(() -> landed[0] = LauncherJson.save(g, j));
      }
      Check.check(landed[0] && json(g).similar(j), "the save landed although the pending file could not be deleted");
      Check.check(log.contains("WARN") && log.contains("pzopt-pending"), "the leftover is logged: " + log);
      Check.check(!Files.exists(tmp(g)), "no tmp file");
      Check.check(LauncherJson.read(g).similar(j) && !Files.exists(pending(g)), "the next read drops the overtaken leftover");
   }

   /** Runs {@code b} with System.out captured (pzopt.Log prints there outside the game); echoes and returns it. */
   static String logOf(Body b) throws Exception {
      java.io.PrintStream old = System.out;
      java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
      System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
      try {
         b.run();
      } finally {
         System.setOut(old);
      }
      String s = buf.toString(StandardCharsets.UTF_8);
      System.out.print(s);
      return s;
   }

   interface Body {
      void run() throws Exception;
   }

   /** 3. Linux / macOS: an open file does not block a rename over it. */
   static void heldElsewhere(Path g, JSONObject j1) throws Exception {
      JSONObject j2 = LauncherJson.read(g);
      j2.getJSONArray("vmArgs").put("-Dpzopt.test=two");
      try (FileInputStream hold = new FileInputStream(g.resolve("ProjectZomboid64.json").toFile())) {
         Check.check(LauncherJson.save(g, j2), "elsewhere an open JSON does not block the replace");
      }
      Check.check(json(g).similar(j2), "the JSON is the saved one: " + json(g));
      Check.check(!Files.exists(pending(g)) && !Files.exists(tmp(g)), "no pending or tmp file");
   }

   /** 4. An invalid pending file is ignored and arms nothing. */
   static void invalidPending(Path g) throws Exception {
      JSONObject stock = new JSONObject(STOCK);
      for (String bad : new String[] {"{}", "not json", "{\"mainClass\":\"m\",\"classpath\":[],\"vmArgs\":[\"-Xmx1g\"]}",
            "{\"mainClass\":\"m\",\"classpath\":[\".\"]}"}) {
         Files.writeString(pending(g), bad, StandardCharsets.UTF_8);
         Check.check(LauncherJson.read(g).similar(stock), "an invalid pending file (" + bad + ") falls back to the live JSON");
      }
      Check.check(LauncherJson.armedHelper == null, "an invalid pending file arms no helper");
   }

   /** A valid pending file is what read() returns; a save that lands removes it. */
   static void saveRemovesPending(Path g) throws Exception {
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=staged");
      Files.writeString(pending(g), staged.toString(1), StandardCharsets.UTF_8);
      // on Windows the helper is armed already (heldOnWindows), so this read starts no second one
      Check.check(LauncherJson.read(g).similar(staged), "read returns a valid pending file");
      JSONObject j = new JSONObject(STOCK);
      j.getJSONArray("vmArgs").put("-Dpzopt.test=landed");
      Check.check(LauncherJson.save(g, j), "the save lands");
      Check.check(json(g).similar(j), "the JSON is the saved one");
      Check.check(!Files.exists(pending(g)), "a save that lands removes the pending file");
      Check.check(LauncherJson.read(g).similar(j), "read returns the live JSON again");
   }

   static Path game(Path g) throws Exception {
      Files.createDirectories(g);
      Files.writeString(g.resolve("ProjectZomboid64.json"), STOCK, StandardCharsets.UTF_8);
      return g;
   }

   static ProcessBuilder quiet(ProcessBuilder pb) {
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      return pb;
   }

   static Path pending(Path g) {
      return g.resolve("ProjectZomboid64.json.pzopt-pending");
   }

   static Path tmp(Path g) {
      return g.resolve("ProjectZomboid64.json.pzopt-tmp");
   }

   static JSONObject json(Path g) throws Exception {
      return read(g.resolve("ProjectZomboid64.json"));
   }

   static JSONObject read(Path f) throws Exception {
      return new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
   }

   static boolean has(JSONObject j, String arg) {
      JSONArray a = j.getJSONArray("vmArgs");
      for (int i = 0; i < a.length(); i++) {
         if (a.getString(i).equals(arg)) {
            return true;
         }
      }
      return false;
   }
}
