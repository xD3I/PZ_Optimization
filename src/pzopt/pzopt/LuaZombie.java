package pzopt;

import java.util.ArrayList;
import java.util.concurrent.atomic.LongAdder;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameTime;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;
import zombie.iso.IsoGridSquare;

/**
 * Mod Lua on the per-zombie update event (2026-10-25, worktree lua-zombie): {@code OnZombieUpdate} fires once per zombie
 * per update (IsoZombie.update), so a mod listening to it pays its handlers once per zombie per frame, which scales with the
 * frame rate and the horde (520-mod list, Rosewood spin: 3.9 ms a frame for 81 zombies, 20 handlers; nothing of the vanilla
 * Lua listens). The IsoZombie hook calls {@link #update}; every key off, that is the stock
 * {@code LuaEventManager.triggerEvent("OnZombieUpdate", z)} unchanged.
 *
 * <p>With a key on, this class dispatches the event itself (the same calls Event.trigger makes: LuaEventProfile.call, the
 * exception logger, the callback-removed index step, the same lock), one handler at a time, and decides per handler and
 * zombie whether the handler runs on this update:
 * <ul>
 * <li>{@code luaZombieHz}: a mod handler runs on a zombie at most N times a (real) second; the phase comes from the zombie's
 * arrival order (golden-ratio spread), so every frame carries an equal share of the zombies (Unreal's tick interval with a
 * random start offset; Unity's amortized update managers).</li>
 * <li>{@code luaZombieLod}: three tiers (near and on screen / on screen / off screen), each with its own rate
 * ({@code luaZombieLodNear}, {@code luaZombieLodHz1}, {@code luaZombieLodHz2}): Unreal's significance manager.</li>
 * <li>{@code luaZombieTimeComp}: while a deferred handler runs for zombie z, GameTime's multiplier getters (getMultiplier,
 * getTimeDelta, getGameWorldSecondsSinceLastUpdate, getTrueMultiplier, getMultipliedSecondsSinceLastUpdate ...) return the time
 * since that handler's last call on z, through UpdateBatch's per-thread perObjectMultiplier holder, the stock mechanism of the
 * tiered zombie updates.</li>
 * <li>handlers kept at the full rate: the game's and ours, the {@code luaZombieExempt} list and {@link #KNOWN}, and with
 * {@code luaZombieWriters=full} every handler {@link LuaZombieScan} finds writing the zombie; {@code learn} (default of the
 * scan) runs a writer at the rate but every update on a zombie it changed in its last call, for {@code luaZombieHotMs}.</li>
 * <li>{@code luaZombieCaptureDue}: a batched zombie with nothing due is not captured for UpdateBatch's game-thread replay.</li>
 * <li>{@code luaZombieBudgetUs}: the rate adapts so the deferred handlers' measured cost stays near this budget a frame.</li>
 * <li>{@code luaZombieCostShare}: with the budget, a deferred handler's interval grows with the square root of its cost a call
 * ({@link #shareMults}): the allocation with the shortest mean interval for the same cost.</li>
 * <li>{@code luaZombieCheapUs}: a handler cheaper than that a call runs every update.</li>
 * <li>{@code luaZombieDirtySkip} (risky): a due zombie whose observable state did not change since its last call is skipped.</li>
 * <li>{@code luaZombieBatchApi}: {@code Events.OnZombieUpdateBatch} (zombies, elapsed), once a frame, for mod authors.</li>
 * <li>{@code devLuaZombieCheck=N}: the dual-evaluation rig, see {@link LuaZombieCheck}.</li>
 * </ul>
 */
public final class LuaZombie {
   private LuaZombie() {
   }

   public static final String EVENT = "OnZombieUpdate";
   public static final String BATCH_EVENT = "OnZombieUpdateBatch";
   private static final String PROFILE_KEY = "Lua - " + EVENT;

   static final int HZ = Math.max(0, Config.LUA_ZOMBIE_HZ);
   static final boolean LOD = Config.LUA_ZOMBIE_LOD;
   static final boolean TIME_COMP = Config.LUA_ZOMBIE_TIME_COMP;
   static final boolean CAPTURE_DUE = Config.LUA_ZOMBIE_CAPTURE_DUE;
   static final boolean DIRTY_SKIP = Config.LUA_ZOMBIE_DIRTY_SKIP;
   static final boolean BATCH_API = Config.LUA_ZOMBIE_BATCH_API;
   static final int BUDGET_US = Config.LUA_ZOMBIE_BUDGET_US;
   static final int CHECK = Config.DEV_LUA_ZOMBIE_CHECK;
   static final String WRITERS = Config.LUA_ZOMBIE_WRITERS; // full | learn | watch | stagger
   static final long CHEAP_NS = Config.LUA_ZOMBIE_CHEAP_US * 1000L;
   // devLuaZombieVariants (dev, within-run comparison of rates): "hz[:lod][:b<us>][:watch][:n<squares>][:w]" items separated by
   // ';', e.g. "0;30;10;5:lod;b2000:lod", cycled every devLuaZombieCycleMs; "0" is the stock dispatch, b<us> a luaZombieBudgetUs,
   // watch = luaZombieWriters=watch, n = luaZombieLodNear, w = luaZombieCostShare.
   // pzopt-luazombie.out's ab column is then the variant index.
   static final String[] VARIANTS = Config.DEV_LUA_ZOMBIE_VARIANTS.isEmpty() ? new String[0] : Config.DEV_LUA_ZOMBIE_VARIANTS.split(";");
   static final boolean COST = Config.LUA_ZOMBIE_COST || BUDGET_US > 0 || CHEAP_NS > 0 || VARIANTS.length > 0;
   static final boolean STAGGER = HZ > 0 || LOD || BUDGET_US > 0 || VARIANTS.length > 0;
   /** Any key on: this class dispatches the event; off, the hook is the stock triggerEvent. */
   public static final boolean ACTIVE = STAGGER || DIRTY_SKIP || BATCH_API || CHECK > 0 || Config.LUA_ZOMBIE_COST || Config.LUA_ZOMBIE_DISPATCH;
   // the rate in force this frame (the keys, or the current variant)
   static volatile int vHz = HZ, vBudgetUs = BUDGET_US, vNear = Config.LUA_ZOMBIE_LOD_NEAR;
   static volatile boolean vLod = LOD, vStock, vWatch = WRITERS.equals("watch"), vShare = Config.LUA_ZOMBIE_COST_SHARE;
   static int variant = -1;
   private static final float[] variantScale = initScales();

   private static float[] initScales() {
      float[] f = new float[Math.max(1, VARIANTS.length)];
      java.util.Arrays.fill(f, 8f); // as budgetScale
      return f;
   }
   private static long cycleT0;

   /** Frame start: the variant of this frame (devLuaZombieVariants). */
   private static void pickVariant() {
      if (VARIANTS.length == 0) {
         return;
      }
      long now = System.currentTimeMillis();
      if (cycleT0 == 0L) {
         cycleT0 = now;
      }
      int v = (int)((now - cycleT0) / Math.max(50, Config.DEV_LUA_ZOMBIE_CYCLE_MS) % VARIANTS.length);
      if (v != variant) {
         int left = variant;
         String[] f = VARIANTS[v].trim().split(":");
         int hz = 0;
         try {
            hz = Integer.parseInt(f[0].trim());
         } catch (NumberFormatException e) {
            hz = 0;
         }
         boolean lod = false, watch = WRITERS.equals("watch"), share = false;
         int near = Config.LUA_ZOMBIE_LOD_NEAR;
         int budget = 0;
         for (int i = 1; i < f.length; i++) {
            String t = f[i].trim();
            if (t.equals("watch")) {
               watch = true;
            } else if (t.equals("w")) {
               share = true;
            } else if (t.startsWith("n")) {
               try {
                  near = Integer.parseInt(t.substring(1));
               } catch (NumberFormatException e) {
                  near = Config.LUA_ZOMBIE_LOD_NEAR;
               }
            } else if (t.equals("lod")) {
               lod = true;
            } else if (t.startsWith("b")) {
               try {
                  budget = Integer.parseInt(t.substring(1));
               } catch (NumberFormatException e) {
                  budget = 0;
               }
            }
         }
         if (f[0].trim().startsWith("b")) { // "b2000" alone
            try {
               budget = Integer.parseInt(f[0].trim().substring(1));
            } catch (NumberFormatException e) {
               budget = 0;
            }
         }
         vHz = hz;
         vLod = lod;
         vBudgetUs = budget;
         vWatch = watch;
         vNear = near;
         vShare = share;
         vStock = hz <= 0 && !lod && budget <= 0;
         if (left >= 0 && left < variantScale.length) {
            variantScale[left] = budgetScale; // the variant we leave keeps its converged scale for its next slice
         }
         variant = v;
         budgetScale = variantScale[v];
         stagEwma = -1;
      }
   }

   /**
    * Handlers read by hand (2026-10-25) that need every update, by "mod id" or "mod id|file part|function" ("*" = any origin):
    * <ul>
    * <li>FullBookcases' spike pit (KNXSpikePit.lua): fires when a zombie steps into a pit square, remembered per update; a
    * zombie crossing the square between two calls would walk over an armed pit. 0.4 us a call.</li>
    * <li>HopOverIt's fence topple (HopOverIt_ZombieTopple.lua): drives the zombie across a fence with setX / setY / setZ on a
    * timeline every update; at a rate the hop would stutter and start late. 0.3 us a call.</li>
    * </ul>
    * Read and left at the rate (the near tier and the learned mode cover them): the airborne-contact handlers of UH1,
    * BlackHawk, LittleBird and PlowAttachment's blade (setVehicle4TestCollision while a zombie is under the aircraft or in
    * front of the blade, always next to the player), ZombieVisibilityFix (staggers itself), the rest in LUA-ZOMBIE-LOG.md.
    */
   static final String[] KNOWN = {"FullBookcases|KNXSpikePit.lua|onZombieUpdate", "*|HopOverIt_ZombieTopple.lua"};

   // the GtAb within-run alternation: the "off" half takes the stock path (every handler, every update)
   static boolean abOn() {
      return !vStock && LuaZombieAb.on(LuaZombieAb.STAGGER);
   }

   // ---- the plan: this frame's handlers (game thread builds it, workers read it) ----

   static final class Handler {
      final LuaClosure fn;
      final String origin;
      final String where; // file:line function
      final int slot;
      boolean full; // every update
      String why = ""; // why full, or "rate"
      LuaZombieScan.Result scan;
      boolean learn; // a writer at the rate, every update on zombies it changed
      boolean watch; // luaZombieWriters=watch: learned through the write barrier (pzopt.LuaZombieWatch), any mod handler
      boolean snapOk; // learned through the before / after snapshot too (the scan found a writer or mod data)
      boolean cheap; // luaZombieCheapUs: its mean cost a call is below the line, so it runs every update
      // game thread counters
      long calls, skipped, ns, hotCalls, changed, wrote;
      double ewmaNs = -1;
      long frameNs;
      int mult = 1; // luaZombieCostShare: runs on every mult-th due update of a zombie (a power of two)
      double intTicks; // this frame: the intervals (ticks) of its deferred calls, summed, and their count (stale_ms column)
      int intN;

      Handler(LuaClosure fn, int slot) {
         this.fn = fn;
         this.slot = slot;
         this.origin = LuaOrigin.ofFunction(fn);
         String file = fn.prototype != null ? fn.prototype.filename : "?";
         int line = fn.prototype != null && fn.prototype.lines != null && fn.prototype.lines.length > 0 ? fn.prototype.lines[0] : 0;
         String name = fn.prototype != null && fn.prototype.name != null ? fn.prototype.name : "?";
         this.where = shortFile(file) + ":" + line + " " + name;
      }
   }

   static final class Plan {
      final Event event; // null: nobody listens to OnZombieUpdate
      final LuaClosure[] fns;
      final Handler[] hs;
      final boolean anyFull, anyStag, batch;
      final int gen;

      Plan(Event event, LuaClosure[] fns, Handler[] hs, boolean batch, int gen) {
         this.event = event;
         this.fns = fns;
         this.hs = hs;
         boolean f = false, s = false;
         for (Handler h : hs) {
            if (h.full) {
               f = true;
            } else {
               s = true;
            }
         }
         this.anyFull = f;
         this.anyStag = s;
         this.batch = batch;
         this.gen = gen;
      }

      boolean empty() {
         return this.hs.length == 0 && !this.batch;
      }
   }

   private static volatile Plan plan;
   private static int planGen;
   private static final java.util.IdentityHashMap<LuaClosure, Handler> known = new java.util.IdentityHashMap<>();
   private static int nextSlot;

   /** Game thread: the plan for the current callbacks (rebuilt when they changed). */
   static Plan refresh() {
      Event e = LuaEventManager.pzoptEvent(EVENT);
      Event b = BATCH_API ? LuaEventManager.pzoptEvent(BATCH_EVENT) : null;
      boolean batch = b != null && !b.callbacks.isEmpty();
      Plan p = plan;
      ArrayList<LuaClosure> cbs = e != null ? e.callbacks : null;
      int n = cbs != null ? cbs.size() : 0;
      if (p != null && p.event == e && p.batch == batch && p.fns.length == n) {
         boolean same = true;
         for (int i = 0; i < n; i++) {
            if (p.fns[i] != cbs.get(i)) {
               same = false;
               break;
            }
         }
         if (same) {
            return p;
         }
      }
      LuaClosure[] fns = new LuaClosure[n];
      Handler[] hs = new Handler[n];
      for (int i = 0; i < n; i++) {
         LuaClosure c = cbs.get(i);
         fns[i] = c;
         Handler h = known.get(c);
         if (h == null) {
            h = classify(new Handler(c, nextSlot++ & 63));
            known.put(c, h);
         }
         hs[i] = h;
      }
      p = new Plan(e, fns, hs, batch, ++planGen);
      plan = p;
      StringBuilder sb = new StringBuilder("lua zombie: ").append(n).append(" OnZombieUpdate handlers").append(batch ? " + batch listeners" : "");
      for (Handler h : hs) {
         sb.append("\n  ").append(h.full ? "full " : h.watch ? "watch" : h.learn ? "learn" : "rate ").append(' ').append(h.origin).append(' ').append(h.where).append(h.why.isEmpty() ? "" : " (" + h.why + ")");
      }
      Log.info(sb.toString());
      return p;
   }

   private static Handler classify(Handler h) {
      if (!h.origin.startsWith("mod:") && !h.origin.equals("other")) {
         h.full = true;
         h.why = h.origin + " handler";
         return h;
      }
      String id = h.origin.startsWith("mod:") ? h.origin.substring(4) : h.origin;
      String ex = exemptWhy(id, h);
      if (ex != null) {
         h.full = true;
         h.why = ex;
         return h;
      }
      h.scan = LuaZombieScan.scan(h.fn);
      h.snapOk = h.scan.writes() || h.scan.modData; // a learned handler is also checked by the before / after snapshot
      if (WRITERS.equals("watch")) { // every mod handler at the rate, every update on a zombie its last call wrote
         h.learn = true;
         h.watch = true;
         h.why = h.scan.writes() ? "writes " + h.scan.summary() + ", watched" : h.scan.modData ? "mod data, watched" : "watched";
         return h;
      }
      if (h.scan.writes()) {
         switch (WRITERS) {
            case "full" -> {
               h.full = true;
               h.why = "writes " + h.scan.summary();
            }
            case "learn" -> {
               h.learn = true;
               h.why = "writes " + h.scan.summary() + ", learned";
            }
            default -> h.why = "writes " + h.scan.summary() + ", at the rate";
         }
      }
      return h;
   }

   private static String exemptWhy(String id, Handler h) {
      for (String list : new String[] {Config.LUA_ZOMBIE_EXEMPT, String.join(",", KNOWN)}) {
         if (list == null || list.isEmpty()) {
            continue;
         }
         for (String e : list.split(",")) {
            e = e.trim();
            if (e.isEmpty()) {
               continue;
            }
            String[] f = e.split("\\|");
            if (!f[0].equals(id) && !f[0].equals("*")) {
               continue;
            }
            if (f.length > 1 && !h.where.contains(f[1])) {
               continue;
            }
            if (f.length > 2 && !h.where.endsWith(" " + f[2])) {
               continue;
            }
            return "exempt " + e;
         }
      }
      return null;
   }

   static String shortFile(String f) {
      if (f == null) {
         return "?";
      }
      int i = f.lastIndexOf("/media/lua/");
      return i >= 0 ? f.substring(i + 11) : f;
   }

   // ---- per-zombie state (the field on IsoZombie) ----

   public static final class State {
      float sched; // progress toward the next deferred call, a fraction of the period (a period change keeps the phase)
      float since; // ticks since the last deferred call (time compensation)
      float lastDt; // this update's ticks
      boolean due; // this update runs the deferred handlers
      long hot; // learned: handler slots that run every update on this zombie
      float hotLeft; // ticks the hot mask stays
      int planGen;
      int tier;
      int dueCount; // luaZombieCostShare: due updates so far (a handler of mult m runs on every m-th, by its slot's phase)
      float[] hSince; // luaZombieCostShare: per handler slot, the ticks of the due updates it sat out (time compensation)
      boolean fresh = true;
      int lastFrame = Integer.MIN_VALUE / 2;
      // batch API
      int batchFrame = -1;
      float batchSince;
      // dirty skip: the observable state at the last deferred call
      final LuaZombieCheck.Snap last = DIRTY_SKIP ? new LuaZombieCheck.Snap() : null;
      final float phase;

      State(float phase) {
         this.phase = phase;
      }

      /** Tests: a zombie at phase {@code phase}. */
      static State forTest(float phase) {
         return new State(phase);
      }
   }

   private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

   private static State state(IsoZombie z) {
      State s = z.pzoptLuaZ;
      if (s == null) {
         int k = SEQ.getAndIncrement();
         s = new State((float)((k * 0.6180339887498949) % 1.0));
         z.pzoptLuaZ = s;
      }
      return s;
   }

   // ---- rates ----

   private static volatile float[] periodTicks = {0f, 0f, 0f}; // per tier, in 60 Hz ticks of fpsMultiplier
   private static float budgetScale = 8f; // a budget starts stretched and comes down: the first frames after a load are not all due

   static float budgetScale() {
      return budgetScale;
   }

   /** The configured rate of a tier (0 = every update). */
   static int hzOf(int tier) {
      if (!vLod) {
         return vHz;
      }
      return switch (tier) {
         case 0 -> Config.LUA_ZOMBIE_LOD_HZ0;
         case 1 -> Config.LUA_ZOMBIE_LOD_HZ1;
         default -> Config.LUA_ZOMBIE_LOD_HZ2;
      };
   }

   /** LOD tier: 0 near a local player (luaZombieLodNear squares, on screen or not), 1 on screen, 2 off screen. */
   private static int tier(IsoZombie z) {
      if (!vLod) {
         return 0;
      }
      float near = vNear;
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         IsoPlayer p = IsoPlayer.players[i];
         if (p != null && !p.isDead()) {
            float dx = p.getX() - z.getX(), dy = p.getY() - z.getY();
            if (dx * dx + dy * dy <= near * near) {
               return 0;
            }
         }
      }
      IsoGridSquare sq = z.getCurrentSquare();
      return sq != null && sq.IsOnScreen() ? 1 : 2;
   }

   /** This update's share of a second in 60 Hz ticks (fpsMultiplier times the bucket's perObjectMultiplier), thread's view. */
   private static float ticksNow() {
      GameTime gt = GameTime.instance;
      return gt.getRealworldSecondsSinceLastUpdate() * 60.0F * UpdateBatch.pom(gt);
   }

   // ---- counters (per frame and session) ----
   static final LongAdder updates = new LongAdder(), dueN = new LongAdder(), idleN = new LongAdder(), dirtySkips = new LongAdder();
   static long frameDispatchNs, frameCalls, frameSkipped, frameStagNs, sessionCalls, sessionSkipped, frameNearCalls, frameHotCalls;
   static long replayNs; // the UpdateBatch replay's time in our dispatch this frame

   // ---- the hook ----

   /** IsoZombie.update's OnZombieUpdate (any thread: a frame worker inside UpdateBatch's flight, or the game thread). */
   public static void update(IsoZombie z) {
      if (!ACTIVE || !abOn()) {
         if (!ACTIVE) {
            LuaEventManager.triggerEvent(EVENT, z);
            return;
         }
         if (z.pzoptLuaZ != null) {
            z.pzoptLuaZ.since = 0f; // the A/B's off half calls every handler every update
         }
         updates.increment(); // the off half's updates too (pzopt-luazombie.out)
         stockTimed(z);
         return;
      }
      Plan p = plan;
      if (p == null || p.empty()) {
         stockTimed(z); // a listener added since the frame start: the stock call (silent-skipped on a worker)
         return;
      }
      updates.increment();
      State s = state(z);
      float dt = ticksNow();
      if (s.planGen != p.gen) {
         s.planGen = p.gen;
         s.hot = 0L;
      }
      s.lastFrame = frameNo; // (a zombie reused from the pool gets a new State: IsoZombie.resetForReuse)
      s.lastDt = dt;
      s.since += dt;
      if (s.hotLeft > 0f) {
         s.hotLeft -= dt;
         if (s.hotLeft <= 0f) {
            s.hot = 0L;
         }
      }
      boolean due;
      if ((vHz > 0 || vLod || vBudgetUs > 0) && (p.anyStag || p.batch)) {
         int t = tier(z);
         s.tier = t;
         due = advance(s, dt, periodTicks[t], t < 2 || !vLod); // a new zombie off screen starts on its phase, not at once
      } else {
         due = true;
      }
      if (due && DIRTY_SKIP && s.last != null && LuaZombieAb.on(LuaZombieAb.DIRTY) && LuaZombieCheck.Snap.sameAs(s.last, z)) {
         due = false;
         dirtySkips.increment();
      }
      s.due = due;
      if (due) {
         dueN.increment();
      }
      if (p.batch && due) {
         s.batchFrame = frameNo;
         s.batchSince = s.since;
      }
      boolean any = due || p.anyFull || s.hot != 0L || CHECK > 0;
      if (!any || p.event == null) {
         if (due) {
            s.since = 0f;
         }
         idleN.increment();
         if (!CAPTURE_DUE && p.event != null && UpdateBatch.onWorkerNow()) {
            UpdateBatch.captureLuaEvent(EVENT, z); // idea 6 off: captured anyway, the replay finds nothing to run
         }
         return;
      }
      if (UpdateBatch.onWorkerNow()) {
         UpdateBatch.captureLuaEvent(EVENT, z); // replayed through replay() on the game thread after the join
      } else {
         dispatch(z, s, p);
      }
   }

   /**
    * The schedule step of one update: {@code dt} ticks pass; true when the zombie's deferred handlers come due. A new zombie
    * starts at its phase share of the period (the golden-ratio sequence of arrivals), so with N zombies about N * dt / period
    * come due in every frame instead of all of them in one.
    */
   static boolean advance(State s, float dt, float period) {
      return advance(s, dt, period, true);
   }

   /**
    * {@code immediate}: a new zombie's first update runs every handler, as stock (first-sight setup: outfits, mod data); a new
    * zombie off screen (LOD tier 2) instead starts at its phase share of the period, so a horde arriving off screen does not
    * run every handler of every zombie in one frame.
    */
   static boolean advance(State s, float dt, float period, boolean immediate) {
      if (s.fresh) {
         s.fresh = false;
         s.sched = s.phase;
         if (immediate) {
            return true;
         }
      }
      if (period <= 0f) {
         return true; // every update
      }
      // progress as a fraction of the period: when the budget changes the period, each zombie keeps its place in it
      // (counted in ticks, a shorter period would make every zombie past it due in the same frame)
      s.sched += dt / period;
      if (s.sched < 1f) {
         return false;
      }
      s.sched -= 1f;
      if (s.sched >= 1f) {
         s.sched %= 1f; // no catch-up: one call however long the gap
      }
      return true;
   }

   /** The stock dispatch, timed on the game thread (the A/B's off half: its dispatch_ns column is the stock handlers' time). */
   private static void stockTimed(Object z) {
      if (!LuaZombieOut.ON || UpdateBatch.onWorkerNow()) {
         LuaEventManager.triggerEvent(EVENT, z); // captured: timed in replay()
         return;
      }
      long t0 = System.nanoTime();
      LuaEventManager.triggerEvent(EVENT, z);
      frameDispatchNs += System.nanoTime() - t0;
   }

   /** UpdateBatch's game-thread replay of a captured OnZombieUpdate. */
   public static void replay(Object zo) {
      if (!(zo instanceof IsoZombie z) || !ACTIVE || !abOn()) {
         stockTimed(zo);
         return;
      }
      State s = z.pzoptLuaZ;
      Plan p = plan;
      if (s == null || p == null || p.event == null || s.planGen != p.gen) {
         stockTimed(z);
         return;
      }
      long t0 = System.nanoTime();
      dispatch(z, s, p);
      replayNs += System.nanoTime() - t0;
   }

   private static Object[][] args = new Object[4][1];
   private static int argDepth;

   /** Game thread: Event.trigger's loop over OnZombieUpdate's handlers, each run or deferred by the plan. */
   static void dispatch(IsoZombie z, State s, Plan p) {
      long t0 = System.nanoTime();
      Event e = p.event;
      boolean due = s.due;
      synchronized (LuaEventManager.pzoptEventLock()) {
         if (LuaManager.env == null || e.callbacks.isEmpty()) {
            if (due) {
               s.since = 0f;
            }
            return;
         }
         if (DebugOptions.instance.checks.slowLuaEvents.getValue()) {
            if (due) {
               s.since = 0f;
            }
            LuaEventManager.triggerEvent(EVENT, z); // the debug checks' slow-event timing: the stock loop, every handler
            return;
         }
         if (argDepth == args.length) {
            args = java.util.Arrays.copyOf(args, argDepth * 2);
            for (int i = argDepth; i < args.length; i++) {
               args[i] = new Object[1];
            }
         }
         Object[] a = args[argDepth++];
         a[0] = z;
         float[] pomH = UpdateBatch.pomHolder();
         float pomSaved = pomH[0];
         float basePom = UpdateBatch.pom(GameTime.instance);
         boolean check = CHECK > 0 && Math.floorMod(z.getID() + frameNo, CHECK) == 0;
         boolean share = vShare && due && !vStock;
         try {
            ArrayList<LuaClosure> cbs = e.callbacks;
            for (int n = 0; n < cbs.size(); n++) {
               LuaClosure c = cbs.get(n);
               Handler h = n < p.fns.length && p.fns[n] == c ? p.hs[n] : find(p, c);
               boolean learnH = h != null && !h.full && (h.learn || vWatch); // vWatch: every mod handler learned through the write barrier
               boolean hot = learnH && (s.hot & 1L << h.slot) != 0L;
               boolean every = h == null || h.full || hot || h.cheap && LuaZombieAb.on(LuaZombieAb.CHEAP); // luaZombieCheapUs: a handler cheaper than that a call is not worth deferring
               boolean run = every || due;
               float since = s.since; // the ticks since this handler's last call on this zombie
               if (run && !every && share) {
                  int m = Math.min(h.mult, multCap[s.tier]);
                  if (s.hSince == null) {
                     s.hSince = new float[64];
                  }
                  since += s.hSince[h.slot];
                  run = m <= 1 || (s.dueCount + h.slot & m - 1) == 0;
                  s.hSince[h.slot] = run ? 0f : since;
               }
               if (!run && !check) {
                  h.skipped++;
                  frameSkipped++;
                  continue;
               }
               boolean deferred = !every; // runs at the rate: time-compensated
               if (deferred && run) {
                  h.intTicks += since;
                  h.intN++;
                  if (TIME_COMP && s.lastDt > 0f) {
                     pomH[0] = basePom * (since / s.lastDt);
                  }
               }
               boolean snap = h != null && (learnH && h.snapOk && run || !run && check);
               boolean watch = learnH && vWatch && run;
               Object watchSaved = null;
               int writesSaved = 0;
               if (watch) {
                  watchSaved = LuaZombieWatch.target;
                  writesSaved = LuaZombieWatch.writes;
                  LuaZombieWatch.target = z;
                  LuaZombieWatch.writes = 0;
               }
               if (snap) {
                  LuaZombieCheck.before(z);
               }
               long c0 = COST ? System.nanoTime() : 0L;
               zombie.GameProfiler.ProfileArea pa = null;
               try {
                  pa = zombie.GameProfiler.getInstance().profile(PROFILE_KEY); // as Event.trigger: the GameProfiler's "Lua - OnZombieUpdate" area
                  pzopt.LuaEventProfile.call(LuaManager.caller, c, a, EVENT);
               } catch (Exception ex) {
                  ExceptionLogger.logException(ex);
               } finally {
                  if (pa != null) {
                     pa.close();
                  }
                  pomH[0] = pomSaved;
               }
               boolean wrote = false;
               if (watch) {
                  wrote = LuaZombieWatch.writes > 0;
                  LuaZombieWatch.target = watchSaved;
                  LuaZombieWatch.writes = writesSaved;
                  if (wrote) {
                     h.wrote++;
                  }
               }
               if (COST && h != null) {
                  long d = System.nanoTime() - c0;
                  h.ns += d;
                  h.frameNs += d;
                  h.ewmaNs = h.ewmaNs < 0 ? d : h.ewmaNs * 0.98 + d * 0.02;
                  if (CHEAP_NS > 0 && h.calls >= 200 && !h.full) {
                     h.cheap = h.ewmaNs < CHEAP_NS * (h.cheap ? 1.5 : 1.0); // hysteresis: a handler near the line does not flip every call
                  }
                  if (deferred && !(vLod && s.tier == 0)) { // the cost the budget can stretch (not the near tier's)
                     frameStagNs += d;
                  }
               }
               frameCalls++;
               if (vLod && s.tier == 0) {
                  frameNearCalls++;
               } else if (hot) {
                  frameHotCalls++;
               }
               if (h != null) {
                  h.calls++;
                  if (hot) {
                     h.hotCalls++;
                  }
               }
               int diff = snap ? LuaZombieCheck.after(z, h, !run) : 0;
               if (learnH && (diff != 0 && run || wrote)) {
                  h.changed++;
                  s.hot |= 1L << h.slot;
                  s.hotLeft = Config.LUA_ZOMBIE_HOT_MS * 0.06F; // ms -> 60 Hz ticks
               }
               if (!(n < cbs.size() && cbs.get(n) == c) && !cbs.contains(c)) { // Event.trigger's removed-callback step; still at n = still listed
                  n--;
               }
            }
         } finally {
            a[0] = null;
            argDepth--;
            if (due) {
               s.since = 0f;
               s.dueCount++;
               if (DIRTY_SKIP && s.last != null) {
                  s.last.take(z);
               }
            }
         }
      }
      frameDispatchNs += System.nanoTime() - t0;
   }

   private static Handler find(Plan p, LuaClosure c) {
      for (int i = 0; i < p.fns.length; i++) {
         if (p.fns[i] == c) {
            return p.hs[i];
         }
      }
      return null; // added during this frame: runs every update until the next plan
   }

   // ---- frame ----

   static volatile int frameNo;
   // the 10 s per-handler summary in the console: measurement runs only (a player's console gets the plan line once)
   private static final boolean SUMMARY = Config.LUA_ZOMBIE_COST || LuaZombieOut.ON;
   private static long lastLog;
   private static final ArrayList<IsoZombie> batchList = new ArrayList<>();
   private static final ArrayList<Double> batchSince = new ArrayList<>();

   /** Pacing.stepStart (every frame step, game thread, before the simulation): the plan and the rates for this frame. */
   public static void frameStart() {
      if (!ACTIVE) {
         LuaZombieOut.frame(); // luaZombieOut alone: the replay's columns of the stock path
         frameNo++; // LuaCollide's frame
         return;
      }
      batchFire(); // the batch API: the zombies that came due in the frame that just ended
      frameStagNsLast = frameStagNs;
      LuaZombieOut.frame(); // the per-frame row of the frame that just ended
      frameNo++;
      if (LuaManager.env == null) {
         return;
      }
      pickVariant();
      Plan p = refresh();
      if (STAGGER) {
         float ft = GameTime.instance != null ? GameTime.instance.getRealworldSecondsSinceLastUpdate() * 60.0F : 1f;
         if (ft > 0f && ft < 600f) {
            frameTicks = frameTicks < 0f ? ft : frameTicks * 0.9F + ft * 0.1F;
         }
         float scale = 1f;
         if (vBudgetUs > 0) {
            scale = adaptBudget();
         } else {
            budgetScale = 1f;
         }
         periodTicks = periods(scale, Math.max(1f, frameTicks), vLod);
         shareMults(p);
      }
      if (SUMMARY) {
         long now = System.currentTimeMillis();
         if (now - lastLog > 10000L) {
            lastLog = now;
            logSummary(p);
         }
      }
   }

   static final int[] multCap = {1, 1, 1};

   /**
    * luaZombieCostShare (idea 11): the deferred handlers' intervals in proportion to the square root of their cost a call.
    * For a cost budget B = sum(c_h / T_h), the intervals that minimise the handlers' mean interval sum(T_h) are T_h ~ sqrt(c_h)
    * (Lagrange: c_h / T_h^2 equal for all); the budget controller then scales them all together. Quantised to powers of two
    * of the cheapest deferred handler's interval (1, 2, 4, 8 due updates of a zombie), never past luaZombieMaxGapMs, never on
    * a tier that runs every update (the near tier).
    */
   static void shareMults(Plan p) {
      if (p == null) {
         return;
      }
      float maxGap = Math.max(1f, Config.LUA_ZOMBIE_MAX_GAP_MS * 0.06F);
      for (int t = 0; t < 3; t++) {
         float pt = periodTicks != null && t < periodTicks.length ? periodTicks[t] : 0f;
         int cap = 1;
         if (vShare && pt > 0f && !(vLod && t == 0)) {
            while (cap < 8 && pt * cap * 2 <= maxGap) {
               cap *= 2;
            }
         }
         multCap[t] = cap;
      }
      double cmin = Double.MAX_VALUE;
      for (Handler h : p.hs) {
         if (!h.full && h.calls >= 100 && h.ewmaNs > 0) {
            cmin = Math.min(cmin, h.ewmaNs);
         }
      }
      for (Handler h : p.hs) {
         h.mult = vShare && cmin < Double.MAX_VALUE && !h.full && h.calls >= 100 && h.ewmaNs > 0 ? shareMult(h.ewmaNs / cmin, h.mult) : 1;
      }
   }

   /** The power of two nearest sqrt(costRatio), 1..8, with a hysteresis of a quarter octave round the current one. */
   static int shareMult(double costRatio, int current) {
      double l = 0.5 * Math.log(Math.max(1.0, costRatio)) / Math.log(2.0); // log2(sqrt(ratio))
      int cur = Integer.numberOfTrailingZeros(Math.max(1, current));
      if (Math.abs(l - cur) <= 0.75) {
         return 1 << Math.min(3, cur);
      }
      return 1 << (int)Math.max(0, Math.min(3, Math.round(l)));
   }

   /** LuaZombieOut, frame start: the mean over the deferred handlers of their mean interval in ms in the frame that ended. */
   static double frameStaleMs() {
      Plan p = plan;
      if (p == null) {
         return 0.0;
      }
      double sum = 0.0;
      int n = 0;
      for (Handler h : p.hs) {
         if (h.intN > 0) {
            sum += h.intTicks / h.intN;
            n++;
         }
         h.intTicks = 0.0;
         h.intN = 0;
      }
      return n == 0 ? 0.0 : sum / n / 0.06;
   }

   private static double stagEwma = -1;
   static float frameTicks = -1f; // the frame step in 60 Hz ticks (EWMA)

   /**
    * The period of each tier in 60 Hz ticks: 60 / rate, or for a rate of 0 (every update) 0, or with a budget scale above 1
    * the frame step times the scale; tiers the budget stretches (all, or with LOD all but the near tier) never beyond
    * luaZombieMaxGapMs.
    */
   static float[] periods(float scale, float frameTicks, boolean lod) {
      float maxGap = Math.max(1f, Config.LUA_ZOMBIE_MAX_GAP_MS * 0.06F);
      float[] pt = new float[3];
      for (int t = 0; t < 3; t++) {
         int hz = hzOf(t);
         float base = hz > 0 ? 60.0F / hz : 0f;
         boolean stretch = scale > 1f && !(lod && t == 0);
         if (stretch) {
            float p = (base > 0f ? base : frameTicks) * scale;
            pt[t] = Math.max(base, Math.min(maxGap, p));
         } else {
            pt[t] = base;
         }
      }
      return pt;
   }

   /**
    * luaZombieBudgetUs: the deferred handlers' cost a frame is proportional to 1 / interval, so the next scale is this scale
    * times cost / budget (EWMA of the cost, at most x1.5 or /1.5 a frame, never below 1: the budget never raises a rate).
    */
   private static float adaptBudget() {
      double ns = frameStagNsLast;
      stagEwma = stagEwma < 0 ? ns : stagEwma * 0.7 + ns * 0.3;
      double budget = vBudgetUs * 1000.0;
      double target = budgetScale * (stagEwma / budget);
      target = Math.max(budgetScale / 1.5, Math.min(budgetScale * 1.5, target));
      float maxScale = 1f + Config.LUA_ZOMBIE_MAX_GAP_MS * 0.06F / Math.max(0.25F, frameTicks);
      budgetScale = (float)Math.max(1.0, Math.min(maxScale, target));
      return budgetScale;
   }

   static long frameStagNsLast;

   /**
    * Frame start, game thread: the batch API's call ({@code Events.OnZombieUpdateBatch(zombies, elapsed)}) with the zombies
    * that came due in the frame that just ended, in the cell's zombie list order; elapsed = real seconds (times the bucket's
    * multiplier) since that zombie's last batch call.
    */
   private static void batchFire() {
      Plan p = plan;
      if (!BATCH_API || p == null || !p.batch || zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
         return;
      }
      ArrayList<IsoZombie> list = zombie.iso.IsoWorld.instance.currentCell.getZombieList();
      batchList.clear();
      batchSince.clear();
      int f = frameNo;
      for (int i = 0; i < list.size(); i++) {
         IsoZombie z = list.get(i);
         State s = z.pzoptLuaZ;
         if (s != null && s.batchFrame == f) {
            batchList.add(z);
            batchSince.add((double)(s.batchSince / 60.0F)); // real seconds (x the bucket's multiplier) since its last batch
            if (p.event == null || !p.anyStag) {
               s.since = 0f;
            }
         }
      }
      if (!batchList.isEmpty()) {
         long t0 = System.nanoTime();
         LuaEventManager.triggerEvent(BATCH_EVENT, batchList, batchSince);
         batchNs += System.nanoTime() - t0;
         batchCalls++;
      }
   }

   static long batchNs, batchCalls;

   private static void logSummary(Plan p) {
      if (p == null || p.hs.length == 0) {
         return;
      }
      StringBuilder sb = new StringBuilder("lua zombie: updates=").append(updates.sum()).append(" due=").append(dueN.sum()).append(" idle=")
         .append(idleN.sum()).append(" dirtySkips=").append(dirtySkips.sum()).append(" calls=").append(sessionCalls).append(" skipped=")
         .append(sessionSkipped).append(" budgetScale=").append(String.format("%.2f", budgetScale));
      for (Handler h : p.hs) {
         sb.append(String.format("%n  %s %-6s calls=%d skipped=%d hot=%d changed=%d wrote=%d us/call=%.1f %s %s", h.full ? "full " : h.watch ? "watch" : h.learn ? "learn" : "rate ",
            h.cheap ? "cheap" : "", h.calls, h.skipped, h.hotCalls, h.changed, h.wrote, h.ewmaNs < 0 ? 0.0 : h.ewmaNs / 1000.0, h.origin, h.where));
      }
      Log.info(sb.toString());
      LuaZombieCheck.log();
   }

   /** LuaEventManager.register: our events in the Events table (the batch API), so a mod's Add finds them at load time. */
   public static void registerEvents() {
      if (BATCH_API) {
         LuaEventManager.AddEvent(BATCH_EVENT);
      }
   }
}
