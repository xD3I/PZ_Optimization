package pzopt;

import java.util.Set;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;

/**
 * {@code animalLosWalk} (tail pass 2026-10-13): the exact, cheaper form of {@code IsoAnimal.updateLOS}'s walk over the
 * cell's moving objects (5 % of the game thread on the Louisville horde: every animal walks ~2,600 entries a frame).
 *
 * <p>The walk ({@code IsoAnimal.pzoptUpdateLosWalk}) visits the same entries in the same order as stock's HashSet
 * iteration, from an array of the set's entries that is rebuilt whenever the set changed ({@link CellObjectSet}'s
 * {@code (version, size)}), with every value it tests read live as stock reads it. What makes it cheaper: an entry's class
 * is classified once per build instead of a chain of instanceof tests per animal, and a zombie's spotted() call is skipped
 * when it provably changes nothing. With animalLosFast's fold a zombie farther than 10 squares (or any zombie for an
 * animal that does not flee zombies) only clears {@code spottedChr} and ticks {@code lastAlerted} toward 0; when
 * {@code spottedChr} is already null and {@code lastAlerted} is exactly 0 (the animal is idle) that tick is a no-op, so an
 * idle animal reads only the x / y of each zombie and tests the stock filters only within 10 squares. Ticks while the
 * animal is not idle are applied one by one where stock's fold would apply them (the same float steps).
 *
 * <p>{@code devAnimalLosCheck}: before each walk the stock walk's events (ticks, calls with their distance, the animal's own
 * entry) are listed from the live set without side effects; the walk records the state before and after each call; the
 * events are then replayed on the recorded states and every call, every state and the final state must agree (counts in
 * the {@code animal los walk} log line).
 */
public final class AnimalLosWalk {
   private AnimalLosWalk() {
   }

   public static final byte OTHER = 0, ZOMBIE = 1, PLAYER = 2, ANIMAL = 3;

   public static int n;
   public static byte[] kind = new byte[4096];
   public static IsoMovingObject[] obj = new IsoMovingObject[4096];
   private static CellObjectSet built;
   private static int builtVersion = -1, builtSize = -1;
   public static long builds, walks, skippedFar, calls, checks, checkMismatches;
   private static long logNs;

   public static boolean enabled() {
      return Config.ANIMAL_LOS_WALK && Overrides.enabled() && GtAb.on(GtAb.ANIMAL_WALK) && Thread.currentThread() == GameWindow.gameThread;
   }

   /** The array of the set's entries in iteration order, rebuilt when the set changed. */
   public static void ensure(CellObjectSet set) {
      walks++;
      log();
      int size = set.size();
      if (set == built && set.version == builtVersion && size == builtSize) {
         return;
      }
      builds++;
      if (obj.length < size) {
         int cap = Integer.highestOneBit(size) << 1;
         obj = new IsoMovingObject[cap];
         kind = new byte[cap];
      }
      int k = 0;
      for (IsoMovingObject o : set) {
         byte c = OTHER;
         if (o instanceof IsoZombie) {
            c = ZOMBIE;
         } else if (o instanceof IsoAnimal) {
            c = ANIMAL;
         } else if (o instanceof IsoPlayer) {
            c = PLAYER;
         }
         kind[k] = c;
         obj[k++] = o;
      }
      for (int i = k; i < n; i++) {
         obj[i] = null;
      }
      n = k;
      built = set;
      builtVersion = set.version;
      builtSize = size;
   }

   /** An idle animal: a zombie's fold tick (spottedChr = null, lastAlerted toward 0) changes nothing. */
   public static boolean idle(IsoAnimal a, BaseAnimalBehavior b) {
      float la = b.lastAlerted;
      return a.spottedChr == null && !(la > 0.0F) && !(la < 0.0F);
   }

   /** One fold tick, as BaseAnimalBehavior.spotted's first lines (and IsoAnimal.pzoptSpottedTicks with n = 1). */
   public static void tick(IsoAnimal a, BaseAnimalBehavior b) {
      a.spottedChr = null;
      if (b.lastAlerted > 0.0F) {
         b.lastAlerted = b.lastAlerted - GameTime.getInstance().getMultiplier();
         if (b.lastAlerted < 0.0F) {
            b.lastAlerted = 0.0F;
         }
      }
   }

   // ---------------------------------------------------------------- devAnimalLosCheck
   private static final int EV_TICK = 0, EV_CALL = 1, EV_SELF = 2;
   private static int evN;
   private static int[] evKind = new int[8192];
   private static IsoMovingObject[] evObj = new IsoMovingObject[8192];
   private static float[] evDist = new float[8192];
   // the walk's calls: target, distance, state before and after
   private static int callN;
   private static IsoMovingObject[] cObj = new IsoMovingObject[256];
   private static float[] cDist = new float[256];
   private static IsoMovingObject[] cChrBefore = new IsoMovingObject[256], cChrAfter = new IsoMovingObject[256];
   private static float[] cLaBefore = new float[256], cLaAfter = new float[256];
   private static boolean checking;

   private static void ev(int k, IsoMovingObject o, float d) {
      if (evN == evKind.length) {
         evKind = java.util.Arrays.copyOf(evKind, evN * 2);
         evObj = java.util.Arrays.copyOf(evObj, evN * 2);
         evDist = java.util.Arrays.copyOf(evDist, evN * 2);
      }
      evKind[evN] = k;
      evObj[evN] = o;
      evDist[evN++] = d;
   }

   /** Lists the stock fold walk's events (IsoAnimal.updateLOS with animalLosFast) from the live set, without side effects. */
   public static void checkBegin(IsoAnimal self, Set<IsoMovingObject> set, boolean noFlee, float locX, float locY) {
      evN = 0;
      callN = 0;
      checking = true;
      for (IsoMovingObject o : set) {
         if (o instanceof IsoZombie z) {
            if (!z.isReanimatedForGrappleOnly() && !(PZMath.abs(z.getZ() - self.getZ()) > 1.0F) && z.getCurrentSquare() != null) {
               float d = noFlee ? 0.0F : IsoUtils.DistanceTo(z.getX(), z.getY(), locX, locY);
               if (noFlee || d > 10.0F) {
                  ev(EV_TICK, null, 0.0F);
               } else {
                  ev(EV_CALL, z, d);
               }
            }
            continue;
         }
         if (o instanceof IsoAnimal || !(o instanceof IsoPlayer p)) {
            if (o == self) {
               ev(EV_SELF, o, 0.0F);
            }
            continue;
         }
         if (PZMath.abs(o.getZ() - self.getZ()) > 1.0F) {
            continue;
         }
         float d = IsoUtils.DistanceTo(o.getX(), o.getY(), locX, locY);
         if (o.getCurrentSquare() != null && !p.isInvisible() && !p.isGhostMode()) {
            ev(EV_CALL, o, d);
         }
      }
   }

   /** The walk, right before a spotted() call. */
   public static void callBefore(IsoAnimal a, BaseAnimalBehavior b, IsoMovingObject o, float d) {
      if (!checking) {
         return;
      }
      if (callN == cObj.length) {
         int m = callN * 2;
         cObj = java.util.Arrays.copyOf(cObj, m);
         cDist = java.util.Arrays.copyOf(cDist, m);
         cChrBefore = java.util.Arrays.copyOf(cChrBefore, m);
         cChrAfter = java.util.Arrays.copyOf(cChrAfter, m);
         cLaBefore = java.util.Arrays.copyOf(cLaBefore, m);
         cLaAfter = java.util.Arrays.copyOf(cLaAfter, m);
      }
      cObj[callN] = o;
      cDist[callN] = d;
      cChrBefore[callN] = a.spottedChr;
      cLaBefore[callN] = b.lastAlerted;
   }

   /** The walk, right after a spotted() call. */
   public static void callAfter(IsoAnimal a, BaseAnimalBehavior b) {
      if (!checking) {
         return;
      }
      cChrAfter[callN] = a.spottedChr;
      cLaAfter[callN++] = b.lastAlerted;
   }

   /** Replays the stock events on the recorded states: every call and state must agree. */
   public static void checkEnd(IsoAnimal a, BaseAnimalBehavior b, IsoMovingObject chr0, float la0, boolean selfAdded) {
      checking = false;
      checks++;
      IsoMovingObject chr = chr0;
      float la = la0;
      int c = 0;
      boolean ok = true;
      boolean self = false;
      for (int i = 0; i < evN && ok; i++) {
         switch (evKind[i]) {
            case EV_TICK -> {
               chr = null;
               if (la > 0.0F) {
                  la = la - GameTime.getInstance().getMultiplier();
                  if (la < 0.0F) {
                     la = 0.0F;
                  }
               }
            }
            case EV_SELF -> self = true;
            default -> {
               if (c >= callN || cObj[c] != evObj[i] || Float.floatToRawIntBits(cDist[c]) != Float.floatToRawIntBits(evDist[i])
                  || cChrBefore[c] != chr || Float.floatToRawIntBits(cLaBefore[c]) != Float.floatToRawIntBits(la)) {
                  ok = false;
               } else {
                  chr = cChrAfter[c];
                  la = cLaAfter[c];
                  c++;
               }
            }
         }
      }
      if (ok && (c != callN || chr != a.spottedChr || Float.floatToRawIntBits(la) != Float.floatToRawIntBits(b.lastAlerted) || self != selfAdded)) {
         ok = false;
      }
      if (!ok) {
         checkMismatches++;
         if (checkMismatches <= 5) {
            Log.warn("animal los walk check: mismatch (events " + evN + ", stock calls " + c + ", walk calls " + callN + ", spottedChr " + (chr == a.spottedChr) + ", lastAlerted " + la + " vs " + b.lastAlerted + ", self " + self + "/" + selfAdded + ")");
         }
      }
      java.util.Arrays.fill(evObj, 0, evN, null);
      java.util.Arrays.fill(cObj, 0, callN, null);
      java.util.Arrays.fill(cChrBefore, 0, callN, null);
      java.util.Arrays.fill(cChrAfter, 0, callN, null);
   }

   private static void log() {
      long now = System.nanoTime();
      if (now - logNs < 10_000_000_000L) {
         return;
      }
      logNs = now;
      Log.info("animal los walk: " + walks + " walks, " + builds + " array builds (" + n + " entries), " + calls + " spotted calls, "
         + skippedFar + " far zombies skipped while idle" + (Config.DEV_ANIMAL_LOS_CHECK ? ", check " + checks + " walks, " + checkMismatches + " mismatches" : ""));
   }
}
