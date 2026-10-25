package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.LightingJNI;

/**
 * Soft shadows of characters and vehicles (Config {@code sunShadows} + {@code sunShadowCharacters} /
 * {@code sunShadowVehicles}, {@code sunShadowTorches}): every character drawn with a model this frame casts the shadow of
 * its body onto whatever the scene depth holds (ground, walls, furniture: the static world, the pass runs right after the
 * chunk composite, before any character is drawn), from the sun when it stands outdoors in daylight and from the torches
 * and headlights in reach at any hour.
 *
 * <p>The body is ten capsules between its bones (torso, head, thighs, calves, upper arms, forearms), the end points taken
 * on the bone worker right after the bones ({@code AnimationPlayer.pzoptPrecomputeShadow}, zombies) or here (the player,
 * animals); a vehicle is two body capsules side by side and the cabin. Per pixel of a caster's shadow area: the world
 * position from the scene depth (IsoDepthHelper's depth is linear in x + y + 2z, the screen gives x - y and x + y - 6z),
 * one bounding capsule first (a pixel in its full light is in full light), then Quilez's soft capsule shadow towards the
 * light (the closest approach of the ray to each segment over the distance travelled: a penumbra that widens with the
 * distance from the body, sharp where the feet touch the ground). The sun: one instanced draw, a quad per caster (the
 * screen box of its capsules and their projections on its floor along the sun); a receiver the static world already hides
 * from the sun takes no second shadow (8 depth taps towards the sun). Lights: one instanced draw of casters x lights, each
 * quad the box of the bounding capsule and its projection away from the light, the shadow's strength the light's share
 * of the pixel (PixelLight's fitted torch model x the darkness), the holder never shading their own torch. Blended as a
 * multiply onto the scene; the capsules come from a small float texture.
 */
public final class CapsuleShadow {
   /** The bones whose positions end the capsules. */
   static final String[] BONES = {"Bip01_Pelvis", "Bip01_Neck", "Bip01_Head", "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_L_Foot", "Bip01_R_Thigh",
      "Bip01_R_Calf", "Bip01_R_Foot", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand"};
   public static final int POINTS = 15;
   /** Capsules as pairs of BONES indices; the head runs from the neck through the head bone and 60 % beyond it. */
   private static final int[] SEG_A = {0, 1, 3, 4, 6, 7, 9, 10, 12, 13};
   private static final int[] SEG_B = {1, 2, 4, 5, 7, 8, 10, 11, 13, 14};
   /** Radii in model metres (x 1.5 into squares). */
   private static final float[] RADIUS = {0.15F, 0.11F, 0.075F, 0.055F, 0.075F, 0.055F, 0.05F, 0.045F, 0.05F, 0.045F};
   static final int K = 10;
   /** Per caster: (unused), its bounding capsule (a xyz r, b xyz 0), its facts (floor z, kind, sun, alpha), then a (xyz, r) and b (xyz, 0) per capsule. */
   private static final int CENTRE = 4 + 2 * K; // the caster's atlas tile centre (sunShadowMeshes)
   private static final int LAMP0 = CENTRE + 1; // two lamp views (sunShadowLampMeshes), 3 texels each: tile, half size; centre now; lamp from the centre at the draw
   private static final int WALL0 = LAMP0 + 6; // sunShadowWallCut: the walls / fences along its sun shadow (walls()), a texel each
   private static final int WALLS = 4;
   private static final int TEXELS = WALL0 + WALLS;
   private static final int MAX = 1024;
   private static final int MAX_LIGHTS = 8; // the frame's lights: torches / headlights first (TORCH_SLOTS at most), then static lights (sunShadowStaticLights)
   private static final int TORCH_SLOTS = 4;
   private static final float CEIL_LAMP_SIZE = 1.0F; // a room's ceiling light (sunShadowRoomLights): an area light (the native lights the whole room from it), a wide penumbra
   private static final float[] LIGHT_SCORE = new float[MAX_LIGHTS];
   private static final float[] STATIC_ORDER = new float[96];
   private static final zombie.iso.IsoLightSource[] STATIC_CAND = new zombie.iso.IsoLightSource[96];
   private static final zombie.iso.IsoRoomLight[] STATIC_ROOM = new zombie.iso.IsoRoomLight[96]; // a candidate room light point (STATIC_CAND null)
   private static final float[] STATIC_PT = new float[96 * 3]; // its point: x, y, reach
   private static final float[] ROOM_PTS = new float[StaticLightShadow.ROOM_POINTS * 3];
   private static final int[] ROOM_RECTS = new int[32 * 4]; // a room's rectangles (x, y, w, h)
   private static final java.util.IdentityHashMap<Object, Boolean> ROOMS_SEEN = new java.util.IdentityHashMap<>(); // gatherStatic: rooms listed this frame
   private static final float[] STATIC_SHARE = new float[MAX_LIGHTS];
   private static final float[] STATIC_LIM = new float[MAX_LIGHTS];
   private static final char[] STATIC_WHY = new char[MAX_LIGHTS]; // dev log: StaticLightShadow.why per light
   private static final int[] STATIC_CODE = new int[MAX_LIGHTS]; // dev log: reach()'s answer per light (-2 not asked)
   private static final int[] STATIC_PICK = new int[2];
   private static final zombie.iso.IsoLightSource[] BURN_LIGHT = new zombie.iso.IsoLightSource[16]; // the frame's burning characters' lights
   private static final IsoGameCharacter[] BURN_CHR = new IsoGameCharacter[16];
   private static long staticLightFrames;
   private static long staticPicks;
   private static long staticVehicleLights; // lightbar world lights left out (gatherStatic)
   private static long devStaticN;
   private static boolean devStaticFrame;
   private static zombie.iso.IsoLightSource pickOwnFire; // the caster being appended: its own fire light (a burning character), never its shadow's light // devStaticLightLog: this frame's casters log their static-light shares / reach (staticPick)
   private static final int MAX_PAIRS = 2048;
   private static long lightPairs;
   private static final float METRIC_Z = 2.4494897F; // squares of height per level

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static Frame current; // game thread: the frame the characters drawn now add their capsules to
   private static volatile boolean failed;
   private static volatile boolean lightsActive; // last frame had a torch or headlight in view (the workers compute end points)
   private static long drawn;
   private static long characters;
   private static long computedHere;
   private static long frames;
   private static long lightFrames;
   private static final float[] SCRATCH = new float[POINTS * 3];
   private static long alternateT0;

   private CapsuleShadow() {
   }

   /** Do the bone workers compute the capsule end points (the sun up, or a torch in view last frame)? */
   public static boolean wanted() {
      return Config.SUN_SHADOWS && Config.SUN_SHADOW_CHARACTERS && (SunShadow.dir[3] > 0F || lightsActive) && !failed;
   }

   static String stats() {
      return "capsule shadows: frames=" + frames + " (with lights " + lightFrames + ", caster x light quads " + lightPairs + ") characters=" + characters + " vehicles=" + vehicles + " animals=" + animals + " atlas=" + atlas + " mesh draws=" + meshDraws + " lamp views drawn=" + lampDraws + " read=" + lampLooks + " (end points on the game thread "
         + computedHere + ") wall collects=" + wallCollects + " (squares " + WALL_CACHE.size() + ", last " + wallLast + ") draws=" + drawn + (failed ? " FAILED" : "") + " | static light frames=" + staticLightFrames + " caster picks=" + staticPicks + " lightbar lights left out=" + staticVehicleLights + " " + StaticLightShadow.stats() + " | " + ShadowAtlas.stats();
   }

   /** The end points of BONES (relative to the character, metric) into out; false when the skeleton lacks one. Any thread. */
   public static boolean points(AnimationPlayer player, float[] out) {
      Object sd = player.getSkinningData();
      if (sd == null) {
         return false;
      }
      int[] idx = player.pzoptCapsuleBones;
      if (idx == null || player.pzoptCapsuleBonesOf != sd) {
         idx = new int[POINTS];
         for (int i = 0; i < POINTS; i++) {
            idx[i] = player.getSkinningBoneIndex(BONES[i], -1);
         }
         player.pzoptCapsuleBones = idx;
         player.pzoptCapsuleBonesOf = sd;
      }
      return ShadowPrep.capsulePoints(player, idx, out);
   }

   /**
    * Game thread, right after the chunk composite: a frame of capsule shadows is queued there (drawn before the characters
    * that follow); the characters drawn this frame add themselves to it.
    */
   public static void queue(int playerIndex) {
      current = null;
      stamp++;
      castersLast = castersNow;
      castersNow = 0;
      drawsNow = 0;
      lampDrawsNow = 0;
      lampViewsLast = lampViewsNow;
      lampViewsNow = 0;
      atlasSameFrame = false;
      rateHz = Config.SUN_SHADOW_RATE_HZ;
      if (rateHz <= 0) {
         // sunShadowRate=frame, like the model: sunShadowMeshFrameBudget draws a frame; past it the casters take turns (one
         // drawn waits casters / budget frames), so the budget goes to the oldest poses
         int budget = Math.max(1, Config.SUN_SHADOW_MESH_FRAME_BUDGET);
         meshBudget = budget;
         meshCredit = 0.0;
         meshStaleFrames = Math.max(1L, (castersLast + budget - 1L) / budget);
         int lampBudget = Math.max(1, Config.SUN_SHADOW_LAMP_BUDGET);
         lampStaleFrames = Math.max(1L, (lampViewsLast + lampBudget - 1L) / lampBudget);
      } else {
         // sunShadowRate N: each caster's pose redrawn so many times a second whatever the frame rate (the draws a frame follow
         // the casters and the last frame's time), at least one a frame, at most sunShadowMeshBudget
         long nowNs = System.nanoTime();
         double dt = lastQueueNs == 0L ? 1.0 / 240.0 : Math.min(0.1, (nowNs - lastQueueNs) / 1e9);
         lastQueueNs = nowNs;
         // the draws come in bursts of at least sunShadowMeshBurst (a flush has a fixed cost: the atlas bound, its tiles cleared,
         // the state put back; ~44 us of the flip's render thread against ~26 us a draw): the frame's share of the redraws is
         // credited, and a frame spends it once it covers a burst
         meshCredit = Math.min(64.0, meshCredit + castersLast * rateHz * dt);
         int burst = Math.max(1, Config.SUN_SHADOW_MESH_BURST);
         if (meshCredit >= Math.min(burst, Math.max(1, castersLast))) {
            meshBudget = Math.min(Math.max(1, Config.SUN_SHADOW_MESH_BUDGET), (int)meshCredit);
            meshCredit -= meshBudget;
         } else {
            meshBudget = 0; // (a new caster still draws: its first pose)
         }
         meshStaleFrames = Math.max(1L, (long)(0.8 / (rateHz * Math.max(1e-4, dt)))); // a pose older than ~0.8 / Hz is due
         lampStaleFrames = meshStaleFrames;
      }
      // the draws scheduled last frame were drawn at its end: their content is the tiles' now
      for (int t = 0; t < ShadowAtlas.MAX_TILES; t++) {
         if (TILE_SCHED[t] == stamp - 1L && TILE_SCHED[t] != 0L) {
            System.arraycopy(TILE_NEXT, t * 4, TILE_OFF, t * 4, 4);
            System.arraycopy(TILE_LAMP_NEXT, t * 3, TILE_LAMP, t * 3, 3);
            TILE_HAS[t] = true;
         }
      }
      if (!Overrides.enabled() || !Config.SUN_SHADOWS || !Config.SUN_SHADOW_CHARACTERS && !Config.SUN_SHADOW_VEHICLES || failed) {
         lightsActive = false;
         return;
      }
      if (Config.DEV_SUN_ALTERNATE > 0) {
         // dev: the pass (and the game thread's collection) on and off every period, from the first frame's wall clock;
         // harness/contact/alt.py splits pzopt-overlay.out's gpu_ms by the same clock
         long now = System.currentTimeMillis();
         if (alternateT0 == 0L) {
            alternateT0 = now;
            Log.info("capsule shadows: alternating every " + Config.DEV_SUN_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         if (((now - alternateT0) / Config.DEV_SUN_ALTERNATE & 1L) == 1L) {
            return;
         }
      }
      Frame f = FRAMES[frameIndex & 3];
      int ox = (int)Math.floor(IsoCamera.frameState.camCharacterX), oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      f.ox = ox;
      f.oy = oy;
      f.sunOn = SunShadow.dir[3] > 0F;
      Core core = Core.getInstance();
      f.zoom = core.getZoom(playerIndex);
      f.ts = Core.tileScale;
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
      float day = cm == null ? 0F : Math.max(0F, Math.min(1F, cm.getDayLightStrength()));
      // a torch's shadow matters in the dark only: none above 90 % daylight (the bench pistol's weapon light is always on)
      f.lightStrength = Math.max(0F, Math.min(1F, (0.9F - day) / 0.6F)) * Math.max(0, Config.SUN_SHADOW_TORCH_PCT) / 100F;
      StaticLightShadow.beginFrame();
      f.nt = 0;
      f.nl = (Config.SUN_SHADOW_TORCHES || Config.SUN_SHADOW_STATIC_LIGHTS) && f.lightStrength > 0.02F ? gatherLights(f) : 0;
      lightsActive = f.nl > 0;
      if (!f.sunOn && f.nl == 0) {
         return;
      }
      frameIndex++;
      f.n = 0;
      f.devPlayer = -1;
      f.offX = IsoCamera.getOffX();
      f.offY = IsoCamera.getOffY();
      f.d0 = IsoDepthHelper.getSquareDepthData(ox, oy, ox, oy, 0.0F).depthStart;
      System.arraycopy(SunShadow.world, 0, f.sun, 0, 4);
      f.strength = SunShadow.dir[3] * Math.max(0, Config.SUN_SHADOW_CHARACTER_PCT) / 100F;
      f.playerIndex = playerIndex;
      current = f;
      frames++;
      if (f.nl > 0) {
         lightFrames++;
      }
      f.silhouette = Config.SUN_SHADOW_SILHOUETTE;
      f.meshes = f.silhouette && (f.sunOn || f.nl > 0 && Config.SUN_SHADOW_LAMP_MESHES) && ShadowAtlas.usable();
      if (f.meshes) {
         // the atlas starts here in the sprite stream: cleared, the frame's sun, before any model of the frame draws
         SpriteRenderer.instance.drawGeneric(ShadowAtlas.begin(f.sun[0], f.sun[1], f.sun[2], true));
         // sunShadowMeshSameFrame: the pass (after the moving objects: f.silhouette) follows the frame's flush
         atlasSameFrame = rateHz <= 0 && Config.SUN_SHADOW_MESH_SAME_FRAME;
      }
      if (f.silhouette) {
         deferred = f; // drawn after the moving objects (afterMoving): the march reads their depth
      } else {
         SpriteRenderer.instance.drawGeneric(f);
      }
   }

   /**
    * Game thread, right after the moving objects (characters, animals, vehicles) were queued: sunShadowSilhouette draws this
    * frame's pass here, reading their depth; the casters collected since queue() are complete.
    */
   public static void afterMoving(int playerIndex) {
      current = null;
      if (Config.SUN_SHADOW_PASS_LATE) {
         return; // beforeFog draws it
      }
      Frame f = deferred;
      deferred = null;
      if (f != null && f.n > 0) {
         flushSameFrame();
         SpriteRenderer.instance.drawGeneric(f);
      }
   }

   /**
    * sunShadowMeshSameFrame: the frame's sun and lamp draws of the casters flushed into the atlas right before the caster
    * pass reads it (the casters' models are all drawn by then; their tiles' parameters went straight to TILE_OFF). The
    * screen composite's flush (queueAtlasFlush) then finds nothing left.
    */
   private static void flushSameFrame() {
      if (atlasSameFrame && drawsNow > 0) {
         SpriteRenderer.instance.drawGeneric(ShadowAtlas.FLUSH_WORLD);
      }
   }

   /**
    * Game thread, right before the god rays and the fog (FBORenderCell, the scene depth complete: water, translucent
    * objects and the stock shadows drawn): sunShadowPassLate draws the frame's caster pass here. Its read of the scene
    * depth sits beside the god rays' and the fog pass's (on AMD / Mesa the first read of a depth target after drawing
    * decompresses it, ~70-85 us of the flip's GPU whatever the pass then does: sharing it is the saving); translucent objects
    * are receivers too.
    */
   public static void beforeFog(int playerIndex) {
      current = null;
      Frame f = deferred;
      deferred = null;
      if (f != null && f.n > 0) {
         flushSameFrame();
         SpriteRenderer.instance.drawGeneric(f);
      }
   }

   /**
    * Game thread, at the start of the screen composite (MultiTextureFBO2.render): the frame's sun draws of the casters
    * are drawn there on the render thread, after the world pass (switching the render target to the atlas in the middle of
    * the world cost the GPU more than the draws), before the frame's slots are released. The next frame's pass reads them.
    */
   public static void queueAtlasFlush() {
      if (drawsNow > 0) {
         SpriteRenderer.instance.drawGeneric(ShadowAtlas.FLUSH);
      }
   }

   /** The stock blob shadow's alpha factor for the caster just added (1 unless it casts a real sun shadow); FBORenderShadows reads it. */
   public static float stockShadowScale() {
      return stockScale;
   }

   /** Game thread, after the caster's stock shadow was queued: later shadows (corpses, mannequins) keep their alpha. */
   public static void stockShadowDone() {
      stockScale = 1F;
   }

   private static Frame deferred;
   private static long stamp; // game thread: CapsuleShadow.queue's call count (the characters' tile stamp)

   /**
    * Game thread, TextureDraw.drawModel: the atlas tile of the model's character when it joined this frame's pass
    * (CapsuleShadow.add), else -1, into the draw's fields (its sun draw on the render thread reads them).
    */
   public static void tileFor(zombie.core.skinnedmodel.ModelManager.ModelSlot slot, TextureDraw texd) {
      texd.pzoptShadowTile = -1;
      texd.pzoptLampN = 0;
      if (slot == null || drawsNow == 0) {
         return;
      }
      IsoGameCharacter chr = slot.character;
      if (chr == null && slot.model != null && slot.model.object instanceof IsoGameCharacter c) {
         chr = c;
      }
      if (chr != null && lampDrawsNow > 0) {
         LampViews lv = LAMPS.get(chr);
         if (lv != null && lv.drawStamp == stamp && lv.n > 0) {
            if (texd.pzoptLampDraw == null) {
               texd.pzoptLampDraw = new float[16];
            }
            System.arraycopy(lv.draw, 0, texd.pzoptLampDraw, 0, lv.n * 8);
            texd.pzoptLampN = lv.n;
            lv.drawStamp = 0L; // one draw a frame
         }
      }
      if (chr == null && slot.model != null && slot.model.object instanceof zombie.vehicles.BaseVehicle v) {
         float[] vs = VEHICLE_TILES.get(v);
         if (vs != null && vs[0] >= 0F && (long)vs[1] == stamp) {
            texd.pzoptShadowTile = (int)vs[0];
            texd.pzoptShadowX = vs[2];
            texd.pzoptShadowY = vs[3];
            texd.pzoptShadowZ = vs[4];
            texd.pzoptShadowHalf = vs[5];
            vs[1] = 0F;
         }
         return;
      }
      if (chr == null || chr.pzoptShadowStamp != stamp || chr.pzoptShadowTile < 0) {
         return;
      }
      texd.pzoptShadowTile = chr.pzoptShadowTile;
      texd.pzoptShadowX = chr.pzoptShadowX;
      texd.pzoptShadowY = chr.pzoptShadowY;
      texd.pzoptShadowZ = chr.pzoptShadowZ;
      texd.pzoptShadowHalf = chr.pzoptShadowHalf;
      chr.pzoptShadowStamp = 0L; // one sun draw a frame (a second view of the same slot draws none)
   }

   private static final Object[] TILE_OWNER = new Object[ShadowAtlas.MAX_TILES];
   /** A vehicle's tile state (vehicles are no override: no fields): tile, stamp of a scheduled draw, centre x, y, z, half. */
   private static final java.util.IdentityHashMap<Object, float[]> VEHICLE_TILES = new java.util.IdentityHashMap<>();
   private static final long[] TILE_SEEN = new long[ShadowAtlas.MAX_TILES]; // the stamp its character last joined a pass
   private static final long[] TILE_DRAWN = new long[ShadowAtlas.MAX_TILES]; // the stamp of its last sun draw (0: never)
   private static final long[] TILE_SUN = new long[ShadowAtlas.MAX_TILES]; // the sun step it was drawn under
   private static final float[] TILE_POSE = new float[ShadowAtlas.MAX_TILES * 6]; // sunShadowStaticVehicles: a vehicle's position and angles at its last draw
   public static long staticVehicleSkips;
   private static final float[] TILE_OFF = new float[ShadowAtlas.MAX_TILES * 4]; // the tile's content: its centre from its character (at the draw), half size
   private static final float[] TILE_NEXT = new float[ShadowAtlas.MAX_TILES * 4]; // a draw scheduled this frame (drawn at the frame's end)
   private static final long[] TILE_SCHED = new long[ShadowAtlas.MAX_TILES]; // the stamp of the frame that scheduled its last draw
   private static final boolean[] TILE_HAS = new boolean[ShadowAtlas.MAX_TILES]; // its content was drawn
   private static final byte[] TILE_KIND = new byte[ShadowAtlas.MAX_TILES]; // -1 its owner's sun view, 0 / 1 its owner's first / second lamp view
   private static final float[] TILE_LAMP = new float[ShadowAtlas.MAX_TILES * 3]; // a lamp view's content: the lamp from its centre (z metric)
   private static final float[] TILE_LAMP_NEXT = new float[ShadowAtlas.MAX_TILES * 3]; // a lamp draw scheduled (the last scheduled)
   /** A character's lamp views: their tiles and this frame's draws (TextureDraw.pzoptLampDraw's layout). */
   private static final class LampViews {
      final int[] tile = {-1, -1};
      long drawStamp;
      int n;
      final float[] draw = new float[16];
   }
   private static final java.util.IdentityHashMap<Object, LampViews> LAMPS = new java.util.IdentityHashMap<>();
   private static int lampDrawsNow;
   private static long lampDraws, lampLooks;
   private static int castersNow, castersLast, drawsNow, meshBudget = 1;
   private static int rateHz; // game thread: this frame's sunShadowRate (0 every frame)
   private static boolean atlasSameFrame; // game thread: this frame's atlas is flushed before its pass (sunShadowMeshSameFrame)
   private static long lastQueueNs;
   private static double meshCredit;
   private static long meshStaleFrames = 1L, lampStaleFrames = 1L;
   private static int lampViewsNow, lampViewsLast;
   private static long meshDraws;
   private static int tileScan;

   /**
    * The caster just appended (the frame's last, a model) keeps its atlas tile from frame to frame (a free one the first
    * time). The tile is drawn again from the sun when it is new, drawn under another sun step, or its turn in the frame's
    * budget has come (sunShadowMeshBudget draws a frame, the casters taking turns: each is redrawn every casters / budget
    * frames, every frame up to sunShadowMeshFrameBudget with sunShadowRate=frame; a player every frame then); in between the pass reads the last
    * pose, placed at the character's position now. With sunShadowRate=frame (+ sunShadowMeshSameFrame) a draw is flushed before this frame's pass
    * and read by it (the shadow shows the model's pose of this frame); without, by the next frame's. Its tile, half size
    * and centre go into the caster's data; a draw's centre and half size on the character for tileFor.
    */
   private static void assignTile(Frame f, zombie.iso.IsoMovingObject chr, boolean sun) {
      int base = (f.n - 1) * TEXELS * 4;
      float[] d = f.data;
      d[base] = -1F;
      d[base + LAMP0 * 4] = -1F;
      d[base + (LAMP0 + 3) * 4] = -1F;
      if (!f.meshes) {
         return;
      }
      if (sun && f.sunOn) {
         sunTile(f, chr, base);
      }
      if (f.nl > 0 && Config.SUN_SHADOW_LAMP_MESHES && chr instanceof IsoGameCharacter) {
         lampTiles(f, chr, base);
      }
   }

   /**
    * sunShadowLampMeshes: the character's views from the (at most two) strongest lamps it stands in (the frame's lights are
    * sorted strongest first), each in a tile of its own, drawn again when new, when its lamp moved round the character by a
    * fifth of a square or its pose is older than sunShadowRate allows (sunShadowLampBudget draws a frame); the lamp at the
    * draw goes with the tile's content, so the pass reads a consistent view in between.
    */
   private static void lampTiles(Frame f, zombie.iso.IsoMovingObject chr, int base) {
      float[] d = f.data;
      float ax = d[base + 4], ay = d[base + 5], az = d[base + 6], r = d[base + 7], bx = d[base + 8], by = d[base + 9], bz = d[base + 10];
      float mx = 0.5F * (ax + bx), my = 0.5F * (ay + by), mz = 0.5F * (az + bz);
      float len = (float)Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
      float half = Math.max(0.4F, Math.min(3.5F, (0.5F * len + r) * 1.1F));
      LampViews lv = null;
      int j = 0;
      float px = chr.getX(), py = chr.getY(), pz = chr.getZ() * METRIC_Z;
      int ci = f.n - 1;
      for (int oq = 0; oq < f.nt + 2 && j < 2; oq++) { // the torches / headlights in their order, then the caster's static lights (staticPick)
         int l = oq < f.nt ? oq : f.lsel[ci * 2 + oq - f.nt];
         if (l < 0 || l >= f.nl) {
            continue;
         }
         float lx = f.lA[l * 4], ly = f.lA[l * 4 + 1], lz = f.lA[l * 4 + 2], reach = f.lA[l * 4 + 3];
         float vx = mx - lx, vy = my - ly;
         float dl = (float)Math.sqrt(vx * vx + vy * vy);
         float d3 = (float)Math.sqrt(dl * dl + (mz - lz) * (mz - lz));
         boolean car = f.lK[l] > 1.5F && f.lK[l] < 2.5F;
         if (dl > reach + r || f.lK[l] < 1.5F && dl < 0.7F || d3 < half * 1.25F) {
            continue; // out of reach, its holder, or the lamp inside the caster's sphere (the capsules there)
         }
         float cone = f.lB[l * 4 + 2];
         if (cone > -1.5F && dl > r + 0.5F) {
            float cosToCaster = (vx * f.lB[l * 4] + vy * f.lB[l * 4 + 1]) / dl;
            float c0 = car ? cone - 0.28F : cone + 0.025F;
            double a = Math.acos(Math.max(-1F, Math.min(1F, cosToCaster))), sa = Math.asin(Math.min(1.0, (r + 0.5F) / dl));
            if (Math.cos(Math.max(0.0, a - sa)) < c0) {
               continue;
            }
         }
         if (lv == null) {
            lv = LAMPS.get(chr);
            if (lv == null) {
               if (LAMPS.size() > 1024) {
                  LAMPS.clear();
               }
               lv = new LampViews();
               LAMPS.put(chr, lv);
            }
            if (lv.drawStamp != stamp) {
               lv.n = 0;
            }
         }
         int t = lv.tile[j];
         if (t < 0 || t >= ShadowAtlas.MAX_TILES || TILE_OWNER[t] != chr || TILE_KIND[t] != j) {
            t = allocTile(chr, j);
            if (t < 0) {
               return;
            }
            lv.tile[j] = t;
         }
         TILE_SEEN[t] = stamp;
         lampViewsNow++;
         int o = t * 4, o3 = t * 3;
         float lox = lx - mx, loy = ly - my, loz = lz - mz; // the lamp from the caster's centre now
         float mdx = lox - TILE_LAMP_NEXT[o3], mdy = loy - TILE_LAMP_NEXT[o3 + 1], mdz = loz - TILE_LAMP_NEXT[o3 + 2];
         boolean moved = mdx * mdx + mdy * mdy + mdz * mdz > 0.04F;
         boolean due = TILE_DRAWN[t] == 0L
            || lampDrawsNow < Math.max(1, Config.SUN_SHADOW_LAMP_BUDGET) && (moved || stamp - TILE_DRAWN[t] >= lampStaleFrames)
            || rateHz <= 0 && chr instanceof zombie.characters.IsoPlayer && TILE_DRAWN[t] != stamp;
         if (due) {
            TILE_NEXT[o] = f.ox + mx - px;
            TILE_NEXT[o + 1] = f.oy + my - py;
            TILE_NEXT[o + 2] = mz - pz;
            TILE_NEXT[o + 3] = half;
            TILE_LAMP_NEXT[o3] = lox;
            TILE_LAMP_NEXT[o3 + 1] = loy;
            TILE_LAMP_NEXT[o3 + 2] = loz;
            if (atlasSameFrame) {
               System.arraycopy(TILE_NEXT, o, TILE_OFF, o, 4); // drawn before this frame's pass: its content now
               System.arraycopy(TILE_LAMP_NEXT, o3, TILE_LAMP, o3, 3);
               TILE_HAS[t] = true;
            }
            TILE_SCHED[t] = stamp;
            TILE_DRAWN[t] = stamp;
            drawsNow++;
            lampDrawsNow++;
            lampDraws++;
            if (lv.n < 2) {
               int q = lv.n * 8;
               lv.draw[q] = t;
               lv.draw[q + 1] = f.ox + mx;
               lv.draw[q + 2] = f.oy + my;
               lv.draw[q + 3] = mz;
               lv.draw[q + 4] = half;
               lv.draw[q + 5] = f.ox + lx;
               lv.draw[q + 6] = f.oy + ly;
               lv.draw[q + 7] = lz;
               lv.n++;
               lv.drawStamp = stamp;
            }
         }
         if (TILE_HAS[t]) {
            int c = base + (LAMP0 + 3 * j) * 4;
            d[c] = t;
            d[c + 1] = TILE_OFF[o + 3];
            d[c + 4] = px - f.ox + TILE_OFF[o];
            d[c + 5] = py - f.oy + TILE_OFF[o + 1];
            d[c + 6] = pz + TILE_OFF[o + 2];
            d[c + 8] = TILE_LAMP[o3];
            d[c + 9] = TILE_LAMP[o3 + 1];
            d[c + 10] = TILE_LAMP[o3 + 2];
            lampLooks++;
         }
         j++;
      }
   }

   private static void sunTile(Frame f, zombie.iso.IsoMovingObject chr, int base) {
      float[] d = f.data;
      IsoGameCharacter ch = chr instanceof IsoGameCharacter c ? c : null;
      float[] vs = null;
      if (ch == null) {
         vs = VEHICLE_TILES.get(chr);
         if (vs == null) {
            if (VEHICLE_TILES.size() > 512) {
               VEHICLE_TILES.clear();
            }
            vs = new float[] {-1F, 0F, 0F, 0F, 0F, 0F};
            VEHICLE_TILES.put(chr, vs);
         }
      }
      int tile = ch != null ? ch.pzoptShadowTile : (int)vs[0];
      if (tile < 0 || tile >= ShadowAtlas.MAX_TILES || TILE_OWNER[tile] != chr || TILE_KIND[tile] != -1) {
         tile = allocTile(chr, -1);
         if (tile < 0) {
            return;
         }
      }
      TILE_SEEN[tile] = stamp;
      castersNow++;
      float px = chr.getX(), py = chr.getY(), pz = chr.getZ() * METRIC_Z;
      int budget = meshBudget; // (0 between bursts: an earlier pose, at the character's position now)
      long period = meshStaleFrames;
      long sunStep = SunShadow.stepSerial();
      boolean due = TILE_DRAWN[tile] == 0L
         || drawsNow < budget && (stamp - TILE_DRAWN[tile] >= period || TILE_SUN[tile] != sunStep)
         || rateHz <= 0 && chr instanceof zombie.characters.IsoPlayer && TILE_DRAWN[tile] != stamp; // a player's own shadow never waits a turn
      int pq = tile * 6;
      float vax = 0F, vay = 0F, vaz = 0F;
      if (ch == null && Config.SUN_SHADOW_STATIC_VEHICLES && chr instanceof zombie.vehicles.BaseVehicle bv) {
         // a vehicle that has not moved since its tile was drawn under this sun step: the tile already holds it (a redraw
         // gives the same depth); parked cars were half the mesh draws of a town drive (2026-10-09)
         vax = bv.getAngleX();
         vay = bv.getAngleY();
         vaz = bv.getAngleZ();
         if (due && TILE_DRAWN[tile] != 0L && TILE_SUN[tile] == sunStep && TILE_POSE[pq] == px && TILE_POSE[pq + 1] == py && TILE_POSE[pq + 2] == pz
            && TILE_POSE[pq + 3] == vax && TILE_POSE[pq + 4] == vay && TILE_POSE[pq + 5] == vaz) {
            due = false;
            staticVehicleSkips++;
         }
      }
      int o = tile * 4;
      if (due) {
         // the draw happens at the end of this frame's world pass (ShadowAtlas.flush): its parameters wait in TILE_NEXT and
         // become the tile's (TILE_OFF) at the next frame's queue, when the pass reads the new content
         float ax = d[base + 4], ay = d[base + 5], az = d[base + 6], r = d[base + 7], bx = d[base + 8], by = d[base + 9], bz = d[base + 10];
         float len = (float)Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
         float half = Math.max(0.4F, Math.min(3.5F, (0.5F * len + r) * 1.1F));
         TILE_NEXT[o] = f.ox + 0.5F * (ax + bx) - px;
         TILE_NEXT[o + 1] = f.oy + 0.5F * (ay + by) - py;
         TILE_NEXT[o + 2] = 0.5F * (az + bz) - pz;
         TILE_NEXT[o + 3] = half;
         TILE_SCHED[tile] = stamp;
         TILE_DRAWN[tile] = stamp;
         TILE_SUN[tile] = sunStep;
         TILE_POSE[pq] = px;
         TILE_POSE[pq + 1] = py;
         TILE_POSE[pq + 2] = pz;
         TILE_POSE[pq + 3] = vax;
         TILE_POSE[pq + 4] = vay;
         TILE_POSE[pq + 5] = vaz;
         drawsNow++;
         meshDraws++;
         if (ch != null) {
            ch.pzoptShadowStamp = stamp; // tileFor: this frame's model draw also draws the tile
            ch.pzoptShadowX = px + TILE_NEXT[o];
            ch.pzoptShadowY = py + TILE_NEXT[o + 1];
            ch.pzoptShadowZ = pz + TILE_NEXT[o + 2];
            ch.pzoptShadowHalf = half;
         } else {
            vs[1] = stamp;
            vs[2] = px + TILE_NEXT[o];
            vs[3] = py + TILE_NEXT[o + 1];
            vs[4] = pz + TILE_NEXT[o + 2];
            vs[5] = half;
         }
         if (atlasSameFrame) {
            System.arraycopy(TILE_NEXT, o, TILE_OFF, o, 4); // drawn before this frame's pass: its content now
            TILE_HAS[tile] = true;
         }
      }
      if (!TILE_HAS[tile]) {
         return; // its first draw is this frame's: the capsules until the next
      }
      d[base] = tile;
      d[base + 1] = TILE_OFF[o + 3];
      int c = base + CENTRE * 4; // the tile's centre now: the character's position + the centre's offset at the draw
      d[c] = px - f.ox + TILE_OFF[o];
      d[c + 1] = py - f.oy + TILE_OFF[o + 1];
      d[c + 2] = pz + TILE_OFF[o + 2];
      d[c + 3] = 0F;
   }

   /**
    * Game thread (pzopt.EntityShadow, at the start of the world render, before this frame's casters assign their tiles):
    * the object's sun tile as the atlas holds it while this frame's models draw (drawn last frame): out = tile, half size,
    * the content's centre from the object's position (x, y squares, z metric). False when it has none yet.
    */
   static boolean receiverTile(zombie.iso.IsoMovingObject o, float[] out) {
      int tile;
      if (o instanceof IsoGameCharacter ch) {
         tile = ch.pzoptShadowTile;
      } else {
         float[] vs = VEHICLE_TILES.get(o);
         tile = vs == null ? -1 : (int)vs[0];
      }
      if (tile < 0 || tile >= ShadowAtlas.MAX_TILES || TILE_OWNER[tile] != o || TILE_KIND[tile] != -1 || !TILE_HAS[tile] || stamp - TILE_SEEN[tile] > 1L) {
         return false;
      }
      int q = tile * 4;
      out[0] = tile;
      out[1] = TILE_OFF[q + 3];
      out[2] = TILE_OFF[q];
      out[3] = TILE_OFF[q + 1];
      out[4] = TILE_OFF[q + 2];
      return true;
   }

   /** A tile nobody joined a pass with for 30 frames (its character left the screen), for chr; -1 when all are in use. */
   private static int allocTile(zombie.iso.IsoMovingObject chr, int kind) {
      for (int k = 0; k < ShadowAtlas.MAX_TILES; k++) {
         int t = (tileScan + k) % ShadowAtlas.MAX_TILES;
         if (TILE_OWNER[t] == null || stamp - TILE_SEEN[t] > 30L) {
            tileScan = t + 1;
            Object old = TILE_OWNER[t];
            if (old != null && TILE_KIND[t] >= 0) { // a lamp view
               LampViews ov = LAMPS.get(old);
               if (ov != null && ov.tile[TILE_KIND[t]] == t) {
                  ov.tile[TILE_KIND[t]] = -1;
               }
            } else if (old instanceof IsoGameCharacter oc) {
               if (oc.pzoptShadowTile == t) {
                  oc.pzoptShadowTile = -1;
               }
            } else if (old != null) {
               float[] ovs = VEHICLE_TILES.get(old);
               if (ovs != null && (int)ovs[0] == t) {
                  VEHICLE_TILES.remove(old);
               }
            }
            TILE_KIND[t] = (byte)kind;
            TILE_LAMP_NEXT[t * 3] = TILE_LAMP_NEXT[t * 3 + 1] = TILE_LAMP_NEXT[t * 3 + 2] = 0F;
            if (kind >= 0) {
               TILE_OWNER[t] = chr;
               TILE_DRAWN[t] = 0L;
               TILE_HAS[t] = false;
               TILE_SCHED[t] = 0L;
               return t;
            }
            TILE_OWNER[t] = chr;
            TILE_DRAWN[t] = 0L;
            TILE_HAS[t] = false;
            TILE_SCHED[t] = 0L;
            if (chr instanceof IsoGameCharacter c) {
               c.pzoptShadowTile = t;
            } else {
               float[] vs = VEHICLE_TILES.get(chr);
               if (vs != null) {
                  vs[0] = t;
               }
            }
            return t;
         }
      }
      return -1;
   }

   private static float stockScale = 1F;

   /**
    * The frame's lights: the torches and headlights (sunShadowTorches, TORCH_SLOTS at most), then the static lights
    * (sunShadowStaticLights) in the slots left.
    */
   private static int gatherLights(Frame f) {
      int n = Config.SUN_SHADOW_TORCHES ? gatherTorches(f) : 0;
      f.nt = n;
      if (Config.SUN_SHADOW_STATIC_LIGHTS) {
         n = gatherStatic(f, n);
         if (n > f.nt) {
            staticLightFrames++;
         }
      }
      return n;
   }

   /** The torches and headlights near the camera (LightingJNI's list for the frame), strongest reach first, TORCH_SLOTS at most. */
   private static int gatherTorches(Frame f) {
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      if (torches == null || torches.isEmpty()) {
         return 0;
      }
      TorchSource.refresh(torches); // torchSource: this frame's lens
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float view = (f.screenW + 2.0F * f.screenH) * f.zoom / (64.0F * f.ts) + 4.0F;
      int n = 0;
      float[] score = LIGHT_SCORE;
      for (int i = 0; i < torches.size(); i++) { // the TORCH_SLOTS strongest (strength x reach): a car's tail lights listed first took the torch's slot
         IsoGameCharacter.TorchInfo t = torches.get(i);
         float tx = TorchSource.x(t), ty = TorchSource.y(t); // torchSource: the drawn item's lens (else the native's position)
         if (t.id == 0 || t.strength <= 0.05F || Math.abs(tx - cx) > view + t.dist || Math.abs(ty - cy) > view + t.dist) {
            continue;
         }
         float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
         if (len < 1e-4F) {
            continue;
         }
         boolean car = t.id >= 4096 || t.focusing > 0;
         float sc = t.strength * Math.max(1.0F, t.dist);
         boolean dup = false;
         for (int j = 0; j < n; j++) { // the game sends every lit item of a player at the same spot: one shadow, the strongest
            if (Math.abs(f.lA[j * 4] - (tx - f.ox)) < 0.05F && Math.abs(f.lA[j * 4 + 1] - (ty - f.oy)) < 0.05F) {
               dup = score[j] >= sc;
               if (!dup) { // drop the weaker one, the new one goes in by its score
                  for (int m = j; m < n - 1; m++) {
                     System.arraycopy(f.lA, (m + 1) * 4, f.lA, m * 4, 4);
                     System.arraycopy(f.lB, (m + 1) * 4, f.lB, m * 4, 4);
                     f.lK[m] = f.lK[m + 1];
                     score[m] = score[m + 1];
                  }
                  n--;
               }
               break;
            }
         }
         if (dup) {
            continue;
         }
         int at = n;
         while (at > 0 && score[at - 1] < sc) {
            at--;
         }
         if (at >= TORCH_SLOTS) {
            continue;
         }
         for (int j = Math.min(n, TORCH_SLOTS - 1); j > at; j--) { // shift the weaker ones down
            System.arraycopy(f.lA, (j - 1) * 4, f.lA, j * 4, 4);
            System.arraycopy(f.lB, (j - 1) * 4, f.lB, j * 4, 4);
            f.lK[j] = f.lK[j - 1];
            score[j] = score[j - 1];
         }
         n = Math.min(n + 1, TORCH_SLOTS);
         score[at] = sc;
         int k = at * 4;
         f.lA[k] = tx - f.ox;
         f.lA[k + 1] = ty - f.oy;
         f.lA[k + 2] = t.z * METRIC_Z + (car ? 0.75F : t.pzoptSrc ? TorchSource.height(t, 0F) * METRIC_Z : 1.35F); // the lamp's height: a headlight, a torch in the hand (torchSource: the lens's)
         f.lA[k + 3] = Math.max(1.0F, t.dist);
         f.lB[k] = t.angleX / len;
         f.lB[k + 1] = t.angleY / len;
         f.lB[k + 2] = t.cone ? t.dot : -2.0F;
         f.lB[k + 3] = t.strength;
         f.lK[at] = car ? 2F : 1F;
         f.lSrc[at] = null;
      }
      return n;
   }

   /**
    * sunShadowStaticLights: the static lights that are on (IsoCell's lamp list: street lamps, lamps, wall lights, fires) near
    * the camera into slots n.. (nearest pool first: the one the camera stands in leads), each a point light (kind 3, no
    * cone) at its fixture's height; with sunShadowRoomLights the rooms' ceiling lights that are on too, ranked with them
    * (kind 5: each lit room's fixture point(s) under the ceiling, StaticLightShadow.roomPoints). Which casters each one
    * shades is decided per caster (append: staticPick).
    */
   private static int gatherStatic(Frame f, int n) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
      if (cell == null || n >= MAX_LIGHTS) {
         return n;
      }
      java.util.Stack<zombie.iso.IsoLightSource> list = cell.getLamppostPositions();
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      // the burning characters' own lights (IsoGameCharacter.onFireLightSource): the game moves one a whole square at a time
      // (a new light on each square change), the shadows take the character's own position so they turn smoothly as it walks
      int nb = 0;
      java.util.ArrayList<IsoGameCharacter> burning = zombie.iso.objects.IsoFireManager.CharactersOnFire_Stack;
      for (int i = 0; i < burning.size() && nb < BURN_LIGHT.length; i++) {
         IsoGameCharacter chr = burning.get(i);
         if (chr != null && chr.onFireLightSource != null) {
            BURN_LIGHT[nb] = chr.onFireLightSource;
            BURN_CHR[nb++] = chr;
         }
      }
      float view = (f.screenW + 2.0F * f.screenH) * f.zoom / (64.0F * f.ts) + 4.0F;
      int nc = 0;
      for (int i = 0; i < list.size(); i++) {
         zombie.iso.IsoLightSource l = list.get(i);
         if (!StaticLightShadow.usable(l)) {
            continue;
         }
         if (StaticLightShadow.fire(l) && StaticLightShadow.lightbarColour(l.r, l.g, l.b)) {
            IsoGridSquare lsq = cell.getGridSquare(l.x, l.y, l.z);
            if (lsq != null && lsq.isVehicleIntersecting()) {
               staticVehicleLights++;
               continue; // an emergency lightbar's world light: the car's own flashing light, not a static one
            }
         }
         float r = Math.min(l.radius, 20);
         float dx = l.x + 0.5F - cx, dy = l.y + 0.5F - cy;
         if (Math.abs(dx) > view + r || Math.abs(dy) > view + r) {
            continue;
         }
         int at = staticSlot(nc, StaticLightShadow.order(dx, dy, r));
         if (at >= 0) {
            STATIC_ROOM[at] = null;
            STATIC_CAND[at] = l;
            nc = Math.max(nc, at + 1);
         }
      }
      int roomsOn = 0;
      if (Config.SUN_SHADOW_ROOM_LIGHTS) {
         // sunShadowRoomLights: the ceiling lights that are on, each room rectangle's fixture point(s) (StaticLightShadow.roomPoints)
         java.util.ArrayList<zombie.iso.IsoRoomLight> rooms = cell.roomLights;
         int camZ = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
         ROOMS_SEEN.clear();
         for (int i = 0; i < rooms.size(); i++) {
            zombie.iso.IsoRoomLight rl = rooms.get(i);
            if (!StaticLightShadow.roomOn(rl) || rl.x - cx > view || cx - (rl.x + rl.width) > view || rl.y - cy > view || cy - (rl.y + rl.height) > view
               || ROOMS_SEEN.put(rl.room, Boolean.TRUE) != null) {
               continue; // off, out of view, or its room listed already (one light a rectangle, switched together)
            }
            roomsOn++;
            java.util.ArrayList<zombie.iso.RoomDef.RoomRect> rr = rl.room.def.rects;
            int nr = Math.min(rr.size(), ROOM_RECTS.length / 4);
            for (int j = 0; j < nr; j++) {
               zombie.iso.RoomDef.RoomRect q = rr.get(j);
               ROOM_RECTS[j * 4] = q.x;
               ROOM_RECTS[j * 4 + 1] = q.y;
               ROOM_RECTS[j * 4 + 2] = q.w;
               ROOM_RECTS[j * 4 + 3] = q.h;
            }
            if (nr == 0) {
               ROOM_RECTS[0] = rl.x;
               ROOM_RECTS[1] = rl.y;
               ROOM_RECTS[2] = rl.width;
               ROOM_RECTS[3] = rl.height;
               nr = 1;
            }
            int k = StaticLightShadow.roomPoints(ROOM_RECTS, nr, ROOM_PTS);
            float zPen = 8F * Math.abs(rl.z - camZ); // the camera's floor first (a room on another floor shades only who stands there)
            for (int p = 0; p < k; p++) {
               float px = ROOM_PTS[p * 3], py = ROOM_PTS[p * 3 + 1], r = ROOM_PTS[p * 3 + 2];
               int at = staticSlot(nc, StaticLightShadow.order(px - cx, py - cy, r) + zPen);
               if (at >= 0) {
                  STATIC_PT[at * 3] = px;
                  STATIC_PT[at * 3 + 1] = py;
                  STATIC_PT[at * 3 + 2] = r;
                  STATIC_ROOM[at] = rl;
                  STATIC_CAND[at] = null;
                  nc = Math.max(nc, at + 1);
               }
            }
         }
         ROOMS_SEEN.clear();
      }
      while (n < MAX_LIGHTS && nc > 0) { // nearest pool first (a selection over a handful)
         int best = 0;
         for (int i = 1; i < nc; i++) {
            if (STATIC_ORDER[i] < STATIC_ORDER[best]) {
               best = i;
            }
         }
         zombie.iso.IsoLightSource l = STATIC_CAND[best];
         zombie.iso.IsoRoomLight rl = STATIC_ROOM[best];
         int k = n * 4;
         f.lB[k] = 0F;
         f.lB[k + 1] = 0F;
         f.lB[k + 2] = -2.0F; // no cone
         f.lSrc[n] = l;
         f.lRoom[n] = rl;
         if (rl != null) {
            // a room's ceiling light (kind 5): its fixture point just under the ceiling, a flat share (StaticLightShadow.roomShare)
            f.lA[k] = STATIC_PT[best * 3] - f.ox;
            f.lA[k + 1] = STATIC_PT[best * 3 + 1] - f.oy;
            f.lA[k + 2] = rl.z * METRIC_Z + StaticLightShadow.CEIL_H;
            f.lA[k + 3] = STATIC_PT[best * 3 + 2];
            f.lB[k + 3] = StaticLightShadow.ROOM_SHARE;
            f.lK[n] = StaticLightShadow.ROOM_KIND;
         } else {
            IsoGameCharacter burner = null;
            for (int b = 0; b < nb && burner == null; b++) {
               if (BURN_LIGHT[b] == l) {
                  burner = BURN_CHR[b];
               }
            }
            // a burning character's light: at the character (its square holds the light: the own-fire guard in reach() keeps it)
            f.lA[k] = (burner != null ? burner.getX() : l.x + 0.5F) - f.ox;
            f.lA[k + 1] = (burner != null ? burner.getY() : l.y + 0.5F) - f.oy;
            f.lA[k + 2] = l.z * METRIC_Z + (burner != null ? StaticLightShadow.BURN_H : StaticLightShadow.height(l));
            f.lA[k + 3] = Math.max(1.0F, Math.min(l.radius, 20));
            f.lB[k + 3] = StaticLightShadow.strength(l.r, l.g, l.b);
            f.lK[n] = StaticLightShadow.fire(l) ? StaticLightShadow.KIND_FIRE : StaticLightShadow.KIND;
         }
         STATIC_ORDER[best] = STATIC_ORDER[--nc];
         STATIC_CAND[best] = STATIC_CAND[nc];
         STATIC_ROOM[best] = STATIC_ROOM[nc];
         System.arraycopy(STATIC_PT, nc * 3, STATIC_PT, best * 3, 3);
         n++;
      }
      java.util.Arrays.fill(STATIC_CAND, 0, STATIC_CAND.length, null);
      java.util.Arrays.fill(STATIC_ROOM, 0, STATIC_ROOM.length, null);
      java.util.Arrays.fill(BURN_LIGHT, 0, nb, null);
      java.util.Arrays.fill(BURN_CHR, 0, nb, null);
      devStaticFrame = false;
      if (Config.DEV_STATIC_LIGHT_LOG > 0 && ++devStaticN % Config.DEV_STATIC_LIGHT_LOG == 0) {
         devStaticFrame = true; // staticPick logs this frame's casters too
         StringBuilder sb = new StringBuilder("capsule shadows: dev static lights (camera " + (int)cx + "," + (int)cy + ", candidates in view " + list.size() + " listed, " + roomsOn + " room lights on in view of "
            + cell.roomLights.size() + ", torches " + f.nt + "):");
         for (int l = f.nt; l < n; l++) {
            zombie.iso.IsoLightSource src = f.lSrc[l];
            if (src == null) {
               zombie.iso.IsoRoomLight rl = f.lRoom[l];
               sb.append(String.format(java.util.Locale.ROOT, " [%.1f,%.1f,%d r %.1f %s]", f.lA[l * 4] + f.ox, f.lA[l * 4 + 1] + f.oy, rl == null ? -1 : rl.z, f.lA[l * 4 + 3],
                  rl == null ? "?" : StaticLightShadow.describe(rl)));
               continue;
            }
            sb.append(String.format(java.util.Locale.ROOT, " [%d,%d,%d r %d rgb %.2f,%.2f,%.2f kind %.0f at %.2f,%.2f h %.2f %s]", src.x, src.y, src.z, src.radius, src.r, src.g, src.b, f.lK[l],
               f.lA[l * 4] + f.ox, f.lA[l * 4 + 1] + f.oy, f.lA[l * 4 + 2] - src.z * METRIC_Z, StaticLightShadow.describe(src)));
         }
         sb.append(" | ").append(StaticLightShadow.stats()).append(" picks ").append(staticPicks).append(" | loaded lights on:");
         int shown = 0;
         for (int i = 0; i < list.size() && shown < 24; i++) { // where to stand (the rigs): every usable light within 60 squares
            zombie.iso.IsoLightSource src = list.get(i);
            if (StaticLightShadow.usable(src) && Math.abs(src.x - cx) < 60 && Math.abs(src.y - cy) < 60) {
               sb.append(String.format(java.util.Locale.ROOT, " %d,%d,%d r%d %s;", src.x, src.y, src.z, src.radius, StaticLightShadow.describe(src)));
               shown++;
            }
         }
         if (devStaticN == Config.DEV_STATIC_LIGHT_LOG) {
            // the first time: the rooms round the camera on its floor (where to pin the rig's zombies), a letter a room, '.' none,
            // '*' a lit room's fixture point; rows north to south, columns west to east
            int camZ = (int)Math.floor(IsoCamera.frameState.camCharacterZ), x0 = (int)cx - 8, y0 = (int)cy - 8;
            java.util.ArrayList<Object> seen = new java.util.ArrayList<>();
            StringBuilder g = new StringBuilder("capsule shadows: dev rooms round " + x0 + "," + y0 + " (17 x 17, z " + camZ + "):");
            for (int y = y0; y <= y0 + 16; y++) {
               g.append("\n  ").append(y).append(' ');
               for (int x = x0; x <= x0 + 16; x++) {
                  zombie.iso.IsoGridSquare q = cell.getGridSquare(x, y, camZ);
                  zombie.iso.areas.IsoRoom rm = q == null ? null : q.getRoom();
                  boolean fix = false;
                  for (int l = f.nt; l < n; l++) {
                     fix |= f.lRoom[l] != null && f.lRoom[l].z == camZ && (int)Math.floor(f.lA[l * 4] + f.ox) == x && (int)Math.floor(f.lA[l * 4 + 1] + f.oy) == y;
                  }
                  if (rm != null && !seen.contains(rm)) {
                     seen.add(rm);
                  }
                  g.append(fix ? '*' : rm == null ? '.' : (char)('A' + seen.indexOf(rm) % 26));
               }
            }
            g.append("\n  rooms:");
            for (int i = 0; i < seen.size(); i++) {
               zombie.iso.areas.IsoRoom rm = (zombie.iso.areas.IsoRoom)seen.get(i);
               boolean lit = !rm.roomLights.isEmpty() && StaticLightShadow.roomOn(rm.roomLights.get(0));
               g.append(' ').append((char)('A' + i % 26)).append('=').append(rm.def == null ? "?" : rm.def.name).append(lit ? "(lit)" : "");
            }
            Log.info(g.toString());
         }
         if (Config.SUN_SHADOW_ROOM_LIGHTS) {
            sb.append(" | lit rooms on the camera's floor:");
            shown = 0;
            int camZ = (int)Math.floor(IsoCamera.frameState.camCharacterZ);
            for (int i = 0; i < cell.roomLights.size() && shown < 16; i++) { // where to stand (the rigs)
               zombie.iso.IsoRoomLight rl = cell.roomLights.get(i);
               if (StaticLightShadow.roomOn(rl) && rl.z == camZ && rl.room.roomLights.indexOf(rl) == 0 && Math.abs(rl.x - cx) < 30 && Math.abs(rl.y - cy) < 30) {
                  sb.append(' ').append(StaticLightShadow.describe(rl)).append(';');
                  shown++;
               }
            }
         }
         Log.info(sb.toString());
      }
      return n;
   }

   /**
    * gatherStatic: where a candidate of this order goes among the nc listed (a new one while there is room, else the place of
    * the farthest one when it is nearer, else -1); its order is written there.
    */
   private static int staticSlot(int nc, float order) {
      int at = nc;
      if (nc >= STATIC_ORDER.length) {
         at = 0;
         for (int i = 1; i < nc; i++) {
            if (STATIC_ORDER[i] > STATIC_ORDER[at]) {
               at = i;
            }
         }
         if (STATIC_ORDER[at] <= order) {
            return -1;
         }
      }
      STATIC_ORDER[at] = order;
      return at;
   }

   /**
    * sunShadowStaticLights: the caster just appended keeps the (at most two) static lights that light it most, among those
    * that reach it as the game's lamp model has it (StaticLightShadow.reach: and the wall plane its shadow stops at, f.llim), into f.lsel (frame slots, -1 none).
    */
   private static void staticPick(Frame f, int c, float mx, float my, float r, float cz) {
      f.lsel[c * 2] = -1;
      f.lsel[c * 2 + 1] = -1;
      int ns = f.nl - f.nt;
      if (ns <= 0) {
         return;
      }
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      int sx = (int)Math.floor(mx + f.ox), sy = (int)Math.floor(my + f.oy), sz = (int)Math.floor(cz / METRIC_Z + 0.01F);
      boolean any = false;
      for (int i = 0; i < ns; i++) {
         int l = f.nt + i;
         float dx = mx - f.lA[l * 4], dy = my - f.lA[l * 4 + 1];
         float dl = (float)Math.sqrt(dx * dx + dy * dy), reach = f.lA[l * 4 + 3];
         zombie.iso.IsoRoomLight rl = f.lRoom[l];
         float sh = dl > reach + r ? 0F
            : rl != null ? StaticLightShadow.roomShare(Math.max(0F, dl - r), reach) * (1F - 0.05F * dl / reach) // (the nearer fixture first on a tie)
            : StaticLightShadow.share(Math.max(0F, dl - r), reach, f.lB[l * 4 + 3]);
         STATIC_CODE[i] = -2; // dev log: not asked (too faint)
         STATIC_WHY[i] = '-';
         zombie.iso.IsoLightSource src = f.lSrc[l];
         if (sh > 0.02F && src != null && src == pickOwnFire) {
            sh = 0F; // the caster's own fire (a burning character): never its own shadow, whichever square its capsules' middle is on
            STATIC_WHY[i] = 'O';
         }
         if (sh > 0.02F) {
            float px = f.lA[l * 4] + f.ox, py = f.lA[l * 4 + 1] + f.oy;
            int code = rl != null ? StaticLightShadow.roomReach(cell, rl, px, py, reach, sx, sy, sz) : StaticLightShadow.reach(cell, src, sx, sy, sz);
            STATIC_WHY[i] = StaticLightShadow.why;
            STATIC_CODE[i] = code;
            if (code == StaticLightShadow.NONE) {
               sh = 0F;
            } else {
               // the wall plane the code is relative to: the light's square (a room light's: its point's, as roomReach keys it)
               STATIC_LIM[i] = rl != null ? StaticLightShadow.planeW(code, (int)Math.floor(px), (int)Math.floor(py), f.ox, f.oy) : StaticLightShadow.planeW(code, src.x, src.y, f.ox, f.oy); // the wall plane the shadow stops at (the pair's w)
            }
         }
         STATIC_SHARE[i] = sh;
         any |= sh > 0.02F;
      }
      if (any) {
         StaticLightShadow.pickTwo(STATIC_SHARE, ns, STATIC_PICK);
         for (int j = 0; j < 2; j++) {
            if (STATIC_PICK[j] >= 0) {
               f.lsel[c * 2 + j] = (byte)(f.nt + STATIC_PICK[j]);
               f.llim[c * 2 + j] = STATIC_LIM[STATIC_PICK[j]];
               staticPicks++;
            }
         }
      }
      if (devStaticFrame && c < 16) {
         StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "capsule shadows: dev static caster %d sq %d,%d,%d room %b picks %d,%d:", c, sx, sy, sz,
            cell.getGridSquare(sx, sy, sz) != null && cell.getGridSquare(sx, sy, sz).getRoom() != null, f.lsel[c * 2], f.lsel[c * 2 + 1]));
         for (int i = 0; i < ns; i++) {
            zombie.iso.IsoLightSource src = f.lSrc[f.nt + i];
            float dx = mx - f.lA[(f.nt + i) * 4], dy = my - f.lA[(f.nt + i) * 4 + 1];
            float lim = STATIC_CODE[i] <= 0 ? (STATIC_CODE[i] == 0 ? -1F : -2F) : StaticLightShadow.planeDistance(STATIC_LIM[i], f.lA[(f.nt + i) * 4], f.lA[(f.nt + i) * 4 + 1], mx, my);
            sb.append(String.format(java.util.Locale.ROOT, " [%s d %.1f share %.2f lim %.2f w %.0f %c]", src != null ? src.x + "," + src.y + "," + src.z : "room", (float)Math.sqrt(dx * dx + dy * dy), STATIC_SHARE[i],
               lim, STATIC_CODE[i] > 0 ? STATIC_LIM[i] : 0F, STATIC_WHY[i])); // lim: the wall's distance from the light along the ray (1e4 none), -1 = the light does not reach it (StaticLightShadow.reach), -2 = too faint to ask
         }
         Log.info(sb.toString());
      }
   }

   /** The sun share through the grid and the clouds at an object outdoors: renderPrepParallel's answer, or computed here. */
   private static float sunShare(zombie.iso.IsoMovingObject o) {
      float v = RenderPrep.sunVis(o);
      return v == v ? v : SunShadow.visibleAt(o.getX(), o.getY(), o.getZ()) * CloudShadow.transmittanceAt(o.getX(), o.getY(), o.getZ());
   }

   /** renderPrepParallel: this frame's pass will ask characters and vehicles their sun share (any thread). */
   static boolean wantsSunVis() {
      return Overrides.enabled() && Config.SUN_SHADOWS && (Config.SUN_SHADOW_CHARACTERS || Config.SUN_SHADOW_VEHICLES) && !failed && SunShadow.dir[3] > 0F;
   }

   /** renderPrepParallel, a frame worker: what add / addAtlas / addVehicle compute as the object's sun share (NaN: not asked). */
   static float sunVisFor(zombie.iso.IsoMovingObject o) {
      if (!(o instanceof zombie.characters.IsoGameCharacter) && !(o instanceof zombie.vehicles.BaseVehicle)) {
         return Float.NaN;
      }
      IsoGridSquare sq = o.getCurrentSquare();
      if (sq == null || !sq.isOutside()) {
         return Float.NaN; // not asked (the pass tests the square itself)
      }
      return SunShadow.visibleAt(o.getX(), o.getY(), o.getZ()) * CloudShadow.transmittanceAt(o.getX(), o.getY(), o.getZ());
   }

   /** Game thread, where a character's stock shadow is drawn: its capsules join this frame's pass. */
   public static void add(IsoGameCharacter chr) {
      stockScale = 1F;
      Frame f = current;
      if (f == null || f.n >= MAX || chr == null || !Config.SUN_SHADOW_CHARACTERS) {
         return;
      }
      pickOwnFire = chr.onFireLightSource; // staticPick: a burning character never takes its own fire's shadow
      boolean animal = chr.isAnimal();
      if (animal && !Config.SUN_SHADOW_ANIMALS) {
         return;
      }
      if (chr.isSeatedInVehicle()) {
         return; // a driver or passenger: the vehicle's capsules are the shadow there, like stock's renderShadow skipping them
      }
      IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null || !chr.hasAnimationPlayer()) {
         return;
      }
      // a character standing in the static world's shade casts no sun shadow of its own (the shade is already there): its
      // sun share through the grid (SunShadow's cached march, the same that darkens its model) scales the sun shadow
      float sunVis = f.sunOn && sq.isOutside() ? sunShare(chr) : 0F;
      boolean sun = sunVis > 0.05F;
      boolean devPlayerNow = Config.DEV_CASTER_TRACE > 0 && !animal && chr instanceof zombie.characters.IsoPlayer pl && pl.isLocalPlayer();
      devTraceNow = devPlayerNow && ++devTraceN % Config.DEV_CASTER_TRACE == 0;
      if (devPlayerNow) {
         f.devInfo = String.format(java.util.Locale.ROOT, "%.2f,%.2f facing %.0f sunVis %.2f", chr.getX(), chr.getY(), Math.toDegrees(chr.getAnimAngleRadians()), sunVis);
      }
      if (devTraceNow) {
         Log.info(String.format(java.util.Locale.ROOT, "capsule shadows: dev caster %.2f,%.2f,%.2f facing %.1f deg anim %.1f deg outside %b sunVis %.3f (prep %.3f) cloud %.3f %s",
            chr.getX(), chr.getY(), chr.getZ(), Math.toDegrees(chr.getDirectionAngleRadians()), Math.toDegrees(chr.getAnimAngleRadians()), sq.isOutside(), sunVis,
            RenderPrep.sunVis(chr), CloudShadow.transmittanceAt(chr.getX(), chr.getY(), chr.getZ()), SunShadow.devMarch(chr.getX(), chr.getY(), chr.getZ())));
      }
      if (!sun && f.nl == 0) {
         return; // indoors or in the shade in daylight without a torch around: nothing to cast
      }
      float alpha = chr.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return; // out of sight: its shadow would give it away
      }
      AnimationPlayer ap = chr.getAnimationPlayer();
      if (ap == null || !ap.isReady()) {
         return;
      }
      float cx = chr.getX() - f.ox, cy = chr.getY() - f.oy, cz = chr.getZ() * METRIC_Z;
      float[] pts = animal ? null : ap.pzoptCapsules();
      if (pts == null) {
         if (animal || !points(ap, SCRATCH)) {
            // an animal (or a skeleton without the human bones): one capsule along the longest extent of all its bones
            float size = animal ? ((zombie.characters.animals.IsoAnimal)chr).getAnimalSize() : 1F;
            if (!boneCapsule(ap, size, cx, cy, cz, CAPS)) {
               return;
            }
            float boundR = CAPS[6];
            int nc = skeletonCapsules(ap, size, cx, cy, cz, CAPS);
            append(f, CAPS, nc, cz, alpha, 2F, sun ? sunVis : 0F);
            f.data[(f.n - 1) * TEXELS * 4 + 7] = Math.max(f.data[(f.n - 1) * TEXELS * 4 + 7], boundR + 0.02F); // the bounding capsule round the whole animal
            assignTile(f, chr, sun);
            animals++;
            if (sun) {
               stockScale = 1F - Math.max(0, Math.min(100, Config.SUN_SHADOW_STOCK_FADE_PCT)) / 100F * Math.min(1F, sunVis);
            }
            return;
         }
         pts = SCRATCH;
         computedHere++;
      }
      float[] caps = CAPS;
      for (int s = 0; s < K; s++) {
         int ia = SEG_A[s] * 3, ib = SEG_B[s] * 3;
         float ax = pts[ia], ay = pts[ia + 1], az = pts[ia + 2];
         float bx = pts[ib], by = pts[ib + 1], bz = pts[ib + 2];
         if (s == 1) { // the head: from the neck through the head bone and on
            bx += (bx - ax) * 0.6F;
            by += (by - ay) * 0.6F;
            bz += (bz - az) * 0.6F;
         }
         int o = s * 7;
         caps[o] = cx + ax;
         caps[o + 1] = cy + ay;
         caps[o + 2] = cz + az;
         caps[o + 3] = cx + bx;
         caps[o + 4] = cy + by;
         caps[o + 5] = cz + bz;
         caps[o + 6] = RADIUS[s] * 1.5F;
      }
      append(f, caps, K, cz, alpha, 0F, sun ? sunVis : 0F);
      assignTile(f, chr, sun);
      if (devPlayerNow) {
         f.devPlayer = f.n - 1;
      }
      if (devTraceNow) {
         int base = (f.n - 1) * TEXELS * 4;
         float[] d = f.data;
         Log.info(String.format(java.util.Locale.ROOT, "capsule shadows: dev caster tile %.0f half %.2f alpha %.2f bound %.2f,%.2f,%.2f - %.2f,%.2f,%.2f r %.2f pts %s",
            d[base], d[base + 1], alpha, d[base + 4], d[base + 5], d[base + 6], d[base + 8], d[base + 9], d[base + 10], d[base + 7], pts == SCRATCH ? "here" : "worker"));
      }
      characters++;
      if (sun) {
         stockScale = 1F - Math.max(0, Math.min(100, Config.SUN_SHADOW_STOCK_FADE_PCT)) / 100F * Math.min(1F, sunVis);
      }
   }

   private static long animals;
   private static long devTraceN; // dev (devCasterTrace)
   private static boolean devTraceNow;
   private static final float[] SEG_LEN = new float[K];
   private static final int[] SEG_BONE = new int[K];

   /**
    * An animal's body as capsules from its own skeleton (boneCapsule's bones already in boneScratch, caps[0..6] the bounding
    * capsule): the bounding capsule thinned to the trunk, then the longest bone-to-parent segments (the legs, the neck, the
    * head, the tail), each as thick as a third of its length within limits. Returns the count (at most K).
    */
   private static int skeletonCapsules(AnimationPlayer ap, float size, float cx, float cy, float cz, float[] caps) {
      caps[6] *= 0.55F; // the trunk
      zombie.core.skinnedmodel.model.SkinningData sd = ap.getSkinningData();
      java.util.List<Integer> parents = sd == null ? null : sd.skeletonHierarchy;
      int count = Math.min(ap.getModelTransformsCount(), boneScratch.length / 3);
      if (parents == null || parents.size() < count) {
         return 1;
      }
      int kept = 0;
      for (int b = 1; b < count; b++) {
         int pb = parents.get(b);
         if (pb < 0 || pb >= count) {
            continue;
         }
         float dx = (boneScratch[b * 3] - boneScratch[pb * 3]) * size, dy = (boneScratch[b * 3 + 1] - boneScratch[pb * 3 + 1]) * size;
         float dz = (boneScratch[b * 3 + 2] - boneScratch[pb * 3 + 2]) * size;
         float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
         if (len < 0.04F) {
            continue;
         }
         int at = kept < K - 1 ? kept++ : -1;
         if (at < 0) {
            at = 0;
            for (int q = 1; q < K - 1; q++) {
               if (SEG_LEN[q] < SEG_LEN[at]) at = q;
            }
            if (len <= SEG_LEN[at]) {
               continue;
            }
         }
         SEG_LEN[at] = len;
         SEG_BONE[at] = b;
      }
      for (int i = 0; i < kept; i++) {
         int b = SEG_BONE[i], pb = parents.get(b);
         int o = (i + 1) * 7;
         caps[o] = cx + boneScratch[pb * 3] * size;
         caps[o + 1] = cy + boneScratch[pb * 3 + 1] * size;
         caps[o + 2] = cz + Math.max(0F, boneScratch[pb * 3 + 2] * size);
         caps[o + 3] = cx + boneScratch[b * 3] * size;
         caps[o + 4] = cy + boneScratch[b * 3 + 1] * size;
         caps[o + 5] = cz + Math.max(0F, boneScratch[b * 3 + 2] * size);
         caps[o + 6] = Math.max(0.03F, Math.min(0.16F * Math.max(0.4F, size), SEG_LEN[i] * 0.3F));
      }
      return kept + 1;
   }
   private static int[] allBones = new int[0];
   private static float[] boneScratch = new float[0];

   /**
    * One capsule round every bone of the skeleton (an animal: its size applied as Model.boneToWorldCoords does), along the
    * box's longest extent, fat enough to hold the body (the half of the other two extents plus a margin), into caps[0..6].
    */
   private static boolean boneCapsule(AnimationPlayer ap, float size, float cx, float cy, float cz, float[] caps) {
      int count = ap.getModelTransformsCount();
      if (count <= 0 || count > 512) {
         return false;
      }
      if (allBones.length != count) {
         allBones = new int[count];
         for (int i = 0; i < count; i++) {
            allBones[i] = i;
         }
         boneScratch = new float[count * 3];
      }
      if (!ShadowPrep.capsulePoints(ap, allBones, boneScratch)) {
         return false;
      }
      float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
      for (int i = 0; i < count; i++) {
         float x = boneScratch[i * 3] * size, y = boneScratch[i * 3 + 1] * size, z = boneScratch[i * 3 + 2] * size;
         x0 = Math.min(x0, x);
         x1 = Math.max(x1, x);
         y0 = Math.min(y0, y);
         y1 = Math.max(y1, y);
         z0 = Math.min(z0, z);
         z1 = Math.max(z1, z);
      }
      z0 = Math.max(0F, z0);
      float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
      float mx = (x0 + x1) * 0.5F, my = (y0 + y1) * 0.5F, mz = (z0 + z1) * 0.5F;
      float r;
      if (ez >= ex && ez >= ey) {
         r = 0.5F * Math.max(ex, ey);
         caps[0] = mx; caps[1] = my; caps[2] = z0 + r; caps[3] = mx; caps[4] = my; caps[5] = Math.max(z0 + r, z1 - r);
      } else if (ex >= ey) {
         r = 0.5F * Math.max(ey, ez);
         caps[0] = x0 + r; caps[1] = my; caps[2] = mz; caps[3] = Math.max(x0 + r, x1 - r); caps[4] = my; caps[5] = mz;
      } else {
         r = 0.5F * Math.max(ex, ez);
         caps[0] = mx; caps[1] = y0 + r; caps[2] = mz; caps[3] = mx; caps[4] = Math.max(y0 + r, y1 - r); caps[5] = mz;
      }
      r = r * 1.25F + 0.08F; // bones sit inside the flesh: the skin and fur reach past them
      caps[0] += cx; caps[1] += cy; caps[2] += cz;
      caps[3] += cx; caps[4] += cy; caps[5] += cz;
      caps[6] = r;
      return true;
   }

   /**
    * Game thread, for a zombie drawn as an atlas sprite (no model, no bones: stock draws it no shadow): one upright capsule
    * of a standing body, so a horde at max zoom keeps its shadows where the near ones have their bone capsules.
    */
   public static void addAtlas(IsoGameCharacter chr) {
      Frame f = current;
      if (f == null || f.n >= MAX || chr == null || !Config.SUN_SHADOW_CHARACTERS || !Config.SUN_SHADOW_ATLAS) {
         return;
      }
      pickOwnFire = chr.onFireLightSource; // staticPick: a burning character never takes its own fire's shadow
      IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null) {
         return;
      }
      float sunVis = f.sunOn && sq.isOutside() ? sunShare(chr) : 0F;
      if (sunVis <= 0.05F && f.nl == 0) {
         return;
      }
      float alpha = chr.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return;
      }
      float cx = chr.getX() - f.ox, cy = chr.getY() - f.oy, cz = chr.getZ() * METRIC_Z;
      float[] caps = CAPS;
      caps[0] = cx;
      caps[1] = cy;
      caps[2] = cz + 0.3F;
      caps[3] = cx;
      caps[4] = cy;
      caps[5] = cz + 2.35F;
      caps[6] = 0.24F;
      append(f, caps, 1, cz, alpha, -1F, sunVis > 0.05F ? sunVis : 0F);
      atlas++;
   }

   private static long atlas;

   /** Game thread, where a vehicle's stock shadow is drawn: its body (two capsules side by side and the cabin) joins the pass. */
   public static void addVehicle(zombie.vehicles.BaseVehicle v) {
      stockScale = 1F;
      Frame f = current;
      if (f == null || f.n >= MAX || v == null || !Config.SUN_SHADOW_VEHICLES || v.getScript() == null) {
         return;
      }
      pickOwnFire = null;
      IsoGridSquare sq = v.getCurrentSquare();
      if (sq == null) {
         return;
      }
      float sunVis = f.sunOn && sq.isOutside() ? sunShare(v) : 0F;
      boolean sun = sunVis > 0.05F;
      if (!sun && f.nl == 0) {
         return;
      }
      float alpha = v.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return;
      }
      org.joml.Vector3f e = v.getScript().getExtents(); // width, height, length (squares)
      org.joml.Vector2f se = v.getScript().getShadowExtents(); // footprint width, length
      org.joml.Vector2f so = v.getScript().getShadowOffset();
      float w = se.x, len = se.y, h = e.y;
      if (w <= 0F || len <= 0F || h <= 0F) {
         return;
      }
      float cz = v.getZ() * METRIC_Z;
      float rb = Math.min(w * 0.25F, h * 0.3F); // the two body capsules
      float rc = Math.min(w * 0.4F, h * 0.28F); // the cabin
      float[] caps = CAPS;
      float[][] local = {
         {-w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, -w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
         {w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
         {0F, h - rc, so.y - len * 0.22F, 0F, h - rc, so.y + len * 0.12F, rc}};
      org.joml.Vector3f p = VEC;
      for (int s = 0; s < 3; s++) {
         float[] c = local[s];
         for (int k = 0; k < 2; k++) {
            v.getWorldPos(so.x + c[k * 3], 0F, c[k * 3 + 2], p);
            caps[s * 7 + k * 3] = p.x - f.ox;
            caps[s * 7 + k * 3 + 1] = p.y - f.oy;
            caps[s * 7 + k * 3 + 2] = cz + c[k * 3 + 1];
         }
         caps[s * 7 + 6] = c[6];
      }
      append(f, caps, 3, cz, alpha, 1F, sun ? sunVis : 0F);
      if (Config.SUN_SHADOW_MESH_VEHICLES) {
         assignTile(f, v, sun);
      }
      vehicles++;
      if (sun) {
         stockScale = 1F - Math.max(0, Math.min(100, Config.SUN_SHADOW_STOCK_FADE_PCT)) / 100F * Math.min(1F, sunVis);
      }
   }

   private static final float[] CAPS = new float[K * 7];
   private static final org.joml.Vector3f VEC = new org.joml.Vector3f();
   private static long vehicles;

   /**
    * One caster into the frame: its capsules (a xyz, b xyz, radius; metric, relative to the origin square), its bounding
    * capsule and its facts (floor z, kind, sun, alpha). Unused capsule slots get radius 0.
    */
   private static void append(Frame f, float[] caps, int count, float cz, float alpha, float kind, float sun) {
      float[] d = f.data;
      int base = f.n * TEXELS * 4;
      // texel 0: x the caster's sun shadow atlas tile (-1 none; assignTile), y the tile's half size (squares), z the depth
      // shell a drawn surface occupies behind what the depth shows (sunShadowSilhouette without a tile; x + y + 2z units:
      // 1.633 a square of view depth; 0.12 squares, a car 0.3), w 1 = its pixels are in the depth (models; an atlas zombie
      // is a flat sprite)
      d[base] = -1F;
      d[base + 1] = 0F;
      d[base + 2] = kind > 0.5F && kind < 1.5F ? 0.5F : 0.2F;
      d[base + 3] = kind < -0.5F ? 0F : 1F;
      // the bounding capsule: along the longest axis of the end points' box, fat enough to hold every capsule (a pixel it
      // leaves in full light is in full light of all of them: the per-pixel test runs one capsule instead of ten there)
      float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
      for (int s = 0; s < count; s++) {
         for (int e = 0; e < 2; e++) {
            x0 = Math.min(x0, caps[s * 7 + e * 3]);
            x1 = Math.max(x1, caps[s * 7 + e * 3]);
            y0 = Math.min(y0, caps[s * 7 + e * 3 + 1]);
            y1 = Math.max(y1, caps[s * 7 + e * 3 + 1]);
            z0 = Math.min(z0, caps[s * 7 + e * 3 + 2]);
            z1 = Math.max(z1, caps[s * 7 + e * 3 + 2]);
         }
      }
      float mx = (x0 + x1) * 0.5F, my = (y0 + y1) * 0.5F, mz = (z0 + z1) * 0.5F;
      float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
      float ax, ay, az, bx, by, bz; // the segment along the longest extent through the middle
      if (ez >= ex && ez >= ey) {
         ax = bx = mx; ay = by = my; az = z0; bz = z1;
      } else if (ex >= ey) {
         ay = by = my; az = bz = mz; ax = x0; bx = x1;
      } else {
         ax = bx = mx; az = bz = mz; ay = y0; by = y1;
      }
      float rb = 0F;
      for (int s = 0; s < count; s++) {
         for (int e = 0; e < 2; e++) {
            float px = caps[s * 7 + e * 3], py = caps[s * 7 + e * 3 + 1], pz = caps[s * 7 + e * 3 + 2];
            float qx = Math.max(Math.min(px, Math.max(ax, bx)), Math.min(ax, bx));
            float qy = Math.max(Math.min(py, Math.max(ay, by)), Math.min(ay, by));
            float qz = Math.max(Math.min(pz, Math.max(az, bz)), Math.min(az, bz));
            float dx = px - qx, dy = py - qy, dz = pz - qz;
            rb = Math.max(rb, (float)Math.sqrt(dx * dx + dy * dy + dz * dz) + caps[s * 7 + 6]);
         }
      }
      d[base + 4] = ax;
      d[base + 5] = ay;
      d[base + 6] = az;
      d[base + 7] = rb + 0.02F;
      d[base + 8] = bx;
      d[base + 9] = by;
      d[base + 10] = bz;
      d[base + 11] = 0F;
      d[base + 12] = cz;
      d[base + 13] = kind;
      d[base + 14] = sun; // the caster's sun share (0: no sun quad)
      d[base + 15] = alpha;
      for (int s = 0; s < K; s++) {
         int o = base + (4 + 2 * s) * 4;
         boolean has = s < count;
         d[o] = has ? caps[s * 7] : 0F;
         d[o + 1] = has ? caps[s * 7 + 1] : 0F;
         d[o + 2] = has ? caps[s * 7 + 2] : 0F;
         d[o + 3] = has ? caps[s * 7 + 6] : 0F;
         d[o + 4] = has ? caps[s * 7 + 3] : 0F;
         d[o + 5] = has ? caps[s * 7 + 4] : 0F;
         d[o + 6] = has ? caps[s * 7 + 5] : 0F;
         d[o + 7] = 0F;
      }
      walls(f, base + WALL0 * 4, mx, my, cz, sun > 0F ? 1F + 0.5F * Math.max(ex, ey) : 0F);
      staticPick(f, f.n, (ax + bx) * 0.5F, (ay + by) * 0.5F, rb + 0.02F, cz);
      f.n++;
   }

   private static final float[] NO_WALLS = new float[0];
   /** A caster square's runs (collectWalls) and when / under which sun step they were collected. */
   private static final class WallRuns {
      float[] runs;
      long serial;
      long ms;
   }
   private static final java.util.HashMap<Long, WallRuns> WALL_CACHE = new java.util.HashMap<>();
   private static long wallFrame = -1L;
   private static int wallRefreshes;
   private static final int WALL_REFRESH_BUDGET = 8; // stale entries renewed a frame (a sun step makes them all stale at once)
   private static final float[] WALL_EDGES = new float[4 * 128];
   private static long wallCollects;
   private static String wallLast = "-"; // the last caster square with walls and its runs (stats)

   /**
    * sunShadowWallCut, game thread: the walls and fences standing in a caster's sun shadow (the squares within band of the
    * shadow's line from its square) as up to WALLS runs into its texels (relative to the frame's origin; wallCut in the
    * shaders). The shadow pass draws on whatever the scene depth shows, so without them a character beside a fence laid its
    * shadow on the far side of the fence, which the fence's own shadow already covers (2026-10-02). band 0: none.
    * Cached per square: an entry older than a second or of another sun step is renewed, at most WALL_REFRESH_BUDGET a frame
    * (the others keep theirs a frame longer); a square without one is collected at once.
    */
   private static void walls(Frame f, int at, float x, float y, float cz, float band) {
      float[] d = f.data;
      java.util.Arrays.fill(d, at, at + WALLS * 4, 0F);
      if (band <= 0F || !Config.SUN_SHADOW_WALL_CUT) {
         return;
      }
      int sx = (int)Math.floor(x + f.ox), sy = (int)Math.floor(y + f.oy), level = (int)Math.floor(cz / METRIC_Z + 1e-3F);
      int b = Math.min(3, (int)Math.ceil(band));
      long serial = SunShadow.stepSerial();
      long now = System.currentTimeMillis();
      if (wallFrame != frames) {
         wallFrame = frames;
         wallRefreshes = 0;
         if (WALL_CACHE.size() > 4096) {
            WALL_CACHE.clear();
         }
      }
      long key = ((long)(sx & 0xFFFFF) << 40 | (long)(sy & 0xFFFFF) << 20 | (long)(level + 64 & 0xFF) << 2 | b) & 0x7FFFFFFFFFFFFFFFL;
      WallRuns e = WALL_CACHE.get(key);
      if (e == null) {
         e = new WallRuns();
         WALL_CACHE.put(key, e);
      }
      if (e.runs == null || (e.serial != serial || now - e.ms > 1000L) && wallRefreshes++ < WALL_REFRESH_BUDGET) {
         e.runs = collectWalls(sx, sy, level, b); // (a fence built or knocked down shows within a second)
         e.serial = serial;
         e.ms = now;
      }
      float[] runs = e.runs;
      for (int i = 0; i < runs.length / 4; i++) {
         int o = at + i * 4;
         boolean alongX = runs[i * 4 + 1] < runs[i * 4 + 2];
         d[o] = runs[i * 4] - (alongX ? f.oy : f.ox);
         d[o + 1] = runs[i * 4 + 1] - (alongX ? f.ox : f.oy);
         d[o + 2] = runs[i * 4 + 2] - (alongX ? f.ox : f.oy);
         d[o + 3] = runs[i * 4 + 3];
      }
   }

   /**
    * The opaque wall / fence edges on the caster's level over the squares its sun shadow can reach (within band + 0.75 of the
    * shadow's line), merged into runs, the nearest WALLS: (line, from, to, top metric) in world squares, a run along y
    * stored from > to.
    */
   private static float[] collectWalls(int sx, int sy, int level, int band) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      float lx = SunShadow.world[0], ly = SunShadow.world[1], lz = SunShadow.world[2];
      float lxy = (float)Math.sqrt(lx * lx + ly * ly);
      if (cell == null || lxy < 1e-3F) {
         return NO_WALLS;
      }
      wallCollects++;
      float dx = -lx / lxy, dy = -ly / lxy; // along the shadow
      // (at most 24 squares: the scan walks the band's squares; the far ends of a dusk shadow are wide and faint)
      float len = Math.min(Math.min(24, Math.max(1, Config.SUN_SHADOW_CHARACTER_REACH)), lz > 1e-3F ? 2.6F * lxy / lz : 1e3F) + 1F;
      float px = sx + 0.5F, py = sy + 0.5F, ex = px + dx * len, ey = py + dy * len;
      int x0 = (int)Math.floor(Math.min(px, ex)) - band, x1 = (int)Math.floor(Math.max(px, ex)) + band;
      int y0 = (int)Math.floor(Math.min(py, ey)) - band, y1 = (int)Math.floor(Math.max(py, ey)) + band;
      float reach = band + 0.75F;
      int edges = 0;
      for (int qy = y0; qy <= y1; qy++) {
         for (int qx = x0; qx <= x1; qx++) {
            float rx = qx + 0.5F - px, ry = qy + 0.5F - py;
            float t = Math.max(0F, Math.min(len, rx * dx + ry * dy));
            float ox = rx - dx * t, oy = ry - dy * t;
            if (ox * ox + oy * oy > reach * reach || edges >= WALL_EDGES.length / 4 - 1) {
               continue;
            }
            IsoGridSquare sq = cell.getGridSquare(qx, qy, level);
            if (sq == null) {
               continue;
            }
            for (int e = 0; e < 2; e++) {
               float h = edgeHeight(sq, e == 0);
               if (h > 0F) {
                  int o = edges++ * 4;
                  WALL_EDGES[o] = e; // 0: the N edge (along x), 1: the W edge
                  WALL_EDGES[o + 1] = e == 0 ? qy : qx; // its line
                  WALL_EDGES[o + 2] = e == 0 ? qx : qy; // from here one square on
                  WALL_EDGES[o + 3] = (level + h) * METRIC_Z;
               }
            }
         }
      }
      if (edges == 0) {
         return NO_WALLS;
      }
      // merge neighbours on one line (the same top), then keep the runs nearest the caster
      float[] runs = new float[edges * 5];
      int nr = 0;
      boolean[] used = new boolean[edges];
      for (int i = 0; i < edges; i++) {
         if (used[i]) {
            continue;
         }
         used[i] = true;
         float axis = WALL_EDGES[i * 4], line = WALL_EDGES[i * 4 + 1], top = WALL_EDGES[i * 4 + 3];
         float lo = WALL_EDGES[i * 4 + 2], hi = lo + 1F;
         for (boolean grew = true; grew; ) {
            grew = false;
            for (int j = 0; j < edges; j++) {
               if (!used[j] && WALL_EDGES[j * 4] == axis && WALL_EDGES[j * 4 + 1] == line && WALL_EDGES[j * 4 + 3] == top
                     && (WALL_EDGES[j * 4 + 2] == hi || WALL_EDGES[j * 4 + 2] + 1F == lo)) {
                  used[j] = true;
                  lo = Math.min(lo, WALL_EDGES[j * 4 + 2]);
                  hi = Math.max(hi, WALL_EDGES[j * 4 + 2] + 1F);
                  grew = true;
               }
            }
         }
         // the distance from the caster's square centre to the run
         float ax = axis == 0F ? Math.max(lo, Math.min(hi, px)) : line, ay = axis == 0F ? line : Math.max(lo, Math.min(hi, py));
         int o = nr++ * 5;
         runs[o] = (ax - px) * (ax - px) + (ay - py) * (ay - py);
         runs[o + 1] = line;
         runs[o + 2] = axis == 0F ? lo : hi; // along y: stored from > to
         runs[o + 3] = axis == 0F ? hi : lo;
         runs[o + 4] = top;
      }
      int keep = Math.min(WALLS, nr);
      StringBuilder sb = new StringBuilder().append(sx).append(',').append(sy).append(':');
      float[] out = new float[keep * 4];
      for (int k = 0; k < keep; k++) {
         int best = -1;
         for (int i = 0; i < nr; i++) {
            if (runs[i * 5] >= 0F && (best < 0 || runs[i * 5] < runs[best * 5])) {
               best = i;
            }
         }
         System.arraycopy(runs, best * 5 + 1, out, k * 4, 4);
         runs[best * 5] = -1F;
         sb.append(String.format(java.util.Locale.ROOT, " %s=%.0f %.0f..%.0f top %.2f", out[k * 4 + 1] < out[k * 4 + 2] ? "y" : "x", out[k * 4],
            Math.min(out[k * 4 + 1], out[k * 4 + 2]), Math.max(out[k * 4 + 1], out[k * 4 + 2]), out[k * 4 + 3]));
      }
      wallLast = sb.toString();
      return out;
   }

   /**
    * How tall (levels, 0 = none) the opaque wall or fence on a square's north / west edge stands: walls and fences (a low
    * fence's transparentN / W means the eye passes over it, not the sun); not windows or doors; nor see-through fences
    * (chain-link, railings), which edgeShape finds mostly empty.
    */
   private static float edgeHeight(IsoGridSquare sq, boolean north) {
      IsoFlagType collide = north ? IsoFlagType.collideN : IsoFlagType.collideW, wall = north ? IsoFlagType.WallN : IsoFlagType.WallW;
      IsoFlagType cut = north ? IsoFlagType.cutN : IsoFlagType.cutW;
      if (!sq.has(collide) && !sq.has(wall) && !sq.has(cut) && !sq.has(IsoFlagType.WallNW)) {
         return 0F;
      }
      IsoFlagType window = north ? IsoFlagType.WindowN : IsoFlagType.WindowW;
      IsoFlagType door = north ? IsoFlagType.doorN : IsoFlagType.doorW, doorWall = north ? IsoFlagType.DoorWallN : IsoFlagType.DoorWallW;
      float h = 0F;
      zombie.util.list.PZArrayList<zombie.iso.IsoObject> objs = sq.getObjects();
      for (int i = 0; i < objs.size(); i++) {
         zombie.iso.IsoObject obj = objs.get(i);
         zombie.iso.sprite.IsoSprite sp = obj == null ? null : obj.getSprite();
         zombie.core.properties.PropertyContainer p = sp == null ? null : sp.getProperties();
         if (p == null || !(p.has(collide) || p.has(wall) || p.has(cut) || p.has(IsoFlagType.WallNW))) {
            continue;
         }
         if (p.has(window) || p.has(door) || p.has(doorWall)) {
            continue;
         }
         float[] shape = EDGE_SHAPE.get(sp);
         if (shape == null) {
            shape = new float[] {edgeShape(sp, true), edgeShape(sp, false)};
            EDGE_SHAPE.put(sp, shape);
         }
         h = Math.max(h, shape[north ? 0 : 1]);
      }
      return h;
   }

   /** Any thread (SunShadow's march runs on the frame workers too): edgeHeight, the wall / fence top on a square's edge (levels). */
   static float edgeTop(IsoGridSquare sq, boolean north) {
      return edgeHeight(sq, north);
   }

   /** Per sprite: edgeShape of its north and its west face (IsoSprite keeps Object's identity hash; concurrent: the march's workers read it). */
   private static final java.util.concurrent.ConcurrentHashMap<zombie.iso.sprite.IsoSprite, float[]> EDGE_SHAPE = new java.util.concurrent.ConcurrentHashMap<>();
   private static final float[] COLUMN_H = new float[8];

   /**
    * A wall / fence sprite's height over one tile edge (levels; 0 = mostly see-through or nothing there), from its texture's
    * mask: 8 columns along the face from the tile's north corner, in each the topmost drawn pixel over the edge's base line
    * (a level is the tile's top three quarters, the floor diamond the bottom quarter); the median of the columns (a post
    * standing above the boards does not count), if at least half the face below it is drawn (chain-link and railings are
    * not). Without a mask: one level for a wall, half for a low fence.
    */
   private static float edgeShape(zombie.iso.sprite.IsoSprite sp, boolean north) {
      zombie.core.properties.PropertyContainer p = sp.getProperties();
      zombie.core.textures.Texture tex = sp.texture;
      boolean low = p.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW) && !p.has(north ? IsoFlagType.TallHoppableN : IsoFlagType.TallHoppableW);
      if (tex == null || tex.getMask() == null || tex.getWidthOrig() <= 0 || tex.getHeightOrig() <= 0) {
         return p.has(north ? IsoFlagType.transparentN : IsoFlagType.transparentW) && !low ? 0F : low ? 0.5F : 1F;
      }
      int w = tex.getWidthOrig(), hgt = tex.getHeightOrig();
      int ox = (int)tex.getOffsetX(), oy = (int)tex.getOffsetY(), tw = tex.getWidth(), th = tex.getHeight();
      float level = 0.75F * hgt;
      int found = 0;
      long drawn = 0L, span = 0L;
      for (int k = 0; k < 8; k++) {
         float frac = (k + 0.5F) / 8F;
         int x = (int)(w * 0.5F + (north ? frac : -frac) * w * 0.5F);
         int base = (int)(level + frac * 0.125F * hgt);
         if (x < ox || x >= ox + tw) {
            continue;
         }
         int top = -1, set = 0;
         for (int y = Math.max(0, oy); y < Math.min(base, oy + th); y++) {
            if (tex.isMaskSet(x, y)) {
               if (top < 0) {
                  top = y;
               }
               set++;
            }
         }
         if (top >= 0) {
            COLUMN_H[found++] = (base - top) / level;
            drawn += set;
            span += base - top;
         }
      }
      if (found < 4 || span <= 0L || drawn < span / 2) {
         return 0F;
      }
      java.util.Arrays.sort(COLUMN_H, 0, found);
      return Math.max(0.05F, Math.min(1F, COLUMN_H[found / 2]));
   }

   private static final class Frame extends TextureDraw.GenericDrawer {
      int n;
      boolean silhouette; // sunShadowSilhouette: drawn after the moving objects, the march through their depth
      boolean meshes; // sunShadowMeshes: the casters' models drawn from the sun into the atlas
      final float[] data = new float[MAX * TEXELS * 4];
      final float[] sun = new float[4];
      boolean sunOn;
      float strength;
      int nl; // lights
      final float[] lA = new float[MAX_LIGHTS * 4]; // x, y (relative to the origin square), z (metric, the lamp's height), reach
      final float[] lB = new float[MAX_LIGHTS * 4]; // direction x, y, cone cosine (-2: none), strength
      final float[] lK = new float[MAX_LIGHTS]; // 1 a handheld torch, 2 a vehicle light, 3 a static point light (StaticLightShadow)
      int nt; // the torches / headlights among the lights (slots 0..nt-1); the static lights follow
      final zombie.iso.IsoLightSource[] lSrc = new zombie.iso.IsoLightSource[MAX_LIGHTS]; // a static light's source (game thread)
      final zombie.iso.IsoRoomLight[] lRoom = new zombie.iso.IsoRoomLight[MAX_LIGHTS]; // or its room light (sunShadowRoomLights; lSrc null)
      final byte[] lsel = new byte[MAX * 2]; // per caster: its static lights (frame slots, strongest first, -1 none)
      final float[] llim = new float[MAX * 2]; // and the wall plane each one's shadow stops at (StaticLightShadow.planeW: the first wall past the caster)
      float lightStrength;
      float zoom;
      float ts;
      float offX;
      float offY;
      float screenW;
      float screenH;
      int ox;
      int oy;
      float d0;
      int playerIndex;
      int devPlayer = -1; // dev (devCasterTrace): the local player's caster index this frame, its facts for the log
      String devInfo;

      @Override
      public void render() {
         if (this.n == 0 || failed) {
            return;
         }
         try {
            GL.draw(this);
         } catch (Throwable t) {
            failed = true;
            Log.warn("capsule shadows: " + t + "; off for the rest of the session");
         }
      }
   }

   private static final Gl GL = new Gl();

   private static final class Gl {
      // dev (devCasterTrace): the local player's sun shadow alone, its shadowed pixels counted (GL_SAMPLES_PASSED: the pass
      // discards every unshadowed fragment), read back a few frames later; the log gives the shadow's area per frame
      private final int[] devQ = new int[8];
      private final String[] devQInfo = new String[8];
      private int devQNext;
      private long devQFrame;

      private void devPlayerQuery(Frame f) {
         if (Config.DEV_CASTER_TRACE <= 0) {
            return;
         }
         devQFrame++;
         for (int k = 0; k < devQ.length; k++) { // the finished ones out
            if (devQ[k] != 0 && devQInfo[k] != null && GL15.glGetQueryObjecti(devQ[k], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               Log.info("capsule shadows: dev caster px " + GL15.glGetQueryObjecti(devQ[k], GL15.GL_QUERY_RESULT) + " " + devQInfo[k]);
               devQInfo[k] = null;
            }
         }
         int pi = f.devPlayer;
         if (pi < 0 || pi >= f.n) {
            Log.info("capsule shadows: dev caster px none (not a caster) frame " + devQFrame);
            return;
         }
         int k = devQNext;
         if (devQInfo[k] != null) {
            return; // the ring is full: skip this frame
         }
         devQNext = (k + 1) % devQ.length;
         if (devQ[k] == 0) {
            devQ[k] = GL15.glGenQueries();
         }
         // the player's row as instance 0, one instance, no colour; then the frame's rows back for the real draw
         this.upload.clear();
         this.upload.put(f.data, pi * TEXELS * 4, TEXELS * 4).flip();
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.dataTex);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TEXELS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, this.upload);
         GL11.glColorMask(false, false, false, false);
         GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, devQ[k]);
         GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, 1);
         GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
         GL11.glColorMask(true, true, true, false);
         int base = pi * TEXELS * 4;
         devQInfo[k] = String.format(java.util.Locale.ROOT, "frame %d epoch_ms %d %s tile %.0f alpha %.2f sun %.2f", devQFrame, System.currentTimeMillis(), f.devInfo, f.data[base], f.data[base + 15], f.data[base + 14]);
         this.upload.clear();
         this.upload.put(f.data, 0, TEXELS * 4).flip();
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TEXELS, 1, GL11.GL_RGBA, GL11.GL_FLOAT, this.upload);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }

      private int program;
      private int silProgram;
      private final int[] us = new int[12];
      private final FloatBuffer rsun = BufferUtils.createFloatBuffer(9);
      private int lightProgram;
      private int dataTex;
      private int vao;
      private final int[] u = new int[8];
      private final int[] ul = new int[14];
      private int pairTex;
      private final float[] pairData = new float[MAX_PAIRS * 4];
      private FloatBuffer pairUpload;

      /**
       * The caster x light pairs worth a quad: the caster within the lamp's reach (plus its size) and inside its cone
       * (with the penumbra's margin), not the torch's holder, not a vehicle under its own headlights.
       */
      private int pairs(Frame f) {
         int n = 0;
         float[] d = f.data;
         for (int c = 0; c < f.n && n < MAX_PAIRS; c++) {
            int base = c * TEXELS * 4;
            float mx = (d[base + 4] + d[base + 8]) * 0.5F, my = (d[base + 5] + d[base + 9]) * 0.5F, r = d[base + 7];
            boolean vehicle = d[base + 13] > 0.5F && d[base + 13] < 1.5F;
            for (int oq = 0; oq < f.nt + 2 && n < MAX_PAIRS; oq++) { // the torches / headlights, then the caster's static lights (staticPick)
               int l = oq < f.nt ? oq : f.lsel[c * 2 + oq - f.nt];
               if (l < 0 || l >= f.nl) {
                  continue;
               }
               float lx = f.lA[l * 4], ly = f.lA[l * 4 + 1], reach = f.lA[l * 4 + 3];
               float vx = mx - lx, vy = my - ly;
               float dl = (float)Math.sqrt(vx * vx + vy * vy);
               boolean car = f.lK[l] > 1.5F && f.lK[l] < 2.5F;
               if (dl > reach + r || f.lK[l] < 1.5F && dl < 0.7F || car && vehicle && dl < 3.5F) {
                  continue;
               }
               float cone = f.lB[l * 4 + 2];
               if (cone > -1.5F && dl > r + 0.5F) {
                  // the cone test on the caster's nearest edge: the angle its radius adds, and the model's own ramp start
                  float cosToCaster = (vx * f.lB[l * 4] + vy * f.lB[l * 4 + 1]) / dl;
                  float spread = (float)Math.min(1.0, (r + 0.5F) / dl);
                  float c0 = car ? cone - 0.28F : cone + 0.025F;
                  // cos(a - s) >= c0 with a the angle to the caster, s the angle its size spans
                  double a = Math.acos(Math.max(-1F, Math.min(1F, cosToCaster))), sa = Math.asin(spread);
                  if (Math.cos(Math.max(0.0, a - sa)) < c0) {
                     continue;
                  }
               }
               // sunShadowLampMeshes: the caster's lamp view drawn from this lamp (its lamp within ~0.7 of a square of it), else -1
               int view = -1;
               for (int j = 0; j < 2; j++) {
                  int q = base + (LAMP0 + 3 * j) * 4;
                  if (d[q] >= 0F) {
                     float ex = d[q + 4] + d[q + 8] - lx, ey = d[q + 5] + d[q + 9] - ly, ez = d[q + 6] + d[q + 10] - f.lA[l * 4 + 2];
                     if (ex * ex + ey * ey + ez * ez < 0.5F) {
                        view = j;
                        break;
                     }
                  }
               }
               this.pairData[n * 4] = c;
               this.pairData[n * 4 + 1] = l;
               this.pairData[n * 4 + 2] = view;
               this.pairData[n * 4 + 3] = oq < f.nt ? StaticLightShadow.OPEN_W : f.llim[c * 2 + oq - f.nt]; // the shadow's end (a static light's: the first wall past the caster)
               n++;
            }
         }
         return n;
      }
      private int lastFbo = -1;
      private int vpAge;
      private int devFboLogs;
      private long devFboChecks, devFboWrong;
      private int lastDepth;
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4];
      private FloatBuffer upload;
      private final FloatBuffer lightA = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
      private final FloatBuffer lightB = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
      private final FloatBuffer lightK = BufferUtils.createFloatBuffer(MAX_LIGHTS);
      // dev (devAoTiming): GPU time of the pass
      private int[] queries;
      private int slot;
      private long ns;
      private long timed;

      private static boolean glSupported() {
         org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
         return (c.OpenGL31 || c.GL_ARB_draw_instanced) && (c.OpenGL30 || c.GL_ARB_vertex_array_object);
      }

      void draw(Frame f) {
         if (this.program == 0 && !glSupported()) {
            failed = true; // LWJGL aborts the JVM on a GL function the context lacks, so check before init() (macOS: GL 2.1)
            Log.warn("capsule shadows: needs OpenGL 3.1 or its extensions (vertex arrays, instanced draws), this context is "
               + GL11.glGetString(GL11.GL_VERSION) + "; off");
            return;
         }
         if (this.program == 0 && !this.init()) {
            failed = true;
            Log.warn("capsule shadows: shaders did not compile; off");
            return;
         }
         // the scene framebuffer from ShadowAtlas' cache (a glGet waits for NVIDIA's threaded driver to drain); its depth
         // attachment and the viewport read back only when it changes or every 120 frames
         int sceneFbo = Config.DEV_SHADOW_GL_GET ? GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) : ShadowAtlas.worldFbo();
         if (Config.DEV_SIL_COST >= 3 && drawn % 600 == 1) {
            Log.info(String.format(java.util.Locale.ROOT, "capsule shadows: dev mapping zoom %.3f ts %.0f screen %.0fx%.0f off %.1f,%.1f origin %d,%d d0 %.6f viewport %.1f,%.1f,%.1f,%.1f (int %d,%d,%d,%d) fbo %d depth %d | first caster a %.2f,%.2f,%.2f r %.2f floor %.2f",
               f.zoom, f.ts, f.screenW, f.screenH, f.offX, f.offY, f.ox, f.oy, f.d0, this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3],
               this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3], this.lastFbo, this.lastDepth, f.data[4], f.data[5], f.data[6], f.data[7], f.data[12]));
         }
         if (Config.DEV_SIL_COST >= 5 && drawn % 600 == 2 && f.n > 0) {
            // dev: the depth under the first caster's feet against the depth the mapping expects there (ground, its floor)
            double ax = f.data[4], ay = f.data[5];
            double A = ax - ay, B = ax + ay; // x - y and x + y - 6z (z = 0 at its floor level 0)
            double kA0 = f.screenW / this.viewportF[2] * f.zoom / (32.0 * f.ts), cA0 = ((-this.viewportF[0] * f.screenW / this.viewportF[2]) * f.zoom + f.offX) / (32.0 * f.ts) - (f.ox - f.oy);
            double kB0 = -f.screenH / this.viewportF[3] * f.zoom / (16.0 * f.ts), cB0 = (((this.viewportF[1] + this.viewportF[3]) * f.screenH / this.viewportF[3]) * f.zoom + f.offY) / (16.0 * f.ts) - (f.ox + f.oy);
            int px = (int)((A - cA0) / kA0), py = (int)((B - cB0) / kB0);
            java.nio.FloatBuffer b = org.lwjgl.BufferUtils.createFloatBuffer(9);
            GL11.glReadPixels(Math.max(0, px - 1), Math.max(0, py - 1), 3, 3, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, b);
            double expect = (B + 0.0 - (f.d0 / PixelLight.DEPTH_PER_XY)) * -PixelLight.DEPTH_PER_XY; // C = x + y + 2z = B at z 0: depth = (cC - C) * DEPTH_PER_XY
            Log.info(String.format(java.util.Locale.ROOT, "capsule shadows: dev depth at the first caster's feet (window %d,%d): %.6f %.6f %.6f, the mapping expects %.6f (d0 %.6f)",
               px, py, b.get(0), b.get(4), b.get(8), f.d0 - B * PixelLight.DEPTH_PER_XY, f.d0));
         }
         if (Config.DEV_SHADOW_GL_GET && ++devFboChecks % 2000 == 0) {
            Log.info("capsule shadows: dev framebuffer checks " + devFboChecks + ", the cache wrong " + devFboWrong);
         }
         if (Config.DEV_SHADOW_GL_GET && sceneFbo != ShadowAtlas.worldFbo()) {
            devFboWrong++;
         }
         if (Config.DEV_SHADOW_GL_GET && sceneFbo != ShadowAtlas.worldFbo() && devFboLogs++ < 5) {
            Log.info("capsule shadows: dev bound framebuffer " + sceneFbo + " but the cache says " + ShadowAtlas.worldFbo() + " (TextureFBO.lastID " + zombie.core.textures.TextureFBO.lastID + ")");
         }
         if (sceneFbo != this.lastFbo || ++this.vpAge > 120 || Config.DEV_SHADOW_GL_GET) { // the attachment query is a round trip: once per scene framebuffer
            this.lastFbo = sceneFbo;
            this.lastDepth = sceneDepthTexture(sceneFbo);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
            GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
            this.vpAge = 0;
         }
         int depthTex = this.lastDepth;
         if (depthTex == 0 || this.viewport[2] <= 0 || this.viewport[3] <= 0) {
            return;
         }
         if (Config.DEV_AO_TIMING) {
            if (this.queries == null) {
               this.queries = new int[16];
               GL15.glGenQueries(this.queries);
            }
            GL33.glQueryCounter(this.queries[this.slot * 2], GL33.GL_TIMESTAMP);
         }
         // window px -> (x - y, x + y - 6z) and depth -> x + y + 2z, relative to the origin square (PixelLight.mapping)
         double vx = this.viewportF[0], vy = this.viewportF[1], vw = this.viewportF[2], vh = this.viewportF[3];
         double sxPerPx = f.screenW / vw, syPerPx = f.screenH / vh;
         double a32 = 32.0 * f.ts, a16 = 16.0 * f.ts;
         float kA = (float)(sxPerPx * f.zoom / a32);
         float cA = (float)(((-vx * sxPerPx) * f.zoom + f.offX) / a32 - (f.ox - f.oy));
         float kB = (float)(-syPerPx * f.zoom / a16);
         float cB = (float)((((vy + vh) * syPerPx) * f.zoom + f.offY) / a16 - (f.ox + f.oy));
         float kC = (float)(-1.0 / PixelLight.DEPTH_PER_XY);
         float cC = (float)(f.d0 / PixelLight.DEPTH_PER_XY);

         int count = f.n * TEXELS * 4;
         this.upload.clear();
         this.upload.put(f.data, 0, count).flip();
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.dataTex);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TEXELS, f.n, GL11.GL_RGBA, GL11.GL_FLOAT, this.upload);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);

         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
         GL11.glColorMask(true, true, true, false);
         GL30.glBindVertexArray(this.vao);
         if (f.sunOn && f.silhouette) {
            // sunShadowSilhouette: the same quads, each pixel's ray marched through the scene depth inside its caster's
            // bounding capsule (the caster's own drawn surface), the capsule model where the penumbra is wider than a limb
            GL20.glUseProgram(this.silProgram);
            GL20.glUniform1i(this.us[0], 0);
            GL20.glUniform1i(this.us[1], 1);
            GL20.glUniform4f(this.us[2], kA, cA, kB, cB);
            GL20.glUniform4f(this.us[3], kC, cC, Config.DEV_SUN_VIEW, f.strength);
            float tanA = Math.max(0.002F, f.sun[3]);
            GL20.glUniform4f(this.us[4], f.sun[0], f.sun[1], f.sun[2], 1.0F / tanA);
            GL20.glUniform4f(this.us[5], this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
            GL20.glUniform4f(this.us[6], Math.max(0, Math.min(48, Config.SUN_SHADOW_SILHOUETTE_STEPS)), tanA, (float)(drawn & 63),
               Config.SUN_SHADOW_SHELL_LIMBS ? 1.0F : 0.0F);
            GL20.glUniform4f(this.us[7], Math.max(1, Config.SUN_SHADOW_CHARACTER_REACH), Config.SUN_SHADOW_CHARACTER_LOD_PCT / 100.0F, Config.DEV_SIL_COST, devTipOld());
            boolean atlasOn = f.meshes && ShadowAtlas.bind(3, 4);
            GL20.glUniform1i(this.us[10], 3);
            GL20.glUniform1i(this.us[11], 4);
            GL20.glUniform4f(this.us[9], atlasOn ? 1.0F : 0.0F, ShadowAtlas.PER_ROW, ShadowAtlas.TILE, 1.0F / ShadowAtlas.SIZE);
            this.rsun.clear();
            this.rsun.put(ShadowAtlas.R).flip();
            GL20.glUniformMatrix3fv(this.us[8], true, this.rsun); // (rows)
            this.devPlayerQuery(f);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.n);
            if (atlasOn) {
               ShadowAtlas.unbind(3, 4);
            }
         } else if (f.sunOn) {
            GL20.glUseProgram(this.program);
            GL20.glUniform1i(this.u[0], 0);
            GL20.glUniform1i(this.u[1], 1);
            GL20.glUniform4f(this.u[2], kA, cA, kB, cB);
            GL20.glUniform4f(this.u[3], kC, cC, Config.DEV_SUN_VIEW, f.strength);
            float tanA = Math.max(0.005F, f.sun[3]);
            GL20.glUniform4f(this.u[4], f.sun[0], f.sun[1], f.sun[2], 1.0F / tanA); // (both stages)
            GL20.glUniform4f(this.u[5], this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
            GL20.glUniform1f(this.u[6], Config.SUN_SHADOW_MARCH ? 1.0F : 0.0F);
            GL20.glUniform4f(this.u[7], Math.max(1, Config.SUN_SHADOW_CHARACTER_REACH), Config.SUN_SHADOW_CHARACTER_LOD_PCT / 100.0F, 0.0F, devTipOld());
            this.devPlayerQuery(f);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.n);
         }
         int pairs = f.nl > 0 ? this.pairs(f) : 0;
         if (Config.DEV_SIL_COST == 8 && drawn % 600 == 3) {
            StringBuilder sb = new StringBuilder("capsule shadows: dev lights (origin " + f.ox + "," + f.oy + ", pairs " + pairs + "):");
            for (int l = 0; l < f.nl; l++) {
               sb.append(String.format(java.util.Locale.ROOT, " [kind %.0f at %.2f,%.2f,%.2f reach %.1f dir %.2f,%.2f cone %.2f strength %.2f]", f.lK[l], f.lA[l * 4],
                  f.lA[l * 4 + 1], f.lA[l * 4 + 2], f.lA[l * 4 + 3], f.lB[l * 4], f.lB[l * 4 + 1], f.lB[l * 4 + 2], f.lB[l * 4 + 3]));
            }
            sb.append(" casters:");
            for (int c = 0; c < f.n; c++) {
               int base = c * TEXELS * 4;
               sb.append(String.format(java.util.Locale.ROOT, " %.1f,%.1f", (f.data[base + 4] + f.data[base + 8]) * 0.5F, (f.data[base + 5] + f.data[base + 9]) * 0.5F));
            }
            Log.info(sb.toString());
         }
         if (pairs > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.pairTex);
            this.pairUpload.clear();
            this.pairUpload.put(this.pairData, 0, pairs * 4).flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, pairs, 1, GL11.GL_RGBA, GL11.GL_FLOAT, this.pairUpload);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUseProgram(this.lightProgram);
            GL20.glUniform1i(this.ul[9], 2);
            GL20.glUniform1i(this.ul[0], 0);
            GL20.glUniform1i(this.ul[1], 1);
            GL20.glUniform4f(this.ul[2], kA, cA, kB, cB);
            GL20.glUniform4f(this.ul[3], kC, cC, Config.DEV_SIL_COST == 8 ? 8.0F : Config.DEV_SUN_VIEW, f.lightStrength);
            GL20.glUniform4f(this.ul[4], this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
            this.lightA.clear();
            this.lightA.put(f.lA, 0, MAX_LIGHTS * 4).flip();
            this.lightB.clear();
            this.lightB.put(f.lB, 0, MAX_LIGHTS * 4).flip();
            this.lightK.clear();
            this.lightK.put(f.lK, 0, MAX_LIGHTS).flip();
            GL20.glUniform4fv(this.ul[5], this.lightA);
            GL20.glUniform4fv(this.ul[6], this.lightB);
            GL20.glUniform1fv(this.ul[7], this.lightK);
            GL20.glUniform1i(this.ul[8], f.nl);
            GL20.glUniform1f(this.ul[13], Math.max(1.0F, Config.SUN_SHADOW_LAMP_SPREAD_PCT / 100.0F));
            boolean lampAtlas = f.meshes && ShadowAtlas.bind(3, 4);
            GL20.glUniform1i(this.ul[11], 3);
            GL20.glUniform1i(this.ul[12], 4);
            GL20.glUniform4f(this.ul[10], lampAtlas ? 1.0F : 0.0F, ShadowAtlas.PER_ROW, ShadowAtlas.TILE, 1.0F / ShadowAtlas.SIZE);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, pairs);
            if (lampAtlas) {
               ShadowAtlas.unbind(3, 4);
            }
         }
         GL30.glBindVertexArray(0); // the game draws with VAO 0 (GodRays does the same)
         lightPairs += pairs;
         drawn++;
         if (Config.DEV_AO_TIMING) {
            GL33.glQueryCounter(this.queries[this.slot * 2 + 1], GL33.GL_TIMESTAMP);
            this.slot = (this.slot + 1) & 7;
            int b = this.slot * 2;
            if (drawn > 8 && GL15.glGetQueryObjecti(this.queries[b + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               this.ns += GL33.glGetQueryObjecti64(this.queries[b + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(this.queries[b], GL15.GL_QUERY_RESULT);
               if (++this.timed % 600 == 0) {
                  Log.info(String.format("capsule shadows gpu us/frame=%.2f (%d casters, %d lights this frame) | %s", this.ns / 1e3 / 600, f.n, f.nl, stats()));
                  this.ns = 0L;
               }
            }
         }
         // back to the sprite renderer's state
         GL20.glUseProgram(0);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL11.glColorMask(true, true, true, true);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private boolean init() {
         this.program = AmbientOcclusion.link(VERT, withCapsule(FRAG));
         this.lightProgram = AmbientOcclusion.link(LIGHT_VERT, withCapsule(LIGHT_FRAG));
         this.silProgram = AmbientOcclusion.link(VERT, withCapsule(SIL_FRAG));
         if (this.program == 0 || this.lightProgram == 0 || this.silProgram == 0) {
            return false;
         }
         String[] snames = {"SceneDepth", "Data", "mapA", "mapC", "sun", "vp", "sil", "reach", "Rsun", "atlas", "Atlas", "AtlasCmp"};
         for (int i = 0; i < snames.length; i++) {
            this.us[i] = GL20.glGetUniformLocation(this.silProgram, snames[i]);
         }
         String[] names = {"SceneDepth", "Data", "mapA", "mapC", "sun", "vp", "march", "reach"};
         for (int i = 0; i < names.length; i++) {
            this.u[i] = GL20.glGetUniformLocation(this.program, names[i]);
         }
         String[] lnames = {"SceneDepth", "Data", "mapA", "mapC", "vp", "lA", "lB", "lK", "nl", "Pairs", "atlas", "Atlas", "AtlasCmp", "spread"};
         for (int i = 0; i < lnames.length; i++) {
            this.ul[i] = GL20.glGetUniformLocation(this.lightProgram, lnames[i]);
         }
         this.dataTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.dataTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, TEXELS, MAX, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         this.pairTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.pairTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX_PAIRS, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         this.pairUpload = BufferUtils.createFloatBuffer(MAX_PAIRS * 4);
         this.vao = GL30.glGenVertexArrays(); // no attributes: the vertex shader builds the quad from gl_VertexID
         this.upload = BufferUtils.createFloatBuffer(MAX * TEXELS * 4);
         Log.info("capsule shadows: pass ready");
         return true;
      }

      private static int sceneDepthTexture(int fbo) {
         if (fbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }
   }

   /**
    * The sun quad of a caster: its bounding capsule's end points and their projections on its floor along the sun, in
    * window px, boxed along the shadow's direction on screen (an oriented box: a long diagonal shadow in a screen-aligned
    * box would be mostly empty pixels, each paying a depth fetch and a capsule test), widened by the radius and the
    * penumbra at the far end. A caster standing indoors gets none.
    */
   private static final String VERT = String.join("\n",
      "#version 140",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;", // x - y = mapA.x * px + mapA.y, x + y - 6z = mapA.z * py + mapA.w (window px, relative to the origin square)
      "uniform vec4 vp;", // viewport x, y, w, h
      "uniform vec4 sun;", // direction to the sun (world, metric), 1 / tan of the penumbra angle
      "uniform vec4 reach;", // x the longest shadow along the ground (squares, sunShadowCharacterReach), y where the bounding capsule alone takes over
      "flat out int inst;",
      "vec2 toPx(vec3 p) {",
      "   return vec2((p.x - p.y - mapA.y) / mapA.x, (p.x + p.y - 6.0 * p.z / 2.4494897 - mapA.w) / mapA.z);",
      "}",
      "void main() {",
      "   inst = gl_InstanceID;",
      "   vec4 a = texelFetch(Data, ivec2(1, gl_InstanceID), 0);",
      "   vec4 b = texelFetch(Data, ivec2(2, gl_InstanceID), 0);",
      "   vec4 facts = texelFetch(Data, ivec2(3, gl_InstanceID), 0);", // floor z, kind, sun, alpha
      "   vec3 L = sun.xyz;",
      "   float lz = max(L.z, 0.05);",
      // a low sun: the shadow's quad ends at the reach (sun.w's companion, reach.x squares along the ground), faded in the fragment
      "   float lxy = max(length(L.xy), 1e-3);",
      // a caster above the ground (a porch, a balcony, an upper floor): its shadow may fall past its floor's edge onto the
      // level below, so the quad reaches that level's plane too (it ended on the caster's own floor: the shadow was cut)
      "   float fz = facts.x > 0.1 ? facts.x - 2.4494897 : facts.x;",
      // the far end: the shadow of the bounding capsule's top (its end points raised by the radius), not of its axis: a sun
      // below ~45 deg throws the round top past the axis end's shadow plus the side pad, and the quad's edge cut the head's
      // shadow flat (2026-10-02; reach.w = 1: the old end, dev devShadowTipTogglePeriod)
      "   float lift = reach.w > 0.5 ? 0.0 : a.w;",
      "   float ta = min(max(0.0, a.z + lift - fz) / lz, reach.x / lxy), tb = min(max(0.0, b.z + lift - fz) / lz, reach.x / lxy);",
      "   vec3 a2 = vec3(a.xy - L.xy * ta, fz), b2 = vec3(b.xy - L.xy * tb, fz);",
      "   vec2 s0 = toPx(a.xyz), s1 = toPx(b.xyz), s2 = toPx(a2), s3 = toPx(b2);",
      "   vec2 du = toPx(-L) - toPx(vec3(0.0));", // the shadow's direction on screen
      "   vec2 u = dot(du, du) > 1e-6 ? normalize(du) : vec2(1.0, 0.0);",
      "   vec2 w = vec2(-u.y, u.x);",
      "   vec4 pu = vec4(dot(s0, u), dot(s1, u), dot(s2, u), dot(s3, u));",
      "   vec4 pw = vec4(dot(s0, w), dot(s1, w), dot(s2, w), dot(s3, w));",
      "   float pad = (a.w + 0.05 + 0.5 * max(ta, tb) / sun.w) * 1.12 / abs(mapA.x);", // squares to px: a horizontal square spans at most 1.12 / |kA| px (along x or y), a unit of height 1.0 / |kA|
      "   vec2 lo = vec2(min(min(pu.x, pu.y), min(pu.z, pu.w)), min(min(pw.x, pw.y), min(pw.z, pw.w))) - pad;",
      "   vec2 hi = vec2(max(max(pu.x, pu.y), max(pu.z, pu.w)), max(max(pw.x, pw.y), max(pw.z, pw.w))) + pad;",
      "   int v = gl_VertexID;",
      "   vec2 c = vec2((v == 1 || v == 2) ? hi.x : lo.x, (v >= 2) ? hi.y : lo.y);",
      "   vec2 px = u * c.x + w * c.y;",
      "   if (facts.z <= 0.0 || reach.z > 1.5 && reach.z < 2.5) px = vec2(-1e4);", // (reach.z: dev devSilCost 2 = no fragments)
      "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, 0.0, 1.0);",
      "}");

   private static long tipT0;
   private static boolean tipOldNow;

   /**
    * Render thread, dev (devShadowTipTogglePeriod, ms): 1 while the sun quads end at the old place (the shadow of the
    * bounding capsule's axis end), every other period from the first pass; the log line's epoch labels a capture's frames.
    */
   private static float devTipOld() {
      if (Config.DEV_SHADOW_TIP_TOGGLE_PERIOD <= 0) {
         return 0.0F;
      }
      long now = System.currentTimeMillis();
      if (tipT0 == 0L) {
         tipT0 = now;
         Log.info("capsule shadows: dev tip toggle fixed at epoch_ms " + now);
      }
      boolean old = (now - tipT0) / Config.DEV_SHADOW_TIP_TOGGLE_PERIOD % 2L == 1L;
      if (old != tipOldNow) {
         tipOldNow = old;
         Log.info("capsule shadows: dev tip toggle " + (old ? "old" : "fixed") + " at epoch_ms " + now);
      }
      return old ? 1.0F : 0.0F;
   }

   /** Quilez, capsule soft shadow: the ray's closest approach to the segment over the distance travelled (tmax: the light's distance). */
   private static final String CAPSULE_GLSL = String.join("\n",
      "float capShadow(vec3 ro, vec3 rd, vec3 a, vec3 b, float r, float k, float tmax) {",
      "   vec3 ba = b - a;",
      "   vec3 oa = ro - a;",
      "   float oad = dot(oa, rd);",
      "   float dba = dot(rd, ba);",
      "   float baba = dot(ba, ba);",
      "   float oaba = dot(oa, ba);",
      "   vec2 th = vec2(-oad * baba + dba * oaba, oaba - oad * dba) / max(baba - dba * dba, 1e-6);",
      "   th.x = clamp(th.x, 0.0001, tmax);",
      "   th.y = clamp(th.y, 0.0, 1.0);",
      "   vec3 p = a + ba * th.y;",
      "   vec3 q = ro + rd * th.x;",
      "   float d = length(p - q) - r;",
      "   float s = clamp(k * d / th.x + 0.5, 0.0, 1.0);",
      "   return s * s * (3.0 - 2.0 * s);",
      "}",
      "vec3 worldAt(vec2 f, float d, vec4 mapA, vec4 mapC) {",
      "   float A = mapA.x * f.x + mapA.y;",
      "   float B = mapA.z * f.y + mapA.w;",
      "   float C = mapC.x * d + mapC.y;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z * 2.4494897);",
      "}",
      // sunShadowWallCut: 1 where one of the caster's walls / fences (texels WALL0.., walls()) stands between the receiver P
      // and the sun L: that receiver is in the wall's own shadow, the caster's shadow ends there (it went through fences).
      // A run along x is the line y = w.x from w.y to w.z (an N edge), along y (stored w.y > w.z) the line x = w.x (a W
      // edge); w.w its top (metric), floor the caster's. The faces the camera sees look south (N edge) / east (W edge): a
      // receiver on the line is on that face, in shade while the sun is on the other side
      "float wallCut(vec3 P, vec3 L, int inst, float floorZ) {",
      "   float cut = 0.0;",
      "   for (int i = 0; i < " + WALLS + "; i++) {",
      "      vec4 w = texelFetch(Data, ivec2(" + WALL0 + " + i, inst), 0);",
      "      if (w.y == w.z) break;",
      "      bool alongX = w.y < w.z;",
      "      float lo = min(w.y, w.z), hi = max(w.y, w.z);",
      "      float off = (alongX ? P.y : P.x) - w.x;", // > 0: the side the camera sees
      "      float ln = alongX ? L.y : L.x;",
      "      float a = alongX ? P.x : P.y;",
      "      float zc = P.z;",
      "      if (abs(off) < 0.1) {",
      "         if (ln >= 0.0) continue;", // the sun on the seen face's side: lit
      "      } else {",
      "         if (off * ln >= 0.0) continue;", // the ray to the sun leaves the line behind
      "         float t = -off / ln;",
      "         a += (alongX ? L.x : L.y) * t;",
      "         zc += L.z * t;",
      "      }",
      "      if (a < lo - 0.02 || a > hi + 0.02 || zc < floorZ - 0.05) continue;",
      "      cut = max(cut, 1.0 - smoothstep(w.w - 0.08, w.w + 0.08, zc));",
      "   }",
      "   return cut;",
      "}");

   private static final String FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 mapC;", // x + y + 2z = mapC.x * depth + mapC.y; dev view; strength
      "uniform vec4 sun;", // direction to the sun (world, metric), 1 / tan of the penumbra angle
      "uniform float march;", // sunShadowMarch: the per-pixel static-shade test
      "uniform vec4 reach;", // x the longest shadow along the ground (squares), y where the bounding capsule alone takes over (squares)
      "flat in int inst;",
      "out vec4 fragColor;",
      "#include capsule",
      "void main() {",
      "   vec2 f = gl_FragCoord.xy;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   if (d >= 0.99999) discard;",
      "   vec3 P = worldAt(f, d, mapA, mapC);",
      "   vec3 ro = P + sun.xyz * 0.03;",
      "   vec4 ba0 = texelFetch(Data, ivec2(1, inst), 0);",
      "   vec4 bb0 = texelFetch(Data, ivec2(2, inst), 0);",
      "   if (capShadow(ro, sun.xyz, ba0.xyz, bb0.xyz, ba0.w, sun.w, 1e4) > 0.999) discard;", // outside the bounding capsule's shadow: full sun
      "   vec4 facts = texelFetch(Data, ivec2(3, inst), 0);", // floor z, kind, the caster's own sun share, alpha
      // how far along the ground from the caster: past reach.x nothing (a low sun's quad was capped), and past reach.y the
      // bounding capsule alone (thinned to the body): the penumbra there is wider than a limb, ten tests bought nothing
      "   float hd = length(P.xy - 0.5 * (ba0.xy + bb0.xy));",
      "   float fade = 1.0 - smoothstep(0.7 * reach.x, reach.x, hd);",
      "   if (fade <= 0.0) discard;",
      "   float alpha = facts.w * facts.z * fade;",
      "   float lod = smoothstep(reach.y, reach.y + 1.0, hd);",
      "   float vis = 1.0;",
      "   if (lod < 1.0) {",
      "      for (int i = 0; i < 10; i++) {", // K
      "         vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "         vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "         if (a.w > 0.0) vis *= capShadow(ro, sun.xyz, a.xyz, b.xyz, a.w, sun.w, 1e4);",
      "      }",
      "   }",
      "   if (lod > 0.0) vis = mix(vis, capShadow(ro, sun.xyz, ba0.xyz, bb0.xyz, ba0.w * 0.55, sun.w, 1e4), lod);",
      // dev (sunShadowMarch): a receiver the static world already hides from the sun takes no second shadow, per pixel:
      // march the scene depth towards the sun (8 steps, up to ~6 squares). Off by default: the caster's own sun share
      // (facts.z) does it per caster, for nothing
      "   if (vis < 0.995 && march > 0.5) {",
      "      for (int j = 1; j <= 8; j++) {",
      "         float t = float(j * j) * 0.09;",
      "         vec3 Q = P + sun.xyz * t;",
      "         float zl = Q.z / 2.4494897;",
      "         vec2 fq = vec2((Q.x - Q.y - mapA.y) / mapA.x, (Q.x + Q.y - 6.0 * zl - mapA.w) / mapA.z);",
      "         ivec2 iq = ivec2(fq);",
      "         if (any(lessThan(iq, ivec2(0))) || any(greaterThanEqual(iq, textureSize(SceneDepth, 0)))) break;",
      "         float ds = texelFetch(SceneDepth, iq, 0).r;",
      "         if (ds >= 0.99999) continue;",
      "         float gap = (mapC.x * ds + mapC.y) - (Q.x + Q.y + 2.0 * zl);", // > 0: the surface there is nearer the camera than the ray point
      "         if (gap > 0.12 && gap < 2.0) { vis = 1.0; break; }",
      "      }",
      "   }",
      "   if (vis < 0.995) vis = mix(vis, 1.0, wallCut(P, sun.xyz, inst, facts.x));",
      "   float m = 1.0 - mapC.w * alpha * (1.0 - vis);",
      "   if (mapC.z > 0.5) m = mix(0.5, 1.0, vis);", // dev: the capsule term alone over grey
      "   if (m > 0.996) discard;",
      "   fragColor = vec4(vec3(m), 1.0);",
      "}");

   /**
    * sunShadowSilhouette: the caster's shadow from its drawn shape. Drawn after the moving objects, so the scene depth holds
    * the caster's own surface (models; an atlas zombie is a flat sprite and keeps the capsules). Per pixel of the caster's
    * quad: the receiver from the depth; a pixel of the caster itself (inside its bounding capsule, above its floor) takes none;
    * the sun ray's stretch inside the bounding capsule is marched, each sample projected to the screen: where the depth there
    * belongs to the caster (inside its bounding capsule, above its floor) and lies in front of the sample by less than the
    * caster's depth (texel 0: a body's, an animal's, a car's), the ray is blocked. The ray leans across the sun's disk per
    * pixel (interleaved gradient noise): the penumbra grows with the distance from the caster. Where that penumbra is wider
    * than a limb (far from the caster) the capsule model's soft shadow takes over.
    */
   private static final String SIL_FRAG = String.join("\n",
      "#version 140",
      "#extension GL_ARB_texture_gather : enable",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Data;",
      "uniform sampler2D Atlas;", // sunShadowMeshes: the casters' sun depth (raw, nearest)
      "uniform sampler2DShadow AtlasCmp;", // the same, compared (bilinear PCF)
      "uniform mat3 Rsun;", // the model's world frame (x west, y up, z north) to the sun's view
      "uniform vec4 atlas;", // x 1 = the atlas this frame, y tiles a row, z tile texels, w 1 / atlas texels
      "uniform vec4 mapA;",
      "uniform vec4 mapC;", // x + y + 2z = mapC.x * depth + mapC.y; dev view; strength
      "uniform vec4 sun;", // direction to the sun (world, metric), 1 / tan of the penumbra angle
      "uniform vec4 sil;", // x the shell's steps (0: no shell), y tan of the sun's angular radius, z the frame, w 1 = the limbs from the shell alone
      "uniform vec4 reach;", // x the longest shadow along the ground (squares), y where the bounding capsule alone takes over (squares)
      "flat in int inst;",
      "out vec4 fragColor;",
      "#include capsule",
      "const float ATLAS_DEPTH = " + ShadowAtlas.DEPTH + ";",
      "const vec2 POISSON[12] = vec2[12](vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),",
      "   vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893), vec2(0.507, 0.064), vec2(0.896, 0.412),",
      "   vec2(-0.322, -0.933), vec2(-0.792, -0.598));",
      "float segDist(vec3 p, vec3 a, vec3 b) {",
      "   vec3 ba = b - a;",
      "   float h = clamp(dot(p - a, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);",
      "   return length(p - a - ba * h);",
      "}",
      // the caster's own sun shadow map (its atlas tile): a percentage-closer soft shadow. The receiver in the tile's view,
      // a blocker search over the widest penumbra it could have, the blockers' mean distance gives the penumbra's width,
      // then 12 compared taps (each a 2 x 2 bilinear PCF) over it
      "float atlasVis(vec3 P, vec3 C, float tile, float halfSize) {",
      "   vec3 w = vec3(-(P.x - C.x), P.z - C.z, -(P.y - C.y));",
      "   vec3 v = Rsun * w;",
      "   vec2 tuv = v.xy / (2.0 * halfSize) + 0.5;",
      "   float drRaw = 0.5 - v.z / (2.0 * ATLAS_DEPTH);",
      "   if (any(lessThan(tuv, vec2(0.0))) || any(greaterThan(tuv, vec2(1.0)))) return 1.0;",
      "   float tileUv = atlas.z * atlas.w;",
      "   vec2 org = vec2(mod(tile, atlas.y), floor(tile / atlas.y)) * tileUv;",
      "   float texSq = 2.0 * halfSize / atlas.z;", // squares a texel
      "   vec2 lo = org + 1.5 * atlas.w, hi = org + tileUv - 1.5 * atlas.w;",
      "   vec2 uv = org + tuv * tileUv;",
      "   float bias = 0.03 / (2.0 * ATLAS_DEPTH);",
      // a receiver past the tile's far plane (a low sun's long shadow on the ground, more than ATLAS_DEPTH squares behind the
      // caster's centre along the sun) is behind everything the tile holds: its compare depth is held just inside the range.
      // It returned full light there, which cut the shadow off on a line across the torso (2026-09-29, 8 deg morning sun)
      "   float dr = min(drRaw, 1.0 - bias);",
      "   float behind = max(0.0, (drRaw - 0.5) * 2.0 * ATLAS_DEPTH + halfSize);", // at most this far behind a blocker along the sun
      "   float rs = clamp(behind * sil.y / texSq, 1.0, 6.0);", // the blocker search's radius (texels)
      // (four gathers of 2 x 2 depths each: 16 samples in 4 fetches)
      "   float bsum = 0.0, bn = 0.0;",
      "   for (int k = 0; k < 4; k++) {",
      "      vec2 o = vec2(k == 0 || k == 2 ? -0.5 : 0.5, k < 2 ? -0.5 : 0.5) * rs;",
      "      vec4 db = textureGather(Atlas, clamp(uv + o * atlas.w, lo, hi));",
      "      vec4 in4 = vec4(lessThan(db, vec4(dr - bias)));",
      "      bsum += dot(db, in4);",
      "      bn += dot(in4, vec4(1.0));",
      "   }",
      "   if (bn < 0.5) return 1.0;",
      "   float dist = (drRaw - bsum / bn) * 2.0 * ATLAS_DEPTH;", // squares from the blockers along the sun (the true distance: the penumbra widens along a long shadow)
      "   float rad = clamp(dist * sil.y / texSq, 0.6, 8.0);", // the penumbra's half width (texels)
      "   float lit = 0.0;",
      "   for (int k = 0; k < 12; k++) {",
      "      lit += texture(AtlasCmp, vec3(clamp(uv + POISSON[k] * rad * atlas.w, lo, hi), dr - bias));",
      "   }",
      "   return lit / 12.0;",
      "}",
      "void main() {",
      "   if (reach.z > 2.5 && reach.z < 3.5) { fragColor = vec4(1.0, 0.3, 0.3, 1.0); return; }", // dev (devSilCost 3): the quads
      "   if (reach.z > 0.5 && reach.z < 1.5) discard;", // dev (devSilCost 1): every fragment out at once
      "   vec2 f = gl_FragCoord.xy;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   if (d >= 0.99999 || reach.z > 6.5) discard;", // (dev devSilCost 7: the depth read, then out)
      "   vec3 P = worldAt(f, d, mapA, mapC);",
      "   vec4 ba0 = texelFetch(Data, ivec2(1, inst), 0);",
      "   vec4 bb0 = texelFetch(Data, ivec2(2, inst), 0);",
      "   vec4 facts = texelFetch(Data, ivec2(3, inst), 0);", // floor z, kind, the caster's own sun share, alpha
      "   vec4 own = texelFetch(Data, ivec2(0, inst), 0);", // tile (-1), its half size, the depth shell, 1 = its pixels are in the depth
      // dev (devSilCost 5): the receiver's reconstructed height over its caster's floor (red 1 square, green 0) and its
      // distance from the caster (blue 4 squares)
      "   if (reach.z > 4.5) { fragColor = vec4(clamp(P.z - facts.x, 0.0, 1.0), 1.0 - clamp(abs(P.z - facts.x), 0.0, 1.0), clamp(length(P.xy - ba0.xy) / 4.0, 0.0, 1.0), 1.0); return; }",
      "   float rb = ba0.w;",
      "   if (own.w > 0.5 && P.z > facts.x + 0.1 && segDist(P, ba0.xyz, bb0.xyz) < rb) discard;", // the caster's own pixel
      "   vec3 L0 = sun.xyz;",
      "   vec3 L = L0;",
      "   vec3 ro = P + L0 * 0.03;",
      "   if (capShadow(ro, L0, ba0.xyz, bb0.xyz, rb, sun.w, 1e4) > 0.999) discard;", // outside the bounding capsule's shadow: full sun
      "   if (reach.z > 3.5) { fragColor = vec4(0.3, 1.0, 0.3, 1.0); return; }", // dev (devSilCost 4): past the bounding test
      "   float hd = length(P.xy - 0.5 * (ba0.xy + bb0.xy));",
      "   float fade = 1.0 - smoothstep(0.7 * reach.x, reach.x, hd);",
      "   if (fade <= 0.0) discard;",
      "   float alpha = facts.w * facts.z * fade;",
      "   float vis;",
      "   if (atlas.x > 0.5 && own.x > -0.5) {",
      "      vis = atlasVis(P, texelFetch(Data, ivec2(" + CENTRE + ", inst), 0).xyz, own.x, own.y);",
      "   } else {",
      // the capsule model (soft): the ten capsules while the penumbra is narrower than a limb, the bounding capsule thinned to
      // the body where it is wider (with a sharp sun a low sun's long shadow keeps its arms and head for squares)
      "      float lod = smoothstep(0.15, 0.3, 2.0 * hd / max(length(L0.xy), 0.05) * sil.y);",
      // with the depth shell (sil.w = 1) a person keeps only the torso and head capsules and an animal its trunk: a limb is
      // thinner than the shell, so the shell alone draws its shadow; a car keeps its three
      "      float vcap = 1.0;",
      "      int nCap = own.w > 0.5 && sil.w > 0.5 && sil.x > 0.5 ? (facts.y > 1.5 ? 1 : facts.y > 0.5 ? 10 : 2) : 10;",
      "      if (lod < 1.0) {",
      "         for (int i = 0; i < 10; i++) {",
      "            if (i >= nCap) break;",
      "            vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "            vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "            if (a.w > 0.0) vcap *= capShadow(ro, L0, a.xyz, b.xyz, a.w, sun.w, 1e4);",
      "         }",
      "      }",
      "      if (lod > 0.0) vcap = mix(vcap, capShadow(ro, L0, ba0.xyz, bb0.xyz, rb * 0.55, sun.w, 1e4), lod);",
      // the ray's stretch inside the bounding capsule: the segment's closest approach to the ray, the chord round it
      "      vec3 ba = bb0.xyz - ba0.xyz;",
      "      float bl = length(ba);",
      "      vec3 bd = bl > 1e-4 ? ba / bl : vec3(0.0, 0.0, 1.0);",
      "      vec3 w0 = ro - ba0.xyz;",
      "      float bdl = dot(bd, L);",
      "      float den = max(1.0 - bdl * bdl, 1e-4);",
      "      float tc = clamp((bdl * dot(w0, bd) - dot(w0, L)) / den, 0.0, 1e4);",
      "      float sinA = sqrt(den);",
      "      float hc = min(rb / max(sinA, 0.05), 0.5 * bl + rb);",
      "      float t0 = max(0.0, tc - hc - rb), t1 = tc + hc + rb;",
      "      float vss = 1.0;",
      "      float tHit = tc;",
      // the drawn surface as a thin shell (own.z deep) behind the depth: hair, clothes, bags, weapons, a car's mirrors, what
      // the capsules miss; only where the capsules leave light to take, the steps following the stretch's length
      "      if (own.w > 0.5 && vcap > 0.03 && sil.x > 0.5) {",
      "         int steps = int(clamp(ceil((t1 - t0) / 0.08), 3.0, sil.x));",
      "         float dt = (t1 - t0) / float(steps);",
      "         ivec2 size = textureSize(SceneDepth, 0);",
      "         for (int i = 0; i < 48; i++) {",
      "            if (i >= steps) break;",
      "            float t = t0 + dt * (float(i) + 0.5);",
      "            vec3 R = ro + L * t;",
      "            float zl = R.z / 2.4494897;",
      "            vec2 fq = vec2((R.x - R.y - mapA.y) / mapA.x, (R.x + R.y - 6.0 * zl - mapA.w) / mapA.z);",
      "            ivec2 iq = ivec2(fq);",
      "            if (any(lessThan(iq, ivec2(0))) || any(greaterThanEqual(iq, size))) continue;",
      "            float ds = texelFetch(SceneDepth, iq, 0).r;",
      "            if (ds >= 0.99999) continue;",
      "            float gap = (mapC.x * ds + mapC.y) - (R.x + R.y + 2.0 * zl);", // > 0: the surface there is nearer the camera than the ray point
      "            if (gap <= -0.02 || gap > own.z) continue;",
      "            vec3 Q = worldAt(vec2(iq) + 0.5, ds, mapA, mapC);",
      "            if (Q.z <= facts.x + 0.05 || segDist(Q, ba0.xyz, bb0.xyz) >= rb + 0.05) continue;",
      "            float occ = 1.0 - smoothstep(0.6 * own.z, own.z, gap);",
      "            if (1.0 - occ < vss) { vss = 1.0 - occ; tHit = t; }",
      "            if (vss < 0.01) break;",
      "         }",
      // far from the caster the penumbra is wider than these details: they blur out (the capsules keep the body)
      "         vss = mix(vss, 1.0, smoothstep(0.06, 0.3, 2.0 * tHit * sil.y));",
      "      }",
      "      vis = min(vcap, vss);",
      "   }",
      "   if (vis < 0.995) vis = mix(vis, 1.0, wallCut(P, L0, inst, facts.x));",
      "   float m = 1.0 - mapC.w * alpha * (1.0 - vis);",
      "   if (mapC.z > 0.5) m = mix(0.5, 1.0, vis);", // dev: the term alone over grey
      "   if (m > 0.996) discard;",
      "   fragColor = vec4(vec3(m), 1.0);",
      "}");

   /**
    * Casters x lights: instance = caster * nl + light. The quad: the screen box of the bounding capsule and its projection
    * away from the light onto the caster's floor (at most the light's reach from it); none for a caster out of the light's
    * reach, for the torch's holder, or for a vehicle and its own headlights.
    */
   private static final String LIGHT_VERT = String.join("\n",
      "#version 140",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 vp;",
      "uniform vec4 lA[" + MAX_LIGHTS + "];", // x, y (relative to the origin square), z (metric), reach
      "uniform vec4 lB[" + MAX_LIGHTS + "];", // direction x, y, cone cosine (-2: none), strength
      "uniform float lK[" + MAX_LIGHTS + "];", // 1 a handheld torch, 2 a vehicle light, 3 a static point light, 4 a fire, 5 a room's ceiling light
      "uniform int nl;",
      "uniform float spread;",
      "uniform sampler2D Pairs;", // instance -> (caster, light, its lamp view or -1, the shadow's end: distance from the light)
      "flat out int inst;",
      "flat out float lim;",
      "flat out int light;",
      "flat out int view;",
      "out vec2 qc;", // the quad's own coordinates (0..1: from the lamp side on, across)
      "vec2 toPx(vec3 p) {",
      "   float A = p.x - p.y, B = p.x + p.y - 6.0 * p.z / 2.4494897;",
      "   return vec2((A - mapA.y) / mapA.x, (B - mapA.w) / mapA.z);",
      "}",
      // sunShadowStaticLights: the distance from the light at L along dir to the wall plane w (StaticLightShadow.planeW), reach
      // when there is none on the way (StaticLightShadow.planeDistance)
      "float wallDist(vec2 L, vec2 dir, float w, float reach) {",
      "   float a = abs(w);",
      "   if (a > 5000.0) return reach;",
      "   float ax = floor(a / 1000.0);",
      "   float comp = ax < 1.5 ? dir.x : dir.y;",
      "   if (comp * w <= 0.0) return reach;",
      "   return max(0.0, ((a - ax * 1000.0 - 500.0) - (ax < 1.5 ? L.x : L.y)) / comp);",
      "}",
      "void main() {",
      "   vec4 pair = texelFetch(Pairs, ivec2(gl_InstanceID, 0), 0);",
      "   lim = pair.w;",
      "   int c = int(pair.x + 0.5);",
      "   int l = int(pair.y + 0.5);",
      "   inst = c;",
      "   light = l;",
      "   view = pair.z < -0.5 ? -1 : int(pair.z + 0.5);",
      "   vec4 a = texelFetch(Data, ivec2(1, c), 0);",
      "   vec4 b = texelFetch(Data, ivec2(2, c), 0);",
      "   vec4 facts = texelFetch(Data, ivec2(3, c), 0);", // floor z, kind, sun, alpha
      "   vec3 L = lA[l].xyz;",
      "   vec3 mid = 0.5 * (a.xyz + b.xyz);",
      "   float dl = length(mid.xy - L.xy);",
      "   bool skip = dl > lA[l].w + a.w || (lK[l] < 1.5 && dl < 0.7) || (lK[l] > 1.5 && lK[l] < 2.5 && facts.y > 0.5 && facts.y < 1.5 && dl < 3.5);",
      "   vec2 sp[4];",
      "   float pd[4];", // each corner's pad (squares): the far ones widen with the shadow's cone
      "   float reach = lA[l].w;",
      "   float rr = a.w + (view >= 0 ? 0.15 : 0.0);", // (a lamp view: the model's clothes and hair round the capsules)
      "   for (int e = 0; e < 2; e++) {",
      "      vec3 p = e == 0 ? a.xyz : b.xyz;",
      "      vec2 dir = p.xy - L.xy;",
      "      float dh = length(dir);",
      "      dir = dh > 1e-3 ? dir / dh : vec2(1.0, 0.0);",
      "      float t = p.z < L.z - 0.05 ? (L.z - facts.x) / (L.z - p.z) : 1e3;", // the ray from the lamp through p hits the floor at L + (p - L) t
      "      float far = min(min(dh * t, reach), max(wallDist(L.xy, dir, lim, reach), dh)) + rr + 0.3;", // a static light's shadow ends at its wall plane (sunShadowStaticLights)
      "      sp[e * 2] = toPx(p);",
      "      sp[e * 2 + 1] = toPx(vec3(L.xy + dir * far, facts.x));",
      "      pd[e * 2] = rr + 0.3;",
      "      pd[e * 2 + 1] = rr * clamp(far / max(dh, 0.3), 1.0, spread) + 0.3;", // (sunShadowLampSpreadPct; past it the sides fade: qc)
      "   }",
      "   vec2 du = toPx(vec3(mid.xy, facts.x)) - toPx(vec3(L.xy, facts.x));", // away from the lamp, on screen
      "   vec2 u = dot(du, du) > 1e-6 ? normalize(du) : vec2(1.0, 0.0);",
      "   vec2 w = vec2(-u.y, u.x);",
      "   vec4 pu = vec4(dot(sp[0], u), dot(sp[1], u), dot(sp[2], u), dot(sp[3], u));",
      "   vec4 pw = vec4(dot(sp[0], w), dot(sp[1], w), dot(sp[2], w), dot(sp[3], w));",
      "   vec4 pad = vec4(pd[0], pd[1], pd[2], pd[3]) * 1.12 / abs(mapA.x);",
      "   vec4 ulo = pu - pad, uhi = pu + pad, wlo = pw - pad, whi = pw + pad;",
      "   vec2 lo = vec2(min(min(ulo.x, ulo.y), min(ulo.z, ulo.w)), min(min(wlo.x, wlo.y), min(wlo.z, wlo.w)));",
      "   vec2 hi = vec2(max(max(uhi.x, uhi.y), max(uhi.z, uhi.w)), max(max(whi.x, whi.y), max(whi.z, whi.w)));",
      "   int v = gl_VertexID;",
      "   vec2 cc = vec2((v == 1 || v == 2) ? hi.x : lo.x, (v >= 2) ? hi.y : lo.y);",
      "   qc = vec2((v == 1 || v == 2) ? 1.0 : 0.0, (v >= 2) ? 1.0 : 0.0);",
      "   vec2 px = u * cc.x + w * cc.y;",
      "   if (skip) px = vec2(-1e4);",
      "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, 0.0, 1.0);",
      "}");

   private static final String LIGHT_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 mapC;", // x + y + 2z = mapC.x * depth + mapC.y; dev view; strength (x the darkness)
      "uniform vec4 lA[" + MAX_LIGHTS + "];",
      "uniform vec4 lB[" + MAX_LIGHTS + "];",
      "uniform float lK[" + MAX_LIGHTS + "];",
      "uniform vec4 atlas;", // on, tiles a row, tile size, 1 / atlas size
      "uniform sampler2D Atlas;",
      "uniform sampler2DShadow AtlasCmp;",
      "flat in int inst;",
      "flat in int light;",
      "flat in int view;",
      "flat in float lim;",
      "in vec2 qc;",
      "out vec4 fragColor;",
      "#include capsule",
      "const vec2 LPOISSON[8] = vec2[](vec2(-0.613, 0.617), vec2(0.170, -0.040), vec2(-0.299, -0.792), vec2(0.645, 0.493),",
      "   vec2(-0.651, -0.109), vec2(0.421, -0.664), vec2(-0.094, 0.927), vec2(0.905, -0.100));",
      // sunShadowLampMeshes: the receiver in the caster's view from the lamp (ShadowAtlas.SunCamera's lamp branch: a look-at
      // from the lamp at the tile's centre, the frustum round the bounding sphere), a percentage-closer lookup whose radius
      // grows with the receiver's distance behind the blockers (the lamp's size)
      "float lampVis(vec3 P, int c, int j, float lampSize) {",
      "   vec4 t0 = texelFetch(Data, ivec2(" + LAMP0 + " + 3 * j, c), 0);",
      "   vec3 C = texelFetch(Data, ivec2(" + (LAMP0 + 1) + " + 3 * j, c), 0).xyz;",
      "   vec3 Lo = texelFetch(Data, ivec2(" + (LAMP0 + 2) + " + 3 * j, c), 0).xyz;",
      "   vec3 q = P - C;",
      "   vec3 mq = vec3(-q.x, q.z, -q.y);", // the model's world frame: x west, y up, z north
      "   vec3 mL = vec3(-Lo.x, Lo.z, -Lo.y);",
      "   float dist = length(mL), R = t0.y;",
      "   vec3 zA = mL / dist;",
      "   vec3 up = abs(zA.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);",
      "   vec3 xA = normalize(cross(up, zA));",
      "   vec3 yA = cross(zA, xA);",
      "   vec3 v = mq - mL;",
      "   float dz = -dot(zA, v);", // distance from the lamp along the view
      "   float tanH = R / sqrt(max(dist * dist - R * R, 1e-4));",
      "   vec2 tuv = vec2(dot(xA, v), dot(yA, v)) / (dz * tanH) * 0.5 + 0.5;",
      "   float n = max(0.02, dist - R), fa = dist + R;",
      "   if (dz <= n || any(lessThan(tuv, vec2(0.0))) || any(greaterThan(tuv, vec2(1.0)))) return 1.0;",
      "   float zb = min(dz - 0.04, fa);", // (a receiver past the far plane compares with the cleared depth: behind every blocker)
      // (below the cleared 1.0: at the far plane the formula rounds to a hair over 1 and every empty texel would block)
      "   float ref = min(((fa + n) / (fa - n) - 2.0 * fa * n / ((fa - n) * zb)) * 0.5 + 0.5, 0.99999);",
      "   float tile = t0.x;",
      "   float tileUv = atlas.z * atlas.w;",
      "   vec2 org = vec2(mod(tile, atlas.y), floor(tile / atlas.y)) * tileUv;",
      "   vec2 lo = org + 1.5 * atlas.w, hi = org + tileUv - 1.5 * atlas.w;",
      "   vec2 uv = org + tuv * tileUv;",
      "   float texSq = 2.0 * tanH * dz / atlas.z;", // squares a texel at the receiver
      // blockers: four taps a texel and a half out, their distance from the lamp
      "   float bsum = 0.0, bn = 0.0;",
      "   for (int k = 0; k < 4; k++) {",
      "      vec2 o = vec2(k == 0 || k == 2 ? -1.5 : 1.5, k < 2 ? -1.5 : 1.5);",
      "      float db = texture(Atlas, clamp(uv + o * atlas.w, lo, hi)).r;",
      "      if (db < ref) { bsum += 2.0 * fa * n / ((fa + n) - (2.0 * db - 1.0) * (fa - n)); bn += 1.0; }",
      "   }",
      "   if (bn < 0.5) return 1.0;",
      "   float blk = bsum / bn;",
      "   float rad = clamp(lampSize * (dz - blk) / max(blk, 0.05) / texSq, 1.5, 6.0);", // the penumbra's half width (texels)
      "   float lit = 0.0;",
      "   for (int k = 0; k < 8; k++) {",
      "      lit += texture(AtlasCmp, vec3(clamp(uv + LPOISSON[k] * rad * atlas.w, lo, hi), ref));",
      "   }",
      "   return lit / 8.0;",
      "}",
      "float segDist(vec3 p, vec3 a, vec3 b) {",
      "   vec3 ba = b - a;",
      "   float h = clamp(dot(p - a, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);",
      "   return length(p - a - ba * h);",
      "}",
      // PixelLight's fitted torch intensity (the share of the pixel's light the lamp gives in the dark)
      // sunShadowStaticLights: how far past the wall plane w (StaticLightShadow.planeW) the point lies on the shadow's side
      // (StaticLightShadow.beyond; -1e4 no plane): the floor behind the wall takes none of the shadow, the wall's face all of it
      "float wallBeyond(vec2 p, float w) {",
      "   float a = abs(w);",
      "   if (a > 5000.0) return -1e4;",
      "   float ax = floor(a / 1000.0);",
      "   return sign(w) * ((ax < 1.5 ? p.x : p.y) - (a - ax * 1000.0 - 500.0));",
      "}",
      "float torch(vec2 p, vec4 a, vec4 b, float kind) {",
      "   vec2 v = p - a.xy;",
      "   float d = length(v);",
      "   float x = clamp(1.0 - d / a.w, 0.0, 1.0);",
      // a static point light (sunShadowStaticLights): a quadratic fade of the native's lamp field, saturating (StaticLightShadow.share)
      // a room's ceiling light (sunShadowRoomLights): the room is lit evenly, the share flat, fading out at the point's reach (StaticLightShadow.roomShare)
      "   if (kind > 4.5) return b.w * min(1.0, x * " + StaticLightShadow.ROOM_FADE + ");",
      "   if (kind > 2.5) return min(1.0, " + StaticLightShadow.GAIN + " * b.w * x * x);",
      "   bool car = kind > 1.5;",
      "   float fall = car ? x * sqrt(x) * (0.93 + 0.07 * x) : x * (0.85 + 0.15 * x);",
      "   float ang = 1.0;",
      "   if (b.z > -1.5 && d > 1e-3) {",
      "      float c0 = car ? b.z - 0.28 : b.z + 0.025, c1 = car ? 1.0 : 0.95;",
      "      ang = clamp((dot(v, b.xy) / d - c0) / max(c1 - c0, 0.05), 0.0, 1.0);",
      "   }",
      "   return min(1.0, (car ? 1.57 : 1.76) * b.w * fall * ang);",
      "}",
      "void main() {",
      "   vec2 f = gl_FragCoord.xy;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   if (d >= 0.99999) discard;",
      "   vec3 P = worldAt(f, d, mapA, mapC);",
      "   vec4 la = lA[light];",
      "   float share = torch(P.xy, la, lB[light], lK[light]);",
      // dev (devSilCost 8): the light quads, blue outside the lamp's light, red by the lamp's share inside
      "   if (mapC.z > 7.5) { fragColor = share < 0.02 ? vec4(0.6, 0.6, 1.0, 1.0) : vec4(1.0, 1.0 - 0.7 * share, 1.0 - 0.7 * share, 1.0); return; }",
      "   if (share < 0.02) discard;",
      "   vec3 tl = la.xyz - P;",
      "   float tmax = length(tl);",
      "   vec3 rd = tl / tmax;",
      "   vec3 ro = P + rd * 0.03;",
      "   float lampSize = lK[light] > 4.5 ? " + CEIL_LAMP_SIZE + " : lK[light] > 3.5 ? " + StaticLightShadow.FIRE_SIZE + " : lK[light] > 2.5 ? " + StaticLightShadow.LAMP_SIZE + " : lK[light] > 1.5 ? 0.25 : 0.12;", // a static lamp's globe is bigger than a torch's lens, a fire's flames bigger still, a ceiling light biggest
      "   float k = tmax / lampSize;", // the lamp's size: penumbra grows with the caster's distance from the receiver
      "   vec4 ba0 = texelFetch(Data, ivec2(1, inst), 0);",
      "   vec4 bb0 = texelFetch(Data, ivec2(2, inst), 0);",
      // the caster's own pixel takes none of its own lamp shadow (as in SIL_FRAG): the pass runs after the moving objects
      // (sunShadowSilhouette), and a car's surface lies inside or behind its three capsules seen from a torch in front of it,
      // so the rear half of a taxi lit by the player's torch went black in capsule-shaped patches (2026-10-01)
      "   vec4 own = texelFetch(Data, ivec2(0, inst), 0);",
      "   float floorZ = texelFetch(Data, ivec2(3, inst), 0).x;",
      "   if (own.w > 0.5 && P.z > floorZ + 0.1 && segDist(P, ba0.xyz, bb0.xyz) < ba0.w) discard;",
      "   if (capShadow(ro, rd, ba0.xyz, bb0.xyz, ba0.w + (view >= 0 ? 0.15 : 0.0), k, tmax) > 0.999) discard;",
      "   float alpha = texelFetch(Data, ivec2(3, inst), 0).w;",
      "   float vis = 1.0;",
      "   if (view >= 0 && atlas.x > 0.5) {",
      "      vis = lampVis(P, inst, view, lampSize);",
      // sunShadowLampMeshNearPct / FarPct: past a square or so from the caster the lamp view's shadow (one perspective tile:
      // a low headlight's shadow of the legs stretched into thin sharp strands, "torn", Discord 2026-10-04) fades into the
      // capsules' soft shadow, whose penumbra widens with the distance as a lamp's does; the shape stays where it is seen
      "      float fk = " + (Config.SUN_SHADOW_LAMP_MESH_FAR_PCT <= 0 ? "0.0" : "smoothstep(" + (Config.SUN_SHADOW_LAMP_MESH_NEAR_PCT / 100.0F) + ", " + (Math.max(Config.SUN_SHADOW_LAMP_MESH_FAR_PCT, Config.SUN_SHADOW_LAMP_MESH_NEAR_PCT + 1) / 100.0F) + ", length(P.xy - 0.5 * (ba0.xy + bb0.xy)))") + ";",
      "      if (fk > 0.0) {",
      "         float cv = 1.0;",
      "         for (int i = 0; i < 10; i++) {",
      "            vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "            vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "            if (a.w > 0.0) cv *= capShadow(ro, rd, a.xyz, b.xyz, a.w, k, tmax);",
      "         }",
      "         vis = mix(vis, cv, fk);",
      "      }",
      "   } else {",
      "      for (int i = 0; i < 10; i++) {", // K
      "         vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "         vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "         if (a.w > 0.0) vis *= capShadow(ro, rd, a.xyz, b.xyz, a.w, k, tmax);",
      "      }",
      "   }",
      // the quad's far end and sides fade out (a shadow wider than the quad ends softly, not on a straight edge)
      "   float edge = smoothstep(0.0, 0.1, qc.y) * smoothstep(1.0, 0.9, qc.y) * smoothstep(1.0, 0.85, qc.x);",
      // a static light's shadow ends at the first wall past the caster (sunShadowStaticLights: its light is not behind it)
      "   edge *= 1.0 - smoothstep(0.04, 0.12, wallBeyond(P.xy, lim));",
      "   vis = mix(1.0, vis, edge);",
      "   float m = 1.0 - mapC.w * share * alpha * (1.0 - vis);",
      "   if (mapC.z > 0.5) m = mix(0.5, 1.0, vis);",
      "   if (m > 0.996) discard;",
      "   fragColor = vec4(vec3(m), 1.0);",
      "}");

   /** Tests (CapsuleShadowShaderTest): the casters x lights program's vertex and fragment sources as linked. */
   static String[] lightProgramSources() {
      return new String[] {LIGHT_VERT, withCapsule(LIGHT_FRAG)};
   }

   /** The shared GLSL goes in where a source says "#include capsule". */
   private static String withCapsule(String src) {
      return src.replace("#include capsule", CAPSULE_GLSL);
   }
}
