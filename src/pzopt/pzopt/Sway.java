package pzopt;

import java.nio.IntBuffer;
import java.util.HashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;

/**
 * Foliage sway (Config key {@code foliageSway}, Enhancements tab, live): grass, bushes and trees bend and sway in the
 * wind while they stay baked into the chunk textures.
 *
 * <p>Stock B42 can only sway foliage by drawing every plant every frame (the "Wind sprite effects" display option:
 * trees and wind-moved sprites leave the chunk textures and are drawn per frame with their top corners sheared). This
 * keeps them baked and moves them where the chunk textures are composited, a pass that runs every frame anyway:
 *
 * <ol>
 *   <li><b>Bake</b>: while a chunk texture bakes, every foliage draw also writes a sway attribute into a second colour
 *       attachment of the chunk FBO (RG8, allocated only for textures that bake foliage): how far this texel moves at
 *       full bend (its height in the plant, the plant's size and stiffness: a pivot / weight map as in Pivot Painter),
 *       the plant's class (grass, bush, tree: its natural frequency), a per-plant phase, and four bits of the texel's
 *       own depth. The tile programs of the bake (tileWithDepth, opaqueWithDepth, seamFix2, CutawayAttached) are
 *       patched to write it (0 for anything rigid, so a fence post drawn over a bush later clears it); the draw
 *       buffers are switched to both attachments only for those programs, so everything else leaves it alone, and
 *       the depth bits catch a texel some other program covered later.</li>
 *   <li><b>Wind</b> (game thread, per frame): the climate's wind intensity and direction drive a lean downwind, a gust
 *       field advected over the world (a periodic value noise: gust fronts roll across fields and crowns, Ghost of
 *       Tsushima style), and each class's oscillation around the lean (Crysis-style main bending, the per-plant phase
 *       from the bake, leaf flutter on bushes and trees).</li>
 *   <li><b>Composite</b>: the chunk composite shader (whatever program draws the chunk textures: stock, pixelLight's,
 *       the sprite filter's variants) looks its texel up through the inverse of the displacement field: a fixed-point
 *       iteration {@code s = p - D(s)} from the pixel itself (inside a plant) and, for pixels a plant moves over, a
 *       few probes upwind accepted only when the probed texel lands on the pixel (the leading edge). The colour and
 *       the depth then come from that texel, so characters still sort against the moved plant. No extra pass, no
 *       per-frame draw of any plant.</li>
 * </ol>
 */
public final class Sway {
   private Sway() {
   }

   // ------------------------------------------------------------------------------------------------ switches

   /** Game thread: the feature is on this frame (live key, the shaders patched, the dev alternation's on half). */
   public static volatile boolean frameOn;
   private static boolean lastWanted;
   private static long toggleT0;
   private static boolean devOff;

   private static boolean supported() {
      return Overrides.enabled() && tilePatched && compositePatched && !System.getProperty("os.name", "").contains("OS X");
   }

   /** The key as the player set it, or the game's wind option handed over to sway (and the shaders are patched). */
   public static boolean wanted() {
      return (Config.FOLIAGE_SWAY || windHandoff) && supported();
   }

   // ------------------------------------------------------------------------------------------------ the game's wind option

   /** The game's "Wind sprite effects" option is on and sway draws it this frame (Config.WIND_SPRITE_SWAY). */
   private static boolean windHandoff;

   /**
    * Game thread, at the top of the world render (FBORenderCell.renderInternal); issue #41. With the game's own "Wind
    * sprite effects" option on, stock takes every tree and every wind-moved sprite out of the chunk textures and draws
    * each one per frame with its top corners sheared: in a forest that undoes the tree bake and costs more than the rest
    * of the frame (flip, max zoom: 244 -> 154 fps). With {@code windSpriteSway} the option reads off while the world
    * renders, so every stock and pzopt decision (the render layer, the bake, the tree pass, the object highlight, the
    * effect applied to a sprite) takes its option-off path and the plants stay baked, and sway moves them instead.
    * Outside the world render (the options screen, options.ini, Lua, the wind effects' own update) the option keeps the
    * player's value. Returns whether the option was switched; pass it to {@link #windHandoffEnd}.
    */
   public static boolean windHandoffBegin() {
      Core core = Core.getInstance();
      boolean wind = core.getOptionDoWindSpriteEffects();
      windHandoff = wind && Config.WIND_SPRITE_SWAY && supported();
      if (windHandoff) {
         core.setOptionDoWindSpriteEffects(false);
      }
      return windHandoff;
   }

   /** Game thread, the end of the world render: the player's value back. */
   public static void windHandoffEnd(boolean switched) {
      if (switched) {
         Core.getInstance().setOptionDoWindSpriteEffects(true);
      }
   }

   // ------------------------------------------------------------------------------------------------ bake (game thread)

   // the foliage object being baked (FBORenderCell.renderMinusFloor), read by the StartShader hook below
   private static boolean objOn;
   private static float objAmp; // 0..1 of AMP_MAX_PX at the top of the plant
   private static float objPhase; // 0..15
   private static float objClass; // 0 grass, 1 bush, 3 tree
   private static float objGround; // the plant's ground row in the chunk texture's logical space
   private static float objHeight; // logical px from the ground row to the top of the plant

   /** Logical px a texel moves at most (weight 1, full bend): the aux weight's scale. */
   static final float AMP_MAX_PX = 32F;
   static final int CLASS_GRASS = 0, CLASS_BUSH = 1, CLASS_TREE = 3;
   /** texd.c tag of a StartShader that draws foliage (read on the render thread). */
   public static final int TAG = 0x53574159;

   public static long foliageDraws, rigidDraws, bakesWithFoliage, auxCreated, auxFreed, auxCleared, treeQuads;

   /**
    * Game thread, FBORenderCell.renderMinusFloor right before {@code object.render}: is this a plant that sways, baked
    * into a chunk texture? Sets the object's sway parameters for the StartShader hook until {@link #end()}.
    */
   public static boolean begin(IsoObject object, boolean translucentOnly) {
      if (!frameOn || translucentOnly || object == null || object instanceof IsoTree) {
         return false;
      }
      IsoSprite sprite = object.getSprite();
      if (sprite == null || !(sprite.moveWithWind || sprite.isBush)) {
         return false;
      }
      FBORenderChunk rc = FBORenderChunkManager.instance.renderChunk;
      IsoGridSquare sq = object.getSquare();
      if (rc == null || sq == null || rc.chunk == null) {
         return false;
      }
      int ts = Core.tileScale;
      // stock's wind types: 1 sways the most (grass), 3 the least (stiff plants)
      float stiff = sprite.windType == 3 ? 0.35F : sprite.windType == 2 ? 0.65F : 1F;
      boolean bush = sprite.isBush;
      objClass = bush ? CLASS_BUSH : CLASS_GRASS;
      objAmp = Math.min(1F, (bush ? 16F : 18F) * ts / 2F * stiff / AMP_MAX_PX);
      objPhase = phaseOf(sq.x, sq.y, object.getObjectIndex());
      // the ground row: the square's south corner in the texture's logical space (TreeBake.spriteRect's rule)
      float xRel = sq.x - rc.chunk.wx * 8;
      float yRel = sq.y - rc.chunk.wy * 8;
      float yoff = FBORenderChunkManager.instance.getYOffset();
      objGround = (xRel + yRel) * (16 * ts) - sq.z * (96 * ts) + yoff + 32 * ts;
      // the plant's height: the sprite frame (its south corner sits on the ground row) less its transparent top
      Texture tex = sprite.getTextureForCurrentFrame(object.getDir(), object);
      float h = 0F;
      if (tex != null) {
         h = (tex.getHeightOrig() - tex.getOffsetY()) * (ts == 2 && tex.getHeightOrig() <= 128 && tex.getWidthOrig() <= 64 ? 2F : 1F);
      }
      objHeight = h > 4F ? h : 48F * ts;
      objOn = true;
      float cx = (xRel - yRel) * (32 * ts) + FBORenderChunkManager.instance.getXOffset();
      // the sprite's visible (trimmed) columns, dilated by how far this plant's top can move
      float sc = tex != null && ts == 2 && tex.getWidthOrig() <= 64 ? 2F : 1F;
      float x0 = tex != null ? cx - tex.getWidthOrig() * sc / 2F + tex.getOffsetX() * sc : cx - 32F * ts;
      float x1 = tex != null ? x0 + tex.getWidth() * sc : cx + 32F * ts;
      float r = objAmp * AMP_MAX_PX * strengthNow() * 1.3F + 2F;
      markRect(rc, x0 - r, objGround - objHeight - 2F, x1 + r, objGround + 2F);
      return true;
   }

   // ---- the tile mask: which 16 x 16 texel tiles of a chunk texture a plant can reach (built while the bake populates)

   static final int MASK_TILE = 16;

   /** A bake's tile marks, carried to the render thread on its FBORenderChunkEnd draw. */
   public static final class MaskHolder extends TextureDraw.GenericDrawer {
      byte[] bits;
      int mw, mh;

      @Override
      public void render() {
      }
   }

   private static FBORenderChunk maskRc;
   private static MaskHolder maskCur;

   /** The strength the mask is dilated for: at least 100 % (a later raise of the key re-bakes nothing). */
   static float strengthNow() {
      return Math.max(1F, Config.FOLIAGE_SWAY_PCT / 100F * Config.DEV_SWAY_GAIN_PCT / 100F);
   }

   /** Marks the tiles under a logical-space rectangle of render chunk {@code rc}'s texture (game or render thread). */
   static void markRect(FBORenderChunk rc, float lx0, float ly0, float lx1, float ly1) {
      if (maskRc != rc || maskCur == null) {
         maskRc = rc;
         maskCur = new MaskHolder();
         maskCur.mw = Math.max(1, (rc.w + MASK_TILE - 1) / MASK_TILE);
         maskCur.mh = Math.max(1, (rc.h + MASK_TILE - 1) / MASK_TILE);
         maskCur.bits = new byte[maskCur.mw * maskCur.mh];
      }
      mark(maskCur.bits, maskCur.mw, maskCur.mh, rc.w, rc.highRes, lx0, ly0, lx1, ly1);
   }

   static void mark(byte[] bits, int mw, int mh, int texW, boolean highRes, float lx0, float ly0, float lx1, float ly1) {
      float s = highRes ? 2F : 1F;
      float o = highRes ? texW / 4F : 0F; // a high-res texture's logical x starts at a quarter of its width
      int tx0 = Math.max(0, (int)Math.floor((lx0 - o) * s / MASK_TILE));
      int tx1 = Math.min(mw - 1, (int)Math.floor((lx1 - o) * s / MASK_TILE));
      int ty0 = Math.max(0, (int)Math.floor(ly0 * s / MASK_TILE));
      int ty1 = Math.min(mh - 1, (int)Math.floor(ly1 * s / MASK_TILE));
      for (int y = ty0; y <= ty1; y++) {
         for (int x = tx0; x <= tx1; x++) {
            bits[y * mw + x] = (byte)255;
         }
      }
   }

   /** Game thread, TextureDraw.FBORenderChunkEnd (populate): this bake's tile marks, or null. */
   public static TextureDraw.GenericDrawer takeMask() {
      MaskHolder m = maskCur;
      FBORenderChunk rc = FBORenderChunkManager.instance.renderChunk;
      maskCur = null;
      maskRc = null;
      return m != null && rc != null ? m : null;
   }

   public static void end() {
      objOn = false;
      SpriteRenderer.instance.drawGeneric(RESET); // the plant's uniforms off the program: a sprite drawn without its own shader start inherits them
   }

   /** Render thread: the bound program's sway uniform back to "rigid" (after a plant's draws). */
   private static final TextureDraw.GenericDrawer RESET = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         int prog = Ssr.boundProgram();
         if (prog <= 0 || !isPatchedProgram(prog)) {
            return;
         }
         Integer l = RESET_LOC.get(prog);
         if (l == null) {
            RESET_LOC.put(prog, l = GL20.glGetUniformLocation(prog, "pzSwObj"));
         }
         if (l >= 0) {
            GL20.glUniform4f(l, 0F, 0F, 0F, 2F);
         }
      }
   };
   private static final HashMap<Integer, Integer> RESET_LOC = new HashMap<>();

   public static float phaseOf(int x, int y, int k) {
      int h = x * 73856093 ^ y * 19349663 ^ k * 83492791;
      h ^= h >>> 13;
      h *= 0x5bd1e995;
      h ^= h >>> 15;
      return (h & 15);
   }

   // program ids of the patched bake programs, by the shader's name (resolved lazily on the game thread)
   private static final HashMap<Integer, Integer> OBJ_LOC = new HashMap<>(); // program -> pzSwObj location
   private static final HashMap<Integer, Integer> OBJ2_LOC = new HashMap<>();
   private static int lastProg = -1, lastL1 = -1, lastL2 = -1;

   /** Game thread: programs whose sway uniform says "rigid" (a plant's draw dirties it; the next rigid draw sets it once). */
   private static final java.util.HashSet<Integer> CLEAN = new java.util.HashSet<>();

   private static int objLoc(Shader shader, HashMap<Integer, Integer> map, String name) {
      int id = shader.getID();
      Integer l = map.get(id);
      if (l == null) {
         ShaderProgram p = shader.getProgram();
         ShaderProgram.Uniform u = p == null ? null : p.getUniform(name, 35666, false); // GL_FLOAT_VEC4
         l = u == null ? -1 : u.loc;
         map.put(id, l);
      }
      return l;
   }

   /**
    * Game thread, TextureDraw.StartShader: a patched bake program gets its sway parameters for this draw (the object's
    * while a plant bakes, zero otherwise), and the draw is tagged so the render thread knows it writes foliage.
    */
   public static ShaderUniformSetter startShader(TextureDraw texd, int program, ShaderUniformSetter uniforms) {
      texd.c = 0;
      if (!frameOn) {
         return uniforms;
      }
      int l1, l2;
      if (program == lastProg) {
         l1 = lastL1; // (consecutive sprite starts are nearly always the same program: no lookups)
         l2 = lastL2;
      } else {
         Shader shader = Shader.ShaderMap.get(program);
         boolean patched = shader != null && isPatchedShader(shader);
         l1 = patched ? objLoc(shader, OBJ_LOC, "pzSwObj") : -1;
         l2 = patched ? objLoc(shader, OBJ2_LOC, "pzSwObj2") : -1;
         lastProg = program;
         lastL1 = l1;
         lastL2 = l2;
      }
      if (l1 < 0 || l2 < 0) {
         return uniforms;
      }
      ShaderUniformSetter a;
      ShaderUniformSetter b;
      if (!objOn && CLEAN.contains(program)) {
         return uniforms; // the program's sway uniform already says "rigid"
      }
      if (objOn) {
         CLEAN.remove(program);
         FBORenderChunk rc = FBORenderChunkManager.instance.renderChunk;
         float texScale = rc != null && rc.highRes ? 2F : 1F;
         a = ShaderUniformSetter.uniform4f(l1, objAmp, objPhase, objClass, 1F);
         b = ShaderUniformSetter.uniform4f(l2, objGround, objHeight, texScale, Config.DEV_SWAY_FLIP_Y ? (rc != null ? rc.h : 0F) : 0F);
         texd.c = TAG;
         foliageDraws++;
      } else {
         CLEAN.add(program);
         a = ShaderUniformSetter.uniform4f(l1, 0F, 0F, 0F, 2F); // rigid: its depth's lowest bit cleared
         b = null;
         rigidDraws++;
      }
      if (b != null) {
         a.setNext(b);
      }
      if (uniforms == null) {
         return a;
      }
      ShaderUniformSetter tail = uniforms;
      while (tail.pzoptNext() != null) {
         tail = tail.pzoptNext();
      }
      tail.setNext(a);
      return uniforms;
   }

   private static boolean isPatchedShader(Shader shader) {
      String n = shader.getName();
      if (n == null) {
         return false;
      }
      Boolean b = PATCHED_TILE.get(n);
      return b != null && b;
   }

   // ------------------------------------------------------------------------------------------------ wind (game thread)

   static final class Frame extends TextureDraw.GenericDrawer {
      boolean on;
      final Ssr.View view = new Ssr.View();
      float amp, lean, dirX, osc;
      final float[] ph = new float[4];
      float gustU, gustV, gustFreq, flutter;
      float probe1, probe2, probe3;
      float zoom = 1F;
      float pLean, pOsc, pGustU, pGustV; // the previous frame's wind (the sway's motion vectors for DLSS)
      int nPush;
      final float[] push = new float[MAX_PUSH * 4]; // u, v (modulo the gust period), radius (squares), strength
      final float[] pPh = new float[4];
      int taps;
      int serial;

      @Override
      public void render() {
         renderFrame = this;
         frameSerial = this.serial;
         mvFrame = false;
         mvWanted = this.on && mvShape();
         evict();
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static int serialCounter;
   private static long lastNs;
   private static double t; // seconds of swaying (stops while paused)
   private static final double[] PHASE = new double[4];
   private static double gustU, gustV;
   static final float GUST_PERIOD_SQ = 256F; // the gust field repeats every 256 squares (the shader's lattice: 32 cells of 8)
   static final float GUST_CELL_SQ = 8F;
   private static float windSmooth = -1F;
   private static int invalidateFrames;

   // ---- characters bend the plants they walk through (swayPush): pushers in world (u, v), eased in and out

   private static final java.util.concurrent.ConcurrentLinkedQueue<float[]> MOVED = new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static final java.util.IdentityHashMap<Object, float[]> PUSHERS = new java.util.IdentityHashMap<>(); // x, y, z, strength, target, lastSeenS
   public static long pushQueued;
   static final int MAX_PUSH = 8;

   /**
    * IsoMovingObject.getGlobalMovementMod (every moving character, every tick; possibly a worker thread): a character within
    * view standing in grass or a bush is a pusher this tick. True when the stock rustle (per-frame draw + re-bakes) is replaced.
    */
   public static boolean moved(zombie.iso.IsoMovingObject o) {
      if (!frameOn || !Config.SWAY_PUSH || o == null) {
         return false;
      }
      IsoGridSquare sq = o.getCurrentSquare();
      if (sq == null) {
         return true;
      }
      float dx = o.getX() - IsoCamera.frameState.camCharacterX, dy = o.getY() - IsoCamera.frameState.camCharacterY;
      if (dx * dx + dy * dy > 60F * 60F) {
         return true;
      }
      if (pushQueuedFrame >= 64) {
         return true; // a horde: the nearest dozens are plenty (the queue is drained once a frame)
      }
      boolean veg = sq.getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.canBeRemoved) || sq.hasBush();
      if (veg) {
         pushQueuedFrame++;
         MOVED.add(new float[] {System.identityHashCode(o), o.getX(), o.getY(), o.getZ(), o instanceof zombie.vehicles.BaseVehicle ? 2.2F : 0.9F});
         pushQueued++;
      }
      return true;
   }

   private static final java.util.HashMap<Integer, float[]> PUSH_BY_ID = new java.util.HashMap<>();

   private static double orbitT;

   private static volatile int pushQueuedFrame;

   /** Game thread, once a frame: the queued moves into the pushers, eased; the strongest near the camera go to the frame. */
   private static void updatePushers(Frame f, double dt) {
      if (Config.DEV_SWAY_PUSH_ORBIT > 0F) {
         // dev: a pusher circling the camera's character (the push's look without anyone walking through plants)
         orbitT += dt;
         float r = Config.DEV_SWAY_PUSH_ORBIT;
         MOVED.add(new float[] {-7, IsoCamera.frameState.camCharacterX + r * (float)Math.cos(orbitT * 0.8), IsoCamera.frameState.camCharacterY + r * (float)Math.sin(orbitT * 0.8),
            IsoCamera.frameState.camCharacterZ, 1.2F});
      }
      float[] m;
      pushQueuedFrame = 0;
      while ((m = MOVED.poll()) != null) {
         int id = (int)m[0];
         float[] p = PUSH_BY_ID.get(id);
         if (p == null) {
            if (PUSH_BY_ID.size() > 64) {
               continue;
            }
            p = new float[7];
            PUSH_BY_ID.put(id, p);
         }
         p[0] = m[1];
         p[1] = m[2];
         p[2] = m[3];
         p[4] = 1F; // target
         p[5] = 0F; // seconds since seen
         p[6] = m[4]; // radius (squares)
      }
      java.util.Iterator<java.util.Map.Entry<Integer, float[]>> it = PUSH_BY_ID.entrySet().iterator();
      while (it.hasNext()) {
         float[] p = it.next().getValue();
         p[5] += (float)dt;
         if (p[5] > 0.15F) {
            p[4] = 0F; // not moving through plants any more: they spring back
         }
         float k = p[4] > p[3] ? (float)Math.min(1.0, dt / 0.2) : (float)Math.min(1.0, dt / 0.6);
         p[3] += (p[4] - p[3]) * k;
         if (p[4] == 0F && p[3] < 0.02F) {
            it.remove();
         }
      }
      f.nPush = 0;
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      // the nearest to the camera first (at most MAX_PUSH)
      for (int n = 0; n < MAX_PUSH; n++) {
         float[] best = null;
         float bd = Float.MAX_VALUE;
         for (float[] p : PUSH_BY_ID.values()) {
            if (p[3] < 0.02F || p[6] < 0F) {
               continue;
            }
            float d = (p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy);
            if (d < bd) {
               bd = d;
               best = p;
            }
         }
         if (best == null) {
            break;
         }
         int i = f.nPush++;
         float u = best[0] - best[1], v = best[0] + best[1] - 6F * best[2];
         f.push[i * 4] = (float)(u - GUST_PERIOD_SQ * Math.floor(u / GUST_PERIOD_SQ));
         f.push[i * 4 + 1] = (float)(v - GUST_PERIOD_SQ * Math.floor(v / GUST_PERIOD_SQ));
         f.push[i * 4 + 2] = best[6];
         f.push[i * 4 + 3] = best[3];
         best[6] = -best[6]; // taken this frame
      }
      for (float[] p : PUSH_BY_ID.values()) {
         p[6] = Math.abs(p[6]);
      }
   }

   /** Game thread, FBORenderCell right before the chunk composite. */
   public static void beforeComposite(int playerIndex) {
      boolean wanted = wanted();
      if (wanted != lastWanted) {
         lastWanted = wanted;
         if (wanted) {
            invalidateFrames = 2; // every chunk texture re-bakes with its sway attributes (bakes spread by the budget)
            Log.info("foliage sway: on (tile programs " + PATCHED_TILE + ", composite programs patched " + compositePatchedNames + ")");
         } else {
            Log.info("foliage sway: off");
            CLEAN.clear(); // (the next time it is on, every program gets its "rigid" uniform again)
            SpriteRenderer.instance.drawGeneric(new GlTask(Sway::resetTilePrograms));
         }
      }
      if (invalidateFrames > 0 && --invalidateFrames == 0) {
         invalidateAll(playerIndex);
      }
      reBakeEvicted();
      boolean on = wanted;
      if (on && Config.DEV_SWAY_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
            Log.info("foliage sway: alternating every " + Config.DEV_SWAY_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         devOff = (now - toggleT0) / Config.DEV_SWAY_ALTERNATE % 2L == 1L;
      }
      frameOn = on && !(devOff && Config.DEV_SWAY_ALTERNATE_ALL); // the bake keeps writing the attributes in the dev off half unless ...All
      long nowNs = System.nanoTime();
      double dt = lastNs == 0L ? 0.0 : Math.min(0.1, (nowNs - lastNs) / 1e9);
      lastNs = nowNs;
      if (!compositePatched) {
         return;
      }
      Frame f = FRAMES[frameIndex++ & 3];
      f.on = on && !devOff;
      f.serial = ++serialCounter;
      if (f.on) {
         boolean paused = zombie.GameTime.isGamePaused();
         float speed = paused ? 0F : Math.min(4F, Math.max(0F, zombie.GameTime.getInstance().getTrueMultiplier()));
         dt *= speed;
         ClimateManager cm = ClimateManager.getInstance();
         float windRaw = cm == null ? 0.2F : cm.getWindIntensity();
         if (Config.DEV_SWAY_WIND >= 0) {
            windRaw = Config.DEV_SWAY_WIND / 100F;
         }
         // the climate's wind moves in minutes; smoothed anyway so an override or a new weather period eases in
         windSmooth = windSmooth < 0F ? windRaw : windSmooth + (windRaw - windSmooth) * (float)Math.min(1.0, dt * 0.5);
         float wind = Math.max(0F, Math.min(1F, windSmooth));
         float angle = cm == null ? 1F : cm.getWindAngleIntensity();
         float dir = angle < 0F ? -1F : 1F; // stock's sprite wind and the rain's slant: the sign of the angle intensity
         float strength = Config.FOLIAGE_SWAY_PCT / 100F * Config.DEV_SWAY_GAIN_PCT / 100F;
         // a breath of air even on a calm day; the lean grows with the wind, the oscillation too but less
         float w = 0.08F + 0.92F * wind;
         f.amp = strength;
         f.lean = (float)Math.pow(w, 1.3) * 0.55F;
         f.osc = 0.12F + 0.38F * w;
         f.dirX = dir;
         // natural frequencies (Hz): grass ~1.3, bushes ~0.8, trees ~0.35; a little faster in strong wind
         double[] hz = {1.3, 0.8, 0.8, 0.35};
         Frame pf = gameFrame;
         for (int i = 0; i < 4; i++) {
            f.pPh[i] = pf != null && pf.on ? pf.ph[i] : (float)PHASE[i];
            PHASE[i] = (PHASE[i] + dt * 2.0 * Math.PI * hz[i] * (0.8 + 0.5 * wind)) % (2.0 * Math.PI);
            f.ph[i] = (float)PHASE[i];
         }
         f.pLean = pf != null && pf.on ? pf.lean : (float)Math.pow(w, 1.3) * 0.55F;
         f.pOsc = pf != null && pf.on ? pf.osc : 0.12F + 0.38F * w;
         f.pGustU = pf != null && pf.on ? pf.gustU : (float)gustU;
         f.pGustV = pf != null && pf.on ? pf.gustV : (float)gustV;
         // gust fronts drift downwind (screen x ~ u = x - y) at 1.5 .. 9 squares a second
         double gustSpeed = 1.5 + 7.5 * wind;
         double cells = GUST_PERIOD_SQ / GUST_CELL_SQ;
         gustU = (gustU - dir * dt * gustSpeed / GUST_CELL_SQ) % cells;
         gustV = (gustV - dt * 0.35 * gustSpeed / GUST_CELL_SQ) % cells;
         if (gustU < 0) gustU += cells;
         if (gustV < 0) gustV += cells;
         f.gustU = (float)gustU;
         f.gustV = (float)gustV;
         f.gustFreq = 1F / GUST_CELL_SQ;
         f.flutter = 0.06F + 0.1F * wind;
         f.taps = Math.max(1, Math.min(4, Config.FOLIAGE_SWAY_TAPS));
         // upwind probes (logical px): where a plant's texel that lands on this pixel can come from
         float reach = AMP_MAX_PX * 0.5F * strength * (f.lean + f.osc);
         f.probe1 = reach * 0.25F;
         f.probe2 = reach * 0.55F;
         f.probe3 = reach;
         f.view.capture(playerIndex);
         f.zoom = f.view.zoom;
         if (Config.SWAY_PUSH) {
            updatePushers(f, dt);
         }
         t += dt;
      }
      gameFrame = f;
      SpriteRenderer.instance.drawGeneric(f);
   }

   // the last frame's wind on the game thread, for the plants the game draws per frame (shearTop)
   private static Frame gameFrame;

   private static float hash(float ix, float iy) {
      ix = ix - 32F * (float)Math.floor(ix / 32F);
      iy = iy - 32F * (float)Math.floor(iy / 32F);
      double v = Math.sin(ix * 127.1 + iy * 311.7) * 43758.5453;
      return (float)(v - Math.floor(v));
   }

   private static float noise(float px, float py) {
      float ix = (float)Math.floor(px), iy = (float)Math.floor(py);
      float fx = px - ix, fy = py - iy;
      float a = hash(ix, iy), b = hash(ix + 1F, iy), c = hash(ix, iy + 1F), d = hash(ix + 1F, iy + 1F);
      return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
   }

   /**
    * Game thread: the horizontal displacement (logical px, + = screen right) of the top of a plant of class {@code cls}
    * with amplitude {@code amp} (0..1 of AMP_MAX_PX) and phase {@code phase} (0..15) at world square (x, y, z): the
    * composite's pzSwD without the per-texel flutter, for a plant the game draws per frame (a faded tree near the player).
    */
   public static float shearTop(float x, float y, float z, float amp, float phase, int cls) {
      Frame f = gameFrame;
      if (f == null || !f.on) {
         return 0F;
      }
      float u = (float)Math.floorMod((int)Math.floor(x - y), (int)GUST_PERIOD_SQ) + (float)((x - y) - Math.floor(x - y));
      float v = (float)Math.floorMod((int)Math.floor(x + y - 6F * z), (int)GUST_PERIOD_SQ) + (float)((x + y - 6F * z) - Math.floor(x + y - 6F * z));
      float g = "sines".equals(Config.SWAY_GUST)
         ? 0.5F + 0.25F * (float)(Math.sin((u * 0.625 + v * 0.21875) * 0.78539816 + f.gustU * 0.78539816) + Math.sin((u * -0.28125 + v * 0.71875) * 0.78539816 + f.gustV * 0.78539816))
         : noise(u * f.gustFreq + f.gustU, v * f.gustFreq + f.gustV);
      float ph = f.ph[Math.max(0, Math.min(3, cls))] + phase / 16F * 6.2831853F;
      float sw = f.lean * (0.3F + 0.7F * g) + f.osc * (0.45F + 0.55F * g) * (float)Math.sin(ph + g * 2.5F);
      return amp * AMP_MAX_PX * f.amp * sw * f.dirX;
   }

   /** Game thread: a GPU section's name split by the dev alternation (devSwayAlternate: ".swon" / ".swoff"). */
   public static String section(String name) {
      if (Config.DEV_SWAY_ALTERNATE <= 0) {
         return name;
      }
      Frame f = gameFrame;
      return f != null && f.on ? name + ".swon" : name + ".swoff";
   }

   private static void invalidateAll(int playerIndex) {
      try {
         zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.getCell();
         if (cell == null) {
            return;
         }
         zombie.iso.IsoChunkMap cm = cell.getChunkMap(playerIndex);
         int n = 0;
         for (int y = 0; y < zombie.iso.IsoChunkMap.chunkGridWidth; y++) {
            for (int x = 0; x < zombie.iso.IsoChunkMap.chunkGridWidth; x++) {
               zombie.iso.IsoChunk c = cm.getChunk(x, y);
               if (c != null && c.minLevel <= 0 && c.maxLevel >= 0) {
                  c.getRenderLevels(playerIndex).invalidateLevel(0, FBORenderChunk.DIRTY_REDRAW);
                  n++;
               }
            }
         }
         Log.info("foliage sway: " + n + " ground chunk textures re-bake with their sway attributes");
      } catch (Throwable e) {
         Log.warn("foliage sway: invalidation failed: " + e);
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread: aux

   static final class Aux {
      int tex;
      int w, h;
      int fbo;
      int depthId;
      int index;
      boolean any; // a foliage draw wrote it since its last clear
      boolean pendingClear;
      int maskTex;
      long auxHandle, maskHandle; // swayBindless: resident handles (0: none yet)
      int mw, mh;
      byte[] mask;
      boolean maskDirty;
      boolean highRes;
      long lastDrawNs = System.nanoTime(); // the last composite draw of this texture (the budget's LRU)
      // texture space -> world: u = x - y = mu.x * s + mu.y, v = x + y - 6z = mv.x * t + mv.y (modulo the gust period), and
      // uv per logical px (the composite's displacement is in the chunk texture's own space: no viewport read back)
      float muA, muB, mvA, mvB, uvX, uvY;
   }

   /** Render thread: the texture-space mapping of render chunk {@code rc}'s texture (its chunk, levels and zoom regime). */
   private static void mapping(Aux a, FBORenderChunk rc) {
      if (rc.chunk == null) {
         return;
      }
      int ts = Core.tileScale;
      float s = rc.highRes ? 2F : 1F;
      float goX = rc.w / 2F; // FBORenderChunkManager.beginRenderChunkLevel's xoff / yoff for this texture
      float yoff = (rc.getTopLevel() - rc.getMinLevel() + 1) * FBORenderChunk.PIXELS_PER_LEVEL + rc.getMinLevel() * FBORenderChunk.PIXELS_PER_LEVEL
         + zombie.iso.fboRenderChunk.FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel());
      float ox = rc.highRes ? a.w / 4F : 0F;
      // logical x = s_tex * w / s + ox; u = (lx - goX) / (32 ts) + 8 (wx - wy)
      float cu = (float)Math.floorMod((rc.chunk.wx - rc.chunk.wy) * 8, (int)GUST_PERIOD_SQ);
      float cv = (float)Math.floorMod((rc.chunk.wx + rc.chunk.wy) * 8, (int)GUST_PERIOD_SQ);
      a.muA = a.w / s / (32F * ts);
      a.muB = (ox - goX) / (32F * ts) + cu;
      a.mvA = a.h / s / (16F * ts);
      a.mvB = -yoff / (16F * ts) + cv;
      a.uvX = s / a.w;
      a.uvY = s / a.h;
   }

   private static final HashMap<Integer, Aux> BY_INDEX = new HashMap<>();
   private static final HashMap<Integer, Aux> BY_DEPTH = new HashMap<>();
   private static FBORenderChunk cur;
   private static Aux curAux;
   public static boolean mrt; // render thread: both draw buffers are on (a patched program bakes into a texture with sway attributes)
   private static long auxBytes;
   private static final IntBuffer BUFS2 = BufferUtils.createIntBuffer(2).put(0, GL30.GL_COLOR_ATTACHMENT0).put(1, GL30.GL_COLOR_ATTACHMENT1);
   private static final IntBuffer BUFS1 = BufferUtils.createIntBuffer(1).put(0, GL30.GL_COLOR_ATTACHMENT0);
   static final int AUX_UNIT = 30, GUST_UNIT = 31, MASK_UNIT = 28; // 28: god rays' local-light pass unit, never bound in the composite
   private static int gustTex;

   /** Render thread: the gust field's 32 x 32 periodic value noise, made once and left bound on GUST_UNIT. */
   private static boolean gustTexture() {
      if (gustTex == 0) {
         java.nio.ByteBuffer b = BufferUtils.createByteBuffer(32 * 32);
         for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
               b.put((byte)Math.round(hash(x, y) * 255F));
            }
         }
         b.flip();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + GUST_UNIT);
         gustTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, gustTex);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 32, 32, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_TEXTURE_MAX_LEVEL, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      } else {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + GUST_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, gustTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      return true;
   }

   /** Render thread, TextureDraw FBORenderChunkStart (after the chunk's FBO is bound). */
   public static void chunkStart(boolean clear) {
      mrt = false;
      cur = FBORenderChunkManager.instance.renderThreadCurrent;
      curAux = null;
      if (cur == null || cur.fbo == null) {
         cur = null;
         return;
      }
      Aux a = BY_INDEX.get(cur.index);
      if (a != null && (a.fbo != cur.fbo.getBufferId() || cur.depth == null || a.depthId != cur.depth.getID())) {
         free(a); // the render chunk got new GL objects
         a = null;
      }
      if (a != null) {
         mapping(a, cur); // a pooled render chunk may hold another chunk now
      }
      if (a != null && clear) {
         a.pendingClear = true;
         a.any = false;
         java.util.Arrays.fill(a.mask, (byte)0);
         a.maskDirty = true;
      }
      curAux = a;
   }

   /** Render thread, TextureDraw FBORenderChunkEnd (before the chunk's FBO is unbound). */
   public static void chunkEnd(TextureDraw texd) {
      if (curAux != null && texd != null && texd.drawer instanceof MaskHolder m && m.bits != null && m.mw == curAux.mw && m.mh == curAux.mh) {
         for (int i = 0; i < m.bits.length; i++) {
            curAux.mask[i] |= m.bits[i];
         }
         curAux.maskDirty = true;
      }
      if (curAux != null && curAux.maskDirty && curAux.maskTex != 0) {
         java.nio.ByteBuffer b = maskBuffer(curAux.mask.length);
         b.put(curAux.mask).flip();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, curAux.maskTex);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, curAux.mw, curAux.mh, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
         GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         curAux.maskDirty = false;
      }
      if (mrt) {
         GL30.glColorMaski(1, true, true, true, true);
         GL20.glDrawBuffers(BUFS1);
         mrt = false;
         auxWrite = false;
      }
      if (curAux != null && curAux.any) {
         bakesWithFoliage++;
      } else if (curAux != null) {
         // this bake drew no plant: the texture's attributes go (its FBO is bound: deleting detaches them)
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0);
         free(curAux);
         auxReleasedEmpty++;
      }
      cur = null;
      curAux = null;
   }

   // ---- the attribute textures' VRAM: a soft budget over the textures the composite has not drawn for a while

   public static long auxReleasedEmpty, auxEvicted;
   private static final java.util.concurrent.ConcurrentLinkedQueue<Integer> EVICTED = new java.util.concurrent.ConcurrentLinkedQueue<>();

   /** Render thread, once a frame (the wind's Frame): over the budget, the longest-unseen attributes go (3 s unseen at least). */
   private static void evict() {
      long budget = (long)Math.max(16, Config.SWAY_AUX_BUDGET_MB) << 20;
      if (auxBytes <= budget || BY_INDEX.isEmpty()) {
         return;
      }
      long now = System.nanoTime();
      int n = 0;
      while (auxBytes > budget && n++ < 8) {
         Aux oldest = null;
         for (Aux a : BY_INDEX.values()) {
            if (now - a.lastDrawNs >= 3_000_000_000L && (oldest == null || a.lastDrawNs < oldest.lastDrawNs)) {
               oldest = a;
            }
         }
         if (oldest == null) {
            return; // everything in use: the budget is soft
         }
         int prev = zombie.core.textures.TextureFBO.getCurrentID();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldest.fbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
         EVICTED.add(oldest.index);
         free(oldest);
         auxEvicted++;
      }
   }

   /** Game thread: a texture whose attributes were evicted re-bakes when it is shown again (and gets them back). */
   private static void reBakeEvicted() {
      Integer idx;
      while ((idx = EVICTED.poll()) != null) {
         FBORenderChunk rc = FBORenderChunkManager.instance.chunks.get(idx);
         if (rc != null && rc.chunk != null && rc.getRenderLevels() != null) {
            rc.getRenderLevels().invalidateLevel(rc.getMinLevel(), FBORenderChunk.DIRTY_REDRAW);
         }
      }
   }

   /** Render thread, TextureDraw StartShader after the program is bound: both draw buffers for a patched bake program. */
   public static void onProgram(int program, TextureDraw texd) {
      if (cur == null) {
         if (mvWanted) {
            mvBind(isSwayComposite(program));
         }
         return;
      }
      boolean foliage = texd != null && texd.c == TAG;
      if (program != 0 && Config.FOLIAGE_SWAY && isPatchedProgram(program)) {
         if (foliage && curAux == null) {
            curAux = create(cur);
         }
         if (curAux != null) {
            auxOn();
            if (curAux.pendingClear) {
               clearAux();
            }
            if (foliage) {
               curAux.any = true;
            }
            return;
         }
      }
      auxOff();
   }

   /**
    * Render thread, a bake into a texture with plant attributes: both draw buffers stay on from the first patched draw to the
    * bake's end, and the attribute attachment is gated per draw by its colour mask (a glDrawBuffers per program switch made
    * the driver revalidate the chunk FBO each time: +13..19 us a frame while streaming).
    */
   private static void auxOn() {
      if (!mrt) {
         GL20.glDrawBuffers(BUFS2);
         mrt = true;
         auxWrite = true; // (a fresh glDrawBuffers leaves the masks as the game set them: all on)
         return;
      }
      if (!auxWrite) {
         GL30.glColorMaski(1, true, true, true, true);
         auxWrite = true;
      }
   }

   private static void auxOff() {
      if (mrt && auxWrite) {
         GL30.glColorMaski(1, false, false, false, false);
         auxWrite = false;
      }
   }

   /** Render thread, after a TextureDraw glColorMask (it sets every draw buffer's mask) while both draw buffers are on. */
   public static void afterColorMask() {
      if (mrt && !auxWrite) {
         GL30.glColorMaski(1, false, false, false, false);
      }
   }

   private static boolean auxWrite;

   /** Render thread, TextureDraw.run while both draw buffers are on: ops that bind a program of their own turn it off. */
   public static void beforeOp(TextureDraw texd) {
      switch (texd.type) {
         case DrawModel:
         case DrawSkyBox:
         case DrawWater:
         case DrawPuddles:
         case DrawParticles:
         case drawTerrain:
         case DrawQueued:
         case RenderQueued:
         case DrawImGui:
            // one draw buffer: these bind programs of their own and some set glColorMask directly (ChunkAo's in-bake AO
            // pass re-enabled attachment 1 and wrote over the attributes); a few per bake, unlike the program switches
            GL20.glDrawBuffers(BUFS1);
            GL30.glColorMaski(1, true, true, true, true);
            mrt = false;
            auxWrite = false;
            break;
         case ShaderUpdate:
            onProgram(texd.a, null);
            break;
         default:
            break;
      }
   }

   private static final float[] ZERO = {0F, 0F, 0F, 1F};

   // ---- DLSS: the sway's motion vectors, a second attachment of the world framebuffer while the composite draws

   static boolean mvFrame; // attached and cleared this frame
   private static boolean mvWanted; // this frame: sway on with DLSS
   private static int[] swayProgIds = new int[0];

   private static void addSwayProgram(int id) {
      swayProgIds = java.util.Arrays.copyOf(swayProgIds, swayProgIds.length + 1);
      swayProgIds[swayProgIds.length - 1] = id;
   }
   private static boolean mvMrt; // both draw buffers on
   private static int mvTex, mvW, mvH, mvFboId, mvAttachedTex;
   public static long mvFrames;

   private static boolean isSwayComposite(int program) {
      for (int id : swayProgIds) {
         if (id == program) {
            return true;
         }
      }
      return false;
   }

   /** Render thread, a composite program just bound: a sway program writes its motion into attachment 1. */
   static void mvBind(boolean swayProgram) {
      if (!swayProgram) {
         if (mvMrt && mvMaskOn) {
            GL30.glColorMaski(1, false, false, false, false); // (both draw buffers stay: a glDrawBuffers per draw revalidated the framebuffer, +450 us)
            mvMaskOn = false;
         }
         return;
      }
      if (!mvShape()) {
         return;
      }
      zombie.core.textures.TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || zombie.core.textures.TextureFBO.getCurrentID() != world.getBufferId()) {
         return; // not drawing into the world framebuffer (the combined FBO debug path): no motion
      }
      if (mvImage()) {
         // swayMvImage: the plant fragments store into an image; the world framebuffer keeps its one draw buffer
         if (!mvFrame) {
            int w = world.getWidth(), h = world.getHeight();
            if (mvTex == 0 || w != mvW || h != mvH || !mvTexImage) {
               if (mvTex != 0) {
                  GL11.glDeleteTextures(mvTex);
               }
               if (mvTileTex != 0) {
                  GL11.glDeleteTextures(mvTileTex);
               }
               // R32UI [epoch 10 | motion x 11 | motion y 11] per pixel, R16UI epoch per 16 x 16 tile (zeros: epoch 0 never matches)
               mvTex = integerTexture(w, h, GL30.GL_R32UI);
               mvTileTex = integerTexture((w + 15) / 16, (h + 15) / 16, GL30.GL_R16UI);
               mvW = w;
               mvH = h;
               mvTexImage = true;
               mvAttachedTex = 0;
            }
            mvEpoch = mvEpoch % 1023 + 1;
            if (mvEpoch % 512 == 1 && org.lwjgl.opengl.GL.getCapabilities().OpenGL44) {
               // both cleared every 512 frames: no texel is older than the epoch's 1023-frame cycle, a stale one never matches
               org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | org.lwjgl.opengl.GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
               org.lwjgl.opengl.GL44.glClearTexImage(mvTex, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (java.nio.IntBuffer)null);
               org.lwjgl.opengl.GL44.glClearTexImage(mvTileTex, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (java.nio.IntBuffer)null);
               mvClears++;
            }
            org.lwjgl.opengl.GL42.glBindImageTexture(MV_IMAGE_UNIT, mvTex, 0, false, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, GL30.GL_R32UI);
            org.lwjgl.opengl.GL42.glBindImageTexture(MV_TILE_UNIT, mvTileTex, 0, false, 0, org.lwjgl.opengl.GL15.GL_WRITE_ONLY, GL30.GL_R16UI);
            mvFrame = true;
            mvFrames++;
         }
         return;
      }
      if (!mvFrame) {
         int w = world.getWidth(), h = world.getHeight();
         if (mvTex == 0 || w != mvW || h != mvH || mvTexImage) {
            if (mvTex != 0) {
               GL11.glDeleteTextures(mvTex);
            }
            mvTexImage = false;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
            mvTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mvTex);
            // RG16F (RG8_SNORM is not colour-renderable on every driver: NVIDIA dropped the writes silently)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, w, h, 0, GL30.GL_RG, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);
            mvNeedsClear = true;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_TEXTURE_MAX_LEVEL, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            mvW = w;
            mvH = h;
         }
         if (mvAttachedTex != mvTex || mvFboId != world.getBufferId()) {
            // attached once and left there (re-attaching every frame made the driver revalidate the world framebuffer:
            // +530 us a frame); every other draw into it keeps one draw buffer
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, mvTex, 0);
            mvFboId = world.getBufferId();
            mvAttachedTex = mvTex;
         }
         GL20.glDrawBuffers(BUFS2);
         if (mvNeedsClear || !mvConsumed) {
            // (normally DLSS's resolve zeroes every texel it consumed: no full clear a frame)
            GL30.glColorMaski(1, true, true, true, true);
            GL30.glClearBufferfv(GL11.GL_COLOR, 1, MV_ZERO);
            mvClears++;
            mvNeedsClear = false;
         }
         mvConsumed = false;
         GL30.glColorMaski(1, true, true, false, false);
         mvMaskOn = true;
         mvBlendOff();
         mvMrt = true;
         mvFrame = true;
         mvFrames++;
         return;
      }
      if (mvMrt && !mvMaskOn) {
         GL30.glColorMaski(1, true, true, false, false);
         mvMaskOn = true;
      }
      if (mvMrt) {
         mvBlendOff();
      }
   }

   private static boolean mvMaskOn;
   private static boolean mvTexImage; // mvTex is the R32UI image (swayMvImage), not the RG16F attachment
   static final int MV_IMAGE_UNIT = 7, MV_TILE_UNIT = 3; // image units (reflections use 5 and 6)
   private static int mvTileTex;

   private static int integerTexture(int w, int h, int format) {
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
      int t = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
      org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, format, w, h);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return t;
   }

   /** Render thread, DLSS's resolve: the per-tile epochs beside {@link #motionTexture()} (swayMvImage). */
   public static int motionTiles() {
      return mvFrame && mvTexImage ? mvTileTex : 0;
   }
   private static int mvEpoch;

   /** Render thread, DLSS's resolve: the motion is the image with this frame's epoch in b (swayMvImage), not the attachment. */
   public static boolean motionIsImage() {
      return mvFrame && mvTexImage;
   }

   public static float motionEpoch() {
      return mvEpoch;
   }

   /**
    * The motion attachment written, never blended: the composite's glEnable(GL_BLEND) covers every draw buffer, which made
    * attachment 1 a read-modify-write of every sway fragment (swayMvNoBlend; the game may re-enable blending mid-pass, so
    * at every sway program bind).
    */
   private static void mvBlendOff() {
      if (Config.SWAY_MV_NO_BLEND) {
         GL30.glDisablei(GL11.GL_BLEND, 1);
      }
   }

   /** Attachment 1's blending back to what the game's blend state says (read from its render-thread cache). */
   private static void mvBlendRestore() {
      if (!Config.SWAY_MV_NO_BLEND) {
         return;
      }
      if (gameBlendOn()) {
         GL30.glEnablei(GL11.GL_BLEND, 1);
      }
   }

   private static java.lang.reflect.Field blendCurrent, blendValue;
   private static boolean blendReflectFailed;

   private static boolean gameBlendOn() {
      if (!blendReflectFailed) {
         try {
            if (blendCurrent == null) {
               blendCurrent = zombie.core.opengl.IOpenGLState.class.getDeclaredField("currentValue");
               blendCurrent.setAccessible(true);
               blendValue = zombie.core.opengl.GLState.CBooleanValue.class.getDeclaredField("value");
               blendValue.setAccessible(true);
            }
            return blendValue.getBoolean(blendCurrent.get(zombie.core.opengl.GLStateRenderThread.Blend));
         } catch (Throwable t) {
            blendReflectFailed = true;
            Log.warn("foliage sway: the game's blend state is not readable (" + t + "); attachment 1 blending restored as on");
         }
      }
      return true; // (the composite draws blended: on is what the game had)
   }

   private static final float[] MV_ZERO = {0F, 0F, 0F, 0F};

   /** Render thread, right after the chunk composite: one draw buffer again, the motion texture detached. */
   public static final TextureDraw.GenericDrawer AFTER_COMPOSITE = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         if (!mvFrame) {
            return;
         }
         if (mvMrt) {
            GL30.glColorMaski(1, true, true, true, true);
            mvBlendRestore();
            mvMaskOn = false;
            if (zombie.core.textures.TextureFBO.getCurrentID() == mvFboId) {
               GL20.glDrawBuffers(BUFS1);
            } else {
               int prev = zombie.core.textures.TextureFBO.getCurrentID();
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mvFboId);
               GL20.glDrawBuffers(BUFS1);
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            }
         }
         mvMrt = false;
      }
   };

   /** Render thread, DLSS's resolve: this frame's sway motion (world framebuffer pixels, x MV_RANGE), or 0. */
   public static int motionTexture() {
      return mvFrame ? mvTex : 0;
   }

   static final float MV_RANGE = 4F;
   private static boolean mvNeedsClear = true, mvConsumed;
   public static long mvClears;

   /** Render thread, DLSS's resolve: its add pass wrote zero back to every texel it consumed (no clear next frame). */
   public static void motionConsumed() {
      mvConsumed = true;
   }

   /** Game thread, FBORenderCell after the chunk composite. */
   public static void afterComposite() {
      if (frameOn && mvShape()) {
         SpriteRenderer.instance.drawGeneric(AFTER_COMPOSITE);
      }
   }
   private static java.nio.ByteBuffer maskBuf;

   private static java.nio.ByteBuffer maskBuffer(int n) {
      if (maskBuf == null || maskBuf.capacity() < n) {
         maskBuf = BufferUtils.createByteBuffer(Math.max(n, 16384));
      }
      maskBuf.clear();
      return maskBuf;
   }

   private static void clearAux() {
      if (!auxWrite) {
         GL30.glColorMaski(1, true, true, true, true);
      }
      GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
      if (!auxWrite) {
         GL30.glColorMaski(1, false, false, false, false);
      }
      curAux.pendingClear = false;
      auxCleared++;
   }

   private static Aux create(FBORenderChunk rc) {
      if (rc.tex == null || rc.depth == null || rc.fbo == null || zombie.core.textures.TextureFBO.getCurrentID() != rc.fbo.getBufferId()) {
         return null;
      }
      int w = rc.tex.getWidthHW();
      int h = rc.tex.getHeightHW();
      if (w <= 0 || h <= 0) {
         return null;
      }
      Aux a = new Aux();
      a.w = w;
      a.h = h;
      a.fbo = rc.fbo.getBufferId();
      a.depthId = rc.depth.getID();
      a.index = rc.index;
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
      a.tex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.tex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, w, h, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_TEXTURE_MAX_LEVEL, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, a.tex, 0);
      a.pendingClear = true;
      a.highRes = rc.highRes;
      mapping(a, rc);
      a.mw = Math.max(1, (w + MASK_TILE - 1) / MASK_TILE);
      a.mh = Math.max(1, (h + MASK_TILE - 1) / MASK_TILE);
      a.mask = new byte[a.mw * a.mh];
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
      a.maskTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.maskTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, a.mw, a.mh, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_TEXTURE_MAX_LEVEL, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      a.maskDirty = true;
      BY_INDEX.put(a.index, a);
      BY_DEPTH.put(a.depthId, a);
      auxCreated++;
      auxBytes += 2L * w * h;
      return a;
   }

   static final int GL12_CLAMP_TO_EDGE = 0x812F;
   static final int GL12_TEXTURE_MAX_LEVEL = 0x813D;

   private static void free(Aux a) {
      BY_INDEX.remove(a.index);
      if (BY_DEPTH.get(a.depthId) == a) {
         BY_DEPTH.remove(a.depthId);
      }
      if (a.auxHandle != 0L) {
         org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleNonResidentARB(a.auxHandle);
         a.auxHandle = 0L;
      }
      if (a.maskHandle != 0L) {
         org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleNonResidentARB(a.maskHandle);
         a.maskHandle = 0L;
      }
      if (a.tex != 0) {
         GL11.glDeleteTextures(a.tex);
         a.tex = 0;
         auxBytes -= 2L * a.w * a.h;
      }
      if (a.maskTex != 0) {
         GL11.glDeleteTextures(a.maskTex);
         a.maskTex = 0;
      }
      auxFreed++;
   }

   /** Render thread, TextureFBO.destroy (inside its GL task, before the FBO is deleted). */
   public static void fboDestroyed(int fboId) {
      if (BY_INDEX.isEmpty()) {
         return;
      }
      Aux found = null;
      for (Aux a : BY_INDEX.values()) {
         if (a.fbo == fboId) {
            found = a;
            break;
         }
      }
      if (found != null) {
         free(found);
      }
   }

   // ------------------------------------------------------------------------------------------------ render thread: composite

   static Frame renderFrame;
   static int frameSerial;
   private static final String[] UNIFORMS = {"pzSwOn", "pzSwAux", "pzSwMap", "pzSwWind", "pzSwPh", "pzSwGust", "pzSwPx", "pzSwGustTex", "pzSwMask", "pzSwPrevW", "pzSwPrevPh", "pzSwMvK", "pzSwPush", "pzSwPushN", "pzSwMvImg", "pzSwEpoch", "pzSwMvTile"};
   private static final HashMap<Integer, int[]> LOCATIONS = new HashMap<>();
   private static final HashMap<Integer, Integer> APPLIED = new HashMap<>();
   private static final HashMap<Integer, Integer> LAST_TEX = new HashMap<>();
   private static final float[] VP = new float[4];
   private static final int[] VPI = new int[4];
   private static final float[] MAP = new float[6];
   public static long compositeDraws, compositeSwayDraws;
   private static float lastProbe1, lastProbe2, lastProbe3;
   private static final java.nio.FloatBuffer PUSH_BUF = BufferUtils.createFloatBuffer(MAX_PUSH * 4);

   /** Render thread, ChunkRenderShader.startRenderThread: this frame's wind and this chunk texture's sway attributes. */
   public static void chunkDraw(Shader shader, TextureDraw texd) {
      if (!compositePatched || Config.DEV_SWAY_VARIANT_STOCK && (Config.DEV_SWAY_SKIP & 16) == 0) {
         return;
      }
      int prog = Ssr.boundProgram();
      int[] l = LOCATIONS.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length + 1];
         for (int i = 0; i < UNIFORMS.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         Shader sh = Shader.ShaderMap.get(prog);
         l[UNIFORMS.length] = sh != null && "pzopt_swChunk".equals(sh.getName()) && bindless(false) ? 1 : 0;
         LOCATIONS.put(prog, l);
      }
      if (l[0] < 0 && shader != null && !"pzopt_swChunk".equals(shader.getName())) {
         // pixelLight's / the sprite filter's composite: its twin for a texture that holds plants
         Frame f0 = renderFrame;
         Aux a0 = f0 != null && f0.on && texd != null && texd.tex1 != null ? BY_DEPTH.get(texd.tex1.getID()) : null;
         if (a0 != null && a0.any && a0.tex != 0) {
            zombie.viewCone.ChunkRenderShader t = twinCached(shader);
            if (t != null) {
               zombie.core.ShaderHelper.glUseProgramObjectARB(t.getID()); // through the game's cache: its ModelViewProjection goes to the bound program
               mvBind(true);
               twinDraws++;
               t.startRenderThread(texd); // DEPTH, chunkDepth and every pass's uniforms on the twin, this included
            }
         }
         return;
      }
      if (l[0] < 0 && !(Config.DEV_SWAY_VARIANT_STOCK && shader != null && "pzopt_swChunk".equals(shader.getName()))) {
         return; // not a patched program (dev: the stock copy still gets every per-draw call, to locations -1)
      }
      if (mvWanted && l[0] >= 0) {
         mvBind(true); // (a twin pixelLight bound itself, outside the StartShader hook)
      }
      Frame f = renderFrame;
      boolean on = f != null && f.on;
      Integer applied = APPLIED.get(prog);
      if (applied == null || applied != frameSerial) {
         APPLIED.put(prog, frameSerial);
         LAST_TEX.put(prog, -2);
         if (on) {
            // this frame's wind (once per program per frame); the samplers' units again (the game re-assigns every
            // sampler2D of a program to units 0, 1, 2... after it compiles)
            GL20.glUniform4f(l[3], f.amp, f.lean, f.dirX, f.osc);
            GL20.glUniform4f(l[4], f.ph[0], f.ph[1], f.ph[2], f.ph[3]);
            GL20.glUniform4f(l[5], f.gustU, f.gustV, f.gustFreq, f.flutter);
            if (l[9] >= 0) {
               GL20.glUniform4f(l[9], f.pLean, f.pOsc, f.pGustU, f.pGustV);
               GL20.glUniform4f(l[10], f.pPh[0], f.pPh[1], f.pPh[2], f.pPh[3]);
               // render px per logical px (the upscaler's scale over the zoom), DLSS's motion sign
               GL20.glUniform4f(l[11], RenderScale.scale() / Math.max(0.05F, f.zoom), Config.DLSS_MV_SIGN, 0F, 0F);
            }
            if (l[14] >= 0) {
               GL20.glUniform1i(l[14], MV_IMAGE_UNIT);
               GL20.glUniform1f(l[15], mvEpoch);
               if (l[16] >= 0) {
                  GL20.glUniform1i(l[16], MV_TILE_UNIT);
               }
            }
            if (l[12] >= 0) {
               PUSH_BUF.clear();
               PUSH_BUF.put(f.push, 0, MAX_PUSH * 4).flip();
               GL20.glUniform4fv(l[12], PUSH_BUF);
               GL20.glUniform1f(l[13], f.nPush);
            }
            lastProbe1 = f.probe1;
            lastProbe2 = f.probe2;
            lastProbe3 = f.probe3;
            if (l[UNIFORMS.length] == 0) {
               GL20.glUniform1i(l[1], AUX_UNIT);
               if (l[8] >= 0) {
                  GL20.glUniform1i(l[8], MASK_UNIT);
               }
            }
            if (l[7] >= 0 && gustTexture()) {
               GL20.glUniform1i(l[7], GUST_UNIT);
            }
         }
      }
      compositeDraws++;
      Aux a = null;
      int want = -1;
      if (on && texd != null && texd.tex1 != null) {
         a = BY_DEPTH.get(texd.tex1.getID());
         if (a != null) {
            a.lastDrawNs = System.nanoTime();
         }
         if (a != null && a.any && a.tex != 0) {
            want = a.tex;
         }
      }
      Integer last = LAST_TEX.get(prog);
      if (last != null && last == want) {
         return; // the same texture as the program's last draw: its textures and uniforms stand
      }
      LAST_TEX.put(prog, want);
      if (want > 0) {
         if (l[UNIFORMS.length] == 1) {
            // swayBindless: two handles instead of two binds and four unit switches
            if (a.auxHandle == 0L) {
               a.auxHandle = org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(a.tex);
               org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(a.auxHandle);
            }
            org.lwjgl.opengl.ARBBindlessTexture.glUniformHandleui64ARB(l[1], a.auxHandle);
            if (Config.SWAY_MASK && a.maskTex != 0 && l[8] >= 0) {
               if (a.maskHandle == 0L) {
                  a.maskHandle = org.lwjgl.opengl.ARBBindlessTexture.glGetTextureHandleARB(a.maskTex);
                  org.lwjgl.opengl.ARBBindlessTexture.glMakeTextureHandleResidentARB(a.maskHandle);
               }
               org.lwjgl.opengl.ARBBindlessTexture.glUniformHandleui64ARB(l[8], a.maskHandle);
            }
         } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + AUX_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, want);
            if (Config.SWAY_MASK && a.maskTex != 0) {
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + MASK_UNIT);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.maskTex);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         // the texture's mip level as the stock implicit fetch picks it: texels per render pixel = texels per logical px x zoom /
         // the upscaler's render scale (the quad is drawn 1:1 in logical px, scaled by the zoom)
         float texelsPerPx = (a.highRes ? 2F : 1F) * (f != null ? f.zoom : 1F) / Math.max(0.05F, RenderScale.scale());
         GL20.glUniform4f(l[0], 1F, a.uvX, a.uvY, texelsPerPx > 1F ? (float)(Math.log(texelsPerPx) / Math.log(2.0)) : 0F);
         GL20.glUniform4f(l[2], a.muA, a.muB, a.mvA, a.mvB);
         GL20.glUniform4f(l[6], (float)a.h / a.w, lastProbe1, lastProbe2, lastProbe3);
         compositeSwayDraws++;
      } else {
         GL20.glUniform4f(l[0], 0F, 0F, 0F, 1F);
      }
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static volatile boolean tilePatched;
   static volatile boolean compositePatched;
   private static final HashMap<String, Boolean> PATCHED_TILE = new HashMap<>();
   /** Render thread: program id -> is a patched bake program. */
   private static final HashMap<Integer, Boolean> PATCHED_PROGRAMS = new HashMap<>();
   private static final StringBuilder compositePatchedNames = new StringBuilder();
   private static String stockCompositeVert, stockCompositeFrag;
   private static final HashMap<String, zombie.viewCone.ChunkRenderShader> VARIANTS = new HashMap<>();
   private static final HashMap<String, Boolean> VARIANT_FAILED = new HashMap<>();
   public static long variantDraws;

   /**
    * Render thread, TextureDraw StartShader before the program is bound: the game's chunk composite becomes the sway
    * variant for a chunk texture that holds swaying plants (its DEPTH is already on the draw: FBORenderChunk puts it on
    * the StartShader), so a chunk draw still binds one program and runs one start, as in stock.
    */
   public static int remap(int program, TextureDraw texd) {
      if (program == 0 || stockCompositeFrag == null) {
         return program;
      }
      zombie.viewCone.ChunkRenderShader stock = zombie.core.SceneShaderStore.chunkRenderShader;
      if (stock == null) {
         return program;
      }
      if (program != stock.getID()) {
         if (Config.SWAY_TWIN_REMAP && !PixelLight.ACTIVE) {
            // the sprite filter's composite: its twin bound in its place (pixelLight picks its own program and twin itself)
            Shader sh = Shader.ShaderMap.get(program);
            if (sh instanceof zombie.viewCone.ChunkRenderShader && SOURCES.containsKey(sh.getProgram())) {
               Shader t = twinOrSelf(sh, texd);
               return t.getID();
            }
         }
         return program;
      }
      Frame f = renderFrame;
      if (f == null || !f.on) {
         return program;
      }
      if (!Config.DEV_SWAY_VARIANT_ALL) {
         Aux a = texd != null && texd.tex1 != null ? BY_DEPTH.get(texd.tex1.getID()) : null;
         if (a == null || !a.any || a.tex == 0) {
            return program;
         }
      }
      zombie.viewCone.ChunkRenderShader v = variant();
      if (v == null) {
         return program;
      }
      variantDraws++;
      return v.getID();
   }

   /** Render thread: the sway variant of the game's chunk composite for the current shape (compiled once per shape), or null. */
   private static zombie.viewCone.ChunkRenderShader variant() {
      if (stockCompositeFrag == null) {
         return null;
      }
      String key = shapeKey(false);
      zombie.viewCone.ChunkRenderShader v = VARIANTS.get(key);
      if (v != null || VARIANT_FAILED.containsKey(key)) {
         return v;
      }
      try {
         v = new zombie.viewCone.ChunkRenderShader("pzopt_swChunk");
         if (v.getProgram() != null && v.isCompiled()) {
            VARIANTS.put(key, v);
            addSwayProgram(v.getID());
            Log.info("foliage sway: composite variant " + key + " = program " + v.getID());
            return v;
         }
         Log.warn("foliage sway: the composite variant " + key + " did not compile; no sway");
      } catch (Throwable t) {
         Log.warn("foliage sway: the composite variant " + key + " failed: " + t);
      }
      VARIANT_FAILED.put(key, Boolean.TRUE);
      return null;
   }


   private static final String[] TILE_NAMES = {"tileWithDepth", "opaqueWithDepth", "seamFix2", "CutawayAttached"};
   private static final String[] COMPOSITE_NAMES = {"chunkShader", "pzopt_chunkBase", "pzopt_chunkStock", "pzopt_sfChunk"};

   private static String baseName(String fileName) {
      String fn = fileName.replace('\\', '/');
      int s = fn.lastIndexOf('/');
      return s >= 0 ? fn.substring(s + 1) : fn;
   }

   /**
    * ShaderUnit hook (outermost): the bake's tile programs write the sway attribute as a second output; the chunk
    * composite looks its texels up through the wind's displacement. Test-compiled; the source stays as it was on failure.
    */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || !Overrides.enabled() || Config.DEV_SWAY_NO_PATCH || System.getProperty("os.name", "").contains("OS X")) {
         return code;
      }
      String base = baseName(fileName);
      if (base.equals("chunkShader.vert")) {
         stockCompositeVert = code; // the variant's vertex shader
         return code;
      }
      if (base.equals("pzopt_swChunk.vert")) {
         return pendingTwinVert != null ? pendingTwinVert : stockCompositeVert != null ? stockCompositeVert : code;
      }
      if (!base.endsWith(".frag")) {
         return code;
      }
      String name = base.substring(0, base.length() - 5);
      try {
         if (name.equals("pzopt_swChunk") && pendingTwinFrag != null) {
            String p = patchComposite(pendingTwinFrag, false); // a twin of pixelLight's / the sprite filter's composite
            if (p == null || !compiles(p) || !links(pendingTwinVert, p)) {
               Log.warn("foliage sway: a composite twin does not compile (" + lastLog + ")");
               return FAILED_SOURCE; // (never the placeholder: its empty main draws black)
            }
            devDump("twin", pendingTwinVert, pendingTwinFrag, p);
            return p;
         }
         if (name.equals("pzopt_swChunk")) {
            if (Config.DEV_SWAY_VARIANT_STOCK && stockCompositeFrag != null) {
               return stockCompositeFrag; // dev: the variant is a plain copy of the game's program (the switch's own cost)
            }
            String p = stockCompositeFrag == null ? null : patchComposite(stockCompositeFrag, false);
            if (p == null || !compiles(p)) {
               Log.warn("foliage sway: the composite variant does not compile (" + lastLog + ")");
               return FAILED_SOURCE; // (never the placeholder: its empty main draws black)
            }
            return p;
         }
         if (name.equals("chunkShader") && !PixelLight.chunkShaderPatched) {
            // the game's composite stays exactly its own: the sway variant (pzopt_swChunk, this source patched) is bound
            // only for chunk textures that hold swaying plants
            stockCompositeFrag = code;
            compositePatched = true;
            compositePatchedNames.append(compositePatchedNames.length() == 0 ? "" : ",").append("chunkShader(variant)");
            return code;
         }
         for (String t : TILE_NAMES) {
            if (t.equals(name)) {
               String p = patchTile(code);
               if (p == null || !compiles(p)) {
                  Log.warn("foliage sway: " + base + " not patched (" + (p == null ? "no gl_FragColor / gl_FragDepth" : "does not compile") + ")");
                  PATCHED_TILE.put(name, false);
                  return code;
               }
               PATCHED_TILE.put(name, true);
               tilePatched = true;
               return p;
            }
         }
         for (String c : COMPOSITE_NAMES) {
            if (c.equals(name)) {
               // pixelLight's / the sprite filter's composites stay their own: a twin is compiled from their recorded
               // source when a texture with plants is drawn with them (recordSource, chunkDraw)
               compositePatched = true;
               if (compositePatchedNames.indexOf(name) < 0) {
                  compositePatchedNames.append(compositePatchedNames.length() == 0 ? "" : ",").append(name + "(twins)");
               }
               return code;
            }
         }
      } catch (Throwable e) {
         Log.warn("foliage sway: patching " + base + " failed: " + e);
      }
      return code;
   }

   // ---- twins: the sway version of any chunk composite program (pixelLight's variants, the sprite filter's), compiled on demand

   private static final java.util.IdentityHashMap<ShaderProgram, String[]> SOURCES = new java.util.IdentityHashMap<>(); // {vert, frag}
   private static final HashMap<String, zombie.viewCone.ChunkRenderShader> TWINS = new HashMap<>(); // base program id + shape -> twin
   private static final HashMap<Integer, Shader> TWIN_BASE = new HashMap<>(); // twin program id -> its base shader
   private static final java.util.HashSet<String> TWIN_FAILED = new java.util.HashSet<>();
   private static String pendingTwinVert, pendingTwinFrag;
   private static int devDumps;

   /** dev (devSwayDumpDir): a twin's vertex, base and patched fragment sources for tools/ShaderRegs.java. */
   private static void devDump(String kind, String vert, String base, String patched) {
      if (Config.DEV_SWAY_DUMP_DIR.isEmpty()) {
         return;
      }
      try {
         java.nio.file.Path d = java.nio.file.Path.of(Config.DEV_SWAY_DUMP_DIR);
         java.nio.file.Files.createDirectories(d);
         int n = devDumps++;
         java.nio.file.Files.writeString(d.resolve(kind + n + ".vert"), vert == null ? "" : vert);
         java.nio.file.Files.writeString(d.resolve(kind + n + "-base.frag"), base == null ? "" : base);
         java.nio.file.Files.writeString(d.resolve(kind + n + "-sway.frag"), patched);
      } catch (java.io.IOException e) {
         Log.warn("foliage sway: dump failed: " + e);
      }
   }
   public static long twinDraws;

   /** ShaderUnit hook (outermost): a chunk composite's final source, recorded with its program (the twins' source). */
   public static String recordSource(ShaderProgram program, String fileName, String code) {
      if (program == null || fileName == null || code == null) {
         return code;
      }
      String base = baseName(fileName);
      int dot = base.lastIndexOf('.');
      String name = dot > 0 ? base.substring(0, dot) : base;
      boolean composite = false;
      for (String c : COMPOSITE_NAMES) {
         composite |= c.equals(name);
      }
      if (composite) {
         String[] v = SOURCES.computeIfAbsent(program, k -> new String[2]);
         v[base.endsWith(".vert") ? 0 : 1] = code;
      }
      return code;
   }

   /** The shader a twin was made from (itself for anything else): pixelLight keeps a twin of its chosen variant. */
   public static Shader baseOf(Shader shader) {
      if (shader == null || TWIN_BASE.isEmpty()) {
         return shader;
      }
      Shader b = TWIN_BASE.get(shader.getID());
      return b != null ? b : shader;
   }

   // twins by base shader for this frame's shape (twinFor's string key built on every draw cost render-thread time)
   private static final java.util.IdentityHashMap<Shader, zombie.viewCone.ChunkRenderShader> TWIN_FAST = new java.util.IdentityHashMap<>();
   private static String twinShape;
   private static int twinShapeSerial = Integer.MIN_VALUE;

   private static zombie.viewCone.ChunkRenderShader twinCached(Shader base) {
      if (twinShapeSerial != frameSerial) {
         twinShapeSerial = frameSerial;
         String k = shapeKey(false);
         if (!k.equals(twinShape)) {
            twinShape = k;
            TWIN_FAST.clear();
         }
      }
      zombie.viewCone.ChunkRenderShader t = TWIN_FAST.get(base);
      if (t == null && !TWIN_FAST.containsKey(base)) {
         t = twinFor(base);
         TWIN_FAST.put(base, t);
      }
      return t;
   }

   /**
    * Render thread, a composite program chosen for a chunk draw (pixelLight's variant, the sprite filter's composite): its
    * sway twin when the texture holds swaying plants, so the draw binds and starts one program, not the base and then the
    * twin (each start sets every pass's uniforms: +74 us of render thread a frame with the full set).
    */
   public static Shader twinOrSelf(Shader want, TextureDraw texd) {
      if (want == null || !compositePatched || texd == null || texd.tex1 == null || TWIN_BASE.containsKey(want.getID())) {
         return want;
      }
      Frame f = renderFrame;
      if (f == null || !f.on) {
         return want;
      }
      if (!Config.SWAY_TWIN_ALL) {
         Aux a = BY_DEPTH.get(texd.tex1.getID());
         if (a == null || !a.any || a.tex == 0) {
            return want;
         }
      }
      zombie.viewCone.ChunkRenderShader t = twinCached(want);
      if (t == null) {
         return want;
      }
      twinDraws++;
      return t;
   }

   /** Render thread: the twin of a chunk composite shader for the current shape (compiled once), or null. */
   private static zombie.viewCone.ChunkRenderShader twinFor(Shader base) {
      ShaderProgram bp = base.getProgram();
      String[] src = bp == null ? null : SOURCES.get(bp);
      if (src == null || src[0] == null || src[1] == null) {
         return null;
      }
      String key = base.getID() + ":" + shapeKey(false);
      zombie.viewCone.ChunkRenderShader t = TWINS.get(key);
      if (t != null || TWIN_FAILED.contains(key)) {
         return t;
      }
      pendingTwinVert = src[0];
      pendingTwinFrag = src[1];
      try {
         t = new zombie.viewCone.ChunkRenderShader("pzopt_swChunk");
         if (t.getProgram() != null && t.isCompiled()) {
            TWINS.put(key, t);
            addSwayProgram(t.getID());
            TWIN_BASE.put(t.getID(), base);
            Log.info("foliage sway: twin " + t.getID() + " of " + base.getName() + " program " + base.getID() + " (" + shapeKey(false) + ")");
            return t;
         }
         Log.warn("foliage sway: the twin of " + base.getName() + " " + base.getID() + " did not compile");
      } catch (Throwable e) {
         Log.warn("foliage sway: the twin of " + base.getName() + " failed: " + e);
      } finally {
         pendingTwinVert = null;
         pendingTwinFrag = null;
      }
      TWIN_FAILED.add(key);
      return null;
   }

   /** Render thread, sway switched off: every patched tile program's sway uniform back to 0 (depths written unchanged). */
   private static void resetTilePrograms() {
      int prev = Ssr.boundProgram();
      for (java.util.Map.Entry<Integer, Boolean> e : PATCHED_PROGRAMS.entrySet()) {
         if (!e.getValue()) {
            continue;
         }
         int l = GL20.glGetUniformLocation(e.getKey(), "pzSwObj");
         if (l >= 0) {
            GL20.glUseProgram(e.getKey());
            GL20.glUniform4f(l, 0F, 0F, 0F, 0F);
         }
      }
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      GL20.glUseProgram(prev > 0 ? prev : 0);
   }

   /** Render thread: is {@code program} one of the patched bake programs (cached by id)? */
   private static boolean isPatchedProgram(int program) {
      Boolean b = PATCHED_PROGRAMS.get(program);
      if (b == null) {
         Shader shader = Shader.ShaderMap.get(program);
         b = shader != null && isPatchedShader(shader);
         PATCHED_PROGRAMS.put(program, b);
      }
      return b;
   }

   private static String lastLog = "";
   /** What a sway composite that does not compile gets instead: the game marks the program uncompiled, it is never used. */
   private static final String FAILED_SOURCE = "#version 120\n#error pzopt foliage sway: this composite variant did not compile\nvoid main() {}\n";

   private static boolean compiles(String src) {
      int s = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(s, src);
      GL20.glCompileShader(s);
      boolean ok = GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) != 0;
      lastLog = ok ? "" : GL20.glGetShaderInfoLog(s, 4096);
      GL20.glDeleteShader(s);
      return ok;
   }

   /** Vertex + fragment source compile and link (the twins: the fragment alone compiled while the program did not link). */
   private static boolean links(String vert, String frag) {
      if (vert == null) {
         return true;
      }
      int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
      GL20.glShaderSource(vs, vert);
      GL20.glCompileShader(vs);
      int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(fs, frag);
      GL20.glCompileShader(fs);
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, vs);
      GL20.glAttachShader(p, fs);
      GL20.glLinkProgram(p);
      boolean ok = GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != 0;
      lastLog = ok ? "" : "vertex: " + GL20.glGetShaderInfoLog(vs, 2048) + " link: " + GL20.glGetProgramInfoLog(p, 4096);
      GL20.glDeleteProgram(p);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      return ok;
   }

   private static int glslVersion(String code) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("#version\\s+(\\d+)").matcher(code);
      return m.find() ? Integer.parseInt(m.group(1)) : 110;
   }

   /** Inserts {@code block} right before {@code void main} (after every declaration the functions may use). */
   private static String beforeMain(String code, String block) {
      int at = code.indexOf("void main");
      return at < 0 ? null : code.substring(0, at) + block + "\n" + code.substring(at);
   }

   /**
    * A bake tile program: every {@code gl_FragColor = x;} becomes both outputs, the colour and the sway attribute (the
    * plant's weight at this row, class, phase and four depth bits; zero for anything rigid).
    */
   static String patchTile(String code) {
      if (!code.contains("gl_FragColor") || !code.contains("gl_FragDepth") || code.contains("gl_FragData")) {
         return null;
      }
      String block = String.join("\n",
         "uniform vec4 pzSwObj;", // x: amplitude (of AMP_MAX_PX) at the top, y: phase 0..15, z: class, w: 1 = plant, 2 = rigid (sway on), 0 = sway off
         "uniform vec4 pzSwObj2;", // x: ground row (logical px), y: plant height (logical px), z: texture px per logical px, w: flip (FBO height) or 0
         // this texel's weight step (0..63): the plant's height here ^1.5 (bends like a cantilever: the base stays, the top moves)
         "float pzSwQ() {",
         "   if (pzSwObj.w < 0.5 || pzSwObj.w > 1.5) return 0.0;",
         "   float fy = pzSwObj2.w > 0.0 ? pzSwObj2.w - gl_FragCoord.y : gl_FragCoord.y;",
         "   float h = clamp((pzSwObj2.x - fy / pzSwObj2.z) / max(pzSwObj2.y, 1.0), 0.0, 1.0);",
         "   return floor(clamp(pzSwObj.x * h * sqrt(h), 0.0, 1.0) * 63.0 + 0.5);",
         "}",
         // the depth as DEPTH16 will hold it, its lowest bit the flag the composite tests for free: 1 = a texel that sways
         "float pzSwDepth(float d) {",
         "   if (pzSwObj.w < 0.5) return d;",
         "   float d16 = floor(clamp(d, 0.0, 1.0) * 65535.0 + 0.5);",
         "   d16 = min(d16 - mod(d16, 2.0) + (pzSwQ() > 0.5 ? 1.0 : 0.0), 65534.0);",
         "   return d16 / 65535.0;",
         "}",
         "vec4 pzSwAttr(float depth) {",
         "   float d16 = floor(clamp(depth, 0.0, 1.0) * 65535.0 + 0.5);",
         "   float db = d16 - 16.0 * floor(d16 / 16.0);",
         "   float q = pzSwQ();",
         "   if (q < 0.5) return vec4(0.0, db / 255.0, 0.0, 1.0);",
         "   return vec4((q * 4.0 + pzSwObj.z) / 255.0, (pzSwObj.y * 16.0 + db) / 255.0, 0.0, 1.0);",
         "}");
      String c = beforeMain(code, block);
      if (c == null) {
         return null;
      }
      // gl_FragDepth = <expr>;  ->  gl_FragDepth = pzSwDepth(<expr>);
      java.util.regex.Matcher md = java.util.regex.Pattern.compile("gl_FragDepth\\s*=\\s*([^;]+);").matcher(c);
      StringBuilder sd = new StringBuilder();
      int nd = 0;
      while (md.find()) {
         md.appendReplacement(sd, java.util.regex.Matcher.quoteReplacement("gl_FragDepth = pzSwDepth(" + md.group(1) + ");"));
         nd++;
      }
      md.appendTail(sd);
      if (nd == 0) {
         return null;
      }
      c = sd.toString();
      // gl_FragColor = <expr>;  ->  gl_FragData[0] = <expr>; gl_FragData[1] = pzSwAttr(gl_FragDepth);
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("gl_FragColor\\s*=\\s*([^;]+);").matcher(c);
      StringBuilder sb = new StringBuilder();
      int n = 0;
      while (m.find()) {
         m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("{ gl_FragData[0] = " + m.group(1) + "; gl_FragData[1] = pzSwAttr(gl_FragDepth); }"));
         n++;
      }
      m.appendTail(sb);
      if (n == 0 || sb.indexOf("gl_FragColor") >= 0) {
         return null;
      }
      return sb.toString();
   }

   /** The gust at world position {@code wp} for the drift {@code drift} (a vec2 expression). */
   private static String gustExpr(boolean noGust, String lod, String drift) {
      if (noGust) {
         return "0.5";
      }
      if ("sines".equals(Config.SWAY_GUST)) {
         // two crossing waves over the world drifting downwind (no fetch: the gust tap was a dependent fetch in the chain)
         // (wave numbers in 32nds: whole periods over the 256-square wrap of the world coordinates, no seam there)
         return "(0.5 + 0.25 * (sin(dot(wp, vec2(0.625, 0.21875)) * 0.78539816 + (" + drift + ").x * 0.78539816) + sin(dot(wp, vec2(-0.28125, 0.71875)) * 0.78539816 + (" + drift + ").y * 0.78539816)))";
      }
      return lod + "(pzSwGustTex, (wp * pzSwGust.z + (" + drift + ") + 0.5) / 32.0, 0.0).r";
   }

   /** The sway programs write their own motion vectors (DLSS on at compile time; swayMotionVectors). */
   /** swayMvImage: the plant fragments store their motion in an image (no second render target in the composite). */
   static boolean mvImage() {
      if (!Config.SWAY_MV_IMAGE) {
         return false;
      }
      if (mvImageCap == 0) {
         try {
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            mvImageCap = caps.OpenGL42 || caps.GL_ARB_shader_image_load_store ? 1 : -1;
         } catch (IllegalStateException noContext) {
            return true; // (no GL context: an offline shader dump; in the game the variants are compile-tested)
         }
      }
      return mvImageCap > 0;
   }

   private static int mvImageCap;

   static boolean mvShape() {
      return Config.SWAY_MV && "dlss".equals(RenderScale.mode());
   }

   /** The compile-time shape of a sway composite program (the variant is compiled per shape: nothing dead in it). */
   static String shapeKey(boolean inPlace) {
      return (mvShape() ? (mvImage() ? "mi" : "mv") : "") + "t" + Config.FOLIAGE_SWAY_TAPS + "v" + (Config.DEV_SWAY_VIEW > 0 ? 1 : 0) + "c" + (Config.SWAY_DEPTH_CHECK ? 1 : 0) + "m" + (Config.SWAY_MASK ? 1 : 0)
         + "s" + (Config.DEV_SWAY_SKIP & (4 | 8 | 32 | 64)) + "i" + Config.SWAY_ITERATIONS + "g" + Config.SWAY_GUST + (Config.SWAY_PUSH ? "p" : "") + (Config.SWAY_LIGHT_UNDISPLACED ? "l" : "") + (Config.SWAY_TWIN_ALL ? "a" : "") + (Config.SWAY_PREFETCH ? "f" : "") + (Config.SWAY_AUX_EAGER ? "e" : "") + (bindless(inPlace) ? "b" : "") + (inPlace ? "p" : "");
   }

   /**
    * The chunk composite: the texel lookup goes through the inverse of the sway displacement. {@code inPlace}: the game's
    * own program is patched (pixelLight's and the sprite filter's composites), so a uniform switches it per texture; else
    * the source is the sway variant, bound only for textures that hold plants. Everything else is decided here, at
    * compile time (taps, the depth check, the tile mask, the dev view): a uniform-gated dead path still costs its
    * registers in every fragment (measured: the gated code alone +15 us at 5K).
    */
   static String patchComposite(String code) {
      return patchComposite(code, true);
   }

   /** The variant reads the attribute and mask textures through bindless handles (swayBindless, where supported). */
   static boolean bindless(boolean inPlace) {
      if (!Config.SWAY_BINDLESS || inPlace) {
         return false;
      }
      try {
         return org.lwjgl.opengl.GL.getCapabilities().GL_ARB_bindless_texture;
      } catch (Throwable t) {
         return false;
      }
   }

   static String patchComposite(String code, boolean inPlace) {
      boolean bl = bindless(inPlace);
      if (bl) {
         // GLSL 4.00+ for bindless samplers: the stock 1.20 composite as 4.20 compatibility (varying, texture2D, gl_FragColor stay)
         java.util.regex.Matcher vm = java.util.regex.Pattern.compile("#version\\s+(\\d+)[^\\n]*").matcher(code);
         if (vm.find() && Integer.parseInt(vm.group(1)) < 420) {
            code = code.substring(0, vm.start()) + "#version 420 compatibility" + code.substring(vm.end());
         }
         int eol = code.indexOf('\n', code.indexOf("#version"));
         code = code.substring(0, eol + 1) + "#extension GL_ARB_bindless_texture : require\n" + code.substring(eol + 1);
      }
      if (mvShape() && mvImage()) {
         // image stores: GLSL 4.20, or 1.50 compatibility + ARB_shader_image_load_store (the stock composite says 1.20)
         int v0 = glslVersion(code);
         java.util.regex.Matcher vm = java.util.regex.Pattern.compile("#version\\s+(\\d+)[^\\n]*").matcher(code);
         if (v0 < 420 && vm.find()) {
            String head = v0 < 150 ? "#version 150 compatibility" : vm.group(0);
            String rest = code.substring(vm.end());
            code = code.substring(0, vm.start()) + head + (rest.contains("GL_ARB_shader_image_load_store") ? "" : "\n#extension GL_ARB_shader_image_load_store : require") + rest;
         }
      }
      int ver = glslVersion(code);
      boolean modern = ver >= 130; // (4.20 compatibility: texture / textureLod for our fetches, the stock texture2D calls stay valid)
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("(varying|in)\\s+vec2\\s+texCoord\\s*;").matcher(code);
      if (!m.find() || code.contains("pzSwT")) {
         return null;
      }
      int taps = Math.max(1, Math.min(4, Config.FOLIAGE_SWAY_TAPS));
      boolean view = Config.DEV_SWAY_VIEW > 0;
      boolean check = Config.SWAY_DEPTH_CHECK;
      boolean mask = Config.SWAY_MASK;
      boolean noGust = (Config.DEV_SWAY_SKIP & 4) != 0;
      boolean oneStep = (Config.DEV_SWAY_SKIP & 8) != 0 || Config.SWAY_ITERATIONS < 2;
      // the varying keeps its name (it links to the vertex shader's output by name); every use reads pzSwT, the displaced copy
      String decl = m.group(0);
      String c = code.substring(0, m.start()) + "@@PZSW_DECL@@" + code.substring(m.end());
      // the colour fetch is declared here, ahead of every other patch's functions (cloud shadows fetch DIFFUSE above main)
      String tex0 = glslVersion(code) >= 130 ? "textureLod" : "texture2DLod";
      boolean lightStill = Config.SWAY_LIGHT_UNDISPLACED && c.contains("pplPos");
      c = (lightStill
            // pixelLight's twin (swayLightUndisplaced): only the colour and the depth come from the moved texel; its light
            // taps keep the pixel's own texel (light is smooth), so they need not wait for the sway lookup
            ? c.replaceAll("\\b(texture2D|texture)\\(\\s*(DIFFUSE|DEPTH)\\s*,\\s*texCoord\\b", "$1($2, pzSwT")
            : c.replaceAll("\\btexCoord\\b", "pzSwT")).replace("@@PZSW_DECL@@", decl + "\nvec2 pzSwT;\nvec2 pzSwMv = vec2(0.0);\nfloat pzSwLod;\n" + (Config.SWAY_TWIN_ALL
            // (swayTwinAll: a texture without attributes keeps the implicit level; a uniform branch, derivatives stay valid)
            ? "uniform vec4 pzSwOn;\nvec4 pzSwFetch(sampler2D s, vec2 uv) { if (pzSwOn.x < 0.5) return " + (glslVersion(code) >= 130 ? "texture" : "texture2D") + "(s, uv); return " + tex0 + "(s, uv, pzSwLod); }"
            : Config.SWAY_PREFETCH
            // swayPrefetch: colour and depth fetched at the pixel's own texel with the flag test; only a pixel a plant moved
            // fetches again (the base program's fetches start at once: waiting for the flag first cost every pixel a round trip)
            ? "vec4 pzSwC0;\nvec4 pzSwZ0;\nbool pzSwMoved = false;\nvec4 pzSwFetch(sampler2D s, vec2 uv) { if (pzSwMoved) return " + tex0 + "(s, uv, pzSwLod); return pzSwC0; }\n"
               + "vec4 pzSwDepthFetch(sampler2D s, vec2 uv) { if (pzSwMoved) return " + (glslVersion(code) >= 130 ? "texture" : "texture2D") + "(s, uv); return pzSwZ0; }"
            : "vec4 pzSwFetch(sampler2D s, vec2 uv) { return " + tex0 + "(s, uv, pzSwLod); }"));
      String tex = modern ? "texture" : "texture2D";
      String lod = modern ? "textureLod" : "texture2DLod"; // fetches inside divergent branches: explicit level (no derivatives there)
      StringBuilder b = new StringBuilder();
      java.util.function.Consumer<String> L = x -> b.append(x).append('\n');
      if (!Config.SWAY_TWIN_ALL) L.accept("uniform vec4 pzSwOn;"); // (swayTwinAll: declared with the fetch above) // x: 1 = sway this texture (in-place programs), y, z: uv per logical px, w: its mip level at this zoom
      L.accept("uniform vec4 pzSwMap;"); // texCoord -> world u = x - y (x, y), v = x + y - 6z (z, w), modulo the gust period
      L.accept((bl ? "layout(bindless_sampler) " : "") + "uniform sampler2D pzSwAux;");
      L.accept("uniform sampler2D pzSwGustTex;"); // 32 x 32 periodic value noise (R8, bilinear, repeat): the gust field
      if (mask) L.accept((bl ? "layout(bindless_sampler) " : "") + "uniform sampler2D pzSwMask;"); // one byte per 16 x 16 texels: can a plant reach this tile
      L.accept("uniform vec4 pzSwWind;"); // x: amplitude scale, y: lean, z: downwind screen direction (+1 / -1), w: oscillation
      L.accept("uniform vec4 pzSwPh;"); // the classes' oscillator phases (grass, bush, -, tree)
      L.accept("uniform vec4 pzSwGust;"); // x, y: the gust field's drift (cells), z: cells per square, w: leaf flutter
      L.accept("uniform vec4 pzSwPx;"); // x: the texture's height / width, y, z, w: upwind probes (logical px)
      if (view) L.accept("vec4 pzSwTint = vec4(0.0);");
      // (weight 0..1, class 0..3, phase 0..1) of an attribute texel
      L.accept("vec3 pzSwDec(vec2 rg) { float q = floor(rg.r * 255.0 + 0.5); float w = floor(q / 4.0); return vec3(w / 63.0, q - 4.0 * w, floor(floor(rg.g * 255.0 + 0.5) / 16.0) / 16.0); }");
      if (check) {
         // the texel's depth still the one the plant wrote (else something covered it later); +-1 step: the driver's float -> DEPTH16 rounding
         L.accept("bool pzSwOk(vec2 uv, vec2 rg) { float d16 = floor(" + tex + "(DEPTH, uv).r * 65535.0 + 0.5); float g = floor(rg.g * 255.0 + 0.5);");
         L.accept("   float e = abs((d16 - 16.0 * floor(d16 / 16.0)) - (g - 16.0 * floor(g / 16.0))); return e < 1.5 || e > 14.5; }");
      }
      // displacement (logical px, x right, y down) of a texel with attribute a; g: the gust at the pixel (computed once)
      L.accept("vec2 pzSwDk(vec3 a, float g, vec2 wp, vec2 lo, vec4 phs) {"); // lo: lean, oscillation
      L.accept("   float amp = a.x * " + AMP_MAX_PX + " * pzSwWind.x;");
      L.accept("   float ph = (a.y < 0.5 ? phs.x : (a.y < 1.5 ? phs.y : (a.y < 2.5 ? phs.z : phs.w))) + a.z * 6.2831853;");
      L.accept("   float x = amp * pzSwWind.z * (lo.x * (0.3 + 0.7 * g) + lo.y * (0.45 + 0.55 * g) * sin(ph + g * 2.5));");
      L.accept("   if (a.y > 0.5) x += amp * pzSwGust.w * (0.3 + g) * sin(ph * 2.7 + a.z * 11.0 + dot(wp, vec2(1.7, 0.9)));"); // leaves flutter
      L.accept("   return vec2(x, 0.08 * abs(x));");
      L.accept("}");
      if (Config.SWAY_PUSH) {
         L.accept("uniform vec4 pzSwPush[" + MAX_PUSH + "];"); // characters walking through plants: u, v, radius (squares), strength
         L.accept("uniform float pzSwPushN;");
         // the texel bends away from each pusher (screen x ~ u); a texel sits up to a plant's height above its base (v smaller)
         L.accept("float pzSwPushX(vec2 wp) {");
         L.accept("   float x = 0.0;");
         L.accept("   for (int i = 0; i < " + MAX_PUSH + "; i++) {");
         L.accept("      if (float(i) >= pzSwPushN) break;");
         L.accept("      vec4 P = pzSwPush[i];");
         L.accept("      vec2 d = wp - P.xy; d -= " + GUST_PERIOD_SQ + " * floor(d / " + GUST_PERIOD_SQ + " + 0.5);");
         L.accept("      float fu = max(0.0, 1.0 - abs(d.x) / P.z);");
         L.accept("      float fv = d.y > 0.0 ? max(0.0, 1.0 - d.y / P.z) : clamp(1.0 + (d.y + 3.0) / P.z, 0.0, 1.0);");
         L.accept("      x += (d.x >= 0.0 ? 1.0 : -1.0) * fu * fu * fv * P.w;");
         L.accept("   }");
         L.accept("   return x;");
         L.accept("}");
         L.accept("vec2 pzSwD(vec3 a, float g, vec2 wp) { vec2 d = pzSwDk(a, g, wp, pzSwWind.yw, pzSwPh); float px = pzSwPushN > 0.5 ? a.x * " + AMP_MAX_PX + " * 0.9 * pzSwPushX(wp) : 0.0; return d + vec2(px, 0.1 * abs(px)); }");
      } else {
         L.accept("vec2 pzSwD(vec3 a, float g, vec2 wp) { return pzSwDk(a, g, wp, pzSwWind.yw, pzSwPh); }");
      }
      boolean mv = mvShape();
      boolean mvImg = mv && mvImage();
      if (mvImg) {
         L.accept("layout(r32ui) writeonly uniform uimage2D pzSwMvImg;"); // [epoch 10 | motion x 11 | y 11] (1/256 render px, +-4)
         L.accept("layout(r16ui) writeonly uniform uimage2D pzSwMvTile;"); // the epoch of every 16 x 16 tile a plant wrote into
         L.accept("uniform float pzSwEpoch;");
      }
      if (mv) {
         L.accept("uniform vec4 pzSwPrevW;"); // the previous frame's lean, oscillation, gust drift
         L.accept("uniform vec4 pzSwPrevPh;");
         L.accept("uniform vec4 pzSwMvK;"); // x: render px per logical px, y: DLSS's motion sign
      }
      L.accept("vec2 pzSwUv(vec2 d) { return d * pzSwOn.yz; }"); // logical px (y down) -> uv (texture rows follow logical rows)
      // the colour fetch at the displaced texel with the undisplaced mip level (a displacement jump at a plant's edge must
      // not pick a coarse mip); one fetch (a ternary between two texture calls is flattened: both ran)
      L.accept("void pzSway() {");
      L.accept("   pzSwT = texCoord;");
      // the chunk quad is affine: its mip level is one number per draw (set with the texture), no derivatives per fragment
      L.accept("   pzSwLod = pzSwOn.w;");
      boolean prefetch = Config.SWAY_PREFETCH && !Config.SWAY_TWIN_ALL;
      if (prefetch) {
         L.accept("   pzSwC0 = " + lod + "(DIFFUSE, texCoord, pzSwLod);");
         L.accept("   pzSwZ0 = " + tex + "(DEPTH, texCoord);");
      }
      if (inPlace || Config.SWAY_TWIN_ALL) L.accept("   if (pzSwOn.x < 0.5) return;");
      if (Config.SWAY_AUX_EAGER) L.accept("   vec2 pzSwR0 = " + lod + "(pzSwAux, texCoord, 0.0).rg;"); // (swayTwinAll: a texture without attributes draws through the twin too)
      if ((Config.DEV_SWAY_SKIP & 64) != 0) L.accept("   if (pzSwOn.w > -1.0) return;"); // dev: the program without its lookup (uniform: not dead code)
      // the depth texel the composite reads anyway: its lowest bit says whether this texel sways (the bake sets it);
      // anything else leaves at once, with one texel fetch the stock shader makes too (a cache hit for its own fetch)
      L.accept("   float d16 = floor(" + (prefetch ? "pzSwZ0" : tex + "(DEPTH, texCoord)") + ".r * 65535.0 + 0.5);");
      L.accept("   bool fl = d16 < 65534.5 && d16 - 2.0 * floor(d16 * 0.5) > 0.5;");
      if (taps < 2) L.accept("   if (!fl) return;");
      if (taps >= 2 && mask) L.accept("   if (!fl && " + tex + "(pzSwMask, texCoord).r < 0.5) return;"); // no plant can reach this tile
      if (Config.SWAY_AUX_EAGER) {
         // swayAuxEager: the attribute fetched beside the depth (not after the flag): plant pixels wait one round trip less
         L.accept("   vec2 r0 = fl ? pzSwR0 : vec2(0.0);");
      } else {
         L.accept("   vec2 r0 = vec2(0.0);");
         L.accept("   if (fl) r0 = " + lod + "(pzSwAux, texCoord, 0.0).rg;"); // (an if: a ternary around a fetch is flattened, the fetch runs anyway)
      }
      L.accept("   vec3 a0 = pzSwDec(r0);");
      // the attribute belongs to this texel only while the depth is the plant's (a later overdraw keeps the flag only by chance)
      L.accept("   bool ok0 = fl && a0.x > 0.0" + (check ? " && abs((d16 - 16.0 * floor(d16 / 16.0)) - (floor(r0.g * 255.0 + 0.5) - 16.0 * floor(floor(r0.g * 255.0 + 0.5) / 16.0))) < 0.5" : "") + ";");
      if (view) L.accept("   pzSwTint = vec4(ok0 ? 0.3 + 0.7 * a0.x : 0.0, 0.0, a0.y > 2.5 ? 0.6 : 0.0, a0.x > 0.0 && !ok0 ? 1.0 : 0.6);");
      L.accept("   vec2 wp = vec2(pzSwMap.x * texCoord.x + pzSwMap.y, pzSwMap.z * texCoord.y + pzSwMap.w);");
      L.accept("   float g = " + gustExpr(noGust, lod, "pzSwGust.xy") + ";");
      L.accept("   if (ok0) {");
      // inside a plant: s = p - D(s), two steps from s = p
      L.accept("      vec2 t1 = texCoord - pzSwUv(pzSwD(a0, g, wp));");
      if (oneStep) {
         L.accept("      pzSwT = t1;");
         if (prefetch) L.accept("      pzSwMoved = true;");
         if (mv) L.accept("      vec3 am = a0;");
      } else {
         L.accept("      vec3 a1 = pzSwDec(" + lod + "(pzSwAux, t1, 0.0).rg);");
         L.accept("      pzSwT = a1.x > 0.0 ? texCoord - pzSwUv(pzSwD(a1, g, wp)) : t1;");
         if (prefetch) L.accept("      pzSwMoved = true;");
         if (mv) L.accept("      vec3 am = a1.x > 0.0 ? a1 : a0;");
      }
      if (mv && (Config.DEV_SWAY_SKIP & 32) == 0) { // (dev 32: the output written as zero, no motion math: export cost alone)
         // where this texel was a frame ago, relative to where it is now, in render px (y up): DLSS's motion
         L.accept("      float gp = " + gustExpr(noGust, lod, "pzSwPrevW.zw") + ";");
         L.accept("      vec2 dl = pzSwDk(am, gp, wp, pzSwPrevW.xy, pzSwPrevPh) - pzSwD(am, g, wp);");
         L.accept("      pzSwMv = vec2(dl.x, -dl.y) * pzSwMvK.x * pzSwMvK.y / " + MV_RANGE + ";");
      }
      if (mvImg) {
         // only plant fragments write (the render target wrote every fragment of the draw: ~35 us at 5K)
         L.accept("      uint e = uint(pzSwEpoch);");
         L.accept("      uvec2 mq = uvec2(clamp(floor(pzSwMv * " + (MV_RANGE * 256F) + " + 0.5), -1024.0, 1023.0) + 1024.0);");
         L.accept("      imageStore(pzSwMvImg, ivec2(gl_FragCoord.xy), uvec4((e << 22u) | (mq.x << 11u) | mq.y));");
         L.accept("      imageStore(pzSwMvTile, ivec2(gl_FragCoord.xy) >> 4, uvec4(e));");
      }
      L.accept("      return;");
      L.accept("   }");
      if (taps >= 2) {
         // the plant's leading edge: a texel upwind whose own displacement lands on this pixel
         L.accept("   float d0 = d16 / 65535.0;");
         for (int k = 0; k < taps - 1; k++) {
            String pr = k == 0 ? "pzSwPx.y" : k == 1 ? "pzSwPx.z" : "pzSwPx.w";
            L.accept("   {");
            L.accept("      vec2 rq = " + lod + "(pzSwAux, texCoord - pzSwUv(vec2(pzSwWind.z * " + pr + ", 0.0)), 0.0).rg;");
            L.accept("      if (rq.r >= 0.5 / 255.0) {");
            L.accept("         vec2 dq = pzSwD(pzSwDec(rq), g, wp);");
            L.accept("         vec2 s = texCoord - pzSwUv(dq);");
            L.accept("         vec2 rs = " + lod + "(pzSwAux, s, 0.0).rg;");
            L.accept("         if (rs.r >= 0.5 / 255.0 && abs(pzSwD(pzSwDec(rs), g, wp).x - dq.x) <= 0.75) {");
            L.accept("            float ds0 = " + lod + "(DEPTH, s, 0.0).r;");
            L.accept("            if (!(d0 > 0.0 && ds0 > d0)) {"); // the plant is behind what this pixel shows: it stays hidden
            L.accept("               pzSwT = s;");
            if (prefetch) L.accept("               pzSwMoved = true;");
            if (view) L.accept("               pzSwTint = vec4(0.0, 1.0, 0.0, 0.8);");
            L.accept("               return;");
            L.accept("            }");
            L.accept("         }");
            L.accept("      }");
            L.accept("   }");
         }
      }
      L.accept("}");
      c = beforeMain(c, b.toString());
      if (c == null) {
         return null;
      }
      c = c.replaceAll("\\b(texture2D|texture)\\(\\s*DIFFUSE\\s*,\\s*pzSwT(\\.st)?\\s*(,\\s*[0-9.]+\\s*)?\\)", "pzSwFetch(DIFFUSE, pzSwT)");
      if (Config.SWAY_PREFETCH && !Config.SWAY_TWIN_ALL) {
         c = c.replaceAll("\\b(texture2D|texture)\\(\\s*DEPTH\\s*,\\s*pzSwT(\\.st)?\\s*\\)", "pzSwDepthFetch(DEPTH, pzSwT)");
      }
      c = c.replace("dFdx(pzSwT", "dFdx(texCoord").replace("dFdy(pzSwT", "dFdy(texCoord").replace("fwidth(pzSwT", "fwidth(texCoord");
      if (glslVersion(c) < 130) {
         int eol = c.indexOf('\n', c.indexOf("#version"));
         c = c.substring(0, eol + 1) + "#extension GL_ARB_shader_texture_lod : enable\n" + c.substring(eol + 1);
      }
      if (view) {
         // dev view (devSwayView=1): the colour tinted by what the lookup did (red: weight, green: an upwind texel landed here,
         // blue: tree, dark alpha 1: attribute rejected by its depth bits)
         c = c.replaceAll("gl_FragColor\\s*=\\s*([^;]+);", "gl_FragColor = pzSwView($1);");
         c = beforeMain(c, "vec4 pzSwView(vec4 c) { return vec4(mix(c.rgb, pzSwTint.rgb, pzSwTint.a * 0.7), c.a); }");
      }
      if (mv && !mvImg) {
         if (c.contains("gl_FragColor")) {
            // every use (the sprite filter's composite also reads and scales it): gl_FragData[0]; the motion right after pzSway
            c = c.replaceAll("\\bgl_FragColor\\b", "gl_FragData[0]");
            mvOutTail = true;
            mvOutFragData = true;
         } else {
            java.util.regex.Matcher om = java.util.regex.Pattern.compile("(layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*)?out\\s+vec4\\s+(\\w+)\\s*;").matcher(c);
            if (!om.find()) {
               return null;
            }
            c = c.substring(0, om.start()) + "layout(location = 0) out vec4 " + om.group(2) + ";\nlayout(location = 1) out vec4 pzSwMvOut;" + c.substring(om.end());
            mvOutTail = true;
         }
      }
      int at = c.indexOf("void main");
      int brace = c.indexOf('{', at);
      if (brace < 0) {
         return null;
      }
      String call = "\n   pzSway();" + (mvOutTail ? (mvOutFragData ? "\n   gl_FragData[1] = vec4(pzSwMv, 0.0, 1.0);" : "\n   pzSwMvOut = vec4(pzSwMv, 0.0, 1.0);") : "");
      mvOutTail = false;
      mvOutFragData = false;
      return c.substring(0, brace + 1) + call + c.substring(brace + 1);
   }

   private static boolean mvOutTail, mvOutFragData;

   // ------------------------------------------------------------------------------------------------ render thread: trees

   /** A tree's amplitude at its top (0..1 of AMP_MAX_PX) for a sprite {@code heightPx} logical px tall. */
   public static float treeAmp(float heightPx) {
      return Math.min(1F, Math.max(0.1F, heightPx * 0.05F) / AMP_MAX_PX);
   }

   private static int treeProgram;
   private static boolean treeFailed;
   private static int treeMvp;
   private static int treeTex;
   private static int treeVbo;
   private static java.nio.FloatBuffer treeBuf;
   private static final java.nio.FloatBuffer MVP = BufferUtils.createFloatBuffer(16);
   private static final org.joml.Matrix4f MVP_M = new org.joml.Matrix4f();
   private static final int TREE_FLOATS = 12; // pos 2, uv 2, depth 1, colour 4, sway 3 (fraction of the height, amplitude, phase)

   private static final String TREE_VERT = String.join("\n",
      "#version 120",
      "attribute vec2 aPos; attribute vec2 aUv; attribute float aDepth; attribute vec4 aCol; attribute vec3 aSw;",
      "uniform mat4 uMvp;",
      "varying vec2 vUv; varying float vDepth; varying vec4 vCol; varying vec3 vSw;",
      "void main() { gl_Position = uMvp * vec4(aPos, 0.0, 1.0); vUv = aUv; vDepth = aDepth; vCol = aCol; vSw = aSw; }");

   // vboRenderer_PositionColorUVDepth's colour and depth, plus the sway attribute (class tree: the trunk's lower part stays)
   private static final String TREE_FRAG = String.join("\n",
      "#version 120",
      "uniform sampler2D uTex;",
      "varying vec2 vUv; varying float vDepth; varying vec4 vCol; varying vec3 vSw;",
      "void main() {",
      "   vec4 texel = texture2D(uTex, vUv);",
      "   float f = clamp((vSw.x - 0.12) / 0.88, 0.0, 1.0);",
      "   float q = floor(clamp(vSw.y * f * f, 0.0, 1.0) * 63.0 + 0.5);",
      "   float d16 = floor(clamp(vDepth, 0.0, 1.0) * 65535.0 + 0.5);",
      "   d16 = min(d16 - mod(d16, 2.0) + (q > 0.5 ? 1.0 : 0.0), 65534.0);", // the composite's free flag: the depth's lowest bit
      "   gl_FragDepth = d16 / 65535.0;",
      "   gl_FragData[0] = vCol * texel;",
      "   float db = d16 - 16.0 * floor(d16 / 16.0);",
      "   gl_FragData[1] = q < 0.5 ? vec4(0.0, db / 255.0, 0.0, 1.0) : vec4((q * 4.0 + 3.0) / 255.0, (vSw.z * 16.0 + db) / 255.0, 0.0, 1.0);",
      "}");

   private static boolean treeProgram() {
      if (treeProgram != 0 || treeFailed) {
         return treeProgram != 0;
      }
      int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
      GL20.glShaderSource(vs, TREE_VERT);
      GL20.glCompileShader(vs);
      int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(fs, OutlinePlantDepth.patchShader("pzoptPlantTree.frag", TREE_FRAG));
      GL20.glCompileShader(fs);
      int p = GL20.glCreateProgram();
      GL20.glAttachShader(p, vs);
      GL20.glAttachShader(p, fs);
      GL20.glBindAttribLocation(p, 0, "aPos");
      GL20.glBindAttribLocation(p, 1, "aUv");
      GL20.glBindAttribLocation(p, 2, "aDepth");
      GL20.glBindAttribLocation(p, 3, "aCol");
      GL20.glBindAttribLocation(p, 4, "aSw");
      GL20.glLinkProgram(p);
      boolean ok = GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != 0;
      if (!ok) {
         Log.warn("foliage sway: the tree program does not link: " + GL20.glGetShaderInfoLog(vs, 2048) + " / " + GL20.glGetShaderInfoLog(fs, 2048)
            + " / " + GL20.glGetProgramInfoLog(p, 2048));
         GL20.glDeleteProgram(p);
         treeFailed = true;
      } else {
         treeProgram = p;
         treeMvp = GL20.glGetUniformLocation(p, "uMvp");
         treeTex = GL20.glGetUniformLocation(p, "uTex");
         treeVbo = GL15.glGenBuffers();
      }
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      return ok;
   }

   /**
    * Render thread, TreeBake.Drawer.render (its depth and alpha state set): the baked trees drawn with their sway
    * attributes into both attachments. False: not baking with foliage sway (the drawer draws them itself).
    */
   public static boolean drawTrees(Texture[] textures, float[] v, int count) {
      if (cur == null || !Config.FOLIAGE_SWAY || !supported() || count == 0 || !treeProgram()) {
         return false;
      }
      if (curAux == null) {
         curAux = create(cur);
         if (curAux == null) {
            return false;
         }
      }
      auxOn();
      if (curAux.pendingClear) {
         clearAux();
      }
      curAux.any = true;
      int need = count * 6 * TREE_FLOATS;
      if (treeBuf == null || treeBuf.capacity() < need) {
         treeBuf = BufferUtils.createFloatBuffer(Math.max(need, 4096));
      }
      java.nio.FloatBuffer b = treeBuf;
      b.clear();
      int st = TreeBake.STRIDE;
      for (int n = 0; n < count; n++) {
         Texture t = textures[n];
         int i = n * st;
         float x0 = v[i], y0 = v[i + 1], x1 = v[i + 2], y1 = v[i + 3], dT = v[i + 4], dB = v[i + 5];
         float u0 = t == null ? 0F : t.getXStart(), u1 = t == null ? 0F : t.getXEnd(), t0 = t == null ? 0F : t.getYStart(), t1 = t == null ? 0F : t.getYEnd();
         float fT = v[i + 10], fB = v[i + 11], amp = v[i + 12], ph = v[i + 13];
         if (amp > 0F) {
            float r = amp * AMP_MAX_PX * strengthNow() * 1.3F + 2F;
            mark(curAux.mask, curAux.mw, curAux.mh, cur.w, cur.highRes, Math.min(x0, x1) - r, Math.min(y0, y1) - r, Math.max(x0, x1) + r, Math.max(y0, y1) + r);
            curAux.maskDirty = true;
         }
         // two triangles: (x0,y0) (x1,y0) (x1,y1) / (x0,y0) (x1,y1) (x0,y1)
         vtx(b, x0, y0, u0, t0, dT, v, i, fT, amp, ph);
         vtx(b, x1, y0, u1, t0, dT, v, i, fT, amp, ph);
         vtx(b, x1, y1, u1, t1, dB, v, i, fB, amp, ph);
         vtx(b, x0, y0, u0, t0, dT, v, i, fT, amp, ph);
         vtx(b, x1, y1, u1, t1, dB, v, i, fB, amp, ph);
         vtx(b, x0, y1, u0, t1, dB, v, i, fB, amp, ph);
      }
      b.flip();
      GL20.glUseProgram(treeProgram);
      OutlinePlantDepth.material(true); // Tree batches bypass VBORenderer; keep them in cached occlusion depth.
      org.joml.Matrix4f prj = Core.getInstance().projectionMatrixStack.isEmpty() ? null : Core.getInstance().projectionMatrixStack.peek();
      org.joml.Matrix4f mv = Core.getInstance().modelViewMatrixStack.isEmpty() ? null : Core.getInstance().modelViewMatrixStack.peek();
      if (prj == null || mv == null) {
         MVP_M.identity();
      } else {
         MVP_M.set(prj).mul(mv);
      }
      MVP.clear();
      MVP_M.get(MVP);
      GL20.glUniformMatrix4fv(treeMvp, false, MVP);
      GL20.glUniform1i(treeTex, 0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, treeVbo);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, b, GL15.GL_STREAM_DRAW);
      int stride = TREE_FLOATS * 4;
      GL20.glEnableVertexAttribArray(0);
      GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
      GL20.glEnableVertexAttribArray(1);
      GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 8L);
      GL20.glEnableVertexAttribArray(2);
      GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 16L);
      GL20.glEnableVertexAttribArray(3);
      GL20.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, stride, 20L);
      GL20.glEnableVertexAttribArray(4);
      GL20.glVertexAttribPointer(4, 3, GL11.GL_FLOAT, false, stride, 36L);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glEnable(GL11.GL_TEXTURE_2D);
      int first = 0;
      while (first < count) {
         Texture t = textures[first];
         int last = first + 1;
         while (last < count && textures[last] == t) {
            last++;
         }
         if (t != null && t.getTextureId() != null) {
            t.getTextureId().bind();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, first * 6, (last - first) * 6);
            treeQuads += last - first;
         }
         first = last;
      }
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
      // what VBORenderer.flush leaves bound (ShaderProgram.End), through the game's cache: the raw glUseProgram here left
      // ShaderHelper naming the program bound before the trees (its next start skipped, its uniform locations used on
      // the default shader); with it the flip drew whole white frames while walking past trees (2026-09-28)
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      zombie.core.ShaderHelper.glUseProgramObjectARB(0);
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      auxOff(); // (the next patched draw turns it back on)
      TreeBake.quadsDrawn += count;
      return true;
   }

   private static void vtx(java.nio.FloatBuffer b, float x, float y, float u, float t, float d, float[] v, int i, float frac, float amp, float ph) {
      b.put(x).put(y).put(u).put(t).put(d).put(v[i + 6]).put(v[i + 7]).put(v[i + 8]).put(v[i + 9]).put(frac).put(amp).put(ph);
   }

   // ------------------------------------------------------------------------------------------------ status

   public static String stats() {
      return "foliage sway: on=" + frameOn + " foliageDraws=" + foliageDraws + " rigidDraws=" + rigidDraws + " bakesWithFoliage=" + bakesWithFoliage
         + " aux=" + BY_INDEX.size() + " (" + (auxBytes >> 20) + " MB, created " + auxCreated + ", freed " + auxFreed + " of them " + auxReleasedEmpty + " empty bakes, " + auxEvicted + " evicted; cleared " + auxCleared + ")"
         + " composite draws=" + compositeDraws + " with sway=" + compositeSwayDraws + " variant=" + variantDraws + " twin=" + twinDraws + " mvFrames=" + mvFrames + " mvClears=" + mvClears + " pushQueued=" + pushQueued + " pushers=" + PUSH_BY_ID.size() + " treeQuads=" + treeQuads;
   }
}
