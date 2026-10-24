package pzopt;

import java.util.ArrayList;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * Ground and grass, shared part (2026-10-24): one gather a frame of what moves over the ground, for every ground feature
 * (the tracks' prints and ruts, the blades' push and trails, GroundState's default stamping). Before it, GroundState, the
 * tracks and the blades each walked the zombie list, the players and the vehicles every frame with their own per-object
 * state (two IdentityHashMaps and a set of fields).
 *
 * <p>Two products, each built only when a consumer asked for it in the last two frames:
 * <ul>
 *   <li><b>Events</b> ({@link #wantEvents()}): this frame's footprints (a stride every 0.42 squares walking, 0.7 running,
 *   feet alternating; animals at 0.7 size), crawl drags, a body's imprint once on death, and every moving vehicle's wheel
 *   lines since the last frame (slip = 1 - Bullet's skidInfo, the vehicle's mass). A crowd is visited round robin (above 160
 *   zombies each one at least every ~1.2 s); the stride line is kept per character, so no print is lost, only placed late.
 *   The characters are gathered on a worker thread (devGroundMoversSync: on the game thread), their events a frame late;
 *   the vehicles on the game thread.
 *   Read {@link #nEvents} / {@link #EV} (layout below) and {@link #EV_SRC}.</li>
 *   <li><b>Near movers</b> ({@link #wantNear(float)}): the players, zombies (at most {@link #MAX_NEAR}) and vehicles within
 *   the asked reach of the camera's character on its level: position, heading, speed, radius, a smoothed moving strength
 *   (0..1, up in 0.15 s, down in 0.6 s of game time), melee attack + reach, a vehicle's half length / width. {@link #nNear}
 *   / {@link #NEAR}.</li>
 * </ul>
 * Per-object state lives in {@code pzoptGm*} fields on IsoMovingObject (no maps); {@code pzoptGmMarkX / Y} are free for a
 * consumer's own "last stamped here" (the blades' trail spacing).
 *
 * <p>Event layout, {@link #EV_FLOATS} floats each: kind ({@link #FOOT}, {@link #DRAG}, {@link #BODY}, {@link #WHEEL}), who
 * ({@link #PLAYER}, {@link #ZOMBIE}, {@link #ANIMAL}, {@link #VEHICLE}), then
 * <ul>
 *   <li>FOOT: x, y (the print's centre), ux, uy (heading), side (+1 left / -1 right), size (1, animals 0.7), running 0/1,
 *   ground (1 natural as GroundState.peek knows it, 0 not known); none on known pavement while {@link #dropPaved};</li>
 *   <li>DRAG: x0, y0, x1, y1 (the dragged line), size;</li>
 *   <li>BODY: x, y, fx, fy (the body's facing), size;</li>
 *   <li>WHEEL: x0, y0, x1, y1 (the wheel's line since its last point), slip 0..1, mass (kg), wheel index.</li>
 * </ul>
 * World square coordinates (doubles would not help: a frame's line is short; the consumer subtracts its origin).
 */
public final class GroundMovers {
   private GroundMovers() {
   }

   public static final int FOOT = 0, DRAG = 1, BODY = 2, WHEEL = 3;
   public static final int PLAYER = 0, ZOMBIE = 1, ANIMAL = 2, VEHICLE = 3;
   public static final int EV_FLOATS = 10, MAX_EVENTS = 2048, MAX_NEAR = 128;
   public static final float STRIDE_WALK = 0.42F, STRIDE_RUN = 0.7F, ANIMAL_SIZE = 0.7F;

   /** This frame's events (game thread; valid from Ground.frameStart to the next). */
   public static final float[] EV = new float[MAX_EVENTS * EV_FLOATS];
   public static final IsoMovingObject[] EV_SRC = new IsoMovingObject[MAX_EVENTS];
   public static int nEvents;
   public static long eventsTotal, eventsDropped, gatherNs, frames, vehicleNs, characterNs, visitsTotal;

   /** A mover near the camera this frame. */
   public static final class Mover {
      public IsoMovingObject obj;
      public int who;
      public float x, y, z;
      /** Heading (unit, world): a character's forward direction, a vehicle's nose. */
      public float fx, fy;
      /** Squares a second (game time) this frame. */
      public float speed;
      /** 0.32 a character, 1 a vehicle. */
      public float radius;
      /** Moving, smoothed (0 standing .. 1 walking or driving). */
      public float strength;
      /** A melee swing is on (a non-ranged HandWeapon in the primary hand), and its reach in squares. */
      public boolean melee;
      public float meleeRange;
      /** A vehicle's half length / half width (squares, along fx / across). */
      public float halfLength, halfWidth;
   }

   public static final Mover[] NEAR = new Mover[MAX_NEAR];
   public static int nNear;

   static {
      for (int i = 0; i < MAX_NEAR; i++) {
         NEAR[i] = new Mover();
      }
   }

   private static int frame, eventsWantedFrame = -100, nearWantedFrame = -100;
   private static float nearReach;
   private static long lastNs;
   private static double dtGame;

   /** Game thread: a consumer reads the events this frame (keeps them coming next frame too). */
   public static void wantEvents() {
      eventsWantedFrame = frame;
   }

   /** Game thread: a consumer reads the near movers within {@code reach} squares of the camera's character. */
   public static void wantNear(float reach) {
      if (nearWantedFrame != frame) {
         nearReach = 0F;
      }
      nearWantedFrame = frame;
      nearReach = Math.max(nearReach, reach);
   }

   /** A consumer asked for events or near movers in the last two frames. */
   static boolean wanted() {
      return eventsWantedFrame >= frame - 2 || nearWantedFrame >= frame - 2;
   }

   /** Seconds of game time this frame (0 paused). */
   public static double dt() {
      return dtGame;
   }

   /** Ground.frameStart, game thread, once a frame (player 0). */
   static void frameStart() {
      frame++;
      long t0 = System.nanoTime();
      double dt = lastNs == 0L ? 0.0 : Math.min(0.1, (t0 - lastNs) / 1e9);
      lastNs = t0;
      boolean paused = zombie.GameTime.isGamePaused();
      dtGame = paused ? 0.0 : dt * Math.min(4.0, Math.max(0.0, zombie.GameTime.getInstance().getTrueMultiplier()));
      nEvents = 0;
      nNear = 0;
      boolean events = eventsWantedFrame >= frame - 2;
      boolean near = nearWantedFrame >= frame - 2;
      if (!events && !near) {
         return;
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      if (cell == null) {
         return;
      }
      frames++;
      if (events) {
         long ta = System.nanoTime();
         GAME.n = 0;
         vehicles(cell);
         scanAnimals(cell);
         long tb = System.nanoTime();
         if (Config.DEV_GROUND_MOVERS_SYNC || workerFailed) {
            characters(cell, GAME, dtGame);
         } else {
            // pzopt ground-tracks 2026-10-24: the characters' gather runs on a worker (the Louisville horde's ~45 zombie
            // visits a frame were 4.3 us of game thread, cold objects): its events of the last job are taken here at the
            // stamp rate (devGroundStateHz, 30 a second: a print ~1-2 batches late), and the next job is posted; the game
            // thread never waits for it
            if (doneGen == postGen && (!posted || t0 - lastTakeNs >= TAKE_PERIOD_NS)) {
               lastTakeNs = t0; // (taken at GroundState's stamp rate: the worker's job covers the time since its last)
               if (posted) {
                  take(WORK);
               }
               postScale = paused ? 0.0 : Math.min(4.0, Math.max(0.0, zombie.GameTime.getInstance().getTrueMultiplier()));
               posted = true;
               startWorker();
               postGen = postGen + 1;
            } else if (doneGen != postGen) {
               workerBusy++;
            }
         }
         nEvents = GAME.n;
         vehicleNs += tb - ta;
         characterNs += System.nanoTime() - tb;
      }
      if (near) {
         near(cell, nearReach);
      }
      gatherNs += System.nanoTime() - t0;
   }

   public static String stats() {
      return "ground movers: frames " + frames + ", events " + eventsTotal + " (dropped " + eventsDropped + "), gather "
         + (frames == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f", gatherNs / 1e3 / frames)) + " us/frame (vehicles "
         + (frames == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f", vehicleNs / 1e3 / frames)) + ", characters "
         + (frames == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f", characterNs / 1e3 / frames)) + ", zombie visits " + visitsTotal + "; worker " + (workerJobs == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f", workerNs / 1e3 / workerJobs))
         + " us/job, jobs " + workerJobs + ", paved prints dropped " + pavedDropped + ", busy " + workerBusy + ", failures " + workerErrors + (Config.DEV_GROUND_MOVERS_SYNC || workerFailed ? ", off" : "") + ")";
   }

   // ------------------------------------------------------------------------------------------------ events

   /** Where a gather writes its events: the frame's {@link #EV} (game thread) or the worker's own arrays. */
   static final class Sink {
      final float[] ev;
      final IsoMovingObject[] src;
      int n;
      long total, dropped;

      Sink(float[] ev, IsoMovingObject[] src) {
         this.ev = ev;
         this.src = src;
      }

      int event(int kind, int who, IsoMovingObject o) {
         if (n >= MAX_EVENTS) {
            dropped++;
            return -1;
         }
         int k = n * EV_FLOATS;
         ev[k] = kind;
         ev[k + 1] = who;
         src[n] = o;
         n++;
         total++;
         return k + 2;
      }
   }

   private static final Sink GAME = new Sink(EV, EV_SRC);
   private static final Sink WORK = new Sink(new float[MAX_EVENTS * EV_FLOATS], new IsoMovingObject[MAX_EVENTS]);

   private static int event(int kind, int who, IsoMovingObject src) {
      int o = GAME.event(kind, who, src);
      if (o < 0) {
         eventsDropped++;
      } else {
         eventsTotal++;
      }
      return o;
   }

   /** Game thread: the worker's finished job appended to this frame's events. */
   private static void take(Sink w) {
      int m = Math.min(w.n, MAX_EVENTS - GAME.n);
      if (m > 0) {
         System.arraycopy(w.ev, 0, EV, GAME.n * EV_FLOATS, m * EV_FLOATS);
         System.arraycopy(w.src, 0, EV_SRC, GAME.n, m);
         GAME.n += m;
      }
      java.util.Arrays.fill(w.src, 0, w.n, null); // (no stale references kept alive)
      eventsTotal += m;
      eventsDropped += w.n - m + w.dropped;
      visitsTotal += workerVisits;
      workerVisits = 0;
      w.n = 0;
      w.dropped = 0;
   }

   // ------------------------------------------------------------------------------------------------ the worker
   // One daemon thread that polls every millisecond for a posted job (no unpark: waking a parked thread is a futex call on
   // the game thread). It owns the characters' pzoptGm* stride state and the round robin's cursor while it is on; it reads
   // the zombie list and the characters' positions as the game thread leaves them (a position a frame old at worst; a list
   // that shrank under the walk ends the walk early, the cursor goes on next job).

   private static volatile int postGen, doneGen;
   private static long lastTakeNs;
   private static final long TAKE_PERIOD_NS = Config.DEV_GROUND_STATE_HZ <= 0 ? 0L : 1_000_000_000L / Config.DEV_GROUND_STATE_HZ;
   private static volatile double postScale;
   private static boolean posted;
   private static volatile boolean workerFailed;
   private static Thread workerThread;
   private static int workerVisits; // worker-owned until doneGen, read by take()
   private static int workerVehicleScan = 60; // (worker: its first job scans)
   public static long workerNs, workerJobs, workerBusy, workerErrors;
   /** Set by GroundState (its only consumer): FOOT events on known pavement are not made (GroundState.peek). */
   public static volatile boolean dropPaved;
   public static long pavedDropped;

   private static void startWorker() {
      if (workerThread != null) {
         return;
      }
      Thread t = new Thread(GroundMovers::workerLoop, "pzopt-ground-movers");
      t.setDaemon(true);
      workerThread = t;
      t.start();
   }

   private static void workerLoop() {
      int seen = 0;
      long last = 0L;
      while (true) {
         int g = postGen;
         if (g == seen) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            continue;
         }
         seen = g;
         long t0 = System.nanoTime();
         double dt = last == 0L ? 1.0 / 60.0 : Math.min(0.1, (t0 - last) / 1e9);
         last = t0;
         try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (cell != null) {
               characters(cell, WORK, dt * postScale);
               boolean drivers = false;
               for (IsoPlayer p : IsoPlayer.players) {
                  drivers |= p != null && p.getVehicle() != null;
               }
               driversNow = drivers;
               if (++workerVehicleScan >= 60) {
                  workerVehicleScan = 0;
                  scanVehicles(cell);
               }
            }
         } catch (Throwable t) {
            WORK.n = 0;
            if (++workerErrors == 1L) {
               Log.warn("ground movers: worker job failed (" + t + "), retrying next frame");
            }
            if (workerErrors >= 200L) {
               workerFailed = true; // (the game thread gathers again)
               Log.warn("ground movers: worker failed 200 times, the gather is back on the game thread");
            }
         }
         workerNs += System.nanoTime() - t0;
         workerJobs++;
         doneGen = g;
      }
   }

   private static final Vector3f WP = new Vector3f();
   private static final ArrayList<BaseVehicle> MOVING = new ArrayList<>();
   private static final ArrayList<IsoGameCharacter> ANIMALS = new ArrayList<>();
   private static int vehicleScan, animalScan = 10000, zCursor;

   private static final BaseVehicle[] NO_VEHICLES = new BaseVehicle[0];
   private static volatile BaseVehicle[] movingNow = NO_VEHICLES; // the last scan's moving vehicles (the worker's, or the game thread's)
   private static BaseVehicle[] movingUsed = NO_VEHICLES;
   public static long vehicleScanRaces;
   private static volatile boolean driversNow = true; // (the worker's last job: a player sits in a vehicle)

   /** The cell's moving vehicles (a scan every ~2 s: a parked car that drives off is picked up then). */
   private static void scanVehicles(IsoCell cell) {
      ArrayList<BaseVehicle> out = new ArrayList<>();
      try {
         for (BaseVehicle v : cell.getVehicles()) {
            if (v != null && Math.abs(v.getCurrentSpeedKmHour()) > 0.5F) {
               out.add(v);
            }
         }
      } catch (RuntimeException e) {
         vehicleScanRaces++; // (the worker's walk met a chunk load's add / remove: the last scan stays, the next one is ~2 s on)
         return;
      }
      movingNow = out.isEmpty() ? NO_VEHICLES : out.toArray(NO_VEHICLES);
   }

   private static void vehicles(IsoCell cell) {
      boolean async = !(Config.DEV_GROUND_MOVERS_SYNC || workerFailed);
      if (!async && ++vehicleScan >= 120) { // (20 frames: a city's hundreds of parked cars cost 1.2 us/frame, gtr-lou3; on the worker since 10-24)
         vehicleScan = 0;
         scanVehicles(cell);
      }
      BaseVehicle[] now = movingNow;
      if (async && now.length == 0 && movingUsed.length == 0 && !driversNow) {
         return; // pzopt ground-tracks 2026-10-24: nothing moves and nobody drives (the worker looks at the players each job)
      }
      if (now != movingUsed) {
         for (BaseVehicle v : movingUsed) { // parked since: its next line starts where it moves off
            boolean still = false;
            for (BaseVehicle w : now) {
               still |= w == v;
            }
            if (!still) {
               v.pzoptGmWheels = null;
            }
         }
         movingUsed = now;
      }
      MOVING.clear();
      if (now.length > 0) {
         java.util.Collections.addAll(MOVING, now);
      }
      for (IsoPlayer p : IsoPlayer.players) {
         BaseVehicle v = p == null ? null : p.getVehicle();
         if (v != null && !MOVING.contains(v)) {
            MOVING.add(v);
         }
      }
      for (int i = 0; i < MOVING.size(); i++) {
         vehicle(MOVING.get(i));
      }
   }

   private static void vehicle(BaseVehicle v) {
      zombie.scripting.objects.VehicleScript script = v.getScript();
      if (script == null || v.getZ() >= 0.5F || v.wheelInfo == null) {
         return;
      }
      int nw = Math.min(script.getWheelCount(), v.wheelInfo.length);
      if (nw <= 0 || nw > 8) {
         return;
      }
      double[] last = v.pzoptGmWheels;
      boolean had = last != null;
      if (last == null) {
         last = v.pzoptGmWheels = new double[16];
      }
      float mass = v.getMass();
      Vector3f mo = script.getModel() != null ? script.getModel().offset : null;
      for (int w = 0; w < nw; w++) {
         zombie.scripting.objects.VehicleScript.Wheel sw = script.getWheel(w);
         v.getWorldPos(sw.offset.x + (mo != null ? mo.x : 0F), 0F, sw.offset.z + (mo != null ? mo.z : 0F), WP, script);
         double x = WP.x, y = WP.y;
         if (had) {
            double lx = last[w * 2], ly = last[w * 2 + 1];
            double d2 = (x - lx) * (x - lx) + (y - ly) * (y - ly);
            if (d2 < 1e-4) {
               continue; // keep the last point: a creeping car still joins up its line
            }
            if (d2 < 16.0) {
               int o = event(WHEEL, VEHICLE, v);
               if (o >= 0) {
                  EV[o] = (float)lx;
                  EV[o + 1] = (float)ly;
                  EV[o + 2] = (float)x;
                  EV[o + 3] = (float)y;
                  EV[o + 4] = 1F - Math.max(0F, Math.min(1F, v.wheelInfo[w].skidInfo));
                  EV[o + 5] = mass;
                  EV[o + 6] = w;
               }
            }
         }
         last[w * 2] = x;
         last[w * 2 + 1] = y;
      }
   }

   private static void characters(IsoCell cell, Sink sink, double dtG) {
      boolean onWorker = sink == WORK;
      ArrayList<IsoZombie> zs = cell.getZombieList();
      int n = zs.size();
      // a crowd round robin: each zombie at least every ~1.2 s (the stride line is kept, nothing is lost: a walker covers
      // ~0.6-1.2 squares in that time, up to 10 prints a visit, a sprinter's 4 squares under the 6-square jump cut). 0.3 s
      // visited ~110 zombies a frame in the Louisville horde at 60 fps (gtr-lou1: gather 7.2 us/frame), 0.6 s ~84 (lou4)
      float dt = (float)Math.max(1.0 / 240.0, Math.min(0.1, dtG));
      int visits = n <= 160 ? n : Math.min(n, Math.max(16, (int)Math.ceil(n * dt / 1.2F)));
      if (zCursor >= n) {
         zCursor = 0;
      }
      for (int k = 0; k < visits; k++) {
         IsoZombie z;
         if (onWorker) {
            if (zCursor >= zs.size()) { // (the list shrank under the worker's walk)
               zCursor = 0;
               break;
            }
            try {
               z = zs.get(zCursor);
            } catch (IndexOutOfBoundsException e) {
               zCursor = 0;
               break;
            }
         } else {
            z = zs.get(zCursor);
         }
         if (++zCursor >= n) {
            zCursor = 0;
         }
         if (z != null) {
            character(z, ZOMBIE, 1F, sink);
         }
         if (onWorker) {
            workerVisits++;
         } else {
            visitsTotal++;
         }
      }
      for (IsoPlayer p : IsoPlayer.players) {
         if (p != null) {
            character(p, PLAYER, 1F, sink);
         }
      }
      IsoGameCharacter[] animals = animalsNow; // (the game thread's scan, published whole)
      for (IsoGameCharacter a : animals) {
         if (a.getCurrentSquare() != null) {
            character(a, ANIMAL, ANIMAL_SIZE, sink);
         }
      }
   }

   /** Game thread: the cell's animals every 120 frames (the object set is the game thread's to walk). */
   private static void scanAnimals(IsoCell cell) {
      if (++animalScan < 600) { // (every ~10 s: the cell's set holds the whole horde, 2,400 entries in Louisville; at 120 frames it was ~1 us a frame)
         return;
      }
      animalScan = 0;
      ANIMALS.clear();
      for (IsoMovingObject o : cell.getObjectList()) {
         if (o instanceof zombie.characters.animals.IsoAnimal a) {
            ANIMALS.add(a);
         }
      }
      animalsNow = ANIMALS.isEmpty() ? NO_ANIMALS : ANIMALS.toArray(NO_ANIMALS);
   }

   private static final IsoGameCharacter[] NO_ANIMALS = new IsoGameCharacter[0];
   private static volatile IsoGameCharacter[] animalsNow = NO_ANIMALS;

   /** A character's strides since its last print: a footprint per stride beside the walked line, feet alternating. */
   private static void character(IsoGameCharacter c, int who, float size, Sink sink) {
      float[] EV = sink.ev; // (the sink's arrays: the frame's or the worker's)
      if (c.getZ() >= 0.5F || c.getVehicle() != null) {
         c.pzoptGmX = 0F;
         c.pzoptGmY = 0F;
         return;
      }
      float x = c.getX(), y = c.getY();
      if (c.isDead()) {
         if (!c.pzoptGmDead) {
            c.pzoptGmDead = true;
            float a = c.getAnimAngleRadians();
            int o = sink.event(BODY, who, c);
            if (o >= 0) {
               EV[o] = x;
               EV[o + 1] = y;
               EV[o + 2] = (float)Math.cos(a);
               EV[o + 3] = (float)Math.sin(a);
               EV[o + 4] = size;
            }
         }
         return;
      }
      c.pzoptGmDead = false; // (a pooled zombie comes back alive)
      float lx = c.pzoptGmX, ly = c.pzoptGmY;
      float dx = x - lx, dy = y - ly;
      float d2 = dx * dx + dy * dy;
      float walk = STRIDE_WALK * size;
      if (d2 < walk * walk && (lx != 0F || ly != 0F)) {
         return; // not a stride yet (the common case)
      }
      if (lx == 0F && ly == 0F || d2 > 36F) { // (a jump: teleport, pooled reuse)
         c.pzoptGmX = x;
         c.pzoptGmY = y;
         return;
      }
      boolean running = c.isRunning() || c.isSprinting();
      float stride = (running ? STRIDE_RUN : STRIDE_WALK) * size;
      if (d2 < stride * stride) {
         return;
      }
      float d = (float)Math.sqrt(d2);
      float ux = dx / d, uy = dy / d;
      int steps = Math.min(10, (int)(d / stride));
      boolean crawl = c.isCrawling();
      for (int k = 1; k <= steps; k++) {
         float px = lx + ux * stride * k, py = ly + uy * stride * k;
         if (crawl) {
            int o = sink.event(DRAG, who, c);
            if (o >= 0) {
               EV[o] = px - ux * stride;
               EV[o + 1] = py - uy * stride;
               EV[o + 2] = px;
               EV[o + 3] = py;
               EV[o + 4] = size;
            }
            continue;
         }
         c.pzoptGmFoot ^= 1;
         int pk = dropPaved ? GroundState.peek(px, py) : 0;
         if (pk == 2) {
            pavedDropped++; // pzopt ground-tracks 2026-10-24: a print on known pavement never becomes an event
            continue;
         }
         int o = sink.event(FOOT, who, c);
         if (o >= 0) {
            EV[o] = px;
            EV[o + 1] = py;
            EV[o + 2] = ux;
            EV[o + 3] = uy;
            EV[o + 4] = c.pzoptGmFoot == 0 ? 1F : -1F;
            EV[o + 5] = size;
            EV[o + 6] = running ? 1F : 0F;
            EV[o + 7] = pk; // 1: natural ground under it (GroundState.peek), 0 not known
         }
      }
      c.pzoptGmX = lx + ux * stride * steps;
      c.pzoptGmY = ly + uy * stride * steps;
   }

   // ------------------------------------------------------------------------------------------------ near movers

   private static void near(IsoCell cell, float reach) {
      float cx = zombie.iso.IsoCamera.frameState.camCharacterX, cy = zombie.iso.IsoCamera.frameState.camCharacterY;
      float cz = zombie.iso.IsoCamera.frameState.camCharacterZ;
      float r2 = reach * reach;
      for (IsoPlayer p : IsoPlayer.players) {
         if (p != null && !p.isDead() && p.getVehicle() == null) {
            nearOne(p, PLAYER, cx, cy, cz, r2);
         }
      }
      ArrayList<IsoZombie> zs = cell.getZombieList();
      for (int i = 0, n = zs.size(); i < n && nNear < MAX_NEAR; i++) {
         IsoZombie z = zs.get(i);
         if (z != null && !z.isDead()) {
            nearOne(z, ZOMBIE, cx, cy, cz, r2);
         }
      }
      for (BaseVehicle v : cell.getVehicles()) {
         if (v != null && nNear < MAX_NEAR) {
            nearOne(v, VEHICLE, cx, cy, cz, r2);
         }
      }
   }

   private static final zombie.iso.Vector2 FWD = new zombie.iso.Vector2();

   private static void nearOne(IsoMovingObject o, int who, float cx, float cy, float cz, float r2) {
      float x = o.getX(), y = o.getY(), z = o.getZ();
      if (nNear >= MAX_NEAR || Math.abs(z - cz) > 0.5F || (x - cx) * (x - cx) + (y - cy) * (y - cy) > r2) {
         return;
      }
      Mover m = NEAR[nNear++];
      m.obj = o;
      m.who = who;
      m.x = x;
      m.y = y;
      m.z = z;
      boolean seen = o.pzoptGmSeen == frame - 1;
      float mx = seen ? x - o.pzoptGmLx : 0F, my = seen ? y - o.pzoptGmLy : 0F;
      float moved = (float)Math.sqrt(mx * mx + my * my);
      o.pzoptGmLx = x;
      o.pzoptGmLy = y;
      o.pzoptGmSeen = frame;
      m.speed = dtGame > 0.0 ? (float)(moved / dtGame) : 0F;
      boolean moving = moved > 1e-3F;
      float k = dtGame <= 0.0 ? 0F : (float)Math.min(1.0, dtGame / (moving ? 0.15 : 0.6));
      float s = seen ? o.pzoptGmStrength : 0F;
      o.pzoptGmStrength = s + ((moving ? 1F : 0F) - s) * k;
      m.strength = o.pzoptGmStrength;
      m.melee = false;
      m.meleeRange = 0F;
      m.halfLength = m.halfWidth = 0F;
      if (o instanceof BaseVehicle v) {
         m.radius = 1F;
         v.getWorldPos(0F, 0F, 0F, WP);
         float ox = WP.x, oy = WP.y;
         v.getWorldPos(0F, 0F, 1F, WP);
         float fx = WP.x - ox, fy = WP.y - oy, l = (float)Math.sqrt(fx * fx + fy * fy);
         m.fx = l > 1e-5F ? fx / l : 1F;
         m.fy = l > 1e-5F ? fy / l : 0F;
         zombie.scripting.objects.VehicleScript sc = v.getScript();
         Vector3f ex = sc == null ? null : sc.getExtents();
         m.halfLength = ex == null ? 1.2F : ex.z * 0.5F;
         m.halfWidth = ex == null ? 0.8F : ex.x * 0.5F;
      } else if (o instanceof IsoGameCharacter ch) {
         m.radius = 0.32F;
         m.fx = ch.getForwardDirectionX();
         m.fy = ch.getForwardDirectionY();
         if (ch.isPerformingAttackAnimation() && ch.getPrimaryHandItem() instanceof zombie.inventory.types.HandWeapon hw && !hw.isRanged()) {
            m.melee = true;
            m.meleeRange = hw.getMaxRange();
         }
      } else {
         m.radius = 0.32F;
         m.fx = 1F;
         m.fy = 0F;
      }
   }
}
