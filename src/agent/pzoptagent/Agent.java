package pzoptagent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads the overrides on a game whose projectzomboid.jar is signed (Build 42.22.1 on, 2026-11-04,
 * docs/findings-signed-jar-2026-11-04.md). A class loader refuses unsigned classes in a package that holds signed ones,
 * so a signed jar can no longer be shadowed by loose classes ahead of it on the class path: the game stops before main.
 * The jar is never written and stays verified. This agent hands the JVM our bytes when a class of the jar loads.
 *
 * It does not touch zombie.core.IntegrityReport: the game's report shows this agent (agents=1) and our files (classpath=N).
 *
 * The files: pzopt/overrides/ holds our classes of the game's packages (build.sh; not a class path root, so a launcher
 * JSON without the -javaagent argument starts the plain stock game). pzopt/agent/pzopt-agent.jar is this class. The jar
 * sits next to pzopt/build-info.properties; a jar of another revision gets no override at all: stock game, no crash.
 *
 * premain must never throw: the JVM would not start, and the player would have no game.
 */
public final class Agent {
   private Agent() {
   }

   public static void premain(String args, Instrumentation inst) {
      try {
         install(inst);
      } catch (Throwable t) {
         log("not active, the game runs stock: " + t);
      }
   }

   /** stdout of premain reaches neither console.txt nor the launcher's output, so the line also goes to Zomboid/pzopt/agent.log. */
   private static void log(String s) {
      System.out.println("[pzopt-agent] " + s);
      try {
         Path dir = Path.of(System.getProperty("user.home"), "Zomboid", "pzopt");
         Files.createDirectories(dir);
         Path file = dir.resolve("agent.log");
         if (Files.isRegularFile(file) && Files.size(file) > 65536) {
            Files.delete(file); // a line or two a boot: start over rather than grow without end
         }
         Files.writeString(file, java.time.LocalDateTime.now().withNano(0) + " " + s + System.lineSeparator(),
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
      } catch (Throwable ignored) {
      }
   }

   private static void install(Instrumentation inst) throws Exception {
      // <game>/pzopt/agent/pzopt-agent.jar
      Path base = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
      Path overrides = base.resolve("overrides");
      Path jarPath = gameJar();
      if (jarPath == null) {
         log("no projectzomboid.jar on the class path; not active");
         return;
      }
      String revision = builtRevision(base.resolve("build-info.properties"));
      Set<String> jarClasses = new HashSet<>();
      try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
         JarEntry version = jar.getJarEntry("zombie/GitVersion.class");
         if (version == null || revision.isEmpty() || !contains(jar, version, revision)) {
            log("the overrides were built for revision " + revision + ", which is not this game's; the game runs stock");
            return;
         }
         jar.stream().forEach(e -> jarClasses.add(e.getName()));
      }
      Set<String> ours = new HashSet<>();
      List<String> modOnly = new ArrayList<>();
      try (Stream<Path> files = Files.walk(overrides)) {
         for (Path f : (Iterable<Path>)files::iterator) {
            String rel = overrides.relativize(f).toString().replace('\\', '/');
            if (rel.endsWith(".class")) {
               String internal = rel.substring(0, rel.length() - 6);
               ours.add(internal);
               if (!jarClasses.contains(rel)) {
                  modOnly.add(internal);
               }
            }
         }
      }
      ClassLoader system = ClassLoader.getSystemClassLoader();
      // Another Java agent whose premain ran first may have loaded game classes we replace (ZombieBuddy 2.3.4 loads
      // KahluaThread, LuaCaller and KahluaConverterManager in its premain). Ours add fields and methods, so they cannot be
      // redefined afterwards, and a mix of its stock copies with the rest of ours fails with NoSuchMethodError /
      // NoSuchFieldError mid-game. Then nothing is replaced: the stock game, and a line saying how to fix the order.
      List<String> early = loadedOfOurs(inst, ours, system);
      if (!early.isEmpty()) {
         log("another Java agent started before PZ Optimization's and already loaded " + early.size()
            + " of the game classes it replaces (" + String.join(", ", early.subList(0, Math.min(4, early.size())))
            + "): the game runs stock. Fix: put -javaagent:pzopt/agent/pzopt-agent.jar before the other agent"
            + " (ZombieBuddy's -javaagent / -agentlib:zbNative) in ProjectZomboid64.json or the launch options; the installer does that.");
         return;
      }
      inst.addTransformer(new ClassFileTransformer() {
         @Override
         public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] stock) {
            if (loader != system || name == null || !ours.contains(name)) {
               return null;
            }
            try {
               return Files.readAllBytes(overrides.resolve(name + ".class"));
            } catch (IOException e) {
               log("could not read the override of " + name + ", using the jar's class: " + e);
               return null;
            }
         }
      });
      int defined = defineModOnly(modOnly, ours, overrides, system);
      log("active for revision " + revision + ": " + ours.size() + " classes (" + defined + " new in game packages)");
   }

   /**
    * Our classes the jar does not have (inner classes such as FBORenderCell$PzoptBakeCell): the class path cannot find them,
    * so they are defined now, beside a class of their package. They get that class's protection domain, the only one the
    * package accepts. A superclass among them comes first: what fails is tried again after the others.
    */
   private static int defineModOnly(List<String> modOnly, Set<String> ours, Path overrides, ClassLoader system) throws IOException {
      int defined = 0;
      List<String> pending = modOnly;
      Throwable last = null;
      while (!pending.isEmpty()) {
         List<String> next = new ArrayList<>();
         for (String name : pending) {
            try {
               int dollar = name.indexOf('$');
               String outer = dollar < 0 ? anchor(name, ours, modOnly) : name.substring(0, dollar);
               Class<?> anchor = Class.forName(outer.replace('/', '.'), false, system);
               MethodHandles.privateLookupIn(anchor, MethodHandles.lookup()).defineClass(Files.readAllBytes(overrides.resolve(name + ".class")));
               defined++;
            } catch (ReflectiveOperationException | LinkageError e) {
               next.add(name);
               last = e;
            }
         }
         if (next.size() == pending.size()) {
            log(next.size() + " new classes could not be defined, first " + next.get(0) + ": " + last);
            break;
         }
         pending = next;
      }
      return defined;
   }

   /** Classes we replace that the system loader already holds (loaded before our transformer existed). */
   private static List<String> loadedOfOurs(Instrumentation inst, Set<String> ours, ClassLoader system) {
      List<String> early = new ArrayList<>();
      for (Class<?> c : inst.getAllLoadedClasses()) {
         if (c.getClassLoader() == system && ours.contains(c.getName().replace('.', '/'))) {
            early.add(c.getName());
         }
      }
      early.sort(null);
      return early;
   }

   /** A class of the jar in the package of a new top-level class. */
   private static String anchor(String name, Set<String> ours, List<String> modOnly) throws ClassNotFoundException {
      String pkg = name.substring(0, name.lastIndexOf('/') + 1);
      for (String c : ours) {
         if (!modOnly.contains(c) && c.startsWith(pkg) && c.indexOf('/', pkg.length()) < 0) {
            return c;
         }
      }
      throw new ClassNotFoundException("no class of the jar next to " + name);
   }

   private static Path gameJar() {
      for (String e : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
         if (e.endsWith("projectzomboid.jar")) {
            return Path.of(e).toAbsolutePath();
         }
      }
      return null;
   }

   private static String builtRevision(Path buildInfo) throws IOException {
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(buildInfo)) {
         p.load(in);
      }
      return p.getProperty("revision", "").trim();
   }

   /** GitVersion.REVISION is a constant: its text is in the class file. Nothing of the game is loaded for this. */
   private static boolean contains(JarFile jar, JarEntry e, String text) throws IOException {
      try (InputStream in = jar.getInputStream(e)) {
         return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains(text);
      }
   }
}
