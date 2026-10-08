package pzopt;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** pzopt.ModCompat: ZombieBuddy @Patch and transformer string constants are found, plain classes are not, the policy switches the right keys. */
public class ModCompatTest {
   @me.zed_0xff.zombie_buddy.Patch(className = "zombie.iso.WorldStreamer", methodName = "stop")
   static class FakePatch {
   }

   @me.zed_0xff.zombie_buddy.Patch(className = "zombie.iso.IsoGridSquare", methodName = "render")
   static class NotOurs {
   }

   static class FakeTransformer implements java.lang.instrument.ClassFileTransformer {
      final String target = "zombie/iso/IsoChunkMap";
      final String method = "updateInternal";
   }

   static class Plain {
      final String target = "zombie.iso.IsoChunkMap";
      final String method = "updateInternal";
   }

   static class ClassOnlyTransformer implements java.lang.instrument.ClassFileTransformer {
      final String target = "zombie/iso/IsoChunk";
   }

   static byte[] bytes(Class<?> c) throws Exception {
      try (InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) {
         return in.readAllBytes();
      }
   }

   static Path write(Path p, String s) throws Exception {
      Files.createDirectories(p.getParent());
      Files.writeString(p, s);
      return p;
   }

   static List<String> wheres(List<ModCompat.Hit> hits) {
      List<String> w = new ArrayList<>();
      for (ModCompat.Hit h : hits) {
         w.add(h.source + " " + h.where() + " " + h.how);
      }
      return w;
   }

   public static void main(String[] args) throws Exception {
      Map<String, Map<String, String[]>> edited = new HashMap<>();
      ModCompat.parseEdited("# header\nzombie/iso/WorldStreamer#stop=wake\nzombie/iso/WorldStreamer#threadLoop=wake,centerFirstLoad\n"
            + "zombie/iso/IsoChunkMap#updateInternal=chunkHandoffSlack\nzombie/iso/IsoChunk#recalcPooled=\n", edited);
      Check.check(edited.get("zombie/iso/WorldStreamer").get("threadLoop").length == 2, "two keys parsed");
      Check.check(edited.get("zombie/iso/IsoChunk").get("recalcPooled").length == 0, "an edit without a switch parses as no keys");
      Set<String> shadowed = Set.of("zombie/iso/WorldStreamer", "zombie/iso/IsoChunkMap", "zombie/iso/IsoChunk");

      List<ModCompat.Hit> hits = new ArrayList<>();
      ModCompat.scanClass("modA", bytes(FakePatch.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && hits.get(0).cls.equals("zombie/iso/WorldStreamer") && "stop".equals(hits.get(0).method)
            && hits.get(0).how.contains("@Patch"), "the @Patch target is read: " + hits.size());
      hits.clear();
      ModCompat.scanClass("modA", bytes(NotOurs.class), shadowed, edited, hits);
      Check.check(hits.isEmpty(), "a patch of a class we do not ship is no hit");
      ModCompat.scanClass("agentB", bytes(FakeTransformer.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && hits.get(0).cls.equals("zombie/iso/IsoChunkMap") && "updateInternal".equals(hits.get(0).method),
            "a transformer's target class + method strings are a hit: " + hits.size());
      hits.clear();
      ModCompat.scanClass("modC", bytes(Plain.class), shadowed, edited, hits);
      Check.check(hits.isEmpty(), "a plain class naming ours in strings is not a patch");
      ModCompat.scanClass("modA", "modA.jar", bytes(FakePatch.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && "modA.jar".equals(hits.get(0).jar), "a hit carries its jar file for the menu's check");
      hits.clear();

      // policy: unknown mod in auto switches the method's keys, a known-ok mod switches nothing, report only logs
      List<ModCompat.Hit> all = new ArrayList<>();
      all.add(new ModCompat.Hit("modA", "zombie/iso/WorldStreamer", "threadLoop", "ZombieBuddy @Patch"));
      all.add(new ModCompat.Hit("modA", "zombie/iso/WorldStreamer", "addJob", "ZombieBuddy @Patch")); // not edited: stock bytecode
      all.add(new ModCompat.Hit("ZBBetterFPS", "zombie/iso/IsoChunkMap", "updateInternal", "string constants"));
      Map<String, String> policy = new HashMap<>();
      policy.put("ZBBetterFPS", "ok");
      Properties out = new Properties();
      ModCompat.applyForTest(all, edited, policy, out, "auto");
      Check.check("false".equals(out.getProperty("wake")) && "false".equals(out.getProperty("centerFirstLoad")), "modA's edited method keys off: " + out);
      Check.check(out.getProperty("chunkHandoffSlack") == null, "a known-compatible mod switches nothing");
      Check.check(ModCompat.reason("wake") != null && ModCompat.reason("wake").contains("modA"), "the reason names the mod");
      String details = ModCompat.details();
      Check.check(details.startsWith("scan\tauto\t"), "details start with the scan line: " + details);
      Properties report = new Properties();
      ModCompat.applyForTest(all, edited, new HashMap<>(), report, "report");
      Check.check(report.isEmpty(), "modCompat=report switches nothing: " + report);
      Properties rule = new Properties();
      policy.put("modA", "off:luaSkipEmpty");
      ModCompat.applyForTest(all, edited, policy, rule, "auto");
      Check.check(rule.size() == 1 && "false".equals(rule.getProperty("luaSkipEmpty")), "an off: rule replaces the method's keys: " + rule);

      // Workshop folder: in the game's Steam library, the game dir sits at a different depth per platform
      Path tmp = Files.createTempDirectory("pzopt-modcompat");
      try {
         Path lib = tmp.resolve("SteamLibrary/steamapps");
         Path game = lib.resolve("common/ProjectZomboid"); // Windows
         Path mod = lib.resolve("workshop/content/108600/3809306528/mods/Viewpoint");
         Files.createDirectories(game);
         Files.createDirectories(mod.resolve("common"));
         Files.writeString(mod.resolve("common/mod.info"), "name=Viewpoint\nid=Viewpoint\n");
         File zomboid = tmp.resolve("Zomboid").toFile();
         Map<String, File> dirs = ModCompat.modDirectories(zomboid, game.toFile());
         Check.check(mod.toFile().equals(dirs.get("Viewpoint")), "a Workshop mod is found from the Windows game dir: " + dirs);
         Path local = tmp.resolve("Zomboid/mods/Viewpoint");
         Files.createDirectories(local);
         Files.writeString(local.resolve("mod.info"), "id=Viewpoint\n");
         dirs = ModCompat.modDirectories(zomboid, game.toFile());
         Check.check(local.toFile().equals(dirs.get("Viewpoint")), "Zomboid/mods comes before the Workshop: " + dirs);

         Check.check(lib.resolve("workshop/content/108600").toFile().equals(ModCompat.workshopContent(game.toFile())), "Windows layout");
         Path linux = tmp.resolve("lib/steamapps");
         Check.check(linux.resolve("workshop/content/108600").toFile()
               .equals(ModCompat.workshopContent(linux.resolve("common/ProjectZomboid/projectzomboid").toFile())), "Linux layout");
         Check.check(linux.resolve("workshop/content/108600").toFile()
               .equals(ModCompat.workshopContent(linux.resolve("common/ProjectZomboid/Project Zomboid.app/Contents/Java").toFile())), "macOS layout");
         Check.check(ModCompat.workshopContent(tmp.resolve("games/ProjectZomboid").toFile()) == null, "no steamapps above the game dir: no Workshop folder");
      } finally {
         Updater.deleteTree(tmp);
      }

      // a mod folder's jars: javaJarFile= resolved as ZombieBuddy does, plus a walk that enters only java/ inside media/
      Path jt = Files.createTempDirectory("pzopt-modjars");
      try {
         Path vp = jt.resolve("Viewpoint"); // declared in common/mod.info, the jar in the version folder
         write(vp.resolve("common/mod.info"), "id=Viewpoint\njavaJarFile=media/java/client/Viewpoint.jar\njavaPkgName=viewpoint\n");
         File vpJar = write(vp.resolve("42/media/java/client/Viewpoint.jar"), "jar").toFile();
         List<File> declared = ModCompat.declaredJars(vp.toFile());
         Check.check(List.of(vpJar).equals(declared), "common/mod.info's javaJarFile is found in the version folder: " + declared);
         Path zb = jt.resolve("ZombieBuddy"); // declared by the version folder, relative to it
         write(zb.resolve("42/mod.info"), "id=ZombieBuddy\njavaJarFile=../libs/ZombieBuddy.jar\n");
         File zbJar = write(zb.resolve("libs/ZombieBuddy.jar"), "jar").toFile();
         declared = ModCompat.declaredJars(zb.toFile());
         Check.check(List.of(zbJar).equals(declared), "a version folder's ../libs/ jar is found: " + declared);
         List<File> found = ModCompat.modJars(zb.toFile());
         Check.check(List.of(zbJar).equals(found), "a declared jar the walk also finds is listed once: " + found);
         Path un = jt.resolve("Undeclared"); // no javaJarFile
         write(un.resolve("42/mod.info"), "id=Undeclared\n");
         File lib = write(un.resolve("libs/Lib.jar"), "jar").toFile();
         File mediaJava = write(un.resolve("42/media/java/Mod.jar"), "jar").toFile();
         write(un.resolve("42/media/textures/Art.jar"), "jar"); // inside media/ but not java/: not found
         write(un.resolve("42/media/lua/client/Lua.jar"), "jar");
         found = ModCompat.modJars(un.toFile());
         Check.check(found.size() == 2 && found.contains(lib) && found.contains(mediaJava),
               "an undeclared jar is found in libs/ and media/java/, not in media's other folders: " + found);
         Path art = jt.resolve("DeclaredInArt"); // a declaration counts wherever the jar is
         write(art.resolve("mod.info"), "id=DeclaredInArt\njavaJarFile=media/scripts/X.jar\n");
         File artJar = write(art.resolve("media/scripts/X.jar"), "jar").toFile();
         found = ModCompat.modJars(art.toFile());
         Check.check(List.of(artJar).equals(found), "a declared jar inside media/scripts/ is found: " + found);
         Path real = jt.resolve("elsewhere/RealMod"); // links are followed and reported under the link, as File.isDirectory did
         write(real.resolve("libs/Linked.jar"), "jar");
         try {
            Path link = Files.createSymbolicLink(jt.resolve("LinkedMod"), real);
            found = ModCompat.modJars(link.toFile());
            Check.check(List.of(link.resolve("libs/Linked.jar").toFile()).equals(found), "a linked mod folder is walked: " + found);
            Files.createSymbolicLink(un.resolve("extra"), real.resolve("libs"));
            found = ModCompat.modJars(un.toFile());
            Check.check(found.contains(un.resolve("extra/Linked.jar").toFile()), "a linked subfolder is walked: " + found);
         } catch (UnsupportedOperationException | java.io.IOException e) {
            System.out.println("ModCompatTest: no symbolic links here (" + e + "), link checks skipped");
         }

         // jar scan cache: a jar with the same path, size and time is read back; a new stamp or a changed jar scans again
         Path jar = jt.resolve("patches.jar");
         try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> c : new Class<?>[] {FakePatch.class, ClassOnlyTransformer.class}) {
               z.putNextEntry(new java.util.zip.ZipEntry(c.getName().replace('.', '/') + ".class"));
               z.write(bytes(c));
               z.closeEntry();
            }
         }
         File cacheFile = jt.resolve("pzopt/mod-compat-cache.properties").toFile();
         ModCompat.JarCache cache = ModCompat.JarCache.load(cacheFile, "build-1");
         List<ModCompat.Hit> scanned = new ArrayList<>();
         ModCompat.scanJar("modA", jar.toFile(), shadowed, edited, scanned, cache);
         Check.check(cache.scanned == 1 && cache.fromCache == 0 && scanned.size() == 2, "a new jar is scanned: " + wheres(scanned));
         cache.save();
         cache = ModCompat.JarCache.load(cacheFile, "build-1");
         List<ModCompat.Hit> cached = new ArrayList<>();
         ModCompat.scanJar("modA", jar.toFile(), shadowed, edited, cached, cache);
         Check.check(cache.fromCache == 1 && cache.scanned == 0, "an unchanged jar comes from the cache: " + cache.fromCache + " cached, " + cache.scanned + " scanned");
         Check.check(wheres(scanned).equals(wheres(cached)), "the cached hits are the scanned ones (class-only hit too): " + wheres(cached));
         Check.check(cached.stream().allMatch(h -> "patches.jar".equals(h.jar)) && scanned.stream().allMatch(h -> "patches.jar".equals(h.jar)),
               "cached hits carry their jar file like scanned ones (the menu's check groups by it)");
         cache.save();
         cache = ModCompat.JarCache.load(cacheFile, "build-2");
         ModCompat.scanJar("modA", jar.toFile(), shadowed, edited, new ArrayList<>(), cache);
         Check.check(cache.scanned == 1 && cache.fromCache == 0, "another build or edited-method map scans again");
         cache.save();
         Check.check(jar.toFile().setLastModified(jar.toFile().lastModified() + 5000), "jar time moved");
         cache = ModCompat.JarCache.load(cacheFile, "build-2");
         ModCompat.scanJar("modA", jar.toFile(), shadowed, edited, new ArrayList<>(), cache);
         Check.check(cache.scanned == 1 && cache.fromCache == 0, "a changed jar scans again");
      } finally {
         Updater.deleteTree(jt);
      }
      System.out.println("ModCompatTest ok");
   }
}
