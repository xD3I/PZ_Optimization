package pzopt;

import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * {@code luaLoadBudgetUs} (lua-stream, 2026-10-25): a streamed chunk's hand-off to the world, time-sliced.
 *
 * <p>A chunk the streamer finished joins the world on the game thread in {@code IsoChunk.doLoadGridsquare}: the border recalc,
 * vehicles and stories, then a loop over every square (objects added to the world, erosion, map objects, rats and the
 * {@code LoadGridsquare} Lua event, which every mod that touches the map listens to), pathfinding / populations / buildings,
 * the loot fill of every square, and the {@code LoadChunk} event. With a big mod list the event alone is ~60 handlers per
 * square (520 mods: 1.5 us a handler, ~90 us a square, 6-10 ms a chunk), all inside the frame the chunk joins in.
 *
 * <p>With the key on this class hands the queued chunks off itself (IsoChunkMap.updateInternal), one chunk at a time in the
 * streamer's order (nearest first): the hand-off runs doLoadGridsquare's first part ({@code pzoptLoadPre}), then the rest
 * of the chunk goes step by step, one square position of the square loop, the middle part, one square of the loot loop,
 * the last part with LoadChunk, while this frame's time budget lasts. Within the chunk every step runs in the stock order,
 * with the stock code, and the next chunk starts only when the last step of the previous one ran, so the order of all
 * hand-off work is the stock one; only the frame boundaries between the steps move (the game already leaves the frame a
 * chunk joins in to the streamer thread's timing). Until its last step ran the chunk is not drawn (loadedFrame / renderFrame,
 * which the renderer already gates on; set as stock does at the end), keeps preventHotSave (stock's "chunk is loading"), and
 * is finished at once when it is unloaded or saved, or when the whole queue is handed off at once (the loading screen).
 *
 * <p>The budget: at least {@code luaLoadBudgetUs}; the backlog (the rest of the loading chunk + the queued chunks at the
 * learned cost of a whole chunk) spread over {@code luaLoadDrainMs} of frames; at most {@code luaLoadMaxFramePct} of the
 * rest of the game step (its recent mean) unless the minimum is larger; {@code luaLoadBacklogMs} a frame while
 * {@code luaLoadBacklog} or more chunks wait (a world load, a teleport). At least one step a frame. At a frame cap the steps
 * also run in the step's slack ({@code luaLoadSlack}, pzopt.SlackWork). Single player only (a client's or server's
 * hand-off stays stock).
 */
public final class LuaStream {
   private LuaStream() {
   }

   public static final int EV_SQUARE = 0;
   public static final int EV_CHUNK = 1;

   private static final boolean KEY = Config.LUA_LOAD_STREAM && Config.LUA_LOAD_BUDGET_US > 0;
   private static final int P_SQUARES = 0, P_MID = 1, P_LOOT = 2, P_END = 3, P_BATCH = 4, P_CHUNKEV = 5;

   // the chunk loading step by step (game thread only)
   private static IsoChunk cur;
   private static int phase, cz, cx, cy;
   private static int curSquaresLeft; // square positions with objects left in the square loop (the backlog estimate)
   private static long curStartNs, curWorkNs;
   private static int curStartFrame;
   private static boolean driving; // set while this class hands a chunk off: doLoadGridsquare may stop after its first part
   private static Thread gameThread;
   private static int lastFrameNo = Integer.MIN_VALUE;
   private static boolean slackRegistered;
   private static boolean finishing, inStep;

   // learned costs (ns)
   private static final SlackWork.Cost PRE = new SlackWork.Cost(800_000.0, 0.5);
   private static final SlackWork.Cost SQUARE = new SlackWork.Cost(60_000.0, 0.5);
   private static final SlackWork.Cost EMPTY = new SlackWork.Cost(500.0, 0.0);
   private static final SlackWork.Cost MID = new SlackWork.Cost(600_000.0, 0.5);
   private static final SlackWork.Cost LOOT = new SlackWork.Cost(20_000.0, 0.5);
   private static final SlackWork.Cost END = new SlackWork.Cost(400_000.0, 0.5);
   private static final SlackWork.Cost CHUNK = new SlackWork.Cost(5_000_000.0, 0.0);
   private static final SlackWork.Cost BATCH = new SlackWork.Cost(300_000.0, 0.5);
   private static final SlackWork.Cost CHUNKEV = new SlackWork.Cost(100_000.0, 0.5);
   private static long chunkRenderDelay;
   private static final long BATCH_SLICE_NS = 250_000L;

   // the frame clock (between two calls of tick)
   private static long lastTickNs;
   private static double stepEmaNs = 16_666_667.0; // the game step
   private static double restEmaNs = 16_666_667.0; // the game step less this class's work
   private static long frameWorkNs; // this frame's hand-off work (in-frame and slack)

   // statistics
   public static long chunks, chunksDeferred, steps, slackSteps, forced, frames, framesWorked, workNs, budgetNs, latencyNs, latencyMaxNs;
   private static long lastLogNs;

   public static boolean on() {
      return KEY && Overrides.enabled() && !GameClient.client && !GameServer.server && (cur != null || LuaStreamAb.on(LuaStreamAb.BUDGET)); // the A/B switches between chunks
   }

   /** IsoChunk.doLoadGridsquare after its first part: true when the rest is left to this class (the hand-off stops there). */
   public static boolean defer(IsoChunk c) {
      if (!driving || cur != null || !on()) {
         return false;
      }
      cur = c;
      phase = P_SQUARES;
      cz = c.minLevel;
      cx = 0;
      cy = 0;
      curSquaresLeft = countSquares(c);
      curStartNs = System.nanoTime();
      curStartFrame = SlackWork.frame();
      c.loadedFrame = -1L; // not drawn until pzoptLoadEnd sets them as stock does
      c.renderFrame = Long.MAX_VALUE;
      chunksDeferred++;
      return true;
   }

   private static int countSquares(IsoChunk c) {
      int n = 0;
      for (int z = c.minLevel; z <= c.maxLevel; z++) {
         for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
               IsoGridSquare sq = c.getGridSquare(x, y, z);
               if (sq != null && !sq.getObjects().isEmpty()) {
                  n++;
               }
            }
         }
      }
      return n;
   }

   /** One step of the loading chunk; clears {@link #cur} after the last one. */
   private static void step() {
      inStep = true;
      try {
         step0();
      } finally {
         inStep = false;
      }
   }

   private static void step0() {
      IsoChunk c = cur;
      long t0 = System.nanoTime();
      switch (phase) {
         case P_SQUARES: {
            IsoGridSquare sq = c.getGridSquare(cx, cy, cz);
            boolean work = sq != null && (!sq.getObjects().isEmpty() || !sq.getStaticMovingObjects().isEmpty());
            c.pzoptLoadSquare(cx, cy, cz);
            if (++cy == 8) { // the stock loop: z outer, x, y inner; maxLevel read at every z as the loop condition does
               cy = 0;
               if (++cx == 8) {
                  cx = 0;
                  cz++;
               }
            }
            if (cz > c.maxLevel) {
               phase = LuaStreamDispatch.batchPending() ? P_BATCH : P_MID;
            }
            long ns = System.nanoTime() - t0;
            if (work) {
               SQUARE.learn(ns);
               if (curSquaresLeft > 0) {
                  curSquaresLeft--;
               }
            } else {
               EMPTY.learn(ns);
            }
            curWorkNs += ns;
            break;
         }
         case P_BATCH: // luaLoadBatch: the held LoadGridsquare events, a slice at a time
            if (LuaStreamDispatch.batchStep(t0 + BATCH_SLICE_NS)) {
               phase = P_MID;
            }
            learnStep(BATCH, t0);
            break;
         case P_MID:
            c.pzoptLoadMid();
            phase = P_LOOT;
            cz = c.minLevel;
            cx = 0;
            cy = 0;
            learnStep(MID, t0);
            if (Config.INSTRUMENT && recOn() && System.nanoTime() - t0 > 4_000_000L) { // which part of the indivisible middle (HandoffParts 5-7 are timed within this step)
               Log.info("lua stream: long middle step " + (System.nanoTime() - t0) / 1000 + " us at chunk " + c.wx + "," + c.wy + ": erosion / global objects "
                     + HandoffParts.CUR[5] / 1000 + " us, collision / populations / pathfinding " + HandoffParts.CUR[6] / 1000 + " us, room lights / buildings / visibility "
                     + HandoffParts.CUR[7] / 1000 + " us");
            }
            break;
         case P_LOOT:
            c.pzoptLoadLoot(cx, cy, cz);
            if (Config.INSTRUMENT && recOn() && System.nanoTime() - t0 > 4_000_000L) {
               IsoGridSquare lsq = c.getGridSquare(cx, cy, cz);
               int conts = 0;
               if (lsq != null) {
                  for (int i = 0; i < lsq.getObjects().size(); i++) {
                     conts += ((zombie.iso.IsoObject)lsq.getObjects().get(i)).getContainerCount();
                  }
               }
               Log.info("lua stream: long loot step " + (System.nanoTime() - t0) / 1000 + " us at square " + (c.wx * 8 + cx) + "," + (c.wy * 8 + cy) + "," + cz + ", " + conts + " containers");
            }
            if (++cy == 8) {
               cy = 0;
               if (++cx == 8) {
                  cx = 0;
                  cz++;
               }
            }
            if (cz > c.maxLevel) {
               phase = P_END;
            }
            learnStep(LOOT, t0);
            break;
         case P_CHUNKEV: { // luaLoadChunkSplit: one LoadChunk handler
            long ev = evBegin();
            boolean done;
            try {
               done = LuaStreamDispatch.chunkStep(c);
            } finally {
               evEnd(EV_CHUNK, ev);
            }
            learnStep(CHUNKEV, t0);
            if (!done) {
               break;
            }
            cur = null;
            c.loadedFrame = IsoWorld.instance.getFrameNo(); // drawn from now on, as stock's last part sets them
            c.renderFrame = c.loadedFrame + chunkRenderDelay;
            ready(c);
            CHUNK.learn(curWorkNs);
            long lat = System.nanoTime() - curStartNs;
            latencyNs += lat;
            latencyMaxNs = Math.max(latencyMaxNs, lat);
            break;
         }
         default: {
            if (Config.LUA_LOAD_CHUNK_SPLIT && LuaStreamDispatch.chunkHandlers() > 1) {
               LuaStreamDispatch.holdChunk = c; // pzoptLoadEnd's LoadChunk is left to the P_CHUNKEV steps
               try {
                  c.pzoptLoadEnd();
               } finally {
                  LuaStreamDispatch.holdChunk = null;
               }
               chunkRenderDelay = c.renderFrame - c.loadedFrame;
               c.loadedFrame = -1L; // not drawn before its last LoadChunk handler ran
               c.renderFrame = Long.MAX_VALUE;
               phase = P_CHUNKEV;
               learnStep(END, t0);
               break;
            }
            cur = null; // before the last part: LoadChunk handlers may unload / save chunks
            c.pzoptLoadEnd();
            learnStep(END, t0);
            CHUNK.learn(curWorkNs);
            long lat = System.nanoTime() - curStartNs;
            latencyNs += lat;
            latencyMaxNs = Math.max(latencyMaxNs, lat);
            break;
         }
      }
      steps++;
   }

   private static void learnStep(SlackWork.Cost cost, long t0) {
      long ns = System.nanoTime() - t0;
      cost.learn(ns);
      curWorkNs += ns;
   }

   private static long nextStepCostNs() {
      if (cur == null) {
         return PRE.estimate();
      }
      switch (phase) {
         case P_SQUARES:
            return SQUARE.estimate();
         case P_BATCH:
            return BATCH.estimate();
         case P_CHUNKEV:
            return CHUNKEV.estimate();
         case P_MID:
            return MID.estimate();
         case P_LOOT:
            return LOOT.estimate();
         default:
            return END.estimate();
      }
   }

   /** The loading chunk's work left, estimated. */
   private static double curLeftNs() {
      if (cur == null) {
         return 0.0;
      }
      double loot = LOOT.estimate() * 64.0 * Math.max(1, cur.maxLevel - cur.minLevel + 1) * 0.25;
      switch (phase) {
         case P_SQUARES:
            return curSquaresLeft * (double)SQUARE.estimate() + MID.estimate() + loot + END.estimate();
         case P_BATCH:
            return curSquaresLeft * (double)SQUARE.estimate() + MID.estimate() + loot + END.estimate();
         case P_CHUNKEV:
            return CHUNKEV.estimate() * Math.max(1, LuaStreamDispatch.chunkHandlers() - LuaStreamDispatch.chunkCursor);
         case P_MID:
            return MID.estimate() + loot + END.estimate();
         case P_LOOT:
            return loot * 0.5 + END.estimate();
         default:
            return END.estimate();
      }
   }

   /** Hands one queued chunk off (its first part runs now; the rest is left to the steps when it joined the world). */
   private static boolean handOff(IsoChunkMap cm, IsoChunk chunk) {
      long t0 = System.nanoTime();
      boolean joined;
      driving = true;
      try {
         joined = cm.pzoptHandOff(chunk);
      } finally {
         driving = false;
      }
      long ns = System.nanoTime() - t0;
      chunks++;
      if (cur == chunk) {
         PRE.learn(ns);
         curWorkNs = ns;
      } else if (joined) {
         CHUNK.learn(ns); // not deferred (should not happen while on): a whole hand-off
      }
      return joined;
   }

   // the chunks' arrival rate (queued for a hand-off), for the budget that keeps up with it
   private static int lastQueued = -1;
   private static long chunksAtLastFrame, lastArrivalNs;
   private static double arrivalsEma, arrivalNsEma; // both from 0: their ratio is the rate from the first frame on (the same weights)
   public static double lastDemandNs, lastBudgetNs;
   private static long frameBudget;

   private static void trackArrivals(int queued) {
      long now = System.nanoTime();
      if (lastQueued >= 0 && lastArrivalNs != 0L) {
         long handed = chunks - chunksAtLastFrame;
         double arrived = Math.max(0, queued - lastQueued + handed);
         double dt = Math.min(1e9, now - lastArrivalNs);
         // time-decayed sums (1d): every frame's arrivals and length count once, older ones fade with the time since; the
         // ratio is the arrival rate. (1c weighted each frame by its length in both: a frame's weight grew with dt, so the
         // short frames the arrivals land in counted less and the rate read ~3x low on the drive.)
         double decay = Math.exp(-dt / (Config.LUA_LOAD_DEMAND_WINDOW_MS * 1e6));
         arrivalsEma = arrivalsEma * decay + arrived;
         arrivalNsEma = arrivalNsEma * decay + dt;
      }
      lastQueued = queued;
      chunksAtLastFrame = chunks;
      lastArrivalNs = now;
   }

   /**
    * This frame's hand-off time: what keeps up with the arrivals (their rate over luaLoadDemandWindowMs times a chunk's learned
    * cost, per frame) plus the backlog spread over luaLoadDrainMs, capped at luaLoadBurstPct of the keep-up share (or
    * luaLoadMaxFramePct of the rest of the step if larger): a burst waits in the queue instead of lengthening frames, and a
    * cap never under the keep-up share cannot grow the queue for good; luaLoadBacklogMs a frame while luaLoadBacklog wait.
    */
   private static long frameBudgetNs(int queued) {
      double min = Config.LUA_LOAD_BUDGET_US * 1000.0;
      double demand = arrivalNsEma > 0.0 ? arrivalsEma / arrivalNsEma * CHUNK.estimate() * stepEmaNs : 0.0;
      lastDemandNs = demand;
      if (queued >= Config.LUA_LOAD_BACKLOG) {
         return lastBudget(Math.max(demand, Config.LUA_LOAD_BACKLOG_MS * 1_000_000.0));
      }
      double backlog = curLeftNs() + queued * (double)CHUNK.estimate();
      double drainFrames = Math.max(1.0, Config.LUA_LOAD_DRAIN_MS * 1_000_000.0 / Math.max(1_000_000.0, stepEmaNs));
      double rate = demand + backlog / drainFrames;
      // 1c capped this at luaLoadBurstPct of the keep-up share: with the drain term above it the queue could never shrink
      // but through the backlog mode (the drive sat at 150 queued, a 100 ms frame every 5th); the drain spreads a burst anyway
      double cap = Config.LUA_LOAD_MAX_FRAME_PCT > 0 ? Math.max(min, restEmaNs * Config.LUA_LOAD_MAX_FRAME_PCT / 100.0) : Double.MAX_VALUE;
      if (Config.LUA_LOAD_BURST_PCT > 0) {
         cap = Math.min(cap, Math.max(min, demand * Config.LUA_LOAD_BURST_PCT / 100.0 + backlog / drainFrames));
      }
      return lastBudget(Math.max(min, Math.min(rate, cap)));
   }

   private static long lastBudget(double ns) {
      lastBudgetNs = ns;
      return (long)ns;
   }

   /** IsoChunkMap.updateInternal with the key on: this frame's hand-offs and steps (true when a chunk joined the world). */
   public static boolean frame(IsoChunkMap cm) {
      if (!on()) {
         return false;
      }
      int f = IsoWorld.instance.getFrameNo();
      if (f == lastFrameNo) {
         return false; // once a frame (split screen: every player's map calls this)
      }
      lastFrameNo = f;
      gameThread = Thread.currentThread();
      if (Config.LUA_LOAD_SLACK && !slackRegistered) {
         slackRegistered = true;
         SlackWork.register(SLACK);
      }
      validate();
      trackFocus();
      int queued = IsoChunk.loadGridSquare.size();
      trackArrivals(queued);
      if (cur == null && queued == 0) {
         return false;
      }
      long budget = frameBudgetNs(queued);
      frameBudget = budget;
      budgetNs += budget;
      framesWorked++;
      long t0 = System.nanoTime();
      boolean joined = false;
      long ts = t0;
      while (true) {
         int ph;
         if (cur == null) {
            IsoChunk c = next();
            if (c == null) {
               break;
            }
            ph = -1;
            if (handOff(cm, c)) {
               joined = true;
            }
         } else {
            ph = phase;
            step();
         }
         long te = System.nanoTime();
         if (te - ts > frameMaxStepNs) {
            frameMaxStepNs = te - ts;
            frameMaxPhase = ph;
         }
         ts = te;
         if (te - t0 >= budget) {
            break;
         }
      }
      long ns = System.nanoTime() - t0;
      workNs += ns;
      frameWorkNs += ns;
      return joined;
   }

   // luaLoadNearestFirst: the player's motion (squares / ns), for the point the next pick aims at
   private static double focusX, focusY, velX, velY;
   private static long focusNs;
   private static final java.util.ArrayList<IsoChunk> scan = new java.util.ArrayList<>();
   public static long picksReordered, picksAged;

   private static void trackFocus() {
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.players[0];
      if (p == null) {
         focusNs = 0L;
         return;
      }
      long now = System.nanoTime();
      double x = p.getX(), y = p.getY();
      if (focusNs != 0L && now > focusNs) {
         double dt = now - focusNs;
         double vx = (x - focusX) / dt, vy = (y - focusY) / dt;
         if (Math.abs(vx) * 1e9 < 200.0 && Math.abs(vy) * 1e9 < 200.0) { // a teleport is no motion
            velX = velX * 0.8 + vx * 0.2;
            velY = velY * 0.8 + vy * 0.2;
         }
      }
      focusX = x;
      focusY = y;
      focusNs = now;
   }

   /** The next chunk to hand off: the streamer's order, or (luaLoadNearestFirst) the one nearest the player's look-ahead point. */
   private static IsoChunk next() {
      if (!Config.LUA_LOAD_NEAREST_FIRST || focusNs == 0L) {
         return IsoChunk.loadGridSquare.poll();
      }
      scan.clear();
      IsoChunk.loadGridSquare.copyTo(scan);
      int n = scan.size();
      if (n <= 1) {
         scan.clear();
         return IsoChunk.loadGridSquare.poll();
      }
      long oldest = scan.get(0).pzoptQueuedNs; // the queue is in arrival order: its head waited longest
      if (Config.LUA_LOAD_NEAREST_MAX_WAIT_MS > 0 && oldest != 0L && System.nanoTime() - oldest > Config.LUA_LOAD_NEAREST_MAX_WAIT_MS * 1_000_000L) {
         scan.clear();
         picksAged++;
         return IsoChunk.loadGridSquare.poll(); // a chunk waiting too long goes first, wherever it is (a small budget starved the far ones)
      }
      double ahead = Config.LUA_LOAD_LOOKAHEAD_MS * 1e6;
      double fx = (focusX + velX * ahead) / 8.0, fy = (focusY + velY * ahead) / 8.0; // chunks are 8 squares
      IsoChunk best = null;
      double bestD = Double.MAX_VALUE;
      for (int i = 0; i < n; i++) {
         IsoChunk c = scan.get(i);
         double dx = c.wx + 0.5 - fx, dy = c.wy + 0.5 - fy;
         double d = dx * dx + dy * dy;
         if (d < bestD) {
            bestD = d;
            best = c;
         }
      }
      boolean first = best == scan.get(0);
      scan.clear();
      if (best != null && IsoChunk.loadGridSquare.remove(best)) {
         if (!first) {
            picksReordered++;
         }
         return best;
      }
      return IsoChunk.loadGridSquare.poll();
   }

   /** A loading chunk that left the world without our hook seeing it (should not happen) is dropped. */
   private static void validate() {
      if (cur != null && IsoWorld.instance.currentCell != null && IsoWorld.instance.currentCell.getChunk(cur.wx, cur.wy) != cur) {
         Log.warn("lua stream: loading chunk " + cur.wx + "," + cur.wy + " is no longer in the world; dropped at phase " + phase);
         cur = null;
      }
   }

   /** IsoChunk.removeFromWorld / Save: a chunk still loading finishes first (game thread only). */
   public static void finishIfCurrent(IsoChunk c, String why) {
      if (cur != null && cur == c) {
         finish(why);
      }
   }

   /** IsoChunkMap.processAllLoadGridSquare: the loading chunk finishes before the queue is handed off at once. */
   public static void finishAll(String why) {
      if (cur != null) {
         finish(why);
      }
   }

   private static void finish(String why) {
      if (finishing || inStep) {
         return; // a step of the loading chunk unloaded or saved it: its loop goes on, as stock's loop goes on in that case
      }
      if (gameThread != null && Thread.currentThread() != gameThread) {
         Log.warn("lua stream: " + why + " of the loading chunk " + cur.wx + "," + cur.wy + " off the game thread (" + Thread.currentThread().getName() + "): left to the game thread");
         return;
      }
      forced++;
      long t0 = System.nanoTime();
      finishing = true;
      try {
         while (cur != null) {
            step();
         }
      } finally {
         finishing = false;
      }
      long ns = System.nanoTime() - t0;
      workNs += ns;
      frameWorkNs += ns;
      noteStep(ns, -3);
      if (Config.DEV_LUA_STREAM_LOG) {
         Log.info("lua stream: loading chunk finished at once for " + why + " (" + ns / 1000 + " us)");
      }
   }

   /** IsoChunkMap.Dispose: the world is gone. */
   public static void reset() {
      cur = null;
      driving = false;
      lastFrameNo = Integer.MIN_VALUE;
      lastQueued = -1;
      arrivalsEma = arrivalNsEma = 0.0;
      lastArrivalNs = 0L;
   }

   private static final SlackWork.Producer SLACK = new SlackWork.Producer() {
      public boolean pending() {
         return on() && (cur != null || !IsoChunk.loadGridSquare.isEmpty()) && IsoWorld.instance.currentCell != null;
      }

      public long nextCostNs() {
         return nextStepCostNs();
      }

      public int nextAgeFrames() {
         return cur == null ? 0 : SlackWork.frame() - curStartFrame;
      }

      public void runNext() {
         long t0 = System.nanoTime();
         if (cur == null) {
            IsoChunk c = next();
            if (c != null) {
               IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[0];
               if (handOff(cm, c)) {
                  for (int n = 0; n < zombie.characters.IsoPlayer.numPlayers; n++) { // as the in-frame hand-off's bChanged
                     IsoChunkMap m = IsoWorld.instance.currentCell.chunkMap[n];
                     if (m != null && !m.ignore) {
                        m.calculateZExtentsForChunkMap();
                     }
                  }
               }
            }
         } else {
            step();
         }
         slackSteps++;
         long ns = System.nanoTime() - t0;
         workNs += ns;
         frameWorkNs += ns;
         noteStep(ns, -4);
      }
   };

   // ---- per-frame record (the benchmark's <step>-luastream.csv) and event timers ----

   private static final long[] evNs = new long[2];
   private static final int[] evCalls = new int[2];
   private static int evDepth;

   public static long evBegin() {
      if (evDepth++ == 0) {
         LuaStreamJava.enter();
         return System.nanoTime();
      }
      return 0L;
   }

   public static void evEnd(int ev, long t0) {
      if (--evDepth == 0 && t0 != 0L) {
         evNs[ev] += System.nanoTime() - t0;
         evCalls[ev]++;
         LuaStreamJava.exit();
      }
   }

   // per chunk: queued for its hand-off -> ready to draw (the benchmark's <step>-chunkready.csv)
   private static final int RCAP = 1 << 16;
   private static final float[] ready = new float[RCAP * 3];
   private static int readyN;

   /** A chunk's hand-off is complete (stock: the end of doLoadGridsquare; with luaLoadChunkSplit: its last LoadChunk handler). */
   static void ready(IsoChunk c) {
      if (c == null) {
         return;
      }
      long q = c.pzoptQueuedNs;
      c.pzoptQueuedNs = 0L; // a pooled chunk's next life sets it again
      if (!recOn()) {
         readyN = 0;
         return;
      }
      if (q == 0L || readyN >= RCAP) {
         return;
      }
      long now = System.nanoTime();
      int i = readyN++ * 3;
      ready[i] = (float)((now - (recT0 == 0L ? now : recT0)) / 1e6);
      ready[i + 1] = (float)((now - q) / 1e6);
      ready[i + 2] = IsoChunk.loadGridSquare.size();
   }

   static void writeReadyCsv(java.io.File f) {
      if (readyN == 0) {
         return;
      }
      try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(f)))) {
         w.println("t_ms,ready_ms,queued");
         for (int k = 0; k < readyN; k++) {
            int i = k * 3;
            w.printf(java.util.Locale.ROOT, "%.3f,%.3f,%d%n", ready[i], ready[i + 1], (int)ready[i + 2]);
         }
      } catch (Throwable t) {
         Log.warn("lua stream: could not write " + f + ": " + t);
      }
      readyN = 0;
   }

   static Thread gameThread() {
      return gameThread;
   }

   private static final int CAP = 1 << 17;
   private static final float[] rec = new float[CAP * 14];
   private static int recN;
   private static long recT0;

   /** IsoChunkMap.updateInternal, every frame: closes the last frame's record. */
   public static void tick() {
      int f = IsoWorld.instance.getFrameNo();
      if (f == lastTickFrame) {
         return;
      }
      lastTickFrame = f;
      long now = System.nanoTime();
      if (lastTickNs != 0L) {
         long dt = now - lastTickNs;
         if (dt > 0L && dt < 2_000_000_000L) {
            stepEmaNs = stepEmaNs * 0.9 + dt * 0.1;
            restEmaNs = restEmaNs * 0.9 + Math.max(0L, dt - frameWorkNs) * 0.1;
         }
         if (recOn() && recN < CAP) {
            if (recN == 0) {
               recT0 = lastTickNs;
            }
            int i = recN++ * 14;
            rec[i] = (float)((lastTickNs - recT0) / 1e6);
            rec[i + 1] = (float)(dt / 1e6);
            rec[i + 2] = (float)(evNs[EV_SQUARE] / 1e6);
            rec[i + 3] = (float)(evNs[EV_CHUNK] / 1e6);
            rec[i + 4] = (float)(frameWorkNs / 1e6);
            rec[i + 5] = IsoChunk.loadGridSquare.size();
            rec[i + 6] = evCalls[EV_SQUARE];
            rec[i + 7] = cur == null ? -1 : curSquaresLeft;
            rec[i + 8] = LuaStreamDispatch.frameSkips;
            rec[i + 9] = (float)(frameBudget / 1e6);
            rec[i + 10] = (float)(lastDemandNs / 1e6);
            rec[i + 11] = visiblePending();
            rec[i + 12] = (float)(frameMaxStepNs / 1e6); // the frame's longest single step (one square, one phase, one hand-off start)
            rec[i + 13] = frameMaxPhase;
         } else if (!recOn()) {
            recN = 0;
         }
      }
      lastTickNs = now;
      if (gameThread == null) {
         gameThread = Thread.currentThread();
      }
      LuaStreamJava.maybeLog(now, false);
      frames++;
      evDepth = 0; // an event that threw past its evEnd ends with the frame
      LuaStreamJava.exit();
      evNs[0] = evNs[1] = 0L;
      evCalls[0] = evCalls[1] = 0;
      LuaStreamDispatch.frameSkips = 0L;
      frameBudget = 0L;
      frameWorkNs = 0L;
      frameMaxStepNs = 0L;
      frameMaxPhase = -2;
      if (Config.DEV_LUA_STREAM_LOG && now - lastLogNs > 10_000_000_000L) {
         lastLogNs = now;
         Log.info("lua stream: chunks " + chunks + " deferred " + chunksDeferred + " steps " + steps + " (slack " + slackSteps + ") forced " + forced
               + " work " + workNs / 1_000_000L + " ms over " + framesWorked + " frames, budget mean " + (framesWorked > 0 ? budgetNs / framesWorked / 1000 : 0)
               + " us, chunk latency mean " + (chunksDeferred > 0 ? latencyNs / chunksDeferred / 1_000_000L : 0) + " ms max " + latencyMaxNs / 1_000_000L + " reordered picks " + picksReordered + " aged picks " + picksAged
               + " ms; costs us: pre " + PRE.estimate() / 1000 + " square " + SQUARE.estimate() / 1000 + " mid " + MID.estimate() / 1000 + " loot " + LOOT.estimate() / 1000
               + " end " + END.estimate() / 1000 + " chunk " + CHUNK.estimate() / 1000 + "; step " + (long)(stepEmaNs / 1000) + " us rest " + (long)(restEmaNs / 1000) + " us");
      }
   }

   private static int lastTickFrame = Integer.MIN_VALUE;
   private static final java.util.ArrayList<IsoChunk> visScan = new java.util.ArrayList<>();

   /** Chunks on screen that wait for their hand-off or are still loading (not drawn): the holes chunk arrival leaves. */
   private static int visiblePending() {
      int n = 0;
      try {
         visScan.clear();
         IsoChunk.loadGridSquare.copyTo(visScan);
         if (cur != null) {
            visScan.add(cur);
         }
         for (int k = 0; k < visScan.size(); k++) {
            IsoChunk c = visScan.get(k);
            if (c != null && c.IsOnScreen(false)) {
               n++;
            }
         }
      } catch (Throwable t) {
         n = -1;
      }
      visScan.clear();
      return n;
   }

   private static long frameMaxStepNs;
   private static int frameMaxPhase = -2;

   /** The frame's longest single piece of hand-off work and where it ran (-3 a forced finish, -4 a slack step). */
   private static void noteStep(long ns, int phase) {
      if (ns > frameMaxStepNs) {
         frameMaxStepNs = ns;
         frameMaxPhase = phase;
      }
   }

   private static String phaseName(int p) {
      return switch (p) {
         case -4 -> "slack";
         case -3 -> "forced";
         case -2 -> "-";
         case -1 -> "handoff";
         case P_SQUARES -> "squares";
         case P_BATCH -> "batch";
         case P_MID -> "mid";
         case P_LOOT -> "loot";
         case P_END -> "end";
         case P_CHUNKEV -> "loadchunk";
         default -> "p" + p;
      };
   }

   /** harness/run.sh routes (pzopt.Harness): recording from route start to the summary, written as pzopt-*.out. */
   private static volatile boolean harnessRecording;

   private static boolean recOn() {
      return BenchRecorder.on || harnessRecording;
   }

   /** Harness route start: the per-step / per-chunk records and the handler census from here. */
   public static void harnessRouteStart() {
      recN = 0;
      readyN = 0;
      harnessRecording = true;
      LuaStreamDispatch.resetCounters();
      if (LuaOrderCheck.ON) {
         LuaOrderCheck.reset();
      }
      routeBase = new long[]{chunks, chunksDeferred, steps, slackSteps, forced, picksReordered, picksAged};
   }

   private static long[] routeBase = new long[7];

   /** Harness route end (before pzopt-bench.out, which run.sh takes as the end): pzopt-luastream.out, pzopt-chunkready.out and
    *  pzopt-luahandlers.out in {@code dir} (csv, the benchmark's <step>-*.csv columns). */
   public static void harnessRouteEnd(java.io.File dir) {
      if (!harnessRecording) {
         return;
      }
      harnessRecording = false;
      long[] b = routeBase;
      Log.info("lua stream: route chunks " + (chunks - b[0]) + " deferred " + (chunksDeferred - b[1]) + " steps " + (steps - b[2]) + " slack steps " + (slackSteps - b[3])
            + " forced finishes " + (forced - b[4]) + " reordered picks " + (picksReordered - b[5]) + " aged picks " + (picksAged - b[6]));
      if (LuaOrderCheck.ON) {
         LuaOrderCheck.report();
      }
      try {
         writeCsv(new java.io.File(dir, "pzopt-luastream.out"));
         writeReadyCsv(new java.io.File(dir, "pzopt-chunkready.out"));
         LuaStreamDispatch.writeCsv(new java.io.File(dir, "pzopt-luahandlers.out"));
      } catch (Throwable t) {
         Log.warn("lua stream: could not write the harness csvs: " + t);
      }
   }

   /** BenchRecorder.writeTimelines: the route's frames (game steps) as csv. */
   static void writeCsv(java.io.File f) {
      LuaStreamJava.maybeLog(System.nanoTime(), true);
      if (recN == 0) {
         return;
      }
      try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(f)))) {
         w.println("t_ms,step_ms,loadgridsquare_ms,loadchunk_ms,stream_work_ms,queued,lgs_calls,loading_left,key_on,lgs_skips,budget_ms,demand_ms,visible_pending,max_step_ms,max_step_phase");
         for (int k = 0; k < recN; k++) {
            int i = k * 14;
            w.printf(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%.3f,%d,%.3f,%s%n", rec[i], rec[i + 1], rec[i + 2], rec[i + 3], rec[i + 4],
                  (int)rec[i + 5], (int)rec[i + 6], (int)rec[i + 7], on() ? 1 : 0, (int)rec[i + 8], rec[i + 9], rec[i + 10], (int)rec[i + 11], rec[i + 12],
                  phaseName((int)rec[i + 13]));
         }
      } catch (Throwable t) {
         Log.warn("lua stream: could not write " + f + ": " + t);
      }
   }
}
