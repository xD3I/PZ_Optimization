package pzopt;

import org.lwjgl.opengl.GL11;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.TextureDraw;

/**
 * The horde's 3D character models on the render thread (Louisville 120 goal, 2026-10-13).
 *
 * <p>Dev rig {@code devHordeModes} (measurement only, the picture changes in some modes): a string of mode digits cycled
 * per frame over the world pass's moving objects. The {@code moving} GPU section is named {@code moving.m<d>}, so one run
 * gives each mode's GPU and render-thread time in the same seconds:
 * <ul>
 * <li>0 stock;</li>
 * <li>1 every character mesh draw call skipped (uniforms, state and binds still issued): the vertex / pixel work;</li>
 * <li>2 the bone palette uploaded twice per mesh draw: the cost of one upload;</li>
 * <li>3 one extra glPushAttrib(all) / glPushClientAttrib(all) / pops right before each character's draw: the cost of one pair;</li>
 * <li>4 the character model draws skipped entirely (vehicles still drawn): what is left of the pass;</li>
 * <li>5 / 6 hordeBatch exact / defer for the frame (pzopt.HordeBatch), whatever the key says; 7 defer without the draw
 * calls, 8 defer with one texture and one mesh for the whole run (no state change between draws: what multi-draw could reach) (the costs of the vertex work and of the binds), 9 defer with the records sent
 * by glBufferSubData instead of the persistent mapping;</li>
 * <li>letters (horde round 2): a = 10 exact with the atlas zombies' sprites (DeadBodyAtlas$BodyTextureDepthDrawer) not
 * drawn, b = 11 exact without the batch's draw calls, c = 12 exact with neither the atlas sprites, the batch's draw calls
 * nor any stock character model draw (the pass's floor), d = 13 exact + atlasBatch (pzopt.AtlasBatch), e = 14 exact + atlasBatch + hordeBatchOrder=window, f = 15 as e with one texture a batch run (wrong picture: the texture binds' cost), g = 16 as e with hordeBatchUnits' texture chunks (needs hordeBatchUnits > 0).</li>
 * </ul>
 * {@code devHordeLog}: one line per frame in {@code pzopt-horde.out} (render thread): mode, characters, mesh draws,
 * indices, palette floats, render-thread ns inside the character draws.
 */
public final class HordeDraw {
   private static final String MODES = Config.DEV_HORDE_MODES;
   private static final boolean ON = !MODES.isEmpty();
   public static final boolean LOG = Config.DEV_HORDE_LOG;

   private HordeDraw() {
   }

   // ---- game thread ----
   private static int gtFrame;
   private static int gtMode;

   /** Game thread, before the moving objects' GPU section begins: picks this frame's mode and queues it in stream order. */
   public static void beginMoving() {
      if (!(ON || LOG || MovingProf.ON) || !Overrides.enabled()) {
         return;
      }
      char ch = ON ? MODES.charAt(Math.floorMod(gtFrame++, MODES.length())) : '0';
      gtMode = ch >= 'a' && ch <= 'z' ? 10 + (ch - 'a') : ch - '0';
      SpriteRenderer.instance.drawGeneric(Setter.get(gtMode, false));
   }

   /** Game thread, after the moving objects' GPU section ends. */
   public static void endMoving() {
      if (!(ON || LOG || MovingProf.ON) || !Overrides.enabled()) {
         return;
      }
      SpriteRenderer.instance.drawGeneric(Setter.get(0, true));
   }

   /** The moving section's name with this frame's mode (unchanged without the rig). */
   public static String section(String base) {
      if (!ON || !Overrides.enabled()) {
         return base;
      }
      return base + ".m" + gtMode;
   }

   // ---- render thread ----
   /** The render thread's current mode (0 outside the moving pass). */
   public static int mode;
   private static int chars;
   private static int draws;
   private static long indices;
   private static long paletteFloats;
   private static long charNs;
   static int frameMode; // (FrameCapture tags captured frames with it)
   private static int runs;
   private static int maxRun;
   private static int curRun;
   private static int others;
   private static boolean otherSince = true;

   /** Render thread (dev log): a command or sprite batch that is not a character draw. */
   public static void other() {
      others++;
      otherSince = true;
   }
   private static java.io.BufferedWriter log;
   private static boolean logFailed;

   /** A generic drawer that issues no GL state (pzopt.HordeBatch lets it inside a run). */
   public interface Neutral {
   }

   private static final class Setter extends TextureDraw.GenericDrawer implements Neutral {
      private static final java.util.ArrayList<Setter> POOL = new java.util.ArrayList<>();
      int value;
      boolean end;

      static Setter get(int value, boolean end) {
         Setter s;
         synchronized (POOL) {
            s = POOL.isEmpty() ? new Setter() : POOL.remove(POOL.size() - 1);
         }
         s.value = value;
         s.end = end;
         return s;
      }

      @Override
      public void render() {
         if (this.end) {
            MovingProf.frameEnd();
            flushFrame();
            mode = 0;
         } else {
            mode = this.value;
            frameMode = this.value;
            MovingProf.frameBegin();
            chars = 0;
            draws = 0;
            indices = 0L;
            paletteFloats = 0L;
            charNs = 0L;
            runs = 0;
            maxRun = 0;
            curRun = 0;
            others = 0;
            otherSince = true;
         }
      }

      @Override
      public void postRender() {
         synchronized (POOL) {
            POOL.add(this);
         }
      }
   }

   /**
    * Render thread, TextureDraw's DrawModel before the drawer renders. Returns false when the draw is skipped (mode 4,
    * characters only). Mode 3 issues one extra attribute push / pop pair.
    */
   public static boolean modelBegin(TextureDraw.GenericDrawer drawer) {
      if (HordeStateDump.ON) {
         HordeStateDump.stockBefore(drawer); // (dev: the GL state before / after a stock zombie draw)
      }
      if (mode == 0 && !LOG) {
         return true;
      }
      if (!(drawer instanceof ModelSlotRenderData slot) || slot.character == null) {
         other();
         return !((mode == 10 || mode == 12) && isAtlasDrawer(drawer)); // modes a / c: the atlas zombies' sprites not drawn
      }
      chars++;
      if (otherSince) {
         runs++;
         curRun = 1;
         otherSince = false;
      } else {
         curRun++;
      }
      if (curRun > maxRun) {
         maxRun = curRun;
      }
      if (mode == 4 || mode == 12) {
         return false;
      }
      if (mode == 3) {
         GL11.glPushClientAttrib(-1);
         GL11.glPushAttrib(1048575);
         GL11.glPopAttrib();
         GL11.glPopClientAttrib();
      }
      t0 = System.nanoTime();
      return true;
   }

   private static long t0;
   private static Class<?> atlasDrawerClass;

   private static boolean isAtlasDrawer(Object d) {
      Class<?> c = d.getClass();
      if (c == atlasDrawerClass) {
         return true;
      }
      if (atlasDrawerClass == null && c.getName().equals("zombie.core.skinnedmodel.DeadBodyAtlas$BodyTextureDepthDrawer")) {
         atlasDrawerClass = c;
         return true;
      }
      return false;
   }

   /** Render thread, after the drawer rendered (paired with a true {@link #modelBegin}). */
   public static void modelEnd(TextureDraw.GenericDrawer drawer) {
      if (HordeStateDump.ON) {
         HordeStateDump.stockAfter();
      }
      if (t0 != 0L) {
         charNs += System.nanoTime() - t0;
         t0 = 0L;
      }
   }

   /** Render thread, Model.DrawSolid of a character mesh: false = skip the draw call (mode 1). */
   public static boolean meshDraw(int numElements, java.nio.FloatBuffer palette) {
      draws++;
      indices += numElements;
      if (palette != null) {
         paletteFloats += palette.limit();
      }
      return mode != 1;
   }

   /** Mode 2: upload the palette a second time. */
   public static boolean extraPalette() {
      return mode == 2;
   }

   private static void flushFrame() {
      if (!LOG || logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = zombie.ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, "pzopt-horde.out"), false), 1 << 16);
            log.write("# nano mode chars draws indices palette_floats char_ns runs max_run others (render thread, per frame's moving pass; runs = groups of character draws with no other command or sprite batch between them)\n");
         }
         log.write(System.nanoTime() + " " + frameMode + " " + chars + " " + draws + " " + indices + " " + paletteFloats + " " + charNs + " " + runs + " " + maxRun + " " + others + "\n");
         if (++flushCount % 240 == 0) {
            log.flush();
         }
      } catch (java.io.IOException | RuntimeException e) {
         logFailed = true;
      }
   }

   private static int flushCount;
}
