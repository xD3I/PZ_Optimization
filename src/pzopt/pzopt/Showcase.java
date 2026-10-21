package pzopt;

import java.util.ArrayList;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * Harness-only cinematic scene (2026-09-24, the HDR horde video): {@code showcase=horde} in the flag file. The player
 * starts at {@code start=} (the harness teleports there at world-ready; invisible, god mode, the area's own zombies kept
 * away from then on) and, at the route start, becomes visible with an M16 ({@code gun}), {@code mags} spare magazines and
 * a lit angle-head flashlight on an ALICE webbing (attached lights emit like held ones). A burning horde of {@code horde}
 * fast shamblers spawns {@code horde_gap} tiles behind them (the {@code horde_dir} side of the pier) and chases; the player
 * runs (the game's own pathfinding, forced run) to the pier spot {@code pier=x,y}, where an ambulance waits behind with its
 * headlights and lightbar on, turns to face the horde while a line of fires lights up across its way, and fires every
 * {@code fire_ms} through the game's own input (right mouse held to aim, left pulled, the aim point on the nearest zombie:
 * zombie.input.Mouse override), reloading from the spare magazines with the game's automatic reload when a magazine is
 * empty. {@code power=off} keeps the grid power (street lamps) off, {@code darkness=pitch} forces the sandbox's pitch-black
 * night. A state line every 2 s ({@code harness: showcase}).
 *
 * <p>With {@code director=jev} the character's actions are chosen outside the game: every 0.3 s the scene's facts go to
 * {@code Zomboid/pzopt-showcase-state.json}; harness/showcase-director.py asks TypeSafe's Jev which action fits, and writes
 * {@code <seq> <action>} to {@code Zomboid/pzopt-showcase-cmd.txt} (run_to_pier, face_horde, light_fire_line, shoot,
 * reload, hold), which the rig carries out until the next one. Movement is input like the aim: the Forward / Backward /
 * Left / Right keys toward the pier plus Run, reported held by the zombie.input.GameKeyboard override.
 *
 * <p>The street shot (2026-10-06, the Workshop headline GIF): {@code horde_when=pier} spawns the horde only once the player
 * stands still at the pier spot, {@code back_off=N} adds the director action {@code back_off} (run, rifle lowered,
 * away from the horde, at most N tiles from the spot), {@code ambulance=none} parks no vehicle.
 *
 * <p>The horde shooting bench (2026-10-18): {@code horde_when=ready} spawns the horde only once the player is ready to fire:
 * at the pier spot, stopped, turned to the horde side, aiming, with a loaded gun (the run and the turn were spent with the
 * horde already chasing). {@code sidearm=<item>} (the bench: Base.Revolver_Long, the .44 Magnum) is carried loaded with
 * {@code sidearm_rounds} spare rounds; at the primary gun's {@code sidearm_at}-th reload (default 2) the player draws it
 * instead, through the game's equip action, and when it is empty goes back to the primary and reloads that. The hands are
 * the truth: the scene keeps the weapon it wants ({@code wanted}) and re-issues an equip the game refused; a reload or an
 * equip counts as under way for 0.5 s after it was issued and while the Lua action queue holds one. {@code end=horde_dead}
 * ends the route {@code end_tail} s (2) after the last horde zombie died, at {@code end_cap} s (90) of route time at the
 * latest; horde zombies that lost the player or stand still are sent at the player again once a second. The pass's
 * horde facts (horde_left, horde_killed, horde_clear_s, shots, reloads, switches) go into the benchmark's step file.
 *
 * <p>What the horde bench needed so the whole horde dies (2026-10-18, runs bench-hd* / hs-jev* on the flip and the Mac),
 * each a flag that defaults on with {@code horde_when=ready}: {@code aimed} (the reticle on a head at chest / head height,
 * one tapped round per press, a zombie a round went at left alone for 0.4 s), {@code fire_mode=tap|single},
 * {@code no_shove} (no shove inside the gun's MinRange), {@code bubble=0.9} (standing horde zombies kept 0.9 tiles out,
 * in front of the muzzle), {@code pin_pier} (pushed off the spot by the crowd: back onto it), {@code step_back} (a zombie
 * on the floor under the player's feet never gets up: the player walks back from it), {@code turn_to_target},
 * {@code no_jam} (jam chance 0, full condition; jams counted). Panic and stress are held at 0 with {@code no_attack}, and
 * fake-dead horde zombies are woken.
 */
public final class Showcase {
   private Showcase() {
   }

   /** the game's mouse position while the showcase aims (zombie.input.Mouse override reads it) */
   public static volatile boolean aimOverride;
   public static volatile int aimXA, aimYA;
   /** zombie.input.Mouse.update holds the right button (aim) and sets the left one (fire) from these */
   public static volatile boolean holdButtons, fireDown;
   /** Harness UI rig (pzoptUiButton): the left mouse button held / released through zombie.input.Mouse.update. */
   public static volatile boolean uiButtonOverride, uiButtonDown;
   /** Harness UI rig (pzoptUiWheel): a wheel step handed to zombie.input.Mouse.update once. */
   public static volatile int uiWheel;

   private enum Phase { WAIT, RUN, TURN, FIGHT }

   /** key codes the zombie.input.GameKeyboard override reports as held (the director's movement) */
   private static final boolean[] heldKeys = new boolean[512];
   private static boolean director, pitchBlack;
   private static String command = "hold";
   private static int commandSeq = -1, commands;
   private static long lastStateNs, lastCmdCheckNs;
   private static java.io.File stateFile, cmdFile;

   public static boolean keyHeld(int key) {
      return key >= 0 && key < heldKeys.length && heldKeys[key];
   }

   static void releaseKeys() {
      java.util.Arrays.fill(heldKeys, false);
   }

   static void holdKey(String binding) {
      int k = zombie.core.Core.getInstance().getKey(binding);
      if (k > 0 && k < heldKeys.length) heldKeys[k] = true;
   }

   private static boolean noShove, pinPier;
   private static float bubble;
   private static boolean stepBack;
   private static long stepBackNs;
   private static int steps;
   private static int woken;
   private static long heldOff;
   private static IsoZombie lastTarget;
   private static long lastShotNs, lastTurnNs;
   private static boolean turnToTarget;
   private static String targetInfo = "-";
   private static int pinned;
   private static long lastFightNs;

   private static boolean hordeWhenReadyFlag() {
      return "ready".equalsIgnoreCase(HarnessFlags.get("horde_when", "start").trim());
   }
   private static boolean on, powerOff, burn = true, fireLine = true, noAttack, hordeAtPier, hordeWhenReady, hordeSpawned;
   private static String sidearmType;
   private static int sidearmRounds, sidearmAt, primaryEmpties, switches, switchTries;
   private static HandWeapon primary, sidearm;
   /** the weapon the scene wants in the hands (the hands catch up through the equip action) */
   private static HandWeapon wanted;
   private static boolean sidearmDrawn, primaryWasEmpty;
   /** an action (reload, equip) issued: busy until then even when the game has not listed it yet */
   private static long pendingUntilNs;
   /** end=horde_dead: the route ends end_tail s after the last horde zombie died, at end_cap s at the latest */
   private static boolean endWhenDead;
   private static float endCap, endTail;
   private static long clearedNs, spawnNs;
   private static int hordeTotal, vanished, repaths;
   private static String endReason = "";
   private static HandWeapon lastAmmoGun;
   private static InventoryItem lastInHand;
   private static int horde, hordeGap, hordeFires, lightbar, fireMs, mags;
   private static float backOffMax, hordeAfterS, hordeMin;
   private static float pierX, pierY;
   private static String gunType, ambulanceType, hordeDir;
   private static HandWeapon gun;
   private static InventoryItem angleLight;
   private static BaseVehicle ambulance;
   private static final ArrayList<IsoZombie> zombies = new ArrayList<>();
   private static final java.util.Set<IsoZombie> hordeSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
   private static final ArrayList<IsoGridSquare> fireSquares = new ArrayList<>();
   private static float dirX, dirY;
   private static Phase phase = Phase.WAIT;
   private static long startNs, phaseNs, lastLogNs, lastClearNs, lastIgniteNs;
   private static float turnFrom, turnTo;
   private static int shots, reloads, strangersRemoved, reattached, lastAmmo = -1;
   private static boolean started, firesLit;
   private static String lastPath = "-";
   private static long lastTickNs, lastRepathNs, holdSinceNs, readySinceNs;
   private static int repress;

   static void apply() {
      on = "horde".equalsIgnoreCase(HarnessFlags.get("showcase", "").trim());
      if (!on) {
         return;
      }
      horde = Integer.parseInt(HarnessFlags.get("horde", "80").trim());
      hordeGap = Integer.parseInt(HarnessFlags.get("horde_gap", "12").trim());
      hordeFires = Integer.parseInt(HarnessFlags.get("horde_fires", "9").trim());
      lightbar = Integer.parseInt(HarnessFlags.get("lightbar", "2").trim());
      fireMs = Integer.parseInt(HarnessFlags.get("fire_ms", "110").trim());
      mags = Integer.parseInt(HarnessFlags.get("mags", "6").trim());
      gunType = HarnessFlags.get("gun", "Base.AssaultRifle").trim();
      ambulanceType = HarnessFlags.get("ambulance", "Base.VanAmbulance").trim();
      hordeDir = HarnessFlags.get("horde_dir", "SE").trim().toUpperCase(java.util.Locale.ROOT);
      powerOff = "off".equalsIgnoreCase(HarnessFlags.get("power", "").trim());
      pitchBlack = "pitch".equalsIgnoreCase(HarnessFlags.get("darkness", "").trim());
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      burn = !"false".equalsIgnoreCase(HarnessFlags.get("burn", "true").trim()); // false: a plain horde (the ragdoll bench, horde-shoot)
      fireLine = !"false".equalsIgnoreCase(HarnessFlags.get("fire_line", "true").trim()); // false: light_fire_line only marks the line lit
      // true: zombies never attack (stock's SystemDisabler switch; GOD_MODE is a cheat and needs Core.debug, so without it the
      // horde bit the player to death at the pier in hs-stock-1): the horde crowds the player, who keeps shooting
      noAttack = "true".equalsIgnoreCase(HarnessFlags.get("no_attack", "false").trim());
      // true (default with no_attack): the player never shoves. The game turns an attack into a shove when the nearest
      // zombie stands inside the gun's MinRange (CombatManager, 0.61 tiles), and the horde that never attacks crowds the
      // player at 0.1-0.2 tiles: the M16 fired about one round a second there and 111 of 150 lived (the flip, bench-hd2).
      // IsoPlayer.setAuthorizedHandToHand(false), the game's own switch, makes that attack the shot.
      noShove = "true".equalsIgnoreCase(HarnessFlags.get("no_shove", Boolean.toString(noAttack)).trim());
      // tiles: horde zombies are kept this far from the player (0 = off; the horde bench 0.9, see tick)
      noJamFlag = "true".equalsIgnoreCase(HarnessFlags.get("no_jam", Boolean.toString(hordeWhenReadyFlag())).trim());
      aimedFire = "true".equalsIgnoreCase(HarnessFlags.get("aimed", Boolean.toString(hordeWhenReadyFlag())).trim());
      stepBack = "true".equalsIgnoreCase(HarnessFlags.get("step_back", Boolean.toString(hordeWhenReadyFlag())).trim());
      turnToTarget = "true".equalsIgnoreCase(HarnessFlags.get("turn_to_target", Boolean.toString(hordeWhenReadyFlag())).trim());
      bubble = Float.parseFloat(HarnessFlags.get("bubble", hordeWhenReadyFlag() ? "0.9" : "0").trim());
      pinPier = "true".equalsIgnoreCase(HarnessFlags.get("pin_pier", Boolean.toString(hordeWhenReadyFlag())).trim()); // the horde bench: the player stays on the spot
      // pier: the horde spawns once the player stands at the pier spot (the Workshop headline shot), not at the route start
      hordeAtPier = "pier".equalsIgnoreCase(HarnessFlags.get("horde_when", "start").trim());
      // ready: once the player stands at the pier, turned to the horde side, aiming a loaded gun (the horde bench)
      hordeWhenReady = "ready".equalsIgnoreCase(HarnessFlags.get("horde_when", "start").trim());
      sidearmType = HarnessFlags.get("sidearm", "").trim();
      sidearmRounds = Integer.parseInt(HarnessFlags.get("sidearm_rounds", "60").trim());
      sidearmAt = Integer.parseInt(HarnessFlags.get("sidearm_at", "2").trim());
      // horde_dead: the route ends once the horde is dead (end_tail s later, the last bodies down), at end_cap s of route
      // time at the latest (the horde bench, 2026-10-18); the route's own legs and hold no longer end it
      endWhenDead = "horde_dead".equalsIgnoreCase(HarnessFlags.get("end", "").trim());
      endCap = Float.parseFloat(HarnessFlags.get("end_cap", "90").trim());
      endTail = Float.parseFloat(HarnessFlags.get("end_tail", "2").trim());
      // N > 0: the director may pick back_off, walking away from the horde while firing, at most N tiles from the pier spot
      backOffMax = Float.parseFloat(HarnessFlags.get("back_off", "0").trim());
      // with horde_when=pier: no spawn before this many seconds after the route start (the world comes up black for ~12 s
      // after the hidden player turns visible; a player already at the spot spawned the horde into the dark, hl-remaster-15)
      hordeAfterS = Float.parseFloat(HarnessFlags.get("horde_after", "0").trim());
      hordeMin = Float.parseFloat(HarnessFlags.get("horde_min", "8").trim()); // no horde square nearer the player than this (tiles)
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-showcase-state.json");
      cmdFile = new java.io.File(z, "pzopt-showcase-cmd.txt");
      cmdFile.delete();
      String[] pier = HarnessFlags.get("pier", "6436,5186").split(",");
      pierX = Float.parseFloat(pier[0].trim()) + 0.5F;
      pierY = Float.parseFloat(pier[1].trim()) + 0.5F;
      String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"}; // k * 45 deg from +x (east), +y is south
      for (int k = 0; k < 8; k++) {
         if (names[k].equals(hordeDir)) {
            dirX = (float)Math.cos(Math.PI / 4 * k);
            dirY = (float)Math.sin(Math.PI / 4 * k);
         }
      }
      Log.info("harness: showcase=horde: " + horde + " burning zombies from the " + hordeDir + ", pier " + pierX + "," + pierY + ", " + gunType + " + " + mags
            + " magazines, " + ambulanceType + " lightbar " + lightbar + (powerOff ? ", power off" : ""));
   }

   /**
    * end=horde_dead (game thread, every route frame): -1 the scene has no end rule (the route's legs and hold end the
    * route), 0 the route goes on, 1 the route ends now: end_tail s after the last horde zombie died, or at end_cap s.
    * A rule of the scene's own state, so it is the same in every pass (the seeded world, the same fight).
    */
   public static int routeEnd(long nowNs) {
      if (!on || !endWhenDead || !started) return -1;
      if (!endReason.isEmpty()) return 1;
      float t = (nowNs - startNs) / 1e9F;
      if (hordeSpawned && hordeTotal > 0 && zombies.isEmpty()) {
         if (clearedNs == 0L) {
            clearedNs = nowNs;
            Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: horde dead at +%.1f s (%.1f s after it came): %d killed, %d vanished, %d shots (%.2f a kill; at head/body/none %d/%d/%d), %d reloads, %d switches, %d jams; the route ends in %.1f s",
                  t, (nowNs - spawnNs) / 1e9, kills(), vanished, shots, kills() > 0 ? shots / (double)kills() : 0.0, headshots, bodyShots, blindShots, reloads, switches, jams, endTail));
         }
         if ((nowNs - clearedNs) / 1e9F >= endTail) endReason = "horde dead";
      } else if (t >= endCap) {
         endReason = "time cap";
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: end_cap %.0f s reached with %d of %d horde zombies alive", endCap, zombies.size(), hordeTotal));
      }
      return endReason.isEmpty() ? 0 : 1;
   }

   /** How far the scene is toward its end (the benchmark's progress bar), -1 without an end rule. */
   public static float progress(long nowNs) {
      if (!on || !endWhenDead) return -1F;
      if (!started) return 0F;
      if (!endReason.isEmpty()) return 1F;
      float byTime = (nowNs - startNs) / 1e9F / endCap;
      float byKills = hordeTotal > 0 ? 0.95F * (hordeTotal - zombies.size()) / hordeTotal : 0F;
      return Math.min(1F, Math.max(byTime, byKills));
   }

   /** The pass's horde facts for the built-in benchmark's step properties. */
   public static java.util.Map<String, String> benchFields() {
      java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
      if (!on) return m;
      m.put("horde_total", Integer.toString(hordeTotal));
      m.put("horde_left", Integer.toString(zombies.size()));
      m.put("horde_killed", Integer.toString(kills()));
      m.put("horde_vanished", Integer.toString(vanished));
      m.put("horde_spawn_s", spawnNs == 0L ? "nan" : String.format(java.util.Locale.ROOT, "%.1f", (spawnNs - startNs) / 1e9));
      m.put("horde_clear_s", clearedNs == 0L ? "nan" : String.format(java.util.Locale.ROOT, "%.1f", (clearedNs - startNs) / 1e9));
      m.put("horde_end", endReason);
      m.put("horde_shots", Integer.toString(shots));
      m.put("horde_reloads", Integer.toString(reloads));
      m.put("horde_switches", Integer.toString(switches));
      m.put("horde_repaths", Integer.toString(repaths));
      m.put("horde_woken", Integer.toString(woken));
      m.put("horde_rounds_per_kill", kills() > 0 ? String.format(java.util.Locale.ROOT, "%.2f", shots / (double)kills()) : "nan");
      m.put("horde_headshots", Integer.toString(headshots));
      m.put("horde_body_shots", Integer.toString(bodyShots));
      m.put("horde_blind_shots", Integer.toString(blindShots));
      m.put("horde_jams", Integer.toString(jams));
      return m;
   }

   /** The route must not teleport the player: the scene runs them to the pier. */
   public static boolean active() {
      return on;
   }

   /** World-ready (game thread): out of harm's way until the scene starts. */
   static void worldReady(IsoPlayer p) {
      if (!on) return;
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true); // setGodMod(true, true) left the player mortal (cine-2)
      if (noAttack) zombie.SystemDisabler.zombiesDontAttack = true;
      p.setInvisible(true, true);
      if (powerOff) IsoWorld.instance.setHydroPowerOn(false);
      if (pitchBlack) {
         zombie.SandboxOptions.instance.nightDarkness.setValue(1); // Pitch Black
         Log.info("harness: showcase: night darkness forced to pitch black");
      }
      clearStrangers(p, 30F);
   }

   /** Route start (game thread): kit, ambulance, horde; then the run. */
   static void routeStart(IsoPlayer p) {
      if (!on || started) {
         return;
      }
      started = true;
      startNs = System.nanoTime();
      try {
         p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
         p.setInvisible(false, true); // the horde sees and chases the player
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Aiming, 10);
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Reloading, 10);
         Log.info("harness: showcase: Aiming " + p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Aiming) + ", Reloading "
               + p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Reloading));
         clearStrangers(p, 30F);
         equip(p);
         replaceVehicle();
         if (!"none".equalsIgnoreCase(ambulanceType)) parkAmbulance();
         if (!hordeAtPier && !hordeWhenReady) spawnHorde(p);
         RagdollWatch.start(); // pzopt-ragdoll.out: every ragdoll episode of the scene
         if (director) {
            enter(Phase.WAIT); // the director (Jev) decides from the first state on
         } else {
            command = "run_to_pier";
            enter(Phase.RUN);
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase setup failed: " + t);
      }
   }

   private static void enter(Phase ph) {
      phase = ph;
      phaseNs = System.nanoTime();
      Log.info("harness: showcase phase " + ph + " at +" + String.format(java.util.Locale.ROOT, "%.1f", (phaseNs - startNs) / 1e9) + " s");
   }

   private static boolean dry(IsoGridSquare sq) {
      return sq != null && !sq.isWaterSquare() && (sq.isSolidFloor() || sq.TreatAsSolidFloor());
   }

   private static void equip(IsoPlayer p) {
      for (InventoryItem lit : p.getActiveLightItems(new ArrayList<>())) { // no light but the angle-head one
         lit.setActivated(false);
         if (lit.isEmittingLight()) {
            p.removeAttachedItem(lit);
         }
      }
      p.setPrimaryHandItem(null);
      p.setSecondaryHandItem(null);
      InventoryItem item = p.getInventory().AddItem(gunType);
      if (!(item instanceof HandWeapon w)) {
         Log.warn("harness: showcase: " + gunType + " is not a weapon: " + item);
         return;
      }
      gun = w;
      gun.setContainsClip(true);
      gun.setCurrentAmmoCount(gun.getMaxAmmo());
      gun.setRoundChambered(true);
      gun.setCondition(gun.getConditionMax());
      p.setPrimaryHandItem(gun);
      if (gun.isTwoHandWeapon() || gun.isRequiresEquippedBothHands()) {
         p.setSecondaryHandItem(gun);
      }
      String magType = gun.getMagazineType();
      int filled = 0;
      for (int i = 0; magType != null && i < mags; i++) {
         InventoryItem mag = p.getInventory().AddItem(magType);
         if (mag != null) {
            mag.setCurrentAmmoCount(mag.getMaxAmmo());
            filled++;
         }
      }
      InventoryItem belt = p.getInventory().AddItem("Base.Bag_ALICE_BeltSus_Green"); // provides the webbing slots
      if (belt != null && belt.canBeEquipped() != null) {
         p.setWornItem(belt.canBeEquipped(), belt);
      }
      angleLight = p.getInventory().AddItem("Base.FlashLight_AngleHead");
      if (angleLight != null) {
         angleLight.setActivated(true);
         p.setAttachedItem("Webbing Left Walkie", angleLight);
      }
      primary = gun;
      wanted = gun;
      // fire_mode=single: the M16 on single fire (the game's recoil delay, ~0.37 s at Aiming 10, paces the rounds: 2.2 a
      // second, 113 of 150 down at 90 s in flip-bench-hdE2); the default, tap: left on Auto and the trigger let go the
      // frame the round leaves, one aimed round per press at the rifle's own cadence
      String mode = HarnessFlags.get("fire_mode", "tap").trim();
      if (hordeWhenReadyFlag() && "single".equalsIgnoreCase(mode) && gun.getFireModePossibilities() != null && gun.getFireModePossibilities().contains("Single")) {
         gun.setFireMode("Single");
      }
      if (!sidearmType.isEmpty()) {
         InventoryItem s = p.getInventory().AddItem(sidearmType);
         if (s instanceof HandWeapon w2) {
            sidearm = w2;
            sidearm.setCurrentAmmoCount(sidearm.getMaxAmmo());
            sidearm.setRoundChambered(true);
            sidearm.setCondition(sidearm.getConditionMax());
            String ammo = sidearm.getAmmoType() != null ? sidearm.getAmmoType().getItemKey() : null;
            if (ammo != null) p.getInventory().AddItems(ammo, sidearmRounds);
            Log.info("harness: showcase: sidearm " + sidearmType + " loaded (" + sidearm.getCurrentAmmoCount() + "), " + sidearmRounds + " spare " + ammo
                  + "; drawn at the primary's reload " + sidearmAt);
         } else {
            Log.warn("harness: showcase: sidearm " + sidearmType + " is not a weapon: " + s);
         }
      }
      p.resetEquippedHandsModels();
      Log.info("harness: showcase: " + gunType + " loaded (" + gun.getCurrentAmmoCount() + "), " + filled + " spare " + magType + ", angle light "
            + (angleLight == null ? "missing" : "emitting " + angleLight.isEmittingLight()));
   }

   /** Behind the pier spot (away from the horde), facing the horde, headlights and lightbar on. */
   private static void parkAmbulance() {
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare sq = cell.getGridSquare(Math.round(pierX - dirX * 4), Math.round(pierY - dirY * 4), 0);
      if (sq == null) {
         Log.warn("harness: showcase: no square behind the pier spot for the ambulance");
         return;
      }
      float a = (float)Math.atan2(-dirX, -dirY); // the vehicle frame of Harness.spawnAndEnter
      if (a < 0F) a += (float)(Math.PI * 2);
      zombie.iso.IsoDirections dir = zombie.iso.IsoDirections.values()[Math.round(a / (float)(Math.PI / 4)) % 8];
      BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(ambulanceType, dir, 0, sq);
      if (v == null) {
         Log.warn("harness: showcase: could not place " + ambulanceType);
         return;
      }
      float angle = (float)(a + Math.PI);
      while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
      v.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
      v.jniTransform.setRotation(v.savedRot);
      v.repair();
      ambulance = v;
      lights();
      Log.info("harness: showcase: " + ambulanceType + " at " + sq.x + "," + sq.y + " facing " + dir + ", headlights " + v.getHeadlightsOn() + ", lightbar " + v.hasLightbar());
   }

   /**
    * {@code replace_vehicle=x,y[/x,y...]}: the save's vehicle nearest each that square (within 6 tiles) is swapped for
    * {@code replace_with} (default Base.SportsCar) on its square and heading, repaired, painted {@code replace_hsv=h,s,v}
    * (default near-black). The Workshop headline shot: a black sports car instead of the silver sedan at the curb.
    */
   private static void replaceVehicle() {
      for (String at : HarnessFlags.get("replace_vehicle", "").trim().split("/")) { // several: x,y/x,y
         if (!at.isBlank()) replaceVehicle(at.trim());
      }
   }

   private static void replaceVehicle(String at) {
      String[] xy = at.split(",");
      float x = Float.parseFloat(xy[0].trim()) + 0.5F, y = Float.parseFloat(xy[1].trim()) + 0.5F;
      BaseVehicle old = null;
      float best = 6F * 6F;
      for (BaseVehicle v : IsoWorld.instance.currentCell.getVehicles()) {
         float dx = v.getX() - x, dy = v.getY() - y, d = dx * dx + dy * dy;
         if (d < best) {
            best = d;
            old = v;
         }
      }
      if (old == null) {
         Log.warn("harness: showcase: no vehicle within 6 tiles of " + at + " to replace");
         return;
      }
      org.joml.Vector3f f = old.getForwardVector(new org.joml.Vector3f());
      IsoGridSquare sq = old.getSquare();
      String oldName = old.getScriptName();
      float ox = old.getX(), oy = old.getY();
      old.permanentlyRemove();
      float a = (float)Math.atan2(-f.x, -f.z); // as parkAmbulance: facing (f.x, f.z) in world x / y
      if (a < 0F) a += (float)(Math.PI * 2);
      zombie.iso.IsoDirections dir = zombie.iso.IsoDirections.values()[Math.round(a / (float)(Math.PI / 4)) % 8];
      String type = HarnessFlags.get("replace_with", "Base.SportsCar").trim();
      BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(type, dir, 0, sq);
      if (v == null) {
         Log.warn("harness: showcase: could not place " + type + " at " + sq.x + "," + sq.y);
         return;
      }
      float angle = (float)(a + Math.PI);
      while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
      v.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
      v.jniTransform.setRotation(v.savedRot);
      v.repair();
      String[] hsv = HarnessFlags.get("replace_hsv", "0,0,0.06").split(",");
      v.setColorHSV(Float.parseFloat(hsv[0].trim()), Float.parseFloat(hsv[1].trim()), Float.parseFloat(hsv[2].trim()));
      org.joml.Vector3f nf = v.getForwardVector(new org.joml.Vector3f());
      Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: replaced %s at %.1f,%.1f (forward %.2f,%.2f) with %s facing %s (forward %.2f,%.2f), hsv %s",
            oldName, ox, oy, f.x, f.z, type, dir, nf.x, nf.z, String.join(",", hsv)));
   }

   private static void lights() {
      if (ambulance == null) return;
      if (!ambulance.getHeadlightsOn()) ambulance.setHeadlightsOn(true);
      if (lightbar > 0 && ambulance.hasLightbar() && ambulance.getLightbarLightsMode() != lightbar) ambulance.setLightbarLightsMode(lightbar);
   }

   /** A burning horde of fast shamblers hordeGap tiles behind the player (the horde side), chasing. */
   private static void spawnHorde(IsoPlayer p) {
      hordeSpawned = true; // once, even when no ground is found
      IsoCell cell = IsoWorld.instance.currentCell;
      int cx = Math.round(p.getX() + dirX * hordeGap), cy = Math.round(p.getY() + dirY * hordeGap);
      ArrayList<IsoGridSquare> ground = new ArrayList<>();
      for (int y = cy - 3; y <= cy + 3; y++) {
         for (int x = cx - 3; x <= cx + 3; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            float ddx = x + 0.5F - p.getX(), ddy = y + 0.5F - p.getY();
            if (dry(sq) && ddx * ddx + ddy * ddy > hordeMin * hordeMin) ground.add(sq); // never on top of the player (cine-1)
         }
      }
      if (ground.isEmpty()) {
         Log.warn("harness: showcase: no dry ground for the horde at " + cx + "," + cy);
         return;
      }
      int per = Math.max(1, (horde + ground.size() - 1) / ground.size());
      for (int i = 0; i < ground.size() && zombies.size() < horde; i++) {
         IsoGridSquare sq = ground.get(i);
         ArrayList<IsoZombie> list = zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit(sq.x, sq.y, 0, Math.min(per, horde - zombies.size()), null, 50);
         if (list != null) zombies.addAll(list);
      }
      hordeSet.addAll(zombies);
      hordeTotal = zombies.size();
      spawnNs = System.nanoTime();
      int burning = 0;
      for (IsoZombie z : zombies) {
         try {
            z.doZombieSpeed(2); // fast shamblers: a chase the player wins
            if (burn) z.SetOnFire();
            burning += z.isOnFire() ? 1 : 0;
            z.pathToCharacter(p);
         } catch (Throwable t) {
            // not fully in the world yet: the re-ignition in tick catches it
         }
      }
      Log.info("harness: showcase: " + zombies.size() + " zombies at " + cx + "," + cy + ", " + burning + " burning");
   }

   /** A line of fires across the horde's way, 7 tiles out from the pier spot. */
   private static void lightFireLine() {
      IsoCell cell = IsoWorld.instance.currentCell;
      float lx = -dirY, ly = dirX;
      fireSquares.clear();
      if (!fireLine) {
         firesLit = true;
         Log.info("harness: showcase: fire line off (fire_line=false), the fight starts");
         return;
      }
      for (int i = 0; i < hordeFires; i++) {
         float off = (i - (hordeFires - 1) / 2F) * 1.5F;
         IsoGridSquare sq = cell.getGridSquare(Math.round(pierX + dirX * 7 + lx * off), Math.round(pierY + dirY * 7 + ly * off), 0);
         if (dry(sq)) {
            zombie.iso.objects.IsoFireManager.StartFire(cell, sq, true, 100);
            fireSquares.add(sq);
         }
      }
      firesLit = true;
      Log.info("harness: showcase: fire line of " + fireSquares.size() + " across the horde's way");
   }

   /** The area's own zombies near the player (they bit the player from behind in showcase-diag): removed. */
   private static void clearStrangers(IsoPlayer p, float radius) {
      try {
         for (IsoZombie z : new ArrayList<>(IsoWorld.instance.getCell().getZombieList())) {
            if (hordeSet.contains(z)) continue;
            float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
            if (dx * dx + dy * dy < radius * radius) {
               z.removeFromWorld();
               z.removeFromSquare();
               strangersRemoved++;
            }
         }
      } catch (Exception e) {
         Log.warn("harness: showcase: clearing the area's zombies failed: " + e);
      }
   }

   /** Every frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on) {
         return;
      }
      if (nowNs - lastClearNs >= 1_000_000_000L) {
         lastClearNs = nowNs;
         clearStrangers(p, started ? 24F : 30F);
         if (powerOff && IsoWorld.instance.isHydroPowerOn()) IsoWorld.instance.setHydroPowerOn(false);
      }
      if (!started) {
         return;
      }
      RagdollWatch.tick(nowNs);
      lights();
      if (zombie.input.Mouse.isCursorVisible()) zombie.input.Mouse.setCursorVisible(false); // no pointer in the shot
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.getStats().set(CharacterStat.ENDURANCE, 1F);
      p.getStats().set(CharacterStat.FATIGUE, 0F);
      if (noAttack) { // a crowd of 100 at arm's length panics the player, and panic and stress take off hit chance (CombatManager.getMoodlesPenalty)
         p.getStats().set(CharacterStat.PANIC, 0F);
         p.getStats().set(CharacterStat.STRESS, 0F);
      }
      if (angleLight != null) {
         if (!angleLight.isActivated()) angleLight.setActivated(true);
         if (p.getAttachedItem("Webbing Left Walkie") != angleLight) {
            p.setAttachedItem("Webbing Left Walkie", angleLight);
            reattached++;
         }
      }
      noJam(primary);
      noJam(sidearm);
      if (noShove && p.isAuthorizedHandToHand()) p.setAuthorizedHandToHand(false);
      if (burn && nowNs - lastIgniteNs >= 2_000_000_000L) {
         lastIgniteNs = nowNs;
         for (IsoZombie z : zombies) {
            if (!z.isDead() && !z.isOnFire()) {
               try {
                  z.SetOnFire();
               } catch (Throwable ignored) {
               }
            }
         }
         if (firesLit) { // the storm's rain does not put fires out, but a fire burns down: keep the line lit
            for (IsoGridSquare sq : fireSquares) {
               if (sq.getFire() == null) zombie.iso.objects.IsoFireManager.StartFire(IsoWorld.instance.currentCell, sq, true, 100);
            }
         }
      }
      if (hordeAtPier && !hordeSpawned && Math.hypot(pierX - p.getX(), pierY - p.getY()) <= 0.8F && !p.isPlayerMoving()
            && (nowNs - startNs) / 1e9 >= hordeAfterS) {
         spawnHorde(p); // in position and stopped: now the horde comes
      }
      IsoZombie target = null;
      float bestD = Float.MAX_VALUE, bestTargetD = Float.MAX_VALUE, nearestStandingD = Float.MAX_VALUE;
      for (int i = zombies.size() - 1; i >= 0; i--) {
         IsoZombie z = zombies.get(i);
         if (z == null || z.isDead() || z.getCurrentSquare() == null) {
            if (z != null && !z.isDead()) vanished++; // left the world without dying (counted, not a kill)
            zombies.remove(i);
            continue;
         }
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY(), d = dx * dx + dy * dy;
         if (bubble > 0F && d < bubble * bubble && !z.isOnFloor() && bubbleSpotDry(p, dx, dy, d)) {
            // a zombie that never attacks walks into the player: one standing inside the player (0.0-0.2 tiles) is behind
            // the muzzle and the shots go past it; the last 3 of mac-bench-hd6m stood there for the final 10 s and 40
            // rounds. Held at the bubble's edge on its own bearing, in front of the muzzle, inside the point-blank bonus
            float r = (float)Math.sqrt(d);
            float ux = r > 1e-3F ? dx / r : -dirX, uy = r > 1e-3F ? dy / r : -dirY;
            z.setX(p.getX() + ux * bubble);
            z.setY(p.getY() + uy * bubble);
            dx = ux * bubble;
            dy = uy * bubble;
            d = bubble * bubble;
            heldOff++;
         }
         if (d < bestD) {
            bestD = d;
         }
         if (!z.isOnFloor() && d < nearestStandingD) nearestStandingD = d;
         // the target: near and in front. Distance in tiles plus the turn it takes (30 degrees count as a tile), a zombie on
         // the floor 5 tiles more, the current target at half its cost (no flipping between two). Nearest-first alone sent
         // the aim round the player once the last ones hugged it from every side: the facing lagged the mouse and the
         // last 11 took 100 rounds (mac-bench-hd5m)
         float zb = (float)Math.toDegrees(Math.atan2(dy, dx));
         float turn = Math.abs(((zb - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F);
         float cost = (float)Math.sqrt(d) + turn / 30F + (z.isOnFloor() ? 5F : 0F);
         if (z == lastTarget) cost *= 0.5F;
         Long shot = aimedFire ? shotAt.get(z) : null;
         if (shot != null && nowNs - shot < 400_000_000L) cost += 20F; // a round went at it: see whether it goes down first
         if (cost < bestTargetD) {
            bestTargetD = cost;
            target = z;
         }
      }
      lastTarget = target;
      if (target != null) {
         targetInfo = String.format(java.util.Locale.ROOT, "%.1f tiles%s, facing %.0f deg off, %s", Math.hypot(target.getX() - p.getX(), target.getY() - p.getY()),
               target.isOnFloor() ? " on the floor" : "", Math.abs((((float)Math.toDegrees(Math.atan2(target.getY() - p.getY(), target.getX() - p.getX())) - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F),
               target.getCurrentState() == null ? "-" : target.getCurrentState().getClass().getSimpleName());
      }
      float dt = lastTickNs == 0L ? 0F : Math.min(0.1F, (nowNs - lastTickNs) / 1e9F);
      lastTickNs = nowNs;
      float px = p.getX(), py = p.getY();
      float toPier = (float)Math.hypot(pierX - px, pierY - py);
      InventoryItem inHand = p.getPrimaryHandItem();
      if (inHand != null && (inHand == primary || inHand == sidearm)) gun = (HandWeapon)inHand; // the hands are the truth
      if (inHand != lastInHand) {
         if (lastInHand != null) {
            Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: switched to %s at +%.1f s (%d rounds)", inHand == null ? "empty hands" : inHand.getFullType(),
                  (nowNs - startNs) / 1e9, inHand instanceof HandWeapon hw ? hw.getCurrentAmmoCount() : 0));
         }
         lastInHand = inHand;
      }
      boolean reloading = busy(p, nowNs);
      if (gun == primary && gun != null) { // the primary's magazines emptied, counted once each (the sidearm replaces the sidearm_at-th reload)
         boolean empty = empty(gun);
         if (empty && !primaryWasEmpty) primaryEmpties++;
         primaryWasEmpty = empty;
      }
      if (hordeSpawned && nowNs - lastRepathNs >= 1_000_000_000L) {
         lastRepathNs = nowNs;
         repathStragglers(p);
      }
      float tx = target != null ? target.getX() : px + dirX * 10, ty = target != null ? target.getY() : py + dirY * 10;
      float bearing = (float)Math.toDegrees(Math.atan2(ty - py, tx - px));
      float offFacing = Math.abs(((bearing - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F);
      if (hordeSpawned && pinPier && toPier > 1.0F && !"run_to_pier".equals(command) && !"back_off".equals(command) && nowNs - stepBackNs > 3_000_000_000L
            && nearestStandingD < 2.5F * 2.5F) { // only while the crowd pushes (a step back from a body on the floor stays)
         // the horde that never attacks pushes the player off the spot (8 tiles by the end of mac-bench-hd3m): back onto
         // it, so the camera, and the fight, stay where every pass has them
         p.setX(pierX);
         p.setY(pierY);
         pinned++;
         px = p.getX();
         py = p.getY();
         toPier = 0F;
      }
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot(p, toPier, offFacing, reloading);
      }
      releaseKeys();
      p.setForceRun(false);
      switch (command) {
         case "run_to_pier" -> {
            aimOff();
            if (toPier > 0.8F) {
               moveKeys(pierX - px, pierY - py);
               holdKey("Run");
               p.setForceRun(true); // the held Run key alone walked (hl-remaster-11): IsoPlayer.UpdateInputState runs on forceRun
            }
            lastPath = "keys to pier " + String.format(java.util.Locale.ROOT, "%.1f", toPier) + (p.isGodMod() ? " god" : " mortal");
         }
         case "face_horde" -> {
            aimOff();
            float d = ((bearing - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F; // the short way round
            float step = 300F * dt;
            p.setDirectionAngle(p.getDirectionAngle() + Math.max(-step, Math.min(step, d)));
         }
         case "light_fire_line" -> {
            aimOff();
            if (!firesLit) lightFireLine();
         }
         // no facing gate: the aimed gun turns with the upper body ahead of the feet (getDirectionAngle), so a 30-80 degree
         // "facing off" still hits; holding fire for it left the tail of mac-bench-hd10m2 at 8 rounds in 8 s
         case "shoot" -> {
            fight(p, nowNs, tx, ty, target != null && bestD < 30F * 30F, reloading, target != null && target.isOnFloor(), target);
            // a zombie on the floor under the player's feet never gets up (ZombieOnGroundState restarts its reanimate
            // timer while it is stepped on) and the shots at it do not land: the last of flip-bench-hdA2 / hdA3 lay
            // 1.0-1.4 tiles off through 20 s of fire. With no zombie standing near, the player walks back from it (aimed,
            // on dry ground) to 2.2 tiles, as a player would, and it gets up or takes the shot
            if (stepBack && target != null && target.isOnFloor() && !reloading && nearestStandingD > 2.5F * 2.5F) {
               float bx = px - target.getX(), by = py - target.getY(), bl = (float)Math.hypot(bx, by);
               if (bl < 2.2F && stepBackDir(p, bx, by)) {
                  stepBackNs = nowNs;
                  steps++;
               }
            }
         }
         case "back_off" -> { // run away from the horde (the opposite of horde_dir), up to back_off tiles; the game cannot run while aiming
            aimOff();
            if (backOffMax > 0F && toPier < backOffMax && !reloading) {
               moveKeys(-dirX, -dirY);
               holdKey("Run");
               p.setForceRun(true);
            }
         }
         case "reload" -> {
            aimOff();
            if (!reloading) reload(p, nowNs);
         }
         default -> aimOff(); // hold
      }
      boolean inPlace = hordeWhenReady && !hordeSpawned && firesLit && toPier <= 0.8F && !p.isPlayerMoving() && offFacing <= 8F && (nowNs - startNs) / 1e9 >= hordeAfterS;
      if (!inPlace) readySinceNs = 0L;
      else if (readySinceNs == 0L) readySinceNs = nowNs;
      if (inPlace) {
         boolean ready = "shoot".equals(command) && p.isAiming() && !reloading && gun != null && (gun.getCurrentAmmoCount() > 0 || gun.isRoundChambered());
         boolean timeout = nowNs - readySinceNs >= 3_000_000_000L; // never a deadlock: 3 s in place without aiming spawns it anyway
         if (ready || timeout) {
            Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %s at +%.1f s (at the pier, stopped, facing %.0f deg off, aiming %b, %d rounds, joypad %d, right button %b, re-presses %d); the horde comes",
                  ready ? "ready to fire" : "3 s in place without aiming", (nowNs - startNs) / 1e9, offFacing, p.isAiming(), gun == null ? -1 : gun.getCurrentAmmoCount(),
                  p.getJoypadBind(), zombie.input.Mouse.isButtonDown(1), repress));
            spawnHorde(p);
         }
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, bestD, toPier, offFacing, reloading);
      }
      if (gun != null) {
         int ammo = gun.getCurrentAmmoCount();
         if (gun != lastAmmoGun) lastAmmo = -1; // another weapon in the hands: its rounds are its own
         if (lastAmmo >= 0 && ammo < lastAmmo) {
            shots += lastAmmo - ammo;
            lastShotNs = nowNs;
            roundFired(nowNs);
         }
         lastAmmo = ammo;
         lastAmmoGun = gun;
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: horde %d of %d left (%d killed, %d vanished, %d re-pathed), by distance %s, on the floor %d, in hands %s, wanted %s, aim %d,%d re-presses %d, pinned %d, held off %d, woken %d, step-back frames %d, target %s, fire %b, rounds at head/body/none %d/%d/%d, head z %.2f, jams %d",
               zombies.size(), hordeTotal, kills(), vanished, repaths, bands(p), onFloor(), inHand == null ? "-" : inHand.getType(), wanted == null ? "-" : wanted.getType(), aimXA, aimYA, repress, pinned, heldOff, woken, steps, targetInfo, fireDown, headshots, bodyShots, blindShots, headZ, jams));
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %s (%d director commands), %s, %d switches, %d zombies left, nearest %.1f tiles, to pier %.1f, aiming %b, rounds %d, ammo %d, reloads %d, spare mags %d, actions %d, strangers removed %d, light reattached %d, run %s | state %s action %s",
               command, commands, gun == null ? "no gun" : gun.getType(), switches, zombies.size(), bestD == Float.MAX_VALUE ? -1.0 : Math.sqrt(bestD), Math.hypot(pierX - p.getX(), pierY - p.getY()), p.isAiming(), shots, gun == null ? -1 : gun.getCurrentAmmoCount(), reloads,
               spareMags(p), p.getCharacterActions().size(), strangersRemoved, reattached, lastPath,
               p.getCurrentState() == null ? "-" : p.getCurrentState().getClass().getSimpleName(), p.getActionStateName()));
      }
   }

   private static int spareMags(IsoPlayer p) {
      if (gun == null) return 0;
      if (gun.getMagazineType() == null) { // a revolver: loose rounds
         String ammo = gun.getAmmoType() != null ? gun.getAmmoType().getItemKey() : null;
         return ammo == null ? 0 : p.getInventory().getCountTypeRecurse(ammo);
      }
      int n = 0;
      for (InventoryItem it : p.getInventory().getItemsFromFullType(gun.getMagazineType())) {
         if (it.getCurrentAmmoCount() > 0) n++;
      }
      return n;
   }

   private static void aimOff() {
      aimOverride = false;
      holdButtons = false;
      fireDown = false;
   }

   /** The movement keys toward (dx, dy) in world tiles: the keys move screen-relative (up = north-west). */
   static void moveKeys(float dx, float dy) {
      float sx = dx - dy, sy = dx + dy, len = (float)Math.hypot(sx, sy);
      if (len < 1e-3F) return;
      sx /= len;
      sy /= len;
      if (sy < -0.38F) holdKey("Forward");
      if (sy > 0.38F) holdKey("Backward");
      if (sx < -0.38F) holdKey("Left");
      if (sx > 0.38F) holdKey("Right");
   }

   /** Without a director: run to the pier, face the horde, light the fire line, shoot, reload when empty. */
   private static void autopilot(IsoPlayer p, float toPier, float offFacing, boolean reloading) {
      String next = command;
      if (!firesLit && toPier > 0.8F && !"face_horde".equals(command)) next = "run_to_pier";
      else if (!firesLit && offFacing > 8F) next = "face_horde";
      else if (!firesLit) next = "light_fire_line";
      // the gun is empty, or the hands do not hold the weapon the scene wants yet (an equip under way or refused)
      else if (needsReload(p)) next = "reload";
      else if (!reloading) next = "shoot";
      if (!next.equals(command)) {
         command = next;
         Log.info("harness: showcase autopilot: " + command);
      }
   }

   /** a revolver has no magazine or chamber to check: empty when its cylinder is */
   private static boolean empty(HandWeapon w) {
      return w.getCurrentAmmoCount() == 0 && (w.getMagazineType() == null || !w.isRoundChambered());
   }

   private static boolean needsReload(IsoPlayer p) {
      return gun != null && (empty(gun) || wanted != null && p.getPrimaryHandItem() != wanted);
   }

   /**
    * A reload or an equip is under way: the game lists it, the Lua queue still holds one, or one was issued less than
    * 0.5 s ago (the eject and the insert of an automatic reload are two actions; between them the list is empty for a
    * frame, and the parent session's pass counted one reload twice that way and drew the Magnum at the first reload).
    */
   private static boolean busy(IsoPlayer p, long nowNs) {
      if (!p.getCharacterActions().isEmpty() || nowNs < pendingUntilNs) return true;
      try {
         Object q = zombie.Lua.LuaManager.env.rawget("ISTimedActionQueue");
         Object queues = q instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("queues") : null;
         Object mine = queues instanceof se.krka.kahlua.vm.KahluaTable t2 ? t2.rawget(p) : null;
         Object list = mine instanceof se.krka.kahlua.vm.KahluaTable t3 ? t3.rawget("queue") : null;
         return list instanceof se.krka.kahlua.vm.KahluaTable t4 && t4.len() > 0;
      } catch (Throwable t) {
         return false;
      }
   }

   private static int kills() {
      return Math.max(0, hordeTotal - zombies.size() - vanished);
   }

   private static String bands(IsoPlayer p) {
      int a = 0, b = 0, c = 0, d = 0;
      for (IsoZombie z : zombies) {
         double r = Math.hypot(z.getX() - p.getX(), z.getY() - p.getY());
         if (r < 2) a++;
         else if (r < 6) b++;
         else if (r < 15) c++;
         else d++;
      }
      return "<2:" + a + " 2-6:" + b + " 6-15:" + c + " >15:" + d;
   }

   private static int onFloor() {
      int n = 0;
      for (IsoZombie z : zombies) {
         if (z.isOnFloor()) n++;
      }
      return n;
   }

   private static final java.util.Map<IsoZombie, float[]> lastSeen = new java.util.IdentityHashMap<>();

   /**
    * Once a second: a horde zombie that has stood still for 5 s more than 3 tiles from the player is sent at the player
    * again (the bench ends when the horde is dead, so none may stay stuck).
    */
   private static void repathStragglers(IsoPlayer p) {
      float t = (System.nanoTime() - startNs) / 1e9F;
      for (IsoZombie z : zombies) {
         if (z.isFakeDead()) {
            // knocked down, it chose to play dead (the game's fake-dead zombies wait for a passer-by): shots do not
            // finish it, so the last of mac-bench-hd12m lay 9 tiles out through 90 rounds and 20 s. Woken, it gets up
            z.setFakeDead(false);
            woken++;
         }
         if (z.isOnFloor()) continue;
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
         float r = (float)Math.hypot(dx, dy);
         float[] seen = lastSeen.get(z);
         boolean stuck = false;
         if (seen == null || Math.hypot(z.getX() - seen[0], z.getY() - seen[1]) > 0.5F) {
            lastSeen.put(z, new float[] {z.getX(), z.getY(), t});
         } else if (t - seen[2] >= 5F && r > 3F) {
            stuck = true;
            seen[2] = t;
         }
         if (stuck) { // stood still 5 s more than 3 tiles out (every second for all that lost the target: 1,418 re-paths, a slower horde)
            try {
               z.setTarget(p);
               z.pathToCharacter(p);
               repaths++;
            } catch (Throwable ignored) {
            }
         }
      }
   }
   private static void readCommand() {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Set.of("run_to_pier", "face_horde", "light_fire_line", "shoot", "back_off", "reload", "hold").contains(c)) return;
         if (!c.equals(command)) {
            commands++;
            Log.info("harness: showcase director: " + command + " -> " + c + " (#" + seq + ")");
         }
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   /** The scene's facts for the director (Jev), plain numbers and flags, written atomically. */
   private static void writeState(IsoPlayer p, float bestD, float toPier, float offFacing, boolean reloading) {
      int near6 = 0, burning = 0;
      for (IsoZombie z : zombies) {
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
         if (dx * dx + dy * dy < 36F) near6++;
         if (z.isOnFire()) burning++;
      }
      String json = String.format(java.util.Locale.ROOT,
            "{\"t\":%d,\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"distance_to_pier_tiles\":%.1f,\"at_pier\":%b,\"moving\":%b,"
                  + "\"degrees_between_facing_and_nearest_zombie\":%.0f,\"aiming\":%b},\"horde\":{\"zombies_alive\":%d,\"nearest_zombie_tiles\":%.1f,"
                  + "\"zombies_within_6_tiles\":%d,\"zombies_burning\":%d},\"weapon\":{\"rounds_in_magazine\":%d,\"round_chambered\":%b,\"spare_full_magazines\":%d,"
                  + "\"reloading\":%b,\"rounds_fired\":%d,\"in_hands\":\"%s\",\"hands_hold_wanted_weapon\":%b,\"sidearm_drawn\":%b},\"fire_line_lit\":%b,\"horde_spawned\":%b,"
                  + "\"horde_comes_when_aiming\":%b,\"zombies_killed\":%d,\"back_off_allowed\":%b,\"back_off_room_tiles\":%.1f}",
            System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9, command, toPier, toPier <= 0.8F, p.isPlayerMoving(), offFacing, p.isAiming(),
            zombies.size(), bestD == Float.MAX_VALUE ? -1F : Math.sqrt(bestD), near6, burning, gun == null ? 0 : gun.getCurrentAmmoCount(),
            gun != null && gun.isRoundChambered(), spareMags(p), reloading, shots, gun == null ? "-" : gun.getType(), wanted == null || p.getPrimaryHandItem() == wanted,
            sidearmDrawn, firesLit, hordeSpawned, hordeWhenReady, kills(), backOffMax > 0F,
            Math.max(0F, backOffMax - toPier));
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: showcase: state write failed: " + e);
      }
   }

   /**
    * The reload command (autopilot or director), called while no action is under way. The hands first get the weapon
    * the scene wants (an equip the game refused or dropped is issued again). Then, with the gun empty: the primary's
    * sidearm_at-th empty magazine draws the loaded sidearm instead of a reload; the sidearm empty goes back to the primary
    * (the next call reloads it); otherwise the game's own automatic reload.
    */
   private static void reload(IsoPlayer p, long nowNs) {
      if (nowNs - lastFightNs < 350_000_000L || p.isAiming() && nowNs - lastFightNs < 1_000_000_000L) {
         // the aim down first, and 0.35 s of it: an eject issued in the frames the aim came down came to nothing and was
         // issued again 1.2 s later, at every one of the 9 reloads of mac-bench-hd4m
         return;
      }
      if (wanted != null && p.getPrimaryHandItem() != wanted) {
         if (switchTries < 3) {
            switchTo(p, wanted, nowNs, true);
         } else { // three equip actions came to nothing: the plain hand swap
            switchTo(p, wanted, nowNs, false);
         }
         return;
      }
      switchTries = 0;
      if (gun == null || !empty(gun) && gun.getCurrentAmmoCount() >= gun.getMaxAmmo()) return;
      if (gun == primary && empty(gun) && sidearm != null && !sidearmDrawn && primaryEmpties >= sidearmAt && sidearm.getCurrentAmmoCount() > 0) {
         sidearmDrawn = true;
         wanted = sidearm;
         switchTo(p, sidearm, nowNs, true);
         return;
      }
      if (gun == sidearm && empty(gun) && primary != null) {
         wanted = primary;
         switchTo(p, primary, nowNs, true);
         return;
      }
      automaticReload(p, nowNs);
   }

   /** The game's equip action (the animation, both hands for a rifle), or the plain hand swap (asked, or Lua refuses it). */
   private static void switchTo(IsoPlayer p, HandWeapon w, long nowNs, boolean action) {
      boolean twoHands = w.isTwoHandWeapon() || w.isRequiresEquippedBothHands();
      boolean queued = false;
      switchTries++;
      try {
         Object cls = zombie.Lua.LuaManager.env.rawget("ISEquipWeaponAction");
         Object q = zombie.Lua.LuaManager.env.rawget("ISTimedActionQueue");
         Object neu = cls instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("new") : null;
         Object add = q instanceof se.krka.kahlua.vm.KahluaTable t2 ? t2.rawget("add") : null;
         if (action && neu != null && add != null) {
            Object[] r = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, neu, cls, p, w, 50.0, true, twoHands);
            if (r != null && r.length > 1 && Boolean.TRUE.equals(r[0]) && r[1] != null) {
               zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, add, r[1]);
               queued = true;
            }
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase: equip action failed: " + t);
      }
      if (!queued) {
         p.setPrimaryHandItem(w);
         p.setSecondaryHandItem(twoHands ? w : null);
         p.resetEquippedHandsModels();
      }
      pendingUntilNs = nowNs + 500_000_000L;
      if (switchTries == 1) switches++; // a retry of the same switch is not another switch
      Log.info("harness: showcase: switching to " + w.getFullType() + " (" + w.getCurrentAmmoCount() + " rounds, " + (queued ? "equip action" : "hand swap")
            + (switchTries > 1 ? ", try " + switchTries : "") + ", primary magazines emptied " + primaryEmpties + ")");
   }

   /** The game's own automatic reload (the reload key's action): eject, insert a full magazine, rack. */
   private static void automaticReload(IsoPlayer p, long nowNs) {
      if (gun == null || spareMags(p) == 0 || gun.getCurrentAmmoCount() >= gun.getMaxAmmo()) return;
      try {
         Object cls = zombie.Lua.LuaManager.env.rawget("ISReloadWeaponAction");
         Object fn = cls instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("BeginAutomaticReload") : null;
         if (fn != null) {
            zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, fn, p, gun);
            pendingUntilNs = nowNs + 1_200_000_000L; // the eject starts late out of the aim (bench-hd2 issued a reload twice at 0.5 s)
            reloads++;
            Log.info("harness: showcase: reload " + reloads + " of " + gun.getType() + " (" + gun.getCurrentAmmoCount() + " rounds, primary magazines emptied " + primaryEmpties + ")");
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase: reload failed: " + t);
      }
   }

   /** the bubble's edge on the zombie's bearing is dry ground (never into the river beside the pier) */
   private static boolean bubbleSpotDry(IsoPlayer p, float dx, float dy, float d) {
      float r = (float)Math.sqrt(d);
      float ux = r > 1e-3F ? dx / r : -dirX, uy = r > 1e-3F ? dy / r : -dirY;
      return dry(IsoWorld.instance.currentCell.getGridSquare((int)Math.floor(p.getX() + ux * bubble), (int)Math.floor(p.getY() + uy * bubble), (int)p.getZ()));
   }

   /** Holds the movement keys for the dry direction (of 8) nearest to away (bx, by) one tile out; false when none is dry. */
   private static boolean stepBackDir(IsoPlayer p, float bx, float by) {
      float bl = (float)Math.hypot(bx, by);
      if (bl < 1e-3F) {
         bx = -dirX;
         by = -dirY;
         bl = 1F;
      }
      float best = -2F, ux = 0F, uy = 0F;
      for (int k = 0; k < 8; k++) {
         float cx = (float)Math.cos(Math.PI / 4 * k), cy = (float)Math.sin(Math.PI / 4 * k);
         float dot = (cx * bx + cy * by) / bl;
         if (dot <= best || dot < 0F) continue;
         if (!dry(IsoWorld.instance.currentCell.getGridSquare((int)Math.floor(p.getX() + cx), (int)Math.floor(p.getY() + cy), (int)p.getZ()))) continue;
         best = dot;
         ux = cx;
         uy = cy;
      }
      if (best < 0F) return false;
      moveKeys(ux, uy);
      return true;
   }

   /**
    * no_jam (default with horde_when=ready, the maintainer 2026-10-18): the weapon cannot jam (HandWeapon.checkJam
    * rolls jamGunChance, 0 returns before the roll) and stays at full condition (the roll grows as it wears); a jam that
    * happens anyway is counted and cleared.
    */
   private static void noJam(HandWeapon w) {
      if (w == null) return;
      if (noJamFlag) {
         if (w.getJamGunChance() != 0F) w.setJamGunChance(0F);
         if (w.getCondition() < w.getConditionMax()) w.setCondition(w.getConditionMax());
      }
      if (w.isJammed()) {
         jams++;
         w.setJammed(false);
         Log.info("harness: showcase: " + w.getType() + " jammed (jam " + jams + "), cleared");
      }
   }

   /** the largest t in [0, 1] keeping a + t (b - a) inside [lo, hi] (a itself inside) */
   private static float clampT(float a, float b, float lo, float hi) {
      if (a < lo || a > hi) return 1F; // the player off the box (a tiny window): no clamp
      if (b > hi) return (hi - a) / (b - a);
      if (b < lo) return (lo - a) / (b - a);
      return 1F;
   }

   private static void fight(IsoPlayer p, long nowNs, float tx, float ty, boolean inRange, boolean reloading, boolean prone, IsoZombie tgt) {
      if (gun == null || reloading) {
         aimOff();
         return;
      }
      lastFightNs = nowNs;
      if (turnToTarget) {
         // the facing turned onto the target as the face_horde action does (720 deg/s): with the horde held round the player
         // the aimed facing stopped following the mouse and stayed 45 deg off the last 5 for 25 s (mac-bench-hd8m)
         float want = (float)Math.toDegrees(Math.atan2(ty - p.getY(), tx - p.getX()));
         float diff = ((want - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F;
         float step = 720F * Math.min(0.1F, Math.max(0F, (nowNs - lastTurnNs) / 1e9F));
         p.setDirectionAngle(p.getDirectionAngle() + Math.max(-step, Math.min(step, diff)));
      }
      lastTurnNs = nowNs;
      float ax = tx - p.getX(), ay = ty - p.getY(), ad = (float)Math.hypot(ax, ay);
      if (ad < (aimedFire ? 0.6F : 2F) && (!prone || ad < 0.3F)) { // a zombie at the player's feet: the aim point 2 tiles out on the same bearing, not on the player (one on the floor: on it)
         if (ad < 1e-3F) {
            ax = dirX;
            ay = dirY;
            ad = 1F;
         }
         tx = p.getX() + ax / ad * 2F;
         ty = p.getY() + ay / ad * 2F;
      }
      float zoom = zombie.core.Core.getInstance().getZoom(0);
      // the aim follows the game's mouse. IsoPlayer.calculateAimVector projects the mouse onto the plane at the player's
      // chest height (getAimOriginPosZ), not the floor: the target is projected at that height too. On the floor the
      // aim was off by the chest height's ~1.5 tiles of iso shift, nothing for a zombie 10 tiles out, 45-70 degrees for
      // one at arm's length (the last 5 of mac-bench-hd8m / hd9m lived through 25 s of fire that way)
      // A zombie on the floor is aimed at on the floor: the game's BallisticsController casts the camera ray through the
      // reticle for a body to hit and pitches the shot at it, and the ray through the chest-height point passes above a
      // body lying flat (the last zombie, on the ground 1-2.4 tiles off, took 100 rounds and lived: flip-bench-hdC1);
      // with that body hit, the facing follows the ballistics target, not the chest-height projection
      float az = p.getAimOriginPosZ();
      float tz = prone ? p.getZ() + 0.05F : az;
      if (aimedFire && tgt != null) {
         if (tgt != headTarget) { // a new target: the head height starts from the standing head, then follows what the reticle hits
            headTarget = tgt;
            sweep = 0;
            headZ = HEAD_Z;
            targetSinceNs = nowNs;
         }
         if (!prone) tz = tgt.getZ() + headZ;
      }
      float sx = (zombie.iso.IsoUtils.XToScreen(tx, ty, tz, 0) - zombie.iso.IsoCamera.getOffX(0)) / zoom;
      float sy = (zombie.iso.IsoUtils.YToScreen(tx, ty, tz, 0) - zombie.iso.IsoCamera.getOffY(0)) / zoom;
      float px = (zombie.iso.IsoUtils.XToScreen(p.getX(), p.getY(), az, 0) - zombie.iso.IsoCamera.getOffX(0)) / zoom;
      float py = (zombie.iso.IsoUtils.YToScreen(p.getX(), p.getY(), az, 0) - zombie.iso.IsoCamera.getOffY(0)) / zoom;
      // pulled toward the player along the same line into the middle of the window: on a 1080p screen a target 10+ tiles
      // south-east lies on the hotbar, which took the right press as a UI click and the player never aimed (the flip,
      // 2026-10-18, bench-hd1); the direction, which is what the aim uses, stays the same
      float w = zombie.core.Core.getInstance().getScreenWidth(), h = zombie.core.Core.getInstance().getScreenHeight();
      float k = 1F;
      k = Math.min(k, clampT(px, sx, 0.12F * w, 0.88F * w));
      k = Math.min(k, clampT(py, sy, 0.12F * h, 0.72F * h));
      aimXA = Math.round(px + (sx - px) * k);
      aimYA = Math.round(py + (sy - py) * k);
      aimOverride = true;
      if (holdButtons && !p.isAiming() && nowNs - holdSinceNs > 700_000_000L) {
         // held 0.7 s without aiming: a UI element may have taken the press (Mouse.uiCaptured stays set until the button
         // is let go), so let go for a frame and press again
         holdButtons = false;
         fireDown = false;
         repress++;
         return;
      }
      if (!holdButtons) holdSinceNs = nowNs;
      holdButtons = true;
      if (!aimedFire) {
         fireDown = inRange && (nowNs - startNs) / 1_000_000L % fireMs < fireMs / 2;
         return;
      }
      aimedTrigger(p, nowNs, inRange, prone, tgt);
   }

   /** head height above the target's feet, in levels (a standing zombie's head; the reticle's body part corrects it) */
   private static final float HEAD_Z = 0.64F;
   private static boolean aimedFire, noJamFlag;
   private static IsoZombie headTarget, shotTarget;
   private static float headZ = HEAD_Z;
   private static final float[] SWEEP = {0F, -0.06F, 0.06F, -0.12F, 0.12F, -0.18F, -0.24F};
   private static int sweep;
   private static long targetSinceNs, pressNs, releaseUntilNs;
   private static int shotPart = -1, headshots, bodyShots, blindShots, jams;
   private static final java.util.Map<IsoZombie, Long> shotAt = new java.util.IdentityHashMap<>();

   /**
    * aimed=true (the horde bench, 2026-10-18, the maintainer: one aimed bullet per zombie, at the head): single fire, one
    * press per round. The press waits for the reticle to be on the target's head: the game's BallisticsController casts
    * the camera ray through the reticle and names the body part it hits (getTargetedBodyPart), which is the part the shot
    * takes (CombatManager.processHit); the head height follows it (a chest hit raises it, a miss over the head lowers
    * it). Any zombie's head under the reticle takes the round; else the target's body after 0.15 s on it, else whatever after
    * 0.4 s. A zombie a round went at is left alone for 0.4 s (did it go down?) while another can be shot.
    */
   private static void aimedTrigger(IsoPlayer p, long nowNs, boolean inRange, boolean prone, IsoZombie tgt) {
      int part = HEAD_NONE;
      IsoZombie headHit = null; // any horde zombie whose head the reticle is on (the round goes to what the ray hits first)
      boolean anyHit = false;
      try {
         zombie.core.physics.BallisticsController bc = p.getBallisticsController();
         if (bc != null) {
            if (tgt != null) part = bc.getTargetedBodyPart(tgt.getID());
            if (part == HEAD_PART) headHit = tgt;
            for (int i = 0; headHit == null && i < zombies.size(); i++) {
               IsoZombie z = zombies.get(i);
               int zp = bc.getTargetedBodyPart(z.getID());
               if (zp != HEAD_NONE) anyHit = true;
               if (zp == HEAD_PART) headHit = z;
            }
            anyHit |= part != HEAD_NONE;
         }
      } catch (Throwable ignored) {
      }
      if (!prone && tgt != null && tgt == headTarget) {
         // no head under the reticle: the next height of the sweep the next frame (the reticle's ray is cast once a
         // frame); a head found keeps its height for the target. A single height adapted by steps drifted to the
         // bottom of its range while the zombies lunged and 61 of 184 rounds went with nothing under the reticle
         // (flip-bench-hdE3)
         if (headHit == null) sweep = (sweep + 1) % SWEEP.length;
         headZ = HEAD_Z + SWEEP[sweep];
      }
      if (fireDown) { // pressed: held until the round goes (the ammo counter in tick) or 150 ms
         if (nowNs - pressNs > 150_000_000L) {
            fireDown = false;
            releaseUntilNs = nowNs + 30_000_000L;
            shotTarget = null;
         }
         return;
      }
      boolean tap = "Auto".equalsIgnoreCase(gun.getFireMode());
      if (!inRange || tgt == null || nowNs < releaseUntilNs || !p.isAiming() || !tap && p.getRecoilDelay() > 0F) return;
      long on = nowNs - targetSinceNs;
      IsoZombie at;
      int atPart;
      if (headHit != null) {
         at = headHit;
         atPart = HEAD_PART;
      } else if (part != HEAD_NONE && (prone || on > 150_000_000L)) {
         at = tgt;
         atPart = part;
      } else if (on > 400_000_000L) {
         at = tgt;
         atPart = part;
      } else {
         return;
      }
      fireDown = true;
      pressNs = nowNs;
      shotTarget = at;
      shotPart = atPart;
   }

   private static final int HEAD_PART = zombie.core.physics.RagdollBodyPart.BODYPART_HEAD.ordinal();
   private static final int HEAD_NONE = zombie.core.physics.RagdollBodyPart.BODYPART_COUNT.ordinal();

   /** A round left the gun (tick's ammo counter): booked to the target it was pressed for. */
   private static void roundFired(long nowNs) {
      if (!aimedFire) return;
      if (shotTarget != null) {
         shotAt.put(shotTarget, nowNs);
         if (shotPart == HEAD_PART) headshots++;
         else if (shotPart == HEAD_NONE) blindShots++;
         else bodyShots++;
      }
      fireDown = false;
      releaseUntilNs = nowNs + 40_000_000L;
      shotTarget = null;
   }
}
