package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.Lua.Event;

/**
 * luaEventDispatchFast (pzopt.LuaTick): the fast loop calls the same handlers in the same order as the stock
 * Event.trigger loop, whatever the handlers do to the list while it runs (remove themselves, remove another one before
 * or after them, add new or listed ones, clear the list, replace an entry, add a duplicate). Fuzzed against an
 * independent copy of the stock loop; also the scheduler's water-filling and stagger.
 */
public class LuaTickTest {
   static final int REMOVE_SELF = 0, REMOVE_OTHER = 1, ADD_NEW = 2, ADD_LISTED = 3, CLEAR = 4, SET = 5, NONE = 6, REMOVE_ALL_COPIES = 7;

   /** What closure i does on its k-th call. */
   static final class Act {
      int onCall;
      int kind;
      int arg;
      int arg2;
   }

   static final class Scenario {
      int n;
      int[] initial; // closure ids in list order (duplicates allowed)
      List<List<Act>> acts = new ArrayList<>(); // per closure id (ids >= n are added ones)
      int rounds;
   }

   static Scenario random(Random r) {
      Scenario sc = new Scenario();
      sc.n = 1 + r.nextInt(10);
      int len = sc.n + (r.nextInt(4) == 0 ? r.nextInt(3) : 0);
      sc.initial = new int[len];
      for (int i = 0; i < len; i++) {
         sc.initial[i] = i < sc.n ? i : r.nextInt(sc.n);
      }
      for (int i = 0; i < sc.n + 8; i++) {
         List<Act> l = new ArrayList<>();
         int k = r.nextInt(3);
         for (int j = 0; j < k; j++) {
            Act a = new Act();
            a.onCall = r.nextInt(4);
            a.kind = r.nextInt(8);
            if (a.kind == CLEAR && r.nextInt(3) != 0) {
               a.kind = NONE;
            }
            a.arg = r.nextInt(sc.n + 8);
            a.arg2 = r.nextInt(12);
            l.add(a);
         }
         sc.acts.add(l);
      }
      sc.rounds = 1 + r.nextInt(3);
      return sc;
   }

   /** One run of a scenario in a mode: 0 = the reference stock loop, 1 = LuaTick.stockLoop, 2 = fast loop, 3 = slotted loop (stats path), 4 = slotted fast. */
   static String run(Scenario sc, int mode) {
      Event e = new Event("PzoptLuaTickTestEvent", 0);
      LuaClosure[] cl = new LuaClosure[sc.n + 8];
      IdentityHashMap<LuaClosure, Integer> id = new IdentityHashMap<>();
      for (int i = 0; i < cl.length; i++) {
         cl[i] = new LuaClosure(new Prototype(), null);
         id.put(cl[i], i);
      }
      for (int i : sc.initial) {
         e.callbacks.add(cl[i]);
      }
      int[] calls = new int[cl.length];
      StringBuilder trace = new StringBuilder();
      int[] nextNew = {sc.n};
      LuaTick.Invoker inv = (caller, c, params, event) -> {
         int me = id.get(c);
         trace.append(me).append(' ');
         int k = calls[me]++;
         for (Act a : sc.acts.get(me)) {
            if (a.onCall != k) {
               continue;
            }
            ArrayList<LuaClosure> cb = e.callbacks;
            switch (a.kind) {
               case REMOVE_SELF -> cb.remove(c);
               case REMOVE_OTHER -> cb.remove(cl[a.arg]);
               case ADD_NEW -> {
                  if (nextNew[0] < cl.length) {
                     cb.add(cl[nextNew[0]++]);
                  }
               }
               case ADD_LISTED -> cb.add(cl[a.arg % sc.n]);
               case CLEAR -> cb.clear();
               case SET -> {
                  if (!cb.isEmpty()) {
                     cb.set(a.arg2 % cb.size(), cl[a.arg]);
                  }
               }
               case REMOVE_ALL_COPIES -> cb.removeIf(x -> x == cl[a.arg]);
               default -> {
               }
            }
         }
      };
      LuaTick.Invoker saved = LuaTick.invoker;
      LuaTick.invoker = inv;
      try {
         for (int round = 0; round < sc.rounds; round++) {
            trace.append("| ");
            if (e.callbacks.isEmpty()) {
               continue;
            }
            switch (mode) {
               case 0 -> reference(e, inv);
               case 1 -> LuaTick.stockLoop(e, null, null);
               case 2 -> LuaTick.loop(e, new LuaTick.Ev(e.name), null, null, null, true);
               case 3 -> LuaTick.loop(e, new LuaTick.Ev(e.name), new LuaTick.Slot(e.name, 0, e.callbacks), null, null, false);
               default -> LuaTick.loop(e, new LuaTick.Ev(e.name), new LuaTick.Slot(e.name, 0, e.callbacks), null, null, true);
            }
         }
      } finally {
         LuaTick.invoker = saved;
      }
      trace.append("|| final");
      for (LuaClosure c : e.callbacks) {
         trace.append(' ').append(id.get(c));
      }
      return trace.toString();
   }

   /** The stock Event.trigger loop (the jar's, without the profiler areas, which return null with the profiler off). */
   static void reference(Event e, LuaTick.Invoker inv) {
      for (int n = 0; n < e.callbacks.size(); n++) {
         LuaClosure closure = e.callbacks.get(n);
         inv.call(null, closure, null, e.name);
         if (!e.callbacks.contains(closure)) {
            n--;
         }
      }
   }

   public static void main(String[] args) {
      Random r = new Random(20261025L);
      int checked = 0;
      for (int it = 0; it < 20000; it++) {
         Scenario sc = random(r);
         String ref = run(sc, 0);
         for (int mode = 1; mode <= 4; mode++) {
            String got = run(sc, mode);
            if (!ref.equals(got)) {
               throw new AssertionError("scenario " + it + " mode " + mode + ":\n  stock " + ref + "\n  got   " + got);
            }
            checked++;
         }
      }
      waterFill();
      stagger();
      for (double n : new double[]{2.0, 4.5, 9.0, 16.0}) {
         rate(n);
      }
      System.out.println("LuaTickTest: " + checked + " dispatch runs equal to the stock loop; water-filling, stagger and intervals ok");
   }

   static LuaTick.H handler(double us) {
      LuaTick.H h = new LuaTick.H(new LuaClosure(new Prototype(), null));
      h.pin = LuaTick.PIN_NONE;
      h.samples = LuaTick.WARM_CALLS;
      h.ewmaNs = us * 1000.0;
      h.lastRunFrame = LuaTick.frameNo;
      return h;
   }

   static void waterFill() {
      LuaTick.ALL.clear();
      LuaTick.ALL_SLOT.clear();
      for (double us : new double[]{100, 50, 30, 25, 1}) {
         LuaTick.ALL.add(handler(us));
         LuaTick.ALL_SLOT.add(null);
      }
      LuaTick.budgetNs = 85_000.0; // 85 us: 100, 50 and 30 are cut to 20 (+ 25? no: 25 > 20 too) -> level (85 - 0) / 4 = 21.25
      LuaTick.waterFill();
      Check.check(Math.abs(LuaTick.level - 21_250.0) < 1.0, "level " + LuaTick.level);
      LuaTick.budgetNs = 1e9;
      LuaTick.waterFill();
      Check.check(Double.isInfinite(LuaTick.level), "a budget over the full cost leaves every handler every frame");
      LuaTick.budgetNs = 0.0;
      LuaTick.waterFill();
      Check.check(LuaTick.level == 0.0, "no budget: level 0 (every handler at its staleness bound)");
      LuaTick.ALL.clear();
      LuaTick.ALL_SLOT.clear();
      LuaTick.budgetNs = Double.POSITIVE_INFINITY;
      LuaTick.level = Double.POSITIVE_INFINITY;
   }

   /**
    * 450 handlers of mixed cost at interval n through decide0's spaced branch (place + the due guard): each runs once per
    * n frames on average (the 2026-10-25 bug: a due placed past n + 1 frames ahead read as stale and ran at once, so at
    * n = 9 almost every handler ran every frame), and the load stays near flat.
    */
   static void rate(double n) {
      java.util.Random r = new java.util.Random(1);
      LuaTick.Slot s = new LuaTick.Slot("x", 0, new ArrayList<>());
      List<LuaTick.H> hs = new ArrayList<>();
      for (int i = 0; i < 450; i++) {
         LuaTick.H h = handler(i == 0 ? 8000 : (r.nextDouble() < 0.1 ? 200 + r.nextDouble() * 2000 : 1 + r.nextDouble() * 50));
         hs.add(h);
      }
      int frames = 600;
      long runs = 0;
      double sum = 0.0, max = 0.0, total = 0.0;
      for (LuaTick.H h : hs) {
         total += h.ewmaNs;
      }
      for (long f = 1; f <= frames; f++) {
         double load = 0.0;
         for (LuaTick.H h : hs) {
            if (h.due > f + 0.5 && h.due - f <= LuaTick.maxAhead(n) + 1.0) {
               continue;
            }
            double ideal = h.ideal + n;
            if (ideal < f + 0.5 || ideal > f + n + 1.0) {
               ideal = f + n;
            }
            h.ideal = ideal;
            h.due = LuaTick.place(s, h, ideal, n, f);
            runs++;
            load += h.ewmaNs;
         }
         if (f > 50) {
            sum += load;
            max = Math.max(max, load);
         }
      }
      double perFrame = runs / (double)frames;
      Check.check(Math.abs(perFrame - 450 / n) <= 450 / n * 0.05, "interval " + n + ": " + perFrame + " runs a frame, want " + 450 / n);
      double mean = sum / (frames - 50);
      Check.check(Math.abs(mean - total / n) <= total / n * 0.05, "interval " + n + ": load " + mean + " ns a frame, want " + total / n);
      Check.check(max <= mean + 8_000_000.0 + 1.0, "interval " + n + ": the worst frame " + max + " stays within the heaviest handler of the mean " + mean);
   }

   static void stagger() {
      LuaTick.Slot s = new LuaTick.Slot("x", 0, new ArrayList<>());
      long f = 1000;
      // 40 equal handlers of period 4, all becoming due on the same frame: the ring spreads them 10 / 10 / 10 / 10
      for (int i = 0; i < 40; i++) {
         LuaTick.H h = handler(50);
         LuaTick.place(s, h, f + 4, 4.0, f);
      }
      double min = Double.MAX_VALUE, max = 0;
      for (long d = f + 1; d <= f + 6; d++) {
         double l = s.load(d);
         if (l > 0) {
            min = Math.min(min, l);
            max = Math.max(max, l);
         }
      }
      Check.check(max - min <= 50_000.0 + 1e-6, "stagger spreads equal handlers within one handler's cost: " + min + ".." + max);
   }
}
