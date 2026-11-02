package pzopt;

import java.util.List;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import java.util.ArrayList;

/**
 * The per-frame translucent pass's square order, kept per chunk level ({@code translucentOrderCache}, 2026-09-27, the
 * "render-list build" of the game-thread offload pass; plan-driving-frame-time 3.1 in part).
 *
 * <p>{@code FBORenderCell.renderOneLevel_Translucent} merges three cached square lists of a level (items, cutaway window
 * frames, translucent objects) with a {@code contains} per insertion and sorts the result by world position, every frame,
 * for every on-screen level: on the flip's 120 km/h drive, with wind-animated vegetation drawn per frame, ~760 squares a frame
 * and a noticeable share of its 20 % translucent pass. The three lists only change when the level's bake rebuilds them, so
 * the merged, sorted order is kept per chunk level with a copy of its inputs (the squares in order and their coordinates);
 * a frame whose three lists hold the same squares in the same order at the same coordinates reuses it: the same list the
 * merge and the stable sort would produce. Anything else runs the stock merge and sort and stores the new result.
 */
public final class TranslucentOrder {
   private TranslucentOrder() {
   }

   private static final class Entry {
      IsoGridSquare[] src = new IsoGridSquare[16];
      int[] xy = new int[32];
      int nItems, nOutlines, nObjects;
      IsoGridSquare[] sorted = new IsoGridSquare[16];
      int nSorted;
   }

   public static long reused, rebuilt;

   public static boolean enabled() {
      return Config.TRANSLUCENT_ORDER_CACHE && Overrides.enabled() && GtAb.on(GtAb.TL_ORDER);
   }

   private static Entry entry(IsoChunk c, int playerIndex, int level, boolean create) {
      int li = level + 32;
      if (li < 0 || li >= 64 || playerIndex < 0 || playerIndex > 3) {
         return null;
      }
      Object[] slots = c.pzoptTlOrder;
      if (slots == null) {
         if (!create) {
            return null;
         }
         slots = new Object[256];
         c.pzoptTlOrder = slots;
      }
      int k = playerIndex * 64 + li;
      Entry e = (Entry)slots[k];
      if (e == null && create) {
         e = new Entry();
         slots[k] = e;
      }
      return e;
   }

   /** Game thread: fills {@code out} with the kept order when the three lists are unchanged; false = run the stock merge. */
   public static boolean reuse(IsoChunk c, int playerIndex, int level, List<IsoGridSquare> items, List<IsoGridSquare> outlines,
         List<IsoGridSquare> objects, ArrayList<IsoGridSquare> out) {
      Entry e = entry(c, playerIndex, level, false);
      if (e == null || e.nItems != items.size() || e.nOutlines != outlines.size() || e.nObjects != objects.size()) {
         return false;
      }
      int p = 0;
      if (!same(e, items, p) || !same(e, outlines, p += e.nItems) || !same(e, objects, p + e.nOutlines)) {
         return false;
      }
      out.clear();
      for (int i = 0; i < e.nSorted; i++) {
         out.add(e.sorted[i]);
      }
      reused++;
      if (Config.DEV_TL_ORDER_CHECK) {
         check(items, outlines, objects, out);
      }
      return true;
   }

   public static long checks, mismatches;

   /** devTlOrderCheck: the stock merge and stable sort of the same three lists, compared with the reused order. */
   private static void check(List<IsoGridSquare> items, List<IsoGridSquare> outlines, List<IsoGridSquare> objects, ArrayList<IsoGridSquare> reusedOrder) {
      java.util.ArrayList<IsoGridSquare> m = new java.util.ArrayList<>(items);
      for (IsoGridSquare sq : outlines) {
         if (!m.contains(sq)) {
            m.add(sq);
         }
      }
      for (IsoGridSquare sq : objects) {
         if (!m.contains(sq)) {
            m.add(sq);
         }
      }
      final int worldRight = zombie.iso.IsoWorld.instance.getMetaGrid().getMaxX() * 256;
      m.sort((o1, o2) -> (o1.x + o1.y * worldRight) - (o2.x + o2.y * worldRight)); // List.sort is stable, as the game's TimSort
      checks++;
      boolean same = m.size() == reusedOrder.size();
      for (int i = 0; same && i < m.size(); i++) {
         same = m.get(i) == reusedOrder.get(i);
      }
      if (!same && ++mismatches <= 10) {
         Log.warn("translucentOrderCache: reused order differs from the stock merge (" + m.size() + " vs " + reusedOrder.size() + " squares)");
      }
   }

   private static boolean same(Entry e, List<IsoGridSquare> l, int at) {
      for (int i = 0, n = l.size(); i < n; i++) {
         IsoGridSquare sq = l.get(i);
         int k = at + i;
         if (e.src[k] != sq || sq != null && (e.xy[k * 2] != sq.x || e.xy[k * 2 + 1] != sq.y)) {
            return false;
         }
      }
      return true;
   }

   /** Game thread, after the stock merge and sort: keep the inputs and the result. */
   public static void store(IsoChunk c, int playerIndex, int level, List<IsoGridSquare> items, List<IsoGridSquare> outlines,
         List<IsoGridSquare> objects, ArrayList<IsoGridSquare> sorted) {
      Entry e = entry(c, playerIndex, level, true);
      if (e == null) {
         return;
      }
      int n = items.size() + outlines.size() + objects.size();
      if (e.src.length < n) {
         e.src = new IsoGridSquare[n + 16];
         e.xy = new int[(n + 16) * 2];
      }
      e.nItems = items.size();
      e.nOutlines = outlines.size();
      e.nObjects = objects.size();
      int k = 0;
      for (List<IsoGridSquare> l : List.of(items, outlines, objects)) {
         for (int i = 0; i < l.size(); i++, k++) {
            IsoGridSquare sq = l.get(i);
            e.src[k] = sq;
            e.xy[k * 2] = sq == null ? 0 : sq.x;
            e.xy[k * 2 + 1] = sq == null ? 0 : sq.y;
         }
      }
      if (e.sorted.length < sorted.size()) {
         e.sorted = new IsoGridSquare[sorted.size() + 16];
      }
      for (int i = 0; i < sorted.size(); i++) {
         e.sorted[i] = sorted.get(i);
      }
      e.nSorted = sorted.size();
      rebuilt++;
   }

   public static String describe() {
      return "translucentOrder reused=" + reused + " rebuilt=" + rebuilt + (Config.DEV_TL_ORDER_CHECK ? " checks=" + checks + " mismatches=" + mismatches : "");
   }
}
