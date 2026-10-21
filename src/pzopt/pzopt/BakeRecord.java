package pzopt;

import java.util.ArrayList;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.sprite.IsoSprite;

/**
 * bakeRecordParallel (2026-10-13): the draw lists of a frame's chunk-level bakes recorded on the frame workers.
 *
 * <p>A baked level cost the game thread ~0.09 ms of draw recording (floors, plants, walls, objects), and a frame that baked
 * nine levels more than another was 0.6 ms slower (devBakeHoldFrames, Louisville horde). Here, right before the chunk loop,
 * the game thread predicts the levels that bake this frame (the scheduler's grants, dirt, occlusion), runs their render-info
 * pass into each unit's own lists and hands the units to the frame workers ({@link FrameBatch#runAsync}); the workers
 * record each unit's floors, plants and objects through the tile-record machinery ({@link DrawRecorder}: the thread's own
 * draw list, GL state cache, draw modifiers) while the game thread runs the chunk loop; where stock draws the level, the
 * game thread takes the unit (helping with the batch if it is not done) and splices its list in. A unit's recorder sees
 * the bake's render mode and its own texture's offsets ({@link #xOffset}, {@link #renderChunk}, IsoSprite's seam tile).
 * Whatever a worker may not draw (other object classes, corpses through the dead-body atlas, items, snow, blood, the tree
 * batch) is deferred: the game thread draws it at its place in the stream during the splice. A level that bakes without
 * a unit takes the stock path; a unit whose level did not bake is dropped (counted).
 */
public final class BakeRecord {
   public static final int DEFER_FLOOR = 0, DEFER_OBJECT = 1, DEFER_CORPSES = 2, DEFER_ITEMS = 3, DEFER_SNOW = 4, DEFER_BLOOD = 5,
      DEFER_TREES_BEGIN = 6, DEFER_TREES_END = 7;

   private BakeRecord() {
   }

   /** What the game's cell does for a unit (FBORenderCell implements it). */
   public interface Cell {
      /** Worker: the unit's render-info pass (stock's, into the unit's own lists). */
      void bakeInfo(Unit u);

      /** Worker: the unit's draws (stock's floor and object passes over the unit's lists). */
      void bakeDraw(Unit u);

      /** Game thread, during the splice: a deferred draw. */
      void bakeDeferred(int kind, Object a, Object b);
   }

   /** One baked level: its texture and offsets, the render-info pass's lists, the recorded range. */
   public static final class Unit {
      public IsoChunk chunk;
      public int level;
      public int playerIndex;
      public FBORenderChunk rc;
      public float xoff, yoff;
      public final ArrayList<IsoGridSquare> shadow = new ArrayList<>(), solid = new ArrayList<>(), shaded = new ArrayList<>(),
         veg = new ArrayList<>(), minus = new ArrayList<>();
      int index;
      int task; // the texture's task: the levels of one chunk texture run in order on one thread (they share its cached lists)
      int waitN = -1, waitW = -1; // the units of the N / W neighbour chunk at this level: their info pass first (stock order)
      volatile boolean infoDone, spliced;
      volatile boolean stockPath; // the level takes the stock path at its bake (a skip or a failure), with every level above it in the chunk
      DrawRecorder rec;
      int entryFrom, entryTo, evFrom, evTo, postFrom;
      volatile boolean done;
      boolean taken;
      Throwable failure;
   }

   private static final ArrayList<DrawRecorder> recorders = new ArrayList<>();
   private static final ThreadLocal<DrawRecorder> threadRecorder = ThreadLocal.withInitial(() -> {
      DrawRecorder r = new DrawRecorder();
      synchronized (recorders) {
         recorders.add(r);
      }
      return r;
   });
   private static final int UNIFORM_POOL = 2048; // a bake level's tile-depth draws take four uniform setters each

   private static Unit[] units = new Unit[64];
   private static int n;
   private static int[] taskFirst = new int[64]; // per task: its first unit (a task's units are consecutive)
   private static int nTasks;
   private static java.util.concurrent.atomic.AtomicIntegerArray taskDone = new java.util.concurrent.atomic.AtomicIntegerArray(64);
   private static boolean dispatched, pending, serial;
   private static Cell cell;
   private static GenericSpriteRenderState real;
   private static boolean failed;

   // counters for the periodic line
   public static long frames, unitsRecorded, unitsSpliced, unitsDropped, unitsFailed, drawn, defers, waitNanos, spliceNanos, prepNanos, checks, checkEntries,
      checkMismatches, unitsMissed, infoChecks, infoMismatches, unitsSkipped, spliceTimeouts, spliceWaits, unitsUnsure, helpedTasks, leadTasks, waitFirstNanos;
   private static int checkLogged;

   /** Whether this frame's bakes go through units (the game thread's chunk loop asks before the pre-pass). */
   public static boolean active() {
      return Config.BAKE_RECORD_PARALLEL && !failed && Overrides.enabled() && zombie.characters.IsoPlayer.numPlayers == 1 && !PixelLight.ACTIVE
         && !Config.MIRRORS && !Sway.frameOn && GtAb.on(GtAb.BAKE_RECORD);
   }

   /** Game thread, before the pre-pass. */
   public static void begin(Cell c) {
      n = 0;
      nTasks = 0;
      cell = c;
      dispatched = false;
   }

   /** Game thread, pre-pass: a level predicted to bake. */
   public static Unit add(IsoChunk c, int level, FBORenderChunk rc, float xoff, float yoff, int playerIndex) {
      if (n == units.length) {
         units = java.util.Arrays.copyOf(units, n * 2);
      }
      Unit u = units[n];
      if (u == null) {
         u = units[n] = new Unit();
      }
      if (n == 0 || units[n - 1].chunk != c || units[n - 1].rc != rc) { // a task per chunk texture (its levels share the lists)
         if (nTasks == taskFirst.length) {
            taskFirst = java.util.Arrays.copyOf(taskFirst, nTasks * 2);
         }
         taskFirst[nTasks++] = n;
      }
      u.task = nTasks - 1;
      u.waitN = u.waitW = -1;
      u.infoDone = false;
      u.spliced = false;
      u.stockPath = false;
      u.index = n++;
      u.chunk = c;
      u.level = level;
      u.rc = rc;
      u.xoff = xoff;
      u.yoff = yoff;
      u.playerIndex = playerIndex;
      u.rec = null;
      u.done = false;
      u.taken = false;
      u.failure = null;
      return u;
   }

   /** Game thread, after the pre-pass: the units to the workers (or, devBakeRecordSerial, recorded at their splice). */
   public static void dispatch() {
      if (n == 0) {
         if (prewoken) {
            prewoken = false;
            published = true; // the woken lanes find nothing and finish (joined by the next batch)
         }
         return;
      }
      zombie.iso.IsoGridSquare.pzoptWarmCutawayTextures();
      if (IsoSprite.globalOffsetX == -1.0F) {
         IsoSprite.globalOffsetX = -zombie.iso.IsoCamera.frameState.offX;
         IsoSprite.globalOffsetY = -zombie.iso.IsoCamera.frameState.offY;
      }
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            ShaderUniformSetter.pzoptRefill(recorders.get(q), UNIFORM_POOL);
         }
      }
      LightingDefer.prepare(n);
      for (int i = 0; i < n; i++) {
         Unit u = units[i];
         for (int j = 0; j < i; j++) {
            Unit v = units[j];
            boolean west = v.chunk.wx == u.chunk.wx - 1 && v.chunk.wy == u.chunk.wy;
            boolean north = v.chunk.wx == u.chunk.wx && v.chunk.wy == u.chunk.wy - 1;
            if (!west && !north) {
               continue;
            }
            if (v.level == u.level) {
               if (north) {
                  u.waitN = j;
               } else {
                  u.waitW = j;
               }
            }
         }
      }
      if (taskDone.length() < nTasks) {
         taskDone = new java.util.concurrent.atomic.AtomicIntegerArray(Math.max(nTasks, taskDone.length() * 2));
      }
      for (int t = 0; t < nTasks; t++) {
         taskDone.set(t, 0);
      }
      real = DrawRecorder.real();
      dispatched = true;
      frames++;
      DrawRecorder.recording = true;
      serial = Config.DEV_BAKE_RECORD_SERIAL;
      if (serial) {
         return;
      }
      int first = 0;
      nextTask.set(first);
      firstTake = true;
      if (prewoken) {
         prewoken = false;
         published = true; // the lanes woken at the prediction's start take the units from here
      } else {
         // a few lanes, not one task per worker: the frame workers share the 8 cores with the game, render and lighting threads
         // and the characters' draw pool during the chunk loop, and every core taken slows the game thread's own (SMT sibling)
         int lanes = Math.max(1, Math.min(Math.min(Config.BAKE_RECORD_LANES, FrameBatch.THREADS), n - first));
         FrameBatch.runAsync(lanes, lane -> drain(), f -> pending = false);
         pending = true; // (after: runAsync joins the batch before it, whose completion clears the flag)
      }
      if (Config.BAKE_RECORD_LEAD && nextTask.get() == 0) {
         // the game thread records the first level itself while the parked workers wake (their wake-up was the first
         // splice's wait when the dispatch came right before the chunk loop)
         int k = nextTask.getAndIncrement();
         if (k < n) {
            leadTasks++;
            recordUnit(k);
         }
      }
   }

   private static boolean firstTake;
   private static boolean prewoken;
   private static volatile boolean published;

   /**
    * Game thread, at the start of the prediction (bakeRecordPrewake): the lanes start now and spin until {@link #dispatch}
    * publishes the units (a parked frame worker took ~0.1-0.2 ms to wake: the first splice's wait). Nothing between here and
    * the dispatch starts another batch (the prediction reads squares, textures and light only).
    */
   public static void prewake() {
      if (!Config.BAKE_RECORD_PREWAKE || Config.DEV_BAKE_RECORD_SERIAL) {
         return;
      }
      published = false;
      prewoken = true;
      int lanes = Math.max(1, Math.min(Config.BAKE_RECORD_LANES, FrameBatch.THREADS));
      FrameBatch.runAsync(lanes, lane -> laneWait(), f -> pending = false);
      pending = true; // (after: runAsync joins the batch before it, whose completion clears the flag)
   }

   private static void laneWait() {
      long deadline = System.nanoTime() + 5_000_000L; // (never reached: the prediction publishes within a fraction of a millisecond)
      int spins = 0;
      while (!published) {
         Thread.onSpinWait();
         if ((++spins & 255) == 0 && System.nanoTime() > deadline) {
            return;
         }
      }
      drain();
   }

   private static final java.util.concurrent.atomic.AtomicInteger nextTask = new java.util.concurrent.atomic.AtomicInteger();

   /** A lane: the next unclaimed unit, in index order, until none is left (the game thread claims them the same way while it waits). */
   private static void drain() {
      int k;
      while ((k = nextTask.getAndIncrement()) < n) {
         recordUnit(k);
      }
   }

   /**
    * Unit k on the calling thread. The levels of one chunk texture share its cached square lists, which their info passes
    * clear and fill: a unit's info pass waits for the one of the level below it in the same texture (stock order); its draw
    * does not touch those lists (a recording thread's cutaway window frame add waits for the splice), so the two draws run
    * in parallel. A lower level that takes the stock path takes the ones above it along (its stock info pass clears the lists).
    */
   static void recordUnit(int k) {
      Unit u = units[k];
      if (k > 0 && units[k - 1].task == u.task) {
         Unit prev = units[k - 1];
         int spins = 0;
         long deadline = System.nanoTime() + TileRecord.timeoutNs(); // (f8-render1: bounded; past it the level goes the stock way)
         while (!prev.infoDone) {
            pause(++spins);
            if ((spins & 63) == 0 && System.nanoTime() > deadline) {
               waitTimedOut("a level's info pass (unit " + (k - 1) + ")");
               break;
            }
         }
         if (prev.stockPath || !prev.infoDone) {
            u.stockPath = true;
            u.infoDone = true;
            u.done = true;
            return;
         }
      }
      record(k);
   }

   /** A worker (or the game thread helping): the units of chunk task t, in level order. */
   static void recordTask(int t) {
      int from = taskFirst[t];
      int to = t + 1 < nTasks ? taskFirst[t + 1] : n;
      boolean stop = false;
      try {
         for (int k = from; k < to; k++) {
            Unit u = units[k];
            if (stop) {
               // a lower level of the chunk takes the stock path: so do the ones above it (the stock info pass of the lower
               // level clears the group's lists the upper levels fill)
               u.stockPath = true;
               u.infoDone = true;
               u.done = true;
               continue;
            }
            record(k);
            stop = u.stockPath;
         }
      } finally {
         taskDone.set(t, 1);
      }
   }

   private static final class Skip extends RuntimeException {
      Skip() {
         super(null, null, false, false);
      }
   }

   private static final Skip SKIP = new Skip();

   /** f8-render1: a bounded wait ran out: logged once, bakeRecordParallel off from the next frame (failed). */
   private static void waitTimedOut(String what) {
      if (!failed) {
         Log.warn("bakeRecordParallel: " + what + " did not finish in " + Config.RECORD_WAIT_TIMEOUT_MS + " ms; the stock bakes from now on");
      }
      failed = true;
      spliceTimeouts++;
   }

   private static void pause(int spins) {
      if (spins < 200) {
         Thread.onSpinWait();
      } else {
         ShortWait.pause(10_000L);
      }
   }

   /**
    * Waits until unit k's level has been made by the game thread (spliced, drawn by the stock path, or passed by the chunk
    * loop). The game thread itself never waits here (it would wait for itself): the unit it records takes the stock path.
    */
   private static void awaitSpliced(Unit v) {
      if (v.spliced) {
         return;
      }
      if (!(Thread.currentThread() instanceof FrameBatch.Worker)) {
         throw SKIP;
      }
      spliceWaits++; // (racy count, dev only)
      int spins = 0;
      long deadline = System.nanoTime() + 20_000_000L; // (a batch joined inside the chunk loop would wait for this worker: the level goes the stock way)
      while (!v.spliced) {
         pause(++spins);
         if ((spins & 63) == 0 && System.nanoTime() > deadline) {
            spliceTimeouts++;
            throw SKIP;
         }
      }
   }

   /** Unit k's render info is final for the draws that read it (its worker's info pass, or the stock pass at its level). */
   private static void awaitInfo(int k) {
      if (k < 0) {
         return;
      }
      Unit v = units[k];
      int spins = 0;
      long deadline = System.nanoTime() + TileRecord.timeoutNs(); // (f8-render1: bounded)
      while (!v.infoDone) {
         pause(++spins);
         if ((spins & 63) == 0 && System.nanoTime() > deadline) {
            waitTimedOut("a neighbour's info pass (unit " + k + ")");
            throw SKIP;
         }
      }
      if (v.stockPath) {
         awaitSpliced(v);
      }
   }

   /** A worker (or the game thread helping): records unit k. */
   static void record(int k) {
      Unit u = units[k];
      DrawRecorder r = threadRecorder.get();
      r.bakeUnit = true;
      r.bakeRc = u.rc;
      r.bakeXoff = u.xoff;
      r.bakeYoff = u.yoff;
      r.seamFix2 = null;
      r.bakeUnsure = false;
      LightingDefer.begin(k);
      r.beginUnit(real);
      u.rec = r;
      u.entryFrom = r.unitEntry;
      u.evFrom = r.unitEvent;
      u.postFrom = r.postSize();
      try {
         try {
            cell.bakeInfo(u);
            if (r.bakeUnsure) {
               unitsUnsure++; // (racy count, dev only)
               throw SKIP; // the level's info pass and draw at the stock time
            }
         } catch (Throwable t) {
            u.stockPath = true; // before infoDone: a neighbour waiting for our info then waits for our stock pass
            throw t;
         } finally {
            u.infoDone = true; // (a failed one too: nothing waits forever)
         }
         awaitInfo(u.waitN); // the N / W squares' objects this unit draws took their render info in their own unit (stock order)
         awaitInfo(u.waitW);
         cell.bakeDraw(u);
      } catch (Skip skip) {
         u.stockPath = true;
         unitsSkipped++;
      } catch (Throwable t) {
         u.failure = t;
         u.stockPath = true;
      } finally {
         r.endUnit();
         LightingDefer.end();
         u.entryTo = r.state.numSprites;
         u.evTo = r.eventCount();
         unitsRecorded++; // (racy count, dev only)
         u.done = true;
      }
   }

   /** Game thread, after the chunk loop's renderOneChunk: every unit of the chunk is made (or will never be): its dependents go on. */
   public static void chunkDone(IsoChunk c) {
      if (!dispatched) {
         return;
      }
      for (int i = 0; i < n; i++) {
         if (units[i].chunk == c) {
            units[i].spliced = true;
         }
      }
   }

   /**
    * Game thread, renderOneLevel at the bake: the level's unit, recorded (the game thread helps the batch while it waits), or
    * null for the stock path (no unit, or its recording failed).
    */
   public static Unit take(IsoChunk c, int level) {
      if (!dispatched) {
         return null;
      }
      Unit u = null;
      for (int i = 0; i < n; i++) {
         Unit x = units[i];
         if (x.chunk == c && x.level == level && !x.taken) {
            u = x;
            break;
         }
      }
      if (u == null) {
         unitsMissed++; // a level baking without a unit: the stock path
         return null;
      }
      u.taken = true;
      long t0 = System.nanoTime();
      Unit next = u.index + 1 < n && units[u.index + 1].task == u.task ? units[u.index + 1] : null; // the texture's level above
      if (serial) {
         if (!u.done) {
            recordTask(u.task);
         }
      } else {
         int spins = 0;
         long deadline = t0 + TileRecord.timeoutNs(); // (f8-render1: bounded; past it the stock path, bakeRecordParallel off)
         // the unit, and the info pass of the texture's level above (it writes the lists this level's tail reads)
         while (!u.done || next != null && !next.infoDone) {
            if ((spins & 63) == 63 && System.nanoTime() > deadline) {
               waitTimedOut("the unit of a baking level");
               u.stockPath = true;
               if (next != null) {
                  next.stockPath = true;
               }
               return null;
            }
            LuaGate.service(); // a worker may be waiting for its Lua call
            int k = nextTask.get() < n ? nextTask.getAndIncrement() : n;
            if (k < n) {
               helpedTasks++;
               recordUnit(k);
            } else if (++spins < 200) {
               Thread.onSpinWait();
            } else {
               ShortWait.pause(10_000L);
            }
         }
      }
      long waited = System.nanoTime() - t0;
      waitNanos += waited;
      if (firstTake && !u.stockPath) {
         firstTake = false;
         waitFirstNanos += waited;
      }
      if (u.failure != null) {
         if (!failed) {
            Log.warn("bakeRecordParallel: a unit failed, the stock bakes from now on: " + u.failure);
            u.failure.printStackTrace();
         }
         failed = true;
         unitsFailed++;
      }
      if (u.stockPath) {
         if (next != null) {
            next.stockPath = true; // this level's stock info pass clears the texture's lists the level above filled: it goes stock too
         }
         LightingDefer.applyOne(u.index);
         return null; // its entries stay in the recorder (reset at the frame's end); the stock pass draws the level
      }
      return u;
   }

   /** Game thread: the unit's list into the frame's stream at this point, its deferred draws drawn here. */
   public static void splice(Unit u, DrawRecorder.Deferred deferred) {
      long t0 = System.nanoTime();
      LightingDefer.applyOne(u.index);
      DrawRecorder.carryZ = TextureDraw.nextZ;
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      u.rec.seekPost(u.postFrom);
      u.rec.splice(DrawRecorder.real(), deferred, u.entryFrom, u.entryTo, u.evFrom, u.evTo);
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      unitsSpliced++;
      u.spliced = true;
      spliceNanos += System.nanoTime() - t0;
   }

   /** Game thread, after the chunk loop: the batch joined, untaken units' light effects run, the recorders emptied. */
   public static void endFrame() {
      if (!dispatched) {
         return;
      }
      for (int i = 0; i < n; i++) {
         units[i].spliced = true;
      }
      if (pending) {
         if (!FrameBatch.joinWithin(TileRecord.timeoutNs())) {
            waitTimedOut("the bake recording batch");
         }
         pending = false;
      }
      for (int i = 0; i < n; i++) {
         Unit u = units[i];
         if (!u.taken) {
            unitsDropped++;
            if (serial && !u.done) {
               continue;
            }
            LightingDefer.applyOne(i);
         }
      }
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            DrawRecorder r = recorders.get(q);
            drawn += r.drawn;
            defers += r.defers;
            r.drawn = r.defers = 0;
            r.units = r.conds = r.condsKept = 0;
            r.fixMoved();
            r.reset();
            r.bakeRc = null;
         }
      }
      for (int i = 0; i < n; i++) {
         Unit u = units[i];
         u.chunk = null;
         u.rc = null;
         u.rec = null;
         u.shadow.clear();
         u.solid.clear();
         u.shaded.clear();
         u.veg.clear();
         u.minus.clear();
      }
      n = 0;
      nTasks = 0;
      dispatched = false;
      DrawRecorder.recording = false;
   }

   // ---- what the draw code asks on a recording thread ----

   private static DrawRecorder bakeRecorder() {
      if (!DrawRecorder.recording) {
         return null;
      }
      DrawRecorder r = DrawRecorder.current();
      return r != null && r.bakeUnit ? r : null;
   }

   /**
    * A render-info answer that depends on when it is asked (a sprite's texture size still loading, FloorDecals): on a
    * thread recording a unit, the unit takes the stock path, so the bake asks again at its own time.
    */
   public static void unsure() {
      DrawRecorder r = bakeRecorder();
      if (r != null) {
         r.bakeUnsure = true;
      }
   }

   public static long lightNeverLit, lightNotReady; // a square's light read on a recording thread before it was ever lit / while not readable (racy dev counts)

   /**
    * LightingJNI, a square's lazy light refresh found nothing to read: never lit and not dirty ({@code notReady} false), or
    * dirty but not readable yet. A not-readable square makes the unit take the stock path (the bake's own time may read it).
    */
   public static void lightUnread(boolean notReady) {
      DrawRecorder r = bakeRecorder();
      if (r == null) {
         return;
      }
      if (notReady) {
         lightNotReady++;
         r.bakeUnsure = true;
      } else {
         lightNeverLit++;
      }
   }

   /** Whether the calling thread records a bake unit. */
   public static boolean onBakeRecorder() {
      return bakeRecorder() != null;
   }

   /** FBORenderCell.renderFloor(IsoObject): true when the calling thread records a unit and the floor is deferred. */
   public static boolean deferFloor(IsoObject o) {
      DrawRecorder r = bakeRecorder();
      if (r == null) {
         return false;
      }
      int why = floorReason(o);
      if (why < 0) {
         r.drawn++;
         return false;
      }
      if (Config.INSTRUMENT) {
         floorReasons.incrementAndGet(why);
         floorCensus.add(o.getClass(), why); // (f8-render1: no key string per defer)
      }
      r.defer(DEFER_FLOOR, o, null);
      return true;
   }

   /** FBORenderCell.renderMinusFloor(IsoObject): true when the calling thread records a unit and the object is deferred. */
   public static boolean deferObject(IsoObject o) {
      DrawRecorder r = bakeRecorder();
      if (r == null) {
         return false;
      }
      int why = TileRecord.deferReason(o, true);
      if (why < 0) {
         r.drawn++;
         return false;
      }
      if (Config.INSTRUMENT) {
         objectReasons.incrementAndGet(why);
         objectCensus.add(o.getClass(), why); // (f8-render1: no key string per defer)
      }
      r.defer(DEFER_OBJECT, o, null);
      return true;
   }

   /** A draw the game thread does at this place of the stream (snow, blood, corpses, items, the tree batch). */
   public static boolean deferHere(int kind, Object a, Object b) {
      DrawRecorder r = bakeRecorder();
      if (r == null) {
         return false;
      }
      r.defer(kind, a, b);
      return true;
   }

   /**
    * A floor a recording thread may draw: a plain IsoObject floor tile on the stock floor path (FloorShaper modifiers of the
    * thread, IsoSprite's tile draw as tileRecordParallel made it per thread), no 3D model, no light-on overlay, no roof
    * joins, no slope (its ramp draws through the cell's shared colour), no children or wall splats, sprites warm.
    */
   static final String[] FLOOR_REASONS = {"class", "noSprite", "lightOn", "slope", "roof", "model", "fascia", "clock", "children", "cold"};
   static final TileRecord.Census floorCensus = new TileRecord.Census("bake:floor:", FLOOR_REASONS);
   static final TileRecord.Census objectCensus = new TileRecord.Census("bake:", TileRecord.REASONS);
   static final java.util.concurrent.atomic.AtomicLongArray floorReasons = new java.util.concurrent.atomic.AtomicLongArray(FLOOR_REASONS.length);
   static final java.util.concurrent.atomic.AtomicLongArray objectReasons = new java.util.concurrent.atomic.AtomicLongArray(TileRecord.REASONS.length);

   /** -1 when a recording thread may draw the floor, else the index of the first FLOOR_REASONS entry it may not. */
   static int floorReason(IsoObject o) {
      if (o.getClass() != IsoObject.class && o.getClass() != zombie.iso.objects.IsoLightSwitch.class) {
         return 0; // (a light switch draws its floor as any IsoObject; one with a light-on sprite stays below, as tileRecordParallel's rule)
      }
      IsoSprite s = o.sprite;
      if (s == null || o.square == null) {
         return 1;
      }
      zombie.core.properties.PropertyContainer props = s.getProperties();
      if (props.has(IsoFlagType.HasLightOnSprite)
         && (o.getClass() != zombie.iso.objects.IsoLightSwitch.class || o.getOnOverlay() == null && o.shouldShowOnOverlay())) {
         return 2; // (tileRecordParallel's rule: a light switch's own check reads its memo; its "on" overlay instance comes from the shared pool, made on the game thread first)
      }
      if (props.getSlopedSurfaceDirection() != null) {
         return 3;
      }
      if (!s.pzoptRoofKnown() || s.getRoofProperties() != null && roofJoinTarget(o.square)) {
         // the floor path never joins, but a roof tile on the chunk's west / north edge may be drawn joined by the neighbour
         // chunk's tile (renderJoinedRoofTile, on the game thread at that chunk's splice, writing this object's render square
         // and depth adjust around its draw): it is drawn on the game thread too, in stock order
         return 4;
      }
      if (o.getSpriteModel() != null) {
         return 5;
      }
      if (o.isFascia()) {
         return 6;
      }
      if (s.name != null && zombie.scripting.ScriptManager.instance.getClockScript(s.name) != null) {
         return 7;
      }
      if (o.pzoptHasChildrenOrSplats()) {
         return 8;
      }
      return o.pzoptSpritesWarm() ? -1 : 9;
   }

   /**
    * FBORenderCell.renderMinusFloor_DoorOrWall's cutaway window frame list add: on a recording thread it runs at the unit's
    * splice (the list belongs to the square's chunk, maybe another unit's, whose thread may be rebuilding it). The splice
    * order is the stock order, and the bake's info pass never adds to that list (it only clears / drops it), so the list
    * comes out the same.
    */
   public static boolean deferListAdd(IsoGridSquare square, int playerIndex) {
      if (bakeRecorder() == null) {
         return false;
      }
      ArrayList<Runnable> sink = LightingDefer.current();
      if (sink == null) {
         return false;
      }
      sink.add(() -> {
         java.util.List<IsoGridSquare> l = square.getChunk().getRenderLevels(playerIndex).getCachedSquares_CutawayWindowFrames(square.getZ());
         if (!l.contains(square)) {
            l.add(square);
         }
      });
      return true;
   }

   /** devBakeRecordCheck: a unit's render info against the stock time's (FBORenderCell.pzoptBakeInfoCheck). */
   public static long infoPuddleDecals; // devBakeRecordCheck: decals whose layer hangs on IsoPuddlesGeometry.shouldRender (not idempotent: the check's own call answers differently)

   public static void infoChecked(Unit u, int bad, String first) {
      infoChecks++;
      if (bad > 0) {
         infoMismatches += bad;
         if (checkLogged++ < 40) {
            Log.info("bake record info check: chunk " + u.chunk.wx + "," + u.chunk.wy + " level " + u.level + ": " + bad + " objects differ, first: " + first);
         }
      }
   }

   /**
    * A square where a W / N neighbour chunk's roof tile may draw this chunk's tile joined to it (FBORenderCell's
    * renderJoinedRoofTile: the same level from x % 8 == 7 / y % 8 == 7, the level below from the tile above-west / north):
    * the chunk's west column and north row.
    */
   static boolean roofJoinTarget(IsoGridSquare sq) {
      return (Math.floorMod(sq.x, 8) == 0 || Math.floorMod(sq.y, 8) == 0) && zombie.iso.fboRenderChunk.FBORenderCell.pzoptRoofJoinTarget(sq);
   }

   // ---- what IsoSprite / IsoObject read of the bake being drawn ----

   public static float xOffset() {
      DrawRecorder r = bakeRecorder();
      return r != null ? r.bakeXoff : FBORenderChunkManager.instance.getXOffset();
   }

   public static float yOffset() {
      DrawRecorder r = bakeRecorder();
      return r != null ? r.bakeYoff : FBORenderChunkManager.instance.getYOffset();
   }

   public static FBORenderChunk renderChunk() {
      DrawRecorder r = bakeRecorder();
      return r != null ? (FBORenderChunk)r.bakeRc : FBORenderChunkManager.instance.renderChunk;
   }

   // ---- devBakeRecordCheck ----

   /** Whether this frame's units are checked against the stock draw. */
   public static boolean checkFrame() {
      return Config.DEV_BAKE_RECORD_CHECK > 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_BAKE_RECORD_CHECK == 0;
   }

   /** A stock draw of a unit, captured and rolled back (TileRecord.Check's procedure, one level). */
   public static final class Check {
      int n0, postRender0;
      final ArrayList<TileRecord.Entry> stock = new ArrayList<>();
      zombie.core.opengl.IOpenGLState<?>[] glStates;
      zombie.core.opengl.IOpenGLState.Value[] glValues;
      boolean[] glDirty;
      final ArrayList<IsoObject> objects = new ArrayList<>();
      final ArrayList<float[]> alphas = new ArrayList<>();
      float nz, ncd;
      Object tileBatch;

      /** Before the stock draw: the stream, GL caches, objects' alphas, carried depth. */
      public static Check begin(Unit u) {
         Check c = new Check();
         GenericSpriteRenderState real = DrawRecorder.real();
         c.n0 = real.numSprites;
         c.postRender0 = real.postRender.size();
         c.glStates = TileRecord.GlStates.all();
         c.glValues = new zombie.core.opengl.IOpenGLState.Value[c.glStates.length];
         c.glDirty = new boolean[c.glStates.length];
         for (int i = 0; i < c.glStates.length; i++) {
            c.glValues[i] = c.glStates[i].pzoptNewValue();
            c.glDirty[i] = c.glStates[i].pzoptSnapshot(c.glValues[i]);
         }
         int p = u.playerIndex;
         for (int y = -1; y < 8; y++) {
            for (int x = -1; x < 8; x++) {
               IsoGridSquare sq = zombie.iso.IsoWorld.instance.currentCell.getGridSquare(u.chunk.wx * 8 + x, u.chunk.wy * 8 + y, u.level);
               if (sq == null) {
                  continue;
               }
               for (int k = 0; k < sq.getObjects().size(); k++) {
                  IsoObject o = sq.getObjects().get(k);
                  c.objects.add(o);
                  c.alphas.add(new float[]{o.getAlpha(p), o.getTargetAlpha(p)});
               }
            }
         }
         c.nz = TextureDraw.nextZ;
         c.ncd = TextureDraw.nextChunkDepth;
         c.tileBatch = TileBatch.snapshot();
         return c;
      }

      /** After the stock draw: its entries kept, everything rolled back. */
      public void rollBack(Unit u) {
         GenericSpriteRenderState real = DrawRecorder.real();
         for (int i = this.n0; i < real.numSprites; i++) {
            this.stock.add(new TileRecord.Entry(real.sprite[i], real.style[i]));
         }
         real.numSprites = this.n0;
         while (real.postRender.size() > this.postRender0) {
            real.postRender.remove(real.postRender.size() - 1);
         }
         for (int i = 0; i < this.glStates.length; i++) {
            this.glStates[i].pzoptRestore(this.glValues[i], this.glDirty[i]);
         }
         TextureDraw.nextZ = this.nz;
         TextureDraw.nextChunkDepth = this.ncd;
         TileBatch.restore(this.tileBatch);
         int p = u.playerIndex;
         for (int i = 0; i < this.objects.size(); i++) {
            float[] a = this.alphas.get(i);
            this.objects.get(i).setAlpha(p, a[0]);
            this.objects.get(i).setTargetAlpha(p, a[1]);
         }
      }

      /** After the splice: the spliced entries against the stock ones. */
      public void compare(Unit u) {
         GenericSpriteRenderState real = DrawRecorder.real();
         checks++;
         int m = real.numSprites - this.n0;
         checkEntries += this.stock.size();
         int bad = 0;
         String first = null;
         if (m != this.stock.size()) {
            bad++;
            first = "entry count stock=" + this.stock.size() + " recorded=" + m;
         }
         int k = Math.min(m, this.stock.size());
         String firstDiff = null;
         for (int i = 0; i < k; i++) {
            String d = this.stock.get(i).diff(real.sprite[this.n0 + i], real.style[this.n0 + i]);
            if (d != null) {
               bad++;
               if (firstDiff == null) {
                  StringBuilder ctx = new StringBuilder();
                  for (int j = Math.max(0, i - 3); j < Math.min(k, i + 3); j++) {
                     ctx.append(" [").append(j).append(" stock ").append(this.stock.get(j).type).append(" rec ").append(real.sprite[this.n0 + j].type).append(']');
                  }
                  firstDiff = "entry " + i + "/" + k + ": " + d + " around:" + ctx;
               }
            }
         }
         if (firstDiff != null) {
            first = first == null ? firstDiff : first + "; " + firstDiff;
         }
         if (bad > 0) {
            checkMismatches += bad;
            if (checkLogged++ < 30) {
               Log.info("bake record check: chunk " + u.chunk.wx + "," + u.chunk.wy + " level " + u.level + ": " + bad + " differences in "
                  + this.stock.size() + " entries, first: " + first);
            }
         }
      }
   }

   static String reasonsText() {
      StringBuilder sb = new StringBuilder(" floor defers:");
      for (int i = 0; i < FLOOR_REASONS.length; i++) {
         sb.append(' ').append(FLOOR_REASONS[i]).append('=').append(floorReasons.get(i));
      }
      sb.append(" object defers:");
      for (int i = 0; i < TileRecord.REASONS.length; i++) {
         sb.append(' ').append(TileRecord.REASONS[i]).append('=').append(objectReasons.get(i));
      }
      sb.append(" classes:");
      TileRecord.deferClasses().entrySet().stream().filter(e -> e.getKey().startsWith("bake:"))
         .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(14)
         .forEach(e -> sb.append(' ').append(e.getKey().substring(5)).append('=').append(e.getValue().sum()));
      return sb.toString();
   }

   private static final long[] total = new long[26]; // the counters since boot (describe() resets its own)

   /** Since boot, for pzopt-bench.out's bake_counters (short runs never reach the periodic line). */
   public static String totals() {
      long[] v = {frames, unitsRecorded, unitsSpliced, unitsDropped, unitsFailed, drawn, defers, waitNanos / 1_000_000L, spliceNanos / 1_000_000L, prepNanos / 1_000_000L,
         checks, checkEntries, checkMismatches, unitsMissed, infoChecks, infoMismatches, unitsSkipped, spliceTimeouts, spliceWaits, unitsUnsure, infoPuddleDecals, helpedTasks, leadTasks, waitFirstNanos / 1_000_000L, lightNeverLit, lightNotReady};
      String[] k = {"frames", "units", "spliced", "dropped", "failed", "drawn", "deferred", "waitMs", "spliceMs", "prepMs", "checks", "checkEntries", "checkMismatches",
         "missed", "infoChecks", "infoMismatches", "skipped", "timeouts", "spliceWaits", "unsure", "infoPuddleDecals", "helped", "lead", "waitFirstMs", "lightNeverLit", "lightNotReady"};
      StringBuilder sb = new StringBuilder(" bakeRecord:");
      for (int i = 0; i < v.length; i++) {
         sb.append(' ').append(k[i]).append('=').append(total[i] + v[i]);
      }
      return sb.append(reasonsText()).toString();
   }

   public static String describe() {
      if (frames == 0) {
         return "";
      }
      long[] v = {frames, unitsRecorded, unitsSpliced, unitsDropped, unitsFailed, drawn, defers, waitNanos / 1_000_000L, spliceNanos / 1_000_000L, prepNanos / 1_000_000L,
         checks, checkEntries, checkMismatches, unitsMissed, infoChecks, infoMismatches, unitsSkipped, spliceTimeouts, spliceWaits, unitsUnsure, infoPuddleDecals, helpedTasks, leadTasks, waitFirstNanos / 1_000_000L, lightNeverLit, lightNotReady};
      for (int i = 0; i < v.length; i++) {
         total[i] += v[i];
      }
      String s = " | bake record: frames=" + frames + " units=" + unitsRecorded + " spliced=" + unitsSpliced + " dropped=" + unitsDropped + " failed=" + unitsFailed
         + " drawn=" + drawn + " deferred=" + defers + " wait ms=" + waitNanos / 1_000_000L + " splice ms=" + spliceNanos / 1_000_000L + " prep ms=" + prepNanos / 1_000_000L
         + " checks=" + checks + " entries=" + checkEntries + " mismatches=" + checkMismatches + " missed=" + unitsMissed + " info checks=" + infoChecks
         + " info mismatches=" + infoMismatches + " skipped=" + unitsSkipped + " timeouts=" + spliceTimeouts + " splice waits=" + spliceWaits + " unsure=" + unitsUnsure + " info puddle decals=" + infoPuddleDecals + " helped=" + helpedTasks + " lead=" + leadTasks + " first wait ms=" + waitFirstNanos / 1_000_000L + " light never lit=" + lightNeverLit + " not ready=" + lightNotReady + reasonsText();
      frames = unitsRecorded = unitsSpliced = unitsDropped = unitsFailed = drawn = defers = waitNanos = spliceNanos = prepNanos = checks = checkEntries = checkMismatches = 0L;
      unitsMissed = infoChecks = infoMismatches = unitsSkipped = spliceTimeouts = spliceWaits = unitsUnsure = infoPuddleDecals = helpedTasks = leadTasks = waitFirstNanos = lightNeverLit = lightNotReady = 0L;
      return s;
   }
}
