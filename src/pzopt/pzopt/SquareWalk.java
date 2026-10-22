package pzopt;

import java.util.Locale;
import zombie.characters.IsoPlayer;

/**
 * Harness scene {@code explore=square} (2026-10-21, the maintainer: the feature sweep "outside the Rosewood church near the
 * main entrance on the east with the character walking in a square and rotating on each corner"): the player walks a
 * square on foot with the game's own "walk to" (pzopt.Nav, never a teleport, never movement keys) and turns round once on
 * the spot at every corner, lap after lap, on a fixed clock: lap L's side k starts at {@code square_start + L *
 * square_period + k * square_period / 4} s after the route start, whatever the frame rate, so the walk repeats with the
 * period exactly. The built-in benchmark's feature sweep (pzopt.BenchSweep) sets the period to its window: every measured
 * window then sees the same part of the same lap, as the old still, turning camera saw the same views.
 *
 * <p>Flags: {@code square=x,y,side[,z]} (corner 0, the first side runs east, then south, west, north: clockwise on screen
 * north-up), {@code square_period} (s a lap, 6), {@code square_start} (s after the route start the first lap begins; until
 * then the player walks to corner 0 and waits there facing the first side, 0), {@code square_spin} (degrees turned at a
 * corner besides the quarter turn to the next side, 360), {@code square_laps} (laps, then the route ends; 0 = until the
 * route's own time ends). At a corner the turn takes the rest of the side's quarter of the lap (the walk there is ~1 s),
 * ending faced along the next side. {@code harness: square: ...} lines per corner (how early the corner was reached),
 * {@code harness: square walk: done} and the nav summary at the end (harness/navjudge.py).
 */
final class SquareWalk {
   private SquareWalk() {
   }

   private static boolean finished, atStart, selfTest;
   private static float x0, y0, side, period, startS, spinDeg;
   private static int z0, lapsMax, lap = -1, sideIdx = 3, late;
   private static long routeNs, lastLogNs;
   private static boolean walking, arrived;
   private static float arriveS, arriveHeading, minSlack = Float.MAX_VALUE, sumSlack;
   private static int corners;

   static boolean finished() {
      return finished;
   }

   static void worldReady(IsoPlayer p) {
      String[] a = HarnessFlags.get("square", "").split(",");
      if (a.length >= 3) {
         x0 = Float.parseFloat(a[0].trim());
         y0 = Float.parseFloat(a[1].trim());
         side = Float.parseFloat(a[2].trim());
         z0 = a.length > 3 ? Integer.parseInt(a[3].trim()) : 0;
      } else { // round the square the player loaded on
         x0 = (float)Math.floor(p.getX()) + 0.5F;
         y0 = (float)Math.floor(p.getY()) + 0.5F;
         side = 2F;
         z0 = (int)p.getZ();
      }
      period = Float.parseFloat(HarnessFlags.get("square_period", "6").trim());
      startS = Float.parseFloat(HarnessFlags.get("square_start", "0").trim());
      spinDeg = Float.parseFloat(HarnessFlags.get("square_spin", "360").trim());
      lapsMax = Integer.parseInt(HarnessFlags.get("square_laps", "0").trim());
      selfTest = "true".equalsIgnoreCase(HarnessFlags.get("nav_selftest", "false").trim());
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      Log.info(String.format(Locale.ROOT, "harness: explore=square from %.2f,%.2f,%d, side %.1f, a lap every %.2f s from +%.2f s, %.0f deg turned at each corner, %s", x0, y0, z0, side,
            period, startS, spinDeg, lapsMax > 0 ? lapsMax + " laps" : "until the route ends"));
   }

   /** Corner k (0..3) of the square: clockwise on a north-up map (+x east, +y south). */
   private static float cx(int k) {
      return x0 + (k == 1 || k == 2 ? side : 0F);
   }

   private static float cy(int k) {
      return y0 + (k == 2 || k == 3 ? side : 0F);
   }

   /** The heading (degrees, 0 = east, 90 = south) of side k, from corner k to k+1. */
   private static float heading(int k) {
      return 90F * (k % 4);
   }

   static void routeStart(IsoPlayer p) {
      routeNs = System.nanoTime();
      boolean[] ok = new boolean[4];
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < 4; k++) {
         ok[k] = Nav.canStandClear(cx(k), cy(k), z0) && Nav.lineClear(cx(k), cy(k), cx((k + 1) % 4), cy((k + 1) % 4), z0);
         sb.append(String.format(Locale.ROOT, " %.1f,%.1f%s", cx(k), cy(k), ok[k] ? "" : " (BLOCKED)"));
      }
      Log.info("harness: square: corners" + sb + ", player at " + String.format(Locale.ROOT, "%.2f,%.2f,%.1f", p.getX(), p.getY(), p.getZ()));
      if (!selfTest) toStart(p);
   }

   private static void toStart(IsoPlayer p) {
      atStart = true;
      if (Math.hypot(p.getX() - cx(0), p.getY() - cy(0)) > 0.2F) {
         Nav.go(p, cx(0), cy(0), z0, "square corner 0 (start)");
         walking = true;
      } else {
         arrived = true;
      }
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (finished) return;
      if (selfTest && Nav.selfTest(p, nowNs)) return;
      if (selfTest && !atStart && Nav.status() == Nav.IDLE) toStart(p);
      Showcase.releaseKeys(); // no movement key may be down: it would cancel the game's walk
      Nav.frame(p, nowNs);
      float t = (nowNs - routeNs) / 1e9F;
      if (walking) {
         int s = Nav.status();
         if (s == Nav.ARRIVED || s == Nav.FAILED) {
            walking = false;
            arrived = true;
            arriveS = t;
            arriveHeading = p.getDirectionAngle();
            if (lap >= 0) {
               float slotEnd = startS + lap * period + (sideIdx + 1) * period / 4F;
               float slack = slotEnd - t;
               corners++;
               sumSlack += slack;
               minSlack = Math.min(minSlack, slack);
               Log.info(String.format(Locale.ROOT, "harness: square: lap %d corner %d %s at +%.2f s, %.2f s before the next side, pos %.2f,%.2f epoch_ms=%d", lap, (sideIdx + 1) % 4,
                     s == Nav.ARRIVED ? "reached" : "NOT REACHED", t, slack, p.getX(), p.getY(), System.currentTimeMillis()));
            }
         }
      }
      // the clock: which side of which lap is due now
      if (t >= startS) {
         int dueLap = (int)Math.floor((t - startS) / period);
         int dueSide = (int)Math.floor(((t - startS) - dueLap * period) / (period / 4F));
         dueSide = Math.max(0, Math.min(3, dueSide));
         if (lapsMax > 0 && dueLap >= lapsMax) {
            finish(nowNs);
            return;
         }
         if (dueLap > lap || dueLap == lap && dueSide > sideIdx) {
            if (walking) {
               late++; // the last side was not walked in its quarter: start the next one from where the player is
               Log.info(String.format(Locale.ROOT, "harness: square: lap %d side %d still walking at its successor's time, +%.2f s", lap, sideIdx, t));
            }
            lap = dueLap;
            sideIdx = dueSide;
            int to = (sideIdx + 1) % 4;
            Nav.go(p, cx(to), cy(to), z0, "square lap " + lap + " side " + sideIdx);
            walking = true;
            arrived = false;
         }
      }
      if (arrived && !walking) {
         if (lap < 0) {
            Nav.face(p, heading(0)); // waiting at corner 0 for the first lap, faced along the first side
         } else {
            // the corner turn: the rest of the side's quarter, a full turn (square_spin) plus the quarter turn to the next side
            float slotEnd = startS + lap * period + (sideIdx + 1) * period / 4F;
            float span = Math.max(0.05F, slotEnd - arriveS - 0.05F);
            float f = Math.min(1F, (t - arriveS) / span);
            float from = heading(sideIdx);
            float total = spinDeg + 90F;
            Nav.face(p, from + total * f);
         }
      }
      if (nowNs - lastLogNs >= 5_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(Locale.ROOT, "harness: square walk: t=%.1fs lap %d side %d pos=%.2f,%.2f dir=%.0f", t, lap, sideIdx, p.getX(), p.getY(), p.getDirectionAngle()));
      }
   }

   private static void finish(long nowNs) {
      finished = true;
      Nav.cancel();
      Log.info(String.format(Locale.ROOT, "harness: square walk: done at +%.2f s, %d laps, %d corners, slack before the next side min %.2f mean %.2f s, %d sides late epoch_ms=%d",
            (nowNs - routeNs) / 1e9F, lapsMax, corners, corners > 0 ? minSlack : 0F, corners > 0 ? sumSlack / corners : 0F, late, System.currentTimeMillis()));
      Log.info("harness: nav summary: " + Nav.summary());
   }
}
