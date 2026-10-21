package pzopt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * lookupMemo (f8-resid 2026-10-15, exact): two game lookups that walk a {@code TreeMap} with
 * {@code String.CASE_INSENSITIVE_ORDER} (every compare folds the case of both strings char by char) get an exact-string hash
 * memo in front:
 * <ul>
 *   <li>{@code FileGuidTable.getFilePathFromGuid} / {@code getGuidFromFilePath}: ~12,000 guids; every clothing item of a
 *   zombie being dressed asks it (OutfitManager.getClothingItem), ~20 times a zombie. On the Louisville bench a spawn or
 *   model-add burst spent 8-20 ms a second there (asprof, run resid-diag1, route s1.5-3.2 and s10.4-10.9).</li>
 *   <li>{@code AnimationVariableHandlePool.getOrCreate}: every new character registers its animation variables by name
 *   (IsoGameCharacter.registerVariableCallbacks).</li>
 * </ul>
 * A memo entry maps the caller's exact string to what the tree returned for it; the tree is case-insensitive, so the
 * answer for one spelling never changes while the tree does not. FileGuidTable's trees change only in {@code loaded()} /
 * {@code clear()}, which drop the memo; the handle pool only grows (a name's handle, once made, is never replaced).
 * Dev check {@code devLookupMemoCheck}: every memo hit is compared with the tree's answer (log line, mismatches).
 */
public final class LookupMemo {
   private LookupMemo() {
   }

   public static final boolean ON = Config.LOOKUP_MEMO && Overrides.enabled();
   private static final boolean CHECK = Config.DEV_LOOKUP_MEMO_CHECK;
   /** ConcurrentHashMap takes no null values: a lookup that found nothing. */
   public static final String NONE = new String("<pzopt: none>");
   private static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), checked = new AtomicLong(), bad = new AtomicLong();
   private static volatile long lastLogNs;

   /** AnimationVariableHandlePool: exact name -> its handle (handles are never replaced). */
   public static final ConcurrentHashMap<String, Object> HANDLES = new ConcurrentHashMap<>(1024);

   public static Map<String, String> newStringMemo() {
      return new ConcurrentHashMap<>(256);
   }

   /** FileGuidTable: the memoised answer of {@code tree.get(key)}, or the tree's own. */
   public static String get(Map<String, String> memo, Map<String, String> tree, String key) {
      if (key == null) {
         return tree.get(key);
      }
      String v = memo.get(key);
      if (v == null) {
         misses.incrementAndGet();
         String t = tree.get(key);
         memo.put(key, t == null ? NONE : t);
         return t;
      }
      hits.incrementAndGet();
      String r = v == NONE ? null : v;
      if (CHECK) {
         check(r, tree.get(key));
      }
      return r;
   }

   /** Dev check: one memo answer against the tree's. */
   public static void check(Object memo, Object tree) {
      checked.incrementAndGet();
      if (memo != tree && (memo == null || !memo.equals(tree))) {
         bad.incrementAndGet();
      }
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         lastLogNs = now;
         Log.info("lookup memo: hits " + hits.get() + ", misses " + misses.get() + ", checked " + checked.get() + ", mismatches " + bad.get());
      }
   }

   public static void hit() {
      hits.incrementAndGet();
   }

   public static void miss() {
      misses.incrementAndGet();
   }

   public static boolean checking() {
      return CHECK;
   }

   public static String describe() {
      return "lookup_memo=hits:" + hits.get() + ",misses:" + misses.get() + ",checked:" + checked.get() + ",mismatches:" + bad.get();
   }
}
