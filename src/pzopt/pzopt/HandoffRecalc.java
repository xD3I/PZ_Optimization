package pzopt;

import java.util.ArrayList;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * handoffRecalcFast / handoffRecalcParallel (2026-10-14, Louisville LOCKED 120, the chunk hand-off pass f8-handoff).
 *
 * <p>A streamed chunk's hand-off recalculates its border squares against the already-loaded neighbours on the game thread
 * (IsoChunk.loadInMainThread: the new squares' RecalcAllWithNeighbours, the border loop of IsoChunk.RecalcAllWithNeighbour per
 * level and direction, the four corners' RecalcAllWithNeighbours per level). Half of a hand-off's game-thread time, and most of
 * it repeats itself: every ReCalculateAll recomputes both squares' properties (a loaded chunk's square stays "dirty" after a
 * recompute, so RecalcPropertiesIfNeeded always recomputes) and both squares' ten blocked-grid pointers, and the six
 * collide / path / vision bits of a pair are pure functions of the squares' static state (properties, objects, which squares
 * exist).
 *
 * <p>During that phase (begin .. end, game thread, the stock code still runs in stock order):
 * <ul>
 * <li>a square's properties are recomputed at its first RecalcPropertiesIfNeeded of the phase only: nothing in the phase
 * changes a recompute's inputs (objects, room, roof, rain, fire, water), the recompute is idempotent, and its side effects
 * are flags no one reads before the phase ends (lighting / physics "check later", the puddle recalc, the obstacle cache);</li>
 * <li>setBlockedGridPointers is deferred: the pointers are written only (nothing in the phase reads them), so only a square's
 * last call matters; its result depends on the square's own pathMatrix at the call (kept), properties and which squares
 * exist. A deferred call is applied before a direct pointer write (IsoChunk.RecalcAllWithNeighbour), before the first
 * recompute of the square or the square above it (a stale-properties recompute could change what it reads), and at the
 * end;</li>
 * <li>(handoffRecalcParallel) the collide / path / vision bits of the pairs the phase will ask for are computed beforehand on
 * the frame workers while the game thread waits (fork-join: nothing else writes squares meanwhile), with every lazy write
 * suppressed on the workers (a property recompute is recorded instead: the replay applies it; the solid-floor cache is not
 * filled). ReCalculateCollide / PathFind / VisionBlocked then read the memo. A pair whose computation reads the
 * neighbour pointers of a square (stairs, sloped surfaces: hasAdjacentStairs, isSlopedSurfaceEdgeBlocked) is not memoised,
 * since doGridNav changes those during the phase. If a square's first recompute in the phase changes its properties (stale
 * before the hand-off) the memo is off for the rest of the phase: everything asked before that saw the old properties in
 * stock as on the workers.</li>
 * </ul>
 * devHandoffRecalcCheck runs the phase twice per chunk (fast, then restored and stock) and compares every field it writes.
 */
public final class HandoffRecalc {
   private HandoffRecalc() {
   }

   /** True while a phase runs (read by the IsoGridSquare hooks on any thread; only the phase thread and the batch act on it). */
   public static boolean capture;
   private static Thread phaseThread;
   private static int phase;
   private static boolean memoOn;
   private static boolean deferOn;
   private static boolean dedupeOn;
   private static volatile boolean forking; // the batch runs: every lazy write is suppressed, property recomputes are recorded
   private static boolean recordOnly; // devHandoffRecalcCheck: the stock run, recording which squares were recomputed
   private static final ArrayList<IsoGridSquare> recorded = new ArrayList<>();

   // memo box: the chunk plus two squares on each side, levels minLevel2-1 .. maxLevel2+1
   private static final int BW = 12;
   private static final int MAXZ = 66;
   private static final int SLOTS = BW * BW * MAXZ;
   // allocated on the first memo / precompute use (~2.5 MB; handoffRecalcFast alone never touches them)
   private static IsoGridSquare[] slotSq;
   private static int[] slotStamp;
   private static int[] slotWant;
   private static int[] wantStamp;
   private static byte[] val; // bits 0-2 collide / path / vision, bits 3-5 the kind is kept, 0x40 not memoisable
   private static byte[] eff; // per kind two bits: betweenA / betweenB recomputed inside
   private static IsoGridSquare[] tgt;
   private static int ox, oy, oz, nz;
   private static final ArrayList<IsoGridSquare> pending = new ArrayList<>();

   // census (game thread)
   public static long phases, fastPhases, parallelPhases, dedupeHits, firstRecalcs, stale, memoHits, memoMisses, memoUnmemo,
      deferred, flushes, owners, pairs, computed, forkNanos, phaseNanos, skippedBusy, skippedSmall, checkChunks, checkMismatch,
      checkFieldMismatch, checkPropSetMismatch, checkStale;
   private static long phaseT0;

   private static final IsoGridSquare.GetSquare CELL = IsoGridSquare.cellGetSquare;

   /** Game thread: the hand-off's recalc phase starts (after the EnsureSurroundNotNull passes). */
   public static void begin(int wx, int wy, int minLevel, int maxLevel, int minLevel2, int maxLevel2, ArrayList<IsoGridSquare> newSquares,
         boolean fast, boolean parallel) {
      phases++;
      phaseT0 = System.nanoTime();
      phaseMode = 0;
      if (!fast) {
         return;
      }
      phaseMode = 1;
      fastPhases++;
      if (++phase == 0) {
         phase = 1;
      }
      phaseThread = Thread.currentThread();
      dedupeOn = true;
      deferOn = !IsoGridSquare.isbDoSlowPathfinding();
      lazyOn = deferOn && Config.HANDOFF_RECALC_MEMO && GtAb.on(GtAb.HANDOFF_MEMO);
      memoOn = lazyOn;
      if (lazyOn) {
         box(wx, wy, minLevel2, maxLevel2);
         phaseMode = 3;
      }
      pending.clear();
      capture = true;
      if (parallel && deferOn) {
         precompute(wx, wy, minLevel, maxLevel, minLevel2, maxLevel2, newSquares);
         if (memoOn) {
            phaseMode = 2;
         }
      }
   }

   /** Game thread: the phase ends (the deferred pointers are applied). */
   public static void end() {
      if (!capture) {
         stat(phaseMode, System.nanoTime() - phaseT0);
         return;
      }
      try {
         flushAll();
      } finally {
         capture = false;
         memoOn = false;
         deferOn = false;
         dedupeOn = false;
         long d = System.nanoTime() - phaseT0;
         phaseNanos += d;
         stat(phaseMode, d);
      }
   }

   // per mode (0 stock, 1 fast, 2 fast + precomputed): phases, ns, max, phases over 0.5 / 1 / 2 / 4 ms
   private static int phaseMode;
   private static final long[][] STAT = new long[4][7];

   private static void stat(int mode, long ns) {
      long[] t = STAT[mode];
      t[0]++;
      t[1] += ns;
      t[2] = Math.max(t[2], ns);
      if (ns > 500_000L) t[3]++;
      if (ns > 1_000_000L) t[4]++;
      if (ns > 2_000_000L) t[5]++;
      if (ns > 4_000_000L) t[6]++;
   }

   private static String stats() {
      StringBuilder b = new StringBuilder();
      String[] names = {"stock", "fast", "parallel", "memo"};
      for (int m = 0; m < 4; m++) {
         long[] t = STAT[m];
         if (t[0] == 0) {
            continue;
         }
         b.append(' ').append(names[m]).append(' ').append(t[0]).append(" mean ").append(t[1] / t[0] / 1000).append(" us max ").append(t[2] / 1000)
            .append(" >0.5/1/2/4 ms ").append(t[3]).append('/').append(t[4]).append('/').append(t[5]).append('/').append(t[6]).append(';');
      }
      return b.toString();
   }

   // devHandoffRecalcCheck: the same chunk's phase timed both ways (fast first, then the stock run on the restored squares)
   private static long chkFastNs, chkStockNs, chkHeavy, chkHeavyFastNs, chkHeavyStockNs, chkT0;

   public static void checkTimeStart() {
      chkT0 = System.nanoTime();
   }

   public static void checkTimeFast() {
      chkFastLast = System.nanoTime() - chkT0;
   }

   private static long chkFastLast, lastForkNs;
   private static int lastPairs;

   public static void checkTimeStock() {
      long st = System.nanoTime() - chkT0;
      Log.info("handoff recalc chunk: pairs " + lastPairs + " fork " + lastForkNs / 1000 + " fast " + chkFastLast / 1000 + " stock " + st / 1000 + " us");
      lastPairs = 0;
      lastForkNs = 0L;
      chkFastNs += chkFastLast;
      chkStockNs += st;
      if (st > 1_000_000L) {
         chkHeavy++;
         chkHeavyFastNs += chkFastLast;
         chkHeavyStockNs += st;
      }
   }

   // ---------------------------------------------------------------- properties (RecalcPropertiesIfNeeded hook)

   private static final ThreadLocal<Rec> REC = ThreadLocal.withInitial(Rec::new);

   /** A worker's record of the property recomputes a computation asked for. */
   private static final class Rec {
      boolean active;
      int n;
      final IsoGridSquare[] sq = new IsoGridSquare[4];
      boolean overflow;

      void reset() {
         this.n = 0;
         this.overflow = false;
      }

      void add(IsoGridSquare s) {
         if (this.n < this.sq.length) {
            this.sq[this.n++] = s;
         } else {
            this.overflow = true;
         }
      }

      /** -1 = not a plain [A][B] sequence. */
      int code(IsoGridSquare a, IsoGridSquare b) {
         if (this.overflow) {
            return -1;
         }
         if (this.n == 0) {
            return 0;
         }
         if (this.n == 1) {
            return this.sq[0] == a ? 1 : this.sq[0] == b ? 2 : -1;
         }
         if (this.n == 2) {
            return this.sq[0] == a && this.sq[1] == b ? 3 : -1;
         }
         return -1;
      }
   }

   /**
    * From IsoGridSquare.RecalcPropertiesIfNeeded with the square dirty and {@link #capture} set: true = the caller skips its
    * recompute (done here, done before in this phase, or recorded on a worker).
    */
   public static boolean skipProps(IsoGridSquare sq) {
      Thread t = Thread.currentThread();
      if (forking || t != phaseThread) {
         Rec r = REC.get();
         if (r.active) {
            r.add(sq);
            return true;
         }
         return false;
      }
      if (recordOnly) {
         recorded.add(sq);
         return false;
      }
      if (!dedupeOn) {
         return false;
      }
      if (sq.pzoptHrStamp == phase) {
         dedupeHits++;
         return true;
      }
      sq.pzoptHrStamp = phase;
      firstRecalcs++;
      if (deferOn) {
         flush(sq); // the deferred pointers read this square's properties (its own, and u's of the square below)
         IsoGridSquare below = CELL.getGridSquare(sq.x, sq.y, sq.z - 1);
         if (below != null) {
            flush(below);
         }
      }
      if (memoOn) {
         int n0 = sq.pzoptPropsKey(KEY_A);
         sq.RecalcProperties();
         int n1 = sq.pzoptPropsKey(KEY_B);
         if (!sameKey(n0, n1)) {
            invalidateAround(sq); // stale properties: from here the memo entries that read this square would answer with the old ones
            stale++;
            if (Config.DEV_HANDOFF_RECALC_CHECK && stale <= 30) {
               Log.info("handoff recalc: stale properties at " + sq.x + "," + sq.y + "," + sq.z + ": " + keyDiff(n0, n1));
            }
         }
      } else {
         sq.RecalcProperties();
      }
      return true;
   }

   /**
    * A pair's computation (O -> T, T adjacent to O) reads the properties of O, T, the two squares between them on O's level, the
    * squares south and east of T, the square below T, and the squares next to those it asks for stairs / sloped edges: all within
    * two squares of O across, two below and one above. The entries of every owner within three squares (and three levels) of a
    * square whose properties changed are dropped (computed live from here, as stock does).
    */
   private static void invalidateAround(IsoGridSquare x) {
      for (int z = x.z - 3; z <= x.z + 3; z++) {
         for (int y = x.y - 3; y <= x.y + 3; y++) {
            for (int xx = x.x - 3; xx <= x.x + 3; xx++) {
               int s = slot(xx, y, z);
               if (s >= 0 && slotStamp[s] == phase) {
                  slotStamp[s] = 0;
                  invalidated++;
               }
            }
         }
      }
   }

   public static long invalidated;

   private static final long[] KEY_A = new long[80];
   private static final long[] KEY_B = new long[80];

   private static boolean sameKey(int n0, int n1) {
      if (n0 != n1 || n0 < 0) {
         return false;
      }
      for (int i = 0; i < n0; i++) {
         if (KEY_A[i] != KEY_B[i]) {
            return false;
         }
      }
      return true;
   }

   private static String keyDiff(int n0, int n1) {
      StringBuilder b = new StringBuilder();
      if (n0 < 0 || n1 < 0) {
         return "too many entries";
      }
      if (KEY_A[0] != KEY_B[0]) {
         b.append("hasTypes ").append(Long.toHexString(KEY_A[0])).append("->").append(Long.toHexString(KEY_B[0])).append(' ');
      }
      if (KEY_A[1] != KEY_B[1]) {
         b.append("hasTree ").append(KEY_A[1]).append("->").append(KEY_B[1]).append(' ');
      }
      for (int w = 0; w < 2; w++) {
         long x = KEY_A[2 + w] ^ KEY_B[2 + w];
         for (int i = 0; i < 64; i++) {
            if ((x >>> i & 1L) != 0L) {
               b.append((KEY_B[2 + w] >>> i & 1L) != 0L ? '+' : '-').append(zombie.iso.SpriteDetails.IsoFlagType.fromIndex(w * 64 + i)).append(' ');
            }
         }
      }
      java.util.TreeSet<Long> a = new java.util.TreeSet<>();
      java.util.TreeSet<Long> c = new java.util.TreeSet<>();
      for (int i = 4; i < n0; i++) {
         a.add(KEY_A[i]);
      }
      for (int i = 4; i < n1; i++) {
         c.add(KEY_B[i]);
      }
      for (Long v : a) {
         if (!c.contains(v)) {
            b.append("-").append(name((short)(v >> 16))).append('=').append(v & 0xFFFF).append(' ');
         }
      }
      for (Long v : c) {
         if (!a.contains(v)) {
            b.append("+").append(name((short)(v >> 16))).append('=').append(v & 0xFFFF).append(' ');
         }
      }
      return b.toString();
   }

   private static String name(short id) {
      try {
         return zombie.core.TilePropertyAliasMap.instance.properties.get(id).propertyName;
      } catch (Throwable t) {
         return "#" + id;
      }
   }

   /** IsoGridSquare.TreatAsSolidFloor: true = do not fill the cache (a worker of the batch, or any thread while it runs). */
   public static boolean noCache() {
      return forking;
   }

   // ---------------------------------------------------------------- blocked-grid pointers (setBlockedGridPointers hook)

   /** IsoGridSquare.setBlockedGridPointers(cellGetSquare) during the phase: true = deferred (the caller returns). */
   public static boolean deferPointers(IsoGridSquare sq) {
      if (!deferOn || Thread.currentThread() != phaseThread || forking) {
         return false;
      }
      if (sq.pzoptHrPend != phase) {
         sq.pzoptHrPend = phase;
         pending.add(sq);
      }
      sq.pzoptHrPath = sq.pathMatrix;
      deferred++;
      return true;
   }

   /** Before a direct pointer write (IsoChunk.RecalcAllWithNeighbour, dz == 0): apply the deferred calls of both squares. */
   public static void flush2(IsoGridSquare a, IsoGridSquare b) {
      if (!deferOn || Thread.currentThread() != phaseThread) {
         return;
      }
      if (a != null) {
         flush(a);
      }
      if (b != null) {
         flush(b);
      }
   }

   private static boolean flushing;

   private static void flush(IsoGridSquare sq) {
      if (sq.pzoptHrPend != phase) {
         return;
      }
      sq.pzoptHrPend = 0;
      flushes++;
      flushing = true;
      boolean d = deferOn;
      deferOn = false;
      try {
         sq.pzoptApplyPointers(sq.pzoptHrPath);
      } finally {
         deferOn = d;
         flushing = false;
      }
   }

   private static void flushAll() {
      if (!deferOn) {
         return;
      }
      for (int i = 0; i < pending.size(); i++) {
         flush(pending.get(i));
      }
      pending.clear();
   }

   // ---------------------------------------------------------------- the memo (ReCalculateCollide / PathFind / VisionBlocked hooks)

   public static final int COLLIDE = 0;
   public static final int PATH = 1;
   public static final int VISION = 2;

   private static int slot(int x, int y, int z) {
      int lx = x - ox;
      int ly = y - oy;
      int lz = z - oz;
      if (lx < 0 || lx >= BW || ly < 0 || ly >= BW || lz < 0 || lz >= nz) {
         return -1;
      }
      return (lz * BW + ly) * BW + lx;
   }

   private static int dir(int dx, int dy, int dz) {
      return (dx + 1) * 9 + (dy + 1) * 3 + dz + 1;
   }

   /** The memoised bit of self -> target (0 / 1), or -1 = compute it (the memo's property recomputes are applied first). */
   public static int memo(IsoGridSquare self, IsoGridSquare target, int kind) {
      if (!memoOn || forking || Thread.currentThread() != phaseThread) {
         return -1;
      }
      int s = slot(self.x, self.y, self.z);
      if (s < 0 || slotStamp[s] != phase || slotSq[s] != self) {
         memoMisses++;
         return -1;
      }
      int dx = target.x - self.x;
      int dy = target.y - self.y;
      int dz = target.z - self.z;
      if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
         return -1;
      }
      int e = s * 27 + dir(dx, dy, dz);
      int v = val[e];
      if ((v >> 3 + kind & 1) == 0 || tgt[e] != target) {
         if ((v & 0x40) != 0) {
            memoUnmemo++;
         } else {
            memoMisses++;
         }
         return -1;
      }
      int f = eff[e] >> (kind * 2) & 3;
      if (f != 0) {
         IsoCell cell = IsoWorld.instance.currentCell;
         if ((f & 1) != 0) {
            IsoGridSquare a = cell.getGridSquare(self.x, target.y, self.z);
            if (a != null) {
               a.RecalcPropertiesIfNeeded();
            }
         }
         if ((f & 2) != 0) {
            IsoGridSquare b = cell.getGridSquare(target.x, self.y, self.z);
            if (b != null) {
               b.RecalcPropertiesIfNeeded();
            }
         }
         if (!memoOn || slotStamp[s] != phase) {
            return -1; // that recompute found stale properties near this owner: compute it as stock does, with the new ones
         }
      }
      memoHits++;
      return v >> kind & 1;
   }

   /**
    * handoffRecalcMemo, after a live computation in the phase: keep the bit for this pair's later repeats (stock asks for each pair
    * about three times a hand-off). Its inputs (the properties of the squares within reach, which squares exist) stay as they were
    * until a stale recompute nearby drops the owner's entries; pairs that read neighbour pointers (stairs, slopes) are not kept;
    * the property recomputes the first computation asked for are no-ops when repeated (one recompute per square and phase).
    */
   public static void store(IsoGridSquare self, IsoGridSquare target, int kind, boolean b) {
      if (!memoOn || !lazyOn || forking || Thread.currentThread() != phaseThread) {
         return;
      }
      int s = slot(self.x, self.y, self.z);
      if (s < 0) {
         return;
      }
      int dx = target.x - self.x;
      int dy = target.y - self.y;
      int dz = target.z - self.z;
      if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
         return;
      }
      if (slotStamp[s] != phase || slotSq[s] != self) {
         slotSq[s] = self;
         slotStamp[s] = phase;
         int b0 = s * 27;
         for (int i = 0; i < 27; i++) {
            val[b0 + i] = 0;
            eff[b0 + i] = 0;
            tgt[b0 + i] = null;
         }
      }
      int e = s * 27 + dir(dx, dy, dz);
      int v = val[e];
      if (tgt[e] != target) {
         IsoGridSquare a = null;
         IsoGridSquare bb = null;
         boolean nav = navReader(self) || navReader(target);
         if (!nav && dx != 0 && dy != 0) {
            a = CELL.getGridSquare(self.x, target.y, self.z);
            bb = CELL.getGridSquare(target.x, self.y, self.z);
            nav = navReader(a) || navReader(bb);
         }
         tgt[e] = target;
         eff[e] = 0;
         if (nav) {
            val[e] = 0x40;
            return;
         }
         v = 0;
      }
      if ((v & 0x40) != 0) {
         return;
      }
      v |= 1 << 3 + kind;
      v = b ? v | 1 << kind : v & ~(1 << kind);
      val[e] = (byte)v;
      stores++;
   }

   public static long stores;
   private static boolean lazyOn;

   // ---------------------------------------------------------------- the precompute (fork-join on the frame workers)

   private static IsoGridSquare[] ownerList = new IsoGridSquare[1024];
   private static int[] ownerSlot = new int[1024];
   private static int ownerCount;

   private static void want(int x, int y, int z, int d) {
      int s = slot(x, y, z);
      if (s < 0) {
         return;
      }
      if (wantStamp[s] != phase) {
         wantStamp[s] = phase;
         slotWant[s] = 0;
      }
      slotWant[s] |= 1 << d;
   }

   private static void wantAll(int x, int y, int z) {
      for (int d = 0; d < 27; d++) {
         if (d != 13) {
            want(x, y, z, d);
         }
      }
   }

   /** RecalcAllWithNeighbours(c): c to all 26, each of them back to c, and the four diagonal fix-ups between c's n/s/w/e. */
   private static void wantCenter(int x, int y, int z) {
      wantAll(x, y, z);
      for (int dx = -1; dx <= 1; dx++) {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx != 0 || dy != 0 || dz != 0) {
                  want(x + dx, y + dy, z + dz, dir(-dx, -dy, -dz));
               }
            }
         }
      }
      fixups(x, y, z);
   }

   private static void fixups(int x, int y, int z) {
      // n.ReCalculateAll(w): n = (x, y-1), w = (x-1, y) and both ways; likewise n-e, s-w, s-e
      want(x, y - 1, z, dir(-1, 1, 0));
      want(x - 1, y, z, dir(1, -1, 0));
      want(x, y - 1, z, dir(1, 1, 0));
      want(x + 1, y, z, dir(-1, -1, 0));
      want(x, y + 1, z, dir(-1, -1, 0));
      want(x - 1, y, z, dir(1, 1, 0));
      want(x, y + 1, z, dir(1, -1, 0));
      want(x + 1, y, z, dir(-1, 1, 0));
   }

   private static void precompute(int wx, int wy, int minLevel, int maxLevel, int minLevel2, int maxLevel2, ArrayList<IsoGridSquare> newSquares) {
      if (FrameBatch.hasPending() || FrameBatch.gameThreadInTask() || UpdateBatch.hasPendingBatch()) {
         skippedBusy++;
         return;
      }
      int x0 = wx * 8;
      int y0 = wy * 8;
      if (!box(wx, wy, minLevel2, maxLevel2)) {
         return;
      }
      // the pairs the phase asks for (a superset where cheap; a miss is computed live as before)
      for (int z = minLevel; z <= maxLevel; z++) {
         for (int i = 0; i < 8; i++) {
            for (int dz = -1; dz <= 1; dz++) {
               for (int t = -1; t <= 1; t++) {
                  // the border loop: an edge square to its outer neighbours, and back
                  edge(x0, y0 + i, z, -1, t, dz);
                  edge(x0 + 7, y0 + i, z, 1, t, dz);
                  edge(x0 + i, y0, z, t, -1, dz);
                  edge(x0 + i, y0 + 7, z, t, 1, dz);
               }
            }
            // the border loop's direct fix-ups along the outer ring and the edge (dz 0, W / E)
            for (int k = -1; k <= 8; k++) {
               want(x0 + k, y0 - 1, z, dir(-1, 0, 0));
               want(x0 + k, y0 - 1, z, dir(1, 0, 0));
               want(x0 + k, y0 + 8, z, dir(-1, 0, 0));
               want(x0 + k, y0 + 8, z, dir(1, 0, 0));
               want(x0 + k, y0, z, dir(-1, 0, 0));
               want(x0 + k, y0, z, dir(1, 0, 0));
               want(x0 + k, y0 + 7, z, dir(-1, 0, 0));
               want(x0 + k, y0 + 7, z, dir(1, 0, 0));
               want(x0 - 1, y0 + k, z, dir(1, 0, 0));
               want(x0, y0 + k, z, dir(-1, 0, 0));
               want(x0 + 8, y0 + k, z, dir(-1, 0, 0));
               want(x0 + 7, y0 + k, z, dir(1, 0, 0));
            }
         }
         wantCenter(x0, y0, z);
         wantCenter(x0 + 7, y0, z);
         wantCenter(x0, y0 + 7, z);
         wantCenter(x0 + 7, y0 + 7, z);
      }
      for (int i = 0; i < newSquares.size(); i++) {
         IsoGridSquare sq = newSquares.get(i);
         wantCenter(sq.x, sq.y, sq.z);
      }
      // the owners: squares that exist with a wanted direction
      ownerCount = 0;
      int np = 0;
      IsoCell cell = IsoWorld.instance.currentCell;
      for (int lz = 0; lz < nz; lz++) {
         for (int ly = 0; ly < BW; ly++) {
            for (int lx = 0; lx < BW; lx++) {
               int s = (lz * BW + ly) * BW + lx;
               if (wantStamp[s] != phase || slotWant[s] == 0) {
                  continue;
               }
               IsoGridSquare sq = cell.getGridSquare(ox + lx, oy + ly, oz + lz);
               if (sq == null) {
                  continue;
               }
               slotSq[s] = sq;
               slotStamp[s] = phase;
               if (ownerCount == ownerList.length) {
                  ownerList = java.util.Arrays.copyOf(ownerList, ownerCount * 2);
                  ownerSlot = java.util.Arrays.copyOf(ownerSlot, ownerCount * 2);
               }
               ownerList[ownerCount] = sq;
               ownerSlot[ownerCount++] = s;
               np += Integer.bitCount(slotWant[s]);
            }
         }
      }
      lastPairs = np;
      if (np < Config.HANDOFF_RECALC_PARALLEL_MIN) {
         for (int i = 0; i < ownerCount; i++) {
            slotStamp[ownerSlot[i]] = 0;
         }
         skippedSmall++;
         return;
      }
      owners += ownerCount;
      pairs += np;
      parallelPhases++;
      final int count = ownerCount;
      final int tasks = Math.min(count, 4 * (FrameBatch.THREADS + 1));
      long t0 = System.nanoTime();
      forking = true;
      Throwable failure;
      try {
         failure = FrameBatch.run(tasks, i -> {
            Rec r = REC.get();
            r.active = true;
            try {
               for (int k = i; k < count; k += tasks) {
                  computeOwner(ownerList[k], ownerSlot[k], r);
               }
            } finally {
               r.active = false;
            }
         });
      } finally {
         forking = false;
      }
      lastForkNs = System.nanoTime() - t0;
      forkNanos += lastForkNs;
      if (failure != null) {
         Log.info("handoff recalc: precompute failed, memo off for this chunk: " + failure);
         return;
      }
      memoOn = true;
   }

   private static boolean box(int wx, int wy, int minLevel2, int maxLevel2) {
      if (slotSq == null) {
         slotSq = new IsoGridSquare[SLOTS];
         slotStamp = new int[SLOTS];
         slotWant = new int[SLOTS];
         wantStamp = new int[SLOTS];
         val = new byte[SLOTS * 27];
         eff = new byte[SLOTS * 27];
         tgt = new IsoGridSquare[SLOTS * 27];
      }
      ox = wx * 8 - 2;
      oy = wy * 8 - 2;
      oz = Math.max(-32, minLevel2 - 1);
      nz = Math.min(31, maxLevel2 + 1) - oz + 1;
      if (nz > MAXZ || nz <= 0) {
         nz = 0;
         return false;
      }
      return true;
   }

   private static void edge(int x, int y, int z, int dx, int dy, int dz) {
      want(x, y, z, dir(dx, dy, dz));
      want(x + dx, y + dy, z + dz, dir(-dx, -dy, -dz));
   }

   private static boolean navReader(IsoGridSquare sq) {
      return sq != null && (sq.HasStairs() || sq.hasSlopedSurface());
   }

   /** A worker: every wanted direction of one owner (writes only this owner's entries). */
   private static void computeOwner(IsoGridSquare o, int s, Rec r) {
      int w = slotWant[s];
      int base = s * 27;
      boolean oNav = navReader(o);
      int n = 0;
      for (int d = 0; d < 27; d++) {
         int e = base + d;
         tgt[e] = null;
         eff[e] = 0;
         if ((w & 1 << d) == 0) {
            val[e] = 0;
            continue;
         }
         int dx = d / 9 - 1;
         int dy = d / 3 % 3 - 1;
         int dz = d % 3 - 1;
         IsoGridSquare t = CELL.getGridSquare(o.x + dx, o.y + dy, o.z + dz);
         if (t == null) {
            val[e] = 0;
            continue;
         }
         tgt[e] = t;
         IsoGridSquare a = null;
         IsoGridSquare b = null;
         boolean nav = oNav || navReader(t);
         if (!nav && dx != 0 && dy != 0) {
            a = CELL.getGridSquare(o.x, t.y, o.z);
            b = CELL.getGridSquare(t.x, o.y, o.z);
            nav = navReader(a) || navReader(b);
         }
         if (nav) {
            val[e] = 0x40; // not memoisable: reads neighbour pointers doGridNav changes
            continue;
         }
         r.reset();
         boolean c = o.CalculateCollide(t, false, false, false, false, CELL);
         int fc = r.code(a, b);
         r.reset();
         boolean p = o.CalculateCollide(t, false, true, false, false, CELL);
         int fp = r.code(a, b);
         r.reset();
         boolean v = o.CalculateVisionBlocked(t, CELL);
         int fv = r.code(a, b);
         if (fc < 0 || fp < 0 || fv < 0) {
            val[e] = 0x40;
            continue;
         }
         val[e] = (byte)(0x38 | (c ? 1 : 0) | (p ? 2 : 0) | (v ? 4 : 0));
         eff[e] = (byte)(fc | fp << 2 | fv << 4);
         n++;
      }
      synchronized (HandoffRecalc.class) {
         computed += n;
      }
   }

   // ---------------------------------------------------------------- devHandoffRecalcCheck (dual evaluation)

   /** The fields the phase writes on the squares of the box, captured / restored / compared. */
   public static final class Snapshot {
      final ArrayList<IsoGridSquare> sq = new ArrayList<>();
      final ArrayList<int[]> ints = new ArrayList<>();
      final ArrayList<IsoGridSquare[]> refs = new ArrayList<>();
      final ArrayList<Object[]> props = new ArrayList<>();

      static Snapshot take(int wx, int wy, int minLevel2, int maxLevel2) {
         Snapshot s = new Snapshot();
         IsoCell cell = IsoWorld.instance.currentCell;
         for (int z = Math.max(-32, minLevel2 - 1); z <= Math.min(31, maxLevel2 + 1); z++) {
            for (int y = wy * 8 - 2; y < wy * 8 + 10; y++) {
               for (int x = wx * 8 - 2; x < wx * 8 + 10; x++) {
                  IsoGridSquare q = cell.getGridSquare(x, y, z);
                  if (q != null) {
                     s.sq.add(q);
                     s.ints.add(q.pzoptHrInts());
                     s.refs.add(q.pzoptHrRefs());
                     s.props.add(q.pzoptHrPropsSave());
                  }
               }
            }
         }
         return s;
      }

      void restore() {
         for (int i = 0; i < this.sq.size(); i++) {
            this.sq.get(i).pzoptHrRestore(this.ints.get(i), this.refs.get(i));
            this.sq.get(i).pzoptHrPropsRestore(this.props.get(i));
         }
      }
   }

   public static Snapshot checkTake(int wx, int wy, int minLevel2, int maxLevel2) {
      return Snapshot.take(wx, wy, minLevel2, maxLevel2);
   }

   public static void checkRestore(Snapshot s) {
      s.restore();
   }

   /** The rig's stock run: record which squares get their properties recomputed. */
   public static void beginRecord() {
      phaseThread = Thread.currentThread();
      recorded.clear();
      recordOnly = true;
      capture = true;
   }

   public static void endRecord() {
      recordOnly = false;
      capture = false;
   }

   private static long staleAtCheck;

   public static void checkMark() {
      staleAtCheck = stale;
   }

   /** Compares the fast run (a) with the stock run (b), both taken over the same squares. */
   public static void checkCompare(Snapshot a, Snapshot b, int wx, int wy) {
      checkChunks++;
      int bad = 0;
      for (int i = 0; i < a.sq.size(); i++) {
         int[] ia = a.ints.get(i);
         int[] ib = b.ints.get(i);
         IsoGridSquare[] ra = a.refs.get(i);
         IsoGridSquare[] rb = b.refs.get(i);
         boolean same = ia[0] == ib[0] && ia[1] == ib[1] && ia[2] == ib[2] && java.util.Arrays.equals(ra, rb); // the solid-floor cache may differ (value-neutral)
         if (!same) {
            bad++;
            checkFieldMismatch++;
            if (checkFieldMismatch <= 20) {
               IsoGridSquare q = a.sq.get(i);
               Log.info("handoff recalc check: mismatch at " + q.x + "," + q.y + "," + q.z + " (chunk " + wx + "," + wy + ") fast "
                  + java.util.Arrays.toString(ia) + " stock " + java.util.Arrays.toString(ib) + " refs " + refDiff(ra, rb));
            }
         }
      }
      // the squares whose properties were recomputed: the fast run's first recomputes vs the stock run's set
      java.util.IdentityHashMap<IsoGridSquare, Boolean> stockSet = new java.util.IdentityHashMap<>();
      for (IsoGridSquare q : recorded) {
         stockSet.put(q, Boolean.TRUE);
      }
      int fastN = 0;
      int miss = 0;
      for (IsoGridSquare q : a.sq) {
         if (q.pzoptHrStamp == phase) {
            fastN++;
            if (stockSet.remove(q) == null) {
               miss++;
            }
         }
      }
      // squares outside the box recomputed by the stock run (none expected)
      int extra = stockSet.size();
      if (miss != 0 || extra != 0) {
         checkPropSetMismatch++;
         if (checkPropSetMismatch <= 10) {
            Log.info("handoff recalc check: recompute set differs at chunk " + wx + "," + wy + ": fast " + fastN + ", only fast " + miss
               + ", only stock " + extra);
         }
      }
      if (stale != staleAtCheck) {
         checkStale++;
      }
      if (bad != 0) {
         checkMismatch++;
      }
   }

   private static String refDiff(IsoGridSquare[] a, IsoGridSquare[] b) {
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < a.length; i++) {
         if (a[i] != b[i]) {
            s.append(i).append(':').append(a[i] == null ? "null" : a[i].x + "," + a[i].y + "," + a[i].z).append('/')
               .append(b[i] == null ? "null" : b[i].x + "," + b[i].y + "," + b[i].z).append(' ');
         }
      }
      return s.toString();
   }

   /** One line for the chunk hand-off census. */
   public static String census() {
      return "recalc phases " + phases + " fast " + fastPhases + " parallel " + parallelPhases + " (busy " + skippedBusy + ", small " + skippedSmall
         + "), props first " + firstRecalcs + " dedup " + dedupeHits + " stale " + stale + " (owners dropped " + invalidated + "), pointers deferred " + deferred + " applied " + flushes
         + ", memo hits " + memoHits + " misses " + memoMisses + " unmemo " + memoUnmemo + " stores " + stores + ", owners " + owners + " pairs " + pairs + " computed "
         + computed + ", fork " + forkNanos / 1000 + " us, phase " + phaseNanos / 1000 + " us; by mode" + stats()
         + (Config.DEV_HANDOFF_RECALC_CHECK ? ", check chunks " + checkChunks + " mismatched " + checkMismatch + " squares " + checkFieldMismatch
            + " recompute-set " + checkPropSetMismatch + " stale " + checkStale + ", paired fast/stock " + chkFastNs / 1000 + "/" + chkStockNs / 1000
            + " us, chunks over 1 ms stock " + chkHeavy + " fast/stock " + chkHeavyFastNs / 1000 + "/" + chkHeavyStockNs / 1000 + " us" : "");
   }
}
