package pzopt;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Occluded zombie outlines (PR #48 by novakovicdavid, reworked 2026-10-03 to cost next to nothing): the parts of a
 * zombie the player sees that walls, trees, cars or other scenery hide from the camera get a thin contour.
 *
 * <p>Everything rides the world framebuffer's stencil (bits 0x7F; bit 0x80 is the game's player-mask bit) during the
 * draws the game already makes, so no scene is drawn twice, no material shader is patched and nothing is copied:
 * <ul>
 *   <li>an eligible zombie's own mesh draws write {@code S|V|b} where they pass the depth test (S = silhouette,
 *       V = visible, b = 4-bit brightness);</li>
 *   <li>right after each of its meshes the same mesh is drawn once more with every state still bound, colour and depth
 *       writes off and the depth test inverted: where something nearer is already in the depth buffer and no character
 *       is visible ({@code V} clear) it writes {@code S|b}, the hidden part;</li>
 *   <li>other characters (the player, animals, zombies not seen) write {@code V} only, so they neither occlude nor
 *       get outlined;</li>
 *   <li>vehicles clear {@code V} where they draw: a zombie drawn before the car in front of it becomes hidden there;</li>
 *   <li>atlas zombies (the far crowd, one textured quad each) mark {@code S|V|b} in their own draw; their hidden part
 *       is one quad each in a single pass at the end of the moving objects (the quads only need the final depth).</li>
 * </ul>
 * At the end of the world's moving objects one stencil-tested screen pass draws the contour: hidden pixels ({@code S}
 * without {@code V}) with a pixel outside every silhouette within the width. The stencil test rejects every other pixel
 * before shading, so the pass costs in proportion to the hidden pixels.
 *
 * <p>Zombies standing in grass or bushes with nothing solid in front get a depth slack of about two squares in their
 * hidden test ({@code occludedOutlineIgnorePlants}): the plants on their own and the next squares do not outline their
 * legs, a wall or a car further away still does.
 */
public final class OccludedOutline {
   private OccludedOutline() {
   }

   static final int S = 0x40, V = 0x20, B = 0x0F, CODE = 0x7F;
   /** Depth of one square step along x or y (IsoDepthHelper.calculateDepth: 0.023093667 / 16). */
   static final float SQUARE_DEPTH = 0.023093667F / 16.0F;
   /** Hidden only beyond this much nearer depth: DEPTH16 chunk depth composited into D24 rounds by up to 1.5e-5. */
   static final float BASE_SLACK = 4.0e-5F;
   /** In grass / bushes: the plants of the zombie's square and the next ones toward the camera. */
   static final float PLANT_SLACK = 2.5F * SQUARE_DEPTH;
   private static final float D24_UNIT = 1.0F / (1 << 24);

   private static String colourSpec;
   private static int colourRgb = 0xFFC740;

   private static volatile boolean failed;
   private static boolean glChecked, glOk;
   // render thread, between begin and finish of one player's world pass
   private static boolean active;

   /** Whether the outline stencil pass is on this frame (pzopt.HordeBatch keeps such model draws on the stock path). */
   public static boolean isActive() {
      return active;
   }
   private static boolean marked; // an eligible zombie drew a hidden-part pass (or queued its quad) this frame
   private static int drawCode, drawMask, hiddenCode;
   private static float hiddenUnits;
   private static boolean vehicleDraw;
   // atlas zombies waiting for their hidden-part quad
   private static int atlasCount;
   private static float[] atlasData = new float[64 * 14];
   private static int[] atlasTex = new int[64 * 3];
   private static final float[] matrix = new float[16];
   private static final Matrix4f tmp = new Matrix4f();
   private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
   private static int atlasProgram, compositeProgram, vao;
   private static int uAtlasMatrix, uAtlasRect, uAtlasUv, uAtlasDepth, uRadius, uColour, uView;
   // dev statistics (devOutlineTiming) and the within-run A/B (devOutlineAlternate)
   private static long frames, framesOn, hiddenMeshes, atlasQuads, finishNs, frameNsOn, frameNsOff, framesTimedOn, framesTimedOff, lastBegin, lastLog;
   private static boolean phaseOn = true;
   private static long phaseStart;

   /** The keys that switch it on (FBO creation may run on the game thread: no GL here). */
   public static boolean depthTextureNeeded() {
      return Config.OCCLUDED_OUTLINES && Overrides.enabled() && !Core.getInstance().getUseOpenGL21();
   }

   static boolean enabled() {
      return Config.OCCLUDED_OUTLINES && Config.ENHANCEMENTS_ENABLED && Overrides.enabled() && !failed;
   }

   /**
    * Game thread: 0 = no outline, else {@link #OUTLINE}, {@link #OUTLINE_PLANTS} or {@link #CLEAR}. The player's current sight of the zombie
    * (never a loaded, remembered or nearby one); carried corpses are temporarily reanimated zombies and stay out.
    */
   public static int eligible(IsoGameCharacter character, int player) {
      if (!enabled()
         || !(character instanceof IsoZombie z)
         || z.isReanimatedForGrappleOnly()
         || character.isDead()
         || player < 0
         || player >= character.isVisibleToPlayer.length
         || !character.isVisibleToPlayer[player]
         || character.getAlpha(player) < 0.01F) {
         return 0;
      }
      return classify(z);
   }

   /** eligible(): outline, outline with the plant slack, seen but nothing in front can hide it (no hidden-part draw). */
   static final int OUTLINE = 1, OUTLINE_PLANTS = 2, CLEAR = 3;
   private static final long CLASS_TTL_NS = 1_000_000_000L;
   private static final int NEAR_ROWS = 4, NEAR_COLUMNS = 2, TREE_ROWS = 9, COLUMNS = 3, LEVELS = 6;
   private static int vehicleFrame = -1, vehicleCount;
   private static float[] vehicleXy = new float[32];

   /**
    * Game thread: what could hide the zombie from the camera. The camera looks from +x+y; a square (a, b, level) is drawn
    * a + b - 3 * level diagonal rows down the screen (a level is three rows) and a - b columns across, and its objects reach
    * up to three levels above their base (trees). So nothing outside the band in front of the zombie (a + b from x + y on,
    * |(a - b) - (x - y)| <= 3, its level and the next ones up to their own reach) can cover it: when every square there
    * holds floors only (and no car is near) the zombie is CLEAR and skips its hidden-part draw. Grass and bushes in the
    * first two rows on its own level fall inside the plant slack; something solid there (a wall, a fence, furniture, a
    * tree, a car) means the full test. Cached per zombie for its square, refreshed every second.
    */
   private static int classify(IsoZombie z) {
      IsoGridSquare sq = z.getCurrentSquare();
      if (sq == null) {
         return OUTLINE;
      }
      long now = System.nanoTime();
      int cls;
      if (z.pzoptOutlineSquare == sq && now - z.pzoptOutlineNs < CLASS_TTL_NS) {
         cls = z.pzoptOutlineClass;
      } else {
         cls = scan(sq);
         z.pzoptOutlineSquare = sq;
         z.pzoptOutlineNs = now;
         z.pzoptOutlineClass = (byte)cls;
      }
      if (cls != OUTLINE && vehicleNear(z.getX(), z.getY())) {
         return OUTLINE; // cars move: checked every frame, never cached
      }
      if (cls == OUTLINE_PLANTS && !Config.OCCLUDED_OUTLINE_IGNORE_PLANTS) {
         return OUTLINE;
      }
      return cls;
   }

   private static int scan(IsoGridSquare sq) {
      IsoCell cell = IsoWorld.instance.getCell();
      int x = sq.x, y = sq.y, z = sq.z;
      boolean solidNear = false, plantNear = false, beyond = false;
      // the zombie's level: anything standing up to NEAR_ROWS rows in front (one level covers three rows), trees (up to
      // three levels tall, crowns about three columns wide) up to TREE_ROWS
      for (int r = 0; r <= TREE_ROWS && !solidNear; r++) {
         for (int c = -COLUMNS; c <= COLUMNS; c++) {
            if (((r + c) & 1) != 0) {
               continue; // a + b and a - b share their parity
            }
            IsoGridSquare s = cell.getGridSquare(x + (r + c) / 2, y + (r - c) / 2, z);
            if (s == null) {
               continue;
            }
            boolean near = r <= NEAR_ROWS && c >= -NEAR_COLUMNS && c <= NEAR_COLUMNS;
            int what = objects(s, near);
            if (what == 0) {
               continue;
            }
            if (r <= 2 && what == 1) {
               plantNear = true;
            } else if (r <= 2) {
               solidNear = true;
            } else {
               beyond = true;
            }
         }
      }
      // the levels above: any square there (a floor, a roof, a wall) drawn over the zombie's rows
      for (int dz = 1; dz < LEVELS && !beyond && !solidNear; dz++) {
         boolean any = false;
         for (int r = 3 * dz - 4; r <= 3 * dz + NEAR_ROWS && !beyond; r++) {
            for (int c = -COLUMNS; c <= COLUMNS; c++) {
               if (((r + c) & 1) != 0) {
                  continue;
               }
               IsoGridSquare s = cell.getGridSquare(x + (r + c) / 2, y + (r - c) / 2, z + dz);
               if (s != null) {
                  any = true;
                  if (objects(s, true) != 0 || s.getFloor() != null) {
                     beyond = true;
                     break;
                  }
               }
            }
         }
         if (!any) {
            break; // an empty level: nothing higher stands here
         }
      }
      if (solidNear) {
         return OUTLINE;
      }
      if (plantNear) {
         return OUTLINE_PLANTS;
      }
      return beyond ? OUTLINE : CLEAR;
   }

   /** 0 = floors only (or, not near, nothing as tall as a tree), 1 = grass / bushes, 2 = anything else that stands up. */
   private static int objects(IsoGridSquare s, boolean near) {
      zombie.util.list.PZArrayList<IsoObject> objects = s.getObjects();
      int what = 0;
      for (int j = 0; j < objects.size(); j++) {
         IsoObject o = objects.get(j);
         if (o == null || o.sprite == null || o instanceof zombie.iso.objects.IsoWorldInventoryObject) {
            continue;
         }
         zombie.iso.SpriteDetails.IsoFlagType[] flat = FLAT;
         boolean isFlat = false;
         for (zombie.iso.SpriteDetails.IsoFlagType t : flat) {
            if (o.sprite.getProperties().has(t)) {
               isFlat = true;
               break;
            }
         }
         if (isFlat) {
            continue;
         }
         boolean tree = o instanceof zombie.iso.objects.IsoTree || o.sprite.getType() == zombie.iso.SpriteDetails.IsoObjectType.tree;
         if (!near && !tree) {
            continue;
         }
         if (!tree && (o.sprite.isBush || o.sprite.canBeRemoved)) {
            what = 1;
         } else {
            return 2;
         }
      }
      return what;
   }

   private static final zombie.iso.SpriteDetails.IsoFlagType[] FLAT = {zombie.iso.SpriteDetails.IsoFlagType.solidfloor,
      zombie.iso.SpriteDetails.IsoFlagType.FloorOverlay, zombie.iso.SpriteDetails.IsoFlagType.attachedFloor,
      zombie.iso.SpriteDetails.IsoFlagType.IsFloorAttached};

   /** A car within 8 squares (its body can hide the zombie whichever side it is drawn from). */
   private static boolean vehicleNear(float x, float y) {
      int frame = IsoWorld.instance.getFrameNo();
      if (frame != vehicleFrame) {
         vehicleFrame = frame;
         vehicleCount = 0;
         for (zombie.vehicles.BaseVehicle v : IsoWorld.instance.getCell().getVehicles()) {
            if (vehicleCount * 2 + 2 > vehicleXy.length) {
               vehicleXy = java.util.Arrays.copyOf(vehicleXy, vehicleXy.length * 2);
            }
            vehicleXy[vehicleCount * 2] = v.getX();
            vehicleXy[vehicleCount * 2 + 1] = v.getY();
            vehicleCount++;
         }
      }
      for (int i = 0; i < vehicleCount; i++) {
         if (Math.abs(vehicleXy[i * 2] - x) + Math.abs(vehicleXy[i * 2 + 1] - y) < 8.0F) {
            return true;
         }
      }
      return false;
   }

   /** Game thread: after the chunk composite, before players and moving objects. */
   public static void begin(int player) {
      if (Config.OCCLUDED_OUTLINES && Overrides.enabled()) {
         SpriteRenderer.instance.drawGeneric(BEGIN);
      }
   }

   /** Game thread: after the moving and translucent objects, before the fog. */
   public static void finish(int player) {
      if (Config.OCCLUDED_OUTLINES && Overrides.enabled()) {
         SpriteRenderer.instance.drawGeneric(FINISH);
      }
   }

   private static final TextureDraw.GenericDrawer BEGIN = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         beginRender();
      }
   };
   private static final TextureDraw.GenericDrawer FINISH = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         finishRender();
      }
   };

   private static void beginRender() {
      active = false;
      marked = false;
      atlasCount = 0;
      drawCode = hiddenCode = 0;
      vehicleDraw = false;
      long now = System.nanoTime();
      if (Config.DEV_OUTLINE_TIMING && lastBegin != 0) {
         long dt = now - lastBegin;
         if (dt < 250_000_000L) {
            if (phaseOn) {
               frameNsOn += dt;
               framesTimedOn++;
            } else {
               frameNsOff += dt;
               framesTimedOff++;
            }
         }
      }
      lastBegin = now;
      if (Config.DEV_OUTLINE_ALTERNATE > 0 && now - phaseStart > Config.DEV_OUTLINE_ALTERNATE * 1_000_000L) {
         phaseStart = now;
         phaseOn = !phaseOn;
         Log.info("occluded outlines: phase " + (phaseOn ? "on" : "off") + " at " + System.currentTimeMillis()); // the video / A/B rigs pair frames by phase
      }
      if (!enabled() || !phaseOn && Config.DEV_OUTLINE_ALTERNATE > 0 || ObjectMotion.enabled()) {
         return; // a temporal upscaler's object motion owns the stencil's low bits
      }
      if (!glChecked) {
         glChecked = true;
         glOk = GL.getCapabilities().OpenGL43 && !Core.getInstance().getUseOpenGL21();
         if (!glOk) {
            Log.warn("occluded outlines: off (needs OpenGL 4.3)");
         }
      }
      active = glOk;
   }

   /**
    * Render thread, TextureDraw DrawModel before the model's draw: sets the stencil codes the model's meshes write.
    * True when {@link #endModel} must follow.
    */
   public static boolean beginModel(TextureDraw draw) {
      if (!active || !(draw.drawer instanceof ModelSlotRenderData slot) || slot.renderToTexture || slot.IsRenderingToCard()) {
         return false;
      }
      if (slot.character != null) {
         if (draw.pzoptOutline != 0 && !slot.inVehicle) {
            float light = 0.2126F * slot.ambientR + 0.7152F * slot.ambientG + 0.0722F * slot.ambientB;
            int b = Math.round(Math.max(0.0F, Math.min(1.0F, light * slot.alpha)) * 15.0F);
            drawCode = S | V | b;
            drawMask = CODE;
            hiddenCode = draw.pzoptOutline == CLEAR ? 0 : S | b; // nothing in front can hide it: its marks only
            hiddenUnits = (draw.pzoptOutline == OUTLINE_PLANTS ? PLANT_SLACK : BASE_SLACK) / D24_UNIT;
         } else {
            drawCode = V;
            drawMask = V;
            hiddenCode = 0;
         }
         return true;
      }
      if (slot.object instanceof zombie.vehicles.BaseVehicle) {
         // a car in front of a zombie drawn before it: the zombie's visible pixels there become hidden
         GL11.glEnable(GL11.GL_STENCIL_TEST);
         GL11.glStencilMask(V);
         GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0);
         GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_ZERO);
         vehicleDraw = true;
         return true;
      }
      return false;
   }

   /** Render thread, after the model's draw. */
   public static void endModel() {
      drawCode = hiddenCode = 0;
      if (vehicleDraw) {
         vehicleDraw = false;
         GLStateRenderThread.StencilFunc.restore();
         GLStateRenderThread.StencilOp.restore();
         GLStateRenderThread.StencilMask.restore();
         GLStateRenderThread.StencilTest.restore();
      }
   }

   /**
    * Render thread, Model.DrawSolid in place of {@code mesh.Draw(effect)} while a character's stencil codes are set
    * (false: the caller draws as stock). The stock draw's own steps (VertexBufferObject.BeginDraw, the elements,
    * FinishDraw) with the stencil code written where the mesh passes the depth test, and in between, every buffer and
    * uniform still bound, the elements once more with colour and depth writes off and the depth test inverted where
    * no character is visible: the hidden part. Model.DrawChar's GLStateRenderThread.restore() puts the depth, colour and
    * stencil state back after each mesh, as it does for the stock draw.
    */
   public static boolean drawMesh(zombie.core.skinnedmodel.model.ModelMesh mesh, zombie.core.skinnedmodel.shader.Shader effect) {
      if (drawCode == 0 || mesh.vb == null) {
         return false;
      }
      int blend = mesh.vb.BeginInstancedDraw(effect); // -1: nothing to draw (stock Draw does nothing either)
      if (blend < 0) {
         return true;
      }
      GL11.glEnable(GL11.GL_STENCIL_TEST);
      GL11.glStencilMask(drawMask);
      GL11.glStencilFunc(GL11.GL_ALWAYS, drawCode, CODE);
      GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
      mesh.vb.PushDrawCall();
      if (hiddenCode != 0) {
         GL11.glStencilFunc(GL11.GL_EQUAL, hiddenCode, V);
         GL11.glDepthFunc(GL11.GL_GREATER);
         GL11.glDepthMask(false);
         GL11.glColorMask(false, false, false, false);
         GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
         GL11.glPolygonOffset(0.0F, -hiddenUnits);
         mesh.vb.PushDrawCall();
         GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
         hiddenMeshes++;
         marked = true;
      }
      mesh.vb.FinishInstancedDraw(effect, blend == 1);
      return true;
   }

   /**
    * Render thread, an eligible atlas zombie's draw (DeadBodyAtlas.BodyTextureDepthDrawer) before its quad flushes:
    * the quad writes {@code S|V|b}, and the quad is kept for the hidden-part pass. The drawer restores the stencil.
    */
   public static void atlas(int mode, Texture texture, Texture depth, float r, float g, float b, float a, float x, float y, float w, float h,
      float near, float far) {
      if (!active || mode == 0) {
         return;
      }
      float light = 0.2126F * r + 0.7152F * g + 0.0722F * b;
      int code = Math.round(Math.max(0.0F, Math.min(1.0F, light * a)) * 15.0F);
      GL11.glEnable(GL11.GL_STENCIL_TEST);
      GL11.glStencilMask(CODE);
      GL11.glStencilFunc(GL11.GL_ALWAYS, S | V | code, CODE);
      GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
      if (mode == CLEAR) {
         return; // nothing in front can hide it: its marks only
      }
      marked = true;
      if (atlasCount * 14 + 14 > atlasData.length) {
         atlasData = java.util.Arrays.copyOf(atlasData, atlasData.length * 2);
         atlasTex = java.util.Arrays.copyOf(atlasTex, atlasTex.length * 2);
      }
      int o = atlasCount * 14;
      atlasData[o] = x;
      atlasData[o + 1] = y;
      atlasData[o + 2] = w;
      atlasData[o + 3] = h;
      atlasData[o + 4] = texture.getXStart();
      atlasData[o + 5] = texture.getYStart();
      atlasData[o + 6] = texture.getXEnd();
      atlasData[o + 7] = texture.getYEnd();
      atlasData[o + 8] = near;
      atlasData[o + 9] = far;
      atlasData[o + 10] = mode == OUTLINE_PLANTS ? PLANT_SLACK : BASE_SLACK;
      int t = atlasCount * 3;
      atlasTex[t] = texture.getID();
      atlasTex[t + 1] = depth.getID();
      atlasTex[t + 2] = S | code;
      if (atlasCount == 0) {
         tmp.set(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek()).get(matrix);
      }
      atlasCount++;
   }

   private static void finishRender() {
      boolean run = active && marked;
      active = false;
      drawCode = hiddenCode = 0;
      if (!run) {
         atlasCount = 0;
         frames++;
         return;
      }
      long t0 = System.nanoTime();
      GpuSections.markNow("outline", false);
      try {
         int fbo = ShadowAtlas.worldFbo();
         int stencilTex = FogPass.sceneDepthTexture(fbo);
         if (stencilTex == 0) {
            return; // the scene keeps a depth renderbuffer (fogPass off since the window opened): nothing to read
         }
         if (compositeProgram == 0 && !initPrograms()) {
            fail("shader link");
            return;
         }
         GL30.glBindVertexArray(vao);
         GL11.glDisable(GL11.GL_CULL_FACE);
         GL11.glDisable(GL11.GL_BLEND);
         if (atlasCount > 0) {
            drawAtlasHidden();
         }
         // the contour: hidden pixels (S without V) with a pixel outside every silhouette within the width
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL33.glBindSampler(1, 0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, stencilTex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_STENCIL_INDEX);
         GL20.glUseProgram(compositeProgram);
         int colour = currentColour();
         float opacity = Math.max(0, Math.min(100, Config.OCCLUDED_OUTLINE_OPACITY)) / 100.0F;
         GL20.glUniform4f(uColour, ((colour >> 16) & 255) / 255.0F, ((colour >> 8) & 255) / 255.0F, (colour & 255) / 255.0F, opacity);
         GL20.glUniform1i(uRadius, Math.max(1, Math.min(4, Config.OCCLUDED_OUTLINE_WIDTH)));
         GL20.glUniform1i(uView, Config.DEV_OUTLINE_VIEW);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glColorMask(true, true, true, true);
         for (int i = 1; i < 8; i++) {
            GL30.glColorMaski(i, false, false, false, false); // HDR / sway auxiliary targets keep their values
         }
         GL11.glEnable(GL11.GL_STENCIL_TEST);
         GL11.glStencilMask(0);
         if (Config.DEV_OUTLINE_VIEW != 0) {
            GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, S | V); // dev view: every marked pixel
         } else {
            GL11.glStencilFunc(GL11.GL_EQUAL, S, S | V);
         }
         GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
         GL11.glEnable(GL11.GL_BLEND);
         GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_DEPTH_COMPONENT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         framesOn++;
      } catch (RuntimeException e) {
         fail(e.toString());
      } finally {
         // back to the sprite renderer's state
         GL20.glUseProgram(0);
         GL30.glBindVertexArray(0); // the game draws with VAO 0
         GL11.glColorMask(true, true, true, true);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         Texture.lastTextureID = -1;
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         GpuSections.markNow("outline", true);
         atlasCount = 0;
         frames++;
         finishNs += System.nanoTime() - t0;
         devLog();
      }
   }

   /** The far crowd's hidden parts: each kept quad again against the final depth, writing S|b where no character shows. */
   private static void drawAtlasHidden() {
      GL20.glUseProgram(atlasProgram);
      matrixBuffer.clear();
      matrixBuffer.put(matrix).flip();
      GL20.glUniformMatrix4fv(uAtlasMatrix, false, matrixBuffer);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDepthFunc(GL11.GL_GREATER);
      GL11.glDepthMask(false);
      GL11.glColorMask(false, false, false, false);
      GL11.glEnable(GL11.GL_STENCIL_TEST);
      GL11.glStencilMask(CODE);
      GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
      int lastTex = -1, lastDepth = -1, lastCode = -1;
      for (int i = 0; i < atlasCount; i++) {
         int t = i * 3, o = i * 14;
         if (atlasTex[t] != lastTex) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lastTex = atlasTex[t]);
         }
         if (atlasTex[t + 1] != lastDepth) {
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lastDepth = atlasTex[t + 1]);
         }
         if (atlasTex[t + 2] != lastCode) {
            GL11.glStencilFunc(GL11.GL_EQUAL, lastCode = atlasTex[t + 2], V);
         }
         GL20.glUniform4f(uAtlasRect, atlasData[o], atlasData[o + 1], atlasData[o + 2], atlasData[o + 3]);
         GL20.glUniform4f(uAtlasUv, atlasData[o + 4], atlasData[o + 5], atlasData[o + 6], atlasData[o + 7]);
         GL20.glUniform3f(uAtlasDepth, atlasData[o + 8], atlasData[o + 9], atlasData[o + 10]);
         GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      atlasQuads += atlasCount;
   }

   private static void fail(String why) {
      if (!failed) {
         Log.warn("occluded outlines: off (" + why + ")");
      }
      failed = true;
   }

   private static void devLog() {
      if (!Config.DEV_OUTLINE_TIMING) {
         return;
      }
      long now = System.nanoTime();
      if (now - lastLog < 10_000_000_000L) {
         return;
      }
      lastLog = now;
      Log.info(String.format(java.util.Locale.ROOT,
         "occluded outlines: frames=%d drawn=%d hidden meshes/frame=%.1f atlas quads/frame=%.1f finish us/frame=%.1f | frame ms on=%.3f (%d) off=%.3f (%d)",
         frames, framesOn, framesOn == 0 ? 0.0 : hiddenMeshes / (double)framesOn, framesOn == 0 ? 0.0 : atlasQuads / (double)framesOn,
         framesOn == 0 ? 0.0 : finishNs / 1e3 / framesOn, framesTimedOn == 0 ? 0.0 : frameNsOn / 1e6 / framesTimedOn, framesTimedOn,
         framesTimedOff == 0 ? 0.0 : frameNsOff / 1e6 / framesTimedOff, framesTimedOff));
      frames = framesOn = hiddenMeshes = atlasQuads = finishNs = frameNsOn = frameNsOff = framesTimedOn = framesTimedOff = 0;
   }

   /** Render thread: the live setting, decoded only when Apply changes it. */
   static int currentColour() {
      String value = Config.OCCLUDED_OUTLINE_COLOUR;
      if (!java.util.Objects.equals(value, colourSpec)) {
         colourRgb = parseColour(value);
         colourSpec = value;
      }
      return colourRgb;
   }

   /** Picker RGB (RRGGBB or #RRGGBB); anything else is the default amber. */
   static int parseColour(String value) {
      if (value == null) {
         return 0xFFC740;
      }
      String text = value.trim().toLowerCase(java.util.Locale.ROOT);
      if (text.startsWith("#")) {
         text = text.substring(1);
      }
      return text.matches("[0-9a-f]{6}") ? Integer.parseInt(text, 16) : 0xFFC740;
   }

   private static boolean initPrograms() {
      atlasProgram = Shaders.program("occluded outline atlas", ATLAS_VERT, ATLAS_FRAG);
      compositeProgram = Shaders.program("occluded outline", COMPOSITE_VERT, COMPOSITE_FRAG);
      if (atlasProgram == 0 || compositeProgram == 0) {
         return false;
      }
      vao = GL30.glGenVertexArrays();
      GL20.glUseProgram(atlasProgram);
      GL20.glUniform1i(GL20.glGetUniformLocation(atlasProgram, "diffuse"), 0);
      GL20.glUniform1i(GL20.glGetUniformLocation(atlasProgram, "depth"), 1);
      uAtlasMatrix = GL20.glGetUniformLocation(atlasProgram, "matrix");
      uAtlasRect = GL20.glGetUniformLocation(atlasProgram, "rect");
      uAtlasUv = GL20.glGetUniformLocation(atlasProgram, "uvRect");
      uAtlasDepth = GL20.glGetUniformLocation(atlasProgram, "depthRange");
      GL20.glUseProgram(compositeProgram);
      GL20.glUniform1i(GL20.glGetUniformLocation(compositeProgram, "stencil"), 1);
      uRadius = GL20.glGetUniformLocation(compositeProgram, "radius");
      uColour = GL20.glGetUniformLocation(compositeProgram, "colour");
      uView = GL20.glGetUniformLocation(compositeProgram, "view");
      GL20.glUseProgram(0);
      return true;
   }

   // the atlas zombie's quad as DeadBodyAtlas draws it (DeadBodyAtlas.frag: depth from the atlas depth texture)
   private static final String ATLAS_VERT = """
      #version 330 core
      uniform mat4 matrix;
      uniform vec4 rect;
      uniform vec4 uvRect;
      out vec2 uv;
      void main() {
          vec2 p = vec2(gl_VertexID & 1, gl_VertexID >> 1);
          gl_Position = matrix * vec4(rect.xy + p * rect.zw, 0.0, 1.0);
          uv = mix(uvRect.xy, uvRect.zw, p);
      }
      """;
   private static final String ATLAS_FRAG = """
      #version 330 core
      uniform sampler2D diffuse;
      uniform sampler2D depth;
      uniform vec3 depthRange; // near, far, slack
      in vec2 uv;
      void main() {
          float d = texture(depth, uv).r;
          if (texture(diffuse, uv).a < 0.01 || d <= 0.0) discard;
          gl_FragDepth = mix(depthRange.x, depthRange.y, d) - depthRange.z;
      }
      """;
   private static final String COMPOSITE_VERT = """
      #version 330 core
      void main() {
          vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
          gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
   // runs only where the stencil test passed: S set, V clear (a hidden part of a seen zombie)
   private static final String COMPOSITE_FRAG = """
      #version 330 core
      uniform usampler2D stencil;
      uniform int radius;
      uniform vec4 colour; // rgb, opacity
      uniform int view; // dev: 1 = the stencil codes (hidden red, seen zombie green, other characters blue)
      layout(location = 0) out vec4 frag;
      void main() {
          ivec2 p = ivec2(gl_FragCoord.xy);
          ivec2 size = textureSize(stencil, 0);
          uint c = texelFetch(stencil, p, 0).r;
          if (view == 1) {
              vec3 k = (c & 0x60u) == 0x40u ? vec3(1.0, 0.1, 0.1) : (c & 0x60u) == 0x60u ? vec3(0.1, 1.0, 0.2) : vec3(0.2, 0.4, 1.0);
              frag = vec4(k * 0.6, 0.6);
              return;
          }
          for (int y = -radius; y <= radius; ++y) {
              for (int x = -radius; x <= radius; ++x) {
                  if (x * x + y * y > radius * radius) continue;
                  ivec2 q = p + ivec2(x, y);
                  if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
                  if ((texelFetch(stencil, q, 0).r & 0x60u) == 0u) {
                      float a = colour.a * float(c & 0xFu) / 15.0;
                      frag = vec4(colour.rgb * a, a);
                      return;
                  }
              }
          }
          discard;
      }
      """;
}
