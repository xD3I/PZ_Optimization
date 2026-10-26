package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import zombie.iso.IsoChunk;

/**
 * {@code devLuaLoadOrderCheck} (lua-stream, 2026-10-26): the checker of the ideas that run mod handlers in another order
 * than stock. Every LoadGridsquare handler call (and, with luaLoadChunkSplit, every LoadChunk handler call) is recorded by
 * pzopt.LuaEffects with its footprint: the Lua table keys it read and wrote (by table identity and key), the squares it read
 * and changed (the self of its Java calls, the squares it asked the cell for, there or not), and three global classes
 * (G_READ: the cell, players, the clock, a static getter; G_WRITE: a change to such state or to an object of unknown owner;
 * G_RNG: a random draw, whose order decides the values every later draw gets).
 *
 * <p>Two calls conflict when one writes what the other reads or writes (a key, a whole table against any key of it, a
 * square, global state against global state, both drawing random numbers). Counted:
 * <ul>
 * <li><b>luaLoadBatch</b> (handler by handler instead of square by square, per chunk): the pairs of calls of different
 * handlers whose order the batch reverses (stock: square i's handlers before square j's; batch: handler a's squares before
 * handler b's), and the calls that touch a square of their own chunk that stock had not loaded yet when it ran them (the
 * batch runs every handler after the whole square loop). Computed from any run: the batch's reversals follow from the
 * square and handler indices, so a run with the batch off predicts what it would change.
 * <li><b>luaLoadNearestFirst</b> (chunks handed off nearest first instead of in arrival order): the pairs of chunks whose
 * hand-off order differs from their arrival order, and among them those with conflicting footprints, or where one chunk's
 * handlers read a square of the other (there or not, loaded or not depends on the order).
 * </ul>
 * A conflict is a possible difference, not a proven one (a write of the same value, a read whose answer the other call did
 * not change are counted too); zero conflicts is the proof that the order cannot matter for what was recorded.
 */
public final class LuaOrderCheck {
   private LuaOrderCheck() {
   }

   public static final boolean ON = Config.DEV_LUA_LOAD_ORDER_CHECK;

   static final class Call {
      int h, loop;
      boolean chunkEvent;
      int[] rt, rk, wt, wk;
      boolean[] rinc, winc; // a counter's read / write (t.n = t.n + 1)
      long[] rs, ws;
      int g;
   }

   static final class ChunkFp {
      int wx, wy;
      long queuedNs, seq;
      final HashSet<Long> rKeys = new HashSet<>(), wKeys = new HashSet<>(), rSq = new HashSet<>(), wSq = new HashSet<>(), rChunks = new HashSet<>();
      final HashSet<Integer> rTables = new HashSet<>(), wTables = new HashSet<>(), rWhole = new HashSet<>(), wWhole = new HashSet<>();
      final HashSet<Long> incKeys = new HashSet<>(); // keys this chunk's calls only counted on
      final HashMap<Long, String> by = new HashMap<>(); // a written key -> the handler that wrote it first
      int g;
   }

   private static Object chunk; // the chunk whose calls are being collected (identity)
   private static int cwx, cwy, minLevel, maxLevel;
   private static long chunkQueuedNs;
   private static final ArrayList<Call> calls = new ArrayList<>();
   private static final ArrayList<ChunkFp> recent = new ArrayList<>();
   private static long seq;

   // batch counters
   public static long chunks, callsSeen, batchPairs, batchTable, batchSquare, batchGlobal, batchRng, laterSquare;
   private static final TreeMap<String, long[]> PAIRS = new TreeMap<>(); // "a <-> b" -> conflicts by class
   private static final TreeMap<String, long[]> LATER = new TreeMap<>(); // handler -> calls touching a later square (read, write)
   private static final TreeMap<String, long[]> FOOT = new TreeMap<>(); // handler -> calls, table writes, square writes, global reads, global writes, rng
   private static final HashMap<String, String> GWHY = new HashMap<>();
   // nearest-first counters
   public static long chunkPairsReversed, chunkPairsConflict, cpTable, cpSquare, cpNeighbour, cpGlobal, cpRng;
   private static final ArrayList<String> EXAMPLES = new ArrayList<>();

   public static void reset() {
      chunk = null;
      calls.clear();
      recent.clear();
      chunks = callsSeen = batchPairs = batchTable = batchSquare = batchGlobal = batchRng = laterSquare = 0L;
      chunkPairsReversed = chunkPairsConflict = cpTable = cpSquare = cpNeighbour = cpGlobal = cpRng = 0L;
      batchCounter = cpCounter = 0L;
      KEYNAME.clear();
      PAIRS.clear();
      LATER.clear();
      FOOT.clear();
      GWHY.clear();
      EXAMPLES.clear();
   }

   /** A recorded handler call just ended (pzopt.LuaEffects holds its footprint): handler {@code h} at its list position. */
   static void add(IsoChunk c, zombie.iso.IsoGridSquare sq, int h, String name, boolean chunkEvent) {
      if (c != null) {
         add(c, c.wx, c.wy, c.minLevel, c.maxLevel, c.pzoptQueuedNs, sq != null, sq == null ? 0 : sq.getX(), sq == null ? 0 : sq.getY(), sq == null ? 0 : sq.getZ(),
               h, name, chunkEvent);
      }
   }

   static void add(Object c, int wx, int wy, int minL, int maxL, long queuedNs, boolean hasSq, int x, int y, int z, int h, String name, boolean chunkEvent) {
      if (c != chunk) {
         flush();
         chunk = c;
         cwx = wx;
         cwy = wy;
         minLevel = minL;
         maxLevel = maxL;
         chunkQueuedNs = queuedNs;
      }
      Call k = new Call();
      k.h = chunkEvent ? 1000 + h : h;
      k.chunkEvent = chunkEvent;
      k.loop = !hasSq ? Integer.MAX_VALUE : loopIndex(x, y, z);
      k.rt = java.util.Arrays.copyOf(LuaEffects.fpRt, LuaEffects.fpRn);
      k.rk = java.util.Arrays.copyOf(LuaEffects.fpRk, LuaEffects.fpRn);
      k.wt = java.util.Arrays.copyOf(LuaEffects.fpWt, LuaEffects.fpWn);
      k.wk = java.util.Arrays.copyOf(LuaEffects.fpWk, LuaEffects.fpWn);
      k.rinc = java.util.Arrays.copyOf(LuaEffects.fpRinc, LuaEffects.fpRn);
      k.winc = java.util.Arrays.copyOf(LuaEffects.fpWinc, LuaEffects.fpWn);
      for (int j = 0; j < LuaEffects.fpWn; j++) {
         if (LuaEffects.fpKeyName[j] != null && KEYNAME.size() < 4096) {
            KEYNAME.putIfAbsent(pack(LuaEffects.fpWt[j], LuaEffects.fpWk[j]), (String)LuaEffects.fpKeyName[j]);
         }
      }
      k.rs = java.util.Arrays.copyOf(LuaEffects.fpRs, LuaEffects.fpRsn);
      k.ws = java.util.Arrays.copyOf(LuaEffects.fpWs, LuaEffects.fpWsn);
      k.g = LuaEffects.fpG;
      calls.add(k);
      callsSeen++;
      NAMES.put(k.h, (chunkEvent ? "LoadChunk " : "") + name);
      long[] f = FOOT.computeIfAbsent(NAMES.get(k.h), u -> new long[6]);
      f[0]++;
      f[1] += k.wt.length > 0 ? 1 : 0;
      f[2] += k.ws.length > 0 ? 1 : 0;
      f[3] += (k.g & LuaEffects.G_READ) != 0 ? 1 : 0;
      f[4] += (k.g & LuaEffects.G_WRITE) != 0 ? 1 : 0;
      f[5] += (k.g & LuaEffects.G_RNG) != 0 ? 1 : 0;
      if ((k.g & LuaEffects.G_WRITE) != 0 && LuaEffects.fpGWhy != null) {
         GWHY.putIfAbsent(NAMES.get(k.h), LuaEffects.fpGWhy);
      }
   }

   private static final HashMap<Integer, String> NAMES = new HashMap<>();
   private static final HashMap<Long, String> KEYNAME = new HashMap<>();
   public static long batchCounter, cpCounter;

   /** Stock's square loop order: z outer from minLevel, x, y inner; -1 outside the chunk. */
   private static int loopIndex(int x, int y, int z) {
      int lx = x - cwx * 8, ly = y - cwy * 8;
      if (lx < 0 || lx >= 8 || ly < 0 || ly >= 8 || z < minLevel || z > maxLevel) {
         return -1;
      }
      return ((z - minLevel) * 8 + lx) * 8 + ly;
   }

   private static int loopIndex(long key) {
      int x = (int)(key >> 36), y = (int)(key >> 12 & 0xFFFFFF), z = (int)(key & 0xFFF) - 64;
      return loopIndex(x, y, z);
   }

   private static String name(int h) {
      String n = NAMES.get(h);
      return n == null ? "#" + h : n;
   }

   // a group's entries: (call index, write?, whole?); stats per handler
   private static final class Group {
      final ArrayList<int[]> e = new ArrayList<>(); // {call, write, whole}
   }

   /** The finished chunk's calls: the batch check, then its footprint for the nearest-first check. */
   public static void flush() {
      if (chunk == null || calls.isEmpty()) {
         chunk = null;
         calls.clear();
         return;
      }
      chunks++;
      // ---- batch: groups by table key, by table (whole entries), by square, global, rng
      HashMap<Long, Group> keyed = new HashMap<>(), tables = new HashMap<>(), squares = new HashMap<>();
      Group global = new Group(), rng = new Group();
      for (int i = 0; i < calls.size(); i++) {
         Call k = calls.get(i);
         if (k.chunkEvent) {
            continue; // LoadChunk runs after the square loop in both orders
         }
         for (int j = 0; j < k.rt.length; j++) {
            entry(k.rk[j] == LuaEffects.WHOLE ? null : keyed, tables, k.rt[j], k.rk[j], i, false, k.rinc[j]);
         }
         for (int j = 0; j < k.wt.length; j++) {
            entry(k.wk[j] == LuaEffects.WHOLE ? null : keyed, tables, k.wt[j], k.wk[j], i, true, k.winc[j]);
         }
         for (long s : k.rs) {
            squares.computeIfAbsent(s, x -> new Group()).e.add(new int[]{i, 0, 0, 0});
            if (loopIndex(s) > k.loop) {
               later(k, false);
            }
         }
         for (long s : k.ws) {
            squares.computeIfAbsent(s, x -> new Group()).e.add(new int[]{i, 1, 0, 0});
            if (loopIndex(s) > k.loop) {
               later(k, true);
            }
         }
         if ((k.g & (LuaEffects.G_READ | LuaEffects.G_WRITE)) != 0) {
            global.e.add(new int[]{i, (k.g & LuaEffects.G_WRITE) != 0 ? 1 : 0, 0, 0});
         }
         if ((k.g & LuaEffects.G_RNG) != 0) {
            rng.e.add(new int[]{i, 1, 0, 0});
         }
      }
      HashSet<Long> conflicting = new HashSet<>(); // call pairs already counted (a, b packed)
      for (Group gr : keyed.values()) {
         check(gr, 0, false, conflicting);
      }
      for (Group gr : tables.values()) {
         check(gr, 0, true, conflicting);
      }
      for (Group gr : squares.values()) {
         check(gr, 1, false, conflicting);
      }
      check(global, 2, false, conflicting);
      check(rng, 3, false, conflicting);
      // ---- the chunk's footprint for nearest-first
      ChunkFp f = new ChunkFp();
      f.wx = cwx;
      f.wy = cwy;
      f.queuedNs = chunkQueuedNs;
      f.seq = seq++;
      for (Call k : calls) {
         for (int j = 0; j < k.rt.length; j++) {
            if (k.rk[j] == LuaEffects.WHOLE) {
               f.rWhole.add(k.rt[j]);
            } else if (k.rinc[j]) {
               f.incKeys.add(pack(k.rt[j], k.rk[j]));
            } else {
               f.rKeys.add(pack(k.rt[j], k.rk[j]));
            }
            f.rTables.add(k.rt[j]);
         }
         for (int j = 0; j < k.wt.length; j++) {
            long pk = pack(k.wt[j], k.wk[j]);
            if (k.wk[j] == LuaEffects.WHOLE) {
               f.wWhole.add(k.wt[j]);
            } else if (k.winc[j]) {
               f.incKeys.add(pk);
            } else {
               f.wKeys.add(pk);
            }
            f.wTables.add(k.wt[j]);
            f.by.putIfAbsent(pk, name(k.h));
         }
         for (long s : k.rs) {
            f.rSq.add(s);
            int x = (int)(s >> 36) >> 3, y = (int)(s >> 12 & 0xFFFFFF) >> 3;
            if (x != cwx || y != cwy) {
               f.rChunks.add(((long)x << 32) | (y & 0xFFFFFFFFL));
            }
         }
         for (long s : k.ws) {
            f.wSq.add(s);
            int x = (int)(s >> 36) >> 3, y = (int)(s >> 12 & 0xFFFFFF) >> 3;
            if (x != cwx || y != cwy) {
               f.rChunks.add(((long)x << 32) | (y & 0xFFFFFFFFL));
            }
         }
         f.g |= k.g;
      }
      for (ChunkFp x : recent) {
         if (f.queuedNs != 0L && x.queuedNs != 0L && x.queuedNs > f.queuedNs) { // x arrived after f but was handed off before it
            chunkPairsReversed++;
            String why = conflict(x, f);
            if (why != null) {
               chunkPairsConflict++;
               if (EXAMPLES.size() < 12 || EXAMPLES.size() < 40 && EXAMPLES.stream().noneMatch(e -> e.endsWith(why))) {
                  EXAMPLES.add(x.wx + "," + x.wy + " before " + f.wx + "," + f.wy + ": " + why);
               }
            }
         }
      }
      recent.add(f);
      if (recent.size() > 96) {
         recent.remove(0);
      }
      chunk = null;
      calls.clear();
   }

   private static long pack(int t, int k) {
      return ((long)t << 32) | (k & 0xFFFFFFFFL);
   }

   private static void entry(HashMap<Long, Group> keyed, HashMap<Long, Group> tables, int t, int k, int call, boolean write, boolean inc) {
      if (keyed != null) {
         keyed.computeIfAbsent(pack(t, k), x -> new Group()).e.add(new int[]{call, write ? 1 : 0, 0, inc ? 1 : 0});
      }
      tables.computeIfAbsent((long)t, x -> new Group()).e.add(new int[]{call, write ? 1 : 0, keyed == null ? 1 : 0, 0});
   }

   private static void later(Call k, boolean write) {
      laterSquare++;
      long[] l = LATER.computeIfAbsent(name(k.h), x -> new long[2]);
      l[write ? 1 : 0]++;
   }

   /**
    * The group's pairs of calls of different handlers that the batch reverses and that conflict (one side writes; in a
    * table group one side must be a whole-table entry, keyed pairs are the keyed groups' job).
    */
   private static void check(Group gr, int cls, boolean wholeOnly, HashSet<Long> counted) {
      ArrayList<int[]> e = gr.e;
      int n = e.size();
      if (n < 2) {
         return;
      }
      boolean anyWrite = false;
      for (int[] a : e) {
         if (a[1] != 0) {
            anyWrite = true;
            break;
         }
      }
      if (!anyWrite) {
         return;
      }
      int budget = 200_000; // pair tests per group: a table every call reads and writes is reported long before this
      for (int i = 0; i < n && budget > 0; i++) {
         int[] a = e.get(i);
         Call ca = calls.get(a[0]);
         for (int j = i + 1; j < n && budget > 0; j++) {
            int[] b = e.get(j);
            budget--;
            if (a[1] == 0 && b[1] == 0 || wholeOnly && a[2] == 0 && b[2] == 0) {
               continue;
            }
            Call cb = calls.get(b[0]);
            if (ca.h == cb.h || ca == cb) {
               continue; // a handler's own calls keep their square order
            }
            boolean reversed = (ca.loop < cb.loop) != (ca.h < cb.h) && ca.loop != cb.loop;
            if (!reversed) {
               continue;
            }
            long key = a[0] < b[0] ? ((long)a[0] << 32) | b[0] : ((long)b[0] << 32) | a[0];
            if (!counted.add(key)) {
               continue;
            }
            batchPairs++;
            int c2 = cls == 0 && a[3] != 0 && b[3] != 0 ? 4 : cls; // both only count on the key
            switch (c2) {
               case 0 -> batchTable++;
               case 1 -> batchSquare++;
               case 2 -> batchGlobal++;
               case 3 -> batchRng++;
               default -> batchCounter++;
            }
            String p = ca.h < cb.h ? name(ca.h) + " <-> " + name(cb.h) : name(cb.h) + " <-> " + name(ca.h);
            PAIRS.computeIfAbsent(p, x -> new long[5])[c2]++;
         }
      }
   }

   /** Why two chunks' footprints conflict (null: they do not). */
   private static String conflict(ChunkFp a, ChunkFp b) {
      String w = writesWhat(a, b);
      if (w == null) {
         w = writesWhat(b, a);
      }
      if (w != null) {
         cpTable++;
         return "table " + w;
      }
      for (long s : a.wSq) {
         if (b.rSq.contains(s) || b.wSq.contains(s)) {
            cpSquare++;
            return "square";
         }
      }
      for (long s : b.wSq) {
         if (a.rSq.contains(s)) {
            cpSquare++;
            return "square";
         }
      }
      long ka = ((long)a.wx << 32) | (a.wy & 0xFFFFFFFFL), kb = ((long)b.wx << 32) | (b.wy & 0xFFFFFFFFL);
      if (a.rChunks.contains(kb) || b.rChunks.contains(ka)) {
         cpNeighbour++;
         return "a square of the other chunk";
      }
      if ((a.g & LuaEffects.G_WRITE) != 0 && (b.g & (LuaEffects.G_READ | LuaEffects.G_WRITE)) != 0
            || (b.g & LuaEffects.G_WRITE) != 0 && (a.g & LuaEffects.G_READ) != 0) {
         cpGlobal++;
         return "global state";
      }
      if ((a.g & LuaEffects.G_RNG) != 0 && (b.g & LuaEffects.G_RNG) != 0) {
         cpRng++;
         return "random draws";
      }
      for (long k : a.incKeys) {
         if (b.incKeys.contains(k)) {
            cpCounter++;
            return "counter " + keyName(k) + " (" + a.by.get(k) + ")";
         }
      }
      return null;
   }

   private static String keyName(long k) {
      String n = KEYNAME.get(k);
      return n == null ? "#" + Integer.toHexString((int)k) : "'" + n + "'";
   }

   /** What {@code w} writes that {@code r} reads or writes (a counter only conflicts with a plain use of it here), null: nothing. */
   private static String writesWhat(ChunkFp w, ChunkFp r) {
      for (long k : w.wKeys) {
         if (r.rKeys.contains(k) || r.wKeys.contains(k) || r.incKeys.contains(k) || r.rWhole.contains((int)(k >> 32)) || r.wWhole.contains((int)(k >> 32))) {
            return keyName(k) + " written by " + w.by.get(k);
         }
      }
      for (long k : w.incKeys) {
         if (r.rKeys.contains(k) || r.wKeys.contains(k) || r.rWhole.contains((int)(k >> 32)) || r.wWhole.contains((int)(k >> 32))) {
            return "counter " + keyName(k) + " counted by " + w.by.get(k) + ", used as a value by the other";
         }
      }
      for (int t : w.wWhole) {
         if (r.rTables.contains(t) || r.wTables.contains(t)) {
            return "a whole table (wiped)";
         }
      }
      return null;
   }

   /** The route's numbers (console). */
   public static void report() {
      flush();
      Log.info("lua order check: " + chunks + " chunks, " + callsSeen + " recorded handler calls; luaLoadBatch would reverse conflicting pairs "
            + batchPairs + " (table " + batchTable + ", square " + batchSquare + ", global " + batchGlobal + ", random " + batchRng + ", counters only " + batchCounter
            + "), calls touching a square of their chunk stock had not loaded yet " + laterSquare
            + "; nearest first: reversed chunk pairs " + chunkPairsReversed + ", conflicting " + chunkPairsConflict + " (table " + cpTable + ", square "
            + cpSquare + ", a square of the other chunk " + cpNeighbour + ", global " + cpGlobal + ", random " + cpRng + ", counters only " + cpCounter + ")");
      PAIRS.entrySet().stream().sorted((x, y) -> Long.compare(sum(y.getValue()), sum(x.getValue()))).limit(12).forEach(en ->
         Log.info("lua order check: batch pair " + en.getKey() + ": table " + en.getValue()[0] + " square " + en.getValue()[1] + " global "
               + en.getValue()[2] + " random " + en.getValue()[3] + " counters " + en.getValue()[4]));
      for (var en : LATER.entrySet()) {
         Log.info("lua order check: " + en.getKey() + " touches a later square of its chunk: reads " + en.getValue()[0] + " writes " + en.getValue()[1]);
      }
      for (var en : FOOT.entrySet()) {
         long[] f = en.getValue();
         Log.info("lua order check: footprint " + en.getKey() + ": calls " + f[0] + ", table writes " + f[1] + ", square writes " + f[2] + ", global reads "
               + f[3] + ", global writes " + f[4] + (GWHY.containsKey(en.getKey()) ? " (" + GWHY.get(en.getKey()) + ")" : "") + ", random " + f[5]);
      }
      for (String ex : EXAMPLES) {
         Log.info("lua order check: nearest first example: " + ex);
      }
   }

   private static long sum(long[] a) {
      long s = 0;
      for (long v : a) {
         s += v;
      }
      return s;
   }
}
