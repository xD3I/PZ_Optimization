package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.BodyDamage.BodyPart;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * The horde shooting bench fought like a player (2026-10-21, the maintainer: "the character should move and react to
 * zombies getting close the same way a player would, shooting when the horde is far, circling the horde, reloading and
 * moving away ... move without getting stuck ... without the character taking any zombie damage"). Flag
 * {@code tactics=kite} on top of {@code showcase=horde horde_when=ready}: the zombies attack (no {@code no_attack}, no god
 * mode once the horde is out), nothing holds them off or pins the player (no {@code bubble}, no {@code pin_pier}), and the
 * player keeps the horde at range with the game's own input: stand and shoot while it is far, run round it when it gets
 * near ({@code kite}), reload where there is room (walking away while the action runs: the game's reload stops on a run,
 * not on a walk), draw the revolver when the rifle runs dry with the horde close.
 *
 * <p>Who decides: the director (Jev, harness/showcase-director.py, {@code director=jev}) or, without one (the in-game
 * BENCHMARK), {@link #autopilot}, the same rules in code. Whatever is chosen, {@link #reflex} runs the kite while a zombie
 * stands within {@code reflex_tiles} (3.5) and the action is not a kite: the player's own flinch, counted
 * ({@code reflexes}) so a run shows how often the director was late.
 *
 * <p>Moving: the movement keys (8 directions) plus Run, steered every 50 ms ({@link #steer}): each direction is walked 3
 * tiles ahead through the pathfinder's own body tests (PolygonalMap2.canStandAt / lineClearCollide) plus water, trees
 * and bushes, kept inside the arena ({@code arena=x,y,r}), and scored by the clearance it keeps from every zombie along
 * the way and at its end (the zombies stepped toward the player meanwhile), a preference for running round the horde
 * (tangent to the horde's centre, one sense kept) and for the direction already run. A move that makes under 0.5 tiles
 * of progress in 2 s is a stuck episode ({@code stuck}).
 *
 * <p>Measured from the game every frame: zombie hits on the player (entries into PlayerHitReactionState and new
 * attackers in IsoGameCharacter.attackedBy, which AttackState sets before it rolls the damage), wounded body parts
 * (scratch, bite, laceration, deep wound, bleeding), the body's health, zombie attacks begun at the player, bumps;
 * the inventory weight against the capacity and the Heavy Load moodle. After a hit with the health under 60 the god mode
 * comes back on so a player's benchmark pass never dies ({@code safety_god}; the pass has failed by then).
 * {@code harness: horde:} lines, {@link #fields} into pzopt-bench.out / pzopt-horde.out (Showcase.summary) and the
 * benchmark's step file.
 *
 * <p>{@code arena_scan=x,y[/x,y...]}: no scene; at the route start the player is moved to each point in turn, 5 s each
 * (the chunks load), and the squares within 45 tiles are classified and the widest clear disc found; the maps go to
 * pzopt-arena.out (how the bench's arena was chosen).
 */
final class HordeTactics {
   private HordeTactics() {
   }

   static boolean on;
   static float kiteAt, shootAt, reflexAt, reloadRoom, arenaX, arenaY, arenaR;
   static boolean arenaSet;

   // ---- facts, every frame ----
   static float nearest = 99F, nearestAny = 99F, centX, centY, zombieSpeed, playerSpeed;
   static int within3, within6, within10, threats;
   static boolean kiteRoom = true;

   // ---- damage ----
   static int hits, stateHits, attackers, attacksBegun, bumps, woundsMax, wounds;
   static float healthStart = -1F, healthMin = 101F, closestEver = 99F;
   static boolean safetyGod;
   private static IsoGameCharacter lastAttackedBy;
   private static boolean inHitState, inBump;
   private static final Map<IsoZombie, Boolean> attacking = new IdentityHashMap<>();

   // ---- the reflex ----
   static int reflexes, reflexFrames;
   private static boolean reflexOn;

   // ---- moving / stuck ----
   static int stuck, kites, steerChanges, blockedFrames, noWayFrames;
   static float stuckLongest, kiteSecs, movedTiles;
   private static boolean wantMove, stuckNow;
   private static long refNs, lastSteerNs, kiteSinceNs, lastTickNs;
   private static float refX, refY, ux, uy, lastX, lastY;
   private static boolean dirValid;
   private static int sense;
   private static String moveWhy = "";

   // ---- speed sampling ----
   private static final Map<IsoZombie, float[]> zpos = new IdentityHashMap<>();
   private static long speedNs;
   private static float pSpeedX, pSpeedY;

   // ---- the kill switch (2026-10-21, the maintainer: end the pass at once on the first zombie damage) ----
   static boolean killSwitch = true;
   static volatile String failReason = "";
   static float failAt = -1F;

   // ---- encumbrance ----
   static float weightStart = -1F, capacity = -1F, weightMax;
   static int heavyMax, magsCarried, magsWanted;

   static void apply() {
      on = "kite".equalsIgnoreCase(HarnessFlags.get("tactics", "").trim());
      kiteAt = Float.parseFloat(HarnessFlags.get("kite_tiles", "6").trim());
      shootAt = Float.parseFloat(HarnessFlags.get("shoot_tiles", "9").trim());
      reflexAt = Float.parseFloat(HarnessFlags.get("reflex_tiles", "3.5").trim());
      reloadRoom = Float.parseFloat(HarnessFlags.get("reload_room", "9").trim());
      killSwitch = !"false".equalsIgnoreCase(HarnessFlags.get("kill_switch", "true").trim());
      fewZombies = Integer.parseInt(HarnessFlags.get("few_zombies", "10").trim());
      closeInAt = Float.parseFloat(HarnessFlags.get("close_in_tiles", "12").trim());
      blindTiles = Float.parseFloat(HarnessFlags.get("blind_tiles", "12").trim());
      roomWeight = Float.parseFloat(HarnessFlags.get("room_weight", "6").trim());
      releaseTiles = Float.parseFloat(HarnessFlags.get("horde_release_tiles", "15").trim());
      closeInLeft = Integer.parseInt(HarnessFlags.get("close_in_left", "20").trim());
      hiddenRounds = Integer.parseInt(HarnessFlags.get("hidden_rounds", "3").trim());
      closeInRun = !"false".equalsIgnoreCase(HarnessFlags.get("close_in_run", "true").trim());
      crowdZombies = Integer.parseInt(HarnessFlags.get("crowd_zombies", "8").trim());
      crowdKite = Float.parseFloat(HarnessFlags.get("crowd_kite_tiles", "4.5").trim());
      packWanted = on && "true".equalsIgnoreCase(HarnessFlags.get("pack", "true").trim());
      backpedalOn = !"false".equalsIgnoreCase(HarnessFlags.get("backpedal", "true").trim());
      backpedalMargin = Float.parseFloat(HarnessFlags.get("backpedal_tiles", "2").trim());
      aimSettle = Float.parseFloat(HarnessFlags.get("aim_settle", "15").trim());
      moveSettle = Float.parseFloat(HarnessFlags.get("move_settle", "30").trim());
      String ng = HarnessFlags.get("nogo", "").trim();
      ArrayList<float[]> boxes = new ArrayList<>();
      for (String b : ng.isEmpty() ? new String[0] : ng.split("/")) {
         String[] v = b.split(",");
         boxes.add(new float[] {Float.parseFloat(v[0].trim()), Float.parseFloat(v[1].trim()), Float.parseFloat(v[2].trim()), Float.parseFloat(v[3].trim())});
      }
      nogo = boxes.toArray(new float[0][]);
      openTiles = Float.parseFloat(HarnessFlags.get("open_tiles", "0").trim());
      openAbort = Float.parseFloat(HarnessFlags.get("open_abort_tiles", "4").trim());
      floorClose = Float.parseFloat(HarnessFlags.get("floor_close_tiles", "3").trim());
      stragglers = Integer.parseInt(HarnessFlags.get("straggler_zombies", "2").trim());
      stragglerKite = Float.parseFloat(HarnessFlags.get("straggler_kite_tiles", "1.6").trim());
      stragglerShoot = Float.parseFloat(HarnessFlags.get("straggler_shoot_tiles", "3").trim());
      stragglerReflex = Float.parseFloat(HarnessFlags.get("straggler_reflex_tiles", "1.4").trim());
      // dev_horde_stand=true (or -Dpzopt.devHordeStand=true, which reaches the built-in benchmark's processes through
      // BENCH_VMARGS): the player never runs, so the horde reaches them: the kill switch / failed-pass test rig
      devStand = "true".equalsIgnoreCase(HarnessFlags.get("dev_horde_stand", System.getProperty("pzopt.devHordeStand", "false")).trim());
      if (on && devStand) Log.info("harness: horde: dev_horde_stand: the player stands and takes the horde (kill switch test)");
      String a = HarnessFlags.get("arena", "").trim();
      if (!a.isEmpty()) {
         String[] p = a.split(",");
         arenaX = Float.parseFloat(p[0].trim()) + 0.5F;
         arenaY = Float.parseFloat(p[1].trim()) + 0.5F;
         arenaR = p.length > 2 ? Float.parseFloat(p[2].trim()) : 25F;
         arenaSet = true;
      }
      scanApply();
      circuitApply();
      if (on) {
         Log.info(String.format(Locale.ROOT, "harness: horde: tactics=kite, kite under %.1f tiles, shoot over %.1f, reflex %.1f, reload room %.1f, arena %s",
               kiteAt, shootAt, reflexAt, reloadRoom, arenaSet ? String.format(Locale.ROOT, "%.1f,%.1f r %.0f", arenaX, arenaY, arenaR) : "around the spot"));
      }
   }

   /** Route start: whatever attacked the player before the scene is not counted. */
   static void routeStart(IsoPlayer p) {
      if (!on) return;
      lastAttackedBy = p.getAttackedBy();
      lastX = p.getX();
      lastY = p.getY();
      zombie.characters.Stats st = p.getStats();
      Log.info(String.format(Locale.ROOT, "harness: horde: the player at the start: health %.1f, hunger %.2f, thirst %.2f, sickness %.2f, infection %.2f, pain %.2f, wounded parts %d, traits %s",
            p.getBodyDamage().getOverallBodyHealth(), st.get(zombie.characters.CharacterStat.HUNGER), st.get(zombie.characters.CharacterStat.THIRST),
            st.get(zombie.characters.CharacterStat.SICKNESS), st.get(zombie.characters.CharacterStat.ZOMBIE_INFECTION), st.get(zombie.characters.CharacterStat.PAIN),
            p.getBodyDamage().getNumPartsScratched() + p.getBodyDamage().getNumPartsBitten(), p.getCharacterTraits() == null ? "-" : p.getCharacterTraits().toString()));
   }

   /**
    * Boots on a player without shoes: IsoPlayer.updateFootInjuries scratches a bare foot walking or running on outdoor
    * ground, and the built-in benchmark's new-world character starts without any (hk-bench1, 2026-10-21: "WOUNDED ...
    * scratched 1" at +5.0 s with the nearest zombie 8.3 tiles off; the bench save's character wears shoes).
    */
   static void boots(IsoPlayer p) {
      zombie.inventory.InventoryItem worn = p.getWornItems().getItem(zombie.scripting.objects.ItemBodyLocation.SHOES);
      if (worn != null && worn.getCondition() > 0) return;
      zombie.inventory.InventoryItem b = p.getInventory().AddItem("Base.Shoes_ArmyBoots");
      if (b == null || b.getBodyLocation() == null) {
         Log.warn("harness: horde: no shoes and no Base.Shoes_ArmyBoots to put on: bare feet get scratched outdoors");
         return;
      }
      p.setWornItem(b.getBodyLocation(), b);
      Log.info("harness: horde: the player had no shoes" + (worn != null ? " (a worn-out pair)" : "") + ": military boots put on");
   }

   static boolean desensitized;
   static float panicMax;

   /** buffs: what the character was given (the summary's horde_buffs) */
   static String buffsDone = "off";

   /**
    * buffs (default with tactics, the maintainer 2026-10-23: "check character traits, clothing items, and moodles/buffs to
    * optimize the run"): the game's own levers on a firearm fight, each read in the game's code:
    * - Marksman: +20 hit chance (CombatManager.calculateHitChanceData), the aim delay drains 10 % faster
    *   (IsoGameCharacter.updateAimingDelay), less wind penalty; Dextrous: the aim delay a new aim starts at x0.8
    *   (resetAimingDelay); Eagle Eyed: max sight range x1.2 (HandWeapon.getMaxSightRange); All Thumbs and Short Sighted go
    * - Sprinting 10: the run reaches the game's cap 1.0 (calculateWalkSpeed: 0.65 x gear + Sprinting / 20, ~0.64 at level 0)
    * - Nimble 10: the moving penalty to hit halves (getMovePenalty: x (1 - (Aiming + Nimble) / 40)), the aimed walk is faster
    * - an ammo strap + a chest rig: the magazine reload x1.15 (ISReloadWeaponAction.setReloadSpeed wants a worn ammo strap
    *   and a worn reloadfastmagazines item); with Sprinting 10 their 0.98 / 0.99 run modifiers stay under the cap
    */
   static void buffs(IsoPlayer p) {
      if (!"true".equalsIgnoreCase(HarnessFlags.get("buffs", "true").trim())) return;
      StringBuilder b = new StringBuilder();
      zombie.characters.traits.CharacterTraits tr = p.getCharacterTraits();
      if (tr != null) {
         for (zombie.scripting.objects.CharacterTrait t : new zombie.scripting.objects.CharacterTrait[] {zombie.scripting.objects.CharacterTrait.ALL_THUMBS,
               zombie.scripting.objects.CharacterTrait.SHORT_SIGHTED}) {
            if (tr.get(t)) {
               tr.remove(t);
               b.append(" -").append(t.getName());
            }
         }
         for (zombie.scripting.objects.CharacterTrait t : new zombie.scripting.objects.CharacterTrait[] {zombie.scripting.objects.CharacterTrait.MARKSMAN,
               zombie.scripting.objects.CharacterTrait.DEXTROUS, zombie.scripting.objects.CharacterTrait.EAGLE_EYED}) {
            if (!tr.get(t)) tr.add(t);
            b.append(" +").append(t.getName());
         }
      }
      // the glasses come off with Short Sighted gone (the maintainer, 2026-10-23: "it doesnt need them anymore"; the bench
      // save's character is Short Sighted and wears them): every worn visual aid (IsoGameCharacter.isWearingGlasses), kept
      // in the inventory
      if (tr == null || !tr.get(zombie.scripting.objects.CharacterTrait.SHORT_SIGHTED)) {
         java.util.List<zombie.inventory.InventoryItem> aids = new java.util.ArrayList<>();
         for (int i = 0; i < p.getWornItems().size(); i++) {
            zombie.inventory.InventoryItem it = p.getWornItems().get(i).getItem();
            if (it != null && it.isVisualAid()) aids.add(it);
         }
         for (zombie.inventory.InventoryItem it : aids) {
            Scene.unpinWorn(it); // else Scene.keepWornItems wore them again (h80-p47: wornRestored=1, blur=1.00 at the end)
            p.removeWornItem(it);
            b.append(" off ").append(it.getType());
         }
      }
      p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Sprinting, 10);
      p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Nimble, 10);
      b.append(" Sprinting ").append(p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Sprinting))
            .append(" Nimble ").append(p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Nimble));
      for (String type : new String[] {"Base.AmmoStrap_Bullets", "Base.Bag_ChestRig"}) {
         zombie.inventory.InventoryItem it = p.getInventory().AddItem(type);
         zombie.scripting.objects.ItemBodyLocation loc = it == null ? null : it.canBeEquipped() != null ? it.canBeEquipped() : it.getBodyLocation();
         if (loc == null) {
            b.append(" no ").append(type);
            continue;
         }
         p.setWornItem(loc, it);
         b.append(" worn ").append(type);
      }
      p.updateSpeedModifiers();
      // the game's vision blur (IsoGameCharacter.updateVisionEffects) is 1 for glasses without Short Sighted as much as for
      // Short Sighted without glasses, and is recomputed only on a clothing change: recomputed here after both changed
      p.updateVisionEffects();
      p.resetModelNextFrame();
      buffsDone = b.toString().trim();
      Log.info("harness: horde: buffs:" + b);
   }

   /** the highest level each moodle that touches the aim, the reload or the run reached */
   private static final zombie.scripting.objects.MoodleType[] WATCHED = {zombie.scripting.objects.MoodleType.PANIC,
         zombie.scripting.objects.MoodleType.STRESS, zombie.scripting.objects.MoodleType.TIRED, zombie.scripting.objects.MoodleType.ENDURANCE,
         zombie.scripting.objects.MoodleType.DRUNK, zombie.scripting.objects.MoodleType.PAIN, zombie.scripting.objects.MoodleType.HYPERTHERMIA,
         zombie.scripting.objects.MoodleType.UNCOMFORTABLE, zombie.scripting.objects.MoodleType.CANT_SPRINT};
   private static final int[] moodleMax = new int[WATCHED.length];

   /**
    * Desensitized (the maintainer, 2026-10-22: "make the character have the desensitized trait so it doesnt get any
    * penalties from panic"): the game's own trait keeps panic at 0 (Stats), so the aim's moodle penalty
    * (CombatManager.getMoodlesPenalty) and the reload's panic slowdown (ISReloadWeaponAction.setReloadSpeed) never come,
    * with no per-frame stat override. The traits it excludes go (Cowardly, Agoraphobic, Claustrophobic, Hemophobic).
    */
   static void desensitize(IsoPlayer p) {
      zombie.characters.traits.CharacterTraits tr = p.getCharacterTraits();
      if (tr == null) return;
      StringBuilder gone = new StringBuilder();
      for (zombie.scripting.objects.CharacterTrait t : new zombie.scripting.objects.CharacterTrait[] {zombie.scripting.objects.CharacterTrait.COWARDLY,
            zombie.scripting.objects.CharacterTrait.AGORAPHOBIC, zombie.scripting.objects.CharacterTrait.CLAUSTROPHOBIC, zombie.scripting.objects.CharacterTrait.HEMOPHOBIC}) {
         if (tr.get(t)) {
            tr.remove(t);
            gone.append(' ').append(t.getName());
         }
      }
      if (!tr.get(zombie.scripting.objects.CharacterTrait.DESENSITIZED)) tr.add(zombie.scripting.objects.CharacterTrait.DESENSITIZED);
      desensitized = tr.get(zombie.scripting.objects.CharacterTrait.DESENSITIZED);
      p.getStats().set(zombie.characters.CharacterStat.PANIC, 0F);
      Log.info("harness: horde: the character is Desensitized " + desensitized + (gone.length() > 0 ? " (removed:" + gone + ")" : ""));
   }

   // ---------------------------------------------------------------- readiness

   /**
    * The maintainer, 2026-10-21: "the zombies start attacking before the character is ready". Two causes, both the bench's:
    * (1) the zombies the bench sends at the player (spawn, re-path) get their target set without spotting it, so their
    * {@code vectorToTarget} stays (0, 0) until a spot; IsoZombie.getShouldAttack reads its length (0 &lt;= 0.72) and the
    * turn-alerted zombie goes into AttackState 10-13 tiles out (every "attack begun" at 10+ tiles of the field runs).
    * In single player stock sets the vector only in spotted(): the bench keeps it to the real one ({@link #aimAt}).
    * (2) the release: the horde's nearest came out ~11.5 tiles off (gap 16, a 7x7 blob), the old pier's gap 12 ~8 tiles,
    * and the release also fired after 3 s in place unaimed. Now the horde comes only when the player stands armed (the
    * rifle in the hands, a full magazine), kitted (boots, the pack), idle (no action queued, not moving, 0.5 s in place)
    * and aimed, and its nearest square is {@code horde_release_tiles} (15) out; the READY line and horde_ready_* say so.
    */
   static float releaseTiles = 15F, readyS = -1F, releaseNearest = -1F, firstAttackS = -1F, firstAttackTiles = -1F, firstContactS = -1F;
   static String readyWhat = "";

   /** The zombie's vector to the player, as spotted() would set it (the bench gave it the target without a spot). */
   static void aimAt(IsoZombie z, IsoPlayer p) {
      z.vectorToTarget.x = p.getX() - z.getX();
      z.vectorToTarget.y = p.getY() - z.getY();
   }

   /** Armed, loaded, kitted, idle: what the horde waits for (with tactics). Empty when ready, else what is missing. */
   static String notReady(IsoPlayer p, zombie.inventory.types.HandWeapon gun) {
      if (gun == null) return "no gun";
      if (p.getPrimaryHandItem() != gun) return "the rifle not in the hands";
      if (gun.getCurrentAmmoCount() < gun.getMaxAmmo()) return "magazine not full (" + gun.getCurrentAmmoCount() + ")";
      if (!p.getCharacterActions().isEmpty()) return "an action queued";
      if (p.isPlayerMoving()) return "moving";
      zombie.inventory.InventoryItem shoes = p.getWornItems().getItem(zombie.scripting.objects.ItemBodyLocation.SHOES);
      if (shoes == null || shoes.getCondition() <= 0) return "no shoes";
      if (packWanted && !packWorn(p)) return "the pack not worn";
      if (!p.isAiming()) return "not aiming";
      return "";
   }

   static boolean packWanted;

   private static boolean packWorn(IsoPlayer p) {
      zombie.characters.WornItems.WornItems w = p.getWornItems();
      for (int i = 0; i < w.size(); i++) {
         zombie.inventory.InventoryItem it = w.getItemByIndex(i);
         if (it != null && "Bag_ALICEpack".equals(it.getType())) return true;
      }
      return false;
   }

   /** The READY line: the moment the horde is released, what the player holds, the nearest zombie (any) then. */
   static void ready(IsoPlayer p, zombie.inventory.types.HandWeapon gun, float t, boolean ok, String missing) {
      readyS = ok ? t : -1F;
      readyWhat = ok ? "ready" : "NOT ready: " + missing;
      Log.info(String.format(Locale.ROOT, "harness: horde: %s at +%.1f s: weapon %s, %d rounds in%s, aiming %b, actions %d, moving %b, nearest zombie %.1f tiles; the horde is released",
            ok ? "READY" : "RELEASED NOT READY (" + missing + ")", t, gun == null ? "none" : gun.getType(), gun == null ? -1 : gun.getCurrentAmmoCount(),
            gun != null && gun.isRoundChambered() ? " + 1 chambered" : "", p.isAiming(), p.getCharacterActions().size(), p.isPlayerMoving(), nearestAny));
   }

   /** After the spawn: how far out the horde's nearest zombie came. */
   static void released(IsoPlayer p, ArrayList<IsoZombie> horde) {
      float m = 99F;
      for (IsoZombie z : horde) {
         m = Math.min(m, (float)Math.hypot(z.getX() - p.getX(), z.getY() - p.getY()));
         aimAt(z, p);
      }
      releaseNearest = m;
      releasedOn = true;
      Log.info(String.format(Locale.ROOT, "harness: horde: released: %d zombies, the nearest %.1f tiles out (release distance %.0f)", horde.size(), m, releaseTiles));
   }

   /** The arena when the flags name none: the spot itself, 25 tiles. */
   static void defaultArena(float x, float y) {
      if (arenaSet) return;
      arenaX = x;
      arenaY = y;
      arenaR = 25F;
      arenaSet = true;
   }

   // ---------------------------------------------------------------- encumbrance

   /**
    * A player going out to fight a horde travels light: everything the save's character carries that is not worn
    * clothing goes (bags and their contents, loose items, hand items); hk-fight1 (2026-10-21) started at 27.9 of 18
    * before a single magazine was packed, Heavy Load 3, and ran at half speed. The kit is added after this.
    */
   static void travelLight(IsoPlayer p) {
      float before = p.getInventory().getCapacityWeight();
      ArrayList<zombie.inventory.InventoryItem> items = new ArrayList<>(p.getInventory().getItems());
      int n = 0;
      float w = 0F;
      StringBuilder heavy = new StringBuilder();
      p.setPrimaryHandItem(null);
      p.setSecondaryHandItem(null);
      for (zombie.inventory.InventoryItem it : items) {
         boolean worn = p.isEquippedClothing(it);
         if (worn && it instanceof zombie.inventory.types.Clothing && !(it instanceof zombie.inventory.types.InventoryContainer)) continue;
         try {
            if (worn) p.removeWornItem(it, false);
            if (p.isAttachedItem(it)) p.removeAttachedItem(it);
            p.getInventory().Remove(it);
         } catch (RuntimeException e) {
            Log.warn("harness: horde: could not drop " + it.getFullType() + ": " + e);
            continue;
         }
         n++;
         float iw = it.getUnequippedWeight();
         w += iw;
         if (iw >= 1F && heavy.length() < 300) heavy.append(heavy.length() == 0 ? "" : ", ").append(it.getType()).append(String.format(Locale.ROOT, " %.1f", iw));
      }
      Log.info(String.format(Locale.ROOT, "harness: horde: travelling light: %d items dropped (%.1f weight; %s), inventory %.2f -> %.2f of capacity %d",
            n, w, heavy.length() == 0 ? "nothing over 1" : heavy, before, p.getInventory().getCapacityWeight(), p.getMaxWeight()));
   }

   static void logWeight(IsoPlayer p, String when) {
      float w = p.getInventory().getCapacityWeight();
      float cap = p.getMaxWeight();
      if (weightStart < 0F) {
         weightStart = w;
         capacity = cap;
      }
      Log.info(String.format(Locale.ROOT, "harness: horde: inventory %s: weight %.2f of capacity %.0f (%.0f %%), Heavy Load level %d, %d of %d magazines carried",
            when, w, cap, 100F * w / Math.max(1F, cap), p.getMoodles().getMoodleLevel(zombie.scripting.objects.MoodleType.HEAVY_LOAD), magsCarried, magsWanted));
   }

   // ---------------------------------------------------------------- every frame

   /** Facts, damage, encumbrance; call every frame of the scene. {@code spawned}: the horde is out. */
   static void observe(IsoPlayer p, long nowNs, boolean spawned, float t) {
      if (!on) return;
      float dt = lastTickNs == 0L ? 0F : Math.min(0.1F, (nowNs - lastTickNs) / 1e9F);
      lastTickNs = nowNs;
      float step = (float)Math.hypot(p.getX() - lastX, p.getY() - lastY);
      if (step < 2F && dt > 0F) movedTiles += step;
      lastX = p.getX();
      lastY = p.getY();
      // threats: every zombie near the player (the horde and any stranger)
      nearest = 99F;
      nearestAny = 99F;
      within3 = within6 = within10 = threats = 0;
      float sx = 0F, sy = 0F;
      int n = 0;
      ArrayList<IsoZombie> list = IsoWorld.instance.getCell().getZombieList();
      for (int i = 0; i < list.size(); i++) {
         IsoZombie z = list.get(i);
         if (z == null || z.isDead() || z.getCurrentSquare() == null) continue;
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
         if (z.getTarget() == p) aimAt(z, p);
         float d = (float)Math.sqrt(dx * dx + dy * dy);
         if (d > 30F) continue;
         threats++;
         nearestAny = Math.min(nearestAny, d);
         boolean standing = !z.isOnFloor();
         if (standing || d < 1.5F) nearest = Math.min(nearest, d);
         if (d < 3F) within3++;
         if (d < 6F) within6++;
         if (d < 10F) within10++;
         if (d < 20F) {
            sx += z.getX();
            sy += z.getY();
            n++;
         }
         if (z.getCurrentState() == zombie.ai.states.AttackState.instance() && z.getTarget() == p) {
            if (attacking.put(z, Boolean.TRUE) == null) {
               attacksBegun++;
               if (firstAttackS < 0F) {
                  firstAttackS = t;
                  firstAttackTiles = d;
               }
               Log.info(String.format(Locale.ROOT, "harness: horde: zombie attack begun at the player at +%.1f s, %.2f tiles", t, d));
            }
         } else {
            attacking.remove(z);
         }
      }
      if (n > 0) {
         centX = sx / n;
         centY = sy / n;
      }
      if (spawned) closestEver = Math.min(closestEver, nearest);
      if (spawned && firstContactS < 0F && nearest < 2F) firstContactS = t;
      if (nowNs - speedNs >= 500_000_000L) { // speeds over 0.5 s windows: the horde's median, the player's own
         float win = speedNs == 0L ? 0F : (nowNs - speedNs) / 1e9F;
         speedNs = nowNs;
         ArrayList<Float> v = new ArrayList<>();
         Map<IsoZombie, float[]> next = new IdentityHashMap<>();
         for (int i = 0; i < list.size(); i++) {
            IsoZombie z = list.get(i);
            if (z == null || z.isDead() || z.isOnFloor()) continue;
            float[] was = zpos.get(z);
            if (was != null && win > 0F && Math.hypot(z.getX() - p.getX(), z.getY() - p.getY()) < 20F) v.add((float)Math.hypot(z.getX() - was[0], z.getY() - was[1]) / win);
            next.put(z, new float[] {z.getX(), z.getY()});
         }
         zpos.clear();
         zpos.putAll(next);
         if (v.size() >= 3) {
            java.util.Collections.sort(v);
            zombieSpeed = v.get(v.size() / 2);
         }
         if (win > 0F) playerSpeed = (float)Math.hypot(p.getX() - pSpeedX, p.getY() - pSpeedY) / win;
         pSpeedX = p.getX();
         pSpeedY = p.getY();
      }
      damage(p, t);
      checkFail(p, t);
      int heavy = p.getMoodles().getMoodleLevel(zombie.scripting.objects.MoodleType.HEAVY_LOAD);
      if (heavy > heavyMax) {
         heavyMax = heavy;
         Log.info(String.format(Locale.ROOT, "harness: horde: Heavy Load level %d at +%.1f s (weight %.2f of %d)", heavy, t, p.getInventory().getCapacityWeight(), p.getMaxWeight()));
      }
      weightMax = Math.max(weightMax, p.getInventory().getCapacityWeight());
      panicMax = Math.max(panicMax, p.getStats().get(zombie.characters.CharacterStat.PANIC));
      for (int i = 0; i < WATCHED.length; i++) {
         int lv = p.getMoodles().getMoodleLevel(WATCHED[i]);
         if (lv > moodleMax[i]) {
            moodleMax[i] = lv;
            Log.info(String.format(Locale.ROOT, "harness: horde: moodle %s level %d at +%.1f s", WATCHED[i], lv, t));
         }
      }
   }

   private static void damage(IsoPlayer p, float t) {
      IsoGameCharacter by = p.getAttackedBy();
      if (by != null && by != lastAttackedBy) {
         attackers++;
         Log.info(String.format(Locale.ROOT, "harness: horde: HIT by %s at +%.1f s, %.2f tiles off, reaction '%s', health %.1f", by instanceof IsoZombie ? "a zombie" : by.getClass().getSimpleName(), t,
               Math.hypot(by.getX() - p.getX(), by.getY() - p.getY()), p.getHitReaction(), p.getBodyDamage().getOverallBodyHealth()));
      }
      lastAttackedBy = by;
      boolean hitState = p.getCurrentState() == zombie.ai.states.PlayerHitReactionState.instance();
      if (hitState && !inHitState) {
         stateHits++; // a second hit by the same zombie leaves attackedBy as it was; the hit reaction comes again
         Log.info(String.format(Locale.ROOT, "harness: horde: hit reaction %d at +%.1f s ('%s')", stateHits, t, p.getHitReaction()));
      }
      inHitState = hitState;
      hits = Math.max(attackers, stateHits);
      boolean bump = p.getCurrentState() == zombie.ai.states.BumpedState.instance();
      if (bump && !inBump) {
         bumps++;
         Log.info(String.format(Locale.ROOT, "harness: horde: bumped (%s) at +%.1f s, nearest zombie %.2f tiles", p.getBumpType(), t, nearestAny));
      }
      inBump = bump;
      int w = 0;
      for (BodyPart bp : p.getBodyDamage().getBodyParts()) {
         if (bp.scratched() || bp.bitten() || bp.isCut() || bp.deepWounded() || bp.getBleedingTime() > 0F || bp.haveBullet()) w++;
      }
      if (w > wounds) Log.info(String.format(Locale.ROOT, "harness: horde: WOUNDED: %d body parts at +%.1f s (scratched %d, bitten %d)", w, t, p.getBodyDamage().getNumPartsScratched(), p.getBodyDamage().getNumPartsBitten()));
      wounds = w;
      woundsMax = Math.max(woundsMax, w);
      float h = p.getBodyDamage().getOverallBodyHealth();
      if (healthStart < 0F) healthStart = h;
      healthMin = Math.min(healthMin, h);
      if (!safetyGod && (hits > 0 || woundsMax > 0) && h < 60F) {
         safetyGod = true;
         zombie.SystemDisabler.zombiesDontAttack = true; // GOD_MODE alone needs Core.debug (hk-fight1 died with it on)
         Log.info(String.format(Locale.ROOT, "harness: horde: health %.1f after %d hits: god mode back on (safety_god; the pass has failed)", h, hits));
      }
   }

   /**
    * The kill switch: the first zombie damage (a hit, a wound) ends the pass ("failed: player damaged"), with
    * {@code kill_switch=false} only the death does ("failed: player died"). God mode comes on at once so the player
    * lives through the summary and the quit. Showcase.routeEnd ends the route on {@link #failReason}.
    */
   private static void checkFail(IsoPlayer p, float t) {
      if (!failReason.isEmpty()) return;
      boolean dead = p.isDead() || p.getBodyDamage().getOverallBodyHealth() <= 0F;
      boolean damaged = hits > 0 || stateHits > 0 || woundsMax > 0;
      if (!dead && !(killSwitch && damaged)) return;
      failAt = t;
      failReason = dead ? "failed: player died" : "failed: player damaged";
      safetyGod = true;
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true); // (needs Core.debug: the line below is what holds)
      zombie.SystemDisabler.zombiesDontAttack = true; // no more bites while the summary is written and the game quits
      Log.info(String.format(Locale.ROOT, "harness: horde: KILL SWITCH at +%.1f s: %s (hits %d, wounds %d, health %.1f, nearest zombie %.2f tiles, %d within 3); the pass ends now",
            t, failReason, Math.max(hits, stateHits), woundsMax, p.getBodyDamage().getOverallBodyHealth(), nearestAny, within3));
   }

   /** Zero zombie damage: no hit, no wound (the health is reported apart: hunger or a fall is not the horde). */
   static boolean unhurt() {
      return hits == 0 && stateHits == 0 && woundsMax == 0 && failReason.isEmpty();
   }

   // ---------------------------------------------------------------- deciding

   /** Without a director: the same rules the director is asked to follow. */
   static String autopilot(String current, boolean empty, boolean reloading, boolean sidearmReady, boolean onSidearm) {
      if (reloading) {
         return nearest < kiteEff() ? "kite" : "reload";
      }
      if ("kite".equals(current)) {
         if (nearest < shootEff()) return "kite"; // run on until the horde is well back
      } else if (nearest < kiteEff()) {
         return "kite";
      }
      if (empty) {
         if (nearest >= reloadRoom || onSidearm) return "reload"; // an empty revolver: "reload" goes back to the rifle
         if (sidearmReady) return "draw_sidearm";
         return nearest < kiteEff() + 1F ? "kite" : "reload"; // walking away while it runs
      }
      return "shoot";
   }

   /**
    * The distances adapt to the crowd: with {@code few_zombies} (10) or fewer within 10 tiles a player stands and picks them
    * off from closer (kite under 4, shoot again over 6); hk-fight4 ran round the last 5 zombies for 90 s at 6 / 9.
    */
   static float kiteEff() {
      if (straggling()) return Math.min(kiteAt, stragglerKite);
      // a wall coming: crowd_zombies (8) within 6 tiles kite from crowd_kite_tiles (4.5) (h80-p32, 2026-10-22: 30 within 6
      // tiles of the pier spot, the nearest at 2.7 mid-reload before the first kite; hit at 22.9 s)
      if (crowdZombies > 0 && within6 >= crowdZombies) return Math.max(kiteAt, crowdKite);
      return within10 <= fewZombies ? Math.min(kiteAt, 4F) : kiteAt;
   }

   static float shootEff() {
      if (straggling()) return Math.min(shootAt, stragglerShoot);
      return within10 <= fewZombies ? Math.min(shootAt, 6F) : shootAt;
   }

   /**
    * One or two zombies near and no more coming ({@code straggler_zombies}, 2, within 6 tiles, none other within 10):
    * turn and shoot them point-blank as the maintainer's takes did, instead of running. h80-p1 (2026-10-22): a fast
    * straggler kept pace with the running player at 1-4 tiles and the kite ran for 20 s without a shot.
    */
   static boolean straggling() {
      return stragglers > 0 && within6 <= stragglers && within10 <= stragglers;
   }

   static float reflexEff() {
      return straggling() ? Math.min(reflexAt, stragglerReflex) : reflexAt;
   }

   /**
    * The trigger waits for the aim to settle (aim_settle, the game's aiming delay; move_settle, its been-moving-for) unless a
    * zombie is within the kite distance + 1. CombatManager takes both straight off the hit chance and IsoPlayer.calculateCritChance
    * off the crit chance: the M16's aiming delay restarts at 40 each time the aim drops (a reload, a kite) and clears in ~1.4 s
    * at Aiming 10, been-moving-for climbs 37 a second to 70 and costs 0.75 x it x distance / 10 (-31 at 6 tiles). h80-p9
    * (2026-10-22): the magazine after the first reload went out at 10 rounds a second in 3.1 s and took 9 zombies.
    */
   static boolean unsettled(IsoPlayer p) {
      if (!on || aimSettle < 0F || nearest < kiteEff() + 1F) return false;
      boolean wait = p.getAimingDelay() > aimSettle || p.getBeenMovingFor() > moveSettle;
      if (wait) settleFrames++;
      return wait;
   }

   static float aimSettle = 15F, moveSettle = 30F;
   static int settleFrames;

   /**
    * The opening (open_tiles, with a circuit): from the release the player runs on along the circuit this far before the
    * fight, as the maintainer did at the pier (2026-10-22): the horde comes out of the gap between the blocks south-east
    * of the spot, the pier is a dead end with water north and west, and every stand there ended with the front at arm's
    * length after the second magazine and a 10-20 s run past the gap's mouth with no shot (h80-p1..p12). Run at once and
    * the mouth is passed while the front is still in the gap; the horde then trails along the promenade. A zombie within
    * open_abort_tiles (4) ends it early.
    */
   static String opening(String act, float t) {
      if (!on || !releasedOn || openTiles <= 0F || circ == null || openState == 2) return act;
      if (openState == 0) {
         openState = 1;
         openFromTravel = circTravel;
         Log.info(String.format(Locale.ROOT, "harness: horde: opening run of %.0f tiles along the circuit at +%.1f s", openTiles, t));
      }
      float run = circTravel - openFromTravel;
      if (run >= openTiles || nearest < openAbort) {
         openState = 2;
         Log.info(String.format(Locale.ROOT, "harness: horde: opening run ends at +%.1f s after %.1f tiles, the nearest %.1f tiles", t, run, nearest));
         return act;
      }
      return "kite";
   }

   /** nogo=x0,y0,x1,y1/...: squares no move goes into (the pier's deck: a dead end, h80-p15 ran up it and was bitten). */
   static float[][] nogo = new float[0][];

   static void addNogo(float x0, float y0, float x1, float y1) {
      nogo = java.util.Arrays.copyOf(nogo, nogo.length + 1);
      nogo[nogo.length - 1] = new float[] {x0, y0, x1, y1};
      Log.info(String.format(Locale.ROOT, "harness: horde: no-go box %.0f,%.0f-%.0f,%.0f", x0, y0, x1, y1));
   }
   static boolean releasedOn;
   static float openTiles, openAbort = 4F, openFromTravel;
   static int openState;

   static int stragglers = 2;
   /** A zombie on the ground is walked up to this close (floor_close_tiles; shots from farther pass over a body lying flat). */
   static float floorClose = 3F;
   static float stragglerKite = 1.6F, stragglerShoot = 3F, stragglerReflex = 1.4F;

   static int fewZombies = 10, instincts;
   static boolean devStand;
   static float closeInAt = 12F;
   static int closeInLeft = 20, hordeLeft;
   /** Rounds in a row without the reticle on the target that make it hidden (hidden_rounds, 3; 0 off). */
   static int hiddenRounds = 3, blindStreak, hiddenWalks;
   static boolean closeInRun = true;
   static int crowdZombies = 8;
   static float crowdKite = 4.5F;
   static IsoZombie blindZ;
   private static float blindX, blindY;
   private static boolean hidWalk;
   private static float hidX, hidY;

   /** A round left the gun at {@code z}; {@code blind}: the reticle was on no body. */
   static void roundAt(IsoZombie z, boolean blind) {
      if (z == null) return;
      // by place, not by zombie: three thumping one wall took turns as the target and the count restarted at every round
      // (h80-p24, 2026-10-22: 91 rounds into the wall from 8 tiles, no walk)
      boolean same = blindZ != null && (z == blindZ || Math.hypot(z.getX() - blindX, z.getY() - blindY) < 2F);
      if (blind) {
         blindStreak = same ? blindStreak + 1 : 1;
         blindZ = z;
         blindX = z.getX();
         blindY = z.getY();
      } else if (same) {
         blindStreak = 0;
      }
   }

   static boolean hiddenAt(IsoZombie z) {
      return hiddenRounds > 0 && blindStreak >= hiddenRounds && blindZ != null && (z == blindZ || Math.hypot(z.getX() - blindX, z.getY() - blindY) < 2F);
   }
   /** No round without the reticle on a body at a target farther than this (blind_tiles). */
   static float blindTiles = 12F;
   private static boolean instinctOn;
   private static long staleKiteNs;

   /**
    * The other half of the reflex: the director still says kite (its view lags by a state write and a Jev answer, and it
    * hesitates near the threshold: hk-fight4's last 90 s were kite at 0.5 against shoot at 0.4 with the nearest 10 tiles
    * off) while the nearest zombie has been past the shoot distance + 1.5 for a second and the gun is ready: the player
    * turns and shoots, counted ({@code instincts}), until the director picks something else or a zombie comes under the kite
    * distance.
    */
   static String instinct(String act, boolean canShoot, long nowNs, float t) {
      if (openState == 1) return act; // the opening run is a kite with the horde far back on purpose
      if (!on || !"kite".equals(act) || reflexOn || !canShoot || nearest < kiteEff() || !instinctOn && nearest < shootEff() + 1.5F) {
         instinctOn = false;
         staleKiteNs = 0L;
         return act;
      }
      if (instinctOn) return "shoot";
      if (staleKiteNs == 0L) staleKiteNs = nowNs;
      if (nowNs - staleKiteNs < 1_000_000_000L) return act;
      instinctOn = true;
      instincts++;
      Log.info(String.format(Locale.ROOT, "harness: horde: instinct %d at +%.1f s: the director says kite with the nearest %.1f tiles off (shoot over %.1f), shooting", instincts, t, nearest, shootEff()));
      return "shoot";
   }

   /** The flinch: a zombie this close and the action is not running away. */
   static String reflex(String command, float t) {
      if (!on) return command;
      if (devStand) return "kite".equals(command) ? "shoot" : command;
      boolean fighting = "shoot".equals(command) || "reload".equals(command) || "hold".equals(command) || "draw_sidearm".equals(command) || "face_horde".equals(command);
      boolean need = fighting && nearest < reflexEff();
      if (need && !reflexOn) {
         reflexes++;
         Log.info(String.format(Locale.ROOT, "harness: horde: reflex %d at +%.1f s: a zombie %.2f tiles off during '%s', running", reflexes, t, nearest, command));
      }
      reflexOn = need || reflexOn && fighting && nearest < reflexEff() + 1.5F; // run until 1.5 tiles more are won
      if (reflexOn) {
         reflexFrames++;
         return "kite";
      }
      return command;
   }

   // ---------------------------------------------------------------- moving

   /**
    * Holds the movement keys (and Run when {@code run}) for the best direction away from the zombies and round the horde;
    * re-chosen every 50 ms. False when no direction is open (the keys stay up).
    */
   static boolean steer(IsoPlayer p, long nowNs, boolean run, String why) {
      if (!"".equals(why) && !why.equals(moveWhy)) {
         moveWhy = why;
      }
      if (!wantMove) {
         kites++;
         kiteSinceNs = nowNs;
         refNs = nowNs;
         refX = p.getX();
         refY = p.getY();
         sense = 0;
      }
      wantMove = true;
      if (nowNs - lastSteerNs >= 50_000_000L || !dirValid) {
         lastSteerNs = nowNs;
         float[] best = choose(p);
         if (best == null) {
            dirValid = false;
            noWayFrames++;
         } else {
            if (dirValid && (Math.abs(best[0] - ux) > 1e-3F || Math.abs(best[1] - uy) > 1e-3F)) steerChanges++;
            ux = best[0];
            uy = best[1];
            dirValid = true;
         }
      }
      trackStuck(p, nowNs);
      if (!dirValid) return false;
      Showcase.moveKeys(ux, uy);
      if (run) {
         Showcase.holdKey("Run");
         p.setForceRun(true);
      }
      return true;
   }

   /** The stuck clock while a move is wanted: under 0.5 tiles of progress in 2 s is a stuck episode. */
   private static void trackStuck(IsoPlayer p, long nowNs) {
      if (p.isCollidedThisFrame() || p.getCollidedObject() != null) blockedFrames++;
      // stuck: under 0.5 tiles of progress in 2 s while moving is wanted
      if (Math.hypot(p.getX() - refX, p.getY() - refY) >= 0.5F) {
         if (stuckNow) {
            float secs = (nowNs - refNs) / 1e9F;
            stuckLongest = Math.max(stuckLongest, secs);
            Log.info(String.format(Locale.ROOT, "harness: horde: moving again after %.1f s", secs));
         }
         stuckNow = false;
         refNs = nowNs;
         refX = p.getX();
         refY = p.getY();
      } else if (!stuckNow && nowNs - refNs > 2_000_000_000L) {
         stuckNow = true;
         stuck++;
         IsoGridSquare sq = p.getCurrentSquare();
         Log.info(String.format(Locale.ROOT, "harness: horde: STUCK %d at %.2f,%.2f (square %s), wanted %.2f,%.2f, state %s, collided %b, nearest zombie %.1f, %s", stuck, p.getX(), p.getY(),
               sq == null ? "-" : sq.x + "," + sq.y, ux, uy, p.getCurrentState() == null ? "-" : p.getCurrentState().getClass().getSimpleName(), p.isCollidedThisFrame(), nearestAny, moveWhy));
         sense = -sense; // the other way round
         dirValid = false;
      }
   }

   static int closeIns;
   private static boolean closing;

   // ---------------------------------------------------------------- walking back while shooting

   static boolean backpedalOn = true;
   static float backpedalMargin = 2F, backpedalSecs, backpedalTiles;
   static int backpedals;
   private static boolean bpActive;
   private static long bpLastNs, bpChooseNs;
   private static float bpUx, bpUy, bpX, bpY;

   /**
    * A player's answer to a horde that walks slower than they run but faster than they can shoot it down: walk back
    * while aiming (the game walks an aiming player, slower, the aim held on the target). During "shoot", with the nearest
    * standing zombie inside the shoot distance + {@code backpedal_tiles} (2), the movement keys (no Run) go the way the
    * kite would (choose: away from the zombies, round the horde, inside the arena), re-chosen every 250 ms. hk-pass2
    * (2026-10-21): standing still to shoot, the 9 -> 6 tile window lasted ~2.4 s against the horde's 1.27 tiles/s, the
    * player kited 156 of 200 s (the arena's edge bent every run and the horde cut across) and the cap came with 2 alive.
    * Off: flag backpedal=false.
    */
   static void backpedal(IsoPlayer p, long nowNs) {
      // straggling: a margin of one tile, else the walk back undid every close-in on one down on the ground (h80-p2: closing in
      // 21-33, one each 1.1 s at 6.0 tiles for 14 s)
      boolean want = on && backpedalOn && !devStand && nearest < shootEff() + (straggling() ? Math.min(1F, backpedalMargin) : backpedalMargin);
      if (!want) {
         backpedalEnd(nowNs);
         return;
      }
      if (!bpActive) {
         bpActive = true;
         backpedals++;
         bpLastNs = nowNs;
         bpChooseNs = 0L;
         bpX = p.getX();
         bpY = p.getY();
      }
      backpedalSecs += (nowNs - bpLastNs) / 1e9F;
      bpLastNs = nowNs;
      backpedalTiles += (float)Math.hypot(p.getX() - bpX, p.getY() - bpY);
      bpX = p.getX();
      bpY = p.getY();
      if (bpChooseNs == 0L || nowNs - bpChooseNs >= 250_000_000L) {
         bpChooseNs = nowNs;
         float[] best = choose(p);
         if (best == null) {
            bpUx = bpUy = 0F;
         } else {
            bpUx = best[0];
            bpUy = best[1];
         }
      }
      if (bpUx != 0F || bpUy != 0F) Showcase.moveKeys(bpUx, bpUy);
   }

   static void backpedalEnd(long nowNs) {
      if (!bpActive) return;
      bpActive = false;
      backpedalSecs += (nowNs - bpLastNs) / 1e9F;
   }

   /** Degrees between the player's facing and the bearing to {@code z}. */
   static float facingOff(IsoPlayer p, IsoZombie z) {
      float zb = (float)Math.toDegrees(Math.atan2(z.getY() - p.getY(), z.getX() - p.getX()));
      return Math.abs(((zb - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F);
   }

   static boolean closingIn() {
      return closing;
   }

   static void closeInEnd() {
      closing = false;
   }

   /**
    * Walk up to the target when it is out of reach for a good shot: farther than {@code close_in_tiles} (12), or down on
    * the ground farther than {@code floor_close_tiles} (3) (a zombie on the floor far off takes rounds that do not land), with no standing
    * zombie inside the shoot distance (then the fight is with that one). Runs while over 10 tiles, walks closer in; stays
    * in the arena and on open ground (else false: shoot from here). True while closing in.
    */
   static boolean closeIn(IsoPlayer p, long nowNs, IsoZombie tgt, float t) {
      float d = (float)Math.hypot(tgt.getX() - p.getX(), tgt.getY() - p.getY());
      // hidden: rounds in a row went with the reticle on no body (h80-p20, 2026-10-22: the last two thumped a building's
      // side wall out of sight 7.6 tiles off and 80 rounds went into the wall for 40 s): walk on until it is in sight
      boolean hidden = hiddenAt(tgt);
      boolean far = d > closeInAt || tgt.isOnFloor() && d > floorClose || hidden && d > 2.5F;
      // a target outside the arena (behind the field's fence, thumping it): the walk goes to the arena's point nearest to
      // it, 3 tiles inside the edge (hk-bp9, 2026-10-21: the last zombie thumped the fence 27 tiles off for 36 s while the
      // player stood at the far edge and ~140 rounds hit nothing)
      float gx = tgt.getX(), gy = tgt.getY();
      float cd = arenaSet ? (float)Math.hypot(gx - arenaX, gy - arenaY) : 0F;
      if (arenaSet && cd > arenaR - 3F && cd > 1e-3F) {
         gx = arenaX + (gx - arenaX) / cd * (arenaR - 3F);
         gy = arenaY + (gy - arenaY) / cd * (arenaR - 3F);
      }
      if (hidden && circ != null) {
         // round the block along the circuit toward the circuit's point nearest to it, 3 tiles a go, then a look
         float sp = circuitS(p.getX(), p.getY()), ds = circuitS(tgt.getX(), tgt.getY()) - sp;
         if (ds > circLen / 2F) ds -= circLen;
         if (ds < -circLen / 2F) ds += circLen;
         if (Math.abs(ds) > 2F) {
            float[] q = circuitAt(sp + Math.signum(ds) * Math.min(Math.abs(ds), circAhead));
            gx = q[0];
            gy = q[1];
         }
      }
      if (hidden && !hidWalk) {
         hidWalk = true;
         hidX = p.getX();
         hidY = p.getY();
         hiddenWalks++;
         Log.info(String.format(Locale.ROOT, "harness: horde: hidden %d at +%.1f s: %d rounds without the reticle on a zombie %.1f tiles off at %.1f,%.1f; walking toward %.1f,%.1f",
               hiddenWalks, t, blindStreak, d, tgt.getX(), tgt.getY(), gx, gy));
      }
      if (hidWalk && (!hidden || Math.hypot(p.getX() - hidX, p.getY() - hidY) >= 3F)) {
         hidWalk = false;
         if (hidden) blindStreak = hiddenRounds - 1; // one more round without it walks on
         if (hidden) {
            closing = false;
            return false;
         }
      }
      float gd = (float)Math.hypot(gx - p.getX(), gy - p.getY());
      // not toward a horde that is still coming: only for the last ones (close_in_left, 20) or one on the ground (hk-bench1..4:
      // the autopilot walked at the horde 12 tiles out the moment it was released)
      if (!far || nearest < shootEff() || gd < 2F || hordeLeft > closeInLeft && !tgt.isOnFloor()) {
         closing = false;
         return false;
      }
      float ux = (gx - p.getX()) / gd, uy = (gy - p.getY()) / gd;
      int z = (int)Math.floor(p.getZ() + 0.01F);
      if (!walkable(p.getX() + ux, p.getY() + uy, z) || !lineClear(p.getX(), p.getY(), p.getX() + ux * 2F, p.getY() + uy * 2F, z)) {
         // round the obstacle: the open direction that gains most on the target (h80-p4, 2026-10-22: a crawler 5.4 tiles off
         // behind the park's bushes, the straight walk refused, 8 s of rounds that did not land)
         float bestDot = 0.3F;
         float[] side = null;
         for (float[] u : DIRS) {
            float dot = u[0] * ux + u[1] * uy;
            if (dot > bestDot && walkable(p.getX() + u[0], p.getY() + u[1], z) && lineClear(p.getX(), p.getY(), p.getX() + u[0] * 2F, p.getY() + u[1] * 2F, z)) {
               bestDot = dot;
               side = u;
            }
         }
         if (side == null) {
            closing = false;
            return false;
         }
         ux = side[0];
         uy = side[1];
      }
      if (!closing) {
         closeIns++;
         Log.info(String.format(Locale.ROOT, "harness: horde: closing in %d at +%.1f s on a zombie %.1f tiles off%s%s", closeIns, t, d, tgt.isOnFloor() ? " on the ground" : "",
               gx != tgt.getX() || gy != tgt.getY() ? String.format(Locale.ROOT, " (outside the arena: to its edge, %.1f tiles)", gd) : ""));
      }
      if (!wantMove) {
         refNs = nowNs;
         refX = p.getX();
         refY = p.getY();
         kiteSinceNs = nowNs;
      }
      closing = true;
      wantMove = true;
      trackStuck(p, nowNs);
      Showcase.moveKeys(ux, uy);
      // run: the aim is down for the walk anyway (the maintainer, 2026-10-22: "why you dont run? you always walk"; a close-in
      // walked under 10 tiles at ~1.3 tiles/s); close_in_run=false walks under 10 tiles as before
      if (gd > 10F || closeInRun) {
         Showcase.holdKey("Run");
         p.setForceRun(true);
      }
      return true;
   }

   /** Not moving this frame (shooting, standing): the stuck clock rests. */
   static void still(long nowNs) {
      if (wantMove) kiteSecs += (nowNs - kiteSinceNs) / 1e9F;
      if (stuckNow) stuckLongest = Math.max(stuckLongest, (nowNs - refNs) / 1e9F);
      wantMove = false;
      stuckNow = false;
      dirValid = false;
      closing = false;
   }

   private static final float[][] DIRS = new float[8][];

   static {
      for (int k = 0; k < 8; k++) DIRS[k] = new float[] {(float)Math.cos(Math.PI / 4 * k), (float)Math.sin(Math.PI / 4 * k)};
   }

   private static float[] choose(IsoPlayer p) {
      float px = p.getX(), py = p.getY();
      int z = (int)Math.floor(p.getZ() + 0.01F);
      ArrayList<IsoZombie> list = IsoWorld.instance.getCell().getZombieList();
      ArrayList<float[]> zs = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
         IsoZombie zz = list.get(i);
         if (zz == null || zz.isDead() || zz.getCurrentSquare() == null) continue;
         float d = (float)Math.hypot(zz.getX() - px, zz.getY() - py);
         if (d < 16F) zs.add(new float[] {zz.getX(), zz.getY(), zz.isOnFloor() ? 1F : 0F});
      }
      float[] lane = laneDir(p); // circuit=: the way on along the loop (then no circling round the horde's centre)
      // round the horde: the tangent of the centre's bearing, one sense kept (the one toward the arena's middle first)
      float rx = px - centX, ry = py - centY, rl = (float)Math.hypot(rx, ry);
      float tx = 0F, ty = 0F;
      if (rl > 1e-3F) {
         rx /= rl;
         ry /= rl;
         if (sense == 0) {
            float ax = arenaX - px, ay = arenaY - py;
            sense = (-ry * ax + rx * ay) >= 0F ? 1 : -1;
         }
         tx = -ry * sense;
         ty = rx * sense;
      }
      float runSpeed = Math.max(3F, playerSpeed);
      float zSpeed = Math.max(1.5F, zombieSpeed);
      float bestScore = -1e9F;
      float[] best = null;
      int open = 0;
      for (float[] u : DIRS) {
         float L = 3F;
         boolean ok = true;
         for (float s = 0.5F; s <= L + 1e-3F && ok; s += 0.5F) {
            ok = walkable(px + u[0] * s, py + u[1] * s, z);
         }
         if (ok) ok = lineClear(px, py, px + u[0] * L, py + u[1] * L, z);
         if (!ok) continue;
         open++;
         // clearance along the path (now) and at its end 0.8 s on (the zombies stepped toward the player)
         float ex = px + u[0] * runSpeed * 0.8F, ey = py + u[1] * runSpeed * 0.8F;
         float pathClear = 99F, endClear = 99F;
         for (float[] q : zs) {
            float r = q[2] > 0F ? 0.6F : 0F; // a body on the floor: keep off it too
            for (float s = 0.5F; s <= L + 1e-3F; s += 0.5F) {
               pathClear = Math.min(pathClear, (float)Math.hypot(px + u[0] * s - q[0], py + u[1] * s - q[1]) + r);
            }
            float qx = q[0], qy = q[1];
            if (q[2] == 0F) {
               float dx = ex - qx, dy = ey - qy, dl = (float)Math.hypot(dx, dy);
               float mv = Math.min(dl, zSpeed * 0.8F);
               if (dl > 1e-3F) {
                  qx += dx / dl * mv;
                  qy += dy / dl * mv;
               }
            }
            endClear = Math.min(endClear, (float)Math.hypot(ex - qx, ey - qy) + r);
         }
         float score = Math.min(endClear, 12F) + 0.7F * Math.min(pathClear, 6F);
         // room: the open fraction of a 7x7 patch round the point 5 tiles on; an alley, the pier's deck or a corner scores low
         // (hk-pier1, 2026-10-21: the kite ran into a 3-wide alley between two building blocks, the horde came round both
         // sides, 9 zombies within 3 tiles and a bite)
         if (roomWeight > 0F) score += roomWeight * roomAt(px + u[0] * 5F, py + u[1] * 5F, z);
         if (pathClear < 1.2F) score -= 20F; // through a zombie: a bump, a grab
         if (lane != null) {
            score += laneWeight * (u[0] * lane[0] + u[1] * lane[1]);
         } else {
            score += 1.5F * (u[0] * tx + u[1] * ty);
         }
         if (dirValid) score += 0.8F * (u[0] * ux + u[1] * uy);
         float ad = (float)Math.hypot(ex - arenaX, ey - arenaY);
         if (lane == null && ad > arenaR * 0.7F) score -= 1.5F * (ad - arenaR * 0.7F); // keep to the arena
         if (score > bestScore) {
            bestScore = score;
            best = u;
         }
      }
      kiteRoom = open > 0;
      return best;
   }

   static float roomWeight = 6F;

   // ---------------------------------------------------------------- the circuit

   /**
    * circuit=x,y/x,y/...: a closed loop on open ground round an obstacle (a building block, a garden) that the player keeps
    * to. The maintainer's own takes at the pier (2026-10-22, docs/findings-horde-bench-rework-2026-10-21.md) never circled
    * the horde in a small arena: they walked away along long open lanes so the horde trailed behind as a column, and in
    * the second take ran laps round the park's octagonal garden. Every move (the kite, the walk back while aiming, the walk
    * while reloading) then prefers the way on along the loop, {@code circuit_ahead} (4) tiles ahead of the player's
    * nearest point on it, weighed by {@code lane_weight} (5) against the clearance from the zombies (which still wins near
    * them); the sense is the one leading away from the horde at the first move ({@code circuit_sense} 1 / -1 forces it).
    * The zombies chasing round an obstacle follow the path behind instead of cutting across.
    */
   static float[][] circ;
   static float[] circCum;
   static float circLen, circTravel, laneWeight = 5F, circAhead = 4F;
   private static int circSense;
   private static float circLastS = -1F;

   private static void circuitApply() {
      String c = HarnessFlags.get("circuit", "").trim();
      circ = null;
      if (c.isEmpty()) return;
      String[] pts = c.split("/");
      circ = new float[pts.length][];
      for (int i = 0; i < pts.length; i++) {
         String[] xy = pts[i].split(",");
         circ[i] = new float[] {Float.parseFloat(xy[0].trim()) + 0.5F, Float.parseFloat(xy[1].trim()) + 0.5F};
      }
      circCum = new float[circ.length + 1];
      for (int i = 0; i < circ.length; i++) {
         float[] a = circ[i], b = circ[(i + 1) % circ.length];
         circCum[i + 1] = circCum[i] + (float)Math.hypot(b[0] - a[0], b[1] - a[1]);
      }
      circLen = circCum[circ.length];
      laneWeight = Float.parseFloat(HarnessFlags.get("lane_weight", "5").trim());
      circAhead = Float.parseFloat(HarnessFlags.get("circuit_ahead", "4").trim());
      circSense = Integer.parseInt(HarnessFlags.get("circuit_sense", "0").trim());
      Log.info(String.format(Locale.ROOT, "harness: horde: circuit of %d points, %.0f tiles round, lane weight %.1f, %.0f tiles ahead, sense %s", circ.length, circLen, laneWeight, circAhead,
            circSense == 0 ? "away from the horde" : Integer.toString(circSense)));
   }

   /** The arc length of the circuit's point nearest to (x, y). */
   static float circuitS(float x, float y) {
      float best = Float.MAX_VALUE, bs = 0F;
      for (int i = 0; i < circ.length; i++) {
         float[] a = circ[i], b = circ[(i + 1) % circ.length];
         float dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
         float t = l2 < 1e-6F ? 0F : Math.max(0F, Math.min(1F, ((x - a[0]) * dx + (y - a[1]) * dy) / l2));
         float d = (float)Math.hypot(x - a[0] - dx * t, y - a[1] - dy * t);
         if (d < best) {
            best = d;
            bs = circCum[i] + t * (circCum[i + 1] - circCum[i]);
         }
      }
      return bs;
   }

   /** The circuit's point at arc length {@code s} (taken round the loop). */
   static float[] circuitAt(float s) {
      s = ((s % circLen) + circLen) % circLen;
      int i = 0;
      while (i < circ.length - 1 && circCum[i + 1] < s) i++;
      float[] a = circ[i], b = circ[(i + 1) % circ.length];
      float seg = circCum[i + 1] - circCum[i];
      float t = seg < 1e-6F ? 0F : (s - circCum[i]) / seg;
      return new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
   }

   /** The unit direction on along the circuit from the player, or null without one. */
   static float[] laneDir(IsoPlayer p) {
      if (circ == null || circLen < 1F) return null;
      float s = circuitS(p.getX(), p.getY());
      if (circSense == 0) {
         float[] f = circuitAt(s + 8F), b = circuitAt(s - 8F);
         circSense = Math.hypot(f[0] - centX, f[1] - centY) >= Math.hypot(b[0] - centX, b[1] - centY) ? 1 : -1;
         Log.info(String.format(Locale.ROOT, "harness: horde: circuit sense %d (away from the horde's centre %.1f,%.1f) from %.1f tiles round", circSense, centX, centY, s));
      }
      if (circLastS >= 0F) {
         float ds = s - circLastS;
         if (ds > circLen / 2F) ds -= circLen;
         if (ds < -circLen / 2F) ds += circLen;
         if (ds * circSense > 0F && Math.abs(ds) < 3F) circTravel += Math.abs(ds);
      }
      circLastS = s;
      float[] q = circuitAt(s + circSense * circAhead);
      float dx = q[0] - p.getX(), dy = q[1] - p.getY(), l = (float)Math.hypot(dx, dy);
      return l < 1e-3F ? null : new float[] {dx / l, dy / l};
   }

   /** The walkable fraction of the 7x7 squares round (x, y). */
   static float roomAt(float x, float y, int z) {
      int open = 0;
      for (int dy = -3; dy <= 3; dy++) {
         for (int dx = -3; dx <= 3; dx++) {
            if (walkable(x + dx, y + dy, z)) open++;
         }
      }
      return open / 49F;
   }

   /** Can the player run over (x, y): the body fits, dry, no tree or bush on it, inside the arena's edge. */
   static boolean walkable(float x, float y, int z) {
      IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare((int)Math.floor(x), (int)Math.floor(y), z);
      if (sq == null || sq.isWaterSquare() || !(sq.isSolidFloor() || sq.TreatAsSolidFloor()) || sq.HasTree() || bush(sq)) return false;
      // the arena bounds a circuit too: the fight stays in the pier scene (the maintainer, 2026-10-22, after h80-p13..p24 kited
      // 30-45 tiles west along the waterfront with the bound off)
      if (arenaSet && Math.hypot(x - arenaX, y - arenaY) > arenaR + 2F) return false;
      for (float[] b : nogo) if (x >= b[0] && x <= b[2] + 1F && y >= b[1] && y <= b[3] + 1F) return false;
      return Nav.canStand(x, y, z);
   }

   static boolean lineClear(float ax, float ay, float bx, float by, int z) {
      return Nav.lineClear(ax, ay, bx, by, z);
   }

   /**
    * A square that slows or trips a running player: the game's own test (IsoGameCharacter.isInTrees2: a bush object, a
    * low / high hedge) plus the bush and foliage sprites by name (hk-scan1 counted 0 bushes on a field full of them with
    * the names alone, 2026-10-21).
    */
   static boolean bush(IsoGridSquare sq) {
      if (sq.hasBush()) return true;
      String mv = sq.getProperties().get("Movement");
      if ("HedgeLow".equalsIgnoreCase(mv) || "HedgeHigh".equalsIgnoreCase(mv)) return true;
      for (int i = 0; i < sq.getObjects().size(); i++) {
         zombie.iso.IsoObject o = sq.getObjects().get(i);
         String n = o.getSprite() == null ? null : o.getSprite().getName();
         if (n != null && (n.startsWith("f_bushes") || n.startsWith("vegetation_foliage") || n.startsWith("e_americanholly") || n.startsWith("f_wallvines"))) return true;
      }
      return false;
   }

   /**
    * Clutter a running player would weave round or trip on: any object on the square that is not its floor, grass, a
    * floor overlay or a bush (counted separately): bins, crates, logs, benches, fire hydrants, car parts, signs.
    */
   static boolean clutter(IsoGridSquare sq) {
      for (int i = 0; i < sq.getObjects().size(); i++) {
         zombie.iso.IsoObject o = sq.getObjects().get(i);
         if (o == sq.getFloor() || o.getSprite() == null) continue;
         zombie.iso.sprite.IsoSprite s = o.getSprite();
         String n = s.getName();
         if (o.isGrass() || o.isBush() || s.attachedFloor || s.canBeRemoved && n != null && n.startsWith("e_")) continue; // grass, overlays, erosion flowers
         if (n != null && (n.startsWith("blends_") || n.startsWith("floors_") || n.startsWith("street_") || n.startsWith("d_streetcracks") || n.startsWith("d_floorleaves") || n.startsWith("e_newgrass") || n.startsWith("overlay_"))) continue;
         if (o instanceof zombie.iso.objects.IsoTree) continue; // counted as a tree
         return true;
      }
      return false;
   }

   // ---------------------------------------------------------------- the director's facts and the summary

   static String stateJson() {
      return String.format(Locale.ROOT,
            "\"tactics\":{\"nearest_standing_zombie_tiles\":%.1f,\"zombies_within_3_tiles\":%d,\"zombies_within_6_tiles\":%d,\"zombies_within_10_tiles\":%d,"
                  + "\"horde_speed_tiles_per_s\":%.1f,\"seconds_until_nearest_reaches_player\":%.1f,\"open_direction_to_run\":%b,\"kite_under_tiles\":%.1f,"
                  + "\"shoot_over_tiles\":%.1f,\"reload_room_tiles\":%.0f,\"reflex_running\":%b,\"hits_taken\":%d,"
                  + "\"nearest_is_closer_than_kite_under\":%b,\"nearest_is_farther_than_shoot_over\":%b,\"room_to_reload\":%b}",
            nearest, within3, within6, within10, zombieSpeed, zombieSpeed > 0.2F ? Math.max(0F, nearest - 1F) / zombieSpeed : 99F, kiteRoom, kiteEff(), shootEff(), reloadRoom, reflexOn, hits,
            nearest < kiteEff(), nearest >= shootEff(), nearest >= reloadRoom);
   }

   static String line(float t) {
      return String.format(Locale.ROOT, "harness: horde: +%.1f s nearest %.1f (any %.1f), within 3/6/10 %d/%d/%d, horde speed %.2f, player %.2f tiles/s, hits %d, wounds %d, health %.1f, attacks begun %d, bumps %d, reflexes %d, instincts %d, kites %d (%.1f s), walk-backs %d (%.1f s, %.1f tiles), stuck %d, blocked frames %d, no way %d",
            t, nearest, nearestAny, within3, within6, within10, zombieSpeed, playerSpeed, hits, woundsMax, healthMin > 100F ? -1F : healthMin, attacksBegun, bumps, reflexes, instincts, kites, kiteSecs, backpedals, backpedalSecs, backpedalTiles, stuck, blockedFrames, noWayFrames);
   }

   static void fields(Map<String, String> m) {
      if (!on) return;
      m.put("horde_tactics", "kite");
      m.put("horde_hits", Integer.toString(Math.max(hits, stateHits)));
      m.put("horde_wounds", Integer.toString(woundsMax));
      m.put("horde_health_min", healthMin > 100F ? "nan" : String.format(Locale.ROOT, "%.1f", healthMin));
      m.put("horde_attacks_begun", Integer.toString(attacksBegun));
      m.put("horde_bumps", Integer.toString(bumps));
      m.put("horde_unhurt", Boolean.toString(unhurt()));
      m.put("horde_ready", readyWhat.isEmpty() ? "never" : readyWhat);
      m.put("horde_ready_s", readyS < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", readyS));
      m.put("horde_release_nearest", releaseNearest < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", releaseNearest));
      m.put("horde_first_attack_s", firstAttackS < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", firstAttackS));
      m.put("horde_first_attack_tiles", firstAttackTiles < 0F ? "nan" : String.format(Locale.ROOT, "%.2f", firstAttackTiles));
      m.put("horde_first_contact_s", firstContactS < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", firstContactS));
      m.put("horde_fail", failReason.isEmpty() ? "none" : failReason);
      m.put("horde_fail_s", failAt < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", failAt));
      m.put("horde_health_start", healthStart < 0F ? "nan" : String.format(Locale.ROOT, "%.1f", healthStart));
      m.put("horde_safety_god", Boolean.toString(safetyGod));
      m.put("horde_reflexes", Integer.toString(reflexes));
      m.put("horde_instincts", Integer.toString(instincts));
      m.put("horde_close_ins", Integer.toString(closeIns));
      m.put("horde_hidden_walks", Integer.toString(hiddenWalks));
      m.put("horde_kites", Integer.toString(kites));
      m.put("horde_kite_s", String.format(Locale.ROOT, "%.1f", kiteSecs));
      m.put("horde_backpedals", Integer.toString(backpedals));
      m.put("horde_backpedal_s", String.format(Locale.ROOT, "%.1f", backpedalSecs));
      m.put("horde_backpedal_tiles", String.format(Locale.ROOT, "%.1f", backpedalTiles));
      m.put("horde_stuck", Integer.toString(stuck));
      m.put("horde_stuck_longest_s", String.format(Locale.ROOT, "%.1f", stuckLongest));
      m.put("horde_blocked_frames", Integer.toString(blockedFrames));
      m.put("horde_closest_tiles", String.format(Locale.ROOT, "%.2f", closestEver));
      m.put("horde_moved_tiles", String.format(Locale.ROOT, "%.0f", movedTiles));
      m.put("horde_weight", String.format(Locale.ROOT, "%.2f", weightStart));
      m.put("horde_weight_max", String.format(Locale.ROOT, "%.2f", weightMax));
      m.put("horde_capacity", String.format(Locale.ROOT, "%.0f", capacity));
      m.put("horde_heavy_load_max", Integer.toString(heavyMax));
      m.put("horde_mags", magsCarried + "/" + magsWanted);
      m.put("horde_desensitized", Boolean.toString(desensitized));
      m.put("horde_panic_max", String.format(Locale.ROOT, "%.3f", panicMax));
      m.put("horde_buffs", buffsDone.replace(' ', '_'));
      StringBuilder mm = new StringBuilder();
      for (int i = 0; i < WATCHED.length; i++) {
         if (moodleMax[i] > 0) mm.append(mm.length() > 0 ? "," : "").append(WATCHED[i]).append(':').append(moodleMax[i]);
      }
      m.put("horde_moodles_max", mm.length() == 0 ? "none" : mm.toString());
      m.put("horde_settle_frames", Integer.toString(settleFrames));
      m.put("horde_circuit", circ == null ? "none" : String.format(Locale.ROOT, "%d points, %.0f tiles, laps %.2f", circ.length, circLen, circTravel / Math.max(1F, circLen)));
   }

   // ---------------------------------------------------------------- arena_scan

   private static float[][] scanPts;
   private static int scanIdx = -1;
   private static long scanNs;
   private static final StringBuilder scanOut = new StringBuilder();

   private static void scanApply() {
      String s = HarnessFlags.get("arena_scan", "").trim();
      if (s.isEmpty()) return;
      String[] parts = s.split("/");
      scanPts = new float[parts.length][];
      for (int i = 0; i < parts.length; i++) {
         String[] xy = parts[i].split(",");
         scanPts[i] = new float[] {Float.parseFloat(xy[0].trim()), Float.parseFloat(xy[1].trim())};
      }
      Log.info("harness: arena scan of " + parts.length + " point(s)");
   }

   static boolean scanning() {
      return scanPts != null && scanIdx < scanPts.length;
   }

   static void scanStart(IsoPlayer p) {
      if (scanPts == null || scanIdx >= 0) return;
      scanIdx = 0;
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
      p.teleportTo((int)scanPts[0][0], (int)scanPts[0][1], 0);
      scanNs = System.nanoTime();
   }

   /** True while the scan owns the player. */
   static boolean scanTick(IsoPlayer p, long nowNs) {
      if (scanPts == null || scanIdx < 0 || scanIdx >= scanPts.length) return false;
      if (nowNs - scanNs < 5_000_000_000L) return true;
      scan(p, (int)scanPts[scanIdx][0], (int)scanPts[scanIdx][1], 45);
      scanIdx++;
      if (scanIdx < scanPts.length) {
         p.teleportTo((int)scanPts[scanIdx][0], (int)scanPts[scanIdx][1], 0);
         scanNs = nowNs;
      } else {
         try {
            java.nio.file.Files.writeString(new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-arena.out").toPath(), scanOut.toString());
         } catch (Exception e) {
            Log.warn("harness: arena scan write failed: " + e);
         }
         Log.info("harness: arena scan done");
      }
      return scanIdx < scanPts.length;
   }

   private static void scan(IsoPlayer p, int cx, int cy, int R) {
      int W = 2 * R + 1;
      char[] c = new char[W * W];
      int[] counts = new int[128];
      Map<String, Integer> names = new java.util.HashMap<>();
      for (int j = 0; j < W; j++) {
         for (int i = 0; i < W; i++) {
            int x = cx - R + i, y = cy - R + j;
            IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare(x, y, 0);
            char k;
            if (sq == null) k = ' ';
            else if (sq.isWaterSquare()) k = '~';
            else if (!(sq.isSolidFloor() || sq.TreatAsSolidFloor())) k = '_';
            else if (sq.HasTree()) k = 'T';
            else if (bush(sq)) k = 'b';
            else if (sq.getVehicleContainer() != null) k = 'v';
            else if (!Nav.canStand(x + 0.5F, y + 0.5F, 0)) k = '#';
            else if (clutter(sq)) k = 'o';
            else if (edge(sq, x + 1, y) || edge(sq, x, y + 1) || edge(sq, x - 1, y) || edge(sq, x, y - 1)) k = '|';
            else k = sq.isOutside() ? '.' : 'i';
            c[j * W + i] = k;
            counts[k]++;
            if (k == 'b' || k == 'o') { // which sprites made the bushes / clutter (checks the classes)
               for (int q = 0; q < sq.getObjects().size(); q++) {
                  zombie.iso.IsoObject o = sq.getObjects().get(q);
                  if (o == sq.getFloor() || o.getSprite() == null || o.getSprite().getName() == null) continue;
                  String nm = o.getSprite().getName();
                  int us = nm.lastIndexOf('_');
                  names.merge(k + ":" + (us > 0 ? nm.substring(0, us) : nm), 1, Integer::sum);
               }
            }
         }
      }
      // clearance: chamfer distance (in tiles) to the nearest square that is not open ground outdoors
      float[] d = new float[W * W];
      for (int i = 0; i < W * W; i++) d[i] = c[i] == '.' ? 1e9F : 0F;
      float s2 = 1.4142F;
      for (int j = 0; j < W; j++) {
         for (int i = 0; i < W; i++) {
            int k = j * W + i;
            if (d[k] == 0F) continue;
            float v = d[k];
            if (i > 0) v = Math.min(v, d[k - 1] + 1F);
            if (j > 0) v = Math.min(v, d[k - W] + 1F);
            if (i > 0 && j > 0) v = Math.min(v, d[k - W - 1] + s2);
            if (i < W - 1 && j > 0) v = Math.min(v, d[k - W + 1] + s2);
            if (i == 0 || j == 0 || i == W - 1 || j == W - 1) v = Math.min(v, 1F);
            d[k] = v;
         }
      }
      for (int j = W - 1; j >= 0; j--) {
         for (int i = W - 1; i >= 0; i--) {
            int k = j * W + i;
            if (d[k] == 0F) continue;
            float v = d[k];
            if (i < W - 1) v = Math.min(v, d[k + 1] + 1F);
            if (j < W - 1) v = Math.min(v, d[k + W] + 1F);
            if (i < W - 1 && j < W - 1) v = Math.min(v, d[k + W + 1] + s2);
            if (i > 0 && j < W - 1) v = Math.min(v, d[k + W - 1] + s2);
            d[k] = v;
         }
      }
      int bi = -1;
      float bv = -1F;
      for (int k = 0; k < W * W; k++) {
         float v = d[k] - 0.02F * (float)Math.hypot(k % W - R, k / W - R);
         if (v > bv) {
            bv = v;
            bi = k;
         }
      }
      int bx = cx - R + bi % W, by = cy - R + bi / W;
      int ci = R * W + R;
      String head = String.format(Locale.ROOT, "arena scan %d,%d r %d: clearance at the point %.1f tiles; widest clear disc at %d,%d, clearance %.1f tiles; squares: open %d indoor %d tree %d bush %d clutter %d water %d nofloor %d blocked %d edge %d vehicle %d unloaded %d",
            cx, cy, R, d[ci], bx, by, d[bi], counts['.'], counts['i'], counts['T'], counts['b'], counts['o'], counts['~'], counts['_'], counts['#'], counts['|'], counts['v'], counts[' ']);
      StringBuilder top = new StringBuilder();
      names.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12).forEach(e -> top.append(' ').append(e.getKey()).append('=').append(e.getValue()));
      head += "; bush / clutter sprites:" + top;
      Log.info("harness: " + head);
      scanOut.append("# ").append(head).append('\n');
      for (int j = 0; j < W; j++) {
         for (int i = 0; i < W; i++) {
            int x = cx - R + i, y = cy - R + j;
            scanOut.append(x == bx && y == by ? '@' : x == cx && y == cy ? '+' : c[j * W + i]);
         }
         scanOut.append('\n');
      }
   }

   private static boolean edge(IsoGridSquare a, int x, int y) {
      IsoGridSquare b = IsoWorld.instance.getCell().getGridSquare(x, y, a.z);
      return b != null && (a.isWallTo(b) || a.isHoppableTo(b) || a.isWindowTo(b) || a.isBlockedTo(b));
   }
}
