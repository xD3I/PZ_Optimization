package pzopt;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Java mod compatibility (2026-10-01, {@code modCompat}). We ship whole classes; a Java mod patches methods of the same
 * classes at load time (a {@code -javaagent} such as PZMulticore, or a ZombieBuddy mod: {@code javaJarFile=} in its
 * mod.info, {@code @Patch(className, methodName)} classes). Its patch lands on our bytecode. A method without a
 * {@code // pzopt:} edit is stock-equivalent (scripts/bytecode-audit.py), so a patch there meets what its author
 * tested; a patch of an edited method can wrap or replace half of one of our features.
 *
 * <p>At Config's class init (before any key is read) this scans the launch's Java mods: the {@code -javaagent} jars of
 * the JVM's arguments and the jars of the mods {@code Zomboid/mods/default.txt} enables (Zomboid/mods, the game's
 * mods folder, the Workshop folder beside the game; {@link #modJars}), each jar's result cached until it changes
 * ({@link JarCache}). For each class file it reads ZombieBuddy's {@code @Patch}
 * annotations, and, for transformer-style agents, string constants naming one of our shadowed classes plus string
 * constants naming one of that class's edited methods. A hit on an edited method switches off the boolean keys that
 * method reads (scripts/override-methods.py writes the map), unless the mod is on the {@link #KNOWN} list (tested
 * together) or {@code modCompat=report}. The keys sit below the player's options.ini: an explicit choice on the tab,
 * -D or pzopt.properties wins. Report: console {@code mod compat:} lines and {@code Zomboid/pzopt/mod-compat.txt}.
 */
public final class ModCompat {
   private ModCompat() {
   }

   /**
    * Mods run together with ours and checked (docs/findings-mod-compat-2026-10-01.md): "ok" = their patches of edited
    * methods are known to work with ours (the conflicts were fixed on our side), else "off:key,key" = switch these.
    * A player's Zomboid/pzopt/mod-compat.ini adds or replaces entries ({@code <mod id>=ok|off:keys|auto}).
    */
   static final String[][] KNOWN = {
      // ZombieBuddy 2.3.3 hooks the game's own entry points (GameWindow.init, Display.create, LuaEventManager, UIManager)
      // with enter / exit advice around our bodies; its loader finds no Java mod on 42.21 with or without ours (matrix
      // run mc-zb-stock, 2026-10-01), its own patches run fine with ours (mc-zbb, mc-lugli)
      {"ZombieBuddy", "ok"},
      // Ultimate ZBetterFPS 1.4.9 reads pzopt.Overrides.enabled() and wraps our IsoChunk.recalcPooled on purpose
      // (runs zb149-*, 2026-09-24 on 42.20, 0 errors; not loadable on 42.21 until ZombieBuddy is)
      {"ZBBetterFPS", "ok"},
      // PZMulticore agent: the four fixes of 38fd8c6 (streamer anchor pzoptDoChunk, lccMain lock, per-thread caches,
      // soundList readers), runs with ours + it on Louisville 2026-09-24; of ours it patches only IsoChunkMap.CalcChunkWidth
      // (an edit without a switch). Its 42.20 build logs one bucket overflow of its own on 42.21 (mc-pzmc)
      {"PZMulticore", "ok"},
   };

   static final String ZB_PATCH = "Lme/zed_0xff/zombie_buddy/Patch;";

   /** One place a mod touches a class we ship. */
   static final class Hit {
      final String source; // mod id, or the agent jar's file name
      final String jar; // the jar file's name (null in tests)
      final String cls; // internal name, zombie/iso/IsoChunk
      final String method; // null: the class only (a transformer naming it, no method found)
      final String how;

      Hit(String source, String cls, String method, String how) {
         this(source, null, cls, method, how);
      }

      Hit(String source, String jar, String cls, String method, String how) {
         this.source = source;
         this.jar = jar;
         this.cls = cls;
         this.method = method;
         this.how = how;
      }

      String where() {
         return this.cls.replace('/', '.') + (this.method != null ? "." + this.method : "");
      }
   }

   private static final List<String> report = new ArrayList<>();
   private static final Map<String, String> reasons = new TreeMap<>(); // key -> why compat switched it off
   private static final List<String> notes = new ArrayList<>(); // one line per mod for the tab
   private static final Map<String, List<File>> scanned = new LinkedHashMap<>(); // source -> its jars, for the menu's check
   private static final List<Hit> found = new ArrayList<>();
   private static final Map<String, String> verdicts = new LinkedHashMap<>(); // source -> "<status>\t<keys>"
   private static final Map<String, Boolean> agents = new HashMap<>(); // source -> loaded with -javaagent
   private static final List<String> problems = new ArrayList<>(); // jars or files the scan could not read
   private static Map<String, Map<String, String[]>> editedSeen = new HashMap<>();
   private static int enabledCount;
   private static String mode = "auto";
   private static long scanMs;
   private static long dirsMs; // modDirectories inside sources()
   private static String split = ""; // where the scan's time went, for the report
   private static boolean logged;

   /**
    * Config, at class init: the values compat gives keys (always "false"), empty when nothing conflicts or
    * {@code modCompat=off}. {@code setting} is the modCompat value from -D / pzopt.properties / options.ini.
    */
   static Properties scan(String setting) {
      Properties out = new Properties();
      mode = setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT);
      if (mode.equals("off")) {
         report.add("mod compat: off (modCompat=off), Java mods not scanned");
         return out;
      }
      long t0 = System.nanoTime();
      try {
         Map<String, Map<String, String[]>> edited = editedMethods();
         Set<String> shadowed = shadowedClasses();
         List<Hit> hits = new ArrayList<>();
         Map<String, String> policy = knownPolicies();
         editedSeen = edited;
         JarCache cache = JarCache.load(new File(UserOptions.file().getParentFile(), JarCache.FILE_NAME), stamp(edited, shadowed));
         long t1 = System.nanoTime();
         scanned.putAll(sources());
         long t2 = System.nanoTime();
         for (Map.Entry<String, List<File>> src : scanned.entrySet()) {
            for (File jar : src.getValue()) {
               scanJar(src.getKey(), jar, shadowed, edited, hits, cache);
            }
         }
         found.addAll(hits);
         cache.save();
         split = String.format("; mod folders %d ms, %d jar(s) found in %d ms, scanned in %d ms (%d from the cache)", dirsMs,
               scanned.values().stream().mapToInt(List::size).sum(), (t2 - t1) / 1_000_000L - dirsMs, (System.nanoTime() - t2) / 1_000_000L,
               cache.fromCache);
         apply(hits, edited, policy, out);
      } catch (Throwable t) {
         problem("mod compat: scan failed (" + t + "); nothing switched");
      }
      scanMs = (System.nanoTime() - t0) / 1_000_000L;
      writeReport();
      return out;
   }

   /** Overrides.onClassLoaded once the game's log exists: the report into console.txt. */
   static void logOnce() {
      if (logged) {
         return;
      }
      logged = true;
      for (String line : report) {
         Log.info(line);
      }
   }

   /** Why compat switched this key off, or null. */
   public static String reason(String key) {
      return reasons.get(key);
   }

   /** One line per mod with Java patches of our classes, for the Optimizations tab (empty when none). */
   public static String summary() {
      return String.join("\n", notes);
   }

   /**
    * The main menu's compatibility check (pzopt_mainscreen_compat.lua): one tab-separated line per fact, the Lua groups
    * them by jar file. {@code scan mode ms enabledMods} / {@code problem text} / {@code mod source agent|mod status keys}
    * (status ok, off, report, edits, none) / {@code jar source fileName path} / {@code hit source fileName Class.method
    * edited|stock|class how keys}; one hit line per distinct place a jar patches.
    */
   public static String details() {
      StringBuilder b = new StringBuilder();
      b.append("scan\t").append(mode).append('\t').append(scanMs).append('\t').append(enabledCount).append('\n');
      for (String p : problems) {
         b.append("problem\t").append(p.replace('\t', ' ').replace('\n', ' ')).append('\n');
      }
      for (Map.Entry<String, List<File>> e : scanned.entrySet()) {
         String source = e.getKey();
         b.append("mod\t").append(source).append('\t').append(agents.getOrDefault(source, false) ? "agent" : "mod").append('\t')
               .append(verdicts.getOrDefault(source, "none\t")).append('\n');
         for (File jar : e.getValue()) {
            b.append("jar\t").append(source).append('\t').append(jar.getName()).append('\t').append(jar.getPath()).append('\n');
            Set<String> seen = new HashSet<>();
            for (Hit h : found) {
               if (!h.source.equals(source) || !jar.getName().equals(h.jar) || !seen.add(h.where())) {
                  continue;
               }
               Map<String, String[]> methods = editedSeen.get(h.cls);
               String kind = h.method == null ? "class" : methods != null && methods.containsKey(h.method) ? "edited" : "stock";
               String keys = kind.equals("edited") ? String.join(",", methods.get(h.method)) : "";
               b.append("hit\t").append(source).append('\t').append(jar.getName()).append('\t').append(h.where()).append('\t')
                     .append(kind).append('\t').append(h.how).append('\t').append(keys).append('\n');
            }
         }
      }
      return b.toString();
   }

   private static void problem(String line) {
      report.add(line);
      problems.add(line.startsWith("mod compat: ") ? line.substring("mod compat: ".length()) : line);
   }

   // ── policy ─────────────────────────────────────────────────────────────────────────────────────────────────

   /** ModCompatTest: the policy alone, under a given modCompat mode. */
   static void applyForTest(List<Hit> hits, Map<String, Map<String, String[]>> edited, Map<String, String> policy, Properties out, String m) {
      mode = m;
      reasons.clear();
      notes.clear();
      verdicts.clear();
      apply(hits, edited, policy, out);
   }

   private static void apply(List<Hit> hits, Map<String, Map<String, String[]>> edited, Map<String, String> policy, Properties out) {
      Map<String, List<Hit>> bySource = new LinkedHashMap<>();
      for (Hit h : hits) {
         bySource.computeIfAbsent(h.source, k -> new ArrayList<>()).add(h);
      }
      if (bySource.isEmpty()) {
         report.add("mod compat: no Java mod patches a class we ship (" + mode + ")");
         return;
      }
      for (Map.Entry<String, List<Hit>> e : bySource.entrySet()) {
         String source = e.getKey();
         String rule = policy.getOrDefault(source, "auto");
         Set<String> editedHits = new LinkedHashSet<>();
         Set<String> stockHits = new LinkedHashSet<>();
         Set<String> classOnly = new LinkedHashSet<>();
         Set<String> keys = new LinkedHashSet<>();
         for (Hit h : e.getValue()) {
            Map<String, String[]> methods = edited.get(h.cls);
            if (h.method == null) {
               classOnly.add(h.where());
            } else if (methods != null && methods.containsKey(h.method)) {
               editedHits.add(h.where());
               for (String k : methods.get(h.method)) {
                  keys.add(k);
               }
            } else {
               stockHits.add(h.where());
            }
         }
         if (rule.startsWith("off:")) {
            keys.clear();
            for (String k : rule.substring(4).split(",")) {
               if (!k.isBlank()) {
                  keys.add(k.trim());
               }
            }
         }
         boolean switching = !keys.isEmpty() && (rule.startsWith("off:") || rule.equals("auto") && mode.equals("auto"));
         StringBuilder line = new StringBuilder("mod compat: ").append(source).append(" patches ");
         if (!editedHits.isEmpty()) {
            line.append(editedHits.size()).append(" edited method(s) ").append(editedHits);
         }
         if (!stockHits.isEmpty()) {
            line.append(editedHits.isEmpty() ? "" : "; ").append(stockHits.size()).append(" unedited (stock bytecode) ").append(stockHits);
         }
         if (!classOnly.isEmpty()) {
            line.append(editedHits.isEmpty() && stockHits.isEmpty() ? "" : "; ").append("names ").append(classOnly).append(" (method unknown)");
         }
         String verdict;
         String status;
         if (rule.equals("ok")) {
            verdict = "known compatible";
            status = "ok";
         } else if (switching) {
            status = "off";
            verdict = "switched off: " + String.join(",", keys);
            for (String k : keys) {
               out.setProperty(k, "false");
               String why = source + " patches " + (editedHits.isEmpty() ? "it" : String.join(", ", editedHits));
               reasons.merge(k, why, (a, b) -> a + "; " + b);
            }
         } else if (keys.isEmpty()) {
            verdict = editedHits.isEmpty() ? "nothing of ours to switch" : "edits without a switch, left as is";
            status = editedHits.isEmpty() ? "none" : "edits";
         } else {
            verdict = "report only (modCompat=" + mode + "), would switch off " + String.join(",", keys);
            status = "report";
         }
         verdicts.put(source, status + "\t" + (rule.equals("ok") ? "" : String.join(",", keys)));
         line.append(" -> ").append(verdict);
         report.add(line.toString());
         notes.add(source + ": " + (rule.equals("ok") ? "tested with ours" : switching ? keys.size() + " setting(s) off: " + String.join(", ", keys)
               : editedHits.isEmpty() ? "no conflict" : "patches our edits, " + verdict));
      }
   }

   private static Map<String, String> knownPolicies() {
      Map<String, String> m = new HashMap<>();
      for (String[] k : KNOWN) {
         m.put(k[0], k[1]);
      }
      File user = new File(UserOptions.file().getParentFile(), "mod-compat.ini"); // beside options.ini (Zomboid/pzopt/)
      if (user.isFile()) {
         Properties p = new Properties();
         try (InputStream in = new FileInputStream(user)) {
            p.load(in);
            for (String id : p.stringPropertyNames()) {
               m.put(id, p.getProperty(id).trim());
            }
            report.add("mod compat: rules from " + user + ": " + p);
         } catch (IOException e) {
            problem("mod compat: could not read " + user + ": " + e);
         }
      }
      return m;
   }

   // ── what we ship ───────────────────────────────────────────────────────────────────────────────────────────

   /** build-info's overrides list, inner classes match through their outer class. */
   private static Set<String> shadowedClasses() {
      Set<String> s = new HashSet<>();
      String list = BuildInfo.get("overrides");
      if (list != null) {
         for (String c : list.split(",")) {
            if (!c.isBlank()) {
               s.add(c.trim());
            }
         }
      }
      return s;
   }

   /** override-methods.properties: class -> edited method -> boolean keys it reads. */
   static Map<String, Map<String, String[]>> editedMethods() throws IOException {
      Map<String, Map<String, String[]>> m = new HashMap<>();
      try (InputStream in = ModCompat.class.getResourceAsStream("/pzopt/override-methods.properties")) {
         if (in == null) {
            report.add("mod compat: no override-methods.properties in this build; only reporting");
            mode = "report";
            return m;
         }
         parseEdited(new String(in.readAllBytes(), StandardCharsets.UTF_8), m);
      }
      return m;
   }

   static void parseEdited(String text, Map<String, Map<String, String[]>> m) {
      for (String line : text.split("\n")) {
         line = line.trim();
         int hash = line.indexOf('#');
         int eq = line.indexOf('=');
         if (line.isEmpty() || line.startsWith("#") || hash < 0 || eq < hash) {
            continue;
         }
         String v = line.substring(eq + 1).trim();
         m.computeIfAbsent(line.substring(0, hash), k -> new HashMap<>())
               .put(line.substring(hash + 1, eq), v.isEmpty() ? new String[0] : v.split(","));
      }
   }

   // ── what the launch loads ──────────────────────────────────────────────────────────────────────────────────

   /** source name -> jars: the -javaagent jars, then the jars of each enabled mod ({@link #modJars}). */
   static Map<String, List<File>> sources() {
      Map<String, List<File>> out = new LinkedHashMap<>();
      long t0 = System.nanoTime();
      Map<String, File> modDirs = modDirectories();
      dirsMs = (System.nanoTime() - t0) / 1_000_000L;
      try {
         for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-javaagent:")) {
               String path = arg.substring("-javaagent:".length());
               int eq = path.indexOf('=');
               File jar = new File(eq >= 0 ? path.substring(0, eq) : path);
               String owner = ownerMod(jar, modDirs);
               String source = owner != null ? owner : jar.getName();
               out.computeIfAbsent(source, k -> new ArrayList<>()).add(jar);
               agents.put(source, true);
            }
         }
      } catch (Throwable t) {
         problem("mod compat: JVM arguments unreadable (" + t + ")");
      }
      List<String> enabled = enabledMods();
      enabledCount = enabled.size();
      List<String> ids = new ArrayList<>();
      List<File> dirs = new ArrayList<>();
      for (String id : enabled) {
         File dir = modDirs.get(id);
         if (dir != null) {
            ids.add(id);
            dirs.add(dir);
         }
      }
      List<List<File>> jars = inParallel(dirs, ModCompat::modJars);
      for (int i = 0; i < ids.size(); i++) {
         for (File j : jars.get(i)) {
            List<File> list = out.computeIfAbsent(ids.get(i), k -> new ArrayList<>());
            if (!list.contains(j)) {
               list.add(j);
            }
         }
      }
      return out;
   }

   /**
    * fn of every item on a few daemon threads, the results in the items' order. The folder reads are ~60 us a
    * directory on Windows; 330 mods' jar walks took 160 ms on one thread, 45 on four, 32 on eight (warm, 2026-10-01).
    */
   static <T, R> List<R> inParallel(List<T> items, java.util.function.Function<T, R> fn) {
      int threads = Math.min(items.size(), Math.min(8, Runtime.getRuntime().availableProcessors()));
      List<R> out = new ArrayList<>(items.size());
      if (threads <= 1) {
         for (T it : items) {
            out.add(fn.apply(it));
         }
         return out;
      }
      java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
         Thread t = new Thread(r, "pzopt-modcompat-" + n.incrementAndGet());
         t.setDaemon(true);
         return t;
      });
      try {
         List<java.util.concurrent.Future<R>> futures = new ArrayList<>(items.size());
         for (T it : items) {
            futures.add(pool.submit(() -> fn.apply(it)));
         }
         for (java.util.concurrent.Future<R> f : futures) {
            out.add(f.get());
         }
         return out;
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException(e);
      } catch (java.util.concurrent.ExecutionException e) {
         throw new IllegalStateException(e.getCause());
      } finally {
         pool.shutdownNow();
      }
   }

   /**
    * The jars of one mod folder: the declared ones ({@link #declaredJars}), then any other jar the walk finds (a jar a
    * mod ships for installing by hand). Inside a folder named media the walk enters only java/: media holds 98 % of the
    * 343k files of 470 Workshop mods (2026-10-01), and its other folders (textures, models, sounds, Lua, scripts) held
    * none of their 6 jars. An undeclared jar there is missed; nothing loads one from there (ZombieBuddy loads the
    * declared jar, an agent comes through -javaagent).
    */
   static List<File> modJars(File dir) {
      List<File> out = declaredJars(dir);
      // same reach as before: the folder's files and 6 levels of subfolders, links followed, jars under 64 MB
      walkJars(dir.toPath(), dir.toPath(), 7, out);
      return out;
   }

   /**
    * modJars' walk of start, the jars reported under dir (start is dir, or its real path when dir is a link). Links are
    * followed by hand: FOLLOW_LINKS makes the JDK compare every folder with its ancestors (Windows has no file key),
    * 1166 ms instead of 394 for the 472 mod folders on one thread.
    */
   private static void walkJars(Path dir, Path start, int maxDepth, List<File> out) {
      try {
         Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
               return d.equals(start) || !art(dir.resolve(start.relativize(d))) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
               int depth = f.equals(start) ? 0 : start.relativize(f).getNameCount();
               Path at = depth == 0 ? dir : dir.resolve(start.relativize(f));
               if (a.isRegularFile()) {
                  addJar(at, a.size(), out);
               } else if (Files.isDirectory(f)) { // a link or junction to a folder
                  if (depth < maxDepth && (depth == 0 || !art(at))) {
                     try {
                        walkJars(at, f.toRealPath(), maxDepth - depth, out);
                     } catch (IOException ignored) {
                     }
                  }
               } else if (Files.isRegularFile(f)) { // a link to a file
                  try {
                     addJar(at, Files.size(f), out);
                  } catch (IOException ignored) {
                  }
               }
               return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
               return FileVisitResult.CONTINUE;
            }
         });
      } catch (IOException ignored) {
      }
   }

   /** A folder inside a folder named media, other than java: the mod's art, Lua and scripts. */
   private static boolean art(Path d) {
      Path parent = d.getParent();
      return parent != null && parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase("media")
            && !d.getFileName().toString().equalsIgnoreCase("java");
   }

   private static void addJar(Path p, long size, List<File> out) {
      if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && size < 64L << 20) {
         File j = p.toFile();
         if (!out.contains(j)) {
            out.add(j);
         }
      }
   }

   /**
    * The jars a mod folder declares with ZombieBuddy's javaJarFile=. ZombieBuddy reads the version folder's mod.info
    * (jar relative to it), else common/mod.info (jar relative to common/, then to the version folder); this tries the
    * root's, common's and every version folder's, so a jar declared for another game version counts too.
    */
   static List<File> declaredJars(File dir) {
      List<File> out = new ArrayList<>();
      List<File> folders = new ArrayList<>();
      folders.add(dir);
      File[] sub = dir.listFiles(File::isDirectory);
      if (sub != null) {
         folders.addAll(List.of(sub));
      }
      for (File f : folders) {
         String jar = javaJarFile(new File(f, "mod.info"));
         if (jar == null) {
            continue;
         }
         List<File> bases = new ArrayList<>();
         bases.add(f);
         if (f.getName().equalsIgnoreCase("common")) {
            for (File v : folders) {
               if (v != dir && v != f) {
                  bases.add(v);
               }
            }
         }
         for (File base : bases) {
            try {
               File j = base.toPath().resolve(jar).normalize().toFile();
               if (j.isFile() && j.length() < 64L << 20 && !out.contains(j)) {
                  out.add(j);
               }
            } catch (RuntimeException e) { // a path this file system cannot name
            }
         }
      }
      return out;
   }

   /** A mod.info's first javaJarFile= that names a .jar (ZombieBuddy skips the others), or null. */
   static String javaJarFile(File info) {
      if (!info.isFile()) {
         return null;
      }
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(info), StandardCharsets.UTF_8))) {
         String line;
         while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.toLowerCase(Locale.ROOT).startsWith("javajarfile=")) {
               String v = line.substring("javajarfile=".length()).trim();
               if (v.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                  return v;
               }
            }
         }
      } catch (IOException ignored) {
      }
      return null;
   }

   /**
    * Scan results per jar, beside options.ini ({@link #FILE_NAME}): a jar whose path, size and time are unchanged is
    * read back instead of scanned (ZombieBuddy.jar alone took 180 ms). The stamp ({@link #stamp}) names this build and
    * the edited-method map; a file with another stamp counts as empty.
    */
   static final class JarCache {
      static final String FILE_NAME = "mod-compat-cache.properties";
      private final File file;
      private final String stamp;
      private final Properties old = new Properties();
      private final Properties now = new Properties(); // the jars of this scan
      private boolean changed;
      int scanned;
      int fromCache;

      private JarCache(File file, String stamp) {
         this.file = file;
         this.stamp = stamp;
      }

      static JarCache load(File file, String stamp) {
         JarCache c = new JarCache(file, stamp);
         if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
               c.old.load(in);
            } catch (IOException | IllegalArgumentException e) {
               c.old.clear();
            }
         }
         if (!stamp.equals(c.old.getProperty("stamp"))) {
            c.old.clear();
            c.changed = true;
         }
         return c;
      }

      private static String key(File jar) {
         return jar.getAbsolutePath();
      }

      private static String version(File jar) {
         return jar.length() + "," + jar.lastModified();
      }

      /** The jar's hits under this source, or null when the jar is not cached as it is now. */
      List<Hit> get(String source, File jar) {
         String v = this.old.getProperty(key(jar));
         if (v == null) {
            return null;
         }
         String[] lines = v.split("\n", -1);
         if (!lines[0].equals(version(jar))) {
            return null;
         }
         List<Hit> hits = new ArrayList<>();
         for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length != 3) {
               return null;
            }
            hits.add(new Hit(source, jar.getName(), f[0], f[1].isEmpty() ? null : f[1].substring(1), f[2]));
         }
         this.now.setProperty(key(jar), v);
         return hits;
      }

      void put(File jar, List<Hit> hits) {
         StringBuilder v = new StringBuilder(version(jar));
         for (Hit h : hits) {
            v.append('\n').append(h.cls).append('\t').append(h.method != null ? "=" + h.method : "").append('\t').append(h.how);
         }
         this.now.setProperty(key(jar), v.toString());
         this.changed = true;
      }

      /** Writes the jars of this scan when anything differs from the file (written beside, then moved over it). */
      void save() {
         Properties had = new Properties();
         had.putAll(this.old);
         had.remove("stamp");
         if (!this.changed && had.equals(this.now)) {
            return;
         }
         Properties out = new Properties();
         out.putAll(this.now);
         out.setProperty("stamp", this.stamp);
         try {
            this.file.getParentFile().mkdirs();
            File tmp = new File(this.file.getPath() + ".tmp");
            try (java.io.OutputStream o = new java.io.FileOutputStream(tmp)) {
               out.store(o, "pzopt ModCompat: jar scan results (path = size,time, then class, =method or empty, how per hit)");
            }
            Files.move(tmp.toPath(), this.file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
         } catch (IOException | RuntimeException ignored) {
         }
      }
   }

   /** This build and the inputs of a jar scan (shadowed classes, edited methods): the cache's validity. */
   static String stamp(Map<String, Map<String, String[]>> edited, Set<String> shadowed) {
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      for (String c : new java.util.TreeSet<>(shadowed)) {
         crc.update((c + "\n").getBytes(StandardCharsets.UTF_8));
      }
      for (Map.Entry<String, Map<String, String[]>> e : new TreeMap<>(edited).entrySet()) {
         for (Map.Entry<String, String[]> m : new TreeMap<>(e.getValue()).entrySet()) {
            crc.update((e.getKey() + "#" + m.getKey() + "=" + String.join(",", m.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
         }
      }
      return BuildInfo.get("commit") + " " + BuildInfo.get("built") + " " + Long.toHexString(crc.getValue());
   }

   /** {@link #scanJar}, through the cache: an unchanged jar's hits are read back, a scanned one is remembered. */
   static void scanJar(String source, File jar, Set<String> shadowed, Map<String, Map<String, String[]>> edited, List<Hit> hits, JarCache cache) {
      List<Hit> jarHits = cache.get(source, jar);
      if (jarHits != null) {
         cache.fromCache++;
      } else {
         jarHits = new ArrayList<>();
         cache.scanned++;
         if (scanJar(source, jar, shadowed, edited, jarHits)) {
            cache.put(jar, jarHits);
         }
      }
      hits.addAll(jarHits);
   }

   /** The mod ids of Zomboid/mods/default.txt (the main menu's active mods, the ones loaded at boot). */
   static List<String> enabledMods() {
      List<String> ids = new ArrayList<>();
      File f = new File(UserOptions.zomboidDir(), "mods" + File.separator + "default.txt");
      if (!f.isFile()) {
         return ids;
      }
      try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
         String line;
         while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("mod") && line.contains("=")) {
               String id = line.substring(line.indexOf('=') + 1).trim();
               if (id.endsWith(",")) {
                  id = id.substring(0, id.length() - 1).trim();
               }
               if (id.startsWith("\\")) {
                  id = id.substring(1);
               }
               if (!id.isEmpty()) {
                  ids.add(id);
               }
            }
         }
      } catch (IOException e) {
         problem("mod compat: could not read " + f + ": " + e);
      }
      return ids;
   }

   /** mod id -> its folder, in the order the game searches: Zomboid/mods, the game's mods, the Workshop downloads. */
   static Map<String, File> modDirectories() {
      return modDirectories(UserOptions.zomboidDir(), new File("").getAbsoluteFile()); // the game dir: the launcher's working directory
   }

   /** ModCompatTest: the same search from a given Zomboid folder and game dir. */
   static Map<String, File> modDirectories(File zomboidDir, File game) {
      Map<String, File> m = new LinkedHashMap<>();
      List<File> roots = new ArrayList<>();
      roots.add(new File(zomboidDir, "mods"));
      roots.add(new File(game, "mods"));
      File content = workshopContent(game);
      File[] items = content != null ? content.listFiles() : null;
      if (items != null) {
         for (File item : items) {
            roots.add(new File(item, "mods"));
         }
      }
      for (List<Map.Entry<File, Set<String>>> folders : inParallel(roots, ModCompat::modFolders)) { // read in parallel, merged in order
         for (Map.Entry<File, Set<String>> f : folders) {
            for (String id : f.getValue()) {
               m.putIfAbsent(id, f.getKey());
            }
         }
      }
      return m;
   }

   /** The mod folders of one root with their ids, in listing order. */
   private static List<Map.Entry<File, Set<String>>> modFolders(File root) {
      List<Map.Entry<File, Set<String>>> out = new ArrayList<>();
      File[] dirs = root.listFiles();
      if (dirs != null) {
         for (File d : dirs) {
            if (d.isDirectory()) {
               out.add(Map.entry(d, modIds(d)));
            }
         }
      }
      return out;
   }

   /**
    * The Workshop downloads: Steam keeps them in the library that holds the game, so under the nearest steamapps folder
    * at or above the game dir. The game dir sits at a different depth per platform: Linux
    * .../steamapps/common/ProjectZomboid/projectzomboid, Windows ...\steamapps\common\ProjectZomboid, macOS
    * .../steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java. Null outside a Steam library.
    */
   static File workshopContent(File game) {
      for (File f = game; f != null; f = f.getParentFile()) {
         if (f.getName().equalsIgnoreCase("steamapps")) {
            return new File(f, "workshop/content/108600");
         }
      }
      return null;
   }

   /** The id= of every mod.info in a mod folder (its root, common/ and the version folders). */
   private static Set<String> modIds(File dir) {
      Set<String> ids = new LinkedHashSet<>();
      List<File> infos = new ArrayList<>();
      infos.add(new File(dir, "mod.info"));
      File[] sub = dir.listFiles();
      if (sub != null) {
         for (File s : sub) {
            if (s.isDirectory()) {
               infos.add(new File(s, "mod.info"));
            }
         }
      }
      for (File info : infos) {
         if (!info.isFile()) {
            continue;
         }
         try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(info), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
               line = line.trim();
               if (line.startsWith("id=")) {
                  ids.add(line.substring(3).trim());
                  break;
               }
            }
         } catch (IOException ignored) {
         }
      }
      return ids;
   }

   private static String ownerMod(File jar, Map<String, File> modDirs) {
      String path = jar.getAbsolutePath();
      for (Map.Entry<String, File> e : modDirs.entrySet()) {
         if (path.startsWith(e.getValue().getAbsolutePath() + File.separator)) {
            return e.getKey();
         }
      }
      return null;
   }

   // ── class files ────────────────────────────────────────────────────────────────────────────────────────────

   /** The hits of one jar's classes; false when the jar could not be opened. */
   static boolean scanJar(String source, File jar, Set<String> shadowed, Map<String, Map<String, String[]>> edited, List<Hit> hits) {
      try (ZipFile zip = new ZipFile(jar)) {
         var entries = zip.entries();
         while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class") || e.getSize() > 4L << 20) {
               continue;
            }
            try (InputStream in = zip.getInputStream(e)) {
               scanClass(source, jar.getName(), in.readAllBytes(), shadowed, edited, hits);
            } catch (IOException | RuntimeException ex) {
               // a class file we cannot read is not a patch we can see
            }
         }
         return true;
      } catch (IOException e) {
         problem("mod compat: could not open " + jar + ": " + e);
         return false;
      }
   }

   /** One class file: its ZombieBuddy @Patch annotations, else the string constants a transformer would compare. */
   static void scanClass(String source, byte[] bytes, Set<String> shadowed, Map<String, Map<String, String[]>> edited, List<Hit> hits) throws IOException {
      scanClass(source, null, bytes, shadowed, edited, hits);
   }

   static void scanClass(String source, String jar, byte[] bytes, Set<String> shadowed, Map<String, Map<String, String[]>> edited, List<Hit> hits)
         throws IOException {
      ClassFile cf = ClassFile.parse(bytes);
      boolean patched = false;
      for (Map<String, Object> a : cf.annotations) {
         if (!ZB_PATCH.equals(a.get("@type"))) {
            continue;
         }
         Object cn = a.get("className");
         Object mn = a.get("methodName");
         if (cn instanceof String c) {
            String internal = c.replace('.', '/');
            if (shadows(shadowed, internal)) {
               hits.add(new Hit(source, jar, internal, mn instanceof String m ? m : null, "ZombieBuddy @Patch"));
               patched = true;
            }
         }
      }
      if (patched || !cf.transformer()) {
         return;
      }
      // transformer style (ASM / raw ClassFileTransformer): the target's name and its method names as string constants
      List<String> named = new ArrayList<>();
      for (String s : cf.strings) {
         String internal = s.replace('.', '/');
         if (shadows(shadowed, internal) && !named.contains(internal)) {
            named.add(internal);
         }
      }
      for (String cls : named) {
         boolean method = false;
         Map<String, String[]> methods = edited.get(cls);
         if (methods != null) {
            for (String s : cf.strings) {
               if (methods.containsKey(s)) {
                  hits.add(new Hit(source, jar, cls, s, "string constants"));
                  method = true;
               }
            }
         }
         if (!method) {
            hits.add(new Hit(source, jar, cls, null, "string constants"));
         }
      }
   }

   private static boolean shadows(Set<String> shadowed, String internal) {
      if (shadowed.contains(internal)) {
         return true;
      }
      int d = internal.indexOf('$');
      return d > 0 && shadowed.contains(internal.substring(0, d));
   }

   /** The parts of a class file the scan reads: string constants and the class-level annotations. */
   static final class ClassFile {
      final List<String> strings = new ArrayList<>();
      final List<String> classes = new ArrayList<>();
      final List<Map<String, Object>> annotations = new ArrayList<>();
      private Object[] cp;

      /** A class that rewrites bytecode: its string constants name what it patches (a plain class naming ours reads it). */
      boolean transformer() {
         for (String c : this.classes) {
            if (c.equals("java/lang/instrument/ClassFileTransformer") || c.startsWith("org/objectweb/asm/")
                  || c.startsWith("net/bytebuddy/") || c.startsWith("javassist/")) {
               return true;
            }
         }
         return false;
      }

      static ClassFile parse(byte[] bytes) throws IOException {
         ClassFile cf = new ClassFile();
         DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
         if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
         }
         in.readUnsignedShort();
         in.readUnsignedShort();
         int n = in.readUnsignedShort();
         cf.cp = new Object[n];
         int[] stringRefs = new int[n];
         int[] classRefs = new int[n];
         int ns = 0;
         int nc = 0;
         for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
               case 1 -> cf.cp[i] = in.readUTF();
               case 3, 4 -> in.skipNBytes(4);
               case 5, 6 -> {
                  in.skipNBytes(8);
                  i++;
               }
               case 8 -> stringRefs[ns++] = in.readUnsignedShort();
               case 7 -> classRefs[nc++] = in.readUnsignedShort();
               case 16, 19, 20 -> in.skipNBytes(2);
               case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
               case 15 -> in.skipNBytes(3);
               default -> throw new IOException("constant tag " + tag);
            }
         }
         for (int k = 0; k < ns; k++) {
            if (cf.cp[stringRefs[k]] instanceof String s) {
               cf.strings.add(s);
            }
         }
         for (int k = 0; k < nc; k++) {
            if (cf.cp[classRefs[k]] instanceof String c) {
               cf.classes.add(c);
            }
         }
         in.skipNBytes(6); // access, this, super
         in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
         for (int part = 0; part < 2; part++) { // fields, methods
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
               in.skipNBytes(6);
               skipAttributes(in);
            }
         }
         int attrs = in.readUnsignedShort();
         for (int i = 0; i < attrs; i++) {
            String name = (String) cf.cp[in.readUnsignedShort()];
            int len = in.readInt();
            if ("RuntimeVisibleAnnotations".equals(name) || "RuntimeInvisibleAnnotations".equals(name)) {
               int count = in.readUnsignedShort();
               for (int a = 0; a < count; a++) {
                  cf.annotations.add(cf.annotation(in));
               }
            } else {
               in.skipNBytes(len);
            }
         }
         return cf;
      }

      private static void skipAttributes(DataInputStream in) throws IOException {
         int attrs = in.readUnsignedShort();
         for (int i = 0; i < attrs; i++) {
            in.skipNBytes(2);
            in.skipNBytes(in.readInt() & 0xFFFFFFFFL);
         }
      }

      private Map<String, Object> annotation(DataInputStream in) throws IOException {
         Map<String, Object> a = new HashMap<>();
         a.put("@type", this.cp[in.readUnsignedShort()]);
         int pairs = in.readUnsignedShort();
         for (int p = 0; p < pairs; p++) {
            String name = (String) this.cp[in.readUnsignedShort()];
            a.put(name, this.elementValue(in));
         }
         return a;
      }

      private Object elementValue(DataInputStream in) throws IOException {
         int tag = in.readUnsignedByte();
         switch (tag) {
            case 's':
               return this.cp[in.readUnsignedShort()];
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c':
               in.readUnsignedShort();
               return null;
            case 'e':
               in.readUnsignedShort();
               return this.cp[in.readUnsignedShort()];
            case '@':
               return this.annotation(in);
            case '[': {
               int n = in.readUnsignedShort();
               List<Object> values = new ArrayList<>();
               for (int i = 0; i < n; i++) {
                  values.add(this.elementValue(in));
               }
               return values;
            }
            default:
               throw new IOException("element value tag " + (char) tag);
         }
      }
   }

   // ── report ─────────────────────────────────────────────────────────────────────────────────────────────────

   private static void writeReport() {
      report.add("mod compat: scan took " + scanMs + " ms (modCompat=" + mode + ")" + split);
      File f = new File(UserOptions.file().getParentFile(), "mod-compat.txt");
      try {
         f.getParentFile().mkdirs();
         java.nio.file.Files.write(f.toPath(), report, StandardCharsets.UTF_8);
      } catch (IOException ignored) {
      }
   }

   /** Command line: scan jars against an override-methods file (tests, the harness matrix's offline check). */
   public static void main(String[] args) throws IOException {
      Map<String, Map<String, String[]>> edited = new HashMap<>();
      parseEdited(new String(java.nio.file.Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8), edited);
      Set<String> shadowed = new HashSet<>(edited.keySet());
      for (String c : BuildInfo.get("overrides") != null ? BuildInfo.get("overrides").split(",") : new String[0]) {
         shadowed.add(c);
      }
      List<Hit> hits = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
         File jar = new File(args[i]);
         scanJar(jar.getName(), jar, shadowed, edited, hits);
      }
      for (Hit h : hits) {
         System.out.println(h.source + "\t" + h.where() + "\t" + h.how + "\t"
               + (edited.containsKey(h.cls) && h.method != null && edited.get(h.cls).containsKey(h.method) ? "EDITED " + String.join(",", edited.get(h.cls).get(h.method)) : "-"));
      }
   }
}
