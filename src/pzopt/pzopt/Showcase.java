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
   /** tactics=kite: targets set aside until (ns), after starve_s (1.5) of settled aim without a round at them. */
   private static final java.util.Map<IsoZombie, Long> skipUntil = new java.util.IdentityHashMap<>();
   private static IsoZombie starveZ;
   private static float starveS, starveLimit = 1.5F;
   private static int targetSkips;
   /**
    * tactics=kite: the revolver may be drawn for an empty rifle (sidearm_draw, true). h80-p23 (2026-10-22): the draw, its
    * 6 rounds and the equip back to the rifle took 7.7 s against 2.5 s for a magazine swap.
    */
   private static boolean sidearmDraw = true;
   /**
    * tactics=kite: no round with the facing more than facing_gate degrees off the target (25), and a round with nothing
    * under the reticle after blind_ms (400). The maintainer (2026-10-22): "you don't have to wait until the character
    * turns, you can shoot precisely the moment you start aiming"; facing_gate=180 blind_ms=0 body_shot_ms=0 do that.
    */
   private static float facingGate = 25F;
   private static long blindNs = 400_000_000L;
   private static boolean headOnly;
   /** tactics=kite: the live zombies within 15 tiles that are not the horde's (targets and headshots too). */
   private static final ArrayList<IsoZombie> others = new ArrayList<>();
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
   private static int hordeTotal, vanished, repaths, adopted;
   private static String endReason = "";
   private static HandWeapon lastAmmoGun;
   private static InventoryItem lastInHand;
   private static int horde, hordeGap, hordeFires, lightbar, fireMs, mags;
   private static float backOffMax, hordeAfterS, hordeMin;
   private static long readyIdleNs;
   private static float pierX, pierY;
   private static String gunType, ambulanceType, hordeDir;
   private static HandWeapon gun;
   static InventoryItem angleLight; // webbing=true or HordeTactics.angleLight (angle_light); kept lit and attached each tick
   static String angleLightSlot = "Webbing Left Walkie";
   private static BaseVehicle ambulance;
   private static int siren = 1;
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
   /**
    * body_shot_ms (150): a standing target without its head under the reticle this long takes a body round. A body round
    * mostly knocks the zombie down instead of killing it (h80-p7, 2026-10-22: 149 head / 132 body rounds for 150 kills),
    * and a zombie on the ground takes more rounds and a walk later.
    */
   private static long bodyShotNs = 150_000_000L;
   private static long lastTickNs, lastRepathNs, holdSinceNs, readySinceNs;
   private static int repress;

   static void apply() {
      HordeTactics.apply(); // tactics=kite (the horde bench fought like a player), arena_scan=
      on = "horde".equalsIgnoreCase(HarnessFlags.get("showcase", "").trim());
      if (!on) {
         return;
      }
      // tactics=kite: nothing holds the horde off or pins the player; the player keeps the range (HordeTactics)
      boolean tac = HordeTactics.on;
      horde = Integer.parseInt(HarnessFlags.get("horde", "80").trim());
      hordeGap = Integer.parseInt(HarnessFlags.get("horde_gap", "12").trim());
      hordeFires = Integer.parseInt(HarnessFlags.get("horde_fires", "9").trim());
      lightbar = Integer.parseInt(HarnessFlags.get("lightbar", "2").trim());
      siren = Integer.parseInt(HarnessFlags.get("siren", "1").trim());
      fireMs = Integer.parseInt(HarnessFlags.get("fire_ms", "110").trim());
      bodyShotNs = Long.parseLong(HarnessFlags.get("body_shot_ms", "150").trim()) * 1_000_000L;
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
      headBone = "true".equalsIgnoreCase(HarnessFlags.get("head_bone", "true").trim());
      fireTiles = Float.parseFloat(HarnessFlags.get("fire_tiles", "30").trim());
      headBoneAims = 0;
      stepBack = "true".equalsIgnoreCase(HarnessFlags.get("step_back", Boolean.toString(hordeWhenReadyFlag() && !tac)).trim());
      turnToTarget = "true".equalsIgnoreCase(HarnessFlags.get("turn_to_target", Boolean.toString(hordeWhenReadyFlag())).trim());
      bubble = Float.parseFloat(HarnessFlags.get("bubble", hordeWhenReadyFlag() && !tac ? "0.9" : "0").trim());
      pinPier = "true".equalsIgnoreCase(HarnessFlags.get("pin_pier", Boolean.toString(hordeWhenReadyFlag() && !tac)).trim()); // the horde bench: the player stays on the spot
      // pier: the horde spawns once the player stands at the pier spot (the Workshop headline shot), not at the route start
      hordeAtPier = "pier".equalsIgnoreCase(HarnessFlags.get("horde_when", "start").trim());
      // ready: once the player stands at the pier, turned to the horde side, aiming a loaded gun (the horde bench)
      hordeWhenReady = "ready".equalsIgnoreCase(HarnessFlags.get("horde_when", "start").trim());
      sidearmType = HarnessFlags.get("sidearm", "").trim();
      sidearmRounds = Integer.parseInt(HarnessFlags.get("sidearm_rounds", "60").trim());
      sidearmAt = Integer.parseInt(HarnessFlags.get("sidearm_at", "2").trim());
      starveLimit = Float.parseFloat(HarnessFlags.get("starve_s", "1.5").trim());
      sidearmDraw = !"false".equalsIgnoreCase(HarnessFlags.get("sidearm_draw", "true").trim());
      facingGate = Float.parseFloat(HarnessFlags.get("facing_gate", "25").trim());
      blindNs = (long)(Float.parseFloat(HarnessFlags.get("blind_ms", "400").trim()) * 1_000_000L);
      headOnly = "true".equalsIgnoreCase(HarnessFlags.get("head_only", "false").trim());
      adoptTiles = Float.parseFloat(HarnessFlags.get("adopt_tiles", "15").trim());
      noAlarms = "true".equalsIgnoreCase(HarnessFlags.get("no_alarms", Boolean.toString(HordeTactics.on)).trim());
      alarmsDisarmed = false;
      alarmsStopped = 0;
      racksIssued = 0;
      racking = false;
      unchamberedSinceNs = 0L;
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
      if (!HordeTactics.failReason.isEmpty()) { // tactics=kite: the kill switch (the player was hit, or died)
         endReason = HordeTactics.failReason;
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %s at +%.1f s with %d of %d horde zombies alive; the route ends now", endReason, t, zombies.size(), hordeTotal));
         return 1;
      }
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

   /** The scene ended its route as a failed pass (the kill switch): Harness writes route_status=failed. */
   public static boolean routeFailed() {
      return on && endReason.startsWith("failed");
   }

   public static String endReason() {
      return endReason;
   }

   /**
    * The hard timeout (2026-10-21): a daemon thread ends the route as failed {@code hard_timeout} s (end_cap + 20) after
    * the route start whatever the frames do: a dead player stops the chunk updates that drive Harness.onFrame, so
    * routeEnd's own end_cap never comes (hk-fight1 ran on for minutes after the death).
    */
   private static void startWatchdog() {
      if (!endWhenDead) return;
      final float hard = Float.parseFloat(HarnessFlags.get("hard_timeout", Float.toString(endCap + 20F)).trim());
      Thread t = new Thread(() -> {
         try {
            Thread.sleep((long)(hard * 1000F));
         } catch (InterruptedException e) {
            return;
         }
         Harness.hardStop(String.format(java.util.Locale.ROOT, "failed: hard timeout at %.0f s (end_cap %.0f s did not end the route; %d of %d horde zombies alive%s)", hard, endCap,
               zombies.size(), hordeTotal, HordeTactics.failReason.isEmpty() ? "" : ", " + HordeTactics.failReason));
      }, "pzopt-horde-watchdog");
      t.setDaemon(true);
      t.start();
      Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: hard timeout armed at %.0f s", hard));
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
      m.put("horde_adopted", Integer.toString(adopted));
      m.put("horde_alarms_stopped", Integer.toString(alarmsStopped));
      m.put("horde_racks_issued", Integer.toString(racksIssued));
      m.put("horde_head_bone_aims", Integer.toString(headBoneAims));
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
      HordeTactics.fields(m); // tactics=kite: hits, wounds, stuck, reflexes, the load
      return m;
   }

   /** Lines for pzopt-bench.out: the horde's end and, with tactics=kite, the fight's damage / stuck / load facts. */
   static String summary() {
      if (!on) return "";
      StringBuilder b = new StringBuilder();
      for (java.util.Map.Entry<String, String> e : benchFields().entrySet()) b.append('\n').append(e.getKey()).append('=').append(e.getValue());
      if (HordeTactics.on) {
         shotLogTick(System.nanoTime(), true);
         try {
            java.nio.file.Files.writeString(new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-shots.out").toPath(), shotLog);
         } catch (Exception e) {
            Log.warn("harness: showcase: pzopt-shots.out write failed: " + e);
         }
      }
      try { // pzopt-horde.out: the same facts for harness/horde-judge.py (run.sh copies every pzopt-*.out)
         java.nio.file.Files.writeString(new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-horde.out").toPath(), b.substring(Math.min(1, b.length())) + "\n");
      } catch (Exception e) {
         Log.warn("harness: showcase: pzopt-horde.out write failed: " + e);
      }
      return b.toString();
   }

   /** The route must not teleport the player: the scene runs them to the pier. */
   public static boolean active() {
      return on || HordeTactics.scanning();
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
      HordeTactics.scanStart(p); // arena_scan=: the scan moves the player itself
      if (!on || started) {
         return;
      }
      started = true;
      startNs = System.nanoTime();
      startWatchdog();
      try {
         p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
         p.setInvisible(false, true); // the horde sees and chases the player
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Aiming, 10);
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Reloading, 10);
         Log.info("harness: showcase: Aiming " + p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Aiming) + ", Reloading "
               + p.getPerkLevel(zombie.characters.skills.PerkFactory.Perks.Reloading));
         clearStrangers(p, 30F);
         equip(p);
         HordeTactics.defaultArena(pierX, pierY);
         HordeTactics.routeStart(p);
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
      if (HordeTactics.on) HordeTactics.travelLight(p); // tactics=kite: the save character's bags and loose items stay home
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
      // webbing=false (the default with tactics=kite, a daylight fight): no webbing belt and no angle-head light; their
      // 3.5 of weight carry seven more magazines. The light alone comes with tactics=kite through angle_light (default true,
      // HordeTactics.angleLight, after buffs' chest rig, which takes the webbing body location the belt would)
      boolean webbing = "true".equalsIgnoreCase(HarnessFlags.get("webbing", Boolean.toString(!HordeTactics.on)).trim());
      InventoryItem belt = webbing ? p.getInventory().AddItem("Base.Bag_ALICE_BeltSus_Green") : null; // provides the webbing slots
      if (belt != null && belt.canBeEquipped() != null) {
         p.setWornItem(belt.canBeEquipped(), belt);
      }
      angleLight = webbing ? p.getInventory().AddItem("Base.FlashLight_AngleHead") : null;
      if (angleLight != null) {
         angleLight.setActivated(true);
         angleLightSlot = "Webbing Left Walkie";
         p.setAttachedItem(angleLightSlot, angleLight);
      }
      // tactics=kite: the rifle's parts (rifle_parts, Base.Laser;Base.RedDot; the maintainer, 2026-10-22: "the best attachments
      // for range and accuracy"). Of the M16's mounts (media/scripts weaponpart.txt) the Laser gives +5 hit chance and -10
      // aiming time, the Red Dot -10 aiming time with the iron sights' 2-10 range; the aiming time is the ceiling of the
      // aiming-delay penalty every round pays (CombatManager.setAimingDelay), 40 -> 20. A scope only adds range
      // (CombatManager takes the better of the sightless and the sight modifier) at +5 (x2, 4-12) / +10 (x4, 8-16) aiming time
      String parts = HarnessFlags.get("rifle_parts", HordeTactics.on ? "Base.Laser;Base.RedDot" : "").trim();
      for (String part : parts.isEmpty() ? new String[0] : parts.split(";")) {
         InventoryItem it = zombie.inventory.InventoryItemFactory.CreateItem(part.trim());
         if (it instanceof zombie.inventory.types.WeaponPart wp) {
            gun.attachWeaponPart(p, wp);
         } else {
            Log.warn("harness: showcase: rifle part " + part + " is not a weapon part: " + it);
         }
      }
      if (!parts.isEmpty()) {
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: rifle parts %s: aiming time %d, hit chance %d, sight %.1f-%.1f tiles, active sight %s, weight %.2f",
               parts, gun.getAimingTime(), gun.getHitChance(), gun.getMinSightRange(p), gun.getMaxSightRange(p), gun.getActiveSight() == null ? "-" : gun.getActiveSight().getType(), gun.getActualWeight()));
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
      // the spare magazines last, as many as fit under the carry capacity (2026-10-21: 20 magazines on top of the kit put
      // the player in Heavy Load, slower than a player would ever fight a horde)
      // tactics=kite: packed one by one, each weighed by the game's own sum (IsoGameCharacter.getInventoryWeight) until the
      // next would pass 90 % of the capacity (hk-fight2: a probe of an empty magazine weighed far less than a full one,
      // 20 "fit" and the player ran in Heavy Load 4, which also takes health down to 75: BodyDamage's heavyLoadDamage)
      float limit = p.getMaxWeight() * 0.95F; // Heavy Load starts past 1.0
      // ... in the heaviest state of the fight: a weapon in the hands weighs less than one stowed, and drawing the sidearm
      // stows the rifle (hk-bench3: 11.26 of 12 at the start, 12.35 and Heavy Load 1 at the draw); the swap is weighed here
      if (HordeTactics.on && sidearm != null) {
         float w0 = p.getInventoryWeight();
         p.setPrimaryHandItem(sidearm);
         p.setSecondaryHandItem(sidearm.isTwoHandWeapon() || sidearm.isRequiresEquippedBothHands() ? sidearm : null);
         float swap = Math.max(0F, p.getInventoryWeight() - w0);
         p.setPrimaryHandItem(gun);
         p.setSecondaryHandItem(gun.isTwoHandWeapon() || gun.isRequiresEquippedBothHands() ? gun : null);
         limit -= swap;
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: the sidearm drawn (rifle stowed) weighs %.2f more: magazines packed under %.2f", swap, limit));
      }
      // pack=true (the default with tactics=kite): a worn ALICE pack (85 % weight reduction) takes the magazines the belt
      // line cannot: hk-fight4 ran dry with 5 of 150 left on 8 magazines. The game's reload finds them there
      // (HandWeapon.getBestMagazine recurses) and moves each to the hands first (ISInventoryPaneContextMenu.transferIfNeeded)
      zombie.inventory.ItemContainer bagInv = null;
      if (HordeTactics.on) HordeTactics.boots(p);
      if (HordeTactics.on) HordeTactics.desensitize(p);
      if (HordeTactics.on) HordeTactics.buffs(p);
      if (HordeTactics.on) HordeTactics.angleLight(p); // angle_light (default true): the angle head flashlight on the chest, lit
      if (HordeTactics.on && "true".equalsIgnoreCase(HarnessFlags.get("pack", "true").trim())) {
         InventoryItem pack = p.getInventory().AddItem("Base.Bag_ALICEpack");
         if (pack instanceof zombie.inventory.types.InventoryContainer bag && pack.canBeEquipped() != null) {
            p.setWornItem(pack.canBeEquipped(), pack);
            bagInv = bag.getInventory();
         }
      }
      int inPack = 0, spareWanted = mags;
      // main_mags (4): the magazines kept at hand; the rest go in the pack first (a full one weighs ~0.85 at hand, ~0.13 in
      // the pack: hk-fight5 packed 7 at hand + 3 in the pack and ran dry with 1 of 150 left). The pack is filled before the
      // hands and an at-hand one over the weight goes in the pack too: the built-in benchmark's new-world character
      // (capacity 12, not the bench save's 18) carried 4 at hand + 5 in the pack = 9 of 20 the other way round (hk-bench2)
      int atHand = Integer.parseInt(HarnessFlags.get("main_mags", "4").trim());
      for (int i = 0; magType != null && i < mags; i++) {
         InventoryItem mag = p.getInventory().AddItem(magType);
         if (mag == null) break;
         mag.setCurrentAmmoCount(mag.getMaxAmmo());
         if (HordeTactics.on && (p.getInventoryWeight() > limit || bagInv != null && i < mags - atHand)) {
            p.getInventory().Remove(mag);
            if (bagInv != null && bagInv.hasRoomFor(p, mag)) {
               bagInv.AddItem(mag);
               if (p.getInventoryWeight() <= limit) {
                  inPack++;
                  filled++;
                  continue;
               }
               bagInv.Remove(mag);
            }
            break;
         }
         filled++;
      }
      // a gun without a magazine (the JS-3T shotgun, a tube; the maintainer, 2026-10-23: "a test run with the JS-3T Tactical
      // Shotgun fully kitted"): gun_rounds (200) loose rounds at hand, one by one under the same 95 % of the capacity
      if (magType == null && gun.getAmmoType() != null) {
         int want = Integer.parseInt(HarnessFlags.get("gun_rounds", "200").trim()), got = 0;
         String ammo = gun.getAmmoType().getItemKey();
         for (; got < want; got++) {
            InventoryItem r = p.getInventory().AddItem(ammo);
            if (r == null) break;
            if (HordeTactics.on && p.getInventoryWeight() > limit) {
               p.getInventory().Remove(r);
               break;
            }
         }
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %d of %d loose %s carried (%.1f of %d)", got, want, ammo, p.getInventoryWeight(), p.getMaxWeight()));
         spareWanted = want;
         filled = got;
      }
      if (HordeTactics.on) Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %d of %d spare magazines packed (%d in the pack) under 95 %% of the capacity (%.1f of %d)", filled, spareWanted, inPack,
            p.getInventoryWeight(), p.getMaxWeight()));
      HordeTactics.magsWanted = spareWanted;
      HordeTactics.magsCarried = filled;
      HordeTactics.logWeight(p, "at the start");
      p.resetEquippedHandsModels();
      Log.info("harness: showcase: " + gunType + " loaded (" + gun.getCurrentAmmoCount() + "), " + filled + " spare " + magType + ", angle light "
            + (angleLight == null ? "missing" : "emitting " + angleLight.isEmittingLight()));
   }

   private static boolean dryGround(IsoCell cell, int cx, int cy) {
      for (int y = cy - 2; y <= cy + 2; y++) {
         for (int x = cx - 2; x <= cx + 2; x++) {
            IsoGridSquare s = cell.getGridSquare(x, y, 0);
            if (s == null || s.isWaterSquare() || !s.isSolidFloor() || s.HasTree()) return false;
         }
      }
      return true;
   }

   /**
    * Behind the pier spot (away from the horde), facing the horde, headlights and lightbar on. The most-behind spot
    * {@code ambulance_back} (6) to +3 tiles out, never on the horde's side, whose 5x5 squares are all dry ground; its box goes into
    * HordeTactics' no-go boxes: the player's steering tests squares (Nav), not vehicles, and h80-p49 (2026-10-23, the van
    * 4 tiles back) kited into it and was pinned against it by the horde.
    */
   private static void parkAmbulance() {
      IsoCell cell = IsoWorld.instance.currentCell;
      float back = Float.parseFloat(HarnessFlags.get("ambulance_back", "6").trim());
      IsoGridSquare sq = null;
      float best = Float.MAX_VALUE;
      // h80-p50: straight behind the pier spot is the river and the deck; a ring back..back+3 tiles out, never on the horde's
      // side, the most-behind dry spot first
      for (int dy = -10; dy <= 10; dy++) {
         for (int dx = -10; dx <= 10; dx++) {
            float d = (float)Math.hypot(dx, dy);
            if (d < back || d > back + 3F) continue;
            float dot = (dx * dirX + dy * dirY) / d;
            if (dot > 0.2F) continue;
            int cx = Math.round(pierX) + dx, cy = Math.round(pierY) + dy;
            float score = dot + d * 0.05F;
            if (score < best && dryGround(cell, cx, cy)) {
               best = score;
               sq = cell.getGridSquare(cx, cy, 0);
            }
         }
      }
      if (sq == null) {
         Log.warn("harness: showcase: no dry 5x5 spot behind the pier spot for the ambulance");
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
      HordeTactics.addNogo(sq.x - 2, sq.y - 2, sq.x + 2, sq.y + 2);
      Log.info("harness: showcase: " + ambulanceType + " at " + sq.x + "," + sq.y + " facing " + dir + ", headlights " + v.getHeadlightsOn() + ", lightbar " + v.hasLightbar()
            + ", siren mode " + v.getLightbarSirenMode() + " (sounding " + v.isSirenSounding() + ")");
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
      // siren (1-3, the game's three siren sounds; 0 = off): the ambulance's siren sounds through the fight (the maintainer,
      // 2026-10-23: "add back the ambulance with the siren on", "leave the siren sound on"); the game's own world sound of it
      // (BaseVehicle: a repeating 100-tile sound while SirenEffectsZombies) is left as the game makes it
      if (siren > 0 && ambulance.hasLightbar() && ambulance.getLightbarSirenMode() != siren) ambulance.setLightbarSirenMode(siren);
   }

   /** A burning horde of fast shamblers hordeGap tiles behind the player (the horde side), chasing. */
   private static void spawnHorde(IsoPlayer p) {
      hordeSpawned = true; // once, even when no ground is found
      IsoCell cell = IsoWorld.instance.currentCell;
      int gap = hordeGap;
      if (HordeTactics.on && gap < HordeTactics.releaseTiles + 4.3F) { // the 7x7 blob's nearest square stays horde_release_tiles out
         gap = (int)Math.ceil(HordeTactics.releaseTiles + 4.3F);
         Log.info("harness: showcase: horde_gap " + hordeGap + " -> " + gap + " (the horde's nearest " + HordeTactics.releaseTiles + " tiles out)");
      }
      int cx = Math.round(p.getX() + dirX * gap), cy = Math.round(p.getY() + dirY * gap);
      ArrayList<IsoGridSquare> ground = new ArrayList<>();
      for (int y = cy - 3; y <= cy + 3; y++) {
         for (int x = cx - 3; x <= cx + 3; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            float ddx = x + 0.5F - p.getX(), ddy = y + 0.5F - p.getY();
            float minD = HordeTactics.on ? Math.max(hordeMin, HordeTactics.releaseTiles) : hordeMin;
            boolean open = !HordeTactics.on || sq != null && sq.isOutside() && !sq.HasTree() && !HordeTactics.bush(sq); // tactics: out in the open (the pier's blob reached into the buildings)
            if (dry(sq) && open && ddx * ddx + ddy * ddy > minD * minD) ground.add(sq); // never on top of the player (cine-1)
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

   /**
    * no_alarms (default with tactics=kite; the maintainer, 2026-10-22: "disable house alarms during the run since some of
    * them draw zombies away"): every building's alarm is disarmed once (the game rings one when a character walks in or a
    * window breaks, AmbientStreamManager.doAlarm), and a ringing one is stopped: Alarm.update calls a 600-tile world sound
    * every frame for 49 s.
    */
   private static void silenceAlarms() {
      try {
         if (!alarmsDisarmed) {
            int n = 0;
            for (zombie.iso.BuildingDef b : IsoWorld.instance.getMetaGrid().getBuildings()) {
               if (b.isAlarmed()) {
                  b.setAlarmed(false);
                  n++;
               }
            }
            alarmsDisarmed = true;
            Log.info("harness: showcase: no_alarms: " + n + " building alarms disarmed");
         }
         zombie.AmbientStreamManager asm = (zombie.AmbientStreamManager)zombie.AmbientStreamManager.instance;
         if (!asm.alarmList.isEmpty()) {
            if (alarmInst == null) {
               alarmInst = zombie.iso.Alarm.class.getDeclaredField("inst");
               alarmInst.setAccessible(true);
            }
            long inst = alarmInst.getLong(null);
            if (inst != 0L) {
               fmod.javafmod.FMOD_Studio_EventInstance_Stop(inst, true);
               alarmInst.setLong(null, 0L);
            }
            alarmsStopped += asm.alarmList.size();
            Log.info("harness: showcase: no_alarms: " + asm.alarmList.size() + " ringing house alarm(s) stopped");
            asm.alarmList.clear();
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase: silencing alarms failed: " + t);
      }
   }

   private static boolean noAlarms, alarmsDisarmed;
   private static float adoptTiles = 15F;
   private static int alarmsStopped;
   private static java.lang.reflect.Field alarmInst;

   /** The area's own zombies near the player (they bit the player from behind in showcase-diag): removed. */
   private static void clearStrangers(IsoPlayer p, float radius) {
      try {
         // tactics=kite, once the horde is out: the area's own zombies that come near join the count and are fought, not
         // removed (the maintainer, 2026-10-22: "count all live zombies for the killcount"); membership is the live list, as a
         // dead horde zombie's object came back as one of them (h80-p33)
         boolean adopt = HordeTactics.on && hordeSpawned;
         java.util.Set<IsoZombie> live = null;
         if (adopt) {
            live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            live.addAll(zombies);
         }
         for (IsoZombie z : new ArrayList<>(IsoWorld.instance.getCell().getZombieList())) {
            if (adopt ? z == null || z.isDead() || live.contains(z) : hordeSet.contains(z)) continue;
            float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
            // inside the pier arena and within adopt_tiles (15) of the player: h80-p35 took them in at 24 tiles, eight stood 15+
            // tiles off outside the arena (drawn by the gunfire) and the player shot at the arena's edge for 60 s
            if (adopt && dx * dx + dy * dy < adoptTiles * adoptTiles
                  && (!HordeTactics.arenaSet || Math.hypot(z.getX() - HordeTactics.arenaX, z.getY() - HordeTactics.arenaY) <= HordeTactics.arenaR)) {
               zombies.add(z);
               hordeSet.add(z);
               hordeTotal++;
               adopted++;
               continue;
            }
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

   // ---------------------------------------------------------------- pzopt.HordeScene: a player's own take of the scene

   /** A new world with a scene file: nothing of a previous load's horde is kept. */
   static void playReset() {
      zombies.clear();
      hordeSet.clear();
      adopted = 0;
      lastSeen.clear();
      hordeSpawned = false;
      started = false;
      gun = primary = sidearm = wanted = null;
      angleLight = null;
   }

   /** The bench's kit (Aiming / Reloading 10, the rifle loaded in the hands, magazines, the .44, boots, the pack); no route, no autopilot. */
   static void playKit(IsoPlayer p) {
      startNs = System.nanoTime();
      p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Aiming, 10);
      p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Reloading, 10);
      clearStrangers(p, 30F);
      equip(p);
   }

   /** The horde now, horde_gap out in horde_dir from where the player stands; null when it came, else why not. */
   static String playRelease(IsoPlayer p) {
      zombies.clear();
      if (!on) return "the scene's showcase=horde is not set";
      try {
         spawnHorde(p);
      } catch (Throwable t) {
         hordeSpawned = false;
         Log.warn("horde scene: the spawn threw: " + t);
         return "the spawn failed (" + t + ")";
      }
      if (zombies.isEmpty()) {
         hordeSpawned = false; // the player may move and try again
         return "no open outdoor ground " + hordeGap + "+ tiles " + hordeDir + " of " + Math.round(p.getX()) + "," + Math.round(p.getY());
      }
      HordeTactics.released(p, zombies);
      return null;
   }

   static int playLeft() {
      return zombies.size();
   }

   /** Every frame of the scene: the horde's vector to the player kept true (no attack from 10 tiles), stragglers re-pathed, the area's own zombies kept off until the release. */
   static void playTick(IsoPlayer p, long nowNs) {
      for (int i = zombies.size() - 1; i >= 0; i--) {
         IsoZombie z = zombies.get(i);
         if (z == null || z.isDead() || z.getCurrentSquare() == null) {
            zombies.remove(i);
         } else if (z.getTarget() == p) {
            HordeTactics.aimAt(z, p);
         }
      }
      if (nowNs - lastClearNs >= 1_000_000_000L) {
         lastClearNs = nowNs;
         if (!hordeSpawned) {
            clearStrangers(p, 30F);
         } else {
            repathStragglers(p);
         }
      }
   }

   /** Every frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (HordeTactics.scanTick(p, nowNs)) {
         return; // arena_scan=: the player is moved from point to point
      }
      if (!on) {
         return;
      }
      if (nowNs - lastClearNs >= 1_000_000_000L) {
         lastClearNs = nowNs;
         clearStrangers(p, started ? 24F : 30F);
         if (noAlarms) silenceAlarms();
         if (powerOff && IsoWorld.instance.isHydroPowerOn()) IsoWorld.instance.setHydroPowerOn(false);
      }
      if (!started) {
         return;
      }
      RagdollWatch.tick(nowNs);
      lights();
      if (zombie.input.Mouse.isCursorVisible()) zombie.input.Mouse.setCursorVisible(false); // no pointer in the shot
      // tactics=kite: mortal once the horde is out (the bench measures the damage), god mode only as the safety after a
      // failed pass (HordeTactics.safetyGod) so a player's benchmark never dies
      boolean god = !HordeTactics.on || !hordeSpawned || HordeTactics.safetyGod;
      if (p.getCheats().isSet(zombie.characters.CheatType.GOD_MODE) != god) p.getCheats().set(zombie.characters.CheatType.GOD_MODE, god);
      p.getStats().set(CharacterStat.ENDURANCE, 1F);
      p.getStats().set(CharacterStat.FATIGUE, 0F);
      // tactics=kite: the character is Desensitized (HordeTactics.desensitize) and never panics, so nothing is zeroed
      if (noAttack || HordeTactics.on && !HordeTactics.desensitized) { // a crowd of 100 at arm's length panics the player, and panic and stress take off hit chance (CombatManager.getMoodlesPenalty)
         p.getStats().set(CharacterStat.PANIC, 0F);
         p.getStats().set(CharacterStat.STRESS, 0F);
      }
      if (angleLight != null) {
         if (!angleLight.isActivated()) angleLight.setActivated(true);
         if (p.getAttachedItem(angleLightSlot) != angleLight) {
            p.setAttachedItem(angleLightSlot, angleLight);
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
         Long aside = skipUntil.get(z);
         if (aside != null && nowNs < aside) cost += 15F; // set aside: the gun got no round at it
         Long shot = aimedFire ? shotAt.get(z) : null;
         if (shot != null && nowNs - shot < 400_000_000L) cost += 20F; // a round went at it: see whether it goes down first
         if (cost < bestTargetD) {
            bestTargetD = cost;
            target = z;
         }
      }
      // tactics=kite: every other live zombie within 15 tiles is a target too (the maintainer, 2026-10-22: "zombies not spawned
      // from the horde are not being shot"; h80-p33's last 25 s: 2-5 of them round the player, none shot, and the last horde
      // zombie's head behind them)
      others.clear();
      if (HordeTactics.on && hordeSpawned) {
         java.util.Set<IsoZombie> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
         live.addAll(zombies);
         for (IsoZombie z : IsoWorld.instance.getCell().getZombieList()) {
            if (z == null || z.isDead() || z.getCurrentSquare() == null || live.contains(z)) continue;
            float dx = z.getX() - p.getX(), dy = z.getY() - p.getY(), d = dx * dx + dy * dy;
            if (d > 15F * 15F) continue;
            others.add(z);
            if (d < bestD) bestD = d;
            if (!z.isOnFloor() && d < nearestStandingD) nearestStandingD = d;
            float zb = (float)Math.toDegrees(Math.atan2(dy, dx));
            float turn = Math.abs(((zb - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F);
            float cost = (float)Math.sqrt(d) + turn / 30F + (z.isOnFloor() ? 5F : 0F);
            if (z == lastTarget) cost *= 0.5F;
            Long aside = skipUntil.get(z);
            if (aside != null && nowNs < aside) cost += 15F;
            Long shot = aimedFire ? shotAt.get(z) : null;
            if (shot != null && nowNs - shot < 400_000_000L) cost += 20F;
            if (cost < bestTargetD) {
               bestTargetD = cost;
               target = z;
            }
         }
      }
      lastTarget = target;
      float tNow = (nowNs - startNs) / 1e9F;
      HordeTactics.hordeLeft = zombies.size();
      HordeTactics.observe(p, nowNs, hordeSpawned, tNow); // tactics=kite: the zombies near, hits, wounds, the load
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
      // a pump gun's rack after each shell (the game's own ISRackFirearm, queued at OnPlayerAttackFinished) is no reload: it
      // racks aimed and walking (stopOnAim / stopOnWalk false), so the aim stays up through it; counted as a reload it took
      // the aim down after every shell, the director asked for a reload, its walk-away ran and the run cancelled the rack,
      // the gun left unchambered (h80-p37, 2026-10-23: 4 shells in 74 s)
      racking = reloading && rackOnly(p);
      if (racking) reloading = false;
      if (racking) unchamberedSinceNs = 0L; // a rack under way (the game's or ours): never a second one behind it (h80-p38)
      else if (HordeTactics.on && hordeSpawned && !reloading && gun != null) rackIfNeeded(p, nowNs);
      // tactics=kite: a target the gun gets no round at with the aim settled (out of sight, or farther than blind_tiles with
      // the reticle on no body) is set aside for 4 s (h80-p22, 2026-10-22: 12 s aimed at one 13-15 tiles off behind the yards,
      // not a round fired, while one stood 6.4 tiles off; the current target's half cost kept it)
      if (HordeTactics.on && hordeSpawned && target != null && starveLimit > 0F) {
         Long last = shotAt.get(target);
         boolean waiting = "shoot".equals(command) && !reloading && p.isAiming() && p.getAimingDelay() < 8F && !HordeTactics.closingIn()
               && (last == null || nowNs - last > 400_000_000L);
         if (target != starveZ) {
            starveZ = target;
            starveS = 0F;
         }
         starveS = waiting ? starveS + dt : 0F;
         if (starveS > starveLimit) {
            skipUntil.put(target, nowNs + 4_000_000_000L);
            targetSkips++;
            HordeTactics.roundAt(target, true); // as a round without the reticle on it: three in a row walk round to it
            Log.info(String.format(java.util.Locale.ROOT, "harness: horde: target set aside %d at +%.1f s: %.1f s without a round at a zombie %.1f tiles off at %.1f,%.1f (%s)", targetSkips, tNow, starveS,
                  Math.hypot(target.getX() - px, target.getY() - py), target.getX(), target.getY(), target.getCurrentState() == null ? "-" : target.getCurrentState().getClass().getSimpleName()));
            starveS = 0F;
         }
      }
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
      // tactics=kite: the flinch (a zombie within reflex_tiles and the action is not a run) overrides the choice
      String act = HordeTactics.on && hordeSpawned ? HordeTactics.reflex(HordeTactics.opening(command, tNow), tNow) : command;
      if (!sidearmDraw && "draw_sidearm".equals(act)) act = "reload"; // sidearm_draw=false: the magazine swap instead
      if ((hordeAtPier || hordeWhenReady) && !hordeSpawned && toPier > 0.8F && !"hold".equals(act)) act = "run_to_pier"; // the spawn waits for the spot (h80-p27)
      if (HordeTactics.on && hordeSpawned) { // and its counterpart: a stale kite with the horde well back turns into shooting
         boolean canShoot = gun != null && !empty(gun) && !dryChambered(p, gun) && !reloading && (wanted == null || p.getPrimaryHandItem() == wanted);
         act = HordeTactics.instinct(act, canShoot, nowNs, tNow);
      }
      if (!"kite".equals(act) && !"reload".equals(act) && !"draw_sidearm".equals(act) && !("shoot".equals(act) && HordeTactics.closingIn())) HordeTactics.still(nowNs);
      if (HordeTactics.on && !"shoot".equals(act)) HordeTactics.backpedalEnd(nowNs);
      switch (act) {
         case "kite" -> { // run round the horde, away from every zombie near (HordeTactics.steer)
            aimOff();
            HordeTactics.steer(p, nowNs, true, "kite");
            lastPath = "kite";
         }
         case "draw_sidearm" -> { // the revolver out of the holster (the game's equip action; walking allowed)
            aimOff();
            if (sidearm != null && wanted != sidearm && sidearm.getCurrentAmmoCount() > 0) {
               wanted = sidearm;
               sidearmDrawn = true;
               switchTries = 0;
            }
            awayWhileBusy(p, nowNs, "away while drawing");
            if (!reloading) reload(p, nowNs);
         }
         case "run_to_pier" -> {
            aimOff();
            // run, and walk the last 2.5 tiles to within 0.5: since the held Run counts (h80-p26, 4.3 tiles/s) the run stopped
            // just outside the spawn's 0.8 tiles, the director read "pier 0.8" and shot at nothing for 200 s (h80-p27)
            if (toPier > 0.5F) {
               moveKeys(pierX - px, pierY - py);
               if (toPier > 2.5F) {
                  holdKey("Run");
                  p.setForceRun(true);
               }
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
            // tactics=kite: the last zombies far off or down on the ground are walked up to (hk-fight6: the last one lay
            // 24 tiles off for 50 s while 180 rounds went blind at it), and no round goes with the facing over 25 degrees off
            if (HordeTactics.on && hordeSpawned && target != null && !reloading && !HordeTactics.devStand && HordeTactics.closeIn(p, nowNs, target, tNow)) {
               aimOff();
               HordeTactics.backpedalEnd(nowNs);
               lastPath = "close in";
            } else {
               if (HordeTactics.on) HordeTactics.closeInEnd();
               boolean facingOk = !HordeTactics.on || target == null || HordeTactics.facingOff(p, target) <= facingGate;
               // fire_tiles: no round past it (a pump gun's buckshot falls off: 7 shells at 9-12 tiles, 0 kills, h80-p38)
               fight(p, nowNs, tx, ty, target != null && bestD < fireTiles * fireTiles && facingOk && !dryChambered(p, gun), reloading, target != null && target.isOnFloor(), target);
               if (HordeTactics.on && hordeSpawned) {
                  if (!reloading) HordeTactics.backpedal(p, nowNs); // walking back while aiming as the horde comes on
                  else HordeTactics.backpedalEnd(nowNs);
               }
            }
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
            awayWhileBusy(p, nowNs, "away while reloading");
            if (!reloading) reload(p, nowNs);
         }
         default -> aimOff(); // hold
      }
      boolean inPlace = hordeWhenReady && !hordeSpawned && firesLit && toPier <= 0.8F && !p.isPlayerMoving() && offFacing <= 8F && (nowNs - startNs) / 1e9 >= hordeAfterS;
      if (!inPlace) {
         readySinceNs = 0L;
         readyIdleNs = 0L;
      }
      else if (readySinceNs == 0L) readySinceNs = nowNs;
      if (inPlace && HordeTactics.on) {
         // tactics=kite: armed, loaded, kitted, idle and aimed for 0.5 s (HordeTactics.notReady), never an unaimed release;
         // 15 s in place without it releases the horde anyway, flagged NOT ready (the judge's ready_before_contact fails)
         String missing = "shoot".equals(command) ? (reloading ? "reloading" : HordeTactics.notReady(p, gun)) : "the action is " + command;
         if (!missing.isEmpty()) readyIdleNs = 0L;
         else if (readyIdleNs == 0L) readyIdleNs = nowNs;
         boolean ok = missing.isEmpty() && nowNs - readyIdleNs >= 500_000_000L;
         if (ok || nowNs - readySinceNs >= 15_000_000_000L) {
            HordeTactics.ready(p, gun, (nowNs - startNs) / 1e9F, ok, missing.isEmpty() ? "not idle 0.5 s" : missing);
            spawnHorde(p);
            HordeTactics.released(p, zombies);
         }
      } else if (inPlace) {
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
         if (lastAmmo >= 0 && ammo < lastAmmo && lastAmmo - ammo > 3) {
            // a magazine out (a reload's eject), not rounds fired: hk-fight3 booked 29 "shots" per eject (rounds per kill 9.6)
         } else if (lastAmmo >= 0 && ammo < lastAmmo) {
            shots += lastAmmo - ammo;
            lastShotNs = nowNs;
            roundFired(nowNs);
         }
         lastAmmo = ammo;
         lastAmmoGun = gun;
      }
      if (HordeTactics.on) shotLogTick(nowNs, false);
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         if (HordeTactics.on) Log.info(HordeTactics.line(tNow) + ", action " + act + ", at " + String.format(java.util.Locale.ROOT, "%.1f,%.1f, aiming delay %.1f, been moving %.1f, aiming %b, settle frames %d, running %b, sprinting %b, force run %b, run key %b",
               p.getX(), p.getY(), p.getAimingDelay(), p.getBeenMovingFor(), p.isAiming(), HordeTactics.settleFrames, p.isRunning(), p.isSprinting(), p.isForceRun(),
               zombie.input.GameKeyboard.isKeyDown("Run")));
         // the last few: where they stand and what they do (h80-p18, 2026-10-22: the last five stood still 26 tiles off for 18 s)
         if (HordeTactics.on && hordeSpawned && !zombies.isEmpty() && zombies.size() <= 8) {
            StringBuilder last = new StringBuilder();
            for (IsoZombie z : zombies) {
               last.append(String.format(java.util.Locale.ROOT, " %.1f,%.1f,%d %s%s%s%s;", z.getX(), z.getY(), (int)z.getZ(), z.getCurrentState() == null ? "-" : z.getCurrentState().getClass().getSimpleName(),
                     z.isOnFloor() ? " floor" : "", z.getTarget() == p ? " targets-player" : z.getTarget() == null ? " no-target" : " other-target", z.isOnFire() ? " burning" : ""));
            }
            Log.info("harness: horde: the last " + zombies.size() + ":" + last);
         }
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: horde %d of %d left (%d killed, %d vanished, %d re-pathed), by distance %s, on the floor %d, in hands %s, wanted %s, aim %d,%d re-presses %d, pinned %d, held off %d, woken %d, step-back frames %d, target %s, fire %b, rounds at head/body/none %d/%d/%d, head z %.2f, jams %d",
               zombies.size(), hordeTotal, kills(), vanished, repaths, bands(p), onFloor(), inHand == null ? "-" : inHand.getType(), wanted == null ? "-" : wanted.getType(), aimXA, aimYA, repress, pinned, heldOff, woken, steps, targetInfo, fireDown, headshots, bodyShots, blindShots, headZ, jams));
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %s (%d director commands), %s, %d switches, %d zombies left, nearest %.1f tiles, to pier %.1f, aiming %b, rounds %d, ammo %d, reloads %d, spare mags %d, actions %d, strangers removed %d, light reattached %d, run %s | state %s action %s",
               command, commands, gun == null ? "no gun" : gun.getType(), switches, zombies.size(), bestD == Float.MAX_VALUE ? -1.0 : Math.sqrt(bestD), Math.hypot(pierX - p.getX(), pierY - p.getY()), p.isAiming(), shots, gun == null ? -1 : gun.getCurrentAmmoCount(), reloads,
               spareMags(p), p.getCharacterActions().size(), strangersRemoved, reattached, lastPath,
               p.getCurrentState() == null ? "-" : p.getCurrentState().getClass().getSimpleName(), p.getActionStateName()));
      }
   }

   /**
    * tactics=kite, a reload or an equip with zombies inside reload_room: walk (no Run: the game's reload and equip stop on a
    * run, not on a walk) away from them while the action runs; with room, stand.
    */
   private static void awayWhileBusy(IsoPlayer p, long nowNs, String why) {
      if (HordeTactics.on && hordeSpawned && HordeTactics.nearest < HordeTactics.reloadRoom && !HordeTactics.devStand) {
         HordeTactics.steer(p, nowNs, false, why);
      } else {
         HordeTactics.still(nowNs);
      }
   }

   private static int spareMags(IsoPlayer p) {
      if (gun == null) return 0;
      if (gun.getMagazineType() == null) { // a revolver: loose rounds
         String ammo = gun.getAmmoType() != null ? gun.getAmmoType().getItemKey() : null;
         return ammo == null ? 0 : p.getInventory().getCountTypeRecurse(ammo);
      }
      return loadedMags(p.getInventory(), gun.getMagazineType());
   }

   /** Loaded magazines of a type in a container and the bags in it (the game's reload takes them from a worn pack too). */
   private static int loadedMags(zombie.inventory.ItemContainer c, String type) {
      int n = 0;
      for (InventoryItem it : c.getItems()) {
         if (type.equals(it.getFullType()) && it.getCurrentAmmoCount() > 0) n++;
         else if (it instanceof zombie.inventory.types.InventoryContainer bag && bag.getInventory() != null) n += loadedMags(bag.getInventory(), type);
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
      else if (HordeTactics.on && hordeSpawned) { // the fight, as the director is asked to play it (HordeTactics.autopilot)
         boolean sidearmReady = sidearmDraw && sidearm != null && gun == primary && sidearm.getCurrentAmmoCount() > 0;
         next = HordeTactics.autopilot(command, needsReload(p), reloading, sidearmReady, gun != null && gun == sidearm);
      }
      // the gun is empty, or the hands do not hold the weapon the scene wants yet (an equip under way or refused)
      else if (needsReload(p)) next = "reload";
      else if (!reloading) next = "shoot";
      if (!next.equals(command)) {
         command = next;
         Log.info("harness: showcase autopilot: " + command);
      }
   }

   /** a revolver has no magazine or chamber to check: empty when its cylinder is; a pump gun with its last shell chambered is not */
   private static boolean empty(HandWeapon w) {
      return w.getCurrentAmmoCount() == 0 && (w.getMagazineType() == null && !w.haveChamber() || !w.isRoundChambered());
   }

   /** a chambered gun with rounds in it and none in the chamber: the pump gun between a shell and its rack */
   private static boolean unchambered(HandWeapon w) {
      return w != null && w.haveChamber() && !w.isRoundChambered() && w.getCurrentAmmoCount() > 0;
   }

   private static boolean racking;
   private static long unchamberedSinceNs;
   private static int racksIssued;

   /** the timed-action queue holds racks only (ISRackFirearm) */
   private static boolean rackOnly(IsoPlayer p) {
      try {
         Object q = zombie.Lua.LuaManager.env.rawget("ISTimedActionQueue");
         Object queues = q instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("queues") : null;
         Object mine = queues instanceof se.krka.kahlua.vm.KahluaTable t2 ? t2.rawget(p) : null;
         Object list = mine instanceof se.krka.kahlua.vm.KahluaTable t3 ? t3.rawget("queue") : null;
         if (!(list instanceof se.krka.kahlua.vm.KahluaTable t4) || t4.len() == 0) return false;
         for (int i = 1; i <= t4.len(); i++) {
            if (!(t4.rawget(i) instanceof se.krka.kahlua.vm.KahluaTable a) || !"ISRackFirearm".equals(a.rawget("Type"))) return false;
         }
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * a rack the game queued and a run cancelled (kite) is never queued again: the gun stays unchambered. Unchambered
    * with nothing under way for 0.25 s (the game's own rack comes at the attack's end), the bot racks it, aimed.
    */
   private static void rackIfNeeded(IsoPlayer p, long nowNs) {
      if (!unchambered(gun) || p.getPrimaryHandItem() != gun || p.isRunning() || p.isSprinting()) {
         unchamberedSinceNs = 0L;
         return;
      }
      if (unchamberedSinceNs == 0L) unchamberedSinceNs = nowNs;
      if (nowNs - unchamberedSinceNs < 250_000_000L) return;
      unchamberedSinceNs = 0L;
      try {
         Object cls = zombie.Lua.LuaManager.env.rawget("ISRackFirearm");
         Object q = zombie.Lua.LuaManager.env.rawget("ISTimedActionQueue");
         Object neu = cls instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("new") : null;
         Object add = q instanceof se.krka.kahlua.vm.KahluaTable t2 ? t2.rawget("add") : null;
         if (neu == null || add == null) return;
         Object[] r = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, neu, cls, p, gun);
         if (r != null && r.length > 1 && Boolean.TRUE.equals(r[0]) && r[1] != null) {
            zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, add, r[1]);
            racksIssued++;
            Log.info("harness: showcase: rack " + racksIssued + " of " + gun.getType() + " (" + gun.getCurrentAmmoCount() + " rounds, the game's rack was cancelled)");
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase: rack failed: " + t);
      }
   }

   private static boolean needsReload(IsoPlayer p) {
      return gun != null && (empty(gun) || dryChambered(p, gun) || wanted != null && p.getPrimaryHandItem() != wanted);
   }

   /**
    * tactics=kite: the magazine is out and a round is still chambered, with a loaded spare to go in (the maintainer,
    * 2026-10-22, after watching their own takes: the bench must win on movement and handling, not aim). The magazine is
    * swapped before the chambered round goes: the game's reload is then eject + insert and skips the rack
    * (ISInsertMagazine racks only an unchambered gun; 0.5-1 s at Reloading 10), and the kept round is fired after it.
    */
   private static boolean dryChambered(IsoPlayer p, HandWeapon w) {
      return HordeTactics.on && w != null && w == gun && w.getMagazineType() != null && w.getCurrentAmmoCount() == 0 && w.isRoundChambered() && spareMags(p) > 0;
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
               if (HordeTactics.on) HordeTactics.aimAt(z, p); // its vector to the player, not a stale one (HordeTactics readiness)
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
         if (!java.util.Set.of("run_to_pier", "face_horde", "light_fire_line", "shoot", "back_off", "reload", "hold", "kite", "draw_sidearm").contains(c)) return;
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
                  + "\"reloading\":%b,\"needs_reload\":%b,\"rounds_fired\":%d,\"in_hands\":\"%s\",\"hands_hold_wanted_weapon\":%b,\"sidearm_drawn\":%b},\"fire_line_lit\":%b,\"horde_spawned\":%b,"
                  + "\"horde_comes_when_aiming\":%b,\"zombies_killed\":%d,\"back_off_allowed\":%b,\"back_off_room_tiles\":%.1f"
                  + (HordeTactics.on ? ",\"revolver_rounds\":" + (sidearm == null || !sidearmDraw ? 0 : sidearm.getCurrentAmmoCount()) + ",\"in_hands_is_revolver\":" + (gun != null && gun == sidearm) + "," + HordeTactics.stateJson() : "") + "}",
            System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9, command, toPier, toPier <= 0.8F, p.isPlayerMoving(), offFacing, p.isAiming(),
            zombies.size(), bestD == Float.MAX_VALUE ? -1F : Math.sqrt(bestD), near6, burning, gun == null ? 0 : gun.getCurrentAmmoCount(),
            gun != null && gun.isRoundChambered(), spareMags(p), reloading, needsReload(p), shots, gun == null ? "-" : gun.getType(), wanted == null || p.getPrimaryHandItem() == wanted,
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
      if (HordeTactics.on && !empty(gun) && !dryChambered(p, gun) && !(gun.getCurrentAmmoCount() <= gun.getMaxAmmo() / 3 && HordeTactics.nearest >= HordeTactics.reloadRoom)) {
         // tactics=kite: a reload is for an empty gun, or a top-up with a third or less left and the horde far; a "reload"
         // asked for a gun with 29 of 30 swapped magazines over and over (hk-fight3: 19 reloads in a row, each mag thrown in)
         return;
      }
      if (gun == primary && empty(gun) && sidearm != null && !sidearmDrawn && primaryEmpties >= sidearmAt && sidearm.getCurrentAmmoCount() > 0 && !HordeTactics.on) {
         sidearmDrawn = true;
         wanted = sidearm;
         switchTo(p, sidearm, nowNs, true);
         return;
      }
      if (HordeTactics.on && gun == primary && empty(gun) && spareMags(p) == 0 && sidearm != null) {
         // tactics=kite: the rifle's magazines are all spent: the revolver from here on (its loose rounds reload it)
         wanted = sidearm;
         sidearmDrawn = true;
         switchTo(p, sidearm, nowNs, true);
         return;
      }
      if (HordeTactics.on && gun == sidearm && empty(gun)) {
         // tactics=kite: back to the rifle while it has magazines, else the revolver's own reload
         boolean rifleLeft = false;
         if (primary != null && primary.getMagazineType() != null) {
            rifleLeft = loadedMags(p.getInventory(), primary.getMagazineType()) > 0;
         }
         if (primary != null && (rifleLeft || !empty(primary))) {
            wanted = primary;
            switchTo(p, primary, nowNs, true);
         } else {
            automaticReload(p, nowNs);
         }
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
         // head_bone: the aim on the head bone itself (CombatManager.getBoneWorldPos, z in levels as HEAD_Z), the sweep a
         // smaller step round it; a zombie on the floor took 12 rounds at its floor spot, each a body hit for 0.1 (the tail of
         // h80-p36: 61.8-74.3 s on one crawler). A bone farther than 1.2 tiles off the feet (an animation not updated) is not used
         if (headBone) {
            zombie.CombatManager.getBoneWorldPos(tgt, "Bip01_Head", headPos);
            if (Math.hypot(headPos.x - tgt.getX(), headPos.y - tgt.getY()) < 1.2F && headPos.z > tgt.getZ() - 0.1F && headPos.z < tgt.getZ() + 1F) {
               tx = headPos.x;
               ty = headPos.y;
               tz = headPos.z + (prone ? 0F : (headZ - HEAD_Z) * 0.5F);
               headBoneAims++;
            }
         }
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
      if (unchambered(gun)) inRange = false; // racking (or about to): the aim stays up, the trigger waits for the round
      if (!aimedFire) {
         fireDown = inRange && (nowNs - startNs) / 1_000_000L % fireMs < fireMs / 2;
         return;
      }
      aimedTrigger(p, nowNs, inRange, prone, tgt);
   }

   /** head height above the target's feet, in levels (a standing zombie's head; the reticle's body part corrects it) */
   private static final float HEAD_Z = 0.64F;
   private static boolean headBone;
   private static float fireTiles = 30F;
   private static final zombie.iso.Vector3 headPos = new zombie.iso.Vector3();
   private static int headBoneAims;
   private static boolean aimedFire, noJamFlag;
   private static IsoZombie headTarget, shotTarget;
   private static float headZ = HEAD_Z;
   private static final float[] SWEEP = {0F, -0.06F, 0.06F, -0.12F, 0.12F, -0.18F, -0.24F};
   private static int sweep;
   private static long targetSinceNs, pressNs, releaseUntilNs;
   private static int shotPart = -1, headshots, bodyShots, blindShots, jams;
   private static final java.util.Map<IsoZombie, Long> shotAt = new java.util.IdentityHashMap<>();

   // tactics: one line per round (pzopt-shots.out): what the trigger knew and whether that zombie was dead a second later,
   // to tune the trigger from the game's own outcome (h80, 2026-10-22: 1.6-2.3 rounds a kill, half of them without a head)
   private record Shot(long ns, IsoZombie z, float dist, int part, float aimDelay, float moving, boolean floor, float health, int near6) {}
   private static final java.util.ArrayDeque<Shot> shotsPending = new java.util.ArrayDeque<>();
   private static final StringBuilder shotLog = new StringBuilder("t dist part aim_delay moving floor health_before near6 dead_1s health_1s\n");

   private static void shotLogAdd(long nowNs) {
      IsoPlayer p = IsoPlayer.getInstance();
      IsoZombie z = shotTarget;
      if (p == null || z == null) return;
      shotsPending.add(new Shot(nowNs, z, (float)Math.hypot(z.getX() - p.getX(), z.getY() - p.getY()), shotPart, p.getAimingDelay(), p.getBeenMovingFor(), z.isOnFloor(), z.getHealth(), HordeTactics.within6));
   }

   private static void shotLogTick(long nowNs, boolean flush) {
      while (!shotsPending.isEmpty() && (flush || nowNs - shotsPending.peekFirst().ns() > 1_000_000_000L)) {
         Shot s = shotsPending.pollFirst();
         shotLog.append(String.format(java.util.Locale.ROOT, "%.2f %.2f %s %.1f %.1f %b %.2f %d %b %.2f%n", (s.ns() - startNs) / 1e9, s.dist(),
               s.part() == HEAD_PART ? "head" : s.part() == HEAD_NONE ? "none" : "body", s.aimDelay(), s.moving(), s.floor(), s.health(), s.near6(), s.z().isDead(), s.z().getHealth()));
      }
   }

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
            for (int i = 0; headHit == null && i < zombies.size() + others.size(); i++) {
               IsoZombie z = i < zombies.size() ? zombies.get(i) : others.get(i - zombies.size());
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
      if (HordeTactics.unsettled(p)) return; // tactics: the aim settles first (aiming delay, the moving penalty)
      long on = nowNs - targetSinceNs;
      IsoZombie at;
      int atPart;
      if (headHit != null) {
         at = headHit;
         atPart = HEAD_PART;
      } else if (headOnly && HordeTactics.on) {
         // head_only (the maintainer, 2026-10-22: "fire all the shots aimed as headshots, be efficient with the bullets so you
         // can cut reload time"): no body or blind round; one on the ground takes the body after 1 s without its head in reach
         if (!(prone && part != HEAD_NONE && on > 1_000_000_000L)) return;
         at = tgt;
         atPart = part;
      } else if (part != HEAD_NONE && (prone || on > bodyShotNs)) {
         at = tgt;
         atPart = part;
      } else if (on >= (HordeTactics.on ? blindNs : 400_000_000L) && !(HordeTactics.on && part == HEAD_NONE
            && Math.hypot(tgt.getX() - p.getX(), tgt.getY() - p.getY()) > HordeTactics.blindTiles)) { // tactics: no blind round far off (hk-bp9)
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
         if (HordeTactics.on) shotLogAdd(nowNs);
         if (HordeTactics.on) HordeTactics.roundAt(shotTarget, shotPart == HEAD_NONE);
         if (shotPart == HEAD_PART) headshots++;
         else if (shotPart == HEAD_NONE) blindShots++;
         else bodyShots++;
      }
      fireDown = false;
      releaseUntilNs = nowNs + 40_000_000L;
      shotTarget = null;
   }
}
