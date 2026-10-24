package pzopt;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
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
      boolean trails = true, cut = true, push = true;
      float trailSeconds = 45F, regrowSeconds = 900F;
      int view; // dev: 1 = flat debug colour

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
         o.trails = trails;
         o.cut = cut;
         o.push = push;
         o.trailSeconds = trailSeconds;
         o.regrowSeconds = regrowSeconds;
         o.view = view;
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
                  case "trails" -> trails = Boolean.parseBoolean(v);
                  case "cut" -> cut = Boolean.parseBoolean(v);
                  case "push" -> push = Boolean.parseBoolean(v);
                  case "trailSeconds" -> trailSeconds = Float.parseFloat(v);
                  case "regrowSeconds" -> regrowSeconds = Float.parseFloat(v);
                  case "view" -> view = Integer.parseInt(v);
                  default -> Log.warn("grass blades: unknown alternation knob " + k);
               }
            } catch (RuntimeException e) {
               Log.warn("grass blades: bad alternation knob " + kv);
            }
         }
      }

      boolean off;
   }

   private static Opts optsA() {
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
      o.trails = Config.GRASS_BLADE_TRAILS;
      o.cut = Config.GRASS_BLADE_CUT;
      o.push = Config.GRASS_BLADE_PUSH;
      o.trailSeconds = Math.max(1, Config.GRASS_BLADE_TRAIL_SECONDS);
      o.regrowSeconds = Math.max(1, Config.GRASS_BLADE_REGROW_SECONDS);
      o.view = Config.DEV_GRASS_VIEW;
      return o;
   }

   // ------------------------------------------------------------------------------------------- squares (game thread)

   /** Grass kind of a floor sprite: 0 none, 1..3 the grass rows of blends_natural_01 (short, medium, tall). */
   private static final IdentityHashMap<IsoSprite, Integer> KIND = new IdentityHashMap<>();

   static int kindOfName(String name) {
      if (name == null) {
         return 0;
      }
      int id;
      if (name.startsWith("blends_natural_01_")) {
         try {
            id = Integer.parseInt(name.substring(18));
         } catch (NumberFormatException e) {
            return 0;
         }
      } else if (name.startsWith("floors_exterior_natural_01_")) {
         // (the old floors IsoChunk maps to the blends: 0 -> 16, 1 -> 32, 2 -> 48)
         return switch (name.substring(27)) {
            case "0" -> 1;
            case "1" -> 2;
            case "2" -> 3;
            default -> 0;
         };
      } else {
         return 0;
      }
      int row = id / 8, col = id % 8;
      // rows 2, 4, 6: the three grasses (cols 0, 5-7 whole tiles, 1-4 their blends into the next row); odd rows 3, 5, 7:
      // the grasses' edges over another ground (partial)
      if (row == 2 || row == 4 || row == 6) {
         return (row / 2) | (col >= 1 && col <= 4 ? 4 : 0);
      }
      if (row == 3 || row == 5 || row == 7) {
         return ((row - 1) / 2) | 4;
      }
      return 0;
   }

   private static int kindOf(IsoSprite s) {
      if (s == null) {
         return 0;
      }
      Integer k = KIND.get(s);
      if (k == null) {
         k = kindOfName(s.getName());
         KIND.put(s, k);
      }
      return k;
   }

   /** A square's grass: bits 0-1 kind (1..3), bit 2 partial (grass over another ground: the root colour decides), 0 none. */
   static int squareGrass(IsoGridSquare sq) {
      IsoObject floor = sq.getFloor();
      if (floor == null) {
         return 0;
      }
      int k = kindOf(floor.getSprite());
      if ((k & 3) != 0 && (k & 4) == 0) {
         return k;
      }
      // grass overlays on a dirt / sand floor: partial
      java.util.ArrayList<IsoSpriteInstance> att = floor.attachedAnimSprite;
      if (att != null) {
         for (int i = 0, n = att.size(); i < n; i++) {
            IsoSpriteInstance si = att.get(i);
            int ka = si == null ? 0 : kindOf(si.parentSprite);
            if ((ka & 3) != 0) {
               return (ka & 3) | 4;
            }
         }
      }
      return k;
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
            if (Config.DEV_GRASS_ALT_B.isEmpty()) {
               o.off = true;
            } else {
               o.apply(Config.DEV_GRASS_ALT_B);
            }
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
      for (int y = icy - r; y <= icy + r; y++) {
         float dy = y + 0.5F - cy;
         for (int x = icx - r; x <= icx + r; x++) {
            float dx = x + 0.5F - cx;
            if (dx * dx + dy * dy > r2) {
               continue;
            }
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq == null) {
               continue;
            }
            int g = squareGrass(sq);
            if (g == 0 || n >= MAX_SQUARES) {
               continue;
            }
            int rx = x - ox + 128, ry = y - oy + 128;
            if (rx < 0 || rx > 255 || ry < 0 || ry > 255) {
               continue;
            }
            f.squares[n++] = rx | ry << 8 | g << 16;
         }
      }
      f.n = n;
      f.dump = dumpTag();
      f.cx = cx - ox;
      f.cy = cy - oy;
      f.zoom = zoom;
      Movers.update(f, o);
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
            Log.info(String.format(java.util.Locale.ROOT, "grass blades: %d frames, %.1f squares/frame, game thread %.2f us/frame | %s",
               frames, squaresTotal / (double)frames, gameNs / 1e3 / frames, Gpu.stats()));
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
      public float[] stamps = new float[0];
      public int nStamps;
      public float now;
   }

   /** The offline rig's parameters: the knobs of the launch's Config (A set). */
   public static Params rigParams() {
      Params p = new Params();
      p.o = optsA();
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

   static final class Frame extends TextureDraw.GenericDrawer {
      String dump;
      Opts o;
      final Ssr.View view = new Ssr.View();
      int[] squares = new int[1024];
      int n;
      float cx, cy, zoom, sunX, sunY, sunZ;
      final float[] stamps = new float[Movers.MAX_STAMPS * 4];
      int nStamps;
      final float[] push = new float[Sway.MAX_PUSH * 4];
      int nPush;
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
      static boolean rig; // the offline rig: no OpenGL 4.3 caps check through the game
      private static int genProgram, drawProgram, vao, squareBuf, bladeBuf, cmdBuf;
      private static int[] gu, du;
      private static IntBuffer squareUpload;
      private static final ByteBuffer CMD = BufferUtils.createByteBuffer(16);
      private static final float[] VP = new float[4];
      private static final int[] VPI = new int[4];
      private static final float[] MAP = new float[6];
      private static int worldFbo = -1, worldColor, worldDepth;
      private static long vpKey = Long.MIN_VALUE;
      private static final java.util.HashMap<Integer, int[]> FBO_ATTACH = new java.util.HashMap<>();
      static long draws, squaresDrawn, noWorld;

      static String stats() {
         return "draws " + draws + " squares " + squaresDrawn + " no world fbo " + noWorld + (failed ? " FAILED" : "");
      }

      /** Render thread, at world entry (shaderWarmup) with blades on: the programs built before the first blade. */
      static void warm() {
         if (Config.GRASS_BLADES && !ready && !failed) {
            init();
         }
      }

      private static boolean init() {
         if (ready) {
            return true;
         }
         if (failed) {
            return false;
         }
         if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
            failed = true;
            Log.info("grass blades: needs OpenGL 4.3 (compute, storage buffers, indirect draws): off");
            return false;
         }
         long t0 = System.nanoTime();
         genProgram = Shaders.compute(GEN, "grass blades: generation");
         drawProgram = Shaders.link(VERT, FRAG, "grass blades: draw");
         if (genProgram == 0 || drawProgram == 0) {
            failed = true;
            return false;
         }
         gu = Shaders.locations(genProgram, "uColor", "uDepth", "uMapA", "uMapB", "uVp", "uOrigin", "uParams", "uFade", "uHasDepth", "uTrail", "uTrailK");
         du = Shaders.locations(drawProgram, "uMapA", "uMapB", "uVp", "uWind", "uPh", "uGust", "uPush", "uPushN", "uOriginW", "uShape", "uSun", "uShade", "uTs");
         vao = GL30.glGenVertexArrays();
         squareBuf = GL15.glGenBuffers();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, squareBuf);
         GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_SQUARES * 4L, GL15.GL_DYNAMIC_DRAW);
         bladeBuf = GL15.glGenBuffers();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bladeBuf);
         GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_BLADES * 32L, GL15.GL_DYNAMIC_COPY);
         cmdBuf = GL15.glGenBuffers();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
         GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 16L, GL15.GL_DYNAMIC_DRAW);
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         squareUpload = BufferUtils.createIntBuffer(MAX_SQUARES);
         Trails.init();
         ready = true;
         Log.info(String.format(java.util.Locale.ROOT, "grass blades: programs ready (%.1f ms)", (System.nanoTime() - t0) / 1e6));
         return true;
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
         p.stamps = f.stamps;
         p.nStamps = f.nStamps;
         p.now = f.now;
         if (f.dump != null) {
            Dump.write(f, p);
         }
         run(p);
         // back to the sprite renderer's state
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      static final Params P = new Params();

      /**
       * Render thread (and the offline rig, tools/GrassRig.java): generation + draw into the bound framebuffer from plain
       * parameters. Leaves program 0, no textures, colour mask all, depth test on, blend off.
       */
      public static void run(Params p) {
         if (!init()) {
            return;
         }
         Opts o = p.o;
         float kA = p.map[0], cA = p.map[1], kB = p.map[2], cB = p.map[3], kC = p.map[4], cC = p.map[5];
         int n = Math.min(p.n, MAX_SQUARES);
         squareUpload.clear();
         squareUpload.put(p.squares, 0, n).flip();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, squareBuf);
         GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, squareUpload);
         int verts = 2 * o.segments + 1;
         CMD.clear();
         CMD.putInt(verts).putInt(0).putInt(0).putInt(0).flip();
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmdBuf);
         GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, CMD);
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

         // trails: this frame's stamps into the trail map
         boolean trails = (o.trails || o.cut) && Trails.ready();
         if (trails && p.nStamps > 0) {
            Trails.stamp(p);
         }

         // generation
         float zoomFade = 1F - smooth(o.maxZoom * 0.8F, o.maxZoom, p.zoom); // the blades shrink into the painting as the zoom nears the limit
         int perSquare = Math.max(1, Math.min(LANES, Math.round(48F * o.densityPct / 100F)));
         GL20.glUseProgram(genProgram);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, p.colorTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         boolean depthOk = o.depthCheck && p.depthTex != 0;
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthOk ? p.depthTex : 0);
         GL20.glUniform1i(gu[0], 0);
         GL20.glUniform1i(gu[1], 1);
         GL20.glUniform4f(gu[2], kA, cA, kB, cB);
         GL20.glUniform4f(gu[3], kC, cC, 0F, 0F);
         GL20.glUniform4f(gu[4], p.vp[0], p.vp[1], p.vp[2], p.vp[3]);
         GL30.glUniform4i(gu[5], p.ox, p.oy, perSquare, o.clumps ? 1 : 0);
         GL20.glUniform4f(gu[6], o.heightPct / 100F * zoomFade, o.widthPct / 100F, 0F, 0F);
         GL20.glUniform4f(gu[7], p.cx, p.cy, o.radius, Math.max(1.5F, o.radius * 0.3F));
         GL20.glUniform1f(gu[8], depthOk ? 1F : 0F);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, trails ? Trails.tex : 0);
         GL20.glUniform1i(gu[9], 2);
         GL20.glUniform4f(gu[10], trails ? p.now : -1F, o.trails ? 1F / o.trailSeconds : 0F, o.cut ? 1F / o.regrowSeconds : 0F, trails ? 1F : 0F);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, squareBuf);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, bladeBuf);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, cmdBuf);
         GL43.glDispatchCompute(n, 1, 1);
         GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

         // draw
         GL20.glUseProgram(drawProgram);
         GL20.glUniform4f(du[0], kA, cA, kB, cB);
         GL20.glUniform4f(du[1], kC, cC, 0F, 0F);
         GL20.glUniform4f(du[2], p.vp[0], p.vp[1], p.vp[2], p.vp[3]);
         if (p.windOn) {
            GL20.glUniform4f(du[3], p.amp, p.lean, p.dirX, p.osc);
            GL20.glUniform4f(du[4], p.ph[0], p.ph[1], p.ph[2], p.ph[3]);
            GL20.glUniform4f(du[5], p.gustU, p.gustV, 0F, 0F);
         } else {
            GL20.glUniform4f(du[3], 0F, 0F, 1F, 0F);
         }
         PUSH.clear();
         PUSH.put(p.push, 0, p.push.length).flip();
         GL20.glUniform4fv(du[6], PUSH);
         GL20.glUniform1f(du[7], o.push ? p.nPush : 0F);
         float ou = (float)Math.floorMod(p.ox - p.oy, 256), ov = (float)Math.floorMod(p.ox + p.oy, 256);
         GL20.glUniform4f(du[8], ou, ov, 0F, 0F);
         GL20.glUniform4f(du[9], o.segments, o.widthPct / 100F, o.view, 0F);
         GL20.glUniform4f(du[10], p.sunX, p.sunY, p.sunZ, o.translucencyPct / 100F);
         GL20.glUniform4f(du[11], o.aoPct / 100F, o.sheenPct / 100F, 0F, 0F);
         GL20.glUniform1f(du[12], p.ts);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_BLEND);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthFunc(GL11.GL_LEQUAL);
         GL11.glDepthMask(true);
         GL11.glColorMask(true, true, true, false);
         GL30.glBindVertexArray(vao);
         GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBuf);
         GL40.glDrawArraysIndirect(GL11.GL_TRIANGLE_STRIP, 0L);
         GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
         GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
         GL30.glBindVertexArray(0);
         GL20.glUseProgram(0);
         GL11.glColorMask(true, true, true, true);
         draws++;
         squaresDrawn += n;
      }

      private static final java.nio.FloatBuffer PUSH = BufferUtils.createFloatBuffer(Sway.MAX_PUSH * 4);
   }

   static float smooth(float e0, float e1, float x) {
      float t = Math.max(0F, Math.min(1F, (x - e0) / (e1 - e0)));
      return t * t * (3F - 2F * t);
   }

   // ----------------------------------------------------------------------------------- movers and trails (game thread)

   /**
    * The characters and cars moving over the grass near the player: each one bends the blades round it this frame (the
    * push list, foliage sway's kernel) and stamps the trail map when it has moved a little (trample with its heading;
    * a melee swing stamps a cut arc in front of the swinger).
    */
   static final class Movers {
      static final int MAX_STAMPS = 64;
      private static final IdentityHashMap<Object, float[]> STATE = new IdentityHashMap<>(); // lastX, lastY, stampX, stampY, strength, seenFrame, radius
      private static int frame;
      private static long lastNs;
      static double now; // trail clock (s), game speed, stops while paused
      private static final java.util.ArrayList<Object> CAND = new java.util.ArrayList<>();
      private static final java.util.ArrayList<float[]> CANDPOS = new java.util.ArrayList<>();

      static void update(Frame f, Opts o) {
         frame++;
         long t = System.nanoTime();
         double dt = lastNs == 0L ? 0.0 : Math.min(0.1, (t - lastNs) / 1e9);
         lastNs = t;
         boolean paused = zombie.GameTime.isGamePaused();
         dt *= paused ? 0.0 : Math.min(4.0, Math.max(0.0, zombie.GameTime.getInstance().getTrueMultiplier()));
         now += dt;
         f.now = (float)now;
         f.nStamps = 0;
         f.nPush = 0;
         if (!o.trails && !o.cut && !o.push) {
            return;
         }
         float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
         float reach = o.radius + 3F;
         float reach2 = reach * reach;
         CAND.clear();
         CANDPOS.clear();
         for (zombie.characters.IsoPlayer pl : zombie.characters.IsoPlayer.players) {
            if (pl != null && !pl.isDead()) {
               add(pl, pl.getX(), pl.getY(), pl.getZ(), 0.32F, cx, cy, reach2);
            }
         }
         IsoCell cell = IsoWorld.instance.currentCell;
         java.util.ArrayList<zombie.characters.IsoZombie> zs = cell.getZombieList();
         for (int i = 0, n = zs.size(); i < n && CAND.size() < 96; i++) {
            zombie.characters.IsoZombie z = zs.get(i);
            if (z != null) {
               add(z, z.getX(), z.getY(), z.getZ(), 0.32F, cx, cy, reach2);
            }
         }
         for (zombie.vehicles.BaseVehicle v : cell.getVehicles()) {
            if (v != null) {
               add(v, v.getX(), v.getY(), v.getZ(), 1.0F, cx, cy, reach2);
            }
         }
         for (int i = 0; i < CAND.size(); i++) {
            Object obj = CAND.get(i);
            float[] pos = CANDPOS.get(i);
            float x = pos[0], y = pos[1], r = pos[2];
            float[] s = STATE.get(obj);
            if (s == null) {
               s = new float[] {x, y, x, y, 0F, frame, r};
               STATE.put(obj, s);
            }
            float mx = x - s[0], my = y - s[1];
            boolean moving = mx * mx + my * my > 1e-6F;
            s[0] = x;
            s[1] = y;
            s[5] = frame;
            float target = moving ? 1F : 0F;
            float k = (float)Math.min(1.0, dt / (moving ? 0.15 : 0.6));
            s[4] += (target - s[4]) * k;
            float sx = x - s[2], sy = y - s[3];
            if (o.trails && sx * sx + sy * sy > 0.15F * 0.15F && f.nStamps < MAX_STAMPS) {
               float dir = (float)Math.atan2(sy, sx);
               if (r > 0.5F) {
                  // a car: its two axles' wheel tracks (the body's direction)
                  float ca = (float)Math.cos(dir), sa = (float)Math.sin(dir);
                  for (int a = -1; a <= 1; a += 2) {
                     for (int b = -1; b <= 1; b += 2) {
                        if (f.nStamps < MAX_STAMPS) {
                           stamp(f, x + ca * 1.2F * a - sa * 0.8F * b, y + sa * 1.2F * a + ca * 0.8F * b, 0.38F, dir, 0);
                        }
                     }
                  }
               } else {
                  stamp(f, x, y, r, dir, 0);
               }
               s[2] = x;
               s[3] = y;
            }
            if (o.cut && obj instanceof zombie.characters.IsoGameCharacter ch && ch.isPerformingAttackAnimation() && f.nStamps < MAX_STAMPS - 3) {
               zombie.inventory.InventoryItem item = ch.getPrimaryHandItem();
               if (item instanceof zombie.inventory.types.HandWeapon hw && !hw.isRanged()) {
                  float ang = (float)Math.atan2(ch.getForwardDirectionY(), ch.getForwardDirectionX());
                  float range = Math.max(0.8F, Math.min(2.0F, hw.getMaxRange()));
                  for (int a = -1; a <= 1; a++) {
                     float aa = ang + a * 0.6F;
                     stamp(f, x + (float)Math.cos(aa) * range * 0.7F, y + (float)Math.sin(aa) * range * 0.7F, 0.45F, ang, 1);
                  }
               }
            }
         }
         // the push list: the nearest movers still bending the grass
         for (int n = 0; n < Sway.MAX_PUSH && o.push; n++) {
            float[] best = null;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < CAND.size(); i++) {
               float[] s = STATE.get(CAND.get(i));
               if (s == null || s[4] < 0.02F || s[6] < 0F) {
                  continue;
               }
               float d = (s[0] - cx) * (s[0] - cx) + (s[1] - cy) * (s[1] - cy);
               if (d < bd) {
                  bd = d;
                  best = s;
               }
            }
            if (best == null) {
               break;
            }
            int i = f.nPush++;
            float u = best[0] - best[1], v = best[0] + best[1];
            f.push[i * 4] = (float)(u - Sway.GUST_PERIOD_SQ * Math.floor(u / Sway.GUST_PERIOD_SQ));
            f.push[i * 4 + 1] = (float)(v - Sway.GUST_PERIOD_SQ * Math.floor(v / Sway.GUST_PERIOD_SQ));
            f.push[i * 4 + 2] = best[6] > 0.5F ? 1.8F : 0.7F;
            f.push[i * 4 + 3] = best[4];
            best[6] = -best[6];
         }
         for (float[] s : STATE.values()) {
            s[6] = Math.abs(s[6]);
         }
         if (frame % 120 == 0) {
            STATE.values().removeIf(s -> frame - s[5] > 600F);
         }
      }

      private static void add(Object obj, float x, float y, float z, float r, float cx, float cy, float reach2) {
         if (z > 0.5F || (x - cx) * (x - cx) + (y - cy) * (y - cy) > reach2) {
            return;
         }
         CAND.add(obj);
         CANDPOS.add(new float[] {x, y, r});
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

   /**
    * Generation: one workgroup per grass square, one lane per blade candidate. A blade record is two vec4: (x, y relative
    * to the origin square, height in squares, packed root colour), (lean x, lean y, width in squares, packed phase /
    * stiffness / facing).
    */
   static final String GEN = String.join("\n",
      "#version 430",
      "layout(local_size_x = " + LANES + ") in;",
      "layout(std430, binding = 0) readonly buffer Squares { uint sq[]; };",
      "struct Blade { vec4 a; vec4 b; };",
      "layout(std430, binding = 1) writeonly buffer Blades { Blade blades[]; };",
      "layout(std430, binding = 2) buffer Cmd { uint cCount; uint cInstances; uint cFirst; uint cBase; };",
      "uniform sampler2D uColor;",
      "uniform sampler2D uDepth;",
      "uniform vec4 uMapA;", // kA, cA, kB, cB: window px -> u, v (relative to the origin square)
      "uniform vec4 uMapB;", // kC, cC: depth -> w
      "uniform vec4 uVp;",
      "uniform ivec4 uOrigin;", // ox, oy, blades per square, clumps
      "uniform vec4 uParams;", // height scale, width scale
      "uniform vec4 uFade;", // player x, y (relative), radius, fade width (squares)
      "uniform float uHasDepth;",
      "uniform sampler2D uTrail;", // 256 x 256 RGBA32F, two texels a square, toroidal: trample time, cut time, key, direction
      "uniform vec4 uTrailK;", // now (s), 1 / trail recovery (s), 1 / regrow (s), on
      "shared uint sCount;",
      "shared uint sBase;",
      COMMON,
      "vec2 gbH2(uint s) { return vec2(gbH01(s), gbH01(s ^ 0x9e3779b9U)); }",
      "void main() {",
      "   uint lane = gl_LocalInvocationID.x;",
      "   if (lane == 0u) sCount = 0u;",
      "   barrier();",
      "   uint s = sq[gl_WorkGroupID.x];",
      "   int rx = int(s & 255u) - 128, ry = int((s >> 8) & 255u) - 128;",
      "   uint kind = (s >> 16) & 3u;",
      "   bool partial = ((s >> 18) & 1u) != 0u;",
      "   int X = uOrigin.x + rx, Y = uOrigin.y + ry;",
      "   bool keep = int(lane) < uOrigin.z;",
      // stratified in an 8 x 8 grid of the square (bit-reversed lane: any count spreads over the whole square)
      "   uint cell = bitfieldReverse(lane) >> 26;",
      "   uint seed = gbSeed(X, Y, lane);",
      "   vec2 j = gbH2(seed);",
      "   vec2 f = (vec2(float(cell & 7u), float(cell >> 3)) + j) / 8.0;",
      "   vec2 p = vec2(float(rx), float(ry)) + f;", // relative to the origin square
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
      "   float baseH = kind == 1u ? 0.16 : (kind == 2u ? 0.24 : 0.34);",
      "   float h = baseH * hmul * (0.6 + 0.6 * gbH01(seed ^ 0x2468u)) * uParams.x;",
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
      "   ivec2 ip = ivec2(clamp(px, uVp.xy, uVp.xy + uVp.zw - 1.0));",
      "   vec3 col = vec3(0.0);",
      "   if (keep) {",
      "      col = texelFetch(uColor, ip, 0).rgb;",
      "      if (uHasDepth > 0.5) {",
      "         float w = uMapB.x * texelFetch(uDepth, ip, 0).r + uMapB.y;",
      "         float wr = p.x + p.y;",
      "         if (w > wr + 0.35 || w < wr - 1.5) keep = false;", // covered (a post, a trunk, a bench) or no ground drawn there
      "      }",
      "      if (partial && !(col.g > col.b * 1.08 && col.g > col.r * 0.85)) keep = false;", // an overlay square: only over grass
      "   }",
      "   uint li = 0u;",
      "   if (keep) li = atomicAdd(sCount, 1u);",
      "   barrier();",
      "   if (lane == 0u) sBase = sCount > 0u ? atomicAdd(cInstances, sCount) : 0u;",
      "   barrier();",
      "   if (keep) {",
      "      float phase = gbH01(seed ^ 0x3333u);",
      "      float stiff = 0.7 + 0.3 * gbH01(seed ^ 0x4444u);",
      "      float facing = gbH01(seed ^ 0x6666u);",
      // the trail map at the root: flattened (lying along the walker's direction), cut (shorter, growing back)
      "      float flatK = 0.0, cutK = 1.0;",
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
      "      h *= cutK;",
      "      vec2 l = lean + (gbH2(seed ^ 0x7777u) - 0.5) * 0.3;",
      "      float width = (0.028 + 0.014 * gbH01(seed ^ 0x8888u)) * uParams.y;",
      "      blades[sBase + li] = Blade(vec4(p, h, uintBitsToFloat(packUnorm4x8(vec4(clamp(col * tone, 0.0, 1.0), flatK)))),",
      "         vec4(l, width, uintBitsToFloat(packUnorm4x8(vec4(phase, stiff, facing, 0.0)))));",
      "   }",
      "}");

   static final String VERT = String.join("\n",
      "#version 430",
      "struct Blade { vec4 a; vec4 b; };",
      "layout(std430, binding = 1) readonly buffer Blades { Blade blades[]; };",
      "uniform vec4 uMapA;",
      "uniform vec4 uMapB;",
      "uniform vec4 uVp;",
      "uniform vec4 uWind;", // foliage sway's: amplitude scale, lean, downwind direction, oscillation
      "uniform vec4 uPh;",
      "uniform vec4 uGust;",
      "uniform vec4 uPush[" + Sway.MAX_PUSH + "];",
      "uniform float uPushN;",
      "uniform vec4 uOriginW;", // the origin square's u, v modulo the gust period
      "uniform vec4 uShape;", // segments, width scale, dev view
      "uniform vec4 uSun;", // direction to the sun (x east, y south, z up), translucency
      "uniform vec4 uShade;", // ao, sheen
      "uniform float uTs;",
      "out vec3 vCol;",
      // foliage sway's gust ("sines") and its displacement pzSwDk, the same expressions (Sway.gustExpr / composite)
      "float gbGust(vec2 wp) { return 0.5 + 0.25 * (sin(dot(wp, vec2(0.625, 0.21875)) * 0.78539816 + uGust.x * 0.78539816) + sin(dot(wp, vec2(-0.28125, 0.71875)) * 0.78539816 + uGust.y * 0.78539816)); }",
      "float gbSwayX(float weight, float g, float phase) {",
      "   float amp = weight * " + Sway.AMP_MAX_PX + " * uWind.x;",
      "   float ph = uPh.x + phase * 6.2831853;",
      "   return amp * uWind.z * (uWind.y * (0.3 + 0.7 * g) + uWind.w * (0.45 + 0.55 * g) * sin(ph + g * 2.5));",
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
      "vec4 gbClip(vec3 q) {",
      "   float u = q.x - q.y, v = q.x + q.y - 2.0 * q.z, w = q.x + q.y + q.z * (2.0 / 3.0) + 0.03;",
      "   vec2 px = vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "   float d = (w - uMapB.y) / uMapB.x;",
      "   return vec4((px - uVp.xy) / uVp.zw * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);",
      "}",
      "vec2 gbPx(vec3 q) {",
      "   float u = q.x - q.y, v = q.x + q.y - 2.0 * q.z;",
      "   return vec2((u - uMapA.y) / uMapA.x, (v - uMapA.w) / uMapA.z);",
      "}",
      "void main() {",
      "   Blade bl = blades[gl_InstanceID];",
      "   vec3 root = vec3(bl.a.xy, 0.0);",
      "   float h = bl.a.z;",
      "   vec4 col = unpackUnorm4x8(floatBitsToUint(bl.a.w));",
      "   vec4 pk = unpackUnorm4x8(floatBitsToUint(bl.b.w));", // phase, stiffness, facing
      "   int segs = int(uShape.x);",
      "   int vid = gl_VertexID;",
      "   int seg = vid >> 1;",
      "   float t = vid >= 2 * segs ? 1.0 : float(seg) / float(segs);",
      "   float side = vid >= 2 * segs ? 0.0 : ((vid & 1) == 0 ? -1.0 : 1.0);",
      // wind: foliage sway's displacement of a grass texel at the blade's tip (logical px, screen x) -> world squares
      "   vec2 wp = mod(uOriginW.xy + vec2(root.x - root.y, root.x + root.y), " + Sway.GUST_PERIOD_SQ + ");",
      "   float g = gbGust(wp);",
      "   float weight = clamp(h / 0.75, 0.0, 1.0) * (0.75 + 0.25 * pk.y);",
      "   float dxPx = gbSwayX(weight, g, pk.x);",
      "   if (uPushN > 0.5) dxPx += weight * " + Sway.AMP_MAX_PX + " * 0.9 * gbPushX(wp);",
      "   float du = dxPx / (32.0 * uTs);", // logical px (zoom 1) -> u
      "   vec2 H = bl.b.xy * h + vec2(du * 0.5, -du * 0.5);",
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
      "   float halfW = 0.5 * bl.b.z * pxPerU * face * pow(it, 0.8);",
      "   if (side != 0.0) halfW = max(halfW, 0.45);",
      "   vec4 c = gbClip(pos);",
      "   c.xy += nrm * side * halfW / uVp.zw * 2.0;",
      "   gl_Position = c;",
      // shading: the painted root colour, darker toward the root, a little lighter toward the tip, sheen where the gust is
      // strong, translucent against a low sun behind it (the camera looks from +x +y)
      "   vec3 base = col.rgb;",
      "   float ao = mix(1.0 - 0.32 * uShade.x, 1.0, smoothstep(0.0, 0.7, t));",
      "   float sheen = uShade.y * 0.30 * g * g * t;",
      "   vec2 sunH = uSun.xy; float sl = length(sunH);",
      "   float back = sl > 1e-4 ? max(0.0, dot(sunH / sl, vec2(-0.7071, -0.7071))) : 0.0;",
      "   float low = uSun.z > 0.0 ? 1.0 - smoothstep(0.12, 0.55, uSun.z) : 0.0;",
      "   float tr = uSun.w * back * low * t * t;",
      "   vec3 c3 = base * ao * (1.0 + 0.10 * t + sheen + 0.16 * flatK * t) + base * vec3(0.75, 0.62, 0.18) * tr;",
      "   if (uShape.z > 0.5) c3 = mix(vec3(1.0, 0.0, 0.0), vec3(1.0, 1.0, 0.0), t);",
      "   vCol = c3;",
      "}");

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
