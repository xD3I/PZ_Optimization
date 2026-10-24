package pzopt;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;

/**
 * Grass with blades (Config {@code grassBlades}, off by default): near the player at close zoom the painted grass grows
 * real blades, drawn over the static world right after the chunk composite.
 *
 * <p>Game thread ({@link #afterComposite}): the ground squares within {@code grassBladeRadius} of the player whose floor
 * is grass (blends_natural_01 grass rows; grass overlays on another floor count as partial) are packed into a list, with
 * the frame's view, the sun and the knobs, and handed to the render thread. Render thread ({@link Frame#render}): a
 * compute pass makes up to 64 blade candidates per square (stratified in the square, Voronoi clumps, heights and leans
 * from a world hash: the same blades every frame), drops the ones whose root is covered in the world framebuffer (its
 * depth nearer than the ground there) and takes each blade's colour from the world colour at its root, i.e. the painted
 * grass already lit (pixelLight, cloud / sun shadows, night, AO all carry over); one indirect draw then builds each blade
 * as a tapered quadratic Bezier strip (Ghost of Tsushima / Jahrmann), its tip moved by foliage sway's own wind function
 * (same gust field, oscillators and pushers as the painted plants), depth-tested against the scene and writing depth so
 * the characters drawn later stand in the grass. Off, zoomed out or without GL 4.3: nothing is queued (0 us).
 */
public final class GrassBlades {
   private GrassBlades() {
   }

   static final float DPXY = (float)PixelLight.DEPTH_PER_XY;
   static final int LANES = 64; // blade candidates per square (one compute workgroup)
   static final int MAX_SQUARES = 8192;
   static final int MAX_BLADES = MAX_SQUARES * LANES;
   static final int MAX_FEET = 8;
   static final int MESH_BLADES = 16; // blades a mesh shader workgroup (4 segments: 144 vertices, 112 triangles)

   // ----------------------------------------------------------------------------------------------- knobs (game thread)

   /** The knobs of one frame (the dev alternation swaps a second set in: devGrassAlternate + devGrassAltB). */
   public static final class Opts {
      int radius = 10;
      int densityPct = 100;
      float maxZoom = 1.25F;
      int segments = 4;
      boolean clumps = true;
      int heightPct = 100;
      int widthPct = 100;
      int sheenPct = 100;
      int translucencyPct = 100;
      int aoPct = 100;
      boolean depthCheck = true;
      boolean tufts = true;
      int wavesPct = 100;
      boolean trails = true, cut = true, push = true, wear = true;
      float lodPx = 0F;
      int genEvery = 1;
      boolean genOnStamp = true;
      int drawMode; // 0 instanced strips, 1 one strip with degenerate joins
      int lodSegments = 1;
      int layerDiv; // the layer path: blades drawn into a 1 / N resolution layer, composited depth-aware (0 = off)
      int layerEvery = 1;
      boolean feedback;
      int feetPct = 100; // the feet push (characters stand in the grass), % of the depth push // squares whose on-screen blades were all hidden by the static scene are skipped for a while (persistent buffer, no sync) // the layer is redrawn every N frames, reprojected (pan) between: the impostor / cache variant
      float trailSeconds = 45F, regrowSeconds = 900F;
      int view; // dev: 1 = flat debug colour
      int devGen; // dev (rig): generation bisection 1 = empty, 2 = after the clumps, 3 = nothing kept
      int devDraw; // dev (rig): 1 = vertex shader runs, nothing rasterised; 2 = empty vertex shader
      int devStop; // dev (rig): 1 = uploads only, 2 = + generation, 0 = all

      Opts copy() {
         Opts o = new Opts();
         o.radius = radius;
         o.densityPct = densityPct;
         o.maxZoom = maxZoom;
         o.segments = segments;
         o.clumps = clumps;
         o.heightPct = heightPct;
         o.widthPct = widthPct;
         o.sheenPct = sheenPct;
         o.translucencyPct = translucencyPct;
         o.aoPct = aoPct;
         o.depthCheck = depthCheck;
         o.tufts = tufts;
         o.wavesPct = wavesPct;
         o.trails = trails;
         o.cut = cut;
         o.push = push;
         o.wear = wear;
         o.lodPx = lodPx;
         o.drawMode = drawMode;
         o.genEvery = genEvery;
         o.genOnStamp = genOnStamp;
         o.lodSegments = lodSegments;
         o.layerDiv = layerDiv;
         o.layerEvery = layerEvery;
         o.feedback = feedback;
         o.feetPct = feetPct;
         o.trailSeconds = trailSeconds;
         o.regrowSeconds = regrowSeconds;
         o.view = view;
         o.devStop = devStop;
         o.devGen = devGen;
         o.devDraw = devDraw;
         return o;
      }

      /** "key:value;key:value" over this set (dev alternation's B half). */
      public void apply(String spec) {
         if (spec == null || spec.isEmpty()) {
            return;
         }
         for (String kv : spec.split("[;,]")) {
            String[] p = kv.split("[:=]", 2);
            if (p.length != 2) {
               continue;
            }
            String k = p[0].trim(), v = p[1].trim();
            try {
               switch (k) {
                  case "on" -> off = !Boolean.parseBoolean(v);
                  case "radius" -> radius = Integer.parseInt(v);
                  case "density" -> densityPct = Integer.parseInt(v);
                  case "maxZoom" -> maxZoom = Float.parseFloat(v);
                  case "segments" -> segments = Integer.parseInt(v);
                  case "clumps" -> clumps = Boolean.parseBoolean(v);
                  case "height" -> heightPct = Integer.parseInt(v);
                  case "width" -> widthPct = Integer.parseInt(v);
                  case "sheen" -> sheenPct = Integer.parseInt(v);
                  case "translucency" -> translucencyPct = Integer.parseInt(v);
                  case "ao" -> aoPct = Integer.parseInt(v);
                  case "depthCheck" -> depthCheck = Boolean.parseBoolean(v);
                  case "tufts" -> tufts = Boolean.parseBoolean(v);
                  case "waves" -> wavesPct = Integer.parseInt(v);
                  case "trails" -> trails = Boolean.parseBoolean(v);
                  case "cut" -> cut = Boolean.parseBoolean(v);
                  case "push" -> push = Boolean.parseBoolean(v);
                  case "wear" -> wear = Boolean.parseBoolean(v);
                  case "lodPx" -> lodPx = Float.parseFloat(v);
                  case "drawMode" -> drawMode = Integer.parseInt(v);
                  case "genEvery" -> genEvery = Integer.parseInt(v);
                  case "genOnStamp" -> genOnStamp = Boolean.parseBoolean(v);
                  case "lodSegments" -> lodSegments = Integer.parseInt(v);
                  case "layerDiv" -> layerDiv = Integer.parseInt(v);
                  case "layerEvery" -> layerEvery = Integer.parseInt(v);
                  case "feedback" -> feedback = Boolean.parseBoolean(v);
                  case "feet" -> feetPct = Integer.parseInt(v);
                  case "trailSeconds" -> trailSeconds = Float.parseFloat(v);
                  case "regrowSeconds" -> regrowSeconds = Float.parseFloat(v);
                  case "view" -> view = Integer.parseInt(v);
                  case "devStop" -> devStop = Integer.parseInt(v);
                  case "devGen" -> devGen = Integer.parseInt(v);
                  case "devDraw" -> devDraw = Integer.parseInt(v);
                  default -> Log.warn("grass blades: unknown alternation knob " + k);
               }
            } catch (RuntimeException e) {
               Log.warn("grass blades: bad alternation knob " + kv);
            }
         }
      }

      boolean off;
   }

   private static Opts aCache, bCache;
   private static int aAge;

   /** The launch's knobs (Config), rebuilt every 120 frames (the live keys) instead of a new set a frame. */
   private static Opts optsA() {
      if (aCache != null && ++aAge < 120) {
         return aCache;
      }
      aAge = 0;
      aCache = buildA();
      bCache = null;
      return aCache;
   }

   private static Opts buildA() {
      Opts o = new Opts();
      o.radius = Math.max(2, Math.min(40, Config.GRASS_BLADE_RADIUS));
      o.densityPct = Math.max(5, Math.min(400, Config.GRASS_BLADE_DENSITY_PCT));
      o.maxZoom = Config.GRASS_BLADE_MAX_ZOOM_PCT / 100F;
      o.segments = Math.max(1, Math.min(8, Config.GRASS_BLADE_SEGMENTS));
      o.clumps = Config.GRASS_BLADE_CLUMPS;
      o.heightPct = Config.GRASS_BLADE_HEIGHT_PCT;
      o.widthPct = Config.GRASS_BLADE_WIDTH_PCT;
      o.sheenPct = Config.GRASS_BLADE_SHEEN_PCT;
      o.translucencyPct = Config.GRASS_BLADE_TRANSLUCENCY_PCT;
      o.aoPct = Config.GRASS_BLADE_AO_PCT;
      o.depthCheck = Config.GRASS_BLADE_DEPTH_CHECK;
      o.tufts = Config.GRASS_BLADE_TUFTS;
      o.wavesPct = Config.GRASS_BLADE_WAVES_PCT;
      o.trails = Config.GRASS_BLADE_TRAILS;
      o.cut = Config.GRASS_BLADE_CUT;
      o.push = Config.GRASS_BLADE_PUSH;
      o.wear = Config.GRASS_BLADE_WEAR;
      o.lodPx = Config.GRASS_BLADE_LOD_PX;
      o.drawMode = Config.GRASS_BLADE_DRAW_MODE;
      o.genEvery = Math.max(1, Config.GRASS_BLADE_GEN_EVERY);
      o.lodSegments = Config.GRASS_BLADE_LOD_SEGMENTS;
      o.layerDiv = Config.GRASS_BLADE_LAYER_DIV;
      o.layerEvery = Math.max(1, Config.GRASS_BLADE_LAYER_EVERY);
      o.feedback = Config.GRASS_BLADE_FEEDBACK;
      o.feetPct = Config.GRASS_BLADE_FEET_PCT;
      o.trailSeconds = Math.max(1, Config.GRASS_BLADE_TRAIL_SECONDS);
      o.regrowSeconds = Math.max(1, Config.GRASS_BLADE_REGROW_SECONDS);
      o.view = Config.DEV_GRASS_VIEW;
      return o;
   }

   // ------------------------------------------------------------------------------------------- squares (game thread)

   /**
    * A square's grass (the shared ground classification, GroundTiles): bits 0-1 kind 1..3 (the light / medium / dark grass
    * of blends_natural_01, short / medium / tall blades), bit 2 partial (a blend piece or grass lying on another ground:
    * the root colour decides blade by blade), bit 3 tuft (an erosion grass sprite stands on it: taller blades round its
    * foot, grassBladeTufts), 0 none (no grass, farmland, dug soil, water).
    */
   static int squareGrass(IsoGridSquare sq) {
      int code = GroundTiles.square(sq);
      if (code == 0 || (code & GroundTiles.F_EXCLUDED) != 0) {
         return 0;
      }
      int k = 0;
      if (GroundTiles.material(code) == GroundTiles.GRASS) {
         k = (GroundTiles.tone(code) + 1) | ((code & GroundTiles.F_BLEND) != 0 ? 4 : 0);
      }
      if ((code & GroundTiles.F_OVERLAY) != 0) {
         int ov = overlays(sq);
         if (k == 0 && (ov & 1) != 0) {
            k = 1 | 4; // grass lying on dirt / sand: partial
         }
         if (k != 0 && (ov & 2) != 0) {
            k |= 8;
         }
      }
      return k;
   }

   /** The plants on a square: bit 0 a grass overlay (floor attachment or object), bit 1 an erosion grass tuft (e_newgrass). */
   private static int overlays(IsoGridSquare sq) {
      int r = 0;
      IsoObject floor = sq.getFloor();
      java.util.ArrayList<IsoSpriteInstance> att = floor == null ? null : floor.getAttachedAnimSprite();
      if (att != null) {
         for (int i = 0, n = att.size(); i < n; i++) {
            IsoSpriteInstance si = att.get(i);
            r |= overlayBits(si == null ? null : si.parentSprite);
         }
      }
      IsoObject[] objs = (IsoObject[])sq.getObjects().getElements();
      for (int i = 0, n = sq.getObjects().size(); i < n; i++) {
         IsoObject o = objs[i];
         if (o != null && o != floor) {
            r |= overlayBits(o.sprite);
         }
      }
      return r;
   }

   private static int overlayBits(IsoSprite s) {
      int c = GroundTiles.of(s);
      if (GroundTiles.material(c) != GroundTiles.GRASS_OVERLAY) {
         return 0;
      }
      return (c & GroundTiles.F_EROSION) != 0 ? 3 : 1;
   }

   /**
    * Harness {@code find=lawn}: the centre of the loaded 3 x 3 chunks with the most open grass (a grass floor and nothing
    * else on the square) round the player, nearer first on a tie; null when there is none.
    */
   public static int[] findLawn(zombie.characters.IsoPlayer p) {
      IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
      if (cell == null || p == null) {
         return null;
      }
      int pwx = p.getXi() >> 3, pwy = p.getYi() >> 3;
      int R = 12;
      int[][] count = new int[2 * R + 1][2 * R + 1];
      for (int dy = -R; dy <= R; dy++) {
         for (int dx = -R; dx <= R; dx++) {
            zombie.iso.IsoChunk c = cell.getChunk(pwx + dx, pwy + dy);
            if (c == null) {
               continue;
            }
            int n = 0;
            for (int y = 0; y < 8; y++) {
               for (int x = 0; x < 8; x++) {
                  IsoGridSquare sq = c.getGridSquare(x, y, 0);
                  if (sq != null && (squareGrass(sq) & 7) != 0 && (squareGrass(sq) & 4) == 0 && sq.getObjects().size() <= 1) {
                     n++;
                  }
               }
            }
            count[dy + R][dx + R] = n;
         }
      }
      int best = -1, bx = 0, by = 0;
      for (int dy = -R + 1; dy <= R - 1; dy++) {
         for (int dx = -R + 1; dx <= R - 1; dx++) {
            int n = 0;
            for (int j = -1; j <= 1; j++) {
               for (int i = -1; i <= 1; i++) {
                  n += count[dy + j + R][dx + i + R];
               }
            }
            int score = n * 4 - (Math.abs(dx) + Math.abs(dy));
            if (score > best) {
               best = score;
               bx = dx;
               by = dy;
            }
         }
      }
      if (best <= 0) {
         Log.warn("grass blades: find=lawn: no grass in the loaded chunks round " + p.getXi() + "," + p.getYi());
         return null;
      }
      int x = (pwx + bx) * 8 + 4, y = (pwy + by) * 8 + 4;
      Log.info("grass blades: find=lawn: " + x + "," + y + " (open grass in its 3 x 3 chunks " + (best + Math.abs(bx) + Math.abs(by)) / 4 + " of 576)");
      return new int[] {x, y, 0};
   }

   /** The grass of a chunk's 64 ground squares (squareGrass), cached; re-read when the chunk object changes or, two a frame, after 2 s. */
   private static final class ChunkGrass {
      Object chunk;
      final byte[] g = new byte[64];
      int frame;
      boolean any;
   }

   private static final java.util.HashMap<Long, ChunkGrass> CHUNKS = new java.util.HashMap<>();
   private static int refreshBudget, gatherFrame;
   static long gatherNs, moversNs, chunkReads;

   private static byte[] chunkGrass(IsoCell cell, int wx, int wy) {
      zombie.iso.IsoChunk c = cell.getChunk(wx, wy);
      if (c == null) {
         return null;
      }
      long key = (long)wx << 32 ^ (wy & 0xffffffffL);
      ChunkGrass e = CHUNKS.get(key);
      if (e == null) {
         if (CHUNKS.size() > 4096) {
            CHUNKS.clear();
         }
         e = new ChunkGrass();
         CHUNKS.put(key, e);
      }
      if (e.chunk != c || (gatherFrame - e.frame > 240 && refreshBudget-- > 0)) {
         e.chunk = c;
         e.frame = gatherFrame;
         e.any = false;
         for (int i = 0; i < 64; i++) {
            IsoGridSquare sq = c.getGridSquare(i & 7, i >> 3, 0);
            int k = sq == null ? 0 : squareGrass(sq);
            e.g[i] = (byte)k;
            e.any |= k != 0;
         }
         chunkReads++;
      }
      return e.any ? e.g : null;
   }

   // ------------------------------------------------------------------------------------------------ frame (game thread)

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static long toggleT0;
   private static boolean altB;
   private static boolean lastOn;
   public static long frames, squaresTotal, gameNs;
   private static long lastLogMs;

   /** Dev alternation's section name: ".gbon" / ".gboff" (GpuSections). */
   static String section(String name) {
      return Config.DEV_GRASS_ALTERNATE <= 0 ? name : name + (altB ? ".gbB" : ".gbA");
   }

   /** Game thread, FBORenderCell right after the chunk composite (and AO), before pixelLight's pass and the characters. */
   public static void afterComposite(int playerIndex) {
      boolean on = Ground.on(Ground.K_BLADES) && Overrides.enabled() && !Gpu.failed && !CoreGl.legacyMac();
      if (on != lastOn) {
         lastOn = on;
         Log.info("grass blades: " + (on ? "on" : "off") + " (radius " + Config.GRASS_BLADE_RADIUS + ", density " + Config.GRASS_BLADE_DENSITY_PCT
            + " %, max zoom " + Config.GRASS_BLADE_MAX_ZOOM_PCT + " %, segments " + Config.GRASS_BLADE_SEGMENTS + ")");
      }
      if (!on) {
         return;
      }
      long t0 = System.nanoTime();
      Opts o = optsA();
      if (Config.DEV_GRASS_ALTERNATE > 0) {
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
            Log.info("grass blades: alternating every " + Config.DEV_GRASS_ALTERNATE + " ms from epoch_ms " + now + " (A first; B = "
               + (Config.DEV_GRASS_ALT_B.isEmpty() ? "off" : Config.DEV_GRASS_ALT_B) + ")");
         }
         altB = (now - toggleT0) / Config.DEV_GRASS_ALTERNATE % 2L == 1L;
         if (altB) {
            if (bCache == null) {
               bCache = o.copy();
               if (Config.DEV_GRASS_ALT_B.isEmpty()) {
                  bCache.off = true;
               } else {
                  bCache.apply(Config.DEV_GRASS_ALT_B);
               }
            }
            o = bCache;
         }
      }
      float zoom = Core.getInstance().getZoom(playerIndex);
      if (o.off || zoom > o.maxZoom + 1e-4F || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         gameNs += System.nanoTime() - t0;
         return;
      }
      Frame f = FRAMES[frameIndex++ & 3];
      f.o = o;
      f.view.capture(playerIndex);
      int ox = f.view.ox, oy = f.view.oy;
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      int r = o.radius;
      int need = (2 * r + 1) * (2 * r + 1);
      if (f.squares.length < need) {
         f.squares = new int[need];
      }
      IsoCell cell = IsoWorld.instance.currentCell;
      int n = 0;
      int icx = (int)Math.floor(cx), icy = (int)Math.floor(cy);
      float r2 = (r + 0.75F) * (r + 0.75F);
      long tg = System.nanoTime();
      refreshBudget = 2;
      gatherFrame++;
      for (int wy = (icy - r) >> 3; wy <= (icy + r) >> 3; wy++) {
         for (int wx = (icx - r) >> 3; wx <= (icx + r) >> 3; wx++) {
            byte[] g = chunkGrass(cell, wx, wy);
            if (g == null) {
               continue;
            }
            int x0 = Math.max(wx << 3, icx - r), x1 = Math.min((wx << 3) + 7, icx + r);
            int y0 = Math.max(wy << 3, icy - r), y1 = Math.min((wy << 3) + 7, icy + r);
            for (int y = y0; y <= y1; y++) {
               float dy = y + 0.5F - cy;
               for (int x = x0; x <= x1; x++) {
                  int k = g[(y & 7) << 3 | (x & 7)];
                  if (k == 0) {
                     continue;
                  }
                  float dx = x + 0.5F - cx;
                  if (dx * dx + dy * dy > r2 || n >= MAX_SQUARES) {
                     continue;
                  }
                  int rx = x - ox + 128, ry = y - oy + 128;
                  if (rx < 0 || rx > 255 || ry < 0 || ry > 255) {
                     continue;
                  }
                  f.squares[n++] = rx | ry << 8 | k << 16;
               }
            }
         }
      }
      gatherNs += System.nanoTime() - tg;
      f.n = n;
      f.b = altB;
      f.dump = dumpTag();
      f.cx = cx - ox;
      f.cy = cy - oy;
      f.zoom = zoom;
      long tm = System.nanoTime();
      Movers.update(f, o);
      moversNs += System.nanoTime() - tm;
      if (o.wear) {
         GroundState.use(); // worn paths: the ground memory stays active while blades read it
      }
      Sky.update(-1F);
      f.sunX = (float)Sky.sun[0];
      f.sunY = (float)Sky.sun[1];
      f.sunZ = (float)Sky.sun[2];
      frames++;
      squaresTotal += n;
      if (n > 0) {
         String sec = section(Ground.section("ground.blades"));
         GpuSections.begin(sec);
         SpriteRenderer.instance.drawGeneric(f);
         GpuSections.end(sec);
      }
      gameNs += System.nanoTime() - t0;
      long now = System.currentTimeMillis();
      if (now - lastLogMs > 10000L && frames > 0) {
         if (lastLogMs != 0L) {
            Log.info(String.format(java.util.Locale.ROOT, "grass blades: %d frames, %.1f squares/frame, game thread %.2f us/frame (squares %.2f, movers %.2f, chunk reads %d) | %s",
               frames, squaresTotal / (double)frames, gameNs / 1e3 / frames, gatherNs / 1e3 / frames, moversNs / 1e3 / frames, chunkReads, Gpu.stats()));
            gatherNs = 0;
            moversNs = 0;
            chunkReads = 0;
         }
         lastLogMs = now;
         frames = 0;
         squaresTotal = 0;
         gameNs = 0;
      }
   }

   /** What one generation + draw needs (the game's frame or the offline rig's dump). */
   public static final class Params {
      public int colorTex, depthTex;
      public int targetFbo; // the framebuffer the blades draw into (the world's), bound on entry
      public final float[] vp = new float[4];
      public final float[] map = new float[6];
      public int[] squares;
      public int n, ox, oy;
      public Opts o;
      public float cx, cy, zoom, ts, sunX, sunY, sunZ;
      public boolean windOn;
      public float amp, lean, dirX, osc, gustU, gustV;
      public final float[] ph = new float[4];
      public final float[] push = new float[Sway.MAX_PUSH * 4];
      public int nPush;
      public final float[] feet = new float[MAX_FEET * 4];
      public int nFeet;
      public float[] stamps = new float[0];
      public int nStamps;
      public float now;
      public int variant;
   }

   /** The offline rig's counters. */
   public static String rigStats() {
      return "feedback tested " + Feedback.tested + " skipped " + Feedback.skipped + " | layer draws " + Gpu.layerDraws + " | raster area " + Gpu.rasterArea;
   }

   /** The offline rig's parameters: the knobs of the launch's Config (A set). */
   public static Params rigParams() {
      Params p = new Params();
      p.o = buildA();
      return p;
   }

   private static float[] dumpAt;
   private static int dumpNext;
   private static long worldUpNs;

   /** devGrassDumpAt: this frame's dump tag ("t<seconds>") when a dump is due, else null. */
   private static String dumpTag() {
      if (Config.DEV_GRASS_DUMP_AT.isEmpty()) {
         return null;
      }
      if (dumpAt == null) {
         String[] parts = Config.DEV_GRASS_DUMP_AT.split(",");
         dumpAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            dumpAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      long now = System.nanoTime();
      if (worldUpNs == 0L) {
         worldUpNs = now;
      }
      if (dumpNext >= dumpAt.length || (now - worldUpNs) / 1e9 < dumpAt[dumpNext]) {
         return null;
      }
      return "t" + (int)dumpAt[dumpNext++];
   }

   /** Render thread: the world under the blades and everything the generation reads, for tools/GrassRig.java. */
   static final class Dump {
      static void write(Frame f, Params p) {
         try {
            java.io.File dir = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-grass");
            dir.mkdirs();
            int vx = (int)p.vp[0], vy = (int)p.vp[1], vw = (int)p.vp[2], vh = (int)p.vp[3];
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, zombie.core.textures.TextureFBO.lastID);
            java.nio.FloatBuffer depthBuf = BufferUtils.createFloatBuffer(vw * vh);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
            ByteBuffer db = ByteBuffer.allocate(vw * vh * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            db.asFloatBuffer().put(depthBuf);
            java.nio.file.Files.write(new java.io.File(dir, f.dump + "-depth.bin").toPath(), db.array());
            ByteBuffer color = BufferUtils.createByteBuffer(vw * vh * 4);
            GL11.glReadPixels(vx, vy, vw, vh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, color);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            byte[] cb = new byte[vw * vh * 4];
            color.get(cb);
            java.nio.file.Files.write(new java.io.File(dir, f.dump + "-color.bin").toPath(), cb);
            ByteBuffer sb = ByteBuffer.allocate(p.n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < p.n; i++) {
               sb.putInt(p.squares[i]);
            }
            java.nio.file.Files.write(new java.io.File(dir, f.dump + "-squares.bin").toPath(), sb.array());
            StringBuilder t = new StringBuilder();
            t.append("w=").append(vw).append("\nh=").append(vh).append("\nvx=").append(vx).append("\nvy=").append(vy).append('\n');
            String[] mk = {"kA", "cA", "kB", "cB", "kC", "cC"};
            for (int i = 0; i < 6; i++) {
               t.append(mk[i]).append('=').append(p.map[i]).append('\n');
            }
            t.append("ox=").append(p.ox).append("\noy=").append(p.oy).append("\nn=").append(p.n).append("\ncx=").append(p.cx).append("\ncy=").append(p.cy)
               .append("\nzoom=").append(p.zoom).append("\nts=").append(p.ts).append("\nsunX=").append(p.sunX).append("\nsunY=").append(p.sunY)
               .append("\nsunZ=").append(p.sunZ).append("\nwindOn=").append(p.windOn).append("\namp=").append(p.amp).append("\nlean=").append(p.lean)
               .append("\ndirX=").append(p.dirX).append("\nosc=").append(p.osc).append("\ngustU=").append(p.gustU).append("\ngustV=").append(p.gustV)
               .append("\nph=").append(p.ph[0]).append(',').append(p.ph[1]).append(',').append(p.ph[2]).append(',').append(p.ph[3])
               .append("\nradius=").append(p.o.radius).append('\n');
            java.nio.file.Files.writeString(new java.io.File(dir, f.dump + "-view.txt").toPath(), t.toString());
            Log.info("grass blades: dump " + f.dump + " " + vw + "x" + vh + " (" + p.n + " squares) to " + dir);
         } catch (Throwable e) {
            Log.warn("grass blades: dump failed: " + e);
         }
      }
   }

   /** devGrassTiming: GPU time of the generation + draw (timestamp pairs read 8 frames later), split by the A / B halves. */
   static final class Timing {
      private static int[] q;
      private static final boolean[] B = new boolean[8];
      private static final boolean[] USED = new boolean[8];
      private static int slot;
      static long nsA, nsB, nA, nB;

      static void begin(boolean b) {
         if (q == null) {
            q = new int[16];
            GL15.glGenQueries(q);
         }
         int i = slot & 7;
         if (USED[i]) {
            long t = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[i * 2 + 1], GL15.GL_QUERY_RESULT) - org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[i * 2], GL15.GL_QUERY_RESULT);
            if (B[i]) {
               nsB += t;
               nB++;
            } else {
               nsA += t;
               nA++;
            }
         }
         B[i] = b;
         USED[i] = true;
         org.lwjgl.opengl.GL33.glQueryCounter(q[i * 2], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
      }

      static void end() {
         org.lwjgl.opengl.GL33.glQueryCounter(q[(slot & 7) * 2 + 1], org.lwjgl.opengl.GL33.GL_TIMESTAMP);
         slot++;
      }

      static String stats() {
         String r = String.format(java.util.Locale.ROOT, " gpu us/frame A %.1f (%d) B %.1f (%d)", nA == 0 ? 0.0 : nsA / 1e3 / nA, nA, nB == 0 ? 0.0 : nsB / 1e3 / nB, nB);
         nsA = nsB = nA = nB = 0;
         return r;
      }
   }

   static final class Frame extends TextureDraw.GenericDrawer {
      String dump;
      boolean b;
      Opts o;
      final Ssr.View view = new Ssr.View();
      int[] squares = new int[1024];
      int n;
      float cx, cy, zoom, sunX, sunY, sunZ;
      final float[] stamps = new float[Movers.MAX_STAMPS * 4];
      int nStamps;
      final float[] push = new float[Sway.MAX_PUSH * 4];
      int nPush;
      final float[] feet = new float[MAX_FEET * 4];
      int nFeet;
      float now;

      @Override
      public void render() {
         Gpu.draw(this);
      }
   }

   // ------------------------------------------------------------------------------------------------ GPU (render thread)

   public static final class Gpu {
      static boolean failed;
      private static boolean ready;
      public static boolean rig; // the offline rig: no OpenGL 4.3 caps check through the game
      private static int genProgram, drawProgram, vao, squareBuf, bladeBuf, cmdBuf;
      private static int[] gu, du;
      private static IntBuffer squareUpload;
      private static final ByteBuffer CMD = BufferUtils.createByteBuffer(60);
      private static final float[] VP = new float[4];
      private static final int[] VPI = new int[4];
      private static final float[] MAP = new float[6];
      private static int worldFbo = -1, worldColor, worldDepth;
      private static long vpKey = Long.MIN_VALUE;
      private static final java.util.HashMap<Integer, int[]> FBO_ATTACH = new java.util.HashMap<>();
      static long draws, squaresDrawn, noWorld;

      static String stats() {
         return (Config.DEV_GRASS_TIMING ? Timing.stats() + " | " : "") + "draws " + draws + " generations " + gens + " squares " + squaresDrawn + " no world fbo " + noWorld + (failed ? " FAILED" : "");
      }

      /** Render thread, at world entry (shaderWarmup) with blades on: the programs built before the first blade. */
      static void warm() {
         if (Config.GRASS_BLADES && !ready && !failed) {
            init();
         }
      }

      static final String[] GU_NAMES = {"uColor", "uDepth", "uMapA", "uMapB", "uVp", "uOrigin", "uParams", "uFade", "uHasDepth", "uTrail", "uTrailK",
         "pzgStateInfo", "uLod", "uDraw", "uCopy", "uFb"};
      static final String[] DU_NAMES = {"uMapA", "uMapB", "uVp", "uWind", "uPh", "uGust", "uPush", "uPushN", "uOriginW", "uShape", "uSun", "uShade", "uTs",
         "uBase", "uStrip", "uWave", "uFeet", "uFeetN"};
      /** OpenGL 4.3 (compute, storage buffers, indirect draws): the compute path; else (4.1) only the vertex-only path. */
      static boolean gl43;
      private static int inlineProgram, copyProgram, copyFbo, copyColor, copyDepth, copyW, copyH, squareVbo, inlineVao;
      private static int[] iu, idu, cu;
      private static int squareTbo, inlineSquaresLoc;
      private static boolean inlineFailed;

      private static boolean init() {
         if (ready) {
            return true;
         }
         if (failed) {
            return false;
         }
         org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
         gl43 = caps.OpenGL43 && !Config.DEV_GRASS_FORCE_INLINE;
         if (!gl43 && !caps.OpenGL41) {
            failed = true;
            Log.info("grass blades: needs OpenGL 4.1: off");
            return false;
         }
         long t0 = System.nanoTime();
         if (gl43) {
            genProgram = Shaders.compute(GEN, "grass blades: generation");
            drawProgram = Shaders.link(VERT, FRAG, "grass blades: draw");
            if (genProgram == 0 || drawProgram == 0) {
               failed = true;
               return false;
            }
            gu = Shaders.locations(genProgram, GU_NAMES);
            du = Shaders.locations(drawProgram, DU_NAMES);
            vao = GL30.glGenVertexArrays();
            squareBuf = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, squareBuf);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_SQUARES * 4L, GL15.GL_DYNAMIC_DRAW);
            bladeBuf = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bladeBuf);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_BLADES * 32L, GL15.GL_DYNAMIC_COPY);
            cmdBuf = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 60L, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            Trails.init();
         } else {
            Log.info("grass blades: OpenGL " + (caps.OpenGL43 ? "4.3 (forced off)" : "4.1") + ": the vertex-only path (no trails, no cut)");
         }
         squareUpload = BufferUtils.createIntBuffer(MAX_SQUARES);
         if (!gl43 && !initInline()) {
            failed = true;
            return false;
         }
         ready = true;
         Log.info(String.format(java.util.Locale.ROOT, "grass blades: programs ready (%.1f ms)", (System.nanoTime() - t0) / 1e6));
         return true;
      }

      /** The vertex-only path's programs and buffers (grassBladeDrawMode 2, or OpenGL 4.1). */
      private static boolean initInline() {
         if (inlineProgram != 0) {
            return true;
         }
         if (inlineFailed) {
            return false;
         }
         inlineProgram = Shaders.link(INLINE_VERT, FRAG.replace("#version 430", "#version 410"), "grass blades: vertex-only draw");
         copyProgram = Shaders.link(ROOT_VERT, ROOT_FRAG, "grass blades: root pass");
         if (inlineProgram == 0 || copyProgram == 0) {
            inlineFailed = true;
            return false;
         }
         iu = Shaders.locations(inlineProgram, GU_NAMES);
         idu = Shaders.locations(inlineProgram, DU_NAMES);
         cu = Shaders.locations(copyProgram, "uColor", "uDepth", "uMapA", "uVp", "uOrigin", "uParams", "uRootSize", "uSquares");
         inlineSquaresLoc = GL20.glGetUniformLocation(inlineProgram, "uSquares");
         squareVbo = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, squareVbo);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long)MAX_SQUARES * 4L, GL15.GL_DYNAMIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         squareTbo = GL11.glGenTextures();
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, squareTbo);
         GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_R32UI, squareVbo);
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
         inlineVao = GL30.glGenVertexArrays();
         copyFbo = GL30.glGenFramebuffers();
         copyTargets(LANES, MAX_SQUARES);
         return true;
      }

      private static void texNearest() {
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
      }

      /** The root pass's targets: one texel per candidate (lane, square index). */
      private static void copyTargets(int w, int h) {
         if (w == copyW && h == copyH && copyColor != 0) {
            return;
         }
         if (copyColor != 0) {
            GL11.glDeleteTextures(copyColor);
            GL11.glDeleteTextures(copyDepth);
         }
         copyW = w;
         copyH = h;
         copyColor = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyColor);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         texNearest();
         copyDepth = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyDepth);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer)null);
         texNearest();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         int prev = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
         GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, copyColor, 0);
         GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, copyDepth, 0);
         GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prev);
      }

      private static int attachment(int attachment) {
         if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }

      /** The world framebuffer's colour / depth textures and the viewport, re-read only when they change (Mirrors.world). */
      private static boolean world() {
         int fbo = zombie.core.textures.TextureFBO.lastID;
         long key = ((long)Core.getInstance().getOffscreenWidth(0) << 32 | Core.getInstance().getOffscreenHeight(0)) * 31L
            + Float.floatToIntBits(RenderScale.scaleX()) * 7L + Float.floatToIntBits(RenderScale.scaleY());
         if (key != vpKey) {
            vpKey = key;
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
            for (int i = 0; i < 4; i++) {
               VP[i] = VPI[i];
            }
            FBO_ATTACH.clear();
         }
         if (fbo != worldFbo) {
            worldFbo = fbo;
            int[] a = FBO_ATTACH.get(fbo);
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
               Log.info("grass blades: world framebuffer " + fbo + " colour " + c + " depth " + d + " viewport " + VPI[0] + "," + VPI[1] + " " + VPI[2] + "x" + VPI[3]);
            }
            worldColor = a[0];
            worldDepth = a[1];
         }
         return worldColor != 0 && VPI[2] > 0 && VPI[3] > 0;
      }

      /** Render thread: the frame's blades over the world framebuffer, then the sprite renderer's state back. */
      static void draw(Frame f) {
         if (!init()) {
            return;
         }
         if (!world()) {
            noWorld++;
            return;
         }
         Params p = P;
         p.colorTex = worldColor;
         p.depthTex = worldDepth;
         p.targetFbo = worldFbo;
         System.arraycopy(VP, 0, p.vp, 0, 4);
         f.view.mapping(VP, p.map);
         p.squares = f.squares;
         p.n = f.n;
         p.ox = f.view.ox;
         p.oy = f.view.oy;
         p.o = f.o;
         p.cx = f.cx;
         p.cy = f.cy;
         p.zoom = f.zoom;
         p.ts = f.view.ts;
         p.sunX = f.sunX;
         p.sunY = f.sunY;
         p.sunZ = f.sunZ;
         Sway.Frame sw = Sway.renderFrame;
         p.windOn = sw != null && sw.on;
         if (p.windOn) {
            p.amp = sw.amp;
            p.lean = sw.lean;
            p.dirX = sw.dirX;
            p.osc = sw.osc;
            System.arraycopy(sw.ph, 0, p.ph, 0, 4);
            p.gustU = sw.gustU;
            p.gustV = sw.gustV;
         }
         System.arraycopy(f.push, 0, p.push, 0, p.push.length);
         p.nPush = f.nPush;
         System.arraycopy(f.feet, 0, p.feet, 0, p.feet.length);
         p.nFeet = f.nFeet;
         p.stamps = f.stamps;
         p.nStamps = f.nStamps;
         p.now = f.now;
         p.variant = f.b ? 1 : 0;
         if (f.dump != null) {
            Dump.write(f, p);
         }
         if (Config.DEV_GRASS_TIMING) {
            Timing.begin(f.b);
         }
         run(p);
         if (Config.DEV_GRASS_TIMING) {
            Timing.end();
         }
         // back to the sprite renderer's state
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      static final Params P = new Params();
      private static int genAge = 1 << 20, genVariant = -1, genSquares = -1, genOx, genOy;
      private static float genKA;
      static long gens;

      /**
       * Render thread (and the offline rig, tools/GrassRig.java): generation + draw into the bound framebuffer from plain
       * parameters. Leaves program 0, no textures, colour mask all, depth test on, blend off.
       */
      public static void run(Params p) {
         if (!init()) {
            return;
         }
         Opts o = p.o;
         if (!gl43 || o.drawMode == 2) {
            if (initInline()) {
               runInline(p);
            }
            return;
         }
         float kA = p.map[0];
         int verts = 2 * o.segments + 1;
         int lodSegs = Math.max(1, Math.min(o.segments, o.lodSegments));
         boolean strip = o.drawMode == 1;
         int vBig = strip ? verts + 2 : verts, vSmall = strip ? 2 * lodSegs + 3 : 2 * lodSegs + 1;
         // temporal amortisation (grassBladeGenEvery): the blades are regenerated every N frames, or at once when the view's
         // scale, the knob set or the square list changed; between, the last blades draw (world-anchored, shifted to the new origin)
         genAge++;
         boolean doGen = genAge >= Math.max(1, o.genEvery) || p.variant != genVariant || kA != genKA || p.n != genSquares
            || Math.abs(p.ox - genOx) > 2 || Math.abs(p.oy - genOy) > 2 || p.nStamps > 0 && o.genOnStamp;
         // trails: this frame's stamps into the trail map
         boolean trails = (o.trails || o.cut) && Trails.ready();
         if (trails && p.nStamps > 0 && o.devStop != 1) {
            Trails.stamp(p);
         }
         if (doGen) {
            genAge = 0;
            genVariant = p.variant;
            genKA = kA;
            genSquares = p.n;
            genOx = p.ox;
            genOy = p.oy;
            gens++;
            int n = Math.min(p.n, MAX_SQUARES);
            int[] list = p.squares;
            int fbRegion = -1;
            if (o.feedback && Feedback.ready()) {
               list = Feedback.filter(p, n);
               n = Feedback.n;
               fbRegion = Feedback.begin(p, list, n);
            }
            squareUpload.clear();
            squareUpload.put(list, 0, n).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, squareBuf);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, squareUpload);
            CMD.clear();
            if (strip) {
               CMD.putInt(0).putInt(1).putInt(0).putInt(0).putInt(0).putInt(1).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(1).putInt(1).flip();
            } else {
               CMD.putInt(verts).putInt(0).putInt(0).putInt(0).putInt(2 * lodSegs + 1).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(1).putInt(1).flip();
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, CMD);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            if (o.devStop == 1) {
               return;
            }

            // generation
            GL20.glUseProgram(genProgram);
            genUniforms(gu, p, trails, p.colorTex, p.depthTex, 1F, 0F, 0F, genProgram);
            GL20.glUniform4f(gu[13], strip ? 1F : 0F, vBig, vSmall, 0F);
            GL20.glUniform1i(gu[15], fbRegion < 0 ? -1 : fbRegion * MAX_SQUARES);
            if (fbRegion >= 0) {
               GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, Feedback.buf);
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, squareBuf);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, cmdBuf);
            GL43.glDispatchCompute(n, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, 0);
            unbindGenTextures();
         }

         if (o.devStop == 2) {
            GL20.glUseProgram(0);
            return;
         }
         // draw
         if (o.layerDiv > 0 && layerReady()) {
            drawLayer(p, strip, vBig);
            return;
         }
         if (o.drawMode == 4 && rasterReady()) {
            drawRaster(p);
            return;
         }
         if (o.drawMode == 3 && meshProgram(o.segments) != 0) {
            GL20.glUseProgram(meshProgram);
            drawUniforms(mu, p, genOx, genOy);
            drawState();
            GL30.glBindVertexArray(vao);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, cmdBuf);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
            org.lwjgl.opengl.NVMeshShader.glDrawMeshTasksIndirectNV(40L);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            GL11.glColorMask(true, true, true, true);
            draws++;
            squaresDrawn += p.n;
            return;
         }
         GL20.glUseProgram(drawProgram);
         drawUniforms(du, p, genOx, genOy);
         drawState();
         GL30.glBindVertexArray(vao);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
         GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
         GL20.glUniform1i(du[13], 0);
         GL20.glUniform1f(du[14], strip ? vBig : 0F);
         GL40.glDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0L);
         if (o.lodPx > 0) {
            GL20.glUniform4f(du[9], lodSegs, o.widthPct / 100F, o.view, o.devDraw);
            GL20.glUniform1i(du[13], MAX_BLADES / 2);
            GL20.glUniform1f(du[14], strip ? vSmall : 0F);
            GL40.glDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 16L);
         }
         GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
         GL30.glBindVertexArray(0);
         GL20.glUseProgram(0);
         GL11.glColorMask(true, true, true, true);
         draws++;
         squaresDrawn += p.n;
      }

      /**
       * The vertex-only path: a root pass writes each candidate's root colour + depth (read from the world's textures while
       * the world framebuffer is not bound) into texel (lane, square) of the root targets, then one instanced draw of every
       * candidate whose vertices make their blade themselves (gbMake with GB_ROOTS) and emit it.
       */
      private static void runInline(Params p) {
         Opts o = p.o;
         int vx = (int)p.vp[0], vy = (int)p.vp[1], vw = (int)p.vp[2], vh = (int)p.vp[3];
         if (o.devStop == 1) {
            return;
         }
         int n = Math.min(p.n, MAX_SQUARES);
         int perSquare = perSquare(o);
         squareUpload.clear();
         squareUpload.put(p.squares, 0, n).flip();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, squareVbo);
         GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, squareUpload);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         GL30.glBindVertexArray(inlineVao);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, squareTbo);
         // root pass
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
         GL11.glViewport(0, 0, copyW, copyH);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         GL11.glEnable(GL32.GL_PROGRAM_POINT_SIZE);
         GL20.glUseProgram(copyProgram);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, o.depthCheck ? p.depthTex : 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.colorTex);
         GL20.glUniform1i(cu[0], 0);
         GL20.glUniform1i(cu[1], 1);
         GL20.glUniform4f(cu[2], p.map[0], p.map[1], p.map[2], p.map[3]);
         GL20.glUniform4f(cu[3], p.vp[0], p.vp[1], p.vp[2], p.vp[3]);
         GL30.glUniform4i(cu[4], p.ox, p.oy, perSquare, o.clumps ? 1 : 0);
         GL20.glUniform4f(cu[5], 0F, 0F, o.tufts ? 1F : 0F, 0F);
         GL20.glUniform2f(cu[6], copyW, copyH);
         GL20.glUniform1i(cu[7], 3);
         GL11.glDrawArrays(GL11.GL_POINTS, 0, n * perSquare);
         GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, p.targetFbo); // (no glGet on the render thread: NVIDIA's threaded driver syncs on it)
         GL11.glViewport(vx, vy, vw, vh);
         if (o.devStop == 2) {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            return;
         }
         GL20.glUseProgram(inlineProgram);
         genUniforms(iu, p, false, copyColor, copyDepth, 1F, 0F, 0F, inlineProgram);
         drawUniforms(idu, p, p.ox, p.oy);
         drawState();
         int V = 2 * o.segments + 3;
         GL20.glUniform1i(inlineSquaresLoc, 3);
         GL20.glUniform1f(idu[14], V);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, n * perSquare * V);
         GL30.glBindVertexArray(0);
         GL13.glActiveTexture(GL13.GL_TEXTURE3);
         GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
         unbindGenTextures();
         GL20.glUseProgram(0);
         GL11.glColorMask(true, true, true, true);
         draws++;
         gens++;
         squaresDrawn += p.n;
      }

      private static int rasterProgram, resolveProgram, pixBuf, pixW, pixH;
      private static int[] ru;
      private static int resolveRect;
      private static boolean rasterFailed;

      private static boolean rasterReady() {
         if (rasterProgram != 0) {
            return true;
         }
         if (rasterFailed) {
            return false;
         }
         org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
         if (!c.GL_NV_shader_atomic_int64 || !c.GL_ARB_gpu_shader_int64) {
            rasterFailed = true;
            Log.info("grass blades: no 64-bit atomics: the software raster draws as drawMode 1");
            return false;
         }
         rasterProgram = Shaders.compute(RASTER, "grass blades: software raster");
         resolveProgram = Shaders.link(RESOLVE_VERT, RESOLVE_FRAG, "grass blades: raster resolve");
         if (rasterProgram == 0 || resolveProgram == 0) {
            rasterFailed = true;
            rasterProgram = 0;
            return false;
         }
         ru = Shaders.locations(rasterProgram, DU_NAMES);
         rasterRectLoc = GL20.glGetUniformLocation(rasterProgram, "uRect");
         resolveRect = GL20.glGetUniformLocation(resolveProgram, "uRect");
         pixBuf = GL15.glGenBuffers();
         return true;
      }

      private static int rasterRectLoc;
      static long rasterArea;
      private static final int[] RECT = new int[4];

      /** The blades' screen rectangle (window px): the squares' bounds, a blade's reach above them. */
      static void bladeRect(Params p, int[] r) {
         float minU = 1e9F, maxU = -1e9F, minV = 1e9F, maxV = -1e9F;
         for (int i = 0; i < p.n; i++) {
            int s = p.squares[i];
            int rx = (s & 255) - 128, ry = (s >> 8 & 255) - 128;
            float u = rx - ry, v = rx + ry;
            minU = Math.min(minU, u - 1F);
            maxU = Math.max(maxU, u + 1F);
            minV = Math.min(minV, v - 1.6F); // a 0.7-square blade reaches 1.4 v above its root
            maxV = Math.max(maxV, v + 2F);
         }
         float x0 = (minU - p.map[1]) / p.map[0], x1 = (maxU - p.map[1]) / p.map[0];
         float y0 = (minV - p.map[3]) / p.map[2], y1 = (maxV - p.map[3]) / p.map[2];
         int vx = (int)p.vp[0], vy = (int)p.vp[1], vw = (int)p.vp[2], vh = (int)p.vp[3];
         int ax = Math.max(vx, (int)Math.floor(Math.min(x0, x1)) - 4), bx = Math.min(vx + vw, (int)Math.ceil(Math.max(x0, x1)) + 4);
         int ay = Math.max(vy, (int)Math.floor(Math.min(y0, y1)) - 4), by = Math.min(vy + vh, (int)Math.ceil(Math.max(y0, y1)) + 4);
         r[0] = ax;
         r[1] = ay;
         r[2] = Math.max(0, bx - ax);
         r[3] = Math.max(0, by - ay);
      }

      private static int layerFbo, layerColor, layerDepth, layerW, layerH, compositeProgram, layerAge = 1 << 20;
      private static int[] lcu;
      private static boolean layerFailed;
      private static final float[] LAYER_MAP = new float[6];
      private static final int[] LAYER_RECT = new int[4];
      private static int layerOx, layerOy, layerDivNow, layerVariant = -1;

      private static boolean layerReady() {
         if (compositeProgram != 0) {
            return true;
         }
         if (layerFailed) {
            return false;
         }
         compositeProgram = Shaders.link(RESOLVE_VERT.replace("#version 450", "#version 430"), LAYER_COMPOSITE, "grass blades: layer composite");
         if (compositeProgram == 0) {
            layerFailed = true;
            return false;
         }
         lcu = Shaders.locations(compositeProgram, "uLayerC", "uLayerD", "uL", "uLd");
         layerFbo = GL30.glGenFramebuffers();
         return true;
      }

      /**
       * The layer path (grassBladeLayerDiv N): the blades of the rectangle drawn into a 1 / N resolution colour + depth
       * layer, then composited over the world (bilinear over the layer's blade texels, their nearest depth through the
       * hardware test). With grassBladeLayerEvery M the layer is redrawn every M frames and moved with the camera between
       * (an ortho pan is a translation in window px and depth).
       */
      private static void drawLayer(Params p, boolean strip, int vBig) {
         Opts o = p.o;
         int div = Math.max(1, o.layerDiv);
         int vx = (int)p.vp[0], vy = (int)p.vp[1], vw = (int)p.vp[2], vh = (int)p.vp[3];
         int lw = vw / div + 2, lh = vh / div + 2;
         if (lw != layerW || lh != layerH) {
            if (layerColor != 0) {
               GL11.glDeleteTextures(layerColor);
               GL11.glDeleteTextures(layerDepth);
            }
            layerW = lw;
            layerH = lh;
            layerColor = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColor);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, lw, lh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
            texNearest();
            layerDepth = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerDepth);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, lw, lh, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer)null);
            texNearest();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, layerColor, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, layerDepth, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, p.targetFbo);
            layerAge = 1 << 20;
         }
         layerAge++;
         boolean redraw = layerAge >= Math.max(1, o.layerEvery) || div != layerDivNow || p.map[0] != LAYER_MAP[0] || p.map[2] != LAYER_MAP[2]
            || p.map[4] != LAYER_MAP[4] || p.variant != layerVariant || Math.abs(p.ox - layerOx) > 8 || Math.abs(p.oy - layerOy) > 8;
         if (redraw) {
            bladeRect(p, LAYER_RECT);
            if (LAYER_RECT[2] == 0 || LAYER_RECT[3] == 0) {
               return;
            }
            layerAge = 0;
            layerDivNow = div;
            layerVariant = p.variant;
            System.arraycopy(p.map, 0, LAYER_MAP, 0, 6);
            layerOx = p.ox;
            layerOy = p.oy;
            int rw = (LAYER_RECT[2] + div - 1) / div, rh = (LAYER_RECT[3] + div - 1) / div;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, layerFbo);
            GL11.glViewport(0, 0, rw, rh);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // the blades into the layer: the rectangle (in full-resolution window px, rw * div wide) is the draw's viewport
            float[] vp = p.vp;
            float s0 = vp[0], s1 = vp[1], s2 = vp[2], s3 = vp[3];
            vp[0] = LAYER_RECT[0];
            vp[1] = LAYER_RECT[1];
            vp[2] = rw * div;
            vp[3] = rh * div;
            minHalfW = 0.45F * div;
            GL20.glUseProgram(drawProgram);
            drawUniforms(du, p, genOx, genOy);
            minHalfW = 0.45F;
            vp[0] = s0;
            vp[1] = s1;
            vp[2] = s2;
            vp[3] = s3;
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL30.glBindVertexArray(vao);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
            GL20.glUniform1i(du[13], 0);
            GL20.glUniform1f(du[14], strip ? vBig : 0F);
            GL40.glDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0L);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, p.targetFbo);
            GL11.glViewport(vx, vy, vw, vh);
            layerDraws++;
         }
         // the camera's pan since the layer was drawn: a translation in window px and depth
         int du0 = (p.ox - p.oy) - (layerOx - layerOy), dv0 = (p.ox + p.oy) - (layerOx + layerOy);
         float sx = (-du0 - p.map[1] + LAYER_MAP[1]) / p.map[0];
         float sy = (-dv0 - p.map[3] + LAYER_MAP[3]) / p.map[2];
         float sd = (-dv0 - p.map[5] + LAYER_MAP[5]) / p.map[4];
         int rw = (LAYER_RECT[2] + div - 1) / div, rh = (LAYER_RECT[3] + div - 1) / div;
         GL20.glUseProgram(compositeProgram);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerDepth);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, layerColor);
         GL20.glUniform1i(lcu[0], 0);
         GL20.glUniform1i(lcu[1], 1);
         GL20.glUniform4f(lcu[2], LAYER_RECT[0] + sx, LAYER_RECT[1] + sy, div, 0F);
         GL20.glUniform4f(lcu[3], sd, rw - 1, rh - 1, 0F);
         drawState();
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         int x0 = (int)Math.floor(LAYER_RECT[0] + sx), y0 = (int)Math.floor(LAYER_RECT[1] + sy);
         GL11.glScissor(x0, y0, rw * div + 1, rh * div + 1);
         GL30.glBindVertexArray(vao);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL30.glBindVertexArray(0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL20.glUseProgram(0);
         GL11.glColorMask(true, true, true, true);
         draws++;
         squaresDrawn += p.n;
      }

      static long layerDraws;

      /** drawMode 4: the software raster over the generated blades, then the resolve through the depth test. */
      private static void drawRaster(Params p) {
         int vw = (int)p.vp[2], vh = (int)p.vp[3];
         if (vw != pixW || vh != pixH) {
            pixW = vw;
            pixH = vh;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pixBuf);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)vw * vh * 8L, GL15.GL_DYNAMIC_COPY);
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, new int[] {-1});
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         }
         bladeRect(p, RECT);
         if (RECT[2] == 0 || RECT[3] == 0) {
            return;
         }
         GL20.glUseProgram(rasterProgram);
         drawUniforms(ru, p, genOx, genOy);
         GL30.glUniform4i(rasterRectLoc, RECT[0], RECT[1], RECT[2], RECT[3]);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, cmdBuf);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, pixBuf);
         GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, cmdBuf);
         GL43.glDispatchComputeIndirect(48L);
         GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, 0);
         GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
         rasterArea += (long)RECT[2] * RECT[3];
         if (p.o.devDraw == 3) { // dev: no resolve (the raster alone; the buffer keeps its minima)
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
            GL20.glUseProgram(0);
            return;
         }
         GL20.glUseProgram(resolveProgram);
         GL30.glUniform4i(resolveRect, RECT[0], RECT[1], RECT[2], RECT[3]);
         drawState();
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
         GL11.glScissor(RECT[0], RECT[1], RECT[2], RECT[3]);
         GL30.glBindVertexArray(vao);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
         GL30.glBindVertexArray(0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
         GL20.glUseProgram(0);
         GL11.glColorMask(true, true, true, true);
         draws++;
         squaresDrawn += p.n;
      }

      private static int meshProgram, meshSegments = -1;
      private static int[] mu;
      private static boolean meshFailed;

      /** The mesh shader program for this segment count (GL_NV_mesh_shader), 0 without it. */
      private static int meshProgram(int segments) {
         if (meshSegments == segments) {
            return meshProgram;
         }
         if (meshFailed || !org.lwjgl.opengl.GL.getCapabilities().GL_NV_mesh_shader) {
            if (!meshFailed) {
               Log.info("grass blades: no GL_NV_mesh_shader: the mesh path draws as drawMode 1");
            }
            meshFailed = true;
            return 0;
         }
         if (meshProgram != 0) {
            GL20.glDeleteProgram(meshProgram);
         }
         meshSegments = segments;
         int ms = Shaders.compile(org.lwjgl.opengl.NVMeshShader.GL_MESH_SHADER_NV, meshShader(segments), "grass blades: mesh shader");
         int fs = Shaders.compile(GL20.GL_FRAGMENT_SHADER, FRAG, "grass blades: mesh fragment");
         if (ms == 0 || fs == 0) {
            meshFailed = true;
            meshProgram = 0;
            return 0;
         }
         meshProgram = GL20.glCreateProgram();
         GL20.glAttachShader(meshProgram, ms);
         GL20.glAttachShader(meshProgram, fs);
         GL20.glLinkProgram(meshProgram);
         GL20.glDeleteShader(ms);
         GL20.glDeleteShader(fs);
         if (GL20.glGetProgrami(meshProgram, GL20.GL_LINK_STATUS) == 0) {
            Log.warn("grass blades: mesh shader link failed: " + GL20.glGetProgramInfoLog(meshProgram, 8192));
            GL20.glDeleteProgram(meshProgram);
            meshProgram = 0;
            meshFailed = true;
            return 0;
         }
         mu = Shaders.locations(meshProgram, DU_NAMES);
         return meshProgram;
      }

      static int perSquare(Opts o) {
         return Math.max(1, Math.min(LANES, Math.round(48F * o.densityPct / 100F)));
      }

      /** The generation's uniforms (compute or vertex-only program) and its textures on units 0-2 (+ GroundState's). */
      private static void genUniforms(int[] u, Params p, boolean trails, int colorTex, int depthTex, float div, float cox, float coy, int program) {
         Opts o = p.o;
         float zoomFade = 1F - smooth(o.maxZoom * 0.8F, o.maxZoom, p.zoom); // the blades shrink into the painting as the zoom nears the limit
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         boolean depthOk = o.depthCheck && depthTex != 0;
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthOk ? depthTex : 0);
         GL20.glUniform1i(u[0], 0);
         GL20.glUniform1i(u[1], 1);
         GL20.glUniform4f(u[2], p.map[0], p.map[1], p.map[2], p.map[3]);
         GL20.glUniform4f(u[3], p.map[4], p.map[5], 0F, 0F);
         GL20.glUniform4f(u[4], p.vp[0], p.vp[1], p.vp[2], p.vp[3]);
         GL30.glUniform4i(u[5], p.ox, p.oy, perSquare(o), o.clumps ? 1 : 0);
         GL20.glUniform4f(u[6], o.heightPct / 100F * zoomFade, o.widthPct / 100F, o.tufts ? 1F : 0F, 0F);
         GL20.glUniform4f(u[7], p.cx, p.cy, o.radius, Math.max(1.5F, o.radius * 0.3F));
         GL20.glUniform1f(u[8], depthOk ? 1F : 0F);
         GL20.glUniform4f(u[12], o.lodPx, o.lodSegments, o.devGen, 0F);
         GL20.glUniform4f(u[14], div, cox, coy, 0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, trails ? Trails.tex : 0);
         GL20.glUniform1i(u[9], 2);
         boolean wear = o.wear && !rig && GroundState.bindTexture();
         if (wear) {
            GroundState.applyUniforms(program);
         } else if (u[11] >= 0) {
            GL20.glUniform4f(u[11], 0F, 1F, 0F, 0F);
         }
         GL20.glUniform4f(u[10], trails ? p.now : -1F, o.trails ? 1F / o.trailSeconds : 0F, o.cut ? 1F / o.regrowSeconds : 0F, trails ? 1F : 0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }

      private static void unbindGenTextures() {
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      }

      /** The draw's uniforms; (gx, gy) the origin square the blades were generated against. */
      static float minHalfW = 0.45F; // px (the layer path draws at 1 / layerDiv: its own pixels)

      private static void drawUniforms(int[] u, Params p, int gx, int gy) {
         Opts o = p.o;
         GL20.glUniform4f(u[0], p.map[0], p.map[1], p.map[2], p.map[3]);
         GL20.glUniform4f(u[1], p.map[4], p.map[5], 0F, 0F);
         GL20.glUniform4f(u[2], p.vp[0], p.vp[1], p.vp[2], p.vp[3]);
         if (p.windOn) {
            GL20.glUniform4f(u[3], p.amp, p.lean, p.dirX, p.osc);
            GL20.glUniform4f(u[4], p.ph[0], p.ph[1], p.ph[2], p.ph[3]);
            GL20.glUniform4f(u[5], p.gustU, p.gustV, 0F, 0F);
         } else {
            GL20.glUniform4f(u[3], 0F, 0F, 1F, 0F);
         }
         PUSH.clear();
         PUSH.put(p.push, 0, p.push.length).flip();
         GL20.glUniform4fv(u[6], PUSH);
         GL20.glUniform1f(u[7], o.push ? p.nPush : 0F);
         FEET.clear();
         FEET.put(p.feet, 0, p.feet.length).flip();
         GL20.glUniform4fv(u[16], FEET);
         GL20.glUniform1f(u[17], o.feetPct > 0 ? p.nFeet : 0F);
         float ou = (float)Math.floorMod(gx - gy, 256), ov = (float)Math.floorMod(gx + gy, 256);
         GL20.glUniform4f(u[8], ou, ov, gx - p.ox, gy - p.oy);
         GL20.glUniform4f(u[9], o.segments, o.widthPct / 100F, o.view, o.devDraw);
         GL20.glUniform4f(u[10], p.sunX, p.sunY, p.sunZ, o.translucencyPct / 100F);
         GL20.glUniform4f(u[11], o.aoPct / 100F, o.sheenPct / 100F, minHalfW, 0F);
         GL20.glUniform1f(u[12], p.ts);
         // the waves' phases from the run's clock in double (no float drift over a long session)
         double wt = p.now;
         GL20.glUniform4f(u[15], p.windOn ? o.wavesPct / 100F : 0F, (float)(wt * WAVE_SPEED % (2.0 * Math.PI)), (float)(wt * 0.21 % (2.0 * Math.PI)), 0F);
      }

      private static void drawState() {
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthFunc(GL11.GL_LEQUAL);
         GL11.glDepthMask(true);
         GL11.glColorMask(true, true, true, false);
      }

      private static final java.nio.FloatBuffer PUSH = BufferUtils.createFloatBuffer(Sway.MAX_PUSH * 4);
      private static final java.nio.FloatBuffer FEET = BufferUtils.createFloatBuffer(MAX_FEET * 4);
      static final double WAVE_SPEED = 3.4; // rad/s: crests ~2 squares a second downwind
   }

   static float smooth(float e0, float e1, float x) {
      float t = Math.max(0F, Math.min(1F, (x - e0) / (e1 - e0)));
      return t * t * (3F - 2F * t);
   }

   /**
    * Visibility feedback (grassBladeFeedback): the generation writes each square's kept / on-screen blade counts into a
    * persistently mapped buffer (three regions, one a frame); two frames later the render thread reads them without any
    * sync (a region still holding the CPU's "unknown" mark is skipped) and leaves out for SKIP_FRAMES the squares that had
    * blades on screen but kept none: hidden by the static scene (a building, a trunk, a car park's baked cars). Squares
    * that were off screen are never skipped (the camera moves). Render thread only.
    */
   static final class Feedback {
      static final int REGIONS = 3, SKIP_FRAMES = 30;
      static int buf;
      static java.nio.IntBuffer mapped;
      static boolean failed;
      static int frame, n;
      static final int[][] LISTS = new int[REGIONS][MAX_SQUARES];
      static final int[] N = new int[REGIONS], OX = new int[REGIONS], OY = new int[REGIONS];
      static final int[] SKIP = new int[256 * 256], KEY = new int[256 * 256];
      static int[] out = new int[MAX_SQUARES];
      static long skipped, tested;

      static boolean ready() {
         if (buf != 0) {
            return true;
         }
         if (failed || !org.lwjgl.opengl.GL.getCapabilities().OpenGL44) {
            failed = true;
            return false;
         }
         buf = GL15.glGenBuffers();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buf);
         int flags = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_READ_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
         org.lwjgl.opengl.GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, (long)REGIONS * MAX_SQUARES * 4L, flags);
         ByteBuffer b = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0L, (long)REGIONS * MAX_SQUARES * 4L, flags);
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         if (b == null) {
            failed = true;
            return false;
         }
         mapped = b.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < REGIONS * MAX_SQUARES; i++) {
            mapped.put(i, -1);
         }
         return true;
      }

      /** This frame's squares without the ones skipped (n = how many are left). Reads the region written two frames ago. */
      static int[] filter(Params p, int count) {
         frame++;
         int r = (frame + 1) % REGIONS; // = frame - 2
         int base = r * MAX_SQUARES;
         int[] l = LISTS[r];
         for (int i = 0; i < N[r]; i++) {
            int c = mapped.get(base + i);
            if (c == -1) {
               continue; // not written yet
            }
            tested++;
            if ((c & 0xffff) == 0 && (c >>> 16) > 0) {
               int s = l[i];
               int x = OX[r] + (s & 255) - 128, y = OY[r] + (s >> 8 & 255) - 128;
               int k = (y & 255) << 8 | (x & 255);
               SKIP[k] = frame + SKIP_FRAMES;
               KEY[k] = x >> 8 & 0xffff | (y >> 8 & 0xffff) << 16;
            }
         }
         if (out.length < count) {
            out = new int[count];
         }
         int m = 0;
         for (int i = 0; i < count; i++) {
            int s = p.squares[i];
            int x = p.ox + (s & 255) - 128, y = p.oy + (s >> 8 & 255) - 128;
            int k = (y & 255) << 8 | (x & 255);
            if (SKIP[k] > frame && KEY[k] == (x >> 8 & 0xffff | (y >> 8 & 0xffff) << 16)) {
               skipped++;
               continue;
            }
            out[m++] = s;
         }
         n = m;
         return out;
      }

      /** The region this frame's generation writes: marked unknown, its square list kept for the read two frames later. */
      static int begin(Params p, int[] list, int count) {
         int r = frame % REGIONS;
         int base = r * MAX_SQUARES;
         for (int i = 0; i < count; i++) {
            mapped.put(base + i, -1);
         }
         System.arraycopy(list, 0, LISTS[r], 0, count);
         N[r] = count;
         OX[r] = p.ox;
         OY[r] = p.oy;
         return r;
      }
   }

   // ----------------------------------------------------------------------------------- movers and trails (game thread)

   /**
    * The characters and cars moving over the grass near the player: each one bends the blades round it this frame (the
    * push list, foliage sway's kernel) and stamps the trail map when it has moved a little (trample with its heading;
    * a melee swing stamps a cut arc in front of the swinger).
    */
   static final class Movers {
      static final int MAX_STAMPS = 64;
      static final float FEET_PUSH_W = 0.8F; // w units (x + y squares): ahead of a model's front
      static double now; // trail clock (s), game speed, stops while paused
      private static final boolean[] USED = new boolean[GroundMovers.MAX_NEAR];

      /** Game thread: the shared mover gather (GroundMovers, ground-common) read into this frame's stamps and push list. */
      static void update(Frame f, Opts o) {
         now += GroundMovers.dt();
         f.now = (float)now;
         f.nStamps = 0;
         f.nPush = 0;
         f.nFeet = 0;
         if (!o.trails && !o.cut && !o.push && o.feetPct <= 0) {
            return;
         }
         float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
         GroundMovers.wantNear(o.radius + 3F);
         int nn = GroundMovers.nNear;
         for (int i = 0; i < nn; i++) {
            GroundMovers.Mover m = GroundMovers.NEAR[i];
            zombie.iso.IsoMovingObject obj = m.obj;
            if (obj == null) {
               continue;
            }
            float x = m.x, y = m.y;
            if (obj.pzoptGmMarkX == 0F && obj.pzoptGmMarkY == 0F) {
               obj.pzoptGmMarkX = x;
               obj.pzoptGmMarkY = y;
            }
            float sx = x - obj.pzoptGmMarkX, sy = y - obj.pzoptGmMarkY;
            if (o.trails && sx * sx + sy * sy > 0.15F * 0.15F && f.nStamps < MAX_STAMPS) {
               float dir = (float)Math.atan2(sy, sx);
               if (m.who == GroundMovers.VEHICLE) {
                  // a car: its four wheels (the body's own axes; the axles ~3/4 of the half length out)
                  float ca = m.fx, sa = m.fy;
                  float hl = m.halfLength > 0F ? m.halfLength * 0.72F : 1.2F, hw = m.halfWidth > 0F ? m.halfWidth * 0.82F : 0.8F;
                  for (int a = -1; a <= 1; a += 2) {
                     for (int b = -1; b <= 1; b += 2) {
                        if (f.nStamps < MAX_STAMPS) {
                           stamp(f, x + ca * hl * a - sa * hw * b, y + sa * hl * a + ca * hw * b, 0.38F, dir, 0);
                        }
                     }
                  }
               } else {
                  stamp(f, x, y, m.radius, dir, 0);
               }
               obj.pzoptGmMarkX = x;
               obj.pzoptGmMarkY = y;
            }
            if (o.cut && m.melee && f.nStamps < MAX_STAMPS - 3) {
               float ang = (float)Math.atan2(m.fy, m.fx);
               float range = Math.max(0.8F, Math.min(2.0F, m.meleeRange));
               for (int a = -1; a <= 1; a++) {
                  float aa = ang + a * 0.6F;
                  stamp(f, x + (float)Math.cos(aa) * range * 0.7F, y + (float)Math.sin(aa) * range * 0.7F, 0.45F, ang, 1);
               }
            }
         }
         // the feet list: the characters nearest the player (standing ones too), their feet's depth push
         java.util.Arrays.fill(USED, 0, nn, false);
         for (int n = 0; n < MAX_FEET && o.feetPct > 0; n++) {
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < nn; i++) {
               GroundMovers.Mover m = GroundMovers.NEAR[i];
               if (USED[i] || m.who == GroundMovers.VEHICLE) {
                  continue;
               }
               float d = (m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy);
               if (d < bd) {
                  bd = d;
                  best = i;
               }
            }
            if (best < 0) {
               break;
            }
            USED[best] = true;
            GroundMovers.Mover m = GroundMovers.NEAR[best];
            int i = f.nFeet++;
            f.feet[i * 4] = m.x - f.view.ox;
            f.feet[i * 4 + 1] = m.y - f.view.oy;
            f.feet[i * 4 + 2] = FEET_PUSH_W * o.feetPct / 100F;
         }
         // the push list: the nearest movers still bending the grass
         java.util.Arrays.fill(USED, 0, nn, false);
         for (int n = 0; n < Sway.MAX_PUSH && o.push; n++) {
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < nn; i++) {
               GroundMovers.Mover m = GroundMovers.NEAR[i];
               if (USED[i] || m.strength < 0.02F) {
                  continue;
               }
               float d = (m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy);
               if (d < bd) {
                  bd = d;
                  best = i;
               }
            }
            if (best < 0) {
               break;
            }
            USED[best] = true;
            GroundMovers.Mover m = GroundMovers.NEAR[best];
            int i = f.nPush++;
            float u = m.x - m.y, v = m.x + m.y;
            f.push[i * 4] = (float)(u - Sway.GUST_PERIOD_SQ * Math.floor(u / Sway.GUST_PERIOD_SQ));
            f.push[i * 4 + 1] = (float)(v - Sway.GUST_PERIOD_SQ * Math.floor(v / Sway.GUST_PERIOD_SQ));
            f.push[i * 4 + 2] = m.who == GroundMovers.VEHICLE ? 1.8F : 0.7F;
            f.push[i * 4 + 3] = m.strength;
         }
      }

      /** kind 0 trample, 1 cut. Stamps: x, y, radius (+ 10 x kind), heading. */
      private static void stamp(Frame f, float x, float y, float r, float dir, int kind) {
         int i = f.nStamps++ * 4;
         f.stamps[i] = x;
         f.stamps[i + 1] = y;
         f.stamps[i + 2] = r + 10F * kind;
         f.stamps[i + 3] = dir;
      }
   }

   /** Render thread: the trail map (256 x 256 RGBA32F, two texels a square, toroidal, keyed by the high bits of the texel). */
   static final class Trails {
      static int tex, program, stampBuf;
      private static int[] su;
      private static boolean failed;
      private static java.nio.FloatBuffer upload;

      static boolean ready() {
         return tex != 0 && program != 0;
      }

      static void init() {
         if (tex != 0 || failed) {
            return;
         }
         program = Shaders.compute(STAMP, "grass blades: trail stamp");
         if (program == 0) {
            failed = true;
            return;
         }
         su = Shaders.locations(program, "uNow");
         tex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA32F, 256, 256);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         // every texel keyed to nowhere (-1): nothing trampled
         java.nio.FloatBuffer z = BufferUtils.createFloatBuffer(256 * 256 * 4);
         for (int i = 0; i < 256 * 256; i++) {
            z.put(-1e9F).put(-1e9F).put(-1F).put(0F);
         }
         z.flip();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 256, 256, GL11.GL_RGBA, GL11.GL_FLOAT, z);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         stampBuf = GL15.glGenBuffers();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, stampBuf);
         GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)Movers.MAX_STAMPS * 16L, GL15.GL_DYNAMIC_DRAW);
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         upload = BufferUtils.createFloatBuffer(Movers.MAX_STAMPS * 4);
      }

      static void stamp(Params p) {
         int n = Math.min(p.nStamps, Movers.MAX_STAMPS);
         upload.clear();
         upload.put(p.stamps, 0, n * 4).flip();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, stampBuf);
         GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, upload);
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         GL20.glUseProgram(program);
         GL20.glUniform1f(su[0], p.now);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, stampBuf);
         GL42.glBindImageTexture(0, tex, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA32F);
         GL43.glDispatchCompute(n, 1, 1);
         GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
         GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA32F);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, 0);
         stamps += n;
      }

      static long stamps;
   }

   /** One workgroup per stamp: the trail texels within its radius (a 16 x 16 box of half squares round it). */
   static final String STAMP = String.join("\n",
      "#version 430",
      "layout(local_size_x = 16, local_size_y = 16) in;",
      "layout(std430, binding = 3) readonly buffer Stamps { vec4 st[]; };",
      "layout(rgba32f, binding = 0) uniform image2D uMap;",
      "uniform float uNow;",
      "void main() {",
      "   vec4 s = st[gl_WorkGroupID.x];",
      "   float kind = s.z >= 10.0 ? 1.0 : 0.0;",
      "   float r = s.z - 10.0 * kind;",
      "   ivec2 c = ivec2(floor(s.xy * 2.0)) - 8 + ivec2(gl_LocalInvocationID.xy);",
      "   vec2 d = (vec2(c) + 0.5) * 0.5 - s.xy;",
      "   if (dot(d, d) > r * r) return;",
      "   float key = float((c.x >> 8) & 255) + 256.0 * float((c.y >> 8) & 255);",
      "   ivec2 q = c & 255;",
      "   vec4 m = imageLoad(uMap, q);",
      "   if (m.z != key) m = vec4(-1e9, -1e9, key, 0.0);",
      "   if (kind < 0.5) { m.x = uNow; m.w = s.w; } else { m.y = uNow; }",
      "   imageStore(uMap, q, m);",
      "}");

   // ------------------------------------------------------------------------------------------------------------ GLSL

   /** The hash and the gust shared by the generation and the draw. */
   static final String COMMON = String.join("\n",
      "uint gbHash(uint x) { x ^= x >> 16; x *= 0x7feb352dU; x ^= x >> 15; x *= 0x846ca68bU; x ^= x >> 16; return x; }",
      "float gbH01(uint x) { return float(gbHash(x) >> 8) * (1.0 / 16777216.0); }",
      "uint gbSeed(int x, int y, uint k) { return gbHash(uint(x) * 73856093U ^ uint(y) * 19349663U ^ k * 83492791U); }");

   /** A candidate's root (square record s, lane): its square, kind, stratified place, seed (gbMake and ROOT_VERT). */
   static final String ROOT_CODE = String.join("\n",
      "   int rx = int(s & 255u) - 128, ry = int((s >> 8) & 255u) - 128;",
      "   uint kind = (s >> 16) & 3u;",
      "   bool partial = ((s >> 18) & 1u) != 0u;",
      "   bool tuft = uParams.z > 0.5 && ((s >> 19) & 1u) != 0u && lane < 20u;", // an erosion tuft: a third of the blades round its foot
      "   int X = uOrigin.x + rx, Y = uOrigin.y + ry;",
      "   bool keep = int(lane) < uOrigin.z;",
      // stratified in an 8 x 8 grid of the square (bit-reversed lane: any count spreads over the whole square)
      "   uint cell = bitfieldReverse(lane) >> 26;",
      "   uint seed = gbSeed(X, Y, lane);",
      "   vec2 j = gbH2(seed);",
      "   vec2 f = (vec2(float(cell & 7u), float(cell >> 3)) + j) / 8.0;",
      "   if (tuft) f = 0.5 + (f - 0.5) * 0.5;",
      "   vec2 p = vec2(float(rx), float(ry)) + f;"); // relative to the origin square

   /**
    * The blade of one candidate (square record s, lane): false when it is dropped. The record is two vec4: (x, y relative to
    * the origin square, height in squares, packed root colour + flattening), (lean x, lean y, width in squares, packed
    * phase / stiffness / facing). Shared by the compute generation (GEN) and the vertex-only path (INLINE_VERT): the
    * root colour / depth come from the world framebuffer's textures, or (GB_ROOTS) the root pass's texel of the candidate.
    */
   static final String GEN_FN = String.join("\n",
      "uniform sampler2D uColor;",
      "uniform sampler2D uDepth;",
      "uniform vec4 uMapA;", // kA, cA, kB, cB: window px -> u, v (relative to the origin square)
      "uniform vec4 uMapB;", // kC, cC: depth -> w
      "uniform vec4 uVp;",
      "uniform ivec4 uOrigin;", // ox, oy, blades per square, clumps
      "uniform vec4 uParams;", // height scale, width scale, tufts
      "uniform vec4 uFade;", // player x, y (relative), radius, fade width (squares)
      "uniform float uHasDepth;",
      "uniform vec4 uCopy;", // the colour / depth textures: divisor, origin x, y
      "uniform vec4 uLod;", // small blade px, small segments, dev bisection
      "uniform sampler2D uTrail;", // 256 x 256 RGBA32F, two texels a square, toroidal: trample time, cut time, key, direction
      "uniform vec4 uTrailK;", // now (s), 1 / trail recovery (s), 1 / regrow (s), on
      "#define texture2D texture",
      GroundState.GLSL,
      COMMON,
      "vec2 gbH2(uint s) { return vec2(gbH01(s), gbH01(s ^ 0x9e3779b9U)); }",
      // the root's colour / depth: the world's textures at the root pixel, or (GB_ROOTS, the vertex-only path) the root pass's
      // texel of this candidate (lane, square index)
      "#ifdef GB_ROOTS",
      "vec3 gbFetchColor(ivec2 cp, uint lane, int sqi) { return texelFetch(uColor, ivec2(int(lane), sqi), 0).rgb; }",
      "float gbFetchDepth(ivec2 cp, uint lane, int sqi) { return texelFetch(uDepth, ivec2(int(lane), sqi), 0).r; }",
      "#else",
      "vec3 gbFetchColor(ivec2 cp, uint lane, int sqi) { return texelFetch(uColor, cp, 0).rgb; }",
      "float gbFetchDepth(ivec2 cp, uint lane, int sqi) { return texelFetch(uDepth, cp, 0).r; }",
      "#endif",
      "bool gbOnScreen;",
      "bool gbMake(uint s, uint lane, int sqi, out vec4 A, out vec4 B, out bool small) {",
      "   gbOnScreen = false;",
      "   A = vec4(0.0); B = vec4(0.0); small = false;",
      ROOT_CODE,
      "   vec2 pw = vec2(float(X), float(Y)) + f;", // world (for the clumps)
      "   float hmul = 1.0; vec2 lean = vec2(0.0); float tone = 1.0;",
      "   if (uOrigin.w != 0) {",
      // Voronoi clumps of ~1.3 squares: the blades of a clump share a height, a lean and a tone
      "      vec2 c = pw / 1.3; vec2 ic = floor(c); float best = 9.0; vec2 bestId = vec2(0.0);",
      "      for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {",
      "         vec2 id = ic + vec2(float(ox), float(oy));",
      "         uint cs = gbSeed(int(id.x), int(id.y), 977u);",
      "         vec2 q = id + gbH2(cs) - c; float d = dot(q, q);",
      "         if (d < best) { best = d; bestId = id; }",
      "      }",
      "      uint cs = gbSeed(int(bestId.x), int(bestId.y), 1931u);",
      "      float a = gbH01(cs) * 6.2831853;",
      "      lean = vec2(cos(a), sin(a)) * (0.15 + 0.35 * gbH01(cs ^ 0x51u));",
      "      hmul = (0.75 + 0.5 * gbH01(cs ^ 0x77u)) * (1.12 - 0.35 * sqrt(best));",
      "      tone = 0.92 + 0.16 * gbH01(cs ^ 0x1234u);",
      "   }",
      "   if (uLod.z == 2.0) return false;",
      "   float baseH = kind == 1u ? 0.16 : (kind == 2u ? 0.24 : 0.34);",
      "   float h = baseH * hmul * (0.6 + 0.6 * gbH01(seed ^ 0x2468u)) * uParams.x;",
      "   if (tuft) h = (0.36 + 0.3 * gbH01(seed ^ 0x2469u)) * uParams.x * uParams.z;",
      // distance fade: the blades shrink into the painting toward the radius, and thin out
      "   float dist = length(p - uFade.xy);",
      "   float fade = 1.0 - smoothstep(uFade.z - uFade.w, uFade.z, dist);",
      "   h *= mix(0.25, 1.0, fade);",
      "   if (gbH01(seed ^ 0x5555u) > fade) keep = false;",
      "   if (h < 0.02) keep = false;",
      // the root on screen
      "   float u = p.x - p.y, v = p.x + p.y;",
      "   vec2 px = vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "   float m = h * 2.0 / abs(uMapA.z) + 4.0;", // the blade's reach on screen
      "   if (px.x < uVp.x - m || px.x > uVp.x + uVp.z + m || px.y < uVp.y - m || px.y > uVp.y + uVp.w + m) keep = false;",
      "   gbOnScreen = keep;", // (visibility feedback: a square whose on-screen blades all fail the depth test is hidden by the static scene)
      "   ivec2 ip = ivec2(clamp(px, uVp.xy, uVp.xy + uVp.zw - 1.0));",
      "   ivec2 cp = ivec2((vec2(ip) - uCopy.yz) / uCopy.x);",
      "   vec3 col = vec3(0.0);",
      "   if (uLod.z == 3.0) keep = false;",
      "   if (keep) {",
      "      col = gbFetchColor(cp, lane, sqi);",
      "      if (uHasDepth > 0.5) {",
      "         float w = uMapB.x * gbFetchDepth(cp, lane, sqi) + uMapB.y;",
      "         float wr = p.x + p.y;",
      "         if (w > wr + (tuft ? 1.5 : 0.35) || w < wr - 1.5) keep = false;", // covered (a post, a trunk, a bench) or no ground drawn there
      "      }",
      "      if (partial && !tuft && !(col.g > col.b * 1.08 && col.g > col.r * 0.85)) keep = false;", // an overlay square: only over grass
      "   }",
      "   float phase = gbH01(seed ^ 0x3333u);",
      "   float stiff = 0.7 + 0.3 * gbH01(seed ^ 0x4444u);",
      "   float facing = gbH01(seed ^ 0x6666u);",
      "   float flatK = 0.0, cutK = 1.0;",
      "   if (keep) {",
      // the trail map at the root: flattened (lying along the walker's direction), cut (shorter, growing back)
      "      if (uTrailK.w > 0.5) {",
      "         ivec2 tq = ivec2(floor(pw * 2.0));",
      "         vec4 tr = texelFetch(uTrail, tq & 255, 0);",
      "         if (tr.z == float((tq.x >> 8) & 255) + 256.0 * float((tq.y >> 8) & 255)) {",
      "            float age = uTrailK.x - tr.x;",
      "            if (uTrailK.y > 0.0 && age >= 0.0) { float r = clamp(1.0 - age * uTrailK.y, 0.0, 1.0); flatK = r * sqrt(r) * (0.85 + 0.15 * gbH01(seed ^ 0x9999u)); }",
      "            float cage = uTrailK.x - tr.y;",
      "            if (uTrailK.z > 0.0 && cage >= 0.0) cutK = mix(0.15 + 0.1 * gbH01(seed ^ 0xaaaau), 1.0, clamp(cage * uTrailK.z, 0.0, 1.0));",
      "            if (flatK > 0.02) facing = fract(tr.w / 6.2831853 + 1.0);", // lying along the trail
      "         }",
      "      }",
      // worn paths (GroundState G: compaction from feet and wheels over game days): short, lying blades
      "      float wear = clamp(pzgStateAt(pw).g * 1.4, 0.0, 1.0);",
      "      h *= cutK * (1.0 - 0.65 * wear);",
      "      flatK = max(flatK, 0.7 * wear);",
      "   }",
      // two draws: blades shorter than uLod.x px on screen with uLod.y segments, the rest with the full count
      "   small = keep && h * 2.0 / abs(uMapA.z) < uLod.x;",
      "   if (!keep) return false;",
      "   vec2 l = lean + (gbH2(seed ^ 0x7777u) - 0.5) * 0.3;",
      "   float width = (0.028 + 0.014 * gbH01(seed ^ 0x8888u)) * uParams.y;",
      "   A = vec4(p, h, uintBitsToFloat(packUnorm4x8(vec4(clamp(col * tone, 0.0, 1.0), flatK))));",
      "   B = vec4(l, width, uintBitsToFloat(packUnorm4x8(vec4(phase, stiff, facing, 0.0))));",
      "   return true;",
      "}");

   /** Generation: one workgroup per grass square, one lane per blade candidate, compacted into the blade buffer + indirect counts. */
   static final String GEN = String.join("\n",
      "#version 430",
      "layout(local_size_x = " + LANES + ") in;",
      "layout(std430, binding = 0) readonly buffer Squares { uint sq[]; };",
      "struct Blade { vec4 a; vec4 b; };",
      "layout(std430, binding = 1) writeonly buffer Blades { Blade blades[]; };",
      "layout(std430, binding = 2) buffer Cmd { uint cCount; uint cInstances; uint cFirst; uint cBase; uint c1Count; uint c1Instances; uint c1First; uint c1Base; uint cN0; uint cN1; uint cMesh; uint cMeshFirst; uint cRx; uint cRy; uint cRz; };",
      "uniform vec4 uDraw;", // x: 1 = one strip of all blades (degenerate joins, no instancing), y, z: vertices a blade (big, small)
      GEN_FN,
      "layout(std430, binding = 5) writeonly buffer Feedback { uint fb[]; };",
      "uniform int uFb;", // the feedback region's first slot (-1: off)
      "shared uint sCount, sCount1, sOn;",
      "shared uint sBase, sBase1;",
      "void main() {",
      "   uint lane = gl_LocalInvocationID.x;",
      "   if (uLod.z == 1.0) return;",
      "   if (lane == 0u) { sCount = 0u; sCount1 = 0u; sOn = 0u; }",
      "   barrier();",
      "   vec4 A, B; bool small;",
      "   bool keep = gbMake(sq[gl_WorkGroupID.x], lane, int(gl_WorkGroupID.x), A, B, small);",
      "   uint li = 0u;",
      "   if (keep) li = small ? atomicAdd(sCount1, 1u) : atomicAdd(sCount, 1u);",
      "   if (gbOnScreen && uFb >= 0) atomicAdd(sOn, 1u);",
      "   barrier();",
      "   if (lane == 0u && uFb >= 0) fb[uFb + int(gl_WorkGroupID.x)] = (sCount + sCount1) | (sOn << 16);",
      "   if (lane == 0u) {",
      "      sBase = sCount > 0u ? atomicAdd(cN0, sCount) : 0u; sBase1 = sCount1 > 0u ? atomicAdd(cN1, sCount1) : 0u;",
      "      if (uDraw.x > 0.5) { if (sCount > 0u) atomicAdd(cCount, sCount * uint(uDraw.y)); if (sCount1 > 0u) atomicAdd(c1Count, sCount1 * uint(uDraw.z)); }",
      "      else { if (sCount > 0u) atomicAdd(cInstances, sCount); if (sCount1 > 0u) atomicAdd(c1Instances, sCount1); }",
      "      if (sCount > 0u) atomicMax(cMesh, (min(sBase + sCount, " + (MAX_BLADES / 2) + "u) + " + (MESH_BLADES - 1) + "u) / " + MESH_BLADES + "u);", // the mesh path's task count
      "      if (sCount > 0u) atomicMax(cRx, (min(sBase + sCount, " + (MAX_BLADES / 2) + "u) + 63u) / 64u);", // the software raster's workgroups
      "   }",
      "   barrier();",
      "   uint at = small ? " + (MAX_BLADES / 2) + "u + sBase1 + li : sBase + li;",
      "   if (keep && (small ? sBase1 + li : sBase + li) < " + (MAX_BLADES / 2) + "u) blades[at] = Blade(A, B);",
      "}");

   /** The draw's uniforms and functions (the compute path's VERT and the vertex-only INLINE_VERT). */
   static final String DRAW_FN = String.join("\n",
      "uniform vec4 uOriginW;", // the origin square's u, v modulo the gust period; the generation's origin shift
      "uniform vec4 uShape;", // segments, width scale, dev view, dev draw
      "uniform vec4 uWind;", // foliage sway's: amplitude scale, lean, downwind direction, oscillation
      "uniform vec4 uPh;",
      "uniform vec4 uGust;",
      "uniform vec4 uPush[" + Sway.MAX_PUSH + "];",
      "uniform vec4 uFeet[" + MAX_FEET + "];", // characters near the player: x, y (squares, relative to the origin square), depth push (w)
      "uniform float uFeetN;",
      "uniform float uPushN;",
      "uniform vec4 uSun;", // direction to the sun (x east, y south, z up), translucency
      "uniform vec4 uShade;", // ao, sheen
      "uniform vec4 uWave;", // Tsushima waves: amount, travelling phase, drift phase
      "uniform float uTs;",
      // foliage sway's gust ("sines") and its displacement pzSwDk, the same expressions (Sway.gustExpr / composite)
      "float gbGust(vec2 wp) { return 0.5 + 0.25 * (sin(dot(wp, vec2(0.625, 0.21875)) * 0.78539816 + uGust.x * 0.78539816) + sin(dot(wp, vec2(-0.28125, 0.71875)) * 0.78539816 + uGust.y * 0.78539816)); }",
      "float gbSwayX(float weight, float g, float phase) {",
      "   float amp = weight * " + Sway.AMP_MAX_PX + " * uWind.x;",
      "   float ph = uPh.x + phase * 6.2831853;",
      "   return amp * uWind.z * (uWind.y * (0.3 + 0.7 * g) + uWind.w * (0.45 + 0.55 * g) * sin(ph + g * 2.5));",
      "}",
      // Ghost of Tsushima's rolling waves: crests ~4 squares apart travelling downwind (screen x, foliage sway's wind),
      // bent along their length by a slow cross-wind drift; periodic in the gust lattice (256 squares) so the wrap is seamless
      "float gbWave(vec2 wp) {",
      "   float x = wp.x * uWind.z;",
      "   float ph = x * 0.78539816 - uWave.y + 0.9 * sin(wp.y * 0.12271846 + uWave.z) + 0.45 * sin(wp.y * 0.29452431 - x * 0.09817477);",
      "   float w = 0.5 + 0.5 * sin(ph);",
      "   return w * w * w;",
      "}",
      "float gbPushX(vec2 wp) {",
      "   float x = 0.0;",
      "   for (int i = 0; i < " + Sway.MAX_PUSH + "; i++) {",
      "      if (float(i) >= uPushN) break;",
      "      vec4 P = uPush[i];",
      "      vec2 d = wp - P.xy; d -= " + Sway.GUST_PERIOD_SQ + " * floor(d / " + Sway.GUST_PERIOD_SQ + " + 0.5);",
      "      float fv = d.y > 0.0 ? max(0.0, 1.0 - d.y / P.z) : clamp(1.0 + (d.y + 3.0) / P.z, 0.0, 1.0);",
      "      float t = d.x / (1.5 * P.z);",
      "      float fu = max(0.0, 1.0 - t * t);",
      "      x += 2.5 * t * fu * fu * fv * P.w;",
      "   }",
      "   return x;",
      "}",
      // world (squares; z: height in squares, 3 to a level) -> clip
      "float gbNear = 0.0;", // the feet push: depth toward the camera (w units) for a blade standing just in front of a character
      "vec4 gbClip(vec3 q) {",
      "   float u = q.x - q.y, v = q.x + q.y - 2.0 * q.z, w = q.x + q.y + q.z * (2.0 / 3.0) + 0.03 + gbNear;",
      "   vec2 px = vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "   float d = (w - uMapB.y) / uMapB.x;",
      "   return vec4((px - uVp.xy) / uVp.zw * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);",
      "}",
      "vec2 gbPx(vec3 q) {",
      "   float u = q.x - q.y, v = q.x + q.y - 2.0 * q.z;",
      "   return vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "}",
      // one vertex of blade (A, B): vertex vid of its strip
      "void gbVertex(vec4 A, vec4 B, int vid, out vec4 P, out vec3 C) {",
      "   vec3 root = vec3(A.xy + uOriginW.zw, 0.0);", // generated against an older origin square (amortised generation)
      // characters stand in the grass: a blade rooted just in front of a character's feet is drawn nearer than the model
      // (the models' depth runs ahead of their position), so it covers the shoes and shins it stands before
      "   gbNear = 0.0;",
      "   for (int i = 0; i < " + MAX_FEET + "; i++) {",
      "      if (float(i) >= uFeetN) break;",
      "      vec2 d = root.xy - uFeet[i].xy;",
      "      float ahead = d.x + d.y;",
      "      if (ahead > -0.05 && dot(d, d) < 0.5) gbNear = max(gbNear, uFeet[i].z);",
      "   }",
      "   float h = A.z;",
      "   vec4 col = unpackUnorm4x8(floatBitsToUint(A.w));",
      "   vec4 pk = unpackUnorm4x8(floatBitsToUint(B.w));", // phase, stiffness, facing
      "   int segs = int(uShape.x);",
      "   int seg = vid >> 1;",
      "   float t = vid >= 2 * segs ? 1.0 : float(seg) / float(segs);",
      "   float side = vid >= 2 * segs ? 0.0 : ((vid & 1) == 0 ? -1.0 : 1.0);",
      // wind: foliage sway's displacement of a grass texel at the blade's tip (logical px, screen x) -> world squares
      "   vec2 wp = mod(uOriginW.xy + vec2(A.x - A.y, A.x + A.y), " + Sway.GUST_PERIOD_SQ + ");",
      "   float g = gbGust(wp);",
      "   float weight = clamp(h / 0.75, 0.0, 1.0) * (0.75 + 0.25 * pk.y);",
      "   float dxPx = gbSwayX(weight, g, pk.x);",
      "   float wave = uWave.x > 0.0 ? gbWave(wp) * uWave.x : 0.0;",
      "   dxPx += wave * weight * " + Sway.AMP_MAX_PX + " * 0.9 * uWind.x * uWind.z;",
      "   if (uPushN > 0.5) dxPx += weight * " + Sway.AMP_MAX_PX + " * 0.9 * gbPushX(wp);",
      "   float du = dxPx / (32.0 * uTs);", // logical px (zoom 1) -> u
      "   vec2 H = B.xy * h + vec2(du * 0.5, -du * 0.5);",
      // trampled: the blade lies along the trail (facing = the walker's heading), its own lean and the wind mostly gone
      "   float flatK = col.a;",
      "   if (flatK > 0.0) { float fa2 = pk.z * 6.2831853; H = mix(H, vec2(cos(fa2), sin(fa2)) * 0.92 * h, flatK); }",
      // length-preserving quadratic Bezier (Jahrmann & Wimmer): the tip leans over and drops so the blade keeps its length
      "   float hl = length(H);",
      "   if (hl > 0.92 * h) { H *= 0.92 * h / hl; hl = 0.92 * h; }",
      "   vec3 p2 = vec3(H, sqrt(max(h * h - hl * hl, 0.0)));",
      "   vec3 p1 = vec3(H * 0.2, p2.z * 0.95);",
      "   float L0 = length(p2), L1 = length(p1) + length(p2 - p1);",
      "   float L = (2.0 * L0 + L1) / 3.0;",
      "   float k = h / max(L, 1e-4);",
      "   p1 *= k; p2 *= k;",
      "   float it = 1.0 - t;",
      "   vec3 pos = root + 2.0 * it * t * p1 + t * t * p2;",
      "   vec3 tan3 = 2.0 * it * p1 + 2.0 * t * (p2 - p1);",
      // the strip's width on screen, across the blade's screen direction
      "   vec2 a0 = gbPx(pos), a1 = gbPx(pos + tan3 * 0.05);",
      "   vec2 dir = a1 - a0; float dl = length(dir); dir = dl > 1e-5 ? dir / dl : vec2(0.0, 1.0);",
      "   vec2 nrm = vec2(-dir.y, dir.x);",
      "   float pxPerU = 1.0 / abs(uMapA.x);",
      "   float fa = pk.z * 3.14159265;",
      "   float face = mix(0.35, 1.0, abs(cos(fa)));",
      "   float halfW = 0.5 * B.z * pxPerU * face * pow(it, 0.8);",
      "   if (side != 0.0) halfW = max(halfW, uShade.z);",
      "   vec4 c = gbClip(pos);",
      "   c.xy += nrm * side * halfW / uVp.zw * 2.0;",
      "   P = uShape.w == 1.0 ? vec4(2.0, 2.0, 2.0, 1.0) : c;",
      // shading: the painted root colour, darker toward the root, a little lighter toward the tip, sheen where the gust is
      // strong, translucent against a low sun behind it (the camera looks from +x +y)
      "   vec3 base = col.rgb;",
      "   float ao = mix(1.0 - 0.32 * uShade.x, 1.0, smoothstep(0.0, 0.7, t));",
      "   float sheen = uShade.y * (0.30 * g * g + 0.5 * wave * uWind.x) * t;",
      "   vec2 sunH = uSun.xy; float sl = length(sunH);",
      "   float back = sl > 1e-4 ? max(0.0, dot(sunH / sl, vec2(-0.7071, -0.7071))) : 0.0;",
      "   float low = uSun.z > 0.0 ? 1.0 - smoothstep(0.12, 0.55, uSun.z) : 0.0;",
      "   float tr = uSun.w * back * low * t * t;",
      "   vec3 c3 = base * ao * (1.0 + 0.10 * t + sheen + 0.16 * flatK * t) + base * vec3(0.75, 0.62, 0.18) * tr;",
      "   if (uShape.z > 0.5) c3 = mix(vec3(1.0, 0.0, 0.0), vec3(1.0, 1.0, 0.0), t);",
      "   C = c3;",
      "}");

   static final String VERT = String.join("\n",
      "#version 430",
      "struct Blade { vec4 a; vec4 b; };",
      "layout(std430, binding = 1) readonly buffer Blades { Blade blades[]; };",
      "uniform vec4 uMapA;",
      "uniform vec4 uMapB;",
      "uniform vec4 uVp;",
      "uniform int uBase;",
      "uniform float uStrip;",
      "out vec3 vCol;",
      DRAW_FN,
      "void main() {",
      "   int vid = gl_VertexID, inst = gl_InstanceID;",
      "   if (uStrip > 0.5) { int V = int(uStrip); inst = vid / V; vid = clamp(vid - inst * V - 1, 0, V - 3); }", // one strip: each blade's first and last vertex doubled (degenerate joins)
      "   if (uShape.w == 2.0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vCol = vec3(0.0); return; }",
      "   Blade bl = blades[uBase + inst];",
      "   gbVertex(bl.a, bl.b, vid, gl_Position, vCol);",
      "}");

   /**
    * The vertex-only path (grassBladeDrawMode 2; OpenGL 4.1, the Mac): no compute, no storage buffers. One instance per
    * candidate (the square record an instanced attribute advancing every uOrigin.z instances), each vertex makes its blade
    * itself (gbMake) from a reduced copy of the world colour / depth (the world framebuffer is the draw target) and emits
    * its vertex; a dropped candidate collapses outside the clip volume.
    */
   static final String INLINE_VERT = String.join("\n",
      "#version 410",
      "#define GB_ROOTS 1",
      "uniform usamplerBuffer uSquares;",
      "uniform float uStrip;", // vertices a candidate (its strip + the two degenerate joins)
      "out vec3 vCol;",
      GEN_FN,
      DRAW_FN,
      "void main() {",
      // one strip of every candidate, joined by degenerate triangles (instancing a 9-vertex strip fills a third of a warp)
      "   int V = int(uStrip);",
      "   int cand = gl_VertexID / V;",
      "   int vid = clamp(gl_VertexID - cand * V - 1, 0, V - 3);",
      "   int sqi = cand / uOrigin.z;",
      "   uint lane = uint(cand - sqi * uOrigin.z);",
      "   vec4 A, B; bool small;",
      "   if (uShape.w == 2.0 || !gbMake(texelFetch(uSquares, sqi).r, lane, sqi, A, B, small)) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vCol = vec3(0.0); return; }",
      "   gbVertex(A, B, vid, gl_Position, vCol);",
      "}");

   /**
    * The mesh shader path (grassBladeDrawMode 3, GL_NV_mesh_shader): the compute generation's blades drawn MESH_BLADES to a
    * workgroup, each blade's strip as its own vertices and triangles (no degenerate joins, no per-instance cost); the
    * workgroup count comes from the generation (cMesh).
    */
   static String meshShader(int segments) {
      int v = 2 * segments + 1, t = v - 2;
      return String.join("\n",
         "#version 450",
         "#extension GL_NV_mesh_shader : require",
         "layout(local_size_x = 32) in;",
         "layout(triangles, max_vertices = " + (MESH_BLADES * v) + ", max_primitives = " + (MESH_BLADES * t) + ") out;",
         "struct Blade { vec4 a; vec4 b; };",
         "layout(std430, binding = 1) readonly buffer Blades { Blade blades[]; };",
         "layout(std430, binding = 2) readonly buffer Cmd { uint cmd[12]; };",
         "uniform vec4 uMapA;",
         "uniform vec4 uMapB;",
         "uniform vec4 uVp;",
         "out vec3 vCol[];",
         DRAW_FN,
         "void main() {",
         "   uint first = gl_WorkGroupID.x * " + MESH_BLADES + "u;",
         "   uint total = min(cmd[8], " + (MAX_BLADES / 2) + "u);",
         "   uint nb = first < total ? min(" + MESH_BLADES + "u, total - first) : 0u;",
         "   for (uint i = gl_LocalInvocationID.x; i < nb * " + v + "u; i += 32u) {",
         "      uint b = i / " + v + "u;",
         "      Blade bl = blades[first + b];",
         "      vec4 P; vec3 C;",
         "      gbVertex(bl.a, bl.b, int(i - b * " + v + "u), P, C);",
         "      gl_MeshVerticesNV[i].gl_Position = P;",
         "      vCol[i] = C;",
         "   }",
         "   for (uint i = gl_LocalInvocationID.x; i < nb * " + t + "u; i += 32u) {",
         "      uint b = i / " + t + "u, k = b * " + v + "u + (i - b * " + t + "u);",
         "      gl_PrimitiveIndicesNV[i * 3u] = k; gl_PrimitiveIndicesNV[i * 3u + 1u] = k + 1u; gl_PrimitiveIndicesNV[i * 3u + 2u] = k + 2u;",
         "   }",
         "   if (gl_LocalInvocationID.x == 0u) gl_PrimitiveCountNV = nb * " + t + "u;",
         "}");
   }

   /**
    * The compute software rasteriser (grassBladeDrawMode 4; GL_NV_shader_atomic_int64): one thread per generated blade
    * walks its strip's triangles over their pixel boxes and keeps the nearest fragment per pixel with a 64-bit atomicMin
    * of (depth, colour) in a buffer over the blades' screen rectangle (Jarl's 2D grass, 80.lv 2025); RESOLVE_FRAG then
    * writes the kept fragments through the hardware depth test and resets the buffer.
    */
   static final String RASTER = String.join("\n",
      "#version 450",
      "#extension GL_ARB_gpu_shader_int64 : require",
      "#extension GL_NV_shader_atomic_int64 : require",
      "layout(local_size_x = 64) in;",
      "struct Blade { vec4 a; vec4 b; };",
      "layout(std430, binding = 1) readonly buffer Blades { Blade blades[]; };",
      "layout(std430, binding = 2) readonly buffer Cmd { uint cmd[15]; };",
      "layout(std430, binding = 4) buffer Pix { uint64_t pix[]; };",
      "uniform vec4 uMapA;",
      "uniform vec4 uMapB;",
      "uniform vec4 uVp;",
      "uniform ivec4 uRect;", // x0, y0, w, h (window px)
      DRAW_FN,
      "vec3 gbWin(vec4 P) { return vec3((P.xy * 0.5 + 0.5) * uVp.zw + uVp.xy, P.z * 0.5 + 0.5); }",
      "float gbEdge(vec2 a, vec2 b, vec2 c) { return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x); }",
      "void gbTri(vec3 a, vec3 b, vec3 c, vec3 ca, vec3 cb, vec3 cc) {",
      "   float area = gbEdge(a.xy, b.xy, c.xy);",
      "   if (abs(area) < 1e-6) return;",
      "   ivec2 lo = max(ivec2(floor(min(a.xy, min(b.xy, c.xy)) - 0.5)), uRect.xy);",
      "   ivec2 hi = min(ivec2(ceil(max(a.xy, max(b.xy, c.xy)) - 0.5)), uRect.xy + uRect.zw - 1);",
      "   if (hi.x - lo.x > 64 || hi.y - lo.y > 64) return;", // (a blade triangle is a few pixels)
      "   for (int y = lo.y; y <= hi.y; y++) for (int x = lo.x; x <= hi.x; x++) {",
      "      vec2 q = vec2(float(x), float(y)) + 0.5;",
      "      float w0 = gbEdge(b.xy, c.xy, q) / area, w1 = gbEdge(c.xy, a.xy, q) / area, w2 = 1.0 - w0 - w1;",
      "      if (w0 < 0.0 || w1 < 0.0 || w2 < 0.0) continue;",
      "      float z = w0 * a.z + w1 * b.z + w2 * c.z;",
      "      if (z < 0.0 || z > 1.0) continue;",
      "      vec3 col = clamp(w0 * ca + w1 * cb + w2 * cc, 0.0, 1.0);",
      "      uint idx = uint((y - uRect.y) * uRect.z + (x - uRect.x));",
      "      atomicMin(pix[idx], (uint64_t(floatBitsToUint(z)) << 32) | uint64_t(packUnorm4x8(vec4(col, 1.0))));",
      "   }",
      "}",
      "void main() {",
      "   uint bi = gl_GlobalInvocationID.x;",
      "   if (bi >= min(cmd[8], " + (MAX_BLADES / 2) + "u)) return;",
      "   Blade bl = blades[bi];",
      "   int segs = int(uShape.x);",
      "   vec4 P0, P1; vec3 C0, C1;",
      "   gbVertex(bl.a, bl.b, 0, P0, C0); gbVertex(bl.a, bl.b, 1, P1, C1);",
      "   vec3 l0 = gbWin(P0), r0 = gbWin(P1);",
      "   for (int k = 1; k <= segs; k++) {",
      "      vec4 P2, P3; vec3 C2, C3;",
      "      gbVertex(bl.a, bl.b, 2 * k, P2, C2);",
      "      vec3 l1 = gbWin(P2);",
      "      if (k == segs) { gbTri(l0, r0, l1, C0, C1, C2); break; }",
      "      gbVertex(bl.a, bl.b, 2 * k + 1, P3, C3);",
      "      vec3 r1 = gbWin(P3);",
      "      gbTri(l0, r0, l1, C0, C1, C2);",
      "      gbTri(r0, r1, l1, C1, C3, C2);",
      "      l0 = l1; r0 = r1; C0 = C2; C1 = C3;",
      "   }",
      "}");

   /** The layer path's composite: the layer's blade texels (alpha 1) blended bilinearly, the nearest one's depth. */
   static final String LAYER_COMPOSITE = String.join("\n",
      "#version 430",
      "uniform sampler2D uLayerC;",
      "uniform sampler2D uLayerD;",
      "uniform vec4 uL;", // the layer's lower-left corner in window px (pan included), divisor
      "uniform vec4 uLd;", // depth shift (pan), last texel x, y
      "layout(location = 0) out vec4 oColor;",
      "void main() {",
      "   vec2 q = (gl_FragCoord.xy - uL.xy) / uL.z - 0.5;",
      "   ivec2 i = ivec2(floor(q)); vec2 f = q - vec2(i);",
      "   vec4 acc = vec4(0.0); float d = 1.0;",
      "   for (int k = 0; k < 4; k++) {",
      "      ivec2 o = ivec2(k & 1, k >> 1);",
      "      ivec2 t = clamp(i + o, ivec2(0), ivec2(uLd.yz));",
      "      vec4 c = texelFetch(uLayerC, t, 0);",
      "      if (c.a < 0.5) continue;",
      "      float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) + 1e-4;",
      "      acc += vec4(c.rgb * w, w);",
      "      d = min(d, texelFetch(uLayerD, t, 0).r);",
      "   }",
      "   if (acc.w <= 0.0) discard;",
      "   oColor = vec4(acc.rgb / acc.w, 1.0);",
      "   gl_FragDepth = d + uLd.x;",
      "}");

   static final String RESOLVE_VERT = String.join("\n",
      "#version 450",
      "void main() { vec2 q = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(q * 2.0 - 1.0, 0.0, 1.0); }");

   static final String RESOLVE_FRAG = String.join("\n",
      "#version 450",
      "#extension GL_ARB_gpu_shader_int64 : require",
      "layout(std430, binding = 4) buffer Pix { uint64_t pix[]; };",
      "uniform ivec4 uRect;",
      "layout(location = 0) out vec4 oColor;",
      "void main() {",
      "   ivec2 q = ivec2(gl_FragCoord.xy) - uRect.xy;",
      "   uint idx = uint(q.y * uRect.z + q.x);",
      "   uint64_t v = pix[idx];",
      "   if (v == 0xFFFFFFFFFFFFFFFFUL) discard;",
      "   pix[idx] = 0xFFFFFFFFFFFFFFFFUL;",
      "   oColor = unpackUnorm4x8(uint(v & 0xFFFFFFFFUL));",
      "   gl_FragDepth = uintBitsToFloat(uint(v >> 32));",
      "}");

   /**
    * The vertex-only path's root pass: one point per candidate into texel (lane, square index) of the root targets, its
    * root pixel's world colour and depth (read while the world framebuffer is not the target).
    */
   static final String ROOT_VERT = String.join("\n",
      "#version 410",
      "uniform usamplerBuffer uSquares;",
      "uniform sampler2D uColor;",
      "uniform sampler2D uDepth;",
      "uniform vec4 uMapA;",
      "uniform vec4 uVp;",
      "uniform ivec4 uOrigin;",
      "uniform vec4 uParams;",
      "uniform vec2 uRootSize;",
      "out vec4 vRootColor;",
      "out float vRootDepth;",
      COMMON,
      "vec2 gbH2(uint s) { return vec2(gbH01(s), gbH01(s ^ 0x9e3779b9U)); }",
      "void main() {",
      "   int sqi = gl_VertexID / uOrigin.z;",
      "   uint lane = uint(gl_VertexID - sqi * uOrigin.z);",
      "   uint s = texelFetch(uSquares, sqi).r;",
      ROOT_CODE,
      "   float u = p.x - p.y, v = p.x + p.y;",
      "   vec2 px = vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "   ivec2 ip = ivec2(clamp(px, uVp.xy, uVp.xy + uVp.zw - 1.0));",
      "   vRootColor = texelFetch(uColor, ip, 0);",
      "   vRootDepth = texelFetch(uDepth, ip, 0).r;",
      "   gl_Position = vec4((vec2(float(lane), float(sqi)) + 0.5) / uRootSize * 2.0 - 1.0, 0.0, 1.0);",
      "   gl_PointSize = 1.0;",
      "}");

   static final String ROOT_FRAG = String.join("\n",
      "#version 410",
      "in vec4 vRootColor;",
      "in float vRootDepth;",
      "layout(location = 0) out vec4 oColor;",
      "layout(location = 1) out vec4 oDepth;",
      "void main() { oColor = vRootColor; oDepth = vec4(vRootDepth); }");

   static final String FRAG = String.join("\n",
      "#version 430",
      "in vec3 vCol;",
      "layout(location = 0) out vec4 oColor;",
      "void main() { oColor = vec4(vCol, 1.0); }");

   // ----------------------------------------------------------------------------------------------- GL helpers

   static final class Shaders {
      static String lastLog = "";

      static int compile(int type, String src, String name) {
         int sh = GL20.glCreateShader(type);
         GL20.glShaderSource(sh, src);
         GL20.glCompileShader(sh);
         if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            lastLog = GL20.glGetShaderInfoLog(sh, 8192);
            Log.warn(name + ": compile failed: " + lastLog);
            GL20.glDeleteShader(sh);
            return 0;
         }
         return sh;
      }

      static int compute(String src, String name) {
         int sh = compile(GL43.GL_COMPUTE_SHADER, src, name);
         if (sh == 0) {
            return 0;
         }
         int p = GL20.glCreateProgram();
         GL20.glAttachShader(p, sh);
         GL20.glLinkProgram(p);
         GL20.glDeleteShader(sh);
         if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            lastLog = GL20.glGetProgramInfoLog(p, 8192);
            Log.warn(name + ": link failed: " + lastLog);
            GL20.glDeleteProgram(p);
            return 0;
         }
         return p;
      }

      static int link(String vert, String frag, String name) {
         int vs = compile(GL20.GL_VERTEX_SHADER, vert, name + " (vertex)");
         int fs = compile(GL20.GL_FRAGMENT_SHADER, frag, name + " (fragment)");
         if (vs == 0 || fs == 0) {
            return 0;
         }
         int p = GL20.glCreateProgram();
         GL20.glAttachShader(p, vs);
         GL20.glAttachShader(p, fs);
         GL20.glLinkProgram(p);
         GL20.glDeleteShader(vs);
         GL20.glDeleteShader(fs);
         if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            lastLog = GL20.glGetProgramInfoLog(p, 8192);
            Log.warn(name + ": link failed: " + lastLog);
            GL20.glDeleteProgram(p);
            return 0;
         }
         return p;
      }

      static int[] locations(int program, String... names) {
         int[] l = new int[names.length];
         for (int i = 0; i < names.length; i++) {
            l[i] = GL20.glGetUniformLocation(program, names[i]);
         }
         return l;
      }
   }
}
