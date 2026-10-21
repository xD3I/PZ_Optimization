package pzopt;

import java.util.List;

/**
 * f9-burst (2026-10-15): a census of ZombieGroupManager's groups. One Louisville run of 17 (f9p-L12) spent ~16 % of its game
 * thread in ZombieGroup.update for the whole route (none of the others had it in their game-thread profile) and had 33-46 ms
 * steps for a second at the horde's first sight. Every 10 s: group count, members (sum / largest), free pool, and the
 * slowest preupdate sweep of the period. Game thread; on in instrumented runs or with devZombieGroupCensus.
 */
public final class ZombieGroupCensus {
   private ZombieGroupCensus() {
   }

   private static long sweepStart, maxSweepNs, lastLogNs;
   private static int maxGroups;

   public static void sample(List<zombie.characters.ZombieGroup> groups, int free) {
      long now = System.nanoTime();
      sweepStart = now;
      maxGroups = Math.max(maxGroups, groups.size());
      if (now - lastLogNs < 10_000_000_000L) {
         return;
      }
      lastLogNs = now;
      long members = 0;
      int largest = 0;
      for (int i = 0; i < groups.size(); i++) {
         int s = groups.get(i).size();
         members += s;
         largest = Math.max(largest, s);
      }
      Log.info("zombie groups: " + groups.size() + " (max " + maxGroups + " this period), members " + members + ", largest " + largest
            + ", free pool " + free + ", slowest sweep " + maxSweepNs / 1000L + " us");
      maxSweepNs = 0L;
      maxGroups = 0;
   }

   public static void swept() {
      maxSweepNs = Math.max(maxSweepNs, System.nanoTime() - sweepStart);
   }
}
