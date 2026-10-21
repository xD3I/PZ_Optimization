package pzopt;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.PlayerCamera;

/**
 * Shadows on the entities (Config {@code entityShadows}, with {@code sunShadows}): the player, zombies, animals and cars
 * drawn as models take the static world's sun shadow per pixel, not one value for the whole body. A zombie half behind a
 * wall has its legs in the shade and its head in the sun; a car under a porch roof is dark under the roof only.
 *
 * <p>The models' own shaders (basicEffect, animalEffect, vehicle*, vehiclewheel*) are patched at launch: the vertex shader
 * carries each vertex's world position (the clip position mapped back through the iso camera of Core.DoPushIsoStuff, one
 * matrix a frame for every model, so every part, held item and car door is placed exactly as drawn), the fragment shader
 * multiplies the ambient (the game's light, the sun's share of which a shadow takes away) by {@code 1 - s (1 - V)}: s the
 * sun shadows' strength now, V the key light's visibility from that point (method {@code entityShadowMethod}).
 *
 * <p>V comes from the god rays' occupancy grid (GodRays: one R32UI texel per square and level round the camera: floors,
 * the N / W edges as wall / window / doorway, roofs, crown density), kept up to date whether the god rays are on or not,
 * and walked towards the light by the same two-level DDA the god rays use (a chunk the ray is above is skipped whole).
 */
public final class EntityShadow {
   private EntityShadow() {
   }

   static final int OCC_UNIT = 40, TOP_UNIT = 41, VOL_UNIT = 42, DYN_UNIT = 43, STATS_BINDING = 4;
   static final int MAX_DRAW_CASTERS = 8, D_VEC4 = 7 + 2 * MAX_DRAW_CASTERS; // a draw's uniform array: A, the brick, K (casters, penumbra), the capsules
   static final int STAT_DYN = (Probes.AX / Probes.N) * (Probes.AY / Probes.N); // the caster atlas's block stats follow the static ones
   private static volatile boolean failed;
   /** Tests: patch without the GL compile check (tests/pzopt/EntityShadowShaderTest links with the driver headless). */
   static boolean noCompileCheck;
   /** Tests: patch the bindless variant. */
   static boolean testBindless, testGl43;
   private static volatile boolean patched; // a model program carries the patch (set when the first one is patched)

   /** Whether a model program carries the entity-shadow patch (pzopt.HordeBatch keeps the model draws on the stock path then). */
   public static boolean modelShaderPatched() {
      return patched;
   }
   private static final java.util.concurrent.atomic.AtomicBoolean STOCK_LOGGED = new java.util.concurrent.atomic.AtomicBoolean(); // the "stay stock" line once
   private static long binds, perPixelBinds, constBinds, probeBinds, uniformBinds, dedupSkips, memoHits;
   private static int checks, devGets;
   private static boolean dumped;
   private static final java.util.Set<Object> CHECKED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

   /** Should the god rays' occupancy grid be kept for us (game thread)? */
   public static boolean wantsOccupancy() {
      return Config.ENTITY_SHADOWS && Config.SUN_SHADOWS && patched && !failed && Overrides.enabled() && !"cpu".equals(Config.ENTITY_SHADOW_METHOD);
   }

   /** Game thread: do model draws take their shade per pixel this frame (and so not the CPU factor in their ambient)? */
   public static boolean perPixel() {
      String v = variant();
      return Config.ENTITY_SHADOWS && Config.SUN_SHADOWS && patched && !failed && Overrides.enabled() && !"cpu".equals(v) && !"off".equals(v);
   }

   static String stats() {
      String gt = String.format(java.util.Locale.ROOT, "game thread %.1f us/frame, join wait %.1f (gather %.1f: entities %.1f, casters %.1f) | ", gameNs / 1e3 / Math.max(1L, gameFrames),
         joinNs / 1e3 / Math.max(1L, gameFrames), (Probes.loopNs + Probes.castNs) / 1e3 / Math.max(1L, gameFrames), Probes.loopNs / 1e3 / Math.max(1L, gameFrames), Probes.castNs / 1e3 / Math.max(1L, gameFrames));
      joinNs = 0L;
      Probes.loopNs = 0L;
      Probes.castNs = 0L;
      gameNs = 0L;
      gameFrames = 0L;
      return gt + String.format(java.util.Locale.ROOT, "entity shadows: binds %d (per pixel %d, probe %d of them uniform %d, constant %d; uploads skipped %d, memo hits %d)%s | probes: gathered %d, cache hits %d, jobs %d queued / %d run in %d dispatches; casters %d, dynamic jobs %d / %d in %d dispatches | occupancy: occ-only frames %d, chunk uploads %d, builds %d",
         binds, perPixelBinds, probeBinds, uniformBinds, constBinds, dedupSkips, memoHits, failed ? " FAILED" : "", Probes.gathered, Probes.cacheHits, Probes.jobsQueued, Probes.jobsRun, Probes.dispatches,
         Probes.castersGathered, Probes.dynJobsQueued, Probes.dynJobsRun, Probes.dynDispatches,
         GodRays.Gl.occOnlyFrames, GodRays.Gl.occUploads, GodRays.chunkBuilds) + Torches.stats() + Ao.stats();
   }

   // ------------------------------------------------------------------------------------------------ dev alternation

   private static final String[] CYCLE = Config.DEV_ENTITY_SHADOW_CYCLE.isEmpty() ? null : Config.DEV_ENTITY_SHADOW_CYCLE.split(",");
   private static long alternateT0;

   /** Game thread: this frame's variant (devEntityShadowCycle with devEntityShadowAlternate), else the configured method. */
   static String variant() {
      if (Config.DEV_ENTITY_SHADOW_ALTERNATE <= 0 || CYCLE == null) {
         return Config.ENTITY_SHADOW_METHOD;
      }
      long now = System.currentTimeMillis();
      if (alternateT0 == 0L) {
         alternateT0 = now;
         Log.info("entity shadows: cycling " + Config.DEV_ENTITY_SHADOW_CYCLE + " every " + Config.DEV_ENTITY_SHADOW_ALTERNATE + " ms from epoch_ms " + now);
      }
      return CYCLE[(int)((now - alternateT0) / Config.DEV_ENTITY_SHADOW_ALTERNATE % CYCLE.length)];
   }

   /** Game thread: a GPU section's name tagged with the variant while cycling. */
   public static String section(String name) {
      return Config.DEV_ENTITY_SHADOW_ALTERNATE > 0 && CYCLE != null && Config.ENTITY_SHADOWS && Config.SUN_SHADOWS ? name + ".es" + variant() : name;
   }

   // ------------------------------------------------------------------------------------------------ dev: pipeline statistics

   private static final class Stat extends TextureDraw.GenericDrawer {
      final boolean begin;

      Stat(boolean begin) {
         this.begin = begin;
      }

      @Override
      public void render() {
         if (!org.lwjgl.opengl.GL.getCapabilities().GL_ARB_pipeline_statistics_query) {
            return;
         }
         if (statQ[0] == 0) {
            statQ[0] = org.lwjgl.opengl.GL15.glGenQueries();
            statQ[1] = org.lwjgl.opengl.GL15.glGenQueries();
         }
         if (this.begin) {
            org.lwjgl.opengl.GL15.glBeginQuery(org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_VERTEX_SHADER_INVOCATIONS_ARB, statQ[0]);
            org.lwjgl.opengl.GL15.glBeginQuery(org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_FRAGMENT_SHADER_INVOCATIONS_ARB, statQ[1]);
         } else {
            org.lwjgl.opengl.GL15.glEndQuery(org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_VERTEX_SHADER_INVOCATIONS_ARB);
            org.lwjgl.opengl.GL15.glEndQuery(org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_FRAGMENT_SHADER_INVOCATIONS_ARB);
            if (++statFrame % 300 == 0) { // (dev: a synchronous read every 300 frames)
               long v = org.lwjgl.opengl.GL33.glGetQueryObjecti64(statQ[0], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
               long fr = org.lwjgl.opengl.GL33.glGetQueryObjecti64(statQ[1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
               Log.info("entity shadows: moving objects' pass: " + v + " vertex shader and " + fr + " fragment shader invocations (one frame)");
            }
         }
      }
   }

   private static final int[] statQ = new int[2];
   private static long statFrame;
   private static final Stat STAT_BEGIN = new Stat(true), STAT_END = new Stat(false);

   /** Game thread, around the moving objects (dev, devEntityShadowStats): their vertex and fragment shader invocations. */
   public static void devStats(boolean begin) {
      if (Config.DEV_ENTITY_SHADOW_STATS) {
         SpriteRenderer.instance.drawGeneric(begin ? STAT_BEGIN : STAT_END);
      }
   }

   // ------------------------------------------------------------------------------------------------ the frame's state

   /** Queued before the moving objects: the frame's key light and method for the render thread's binds. */
   static final class FrameState extends TextureDraw.GenericDrawer {
      boolean on;
      int method; // 0 constant (the CPU factor), 1 per pixel, 2 probe volume
      float s; // shadow strength
      float dx, dy, slope, overhead;
      float lx, ly, lz, form; // the direction to the light (world, metric: x east, y south, z up) and the form contrast
      int dev;
      // probe volume: the bricks to compute this frame (8 floats a job: origin xyz, spacing xyz, atlas cell x, y) and the
      // brick of every entity drawn (object -> origin xyz, spacing xyz, atlas cell x, y, cells n)
      float[] jobs = new float[0];
      int jobCount;
      // dynamic casters: capsules (8 floats: a xyz r, b xyz 0; world squares, z metric) and the bricks they reach (16 floats
      // a job: origin xyz + atlas x, spacing xyz + atlas y, 8 caster indices, -1 = none)
      float[] casters = new float[0];
      int casterCount;
      float[] dynJobs = new float[0];
      int dynJobCount;
      final java.util.IdentityHashMap<Object, float[]> bricks = new java.util.IdentityHashMap<>();
      float occMaxTop;
      boolean castersOn;
      boolean skip; // dev variant pskip
      boolean perVertex; // probev
      boolean vsOnly; // dev variant pvs
      boolean noUniform; // dev variant nouni: probe without the uniform mode
      boolean perPixelAll; // dev variant ppix: every probe draw per pixel (no hybrid)
      boolean selfOn; // entityShadowSelf (and not the dev variant noself)
      boolean dynCompute; // variant dyncs: the moving casters by the compute pass into their own atlas (else in the model shaders)
      float capK; // the capsules' 1 / tan of the penumbra angle
      float penTan; // the tan of the penumbra angle
      final float[] cloudMap = new float[12]; // CloudShadow.entityMapping
      boolean cloudOn;
      boolean noDedupe, noReset, noMemo; // dev variants nodedupe / noreset (the reset after a draw left out: other paths' draws of these programs take the last shade)
      boolean forceUniform; // dev variant uni: every brick drawn in the uniform mode (its cost: the facing alone)
      int rows; // the frame's entity bricks (a brick's index)
      long frame; // the gather's frame number (bind stamps the objects it draws as models with it)
      boolean wantDrawn; // a gather runs this frame: bind queues the objects it draws as models (else nothing drains the queue)
      final float[] torches = new float[Torches.MAX * Torches.STRIDE]; // the torches and headlights (Torches.snapshot)
      int torchCount;
      float torchStrength; // the darkness x sunShadowTorchPct (CapsuleShadow's light strength)
      boolean aoOn; // entityShadowAoPct: the characters' ambient occluded by the bodies and cars next to them
      float aoStrength;
      final java.util.IdentityHashMap<Object, float[]> aoRows = new java.util.IdentityHashMap<>(); // receiver -> the capsules next to it
      final java.util.IdentityHashMap<Object, float[]> torchRows = new java.util.IdentityHashMap<>(); // receiver -> its torch and the casters towards it

      @Override
      public void render() {
         rt = this;
         if (this.on && this.method == 2 && Config.ENTITY_SHADOW_COMPUTE_EARLY) {
            Probes.compute(this);
            Probes.computeDynamic(this);
            Probes.readBack(this);
         }
      }
   }

   /**
    * The probe compute of a frame, queued right before its moving objects draw (beforeMoving). At the top of the cell
    * render (entityShadowComputeEarly) it ran just before IsoCell.drawStencilMask, and on the frames it dispatched the
    * cutaway mask was lost: a see-through tree over the player was drawn whole for that frame (2026-10-08, the
    * maintainer's flip save; runs xxl-bis-*). Here it still runs before every model draw that reads its atlas.
    */
   static final class ComputeDrawer extends TextureDraw.GenericDrawer {
      FrameState f;

      @Override
      public void render() {
         FrameState s = this.f;
         if (s != null && s.on && s.method == 2) {
            Probes.compute(s);
            Probes.computeDynamic(s);
            Probes.readBack(s);
         }
      }
   }

   private static final ComputeDrawer[] COMPUTES = {new ComputeDrawer(), new ComputeDrawer(), new ComputeDrawer(), new ComputeDrawer()};
   private static FrameState queued; // game thread: the frame state frameStart queued last (its compute goes out at beforeMoving)

   private static final FrameState[] FRAMES = {new FrameState(), new FrameState(), new FrameState(), new FrameState()};
   private static int frameIndex;
   private static long viewT0, statFrames;
   static volatile FrameState rt = new FrameState();

   /**
    * Game thread, at the start of the world render (FBORenderCell.renderInternal): this frame's light for the model draws
    * and its probe bricks, the compute queued first in the frame (right before the moving objects it waited for the
    * graphics before it to drain: ~21 us a dispatch whatever its work).
    */
   public static void frameStart(int playerIndex) {
      long t0 = System.nanoTime();
      try {
         frameStartInner(playerIndex);
      } finally {
         gameNs += System.nanoTime() - t0;
         gameFrames++;
      }
   }

   static long gameNs, gameFrames, joinNs;
   private static volatile FrameState pending;
   private static volatile int pendingPlayer;
   private static volatile boolean pendingDone;
   private static final Object PENDING_LOCK = new Object();
   private static final java.util.concurrent.ExecutorService GATHER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-entity-shadows");
      t.setDaemon(true);
      return t;
   });
   private static final Runnable GATHER_TASK = () -> {
      FrameState f = pending;
      try {
         if (f != null) {
            Probes.candidates(f);
            if (f.on && f.method == 2) {
               Probes.gather(f, pendingPlayer);
            }
            if (f.torchCount > 0 || f.aoOn) {
               Torches.buildGrid();
            }
            if (f.torchCount > 0) {
               Torches.gather(f, pendingPlayer);
            }
            if (f.aoOn) {
               Ao.gather(f, pendingPlayer);
            }
         }
      } catch (Throwable t) {
         Log.warn("entity shadows: gather failed: " + t);
      } finally {
         synchronized (PENDING_LOCK) {
            pendingDone = true;
            PENDING_LOCK.notifyAll();
         }
      }
   };

   /**
    * Dev (devEntityShadowDepthCopy): the world depth copied right before the moving objects (what screen-space contact shadows
    * on the entities would read: the models write the depth they would march), in GPU section "esDepthCopy" - its cost decides
    * whether the technique can fit the budget.
    */
   private static final class DepthCopy extends TextureDraw.GenericDrawer {
      private int copy, w, h;

      @Override
      public void render() {
         int fbo = zombie.core.textures.TextureFBO.lastID;
         int tex = FogPass.sceneDepthTexture(fbo);
         if (tex == 0) {
            return;
         }
         int tw = FogPass.sceneDepthWidth(fbo), th = FogPass.sceneDepthHeight(fbo);
         if (this.copy == 0 || tw != this.w || th != this.h) {
            if (this.copy != 0) {
               GL11.glDeleteTextures(this.copy);
            }
            this.w = tw;
            this.h = th;
            this.copy = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.copy);
            org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8, tw, th);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Log.info("entity shadows: dev depth copy " + tw + "x" + th);
         }
         org.lwjgl.opengl.GL43.glCopyImageSubData(tex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, this.copy, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, tw, th, 1);
      }
   }

   private static final DepthCopy DEPTH_COPY = new DepthCopy();

   /** Game thread, right before the moving objects draw (FBORenderCell): the worker's gather done (the tiles change from here). */
   public static void beforeMoving(int playerIndex) {
      if (Config.DEV_ENTITY_SHADOW_DEPTH_COPY) {
         GpuSections.begin("esDepthCopy");
         SpriteRenderer.instance.drawGeneric(DEPTH_COPY);
         GpuSections.end("esDepthCopy");
      }
      if (pending == null) {
         queueCompute();
         return;
      }
      long t0 = System.nanoTime();
      synchronized (PENDING_LOCK) {
         while (!pendingDone) {
            try {
               PENDING_LOCK.wait();
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }
      pending = null;
      joinNs += System.nanoTime() - t0;
      queueCompute();
   }

   /** Game thread, in beforeMoving: the frame's probe compute, after the cutaway mask and before the models (see ComputeDrawer). */
   private static void queueCompute() {
      FrameState f = queued;
      queued = null;
      if (f == null || Config.ENTITY_SHADOW_COMPUTE_EARLY || !f.on || f.method != 2) {
         return;
      }
      ComputeDrawer c = COMPUTES[(frameIndex - 1) & 3];
      c.f = f;
      GpuSections.begin("esProbes"); // (the probe compute, when a brick changed)
      SpriteRenderer.instance.drawGeneric(c);
      GpuSections.end("esProbes");
   }

   private static void frameStartInner(int playerIndex) {
      queued = null;
      if (!Config.ENTITY_SHADOWS || !patched || failed) {
         if (rt.on) {
            FrameState f = FRAMES[frameIndex++ & 3];
            f.on = false;
            SpriteRenderer.instance.drawGeneric(f);
         }
         return;
      }
      if (++statFrames % 600 == 0) {
         Log.info(stats());
      }
      FrameState f = FRAMES[frameIndex++ & 3];
      String v = variant();
      f.on = Config.SUN_SHADOWS && SunShadow.dir[3] > 0F && Overrides.enabled() && !"off".equals(v); // off: the draws stay as before (the CPU factor in a character's ambient)
      f.method = "cpu".equals(v) ? 0 : "pixel".equals(v) ? 1 : 2;
      f.s = SunShadow.dir[3];
      float wx = SunShadow.world[0], wy = SunShadow.world[1], wz = SunShadow.world[2];
      float h = (float)Math.sqrt(wx * wx + wy * wy);
      f.overhead = h < 1e-3F ? 1F : 0F;
      f.dx = h < 1e-3F ? 1F : wx / h;
      f.dy = h < 1e-3F ? 0F : wy / h;
      f.slope = h < 1e-3F ? 0F : wz / h / LEVEL;
      f.lx = wx;
      f.ly = wy;
      f.lz = wz;
      f.form = "flat".equals(v) ? 0F : Config.ENTITY_SHADOW_FORM_PCT / 100F; // flat (dev A/B): the probe volume without the facing
      f.bricks.clear();
      f.rows = 0;
      f.jobCount = 0;
      f.casterCount = 0;
      f.dynJobCount = 0;
      f.castersOn = Config.ENTITY_SHADOW_CASTERS && !"nocast".equals(v);
      f.skip = "pskip".equals(v);
      f.vsOnly = "pvs".equals(v);
      f.noUniform = "nouni".equals(v);
      f.forceUniform = "uni".equals(v);
      f.perPixelAll = "ppix".equals(v);
      f.dynCompute = "dyncs".equals(v);
      f.noDedupe = "nodedupe".equals(v);
      f.noReset = "noreset".equals(v);
      f.noMemo = "nomemo".equals(v);
      f.cloudOn = Config.ENTITY_SHADOW_CLOUDS && !"noclouds".equals(v) && CloudShadow.entityMapping(f.cloudMap);
      // self-shadows only where a body is big enough on screen to show them (entityShadowSelfMaxZoom)
      f.selfOn = Config.ENTITY_SHADOW_SELF && Config.SUN_SHADOW_MESHES && !"noself".equals(v)
         && zombie.iso.IsoCamera.frameState.zoom <= Config.ENTITY_SHADOW_SELF_MAX_ZOOM_PCT / 100F;
      f.penTan = (float)Math.tan(Math.toRadians(Math.max(2.0, 3.0 * Math.max(1, Config.SUN_SHADOW_SOFTNESS_PCT) / 100.0)));
      f.capK = 1F / f.penTan;
      f.perVertex = "probev".equals(v) || "probe".equals(v) && Config.ENTITY_SHADOW_PER_VERTEX;
      Torches.snapshot(f, v);
      f.aoOn = Config.ENTITY_SHADOW_AO_PCT > 0 && Overrides.enabled() && !"off".equals(v) && !"noao".equals(v);
      f.aoStrength = Config.ENTITY_SHADOW_AO_PCT / 100F;
      f.wantDrawn = f.on && f.method == 2 || f.torchCount > 0 || f.aoOn;
      if (f.wantDrawn) {
         // on a worker (entityShadowAsync): it reads positions the render phase leaves alone and the tiles CapsuleShadow assigns
         // only while the moving objects draw; beforeMoving() joins it
         if (Config.ENTITY_SHADOW_ASYNC) {
            pending = f;
            pendingPlayer = playerIndex;
            pendingDone = false;
            GATHER.submit(GATHER_TASK);
         } else {
            Probes.candidates(f);
            if (f.on && f.method == 2) {
               Probes.gather(f, playerIndex);
            }
            if (f.torchCount > 0 || f.aoOn) {
               Torches.buildGrid();
            }
            if (f.torchCount > 0) {
               Torches.gather(f, playerIndex);
            }
            if (f.aoOn) {
               Ao.gather(f, playerIndex);
            }
         }
      }
      f.dev = Config.DEV_ENTITY_SHADOW_VIEW;
      boolean timed = f.on && f.method == 2 && Config.ENTITY_SHADOW_COMPUTE_EARLY; // (whether a compute runs is known on the render thread; late, queueCompute times it)
      if (Config.DEV_ENTITY_SHADOW_VIEW_CYCLE > 0) {
         long now = System.currentTimeMillis();
         if (viewT0 == 0L) {
            viewT0 = now;
            Log.info("entity shadows: dev views 0 / 1 / 2 / 3 / 4 / 5 every " + Config.DEV_ENTITY_SHADOW_VIEW_CYCLE + " ms from epoch_ms " + now);
         }
         f.dev = (int)((now - viewT0) / Config.DEV_ENTITY_SHADOW_VIEW_CYCLE % 6L);
      }
      if (timed) {
         GpuSections.begin("esProbes"); // (the probe compute, when a brick changed)
      }
      queued = f; // its probe compute goes out at beforeMoving (ComputeDrawer)
      SpriteRenderer.instance.drawGeneric(f);
      if (timed) {
         GpuSections.end("esProbes");
      }
   }

   /**
    * Torch and headlight shadows on characters: a character lit by a torch or a car's headlight takes the shadow of the people,
    * animals and cars between it and the light (capsules, as the sun's moving casters). The torches come from the lighting's
    * own list (LightingJNI, game thread); the casters and receivers are the objects drawn as models (Probes.candidates), on the
    * gather worker; bind() finds which of the draw's model lights is the torch (its square) and hands the capsules over.
    */
   static final class Torches {
      static final int MAX = 16, STRIDE = 10; // torches a frame; x, y, z (levels), reach, direction x, y, cone cosine (-2 none), strength, kind (2 a vehicle light), the lamp's height (metric)
      static final int MAX_CASTERS = 4;
      static final int ROW = 8 + 8 * MAX_CASTERS; // [0..2] the torch (world squares, z metric), [3] casters, [4] strength x its share here, [7] lift, the capsules
      static final float CELL = 4F; // the casters' grid (squares)
      static final java.util.HashMap<Long, int[]> GRID = new java.util.HashMap<>();
      static float[] caps = new float[8 * 256];
      static final java.util.ArrayList<Object> CAP_OWNERS = new java.util.ArrayList<>();
      private static final float[] ONE = new float[7 * 3];
      private static final int[] SEL = new int[MAX_CASTERS];
      private static final float[] SCORE = new float[MAX_CASTERS];
      static long receivers, rows, castersTested, gatherNs, gathers, binds, matched, gridNs;

      /**
       * Game thread (frameStart): the frame's torches and headlights as CapsuleShadow's ground pass takes them (the drawn
       * lens, the lamp's height, a headlight's kind) and the frame's strength (the darkness x sunShadowTorchPct).
       */
      static void snapshot(FrameState f, String v) {
         f.torchCount = 0;
         if (!Config.ENTITY_SHADOW_TORCHES || !Overrides.enabled() || "off".equals(v) || "notorch".equals(v)) {
            return;
         }
         zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
         float day = cm == null ? 0F : Math.max(0F, Math.min(1F, cm.getDayLightStrength()));
         // a torch's shadow matters in the dark only (CapsuleShadow: none above 90 % daylight)
         f.torchStrength = Math.max(0F, Math.min(1F, (0.9F - day) / 0.6F)) * Math.max(0, Config.SUN_SHADOW_TORCH_PCT) / 100F;
         if (f.torchStrength <= 0.02F) {
            return;
         }
         java.util.ArrayList<zombie.characters.IsoGameCharacter.TorchInfo> list = zombie.iso.LightingJNI.pzoptTorches();
         for (int i = 0; i < list.size() && f.torchCount < MAX; i++) {
            zombie.characters.IsoGameCharacter.TorchInfo t = list.get(i);
            if (t == null || t.id == 0 || t.dist <= 0F || t.strength <= 0.05F) {
               continue;
            }
            float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
            if (len < 1e-4F) {
               continue;
            }
            boolean car = t.id >= 4096 || t.focusing > 0;
            float tx = TorchSource.x(t), ty = TorchSource.y(t);
            boolean dup = false;
            for (int k = 0; k < f.torchCount; k++) { // (the game sends every lit item of a player at the same spot: the strongest)
               int o = k * STRIDE;
               if (Math.abs(f.torches[o] - tx) < 0.05F && Math.abs(f.torches[o + 1] - ty) < 0.05F) {
                  if (f.torches[o + 7] < t.strength) {
                     f.torches[o + 7] = t.strength;
                  }
                  dup = true;
                  break;
               }
            }
            if (dup) {
               continue;
            }
            int o = f.torchCount++ * STRIDE;
            f.torches[o] = tx;
            f.torches[o + 1] = ty;
            f.torches[o + 2] = t.z;
            f.torches[o + 3] = Math.max(1F, t.dist);
            f.torches[o + 4] = t.angleX / len;
            f.torches[o + 5] = t.angleY / len;
            f.torches[o + 6] = t.cone ? t.dot : -2F;
            f.torches[o + 7] = t.strength;
            f.torches[o + 8] = car ? 2F : 1F;
            f.torches[o + 9] = (float)Math.floor(t.z) * LEVEL + (car ? 0.75F : t.pzoptSrc ? TorchSource.height(t, 0F) * LEVEL : 1.35F);
         }
      }

      /** PixelLight's fitted torch intensity at (x, y) (CapsuleShadow's torch(): the share of the light the lamp gives in the dark). */
      private static float share(float[] T, int o, float x, float y) {
         float vx = x - T[o], vy = y - T[o + 1], d = (float)Math.sqrt(vx * vx + vy * vy);
         boolean car = T[o + 8] > 1.5F;
         float q = Math.max(0F, Math.min(1F, 1F - d / T[o + 3]));
         float fall = car ? q * (float)Math.sqrt(q) * (0.93F + 0.07F * q) : q * (0.85F + 0.15F * q);
         float ang = 1F;
         if (T[o + 6] > -1.5F && d > 1e-3F) {
            float c0 = car ? T[o + 6] - 0.28F : T[o + 6] + 0.025F, c1 = car ? 1F : 0.95F;
            ang = Math.max(0F, Math.min(1F, ((vx * T[o + 4] + vy * T[o + 5]) / d - c0) / Math.max(c1 - c0, 0.05F)));
         }
         return Math.min(1F, (car ? 1.57F : 1.76F) * T[o + 7] * fall * ang);
      }

      static int capCount; // the capsules in the grid this frame (buildGrid)

      /** Worker (after Probes.candidates): every candidate's capsules, in a grid of CELL squares (the torches' and Ao's casters). */
      static void buildGrid() {
         long t0 = System.nanoTime();
         GRID.clear();
         CAP_OWNERS.clear();
         int nc = 0;
         for (IsoMovingObject o : Probes.CANDIDATES) {
            int k = Probes.capsules(o, ONE, false);
            for (int q = 0; q < k; q++) {
               if (caps.length < (nc + 1) * 8) {
                  caps = java.util.Arrays.copyOf(caps, caps.length * 2);
               }
               int c = nc * 8, s = q * 7;
               System.arraycopy(ONE, s, caps, c, 7);
               caps[c + 7] = 0F;
               CAP_OWNERS.add(o);
               // the capsule into every grid cell its box touches (a car spans two)
               float r = ONE[s + 6];
               int gx0 = (int)Math.floor((Math.min(ONE[s], ONE[s + 3]) - r) / CELL), gx1 = (int)Math.floor((Math.max(ONE[s], ONE[s + 3]) + r) / CELL);
               int gy0 = (int)Math.floor((Math.min(ONE[s + 1], ONE[s + 4]) - r) / CELL), gy1 = (int)Math.floor((Math.max(ONE[s + 1], ONE[s + 4]) + r) / CELL);
               for (int gy = gy0; gy <= gy1; gy++) {
                  for (int gx = gx0; gx <= gx1; gx++) {
                     long key = (long)gx << 32 | gy & 0xFFFFFFFFL;
                     int[] l = GRID.get(key);
                     if (l == null) {
                        l = new int[9];
                        GRID.put(key, l);
                     } else if (l[0] + 1 >= l.length) {
                        l = java.util.Arrays.copyOf(l, l.length * 2);
                        GRID.put(key, l);
                     }
                     l[++l[0]] = nc;
                  }
               }
               nc++;
            }
         }
         capCount = nc;
         gridNs += System.nanoTime() - t0;
      }

      /** Worker (after buildGrid): for every character in a torch's reach, the capsules between it and the torch. */
      static void gather(FrameState f, int playerIndex) {
         long t0 = System.nanoTime();
         f.torchRows.clear();
         if (capCount == 0) {
            gatherNs += System.nanoTime() - t0;
            gathers++;
            return;
         }
         for (IsoMovingObject o : Probes.CANDIDATES) {
            if (!(o instanceof zombie.characters.IsoGameCharacter chr) || o.getAlpha(playerIndex) < 0.01F) {
               continue;
            }
            float x = o.getX(), y = o.getY(), z = o.getZ();
            // the torch that gives it most of its light, on its level
            int best = -1;
            float bestShare = 0.02F;
            for (int t = 0; t < f.torchCount; t++) {
               int b = t * STRIDE;
               float dx = f.torches[b] - x, dy = f.torches[b + 1] - y;
               if ((int)Math.floor(f.torches[b + 2]) != (int)Math.floor(z) || dx * dx + dy * dy < 0.04F) {
                  continue;
               }
               float sh = share(f.torches, b, x, y);
               if (sh > bestShare) {
                  best = t;
                  bestShare = sh;
               }
            }
            if (best < 0) {
               continue;
            }
            receivers++;
            int b = best * STRIDE;
            float tx = f.torches[b], ty = f.torches[b + 1], tz = f.torches[b + 9];
            float px = x, py = y, pz = (float)Math.floor(z) * LEVEL + 1.2F;
            float ux = tx - px, uy = ty - py, uz = tz - pz, len = (float)Math.sqrt(ux * ux + uy * uy + uz * uz);
            int count = 0;
            // the grid cells along the segment (each capsule tested once: the last receiver that tested it)
            int gx0 = (int)Math.floor(Math.min(px, tx) / CELL), gx1 = (int)Math.floor(Math.max(px, tx) / CELL);
            int gy0 = (int)Math.floor(Math.min(py, ty) / CELL), gy1 = (int)Math.floor(Math.max(py, ty) / CELL);
            stamp++;
            for (int gy = gy0; gy <= gy1; gy++) {
               for (int gx = gx0; gx <= gx1; gx++) {
                  if (!cellNearSegment(gx, gy, px, py, tx, ty)) {
                     continue;
                  }
                  int[] l = GRID.get((long)gx << 32 | gy & 0xFFFFFFFFL);
                  if (l == null) {
                     continue;
                  }
                  for (int i = 1; i <= l[0]; i++) {
                     int c = l[i];
                     if (seen.length <= c) {
                        seen = java.util.Arrays.copyOf(seen, Math.max(c + 1, seen.length * 2));
                     }
                     if (seen[c] == stamp) {
                        continue;
                     }
                     seen[c] = stamp;
                     Object owner = CAP_OWNERS.get(c);
                     if (owner == o) {
                        continue; // its own body
                     }
                     int cb = c * 8;
                     float r = caps[cb + 6];
                     // the torch's holder (the light inside or at its body) casts none from its own torch
                     if (segDist(tx, ty, tz, tx, ty, tz, caps[cb], caps[cb + 1], caps[cb + 2], caps[cb + 3], caps[cb + 4], caps[cb + 5]) - r < 0.45F) {
                        continue;
                     }
                     castersTested++;
                     float d = segDist(px, py, pz, tx, ty, tz, caps[cb], caps[cb + 1], caps[cb + 2], caps[cb + 3], caps[cb + 4], caps[cb + 5]);
                     float along = SEG_T * len; // (where along the receiver -> torch segment it passes closest)
                     if (along < 0.3F || len - along < 0.6F) {
                        continue;
                     }
                     float miss = d - r - Probes.RECV_HALF_WIDTH * 0.75F;
                     if (miss > 0F) {
                        continue;
                     }
                     if (count < MAX_CASTERS) {
                        SEL[count] = c;
                        SCORE[count++] = miss;
                     } else {
                        int worst = 0;
                        for (int q = 1; q < count; q++) {
                           if (SCORE[q] > SCORE[worst]) worst = q;
                        }
                        if (miss < SCORE[worst]) {
                           SEL[worst] = c;
                           SCORE[worst] = miss;
                        }
                     }
                  }
               }
            }
            if (count == 0) {
               continue;
            }
            float[] row = new float[ROW];
            row[0] = tx;
            row[1] = ty;
            row[2] = tz;
            row[3] = count;
            row[4] = f.torchStrength * bestShare;
            row[7] = chr.isSeatedInVehicle() ? 0F : 0.29393876F; // DoPushIsoStuff's model offset (bind)
            for (int q = 0; q < count; q++) {
               int cb = SEL[q] * 8, t = 8 + q * 8;
               // as the shaders read a capsule: a xyz r, b xyz -
               row[t] = caps[cb];
               row[t + 1] = caps[cb + 1];
               row[t + 2] = caps[cb + 2];
               row[t + 3] = caps[cb + 6];
               row[t + 4] = caps[cb + 3];
               row[t + 5] = caps[cb + 4];
               row[t + 6] = caps[cb + 5];
            }
            f.torchRows.put(o, row);
            rows++;
         }
         gatherNs += System.nanoTime() - t0;
         gathers++;
      }

      static int stamp;
      static int[] seen = new int[256];
      static float SEG_T; // segDist's parameter along the first segment

      /** Whether the grid cell (gx, gy) is within a cell's reach of the segment p -> t (the capsules' radius margin). */
      private static boolean cellNearSegment(int gx, int gy, float px, float py, float tx, float ty) {
         float cx = (gx + 0.5F) * CELL, cy = (gy + 0.5F) * CELL;
         float ux = tx - px, uy = ty - py, l2 = ux * ux + uy * uy;
         float t = l2 > 1e-6F ? Math.max(0F, Math.min(1F, ((cx - px) * ux + (cy - py) * uy) / l2)) : 0F;
         float dx = px + ux * t - cx, dy = py + uy * t - cy;
         return dx * dx + dy * dy <= CELL * CELL; // (half diagonal 0.71 cell + a capsule's reach)
      }

      /** The distance between the segments p0 -> p1 and q0 -> q1 (SEG_T: the closest point's parameter on the first). */
      static float segDist(float p0x, float p0y, float p0z, float p1x, float p1y, float p1z, float q0x, float q0y, float q0z, float q1x, float q1y, float q1z) {
         float dx = p1x - p0x, dy = p1y - p0y, dz = p1z - p0z, ex = q1x - q0x, ey = q1y - q0y, ez = q1z - q0z;
         float rx = p0x - q0x, ry = p0y - q0y, rz = p0z - q0z;
         float a = dx * dx + dy * dy + dz * dz, e = ex * ex + ey * ey + ez * ez, ff = ex * rx + ey * ry + ez * rz;
         float s, t;
         if (a <= 1e-6F && e <= 1e-6F) {
            s = 0F;
            t = 0F;
         } else if (a <= 1e-6F) {
            s = 0F;
            t = Math.max(0F, Math.min(1F, ff / e));
         } else {
            float c = dx * rx + dy * ry + dz * rz;
            if (e <= 1e-6F) {
               t = 0F;
               s = Math.max(0F, Math.min(1F, -c / a));
            } else {
               float bb = dx * ex + dy * ey + dz * ez, den = a * e - bb * bb;
               s = den > 1e-9F ? Math.max(0F, Math.min(1F, (bb * ff - c * e) / den)) : 0F;
               t = (bb * s + ff) / e;
               if (t < 0F) {
                  t = 0F;
                  s = Math.max(0F, Math.min(1F, -c / a));
               } else if (t > 1F) {
                  t = 1F;
                  s = Math.max(0F, Math.min(1F, (bb - c) / a));
               }
            }
         }
         SEG_T = s;
         float qx = p0x + dx * s - (q0x + ex * t), qy = p0y + dy * s - (q0y + ey * t), qz = p0z + dz * s - (q0z + ez * t);
         return (float)Math.sqrt(qx * qx + qy * qy + qz * qz);
      }

      static final java.nio.FloatBuffer TC = org.lwjgl.BufferUtils.createFloatBuffer((2 + 2 * MAX_CASTERS) * 4);
      static final float[] TCA = new float[(2 + 2 * MAX_CASTERS) * 4];

      /** Render thread (bind): the draw's torch shadow when the object has a row; false otherwise. */
      static boolean bind(Prog p, int id, FrameState f, IsoMovingObject obj) {
         float[] row = obj != null ? f.torchRows.get(obj) : null;
         if (row == null) {
            return false;
         }
         binds++;
         if (!frameUniforms(p, id, f)) {
            return false;
         }
         matched++;
         int n = (int)row[3];
         float[] d = TCA;
         d[0] = row[0];
         d[1] = row[1];
         d[2] = row[2];
         d[3] = row[4];
         d[4] = row[7];
         d[5] = n;
         d[6] = 10F; // (a torch is small: a sharp penumbra)
         d[7] = f.dev; // (dev view 13 reads it: the draw's A holds none at night)
         System.arraycopy(row, 8, d, 8, n * 8);
         GL20.glUniform4fv(p.tc, TC.limit(8 + n * 8).put(0, d, 0, 8 + n * 8));
         p.tcSet = true;
         p.tcN = 8 + n * 8;
         return true;
      }

      static String stats() {
         String s = String.format(java.util.Locale.ROOT, " | torches: receivers %d, shadowed %d, casters tested %d, binds %d matched %d, gather %.1f us, grid %.1f us",
            receivers, rows, castersTested, binds, matched, gathers == 0 ? 0.0 : gatherNs / 1e3 / gathers, gathers == 0 ? 0.0 : gridNs / 1e3 / gathers);
         receivers = rows = castersTested = binds = matched = gatherNs = gathers = gridNs = 0L;
         return s;
      }
   }

   /**
    * Capsule ambient occlusion on characters: for every character drawn as a model, the up to 3 nearest capsules of other
    * bodies and cars within REACH of its own (the torches' grid of the drawn models), on the gather worker; bind() hands them
    * to the shaders, which take that share of the ambient light (AO_GLSL).
    */
   static final class Ao {
      static final int MAX_CASTERS = 3;
      static final float REACH = 1.25F; // squares from a caster's surface the occlusion fades out at
      static final int ROW = 4 + 8 * MAX_CASTERS; // [0] casters, [1] lift; the capsules (a xyz r, b xyz -)
      private static final float[] OWN = new float[7 * 3];
      private static final int[] SEL = new int[MAX_CASTERS];
      private static final float[] SCORE = new float[MAX_CASTERS];
      static final java.nio.FloatBuffer AO = org.lwjgl.BufferUtils.createFloatBuffer((1 + 2 * MAX_CASTERS) * 4);
      static final float[] AOA = new float[(1 + 2 * MAX_CASTERS) * 4];
      static long receivers, rows, gatherNs, gathers, binds;

      /** Worker (after Torches.buildGrid). */
      static void gather(FrameState f, int playerIndex) {
         long t0 = System.nanoTime();
         f.aoRows.clear();
         if (Torches.capCount == 0) {
            gatherNs += System.nanoTime() - t0;
            gathers++;
            return;
         }
         float[] caps = Torches.caps;
         for (IsoMovingObject o : Probes.CANDIDATES) {
            if (!(o instanceof zombie.characters.IsoGameCharacter chr) || o.getAlpha(playerIndex) < 0.01F || Probes.capsules(o, OWN, false) != 1) {
               continue;
            }
            receivers++;
            float x = o.getX(), y = o.getY();
            float reach = REACH + 1.2F; // (a car's capsule radius)
            int gx0 = (int)Math.floor((x - reach) / Torches.CELL), gx1 = (int)Math.floor((x + reach) / Torches.CELL);
            int gy0 = (int)Math.floor((y - reach) / Torches.CELL), gy1 = (int)Math.floor((y + reach) / Torches.CELL);
            Torches.stamp++;
            int count = 0;
            for (int gy = gy0; gy <= gy1; gy++) {
               for (int gx = gx0; gx <= gx1; gx++) {
                  int[] l = Torches.GRID.get((long)gx << 32 | gy & 0xFFFFFFFFL);
                  if (l == null) {
                     continue;
                  }
                  for (int i = 1; i <= l[0]; i++) {
                     int c = l[i];
                     if (Torches.seen.length <= c) {
                        Torches.seen = java.util.Arrays.copyOf(Torches.seen, Math.max(c + 1, Torches.seen.length * 2));
                     }
                     if (Torches.seen[c] == Torches.stamp || Torches.CAP_OWNERS.get(c) == o) {
                        continue;
                     }
                     Torches.seen[c] = Torches.stamp;
                     int cb = c * 8;
                     float gap = Torches.segDist(OWN[0], OWN[1], OWN[2], OWN[3], OWN[4], OWN[5], caps[cb], caps[cb + 1], caps[cb + 2], caps[cb + 3], caps[cb + 4], caps[cb + 5])
                        - caps[cb + 6] - OWN[6];
                     if (gap > REACH) {
                        continue;
                     }
                     if (count < MAX_CASTERS) {
                        SEL[count] = c;
                        SCORE[count++] = gap;
                     } else {
                        int worst = 0;
                        for (int q = 1; q < count; q++) {
                           if (SCORE[q] > SCORE[worst]) worst = q;
                        }
                        if (gap < SCORE[worst]) {
                           SEL[worst] = c;
                           SCORE[worst] = gap;
                        }
                     }
                  }
               }
            }
            if (count == 0) {
               continue;
            }
            float[] row = new float[ROW];
            row[0] = count;
            row[1] = chr.isSeatedInVehicle() ? 0F : 0.29393876F; // DoPushIsoStuff's model offset (bind)
            for (int q = 0; q < count; q++) {
               int cb = SEL[q] * 8, t = 4 + q * 8;
               row[t] = caps[cb];
               row[t + 1] = caps[cb + 1];
               row[t + 2] = caps[cb + 2];
               row[t + 3] = caps[cb + 6];
               row[t + 4] = caps[cb + 3];
               row[t + 5] = caps[cb + 4];
               row[t + 6] = caps[cb + 5];
            }
            f.aoRows.put(o, row);
            rows++;
         }
         gatherNs += System.nanoTime() - t0;
         gathers++;
      }

      /** Render thread (bind): the draw's occluders when the object has a row; false otherwise. */
      static boolean bind(Prog p, int id, FrameState f, IsoMovingObject obj) {
         float[] row = obj != null ? f.aoRows.get(obj) : null;
         if (row == null || !frameUniforms(p, id, f)) {
            return false;
         }
         binds++;
         int n = (int)row[0];
         float[] d = AOA;
         d[0] = n;
         d[1] = row[1];
         d[2] = f.aoStrength;
         d[3] = f.dev; // (dev view 14)
         System.arraycopy(row, 4, d, 4, n * 8);
         GL20.glUniform4fv(p.ao, AO.limit(4 + n * 8).put(0, d, 0, 4 + n * 8));
         p.aoSet = true;
         p.aoN = 4 + n * 8;
         return true;
      }

      static String stats() {
         String s = String.format(java.util.Locale.ROOT, " | occlusion: receivers %d, occluded %d, binds %d, gather %.1f us",
            receivers, rows, binds, gathers == 0 ? 0.0 : gatherNs / 1e3 / gathers);
         receivers = rows = binds = gatherNs = gathers = 0L;
         return s;
      }
   }

   // ------------------------------------------------------------------------------------------------ shaders (launch)

   private static final float LEVEL = 2.4494897F;
   private static final Pattern VERSION = Pattern.compile("^\\uFEFF?\\s*#version\\s+(\\d+)[^\\n]*\\n");
   private static final Pattern CLIP = Pattern.compile("vec4\\s+o\\s*=\\s*ModelViewProjection\\s*\\*[^;]+;");

   static boolean target(String base) {
      if (base.startsWith("pzopt_") || base.contains("wireframe") || base.contains("_common") || base.contains("instanced")) {
         return false;
      }
      return base.startsWith("basicEffect") || base.startsWith("animalEffect") || base.startsWith("vehicle");
   }

   /** ShaderUnit: the model programs' sources with the per-pixel shade (launch; dormant while off). */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || !noCompileCheck && (!Config.ENTITY_SHADOWS || !Overrides.enabled() || CoreGl.legacyMac() || CoreGl.active || !gl43Available())) {
         return code; // (the probe volume needs compute: without GL 4.3, macOS's 4.1 core context included, the programs stay stock and the one value a body applies)
      }
      String f = fileName.replace('\\', '/');
      int slash = f.lastIndexOf('/');
      String base = slash < 0 ? f : f.substring(slash + 1);
      if (!target(base)) {
         return code;
      }
      if (!noCompileCheck && !Config.SUN_SHADOWS) {
         // sun shadows off at launch: the model programs stay stock (a patch into every player's model shaders for a feature
         // that is off made every character, car and item invisible on AMD / Windows, 2026-10-08); turned on later, the
         // shade per pixel comes at the next launch and the one value a body applies until then
         if (STOCK_LOGGED.compareAndSet(false, true)) {
            Log.info("entity shadows: sun shadows off at launch, the model shaders stay stock");
         }
         return code;
      }
      try {
         if (base.endsWith(".vert")) {
            return patchVert(base, code);
         }
         if (base.endsWith(".frag")) {
            return patchFrag(base, code);
         }
      } catch (RuntimeException e) {
         Log.warn("entity shadows: " + base + " not patched: " + e);
      }
      return code;
   }

   private static String patchVert(String base, String code) {
      Matcher v = VERSION.matcher(code);
      Matcher m = CLIP.matcher(code);
      if (!v.find() || !m.find()) {
         Log.warn("entity shadows: " + base + " has changed shape, not patched");
         return code;
      }
      int version = Integer.parseInt(v.group(1));
      if (version < 330) {
         Log.warn("entity shadows: " + base + " is GLSL " + version + ", not patched");
         return code;
      }
      boolean bl = bindlessAvailable();
      String head = bl ? "#version " + Math.max(420, version) + " compatibility\n#extension GL_ARB_bindless_texture : require\n#define PZES_BINDLESS\n" // (animalEffect.vert writes varyings)
         : "#version " + version + "\n#extension GL_ARB_shading_language_420pack : enable\n";
      String decl = "out vec3 pzEsW;\nout vec3 pzEsN;\nout vec3 pzEsC;\nflat out vec4 pzEsQ;\nout float pzEsV;\nflat out float pzEsU;\nuniform mat4 pzEsClipToWorld;\n" + VERT_GLSL + "\n";
      String body = "\n\tpzEsW = (pzEsClipToWorld * vec4(o.xyz / o.w, 1.0)).xyz; // pzopt: entity shadows, the world position (x, y squares, z levels)";
      String c = code.substring(0, m.end()) + body + code.substring(m.end());
      c = head + decl + c.substring(v.end());
      // the world normal (level units; the shade rescales z), from the same matrices, and the probe lookup's vertex part:
      // written last, vertNormal is set by then
      int last = c.lastIndexOf('}');
      c = c.substring(0, last) + "\tpzEsN = " + (c.contains("vertNormal") ? "mat3(pzEsClipToWorld) * (mat3(ModelViewProjection) * vertNormal)" : "vec3(0.0, 0.0, 1.0)")
         + "; // pzopt: entity shadows, the world normal\n\tpzEsVertex(); // pzopt: entity shadows, the probe brick's cell (and the per-vertex shade)\n" + c.substring(last);
      if (!compiles(GL20.GL_VERTEX_SHADER, base, c)) {
         return code;
      }
      VERT_OK.add(program(base));
      return c;
   }

   /** Patch time (ShaderUnit, the context current): may the model programs read the probe textures by bindless handles? */
   static volatile boolean bindless;

   private static boolean bindlessAvailable() {
      if (noCompileCheck) {
         return bindless = testBindless;
      }
      if (!Config.ENTITY_SHADOW_BINDLESS) {
         return false;
      }
      try {
         bindless = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_bindless_texture;
      } catch (RuntimeException e) {
         bindless = false;
      }
      return bindless;
   }

   /** Patch time: does the context have GL 4.3 (the probe compute, the block stats' storage buffer in the fragment)? */
   private static boolean gl43Available() {
      if (noCompileCheck) {
         return testGl43;
      }
      try {
         return org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
      } catch (RuntimeException e) {
         return false;
      }
   }

   /** Vertex units patched, by program (the file name without _static and the extension): a fragment unit is patched only beside one. */
   private static final java.util.Set<String> VERT_OK = java.util.concurrent.ConcurrentHashMap.newKeySet();

   private static String program(String base) {
      String b = base.substring(0, base.lastIndexOf('.'));
      return b.endsWith("_static") ? b.substring(0, b.length() - 7) : b;
   }

   /** ShaderUnit compiles with the context current: a patched unit that does not compile is never handed to the game. */
   private static boolean compiles(int type, String base, String src) {
      if (noCompileCheck) {
         return true;
      }
      int test = GL20.glCreateShader(type);
      GL20.glShaderSource(test, src);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("entity shadows: patched " + base + " does not compile, it stays stock: " + log);
      }
      return ok;
   }

   private static String patchFrag(String base, String code) {
      Matcher v = VERSION.matcher(code);
      int main = code.indexOf("void main()");
      int brace = main < 0 ? -1 : code.indexOf('{', main);
      if (!v.find() || brace < 0 || code.indexOf("AmbientColour", brace) < 0) {
         Log.warn("entity shadows: " + base + " has changed shape, not patched");
         return code;
      }
      if (!VERT_OK.contains(program(base))) {
         // the vertex unit (compiled first) was not patched: the fragment's input would not link
         Log.warn("entity shadows: " + base + " without a patched vertex unit, not patched");
         return code;
      }
      int version = Integer.parseInt(v.group(1));
      // 330 compatibility: integer textures and layout(binding) (420pack) while the stock code's varying / texture2D /
      // gl_FragColor stay legal; the samplers need their units at link (the game validates with every sampler on unit 0, Mesa
      // fails two sampler types on one unit)
      boolean bl = bindlessAvailable(), gl43 = gl43Available();
      String head = "#version " + Math.max(gl43 ? 430 : bl ? 420 : 330, version) + " compatibility\n" + (bl ? "#extension GL_ARB_bindless_texture : require\n#define PZES_BINDLESS\n" : "")
         + (gl43 ? "#define PZES_STATS\n" : bl ? "" : "#extension GL_ARB_shading_language_420pack : enable\n");
      String in = version >= 130 ? "in" : "varying";
      String mainBody = code.substring(brace + 1).replace("AmbientColour", "pzEsAmb");
      if (base.startsWith("basicEffect") || base.startsWith("animalEffect")) {
         mainBody = TORCH_LIGHT.matcher(mainBody).replaceFirst("$0 lighting *= pzEsTorchShade();"); // (after the clamp: the shadow takes the torch's share of what is drawn)
      }
      String c = head + code.substring(v.end(), brace + 1)
         + "\n\tvec3 pzEsAmb = pzEsAmbient(AmbientColour) * pzEsAoAt(); // pzopt: entity shadows, the sun's share of the light taken away in the shade; the bodies next to it"
         + mainBody;
      // the declarations after the version and before the stock ones (the functions use only their own uniforms)
      int at = head.length();
      c = c.substring(0, at) + "in vec3 pzEsW;\nin vec3 pzEsN;\nin vec3 pzEsC;\nflat in vec4 pzEsQ;\nin float pzEsV;\nflat in float pzEsU;\n" + FRAG_GLSL + "\n" + TORCH_GLSL + "\n" + AO_GLSL + "\n" + c.substring(at);
      int last = c.lastIndexOf('}'); // main is the unit's last function: dev view 9 writes its code over the colour
      c = c.substring(0, last) + "\tif (pzEsAo[0].w > 13.5) gl_FragColor = vec4(vec3(pzEsAoAt()), 1.0); else if (pzEsTc[1].w > 12.5) gl_FragColor = vec4(vec3(pzEsTorchVis()), 1.0) * vec4(1.0, 1.0 - pzEsTc[0].w, 1.0 - pzEsTc[0].w, 1.0); else if (pzEsA.w > 11.5) gl_FragColor = vec4(pzEsCloudAt(vec3(pzEsW.xy, pzEsP0.z + 0.1), 0.0), pzEsP2.z, 0.0, 1.0); else if (pzEsA.w > 10.5) gl_FragColor = vec4(vec3(pzEsSelf()), 1.0); else if (pzEsA.w > 9.5) gl_FragColor = vec4(pzEsSunVisDbg(vec3(pzEsW.xy, pzEsMaxF(pzEsW.z, pzEsB.x + 0.02))), 1.0); else if (pzEsA.w > 8.5) gl_FragColor = vec4(pzEsSunVisDbg(pzEsDbg.xyz), 1.0); // pzopt: entity shadows, dev\n" + c.substring(last);
      if (!compiles(GL20.GL_FRAGMENT_SHADER, base, c)) {
         return code;
      }
      patched = true;
      Log.info("entity shadows: " + base + " patched");
      if (Config.DEV_ENTITY_SHADOW_CHECK) {
         try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-es-" + base + ".glsl"), c);
         } catch (Exception e) {
            Log.warn("entity shadows: source dump failed: " + e);
         }
      }
      return c;
   }

   /** ShaderUnit, dev (devEntityShadowCheck): the final source of a patched model unit into Zomboid/pzopt-esfinal-*.glsl; returns it unchanged. */
   public static String devFinal(String fileName, String code) {
      if (Config.DEV_ENTITY_SHADOW_CHECK && fileName != null && code != null && code.contains("pzEsW")) {
         String base = fileName.replace('\\', '/');
         base = base.substring(base.lastIndexOf('/') + 1);
         try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-esfinal-" + base + ".glsl"), code);
         } catch (Exception e) {
            Log.warn("entity shadows: final source dump failed: " + e);
         }
      }
      return code;
   }


   /**
    * Moving casters in the model shaders (no compute): the draw's up to 8 capsules (pzEsD[7..22]: a xyz r, b xyz -; world
    * squares, z metric), Quilez's capsule soft shadow towards the light; written without clamp / max (the game's util/math.h
    * declares overloads that hide the built-ins in the fragment units).
    */
   static final String CAPS_GLSL = String.join("\n",
      "float pzEsCap(vec3 ro, vec3 rd, vec3 a, vec3 b, float r, float k) {",
      "   vec3 ba = b - a, oa = ro - a;",
      "   float oad = dot(oa, rd), dba = dot(rd, ba), baba = dot(ba, ba), oaba = dot(oa, ba);",
      "   float den = baba - dba * dba;",
      "   vec2 th = vec2(-oad * baba + dba * oaba, oaba - oad * dba) / (den > 1e-6 ? den : 1e-6);",
      "   th.x = th.x < 0.0001 ? 0.0001 : th.x;",
      "   th.y = th.y < 0.0 ? 0.0 : th.y > 1.0 ? 1.0 : th.y;",
      "   float d = length(a + ba * th.y - (ro + rd * th.x)) - r;",
      "   float s = k * d / th.x + 0.5;",
      "   s = s < 0.0 ? 0.0 : s > 1.0 ? 1.0 : s;",
      "   return s * s * (3.0 - 2.0 * s);",
      "}",
      "float pzEsCasters(vec3 w) {", // w: world squares, z levels
      "   int n = int(pzEsK.x + 0.5);",
      "   if (n == 0) return 1.0;",
      "   vec3 p = vec3(w.xy, w.z * 2.4494897);",
      "   float v = 1.0;",
      "   for (int i = 0; i < 8; i++) {",
      "      if (i >= n) break;",
      "      vec4 a = pzEsD[7 + 2 * i], b = pzEsD[8 + 2 * i];",
      "      v *= pzEsCap(p, pzEsL.xyz, a.xyz, b.xyz, a.w, pzEsK.y);",
      "   }",
      "   return v;",
      "}");


   /**
    * Self-shadowing and the limbs' own shadows (entityShadowSelf, bindless): the entity's tile of the characters' sun depth
    * atlas (pzopt.ShadowAtlas, last frame's pose: the atlas is drawn after the models) looked up at the pixel, a hardware
    * 2 x 2 compare (pzEsT0: tile, half size, on; pzEsT1: the tile's centre, world squares, z metric; pzEsR the sun's view).
    */
   static final String SELF_GLSL = String.join("\n",
      "#ifdef PZES_BINDLESS",
      // (a plain sampler2D: the game's ShaderBufferData has no entry for sampler2DShadow and fails the program's setup; built
      // from the handle's two halves, no sampler uniform: see PROBE_SAMPLERS)
      "uniform int pzEsAtlasLo, pzEsAtlasHi;",
      "#define pzEsAtlas sampler2D(uvec2(uint(pzEsAtlasLo), uint(pzEsAtlasHi)))",
      "uniform mat3 pzEsR;",
      "float pzEsSelf() {",
      "   if (pzEsT0.z < 0.5) return 1.0;",
      "   vec3 n = pzEsN;",
      "   n.z *= 2.4494897;",
      "   n = dot(n, n) > 1e-12 ? normalize(n) : vec3(0.0, 0.0, 1.0);",
      // surfaces turning away from the light are the facing term's (and the depth map's terminator is stair-stepped there):
      // the self-shadow fades in from edge-on to a sixth of the way towards the light
      "   float ndl = dot(n, pzEsL.xyz);",
      "   if (ndl <= 0.0) return 1.0;",
      "   float texel = 2.0 * pzEsT0.y / " + ShadowAtlas.TILE + ".0;", // squares a texel of this tile
      "   vec3 P = vec3(pzEsW.xy, (pzEsW.z + pzEsP2.w) * 2.4494897) + n * (1.5 * texel);", // (the model's lift; a normal offset of a texel and a half)
      "   vec3 C = pzEsT1.xyz;",
      "   vec3 v = pzEsR * vec3(-(P.x - C.x), P.z - C.z, -(P.y - C.y));",
      "   float halfSize = pzEsT0.y;",
      "   vec2 tuv = v.xy / (2.0 * halfSize) + 0.5;",
      "   if (tuv.x < 0.0 || tuv.y < 0.0 || tuv.x > 1.0 || tuv.y > 1.0) return 1.0;",
      "   float tileUv = " + ((double)ShadowAtlas.TILE / ShadowAtlas.SIZE) + ";",
      "   vec2 org = vec2(mod(pzEsT0.x, " + ShadowAtlas.PER_ROW + ".0), floor(pzEsT0.x / " + ShadowAtlas.PER_ROW + ".0)) * tileUv;",
      "   vec2 uv = org + (tuv * (" + (ShadowAtlas.TILE - 3) + ".0) + 1.5) / " + ShadowAtlas.SIZE + ".0;", // (inside the tile's border texels)
      "   float dr = 0.5 - v.z / " + (2.0 * ShadowAtlas.DEPTH) + " - 0.004;",
      // a 2 x 2 compare, weighted bilinearly (what a compare sampler's linear filter does)
      "   vec2 tc = uv * " + ShadowAtlas.SIZE + ".0 - 0.5;",
      "   vec2 fw = fract(tc);",
      "   vec4 dd = textureGather(pzEsAtlas, (floor(tc) + 1.0) / " + ShadowAtlas.SIZE + ".0);", // (w: (0,0), z: (1,0), x: (0,1), y: (1,1))
      "   vec4 lit = vec4(greaterThanEqual(dd, vec4(dr)));",
      "   float sh = mix(mix(lit.w, lit.z, fw.x), mix(lit.x, lit.y, fw.x), fw.y);",
      "   float fade = ndl < 0.17 ? ndl / 0.17 : 1.0;",
      "   return mix(1.0, sh, fade * fade * (3.0 - 2.0 * fade));",
      "}",
      "#else",
      "float pzEsSelf() { return 1.0; }",
      "#endif");


   /**
    * Cloud shadows on the entities at the vertex / pixel (bindless; a car can straddle a cloud's soft edge): CloudShadow's
    * world mapping (transmittanceAt) on its field, two taps. Without it, the entity's one value.
    */
   static final String CLOUD_GLSL = String.join("\n",
      "uniform vec4 pzEsCm0, pzEsCm1, pzEsCm2;", // 1 / period, parA, parB, detail scale | uv offsets base, detail | 1 - cover, 1 / edge, erosion, opacity (<= 0: off)
      "#ifdef PZES_BINDLESS",
      "uniform int pzEsCloudLo, pzEsCloudHi;", // (the field's handle, no sampler uniform: see PROBE_SAMPLERS)
      "#define pzEsCloud sampler2D(uvec2(uint(pzEsCloudLo), uint(pzEsCloudHi)))",
      "float pzEsCloudAt(vec3 w, float fallback) {", // w: world squares, z levels
      "   if (pzEsCm2.w <= 0.0) return fallback;",
      "   vec2 p = w.xy + pzEsCm0.yz * w.z;",
      "   float d = (textureLod(pzEsCloud, p * pzEsCm0.x + pzEsCm1.xy, 0.0).r - pzEsCm2.x) * pzEsCm2.y;",
      "   if (d <= 0.0) return 1.0;",
      "   d = d > 1.0 ? 1.0 : d;",
      "   float e = textureLod(pzEsCloud, p * (pzEsCm0.x * pzEsCm0.w) + pzEsCm1.zw, 0.0).g * pzEsCm2.z;",
      "   d = (d - e) / (1.0 - e);",
      "   d = d < 0.0 ? 0.0 : d > 1.0 ? 1.0 : d;",
      "   return 1.0 - pzEsCm2.w * (1.0 - exp(-3.0 * d));",
      "}",
      "#else",
      "float pzEsCloudAt(vec3 w, float fallback) { return fallback; }",
      "#endif");



   /**
    * Capsule ambient occlusion on characters (Unreal's capsule indirect shadows, The Last of Us): the bodies and cars next to
    * a character take part of its ambient light, the side facing them most. pzEsAo[0]: casters, the model's lift (levels),
    * strength; up to 3 capsules (a xyz r, b xyz -; world squares, z metric). A capsule's occlusion is an infinite cylinder's
    * cosine-weighted share at the closest point of its axis, (r / l) / 2 x the facing, fading out by REACH.
    */
   static final String AO_GLSL = String.join("\n",
      "uniform vec4 pzEsAo[" + (1 + 2 * Ao.MAX_CASTERS) + "];",
      "float pzEsAoAt() {",
      "   int k = int(pzEsAo[0].x + 0.5);",
      "   if (k == 0) return 1.0;",
      "   vec3 p = vec3(pzEsW.xy, (pzEsW.z + pzEsAo[0].y) * 2.4494897);",
      "   vec3 n = pzEsN;",
      "   n.z *= 2.4494897;",
      "   n = dot(n, n) > 1e-12 ? normalize(n) : vec3(0.0, 0.0, 1.0);",
      "   float v = 1.0;",
      "   for (int i = 0; i < " + Ao.MAX_CASTERS + "; i++) {",
      "      if (i >= k) break;",
      "      vec4 a = pzEsAo[1 + 2 * i], b = pzEsAo[2 + 2 * i];",
      "      vec3 ba = b.xyz - a.xyz;",
      "      float h = dot(p - a.xyz, ba) / (dot(ba, ba) > 1e-6 ? dot(ba, ba) : 1e-6);",
      "      h = h < 0.0 ? 0.0 : h > 1.0 ? 1.0 : h;",
      "      vec3 d = a.xyz + ba * h - p;",
      "      float l = length(d);",
      "      l = l > a.w * 1.05 ? l : a.w * 1.05;",
      "      float c = dot(n, d) / l;",
      "      c = c < 0.0 ? 0.0 : c;",
      "      float fade = 1.0 - (l - a.w) / " + Ao.REACH + ";",
      "      fade = fade < 0.0 ? 0.0 : fade;",
      "      v *= 1.0 - 0.5 * (a.w / l) * c * fade * fade;",
      "   }",
      "   return 1.0 - pzEsAo[0].z * (1.0 - v);",
      "}");

   /**
    * Torch and headlight shadows on characters (any hour, indoors too), as CapsuleShadow's ground pass: the draw's torch
    * (pzEsTc[0]: world squares, z metric; w = strength x the torch's share of the light there, 0 none; [1]: the model's lift
    * in levels, casters, k, dev view) and up to 4 capsules between the receiver and it ([2..9]); the lit colour times
    * 1 - w (1 - the capsules' soft shadow towards the torch).
    */
   static final String TORCH_GLSL = String.join("\n",
      "uniform vec4 pzEsTc[" + (2 + 2 * Torches.MAX_CASTERS) + "];",
      "float pzEsTorchVis() {",
      "   if (pzEsTc[0].w <= 0.0) return 1.0;",
      "   vec3 p = vec3(pzEsW.xy, (pzEsW.z + pzEsTc[1].x) * 2.4494897);",
      "   vec3 d = pzEsTc[0].xyz - p;",
      "   float len = length(d);",
      "   vec3 rd = d / (len > 1e-4 ? len : 1e-4);",
      "   int n = int(pzEsTc[1].y + 0.5);",
      "   float v = 1.0;",
      "   for (int c = 0; c < " + Torches.MAX_CASTERS + "; c++) {",
      "      if (c >= n) break;",
      "      vec4 a = pzEsTc[2 + 2 * c], b = pzEsTc[3 + 2 * c];",
      "      v *= pzEsCap(p, rd, a.xyz, b.xyz, a.w, pzEsTc[1].z);",
      "   }",
      "   return v;",
      "}",
      "float pzEsTorchShade() { return 1.0 - pzEsTc[0].w * (1.0 - pzEsTorchVis()); }");
   private static final Pattern TORCH_LIGHT = Pattern.compile("lighting\\.z\\s*=\\s*min\\(\\s*lighting\\.z\\s*,\\s*1\\.0\\s*\\)\\s*;");

   /**
    * The probe textures, bindless (handles set once a program) or on their units; the vertex and fragment stages share them.
    * Bindless, every sampler is built from its handle's two halves in plain int uniforms (int: the game's ShaderBufferData
    * builds an entry for every active uniform and knows no uint / uvec2, a null entry fails the game's start), never a
    * layout(bindless_sampler) sampler uniform:
    * such a uniform holds 0 until its handle is set, i.e. "unit 0", and the game's ShaderProgram validates the program right
    * after the link, with its own sampler2Ds on unit 0 too. AMD's Windows driver counts bindless samplers in the rule that
    * samplers of two types may not share a unit and fails the validation (NVIDIA does not check it, Mesa leaves bindless
    * samplers out); the game then deletes the program, and every character, car and item drawn with it is invisible
    * (eceb5cf2, 2026-10-08).
    */
   static final String PROBE_SAMPLERS = String.join("\n",
      "#ifdef PZES_BINDLESS",
      "uniform int pzEsVolLo, pzEsVolHi, pzEsDynLo, pzEsDynHi;", // the probe atlas and the moving casters' atlas by handle: no binds a draw
      "#define pzEsVol sampler3D(uvec2(uint(pzEsVolLo), uint(pzEsVolHi)))",
      "#define pzEsDyn sampler3D(uvec2(uint(pzEsDynLo), uint(pzEsDynHi)))",
      "#else",
      "layout(binding = " + VOL_UNIT + ") uniform sampler3D pzEsVol;",
      "layout(binding = " + DYN_UNIT + ") uniform sampler3D pzEsDyn;",
      "#endif");

   /**
    * The vertex side (method 2 probe, 4 probev): the entity's brick from its row of the frame's table (P0 origin + cells a
    * side, P1 1 / spacing + moving casters, P2 the first atlas texel, the cloud transmittance, the model's lift); the
    * cell coordinate and the brick's facts for the fragment, or (probev) the whole shade.
    */
   static final String VERT_GLSL = String.join("\n",
      // a draw's values, one call: [0] = A (method, the constant shade / the uniform visibility / -, strength, dev view),
      // [1..3] the probe brick (P0 origin + cells a side, P1 1 / spacing + moving casters, P2 first atlas texel, cloud, lift)
      "uniform vec4 pzEsD[" + D_VEC4 + "];",
      "#define pzEsA pzEsD[0]",
      "#define pzEsP0 pzEsD[1]",
      "#define pzEsP1 pzEsD[2]",
      "#define pzEsP2 pzEsD[3]",
      "#define pzEsK pzEsD[4]",
      "#define pzEsT0 pzEsD[5]",
      "#define pzEsT1 pzEsD[6]",
      "uniform vec4 pzEsL;",
      PROBE_SAMPLERS,
      // the frame's brick table as a uniform block: a draw's row is the same for all its vertices, read from the constant
      // cache (a texelFetch of a table texture cost ~40 us a frame in a 40-zombie crowd: the vertices, not the pixels)
      CAPS_GLSL,
      CLOUD_GLSL,
      "void pzEsVertex() {",
      "   pzEsC = vec3(0.0);",
      "   pzEsQ = vec4(0.0);",
      "   pzEsV = 1.0;",
      "   pzEsU = -1.0;",
      "   if (pzEsA.x < 1.5 || pzEsA.x > 2.5 && pzEsA.x < 3.5 || pzEsA.x > 5.5) return;", // (3: pskip; 5: pvs, the vertex work of 2; 6: uniform)
      // the draw's brick (P0 origin + cells a side, P1 1 / spacing + moving casters, P2 the first atlas texel, the cloud
      // transmittance, the model's lift) as uniforms: no memory reads a vertex (a table read a vertex cost ~20 us in a crowd)
      "   vec4 P0 = pzEsP0, P1 = pzEsP1, P2 = pzEsP2;",
      "   pzEsC = (pzEsW + vec3(0.0, 0.0, P2.w) - P0.xyz) * P1.xyz;",
      "   pzEsQ = vec4(P2.xy, P0.w, (P2.z + 2.0) * (P1.w > 0.5 ? 1.0 : -1.0));",
      "   if (pzEsA.x < 3.5 || pzEsA.x > 4.5) return;",
      // probev: the tap and the facing here, once a vertex
      "   vec3 c = min(max(pzEsC, vec3(0.0)), vec3(P0.w - 1.0, P0.w - 1.0, " + (Probes.NZ - 1) + ".0)) + 0.5;",
      "   vec3 uvw = vec3((P2.xy + c.xy) * " + (1.0 / Probes.AX) + ", c.z * " + (1.0 / Probes.NZ) + ");",
      "   float v = P1.w > 0.5 ? textureLod(pzEsDyn, uvw, 0.0).r : textureLod(pzEsVol, uvw, 0.0).r;",
      "   v *= pzEsCloudAt(pzEsW + vec3(0.0, 0.0, P2.w), P2.z) * pzEsCasters(pzEsW + vec3(0.0, 0.0, P2.w));", // (the clouds at the vertex; the moving casters' capsules, when the draw has any)
      "   vec3 n = pzEsN;",
      "   n.z *= 2.4494897;",
      "   float h = dot(n, n) > 1e-12 ? dot(normalize(n), pzEsL.xyz) * 0.5 + 0.5 : 0.5;",
      "   pzEsV = 1.0 - pzEsA.z + pzEsA.z * v * (1.0 + pzEsL.w * (2.0 * h - 1.0));",
      "}");




   /** The fragment side: the shade factor at the fragment's world position (God rays' sunVis on absolute squares). */
   static final String FRAG_GLSL = String.join("\n",
      // methods (pzEsA.x): 0 constant, 1 per pixel, 2 probe volume, 4 per vertex, 6 uniform brick; 3 / 5 dev
      "#ifndef pzEsA",
      // (the vertex unit declares the same) a draw's values, one call: [0] = A (method, the constant shade / the uniform visibility / -, strength, dev view),
      // [1..3] the probe brick (P0 origin + cells a side, P1 1 / spacing + moving casters, P2 first atlas texel, cloud, lift)
      "uniform vec4 pzEsD[" + D_VEC4 + "];",
      "#define pzEsA pzEsD[0]",
      "#define pzEsP0 pzEsD[1]",
      "#define pzEsP1 pzEsD[2]",
      "#define pzEsP2 pzEsD[3]",
      "#define pzEsK pzEsD[4]",
      "#define pzEsT0 pzEsD[5]",
      "#define pzEsT1 pzEsD[6]",
      "#endif",
      "uniform vec4 pzEsL;", // the direction to the light (world, metric), w: the form contrast
      CAPS_GLSL,
      SELF_GLSL,
      CLOUD_GLSL,
      "uniform vec4 pzEsB;", // x: the object's floor level, y: cloud transmittance at the object, z: how much of its level a roof square fills, w: the model's lift (levels)
      "uniform vec4 pzEsDbg;", // dev: the object's position + 0.3 levels
      "uniform vec4 pzEsSun;", // horizontal direction to the light (unit), levels risen per square across, 1 = overhead
      "uniform vec4 pzEsLim;", // exit height (levels), max distance (squares), foliage extinction per square at full density, occupancy's lowest level
      "layout(binding = " + OCC_UNIT + ") uniform usampler3D pzEsOcc;",
      "layout(binding = " + TOP_UNIT + ") uniform usampler2D pzEsTop;",
      "const float PZES_LH = 2.4494897;",
      // (the game's util/math.h declares max(float, float) and clamp overloads, which hide the built-in ones in the units
      // that include it: none of max / clamp is called here)
      "float pzEsMaxF(float a, float b) { return a > b ? a : b; }",
      "vec2 pzEsMax2(vec2 a, vec2 b) { return vec2(pzEsMaxF(a.x, b.x), pzEsMaxF(a.y, b.y)); }",
      "ivec2 pzEsClampI(ivec2 v, ivec2 lo, ivec2 hi) { return ivec2(v.x < lo.x ? lo.x : v.x > hi.x ? hi.x : v.x, v.y < lo.y ? lo.y : v.y > hi.y ? hi.y : v.y); }",
      "uint pzEsOccAt(ivec2 sq, int lvl) {",
      "   int lz = lvl - int(pzEsLim.w);",
      "   if (lz < 0 || lz >= " + GodRays.OCC_L + ") return 0u;",
      "   return texelFetch(pzEsOcc, ivec3(sq.x & " + (GodRays.OCC_N - 1) + ", sq.y & " + (GodRays.OCC_N - 1) + ", lz), 0).r;",
      "}",
      "float pzEsChunkTop(ivec2 sq) {",
      "   return float(texelFetch(pzEsTop, ivec2((sq.x >> 3) & " + (GodRays.OCC_CHUNKS - 1) + ", (sq.y >> 3) & " + (GodRays.OCC_CHUNKS - 1) + "), 0).r) + pzEsLim.w;",
      "}",
      "bool pzEsEdge(uint e, float f) {",
      "   if (e == 0u) return false;",
      "   if (e == 1u) return true;",
      "   if (e == 2u) return f < 0.30 || f > 0.84;",
      "   return f > 0.80;",
      "}",
      "float pzEsHash(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.x + p.y) * p.z); }",
      "float pzEsLeaf(vec3 p) {",
      "   vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);",
      "   float a = mix(mix(pzEsHash(i), pzEsHash(i + vec3(1, 0, 0)), f.x), mix(pzEsHash(i + vec3(0, 1, 0)), pzEsHash(i + vec3(1, 1, 0)), f.x), f.y);",
      "   float b = mix(mix(pzEsHash(i + vec3(0, 0, 1)), pzEsHash(i + vec3(1, 0, 1)), f.x), mix(pzEsHash(i + vec3(0, 1, 1)), pzEsHash(i + vec3(1, 1, 1)), f.x), f.y);",
      "   return mix(a, b, f.z);",
      "}",
      "float pzEsSunVis(vec3 p) {", // p: absolute squares, z in levels
      "   ivec2 sq = ivec2(floor(p.xy));",
      "   int lvl = int(floor(p.z));",
      "   if (pzEsSun.w > 0.5) {",
      "      for (int L = lvl + 1; float(L) <= pzEsLim.x; L++) { if ((pzEsOccAt(sq, L) & 65u) != 0u) return 0.0; }",
      "      return 1.0;",
      "   }",
      "   vec2 d = pzEsSun.xy;",
      "   float slope = pzEsSun.z;",
      "   vec2 inv = 1.0 / pzEsMax2(abs(d), vec2(1e-5));",
      "   vec2 fr = p.xy - vec2(sq);",
      "   vec2 tMax = vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "   ivec2 stp = ivec2(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1);",
      "   float t = 0.0, vis = 1.0;",
      "   bool jumped = false;",
      "   for (int i = 0; i < 128; i++) {",
      "      if (!jumped && p.z + t * slope >= pzEsChunkTop(sq)) {",
      "         vec2 at = p.xy + d * t;",
      "         ivec2 c0 = (sq >> 3) * 8;",
      "         vec2 tx = vec2(d.x >= 0.0 ? float(c0.x + 8) - at.x : at.x - float(c0.x), d.y >= 0.0 ? float(c0.y + 8) - at.y : at.y - float(c0.y)) * inv;",
      "         t += pzEsMaxF(0.0, min(tx.x, tx.y) - 1e-3);",
      "         jumped = true;",
      "         if (p.z + t * slope > pzEsLim.x || t > pzEsLim.y) return vis;",
      "         at = p.xy + d * t;",
      "         sq = pzEsClampI(ivec2(floor(at)), c0, c0 + 7);",
      "         lvl = int(floor(p.z + t * slope));",
      "         fr = at - vec2(sq);",
      "         tMax = t + vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "         continue;",
      "      }",
      "      jumped = false;",
      "      float tn = min(tMax.x, tMax.y);",
      "      float zn = p.z + tn * slope;",
      "      int ln = int(floor(zn));",
      "      uint o = pzEsOccAt(sq, lvl);",
      "      uint fol = (o >> 8) & 15u;",
      "      if (fol != 0u) {",
      "         vec3 m = vec3(p.xy + d * (0.5 * (t + tn)), (p.z + 0.5 * (t + tn) * slope) * PZES_LH);",
      "         float n = pzEsLeaf(vec3(mod(m.xy, 1024.0), m.z) * 1.4);",
      "         vis *= exp(-pzEsLim.z * float(fol) * pzEsMaxF(0.0, n * 1.8 - 0.55) * (tn - t) * 1.2);",
      "      }",
      "      if ((o & 64u) != 0u && p.z + t * slope - float(lvl) < pzEsB.z) return 0.0;", // inside a roof square's fill (a pitched roof's ridge is near the level's top)
      "      for (int L = lvl + 1; L <= ln; L++) { if ((pzEsOccAt(sq, L) & 65u) != 0u) return 0.0; }",
      "      lvl = ln;",
      "      t = tn;",
      "      if (zn > pzEsLim.x || t > pzEsLim.y) return vis;",
      "      float f = zn - float(ln);",
      "      if (tMax.x < tMax.y) {",
      "         uint e = (pzEsOccAt(d.x >= 0.0 ? sq + ivec2(1, 0) : sq, ln) >> 3) & 3u;",
      "         if (pzEsEdge(e, f)) return 0.0;",
      "         sq.x += stp.x; tMax.x += inv.x;",
      "      } else {",
      "         uint e = (pzEsOccAt(d.y >= 0.0 ? sq + ivec2(0, 1) : sq, ln) >> 1) & 3u;",
      "         if (pzEsEdge(e, f)) return 0.0;",
      "         sq.y += stp.y; tMax.y += inv.y;",
      "      }",
      "      if (vis < 0.02) return 0.0;",
      "   }",
      "   return vis;",
      "}",
      "vec3 pzEsSunVisDbg(vec3 p) {", // p: absolute squares, z in levels
      "   ivec2 sq = ivec2(floor(p.xy));",
      "   int lvl = int(floor(p.z));",
      "   if (pzEsSun.w > 0.5) {",
      "      for (int L = lvl + 1; float(L) <= pzEsLim.x; L++) { if ((pzEsOccAt(sq, L) & 65u) != 0u) return vec3(0.5, 0.5, 0.5); }",
      "      return vec3(1.0, 1.0, 1.0);",
      "   }",
      "   vec2 d = pzEsSun.xy;",
      "   float slope = pzEsSun.z;",
      "   vec2 inv = 1.0 / pzEsMax2(abs(d), vec2(1e-5));",
      "   vec2 fr = p.xy - vec2(sq);",
      "   vec2 tMax = vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "   ivec2 stp = ivec2(d.x >= 0.0 ? 1 : -1, d.y >= 0.0 ? 1 : -1);",
      "   float t = 0.0, vis = 1.0;",
      "   bool jumped = false;",
      "   for (int i = 0; i < 128; i++) {",
      "      if (!jumped && p.z + t * slope >= pzEsChunkTop(sq)) {",
      "         vec2 at = p.xy + d * t;",
      "         ivec2 c0 = (sq >> 3) * 8;",
      "         vec2 tx = vec2(d.x >= 0.0 ? float(c0.x + 8) - at.x : at.x - float(c0.x), d.y >= 0.0 ? float(c0.y + 8) - at.y : at.y - float(c0.y)) * inv;",
      "         t += pzEsMaxF(0.0, min(tx.x, tx.y) - 1e-3);",
      "         jumped = true;",
      "         if (p.z + t * slope > pzEsLim.x || t > pzEsLim.y) return vec3(1.0, 0.0, 0.0);",
      "         at = p.xy + d * t;",
      "         sq = pzEsClampI(ivec2(floor(at)), c0, c0 + 7);",
      "         lvl = int(floor(p.z + t * slope));",
      "         fr = at - vec2(sq);",
      "         tMax = t + vec2(d.x >= 0.0 ? 1.0 - fr.x : fr.x, d.y >= 0.0 ? 1.0 - fr.y : fr.y) * inv;",
      "         continue;",
      "      }",
      "      jumped = false;",
      "      float tn = min(tMax.x, tMax.y);",
      "      float zn = p.z + tn * slope;",
      "      int ln = int(floor(zn));",
      "      uint o = pzEsOccAt(sq, lvl);",
      "      uint fol = (o >> 8) & 15u;",
      "      if (fol != 0u) {",
      "         vec3 m = vec3(p.xy + d * (0.5 * (t + tn)), (p.z + 0.5 * (t + tn) * slope) * PZES_LH);",
      "         float n = pzEsLeaf(vec3(mod(m.xy, 1024.0), m.z) * 1.4);",
      "         vis *= exp(-pzEsLim.z * float(fol) * pzEsMaxF(0.0, n * 1.8 - 0.55) * (tn - t) * 1.2);",
      "      }",
      "      if ((o & 64u) != 0u && p.z + t * slope - float(lvl) < pzEsB.z) return vec3(0.0, 0.0, 1.0);", // inside a roof square's fill (a pitched roof's ridge is near the level's top)
      "      for (int L = lvl + 1; L <= ln; L++) { if ((pzEsOccAt(sq, L) & 65u) != 0u) return vec3(1.0, 0.0, 1.0); }",
      "      lvl = ln;",
      "      t = tn;",
      "      if (zn > pzEsLim.x || t > pzEsLim.y) return vec3(0.0, 1.0, 0.0);",
      "      float f = zn - float(ln);",
      "      if (tMax.x < tMax.y) {",
      "         uint e = (pzEsOccAt(d.x >= 0.0 ? sq + ivec2(1, 0) : sq, ln) >> 3) & 3u;",
      "         if (pzEsEdge(e, f)) return vec3(1.0, 1.0, 0.0);",
      "         sq.x += stp.x; tMax.x += inv.x;",
      "      } else {",
      "         uint e = (pzEsOccAt(d.y >= 0.0 ? sq + ivec2(0, 1) : sq, ln) >> 1) & 3u;",
      "         if (pzEsEdge(e, f)) return vec3(1.0, 1.0, 0.0);",
      "         sq.y += stp.y; tMax.y += inv.y;",
      "      }",
      "      if (vis < 0.02) return vec3(0.0, 0.0, 0.0);",
      "   }",
      "   return vec3(0.0, 1.0, 1.0);",
      "}",
      PROBE_SAMPLERS,
      // the probe brick's cell coordinate comes interpolated from the vertices (affine in the world position: exact); clamped
      // here into the brick, then one trilinear tap (two with moving casters near)
      "float pzEsProbe() {",
      "   if (pzEsU >= 0.0) return pzEsU;", // the brick all in the sun or all in the shade (the vertices read its blocks' min / max): no tap
      "   float n = pzEsQ.z, cloud = abs(pzEsQ.w) - 2.0;", // (the cloud transmittance stored + 2, its sign the moving casters' flag)
      "   bool dyn = pzEsQ.w > 0.0;",
      "   vec3 c = min(vec3(pzEsMaxF(pzEsC.x, 0.0), pzEsMaxF(pzEsC.y, 0.0), pzEsMaxF(pzEsC.z, 0.0)), vec3(n - 1.0, n - 1.0, " + (Probes.NZ - 1) + ".0)) + 0.5;",
      "   vec3 uvw = vec3((pzEsQ.xy + c.xy) * " + (1.0 / Probes.AX) + ", c.z * " + (1.0 / Probes.NZ) + ");",
      "   return (dyn ? texture(pzEsDyn, uvw).r : texture(pzEsVol, uvw).r) * pzEsCloudAt(pzEsW + vec3(0.0, 0.0, pzEsP2.w), cloud);", // (the casters' atlas holds static x capsules; the clouds at the pixel)
      "}",
      "float pzEsFactor() {",
      "   if (pzEsA.x < 0.5) return 1.0 - pzEsA.y;",
      "   if (pzEsA.x > 2.5 && pzEsA.x < 3.5) return 1.0;", // dev (pskip): the bind and the varyings without the vertices' and the fragment's lookups
      "   float v;",
      "   if (pzEsA.x > 5.5) {",
      "      v = pzEsA.y * (pzEsA.y > 0.0 ? pzEsSelf() * pzEsCloudAt(pzEsW + vec3(0.0, 0.0, pzEsP2.w), 1.0) : 1.0);", // uniform: the entity's brick all in the sun or all in the shade (the stats read back): its value, no probe lookups; the self-shadow and the clouds in the sun
      "   } else if (pzEsA.x > 4.5) {",
      "      return 1.0;", // dev (pvs): the vertex work of probe without the pixel's taps
      "   } else if (pzEsA.x > 3.5) {",
      "      return 1.0 - pzEsA.z + (pzEsV - 1.0 + pzEsA.z) * pzEsSelf();", // probev: the shade from the vertices, the self-shadow per pixel
      "   } else if (pzEsA.x > 1.5) {",
      "      v = pzEsProbe() * pzEsCasters(pzEsW + vec3(0.0, 0.0, pzEsP2.w)) * pzEsSelf();", // (cloud included; the moving casters' capsules and the self-shadow per pixel)
      "   } else {",
      "      vec3 p = pzEsW;",
      "      p.z = pzEsMaxF(p.z + pzEsB.w, pzEsB.x + 0.02);", // feet a hair under their floor would see the floor they stand on as a ceiling
      "      v = pzEsSunVis(p) * pzEsB.y;",
      "   }",
      // the sun's share s of the daylight lit by the surface's facing (half-Lambert, mean 1 so a body's brightness holds),
      // times the light reaching it; the sky's 1 - s stays
      "   vec3 n = pzEsN;",
      "   n.z *= PZES_LH;",
      "   float h = dot(n, n) > 1e-12 ? dot(normalize(n), pzEsL.xyz) * 0.5 + 0.5 : 0.5;",
      "   float shape = 1.0 + pzEsL.w * (2.0 * h - 1.0);",
      "   return 1.0 - pzEsA.z + pzEsA.z * v * shape;",
      "}",
      "vec3 pzEsAmbient(vec3 amb) {",
      "   float k = pzEsFactor();",
      "   if (pzEsA.w > 8.5) return pzEsSunVisDbg(pzEsDbg.xyz);", // dev: why the trace from the object's position ended: white overhead lit, grey overhead blocked, red exit after a chunk jump, green exit in the walk, blue roof, magenta floor, yellow edge, black foliage, cyan out of steps
      "   if (pzEsA.w > 6.5) return pzEsA.w > 7.5 ? vec3(pzEsLim.x / 10.0, pzEsLim.y / 64.0, -pzEsLim.w / 8.0) : vec3(abs(pzEsSun.x), abs(pzEsSun.y) * 5.0, pzEsSun.z * 5.0);", // dev: the frame uniforms
      "   if (pzEsA.w > 5.5) { uint o = pzEsOccAt(ivec2(floor(pzEsDbg.xy)), int(pzEsDbg.z)); uint tp = texelFetch(pzEsTop, ivec2((int(pzEsDbg.x) >> 3) & 31, (int(pzEsDbg.y) >> 3) & 31), 0).r; return vec3(o == 0u ? 1.0 : 0.0, o != 0u ? 1.0 : 0.0, tp != 0u ? 1.0 : 0.0); }", // dev: one square's occupancy (green: set, red: 0) and its chunk top (blue: set)
      "   if (pzEsA.w > 4.5) { float v = pzEsSunVis(pzEsDbg.xyz); float tp = pzEsChunkTop(ivec2(floor(pzEsDbg.xy))); return vec3(1.0 - v, v, tp > pzEsDbg.z ? 1.0 : 0.0); }", // dev: the trace from the object's position (blue: its chunk's top is above it)
      "   if (pzEsA.w > 3.5) { float d = pzEsChunkTop(ivec2(floor(pzEsW.xy))) - pzEsW.z; return vec3(d > 0.0 ? d * 0.5 : 0.0, d <= 0.0 ? 1.0 : 0.0, fract(pzEsW.z)); }", // dev: the chunk's top above the pixel (red), none (green)
      "   if (pzEsA.w > 2.5) { vec3 p = pzEsW; p.z = pzEsMaxF(p.z, pzEsB.x + 0.02); float v = pzEsSunVis(p); return vec3(1.0 - v, v, 0.0) * 2.0; }", // dev: the raw visibility
      "   if (pzEsA.w > 1.5) return fract(pzEsW);", // dev: the world position's grid
      "   if (pzEsA.w > 0.5) return vec3(1.0 - k, k, 0.0) * 2.0;", // dev: the factor (green sun, red shade)
      "   return amb * k;",
      "}");


   // ------------------------------------------------------------------------------------------------ probe volumes

   /**
    * entityShadowMethod=probe: every model entity outdoors near the camera gets a world-aligned brick of sun visibility
    * probes (a person 8 x 8 x 8 over 1.8 x 1.8 squares and 1.4 levels, a vehicle 16 x 16 x 8 over 6.4 squares), each
    * probe the same occupancy trace the per-pixel method runs; its pixels then take one trilinear tap. A brick is computed
    * again only when its snapped origin, the sun's step or the occupancy changed (an idle crowd costs nothing), all of a
    * frame's in one compute dispatch before the moving objects draw. Light probe proxy volumes (Unity) / the volumetric
    * lightmap's per-object sampling (Unreal), with the static world traced on the GPU.
    */
   static final class Probes {
      static final int N = 8, NZ = 8; // a person's brick: N x N x NZ probes; a vehicle: 2N x 2N x NZ (four jobs)
      static final int AX = 256, AY = 256; // atlas texels (32 x 32 slots of N x N; the last 8 rows hold 2 x 2 slot blocks)
      static final int SLOTS_X = AX / N, SINGLE_ROWS = 24, BLOCK_ROWS = (AY / N - SINGLE_ROWS) / 2;
      // a person's static brick covers the square it stands in plus 3/8 of a square round it (8 probes 1/4 square apart),
      // a vehicle's the 2 x 2 squares it is in plus 3 squares round (16 probes 1/2 square apart): entities in one square share
      // a brick, and a walking one needs a new trace only when it changes square
      static final float SP_CHAR = 0.25F, SPZ_CHAR = 0.18F, SP_VEH = 0.5F, SPZ_VEH = 0.15F;
      static final float MARGIN_CHAR = 0.375F, MARGIN_VEH = 3F, STRIDE_VEH = 2F;

      /** A static brick of the world (shared by the entities in its square), cached in the atlas while it is used. */
      private static final class SBrick {
         int slot;
         boolean vehicle;
         long key = Long.MIN_VALUE; // the world (sun step, occupancy) it was computed under
         long seen, computed;
      }

      /** A model entity: its slot in the casters' atlas (when moving casters reach it) and since when it has casters near. */
      private static final class Entry {
         int dynSlot = -1;
         boolean vehicle;
         long seen, dynSince = -1L;
      }

      private static final java.util.HashMap<Long, SBrick> STATIC = new java.util.HashMap<>();
      private static final java.util.IdentityHashMap<Object, Entry> ENTRIES = new java.util.IdentityHashMap<>();
      private static final java.util.ArrayDeque<Integer> FREE_SINGLE = new java.util.ArrayDeque<>(), FREE_BLOCK = new java.util.ArrayDeque<>();
      private static final java.util.ArrayDeque<Integer> DYN_FREE_SINGLE = new java.util.ArrayDeque<>(), DYN_FREE_BLOCK = new java.util.ArrayDeque<>();
      private static long frame;
      static long gathered, jobsQueued, cacheHits, staticBricks;

      static {
         for (int i = 0; i < SLOTS_X * SINGLE_ROWS; i++) {
            FREE_SINGLE.add(i);
            DYN_FREE_SINGLE.add(i);
         }
         for (int i = 0; i < (SLOTS_X / 2) * BLOCK_ROWS; i++) {
            FREE_BLOCK.add(i);
            DYN_FREE_BLOCK.add(i);
         }
      }

      static int slotX(int slot, boolean vehicle) {
         return vehicle ? slot % (SLOTS_X / 2) * 2 * N : slot % SLOTS_X * N;
      }

      static int slotY(int slot, boolean vehicle) {
         return vehicle ? (SINGLE_ROWS + slot / (SLOTS_X / 2) * 2) * N : slot / SLOTS_X * N;
      }

      /**
       * Game thread: the bricks of this frame's model entities outdoors near the camera (each the shared static brick of its
       * square), and the jobs of the static bricks not yet computed under this sun step and occupancy.
       */
      static long loopNs, castNs;

      /** Worker, first every frame it runs: the frame number and the candidates (the objects drawn as models lately, the players). */
      static void candidates(FrameState f) {
         frame++;
         f.frame = frame;
         // the objects drawn as models lately (bind() queues them; a horde's atlas zombies never are) and the players: not the
         // cell's whole object list (thousands in a horde: ~100 us a frame to walk)
         for (IsoMovingObject o; (o = DRAWN.poll()) != null; ) {
            MODELS.put(o, frame);
         }
         for (int pi = 0; pi < zombie.characters.IsoPlayer.numPlayers; pi++) {
            if (zombie.characters.IsoPlayer.players[pi] != null) {
               MODELS.put(zombie.characters.IsoPlayer.players[pi], frame);
            }
         }
         CANDIDATES.clear();
         for (java.util.Iterator<java.util.Map.Entry<IsoMovingObject, Long>> it = MODELS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<IsoMovingObject, Long> me = it.next();
            if (frame - me.getValue() > 8L) {
               it.remove();
            } else {
               CANDIDATES.add(me.getKey());
            }
         }
      }

      static void gather(FrameState f, int playerIndex) {
         long tg = System.nanoTime();
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
         if (cell == null) {
            return;
         }
         float cx = zombie.iso.IsoCamera.frameState.camCharacterX, cy = zombie.iso.IsoCamera.frameState.camCharacterY;
         float r = 24F + 40F * Math.max(0.25F, zombie.iso.IsoCamera.frameState.zoom);
         long world = SunShadow.stepSerial() * 1000003L + GodRays.buildSerial * 7919L + Config.ENTITY_SHADOW_ROOF_PCT;
         for (IsoMovingObject o : CANDIDATES) {
            boolean veh = o instanceof zombie.vehicles.BaseVehicle;
            if (!veh && !(o instanceof zombie.characters.IsoGameCharacter)) {
               continue;
            }
            float x = o.getX(), y = o.getY(), z = o.getZ();
            if (Math.abs(x - cx) > r || Math.abs(y - cy) > r) {
               continue;
            }
            if (!veh && o.getAlpha(playerIndex) < 0.01F) {
               continue; // not drawn (out of sight)
            }
            IsoGridSquare sq = o.getCurrentSquare();
            if (sq == null || !sq.isOutside() || f.rows >= MAX_ROWS) {
               continue;
            }
            // the square (2 x 2 squares for a vehicle) and the level: the static brick
            float stride = veh ? STRIDE_VEH : 1F, margin = veh ? MARGIN_VEH : MARGIN_CHAR;
            int bx = (int)Math.floor(x / stride), by = (int)Math.floor(y / stride), bz = (int)Math.floor(z + 0.01F);
            long bkey = (((long)bx & 0xFFFFFL) << 36 | ((long)by & 0xFFFFFL) << 16 | (bz + 64) & 0xFFFFL) << 1 | (veh ? 1L : 0L);
            SBrick b = STATIC.get(bkey);
            if (b == null) {
               Integer slot = veh ? FREE_BLOCK.poll() : FREE_SINGLE.poll();
               if (slot == null) {
                  evictStatic(veh);
                  slot = veh ? FREE_BLOCK.poll() : FREE_SINGLE.poll();
                  if (slot == null) {
                     continue; // atlas full of bricks in use: the constant factor
                  }
               }
               b = new SBrick();
               b.slot = slot;
               b.vehicle = veh;
               STATIC.put(bkey, b);
               staticBricks++;
            }
            b.seen = frame;
            Entry e = ENTRIES.get(o);
            if (e == null) {
               e = new Entry();
               e.vehicle = veh;
               ENTRIES.put(o, e);
            }
            e.seen = frame;
            int n = veh ? 2 * N : N;
            float sp = veh ? SP_VEH : SP_CHAR, spz = veh ? SPZ_VEH : SPZ_CHAR;
            float ox = bx * stride - margin, oy = by * stride - margin, oz = bz - 0.1F;
            int sx = slotX(b.slot, veh), sy = slotY(b.slot, veh);
            float lift = veh || ((zombie.characters.IsoGameCharacter)o).isSeatedInVehicle() ? 0F : 0.29393876F; // DoPushIsoStuff's model offset (EntityShadow.bind)
            if (b.key != world) {
               b.key = world;
               b.computed = frame;
               queueStatic(f, ox, oy, oz, sp, spz, sx, sy, n);
            } else {
               cacheHits++;
            }
            // [0..2] origin, [3] cells a side, [4..6] 1 / spacing, [7, 8] the atlas texel drawn from, [9] moving casters, [10]
            // table row, [11] cloud, [12] lift, [13] age of what is drawn (frames), [14, 15] the static brick's texel
            // [16] moving casters drawn in the shaders, [17..24] their indices
            // [25] the entity's sun tile (-1 none), [26] its half size, [27..29] its centre (world squares, z metric)
            float[] brick = new float[] {ox, oy, oz, n, 1F / sp, 1F / sp, 1F / spz, sx, sy, 0F, f.rows++, CloudShadow.transmittanceAt(x, y, z), lift, frame - b.computed, sx, sy,
               0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, 0F, -1F, 0F, 0F, 0F, 0F};
            if (f.selfOn && CapsuleShadow.receiverTile(o, TILE)) {
               brick[25] = TILE[0];
               brick[26] = TILE[1];
               brick[27] = x + TILE[2];
               brick[28] = y + TILE[3];
               brick[29] = z * LEVEL + TILE[4];
            }
            f.bricks.put(o, brick);
            BRICKS.add(brick);
            gathered++;
            if (f.castersOn) {
               RECEIVERS.add(o);
               RECEIVER_BRICKS.add(brick);
            }
         }
         // level of detail: the moving casters and the self-shadow for the entityShadowDetail entities nearest the camera
         // (the rest keep the static shade and the facing: in a crowd the far ones are small)
         int detail = Math.max(0, Config.ENTITY_SHADOW_DETAIL);
         if (BRICKS.size() > detail) {
            DIST.clear();
            for (int i = 0; i < BRICKS.size(); i++) {
               float[] b = BRICKS.get(i);
               float dx = b[0] + b[3] * 0.5F / b[4] - cx, dy = b[1] + b[3] * 0.5F / b[5] - cy;
               DIST.add(new float[] {dx * dx + dy * dy, i});
            }
            DIST.sort((a, b) -> Float.compare(a[0], b[0]));
            for (int i = detail; i < DIST.size(); i++) {
               float[] b = BRICKS.get((int)DIST.get(i)[1]);
               b[25] = -1F; // no self-shadow
               int at = RECEIVER_BRICKS.indexOf(b);
               if (at >= 0) {
                  RECEIVER_BRICKS.remove(at);
                  RECEIVERS.remove(at);
               }
            }
         }
         long tc = System.nanoTime();
         loopNs += tc - tg;
         if (f.castersOn && !RECEIVERS.isEmpty()) {
            dynamicJobs(f, cell, cx, cy, r, playerIndex);
         }
         castNs += System.nanoTime() - tc;
         RECEIVERS.clear();
         RECEIVER_BRICKS.clear();
         BRICKS.clear();
         if ((frame & 63) == 0) {
            // entities not drawn for a while give their casters' slot back
            java.util.Iterator<java.util.Map.Entry<Object, Entry>> it = ENTRIES.entrySet().iterator();
            while (it.hasNext()) {
               Entry e = it.next().getValue();
               if (frame - e.seen > 120) {
                  if (e.dynSlot >= 0) {
                     (e.vehicle ? DYN_FREE_BLOCK : DYN_FREE_SINGLE).add(e.dynSlot);
                  }
                  it.remove();
               }
            }
         }
      }

      private static void queueStatic(FrameState f, float ox, float oy, float oz, float sp, float spz, int sx, int sy, int n) {
         for (int by = 0; by < n / N; by++) {
            for (int bx = 0; bx < n / N; bx++) {
               if (f.jobs.length < (f.jobCount + 1) * 8) {
                  f.jobs = java.util.Arrays.copyOf(f.jobs, Math.max(64, f.jobs.length * 2));
               }
               int j = f.jobCount++ * 8;
               f.jobs[j] = ox + bx * N * sp;
               f.jobs[j + 1] = oy + by * N * sp;
               f.jobs[j + 2] = oz;
               f.jobs[j + 3] = sx + bx * N;
               f.jobs[j + 4] = sp;
               f.jobs[j + 5] = sp;
               f.jobs[j + 6] = spz;
               f.jobs[j + 7] = sy + by * N;
               jobsQueued++;
            }
         }
      }

      /** The atlas is full: the static bricks not used for 30 frames (the oldest first) give their slots back. */
      private static void evictStatic(boolean vehicle) {
         java.util.Iterator<java.util.Map.Entry<Long, SBrick>> it = STATIC.entrySet().iterator();
         while (it.hasNext()) {
            SBrick b = it.next().getValue();
            if (b.vehicle == vehicle && frame - b.seen > 30) {
               (vehicle ? FREE_BLOCK : FREE_SINGLE).add(b.slot);
               it.remove();
            }
         }
      }

      static final int MAX_ROWS = 512; // (3 vec4 a row: 24 KB, under every driver's 64 KB uniform block)
      private static final java.util.ArrayList<float[]> BRICKS = new java.util.ArrayList<>();
      /** Render thread -> the gather: objects drawn as models (each once a frame, bind()). */
      static final java.util.concurrent.ConcurrentLinkedQueue<IsoMovingObject> DRAWN = new java.util.concurrent.ConcurrentLinkedQueue<>();
      private static final java.util.IdentityHashMap<IsoMovingObject, Long> MODELS = new java.util.IdentityHashMap<>();
      private static final java.util.ArrayList<IsoMovingObject> CANDIDATES = new java.util.ArrayList<>();
      private static final java.util.ArrayList<float[]> DIST = new java.util.ArrayList<>();
      private static final java.util.ArrayList<Object> RECEIVERS = new java.util.ArrayList<>();
      private static final java.util.ArrayList<float[]> RECEIVER_BRICKS = new java.util.ArrayList<>();
      private static final java.util.ArrayList<Object> CASTER_OWNERS = new java.util.ArrayList<>();
      private static final org.joml.Vector3f VEC = new org.joml.Vector3f();
      private static final java.util.Set<IsoMovingObject> CASTER_SET = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      private static final java.util.ArrayList<IsoMovingObject> CASTER_LIST = new java.util.ArrayList<>();
      private static final float[] LRAY = new float[3];
      static long dynJobsQueued, castersGathered;
      static final int MAX_CASTERS_A_BRICK = 4; // (nearest first)
      static final int DYN_JOB = 20; // floats a caster job: origin + its texel x, spacing + texel y, 8 casters, the static brick's texel

      /**
       * Game thread: the moving casters near the camera as capsules (a person one upright capsule, an animal a lower one,
       * a vehicle its two body capsules and the cabin, as CapsuleShadow builds them), and for every receiver brick the
       * casters whose shadow (the capsule swept away from the light by its height) reaches it, itself excepted.
       */
      private static void dynamicJobs(FrameState f, zombie.iso.IsoCell cell, float cx, float cy, float r, int playerIndex) {
         CASTER_OWNERS.clear();
         // only casters whose shadow can reach a receiver: the receivers' box grown by the shadow's reach (a horde's thousands
         // of zombies otherwise all became casters: 173 us of the worker a frame in Louisville)
         float rx0 = Float.MAX_VALUE, ry0 = Float.MAX_VALUE, rx1 = -Float.MAX_VALUE, ry1 = -Float.MAX_VALUE;
         for (Object ro : RECEIVERS) {
            zombie.iso.IsoMovingObject m = (zombie.iso.IsoMovingObject)ro;
            rx0 = Math.min(rx0, m.getX());
            ry0 = Math.min(ry0, m.getY());
            rx1 = Math.max(rx1, m.getX());
            ry1 = Math.max(ry1, m.getY());
         }
         rx0 -= 11F;
         ry0 -= 11F;
         rx1 += 11F;
         ry1 += 11F;
         float lz = Math.max(0.12F, f.lz), lh = (float)Math.sqrt(f.lx * f.lx + f.ly * f.ly);
         // the shadow's reach per unit of caster height, along the ground away from the light (capped at 8 squares)
         float run = Math.min(8F / 2.7F, lh / lz);
         float rx = lh < 1e-4F ? 0F : -f.lx / lh * run, ry = lh < 1e-4F ? 0F : -f.ly / lh * run;
         // the casters: the moving objects on the squares along each receiver's way to the light (8 squares, one to each
         // side), each once
         CASTER_SET.clear();
         CASTER_LIST.clear();
         float hx = lh < 1e-4F ? 0F : f.lx / lh, hy = lh < 1e-4F ? 0F : f.ly / lh;
         for (Object ro : RECEIVERS) {
            zombie.iso.IsoMovingObject m = (zombie.iso.IsoMovingObject)ro;
            int rz = (int)Math.floor(m.getZ());
            int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
            for (float t = 0F; t <= 8F; t += 0.5F) {
               int qx = (int)Math.floor(m.getX() + hx * t), qy = (int)Math.floor(m.getY() + hy * t);
               if (qx == lastX && qy == lastY) {
                  continue;
               }
               lastX = qx;
               lastY = qy;
               for (int ox = -1; ox <= 1; ox++) {
                  for (int oy = -1; oy <= 1; oy++) {
                     zombie.iso.IsoGridSquare q = cell.getGridSquare(qx + ox, qy + oy, rz);
                     if (q == null) {
                        continue;
                     }
                     java.util.ArrayList<IsoMovingObject> mo = q.getMovingObjects();
                     for (int k = 0; k < mo.size(); k++) {
                        IsoMovingObject c = mo.get(k);
                        if (c != null && CASTER_SET.add(c)) {
                           CASTER_LIST.add(c);
                        }
                     }
                  }
               }
            }
         }
         for (zombie.vehicles.BaseVehicle v : cell.getVehicles()) { // (a car spans squares: the vehicles' short list, by the receivers' box)
            if (v != null && v.getX() >= rx0 && v.getX() <= rx1 && v.getY() >= ry0 && v.getY() <= ry1 && CASTER_SET.add(v)) {
               CASTER_LIST.add(v);
            }
         }
         for (IsoMovingObject o : CASTER_LIST) {
            int k = capsules(o, CAPS7, true);
            for (int q = 0; q < k; q++) {
               int c = q * 7;
               addCaster(f, o, CAPS7[c], CAPS7[c + 1], CAPS7[c + 2], CAPS7[c + 3], CAPS7[c + 4], CAPS7[c + 5], CAPS7[c + 6]);
            }
         }
         if (f.casterCount == 0) {
            return;
         }
         castersGathered += f.casterCount;
         // every receiver brick against every caster's shadow box (a person's shadow 2.7 squares tall at most)
         float[] lzRay = LRAY;
         lzRay[0] = f.lx;
         lzRay[1] = f.ly;
         lzRay[2] = f.lz;
         if (recvX.length < RECEIVERS.size()) {
            recvX = new float[RECEIVERS.size() * 2];
            recvY = new float[RECEIVERS.size() * 2];
         }
         for (int i = 0; i < RECEIVERS.size(); i++) {
            zombie.iso.IsoMovingObject ro = (zombie.iso.IsoMovingObject)RECEIVERS.get(i);
            recvX[i] = ro.getX();
            recvY[i] = ro.getY();
         }
         for (int i = 0; i < RECEIVERS.size(); i++) {
            Object recv = RECEIVERS.get(i);
            float[] b = RECEIVER_BRICKS.get(i);
            float n = b[3], bx0 = b[0], by0 = b[1], bx1 = bx0 + n / b[4], by1 = by0 + n / b[5];
            float bz0 = b[2] * LEVEL, bz1 = (b[2] + NZ / b[6]) * LEVEL;
            int[] list = LIST;
            int count = 0;
            for (int k = 0; k < f.casterCount && count < MAX_CASTERS_A_BRICK; k++) {
               if (CASTER_OWNERS.get(k) == recv) {
                  continue; // its own body: not at this resolution
               }
               int c = k * 8;
               float x0 = Math.min(f.casters[c], f.casters[c + 4]) - f.casters[c + 3], x1 = Math.max(f.casters[c], f.casters[c + 4]) + f.casters[c + 3];
               float y0 = Math.min(f.casters[c + 1], f.casters[c + 5]) - f.casters[c + 3], y1 = Math.max(f.casters[c + 1], f.casters[c + 5]) + f.casters[c + 3];
               float zTop = Math.max(f.casters[c + 2], f.casters[c + 6]) + f.casters[c + 3];
               float hgt = Math.max(0F, zTop - bz0);
               // the shadow box: the capsule's box swept by -L up to the height above the brick's floor
               float sx0 = Math.min(x0, x0 + rx * hgt), sx1 = Math.max(x1, x1 + rx * hgt), sy0 = Math.min(y0, y0 + ry * hgt), sy1 = Math.max(y1, y1 + ry * hgt);
               if (sx1 < bx0 || sx0 > bx1 || sy1 < by0 || sy0 > by1 || zTop < bz0 || Math.min(f.casters[c + 2], f.casters[c + 6]) - f.casters[c + 3] > bz1) {
                  continue;
               }
               // the exact test: the light's ray from the receiver's axis (feet, middle, head) passes the capsule within its
               // radius + the receiver's half width + the penumbra at that distance
               float near = rayMiss(f, c, recvX[i], recvY[i], bz0, bz1, lzRay);
               if (near > 0F) {
                  continue;
               }
               if (count < list.length) {
                  list[count] = k;
                  score[count++] = near;
               } else {
                  int worst = 0;
                  for (int q = 1; q < count; q++) {
                     if (score[q] > score[worst]) worst = q;
                  }
                  if (near < score[worst]) {
                     list[worst] = k;
                     score[worst] = near;
                  }
               }
            }
            if (!f.dynCompute) {
               // the shaders take the list (bind() hands the capsules over with the draw)
               b[16] = Math.min(count, MAX_DRAW_CASTERS);
               for (int q = 0; q < (int)b[16]; q++) {
                  b[17 + q] = list[q];
               }
               continue;
            }
            Entry e = ENTRIES.get(recv);
            if (count == 0 || e == null) {
               if (e != null) {
                  e.dynSince = -1L;
               }
               continue;
            }
            if (e.dynSlot < 0) {
               Integer slot = e.vehicle ? DYN_FREE_BLOCK.poll() : DYN_FREE_SINGLE.poll();
               if (slot == null) {
                  continue; // the casters' atlas is full: the static brick alone
               }
               e.dynSlot = slot;
            }
            if (e.dynSince < 0L) {
               e.dynSince = frame;
            }
            // drawn from the casters' atlas (static x capsules), its age the frames since the casters came near
            int dx = slotX(e.dynSlot, e.vehicle), dy = slotY(e.dynSlot, e.vehicle);
            b[9] = 1F;
            b[7] = dx;
            b[8] = dy;
            b[13] = frame - e.dynSince;
            float sp = 1F / b[4], spz = 1F / b[6];
            int blocks = (int)n / N;
            for (int by = 0; by < blocks; by++) {
               for (int bx = 0; bx < blocks; bx++) {
                  if (f.dynJobs.length < (f.dynJobCount + 1) * DYN_JOB) {
                     f.dynJobs = java.util.Arrays.copyOf(f.dynJobs, Math.max(256, f.dynJobs.length * 2));
                  }
                  int j = f.dynJobCount++ * DYN_JOB;
                  f.dynJobs[j] = b[0] + bx * N * sp;
                  f.dynJobs[j + 1] = b[1] + by * N * sp;
                  f.dynJobs[j + 2] = b[2];
                  f.dynJobs[j + 3] = dx + bx * N;
                  f.dynJobs[j + 4] = sp;
                  f.dynJobs[j + 5] = sp;
                  f.dynJobs[j + 6] = spz;
                  f.dynJobs[j + 7] = dy + by * N;
                  for (int q = 0; q < 8; q++) {
                     f.dynJobs[j + 8 + q] = q < count ? list[q] : -1F;
                  }
                  f.dynJobs[j + 16] = b[14] + bx * N; // the static brick's texel (its values times the capsules)
                  f.dynJobs[j + 17] = b[15] + by * N;
                  f.dynJobs[j + 18] = 0F;
                  f.dynJobs[j + 19] = 0F;
                  dynJobsQueued++;
               }
            }
         }
      }

      private static final float[] CAPS7 = new float[7 * 3];

      /**
       * Worker: an object's capsules as casters (a person one upright capsule, an animal a lower one, a vehicle its two body
       * capsules and the cabin, as CapsuleShadow builds them): 7 floats each (a xyz, b xyz, r; world squares, z metric) into
       * out, their count (0: none, a seated or dead one, indoors when outsideOnly).
       */
      static int capsules(IsoMovingObject o, float[] out, boolean outsideOnly) {
         boolean veh = o instanceof zombie.vehicles.BaseVehicle;
         zombie.characters.IsoGameCharacter chr = o instanceof zombie.characters.IsoGameCharacter c ? c : null;
         if (!veh && chr == null) {
            return 0;
         }
         float x = o.getX(), y = o.getY();
         if (chr != null && (chr.isSeatedInVehicle() || chr.isDead() && !(chr instanceof zombie.characters.IsoPlayer))) {
            return 0;
         }
         IsoGridSquare sq = o.getCurrentSquare();
         if (sq == null || outsideOnly && !sq.isOutside()) {
            return 0;
         }
         float zm = o.getZ() * LEVEL;
         if (veh) {
            zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle)o;
            if (v.getScript() == null) {
               return 0;
            }
            org.joml.Vector3f e = v.getScript().getExtents();
            org.joml.Vector2f se = v.getScript().getShadowExtents(), so = v.getScript().getShadowOffset();
            float w = se.x, len = se.y, h = e.y;
            if (w <= 0F || len <= 0F || h <= 0F) {
               return 0;
            }
            float rb = Math.min(w * 0.25F, h * 0.3F), rc = Math.min(w * 0.4F, h * 0.28F);
            float[][] local = {
               {-w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, -w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
               {w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
               {0F, h - rc, so.y - len * 0.22F, 0F, h - rc, so.y + len * 0.12F, rc}};
            int n = 0;
            for (float[] c : local) {
               v.getWorldPos(so.x + c[0], 0F, c[2], VEC);
               out[n] = VEC.x;
               out[n + 1] = VEC.y;
               out[n + 2] = zm + c[1];
               v.getWorldPos(so.x + c[3], 0F, c[5], VEC);
               out[n + 3] = VEC.x;
               out[n + 4] = VEC.y;
               out[n + 5] = zm + c[4];
               out[n + 6] = c[6];
               n += 7;
            }
            return 3;
         } else {
            boolean animal = chr instanceof zombie.characters.animals.IsoAnimal;
            float rad = animal ? 0.32F : 0.24F, top = animal ? 1.3F : 2.45F;
            out[0] = x;
            out[1] = y;
            out[2] = zm + rad;
            out[3] = x;
            out[4] = y;
            out[5] = zm + top - rad;
            out[6] = rad;
            return 1;
         }
      }

      private static final int[] LIST = new int[MAX_CASTERS_A_BRICK];
      private static final float[] score = new float[MAX_CASTERS_A_BRICK];
      private static float[] recvX = new float[64], recvY = new float[64];
      private static final float RECV_HALF_WIDTH = 0.4F;

      /**
       * How far the light's rays from a receiver's axis (x, y; z from z0 to z1 metric, three heights) miss the caster c's
       * capsule beyond its radius, the receiver's half width and the penumbra at that distance: <= 0 when it can shade it
       * (the more negative, the deeper).
       */
      private static float rayMiss(FrameState f, int c, float x, float y, float z0, float z1, float[] L) {
         float ax = f.casters[c], ay = f.casters[c + 1], az = f.casters[c + 2], r = f.casters[c + 3];
         float bx = f.casters[c + 4], by = f.casters[c + 5], bz = f.casters[c + 6];
         float best = Float.MAX_VALUE;
         for (int h = 0; h < 3; h++) {
            float pz = z0 + (z1 - z0) * (0.1F + 0.4F * h);
            // closest points between the ray p + t L (t >= 0, |L| = 1) and the segment a + u U (u in 0..1, U = b - a)
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float w0x = x - ax, w0y = y - ay, w0z = pz - az;
            float B = L[0] * ux + L[1] * uy + L[2] * uz, C = ux * ux + uy * uy + uz * uz;
            float D = L[0] * w0x + L[1] * w0y + L[2] * w0z, E = ux * w0x + uy * w0y + uz * w0z;
            float den = C - B * B;
            float u = den > 1e-6F ? (E - B * D) / den : 0F;
            u = Math.max(0F, Math.min(1F, u));
            float t = Math.max(0F, B * u - D);
            u = C > 1e-6F ? Math.max(0F, Math.min(1F, (B * t + E) / C)) : 0F;
            float qx = ax + ux * u - (x + L[0] * t), qy = ay + uy * u - (y + L[1] * t), qz = az + uz * u - (pz + L[2] * t);
            float dist = (float)Math.sqrt(qx * qx + qy * qy + qz * qz) - r - RECV_HALF_WIDTH - t * f.penTan;
            best = Math.min(best, dist);
         }
         return best;
      }
      private static final float[] TILE = new float[5];

      private static void addCaster(FrameState f, Object owner, float ax, float ay, float az, float bx, float by, float bz, float r) {
         if (f.casters.length < (f.casterCount + 1) * 8) {
            f.casters = java.util.Arrays.copyOf(f.casters, Math.max(256, f.casters.length * 2));
         }
         int c = f.casterCount++ * 8;
         f.casters[c] = ax;
         f.casters[c + 1] = ay;
         f.casters[c + 2] = az;
         f.casters[c + 3] = r;
         f.casters[c + 4] = bx;
         f.casters[c + 5] = by;
         f.casters[c + 6] = bz;
         f.casters[c + 7] = 0F;
         CASTER_OWNERS.add(owner);
      }

      private static int dynTex, dynProg, dynJobBuf, capBuf;
      private static int uDynL = -1;
      private static boolean dynFailed;
      private static final java.nio.FloatBuffer DYN = org.lwjgl.BufferUtils.createFloatBuffer(DYN_JOB * 2048), CAPS = org.lwjgl.BufferUtils.createFloatBuffer(8 * 2048);
      static long dynDispatches, dynJobsRun;

      static int dynTex() {
         return dynTex;
      }

      /** Render thread, after compute(): the moving casters' capsule shade of this frame's bricks that have casters near. */
      static void computeDynamic(FrameState f) {
         if (f.dynJobCount == 0 || volTex == 0 || !ensureDynamic()) {
            return;
         }
         int count = Math.min(f.dynJobCount, DYN.capacity() / DYN_JOB), caps = Math.min(f.casterCount, CAPS.capacity() / 8);
         DYN.clear();
         DYN.put(f.dynJobs, 0, count * DYN_JOB).flip();
         CAPS.clear();
         CAPS.put(f.casters, 0, caps * 8).flip();
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, dynJobBuf);
         org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, DYN, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, capBuf);
         org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, CAPS, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
         org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0, dynJobBuf);
         org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 1, capBuf);
         org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, STATS_BINDING, statBuf);
         GL20.glUseProgram(dynProg);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, volTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         // the capsules' penumbra: the sun's angular radius (sunShadowSoftnessPct), at least 2 degrees at this resolution
         float pen = (float)Math.tan(Math.toRadians(Math.max(2.0, 3.0 * Math.max(1, Config.SUN_SHADOW_SOFTNESS_PCT) / 100.0)));
         GL20.glUniform4f(uDynL, f.lx, f.ly, f.lz, 1F / pen);
         org.lwjgl.opengl.GL42.glBindImageTexture(0, dynTex, 0, true, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_R8);
         org.lwjgl.opengl.GL43.glDispatchCompute(count, 1, 1);
         org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT | org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT);
         org.lwjgl.opengl.GL42.glBindImageTexture(0, 0, 0, true, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_R8);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         dynDispatches++;
         dynJobsRun += count;
      }

      private static boolean ensureDynamic() {
         if (dynTex != 0) {
            return true;
         }
         if (dynFailed) {
            return false;
         }
         String src = String.join("\n",
            "#version 430",
            "layout(local_size_x = " + N + ", local_size_y = " + N + ", local_size_z = " + NZ + ") in;",
            "layout(r8, binding = 0) uniform writeonly image3D uOut;",
            "layout(std430, binding = 0) readonly buffer Jobs { vec4 job[]; };",
            "layout(std430, binding = 1) readonly buffer Caps { vec4 cap[]; };",
            "uniform vec4 uL;", // the direction to the light (world, metric), w: 1 / tan of the penumbra angle
            "layout(binding = " + VOL_UNIT + ") uniform sampler3D uStatic;",
            "layout(std430, binding = " + STATS_BINDING + ") buffer PzEsStatsW { vec4 stat[]; };",
            "shared uint sMin, sMax;",
            CAPSULE_GLSL,
            "void main() {",
            "   uint j = gl_WorkGroupID.x;",
            "   vec4 a = job[j * 5u], b = job[j * 5u + 1u], c0 = job[j * 5u + 2u], c1 = job[j * 5u + 3u], e = job[j * 5u + 4u];",
            "   ivec3 l = ivec3(gl_LocalInvocationID);",
            "   if (gl_LocalInvocationIndex == 0u) { sMin = 255u; sMax = 0u; }",
            "   barrier();",
            "   vec3 p = a.xyz + vec3(l) * b.xyz;",
            "   p.z = max(p.z, floor(a.z + 0.12 + b.z) + 0.02) * 2.4494897;", // (from above the floor, as the static probes) levels -> metric, as the capsules
            // the static visibility times the capsules: one atlas and one tap for a brick with casters near
            "   float v = texelFetch(uStatic, ivec3(int(e.x) + l.x, int(e.y) + l.y, l.z), 0).r;",
            "   for (int i = 0; i < 8; i++) {",
            "      float k = i < 4 ? c0[i] : c1[i - 4];",
            "      if (k < 0.0) break;",
            "      vec4 ca = cap[int(k) * 2], cb = cap[int(k) * 2 + 1];",
            "      v *= capShadow(p, uL.xyz, ca.xyz, cb.xyz, ca.w, uL.w, 1e4);",
            "   }",
            "   imageStore(uOut, ivec3(int(a.w) + l.x, int(b.w) + l.y, l.z), vec4(v));",
            "   uint q = uint(v * 255.0 + 0.5);",
            "   atomicMin(sMin, q);",
            "   atomicMax(sMax, q);",
            "   barrier();",
            "   if (gl_LocalInvocationIndex == 0u) stat[" + STAT_DYN + " + (int(b.w) >> 3) * " + (Probes.AX / Probes.N) + " + (int(a.w) >> 3)] = vec4(float(sMin), float(sMax), 0.0, 0.0) / 255.0;",
            "}");
         dynProg = GodRays.Gl.computeProgram(src, "entity shadow casters");
         if (dynProg == 0) {
            dynFailed = true;
            Log.warn("entity shadows: the caster compute program failed; no moving casters on entities");
            return false;
         }
         uDynL = GL20.glGetUniformLocation(dynProg, "uL");
         dynJobBuf = org.lwjgl.opengl.GL15.glGenBuffers();
         capBuf = org.lwjgl.opengl.GL15.glGenBuffers();
         dynTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, dynTex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         org.lwjgl.opengl.GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, org.lwjgl.opengl.GL30.GL_R8, AX, AY, NZ);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
         Log.info("entity shadows: caster atlas ready, compute program " + dynProg);
         return true;
      }

      /** Quilez, capsule soft shadow (as CapsuleShadow's): the ray's closest approach to the segment over the distance travelled. */
      static final String CAPSULE_GLSL = String.join("\n",
         "float capShadow(vec3 ro, vec3 rd, vec3 a, vec3 b, float r, float k, float tmax) {",
         "   vec3 ba = b - a;",
         "   vec3 oa = ro - a;",
         "   float oad = dot(oa, rd);",
         "   float dba = dot(rd, ba);",
         "   float baba = dot(ba, ba);",
         "   float oaba = dot(oa, ba);",
         "   vec2 th = vec2(-oad * baba + dba * oaba, oaba - oad * dba) / max(baba - dba * dba, 1e-6);",
         "   th.x = clamp(th.x, 0.0001, tmax);",
         "   th.y = clamp(th.y, 0.0, 1.0);",
         "   vec3 p = a + ba * th.y;",
         "   vec3 q = ro + rd * th.x;",
         "   float d = length(p - q) - r;",
         "   float s = clamp(k * d / th.x + 0.5, 0.0, 1.0);",
         "   return s * s * (3.0 - 2.0 * s);",
         "}");

      private static int volTex, prog, jobBuf, statBuf, readBuf;
      /** The static atlas's block stats as the GPU last copied them (persistent map, no sync: a frame or two old). */
      static java.nio.FloatBuffer readStats;
      static long volHandle, dynHandle;
      private static int uSun = -1, uLim, uB;
      private static boolean progFailed;
      private static final java.nio.FloatBuffer JOBS = org.lwjgl.BufferUtils.createFloatBuffer(8 * 4096);
      static long dispatches, jobsRun;

      /** Render thread, before the moving objects: this frame's changed bricks into the atlas (one dispatch). */
      static void compute(FrameState f) {
         if (!ensure()) {
            return;
         }
         if (f.jobCount == 0 || GodRays.Gl.occTex == 0) {
            return;
         }
         org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, STATS_BINDING, statBuf); // (binding 4: no other pass uses it)
         int count = Math.min(f.jobCount, JOBS.capacity() / 8);
         JOBS.clear();
         JOBS.put(f.jobs, 0, count * 8).flip();
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, jobBuf);
         org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, JOBS, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
         org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0, jobBuf);
         GL20.glUseProgram(prog);
         GL20.glUniform4f(uSun, f.dx, f.dy, f.slope, f.overhead);
         GL20.glUniform4f(uLim, GodRays.Gl.occMaxTop, 64F, 0.55F, GodRays.Gl.occZ0Now);
         GL20.glUniform4f(uB, 0F, 1F, Config.ENTITY_SHADOW_ROOF_PCT / 100F, 0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, GodRays.Gl.topTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         org.lwjgl.opengl.GL42.glBindImageTexture(0, volTex, 0, true, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_R8);
         org.lwjgl.opengl.GL43.glDispatchCompute(count, 1, 1);
         org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT | org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
         org.lwjgl.opengl.GL42.glBindImageTexture(0, 0, 0, true, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_R8);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         dispatches++;
         jobsRun += count;
      }

      static int volTex() {
         return volTex;
      }

      /**
       * Render thread, after this frame's compute passes: the block stats to the host copy, read by bind() a frame or two
       * later without a sync (a brick just computed waits UNIFORM_AGE frames before its stats are trusted).
       */
      static void readBack(FrameState f) {
         if (readStats == null || f.jobCount == 0 && f.dynJobCount == 0) {
            return;
         }
         org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, statBuf);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, readBuf);
         org.lwjgl.opengl.GL31.glCopyBufferSubData(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, 2L * STAT_DYN * 16L);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, 0);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0);
      }

      static final int UNIFORM_AGE = 4; // frames a freshly queued brick waits before its read-back stats are trusted

      /**
       * Render thread: the visibility of a brick all in the sun or all in the shade, from the read-back stats of its blocks;
       * -1 when it varies, has moving casters near (their shade is of this frame), or was computed too recently.
       */
      static float uniformValue(float[] br) {
         java.nio.FloatBuffer st = readStats;
         if (st == null || br[13] < UNIFORM_AGE || br[16] > 0F) {
            return -1F; // (moving casters near: the shaders draw their capsules)
         }
         // a brick with moving casters near reads the casters' atlas stats (static x capsules) of a frame or two ago: a
         // shadow starting to cross it switches it to the full path that much later
         int base = br[9] > 0F ? STAT_DYN : 0;
         int bx = (int)br[7] >> 3, by = (int)br[8] >> 3, nb = (int)br[3] / N, w = AX / N;
         float lo = 1F, hi = 0F;
         for (int j = 0; j < nb; j++) {
            for (int i = 0; i < nb; i++) {
               int k = (base + (by + j) * w + bx + i) * 4;
               lo = Math.min(lo, st.get(k));
               hi = Math.max(hi, st.get(k + 1));
            }
         }
         return hi - lo < 0.003F ? lo : -1F;
      }

      private static boolean ensure() {
         if (volTex != 0) {
            return true;
         }
         if (progFailed || !org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
            progFailed = true;
            return false;
         }
         String src = String.join("\n",
            "#version 430",
            "layout(local_size_x = " + N + ", local_size_y = " + N + ", local_size_z = " + NZ + ") in;",
            "layout(r8, binding = 0) uniform writeonly image3D uOut;",
            "layout(std430, binding = 0) readonly buffer Jobs { vec4 job[]; };",
            "vec3 pzEsW, pzEsN, pzEsC; vec4 pzEsQ; float pzEsV, pzEsU;", // (the shared code's fragment helpers read them; unused here)
            FRAG_GLSL,
            "layout(std430, binding = " + STATS_BINDING + ") buffer PzEsStatsW { vec4 stat[]; };",
            "shared uint sMin, sMax;",
            "void main() {",
            "   uint j = gl_WorkGroupID.x;",
            "   vec4 a = job[j * 2u], b = job[j * 2u + 1u];",
            "   ivec3 l = ivec3(gl_LocalInvocationID);",
            "   if (gl_LocalInvocationIndex == 0u) { sMin = 255u; sMax = 0u; }",
            "   barrier();",
            "   vec3 p = a.xyz + vec3(l) * b.xyz;",
            "   p.z = pzEsMaxF(p.z, floor(a.z + 0.12 + b.z) + 0.02);", // the brick's lowest layer is under the entity's floor: from just above it (else it sees the floor as a ceiling)
            "   float v = pzEsSunVis(p);",
            "   imageStore(uOut, ivec3(int(a.w) + l.x, int(b.w) + l.y, l.z), vec4(v));",
            // the block's lowest and highest probe: a block all in the sun or all in the shade needs no tap (pzEsProbe)
            "   uint q = uint(v * 255.0 + 0.5);",
            "   atomicMin(sMin, q);",
            "   atomicMax(sMax, q);",
            "   barrier();",
            "   if (gl_LocalInvocationIndex == 0u) stat[(int(b.w) >> 3) * " + (Probes.AX / Probes.N) + " + (int(a.w) >> 3)] = vec4(float(sMin), float(sMax), 0.0, 0.0) / 255.0;",
            "}");
         prog = GodRays.Gl.computeProgram(src, "entity shadow probes");
         if (prog == 0) {
            progFailed = true;
            Log.warn("entity shadows: the probe compute program failed; the per-pixel method's fallback is the constant factor");
            return false;
         }
         uSun = GL20.glGetUniformLocation(prog, "pzEsSun");
         uLim = GL20.glGetUniformLocation(prog, "pzEsLim");
         uB = GL20.glGetUniformLocation(prog, "pzEsB");
         jobBuf = org.lwjgl.opengl.GL15.glGenBuffers();
         volTex = GL11.glGenTextures();
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, volTex);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
         org.lwjgl.opengl.GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, org.lwjgl.opengl.GL30.GL_R8, AX, AY, NZ);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

         org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
         readBuf = caps.OpenGL44 || caps.GL_ARB_buffer_storage ? org.lwjgl.opengl.GL15.glGenBuffers() : 0; // (no persistent map: no uniform mode)
         if (readBuf != 0) {
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, readBuf);
         int flags = org.lwjgl.opengl.GL30.GL_MAP_READ_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
         org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 2L * STAT_DYN * 16L, flags | org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT);
         java.nio.ByteBuffer mapped = org.lwjgl.opengl.GL30.glMapBufferRange(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0L, 2L * STAT_DYN * 16L, flags);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0);
         readStats = mapped != null ? mapped.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer() : null;
         }
         statBuf = org.lwjgl.opengl.GL15.glGenBuffers();
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, statBuf);
         org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, STAT_DYN * 2L * 16L, org.lwjgl.opengl.GL15.GL_DYNAMIC_COPY);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);

         ensureDynamic(); // (both atlases exist before any handle is made)
         if (bindless && dynTex != 0) {
            volHandle = org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(volTex);
            dynHandle = org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(dynTex);
            org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(volHandle);
            org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(dynHandle);
         }
         Log.info("entity shadows: probe atlas " + AX + "x" + AY + "x" + NZ + " R8 ready, compute program " + prog + (bindless ? ", bindless handles" : ", bound per draw"));
         return true;
      }
   }

   // ------------------------------------------------------------------------------------------------ binds (render thread)

   private static final class Prog {
      int a, b, c, sun, lim, l, p0, p1, p2, r, cm0 = -1, cm1, cm2, tc = -1, ao = -1;
      boolean tcSet, aoSet; // the torch / occlusion uniform holds a shadow (reset after the draw)
      int tcN, aoN, memoTcN, memoAoN; // floats the last torch / occlusion upload held; the memo's (0: none)
      final float[] memoTc = new float[(2 + 2 * Torches.MAX_CASTERS) * 4], memoAo = new float[(1 + 2 * Ao.MAX_CASTERS) * 4];
      final float[] cur = new float[D_VEC4 * 4]; // what pzEsD holds (setA / setD); a new program's uniforms are 0
      IsoMovingObject memoObj; // the entity of the last full bind, its frame and camera, and the A it uploaded
      FrameState memoFrame;
      ModelCamera memoCam;
      final float[] memoA = new float[4];
      boolean handles, atlas, cloud;
      long rSerial = -1L; // the bindless samplers' handles are set
      long serial = -1L;
   }

   private static final HashMap<Integer, Prog> PROGS = new HashMap<>();
   private static Prog[] PROG_BY_ID = new Prog[256];
   private static boolean bound; // render thread: bind() set the program's shade (unbind() puts it back)
   private static final float[] D16 = new float[D_VEC4 * 4];
   private static final java.nio.FloatBuffer D_SHORT = org.lwjgl.BufferUtils.createFloatBuffer(28), D_LONG = org.lwjgl.BufferUtils.createFloatBuffer(D_VEC4 * 4);
   private static final Matrix4f C = new Matrix4f();
   private static final Matrix4f P_LAST = new Matrix4f();
   private static final Matrix4f TMP = new Matrix4f();
   private static final Matrix4f W = new Matrix4f();
   private static final java.nio.FloatBuffer M16 = org.lwjgl.BufferUtils.createFloatBuffer(16);
   private static double camX = Double.NaN, camY, camZ;
   private static boolean camDirty = true;
   private static int tileScaleLast = -1;
   private static long serial;
   private static FrameState texState;

   /**
    * Render thread, Model.DrawSolid / DrawVehicle after the stock uniforms (the program is bound): the shade uniforms of
    * this draw. A draw from another camera (the sun's atlas, mirrors, the car occupants' tiles) takes the constant factor.
    */
   public static void bind(Shader effect, ModelSlotRenderData slot, ModelInstanceRenderData inst) {
      bound = false;
      if (effect == null || !patched || ModelCamera.instance instanceof ShadowAtlas.SunCamera) {
         return; // (a sun depth draw: no colour; the program's shade is none since the last unbind)
      }
      bound = true;
      int id = effect.getID();
      Prog p = id >= 0 && id < PROG_BY_ID.length ? PROG_BY_ID[id] : null;
      if (p == null) {
         p = new Prog();
         p.a = GL20.glGetUniformLocation(id, "pzEsD");
         p.b = GL20.glGetUniformLocation(id, "pzEsB");
         p.c = GL20.glGetUniformLocation(id, "pzEsClipToWorld");
         p.sun = GL20.glGetUniformLocation(id, "pzEsSun");
         p.lim = GL20.glGetUniformLocation(id, "pzEsLim");
         p.l = GL20.glGetUniformLocation(id, "pzEsL");
         p.cm0 = GL20.glGetUniformLocation(id, "pzEsCm0");
         p.cm1 = GL20.glGetUniformLocation(id, "pzEsCm1");
         p.cm2 = GL20.glGetUniformLocation(id, "pzEsCm2");
         p.tc = GL20.glGetUniformLocation(id, "pzEsTc");
         p.ao = GL20.glGetUniformLocation(id, "pzEsAo");
         p.p0 = GL20.glGetUniformLocation(id, "pzEsP0");
         p.p1 = GL20.glGetUniformLocation(id, "pzEsP1");
         p.p2 = GL20.glGetUniformLocation(id, "pzEsP2");
         int uo = GL20.glGetUniformLocation(id, "pzEsOcc"), ut = GL20.glGetUniformLocation(id, "pzEsTop");
         if (Config.DEV_ENTITY_SHADOW_CHECK && p.a >= 0) {
            Log.info("entity shadows: program " + effect.getName() + " " + id + " samplers before: occ " + (uo < 0 ? "-" : GL20.glGetUniformi(id, uo)) + " top " + (ut < 0 ? "-" : GL20.glGetUniformi(id, ut)));
         }
         // the samplers' units (layout(binding) at link; set again in case the game's post-link pass renumbered them)
         if (uo >= 0) {
            GL20.glUniform1i(uo, OCC_UNIT);
         }
         if (ut >= 0) {
            GL20.glUniform1i(ut, TOP_UNIT);
         }
         PROGS.put(id, p);
         if (id >= 0 && id < 1 << 16) {
            if (id >= PROG_BY_ID.length) {
               PROG_BY_ID = java.util.Arrays.copyOf(PROG_BY_ID, Math.max(id + 1, PROG_BY_ID.length * 2));
            }
            PROG_BY_ID[id] = p;
         }
      }
      if (p.a < 0) {
         return; // not a patched program
      }
      binds++;
      FrameState f = rt;
      IsoMovingObject obj = slot != null ? slot.object : null;
      ModelCamera cam = ModelCamera.instance;
      if (obj != null && obj == p.memoObj && f == p.memoFrame && cam == p.memoCam && !f.noMemo) {
         // the next mesh of the entity drawn last with this program (a body's clothes, a car's parts): its values are still
         // in the program but for what the reset after the last draw zeroed (the A, the torch, the occlusion)
         memoHits++;
         float[] m = p.memoA;
         setA(p, m[0], m[1], m[2], m[3]);
         if (p.memoTcN > 0) {
            GL20.glUniform4fv(p.tc, Torches.TC.limit(p.memoTcN).put(0, p.memoTc, 0, p.memoTcN));
            p.tcSet = true;
         }
         if (p.memoAoN > 0) {
            GL20.glUniform4fv(p.ao, Ao.AO.limit(p.memoAoN).put(0, p.memoAo, 0, p.memoAoN));
            p.aoSet = true;
         }
         return;
      }
      bindDraw(p, id, f, obj, slot);
      p.memoObj = obj;
      p.memoFrame = f;
      p.memoCam = cam;
      System.arraycopy(p.cur, 0, p.memoA, 0, 4);
      p.memoTcN = p.tcSet ? p.tcN : 0;
      if (p.tcSet) {
         System.arraycopy(Torches.TCA, 0, p.memoTc, 0, p.tcN);
      }
      p.memoAoN = p.aoSet ? p.aoN : 0;
      if (p.aoSet) {
         System.arraycopy(Ao.AOA, 0, p.memoAo, 0, p.aoN);
      }
   }

   /** Render thread (bind): the draw's shade uniforms, computed. */
   private static void bindDraw(Prog p, int id, FrameState f, IsoMovingObject obj, ModelSlotRenderData slot) {
      boolean outside;
      if (obj instanceof zombie.characters.IsoGameCharacter) {
         outside = slot.outside;
      } else {
         IsoGridSquare sq = obj != null ? obj.getCurrentSquare() : null;
         outside = sq != null && sq.isOutside();
      }
      boolean main = mainCamera();
      if (main && obj != null && f.wantDrawn && obj.pzoptEsDrawn != f.frame) {
         obj.pzoptEsDrawn = f.frame; // (the gather builds bricks for the objects drawn as models: queued once a frame)
         Probes.DRAWN.add(obj);
      }
      boolean torch = f.torchCount > 0 && main && p.tc >= 0 && !failed && Torches.bind(p, id, f, obj);
      if (!torch && p.tcSet) {
         GL20.glUniform4f(p.tc, 0F, 0F, 0F, 0F); // (no torch shadow for this draw)
         p.tcSet = false;
      }
      boolean ao = f.aoOn && main && p.ao >= 0 && !failed && Ao.bind(p, id, f, obj);
      if (!ao && p.aoSet) {
         GL20.glUniform4f(p.ao, 0F, 0F, 0F, 0F); // (no occlusion for this draw)
         p.aoSet = false;
      }
      if (!f.on || !outside || failed) {
         setA(p, 0F, 0F, 0F, 0F);
         constBinds++;
         return;
      }
      float[] br = f.method == 2 && main ? f.bricks.get(obj) : null;
      boolean probe = br != null && Probes.volTex() != 0;
      boolean pixel = f.method == 1 && main && GodRays.Gl.occTex != 0;
      float cloud = probe || obj == null ? 1F : CloudShadow.transmittanceAt(obj.getX(), obj.getY(), obj.getZ()); // (a brick's row holds its own)
      if (!probe && !pixel) {
         // the CPU march at the object (one value for the body), as before; a character's ambient already holds it when the
         // method is cpu (ModelInstance)
         float k = obj instanceof zombie.characters.IsoGameCharacter chr ? SunShadow.characterFactor(chr)
            : obj != null ? 1F - f.s * (1F - SunShadow.visibleAt(obj.getX(), obj.getY(), obj.getZ()) * cloud) : 1F;
         setA(p, 0F, f.method == 0 && main && obj instanceof zombie.characters.IsoGameCharacter ? 0F : 1F - k, f.s, f.dev);
         constBinds++;
         return;
      }
      if (!frameUniforms(p, id, f)) {
         setA(p, 0F, 0F, 0F, 0F);
         return;
      }
      if (pixel || f.dev >= 2) {
         // the occupancy textures on their units at every draw: in a busy frame something between two model draws resets
         // the high units (a crowd: unit 40 read 0 at the draws, every character lit; es-occ7)
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         GL11.glBindTexture(GL12.GL_TEXTURE_3D, GodRays.Gl.occTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, GodRays.Gl.topTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      if (probe) {
         // probe volume: one uniform a draw (method, the entity's table row, strength, view); the atlases and the table by
         // handle (set once a program) or bound here
         probeBinds++;
         if (bindless) {
            if (!p.handles && Probes.volHandle != 0L) {
               p.handles = true;
               handle(id, "pzEsVol", Probes.volHandle);
               handle(id, "pzEsDyn", Probes.dynHandle);
            }
         } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + VOL_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, Probes.volTex());
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + DYN_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, Probes.dynTex());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         float u = f.forceUniform ? 1F : f.skip || f.vsOnly || !Config.ENTITY_SHADOW_UNIFORM || f.noUniform ? -1F : Probes.uniformValue(br);
         if (u >= 0F) {
            uniformBinds++;
            float[] d = D16;
            boolean self = bindless && br[25] >= 0F && u > 0F && selfReady(p, id);
            d[0] = 6F;
            d[1] = f.cloudOn && bindless ? u : u * br[11]; // (the clouds per pixel when the shaders have the field)
            d[2] = f.s;
            d[3] = f.dev;
            if (!self && !(f.cloudOn && bindless && u > 0F)) {
               setA(p, d[0], d[1], d[2], d[3]);
               return;
            }
            d[15] = br[12]; // (the lift, pzEsP2.w)
            d[20] = br[25];
            d[21] = br[26];
            d[22] = self ? 1F : 0F;
            d[24] = br[27];
            d[25] = br[28];
            d[26] = br[29];
            setD(p, d, 28, D_SHORT);
            return;
         }
         // characters per vertex (their meshes are dense), vehicles per pixel (a car's big triangles would smear a shadow's edge)
         boolean perVertex = f.perVertex || Config.ENTITY_SHADOW_HYBRID && !(obj instanceof zombie.vehicles.BaseVehicle) && !f.perPixelAll;
         float[] d = D16;
         int nc = (int)br[16];
         d[16] = nc;
         d[17] = f.capK;
         boolean self = bindless && br[25] >= 0F && selfReady(p, id);
         d[20] = br[25];
         d[21] = br[26];
         d[22] = self ? 1F : 0F;
         d[24] = br[27];
         d[25] = br[28];
         d[26] = br[29];
         for (int q = 0; q < nc; q++) {
            int c = (int)br[17 + q] * 8, t = 28 + q * 8;
            System.arraycopy(f.casters, c, d, t, 8);
         }
         d[0] = f.skip ? 3F : f.vsOnly ? 5F : perVertex ? 4F : 2F;
         d[1] = br[10];
         d[2] = f.s;
         d[3] = f.dev;
         d[4] = br[0];
         d[5] = br[1];
         d[6] = br[2];
         d[7] = br[3];
         d[8] = br[4];
         d[9] = br[5];
         d[10] = br[6];
         d[11] = br[9];
         d[12] = br[7];
         d[13] = br[8];
         d[14] = br[11];
         d[15] = br[12];
         if (nc > 0) {
            setD(p, d, 28 + nc * 8, D_LONG); // (A, the brick, K, the tile and the capsules: one call)
         } else {
            setD(p, d, 28, D_SHORT); // (A, the brick, K and the tile)
         }
         return;
      }
      // Core.DoPushIsoStuff draws a person 0.48 model units (0.72 squares, 0.294 levels) under where the bones put it (feet
      // on the floor); the shade is looked up where the body really is
      ModelCamera cam = ModelCamera.instance;
      float lift = cam instanceof zombie.core.opengl.CharacterModelCamera && !cam.inVehicle ? 0.29393876F : 0F;
      GL20.glUniform4f(p.b, (float)Math.floor(obj.getZ()), cloud, Config.ENTITY_SHADOW_ROOF_PCT / 100F, lift);
      perPixelBinds++;
      setA(p, 1F, 0F, f.s, f.dev);
      if (Config.DEV_ENTITY_SHADOW_CHECK && checks < 60 && CHECKED.add(obj) && Core.getInstance().modelViewMatrixStack.peek() != null) {
         // dev: the model origin through MVP and back through C against the object's position (once per object)
         checks++;
         Matrix4f mvp = new Matrix4f(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
         org.joml.Vector4f o = mvp.transform(new org.joml.Vector4f(0F, 0F, 0F, 1F));
         o.div(o.w);
         org.joml.Vector4f w = C.transform(new org.joml.Vector4f(o.x, o.y, o.z, 1F));
         Log.info(String.format(java.util.Locale.ROOT, "entity shadows: check %s at %.3f,%.3f,%.3f -> origin %.3f,%.3f,%.3f cpu %s (sun %.3f,%.3f slope %.4f, occ z0 %d top %.1f tex %d/%d)",
            obj.getClass().getSimpleName(), obj.getX(), obj.getY(), obj.getZ(), w.x, w.y, w.z, SunShadow.devMarch(obj.getX(), obj.getY(), obj.getZ()), f.dx, f.dy, f.slope, GodRays.Gl.occZ0Now, GodRays.Gl.occMaxTop, GodRays.Gl.occTex, GodRays.Gl.topTex));
      }
      if (Config.DEV_ENTITY_SHADOW_CHECK && !dumped && statFrames > 900 && obj instanceof zombie.characters.IsoPlayer) {
         dumped = true;
         {
            // the occupancy bits along the sun's way (x towards the sun when it is mostly east / west), levels 0..2
            StringBuilder b = new StringBuilder("entity shadows: occupancy from the player along x:");
            int sx = (int)Math.floor(obj.getX()), sy = (int)Math.floor(obj.getY()), dir = f.dx >= 0F ? 1 : -1;
            for (int L = 0; L <= 2; L++) {
               b.append(" | L").append(L).append(':');
               for (int i = 0; i <= 14; i++) {
                  int x = sx + i * dir;
                  int si = (Math.floorDiv(sy, 8) & GodRays.OCC_CHUNKS - 1) * GodRays.OCC_CHUNKS + (Math.floorDiv(x, 8) & GodRays.OCC_CHUNKS - 1);
                  int[] d = GodRays.slotData[si];
                  int lz = L - GodRays.Gl.occZ0Now;
                  int v = d == null || lz < 0 || lz >= GodRays.OCC_L ? -1 : d[(lz * 8 + Math.floorMod(sy, 8)) * 8 + Math.floorMod(x, 8)];
                  b.append(' ').append(x).append('=').append(v < 0 ? "?" : Integer.toHexString(v & 0xFFFF));
               }
            }
            b.append(" | tops");
            for (int i = 0; i <= 14; i += 8) {
               int x = sx + i * dir;
               b.append(' ').append(GodRays.levelTops[(Math.floorDiv(sy, 8) & GodRays.OCC_CHUNKS - 1) * GodRays.OCC_CHUNKS + (Math.floorDiv(x, 8) & GodRays.OCC_CHUNKS - 1)]);
            }
            Log.info(b.toString());
            // the 5 x 5 chunks round the player: occupancy (16 levels x 64 ints each) and tops, for an offline replay of the trace
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(
                  zombie.ZomboidFileSystem.instance.getCacheDir() + java.io.File.separator + "pzopt-esocc.bin")))) {
               int cwx = Math.floorDiv(sx, 8), cwy = Math.floorDiv(sy, 8);
               out.writeInt(cwx);
               out.writeInt(cwy);
               out.writeInt(GodRays.Gl.occZ0Now);
               out.writeFloat(obj.getX());
               out.writeFloat(obj.getY());
               out.writeFloat(obj.getZ());
               out.writeFloat(f.dx);
               out.writeFloat(f.dy);
               out.writeFloat(f.slope);
               for (int wy = cwy - 2; wy <= cwy + 2; wy++) {
                  for (int wx = cwx - 2; wx <= cwx + 2; wx++) {
                     int si = (wy & GodRays.OCC_CHUNKS - 1) * GodRays.OCC_CHUNKS + (wx & GodRays.OCC_CHUNKS - 1);
                     int[] d = GodRays.slotData[si];
                     out.writeInt(GodRays.levelTops[si]);
                     for (int i = 0; i < 64 * GodRays.OCC_L; i++) {
                        out.writeInt(d == null ? 0 : d[i]);
                     }
                  }
               }
            } catch (java.io.IOException e) {
               Log.warn("entity shadows: occupancy dump failed: " + e);
            }
            // the GPU's copies of the same: the occupancy texture and the tops, read back (dev, once)
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(
                  zombie.ZomboidFileSystem.instance.getCacheDir() + java.io.File.separator + "pzopt-esocc-gpu.bin")))) {
               java.nio.IntBuffer ib = org.lwjgl.BufferUtils.createIntBuffer(GodRays.OCC_N * GodRays.OCC_N * GodRays.OCC_L);
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
               GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, org.lwjgl.opengl.GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, ib);
               java.nio.ByteBuffer tb = org.lwjgl.BufferUtils.createByteBuffer(GodRays.OCC_CHUNKS * GodRays.OCC_CHUNKS);
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
               GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
               GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, tb);
               GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
               GL13.glActiveTexture(GL13.GL_TEXTURE0);
               for (int i = 0; i < ib.capacity(); i++) {
                  out.writeInt(ib.get(i));
               }
               for (int i = 0; i < tb.capacity(); i++) {
                  out.writeByte(tb.get(i));
               }
            } catch (java.io.IOException e) {
               Log.warn("entity shadows: gpu occupancy dump failed: " + e);
            }
         }
      }
      GL20.glUniform4f(p.b, (float)Math.floor(obj.getZ()), cloud, Config.ENTITY_SHADOW_ROOF_PCT / 100F, 0F);
      if (f.dev == 5 || f.dev == 9) {
         GL20.glUniform4f(GL20.glGetUniformLocation(id, "pzEsDbg"), obj.getX(), obj.getY(), obj.getZ() + 0.3F, 0F);
      } else if (f.dev == 6) {
         GL20.glUniform4f(GL20.glGetUniformLocation(id, "pzEsDbg"), Config.DEV_ENTITY_SHADOW_PROBE_X, Config.DEV_ENTITY_SHADOW_PROBE_Y, Config.DEV_ENTITY_SHADOW_PROBE_Z, 0F);
      }
      if (Config.DEV_ENTITY_SHADOW_CHECK && (++devGets & 1023) == 1) {
         int act = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + OCC_UNIT);
         int b3 = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
         int w3 = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_WIDTH), d3 = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL12.GL_TEXTURE_DEPTH);
         int f3 = GL11.glGetTexLevelParameteri(GL12.GL_TEXTURE_3D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
         Log.info("entity shadows: unit " + OCC_UNIT + " 3D texture " + b3 + " is " + w3 + " wide, " + d3 + " deep, format 0x" + Integer.toHexString(f3) + ", isTexture " + GL11.glIsTexture(GodRays.Gl.occTex));
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
         int b2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         GL13.glActiveTexture(act);
         float[] ua = new float[4], ub = new float[4], us = new float[4], ul = new float[4];
         GL20.glGetUniformfv(id, p.a, ua);
         GL20.glGetUniformfv(id, p.b, ub);
         GL20.glGetUniformfv(id, p.sun, us);
         GL20.glGetUniformfv(id, p.lim, ul);
         Log.info("entity shadows: uniforms A " + java.util.Arrays.toString(ua) + " B " + java.util.Arrays.toString(ub) + " sun " + java.util.Arrays.toString(us) + " lim " + java.util.Arrays.toString(ul) + " locs " + p.a + "/" + p.b + "/" + p.sun + "/" + p.lim + "/" + p.c);
         Log.info("entity shadows: at a draw unit " + OCC_UNIT + " 3D=" + b3 + " unit " + TOP_UNIT + " 2D=" + b2 + " (occ " + GodRays.Gl.occTex + " top " + GodRays.Gl.topTex + "), active unit " + (act - GL13.GL_TEXTURE0) + ", program " + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) + " / " + id);
      }
   }

   /**
    * Render thread, right after the draw bind() set up: the program's shade back to none, so a draw of the same program
    * on another path (a world model baked into a chunk texture, the UI's 3D views) never inherits a character's.
    */
   public static void unbind(Shader effect) {
      if (effect == null || !patched || !bound) {
         return;
      }
      bound = false;
      int id = effect.getID();
      Prog p = id >= 0 && id < PROG_BY_ID.length ? PROG_BY_ID[id] : null;
      if (p != null && p.a >= 0 && !rt.noReset) {
         setA(p, 0F, 0F, 0F, 0F);
      }
      if (p != null && p.tcSet) {
         GL20.glUniform4f(p.tc, 0F, 0F, 0F, 0F); // (other paths draw these programs too)
         p.tcSet = false;
      }
      if (p != null && p.aoSet) {
         GL20.glUniform4f(p.ao, 0F, 0F, 0F, 0F);
         p.aoSet = false;
      }
   }

   /**
    * Render thread: the frame's uniforms of the program (the clip-to-world matrix, the light, the clouds) once a frame; false
    * without a projection.
    */
   private static boolean frameUniforms(Prog p, int id, FrameState f) {
      if (texState != f) {
         texState = f; // a new frame: every program's frame uniforms again, the camera's position again
         serial++;
         camDirty = true;
      }
      if (!clipToWorld()) {
         return false;
      }
      if (p.serial != serial) {
         p.serial = serial;
         M16.clear();
         C.get(M16);
         GL20.glUniformMatrix4fv(p.c, false, M16);
         GL20.glUniform4f(p.sun, f.dx, f.dy, f.slope, f.overhead);
         GL20.glUniform4f(p.lim, GodRays.Gl.occMaxTop, 64F, 0.55F, GodRays.Gl.occZ0Now);
         GL20.glUniform4f(p.l, f.lx, f.ly, f.lz, f.form);
         if (p.cm0 >= 0) {
            float[] cm = f.cloudMap;
            GL20.glUniform4f(p.cm0, cm[0], cm[1], cm[2], cm[3]);
            GL20.glUniform4f(p.cm1, cm[4], cm[5], cm[6], cm[7]);
            GL20.glUniform4f(p.cm2, cm[8], cm[9], cm[10], f.cloudOn && cloudReady(p, id) ? cm[11] : 0F);
         }
      }
      return true;
   }

   /**
    * Render thread: element 0 of the program's array (A) unless it holds these values already (the program's uniforms
    * keep their values between draws: an indoor or constant draw after the reset, or the next mesh of the same entity, needs
    * no call).
    */
   private static void setA(Prog p, float a, float b, float c, float d) {
      float[] cur = p.cur;
      if (!rt.noDedupe && cur[0] == a && cur[1] == b && cur[2] == c && cur[3] == d) {
         dedupSkips++;
         return;
      }
      cur[0] = a;
      cur[1] = b;
      cur[2] = c;
      cur[3] = d;
      GL20.glUniform4f(p.a, a, b, c, d);
   }

   /** Render thread: the first n floats of the program's array unless it holds them already (setA). */
   private static void setD(Prog p, float[] d, int n, java.nio.FloatBuffer buf) {
      float[] cur = p.cur;
      if (!rt.noDedupe && java.util.Arrays.equals(cur, 0, n, d, 0, n)) {
         dedupSkips++;
         return;
      }
      System.arraycopy(d, 0, cur, 0, n);
      GL20.glUniform4fv(p.a, buf.limit(n).put(0, d, 0, n));
   }

   /**
    * Game thread (IsoZombie.renderAtlasTexture): the shade of a zombie drawn as an atlas sprite (no model, no shader of
    * ours): the CPU march at its chest, the one value for the body (cached per half square and sun step).
    */
   public static float impostorFactor(zombie.characters.IsoGameCharacter chr) {
      return Config.ENTITY_SHADOWS && Config.ENTITY_SHADOW_IMPOSTORS && !"off".equals(variant()) ? SunShadow.characterFactor(chr) : 1F;
   }

   /** Render thread: a texture handle into the program's two int uniforms {@code name}Lo / Hi (the shaders build their samplers from them, PROBE_SAMPLERS). */
   private static void handle(int id, String name, long h) {
      int lo = GL20.glGetUniformLocation(id, name + "Lo"), hi = GL20.glGetUniformLocation(id, name + "Hi");
      if (lo >= 0) {
         org.lwjgl.opengl.GL41.glProgramUniform1i(id, lo, (int)h);
      }
      if (hi >= 0) {
         org.lwjgl.opengl.GL41.glProgramUniform1i(id, hi, (int)(h >>> 32));
      }
   }

   /** Render thread: the program's cloud field handle (once); false before the field exists or without bindless. */
   private static boolean cloudReady(Prog p, int id) {
      if (p.cloud) {
         return true;
      }
      long h = bindless ? CloudShadow.fieldHandle() : 0L;
      int loc = h != 0L ? GL20.glGetUniformLocation(id, "pzEsCloudLo") : -1;
      if (loc < 0) {
         return false;
      }
      handle(id, "pzEsCloud", h);
      p.cloud = true;
      return true;
   }

   /** Render thread: the program's atlas handle (once) and this frame's sun view (once a frame); false before the atlas exists. */
   private static boolean selfReady(Prog p, int id) {
      if (!p.atlas) {
         long h = ShadowAtlas.compareHandle();
         if (h == 0L) {
            return false;
         }
         int loc = GL20.glGetUniformLocation(id, "pzEsAtlasLo");
         p.r = GL20.glGetUniformLocation(id, "pzEsR");
         if (loc < 0 || p.r < 0) {
            return false;
         }
         handle(id, "pzEsAtlas", h);
         p.atlas = true;
      }
      if (p.rSerial != serial) {
         p.rSerial = serial;
         float[] r = ShadowAtlas.R; // rows: across, up, towards the sun (GLSL mat3 takes columns: transpose)
         M9.clear();
         M9.put(r[0]).put(r[3]).put(r[6]).put(r[1]).put(r[4]).put(r[7]).put(r[2]).put(r[5]).put(r[8]).flip();
         GL20.glUniformMatrix3fv(p.r, false, M9);
      }
      return true;
   }

   private static final java.nio.FloatBuffer M9 = org.lwjgl.BufferUtils.createFloatBuffer(9);

   /** Is the model camera now the world's (characters / vehicles), not a pzopt pass's own? */
   private static boolean mainCamera() {
      ModelCamera c = ModelCamera.instance;
      return c instanceof zombie.core.opengl.CharacterModelCamera || c instanceof zombie.vehicles.VehicleModelCamera;
   }

   /**
    * The clip-to-world matrix of the model camera now (Core.DoPushIsoStuff: projection P, the iso view W = scale x
    * rotations, the camera's position; a model's own transform comes after and drops out): world = A W^-1 P^-1 clip.
    * Recomputed when P, the tile scale or the camera position change. False when there is no projection.
    */
   private static boolean clipToWorld() {
      if (Core.getInstance().projectionMatrixStack.isEmpty()) {
         return false;
      }
      Matrix4f proj = Core.getInstance().projectionMatrixStack.peek();
      if (!camDirty && proj.equals(P_LAST)) {
         return true; // (the camera's position holds for the frame: bind() marks a new frame)
      }
      camDirty = false;
      // the camera's position as DoPushIsoStuff computes it
      float cx = Core.getInstance().floatParamMap.get(0).floatValue();
      float cy = Core.getInstance().floatParamMap.get(1).floatValue();
      float cz = Core.getInstance().floatParamMap.get(2).floatValue();
      zombie.core.sprite.SpriteRenderState rs = SpriteRenderer.instance.getRenderingState();
      PlayerCamera cam = rs.playerCamera[rs.playerIndex];
      double x = cx, y = cy;
      float tox = cam.getTOffX(), toy = cam.getTOffY();
      x -= cam.XToIso(-tox - cam.rightClickX, -toy - cam.rightClickY, 0.0F);
      y -= cam.YToIso(-tox - cam.rightClickX, -toy - cam.rightClickY, 0.0F);
      x += cam.deferedX;
      y += cam.deferedY;
      if (x == camX && y == camY && cz == camZ && Core.tileScale == tileScaleLast && proj.equals(P_LAST)) {
         return true;
      }
      camX = x;
      camY = y;
      camZ = cz;
      tileScaleLast = Core.tileScale;
      P_LAST.set(proj);
      W.scaling(Core.scale).scale(Core.tileScale / 2.0F).rotate(0.5235988F, 1.0F, 0.0F, 0.0F).rotate(2.3561945F, 0.0F, 1.0F, 0.0F);
      // v = W^-1 P^-1 clip: x west, y up (metric), z north, relative to the camera; world x = camX - v.x, y = camY - v.z,
      // level = camZ + v.y / 2.449 (joml's constructor takes the columns)
      Matrix4f a = TMP.set(-1F, 0F, 0F, 0F, 0F, 0F, 1F / LEVEL, 0F, 0F, -1F, 0F, 0F, (float)x, (float)y, cz, 1F);
      C.set(proj).mul(W).invert();
      a.mul(C, C);
      serial++;
      return true;
   }
}
