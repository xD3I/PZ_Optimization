package pzopt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * luaFastTable: {@link LuaMap} must behave exactly like the LinkedHashMap it replaces behind a Lua table: same get /
 * containsKey / put / remove answers, same iteration order (pairs order), same views. A random differential run over
 * the key kinds Lua tables hold (integers 1..n as Doubles, other doubles incl. -0.0 and NaN, strings, booleans,
 * tables / Java objects by identity, a null key), with removals that punch holes into the integer run and re-adds.
 */
public class LuaMapTest {
   static final class IdKey {
      final int id;

      IdKey(int id) {
         this.id = id;
      }
   }

   /** A Java object key with its own equals / hashCode (a game object used as a table key). */
   static final class ValKey {
      final int v;

      ValKey(int v) {
         this.v = v;
      }

      @Override
      public boolean equals(Object o) {
         return o instanceof ValKey k && k.v == this.v;
      }

      @Override
      public int hashCode() {
         return this.v % 7; // collisions on purpose
      }
   }

   public static void main(String[] args) {
      Random r = new Random(42);
      IdKey[] ids = new IdKey[20];
      for (int i = 0; i < ids.length; i++) {
         ids[i] = new IdKey(i);
      }
      long ops = 0;
      for (int round = 0; round < 400; round++) {
         LinkedHashMap<Object, Object> ref = new LinkedHashMap<>();
         LuaMap m = new LuaMap();
         int n = 1 + r.nextInt(round < 200 ? 40 : 600);
         for (int step = 0; step < n * 6; step++) {
            Object key = randomKey(r, ids, n);
            int op = r.nextInt(10);
            if (op < 5) {
               Object v = r.nextInt(5) == 0 ? null : (Object) (double) r.nextInt(1000);
               Check.check(java.util.Objects.equals(ref.put(key, v), m.put(key, v)), "put " + key);
            } else if (op < 7) {
               Check.check(java.util.Objects.equals(ref.remove(key), m.remove(key)), "remove " + key);
            } else if (op < 9) {
               Check.check(java.util.Objects.equals(ref.get(key), m.get(key)), "get " + key);
               Check.check(ref.containsKey(key) == m.containsKey(key), "containsKey " + key);
            } else if (r.nextInt(30) == 0) {
               ref.clear();
               m.clear();
            } else {
               // sequential append, the t[#t+1] = v pattern
               double k = m.size() + 1;
               Check.check(java.util.Objects.equals(ref.put(k, "s"), m.put(k, "s")), "append");
            }
            ops++;
            if (step % 17 == 0) {
               same(ref, m);
            }
         }
         same(ref, m);
         // iterator removal of every other entry
         Iterator<Map.Entry<Object, Object>> a = ref.entrySet().iterator();
         Iterator<Map.Entry<Object, Object>> b = m.entrySet().iterator();
         int i = 0;
         while (a.hasNext()) {
            Check.check(b.hasNext(), "iterator length");
            Map.Entry<Object, Object> ea = a.next();
            Map.Entry<Object, Object> eb = b.next();
            Check.check(java.util.Objects.equals(ea.getKey(), eb.getKey()), "iterator key order");
            if ((i++ & 1) == 0) {
               a.remove();
               b.remove();
            } else {
               ea.setValue("set");
               eb.setValue("set");
            }
         }
         Check.check(!b.hasNext(), "iterator end");
         same(ref, m);
      }
      // the integer prefix survives growth, a hole and a refill
      LuaMap s = new LuaMap();
      for (int i = 1; i <= 1000; i++) {
         s.put((double) i, i);
      }
      Check.check(s.seq == 1000, "seq after 1000 appends: " + s.seq);
      s.remove(500.0);
      Check.check(s.seq == 499, "seq after a hole");
      Check.check(s.get(501.0).equals(501), "get past the hole");
      s.put(500.0, "back");
      Object[] ks = s.keyArray();
      Check.check(ks[ks.length - 1].equals(500.0), "a re-added key goes to the end");
      try {
         LuaMap c = new LuaMap();
         c.put("a", 1);
         for (Object k : c.keySet()) {
            c.put("b", 2);
         }
         Check.check(false, "a structural change during iteration must throw");
      } catch (java.util.ConcurrentModificationException expected) {
      }
      // arrays grown, shrunk from the end, holed and refilled: len on every step
      for (int round = 0; round < 200; round++) {
         LinkedHashMap<Object, Object> ref = new LinkedHashMap<>();
         LuaMap m = new LuaMap();
         for (int step = 0; step < 300; step++) {
            int op = r.nextInt(10);
            if (op < 6) {
               double k = m.size() + 1;
               ref.put(k, "v");
               m.put(k, "v");
            } else if (op < 8 && !ref.isEmpty()) {
               double k = (double) m.size();
               ref.remove(k);
               m.remove(k);
            } else if (op < 9) {
               double k = 1 + r.nextInt(m.size() + 3);
               ref.remove(k);
               m.remove(k);
            } else {
               Object k = r.nextBoolean() ? (Object) "x" : (Object) (double) (1 + r.nextInt(m.size() + 3));
               ref.put(k, 1.0);
               m.put(k, 1.0);
            }
            same(ref, m);
         }
      }
      Check.check(seqLens > 1000, "the sequence shortcut was exercised: " + seqLens);
      System.out.println("LuaMapTest ok (" + ops + " random ops against LinkedHashMap, " + lens + " len checks, " + seqLens + " plain arrays)");
   }

   static Object randomKey(Random r, IdKey[] ids, int n) {
      switch (r.nextInt(9)) {
         case 0:
         case 1:
         case 2:
            return (double) (1 + r.nextInt(n));
         case 3:
            return switch (r.nextInt(6)) {
               case 0 -> -0.0;
               case 1 -> 0.0;
               case 2 -> Double.NaN;
               case 3 -> 1.5;
               default -> (double) -r.nextInt(5);
            };
         case 4:
         case 5:
            return "k" + r.nextInt(n);
         case 6:
            return ids[r.nextInt(ids.length)];
         case 7:
            return new ValKey(r.nextInt(30));
         default:
            return r.nextInt(20) == 0 ? null : (Object) r.nextBoolean();
      }
   }

   static void same(LinkedHashMap<Object, Object> ref, LuaMap m) {
      Check.check(ref.size() == m.size(), "size " + ref.size() + " vs " + m.size());
      Check.check(ref.isEmpty() == m.isEmpty(), "isEmpty");
      List<Object> a = new ArrayList<>(ref.keySet());
      Object[] b = m.keySet().toArray();
      Check.check(a.size() == b.length, "keySet size");
      for (int i = 0; i < b.length; i++) {
         Check.check(java.util.Objects.equals(a.get(i), b[i]), "key order at " + i + ": " + a.get(i) + " vs " + b[i]);
         Check.check(java.util.Objects.equals(ref.get(a.get(i)), m.get(b[i])), "value of " + b[i]);
      }
      List<Object> va = new ArrayList<>(ref.values());
      List<Object> vb = new ArrayList<>(m.values());
      Check.check(va.equals(vb), "values order");
      Check.check(ref.equals(m) && m.equals(ref), "Map.equals");
      Check.check(ref.hashCode() == m.hashCode(), "Map.hashCode");
      if (!ref.containsValue(null)) {
         // KahluaTableImpl.len: the LuaMap shortcut for a plain array must give the stock binary search's answer
         lens++;
         int la = new se.krka.kahlua.j2se.KahluaTableImpl(ref).len();
         int lb = new se.krka.kahlua.j2se.KahluaTableImpl(m).len();
         Check.check(la == lb, "len " + la + " vs " + lb + (m.isSequence() ? " (sequence)" : ""));
         if (m.isSequence()) {
            seqLens++;
         }
      }
   }

   static int lens;
   static int seqLens;
}
