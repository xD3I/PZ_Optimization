package pzopt;

/**
 * Ground and grass (2026-10-24): the frame's switches of the ground features and the dev cost rig they share.
 *
 * <p><b>Switches.</b> {@link #on(long)} with a {@code K_*} bit: the feature's key after the master switches (Config's GATED
 * rules: {@code enhancementsEnabled}, {@code catGroundEnabled}) and after the dev alternation's off half. A feature asks
 * once a frame (in its {@link GroundBake.FloorPatch#active()}, its pass, its stamping); everything else in this package
 * follows those answers. {@link #macroPct()} is groundMacroPct the same way (0 in an off half).
 *
 * <p><b>Cost rig.</b> {@code -Dpzopt.devGroundAlternate=<ms>}: the keys in {@code devGroundAlternateKeys} (comma names or
 * "all") are off every second period, on first; the console says {@code ground: alternating every P ms from epoch_ms T (on
 * first), keys ...} and {@code harness/ground/ab.py <run>} pairs each on period with the off periods beside it (frame ms,
 * GPU ms, render / game thread). A bake-time key's switch re-bakes the natural ground on screen ({@code
 * devGroundAlternateRebake}, default on), so both halves show their own picture; the periods' first 15 % are skipped.
 * {@code devGroundCycle=a,b,c}: {@link #variant()} steps through the names (one per period, or one per on / off pair with
 * the alternation on) for a feature's in-run variant comparisons; the console says {@code ground: cycle a,b,c every P ms
 * from epoch_ms T}. The GtAb groups ({@code devGtAlternateKeys=groundScatter,...}) reach the same keys through ZombAb.
 * {@link #section(String)}: a GPU section name with the half ({@code .on} / {@code .off}) appended while alternating.
 */
public final class Ground {
   private Ground() {
   }

   public static final long K_HEX = ZombAb.GROUND_HEX, K_MACRO = ZombAb.GROUND_MACRO, K_HEIGHT = ZombAb.GROUND_HEIGHT, K_SCATTER = ZombAb.GROUND_SCATTER,
      K_GLINTS = ZombAb.GROUND_GLINTS, K_TRACKS = ZombAb.GROUND_TRACKS, K_BLADES = ZombAb.GROUND_BLADES;
   static final String[] KEY_NAMES = {"groundHexTiling", "groundMacroPct", "groundHeightBlend", "groundScatter", "glints", "groundTracks", "grassBlades"};
   static final long[] KEY_BITS = {K_HEX, K_MACRO, K_HEIGHT, K_SCATTER, K_GLINTS, K_TRACKS, K_BLADES};

   private static final long ALT_MASK = altMask(Config.DEV_GROUND_ALTERNATE_KEYS);
   private static final String[] CYCLE = Config.DEV_GROUND_CYCLE.isEmpty() ? null : Config.DEV_GROUND_CYCLE.split(",");
   private static long t0;
   private static volatile long frameMask; // the keys on this frame
   private static volatile boolean offHalf;
   private static volatile int cycleIx = -1;
   private static int lastPeriod = -1;
   private static long lastStats;

   private static long altMask(String keys) {
      if (keys == null || keys.trim().isEmpty() || keys.trim().equals("all")) {
         long m = 0L;
         for (long b : KEY_BITS) {
            m |= b;
         }
         return m;
      }
      long m = 0L;
      for (String k : keys.split(",")) {
         for (int i = 0; i < KEY_NAMES.length; i++) {
            if (KEY_NAMES[i].equals(k.trim())) {
               m |= KEY_BITS[i];
            }
         }
      }
      return m;
   }

   /** Is the ground key {@code bit} on this frame (any thread; the game thread's answer of this frame). */
   public static boolean on(long bit) {
      return (frameMask & bit) != 0L;
   }

   /** groundMacroPct this frame (0 when off or in an off half). */
   public static int macroPct() {
      return on(K_MACRO) ? Config.GROUND_MACRO_PCT : 0;
   }

   /** Any ground key on this frame. */
   public static boolean any() {
      return frameMask != 0L;
   }

   /** devGroundCycle's current name, or null without a cycle. */
   public static String variant() {
      int i = cycleIx;
      return CYCLE == null || i < 0 ? null : CYCLE[i].trim();
   }

   /** The alternation's off half is running (the listed keys read off). */
   public static boolean offHalf() {
      return offHalf;
   }

   /** A GPU section name, with ".on" / ".off" while the alternation runs. */
   public static String section(String name) {
      if (Config.DEV_GROUND_ALTERNATE <= 0) {
         return name;
      }
      String[] n = SECTIONS.computeIfAbsent(name, k -> new String[] {k + ".on", k + ".off"});
      return n[offHalf ? 1 : 0];
   }

   private static final java.util.concurrent.ConcurrentHashMap<String, String[]> SECTIONS = new java.util.concurrent.ConcurrentHashMap<>();

   private static long configMask() {
      long m = 0L;
      if (Config.GROUND_HEX_TILING) {
         m |= K_HEX;
      }
      if (Config.GROUND_MACRO_PCT > 0) {
         m |= K_MACRO;
      }
      if (Config.GROUND_HEIGHT_BLEND) {
         m |= K_HEIGHT;
      }
      if (Config.GROUND_SCATTER) {
         m |= K_SCATTER;
      }
      if (Config.GLINTS) {
         m |= K_GLINTS;
      }
      if (Config.GROUND_TRACKS) {
         m |= K_TRACKS;
      }
      if (Config.GRASS_BLADES) {
         m |= K_BLADES;
      }
      return m;
   }

   /**
    * The feature classes, loaded (static init: they register their patches, passes, rules) the first frame a ground key or
    * a ground dev rig is on; a name whose class does not exist is skipped. Each feature branch adds its own name.
    */
   static final String[] FEATURES = {"pzopt.GroundTilingPatch", "pzopt.GrassBlades"};
   private static boolean featuresLoaded;
   /** A ground dev rig runs (the shared parts then run every frame whatever the keys). */
   private static final boolean DEV_ANY = Config.DEV_GROUND_ALTERNATE > 0 || !Config.DEV_GROUND_CYCLE.isEmpty() || Config.DEV_GROUND_STATE_TEST
      || Config.DEV_GROUND_WALKERS > 0;

   private static void loadFeatures() {
      featuresLoaded = true;
      for (String n : FEATURES) {
         try {
            Class.forName(n, true, Ground.class.getClassLoader());
            Log.info("ground: feature " + n + " loaded");
         } catch (ClassNotFoundException e) {
            // (not on this branch)
         } catch (Throwable t) {
            Log.warn("ground: feature " + n + " failed to load and is off: " + t);
         }
      }
   }

   /** FBORenderCell.renderInternal, game thread, once a frame per player: the frame's switches, then the shared parts. */
   public static void frameStart(int playerIndex) {
      long m = configMask();
      if (!featuresLoaded && (m != 0L || DEV_ANY || Config.DEV_GROUND_VIEW != 0)) {
         loadFeatures();
      }
      if (m == 0L && !DEV_ANY && frameMask == 0L && !GroundBake.FLOOR_ON && !GroundBake.PASS_ON && !GroundState.active()) {
         // every key off: nothing below has work (the registered patches answer off from the same keys)
         if (GroundBake.anyRegistered()) {
            GroundBake.frameStart(playerIndex);
         }
         return;
      }
      int period = Config.DEV_GROUND_ALTERNATE > 0 ? Config.DEV_GROUND_ALTERNATE : 0;
      if (period > 0) {
         long now = System.currentTimeMillis();
         if (t0 == 0L) {
            t0 = now;
            Log.info("ground: alternating every " + period + " ms from epoch_ms " + t0 + " (on first), keys " + Config.DEV_GROUND_ALTERNATE_KEYS
               + (CYCLE != null ? " cycle " + Config.DEV_GROUND_CYCLE : ""));
         }
         int k = (int)((now - t0) / period);
         boolean off = (k & 1) == 1;
         offHalf = off;
         if (off) {
            m &= ~ALT_MASK;
         }
         if (CYCLE != null) {
            cycleIx = (k / 2) % CYCLE.length;
         }
         if (k != lastPeriod) {
            lastPeriod = k;
            if (Config.INSTRUMENT) {
               Log.info("ground: period " + k + (off ? " off" : " on") + (CYCLE != null ? " " + variant() : ""));
            }
         }
      } else if (CYCLE != null) {
         long now = System.currentTimeMillis();
         if (t0 == 0L) {
            t0 = now;
            Log.info("ground: cycle " + Config.DEV_GROUND_CYCLE + " every 3000 ms from epoch_ms " + t0);
         }
         cycleIx = (int)(((now - t0) / 3000L) % CYCLE.length);
      }
      // the GtAb groups (devGtAlternateKeys=groundScatter,...) through ZombAb's own bits
      for (long b : KEY_BITS) {
         if ((m & b) != 0L && ZombAb.listed(b) && !ZombAb.on(b)) {
            m &= ~b;
         }
      }
      frameMask = m;
      GroundBake.frameStart(playerIndex);
      GroundState.frameStart(playerIndex);
      if (Config.INSTRUMENT && playerIndex == 0) {
         long now = System.currentTimeMillis();
         if (now - lastStats > 10_000L) {
            lastStats = now;
            Log.info(GroundBake.stats());
            if (GroundState.active()) {
               Log.info(GroundState.stats());
            }
         }
      }
   }
}
