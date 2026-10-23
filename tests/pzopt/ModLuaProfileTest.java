package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.MathLib;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;

/**
 * The benchmark's mod Lua profiler (ModLuaProfile) on a real Kahlua interpreter without the game: Lua files placed in
 * mod folders (one Build 42 common/ folder), their handlers driven frame by frame through the profiler's hooks while its
 * sampler runs, and the sampled shares checked against the handlers' measured times; the wrapper rule (a mod wrapping a
 * vanilla UI function is charged its own code only); call counts, events, the slowest frames; the hooks' and the
 * sampler's cost; the files and BenchDash's Mods row on a fixture run folder; the properties round trip; ModCompat's
 * step-aside; and the Lua files of the Workshop mods on this machine loaded into a stubbed game API, their event
 * handlers called through the hook and attributed to their mod.info ids.
 */
public class ModLuaProfileTest {
   static J2SEPlatform platform = J2SEPlatform.getInstance();
   static KahluaThread thread;
   static LuaCaller caller = new LuaCaller(new KahluaConverterManager());

   static KahluaTable newEnv() {
      KahluaTable env = platform.newTable();
      env.rawset("_G", env);
      BaseLib.register(env);
      MathLib.register(platform, env);
      StringLib.register(platform, env);
      TableLib.register(platform, env);
      LuaCompiler.register(env);
      return env;
   }

   static KahluaThread newThread(KahluaTable env) {
      KahluaThread t = new KahluaThread(platform, env);
      t.debugOwnerThread = Thread.currentThread();
      return t;
   }

   /** Compiles a file as LuaManager.RunLua does (its full path on the prototypes) and runs it; false when it fails. */
   static boolean run(KahluaThread t, KahluaTable env, String path, String src) {
      String name = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
      FuncState.currentFile = name;
      FuncState.currentfullFile = path;
      try {
         LuaClosure c = LuaCompiler.loadstring(src, name, env);
         Object[] r = t.pcall(c, new Object[0]);
         return r.length > 0 && Boolean.TRUE.equals(r[0]);
      } catch (Throwable e) {
         return false;
      }
   }

   static void write(File f, String text) throws Exception {
      f.getParentFile().mkdirs();
      Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
   }

   static void callOn(Object fn, String event) {
      ModLuaProfile.callEvent(caller, thread, (LuaClosure)fn, new Object[0], event);
   }

   static ModLuaProfile.Origin origin(ModLuaProfile.Result r, String id) {
      for (ModLuaProfile.Origin o : r.origins) {
         if (o.id.equals(id)) {
            return o;
         }
      }
      return null;
   }

   @SuppressWarnings("unchecked")
   static List<ModCompat.Hit> foundHits() throws Exception {
      java.lang.reflect.Field f = ModCompat.class.getDeclaredField("found");
      f.setAccessible(true);
      return (List<ModCompat.Hit>)f.get(null);
   }

   public static void main(String[] args) throws Exception {
      java.nio.file.Path home = Files.createTempDirectory("pzopt-modlua-test");
      System.setProperty("deployment.user.cachedir", home.toString()); // UserOptions.zomboidDir -> <home>/Zomboid
      Class.forName("pzopt.Config"); // its ModCompat scan of this machine's mods first, then a clean slate for the test
      foundHits().clear();

      // ---- mods on disk: BusyMod (mod.info beside media/), CheapMod (Build 42: common/ + a version folder's mod.info)
      File mods = new File(home.toFile(), "mods");
      write(new File(mods, "BusyMod/42/mod.info"), "name=Busy Mod\nid=BusyMod\n");
      write(new File(mods, "CheapMod/42.19/mod.info"), "name=Cheap Mod\nid=CheapMod\n");
      String busyPath = new File(mods, "BusyMod/42/media/lua/client/BusyMod/Busy.lua").getAbsolutePath();
      String cheapPath = new File(mods, "CheapMod/common/media/lua/client/Cheap.lua").getAbsolutePath();
      new File(cheapPath).getParentFile().mkdirs();
      String gamePath = new File("media/lua/client/ISUI/ISFakePanel.lua").getAbsolutePath(); // (the game's media/lua, never created)
      Check.check("game".equals(LuaOrigin.of(gamePath)), "the game's file: " + LuaOrigin.of(gamePath));
      Check.check("mod:BusyMod".equals(LuaOrigin.of(busyPath)), "a mod file: " + LuaOrigin.of(busyPath));
      Check.check("mod:CheapMod".equals(LuaOrigin.of(cheapPath)), "a Build 42 common/ file takes the version folder's id: " + LuaOrigin.of(cheapPath));

      KahluaTable env = newEnv();
      thread = newThread(env);
      ModLuaProfile.threadSource = () -> thread;
      ModLuaProfile.modName = id -> id.equals("BusyMod") ? "Busy Mod" : null;
      Check.check(run(thread, env, gamePath, ""
            + "ISFakePanel = {}\n"
            + "function ISFakePanel:render() local s = 0 for i = 1, 6000 do s = s + i % 7 end self.s = s end\n"
            + "ISFakePanel_orig = ISFakePanel.render\n"
            + "function NewPanel() return setmetatable({}, { __index = ISFakePanel }) end\n"
            + "function GameTick() local s = 0 for i = 1, 9000 do s = s + i % 3 end return s end\n"), "the game file loads");
      Check.check(run(thread, env, busyPath, ""
            + "BUSY_SPIKE = 1\n"
            + "local function work(n) local s = 0 for i = 1, n do s = s + (i * 3) % 11 end return s end\n"
            + "function BusyModTick() return work(12000 * BUSY_SPIKE) end\n"
            + "local orig = ISFakePanel.render\n"
            + "ISFakePanel.render = function(self) orig(self) work(3000) end\n"), "the busy mod loads");
      Check.check(run(thread, env, cheapPath, "function CheapModTick() local x = 1 + 1 return x end\n"), "the cheap mod loads");
      Object busy = env.rawget("BusyModTick"), cheap = env.rawget("CheapModTick"), gameTick = env.rawget("GameTick");
      Object wrapper = thread.tableget(env.rawget("ISFakePanel"), "render"), orig = env.rawget("ISFakePanel_orig");
      KahluaTable panel = (KahluaTable)thread.pcall(env.rawget("NewPanel"), new Object[0])[1]; // an element's table: the class behind __index
      Object element = new Object();

      // the wrapper's split: how much of it is the vanilla body (warm, then measured)
      for (int i = 0; i < 300; i++) {
         thread.pcall(wrapper, new Object[] {panel});
         thread.pcall(orig, new Object[] {panel});
      }
      long tw = 0, to = 0;
      for (int i = 0; i < 400; i++) {
         long a = System.nanoTime();
         thread.pcall(wrapper, new Object[] {panel});
         long b = System.nanoTime();
         thread.pcall(orig, new Object[] {panel});
         tw += b - a;
         to += System.nanoTime() - b;
      }
      double vanillaShare = Math.min(1, (double)to / tw);

      // ---- a profiled "pass": frames of OnTick handlers and one UI render, every 50th frame a BusyMod spike
      long tBusy = 0, tCheap = 0, tGame = 0, tUi = 0;
      int frames = 0;
      ModLuaProfile.start();
      Check.check(ModLuaProfile.on && ModLuaProfile.active(), "hooks and sampler on");
      long end = System.nanoTime() + 1_800_000_000L;
      while (System.nanoTime() < end) {
         ModLuaProfile.onFrame(System.nanoTime());
         env.rawset("BUSY_SPIKE", frames % 50 == 25 ? 15.0 : 1.0);
         long a = System.nanoTime();
         callOn(busy, "OnTick");
         long b = System.nanoTime();
         callOn(cheap, "OnTick");
         long c = System.nanoTime();
         callOn(gameTick, "OnPlayerUpdate");
         long d = System.nanoTime();
         ModLuaProfile.uiEnter();
         caller.pcallvoid(thread, wrapper, new Object[] {panel});
         ModLuaProfile.uiExit(element, panel, 1);
         long e = System.nanoTime();
         tBusy += b - a;
         tCheap += c - b;
         tGame += d - c;
         tUi += e - d;
         frames++;
      }
      ModLuaProfile.Result r = ModLuaProfile.stop();
      Check.check(!ModLuaProfile.on && !ModLuaProfile.active(), "hooks and sampler off after the pass");
      double nf = r.frames;
      double busyTruth = (tBusy + tUi * (1 - vanillaShare)) / 1e6 / nf, gameTruth = (tGame + tUi * vanillaShare) / 1e6 / nf;
      ModLuaProfile.Origin ob = origin(r, "mod:BusyMod"), oc = origin(r, "mod:CheapMod"), og = origin(r, "game");
      System.out.printf(java.util.Locale.ROOT, "mod lua profile: %d frames, %d samples (%.0f Hz, %d in Lua); BusyMod %.4f ms/frame (measured %.4f), game %.4f (measured %.4f), "
            + "CheapMod %.4f (measured %.4f), wrapper's vanilla share %.2f%n", r.frames, r.samples, r.sampleHz, r.luaSamples, ob == null ? -1 : ob.ms, busyTruth,
            og == null ? -1 : og.ms, gameTruth, oc == null ? -1 : oc.ms, tCheap / 1e6 / nf, vanillaShare);
      Check.check(r.sampleHz > 500, "the sampler ran near its rate: " + r.sampleHz);
      Check.check(ob != null && og != null && oc != null, "the two mods and the game's Lua each have a row");
      Check.check(Math.abs(ob.ms - busyTruth) <= 0.2 * busyTruth, "BusyMod's sampled time within 20 % of its measured own time: " + ob.ms + " vs " + busyTruth);
      Check.check(Math.abs(og.ms - gameTruth) <= 0.25 * gameTruth, "the game's Lua (its own handler and the vanilla body the wrapper calls) within 25 %: " + og.ms + " vs " + gameTruth);
      Check.check(oc.ms < 0.05 * ob.ms, "CheapMod costs next to nothing: " + oc.ms);
      Check.check(Math.abs(ob.callsPerFrame - 2) < 0.05 && Math.abs(oc.callsPerFrame - 1) < 0.05 && Math.abs(og.callsPerFrame - 1) < 0.05,
            "calls per frame: BusyMod 2 (tick + UI render), CheapMod 1, game 1: " + ob.callsPerFrame + " " + oc.callsPerFrame + " " + og.callsPerFrame);
      Check.check(r.worst == ob && "Busy Mod (BusyMod)".equals(ob.name) && "mod".equals(ob.kind) && "game".equals(og.kind), "BusyMod is the worst mod: " + (r.worst == null ? null : r.worst.id));
      Check.check(ob.p99Ms > 4 * ob.ms, "BusyMod's spikes land in the slowest 1 % of frames: p99 contribution " + ob.p99Ms + " vs mean " + ob.ms);
      Check.check(r.spikeRows.size() > 0 && "mod:BusyMod".equals(r.spikeRows.get(0)[4]), "the slowest frame's biggest Lua is BusyMod: " + String.join(",", r.spikeRows.get(0)));
      Check.check(r.mods > 0 && Math.abs(r.mods - (ob.ms + oc.ms)) < 1e-6 && r.luaMs >= r.mods + r.gameMs - 1e-6, "mods' sum and the Lua total");
      boolean workRow = false, wrapperRow = false, vanillaUiRow = false, gameTickRow = false;
      for (String[] f : r.fnRows) { // origin, file, function, line, event, self, total, calls
         workRow |= f[0].equals("mod:BusyMod") && f[1].equals("client/BusyMod/Busy.lua") && f[2].equals("work") && f[4].equals("OnTick") && Double.parseDouble(f[5]) > 0;
         wrapperRow |= f[0].equals("mod:BusyMod") && f[2].equals("render") && f[4].equals(ModLuaProfile.UI_RENDER) && Math.abs(Double.parseDouble(f[7]) - 1) < 0.05;
         vanillaUiRow |= f[0].equals("game") && f[1].equals("client/ISUI/ISFakePanel.lua") && f[2].equals("render") && f[4].equals(ModLuaProfile.UI_RENDER) && Double.parseDouble(f[5]) > 0;
         gameTickRow |= f[0].equals("game") && f[2].equals("GameTick") && f[4].equals("OnPlayerUpdate");
      }
      Check.check(workRow, "BusyMod's work() under OnTick, self time > 0");
      Check.check(wrapperRow, "the mod's render wrapper, one UI render call a frame");
      Check.check(vanillaUiRow, "the vanilla render body the wrapper calls stays the game's, under UI render");
      Check.check(gameTickRow, "the game's handler under its event");
      Map<String, String[]> ev = new LinkedHashMap<>();
      for (String[] e : r.eventRows) {
         ev.put(e[0], e);
      }
      Check.check(ev.containsKey("OnTick") && ev.containsKey("OnPlayerUpdate") && ev.containsKey(ModLuaProfile.UI_RENDER), "the three labels: " + ev.keySet());
      Check.check(Math.abs(Double.parseDouble(ev.get("OnTick")[3]) - 2) < 0.05 && "mod:BusyMod".equals(ev.get("OnTick")[4]), "OnTick: 2 calls a frame, BusyMod most");
      Check.check(Double.parseDouble(ev.get("OnPlayerUpdate")[2]) == 0, "OnPlayerUpdate has no mod time");
      System.out.printf(java.util.Locale.ROOT, "mod lua profile: hook %.1f ns, %.2f us/frame on the game thread, sampler %.2f %% of a core%n", r.hookNs, r.overheadUs, r.samplerCpuPct);
      Check.check(r.hookNs > 0 && r.hookNs < 500, "a hook costs well under a microsecond: " + r.hookNs);

      // ---- Kahlua's own Lua-coded library (stdlib's math.max, table.sort's comparator: prototypes with no file) is its caller's
      String noFile = null;
      FuncState.currentFile = "stdlib";
      FuncState.currentfullFile = noFile;
      LuaClosure libChunk = LuaCompiler.loadstring("function LibWork(n) local s = 0 for i = 1, n do s = s + i % 5 end return s end\n", "stdlib", env);
      thread.pcall(libChunk, new Object[0]);
      Check.check(run(thread, env, busyPath.replace("Busy.lua", "Lib.lua"), "function BusyLibTick() local r = LibWork(20000) return r end\n"), "the library caller loads");
      Object libTick = env.rawget("BusyLibTick");
      ModLuaProfile.start();
      long libEnd = System.nanoTime() + 400_000_000L;
      while (System.nanoTime() < libEnd) {
         ModLuaProfile.onFrame(System.nanoTime());
         callOn(libTick, "OnTick");
      }
      ModLuaProfile.Result lr = ModLuaProfile.stop();
      ModLuaProfile.Origin lb = origin(lr, "mod:BusyMod"), lo = origin(lr, "other");
      Check.check(lb != null && lb.ms > 0 && (lo == null || lo.ms < 0.05 * lb.ms), "library frames' self time goes to the mod that called them: BusyMod "
            + (lb == null ? -1 : lb.ms) + ", unknown " + (lo == null ? 0 : lo.ms));
      boolean libTotal = false;
      for (String[] f : lr.fnRows) {
         libTotal |= f[0].equals("other") && f[2].equals("LibWork") && Double.parseDouble(f[6]) > 0 && Double.parseDouble(f[5]) < 0.05 * Double.parseDouble(f[6]); // (a sample of the call's set-up sees the library frame alone)
      }
      Check.check(libTotal, "the library function keeps a total-time row, (almost) no self time: " + lr.fnRows.stream().filter(f -> f[2].equals("LibWork")).map(f -> String.join(" ", f)).toList());

      // ---- the hook passes the call through: same caller method and arguments, its exception unchanged, the label stack restored
      RuntimeException boom = new RuntimeException("mod error");
      Object[] seenArgs = new Object[1];
      LuaCaller throwing = new LuaCaller(new KahluaConverterManager()) {
         @Override
         public void protectedCallVoid(KahluaThread t, Object fn, Object[] a) {
            seenArgs[0] = a;
            throw boom;
         }
      };
      ModLuaProfile.start();
      Object[] myArgs = {"x"};
      Throwable caught = null;
      try {
         ModLuaProfile.callEvent(throwing, thread, (LuaClosure)busy, myArgs, "OnTick");
      } catch (Throwable t) {
         caught = t;
      }
      Check.check(caught == boom && seenArgs[0] == myArgs, "the handler's exception and arguments pass through unchanged");
      Thread me = Thread.currentThread();
      Thread other = new Thread(() -> {
         thread.debugOwnerThread = Thread.currentThread();
         ModLuaProfile.callEvent(caller, thread, (LuaClosure)cheap, new Object[0], "OnTick");
         thread.debugOwnerThread = me;
      });
      ModLuaProfile.stop();

      // ---- overhead: the hook's check when off, the hook when on, the sampler beside running Lua
      Object[] none = new Object[0];
      for (int i = 0; i < 50000; i++) {
         caller.protectedCallVoid(thread, cheap, none);
      }
      double direct = Double.MAX_VALUE, offPath = Double.MAX_VALUE, onPath = Double.MAX_VALUE;
      for (int round = 0; round < 5; round++) {
         long a = System.nanoTime();
         for (int i = 0; i < 100000; i++) {
            caller.protectedCallVoid(thread, cheap, none);
         }
         long b = System.nanoTime();
         for (int i = 0; i < 100000; i++) {
            if (ModLuaProfile.on) { // LuaEventProfile.call's path with the profiler off
               ModLuaProfile.callEvent(caller, thread, (LuaClosure)cheap, none, "OnTick");
            } else {
               caller.protectedCallVoid(thread, cheap, none);
            }
         }
         long c = System.nanoTime();
         direct = Math.min(direct, (b - a) / 1e5);
         offPath = Math.min(offPath, (c - b) / 1e5);
      }
      ModLuaProfile.start();
      for (int round = 0; round < 5; round++) {
         long a = System.nanoTime();
         for (int i = 0; i < 100000; i++) {
            ModLuaProfile.callEvent(caller, thread, (LuaClosure)cheap, none, "OnTick");
         }
         onPath = Math.min(onPath, (System.nanoTime() - a) / 1e5);
      }
      ModLuaProfile.stop();
      // the sampler's effect on the interpreter: a fixed Lua workload with and without it
      double busyOff = Double.MAX_VALUE, busyOn = Double.MAX_VALUE;
      for (int round = 0; round < 4; round++) {
         long a = System.nanoTime();
         for (int i = 0; i < 60; i++) {
            caller.protectedCallVoid(thread, busy, none);
         }
         busyOff = Math.min(busyOff, (System.nanoTime() - a) / 60e3);
         ModLuaProfile.start();
         a = System.nanoTime();
         for (int i = 0; i < 60; i++) {
            caller.protectedCallVoid(thread, busy, none);
         }
         busyOn = Math.min(busyOn, (System.nanoTime() - a) / 60e3);
         ModLuaProfile.stop();
      }
      System.out.printf(java.util.Locale.ROOT, "mod lua profile overhead: a cheap handler %.1f ns direct, %.1f ns through the off hook (%+.1f), %.1f ns through the on hook (%+.1f); "
            + "a %.0f us Lua handler %+.2f %% with the sampler running%n", direct, offPath, offPath - direct, onPath, onPath - direct, busyOff, 100 * (busyOn / busyOff - 1));
      Check.check(offPath - direct < Math.max(15, 0.25 * direct), "the off hook costs nothing measurable: " + (offPath - direct) + " ns");
      Check.check(onPath - direct < 400, "the on hook stays well under a microsecond: " + (onPath - direct) + " ns");
      Check.check(busyOn < busyOff * 1.10, "the sampler slows running Lua by less than 10 %: " + busyOn + " vs " + busyOff);
      // a thread other than the one that started the pass is passed through without counting
      ModLuaProfile.start();
      other.start();
      other.join();
      ModLuaProfile.Result ro = ModLuaProfile.stop();
      Check.check(ro.hookCalls == 0, "a call from another thread is not counted: " + ro.hookCalls);

      // ---- the files and BenchDash's Mods row on a fixture run folder (the first profile's result)
      String run = "20261021-130000";
      File dir = BenchPlan.runDir(run);
      dir.mkdirs();
      String base = "step-00-spin-A-1";
      ModLuaProfile.writeCsv(r, new File(dir, base + "-luaprof.csv"));
      ModLuaProfile.writeProperties(r, new File(dir, base + "-luaprof.properties"));
      List<String> csv = Files.readAllLines(new File(dir, base + "-luaprof.csv").toPath());
      Check.check(csv.size() == r.frames + 1 && csv.get(0).startsWith("frame,t_s,frame_ms,lua_ms,") && csv.get(0).contains("mod:BusyMod"), "a row per frame: " + csv.get(0));
      Map<String, String> p = new LinkedHashMap<>();
      p.put("scenario", "spin");
      p.put("config", "A");
      p.put("pass", "1");
      p.put("status", "ok");
      p.put("seconds", "1.8");
      BenchResults.writeStep(run, 0, p);
      String text = BenchDash.text(run + "\tspin\tA\t1");
      Check.check(BenchDashTest.value(BenchDashTest.panel(text, 61), "V", "mods") != null, "panel 61: Lua per frame");
      Check.check(BenchDashTest.value(BenchDashTest.panel(text, 63), "V", "mod").equals("Busy Mod (BusyMod)"), "panel 63: the worst mod by name");
      List<String[]> table = BenchDashTest.panel(text, 64);
      Check.check(table.size() >= 4 && table.get(0)[0].equals("C") && table.get(1)[1].equals("Busy Mod (BusyMod)") && table.get(1)[2].equals("mod"), "panel 64: mods first, by cost");
      Check.check(table.stream().anyMatch(x -> x[1].equals("Project Zomboid (game Lua)") && x[2].equals("game")), "panel 64: the game's Lua in its own row");
      Check.check(Math.abs(Double.parseDouble(table.get(1)[5]) - 2) < 0.05, "panel 64: BusyMod's calls per frame");
      List<String[]> ts = BenchDashTest.panel(text, 65);
      Check.check(ts.stream().anyMatch(x -> x[0].equals("S") && x[1].equals("Busy Mod (BusyMod)")) && ts.stream().anyMatch(x -> x[1].equals("game's Lua")), "panel 65: series per mod and the game's");
      String[] busySeries = ts.stream().filter(x -> x[1].equals("Busy Mod (BusyMod)")).findFirst().get();
      Check.check(busySeries[2].split(",").length == busySeries[3].split(",").length && busySeries[2].startsWith("0.25,0.75"), "panel 65: 0.5 s points");
      Check.check(BenchDashTest.panel(text, 66).stream().anyMatch(x -> x[0].equals("R") && x[2].startsWith("client/BusyMod/Busy.lua:work (")), "panel 66: file:function (line)");
      Check.check(BenchDashTest.panel(text, 67).stream().anyMatch(x -> x[1].equals("OnTick") && x[5].equals("Busy Mod (BusyMod)")), "panel 67: OnTick, BusyMod most");
      Check.check(BenchDashTest.panel(text, 68).size() > 1 && BenchDashTest.panel(text, 68).get(1)[5].equals("Busy Mod (BusyMod)"), "panel 68: the slowest frame names BusyMod");
      Check.check(BenchDashTest.value(BenchDashTest.panel(text, 69), "V", "samples") != null, "panel 69: the profiler's own numbers");
      Check.check(BenchDashTest.panel(BenchDash.text("20261021-999999\tspin\tA\t1"), 61).isEmpty(), "no profile: no Mods panels");

      // ---- the properties round trip (backslashes, tabs, blanks in names), counters off
      ModLuaProfile.Result fake = new ModLuaProfile.Result();
      fake.frames = 10;
      fake.countingOff = "SomeJavaMod patches the hooked methods";
      ModLuaProfile.Origin o = new ModLuaProfile.Origin();
      o.id = "mod:Odd";
      o.kind = "mod";
      o.name = " C:\\mods\\Odd\tname";
      o.ms = 0.5;
      fake.origins.add(o);
      fake.worst = o;
      fake.fnRows.add(new String[] {"mod:Odd", "client/Odd.lua", "fn", "3", "OnTick", "0.1", "0.2", "1"});
      File pf = new File(home.toFile(), "fake.properties");
      ModLuaProfile.writeProperties(fake, pf);
      Properties back = ModLuaDash.read(pf);
      Check.check(back.getProperty("worst_mod_name").equals(" C:\\mods\\Odd name"), "a name with backslashes and a leading blank reads back: [" + back.getProperty("worst_mod_name") + "]");
      Check.check("off".equals(back.getProperty("counting")) && ModLuaDash.rows(back, "fn.").get(0)[7].isEmpty(), "counters off: no call counts written");
      Check.check(ModLuaDash.rows(back, "origin.").get(0).length == 8, "an origin row has its eight fields");

      // ---- ModCompat: a Java mod patching a hooked method switches the counters off; the sampler keeps going
      foundHits().add(new ModCompat.Hit("SomeJavaMod", "zombie/Lua/Event", "trigger", "@Patch"));
      foundHits().add(new ModCompat.Hit("OtherMod", "zombie/iso/IsoChunk", "update", "@Patch"));
      Check.check(ModCompat.patchers("zombie/Lua/Event.trigger", "zombie/ui/UIElement.render").equals(List.of("SomeJavaMod")), "patchers: only the one on a hooked method");
      ModLuaProfile.start();
      Check.check(!ModLuaProfile.on && ModLuaProfile.active(), "a patched hook: counters off, sampler on");
      ModLuaProfile.Result rc = ModLuaProfile.stop();
      Check.check(rc.countingOff.contains("SomeJavaMod"), "the reason names the mod: " + rc.countingOff);
      foundHits().clear();

      workshopMods();
      System.out.println("ModLuaProfileTest OK");
   }

   /**
    * The Lua of the Workshop mods on this machine (skipped when there are none): each mod's client / shared files loaded
    * into a stubbed game API (any unknown global is a stub that can be called and indexed; Events.X.Add records the
    * handler), every recorded handler called once through the profiler's hook, its calls attributed to the mod.info id.
    */
   static void workshopMods() throws Exception {
      File ws = new File("/games/steamapps/workshop/content/108600");
      File[] items = ws.listFiles(File::isDirectory);
      if (items == null || items.length == 0) {
         System.out.println("mod lua profile: no Workshop mods on this machine, the real-mod check is skipped");
         return;
      }
      java.util.Arrays.sort(items);
      KahluaTable env = newEnv();
      thread = newThread(env);
      Check.check(run(thread, env, new File("stub.lua").getAbsolutePath(), ""
            + "local Stub\n"
            + "local mt = {}\n"
            + "Stub = setmetatable({}, mt)\n"
            + "PZOPT_BUDGET = 20000\n" // every stub use counts: a loop over the stub API ends in an error, never hangs
            + "local function spend() PZOPT_BUDGET = PZOPT_BUDGET - 1 if PZOPT_BUDGET < 0 then error('stub budget') end end\n"
            + "mt.__index = function(t, k) spend() return Stub end\n"
            + "mt.__newindex = function(t, k, v) end\n"
            + "mt.__call = function(...) spend() return Stub end\n"
            + "mt.__add = function() return 0 end mt.__sub = mt.__add mt.__mul = mt.__add mt.__div = mt.__add mt.__unm = mt.__add mt.__mod = mt.__add\n"
            + "mt.__concat = function() return '' end\n"
            + "mt.__lt = function() return false end mt.__le = mt.__lt mt.__len = function() return 0 end\n"
            + "PZOPT_HANDLERS = {}\n"
            + "Events = setmetatable({}, { __index = function(t, name) local e = { Add = function(fn) table.insert(PZOPT_HANDLERS, { name, fn }) end, Remove = function() end } rawset(t, name, e) return e end })\n"
            + "require = function() return Stub end\n"
            + "setmetatable(_G, { __index = function(t, k) return Stub end })\n"), "the stub API loads");
      ModLuaProfile.threadSource = () -> thread;
      int modsTried = 0, filesOk = 0, filesFailed = 0;
      Map<String, String> fileMod = new LinkedHashMap<>();
      for (File item : items) {
         File[] ms = new File(item, "mods").listFiles(File::isDirectory);
         for (File m : ms == null ? new File[0] : ms) {
            List<File> lua = new ArrayList<>();
            collect(new File(m, "common/media/lua/shared"), lua);
            collect(new File(m, "common/media/lua/client"), lua);
            File[] versions = m.listFiles(f -> f.isDirectory() && f.getName().startsWith("42"));
            if (versions != null && versions.length > 0) {
               java.util.Arrays.sort(versions);
               collect(new File(versions[versions.length - 1], "media/lua/shared"), lua);
               collect(new File(versions[versions.length - 1], "media/lua/client"), lua);
            }
            String id = modInfoId(m, versions);
            if (lua.isEmpty() || id == null || modsTried >= 8) {
               continue;
            }
            modsTried++;
            for (File f : lua.subList(0, Math.min(25, lua.size()))) {
               env.rawset("PZOPT_BUDGET", 20000.0);
               if (run(thread, env, f.getAbsolutePath(), Files.readString(f.toPath(), StandardCharsets.ISO_8859_1))) {
                  filesOk++;
               } else {
                  filesFailed++;
               }
               fileMod.put(f.getAbsolutePath(), id);
            }
         }
      }
      KahluaTable handlers = (KahluaTable)env.rawget("PZOPT_HANDLERS");
      int n = handlers == null ? 0 : handlers.len();
      ModLuaProfile.start();
      int called = 0;
      for (int i = 1; i <= n; i++) {
         KahluaTable h = (KahluaTable)handlers.rawget((double)i);
         Object fn = h.rawget(2.0);
         if (fn instanceof LuaClosure c) {
            ModLuaProfile.onFrame(System.nanoTime());
            env.rawset("PZOPT_BUDGET", 20000.0);
            ModLuaProfile.callEvent(caller, thread, c, new Object[0], String.valueOf(h.rawget(1.0)));
            called++;
         }
      }
      ModLuaProfile.Result r = ModLuaProfile.stop();
      int wrong = 0, checked = 0;
      for (int i = 1; i <= n; i++) {
         Object fn = ((KahluaTable)handlers.rawget((double)i)).rawget(2.0);
         if (fn instanceof LuaClosure c && fileMod.containsKey(c.prototype.filename)) {
            checked++;
            if (!("mod:" + fileMod.get(c.prototype.filename)).equals(LuaOrigin.of(c.prototype.filename))) {
               wrong++;
               System.out.println("mod lua profile: " + c.prototype.filename + " -> " + LuaOrigin.of(c.prototype.filename) + ", mod.info says " + fileMod.get(c.prototype.filename));
            }
         }
      }
      long counted = 0;
      for (ModLuaProfile.Origin o : r.origins) {
         if (o.kind.equals("mod")) {
            counted += o.calls;
         }
      }
      System.out.printf("mod lua profile: Workshop: %d mods, %d Lua files loaded (%d failed on the stub API), %d event handlers called through the hook, "
            + "%d calls counted to %d mods, %d of %d handlers' origins wrong%n", modsTried, filesOk, filesFailed, called, counted, r.origins.stream().filter(o -> o.kind.equals("mod")).count(), wrong, checked);
      Check.check(wrong == 0, "every Workshop handler's file maps to its mod.info id");
      Check.check(counted == called, "every handler call counted to a mod: " + counted + " of " + called);
   }

   static void collect(File dir, List<File> out) {
      File[] fs = dir.listFiles();
      if (fs == null) {
         return;
      }
      java.util.Arrays.sort(fs);
      for (File f : fs) {
         if (f.isDirectory()) {
            collect(f, out);
         } else if (f.getName().endsWith(".lua")) {
            out.add(f);
         }
      }
   }

   static String modInfoId(File mod, File[] versions) throws Exception {
      List<File> infos = new ArrayList<>();
      infos.add(new File(mod, "mod.info"));
      infos.add(new File(mod, "common/mod.info"));
      if (versions != null) {
         for (File v : versions) {
            infos.add(new File(v, "mod.info"));
         }
      }
      for (File f : infos) {
         if (f.isFile()) {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1)) {
               if (line.trim().startsWith("id=")) {
                  return line.trim().substring(3).trim();
               }
            }
         }
      }
      return null;
   }
}
