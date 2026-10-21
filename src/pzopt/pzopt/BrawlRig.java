package pzopt;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import zombie.Lua.LuaManager;
import zombie.SandboxOptions;
import zombie.WorldSoundManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * Harness rig {@code brawl=N} (2026-10-13, zombie game-thread pass): the zombie behaviour check of a simulation change
 * (entityUpdateParallel: the zombies' update on the workers). {@code brawl_at} s (default 3) after the first tick the player
 * becomes visible and attackable (zombies may attack, drag-down off, health restored every frame so the run goes on) and N
 * hostile zombies spawn on free outdoor squares {@code brawl_min}..{@code brawl_max} (4..30) tiles away; every
 * {@code brawl_noise} s (default 4, 0 = never) a world sound at the player ({@code brawl_noise_radius}, 40) calls the far
 * ones in. The route keeps the player in place ({@link #active()}).
 *
 * <p>A line every second ({@code harness: brawl t= ...}): alive zombies, how many target the player, mean / median
 * distance to the player, zombies within 1.5 / 4 / 12 tiles, the action states, the hits the player took this second and
 * the health they cost. {@code brawl=} in pzopt-bench.out: totals (hits, damage, seconds with an attack, kills, deaths) and
 * the run's state-seconds per action state. Compare key off / on runs of the same build ({@code harness/brawl.py}).
 */
final class BrawlRig {
   private static int zombies = -1; // -1 = flags not read yet, 0 = off
   private static long atNs, lastLogNs, spawnNs, lastNoiseNs, noiseNs;
   private static int noiseRadius;
   private static boolean started;
   private static final ArrayList<IsoZombie> spawned = new ArrayList<>();
   private static long hits, hitsAtLog, seconds, attackSeconds, framesWithHit;
   private static double damage, damageAtLog;
   private static int deaths, maxClose;
   private static final TreeMap<String, Long> stateSeconds = new TreeMap<>();
   private static final StringBuilder timeline = new StringBuilder();

   private BrawlRig() {
   }

   /** The route must not move the player. */
   static boolean active() {
      if (zombies < 0) {
         return Integer.parseInt(HarnessFlags.get("brawl", "0").trim()) > 0;
      }
      return zombies > 0;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (zombies == 0) {
         return;
      }
      if (zombies < 0) {
         zombies = Integer.parseInt(HarnessFlags.get("brawl", "0").trim());
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("brawl_at", "3")) * 1e9);
         noiseNs = (long)(Float.parseFloat(HarnessFlags.get("brawl_noise", "4")) * 1e9);
         noiseRadius = Integer.parseInt(HarnessFlags.get("brawl_noise_radius", "40").trim());
         return;
      }
      if (!started) {
         if (nowNs >= atNs) {
            start(p, nowNs);
         }
         return;
      }
      keepAlive(p);
      if (noiseNs > 0 && nowNs - lastNoiseNs >= noiseNs) {
         lastNoiseNs = nowNs;
         WorldSoundManager.instance.addSound(p, p.getXi(), p.getYi(), (int)p.getZ(), noiseRadius, noiseRadius);
      }
      if (nowNs - lastLogNs >= 1_000_000_000L) {
         lastLogNs = nowNs;
         log(p, nowNs);
      }
   }

   private static void start(IsoPlayer p, long nowNs) {
      started = true;
      try {
         p.setInvisible(false, true);
         p.setGhostMode(false, true);
         p.setGodMod(false, true); // the bench sets it; the hits must land (counted and healed in keepAlive)
         zombie.SystemDisabler.zombiesDontAttack = false;
         SandboxOptions.instance.lore.zombiesDragDown.setValue(false);
         IsoCell cell = IsoWorld.instance.currentCell;
         int px = p.getXi(), py = p.getYi();
         int rMin = Integer.parseInt(HarnessFlags.get("brawl_min", "4").trim()), rMax = Integer.parseInt(HarnessFlags.get("brawl_max", "30").trim());
         ArrayList<IsoGridSquare> ground = new ArrayList<>();
         for (int y = py - rMax; y <= py + rMax; y++) {
            for (int x = px - rMax; x <= px + rMax; x++) {
               int d2 = (x - px) * (x - px) + (y - py) * (y - py);
               IsoGridSquare g = cell.getGridSquare(x, y, 0);
               if (d2 >= rMin * rMin && d2 <= rMax * rMax && g != null && g.isOutside() && g.isFree(false) && !g.isWaterSquare()) {
                  ground.add(g);
               }
            }
         }
         java.util.Collections.shuffle(ground, new java.util.Random(42));
         for (int i = 0; i < ground.size() && spawned.size() < zombies; i++) {
            IsoGridSquare g = ground.get(i);
            ArrayList<IsoZombie> list = LuaManager.GlobalObject.addZombiesInOutfit(g.x, g.y, 0, 1, null, 50);
            if (list != null) {
               spawned.addAll(list);
            }
         }
         spawnNs = lastLogNs = lastNoiseNs = nowNs;
         WorldSoundManager.instance.addSound(p, px, py, (int)p.getZ(), noiseRadius, noiseRadius);
         Log.info("harness: brawl: " + spawned.size() + " zombies spawned " + rMin + ".." + rMax + " tiles round " + px + "," + py + " (" + ground.size()
               + " squares), noise every " + noiseNs / 1e9 + " s radius " + noiseRadius + ", entityUpdateParallel=" + Config.ENTITY_UPDATE_PARALLEL);
      } catch (Throwable t) {
         Log.warn("harness: brawl: setup failed: " + t);
         zombies = 0;
      }
   }

   /** Every frame: the health a hit took is counted and given back, so the player stands through the whole route. */
   private static void keepAlive(IsoPlayer p) {
      if (p.isDead()) {
         deaths++;
         return;
      }
      BodyDamage bd = p.getBodyDamage();
      if (bd == null) {
         return;
      }
      float h = bd.getOverallBodyHealth();
      if (h < 99.999f) {
         hits++;
         damage += 100.0 - h;
         framesWithHit++;
         bd.RestoreToFullHealth();
      }
   }

   private static void log(IsoPlayer p, long nowNs) {
      int alive = 0, targeting = 0, near15 = 0, near4 = 0, near12 = 0, dead = 0;
      ArrayList<Float> d = new ArrayList<>();
      TreeMap<String, Integer> states = new TreeMap<>();
      for (IsoZombie z : spawned) {
         if (z.isDead()) {
            dead++;
            continue;
         }
         if (z.getCurrentSquare() == null) {
            continue;
         }
         alive++;
         if (z.getTarget() == p) {
            targeting++;
         }
         float dist = (float)Math.sqrt(z.DistToSquared(p));
         d.add(dist);
         if (dist < 1.5f) near15++;
         if (dist < 4f) near4++;
         if (dist < 12f) near12++;
         String s = z.getActionStateName();
         states.merge(s == null ? "null" : s, 1, Integer::sum);
      }
      d.sort(null);
      double mean = d.isEmpty() ? 0 : d.stream().mapToDouble(Float::doubleValue).average().orElse(0);
      double median = d.isEmpty() ? 0 : d.get(d.size() / 2);
      long h = hits - hitsAtLog;
      double dmg = damage - damageAtLog;
      hitsAtLog = hits;
      damageAtLog = damage;
      seconds++;
      if (h > 0) attackSeconds++;
      maxClose = Math.max(maxClose, near15);
      StringBuilder st = new StringBuilder();
      for (Map.Entry<String, Integer> e : states.entrySet()) {
         st.append(st.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
         stateSeconds.merge(e.getKey(), (long)e.getValue(), Long::sum);
      }
      float t = (nowNs - spawnNs) / 1e9f;
      timeline.append(String.format(Locale.ROOT, "%s%.0f:%.1f/%d/%d", timeline.length() == 0 ? "" : ";", t, mean, near4, h));
      Log.info(String.format(Locale.ROOT, "harness: brawl t=%.0f alive=%d dead=%d target=%d dist_mean=%.2f dist_median=%.2f within1.5=%d within4=%d within12=%d hits=%d damage=%.1f states=%s",
            t, alive, dead, targeting, mean, median, near15, near4, near12, h, dmg, st));
   }

   /** Line for pzopt-bench.out ("" when the rig is off). */
   static String summary() {
      if (!started) {
         return "";
      }
      int dead = 0;
      for (IsoZombie z : spawned) {
         if (z.isDead()) dead++;
      }
      StringBuilder st = new StringBuilder();
      for (Map.Entry<String, Long> e : stateSeconds.entrySet()) {
         st.append(st.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
      }
      return String.format(Locale.ROOT, "\nbrawl=zombies:%d seconds:%d hits:%d frames_hit:%d damage:%.1f attack_seconds:%d max_within1.5:%d zombies_dead:%d player_deaths:%d state_seconds:%s timeline:%s",
            spawned.size(), seconds, hits, framesWithHit, damage, attackSeconds, maxClose, dead, deaths, st, timeline);
   }
}
