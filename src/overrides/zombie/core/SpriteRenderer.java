package zombie.core;

import imgui.ImDrawData;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.UsedFromLua;
import zombie.asset.Asset;
import zombie.core.Styles.AbstractStyle;
import zombie.core.Styles.AdditiveStyle;
import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.LightingStyle;
import zombie.core.Styles.Style;
import zombie.core.Styles.TransparentStyle;
import zombie.core.VBO.GLVertexBufferObject;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLState;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.skinnedmodel.ModelManager.ModelSlot;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.sprite.SpriteRendererStates;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureAssetManager;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.core.textures.TextureDraw.Type;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.iso.IsoPuddles;
import zombie.iso.PlayerCamera;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.ui.UIManager;
import zombie.util.list.PZArrayUtil;
import zombie.worldMap.WorldMapImages;

@UsedFromLua
public final class SpriteRenderer {
   public static final SpriteRenderer instance = new SpriteRenderer();
   static final int TEXTURE0_COORD_OFFSET = 8;
   static final int COLOR_OFFSET = 16;
   static final int TEXTURE1_COORD_OFFSET = 20;
   static final int TEXTURE2_COORD_OFFSET = 28;
   static final int VERTEX_SIZE = 36;
   public static final SpriteRenderer.RingBuffer ringBuffer = new SpriteRenderer.RingBuffer();
   public static final int NUM_RENDER_STATES = 3;
   public final SpriteRendererStates states = new SpriteRendererStates();
   private volatile boolean waitingForRenderState;
   public static boolean glBlendfuncEnabled = true;
   private final PerformanceProfileProbe buildStateDrawBuffer = new PerformanceProfileProbe("buildStateDrawBuffer");
   private final PerformanceProfileProbe buildStateUiDrawBuffer = new PerformanceProfileProbe("buildStateUIDrawBuffer(UI)");
   private static long waitTime;
   private final PerformanceProfileProbe waitForReadyState = new PerformanceProfileProbe("waitForReadyState");
   private final PerformanceProfileProbe waitForReadySlotToOpen = new PerformanceProfileProbe("waitForReadySlotToOpen");

   public void create() {
      ringBuffer.create();
   }

   public void clearSprites() {
      this.states.getPopulating().clear();
   }

   public void glDepthMask(boolean b) {
      this.states.getPopulatingActiveState().glDepthMask(b);
   }

   public void renderflipped(Texture tex, float x, float y, float width, float height, float r, float g, float b, float a, Consumer<TextureDraw> texdModifier) {
      this.states.getPopulatingActiveState().renderflipped(tex, x, y, width, height, r, g, b, a, texdModifier);
   }

   public void drawModel(ModelSlot model) {
      this.states.getPopulatingActiveState().drawModel(model);
   }

   public void renderQueued() {
      this.states.getPopulatingActiveState().renderQueued();
   }

   public void beginProfile(PerformanceProfileProbe probe) {
      this.states.getPopulatingActiveState().beginProfile(probe);
   }

   public void endProfile(PerformanceProfileProbe probe) {
      this.states.getPopulatingActiveState().endProfile(probe);
   }

   public void drawSkyBox(Shader shader, int playerIndex, int apiId, int bufferId) {
      this.states.getPopulatingActiveState().drawSkyBox(shader, playerIndex, apiId, bufferId);
   }

   public void drawWater(Shader shader, int playerIndex, int firstSquare, int numSquares, boolean bShore) {
      this.states.getPopulatingActiveState().drawWater(shader, playerIndex, firstSquare, numSquares, bShore);
   }

   public void drawPuddles(int playerIndex, int z, int firstSquare, int numSquares) {
      this.states.getPopulatingActiveState().drawPuddles(playerIndex, z, firstSquare, numSquares);
   }

   public void drawParticles(int playerIndex, int var1, int var2) {
      this.states.getPopulatingActiveState().drawParticles(playerIndex, var1, var2);
   }

   public TextureDraw drawGeneric(GenericDrawer gd) {
      return this.states.getPopulatingActiveState().drawGeneric(gd);
   }

   public void glDisable(int a) {
      this.states.getPopulatingActiveState().glDisable(a);
   }

   public void glEnable(int a) {
      this.states.getPopulatingActiveState().glEnable(a);
   }

   public void NewFrame() {
      this.states.getPopulatingActiveState().NewFrame();
   }

   public void glDepthFunc(int a) {
      this.states.getPopulatingActiveState().glDepthFunc(a);
   }

   public void glStencilMask(int a) {
      this.states.getPopulatingActiveState().glStencilMask(a);
   }

   public void glClear(int a) {
      this.states.getPopulatingActiveState().glClear(a);
   }

   public void glBindFramebuffer(int binding, int fbo) {
      this.states.getPopulatingActiveState().glBindFramebuffer(binding, fbo);
   }

   public void glClearColor(int r, int g, int b, int a) {
      this.states.getPopulatingActiveState().glClearColor(r, g, b, a);
   }

   public void glClearDepth(float d) {
      this.states.getPopulatingActiveState().glClearDepth(d);
   }

   public void glStencilFunc(int a, int b, int c) {
      this.states.getPopulatingActiveState().glStencilFunc(a, b, c);
   }

   public void glStencilOp(int a, int b, int c) {
      this.states.getPopulatingActiveState().glStencilOp(a, b, c);
   }

   public void glColorMask(int a, int b, int c, int d) {
      this.states.getPopulatingActiveState().glColorMask(a, b, c, d);
   }

   public void glAlphaFunc(int a, float b) {
      this.states.getPopulatingActiveState().glAlphaFunc(a, b);
   }

   public void glBlendFunc(int a, int b) {
      this.states.getPopulatingActiveState().glBlendFunc(a, b);
   }

   public void glBlendFuncSeparate(int a, int b, int c, int d) {
      this.states.getPopulatingActiveState().glBlendFuncSeparate(a, b, c, d);
   }

   public void glBlendEquation(int a) {
      this.states.getPopulatingActiveState().glBlendEquation(a);
   }

   public void render(
      Texture tex,
      double x1,
      double y1,
      double x2,
      double y2,
      double x3,
      double y3,
      double x4,
      double y4,
      float r,
      float g,
      float b,
      float a,
      Consumer<TextureDraw> texdModifier
   ) {
      this.states.getPopulatingActiveState().render(tex, x1, y1, x2, y2, x3, y3, x4, y4, r, g, b, a, texdModifier);
   }

   public void render(
      Texture tex,
      double x1,
      double y1,
      double x2,
      double y2,
      double x3,
      double y3,
      double x4,
      double y4,
      float r1,
      float g1,
      float b1,
      float a1,
      float r2,
      float g2,
      float b2,
      float a2,
      float r3,
      float g3,
      float b3,
      float a3,
      float r4,
      float g4,
      float b4,
      float a4,
      Consumer<TextureDraw> texdModifier
   ) {
      this.states
         .getPopulatingActiveState()
         .render(tex, x1, y1, x2, y2, x3, y3, x4, y4, r1, g1, b1, a1, r2, g2, b2, a2, r3, g3, b3, a3, r4, g4, b4, a4, texdModifier);
   }

   public void render(
      Texture tex,
      double x1,
      double y1,
      double x2,
      double y2,
      double x3,
      double y3,
      double x4,
      double y4,
      double u1,
      double v1,
      double u2,
      double v2,
      double u3,
      double v3,
      double u4,
      double v4,
      float r,
      float g,
      float b,
      float a
   ) {
      this.states.getPopulatingActiveState().render(tex, x1, y1, x2, y2, x3, y3, x4, y4, u1, v1, u2, v2, u3, v3, u4, v4, r, g, b, a);
   }

   public void renderdebug(
      Texture tex,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      float x4,
      float y4,
      float r1,
      float g1,
      float b1,
      float a1,
      float r2,
      float g2,
      float b2,
      float a2,
      float r3,
      float g3,
      float b3,
      float a3,
      float r4,
      float g4,
      float b4,
      float a4,
      Consumer<TextureDraw> texdModifier
   ) {
      this.states
         .getPopulatingActiveState()
         .renderdebug(tex, x1, y1, x2, y2, x3, y3, x4, y4, r1, g1, b1, a1, r2, g2, b2, a2, r3, g3, b3, a3, r4, g4, b4, a4, texdModifier);
   }

   public void renderline(Texture tex, int x1, int y1, int x2, int y2, float r, float g, float b, float a, float thickness) {
      this.states.getPopulatingActiveState().renderline(tex, x1, y1, x2, y2, r, g, b, a, thickness);
   }

   public void renderline(Texture tex, int x1, int y1, int x2, int y2, float r, float g, float b, float a) {
      this.states.getPopulatingActiveState().renderline(tex, x1, y1, x2, y2, r, g, b, a);
   }

   public void renderlinef(Texture tex, float x1, float y1, float x2, float y2, float r, float g, float b, float a, int thickness) {
      this.states.getPopulatingActiveState().renderlinef(tex, x1, y1, x2, y2, r, g, b, a, thickness);
   }

   public void renderlinef(Texture tex, float x1, float y1, float x2, float y2, float r, float g, float b, float a, float baseThickness, float topThickness) {
      this.states.getPopulatingActiveState().renderlinef(tex, x1, y1, x2, y2, r, g, b, a, baseThickness, topThickness);
   }

   public void render(Texture tex, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int c1, int c2, int c3, int c4) {
      this.states.getPopulatingActiveState().render(tex, x1, y1, x2, y2, x3, y3, x4, y4, c1, c2, c3, c4);
   }

   public void render(Texture tex, float x, float y, float width, float height, float r, float g, float b, float a, Consumer<TextureDraw> texdModifier) {
      if (PerformanceSettings.fboRenderChunk && !WeatherFxMask.isRenderingMask()) {
         this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, texdModifier);
      } else {
         float x1 = PZMath.floor(x);
         float y1 = PZMath.floor(y);
         float x2 = PZMath.ceil(x + width);
         float y2 = PZMath.ceil(y + height);
         this.states.getPopulatingActiveState().render(tex, x1, y1, x2 - x1, y2 - y1, r, g, b, a, texdModifier);
      }
   }

   public void render(
      Texture tex, Texture tex2, float x, float y, float width, float height, float r, float g, float b, float a, Consumer<TextureDraw> texdModifier
   ) {
      if (PerformanceSettings.fboRenderChunk && !WeatherFxMask.isRenderingMask()) {
         this.states.getPopulatingActiveState().render(tex, tex2, x, y, width, height, r, g, b, a, texdModifier);
      } else {
         float x1 = PZMath.floor(x);
         float y1 = PZMath.floor(y);
         float x2 = PZMath.ceil(x + width);
         float y2 = PZMath.ceil(y + height);
         this.states.getPopulatingActiveState().render(tex, tex2, x1, y1, x2 - x1, y2 - y1, r, g, b, a, texdModifier);
      }
   }

   public void renderi(Texture tex, int x, int y, int width, int height, float r, float g, float b, float a, Consumer<TextureDraw> texdModifier) {
      this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, texdModifier);
   }

   public void renderClamped(
      Texture tex,
      int x,
      int y,
      int width,
      int height,
      int clampMinX,
      int clampMinY,
      int clampWidth,
      int clampHeight,
      float r,
      float g,
      float b,
      float a,
      Consumer<TextureDraw> texdModifier
   ) {
      int x1 = PZMath.clamp(x, clampMinX, clampMinX + clampWidth);
      int y1 = PZMath.clamp(y, clampMinY, clampMinY + clampHeight);
      int x2 = PZMath.clamp(x + width, clampMinX, clampMinX + clampWidth);
      int y2 = PZMath.clamp(y + height, clampMinY, clampMinY + clampHeight);
      if (x1 != x2 && y1 != y2) {
         int dx1 = x1 - x;
         int dx2 = x + width - x2;
         int dy1 = y1 - y;
         int dy2 = y + height - y2;
         if (dx1 == 0 && dx2 == 0 && dy1 == 0 && dy2 == 0) {
            this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, texdModifier);
         } else {
            float u0 = 0.0F;
            float v0 = 0.0F;
            float u1 = 1.0F;
            float v1 = 1.0F;
            if (tex != null) {
               u0 = (float)dx1 / width;
               v0 = (float)dy1 / height;
               u1 = (float)(width - dx2) / width;
               v1 = (float)(height - dy2) / height;
               float uSpan = tex.getXEnd() - tex.getXStart();
               float vSpan = tex.getYEnd() - tex.getYStart();
               u0 = tex.getXStart() + u0 * uSpan;
               u1 = tex.getXStart() + u1 * uSpan;
               v0 = tex.getYStart() + v0 * vSpan;
               v1 = tex.getYStart() + v1 * vSpan;
            }

            x = x1;
            y = y1;
            width = x2 - x1;
            height = y2 - y1;
            this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, u0, v0, u1, v0, u1, v1, u0, v1, texdModifier);
         }
      }
   }

   public void renderRect(int x, int y, int width, int height, float r, float g, float b, float a) {
      this.states.getPopulatingActiveState().renderRect(x, y, width, height, r, g, b, a);
   }

   public void renderPoly(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, float r, float g, float b, float a) {
      this.states.getPopulatingActiveState().renderPoly(x1, y1, x2, y2, x3, y3, x4, y4, r, g, b, a);
   }

   public void renderPoly(Texture tex, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, float r, float g, float b, float a) {
      this.states.getPopulatingActiveState().renderPoly(tex, x1, y1, x2, y2, x3, y3, x4, y4, r, g, b, a);
   }

   public void renderPoly(
      Texture tex,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      float x4,
      float y4,
      float r,
      float g,
      float b,
      float a,
      float u1,
      float v1,
      float u2,
      float v2,
      float u3,
      float v3,
      float u4,
      float v4
   ) {
      this.states.getPopulatingActiveState().renderPoly(tex, x1, y1, x2, y2, x3, y3, x4, y4, r, g, b, a, u1, v1, u2, v2, u3, v3, u4, v4);
   }

   public void render(
      Texture tex,
      float x,
      float y,
      float width,
      float height,
      float r,
      float g,
      float b,
      float a,
      float u1,
      float v1,
      float u2,
      float v2,
      float u3,
      float v3,
      float u4,
      float v4
   ) {
      this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, u1, v1, u2, v2, u3, v3, u4, v4, null);
   }

   public void render(
      Texture tex,
      float x,
      float y,
      float width,
      float height,
      float r,
      float g,
      float b,
      float a,
      float u1,
      float v1,
      float u2,
      float v2,
      float u3,
      float v3,
      float u4,
      float v4,
      Consumer<TextureDraw> texdModifier
   ) {
      this.states.getPopulatingActiveState().render(tex, x, y, width, height, r, g, b, a, u1, v1, u2, v2, u3, v3, u4, v4, texdModifier);
   }

   private void buildDrawBuffer(TextureDraw[] sprites, Style[] styles, int numSprites) {
      TextureDraw lastSprite = null;

      for (int i = 0; i < numSprites; i++) {
         TextureDraw sprite = sprites[i];
         Style style = styles[i];
         ringBuffer.add(sprite, lastSprite, style);
         lastSprite = sprite;
      }
   }

   public void prePopulating() {
      long pzoptT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
      this.states.getPopulating().prePopulating();
      pzopt.GtAb.end(pzopt.GtAb.S_PREPOP, pzoptT); // pzopt
   }

   public void postRender() {
      SpriteRenderState renderState = this.states.getRendering();
      if (renderState.numSprites == 0 && renderState.stateUi.numSprites == 0) {
         renderState.onRendered();
      } else {
         TextureFBO.reset();
         IsoPuddles.VBOs.startFrame();
         GLStateRenderThread.startFrame();
         AbstractPerformanceProfileProbe var2 = this.buildStateUiDrawBuffer.profile();

         try {
            this.buildStateUIDrawBuffer(renderState);
         } catch (Throwable var8) {
            if (var2 != null) {
               try {
                  var2.close();
               } catch (Throwable var6) {
                  var8.addSuppressed(var6);
               }
            }

            throw var8;
         }

         if (var2 != null) {
            var2.close();
         }

         if (!UIManager.useUiFbo) {
            WorldMapImages.startFrame();
         }

         var2 = this.buildStateDrawBuffer.profile();

         try {
            this.buildStateDrawBuffer(renderState);
         } catch (Throwable var7) {
            if (var2 != null) {
               try {
                  var2.close();
               } catch (Throwable var5) {
                  var7.addSuppressed(var5);
               }
            }

            throw var7;
         }

         if (var2 != null) {
            var2.close();
         }

         if (!UIManager.useUiFbo) {
            WorldMapImages.endFrame();
         }

         renderState.onRendered();
         Core.getInstance().setLastRenderedFBO(renderState.fbo);
         this.notifyRenderStateQueue();
      }
   }

   private void buildStateDrawBuffer(SpriteRenderState renderState) {
      ringBuffer.begin();
      this.buildDrawBuffer(renderState.sprite, renderState.style, renderState.numSprites);
      ringBuffer.render();
   }

   private void buildStateUIDrawBuffer(SpriteRenderState renderState) {
      if (renderState.stateUi.numSprites > 0) {
         WorldMapImages.startFrame();
         ringBuffer.begin();
         renderState.stateUi.active = true;
         this.buildDrawBuffer(renderState.stateUi.sprite, renderState.stateUi.style, renderState.stateUi.numSprites);
         ringBuffer.render();
         WorldMapImages.endFrame();
      }

      renderState.stateUi.active = false;
   }

   public void notifyRenderStateQueue() {
      synchronized (this.states) {
         this.states.notifyAll();
      }
   }

   public void glBuffer(int i, int p) {
      this.states.getPopulatingActiveState().glBuffer(i, p);
   }

   public void glDoStartFrame(int w, int h, float zoom, int player) {
      this.states.getPopulatingActiveState().glDoStartFrame(w, h, zoom, player);
   }

   public void FBORenderChunkStart(int index, boolean bClear) {
      this.states.getPopulatingActiveState().FBORenderChunkStart(index, bClear);
   }

   public void FBORenderChunkEnd() {
      this.states.getPopulatingActiveState().FBORenderChunkEnd();
   }

   public void glDoStartFrame(int w, int h, float zoom, int player, boolean isTextFrame) {
      this.states.getPopulatingActiveState().glDoStartFrame(w, h, zoom, player, isTextFrame);
   }

   public void glDoStartFrameFlipY(int w, int h, float zoom, int player) {
      this.states.getPopulatingActiveState().glDoStartFrameFlipY(w, h, zoom, player);
   }

   public void glDoStartFrameNoZoom(int w, int h, float zoom, int player) {
      this.states.getPopulatingActiveState().glDoStartFrameNoZoom(w, h, zoom, player);
   }

   public void glDoStartFrameFx(int w, int h, int player) {
      this.states.getPopulatingActiveState().glDoStartFrameFx(w, h, player);
   }

   public void glIgnoreStyles(boolean b) {
      this.states.getPopulatingActiveState().glIgnoreStyles(b);
   }

   public void glDoEndFrame() {
      this.states.getPopulatingActiveState().glDoEndFrame();
   }

   public void pushIsoView(float ox, float oy, float oz, float useangle, boolean vehicle) {
      this.states.getPopulatingActiveState().pushIsoView(ox, oy, oz, useangle, vehicle);
   }

   public void popIsoView() {
      this.states.getPopulatingActiveState().popIsoView();
   }

   public void glDoEndFrameFx(int player) {
      this.states.getPopulatingActiveState().glDoEndFrameFx(player);
   }

   public void doCoreIntParam(int id, float val) {
      this.states.getPopulatingActiveState().doCoreIntParam(id, val);
   }

   public void glTexParameteri(int a, int b, int c) {
      this.states.getPopulatingActiveState().glTexParameteri(a, b, c);
   }

   public void StartShader(int iD, int playerIndex) {
      this.states.getPopulatingActiveState().StartShader(iD, playerIndex);
   }

   public void StartShader(int iD, int playerIndex, ShaderUniformSetter uniforms) {
      this.states.getPopulatingActiveState().StartShader(iD, playerIndex, uniforms);
   }

   public void EndShader() {
      this.states.getPopulatingActiveState().EndShader();
   }

   public void setCutawayTexture(Texture tex, int x, int y, int w, int h) {
      this.states.getPopulatingActiveState().setCutawayTexture(tex, x, y, w, h);
   }

   public void clearCutawayTexture() {
      this.states.getPopulatingActiveState().clearCutawayTexture();
   }

   public void setCutawayTexture2(Texture tex, int x, int y, int w, int h) {
      this.states.getPopulatingActiveState().setCutawayTexture2(tex, x, y, w, h);
   }

   public void setUseVertColorsArray(byte whichShader, int c0, int c1, int c2, int c3) {
      this.states.getPopulatingActiveState().setUseVertColorsArray(whichShader, c0, c1, c2, c3);
   }

   public void clearUseVertColorsArray() {
      this.states.getPopulatingActiveState().clearUseVertColorsArray();
   }

   public void setExtraWallShaderParams(SpriteRenderer.WallShaderTexRender wallTexRender) {
      this.states.getPopulatingActiveState().setExtraWallShaderParams(wallTexRender);
   }

   public void ShaderUpdate1i(int shaderID, int uniform, int uniformValue) {
      this.states.getPopulatingActiveState().ShaderUpdate1i(shaderID, uniform, uniformValue);
   }

   public void ShaderUpdate1f(int shaderID, int uniform, float uniformValue) {
      this.states.getPopulatingActiveState().ShaderUpdate1f(shaderID, uniform, uniformValue);
   }

   public void ShaderUpdate2f(int shaderID, int uniform, float value1, float value2) {
      this.states.getPopulatingActiveState().ShaderUpdate2f(shaderID, uniform, value1, value2);
   }

   public void ShaderUpdate3f(int shaderID, int uniform, float value1, float value2, float value3) {
      this.states.getPopulatingActiveState().ShaderUpdate3f(shaderID, uniform, value1, value2, value3);
   }

   public void ShaderUpdate4f(int shaderID, int uniform, float value1, float value2, float value3, float value4) {
      this.states.getPopulatingActiveState().ShaderUpdate4f(shaderID, uniform, value1, value2, value3, value4);
   }

   public void glLoadIdentity() {
      this.states.getPopulatingActiveState().glLoadIdentity();
   }

   public void glGenerateMipMaps(int a) {
      this.states.getPopulatingActiveState().glGenerateMipMaps(a);
   }

   public void glBind(int a) {
      this.states.getPopulatingActiveState().glBind(a);
   }

   public void releaseFBORenderChunkLock() {
      this.states.getPopulatingActiveState().releaseFBORenderChunkLock();
   }

   public void glViewport(int x, int y, int width, int height) {
      this.states.getPopulatingActiveState().glViewport(x, y, width, height);
   }

   public void render(ImDrawData drawData) {
      this.states.getPopulatingActiveState().render(drawData);
   }

   public void startOffscreenUI() {
      this.states.getPopulating().stateUi.active = true;
      this.states.getPopulating().stateUi.defaultStyle = TransparentStyle.instance;
      GLState.startFrame();
   }

   public void stopOffscreenUI() {
      this.states.getPopulating().stateUi.active = false;
   }

   public static long getWaitTime() {
      return waitTime;
   }

   public void pushFrameDown() {
      synchronized (this.states) {
         long startTime = System.nanoTime();
         AbstractPerformanceProfileProbe var4 = this.waitForReadySlotToOpen.profile();

         try {
            long pzoptT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
            this.waitForReadySlotToOpen();
            pzopt.GtAb.end(pzopt.GtAb.S_HANDOFF, pzoptT); // pzopt
         } catch (Throwable var9) {
            if (var4 != null) {
               try {
                  var4.close();
               } catch (Throwable var8) {
                  var9.addSuppressed(var8);
               }
            }

            throw var9;
         }

         if (var4 != null) {
            var4.close();
         }

         waitTime = System.nanoTime() - startTime;
         this.states.movePopulatingToReady();
         this.notifyRenderStateQueue();
      }
   }

   public SpriteRenderState acquireStateForRendering(BooleanSupplier waitCallback) {
      synchronized (this.states) {
         AbstractPerformanceProfileProbe var3 = this.waitForReadyState.profile();

         Object var4;
         label49: {
            try {
               if (!this.waitForReadyState(waitCallback)) {
                  var4 = null;
                  break label49;
               }
            } catch (Throwable var8) {
               if (var3 != null) {
                  try {
                     var3.close();
                  } catch (Throwable var7) {
                     var8.addSuppressed(var7);
                  }
               }

               throw var8;
            }

            if (var3 != null) {
               var3.close();
            }

            this.states.moveReadyToRendering();
            this.notifyRenderStateQueue();
            return this.states.getRendering();
         }

         if (var3 != null) {
            var3.close();
         }

         return (SpriteRenderState)var4;
      }
   }

   private boolean waitForReadyState(BooleanSupplier waitCallback) {
      if (RenderThread.isRunning() && this.states.getReady() == null) {
         if (!RenderThread.isWaitForRenderState() && !this.isWaitingForRenderState()) {
            return false;
         }

         while (this.states.getReady() == null) {
            try {
               if (!waitCallback.getAsBoolean()) {
                  return false;
               }

               this.states.wait();
            } catch (InterruptedException var3) {
            }
         }

         return true;
      } else {
         return true;
      }
   }

   private void waitForReadySlotToOpen() {
      if (this.states.getReady() != null && RenderThread.isRunning()) {
         this.waitingForRenderState = true;

         while (this.states.getReady() != null) {
            try {
               this.states.wait();
            } catch (InterruptedException var2) {
            }
         }

         this.waitingForRenderState = false;
      }
   }

   public int getMainStateIndex() {
      return this.states.getPopulatingActiveState().index;
   }

   public int getRenderStateIndex() {
      return this.states.getRenderingActiveState().index;
   }

   public boolean getDoAdditive() {
      return this.states.getPopulatingActiveState().defaultStyle == AdditiveStyle.instance;
   }

   public void setDefaultStyle(AbstractStyle style) {
      this.states.getPopulatingActiveState().defaultStyle = style;
   }

   public void setDoAdditive(boolean bDoAdditive) {
      this.states.getPopulatingActiveState().defaultStyle = (AbstractStyle)(bDoAdditive ? AdditiveStyle.instance : TransparentStyle.instance);
   }

   public void initFromIsoCamera(int nPlayer) {
      this.states.getPopulating().playerCamera[nPlayer].initFromIsoCamera(nPlayer);
   }

   public void setRenderingPlayerIndex(int player) {
      this.states.getRendering().playerIndex = player;
   }

   public int getRenderingPlayerIndex() {
      return this.states.getRendering().playerIndex;
   }

   public PlayerCamera getRenderingPlayerCamera(int userId) {
      return this.states.getRendering().playerCamera[userId];
   }

   public SpriteRenderState getRenderingState() {
      return this.states.getRendering();
   }

   public SpriteRenderState getPopulatingState() {
      return this.states.getPopulating();
   }

   public boolean isMaxZoomLevel() {
      return this.getPlayerZoomLevel() >= this.getPlayerMaxZoom();
   }

   public boolean isMinZoomLevel() {
      return this.getPlayerZoomLevel() <= this.getPlayerMinZoom();
   }

   public float getPlayerZoomLevel() {
      SpriteRenderState renderingState = this.states.getRendering();
      int userId = renderingState.playerIndex;
      return renderingState.zoomLevel[userId];
   }

   public float getPlayerMaxZoom() {
      SpriteRenderState renderingState = this.states.getRendering();
      return renderingState.maxZoomLevel;
   }

   public float getPlayerMinZoom() {
      SpriteRenderState renderingState = this.states.getRendering();
      return renderingState.minZoomLevel;
   }

   public boolean isWaitingForRenderState() {
      return this.waitingForRenderState;
   }

   public static final class RingBuffer {
      GLVertexBufferObject[] vbo;
      GLVertexBufferObject[] ibo;
      long bufferSize;
      long bufferSizeInVertices;
      long indexBufferSize;
      int numBuffers;
      int sequence = -1;
      int mark = -1;
      FloatBuffer currentVertices;
      ShortBuffer currentIndices;
      FloatBuffer[] vertices;
      ByteBuffer[] verticesBytes;
      ShortBuffer[] indices;
      ByteBuffer[] indicesBytes;
      Texture lastRenderedTexture0;
      Texture currentTexture0;
      Texture lastRenderedTexture1;
      Texture currentTexture1;
      Texture lastRenderedTexture2;
      Texture currentTexture2;
      boolean shaderChangedTexture1;
      byte lastUseAttribArray;
      byte currentUseAttribArray;
      Style lastRenderedStyle;
      Style currentStyle;
      SpriteRenderer.RingBuffer.StateRun[] stateRun;
      public boolean restoreVbos;
      public boolean restoreBoundTextures;
      int vertexCursor;
      int indexCursor;
      int numRuns;
      SpriteRenderer.RingBuffer.StateRun currentRun;
      public static boolean ignoreStyles;
      final PerformanceProfileProbe drawRangleElements = new PerformanceProfileProbe("Render Style");

      RingBuffer() {
      }

      void create() {
         GL20.glEnableVertexAttribArray(0);
         GL20.glEnableVertexAttribArray(1);
         GL20.glEnableVertexAttribArray(2);
         GL20.glEnableVertexAttribArray(3);
         GL20.glEnableVertexAttribArray(4);
         this.bufferSize = Core.debug ? 262144L : 65536L;
         this.numBuffers = Core.debug ? 256 : 128;
         this.bufferSizeInVertices = this.bufferSize / 36L;
         this.indexBufferSize = this.bufferSizeInVertices * 3L;
         this.vertices = new FloatBuffer[this.numBuffers];
         this.verticesBytes = new ByteBuffer[this.numBuffers];
         this.indices = new ShortBuffer[this.numBuffers];
         this.indicesBytes = new ByteBuffer[this.numBuffers];
         this.stateRun = new SpriteRenderer.RingBuffer.StateRun[5000];

         for (int n = 0; n < 5000; n++) {
            this.stateRun[n] = new SpriteRenderer.RingBuffer.StateRun();
         }

         this.vbo = new GLVertexBufferObject[this.numBuffers];
         this.ibo = new GLVertexBufferObject[this.numBuffers];

         for (int i = 0; i < this.numBuffers; i++) {
            this.vbo[i] = new GLVertexBufferObject(this.bufferSize, GLVertexBufferObject.funcs.GL_ARRAY_BUFFER(), GLVertexBufferObject.funcs.GL_STREAM_DRAW());
            this.vbo[i].create();
            this.ibo[i] = new GLVertexBufferObject(
               this.indexBufferSize, GLVertexBufferObject.funcs.GL_ELEMENT_ARRAY_BUFFER(), GLVertexBufferObject.funcs.GL_STREAM_DRAW()
            );
            this.ibo[i].create();
         }
      }

      void add(TextureDraw draw, TextureDraw prevDraw, Style newStyle) {
         if (newStyle != null) {
            if (this.vertexCursor + 4 > this.bufferSizeInVertices || this.indexCursor + 6 > this.indexBufferSize) {
               SpriteRenderer.ringBuffer.render();
               SpriteRenderer.ringBuffer.next();
            }

            if (this.prepareCurrentRun(draw, prevDraw, newStyle)) {
               FloatBuffer floats = this.currentVertices;
               AlphaOp alphaOp = newStyle.getAlphaOp();
               floats.put(draw.x0);
               floats.put(draw.y0);
               if (draw.tex == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  if (draw.flipped) {
                     floats.put(draw.u1);
                  } else {
                     floats.put(draw.u0);
                  }

                  floats.put(draw.v0);
               }

               int color = draw.getColor(0);
               alphaOp.op(color, 255, floats);
               if (draw.tex1 == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  floats.put(draw.tex1U0);
                  floats.put(draw.tex1V0);
               }

               if (draw.tex2 == null) {
                  floats.put(draw.pzoptVD ? draw.pzoptVDFront : 0.0F); // pzopt: tileVertexDepth, the tile's depth pair rides in the empty third texture's slot (pzopt.TileBatch)
                  floats.put(draw.pzoptVD ? draw.pzoptVDFar : 0.0F); // pzopt: tileVertexDepth
               } else {
                  floats.put(draw.tex2U0);
                  floats.put(draw.tex2V0);
               }

               floats.put(draw.x1);
               floats.put(draw.y1);
               if (draw.tex == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  if (draw.flipped) {
                     floats.put(draw.u0);
                  } else {
                     floats.put(draw.u1);
                  }

                  floats.put(draw.v1);
               }

               color = draw.getColor(1);
               alphaOp.op(color, 255, floats);
               if (draw.tex1 == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  floats.put(draw.tex1U1);
                  floats.put(draw.tex1V1);
               }

               if (draw.tex2 == null) {
                  floats.put(draw.pzoptVD ? draw.pzoptVDFront : 0.0F); // pzopt: tileVertexDepth, the tile's depth pair rides in the empty third texture's slot (pzopt.TileBatch)
                  floats.put(draw.pzoptVD ? draw.pzoptVDFar : 0.0F); // pzopt: tileVertexDepth
               } else {
                  floats.put(draw.tex2U1);
                  floats.put(draw.tex2V1);
               }

               floats.put(draw.x2);
               floats.put(draw.y2);
               if (draw.tex == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  if (draw.flipped) {
                     floats.put(draw.u3);
                  } else {
                     floats.put(draw.u2);
                  }

                  floats.put(draw.v2);
               }

               color = draw.getColor(2);
               alphaOp.op(color, 255, floats);
               if (draw.tex1 == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  floats.put(draw.tex1U2);
                  floats.put(draw.tex1V2);
               }

               if (draw.tex2 == null) {
                  floats.put(draw.pzoptVD ? draw.pzoptVDFront : 0.0F); // pzopt: tileVertexDepth, the tile's depth pair rides in the empty third texture's slot (pzopt.TileBatch)
                  floats.put(draw.pzoptVD ? draw.pzoptVDFar : 0.0F); // pzopt: tileVertexDepth
               } else {
                  floats.put(draw.tex2U2);
                  floats.put(draw.tex2V2);
               }

               floats.put(draw.x3);
               floats.put(draw.y3);
               if (draw.tex == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  if (draw.flipped) {
                     floats.put(draw.u2);
                  } else {
                     floats.put(draw.u3);
                  }

                  floats.put(draw.v3);
               }

               color = draw.getColor(3);
               alphaOp.op(color, 255, floats);
               if (draw.tex1 == null) {
                  floats.put(0.0F);
                  floats.put(0.0F);
               } else {
                  floats.put(draw.tex1U3);
                  floats.put(draw.tex1V3);
               }

               if (draw.tex2 == null) {
                  floats.put(draw.pzoptVD ? draw.pzoptVDFront : 0.0F); // pzopt: tileVertexDepth, the tile's depth pair rides in the empty third texture's slot (pzopt.TileBatch)
                  floats.put(draw.pzoptVD ? draw.pzoptVDFar : 0.0F); // pzopt: tileVertexDepth
               } else {
                  floats.put(draw.tex2U3);
                  floats.put(draw.tex2V3);
               }

               if (draw.getColor(0) == draw.getColor(2)) {
                  this.currentIndices.put((short)this.vertexCursor);
                  this.currentIndices.put((short)(this.vertexCursor + 1));
                  this.currentIndices.put((short)(this.vertexCursor + 2));
                  this.currentIndices.put((short)this.vertexCursor);
                  this.currentIndices.put((short)(this.vertexCursor + 2));
                  this.currentIndices.put((short)(this.vertexCursor + 3));
               } else {
                  this.currentIndices.put((short)(this.vertexCursor + 1));
                  this.currentIndices.put((short)(this.vertexCursor + 2));
                  this.currentIndices.put((short)(this.vertexCursor + 3));
                  this.currentIndices.put((short)(this.vertexCursor + 1));
                  this.currentIndices.put((short)(this.vertexCursor + 3));
                  this.currentIndices.put((short)(this.vertexCursor + 0));
               }

               this.indexCursor += 6;
               this.vertexCursor += 4;
               this.currentRun.endIndex += 6;
               this.currentRun.length += 4;
            }
         }
      }

      private boolean prepareCurrentRun(TextureDraw draw, TextureDraw prevDraw, Style newStyle) {
         Texture newTexture0 = draw.tex;
         Texture newTexture1 = draw.tex1;
         Texture newTexture2 = draw.tex2;
         byte newUseAttribArray = draw.useAttribArray;
         if (this.isStateChanged(draw, prevDraw, newStyle, newTexture0, newTexture1, newTexture2, newUseAttribArray)) {
            this.currentRun = this.stateRun[this.numRuns];
            this.currentRun.start = this.vertexCursor;
            this.currentRun.length = 0;
            this.currentRun.style = newStyle;
            this.currentRun.texture0 = newTexture0;
            this.currentRun.z = draw.z;
            this.currentRun.chunkDepth = draw.chunkDepth;
            this.currentRun.texture1 = newTexture1;
            this.currentRun.texture2 = newTexture2;
            this.currentRun.useAttribArray = newUseAttribArray;
            this.currentRun.indices = this.currentIndices;
            this.currentRun.startIndex = this.indexCursor;
            this.currentRun.endIndex = this.indexCursor;
            this.numRuns++;
            if (this.numRuns == this.stateRun.length) {
               this.growStateRuns();
            }

            this.currentStyle = newStyle;
            this.currentTexture0 = newTexture0;
            this.currentTexture1 = newTexture1;
            this.currentTexture2 = newTexture2;
            this.currentUseAttribArray = newUseAttribArray;
         }

         if (draw.type != Type.glDraw) {
            this.currentRun.ops.add(draw);
            return false;
         } else {
            return true;
         }
      }

      private boolean isStateChanged(
         TextureDraw draw, TextureDraw prevDraw, Style newStyle, Texture newTexture0, Texture newTexture1, Texture newTexture2, byte newUseAttribArray
      ) {
         if (this.currentRun == null) {
            return true;
         }

         if (draw.type == Type.DrawModel) {
            return true;
         }

         if (newUseAttribArray != this.currentUseAttribArray) {
            return true;
         }

         if (newTexture0 != this.currentTexture0) {
            return true;
         }

         if (newTexture1 != this.currentTexture1) {
            return true;
         }

         if (newTexture2 != this.currentTexture2) {
            return true;
         }

         if (prevDraw != null) {
            if (prevDraw.type == Type.DrawModel) {
               return true;
            }

            if (draw.type == Type.glDraw && prevDraw.type != Type.glDraw) {
               return true;
            }

            if (draw.type != Type.glDraw && prevDraw.type == Type.glDraw) {
               return true;
            }
         }

         if (newStyle != this.currentStyle) {
            if (this.currentStyle == null) {
               return true;
            }

            if (newStyle.getStyleID() != this.currentStyle.getStyleID()) {
               return true;
            }
         }

         return false;
      }

      private void next() {
         this.sequence++;
         if (this.sequence == this.numBuffers) {
            this.sequence = 0;
         }

         if (this.sequence == this.mark) {
            DebugType.General.error("Buffer overrun.");
         }

         this.vbo[this.sequence].bind();
         ByteBuffer buf = this.vbo[this.sequence].map();
         if (this.vertices[this.sequence] == null || this.verticesBytes[this.sequence] != buf) {
            this.verticesBytes[this.sequence] = buf;
            this.vertices[this.sequence] = buf.asFloatBuffer();
         }

         this.ibo[this.sequence].bind();
         ByteBuffer ibuf = this.ibo[this.sequence].map();
         if (this.indices[this.sequence] == null || this.indicesBytes[this.sequence] != ibuf) {
            this.indicesBytes[this.sequence] = ibuf;
            this.indices[this.sequence] = ibuf.asShortBuffer();
         }

         this.currentVertices = this.vertices[this.sequence];
         this.currentVertices.clear();
         this.currentIndices = this.indices[this.sequence];
         this.currentIndices.clear();
         this.vertexCursor = 0;
         this.indexCursor = 0;
         this.numRuns = 0;
         this.currentRun = null;
      }

      void begin() {
         this.currentStyle = null;
         this.currentTexture0 = null;
         this.currentTexture1 = null;
         this.currentUseAttribArray = -1;
         this.next();
         this.mark = this.sequence;
      }

      void render() {
         this.vbo[this.sequence].unmap();
         this.ibo[this.sequence].unmap();
         this.restoreVbos = true;

         for (int i = 0; i < this.numRuns; i++) {
            this.stateRun[i].render();
         }

         Model.modelDrawCounts.clear();
      }

      void growStateRuns() {
         SpriteRenderer.RingBuffer.StateRun[] newStateRun = new SpriteRenderer.RingBuffer.StateRun[(int)(this.stateRun.length * 1.5F)];
         System.arraycopy(this.stateRun, 0, newStateRun, 0, this.stateRun.length);

         for (int i = this.numRuns; i < newStateRun.length; i++) {
            newStateRun[i] = new SpriteRenderer.RingBuffer.StateRun();
         }

         this.stateRun = newStateRun;
      }

      public void shaderChangedTexture1() {
         this.shaderChangedTexture1 = true;
      }

      public void checkShaderChangedTexture1() {
         if (this.shaderChangedTexture1) {
            this.shaderChangedTexture1 = false;
            this.lastRenderedTexture1 = null;
            GL13.glActiveTexture(33985);
            GL11.glDisable(3553);
            GL13.glActiveTexture(33984);
         }
      }

      private void drawElements(int start, int length, int startIndex, int endIndex) {
         ShaderHelper.setModelViewProjection();
         GL12.glDrawRangeElements(4, start, start + length, endIndex - startIndex, 5123, startIndex * 2L);
         pzopt.DrawStats.ringDraws++; // pzopt: instrumented runs, the batcher's draw calls
      }

      public void debugBoundTexture(Texture texture0, int unit) {
         if (GL11.glGetInteger(34016) == unit) {
            int current = GL11.glGetInteger(32873);
            String currentName = null;
            if (texture0 == null && current != 0) {
               for (Asset asset : TextureAssetManager.instance.getAssetTable().values()) {
                  Texture texture = (Texture)asset;
                  if (texture.getID() == current) {
                     currentName = texture.getPath().getPath();
                     break;
                  }
               }

               DebugType.General.error("SpriteRenderer.lastBoundTexture0=null doesn't match OpenGL texture id=" + current + " " + currentName);
            } else if (texture0 != null && texture0.getID() != -1 && current != texture0.getID()) {
               for (Asset asset : TextureAssetManager.instance.getAssetTable().values()) {
                  Texture texture = (Texture)asset;
                  if (texture.getID() == current) {
                     currentName = texture.getName();
                     break;
                  }
               }

               DebugType.General
                  .error("SpriteRenderer.lastBoundTexture0 id=" + texture0.getID() + " doesn't match OpenGL texture id=" + current + " " + currentName);
            }
         }
      }

      private class StateRun {
         float z;
         float chunkDepth;
         Texture texture0;
         Texture texture1;
         Texture texture2;
         byte useAttribArray = -1;
         Style style;
         int start;
         int length;
         ShortBuffer indices;
         int startIndex;
         int endIndex;
         final ArrayList<TextureDraw> ops = new ArrayList<>();

         @Override
         public String toString() {
            String nl = System.lineSeparator();
            return this.getClass().getSimpleName()
               + "{ "
               + nl
               + "  ops:"
               + PZArrayUtil.arrayToString(this.ops, "{", "}", ", ")
               + nl
               + "  texture0:"
               + this.texture0
               + nl
               + "  texture1:"
               + this.texture1
               + nl
               + "  useAttribArray:"
               + this.useAttribArray
               + nl
               + "  style:"
               + this.style
               + nl
               + "  start:"
               + this.start
               + nl
               + "  length:"
               + this.length
               + nl
               + "  indices:"
               + this.indices
               + nl
               + "  startIndex:"
               + this.startIndex
               + nl
               + "  endIndex:"
               + this.endIndex
               + nl
               + "}";
         }

         void render() {
            if (this.style != null) {
               int n = this.ops.size();
               if (n > 0) {
                  for (int i = 0; i < n; i++) {
                     this.ops.get(i).run();
                  }

                  this.ops.clear();
               } else {
                  if (pzopt.MovingProf.active) pzopt.MovingProf.sprites(this.texture0, this.length); // pzopt: devMovingProf, a sprite batch of the moving pass (dev rig)
                  if (pzopt.HordeDraw.LOG) pzopt.HordeDraw.other(); // pzopt: horde model draws, dev: a sprite batch between character draws
                  if (pzopt.HordeBatch.pending != 0) pzopt.HordeBatch.beforeSprites(); // pzopt: hordeBatch, an exact run of zombie draws ends before a sprite batch
                  if (pzopt.AtlasBatch.pending != 0) pzopt.AtlasBatch.beforeSprites(); // pzopt: atlasBatch, a run of atlas quads ends before a sprite batch
                  if (this.style != RingBuffer.this.lastRenderedStyle) {
                     if (RingBuffer.this.lastRenderedStyle != null
                        && (
                           !SpriteRenderer.RingBuffer.ignoreStyles
                              || RingBuffer.this.lastRenderedStyle != AdditiveStyle.instance
                                 && RingBuffer.this.lastRenderedStyle != TransparentStyle.instance
                                 && RingBuffer.this.lastRenderedStyle != LightingStyle.instance
                        )) {
                        RingBuffer.this.lastRenderedStyle.resetState();
                     }

                     if (this.style != null
                        && (
                           !SpriteRenderer.RingBuffer.ignoreStyles
                              || this.style != AdditiveStyle.instance && this.style != TransparentStyle.instance && this.style != LightingStyle.instance
                        )) {
                        this.style.setupState();
                     }

                     RingBuffer.this.lastRenderedStyle = this.style;
                  }

                  if (RingBuffer.this.lastRenderedTexture0 != null && RingBuffer.this.lastRenderedTexture0.getID() != Texture.lastTextureID) {
                     RingBuffer.this.restoreBoundTextures = true;
                  }

                  if (RingBuffer.this.restoreBoundTextures) {
                     Texture.lastTextureID = 0;
                     GL11.glBindTexture(3553, 0);
                     if (this.texture0 == null) {
                        GL11.glDisable(3553);
                     }

                     if (DefaultShader.isActive) {
                        SceneShaderStore.defaultShader.setTextureActive(false);
                     }

                     RingBuffer.this.lastRenderedTexture0 = null;
                     RingBuffer.this.lastRenderedTexture1 = null;
                     RingBuffer.this.lastRenderedTexture2 = null;
                     RingBuffer.this.restoreBoundTextures = false;
                  }

                  if (DefaultShader.isActive) {
                     SceneShaderStore.defaultShader.setZ(this.z);
                     SceneShaderStore.defaultShader.setChunkDepth(this.chunkDepth);
                  }

                  if (this.texture0 != RingBuffer.this.lastRenderedTexture0) {
                     if (this.texture0 != null) {
                        if (RingBuffer.this.lastRenderedTexture0 == null) {
                           GL11.glEnable(3553);
                        }

                        this.texture0.bind();
                        if (DefaultShader.isActive) {
                           SceneShaderStore.defaultShader.setTextureActive(true);
                        }
                     } else {
                        GL11.glDisable(3553);
                        Texture.lastTextureID = 0;
                        GL11.glBindTexture(3553, 0);
                        if (DefaultShader.isActive) {
                           SceneShaderStore.defaultShader.setTextureActive(false);
                        }
                     }

                     RingBuffer.this.lastRenderedTexture0 = this.texture0;
                  }

                  if (DebugOptions.instance.checks.boundTextures.getValue()) {
                     RingBuffer.this.debugBoundTexture(RingBuffer.this.lastRenderedTexture0, 33984);
                  }

                  if (this.texture1 != RingBuffer.this.lastRenderedTexture1) {
                     GL13.glActiveTexture(33985);
                     if (this.texture1 != null) {
                        GL11.glBindTexture(3553, this.texture1.getID());
                     } else {
                        GL11.glDisable(3553);
                     }

                     GL13.glActiveTexture(33984);
                     RingBuffer.this.lastRenderedTexture1 = this.texture1;
                  }

                  if (this.texture2 != RingBuffer.this.lastRenderedTexture2) {
                     GL13.glActiveTexture(33986);
                     if (this.texture2 != null) {
                        GL11.glBindTexture(3553, this.texture2.getID());
                     } else {
                        GL11.glDisable(3553);
                     }

                     GL13.glActiveTexture(33984);
                     RingBuffer.this.lastRenderedTexture2 = this.texture2;
                  }

                  if (this.length != 0) {
                     if (this.length == -1) {
                        RingBuffer.this.restoreVbos = true;
                     } else {
                        if (RingBuffer.this.restoreVbos) {
                           RingBuffer.this.restoreVbos = false;
                           RingBuffer.this.vbo[RingBuffer.this.sequence].bind();
                           RingBuffer.this.ibo[RingBuffer.this.sequence].bind();
                           GL13.glActiveTexture(33984);
                           GL20.glVertexAttribPointer(0, 2, 5126, false, 36, 0L);
                           GL20.glVertexAttribPointer(1, 2, 5126, false, 36, 8L);
                           GL20.glVertexAttribPointer(2, 4, 5121, true, 36, 16L);
                           GL20.glVertexAttribPointer(3, 2, 5126, false, 36, 20L);
                           GL20.glVertexAttribPointer(4, 2, 5126, false, 36, 28L);
                        }

                        assert GL11.glGetInteger(34964) == RingBuffer.this.vbo[RingBuffer.this.sequence].getID();
                        if (this.style.getRenderSprite()) {
                           try (AbstractPerformanceProfileProbe i = RingBuffer.this.drawRangleElements.profile()) { // pzopt: decompiler fix, the jar's try-with-resources (Vineflower's expansion compiled to other bytecode)
                              RingBuffer.this.drawElements(this.start, this.length, this.startIndex, this.endIndex);
                           } // pzopt: decompiler fix
                        } else {
                           this.style.render(this.start, this.startIndex);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public enum WallShaderTexRender {
      All,
      LeftOnly,
      RightOnly;
   }

   private static final class s_performance {
      static final PerformanceProfileProbe ringBufferBegin = new PerformanceProfileProbe("RingBuffer.begin");
      static final PerformanceProfileProbe ringBufferNext = new PerformanceProfileProbe("RingBuffer.next");
      static final PerformanceProfileProbe ringBufferRender = new PerformanceProfileProbe("RingBuffer.render");
      static final PerformanceProfileProbe spriteRendererBuildDrawBuffer = new PerformanceProfileProbe("SpriteRenderer.buildDrawBuffer");
   }
}
