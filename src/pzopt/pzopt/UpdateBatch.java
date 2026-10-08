package pzopt;

import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * The simulation bucket's own update loop on the frame workers ({@code entityUpdateParallel}).
 *
 * <p>Stock {@code MovingObjectUpdateSchedulerUpdateBucket.update(int)} walks one of the bucket's sub-lists and, per
 * entity, calls {@code setCurrentSimulationLevel(level)}, {@code preupdate()}, {@code frameStep()} and
 * {@code update()} — in that order, four calls, nothing else. This batch runs that same sequence per entity on the
 * {@link FrameBatch} workers instead of one entity after another on the game thread. The scheduler's other batches
 * ({@link AnimBatch}, {@link ActionEval}, {@link SeparateBatch}) all sit around the simulation; this one is the
 * simulation, which is why it ships off.
 *
 * <p><b>One bucket at a time, never across two.</b> The bucket sets {@code GameTime.perObjectMultiplier} to its own
 * frame mod for the whole of its loop and back to 1 afterwards. That field is a single value on the {@code GameTime}
 * singleton, so it is only constant — and therefore only correct — while one bucket's entities are in flight. Two
 * buckets batched at once would each publish their own multiplier and every entity would read whichever landed last.
 *
 * <p>What stays on the game thread, in the bucket before the batch is filled: the {@code IsoDeadBody} entries (they
 * go to the cell's remove set, shared and order-dependent) and the reused-zombie debug branch. Everything the batch
 * did not take runs inline exactly as before, and an entity that throws on a worker is reported once and turns the
 * batching off for the rest of the session — the bucket then walks stock's loop from the next frame on.
 */
public final class UpdateBatch {
   private UpdateBatch() {
   }

   private static IsoMovingObject[] queue = new IsoMovingObject[4096];
   private static int count;
   private static volatile boolean failed;
   private static volatile boolean inFlight; // set/cleared by run() around FrameBatch.run: the entity batch is on the workers now

   // ── position snapshot (the PZMulticore IsoMovingObjectPatcher layer, pzopt-shaped) ──────────────────────────
   //
   // The first live A/B's residue was 20 zero-length ForwardDirection throws on the GAME thread: a worker read
   // another entity's x/y/z while a second worker was writing them, and the torn value surfaced a frame later.
   // So for the window of one batch, a cross-entity getX()/getY()/getZ() answers from these arrays — the position
   // frozen on the game thread just before dispatch — while a self-read (the entity the current task is updating,
   // CURRENT) stays live. Only the queued entities are snapshotted: everything else (players, animals, grappled
   // zombies) ran inline before run() and is motionless while the batch is in flight.
   //
   // Validity is the per-entity frame stamp against snapshotFrame — no clear pass after the join, a stale index
   // from an earlier batch simply stops matching. Happens-before: the arrays and stamps are written on the game
   // thread BEFORE the volatile write to inFlight; every reader volatile-reads inFlight first (frozen()).
   private static volatile float[] snapX = new float[4096];
   private static volatile float[] snapY = new float[4096];
   private static volatile float[] snapZ = new float[4096];
   private static volatile long snapshotFrame;
   private static final ThreadLocal<IsoMovingObject> CURRENT = new ThreadLocal<>();

   // setForwardDirectionFromIsoDirection's scratch vector. The jar's method writes the direction into the STATIC
   // IsoGameCharacter.tempVector2_2 and reads it back, and getVectorFromDirection ZEROES the vector before the
   // switch assigns it — so with zombies on the workers, any character reading between another's zeroing and its
   // assignment got an exact (0,0) and threw "Forward Direction cannot be zero length vector" (the whole
   // 37-exception Louisville residue: WalkTowardState, ThumpState, ClimbOverFenceState — the last from
   // setDir(IsoDirections.N), a constant, which is what ruled the states' own math out). Between throws the same
   // race silently handed a walker another zombie's direction. Per-thread vector, same idiom as VehicleCull.near.
   private static final ThreadLocal<zombie.iso.Vector2> DIR_SCRATCH = ThreadLocal.withInitial(zombie.iso.Vector2::new);

   /** This thread's direction scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 dirScratch() {
      return DIR_SCRATCH.get();
   }

   // The per-task perObjectMultiplier override: NaN = not inside a batch task (read the live field). A
   // float[1] holder instead of ThreadLocal<Float> so the per-task set/clear never boxes. Written by the
   // batch runner from the value captured at dispatch; read by pom() at the five consumer sites so a task
   // keeps its bucket's multiplier even after the game thread has moved on to the next bucket's write.
   private static final ThreadLocal<float[]> POM = ThreadLocal.withInitial(() -> new float[]{Float.NaN});

   /** The perObjectMultiplier this thread should see: the dispatch-time capture inside a batch task, the live field otherwise. */
   public static float pom(zombie.GameTime gameTime) {
      float v = POM.get()[0];
      return v == v ? v : gameTime.perObjectMultiplier; // v==v is false only for the NaN sentinel
   }

   // IsoGameCharacter's static tempo/tempo2 scratch vectors, per thread (the dirScratch disease's siblings:
   // faceThisObject fills the static tempo and hands it to DirectionFromVector, so two zombies facing
   // anything concurrently share one vector — run lou-pipe-off caught ZombieEatBodyState throwing the
   // zero-length exception through exactly that path). Worker-reachable users read these; the debug/render/
   // death-path users keep the statics.
   private static final ThreadLocal<zombie.iso.Vector2> TEMPO = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<zombie.iso.Vector2> TEMPO2 = ThreadLocal.withInitial(zombie.iso.Vector2::new);

   /** This thread's tempo scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 tempoScratch() {
      return TEMPO.get();
   }

   /** This thread's tempo2 scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 tempo2Scratch() {
      return TEMPO2.get();
   }

   // WalkTowardState's singleton temp/worldPos scratch, per thread (the State instance is shared by every
   // walking zombie; its fields are the same disease as the statics above).
   private static final ThreadLocal<zombie.iso.Vector2> WALK = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<org.joml.Vector3f> WALK3 = ThreadLocal.withInitial(org.joml.Vector3f::new);

   /** This thread's WalkTowardState temp scratch, for that override. */
   public static zombie.iso.Vector2 walkScratch() {
      return WALK.get();
   }

   /** This thread's WalkTowardState worldPos scratch, for that override. */
   public static org.joml.Vector3f walkScratch3() {
      return WALK3.get();
   }

   // PathFindBehavior2's four static scratch objects, per thread (the same disease again, and the one the
   // "async pathfinding race" was misread as). The class holds tempVector2, tempVector2_2, tempVector3f_1 and
   // pointOnPath as STATIC mutable objects, and every batch task's update() writes and reads them. pointOnPath
   // is the sharp one: update() calls closestPointOnPath(..., this.path, pointOnPath) and reads
   // pointOnPath.pathIndex back on the NEXT line, so a task landing between the two statements takes an index
   // derived from another character's path — too large and the following nodes.get throws IndexOutOfBounds (the
   // 77-958 counted escapes a route), in range and the zombie silently walks the wrong segment of its own path.
   // The vectors tear the same way: getDeferredMovement(tempVector2_2) then moveUnmodded(tempVector2_2.x, ...)
   // thirty lines later moves this character by whatever another one wrote in between, and the
   // getLengthSquared() > 0 guard in front of setForwardDirection can be true when the read a line later is
   // (0,0). Per-thread is exact single-threaded: one thread sees one object, written then read inside one
   // method, residual state included (closestPointOnPath resets pathIndex but not dist, and advanceAlongPath
   // accumulates into dist — stock carries that over from the previous call on the static, and a thread's own
   // holder carries it over the same way, now only from its own calls).
   private static final ThreadLocal<zombie.iso.Vector2> PATH = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<zombie.iso.Vector2> PATH2 = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<org.joml.Vector3f> PATH3 = ThreadLocal.withInitial(org.joml.Vector3f::new);
   private static final ThreadLocal<zombie.pathfind.PathFindBehavior2.PointOnPath> PATH_POINT =
         ThreadLocal.withInitial(zombie.pathfind.PathFindBehavior2.PointOnPath::new);

   /** This thread's PathFindBehavior2 tempVector2 scratch, for that override. */
   public static zombie.iso.Vector2 pathScratch() {
      return PATH.get();
   }

   /** This thread's PathFindBehavior2 tempVector2_2 scratch, for that override. */
   public static zombie.iso.Vector2 pathScratch2() {
      return PATH2.get();
   }

   /** This thread's PathFindBehavior2 tempVector3f_1 scratch, for that override. */
   public static org.joml.Vector3f pathScratch3() {
      return PATH3.get();
   }

   /** This thread's PathFindBehavior2 pointOnPath scratch, for that override. */
   public static zombie.pathfind.PathFindBehavior2.PointOnPath pathPointScratch() {
      return PATH_POINT.get();
   }

   /**
    * True when this entity's position must be read from the snapshot: a batch is in flight, the entity is in it
    * (its stamp matches this batch), and the caller is not the task updating it. Called by the IsoMovingObject
    * override's getX/getY/getZ — with the key off the volatile is always false and nothing else is read.
    */
   public static boolean frozen(IsoMovingObject entity) {
      return inFlight && entity.pzoptSnapshotFrame == snapshotFrame && entity != CURRENT.get();
   }

   /** The frozen X of an entity {@link #frozen} said yes for. */
   public static float frozenX(IsoMovingObject entity) {
      return snapX[entity.pzoptSnapshotIndex];
   }

   /** The frozen Y of an entity {@link #frozen} said yes for. */
   public static float frozenY(IsoMovingObject entity) {
      return snapY[entity.pzoptSnapshotIndex];
   }

   /** The frozen Z of an entity {@link #frozen} said yes for. */
   public static float frozenZ(IsoMovingObject entity) {
      return snapZ[entity.pzoptSnapshotIndex];
   }

   // ── setMovingSquare deferral ────────────────────────────────────────────────────────────────────────────────
   //
   // setMovingSquare mutates the target square's shared MovingObjects ArrayList; two workers landing entities on
   // one square is a plain list race. A call made from inside a batched entity's update is latched on the entity
   // (last call wins, like the serial loop's end state) and replayed by the game thread right after the join. The
   // PZMulticore reference skips the call outright; a pzopt batch can replay on the game thread, so nothing goes
   // stale. The latched entity need not be in the queue — an update may set a square on another entity.
   private static final java.util.concurrent.ConcurrentLinkedQueue<IsoMovingObject> deferredSquares =
         new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static long movingSquareDeferred;

   /**
    * Called by the IsoMovingObject override at the top of setMovingSquare. True = the call was latched (the
    * caller returns without touching the square lists); false = no batch window, run the stock body.
    */
   public static boolean deferMovingSquare(IsoMovingObject entity, zombie.iso.IsoGridSquare square) {
      if (!inFlight || CURRENT.get() == null) {
         return false;
      }

      if (entity.pzoptDeferredSquareFrame != snapshotFrame) {
         entity.pzoptDeferredSquareFrame = snapshotFrame;
         deferredSquares.add(entity); // two racing stamp reads may add twice; the replay's stamp check drops the twin
      }

      entity.pzoptDeferredSquare = square;
      return true;
   }

   // ── Lua capture-and-replay (entityUpdateLuaReplay) ─────────────────────────────────────────────────────────
   //
   // xD3I's AnimParallel pattern applied to the update batch: a worker mid-batch reaching triggerEvent appends
   // (event, args…) to its task's capture list instead of dropping the dispatch; after the join the game thread
   // walks the tasks in queue order — stock's serial event order — and fans each record back through the real
   // triggerEvent overloads, still inside the bucket window so handlers run under the bucket's
   // perObjectMultiplier exactly as stock's inline dispatch did (AnimParallel dispatches under useMultiplier for
   // the same reason: ThumpState counts strikes over it). With the key off the guard counts and drops, as before.
   private static java.util.ArrayList<Object[]>[] luaCaptures = newLuaCaptureArray(4096);
   private static final ThreadLocal<java.util.ArrayList<Object[]>> LUA_CAPTURE = new ThreadLocal<>();
   private static long luaCaptured, luaReplayed;

   @SuppressWarnings("unchecked")
   private static java.util.ArrayList<Object[]>[] newLuaCaptureArray(int size) {
      return new java.util.ArrayList[size];
   }

   // ── Deferred emitter ticks (emitterDefer) ──────────────────────────────────────────────────────────────
   //
   // The same per-task slot shape as the Lua capture: a batched zombie reaching updateEmitter queues ITSELF
   // on its task's slot instead of ticking FMOD on the worker (the ticks serialized on the emitter monitors,
   // against each other and against the inline player's combat — and the prone branch writes a STATIC bone
   // scratch). joinPending runs the queued ticks on the game thread in queue order — stock's serial order —
   // under the flight's captured multiplier. The tick still lands in the same frame, before anything renders.
   private static java.util.ArrayList<zombie.characters.IsoGameCharacter>[] emitterCaptures = newEmitterCaptureArray(4096);
   private static final ThreadLocal<java.util.ArrayList<zombie.characters.IsoGameCharacter>> EMITTER_CAPTURE = new ThreadLocal<>();
   private static long emitterDeferred, emitterDrained;

   @SuppressWarnings("unchecked")
   private static java.util.ArrayList<zombie.characters.IsoGameCharacter>[] newEmitterCaptureArray(int size) {
      return new java.util.ArrayList[size];
   }

   /**
    * A batch task reached {@code updateEmitter}: queue the character for the join's drain and return true.
    * Off a batch task (or with {@code emitterDefer} off at dispatch) the slot is unset and the caller runs
    * the stock body in place — the inline path never defers.
    */
   public static boolean deferEmitter(zombie.characters.IsoGameCharacter character) {
      java.util.ArrayList<zombie.characters.IsoGameCharacter> list = EMITTER_CAPTURE.get();
      if (list == null) {
         return false;
      }

      list.add(character);
      emitterDeferred++;
      return true;
   }

   /**
    * Game thread, after the join: every task's queued emitter ticks through the real updateEmitter, in queue
    * order. {@code flightPomArr} is set on the combined path — that flight spans every bucket of the frame, so each
    * tick must land under its own entity's time scale — and null on the sync path, where the flight's single
    * {@code flightPom} covers every task, as it always did.
    */
   private static void drainEmitters(int n) {
      float[] h = POM.get(); // joinPending set it to flightPom already; this is the same holder array
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         java.util.ArrayList<zombie.characters.IsoGameCharacter> list = emitterCaptures[i];
         if (list == null || list.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom; // spec 3.5: stock's per-bucket multiplier per entity
         for (int j = 0; j < list.size(); j++) {
            list.get(j).updateEmitter(); // EMITTER_CAPTURE is unset on the game thread, so the stock body runs
            emitterDrained++;
         }

         list.clear();
      }
   }

   // ── Deferred native physics (physicsDefer) ─────────────────────────────────────────────────────────────
   //
   // The emitter slot's shape again, for the two Bullet calls a batched entity's update can make. This one is
   // not an optimization: with the batch on, shooting zombies killed the process in both live runs — SIGSEGV in
   // libPZBullet64's btHashedOverlappingPairCache::removeOverlappingPair on a pzopt-frame worker, under
   // IsoGameCharacter.updateInternal -> releaseBallisticsTarget -> BallisticsTarget.removeFromWorld. Bullet is
   // not thread-safe, the game thread makes its own Bullet calls in the same window, and a native crash never
   // reaches flightFailure, so the failure latch cannot turn the batch off after the fact: the calls have to be
   // off the workers. Locking would not be enough either — BallisticsTarget.boneTransformData is a PUBLIC
   // STATIC float[] that getBoneTransforms fills and hands straight to the native, and initialize() replaces it
   // with a longer array when a taller skeleton arrives; the game thread's own BallisticsTarget.add() writes the
   // same buffer. Deferring is what fixes that, because add() is game-thread-only.
   //
   //   ballistics: IsoGameCharacter.updateBallisticsTarget, the one caller of BallisticsTarget.update() and the
   //     only route from an entity update to addBallisticsTarget / setBallisticsTargetAxis /
   //     updateBallisticsTarget / updateBallisticsTargetSkeleton / removeBallisticsTarget (the last two through
   //     the true return and releaseBallisticsTarget). CombatManager gives every character a gun is aimed at a
   //     target, so with a rifle up this runs per aimed-at zombie per frame.
   //   ragdolls: IsoZombie.updateInternal's RagdollController.vehicleCollision, which reaches
   //     setRagdollBodyDynamics / resetRagdollBodyDynamics with the STATIC vehicleRagdollBodyDynamicsParams and
   //     calls BaseVehicle.testTouchingVehicle on a vehicle the game thread may be updating that instant. Rare
   //     (a ragdolling zombie in contact with a car), which is why it had not crashed yet; same hazard. It needs
   //     the vehicle as well as the zombie, because updateInternal nulls vehicle4testCollision a few lines on —
   //     hence a queue of pairs rather than of characters.
   //
   // Both queues live on one per-task slot under one key: same flight, same drain point, same reason. The drain
   // is at joinPending like the emitters', before the Lua replay (a handler must not read a stale hitbox), and
   // ragdolls before ballistics because that is their order inside one zombie's update (vehicleCollision sits
   // above IsoZombie.updateInternal's super.update(), which is what reaches updateBallisticsTarget).
   private record RagdollHit(zombie.characters.IsoZombie zombie, zombie.vehicles.BaseVehicle vehicle) {
   }

   private static final class PhysicsSlot {
      final java.util.ArrayList<zombie.characters.IsoGameCharacter> ballistics = new java.util.ArrayList<>();
      final java.util.ArrayList<RagdollHit> ragdolls = new java.util.ArrayList<>();
   }

   private static PhysicsSlot[] physicsSlots = new PhysicsSlot[4096];
   private static final ThreadLocal<PhysicsSlot> PHYSICS_CAPTURE = new ThreadLocal<>();
   private static long ballisticsDeferred, ballisticsDrained, ragdollDeferred, ragdollDrained;

   /**
    * A batch task reached {@code updateBallisticsTarget} with a live target: queue the character for the join's
    * drain and return true. The caller checks {@code ballisticsTarget != null} BEFORE asking, so 12,000 zombies
    * do not queue an entry a frame for a target almost none of them have. A plain reference read is atomic, so
    * the worker sees either null or a valid target: a stale null only means the target — created this frame by
    * the player's shot — updates one frame later, and a stale non-null is harmless because the game thread
    * re-reads the field at the drain. Off a batch task (or with {@code physicsDefer} off at dispatch) the slot
    * is unset and the caller runs the stock body in place.
    */
   public static boolean deferBallistics(zombie.characters.IsoGameCharacter character) {
      PhysicsSlot slot = PHYSICS_CAPTURE.get();
      if (slot == null) {
         return false;
      }

      slot.ballistics.add(character);
      ballisticsDeferred++;
      return true;
   }

   /**
    * A batch task reached {@code RagdollController.vehicleCollision}: queue the zombie with the vehicle it was
    * handed (the field is nulled later in the same method, so the reference must travel with the entry) and
    * return true. Off a batch task the slot is unset and the caller makes the call in place.
    */
   public static boolean deferRagdollVehicle(zombie.characters.IsoZombie zombie, zombie.vehicles.BaseVehicle vehicle) {
      PhysicsSlot slot = PHYSICS_CAPTURE.get();
      if (slot == null) {
         return false;
      }

      slot.ragdolls.add(new RagdollHit(zombie, vehicle));
      ragdollDeferred++;
      return true;
   }

   /**
    * Game thread, after the join: every task's queued hitbox updates through the override's drain entry point,
    * in queue order, each under its own entity's multiplier (see {@link #drainEmitters}). The entry point calls
    * the private stock method, which re-reads {@code ballisticsTarget} — so a target released between the
    * worker's read and here is simply not updated, and the real work happens with the slot unset.
    */
   private static void drainBallistics(int n) {
      float[] h = POM.get(); // joinPending set it to flightPom already; this is the same holder array
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         PhysicsSlot slot = physicsSlots[i];
         if (slot == null || slot.ballistics.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom;
         for (int j = 0; j < slot.ballistics.size(); j++) {
            slot.ballistics.get(j).pzoptUpdateBallisticsTarget();
            ballisticsDrained++;
         }

         slot.ballistics.clear();
      }
   }

   /** Game thread, after the join: every task's queued ragdoll-versus-vehicle contact tests, in queue order. */
   private static void drainRagdolls(int n) {
      float[] h = POM.get();
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         PhysicsSlot slot = physicsSlots[i];
         if (slot == null || slot.ragdolls.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom;
         for (int j = 0; j < slot.ragdolls.size(); j++) {
            RagdollHit hit = slot.ragdolls.get(j);
            hit.zombie().pzoptVehicleCollision(hit.vehicle()); // the override re-reads the ragdoll controller
            ragdollDrained++;
         }

         slot.ragdolls.clear();
      }
   }

   /**
    * A worker mid-batch reached a {@code triggerEvent} overload (the override calls this from behind its
    * {@link #onWorkerNow} guard): capture the dispatch for the game thread's replay, or count-and-drop when
    * {@code entityUpdateLuaReplay} is off. The varargs array only ever allocates on this already-guarded path.
    */
   public static void captureLuaEvent(String event, Object... params) {
      java.util.ArrayList<Object[]> list = LUA_CAPTURE.get();
      if (list == null || !Config.ENTITY_UPDATE_LUA_REPLAY) {
         onLuaSuppressed();
         return;
      }

      Object[] record = new Object[params.length + 1];
      record[0] = event;
      System.arraycopy(params, 0, record, 1, params.length);
      list.add(record);
   }

   /**
    * A batched entity's update reached a void Java -> Lua call rather than an event (pzopt.LuaGate, the LuaCaller
    * override): a zombie trampling a crop or walking through a lit campfire, a mod's function. It joins the task's
    * capture list and the replay runs it on the game thread in stock's order. False (the caller runs it on the game
    * thread itself) when no task of this thread is capturing or the replay is off.
    */
   public static boolean captureLuaCall(Runnable call) {
      if (!onWorkerNow() || !Config.ENTITY_UPDATE_LUA_REPLAY) {
         return false;
      }
      java.util.ArrayList<Object[]> list = LUA_CAPTURE.get();
      if (list == null) {
         return false;
      }
      list.add(new Object[] {call});
      return true;
   }

   /**
    * Game thread, after the join: every task's captured events through the real dispatch, in queue order, each
    * under its own entity's multiplier (the combined path's {@code flightPomArr} — see {@link #drainEmitters}).
    */
   private static void replayLuaEvents(int n) {
      float[] h = POM.get();
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         java.util.ArrayList<Object[]> list = luaCaptures[i];
         if (list == null || list.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom; // spec 3.5: the handlers of this entity's events see its time scale
         luaCaptured += list.size();
         for (int j = 0; j < list.size(); j++) {
            Object[] r = list.get(j);
            if (r[0] instanceof Runnable call) { // captureLuaCall: a direct Lua call, run where stock ran it
               call.run();
               luaReplayed++;
               continue;
            }
            String e = (String) r[0];
            switch (r.length) {
               case 1 -> zombie.Lua.LuaEventManager.triggerEvent(e);
               case 2 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1]);
               case 3 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2]);
               case 4 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3]);
               case 5 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4]);
               case 6 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5]);
               case 7 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6]);
               case 8 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
               default -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
            }

            luaReplayed++;
         }

         list.clear();
      }
   }

   /** How many worker-fired Lua events the capture has taken this session. */
   public static long getLuaCapturedCount() {
      return luaCaptured;
   }

   /** How many captured Lua events the game thread has replayed this session. */
   public static long getLuaReplayedCount() {
      return luaReplayed;
   }

   /** Game thread, after the join: apply the last latched square per entity through the real setMovingSquare. */
   private static void applyDeferredSquares() {
      IsoMovingObject entity;
      while ((entity = deferredSquares.poll()) != null) {
         if (entity.pzoptDeferredSquareFrame != snapshotFrame) {
            continue; // a duplicate queue entry: this entity was already applied
         }

         entity.pzoptDeferredSquareFrame = 0L;
         zombie.iso.IsoGridSquare square = entity.pzoptDeferredSquare;
         entity.pzoptDeferredSquare = null;
         entity.setMovingSquare(square); // inFlight is false again, so this runs the stock body
         movingSquareDeferred++;
      }
   }

   /**
    * True only on a {@link FrameBatch.Worker} while this batch's entities are in flight — the one predicate every
    * thread-safety guard of {@code entityUpdateParallel} keys on (the ForwardDirection throw, the LuaEventManager
    * suppression, the PathFindBehavior2 clone and catch). Two conditions, both load-bearing:
    *
    * <ul>
    *   <li>{@code inFlight}: the scheduler's other batches ({@code AnimBatch}, {@code ActionEval},
    *       {@code LightingBatch}, {@code SeparateBatch}) run on the same workers but never call Lua or the guarded
    *       entity paths by design, so a guard must not fire for them; {@code FrameBatch} runs one batch at a time
    *       (run() joins any pending async batch first), so while this flag is up the only tasks on the workers are
    *       this batch's entities.
    *   <li>the {@code instanceof} check: the game thread works the batch too, and an entity it updates must behave
    *       exactly as with the key off — Lua allowed, the zero-length throw kept, the live path list read.
    * </ul>
    *
    * <p>Cheap on the game-thread fast path: with the key off the volatile is always false and the thread check is
    * never reached.
    */
   public static boolean onWorkerNow() {
      return inFlight && Thread.currentThread() instanceof FrameBatch.Worker;
   }

   /**
    * True while this thread is running one of the batch's entity tasks — a worker OR the game thread working
    * the batch. The pathfind guard's predicate: every PathFindState escape of the live runs (4 in
    * lou-replay-clean, 1 in lou-fwd-scratch) was the game-thread participant reading the live path list while
    * the pathfind thread or another worker's group-leader pathToLocation mutated it — mid-batch the participant
    * faces the same writers a worker does, so it snapshots and skips like one. Outside a batch CURRENT is never
    * set and vanilla behaviour is untouched (deferMovingSquare has used this same condition from the start).
    */
   public static boolean onBatchTaskNow() {
      return inFlight && CURRENT.get() != null;
   }

   public static long frames, batched, maxBatch, workNanos, waitNanos;

   /** True while a bucket should hand its scheduled entities over instead of walking them itself. */
   public static boolean enabled() {
      return Config.ENTITY_UPDATE_PARALLEL && !failed && Config.effectiveWorkers() > 1
            && multiplayerAllowed(GameClient.client, GameServer.server, Config.ENTITY_UPDATE_SERVER)
            && Overrides.enabled() && GtAb.on(GtAb.ZOMBIE_UPDATE); // devGtAlternate: the within-run A/B
   }

   /** The authoritative server may opt in; an MP client never owns this simulation work. */
   static boolean multiplayerAllowed(boolean client, boolean server, boolean serverEnabled) {
      return !client && (!server || serverEnabled);
   }

   /** Calm states accepted by the safe-state filter; the network walk exists only in the authoritative server loop. */
   public static boolean safeState(zombie.ai.State state, boolean server, boolean serverEnabled) {
      return state == zombie.ai.states.ZombieIdleState.instance()
            || state == zombie.ai.states.WalkTowardState.instance()
            || state == zombie.ai.states.PathFindState.instance()
            || server && serverEnabled && state == zombie.ai.states.WalkTowardNetworkState.instance();
   }

   /**
    * Whether an entity of this type may update on a worker at all. ActionEval's rule: players and animals stay
    * inline. The player is the one that matters — {@code IsoPlayer.update()} runs Lua (input, timed actions, the
    * {@code OnPlayer*} events), nothing here guards against Lua re-entry from a worker, and the scheduler puts
    * every player in the FULL bucket, so a batch that did not exclude them would hand them to a worker on the
    * first frame. Animals follow the same rule as in ActionEval rather than being assumed safe.
    *
    * <p>Vehicles and physics objects stay inline too, found by the first hour-long live session (2026-09-26):
    * an active vehicle's {@code update()} reaches Lua part scripts ({@code updateParts} →
    * {@code VehicleParts.callLuaVoid}) — the wrong-thread guard errored 26 times on the workers and the 27th
    * corrupted the Kahlua VM stack ("Index -4 out of bounds for length 1000"), latching batching off. The bench
    * route is on foot, so no active vehicle ever updated mid-batch before real play did it. IsoPhysicsObject
    * steps native physics and was never audited — same rule.
    */
   public static boolean batchableType(Class<?> type) {
      return !IsoPlayer.class.isAssignableFrom(type) && !IsoAnimal.class.isAssignableFrom(type)
            && !zombie.vehicles.BaseVehicle.class.isAssignableFrom(type)
            && !zombie.iso.IsoPhysicsObject.class.isAssignableFrom(type);
   }

   /** Whether this entity may update on a worker: its type, plus the per-zombie cases that read another entity. */
   public static boolean batchable(IsoMovingObject entity) {
      if (!batchableType(entity.getClass())) {
         return false;
      }

      // Grapple and a reanimated player read the other character while updating; ActionEval excludes the same three.
      if (entity instanceof IsoZombie zombie) {
         if (Config.ENTITY_UPDATE_SAFE_STATES) {
            return zombie.pzoptBatchSafe(); // the calm-state whitelist (gt-offload pass, 2026-09-27): no Bullet, no falling, no player near
         }
         return !zombie.isBeingGrappled() && !zombie.isGrappling() && zombie.getReanimatedPlayer() == null;
      }

      return true;
   }

   /** True once an entity threw on a worker: the session is back on stock's loop. */
   public static boolean hasFailed() {
      return failed;
   }

   /** How many entities the current bucket has handed over and not yet run. */
   public static int pending() {
      return count;
   }

   /** Game thread, from the bucket's loop: this entity updates this frame and can run on a worker. */
   public static void add(IsoMovingObject entity) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }

      queue[count++] = entity;
   }

   // ── the pipeline's one airborne batch (entityUpdatePipeline): dispatched without a join so the game
   // thread can collect the NEXT bucket while these tasks run; joined before the next dispatch (and after the
   // last bucket, from the scheduler override), so at most one batch is ever in flight — FrameBatch's own
   // one-batch rule, kept. The flight owns its entity array (double-buffered with the collection queue), its
   // dispatch-time perObjectMultiplier (replay runs under it) and its Lua capture count.
   private static IsoMovingObject[] spareQueue;
   private static IsoMovingObject[] flightArr;
   private static int flightN;
   private static float flightPom;
   private static boolean flightLuaReplay;
   private static boolean flightPending;
   private static volatile Throwable flightFailure;
   private static int snapAppend; // inline entities stamped into the in-flight snapshot append here

   /** True while a dispatched batch has not been joined yet. */
   public static boolean hasPendingBatch() {
      return flightPending;
   }

   // ── combined dispatch (spec 2026-09-27): the whole frame's batchables in one workers-only flight ──
   //
   // The per-bucket pipeline overlapped bucket N's flight with bucket N+1's collection, and the overlap window
   // was measurably empty: the dominant batch is FULL's and the buckets after it are trivial, so `async helped`
   // was ~100 % of `batched` — the game thread reached the join before the workers woke and did all the work
   // itself. On a combined frame every bucket only QUEUES: batchables here with their bucket's multiplier and
   // simulation level stamped at queue time, everything else into the inline queue. After the last bucket the
   // scheduler dispatches the whole frame at once and runs the inline entities while the flight is airborne —
   // that game-thread work is the runway: then joins at its tail, before updateZombieVocals(). Animals are the
   // exception since animalsAfterJoin: their own queue, run after that join (see runAnimalPhase).
   private static boolean combinedFrame; // latched once per frame (scheduler.update entry); never read from the wall clock mid-frame
   private static float[] queuePom = new float[4096];   // per-entity: its bucket's perObjectMultiplier at add()
   private static int[] queueLevel = new int[4096];     // per-entity: its bucket's simulationLevel ordinal
   private static float[] sparePom;                     // double-buffered with the flight, like spareQueue
   private static int[] spareLevel;
   private static float[] flightPomArr;                 // the airborne batch's per-task multipliers (replay/drain read these)
   private static int[] flightLevelArr;
   private static IsoMovingObject[] inlineQueue = new IsoMovingObject[1024];
   private static float[] inlinePom = new float[1024];
   private static int[] inlineLevel = new int[1024];
   private static int inlineCount;
   private static long nestedJoins, combinedFrames, inlineQueued;

   // ── animalsAfterJoin (2026-09-28): the frame's animals update after the landing, not under the flight ──
   //
   // With a herd near the horde the inline phase cost more than the overlap gained (docs/findings-gt-offload-2026-09-27.md,
   // "PR #35 update"): an animal's sight walk reads every zombie through getX / getY / getZ / getCurrentSquare, each of
   // those went through frozen() and the snapshot while the workers wrote the same objects, and animal LOS with 60 cows
   // grew by 1.3 (flip), 1.6 (desktop) and 4.9 ms (Mac). So on a combined frame queueInline sends an animal here instead,
   // stamped exactly like the inline queue, and runAnimalPhase updates them after joinPending: the zombies are settled,
   // the reads are direct, and no worker writes what the herd reads. The routing is this class's policy, so the bucket
   // override is unchanged. The key is final; the field below is read instead so the off arm is reachable from a test.
   private static boolean animalsAfterJoin = Config.ANIMALS_AFTER_JOIN;
   private static IsoMovingObject[] animalQueue = new IsoMovingObject[256];
   private static float[] animalPom = new float[256];
   private static int[] animalLevel = new int[256];
   private static int animalCount;
   private static long animalsRun; // animals updated by runAnimalPhase this session: `animalsAfterJoin=` on the status line

   // The runway metric, summed per ENTITY flight instead of read out of FrameBatch when describe() prints.
   // FrameBatch.lastPreClaimed() is a single value published by whichever join ran last, and the pool is shared:
   // AnimBatch, LightingBatch, SeparateBatch and CharDraw all dispatch and join through FrameBatch, several of them
   // LATER in the same frame than this flight — so by print time that number is the animation batch's, not ours, and
   // the acceptance measurement would be of the wrong batch. joinPending() reads it at THIS flight's join and adds it
   // here. Cumulative, like batched, so preClaimed / batched is the runway fraction directly: the ~0 of the old
   // per-bucket shape is the defect, its rise is the combined shape's evidence. Every landing counts, the sync path's
   // near-zero included — that contribution is the control arm and belongs in the same reading.
   private static long preClaimed;

   // values() allocates a fresh array per call, and the combined runner decodes a level per task — thousands of
   // tasks a frame. The enum is immutable, so one copy for the session.
   private static final UpdateSchedulerSimulationLevel[] LEVELS = UpdateSchedulerSimulationLevel.values();

   /**
    * Scheduler entry, once per frame: latch whether this frame runs the combined shape — exactly the value it is
    * handed, no gate of its own. Reading the wall clock (devPipelineAlternate) once per frame means a window flip can
    * never produce a mixed frame (spec 3.1). The test seam takes the value directly, which is why the gate lives in
    * {@link #latchFrameFromConfig()} and not here: with {@code enabled()} folded in, this latched false whatever the
    * caller asked for whenever the feature was off — a bare JVM is such a case ({@code entityUpdateParallel} defaults
    * to false, so {@code enabled()} short-circuits there; {@code Overrides.enabled()} itself is true against a built
    * classpath) — and {@link #combinedFrame()} plus the two resets below were then unreachable from a test.
    */
   public static void latchFrame(boolean combined) {
      combinedFrame = combined;
      // A frame that threw before its animal phase (a bucket, the inline phase or the join) leaves its herd queued.
      // Dropped on either arm, not only on a combined frame like the inline queue below: after such a frame a combined
      // one may never come again (the failure latch, a multiplayer session), and the slots would pin those animals and
      // their world for the rest of the process. Empty on every frame that queued nothing, so a no-op there.
      if (animalCount > 0) {
         dropAnimals();
      }
      if (combinedFrame) {
         clear();
         // The abandoned inline entities are nulled, not just forgotten: a frame that threw between queueInline and
         // runInlinePhase leaves its references in the array, and a slot past the new frame's inlineCount is never
         // overwritten — without this a single dropped frame pins those entities for the rest of the session.
         java.util.Arrays.fill(inlineQueue, 0, inlineCount, null);
         inlineCount = 0;
         combinedFrames++;
      }
   }

   /**
    * The production latch, and the one owner of the gate: {@link #pipelineOn()} (the key, or the
    * devPipelineAlternate window) AND {@link #enabled()} (the parallel key, the failure latch, the worker count,
    * singleplayer, the override build check), both read once here at the scheduler's frame entry. Both are needed —
    * {@code pipelineOn()} does not imply {@code enabled()}, it reads the pipeline key alone.
    */
   public static boolean latchFrameFromConfig() {
      latchFrame(pipelineOn() && enabled());
      return combinedFrame;
   }

   /** True while this frame's buckets must queue instead of executing (spec 3.2). */
   public static boolean combinedFrame() {
      return combinedFrame;
   }

   /** Combined add: the entity plus its bucket's multiplier (the live global — the bucket just set it) and level. */
   public static void add(IsoMovingObject entity, int simulationLevelOrdinal) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
         queuePom = java.util.Arrays.copyOf(queuePom, count * 2);
         queueLevel = java.util.Arrays.copyOf(queueLevel, count * 2);
      }
      if (queuePom.length < queue.length) { // the sync-path add() grew queue alone in an earlier frame
         queuePom = java.util.Arrays.copyOf(queuePom, queue.length);
         queueLevel = java.util.Arrays.copyOf(queueLevel, queue.length);
      }

      queuePom[count] = zombie.GameTime.getInstance().perObjectMultiplier;
      queueLevel[count] = simulationLevelOrdinal;
      queue[count++] = entity;
   }

   /**
    * Combined mode: an inline (non-batchable) entity, deferred to runInlinePhase under the flight (spec 3.4). An animal
    * goes to runAnimalPhase after the landing instead ({@code animalsAfterJoin}), with the same two stamps.
    */
   public static void queueInline(IsoMovingObject entity, int simulationLevelOrdinal) {
      if (animalsAfterJoin && entity instanceof IsoAnimal) {
         queueAnimal(entity, simulationLevelOrdinal);
         return;
      }

      if (inlineCount == inlineQueue.length) {
         inlineQueue = java.util.Arrays.copyOf(inlineQueue, inlineCount * 2);
         inlinePom = java.util.Arrays.copyOf(inlinePom, inlineCount * 2);
         inlineLevel = java.util.Arrays.copyOf(inlineLevel, inlineCount * 2);
      }

      inlinePom[inlineCount] = zombie.GameTime.getInstance().perObjectMultiplier;
      inlineLevel[inlineCount] = simulationLevelOrdinal;
      inlineQueue[inlineCount++] = entity;
      inlineQueued++;
   }

   /** queueInline's animal arm: the inline queue's stamping, into the queue runAnimalPhase walks after the join. */
   private static void queueAnimal(IsoMovingObject entity, int simulationLevelOrdinal) {
      if (animalCount == animalQueue.length) {
         animalQueue = java.util.Arrays.copyOf(animalQueue, animalCount * 2);
         animalPom = java.util.Arrays.copyOf(animalPom, animalCount * 2);
         animalLevel = java.util.Arrays.copyOf(animalLevel, animalCount * 2);
      }

      animalPom[animalCount] = zombie.GameTime.getInstance().perObjectMultiplier; // the bucket just set it: its frame mod
      animalLevel[animalCount] = simulationLevelOrdinal;
      animalQueue[animalCount++] = entity;
   }

   /** Null the animal queue's used slots and empty it: after the phase (thrown or not) and at every frame latch. */
   private static void dropAnimals() {
      java.util.Arrays.fill(animalQueue, 0, animalCount, null);
      animalCount = 0;
   }

   /** The nested-batch guard's landing (spec 3.4): full bookkeeping, counted, dev-logged once. */
   public static void nestedJoin() {
      nestedJoins++;
      if (nestedJoins == 1L && Config.DEV) {
         Log.info("combined dispatch: nested FrameBatch use landed the flight first\n" + stackOf(new Throwable("caller")));
      }

      joinPending();
   }

   /** How many times a nested batch user landed the combined flight this session. */
   public static long getNestedJoins() {
      return nestedJoins;
   }

   /** Tasks the workers had claimed before the game thread reached the join, summed over every entity flight. */
   public static long getPreClaimed() {
      return preClaimed;
   }

   /** Test seam: undo the failure latch between CombinedDispatchTest sections. */
   public static void resetFailedForTest() {
      failed = false;
   }

   /** Test seam: route animals as {@code animalsAfterJoin=on} would (the key is final, read once at class init). */
   public static void setAnimalsAfterJoinForTest(boolean on) {
      animalsAfterJoin = on;
   }

   /** How many animals the after-join phase has updated this session. */
   public static long getAnimalsAfterJoin() {
      return animalsRun;
   }

   private static long altWindow = Long.MIN_VALUE; // devPipelineAlternate: last logged window index

   /**
    * Whether this bucket dispatch uses the pipeline. Plain ENTITY_UPDATE_PIPELINE normally; with
    * devPipelineAlternate=N the answer flips every N seconds inside the run — same zombies, same spawn,
    * same thermals for both modes — and each flip is logged with its epoch so the frame log splits into
    * paired windows (drop the first window of each pair as warm-up when analysing).
    */
   public static boolean pipelineOn() {
      int alt = Config.DEV_PIPELINE_ALTERNATE;
      if (alt <= 0) {
         return Config.ENTITY_UPDATE_PIPELINE;
      }
      long now = System.currentTimeMillis();
      long window = now / (alt * 1000L);
      boolean on = (window & 1L) == 0L;
      if (window != altWindow) {
         altWindow = window;
         Log.info("pipeline-alt: window " + (on ? "on" : "off") + " @" + now);
      }
      return on;
   }

   /**
    * Game thread, at the end of one bucket's collection: run the collected entities' update sequence on the
    * workers and drain the queue. Returns false when there was nothing to do. The synchronous shape —
    * dispatch, then join — used when the pipeline key is off and by the JVM tests.
    */
   public static boolean run(UpdateSchedulerSimulationLevel level) {
      boolean dispatched = dispatchAsync(level);
      joinPending();
      return dispatched;
   }

   /**
    * Game thread: dispatch the collected entities to the workers WITHOUT joining (entityUpdatePipeline). The
    * caller keeps the game thread busy with the next bucket's collection and calls {@link #joinPending}
    * before the next dispatch. Any batch still airborne is joined here first, so two can never overlap.
    */
   public static boolean dispatchAsync(UpdateSchedulerSimulationLevel level) {
      joinPending();
      int n = count;
      if (n == 0) {
         return false;
      }

      frames++;
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }

      // Freeze the queued entities' positions BEFORE the volatile write to inFlight below: that ordered pair is
      // the happens-before that lets workers read the arrays and stamps without a lock. INLINE_SLACK reserves
      // room for the next bucket's inline entities (players, vehicles, animals) to stamp their post-update
      // positions in while this batch flies — the arrays never grow mid-flight.
      snapshotFrame++;
      if (snapX.length < n + INLINE_SLACK) {
         int size = Math.max(n + INLINE_SLACK, snapX.length * 2);
         snapX = new float[size];
         snapY = new float[size];
         snapZ = new float[size];
      }
      float[] sx = snapX;
      float[] sy = snapY;
      float[] sz = snapZ;
      long frame = snapshotFrame;
      final IsoMovingObject[] q = queue; // the flight owns THIS array; the next collection gets the spare
      for (int i = 0; i < n; i++) {
         IsoMovingObject entity = q[i];
         sx[i] = entity.getX(); // still live here: inFlight is false until the write below
         sy[i] = entity.getY();
         sz[i] = entity.getZ();
         entity.pzoptSnapshotIndex = i;
         entity.pzoptSnapshotFrame = frame;
      }
      snapAppend = n;

      boolean luaReplay = Config.ENTITY_UPDATE_LUA_REPLAY;
      if (luaReplay && luaCaptures.length < n) {
         luaCaptures = java.util.Arrays.copyOf(luaCaptures, Math.max(n, luaCaptures.length * 2));
      }
      final boolean flightDefer = Config.EMITTER_DEFER;
      if (flightDefer && emitterCaptures.length < n) {
         emitterCaptures = java.util.Arrays.copyOf(emitterCaptures, Math.max(n, emitterCaptures.length * 2));
      }
      final boolean flightPhysics = Config.PHYSICS_DEFER;
      if (flightPhysics && physicsSlots.length < n) {
         physicsSlots = java.util.Arrays.copyOf(physicsSlots, Math.max(n, physicsSlots.length * 2));
      }

      // The bucket's frame mod, captured at dispatch: the game thread moves on to the next bucket — and
      // rewrites the global perObjectMultiplier — while these tasks still run, so every task reads THIS value
      // through pom() instead of the live field (the five consumer sites are the GameTime getters,
      // FrameDelay.update and IsoZombie.allowsInvisibleAnimationSkips; PerObjectMultiplierTest).
      final float pomAtDispatch = zombie.GameTime.getInstance().perObjectMultiplier;
      final boolean flightReplay = luaReplay;

      flightFailure = null;
      inFlight = true; // onWorkerNow() and frozen(): the workers are running this batch's entities from here to joinPending
      LightingDefer.prepare(n); // gt-offload: the tasks' lazy light-read side effects, applied at the join
      FrameBatch.runAsync(n, i -> {
         IsoMovingObject entity = q[i];
         LightingDefer.begin(i);
         CURRENT.set(entity); // frozen(): this task's entity reads itself live, everyone else frozen
         POM.get()[0] = pomAtDispatch; // pom(): this task reads the dispatch-time multiplier
         java.util.ArrayList<Object[]> capture = null;
         if (flightReplay) {
            capture = luaCaptures[i];
            if (capture == null) {
               capture = new java.util.ArrayList<>();
               luaCaptures[i] = capture; // published to the game thread by the join
            }
            LUA_CAPTURE.set(capture); // captureLuaEvent appends here for this task
         }
         java.util.ArrayList<zombie.characters.IsoGameCharacter> emitterSlot = null;
         if (flightDefer) {
            emitterSlot = emitterCaptures[i];
            if (emitterSlot == null) {
               emitterSlot = new java.util.ArrayList<>();
               emitterCaptures[i] = emitterSlot; // published to the game thread by the join
            }
            EMITTER_CAPTURE.set(emitterSlot); // deferEmitter queues here for this task
         }
         PhysicsSlot physicsSlot = null;
         if (flightPhysics) {
            physicsSlot = physicsSlots[i];
            if (physicsSlot == null) {
               physicsSlot = new PhysicsSlot();
               physicsSlots[i] = physicsSlot; // published to the game thread by the join
            }
            PHYSICS_CAPTURE.set(physicsSlot); // deferBallistics / deferRagdollVehicle queue here for this task
         }
         try {
            entity.setCurrentSimulationLevel(level);
            entity.preupdate();
            entity.frameStep();
            entity.update();
         } finally {
            LightingDefer.end();
            CURRENT.set(null); // a pooled worker must not carry the reference into the next task
            POM.get()[0] = Float.NaN; // pom() follows the live field again off-task
            if (capture != null) {
               LUA_CAPTURE.set(null);
            }
            if (emitterSlot != null) {
               EMITTER_CAPTURE.set(null);
            }
            if (physicsSlot != null) {
               PHYSICS_CAPTURE.set(null); // a pooled worker must not defer for the next batch's inline work
            }
         }
      }, fx -> flightFailure = fx);

      // Hand the owned array to the flight and swap the spare in for the next bucket's collection: the
      // workers iterate q while add() fills a different array, so the two never race.
      flightArr = q;
      flightN = n;
      flightPom = pomAtDispatch;
      flightLuaReplay = flightReplay;
      flightPending = true;
      queue = spareQueue != null ? spareQueue : new IsoMovingObject[q.length];
      spareQueue = null;
      count = 0;
      return true;
   }

   /**
    * Combined dispatch (spec 3.3): the whole frame's batchables in one workers-only flight. Each task runs
    * under ITS entity's simulation level and multiplier (queueLevel/queuePom, stamped at add) instead of one
    * level and one multiplier for the flight, because this batch spans every bucket of the frame. The snapshot
    * is sized n + inlineCount + 64 — the walk finished before this call, so the inline count is exact rather
    * than the pipeline's fixed INLINE_SLACK guess.
    */
   public static boolean dispatchCombined() {
      joinPending();
      int n = count;
      if (n == 0) {
         return false;
      }

      frames++;
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }

      snapshotFrame++;
      int need = n + inlineCount + 64;
      if (snapX.length < need) {
         int size = Math.max(need, snapX.length * 2);
         snapX = new float[size];
         snapY = new float[size];
         snapZ = new float[size];
      }
      float[] sx = snapX;
      float[] sy = snapY;
      float[] sz = snapZ;
      long frame = snapshotFrame;
      final IsoMovingObject[] q = queue; // the flight owns THESE three arrays; the next frame collects into the spares
      final float[] poms = queuePom;
      final int[] levels = queueLevel;
      for (int i = 0; i < n; i++) {
         IsoMovingObject entity = q[i];
         sx[i] = entity.getX(); // still live here: inFlight is false until the write below
         sy[i] = entity.getY();
         sz[i] = entity.getZ();
         entity.pzoptSnapshotIndex = i;
         entity.pzoptSnapshotFrame = frame;
      }
      snapAppend = n;

      boolean luaReplay = Config.ENTITY_UPDATE_LUA_REPLAY;
      if (luaReplay && luaCaptures.length < n) {
         luaCaptures = java.util.Arrays.copyOf(luaCaptures, Math.max(n, luaCaptures.length * 2));
      }
      final boolean flightDefer = Config.EMITTER_DEFER;
      if (flightDefer && emitterCaptures.length < n) {
         emitterCaptures = java.util.Arrays.copyOf(emitterCaptures, Math.max(n, emitterCaptures.length * 2));
      }
      final boolean flightPhysics = Config.PHYSICS_DEFER;
      if (flightPhysics && physicsSlots.length < n) {
         physicsSlots = java.util.Arrays.copyOf(physicsSlots, Math.max(n, physicsSlots.length * 2));
      }
      final boolean flightReplay = luaReplay;

      flightFailure = null;
      inFlight = true; // onWorkerNow() and frozen(): the workers are running this batch's entities from here to joinPending
      LightingDefer.prepare(n); // gt-offload: the tasks' lazy light-read side effects, applied at the join (merge fix: the combined flight needs it like dispatchAsync)
      FrameBatch.runAsync(n, i -> {
         IsoMovingObject entity = q[i];
         LightingDefer.begin(i);
         CURRENT.set(entity); // frozen(): this task's entity reads itself live, everyone else frozen
         POM.get()[0] = poms[i]; // spec 3.3: per-task, not per-flight — this flight spans every bucket of the frame
         java.util.ArrayList<Object[]> capture = null;
         if (flightReplay) {
            capture = luaCaptures[i];
            if (capture == null) {
               capture = new java.util.ArrayList<>();
               luaCaptures[i] = capture; // published to the game thread by the join
            }
            LUA_CAPTURE.set(capture); // captureLuaEvent appends here for this task
         }
         java.util.ArrayList<zombie.characters.IsoGameCharacter> emitterSlot = null;
         if (flightDefer) {
            emitterSlot = emitterCaptures[i];
            if (emitterSlot == null) {
               emitterSlot = new java.util.ArrayList<>();
               emitterCaptures[i] = emitterSlot; // published to the game thread by the join
            }
            EMITTER_CAPTURE.set(emitterSlot); // deferEmitter queues here for this task
         }
         PhysicsSlot physicsSlot = null;
         if (flightPhysics) {
            physicsSlot = physicsSlots[i];
            if (physicsSlot == null) {
               physicsSlot = new PhysicsSlot();
               physicsSlots[i] = physicsSlot; // published to the game thread by the join
            }
            PHYSICS_CAPTURE.set(physicsSlot); // deferBallistics / deferRagdollVehicle queue here for this task
         }
         try {
            entity.setCurrentSimulationLevel(LEVELS[levels[i]]);
            entity.preupdate();
            entity.frameStep();
            entity.update();
         } finally {
            LightingDefer.end();
            CURRENT.set(null); // a pooled worker must not carry the reference into the next task
            POM.get()[0] = Float.NaN; // pom() follows the live field again off-task
            if (capture != null) {
               LUA_CAPTURE.set(null);
            }
            if (emitterSlot != null) {
               EMITTER_CAPTURE.set(null);
            }
            if (physicsSlot != null) {
               PHYSICS_CAPTURE.set(null); // a pooled worker must not defer for the next batch's inline work
            }
         }
      }, fx -> flightFailure = fx);

      flightArr = q;
      flightN = n;
      flightPomArr = poms;
      flightLevelArr = levels;
      flightPom = Float.NaN; // scalar unused on the combined path: the drains read flightPomArr per task
      flightLuaReplay = flightReplay;
      flightPending = true;
      // Swap the spares in for the next frame's collection, keeping the invariant the stamped add() relies on:
      // queuePom / queueLevel are never shorter than queue (a sync-path dispatchAsync recycles the entity array
      // alone, so the three can arrive here with different lengths).
      queue = spareQueue != null ? spareQueue : new IsoMovingObject[q.length];
      queuePom = sparePom != null && sparePom.length >= queue.length ? sparePom : new float[queue.length];
      queueLevel = spareLevel != null && spareLevel.length >= queue.length ? spareLevel : new int[queue.length];
      spareQueue = null;
      sparePom = null;
      spareLevel = null;
      count = 0;
      return true;
   }

   /**
    * Game thread, after dispatchCombined (spec 3.4): the frame's inline entities under the flight. Per entry
    * the stock four calls under its bucket's multiplier (the live global — pom() on the game thread resolves
    * through it, the POM holder stays NaN here by construction), stampInline publishing its post-update
    * position into the airborne snapshot. Global back to 1.0 after, stock's guarantee.
    */
   public static void runInlinePhase() {
      zombie.GameTime gt = zombie.GameTime.getInstance();
      for (int i = 0; i < inlineCount; i++) {
         IsoMovingObject entity = inlineQueue[i];
         gt.perObjectMultiplier = inlinePom[i];
         entity.setCurrentSimulationLevel(LEVELS[inlineLevel[i]]);
         entity.preupdate();
         entity.frameStep();
         entity.update();
         stampInline(entity);
      }

      gt.perObjectMultiplier = 1.0F;
      java.util.Arrays.fill(inlineQueue, 0, inlineCount, null);
      inlineCount = 0;
   }

   /**
    * Game thread, after joinPending ({@code animalsAfterJoin}): the frame's animals, per entry the stock four calls under
    * its bucket's multiplier and simulation level (stamped at queueInline), in queue order; global back to 1.0 after,
    * stock's guarantee. No stampInline: nothing is airborne to publish into, which is the point: every zombie is settled,
    * frozen() is false, and an animal's reads of the horde are direct.
    *
    * <p>The scheduler calls this after the pipeline's try/finally, never from a finally, so it runs only when the buckets,
    * the inline phase and the join all returned normally and cannot replace an exception from any of them. On such a
    * frame every queued animal updates exactly once, before the scheduler's update returns (the postupdate loop and the
    * vocal walks read animals). An animal that throws ends the phase there, as it ends stock's bucket loop: the exception
    * propagates, the rest of this frame's herd is dropped, and the finally puts the multiplier back to 1.0 and nulls every
    * slot, so nothing is pinned. A frame with no animal queued (every non-combined frame) returns before touching anything.
    */
   public static void runAnimalPhase() {
      if (animalCount == 0) {
         return;
      }

      zombie.GameTime gt = zombie.GameTime.getInstance();
      try {
         // A no-op on the scheduler's path (it just landed the flight). Here so that no caller can update an animal
         // under an airborne flight: the invariant belongs to this method, not to its call site.
         joinPending();
         for (int i = 0; i < animalCount; i++) {
            IsoMovingObject entity = animalQueue[i];
            gt.perObjectMultiplier = animalPom[i];
            entity.setCurrentSimulationLevel(LEVELS[animalLevel[i]]);
            entity.preupdate();
            entity.frameStep();
            entity.update();
            animalsRun++;
         }
      } finally {
         gt.perObjectMultiplier = 1.0F; // cannot throw, so this finally never masks the exception it runs under
         dropAnimals();
      }
   }

   /**
    * Game thread: wait for the airborne batch, then land its window — deferred tile updates, the Lua replay
    * under ITS dispatch-time multiplier (the game thread's global may already be the next bucket's), the
    * failure latch. No-op without a pending batch.
    */
   public static void joinPending() {
      if (!flightPending) {
         return;
      }

      long j0 = System.nanoTime();
      FrameBatch.join(); // runs the completion above on this thread: flightFailure is set past here
      waitNanos += System.nanoTime() - j0; // the game thread's cost of this batch IS the join wait: with the
      // pipeline it did the next bucket's collection instead of task work, so work ms stays ~0 by design
      preClaimed += FrameBatch.lastPreClaimed(); // THIS flight's runway, taken at its own join — see the field
      inFlight = false;
      int n = flightN;

      // The window's latched setMovingSquare calls, applied in one place on the game thread — also after a
      // failed batch, so a partially updated frame still lands its tile updates instead of leaking them.
      applyDeferredSquares();
      LightingDefer.apply(n); // gt-offload: the lazy light reads' level invalidations, LightDirt, cutaway and room / meta hooks, in task order

      // The window's deferred emitter ticks, then its deferred Bullet calls, then its captured Lua dispatches,
      // all in queue order — stock's serial order — under the multiplier the batch dispatched with, so the
      // ticks, the physics and the handlers see the same time scale as stock's inline run. Also after a failed
      // batch: what deferred before the throw would have run in stock's semantics too.
      float[] h = POM.get();
      h[0] = flightPom; // NaN on the combined path: pom() falls through to the live global until a walker sets a task's own
      try {
         drainEmitters(n); // before the replay: a handler reading a zombie's sound state sees the ticked emitter
         drainRagdolls(n); // physicsDefer, in one zombie's own order: vehicleCollision sits above its super.update()
         drainBallistics(n); // ... which is what reaches updateBallisticsTarget; both before the replay, so no
         // handler of a captured event can observe a hitbox that is still where the zombie stood last frame
         if (flightLuaReplay) {
            replayLuaEvents(n);
         }
      } finally {
         h[0] = Float.NaN;
      }
      Throwable t = flightFailure;
      if (t != null) {
         if (!failed) {
            failed = true; // the bucket walks stock's loop from the next frame on
            // The full stack trace, once: the live frame-10 "Index -1 out of bounds for length 2" arrived with
            // no call site because only t.toString() was logged, and the next unknown race must not.
            Log.warn("entityUpdateParallel: an entity update failed on a worker, batching off: " + stackOf(t));
         } else {
            Log.warn("entityUpdateParallel: an entity update failed on a worker, batching off: " + t);
         }
      }

      java.util.Arrays.fill(flightArr, 0, n, null);
      spareQueue = flightArr; // the next dispatch collects into it
      flightArr = null;
      sparePom = flightPomArr; // symmetric with the entity array: the combined path recycles all three buffers
      spareLevel = flightLevelArr; // (both null after a sync-path flight, which never took them)
      flightPomArr = null;
      flightLevelArr = null;
      flightPending = false;
   }

   /** Room reserved past the queued block for inline entities stamped mid-flight (players, vehicles, animals). */
   private static final int INLINE_SLACK = 512;

   /**
    * Game thread, from the bucket's loop, right after an inline (non-batchable) entity finished its four
    * calls while a batch is airborne: stamp its post-update position into the in-flight snapshot so the
    * workers read a stable value — exactly what they saw when inline entities all ran before the dispatch.
    * Bounded by INLINE_SLACK; past it the entity just stays live (the pre-snapshot behaviour).
    */
   public static void stampInline(IsoMovingObject entity) {
      if (!flightPending || snapAppend >= snapX.length) {
         return;
      }
      int i = snapAppend++;
      snapX[i] = entity.getX();
      snapY[i] = entity.getY();
      snapZ[i] = entity.getZ();
      entity.pzoptSnapshotIndex = i;
      entity.pzoptSnapshotFrame = snapshotFrame; // published by the plain writes: a torn read just means one more live read
   }

   /** Game thread: drop whatever was collected (a bucket that never reached run()). */
   public static void clear() {
      java.util.Arrays.fill(queue, 0, count, null);
      count = 0;
   }

   /** One throwable, rendered with its full stack trace and causes, for the one-shot logs. */
   private static String stackOf(Throwable t) {
      java.io.StringWriter sw = new java.io.StringWriter();
      t.printStackTrace(new java.io.PrintWriter(sw));
      return sw.toString().trim();
   }

   private static final java.util.concurrent.atomic.AtomicLong luaSuppressed = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong pathfindRaceEscaped = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong surfacePropertyRaceSkipped = new java.util.concurrent.atomic.AtomicLong();

   /**
    * A worker mid-batch reached {@code LuaEventManager.triggerEvent} and the override's guard turned the dispatch
    * into a no-op (vanilla's main-thread path writes the shared static argument slots {@code a1..a8}/
    * {@code a1index..a8index}; its off-thread path takes the EventMap monitor and queues into a shared pool — a
    * worker must enter neither). Counted, and the first occurrence dumps the current stack so the log says WHICH
    * event from WHERE; the count is on {@link #describe}.
    */
   public static void onLuaSuppressed() {
      if (luaSuppressed.incrementAndGet() == 1L) {
         Log.warn("entityUpdateParallel: first Lua event suppressed on a worker (dispatch site follows): "
               + stackOf(new Throwable("suppressed Lua dispatch on " + Thread.currentThread().getName())));
      }
   }

   /** How many Lua event dispatches the worker guard has suppressed this session. */
   public static long getLuaSuppressedCount() {
      return luaSuppressed.get();
   }

   /**
    * A batch task's {@code PathFindBehavior2.update()} threw {@code IndexOutOfBounds} or
    * {@code IllegalStateException}. This used to be swallowed into {@code BehaviorResult.Working}, on the reading
    * that PZ's pathfinder writes the character's path list from its own thread — it does not: both pathfinders
    * fill the REQUEST's own Path and the only code that copies a result into a character is
    * {@code PathFindBehavior2.Succeeded}, called from {@code PolygonalMap2.updateMain} /
    * {@code PathfindNative.updateMain}, on the game thread, from {@code IngameState.UpdateStuff}, which runs after
    * {@code IsoWorld.update()} has returned and the flight has landed. The real race was the class's four static
    * scratch objects, now per-thread ({@link #pathPointScratch} and friends), which closes the silent
    * wrong-index/wrong-vector case as well as the throwing one.
    *
    * <p>So this is an assertion now, not a catch: it counts, the first one logs the whole stack, and the
    * exception is rethrown, which is vanilla's behaviour on and off a batch alike. A nonzero
    * {@code pathfindRaceEscaped} on {@link #describe} means that reading is wrong somewhere and the log says
    * where. Only a batch task counts — vanilla's own throw shape (an empty path with the finder reporting found)
    * is not a race.
    */
   public static void onPathfindRaceEscaped(Throwable e) {
      if (pathfindRaceEscaped.incrementAndGet() == 1L) {
         Log.warn("entityUpdateParallel: PathFindBehavior2.update threw on a batch task, which the per-thread"
               + " scratch was supposed to make impossible, so the path race is NOT closed; stack follows: "
               + stackOf(e));
      }
   }

   /** How many batch tasks threw out of PathFindBehavior2.update this session. Zero is the expected reading. */
   public static long getPathfindRaceEscapedCount() {
      return pathfindRaceEscaped.get();
   }

   /**
    * {@code PropertyContainer.initSurface}'s entry walk read a tile-property alias index outside the alias map's
    * range and skipped that entry rather than throwing. The walk reads the backing trove map's state byte, its key
    * and its value separately, so while another thread clears and refills the container — stock's
    * {@code IsoGridSquare.RecalcProperties}, which chunk streaming calls from its own threads — a state byte that
    * still says FULL can be paired with the map's no-entry key or value, both -1 for a property container, because
    * trove writes the no-entry key into the key array BEFORE the state byte stops saying FULL. Stock handed that
    * index straight to the alias list: the live Louisville route died on {@code Index -1 out of bounds for length
    * 235} three seconds in and the throw latched batching off for the whole session. The condition is transient —
    * the entry contributes nothing this time and the next call re-derives the value off an untorn view — so the
    * count is the only report; it is on {@link #describe}.
    */
   public static void onSurfacePropertyRaceSkipped() {
      surfacePropertyRaceSkipped.incrementAndGet();
   }

   /** How many torn surface-property entries the alias-range guard has skipped this session. */
   public static long getSurfacePropertyRaceSkippedCount() {
      return surfacePropertyRaceSkipped.get();
   }

   public static String describe() {
      return "update batch: frames=" + frames + " batched=" + batched + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " luaCaptured=" + luaCaptured + " luaReplayed=" + luaReplayed
            + " emitterDeferred=" + emitterDeferred + " emitterDrained=" + emitterDrained
            + " ballisticsDeferred=" + ballisticsDeferred + " ballisticsDrained=" + ballisticsDrained
            + " ragdollDeferred=" + ragdollDeferred + " ragdollDrained=" + ragdollDrained
            + " luaSuppressed=" + luaSuppressed.get() + " lightEffectsDeferred=" + LightingDefer.deferred + " pathfindRaceEscaped=" + pathfindRaceEscaped.get()
            + " surfacePropertyRaceSkipped=" + surfacePropertyRaceSkipped.get()
            + " movingSquareDeferred=" + movingSquareDeferred
            + " combinedFrames=" + combinedFrames + " inlineQueued=" + inlineQueued + " animalsAfterJoin=" + animalsRun
            + " nestedJoins=" + nestedJoins + " preClaimed=" + preClaimed
            + (failed ? " FAILED" : "");
   }
}
