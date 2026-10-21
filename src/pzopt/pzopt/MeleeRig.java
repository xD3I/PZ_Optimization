package pzopt;

import java.util.ArrayList;
import java.util.Locale;

import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.skills.PerkFactory;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;

/**
 * Harness rig {@code melee=N} (2026-10-18, the zombie "phasing" reports: a zombie flashes as a naked stand-in figure when
 * the player hits it with a melee weapon, kills it, drags its corpse). {@code melee_at} s (default 3) after the first tick
 * the player becomes visible with {@code melee_weapon} (Base.Axe; its skill at {@code melee_skill}, 3) and N (at most 3)
 * zombies stand still in a row one square east of the player, each on its own slot ({@code setUseless}: no walking or
 * attacking; snapped back to its slot when the crowd pushes it more than 0.35 tiles, except while it lies on the floor). The
 * player aims at the nearest live one (the showcase's mouse override turns them), walks up to it with the movement keys until
 * it is within {@code melee_reach} (0.85 tiles), and every {@code melee_swing_ms} (1100)
 * swings through the game's own entry, {@code IsoPlayer.AttemptAttack} (what the attack input calls while aiming), so every
 * hit, knock-down, item knocked off and kill takes the stock path. A killed zombie's slot gets a new zombie after
 * {@code melee_respawn_ms} (1500); the corpses stay. The route keeps the player in place ({@link #active()}).
 *
 * <p>Console: {@code harness: melee hit t=<epoch ms> ...} on every health drop of a target, {@code harness: melee kill t=...}
 * on a death, a line a second with the totals; {@code melee=} in pzopt-bench.out. phasing-judge.py matches its flash events
 * against the hit / kill times.
 */
final class MeleeRig {
   private static int zombies = -1; // -1 = flags not read yet, 0 = off
   private static long atNs, swingNs, respawnNs, lastLogNs, lastSpawnNs, startNs;
   private static boolean started;
   private static String weaponType;
   private static int skill;
   private static HandWeapon weapon;
   private static final ArrayList<IsoZombie> spawned = new ArrayList<>();
   private static final ArrayList<Float> lastHealth = new ArrayList<>();
   private static final ArrayList<int[]> home = new ArrayList<>(); // slot x, y of each spawned zombie
   private static IsoZombie[] slot;
   private static long[] slotFreeNs;
   private static int px0, py0, pz0;
   private static float reach;
   private static long approachFrames;
   private static long lastSwingNs;
   private static final ArrayList<Boolean> reacting = new ArrayList<>(); // hit reaction playing last tick
   private static final ArrayList<Long> standinSince = new ArrayList<>(); // epoch ms the stand-in episode began, 0 = none
   private static final ArrayList<Integer> standinFrames = new ArrayList<>();
   private static int standinEpisodes, standinTotalFrames;
   private static int hits, kills, spawnedTotal, swings, snaps, strangers;

   private MeleeRig() {
   }

   /** The route must not move the player. */
   static boolean active() {
      if (zombies < 0) {
         return Integer.parseInt(HarnessFlags.get("melee", "0").trim()) > 0;
      }
      return zombies > 0;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (zombies == 0) {
         return;
      }
      if (zombies < 0) {
         zombies = Integer.parseInt(HarnessFlags.get("melee", "0").trim());
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("melee_at", "3")) * 1e9);
         swingNs = (long)(Float.parseFloat(HarnessFlags.get("melee_swing_ms", "1100")) * 1e6);
         respawnNs = (long)(Float.parseFloat(HarnessFlags.get("melee_respawn_ms", "1500")) * 1e6);
         weaponType = HarnessFlags.get("melee_weapon", "Base.Axe").trim();
         skill = Integer.parseInt(HarnessFlags.get("melee_skill", "3").trim());
         reach = Float.parseFloat(HarnessFlags.get("melee_reach", "0.85").trim());
         return;
      }
      if (!started) {
         if (nowNs >= atNs) {
            start(p, nowNs);
         }
         return;
      }
      try {
         track(nowNs);
         standins();
         pin();
         for (int i = 0; i < slot.length; i++) {
            if ((slot[i] == null || slot[i].isDead()) && nowNs >= slotFreeNs[i]) {
               spawnAt(i);
            }
         }
         swing(p, nowNs);
      } catch (Throwable t) {
         Log.warn("harness: melee: " + t);
      }
      if (nowNs - lastLogNs >= 1_000_000_000L) {
         lastLogNs = nowNs;
         clearStrangers(p);
         Log.info(String.format(Locale.ROOT, "harness: melee t=%.0f s: %d live, %d hits, %d kills, %d spawned, %d swings, %d snaps, %d strangers removed",
               (nowNs - startNs) / 1e9, live(), hits, kills, spawnedTotal, swings, snaps, strangers));
      }
   }

   private static void start(IsoPlayer p, long nowNs) {
      started = true;
      startNs = lastLogNs = lastSpawnNs = nowNs;
      try {
         p.setInvisible(false, true);
         p.setGhostMode(false, true);
         zombie.SystemDisabler.zombiesDontAttack = true;
         p.setPrimaryHandItem(null);
         p.setSecondaryHandItem(null);
         InventoryItem item = p.getInventory().AddItem(weaponType);
         if (!(item instanceof HandWeapon w)) {
            Log.warn("harness: melee: " + weaponType + " is not a weapon: " + item);
            zombies = 0;
            return;
         }
         weapon = w;
         p.setPrimaryHandItem(w);
         if (w.isTwoHandWeapon() || w.isRequiresEquippedBothHands()) {
            p.setSecondaryHandItem(w);
         }
         for (PerkFactory.Perk perk : new PerkFactory.Perk[] {PerkFactory.Perks.Axe, PerkFactory.Perks.Blunt, PerkFactory.Perks.SmallBlunt,
               PerkFactory.Perks.LongBlade, PerkFactory.Perks.SmallBlade, PerkFactory.Perks.Spear}) {
            p.setPerkLevelDebug(perk, skill);
         }
         clearStrangers(p);
         zombies = Math.min(zombies, 3);
         px0 = p.getXi();
         py0 = p.getYi();
         pz0 = (int)p.getZ();
         slot = new IsoZombie[zombies];
         slotFreeNs = new long[zombies];
         for (int i = 0; i < zombies; i++) {
            spawnAt(i);
         }
         Log.info("harness: melee: " + weaponType + " (skill " + skill + "), " + spawned.size() + " standing zombies next to " + p.getXi() + "," + p.getYi()
               + ", a swing every " + swingNs / 1e6 + " ms, zombieFirstSightSprite=" + Config.ZOMBIE_FIRST_SIGHT_SPRITE);
      } catch (Throwable t) {
         Log.warn("harness: melee: setup failed: " + t);
         zombies = 0;
      }
   }

   private static int live() {
      int n = 0;
      for (IsoZombie z : slot) {
         if (z != null && !z.isDead()) {
            n++;
         }
      }
      return n;
   }

   /** Slot i: one square east of the player, rows north / level / south of them (the middle first). */
   private static void spawnAt(int i) {
      int x = px0 + 1, y = py0 + (i == 0 ? 0 : i == 1 ? -1 : 1);
      ArrayList<IsoZombie> list = LuaManager.GlobalObject.addZombiesInOutfit(x, y, pz0, 1, null, 50);
      if (list == null || list.isEmpty()) {
         return;
      }
      IsoZombie z = list.get(0);
      z.setUseless(true);
      slot[i] = z;
      spawned.add(z);
      lastHealth.add(z.getHealth());
      reacting.add(false);
      standinSince.add(0L);
      standinFrames.add(0);
      home.add(new int[] {x, y});
      spawnedTotal++;
      Log.info("harness: melee spawn t=" + System.currentTimeMillis() + " zombie=" + (spawned.size() - 1) + " slot=" + i + " " + screen(z));
   }

   /** Keep every standing zombie on its slot: the crowd and the hit reactions push them into the player otherwise. */
   private static void pin() {
      for (int i = 0; i < spawned.size(); i++) {
         IsoZombie z = spawned.get(i);
         if (z.isDead() || z.isOnFloor() || z.getCurrentSquare() == null) {
            continue;
         }
         float hx = home.get(i)[0] + 0.5f, hy = home.get(i)[1] + 0.5f;
         float dx = z.getX() - hx, dy = z.getY() - hy;
         if (dx * dx + dy * dy > 0.35f * 0.35f) {
            z.setX(hx);
            z.setY(hy);
            z.setLastX(hx);
            z.setLastY(hy);
            z.setNextX(hx);
            z.setNextY(hy);
            snaps++;
         }
      }
   }

   /** The zombie's feet on the screen, in capture pixels (the world framebuffer is screen-sized). */
   private static String screen(IsoZombie z) {
      float zoom = zombie.core.Core.getInstance().getZoom(0);
      float sx = (zombie.iso.IsoUtils.XToScreen(z.getX(), z.getY(), z.getZ(), 0) - zombie.iso.IsoCamera.getOffX(0)) / zoom;
      float sy = (zombie.iso.IsoUtils.YToScreen(z.getX(), z.getY(), z.getZ(), 0) - zombie.iso.IsoCamera.getOffY(0)) / zoom;
      return String.format(Locale.ROOT, "sx=%d sy=%d zoom=%.2f", Math.round(sx), Math.round(sy), zoom);
   }

   /**
    * Every frame: whether the build draws a target as the first-sight stand-in sprite ({@link FirstSight#waiting} or
    * {@link FirstSight#newSprite}: the cull loop's tests for drawing the lit flat sprite). Episodes are logged with their start, length and the
    * zombie's screen place: on the release this is the "phasing"; off (zombieFirstSightSprite=false, stock) it never fires.
    */
   private static void standins() {
      for (int i = 0; i < spawned.size(); i++) {
         IsoZombie z = spawned.get(i);
         boolean on = FirstSight.ON && z.getCurrentSquare() != null && (FirstSight.waiting(z) || FirstSight.newSprite(z)); // waiting: its composite pending; newSprite: lit sprite under a new or fading model
         long since = standinSince.get(i);
         if (on && since == 0) {
            standinSince.set(i, System.currentTimeMillis());
            standinFrames.set(i, 1);
            Log.info("harness: melee standin on t=" + System.currentTimeMillis() + " zombie=" + i + " dead=" + z.isDead() + " floor=" + z.isOnFloor() + " " + screen(z));
         } else if (on) {
            standinFrames.set(i, standinFrames.get(i) + 1);
         } else if (since != 0) {
            standinEpisodes++;
            standinTotalFrames += standinFrames.get(i);
            Log.info("harness: melee standin off t=" + System.currentTimeMillis() + " zombie=" + i + " frames=" + standinFrames.get(i) + " ms=" + (System.currentTimeMillis() - since));
            standinSince.set(i, 0L);
         }
      }
   }

   /** The area's own zombies within 30 tiles (they crowd the player and take the swings): removed, as the showcase does. */
   private static void clearStrangers(IsoPlayer p) {
      for (IsoZombie z : new ArrayList<>(zombie.iso.IsoWorld.instance.getCell().getZombieList())) {
         if (spawned.contains(z) || z.isDead()) {
            continue;
         }
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
         if (dx * dx + dy * dy < 30f * 30f) {
            z.removeFromWorld();
            z.removeFromSquare();
            strangers++;
         }
      }
   }

   /** Health drops and deaths of the targets, with the epoch time (phasing-judge.py lines them up with the recording). */
   private static void track(long nowNs) {
      for (int i = 0; i < spawned.size(); i++) {
         IsoZombie z = spawned.get(i);
         float last = lastHealth.get(i);
         if (last < -1e8f) {
            continue; // already counted dead
         }
         float h = z.getHealth();
         if (z.isDead()) {
            kills++;
            lastHealth.set(i, -1e9f);
            for (int k = 0; k < slot.length; k++) {
               if (slot[k] == z) {
                  slotFreeNs[k] = nowNs + respawnNs;
               }
            }
            Log.info("harness: melee kill t=" + System.currentTimeMillis() + " zombie=" + i + " " + screen(z));
         } else {
            String r = z.getHitReaction();
            boolean react = r != null && !r.isEmpty();
            if (h < last - 1e-4f || react && !reacting.get(i)) { // the health drop, or a new hit reaction (a knock-down can cost no health)
               hits++;
               lastHealth.set(i, h);
               Log.info(String.format(Locale.ROOT, "harness: melee hit t=%d zombie=%d health %.2f -> %.2f reaction=%s %s", System.currentTimeMillis(), i, last, h, r, screen(z)));
            }
            reacting.set(i, react);
         }
      }
   }

   /** Aim at the nearest live target (the mouse override turns the player) and swing every period through IsoPlayer.AttemptAttack. */
   private static void swing(IsoPlayer p, long nowNs) {
      IsoZombie best = null;
      float bestD = Float.MAX_VALUE;
      for (IsoZombie z : slot) {
         if (z == null || z.isDead() || z.getCurrentSquare() == null) {
            continue;
         }
         float d = z.DistToSquared(p);
         if (d < bestD) {
            bestD = d;
            best = z;
         }
      }
      Showcase.releaseKeys();
      if (best == null || weapon == null) {
         Showcase.aimOverride = false;
         Showcase.holdButtons = false;
         Showcase.fireDown = false;
         return;
      }
      float zoom = zombie.core.Core.getInstance().getZoom(0);
      float sx = zombie.iso.IsoUtils.XToScreen(best.getX(), best.getY(), p.getZ(), 0) - zombie.iso.IsoCamera.getOffX(0);
      float sy = zombie.iso.IsoUtils.YToScreen(best.getX(), best.getY(), p.getZ(), 0) - zombie.iso.IsoCamera.getOffY(0);
      Showcase.aimXA = Math.round(sx / zoom);
      Showcase.aimYA = Math.round(sy / zoom);
      Showcase.aimOverride = true;
      Showcase.holdButtons = true; // right button held: aiming
      Showcase.fireDown = false;
      if (Math.sqrt(bestD) > reach) { // walk up to the target first (the slots are a square away, past the weapon's reach)
         if (!p.isAttackStarted()) {
            Showcase.moveKeys(best.getX() - p.getX(), best.getY() - p.getY());
            approachFrames++;
         }
         return;
      }
      if (nowNs - lastSwingNs >= swingNs && p.isAiming() && !p.isAttackStarted()) {
         lastSwingNs = nowNs;
         swings++;
         p.faceThisObject(best); // the aim's mouse turn did not reach the melee facing (rig v3 swung west of the targets)
         if (swings <= 12) {
            zombie.iso.Vector2 fwd = p.getForwardDirection();
            Log.info(String.format(Locale.ROOT, "harness: melee swing %d: player %.2f,%.2f fwd %.2f,%.2f target %.2f,%.2f dist %.2f aiming %b ready %b mouse %d,%d",
                  swings, p.getX(), p.getY(), fwd.x, fwd.y, best.getX(), best.getY(), Math.sqrt(bestD), p.isAiming(), p.isWeaponReady(), Showcase.aimXA, Showcase.aimYA));
         }
         p.AttemptAttack();
      }
   }

   static String summary() {
      if (zombies <= 0) {
         return "";
      }
      return String.format(Locale.ROOT, "\nmelee=weapon:%s skill:%d swings:%d hits:%d kills:%d spawned:%d snaps:%d strangers:%d approach_frames:%d standin_episodes:%d standin_frames:%d", weaponType, skill, swings, hits, kills, spawnedTotal, snaps, strangers, approachFrames, standinEpisodes, standinTotalFrames);
   }
}
