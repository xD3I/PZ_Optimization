package pzopt;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * bakeScheduler (2026-09-24, the Rosewood 120 km/h drive): one budget for every chunk-level bake of a frame.
 *
 * <p>The drive baked nothing in 63 % of its frames and 10-120 levels in others (GPU 3-16 ms, render thread 5-12 ms in
 * one frame): each dirt reason had its own rule (creations counted by bakeBudget, lighting and redraw spread by their
 * budgets, cutaway / object / tree changes and first bakes of pooled chunk levels never held), so the kinds that arrive
 * together (a chunk row crossed every ~270 ms at 120 km/h, a building entering or leaving the collapse set while driving
 * past it) stacked. Here FBORenderCell offers every dirty on-screen level once a frame, before the chunk loop, with its
 * class and its chunk's distance to the camera character; {@link #plan} grants:
 * <ol>
 * <li>{@link #MUST}: an existing texture whose objects, items, trees or obscuring changed: always (a stale one would show
 *     the object neither baked nor drawn per frame);</li>
 * <li>up to {@code bakeArrivalQuota} never-textured levels (a hole on screen until baked), lowest level first, then
 *     nearest, outside the budget;</li>
 * <li>levels past their class's longest wait ({@code bakeMaxWait*}), by class then oldest first, up to
 *     {@code bakeFrameBudgetHard};</li>
 * <li>the rest by class (cutaway, never-textured, strong lighting, redraw, lighting drift), nearest first within a
 *     class, until {@code bakeFrameBudget} bakes are granted this frame.</li>
 * </ol>
 * With {@code bakeSmooth} the last step grants only the least number a frame that still meets every waiting level's longest
 * wait (demand bound, earliest deadline first), never more than the budget.
 * A level not granted keeps its previous texture on screen (FBORenderCell's deferral path); a never-textured one is not
 * drawn, which at the edge of the loaded grid looks like the unloaded ground beyond it. Waits are frames since the level
 * was first offered dirty.
 */
public final class BakeScheduler {
   public static final boolean ON = Config.BAKE_SCHEDULER && Overrides.enabled();
   public static final int MUST = 0, CUTAWAY = 1, ARRIVAL = 2, STRONG = 3, REDRAW = 4, LIGHT = 5, CLASSES = 6;
   /**
    * seamDirections: the redraw a neighbour loading on a side no seam of the level reads (north, west, the diagonals) sets
    * this pzopt dirty bit instead of DIRTY_REDRAW; stock never looks at it, the scheduler bakes it with lighting drift.
    */
   public static final long DIRTY_SEAM_LOW = 1L << 20;
   /**
    * pplUnseenAmbient: a square the player sees for the first time (LightingJNI's visibility bits leave 0) baked black until
    * now; its level re-bakes in the strong lighting class (a few frames), not with the lighting drift (up to bakeMaxWaitLight).
    */
   public static final long DIRTY_FIRST_SIGHT = 1L << 21;
   private static final String[] NAMES = {"must", "cutaway", "arrival", "strong", "redraw", "light"};
   private static final BakeScheduler[] players = new BakeScheduler[4];

   private Object[] chunk = new Object[256]; // IsoChunk, compared by identity
   private int[] level = new int[256];
   private int[] cls = new int[256];
   private int[] dist = new int[256];
   private int[] wait = new int[256];
   private long[] key = new long[256];
   private int n;
   private int frame;
   private final IdentityHashMap<Object, int[]> dirtySince = new IdentityHashMap<>(); // [li] = first frame offered dirty, 0 = none; [64] = last frame seen
   private final IdentityHashMap<Object, long[]> granted = new IdentityHashMap<>(); // [0] bit li = granted this frame; [1 + li] = its class
   private final IdentityHashMap<Object, int[]> arrivalGrant = new IdentityHashMap<>(); // [li] = frame of the last arrival grant (repeat stat)
   private final long[] used = new long[CLASSES], unused = new long[CLASSES];
   private long arrivalRepeats;
   // counters for the periodic log line
   private final long[] offered = new long[CLASSES], grantedN = new long[CLASSES], overdue = new long[CLASSES], waitSum = new long[CLASSES];
   private final int[] waitMax = new int[CLASSES];
   private long frames, deferredFrames, maxGranted, grantsMade, grantsUsed;

   public static BakeScheduler get(int playerIndex) {
      BakeScheduler s = players[playerIndex];
      if (s == null) {
         s = players[playerIndex] = new BakeScheduler();
      }
      return s;
   }

   /** Game thread, before the offers of a frame. */
   public void begin(int frameNo) {
      this.frame = frameNo;
      this.n = 0;
      // Chunk objects the streamer threads reused since the last frame leave the tables here, on the game thread: the
      // maps are plain IdentityHashMaps read and written by the plan, and a remove from WorldReuserThread / WorldStreamer
      // while the prune below iterated them was a ConcurrentModificationException out of the cell render (one frame
      // without a world composite, 2026-10-01 console of the maintainer's Riverside session).
      for (Object c; (c = REUSED.poll()) != null; ) {
         for (BakeScheduler s : players) {
            if (s != null) {
               s.dirtySince.remove(c);
               s.arrivalGrant.remove(c); // the repeat stat is per chunk position
            }
         }
      }
      for (long[] g : this.granted.values()) {
         long bits = g[0];
         while (bits != 0L) {
            int li = Long.numberOfTrailingZeros(bits);
            bits &= bits - 1;
            this.unused[(int)g[1 + li]]++;
         }
      }
      this.granted.clear();
      if ((frameNo & 255) == 0) {
         // chunks no longer offered (baked clean, gone off screen, pooled) for a few seconds leave the table
         this.dirtySince.values().removeIf(a -> frameNo - a[64] > 2000);
      }
   }

   public void offer(Object c, int lvl, int klass, int distance) {
      int li = lvl + 32;
      if (li < 0 || li >= 64) {
         return;
      }
      int[] since = this.dirtySince.computeIfAbsent(c, k -> new int[65]);
      if (since[li] == 0) {
         since[li] = this.frame;
      }
      since[64] = this.frame;
      if (this.n == this.chunk.length) {
         int m = this.n * 2;
         this.chunk = Arrays.copyOf(this.chunk, m);
         this.level = Arrays.copyOf(this.level, m);
         this.cls = Arrays.copyOf(this.cls, m);
         this.dist = Arrays.copyOf(this.dist, m);
         this.wait = Arrays.copyOf(this.wait, m);
         this.key = Arrays.copyOf(this.key, m);
      }
      int i = this.n++;
      this.chunk[i] = c;
      this.level[i] = lvl;
      this.cls[i] = klass;
      this.dist[i] = distance;
      this.wait[i] = this.frame - since[li];
      this.offered[klass]++;
   }

   private static int maxWait(int klass) {
      return switch (klass) {
         case CUTAWAY -> Config.BAKE_MAX_WAIT_CUTAWAY;
         case STRONG -> Config.BAKE_MAX_WAIT_STRONG;
         case ARRIVAL -> Config.BAKE_MAX_WAIT_ARRIVAL;
         case REDRAW -> Config.BAKE_MAX_WAIT_REDRAW;
         case LIGHT -> Config.BAKE_MAX_WAIT_LIGHT;
         default -> 0;
      };
   }

   private int budgetNow = -1;
   private int calmFrames;
   private long budgetSum;

   /**
    * bakeBudgetAdaptive: this frame's budget from the last frame's cost (AIMD): the game step without the limiter wait
    * (FrameCap.lastStepNs) or the render thread's acquire-to-swap time (Pacing.lastSubmitNs) above bakeBudgetHighPct (90) % of
    * the cap interval halves it (never under bakeFrameBudgetMin); both under bakeBudgetLowPct (60) % for 4 frames in a row add
    * one (never over bakeFrameBudget).
    * Uncapped or without the timings it is bakeFrameBudget.
    */
   public int budget(long intervalNs) {
      int b = adaptiveBudget(intervalNs);
      if (Config.BAKE_DEADLINE_PCT > 0 && intervalNs > 0L) {
         // bakeDeadlinePct: this step already used that share of its interval before the render phase (a heavy update:
         // chunk arrivals, lighting, Lua): the normal tier waits; must, arrival quota and overdue levels still go
         long elapsed = System.nanoTime() - Pacing.stepStartNs();
         if (elapsed > intervalNs * Config.BAKE_DEADLINE_PCT / 100) {
            this.deadlineFrames++;
            return 0;
         }
      }
      return b;
   }

   private long deadlineFrames;

   private int adaptiveBudget(long intervalNs) {
      int max = Config.BAKE_FRAME_BUDGET;
      if (!Config.BAKE_BUDGET_ADAPTIVE || intervalNs <= 0L) {
         return max;
      }
      int min = Math.min(max, Config.BAKE_FRAME_BUDGET_MIN);
      if (this.budgetNow < 0) {
         this.budgetNow = max;
      }
      long cost = Math.max(FrameCap.lastStepNs, Pacing.lastSubmitNs + (Config.BAKE_BUDGET_GPU && GtAb.on(GtAb.BAKE_GPU) ? Pacing.lastGpuPastNs : 0L)); // bakeBudgetGpu (f8-resid): the render side's span to GPU done
      if (cost > intervalNs * Config.BAKE_BUDGET_HIGH_PCT / 100) {
         this.budgetNow = Math.max(min, this.budgetNow / 2);
         this.calmFrames = 0;
      } else if (cost < intervalNs * Config.BAKE_BUDGET_LOW_PCT / 100) {
         if (++this.calmFrames >= 4) {
            this.calmFrames = 0;
            this.budgetNow = Math.min(max, this.budgetNow + 1);
         }
      } else {
         this.calmFrames = 0;
      }
      this.budgetSum += this.budgetNow;
      return this.budgetNow;
   }

   private final int[] slackHist = new int[1024];
   private long smoothSum;

   private int burstLeft, lastLevel = Integer.MIN_VALUE;
   private long burstFrames, burstGrants;

   /**
    * bakeLevelChangeFrames: the camera's level this frame. On a change (the player took the stairs) the next frames grant every
    * cutaway and never-textured level at once: the floor the player arrives on replaces the old one in one frame, as in stock,
    * instead of chunk by chunk with the old floor's textures in between (the stairs walk of 2026-09-25).
    */
   public void cameraLevel(int lvl) {
      if (lvl != this.lastLevel) {
         if (this.lastLevel != Integer.MIN_VALUE) {
            this.burstLeft = Config.BAKE_LEVEL_CHANGE_FRAMES;
         }
         this.lastLevel = lvl;
      }
   }

   /** Grants this frame's bakes (see the class comment); {@code budget} is this frame's bakeFrameBudget. */
   public void plan(int budget) {
      boolean hold = Config.DEV_BAKE_HOLD_FRAMES > 0 && this == players[0] && this.frame / Config.DEV_BAKE_HOLD_FRAMES % 2 == 1;
      if (Config.DEV_BAKE_HOLD_FRAMES > 0 && this == players[0]) {
         BakeLog.holdBlock(hold); // devBakeHoldFrames: this frame's block, for the timing row
      }
      boolean burst = this.burstLeft > 0;
      if (burst) {
         this.burstLeft--;
         this.burstFrames++;
      }
      int given = 0;
      int arrivals = 0;
      boolean smooth = Config.BAKE_SMOOTH;
      int[] h = this.slackHist;
      if (smooth) {
         Arrays.fill(h, 0);
      }
      // sort keys: tier (0 must, 1 arrival quota, 2 overdue, 3 normal), then within overdue the longest wait, else class then
      // distance (bakeSmooth: the frames left before the level's longest wait first, then class, then distance)
      for (int i = 0; i < this.n; i++) {
         int k = this.cls[i];
         long tier;
         long sub;
         if (k == MUST || burst && (k == CUTAWAY || k == ARRIVAL) && (Config.BAKE_LEVEL_CHANGE_RADIUS < 0 || this.dist[i] <= Config.BAKE_LEVEL_CHANGE_RADIUS)) {
            tier = 0;
            sub = this.dist[i];
            if (k != MUST) {
               this.burstGrants++;
            }
         } else if (k == ARRIVAL && Config.BAKE_ARRIVAL_QUOTA > 0) {
            tier = 1; // the arrival quota: ground level first (no upper floor floating over an unbaked ground), then the nearest
            sub = (long)(this.level[i] + 32) << 12 | Math.min(this.dist[i], 0xFFF);
         } else if (this.wait[i] >= maxWait(k)) {
            tier = 2;
            sub = (long)k << 12 | 0xFFFL - Math.min(this.wait[i], 0xFFF); // class first (an overdue arrival before an overdue redraw), then the longest wait
         } else if (smooth) {
            tier = 3;
            int slack = Math.min(maxWait(k) - this.wait[i], h.length - 1); // >= 1: not overdue
            h[slack]++;
            sub = (long)slack << 15 | (long)k << 12 | Math.min(this.dist[i], 0xFFF);
         } else {
            tier = 3;
            sub = (long)k << 12 | Math.min(this.dist[i], 0xFFF);
         }
         this.key[i] = tier << 48 | sub << 16 | i;
      }
      Arrays.sort(this.key, 0, this.n);
      int hard = Math.max(budget, Config.BAKE_FRAME_BUDGET_HARD);
      // bakeCapUs: the grants beyond the must class that fit the frame's bake time, from the measured cost of a grant
      int cap = Config.BAKE_CAP_US > 0 ? Math.max(1, (int)(Config.BAKE_CAP_US * 1000.0 / Math.max(20_000.0, this.grantNs))) : Integer.MAX_VALUE;
      if (Config.BAKE_CAP_US > 0) {
         hard = Math.max(hard, cap); // the cap is the burst limit now
         this.capSum += cap;
      }
      if (smooth) {
         // demand bound: the levels due within d frames need ceil(count / d) bakes a frame from now on; the largest over d
         // is the least rate that meets every deadline, so a backlog drains evenly instead of in budget-sized bursts
         int need = Config.BAKE_SMOOTH_MIN;
         int due = 0;
         for (int d = 1; d < h.length; d++) {
            due += h[d];
            if (due > 0) {
               need = Math.max(need, (due + d - 1) / d);
            }
         }
         budget = Math.min(budget, need);
         this.smoothSum += budget;
      }
      int slack = this.slackCap(); // bakeSlackCap: grants (all tiers but must) that still fit this step, MAX_VALUE when off
      int musts = 0;
      for (int j = 0; j < this.n; j++) {
         int i = (int)(this.key[j] & 0xFFFF);
         long tier = this.key[j] >>> 48;
         boolean grant = tier == 0 || tier == 1 && arrivals < Config.BAKE_ARRIVAL_QUOTA || tier == 2 && given < hard || tier == 3 && given < budget;
         if (grant && tier != 0 && given + arrivals >= cap) {
            grant = false; // bakeCapUs: this frame's bake time is spent; the rest keeps its order for the next plan
            this.capped++;
         }
         if (grant && tier >= 2 && given + arrivals + musts >= slack && given >= Config.BAKE_SLACK_MIN
               && !(tier == 2 && this.wait[i] >= 4 * Math.max(1, maxWait(this.cls[i])))) {
            grant = false; // bakeSlackCap: the step has no room left for it; a level 4x past its longest wait still goes
            this.slackHeld++;
         }
         if (tier == 0) {
            musts++;
         }
         if (hold && this.cls[i] != MUST) {
            grant = false; // devBakeHoldFrames: a hold block bakes only what must change at once
         }
         if (!grant) {
            continue;
         }
         if (tier == 1) {
            arrivals++; // outside the budget: a never-textured level is a hole on screen
         } else {
            given++;
         }
         if (GtAb.TIMING && this == players[0]) {
            GtAb.add(GtAb.C_BAKE_T0 + (int)tier, 1L); // Louisville 120 item 3 census: grants by tier
         }
         int k = this.cls[i];
         this.grantedN[k]++;
         if (tier == 2) {
            this.overdue[k]++;
         }
         this.waitSum[k] += this.wait[i];
         this.waitMax[k] = Math.max(this.waitMax[k], this.wait[i]);
         long[] g = this.granted.computeIfAbsent(this.chunk[i], c -> new long[65]);
         g[0] |= 1L << (this.level[i] + 32);
         g[1 + this.level[i] + 32] = k;
         if (k == ARRIVAL) {
            int[] last = this.arrivalGrant.computeIfAbsent(this.chunk[i], c -> new int[64]);
            int li = this.level[i] + 32;
            if (last[li] != 0 && this.frame - last[li] < 2400) {
               this.arrivalRepeats++;
            }
            last[li] = this.frame;
         }
      }
      this.frames++;
      this.slackPlanGrants = given + arrivals + musts; // bakeSlackCap: this step's bakes, for the next step's rest estimate
      this.grantsMade += given + arrivals;
      if (given + arrivals < this.n) {
         this.deferredFrames++;
      }
      this.maxGranted = Math.max(this.maxGranted, given + arrivals);
      if (GtAb.TIMING && this == players[0]) {
         GtAb.add(GtAb.C_BAKE_OFFERED, this.n);
      }
      if (this == players[0]) {
         DynRes.onBakesPlanned(given + arrivals, this.n - given - arrivals, budget); // dynResBakeFeedforward: this frame's bakes, the backlog for the next
      }
   }

   private double grantNs = 250_000.0; // bakeCapUs: EWMA of the game thread's chunk-loop time per grant (a grant bakes its texture's levels)
   private long capped, capSum;

   // ---- bakeSlackCap (tail6 2026-10-14) ----
   private long slackPlanStep; // Pacing.stepStartNs of the last plan
   private long slackPlanOffsetNs; // its plan time after the step start
   private int slackPlanGrants; // its grants
   private double slackRestNs = 3_000_000.0; // EWMA: a step's time after the plan, its bakes excluded
   private long slackHeld, slackCapSum, slackCapFrames;

   /**
    * bakeSlackCap: how many grants this step still has room for: (interval - time used before the plan - the rest of the step
    * - bakeSlackMarginUs) / the measured cost of a grant. The rest of the step (after the plan, bakes excluded) is learned from
    * the steps before: their length (FrameCap.lastStepNs) less their plan offset less their grants' cost. MAX_VALUE when off,
    * uncapped or in the off half of a GtAb alternation.
    */
   private int slackCap() {
      long interval = Pacing.capIntervalNs();
      if (!Config.BAKE_SLACK_CAP || interval <= 0L || this != players[0]) {
         return Integer.MAX_VALUE;
      }
      long now = System.nanoTime();
      long step = Pacing.stepStartNs();
      if (this.slackPlanStep != 0L && step != this.slackPlanStep) {
         long rest = FrameCap.lastStepNs - this.slackPlanOffsetNs - (long)(this.slackPlanGrants * this.grantNs);
         if (rest > 0L && rest < 4L * interval) {
            this.slackRestNs += 0.1 * (rest - this.slackRestNs);
         }
      }
      this.slackPlanStep = step;
      this.slackPlanOffsetNs = now - step;
      if (!GtAb.on(GtAb.BAKE_SLACK)) {
         return Integer.MAX_VALUE;
      }
      double room = interval - (now - step) - this.slackRestNs - Config.BAKE_SLACK_MARGIN_US * 1000.0;
      int cap = (int)Math.max(0.0, Math.floor(room / Math.max(20_000.0, this.grantNs)));
      this.slackCapSum += cap;
      this.slackCapFrames++;
      return cap;
   }

   /** bakeCapUs, game thread: a chunk whose levels took {@code ns} in the chunk loop baked {@code grants} granted textures. */
   public void grantCost(long ns, int grants) {
      if (grants > 0 && ns > 0L) {
         this.grantNs += 0.1 * (ns / (double)grants - this.grantNs);
      }
   }

   /** Whether the level holds a grant this frame, without using it (the bake preparation asks first). */
   public boolean peek(Object c, int lvl) {
      long[] g = this.granted.get(c);
      return g != null && (g[0] & 1L << (lvl + 32)) != 0L;
   }

   /** renderOneLevel's decision for a level offered this frame; a true answer is counted as a used grant. */
   public boolean granted(Object c, int lvl) {
      long[] g = this.granted.get(c);
      long bit = 1L << (lvl + 32);
      if (g != null && (g[0] & bit) != 0L && g[1 + lvl + 32] > CUTAWAY && timeGuard()) {
         g[0] &= ~bit; // bakeTimeGuardPct: past the share of the step, a lower-class bake waits for the next plan
         this.guarded++;
         return false;
      }
      if (g != null && (g[0] & bit) != 0L) {
         g[0] &= ~bit; // once: a second ask this frame (none expected) is not a second bake
         this.grantsUsed++;
         this.used[(int)g[1 + lvl + 32]]++;
         return true;
      }
      return false;
   }

   private long guarded;

   /**
    * bakeTimeGuardPct (2026-10-05, Louisville 120 fps pass): at a frame cap, has this step already used that share of its
    * interval. A bake's preparation is ~0.3-0.5 ms of game thread and its draw lands on the GPU in the same frame, so the
    * arrival / strong / redraw / light classes stop there and are planned again next frame (their longest waits still apply).
    */
   static boolean timeGuard() {
      if (Config.BAKE_TIME_GUARD_PCT <= 0 || !Overrides.enabled() || !GtAb.on(GtAb.BAKE_GUARD)) {
         return false;
      }
      long interval = Pacing.capIntervalNs();
      return interval > 0L && System.nanoTime() - Pacing.stepStartNs() > interval * Config.BAKE_TIME_GUARD_PCT / 100;
   }

   /** The level's texture was baked: its wait starts again with its next dirt. */
   public void baked(Object c, int lvl) {
      int[] since = this.dirtySince.get(c);
      int li = lvl + 32;
      if (since != null && li >= 0 && li < 64) {
         since[li] = 0;
      }
   }

   /** Chunk objects reused for another position (IsoChunk.resetForStore, on the world streamer / reuser threads). */
   private static final java.util.concurrent.ConcurrentLinkedQueue<Object> REUSED = new java.util.concurrent.ConcurrentLinkedQueue<>();

   /**
    * A chunk object is being reused for another position. Called off the game thread (WorldReuserThread, WorldStreamer,
    * WorldGenerate): the removal is queued and done by every player's {@link #begin} on the game thread.
    */
   public static void chunkReused(Object c) {
      REUSED.add(c);
   }

   public String summary() {
      StringBuilder sb = new StringBuilder(" | bake scheduler: arrival repeats=").append(this.arrivalRepeats).append(" unused by class=");
      for (int k = 0; k < CLASSES; k++) {
         sb.append(k == 0 ? "" : "/").append(this.unused[k]);
      }
      sb.append(" frames=").append(this.frames).append(" held=").append(this.deferredFrames)
         .append(" max/frame=").append(this.maxGranted).append(" time-guarded=").append(this.guarded).append(" grants used=").append(this.grantsUsed).append('/').append(this.grantsMade)
         .append(" budget avg=").append(this.frames == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.1f", this.budgetSum / (double)this.frames))
         .append(" deadline holds=").append(this.deadlineFrames)
         .append(" smooth avg=").append(this.frames == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.2f", this.smoothSum / (double)this.frames))
         .append(" level-change frames=").append(this.burstFrames).append(" grants=").append(this.burstGrants);
      if (Config.BAKE_CAP_US > 0) {
         sb.append(" cap: grant us=").append(Math.round(this.grantNs / 1000.0)).append(" cap avg=")
            .append(this.frames == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.1f", this.capSum / (double)this.frames)).append(" held by cap=").append(this.capped);
         this.capped = this.capSum = 0L;
      }
      if (Config.BAKE_SLACK_CAP) {
         sb.append(" slack cap: grant us=").append(Math.round(this.grantNs / 1000.0)).append(" rest us=").append(Math.round(this.slackRestNs / 1000.0))
            .append(" cap avg=").append(this.slackCapFrames == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f", this.slackCapSum / (double)this.slackCapFrames))
            .append(" held=").append(this.slackHeld);
         this.slackHeld = this.slackCapSum = this.slackCapFrames = 0L;
      }
      for (int k = 0; k < CLASSES; k++) {
         if (this.offered[k] == 0) {
            continue;
         }
         sb.append(' ').append(NAMES[k]).append('=').append(this.grantedN[k]).append('/').append(this.offered[k]).append(" (overdue ")
            .append(this.overdue[k]).append(", wait avg ").append(this.grantedN[k] == 0 ? 0 : this.waitSum[k] / this.grantedN[k]).append(" max ")
            .append(this.waitMax[k]).append(')');
      }
      Arrays.fill(this.offered, 0L);
      Arrays.fill(this.used, 0L);
      Arrays.fill(this.unused, 0L);
      this.arrivalRepeats = 0L;
      Arrays.fill(this.grantedN, 0L);
      Arrays.fill(this.overdue, 0L);
      Arrays.fill(this.waitSum, 0L);
      Arrays.fill(this.waitMax, 0);
      this.frames = this.deferredFrames = this.maxGranted = this.grantsMade = this.grantsUsed = this.budgetSum = this.smoothSum = this.deadlineFrames = 0L;
      return sb.toString();
   }
}
