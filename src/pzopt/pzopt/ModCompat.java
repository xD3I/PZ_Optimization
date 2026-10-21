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
import java.util.ArrayList;
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
 * the JVM's arguments and every jar inside the mods {@code Zomboid/mods/default.txt} enables (Zomboid/mods, the game's
 * mods folder, the Workshop folder beside the game). For each class file it reads ZombieBuddy's {@code @Patch}
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
         scanned.putAll(sources());
         for (Map.Entry<String, List<File>> src : scanned.entrySet()) {
            for (File jar : src.getValue()) {
               scanJar(src.getKey(), jar, shadowed, edited, hits);
            }
         }
         found.addAll(hits);
         apply(hits, edited, policy, out);
      } catch (Throwable t) {
         problem("mod compat: scan failed (" + t + "); nothing switched");
      }
      scanMs = (System.nanoTime() - t0) / 1_000_000L;
      writeReport();
      return out;
   }

   /** Whether the launch may carry Java mods (any -javaagent or mod jar, or the scan was off / failed): pzopt.RenderLast. */
   public static boolean javaModsPossible() {
      if (mode.equals("off") || !problems.isEmpty()) {
         return true;
      }
      for (List<File> l : scanned.values()) {
         if (!l.isEmpty()) {
            return true;
         }
      }
      return false;
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

   /** source name -> jars: the -javaagent jars, then every jar of each enabled mod. */
   static Map<String, List<File>> sources() {
      Map<String, List<File>> out = new LinkedHashMap<>();
      Map<String, File> modDirs = modDirectories();
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
      for (String id : enabled) {
         File dir = modDirs.get(id);
         if (dir == null) {
            continue;
         }
         List<File> jars = new ArrayList<>();
         collectJars(dir, jars, 0);
         for (File j : jars) {
            List<File> list = out.computeIfAbsent(id, k -> new ArrayList<>());
            if (!list.contains(j)) {
               list.add(j);
            }
         }
      }
      return out;
   }

   private static void collectJars(File dir, List<File> out, int depth) {
      File[] files = dir.listFiles();
      if (files == null || depth > 6) {
         return;
      }
      for (File f : files) {
         if (f.isDirectory()) {
            collectJars(f, out, depth + 1);
         } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".jar") && f.length() < 64L << 20) {
            out.add(f);
         }
      }
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
      Map<String, File> m = new LinkedHashMap<>();
      List<File> roots = new ArrayList<>();
      roots.add(new File(UserOptions.zomboidDir(), "mods"));
      File game = new File("").getAbsoluteFile(); // the game dir: the launcher's working directory
      roots.add(new File(game, "mods"));
      File steamapps = game.getParentFile() != null && game.getParentFile().getParentFile() != null
            ? game.getParentFile().getParentFile().getParentFile() : null; // .../steamapps/common/ProjectZomboid/projectzomboid
      File[] items = steamapps != null ? new File(steamapps, "workshop/content/108600").listFiles() : null;
      if (items != null) {
         for (File item : items) {
            roots.add(new File(item, "mods"));
         }
      }
      for (File root : roots) {
         File[] dirs = root.listFiles();
         if (dirs == null) {
            continue;
         }
         for (File d : dirs) {
            if (!d.isDirectory()) {
               continue;
            }
            for (String id : modIds(d)) {
               m.putIfAbsent(id, d);
            }
         }
      }
      return m;
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

   static void scanJar(String source, File jar, Set<String> shadowed, Map<String, Map<String, String[]>> edited, List<Hit> hits) {
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
      } catch (IOException e) {
         problem("mod compat: could not open " + jar + ": " + e);
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
      report.add("mod compat: scan took " + scanMs + " ms (modCompat=" + mode + ")");
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
