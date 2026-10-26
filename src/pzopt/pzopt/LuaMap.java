package pzopt;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * The hash map behind a Lua table when {@code luaFastTable} is on (2026-10-25, the Lua VM pass): a compact,
 * insertion-ordered open-addressing map (the layout CPython's dict uses) in place of the {@code LinkedHashMap} that
 * {@code J2SEPlatform.newTable} gives every {@code KahluaTableImpl}.
 *
 * <p><b>Same observable behaviour as the LinkedHashMap.</b> Iteration order is insertion order (a key that is
 * re-assigned keeps its place, a key removed and added again goes to the end), so {@code pairs} visits keys in exactly
 * the order the stock table did; key equality is HashMap's ({@code k == key || key.equals(k)} after a hash match, so a
 * Double key matches on {@code doubleToLongBits}, a Java object key on its own equals / hashCode); views
 * ({@code entrySet}, {@code keySet}, {@code values}) are full Map views and their iterators fail fast on a structural
 * change as LinkedHashMap's do. What changes is the cost: entries live in three parallel arrays (no Node object per
 * entry), and the leading run of keys 1, 2, 3 ... n stored in that order (an array built with {@code t[#t+1] = v} or
 * {@code table.insert}) is found by index without hashing ({@link #seq}), which is what {@code #t}, {@code ipairs} and
 * {@code t[i]} read.
 */
public final class LuaMap extends AbstractMap<Object, Object> {
   /** A removed entry's key in the dense arrays (kept so the order of the others never moves). */
   static final Object TOMB = new Object();
   private static final int EMPTY = 0;
   private static final int DUMMY = -1;

   Object[] keys;
   Object[] vals;
   int[] hashes;
   /** Dense slots in use (live entries and tombstones). */
   int used;
   int size;
   /** Open-addressing index: 0 empty, -1 a deleted slot, else the dense position + 1. */
   int[] index;
   int dummies;
   /** keys[0 .. seq-1] are exactly 1.0, 2.0 ... seq (as Doubles) with no tombstone among them. */
   int seq;
   int modCount;

   public LuaMap() {
      this.keys = new Object[4];
      this.vals = new Object[4];
      this.hashes = new int[4];
      this.index = new int[8];
   }

   static int hash(Object key) {
      int h;
      if (key == null) {
         return 0; // LinkedHashMap holds a null key too (a Lua t[nil] = v reaches rawset and the map stores it)
      } else if (key instanceof String s) {
         h = s.hashCode();
      } else if (key instanceof Double d) {
         long b = Double.doubleToLongBits(d);
         h = (int) (b ^ (b >>> 32));
      } else {
         h = key.hashCode();
      }
      h *= 0x9E3779B9;
      return h ^ (h >>> 15);
   }

   /** Dense position of key, or -1. */
   int find(Object key, int h) {
      int[] idx = this.index;
      int mask = idx.length - 1;
      int i = h & mask;
      int perturb = h;
      while (true) {
         int e = idx[i];
         if (e == EMPTY) {
            return -1;
         }
         if (e > 0) {
            int p = e - 1;
            if (this.hashes[p] == h) {
               Object k = this.keys[p];
               if (k == key || (key != null && key.equals(k))) {
                  return p;
               }
            }
         }
         perturb >>>= 5;
         i = (i * 5 + 1 + perturb) & mask;
      }
   }

   /** The position a lookup of an integral Double key in 1..seq goes to, else -1. */
   private int seqPos(Object key) {
      if (key instanceof Double d && this.seq > 0) {
         double v = d;
         int i = (int) v;
         if (i == v && i >= 1 && i <= this.seq) {
            return i - 1;
         }
      }
      return -1;
   }

   @Override
   public Object get(Object key) {
      int p = this.seqPos(key);
      if (p >= 0) {
         return this.vals[p];
      }
      p = this.find(key, hash(key));
      return p < 0 ? null : this.vals[p];
   }

   /** get() for a key the caller knows is a Double holding v (the table's integer reads). */
   public Object getNumber(Object key, double v) {
      int i = (int) v;
      if (i == v && i >= 1 && i <= this.seq) {
         return this.vals[i - 1];
      }
      int p = this.find(key, hash(key));
      return p < 0 ? null : this.vals[p];
   }

   /** Every key is in the leading run 1..size (a plain array): KahluaUtil.len's binary search finds exactly size. */
   public boolean isSequence() {
      return this.seq == this.size;
   }

   /** The value of the integer key d when it is in the leading 1..seq run, else null (luaJitNumbers). */
   public Object seqGet(double d) {
      int i = (int) d;
      if (i == d && i >= 1 && i <= this.seq) {
         return this.vals[i - 1];
      }
      return null;
   }

   /** get() for a String key. */
   public Object getString(String key) {
      int h = key.hashCode() * 0x9E3779B9;
      int p = this.find(key, h ^ (h >>> 15));
      return p < 0 ? null : this.vals[p];
   }

   @Override
   public boolean containsKey(Object key) {
      return this.seqPos(key) >= 0 || this.find(key, hash(key)) >= 0;
   }

   /** luaJitInlineCache: replaces the value at a live position (as put of its present key); returns the old one. */
   public Object setValueAt(int p, Object value) {
      Object old = this.vals[p];
      this.vals[p] = value;
      return old;
   }

   @Override
   public Object put(Object key, Object value) {
      int p = this.seqPos(key);
      int h = 0;
      if (p < 0) {
         h = hash(key);
         p = this.find(key, h);
      }
      if (p >= 0) {
         Object old = this.vals[p];
         this.vals[p] = value;
         return old;
      }
      this.append(key, value, h);
      return null;
   }

   private void append(Object key, Object value, int h) {
      if (this.used == this.keys.length) {
         this.makeRoom();
      }
      int p = this.used++;
      this.keys[p] = key;
      this.vals[p] = value;
      this.hashes[p] = h;
      this.size++;
      this.modCount++;
      if (this.seq == p && key instanceof Double d && d == p + 1) {
         this.seq = p + 1;
      }
      if ((this.used + this.dummies) * 3 >= this.index.length * 2) {
         this.rebuildIndex(this.index.length * 2);
      } else {
         this.insertIndex(h, p);
      }
   }

   private void insertIndex(int h, int p) {
      int[] idx = this.index;
      int mask = idx.length - 1;
      int i = h & mask;
      int perturb = h;
      while (idx[i] > 0) {
         perturb >>>= 5;
         i = (i * 5 + 1 + perturb) & mask;
      }
      if (idx[i] == DUMMY) {
         this.dummies--;
      }
      idx[i] = p + 1;
   }

   private void rebuildIndex(int len) {
      int n = 8;
      while (n < len || n * 2 < this.used * 3) {
         n <<= 1;
      }
      this.index = new int[n];
      this.dummies = 0;
      for (int p = 0; p < this.used; p++) {
         if (this.keys[p] != TOMB) {
            this.insertIndex(this.hashes[p], p);
         }
      }
   }

   /** The dense arrays are full: squeeze out the tombstones when there are many, else grow (order kept either way). */
   private void makeRoom() {
      int cap = this.keys.length;
      if (this.size * 4 < cap * 3) {
         this.compact(cap);
      } else {
         this.compact(cap * 2);
      }
   }

   private void compact(int cap) {
      Object[] nk = new Object[cap];
      Object[] nv = new Object[cap];
      int[] nh = new int[cap];
      int q = 0;
      for (int p = 0; p < this.used; p++) {
         Object k = this.keys[p];
         if (k != TOMB) {
            nk[q] = k;
            nv[q] = this.vals[p];
            nh[q] = this.hashes[p];
            q++;
         }
      }
      this.keys = nk;
      this.vals = nv;
      this.hashes = nh;
      this.used = q;
      int s = 0;
      while (s < q && nk[s] instanceof Double d && d == s + 1) {
         s++;
      }
      this.seq = s;
      this.rebuildIndex(Math.max(8, q * 2));
   }

   @Override
   public Object remove(Object key) {
      int h = hash(key);
      int[] idx = this.index;
      int mask = idx.length - 1;
      int i = h & mask;
      int perturb = h;
      while (true) {
         int e = idx[i];
         if (e == EMPTY) {
            return null;
         }
         if (e > 0) {
            int p = e - 1;
            if (this.hashes[p] == h) {
               Object k = this.keys[p];
               if (k == key || (key != null && key.equals(k))) {
                  return this.removeAt(p, i);
               }
            }
         }
         perturb >>>= 5;
         i = (i * 5 + 1 + perturb) & mask;
      }
   }

   private Object removeAt(int p, int slot) {
      Object old = this.vals[p];
      this.keys[p] = TOMB;
      this.vals[p] = null;
      this.index[slot] = DUMMY;
      this.dummies++;
      this.size--;
      this.modCount++;
      if (p < this.seq) {
         this.seq = p;
      }
      if (this.size == 0) {
         this.clearArrays();
      }
      return old;
   }

   /** Removes the entry at dense position p (an iterator's remove). */
   void removeEntryAt(int p) {
      Object k = this.keys[p];
      if (k == TOMB) {
         return;
      }
      this.remove(k);
   }

   private void clearArrays() {
      if (this.keys.length > 16) {
         this.keys = new Object[4];
         this.vals = new Object[4];
         this.hashes = new int[4];
         this.index = new int[8];
      } else {
         java.util.Arrays.fill(this.keys, 0, this.used, null);
         java.util.Arrays.fill(this.vals, 0, this.used, null);
         java.util.Arrays.fill(this.index, 0);
      }
      this.used = 0;
      this.dummies = 0;
      this.seq = 0;
   }

   @Override
   public void clear() {
      if (this.used > 0) {
         this.modCount++;
      }
      this.size = 0;
      this.clearArrays();
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public boolean isEmpty() {
      return this.size == 0;
   }

   /** The live keys in iteration order (KahluaTableImpl.iterator's snapshot). */
   public Object[] keyArray() {
      Object[] out = new Object[this.size];
      int q = 0;
      Object[] k = this.keys;
      for (int p = 0, n = this.used; p < n; p++) {
         Object o = k[p];
         if (o != TOMB) {
            out[q++] = o;
         }
      }
      return out;
   }

   /** The dense position of the live entry for key if it still sits at hint (the pairs fast path), else -1. */
   public int positionIfAt(Object key, int hint) {
      return hint >= 0 && hint < this.used && this.keys[hint] == key ? hint : -1;
   }

   public Object keyAt(int p) {
      return this.keys[p];
   }

   public Object valueAt(int p) {
      return this.vals[p];
   }

   /** Dense positions of the live entries, parallel to keyArray(). */
   public int[] positionArray() {
      int[] out = new int[this.size];
      int q = 0;
      Object[] k = this.keys;
      for (int p = 0, n = this.used; p < n; p++) {
         if (k[p] != TOMB) {
            out[q++] = p;
         }
      }
      return out;
   }

   private abstract class DenseIterator<T> implements Iterator<T> {
      int next;
      int last = -1;
      int expected = LuaMap.this.modCount;

      DenseIterator() {
         this.advance();
      }

      private void advance() {
         Object[] k = LuaMap.this.keys;
         int n = LuaMap.this.used;
         while (this.next < n && k[this.next] == TOMB) {
            this.next++;
         }
      }

      @Override
      public boolean hasNext() {
         return this.next < LuaMap.this.used;
      }

      int nextPos() {
         if (LuaMap.this.modCount != this.expected) {
            throw new ConcurrentModificationException();
         }
         if (this.next >= LuaMap.this.used) {
            throw new NoSuchElementException();
         }
         int p = this.next++;
         this.last = p;
         this.advance();
         return p;
      }

      @Override
      public void remove() {
         if (this.last < 0) {
            throw new IllegalStateException();
         }
         if (LuaMap.this.modCount != this.expected) {
            throw new ConcurrentModificationException();
         }
         Object k = LuaMap.this.keys[this.last];
         int keep = LuaMap.this.used;
         LuaMap.this.remove(k);
         this.last = -1;
         this.expected = LuaMap.this.modCount;
         if (LuaMap.this.used != keep) {
            // the map emptied and reset its arrays: nothing is left to visit
            this.next = LuaMap.this.used;
         }
      }
   }

   private final class Entry implements Map.Entry<Object, Object> {
      final int p;
      final Object key;

      Entry(int p) {
         this.p = p;
         this.key = LuaMap.this.keys[p];
      }

      @Override
      public Object getKey() {
         return this.key;
      }

      @Override
      public Object getValue() {
         return LuaMap.this.keys.length > this.p && LuaMap.this.keys[this.p] == this.key ? LuaMap.this.vals[this.p] : LuaMap.this.get(this.key);
      }

      @Override
      public Object setValue(Object value) {
         if (LuaMap.this.keys.length > this.p && LuaMap.this.keys[this.p] == this.key) {
            Object old = LuaMap.this.vals[this.p];
            LuaMap.this.vals[this.p] = value;
            return old;
         }
         return LuaMap.this.put(this.key, value);
      }

      @Override
      public boolean equals(Object o) {
         return o instanceof Map.Entry<?, ?> e && java.util.Objects.equals(this.key, e.getKey()) && java.util.Objects.equals(this.getValue(), e.getValue());
      }

      @Override
      public int hashCode() {
         return java.util.Objects.hashCode(this.key) ^ java.util.Objects.hashCode(this.getValue());
      }

      @Override
      public String toString() {
         return this.key + "=" + this.getValue();
      }
   }

   private Set<Map.Entry<Object, Object>> entrySet;
   private Set<Object> keySet;
   private Collection<Object> values;

   @Override
   public Set<Map.Entry<Object, Object>> entrySet() {
      Set<Map.Entry<Object, Object>> es = this.entrySet;
      if (es == null) {
         es = new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<Object, Object>> iterator() {
               return new DenseIterator<>() {
                  @Override
                  public Map.Entry<Object, Object> next() {
                     return new Entry(this.nextPos());
                  }
               };
            }

            @Override
            public int size() {
               return LuaMap.this.size;
            }

            @Override
            public void clear() {
               LuaMap.this.clear();
            }

            @Override
            public boolean contains(Object o) {
               if (!(o instanceof Map.Entry<?, ?> e) || e.getKey() == null || !LuaMap.this.containsKey(e.getKey())) {
                  return false;
               }
               return java.util.Objects.equals(LuaMap.this.get(e.getKey()), e.getValue());
            }

            @Override
            public boolean remove(Object o) {
               if (this.contains(o)) {
                  LuaMap.this.remove(((Map.Entry<?, ?>) o).getKey());
                  return true;
               }
               return false;
            }
         };
         this.entrySet = es;
      }
      return es;
   }

   @Override
   public Set<Object> keySet() {
      Set<Object> ks = this.keySet;
      if (ks == null) {
         ks = new AbstractSet<>() {
            @Override
            public Iterator<Object> iterator() {
               return new DenseIterator<>() {
                  @Override
                  public Object next() {
                     return LuaMap.this.keys[this.nextPos()];
                  }
               };
            }

            @Override
            public int size() {
               return LuaMap.this.size;
            }

            @Override
            public boolean contains(Object o) {
               return LuaMap.this.containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
               if (LuaMap.this.containsKey(o)) {
                  LuaMap.this.remove(o);
                  return true;
               }
               return false;
            }

            @Override
            public void clear() {
               LuaMap.this.clear();
            }

            @Override
            public Object[] toArray() {
               return LuaMap.this.keyArray();
            }
         };
         this.keySet = ks;
      }
      return ks;
   }

   @Override
   public Collection<Object> values() {
      Collection<Object> vs = this.values;
      if (vs == null) {
         vs = new AbstractCollection<>() {
            @Override
            public Iterator<Object> iterator() {
               return new DenseIterator<>() {
                  @Override
                  public Object next() {
                     return LuaMap.this.vals[this.nextPos()];
                  }
               };
            }

            @Override
            public int size() {
               return LuaMap.this.size;
            }

            @Override
            public void clear() {
               LuaMap.this.clear();
            }
         };
         this.values = vs;
      }
      return vs;
   }
}
