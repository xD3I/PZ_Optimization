package pzopt;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.expose.LuaJavaClassExposer;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.MathLib;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.CoroutineLib;
import se.krka.kahlua.stdlib.OsLib;
import se.krka.kahlua.stdlib.RandomLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.test.UserdataArray;
import se.krka.kahlua.threading.BlockingKahluaThread;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.KahluaNumberConverter;

/**
 * A headless Kahlua state for the Lua VM tests and benchmarks: the game's J2SE platform and standard library (with
 * the game's stdlib.lua), its number converters and its LuaJavaClassExposer for the stub game API of
 * {@link LuaVmStubs}, plus the few global functions mod code calls on its hot paths ({@code getCell}, {@code isClient}).
 * No LuaManager: {@code Core.debug} stays false, so the interpreter runs its release path, as in a player's game.
 */
public final class LuaVm {
   public final J2SEPlatform platform = J2SEPlatform.getInstance();
   public final KahluaTable env;
   public final KahluaThread thread;
   public final KahluaConverterManager converters = new KahluaConverterManager();
   public final LuaJavaClassExposer exposer;
   public final LuaVmStubs.Cell cell;

   static J2SEPlatform platform() {
      return J2SEPlatform.getInstance();
   }

   public static Path gameDir() {
      String d = System.getenv("PZ_DIR");
      if (d == null || d.isEmpty()) {
         d = "/games/steamapps/common/ProjectZomboid/projectzomboid";
      }
      return Path.of(d);
   }

   public LuaVm() {
      this.env = this.platform.newTable();
      this.env.rawset("_G", this.env);
      this.env.rawset("_VERSION", "Kahlua (pzopt LuaVm)");
      MathLib.register(this.platform, this.env);
      BaseLib.register(this.env);
      RandomLib.register(this.platform, this.env);
      UserdataArray.register(this.platform, this.env);
      StringLib.register(this.platform, this.env);
      CoroutineLib.register(this.platform, this.env);
      OsLib.register(this.platform, this.env);
      TableLib.register(this.platform, this.env);
      LuaCompiler.register(this.env);
      BlockingKahluaThread worker = new BlockingKahluaThread(this.platform, this.env);
      KahluaUtil.setWorkerThread(this.env, worker);
      KahluaUtil.setupLibraryText(this.env, worker, gameDir().resolve("stdlib.lua").toFile());
      this.thread = new KahluaThread(this.platform, this.env);
      this.thread.debugOwnerThread = Thread.currentThread();
      KahluaNumberConverter.install(this.converters);
      this.exposer = new LuaJavaClassExposer(this.converters, this.platform, this.env);
      for (Class<?> c : new Class<?>[]{LuaVmStubs.Cell.class, LuaVmStubs.Square.class, LuaVmStubs.Obj.class,
         LuaVmStubs.Sprite.class, LuaVmStubs.Props.class, java.util.ArrayList.class}) {
         for (Method m : c.getMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
               this.exposer.exposeMethod(c, m, this.env);
            }
         }
      }
      this.cell = new LuaVmStubs.Cell(64, 32);
      for (Method m : LuaVmStubs.JCalls.class.getMethods()) {
         if (m.getDeclaringClass() == LuaVmStubs.JCalls.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
            this.exposer.exposeMethod(LuaVmStubs.JCalls.class, m, this.env);
         }
      }
      LuaVmStubs.JCalls jc = new LuaVmStubs.JCalls();
      this.env.rawset("JC", jc);
      try {
         Globals g = new Globals(this.cell);
         for (String name : new String[]{"getCell", "isClient", "isServer"}) {
            Method m = Globals.class.getMethod(name);
            this.exposer.exposeGlobalObjectFunction(this.env, g, m, name);
         }
         this.exposer.exposeGlobalObjectFunction(this.env, jc, LuaVmStubs.JCalls.class.getMethod("addI", int.class, int.class), "jcAdd");
         this.exposer.exposeGlobalObjectFunction(this.env, jc, LuaVmStubs.JCalls.class.getMethod("noop"), "jcNoop");
         this.exposer.exposeGlobalClassFunction(this.env, LuaVmStubs.JCalls.class, LuaVmStubs.JCalls.class.getMethod("sAdd", int.class, int.class), "jcSAdd");
      } catch (ReflectiveOperationException e) {
         throw new RuntimeException(e);
      }
   }

   /** The global functions the stub API offers (exposed like the game's @LuaMethod(global = true) functions). */
   public static final class Globals {
      private final LuaVmStubs.Cell cell;

      Globals(LuaVmStubs.Cell cell) {
         this.cell = cell;
      }

      public LuaVmStubs.Cell getCell() {
         return this.cell;
      }

      public boolean isClient() {
         return false;
      }

      public boolean isServer() {
         return false;
      }
   }

   public LuaClosure load(String source, String name) throws java.io.IOException {
      return LuaCompiler.loadstring(source, name, this.env);
   }

   public LuaClosure loadFile(Path p) throws java.io.IOException {
      return this.load(Files.readString(p), p.getFileName().toString());
   }

   /** Runs a chunk (or a function) protected; throws with the Lua error message if it failed. */
   public Object[] run(Object fn, Object... args) {
      Object[] r = this.thread.pcall(fn, args);
      if (r.length == 0 || r[0] != Boolean.TRUE) {
         throw new IllegalStateException("Lua error: " + (r.length > 1 ? r[1] : "?") + "\n" + (r.length > 2 ? r[2] : ""));
      }
      return r;
   }

   public static Path fixtures() {
      Path p = Path.of("tests/pzopt/fixtures/luavm");
      if (!Files.isDirectory(p)) {
         String repo = System.getProperty("pzopt.repo", System.getenv().getOrDefault("PZOPT_REPO", ""));
         p = Path.of(repo, "tests/pzopt/fixtures/luavm");
      }
      return p;
   }

   static File stdlib() {
      return gameDir().resolve("stdlib.lua").toFile();
   }
}
