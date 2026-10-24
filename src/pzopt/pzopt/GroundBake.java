package pzopt;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

/**
 * Ground and grass, shared part (2026-10-24): the hooks the ground features use inside the chunk-texture bake. Every hook
 * costs nothing while no feature uses it (one static boolean read on the paths it touches; no GL call, no allocation).
 *
 * <h2>(b) The natural-ground floor program</h2>
 * A feature registers a {@link FloorPatch} ({@link #register(FloorPatch)}, once, from its static init or its first frame).
 * While at least one patch is {@link FloorPatch#active()}, every natural floor tile ({@link GroundTiles#natural}, level 0)
 * of a bake is drawn with a variant of the bake's tile program ({@code tileWithDepth}, and {@code opaqueWithDepth} for the
 * floors that use it) instead of the stock one: the stock program's final source (every other pzopt patch in it: tile
 * vertex depth, foliage sway's attribute, pixelLight) plus this class's ground code and the patches' GLSL, compiled once
 * per set of active patches on the render thread (the floors keep the stock program until it is ready). The variant
 * gives the patches, in GLSL (all names {@code pzg*}):
 * <ul>
 *   <li>{@link GroundHash#GLSL}: hashes, value noise, fbm, Worley cells;</li>
 *   <li>{@code vec3 pzgW} (the texel's world position: x, y in squares, z the level), {@code ivec2 pzgSq} (its square),
 *       {@code vec2 pzgF} (its place in the square, 0..1), {@code int pzgClass} (the square's class map byte, see
 *       {@link GroundTiles#mapByte}; {@code pzgMaterial()} its material), {@code int pzgClassAt(ivec2 square)} (any square's,
 *       neighbours included), {@code int pzgPiece} (with {@link FloorPatch#wantsAttachedFlag()}: 0 the floor's own sprite, 1 an
 *       attached piece of natural ground (a blends_natural edge), 2 any other attached piece (a grass / erosion overlay,
 *       dug soil); {@code bool pzgAttached} = pzgPiece != 0; without the flag every piece draws as 0), all set before the
 *       stock code runs;</li>
 *   <li>{@code vec4 pzgStateAt(vec2 world)}: the ground state map ({@link GroundState}) at a world position;</li>
 *   <li>the stock program's own names ({@code DIFFUSE}, {@code texCoord}, {@code col}, ...): the patch functions are
 *       inserted right before {@code main}, after every declaration.</li>
 * </ul>
 * A patch hooks in at two points: {@link FloorPatch#texel()} names a {@code vec4 f(vec4 texel)} applied to the floor's art
 * texel as the stock code fetched it (before the light colour multiplies it: the place to re-sample the art at world
 * coordinates), {@link FloorPatch#color()} a {@code vec4 f(vec4 colour)} applied to the colour the program writes. Several
 * patches chain in registration order. Per bake, {@link FloorPatch#bakeStart} gets the variant program on the render thread
 * to set its own uniforms; {@link FloorPatch#samplers()} names its own sampler2D uniforms and their texture units (set
 * after the link: the game renumbers sampler2D units at link time; bind the textures yourself, units 56-63 are this
 * package's: 56 class map, 57 ground state, 58-63 free for the features).
 *
 * <h2>(c) Extra draws after the floors</h2>
 * A {@link FloorPass} ({@link #register(FloorPass)}) draws into a chunk level's bake after its floor tiles and before its
 * snow, blood and objects (scatter: leaves under the blood and the puddles), on the game thread: with bakeRecordParallel the
 * call is deferred to the splice, at its place in the stream. Draw with the game's own sprite paths (IsoSprite.render,
 * SpriteRenderer.render with the tile programs) so the depth and the pixelLight bake stay right.
 *
 * <h2>(a) The texel's world position</h2>
 * In the floor variant: {@code pzgW} (above). Anywhere else on the render thread during a bake: {@link #bakeMapping(float[])}
 * gives the current chunk texture's mapping (u = x - y = fragX * m[0] + m[1], v = x + y - 6 z = fragY * m[2] + m[3],
 * gl_FragCoord of the bake), the same numbers the floor variant's {@code pzgMap} uniform holds.
 *
 * <h2>(d) Re-bakes</h2>
 * {@link #rebakeAll(String, boolean)} / {@link #rebakeArea} / {@link #rebakeChunk} mark the ground chunk levels on screen
 * (or in an area) dirty with a reason; {@code low = true} uses the bake scheduler's lowest class (with lighting drift: a
 * season step that may take seconds), else the redraw class. The counts per reason are in {@link #stats()}. The floor
 * variant's own changes (a patch switched on or off, live or by the dev alternation) re-bake through this by themselves.
 *
 * <h2>The class map</h2>
 * A 512 x 512 squares toroidal R8 texture (unit 56) holding {@link GroundTiles#mapByte} of the level-0 squares of every
 * chunk that baked while a patch, a pass or {@link #needClassMap()} wanted it (the chunk plus a one-square border, so a
 * patch can read its neighbours), uploaded through a pixel buffer right before the bake that needs it.
 * {@link #GLSL_CLASS} declares the lookup for other programs (the composite, the blade pass); bind with {@link #bindClassMap}.
 */
public final class GroundBake {
   private GroundBake() {
   }

   // ------------------------------------------------------------------------------------------------ the API

   /** A floor-program patch (ground tiling, macro variation, height blending, baked tracks). */
   public interface FloorPatch {
      /** Unique, short, [a-zA-Z0-9]: names the variant's defines. */
      String id();

      /** Game thread, once a frame: the patch wants to draw (its key after {@link Ground}'s alternation). */
      boolean active();

      /** GLSL inserted before main (after the stock declarations and the ground helpers): uniforms, functions. */
      String declarations();

      /**
       * The name of a {@code vec4 f(vec4 texel)} applied to the floor's art texel, or null. Texels are premultiplied (the
       * game's pages, ImageData.performPreMultipliedAlpha) and floors bake with ONE / ONE_MINUS_SRC_ALPHA: return
       * premultiplied colour (un-premultiply, work, return rgb * a), or partial-alpha edges come out bright (ground-tiling).
       */
      default String texel() {
         return null;
      }

      /** The name of a {@code vec4 f(vec4 colour)} applied to the colour the program writes (premultiplied too), or null. */
      default String color() {
         return null;
      }

      /** {name, unit} pairs: this patch's sampler2D uniforms and the texture units they read (58-63 are free). */
      default String[][] samplers() {
         return new String[0][];
      }

      /** {@code #define} lines put at the head of the variant (before the stock source and the ground helpers), or "". */
      default String defines() {
         return "";
      }

      /** Wants {@code pzgPiece} / {@code pzgAttached} (costs two more programs: attached pieces draw with their own, by kind). */
      default boolean wantsAttachedFlag() {
         return false;
      }

      /** Render thread, each bake into a chunk texture while the variant exists: set this patch's uniforms on {@code program}. */
      default void bakeStart(int program, FBORenderChunk rc) {
      }
   }

   /** Extra draws into a chunk level's bake after its floors (game thread). */
   public interface FloorPass {
      String id();

      /** Game thread, once a frame. */
      boolean active();

      /** Game thread, inside the bake of chunk {@code c}'s level {@code level}, after its floor tiles (or its snow, by phase). */
      void draw(IsoChunk c, int level, int playerIndex);

      /** {@link #PHASE_FLOORS}: after the floor tiles, before the snow; {@link #PHASE_AFTER_SNOW}: after the snow, before the blood. */
      default int phase() {
         return PHASE_FLOORS;
      }
   }

   public static final int PHASE_FLOORS = 0, PHASE_AFTER_SNOW = 1;

   private static final CopyOnWriteArrayList<FloorPatch> PATCHES = new CopyOnWriteArrayList<>();
   private static final CopyOnWriteArrayList<FloorPass> PASSES = new CopyOnWriteArrayList<>();

   /**
    * Dev (devGroundNoop, 2026-10-24, ground-tiling's "the no-op variant doubles a bake's GPU time"): floor patches that
    * change nothing, keyed by groundHexTiling and the cycle token: full (the variant as a patch gets it), nofetch (without
    * the class-map fetch in pzgSetup), noupload (the variant, no class-map uploads while it is on). With
    * devGroundAlternate + devGroundAlternateKeys=groundHexTiling + devGroundCycle=full,nofetch,noupload; harness/ground/bakecost.py.
    */
   static void registerNoopProbes() {
      String[][] probes = {{"noopFull", "full", ""}, {"noopNoFetch", "nofetch", "#define PZG_NO_CLASS_FETCH 1"}, {"noopNoUpload", "noupload", ""}};
      for (String[] pr : probes) {
         register(new FloorPatch() {
            public String id() {
               return pr[0];
            }

            public boolean active() {
               String t = Ground.variant();
               return Ground.on(Ground.K_HEX) && (t == null ? pr[1].equals("full") : t.equals(pr[1]));
            }

            public String declarations() {
               return "";
            }

            public String defines() {
               return pr[2];
            }
         });
      }
      Log.info("ground: clock nano=" + System.nanoTime() + " epoch_ms=" + System.currentTimeMillis() + " (no-op floor probes registered)");
   }

   public static void register(FloorPatch p) {
      for (FloorPatch q : PATCHES) {
         if (q.id().equals(p.id())) {
            PATCHES.remove(q);
         }
      }
      PATCHES.add(p);
      Log.info("ground: floor patch " + p.id() + " registered");
   }

   public static void register(FloorPass p) {
      for (FloorPass q : PASSES) {
         if (q.id().equals(p.id())) {
            PASSES.remove(q);
         }
      }
      PASSES.add(p);
      Log.info("ground: floor pass " + p.id() + " registered");
   }

   static boolean anyRegistered() {
      return Config.DEV_GROUND_VIEW != 0 || !PATCHES.isEmpty() || !PASSES.isEmpty() || classWantedFrame >= frameNo - 2 || FLOOR_ON || PASS_ON || CLASS_ON;
   }

   /** A feature outside the bake reads the class map (the composite, the blade pass): it is kept up to date while this is called each frame. */
   public static void needClassMap() {
      classWantedFrame = frameNo;
   }

   // ------------------------------------------------------------------------------------------------ switches (game thread)

   /** The floor variant is bound for natural floors this frame. */
   public static volatile boolean FLOOR_ON;
   /** A floor pass draws this frame. */
   public static volatile boolean PASS_ON;
   /** An after-snow floor pass draws this frame. */
   public static volatile boolean PASS_AFTER_SNOW_ON;
   /** The class map is updated at bakes this frame. */
   public static volatile boolean CLASS_ON;
   private static int frameNo, classWantedFrame = -100;
   private static String signature = ""; // active patches, by id, the variant they need
   private static String passSignature = "";
   private static final ArrayList<FloorPatch> ACTIVE = new ArrayList<>();
   private static final ArrayList<FloorPass> ACTIVE_PASSES = new ArrayList<>();
   private static final ArrayList<FloorPass> ACTIVE_AFTER_SNOW = new ArrayList<>();
   private static boolean shownSignature;

   static boolean supported() {
      return Overrides.enabled() && !CoreGl.legacyMac();
   }

   /** Game thread, Ground.frameStart: which patches and passes run this frame; a change re-bakes the ground on screen. */
   static void frameStart(int playerIndex) {
      frameNo++;
      if (PATCHES.isEmpty() && PASSES.isEmpty() && classWantedFrame < frameNo - 2 && Config.DEV_GROUND_VIEW == 0) {
         if (FLOOR_ON || PASS_ON || PASS_AFTER_SNOW_ON || CLASS_ON) {
            FLOOR_ON = PASS_ON = PASS_AFTER_SNOW_ON = CLASS_ON = false;
         }
         return;
      }
      // the frame's active set as a key: unchanged and applied, nothing to rebuild (no strings, no lists a frame)
      boolean sup = supported();
      long key = sup ? (long)(Config.DEV_GROUND_VIEW & 0xFF) << 48 : 0L;
      if (sup) {
         for (int i = 0; i < PATCHES.size() && i < 24; i++) {
            if (activeSafe(PATCHES.get(i))) {
               key |= 1L << i;
            }
         }
         for (int i = 0; i < PASSES.size() && i < 24; i++) {
            if (activeSafe(PASSES.get(i))) {
               key |= 1L << (24 + i);
            }
         }
      }
      if (key == appliedKey && PATCHES.size() == appliedPatches && PASSES.size() == appliedPasses) {
         CLASS_ON = sup && (FLOOR_ON || PASS_ON || PASS_AFTER_SNOW_ON || classWantedFrame >= frameNo - 2);
         return;
      }
      ACTIVE.clear();
      StringBuilder sig = new StringBuilder();
      boolean attached = false;
      if (supported()) {
         for (FloorPatch p : PATCHES) {
            boolean on;
            try {
               on = p.active();
            } catch (Throwable t) {
               on = false;
            }
            if (on) {
               ACTIVE.add(p);
               sig.append(sig.length() == 0 ? "" : "+").append(p.id());
               attached |= p.wantsAttachedFlag();
            }
         }
      }
      if (Config.DEV_GROUND_VIEW != 0 && supported()) {
         sig.append(sig.length() == 0 ? "" : "+").append("view").append(Config.DEV_GROUND_VIEW);
      }
      String s = sig.toString();
      boolean ready = false;
      if (!s.isEmpty()) {
         Variant v = VARIANTS.get(s);
         if (v == null) {
            v = new Variant(s, new ArrayList<>(ACTIVE), attached, Config.DEV_GROUND_VIEW);
            VARIANTS.put(s, v);
            SpriteRenderer.instance.drawGeneric(v); // compiled on the render thread, in stream order
         }
         ready = v.state == Variant.READY;
         if (ready) {
            current = v;
         }
      }
      if (s.isEmpty()) {
         current = null; // (chunkStart has nothing to set up)
      }
      boolean floorOn = ready;
      if (!s.equals(signature) && (ready || s.isEmpty())) {
         // the floor programs change: the natural ground on screen re-bakes with the new set (or back to stock)
         if (!signature.isEmpty() || !s.isEmpty()) {
            rebakeAll(s.isEmpty() ? "floor patches off" : "floor patches " + s, false);
         }
         signature = s;
         if (!s.isEmpty() && !shownSignature) {
            shownSignature = true;
         }
      }
      FLOOR_ON = floorOn && signature.equals(s);
      ACTIVE_PASSES.clear();
      ACTIVE_AFTER_SNOW.clear();
      StringBuilder ps = new StringBuilder();
      if (supported()) {
         for (FloorPass p : PASSES) {
            boolean on;
            try {
               on = p.active();
            } catch (Throwable t) {
               on = false;
            }
            if (on) {
               (p.phase() == PHASE_AFTER_SNOW ? ACTIVE_AFTER_SNOW : ACTIVE_PASSES).add(p);
               ps.append(ps.length() == 0 ? "" : "+").append(p.id());
            }
         }
      }
      String pss = ps.toString();
      if (!pss.equals(passSignature)) {
         rebakeAll(pss.isEmpty() ? "floor passes off" : "floor passes " + pss, false);
         passSignature = pss;
      }
      PASS_ON = !ACTIVE_PASSES.isEmpty();
      PASS_AFTER_SNOW_ON = !ACTIVE_AFTER_SNOW.isEmpty();
      CLASS_ON = supported() && (FLOOR_ON || PASS_ON || PASS_AFTER_SNOW_ON || classWantedFrame >= frameNo - 2);
      // applied once the programs of this set are the ones baking (a variant still compiling: checked again next frame)
      boolean applied = signature.equals(s) && (s.isEmpty() || FLOOR_ON);
      appliedKey = applied ? key : -1L;
      appliedPatches = PATCHES.size();
      appliedPasses = PASSES.size();
   }

   private static long appliedKey = -1L;
   private static int appliedPatches = -1, appliedPasses = -1;

   private static boolean activeSafe(FloorPatch p) {
      try {
         return p.active();
      } catch (Throwable t) {
         return false;
      }
   }

   private static boolean activeSafe(FloorPass p) {
      try {
         return p.active();
      } catch (Throwable t) {
         return false;
      }
   }

   // ------------------------------------------------------------------------------------------------ the floor draw (game / recording threads)

   /** Per thread: the floor being drawn takes the variant. */
   private static final ThreadLocal<boolean[]> CUR = ThreadLocal.withInitial(() -> new boolean[1]);
   private static volatile Variant current;
   public static long naturalFloors, stockFloors;

   /** FBORenderCell.renderFloor(IsoObject), before the floor draws (bake floor pass; any thread that records it). */
   public static boolean floorBegin(IsoObject object) {
      if (!FLOOR_ON || object == null) {
         return false;
      }
      IsoGridSquare sq = object.square;
      if (sq == null || sq.z != 0 || zombie.iso.fboRenderChunk.FBORenderCell.pzoptTranslucentOnly() || !GroundTiles.natural(GroundTiles.square(sq))) {
         stockFloors++;
         return false;
      }
      CUR.get()[0] = true;
      naturalFloors++;
      return true;
   }

   public static void floorEnd() {
      CUR.get()[0] = false;
   }

   /**
    * IsoSprite.startTileDepthShader: the program a floor tile starts ({@code stock}: the stock tile-depth program the game
    * chose; the caller builds its uniform setters from the shader returned, so a variant whose uniforms sit at other
    * locations than the stock program's gets its own; {@code sprite} the sprite drawn, {@code attached}: it is not the floor object's own, a blend edge or an
    * overlay). The variant for a natural floor between {@link #floorBegin} and {@link #floorEnd}, else {@code program}.
    */
   public static zombie.tileDepth.TileDepthShader shader(zombie.tileDepth.TileDepthShader stock, zombie.iso.sprite.IsoSprite sprite, boolean attached) {
      if (!FLOOR_ON || stock == null || !CUR.get()[0]) {
         return stock;
      }
      Variant v = current;
      if (v == null || v.state != Variant.READY) {
         return stock;
      }
      int program = stock.getID();
      int piece = 0;
      if (attached && v.attachedFlag) {
         int code = GroundTiles.of(sprite);
         piece = GroundTiles.natural(code) && (code & (GroundTiles.F_OVERLAY | GroundTiles.F_FARM)) == 0 ? 1 : 2;
      }
      int k = program == v.stockTile ? 0 : program == v.stockOpaque ? 1 : -1;
      if (k < 0) {
         return stock;
      }
      zombie.tileDepth.TileDepthShader sh = v.shaders[piece * 2 + k];
      return sh != null ? sh : stock;
   }

   /** IsoSprite.startTileDepthShader, the seam strip of a border floor (IsoSprite.seamFix2): the variant's seam program. */
   public static zombie.tileDepth.TileSeamShader seamShader(zombie.tileDepth.TileSeamShader stock, IsoObject obj) {
      if (!FLOOR_ON || stock == null || !CUR.get()[0]) {
         return stock;
      }
      if (Config.DEV_GROUND_SEAM_STOCK && obj != null && obj.square != null && ((obj.square.x >> 3) + (obj.square.y >> 3) & 1) == 0) {
         return stock; // dev: the stock strips on every other chunk (one picture shows both)
      }
      Variant v = current;
      if (v == null || v.state != Variant.READY || v.seamShader == null || stock.getID() != v.stockSeam) {
         return stock;
      }
      return v.seamShader;
   }

   // ------------------------------------------------------------------------------------------------ (c) floor passes

   /** FBORenderCell.pzoptBakeFloors after the floor tiles (game thread, or deferred to the splice). */
   public static void floorPasses(IsoChunk c, int level, int playerIndex) {
      if (PASS_ON) {
         run(ACTIVE_PASSES, c, level, playerIndex);
      }
   }

   /** FBORenderCell.pzoptBakeSnow's end (game thread, in the bake stream, in place or deferred): the after-snow passes. */
   public static void afterSnowPasses(IsoChunk c, int level, int playerIndex) {
      if (PASS_AFTER_SNOW_ON) {
         run(ACTIVE_AFTER_SNOW, c, level, playerIndex);
      }
   }

   private static void run(ArrayList<FloorPass> list, IsoChunk c, int level, int playerIndex) {
      for (int i = 0; i < list.size(); i++) {
         try {
            list.get(i).draw(c, level, playerIndex);
         } catch (Throwable t) {
            Log.warn("ground: floor pass " + list.get(i).id() + " failed and is off: " + t);
            PASSES.remove(list.get(i));
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ (d) re-bakes

   private static final java.util.LinkedHashMap<String, long[]> REBAKES = new java.util.LinkedHashMap<>();

   /** Game thread: every on-screen chunk texture holding level 0 re-bakes (reason for the stats; low = lowest scheduler class). */
   public static int rebakeAll(String reason, boolean low) {
      return rebakeArea(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, reason, low);
   }

   /** Game thread: the loaded chunks overlapping squares [x0, x1] x [y0, y1] re-bake their level 0. */
   public static int rebakeArea(int x0, int y0, int x1, int y1, String reason, boolean low) {
      int n = 0;
      try {
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.getCell();
         if (cell == null) {
            return 0;
         }
         long flag = low && BakeScheduler.ON ? BakeScheduler.DIRTY_SEAM_LOW : FBORenderChunk.DIRTY_REDRAW;
         for (int p = 0; p < zombie.characters.IsoPlayer.numPlayers; p++) {
            if (zombie.characters.IsoPlayer.players[p] == null) {
               continue;
            }
            zombie.iso.IsoChunkMap cm = cell.getChunkMap(p);
            for (int y = 0; y < zombie.iso.IsoChunkMap.chunkGridWidth; y++) {
               for (int x = 0; x < zombie.iso.IsoChunkMap.chunkGridWidth; x++) {
                  IsoChunk c = cm.getChunk(x, y);
                  if (c == null || c.minLevel > 0 || c.maxLevel < 0) {
                     continue;
                  }
                  int cx0 = c.wx * 8, cy0 = c.wy * 8;
                  if (cx0 + 7 < x0 || cx0 > x1 || cy0 + 7 < y0 || cy0 > y1) {
                     continue;
                  }
                  c.getRenderLevels(p).invalidateLevel(0, flag);
                  n++;
               }
            }
         }
      } catch (Throwable e) {
         Log.warn("ground: re-bake (" + reason + ") failed: " + e);
      }
      synchronized (REBAKES) {
         long[] r = REBAKES.computeIfAbsent(reason.length() > 40 ? reason.substring(0, 40) : reason, k -> new long[2]);
         r[0]++;
         r[1] += n;
         if (REBAKES.size() > 64) {
            REBAKES.remove(REBAKES.keySet().iterator().next());
         }
      }
      if (n > 0 && Config.INSTRUMENT) {
         Log.info("ground: " + n + " chunk textures re-bake (" + reason + ")");
      }
      return n;
   }

   /** Game thread: chunk (wx, wy)'s level 0 re-bakes. */
   public static int rebakeChunk(int wx, int wy, String reason, boolean low) {
      return rebakeArea(wx * 8, wy * 8, wx * 8 + 7, wy * 8 + 7, reason, low);
   }

   // ------------------------------------------------------------------------------------------------ the class map

   static final int CLASS_N = 512, CLASS_UNIT = 56, STATE_UNIT = 57;
   private static final java.util.ArrayDeque<ClassPatch> FREE_PATCHES = new java.util.ArrayDeque<>();
   public static long classUploads, classBytes;

   /** Render-thread upload of one chunk's classes (10 x 10 squares: the chunk and a border of one). */
   private static final class ClassPatch extends TextureDraw.GenericDrawer {
      final byte[] b = new byte[200]; // 10 x 10 squares, RG: GroundTiles.mapByte, tone + flags (toneByte)
      int x0, y0; // world square of b[0]

      @Override
      public void render() {
         uploadClass(this);
      }

      @Override
      public void postRender() {
         synchronized (FREE_PATCHES) {
            if (FREE_PATCHES.size() < 256) {
               FREE_PATCHES.add(this);
            }
         }
      }
   }

   /**
    * FBORenderCell.renderOneLevel, game thread, a level-0 bake begins (its FBORenderChunkStart is queued): this chunk's
    * classes (and a border of one) into the class map, uploaded before the bake's draws in the stream.
    */
   public static void bakeBegin(IsoChunk c, int level) {
      if (!CLASS_ON || c == null || level != 0) {
         return;
      }
      if (Config.DEV_GROUND_NOOP && "noupload".equals(Ground.variant()) && Ground.on(Ground.K_HEX)) {
         return; // dev probe: the variant without its class-map uploads
      }
      ClassPatch p;
      synchronized (FREE_PATCHES) {
         p = FREE_PATCHES.poll();
      }
      if (p == null) {
         p = new ClassPatch();
      }
      p.x0 = c.wx * 8 - 1;
      p.y0 = c.wy * 8 - 1;
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      for (int y = 0; y < 10; y++) {
         for (int x = 0; x < 10; x++) {
            IsoGridSquare sq;
            if (x >= 1 && x <= 8 && y >= 1 && y <= 8) {
               sq = c.getGridSquare(x - 1, y - 1, 0);
            } else {
               sq = cell == null ? null : cell.getGridSquare(p.x0 + x, p.y0 + y, 0);
            }
            int code = GroundTiles.square(sq);
            p.b[(y * 10 + x) * 2] = (byte)GroundTiles.mapByte(code);
            p.b[(y * 10 + x) * 2 + 1] = (byte)GroundTiles.toneByte(code);
         }
      }
      SpriteRenderer.instance.drawGeneric(p);
   }

   private static int classTex, classPbo;
   private static java.nio.ByteBuffer classBuf;

   private static void uploadClass(ClassPatch p) {
      if (classTex == 0) {
         classTex = GL11.glGenTextures();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLASS_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, classTex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
         java.nio.ByteBuffer zero = BufferUtils.createByteBuffer(CLASS_N * CLASS_N * 2);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, CLASS_N, CLASS_N, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, zero);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         classPbo = GL15.glGenBuffers();
         classBuf = BufferUtils.createByteBuffer(200);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      GpuSections.markNow(Ground.section("ground.class"), false);
      classBuf.clear();
      classBuf.put(p.b).flip();
      // through a pixel buffer: on Mesa a client-memory sub-image waits for the driver thread
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, classPbo);
      GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, classBuf, GL15.GL_STREAM_DRAW);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLASS_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, classTex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      int x0 = Math.floorMod(p.x0, CLASS_N), y0 = Math.floorMod(p.y0, CLASS_N);
      int w1 = Math.min(10, CLASS_N - x0), h1 = Math.min(10, CLASS_N - y0);
      GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 10);
      sub(x0, y0, w1, h1, 0, 0);
      if (w1 < 10) {
         sub(0, y0, 10 - w1, h1, w1, 0);
      }
      if (h1 < 10) {
         sub(x0, 0, w1, 10 - h1, 0, h1);
         if (w1 < 10) {
            sub(0, 0, 10 - w1, 10 - h1, w1, h1);
         }
      }
      GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      classUploads++;
      classBytes += 200;
      GpuSections.markNow(Ground.section("ground.class"), true);
   }

   private static void sub(int tx, int ty, int w, int h, int sx, int sy) {
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, tx, ty, w, h, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, (long)(sy * 10 + sx) * 2L);
   }

   /** Render thread: the class map on its unit (56) for a program outside the bake (left bound; returns false before the first bake made it). */
   public static boolean bindClassMap() {
      if (classTex == 0) {
         return false;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLASS_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, classTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   /** GLSL for any program that reads the class map (bind it with {@link #bindClassMap}; set {@code pzgClassTex} to unit 56). */
   public static final String GLSL_CLASS = String.join("\n",
      "#ifndef PZG_CLASS",
      "#define PZG_CLASS 1",
      "uniform sampler2D pzgClassTex;", // GroundBake.CLASS_UNIT: RG8, one texel a square, toroidal over 512 squares
      "int pzgClassAt(ivec2 sq) { return int(texelFetch(pzgClassTex, ivec2(sq.x & 511, sq.y & 511), 0).r * 255.0 + 0.5); }",
      // G: the tone within the material (grass 0 light, 1 medium, 2 dark) in the low 4 bits, 16 erosion, 32 farm (GroundTiles.toneByte)
      "int pzgToneByteAt(ivec2 sq) { return int(texelFetch(pzgClassTex, ivec2(sq.x & 511, sq.y & 511), 0).g * 255.0 + 0.5); }",
      "int pzgToneAt(ivec2 sq) { return pzgToneByteAt(sq) & 15; }",
      "int pzgMaterialOf(int cls) { return cls & 15; }", // GroundTiles.NONE .. DARK_DIRT
      "bool pzgNatural(int cls) { int m = cls & 15; return m != 0 && m != 5 && m != 6 && m != 7 && (cls & 128) == 0; }",
      "#endif",
      "");

   // ------------------------------------------------------------------------------------------------ the floor variant (render thread)

   private static final java.util.HashMap<String, Variant> VARIANTS = new java.util.HashMap<>();
   private static String tileVert, tileFrag, opaqueVert, opaqueFrag, seamVert, seamFrag;
   private static Variant compiling;
   private static int compilingKind; // piece * 2 + (0 tile, 1 opaque)

   private static final class Variant extends TextureDraw.GenericDrawer {
      static final int PENDING = 0, READY = 1, FAILED = 2;
      final String sig;
      final ArrayList<FloorPatch> patches;
      final boolean attachedFlag;
      final int view;
      volatile int state = PENDING;
      final int[] prog = new int[6]; // [piece * 2 + (0 tile, 1 opaque)], 0 = none (the stock program)
      final zombie.tileDepth.TileDepthShader[] shaders = new zombie.tileDepth.TileDepthShader[6];
      int stockTile, stockOpaque;
      final int[] mapLoc = new int[6];
      // the chunk-edge seam strips (IsoSprite.seamFix2: a border floor drawn again, shifted, through seamFix2's mask)
      int seamProg, stockSeam = -1, seamMapLoc = -1;
      zombie.tileDepth.TileSeamShader seamShader;

      Variant(String sig, ArrayList<FloorPatch> patches, boolean attachedFlag, int view) {
         this.sig = sig;
         this.patches = patches;
         this.attachedFlag = attachedFlag;
         this.view = view;
      }

      @Override
      public void render() {
         if (this.state == PENDING) {
            build(this);
         }
      }
   }

   /** ShaderUnit hook (outermost): the tile programs' final sources recorded; the ground variants' sources given out. */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null) {
         return code;
      }
      String fn = fileName.replace('\\', '/');
      int sl = fn.lastIndexOf('/');
      String base = sl >= 0 ? fn.substring(sl + 1) : fn;
      switch (base) {
         case "tileWithDepth.vert":
            tileVert = code;
            return code;
         case "tileWithDepth.frag":
            tileFrag = code;
            return code;
         case "opaqueWithDepth.vert":
            opaqueVert = code;
            return code;
         case "opaqueWithDepth.frag":
            opaqueFrag = code;
            return code;
         case "seamFix2.vert":
            seamVert = code;
            return code;
         case "seamFix2.frag":
            seamFrag = code;
            return code;
         case "pzopt_grSeam.vert":
            return seamVert != null ? seamVert : code;
         case "pzopt_grSeam.frag": {
            Variant v = compiling;
            String p = v == null || seamFrag == null ? null : variantSource(seamFrag, v, 0);
            return p != null ? p : FAILED_SOURCE;
         }
         case "pzopt_grTile.vert":
            return (compilingKind & 1) == 0 ? (tileVert != null ? tileVert : code) : (opaqueVert != null ? opaqueVert : code);
         case "pzopt_grTile.frag": {
            Variant v = compiling;
            String src = (compilingKind & 1) == 0 ? tileFrag : opaqueFrag;
            String p = v == null || src == null ? null : variantSource(src, v, compilingKind / 2);
            return p != null ? p : FAILED_SOURCE;
         }
         default:
            return code;
      }
   }

   private static final String FAILED_SOURCE = "#version 120\n#error pzopt ground: no floor variant\nvoid main() {}\n";

   /** The uniforms and helpers every floor variant has (after the header). */
   static final String GLSL_FLOOR_HEAD = String.join("\n",
      "uniform vec4 pzgMap;", // u = x - y = fragX * x + y, v = x + y - 6z = fragY * z + w (GroundBake.mapping)
      "uniform vec4 pzgBake;", // x: the level, y: dev view, z, w: 0
      GroundState.GLSL,
      GLSL_CLASS,
      "vec3 pzgW;",
      "ivec2 pzgSq;",
      "vec2 pzgF;",
      "int pzgClass;",
      "bool pzgAttached;",
      "int pzgPiece;",
      "");

   static final String GLSL_FLOOR_SETUP = String.join("\n",
      "int pzgMaterial() { return pzgClass & 15; }",
      "void pzgSetup() {",
      "   float u = gl_FragCoord.x * pzgMap.x + pzgMap.y;",
      "   float v = gl_FragCoord.y * pzgMap.z + pzgMap.w;",
      "   float z = pzgBake.x;",
      "   pzgW = vec3((v + u) * 0.5 + 3.0 * z, (v - u) * 0.5 + 3.0 * z, z);",
      "   vec2 fl = floor(pzgW.xy);",
      "   pzgSq = ivec2(fl);",
      "   pzgF = pzgW.xy - fl;",
      "#ifdef PZG_NO_CLASS_FETCH",
      "   pzgClass = 1;", // (dev probe: the variant without its class-map fetch)
      "#else",
      "   pzgClass = pzgClassAt(pzgSq);",
      "#endif",
      "   pzgPiece = PZG_PIECE;",
      "   pzgAttached = PZG_PIECE != 0;",
      "}",
      "");

   /** dev views (devGroundView): 1 = the world position as a checker of squares and an 8-square grid, 2 = the class map's material. */
   static final String GLSL_VIEW = String.join("\n",
      "vec4 pzgView(vec4 c) {",
      "#if PZG_VIEW == 1",
      "   float chk = mod(float(pzgSq.x + pzgSq.y), 2.0);",
      "   vec2 g = abs(fract(pzgW.xy / 8.0) - 0.5);",
      "   float line = max(g.x, g.y) > 0.485 ? 1.0 : 0.0;",
      "   vec3 t = mix(vec3(0.15, 0.55, 0.95) * (0.6 + 0.4 * chk), vec3(1.0, 0.2, 0.1), line);",
      "   return vec4(mix(c.rgb, t * c.a, 0.65), c.a);",
      "#elif PZG_VIEW == 2",
      "   int m = pzgClass & 15;",
      "   vec3 t = m == 1 ? vec3(0.1, 0.8, 0.1) : m == 2 ? vec3(0.5, 0.3, 0.1) : m == 3 ? vec3(0.9, 0.85, 0.4) : m == 4 ? vec3(0.5, 0.5, 0.55)",
      "      : m == 8 ? vec3(0.8, 0.4, 0.2) : m == 9 ? vec3(0.3, 0.15, 0.05) : vec3(1.0, 0.0, 1.0);",
      "   if ((pzgClass & 32) != 0) t *= 0.6;",
      "   return vec4(mix(c.rgb, t * c.a, 0.7), c.a);",
      "#elif PZG_VIEW == 3",
      // the ground state: ruts blue, berms red, wear green, freshness lifts it, the 8-square chunk grid of the window faint
      "   vec4 st = pzgStateAt(pzgW.xy);",
      "   vec3 t = vec3(clamp(st.x * 12.0, 0.0, 1.0), st.y, clamp(-st.x * 6.0, 0.0, 1.0)) + 0.25 * st.z;",
      "   float k = clamp(max(abs(st.x) * 8.0, max(st.y, st.z)), 0.0, 1.0);",
      "   return vec4(mix(c.rgb, t * c.a, 0.85 * k), c.a);",
      "#else",
      "   return c;",
      "#endif",
      "}",
      "");

   /** The variant's fragment source from the stock program's final source; null if the source is not as expected. */
   static String variantSource(String stock, Variant v, int piece) {
      return variantSource(stock, v.patches, v.view, piece);
   }

   static String variantSource(String stock, java.util.List<FloorPatch> patches, int view, int piece) {
      StringBuilder head = new StringBuilder();
      head.append("#define PZG_FLOOR 1\n#define PZG_VIEW ").append(view).append('\n');
      head.append("#define PZG_PIECE ").append(piece).append('\n');
      for (FloorPatch p : patches) {
         String d = p.defines();
         if (d != null && !d.isEmpty()) {
            head.append(d).append('\n');
         }
      }
      head.append(GroundHash.GLSL).append(GLSL_FLOOR_HEAD);
      String c = GroundHash.insertAfterHeader(stock, head.toString(), 130);
      if (c == null) {
         return null;
      }
      StringBuilder pre = new StringBuilder(GLSL_FLOOR_SETUP).append(GLSL_VIEW);
      StringBuilder texelChain = new StringBuilder("vec4 pzgTexel(vec4 t) {\n");
      StringBuilder colorChain = new StringBuilder("vec4 pzgColor(vec4 c) {\n");
      for (FloorPatch p : patches) {
         pre.append("// ground patch ").append(p.id()).append('\n').append(p.declarations()).append('\n');
         if (p.texel() != null) {
            texelChain.append("   t = ").append(p.texel()).append("(t);\n");
         }
         if (p.color() != null) {
            colorChain.append("   c = ").append(p.color()).append("(c);\n");
         }
      }
      texelChain.append("   return t;\n}\n");
      colorChain.append("   return pzgView(c);\n}\n");
      pre.append(texelChain).append(colorChain);
      int at = c.indexOf("void main");
      if (at < 0) {
         return null;
      }
      c = c.substring(0, at) + pre + c.substring(at);
      // pzgSetup() first in main
      at = c.indexOf("void main");
      int brace = c.indexOf('{', at);
      if (brace < 0) {
         return null;
      }
      c = c.substring(0, brace + 1) + "\n   pzgSetup();" + c.substring(brace + 1);
      // the art texel: the stock fetch of DIFFUSE at texCoord (the first one in main)
      int mainAt = c.indexOf("void main");
      java.util.regex.Matcher mt = java.util.regex.Pattern.compile("texture2D\\s*\\(\\s*DIFFUSE\\s*,\\s*texCoord(\\.st|\\.xy)?\\s*(,\\s*0\\.0\\s*)?\\)").matcher(c);
      if (mt.find(mainAt)) {
         c = c.substring(0, mt.start()) + "pzgTexel(" + mt.group(0) + ")" + c.substring(mt.end());
      } else {
         Log.warn("ground: the tile program's art fetch was not found; texel patches do nothing");
      }
      // the colour written
      java.util.regex.Matcher md = java.util.regex.Pattern.compile("gl_FragData\\[0\\]\\s*=\\s*([^;]+);").matcher(c);
      StringBuilder sb = new StringBuilder();
      int n = 0;
      while (md.find()) {
         if (md.start() < mainAt) {
            continue;
         }
         md.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("gl_FragData[0] = pzgColor(" + md.group(1) + ");"));
         n++;
      }
      md.appendTail(sb);
      if (n == 0) {
         java.util.regex.Matcher mc = java.util.regex.Pattern.compile("gl_FragColor\\s*=\\s*([^;]+);").matcher(c);
         sb = new StringBuilder();
         while (mc.find()) {
            mc.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("gl_FragColor = pzgColor(" + mc.group(1) + ");"));
            n++;
         }
         mc.appendTail(sb);
      }
      if (n == 0) {
         return null;
      }
      return sb.toString();
   }

   private static void build(Variant v) {
      zombie.tileDepth.TileDepthShader st = zombie.core.SceneShaderStore.tileDepthShader;
      zombie.tileDepth.TileDepthShader so = zombie.core.SceneShaderStore.opaqueDepthShader;
      if (st == null || !st.isCompiled() || tileFrag == null || tileVert == null) {
         v.state = Variant.FAILED;
         VARIANTS.remove(v.sig); // (asked again next frame)
         return;
      }
      v.stockTile = st.getID();
      v.stockOpaque = so != null && so.isCompiled() ? so.getID() : -1;
      try {
         boolean opaque = v.stockOpaque > 0 && opaqueFrag != null && opaqueVert != null;
         for (int piece = 0; piece < (v.attachedFlag ? 3 : 1); piece++) {
            v.prog[piece * 2] = compile(v, piece * 2, st);
            v.prog[piece * 2 + 1] = opaque ? compile(v, piece * 2 + 1, so) : 0;
            for (int k = 0; k < 2; k++) {
               int id = v.prog[piece * 2 + k];
               v.shaders[piece * 2 + k] = id == 0 ? null : (zombie.tileDepth.TileDepthShader)zombie.core.opengl.Shader.ShaderMap.get(id);
               if (id != 0 && v.shaders[piece * 2 + k] == null) {
                  v.prog[piece * 2 + k] = 0;
               }
            }
            if (v.prog[piece * 2] == 0 && piece == 0) {
               v.state = Variant.FAILED;
               return;
            }
         }
         compileSeam(v);
         v.state = Variant.READY;
         Log.info("ground: floor variant " + v.sig + " = programs " + java.util.Arrays.toString(v.prog) + " (tile / opaque per piece: floor, natural edge, overlay), seam " + v.seamProg + ", stock " + v.stockTile + " / " + v.stockOpaque + " / " + v.stockSeam);
      } catch (Throwable t) {
         Log.warn("ground: floor variant " + v.sig + " failed: " + t);
         v.state = Variant.FAILED;
      }
   }

   /** The seam strips' variant (piece 0): without it the border strips keep the stock art (a line along every chunk edge). */
   private static void compileSeam(Variant v) {
      zombie.tileDepth.TileSeamShader ss = zombie.core.SceneShaderStore.tileSeamShader;
      if (ss == null || !ss.isCompiled() || seamFrag == null || seamVert == null) {
         return;
      }
      compiling = v;
      compilingKind = 0;
      try {
         zombie.tileDepth.TileSeamShader s = new zombie.tileDepth.TileSeamShader("pzopt_grSeam");
         if (s.getProgram() == null || !s.isCompiled()) {
            Log.warn("ground: floor variant " + v.sig + ": the seam program did not compile (stock seam strips)");
            return;
         }
         int id = s.getID();
         Sway.aliasPatchedTile("pzopt_grSeam", "seamFix2");
         initSamplers(v, id);
         v.seamMapLoc = GL20.glGetUniformLocation(id, "pzgMap");
         v.seamShader = s;
         v.seamProg = id;
         v.stockSeam = ss.getID();
      } catch (Throwable t) {
         Log.warn("ground: floor variant " + v.sig + ": seam program failed: " + t);
      } finally {
         compiling = null;
      }
   }

   /** The variant's samplers off unit 0 (the game numbered them in order at the link), the patches' own, its view. */
   private static void initSamplers(Variant v, int id) {
      int prev = Ssr.boundProgram();
      GL20.glUseProgram(id);
      set1i(id, "pzgClassTex", CLASS_UNIT);
      set1i(id, "pzgStateTex", STATE_UNIT);
      for (FloorPatch p : v.patches) {
         for (String[] sm : p.samplers()) {
            set1i(id, sm[0], Integer.parseInt(sm[1]));
         }
      }
      int lb = GL20.glGetUniformLocation(id, "pzgBake");
      if (lb >= 0) {
         GL20.glUniform4f(lb, 0F, v.view, 0F, 0F);
      }
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      GL20.glUseProgram(prev > 0 ? prev : 0);
   }

   /** One variant program; 0 when it does not compile or a stock uniform moved. */
   private static int compile(Variant v, int kind, zombie.tileDepth.TileDepthShader stock) {
      compiling = v;
      compilingKind = kind;
      try {
         zombie.tileDepth.TileDepthShader s = new zombie.tileDepth.TileDepthShader("pzopt_grTile");
         if (s.getProgram() == null || !s.isCompiled()) {
            Log.warn("ground: floor variant " + v.sig + " (kind " + kind + ") did not compile: stock floors");
            return 0;
         }
         String bad = sameUniforms(stock.getID(), s.getID());
         if (bad != null && kind == 0) {
            // harmless: IsoSprite builds the floor's uniform setters from the variant itself (GroundBake.shader), TileBatch,
            // foliage sway and the uniform cache look locations up per program
            Log.info("ground: floor variant " + v.sig + ": stock uniform locations differ (" + bad + "), set through the variant's own");
         }
         int id = s.getID();
         Sway.aliasPatchedTile("pzopt_grTile", kind % 2 == 0 ? "tileWithDepth" : "opaqueWithDepth");
         initSamplers(v, id);
         v.mapLoc[kind] = GL20.glGetUniformLocation(id, "pzgMap");
         return id;
      } finally {
         compiling = null;
         compilingKind = 0;
      }
   }

   private static void set1i(int program, String name, int value) {
      int l = GL20.glGetUniformLocation(program, name);
      if (l >= 0) {
         GL20.glUniform1i(l, value);
      }
   }

   /** Null when every active uniform of {@code stock} has the same location in {@code variant}, else its name (SpriteFilter's rule). */
   private static String sameUniforms(int stock, int variant) {
      int n = GL20.glGetProgrami(stock, GL20.GL_ACTIVE_UNIFORMS);
      try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
         java.nio.IntBuffer size = st.mallocInt(1), type = st.mallocInt(1);
         for (int i = 0; i < n; i++) {
            String name = GL20.glGetActiveUniform(stock, i, size, type);
            int a = GL20.glGetUniformLocation(stock, name);
            int b = GL20.glGetUniformLocation(variant, name);
            if (a != b) {
               return name + " (" + a + " -> " + b + ")";
            }
         }
      }
      return null;
   }

   // ------------------------------------------------------------------------------------------------ per bake (render thread)

   private static final float[] MAP = new float[4];
   private static boolean mapValid;

   /** TextureDraw FBORenderChunkStart (render thread, after the chunk's FBO is bound): the variant's mapping for this texture. */
   public static void chunkStart() {
      Variant v = current;
      if (v == null && !PASS_ON && !mapValid) {
         return; // (every ground feature off: nothing to do)
      }
      mapValid = false;
      FBORenderChunk rc = FBORenderChunkManager.instance.renderThreadCurrent;
      if (rc == null || rc.chunk == null) {
         return;
      }
      if (v == null || v.state != Variant.READY) {
         if (!PATCHES.isEmpty() || !PASSES.isEmpty()) {
            mapping(rc, MAP);
            mapValid = true;
         }
         return;
      }
      mapping(rc, MAP);
      mapValid = true;
      GpuSections.markNow(Ground.section("ground.bakeStart"), false);
      int prev = Ssr.boundProgram();
      boolean bound = false;
      for (int k = 0; k < 7; k++) {
         int id = k < 6 ? v.prog[k] : v.seamProg;
         if (id == 0) {
            continue;
         }
         GL20.glUseProgram(id);
         bound = true;
         int l = k < 6 ? v.mapLoc[k] : v.seamMapLoc;
         if (l >= 0) {
            GL20.glUniform4f(l, MAP[0], MAP[1], MAP[2], MAP[3]);
         }
         GroundState.applyUniforms(id);
         for (FloorPatch p : v.patches) {
            try {
               p.bakeStart(id, rc);
            } catch (Throwable t) {
               Log.warn("ground: patch " + p.id() + " bakeStart failed: " + t);
            }
         }
      }
      if (bound) {
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         GL20.glUseProgram(prev > 0 ? prev : 0);
      }
      bindClassMap();
      GroundState.bindTexture();
      GpuSections.markNow(Ground.section("ground.bakeStart"), true);
   }

   /** Render thread, during a bake (after FBORenderChunkStart): the current texture's mapping into {@code out} (4 floats); false outside a ground-relevant bake. */
   public static boolean bakeMapping(float[] out) {
      if (!mapValid) {
         return false;
      }
      System.arraycopy(MAP, 0, out, 0, 4);
      return true;
   }

   /**
    * The mapping of render chunk {@code rc}'s bake: u = x - y = fragX * m[0] + m[1], v = x + y - 6z = fragY * m[2] + m[3]
    * (gl_FragCoord of the bake; FBORenderChunkManager.beginRenderChunkLevel's offsets, Core.DoStartFrameFlipY's projection:
    * a high-res texture's ortho covers [w/4, 3w/4] x [0, h/2] of its logical space).
    */
   static void mapping(FBORenderChunk rc, float[] m) {
      int ts = Core.tileScale;
      float s = rc.highRes ? 2F : 1F;
      float goX = rc.w / 2F;
      float yoff = (rc.getTopLevel() - rc.getMinLevel() + 1) * FBORenderChunk.PIXELS_PER_LEVEL + rc.getMinLevel() * FBORenderChunk.PIXELS_PER_LEVEL
         + zombie.iso.fboRenderChunk.FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel());
      float ox = rc.highRes ? rc.w / 4F : 0F;
      m[0] = 1F / (s * 32F * ts);
      m[1] = (ox - goX) / (32F * ts) + 8F * (rc.chunk.wx - rc.chunk.wy);
      m[2] = 1F / (s * 16F * ts);
      m[3] = -yoff / (16F * ts) + 8F * (rc.chunk.wx + rc.chunk.wy);
   }

   public static String stats() {
      StringBuilder b = new StringBuilder("ground bake: floor ").append(FLOOR_ON ? "on (" + signature + ")" : "off").append(", passes ")
         .append(PASS_ON ? passSignature : "off").append(", class map ").append(CLASS_ON ? "on" : "off").append(", natural floors ")
         .append(naturalFloors).append(" stock ").append(stockFloors).append(", class uploads ").append(classUploads)
         .append(", sprites looked up ").append(GroundTiles.lookups);
      synchronized (REBAKES) {
         if (!REBAKES.isEmpty()) {
            b.append(", re-bakes:");
            for (java.util.Map.Entry<String, long[]> e : REBAKES.entrySet()) {
               b.append(' ').append(e.getKey().replace(' ', '_')).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]);
            }
         }
      }
      return b.toString();
   }
}
