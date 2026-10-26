package pzopt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.Lua.Event;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * Lua event dispatch and the mod tick rate (2026-10-25, a Discord suggestion: OnTick fires once a frame, so a big mod
 * list's handlers barrage the game thread; run them at a fixed rate instead). Event.trigger hands its loop here when
 * {@link #ON}; docs/override-edits.md "Lua tick".
 *
 * <ul>
 * <li>{@code luaEventDispatchFast}: the stock loop without the per-call profiler key ({@code "Lua - " + name}, built for
 * every handler call even with the profiler off) and with the "is the handler still listed" test (an O(n)
 * {@code contains} after every call: O(n²) per event) answered in O(1) when the list still holds the handler at its
 * index (the common case; the stock test runs otherwise, so handlers that add or remove handlers mid-dispatch see stock
 * behaviour). Proven equal by LuaTickTest.</li>
 * <li>{@code luaTickHz} / {@code luaTickAdaptive}: a mod's handler of a capped event (OnTick, OnPlayerUpdate,
 * OnTickEvenPaused) runs every n-th frame instead of every frame. The game's and our handlers, cheap handlers
 * ({@code luaTickCheapUs}), handlers {@link LuaTickScan} finds input / UI / camera / movement in, the known list and the
 * player's per-mod choice stay every frame. n: the cap's frames per call (fps / Hz), or the adaptive budget's
 * max-min fair share (water-filling on each handler's measured cost: handlers under the level run every frame, the
 * others every cost/level frames), never more than luaTickMaxStaleFrames / luaTickMaxStaleMs for the adaptive part.
 * The frames a spaced-out handler runs on are spread by a projected-load ring (each placement takes the least loaded
 * frame near its ideal one, largest handlers first at a rebalance), so the load is flat instead of a spike every n-th
 * frame. Time compensation ({@link LuaTickTime}): such a handler reads the frame-time getters summed over the frames
 * since its last call.</li>
 * </ul>
 */
public final class LuaTick {
   private LuaTick() {
   }

   static final int AD_OFF = 0, AD_BUDGET = 1, AD_AIMD = 2;
   static final int ADAPT = switch (Config.LUA_TICK_ADAPTIVE) {
      case "budget", "on", "true" -> AD_BUDGET;
      case "aimd" -> AD_AIMD;
      default -> AD_OFF;
   };
   static final boolean FAST = Config.LUA_EVENT_DISPATCH_FAST;
   static final int HZ = Config.LUA_TICK_HZ;
   static final boolean SCHED = HZ > 0 || ADAPT != AD_OFF;
   static final boolean RECORD = Config.LUA_TICK_STATS;
   static final int ALT_MS = Config.DEV_LUA_TICK_ALTERNATE;
   static final boolean ALT_FAST = ALT_MS > 0 && Config.DEV_LUA_TICK_ALTERNATE_WHAT.equals("fast");
   static final boolean ALT_ALL = ALT_MS > 0 && Config.DEV_LUA_TICK_ALTERNATE_WHAT.equals("all"); // fast + schedule + skipEmpty
   static final boolean ALT_SCHED = ALT_MS > 0 && !ALT_FAST;
   static final int COMP = switch (Config.LUA_TICK_TIME_COMP) {
      case "off", "false", "none" -> 0;
      case "object" -> 2;
      default -> 1;
   };
   static final boolean OWN_TICKS = Config.LUA_TICK_TICKS.equals("own");
   static final boolean ERR = Config.LUA_ERROR_THROTTLE > 0;
   static final boolean SKIP_EMPTY = Config.LUA_EVENT_SKIP_EMPTY;
   static final int SPIKE_PCT = Config.LUA_TICK_SPIKE_DEFER_PCT;
   static final boolean INPUT_WAKE = Config.LUA_TICK_INPUT_WAKE;
   public static final boolean ON = Overrides.enabled() && (FAST || SCHED || RECORD || ALT_MS > 0 || ERR || SKIP_EMPTY);

   /** The call a handler gets: LuaEventProfile.call (the mod Lua profiler's hook). LuaTickTest swaps it. */
   interface Invoker {
      void call(LuaCaller caller, LuaClosure closure, Object[] params, String event);
   }

   static Invoker invoker = LuaEventProfile::call;

   static final int CLS_GAME = 0, CLS_PZOPT = 1, CLS_MOD = 2;
   static final int PIN_NONE = 0, PIN_ORIGIN = 1, PIN_USER = 2, PIN_KNOWN = 3, PIN_SCAN = 4;
   static final String[] PIN_NAMES = {"", "origin", "user", "known", "scan"};
   static final int WARM_CALLS = 8;
   static final int RING = 512;
   static final int REBALANCE_FRAMES = 240;

   /** One handler of one event slot. */
   static final class H {
      final LuaClosure c;
      final String origin;
      final int cls;
      int pin = -1; // -1 = not decided yet
      String pinWhy = "";
      boolean wake; // luaTickInputWake: an input poller, also run in every frame with an input change
      double ewmaNs; // per call
      long maxNs;
      int samples;
      long calls, runs, skips;
      long timedNs; // RECORD: total measured
      double ideal, due; // frame numbers
      long lastRunFrame = Long.MIN_VALUE / 2;
      double fpsAtRun = Double.NaN;
      double interval = 1.0;
      long stampFrame = Long.MIN_VALUE;
      boolean stampRun;
      double ownTicks;
      final long[] phNs = new long[2], phRuns = new long[2]; // RECORD: measured time and runs by A/B phase (0 off, 1 on)

      H(LuaClosure c) {
         this.c = c;
         this.origin = c.prototype == null ? "other" : LuaOrigin.of(c.prototype.filename);
         this.cls = origin.equals("game") ? CLS_GAME : origin.equals("pzopt") ? CLS_PZOPT : CLS_MOD;
      }

      void time(long ns) {
         ewmaNs = samples == 0 ? ns : ewmaNs + (ns - ewmaNs) * (samples < WARM_CALLS ? 1.0 / (samples + 1) : 0.125);
         samples++;
         if (ns > maxNs) {
            maxNs = ns;
         }
      }

      String where() {
         if (c.prototype == null) {
            return "?";
         }
         return c.prototype.filename + ":" + (c.prototype.lines != null && c.prototype.lines.length > 0 ? c.prototype.lines[0] : 0)
               + (c.prototype.name != null ? " " + c.prototype.name : "");
      }
   }

   /** One dispatch target: an event, or one local player's OnPlayerUpdate. */
   static final class Slot {
      final String event;
      final int index;
      final IdentityHashMap<LuaClosure, H> byClosure = new IdentityHashMap<>();
      LuaClosure[] keys = new LuaClosure[16];
      H[] hs = new H[16];
      final double[] ring = new double[RING];
      final long[] ringFrame = new long[RING];
      long rebalancedAt;
      final ArrayList<LuaClosure> list; // the event's callbacks (pruning)
      // per frame (RECORD / adaptive feedback)
      long frameEstNs, frameMeasNs, frameLoopNs;
      int frameRuns, frameSkips;

      Slot(String event, int index, ArrayList<LuaClosure> list) {
         this.event = event;
         this.index = index;
         this.list = list;
         Arrays.fill(ringFrame, Long.MIN_VALUE);
      }

      long measuredFrameNs() {
         return frameMeasNs;
      }

      /** Drops the state of closures no longer listed (mods that add a fresh closure per action). */
      void prune() {
         if (byClosure.size() <= list.size() + 64) {
            return;
         }
         IdentityHashMap<LuaClosure, Boolean> live = new IdentityHashMap<>();
         for (int i = 0; i < list.size(); i++) {
            live.put(list.get(i), Boolean.TRUE);
         }
         byClosure.keySet().removeIf(c -> !live.containsKey(c));
         Arrays.fill(keys, null);
      }

      H handler(int n, LuaClosure c) {
         if (n < keys.length && keys[n] == c) {
            return hs[n];
         }
         H h = byClosure.get(c);
         if (h == null) {
            h = new H(c);
            byClosure.put(c, h);
            ALL.add(h);
            ALL_SLOT.add(this);
         }
         if (n >= keys.length) {
            int len = Math.max(n + 1, keys.length * 2);
            keys = Arrays.copyOf(keys, len);
            hs = Arrays.copyOf(hs, len);
         }
         keys[n] = c;
         hs[n] = h;
         return h;
      }

      double load(long f) {
         int i = (int)(f & (RING - 1));
         return ringFrame[i] == f ? ring[i] : 0.0;
      }

      void addLoad(long f, double v) {
         int i = (int)(f & (RING - 1));
         if (ringFrame[i] != f) {
            ringFrame[i] = f;
            ring[i] = 0.0;
         }
         ring[i] += v;
      }

      void clearFuture() {
         Arrays.fill(ringFrame, Long.MIN_VALUE);
      }
   }

   /** Per Event: is it capped, its slots. Event.pzoptTick holds it. */
   static final class Ev {
      final String name;
      final boolean capped;
      final boolean perPlayer;
      final boolean ticksArg;
      final Slot[] slots = new Slot[5];

      Ev(String name) {
         this.name = name;
         this.capped = cappedEvent(name);
         this.perPlayer = name.equals("OnPlayerUpdate");
         this.ticksArg = name.equals("OnTick") || name.equals("OnTickEvenPaused");
      }

      /** The slot of this call (OnPlayerUpdate: the local player; null = a remote player, never spaced out). */
      Slot slot(Object[] params, ArrayList<LuaClosure> list) {
         int i = 0;
         if (perPlayer) {
            Object p = params != null && params.length > 0 ? params[0] : null;
            if (!(p instanceof IsoPlayer pl) || !pl.isLocalPlayer()) {
               return null;
            }
            i = Math.max(0, Math.min(3, pl.getPlayerNum()));
         }
         Slot s = slots[i];
         if (s == null) {
            s = slots[i] = new Slot(name, i, list);
            SLOTS.add(s);
         }
         return s;
      }
   }

   static final ArrayList<Slot> SLOTS = new ArrayList<>();
   static final ArrayList<H> ALL = new ArrayList<>(); // every handler seen in a slot (stats, water-filling)
   static final ArrayList<Slot> ALL_SLOT = new ArrayList<>(); // ALL's slot, same index

   private static final Set<String> CAPPED_EVENTS = LuaTickScan.ids(Config.LUA_TICK_EVENTS);
   private static final Set<String> USER_EVERY = LuaTickScan.ids(Config.LUA_TICK_MODS_EVERY_FRAME);
   private static final Set<String> USER_CAPPED = LuaTickScan.ids(Config.LUA_TICK_MODS_CAPPED);

   static boolean cappedEvent(String name) {
      return name != null && CAPPED_EVENTS.contains(name) && !name.equals("OnRenderTick");
   }

   // --- the frame clock (game thread) ---------------------------------------------------------------------------------

   static long frameNo;
   static long lastLogNs;
   static long lastStepNs;
   static double fpsSum; // sum of GameTime.fpsMultiplier over the frames so far
   static double periodNs = 1e9 / 60.0; // smoothed frame period
   static long lastPeriodNs;
   static long lastCpuNs;
   static double busy; // smoothed game-thread busy share
   static double otherNs = Double.NaN; // smoothed frame period minus the spaced-out handlers' cost (adaptive)
   static double budgetNs = Double.POSITIVE_INFINITY; // adaptive budget for the spaced-out handlers per frame
   static double level = Double.POSITIVE_INFINITY; // water-filling level (ns per frame per handler)
   static double fullNs; // sum of the spaced-out candidates' cost per frame (all every frame)
   static double frameSpacedNs; // this frame's spaced-out handlers' cost
   static boolean schedNow; // the schedule applies this frame
   static boolean spikeNow; // luaTickSpikeDeferPct: this event fires in a frame already long
   static long spikeDefers, emptySkips, wakeRuns, inputFrames; // census
   static boolean inputNow; // luaTickInputWake: a key, mouse button or the wheel changed in this frame (or a controller is in use)
   static boolean fastNow = FAST;
   static boolean skipNow = SKIP_EMPTY;
   static boolean altPhaseOn = true;
   static int curPh = 1; // RECORD: this frame's A/B phase
   static final long[] phFrames = new long[2];
   static long altPhaseStart;
   private static ThreadMXBean mx;
   private static boolean cpuOk = true;

   /** Called at the first dispatch of a frame (frame identity: Pacing's step start). */
   static void frame() {
      long st = Pacing.stepStartNs();
      if (st == lastStepNs && st != 0L) {
         return;
      }
      long cpu = threadCpu();
      if (lastStepNs != 0L && st > lastStepNs) {
         long p = st - lastStepNs;
         lastPeriodNs = p;
         periodNs += (Math.min(p, 1_000_000_000L) - periodNs) * 0.2;
         if (cpu >= 0 && lastCpuNs >= 0) {
            double b = Math.min(1.0, (double)(cpu - lastCpuNs) / p);
            busy += (b - busy) * 0.2;
         }
         if (RECORD) {
            Rec.row(p, cpu >= 0 && lastCpuNs >= 0 ? cpu - lastCpuNs : -1L);
         }
         if (ADAPT != AD_OFF) {
            controller(p);
         }
      }
      lastStepNs = st == 0L ? lastStepNs + 1 : st;
      lastCpuNs = cpu;
      frameNo++;
      fpsSum += GameTime.getInstance().fpsMultiplier;
      if (INPUT_WAKE) {
         inputNow = inputChanged();
         if (inputNow) {
            inputFrames++;
         }
      }
      frameSpacedNs = 0.0;
      for (int i = 0; i < SLOTS.size(); i++) {
         Slot s = SLOTS.get(i);
         s.frameEstNs = 0L;
         s.frameMeasNs = 0L;
         s.frameLoopNs = 0L;
         s.frameRuns = 0;
         s.frameSkips = 0;
      }
      if (frameNo % 600L == 0L) {
         pruneAll();
      }
      if (RECORD && st - lastLogNs > 10_000_000_000L) {
         lastLogNs = st;
         Log.info(describe());
      }
      boolean on = true;
      if (ALT_MS > 0) {
         long now = System.nanoTime();
         if (altPhaseStart == 0L) {
            altPhaseStart = now;
         }
         boolean ph = ((now - altPhaseStart) / (ALT_MS * 1_000_000L) & 1L) == 0L;
         if (ph != altPhaseOn) {
            altPhaseOn = ph;
            if (ph && ALT_SCHED) {
               rebalanceAll(); // resuming: the overdue handlers spread again instead of all running now
            }
         }
         on = altPhaseOn;
      }
      curPh = on ? 1 : 0;
      phFrames[curPh]++;
      fastNow = FAST && !((ALT_FAST || ALT_ALL) && !on);
      skipNow = SKIP_EMPTY && !(ALT_ALL && !on);
      boolean asleep = !GameServer.server && !GameClient.client && IsoPlayer.getInstance() != null && IsoPlayer.allPlayersAsleep();
      schedNow = SCHED && !GameServer.server && !asleep && !(ALT_SCHED && !on);
      if (schedNow && ADAPT != AD_OFF && (frameNo & 3L) == 0L) {
         waterFill();
      }
   }

   /** Every 600 frames: the handler lists rebuilt from the slots (removed closures dropped). */
   static void pruneAll() {
      ALL.clear();
      ALL_SLOT.clear();
      for (int i = 0; i < SLOTS.size(); i++) {
         Slot s = SLOTS.get(i);
         s.prune();
         for (H h : s.byClosure.values()) {
            ALL.add(h);
            ALL_SLOT.add(s);
         }
      }
   }

   private static long threadCpu() {
      if (!cpuOk || (ADAPT == AD_OFF && !RECORD)) {
         return -1L;
      }
      try {
         if (mx == null) {
            mx = ManagementFactory.getThreadMXBean();
         }
         return mx.getCurrentThreadCpuTime();
      } catch (Throwable t) {
         cpuOk = false;
         return -1L;
      }
   }

   /** The adaptive budget for the next frames from the frame that just ended (period p). */
   static void controller(long p) {
      double target = targetNs();
      double other = p - frameSpacedNs;
      otherNs = Double.isNaN(otherNs) ? other : otherNs + (other - otherNs) * 0.25;
      boolean bound = busy * 100.0 >= Config.LUA_TICK_BUSY_PCT;
      double min = Config.LUA_TICK_MIN_BUDGET_US * 1000.0;
      if (ADAPT == AD_BUDGET) {
         double b = bound ? Math.max(min, target - otherNs) : Double.POSITIVE_INFINITY;
         budgetNs = Double.isInfinite(b) || Double.isInfinite(budgetNs) ? b : budgetNs + (b - budgetNs) * 0.25;
      } else { // AIMD: halve-ish when late and game-thread bound, grow by 5 % of the full cost when on time
         if (Double.isInfinite(budgetNs)) {
            budgetNs = Math.max(min, fullNs);
         }
         if (bound && p > target * 1.05) {
            budgetNs = Math.max(min, budgetNs * 0.7);
         } else if (p < target * 0.95 || !bound) {
            budgetNs = Math.min(Math.max(min, fullNs * 1.05), budgetNs + Math.max(fullNs * 0.05, 50_000.0));
         }
      }
   }

   static double targetNs() {
      if (Config.LUA_TICK_TARGET_FPS > 0) {
         return 1e9 / Config.LUA_TICK_TARGET_FPS;
      }
      long cap = Pacing.capIntervalNs();
      return cap > 0L ? cap : 1e9 / 240.0;
   }

   /** Max-min fair share of the budget: the level each spaced-out candidate's per-frame cost is cut to. */
   static void waterFill() {
      int n = 0;
      double[] c = costs;
      double total = 0.0;
      for (int i = 0; i < ALL.size(); i++) {
         H h = ALL.get(i);
         if (h.pin == PIN_NONE && h.samples >= WARM_CALLS && h.ewmaNs >= Config.LUA_TICK_CHEAP_US * 1000.0 && frameNo - h.lastRunFrame < 600L) {
            if (n == c.length) {
               c = costs = Arrays.copyOf(c, n * 2);
            }
            c[n++] = h.ewmaNs;
            total += h.ewmaNs;
         }
      }
      fullNs = total;
      double b = budgetNs;
      if (Double.isInfinite(b) || b >= total || n == 0) {
         level = Double.POSITIVE_INFINITY;
         return;
      }
      if (b <= 0.0) {
         level = 0.0;
         return;
      }
      Arrays.sort(c, 0, n); // ascending
      double below = 0.0; // sum of the costs under the level
      for (int k = 0; k < n; k++) {
         int above = n - k;
         double lv = (b - below) / above;
         if (lv <= c[k]) {
            level = lv;
            return;
         }
         below += c[k];
      }
      level = Double.POSITIVE_INFINITY;
   }

   private static double[] costs = new double[256];

   // --- dispatch --------------------------------------------------------------------------------------------------------

   /** Event.trigger's loop (the callbacks list is not empty). Same return value and error handling as stock. */
   public static boolean trigger(Event e, LuaCaller caller, Object[] params) {
      Ev ev = e.pzoptTick instanceof Ev x && x.name.equals(e.name) ? x : init(e);
      Slot s = null;
      if (ev.capped && (SCHED || RECORD || ALT_MS > 0)) {
         frame();
         s = SCHED || RECORD ? ev.slot(params, e.callbacks) : null;
      }
      if (s != null && SPIKE_PCT > 0 && schedNow) {
         long el = System.nanoTime() - Pacing.stepStartNs();
         spikeNow = Pacing.stepStartNs() != 0L && el > periodNs * SPIKE_PCT / 100.0;
      } else {
         spikeNow = false;
      }
      boolean fast = fastNow;
      if (!fast && s == null && !ERR && !skipNow) {
         return stockLoop(e, caller, params);
      }
      if (RECORD && s != null) { // the whole dispatch, the loop's own overhead included (the fast-dispatch A/B)
         long t0 = System.nanoTime();
         try {
            return loop(e, ev, s, caller, params, fast);
         } finally {
            s.frameLoopNs += System.nanoTime() - t0;
         }
      }
      return loop(e, ev, s, caller, params, fast);
   }

   private static boolean announced;

   private static Ev init(Event e) {
      if (!announced) {
         announced = true;
         Log.info("luaTick: dispatchFast=" + FAST + " skipEmpty=" + SKIP_EMPTY + " hz=" + HZ + " adaptive=" + Config.LUA_TICK_ADAPTIVE
               + " events=" + Config.LUA_TICK_EVENTS + " timeComp=" + Config.LUA_TICK_TIME_COMP + " stagger=" + Config.LUA_TICK_STAGGER
               + " costAware=" + Config.LUA_TICK_COST_AWARE + " cheapUs=" + Config.LUA_TICK_CHEAP_US + " scan=" + Config.LUA_TICK_SCAN
               + " maxStale=" + Config.LUA_TICK_MAX_STALE_FRAMES + "f/" + Config.LUA_TICK_MAX_STALE_MS + "ms errorThrottle="
               + Config.LUA_ERROR_THROTTLE + " stats=" + RECORD + (ALT_MS > 0 ? " alternate=" + ALT_MS + "ms/" + Config.DEV_LUA_TICK_ALTERNATE_WHAT : ""));
      }
      Ev ev = new Ev(e.name);
      e.pzoptTick = ev;
      return ev;
   }

   /** The stock Event.trigger loop, verbatim in behaviour (the A/B's "off" half and every event while the keys are off). */
   static boolean stockLoop(Event e, LuaCaller caller, Object[] params) {
      ArrayList<LuaClosure> callbacks = e.callbacks;
      GameProfiler profiler = GameProfiler.getInstance();
      boolean slow = DebugOptions.instance.checks.slowLuaEvents.getValue();
      for (int n = 0; n < callbacks.size(); n++) {
         LuaClosure closure = callbacks.get(n);
         try {
            GameProfiler.ProfileArea area = profiler.profile("Lua - " + e.name);
            try {
               long start = slow ? System.nanoTime() : 0L;
               invoker.call(caller, closure, params, e.name);
               if (slow) {
                  double delayMS = (System.nanoTime() - start) / 1000000.0;
                  if (delayMS > 250.0) {
                     DebugType.Lua.warn("SLOW Lua event callback %s %s %dms", new Object[]{closure.prototype.file, closure, (int)delayMS});
                  }
               }
            } catch (Throwable t) {
               if (area != null) {
                  try {
                     area.close();
                  } catch (Throwable t2) {
                     t.addSuppressed(t2);
                  }
               }
               throw t;
            }
            if (area != null) {
               area.close();
            }
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
         if (!callbacks.contains(closure)) {
            n--;
         }
      }
      return true;
   }

   /** The loop with the fast dispatch and / or the schedule (s = this call's slot, null = not a capped call). */
   static boolean loop(Event e, Ev ev, Slot s, LuaCaller caller, Object[] params, boolean fast) {
      ArrayList<LuaClosure> callbacks = e.callbacks;
      GameProfiler profiler = GameProfiler.getInstance();
      boolean slow = DebugOptions.instance.checks.slowLuaEvents.getValue();
      boolean sched = s != null && schedNow;
      for (int n = 0; n < callbacks.size(); n++) {
         LuaClosure closure = callbacks.get(n);
         H h = s == null ? null : s.handler(n, closure);
         if (h == null || !sched || decide(s, h, ev)) {
            if (skipNow && empty(closure)) {
               emptySkips++;
            } else {
               call(e, ev, s, h, caller, closure, params, slow, profiler, fast);
            }
         }
         if (fast) {
            if (n >= callbacks.size() || callbacks.get(n) != closure) {
               if (!callbacks.contains(closure)) {
                  n--;
               }
            }
         } else if (!callbacks.contains(closure)) {
            n--;
         }
      }
      return true;
   }

   private static void call(Event e, Ev ev, Slot s, H h, LuaCaller caller, LuaClosure closure, Object[] params, boolean slow,
         GameProfiler profiler, boolean fast) {
      boolean timed = h != null && (RECORD || h.samples < WARM_CALLS || h.calls % Config.LUA_TICK_SAMPLE_EVERY == 0L);
      boolean comp = false;
      float compSum = 0.0F;
      Object[] args = params;
      if (h != null) {
         long f = frameNo;
         if (COMP != 0 && schedNow && f - h.lastRunFrame > 1L && !Double.isNaN(h.fpsAtRun)) {
            comp = true;
            compSum = (float)(fpsSum - h.fpsAtRun);
         }
         h.lastRunFrame = f;
         h.fpsAtRun = fpsSum;
         h.calls++;
         if (OWN_TICKS && ev.ticksArg && h.interval > 1.0) {
            h.ownTicks++;
            args = new Object[]{h.ownTicks};
         }
      }
      Object pErr = null;
      if (ERR) {
         pErr = LuaErrorThrottle.current;
         LuaErrorThrottle.owner = Thread.currentThread();
         LuaErrorThrottle.limit = Config.LUA_ERROR_THROTTLE;
         LuaErrorThrottle.current = closure;
      }
      boolean pActive = LuaTickTime.active;
      Thread pOwner = LuaTickTime.owner;
      float pSum = LuaTickTime.sum;
      float pPom = 0.0F;
      GameTime gt = null;
      if (comp && COMP == 1) {
         LuaTickTime.owner = Thread.currentThread();
         LuaTickTime.sum = compSum;
         LuaTickTime.active = true;
      } else {
         LuaTickTime.active = false; // a handler running inside a compensated one reads its own frame
         if (comp) { // object: GameTime's own staggered-update multiplier
            gt = GameTime.getInstance();
            pPom = gt.perObjectMultiplier;
            float cur = gt.fpsMultiplier;
            gt.perObjectMultiplier = pPom * (cur > 0.0F ? compSum / cur : 1.0F);
         }
      }
      try {
         GameProfiler.ProfileArea area = !fast || GameProfiler.isRunning() ? profiler.profile("Lua - " + e.name) : null;
         try {
            long start = slow || timed ? System.nanoTime() : 0L;
            invoker.call(caller, closure, args, e.name);
            if (slow || timed) {
               long ns = System.nanoTime() - start;
               if (timed) {
                  h.time(ns);
                  if (RECORD) {
                     h.timedNs += ns;
                     h.phNs[curPh] += ns;
                     h.phRuns[curPh]++;
                     s.frameMeasNs += ns;
                  }
               }
               if (slow) {
                  double delayMS = ns / 1000000.0;
                  if (delayMS > 250.0) {
                     DebugType.Lua.warn("SLOW Lua event callback %s %s %dms", new Object[]{closure.prototype.file, closure, (int)delayMS});
                  }
               }
            }
         } catch (Throwable t) {
            if (area != null) {
               try {
                  area.close();
               } catch (Throwable t2) {
                  t.addSuppressed(t2);
               }
            }
            throw t;
         }
         if (area != null) {
            area.close();
         }
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      } finally {
         if (ERR) {
            LuaErrorThrottle.current = pErr;
         }
         LuaTickTime.active = pActive;
         LuaTickTime.owner = pOwner;
         LuaTickTime.sum = pSum;
         if (gt != null) {
            gt.perObjectMultiplier = pPom;
         }
      }
      if (h != null) {
         h.runs++;
         double est = h.ewmaNs;
         s.frameEstNs += (long)est;
         s.frameRuns++;
         if (h.interval > 1.0) {
            frameSpacedNs += est;
         }
      }
   }

   /** luaEventSkipEmpty: a Lua function whose whole body is one RETURN (calling it has no effect). */
   static boolean empty(LuaClosure c) {
      int[] code = c.prototype == null ? null : c.prototype.code;
      return code != null && code.length == 1 && (code[0] & 63) == 30;
   }

   /** Does handler h run in this frame? Also schedules its next frame when it runs. */
   static boolean decide(Slot s, H h, Ev ev) {
      long f = frameNo;
      if (h.stampFrame == f) { // the same closure listed twice: both entries follow the first one's decision
         return h.stampRun;
      }
      h.stampFrame = f;
      boolean run = decide0(s, h, f);
      h.stampRun = run;
      if (!run) {
         h.skips++;
         s.frameSkips++;
      }
      return run;
   }

   private static boolean decide0(Slot s, H h, long f) {
      if (h.pin < 0) {
         pin(h);
      }
      // cheap handlers stay every frame under the adaptive budget only (spacing them buys it nothing); the cap is a rate for
      // every mod handler, and the 650 list's ~700 cheap ones are 1.1-1.2 ms a frame together (run 15). Hysteresis: a
      // spaced-out handler stays so down to half the threshold.
      double cheap = HZ > 0 ? 0.0 : Config.LUA_TICK_CHEAP_US * (h.interval > 1.0 ? 500.0 : 1000.0);
      if (h.pin != PIN_NONE || h.samples < WARM_CALLS || h.ewmaNs < cheap) {
         h.interval = 1.0;
         h.ideal = h.due = f + 1;
         return true;
      }
      double n = interval(h);
      h.interval = n;
      if (n <= 1.0) {
         h.ideal = h.due = f + 1;
         return true;
      }
      boolean woken = h.wake && inputNow && h.lastRunFrame != f;
      if (woken) {
         wakeRuns++;
      } else if (h.due > f + 0.5 && h.due - f <= maxAhead(n) + 1.0) { // (a due further ahead is stale state: run now)
         return false;
      }
      if (!woken && spikeNow && f - h.lastRunFrame <= n + 1.0) { // a long frame: a due handler waits a frame (at most two frames late)
         h.due = f + 1;
         spikeDefers++;
         return false;
      }
      // runs now; the next frame
      double ideal = h.ideal + n;
      if (ideal < f + 0.5 || ideal > f + n + 1.0) {
         ideal = f + n;
      }
      h.ideal = ideal;
      h.due = place(s, h, ideal, n, f);
      if (f - s.rebalancedAt >= REBALANCE_FRAMES && Config.LUA_TICK_STAGGER) {
         rebalance(s, f);
      }
      return true;
   }

   /** The adaptive staleness bound in frames (luaTickMaxStaleFrames / luaTickMaxStaleMs). */
   static double staleFrames() {
      return Math.min(Config.LUA_TICK_MAX_STALE_FRAMES, Config.LUA_TICK_MAX_STALE_MS * 1e6 / Math.max(1.0, periodNs));
   }

   /** Frames between two calls of h (>= 1). */
   static double interval(H h) {
      double n = 1.0;
      if (HZ > 0) {
         n = 1e9 / HZ / Math.max(1.0, periodNs);
      }
      if (ADAPT != AD_OFF && !Double.isInfinite(level) && h.ewmaNs > level) {
         double stale = staleFrames();
         double a = level <= 0.0 ? stale : Math.min(stale, h.ewmaNs / level);
         n = Math.max(n, Math.max(1.0, a));
      }
      return Math.min(n, RING / 4.0);
   }

   /**
    * How far ahead a handler of interval n may be placed: half a period past its ideal frame under the cap (the cap is a
    * maximum rate, a frame late is harmless and its time is caught up), never past the staleness bound when adaptive.
    */
   static double maxAhead(double n) {
      return ADAPT != AD_OFF && HZ <= 0 ? Math.max(1.0, Math.max(Math.floor(n), staleFrames())) : n * 1.5 + 1.0;
   }

   /** The frame near {@code ideal} with the least projected load (stagger), its load booked. */
   static double place(Slot s, H h, double ideal, double n, long f) {
      double cost = Config.LUA_TICK_COST_AWARE ? Math.max(1.0, h.ewmaNs) : 1.0;
      if (!Config.LUA_TICK_STAGGER) {
         long d = Math.max(f + 1, Math.round(ideal));
         return d;
      }
      double hw = (n - 1.0) / 2.0;
      long lo = Math.max(f + 1, Math.round(ideal - hw));
      long hi = Math.max(lo, Math.min(Math.round(ideal + hw), f + (long)Math.floor(maxAhead(n))));
      long best = lo;
      double bestLoad = Double.POSITIVE_INFINITY;
      double bestDist = Double.POSITIVE_INFINITY;
      for (long d = lo; d <= hi; d++) {
         double l = s.load(d);
         double dist = Math.abs(d - ideal);
         if (l < bestLoad - 1e-9 || (Math.abs(l - bestLoad) <= 1e-9 && dist < bestDist)) {
            best = d;
            bestLoad = l;
            bestDist = dist;
         }
      }
      s.addLoad(best, cost);
      return best;
   }

   /** LPT: the slot's spaced-out handlers placed again from scratch, the most expensive first. */
   static void rebalance(Slot s, long f) {
      s.rebalancedAt = f;
      ArrayList<H> hs = new ArrayList<>();
      for (H h : s.byClosure.values()) {
         if (h.interval > 1.0 && h.pin == PIN_NONE && f - h.lastRunFrame < 600L) {
            hs.add(h);
         }
      }
      if (hs.isEmpty()) {
         return;
      }
      if (Config.LUA_TICK_COST_AWARE) {
         hs.sort((a, b) -> Double.compare(b.ewmaNs, a.ewmaNs));
      }
      s.clearFuture();
      for (H h : hs) {
         // its next frame anywhere in (f, f + n]: at most one extra period since its last call
         long lo = f + 1;
         long hi = f + Math.max(1L, (long)Math.ceil(h.interval));
         long best = lo;
         double bestLoad = Double.POSITIVE_INFINITY;
         double cost = Config.LUA_TICK_COST_AWARE ? Math.max(1.0, h.ewmaNs) : 1.0;
         for (long d = lo; d <= hi; d++) {
            double l = s.load(d);
            if (l < bestLoad - 1e-9) {
               best = d;
               bestLoad = l;
            }
         }
         s.addLoad(best, cost);
         h.ideal = best;
         h.due = best;
      }
   }

   static void rebalanceAll() {
      for (int i = 0; i < SLOTS.size(); i++) {
         rebalance(SLOTS.get(i), frameNo);
      }
   }

   /** Why a handler stays every frame whatever its cost (decided once). */
   static void pin(H h) {
      if (h.cls != CLS_MOD) {
         h.pin = PIN_ORIGIN;
         h.pinWhy = h.origin;
         return;
      }
      String id = h.origin.startsWith("mod:") ? h.origin.substring(4) : h.origin;
      if (USER_CAPPED.contains(id)) {
         h.pin = PIN_NONE;
         h.pinWhy = "user capped";
         return;
      }
      if (USER_EVERY.contains(id)) {
         h.pin = PIN_USER;
         h.pinWhy = "user";
         return;
      }
      if (LuaTickScan.KNOWN.contains(id)) {
         h.pin = PIN_KNOWN;
         h.pinWhy = "known";
         return;
      }
      if (Config.LUA_TICK_SCAN) {
         KahluaTable env = zombie.Lua.LuaManager.env;
         String r = LuaTickScan.scan(h.c, env);
         if (r != null && INPUT_WAKE && r.startsWith("input ") && LuaTickScan.scanWithout(h.c, env, "input") == null) {
            h.pin = PIN_NONE;
            h.pinWhy = "wake " + r;
            h.wake = true;
            return;
         }
         if (r != null) {
            h.pin = PIN_SCAN;
            h.pinWhy = r;
            return;
         }
      }
      h.pin = PIN_NONE;
      h.pinWhy = "";
   }

   // --- measurement (luaTickStats) ------------------------------------------------------------------------------------

   /** Per-frame rows for the benchmark's results (luaTickStats). */
   static final class Rec {
      static final int MAX = 200_000;
      static long[] t = new long[4096];
      static long[] period = new long[4096];
      static long[] cpu = new long[4096];
      static long[] tick = new long[4096], opu = new long[4096], paused = new long[4096], loop = new long[4096];
      static int[] runs = new int[4096], skips = new int[4096];
      static float[] budget = new float[4096], lvl = new float[4096];
      static byte[] phase = new byte[4096];
      static long[] epoch = new long[4096];
      static int n;
      static boolean on;
      static long[] tickTotals = new long[3];

      static void row(long p, long c) {
         if (!on || n >= MAX) {
            return;
         }
         if (n == t.length) {
            int len = n * 2;
            t = Arrays.copyOf(t, len);
            period = Arrays.copyOf(period, len);
            cpu = Arrays.copyOf(cpu, len);
            tick = Arrays.copyOf(tick, len);
            opu = Arrays.copyOf(opu, len);
            paused = Arrays.copyOf(paused, len);
            loop = Arrays.copyOf(loop, len);
            runs = Arrays.copyOf(runs, len);
            skips = Arrays.copyOf(skips, len);
            budget = Arrays.copyOf(budget, len);
            lvl = Arrays.copyOf(lvl, len);
            phase = Arrays.copyOf(phase, len);
            epoch = Arrays.copyOf(epoch, len);
         }
         long tk = 0, op = 0, pa = 0, lp = 0;
         int r = 0, sk = 0;
         for (int i = 0; i < SLOTS.size(); i++) {
            Slot s = SLOTS.get(i);
            long ns = s.measuredFrameNs();
            switch (s.event) {
               case "OnTick" -> tk += ns;
               case "OnPlayerUpdate" -> op += ns;
               default -> pa += ns;
            }
            lp += s.frameLoopNs;
            r += s.frameRuns;
            sk += s.frameSkips;
         }
         t[n] = lastStepNs;
         period[n] = p;
         cpu[n] = c;
         tick[n] = tk;
         opu[n] = op;
         paused[n] = pa;
         loop[n] = lp;
         runs[n] = r;
         skips[n] = sk;
         budget[n] = (float)(Double.isInfinite(budgetNs) ? -1.0 : budgetNs / 1000.0);
         lvl[n] = (float)(Double.isInfinite(level) ? -1.0 : level / 1000.0);
         phase[n] = (byte)(schedNow ? 1 : 0) ;
         if (ALT_FAST) {
            phase[n] = (byte)(fastNow ? 1 : 0);
         } else if (ALT_MS > 0) { // the A/B phase itself (an A/A control with no schedule alternates too)
            phase[n] = (byte)curPh;
         }
         epoch[n] = System.currentTimeMillis();
         n++;
      }
   }

   /** Benchmark route start: the recording begins (counters reset). */
   public static void recordStart() {
      if (!ON || !RECORD) {
         return;
      }
      Rec.n = 0;
      Rec.on = true;
      phFrames[0] = phFrames[1] = 0L;
      for (H h : ALL) {
         h.runs = h.skips = 0;
         h.timedNs = 0;
         h.maxNs = 0;
         h.phNs[0] = h.phNs[1] = h.phRuns[0] = h.phRuns[1] = 0L;
      }
   }

   /** Benchmark route end: {@code <base>-luatick.csv} (per frame) and {@code <base>-luatick.txt} (per handler) into dir. */
   public static void recordWrite(File dir, String base) {
      recordWrite(new File(dir, base + "-luatick.csv"), new File(dir, base + "-luatick.txt"));
   }

   /** The recording's frame rows into {@code csv}, the handler table into {@code txt} (harness runs: pzopt-luatick*.out). */
   public static void recordWrite(File csv, File txt) {
      if (!ON || !RECORD || !Rec.on) {
         return;
      }
      Rec.on = false;
      try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(csv), StandardCharsets.UTF_8))) {
         w.println("epoch_ms,step_ns,period_us,cpu_us,ontick_us,opu_us,paused_us,loop_us,runs,skips,budget_us,level_us,phase");
         for (int i = 0; i < Rec.n; i++) {
            w.printf(Locale.ROOT, "%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%.1f,%.1f,%d%n", Rec.epoch[i], Rec.t[i], Rec.period[i] / 1e3,
                  Rec.cpu[i] / 1e3, Rec.tick[i] / 1e3, Rec.opu[i] / 1e3, Rec.paused[i] / 1e3, Rec.loop[i] / 1e3, Rec.runs[i], Rec.skips[i], Rec.budget[i], Rec.lvl[i], Rec.phase[i]);
         }
      } catch (Exception ex) {
         Log.warn("luaTick: could not write the frame rows: " + ex);
      }
      try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(txt), StandardCharsets.UTF_8))) {
         w.println(describe());
         w.println("# frames by phase: off " + phFrames[0] + ", on " + phFrames[1]);
         w.println("event\tslot\torigin\tpin\tpin_why\tcalls_run\tskips\tmean_us\tmax_us\ttimed_ms\tinterval\toff_ms\toff_runs\ton_ms\ton_runs\twhere");
         ArrayList<Integer> idx = new ArrayList<>();
         for (int i = 0; i < ALL.size(); i++) {
            idx.add(i);
         }
         idx.sort((a, b) -> Long.compare(ALL.get(b).timedNs, ALL.get(a).timedNs));
         for (int i : idx) {
            H h = ALL.get(i);
            Slot s = ALL_SLOT.get(i);
            if (h.runs + h.skips == 0) {
               continue;
            }
            if (h.pin < 0) {
               pin(h);
            }
            w.printf(Locale.ROOT, "%s\t%d\t%s\t%s\t%s\t%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.3f\t%d\t%.3f\t%d\t%s%n", s.event, s.index, h.origin, PIN_NAMES[Math.max(0, h.pin)],
                  h.pinWhy, h.runs, h.skips, h.ewmaNs / 1e3, h.maxNs / 1e3, h.timedNs / 1e6, h.interval, h.phNs[0] / 1e6, h.phRuns[0], h.phNs[1] / 1e6,
                  h.phRuns[1], h.where());
         }
      } catch (Exception ex) {
         Log.warn("luaTick: could not write the handler table: " + ex);
      }
      Log.info("luaTick: " + Rec.n + " frames recorded into " + csv.getName() + " / " + txt.getName());
   }

   /** One line: the keys and the handler census. */
   // --- the menu's per-mod list -------------------------------------------------------------------------------------------

   private static java.lang.reflect.Field eventMapField;

   private static Event event(String name) {
      try {
         if (eventMapField == null) {
            java.lang.reflect.Field f = zombie.Lua.LuaEventManager.class.getDeclaredField("EventMap");
            f.setAccessible(true);
            eventMapField = f;
         }
         Object m = eventMapField.get(null);
         return m instanceof java.util.Map<?, ?> map && map.get(name) instanceof Event e ? e : null;
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * The PZ OPTIMIZATION screen's "Mods' per-frame Lua" list: one line per mod with handlers on the capped events,
    * tab-separated: mod id, display name, handlers, handlers kept every frame by the scan, the first scan reason, the
    * measured cost of all its handlers if called every frame (us, -1 = not measured this session: the main menu), the
    * events. Built from the events' current handler lists (the main menu has the client / shared files' handlers; in game
    * every file's), with this session's measurements where LuaTick has them. Lua-thread only.
    */
   public static String modsReport() {
      java.util.LinkedHashMap<String, Object[]> mods = new java.util.LinkedHashMap<>(); // id -> {name, n, pinned, reason, costNs, events}
      KahluaTable env = zombie.Lua.LuaManager.env;
      for (String evName : CAPPED_EVENTS) {
         Event e = event(evName);
         if (e == null) {
            continue;
         }
         Ev ev = e.pzoptTick instanceof Ev x && x.name.equals(e.name) ? x : null;
         IdentityHashMap<LuaClosure, Boolean> once = new IdentityHashMap<>();
         for (LuaClosure c : new ArrayList<>(e.callbacks)) {
            if (c == null || once.put(c, Boolean.TRUE) != null) {
               continue;
            }
            String origin = c.prototype == null ? "other" : LuaOrigin.of(c.prototype.filename);
            if (!origin.startsWith("mod:")) {
               continue;
            }
            String id = origin.substring(4);
            Object[] m = mods.computeIfAbsent(id, k -> new Object[]{ModLuaProfile.displayName(origin), 0, 0, "", -1.0, new java.util.LinkedHashSet<String>()});
            m[1] = (Integer)m[1] + 1;
            @SuppressWarnings("unchecked")
            java.util.Set<String> evs = (java.util.Set<String>)m[5];
            evs.add(evName);
            String r = null;
            H h = null;
            if (ev != null) {
               for (Slot sl : ev.slots) {
                  if (sl != null && sl.byClosure.get(c) != null) {
                     h = sl.byClosure.get(c);
                     break;
                  }
               }
            }
            if (h != null && h.pin == PIN_SCAN) {
               r = h.pinWhy;
            } else if (h == null || h.pin < 0) {
               r = LuaTickScan.scan(c, env);
            }
            if (r != null) {
               m[2] = (Integer)m[2] + 1;
               if (((String)m[3]).isEmpty()) {
                  m[3] = r;
               }
            }
            if (h != null && h.samples > 0) {
               m[4] = Math.max(0.0, (Double)m[4]) + h.ewmaNs;
            }
         }
      }
      StringBuilder b = new StringBuilder();
      for (java.util.Map.Entry<String, Object[]> en : mods.entrySet()) {
         Object[] m = en.getValue();
         double ns = (Double)m[4];
         b.append(en.getKey().replace('\t', ' ')).append('\t').append(String.valueOf(m[0]).replace('\t', ' ')).append('\t').append(m[1])
               .append('\t').append(m[2]).append('\t').append(String.valueOf(m[3]).replace('\t', ' ')).append('\t')
               .append(ns < 0.0 ? "-1" : String.format(Locale.ROOT, "%.1f", ns / 1e3)).append('\t')
               .append(String.join(",", (java.util.Set<String>)m[5])).append('\n');
      }
      return b.toString();
   }

   // --- luaTickInputWake: did the input state change in this frame -----------------------------------------------------

   private static boolean[] keyDown, keyLast;
   private static boolean keyFailed, lastTyping;

   /** True when a key, a mouse button or the wheel changed since the last frame, a controller is assigned, or unknown. */
   static boolean inputChanged() {
      if (keyDown == null && !keyFailed) {
         try { // GameKeyboard's arrays are made on its first update and kept for the session
            java.lang.reflect.Field d = zombie.input.GameKeyboard.class.getDeclaredField("down");
            java.lang.reflect.Field l = zombie.input.GameKeyboard.class.getDeclaredField("lastDown");
            d.setAccessible(true);
            l.setAccessible(true);
            keyDown = (boolean[])d.get(null);
            keyLast = (boolean[])l.get(null);
         } catch (Throwable t) {
            keyFailed = true;
            Log.info("luaTick: input wake cannot read the keyboard state (" + t + "): input pollers run every frame");
         }
      }
      // isKeyDown reads false while a text box takes the typing: its start and end change what the pollers see too
      zombie.ui.UITextEntryInterface box = zombie.core.Core.currentTextEntryBox;
      boolean typing = box != null && box.isDoingTextEntry();
      boolean typingChanged = typing != lastTyping;
      lastTyping = typing;
      if (typingChanged || keyDown == null || keyLast == null || !Arrays.equals(keyDown, keyLast)) {
         return true;
      }
      boolean[] md = zombie.input.Mouse.buttonDownStates, mp = zombie.input.Mouse.buttonPrevStates;
      if (md == null || mp == null || !Arrays.equals(md, mp) || zombie.input.Mouse.wheelDelta != 0) {
         return true;
      }
      zombie.input.JoypadManager.Joypad[] j = zombie.input.JoypadManager.instance.joypads;
      for (int i = 0; i < j.length; i++) {
         if (j[i] != null) {
            return true;
         }
      }
      return false;
   }

   static String describe() {
      int[] pins = new int[5];
      int mods = 0, cheap = 0, warm = 0, spaced = 0;
      for (H h : ALL) {
         if (h.cls == CLS_MOD) {
            mods++;
         }
         if (h.pin >= 0) {
            pins[h.pin]++;
         }
         if (h.pin == PIN_NONE) {
            if (h.samples < WARM_CALLS) {
               warm++;
            } else if (h.ewmaNs < Config.LUA_TICK_CHEAP_US * 1000.0) {
               cheap++;
            } else if (h.interval > 1.0) {
               spaced++;
            }
         }
      }
      return String.format(Locale.ROOT, "luaTick: fast=%s hz=%d adaptive=%s comp=%d stagger=%s costAware=%s scan=%s cheapUs=%d stale=%d/%dms"
            + " | handlers=%d mods=%d pinned origin=%d user=%d known=%d scan=%d | free: warm=%d cheap=%d spaced=%d | period=%.2fms busy=%.0f%% budget=%s level=%s spikeDefers=%d emptySkips=%d wake=%s wakeRuns=%d inputFrames=%d",
            FAST, HZ, Config.LUA_TICK_ADAPTIVE, COMP, Config.LUA_TICK_STAGGER, Config.LUA_TICK_COST_AWARE, Config.LUA_TICK_SCAN,
            Config.LUA_TICK_CHEAP_US, Config.LUA_TICK_MAX_STALE_FRAMES, Config.LUA_TICK_MAX_STALE_MS, ALL.size(), mods, pins[PIN_ORIGIN],
            pins[PIN_USER], pins[PIN_KNOWN], pins[PIN_SCAN], warm, cheap, spaced, periodNs / 1e6, busy * 100.0,
            Double.isInfinite(budgetNs) ? "inf" : String.format(Locale.ROOT, "%.0fus", budgetNs / 1e3),
            Double.isInfinite(level) ? "inf" : String.format(Locale.ROOT, "%.0fus", level / 1e3), spikeDefers, emptySkips, INPUT_WAKE, wakeRuns, inputFrames);
   }
}
