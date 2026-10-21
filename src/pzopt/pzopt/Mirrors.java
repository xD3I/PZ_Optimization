package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.properties.PropertyContainer;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;
import zombie.vehicles.BaseVehicle;

/**
 * Mirror and window reflections (key {@code mirrors}, 2026-10-03).
 *
 * <p>The iso camera is orthographic and looks down at 30 degrees, so the mirror of every view ray about a vertical plane is
 * the same ray for every pixel: a mirror on a north wall (facing +y) sends the view ray off along world (-1, +1, -1/3) per
 * square, which on screen is a straight line of slope 1/2 down-left (down-right for a west-wall plane), and along it the
 * iso depth w = x + y + 2z falls by 2/3 a square. A reflected ray from a pane at height h reaches the floor after 3 h
 * squares: wall mirrors show the floor and whoever stands within two squares of them, an upper-floor window the street
 * four to six squares out.
 *
 * <p>Per frame:
 * <ol>
 * <li>capture: windows and mirror tiles are drawn per frame (the mirrors moved out of the chunk textures); while one draws,
 * TextureDraw.Create hands its quad and texture here; the quad is kept in camera-free iso units (u = x - y,
 * v = x + y - 6z).</li>
 * <li>static pass, right after the chunk composite (the frame holds the static world alone), at most every
 * mirrorsStaticEvery frames: the panes captured last frame whose atlas tile is new, came further on screen or is due for
 * its staggered refresh. Every glass pixel marches its reflected ray along the screen line through the world depth (floor
 * first, then taps and bisection) and imageStores colour + hit distance into the pane's tile of an RGBA8 atlas, its glass
 * mask into an R8 atlas. The ortho camera's reflected rays do not move with a pan: a tile holds while the scene does.</li>
 * <li>model pass, right after the moving objects: every character / vehicle a visible pane can see is drawn once more,
 * its model view post-multiplied by the plane's reflection (its lighting stays its own), into an offscreen layer; kept
 * for a few frames while nothing moved.</li>
 * <li>composite, after each level's translucent objects (where the panes themselves were drawn): one draw; the hardware
 * depth test at the pane's analytic depth hides whatever stands in front, then the tile's reflection and the model layer
 * (nearer hit wins: the layer depth gives the model's distance) blend over the glass. Every 8th frame it also marks which
 * panes have pixels on screen (read back by the game thread from a persistent buffer three frames later, no sync): hidden
 * panes are neither marched nor mirror anyone.</li>
 * </ol>
 * Measurements and the variants tried: docs/findings-mirrors-2026-10-03.md.
 */
public final class Mirrors {
   private Mirrors() {
   }

   static volatile boolean failed;
   private static boolean frameOn, altLogged;
   private static int skipNow;
   private static long frames;

   public static boolean active() {
      return Config.MIRRORS && !failed && Overrides.enabled() && !CoreGl.legacyMac();
   }

   private static final String[] CYCLE = Config.DEV_MIRRORS_CYCLE.isBlank() ? new String[0] : Config.DEV_MIRRORS_CYCLE.split(",");

   /** dev: the alternation / cycle entry of this moment (-1: no cycle). */
   private static int cycleIndex() {
      int ms = Config.DEV_MIRRORS_ALTERNATE;
      return ms <= 0 || CYCLE.length == 0 ? -1 : (int)(System.currentTimeMillis() / ms % CYCLE.length);
   }

   private static boolean altOn() {
      int ms = Config.DEV_MIRRORS_ALTERNATE;
      int c = cycleIndex();
      if (c >= 0) {
         return !"off".equals(CYCLE[c].trim());
      }
      return ms <= 0 || (System.currentTimeMillis() / ms & 1L) == 0L;
   }

   /** The skip bits now: devMirrorsSkip, or the cycle's entry. */
   static int skipNow() {
      return skipNow;
   }

   static int skip() {
      int c = cycleIndex();
      if (c >= 0 && !"off".equals(CYCLE[c].trim())) {
         return Integer.parseInt(CYCLE[c].trim());
      }
      return Config.DEV_MIRRORS_SKIP;
   }

   static String section(String name) {
      int c = cycleIndex();
      if (c >= 0) {
         return name + ".c" + c;
      }
      return Config.DEV_MIRRORS_ALTERNATE <= 0 ? name : name + (altOn() ? ".mon" : ".moff");
   }

   // ------------------------------------------------------------------------------------------------ mirror tiles

   private static final float[] NONE = new float[0];
   private static final ConcurrentHashMap<IsoSprite, float[]> SPRITES = new ConcurrentHashMap<>();
   private static HashMap<String, Integer> maskCells;
   private static int maskRows = 1;

   /** A mirror tile the camera sees the glass of: {axis (0 north wall, 1 west wall), plane offset from the wall, mask cell}, else null. */
   static float[] mirrorInfo(IsoSprite s) {
      return Config.MIRRORS_WALL_MIRRORS ? mirrorTileInfo(s) : null; // (mirrorsWallMirrors off: no mirror tile is per frame, captured or held in the cutaway)
   }

   /** {@link #mirrorInfo} whether or not wall mirrors reflect (the harness rigs find the mirrors with it). */
   static float[] mirrorTileInfo(IsoSprite s) {
      if (s == null) {
         return null;
      }
      float[] v = SPRITES.get(s);
      if (v == null) {
         v = compute(s);
         SPRITES.put(s, v);
      }
      return v.length == 0 ? null : v;
   }

   private static float[] compute(IsoSprite s) {
      PropertyContainer p = s.getProperties();
      if (p == null || !p.has("IsMirror") || s.getName() == null) {
         return NONE;
      }
      String facing = p.get("Facing");
      int axis = "S".equals(facing) ? 0 : "E".equals(facing) ? 1 : -1;
      if (axis < 0) {
         return NONE; // N / W facing: the camera sees the back
      }
      Integer cell = masks().get(s.getName());
      if (cell == null) {
         return NONE;
      }
      String n = s.getName();
      // where the glass stands in front of its wall (squares): a medicine cabinet's door, a dresser's mirror, a framed pane
      float off = n.startsWith("fixtures_bathroom") ? 0.22F : n.startsWith("furniture_storage") ? 0.12F : 0.03F;
      return new float[] {axis, off, cell};
   }

   /** Is this tile drawn per frame for the mirrors (out of the chunk textures)? FBORenderCell's per-frame tile test. */
   public static boolean perFrame(IsoSprite s) {
      return Config.MIRRORS && !failed && Overrides.enabled() && (mirrorInfo(s) != null || Props.perFrame(s));
   }

   private static synchronized HashMap<String, Integer> masks() {
      if (maskCells != null) {
         return maskCells;
      }
      HashMap<String, Integer> m = new HashMap<>();
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/mirrors/mirror-masks.txt");
         int max = 0;
         try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
               line = line.trim();
               if (line.isEmpty() || line.startsWith("#")) {
                  continue;
               }
               String[] parts = line.split("\\s+");
               int cell = Integer.parseInt(parts[1]);
               m.put(parts[0], cell);
               max = Math.max(max, cell);
            }
         }
         maskRows = max / 8 + 1;
         Log.info("mirrors: " + m.size() + " mirror tiles with glass masks");
      } catch (Throwable t) {
         Log.warn("mirrors: no mirror masks (" + t + "): wall mirrors stay stock");
      }
      maskCells = m;
      return m;
   }

   // ------------------------------------------------------------------------------------------------ capture (game thread)

   /** A reflector seen this frame: its pane's plane and its sprite quad in camera-free iso units. */
   static final class Refl {
      Object key;
      int axis;
      float c, z, area, alpha;
      float u0, v0, u1, v1, s0, t0, s1, t1, m0x, m0y, m1x, m1y;
      boolean mirror;
      Texture tex;
      Object room; // the room the pane hangs in (OUTDOORS outside; ANY_ROOM: a window, which looks out of its room)
      float light; // the pane's own light as drawn (its vertex colours' brightest channel)
      boolean seen; // the pane's square was ever seen by the player (else the game draws it black: undiscovered)
      int prop; // a reflective prop's class (pzopt.Props), 0 a window / mirror
      boolean propAlpha, flip; // the prop's glass is its translucent texels; its sprite drawn flipped (south and east faces swap)
      int sx, sy; // the prop's square
      boolean outside; // the prop stands outdoors: a top's ray that finds nothing shows the sky
      float topC = Float.NaN; // the prop's reflecting top (absolute level), NaN none: a plane for the mirrored models too
   }

   static final Object OUTDOORS = new Object(), ANY_ROOM = new Object();

   /** The room a mirror pane / a character stands in, for keeping each mirror's reflections to its own room. */
   static Object roomOf(IsoGridSquare sq) {
      if (sq == null) return OUTDOORS;
      zombie.iso.areas.IsoRoom room = sq.getRoom();
      return room == null ? OUTDOORS : room;
   }

   private static Object capRoom;
   private static boolean capSeen;

   private static ArrayList<Refl> cur = new ArrayList<>(), prev = new ArrayList<>();
   private static final ArrayList<Refl> POOL = new ArrayList<>();
   private static IdentityHashMap<Object, Refl> curKey = new IdentityHashMap<>(), prevKey = new IdentityHashMap<>();
   private static IsoObject capturing;
   public static boolean capturingNow; // (TextureDraw.Create's one test)
   private static int capAxis;
   private static float capC, capZ;
   private static boolean capMirror;
   private static float[] capInfo, capProp;
   private static Refl capRefl;
   private static float capAlpha = -1F; // an attached mirror's own alpha (its capture draw is transparent), else -1
   private static int lateFrom;

   /**
    * The mirror among a wall's attached sprites, else null. The map's wall mirrors (walls_decoration_01_*, 18 of the 24
    * masked tiles) are WallOverlay tiles: CellLoader adds them to their wall's attachedAnimSprite list instead of making
    * an object, so they bake with the wall and the object test never saw them (player save, 2026-10-04).
    */
   public static zombie.iso.sprite.IsoSpriteInstance attachedMirror(IsoObject o) {
      if (!Config.MIRRORS || failed || !Config.MIRRORS_WALL_MIRRORS) {
         return null;
      }
      return attachedMirrorTile(o);
   }

   /** {@link #attachedMirror} whether or not the mirrors are on (the harness rigs find the mirrors with it). */
   static zombie.iso.sprite.IsoSpriteInstance attachedMirrorTile(IsoObject o) {
      if (o == null) {
         return null;
      }
      ArrayList<zombie.iso.sprite.IsoSpriteInstance> a = o.getAttachedAnimSprite();
      if (a == null) {
         return null;
      }
      for (int i = 0, n = a.size(); i < n; i++) {
         zombie.iso.sprite.IsoSpriteInstance s = a.get(i);
         if (s != null && mirrorTileInfo(s.getParentSprite()) != null) {
            return s;
         }
      }
      return null;
   }

   // the cutaway flag each mirror-carrying wall was last drawn with (its bake): the reflection follows the wall on screen
   private static final java.util.WeakHashMap<IsoObject, Integer> DRAWN_CUT = new java.util.WeakHashMap<>();

   /** Game thread, FBORenderCell.renderMinusFloor_DoorOrWall: a wall drawn (into its chunk texture, or per frame) with this cut. */
   public static void wallDrawn(IsoObject wall, int cut) {
      if (Config.MIRRORS && !failed && Config.MIRRORS_DRAWN_CUT && attachedMirror(wall) != null) {
         DRAWN_CUT.put(wall, cut);
      }
   }

   /** The cut a wall mirror's wall shows on screen: the one it was last drawn with, else the live flag. */
   public static int drawnCut(IsoObject wall, int live) {
      if (!Config.MIRRORS_DRAWN_CUT) {
         return live;
      }
      Integer c = DRAWN_CUT.get(wall);
      return c == null ? live : c;
   }

   /** The cutaway bits (1 north, 2 west) of a square's walls that carry a mirror the camera sees: a map overlay or a mirror tile. */
   public static int mirrorWallBits(IsoGridSquare sq) {
      if (!Config.MIRRORS || failed || sq == null) {
         return 0;
      }
      int bits = 0;
      for (int i = 0, n = sq.getObjects().size(); i < n; i++) {
         IsoObject o = sq.getObjects().get(i);
         zombie.iso.sprite.IsoSpriteInstance att = attachedMirror(o);
         if (att != null) {
            bits |= att.getParentSprite().getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.attachedN) ? 1 : 2;
            continue;
         }
         float[] info = o == null ? null : mirrorInfo(o.getSprite());
         if (info != null) {
            bits |= info[0] == 0F ? 1 : 2;
         }
      }
      return bits;
   }

   // dev (devMirrorsLog): why each wall's mirror overlay was or was not captured this frame, logged when it changes
   private static final IdentityHashMap<IsoObject, String> DEV_ATT = new IdentityHashMap<>(), DEV_ATT_NOW = new IdentityHashMap<>();

   public static void devAttached(IsoObject wall, String why) {
      if (Config.DEV_MIRRORS_LOG && wall != null && wall.square != null && attachedMirror(wall) != null) {
         String had = DEV_ATT_NOW.get(wall);
         if (had == null || !had.startsWith("captured")) { // (a level group's other level passes the square too: a capture wins)
            DEV_ATT_NOW.put(wall, why);
         }
      }
   }

   private static void devAttachedFrame() {
      for (java.util.Map.Entry<IsoObject, String> e : DEV_ATT.entrySet()) {
         DEV_ATT_NOW.putIfAbsent(e.getKey(), "absent");
      }
      for (java.util.Map.Entry<IsoObject, String> e : DEV_ATT_NOW.entrySet()) {
         String old = DEV_ATT.put(e.getKey(), e.getValue());
         if (!e.getValue().equals(old)) {
            IsoGridSquare sq = e.getKey().square;
            zombie.iso.sprite.IsoSpriteInstance s = attachedMirror(e.getKey());
            boolean north = s != null && s.getParentSprite().getProperties().has(zombie.iso.SpriteDetails.IsoFlagType.attachedN);
            float ax = north ? sq.x + 0.5F : sq.x, ay = north ? sq.y : sq.y + 0.5F, az = sq.z + 0.4F; // the glass's middle, roughly
            IsoCamera.FrameState fs = IsoCamera.frameState;
            float zoom = fs.zoom > 0F ? fs.zoom : 1F;
            String at = String.format(java.util.Locale.ROOT, " screen %.0f,%.0f", (zombie.iso.IsoUtils.XToScreen(ax, ay, az, 0) - fs.offX) / zoom,
               (zombie.iso.IsoUtils.YToScreen(ax, ay, az, 0) - fs.offY) / zoom);
            Log.info("mirrors: dev attached " + (s != null ? s.getParentSprite().getName() : "?") + " on " + e.getKey().getSprite().getName() + " at " + sq.x + "," + sq.y + "," + sq.z
               + ": " + old + " -> " + e.getValue() + " (frame " + frames + at + ", epoch_ms=" + System.currentTimeMillis() + ")");
         }
      }
      DEV_ATT_NOW.clear();
   }

   /**
    * Game thread, FBORenderCell's animated-attachments pass: a wall's mirror overlay about to be drawn transparent, for its
    * quad (the baked copy is what shows; the reflection is composited over it like over a mirror object).
    */
   public static boolean beginCaptureAttached(IsoObject wall, zombie.iso.sprite.IsoSpriteInstance s) {
      if (!frameOn || wall == null || wall.square == null || s == null || curKey.containsKey(wall)) {
         return false;
      }
      float[] info = mirrorInfo(s.getParentSprite());
      if (info == null) {
         return false;
      }
      IsoGridSquare sq = wall.square;
      capAxis = (int)info[0];
      capC = (capAxis == 0 ? sq.y : sq.x) + info[1];
      capMirror = true;
      capInfo = info;
      capZ = sq.z;
      capAlpha = s.alpha;
      capRoom = roomOf(sq);
      capSeen = sq.isSeen(IsoCamera.frameState.playerIndex);
      capturing = wall;
      capturingNow = true;
      capRefl = null;
      return true;
   }

   /** Game thread, FBORenderCell.renderTranslucent: a window / mirror tile about to draw; its quads go to {@link #captured}. */
   /** tileRecordVisuals: whether beginCapture could take this object (no side effects; the recorder leaves it to the game thread). */
   public static boolean capturable(IsoObject o) {
      if (!frameOn || o == null || o.square == null) {
         return false;
      }
      if (o instanceof IsoWindow) {
         return Config.MIRRORS_WINDOWS;
      }
      return mirrorInfo(o.getSprite()) != null || Props.on() && (Config.DEV_MIRRORS_PROP_SKIP & 1) == 0 && Props.info(o.getSprite()) != null;
   }

   public static boolean beginCapture(IsoObject o) {
      if (!frameOn || o == null || o.square == null || curKey.containsKey(o)) {
         return false;
      }
      IsoGridSquare sq = o.square;
      if (o instanceof IsoWindow w) {
         if (!Config.MIRRORS_WINDOWS) {
            return false;
         }
         IsoCurtain curtain = w.HasCurtains();
         if (curtain != null && !curtain.IsOpen() && curtain.square == sq) {
            return false; // a closed curtain on the camera side of the pane (drawn over it in the same pass)
         }
         capAxis = w.getNorth() ? 0 : 1;
         capC = capAxis == 0 ? sq.y : sq.x;
         capMirror = false;
         capInfo = null;
      } else {
         float[] info = mirrorInfo(o.getSprite());
         if (info == null) {
            float[] p = Props.on() && (Config.DEV_MIRRORS_PROP_SKIP & 1) == 0 ? Props.info(o.getSprite()) : null; // (devMirrorsPropSkip 1: props not captured)
            if (p == null) {
               return false;
            }
            capProp = p;
            capAxis = (int)p[3]; // (its main vertical face, for the mirrored models; -1 a top: none)
            capC = capAxis < 0 ? 0F : (capAxis == 0 ? sq.y : sq.x) + p[4];
            // the gym's wall mirrors are silvered mirrors: the room rebuilt behind the glass, people placed as in a mirror
            capMirror = (int)p[0] == Props.MIRROR && capAxis >= 0 && Config.MIRRORS_PROP_MIRROR_AS_MIRROR;
            capInfo = null;
         } else {
            capAxis = (int)info[0];
            capC = (capAxis == 0 ? sq.y : sq.x) + info[1];
            capMirror = true;
            capInfo = info;
         }
      }
      capZ = sq.z;
      capRoom = capMirror ? roomOf(sq) : ANY_ROOM;
      capSeen = sq.isSeen(IsoCamera.frameState.playerIndex);
      capturing = o;
      capturingNow = true;
      capRefl = null;
      return true;
   }

   /** Game thread, TextureDraw.Create while a reflector draws: the largest quad with a texture is its sprite. */
   public static void captured(TextureDraw texd) {
      if (capturing == null || texd.tex == null) {
         return;
      }
      float xa = Math.min(Math.min(texd.x0, texd.x1), Math.min(texd.x2, texd.x3));
      float xb = Math.max(Math.max(texd.x0, texd.x1), Math.max(texd.x2, texd.x3));
      float ya = Math.min(Math.min(texd.y0, texd.y1), Math.min(texd.y2, texd.y3));
      float yb = Math.max(Math.max(texd.y0, texd.y1), Math.max(texd.y2, texd.y3));
      float area = (xb - xa) * (yb - ya);
      if (area <= 1F) {
         return;
      }
      if (capRefl != null && capRefl.tex != null && texd.tex.getID() == capRefl.tex.getID() && capRefl.tex != texd.tex) {
         // (another sub-texture of the same page: a different sprite, e.g. an overlay; the larger wins below)
      } else if (capRefl != null && capRefl.tex == texd.tex) {
         unionInto(capRefl, texd, xa, xb, ya, yb);
         return;
      }
      if (capRefl != null && area <= capRefl.area) {
         return;
      }
      Refl r = capRefl != null ? capRefl : POOL.isEmpty() ? new Refl() : POOL.remove(POOL.size() - 1);
      capRefl = r;
      r.key = capturing;
      r.area = area;
      r.axis = capAxis;
      r.c = capC;
      r.z = capZ;
      r.mirror = capMirror;
      r.tex = texd.tex;
      r.alpha = capAlpha >= 0F ? capAlpha : ((texd.col0 >>> 24) & 0xFF) / 255F;
      r.room = capRoom;
      r.seen = capSeen;
      r.light = Math.max(Math.max(channelMax(texd.col0), channelMax(texd.col1)), Math.max(channelMax(texd.col2), channelMax(texd.col3)));
      double a32 = 32.0 * Core.tileScale, a16 = 16.0 * Core.tileScale;
      float offX = IsoCamera.frameState.offX, offY = IsoCamera.frameState.offY;
      r.u0 = (float)((xa + offX) / a32);
      r.u1 = (float)((xb + offX) / a32);
      r.v0 = (float)((ya + offY) / a16);
      r.v1 = (float)((yb + offY) / a16);
      boolean flip = texd.flipped;
      r.s0 = flip ? texd.u1 : texd.u0;
      r.s1 = flip ? texd.u0 : texd.u1;
      r.t0 = texd.v0;
      r.t1 = texd.v2;
      r.prop = 0;
      r.topC = Float.NaN;
      if (capProp != null) {
         float[] p = capProp;
         r.prop = (int)p[0];
         r.propAlpha = p[2] > 0.5F;
         // the prop's flipped facing: the frame drawn mirrored, its faces turn over (a south face shows as an east one)
         r.flip = flip;
         r.sx = capturing.square.x;
         r.sy = capturing.square.y;
         r.outside = capturing.square.isOutside();
         r.topC = p[5] > 0F && Config.MIRRORS_PROP_TOP_MODELS ? capZ + p[5] : Float.NaN;
         if (flip && r.axis >= 0) {
            r.axis = 1 - r.axis;
            r.c = (r.axis == 0 ? r.sy : r.sx) + p[4];
         }
         Texture t = texd.tex;
         float wo = Math.max(1F, t.getWidthOrig()), ho = Math.max(1F, t.getHeightOrig());
         float fx0 = t.getOffsetX() / wo, fx1 = (t.getOffsetX() + t.getWidth()) / wo;
         float fy0 = t.getOffsetY() / ho, fy1 = (t.getOffsetY() + t.getHeight()) / ho;
         int cell = (int)p[1];
         float col = cell % Props.COLS, row = cell / Props.COLS;
         float mx0 = (col + fx0) / Props.COLS, mx1 = (col + fx1) / Props.COLS;
         r.m0x = flip ? mx1 : mx0;
         r.m1x = flip ? mx0 : mx1;
         r.m0y = (row + fy0) / Props.rows;
         r.m1y = (row + fy1) / Props.rows;
      } else if (capInfo != null) {
         Texture t = texd.tex;
         float wo = Math.max(1F, t.getWidthOrig()), ho = Math.max(1F, t.getHeightOrig());
         float fx0 = t.getOffsetX() / wo, fx1 = (t.getOffsetX() + t.getWidth()) / wo;
         float fy0 = t.getOffsetY() / ho, fy1 = (t.getOffsetY() + t.getHeight()) / ho;
         int cell = (int)capInfo[2];
         float col = cell % 8, row = cell / 8;
         float mx0 = (col + fx0) / 8F, mx1 = (col + fx1) / 8F;
         r.m0x = flip ? mx1 : mx0;
         r.m1x = flip ? mx0 : mx1;
         r.m0y = (row + fy0) / maskRows;
         r.m1y = (row + fy1) / maskRows;
      } else {
         r.m0x = r.m0y = 0F;
         r.m1x = r.m1y = -1F; // a window: its glass is the sprite's translucent texels
      }
   }

   private static float channelMax(int c) {
      return Math.max(Math.max(c & 0xFF, (c >>> 8) & 0xFF), (c >>> 16) & 0xFF) / 255F;
   }

   /**
    * How much of its reflection a pane shows by its own light: a pane in a room the player never saw is drawn black by the
    * game and shows none (it showed the lit room geometry and the floor as a bright panel in an undiscovered room); one
    * drawn near black (out of sight, unlit) fades out with it. (dev skip bit 8388608: always all of it, before 2026-10-08)
    */
   static float paneLight(Refl r) {
      if ((skipNow & 8388608) != 0) return 1F;
      if (!r.seen) return 0F;
      float t = Math.max(0F, Math.min(1F, (r.light - 0.02F) / 0.10F));
      return t * t * (3F - 2F * t);
   }

   /**
    * A prop's quad cut down to its reflective texels' box (the frame fractions of props.txt): the composite and the march run
    * over the glass alone (a TV's screen is a third of its sprite). The frame fraction under a point of the quad follows from
    * its atlas cell rect (linear, flipped sprites included), and the sprite / mask / iso rects shrink by the same parameters.
    */
   private static void tighten(Refl r, float[] p) {
      int cell = (int)p[1];
      float col = cell % Props.COLS, row = cell / Props.COLS;
      float x0 = r.m0x * Props.COLS - col, x1 = r.m1x * Props.COLS - col;
      float y0 = r.m0y * Props.rows - row, y1 = r.m1y * Props.rows - row;
      if (Math.abs(x1 - x0) < 1e-5F || Math.abs(y1 - y0) < 1e-5F) {
         return;
      }
      float a0 = (p[6] - x0) / (x1 - x0), a1 = (p[8] - x0) / (x1 - x0);
      float b0 = (p[7] - y0) / (y1 - y0), b1 = (p[9] - y0) / (y1 - y0);
      float alo = Math.max(0F, Math.min(a0, a1)), ahi = Math.min(1F, Math.max(a0, a1));
      float blo = Math.max(0F, Math.min(b0, b1)), bhi = Math.min(1F, Math.max(b0, b1));
      if (ahi <= alo || bhi <= blo) {
         return;
      }
      float u0 = r.u0, du = r.u1 - r.u0, s0 = r.s0, ds = r.s1 - r.s0, m0x = r.m0x, dmx = r.m1x - r.m0x;
      r.u0 = u0 + alo * du;
      r.u1 = u0 + ahi * du;
      r.s0 = s0 + alo * ds;
      r.s1 = s0 + ahi * ds;
      r.m0x = m0x + alo * dmx;
      r.m1x = m0x + ahi * dmx;
      float v0 = r.v0, dv = r.v1 - r.v0, t0 = r.t0, dt = r.t1 - r.t0, m0y = r.m0y, dmy = r.m1y - r.m0y;
      r.v0 = v0 + blo * dv;
      r.v1 = v0 + bhi * dv;
      r.t0 = t0 + blo * dt;
      r.t1 = t0 + bhi * dt;
      r.m0y = m0y + blo * dmy;
      r.m1y = m0y + bhi * dmy;
      r.area *= (ahi - alo) * (bhi - blo);
   }

   /** A second quad of the same sprite (a wall drawn as left / right halves): the reflector covers both. */
   private static void unionInto(Refl r, TextureDraw texd, float xa, float xb, float ya, float yb) {
      double a32 = 32.0 * Core.tileScale, a16 = 16.0 * Core.tileScale;
      float offX = IsoCamera.frameState.offX, offY = IsoCamera.frameState.offY;
      float u0 = (float)((xa + offX) / a32), u1 = (float)((xb + offX) / a32);
      float v0 = (float)((ya + offY) / a16), v1 = (float)((yb + offY) / a16);
      float su = (r.s1 - r.s0) / Math.max(1e-6F, r.u1 - r.u0), sv = (r.t1 - r.t0) / Math.max(1e-6F, r.v1 - r.v0);
      float mu = (r.m1x - r.m0x) / Math.max(1e-6F, r.u1 - r.u0), mv = (r.m1y - r.m0y) / Math.max(1e-6F, r.v1 - r.v0);
      float nu0 = Math.min(r.u0, u0), nu1 = Math.max(r.u1, u1), nv0 = Math.min(r.v0, v0), nv1 = Math.max(r.v1, v1);
      r.s0 += (nu0 - r.u0) * su;
      r.s1 += (nu1 - r.u1) * su;
      r.t0 += (nv0 - r.v0) * sv;
      r.t1 += (nv1 - r.v1) * sv;
      if (r.m1x >= 0F) {
         r.m0x += (nu0 - r.u0) * mu;
         r.m1x += (nu1 - r.u1) * mu;
         r.m0y += (nv0 - r.v0) * mv;
         r.m1y += (nv1 - r.v1) * mv;
      }
      r.u0 = nu0;
      r.u1 = nu1;
      r.v0 = nv0;
      r.v1 = nv1;
      r.area = (nu1 - nu0) * (nv1 - nv0) * (float)(a32 * a16);
   }

   public static void endCapture() {
      if (capturing != null && capRefl != null && capProp != null && Config.MIRRORS_PROP_TIGHT_QUADS && (Config.DEV_MIRRORS_PROP_SKIP & 2) == 0) {
         tighten(capRefl, capProp);
      }
      if (capturing != null && capRefl != null) {
         cur.add(capRefl);
         curKey.put(capturing, capRefl);
      }
      capturing = null;
      capturingNow = false;
      capRefl = null;
      capAlpha = -1F;
      capProp = null;
   }

   // ------------------------------------------------------------------------------------------------ the frame (game thread)

   static final int TEX = 10, MAXR = 256, MAXP = 32, MAX_BATCHES = 16;

   static final class Frame {
      final Ssr.View view = new Ssr.View();
      long serial;
      boolean on;
      // static pass: last frame's reflectors under this frame's camera
      final float[] sData = new float[MAXR * TEX * 4];
      final Texture[] sTex = new Texture[MAXR];
      int nS;
      // composites: this frame's reflectors, one batch per level
      final float[] lData = new float[MAXR * TEX * 4];
      final Tile[] lTile = new Tile[MAXR]; // the pane of each composite row (its visibility count comes back here)
      final String[] lName = new String[MAXR]; // dev (devMirrorsLog): each composite row's square, for the rects log
      final Texture[] lTex = new Texture[MAXR];
      int nL, nBatches;
      final LateDrawer[] late = new LateDrawer[MAX_BATCHES];
      // planes the models are mirrored in: axis, c (relative), lateral lo / hi (absolute), zlo, zhi, iso rect u0 v0 u1 v1 (relative)
      final float[] planes = new float[MAXP * 10];
      final boolean[] planeMirror = new boolean[MAXP]; // a mirror's plane (else windows only): people placed by mirrorsView*
      final float[] planeDrop = new float[MAXP]; // a mirror plane's viewDrop (paneDrop: lower for a full-length mirror)
      final Object[] planeRoom = new Object[MAXP]; // the room the plane's panes hang in (ANY_ROOM: windows), people elsewhere are not mirrored
      int nP;
      int modelsQueued, skip, nClear;
      final float[] skyTop = new float[4], sunTop = new float[4]; // an outdoor top's sky (rgb, on) and the sun in its mirror direction (rgb, cos to it)
      boolean glossy; // the static pass marches a glossy prop (steel, ceramic): the atlas's mip chain is rebuilt after it
      boolean silver; // a silvered mirror (a mirror tile, the gym's mirrors) among the frame's planes: people redrawn at mirrorsModelHz
      float ppu, ppv; // this frame's px per iso unit (a tile marched at another zoom is read scaled by the ratio)
      final int[] clear = new int[MAXR * 4]; // atlas tiles new this frame: cleared to "no reflection" before the march
      long modelSig;
      final StaticDrawer stat = new StaticDrawer();
      final ModelFlush flush = new ModelFlush();
      final MirrorGeometry.Batch geo = new MirrorGeometry.Batch(); // the due mirrors' rooms, drawn before the march

      Frame() {
         for (int i = 0; i < MAX_BATCHES; i++) {
            this.late[i] = new LateDrawer();
         }
         this.stat.f = this;
         this.flush.f = this;
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static Frame frame;

   /**
    * Render thread, at the top of a frame's cell render: a model queued after the previous frame's flush (or in a frame
    * without one) is dropped. Its slot data is released with that frame's state, so the next flush drew freed data:
    * "model pass failed, off: NullPointerException ... modelInstance is null" turned the mirrors off for the session
    * (player save, 2026-10-04).
    */
   private static final TextureDraw.GenericDrawer DROP_STALE = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         recyclePending();
      }
   };

   /** Game thread, FBORenderCell.performRenderTiles before the chunks: this frame's reflectors from last frame's capture. */
   public static void beginFrame(int playerIndex) {
      if (Config.MIRRORS && !failed) {
         SpriteRenderer.instance.drawGeneric(DROP_STALE);
      }
      if (Config.DEV_MIRRORS_LOG) {
         devAttachedFrame();
      }
      // last frame's capture becomes the static pass's list
      for (Refl r : prev) {
         r.key = null;
         r.tex = null;
         POOL.add(r);
      }
      prev.clear();
      ArrayList<Refl> t = prev;
      prev = cur;
      cur = t;
      IdentityHashMap<Object, Refl> tk = prevKey;
      prevKey = curKey;
      curKey = tk;
      curKey.clear();
      capturing = null;
      capturingNow = false;
      frameOn = false;
      if (Config.DEV_MIRRORS_ALTERNATE > 0 && !altLogged && active()) {
         altLogged = true;
         Log.info("mirrors: dev alternating every " + Config.DEV_MIRRORS_ALTERNATE + " ms from epoch_ms 0 (on first)" + (CYCLE.length > 0 ? ", cycle " + Config.DEV_MIRRORS_CYCLE : ""));
      }
      if (!active() || !altOn()) {
         frame = null;
         return;
      }
      frames++;
      skipNow = skip();
      Frame f = FRAMES[(int)(frames & 3)];
      frame = f;
      f.serial = frames;
      f.view.capture(playerIndex);
      f.nS = 0;
      f.nL = 0;
      f.nBatches = 0;
      f.nP = 0;
      f.modelsQueued = 0;
      f.silver = false;
      f.glossy = false;
      skyForTops(f);
      f.modelSig = Float.floatToIntBits(f.view.offX) * 31L + Float.floatToIntBits(f.view.offY) * 17L + Float.floatToIntBits(f.view.zoom);
      f.skip = skipNow;
      f.nL = 0;
      visGame(f);
      lateFrom = 0;
      frameOn = true;
      boolean staticPass = "pass".equals(Config.MIRRORS_STATIC);
      // the static reflection lives in each pane's own tile of the atlas: the ortho camera's reflected rays do not move with
      // a pan, so a tile holds while the scene does; a pane is marched when its tile is new, when more of it came on
      // screen, and every mirrorsStaticReuse frames (staggered: at most mirrorsRefreshBudget a frame, every 4th frame)
      float vw = lastVw > 0F ? lastVw : f.view.screenW, vh = lastVh > 0F ? lastVh : f.view.screenH;
      VPG[2] = vw;
      VPG[3] = vh;
      f.view.mapping(VPG, MAPG);
      float ppu = 1F / MAPG[0], ppv = 1F / Math.abs(MAPG[2]);
      f.ppu = ppu;
      f.ppv = ppv;
      // a zoom in motion keeps every pane's tile (read scaled by the composite); the zoom it settles at is marched once
      boolean zoomMoving = ppu != lastPpu || ppv != lastPpv;
      lastPpu = ppu;
      lastPpv = ppv;
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      int budget = Config.MIRRORS_REFRESH_BUDGET;
      int every = Math.max(1, Config.MIRRORS_STATIC_EVERY);
      // the pass's fixed cost (the frame's colour / depth made readable) is paid at most every mirrorsStaticEvery frames:
      // a new pane waits that long for its reflection
      boolean passFrame = frames % every == 0 || (skipNow & 256) != 0;
      float passPx = 0F;
      f.nClear = 0;
      f.geo.reset();
      boolean geometry = Config.MIRRORS_GEOMETRY && (skipNow & 131072) == 0;
      // frames the camera has stood still (a soft refresh waits for it: a march resamples the frame at the camera's offset)
      if (f.view.offX != lastCamX || f.view.offY != lastCamY) {
         camStill = 0;
         lastCamX = f.view.offX;
         lastCamY = f.view.offY;
      } else {
         camStill++;
      }
      // the static atlas holds one tile per pane at this zoom. Zoomed in among many panes (a motel's windows, glass and
      // fixtures at zoom 0.25: 36 panes, ~5.4 M px for a 4.2 M px atlas) it cannot hold them all. Before 2026-10-10 a full
      // atlas started over in the middle of a pass: the tiles handed out before the reset were void, those panes marched
      // again the next pass, the atlas filled and started over again (337 times in 900 frames), and the panes first in
      // line never kept a tile: a bathroom mirror showed only the stock glass (maintainer, 2026-10-09). Now the panes ask in
      // order of importance (silvered mirrors first, then the nearest to the screen's centre), a pane that does not fit
      // goes without a reflection, and the atlas starts over only at the top of a pass frame, when tiles of panes no longer
      // on screen hold the space a pane on screen waits for. (dev skip bit 134217728: the old way)
      ArrayList<Refl> order = prev;
      if ((skipNow & 134217728) == 0) {
         ORDER.clear();
         ORDER.addAll(prev);
         float cu = MAPG[1] + MAPG[0] * vw * 0.5F + ou, cv = MAPG[3] + MAPG[2] * vh * 0.5F + ov; // (the screen centre in pane units)
         ORDER.sort((a, b) -> Float.compare(rank(a, cu, cv), rank(b, cu, cv)));
         order = ORDER;
         if (staticPass && passFrame && !zoomMoving) {
            compactAtlas(order, ppu, ppv);
         }
      }
      for (Refl r : order) {
         Tile known = TILES.get(r.key);
         // visibility feedback: a pane the composite drew no pixel of (under a roof, behind a building) is neither marched
         // nor a reason to mirror anyone (dev bit 1024: off)
         if (known != null && known.visPx == 0 && known.visSerial >= 0L && frames - known.visSerial < 30 && (skipNow & 1024) == 0) {
            hiddenSkips++;
            continue;
         }
         if (r.prop != 0 && tooSmall(r, f.ppu, f.ppv)) {
            continue; // (a prop a few px wide zoomed out: its reflection would not show)
         }
         addPlane(f, r);
         f.silver |= r.mirror || r.prop == Props.MIRROR;
         if (!staticPass || f.nS >= MAXR) {
            continue;
         }
         if (!passFrame) {
            // marches, and so new tiles, only on a pass frame: a tile allocated between them replaced the pane's marched
            // one and the composite found nothing in it, so the reflection showed one frame in four while zooming
            continue;
         }
         Tile tl = tileFor(r, ppu, ppv, zoomMoving);
         if (tl == null || tl.ppu != ppu || tl.ppv != ppv) {
            continue; // (zooming: the kept tile, read scaled, until the zoom settles)
         }
         float pa = ((r.u0 - ou) - MAPG[1]) / MAPG[0], pb = ((r.u1 - ou) - MAPG[1]) / MAPG[0];
         float qa = ((r.v0 - ov) - MAPG[3]) / MAPG[2], qb = ((r.v1 - ov) - MAPG[3]) / MAPG[2];
         float area = Math.max(1F, Math.abs(pb - pa) * Math.abs(qb - qa));
         float ix = Math.max(0F, Math.min(vw, Math.max(pa, pb)) - Math.max(0F, Math.min(pa, pb)));
         float iy = Math.max(0F, Math.min(vh, Math.max(qa, qb)) - Math.max(0F, Math.min(qa, qb)));
         float vis = ix * iy / area;
         boolean fresh = tl.refreshed < 0L;
         // (dev views 5 and 6 paint the tiles themselves: re-marched every pass frame so a toggled picture is never the dev one)
         // a mirror in a room is re-marched when what its rays reach changed (MirrorGeometry.signature: the room's objects,
         // light, seen and cutaway state), not every mirrorsStaticReuse frames: each re-march resampled the frame at the
         // camera's new sub-pixel offset and the stand-ins jumped while the player walked (the reflection flickered as he
         // moved). A long age refresh stays as a safety net. Windows and outdoor mirrors keep the age refresh. (dev skip bit
         // 33554432: the age refresh for every pane, before 2026-10-08)
         // (each pane's room checked every 16 frames, staggered: a few dozen squares walked per check)
         // (a prop mirror, the gym's panels, is a wall mirror with a room too: on the prop age refresh alone its pane went
         // re-marched ~0.8 s after the player left the room, smeared meanwhile, 2026-10-16; dev skip2 bit 16: the age refresh)
         boolean roomPane = r.prop == 0 || r.prop == Props.MIRROR && r.mirror && (Config.DEV_MIRRORS_SKIP2 & 16) == 0;
         boolean check = passFrame && !fresh && roomPane && (skipNow & 33554432) == 0 && ((frames >> 2) + (tl.x >> 4) + (tl.y >> 4) & 3) == 0;
         long sig = check ? MirrorGeometry.signature(r) : 0L;
         long soft = MirrorGeometry.SIG[1];
         // objects or the seen state at once; the light and the cutaway (they change all the time while the player walks:
         // the vision cone's fade, the walls cut round him) at most every 120 frames
         // (soft: once the camera has stood still for 10 frames, or after 480 frames walking)
         boolean changed = sig != 0L && tl.sig != 0L && (sig != tl.sig || soft != tl.soft && frames - tl.refreshed >= 120 && (camStill >= 10 || frames - tl.refreshed >= 480));
         int reuse = tl.sig != 0L && (skipNow & 33554432) == 0 ? Config.MIRRORS_STATIC_REUSE * 20 : Config.MIRRORS_STATIC_REUSE; // (a pane with a room: its room check refreshes it)
         // a prop's reflection is refreshed less often (mirrorsPropStaticReuse): what it shows changes only when the world does
         if (r.prop != 0 && !(roomPane && tl.sig != 0L)) {
            reuse = Config.MIRRORS_PROP_STATIC_REUSE;
         }
         boolean due = passFrame && (fresh || vis > tl.vis + 0.05F || reuse <= 0 || (skipNow & 256) != 0 || Config.DEV_MIRRORS_VIEW == 5 || Config.DEV_MIRRORS_VIEW == 6
               || changed || frames - tl.refreshed >= reuse && budget-- > 0);
         if (!due) {
            continue;
         }
         // the pass's marched area capped (mirrorsStaticBudgetPct of the viewport's px): ten props new at once marched together
         // cost the flip a 0.4 ms frame (prop-v7); the rest wait for the next pass frames (the first pane of a pass always goes)
         float tpx = tl.w * tl.h * tl.scale * tl.scale;
         if ((r.prop == Props.STEEL || r.prop == Props.CERAMIC) && "bake".equals(Config.MIRRORS_PROP_GLOSS)) {
            tpx *= Math.max(1, Config.MIRRORS_PROP_GLOSS_RAYS); // (a glossy prop's texels march several rays)
         }
         if (Config.MIRRORS_STATIC_BUDGET_PCT > 0 && passPx > 0F && passPx + tpx > Config.MIRRORS_STATIC_BUDGET_PCT / 100F * vw * vh && (skipNow & 256) == 0) {
            budgetHolds++;
            continue;
         }
         passPx += tpx;
         f.glossy |= r.prop == Props.STEEL || r.prop == Props.CERAMIC;
         if (fresh && f.nClear < MAXR) {
            f.clear[f.nClear * 4] = tl.x;
            f.clear[f.nClear * 4 + 1] = tl.y;
            f.clear[f.nClear * 4 + 2] = tl.w;
            f.clear[f.nClear * 4 + 3] = tl.h;
            f.nClear++;
         }
         tl.refreshed = frames;
         tl.vis = vis;
         if (changed) {
            lightMarches++;
         }
         tl.light = r.light;
         if (sig != 0L || tl.sig == 0L) {
            tl.sig = sig != 0L ? sig : passFrame && roomPane && (skipNow & 33554432) == 0 ? MirrorGeometry.signature(r) : 0L;
            tl.soft = MirrorGeometry.SIG[1];
         }
         int geo = geometry ? MirrorGeometry.collect(f.geo, r, tl) : 0;
         pack(f, r, f.sData, f.nS, false);
         f.sData[f.nS * TEX * 4 + 28] = geo > 0 ? 1F : 0F;
         boolean closed = geo > 0 && MirrorGeometry.roomClosed(r);
         if (closed) {
            f.sData[f.nS * TEX * 4 + 29] = -f.sData[f.nS * TEX * 4 + 29]; // (the room closed to the camera: withGeom takes the geometry)
         }
         if (Config.DEV_MIRRORS_LOG && closed != tl.closed) {
            Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev pane %s (axis %d, c %.2f): room %s to the camera epoch_ms=%d", r.key instanceof zombie.iso.IsoObject ko && ko.square != null ? ko.square.x + "," + ko.square.y + "," + ko.square.z : "?", r.axis, r.c, closed ? "closed" : "open", System.currentTimeMillis()));
         }
         tl.closed = closed;
         f.sTex[f.nS++] = r.tex;
      }
      if (f.geo.n > 1) {
         MirrorGeometry.sort(f.geo);
      }
      if (frames % 1800 == 900) {
         Log.info(stats());
      }
      if (Config.DEV_MIRRORS_LOG && frames % 300 == 5) {
         StringBuilder sb = new StringBuilder("mirrors: frame " + frames + ", " + prev.size() + " reflectors, " + f.nP + " planes:");
         for (int i = 0; i < f.nP; i++) {
            int o = i * 10;
            sb.append(String.format(java.util.Locale.ROOT, " {%d c %.2f %.1f..%.1f %.1f..%.1f%s}", (int)f.planes[o], f.planes[o + 1], f.planes[o + 2], f.planes[o + 3], f.planes[o + 4], f.planes[o + 5], f.planeMirror[i] ? " mirror" : ""));
         }
         sb.append(" | models queued last frame ").append(devModelsQueued).append(" reflectors:");
         for (int i = 0; i < Math.min(prev.size(), 8); i++) {
            Refl r = prev.get(i);
            sb.append(String.format(java.util.Locale.ROOT, " [%s axis %d c %.2f z %.0f u %.2f..%.2f v %.2f..%.2f a %.2f]", r.mirror ? "mirror" : "window", r.axis, r.c, r.z, r.u0, r.u1, r.v0, r.v1, r.alpha));
         }
         Log.info(sb.toString());
      }
   }

   /**
    * The sky an outdoor prop's top shows where its ray finds nothing (mirrorsPropSky): its mirror direction is fixed, north-west
    * and 30 degrees up ((-1, -1, +0.816) a square in x east, y south, z up), so the skybox's gradient there, plus the sun when
    * it stands near that direction (a GGX-like lobe of the prop's roughness in the shader): a low evening sun in the north-west
    * is the only glint a flat top of the iso camera can show (south / east faces mirror downwards).
    */
   private static void skyForTops(Frame f) {
      f.skyTop[3] = 0F;
      f.sunTop[0] = f.sunTop[1] = f.sunTop[2] = f.sunTop[3] = 0F;
      if (!Config.MIRRORS_PROP_SKY || !Props.on()) {
         return;
      }
      try {
         zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
         zombie.iso.sprite.SkyBox sb = zombie.iso.sprite.SkyBox.getInstance();
         if (cm == null || sb == null) {
            return;
         }
         zombie.core.Color h = sb.getShaderSkyHColour(), l = sb.getShaderSkyLColour();
         float day = Math.max(0F, Math.min(1F, cm.getDayLightStrength()));
         float cloud = Math.max(0F, Math.min(1F, Math.max(cm.getCloudIntensity(), cm.getPrecipitationIntensity())));
         float fog = Math.max(0F, Math.min(1F, cm.getFogIntensity()));
         float grey = Math.max(fog, cloud * 0.7F), g = 0.5F * (0.25F + 0.75F * day);
         // 30 degrees up: halfway between the horizon colour and the zenith's
         f.skyTop[0] = (0.5F * (h.r + l.r)) * (1F - grey) + g * grey;
         f.skyTop[1] = (0.5F * (h.g + l.g)) * (1F - grey) + g * grey;
         f.skyTop[2] = (0.5F * (h.b + l.b)) * (1F - grey) + g * grey;
         f.skyTop[3] = 1F;
         Sky.update(-1F);
         if (Sky.sunElevDeg > 0.0) {
            double rx = -1.0 / 1.633, ry = -1.0 / 1.633, rz = 0.816 / 1.633;
            float cos = (float)(rx * Sky.sun[0] + ry * Sky.sun[1] + rz * Sky.sun[2]);
            float clear = Math.max(0F, 1F - 0.92F * cloud - 0.6F * fog);
            zombie.iso.weather.ClimateColorInfo gl = cm.getGlobalLight();
            zombie.core.Color ext = gl != null ? gl.getExterior() : null;
            float k = clear * Math.min(1F, (float)Sky.sunElevDeg / 4F) * 3F;
            f.sunTop[0] = (ext != null ? ext.r : 1F) * k;
            f.sunTop[1] = (ext != null ? ext.g : 1F) * k;
            f.sunTop[2] = (ext != null ? ext.b : 1F) * k;
            f.sunTop[3] = cos;
         }
      } catch (Throwable t) {
         f.skyTop[3] = 0F;
      }
   }

   /** A prop whose reflective box covers fewer than mirrorsPropMinPx px at this zoom (skipped by the march and the composite). */
   static boolean tooSmall(Refl r, float ppu, float ppv) {
      return Config.MIRRORS_PROP_MIN_PX > 0 && ppu > 0F && (r.u1 - r.u0) * ppu * (r.v1 - r.v0) * ppv < Config.MIRRORS_PROP_MIN_PX;
   }

   /** One reflector's rows (TEX texels of RGBA32F), relative to the frame's origin square. */
   private static void pack(Frame f, Refl r, float[] d, int i, boolean stat) {
      int o = i * TEX * 4;
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      d[o] = r.u0 - ou;
      d[o + 1] = r.v0 - ov;
      d[o + 2] = r.u1 - ou;
      d[o + 3] = r.v1 - ov;
      d[o + 4] = r.s0;
      d[o + 5] = r.t0;
      d[o + 6] = r.s1;
      d[o + 7] = r.t1;
      d[o + 8] = r.m0x;
      d[o + 9] = r.m0y;
      d[o + 10] = r.m1x;
      d[o + 11] = r.m1y;
      d[o + 12] = r.axis;
      d[o + 13] = r.c - (r.axis == 0 ? f.view.oy : f.view.ox);
      // the lowest floor a ray may reach. A mirror hangs in a room with its floor in front, a ground-floor pane has no level
      // under it: their rays end at their own floor (marched further, a ray that passed behind the bathtub "hit" the
      // cut-away outer wall's brick strip under the bathroom floor: a medicine cabinet showed a dark brown slab, maintainer's
      // save 2026-10-04). An upper-floor window looks down at the street below its level. (dev skip bit 32768: the old rule)
      d[o + 14] = (r.mirror || r.prop != 0 || r.z <= 0F) && (skipNow & 32768) == 0 ? r.z : Math.min(r.z, 0F) - 1F;
      d[o + 15] = r.z;
      d[o + 16] = r.prop != 0 ? Props.strength(r.prop) : (r.mirror ? Config.MIRRORS_MIRROR_PCT : Config.MIRRORS_WINDOW_PCT) / 100F;
      d[o + 17] = r.alpha * paneLight(r); // (a prop too: one the player cannot see, drawn near black, reflects nothing)
      d[o + 18] = Math.max(1, Config.MIRRORS_REACH);
      d[o + 19] = stat ? 1F : 0F;
      Tile t = r.key == null ? null : TILES.get(r.key);
      d[o + 20] = t == null ? 0F : t.x; // the pane's atlas tile
      d[o + 21] = t == null ? 0F : t.y;
      d[o + 22] = t == null ? 0F : t.w;
      d[o + 23] = t == null ? 0F : t.h;
      d[o + 24] = r.mirror ? 1F + viewLateral(true, skipNow) : 0F; // a mirror: 1 + where its people stand sideways of their image (viewLateral)
      d[o + 25] = t == null ? 1F : t.scale; // texels per px: 1, or 0.5 (a window's half-resolution march)
      d[o + 26] = t == null || t.ppu <= 0F || f.ppu <= 0F ? 1F : t.ppu / f.ppu; // tile px per px of this frame (1 unless the tile was marched at another zoom)
      d[o + 27] = t == null || t.ppv <= 0F || f.ppv <= 0F ? 1F : t.ppv / f.ppv;
      d[o + 28] = 0F; // the pane's room geometry is in the geometry atlas (static pass only; set by beginFrame)
      d[o + 29] = 0.25F; // a marched hit this much farther than the geometry is the ray passing behind it (squares)
      // a mirrored person's distance on the scale of the room behind the glass: the room is marched with the camera's drop
      // (the floor d squares out shows d / 3 levels up), people with viewDrop (d S levels up); scaled by 3 S their feet meet
      // the reflected floor where they stand on it instead of sinking into it (the floor covered the shins at S = 1/6)
      d[o + 30] = 3F * (r.mirror ? paneDrop(r, skipNow) : viewDrop(false, skipNow));
      d[o + 31] = Float.isNaN(r.topC) ? -1000F : r.topC; // a prop's top: the model layer's people mirrored in it show on its top texels
      // a prop: its class, alpha mode, top reach, flip; its square (relative)
      d[o + 32] = r.prop;
      d[o + 33] = (r.propAlpha ? 1F : 0F) + (r.outside ? 2F : 0F); // alpha mode + 2 outdoors
      d[o + 34] = Math.max(1, Config.MIRRORS_PROP_REACH);
      d[o + 35] = r.flip ? 1F : 0F;
      d[o + 36] = r.sx - f.view.ox;
      d[o + 37] = r.sy - f.view.oy;
      d[o + 38] = r.z;
      // the prop's roughness (blur / mip: blurred by the composite), negative when the static pass bakes it (mirrorsPropGloss=bake)
      d[o + 39] = r.prop == 0 || "off".equals(Config.MIRRORS_PROP_GLOSS) ? 0F : Props.roughness(r.prop) * ("bake".equals(Config.MIRRORS_PROP_GLOSS) ? -1F : 1F);
   }


   // ------------------------------------------------------------------------------------------------ the static atlas (game thread)

   static final int ATLAS = 2048;
   static final class Tile {
      Texture tex;
      int x, y, w, h, gen;
      float ppu, ppv, scale, vis;
      float light = -1F; // the pane's own light when it was last marched
      long sig, soft; // MirrorGeometry.signature (objects, seen) and its soft part (light, cutaway) at the last march (0: none, the age refresh)
      long refreshed = -1L;
      boolean closed; // its room was closed to the camera at the last march (MirrorGeometry.roomClosed)
      volatile int visPx = -1; // the pane's pixels the composite drew (visibility feedback, 1-3 frames late; -1 unknown)
      volatile long visSerial = -1L;
   }

   private static final IdentityHashMap<Object, Tile> TILES = new IdentityHashMap<>();
   private static final ArrayList<Refl> ORDER = new ArrayList<>();
   private static int shelfX, shelfY, shelfH, atlasGen;
   private static long usedArea; // px handed out since the atlas last started over
   static long atlasFullSkips;

   /** A pane's place in the tile queue: silvered mirrors first, then by the distance (pane units) to the screen's centre. */
   private static float rank(Refl r, float cu, float cv) {
      float du = 0.5F * (r.u0 + r.u1) - cu, dv = 0.5F * (r.v0 + r.v1) - cv;
      return (r.mirror || r.prop == Props.MIRROR ? 0F : 1e12F) + du * du + dv * dv;
   }

   private static int tileW(Refl r, float ppu) {
      return (int)Math.ceil((r.u1 - r.u0) * ppu) + 2;
   }

   private static int tileH(Refl r, float ppv) {
      return (int)Math.ceil((r.v1 - r.v0) * ppv) + 2;
   }

   /**
    * Top of a pass frame: start the atlas over when panes on screen wait for a tile that does not fit in what is left and
    * tiles of panes gone (or of an old size) hold at least an eighth of the atlas. Panes that do not fit after that go
    * without a tile (tileFor), so a crowded view settles instead of starting over every pass.
    */
   private static void compactAtlas(ArrayList<Refl> order, float ppu, float ppv) {
      long live = 0L, missing = 0L;
      for (Refl r : order) {
         Tile t = TILES.get(r.key);
         int w = tileW(r, ppu), h = tileH(r, ppv);
         if (t != null && t.gen == atlasGen && t.ppu == ppu && t.ppv == ppv && t.w >= w && t.h >= h && t.tex == r.tex) {
            live += (long)(t.w + 1) * (t.h + 1);
         } else if (w <= ATLAS && h <= ATLAS) {
            missing += (long)(w + 1) * (h + 1);
         }
      }
      long free = (long)ATLAS * ATLAS - usedArea;
      if (missing > 0L && missing > free * 0.9F && usedArea - live >= (long)ATLAS * ATLAS / 8) {
         atlasGen++;
         atlasResets++;
         TILES.clear();
         shelfX = shelfY = shelfH = 0;
         usedArea = 0L;
      }
   }
   static volatile float lastVw, lastVh;
   private static float lastPpu, lastPpv;
   private static final float[] VPG = new float[4], MAPG = new float[6];
   static long tileAllocs, atlasResets, tileMarches, hiddenSkips, budgetHolds;

   /**
    * The pane's tile at this frame's px per iso unit (a zoom change or a too small tile gets a new one; a full atlas starts
    * over). With {@code keep} (the zoom is still moving) a marched tile of the same sprite is returned at its own zoom:
    * the composite reads it scaled, and the atlas is not filled with a tile per pane every pass frame of a zoom.
    */
   private static Tile tileFor(Refl r, float ppu, float ppv, boolean keep) {
      Tile t = TILES.get(r.key);
      float scale = !r.mirror && r.prop != Props.MIRROR && Config.MIRRORS_WINDOW_HALF_RES ? 0.5F : 1F;
      int w = tileW(r, ppu), h = tileH(r, ppv); // (px of the quad: the colour and the glass mask texel per px)
      if (t != null && t.gen == atlasGen && t.ppu == ppu && t.ppv == ppv && t.scale == scale && t.w >= w && t.h >= h && t.tex == r.tex) {
         return t;
      }
      if (keep && t != null && t.gen == atlasGen && t.scale == scale && t.tex == r.tex && t.refreshed >= 0L) {
         return t;
      }
      if (w > ATLAS || h > ATLAS) {
         return null;
      }
      if (shelfX + w > ATLAS && shelfY + shelfH + h <= ATLAS) {
         shelfX = 0;
         shelfY += shelfH;
         shelfH = 0;
      }
      if (shelfX + w > ATLAS || shelfY + h > ATLAS) {
         if ((skipNow & 134217728) == 0) {
            atlasFullSkips++; // full: this pane goes without a reflection (compactAtlas starts over at the top of a pass)
            return null;
         }
         atlasGen++; // full: start over, every pane marched again (the old way)
         atlasResets++;
         TILES.clear();
         shelfX = shelfY = shelfH = 0;
         usedArea = 0L;
      }
      t = new Tile();
      t.x = shelfX;
      t.y = shelfY;
      t.w = w;
      t.h = h;
      t.gen = atlasGen;
      t.ppu = ppu;
      t.ppv = ppv;
      t.scale = scale;
      t.tex = r.tex; // (a smashed / opened window draws another sprite: a new tile, marched at once)
      shelfX += w + 1;
      shelfH = Math.max(shelfH, h + 1);
      usedArea += (long)(w + 1) * (h + 1);
      TILES.put(r.key, t);
      tileAllocs++;
      return t;
   }

   /** A reflector's lateral extent on its plane (absolute squares) and height range (levels): {lo, hi, zlo, zhi}. */
   static void paneExtent(Refl r, float[] out) {
      float c = r.c;
      float lo, hi;
      float zA, zB, zC, zD;
      if (r.axis == 0) {
         lo = r.u0 + c;
         hi = r.u1 + c;
         zA = (r.u0 + 2F * c - r.v0) / 6F;
         zB = (r.u1 + 2F * c - r.v1) / 6F;
         zC = (r.u0 + 2F * c - r.v1) / 6F;
         zD = (r.u1 + 2F * c - r.v0) / 6F;
      } else {
         lo = c - r.u1;
         hi = c - r.u0;
         zA = (2F * c - r.u0 - r.v0) / 6F;
         zB = (2F * c - r.u1 - r.v1) / 6F;
         zC = (2F * c - r.u0 - r.v1) / 6F;
         zD = (2F * c - r.u1 - r.v0) / 6F;
      }
      out[0] = lo;
      out[1] = hi;
      out[2] = Math.min(Math.min(zA, zB), Math.min(zC, zD));
      out[3] = Math.max(Math.max(zA, zB), Math.max(zC, zD));
   }

   /** Lateral extent of a reflector on its plane (absolute squares) and its height range (levels). */
   private static void addPlane(Frame f, Refl r) {
      if (!Float.isNaN(r.topC)) {
         addTopPlane(f, r);
      }
      if (r.axis < 0) {
         return; // (a prop without a vertical face: no plane for the mirrored models)
      }
      float c = r.c;
      float lo, hi;
      float zA, zB, zC, zD;
      if (r.axis == 0) {
         lo = r.u0 + c;
         hi = r.u1 + c;
         zA = (r.u0 + 2F * c - r.v0) / 6F;
         zB = (r.u1 + 2F * c - r.v1) / 6F;
         zC = (r.u0 + 2F * c - r.v1) / 6F;
         zD = (r.u1 + 2F * c - r.v0) / 6F;
      } else {
         lo = c - r.u1;
         hi = c - r.u0;
         zA = (2F * c - r.u0 - r.v0) / 6F;
         zB = (2F * c - r.u1 - r.v1) / 6F;
         zC = (2F * c - r.u0 - r.v1) / 6F;
         zD = (2F * c - r.u1 - r.v0) / 6F;
      }
      float zlo = Math.min(Math.min(zA, zB), Math.min(zC, zD)), zhi = Math.max(Math.max(zA, zB), Math.max(zC, zD));
      float drop = r.mirror ? paneDrop(r, zlo, zhi, skipNow) : 0F;
      float cRel = c - (r.axis == 0 ? f.view.oy : f.view.ox);
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      // one plane per room: panes of two rooms on one wall line are two planes (dev skip bit 4194304: merged whatever the
      // room, before 2026-10-08; the plane keeps its first pane's room so the people mirrored across rooms are still counted)
      Object room = r.room == null ? ANY_ROOM : r.room;
      boolean anyRoom = (skipNow & 4194304) != 0;
      for (int i = 0; i < f.nP; i++) {
         int o = i * 10;
         // (within 0.1 of a square: a sliding door's leaf runs 0.06 in front of its fixed pane on its own track; as two
         // planes each placed its own copy of the people, a doubled image over the leaf)
         if ((int)f.planes[o] == r.axis && Math.abs(f.planes[o + 1] - cRel) < 0.1F && zlo < f.planes[o + 5] + 0.5F && zhi > f.planes[o + 4] - 0.5F && (anyRoom || f.planeRoom[i] == room)
               && !(r.mirror && f.planeMirror[i] && f.planeDrop[i] != drop)) { // (one viewDrop per plane: its people are placed once)
            f.planes[o + 2] = Math.min(f.planes[o + 2], lo);
            f.planes[o + 3] = Math.max(f.planes[o + 3], hi);
            f.planes[o + 4] = Math.min(f.planes[o + 4], zlo);
            f.planes[o + 5] = Math.max(f.planes[o + 5], zhi);
            f.planes[o + 6] = Math.min(f.planes[o + 6], r.u0 - ou);
            f.planes[o + 7] = Math.min(f.planes[o + 7], r.v0 - ov);
            f.planes[o + 8] = Math.max(f.planes[o + 8], r.u1 - ou);
            f.planes[o + 9] = Math.max(f.planes[o + 9], r.v1 - ov);
            if (r.mirror && !f.planeMirror[i]) {
               f.planeDrop[i] = drop;
            }
            f.planeMirror[i] |= r.mirror;
            return;
         }
      }
      if (f.nP >= MAXP) {
         return;
      }
      f.planeMirror[f.nP] = r.mirror;
      f.planeDrop[f.nP] = drop;
      f.planeRoom[f.nP] = room;
      int o = f.nP++ * 10;
      f.planes[o] = r.axis;
      f.planes[o + 1] = cRel;
      f.planes[o + 2] = lo;
      f.planes[o + 3] = hi;
      f.planes[o + 4] = zlo;
      f.planes[o + 5] = zhi;
      f.planes[o + 6] = r.u0 - ou;
      f.planes[o + 7] = r.v0 - ov;
      f.planes[o + 8] = r.u1 - ou;
      f.planes[o + 9] = r.v1 - ov;
   }


   /**
    * A prop's top as a plane for the mirrored models (axis 2): {2, z (absolute level), x0, x1, y0, y1 (absolute: the prop's
    * square, a little wider), iso rect}. Tops at the same height that touch merge.
    */
   private static void addTopPlane(Frame f, Refl r) {
      float ou = f.view.ox - f.view.oy, ov = f.view.ox + f.view.oy;
      float x0 = r.sx - 0.1F, x1 = r.sx + 1.1F, y0 = r.sy - 0.1F, y1 = r.sy + 1.1F;
      for (int i = 0; i < f.nP; i++) {
         int o = i * 10;
         if (f.planes[o] == 2F && Math.abs(f.planes[o + 1] - r.topC) < 0.05F && x0 <= f.planes[o + 3] && x1 >= f.planes[o + 2] && y0 <= f.planes[o + 5] && y1 >= f.planes[o + 4]) {
            f.planes[o + 2] = Math.min(f.planes[o + 2], x0);
            f.planes[o + 3] = Math.max(f.planes[o + 3], x1);
            f.planes[o + 4] = Math.min(f.planes[o + 4], y0);
            f.planes[o + 5] = Math.max(f.planes[o + 5], y1);
            f.planes[o + 6] = Math.min(f.planes[o + 6], r.u0 - ou);
            f.planes[o + 7] = Math.min(f.planes[o + 7], r.v0 - ov);
            f.planes[o + 8] = Math.max(f.planes[o + 8], r.u1 - ou);
            f.planes[o + 9] = Math.max(f.planes[o + 9], r.v1 - ov);
            return;
         }
      }
      if (f.nP >= MAXP) {
         return;
      }
      f.planeMirror[f.nP] = false;
      f.planeRoom[f.nP] = ANY_ROOM; // (a prop's top, like a window: whoever stands behind it)
      int o = f.nP++ * 10;
      f.planes[o] = 2F;
      f.planes[o + 1] = r.topC;
      f.planes[o + 2] = x0;
      f.planes[o + 3] = x1;
      f.planes[o + 4] = y0;
      f.planes[o + 5] = y1;
      f.planes[o + 6] = r.u0 - ou;
      f.planes[o + 7] = r.v0 - ov;
      f.planes[o + 8] = r.u1 - ou;
      f.planes[o + 9] = r.v1 - ov;
   }

   /** Game thread, FBORenderCell after the chunk composite: the static pass over last frame's reflectors. */
   public static void afterComposite() {
      Frame f = frame;
      if (f == null || f.nS == 0 || (skipNow & 1) != 0) {
         return;
      }
      tileMarches += f.nS;
      GpuSections.begin(section("mirrors.static"));
      SpriteRenderer.instance.drawGeneric(f.stat);
      GpuSections.end(section("mirrors.static"));
   }

   /**
    * Where a person d squares in front of a plane shows on it: lateral + viewLateral d, height + viewDrop d. The game camera's
    * true reflection is (1, 1/3): d squares to the side and d / 3 levels up, so a player at the sink saw their head above
    * and beside the medicine cabinet (maintainer, 2026-10-04: "the cabinet mirror should show the character's head when the
    * character is in front of the sink"). Mirrors use mirrorsViewLateralPct / mirrorsViewDropPct of it (default 0, 50: the
    * person straight in front of where they stand, a little higher, as one sees oneself looking slightly down into a mirror;
    * at their own height the head stayed under the game's high-hung cabinets); windows keep the true one.
    */
   static float viewLateral(boolean mirror, int skip) {
      return !mirror || (skip & 1048576) != 0 ? 1F : Config.MIRRORS_VIEW_LATERAL_PCT / 100F;
   }

   static float viewDrop(boolean mirror, int skip) {
      return !mirror || (skip & (1048576 | 2097152)) != 0 ? 1F / 3F : Config.MIRRORS_VIEW_DROP_PCT / 300F;
   }

   /**
    * viewDrop of a mirror pane. A full-length mirror (its glass from the floor up, the gym's panels) keeps the whole person
    * in the glass: at the cabinets' d / 6 levels up, a person 1.4 squares out already lost their head over the glass's top
    * edge (the maintainer's gym, 2026-10-16). Its drop is lowered so the head (0.85 levels) reaches the glass's top 2 squares
    * out. Dev skip2 bit 2: every mirror at mirrorsViewDropPct.
    */
   static float paneDrop(Refl r, float zlo, float zhi, int skip) {
      float s = viewDrop(true, skip);
      if ((Config.DEV_MIRRORS_SKIP2 & 2) != 0 || (skip & (1048576 | 2097152)) != 0 || zlo - r.z > 0.15F || zhi - zlo < 0.8F) {
         return s;
      }
      return Math.min(s, Math.max(0F, zhi - r.z - 0.85F) / 2F);
   }

   static float paneDrop(Refl r, int skip) {
      float c = r.c, zA, zB, zC, zD;
      if (r.axis == 0) {
         zA = (r.u0 + 2F * c - r.v0) / 6F;
         zB = (r.u1 + 2F * c - r.v1) / 6F;
         zC = (r.u0 + 2F * c - r.v1) / 6F;
         zD = (r.u1 + 2F * c - r.v0) / 6F;
      } else {
         zA = (2F * c - r.u0 - r.v0) / 6F;
         zB = (2F * c - r.u1 - r.v1) / 6F;
         zC = (2F * c - r.u0 - r.v1) / 6F;
         zD = (2F * c - r.u1 - r.v0) / 6F;
      }
      return paneDrop(r, Math.min(Math.min(zA, zB), Math.min(zC, zD)), Math.max(Math.max(zA, zB), Math.max(zC, zD)), skip);
   }

   /**
    * Game thread, TextureDraw.drawModel: the planes (bit mask over the frame's planes) a character / vehicle shows in. A model
    * at distance d in front of a plane is seen at the pane's points (lateral + L d, height + S d, viewLateral / viewDrop);
    * those must fall on a pane.
    */
   public static void planesFor(ModelManager.ModelSlot slot, TextureDraw texd) {
      texd.pzoptMirrorPlanes = 0;
      Frame f = frame;
      if (f == null || f.nP == 0 || slot == null || !Config.MIRRORS_MODELS || (skipNow & 2) != 0 || f.modelsQueued >= Config.MIRRORS_MAX_MODELS) {
         return;
      }
      Object o = slot.character != null ? slot.character : slot.model != null ? slot.model.object : null;
      float x, y, z, r, h;
      if (o instanceof IsoGameCharacter c) {
         x = c.getX();
         y = c.getY();
         z = c.getZ();
         r = 0.6F;
         h = 0.85F;
      } else if (o instanceof BaseVehicle v) {
         x = v.getX();
         y = v.getY();
         z = v.getZ();
         r = 3.0F;
         h = 0.8F;
      } else {
         return;
      }
      float cdx = x - IsoCamera.frameState.camCharacterX, cdy = y - IsoCamera.frameState.camCharacterY;
      if (cdx * cdx + cdy * cdy > (float)Config.MIRRORS_MODEL_RANGE * Config.MIRRORS_MODEL_RANGE) {
         return;
      }
      int mask = 0;
      Object here = o instanceof IsoGameCharacter c0 ? roomOf(c0.getCurrentSquare()) : OUTDOORS;
      for (int i = 0; i < f.nP; i++) {
         int p = i * 10;
         if (f.planes[p] == 2F) {
            // a top at z = c: its reflected rays climb a third of a level a square going away from the camera (-1, -1): a
            // model t squares behind the top's point P is seen there when its body spans the ray's height c + t / 3
            float c = f.planes[p + 1];
            float t0 = Math.max(0.05F, 3F * (z - c)), t1 = Math.min(Config.MIRRORS_PROP_REACH, 3F * (z + h - c));
            if (t1 < t0 || x + t1 + r < f.planes[p + 2] || x + t0 - r > f.planes[p + 3] || y + t1 + r < f.planes[p + 4] || y + t0 - r > f.planes[p + 5]) {
               continue;
            }
            mask |= 1 << i;
            continue;
         }
         float cAbs = f.planes[p + 1] + (f.planes[p] == 0F ? f.view.oy : f.view.ox);
         float d = f.planes[p] == 0F ? y - cAbs : x - cAbs;
         float vl = viewLateral(f.planeMirror[i], skipNow), vs = f.planeMirror[i] ? f.planeDrop[i] : viewDrop(false, skipNow);
         float dMax = vs > 0.001F ? (f.planes[p + 5] - z) / vs : Config.MIRRORS_REACH;
         if (d < 0.05F - r * 0.5F || d > dMax + r + 0.5F) {
            continue;
         }
         float lat = f.planes[p] == 0F ? x + vl * d : y + vl * d;
         if (lat < f.planes[p + 2] - r || lat > f.planes[p + 3] + r) {
            continue;
         }
         float zi = z + Math.max(d, 0F) * vs;
         if (zi > f.planes[p + 5] || zi + h < f.planes[p + 4]) {
            continue;
         }
         if (f.planeRoom[i] != ANY_ROOM && f.planeRoom[i] != here) {
            // in front of the plane but in another room (a wall between): a mirror shows its own room only. Before
            // 2026-10-08 a person behind the wall of a mirror's room showed in it (dev skip bit 4194304: the old way)
            crossRoom++;
            if (Config.DEV_MIRRORS_LOG && crossRoomLogs < 40) {
               crossRoomLogs++;
               Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev %s at %.2f,%.2f,%.1f (%s) is in front of plane %d (axis %d, c %.2f, room %s), %s epoch_ms=%d", o instanceof IsoGameCharacter ? "character" : "vehicle", x, y, z,
                     roomName(here), i, (int)f.planes[p], cAbs, roomName(f.planeRoom[i]), (skipNow & 4194304) != 0 ? "MIRRORED (old way)" : "not mirrored", System.currentTimeMillis()));
            }
            if ((skipNow & 4194304) == 0) {
               continue;
            }
         }
         mask |= 1 << i;
      }
      if (Config.DEV_MIRRORS_LOG && frames % 300 == 4) {
         Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev model %s at %.2f,%.2f,%.2f planes mask %s", o.getClass().getSimpleName(), x, y, z, Integer.toBinaryString(mask)));
         if (devPlanesFrame != frames) {
            devPlanesFrame = frames;
            StringBuilder sb = new StringBuilder("mirrors: dev planes:");
            for (int i = 0; i < f.nP; i++) {
               int p = i * 10;
               float cA = f.planes[p] == 2F ? f.planes[p + 1] : f.planes[p + 1] + (f.planes[p] == 0F ? f.view.oy : f.view.ox);
               sb.append(String.format(java.util.Locale.ROOT, " [%d axis %d c %.3f lat %.2f..%.2f z %.2f..%.2f %s room %s]", i, (int)f.planes[p], cA, f.planes[p + 2], f.planes[p + 3], f.planes[p + 4], f.planes[p + 5],
                  f.planeMirror[i] ? "mirror" : "glass", roomName(f.planeRoom[i])));
            }
            Log.info(sb.toString());
         }
      }
      if (mask != 0) {
         texd.pzoptMirrorPlanes = mask;
         f.modelsQueued++;
         float ang = o instanceof IsoGameCharacter c2 ? c2.getAnimAngleRadians() : ((BaseVehicle)o).getAngleY();
         f.modelSig = f.modelSig * 1000003L + System.identityHashCode(o) + Float.floatToIntBits(x) * 31L + Float.floatToIntBits(y) * 17L + Float.floatToIntBits(z) * 7L
               + Float.floatToIntBits(ang) * 3L + mask + (skipNow & (1048576 | 2097152));
      }
   }

   static long crossRoom, unseenPaneFrames, lightMarches;
   private static float lastCamX = Float.NaN, lastCamY = Float.NaN;
   private static int camStill;
   private static int crossRoomLogs, devPaneLogs;
   private static long devPlanesFrame = -1;
   private static final IdentityHashMap<Object, Integer> DEV_PANE = new IdentityHashMap<>();

   static String roomName(Object room) {
      return room == OUTDOORS ? "outdoors" : room == ANY_ROOM ? "any" : room instanceof zombie.iso.areas.IsoRoom ir ? ir.getName() + "@" + ir.getRoomDef().getX() + "," + ir.getRoomDef().getY() + "," + ir.getRoomDef().getZ() : "?";
   }

   /** Game thread, FBORenderCell after the moving objects: the mirrored models into the layer. */
   private static long modelSigLast, modelFrameLast, modelNsLast;
   static long modelReuses;

   static int devModelsQueued;

   public static void afterMoving() {
      Frame f = frame;
      if (f != null) {
         devModelsQueued = f.modelsQueued;
      }
      if (f == null || f.modelsQueued == 0) {
         return;
      }
      // the same models in the same places under the same camera: last frame's layer still holds (renewed every mirrorsModelReuse frames)
      long now = System.nanoTime();
      boolean still = Config.MIRRORS_MODEL_REUSE > 1 && f.modelSig == modelSigLast && frames - modelFrameLast < Config.MIRRORS_MODEL_REUSE;
      // a rate cap even while they move: at 240 fps a mirrored model redrawn 120 times a second is 4 ms behind at most
      // glass alone (no silvered mirror in the frame: windows, glass props, screens, steel at 12-35 %) at mirrorsGlassModelHz:
      // the faint reflection of a walking person lags a few ms more, unseen
      int hz = f.silver || Config.MIRRORS_GLASS_MODEL_HZ <= 0 ? Config.MIRRORS_MODEL_HZ : Math.min(Config.MIRRORS_MODEL_HZ, Config.MIRRORS_GLASS_MODEL_HZ);
      boolean capped = hz > 0 && now - modelNsLast < 1_000_000_000L / hz && layerSerial >= 0 && (skipNow & 16384) == 0;
      f.flush.reuse = still || capped;
      if (f.flush.reuse) {
         modelReuses++;
         SpriteRenderer.instance.drawGeneric(f.flush); // (recycles the frame's queued draws, keeps the layer)
         return;
      }
      modelSigLast = f.modelSig;
      modelFrameLast = frames;
      modelNsLast = now;
      GpuSections.begin(section("mirrors.models"));
      SpriteRenderer.instance.drawGeneric(f.flush);
      GpuSections.end(section("mirrors.models"));
   }

   /** Game thread, FBORenderCell after a level's translucent objects: the composite over the reflectors that drew in it. */
   public static void afterTranslucent() {
      if (!Config.MIRRORS_COMPOSITE_ONCE) {
         composite();
      }
   }

   /** Game thread, FBORenderCell after every level's translucent objects: with mirrorsCompositeOnce, one composite for all. */
   public static void afterLevels() {
      if (Config.MIRRORS_COMPOSITE_ONCE) {
         composite();
      }
   }

   private static void composite() {
      Frame f = frame;
      if (f == null || lateFrom >= cur.size()) {
         return;
      }
      int from = lateFrom;
      lateFrom = cur.size();
      if ((skipNow & 4) != 0 || f.nBatches >= MAX_BATCHES) {
         return;
      }
      // sorted by texture so the composite binds each sprite page once
      ArrayList<Refl> batch = SORT;
      batch.clear();
      for (int i = from; i < cur.size(); i++) {
         batch.add(cur.get(i));
      }
      // props last: they draw in a second instanced draw (their occlusion is tested per texel against the frame's depth)
      // props after the panes, the glossy ones (steel, ceramic: their own blend) last
      batch.sort((a, b) -> group(a) != group(b) ? Integer.compare(group(a), group(b)) : Integer.compare(System.identityHashCode(a.tex), System.identityHashCode(b.tex)));
      int start = f.nL;
      for (Refl r : batch) {
         if (f.nL >= MAXR) {
            break;
         }
         if (r.prop != 0 && tooSmall(r, f.ppu, f.ppv)) {
            continue;
         }
         Tile t = TILES.get(r.key);
         f.lTile[f.nL] = t;
         pack(f, r, f.lData, f.nL, t != null && t.refreshed >= 0L && t.gen == atlasGen && (skipNow & 1) == 0);
         if ((skipNow & 67108864) != 0) {
            f.lData[f.nL * TEX * 4 + 29] = -1F; // (row 7 .y, the composite's copy only: the old occlusion test for the mirrored people)
         }
         if (!r.seen) {
            unseenPaneFrames++;
         }
         if (Config.DEV_MIRRORS_LOG && r.key != null) {
            // a pane's seen state / light / shown share whenever it changes (tenths)
            int state = (r.seen ? 1000 : 0) + Math.round(r.light * 10F) * 10 + Math.round(paneLight(r) * 9F);
            Integer last = DEV_PANE.put(r.key, state);
            if ((last == null || last != state) && devPaneLogs < 400) {
               devPaneLogs++;
               Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev pane %s %s %s: seen %b, light %.2f, reflection shown %.2f epoch_ms=%d", r.mirror ? "mirror" : r.prop != 0 ? "prop" : "window",
                     r.key instanceof IsoObject ko && ko.square != null ? ko.square.x + "," + ko.square.y + "," + ko.square.z : "?",
                     r.key instanceof IsoObject ko && ko.getSprite() != null ? ko.getSprite().getName() : "?", r.seen, r.light, paneLight(r), System.currentTimeMillis()));
            }
         }
         if (Config.DEV_MIRRORS_LOG) {
            f.lName[f.nL] = r.key instanceof IsoObject ko && ko.square != null ? ko.square.x + "," + ko.square.y + "," + ko.square.z : "?";
         }
         f.lTex[f.nL++] = r.tex;
      }
      if (f.nL == start) {
         return;
      }
      LateDrawer d = f.late[f.nBatches++];
      d.f = f;
      d.start = start;
      d.count = f.nL - start;
      int props = 0;
      for (int i = start; i < f.nL; i++) {
         if (f.lData[i * TEX * 4 + 32] > 0.5F) {
            props++;
         }
      }
      d.props = props;
      int glossy = 0;
      for (int i = start; i < f.nL; i++) {
         float c = f.lData[i * TEX * 4 + 32];
         if (c == Props.STEEL || c == Props.CERAMIC || c == Props.SCREEN) {
            glossy++;
         }
      }
      d.glossy = Config.MIRRORS_PROP_METAL_BLEND ? glossy : 0;
      d.last = true;
      if (f.nBatches > 1) {
         f.late[f.nBatches - 2].last = false; // (the render thread runs it later: this frame's queue is still being built)
      }
      GpuSections.begin(section("mirrors.late"));
      SpriteRenderer.instance.drawGeneric(d);
      GpuSections.end(section("mirrors.late"));
   }

   private static final ArrayList<Refl> SORT = new ArrayList<>();

   /** The composite's draw a reflector goes in: 0 panes, 1 props, 2 steel / ceramic / screens (the modulating blend). */
   private static int group(Refl r) {
      return r.prop == 0 ? 0 : Config.MIRRORS_PROP_METAL_BLEND && (r.prop == Props.STEEL || r.prop == Props.CERAMIC || r.prop == Props.SCREEN) ? 2 : 1;
   }

   // ------------------------------------------------------------------------------------------------ render thread: resources

   private static int staticProg, lateProg, latePropProg, vao, maskTex, staticTex, glassTex;
   private static final int[] DATA = new int[6]; // [frame slot (3) x pass (static, composites)]
   private static int layerFbo, layerColor, layerDepth, layerW, layerH;
   // the models mirrored in the props' tops: a layer of their own (a prop's top and its front face stand side by side on
   // screen, and a model's two images overlapped in one layer)
   private static int layerTopFbo, layerTopColor, layerTopDepth;
   private static boolean layerTopDrawn;
   private static int[] uS, uL, uLP;
   private static final FloatBuffer UP = BufferUtils.createFloatBuffer(MAXR * TEX * 4);
   private static int worldFbo = -1, worldColor, worldDepth;
   private static long vpAge = Long.MIN_VALUE;
   private static final HashMap<Integer, int[]> FBO_ATTACH = new HashMap<>();
   static long requeries;
   private static final int[] VPI = new int[4];
   private static final float[] VPF = new float[4];
   private static final float[] MAP = new float[6];
   static final int IMAGE_UNIT = 6;
   static final boolean VIS_ON = Config.MIRRORS_VISIBILITY;
   private static final ByteBuffer MISS = BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 0, (byte)255}).flip(); // "no reflection here"
   // visibility feedback (2026-10-03): the composite adds each pane's visible pixels into a persistently mapped, coherent
   // storage buffer (4 frame slots of MAXR counters); the game thread zeroes this frame's slot and reads the slot of three
   // frames ago straight from the mapping: no GL call, no wait (a slot the GPU has not finished reads as partial counts,
   // harmless for a "has it any pixel" heuristic). The first design read an R32UI texture back through a PBO + fence: 137 us
   // of render thread per composite (every sync / map call waits for NVIDIA's threaded driver).
   static final int VIS_SLOTS = 4, VIS_EVERY = 8;
   private static int visBuf, visDev; // host-visible readback ring (persistent, coherent); the counters the shader writes (device local)
   static volatile java.nio.IntBuffer visMap;
   private static long visReads;

   private static void visCreate() {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (!(caps.OpenGL44 || caps.GL_ARB_buffer_storage) || !(caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object)) {
         return;
      }
      int flags = org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT | GL30.GL_MAP_READ_BIT | GL30.GL_MAP_WRITE_BIT;
      visBuf = GL15.glGenBuffers();
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, visBuf);
      org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, (long)VIS_SLOTS * MAXR * 4L, flags);
      ByteBuffer m = GL30.glMapBufferRange(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0L, (long)VIS_SLOTS * MAXR * 4L, flags);
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
      // the shader writes device-local counters (writes into the coherent host mapping went over PCIe from every sampled
      // fragment: the composite grew 12 -> 300 us walking into a street of windows); once a frame they are copied over
      visDev = GL15.glGenBuffers();
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, visDev);
      org.lwjgl.opengl.GL44.glBufferStorage(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, (long)MAXR * 4L, 0);
      GL15.glBindBuffer(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 0);
      if (m != null) {
         java.nio.IntBuffer ib = m.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < VIS_SLOTS * MAXR; i++) {
            ib.put(i, 0);
         }
         visMap = ib;
         int block = org.lwjgl.opengl.GL43.glGetProgramResourceIndex(lateProg, org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BLOCK, "VisBuf");
         if (block >= 0) {
            org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(lateProg, block, 3);
         }
         int blockP = latePropProg == lateProg ? -1 : org.lwjgl.opengl.GL43.glGetProgramResourceIndex(latePropProg, org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BLOCK, "VisBuf");
         if (blockP >= 0) {
            org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(latePropProg, blockP, 3);
         }
         Log.info("mirrors: visibility feedback buffer (persistent, coherent) " + visBuf);
      }
   }

   /** Game thread, beginFrame: this frame's counters zeroed, the counts of three frames ago into their panes. */
   private static void visGame(Frame f) {
      java.nio.IntBuffer m = visMap;
      if (m == null) {
         return;
      }
      long s3 = f.serial - 3L;
      if (s3 < 0L || s3 % VIS_EVERY != 0) {
         return; // (only every VIS_EVERY-th frame wrote counts; read three frames after it)
      }
      Frame g = FRAMES[(int)(s3 & 3)];
      if (s3 < 0L || g.serial != s3) {
         return;
      }
      int gs = (int)(s3 % VIS_SLOTS);
      for (int i = 0; i < g.nL; i++) {
         Tile t = g.lTile[i];
         if (t != null) {
            t.visPx = m.get(gs * MAXR + i);
            t.visSerial = s3;
         }
      }
      visReads++;
   }

   private static final ByteBuffer ZERO4 = BufferUtils.createByteBuffer(4);
   private static final ByteBuffer NO_GLASS = BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 0, 0}).flip();
   /** The composite reads the frame in place (a texture barrier each): only for mirrorsStatic=late. */
   static final boolean LIVE_READS = "late".equals(Config.MIRRORS_STATIC);
   private static boolean FULL, STATIC_FBO, liveReads; // (decided once from the context's capabilities)
   private static int atlasFbo;

   private static final String[] UNIFORMS = {"Data", "WorldColor", "WorldDepth", "Sprite", "Masks", "StaticTex", "LayerColor", "LayerDepth", "mapA", "mapC", "vp", "march", "layerMap", "dev", "Static", "first", "depthTest", "Glass", "GlassTex", "Vis", "visRow", "visOn", "GeomTex", "PropTex", "StaticMip", "skyTop", "sunTop", "LayerTopColor", "LayerTopDepth", "HiZ", "gloss", "metal", "skip2"};

   private static boolean ensurePrograms() {
      if (staticProg != 0) {
         return true;
      }
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      boolean images = caps.OpenGL42 || caps.GL_ARB_shader_image_load_store;
      boolean barrier = caps.OpenGL45 || caps.GL_ARB_texture_barrier || caps.GL_NV_texture_barrier;
      // full: GL 4.3 + image stores + texture barriers + buffer storage (the static pass stores into the atlas images, the
      // composite marks visible panes); else the GL 4.1 path (macOS core): the static pass renders into the atlas
      // framebuffer, no visibility feedback, no live-read variant
      FULL = images && barrier && caps.OpenGL43 && (caps.OpenGL44 || caps.GL_ARB_buffer_storage) && !CoreGl.active;
      STATIC_FBO = !FULL || Config.MIRRORS_STATIC_FBO;
      liveReads = LIVE_READS && barrier;
      if (!caps.OpenGL33) {
         failed = true;
         Log.warn("mirrors: needs OpenGL 3.3 / 4.1 core; mirrors and window reflections off");
         return false;
      }
      Log.info("mirrors: " + (FULL ? "GL 4.3 path" : "GL 4.1 path") + ", static pass " + (STATIC_FBO ? "into the atlas framebuffer" : "image stores")
            + (LIVE_READS && !liveReads ? " (no texture barrier: mirrorsStatic=late runs as pass)" : ""));
      staticProg = AmbientOcclusion.link(STATIC_FBO ? vertTile(FULL) : vert(FULL), frag(STATIC_FBO ? K_STATIC_FBO : K_STATIC_IMAGE, FULL));
      lateProg = AmbientOcclusion.link(vert(FULL), frag(K_LATE, FULL));
      latePropProg = Config.MIRRORS_PROP_DEPTH_WRITE ? AmbientOcclusion.link(vert(FULL), frag(K_LATE_PROP, FULL)) : lateProg;
      if (staticProg == 0 || lateProg == 0 || latePropProg == 0) {
         failed = true;
         Log.warn("mirrors: shaders did not link; off");
         return false;
      }
      uS = new int[UNIFORMS.length];
      uL = new int[UNIFORMS.length];
      uLP = new int[UNIFORMS.length];
      for (int i = 0; i < UNIFORMS.length; i++) {
         uS[i] = GL20.glGetUniformLocation(staticProg, UNIFORMS[i]);
         uL[i] = GL20.glGetUniformLocation(lateProg, UNIFORMS[i]);
         uLP[i] = GL20.glGetUniformLocation(latePropProg, UNIFORMS[i]);
      }
      // the samplers' units never change: set once here, not per pass
      for (int[] u : new int[][] {uS, uL, uLP}) {
         GL20.glUseProgram(u == uS ? staticProg : u == uL ? lateProg : latePropProg);
         GL20.glUniform1i(u[0], 2);
         GL20.glUniform1i(u[1], 0);
         GL20.glUniform1i(u[2], 1);
         GL20.glUniform1i(u[3], 4);
         GL20.glUniform1i(u[4], 3);
         GL20.glUniform1i(u[5], 5);
         GL20.glUniform1i(u[6], 7);
         GL20.glUniform1i(u[7], 8);
         GL20.glUniform1i(u[14], IMAGE_UNIT);
         GL20.glUniform1i(u[17], IMAGE_UNIT + 1);
         GL20.glUniform1i(u[18], 6);
         GL20.glUniform1i(u[22], 15);
         GL20.glUniform1i(u[23], 9);
         GL20.glUniform1i(u[24], 10);
         GL20.glUniform1i(u[27], 11);
         GL20.glUniform1i(u[28], 12);
         GL20.glUniform1i(u[29], 13);
      }
      GL20.glUseProgram(0);
      vao = GL30.glGenVertexArrays();
      // one data texture per frame in flight and pass (static / composites): an upload never rewrites rows a queued draw
      // still reads (the driver would wait for it or copy the texture); the composites of a frame use disjoint rows
      for (int i = 0; i < DATA.length; i++) {
         DATA[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[i]);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, TEX, MAXR, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      }
      maskTex = loadMasks();
      if (VIS_ON && FULL) {
         visCreate();
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Log.info("mirrors: programs linked (static " + staticProg + ", composite " + lateProg + "), mask texture " + maskTex);
      return true;
   }

   private static int propTex, mipSampler;
   static final boolean GLOSS_ACCUM = "bake".equals(Config.MIRRORS_PROP_GLOSS) && Config.MIRRORS_PROP_GLOSS_ACCUM_PCT > 0;
   static final boolean GLOSS_MIP = Config.MIRRORS_PROP_GLOSS.equals("mip");
   static final int MIP_LEVELS = 5;
   static long mipBuilds;

   /** The prop atlas (pzopt.Props), loaded on first use. */
   static int propTex() {
      if (propTex == 0) {
         propTex = Props.loadAtlas();
      }
      return propTex;
   }

   private static int loadMasks() {
      int tex = GL11.glGenTextures();
      int w = 8, h = 8;
      ByteBuffer px = BufferUtils.createByteBuffer(w * h);
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/mirrors/mirror-masks.png");
         java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
         w = img.getWidth();
         h = img.getHeight();
         px = BufferUtils.createByteBuffer(w * h);
         java.awt.image.Raster r = img.getRaster();
         int[] row = new int[w];
         for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
               px.put((byte)row[x]);
            }
         }
         px.flip();
      } catch (Throwable t) {
         Log.warn("mirrors: mask texture unreadable: " + t);
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, px);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      return tex;
   }

   /** The world framebuffer (the game's tracked binding), its attachments and the viewport: re-read when it changes or every 120 frames. */
   private static boolean world() {
      int fbo = zombie.core.textures.TextureFBO.lastID;
      // a framebuffer / viewport query is a round trip to NVIDIA's threaded driver (hundreds of us): only when the framebuffer,
      // the offscreen size or the render scale changed (a re-query every 120 frames cost ~4.7 us a composite on average)
      long key = ((long)Core.getInstance().getOffscreenWidth(0) << 32 | Core.getInstance().getOffscreenHeight(0)) * 31L
            + Float.floatToIntBits(RenderScale.scaleX()) * 7L + Float.floatToIntBits(RenderScale.scaleY());
      if (key != vpAge) {
         vpAge = key;
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, VPF);
         lastVw = VPF[2];
         lastVh = VPF[3];
         FBO_ATTACH.clear(); // (a resize re-creates the framebuffers' textures)
         requeries++;
      }
      if (fbo != worldFbo) {
         worldFbo = fbo;
         int[] a = FBO_ATTACH.get(fbo); // attachments per framebuffer: other passes bind theirs between mine, the id alternates
         if (a == null) {
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            int c = attachment(GL30.GL_COLOR_ATTACHMENT0);
            int d = attachment(GL30.GL_DEPTH_STENCIL_ATTACHMENT);
            if (d == 0) {
               d = attachment(GL30.GL_DEPTH_ATTACHMENT);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            a = new int[] {c, d};
            FBO_ATTACH.put(fbo, a);
            requeries++;
            if (Config.DEV_MIRRORS_LOG) {
               Log.info("mirrors: world framebuffer " + fbo + " colour " + c + " depth " + d + " viewport " + VPI[0] + "," + VPI[1] + " " + VPI[2] + "x" + VPI[3]);
            }
         }
         worldColor = a[0];
         worldDepth = a[1];
      }
      return worldColor != 0 && worldDepth != 0 && VPI[2] > 0 && VPI[3] > 0;
   }

   /** glTextureBarrier through whichever entry point the context has (GL 4.5, ARB or NV). */
   private static void barrier() {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (caps.OpenGL45) {
         org.lwjgl.opengl.GL45.glTextureBarrier();
      } else if (caps.GL_ARB_texture_barrier) {
         org.lwjgl.opengl.ARBTextureBarrier.glTextureBarrier();
      } else {
         org.lwjgl.opengl.NVTextureBarrier.glTextureBarrierNV();
      }
   }

   private static int attachment(int attachment) {
      if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_TEXTURE) {
         return 0;
      }
      return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
   }

   private static void ensureStatic() {
      if (staticTex != 0) {
         return;
      }
      int w = ATLAS, h = ATLAS;
      glassTex = GL11.glGenTextures(); // each pane's glass mask (R) and its people's line of sight (G, losOut), written by the static pass (the composite reads no sprite)
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glassTex);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, w, h, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      staticTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
      if (GLOSS_MIP && FULL) {
         // mirrorsPropGloss=mip: a mip chain over the atlas, rebuilt after a pass that marched a glossy prop; the composite reads
         // a rough surface's reflection one level per doubling of its blur (a pre-convolved chain, one tap)
         GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, MIP_LEVELS, GL11.GL_RGBA8, w, h);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MIP_LEVELS - 1);
         mipSampler = GL33.glGenSamplers();
         GL33.glSamplerParameteri(mipSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
         GL33.glSamplerParameteri(mipSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL33.glSamplerParameteri(mipSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL33.glSamplerParameteri(mipSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      } else {
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      }
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      if (STATIC_FBO) {
         atlasFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, atlasFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, staticTex, 0);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, glassTex, 0);
         GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
         int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            failed = true;
            Log.warn("mirrors: atlas framebuffer incomplete (" + status + "); off");
         }
      }
      Log.info("mirrors: static reflection atlas " + w + "x" + h);
   }

   private static boolean ensureLayer() {
      int w = VPI[0] + VPI[2], h = VPI[1] + VPI[3];
      if (layerFbo != 0 && layerW == w && layerH == h) {
         return true;
      }
      if (layerFbo != 0) {
         GL30.glDeleteFramebuffers(layerFbo);
         GL11.glDeleteTextures(layerColor);
         GL11.glDeleteTextures(layerDepth);
      }
      layerColor = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColor);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      layerDepth = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerDepth);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer)null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      layerFbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, layerColor, 0);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, layerDepth, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         Log.warn("mirrors: model layer incomplete (" + status + "); models off");
         GL30.glDeleteFramebuffers(layerFbo);
         layerFbo = 0;
         return false;
      }
      layerW = w;
      layerH = h;
      Log.info("mirrors: model layer " + w + "x" + h);
      if (layerTopFbo != 0) {
         GL30.glDeleteFramebuffers(layerTopFbo);
         GL11.glDeleteTextures(layerTopColor);
         GL11.glDeleteTextures(layerTopDepth);
         layerTopFbo = 0;
      }
      if (Props.on()) {
         layerTopColor = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerTopColor);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         layerTopDepth = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerTopDepth);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, w, h, 0, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         layerTopFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerTopFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, layerTopColor, 0);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, layerTopDepth, 0);
         if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(layerTopFbo);
            layerTopFbo = 0;
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
      }
      return true;
   }

   /** Window px -> iso (relative to the frame's origin square), iso depth from window depth. */
   private static void mapping(Frame f) {
      f.view.mapping(VPF, MAP);
   }

   private static void commonUniforms(int[] u, Frame f) {
      GL20.glUniform4f(u[8], MAP[0], MAP[1], MAP[2], MAP[3]);
      GL20.glUniform4f(u[9], MAP[4], MAP[5], 0F, Math.max(1, Config.MIRRORS_REACH));
      GL20.glUniform4f(u[10], VPF[0], VPF[1], VPF[2], VPF[3]);
      GL20.glUniform4f(u[11], Math.max(4, Math.min(64, Config.MIRRORS_STEPS)), Config.MIRRORS_THICKNESS_PCT / 100F, Config.MIRRORS_STAND_IN_PCT / 100F, Math.max(2, Config.MIRRORS_STEP_PX));
      int view = Config.DEV_MIRRORS_VIEW_TOGGLE_MS > 0 && (System.currentTimeMillis() / Config.DEV_MIRRORS_VIEW_TOGGLE_MS & 1L) == 1L ? 0 : Config.DEV_MIRRORS_VIEW;
      GL20.glUniform4f(u[13], view, f.skip, "off".equals(Config.MIRRORS_STATIC) ? 0F : 1F, 0F);
      GL20.glUniform1i(u[32], Config.DEV_MIRRORS_SKIP2);
   }

   // the rows go up through a persistently mapped pixel-unpack ring (one region per data texture): the texture update then
   // reads a buffer, not client memory. Mesa's threaded GL waited for its driver thread on every client-memory
   // glTexSubImage2D: 289 us of render thread per composite on the flip (prop-v1, 2026-10-08)
   private static int upPbo;
   private static java.nio.FloatBuffer upMap;
   static final boolean PBO_UPLOAD = Config.MIRRORS_PBO_UPLOAD;

   private static void upPboCreate() {
      org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
      if (!PBO_UPLOAD || !(caps.OpenGL44 || caps.GL_ARB_buffer_storage)) {
         upPbo = -1;
         return;
      }
      long size = (long)DATA.length * MAXR * TEX * 16L;
      int flags = org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT | GL30.GL_MAP_WRITE_BIT;
      upPbo = GL15.glGenBuffers();
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, upPbo);
      org.lwjgl.opengl.GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size, flags);
      ByteBuffer m = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, size, flags);
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      if (m == null) {
         GL15.glDeleteBuffers(upPbo);
         upPbo = -1;
         return;
      }
      upMap = m.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
      Log.info("mirrors: data uploads through a persistent unpack buffer");
   }

   /** Rows [start, start + n) of the frame's data texture for the pass (0 static, 1 composites). */
   private static void upload(Frame f, int pass, float[] d, int start, int n) {
      int slot = (int)(f.serial % 3) * 2 + pass;
      GL13.glActiveTexture(GL13.GL_TEXTURE2);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, DATA[slot]);
      if (upPbo == 0) {
         upPboCreate();
      }
      if (upPbo > 0 && (Config.DEV_MIRRORS_PROP_SKIP & 4) == 0) {
         int base = (slot * MAXR + start) * TEX * 4;
         upMap.put(base, d, start * TEX * 4, n * TEX * 4);
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, upPbo);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, start, TEX, n, GL11.GL_RGBA, GL11.GL_FLOAT, (long)base * 4L);
         GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
         return;
      }
      UP.clear();
      UP.put(d, start * TEX * 4, n * TEX * 4).flip();
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, start, TEX, n, GL11.GL_RGBA, GL11.GL_FLOAT, UP);
   }

   /** Instanced quads per sprite page: instance i reads data row (first + i); sprite on unit 4. */
   private static void drawGroups(int[] u, Texture[] texs, int start, int n) {
      int uFirst = u[15];
      int i = 0;
      while (i < n) {
         Texture t = texs[start + i];
         int j = i + 1;
         while (j < n && texs[start + j] == t) {
            j++;
         }
         int id = t == null ? 0 : t.getID();
         if (id > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL20.glUniform1f(uFirst, start + i);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, j - i);
         }
         i = j;
      }
   }

   private static void bindWorld() {
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
      GL13.glActiveTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
      GL13.glActiveTexture(GL13.GL_TEXTURE3);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
   }

   private static void restore() {
      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      for (int unit : new int[] {15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1}) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL11.glColorMask(true, true, true, true);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(true);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      GLStateRenderThread.restore();
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreVbos = true;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
   }

   // ------------------------------------------------------------------------------------------------ render thread: passes

   static long staticDraws, lateDraws, modelDraws, layerFrames;
   static volatile long staticCpuNs, lateCpuNs, flushCpuNs;
   static final long[] lateSplit = new long[4]; // dev: composite render thread: upload, set-up, draw, restore

   /** The static pass: last frame's reflectors, the reflected rays marched through the static world into the image. */
   static final class StaticDrawer extends TextureDraw.GenericDrawer {
      Frame f;

      @Override
      public void render() {
         if (failed) {
            return;
         }
         long t0 = System.nanoTime();
         try {
            if (!ensurePrograms() || !world()) {
               return;
            }
            ensureStatic();
            mapping(this.f);
            // the due mirrors' rooms into the geometry atlas first (its own framebuffer), read by the march below
            boolean geo = (this.f.skip & 131072) == 0 && MirrorGeometry.draw(this.f.geo, this.f.serial, FULL, (this.f.skip & 524288) != 0);
            if (geo) {
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, worldFbo);
               GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE15);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, geo ? MirrorGeometry.texture() : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, Props.on() ? propTex() : 0);
            upload(this.f, 0, this.f.sData, 0, this.f.nS);
            GL20.glUseProgram(staticProg);
            GL20.glUniform4f(uS[25], this.f.skyTop[0], this.f.skyTop[1], this.f.skyTop[2], this.f.skyTop[3]);
            GL20.glUniform4f(uS[26], this.f.sunTop[0], this.f.sunTop[1], this.f.sunTop[2], this.f.sunTop[3]);
            GL20.glUniform4f(uS[30], this.f.serial % 61, Config.MIRRORS_PROP_GLOSS_ACCUM_PCT / 100F, 0F, 0F);
            if (STATIC_FBO) {
               fboPass(this.f);
               staticDraws++;
               return;
            }
            if ((this.f.skip & 64) == 0) {
               barrier(); // the composite's colour / depth readable (dev bit 64: without, the fixed cost of the pass alone)
            }
            if (Config.MIRRORS_HIZ) {
               HiZ.build(this.f);
               GL20.glUseProgram(staticProg);
            }
            bindWorld();
            if (this.f.nClear > 0 && org.lwjgl.opengl.GL.getCapabilities().OpenGL44) {
               for (int i = 0; i < this.f.nClear; i++) {
                  org.lwjgl.opengl.GL44.glClearTexSubImage(staticTex, 0, this.f.clear[i * 4], this.f.clear[i * 4 + 1], 0, this.f.clear[i * 4 + 2], this.f.clear[i * 4 + 3], 1,
                     GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MISS);
                  org.lwjgl.opengl.GL44.glClearTexSubImage(glassTex, 0, this.f.clear[i * 4], this.f.clear[i * 4 + 1], 0, this.f.clear[i * 4 + 2], this.f.clear[i * 4 + 3], 1,
                     GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, NO_GLASS);
               }
            }
            GL42.glBindImageTexture(IMAGE_UNIT, staticTex, 0, false, 0, GLOSS_ACCUM ? GL15.GL_READ_WRITE : GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(IMAGE_UNIT + 1, glassTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG8);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(false, false, false, false);
            GL20.glUseProgram(staticProg);
            commonUniforms(uS, this.f);

            GL30.glBindVertexArray(vao);
            if ((this.f.skip & 512) == 0) {
               drawGroups(uS, this.f.sTex, 0, this.f.nS); // (dev bit 512: the pass without its draws)
            }
            if ((this.f.skip & 128) == 0) {
               GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT); // (dev bit 128: without; the composites read the image much later)
            }
            if (mipSampler != 0 && this.f.glossy) {
               GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS); // (the stores visible to the mip build)
               GL13.glActiveTexture(GL13.GL_TEXTURE5);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
               GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
               mipBuilds++;
            }

            GL42.glBindImageTexture(IMAGE_UNIT, 0, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(IMAGE_UNIT + 1, 0, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R8);
            staticDraws++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: static pass failed, off: " + t);
         } finally {
            restore();
            staticCpuNs += System.nanoTime() - t0;
         }
      }
   }

   /**
    * The static pass through the atlas framebuffer (GL 4.1, or mirrorsStaticFbo): every texel of a due pane's tile drawn
    * in tile space (each knows its pane coordinates), reading the frame's colour / depth while another framebuffer is
    * bound (no texture barrier, no image stores, no clears: the whole tile is written).
    */
   private static void fboPass(Frame f) {
      bindWorld();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, atlasFbo);
      GL11.glViewport(0, 0, ATLAS, ATLAS);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glColorMask(true, true, true, true);
      GL20.glUseProgram(staticProg);
      commonUniforms(uS, f);
      GL30.glBindVertexArray(vao);
      if ((f.skip & 512) == 0) {
         drawGroups(uS, f.sTex, 0, f.nS);
      }
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, worldFbo);
      GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
   }

   /** The composite over one level's reflectors: static reflection + model layer, blended over the glass. */
   static final class LateDrawer extends TextureDraw.GenericDrawer {
      Frame f;
      int start, count, props, glossy; // (the last props rows are reflective props: a second draw; of them the last glossy ones a third)
      boolean last; // the frame's last composite (set when the frame ends)

      @Override
      public void render() {
         if (failed) {
            return;
         }
         long t0 = System.nanoTime();
         try {
            if (!ensurePrograms() || !world()) {
               return;
            }
            ensureStatic();
            mapping(this.f);
            if (Config.DEV_MIRRORS_LOG && this.f.serial % Math.max(1, Config.DEV_MIRRORS_RECTS_EVERY) < Math.min(3, Math.max(1, Config.DEV_MIRRORS_RECTS_EVERY))) {
               // dev: each reflector's window px rect (GL origin bottom-left; image rows = viewport height - y), for crops
               StringBuilder sb = new StringBuilder("mirrors: dev rects (viewport " + VPI[2] + "x" + VPI[3] + "):");
               for (int k = 0, shown = 0; k < 2 * this.count && shown < 16; k++) {
                  int i = k % this.count;
                  int o = (this.start + i) * TEX * 4;
                  if ((this.f.lData[o + 24] > 0.5F) != (k < this.count)) {
                     continue; // mirrors first, then windows
                  }
                  shown++;
                  float x0 = (this.f.lData[o] - MAP[1]) / MAP[0], x1 = (this.f.lData[o + 2] - MAP[1]) / MAP[0];
                  float y0 = (this.f.lData[o + 1] - MAP[3]) / MAP[2], y1 = (this.f.lData[o + 3] - MAP[3]) / MAP[2];
                  sb.append(String.format(java.util.Locale.ROOT, " [%s %.0f,%.0f %.0fx%.0f @%s]", this.f.lData[o + 24] > 0.5F ? "mirror" : "window",
                     Math.min(x0, x1), VPI[3] - Math.max(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0), this.f.lName[this.start + i]));
               }
               Log.info(sb.append(" epoch_ms=").append(System.currentTimeMillis()).toString());
            }
            long tu = System.nanoTime();
            upload(this.f, 1, this.f.lData, this.start, this.count);
            long ts = System.nanoTime();
            lateSplit[0] += ts - tu;
            boolean live = liveReads;
            boolean texOcclusion = (this.f.skip & 4096) != 0, noOcclusion = (this.f.skip & 8192) != 0; // dev: occlusion by a depth texture read / none
            if (live) {
               barrier(); // the frame read in place (static mode "late", or a reflector new this frame)
               bindWorld();
            } else if (texOcclusion) {
               bindWorld();
            } else {
               GL13.glActiveTexture(GL13.GL_TEXTURE3);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex); // (the world's colour / depth stay unbound: depth is being tested against that very buffer)
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glassTex);
            boolean layer = layerSerial == this.f.serial && layerFbo != 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer ? layerColor : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer ? layerDepth : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE11);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer && layerTopDrawn ? layerTopColor : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE12);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layer && layerTopDrawn ? layerTopDepth : 0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (live || texOcclusion || noOcclusion) {
               GL11.glDisable(GL11.GL_DEPTH_TEST);
            } else {
               GL11.glEnable(GL11.GL_DEPTH_TEST);
               GL11.glDepthFunc(GL11.GL_LEQUAL);
            }
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glColorMask(true, true, true, false);
            GL20.glUseProgram(lateProg);
            commonUniforms(uL, this.f);
            GL20.glUniform4f(uL[12], layer ? 1F : 0F, layerK, layerZ0, layerW0);
            GL20.glUniform4f(uL[16], live || texOcclusion || noOcclusion ? 0F : 1F, 0.35F, layerScale, 0F);
            GL20.glUniform4f(uL[13], (Config.DEV_MIRRORS_VIEW_TOGGLE_MS > 0 && (System.currentTimeMillis() / Config.DEV_MIRRORS_VIEW_TOGGLE_MS & 1L) == 1L) ? 0 : Config.DEV_MIRRORS_VIEW,
               this.f.skip, "off".equals(Config.MIRRORS_STATIC) ? 0F : 1F, live ? 0F : texOcclusion ? 2F : 1F);
            boolean vis = VIS_ON && !live && visMap != null && (this.f.skip & 2048) == 0 && this.f.serial % VIS_EVERY == 0; // (every 8th frame: the clear, barrier and copy cost ~10 us)
            if (vis) {
               if (this.start == 0) {
                  org.lwjgl.opengl.GL45.glClearNamedBufferData(visDev, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer)null); // (GPU-side zero)
               }
               org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER, 3, visDev);
               GL20.glUniform1i(uL[20], 0);
            }
            GL20.glUniform1i(uL[21], vis ? 1 : 0);
            GL30.glBindVertexArray(vao);
            long td = System.nanoTime();
            lateSplit[1] += td - ts;
            if (live) {
               drawGroups(uL, this.f.lTex, this.start, this.count); // (a reflector new this frame marches here: its sprite is needed)
            } else {
               int panes = this.count - this.props;
               if (panes > 0) {
                  GL20.glUniform1f(uL[15], this.start);
                  GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, panes); // one draw: the glass masks and reflections come from the atlas
               }
               if (this.props > 0 && (Config.DEV_MIRRORS_PROP_SKIP & 8) == 0) {
                  // the props: no analytic plane for the vertex depth (a prop has several faces); each texel's own surface depth
                  // (from the prop atlas) goes to the hardware depth test through gl_FragDepth (mirrorsPropDepthWrite), else the
                  // frame's depth is read as a texture
                  GL13.glActiveTexture(GL13.GL_TEXTURE9);
                  GL11.glBindTexture(GL11.GL_TEXTURE_2D, propTex());
                  if (mipSampler != 0) {
                     GL13.glActiveTexture(GL13.GL_TEXTURE10);
                     GL11.glBindTexture(GL11.GL_TEXTURE_2D, staticTex);
                     GL33.glBindSampler(10, mipSampler);
                  }
                  int[] u = uL;
                  if (latePropProg != lateProg) {
                     u = uLP;
                     GL20.glUseProgram(latePropProg);
                     commonUniforms(uLP, this.f);
                     GL20.glUniform4f(uLP[12], layer ? 1F : 0F, layerK, layerZ0, layerW0);
                     GL20.glUniform4f(uLP[13], (Config.DEV_MIRRORS_VIEW_TOGGLE_MS > 0 && (System.currentTimeMillis() / Config.DEV_MIRRORS_VIEW_TOGGLE_MS & 1L) == 1L) ? 0 : Config.DEV_MIRRORS_VIEW,
                        this.f.skip, "off".equals(Config.MIRRORS_STATIC) ? 0F : 1F, 1F);
                     GL20.glUniform1i(uLP[20], 0);
                     GL20.glUniform1i(uLP[21], vis ? 1 : 0);
                  } else {
                     GL11.glDisable(GL11.GL_DEPTH_TEST);
                     bindWorld();
                  }
                  GL20.glUniform4f(u[31], Config.MIRRORS_PROP_METAL_BLEND ? 1F : 0F, 0F, 0F, 0F);
                  GL20.glUniform4f(u[16], Config.MIRRORS_PROP_CONSERVATIVE_DEPTH && latePropProg != lateProg ? 2F : 0F, 0.35F, layerScale, mipSampler != 0 ? 1F : 0F);
                  int plain = this.props - this.glossy;
                  if (plain > 0) {
                     GL20.glUniform1f(u[15], this.start + panes);
                     GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, plain);
                  }
                  if (this.glossy > 0) {
                     // steel / ceramic (mirrorsPropMetalBlend): the reflection modulates the surface's own colour, dst x (1 - a + a m),
                     // instead of replacing a share of it: blended over, the mirrored floor / wall continued the scene beside the
                     // counter and the steel read as see-through (maintainer, 2026-10-09)
                     GL14.glBlendFuncSeparate(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                     GL20.glUniform1f(u[15], this.start + panes + plain);
                     GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, this.glossy);
                     GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                  }
                  if (mipSampler != 0) {
                     GL33.glBindSampler(10, 0);
                  }
               }
            }
            if (vis && this.last) {
               GL42.glMemoryBarrier(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
               org.lwjgl.opengl.GL45.glCopyNamedBufferSubData(visDev, visBuf, 0L, (long)(this.f.serial % VIS_SLOTS) * MAXR * 4L, (long)MAXR * 4L); // (async, the game thread reads it frames later)
            }
            lateSplit[2] += System.nanoTime() - td;
            lateDraws++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: composite failed, off: " + t);
         } finally {
            long tr = System.nanoTime();
            restore();
            long te = System.nanoTime();
            lateSplit[3] += te - tr;
            lateCpuNs += te - t0;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the model layer

   /** A mirrored model draw waiting for the frame's flush: its slot (valid until the frame's postRender) and camera. */
   private static final class Pending {
      ModelSlotRenderData slot;
      int planes;
      boolean vehicle;
      float camX, camY, camZ, angle, ox, oy, oz;
   }

   private static final ArrayList<Pending> PENDING = new ArrayList<>();
   private static final ArrayList<Pending> PPOOL = new ArrayList<>();
   private static long layerSerial = -1;
   private static float layerK, layerZ0, layerW0;
   private static float layerScale = 1F, drawScale = 1F; // the layer's resolution scale (read by the composite), the one of the flush in progress
   private static final MirrorCamera CAMERA = new MirrorCamera();

   /** Render thread, TextureDraw's DrawModel right after the model's own draw: queued for the frame's mirrored draws. */
   public static void renderModel(TextureDraw texd) {
      if (failed || texd.pzoptMirrorPlanes == 0 || !(texd.drawer instanceof ModelSlotRenderData slot)) {
         return;
      }
      ModelCamera cam = ModelCamera.instance;
      if (cam == null || cam.inVehicle || slot.renderToTexture || !cam.useWorldIso) {
         return;
      }
      Pending p = PPOOL.isEmpty() ? new Pending() : PPOOL.remove(PPOOL.size() - 1);
      p.slot = slot;
      p.planes = texd.pzoptMirrorPlanes;
      p.vehicle = slot.character == null;
      p.camX = cam.x;
      p.camY = cam.y;
      p.camZ = cam.z;
      p.angle = cam.useAngle;
      PENDING.add(p);
   }

   private static void recyclePending() {
      for (Pending p : PENDING) {
         p.slot = null;
         PPOOL.add(p);
      }
      PENDING.clear();
   }

   private static final int[] QR = new int[4];

   /** Render thread: plane k's panes on screen (layer px, 2 px margin) inside the scissor sx, sy, sw, sh into QR; false when empty. */
   private static boolean planeRect(Frame f, int k, float ls, int sx, int sy, int sw, int sh) {
      int o = k * 10;
      float pa = (f.planes[o + 6] - MAP[1]) / MAP[0], pb = (f.planes[o + 8] - MAP[1]) / MAP[0];
      float qa = (f.planes[o + 7] - MAP[3]) / MAP[2], qb = (f.planes[o + 9] - MAP[3]) / MAP[2];
      int x0 = Math.max(sx, (int)Math.floor(Math.min(pa, pb) * ls) - 2), y0 = Math.max(sy, (int)Math.floor(Math.min(qa, qb) * ls) - 2);
      int x1 = Math.min(sx + sw, (int)Math.ceil(Math.max(pa, pb) * ls) + 2), y1 = Math.min(sy + sh, (int)Math.ceil(Math.max(qa, qb) * ls) + 2);
      QR[0] = x0;
      QR[1] = y0;
      QR[2] = x1 - x0;
      QR[3] = y1 - y0;
      return x1 > x0 && y1 > y0;
   }

   static final class ModelFlush extends TextureDraw.GenericDrawer {
      Frame f;
      boolean reuse;

      @Override
      public void render() {
         if (failed || PENDING.isEmpty()) {
            recyclePending();
            return;
         }
         if (this.reuse) {
            recyclePending();
            if (layerSerial >= 0) {
               layerSerial = this.f.serial; // last frame's layer, read again
            }
            return;
         }
         long t0 = System.nanoTime();
         int previousFbo = zombie.core.textures.TextureFBO.lastID;
         ModelCamera prevCam = ModelCamera.instance;
         ShadowAtlas.Tracked saved = ShadowAtlas.Tracked.save();
         GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
         try {
            if (!world() || !ensureLayer()) {
               return;
            }
            mapping(this.f);
            // with no silvered mirror on screen (glass, screens, steel: 12-35 %) the mirrored models go into the layer at
            // mirrorsGlassModelScalePct of the resolution (its lower-left part; the composite reads it scaled)
            float ls = this.f.silver || Config.MIRRORS_GLASS_MODEL_SCALE_PCT >= 100 || (Config.DEV_MIRRORS_PROP_SKIP & 16) != 0 ? 1F : Math.max(25, Config.MIRRORS_GLASS_MODEL_SCALE_PCT) / 100F;
            drawScale = ls;
            // pass 0: the vertical planes into the layer; pass 1: the props' tops into their own layer
            int topBits = 0;
            for (int i = 0; i < this.f.nP; i++) {
               if (this.f.planes[i * 10] == 2F) {
                  topBits |= 1 << i;
               }
            }
            int wanted = 0;
            for (Pending p : PENDING) {
               wanted |= p.planes;
            }
            boolean calibrated = false;
            layerTopDrawn = false;
            for (int pass = 0; pass < 2; pass++) {
            int passBits = pass == 0 ? ~topBits : topBits;
            int fbo = pass == 0 ? layerFbo : layerTopFbo;
            if (pass == 1 && ((wanted & topBits) == 0 || fbo == 0)) {
               break;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(Math.round(VPI[0] * ls), Math.round(VPI[1] * ls), Math.round(VPI[2] * ls), Math.round(VPI[3] * ls));
            GLStateRenderThread.ScissorTest.set(false);
            GLStateRenderThread.StencilTest.set(false);
            GLStateRenderThread.ColorMask.set(true, true, true, true);
            GLStateRenderThread.DepthMask.set(true);
            // the union of the pass's planes' panes on screen: what the composite can read
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = 0; i < this.f.nP; i++) {
               if ((passBits & (1 << i)) == 0) {
                  continue;
               }
               int o = i * 10;
               float pa = (this.f.planes[o + 6] - MAP[1]) / MAP[0], pb = (this.f.planes[o + 8] - MAP[1]) / MAP[0];
               float qa = (this.f.planes[o + 7] - MAP[3]) / MAP[2], qb = (this.f.planes[o + 9] - MAP[3]) / MAP[2];
               x0 = Math.min(x0, Math.min(pa, pb));
               x1 = Math.max(x1, Math.max(pa, pb));
               y0 = Math.min(y0, Math.min(qa, qb));
               y1 = Math.max(y1, Math.max(qa, qb));
            }
            int sx = Math.max(0, (int)Math.floor(x0 * ls) - 2), sy = Math.max(0, (int)Math.floor(y0 * ls) - 2);
            int sw = Math.max(0, Math.min(layerW, (int)Math.ceil(x1 * ls) + 2) - sx), sh = Math.max(0, Math.min(layerH, (int)Math.ceil(y1 * ls) + 2) - sy);
            if (sw <= 0 || sh <= 0) {
               continue;
            }
            if (pass == 1 && (Config.DEV_MIRRORS_TOP_TEST & 1) != 0) {
               sx = sy = 0; // dev: the top pass without its scissor
               sw = layerW;
               sh = layerH;
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sx, sy, sw, sh);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GLStateRenderThread.ScissorTest.set(true);
            GLStateRenderThread.DepthTest.set(true);
            GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
            GL11.glFrontFace(GL11.GL_CW); // a reflection turns the triangles' winding over
            if (pass == 1 && (Config.DEV_MIRRORS_TOP_TEST & 2) != 0) {
               GLStateRenderThread.DepthTest.set(false); // dev: the top pass without its depth test
               GL11.glDisable(GL11.GL_CULL_FACE);
            }
            int[] done = Config.MIRRORS_MODEL_SHARE && (Config.DEV_MIRRORS_PROP_SKIP & 32) == 0 ? Share.draw(this.f, sx, sy, sw, sh, ls, pass == 0 ? 0 : 2, pass == 0 ? 1 : 2, fbo) : null;
            calibrated |= done != null && Share.calibrated;
            // stage 0 the glass and prop planes, stage 1 the silvered mirrors' own, each mirror's panes cleared first: a
            // glass or prop plane whose panes' box covers a mirror (the bathroom's sink, toilet and tub merged into one plane
            // along the mirror's wall, 7 squares long) put its copy of the person, placed by the true reflection, on the
            // mirror's glass beside the mirror's own copy: two people in the mirror (maintainer, 2026-10-12; dev skip bit
            // 536870912: one stage, the pass's union scissor, before)
            boolean ownPanes = (this.f.skip & 536870912) == 0;
            for (int stage = 0; stage < 2; stage++) {
            if (stage == 1 && ownPanes) {
               for (int k = 0; k < this.f.nP; k++) {
                  if (this.f.planeMirror[k] && (wanted & passBits & (1 << k)) != 0 && planeRect(this.f, k, ls, sx, sy, sw, sh)) {
                     GLStateRenderThread.ScissorTest.set(true);
                     GLStateRenderThread.ColorMask.set(true, true, true, true);
                     GLStateRenderThread.DepthMask.set(true);
                     GL11.glScissor(QR[0], QR[1], QR[2], QR[3]);
                     GL11.glClearColor(0F, 0F, 0F, 0F);
                     GL11.glClearDepth(1.0);
                     GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                  }
               }
            }
            for (int i = 0; i < PENDING.size(); i++) {
               Pending p = PENDING.get(i);
               if (ShadowAtlas.texturesPending(p.slot)) {
                  continue;
               }
               for (int k = 0; k < this.f.nP; k++) {
                  if ((p.planes & passBits & (1 << k)) == 0 || done != null && (done[i] & (1 << k)) != 0 || this.f.planeMirror[k] != (stage == 1)) {
                     continue;
                  }
                  int o = k * 10;
                  float cAbs = this.f.planes[o] == 2F ? this.f.planes[o + 1] : this.f.planes[o + 1] + (this.f.planes[o] == 0F ? this.f.view.oy : this.f.view.ox);
                  CAMERA.setUp(p, (int)this.f.planes[o], cAbs, viewLateral(this.f.planeMirror[k], this.f.skip), this.f.planeMirror[k] ? this.f.planeDrop[k] : viewDrop(false, this.f.skip));
                  if (!calibrated) {
                     calibrated = CAMERA.calibrate(this.f);
                  }
                  // the copy clipped to this plane's own panes, as Share's copies are (under the pass's union scissor a mirror's
                  // copy also reached the window beside it)
                  int qx = sx, qy = sy, qw = sw, qh = sh;
                  if (ownPanes) {
                     if (!planeRect(this.f, k, ls, sx, sy, sw, sh)) {
                        continue;
                     }
                     qx = QR[0];
                     qy = QR[1];
                     qw = QR[2];
                     qh = QR[3];
                  }
                  float squareDepth = p.slot.squareDepth;
                  boolean outline = ShadowAtlas.outline(p.slot, false); // the aim outline is a screen overlay: once, not per plane
                  ModelCamera.instance = CAMERA;
                  GLStateRenderThread.ScissorTest.set(true);
                  GL11.glScissor(qx, qy, qw, qh);
                  try {
                     synchronized (p.slot) {
                        p.slot.render();
                     }
                  } finally {
                     p.slot.squareDepth = squareDepth;
                     ShadowAtlas.outline(p.slot, outline);
                  }
                  modelDraws++;
               }
            }
            }
            if (pass == 1) {
               layerTopDrawn = true;
            }
            }
            layerSerial = calibrated ? this.f.serial : -1;
            layerScale = ls;
            if (Config.DEV_MIRRORS_LOG && layerFrames % 300 == 150) {
               // dev: the layer's drawn px (alpha > 0) read back once: their box, against the scissor and the frame's planes
               int lw = Math.round(VPI[2] * ls), lh = Math.round(VPI[3] * ls);
               ByteBuffer px = BufferUtils.createByteBuffer(lw * lh * 4);
               GL11.glReadPixels(0, 0, lw, lh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
               int bx0 = lw, by0 = lh, bx1 = -1, by1 = -1, n = 0;
               for (int yy = 0; yy < lh; yy++) {
                  for (int xx = 0; xx < lw; xx++) {
                     if ((px.get((yy * lw + xx) * 4 + 3) & 0xFF) > 0) {
                        n++;
                        bx0 = Math.min(bx0, xx);
                        bx1 = Math.max(bx1, xx);
                        by0 = Math.min(by0, yy);
                        by1 = Math.max(by1, yy);
                     }
                  }
               }
               StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "mirrors: dev layer (scale %.2f, %dx%d, %s): %d px drawn, box %d,%d..%d,%d; pending:", ls, lw, lh, layerTopDrawn ? "top layer" : "layer", n, bx0, by0, bx1, by1));
               for (Pending p : PENDING) {
                  float u = (p.camX - this.f.view.ox) - (p.camY - this.f.view.oy), v = (p.camX - this.f.view.ox) + (p.camY - this.f.view.oy) - 6F * p.camZ;
                  sb.append(String.format(java.util.Locale.ROOT, " [%s planes %s at layer px %.0f,%.0f, cam z %.3f, object z %.3f]", p.vehicle ? "vehicle" : "character", Integer.toBinaryString(p.planes), (u - MAP[1]) / MAP[0] * ls, (v - MAP[3]) / MAP[2] * ls,
                     p.camZ, p.slot != null && p.slot.object != null ? p.slot.object.getZ() : -1F));
               }
               Log.info(sb.toString());
            }
            layerFrames++;
         } catch (Throwable t) {
            failed = true;
            Log.warn("mirrors: model pass failed, off: " + t);
         } finally {
            GL11.glFrontFace(GL11.GL_CCW);
            ModelCamera.instance = prevCam;
            saved.restore();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            GL11.glPopAttrib();
            GL11.glDepthRange(0.0, 1.0);
            recyclePending();
            Texture.lastTextureID = -1;
            flushCpuNs += System.nanoTime() - t0;
         }
      }
   }

   /** mirrorsHiZ: the frame's nearest scene (largest iso depth) per 16 x 16 px block, built at the static pass, bound on unit 13. */
   static final class HiZ {
      static int fbo, tex, prog, w, h, uDepth, uMap, uVp;
      static long builds;

      static void build(Frame f) {
         int cw = (VPI[2] + 15) / 16, ch = (VPI[3] + 15) / 16;
         if (prog == 0) {
            prog = AmbientOcclusion.link(String.join("\n",
               FULL ? "#version 430" : "#version 410 core",
               "void main() {",
               "   int k = gl_VertexID;",
               "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
               "   gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);",
               "}"), String.join("\n",
               FULL ? "#version 430" : "#version 410 core",
               "uniform sampler2D WorldDepth;",
               "uniform vec4 mapC;",
               "uniform vec4 vp;",
               "out vec4 fragColor;",
               "void main() {",
               "   ivec2 o = ivec2(gl_FragCoord.xy) * 16 + ivec2(vp.xy);",
               "   ivec2 hi = ivec2(vp.xy + vp.zw) - 1;",
               "   float m = -1e30;",
               "   for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) m = max(m, mapC.x * texelFetch(WorldDepth, min(o + ivec2(x, y), hi), 0).r + mapC.y);",
               "   fragColor = vec4(m);",
               "}"));
            if (prog == 0) {
               prog = -1;
               Log.warn("mirrors: Hi-Z program did not link; marches without it");
            } else {
               uDepth = GL20.glGetUniformLocation(prog, "WorldDepth");
               uMap = GL20.glGetUniformLocation(prog, "mapC");
               uVp = GL20.glGetUniformLocation(prog, "vp");
            }
         }
         if (prog < 0) {
            return;
         }
         if (tex == 0 || w != cw || h != ch) {
            if (tex != 0) {
               GL30.glDeleteFramebuffers(fbo);
               GL11.glDeleteTextures(tex);
            }
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, cw, ch, 0, GL11.GL_RED, GL11.GL_FLOAT, (FloatBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            w = cw;
            h = ch;
         }
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL11.glViewport(0, 0, cw, ch);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glColorMask(true, true, true, true);
         GL20.glUseProgram(prog);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
         GL20.glUniform1i(uDepth, 1);
         GL20.glUniform4f(uMap, MAP[4], MAP[5], 0F, 0F);
         GL20.glUniform4f(uVp, VPF[0], VPF[1], VPF[2], VPF[3]);
         GL30.glBindVertexArray(vao);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, worldFbo);
         GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
         GL13.glActiveTexture(GL13.GL_TEXTURE13);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         builds++;
      }
   }

   /**
    * One mirrored draw per orientation (mirrorsModelShare, 2026-10-08). For a true reflection the images of a model in two
    * planes of the same orientation differ by a translation alone (planes y = c1 and y = c2: the image moves (0, 2 (c2 - c1), 0)
    * in the world, on screen u -2 dc, v +2 dc, iso depth +2 dc; x = c: u +2 dc; z = c: v -12 dc, iso depth +4 dc). So the
    * models mirrored in several windows / prop panes of one orientation are drawn once, in the first plane, into a scratch
    * impostor (the viewport shifted onto the reflected models' screen box), and each (model, plane) gets its box copied into
    * the layer at the plane's offset, clipped to the plane's panes, at one depth (the model centre's image: the copy samples
    * no depth texture, which AMD decompresses, and keeps early depth tests). The silvered mirrors (people placed by
    * mirrorsView*, not a true reflection) keep a draw per plane. The flip drew the player and a car into 8 planes: 1.2 ms of GPU
    * per drawn frame (prop-v3); the first version copied the whole box per plane with its depth texture: 2.0 ms (prop-v5).
    */
   static final class Share {
      static final int SCR = 2048;
      static int fbo, color, depth, prog, vaoQ;
      static int uSrc, uDst, uZ, uColor, uVp;
      static boolean calibrated;
      static long shared, copies, fallbacks;
      private static final int[] KS = new int[MAXP];
      private static int[] done = new int[64];
      private static float[] boxes = new float[64 * 4]; // per pending model: its reflection's window box in the reference plane

      static boolean ensure() {
         if (fbo != 0) {
            return fbo > 0;
         }
         color = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SCR, SCR, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         depth = GL30.glGenRenderbuffers(); // (never sampled)
         GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
         GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, SCR, SCR);
         GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         fbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
         GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
         boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
         prog = AmbientOcclusion.link(String.join("\n",
            FULL ? "#version 430" : "#version 410 core",
            "uniform vec4 Dst;", // the copy's window rect: x, y, w, h
            "uniform vec4 Vp;",
            "uniform float Z;", // its depth (ndc)
            "void main() {",
            "   int k = gl_VertexID;",
            "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
            "   vec2 px = Dst.xy + c * Dst.zw;",
            "   gl_Position = vec4((px - Vp.xy) / Vp.zw * 2.0 - 1.0, Z, 1.0);",
            "}"), String.join("\n",
            FULL ? "#version 430" : "#version 410 core",
            "uniform sampler2D ColorTex;",
            "uniform vec4 Src;", // window px -> scratch texel: texel = px - Src.xy
            "out vec4 fragColor;",
            "void main() {",
            "   vec4 c = texelFetch(ColorTex, ivec2(gl_FragCoord.xy - Src.xy), 0);",
            "   if (c.a < 0.004) discard;",
            "   fragColor = c;",
            "}"));
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
         if (!ok || prog == 0) {
            Log.warn("mirrors: shared model impostor unavailable; a draw per plane");
            fbo = -1;
            return false;
         }
         uSrc = GL20.glGetUniformLocation(prog, "Src");
         uDst = GL20.glGetUniformLocation(prog, "Dst");
         uZ = GL20.glGetUniformLocation(prog, "Z");
         uColor = GL20.glGetUniformLocation(prog, "ColorTex");
         uVp = GL20.glGetUniformLocation(prog, "Vp");
         vaoQ = GL30.glGenVertexArrays();
         Log.info("mirrors: shared model impostor " + SCR + "x" + SCR);
         return true;
      }

      /** Window px of a world point (frame-relative mapping MAP) into box i. */
      private static void box(Frame f, int i, float x, float y, float z) {
         float u = (x - f.view.ox) - (y - f.view.oy), v = (x - f.view.ox) + (y - f.view.oy) - 6F * z;
         float px = (u - MAP[1]) / MAP[0] * drawScale, py = (v - MAP[3]) / MAP[2] * drawScale;
         int o = i * 4;
         boxes[o] = Math.min(boxes[o], px);
         boxes[o + 1] = Math.min(boxes[o + 1], py);
         boxes[o + 2] = Math.max(boxes[o + 2], px);
         boxes[o + 3] = Math.max(boxes[o + 3], py);
      }

      /**
       * Render thread, ModelFlush with the layer bound and cleared: the shared draws; returns per pending model the planes
       * already in the layer (null: nothing shared).
       */
      static int[] draw(Frame f, int sx, int sy, int sw, int sh, float ls, int axisFrom, int axisTo, int target) {
         calibrated = false;
         int np = PENDING.size();
         if (np > done.length) {
            done = new int[np * 2];
            boxes = new float[np * 8];
         }
         java.util.Arrays.fill(done, 0, np, 0);
         boolean any = false;
         for (int axis = axisFrom; axis <= axisTo; axis++) {
            int n = 0;
            for (int k = 0; k < f.nP; k++) {
               if ((int)f.planes[k * 10] == axis && !f.planeMirror[k]) {
                  KS[n++] = k;
               }
            }
            if (n < 2) {
               continue;
            }
            int bits = 0;
            for (int j = 0; j < n; j++) {
               bits |= 1 << KS[j];
            }
            // the reference: the plane most of the sharing models show in. KS[0], the frame's first plane of the axis, could
            // be one nobody stands before (an N window five squares south of the sunroom's wall): the models mirrored that far
            // fell out of the scratch draw and every copy was empty, so a person in two planes of one wall (a sliding door's
            // leaf beside its fixed pane) showed in neither (2026-10-10)
            int ref = KS[0], refModels = -1;
            for (int j = 0; j < n; j++) {
               int cnt = 0;
               for (int i = 0; i < np; i++) {
                  Pending p = PENDING.get(i);
                  if ((p.planes & (1 << KS[j])) != 0 && Integer.bitCount(p.planes & bits) >= 2) {
                     cnt++;
                  }
               }
               if (cnt > refModels) {
                  refModels = cnt;
                  ref = KS[j];
               }
            }
            float cRef = axis == 2 ? f.planes[ref * 10 + 1] : f.planes[ref * 10 + 1] + (axis == 0 ? f.view.oy : f.view.ox);
            // the models that show in two or more of these planes, each one's reflection box in the reference plane
            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
            int models = 0;
            for (int i = 0; i < np; i++) {
               Pending p = PENDING.get(i);
               if (Integer.bitCount(p.planes & bits) < 2 || ShadowAtlas.texturesPending(p.slot)) {
                  continue;
               }
               models++;
               boxes[i * 4] = boxes[i * 4 + 1] = Float.MAX_VALUE;
               boxes[i * 4 + 2] = boxes[i * 4 + 3] = -Float.MAX_VALUE;
               float r = p.vehicle ? 3.2F : 0.7F, h = 1.0F;
               for (int c = 0; c < 8; c++) {
                  float x = p.camX + ((c & 1) == 0 ? -r : r), y = p.camY + ((c & 2) == 0 ? -r : r), z = p.camZ + ((c & 4) == 0 ? 0F : h);
                  if (axis == 0) {
                     y = 2F * cRef - y;
                  } else if (axis == 1) {
                     x = 2F * cRef - x;
                  } else {
                     z = 2F * cRef - z;
                  }
                  box(f, i, x, y, z);
               }
               bx0 = Math.min(bx0, boxes[i * 4] - 4F);
               by0 = Math.min(by0, boxes[i * 4 + 1] - 4F);
               bx1 = Math.max(bx1, boxes[i * 4 + 2] + 4F);
               by1 = Math.max(by1, boxes[i * 4 + 3] + 4F);
            }
            if (models == 0) {
               continue;
            }
            int bx = (int)Math.floor(bx0), by = (int)Math.floor(by0);
            int bw = (int)Math.ceil(bx1) - bx, bh = (int)Math.ceil(by1) - by;
            if (bw > SCR || bh > SCR || bw <= 0 || bh <= 0 || !ensure()) {
               fallbacks++;
               continue;
            }
            // the reference draws into the scratch: the viewport shifted so the box lands at its origin
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(Math.round(VPI[0] * ls) - bx, Math.round(VPI[1] * ls) - by, Math.round(VPI[2] * ls), Math.round(VPI[3] * ls));
            GLStateRenderThread.ScissorTest.set(true);
            GL11.glScissor(0, 0, bw, bh);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GLStateRenderThread.DepthTest.set(true);
            GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
            GLStateRenderThread.DepthMask.set(true);
            GL11.glFrontFace(GL11.GL_CW);
            for (int i = 0; i < np; i++) {
               Pending p = PENDING.get(i);
               if (Integer.bitCount(p.planes & bits) < 2 || ShadowAtlas.texturesPending(p.slot)) {
                  continue;
               }
               CAMERA.setUp(p, axis, cRef, 1F, 1F / 3F);
               if (!calibrated) {
                  calibrated = CAMERA.calibrate(f);
               }
               float squareDepth = p.slot.squareDepth;
               boolean outline = ShadowAtlas.outline(p.slot, false);
               ModelCamera.instance = CAMERA;
               GLStateRenderThread.ScissorTest.set(true);
               GL11.glScissor(0, 0, bw, bh);
               try {
                  synchronized (p.slot) {
                     p.slot.render();
                  }
               } finally {
                  p.slot.squareDepth = squareDepth;
                  ShadowAtlas.outline(p.slot, outline);
               }
               modelDraws++;
               done[i] |= p.planes & bits;
            }
            if (!calibrated) {
               continue;
            }
            any = true;
            shared++;
            // each (model, plane): the model's box copied into the layer at the plane's offset, clipped to the plane's panes,
            // at the depth of the model centre's image in that plane
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
            GL11.glViewport(Math.round(VPI[0] * ls), Math.round(VPI[1] * ls), Math.round(VPI[2] * ls), Math.round(VPI[3] * ls));
            GL20.glUseProgram(prog);
            GL20.glUniform1i(uColor, 0);
            GL20.glUniform4f(uVp, Math.round(VPI[0] * ls), Math.round(VPI[1] * ls), Math.round(VPI[2] * ls), Math.round(VPI[3] * ls));
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glFrontFace(GL11.GL_CCW); // (the copy's quad is not mirrored)
            GL30.glBindVertexArray(vaoQ);
            for (int j = 0; j < n; j++) {
               int k = KS[j];
               int ok = k * 10;
               float c = axis == 2 ? f.planes[ok + 1] : f.planes[ok + 1] + (axis == 0 ? f.view.oy : f.view.ox);
               float dc = c - cRef;
               float du = axis == 0 ? -2F * dc : axis == 1 ? 2F * dc : 0F, dv = axis == 2 ? -12F * dc : 2F * dc;
               float dpx = du / MAP[0] * ls, dpy = dv / MAP[2] * ls;
               // the plane's panes on screen (layer px)
               float pa = (f.planes[ok + 6] - MAP[1]) / MAP[0] * ls, pb = (f.planes[ok + 8] - MAP[1]) / MAP[0] * ls;
               float qa = (f.planes[ok + 7] - MAP[3]) / MAP[2] * ls, qb = (f.planes[ok + 9] - MAP[3]) / MAP[2] * ls;
               float px0 = Math.min(pa, pb) - 2F, px1 = Math.max(pa, pb) + 2F, py0 = Math.min(qa, qb) - 2F, py1 = Math.max(qa, qb) + 2F;
               GL20.glUniform4f(uSrc, bx + dpx, by + dpy, 0F, 0F);
               for (int i = 0; i < np; i++) {
                  Pending p = PENDING.get(i);
                  if ((done[i] & (1 << k)) == 0) {
                     continue;
                  }
                  int o = i * 4;
                  float x0 = Math.max(boxes[o] + dpx, px0), x1 = Math.min(boxes[o + 2] + dpx, px1);
                  float y0 = Math.max(boxes[o + 1] + dpy, py0), y1 = Math.min(boxes[o + 3] + dpy, py1);
                  if (x1 <= x0 || y1 <= y0) {
                     continue;
                  }
                  // the centre's image in plane c: iso depth w of the reflected centre, to the layer's ndc
                  float x = p.camX, y = p.camY, z = p.camZ + 0.5F;
                  if (axis == 0) {
                     y = 2F * c - y;
                  } else if (axis == 1) {
                     x = 2F * c - x;
                  } else {
                     z = 2F * c - z;
                  }
                  float w = (x - f.view.ox) + (y - f.view.oy) + 2F * z;
                  GL20.glUniform1f(uZ, Math.max(-1F, Math.min(1F, layerZ0 + (w - layerW0) / layerK)));
                  GL20.glUniform4f(uDst, x0, y0, x1 - x0, y1 - y0);
                  GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
                  copies++;
               }
            }
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            Texture.lastTextureID = -1;
         }
         // back to the layer for the draws per plane
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
         GL11.glViewport(Math.round(VPI[0] * ls), Math.round(VPI[1] * ls), Math.round(VPI[2] * ls), Math.round(VPI[3] * ls));
         GLStateRenderThread.ScissorTest.set(true);
         GL11.glScissor(sx, sy, sw, sh);
         GLStateRenderThread.DepthTest.set(true);
         GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
         GLStateRenderThread.DepthMask.set(true);
         GL11.glFrontFace(GL11.GL_CW);
         return any ? done : null;
      }
   }

   /**
    * The stock world camera of a model (Core.DoPushIsoStuff) with the plane's reflection between the world and the model:
    * modelview = A T B with B the model's own scale / facing / offset; the reflection R acts in T's frame, so the draw uses
    * A T R B = MV B^-1 R B. Its lighting is computed in the model's frame and stays the real one. The depth is the plain
    * projected one (squareDepth set as ShadowAtlas' sun camera does), which the composite turns back into iso depth.
    */
   static final class MirrorCamera extends ModelCamera {
      private Pending p;
      private int axis;
      private float c, viewL = 1F, viewS = 1F / 3F;
      private final Matrix4f b = new Matrix4f(), bi = new Matrix4f(), r = new Matrix4f(), scratch = new Matrix4f(), scratch2 = new Matrix4f();
      private int devFacingLogs, devTopLogs;
      private final Vector4f v = new Vector4f();

      void setUp(Pending p, int axis, float cAbs, float viewL, float viewS) {
         this.p = p;
         this.viewL = viewL;
         this.viewS = viewS;
         this.axis = axis;
         this.c = cAbs;
         this.useAngle = p.angle;
         this.useWorldIso = true;
         this.inVehicle = false;
         this.x = p.camX;
         this.y = p.camY;
         this.z = p.camZ;
         this.depthMask = true;
      }

      private Matrix4f modelB() {
         this.b.identity();
         if (this.p.vehicle) {
            this.b.scale(-1.0F, 1.0F, 1.0F).rotate(this.useAngle + (float)Math.PI, 0F, 1F, 0F);
         } else {
            this.b.scale(-1.5F, 1.5F, 1.5F).rotate(this.useAngle + (float)Math.PI, 0F, 1F, 0F).translate(0F, -0.48F, 0F);
         }
         return this.b;
      }

      @Override
      public void Begin() {
         Core core = Core.getInstance();
         core.DoPushIsoStuff(this.x, this.y, this.z, this.useAngle, this.p.vehicle);
         Matrix4f mv = core.modelViewMatrixStack.peek();
         // T's frame: (-(wx - x), (wz - z) * 2.449, -(wy - y)); the plane wy = c is W.z = -(c - y), wx = c is W.x = -(c - x)
         // A point X d squares in front of the plane is to show at the pane point P = X - d D, D = the reflected view ray per
         // square out (north pane (-L, 1, -S), west (1, -L, -S) in world x, y, levels; L = 1, S = 1/3 the camera's true
         // reflection), with the iso depth 8/3 d under P's (the composite turns the layer's depth back into d): Y = P - d (1, 1, 1/3)
         // (the view axis), i.e. Y = X - d K, K = D + (1, 1, 1/3). With L = 1, S = 1/3 that is the plane's reflection. In T's
         // frame (x, y, z) = (-(wx - x), 2.449 (wz - z), -(wy - y)): north d = zc - W.z, west d = xc - W.x. det = -1 for every
         // L, S (still a mirror image: the winding turns over)
         this.r.identity();
         float kDrop = -2.449F * (1F / 3F - this.viewS);
         if (this.axis == 2) {
            // a top z = c: T's y is 2.449 (wz - z); its mirror y' = 2 yc - y
            float yc = 2.449F * (this.c - this.z) - (this.p.vehicle ? Config.DEV_MIRRORS_TOP_Y_VEHICLE_PCT : Config.DEV_MIRRORS_TOP_Y_PCT) / 100F;
            this.r.m11(-1F).m31(2F * yc);
         } else if (this.axis == 0) {
            float zc = -(this.c - this.y);
            float k0 = 1F - this.viewL, k1 = kDrop, k2 = 2F;
            this.r.m20(-k0).m21(-k1).m22(1F - k2).m30(zc * k0).m31(zc * k1).m32(zc * k2);
         } else {
            float xc = -(this.c - this.x);
            float k0 = 2F, k1 = kDrop, k2 = 1F - this.viewL;
            this.r.m00(1F - k0).m01(-k1).m02(-k2).m30(xc * k0).m31(xc * k1).m32(xc * k2);
         }
         modelB();
         this.bi.set(this.b).invert();
         mv.mul(this.scratch.set(this.bi).mul(this.r).mul(this.b));
         Matrix4f pv = this.scratch.set(core.projectionMatrixStack.peek()).mul(mv);
         if (this.p.vehicle && this.p.slot != null) {
            pv.translate(0.0F, this.p.slot.centerOfMassY, 0.0F);
         }
         float originZ = pv.m32() / pv.m33();
         if (Config.DEV_MIRRORS_LOG && this.axis == 2 && devTopLogs < 6) {
            // dev: where a top plane's mirror image of the model lands (ndc of the model's origin and of a point one unit up,
            // real and mirrored)
            devTopLogs++;
            Matrix4f real = new Matrix4f(core.projectionMatrixStack.peek()).mul(new Matrix4f(mv).mul(new Matrix4f(this.bi).mul(this.r).mul(this.b).invert()));
            Vector4f a0 = real.transform(new Vector4f(0F, 0F, 0F, 1F)), a1 = real.transform(new Vector4f(0F, 1F, 0F, 1F));
            Vector4f m0 = pv.transform(new Vector4f(0F, 0F, 0F, 1F)), m1 = pv.transform(new Vector4f(0F, 1F, 0F, 1F));
            Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev top check (plane z %.2f, model z %.2f): origin real %.4f,%.4f,%.4f mirrored %.4f,%.4f,%.4f; +1 up real %.4f,%.4f mirrored %.4f,%.4f",
               this.c, this.z, a0.x / a0.w, a0.y / a0.w, a0.z / a0.w, m0.x / m0.w, m0.y / m0.w, m0.z / m0.w, a1.x / a1.w, a1.y / a1.w, m1.x / m1.w, m1.y / m1.w));
         }
         if (Config.DEV_MIRRORS_LOG && devFacingLogs < 6) {
            // dev: the model's forward axis (model z) under the mirrored camera against the real one: a character facing
            // the pane must turn its front to the camera in the mirror, the depth step of the axis flips sign
            devFacingLogs++;
            Matrix4f real = new Matrix4f(core.projectionMatrixStack.peek()).mul(new Matrix4f(mv).mul(this.scratch2.set(this.bi).mul(this.r).mul(this.b).invert()));
            Vector4f a0 = real.transform(new Vector4f(0F, 0F, 0F, 1F)), ax = real.transform(new Vector4f(1F, 0F, 0F, 1F)), az = real.transform(new Vector4f(0F, 0F, 1F, 1F));
            Vector4f m0 = pv.transform(new Vector4f(0F, 0F, 0F, 1F)), mx = pv.transform(new Vector4f(1F, 0F, 0F, 1F)), mz = pv.transform(new Vector4f(0F, 0F, 1F, 1F));
            Log.info(String.format(java.util.Locale.ROOT, "mirrors: dev facing check (%s, angle %.2f, plane %s %.2f): model x ndc dz real %+.5f mirrored %+.5f; model z ndc dz real %+.5f mirrored %+.5f (one axis flips sign: the mirror image)",
               this.p.vehicle ? "vehicle" : "character", this.useAngle, this.axis == 0 ? "y" : "x", this.c, ax.z - a0.z, mx.z - m0.z, az.z - a0.z, mz.z - m0.z));
         }
         if (this.p.slot != null) {
            this.p.slot.squareDepth = (originZ + 1.0F) / 2.0F + (this.p.vehicle ? 0.0F : 1.0E-4F);
         }
         GL11.glDepthRange(0.0, 1.0);
         GL11.glDepthMask(true);
      }

      @Override
      public void End() {
         Core.getInstance().DoPopIsoStuff();
      }

      /**
       * The layer's depth as iso depth: ndc z = z0 + k (w - w0) for any world point (the ortho view's depth runs along the
       * iso view axis), measured on this camera's unmirrored frame at the model's square and one square east of it.
       */
      boolean calibrate(Frame f) {
         Core core = Core.getInstance();
         core.DoPushIsoStuff(this.x, this.y, this.z, this.useAngle, this.p.vehicle);
         Matrix4f mv = core.modelViewMatrixStack.peek();
         modelB();
         this.bi.set(this.b).invert();
         Matrix4f at = this.scratch.set(core.projectionMatrixStack.peek()).mul(mv).mul(this.bi); // P A T: T's frame -> clip
         at.transform(this.v.set(0F, 0F, 0F, 1F));
         float z0 = this.v.z / this.v.w;
         at.transform(this.v.set(-1F, 0F, 0F, 1F)); // one square east: w + 1
         float z1 = this.v.z / this.v.w;
         core.DoPopIsoStuff();
         float k = z1 - z0;
         if (Math.abs(k) < 1e-9F) {
            return false;
         }
         layerK = 1F / k;
         layerZ0 = z0;
         layerW0 = (this.x - f.view.ox) + (this.y - f.view.oy) + 2F * this.z;
         return true;
      }
   }

   public static String stats() {
      return "mirrors: frames " + frames + ", static draws " + staticDraws + " (" + tileMarches + " pane marches, " + tileAllocs + " tiles, " + atlasResets + " atlas resets, " + atlasFullSkips + " panes without room, " + hiddenSkips + " hidden pane-frames skipped, " + budgetHolds + " held by the pass budget)" + ", composites " + lateDraws + ", model layer frames " + layerFrames + " (kept " + modelReuses + "), mirrored model draws " + modelDraws
            + ", framebuffer queries " + requeries + String.format(java.util.Locale.ROOT, ", render thread us per call: static %.1f, composite %.1f, model flush %.1f per drawn frame", staticCpuNs / 1e3 / Math.max(1, staticDraws),
               lateCpuNs / 1e3 / Math.max(1, lateDraws), flushCpuNs / 1e3 / Math.max(1, layerFrames))
            + String.format(java.util.Locale.ROOT, " (composite: upload %.1f, set-up %.1f, draw %.1f, restore %.1f)", lateSplit[0] / 1e3 / Math.max(1, lateDraws),
               lateSplit[1] / 1e3 / Math.max(1, lateDraws), lateSplit[2] / 1e3 / Math.max(1, lateDraws), lateSplit[3] / 1e3 / Math.max(1, lateDraws))
            + ", people in front of another room's mirror " + crossRoom + ((skipNow & 4194304) != 0 ? " (mirrored: old way)" : " (not mirrored)") + ", unseen pane-frames " + unseenPaneFrames + ", re-marches for a room change " + lightMarches
            + ((skipNow & 8388608) != 0 ? " (reflected: old way)" : " (no reflection)") + ", shared model draws " + Share.shared + " (" + Share.copies + " copies, " + Share.fallbacks + " too large)" + ", " + MirrorGeometry.stats() + (failed ? ", failed" : "");
   }

   // ------------------------------------------------------------------------------------------------ shaders

   static String vert(boolean full) {
      return (full ? "#version 430\n" : "#version 410 core\n") + VERT_BODY.replace("void main() {", ROWS_OUT + "\nvoid main() {").replace("   inst = gl_InstanceID + int(first);", "   inst = gl_InstanceID + int(first);\n" + ROWS_SET);
   }

   /**
    * The pane's data rows, fetched once per vertex and handed to the fragments flat (each fragment fetched up to ten rows of the
    * data texture before: the composite's per-pixel cost on the flip's APU).
    */
   static final String ROWS_OUT, ROWS_SET, ROWS_IN;

   static {
      StringBuilder o = new StringBuilder(), st = new StringBuilder(), in = new StringBuilder();
      for (int i = 0; i < TEX; i++) {
         o.append("flat out vec4 d").append(i).append(";\n");
         in.append("flat in vec4 d").append(i).append(";\n");
         st.append("   d").append(i).append(" = texelFetch(Data, ivec2(").append(i).append(", inst), 0);\n");
      }
      ROWS_OUT = o.toString();
      ROWS_SET = st.toString();
      ROWS_IN = in.toString();
   }

   /** A fragment body with its data-row fetches turned into the flat rows (Config mirrorsFlatRows; dev A/B). */
   static String flatRows(String frag) {
      if (!Config.MIRRORS_FLAT_ROWS) {
         return frag;
      }
      return frag.replace("flat in int inst;", "flat in int inst;\n" + ROWS_IN).replaceAll("texelFetch\\(Data, ivec2\\((\\d), inst\\), 0\\)", "d$1");
   }

   static final String VERT_BODY = String.join("\n",
         "uniform sampler2D Data;",
         "uniform vec4 mapA;", // u = mapA.x px + mapA.y, v = mapA.z py + mapA.w (window px, iso relative to the origin square)
         "uniform vec4 vp;",
         "uniform vec4 mapC;", // kC, cC: iso depth w = kC depth + cC
         "uniform vec4 depthTest;", // x on: the quad at the pane's own depth, y the slack towards the camera (iso depth units)
         "uniform float first;",
         "flat out int inst;",
         "out vec2 sUv;",
         "out vec2 mUv;",
         "void main() {",
         "   inst = gl_InstanceID + int(first);",
         "   vec4 q = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 s = texelFetch(Data, ivec2(1, inst), 0);",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   int k = gl_VertexID;",
         "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
         "   vec2 iso = mix(q.xy, q.zw, c);",
         "   vec2 px = vec2((iso.x - mapA.y) / mapA.x, (iso.y - mapA.w) / mapA.z);",
         "   sUv = mix(s.xy, s.zw, c);",
         "   mUv = mix(m.xy, m.zw, c);",
         "   float z = 0.0;",
         "   if (depthTest.x > 1.5) {", // a prop (mirrorsPropConservativeDepth): the nearest its surface can be, the front top corner of its square; its fragments write their own depth, never nearer
         "      vec4 sq9 = texelFetch(Data, ivec2(9, inst), 0);",
         "      float w = sq9.x + sq9.y + 2.0 * sq9.z + 4.0 + depthTest.y;",
         "      z = 2.0 * (w - mapC.y) / mapC.x - 1.0;",
         "   } else if (depthTest.x > 0.5) {", // the pane's depth under this corner (the plane is linear on screen): the hardware test hides it behind whatever stands in front
         "      vec4 pl = texelFetch(Data, ivec2(3, inst), 0);",
         "      vec3 P = pl.x < 0.5 ? vec3(iso.x + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - iso.x, 0.0);",
         "      P.z = (P.x + P.y - iso.y) / 6.0;",
         "      float w = P.x + P.y + 2.0 * P.z + depthTest.y;",
         "      z = 2.0 * (w - mapC.y) / mapC.x - 1.0;",
         "   }",
         "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, z, 1.0);",
         "}");

   /** GL 4.1 static pass: the due panes' tiles rasterised in the atlas (tile space), with the pane coordinates per texel. */
   static String vertTile(boolean full) {
      return vertTileBody(full).replace("void main() {", ROWS_OUT + "\nvoid main() {").replace("   inst = gl_InstanceID + int(first);", "   inst = gl_InstanceID + int(first);\n" + ROWS_SET);
   }

   private static String vertTileBody(boolean full) {
      return String.join("\n",
         full ? "#version 430" : "#version 410 core",
         "uniform sampler2D Data;",
         "uniform vec4 mapA;",
         "uniform float first;",
         "flat out int inst;",
         "out vec2 sUv;",
         "out vec2 mUv;",
         "out vec2 lpx;",
         "out vec2 tq;",
         "void main() {",
         "   inst = gl_InstanceID + int(first);",
         "   vec4 q = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 s = texelFetch(Data, ivec2(1, inst), 0);",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 tile = texelFetch(Data, ivec2(5, inst), 0);",
         "   int k = gl_VertexID;",
         "   vec2 c = vec2((k == 1 || k == 2) ? 1.0 : 0.0, k >= 2 ? 1.0 : 0.0);",
         "   vec2 quadPx = max(vec2((q.z - q.x) / mapA.x, (q.w - q.y) / abs(mapA.z)), vec2(1.0));",
         "   lpx = c * tile.zw;", // px from the quad's top-left (the composite reads texel floor(local))
         "   tq = lpx / quadPx;",
         "   sUv = mix(s.xy, s.zw, tq);",
         "   mUv = mix(m.xy, m.zw, tq);",
         "   gl_Position = vec4((tile.xy + lpx) / " + ATLAS + ".0 * 2.0 - 1.0, 0.0, 1.0);",
         "}");
   }

   static final int K_STATIC_IMAGE = 0, K_STATIC_FBO = 1, K_LATE = 2, K_LATE_PROP = 3;

   /** The fragment programs: the static march storing into the atlas images (GL 4.3), the same rendering into the atlas
    * framebuffer (GL 4.1: macOS), the composite. full: GL 4.3 (image stores, the visibility buffer), else GLSL 4.10 core. */
   static String frag(int kind, boolean full) {
      boolean stat = kind == K_STATIC_IMAGE || kind == K_STATIC_FBO, fbo = kind == K_STATIC_FBO, prop = kind == K_LATE_PROP;
      return String.join("\n",
         full ? "#version 430" : "#version 410 core\n#define PZ_NO_VIS",
         stat ? "#define PZ_STATIC" : "",
         stat && Config.MIRRORS_HIZ ? "#define PZ_HIZ" : "",
         // the props' composite: each texel's own surface depth to the hardware depth test (gl_FragDepth): reading the frame's
         // depth as a texture made AMD / Mesa decompress the whole depth buffer, ~210 us a frame on the flip (prop-v3)
         prop ? "#define PZ_PROPDEPTH" : "",

         "#define GLOSS_RAYS " + Math.max(1, Math.min(16, Config.MIRRORS_PROP_GLOSS_RAYS)),
         kind == K_STATIC_IMAGE ? "#extension GL_ARB_shader_image_load_store : require" : "",
         !stat && full ? "#extension GL_ARB_shader_storage_buffer_object : require" : "",
         !stat && !prop && full ? "layout(early_fragment_tests) in;" : "", // (the composite runs only where the pane is in front: the depth test first)
         !stat && full ? "layout(std430) buffer VisBuf { uint vis[]; };" : "",
         prop && full && Config.MIRRORS_PROP_CONSERVATIVE_DEPTH ? "layout(depth_greater) out float gl_FragDepth;" : "", // (GLSL 4.20 core; after every #extension line: Mesa refuses a declaration before one)
         stat ? "" : "uniform int visRow;",
         stat ? "" : "uniform int visOn;",
         "uniform sampler2D Data;",
         "uniform sampler2D WorldColor;",
         "uniform sampler2D WorldDepth;",
         "uniform sampler2D Sprite;",
         "uniform sampler2D Masks;",
         "uniform sampler2D StaticTex;",
         "uniform sampler2D LayerColor;",
         "uniform sampler2D LayerDepth;",
         kind == K_STATIC_IMAGE ? (GLOSS_ACCUM ? "#define PZ_ACCUM\nlayout(rgba8) uniform image2D Static;" : "layout(rgba8) writeonly uniform image2D Static;") : "",
         "uniform vec4 metal;", // x: steel / ceramic composited as a modulation (mirrorsPropMetalBlend)
         "uniform vec4 gloss;", // the static pass's jitter seed, the glossy history's blend weight
         kind == K_STATIC_IMAGE ? "layout(rg8) writeonly uniform image2D Glass;" : "",
         stat ? "" : "uniform sampler2D GlassTex;",
         stat ? "" : "uniform sampler2D StaticMip;",
         stat ? "" : "uniform sampler2D LayerTopColor;", // (the models mirrored in the props' tops)
         stat ? "" : "uniform sampler2D LayerTopDepth;", // (the static atlas through a trilinear sampler: mirrorsPropGloss=mip)
         stat ? "uniform sampler2D GeomTex;" : "",
         stat ? "uniform vec4 skyTop;" : "", // an outdoor top's sky where its ray finds nothing (rgb, on)
         stat ? "uniform vec4 sunTop;" : "", // the sun in a top's mirror direction (rgb, cos to it)
         "uniform sampler2D PropTex;",
         "uniform vec4 mapA;", // kA, cA, kB, cB
         "uniform vec4 mapC;", // kC, cC (iso depth w = kC depth + cC), -, the longest ray (squares)
         "uniform vec4 vp;",
         "uniform vec4 march;", // most taps, thickness (iso depth units), stand-in strength, px between taps
         "uniform vec4 layerMap;", // on, 1 / k, z0, w0: iso depth of a layer texel = w0 + (ndc z - z0) / k
         stat ? "" : "uniform vec4 depthTest;", // (z: the layer's resolution scale)
         "uniform vec4 dev;", // view, skip bits, static on
         "uniform int skip2;", // devMirrorsSkip2
         "flat in int inst;",
         "in vec2 sUv;",
         "in vec2 mUv;",
         fbo ? "in vec2 lpx;" : "", // the texel's px offset in its pane (tile space)
         fbo ? "in vec2 tq;" : "", // its place in the sprite's quad (0..1 inside)
         fbo ? "layout(location = 0) out vec4 fragColor;" : "out vec4 fragColor;",
         fbo ? "layout(location = 1) out vec4 glassOut;" : "",
         FUNCS,
         fbo ? MAIN_STATIC_FBO : (stat ? MAIN_STATIC_IMAGE : MAIN_LATE)).transform(Mirrors::flatRows);
   }

   static final String FUNCS = String.join("\n",
         "float isoDepth(vec2 px) { return mapC.x * texelFetch(WorldDepth, ivec2(px), 0).r + mapC.y; }",
         "bool inside(vec2 px) { return px.x >= vp.x && px.y >= vp.y && px.x < vp.x + vp.z && px.y < vp.y + vp.w; }",
         // a mirror's people stand where their own line of sight leaves pane point P: out, vl squares sideways (lateral - vl t)
         // and vs levels down a square out (viewLateral / viewDrop; the camera's reflected ray is vl 1, vs 1/3). The first
         // thing the frame shows on that line (marched before the characters draw: never the person) hides a person standing
         // behind it; the camera's slanted ray (marchRay) is another line, which meets a side wall or the furniture beside
         // the glass first: a person in front of a mirror next to a wall showed as a sliver (maintainer, 2026-10-09). Stored
         // in the glass's G as 1 - distance / reach (0: nothing in the way within reach, the cleared value)
         "#ifdef PZ_STATIC",
         "float losOut(vec3 P, float axis, float vl, float vs, float reach, float floorZ) {",
         "   float tEnd = min(reach, vs > 0.001 ? (P.z - floorZ) / vs - 0.05 : reach);", // (past its floor the line is under the people's feet)
         "   float t0 = min(0.2, 0.5 * tEnd);", // (the glass's own frame and wall, as marchRay)
         "   if (tEnd <= t0) return 0.0;",
         "   vec2 pxPerT = axis < 0.5 ? vec2((-1.0 - vl) / mapA.x, (1.0 - vl + 6.0 * vs) / mapA.z) : vec2((1.0 + vl) / mapA.x, (1.0 - vl + 6.0 * vs) / mapA.z);",
         "   vec2 px0 = vec2((P.x - P.y - mapA.y) / mapA.x, (P.x + P.y - 6.0 * P.z - mapA.w) / mapA.z);",
         "   float wM = P.x + P.y + 2.0 * P.z, dw = 1.0 - vl - 2.0 * vs;", // (iso depth along the line)
         "   int n = int(clamp(length(pxPerT) * (tEnd - t0) / march.w, 4.0, march.x));",
         "   for (int i = 1; i <= 64; i++) {",
         "      if (i > n) break;",
         "      float t = t0 + (tEnd - t0) * float(i) / float(n);",
         "      vec2 px = px0 + pxPerT * t;",
         "      if (!inside(px)) return 0.0;",
         "      float d = isoDepth(px) - (wM + dw * t);",
         "      if (d >= 0.0 && d < march.y) return 1.0 - clamp(t / reach, 0.0, 1.0);",
         "   }",
         "   return 0.0;",
         "}",
         "#endif",
         // the reflected ray from pane point P (x, y, z relative; z in levels): screen line px0 + pxPerT t, iso depth wM - 2t/3,
         // height z - t/3; the first tap where the scene stands in front of the ray within the thickness, refined
         // dev view 5: how each ray ended (green floor shortcut, blue marched hit, magenta a marched hit under the pane's own
         // floor, yellow floor landing hidden (the floor seen last stands in), orange the same with the landing pixel, red
         // reach fallback, black miss)
         "vec4 kindOut(vec4 r, vec3 k) { return dev.x == 5.0 ? vec4(k, r.a) : r; }",
         // a hit: colour, and in alpha the distance (t / reach, 127 steps) with a stand-in flag in its lowest bit (the
         // composite draws a stand-in, the floor guessed where the camera cannot see it, at mirrorsStandInPct); 255 = none
         "vec4 hitOut(vec3 c, float t, float standIn) { return vec4(c, (floor(clamp(t / mapC.w, 0.0, 0.99) * 126.0 + 0.5) * 2.0 + standIn) / 255.0); }",
         // Hi-Z (mirrorsHiZ): the nearest scene (largest iso depth) of each 16 x 16 px block, built at the static pass; a block
         // where the whole scene stands behind the ray's iso depth at its exit cannot hold a hit: the march jumps to the exit
         "#ifdef PZ_HIZ",
         "uniform sampler2D HiZ;",
         "float hizExit(vec2 px, vec2 pxPerT, float t, float wAt, float dwdt) {",
         "   vec2 b = floor(px / 16.0);",
         "   float tx = pxPerT.x > 0.001 ? ((b.x + 1.0) * 16.0 - px.x) / pxPerT.x : pxPerT.x < -0.001 ? (px.x - b.x * 16.0) / -pxPerT.x : 1e9;",
         "   float ty = pxPerT.y > 0.001 ? ((b.y + 1.0) * 16.0 - px.y) / pxPerT.y : pxPerT.y < -0.001 ? (px.y - b.y * 16.0) / -pxPerT.y : 1e9;",
         "   float te = min(tx, ty) + 0.01;",
         "   float cmax = texelFetch(HiZ, ivec2(b), 0).r;",
         "   return cmax < wAt + dwdt * te ? t + te : t;", // (dwdt < 0: the ray's iso depth falls; the smallest is at the exit)
         "}",
         "#endif",
         "vec4 marchRay(vec3 P, float axis, float tMax, float zMin, float floorZ) {",
         "   float tEnd = min(tMax, max(0.05, (P.z - zMin) * 3.0));",
         "   vec2 pxPerT = vec2((axis < 0.5 ? -2.0 : 2.0) / mapA.x, 2.0 / mapA.z);",
         "   vec2 px0 = vec2((P.x - P.y - mapA.y) / mapA.x, (P.x + P.y - 6.0 * P.z - mapA.w) / mapA.z);",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         // floor first: the ray meets the pane's own floor at tf; when the frame shows that floor there and half a march's
         // taps find nothing standing in the ray's way before it, that is the hit (no refinement needed)
         "   float tf = (P.z - floorZ) * 3.0;",
         // the ray's first 0.2 squares are the pane's own surroundings: the mirror sprite and its wall stand there in the
         // frame's depth, within the thickness of the ray's own depth; at zoom 0.25 the taps (10 px apart) are 0.08 squares
         // apart and the first ones "hit" the mirror itself: its glass colour speckled over the reflection in triangles
         // (maintainer's screenshot 2026-10-04). Nothing else stands that close in front of the glass (dev bit 262144: off)
         "   float t0 = (int(dev.y) & 262144) != 0 ? 0.0 : min(0.2, 0.5 * tEnd);",
         "   vec2 pf = px0 + pxPerT * tf;",
         "   if ((int(dev.y) & 16) == 0 && tf > 0.05 && tf < tEnd + 0.01) {",
         "      if (inside(pf) && abs(isoDepth(pf) - (wM - 0.6666667 * tf)) < 0.25) {",
         "         bool clear = true;",
         "         int kk = max(3, int(clamp(length(pxPerT) * tf / march.w, 4.0, march.x)) / 2);", // half the taps a full march of tf takes (harness/mirrors/march_sim.py: same error as the full march)
         "         for (int k = 1; k <= 32; k++) {",
         "            if (k > kk) break;",
         "            float t = t0 + max(tf - t0, 0.0) * float(k) / (float(kk) + 1.0);",
         "            float d = isoDepth(px0 + pxPerT * t) - (wM - 0.6666667 * t);",
         "            if (d >= 0.0 && d < march.y) { clear = false; break; }",
         "         }",
         "         if (clear) return kindOut(hitOut(texelFetch(WorldColor, ivec2(pf), 0).rgb, tf, 0.0), vec3(0.0, 1.0, 0.0));",
         "      }",
         "   }",
         "   int n = int(clamp(length(pxPerT) * tEnd / march.w, 4.0, march.x));",
         "   float dt = (tEnd - t0) / float(n);",
         "   float tLo = t0, tHit = -1.0;",
         "   vec2 lastFloor = vec2(-1.0);", // the last tap whose pixel shows the pane's own floor (iso depth of that floor there)
         "   for (int i = 1; i <= 64; i++) {",
         "      if (i > n) break;",
         "      float t = t0 + dt * float(i);",
         "      vec2 px = px0 + pxPerT * t;",
         "      if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "#ifdef PZ_HIZ",
         "      float tj = hizExit(px, pxPerT, t, wM - 0.6666667 * t, -0.6666667);",
         "      if (tj > t) { i = max(i, int(ceil((tj - t0) / dt)) - 1); tLo = t; continue; }",
         "#endif",
         "      float iz = isoDepth(px);",
         "      float d = iz - (wM - 0.6666667 * t);",
         "      if (d >= 0.0 && d < march.y) { tHit = t; break; }",
         "      if (t < tf && abs(iz - (P.x + P.y - 6.0 * P.z + 2.0 * t + 8.0 * floorZ)) < 0.25) lastFloor = px;",
         "      tLo = t;",
         "   }",
         "   if (tHit < 0.0) {",
         "      if ((P.z - zMin) * 3.0 > tMax) return vec4(0.0, 0.0, 0.0, 1.0);", // out of reach before any floor
         // nothing in the ray's way and its landing on the pane's own floor hidden from the camera (a table, chairs, a
         // bathtub standing there): the floor goes on under what hides it, the last floor pixel the ray passed over stands
         // in, flagged as a stand-in (drawn at mirrorsStandInPct); without one, the landing pixel. (Maintainer's save,
         // 2026-10-04: the ray's end one level under the floor, the old stand-in, showed a pixel squares away, the house's
         // siding as grey bands across a wall mirror's upper half; the landing pixel alone made the dining table's grey top
         // one flat slab over it.)
         "      if ((int(dev.y) & 16) == 0 && (int(dev.y) & 32768) == 0 && tf > 0.05 && tf < tEnd + 0.01 && inside(pf)) {",
         "         vec2 sp = lastFloor.x >= 0.0 && (int(dev.y) & 65536) == 0 ? lastFloor : pf;",
         "         return kindOut(hitOut(texelFetch(WorldColor, ivec2(sp), 0).rgb, tf, 1.0), sp == pf ? vec3(1.0, 0.5, 0.0) : vec3(1.0, 1.0, 0.0));",
         "      }",
         "      tHit = tEnd;", // the floor under the ray (hidden from the camera: its colour on screen stands in)
         "      vec2 pe = px0 + pxPerT * tHit;",
         "      if (!inside(pe)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "      return kindOut(hitOut(texelFetch(WorldColor, ivec2(pe), 0).rgb, tHit, 1.0), vec3(1.0, 0.0, 0.0));",
         "   } else {",
         "      float a = tLo, b = tHit;",
         "      for (int k = 0; k < 4; k++) {",
         "         float m = 0.5 * (a + b);",
         "         float d = isoDepth(px0 + pxPerT * m) - (wM - 0.6666667 * m);",
         "         if (d >= 0.0 && d < march.y) b = m; else a = m;",
         "      }",
         "      tHit = b;",
         // A "hit" in the last 0.15 levels above the pane's own floor, with a floor seen on the way: the ray reached the
         // floor behind what hides it. The thickness test took a thin cut-away wall stub there for a solid (a medicine
         // cabinet reflected the bathroom's outer brick strip, maintainer's save 2026-10-04): the floor goes on instead.
         "      if ((int(dev.y) & 32768) == 0 && tf > 0.05 && tHit > tf - 0.45 && lastFloor.x >= 0.0) return kindOut(hitOut(texelFetch(WorldColor, ivec2(lastFloor), 0).rgb, tf, 1.0), vec3(1.0, 1.0, 0.0));",
         "   }",
         "   vec2 px = px0 + pxPerT * tHit;",
         "   if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "   return kindOut(hitOut(texelFetch(WorldColor, ivec2(px), 0).rgb, tHit, 0.0), tf > 0.05 && tHit > tf + 0.1 ? vec3(1.0, 0.0, 1.0) : vec3(0.0, 0.3, 1.0));",
         "}",
         // a reflective prop's texel (pzopt.Props atlas): its face (0 none, 1 top, 2 south, 3 east; a flipped sprite's south and
         // east faces swap), the face's plane offset from the square's north floor corner, its reflectance
         "int propFace(vec2 uv, float flip, out float off, out float refl) {",
         "   ivec2 sz = textureSize(PropTex, 0);",
         "   vec2 g = texelFetch(PropTex, clamp(ivec2(uv * vec2(sz)), ivec2(0), sz - ivec2(1)), 0).rg;",
         "   int r = int(g.r * 255.0 + 0.5);",
         "   off = float(r & 63) / 32.0 - 0.25;",
         "   refl = g.g;",
         "   int face = r >> 6;",
         "   if (flip > 0.5 && face >= 2) face = 5 - face;",
         "   return face;",
         "}",
         // the point of the face under (u, v): a top z = c, a south face y = c, an east face x = c (sq: the square, relative)
         "vec3 propPoint(int face, float off, float u, float v, vec3 sq) {",
         "   if (face == 1) { float z = sq.z + off; float s = v + 6.0 * z; return vec3(0.5 * (s + u), 0.5 * (s - u), z); }",
         "   vec3 P = face == 2 ? vec3(u + sq.y + off, sq.y + off, 0.0) : vec3(sq.x + off, sq.x + off - u, 0.0);",
         "   P.z = (P.x + P.y - v) / 6.0;",
         "   return P;",
         "}",
         // a top's reflected ray: the view ray (-1, -1, -1/3) a square mirrored in z, (-1, -1, +1/3): straight up its screen
         // column (v falls 4 a square), the iso depth falls 4/3 a square, it climbs a third of a level a square (never meets
         // the floor); taps, then 4 bisections; a miss leaves the prop's own look
         "#ifdef PZ_STATIC",
         // an outdoor top's ray that finds nothing (or leaves the screen) shows the sky, and the sun when it stands in the
         // mirror direction (a lobe as wide as the surface is rough)
         "vec4 topMiss(bool outside, float rough, float tMax) {",
         "   if (!outside || skyTop.w < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);",
         "   float r2 = max(rough * rough, 0.0009);",
         "   vec3 c = skyTop.rgb + sunTop.rgb * exp(-(1.0 - sunTop.w) / r2) * (0.0009 / r2);",
         "   return kindOut(hitOut(min(c, vec3(1.0)), tMax * 0.98, 0.0), vec3(0.0, 1.0, 1.0));",
         "}",
         "#else",
         "vec4 topMiss(bool outside, float rough, float tMax) { return vec4(0.0, 0.0, 0.0, 1.0); }",
         "#endif",
         "vec4 marchTop(vec3 P, float tMax, bool outside, float rough) {",
         "   vec2 pxPerT = vec2(0.0, -4.0 / mapA.z);",
         "   vec2 px0 = vec2((P.x - P.y - mapA.y) / mapA.x, (P.x + P.y - 6.0 * P.z - mapA.w) / mapA.z);",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         "   float t0 = 0.05;",
         "   int n = int(clamp(length(pxPerT) * tMax / march.w, 4.0, march.x));",
         "   float dt = (tMax - t0) / float(n);",
         "   float tLo = t0, tHit = -1.0;",
         "   for (int i = 1; i <= 64; i++) {",
         "      if (i > n) break;",
         "      float t = t0 + dt * float(i);",
         "      vec2 px = px0 + pxPerT * t;",
         "      if (!inside(px)) return topMiss(outside, rough, tMax);",
         "#ifdef PZ_HIZ",
         "      float tj = hizExit(px, pxPerT, t, wM - 1.3333333 * t, -1.3333333);",
         "      if (tj > t) { i = max(i, int(ceil((tj - t0) / dt)) - 1); tLo = t; continue; }",
         "#endif",
         "      float d = isoDepth(px) - (wM - 1.3333333 * t);",
         "      if (d >= 0.0 && d < march.y) { tHit = t; break; }",
         "      tLo = t;",
         "   }",
         "   if (tHit < 0.0) return topMiss(outside, rough, tMax);",
         "   float a = tLo, b = tHit;",
         "   for (int k = 0; k < 4; k++) {",
         "      float m = 0.5 * (a + b);",
         "      float d = isoDepth(px0 + pxPerT * m) - (wM - 1.3333333 * m);",
         "      if (d >= 0.0 && d < march.y) b = m; else a = m;",
         "   }",
         "   vec2 px = px0 + pxPerT * b;",
         "   if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "   return kindOut(hitOut(texelFetch(WorldColor, ivec2(px), 0).rgb, b, 0.0), vec3(0.0, 0.3, 1.0));",
         "}",
         // a reflected ray in any direction D (x, y squares, z levels a step of t): taps and bisection; one that passes under its
         // floor takes the floor where it meets it (glossy rays, jittered off the mirror direction)
         "vec4 marchDir(vec3 P, vec3 D, float tMax, float zMin) {",
         "   vec2 pxPerT = vec2((D.x - D.y) / mapA.x, (D.x + D.y - 6.0 * D.z) / mapA.z);",
         "   float dw = D.x + D.y + 2.0 * D.z;",
         "   vec2 px0 = vec2((P.x - P.y - mapA.y) / mapA.x, (P.x + P.y - 6.0 * P.z - mapA.w) / mapA.z);",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         "   float tEnd = D.z < -0.001 ? min(tMax, (P.z - zMin) / -D.z) : tMax;",
         "   float t0 = min(0.2, 0.5 * tEnd);",
         "   int n = int(clamp(length(pxPerT) * tEnd / march.w, 4.0, 24.0));",
         "   float dt = (tEnd - t0) / float(n), tLo = t0, tHit = -1.0;",
         "   for (int i = 1; i <= 24; i++) {",
         "      if (i > n) break;",
         "      float t = t0 + dt * float(i);",
         "      vec2 px = px0 + pxPerT * t;",
         "      if (!inside(px)) return vec4(0.0, 0.0, 0.0, 1.0);",
         "      float d = isoDepth(px) - (wM + dw * t);",
         "      if (d >= 0.0 && d < march.y) { tHit = t; break; }",
         "      tLo = t;",
         "   }",
         "   if (tHit < 0.0) {",
         "      if (D.z >= -0.001) return vec4(0.0, 0.0, 0.0, 1.0);",
         "      vec2 pe = px0 + pxPerT * tEnd;", // the floor where the ray meets it (hidden or not: a glossy guess)
         "      return inside(pe) ? hitOut(texelFetch(WorldColor, ivec2(pe), 0).rgb, tEnd, 0.0) : vec4(0.0, 0.0, 0.0, 1.0);",
         "   }",
         "   float a = tLo, b = tHit;",
         "   for (int k = 0; k < 3; k++) {",
         "      float m = 0.5 * (a + b);",
         "      float d = isoDepth(px0 + pxPerT * m) - (wM + dw * m);",
         "      if (d >= 0.0 && d < march.y) b = m; else a = m;",
         "   }",
         "   vec2 px = px0 + pxPerT * b;",
         "   return inside(px) ? hitOut(texelFetch(WorldColor, ivec2(px), 0).rgb, b, 0.0) : vec4(0.0, 0.0, 0.0, 1.0);",
         "}",
         // a glossy prop (mirrorsPropGloss=bake): mirrorsPropGlossRays rays jittered round the mirror direction (a rotated grid
         // of the surface's roughness, turned per texel), averaged at the static pass, so the composite reads one sharp tap
         "vec4 glossRays(vec3 P, int face, vec4 pl, vec4 st, vec4 pr, float rough) {",
         "   vec3 D = face == 1 ? vec3(-1.0, -1.0, 1.0 / 3.0) : face == 2 ? vec3(-1.0, 1.0, -1.0 / 3.0) : vec3(1.0, -1.0, -1.0 / 3.0);",
         "   vec3 side = face == 1 ? vec3(0.7071, -0.7071, 0.0) : face == 2 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);", // across the mirror direction, in the surface
         "   float tMax = face == 1 ? pr.z : st.z;",
         "   float zMin = face == 1 ? -100.0 : pl.z;",
         "   float h = fract(sin(dot(gl_FragCoord.xy + gloss.x * vec2(7.31, 3.17), vec2(12.9898, 78.233))) * 43758.547) * 6.2832;", // (turned each refresh: the history gathers new directions)
         "   vec3 acc = vec3(0.0); float tAcc = 0.0; int hits = 0;",
         "   for (int i = 0; i < GLOSS_RAYS; i++) {",
         "      float an = h + 6.2832 * (float(i) + 0.5) / float(GLOSS_RAYS);",
         "      float rr = rough * sqrt((float(i) + 0.5) / float(GLOSS_RAYS));",
         "      vec3 Dj = D + side * (rr * cos(an)) + vec3(0.0, 0.0, rr * sin(an) / 3.0);",
         "      vec4 r = marchDir(P, Dj, tMax, zMin);",
         "      if (r.a < 0.995) { acc += r.rgb; tAcc += floor(r.a * 255.0 + 0.5); hits++; }",
         "   }",
         "   if (hits == 0) return face == 1 ? topMiss(pr.y > 1.5, rough, tMax) : vec4(0.0, 0.0, 0.0, 1.0);",
         "   float code = floor(tAcc / float(hits) * 0.5) * 2.0;", // the mean distance step (no stand-in bit)
         "   return vec4(acc / float(hits), code / 255.0);",
         "}",
         // a prop texel's reflection: a top up its column, a south / east face as a pane of that orientation
         "vec4 propRay(vec3 P, int face, vec4 pl, vec4 st, vec4 pr, float rough) {",
         "   if (rough < 0.0) return glossRays(P, face, pl, st, pr, -rough);",
         "   return face == 1 ? marchTop(P, pr.z, pr.y > 1.5, rough) : marchRay(P, face == 2 ? 0.0 : 1.0, st.z, pl.z, pl.w);",
         "}",
         // the room geometry (MirrorGeometry) where the march could not see what the ray meets: no hit, a stand-in, or a hit
         // farther than the geometry (the march passed behind something the camera cannot see round: the bathtub's far
         // side); the march's own hit, the frame's real pixel, where the camera sees that surface. Dev view 5: cyan, 6: the
         // geometry alone
         "#ifdef PZ_STATIC",
         "vec4 withGeom(vec4 r, ivec2 at) {",
         "   vec4 g7 = texelFetch(Data, ivec2(7, inst), 0);",
         "   if (g7.x < 0.5 || (int(dev.y) & 131072) != 0) return dev.x == 6.0 ? vec4(0.0, 0.0, 0.0, 1.0) : r;",
         "   vec4 g = texelFetch(GeomTex, at, 0);",
         "   float gc = floor(g.a * 255.0 + 0.5);",
         "   bool furn = gc < 254.5 && mod(gc, 2.0) > 0.5;", // (MirrorGeometry: furniture standing in the room)
         "   if (furn) { gc -= 1.0; g.a = gc / 255.0; }",
         "   if (dev.x == 6.0) return gc < 254.5 ? g : vec4(0.0, 0.0, 0.0, 1.0);",
         "   if (gc > 254.5) return r;",
         "   float rc = floor(r.a * 255.0 + 0.5);",
         "   bool real = rc < 254.5 && mod(rc, 2.0) < 0.5;",
         "   float tR = floor(rc * 0.5) / 126.0 * mapC.w, tG = floor(gc * 0.5) / 126.0 * mapC.w;",
         // furniture is drawn per frame often (the translucent layer: a sink, its tap), after this pass reads the frame: the
         // march saw the counter's cut-out under it, a black hole, where the reflected sink belongs (maintainer, 2026-10-10).
         // The room's furniture wins over a marched hit unless the camera clearly sees something nearer (dev skip bit
         // 268435456: the march's hit wins as before)
         // (within a square of the glass always: the mirror shows the side of it the camera never sees, its turned sprite;
         // the march's hit there is the camera's view of the counter's foot, often its dark cut-out under the basin)
         "   bool furnWins = furn && (tG <= tR + 0.35 || tG <= 1.0) && (int(dev.y) & 268435456) == 0;",
         // a pure black hit where the room has a texel is a hole in the frame this pass reads (an object drawn later: the
         // counter's cut-out under the basin), not a black surface: the room's geometry, lit by the same squares, stands in
         "   bool hole = max(r.r, max(r.g, r.b)) < 0.09 && (int(dev.y) & 268435456) == 0;", // (the cut-out reads 18 / 255)
         // a marched hit well in front of the room's geometry is a thickness hit (the ray passed behind a thin rack upright
         // and the march took its pixels: a torn, streaky second copy beside the geometry's own rack, the maintainer's gym,
         // 2026-10-16); what the room really holds there is in the geometry (dev skip2 bit 1: the march's hit wins as before)
         // and the switch between the two is a blend over the tolerance's upper half: a hard switch drew a jagged comb along
         // the reflected wall where neighbouring rays fell either side of it (a wider band let the rack's pixels show faintly)
         // (a negative tolerance: the room is closed to the camera, MirrorGeometry.roomClosed; the frame shows its walls' outer
         // faces where the room was, and a marched hit there agrees with the geometry's far wall by chance). The geometry's
         // colour then, with the march's distance code: the mirrored people are clipped by it, and the geometry's code cut
         // the player walking in at the bathroom door into two images (2026-10-16)
         "   if (real && !furnWins && !hole && g7.y < 0.0 && abs(tR - tG) < -g7.y) return dev.x == 5.0 ? vec4(0.0, 1.0, 1.0, r.a) : vec4(g.rgb, r.a);",
         "   if (real && !furnWins && !hole && g7.y > 0.0) {",
         "      float dd = tR - tG;",
         "      float a = (skip2 & 1) != 0 ? step(g7.y, dd) : smoothstep(0.5 * g7.y, g7.y, abs(dd));",
         "      if (a < 0.001) return r;",
         "      if (a < 0.999 && dev.x != 5.0) return vec4(mix(r.rgb, g.rgb, a), a < 0.5 ? r.a : g.a);",
         "   }",
         "   return dev.x == 5.0 ? vec4(0.0, 1.0, 1.0, g.a) : g;",
         "}",
         // the mirrored people's line of sight (losOut's code) shortened by the room's furniture within a square of the glass:
         // the reflected sink and its tap stand in front of whoever stands behind them. (Only near furniture: the geometry is
         // drawn along the camera's slanted ray, which is a square sideways a square out; walls never hide a person in their
         // own room, so they are left out.) Dev skip bit 268435456: off
         "float losGeom(float enc, ivec2 at) {",
         "   vec4 g7 = texelFetch(Data, ivec2(7, inst), 0);",
         "   if (g7.x < 0.5 || (int(dev.y) & (131072 | 268435456)) != 0) return enc;",
         "   float gc = floor(texelFetch(GeomTex, at, 0).a * 255.0 + 0.5);",
         "   if (gc > 254.5 || mod(gc, 2.0) < 0.5) return enc;",
         "   float tG = floor(gc * 0.5) / 126.0 * mapC.w;",
         "   float reach = texelFetch(Data, ivec2(4, inst), 0).z;",
         "   return tG <= 1.0 ? max(enc, 1.0 - clamp(tG / reach, 0.0, 1.0)) : enc;",
         "}",
         "#endif");

   private static String oldMain(boolean stat) {
      return String.join("\n",
         "void main() {",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 pl = texelFetch(Data, ivec2(3, inst), 0);", // axis, c, lowest floor, level
         "   vec4 st = texelFetch(Data, ivec2(4, inst), 0);", // strength, alpha, reach, static
         "   vec4 pr = texelFetch(Data, ivec2(8, inst), 0);", // a prop: class, alpha mode, top reach, flip
         "   vec4 sq = texelFetch(Data, ivec2(9, inst), 0);", // its square (relative)
         "   int face = 0; float poff = 0.0, prefl = 0.0;",
         stat ? "   if (pr.x > 0.5) face = propFace(mUv, pr.w, poff, prefl);" : "",
         "   float mask;",
         // visibility feedback: "this pane has pixels on screen" as a plain store from one px in 16 (an atomic add from every
         // px of a pane on one counter serialised them: the composite went 8 -> 73 us)
         stat ? "" : "#ifndef PZ_NO_VIS\n   if (visOn != 0 && ((int(gl_FragCoord.x) | int(gl_FragCoord.y)) & 3) == 0) vis[visRow + inst] = 1u;\n#endif",
         "   vec2 fc = gl_FragCoord.xy;",
         "   float u = mapA.x * fc.x + mapA.y, v = mapA.z * fc.y + mapA.w;",
         // the pane's own coordinates (px from its quad's top-left at this zoom, pan-free) and its atlas tile
         "   vec4 q0 = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 tile = texelFetch(Data, ivec2(5, inst), 0);",
         "   vec4 t6 = texelFetch(Data, ivec2(6, inst), 0);", // mirror, texels per px
         "   vec2 local = vec2((u - q0.x) / mapA.x, (v - q0.y) / abs(mapA.z));",
         "   ivec2 lp = ivec2(floor(local * t6.zw));", // (t6.zw: tile px per px, 1 unless the tile was marched at another zoom)
         "   ivec2 texel = clamp(lp, ivec2(0), ivec2(tile.zw) - 1);",
         stat ? String.join("\n",
         "   vec4 sp = texture(Sprite, sUv);",
         "   mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a);",
         "   if (pr.x > 0.5) mask = face > 0 ? prefl * (mod(pr.y, 2.0) > 0.5 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : step(0.5, sp.a)) : 0.0;",
         "   if (all(greaterThanEqual(lp, ivec2(0)))) imageStore(Glass, ivec2(tile.xy) + texel, vec4(mask));", // the pane's glass, for the composite
         "   if (mask < 0.004 && (t6.y > 0.75 || ((lp.x | lp.y) & 1) != 0)) discard;")
         : String.join("\n",
         "   mask = st.w > 0.5 ? texelFetch(GlassTex, ivec2(tile.xy) + texel, 0).r : 0.0;",
         "   if (dev.w < 0.5) { vec4 sp = texture(Sprite, sUv); mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a); }",
         "#ifdef PZ_PROPDEPTH",
         "   if (dev.x == 7.0 || dev.x == 8.0) gl_FragDepth = gl_FragCoord.z;", // (every path of the props' program writes its depth)
         "#endif",
         "   if (dev.x == 7.0) { vec4 lr = texelFetch(LayerColor, ivec2(gl_FragCoord.xy * depthTest.z), 0); fragColor = vec4(lr.rgb, 0.5 + 0.5 * lr.a); return; }", // dev: the raw model layer over every reflector quad
         "   if (dev.x == 8.0) { vec4 lr = texelFetch(LayerTopColor, ivec2(gl_FragCoord.xy * depthTest.z), 0); fragColor = vec4(lr.rgb + vec3(0.3, 0.0, 0.0), 0.5 + 0.5 * lr.a); return; }", // dev: the raw top layer over every reflector quad (tinted red)
         "   if (mask < 0.004) discard;",
         "   if (pr.x > 0.5) face = propFace(mUv, pr.w, poff, prefl);"), // (the composite reads the prop atlas only where there is glass)
         "   vec3 P = pl.x < 0.5 ? vec3(u + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - u, 0.0);",
         "   P.z = (P.x + P.y - v) / 6.0;",
         "   if (pr.x > 0.5) { if (face == 0) discard; P = propPoint(face, poff, u, v, sq.xyz); }",
         stat ? String.join("\n",
         "   fragColor = vec4(0.0);",
         "   if (any(lessThan(lp, ivec2(0)))) return;",
         "   if (t6.x > 0.5 && t6.x < 1.999 && pr.x < 0.5) imageStore(Glass, ivec2(tile.xy) + texel, vec4(mask, losGeom(losOut(P, pl.x, t6.x - 1.0, texelFetch(Data, ivec2(7, inst), 0).z / 3.0, st.z, pl.w), ivec2(tile.xy) + texel), 0.0, 0.0));", // a mirror: its people's line of sight (losOut, losGeom)
         "   if ((int(dev.y) & 32) != 0) { imageStore(Static, ivec2(tile.xy) + texel, vec4(0.5, 0.5, 0.5, 0.5)); return; }", // dev: the pass without the march (the glass mask still written)
         "   if (t6.y > 0.75) { imageStore(Static, ivec2(tile.xy) + texel, withGeom(pr.x > 0.5 ? propRay(P, face, pl, st, pr, sq.w) : marchRay(P, pl.x, st.z, pl.z, pl.w), ivec2(tile.xy) + texel)); return; }",
         "   if (((lp.x | lp.y) & 1) != 0) return;", // half resolution: the even px of each 2x2 marches and stores the block
         "   vec4 r2 = pr.x > 0.5 ? propRay(P, face, pl, st, pr, sq.w) : marchRay(P, pl.x, st.z, pl.z, pl.w);",
         "   ivec2 b = ivec2(tile.xy) + texel;",
         "   ivec2 hi = ivec2(tile.xy + tile.zw) - 1;",
         "#ifdef PZ_ACCUM",
         // a baked glossy texel blends into what the tile held (the tile does not move with the camera: no reprojection), so
         // successive refreshes add up rays; a cleared tile (no reflection yet) or a miss starts over
         "   if (pr.x > 0.5 && sq.w < 0.0 && r2.a < 0.995) { vec4 old = imageLoad(Static, b); if (old.a < 0.995) r2.rgb = mix(old.rgb, r2.rgb, gloss.y); }",
         "#endif",
         "   imageStore(Static, b, r2);",
         "   imageStore(Static, min(b + ivec2(1, 0), hi), r2);",
         "   imageStore(Static, min(b + ivec2(0, 1), hi), r2);",
         "   imageStore(Static, min(b + ivec2(1, 1), hi), r2);",
         "}")
         : String.join("\n",
         "   float wM = P.x + P.y + 2.0 * P.z;",
         "   if ((dev.w < 0.5 || dev.w > 1.5) && isoDepth(fc) > wM + 0.35) discard;", // something stands in front of the pane (dev.w 1: the hardware depth test did it)
         // a prop: its own surface against the frame's depth (bound for the props' draw; glass writes none, so what stands
         // behind it passes and whoever stands in front hides it)
         "#ifdef PZ_PROPDEPTH",
         "   gl_FragDepth = clamp((wM + 0.1 - mapC.y) / mapC.x, 0.0, 1.0);",
         "#else",
         "   if (pr.x > 0.5 && isoDepth(fc) > wM + 0.1) discard;",
         "#endif",
         "   if ((int(dev.y) & 8) != 0) { fragColor = vec4(1.0, 0.0, 1.0, 0.5 * mask); return; }",
         "   vec4 r = vec4(0.0, 0.0, 0.0, 1.0);",
         "   if (dev.z > 0.5) r = st.w > 0.5 ? texelFetch(StaticTex, ivec2(tile.xy) + texel, 0) : dev.w < 0.5 ? marchRay(P, pl.x, st.z, pl.z, pl.w) : r;", // (no static yet, live reads off: the reflection starts next frame)
         "   float code = floor(r.a * 255.0 + 0.5);", // hitOut's alpha: distance steps and the stand-in bit; 255 = no reflection
         "   bool hit = code < 254.5;",
         "   float conf = mod(code, 2.0) > 0.5 ? march.z : 1.0;",
         "   float tS = hit ? floor(code * 0.5) / 126.0 * mapC.w : 1e9;",
         "   vec3 col = r.rgb;",
         // a glossy prop (steel, ceramic): the tile's reflection blurred over a disc that grows with the hit distance (a
         // rough surface's reflection is sharp where it touches and smears away from it: contact hardening); 8 taps, hits only
         "   if (pr.x > 0.5 && sq.w > 0.001 && hit) {", // (sq.w < 0: baked by the static pass)
         "      float rad = min(16.0, sq.w * (tS + 1.0) / abs(mapA.x) * 0.5);", // (+1 square: a rough surface smears even what touches it)
         "      if (rad >= 1.0 && depthTest.w > 0.5) {",
         // the pre-convolved chain: one tap at the level of the blur's width
         "         col = textureLod(StaticMip, (vec2(ivec2(tile.xy) + texel) + 0.5) / vec2(textureSize(StaticMip, 0)), metal.x > 0.5 ? min(log2(rad), 2.5) : log2(rad)).rgb;",
         "      } else if (rad >= 1.0) {",
         "         vec3 acc = col; float wsum = 1.0;",
         "         ivec2 lo = ivec2(tile.xy), hi = ivec2(tile.xy + tile.zw) - 1;",
         "         for (int k = 0; k < 8; k++) {",
         "            float an = 2.39996 * float(k) + 0.7, rr = rad * sqrt((float(k) + 0.5) / 8.0);",
         "            vec4 q = texelFetch(StaticTex, clamp(ivec2(tile.xy) + texel + ivec2(rr * vec2(cos(an), sin(an))), lo, hi), 0);",
         "            if (q.a < 0.995) { acc += q.rgb; wsum += 1.0; }",
         "         }",
         "         col = acc / wsum;",
         "      }",
         "   }",
         // a metal's reflection is tinted by the metal (F0 = its colour: steel ~0.55): a darker, greyer picture than glass's
         "   vec3 colS = col;", // (the static reflection alone, before the mirrored people / cars)
         "   vec4 lc = vec4(0.0);",
         "   float devShown = 0.0, devHidden = 0.0;",
         // the model layer was mirrored in the prop's main vertical face: only that face's texels show it
         "   bool layerOk = pr.x < 0.5 || (face >= 2 && abs((face == 2 ? 0.0 : 1.0) - pl.x) < 0.5 && abs((face == 2 ? P.y : P.x) - pl.y) < 0.1)",
         "      || (face == 1 && abs(P.z - texelFetch(Data, ivec2(7, inst), 0).w) < 0.1);", // (a top: its plane, pack row 7 .w)
         "   if (layerMap.x > 0.5 && layerOk) {",
         "      ivec2 lfc = ivec2(fc * depthTest.z);", // (the layer drawn at a fraction of the resolution: mirrorsGlassModelScalePct)
         "      lc = face == 1 ? texelFetch(LayerTopColor, lfc, 0) : texelFetch(LayerColor, lfc, 0);",
         "      if (lc.a > 0.01) {",
         "         float wP = layerMap.w + ((face == 1 ? texelFetch(LayerTopDepth, lfc, 0).r : texelFetch(LayerDepth, lfc, 0).r) * 2.0 - 1.0 - layerMap.z) * layerMap.y;",
         "         float tM = 0.375 * (wM - wP) * texelFetch(Data, ivec2(7, inst), 0).z;", // the model's distance in front of the pane (iso depth falls 8/3 a square behind it), on the room's scale (pack: 3 viewDrop)
         // a mirror (its people at viewLateral below 1): hidden only by what stands on their own line of sight before them
         // (losOut, glass G), not by the static ray's hit (dev skip bit 67108864 / row 7 .y -1: the old test)
         "         bool shown = tM < tS + 0.15 && tM > -0.25;",
         "         if (t6.x > 0.5 && t6.x < 1.999 && pr.x < 0.5 && texelFetch(Data, ivec2(7, inst), 0).y >= 0.0) {",
         "            float g = st.w > 0.5 ? texelFetch(GlassTex, ivec2(tile.xy) + texel, 0).g : 0.0;",
         "            float dM = 0.375 * (wM - wP);", // (the person's own distance in front of the glass, squares)
         "            shown = dM > -0.25 && (g < 0.002 || dM < (1.0 - g) * st.z + 0.15);",
         "         }",
         "         if (shown) { col = mix(col, lc.rgb, lc.a); conf = mix(conf, 1.0, lc.a); hit = true; tS = min(tS, tM); devShown = lc.a; } else devHidden = lc.a;",
         "      }",
         "   }",
         "   if (dev.x == 9.0) { fragColor = vec4(devHidden, devShown, 0.15, mask); return; }", // dev: the mirrored people on the glass, green shown, red hidden
         "   if (!hit) discard;",
         "   float fade = 1.0 - smoothstep(0.75, 1.0, tS / mapC.w);",
         "   float a = mask * st.x * st.y * fade * conf;",
         // Fresnel at the game camera's angle: a top meets the view ray at 60 degrees, a wall face at 52 (Schlick, F0 0.04: 0.070
         // against 0.048), so a dielectric's top reflects ~1.45 times what its upright panes do; metals (steel) about the same
         "   if (pr.x > 0.5 && face == 1 && pr.x != 4.0) a = min(1.0, a * 1.45);",
         "   if (dev.x == 1.0 || dev.x == 5.0 || dev.x == 6.0) a = mask;",
         "   if (dev.x == 2.0) { fragColor = vec4(mask, code < 254.5 ? 1.0 : 0.0, 0.0, 1.0); return; }",
         "   if (dev.x == 3.0) { fragColor = vec4(vec3(tS / mapC.w), mask); return; }",
         "   if (dev.x == 4.0) { fragColor = vec4(lc.rgb, mask * lc.a); return; }",
         // steel / ceramic (drawn with dst x src + dst x (1 - a)): the reflection's brightness, mostly grey, as a modulation of
         // the surface's own colour around 1 (mid-grey 0.5 leaves it as it is): a sheen, never the scene seen through it
         // only the people and cars mirrored in it, as their brightness against the static reflection under them (dark or bright
         // smudges passing by): the static scene itself stays out. Mirrored by a vertical face in the iso view the floor continues
         // the floor beside the counter, and any of its detail on the steel (the whole picture blended over it, then its
         // brightness round a 16 px mean) read as seeing through it (maintainer, 2026-10-09)
         "   if (metal.x > 0.5 && (pr.x == 4.0 || pr.x == 5.0 || pr.x == 2.0)) {", // (a dark screen too: the asphalt mirrored in it read the same way)
         "      float det = dot(col - colS, vec3(0.299, 0.587, 0.114));",
         "      if (abs(det) < 0.004) discard;",
         "      vec3 m = vec3(clamp(1.0 + 1.5 * det, 0.5, 1.6));",
         "      fragColor = vec4(m * a, a);",
         "      return;",
         "   }",
         "   fragColor = vec4(col, a);",
         "}"));
   }

   static final String MAIN_STATIC_IMAGE = oldMain(true), MAIN_LATE = oldMain(false);

   /** GL 4.1 static pass: each atlas texel of a due pane's tile, rasterised in tile space; colour + hit distance, glass mask. */
   static final String MAIN_STATIC_FBO = String.join("\n",
         "void main() {",
         "   vec4 m = texelFetch(Data, ivec2(2, inst), 0);",
         "   vec4 pl = texelFetch(Data, ivec2(3, inst), 0);",
         "   vec4 st = texelFetch(Data, ivec2(4, inst), 0);",
         "   vec4 q0 = texelFetch(Data, ivec2(0, inst), 0);",
         "   vec4 pr = texelFetch(Data, ivec2(8, inst), 0);",
         "   vec4 sq = texelFetch(Data, ivec2(9, inst), 0);",
         "   int face = 0; float poff = 0.0, prefl = 0.0;",
         "   if (pr.x > 0.5) face = propFace(mUv, pr.w, poff, prefl);",
         "   vec2 local = lpx;",
         "   float u = q0.x + local.x * mapA.x, v = q0.y + local.y * abs(mapA.z);",
         "   float mask = 0.0;",
         "   if (all(greaterThanEqual(tq, vec2(0.0))) && all(lessThanEqual(tq, vec2(1.0)))) {",
         "      vec4 sp = texture(Sprite, sUv);",
         "      mask = m.z < 0.0 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : texture(Masks, mUv).r * step(0.5, sp.a);",
         "      if (pr.x > 0.5) mask = face > 0 ? prefl * (mod(pr.y, 2.0) > 0.5 ? smoothstep(0.1, 0.2, sp.a) * (1.0 - smoothstep(0.9, 0.97, sp.a)) : step(0.5, sp.a)) : 0.0;",
         "   }",
         "   glassOut = vec4(mask, 0.0, 0.0, 0.0);",
         "   fragColor = vec4(0.0, 0.0, 0.0, 1.0);", // no reflection here (every texel of the tile is written: no clear)
         "   if (mask < 0.004) return;",
         "   if ((int(dev.y) & 32) != 0) { fragColor = vec4(0.5, 0.5, 0.5, 0.5); return; }",
         "   vec3 P = pl.x < 0.5 ? vec3(u + pl.y, pl.y, 0.0) : vec3(pl.y, pl.y - u, 0.0);",
         "   P.z = (P.x + P.y - v) / 6.0;",
         "   if (pr.x > 0.5) { fragColor = propRay(propPoint(face, poff, u, v, sq.xyz), face, pl, st, pr, sq.w); return; }",
         "   vec4 t6 = texelFetch(Data, ivec2(6, inst), 0);",
         "   if (t6.x > 0.5 && t6.x < 1.999) glassOut.g = losGeom(losOut(P, pl.x, t6.x - 1.0, texelFetch(Data, ivec2(7, inst), 0).z / 3.0, st.z, pl.w), ivec2(gl_FragCoord.xy));", // a mirror: its people's line of sight
         "   fragColor = withGeom(marchRay(P, pl.x, st.z, pl.z, pl.w), ivec2(gl_FragCoord.xy));",
         "}");

}
