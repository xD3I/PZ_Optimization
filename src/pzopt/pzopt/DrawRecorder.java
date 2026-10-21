package pzopt;

import java.util.ArrayList;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.IOpenGLState;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;

/**
 * One tile draw unit's recorded draw list (tileRecordParallel, docs/plan-louisville-120-structural.md change A).
 *
 * <p>While a thread records a unit, {@code SpriteRendererStates.getPopulatingActiveState} hands it this recorder's own
 * {@link RecState} instead of the frame's populating state, and {@code IOpenGLState.set} checks this recorder's own GL
 * state cache instead of the game thread's. The game thread then splices the unit into the populating state in stock
 * order ({@link #splice}): the entries move over by reference, and the stream that comes out is the one the stock
 * pass would have written, entry for entry:
 *
 * <ul>
 * <li>The first set of a GL state in a segment is <em>conditional</em>: the recorder cannot know the game thread's
 * cache at that point of the stream, so it records only the value, and the splice runs the stock set there against the
 * game thread's cache (issuing the entry when stock would have).</li>
 * <li>An object a recording thread may not draw is <em>deferred</em>: the recorder notes it, and the splice draws it
 * on the game thread, through the stock path, at its place in the stream. Before it, the game thread's GL cache takes
 * every value the segment set; after it, the recorder's cache counts as unknown again (the deferred object may have
 * changed any state), so the next sets are conditional.</li>
 * <li>A tree flush point: where stock would draw the pending tree batch before a non-tree object
 * ({@code FBORenderCell.renderTranslucent(square, layer)}), the recorder notes it and the splice flushes the game
 * thread's {@code FBORenderTrees.current} there (trees are deferred, so pending trees only exist on the game thread).</li>
 * </ul>
 */
public final class DrawRecorder {
   /** tileRecordMoveRef: this splice moves the translucent units' entry objects instead of copying them (TileRecord sets it per pass). */
   public static boolean moveRef;
   /** IndieGL: SpriteRenderer.glBlendfuncEnabled as the calling thread sees it (a weather-mask recorder keeps its own). */
   public static boolean blendEnabled() {
      if (recording) {
         DrawRecorder r = current();
         if (r != null && r.weatherMask) {
            return r.blendEnabled;
         }
      }
      return SpriteRenderer.glBlendfuncEnabled;
   }

   /** WeatherFxMask's loop: true when the calling thread records the weather mask (the flag is the recorder's). */
   public static boolean setBlendEnabled(boolean v) {
      if (recording) {
         DrawRecorder r = current();
         if (r != null && r.weatherMask) {
            r.blendEnabled = v;
            return true;
         }
      }
      return false;
   }

   /** TileBatch.start: the calling thread records a translucent unit with tileRecordBatchSim (its tile starts stay raw). */
   public static boolean onBatchSimRecorder() {
      DrawRecorder r = current();
      return r != null && r.batchSim;
   }

   /** True while units are being recorded this frame (the game thread sets it around the recording). */
   public static volatile boolean recording;
   private static DrawRecorder gameThreadRecorder; // the unit the game thread is recording (its share of a batch, or every unit with devTileRecordSerial)
   private static Thread gameThread;

   private static int glSlots; // IOpenGLState instances: each gets a slot in every recorder's cache

   /** IOpenGLState's constructor: the next cache slot. */
   public static synchronized int nextGlSlot() {
      return glSlots++;
   }

   /** The populating state the calling thread records into, or null when it records nothing (stock path). */
   public static GenericSpriteRenderState stateForThread() {
      DrawRecorder r = current();
      return r == null ? null : r.state;
   }

   /** The calling thread's recorder while it records a unit, else null (IndieGL keeps its temps there). */
   public static DrawRecorder currentRecorder() {
      return current();
   }

   /** Whether the calling thread records a unit now (it uses its own scratch instead of the game's statics). */
   public static boolean onRecordingThread() {
      return current() != null;
   }

   static DrawRecorder current() {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         return (DrawRecorder)w.drawRecorder;
      }
      return t == gameThread ? gameThreadRecorder : null;
   }

   /** Binds {@code r} to the calling thread (null unbinds). */
   static void bind(DrawRecorder r) {
      Thread t = Thread.currentThread();
      if (t instanceof FrameBatch.Worker w) {
         w.drawRecorder = r;
      } else {
         gameThread = t;
         gameThreadRecorder = r;
      }
   }

   /** IOpenGLState.set on a recording thread: true when this recorder took the set (the game thread's cache is untouched). */
   public static boolean glSet(IOpenGLState<?> s, IOpenGLState.Value value) {
      DrawRecorder r = current();
      if (r == null) {
         return false;
      }
      r.set(s, value);
      return true;
   }

   // ---- the recorder ----

   /** A recorder's draw list: the stock render state code, appending to its own arrays. */
   static final class RecState extends GenericSpriteRenderState {
      RecState() {
         super(-1);
      }
   }

   final RecState state = new RecState();
   public Object indieGlTemps; // IndieGL's temps for this recorder's thread (IndieGL owns the type)
   public Object isoSpriteScratch, isoObjectScratch, cellScratch, isoGridSquareScratch;
   RenderScratch renderScratch; // the thread's draw modifiers / wall shapers (pzopt.RenderScratch) // IsoSprite's / IsoObject's / FBORenderCell's draw scratch (each class owns its type)
   public float nextZ, nextChunkDepth; // TextureDraw's depth of the next draw on this thread
   private boolean zTouched, cdTouched; // set or taken since the segment started (else the next draw takes what stock left before the segment)
   public Object uniformPool; // ShaderUniformSetter's free list of this recorder (refilled on the game thread before each pass)
   public int uniformPoolSize;
   boolean windowFrameOutline; // FBORenderCell.renderWindowFrameOutline on this recording thread
   public zombie.iso.IsoObject lowestCutawayN, lowestCutawayW; // FBORenderCell.lowestCutawayObjectN / W on this recording thread
   // bakeRecordParallel: a recorder of chunk-texture bake units (pzopt.BakeRecord) sees the bake's render mode (caching, not
   // the translucent pass) and its own unit's texture, offsets and seam-fix tile instead of FBORenderChunkManager's / IsoSprite's
   public boolean bakeUnit;
   // tileRecordBatchSim (f8-render1): a translucent unit records every tile start raw too and is spliced through TileBatch's
   // merge / fold replay like a bake unit (moveBake), so the stream is the stock one with tileVertexDepth's merged starts
   public boolean batchSim;
   // tileRecordWeather (f8-render1): a recorder of the weather mask loop sees WeatherFxMask.isRenderingMask() true and keeps
   // its own SpriteRenderer.glBlendfuncEnabled (the loop flips it around each blend set; the game thread's draws read the static)
   public boolean weatherMask;
   public boolean blendEnabled = true;
   public Object bakeRc; // the unit's FBORenderChunk
   public float bakeXoff, bakeYoff; // FBORenderChunkManager.xoff / yoff as beginRenderChunkLevel sets them for the unit
   public Object seamFix2; // IsoSprite.seamFix2 on this recording thread (the floor seam fix's tile)
   public boolean bakeUnsure; // the unit's render-info pass met an answer that may change by the bake's time (BakeRecord.unsure)
   private IOpenGLState<?>[] glState = new IOpenGLState<?>[32]; // by slot, the state objects this recorder has seen
   private IOpenGLState.Value[] glCur = new IOpenGLState.Value[32]; // by slot, this recorder's current value
   private boolean[] glKnown = new boolean[32]; // by slot, set since the unit started / since the last deferred object
   private int[] knownList = new int[32]; // the slots with glKnown set, in the order they were set
   private int knownN;

   // events, in stream order: the entry index they apply at, their kind and arguments
   static final byte EV_COND = 1, EV_DEFER = 2, EV_FLUSH = 3, EV_KNOWN = 4, EV_TAKE_Z = 5, EV_TAKE_CD = 6, EV_DEPTH_LEFT = 7;
   private byte[] evKind = new byte[64];
   private int[] evPos = new int[64];
   private int[] evEnd = new int[64]; // EV_KNOWN: the slot; EV_DEFER: the deferred kind
   private Object[] evA = new Object[64]; // EV_COND: the state object; EV_KNOWN: the value; EV_DEFER: the object / square
   private Object[] evB = new Object[64]; // EV_COND: the value (a copy); EV_DEFER: the second argument
   private float[] evZ = new float[64]; // EV_DEPTH_LEFT: nextZ (unboxed, GC pass 2026-10-14)
   private float[] evCd = new float[64]; // EV_DEPTH_LEFT: nextChunkDepth
   private int events;
   // copies handed to events, reused per unit, one pool per value class (GC pass 2026-10-14: a single pool by position
   // re-made an entry whenever the class at that position changed between units, ~6 MB/s of garbage on Louisville)
   private Class<?>[] valueCls = new Class<?>[8];
   @SuppressWarnings("unchecked")
   private ArrayList<IOpenGLState.Value>[] valuePools = new ArrayList[8];
   private int[] valuesUsed = new int[8];
   private int valueKinds;
   private boolean nonTreeSinceDefer; // a flush point is only needed at the first non-tree object of a segment
   private int postCursor; // splice: the next entry of the recorder's postRender list

   public int units, drawn, defers, conds, condsKept; // dev counters (this recorder's, summed by TileRecord)
   public int lightOrder; // tileRecordLightOrder: objects this recorder deferred after a deferred object of their square (summed by TileRecord)
   int unitWx, unitWy; // devDrawListTrace: the current unit's chunk
   int unitZ; // devDrawListCheck: the current unit's level and pass (LightTrace's stock order)
   boolean unitFloor;
   zombie.iso.IsoGridSquare[] taint = new zombie.iso.IsoGridSquare[8]; // tileRecordLightOrder: squares of the unit a deferred object may refresh at the splice
   int taintN;

   // one recorder per recording thread: its units follow each other in its lists (a recorder per unit spread the
   // pass over ~70 sets of arrays and entries and slowed the rest of the tile render through the caches, 2026-10-06)
   int unitEntry, unitEvent; // where the current unit started

   /** Starts a unit on the calling thread (its first entry / event are {@link #unitEntry} / {@link #unitEvent}). */
   void beginUnit(GenericSpriteRenderState real) {
      if (this.state.numSprites == 0 && this.events == 0) {
         this.state.defaultStyle = real.defaultStyle;
      }
      for (int q = 0; q < this.knownN; q++) {
         this.glKnown[this.knownList[q]] = false;
      }
      this.knownN = 0;
      this.nonTreeSinceDefer = false;
      this.nextZ = 0.0F;
      this.nextChunkDepth = 0.0F;
      this.zTouched = false;
      this.cdTouched = false;
      this.unitEntry = this.state.numSprites;
      this.unitEvent = this.events;
      this.units++;
      bind(this);
   }

   /** Ends the unit on the calling thread: what it set goes to the game thread's cache at the end of its splice. */
   void endUnit() {
      if ((this.bakeUnit || this.batchSim) && TileBatch.ON) {
         this.classify(this.unitEntry, this.state.numSprites);
      }
      this.knownToEvents();
      this.depthLeft();
      bind(null);
   }

   // ---- TextureDraw's "depth of the next draw" (nextZ / nextChunkDepth): stock leaves an unconsumed value (its draw was
   // skipped: no texture, zero alpha) to whichever draw comes next in the stream, across objects and units. A segment
   // records where it first takes the value it inherited and what it leaves; the splice carries the real one through. ----

   /** TextureDraw.pzoptSetNextZ on this recorder's thread. */
   public void depthSetZ(float z) {
      this.nextZ = z;
      this.zTouched = true;
   }

   public void depthSetChunkDepth(float d) {
      this.nextChunkDepth = d;
      this.cdTouched = true;
   }

   /** TextureDraw's Create on this recorder's thread: the draw at the next entry takes the depth (and the values reset). */
   public void depthTake(TextureDraw texd) {
      if (!this.zTouched) {
         this.zTouched = true;
         this.event(EV_TAKE_Z); // its z is the inherited value, patched at the splice
      }
      if (!this.cdTouched) {
         this.cdTouched = true;
         this.event(EV_TAKE_CD);
      }
      texd.z = this.nextZ;
      texd.chunkDepth = this.nextChunkDepth;
      this.nextZ = 0.0F;
      this.nextChunkDepth = 0.0F;
   }

   /** End of a segment: what it leaves for the next draw of the stream (only the values it touched; the rest pass through). */
   private void depthLeft() {
      if (this.zTouched || this.cdTouched) {
         int ev = this.event(EV_DEPTH_LEFT);
         this.evEnd[ev] = (this.zTouched ? 1 : 0) | (this.cdTouched ? 2 : 0);
         this.evZ[ev] = this.nextZ;
         this.evCd[ev] = this.nextChunkDepth;
      }
      this.nextZ = 0.0F;
      this.nextChunkDepth = 0.0F;
      this.zTouched = false;
      this.cdTouched = false;
   }

   /** The splice's carried depth (the game thread's TextureDraw statics are put back from it after each level). */
   static float carryZ, carryCd;

   /** Every state value this segment set, as events that put it in the game thread's cache at this point of the splice. */
   private void knownToEvents() {
      for (int q = 0; q < this.knownN; q++) {
         int slot = this.knownList[q];
         int ev = this.event(EV_KNOWN);
         this.evEnd[ev] = slot;
         this.evA[ev] = this.copyOf(this.glState[slot], this.glCur[slot]);
         this.glKnown[slot] = false;
      }
      this.knownN = 0;
   }

   int eventCount() {
      return this.events;
   }

   private int[] movedIdx = new int[256]; // bakeRecordParallel: recorder slots whose entry object moved into the frame's list
   private TextureDraw[] movedObj = new TextureDraw[256]; // and the frame's spare object each slot takes back

   /** Game thread, no unit recording: the moved slots take their spare objects (see move). */
   void fixMoved() {
      for (int i = 0; i < this.movedN; i++) {
         TextureDraw spare = this.movedObj[i];
         spare.drawer = null; // (f8-render1: cleared here instead of in moveBake: the frame's spare objects are cold, a miss each)
         spare.future = null;
         this.state.sprite[this.movedIdx[i]] = spare;
         this.movedObj[i] = null;
      }
      this.movedN = 0;
   }

   /** tileRecordBatchSim: the recorder's thread puts its moved slots right before its next unit (TileRecord.record). */
   void fixMovedIfAny() {
      if (this.movedN != 0) {
         this.fixMoved();
      }
   }

   private int movedN;

   // bakeRecordParallel: what the splice needs of each entry, written by the recording thread at the unit's end so the game
   // thread reads one byte per entry instead of the entry object (each one another core's cache line)
   static final byte K_PLAIN = 0, K_VDSTART = 1, K_VDDRAW = 2, K_MASK = 3, K_FUNC = 4;
   private byte[] kind = new byte[16384];
   private int[] kindArg = new int[16384];

   private void classify(int from, int to) {
      if (to > this.kind.length) {
         int len = Math.max(to, this.kind.length * 2);
         this.kindArg = java.util.Arrays.copyOf(this.kindArg, len);
         this.kind = java.util.Arrays.copyOf(this.kind, len);
      }
      TextureDraw[] sprites = this.state.sprite;
      for (int k = from; k < to; k++) {
         TextureDraw e = sprites[k];
         TextureDraw.Type t = e.type;
         byte kd = K_PLAIN;
         if (t == TextureDraw.Type.StartShader) {
            if (e.pzoptVDStart) {
               kd = K_VDSTART;
               this.kindArg[k] = e.a;
            }
         } else if (t == TextureDraw.Type.glDraw) {
            if (e.pzoptVD) {
               kd = K_VDDRAW;
            }
         } else if (t == TextureDraw.Type.glDepthMask) {
            kd = K_MASK;
            this.kindArg[k] = e.a;
         } else if (t == TextureDraw.Type.glDepthFunc) {
            kd = K_FUNC;
            this.kindArg[k] = e.a;
         }
         this.kind[k] = kd;
      }
   }

   /** bakeRecordParallel's splice of entries [from, to): the entry objects move into the frame's list (see move). */
   private void moveBake(GenericSpriteRenderState real, int from, int to) {
      RecState rec = this.state;
      boolean tiles = TileBatch.ON;
      TextureDraw[] sprites = rec.sprite;
      for (int k = from; k < to; k++) {
         real.CheckSpriteSlots();
         int n = real.numSprites;
         TextureDraw src = sprites[k];
         byte kd = tiles ? this.kind[k] : K_PLAIN;
         if (kd == K_VDSTART && TileBatch.spliceStart(real, this.kindArg[k])) {
            if (this.postCursor < rec.postRender.size() && rec.postRender.get(this.postCursor) == src) {
               this.postCursor++; // stock never recorded this start: nothing to run after the frame
            }
            src.drawer = null;
            src.future = null;
            continue;
         }
         // the entry object itself moves into the frame's list (a copy of every field cost more); the frame's spare slot
         // object (past numSprites: in no postRender list) goes to the recorder's slot at the frame's end (fixMoved: the
         // recorder's thread may be growing its arrays for another unit now)
         TextureDraw spare = real.sprite[n];
         real.sprite[n] = src;
         if (this.movedN == this.movedIdx.length) {
            this.movedIdx = java.util.Arrays.copyOf(this.movedIdx, this.movedN * 2);
            this.movedObj = java.util.Arrays.copyOf(this.movedObj, this.movedN * 2);
         }
         this.movedIdx[this.movedN] = k;
         this.movedObj[this.movedN++] = spare; // its drawer / future are cleared in fixMoved
         real.style[n] = rec.style[k];
         real.numSprites = n + 1;
         if (this.postCursor < rec.postRender.size() && rec.postRender.get(this.postCursor) == src) {
            real.postRender.add(src);
            this.postCursor++;
         }
         if (tiles) {
            if (kd == K_VDSTART) {
               TileBatch.spliceStarted(real, this.kindArg[k], n);
            } else {
               TileBatch.spliceMoved(real, n, kd, this.kindArg[k]);
            }
         }
      }
   }

   /** bakeRecordParallel: the splice of a unit starts at its own postRender entries (units the frame did not splice left theirs). */
   void seekPost(int p) {
      this.postCursor = p;
   }

   int postSize() {
      return this.state.postRender.size();
   }

   /** After every unit of the pass was spliced: the lists start empty for the next pass. */
   void reset() {
      for (int ev = 0; ev < this.events; ev++) {
         this.evA[ev] = null;
         this.evB[ev] = null;
      }
      this.events = 0;
      java.util.Arrays.fill(this.valuesUsed, 0, this.valueKinds, 0);
      this.state.numSprites = 0;
      this.state.postRender.clear(); // queued on the frame's copies by move()
      this.postCursor = 0;
   }

   private IOpenGLState.Value copyOf(IOpenGLState<?> s, IOpenGLState.Value v) {
      Class<?> cls = v.getClass();
      int k = 0;
      while (k < this.valueKinds && this.valueCls[k] != cls) {
         k++;
      }
      if (k == this.valueKinds) {
         if (k == this.valueCls.length) {
            this.valueCls = java.util.Arrays.copyOf(this.valueCls, k * 2);
            this.valuePools = java.util.Arrays.copyOf(this.valuePools, k * 2);
            this.valuesUsed = java.util.Arrays.copyOf(this.valuesUsed, k * 2);
         }
         this.valueCls[k] = cls;
         this.valuePools[k] = new ArrayList<>();
         this.valueKinds++;
      }
      ArrayList<IOpenGLState.Value> pool = this.valuePools[k];
      int used = this.valuesUsed[k]++;
      IOpenGLState.Value c;
      if (used < pool.size()) {
         c = pool.get(used);
      } else {
         c = s.pzoptNewValue();
         if (c.getClass() != cls) { // never seen: a state whose default value is of another class than its values
            c.set(v);
            return c;
         }
         pool.add(c);
      }
      c.set(v);
      return c;
   }

   private void set(IOpenGLState<?> s, IOpenGLState.Value value) {
      int slot = s.pzoptSlot;
      if (slot >= this.glCur.length) {
         int n = Math.max(slot + 1, this.glCur.length * 2);
         this.glState = java.util.Arrays.copyOf(this.glState, n);
         this.glCur = java.util.Arrays.copyOf(this.glCur, n);
         this.glKnown = java.util.Arrays.copyOf(this.glKnown, n);
      }
      if (this.glCur[slot] == null) {
         this.glCur[slot] = s.pzoptNewValue();
         this.glState[slot] = s;
      }
      if (!this.glKnown[slot]) {
         // the first set of the segment: whether stock issues it depends on the game thread's cache at this point of
         // the stream, which only the splice knows
         // the splice issues the entry itself, into the frame's list, when the game thread's cache would (most do not:
         // writing them here and dropping them at the splice cost more than the whole stock pass's state handling)
         int ev = this.event(EV_COND);
         this.evA[ev] = s;
         this.evB[ev] = this.copyOf(s, value);
         this.glCur[slot].set(value);
         this.glKnown[slot] = true;
         if (this.knownN == this.knownList.length) {
            this.knownList = java.util.Arrays.copyOf(this.knownList, this.knownN * 2);
         }
         this.knownList[this.knownN++] = slot;
         this.conds++;
      } else if (!value.equals(this.glCur[slot])) {
         this.glCur[slot].set(value);
         s.pzoptEmit(value);
      }
   }

   private int event(byte kind) {
      int ev = this.events;
      if (ev == this.evKind.length) {
         int n = ev * 2;
         this.evKind = java.util.Arrays.copyOf(this.evKind, n);
         this.evPos = java.util.Arrays.copyOf(this.evPos, n);
         this.evEnd = java.util.Arrays.copyOf(this.evEnd, n);
         this.evA = java.util.Arrays.copyOf(this.evA, n);
         this.evB = java.util.Arrays.copyOf(this.evB, n);
         this.evZ = java.util.Arrays.copyOf(this.evZ, n);
         this.evCd = java.util.Arrays.copyOf(this.evCd, n);
      }
      this.evKind[ev] = kind;
      this.evPos[ev] = this.state.numSprites;
      this.events = ev + 1;
      return ev;
   }

   /** Defers a draw to the game thread at this point of the stream ({@code kind} is the caller's). */
   void defer(int kind, Object a, Object b) {
      // the game thread's cache must hold what this segment set before the deferred draw runs against it; after it,
      // the next sets are conditional again
      this.knownToEvents();
      this.depthLeft();
      int ev = this.event(EV_DEFER);
      this.evEnd[ev] = kind;
      this.evA[ev] = a;
      this.evB[ev] = b;
      this.nonTreeSinceDefer = false;
      this.defers++;
   }

   /** A non-tree object is about to draw: stock flushes the pending trees here, the first time in a segment. */
   void flushPoint() {
      if (!this.nonTreeSinceDefer) {
         this.nonTreeSinceDefer = true;
         this.event(EV_FLUSH);
      }
   }

   /** Runs a deferred draw on the game thread during the splice. */
   public interface Deferred {
      void run(int kind, Object a, Object b);

      void flushTrees();
   }

   /**
    * Game thread: appends one unit (entries [entryFrom, entryTo), events [evFrom, evTo) of this recorder) to
    * {@code real} in stream order, runs its deferred draws, and leaves the game thread's GL cache as the stock pass
    * would have.
    */
   void splice(GenericSpriteRenderState real, Deferred deferred, int entryFrom, int entryTo, int evFrom, int evTo) {
      if ((this.bakeUnit || this.batchSim) && TileBatch.ON) {
         TileBatch.spliceBegin(real); // bakeRecordParallel; tileRecordBatchSim
      }
      int i = entryFrom;
      for (int ev = evFrom; ev < evTo; ev++) {
         int pos = this.evPos[ev];
         this.move(real, i, pos);
         i = pos;
         switch (this.evKind[ev]) {
            case EV_COND: {
               @SuppressWarnings("unchecked")
               IOpenGLState<IOpenGLState.Value> s = (IOpenGLState<IOpenGLState.Value>)this.evA[ev];
               IOpenGLState.Value v = (IOpenGLState.Value)this.evB[ev];
               if (s.pzoptWouldEmit(v)) {
                  s.pzoptAdopt(v);
                  s.pzoptEmit(v); // the stock set, against the game thread's cache, into the frame's list
                  this.condsKept++;
               }
               break;
            }
            case EV_KNOWN:
               this.glState[this.evEnd[ev]].pzoptAdopt((IOpenGLState.Value)this.evA[ev]);
               break;
            case EV_DEFER:
               TextureDraw.nextZ = carryZ; // the deferred draw takes the carried depth through the statics, as stock
               TextureDraw.nextChunkDepth = carryCd;
               deferred.run(this.evEnd[ev], this.evA[ev], this.evB[ev]);
               carryZ = TextureDraw.nextZ;
               carryCd = TextureDraw.nextChunkDepth;
               break;
            case EV_TAKE_Z:
               this.state.sprite[pos].z = carryZ; // still the recorder's entry: move() copies it below
               carryZ = 0.0F;
               break;
            case EV_TAKE_CD:
               this.state.sprite[pos].chunkDepth = carryCd;
               carryCd = 0.0F;
               break;
            case EV_DEPTH_LEFT: {
               int m = this.evEnd[ev];
               if ((m & 1) != 0) {
                  carryZ = this.evZ[ev];
               }
               if ((m & 2) != 0) {
                  carryCd = this.evCd[ev];
               }
               break;
            }
            case EV_FLUSH:
               deferred.flushTrees();
               break;
            default:
               break;
         }
      }
      this.move(real, i, entryTo);
   }

   /** Copies entries [from, to) of the recorder to the end of {@code real} (each recorder keeps its own TextureDraw objects). */
   private void move(GenericSpriteRenderState real, int from, int to) {
      if (this.bakeUnit || this.batchSim && TileBatch.ON) {
         this.moveBake(real, from, to);
         return;
      }
      RecState rec = this.state;
      boolean tiles = this.bakeUnit && TileBatch.ON; // bakeRecordParallel: the unit recorded every tile start; merged / folded here as stock would
      for (int k = from; k < to; k++) {
         real.CheckSpriteSlots();
         int n = real.numSprites;
         TextureDraw src = rec.sprite[k];
         boolean vdStart = tiles && src.type == TextureDraw.Type.StartShader && src.pzoptVDStart;
         if (vdStart && TileBatch.spliceStart(real, src.a)) {
            if (this.postCursor < rec.postRender.size() && rec.postRender.get(this.postCursor) == src) {
               this.postCursor++; // stock never recorded this start: nothing to run after the frame
            }
            src.drawer = null;
            src.future = null;
            continue;
         }
         if (moveRef && !tiles) {
            // tileRecordMoveRef (f8-render1): the entry object itself goes into the frame's list and the frame's spare
            // object (past numSprites: in no postRender list) takes its slot in the recorder; the units are all recorded
            // (the batch is joined) before any splice, so no recording thread touches these arrays now. The entry keeps
            // its drawer: it is the frame's entry now (a copy of every field was most of the splice's cost)
            TextureDraw spare = real.sprite[n];
            spare.drawer = null;
            spare.future = null;
            real.sprite[n] = src;
            rec.sprite[k] = spare;
            real.style[n] = rec.style[k];
            real.numSprites = n + 1;
            if (this.postCursor < rec.postRender.size() && rec.postRender.get(this.postCursor) == src) {
               real.postRender.add(src);
               this.postCursor++;
            }
            continue;
         }
         real.sprite[n].pzoptCopyFrom(src);
         real.style[n] = rec.style[k];
         real.numSprites = n + 1;
         // a StartShader entry the recorder's state queued for postRender: the frame's copy is the one to queue
         if (this.postCursor < rec.postRender.size() && rec.postRender.get(this.postCursor) == src) {
            real.postRender.add(real.sprite[n]);
            this.postCursor++;
         }
         // the frame's copy owns the drawer (a StartShader's uniform chain is released by its postRender): the
         // recorder's entry lets go, as a stock entry's postRender would (a stale chain on a later StartShader
         // without uniforms was released twice and looped the pool, 2026-10-06)
         src.drawer = null;
         src.future = null;
         if (tiles) {
            if (vdStart) {
               TileBatch.spliceStarted(real, src.a);
            } else if (real.sprite[n].type == TextureDraw.Type.glDraw && real.sprite[n].pzoptVD) {
               TileBatch.spliceDraw(real, n);
            }
         }
      }
   }

   /** The populating state of the frame (what the splice appends to). */
   static GenericSpriteRenderState real() {
      return SpriteRenderer.instance.states.getPopulatingActiveState();
   }
}
