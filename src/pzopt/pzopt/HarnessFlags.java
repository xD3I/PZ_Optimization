package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import zombie.ZomboidFileSystem;

/** The key=value flag file harness/run.sh writes to Zomboid/Lua/pzopt-harness.txt, read once. */
public final class HarnessFlags {
   /** The flags came from the built-in benchmark's plan, not from a harness/run.sh flag file. */
   static boolean fromBenchmark;
   private static final Properties props = load();

   private HarnessFlags() {
   }

   private static Properties load() {
      Properties p = new Properties();
      File f = new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua" + File.separator + "pzopt-harness.txt");
      if (f.isFile()) {
         try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            p.load(r);
         } catch (IOException e) {
            Log.warn("harness: could not read " + f + ": " + e);
         }
         return p;
      }
      Properties bench = BenchPlan.harnessFlags(); // a built-in benchmark pass: its scenario's route (BenchSuite)
      if (bench != null) {
         fromBenchmark = true;
         return bench;
      }
      return p;
   }

   /** A save's scene file (pzopt.HordeScene): its keys win over the flag file's; null when no scene is loaded. */
   private static volatile Properties overlay;

   /** pzopt.HordeScene, game thread: the loaded save's scene keys (null clears them). */
   static void overlay(Properties p) {
      overlay = p;
   }

   public static String get(String key) {
      Properties o = overlay;
      String v = o == null ? null : o.getProperty(key);
      return v != null ? v : props.getProperty(key);
   }

   public static String get(String key, String def) {
      Properties o = overlay;
      String v = o == null ? null : o.getProperty(key);
      return v != null ? v : props.getProperty(key, def);
   }

   /** Append started=1 to the flag file: the Lua mod quits the process on the next return to the main menu. */
   static void markStarted() {
      if (fromBenchmark) {
         return; // no flag file: the benchmark's own Lua quits the process at the menu (pzopt_benchmark.lua)
      }
      File f = new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua" + File.separator + "pzopt-harness.txt");
      try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
         w.write("started=1\n");
      } catch (IOException e) {
         Log.warn("harness: could not mark " + f + " started: " + e);
      }
   }

   static void setRejectReason(String reason) {
      props.setProperty("reject_reason", reason);
   }
}
