package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.vm.Coroutine;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * The benchmark's mod Lua profiler (2026-10-21): during a timed pass it
 * measures how much of each game frame every enabled mod's Lua costs, so a player can see which mod costs frames.
 *
 * <p><b>Time is sampled, calls are counted.</b> A daemon thread (pzopt-modlua-sampler, {@link #hz} = 2 kHz) reads the
 * main Lua thread's live call frames (KahluaThread.currentCoroutine, the same racy read-only walk as LuaProfile, but
 * without the game-thread Java stack trace: no safepoint, nothing runs on the game thread). Each sample is weighted by
 * the real time since the previous one and charged as <i>self</i> time to the innermost Lua function on the stack (with
 * the Java it is calling at that moment: a mod's {@code getCell():getGridSquare} is that mod's cost) and as
 * <i>total</i> time to every distinct function on the stack. A function's file gives its origin (LuaOrigin: the game's
 * media/lua, ours, a mod's folder by its mod.info id), so a mod that wraps a vanilla function is charged its own code
 * only, not the vanilla body it calls through. The hooks the game thread does run are counters: every Lua event handler
 * call (Event.trigger via LuaEventProfile.call) and every UI prerender / render / update (UIElement via UiProfile) is
 * counted per function and names the event for the sampler (a 64-entry label stack, two field writes a call). Hooks and
 * sampler are off outside a benchmark pass; {@link #on} is then the only thing the hooks read.
 *
 * <p>Frames: {@link #onFrame} once per game frame (Harness.onFrame through Benchmark.onTick) opens frame N; every
 * sample lands in the frame open when it was taken, so the per-frame series and the slowest frames line up with the
 * game thread's frames. Results: {@code <base>-luaprof.csv} (one row per game frame: its start, length, all Lua, each
 * origin's ms) and {@code <base>-luaprof.properties} (the summary, the mods, functions, events, slowest frames rows),
 * read back by ModLuaDash for the report's "Mods (Lua)" row; a few {@code lua_*} keys go into the pass's step file.
 *
 * <p>Why not the alternatives: GameThreadProfile's Java stack samples see only KahluaThread.luaMainloop and cost a
 * safepoint each (fine at 25 Hz, not at the rate a per-frame series needs); timing every call (nanoTime pairs, or Lua
 * wrappers like the harness's installLuaProf) is inclusive (a mod wrapping ISInventoryPane.render would pay for the
 * whole vanilla render), costs ~40 ns a call on every OnZombieUpdate, misses Lua entered elsewhere (hooks, timed actions,
 * callbacks) and a Lua wrapper changes what the mod sees (return values past the third, upvalue identity). Measured in
 * tests/pzopt/ModLuaProfileTest: sampled self time within 1 % of nanoTime truth, the hook +4-6 ns a call when on, 0
 * when off, the sampler 1-1.5 % of one core and no measurable slowdown of running Lua.
 *
 * <p>The passes run the player's mods: each pass is a fresh process whose boot loads the main menu's mod list
 * (ZomboidFileSystem.loadMods("default")), so the enabled mods' Lua is loaded and their handlers run in the pass's fresh
 * sandbox world (no ResetLua). Map mods are not applied (the pass never fills ActiveMods "currentGame") and cost that
 * depends on the player's own save is not seen.
 *
 * <p>Never changes what Lua does: the hooked handler call is the same {@code protectedCallVoid} with the same
 * arguments, its exceptions pass through, nothing is wrapped in Lua. A Java mod that patches one of the hooked methods
 * (ModCompat) switches the counters off for the pass; the sampler hooks nothing and keeps running.
 */
public final class ModLuaProfile {
   /** The game-thread hooks count and label (read by LuaEventProfile.call and UiProfile on every Lua call). */
   public static volatile boolean on;
   /** The sampler records (a pass is being profiled). */
   static volatile boolean recording;
   static int hz = 2000;
   /** The Lua thread whose frames are sampled (tests point it at their own). */
   static Supplier<KahluaThread> threadSource = () -> zombie.Lua.LuaManager.thread;
   /** A mod id's display name (tests replace it: ChooseGameInfo needs the game). */
   static Function<String, String> modName = ModLuaProfile::gameModName;

   static final String UI = "UI";
   static final String CALL = "Lua call";
   static final String UI_PRERENDER = "UI prerender", UI_RENDER = "UI render", UI_UPDATE = "UI update", UI_OTHER = "UI other";
   private static final String[] UI_KIND_KEY = {"prerender", "render", "update"};

   // ---- the label stack (game thread writes, the sampler reads racily)
   private static final String[] labels = new String[64];
   private static volatile int depth;
   private static volatile Thread game;

   // ---- frames (game thread)
   private static long[] frameStart = new long[16384];
   private static volatile int frame;
   private static long startNs, stopNs;

   // ---- counts (game thread)
   private static final IdentityHashMap<Prototype, Calls> calls = new IdentityHashMap<>();
   private static final IdentityHashMap<Object, UiFns> uiFns = new IdentityHashMap<>();
   private static long hookCalls;
   private static String countingOff = "";

   // ---- samples (sampler thread; the game thread reads them after the join)
   private static Thread sampler;
   private static final IdentityHashMap<Prototype, Fn> fns = new IdentityHashMap<>();
   private static final HashMap<String, Integer> originIdx = new HashMap<>();
   private static final ArrayList<String> origins = new ArrayList<>();
   private static final HashMap<String, long[]> labelOrigin = new HashMap<>();
   private static long[] originSelf = new long[16];
   private static int recN;
   private static int[] recFrame = new int[65536], recOrigin = new int[65536], recNs = new int[65536];
   private static long samples, luaSamples, sampledNs, luaNs, samplerCpuNs;

   private static Result last;

   private ModLuaProfile() {
   }

   // ------------------------------------------------------------------ lifecycle (game thread)

   /** Route start: clears everything, starts the sampler, switches the hooks on unless a Java mod patches them. */
   static synchronized void start() {
      try { // never throws: Benchmark.onRouteStart calls this on the route's first frame
         startInner();
      } catch (Throwable t) {
         on = false;
         recording = false;
         Log.warn("mod lua profile: could not start: " + t);
      }
   }

   private static void startInner() {
      if (recording) {
         stop();
      }
      game = Thread.currentThread();
      depth = 0;
      calls.clear();
      uiFns.clear();
      hookCalls = 0;
      fns.clear();
      originIdx.clear();
      origins.clear();
      labelOrigin.clear();
      Arrays.fill(originSelf, 0);
      for (String o : new String[] {"game", "pzopt", "other"}) {
         origin(o);
      }
      recN = 0;
      samples = luaSamples = sampledNs = luaNs = samplerCpuNs = 0;
      last = null;
      startNs = System.nanoTime();
      stopNs = 0;
      frameStart[0] = startNs;
      frame = 0;
      List<String> patchers = List.of();
      try {
         patchers = ModCompat.patchers("zombie/Lua/Event.trigger", "zombie/ui/UIElement.render", "zombie/ui/UIElement.update");
      } catch (Throwable t) {
         // no scan: count
      }
      countingOff = patchers.isEmpty() ? "" : String.join(", ", patchers) + " patches the hooked methods";
      recording = true;
      on = countingOff.isEmpty();
      int gen = ++generation;
      sampler = new Thread(() -> sampleLoop(gen), "pzopt-modlua-sampler");
      sampler.setDaemon(true);
      sampler.start();
      Log.info("mod lua profile: recording at " + hz + " Hz" + (on ? "" : "; calls not counted (" + countingOff + ")"));
   }

   /** Route end: the hooks off, the sampler joined, the results computed (kept for {@link #write} / {@link #summary}). */
   static synchronized Result stop() {
      if (!recording) {
         return last;
      }
      on = false;
      recording = false;
      stopNs = System.nanoTime();
      Thread s = sampler;
      sampler = null;
      if (s != null) {
         LockSupport.unpark(s);
         try {
            s.join(500);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
      try { // never throws: Benchmark.onRouteEnd calls this before its own error handling
         last = compute(calibrateHookNs());
      } catch (Throwable t) {
         Log.warn("mod lua profile: could not compute the results: " + t);
         last = null;
      }
      return last;
   }

   static boolean active() {
      return recording;
   }

   /** Harness.onFrame (via Benchmark.onTick), game thread: the next frame starts. */
   static void onFrame(long nowNs) {
      if (!recording || Thread.currentThread() != game) {
         return;
      }
      int f = frame + 1;
      if (f >= frameStart.length) {
         frameStart = Arrays.copyOf(frameStart, frameStart.length * 2);
      }
      frameStart[f] = nowNs;
      frame = f;
      if (depth != 0) { // a label left by a call that threw past its exit hook: no Lua runs at the frame hook
         try {
            KahluaThread t = threadSource.get();
            Coroutine c = t == null ? null : t.currentCoroutine;
            if (c == null || c.getCallframeTop() == 0) {
               depth = 0;
            }
         } catch (Throwable ignored) {
         }
      }
   }

   // ------------------------------------------------------------------ hooks (game thread, only while on)

   /** LuaEventProfile.call while {@link #on}: the same handler call, counted, with its event named for the sampler. */
   public static void callEvent(LuaCaller caller, KahluaThread thread, LuaClosure closure, Object[] params, String event) {
      if (Thread.currentThread() != game) {
         caller.protectedCallVoid(thread, closure, params);
         return;
      }
      int d = depth;
      push(d, event);
      count(closure.prototype, event);
      try {
         caller.protectedCallVoid(thread, closure, params);
      } finally {
         depth = d;
      }
   }

   /** UiProfile.callBegin while {@link #on}: a UI element's Lua call follows. */
   public static void uiEnter() {
      if (Thread.currentThread() == game) {
         push(depth, UI);
      }
   }

   /** UiProfile.callEnd while {@link #on}: the UI call returned; kind 0 prerender, 1 render, 2 update. */
   public static void uiExit(Object element, KahluaTable table, int kind) {
      if (Thread.currentThread() != game) {
         return;
      }
      int d = depth;
      if (d > 0 && (d > labels.length || labels[d - 1] == UI)) {
         depth = d - 1;
      }
      if (table == null || kind < 0 || kind > 2) {
         return;
      }
      UiFns u = uiFns.get(element);
      int f = frame;
      if (u == null || f - u.stamp[kind] > 300 || u.stamp[kind] < 0) { // looked up again now and then: a mod may swap it
         if (u == null) {
            if (uiFns.size() > 20000) {
               uiFns.clear();
            }
            u = new UiFns();
            uiFns.put(element, u);
         }
         Object fn = null;
         try {
            KahluaThread t = threadSource.get();
            fn = t == null ? table.rawget(UI_KIND_KEY[kind]) : t.tableget(table, UI_KIND_KEY[kind]);
         } catch (Throwable ignored) {
         }
         u.fn[kind] = fn instanceof LuaClosure c ? c.prototype : null;
         u.stamp[kind] = f;
      }
      Prototype p = u.fn[kind];
      if (p != null) {
         count(p, uiLabel(p.name));
      }
   }

   private static void push(int d, String label) {
      if (d < labels.length) {
         labels[d] = label;
      }
      depth = d + 1;
   }

   private static void count(Prototype p, String label) {
      if (p == null) {
         return;
      }
      hookCalls++;
      Calls c = calls.get(p);
      if (c == null) {
         c = new Calls();
         calls.put(p, c);
      }
      c.add(label);
   }

   /** The UI label of a UI call's entry function (the sampler and the counters use the same rule). */
   static String uiLabel(String name) {
      if ("prerender".equals(name)) {
         return UI_PRERENDER;
      }
      if ("render".equals(name)) {
         return UI_RENDER;
      }
      if ("update".equals(name)) {
         return UI_UPDATE;
      }
      return UI_OTHER;
   }

   /** One hook's game-thread cost (thread check, push, count, pop), measured after the pass on a scratch counter. */
   private static double calibrateHookNs() {
      Calls scratch = new Calls();
      IdentityHashMap<Prototype, Calls> m = new IdentityHashMap<>();
      Prototype p = new Prototype();
      m.put(p, scratch);
      String ev = "OnTick";
      long best = Long.MAX_VALUE;
      int hits = 0;
      for (int round = 0; round < 5; round++) {
         long t0 = System.nanoTime();
         for (int i = 0; i < 20000; i++) {
            if (Thread.currentThread() == game) {
               hits++;
            }
            int d = depth;
            if (d < labels.length) {
               labels[d] = ev;
            }
            depth = d + 1;
            m.get(p).add(ev);
            depth = d;
         }
         best = Math.min(best, System.nanoTime() - t0);
      }
      calibrationSink = hits;
      depth = 0;
      return best / 20000.0;
   }

   static volatile int calibrationSink;

   // ------------------------------------------------------------------ the sampler

   private static volatile int generation;

   private static void sampleLoop(int gen) {
      long period = 1_000_000_000L / Math.max(50, hz);
      long lastNs = System.nanoTime();
      long next = lastNs + period;
      Prototype[] seen = new Prototype[256];
      while (recording && generation == gen) {
         long wait = next - System.nanoTime();
         if (wait > 0) {
            LockSupport.parkNanos(wait);
         }
         if (!recording || generation != gen) {
            break;
         }
         long now = System.nanoTime();
         long w = Math.min(now - lastNs, 4 * period);
         lastNs = now;
         next += period;
         if (now - next > 8 * period) {
            next = now + period; // fell far behind (a stall): no burst to catch up
         }
         try {
            sample(w, seen);
         } catch (Throwable t) {
            // a torn read of the live frames: this sample is dropped
         }
      }
      try {
         samplerCpuNs = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
      } catch (Throwable ignored) {
      }
   }

   /** One sample of the Lua stack, weighted {@code w} ns (package-private: the tests feed it directly). */
   static void sample(long w, Prototype[] seen) {
      samples++;
      sampledNs += w;
      int f = frame;
      KahluaThread t = threadSource.get();
      Coroutine c = t == null ? null : t.currentCoroutine;
      if (c == null) {
         return;
      }
      LuaCallFrame[] st = c.getCallframeStack();
      int top = Math.min(c.getCallframeTop(), st.length);
      Prototype self = null, entry = null, lib = null;
      int nSeen = 0;
      for (int i = top - 1; i >= 0; i--) {
         LuaCallFrame fr = st[i];
         LuaClosure cl = fr == null ? null : fr.closure;
         Prototype p = cl == null ? null : cl.prototype;
         if (p == null) {
            continue; // a Java function's frame (its time is its Lua caller's) or one being set up
         }
         if (self == null) {
            if (p.filename == null || p.filename.isEmpty()) {
               // Kahlua's own Lua-coded library (stdlib's math.max, table.sort's partition_comp, gmatch): no file of anyone,
               // so its self time is its caller's, as a Java function's is (it stays in the total column)
               if (lib == null) {
                  lib = p;
               }
            } else {
               self = p;
            }
         }
         entry = p;
         boolean dup = false;
         for (int k = 0; k < nSeen; k++) {
            if (seen[k] == p) {
               dup = true;
               break;
            }
         }
         if (!dup && nSeen < seen.length) {
            seen[nSeen++] = p;
         }
      }
      if (self == null) {
         self = lib;
      }
      if (self == null) {
         return;
      }
      int d = depth;
      String label = d > 0 ? labels[Math.min(d, labels.length) - 1] : null;
      if (label == null) {
         label = CALL;
      } else if (label == UI) {
         label = uiLabel(entry.name);
      }
      record(f, self, seen, nSeen, label, w);
   }

   private static void record(int f, Prototype self, Prototype[] stack, int n, String label, long w) {
      luaSamples++;
      luaNs += w;
      Fn sf = fn(self);
      sf.row(label)[0] += w;
      for (int k = 0; k < n; k++) {
         fn(stack[k]).row(label)[1] += w;
      }
      int o = sf.origin;
      if (o >= originSelf.length) {
         originSelf = Arrays.copyOf(originSelf, Math.max(o + 1, originSelf.length * 2));
      }
      originSelf[o] += w;
      long[] lo = labelOrigin.get(label);
      if (lo == null || lo.length <= o) {
         long[] grown = new long[Math.max(o + 1, 16)];
         if (lo != null) {
            System.arraycopy(lo, 0, grown, 0, lo.length);
         }
         lo = grown;
         labelOrigin.put(label, lo);
      }
      lo[o] += w;
      if (recN == recFrame.length) {
         int len = recN * 2;
         recFrame = Arrays.copyOf(recFrame, len);
         recOrigin = Arrays.copyOf(recOrigin, len);
         recNs = Arrays.copyOf(recNs, len);
      }
      recFrame[recN] = f;
      recOrigin[recN] = o;
      recNs[recN] = (int)Math.min(Integer.MAX_VALUE, w);
      recN++;
   }

   private static Fn fn(Prototype p) {
      Fn f = fns.get(p);
      if (f == null) {
         f = new Fn(origin(LuaOrigin.of(p.filename)), displayFile(p.filename), p.name, p.lines != null && p.lines.length > 0 ? p.lines[0] : 0);
         fns.put(p, f);
      }
      return f;
   }

   private static int origin(String id) {
      Integer i = originIdx.get(id);
      if (i == null) {
         i = origins.size();
         origins.add(id);
         originIdx.put(id, i);
      }
      return i;
   }

   /** A Lua file as the report shows it: the part after media/lua/ (client/MyMod/UI.lua), else its last two parts. */
   static String displayFile(String path) {
      if (path == null || path.isEmpty()) {
         return "?";
      }
      String p = path.replace('\\', '/');
      int i = p.lastIndexOf("/media/lua/");
      if (i >= 0) {
         return p.substring(i + "/media/lua/".length());
      }
      int s = p.lastIndexOf('/');
      int s2 = s > 0 ? p.lastIndexOf('/', s - 1) : -1;
      return s2 >= 0 ? p.substring(s2 + 1) : p;
   }

   static final class Fn {
      final int origin;
      final String file, name;
      final int line;
      final HashMap<String, long[]> by = new HashMap<>(4); // label -> {self ns, total ns}
      private String lastLabel;
      private long[] lastRow;

      Fn(int origin, String file, String name, int line) {
         this.origin = origin;
         this.file = file;
         this.name = name;
         this.line = line;
      }

      long[] row(String label) {
         if (label == lastLabel) {
            return lastRow;
         }
         long[] r = by.computeIfAbsent(label, k -> new long[2]);
         lastLabel = label;
         lastRow = r;
         return r;
      }
   }

   static final class Calls {
      private String l1;
      private long n1;
      private HashMap<String, long[]> more;

      void add(String label) {
         if (label == l1 || l1 == null) {
            l1 = label;
            n1++;
            return;
         }
         if (more == null) {
            more = new HashMap<>(4);
         }
         more.computeIfAbsent(label, k -> new long[1])[0]++;
      }

      Map<String, Long> byLabel() {
         HashMap<String, Long> m = new HashMap<>();
         if (l1 != null) {
            m.merge(l1, n1, Long::sum);
         }
         if (more != null) {
            for (Map.Entry<String, long[]> e : more.entrySet()) {
               m.merge(e.getKey(), e.getValue()[0], Long::sum);
            }
         }
         return m;
      }
   }

   private static final class UiFns {
      final Prototype[] fn = new Prototype[3];
      final int[] stamp = {-1, -1, -1};
   }

   // ------------------------------------------------------------------ results (game thread, after the join)

   /** What a pass measured, ready to write. */
   static final class Result {
      int frames;
      double seconds, sampleHz, intervalUs, hookNs, overheadUs, samplerCpuPct, p99FrameMs;
      long samples, luaSamples, hookCalls;
      int spikeFrames;
      String countingOff = "";
      double luaMs, luaPct, gameMs, mods, modsPct;
      final List<Origin> origins = new ArrayList<>();
      final List<String[]> fnRows = new ArrayList<>(); // origin, file, function, line, event, self ms, total ms, calls/frame
      final List<String[]> eventRows = new ArrayList<>(); // event, lua ms, mods ms, calls/frame, top origin, top ms
      final List<String[]> spikeRows = new ArrayList<>(); // t s (the frame's end: where the frame-time chart shows it), frame ms, lua ms, mods ms, top origin, top ms
      double[] frameT, frameMs, frameLua;
      float[][] perOrigin; // [origin column][frame] ms
      List<String> columns = new ArrayList<>(); // the per-frame columns' origins
      Origin worst;
   }

   static final class Origin {
      String id, kind, name;
      long selfNs, calls;
      double ms, pct, callsPerFrame, p99Ms;
      int files;
   }

   static String kindOf(String id) {
      return id.startsWith("mod:") ? "mod" : id.equals("game") ? "game" : id.equals("pzopt") ? "PZ Optimization" : "unknown";
   }

   private static Result compute(double hookNs) {
      Result r = new Result();
      int nf = frame + 1;
      r.frames = nf;
      double[] dur = new double[nf];
      double sum = 0;
      for (int i = 0; i < nf; i++) {
         long end = i + 1 < nf ? frameStart[i + 1] : stopNs;
         dur[i] = Math.max(0, end - frameStart[i]) / 1e6;
         sum += dur[i];
      }
      double wallS = (stopNs - startNs) / 1e9;
      r.seconds = wallS;
      r.samples = samples;
      r.luaSamples = luaSamples;
      r.sampleHz = wallS > 0 ? samples / wallS : 0;
      r.intervalUs = samples > 0 ? sampledNs / 1e3 / samples : 0;
      r.hookCalls = hookCalls;
      r.hookNs = hookNs;
      r.overheadUs = nf > 0 ? hookCalls * hookNs / 1e3 / nf : 0;
      r.samplerCpuPct = wallS > 0 && samplerCpuNs > 0 ? 100.0 * samplerCpuNs / 1e9 / wallS : Double.NaN;
      r.countingOff = countingOff;
      // the call counts, per prototype -> origin, per (prototype, label)
      HashMap<String, Long> originCalls = new HashMap<>();
      HashMap<String, Long> labelCalls = new HashMap<>();
      IdentityHashMap<Prototype, Map<String, Long>> protoCalls = new IdentityHashMap<>();
      for (Map.Entry<Prototype, Calls> e : calls.entrySet()) {
         Map<String, Long> m = e.getValue().byLabel();
         protoCalls.put(e.getKey(), m);
         String o = origins.get(origin(LuaOrigin.of(e.getKey().filename)));
         for (Map.Entry<String, Long> c : m.entrySet()) {
            originCalls.merge(o, c.getValue(), Long::sum);
            labelCalls.merge(c.getKey(), c.getValue(), Long::sum);
         }
      }
      int no = origins.size();
      // per-frame per-origin ms
      float[][] per = new float[no][];
      float[] none = new float[nf];
      for (int o = 0; o < no; o++) {
         per[o] = o < originSelf.length && originSelf[o] > 0 ? new float[nf] : none; // (no sample: never written)
      }
      double[] frameLua = new double[nf];
      for (int i = 0; i < recN; i++) {
         int f = Math.min(nf - 1, Math.max(0, recFrame[i]));
         double ms = recNs[i] / 1e6;
         per[recOrigin[i]][f] += (float)ms;
         frameLua[f] += ms;
      }
      // the slowest 1 % of frames
      double[] sorted = Arrays.copyOf(dur, nf);
      Arrays.sort(sorted);
      r.p99FrameMs = nf > 0 ? sorted[Math.min(nf - 1, (int)Math.floor(0.99 * (nf - 1)))] : Double.NaN;
      int spikes = 0;
      for (int i = 0; i < nf; i++) {
         if (dur[i] >= r.p99FrameMs) {
            spikes++;
         }
      }
      r.spikeFrames = spikes;
      // files per origin (sampled or counted)
      HashMap<String, java.util.HashSet<String>> files = new HashMap<>();
      for (Fn f : fns.values()) {
         files.computeIfAbsent(origins.get(f.origin), k -> new java.util.HashSet<>()).add(f.file);
      }
      for (Prototype p : protoCalls.keySet()) {
         files.computeIfAbsent(LuaOrigin.of(p.filename), k -> new java.util.HashSet<>()).add(displayFile(p.filename));
      }
      long modsNs = 0, gameNs = 0;
      for (int o = 0; o < no; o++) {
         String id = origins.get(o);
         long self = o < originSelf.length ? originSelf[o] : 0;
         long nCalls = originCalls.getOrDefault(id, 0L);
         if (self == 0 && nCalls == 0) {
            continue;
         }
         Origin x = new Origin();
         x.id = id;
         x.kind = kindOf(id);
         x.name = displayName(id);
         x.selfNs = self;
         x.calls = nCalls;
         x.ms = nf > 0 ? self / 1e6 / nf : 0;
         x.pct = sum > 0 ? 100.0 * self / 1e6 / sum : 0;
         x.callsPerFrame = nf > 0 ? (double)nCalls / nf : 0;
         double sp = 0;
         for (int i = 0; i < nf; i++) {
            if (dur[i] >= r.p99FrameMs) {
               sp += per[o][i];
            }
         }
         x.p99Ms = spikes > 0 ? sp / spikes : 0;
         java.util.HashSet<String> fs = files.get(id);
         x.files = fs == null ? 0 : fs.size();
         r.origins.add(x);
         if (id.startsWith("mod:")) {
            modsNs += self;
            if (r.worst == null || self > r.worst.selfNs) {
               r.worst = x;
            }
         } else if (id.equals("game")) {
            gameNs += self;
         }
      }
      // mods first by cost, then the game's, ours and the unknown
      r.origins.sort((a, b) -> {
         int ka = a.kind.equals("mod") ? 0 : 1, kb = b.kind.equals("mod") ? 0 : 1;
         if (ka != kb) {
            return Integer.compare(ka, kb);
         }
         int c = Long.compare(b.selfNs, a.selfNs);
         return c != 0 ? c : Long.compare(b.calls, a.calls);
      });
      r.luaMs = nf > 0 ? luaNs / 1e6 / nf : 0;
      r.luaPct = sum > 0 ? 100.0 * luaNs / 1e6 / sum : 0;
      r.gameMs = nf > 0 ? gameNs / 1e6 / nf : 0;
      r.mods = nf > 0 ? modsNs / 1e6 / nf : 0;
      r.modsPct = sum > 0 ? 100.0 * modsNs / 1e6 / sum : 0;
      // functions: every (function, event) sampled or counted
      List<Object[]> fr = new ArrayList<>();
      for (Map.Entry<Prototype, Fn> e : fns.entrySet()) {
         Fn f = e.getValue();
         Map<String, Long> cm = protoCalls.getOrDefault(e.getKey(), Map.of());
         for (Map.Entry<String, long[]> le : f.by.entrySet()) {
            long n = cm.getOrDefault(le.getKey(), 0L);
            fr.add(new Object[] {origins.get(f.origin), f.file, fnName(f.name, f.line), f.line, le.getKey(), le.getValue()[0], le.getValue()[1], n});
         }
      }
      for (Map.Entry<Prototype, Map<String, Long>> e : protoCalls.entrySet()) {
         Prototype p = e.getKey();
         for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
            Fn sampled = fns.get(p);
            if (sampled != null && sampled.by.containsKey(c.getKey())) {
               continue;
            }
            int line = p.lines != null && p.lines.length > 0 ? p.lines[0] : 0;
            fr.add(new Object[] {LuaOrigin.of(p.filename), displayFile(p.filename), fnName(p.name, line), line, c.getKey(), 0L, 0L, c.getValue()});
         }
      }
      fr.sort((a, b) -> {
         int c = Long.compare((Long)b[5], (Long)a[5]);
         if (c != 0) {
            return c;
         }
         c = Long.compare((Long)b[6], (Long)a[6]);
         return c != 0 ? c : Long.compare((Long)b[7], (Long)a[7]);
      });
      for (int i = 0; i < Math.min(300, fr.size()); i++) {
         Object[] x = fr.get(i);
         r.fnRows.add(new String[] {(String)x[0], (String)x[1], (String)x[2], Integer.toString((Integer)x[3]), (String)x[4], f3((Long)x[5] / 1e6 / nf),
               f3((Long)x[6] / 1e6 / nf), f3((double)(Long)x[7] / nf)});
      }
      // events
      java.util.HashSet<String> ls = new java.util.HashSet<>(labelOrigin.keySet());
      ls.addAll(labelCalls.keySet());
      List<Object[]> er = new ArrayList<>();
      for (String l : ls) {
         long[] lo = labelOrigin.getOrDefault(l, new long[0]);
         long all = 0, mods = 0, top = 0;
         int topO = -1;
         for (int o = 0; o < Math.min(lo.length, origins.size()); o++) {
            all += lo[o];
            if (origins.get(o).startsWith("mod:")) {
               mods += lo[o];
            }
            if (lo[o] > top) {
               top = lo[o];
               topO = o;
            }
         }
         er.add(new Object[] {l, all, mods, labelCalls.getOrDefault(l, 0L), topO < 0 ? "" : origins.get(topO), top});
      }
      er.sort((a, b) -> {
         int c = Long.compare((Long)b[1], (Long)a[1]);
         return c != 0 ? c : Long.compare((Long)b[3], (Long)a[3]);
      });
      for (Object[] x : er) {
         r.eventRows.add(new String[] {(String)x[0], f3((Long)x[1] / 1e6 / nf), f3((Long)x[2] / 1e6 / nf), f3((double)(Long)x[3] / nf), (String)x[4], f3((Long)x[5] / 1e6 / nf)});
      }
      // the slowest frames and whose Lua ran in them
      Integer[] idx = new Integer[nf];
      for (int i = 0; i < nf; i++) {
         idx[i] = i;
      }
      Arrays.sort(idx, (a, b) -> Double.compare(dur[b], dur[a]));
      for (int k = 0; k < Math.min(12, nf); k++) {
         int i = idx[k];
         double m = 0, top = 0;
         int topO = -1;
         for (int o = 0; o < no; o++) {
            if (origins.get(o).startsWith("mod:")) {
               m += per[o][i];
            }
            if (per[o][i] > top) {
               top = per[o][i];
               topO = o;
            }
         }
         r.spikeRows.add(new String[] {f3((frameStart[i] - startNs) / 1e9 + dur[i] / 1e3), f3(dur[i]), f3(frameLua[i]), f3(m), topO < 0 ? "" : origins.get(topO), f3(top)});
      }
      // the per-frame columns: origins with samples, by cost; past 24 the rest of the mods in one column
      List<Integer> withSamples = new ArrayList<>();
      for (int o = 0; o < no; o++) {
         if (o < originSelf.length && originSelf[o] > 0) {
            withSamples.add(o);
         }
      }
      withSamples.sort((a, b) -> Long.compare(originSelf[b], originSelf[a]));
      List<float[]> cols = new ArrayList<>();
      float[] rest = null;
      for (int k = 0; k < withSamples.size(); k++) {
         int o = withSamples.get(k);
         if (k < 24) {
            r.columns.add(origins.get(o));
            cols.add(per[o]);
         } else {
            if (rest == null) {
               rest = new float[nf];
            }
            for (int i = 0; i < nf; i++) {
               rest[i] += per[o][i];
            }
         }
      }
      if (rest != null) {
         r.columns.add("mod:(other mods)");
         cols.add(rest);
      }
      r.perOrigin = cols.toArray(new float[0][]);
      r.frameT = new double[nf];
      r.frameMs = dur;
      r.frameLua = frameLua;
      for (int i = 0; i < nf; i++) {
         r.frameT[i] = (frameStart[i] - startNs) / 1e9;
      }
      return r;
   }

   static String fnName(String name, int line) {
      return name == null || name.isEmpty() ? "(anonymous)" : name;
   }

   static String displayName(String id) {
      if (id.startsWith("mod:")) {
         String m = id.substring(4);
         String n = null;
         try {
            n = modName.apply(m);
         } catch (Throwable ignored) {
         }
         return n == null || n.isBlank() || n.equals(m) ? m : n + " (" + m + ")";
      }
      return switch (id) {
         case "game" -> "Project Zomboid (game Lua)";
         case "pzopt" -> "PZ Optimization";
         default -> "unknown origin";
      };
   }

   private static String gameModName(String id) {
      zombie.gameStates.ChooseGameInfo.Mod m = zombie.gameStates.ChooseGameInfo.getModDetails(id);
      return m == null ? null : m.getName();
   }

   // ------------------------------------------------------------------ files

   /** The step's {@code lua_*} keys (empty without a profiled pass). */
   static Map<String, String> summary() {
      LinkedHashMap<String, String> m = new LinkedHashMap<>();
      Result r = last;
      if (r == null) {
         return m;
      }
      try {
         summaryInto(m, r);
      } catch (Throwable t) {
         m.clear();
      }
      return m;
   }

   private static void summaryInto(Map<String, String> m, Result r) {
      m.put("lua_ms", f4(r.luaMs));
      m.put("lua_mods_ms", f4(r.mods));
      m.put("lua_mods_pct", f4(r.modsPct));
      m.put("lua_worst_mod", r.worst == null ? "" : r.worst.id.substring(4));
      m.put("lua_worst_mod_ms", r.worst == null ? "" : f4(r.worst.ms));
      m.put("lua_samples", Long.toString(r.samples));
      m.put("lua_overhead_us", f4(r.overheadUs));
   }

   /** {@code <base>-luaprof.csv} and {@code <base>-luaprof.properties} into the pass's run folder. Never throws. */
   static void write(File dir, String base) {
      Result r = last;
      if (r == null) {
         return;
      }
      try {
         writeCsv(r, new File(dir, base + "-luaprof.csv"));
         writeProperties(r, new File(dir, base + "-luaprof.properties"));
      } catch (Throwable t) {
         Log.warn("mod lua profile: could not write the results: " + t);
      }
   }

   static void writeCsv(Result r, File f) throws IOException {
      try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
         StringBuilder b = new StringBuilder("frame,t_s,frame_ms,lua_ms");
         for (String c : r.columns) {
            b.append(',').append(c.replace(',', '_'));
         }
         w.write(b.append('\n').toString());
         for (int i = 0; i < r.frames; i++) {
            b.setLength(0);
            b.append(i).append(',').append(String.format(Locale.ROOT, "%.4f", r.frameT[i])).append(',').append(f3(r.frameMs[i])).append(',').append(f3(r.frameLua[i]));
            for (float[] col : r.perOrigin) {
               b.append(',').append(f3(col[i]));
            }
            w.write(b.append('\n').toString());
         }
      }
   }

   static void writeProperties(Result r, File f) throws IOException {
      StringBuilder b = new StringBuilder("# pzopt mod Lua profile of one benchmark pass (pzopt.ModLuaProfile); ms are per game frame\n");
      prop(b, "version", "1");
      prop(b, "frames", Integer.toString(r.frames));
      prop(b, "seconds", f4(r.seconds));
      prop(b, "sample_hz", f4(r.sampleHz));
      prop(b, "sample_interval_us", f4(r.intervalUs));
      prop(b, "samples", Long.toString(r.samples));
      prop(b, "samples_in_lua", Long.toString(r.luaSamples));
      prop(b, "lua_ms", f4(r.luaMs));
      prop(b, "lua_pct", f4(r.luaPct));
      prop(b, "game_lua_ms", f4(r.gameMs));
      prop(b, "mods_ms", f4(r.mods));
      prop(b, "mods_pct", f4(r.modsPct));
      prop(b, "worst_mod", r.worst == null ? "" : r.worst.id);
      prop(b, "worst_mod_name", r.worst == null ? "" : r.worst.name.replace('\t', ' '));
      prop(b, "worst_mod_ms", r.worst == null ? "" : f4(r.worst.ms));
      prop(b, "worst_mod_pct", r.worst == null ? "" : f4(r.worst.pct));
      prop(b, "p99_frame_ms", f4(r.p99FrameMs));
      prop(b, "spike_frames", Integer.toString(r.spikeFrames));
      prop(b, "counting", r.countingOff.isEmpty() ? "on" : "off");
      prop(b, "counting_off_reason", r.countingOff.replace('\t', ' '));
      prop(b, "hook_calls", Long.toString(r.hookCalls));
      prop(b, "hook_ns", f4(r.hookNs));
      prop(b, "overhead_us", f4(r.overheadUs));
      prop(b, "sampler_cpu_pct", f4(r.samplerCpuPct));
      int i = 0;
      for (Origin o : r.origins) {
         prop(b, "origin." + i++, row(o.id, o.kind, o.name, f4(o.ms), f4(o.pct), r.countingOff.isEmpty() ? f4(o.callsPerFrame) : "", f4(o.p99Ms), Integer.toString(o.files)));
      }
      i = 0;
      for (String[] x : r.fnRows) {
         if (!r.countingOff.isEmpty()) {
            x[7] = "";
         }
         prop(b, "fn." + i++, row(x));
      }
      i = 0;
      for (String[] x : r.eventRows) {
         if (!r.countingOff.isEmpty()) {
            x[3] = "";
         }
         prop(b, "event." + i++, row(x));
      }
      i = 0;
      for (String[] x : r.spikeRows) {
         prop(b, "spike." + i++, row(x));
      }
      Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
   }

   private static String row(String... c) {
      StringBuilder b = new StringBuilder();
      for (int i = 0; i < c.length; i++) {
         b.append(i > 0 ? "\t" : "").append(c[i] == null ? "" : c[i].replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      return b.toString();
   }

   /** One key=value line that java.util.Properties reads back as written (backslashes and a leading blank escaped). */
   private static void prop(StringBuilder b, String k, String v) {
      String s = (v == null ? "" : v).replace("\\", "\\\\").replace("\n", " ").replace("\r", " ");
      if (s.startsWith(" ")) {
         s = "\\" + s;
      }
      b.append(k).append('=').append(s).append('\n');
   }

   static String f3(double v) {
      if (Double.isNaN(v) || Double.isInfinite(v)) {
         return "nan";
      }
      if (v == 0) {
         return "0";
      }
      return String.format(Locale.ROOT, "%.3f", v);
   }

   static String f4(double v) {
      if (Double.isNaN(v) || Double.isInfinite(v)) {
         return "nan";
      }
      return String.format(Locale.ROOT, "%.4f", v);
   }
}
