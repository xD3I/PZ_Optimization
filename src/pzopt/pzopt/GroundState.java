package pzopt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * Ground and grass, shared part (2026-10-24, v2 the same evening): what the ground remembers (ruts, prints, berms, worn
 * paths) as a GPU map round the player, kept per save on disk. v1 was a CPU map uploaded in tiles (8 texels a square,
 * ~47 us a frame of game thread with 200 walkers); v2 takes the design of the tracks branch (pzopt.GroundTracks, its
 * interim map): the game thread only lists this frame's primitives binned per square, one compute dispatch writes them.
 *
 * <h2>The map</h2>
 * {@link #SQUARES} x {@link #SQUARES} squares at {@link #TPS} texels a square: a {@link #TN}^2 RGBA8 texture (16 MB of
 * VRAM, nothing on the heap), toroidal: world square (x, y) is texel (x * TPS & (TN - 1), y * TPS & (TN - 1)), GL_REPEAT,
 * linear. It holds the 16 x 16 chunks of the window round the player (chunk (cx, cy) in slot (cx & 15, cy & 15)); the
 * window recentres when the player strays 3 chunks from its middle, and a slot that changes chunk is saved (when it holds
 * anything new), cleared to the rest texel and loaded with its new chunk. Channels (fixed here; the rules decide the
 * physics):
 * <ul>
 *   <li>R: height h in squares, {@code h = (r * 255 - 192) / 510} (-0.376 .. +0.125; rest 192: flat), &lt; 0 a rut, &gt; 0 a berm;</li>
 *   <li>G: wear / compaction 0..1 (trodden grass, a horde's path);</li>
 *   <li>B: freshness 0..1 (1 just cut, fades in hours);</li>
 *   <li>A: slip 0..1 (a spinning or locked wheel's smear).</li>
 * </ul>
 *
 * <h2>Writing</h2>
 * Game thread: {@link #prim} (or {@link #wheel}, {@link #ellipse}, {@link #drag}) during {@link Ground#frameStart}; a
 * primitive is binned into the squares its box covers whose surface takes prints ({@link GroundTiles#surface}: grass, dirt,
 * sand, gravel, snow over any of them; cached per chunk 5 s), one compute work group a square (16 x 16 texels). The default
 * sources turn {@link GroundMovers}' events into primitives (wheels: depth 0.10 sqrt(mass / 1400) at most 0.2, half width
 * 0.10 / 0.13 above 2,500 kg, deeper and wider with slip; feet: a 0.14 x 0.055 ellipse 0.09 beside the walked line, animals
 * 0.7 size; bodies 0.85 x 0.28 once; crawlers a 0.22 drag); {@link #SOURCES} switches and scales them.
 *
 * <p><b>Primitives</b> (12 floats, the prim SSBO; a = vec4 0..3, b = 4..7, c = 8..11; c.w the kind):
 * <ul>
 *   <li>kind 0 wheel line: a = (x0, y0, x1, y1), b = (half width, depth, berm height, slip), c = (wear, berm width, tread, 0);</li>
 *   <li>kind 1 ellipse (foot, hoof, body): a = (x, y, heading x, heading y), b = (half length, half width, depth, berm),
 *       c = (wear, berm width, heel-toe 0..1, 1);</li>
 *   <li>kind 2 drag line: as kind 0 (shallow, wide, high wear), c.w = 2. Other kinds are the rules' own.</li>
 * </ul>
 * Positions are world squares on the game thread, made relative to the frame's origin (the window's first square) when
 * binned, so the GPU works in small floats.
 *
 * <h2>Rules</h2>
 * {@link #setRules(Rules)}: GLSL that defines {@code vec4 gsStampTexel(vec4 s, vec2 p, int mat, int count)} (s decoded,
 * p the texel's position from the frame origin, the square's primitives through {@code gsPrimA / B / C(i)}), {@code vec4
 * gsDecayTexel(vec4 s, int mat, float dt, vec4 weather)}, and with {@link Rules#relax()} {@code float gsRelaxHeight(float h,
 * vec4 neighbours, int mat)} (a pass after the stamp and after the decay over an 18 x 18 shared tile). The framework decodes
 * and encodes (stamp: nearest; decay and relax: stochastic rounding seeded by the frame so slow decays move on average).
 * Without rules {@link DefaultRules} keeps wear and freshness only (the blades' worn paths with the tracks off).
 *
 * <h2>Decay</h2>
 * Every {@link Rules#decayEveryHours()} game hours over the live chunks ({@link Rules#decayDt} scales the hours; weather =
 * rain, snowfall, temperature C, wind), and once over a chunk read back from disk with the hours since it was saved.
 *
 * <h2>Reading</h2>
 * GLSL {@link #GLSL}: {@code pzgStateAt(world)} decoded (h, wear, freshness, slip; zeros outside the window or while off),
 * {@code pzgStateRaw}. The floor variant has it built in ({@link GroundBake}); another program declares {@link #GLSL},
 * calls {@link #bindTexture()} (unit {@link #UNIT}) and {@link #applyUniforms(int)} on the render thread, or binds
 * {@link #texture()} itself (uv = world square * TPS / TN). Game thread: {@link #isLive(int, int)} (a chunk holds anything),
 * {@link #inWindow(int, int)}, {@link #windowX()} / {@link #windowY()}.
 *
 * <h2>Re-bakes</h2>
 * With {@link Rules#rebakes()} a chunk that changed re-bakes its level 0 at most every {@link Rules#rebakeMs()} (4 a frame,
 * DIRTY_BLOOD: a floor decal changed). {@link #addListener}: told of each chunk that changed (same throttle).
 *
 * <h2>Persistence</h2>
 * {@code groundTracksPersist}: a slot leaving the window, every 30 s and at {@link #flush()} its chunk is read back on the
 * GPU's time (glGetTextureSubImage into a pixel buffer, a fence polled without waiting on later frames; never a glGet, never
 * a stall) and written on one background thread to {@code <cache>/pzopt/ground/<mode>/<save>/r_<rx>_<ry>.gsr} (never inside
 * the save; one region file per 16 x 16 chunks, deflated chunk blobs with the game hour they were written). A chunk entering
 * the window is read on that thread, uploaded through a pixel buffer and decayed by its time away; a chunk stamped before
 * its file arrived keeps the new stamps and drops the file. What was stamped in the last 30 s before quitting is lost.
 *
 * <h2>Cost</h2>
 * Nothing without a user: {@link Ground#frameStart} returns before reaching it, no texture exists. On: the game thread bins
 * (a few us with a car and 200 walkers), the GPU runs one dispatch over the stamped squares, decay every 15 game minutes.
 * Needs OpenGL 4.4 (compute, image stores, texture clears); persistence 4.5. macOS (4.1) and older GPUs: off.
 */
public final class GroundState {
   private GroundState() {
   }

   public static final int TPS = 16, SQUARES = 128, TN = TPS * SQUARES, CS = 8, SLOTS = SQUARES / CS, CHUNK_TEX = CS * TPS;
   public static final int UNIT = GroundBake.STATE_UNIT;
   public static final int MAX_PRIMS = 2048, MAX_CELLS = 8192, MAX_PAIRS = 16384, MAX_DECAY = SLOTS * SLOTS * 64;
   public static final int MAX_LOADS_PER_FRAME = 4, READBACK_BATCH = 16;
   /** The rest texel (flat, untouched). */
   public static final byte REST_R = (byte)192;
   public static final long CHANGE_MS = 250L;

   // ------------------------------------------------------------------------------------------------ the API

   /** The physics of the map: GLSL and its clocks (pzopt.GroundTracks gives its own). */
   public interface Rules {
      /** A name (a different id rebuilds the program). */
      String id();

      /** GLSL defining gsStampTexel, gsDecayTexel and, with {@link #relax()}, gsRelaxHeight (see the class comment). */
      String glsl();

      /** A relax pass after each stamp and decay ({@code gsRelaxHeight}). */
      default boolean relax() {
         return false;
      }

      /** The dt the decay pass gets for {@code gameHours} of game time. */
      default float decayDt(double gameHours) {
         return (float)(gameHours * 2.0 / Math.max(1, Config.GROUND_TRACKS_DAYS));
      }

      /** Game hours between decay passes. */
      default double decayEveryHours() {
         return 0.25;
      }

      /** Chunks that changed re-bake (the state is drawn into the chunk textures). */
      default boolean rebakes() {
         return false;
      }

      default long rebakeMs() {
         return 300L;
      }

      /** A chunk on disk older than this (game hours) is dropped instead of loaded. */
      default double maxAgeHours() {
         return 24.0 * 32.0;
      }
   }

   /** The default sources' switches and scale (the tracks feature sets them from its keys). */
   public static final class Sources {
      public volatile boolean vehicles = true, feet = true, bodies = true, on = true;
      public volatile float strengthPct = 100F;
   }

   public static final Sources SOURCES = new Sources();

   public interface ChangeListener {
      /** Game thread: chunk (cx, cy)'s ground state changed. */
      void changed(int cx, int cy);
   }

   private static volatile Rules rules = new DefaultRules();
   private static final CopyOnWriteArrayList<ChangeListener> LISTENERS = new CopyOnWriteArrayList<>();
   private static boolean rebakeOnChange;
   private static int usedFrame = -100;
   private static int fullRebakeFrame = -100;

   public static void setRules(Rules r) {
      rules = r == null ? new DefaultRules() : r;
   }

   public static Rules rules() {
      return rules;
   }

   public static void addListener(ChangeListener l) {
      LISTENERS.addIfAbsent(l);
   }

   public static void removeListener(ChangeListener l) {
      LISTENERS.remove(l);
   }

   /** Changed chunks re-bake their level 0 (low priority) through GroundBake, whatever the rules say. */
   public static void rebakeOnChange(boolean on) {
      rebakeOnChange = on;
   }

   /**
    * Game thread, every frame it applies (e.g. from {@link Ground#onFrame}): a feature whose floor pass reads the state (the
    * cover crushed in the ruts) needs changed chunks re-baked whole, not only their after-snow decals; the full re-bake then
    * replaces the rules' DIRTY_BLOOD one, so a change costs one re-bake whoever reads it.
    */
   public static void wantFullRebakes() {
      fullRebakeFrame = frame;
   }

   /** Game thread, once a frame: a feature reads or writes the map (keeps it on into the next frame). */
   public static void use() {
      usedFrame = frame;
   }

   /** The map runs this frame. */
   public static boolean active() {
      return activeNow;
   }

   /** Something to finish (read-backs in flight, a flush): Ground.frameStart keeps calling. */
   static boolean busy() {
      return activeNow || wasActive || inFlight.get() > 0;
   }

   /** Game thread: chunk (cx, cy) is in the window and holds anything (a stamp, a load). */
   public static boolean isLive(int cx, int cy) {
      int s = slot(cx, cy);
      return activeNow && inWindow(cx, cy) && slotKey[s] == key(cx, cy) && slotLive[s];
   }

   public static boolean inWindow(int cx, int cy) {
      return wcx != Integer.MIN_VALUE && cx >= wcx && cy >= wcy && cx < wcx + SLOTS && cy < wcy + SLOTS;
   }

   /** The window's first chunk (game thread). */
   public static int windowX() {
      return wcx;
   }

   public static int windowY() {
      return wcy;
   }

   /** The weather the decay sees: rain 0..1, snowfall 0..1, temperature C, wind 0..1 (game thread, updated each frame on). */
   public static final float[] WEATHER = new float[4];

   // ------------------------------------------------------------------------------------------------ the default rules

   /** Wear and freshness only: a trodden path that recovers over groundTracksDays (no ruts). */
   public static final class DefaultRules implements Rules {
      @Override
      public String id() {
         return "default";
      }

      @Override
      public String glsl() {
         return GLSL_DEFAULT_RULES;
      }
   }

   static final String GLSL_DEFAULT_RULES = String.join("\n",
      "// surfaces 0 none, 1 grass, 2 dirt, 3 sand, 4 gravel, 5 snow: wear a pass adds, the wear's tau (hours of dt)",
      "const float GS_WEAR[6] = float[6](0.0, 0.22, 0.18, 0.08, 0.12, 0.30);",
      "const float GS_TAU[6] = float[6](1.0, 24.0, 168.0, 48.0, 120.0, 400.0);",
      "float gsWeight(vec4 a, vec4 b, vec4 c, vec2 p) {",
      "   if (int(c.w + 0.5) == 1) {",
      "      vec2 d = p - a.xy;",
      "      vec2 q = vec2(dot(d, a.zw), dot(d, vec2(-a.w, a.z))) / max(b.xy, vec2(1e-3));",
      "      return 1.0 - smoothstep(0.7, 1.0, length(q));",
      "   }",
      "   vec2 ab = a.zw - a.xy;",
      "   float t = clamp(dot(p - a.xy, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);",
      "   return 1.0 - smoothstep(b.x * 0.6, b.x, length(p - a.xy - ab * t));",
      "}",
      "vec4 gsStampTexel(vec4 s, vec2 p, int mat, int count) {",
      "   for (int i = 0; i < count; i++) {",
      "      vec4 a = gsPrimA(i), b = gsPrimB(i), c = gsPrimC(i);",
      "      float w = gsWeight(a, b, c, p);",
      "      if (w > 0.0) {",
      "         s.y = min(1.0, s.y + w * c.x * GS_WEAR[mat]);",
      "         s.z = max(s.z, w);",
      "      }",
      "   }",
      "   return s;",
      "}",
      "vec4 gsDecayTexel(vec4 s, int mat, float dt, vec4 weather) {",
      "   s.y *= exp(-dt / GS_TAU[mat]);",
      "   s.z *= exp(-dt / 3.0);",
      "   return s;",
      "}",
      "");

   // ------------------------------------------------------------------------------------------------ state (game thread)

   private static final long NONE = Long.MIN_VALUE;
   private static int wcx = Integer.MIN_VALUE, wcy;
   private static final long[] slotKey = new long[SLOTS * SLOTS];
   private static final boolean[] slotLive = new boolean[SLOTS * SLOTS]; // holds anything (stamped or loaded)
   private static final boolean[] slotModified = new boolean[SLOTS * SLOTS]; // stamped since its last save / load
   private static final boolean[] slotPending = new boolean[SLOTS * SLOTS]; // changed since its last re-bake / listener call
   private static final long[] slotRebakeMs = new long[SLOTS * SLOTS];
   private static final byte[][] slotMat = new byte[SLOTS * SLOTS][];
   private static final long[] slotMatMs = new long[SLOTS * SLOTS];
   private static final int[] slotLoadedFrame = new int[SLOTS * SLOTS];
   private static int frame;
   private static boolean activeNow, wasActive, windowMoved;
   private static String saveId;
   private static long lastSaveMs;
   private static double lastDecayHours = -1.0;
   private static final AtomicInteger inFlight = new AtomicInteger();
   public static long frames, primsTotal, primsDropped, cellsTotal, dispatches, decayPasses, decayCells, rebakesAsked, clears, readbacks, chunksSaved,
      chunksLoaded, loadsDropped, uploads;
   public static long gameNs, sourcesNs, rigNs, binNs, batches;
   private static long lastStampNs, lastWeatherMs;
   private static final long STAMP_PERIOD_NS = Config.DEV_GROUND_STATE_HZ <= 0 ? 0L : 1_000_000_000L / Config.DEV_GROUND_STATE_HZ;

   static {
      java.util.Arrays.fill(slotKey, NONE);
   }

   static int slot(int cx, int cy) {
      return (cx & (SLOTS - 1)) + (cy & (SLOTS - 1)) * SLOTS;
   }

   static long key(int cx, int cy) {
      return ((long)cx << 32) ^ (cy & 0xFFFFFFFFL);
   }

   private static int keyX(long k) {
      return (int)(k >> 32);
   }

   private static int keyY(long k) {
      return (int)k;
   }

   private static volatile boolean failed;

   static boolean supported() {
      return GroundBake.supported() && !failed;
   }

   /** Ground.frameStart (game thread). */
   static void frameStart(int playerIndex) {
      if (playerIndex != 0) {
         return; // (split screen: the map follows player 1)
      }
      frame++;
      boolean rigs = (Config.DEV_GROUND_STATE_TEST || Config.DEV_GROUND_WALKERS > 0) && !Ground.offHalf(); // (the rigs follow the cost alternation)
      boolean want = usedFrame >= frame - 2 || Ground.on(Ground.K_TRACKS) || rigs;
      want &= supported();
      zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.players[0];
      IsoCell cell = IsoWorld.instance.currentCell;
      if (!want || pl == null || cell == null) {
         nP = 0;
         if (wasActive) {
            wasActive = false;
            activeNow = false;
            BIND_ON = false;
            Frame f = alloc();
            readbackModified(f, false);
            submit(f);
         } else if (inFlight.get() > 0) {
            submit(alloc()); // the render thread polls the read-backs' fences
         }
         return;
      }
      long t0 = System.nanoTime();
      frames++;
      if (!wasActive) {
         wasActive = true;
         Log.info("ground state: on (" + TN + "^2 RGBA8 over " + SQUARES + " squares, " + TPS + " texels a square, rules " + rules.id() + ", persist "
            + Config.GROUND_TRACKS_PERSIST + ")");
      }
      activeNow = true;
      BIND_ON = true;
      Frame f = alloc();
      String sid = zombie.core.Core.gameMode + "/" + zombie.core.Core.gameSaveWorld;
      if (!sid.equals(saveId)) {
         if (saveId != null) {
            readbackModified(f, true); // the old save's chunks, then everything cleared
         }
         f.clearAll = true;
         java.util.Arrays.fill(slotKey, NONE);
         java.util.Arrays.fill(slotLive, false);
         java.util.Arrays.fill(slotModified, false);
         java.util.Arrays.fill(slotPending, false);
         java.util.Arrays.fill(slotMat, null);
         LOADS.clear();
         wcx = Integer.MIN_VALUE;
         lastDecayHours = -1.0;
         saveId = sid;
      }
      window(f, pl.getX(), pl.getY());
      long nowMs = System.currentTimeMillis();
      if (nowMs - lastWeatherMs >= 1000L) {
         lastWeatherMs = nowMs;
         weather();
      }
      applyLoads(f, nowMs);
      boolean inst = Config.INSTRUMENT;
      long ta = inst ? System.nanoTime() : 0L;
      if (SOURCES.on) {
         GroundMovers.wantEvents();
         sources();
      }
      long tb = inst ? System.nanoTime() : 0L;
      if (Config.DEV_GROUND_WALKERS > 0 && rigs) {
         walkers(pl, Config.DEV_GROUND_WALKERS);
      }
      if (Config.DEV_GROUND_STATE_TEST && rigs && frame % 60 == 0) {
         testPattern(pl);
      }
      long tc = inst ? System.nanoTime() : 0L;
      // the stamps go to the GPU in batches (devGroundStateHz a second, or a half-full list): a dispatch has a fixed cost and
      // nobody sees a print 30 ms late (the bake re-bakes at most every 300 ms, the blades read the map as it is)
      long nowNs = tc != 0L ? tc : System.nanoTime();
      if (nP > 0 && (nP >= MAX_PRIMS / 2 || nowNs - lastStampNs >= STAMP_PERIOD_NS)) {
         lastStampNs = nowNs;
         bin(f, nowMs);
         nP = 0; // (cleared after binning: a feature's frame hook queues before this turn)
         batches++;
      }
      if (inst) {
         long td = System.nanoTime();
         sourcesNs += tb - ta;
         rigNs += tc - tb;
         binNs += td - tc;
      }
      decay(f, nowMs);
      changes(nowMs);
      if (Config.GROUND_TRACKS_PERSIST && nowMs - lastSaveMs > 30_000L) {
         lastSaveMs = nowMs;
         readbackModified(f, false);
      }
      f.ox = wcx * CS;
      f.oy = wcy * CS;
      f.tick = frame;
      f.rules = rules;
      if (f.nCells > 0 || f.nDecay > 0 || f.nClear > 0 || f.nLoad > 0 || f.nRead > 0 || f.clearAll || windowMoved || inFlight.get() > 0) {
         windowMoved = false;
         submit(f);
      } else {
         release(f);
      }
      gameNs += System.nanoTime() - t0;
   }

   private static void submit(Frame f) {
      f.on = activeNow;
      SpriteRenderer.instance.drawGeneric(f);
   }

   /** Recentre on the player's chunk when it strays 3 chunks from the middle; slots changing hands are saved and cleared. */
   private static void window(Frame f, float px, float py) {
      int pcx = (int)Math.floor(px / CS), pcy = (int)Math.floor(py / CS);
      int ncx = pcx - SLOTS / 2, ncy = pcy - SLOTS / 2;
      if (wcx != Integer.MIN_VALUE && Math.abs(ncx - wcx) < 3 && Math.abs(ncy - wcy) < 3) {
         return;
      }
      wcx = ncx;
      wcy = ncy;
      windowMoved = true;
      for (int cy = wcy; cy < wcy + SLOTS; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS; cx++) {
            int s = slot(cx, cy);
            long k = key(cx, cy);
            if (slotKey[s] == k) {
               continue;
            }
            if (slotLive[s]) {
               if (slotModified[s] && Config.GROUND_TRACKS_PERSIST && slotKey[s] != NONE) {
                  f.read(s, slotKey[s]);
               }
               f.clear[f.nClear++] = s;
               clears++;
            }
            slotKey[s] = k;
            slotLive[s] = false;
            slotModified[s] = false;
            slotPending[s] = false;
            slotMat[s] = null;
            if (Config.GROUND_TRACKS_PERSIST && saveId != null) {
               load(cx, cy);
            }
         }
      }
   }

   /** Every modified live slot read back (and saved); with {@code all} the slots are forgotten too. */
   private static void readbackModified(Frame f, boolean all) {
      if (!Config.GROUND_TRACKS_PERSIST || saveId == null) {
         return;
      }
      for (int s = 0; s < SLOTS * SLOTS; s++) {
         if (slotLive[s] && slotModified[s] && slotKey[s] != NONE) {
            f.read(s, slotKey[s]);
            slotModified[s] = false;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ surfaces

   /** The surface of each of a chunk's 64 squares at level 0 (GroundTiles.surface; cached 5 s: snow comes and goes). */
   static byte[] surfaces(int cx, int cy, long nowMs) {
      int s = slot(cx, cy);
      byte[] m = slotMat[s];
      if (m != null && nowMs - slotMatMs[s] < 5000L && slotKey[s] == key(cx, cy)) {
         return m;
      }
      if (m == null) {
         m = new byte[64];
      }
      if (testSurface != null) {
         for (int q = 0; q < 64; q++) {
            m[q] = (byte)testSurface.applyAsInt(cx * CS + (q & 7), cy * CS + (q >> 3));
         }
         slotMat[s] = m;
         slotMatMs[s] = nowMs;
         return m;
      }
      IsoCell cell = IsoWorld.instance.getCell();
      boolean snowAnywhere = zombie.iso.fboRenderChunk.FBORenderSnow.getInstance().isSnowAnywhere();
      for (int y = 0; y < CS; y++) {
         for (int x = 0; x < CS; x++) {
            int wx = cx * CS + x, wy = cy * CS + y;
            IsoGridSquare sq = cell == null ? null : cell.getGridSquare(wx, wy, 0);
            boolean snow = snowAnywhere && cell != null && sq != null && cell.gridSquareIsSnow(wx, wy, 0);
            m[x + y * CS] = (byte)(sq == null ? GroundTiles.SURF_NONE : GroundTiles.surface(sq, snow));
         }
      }
      if (slotKey[s] == key(cx, cy)) {
         slotMat[s] = m;
         slotMatMs[s] = nowMs;
      }
      return m;
   }

   /** Tests: the surface of world square (x, y) instead of the world's. */
   static java.util.function.IntBinaryOperator testSurface;

   /** Tests: a frame as frameStart builds it (window round (px, py), {@code prims} adds primitives, binned), no GL. */
   static Frame testFrame(float px, float py, Runnable prims) {
      frame++;
      activeNow = true;
      saveId = null;
      Frame f = new Frame();
      window(f, px, py);
      nP = 0;
      prims.run();
      bin(f, 0L);
      f.ox = wcx * CS;
      f.oy = wcy * CS;
      return f;
   }

   /** Tests: chunk (cx, cy)'s surface squares into the frame's decay list with {@code dt}. */
   static void testDecay(Frame f, int cx, int cy, float dt) {
      decayChunk(f, cx, cy, dt, 0L);
   }

   // ------------------------------------------------------------------------------------------------ primitives

   private static final double[] P = new double[MAX_PRIMS * 12];
   private static final double[] PB = new double[MAX_PRIMS * 4];
   private static int nP;

   /**
    * Game thread, during Ground.frameStart's turn: one primitive (layout in the class comment; positions in world squares)
    * with its bounding box (squares). {@code zwPoint}: a.zw is a second point (lines) rather than a direction. False when
    * the frame's list is full.
    */
   public static boolean prim(double ax, double ay, double az, double aw, float b0, float b1, float b2, float b3, float c0, float c1, float c2, float kind,
                              boolean zwPoint, double x0, double y0, double x1, double y1) {
      if (!activeNow) {
         return false;
      }
      if (nP >= MAX_PRIMS) {
         primsDropped++;
         return false;
      }
      int o = nP * 12;
      P[o] = ax;
      P[o + 1] = ay;
      P[o + 2] = az;
      P[o + 3] = aw;
      P[o + 4] = b0;
      P[o + 5] = b1;
      P[o + 6] = b2;
      P[o + 7] = b3;
      P[o + 8] = c0;
      P[o + 9] = c1;
      P[o + 10] = c2;
      P[o + 11] = zwPoint ? -1.0 - kind : kind; // (the sign carries zwPoint to the binner; the GPU sees kind)
      int b = nP * 4;
      PB[b] = x0;
      PB[b + 1] = y0;
      PB[b + 2] = x1;
      PB[b + 3] = y1;
      nP++;
      return true;
   }

   private static float strength() {
      return SOURCES.strengthPct / 100F;
   }

   /** A wheel's line this frame: half width, depth (before the surface's sink), berm height, slip 0..1. */
   public static void wheel(double x0, double y0, double x1, double y1, float half, float depth, float berm, float slip) {
      float s = strength();
      double m = half + 0.3 * (1.0 + slip);
      prim(x0, y0, x1, y1, half, depth * s, berm * s, slip, 1F, 0.1F, 1F, 0F, true, Math.min(x0, x1) - m, Math.min(y0, y1) - m, Math.max(x0, x1) + m,
         Math.max(y0, y1) + m);
   }

   /** A print: an ellipse at (x, y) heading (fx, fy) (unit), half length / width, depth, berm, wear, heel-toe 0..1. */
   public static void ellipse(double x, double y, float fx, float fy, float len, float wid, float depth, float berm, float wear, float heelToe) {
      float s = strength();
      double m = len + 0.25;
      prim(x, y, fx, fy, len, wid, depth * s, berm * s, wear, 0.06F, heelToe, 1F, false, x - m, y - m, x + m, y + m);
   }

   /** A drag mark: a shallow wide line. */
   public static void drag(double x0, double y0, double x1, double y1, float half) {
      float s = strength();
      double m = half + 0.2;
      prim(x0, y0, x1, y1, half, 0.012F * s, 0.004F * s, 0F, 2.5F, 0.08F, 0F, 2F, true, Math.min(x0, x1) - m, Math.min(y0, y1) - m, Math.max(x0, x1) + m,
         Math.max(y0, y1) + m);
   }

   /** GroundMovers' events as primitives (the numbers of the tracks branch). */
   private static void sources() {
      float[] ev = GroundMovers.EV;
      Sources src = SOURCES;
      for (int i = 0, n = GroundMovers.nEvents; i < n; i++) {
         int o = i * GroundMovers.EV_FLOATS;
         int kind = (int)ev[o], who = (int)ev[o + 1];
         o += 2;
         switch (kind) {
            case GroundMovers.WHEEL: {
               if (!src.vehicles) {
                  break;
               }
               float mass = Math.max(300F, ev[o + 5]);
               float depth = Math.min(0.2F, 0.10F * (float)Math.sqrt(mass / 1400F));
               float half = mass > 2500F ? 0.13F : 0.10F;
               float slip = ev[o + 4];
               wheel(ev[o], ev[o + 1], ev[o + 2], ev[o + 3], half, depth * (1F + 0.6F * slip), 0.035F * (1F + slip), slip);
               break;
            }
            case GroundMovers.FOOT: {
               if (!src.feet) {
                  break;
               }
               float ux = ev[o + 2], uy = ev[o + 3], size = ev[o + 5];
               float side = ev[o + 4] * 0.09F * size;
               float depth = who == GroundMovers.PLAYER ? 0.05F : who == GroundMovers.ANIMAL ? 0.04F : 0.045F;
               ellipse(ev[o] - uy * side, ev[o + 1] + ux * side, ux, uy, 0.14F * size, 0.055F * size, depth, 0.012F, 1F, 1F);
               break;
            }
            case GroundMovers.DRAG:
               if (src.feet) {
                  drag(ev[o], ev[o + 1], ev[o + 2], ev[o + 3], 0.22F);
               }
               break;
            case GroundMovers.BODY:
               if (src.bodies) {
                  float size = ev[o + 4];
                  ellipse(ev[o], ev[o + 1], ev[o + 2], ev[o + 3], 0.85F * size, 0.28F * size, 0.035F, 0.01F, 1F, 0F);
               }
               break;
            default:
               break;
         }
      }
   }

   private static float[] walkerState = new float[0];

   /** devGroundWalkers: N synthetic walkers circling the player, a print per 0.42 squares (the stamping cost rig). */
   private static void walkers(zombie.characters.IsoPlayer pl, int n) {
      if (walkerState.length < n * 3) {
         walkerState = new float[n * 3];
      }
      double t = System.nanoTime() / 1e9;
      for (int i = 0; i < n; i++) {
         double r = 3.0 + (i % 20) * 0.9;
         double a = t * (1.2 / r) * 1.4 + i * 2.399963;
         float x = (float)(pl.getX() + Math.cos(a) * r), y = (float)(pl.getY() + Math.sin(a) * r);
         int o = i * 3;
         float lx = walkerState[o], ly = walkerState[o + 1];
         float dx = x - lx, dy = y - ly, d2 = dx * dx + dy * dy;
         if (lx == 0F && ly == 0F || d2 > 9F) {
            walkerState[o] = x;
            walkerState[o + 1] = y;
            continue;
         }
         if (d2 < GroundMovers.STRIDE_WALK * GroundMovers.STRIDE_WALK) {
            continue;
         }
         float d = (float)Math.sqrt(d2), ux = dx / d, uy = dy / d;
         walkerState[o + 2] = -walkerState[o + 2] + (walkerState[o + 2] == 0F ? 1F : 0F);
         float side = (walkerState[o + 2] > 0F ? 0.09F : -0.09F);
         ellipse(x - uy * side, y + ux * side, ux, uy, 0.14F, 0.055F, 0.045F, 0.012F, 1F, 1F);
         walkerState[o] = x;
         walkerState[o + 1] = y;
      }
   }

   private static void testPattern(zombie.characters.IsoPlayer pl) {
      float x = (float)Math.floor(pl.getX()) + 0.5F, y = (float)Math.floor(pl.getY()) + 0.5F;
      for (int i = -6; i <= 6; i += 3) {
         wheel(x - 6, y + i, x + 6, y + i, 0.12F, 0.1F, 0.04F, 0F);
         ellipse(x + i, y - 8, 1F, 0F, 0.2F + 0.05F * (i + 6), 0.1F, 0.05F, 0.012F, 1F, 1F);
      }
   }

   // ------------------------------------------------------------------------------------------------ binning

   private static final int[] cellOf = new int[SQUARES * SQUARES];
   private static final int[] cellFrame = new int[SQUARES * SQUARES];
   private static final int[] pairCell = new int[MAX_PAIRS], pairPrim = new int[MAX_PAIRS];

   /** The primitives binned per square: cells (one work group each) and their primitive lists, counting-sorted by cell. */
   private static void bin(Frame f, long nowMs) {
      int ox = wcx * CS, oy = wcy * CS;
      int lastCx = Integer.MIN_VALUE, lastCy = Integer.MIN_VALUE;
      byte[] lastMats = null;
      int nPairs = 0;
      f.nPrims = 0;
      f.nCells = 0;
      for (int i = 0; i < nP; i++) {
         int b = i * 4;
         int x0 = (int)Math.floor(PB[b]), y0 = (int)Math.floor(PB[b + 1]), x1 = (int)Math.floor(PB[b + 2]), y1 = (int)Math.floor(PB[b + 3]);
         x0 = Math.max(x0, ox);
         y0 = Math.max(y0, oy);
         x1 = Math.min(x1, ox + SQUARES - 1);
         y1 = Math.min(y1, oy + SQUARES - 1);
         if (x0 > x1 || y0 > y1 || (x1 - x0 + 1) * (y1 - y0 + 1) > 64) {
            continue;
         }
         int pi = -1;
         for (int sy = y0; sy <= y1; sy++) {
            for (int sx = x0; sx <= x1; sx++) {
               int cx = sx >> 3, cy = sy >> 3;
               if (cx != lastCx || cy != lastCy) { // (a primitive's squares are mostly in one chunk)
                  lastCx = cx;
                  lastCy = cy;
                  lastMats = surfaces(cx, cy, nowMs);
               }
               int mat = lastMats[(sx & 7) + (sy & 7) * CS];
               if (mat == GroundTiles.SURF_NONE) {
                  continue;
               }
               int li = (sx - ox) + (sy - oy) * SQUARES;
               int ci;
               if (cellFrame[li] != frame) {
                  if (f.nCells >= MAX_CELLS) {
                     continue;
                  }
                  cellFrame[li] = frame;
                  ci = f.nCells++;
                  cellOf[li] = ci;
                  int o = ci * 4;
                  f.cells[o] = ((sx * TPS) & (TN - 1)) | (((sy * TPS) & (TN - 1)) << 16);
                  f.cells[o + 1] = (sx - ox + 32768) | ((sy - oy + 32768) << 16);
                  f.cells[o + 2] = 0;
                  f.cells[o + 3] = mat << 24;
                  int s = slot(cx, cy);
                  slotLive[s] = true;
                  slotModified[s] = true;
                  slotPending[s] = true;
               } else {
                  ci = cellOf[li];
               }
               if (nPairs >= MAX_PAIRS) {
                  continue;
               }
               if (pi < 0) {
                  pi = f.nPrims++;
                  int o = i * 12, q = pi * 12;
                  double k = P[o + 11];
                  boolean zwPoint = k < 0.0;
                  f.prims[q] = (float)(P[o] - ox);
                  f.prims[q + 1] = (float)(P[o + 1] - oy);
                  f.prims[q + 2] = zwPoint ? (float)(P[o + 2] - ox) : (float)P[o + 2];
                  f.prims[q + 3] = zwPoint ? (float)(P[o + 3] - oy) : (float)P[o + 3];
                  for (int j = 4; j < 11; j++) {
                     f.prims[q + j] = (float)P[o + j];
                  }
                  f.prims[q + 11] = (float)(zwPoint ? -1.0 - k : k);
               }
               pairCell[nPairs] = ci;
               pairPrim[nPairs] = pi;
               nPairs++;
            }
         }
      }
      primsTotal += f.nPrims;
      cellsTotal += f.nCells;
      int[] cnt = f.count;
      java.util.Arrays.fill(cnt, 0, f.nCells, 0);
      for (int k = 0; k < nPairs; k++) {
         cnt[pairCell[k]]++;
      }
      int run = 0;
      for (int c = 0; c < f.nCells; c++) {
         f.cells[c * 4 + 2] = run;
         f.cells[c * 4 + 3] |= cnt[c] & 0xFFFF;
         int n = cnt[c];
         cnt[c] = run;
         run += n;
      }
      for (int k = 0; k < nPairs; k++) {
         f.index[cnt[pairCell[k]]++] = pairPrim[k];
      }
      f.nIndex = nPairs;
   }

   // ------------------------------------------------------------------------------------------------ weather, decay, changes

   private static void weather() {
      zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
      if (cm == null) {
         return;
      }
      boolean snowing = cm.getPrecipitationIsSnow();
      WEATHER[0] = snowing ? 0F : clamp01(cm.getRainIntensity());
      WEATHER[1] = snowing ? clamp01(cm.getSnowIntensity()) : 0F;
      WEATHER[2] = cm.getTemperature();
      WEATHER[3] = clamp01(cm.getWindIntensity());
   }

   static float clamp01(float v) {
      return v < 0F ? 0F : v > 1F ? 1F : v;
   }

   private static double worldHours() {
      try {
         return zombie.GameTime.getInstance().getWorldAgeHours();
      } catch (Throwable t) {
         return 0.0;
      }
   }

   /** A cell for each surface square of chunk (cx, cy) into the frame's decay list, with its own dt. */
   private static void decayChunk(Frame f, int cx, int cy, float dt, long nowMs) {
      byte[] mats = surfaces(cx, cy, nowMs);
      int ox = wcx * CS, oy = wcy * CS;
      int dtBits = Float.floatToRawIntBits(dt);
      for (int q = 0; q < 64; q++) {
         if (mats[q] == GroundTiles.SURF_NONE || f.nDecay >= MAX_DECAY) {
            continue;
         }
         int sx = cx * CS + (q & 7), sy = cy * CS + (q >> 3);
         int o = f.nDecay * 4;
         f.decay[o] = ((sx * TPS) & (TN - 1)) | (((sy * TPS) & (TN - 1)) << 16);
         f.decay[o + 1] = (sx - ox + 32768) | ((sy - oy + 32768) << 16);
         f.decay[o + 2] = dtBits;
         f.decay[o + 3] = mats[q] << 24;
         f.nDecay++;
      }
   }

   private static void decay(Frame f, long nowMs) {
      double hours = worldHours();
      Rules r = rules;
      if (lastDecayHours < 0.0 || hours < lastDecayHours) {
         lastDecayHours = hours;
         return;
      }
      if (hours - lastDecayHours < r.decayEveryHours()) {
         return;
      }
      float dt = r.decayDt(hours - lastDecayHours);
      lastDecayHours = hours;
      System.arraycopy(WEATHER, 0, f.weather, 0, 4);
      int before = f.nDecay;
      for (int cy = wcy; cy < wcy + SLOTS; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS; cx++) {
            int s = slot(cx, cy);
            if (!slotLive[s] || slotLoadedFrame[s] == frame) {
               continue; // (a chunk loaded this frame has its own catch-up decay in the list)
            }
            decayChunk(f, cx, cy, dt, nowMs);
            slotPending[s] = true;
            slotModified[s] = true;
         }
      }
      decayPasses++;
      decayCells += f.nDecay - before;
   }

   /** Changed chunks: listeners, re-bakes (throttled per chunk). */
   private static void changes(long nowMs) {
      Rules r = rules;
      boolean viewRebake = Config.DEV_GROUND_VIEW == 3; // (the state view draws the map into the bake)
      boolean full = rebakeOnChange || fullRebakeFrame >= frame - 2;
      boolean rebake = r.rebakes() || full || viewRebake;
      if (!rebake && LISTENERS.isEmpty()) {
         return;
      }
      long every = Math.max(CHANGE_MS, r.rebakeMs());
      IsoCell cell = IsoWorld.instance.getCell();
      int budget = 4;
      for (int cy = wcy; cy < wcy + SLOTS && budget > 0; cy++) {
         for (int cx = wcx; cx < wcx + SLOTS && budget > 0; cx++) {
            int s = slot(cx, cy);
            if (!slotPending[s] || nowMs - slotRebakeMs[s] < every) {
               continue;
            }
            slotPending[s] = false;
            slotRebakeMs[s] = nowMs;
            for (ChangeListener l : LISTENERS) {
               try {
                  l.changed(cx, cy);
               } catch (Throwable t) {
                  Log.warn("ground state: listener failed and is off: " + t);
                  LISTENERS.remove(l);
               }
            }
            if (rebake) {
               IsoChunk c = cell == null ? null : cell.getChunk(cx, cy);
               if (c != null) {
                  if (full || viewRebake && !r.rebakes()) {
                     GroundBake.rebakeChunk(cx, cy, "tracks", true);
                  } else {
                     c.invalidateRenderChunkLevel(0, 1L); // DIRTY_BLOOD: a floor decal changed
                  }
                  rebakesAsked++;
                  budget--;
               }
            }
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the frame's GPU work

   private static final ArrayList<Frame> POOL = new ArrayList<>();

   private static Frame alloc() {
      synchronized (POOL) {
         if (!POOL.isEmpty()) {
            Frame f = POOL.remove(POOL.size() - 1);
            f.reset();
            return f;
         }
      }
      return new Frame();
   }

   static void release(Frame f) {
      synchronized (POOL) {
         if (POOL.size() < 6) {
            POOL.add(f);
         }
      }
   }

   static final class Frame extends TextureDraw.GenericDrawer {
      final int[] cells = new int[MAX_CELLS * 4], index = new int[MAX_PAIRS], count = new int[MAX_CELLS];
      final float[] prims = new float[MAX_PRIMS * 12];
      final int[] decay = new int[MAX_DECAY * 4];
      final float[] weather = new float[4];
      final int[] clear = new int[SLOTS * SLOTS];
      final int[] readSlot = new int[SLOTS * SLOTS];
      final long[] readKey = new long[SLOTS * SLOTS];
      final int[] loadSlot = new int[MAX_LOADS_PER_FRAME];
      final ByteBuffer loadBuf = BufferUtils.createByteBuffer(MAX_LOADS_PER_FRAME * CHUNK_TEX * CHUNK_TEX * 4);
      int nCells, nPrims, nIndex, nDecay, nClear, nRead, nLoad, ox, oy, tick;
      boolean clearAll, on;
      String readDir;
      double readHours;
      Rules rules;

      void reset() {
         nCells = nPrims = nIndex = nDecay = nClear = nRead = nLoad = 0;
         clearAll = false;
         readDir = null;
         loadBuf.clear();
      }

      void read(int s, long k) {
         if (nRead >= readSlot.length || saveId == null) {
            return;
         }
         readSlot[nRead] = s;
         readKey[nRead] = k;
         nRead++;
         readDir = dir(saveId).getPath();
         readHours = worldHours();
      }

      @Override
      public void render() {
         renderFrame(this);
      }

      @Override
      public void postRender() {
         release(this);
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static boolean tried;
   private static volatile int tex;
   private static int prog, progRulesHash, cellsBuf, decayBuf, primsBuf, indexBuf, uploadPbo;
   private static int uMode, uWeather, uTick;
   private static boolean canRead;
   private static volatile int rOx, rOy;
   static volatile boolean BIND_ON;
   private static final IntBuffer INTS = BufferUtils.createIntBuffer(Math.max(MAX_DECAY * 4, MAX_PAIRS));
   private static final FloatBuffer FLOATS = BufferUtils.createFloatBuffer(MAX_PRIMS * 12);
   private static final ByteBuffer REST = BufferUtils.createByteBuffer(4).put(REST_R).put((byte)0).put((byte)0).put((byte)0).flip();

   private static final class Pending {
      long fence;
      int pbo, n;
      ByteBuffer mapped; // persistent read mapping (client storage): the IO thread copies out of it, the render thread never does
      final long[] keys = new long[READBACK_BATCH];
      String dir;
      double hours;
   }

   private static final ArrayList<Pending> PENDING = new ArrayList<>();
   private static final ArrayList<Pending> FREE_PENDING = new ArrayList<>();

   private static boolean init() {
      if (tried) {
         return tex != 0;
      }
      tried = true;
      org.lwjgl.opengl.GLCapabilities caps = GL.getCapabilities();
      if (!caps.OpenGL44) {
         failed = true;
         Log.warn("ground state: needs OpenGL 4.4 (compute, image stores, texture clears), off");
         return false;
      }
      canRead = caps.OpenGL45;
      if (!canRead) {
         Log.warn("ground state: no OpenGL 4.5 (glGetTextureSubImage): tracks are not kept on disk");
      }
      int t = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
      GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, TN, TN);
      GL44.glClearTexImage(t, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, REST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      cellsBuf = ssbo((long)MAX_CELLS * 16);
      decayBuf = ssbo((long)MAX_DECAY * 16);
      primsBuf = ssbo((long)MAX_PRIMS * 48);
      indexBuf = ssbo((long)MAX_PAIRS * 4);
      uploadPbo = GL15.glGenBuffers();
      tex = t;
      Log.info("ground state: map ready (" + TN + "^2 RGBA8, " + (canRead ? "persistence by GPU read-back" : "no read-back") + ")");
      return true;
   }

   private static int ssbo(long bytes) {
      int b = GL15.glGenBuffers();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, b);
      GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      return b;
   }

   /** The compute program of {@code r} (rebuilt when the rules change). */
   private static boolean program(Rules r) {
      int h = System.identityHashCode(r) ^ r.id().hashCode();
      if (prog != 0 && h == progRulesHash) {
         return true;
      }
      if (prog != 0) {
         GL20.glDeleteProgram(prog);
         prog = 0;
      }
      progRulesHash = h;
      prog = RipplesGpu.program(computeSource(r), "ground state compute (" + r.id() + ")");
      if (prog == 0) {
         Log.warn("ground state: rules " + r.id() + " did not compile" + (r instanceof DefaultRules ? ", off" : ", back to the default rules"));
         if (r instanceof DefaultRules) {
            failed = true;
         } else {
            rules = new DefaultRules();
         }
         return false;
      }
      uMode = GL20.glGetUniformLocation(prog, "gsMode");
      uWeather = GL20.glGetUniformLocation(prog, "gsWeather");
      uTick = GL20.glGetUniformLocation(prog, "gsTick");
      return true;
   }

   /** The whole compute source for rules {@code r} (tests link it). */
   static String computeSource(Rules r) {
      return "#version 430\n#define GS_TPS " + TPS + ".0\n#define GS_TN " + TN + "\n" + (r.relax() ? "#define GS_RELAX 1\n" : "") + GLSL_CS_HEAD + r.glsl() + "\n"
         + GLSL_CS_MAIN;
   }

   static final String GLSL_CS_HEAD = String.join("\n",
      "// pzopt.GroundState: one work group a square (16 x 16 texels), cells listed by the game thread. gsMode 0 stamp, 1 decay",
      "// (dt per cell in cell.z), 2 relax. The map is toroidal: texel (absolute square * GS_TPS + local) & (GS_TN - 1).",
      "layout(local_size_x = 16, local_size_y = 16) in;",
      "layout(rgba8, binding = 0) uniform image2D gsState;",
      "layout(std430, binding = 1) readonly buffer GsCells { uvec4 gsCells[]; };",
      "layout(std430, binding = 2) readonly buffer GsPrims { vec4 gsPrims[]; };",
      "layout(std430, binding = 3) readonly buffer GsIndex { uint gsIndex[]; };",
      "uniform int gsMode;",
      "uniform vec4 gsWeather;",
      "uniform uint gsTick;",
      "int gsCellStart, gsCellCount;",
      "vec4 gsPrimA(int i) { return gsPrims[gsIndex[gsCellStart + i] * 3u]; }",
      "vec4 gsPrimB(int i) { return gsPrims[gsIndex[gsCellStart + i] * 3u + 1u]; }",
      "vec4 gsPrimC(int i) { return gsPrims[gsIndex[gsCellStart + i] * 3u + 2u]; }",
      "float gsHash(vec2 p) {",
      "   p = fract(p * vec2(443.897, 441.423));",
      "   p += dot(p, p.yx + 19.19);",
      "   return fract((p.x + p.y) * p.x);",
      "}",
      "// 8-bit state: h = (r * 255 - 192) / 510 squares (-0.376 .. +0.125); wear, freshness, slip as they are",
      "vec4 gsDecode(vec4 r) { return vec4((r.x * 255.0 - 192.0) / 510.0, r.yzw); }",
      "vec4 gsEncodeNearest(vec4 s) { return clamp(floor(vec4(s.x * 510.0 + 192.0, s.yzw * 255.0) + 0.5), 0.0, 255.0) / 255.0; }",
      "// stochastic rounding: slow decays move the 8-bit value on average instead of stalling on it",
      "vec4 gsEncode(vec4 s, vec2 seed) {",
      "   vec4 v = vec4(s.x * 510.0 + 192.0, s.yzw * 255.0);",
      "   vec4 n = vec4(gsHash(seed + 0.17), gsHash(seed + 3.31), gsHash(seed + 7.73), gsHash(seed + 11.9));",
      "   return clamp(floor(v + n), 0.0, 255.0) / 255.0;",
      "}",
      "// ---- the rules",
      "");

   static final String GLSL_CS_MAIN = String.join("\n",
      "// ---- the framework's main",
      "#ifdef GS_RELAX",
      "shared float gsSh[18 * 18];",
      "#endif",
      "void main() {",
      "   uvec4 cell = gsCells[gl_WorkGroupID.x];",
      "   ivec2 base = ivec2(int(cell.x & 0xFFFFu), int(cell.x >> 16));",
      "   vec2 sq = vec2(float(int(cell.y & 0xFFFFu) - 32768), float(int(cell.y >> 16) - 32768));",
      "   gsCellStart = int(cell.z);",
      "   gsCellCount = int(cell.w & 0xFFFFu);",
      "   int mat = int(cell.w >> 24);",
      "   ivec2 l = ivec2(gl_LocalInvocationID.xy);",
      "   ivec2 t = (base + l) & (GS_TN - 1);",
      "   vec2 p = sq + (vec2(l) + 0.5) / GS_TPS;",
      "   vec2 seed = vec2(t) + float(gsTick & 1023u) * 0.618;",
      "   if (gsMode == 2) {",
      "#ifdef GS_RELAX",
      "      for (int k = int(gl_LocalInvocationIndex); k < 18 * 18; k += 256) {",
      "         ivec2 o = ivec2(k % 18, k / 18) - 1;",
      "         gsSh[k] = gsDecode(imageLoad(gsState, (base + o) & (GS_TN - 1))).x;",
      "      }",
      "      barrier();",
      "      if (mat == 0) return;",
      "      vec4 s = gsDecode(imageLoad(gsState, t));",
      "      int c = (l.y + 1) * 18 + l.x + 1;",
      "      float h = gsRelaxHeight(gsSh[c], vec4(gsSh[c + 1], gsSh[c - 1], gsSh[c + 18], gsSh[c - 18]), mat);",
      "      if (abs(h - s.x) > 1e-5) {",
      "         s.x = h;",
      "         imageStore(gsState, t, gsEncode(s, seed));",
      "      }",
      "#endif",
      "      return;",
      "   }",
      "   vec4 s = gsDecode(imageLoad(gsState, t));",
      "   vec4 n = gsMode == 0 ? gsStampTexel(s, p, mat, gsCellCount) : gsDecayTexel(s, mat, uintBitsToFloat(cell.z), gsWeather);",
      "   if (n != s) {",
      "      imageStore(gsState, t, gsMode == 0 ? gsEncodeNearest(n) : gsEncode(n, seed));",
      "   }",
      "}",
      "");

   private static void upload(int buf, int[] data, int n) {
      INTS.clear();
      INTS.put(data, 0, Math.max(1, n)).flip();
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buf);
      GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, INTS);
   }

   static void renderFrame(Frame f) {
      if (failed || !init()) {
         return;
      }
      GpuSections.markNow(Ground.section("ground.state"), false); // (render thread: issued in the GL stream now; begin/end would queue into the next draw list)
      try {
         pollReadbacks();
         rOx = f.ox;
         rOy = f.oy;
         int t = tex;
         if (f.nRead > 0 && canRead) {
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42.GL_PIXEL_BUFFER_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            issueReadbacks(f, t);
         }
         if (f.clearAll) {
            GL44.glClearTexImage(t, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, REST);
         }
         for (int i = 0; i < f.nClear; i++) {
            int s = f.clear[i];
            GL44.glClearTexSubImage(t, 0, (s & (SLOTS - 1)) * CHUNK_TEX, (s / SLOTS) * CHUNK_TEX, 0, CHUNK_TEX, CHUNK_TEX, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
               REST);
         }
         if (f.nLoad > 0) {
            f.loadBuf.flip();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, uploadPbo);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, f.loadBuf, GL15.GL_STREAM_DRAW);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            for (int i = 0; i < f.nLoad; i++) {
               int s = f.loadSlot[i];
               GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, (s & (SLOTS - 1)) * CHUNK_TEX, (s / SLOTS) * CHUNK_TEX, CHUNK_TEX, CHUNK_TEX, GL11.GL_RGBA,
                  GL11.GL_UNSIGNED_BYTE, (long)i * CHUNK_TEX * CHUNK_TEX * 4);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            uploads += f.nLoad;
         }
         Rules r = f.rules;
         if ((f.nCells > 0 || f.nDecay > 0) && r != null && program(r)) {
            GL20.glUseProgram(prog);
            GL42.glBindImageTexture(0, t, 0, false, 0, GL15.GL_READ_WRITE, GL11.GL_RGBA8);
            GL30.glUniform1ui(uTick, f.tick);
            if (f.nClear > 0 || f.nLoad > 0 || f.clearAll) {
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
            }
            if (f.nDecay > 0) {
               upload(decayBuf, f.decay, f.nDecay * 4);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, decayBuf);
               GL20.glUniform1i(uMode, 1);
               GL20.glUniform4f(uWeather, f.weather[0], f.weather[1], f.weather[2], f.weather[3]);
               GL43.glDispatchCompute(f.nDecay, 1, 1);
               dispatches++;
               if (r.relax()) {
                  GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                  GL20.glUniform1i(uMode, 2);
                  GL43.glDispatchCompute(f.nDecay, 1, 1);
               }
               GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            }
            if (f.nCells > 0) {
               upload(cellsBuf, f.cells, f.nCells * 4);
               upload(indexBuf, f.index, f.nIndex);
               FLOATS.clear();
               FLOATS.put(f.prims, 0, Math.max(12, f.nPrims * 12)).flip();
               GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, primsBuf);
               GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, FLOATS);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, cellsBuf);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, primsBuf);
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, indexBuf);
               GL20.glUniform1i(uMode, 0);
               GL43.glDispatchCompute(f.nCells, 1, 1);
               dispatches++;
               if (r.relax()) {
                  GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                  GL20.glUniform1i(uMode, 2);
                  GL43.glDispatchCompute(f.nCells, 1, 1);
               }
            }
            // the bake's and composite's samplers, the next frame's read-backs
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
         }
      } catch (Throwable e) {
         failed = true;
         Log.warn("ground state: GPU pass failed, off: " + e);
      } finally {
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
         for (int i = 1; i < 4; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
         }
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         GL20.glUseProgram(0);
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         GpuSections.markNow(Ground.section("ground.state"), true);
      }
   }

   private static int readFbo, readFboTex, readbackIssues, packProg, packOrgBuf, packBuf;
   private static boolean packFailed;
   static final int RB_GET = 0, RB_PIXELS = 1, RB_PACK = 2;

   /** The read-back's packing pass: each chunk's 128 x 128 texels into one buffer (one dispatch, then one buffer copy). */
   static final String GLSL_PACK = String.join("\n",
      "#version 430",
      "layout(local_size_x = 16, local_size_y = 16) in;",
      "layout(rgba8, binding = 0) readonly uniform image2D gsMap;",
      "layout(std430, binding = 1) readonly buffer GsOrg { ivec2 gsOrg[]; };",
      "layout(std430, binding = 2) writeonly buffer GsOut { uint gsOut[]; };",
      "void main() {",
      "   uint i = gl_WorkGroupID.z;",
      "   ivec2 t = ivec2(gl_GlobalInvocationID.xy);",
      "   gsOut[i * " + (CHUNK_TEX * CHUNK_TEX) + "u + uint(t.y) * " + CHUNK_TEX + "u + uint(t.x)] = packUnorm4x8(imageLoad(gsMap, gsOrg[i] + t));",
      "}",
      "");

   private static void issueReadbacks(Frame f, int t) {
      // devGroundReadback (A/B): pack (default) = a compute pass packs the chunks into a buffer, copied into the read-back
      // buffer: two calls a batch; get = glGetTextureSubImage and pixels = glReadPixels, one call a chunk, cost the render
      // thread ~1.6 ms a flush of ~30 chunks on NVIDIA's threaded driver (gc-statev2-cost4, gc-statev2-rb)
      String how = Config.DEV_GROUND_READBACK;
      int mode = "get".equals(how) ? RB_GET : "pixels".equals(how) ? RB_PIXELS : "alternate".equals(how) ? ((readbackIssues++ & 1) == 0 ? RB_PACK : RB_GET) : RB_PACK;
      if (mode == RB_PACK && !packReady()) {
         mode = RB_GET;
      }
      if (mode == RB_PIXELS && (readFbo == 0 || readFboTex != t)) {
         if (readFbo == 0) {
            readFbo = GL30.glGenFramebuffers();
         }
         readFboTex = t;
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
         GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t, 0);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      }
      String sec = Ground.section(mode == RB_PACK ? "ground.rb.pack" : mode == RB_PIXELS ? "ground.rb.pixels" : "ground.rb.get");
      GpuSections.markNow(sec, false);
      if (mode == RB_PIXELS) {
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
         GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
      }
      try {
         issueReadbacks(f, t, mode);
      } finally {
         if (mode == RB_PIXELS) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID); // (the game binds GL_FRAMEBUFFER: read = draw)
         }
         if (mode == RB_PACK) {
            GL20.glUseProgram(0);
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            zombie.core.ShaderHelper.forgetCurrentlyBound();
         }
         GpuSections.markNow(sec, true);
      }
   }

   private static boolean packReady() {
      if (packProg != 0) {
         return true;
      }
      if (packFailed) {
         return false;
      }
      packProg = RipplesGpu.program(GLSL_PACK, "ground state read-back pack");
      if (packProg == 0) {
         packFailed = true;
         Log.warn("ground state: the read-back pack pass did not compile, glGetTextureSubImage instead");
         return false;
      }
      packOrgBuf = ssbo((long)READBACK_BATCH * 8);
      packBuf = ssbo((long)READBACK_BATCH * CHUNK_TEX * CHUNK_TEX * 4);
      return true;
   }

   private static final java.nio.IntBuffer PACK_ORG = BufferUtils.createIntBuffer(READBACK_BATCH * 2);

   private static void issueReadbacks(Frame f, int t, int mode) {
      int i = 0;
      while (i < f.nRead) {
         Pending p;
         synchronized (FREE_PENDING) {
            p = FREE_PENDING.isEmpty() ? null : FREE_PENDING.remove(FREE_PENDING.size() - 1);
         }
         if (p == null) {
            p = new Pending();
            p.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, p.pbo);
            long size = (long)READBACK_BATCH * CHUNK_TEX * CHUNK_TEX * 4;
            int flags = GL30.GL_MAP_READ_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
            org.lwjgl.opengl.GL44.glBufferStorage(GL21.GL_PIXEL_PACK_BUFFER, size, flags | org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT);
            p.mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, size, flags);
         } else {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, p.pbo);
         }
         p.n = 0;
         p.dir = f.readDir;
         p.hours = f.readHours;
         if (mode == RB_PACK) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            PACK_ORG.clear();
            for (; i < f.nRead && p.n < READBACK_BATCH; i++) {
               int s = f.readSlot[i];
               PACK_ORG.put((s & (SLOTS - 1)) * CHUNK_TEX).put((s / SLOTS) * CHUNK_TEX);
               p.keys[p.n++] = f.readKey[i];
            }
            PACK_ORG.flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, packOrgBuf);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, PACK_ORG);
            GL20.glUseProgram(packProg);
            GL42.glBindImageTexture(0, t, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, packOrgBuf);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, packBuf);
            GL43.glDispatchCompute(CHUNK_TEX / 16, CHUNK_TEX / 16, p.n);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, packBuf);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, p.pbo);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, (long)p.n * CHUNK_TEX * CHUNK_TEX * 4);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
         }
         for (; i < f.nRead && p.n < READBACK_BATCH; i++) {
            int s = f.readSlot[i];
            if (mode == RB_PIXELS) {
               GL11.glReadPixels((s & (SLOTS - 1)) * CHUNK_TEX, (s / SLOTS) * CHUNK_TEX, CHUNK_TEX, CHUNK_TEX, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                  (long)p.n * CHUNK_TEX * CHUNK_TEX * 4);
            } else {
               GL45.glGetTextureSubImage(t, 0, (s & (SLOTS - 1)) * CHUNK_TEX, (s / SLOTS) * CHUNK_TEX, 0, CHUNK_TEX, CHUNK_TEX, 1, GL11.GL_RGBA,
                  GL11.GL_UNSIGNED_BYTE, CHUNK_TEX * CHUNK_TEX * 4, (long)p.n * CHUNK_TEX * CHUNK_TEX * 4);
            }
            p.keys[p.n++] = f.readKey[i];
         }
         GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
         p.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
         PENDING.add(p);
         inFlight.incrementAndGet();
         readbacks += p.n;
      }
   }

   /** Render thread: read-backs whose fence has passed (a zero-timeout check, never a wait) go to the IO thread. */
   private static void pollReadbacks() {
      for (int i = 0; i < PENDING.size(); i++) {
         Pending p = PENDING.get(i);
         int r = GL32.glClientWaitSync(p.fence, 0, 0L);
         if (r != GL32.GL_ALREADY_SIGNALED && r != GL32.GL_CONDITION_SATISFIED) {
            if (r == GL32.GL_WAIT_FAILED) {
               GL32.glDeleteSync(p.fence);
               PENDING.remove(i--);
               inFlight.decrementAndGet();
            }
            continue;
         }
         GL32.glDeleteSync(p.fence);
         PENDING.remove(i--);
         // the bytes stay in the persistent mapping; the IO thread copies them out and only then frees the buffer (no
         // 1 MB copy and allocation on the render thread: 188 us a flush, gc-statev2-cost3)
         final ByteBuffer b = p.mapped.duplicate();
         final Pending done = p;
         final int n = p.n;
         final long[] keys = java.util.Arrays.copyOf(p.keys, n);
         final File d = new File(p.dir);
         final double hours = p.hours;
         IO.execute(() -> {
            try {
               byte[][] raws = new byte[n][];
               try {
                  for (int k = 0; k < n; k++) {
                     raws[k] = new byte[CHUNK_TEX * CHUNK_TEX * 4];
                     b.position(k * CHUNK_TEX * CHUNK_TEX * 4);
                     b.get(raws[k]);
                  }
               } finally {
                  synchronized (FREE_PENDING) {
                     FREE_PENDING.add(done); // (the mapping is free again: the next read-back may land in it)
                  }
               }
               int[] xs = new int[n], ys = new int[n];
               for (int k = 0; k < n; k++) {
                  xs[k] = keyX(keys[k]);
                  ys[k] = keyY(keys[k]);
               }
               writeChunks(d, xs, ys, raws, hours);
            } finally {
               inFlight.decrementAndGet();
            }
         });
      }
   }

   /** Render thread: the map on unit {@link #UNIT} (false before it exists; a program then reads pzgStateInfo.w = 0). */
   public static boolean bindTexture() {
      int t = tex;
      if (t == 0) {
         return false;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   /** Render thread: the map's GL texture (0 before it exists). Sample at world square * TPS / TN, GL_REPEAT, linear. */
   public static int texture() {
      return tex;
   }

   /** Render thread: the window's first square as the map's current contents have it (x, y). */
   public static int renderWindowX() {
      return rOx;
   }

   public static int renderWindowY() {
      return rOy;
   }

   private static int[] locs = new int[0]; // per program id: 1 + pzgStateInfo, 1 + pzgStateWin (0: not looked up)

   /** Render thread, {@code program} bound: pzgStateInfo (texels a square, map texels, 0, on), pzgStateWin, pzgStateTex's unit. */
   public static void applyUniforms(int program) {
      if (program <= 0) {
         return;
      }
      if (program * 2 + 1 >= locs.length) {
         locs = java.util.Arrays.copyOf(locs, Math.max(program * 2 + 2, locs.length * 2));
      }
      if (locs[program * 2] == 0) {
         locs[program * 2] = 1 + GL20.glGetUniformLocation(program, "pzgStateInfo");
         locs[program * 2 + 1] = 1 + GL20.glGetUniformLocation(program, "pzgStateWin");
         int lt = GL20.glGetUniformLocation(program, "pzgStateTex");
         if (lt >= 0) {
            GL20.glUniform1i(lt, UNIT);
         }
      }
      int li = locs[program * 2] - 1, lw = locs[program * 2 + 1] - 1;
      if (li >= 0) {
         GL20.glUniform4f(li, TPS, TN, 0F, BIND_ON && tex != 0 ? 1F : 0F);
      }
      if (lw >= 0) {
         GL20.glUniform4f(lw, rOx, rOy, rOx + SQUARES, rOy + SQUARES);
      }
   }

   /** Render thread: a program was deleted (its id may be reused). */
   public static void forgetProgram(int program) {
      if (program > 0 && program * 2 + 1 < locs.length) {
         locs[program * 2] = locs[program * 2 + 1] = 0;
      }
   }

   /** GLSL for any program (the floor variant includes it): read with {@link #bindTexture} + {@link #applyUniforms}. */
   public static final String GLSL = String.join("\n",
      "#ifndef PZG_STATE",
      "#define PZG_STATE 1",
      "uniform sampler2D pzgStateTex;",
      "uniform vec4 pzgStateInfo;", // texels a square, map size in texels, 0, on
      "uniform vec4 pzgStateWin;", // the window in squares: x0, y0, x1, y1
      "vec4 pzgStateRaw(vec2 world) {",
      "   if (pzgStateInfo.w < 0.5 || world.x < pzgStateWin.x || world.y < pzgStateWin.y || world.x >= pzgStateWin.z || world.y >= pzgStateWin.w)",
      "      return vec4(192.0 / 255.0, 0.0, 0.0, 0.0);",
      "   return texture2D(pzgStateTex, world * (pzgStateInfo.x / pzgStateInfo.y));",
      "}",
      "// height in squares (< 0 a rut, > 0 a berm), wear, freshness, slip",
      "vec4 pzgStateDecode(vec4 r) { return vec4((r.x * 255.0 - 192.0) / 510.0, r.yzw); }",
      "vec4 pzgStateAt(vec2 world) { return pzgStateDecode(pzgStateRaw(world)); }",
      "#endif",
      "");

   /**
    * {@link #GLSL} for a GLSL 1.30+ program, with texture() (2026-10-24, the blades' Mac trap): a texture2D token makes
    * CoreGlsl (macOS GL 4.1 core) inject its wrappers, whose bias overloads Apple refuses in a vertex shader.
    */
   public static final String GLSL_130 = GLSL.replace("texture2D(", "texture(");

   // ------------------------------------------------------------------------------------------------ persistence

   private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-ground-io");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
   });
   private static final ConcurrentLinkedQueue<Object[]> LOADS = new ConcurrentLinkedQueue<>();
   private static final int MAGIC = 0x475A5352; // "GZSR"
   private static final int VERSION = 2; // 2: 16 texels a square, (h, wear, freshness, slip) with rest 192

   static File dir(String sid) {
      String safe = sid.replaceAll("[^A-Za-z0-9._/-]", "_").replace("..", "_");
      return new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + "ground" + File.separator + safe.replace('/', File.separatorChar));
   }

   /** Game thread: every modified chunk read back and saved (the next frames finish it). */
   public static void flush() {
      if (activeNow && Config.GROUND_TRACKS_PERSIST) {
         Frame f = alloc();
         readbackModified(f, false);
         f.ox = wcx * CS;
         f.oy = wcy * CS;
         f.rules = rules;
         submit(f);
      }
   }

   private static void load(int cx, int cy) {
      final File d = dir(saveId);
      final String sid = saveId;
      IO.execute(() -> {
         double[] at = new double[1];
         byte[] b = readChunk(d, cx, cy, at);
         if (b != null) {
            LOADS.add(new Object[] {sid, cx, cy, b, at[0]});
         }
      });
   }

   private static void applyLoads(Frame f, long nowMs) {
      Object[] l;
      double hours = worldHours();
      Rules r = rules;
      while (f.nLoad < MAX_LOADS_PER_FRAME && (l = LOADS.poll()) != null) {
         if (!l[0].equals(saveId)) {
            continue;
         }
         int cx = (Integer)l[1], cy = (Integer)l[2];
         byte[] b = (byte[])l[3];
         double age = hours - (Double)l[4];
         int s = slot(cx, cy);
         if (slotKey[s] != key(cx, cy) || slotLive[s] || age > r.maxAgeHours() || age < -1.0) {
            loadsDropped++; // left the window, stamped meanwhile (the new stamps win), or too old
            continue;
         }
         f.loadSlot[f.nLoad++] = s;
         f.loadBuf.put(b);
         slotLive[s] = true;
         slotModified[s] = false;
         slotPending[s] = true;
         slotLoadedFrame[s] = frame;
         if (age > 0.0) {
            System.arraycopy(WEATHER, 0, f.weather, 0, 4);
            decayChunk(f, cx, cy, r.decayDt(age), nowMs);
         }
         chunksLoaded++;
      }
   }

   // region files: 16 x 16 chunks; header: magic, version, 256 x (offset int, length int, hours double); blobs deflated

   private static final int REGION = 16, HEADER = 8 + REGION * REGION * 16;

   private static File regionFile(File d, int cx, int cy) {
      return new File(d, "r_" + Math.floorDiv(cx, REGION) + "_" + Math.floorDiv(cy, REGION) + ".gsr");
   }

   private static boolean isRest(byte[] raw) {
      for (int k = 0; k < raw.length; k += 4) {
         if (raw[k] != REST_R || raw[k + 1] != 0 || raw[k + 2] != 0 || raw[k + 3] != 0) {
            return false;
         }
      }
      return true;
   }

   /** IO thread: one chunk. */
   static void writeChunk(File d, int cx, int cy, byte[] raw, double hours) {
      writeChunks(d, new int[] {cx}, new int[] {cy}, new byte[][] {raw}, hours);
   }

   /** IO thread: chunks, one write per region file they fall in; a chunk at rest is removed from its region. */
   static void writeChunks(File d, int[] cxs, int[] cys, byte[][] raws, double hours) {
      boolean[] done = new boolean[cxs.length];
      for (int i = 0; i < cxs.length; i++) {
         if (done[i]) {
            continue;
         }
         int rx = Math.floorDiv(cxs[i], REGION), ry = Math.floorDiv(cys[i], REGION);
         try {
            d.mkdirs();
            File f = regionFile(d, cxs[i], cys[i]);
            byte[][] blobs = new byte[REGION * REGION][];
            double[] at = new double[REGION * REGION];
            readRegion(f, blobs, at);
            for (int j = i; j < cxs.length; j++) {
               if (done[j] || Math.floorDiv(cxs[j], REGION) != rx || Math.floorDiv(cys[j], REGION) != ry) {
                  continue;
               }
               done[j] = true;
               int k = Math.floorMod(cys[j], REGION) * REGION + Math.floorMod(cxs[j], REGION);
               blobs[k] = isRest(raws[j]) ? null : deflate(raws[j]);
               at[k] = hours;
               chunksSaved++;
            }
            File tmp = new File(d, f.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
               out.writeInt(MAGIC);
               out.writeInt(VERSION);
               int off = HEADER;
               for (int k = 0; k < REGION * REGION; k++) {
                  int len = blobs[k] == null ? 0 : blobs[k].length;
                  out.writeInt(len == 0 ? 0 : off);
                  out.writeInt(len);
                  out.writeDouble(at[k]);
                  off += len;
               }
               for (int k = 0; k < REGION * REGION; k++) {
                  if (blobs[k] != null) {
                     out.write(blobs[k]);
                  }
               }
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
         } catch (Throwable t) {
            Log.warn("ground state: could not save region " + rx + "," + ry + ": " + t);
         }
      }
   }

   /** IO thread: the chunk's texels and (into {@code hoursOut[0]}) the game hour they were saved at; null when absent. */
   static byte[] readChunk(File d, int cx, int cy, double[] hoursOut) {
      File f = regionFile(d, cx, cy);
      if (!f.isFile()) {
         return null;
      }
      byte[][] blobs = new byte[REGION * REGION][];
      double[] at = new double[REGION * REGION];
      if (!readRegion(f, blobs, at)) {
         return null;
      }
      int i = Math.floorMod(cy, REGION) * REGION + Math.floorMod(cx, REGION);
      if (blobs[i] == null) {
         return null;
      }
      try {
         byte[] raw = inflate(blobs[i], CHUNK_TEX * CHUNK_TEX * 4);
         if (hoursOut != null) {
            hoursOut[0] = at[i];
         }
         return raw;
      } catch (Throwable t) {
         Log.warn("ground state: chunk " + cx + "," + cy + " unreadable: " + t);
         return null;
      }
   }

   /** The region files in {@code d} (tests, stats). */
   static int regionFiles(File d) {
      File[] fs = d.listFiles((x, n) -> n.endsWith(".gsr"));
      return fs == null ? 0 : fs.length;
   }

   private static boolean readRegion(File f, byte[][] blobs, double[] at) {
      if (!f.isFile()) {
         return false;
      }
      try {
         byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
         DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(all));
         if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            return false; // (another version: overwritten at the next save)
         }
         for (int k = 0; k < REGION * REGION; k++) {
            int off = in.readInt(), len = in.readInt();
            at[k] = in.readDouble();
            if (len > 0 && off >= HEADER && off + len <= all.length) {
               blobs[k] = java.util.Arrays.copyOfRange(all, off, off + len);
            }
         }
         return true;
      } catch (Throwable t) {
         Log.warn("ground state: region " + f.getName() + " unreadable: " + t);
         return false;
      }
   }

   static byte[] deflate(byte[] raw) {
      java.util.zip.Deflater def = new java.util.zip.Deflater(6);
      def.setInput(raw);
      def.finish();
      ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 8 + 64);
      byte[] buf = new byte[8192];
      while (!def.finished()) {
         bo.write(buf, 0, def.deflate(buf));
      }
      def.end();
      return bo.toByteArray();
   }

   static byte[] inflate(byte[] z, int size) throws java.util.zip.DataFormatException {
      java.util.zip.Inflater inf = new java.util.zip.Inflater();
      inf.setInput(z);
      byte[] raw = new byte[size];
      int n = 0;
      while (n < size && !inf.finished()) {
         int k = inf.inflate(raw, n, size - n);
         if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
            break;
         }
         n += k;
      }
      inf.end();
      if (n != size) {
         throw new java.util.zip.DataFormatException("short chunk (" + n + " of " + size + ")");
      }
      return raw;
   }

   public static String stats() {
      return "ground state: " + (activeNow ? "on" : "off") + " rules " + rules.id() + ", frames " + frames + ", prims " + primsTotal + " (dropped " + primsDropped
         + "), cells " + cellsTotal + ", dispatches " + dispatches + ", decay passes " + decayPasses + " (" + decayCells + " cells), clears " + clears
         + ", read-backs " + readbacks + " (in flight " + inFlight.get() + "), chunks saved " + chunksSaved + " loaded " + chunksLoaded + " dropped "
         + loadsDropped + ", re-bakes " + rebakesAsked + ", stamp batches " + batches + ", game " + us(gameNs) + " us/frame (sources " + us(sourcesNs)
         + ", dev rigs " + us(rigNs) + ", binning " + us(binNs) + ")" + (failed ? " (failed)" : "");
   }

   private static String us(long ns) {
      return frames == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f", ns / 1e3 / frames);
   }
}
