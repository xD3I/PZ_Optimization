package pzopt;

/**
 * Within-run A/B of the game-thread offload keys (2026-09-27, {@code devGtAlternate}): from the first frame the keys listed
 * in {@code devGtAlternateKeys} switch off and on every {@code devGtAlternate} ms, so both halves share one run (the
 * scene, the clocks and other load drift alike) and {@code harness/gtab.py} splits the frames and the game-thread profile
 * by half. Keys not listed, or no alternation, read their Config value unchanged. The phase flips only at a frame start
 * ({@link #frame}, from {@code Pacing.stepStart}), so a frame never mixes the two paths.
 */
public final class GtAb {
   private GtAb() {
   }

   public static final int RENDER_PREP = 1; // renderPrepParallel
   public static final int PPL_PACK = 2; // pplPackParallel
   public static final int SCHED = 4; // schedulerClassifyParallel
   public static final int ANIMAL_LOS = 8; // animalLosFast
   public static final int WEATHER = 16; // weatherParticlesParallel
   public static final int ZOMBIE_UPDATE = 32; // entityUpdateParallel (PR #35 + the calm-state whitelist)
   public static final int BAKE_PREP = 64; // bakePrepParallel
   public static final int TORCH_NEAR = 128; // pplTorchNearChunk
   public static final int ZOMBIE_STATS = 256; // zombieStatsFold
   public static final int LOS_PREFETCH = 512; // losLightPrefetch
   public static final int VIS_POLY = 1024; // visPolyAsync
   public static final int AO_CONTEXT = 2048; // aoContextParallel
   public static final int TL_ORDER = 4096; // translucentOrderCache
   public static final int TORCH_SOURCE = 8192; // torchSource (an Enhancements key: the cost of the lens solve and its visuals)
   public static final int PROFILER_IDLE = 16384; // profilerIdleFast
   public static final int SIM_LOD = 32768; // zombieSimLodTiles (the extra distance steps)
   public static final int ANIMAL_SNAP = 65536; // animalLosSnapshot
   public static final int LOOT_DEFER = 131072; // lootDefer
   public static final int SPAWN_SPREAD = 262144; // zombieSpawnSpread
   public static final int SLACK_WORK = 524288; // slackWork
   public static final int MODEL_ADDS = 1048576; // zombieModelAddBudgetUs
   public static final int BAKE_GUARD = 2097152; // bakeTimeGuardPct
   public static final int POSTUPDATE = 4194304; // postupdateParallel (Louisville 120 plan B)
   public static final int TILE_RECORD = 8388608; // tileRecordParallel (Louisville 120 plan A)
   public static final int NEAR_VEHICLE = 16777216; // nearVehicleGrid (zombie pass 2026-10-13)
   public static final int HEAD_ON_WORKER = 33554432; // headOnWorker (zombie pass 2026-10-13: the A/B after the see_all flood was gone)
   public static final int PLAYER_FIRST = 67108864; // entityUpdatePlayerFirst (zombie pass 2026-10-13)
   public static final int MORE_STATES = 1073741824; // entityUpdateMoreStates (zombie pass 2026-10-13)
   public static final int NEAR_PLAYER = 536870912; // entityUpdateNearPlayer (zombie pass 2026-10-13)
   public static final int SEPARATE_MEMO = 268435456; // separateMemo (zombie pass 2026-10-13)
   public static final int FRAME_SPIN = 134217728; // frameSpin: the frame workers' spin before parking (frameSpinUs > 0), zombie pass 2026-10-13
   public static final long RAGDOLL_GATE = 1L << 31; // ragdollGate (isRagdoll pass 2026-10-13; bits above 30 need the long mask)
   public static final long BODY_MODEL_CACHE = 1L << 32; // bodyModelCache (isRagdoll pass 2026-10-13)
   public static final long BAKE_RECORD = 1L << 33; // bakeRecordParallel (render-phase pass f8-render1, 2026-10-13)
   public static final long TILE_MOVE_REF = 1L << 35; // tileRecordMoveRef (render-phase pass f8-render1, 2026-10-13)
   public static final long RENDER_LAST = 1L << 36; // renderLastFast (render-phase pass f8-render1, 2026-10-13)
   public static final long TILE_MODELS = 1L << 37; // tileRecordModels (render-phase pass f8-render1, 2026-10-13)
   public static final long TILE_WEATHER = 1L << 38; // tileRecordWeather (render-phase pass f8-render1, 2026-10-13)
   public static final long TILE_FLOORS = 1L << 34; // tileRecordFloors (render-phase pass f8-render1, 2026-10-13)
   public static final long ANIMAL_WALK = 1L << 56; // animalLosWalk (tail pass 2026-10-13; tail1 takes bits 56-63, named in parse())
   public static final long COMBINER = 1L << 57; // combinerPool (tail2): keep storage, reserve, budget retention
   public static final long HANDOFF_FAST = 1L << 58; // handoffRecalcFast (hand-off pass f8-handoff 2026-10-14; bits 58-61, named in parse())
   public static final long HANDOFF_PARALLEL = 1L << 59; // handoffRecalcParallel (f8-handoff)
   public static final long HANDOFF_MEMO = 1L << 60; // handoffRecalcMemo (f8-handoff)
   public static final long HANDOFF_SCHED = 1L << 61; // chunkHandoffSched: chunkHandoffCost=chunk + chunkHandoffSlackMaxWait + chunkHandoffForceAnyFrame together (f8-handoff)
   public static final long BAKE_SLACK = 1L << 62; // bakeSlackCap (tail6 2026-10-14; bit 58 in its worktree, moved at the round-6 merge (58-61 are f8-handoff's); named in parse())
   public static final long GC_FIT = 1L << 63; // gcSlackFit (tail6 2026-10-14; bit 59 in its worktree; named in parse())
   public static final long PALETTE_MISMATCH = 1L << 49; // hordePaletteMismatch (tail6 2026-10-14; bit 60 in its worktree; named in parse())
   public static final long CHUNK_PACE = 1L << 51; // chunkHandoffPaceMs (chunk pass f8-chunk 2026-10-13)
   public static final long FRAME_QUEUE = 1L << 48; // frameQueue (f8-twofix 2026-10-14: its cost uncapped and at the cap, within one run; named in parse())
   public static final long FQ_SPIN = 1L << 39; // frameQueueSpinUs (f9-present 2026-10-15; off phase = the 1 ms spin; named in parse())
   public static final long RT_HOLD = 1L << 55; // frameQueueRtHold (f9-present 2026-10-15; named in parse())
   public static final long BAKE_GPU = 1L << 54; // bakeBudgetGpu (f8-resid 2026-10-15; named in parse())
   public static final long BURST_GOVERNOR = 1L << 53; // burstGovernor (f8-resid 2026-10-15; named in parse())
   public static final long CORE_ISOLATE = 1L << 52; // coreIsolate (f8-resid 2026-10-15: the reserved cores against none, CorePlacement polls the phase; named in parse())
   public static final long VISION_BATCH = 1L << 50; // lightingVisionParallel (chunk pass f8-chunk 2026-10-13; bits 50-55, named in parse())

   private static final String[] NAMES = {"renderPrepParallel", "pplPackParallel", "schedulerClassifyParallel", "animalLosFast",
      "weatherParticlesParallel", "entityUpdateParallel", "bakePrepParallel", "pplTorchNearChunk", "zombieStatsFold", "losLightPrefetch", "visPolyAsync", "aoContextParallel", "translucentOrderCache", "torchSource", "profilerIdleFast", "zombieSimLod", "animalLosSnapshot", "lootDefer", "zombieSpawnSpread", "slackWork", "zombieModelAddBudget", "bakeTimeGuard", "postupdateParallel", "tileRecordParallel", "nearVehicleGrid", "headOnWorker", "entityUpdatePlayerFirst", "frameSpin", "separateMemo", "entityUpdateNearPlayer", "entityUpdateMoreStates", "ragdollGate", "bodyModelCache", "bakeRecordParallel", "tileRecordFloors", "tileRecordMoveRef", "renderLastFast", "tileRecordModels", "tileRecordWeather"};

   private static final long MASK = parse(Config.DEV_GT_ALTERNATE_KEYS);
   // a second, independent alternation group (2026-10-13): its own keys and period, so one run measures two keys
   // (a 2x2 within-run design; the row's phase column is on1 + 2 * on2)
   private static final long MASK2 = Config.DEV_GT_ALTERNATE2 > 0 ? parse(Config.DEV_GT_ALTERNATE_KEYS2) : 0L;
   private static volatile boolean off2;
   private static boolean lastOff2;
   private static long t0;
   private static volatile boolean off; // this frame runs the listed keys' old path

   private static long parse(String keys) {
      long m = 0;
      if (keys == null || keys.isEmpty()) {
         return 0;
      }
      for (String k : keys.split(",")) {
         k = k.trim();
         if (k.equals("all")) {
            return -1L;
         }
         for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(k)) {
               m |= 1L << i;
            }
         }
         m |= ZombAb.bit(k); // zombie redesign (f8-zomb2): its keys on bits 40-47
         if (k.equals("animalLosWalk")) { // tail pass: bits 56-63 by name
            m |= ANIMAL_WALK;
         }
         if (k.equals("combinerPool")) { // tail2
            m |= COMBINER;
         }
         if (k.equals("lightingVisionParallel")) { // chunk pass (f8-chunk): bits 50-55 by name
            m |= VISION_BATCH;
         }
         if (k.equals("chunkHandoffPace")) { // chunk pass (f8-chunk)
            m |= CHUNK_PACE;
         }
         if (k.equals("frameQueue")) { // f8-twofix
            m |= FRAME_QUEUE;
         }
         if (k.equals("handoffRecalcFast")) { // hand-off pass (f8-handoff)
            m |= HANDOFF_FAST;
         }
         if (k.equals("handoffRecalcParallel")) { // hand-off pass (f8-handoff)
            m |= HANDOFF_PARALLEL;
         }
         if (k.equals("handoffRecalcMemo")) { // hand-off pass (f8-handoff)
            m |= HANDOFF_MEMO;
         }
         if (k.equals("chunkHandoffSched")) { // hand-off pass (f8-handoff): the slack scheduler's three keys
            m |= HANDOFF_SCHED;
         }
         if (k.equals("bakeSlackCap")) { // tail6
            m |= BAKE_SLACK;
         }
         if (k.equals("gcSlackFit")) { // tail6
            m |= GC_FIT;
         }
         if (k.equals("hordePaletteMismatch")) { // tail6
            m |= PALETTE_MISMATCH;
         }
         if (k.equals("coreIsolate")) { // f8-resid
            m |= CORE_ISOLATE;
         }
         if (k.equals("burstGovernor")) { // f8-resid
            m |= BURST_GOVERNOR;
         }
         if (k.equals("frameQueueSpin")) { // f9-present
            m |= FQ_SPIN;
         }
         if (k.equals("frameQueueRtHold")) { // f9-present
            m |= RT_HOLD;
         }
         if (k.equals("bakeBudgetGpu")) { // f8-resid
            m |= BAKE_GPU;
         }
      }
      return m;
   }

   private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
   private static final java.lang.management.OperatingSystemMXBean OS = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
   private static long lastCpu, lastProc, lastWall;
   private static boolean lastOff;
   private static final StringBuilder LOG = new StringBuilder(1 << 16);
   private static java.io.Writer out;
   private static int rows;
   public static int zombieUpdates; // IsoZombie.update calls since the last frame start (game thread)

   /**
    * Game thread, every frame start: the phase of this frame, and one row of pzopt-gtab.out for the frame that just ended
    * (its phase, the game thread's CPU time, the process's CPU time and the wall time since the last frame start): what
    * a key moves off the game thread shows as game-thread CPU even when the frame time is bound elsewhere (the GPU, the
    * render thread), and the process column shows where the work went.
    */
   public static void frame() {
      if (!TIMING) { // tail pass: devFrameSections writes the rows without an alternation
         return;
      }
      long now = System.currentTimeMillis();
      if (t0 == 0L) {
         if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
            return; // from the first world frame
         }
         t0 = now;
         if (Config.DEV_GT_PHASE_RANDOM && Config.DEV_GT_ALTERNATE > 0) {
            // devGtPhaseRandom (f8-resid): the cycle starts at a random point, so a route event at a fixed time after the
            // world start (Louisville's horde first sight ~22 s in) lands in either phase across runs (an A/A placebo with
            // 370 / 430 ms periods read 5.0 / 2.6 % "on" vs 1.6 / 1.2 % "off": the first sight fell into "on" in both)
            t0 -= (long)(Math.random() * 4.0 * Config.DEV_GT_ALTERNATE);
         }
         Log.info("gt ab: alternating every " + Config.DEV_GT_ALTERNATE + " ms from epoch_ms " + t0 + " (on first), keys " + Config.DEV_GT_ALTERNATE_KEYS);
      }
      if (Config.DEV_FRAME_SECTIONS) { // tail pass: the OS columns of the frame that just ended (deltas since its start)
         osSample();
      }
      long cpu = THREADS.getCurrentThreadCpuTime();
      long proc = OS instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : 0L;
      long wall = System.nanoTime();
      if (lastWall != 0L) {
         LOG.append(now).append(' ').append((lastOff ? 0 : 1) + (lastOff2 ? 0 : 2)).append(' ').append(cpu - lastCpu).append(' ').append(proc - lastProc).append(' ').append(wall - lastWall).append(' ').append(zombieUpdates);
         for (int i = 0; i < SECTIONS; i++) {
            LOG.append(' ').append(SECTION_NS[i]);
         }
         ZombTiming.append(LOG); // zombie redesign (f8-zomb2): its own columns after the sections
         LOG.append('\n');
         if (++rows % 250 == 0) {
            flush();
         }
      }
      zombieUpdates = 0;
      java.util.Arrays.fill(SECTION_NS, 0L);
      lastCpu = cpu;
      lastProc = proc;
      lastWall = wall;
      if (Config.DEV_GT_ALTERNATE <= 0 || MASK == 0) { // tail pass: devFrameSections alone, every frame "on"
         return;
      }
      long period = (now - t0) / Config.DEV_GT_ALTERNATE;
      // ABBA (devGtAbba, default on): on, off, off, on, on, off, ... so a scene that grows heavier along the route weighs
      // both halves alike; plain alternation put every off half after its on half (an A/A placebo read 0.6 ms / 7 points
      // of misses "better" on, 2026-10-05)
      off = Config.DEV_GT_ABBA ? ((period + 1L) >> 1 & 1L) == 1L : (period & 1L) == 1L;
      lastOff = off;
      if (MASK2 != 0) {
         long period2 = (now - t0) / Config.DEV_GT_ALTERNATE2;
         off2 = Config.DEV_GT_ABBA ? ((period2 + 1L) >> 1 & 1L) == 1L : (period2 & 1L) == 1L;
         lastOff2 = off2;
      }
   }

   // tail pass (devFrameSections): /proc readers, game thread only
   private static java.io.RandomAccessFile schedFile, statFile;
   private static final byte[] PROC_BUF = new byte[1024];
   private static long lastRunq = -1, lastMin = -1, lastMaj = -1;
   private static boolean osFailed;

   private static void osSample() {
      if (osFailed) {
         return;
      }
      try {
         if (schedFile == null) {
            schedFile = new java.io.RandomAccessFile("/proc/thread-self/schedstat", "r");
            statFile = new java.io.RandomAccessFile("/proc/self/stat", "r");
         }
         schedFile.seek(0L);
         int n = schedFile.read(PROC_BUF);
         long runq = field(PROC_BUF, n, 0, 1); // "run_ns wait_ns slices": the second number
         statFile.seek(0L);
         n = statFile.read(PROC_BUF);
         int close = 0;
         for (int i = n - 1; i > 0; i--) {
            if (PROC_BUF[i] == ')') {
               close = i + 2;
               break;
            }
         }
         long min = field(PROC_BUF, n, close, 7); // after "pid (comm) ": state ppid pgrp session tty tpgid flags minflt cminflt majflt
         long maj = field(PROC_BUF, n, close, 9);
         SECTION_NS[S_HANDOFF_WAIT] = zombie.core.SpriteRenderer.getWaitTime();
         long bw = FrameBatch.waitNanos + FrameBatch.asyncJoinNanos;
         long gt = GlNames.genTexNanos;
         if (lastBatchWait >= 0) {
            SECTION_NS[S_BATCH_WAIT] = bw - lastBatchWait;
            SECTION_NS[S_RT_GENTEX] = gt - lastGenTex;
         }
         long nt = zombie.core.textures.Texture.totalTextureID;
         if (lastNewTex >= 0) {
            SECTION_NS[C_NEW_TEX] = nt - lastNewTex;
         }
         lastNewTex = nt;
         lastBatchWait = bw;
         lastGenTex = gt;
         if (lastRunq >= 0) {
            SECTION_NS[C_RUNQ] = runq - lastRunq;
            SECTION_NS[C_MINFLT] = min - lastMin;
            SECTION_NS[C_MAJFLT] = maj - lastMaj;
         }
         lastRunq = runq;
         lastMin = min;
         lastMaj = maj;
      } catch (Throwable t) {
         osFailed = true;
         Log.warn("gt ab: no /proc frame counters: " + t);
      }
   }

   /** The index-th space-separated decimal number of buf[from, n). */
   private static long field(byte[] buf, int n, int from, int index) {
      int i = from;
      for (int k = 0; k < index; k++) {
         while (i < n && buf[i] != ' ') {
            i++;
         }
         i++;
      }
      long v = 0;
      while (i < n && buf[i] >= '0' && buf[i] <= '9') {
         v = v * 10 + (buf[i++] - '0');
      }
      return v;
   }

   private static void flush() {
      try {
         if (out == null) {
            java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-gtab.out");
            out = new java.io.BufferedWriter(new java.io.FileWriter(f));
            out.write("# epoch_ms on(1)/off(0) game_thread_cpu_ns process_cpu_ns wall_ns zombie_updates, then ns per section: startFrame schedUpdate animalLos playerLos pplBeforeComposite renderMovingObjects performRenderTiles postupdate visPolyRenderMain aoFlush chunkMapUpdate popmanUpdate lightingUpdate logic finishAnimation renderInternal sceneCull atlases cellRender pu_loop pu_move pu_flush pu_zombies pu_moved pu_collided vzmUpdate chunkPos nativeUnload bake_t0 bake_t1 bake_t2 bake_t3 bake_offered tl_pass tl_record tl_splice rt_handoff rt_prepop rt_tilesInternal rt_renderlast rt_weather tl_floor runq_ns minflt majflt handoff batchWait rtGenTex newTex" + ZombTiming.HEADER + "  (per frame, devGtAlternate " + Config.DEV_GT_ALTERNATE + " ms, keys "
               + Config.DEV_GT_ALTERNATE_KEYS + ")\n");
         }
         out.write(LOG.toString());
         out.flush();
      } catch (java.io.IOException e) {
         Log.warn("gt ab: " + e);
      }
      LOG.setLength(0);
   }

   // ---- per-frame section timers (dev, only while an alternation runs): the ns each section took this frame, one column each ----
   public static final int S_START_FRAME = 0, S_SCHED_UPDATE = 1, S_ANIMAL_LOS = 2, S_PLAYER_LOS = 3, S_PPL = 4, S_MOVING = 5, S_TILES = 6, S_POSTUPDATE = 7;
   public static final int S_VISPOLY = 8, S_AO_FLUSH = 9, S_CHUNKMAP = 10, S_POPMAN = 11, S_LIGHTING = 12, S_LOGIC = 13, S_FINISH_ANIM = 14, S_RENDER = 15, S_CULL = 16, S_ATLAS = 17, S_CELL = 18;
   // Louisville 120 plan B: inside postupdate, the bucket loop, the zombies' IsoMovingObject.postupdate (timed on one zombie in
   // PU_SAMPLE, scaled), the ActionEval / AnimBatch flush; then counts, not ns: zombies through the movement, zombies whose
   // square changed, zombies that collided
   public static final int S_PU_LOOP = 19, S_PU_MOVE = 20, S_PU_FLUSH = 21, C_PU_ZOMBIES = 22, C_PU_MOVED = 23, C_PU_COLLIDED = 24;
   public static final int PU_SAMPLE = 8;
   // Louisville 120 item 3: VirtualZombieManager.update (zombie removal / reuse), IsoChunkMap.ProcessChunkPos (the grid shift and chunk unloads)
   public static final int S_VZM = 25, S_CHUNK_POS = 26;
   // Louisville 120 item 3: the popman native's chunk unload call; bakes granted this frame by tier (counts: must / level-change
   // burst, arrival quota, overdue, normal) and offered
   public static final int S_NATIVE_UNLOAD = 27, C_BAKE_T0 = 28, C_BAKE_T1 = 29, C_BAKE_T2 = 30, C_BAKE_T3 = 31, C_BAKE_OFFERED = 32;
   public static final int S_TL_PASS = 33, S_TL_RECORD = 34, S_TL_SPLICE = 35; // tileRecordParallel: the translucent passes (either path), recording, splicing
   // render-phase pass f8-render1 (2026-10-13): the frame hand-off wait (SpriteRenderer.pushFrameDown), the populating state's
   // clear (prePopulating: the last use's postRender), renderTilesInternal, the renderlast loops, the weather fx, the
   // translucent floor passes
   public static final int S_HANDOFF = 36, S_PREPOP = 37, S_TILES_INTERNAL = 38, S_RENDERLAST = 39, S_WEATHER = 40, S_TL_FLOOR = 41;
   // tail pass 2026-10-13 (devFrameSections): per frame, the game thread's run-queue wait (ns runnable but not on a CPU,
   // /proc/thread-self/schedstat) and the process's minor / major page faults (/proc/self/stat), counts not ns
   public static final int C_RUNQ = 42, C_MINFLT = 43, C_MAJFLT = 44;
   // tail pass: the frame's hand-off wait (SpriteRenderer.pushFrameDown), the game thread's frame-batch join waits
   // (FrameBatch.waitNanos + asyncJoinNanos), the render thread's glGenTextures round trips since the last frame
   public static final int S_HANDOFF_WAIT = 45, S_BATCH_WAIT = 46, S_RT_GENTEX = 47; // integ-r2: render1 took 36-41 (its S_HANDOFF is the timed pushFrameDown, column rt_handoff)
   public static final int C_NEW_TEX = 48; // tail pass: textures created since the last frame (Texture.totalTextureID, render thread)
   private static final int SECTIONS = 49;
   private static long lastBatchWait = -1, lastGenTex = -1, lastNewTex = -1;
   private static final long[] SECTION_NS = new long[SECTIONS];
   public static final boolean TIMING = Config.DEV_GT_ALTERNATE > 0 && MASK != 0 || Config.DEV_FRAME_SECTIONS; // tail pass: or devFrameSections

   /** Game thread: a section starts (0 when no alternation runs). */
   public static long begin() {
      return TIMING ? System.nanoTime() : 0L;
   }

   /** Game thread: the section that began at {@code t} ends. */
   public static void end(int section, long t) {
      if (t != 0L) {
         SECTION_NS[section] += System.nanoTime() - t;
      }
   }

   /** Game thread: add a count (or scaled ns) to a column of this frame. */
   public static void add(int section, long v) {
      SECTION_NS[section] += v;
   }

   /** pzopt.ZombAb (zombie redesign, f8-zomb2): the phase of each alternation group, for its own key mask. */
   static boolean offPhase1() {
      return off;
   }

   static boolean offPhase2() {
      return off2;
   }

   /** f8-resid: the key's bit is in an alternation group (only then does its off phase ever come). */
   public static boolean listed(long bit) {
      return Config.DEV_GT_ALTERNATE > 0 && (MASK & bit) != 0 || (MASK2 & bit) != 0;
   }

   /** False in the off half of an alternation for a listed key: the caller takes its old path this frame. */
   public static boolean on(long bit) {
      return (!off || (MASK & bit) == 0) && (!off2 || (MASK2 & bit) == 0);
   }
}
