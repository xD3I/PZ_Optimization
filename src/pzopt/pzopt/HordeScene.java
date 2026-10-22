package pzopt;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

/**
 * The horde bench's scene for a player to play by hand (2026-10-21, the maintainer: "set me up with a save, and I'll record a
 * one minute video on how a player deals with the horde in that scene"). Off unless the loaded save's folder holds
 * {@value #FILE}; nothing of the harness runs (no flag file, no route, no autopilot, no director, no kill switch, no quit).
 *
 * <p>At world ready: the player goes to {@code scene_start} facing {@code horde_dir}, the hour is set to {@code scene_hour}
 * and the weather pinned to {@code scene_weather=clear}, the bench's kit is put on (Showcase.playKit: travel light, the rifle
 * loaded in the hands, magazines packed, the .44, boots, the ALICE pack, Aiming / Reloading 10) and the area's own zombies are
 * kept 30 tiles off. The kit is applied once per save ({@value #APPLIED} records it); a later load leaves the player where the
 * game saved them. The horde comes when the player presses {@code scene_key} (Home by default), or {@code scene_release_after}
 * seconds after the kit when that is above 0; once per load. The rest of the file is the bench's own Showcase / HordeTactics
 * keys (horde, horde_dir, horde_gap, mags, sidearm, ...), read through HarnessFlags' overlay.
 *
 * <p>Hook: Stats.frameTick (IsoChunk.update, game thread), one int compare per call when no scene is loaded.
 */
public final class HordeScene {
   public static final String FILE = "pzopt-scene.txt";
   static final String APPLIED = "pzopt-scene.applied";
   private static final int WAIT = 0, PLACING = 1, ARMED = 2, OUT = 3;

   private static int lastFrame = Integer.MIN_VALUE;
   private static IsoCell cell;
   private static boolean live;
   private static int state;
   private static long liveNs, kitNs, lastPinNs;
   private static float sx, sy, hour, releaseAfter;
   private static int sz, key, key2;
   private static boolean keyWas, key2Was, kitApplied, god, pinClear, shotTrigger;
   private static File dir;
   /** The shot trigger: the ranged weapon in the hands and its rounds (magazine + chambered) when it was taken up. */
   private static zombie.inventory.types.HandWeapon shotGun;
   private static int shotRounds;
   /** Diagnostics while armed (the maintainer's first take, 2026-10-22: "the horde didnt appear", no line said why). */
   private static final boolean[] rawWas = new boolean[256];
   private static int pressesLogged, framesArmed;
   private static long lastBeatNs;

   private HordeScene() {
   }

   /** Stats.frameTick: once per frame. */
   static void frame(int frameId) {
      if (frameId == lastFrame) {
         return;
      }
      lastFrame = frameId;
      IsoWorld w = IsoWorld.instance;
      IsoCell c = w == null ? null : w.currentCell;
      if (c != cell) {
         cell = c;
         live = false;
         if (c != null) {
            check();
         }
      }
      if (live) {
         try {
            tick(System.nanoTime());
         } catch (Throwable t) {
            live = false;
            Log.warn("horde scene: stopped: " + t);
         }
      }
   }

   private static void check() {
      File f;
      try {
         f = zombie.ZomboidFileSystem.instance.getFileInCurrentSave(FILE);
      } catch (Throwable t) {
         return;
      }
      if (f == null || !f.isFile()) {
         HarnessFlags.overlay(null);
         return;
      }
      Properties p = new Properties();
      try (FileReader r = new FileReader(f)) {
         p.load(r);
      } catch (IOException e) {
         Log.warn("horde scene: could not read " + f + ": " + e);
         return;
      }
      dir = f.getParentFile();
      String[] s = p.getProperty("scene_start", "6436,5186,0").split(",");
      sx = Float.parseFloat(s[0].trim()) + 0.5F;
      sy = Float.parseFloat(s[1].trim()) + 0.5F;
      sz = s.length > 2 ? Integer.parseInt(s[2].trim()) : 0;
      hour = Float.parseFloat(p.getProperty("scene_hour", "12").trim());
      pinClear = "clear".equalsIgnoreCase(p.getProperty("scene_weather", "clear").trim());
      key = Integer.parseInt(p.getProperty("scene_key", "199").trim()); // 199 = Home (org.lwjglx.input.Keyboard.KEY_HOME), unbound in the game
      key2 = Integer.parseInt(p.getProperty("scene_key2", "207").trim()); // 207 = End, unbound too; 0 = none
      shotTrigger = !"false".equalsIgnoreCase(p.getProperty("scene_shot", "true").trim()); // the player's first shot calls the horde too
      releaseAfter = Float.parseFloat(p.getProperty("scene_release_after", "0").trim());
      god = "true".equalsIgnoreCase(p.getProperty("scene_god", "false").trim());
      kitApplied = new File(dir, APPLIED).isFile();
      HarnessFlags.overlay(p);
      Showcase.playReset();
      Showcase.apply(); // the bench's keys: gun, mags, sidearm, horde, horde_dir, horde_gap (HordeTactics.apply too)
      live = true;
      state = WAIT;
      lastPinNs = 0L;
      liveNs = System.nanoTime();
      keyWas = key2Was = true; // a key already down at load is not a press
      shotGun = null;
      pressesLogged = framesArmed = 0;
      lastBeatNs = 0L;
      java.util.Arrays.fill(rawWas, true);
      Log.info("horde scene: " + f + (kitApplied ? " (kit applied on an earlier load: the player stays where the save left them)" : "")
            + "; the horde comes on " + triggers());
   }

   private static void tick(long nowNs) {
      IsoPlayer p = IsoPlayer.getInstance();
      if (p == null || p.getCurrentSquare() == null) {
         return;
      }
      if (pinClear && nowNs - lastPinNs >= 1_000_000_000L) {
         lastPinNs = nowNs;
         pinClear(state == WAIT);
      }
      switch (state) {
         case WAIT -> {
            if (nowNs - liveNs < 1_000_000_000L) return; // the world's first frames
            if (!kitApplied) {
               if (p.getVehicle() != null) p.getVehicle().exit(p);
               Log.info(String.format(Locale.ROOT, "horde scene: the player from %d,%d to %.1f,%.1f,%d", p.getXi(), p.getYi(), sx, sy, sz));
               p.teleportTo(sx, sy, sz);
            }
            state = PLACING;
            kitNs = nowNs;
         }
         case PLACING -> {
            IsoGridSquare at = IsoWorld.instance.currentCell.getGridSquare((int)sx, (int)sy, sz);
            boolean there = kitApplied || at != null && p.getCurrentSquare() == at;
            if (!there || nowNs - kitNs < 2_000_000_000L) {
               if (nowNs - kitNs > 30_000_000_000L) {
                  Log.warn("horde scene: the start square never loaded; the kit is put on where the player stands");
               } else {
                  return;
               }
            }
            p.setInvisible(false, true);
            p.getCheats().set(zombie.characters.CheatType.GOD_MODE, god);
            if (!kitApplied) {
               zombie.GameTime gt = zombie.GameTime.getInstance();
               gt.setLastTimeOfDay(hour % 24F);
               gt.setTimeOfDay(hour % 24F);
               Showcase.playKit(p);
               String dirName = HarnessFlags.get("horde_dir", "SE").trim().toUpperCase(Locale.ROOT);
               String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
               for (int k = 0; k < 8; k++) {
                  if (names[k].equals(dirName)) p.setDirectionAngle(45F * k);
               }
               try (FileWriter w = new FileWriter(new File(dir, APPLIED))) {
                  w.write("kit applied " + java.time.LocalDateTime.now() + "\n");
               } catch (IOException e) {
                  Log.warn("horde scene: could not write " + APPLIED + ": " + e);
               }
               kitApplied = true;
               Log.info(String.format(Locale.ROOT, "horde scene: ready at %.1f,%.1f: hour %.0f, weather %s, god mode %b; press key %d for the horde", p.getX(), p.getY(),
                     hour, pinClear ? "clear (pinned)" : "the save's", god, key));
            }
            p.setHaloNote(hint(), 255, 255, 255, 600F);
            state = ARMED;
            kitNs = nowNs;
            Log.info("horde scene: armed; the horde comes on " + triggers());
         }
         case ARMED -> {
            framesArmed++;
            Showcase.playTick(p, nowNs);
            logPresses();
            String why = null;
            boolean down = key > 0 && (zombie.input.GameKeyboard.isKeyDown(key) || zombie.input.GameKeyboard.isKeyDownRaw(key));
            if (down && !keyWas) why = "key " + keyName(key);
            keyWas = down;
            boolean down2 = key2 > 0 && (zombie.input.GameKeyboard.isKeyDown(key2) || zombie.input.GameKeyboard.isKeyDownRaw(key2));
            if (down2 && !key2Was) why = "key " + keyName(key2);
            key2Was = down2;
            if (why == null && shotTrigger && shot(p)) why = "the first shot";
            if (why == null && releaseAfter > 0F && nowNs - kitNs >= (long)(releaseAfter * 1e9)) why = "the timer (" + releaseAfter + " s)";
            if (why == null && nowNs - lastBeatNs >= 15_000_000_000L) {
               lastBeatNs = nowNs;
               Log.info(String.format(Locale.ROOT, "horde scene: armed %.0f s (%d frames), waiting for %s; %d key presses seen; player at %.1f,%.1f, aiming %b",
                     (nowNs - kitNs) / 1e9, framesArmed, triggers(), pressesLogged, p.getX(), p.getY(), p.isAiming()));
            }
            if (why != null) {
               Log.info("horde scene: release asked by " + why + " at " + String.format(Locale.ROOT, "%.1f,%.1f", p.getX(), p.getY()));
               String fail = Showcase.playRelease(p);
               if (fail == null) {
                  state = OUT;
                  Log.info("horde scene: the horde released (" + why + "), " + Showcase.playLeft() + " zombies");
               } else {
                  releaseAfter = 0F; // the timer once; the keys again
                  Log.warn("horde scene: release failed: " + fail);
                  p.setHaloNote("No horde: " + fail + ". Back to the pier, then Home or End", 255, 120, 120, 400F);
               }
            }
         }
         default -> Showcase.playTick(p, nowNs);
      }
   }

   private static String triggers() {
      return "key " + keyName(key) + (key2 > 0 ? " or " + keyName(key2) : "") + (shotTrigger ? ", the first shot" : "")
            + (releaseAfter > 0F ? ", or " + releaseAfter + " s after the kit" : "");
   }

   private static String hint() {
      return "The horde comes when you press Home or End" + (shotTrigger ? " or fire" : "") + (releaseAfter > 0F ? ", or in " + Math.round(releaseAfter) + " s" : "");
   }

   private static String keyName(int k) {
      try {
         return org.lwjglx.input.Keyboard.getKeyName(k) + " (" + k + ")";
      } catch (Throwable t) {
         return Integer.toString(k);
      }
   }

   /** The ranged weapon in the hands lost a round (fired) since it was taken up. */
   private static boolean shot(IsoPlayer p) {
      zombie.inventory.InventoryItem it = p.getPrimaryHandItem();
      zombie.inventory.types.HandWeapon w = it instanceof zombie.inventory.types.HandWeapon hw && hw.isRanged() ? hw : null;
      int rounds = w == null ? 0 : w.getCurrentAmmoCount() + (w.isRoundChambered() ? 1 : 0);
      if (w != shotGun) {
         shotGun = w;
         shotRounds = rounds;
         return false;
      }
      if (w != null && rounds < shotRounds) {
         shotRounds = rounds;
         return p.isAiming(); // a round gone while aiming: fired (not a magazine taken out)
      }
      if (w != null) shotRounds = rounds;
      return false;
   }

   /** The first 20 keys pressed while armed, raw (GameKeyboard's own state, what Lua's OnKeyPressed comes from). */
   private static void logPresses() {
      if (pressesLogged >= 20) return;
      for (int k = 1; k < rawWas.length; k++) {
         boolean d = zombie.input.GameKeyboard.isKeyDownRaw(k);
         if (d && !rawWas[k] && pressesLogged < 20) {
            pressesLogged++;
            Log.info("horde scene: key pressed " + keyName(k) + " at frame " + lastFrame + " (isKeyDown " + zombie.input.GameKeyboard.isKeyDown(k) + ")");
         }
         rawWas[k] = d;
      }
   }

   /** weather clear: what Scene's weather=clear pins (no rain, cloud or fog, a light wind); the hour's own light. */
   private static void pinClear(boolean first) {
      ClimateManager cm = ClimateManager.getInstance();
      if (cm == null) return;
      if (first) {
         cm.stopWeatherAndThunder();
         cm.setEnabledWeatherGeneration(false);
      }
      cm.getClimateFloat(ClimateManager.FLOAT_PRECIPITATION_INTENSITY).setOverride(0F, 1F);
      cm.getClimateFloat(ClimateManager.FLOAT_CLOUD_INTENSITY).setOverride(0F, 1F);
      cm.getClimateFloat(ClimateManager.FLOAT_WIND_INTENSITY).setOverride(0.1F, 1F);
      cm.getClimateFloat(ClimateManager.FLOAT_WIND_ANGLE_INTENSITY).setOverride(0F, 1F);
      cm.getClimateFloat(ClimateManager.FLOAT_FOG_INTENSITY).setOverride(0F, 1F);
      cm.getClimateFloat(ClimateManager.FLOAT_DESATURATION).setOverride(0F, 1F);
      cm.getClimateBool(ClimateManager.BOOL_IS_SNOW).setOverride(false);
   }
}
