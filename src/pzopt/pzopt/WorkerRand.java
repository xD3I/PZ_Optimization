package pzopt;

import java.util.Random;
import org.uncommons.maths.random.CellularAutomatonRNG;

/**
 * One random stream per frame worker ({@code randPerWorker}, zombie redesign round 3, 2026-10-13, f8-zomb3).
 *
 * <p>The game's {@code Rand} draws everything from one {@code CellularAutomatonRNG}, whose {@code next} takes a fair
 * {@code ReentrantLock}. In the entity flight eight workers draw from it for every zombie every frame (the sheet-rope damage
 * roll, the sound response, the idle and wander states): on the Louisville horde it was ~7 % of the flight's samples, most
 * of it the lock hand-off between workers (run zomb2-prof5). The game's instance is wrapped: the game thread (and every
 * other thread) still draws from the game's own generator, bit for bit the same values in the same order as long as no
 * worker draws ({@code next} delegates); a frame worker draws from its own generator of the same kind, seeded from the
 * game's at its first draw. Which zombie gets which number was already decided by the workers' scheduling since the flight
 * went parallel; what changes is that the workers' draws no longer advance the game thread's stream: a statistically
 * identical game, not a bit-identical one (reported, off by default).
 */
public final class WorkerRand extends Random {
   private final Random main;
   private final CellularAutomatonRNG mainCa;

   private WorkerRand(Random main) {
      super(0L);
      this.main = main;
      this.mainCa = main instanceof CellularAutomatonRNG ca ? ca : null;
   }

   private static final boolean ENABLED = Config.RAND_PER_WORKER;
   private static java.lang.reflect.Field field;
   private static Object wrappedOf;
   public static long installs, workerDraws;
   static volatile Thread countThread; // devAnimalLosSkipCheck: count this thread's draws
   static long counted;

   /** True when the game's generator is wrapped (every draw passes next()). */
   static boolean installed() {
      return wrappedOf != null && field != null;
   }

   /** Game thread, before a parallel batch: wrap RandStandard's generator (again after the game replaced it). */
   static void ensure() {
      if (!ENABLED) {
         return;
      }
      try {
         if (field == null) {
            field = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
            field.setAccessible(true);
         }
         Object cur = field.get(zombie.core.random.RandStandard.INSTANCE);
         if (cur == null || cur instanceof WorkerRand) {
            return;
         }
         if (cur == wrappedOf) {
            return;
         }
         field.set(zombie.core.random.RandStandard.INSTANCE, new WorkerRand((Random) cur));
         wrappedOf = cur;
         installs++;
         Log.info("randPerWorker: the frame workers draw from their own generators (" + cur.getClass().getSimpleName() + " wrapped)");
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("randPerWorker: could not wrap the game's generator, off: " + e);
         field = null;
         wrappedOf = new Object();
      }
   }

   @Override
   protected int next(int bits) {
      if (Thread.currentThread() instanceof FrameBatch.Worker w && ZombAb.on(ZombAb.RAND_WORKER)) {
         Random r = w.rng;
         if (r == null) {
            r = seeded();
            w.rng = r;
         }
         return r.nextInt() >>> (32 - bits);
      }
      if (countThread == Thread.currentThread()) {
         counted++; // devAnimalLosSkipCheck: the draws of one thread, the game thread's alone
      }
      if (this.mainCa != null) {
         return this.mainCa.next(bits); // the game's generator, its own value for this width
      }
      return this.main.nextInt() >>> (32 - bits);
   }

   private Random seeded() {
      int s = this.main.nextInt() ^ (int) System.nanoTime();
      return new CellularAutomatonRNG(new byte[]{(byte) (s >>> 24), (byte) (s >>> 16), (byte) (s >>> 8), (byte) s});
   }

   @Override
   public void setSeed(long seed) {
      if (this.main != null) {
         this.main.setSeed(seed); // Random's constructor calls this before main is set
      }
   }
}
