package pzopt;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import se.krka.kahlua.vm.UpValue;

/**
 * Static scan of a mod's OnZombieUpdate handler (pzopt.LuaZombie, idea 5 of the 2026-10-25 pass): does the handler (its
 * prototype, the functions nested in it, the Lua functions it holds as upvalues and the global Lua functions it names, two
 * levels deep) name a Java method that changes a character: setX / setVariable / changeState / setUseless / Kill / addBlood /
 * pathToLocation ... (a method name is a string constant of the prototype: the SELF instruction's key). A handler that names
 * none is read-only for the zombie as far as the scan can tell; one that touches {@code getModData} is listed separately (a
 * mod data write is a table store, invisible to the scan). A cached Java method (a local holding {@code zombie:setX}'s
 * function object) is not seen: the learned mode and the rig cover what the scan misses.
 */
public final class LuaZombieScan {
   private LuaZombieScan() {
   }

   static final Pattern WRITER = Pattern.compile(
      "^(set[A-Z0-9_].*|change.*|Kill|kill.*|add[A-Z].*|remove[A-Z].*|clear[A-Z].*|reset[A-Z].*|path[A-Z].*|Say|say[A-Z]?.*|playSound.*|"
         + "hit[A-Z].*|Hit.*|knock.*|become.*|faceThis.*|faceLocation.*|spotted|DoZombie.*|Damage.*|damage.*|transmit.*|sendObjectChange|"
         + "makeInactive|toggle.*|Wander|wander.*|teleport.*|dress.*|Dress.*|getEmitter|PlayAnim.*|Lunge|pathTo.*|moveTo.*|MoveForward)$");

   public static final class Result {
      final LinkedHashSet<String> writers = new LinkedHashSet<>();
      boolean modData;
      int prototypes;

      boolean writes() {
         return !this.writers.isEmpty();
      }

      String summary() {
         StringBuilder sb = new StringBuilder();
         int k = 0;
         for (String w : this.writers) {
            if (k++ == 6) {
               sb.append(",...");
               break;
            }
            sb.append(k > 1 ? "," : "").append(w);
         }
         return sb + (this.modData ? " +modData" : "");
      }
   }

   public static Result scan(LuaClosure fn) {
      Result r = new Result();
      IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
      try {
         closure(fn, r, seen, 0);
      } catch (Throwable t) {
         Log.warn("lua zombie scan: " + t);
      }
      return r;
   }

   private static void closure(LuaClosure c, Result r, IdentityHashMap<Object, Boolean> seen, int depth) {
      if (c == null || seen.put(c, Boolean.TRUE) != null) {
         return;
      }
      proto(c.prototype, c.env, r, seen, depth);
      if (depth < 2 && c.upvalues != null) {
         for (UpValue u : c.upvalues) {
            Object v = u == null ? null : u.getValue();
            if (v instanceof LuaClosure lc) {
               closure(lc, r, seen, depth + 1);
            }
         }
      }
   }

   private static void proto(Prototype p, KahluaTable env, Result r, IdentityHashMap<Object, Boolean> seen, int depth) {
      if (p == null || seen.put(p, Boolean.TRUE) != null) {
         return;
      }
      r.prototypes++;
      if (p.constants != null) {
         for (Object k : p.constants) {
            if (k instanceof String s) {
               if (s.equals("getModData")) {
                  r.modData = true;
               } else if (WRITER.matcher(s).matches()) {
                  r.writers.add(s);
               } else if (depth < 2 && env != null) {
                  Object g = env.rawget(s); // a global Lua function the handler names
                  if (g instanceof LuaClosure lc) {
                     closure(lc, r, seen, depth + 1);
                  }
               }
            }
         }
      }
      if (p.prototypes != null) {
         for (Prototype q : p.prototypes) {
            proto(q, env, r, seen, depth);
         }
      }
   }
}
