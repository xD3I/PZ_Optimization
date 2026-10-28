package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/**
 * setForwardDirectionFromIsoDirection uses a per-thread scratch vector ({@code entityUpdateParallel};
 * the IsoGameCharacter override).
 *
 * <p>The residue of every batched Louisville run (37 exceptions / 3,064 zombies, 1.21% per zombie,
 * unchanged by the position-snapshot AND the Lua-replay layers): {@code IllegalStateException: Forward
 * Direction cannot be zero length vector} out of WalkTowardState, ThumpState and ClimbOverFenceState —
 * ClimbOverFenceState throwing from {@code setDir(IsoDirections.N)}, a CONSTANT, which rules the states'
 * own math out. All 33 stacks route through {@code IsoGameCharacter.setForwardDirectionFromIsoDirection},
 * whose jar body is four instructions: {@code getstatic tempVector2_2; getVectorFromDirection(it);
 * getstatic tempVector2_2; setForwardDirection(it)} — one STATIC scratch Vector2 shared by every character
 * on every thread. And {@code getVectorFromDirection} ZEROES the vector before the switch assigns the
 * direction, so any thread reading between another thread's zeroing and its assignment sees exactly (0,0):
 * the zero is manufactured inside the chain, which is why the snapshot layer could not touch it. Between
 * throws, the same race silently hands a walker another zombie's direction. The fix, in the repo's own
 * idiom (VehicleCull.near → ThreadLocal was xD3I's fix for this exact pattern): the override's method runs
 * on {@code pzopt.UpdateBatch.dirScratch()}, a per-thread Vector2. Identical output single-threaded;
 * no shared write from the batch workers.
 *
 * <p>A runtime hammer needs a constructible IsoGameCharacter, which a bare JVM does not have (its
 * constructor chain runs inventory/body/stats init through game state — same constraint as
 * ZombieGroupGuardTest). So the evidence is structural plus the live run: the jar's method still reads the
 * shared static (if TIS fixes it, this fails and the override gets re-read), the override's method reads
 * no static scratch and routes through dirScratch(), and dirScratch() itself is exercised for per-thread
 * identity.
 */
public class ForwardDirectionScratchTest {

   private static final String CLASS = "zombie/characters/IsoGameCharacter.class";
   private static final String METHOD = "setForwardDirectionFromIsoDirection";

   public static void main(String[] args) throws Exception {
      // ── the jar: the method reads the shared static (the hazard is TIS's, the fix below is ours) ──
      // Up to 42.21 the jar read the static tempVector2_2; 42.22 allocates a fresh Vector2 per call (thread-safe, re-read
      // 2026-10-27: the override keeps the per-thread vector, the same result without the allocation per call).
      MethodModel jarMethod = method(jarClass(CLASS));
      Check.check(readsField(jarMethod, "tempVector2_2") || allocates(jarMethod, "zombie/iso/Vector2"),
            "the jar's " + METHOD + " uses the static tempVector2_2 scratch or a fresh Vector2"
                  + " (if TIS reworked it again, re-read the override against the new jar)");

      // ── the override: no shared scratch, the per-thread vector instead ──
      MethodModel ours = method(ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.characters.IsoGameCharacter"))));
      Check.check(!readsField(ours, "tempVector2_2"),
            METHOD + " no longer touches the static tempVector2_2 scratch");
      Check.check(invokes(ours, "pzopt/UpdateBatch", "dirScratch"),
            METHOD + " takes its scratch vector from UpdateBatch.dirScratch()");

      // ── the tempo/tempo2 statics: the same disease's siblings, found live by run lou-pipe-off
      // (ZombieEatBodyState → faceThisObject → the static tempo → the zero-length throw at frame 41).
      // Every worker-reachable user converts to per-thread scratch; the debug/render/death-path users keep
      // the statics and stay byte-identical to the jar.
      String[][] tempoMethods = {
            {"faceThisObject", "(Lzombie/iso/IsoObject;)V"},
            {"faceThisObjectAlt", "(Lzombie/iso/IsoObject;)V"},
            {"facePosition", "(II)V"},
            {"doDeferredMovement", "()V"},
            {"getMovementSpeed", "()F"},
      };
      ClassModel jarC = jarClass(CLASS);
      ClassModel ourC = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.characters.IsoGameCharacter")));
      for (String[] tm : tempoMethods) {
         MethodModel jm = namedMethod(jarC, tm[0], tm[1]);
         Check.check(readsAnyField(jm, "tempo", "tempo2"),
               "the jar's " + tm[0] + " still uses the static tempo scratch (else re-read the override)");
         MethodModel om = namedMethod(ourC, tm[0], tm[1]);
         Check.check(!readsAnyField(om, "tempo", "tempo2"),
               tm[0] + " no longer touches the static tempo/tempo2 scratch");
         Check.check(invokes(om, "pzopt/UpdateBatch", "tempoScratch") || invokes(om, "pzopt/UpdateBatch", "tempo2Scratch"),
               tm[0] + " takes its scratch from UpdateBatch.tempoScratch()/tempo2Scratch()");
      }

      // ── WalkTowardState's own singleton scratch: the family's last member (run lou-pipe-final, one
      // WalkTowardState zero-length throw with both statics already converted — the state INSTANCE is a
      // singleton, so its temp/worldPos fields are shared by every walking zombie on every thread).
      MethodModel jarExec = namedMethod(jarClass("zombie/ai/states/WalkTowardState.class"),
            "execute", "(Lzombie/characters/IsoGameCharacter;)V");
      Check.check(readsAnyField(jarExec, "temp"), "the jar's WalkTowardState.execute uses the singleton temp field");
      ClassModel wts = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.ai.states.WalkTowardState")));
      for (String[] wm : new String[][]{{"execute", "(Lzombie/characters/IsoGameCharacter;)V"},
            {"calculateTargetLocation", "(Lzombie/characters/IsoZombie;Lzombie/iso/Vector2;)Z"}}) {
         MethodModel om = namedMethod(wts, wm[0], wm[1]);
         Check.check(!readsAnyField(om, "temp", "worldPos"),
               "WalkTowardState." + wm[0] + " no longer touches the singleton temp/worldPos scratch");
         Check.check(invokes(om, "pzopt/UpdateBatch", "tempoScratch") || invokes(om, "pzopt/UpdateBatch", "walkScratch"),
               "WalkTowardState." + wm[0] + " takes per-thread scratch");
      }

      // ── the scratch itself: stable per thread, never shared across threads ──
      Object first = UpdateBatch.dirScratch();
      Check.check(first == UpdateBatch.dirScratch(), "dirScratch() is stable on one thread");
      AtomicReference<Object> other = new AtomicReference<>();
      Thread t = new Thread(() -> other.set(UpdateBatch.dirScratch()));
      t.start();
      t.join();
      Check.check(other.get() != null && other.get() != first, "dirScratch() is a different vector on a different thread");

      System.out.println("ForwardDirectionScratchTest ok");
   }

   private static MethodModel namedMethod(ClassModel model, String name, String desc) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name) && m.methodTypeSymbol().descriptorString().equals(desc)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + name + desc + " not found");
   }

   private static boolean readsAnyField(MethodModel m, String... names) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof FieldInstruction f) {
            for (String n : names) {
               if (f.name().stringValue().equals(n)) {
                  found[0] = true;
               }
            }
         }
      });
      return found[0];
   }

   private static MethodModel method(ClassModel model) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(METHOD) && m.methodTypeSymbol().descriptorString().equals("()V")) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + METHOD + "()V not found");
   }

   private static boolean allocates(MethodModel m, String className) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof NewObjectInstruction n && n.className().name().stringValue().equals(className)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static boolean readsField(MethodModel m, String fieldName) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof FieldInstruction f && f.name().stringValue().equals(fieldName)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static boolean invokes(MethodModel m, String owner, String name) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv
               && inv.owner().name().stringValue().equals(owner)
               && inv.name().stringValue().equals(name)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   /** The jar's copy of a class, parsed without running it (as in ZombieGroupGuardTest). */
   private static ClassModel jarClass(String entry) throws Exception {
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (!part.endsWith("projectzomboid.jar")) {
            continue;
         }
         try (ZipFile zip = new ZipFile(part)) {
            var e = zip.getEntry(entry);
            Check.check(e != null, "the game jar still has " + entry);
            try (var in = zip.getInputStream(e)) {
               return ClassFile.of().parse(in.readAllBytes());
            }
         }
      }
      throw new AssertionError("FAILED: projectzomboid.jar is not on the test classpath");
   }

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
            + " — is the IsoGameCharacter override built?");
   }
}
