package pzopt;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pzopt.BootRepair's file work on a temporary game folder: the removal (inside the folder only, the manifest and file
 * list last, emptied folders gone, stock files kept), the install of an unpacked Workshop copy (the installers'
 * manifest format with real hashes, every file copied), the stock-class check against a jar, which uninstall list
 * belongs to the install, and Zomboid's folder from -cachedir=. run() itself needs the game's boot (Overrides).
 */
public class BootRepairTest {
   static int failures;

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) throws Exception {
      Path dir = Files.createTempDirectory("pzopt-bootrepair-test");
      try {
         remove(dir.resolve("remove"));
         symlinked(dir.resolve("symlink"));
         install(dir.resolve("install"));
         stock(dir.resolve("stock"));
         pending(dir.resolve("pending"));
         zomboid();
         preload();
      } finally {
         Updater.deleteTree(dir);
      }
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("BootRepairTest ok");
   }

   static void write(Path p, String s) throws Exception {
      Files.createDirectories(p.getParent());
      Files.writeString(p, s, StandardCharsets.UTF_8);
   }

   static void remove(Path base) throws Exception {
      Path g = base.resolve("game");
      write(g.resolve("projectzomboid.jar"), "jar");
      write(g.resolve("zombie/GameWindow.class"), "ours");
      write(g.resolve("pzopt/Config.class"), "ours");
      write(g.resolve("media/lua/client/pzopt/tab.lua"), "ours");
      write(g.resolve("media/lua/client/Stock.lua"), "stock");
      write(g.resolve(Updater.MANIFEST), "# files written by install.ps1 - do not edit\n# revision=old installed=x\nzombie/GameWindow.class -\n");
      write(g.resolve(Updater.FILE_LIST), "zombie/GameWindow.class\n");
      write(base.resolve("outside.txt"), "not ours");
      Set<Path> files = new LinkedHashSet<>(List.of(g.resolve(Updater.MANIFEST), g.resolve("pzopt/Config.class"), g.resolve("zombie/GameWindow.class"),
            g.resolve("media/lua/client/pzopt/tab.lua"), g.resolve(Updater.FILE_LIST), g.resolve("../outside.txt"), g.resolve("gone.class")));
      List<Path> dirs = List.of(g.resolve("zombie"), g.resolve("pzopt"), g.resolve("media/lua/client/pzopt"), g.resolve("media/lua/client"), g);
      List<String> left = BootRepair.remove(g, files, dirs);
      check(left.isEmpty(), "nothing left: " + left);
      for (String rel : new String[] {"zombie/GameWindow.class", "pzopt/Config.class", "media/lua/client/pzopt/tab.lua", Updater.MANIFEST, Updater.FILE_LIST}) {
         check(!Files.exists(g.resolve(rel)), "removed " + rel);
      }
      check(Files.exists(base.resolve("outside.txt")), "a path outside the game folder is never touched");
      check(Files.exists(g.resolve("projectzomboid.jar")) && Files.exists(g.resolve("media/lua/client/Stock.lua")), "stock files stay");
      check(!Files.exists(g.resolve("zombie")) && !Files.exists(g.resolve("pzopt")) && !Files.exists(g.resolve("media/lua/client/pzopt")),
            "emptied folders removed");
      check(Files.exists(g.resolve("media/lua/client")) && Files.isDirectory(g), "folders with stock files and the game folder stay");
   }

   /** The game reached through a symlinked library folder: a list with the physical paths still removes the files. */
   static void symlinked(Path base) throws Exception {
      Path real = base.resolve("real/game");
      write(real.resolve("zombie/GameWindow.class"), "ours");
      write(real.resolve("projectzomboid.jar"), "jar");
      Path link = base.resolve("link");
      Files.createSymbolicLink(link, base.resolve("real"));
      Path g = link.resolve("game");
      List<String> left = BootRepair.remove(g, new LinkedHashSet<>(List.of(real.resolve("zombie/GameWindow.class"))), List.of(real.resolve("zombie")));
      check(left.isEmpty() && !Files.exists(real.resolve("zombie/GameWindow.class")), "physical paths under a symlinked game folder are removed");
      check(!Files.exists(real.resolve("zombie")) && Files.exists(real.resolve("projectzomboid.jar")), "its emptied folder too, the jar stays");
   }

   static void install(Path base) throws Exception {
      Path src = base.resolve("copy");
      write(src.resolve("pzopt/build-info.properties"), "revision=new\ncommit=abc1234\nbuilt=1\n");
      write(src.resolve("pzopt/Config.class"), "new pzopt");
      write(src.resolve("zombie/GameWindow.class"), "new override");
      write(src.resolve("Uninstall-PZ-Optimization.cmd"), "@echo off");
      write(src.resolve(Updater.FILE_LIST), "pzopt/Config.class\npzopt/build-info.properties\nzombie/GameWindow.class\nUninstall-PZ-Optimization.cmd\n../evil\n"
            + Updater.FILE_LIST + "\n");
      Path g = base.resolve("game");
      write(g.resolve("projectzomboid.jar"), "jar");
      List<String> rels = BootRepair.releaseList(src);
      check(!rels.contains("../evil") && rels.contains("zombie/GameWindow.class") && rels.size() == 5, "release list keeps the safe paths: " + rels);
      String err = BootRepair.install(src, g, "new", rels);
      check(err == null, "install ok: " + err);
      for (String rel : rels) {
         check(Files.exists(g.resolve(rel)), "installed " + rel);
      }
      List<String> m = Files.readAllLines(g.resolve(Updater.MANIFEST));
      check(m.get(0).startsWith("# files written by the boot repair") && m.get(1).startsWith("# revision=new installed="), "manifest header: " + m);
      check(m.contains("zombie/GameWindow.class " + BootRepair.sha256(g.resolve("zombie/GameWindow.class"))), "manifest has real hashes: " + m);
      check(Updater.previousFiles(g).containsAll(rels), "the updater reads the manifest back");
      // a copy with a file missing: what was copied goes again, no manifest
      Path g2 = base.resolve("game2");
      write(g2.resolve("projectzomboid.jar"), "jar");
      err = BootRepair.install(src, g2, "new", List.of("pzopt/Config.class", "zombie/Missing.class"));
      check(err != null, "a missing file fails the install");
      check(!Files.exists(g2.resolve("pzopt/Config.class")) && !Files.exists(g2.resolve(Updater.MANIFEST)), "a failed install is taken out again");
   }

   static void stock(Path base) throws Exception {
      Path jar = base.resolve("projectzomboid.jar");
      Files.createDirectories(base);
      byte[] cls = "stock bytes".getBytes(StandardCharsets.UTF_8);
      try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
         out.putNextEntry(new JarEntry("zombie/GameWindow.class"));
         out.write(cls);
         out.closeEntry();
      }
      Path tmp = base.resolve("cls");
      Files.write(tmp, cls);
      String sha = BootRepair.sha256(tmp);
      Path copy = base.resolve("copy");
      write(copy.resolve("pzopt/build-info.properties"), "revision=r\noverrides=zombie/GameWindow\nstock.zombie.GameWindow=" + sha + "\n");
      check(BootRepair.stockMatches(copy, jar), "same stock class: matches");
      write(copy.resolve("pzopt/build-info.properties"), "revision=r\noverrides=zombie/GameWindow\nstock.zombie.GameWindow=" + "0".repeat(64) + "\n");
      check(!BootRepair.stockMatches(copy, jar), "another stock class (a hotfix): no match");
      check(!BootRepair.stockMatches(base.resolve("nothing"), jar), "no build-info: no match");
   }

   static void pending(Path base) throws Exception {
      Path list = base.resolve("uninstall-files.txt");
      Path manifest = base.resolve("game/pzopt-installed.txt");
      write(list, "x\n");
      check(BootRepair.pendingIsCurrent(list, manifest), "no manifest: the list stands");
      write(manifest, "# m\n");
      Files.setLastModifiedTime(manifest, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
      check(BootRepair.pendingIsCurrent(list, manifest), "an install older than the list: the list stands");
      Files.setLastModifiedTime(manifest, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
      check(!BootRepair.pendingIsCurrent(list, manifest), "a newer install: the old list is not its uninstall");
   }

   static void zomboid() {
      check(BootRepair.zomboidDir(new String[] {"-debug", "-cachedir=/tmp/zz "}).equals(Path.of("/tmp/zz")), "-cachedir= wins");
      check(BootRepair.zomboidDir(new String[0]).equals(Path.of(System.getProperty("user.home"), "Zomboid")), "else ~/Zomboid");
   }

   /**
    * Every pzopt class BootRepair, Restart and Log refer to (class entries and descriptors of their constant pools) is in
    * BootRepair.PRELOAD: after the removal a class the old install did not load yet is gone (2026-10-29: BenchPlan).
    */
   static void preload() throws Exception {
      Set<String> listed = new LinkedHashSet<>(Arrays.asList(BootRepair.PRELOAD));
      for (Class<?> c : new Class<?>[] {BootRepair.class, Restart.class, Log.class}) {
         for (String ref : pzoptRefs(c)) {
            check(ref.equals(c.getName()) || ref.equals("pzopt.BootRepair") || listed.contains(ref),
                  c.getSimpleName() + " refers to " + ref + ", which BootRepair.PRELOAD does not load before the files change");
         }
      }
      for (String name : BootRepair.PRELOAD) {
         check(Class.forName(name, false, BootRepairTest.class.getClassLoader()) != null, "PRELOAD names a class: " + name);
      }
   }

   static Set<String> pzoptRefs(Class<?> c) throws Exception {
      Set<String> out = new LinkedHashSet<>();
      Pattern desc = Pattern.compile("Lpzopt/([A-Za-z0-9_$]+);");
      try (InputStream raw = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class");
            DataInputStream in = new DataInputStream(raw)) {
         in.readInt();
         in.readUnsignedShort();
         in.readUnsignedShort();
         int n = in.readUnsignedShort();
         String[] utf = new String[n];
         int[] classIdx = new int[n];
         for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
               case 1 -> utf[i] = in.readUTF();
               case 7 -> classIdx[i] = in.readUnsignedShort();
               case 8, 16, 19, 20 -> in.readUnsignedShort();
               case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
               case 15 -> {
                  in.readUnsignedByte();
                  in.readUnsignedShort();
               }
               case 5, 6 -> {
                  in.readLong();
                  i++;
               }
               default -> throw new IllegalStateException("constant pool tag " + tag + " in " + c);
            }
         }
         for (int i = 1; i < n; i++) {
            if (classIdx[i] != 0 && utf[classIdx[i]].startsWith("pzopt/")) {
               out.add(utf[classIdx[i]].replace('/', '.'));
            }
            if (utf[i] != null) {
               Matcher m = desc.matcher(utf[i]);
               while (m.find()) {
                  out.add("pzopt." + m.group(1));
               }
            }
         }
      }
      return out;
   }
}
