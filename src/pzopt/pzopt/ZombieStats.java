package pzopt;

import zombie.AchievementManager;
import zombie.statistics.Statistic;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;
import zombie.statistics.StatisticsManager;

/**
 * The zombies' travel statistics folded per frame ({@code zombieStatsFold}, 2026-09-27).
 *
 * <p>Every zombie update ends in {@code IsoZombie.updateMovementStatistics}: {@code StatisticsManager.incrementStatistic}
 * with one of three keys ("Zombie's Distance Walked / Ran / Crawled"), i.e. a {@code computeIfAbsent} with a capturing
 * lambda, a second map lookup, one float addition and {@code AchievementManager.checkAchievementsOnStatisticChange}
 * walking every achievement. On the Louisville horde that is ~2,000 calls a frame and 1.4 % of the game thread. While
 * the scheduler's update loop runs, the additions go into a local float per statistic instead, started from the
 * statistic's value and added in the zombies' order, so the value written back after the loop is bitwise the one the
 * loop of additions produced; the achievement check runs once per statistic with that value (the values only grow and a
 * statistic has at most one achievement, so the same achievements unlock, in the same frame). Outside the loop the stock
 * call runs.
 */
public final class ZombieStats {
   private ZombieStats() {
   }

   private static final String[] NAMES = {"Zombie's Distance Walked", "Zombie's Distance Ran", "Zombie's Distance Crawled"};
   private static final Statistic[] STAT = new Statistic[3];
   private static final float[] ACC = new float[3];
   private static final boolean[] PENDING = new boolean[3];
   private static boolean open;
   public static long folded, frames;

   /** Game thread, the top of the scheduler's update loop. */
   private static Thread GAME_THREAD;
   private static final Tl<float[]> WORKER = new Tl<>(() -> new float[4]); // walked, ran, crawled, frame stamp
   private static final java.util.ArrayList<float[]> WORKERS = new java.util.ArrayList<>();

   public static void begin() {
      GAME_THREAD = Thread.currentThread();
      open = Config.ZOMBIE_STATS_FOLD && Overrides.enabled() && GtAb.on(GtAb.ZOMBIE_STATS);
   }

   /** Game thread, a zombie's travel statistic: true when it was folded (the caller skips the stock increment). */
   public static boolean add(String name, float amount) {
      if (!open) {
         return false;
      }
      int i = name.equals(NAMES[0]) ? 0 : name.equals(NAMES[1]) ? 1 : name.equals(NAMES[2]) ? 2 : -1;
      if (i < 0) {
         return false;
      }
      if (Thread.currentThread() != GAME_THREAD) {
         // a zombie updated on a frame worker (entityUpdateParallel): its thread's own partial sum, added after the batch
         // (the zombies' order across threads is not stock's there anyway)
         float[] w = WORKER.get();
         if (w[3] != frames + 1) {
            w[0] = w[1] = w[2] = 0.0F;
            w[3] = frames + 1;
            synchronized (WORKERS) {
               WORKERS.add(w);
            }
         }
         w[i] += amount;
         return true;
      }
      if (!PENDING[i]) {
         final String key = NAMES[i];
         Statistic s = StatisticsManager.getInstance().getStatistics().computeIfAbsent(key, k -> new Statistic(key, StatisticType.Zombie, StatisticCategory.Travel));
         STAT[i] = s;
         ACC[i] = s.getValue();
         PENDING[i] = true;
      }
      ACC[i] += amount;
      folded++;
      return true;
   }

   /** Game thread, after the loop (also when it threw): the sums written back, one achievement check each. */
   public static void end() {
      if (!open) {
         return;
      }
      open = false;
      frames++;
      synchronized (WORKERS) { // entityUpdateParallel: the workers' partial sums after the game thread's own
         for (int k = 0; k < WORKERS.size(); k++) {
            float[] w = WORKERS.get(k);
            for (int i = 0; i < 3; i++) {
               if (w[i] != 0.0F) {
                  if (!PENDING[i]) {
                     final String key = NAMES[i];
                     STAT[i] = StatisticsManager.getInstance().getStatistics().computeIfAbsent(key, x -> new Statistic(key, StatisticType.Zombie, StatisticCategory.Travel));
                     ACC[i] = STAT[i].getValue();
                     PENDING[i] = true;
                  }
                  ACC[i] += w[i];
               }
            }
         }
         WORKERS.clear();
      }
      for (int i = 0; i < 3; i++) {
         if (PENDING[i]) {
            PENDING[i] = false;
            STAT[i].setValue(ACC[i]);
            AchievementManager.getInstance().checkAchievementsOnStatisticChange(STAT[i]);
            STAT[i] = null;
         }
      }
   }

   public static String describe() {
      return "zombieStats frames=" + frames + " folded=" + folded;
   }
}
