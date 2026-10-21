package pzopt;

/**
 * Hand-off census (f8-handoff 2026-10-14, instrumented runs): IsoChunk.doLoadGridsquare's game-thread time by part, summed and
 * printed with the "chunk hand-off:" console line (pzopt.ChunkHandoff), so a run without the profiler says what a chunk's
 * arrival costs. Parts: 0 loadInMainThread (the border recalc), 1 vehicles / zone stories / ranch animals / grass / horde
 * survivor, 2 update (vehicle stories) / ragdolls / corpse count / flies / walls / corpses and blood, 3 vehicles added to the
 * world, 4 the square loop (objects, erosion, map objects, rats, LoadGridsquare events), 5 erosion chunk / global objects /
 * reanimated players, 6 collision / populations / pathfinding / generators / loot respawn, 7 room lights / randomized
 * buildings / visibility polygon, 8 loot fill, 9 adjacent chunks / LoadChunk event.
 */
public final class HandoffParts {
   private HandoffParts() {
   }

   public static final String[] NAMES = {"recalc", "stories", "update", "vehicles", "squares", "erosion", "pathfind", "buildings", "loot", "rest"};
   public static final long[] NS = new long[10];
   public static final long[] MAX = new long[10];
   public static final long[] CUR = new long[10]; // devHandoffLog: this chunk's parts
   private static long last;

   public static void start() {
      if (Config.INSTRUMENT) {
         last = System.nanoTime();
         java.util.Arrays.fill(CUR, 0L);
      }
   }

   public static void mark(int part) {
      if (!Config.INSTRUMENT) {
         return;
      }
      long now = System.nanoTime();
      long d = now - last;
      NS[part] += d;
      CUR[part] = d;
      if (d > MAX[part]) {
         MAX[part] = d;
      }
      last = now;
   }

   /** The census since the last call (then cleared): "part sum/max us". */
   public static String drain() {
      StringBuilder b = new StringBuilder("parts us (sum/max)");
      for (int i = 0; i < NS.length; i++) {
         b.append(' ').append(NAMES[i]).append(' ').append(NS[i] / 1000).append('/').append(MAX[i] / 1000);
         NS[i] = 0L;
         MAX[i] = 0L;
      }
      return b.toString();
   }
}
