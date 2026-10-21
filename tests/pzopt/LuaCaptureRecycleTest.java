package pzopt;

/**
 * GC pass 2026-10-14: UpdateBatch.CaptureList keeps a task's Lua capture records after the replay and hands them to the
 * next captures of the same length. A reused record must come back empty (nothing it referenced stays reachable) and only
 * for its own length; the list itself is empty after the recycle, as after stock's clear().
 */
public class LuaCaptureRecycleTest {
   public static void main(String[] a) {
      UpdateBatch.CaptureList list = new UpdateBatch.CaptureList();
      Object payload = new Object();
      Object[] r2 = list.take(2);
      r2[0] = "OnZombieUpdate";
      r2[1] = payload;
      list.add(r2);
      Object[] r3 = list.take(3);
      r3[0] = "OnHitZombie";
      r3[1] = payload;
      r3[2] = payload;
      list.add(r3);
      list.recycle();
      Check.check(list.isEmpty(), "list empty after recycle");
      Check.check(r2[0] == null && r2[1] == null, "a recycled record holds nothing");
      Check.check(r3[0] == null && r3[1] == null && r3[2] == null, "a recycled record holds nothing (3)");
      Check.check(list.take(3) == r3, "a record of the same length is reused");
      Check.check(list.take(2) == r2, "the other length too");
      Object[] fresh = list.take(2);
      Check.check(fresh != r2 && fresh.length == 2, "no spare left: a new record");
      Object[] other = list.take(5);
      Check.check(other.length == 5, "a length with no spare: a new record of that length");
      // the spare stack is bounded: 40 records recycled keep at most 32
      for (int i = 0; i < 40; i++) {
         list.add(new Object[2]);
      }
      list.recycle();
      int reused = 0;
      for (int i = 0; i < 40; i++) {
         Object[] r = list.take(2);
         if (r[0] == null) {
            reused++;
         }
      }
      Check.check(reused == 40, "every taken record is empty");
      System.out.println("LuaCaptureRecycleTest ok");
   }
}
