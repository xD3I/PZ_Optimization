package pzopt;

import java.util.ArrayList;
import java.util.List;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.IOpenGLState;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.sprite.IsoSprite;

/**
 * The per-frame translucent tile pass recorded per chunk (tileRecordParallel, docs/plan-louisville-120-structural.md
 * change A): each on-screen chunk's translucent objects of the level are one unit, recorded into its own
 * {@link DrawRecorder} and spliced into the frame's draw list in stock chunk order.
 *
 * <p>Phase A1 ({@code devTileRecordSerial}): every unit is recorded on the game thread, one after the other, so the
 * recorder / splice machinery can be checked against the stock pass ({@code devDrawListCheck}) and its overhead
 * measured before any worker records a unit. Phase A2 ({@code tileRecordParallel}): the units on the frame workers.
 */
public final class TileRecord {
   private TileRecord() {
   }

   public static final int DEFER_OBJECT = 0; // FBORenderCell.renderTranslucent(IsoObject) on the game thread
   public static final int DEFER_ITEMS = 1; // FBORenderCell.renderWorldInventoryObjects(square, renderSquare, false)
   public static final int DEFER_MASK = 2; // tileRecordWeather: WeatherFxMask's open door / objects / characters (DEFER_MASK + 0..2)

   private static final ArrayList<DrawRecorder> recorders = new ArrayList<>(); // every thread's recorder (reset after each pass)
   private static final ThreadLocal<DrawRecorder> threadRecorder = ThreadLocal.withInitial(() -> {
      DrawRecorder r = new DrawRecorder();
      synchronized (recorders) {
         recorders.add(r);
      }
      return r;
   });
   // per recorded unit of the pass: its recorder and its entry / event ranges there
   private static DrawRecorder[] unitRec = new DrawRecorder[64];
   private static int[] unitEntryFrom = new int[64], unitEntryTo = new int[64], unitEvFrom = new int[64], unitEvTo = new int[64];
   // f8-render1: each unit's first postRender entry in its recorder: a recorder's units are spliced in another order than
   // recorded (a level's floor units before its translucent ones, the weather parts last), and the splice's postRender
   // cursor must start at the unit's own entries, or a model's DrawModel entry missed the frame's postRender (its drawer
   // never released, left on the entry object for the next StartShader: devDrawListCheck "drawer" differences)
   private static int[] unitPostFrom = new int[64];
   private static boolean failed;
   private static int[] work = new int[64];
   private static final int UNIFORM_POOL = 512; // ShaderUniformSetter entries each recorder holds at a pass start (a tile-depth draw takes four)

   // dev counters, logged with the periodic FBORenderCell line
   static long passes, units, drawn, defers, conds, condsKept, checks, checkEntries, checkMismatches, recordNanos, spliceNanos, stockNanos;
   static long lightOrderDefers, checkLate; // tileRecordLightOrder: objects deferred to keep stock's light refresh order; devDrawListCheck: differences of draws on a native-late square
   private static int checkLogged, lateLogged;

   /** Whether this frame's translucent pass goes through the recorders. */
   public static boolean active() {
      return (Config.TILE_RECORD_PARALLEL || Config.DEV_TILE_RECORD_SERIAL) && !failed && Overrides.enabled() && GtAb.on(GtAb.TILE_RECORD)
         && (Config.TILE_RECORD_VISUALS || !Config.MIRRORS && !PixelLight.ACTIVE && !Sway.frameOn) && zombie.characters.IsoPlayer.numPlayers == 1;
   }

   /**
    * Whether a recording thread may draw {@code o} itself. Everything else is deferred to the game thread at its place
    * in the stream. A1 keeps the A2 rule so its counts and overhead are the real ones: plain tiles, windows, doors,
    * curtains and window frames, with no light-on overlay (the power check walks generators and rooms), no clock hands,
    * no roof seam joining (it draws the neighbour square's roof tile, often another chunk's unit) and no 3D model.
    */
   static boolean eligible(IsoObject o) {
      return deferReason(o) < 0;
   }

   private static boolean modelsOn; // this frame's translucent units may draw 3D sprite models (tileRecordModels, GtAb)

   /**
    * tileRecordModels (f8-render1): a static 3D sprite model (a door) a recording thread may draw: its model and texture
    * loaded and ready (nothing loads or creates), no animation (player or palette frame), no render effects, no highlight or
    * outline. IsoObjectModelDrawer's pools are locked and it reads the recorder's render mode (override); IsoObject.renderModel
    * scales a copy of the light on a recording thread.
    */
   static boolean modelRecordable(IsoObject o) {
      zombie.iso.SpriteModel sm = o.getSpriteModel();
      if (sm == null || sm.getTextureName() != null) {
         modelWhy[0]++;
         return false;
      }
      int p = zombie.iso.IsoCamera.frameState.playerIndex;
      if (o.isHighlighted(p) || o.isOutlineHighlight(p) || o.getObjectRenderEffectsToApply() != null) {
         modelWhy[1]++;
         return false;
      }
      zombie.scripting.objects.ModelScript ms = zombie.scripting.ScriptManager.instance.getModelScript(sm.modelScriptName);
      if (ms == null || ms.getModelManagerKey() == null) {
         modelWhy[2]++;
         return false;
      }
      zombie.core.skinnedmodel.model.Model m = zombie.core.skinnedmodel.ModelManager.instance.tryGetLoadedModel(ms.getModelManagerKey(), false);
      if (m == null || m.isFailure() || !m.isReady() || m.mesh == null || !m.mesh.isReady() || m.tex != null && !m.tex.isReady()) {
         modelWhy[3]++;
         return false;
      }
      if (zombie.core.skinnedmodel.model.IsoObjectAnimations.getInstance().getAnimationPlayer(o) != null) {
         modelWhy[4]++;
         return false;
      }
      return true;
   }

   static final long[] modelWhy = new long[5]; // dev: why a model stayed on the game thread (texture name, highlight / effects, script, not loaded, animating); racy counts

   static final String[] REASONS = {"deferAll", "class", "noSprite", "lightOn", "roof", "model", "clock", "floorPath", "wallPath", "fascia", "attached", "cold", "mirror", "seam", "lightOrder"};

   static final java.util.concurrent.atomic.AtomicLongArray reasons = new java.util.concurrent.atomic.AtomicLongArray(REASONS.length);
   /**
    * The dev census of deferred objects by class and reason (instrumented runs). f8-render1: counted per class without
    * building a key string per deferred object (the census allocated a string per defer on every bench run: ~80 a frame
    * with both recorders on, more ZGC work in the measured frames); the strings are made when the line is written.
    */
   static final class Census {
      final String prefix;
      final String[] reasons;
      private final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.concurrent.atomic.LongAdder[]> map = new java.util.concurrent.ConcurrentHashMap<>();

      Census(String prefix, String[] reasons) {
         this.prefix = prefix;
         this.reasons = reasons;
      }

      void add(Class<?> c, int why) {
         java.util.concurrent.atomic.LongAdder[] a = this.map.get(c);
         if (a == null) {
            a = this.map.computeIfAbsent(c, k -> {
               java.util.concurrent.atomic.LongAdder[] x = new java.util.concurrent.atomic.LongAdder[this.reasons.length];
               for (int i = 0; i < x.length; i++) {
                  x[i] = new java.util.concurrent.atomic.LongAdder();
               }
               return x;
            });
         }
         a[why].increment();
      }

      void into(java.util.Map<String, java.util.concurrent.atomic.LongAdder> out) {
         for (java.util.Map.Entry<Class<?>, java.util.concurrent.atomic.LongAdder[]> e : this.map.entrySet()) {
            for (int i = 0; i < this.reasons.length; i++) {
               if (e.getValue()[i].sum() > 0) {
                  out.put(this.prefix + e.getKey().getSimpleName() + ":" + this.reasons[i], e.getValue()[i]);
               }
            }
         }
      }
   }

   static final Census tileCensus = new Census("", REASONS);

   /** The census as "[bake:[floor:]]Class:reason" -> count (made when a line is written). */
   static java.util.Map<String, java.util.concurrent.atomic.LongAdder> deferClasses() {
      java.util.Map<String, java.util.concurrent.atomic.LongAdder> m = new java.util.HashMap<>();
      tileCensus.into(m);
      BakeRecord.floorCensus.into(m);
      BakeRecord.objectCensus.into(m);
      return m;
   }

   /**
    * -1 when a recording thread may draw {@code o}, else the index of the first reason it may not (REASONS). The rule
    * keeps the workers on the path that was audited for them: a plain tile through renderMinusFloor_NotDoorOrWall ->
    * IsoObject.render -> IsoSprite's tile-depth draw, with its overlay and attached sprites. Off it: other classes
    * (windows / doors / light switches / appliances), the floor and the door / wall paths (IsoGridSquare's wall lighting,
    * not made thread-safe), light-on overlays (the power check walks generators and rooms), roof seam joins (they draw
    * the neighbour square's roof tile, often another unit's; a roof sprite's lazy init runs on the game thread first),
    * 3D sprite models, clocks (they swap their shared sprite's texture while drawing), fascia (cutaway building
    * queries) and children / wall blood splats.
    */
   static int deferReason(IsoObject o) {
      return deferReason(o, false);
   }

   /**
    * {@code bake}: bakeRecordParallel's object pass. A bake draws a roof tile's joined neighbour (FBORenderCell's
    * renderJoinedRoofTile: another chunk's or level's object, its render info recomputed) only where the tile can have a
    * seam (a chunk edge, a level-below join): only those roof tiles stay on the game thread there.
    */
   static int deferReason(IsoObject o, boolean bake) {
      if (Config.DEV_TILE_RECORD_DEFER_ALL) {
         return 0;
      }
      if (Config.MIRRORS && Mirrors.capturable(o)) {
         return 12; // mirrors capture a window / mirror / reflective prop's quad as it draws (game-thread state)
      }
      if (!bake && lightTainted(o)) {
         return 14; // tileRecordLightOrder: an object deferred before it in its unit may refresh its square's light at the splice
      }
      if (!bake && floorsOn && o.sprite != null && o.sprite.getProperties().has(IsoFlagType.transparentFloor)) {
         // tileRecordFloors (f8-render1): a transparent floor goes FBORenderCell.renderFloor's way; bakeRecordParallel made
         // that path per thread (floor shapers, seam fix, offsets), so the same rule as a bake's floors decides
         return BakeRecord.floorReason(o) < 0 ? -1 : 7;
      }
      Class<?> c = o.getClass();
      boolean window = c == zombie.iso.objects.IsoWindow.class;
      boolean door = c == zombie.iso.objects.IsoDoor.class;
      if (c != IsoObject.class && c != zombie.iso.objects.IsoLightSwitch.class && !window && !door) {
         return 1;
      }
      if (door && (((zombie.iso.objects.IsoDoor)o).HasCurtains() != null || o.getProperties() != null && o.getProperties().has(zombie.core.properties.IsoPropertyType.DOUBLE_DOOR))) {
         return 1; // a curtained door draws through IsoDoor's static colour scratch; a double door asks its master half (maybe another unit's) for its model
      }
      IsoSprite s = o.sprite;
      if (s == null || o.square == null) {
         return 2;
      }
      if (bake && seamWall(o)) {
         return 13; // FBORenderCell.renderSeamFix2_Wall's seam tile: TileSeamModifier.setupWallDepth reads IsoSprite.seamFix2, the game thread's
      }
      zombie.core.properties.PropertyContainer props = s.getProperties();
      if (props.has(IsoFlagType.HasLightOnSprite)
         && (c != zombie.iso.objects.IsoLightSwitch.class || o.getOnOverlay() == null
            && (!bake && lightUnread(o) ? ((zombie.iso.objects.IsoLightSwitch)o).pzoptOverlayIfSeen() : o.shouldShowOnOverlay()))) {
         // (tileRecordLightOrder: shouldShowOnOverlay reads IsoGridSquare.isSeen, the square's lazy light refresh; stock runs
         // it after the switch's draw, in hasAnimatedAttachments: asked here first, it rewrote the light the switch and every
         // object of the square drawn after it read. On a square not read in this lighting pass the switch is asked only the
         // power part: a switch that could show its overlay is deferred, one that cannot is recorded and its own stock code
         // refreshes the square after its draw, as in stock.)
         // a plain object's power check walks containers and fuel pipes; a light switch's own check reads its memo and
         // the squares around (IsoLightSwitch override), but its "on" overlay instance comes from the shared sprite pool,
         // made on the game thread first (once)
         return 3;
      }
      if (!s.pzoptRoofKnown() || s.getRoofProperties() != null && (!bake || zombie.iso.fboRenderChunk.FBORenderCell.pzoptRoofJoinPossible(o.square, s)
         || BakeRecord.roofJoinTarget(o.square))) {
         return 4; // (bake: a tile that may draw a neighbour's joined, or be drawn joined by one, on the game thread at its splice)
      }
      boolean model = o.getSpriteModel() != null;
      if (model && (bake || !modelsOn || !modelRecordable(o))) {
         return 5;
      }
      if (s.name != null && zombie.scripting.ScriptManager.instance.getClockScript(s.name) != null) {
         return 6;
      }
      if (props.has(IsoFlagType.transparentFloor) || s.solidfloor) {
         return 7;
      }
      zombie.iso.SpriteDetails.IsoObjectType t = s.getTileType();
      boolean wallPath = s.cutN || s.cutW || t == zombie.iso.SpriteDetails.IsoObjectType.doorFrW || t == zombie.iso.SpriteDetails.IsoObjectType.doorFrN
         || t == zombie.iso.SpriteDetails.IsoObjectType.doorW || t == zombie.iso.SpriteDetails.IsoObjectType.doorN || o.getType() == zombie.iso.SpriteDetails.IsoObjectType.doorFrW
         || o.getType() == zombie.iso.SpriteDetails.IsoObjectType.doorFrN;
      if (wallPath && !Config.TILE_RECORD_WALLS) {
         return 8;
      }
      if (!door && (t == zombie.iso.SpriteDetails.IsoObjectType.doorW || t == zombie.iso.SpriteDetails.IsoObjectType.doorN)) {
         return 8; // a door tile that is not an IsoDoor (IsoThumpable / plain): keep it off the workers
      }
      if (o.isFascia()) {
         return 9;
      }
      if (o.pzoptHasChildrenOrSplats()) {
         return 10;
      }
      if (!o.pzoptSpritesWarm() && !(model && s.def != null)) {
         return 11; // (tileRecordModels: a recordable model draws no sprite, so its sprites need not be warm)
      }
      return -1;
   }

   /**
    * bakeRecordParallel: an object whose wall draw may add a seam tile (FBORenderCell.renderSeamFix2_Wall's conditions: a
    * north / west wall or window on the chunk's east / south edge with a wall, window frame or door wall on the next square;
    * the tileset exclusions left out, so a few more stay than need to).
    */
   static boolean seamWall(IsoObject o) {
      zombie.iso.IsoGridSquare sq = o.square;
      boolean ex = Math.floorMod(sq.x, 8) == 7, ey = Math.floorMod(sq.y, 8) == 7;
      if (!ex && !ey) {
         return false;
      }
      zombie.core.properties.PropertyContainer p = o.sprite.getProperties();
      if (p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW)) {
         return false;
      }
      boolean nw = p.has(IsoFlagType.WallNW);
      if (ey && (nw || p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WindowW))) {
         zombie.iso.IsoGridSquare s = sq.getAdjacentSquare(zombie.iso.IsoDirections.S);
         if (s != null && ((s.getWallType() & 4) != 0 || s.getWindowFrame(zombie.iso.objects.GridSquareEdgeFacingDirection.EAST_WEST) != null
            || s.has(IsoFlagType.DoorWallW))) {
            return true;
         }
      }
      if (ex && (nw || p.has(IsoFlagType.WallN) || p.has(IsoFlagType.WindowN))) {
         zombie.iso.IsoGridSquare e = sq.getAdjacentSquare(zombie.iso.IsoDirections.E);
         if (e != null && ((e.getWallType() & 1) != 0 || e.getWindowFrame(zombie.iso.objects.GridSquareEdgeFacingDirection.NORTH_SOUTH) != null
            || e.has(IsoFlagType.DoorWallN))) {
            return true;
         }
      }
      return false;
   }

   /**
    * tileRecordLightOrder (f8-twofix, 2026-10-14). A translucent draw reads its square's cached light (getLightInfo: the
    * JNILighting's ColorInfo by reference; getVertLight: its cached corners); the lazy refresh (JNILighting.update, the
    * first light read of the square in a lighting pass, by whatever code asks first) rewrites it in place. A unit runs its
    * objects' stock code in stock order, so the refreshes that code makes come at stock's place for the draws after them,
    * except two: the defer rule's own light question ({@code shouldShowOnOverlay} of a light switch, before its draw where
    * stock asks after it), and a deferred object's stock code, which refreshes at the splice, after the unit's later draws
    * read the square on the worker. So: a light switch on a square the pass has not read is deferred without asking
    * ({@link #lightUnread}), and once an object on such a square is deferred, the unit's later objects on that square are
    * deferred too ({@link #lightTainted}): they draw at the splice after it, as in stock. (Not narrowed to the chunk levels
    * getChunkDirty marks: asked at the unit start it said clean for units whose lamps then refreshed to new values,
    * twofix-lo-on2 / on3.)
    */
   static boolean lightUnread(IsoObject o) {
      if (!Config.TILE_RECORD_LIGHT_ORDER) {
         return false;
      }
      DrawRecorder r = DrawRecorder.current();
      return r != null && !r.bakeUnit && stale(o.square, zombie.iso.IsoCamera.frameState.playerIndex);
   }

   static boolean lightTainted(IsoObject o) {
      if (!Config.TILE_RECORD_LIGHT_ORDER) {
         return false;
      }
      DrawRecorder r = DrawRecorder.current();
      if (r == null || r.bakeUnit || r.taintN == 0) {
         return false;
      }
      for (int i = 0; i < r.taintN; i++) {
         if (r.taint[i] == o.square) {
            r.lightOrder++;
            return true;
         }
      }
      return false;
   }

   /** A deferred object (or a square's deferred items) whose square the pass has not read: the unit's later objects on it follow it. */
   private static void taint(DrawRecorder r, IsoGridSquare sq) {
      if (!Config.TILE_RECORD_LIGHT_ORDER || r.bakeUnit || sq == null || !stale(sq, zombie.iso.IsoCamera.frameState.playerIndex)) {
         return;
      }
      for (int i = 0; i < r.taintN; i++) {
         if (r.taint[i] == sq) {
            return;
         }
      }
      if (r.taintN == r.taint.length) {
         r.taint = java.util.Arrays.copyOf(r.taint, r.taintN * 2);
      }
      r.taint[r.taintN++] = sq;
   }

   private static boolean stale(IsoGridSquare sq, int p) {
      return sq != null && sq.lighting[p] instanceof zombie.iso.LightingJNI.JNILighting jl && jl.pzoptStaleSync();
   }


   /** FBORenderCell.renderTranslucent(IsoObject): true when the calling thread records and {@code o} is deferred instead. */
   public static boolean deferObject(IsoObject o) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      int why = deferReason(o);
      if (why < 0) {
         r.drawn++;
         return false;
      }
      if (Config.INSTRUMENT) { // dev census
         reasons.incrementAndGet(why);
         tileCensus.add(o.getClass(), why);
      }
      taint(r, o.square); // tileRecordLightOrder
      r.defer(DEFER_OBJECT, o, r.windowFrameOutline ? Boolean.TRUE : Boolean.FALSE); // drawn with the flag stock had set around it
      return true;
   }

   /** FBORenderCell.renderOneLevel_Translucent's renderWindowFrameOutline flag: true when the calling thread records (its recorder keeps it). */
   public static boolean setWindowFrameOutline(boolean v) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      r.windowFrameOutline = v;
      return true;
   }

   /** FBORenderCell.pzoptWindowFrameOutline: the recorder's flag. */
   public static boolean windowFrameOutline(DrawRecorder r) {
      return r.windowFrameOutline;
   }

   /** FBORenderCell.renderOneLevel_Translucent's items: true when the calling thread records (the items are deferred). */
   public static boolean deferItems(IsoGridSquare square, IsoGridSquare renderSquare) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      taint(r, square); // tileRecordLightOrder
      r.defer(DEFER_ITEMS, square, renderSquare);
      return true;
   }

   /**
    * FBORenderCell.renderTranslucent(square, layer), before a non-tree object: true when the calling thread records
    * (the tree flush happens at splice time on the game thread), false for the stock check.
    */
   public static boolean treeFlushPoint() {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      r.flushPoint();
      return true;
   }

   /** FBORenderCell.renderTranslucentObjects: the chunk loop, recorded per chunk and spliced in order. */
   public static void translucentPass(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms) {
      if (spliceFrameLevel(cell, chunks, playerIndex, z, floorShader, wallShader, ms)) {
         return;
      }
      dropWeather();
      if (failed) { // (f8-render1: a batch timed out this frame: the stock pass)
         for (int i = 0; i < chunks.size(); i++) {
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
         }
         return;
      }
      int n = chunks.size();
      GenericSpriteRenderState real = DrawRecorder.real();
      passes++;
      modelsOn = Config.TILE_RECORD_MODELS && GtAb.on(GtAb.TILE_MODELS);
      boolean check = Config.DEV_DRAW_LIST_CHECK > 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_DRAW_LIST_CHECK == 0;
      Check snap = null;
      if (check) {
         snap = Check.stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real);
      }
      long t0 = System.nanoTime();
      // units with nothing to draw at this level (most on-screen chunks) are not recorded: their stock call, a few
      // state entries, runs at its place in the splice
      int w = 0;
      grow(n);
      for (int i = 0; i < n; i++) {
         if (cell.pzoptTranslucentWork(chunks.get(i), playerIndex, z)) {
            work[w++] = i;
         }
      }
      final int nw = w;
      final int[] wk = work;
      prepareDispatch(nw);
      DrawRecorder.recording = true;
      Throwable failure = null;
      try {
         if (Config.TILE_RECORD_PARALLEL && nw > 1) {
            failure = FrameBatch.runWithin(nw, k -> record(cell, k, chunks.get(wk[k]), playerIndex, z, floorShader, wallShader, ms, real, false), timeoutNs());
         } else {
            for (int k = 0; k < nw; k++) {
               record(cell, k, chunks.get(wk[k]), playerIndex, z, floorShader, wallShader, ms, real, false);
            }
         }
      } catch (Throwable t) {
         failure = t;
      } finally {
         DrawRecorder.recording = false;
      }
      long t1 = System.nanoTime();
      if (failure instanceof FrameBatch.Timeout) {
         // a recording unit hangs: nothing of this pass is spliced, the stock pass draws the level, the stock path from now on
         timedOut(failure.getMessage());
         for (int i = 0; i < n; i++) {
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
         }
         return;
      }
      DrawRecorder.moveRef = Config.TILE_RECORD_MOVE_REF && GtAb.on(GtAb.TILE_MOVE_REF);
      Deferred deferred = new Deferred(cell);
      DrawRecorder.carryZ = TextureDraw.nextZ; // the stream's carried next-draw depth
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      int k = 0;
      for (int i = 0; i < n; i++) {
         if (k < nw && wk[k] == i) {
            DrawRecorder r = unitRec[k];
            LightingDefer.applyOne(k); // the unit's lazy-lighting side effects (level invalidations, puddle / cutaway / room hooks), as stock ran them during its draws
            if (r != null) {
               r.seekPost(unitPostFrom[k]); // (f8-render1: the unit's own postRender entries)
               r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
            }
            unitRec[k] = null;
            k++;
         } else {
            TextureDraw.nextZ = DrawRecorder.carryZ;
            TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
            DrawRecorder.carryZ = TextureDraw.nextZ;
            DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
         }
      }
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      collectCounters();
      long t2 = System.nanoTime();
      recordNanos += t1 - t0;
      spliceNanos += t2 - t1;
      if (GtAb.TIMING) {
         GtAb.add(GtAb.S_TL_RECORD, t1 - t0);
         GtAb.add(GtAb.S_TL_SPLICE, t2 - t1);
      }
      if (failure != null) {
         failed = true;
         Log.warn("tileRecordParallel: a unit failed, the stock pass from now on: " + failure);
         failure.printStackTrace();
      }
      if (snap != null) {
         snap.compare(real);
      }
   }

   /**
    * Game thread, before units record: what the recording threads read or take. IsoSprite's screen offset (lazily set
    * by the first draw of a frame, the same value from any thread), each recorder's uniform pool, the lazy-lighting
    * effect lists of {@code n} units.
    */
   private static void prepareDispatch(int n) {
      zombie.iso.IsoGridSquare.pzoptWarmCutawayTextures();
      if (zombie.iso.sprite.IsoSprite.globalOffsetX == -1.0F) {
         zombie.iso.sprite.IsoSprite.globalOffsetX = -zombie.iso.IsoCamera.frameState.offX;
         zombie.iso.sprite.IsoSprite.globalOffsetY = -zombie.iso.IsoCamera.frameState.offY;
      }
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            ShaderUniformSetter.pzoptRefill(recorders.get(q), UNIFORM_POOL);
         }
      }
      LightingDefer.prepare(n);
   }

   private static void grow(int n) {
      if (work.length < n) {
         int m = Math.max(n, work.length * 2);
         work = new int[m];
         unitRec = new DrawRecorder[m];
         unitEntryFrom = new int[m];
         unitEntryTo = new int[m];
         unitEvFrom = new int[m];
         unitPostFrom = new int[m];
         unitEvTo = new int[m];
         unitZ = new int[m];
         unitFloor = new boolean[m];
      }
   }

   private static void collectCounters() {
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            DrawRecorder r = recorders.get(q);
            r.reset();
            units += r.units;
            defers += r.defers;
            conds += r.conds;
            condsKept += r.condsKept;
            drawn += r.drawn;
            lightOrderDefers += r.lightOrder;
            r.units = r.defers = r.conds = r.condsKept = r.drawn = r.lightOrder = 0;
         }
      }
   }

   // ---- tileRecordAsync: every level's units recorded in one batch while the game thread draws the rest of the frame ----

   private static boolean frameOn; // this frame's units were dispatched by startFrame
   private static boolean framePending; // their batch has not been joined yet
   private static int frameUnits, frameMinZ, frameMaxZ, frameLevelsSpliced;
   private static int[] levelFrom = new int[64], levelTo = new int[64]; // per level (z - frameMinZ), its units [from, to) (chunk order)
   private static int[] unitZ = new int[64];
   private static boolean[] unitFloor = new boolean[64]; // tileRecordFloors: the unit is a chunk's translucent floor pass of its level
   private static int[] floorFrom = new int[64], floorTo = new int[64]; // per level, its floor units [from, to) (floor chunk order)
   private static List<IsoChunk> frameFloorChunks; // the frame's chunksWithTranslucentFloor when its floor units were dispatched
   private static boolean floorsOn; // this frame's translucent floor passes are recorded (tileRecordFloors, GtAb)
   private static AlphaSnap asyncSnapFloor; // devDrawListCheck, async: the floor chunks' objects' alpha at dispatch
   private static List<IsoChunk> frameChunks;
   private static volatile Throwable frameFailure;
   private static AlphaSnap asyncSnap; // devDrawListCheck, async: the objects' alpha at dispatch

   /** The alpha / target alpha of every object on the given levels of the chunks (devDrawListCheck). */
   static final class AlphaSnap {
      final ArrayList<IsoObject> objects = new ArrayList<>();
      final ArrayList<float[]> alphas = new ArrayList<>();

      static AlphaSnap capture(List<IsoChunk> chunks, int minZ, int maxZ, int playerIndex) {
         AlphaSnap a = new AlphaSnap();
         for (IsoChunk chunk : chunks) {
            if (chunk == null) {
               continue;
            }
            for (int z = minZ; z <= maxZ; z++) {
               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                     if (sq == null) {
                        continue;
                     }
                     for (int k = 0; k < sq.getObjects().size(); k++) {
                        IsoObject o = sq.getObjects().get(k);
                        a.objects.add(o);
                        a.alphas.add(new float[]{o.getAlpha(playerIndex), o.getTargetAlpha(playerIndex)});
                     }
                  }
               }
            }
         }
         return a;
      }

      void restore(int playerIndex) {
         for (int i = 0; i < this.objects.size(); i++) {
            IsoObject o = this.objects.get(i);
            float[] v = this.alphas.get(i);
            o.setAlpha(playerIndex, v[0]);
            o.setTargetAlpha(playerIndex, v[1]);
         }
      }
   }
   static long asyncFrames, asyncJoinNanos, asyncDispatchNanos;

   /**
    * FBORenderCell.performRenderTiles, after the chunk composite (the per-level square lists of the frame are final):
    * every level's units of the translucent pass are handed to the frame workers at once and recorded while the game
    * thread draws the players, corpses, moving objects, water and attachments. The first translucent pass joins the
    * batch; each level is spliced at its own pass, in stock order.
    */
   public static void startFrame(FBORenderCell cell, List<IsoChunk> chunks, List<IsoChunk> floorChunks, int playerIndex, int minZ, int maxZ, Shader floorShader, Shader wallShader,
         long ms) {
      frameOn = false;
      asyncSnap = null;
      asyncSnapFloor = null;
      frameFloorChunks = null;
      floorsOn = false;
      dropWeather(); // a weather unit last frame's mask pass never took
      weatherIdx = -1;
      if (!Config.TILE_RECORD_PARALLEL || !Config.TILE_RECORD_ASYNC || !active()) {
         return;
      }
      boolean floors = Config.TILE_RECORD_FLOORS && floorChunks != null && GtAb.on(GtAb.TILE_FLOORS);
      modelsOn = Config.TILE_RECORD_MODELS && GtAb.on(GtAb.TILE_MODELS);
      long t0 = System.nanoTime();
      int levels = maxZ - minZ + 1;
      if (levels <= 0 || levels > 64) {
         return;
      }
      int n = chunks.size();
      int nf = floors ? floorChunks.size() : 0;
      grow((n + nf) * levels + WEATHER_MAX_PARTS); // (+ the weather mask parts)
      int u = 0;
      for (int z = minZ; z <= maxZ; z++) {
         levelFrom[z - minZ] = u;
         for (int i = 0; i < n; i++) {
            if (cell.pzoptTranslucentWork(chunks.get(i), playerIndex, z)) {
               work[u] = i;
               unitZ[u] = z;
               unitFloor[u] = false;
               u++;
            }
         }
         levelTo[z - minZ] = u;
      }
      if (floors) {
         for (int z = minZ; z <= maxZ; z++) {
            floorFrom[z - minZ] = u;
            for (int i = 0; i < nf; i++) {
               if (cell.pzoptTranslucentFloorWork(floorChunks.get(i), playerIndex, z)) {
                  work[u] = i;
                  unitZ[u] = z;
                  unitFloor[u] = true;
                  u++;
               }
            }
            floorTo[z - minZ] = u;
         }
      }
      int nPlayerW = playerIndex;
      if (Config.TILE_RECORD_WEATHER && GtAb.on(GtAb.TILE_WEATHER) && zombie.iso.weather.fx.WeatherFxMask.pzoptMaskRecordable(playerIndex)) {
         // tileRecordWeather: the weather mask's wall / floor loop, recorded with the rest and spliced in WeatherFxMask.drawFxMask
         weatherIdx = u;
         weatherList = zombie.iso.weather.fx.WeatherFxMask.pzoptMaskList(playerIndex);
         weatherCount = zombie.iso.weather.fx.WeatherFxMask.pzoptMaskCount(playerIndex);
         weatherParts = Math.max(1, Math.min(WEATHER_MAX_PARTS, weatherCount / 24)); // a few parts: one long unit held up the first translucent join
         for (int p = 0; p < weatherParts; p++) {
            weatherFrom[p] = weatherCount * p / weatherParts;
            weatherTo[p] = weatherCount * (p + 1) / weatherParts;
            work[u] = 0;
            unitZ[u] = 0;
            unitFloor[u] = false;
            u++;
         }
      }
      if (u == 0) {
         return;
      }
      floorsOn = floors;
      frameFloorChunks = floors ? floorChunks : null;
      frameUnits = u;
      frameMinZ = minZ;
      frameMaxZ = maxZ;
      frameLevelsSpliced = 0;
      frameChunks = chunks;
      frameFailure = null;
      prepareDispatch(u);
      if (Config.DEV_DRAW_LIST_CHECK > 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_DRAW_LIST_CHECK == 0) {
         asyncSnap = AlphaSnap.capture(chunks, minZ, maxZ, playerIndex); // the objects' alpha before the units record (the check re-runs stock from it)
         if (floors) {
            asyncSnapFloor = AlphaSnap.capture(floorChunks, minZ, maxZ, playerIndex);
         }
         LightTrace.begin(); // the recording's light refreshes are undone for the check's stock re-runs (devDrawListTrace: logged)
      }
      final GenericSpriteRenderState real = DrawRecorder.real();
      final int[] wk = work;
      final int[] uz = unitZ;
      final boolean[] uf = unitFloor;
      DrawRecorder.recording = true;
      frameOn = true;
      framePending = true;
      asyncFrames++;
      final int wIdx = weatherIdx;
      final int wParts = weatherParts;
      FrameBatch.runAsync(u, task -> {
         // the weather unit (the longest single unit) is the batch's first task, so a worker starts it at once: as the last
         // one it was still running at the first translucent pass's join (r1-wab: that join 0.04 -> 0.16 ms)
         int k = wIdx < 0 ? task : task < wParts ? wIdx + task : task - wParts;
         if (wIdx >= 0 && k >= wIdx && k < wIdx + wParts) {
            recordWeather(k, k - wIdx, nPlayerW, real);
         } else {
            record(cell, k, (uf[k] ? floorChunks : chunks).get(wk[k]), playerIndex, uz[k], floorShader, wallShader, ms, real, uf[k]);
         }
      }, failure -> {
         DrawRecorder.recording = false;
         framePending = false;
         frameFailure = failure;
      });
      asyncDispatchNanos += System.nanoTime() - t0;
   }

   /** The pass of level z: true when startFrame recorded it (it was spliced here), false = the caller's own path. */
   static boolean spliceFrameLevel(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms) {
      if (!frameOn || chunks != frameChunks || z < frameMinZ || z > frameMaxZ) {
         return false;
      }
      long t0 = System.nanoTime();
      if (framePending) {
         if (!FrameBatch.joinWithin(timeoutNs())) {
            timedOut("the frame's recording batch");
            return false; // this level and the rest of the frame: the stock passes
         }
         if (framePending) { // joined through another batch user's join: the completion ran there
            DrawRecorder.recording = false;
            framePending = false;
         }
      }
      long t1 = System.nanoTime();
      asyncJoinNanos += t1 - t0;
      GenericSpriteRenderState real = DrawRecorder.real();
      passes++;
      DrawRecorder.moveRef = Config.TILE_RECORD_MOVE_REF && GtAb.on(GtAb.TILE_MOVE_REF);
      Check snap = null;
      if (asyncSnap != null) {
         // devDrawListCheck in async mode: the stock pass of this level from the objects' state before the units
         // recorded, rolled back, then the splice; the objects get their after-recording state back in between
         AlphaSnap post = AlphaSnap.capture(chunks, z, z, playerIndex);
         asyncSnap.restore(playerIndex);
         snap = Check.stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real);
         post.restore(playerIndex);
         t1 = System.nanoTime();
      }
      Deferred deferred = new Deferred(cell);
      int from = levelFrom[z - frameMinZ];
      int to = levelTo[z - frameMinZ];
      int k = from;
      int n = chunks.size();
      DrawRecorder.carryZ = TextureDraw.nextZ; // the stream's carried next-draw depth
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      for (int i = 0; i < n; i++) {
         if (k < to && work[k] == i) {
            DrawRecorder r = unitRec[k];
            LightingDefer.applyOne(k);
            if (r != null) {
               r.seekPost(unitPostFrom[k]); // (f8-render1: the unit's own postRender entries)
               r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
            }
            unitRec[k] = null;
            k++;
         } else {
            TextureDraw.nextZ = DrawRecorder.carryZ;
            TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
            DrawRecorder.carryZ = TextureDraw.nextZ;
            DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
         }
      }
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      long t2 = System.nanoTime();
      recordNanos += t1 - t0;
      spliceNanos += t2 - t1;
      if (GtAb.TIMING) {
         GtAb.add(GtAb.S_TL_RECORD, t1 - t0);
         GtAb.add(GtAb.S_TL_SPLICE, t2 - t1);
      }
      if (snap != null) {
         snap.compare(real);
      }
      if (++frameLevelsSpliced == frameMaxZ - frameMinZ + 1) {
         endFrame();
      }
      return true;
   }

   /**
    * tileRecordFloors: the translucent floor pass of level z (FBORenderCell.renderTranslucentFloorObjects): true when
    * startFrame recorded it (spliced here in chunk order), false = the caller's own path.
    */
   public static boolean spliceFloorLevel(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms) {
      if (!frameOn || !floorsOn || chunks != frameFloorChunks || z < frameMinZ || z > frameMaxZ) {
         return false;
      }
      long t0 = System.nanoTime();
      if (framePending) {
         if (!FrameBatch.joinWithin(timeoutNs())) {
            timedOut("the frame's recording batch");
            return false;
         }
         if (framePending) {
            DrawRecorder.recording = false;
            framePending = false;
         }
      }
      long t1 = System.nanoTime();
      asyncJoinNanos += t1 - t0;
      GenericSpriteRenderState real = DrawRecorder.real();
      floorPasses++;
      DrawRecorder.moveRef = Config.TILE_RECORD_MOVE_REF && GtAb.on(GtAb.TILE_MOVE_REF);
      Check snap = null;
      if (asyncSnapFloor != null) {
         AlphaSnap post = AlphaSnap.capture(chunks, z, z, playerIndex);
         asyncSnapFloor.restore(playerIndex);
         snap = Check.stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real, true);
         post.restore(playerIndex);
         t1 = System.nanoTime();
      }
      Deferred deferred = new Deferred(cell);
      int from = floorFrom[z - frameMinZ];
      int to = floorTo[z - frameMinZ];
      int k = from;
      int n = chunks.size();
      DrawRecorder.carryZ = TextureDraw.nextZ;
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      for (int i = 0; i < n; i++) {
         if (k < to && work[k] == i) {
            DrawRecorder r = unitRec[k];
            LightingDefer.applyOne(k);
            if (r != null) {
               r.seekPost(unitPostFrom[k]); // (f8-render1: the unit's own postRender entries)
               r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
            }
            unitRec[k] = null;
            k++;
         } else {
            TextureDraw.nextZ = DrawRecorder.carryZ;
            TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
            cell.pzoptRecordChunkTranslucentFloor(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
            DrawRecorder.carryZ = TextureDraw.nextZ;
            DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
         }
      }
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      long t2 = System.nanoTime();
      floorSpliceNanos += t2 - t1;
      if (GtAb.TIMING) {
         GtAb.add(GtAb.S_TL_RECORD, t1 - t0);
         GtAb.add(GtAb.S_TL_SPLICE, t2 - t1);
      }
      if (snap != null) {
         snap.compare(real);
      }
      return true;
   }

   static long floorPasses, floorSpliceNanos;

   // ---- tileRecordWeather (f8-render1): the weather mask's loop ----
   private static int weatherIdx = -1; // the frame's weather unit (-1 none)
   private static Object weatherList; // the mask array and count it recorded (the splice checks they are unchanged)
   private static int weatherCount;
   private static boolean weatherWaiting; // recorded, endFrame passed, not spliced yet (the recorders keep their entries)
   private static final int WEATHER_MAX_PARTS = 4;
   private static int weatherParts; // the loop is recorded in this many units [weatherIdx, weatherIdx + weatherParts), masks split evenly
   private static final int[] weatherFrom = new int[WEATHER_MAX_PARTS], weatherTo = new int[WEATHER_MAX_PARTS];
   private static final boolean[] weatherBlendEnd = new boolean[WEATHER_MAX_PARTS]; // each part's glBlendfuncEnabled at its end
   private static final int[] weatherDefersPart = new int[WEATHER_MAX_PARTS]; // deferred calls per part (the check skips frames with any)
   static long weatherSpliced, weatherDropped, weatherNanos;

   private static void recordWeather(int k, int part, int nPlayer, GenericSpriteRenderState real) {
      DrawRecorder r = threadRecorder.get();
      r.batchSim = Config.TILE_RECORD_BATCH_SIM && TileBatch.ON;
      r.fixMovedIfAny();
      LightingDefer.begin(k);
      r.beginUnit(real);
      r.taintN = 0; // (tileRecordLightOrder)
      unitRec[k] = r;
      unitEntryFrom[k] = r.unitEntry;
      unitEvFrom[k] = r.unitEvent;
      unitPostFrom[k] = r.postSize();
      int defers0 = r.defers;
      r.weatherMask = true;
      r.blendEnabled = true; // (the loop sets it before every blend set)
      try {
         zombie.iso.weather.fx.WeatherFxMask.pzoptMaskLoop(nPlayer, weatherFrom[part], weatherTo[part]);
      } finally {
         weatherBlendEnd[part] = r.blendEnabled;
         weatherDefersPart[part] = r.defers - defers0;
         r.weatherMask = false;
         r.endUnit();
         LightingDefer.end();
         unitEntryTo[k] = r.state.numSprites;
         unitEvTo[k] = r.eventCount();
      }
   }

   private static boolean weatherRecorded() {
      for (int p = 0; p < weatherParts; p++) {
         if (unitRec[weatherIdx + p] == null) {
            return false;
         }
      }
      return true;
   }

   /** WeatherFxMask's loop on a recording thread: a call the game thread makes at this place of the stream (0 open door, 1 objects, 2 characters). */
   public static boolean deferMask(int kind, Object mask) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null || !r.weatherMask) {
         return false;
      }
      r.defer(DEFER_MASK + kind, mask, r.blendEnabled ? Boolean.TRUE : Boolean.FALSE);
      return true;
   }

   /**
    * WeatherFxMask.drawFxMask, in place of the mask loop: true when this frame's weather unit was spliced here (the masks
    * are the ones it recorded), false = the stock loop.
    */
   public static boolean spliceWeather(int nPlayer) {
      if (!weatherWaiting || weatherIdx < 0) {
         return false;
      }
      if (zombie.iso.weather.fx.WeatherFxMask.pzoptMaskList(nPlayer) != weatherList || zombie.iso.weather.fx.WeatherFxMask.pzoptMaskCount(nPlayer) != weatherCount) {
         dropWeather();
         return false;
      }
      long t0 = System.nanoTime();
      GenericSpriteRenderState real = DrawRecorder.real();
      DrawRecorder.moveRef = Config.TILE_RECORD_MOVE_REF && GtAb.on(GtAb.TILE_MOVE_REF);
      Check snap = null;
      int defers = 0;
      for (int p = 0; p < weatherParts; p++) {
         defers += weatherDefersPart[p];
      }
      if (Config.DEV_DRAW_LIST_CHECK > 0 && defers == 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_DRAW_LIST_CHECK == 0) {
         boolean b0 = zombie.core.SpriteRenderer.glBlendfuncEnabled;
         snap = Check.stockRun(() -> zombie.iso.weather.fx.WeatherFxMask.pzoptMaskLoop(nPlayer), real);
         zombie.core.SpriteRenderer.glBlendfuncEnabled = b0;
      }
      Deferred deferred = new Deferred(null);
      DrawRecorder.carryZ = zombie.core.textures.TextureDraw.nextZ;
      DrawRecorder.carryCd = zombie.core.textures.TextureDraw.nextChunkDepth;
      for (int p = 0; p < weatherParts; p++) {
         int k = weatherIdx + p;
         DrawRecorder r = unitRec[k];
         LightingDefer.applyOne(k);
         if (r != null) {
            r.seekPost(unitPostFrom[k]); // (f8-render1: the unit's own postRender entries)
            r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
         }
         unitRec[k] = null;
      }
      zombie.core.textures.TextureDraw.nextZ = DrawRecorder.carryZ;
      zombie.core.textures.TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      zombie.core.SpriteRenderer.glBlendfuncEnabled = weatherBlendEnd[weatherParts - 1]; // where stock's loop left the flag
      if (snap != null) {
         snap.compare(real);
      }
      weatherIdx = -1;
      weatherWaiting = false;
      collectCounters();
      weatherSpliced++;
      weatherNanos += System.nanoTime() - t0;
      return true;
   }

   /** A weather unit nothing took (the mask pass did not run, or its masks changed): its entries go with the recorders' reset. */
   private static void dropWeather() {
      if (!weatherWaiting) {
         return;
      }
      if (weatherIdx >= 0) {
         for (int p = 0; p < weatherParts; p++) {
            unitRec[weatherIdx + p] = null;
         }
      }
      weatherIdx = -1;
      weatherWaiting = false;
      weatherDropped++;
      collectCounters();
   }

   static long timeoutNs() {
      return Config.RECORD_WAIT_TIMEOUT_MS * 1_000_000L;
   }

   /**
    * A recording batch did not finish within recordWaitTimeoutMs: tileRecordParallel is off for the rest of the session
    * (failed), this frame's remaining passes take the stock path. DrawRecorder.recording stays as it is (a worker still in
    * the batch keeps drawing into its own recorder, never into the frame's state); nothing recorded this frame is spliced.
    */
   private static void timedOut(String what) {
      failed = true;
      frameOn = false;
      framePending = false;
      floorsOn = false;
      frameChunks = null;
      frameFloorChunks = null;
      weatherIdx = -1;
      weatherWaiting = false;
      Log.warn("tileRecordParallel: " + what + " did not finish in " + Config.RECORD_WAIT_TIMEOUT_MS + " ms; the stock passes from now on");
   }

   /** FBORenderCell.performRenderTiles after the level loop (and spliceFrameLevel after the last level): the frame's units are done. */
   public static void endFrame() {
      if (!frameOn) {
         return;
      }
      if (framePending) {
         if (!FrameBatch.joinWithin(timeoutNs())) {
            timedOut("the frame's recording batch");
            return;
         }
         DrawRecorder.recording = false;
         framePending = false;
      }
      frameOn = false;
      LightTrace.end();
      frameChunks = null;
      frameFloorChunks = null;
      floorsOn = false;
      for (int k = 0; k < frameUnits; k++) {
         if (weatherIdx < 0 || k < weatherIdx || k >= weatherIdx + weatherParts) {
            unitRec[k] = null;
         }
      }
      if (weatherIdx >= 0 && frameFailure == null && weatherRecorded()) {
         weatherWaiting = true; // the recorders keep their entries until the mask pass splices (or drops) the weather unit
      } else {
         weatherIdx = -1;
         collectCounters();
      }
      Throwable f = frameFailure;
      if (f != null) {
         failed = true;
         Log.warn("tileRecordParallel: a unit failed, the stock pass from now on: " + f);
         f.printStackTrace();
      }
   }

   private static void record(FBORenderCell cell, int k, IsoChunk chunk, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms,
         GenericSpriteRenderState real, boolean floor) {
      DrawRecorder r = threadRecorder.get();
      r.batchSim = Config.TILE_RECORD_BATCH_SIM && TileBatch.ON;
      r.fixMovedIfAny(); // the slots the last frame's splices moved out take their spare objects, on this recorder's own thread
      if (Config.DEV_RECORD_STALL_MS > 0 && k == 0 && asyncFrames == 600 && Thread.currentThread() instanceof FrameBatch.Worker) {
         try {
            Thread.sleep(Config.DEV_RECORD_STALL_MS); // devRecordStallMs: a hung unit
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
      LightingDefer.begin(k);
      r.beginUnit(real);
      r.taintN = 0; // tileRecordLightOrder
      r.unitZ = z; // devDrawListCheck: the unit's place in stock order (LightTrace)
      r.unitFloor = floor;
      r.unitWx = chunk.wx; // devDrawListTrace
      r.unitWy = chunk.wy;
      unitRec[k] = r;
      unitEntryFrom[k] = r.unitEntry;
      unitEvFrom[k] = r.unitEvent;
      unitPostFrom[k] = r.postSize();
      try {
         if (floor) {
            cell.pzoptRecordChunkTranslucentFloor(chunk, playerIndex, z, floorShader, wallShader, ms);
         } else {
            cell.pzoptRecordChunkTranslucent(chunk, playerIndex, z, floorShader, wallShader, ms);
         }
      } finally {
         r.endUnit();
         LightingDefer.end();
         unitEntryTo[k] = r.state.numSprites;
         unitEvTo[k] = r.eventCount();
      }
   }

   /** Game thread, at splice time: the deferred draws, through the stock path. */
   private static final class Deferred implements DrawRecorder.Deferred {
      final FBORenderCell cell;

      Deferred(FBORenderCell cell) {
         this.cell = cell;
      }

      @Override
      public void run(int kind, Object a, Object b) {
         if (kind == DEFER_OBJECT) {
            IsoObject o = (IsoObject)a;
            if (o.sprite != null && !o.sprite.pzoptRoofKnown()) {
               o.sprite.getRoofProperties(); // the sprite's lazy roof init, here on the game thread (a window or wall never reaches it on its own path), so the next frame can record it
            }
            boolean outline = b == Boolean.TRUE;
            if (outline) {
               this.cell.renderWindowFrameOutline = true;
            }
            this.cell.renderTranslucent((IsoObject)a);
            if (outline) {
               this.cell.renderWindowFrameOutline = false;
            }
         } else if (kind >= DEFER_MASK) {
            zombie.core.SpriteRenderer.glBlendfuncEnabled = b == Boolean.TRUE; // the loop's flag at this point, as stock had it
            zombie.iso.weather.fx.WeatherFxMask.pzoptMaskDeferred(kind - DEFER_MASK, (zombie.iso.weather.fx.WeatherFxMask)a);
         } else {
            this.cell.pzoptRenderItemsTranslucent((IsoGridSquare)a, (IsoGridSquare)b);
         }
      }

      @Override
      public void flushTrees() {
         this.cell.pzoptFlushTrees();
      }
   }

   static long stockPasses, stockPassNanos;

   /** FBORenderCell.renderTranslucentObjects: one stock pass took {@code ns} (the A/B's other half). */
   public static void stockPass(long ns) {
      stockPasses++;
      stockPassNanos += ns;
   }

   public static String describe() {
      String s = "tile record: async frames=" + asyncFrames + " dispatch ms=" + asyncDispatchNanos / 1_000_000L + " join ms=" + asyncJoinNanos / 1_000_000L + " stock passes=" + stockPasses + " stock us/pass=" + (stockPasses == 0 ? 0 : stockPassNanos / 1000L / stockPasses)
         + " recorded us/pass=" + (passes == 0 ? 0 : (recordNanos + spliceNanos) / 1000L / passes) + " passes=" + passes + " units=" + units + " drawn=" + drawn + " defers=" + defers + " conds=" + conds + " kept=" + condsKept
         + " record ms=" + recordNanos / 1_000_000L + " splice ms=" + spliceNanos / 1_000_000L + " floor passes=" + floorPasses + " floor splice ms=" + floorSpliceNanos / 1_000_000L + " weather spliced=" + weatherSpliced + " dropped=" + weatherDropped + " weather ms=" + weatherNanos / 1_000_000L + " model kept: tex=" + modelWhy[0] + " hl=" + modelWhy[1] + " script=" + modelWhy[2] + " load=" + modelWhy[3] + " anim=" + modelWhy[4]
         + (checks > 0 ? " check passes=" + checks + " entries=" + checkEntries + " mismatches=" + checkMismatches + " stock ms=" + stockNanos / 1_000_000L : "")
         + " lightOrder defers=" + lightOrderDefers + (checks > 0 ? " check late-light differences=" + checkLate : "") + LightTrace.describeTotals();
      if (Config.INSTRUMENT) {
         s += " defer reasons:";
         for (int i = 0; i < REASONS.length; i++) {
            s += " " + REASONS[i] + "=" + reasons.get(i);
         }
         s += " classes:" + deferClasses().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(12)
            .map(e -> " " + e.getKey() + "=" + e.getValue().sum()).collect(java.util.stream.Collectors.joining());
      }
      return s;
   }

   /** The game thread's GL state caches (GLState's), for the check's snapshot and roll-back. */
   static final class GlStates {
      private static IOpenGLState<?>[] all;

      static IOpenGLState<?>[] all() {
         if (all == null) {
            all = new IOpenGLState<?>[]{zombie.core.opengl.GLState.AlphaFunc, zombie.core.opengl.GLState.AlphaTest, zombie.core.opengl.GLState.Blend,
               zombie.core.opengl.GLState.BlendFunc, zombie.core.opengl.GLState.BlendFuncSeparate, zombie.core.opengl.GLState.ColorMask,
               zombie.core.opengl.GLState.DepthFunc, zombie.core.opengl.GLState.DepthMask, zombie.core.opengl.GLState.DepthTest,
               zombie.core.opengl.GLState.ScissorTest, zombie.core.opengl.GLState.StencilFunc, zombie.core.opengl.GLState.StencilMask,
               zombie.core.opengl.GLState.StencilOp, zombie.core.opengl.GLState.StencilTest};
         }
         return all;
      }
   }

   // ---- devDrawListCheck: the stock pass and the recorded one back to back in one frame, compared entry by entry ----

   static final class Check {
      int n0;
      int postRender0;
      int traceFrom, traceTo; // devDrawListTrace: this stock re-run's events
      int sFrom, sTo; // LightTrace: this stock re-run's object draws
      final ArrayList<Entry> stock = new ArrayList<>();
      IOpenGLState<?>[] glStates;
      IOpenGLState.Value[] glValues;
      boolean[] glDirty;
      final ArrayList<IsoObject> objects = new ArrayList<>();
      final ArrayList<float[]> alphas = new ArrayList<>();

      static Check stock(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms, GenericSpriteRenderState real) {
         return stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real, false);
      }

      static Check stock(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms, GenericSpriteRenderState real,
            boolean floor) {
         Check c = new Check();
         c.n0 = real.numSprites;
         c.postRender0 = real.postRender.size();
         c.glStates = GlStates.all();
         c.glValues = new IOpenGLState.Value[c.glStates.length];
         c.glDirty = new boolean[c.glStates.length];
         for (int i = 0; i < c.glStates.length; i++) {
            c.glValues[i] = c.glStates[i].pzoptNewValue();
            c.glDirty[i] = c.glStates[i].pzoptSnapshot(c.glValues[i]);
         }
         for (IsoChunk chunk : chunks) {
            if (chunk == null) {
               continue;
            }
            for (int y = 0; y < 8; y++) {
               for (int x = 0; x < 8; x++) {
                  IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                  if (sq == null) {
                     continue;
                  }
                  for (int k = 0; k < sq.getObjects().size(); k++) {
                     IsoObject o = sq.getObjects().get(k);
                     c.objects.add(o);
                     c.alphas.add(new float[]{o.getAlpha(playerIndex), o.getTargetAlpha(playerIndex)});
                  }
               }
            }
         }
         float nz = TextureDraw.nextZ;
         float ncd = TextureDraw.nextChunkDepth;
         zombie.iso.fboRenderChunk.FBORenderTrees trees = cell.pzoptTreesSnapshot();
         int treeCount = cell.pzoptTreesCount();
         long t0 = System.nanoTime();
         c.traceFrom = LightTrace.mark();
         c.sFrom = LightTrace.sMark();
         ArrayList<Object> lightPost = LightTrace.undo(LightTrace.key(z, floor)); // the squares the recording refreshed at or after this pass's place, as before it
         LightTrace.stockRun = true;
         try {
            for (IsoChunk chunk : chunks) {
               if (floor) {
                  cell.pzoptRecordChunkTranslucentFloor(chunk, playerIndex, z, floorShader, wallShader, ms);
               } else {
                  cell.pzoptRecordChunkTranslucent(chunk, playerIndex, z, floorShader, wallShader, ms);
               }
            }
         } finally {
            LightTrace.stockRun = false;
            LightTrace.redo(lightPost);
         }
         c.traceTo = LightTrace.mark();
         c.sTo = LightTrace.sMark();
         stockNanos += System.nanoTime() - t0;
         for (int i = c.n0; i < real.numSprites; i++) {
            c.stock.add(new Entry(real.sprite[i], real.style[i]));
         }
         // roll the stock pass back: the frame keeps the recorded one
         for (int i = c.n0; i < real.numSprites; i++) {
            real.sprite[i].drawer = null; // (f8-render1: a rolled-back entry object keeps no drawer: the next entry made in it, a
            real.sprite[i].future = null; // StartShader, showed the stock model drawer as a false "drawer" difference)
         }
         real.numSprites = c.n0;
         while (real.postRender.size() > c.postRender0) {
            real.postRender.remove(real.postRender.size() - 1);
         }
         for (int i = 0; i < c.glStates.length; i++) {
            c.glStates[i].pzoptRestore(c.glValues[i], c.glDirty[i]);
         }
         cell.pzoptTreesRestore(trees, treeCount);
         TextureDraw.nextZ = nz;
         TextureDraw.nextChunkDepth = ncd;
         for (int i = 0; i < c.objects.size(); i++) {
            IsoObject o = c.objects.get(i);
            float[] a = c.alphas.get(i);
            o.setAlpha(playerIndex, a[0]);
            o.setTargetAlpha(playerIndex, a[1]);
         }
         return c;
      }

      /** tileRecordWeather's check: the stock run of {@code body} into the frame's list, rolled back (GL caches too). */
      static Check stockRun(Runnable body, GenericSpriteRenderState real) {
         Check c = new Check();
         c.n0 = real.numSprites;
         c.postRender0 = real.postRender.size();
         c.glStates = GlStates.all();
         c.glValues = new IOpenGLState.Value[c.glStates.length];
         c.glDirty = new boolean[c.glStates.length];
         for (int i = 0; i < c.glStates.length; i++) {
            c.glValues[i] = c.glStates[i].pzoptNewValue();
            c.glDirty[i] = c.glStates[i].pzoptSnapshot(c.glValues[i]);
         }
         float nz = TextureDraw.nextZ;
         float ncd = TextureDraw.nextChunkDepth;
         Object tb = TileBatch.snapshot();
         long t0 = System.nanoTime();
         body.run();
         stockNanos += System.nanoTime() - t0;
         for (int i = c.n0; i < real.numSprites; i++) {
            c.stock.add(new Entry(real.sprite[i], real.style[i]));
         }
         for (int i = c.n0; i < real.numSprites; i++) {
            real.sprite[i].drawer = null;
            real.sprite[i].future = null;
         }
         real.numSprites = c.n0;
         while (real.postRender.size() > c.postRender0) {
            real.postRender.remove(real.postRender.size() - 1);
         }
         for (int i = 0; i < c.glStates.length; i++) {
            c.glStates[i].pzoptRestore(c.glValues[i], c.glDirty[i]);
         }
         TileBatch.restore(tb);
         TextureDraw.nextZ = nz;
         TextureDraw.nextChunkDepth = ncd;
         return c;
      }

      void compare(GenericSpriteRenderState real) {
         checks++;
         int n = real.numSprites - this.n0;
         checkEntries += this.stock.size();
         int bad = 0, late = 0;
         String first = null;
         if (n != this.stock.size()) {
            bad++;
            first = "entry count stock=" + this.stock.size() + " recorded=" + n;
         }
         int m = Math.min(n, this.stock.size());
         for (int i = 0; i < m; i++) {
            String d = this.stock.get(i).diff(real.sprite[this.n0 + i], real.style[this.n0 + i]);
            if (d != null && n == this.stock.size() && LightTrace.lateSquare(this.sFrom, this.sTo, this.n0 + i) != null) {
               // the native lighting thread wrote this draw's square between the recording and the re-run (LightTrace)
               checkLate++;
               late++;
               LightTrace.explain(this.traceFrom, this.traceTo, this.n0 + i, d, true);
               continue;
            }
            if (d != null) {
               LightTrace.explain(this.traceFrom, this.traceTo, this.n0 + i, d, false);
            }
            if (d != null) {
               bad++;
               if (first == null) {
                  first = "entry " + i + "/" + m + ": " + d;
               }
            }
         }
         if (bad > 0) {
            checkMismatches += bad;
            if (checkLogged++ < 20) {
               Log.info("tile record check: " + bad + " differences in a pass of " + this.stock.size() + " entries, first: " + first);
            }
         }
         if (late > 0 && lateLogged++ < 20) {
            Log.info("tile record check: " + late + " late-light differences in a pass of " + this.stock.size() + " entries (draws of squares the native lighting thread wrote between the recording and the re-run)");
         }
      }
   }

   /** A copy of a TextureDraw's fields (the stock pass's entries are reused by the recorded one). */
   static final class Entry {
      final TextureDraw.Type type;
      final Object style;
      final boolean flipped, singleCol;
      final int a, b, c, d, col0, col1, col2, col3, tex1Col0, tex1Col1, tex1Col2, tex1Col3;
      final float f1, z, chunkDepth;
      final float[] xy, uv, t1, t2, vars;
      final Object tex, tex1, tex2;
      final byte useAttribArray;
      final Class<?> drawerClass;
      final ShaderUniformSetter uniforms;
      final String uniformsText;

      Entry(TextureDraw t, Object style) {
         this.type = t.type;
         this.style = style;
         this.flipped = t.flipped;
         this.singleCol = t.singleCol;
         this.a = t.a;
         this.b = t.b;
         this.c = t.c;
         this.d = t.d;
         this.col0 = t.col0;
         this.col1 = t.col1;
         this.col2 = t.col2;
         this.col3 = t.col3;
         this.tex1Col0 = t.tex1Col0;
         this.tex1Col1 = t.tex1Col1;
         this.tex1Col2 = t.tex1Col2;
         this.tex1Col3 = t.tex1Col3;
         this.f1 = t.f1;
         this.z = t.z;
         this.chunkDepth = t.chunkDepth;
         this.xy = new float[]{t.x0, t.x1, t.x2, t.x3, t.y0, t.y1, t.y2, t.y3};
         this.uv = new float[]{t.u0, t.u1, t.u2, t.u3, t.v0, t.v1, t.v2, t.v3};
         this.t1 = new float[]{t.tex1U0, t.tex1U1, t.tex1U2, t.tex1U3, t.tex1V0, t.tex1V1, t.tex1V2, t.tex1V3};
         this.t2 = new float[]{t.tex2U0, t.tex2U1, t.tex2U2, t.tex2U3, t.tex2V0, t.tex2V1, t.tex2V2, t.tex2V3};
         this.vars = t.vars == null ? null : t.vars.clone();
         this.tex = t.tex;
         this.tex1 = t.tex1;
         this.tex2 = t.tex2;
         this.useAttribArray = t.useAttribArray;
         this.drawerClass = t.drawer == null ? null : t.drawer.getClass();
         // a uniform chain is pooled: copy it so the stock entry's chain can be released and reused
         this.uniforms = t.drawer instanceof ShaderUniformSetter u ? u : null;
         this.uniformsText = this.uniforms == null ? null : this.uniforms.pzoptDescribe();
      }

      String diff(TextureDraw t, Object style) {
         if (t.type != this.type) {
            return "type " + this.type + " vs " + t.type;
         }
         if (style != this.style) {
            return this.type + " style";
         }
         // a TextureDraw is reused: each entry type sets only its own fields, the rest are stale from an earlier use
         switch (this.type) {
            case glDraw:
               return this.diffDraw(t);
            case glDepthFunc: case glEnable: case glDisable: case glStencilMask: case glBlendEquation: case glDepthMask: case glBind:
            case glGenerateMipMaps: case glIgnoreStyles: case glClear:
               return t.a != this.a ? this.type + " a=" + this.a + "/" + t.a : null;
            case glBlendFunc: case glBuffer: case glBindFramebuffer: case FBORenderChunkStart:
               return t.a != this.a || t.b != this.b ? this.type + " args" : null;
            case glStencilFunc: case glStencilOp: case glTexParameteri: case glDoStartFrameFx:
               return t.a != this.a || t.b != this.b || t.c != this.c ? this.type + " args" : null;
            case glBlendFuncSeparate: case glViewport:
               return t.a != this.a || t.b != this.b || t.c != this.c || t.d != this.d ? this.type + " args" : null;
            case glAlphaFunc: case doCoreIntParam:
               return t.a != this.a || Float.floatToIntBits(t.f1) != Float.floatToIntBits(this.f1) ? this.type + " args" : null;
            case glColorMask:
               return t.a != this.a || t.b != this.b || t.c != this.c || Float.floatToIntBits(t.x0) != Float.floatToIntBits(this.xy[0]) ? this.type + " args" : null;
            case StartShader:
               if (t.a != this.a || t.c != this.c) {
                  return "StartShader id=" + this.a + "/" + t.a + " c=" + this.c + "/" + t.c;
               }
               return this.diffDrawer(t);
            case ShaderUpdate:
               if (t.a != this.a || t.b != this.b || t.c != this.c) {
                  return "ShaderUpdate args";
               }
               if (this.c == -1) {
                  return t.d != this.d ? "ShaderUpdate int" : null;
               }
               for (int k = 0; k < Math.min(4, Math.max(0, this.c)); k++) {
                  float a = k == 0 ? t.u0 : k == 1 ? t.u1 : k == 2 ? t.u2 : t.u3;
                  if (Float.floatToIntBits(a) != Float.floatToIntBits(this.uv[k])) {
                     return "ShaderUpdate float " + k;
                  }
               }
               return null;
            default:
               return this.diffDrawer(t);
         }
      }

      private String diffDrawer(TextureDraw t) {
         Class<?> dc = t.drawer == null ? null : t.drawer.getClass();
         if (dc != this.drawerClass) {
            return this.type + " drawer " + this.drawerClass + " vs " + dc;
         }
         if (this.uniforms != null && !this.uniforms.pzoptSameChain((ShaderUniformSetter)t.drawer)) {
            return this.type + " uniforms stock [" + this.uniformsText + "] recorded [" + ((ShaderUniformSetter)t.drawer).pzoptDescribe() + "]";
         }
         return null;
      }

      private String diffDraw(TextureDraw t) {
         if (t.flipped != this.flipped || t.singleCol != this.singleCol || t.useAttribArray != this.useAttribArray) {
            return "draw flags flipped " + this.flipped + "/" + t.flipped + " singleCol " + this.singleCol + "/" + t.singleCol + " attrib " + this.useAttribArray + "/"
               + t.useAttribArray + " tex " + texName(t.tex);
         }
         if (t.col0 != this.col0 || t.col1 != this.col1 || t.col2 != this.col2 || t.col3 != this.col3) {
            return "draw colours " + Integer.toHexString(this.col0) + "/" + Integer.toHexString(t.col0) + " tex " + texName(t.tex);
         }
         if (Float.floatToIntBits(t.z) != Float.floatToIntBits(this.z) || Float.floatToIntBits(t.chunkDepth) != Float.floatToIntBits(this.chunkDepth)) {
            return "draw depth z=" + this.z + "/" + t.z + " chunkDepth=" + this.chunkDepth + "/" + t.chunkDepth + " tex " + texName(t.tex) + " tex1 " + texName(t.tex1);
         }
         if (!same(this.xy, t.x0, t.x1, t.x2, t.x3, t.y0, t.y1, t.y2, t.y3)) {
            return "draw position " + this.xy[0] + "," + this.xy[4] + " vs " + t.x0 + "," + t.y0;
         }
         if (!same(this.uv, t.u0, t.u1, t.u2, t.u3, t.v0, t.v1, t.v2, t.v3)) {
            return "draw uv";
         }
         if (t.tex != this.tex || t.tex1 != this.tex1 || t.tex2 != this.tex2) {
            return "draw texture";
         }
         if (this.tex1 != null && (!same(this.t1, t.tex1U0, t.tex1U1, t.tex1U2, t.tex1U3, t.tex1V0, t.tex1V1, t.tex1V2, t.tex1V3)
            || t.tex1Col0 != this.tex1Col0 || t.tex1Col1 != this.tex1Col1 || t.tex1Col2 != this.tex1Col2 || t.tex1Col3 != this.tex1Col3)) {
            return "draw tex1";
         }
         if (this.tex2 != null && !same(this.t2, t.tex2U0, t.tex2U1, t.tex2U2, t.tex2U3, t.tex2V0, t.tex2V1, t.tex2V2, t.tex2V3)) {
            return "draw tex2";
         }
         return this.diffDrawer(t);
      }

      private static String texName(zombie.core.textures.Texture t) {
         return t == null ? "null" : t.getName();
      }

      private static boolean same(float[] a, float... b) {
         for (int i = 0; i < a.length; i++) {
            if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[i])) {
               return false;
            }
         }
         return true;
      }
   }
}
