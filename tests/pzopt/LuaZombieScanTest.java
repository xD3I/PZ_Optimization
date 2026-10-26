package pzopt;

import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/** pzopt.LuaZombieScan (writer names in a handler's constants, nested prototypes) and LuaCollide's per-frame pair table. */
public class LuaZombieScanTest {
   public static void main(String[] args) {
      Prototype ro = proto("getX", "getY", "getModData", "isDead");
      LuaZombieScan.Result r = LuaZombieScan.scan(new LuaClosure(ro, null));
      Check.check(!r.writes(), "getters only: read-only");
      Check.check(r.modData, "getModData noted");
      Prototype inner = proto("setTargetAlpha");
      Prototype outer = proto("getX");
      outer.prototypes = new Prototype[] {inner};
      LuaZombieScan.Result w = LuaZombieScan.scan(new LuaClosure(outer, null));
      Check.check(w.writes() && w.summary().contains("setTargetAlpha"), "a nested function's setter is found: " + w.summary());
      Check.check(LuaZombieScan.scan(new LuaClosure(proto("changeState", "Kill"), null)).writes(), "changeState / Kill");
      Check.check(!LuaZombieScan.scan(new LuaClosure(proto("settings", "set"), null)).writes(), "'settings' and a bare 'set' are not setters");

      Object a = new Object(), b = new Object(), c = new Object();
      LuaZombie.frameNo = 100;
      Check.check(!LuaCollide.seen(a, b), "first report of a pair");
      Check.check(LuaCollide.seen(a, b), "the same pair again in the frame");
      Check.check(!LuaCollide.seen(b, a), "the other direction is its own event");
      Check.check(!LuaCollide.seen(a, c), "another pair");
      for (int i = 0; i < 5000; i++) {
         LuaCollide.seen(new Object(), a); // the table grows
      }
      Check.check(LuaCollide.seen(a, b) && LuaCollide.seen(a, c), "pairs survive the table growing");
      LuaZombie.frameNo = 101;
      Check.check(!LuaCollide.seen(a, b), "a new frame forgets the pairs");
      // the write barrier's method names
      try {
         Class<?> k = Probe.class;
         Check.check(LuaZombieWatch.isWriter(k.getMethod("setX", float.class)), "setX writes");
         Check.check(LuaZombieWatch.isWriter(k.getMethod("knockDown", boolean.class)), "knockDown writes");
         Check.check(!LuaZombieWatch.isWriter(k.getMethod("getX")), "getX reads");
         Check.check(!LuaZombieWatch.isWriter(k.getMethod("isDead")), "isDead reads");
         Check.check(!LuaZombieWatch.isWriter(k.getMethod("getEmitter")), "getEmitter reads");
      } catch (NoSuchMethodException e) {
         throw new RuntimeException(e);
      }
      Object z = new Object();
      LuaZombieWatch.target = z;
      LuaZombieWatch.writes = 0;
      LuaZombieWatch.call(new Object());
      LuaZombieWatch.call(z);
      Check.check(LuaZombieWatch.writes == 1, "only a write on the watched zombie counts: " + LuaZombieWatch.writes);
      LuaZombieWatch.target = null;
      System.out.println("LuaZombieScanTest ok");
   }

   public static final class Probe {
      public void setX(float x) {
      }

      public void knockDown(boolean b) {
      }

      public float getX() {
         return 0f;
      }

      public boolean isDead() {
         return false;
      }

      public Object getEmitter() {
         return null;
      }
   }

   private static Prototype proto(String... names) {
      Prototype p = new Prototype();
      p.constants = names.clone();
      p.prototypes = new Prototype[0];
      return p;
   }
}
