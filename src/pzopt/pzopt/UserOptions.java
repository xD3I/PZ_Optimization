package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The player's own Config choices, made in the "Optimizations" tab of the options screen
 * (media/lua/client/pzopt/pzopt_optimizations_options.lua) and kept in
 * {@code Zomboid/pzopt/options.ini}. Read by {@link Config} at class init, below
 * {@code -Dpzopt.<key>} and the install dir's {@code pzopt.properties}: the harness writes that
 * file per run, and a run's flags must not depend on what was clicked in the menu.
 *
 * Every key takes effect on the next launch, except live keys such as the Profiler tab's, the Enhancements tab's, and
 * the RawMouse sensitivity / acceleration curve ({@link Config#isLive}): {@link #set} re-reads those at once; consumers
 * read the volatile value on use. The tab shows the values in force
 * ({@link Config#value}) and the stock "restart required" dialog when a change to a next-launch
 * key differs from them. A key that is absent from the file uses the default, so "Default" in the tab
 * removes the key rather than writing the default's value: defaults may change per build and
 * per machine (worker counts).
 *
 * The file is located like {@code ZomboidFileSystem.getCacheDir()} does (the launch's -cachedir=, else
 * deployment.user.cachedir or user.home, plus /Zomboid) without touching that class, because Config initializes
 * inside {@code MainScreenState}'s static initializer, before main() has parsed -cachedir= (issue: settings and
 * the export went to C:\Users\...\Zomboid while the game used -cachedir=E:/Zomboid).
 */
public final class UserOptions {
   static final String FILE_NAME = "options.ini";
   private static final Properties live = read(file());

   private UserOptions() {
   }

   static File file() {
      String override = System.getProperty("pzopt.userOptionsFile");
      if (override != null && !override.isEmpty()) {
         return new File(override);
      }
      return new File(zomboidDir(), "pzopt" + File.separator + FILE_NAME);
   }

   private static File zomboidDir;

   /**
    * The game's user folder (Zomboid/), located as ZomboidFileSystem.getCacheDir() does: the launch's
    * {@code -cachedir=} (MainScreenState.main parses it after Config has read this folder, so it is taken from
    * the process's own command line here), else deployment.user.cachedir or user.home, plus /Zomboid.
    */
   static synchronized File zomboidDir() {
      if (zomboidDir == null) {
         String arg = cacheDirArg(launchArguments());
         if (arg != null) {
            zomboidDir = new File(arg.replace("/", File.separator)).getAbsoluteFile(); // as ZomboidFileSystem.setCacheDir
         } else {
            String root = System.getProperty("deployment.user.cachedir");
            if (root == null || System.getProperty("os.name", "").startsWith("Win")) {
               root = System.getProperty("user.home");
            }
            zomboidDir = new File(root + File.separator + "Zomboid");
         }
      }
      return zomboidDir;
   }

   /**
    * Called by MainScreenState.main once the game's folder is set: says which folder pzopt's files use and warns when it
    * is not the game's (a launcher whose -cachedir= this process's command line does not show).
    */
   public static void checkCacheDir(String gameCacheDir) {
      File ours = zomboidDir().getAbsoluteFile();
      File game = new File(gameCacheDir).getAbsoluteFile();
      if (ours.equals(game)) {
         Log.info("user folder " + game + " (options " + file().getAbsolutePath() + ")");
      } else {
         Log.warn("the game uses " + game + " but pzopt read its options from " + file().getAbsolutePath()
               + " (-cachedir= not found in the launch arguments " + launchArguments() + ")");
      }
   }

   /** The last {@code -cachedir=} argument, as MainScreenState.main reads it (the last one wins there too), or null. */
   static String cacheDirArg(List<String> args) {
      String dir = null;
      for (String a : args) {
         if (a != null && a.startsWith("-cachedir=")) {
            dir = a.replace("-cachedir=", "").trim();
         }
      }
      return dir;
   }

   /**
    * This process's command-line arguments. ProcessHandle has them on Linux (/proc/self/cmdline) and macOS, not on
    * Windows, where kernel32's GetCommandLineW is split as the C runtime splits it for the launcher's main().
    */
   static List<String> launchArguments() {
      try {
         if (System.getProperty("os.name", "").startsWith("Win")) {
            return splitWindowsCommandLine(windowsCommandLine());
         }
         String[] a = ProcessHandle.current().info().arguments().orElse(null);
         if (a != null) {
            return List.of(a);
         }
      } catch (Throwable t) {
         System.out.println("WARN [pzopt] could not read the launch arguments for -cachedir=: " + t);
      }
      String cmd = System.getProperty("sun.java.command"); // the java launcher's main class + arguments (space-joined)
      return cmd != null ? List.of(cmd.split(" ")) : List.of();
   }

   private static String windowsCommandLine() throws Throwable {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
      MethodHandle get = linker.downcallHandle(kernel32.find("GetCommandLineW").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
      MemorySegment p = (MemorySegment)get.invokeExact();
      return p.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
   }

   /**
    * A Windows command line split into arguments by the C runtime's rules: whitespace separates outside quotes,
    * 2n backslashes before a quote give n and the quote toggles quoting, 2n+1 give n and a literal quote, "" inside
    * quotes is a literal quote, other backslashes are literal. The first element (the program) ends at its quote.
    */
   static List<String> splitWindowsCommandLine(String line) {
      List<String> out = new ArrayList<>();
      int i = 0;
      int n = line.length();
      // the program name: quoted or up to whitespace, no backslash rules
      StringBuilder prog = new StringBuilder();
      if (i < n && line.charAt(i) == '"') {
         for (i++; i < n && line.charAt(i) != '"'; i++) {
            prog.append(line.charAt(i));
         }
         i++;
      } else {
         for (; i < n && line.charAt(i) != ' ' && line.charAt(i) != '\t'; i++) {
            prog.append(line.charAt(i));
         }
      }
      out.add(prog.toString());
      while (true) {
         while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
         }
         if (i >= n) {
            return out;
         }
         StringBuilder arg = new StringBuilder();
         boolean quoted = false;
         for (; i < n; i++) {
            char c = line.charAt(i);
            if (c == '\\') {
               int b = 0;
               while (i < n && line.charAt(i) == '\\') {
                  b++;
                  i++;
               }
               if (i < n && line.charAt(i) == '"') {
                  arg.append("\\".repeat(b / 2));
                  if (b % 2 == 1) {
                     arg.append('"');
                     continue;
                  }
               } else {
                  arg.append("\\".repeat(b));
               }
               i--;
               continue;
            }
            if (c == '"') {
               if (quoted && i + 1 < n && line.charAt(i + 1) == '"') {
                  arg.append('"');
                  i++;
               } else {
                  quoted = !quoted;
               }
               continue;
            }
            if (!quoted && (c == ' ' || c == '\t')) {
               break;
            }
            arg.append(c);
         }
         out.add(arg.toString());
      }
   }

   /** The values as they were at boot; {@link Config} reads through this object once. */
   static Properties load() {
      return live;
   }

   static Properties read(File f) {
      Properties p = new Properties();
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   /** The saved value of a key, or null when the key is absent (default in force). */
   public static synchronized String get(String key) {
      return live.getProperty(key);
   }

   /** Stores a value (null or empty removes the key) and rewrites the file at once; a live key (Config.isLive) applies now. */
   public static synchronized void set(String key, String value) {
      if (key == null || key.isEmpty()) {
         return;
      }
      String old = live.getProperty(key);
      if (value == null || value.trim().isEmpty()) {
         if (old == null) {
            return;
         }
         live.remove(key);
      } else {
         value = value.trim();
         if (value.equals(old)) {
            return;
         }
         live.setProperty(key, value);
      }
      // a tab master switch: every key it gates whose value in force moved is applied as if set on its own
      java.util.List<String> gated = Config.gatedBy(key);
      String[] before = null;
      if (gated != null) {
         before = new String[gated.size()];
         for (int i = 0; i < before.length; i++) {
            before[i] = Config.value(gated.get(i));
         }
      }
      boolean now = Config.reloadLive(key);
      if (now && gated != null) {
         boolean overlay = false;
         for (int i = 0; i < before.length; i++) {
            String k = gated.get(i);
            if (Config.isLive(k) && !java.util.Objects.equals(before[i], Config.value(k))) {
               if (Enhancements.owns(k)) {
                  Enhancements.apply(k);
               } else {
                  overlay = true;
               }
            }
         }
         if (overlay) {
            Overlay.reconfigure();
         }
      } else if (now && Enhancements.owns(key)) {
         Enhancements.apply(key);
      } else if (now && !isRawMouseOption(key)) {
         Overlay.reconfigure();
      }
      File f = file();
      try {
         write(f, live);
         Log.info("options: " + key + "=" + (value == null || value.isEmpty() ? "(default)" : value) + " saved to " + f.getPath()
               + (now ? " (applied now)" : " (applies on the next launch)"));
      } catch (IOException e) {
         Log.warn("options: could not write " + f.getAbsolutePath() + ": " + e);
      }
   }
   private static boolean isRawMouseOption(String key) {
      return "mouseSensitivity".equals(key) || "mouseAcceleration".equals(key)
            || "mouseAccelerationOnsetCps".equals(key) || "mouseAccelerationSlopePctPerKcps".equals(key)
            || "mouseAccelerationCapPct".equals(key);
   }

   /**
    * The tabs' "Export settings" copy, beside options.ini. The Lua builds the {@code key=value} lines (only the settings
    * that differ from the build's defaults, grouped per tab); the same text goes to the clipboard.
    */
   static final String EXPORT_NAME = "settings-export.ini";

   static File exportFile() {
      return new File(file().getAbsoluteFile().getParentFile(), EXPORT_NAME);
   }

   /** The export's full text: a comment header (date, build) above {@code body}; every header line is a comment to the import. */
   public static String exportText(String body) {
      String when = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ');
      String commit = BuildInfo.get("commit");
      return "# PZ Optimization settings (Options > PZ Optimization), exported " + when
            + (commit != null && !commit.isEmpty() ? ", build " + commit : "") + "\n"
            + "# Import: Options > PZ Optimization > Import settings... Settings not listed go back to the build's defaults.\n"
            + (body == null ? "" : body);
   }

   /** Writes the export file; its path, or "" when it could not be written (logged). */
   public static String exportWrite(String text) {
      File f = exportFile();
      try {
         File dir = f.getParentFile();
         if (dir != null) {
            dir.mkdirs();
         }
         java.nio.file.Files.writeString(f.toPath(), text == null ? "" : text);
         Log.info("options: settings exported to " + f.getPath());
         return f.getPath();
      } catch (IOException e) {
         Log.warn("options: could not write " + f.getAbsolutePath() + ": " + e);
         return "";
      }
   }

   /** The last export's text, or "" when there is none. */
   public static String exportRead() {
      File f = exportFile();
      if (!f.isFile()) {
         return "";
      }
      try {
         return java.nio.file.Files.readString(f.toPath());
      } catch (IOException e) {
         Log.warn("options: could not read " + f.getAbsolutePath() + ": " + e);
         return "";
      }
   }

   /** One {@code key=value} line per key, sorted, so the file diffs cleanly. */
   static void write(File f, Properties p) throws IOException {
      File dir = f.getAbsoluteFile().getParentFile();
      if (dir != null) {
         dir.mkdirs();
      }
      TreeMap<String, String> sorted = new TreeMap<>();
      for (String k : p.stringPropertyNames()) {
         sorted.put(k, p.getProperty(k));
      }
      try (Writer w = new FileWriter(f)) {
         w.write("# pzopt options chosen in Options > PZ Optimization; keys as in pzopt.properties (see pzopt.Config).\n");
         w.write("# Absent keys use the build's defaults. -Dpzopt.<key> and the install dir's pzopt.properties win over this file.\n");
         for (var e : sorted.entrySet()) {
            w.write(e.getKey() + "=" + e.getValue() + "\n");
         }
      }
   }
}
