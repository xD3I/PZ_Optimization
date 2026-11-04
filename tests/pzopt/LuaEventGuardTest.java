package pzopt;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * Lua event dispatch never runs on a frame worker ({@code entityUpdateParallel}; the LuaEventManager override).
 *
 * <p>Vanilla {@code triggerEvent}'s main-thread path writes the shared static argument slots
 * {@code a1..a8}/{@code a1index..a8index}, and its off-thread path takes the {@code EventMap} monitor and queues
 * into a shared pool — neither may happen from a worker mid-batch, and a mod's event handler running against a
 * half-updated entity is wrong even where it would not crash. So every {@code triggerEvent} overload starts with:
 * if {@code UpdateBatch.onWorkerNow()}, count it ({@code onLuaSuppressed()}) and return. Ported from PZMulticore's
 * LuaEventManagerPatcher (same funnel, same early return); pcall-level guarding (KahluaThread) is deliberately
 * deferred until evidence shows a path that bypasses triggerEvent.
 *
 * <p>Runtime dispatch into a real Lua state is NOT exercised here — a bare JVM has no Kahlua environment
 * ({@code LuaManager.env} stays null), which is also why the game-thread calls below are limited to the two
 * overloads whose vanilla bodies null-check {@code env}. The guard itself needs no Lua state (it returns at
 * entry), so worker-side suppression IS exercised for all nine overloads; on top of that the built override's
 * bytecode is pinned: every {@code triggerEvent} overload the jar ships exists here and begins with the guard.
 */
public class LuaEventGuardTest {

   /** Calls the real triggerEvent overloads from whatever thread the batch put this probe on. */
   static final class Probe extends IsoMovingObject {
      final int index;
      volatile boolean ranOnWorker;
      volatile boolean ran;

      Probe(int index) {
         super(false);
         this.index = index;
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         this.ranOnWorker = Thread.currentThread() instanceof FrameBatch.Worker;
         Object o = "x";
         if (this.ranOnWorker) {
            // all nine overloads: each must return at the guard (anything past it would NPE on the null Lua env)
            LuaEventManager.triggerEvent("PzoptGuardProbe");
            LuaEventManager.triggerEvent("PzoptGuardProbe", o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o, o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o, o, o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o, o, o, o, o);
            LuaEventManager.triggerEvent("PzoptGuardProbe", o, o, o, o, o, o, o, o);
         } else {
            // the game thread mid-batch dispatches as vanilla: these two overloads return on the null env
            // without counting a suppression (the other seven have no env null-check and need a Lua state)
            LuaEventManager.triggerEvent("PzoptGuardProbe");
            LuaEventManager.triggerEvent("PzoptGuardProbe", o);
         }
         this.ran = true;
         try {
            Thread.sleep(1); // spread the batch across threads, as in UpdateBatchTest
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      // ── bytecode pins first: the override really shadows the jar and guards every overload ──
      Set<String> jarOverloads = triggerEventOverloads(jarClass("zombie/Lua/LuaEventManager.class"));
      Check.check(jarOverloads.size() == 9, "the jar ships nine triggerEvent overloads, found " + jarOverloads);

      ClassModel override = java.lang.classfile.ClassFile.of()
            .parse(Files.readAllBytes(looseClass("zombie.Lua.LuaEventManager")));
      Set<String> ourOverloads = triggerEventOverloads(override);
      Check.check(ourOverloads.equals(jarOverloads),
            "the override carries exactly the jar's triggerEvent overloads; jar=" + jarOverloads + " ours=" + ourOverloads);

      for (MethodModel m : override.methods()) {
         if (!m.methodName().stringValue().equals("triggerEvent")) {
            continue;
         }
         InvokeInstruction first = null;
         boolean suppressed = false;
         for (var element : m.code().orElseThrow()) {
            if (element instanceof InvokeInstruction inv) {
               if (first == null) {
                  first = inv;
               }
               if (inv.owner().name().stringValue().equals("pzopt/UpdateBatch")
                     && inv.name().stringValue().equals("captureLuaEvent")) {
                  suppressed = true;
               }
            }
         }
         String sig = m.methodTypeSymbol().descriptorString();
         Check.check(first != null && first.owner().name().stringValue().equals("pzopt/UpdateBatch")
                     && first.name().stringValue().equals("onWorkerNow"),
               "triggerEvent" + sig + " begins with the onWorkerNow() guard, first call was "
                     + (first == null ? "none" : first.owner().name().stringValue() + "." + first.name().stringValue()));
         Check.check(suppressed, "triggerEvent" + sig + " routes the worker dispatch to captureLuaEvent()"
               + " (which captures for replay, or counts-and-drops when entityUpdateLuaReplay is off)");
      }

      // ── runtime needs drop mode: the probes fire all nine overloads on workers, and with replay on the
      // fan-out would dispatch the seven env-unchecked overloads into the bare JVM's null Lua state. The
      // default run pins the bytecode above (capture-mode runtime lives in LuaEventReplayTest) and then runs
      // itself once more with the key off for the drop-mode runtime below.
      if (Config.ENTITY_UPDATE_LUA_REPLAY) {
         relaunchWithReplayOff();
         System.out.println("LuaEventGuardTest ok");
         return;
      }

      // ── runtime (drop mode): a worker mid-batch is suppressed and counted, the game thread is untouched ──
      Check.check(UpdateBatch.getLuaSuppressedCount() == 0, "no suppression before the batch");

      int n = 192;
      Probe[] probes = new Probe[n];
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(i);
         UpdateBatch.add(probes[i]);
      }
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      PrintStream old = System.out;
      System.setOut(new PrintStream(buf, true));
      try {
         UpdateBatch.run(UpdateSchedulerSimulationLevel.FULL);
      } finally {
         System.setOut(old);
      }
      String logged = buf.toString();

      int onWorker = 0;
      int onGameThread = 0;
      for (Probe p : probes) {
         Check.check(p.ran, "probe " + p.index + " ran (a throw past the guard would have killed it)");
         if (p.ranOnWorker) {
            onWorker++;
         } else {
            onGameThread++;
         }
      }
      Check.check(onWorker > 0, "the batch put probes on the workers: " + onWorker);
      Check.check(onGameThread > 0, "the game thread worked the batch too: " + onGameThread);
      Check.check(!UpdateBatch.hasFailed(), "no probe threw: the guard returned before the null Lua env");

      long suppressed = UpdateBatch.getLuaSuppressedCount();
      Check.check(suppressed == 9L * onWorker,
            "every worker call was suppressed and nothing else was counted: " + onWorker
                  + " worker probes x 9 overloads = " + (9L * onWorker) + ", counted " + suppressed);

      // the one-shot stack dump named the call site, once
      int dumps = count(logged, "first Lua event suppressed on a worker");
      Check.check(dumps == 1, "the first suppression dumped a stack exactly once, saw " + dumps + " in: " + logged);
      Check.check(logged.contains("triggerEvent"),
            "the dump shows WHICH dispatch from WHERE (a triggerEvent frame), got: " + logged);

      // ── game thread outside a batch: vanilla path, no counting ──
      LuaEventManager.triggerEvent("PzoptGuardProbe");
      LuaEventManager.triggerEvent("PzoptGuardProbe", "x");
      Check.check(UpdateBatch.getLuaSuppressedCount() == suppressed,
            "game-thread dispatch outside a batch never counts as suppressed");

      Check.check(UpdateBatch.describe().contains("luaSuppressed=" + suppressed),
            "describe() folds the suppression count in: " + UpdateBatch.describe());

      System.out.println("LuaEventGuardTest ok");
   }

   /** Run this same test in a subprocess with entityUpdateLuaReplay off, for the drop-mode runtime half. */
   private static void relaunchWithReplayOff() throws Exception {
      String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
      java.util.List<String> cmd = new java.util.ArrayList<>();
      cmd.add(javaBin);
      cmd.add("-Dpzopt.dev=true");
      cmd.add("-Dpzopt.entityUpdateLuaReplay=false");
      String userOptions = System.getProperty("pzopt.userOptionsFile");
      if (userOptions != null) {
         cmd.add("-Dpzopt.userOptionsFile=" + userOptions);
      }
      cmd.add("-cp");
      cmd.add(System.getProperty("java.class.path"));
      // a signed game jar: the overrides load through the agent (scripts/test.sh), the relaunch needs it too
      cmd.addAll(cmd.size() - 2, java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(a -> a.startsWith("-javaagent:")).toList());
      cmd.add(LuaEventGuardTest.class.getName());

      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();
      Check.check(exit == 0 && out.contains("LuaEventGuardTest ok"),
            "the drop-mode relaunch (entityUpdateLuaReplay=false) passed; exit=" + exit + " output:\n" + out);
   }

   private static Set<String> triggerEventOverloads(ClassModel model) {
      Set<String> out = new HashSet<>();
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals("triggerEvent")) {
            out.add(m.methodTypeSymbol().descriptorString());
         }
      }
      return out;
   }

   private static int count(String haystack, String needle) {
      int n = 0;
      for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
         n++;
      }
      return n;
   }

   /** The jar's copy of a class, parsed without running it (as in ItemVisualsScratchTest). */
   private static ClassModel jarClass(String entry) throws Exception {
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (!part.endsWith("projectzomboid.jar")) {
            continue;
         }
         try (ZipFile zip = new ZipFile(part)) {
            var e = zip.getEntry(entry);
            Check.check(e != null, "the game jar still has " + entry);
            try (var in = zip.getInputStream(e)) {
               return java.lang.classfile.ClassFile.of().parse(in.readAllBytes());
            }
         }
      }
      throw new AssertionError("FAILED: projectzomboid.jar is not on the test classpath");
   }

   /** The compiled override, from the build output on the test classpath (build/classes ahead of the jar). */
   private static Path looseClass(String className) {
      String rel = className.replace('.', '/') + ".class";
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (part.endsWith(".jar")) {
            continue;
         }
         Path p = Path.of(part).resolve(rel);
         if (Files.isRegularFile(p)) {
            return p;
         }
      }
      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath"
            + " (the LuaEventManager override is not built)");
   }
}
