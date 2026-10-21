package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.LightingJNI;

/**
 * The square lights of a frame devDrawListCheck compares (tileRecordAsync), f8-twofix 2026-10-14.
 *
 * <p>A translucent draw reads its square's cached light (IsoGridSquare.getLightInfo: the JNILighting's ColorInfo by
 * reference; getVertLight: its cached corners); only the lazy refresh {@code JNILighting.update} (the first light read of
 * the square in a lighting pass, by whichever code asks first) rewrites them. The units record at the top of the tile
 * render and their own code refreshes squares as it goes; the check's stock re-run of a level comes later in the frame
 * and, without this class, saw those refreshes: a window the recording (like stock) drew with the previous pass's light
 * before a later object of its unit refreshed the square read the new light in the re-run (integ4-rigs2, r1-check3 /
 * check5: "draw colours" of fixtures_windows_01_16).
 *
 * <p>So, from a checked frame's dispatch to its last splice:
 * <ul>
 * <li>a recording thread's first refresh of a square saves the square's light state ({@link #beforeRefresh}); the stock
 * re-run of level z (floor pass, then object pass) starts from the saved state of the squares that units at or after
 * its own place in stock order refreshed, and the post-recording state comes back after it ({@link #undo} / {@link #redo});
 * </li>
 * <li>a square whose refresh the recording asked for and the lighting engine did not answer (not dirty yet, or dirty and
 * not readable: a chunk the native lighting thread was still lighting) while the stock re-run's refresh of it read new
 * values, with the same Java update counter, is "native late": the native thread wrote it between the two moments
 * (stock's own pass races that thread the same way). A difference of a draw of the re-run's object on such a square is
 * counted apart ({@code late}) and not as a mismatch; every other difference stays a mismatch.</li>
 * </ul>
 * {@code devDrawListTrace} also logs every refresh and translucent draw of the frame (phase R recording / S stock re-run / G
 * other game-thread code / W other worker, ticks, light, time, caller) and explains each difference in the console.
 */
public final class LightTrace {
   private LightTrace() {
   }

   public static final boolean CHECK = Config.DEV_DRAW_LIST_CHECK > 0;
   public static final boolean TRACE = CHECK && Config.DEV_DRAW_LIST_TRACE;
   /** This frame is checked (set by TileRecord at a checked frame's dispatch, cleared after its last splice). */
   public static volatile boolean on;
   static volatile boolean stockRun; // the check's stock re-run (game thread)

   // ---- undo of the recording's refreshes for the stock re-run ----
   private static final IdentityHashMap<LightingJNI.JNILighting, Integer> saved = new IdentityHashMap<>();
   private static final ArrayList<LightingJNI.JNILighting> undoSquares = new ArrayList<>();
   private static final ArrayList<int[]> undoPre = new ArrayList<>();
   private static final ArrayList<Integer> undoKey = new ArrayList<>();
   private static final IdentityHashMap<LightingJNI.JNILighting, Boolean> failedR = new IdentityHashMap<>(); // the recording asked, the engine did not answer
   private static final IdentityHashMap<IsoGridSquare, Boolean> nativeLate = new IdentityHashMap<>();
   private static final IdentityHashMap<LightingJNI.JNILighting, int[]> replayState = new IdentityHashMap<>(); // during a re-run: what the recording read
   private static final IdentityHashMap<LightingJNI.JNILighting, int[]> stockPre = new IdentityHashMap<>(); // squares the re-run itself refreshed: their state before it
   private static final IdentityHashMap<LightingJNI.JNILighting, int[]> pendingReal = new IdentityHashMap<>(); // ... and what it read, for the frame's own next refresh
   static long replayed;
   // the stock re-run's object draws: draw-list index at the start of each object and its square
   private static int[] sEntry = new int[4096];
   private static IsoGridSquare[] sSquare = new IsoGridSquare[4096];
   private static int sCount;
   static long frames, undone, lateSquares;

   // ---- trace (devDrawListTrace) ----
   private static final int CAP = 1 << 16;
   private static final AtomicInteger n = new AtomicInteger();
   private static final byte[] kind = new byte[CAP]; // 'L' refresh, 'O' object draw
   private static final byte[] phase = new byte[CAP];
   private static final int[] sx = new int[CAP], sy = new int[CAP], sz = new int[CAP];
   private static final int[] tickBefore = new int[CAP], tickAfter = new int[CAP], counter = new int[CAP];
   private static final int[] light = new int[CAP], vert0 = new int[CAP], entry = new int[CAP], objId = new int[CAP];
   private static final byte[] dirty = new byte[CAP]; // 'L': the native's square dirty answer after the call (-1 not asked)
   private static final long[] time = new long[CAP];
   private static final String[] caller = new String[CAP];
   private static final String[] thread = new String[CAP];
   private static final int[] unit = new int[CAP]; // R: the recorder's unit chunk (wx << 16 | wy & 0xFFFF)
   private static long t0;
   static long refreshes, gameRefreshes, gameRefreshesChanged;
   private static int explainLogged;

   /** TileRecord, at a checked frame's dispatch (game thread, before the units start). */
   static void begin() {
      if (!CHECK) {
         return;
      }
      synchronized (saved) {
         replayState.clear();
         stockPre.clear();
         pendingReal.clear();
         saved.clear();
         undoSquares.clear();
         undoPre.clear();
         undoKey.clear();
         failedR.clear();
         nativeLate.clear();
      }
      sCount = 0;
      n.set(0);
      t0 = System.nanoTime();
      frames++;
      on = true;
   }

   /** TileRecord, after the frame's last splice (game thread). */
   static void end() {
      on = false;
      // squares a re-run refreshed that nothing in the frame asked again: the native answered them already, so they take the
      // values read then (as had the re-run's refresh stayed)
      ArrayList<java.util.Map.Entry<LightingJNI.JNILighting, int[]>> left;
      synchronized (saved) {
         left = new ArrayList<>(pendingReal.entrySet());
         pendingReal.clear();
      }
      for (java.util.Map.Entry<LightingJNI.JNILighting, int[]> e : left) {
         synchronized (e.getKey()) {
            if (e.getKey().pzoptTick() != e.getValue()[0]) {
               e.getKey().pzoptLoadLight(e.getValue());
            }
         }
      }
   }

   private static byte phaseOf() {
      if (DrawRecorder.current() != null) {
         return 'R';
      }
      if (Thread.currentThread() instanceof FrameBatch.Worker) {
         return 'W';
      }
      return stockRun ? (byte)'S' : (byte)'G';
   }

   /** Stock order of a unit: level, then its floor pass before its object pass. */
   static int key(int z, boolean floor) {
      return z * 2 + (floor ? 0 : 1);
   }

   /** JNILighting.update, under the square's monitor, before the refresh: a recording thread's first refresh saves the state. */
   public static void beforeRefresh(LightingJNI.JNILighting jl, int tick) {
      if (stockRun) {
         if (tick != LightingJNI.getUpdateCounter(zombie.iso.IsoCamera.frameState.playerIndex)) {
            synchronized (saved) {
               if (!stockPre.containsKey(jl)) {
                  stockPre.put(jl, jl.pzoptSaveLight()); // the re-run's own refresh is taken back after it (the splice that follows draws from the frame's state)
               }
            }
         }
         return;
      }
      DrawRecorder r = DrawRecorder.current();
      if (r == null || r.bakeUnit || tick == LightingJNI.getUpdateCounter(zombie.iso.IsoCamera.frameState.playerIndex)) {
         return;
      }
      synchronized (saved) {
         if (saved.containsKey(jl)) {
            return;
         }
         saved.put(jl, undoSquares.size());
         undoSquares.add(jl);
         undoPre.add(jl.pzoptSaveLight());
         undoKey.add(key(r.unitZ, r.unitFloor));
      }
   }

   /** Check.stock, before the stock re-run of the unit place {@code k}: the squares units at or after it refreshed, as before. */
   static ArrayList<Object> undo(int k) {
      if (!CHECK || !on) {
         return null;
      }
      ArrayList<LightingJNI.JNILighting> squares = new ArrayList<>();
      ArrayList<int[]> pres = new ArrayList<>();
      synchronized (saved) {
         for (int i = 0; i < undoSquares.size(); i++) {
            if (undoKey.get(i) >= k) { // (a unit before this pass's place: stock refreshed it before this pass too)
               squares.add(undoSquares.get(i));
               pres.add(undoPre.get(i));
            }
         }
      }
      // (never a square's monitor inside the list lock: JNILighting.update calls in here holding its square's monitor)
      ArrayList<Object> post = new ArrayList<>();
      for (int i = 0; i < squares.size(); i++) {
         LightingJNI.JNILighting jl = squares.get(i);
         int[] st;
         synchronized (jl) {
            st = jl.pzoptSaveLight();
            jl.pzoptLoadLight(pres.get(i));
         }
         post.add(jl);
         post.add(st);
         undone++;
      }
      synchronized (saved) {
         for (int i = 0; i < post.size(); i += 2) {
            replayState.put((LightingJNI.JNILighting)post.get(i), (int[])post.get(i + 1));
         }
      }
      return post;
   }

   /**
    * Check.stock, after the stock re-run: the frame goes on with the recording's state. The squares the re-run refreshed
    * itself go back to their state before it (a deferred lamp spliced after the re-run read the re-run's refresh: runs
    * twofix-lo-on*-c), and the frame's own next refresh of each takes what the re-run read (the native answers a square
    * once a pass).
    */
   static void redo(ArrayList<Object> post) {
      if (post == null) {
         return;
      }
      ArrayList<LightingJNI.JNILighting> squares = new ArrayList<>();
      ArrayList<int[]> pres = new ArrayList<>();
      synchronized (saved) {
         replayState.clear();
         for (java.util.Map.Entry<LightingJNI.JNILighting, int[]> e : stockPre.entrySet()) {
            squares.add(e.getKey());
            pres.add(e.getValue());
         }
         stockPre.clear();
      }
      ArrayList<int[]> read = new ArrayList<>();
      for (int i = 0; i < squares.size(); i++) {
         LightingJNI.JNILighting jl = squares.get(i);
         synchronized (jl) {
            read.add(jl.pzoptSaveLight());
            jl.pzoptLoadLight(pres.get(i));
         }
      }
      synchronized (saved) {
         for (int i = 0; i < squares.size(); i++) {
            pendingReal.put(squares.get(i), read.get(i));
         }
      }
      for (int i = 0; i < post.size(); i += 2) {
         LightingJNI.JNILighting jl = (LightingJNI.JNILighting)post.get(i);
         synchronized (jl) {
            jl.pzoptLoadLight((int[])post.get(i + 1));
         }
      }
   }

   /**
    * JNILighting.update on a checked frame, under the square's monitor: the stock re-run asks for the refresh of a square
    * the recording refreshed (and {@link #undo} put back): the values the recording read are loaded instead of asking the
    * native, which answers a square's dirty bit once a pass (asked again it said "not dirty" and the re-run kept the old
    * light, run twofix-lo-off). The refresh's side effects ran with the recording's.
    */
   public static boolean replay(LightingJNI.JNILighting jl, int tick) {
      if (tick == LightingJNI.getUpdateCounter(zombie.iso.IsoCamera.frameState.playerIndex)) {
         return false;
      }
      int[] st;
      synchronized (saved) {
         st = stockRun ? replayState.get(jl) : pendingReal.remove(jl);
      }
      if (st == null) {
         return false;
      }
      jl.pzoptLoadLight(st);
      replayed++;
      return true;
   }

   /** Check.stock: the index of the next stock-object record (the bounds of one re-run). */
   static int sMark() {
      return sCount;
   }

   /**
    * Check.compare: the square of the re-run's object drawn at draw-list index {@code e} when the native thread wrote
    * its light between the recording and the re-run ("native late"), else null.
    */
   static IsoGridSquare lateSquare(int from, int to, int e) {
      int best = -1;
      for (int i = from; i < to; i++) {
         if (sEntry[i] <= e && (best < 0 || sEntry[i] >= sEntry[best])) {
            best = i;
         }
      }
      if (best < 0) {
         return null;
      }
      IsoGridSquare sq = sSquare[best];
      synchronized (saved) {
         return nativeLate.containsKey(sq) ? sq : null;
      }
   }

   private static int slot() {
      int i = n.getAndIncrement();
      return i < CAP ? i : -1;
   }

   private static int pack(zombie.core.textures.ColorInfo c) {
      return c == null ? 0 : Math.round(c.r * 255.0F) | Math.round(c.g * 255.0F) << 8 | Math.round(c.b * 255.0F) << 16;
   }

   /** Packed rgb of a ColorInfo (JNILighting.update's light before its refresh; trace only). */
   public static int packed(zombie.core.textures.ColorInfo c) {
      return TRACE ? pack(c) : 0;
   }

   /** The next trace event index. */
   static int mark() {
      return Math.min(n.get(), CAP);
   }

   /**
    * JNILighting.update (fboRenderChunk path) on a checked frame, under the square's monitor, after the lazy refresh ran:
    * {@code before} / {@code after} its tick, the square's light now.
    */
   public static void light(LightingJNI.JNILighting jl, IsoGridSquare sq, int playerIndex, int before, int after, int lightBefore,
         zombie.core.textures.ColorInfo li, int v0, int staleStamp) {
      if (sq == null) {
         return;
      }
      int c = LightingJNI.getUpdateCounter(playerIndex);
      byte ph = phaseOf();
      if (ph == 'R' && before != c && after != c) {
         synchronized (saved) {
            failedR.put(jl, Boolean.TRUE);
         }
      } else if (ph == 'S' && before != c && after == c) {
         synchronized (saved) {
            if (failedR.containsKey(jl) && nativeLate.put(sq, Boolean.TRUE) == null) {
               lateSquares++;
            }
         }
      }
      if (!TRACE) {
         return;
      }
      int i = slot();
      if (i < 0) {
         return;
      }
      kind[i] = 'L';
      phase[i] = ph;
      sx[i] = sq.x;
      sy[i] = sq.y;
      sz[i] = sq.z;
      tickBefore[i] = before;
      tickAfter[i] = after;
      counter[i] = c;
      light[i] = pack(li);
      vert0[i] = v0;
      entry[i] = -1;
      objId[i] = 0;
      dirty[i] = -1;
      if (before != c && after != c) {
         dirty[i] = (byte)(LightingJNI.getSquareDirty(playerIndex, sq.x, sq.y, sq.z + 32) ? 1 : 0); // not read: was it not dirty, or dirty and unreadable
      }
      caller[i] = null;
      thread[i] = Thread.currentThread().getName();
      unit[i] = unitOf(ph);
      if (before != c && after == c && staleStamp == (int)frames) {
         caller[i] = callerOf(); // a refresh after a traced draw read this square stale: who
      }
      if (before != c && after == c && (ph == 'G' || ph == 'W')) {
         if (caller[i] == null) {
            caller[i] = callerOf();
         }
         gameRefreshes++;
         if (light[i] != lightBefore) {
            gameRefreshesChanged++;
         }
      }
      time[i] = System.nanoTime() - t0;
      refreshes++;
   }

   private static int unitOf(byte ph) {
      if (ph != 'R') {
         return 0;
      }
      DrawRecorder r = DrawRecorder.current();
      return r == null ? 0 : r.unitWx << 16 | r.unitWy & 0xFFFF;
   }

   private static String callerOf() {
      return StackWalker.getInstance().walk(s -> s.skip(3).limit(10)
         .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
         .collect(java.util.stream.Collectors.joining(" < ")));
   }

   /** FBORenderCell.renderTranslucent(IsoObject) on a checked frame, before anything is drawn. */
   public static void object(IsoObject o) {
      IsoGridSquare sq = o.square;
      if (sq == null) {
         return;
      }
      byte ph = phaseOf();
      if (ph == 'S') {
         if (sCount == sEntry.length) {
            sEntry = java.util.Arrays.copyOf(sEntry, sCount * 2);
            sSquare = java.util.Arrays.copyOf(sSquare, sCount * 2);
         }
         sEntry[sCount] = DrawRecorder.real().numSprites;
         sSquare[sCount] = sq;
         sCount++;
      }
      if (!TRACE) {
         return;
      }
      int p = zombie.iso.IsoCamera.frameState.playerIndex;
      int e = ph == 'R' ? DrawRecorder.current().state.numSprites : DrawRecorder.real().numSprites;
      int i = slot();
      if (i < 0) {
         return;
      }
      kind[i] = 'O';
      phase[i] = ph;
      sx[i] = sq.x;
      sy[i] = sq.y;
      sz[i] = sq.z;
      int tick = Integer.MIN_VALUE;
      if (sq.lighting[p] instanceof LightingJNI.JNILighting jl) {
         tick = jl.pzoptTick();
         if (tick != LightingJNI.getUpdateCounter(p)) {
            jl.pzoptTraceStale = (int)frames; // a later refresh of this square in the frame logs its caller
         }
      }
      tickBefore[i] = tick;
      tickAfter[i] = tick;
      counter[i] = LightingJNI.getUpdateCounter(p);
      light[i] = pack(sq.getLightInfo(p));
      vert0[i] = sq.getVertLight(0, p);
      entry[i] = e;
      objId[i] = System.identityHashCode(o);
      dirty[i] = -1;
      caller[i] = null;
      thread[i] = Thread.currentThread().getName();
      unit[i] = unitOf(ph);
      time[i] = System.nanoTime() - t0;
   }

   /**
    * devDrawListTrace, Check.compare: a difference at draw-list index {@code stockEntry}: which object it was, what its
    * recording read, every refresh of its square in this frame.
    */
   static void explain(int from, int to, int stockEntry, String what, boolean late) {
      if (!TRACE) {
         return;
      }
      int count = Math.min(n.get(), CAP);
      int obj = -1;
      for (int i = from; i < Math.min(to, count); i++) {
         if (kind[i] == 'O' && phase[i] == 'S' && entry[i] <= stockEntry && (obj < 0 || entry[i] >= entry[obj])) {
            obj = i;
         }
      }
      if (obj < 0) {
         log("light trace: " + what + " (no stock object before entry " + stockEntry + ")");
         return;
      }
      int rec = -1;
      for (int i = 0; i < count; i++) {
         if (kind[i] == 'O' && phase[i] == 'R' && objId[i] == objId[obj] && sx[i] == sx[obj] && sy[i] == sy[obj] && sz[i] == sz[obj]) {
            rec = i;
         }
      }
      StringBuilder b = new StringBuilder(512);
      b.append("light trace: ").append(late ? "late light, " : "").append(what).append(" at ").append(sx[obj]).append(',').append(sy[obj]).append(',').append(sz[obj])
         .append(" | stock read ").append(describe(obj));
      b.append(" | recording read ").append(rec < 0 ? "none (deferred or not traced)" : describe(rec));
      for (int i = 0; i < count; i++) {
         if (kind[i] == 'L' && sx[i] == sx[obj] && sy[i] == sy[obj] && sz[i] == sz[obj]) {
            b.append(" | refresh ").append(describe(i));
         }
      }
      log(b.toString());
   }

   private static String describe(int i) {
      String s = (char)phase[i] + "[" + thread[i] + (unit[i] != 0 ? " unit " + (unit[i] >> 16) + "," + (short)unit[i] : "") + "] t="
         + String.format(java.util.Locale.ROOT, "%.3f", time[i] / 1e6) + "ms tick " + tickBefore[i] + (kind[i] == 'L' ? "->" + tickAfter[i] : "")
         + " counter " + counter[i] + " light " + Integer.toHexString(light[i]) + " vert0 " + Integer.toHexString(vert0[i]);
      if (kind[i] == 'O') {
         s += " entry " + entry[i];
      }
      if (dirty[i] >= 0) {
         s += dirty[i] == 1 ? " dirty-unreadable" : " not-dirty";
      }
      if (caller[i] != null) {
         s += " by " + caller[i];
      }
      return s;
   }

   private static void log(String s) {
      if (explainLogged++ < 40) {
         Log.info(s);
      }
   }

   /** Counters for the tile record line. */
   static String describeTotals() {
      return CHECK ? " light check: frames=" + frames + " refreshes undone=" + undone + " replayed=" + replayed + " native-late squares=" + lateSquares
         + (TRACE ? " traced refreshes=" + refreshes + " other-code refreshes=" + gameRefreshes + " (light changed " + gameRefreshesChanged + ")" : "") : "";
   }
}
