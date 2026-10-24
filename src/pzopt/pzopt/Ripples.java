package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.joml.Vector3f;
import org.joml.Vector4f;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWorld;

/**
 * Puddle ripples (2026-10-10, Enhancements tab, write-up docs/findings-puddle-ripples-2026-10-10.md): characters, animals
 * and vehicles moving through a puddle ring it. Each footstep (a stride travelled on a wet outdoor square, the two feet
 * alternating beside the walking line) and each wheel (every quarter square) starts a ring; the puddle shader adds the
 * rings' slope to its own wave normal {@code gm} (so the stock sky reflection, HDR's glints and pzopt.Ssr's mirrored scene
 * bend with them) and lets the crests catch the light, only where a puddle shows (its own mask).
 *
 * <p>Cost model: no puddles on screen (dry weather, snow, LQ puddles baked into the chunk textures) = nothing at all; wet
 * with nobody walking = nothing queued, one uniform test per puddle pixel. The evaluation modes ({@code ripplesMode},
 * each measured in docs/findings-puddle-ripples-2026-10-10.md) differ in where the ring maths happens:
 * <ul>
 *   <li>{@code loop}: every puddle pixel tests every ring on screen (a radius test, then one fetch of the ring's profile:
 *       pzopt.RipplesModel's iWave impulse response, slope over radius and age);</li>
 *   <li>{@code tiled}: the CPU bins the rings into 128 px screen tiles (up to 8 a tile, newest first); a pixel reads its
 *       tile's indices (one or two texels) and evaluates those only;</li>
 *   <li>{@code alu}: tiled, the profile's closed form (fitted to it: ripples filling a disk growing as t^0.7, shorter crests
 *       inside) instead of its texture fetch: no fetch a ring, a pow, an exp and a sin;</li>
 *   <li>{@code field}: tiled, the rings evaluated once per texel by a compute pass into a sparse atlas at half the
 *       view's resolution (only the tiles with rings), the pixel reads its tile's slot and one bilinear texel
 *       (pzopt.RipplesGpu);</li>
 *   <li>{@code sim}: a world-anchored height field (16 texels a square, toroidal) stepped by the wave equation at a fixed
 *       rate on the active tiles only, footsteps injected as impulses, the puddle's shore (the squares' puddle level) a
 *       fixed boundary that reflects the rings; the pixel reads four texels (pzopt.RipplesGpu);</li>
 *   <li>{@code world}: the rings binned once for their life in world tiles (pzopt.RipplesWorld): no per-frame binning, only
 *       the births, deaths and changed tiles; the pixel reads its world tile and its rings from a pool texture.</li>
 * </ul>
 */
public final class Ripples {
   private Ripples() {
   }

   static final int MAX = 96; // rings a frame (two uniform arrays of vec4: 768 of the 1024 fragment components GL guarantees)
   static final int TILE_PX = 128, TILES_X = 128, TILES_Y = 64; // the tile grid (px of the offscreen view)
   static final int PER_TILE = 8; // ring indices a tile: two RGBA8 texels
   static final int TILE_UNIT = 18, LUT_UNIT = 19, FIELD_UNIT = 20; // free in the puddle program (stock 0-3; Ssr 13-15, HDR 3-8, clouds 16)
   static volatile boolean failed = CoreGl.legacyMac();

   // ring model (squares, seconds): the life; the alu mode's closed form of the iWave footstep (fitted, RING_FN)
   static final float LIFE = 1.8F;
   static final float ALU_R1 = 1.12F, ALU_RP = 0.7F, ALU_R0 = 0.06F, ALU_K = 27F, ALU_CHIRP = 0.7F, ALU_VP = 0.9F, ALU_A = 0.55F, ALU_B = -1.2F;

   private static final float[] ALU_REACH = new float[129]; // aluReach over 0 .. LIFE * BIG (Math.pow per ring a frame showed: ~10 us)

   static {
      for (int i = 0; i < ALU_REACH.length; i++) {
         ALU_REACH[i] = ALU_R1 * (float)Math.pow(i / 128.0 * LIFE * 1.8, ALU_RP) + ALU_R0;
      }
   }

   /**
    * Per ring per frame, what every pixel of it would compute alike (hoisted, rip-cost11): the cull radius in world squares,
    * the amplitude (alu: the closed form's decay and fade too), the phase (alu: k v t; profile modes: the table row), 1 / size.
    */
   static void ringConstants(float[] q, int o, float age, boolean big, float amp) {
      float sc = big ? BIG : 1F, t = age / sc;
      boolean alu = phase == ALU;
      q[o] = (alu ? aluReach(t) : RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(t)) * sc;
      if (alu) {
         float x = Math.max(0F, Math.min(1F, (t - LIFE) / (LIFE * 0.65F - LIFE)));
         q[o + 1] = t >= LIFE ? 0F : amp * ALU_A * (float)Math.exp(ALU_B * t) * x * x * (3F - 2F * x);
         q[o + 2] = ALU_K * ALU_VP * t;
      } else {
         q[o + 1] = t >= LIFE ? 0F : amp;
         q[o + 2] = t / LIFE;
      }
      q[o + 3] = 1F / sc;
   }

   /** The alu mode's ring radius (squares) at age t (s): its cull radius (the table's next entry up: never short). */
   static float aluReach(float t) {
      int i = (int)Math.ceil(Math.max(0F, t) / (LIFE * 1.8F) * 128F);
      return ALU_REACH[Math.min(128, i)];
   }
   static final float BIG = 1.8F; // a vehicle wheel's ring: this much bigger in space and time
   static final float RAIN_BOOST = 0.75F; // the rings' strength x (1 + this x the puddles' rain intensity, 0..2): they would drown in the raindrop rings
   static final float SHADE_X = 0.2F, SHADE_Z = 0.5F;
   // rain rings and wakes: their slope against a light from the top of the screen, added to the puddle's final colour
   static final float LIT_X = 0.35F, LIT_Z = 0.9F, LIT_GAIN = Config.DEV_RAIN_LIT_PCT / 100F, LIT_ALPHA = 0.35F; // crest shading: how much a ring's slope towards the top of the screen brightens the puddle

   static final String[] MODES = {"loop", "tiled", "alu", "field", "sim", "world"};
   static final int LOOP = 1, TILED = 2, ALU = 3, FIELD = 4, SIM = 5, WORLD = 6;

   public static long frames, emitted, emittedWheels, maxLive, uploads, scanned;
   static final long[] cpuNsPhase = new long[MODES.length + 1], framesPhase = new long[MODES.length + 1];
   static long patchedUnits;

   public static boolean on() {
      return Config.RIPPLES && Overrides.enabled() && !failed;
   }

   public static String stats() {
      if (phaseLog.length() > 0) {
         Log.info("ripples: phases" + phaseLog);
         phaseLog.setLength(0);
      }
      StringBuilder b = new StringBuilder("frames=" + frames + " candidates=" + candidates + " dropped=" + dropped + " emitted=" + emitted + " wheels=" + emittedWheels + " maxLive=" + maxLive + " uploads=" + uploads
            + " scanned/frame=" + (frames > 0 ? scanned / frames : 0) + " mode=" + Config.RIPPLES_MODE + " patched=" + patchedUnits + (failed ? " (failed)" : ""));
      for (int p = 0; p < cpuNsPhase.length; p++) {
         if (framesPhase[p] > 0) {
            b.append(" cpu_us.").append(p == 0 ? "off" : MODES[p - 1]).append('=').append(String.format("%.2f", cpuNsPhase[p] / 1000.0 / framesPhase[p]));
         }
      }
      b.append(' ').append(RipplesWorld.stats()).append(" world_upload_kb/frame=").append(String.format("%.2f", frames > 0 ? RipplesWorld.uploadBytes / 1024.0 / frames : 0.0));
      b.append(" bin_stolen=").append(binStolen).append(" hook_calls/frame=").append(frames > 0 ? hookCalls / frames : 0);
      if (Config.DEV_RIPPLES_HOOK_TIMING && frames > 0) {
         b.append(String.format(" hook_us/frame=%.2f", hookNs / 1000.0 / frames));
      }
      b.append(String.format(" bin_us=%.2f inserts/frame=%d tile_kb/frame=%.1f", binFrames > 0 ? binNs / 1000.0 / binFrames : 0.0, binFrames > 0 ? binInserts / binFrames : 0, frames > 0 ? tileBytes / 1024.0 / frames : 0.0));
      return b.append(' ').append(RipplesGpu.stats()).append(' ').append(RainRipples.stats()).append(' ').append(Wakes.stats()).toString();
   }

   // ------------------------------------------------------------------------------------------------ sources (game thread)

   private static final int CAP = 1024;
   private static final float[] sx = new float[CAP], sy = new float[CAP], st = new float[CAP], sa = new float[CAP];
   private static final int[] sz = new int[CAP];
   private static final boolean[] sbig = new boolean[CAP];
   private static int head, live; // ring buffer: the newest at head - 1
   static double clock; // seconds of game play (paused: still)
   private static long lastNs;
   private static int lastFrame = Integer.MIN_VALUE;
   static int phase; // this frame's mode: 0 = off (dev A/B), else the mode index + 1
   static int rainPhase = RainRipples.CELLS, wakePhase = Wakes.RINGS; // this frame's raindrop and wake modes (RainRipples.MODES / Wakes.MODES indices)
   private static int phaseFrame = Integer.MIN_VALUE;

   static void emit(float x, float y, int z, float amp, boolean big) {
      emit(x, y, z, amp, big, 0F);
   }

   /** A ring born {@code ago} seconds before now (a stride found complete a few frames late keeps its true start). */
   static void emit(float x, float y, int z, float amp, boolean big, float ago) {
      int i = head;
      head = (head + 1) % CAP;
      if (live < CAP) {
         live++;
      }
      sx[i] = x;
      sy[i] = y;
      sz[i] = z;
      st[i] = (float)clock - Math.max(0F, Math.min(0.1F, ago));
      sa[i] = Math.max(0.05F, Math.min(1F, amp));
      sbig[i] = big;
      emitted++;
      RipplesGpu.impulse(x, y, z, sa[i], big);
      RipplesWorld.birth(x, y, z, sa[i], big, st[i]);
   }

   /** Is the square wet enough here for a ring (a puddle shows; the debug view's own threshold), outdoors? */
   private static boolean wet(IsoGridSquare sq) {
      return sq != null && sq.getPuddlesInGround() > 0.09F;
   }

   static int modeIndex(String m) {
      for (int i = 0; i < MODES.length; i++) {
         if (MODES[i].equals(m)) {
            return i;
         }
      }
      return ALU - 1;
   }

   /**
    * Game thread, FBORenderCell before the puddles' GPU section: the section's name for this frame's phase (dev A/B:
    * "puddles.off" / "puddles.<mode>"; plain "puddles" otherwise).
    */
   public static String puddleSection() {
      int f = IsoCamera.frameState.frameCount;
      if (f != phaseFrame) {
         phaseFrame = f;
         phase = currentPhase();
      }
      if (Config.DEV_RIPPLES_ALTERNATE <= 0 || !on()) {
         return "puddles";
      }
      if (!Config.DEV_RAIN_MODES.isEmpty()) {
         return "puddles.rain_" + RainRipples.MODES[rainPhase];
      }
      if (!Config.DEV_WAKE_MODES.isEmpty()) {
         return "puddles.wake_" + Wakes.MODES[wakePhase];
      }
      return phase == 0 ? "puddles.off" : "puddles." + MODES[phase - 1];
   }

   private static int lastLoggedPhase = -1;
   private static final StringBuilder phaseLog = new StringBuilder();

   private static int currentPhase() {
      int mode = modeIndex(Config.RIPPLES_MODE) + 1;
      if ((mode == SIM || mode == FIELD) && !RipplesGpu.supported()) {
         mode = TILED;
      }
      rainPhase = RainRipples.effective(RainRipples.modeIndex(Config.RIPPLES_RAIN));
      wakePhase = Wakes.effective(Wakes.modeIndex(Config.RIPPLES_WAKE));
      int ms = Config.DEV_RIPPLES_ALTERNATE;
      if (ms <= 0) {
         return wakePhase == Wakes.SIM ? SIM : mode; // the wakes in the sim: the footsteps run there too
      }
      if (!Config.DEV_RAIN_MODES.isEmpty() || !Config.DEV_WAKE_MODES.isEmpty()) {
         // the raindrop or wake A/B: the footsteps keep their mode, the listed modes take turns (no implicit off)
         boolean rain = !Config.DEV_RAIN_MODES.isEmpty();
         String[] list = (rain ? Config.DEV_RAIN_MODES : Config.DEV_WAKE_MODES).split("\\|");
         int k = (int)((System.currentTimeMillis() / ms) % list.length);
         String name = list[k].trim();
         int p;
         if (rain) {
            rainPhase = RainRipples.effective(RainRipples.modeIndex(name));
            p = 100 + rainPhase;
            name = "rain_" + RainRipples.MODES[rainPhase];
         } else {
            wakePhase = Wakes.effective(Wakes.modeIndex(name));
            p = 200 + wakePhase;
            name = "wake_" + Wakes.MODES[wakePhase];
            if (wakePhase == Wakes.SIM) {
               mode = SIM;
            }
         }
         if (p != lastLoggedPhase) {
            lastLoggedPhase = p;
            phaseLog.append(' ').append(name).append('@').append(System.currentTimeMillis());
            if (phaseLog.length() > 1500) {
               Log.info("ripples: phases" + phaseLog);
               phaseLog.setLength(0);
            }
         }
         return mode;
      }
      String[] list = Config.DEV_RIPPLES_MODES.isEmpty() ? new String[] {MODES[mode - 1]} : Config.DEV_RIPPLES_MODES.split("\\|");
      long slot = System.currentTimeMillis() / ms;
      int n = list.length + 1; // off, then each listed mode
      int k = (int)(slot % n);
      int p = k == 0 ? 0 : modeIndex(list[k - 1].trim()) + 1;
      if (p != lastLoggedPhase) {
         lastLoggedPhase = p;
         // batched: a console line costs ~1 ms on the game thread (it showed in the profile at 400 ms phases)
         phaseLog.append(' ').append(p == 0 ? "off" : MODES[p - 1]).append('@').append(System.currentTimeMillis());
         if (phaseLog.length() > 1500) {
            Log.info("ripples: phases" + phaseLog);
            phaseLog.setLength(0);
         }
      }
      return p;
   }

   private static Frame lastBuilt;
   private static int lastBuiltPlayer = -1;

   /**
    * Game thread, FBORenderCell.renderPuddles (each player, both puddle passes): once a frame, the new footsteps and wheel
    * rings, then this player's on-screen rings to the render thread (the frame's puddle draws follow in the stream).
    */
   public static void gather(int playerIndex) {
      if (!on() || !RipplesModel.ready()) {
         return;
      }
      long t0 = System.nanoTime();
      int f = IsoCamera.frameState.frameCount;
      boolean first = f != lastFrame;
      if (first) {
         lastFrame = f;
         frames++;
         float dt = lastNs == 0L ? 0F : Math.min(0.1F, (t0 - lastNs) / 1.0E9F);
         lastNs = t0;
         boolean paused = zombie.GameTime.isGamePaused();
         float speed = paused ? 0F : Math.min(4F, Math.max(0F, zombie.GameTime.getInstance().getTrueMultiplier()));
         dt *= speed;
         clock += dt;
         if (dt > 0F) {
            float cps = candThisFrame / dt;
            candRate += (cps - candRate) * Math.min(1F, dt * 2F); // ~0.5 s smoothing
            passP = Config.RIPPLES_PER_SEC <= 0 || candRate <= Config.RIPPLES_PER_SEC ? 1F : Config.RIPPLES_PER_SEC / candRate;
            candThisFrame = 0F;
            zombie.iso.PlayerCamera cam = IsoCamera.cameras[IsoCamera.frameState.playerIndex];
            armTs = 32F * Core.tileScale;
            armX0 = cam.getOffX();
            armY0 = cam.getOffY();
            armW = IsoCamera.frameState.offscreenWidth;
            armH = IsoCamera.frameState.offscreenHeight;
            armedFrame = phase == 0 || Config.ENTITY_UPDATE_PARALLEL ? Integer.MIN_VALUE : f;
            int nz = IsoWorld.instance.getCell() == null ? 0 : IsoWorld.instance.getCell().getZombieList().size();
            // when most footsteps would be dropped anyway, look less often (at most every 1.5 s: a walker moves ~1 square)
            float visit = Math.min(1.5F, VISIT_S / Math.max(0.27F, passP));
            hookEvery = nz <= 128 ? 1 : Math.max(1, Math.min(64, Math.round(visit / Math.max(0.001F, dt / speed))));
            scan(dt);
            if (Config.DEV_RIPPLES_STRESS > 0) {
               stress(dt);
            }
         }
         RipplesGpu.advance(dt);
         RipplesWorld.tick();
         RainRipples.advance(dt, rainPhase);
      }
      Frame fr;
      if (!first && lastBuiltPlayer == playerIndex) {
         fr = lastBuilt; // the HDR glint pass draws the puddles once more: the same rings
      } else {
         fr = build(playerIndex);
         lastBuilt = fr;
         lastBuiltPlayer = playerIndex;
      }
      long ns = System.nanoTime() - t0;
      if (first) {
         cpuNsPhase[phase] += ns;
         framesPhase[phase]++;
      } else {
         cpuNsPhase[phase] += ns;
      }
      if (fr != null) {
         SpriteRenderer.instance.drawGeneric(fr.begin);
      }
   }

   // the ring budget: candidate footsteps a second (smoothed) against Config.RIPPLES_PER_SEC; each passes with p = budget / rate
   private static float candRate, passP = 1F, candThisFrame;
   private static final java.util.SplittableRandom RNG = new java.util.SplittableRandom(0x5eed);
   public static long candidates, dropped;

   private static final ArrayList<zombie.characters.animals.IsoAnimal> animals = new ArrayList<>();
   private static int animalScan = 119, vehicleScan = 29;
   private static final ArrayList<zombie.vehicles.BaseVehicle> moving = new ArrayList<>();

   /** Characters and vehicles: a ring per stride on a wet square. */
   // the zombie hook (IsoZombie.update, game thread): armed by the last gather with its screen; replaces the zombie scan
   private static int armedFrame = Integer.MIN_VALUE;
   private static float armTs, armX0, armY0, armW, armH;
   private static int hookEvery = 1, armFrameNo; // a crowd: each zombie checks every hookEvery-th frame (its id spreads them)
   public static long hookCalls, hookNs;

   /** IsoZombie.update, after the zombie's own update (game thread unless entityUpdateParallel): its stride, if on screen. */
   public static void zombieUpdated(zombie.characters.IsoZombie z) {
      int fc = IsoCamera.frameState.frameCount;
      if (fc - armedFrame > 2 || hookEvery > 1 && (fc + z.getID()) % hookEvery != 0) {
         return; // no puddles drawn lately, ripples off, the dev A/B's off phase, or not this zombie's frame
      }
      hookCalls++;
      long t0 = Config.DEV_RIPPLES_HOOK_TIMING && (hookCalls & 255) == 0 ? System.nanoTime() : 0L; // sampled: 1 call in 256
      float x = z.getX() - z.getZ() * 3F, y = z.getY() - z.getZ() * 3F;
      float px = (x - y) * armTs - armX0, py = (x + y) * armTs * 0.5F - armY0;
      float margin = 2F * armTs;
      if (px < -margin || py < -margin || px > armW + margin || py > armH + 3F * margin) {
         if (z.pzoptRipX != 0F) {
            z.pzoptRipX = 0F; // off screen: start afresh when it comes back
            z.pzoptRipY = 0F;
         }
      } else {
         character(z);
      }
      if (t0 != 0L) {
         hookNs += (System.nanoTime() - t0) * 256;
      }
   }

   private static int zCursor;
   static final float VISIT_S = 0.4F; // a crowd's characters are each looked at this often at least (a stride's ring keeps its true start)

   private static void scan(float dt) {
      zombie.iso.IsoCell cell = IsoWorld.instance.getCell();
      if (cell == null) {
         return;
      }
      ArrayList<zombie.characters.IsoZombie> zs = cell.getZombieList();
      int n = Config.ENTITY_UPDATE_PARALLEL ? zs.size() : 0; // the zombies tell us themselves (zombieUpdated) unless they update on workers
      // many characters: each looked at every VISIT_S seconds (round robin), not every frame
      int visits = n <= 128 ? n : Math.min(n, Math.max(32, (int)Math.ceil(n * dt / VISIT_S)));
      if (zCursor >= n) {
         zCursor = 0;
      }
      zombie.iso.PlayerCamera cam = IsoCamera.cameras[IsoCamera.frameState.playerIndex];
      float ts = 32F * Core.tileScale;
      float cx0 = cam.getOffX(), cy0 = cam.getOffY();
      float cw = IsoCamera.frameState.offscreenWidth, ch = IsoCamera.frameState.offscreenHeight;
      float margin = 2F * ts;
      for (int v = 0; v < visits; v++) {
         zombie.characters.IsoZombie z = zs.get(zCursor);
         if (++zCursor >= n) {
            zCursor = 0;
         }
         float x = z.getX() - z.getZ() * 3F, y = z.getY() - z.getZ() * 3F;
         float px = (x - y) * ts - cx0, py = (x + y) * ts * 0.5F - cy0;
         if (px < -margin || py < -margin || px > cw + margin || py > ch + 3F * margin) {
            if (z.pzoptRipX != 0F) {
               z.pzoptRipX = 0F; // off screen: start afresh when it comes back
               z.pzoptRipY = 0F;
            }
            continue;
         }
         scanned++;
         character(z);
      }
      for (zombie.characters.IsoPlayer p : zombie.characters.IsoPlayer.players) {
         if (p != null) {
            character(p);
         }
      }
      // animals: no list of their own; the cell's object set (a hash set of every moving object) walked every 2 s
      if (++animalScan >= 120) {
         animalScan = 0;
         animals.clear();
         for (IsoMovingObject o : cell.getObjectList()) {
            if (o instanceof zombie.characters.animals.IsoAnimal a) {
               animals.add(a);
            }
         }
      }
      for (int i = 0; i < animals.size(); i++) {
         zombie.characters.animals.IsoAnimal a = animals.get(i);
         if (a.getCurrentSquare() != null) {
            character(a);
         }
      }
      if (Config.RIPPLES_VEHICLES) {
         // the cell's vehicles (hundreds parked in a town, each a cold object) are looked at twice a second for the moving
         // ones; those and the local players' own cars are followed every frame
         if (++vehicleScan >= 30) {
            vehicleScan = 0;
            moving.clear();
            for (zombie.vehicles.BaseVehicle v : cell.getVehicles()) {
               if (Math.abs(v.getCurrentSpeedKmHour()) > 1F) {
                  moving.add(v);
               }
            }
         }
         for (zombie.characters.IsoPlayer p : zombie.characters.IsoPlayer.players) {
            zombie.vehicles.BaseVehicle v = p == null ? null : p.getVehicle();
            if (v != null && !moving.contains(v)) {
               moving.add(v);
            }
         }
         for (int i = 0; i < moving.size(); i++) {
            vehicle(moving.get(i));
         }
      }
   }

   private static void character(zombie.characters.IsoGameCharacter c) {
      float x = c.getX(), y = c.getY();
      float lx = c.pzoptRipX, ly = c.pzoptRipY;
      float dx = x - lx, dy = y - ly;
      float d2 = dx * dx + dy * dy;
      if (d2 < 0.55F * 0.55F && (lx != 0F || ly != 0F)) {
         return; // not a stride yet (the common case)
      }
      if (lx == 0F && ly == 0F || d2 > 4F || c.getVehicle() != null) {
         c.pzoptRipX = x;
         c.pzoptRipY = y;
         c.pzoptRipT = (float)clock;
         return;
      }
      float stride = c.isRunning() || c.isSprinting() ? 0.85F : 0.55F;
      if (d2 < stride * stride) {
         return;
      }
      float d = (float)Math.sqrt(d2);
      // the foot lands where each stride ends on the walked line (whatever the frame step), the two feet beside it
      float ux = dx / d, uy = dy / d;
      int steps = Math.min(4, (int)(d / stride));
      float total = Math.max(0.016F, (float)clock - c.pzoptRipT);
      float speed = d / total; // squares a second
      float late = (d - stride * steps) / Math.max(0.1F, speed); // the last stride ended this long ago
      float amp = 0.35F + 0.65F * Math.min(1F, speed / 4F);
      if (c instanceof zombie.characters.animals.IsoAnimal) {
         amp *= 0.6F;
      }
      int z = (int)Math.floor(c.getZ());
      boolean always = c instanceof zombie.characters.IsoPlayer;
      for (int k = 1; k <= steps; k++) {
         float px = lx + ux * stride * k, py = ly + uy * stride * k;
         c.pzoptRipFoot ^= 1;
         candThisFrame++;
         candidates++;
         if (!always && passP < 1F && RNG.nextFloat() >= passP) {
            dropped++;
            continue; // over the budget: this footstep makes no ring (decided before the square lookup)
         }
         float side = c.pzoptRipFoot == 0 ? 0.09F : -0.09F;
         float fx = px - uy * side, fy = py + ux * side;
         IsoGridSquare sq = c.getCell().getGridSquare((int)Math.floor(fx), (int)Math.floor(fy), z);
         if (wet(sq)) {
            emit(fx, fy, z, amp, false, late + (steps - k) * stride / Math.max(0.1F, speed));
         }
      }
      c.pzoptRipX = lx + ux * stride * steps;
      c.pzoptRipY = ly + uy * stride * steps;
      c.pzoptRipT = (float)clock - late;
   }

   private static final Vector3f WHEEL = new Vector3f();

   private static void vehicle(zombie.vehicles.BaseVehicle v) {
      if (wakePhase != Wakes.RINGS && wakePhase != Wakes.SIM) {
         Wakes.vehicle(v, wakePhase); // a continuous wake (pzopt.Wakes)
         return;
      }
      float x = v.getX(), y = v.getY();
      float lx = v.pzoptRipX, ly = v.pzoptRipY;
      float dx = x - lx, dy = y - ly;
      float d2 = dx * dx + dy * dy;
      if (lx == 0F && ly == 0F || d2 > 64F) {
         v.pzoptRipX = x;
         v.pzoptRipY = y;
         v.pzoptRipT = (float)clock;
         return;
      }
      float elapsed = Math.max(0.016F, (float)clock - v.pzoptRipT);
      float speed = (float)Math.sqrt(d2) / elapsed; // squares a second
      // a wake, not a ring a quarter square: the spacing grows with the speed (0.06 s of travel, 0.3 .. 1.5 squares)
      float spacing = wakePhase == Wakes.SIM ? Wakes.SIM_SPACING : Math.max(0.3F, Math.min(1.5F, speed * 0.06F)); // sim: a moving source, its wake the water's own
      if (d2 < spacing * spacing) {
         return;
      }
      v.pzoptRipX = x;
      v.pzoptRipY = y;
      v.pzoptRipT = (float)clock;
      zombie.scripting.objects.VehicleScript script = v.getScript();
      if (script == null) {
         return;
      }
      float amp = 0.5F + 0.5F * Math.min(1F, speed / 8F);
      int z = (int)Math.floor(v.getZ());
      Vector3f mo = script.getModel() != null ? script.getModel().offset : null;
      // the rear wheels only: the front ones run in the same tracks
      float minZ = Float.MAX_VALUE;
      for (int w = 0; w < script.getWheelCount(); w++) {
         minZ = Math.min(minZ, script.getWheel(w).offset.z);
      }
      for (int w = 0; w < script.getWheelCount(); w++) {
         Vector3f off = script.getWheel(w).offset;
         if (off.z > minZ + 0.01F) {
            continue;
         }
         v.getWorldPos(off.x + (mo != null ? mo.x : 0F), 0F, off.z + (mo != null ? mo.z : 0F), WHEEL, script);
         IsoGridSquare sq = v.getCell().getGridSquare((int)Math.floor(WHEEL.x), (int)Math.floor(WHEEL.y), z);
         if (wet(sq)) {
            emit(WHEEL.x, WHEEL.y, z, amp, true);
            emittedWheels++;
         }
      }
   }

   // devRipplesStress=N: N walkers round the player, stepping on every square (wet or not): a fixed ring load to measure
   private static float[] walkers;

   private static void stress(float dt) {
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
      if (p == null) {
         return;
      }
      int n = Config.DEV_RIPPLES_STRESS;
      if (walkers == null || walkers.length != n * 5) {
         walkers = new float[n * 5];
         java.util.Random r = new java.util.Random(42);
         for (int i = 0; i < n; i++) {
            walkers[i * 5] = (r.nextFloat() - 0.5F) * 16F;
            walkers[i * 5 + 1] = (r.nextFloat() - 0.5F) * 16F;
            walkers[i * 5 + 2] = (float)(r.nextFloat() * Math.PI * 2);
            walkers[i * 5 + 3] = r.nextFloat() * 0.55F;
         }
      }
      for (int i = 0; i < n; i++) {
         int k = i * 5;
         float a = walkers[k + 2] + (float)Math.sin(clock * 0.7 + i) * dt * 0.8F;
         walkers[k + 2] = a;
         float step = 1.3F * dt;
         walkers[k] += (float)Math.cos(a) * step;
         walkers[k + 1] += (float)Math.sin(a) * step;
         if (Math.abs(walkers[k]) > 8F || Math.abs(walkers[k + 1]) > 8F) {
            walkers[k + 2] += (float)Math.PI;
         }
         walkers[k + 3] += step;
         if (walkers[k + 3] >= 0.55F) {
            walkers[k + 3] -= 0.55F;
            walkers[k + 4] = walkers[k + 4] == 0F ? 1F : 0F;
            float side = walkers[k + 4] == 0F ? 0.09F : -0.09F;
            emit(p.getX() + walkers[k] - (float)Math.sin(a) * side, p.getY() + walkers[k + 1] + (float)Math.cos(a) * side, (int)Math.floor(p.getZ()), 0.6F, false);
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the frame's rings

   /** One frame's on-screen rings for one player, as the render thread uploads them. */
   static final class Frame {
      final float[] src = new float[MAX * 4]; // view px x, y (game camera), age, packed level / size / amplitude
      final float[] q = new float[MAX * 4]; // per ring, hoisted from the pixels: cull radius (world squares), amplitude, phase or profile row, 1 / size
      double ox, oy; // the game camera's offset the source x / y are relative to
      int n, mode; // mode: 0 off, else the mode index + 1
      final byte[] tiles = new byte[TILES_X * TILES_Y * PER_TILE];
      final byte[] cnt = new byte[TILES_X * TILES_Y];
      final int[] dirty = new int[TILES_X * TILES_Y]; // the tiles holding rings (cleared from here next time)
      int nDirty, rowMin, rowMax;
      int tilesX, tilesY;
      // field: the tiles with rings, one atlas slot each (tile x, y, ring count, then PER_TILE indices)
      final int[] slots = new int[RipplesGpu.SLOTS * (3 + PER_TILE)];
      int nSlots;
      // sim: this frame's steps, impulses, active / cleared tiles, the shore mask when it changed
      final RipplesGpu.SimFrame sim = new RipplesGpu.SimFrame();
      // world: the pool slots and world tiles that changed since the last frame
      final WorldFrame world = new WorldFrame();
      // tiles: 0 = to bin, 1 = binning, 2 = binned (Binner / the render thread)
      final java.util.concurrent.atomic.AtomicInteger binState = new java.util.concurrent.atomic.AtomicInteger(2);
      float binW, binH, binTs;
      // the gate: no pixel outside this box does any ripple work (world / sim: true world squares; else view px)
      float bx0, by0, bx1, by1;
      final Begin begin = new Begin(this);
      final RainRipples.RainFrame rain = new RainRipples.RainFrame();
      final Wakes.WakeFrame wake = new Wakes.WakeFrame();
      final Compute compute = new Compute(this);
      boolean computeQueued;
   }

   static final class WorldFrame {
      final int[] tileIdx = new int[RipplesWorld.WTILES * RipplesWorld.WTILES];
      final short[] tileTexels = new short[RipplesWorld.WTILES * RipplesWorld.WTILES * RipplesWorld.PER_TILE * 4];
      int nTiles;
      float now;
      boolean any;
   }

   static final class Begin extends TextureDraw.GenericDrawer {
      final Frame f;

      Begin(Frame f) {
         this.f = f;
      }

      @Override
      public void render() {
         if (this.f.mode == FIELD) {
            awaitBinned(this.f); // the field's slots come from the binning
         }
         renderFrame = this.f;
         renderSerial++;
         tilesUploaded = false;
         if (this.f.mode == WORLD) {
            RipplesWorld.render(this.f);
         } else if (this.f.n > 0 || this.f.mode == SIM) {
            RipplesGpu.render(this.f);
         }
      }
   }

   /**
    * The raindrops' and wakes' compute passes of a frame (field / sim steps, slab stamps, particle bin / gather), run at the
    * start of the next frame's world render (2026-10-11, rw-cost-wake13-nodraw: queued right before the puddle draws, a
    * dispatch waited for the graphics before it to drain and its barrier emptied the texture caches the puddles then
    * refilled: ~12 us with no wake pixel drawn at all; EntityShadow found the same ~21 us a dispatch). The puddles read
    * the result one frame (~16 ms) late.
    */
   static final class Compute extends TextureDraw.GenericDrawer {
      final Frame f;

      Compute(Frame f) {
         this.f = f;
      }

      @Override
      public void render() {
         RainRipples.render(this.f.rain);
         Wakes.render(this.f);
      }
   }

   /** Game thread, FBORenderCell at the start of the world render: the last built frame's compute, first in this frame. */
   public static void frameStart(int playerIndex) {
      Frame f = lastBuilt;
      if (f == null || f.computeQueued || !on()) {
         return;
      }
      f.computeQueued = true;
      SpriteRenderer.instance.drawGeneric(f.compute);
   }

   private static final Frame[] FRAMES = new Frame[8];
   private static int frameIndex;
   static volatile Frame renderFrame;
   private static int renderSerial;
   private static boolean tilesUploaded;

   static {
      for (int i = 0; i < FRAMES.length; i++) {
         FRAMES[i] = new Frame();
      }
   }

   private static boolean lastSent;

   private static Frame build(int playerIndex) {
      zombie.iso.PlayerCamera cam = IsoCamera.cameras[playerIndex];
      double ox = cam.getOffX(), oy = cam.getOffY();
      float w = IsoCamera.getOffscreenWidth(playerIndex), h = IsoCamera.getOffscreenHeight(playerIndex);
      float ts = 32F * Core.tileScale;
      Frame fr = FRAMES[frameIndex];
      frameIndex = (frameIndex + 1) % FRAMES.length;
      fr.ox = ox;
      fr.oy = oy;
      fr.mode = phase;
      fr.nSlots = 0;
      fr.computeQueued = false;
      // raindrops and wakes: a frame every frame while either draws (the clock moves); they ride on the footsteps' frame
      boolean extra = RainRipples.build(fr.rain, rainPhase, playerIndex) | Wakes.build(fr, wakePhase, playerIndex);
      if (phase == WORLD) {
         fr.n = 0;
         boolean any = RipplesWorld.build(fr);
         if (!any && !lastSent && !extra) {
            return null;
         }
         lastSent = fr.world.any;
         return fr;
      }
      int n = 0;
      float vbx0 = Float.MAX_VALUE, vby0 = Float.MAX_VALUE, vbx1 = -Float.MAX_VALUE, vby1 = -Float.MAX_VALUE;
      float now = (float)clock; // the rings' start times are floats of the same clock (a double now made the newest one's age negative)
      float margin = Math.max(aluReach(LIFE), RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(LIFE)) * BIG * ts * 1.5F;
      // newest first: when more than MAX are on screen the oldest (faintest) are dropped
      for (int k = 0, i = (head - 1 + CAP) % CAP; k < live; k++, i = (i - 1 + CAP) % CAP) {
         float age = now - st[i];
         float life = sbig[i] ? LIFE * BIG : LIFE;
         if (age >= life) {
            if (age >= life + 0.15F) {
               live = k; // older ones are dead too (emitted in clock order, backdated by at most 0.1 s)
               break;
            }
            continue;
         }
         if (n >= MAX) {
            continue;
         }
         float wx = sx[i] - sz[i] * 3F, wy = sy[i] - sz[i] * 3F;
         double px = (double)(wx - wy) * ts - ox;
         double py = (double)(wx + wy) * ts * 0.5 - oy;
         if (px < -margin || py < -margin || px > w + margin || py > h + margin) {
            continue;
         }
         float sc = sbig[i] ? BIG : 1F, tt = age / sc;
         float rx = (phase == ALU ? aluReach(tt) : RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(tt)) * sc * ts * 1.4143F;
         vbx0 = Math.min(vbx0, (float)px - rx);
         vbx1 = Math.max(vbx1, (float)px + rx);
         vby0 = Math.min(vby0, (float)py - rx * 0.5F);
         vby1 = Math.max(vby1, (float)py + rx * 0.5F);
         int o = n * 4;
         ringConstants(fr.q, o, age, sbig[i], sa[i]);
         fr.src[o] = (float)px;
         fr.src[o + 1] = (float)py;
         fr.src[o + 2] = age;
         fr.src[o + 3] = sz[i] * 4F + (sbig[i] ? 2F : 0F) + Math.min(0.999F, sa[i]);
         n++;
      }
      maxLive = Math.max(maxLive, live);
      fr.bx0 = vbx0;
      fr.by0 = vby0;
      fr.bx1 = vbx1;
      fr.by1 = vby1;
      if (frames % 600 == 0) {
         Log.info("ripples: live=" + live + " on screen=" + n + " clock=" + String.format("%.2f", clock) + " " + stats());
      }
      fr.n = phase == 0 ? 0 : n;
      if (phase == SIM) {
         // the simulation runs while its tiles are active, rings on screen or not; the shader samples it everywhere
         boolean any = RipplesGpu.buildSim(fr, playerIndex);
         if (!any && !lastSent && !extra) {
            return null;
         }
         lastSent = any;
         return fr;
      }
      if (fr.n == 0) {
         // nothing to draw: one empty frame tells the render thread, then nothing at all is queued
         boolean send = lastSent;
         lastSent = false;
         return send || extra ? fr : null;
      }
      if (phase >= TILED) {
         fr.binW = w;
         fr.binH = h;
         fr.binTs = ts;
         if (Config.DEV_RIPPLES_BIN_INLINE) {
            bin(fr, w, h, ts);
            fr.binState.set(2);
         } else {
            fr.binState.set(0);
            Binner.submit(fr); // a worker bins it; the render thread does it itself if it gets there first
         }
      } else {
         fr.binState.set(2);
      }
      lastSent = true;
      return fr;
   }

   public static volatile long binNs, binFrames, binInserts, binStolen;

   /** Waits until the frame's tiles are binned (render thread): bins them here if the worker has not started. */
   static void awaitBinned(Frame fr) {
      if (fr.binState.get() == 2) {
         return;
      }
      if (fr.binState.compareAndSet(0, 1)) {
         bin(fr, fr.binW, fr.binH, fr.binTs);
         fr.binState.set(2);
         binStolen++;
         return;
      }
      while (fr.binState.get() != 2) {
         Thread.onSpinWait(); // the worker is in it: microseconds
      }
   }

   /** The binning worker: one daemon thread, parked between frames, woken by the game thread's submit. */
   static final class Binner {
      private static final java.util.concurrent.atomic.AtomicReference<Frame> next = new java.util.concurrent.atomic.AtomicReference<>();
      private static volatile Thread thread;

      static void submit(Frame fr) {
         next.set(fr); // no wake-up (a futex call a frame on the game thread): the worker polls every 0.5 ms
         if (thread == null) {
            synchronized (Binner.class) {
               if (thread == null) {
                  Thread t = new Thread(Binner::loop, "pzopt-ripples-bin");
                  t.setDaemon(true);
                  t.start();
                  thread = t;
               }
            }
         }
      }

      private static void loop() {
         while (true) {
            Frame fr = next.getAndSet(null);
            if (fr == null) {
               java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
               continue;
            }
            if (fr.binState.compareAndSet(0, 1)) {
               bin(fr, fr.binW, fr.binH, fr.binTs);
               fr.binState.set(2);
            }
         }
      }
   }

   /**
    * The tiles each ring reaches (its reach's iso ellipse: the bounding box less the tiles whose nearest corner is out of
    * reach), up to PER_TILE a tile, newest first. Only the tiles this frame object filled last time are cleared.
    */
   private static void bin(Frame fr, float w, float h, float ts) {
      long t0 = System.nanoTime();
      int tx = Math.min(TILES_X, (int)Math.ceil(w / TILE_PX) + 1), ty = Math.min(TILES_Y, (int)Math.ceil(h / TILE_PX) + 1);
      byte[] tiles = fr.tiles, cnt = fr.cnt;
      int[] dirty = fr.dirty;
      for (int k = 0; k < fr.nDirty; k++) {
         int t = dirty[k];
         cnt[t] = 0;
         int b = t * PER_TILE;
         for (int j = 0; j < PER_TILE; j++) {
            tiles[b + j] = 0;
         }
      }
      int nd = 0, rmin = Integer.MAX_VALUE, rmax = -1;
      fr.tilesX = tx;
      fr.tilesY = ty;
      boolean field = fr.mode == FIELD;
      // a tile's corners lie this far (squares) from its centre: corner (T/2, T/2) px -> d = (T/2, T/2) / ts -> world (1.5, 0.5) T/2/ts
      float half = (float)Math.hypot(0.75F * TILE_PX / ts, 0.25F * TILE_PX / ts);
      for (int s = 0; s < fr.n; s++) {
         float px = fr.src[s * 4], py = fr.src[s * 4 + 1], age = fr.src[s * 4 + 2];
         float packed = fr.src[s * 4 + 3];
         if (field && packed >= 4F) {
            continue; // the field holds level 0 only
         }
         boolean big = (packed % 4F) >= 2F;
         float sc = big ? BIG : 1F;
         float t = age / sc;
         float reach = (fr.mode == ALU ? aluReach(t) : RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(t)) * sc; // squares
         // a world circle of radius r: +-r*ts*sqrt(2) px across, +-r*ts*sqrt(2)/2 px down
         float rx = reach * ts * 1.4143F, ry = rx * 0.5F;
         int x0 = Math.max(0, (int)Math.floor((px - rx) / TILE_PX)), x1 = Math.min(tx - 1, (int)Math.floor((px + rx) / TILE_PX));
         int y0 = Math.max(0, (int)Math.floor((py - ry) / TILE_PX)), y1 = Math.min(ty - 1, (int)Math.floor((py + ry) / TILE_PX));
         float lim = reach + half;
         float lim2 = lim * lim;
         for (int y = y0; y <= y1; y++) {
            float cy = ((y + 0.5F) * TILE_PX - py) / ts; // d.y
            for (int x = x0; x <= x1; x++) {
               float cx = ((x + 0.5F) * TILE_PX - px) / ts; // d.x
               // the tile centre in world squares (the shader's metric): (d.x / 2 + d.y, d.y - d.x / 2)
               float wx = cx * 0.5F + cy, wy = cy - cx * 0.5F;
               if (wx * wx + wy * wy > lim2) {
                  continue;
               }
               int ti = y * TILES_X + x;
               int c = cnt[ti];
               if (c >= PER_TILE) {
                  continue; // full: it keeps the newest rings
               }
               tiles[ti * PER_TILE + c] = (byte)(s + 1);
               cnt[ti] = (byte)(c + 1);
               binInserts++;
               if (c == 0) {
                  dirty[nd++] = ti;
                  if (y < rmin) {
                     rmin = y;
                  }
                  if (y > rmax) {
                     rmax = y;
                  }
               }
            }
         }
      }
      fr.nDirty = nd;
      fr.rowMin = rmin;
      fr.rowMax = rmax;
      if (field) {
         // the atlas slots: every tile with a ring; the tile's first texel then holds its slot + 1 instead of indices
         int ns = 0;
         for (int k = 0; k < nd; k++) {
            int ti = dirty[k];
            int base = ti * PER_TILE;
            if (ns >= RipplesGpu.SLOTS) {
               java.util.Arrays.fill(tiles, base, base + PER_TILE, (byte)0);
               continue;
            }
            int o = ns * (3 + PER_TILE);
            fr.slots[o] = ti % TILES_X;
            fr.slots[o + 1] = ti / TILES_X;
            int c = cnt[ti];
            for (int j = 0; j < c; j++) {
               fr.slots[o + 3 + j] = (tiles[base + j] & 255) - 1;
            }
            fr.slots[o + 2] = c;
            java.util.Arrays.fill(tiles, base, base + PER_TILE, (byte)0);
            tiles[base] = (byte)((ns + 1) & 255);
            tiles[base + 1] = (byte)((ns + 1) >> 8);
            ns++;
         }
         fr.nSlots = ns;
      }
      binNs += System.nanoTime() - t0;
      binFrames++;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   static final String[] UNIFORMS = {"pzRipS", "pzRipP", "pzRipT", "pzRipTile", "pzRipLut", "pzRipField", "pzRipW", "pzRipK", "pzRipPool", "pzRipC", "pzRipB", "pzRipQ"};
   private static final java.util.HashMap<Integer, int[]> LOCATIONS = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, Integer> UPLOADED = new java.util.HashMap<>();
   private static final FloatBuffer SRC = BufferUtils.createFloatBuffer(MAX * 4), QBUF = BufferUtils.createFloatBuffer(MAX * 4);
   private static int tileTex, tilePbo, upRowMin = Integer.MAX_VALUE, upRowMax = -1;
   static long tileBytes;
   private static final ByteBuffer TILE_BYTES = BufferUtils.createByteBuffer(TILES_X * TILES_Y * PER_TILE);

   /**
    * Render thread, PuddlesShader.updatePuddlesParams (the puddle program bound): this frame's rings (once a frame per
    * program: uniforms stay with the program) and the textures of the mode.
    */
   public static void puddleUniforms(int prog, int z) {
      if (prog <= 0 || failed) {
         return;
      }
      RipplesGpu.noteCaps();
      int[] l = LOCATIONS.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length];
         for (int i = 0; i < l.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         LOCATIONS.put(prog, l);
         if (l[1] >= 0) {
            if (l[3] >= 0) {
               GL20.glUniform1i(l[3], TILE_UNIT);
            }
            if (l[4] >= 0) {
               GL20.glUniform1i(l[4], LUT_UNIT);
            }
            if (l[5] >= 0) {
               GL20.glUniform1i(l[5], FIELD_UNIT);
            }

         }
      }
      if (l[1] < 0) {
         return; // not a patched program
      }
      Frame f = renderFrame;
      RainRipples.uniforms(prog, f);
      Wakes.uniforms(prog, f);
      GroundTracks.puddleUniforms(prog); // the water in the ground tracks' ruts (groundTracksWater=puddles)
      boolean sim = f != null && f.mode == SIM && f.sim.any;
      boolean world = f != null && f.mode == WORLD && f.world.any;
      if (f == null || f.n == 0 && !sim && !world || !on()) {
         GL20.glUniform4f(l[1], 0F, 0F, 0F, 0F);
         return;
      }
      Integer up = UPLOADED.get(prog);
      if (up == null || up != renderSerial) {
         UPLOADED.put(prog, renderSerial);
         uploads++;
         // the rings relative to the camera this draw uses (IsoPuddles' WOffset: offX + jiggle)
         Vector4f off = IsoPuddles.getInstance().getShaderOffset();
         float dx = (float)(f.ox - off.x), dy = (float)(f.oy - off.y);
         if (f.mode < FIELD) {
            SRC.clear();
            for (int i = 0; i < f.n; i++) {
               SRC.put(f.src[i * 4] + dx).put(f.src[i * 4 + 1] + dy).put(f.src[i * 4 + 2]).put(f.src[i * 4 + 3]);
            }
            SRC.flip();
            GL20.glUniform4fv(l[0], SRC);
            if (l[11] >= 0) {
               QBUF.clear();
               QBUF.put(f.q, 0, f.n * 4).flip();
               GL20.glUniform4fv(l[11], QBUF);
            }
         }
         float strength = Config.RIPPLES_STRENGTH_PCT / 100F;
         GL20.glUniform4f(l[1], 1F / (32F * Core.tileScale), strength, sim || world ? 1F : f.n, f.mode - 1); // w: 0 loop, 1 tiled, 2 alu, 3 field, 4 sim, 5 world
         if (l[7] >= 0) {
            GL20.glUniform4f(l[7], RipplesModel.reach0, RipplesModel.reachV, 1F / RipplesModel.RMAX, sim ? f.sim.frac : 1F / LIFE); // sim: w the step blend
         }
         // the tile grid is in the game camera's frame: a draw-camera pixel p is at p - (dx, dy) there (the shader subtracts xy)
         GL20.glUniform4f(l[2], dx, dy, 1F / TILE_PX, Config.DEV_RIPPLES_VIEW ? 1F : 0F);
         if (l[10] >= 0) {
            if (sim || world) {
               // the box relative to the draw camera's view px (0, 0) in true world squares (the shader's d metric, level 0)
               double a = off.x / (32.0 * Core.tileScale), b = off.y / (16.0 * Core.tileScale);
               double wx0 = (a + b) * 0.5, wy0 = (b - a) * 0.5;
               GL20.glUniform4f(l[10], (float)(f.bx0 - wx0), (float)(f.by0 - wy0), (float)(f.bx1 - wx0), (float)(f.by1 - wy0));
            } else {
               GL20.glUniform4f(l[10], f.bx0 + dx, f.by0 + dy, f.bx1 + dx, f.by1 + dy);
            }
         }
         if (world && l[9] >= 0) {
            GL20.glUniform4f(l[9], f.world.now, 0F, 0F, 0F);
         }
         if ((sim || world) && l[6] >= 0) {
            // the world square (toroidal, mod the field's span) of the draw camera's view px (0, 0) at level 0
            double a = off.x / (32.0 * Core.tileScale), b = off.y / (16.0 * Core.tileScale);
            double span = RipplesGpu.SIM_SPAN;
            double wx = ((a + b) * 0.5) % span, wy = ((b - a) * 0.5) % span;
            GL20.glUniform4f(l[6], (float)(wx < 0 ? wx + span : wx), (float)(wy < 0 ? wy + span : wy), (float)(1.0 / span), RipplesGpu.simHeightScale());
         }
      }
      if (f.mode >= TILED && f.mode <= FIELD) {
         bindTiles(f);
      }
      if (f.mode == LOOP || f.mode == TILED) {
         bindLut();
      }
      if (f.mode == FIELD || sim) {
         RipplesGpu.bindForPuddles(f);
      }
      if (world) {
         bindLut();
         RipplesWorld.bindForPuddles();
      }
   }

   static int lutTex;

   /** The ring profile (RipplesModel.LUT: iWave's radial slope over radius x age) on LUT_UNIT. */
   static void bindLut() {
      if (lutTex == 0) {
         FloatBuffer b = BufferUtils.createFloatBuffer(RipplesModel.LUT_W * RipplesModel.LUT_H);
         b.put(RipplesModel.LUT).flip();
         lutTex = GL11.glGenTextures();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, lutTex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16F, RipplesModel.LUT_W, RipplesModel.LUT_H, 0, GL11.GL_RED, GL11.GL_FLOAT, b);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, lutTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   /** The tile texture (two RGBA8 texels a tile: eight ring indices + 1, or the field's atlas slot + 1 in the first). */
   private static void bindTiles(Frame f) {
      awaitBinned(f);
      if (tileTex == 0) {
         tileTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TILES_X * 2, TILES_Y, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         tilePbo = GL15.glGenBuffers();
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + TILE_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
      if (!tilesUploaded) {
         tilesUploaded = true;
         // the rows with rings now or at the last upload (those still hold the old indices)
         int r0 = Math.min(f.rowMin, upRowMin), r1 = Math.max(f.rowMax, upRowMax);
         upRowMin = f.rowMin;
         upRowMax = f.rowMax;
         if (r1 >= r0) {
            int rowBytes = TILES_X * PER_TILE, bytes = (r1 - r0 + 1) * rowBytes;
            TILE_BYTES.clear();
            TILE_BYTES.put(f.tiles, r0 * rowBytes, bytes).flip();
            // through a buffer: Mesa waits for its driver thread on a client-memory upload (docs/findings-prop-reflections)
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, tilePbo);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, (long)TILES_X * TILES_Y * PER_TILE, GL15.GL_STREAM_DRAW);
            GL15.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, TILE_BYTES);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, r0, TILES_X * 2, r1 - r0 + 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            tileBytes += bytes;
         }
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   // ------------------------------------------------------------------------------------------------ shader

   /**
    * ShaderUnit hook (innermost, before HDR's glint and Ssr's reflection patches see {@code gm}): the puddles' common
    * fragment unit gets the ring evaluation, added to the wave normal of the HQ and MQ puddles where the puddle shows.
    * Stays GLSL 1.20 (the later patches check the version line).
    */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || !Config.RIPPLES || failed || !Overrides.enabled()) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (f.endsWith("puddles_common.vert.glsl")) {
         String v = Wakes.patchVert(code);
         if (v == null) {
            Log.warn("ripples: " + fileName + " has changed, no wake gate: wakes off");
            Wakes.failed = true;
            return code;
         }
         Log.info("ripples: wake gate patched into " + fileName);
         return v;
      }
      if (!f.endsWith("puddles_common.frag.glsl")) {
         return code;
      }
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities(); // (ShaderUnit compiles with the context current)
      // the raindrop flipbook's sampler3D: Mesa wants its unit in the source (the game validates with every sampler on unit 0
      // and Mesa refuses two sampler types on one unit), NVIDIA's GLSL 1.20 refuses layout() even with 420pack (and does
      // not need it): the bound variant first, the plain one when the driver's compiler refuses it
      boolean binding = caps.OpenGL42 || caps.GL_ARB_shading_language_420pack || CoreGl.active;
      String c = patchText(code, binding);
      if (c == null) {
         Log.warn("ripples: " + fileName + " has changed, no ripples");
         return code;
      }
      String log = compileLog(c);
      if (log != null && binding) {
         c = patchText(code, false);
         String plain = compileLog(c);
         Log.info("ripples: the driver refuses layout(binding) in the puddle unit, the raindrop flipbook's sampler3D declared plain" + (plain == null ? "" : " (that fails too)"));
         if (plain == null) {
            RainRipples.noVolume = false; // declared, unit set by glUniform1i (validation never sees two types on unit 0 there)
         }
         log = plain;
      }
      boolean ok = log == null;
      if (!ok) {
         Log.warn("ripples: " + fileName + " patch does not compile, no ripples: " + log);
         return code;
      }
      patchedUnits++;
      Log.info("ripples: patched into " + fileName);
      return c;
   }

   /** null when the fragment unit compiles here, else the driver's log. */
   private static String compileLog(String src) {
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, src);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? null : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      return log;
   }

   /** The puddle unit's text with the ripples in (no GL: the tests compile it with glslangValidator), or null when its anchors moved. */
   static String patchText(String code) {
      return patchText(code, true);
   }

   /** {@code binding}: the context takes layout(binding) in GLSL 1.20 (420pack): the raindrop flipbook's sampler3D needs it. */
   static String patchText(String code, boolean binding) {
      String anchor = "gm += rainWaves.xxy*0.3;";
      int sm = code.indexOf("vec2 SphereMap(");
      if (sm < 0 || !code.contains(anchor) || !code.contains("float levelf =") || !code.contains("uniform vec4 WOffset;") || !code.contains("float puddlesZCoordinate")) {
         return null;
      }
      String c = code.substring(0, sm) + RING_GLSL + "\n" + RainRipples.glsl(binding) + "\n" + Wakes.glsl() + "\n" + GroundTracks.puddleGlsl() + "\n" + code.substring(sm);
      if (binding) {
         // right after the version line (Mesa refuses a declaration before an #extension; Ssr keeps what follows its own header)
         int eol = c.indexOf('\n');
         c = c.substring(0, eol + 1) + "#extension GL_ARB_shading_language_420pack : enable\n" + c.substring(eol + 1);
      }
      c = RainRipples.patch(c);
      if (c == null) {
         return null;
      }
      c = c.replace("float levelf =min(pow(level, 0.3), 1.0) + level;", "float levelf =min(pow(level, 0.3), 1.0) + level;\n    levelf = pzGtLevel(fragCoord, levelf); // pzopt: water in the ground tracks' ruts (pzopt.GroundTracks)");
      c = c.replace(anchor, anchor + "\n    if (pzRipP.z > 0.5 && (levelf*puddlesSize > 0.28 || pzRipT.w > 0.5)) { pzRipG = pzRipGrad(fragCoord) * pzRipP.y * (1.0 + " + RAIN_BOOST + " * rainIntensity); gm.xz -= pzRipG; } // pzopt: puddle ripples (a footstep stirs more than the raindrops' rings: stronger in rain)\n    pzWakeG = pzWake(fragCoord, levelf*puddlesSize); pzRipG += pzRainG + pzWakeG; gm.xz -= pzRainG + pzWakeG; // pzopt: rain rings (pzopt.RainRipples), car wakes (pzopt.Wakes)");
      // the crests catch the light (the overcast sky the puddle mirrors in rain is flat: the tilt alone hardly shows)
      String refl = "vec3 reflection = fragColor.rgb;";
      if (c.contains(refl)) {
         c = c.replace(refl, refl + "\n    reflection += vec3(dot(pzRipG, vec2(" + SHADE_X + ", " + SHADE_Z + "))); // pzopt: puddle ripples, crest shading");
      }
      String end = "fragColor.a = mix(fragColor.a, 0.5+muddyPuddles*0.3, alphaPuddlesReflection);";
      return c.replace(end, end + "\n    { float pzL = dot(pzRainG + 0.35 * pzWakeG, vec2(" + LIT_X + ", " + LIT_Z + ")); fragColor.rgb += vec3(pzL * " + LIT_GAIN + "); fragColor.a = min(1.0, fragColor.a + abs(pzL) * " + LIT_ALPHA + "); } // pzopt: rain rings / wakes catch the light (signed: one flank of a crest bright, the other dark; after the stock mixes, which keep ~9 % of a normal's effect)"
            + "\n    if (pzRipT.w > 0.5) fragColor = vec4(clamp(vec3(pzRipG.x, pzRipG.y, -pzRipG.y) * 4.0, 0.0, 1.0) + vec3(0.0, 0.0, 0.15), 1.0); // pzopt: puddle ripples, dev view");
   }

   /** The ring packet's radial slope (GLSL 1.20, shared by the puddle unit and the field's compute pass). */
   static final String RING_FN = String.join("\n",
         "// the closed form of the iWave footstep (fitted to RipplesModel's table, docs/findings-puddle-ripples): ripples filling a",
         "// disk that grows as t^0.7, shorter crests inside (the long waves lead), phase speed 0.9 squares a second, fading",
         "float pzRipRing(float r, float t) {",
         "   float rv = " + ALU_R1 + " * pow(t, " + ALU_RP + ") + " + ALU_R0 + ";",
         "   if (r > rv) return 0.0;",
         "   float x = clamp((rv - r) / (0.4 * rv), 0.0, 1.0);",
         "   float k = " + ALU_K + " * (1.0 + " + ALU_CHIRP + " * (1.0 - r / rv));",
         "   float fade = smoothstep(" + LIFE + ", " + (LIFE * 0.65F) + ", t);",
         "   return " + ALU_A + " * exp(" + ALU_B + " * t) * fade * x * x * (3.0 - 2.0 * x) * sin(k * r - " + (ALU_K * ALU_VP) + " * t) * inversesqrt(1.0 + 4.0 * r);",
         "}",
         "",
         "// a ring's slope at dw world squares from its centre, age s (pk: level*4 + 2*vehicle + amplitude), in the",
         "// puddle normal's plane (x right, z down the screen); lut: the profile texture, else the packet maths",
         "vec2 pzRipSlopeW(vec2 dw, float age, float pk, bool lut) {",
         "   float lv = floor(pk * 0.25);",
         "   float rem = pk - lv * 4.0;",
         "   float big = step(2.0, rem);",
         "   float amp = rem - 2.0 * big;",
         "   float sc = mix(1.0, " + BIG + ", big);",
         "   float t = age / sc;",
         "   if (t < 0.0 || t >= " + LIFE + ") return vec2(0.0);",
         "   dw /= sc;",
         "   float reach = lut ? pzRipK.x + pzRipK.y * sqrt(t) : " + ALU_R1 + " * pow(t, " + ALU_RP + ") + " + ALU_R0 + ";",
         "   float r2 = dot(dw, dw);",
         "   if (r2 > reach * reach) return vec2(0.0);",
         "   float r = sqrt(r2) + 1e-4;",
         "   float ring = lut ? pzRipLutAt(vec2(r * pzRipK.z, t * pzRipK.w)) : pzRipRing(r, t);",
         "   vec2 g = dw * (ring * amp / r);",
         "   return vec2(g.x - g.y, g.x + g.y) * 0.7071;",
         "}",
         "",
         "// a ring's slope at view px p; s as pzRipS (xy its centre in view px)",
         "vec2 pzRipSlope(vec2 p, vec4 s, float sqPerPx, bool lut) {",
         "   vec2 d = (p - s.xy) * sqPerPx; // x: dWx - dWy, y: (dWx + dWy) / 2",
         "   return pzRipSlopeW(vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5), s.z, s.w, lut);",
         "}");

   static final String RING_GLSL = String.join("\n",
         "// pzopt: puddle ripples (pzopt.Ripples)",
         "uniform vec4 pzRipS[" + MAX + "]; // a ring: xy its centre (px of this view), z age (s), w level*4 + 2*vehicle + amplitude",
         "uniform vec4 pzRipQ[" + MAX + "]; // its per-frame constants: x cull radius (world squares), y amplitude, z phase / profile row, w 1/size",
         "uniform vec4 pzRipP; // x squares a px / 2 (1/(32*tileScale)), y strength, z count (0 = none), w mode (0 loop 1 tiled 2 lut 3 field 4 sim)",
         "uniform vec4 pzRipT; // xy the tile grid's origin in this view's px, z 1/tile px, w dev view",
         "uniform vec4 pzRipK; // the profile: x + y sqrt(t) its reach (squares), z 1/radius span, w 1/life",
         "uniform vec4 pzRipW; // sim: xy the world square of the view's px (0, 0) (mod the field's span), z 1/span, w height scale",
         "uniform sampler2D pzRipTile; // " + TILES_X + " tiles x 2 texels by " + TILES_Y + ", rgba = ring indices + 1 (/255); field: slot + 1 in rg",
         "uniform sampler2D pzRipLut; // the ring profile (pzRipRing without spreading / amplitude), offset in packet widths x age",
         "uniform sampler2D pzRipField; // field: the atlas of the tiles' slopes; sim: the height field (r)",
         "uniform vec4 pzRipC; // world: x the clock now (mod " + RipplesWorld.CLOCK + " s)",
         "uniform vec4 pzRipB; // the gate: no ripple work outside it (sim / world: world squares from the view's px (0, 0); else view px)",
         "vec2 pzRipG = vec2(0.0); // this pixel's ring slope (set where the wave normal is made)",
         "float pzRipLutAt(vec2 uv) { return texture2D(pzRipLut, uv).r; }",
         RING_FN,
         "",
         "// one ring of the view modes, its age-only terms precomputed (pzRipQ): only the radius-dependent maths a pixel",
         "vec2 pzRipOne(vec2 p, int i) {",
         "   vec4 s = pzRipS[i];",
         "   if (abs(floor(s.w * 0.25) - puddlesZCoordinate) > 0.5) return vec2(0.0);",
         "   vec4 q = pzRipQ[i];",
         "   vec2 d = (p - s.xy) * pzRipP.x;",
         "   vec2 dw = vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5); // world squares",
         "   float r2 = dot(dw, dw);",
         "   if (r2 > q.x * q.x) return vec2(0.0);",
         "   float r = sqrt(r2) + 1e-4;",
         "   float rs = r * q.w; // in the ring's own size",
         "   float ring;",
         "   if (pzRipP.w > 1.5) {",
         "      float rv = q.x * q.w;",
         "      float x = clamp((rv - rs) / (0.4 * rv), 0.0, 1.0);",
         "      float k = " + ALU_K + " * (1.0 + " + ALU_CHIRP + " * (1.0 - rs / rv));",
         "      ring = q.y * x * x * (3.0 - 2.0 * x) * sin(k * rs - q.z) * inversesqrt(1.0 + 4.0 * rs);",
         "   } else ring = q.y * pzRipLutAt(vec2(rs * pzRipK.z, q.z));",
         "   vec2 g = dw * (ring / r);",
         "   return vec2(g.x - g.y, g.x + g.y) * 0.7071;",
         "}",
         "",
         "vec2 pzRipGrad(vec2 fc) {",
         "   vec2 p = fc * WOffset.zw; // px from the view's top left",
         "   vec2 g = vec2(0.0);",
         "   float mode = pzRipP.w;",
         "   if (mode > 3.5) {",
         "      vec2 d0 = p * pzRipP.x;",
         "      vec2 dv = vec2(d0.x * 0.5 + d0.y, d0.y - d0.x * 0.5) + vec2(3.0 * puddlesZCoordinate);",
         "      if (any(lessThan(dv, pzRipB.xy)) || any(greaterThan(dv, pzRipB.zw))) return g;",
         "   } else if (any(lessThan(p, pzRipB.xy)) || any(greaterThan(p, pzRipB.zw))) return g;",
         "   if (mode > 4.5) {",
         "      // world: the pixel's world square (true level: the puddle of level z is drawn 3z squares up-left), its tile",
         "      vec2 d = p * pzRipP.x;",
         "      vec2 w = pzRipW.xy + vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5) + vec2(3.0 * puddlesZCoordinate);",
         "      vec2 tile = floor(w * " + (1F / RipplesWorld.WT) + ");",
         "      vec2 org = tile * " + RipplesWorld.WT + ".0; // the tile's corner, lifted as the pixel is",
         "      vec2 t = mod(tile, " + RipplesWorld.WTILES + ".0);",
         "      float ty = (t.y + 0.5) / " + RipplesWorld.WTILES + ".0, tx = t.x * " + RipplesWorld.PER_TILE + ".0 + 0.5;",
         "      for (int i = 0; i < " + RipplesWorld.PER_TILE + "; i++) {",
         "         vec4 e = texture2D(pzRipTile, vec2((tx + float(i)) / " + (RipplesWorld.WTILES * RipplesWorld.PER_TILE) + ".0, ty));",
         "         if (e.a <= 0.0) break; // the tile's list ends",
         "         float pk = e.a * " + RipplesWorld.PACK + ";",
         "         if (abs(floor(pk * 0.25) - puddlesZCoordinate) > 0.5) continue;",
         "         vec2 c = org + (e.rg - 0.5) * " + RipplesWorld.OFF_RANGE + ";",
         "         g += pzRipSlopeW(w - c, mod(pzRipC.x - e.b * " + RipplesWorld.CLOCK + ", " + RipplesWorld.CLOCK + "), pk, true);",
         "      }",
         "      return g;",
         "   }",
         "   if (mode > 3.5) {",
         "      // sim: the world height field (toroidal), its slope from three bilinear texels",
         "      if (puddlesZCoordinate > 0.5) return g;",
         "      vec2 d = p * pzRipP.x;",
         "      vec2 w = pzRipW.xy + vec2(d.x * 0.5 + d.y, d.y - d.x * 0.5); // world squares",
         "      vec2 uv = w * pzRipW.z;",
         "      float e = 1.0 / " + RipplesGpu.SIM_N + ".0;",
         "      vec2 bl = vec2(pzRipK.w, 1.0 - pzRipK.w); // the last two steps (r now, g the one before) blended to the frame's time",
         "      float hx = dot(texture2D(pzRipField, uv + vec2(e * 0.5, 0.0)).rg - texture2D(pzRipField, uv - vec2(e * 0.5, 0.0)).rg, bl);",
         "      float hy = dot(texture2D(pzRipField, uv + vec2(0.0, e * 0.5)).rg - texture2D(pzRipField, uv - vec2(0.0, e * 0.5)).rg, bl);",
         "      vec2 gw = vec2(hx, hy) * pzRipW.w; // slope per square",
         "      return vec2(gw.x - gw.y, gw.x + gw.y) * 0.7071;",
         "   }",
         "   if (mode < 0.5) {",
         "      int n = int(pzRipP.z);",
         "      for (int i = 0; i < " + MAX + "; i++) {",
         "         if (i >= n) break;",
         "         g += pzRipOne(p, i);",
         "      }",
         "      return g;",
         "   }",
         "   vec2 q = p - pzRipT.xy;",
         "   vec2 tile = floor(q * pzRipT.z);",
         "   vec2 tc = vec2((tile.x * 2.0 + 0.5) / " + (TILES_X * 2) + ".0, (tile.y + 0.5) / " + TILES_Y + ".0);",
         "   vec4 ids = floor(texture2D(pzRipTile, tc) * 255.0 + 0.5);",
         "   if (ids.x < 0.5) return g;",
         "   if (mode > 2.5) {",
         "      if (puddlesZCoordinate > 0.5) return g; // the field holds level 0",
         "      // field: the tile's atlas slot (" + RipplesGpu.ATLAS_SLOTS_X + " slots a row, " + RipplesGpu.SLOT_TEXELS + " texels a side)",
         "      float slot = ids.x + ids.y * 256.0 - 1.0;",
         "      vec2 so = vec2(mod(slot, " + RipplesGpu.ATLAS_SLOTS_X + ".0), floor(slot / " + RipplesGpu.ATLAS_SLOTS_X + ".0)) * " + RipplesGpu.SLOT_TEXELS + ".0;",
         "      vec2 local = clamp((q - tile / pzRipT.z) * " + (RipplesGpu.SLOT_TEXELS / (float)TILE_PX) + ", vec2(0.5), vec2(" + (RipplesGpu.SLOT_TEXELS - 0.5F) + "));",
         "      return texture2D(pzRipField, (so + local) / vec2(" + RipplesGpu.ATLAS_W + ".0, " + RipplesGpu.ATLAS_H + ".0)).rg;",
         "   }",
         "   g += pzRipOne(p, int(ids.x) - 1);",
         "   if (ids.y > 0.5) g += pzRipOne(p, int(ids.y) - 1);",
         "   if (ids.z > 0.5) g += pzRipOne(p, int(ids.z) - 1);",
         "   if (ids.w > 0.5) {",
         "      g += pzRipOne(p, int(ids.w) - 1);",
         "      vec4 more = floor(texture2D(pzRipTile, tc + vec2(1.0 / " + (TILES_X * 2) + ".0, 0.0)) * 255.0 + 0.5);",
         "      if (more.x > 0.5) g += pzRipOne(p, int(more.x) - 1);",
         "      if (more.y > 0.5) g += pzRipOne(p, int(more.y) - 1);",
         "      if (more.z > 0.5) g += pzRipOne(p, int(more.z) - 1);",
         "      if (more.w > 0.5) g += pzRipOne(p, int(more.w) - 1);",
         "   }",
         "   return g;",
         "}",
         "");
}
