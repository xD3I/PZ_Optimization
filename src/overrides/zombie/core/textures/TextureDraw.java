package zombie.core.textures;

import gnu.trove.map.hash.TIntObjectHashMap;
import imgui.ImDrawData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.Display;
import zombie.GameProfiler;
import zombie.IndieGL;
import zombie.GameProfiler.ProfileArea;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.SpriteRenderer.RingBuffer;
import zombie.core.SpriteRenderer.WallShaderTexRender;
import zombie.core.logger.ExceptionLogger;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.rendering.RenderList;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.ModelManager.ModelSlot;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstanceRenderDataList;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.debug.DebugContext;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.ui.UIManager;
import zombie.util.list.PZArrayUtil;
import zombie.viewCone.ViewConeTextureFBO;

public final class TextureDraw {
   public int pzoptOutline; // pzopt: occluded outlines, this player's sight of the zombie snapshotted on the game thread (0 none, 1 outline, 2 with the plant slack)
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.textures.TextureDraw");
   }

   public static float nextZ;
   public int pzoptShadowTile = -1; // pzopt: sunShadowMeshes, DrawModel: the caster's tile of the sun shadow atlas (-1: none)
   public float pzoptShadowX, pzoptShadowY, pzoptShadowZ, pzoptShadowHalf; // pzopt: its centre (world, z metric) and half size
   public int pzoptMirrorPlanes; // pzopt: mirrors, DrawModel: the frame's reflector planes the model shows in (bits; pzopt.Mirrors)
   public int pzoptLampN; // pzopt: sunShadowLampMeshes, DrawModel: the caster's lamp views to draw (ShadowAtlas.renderLamps)
   public float[] pzoptLampDraw; // pzopt: per view tile, centre x, y, z, half size, lamp x, y, z
   public static float nextChunkDepth;

   /** pzopt: tileRecordParallel, the depth of the next draw on this thread (a recording thread keeps its own). */
   public static void pzoptSetNextZ(float z) { // pzopt
      pzopt.DrawRecorder r; // pzopt
      if (pzopt.DrawRecorder.recording && (r = pzopt.DrawRecorder.currentRecorder()) != null) { // pzopt
         r.depthSetZ(z); // pzopt
      } else { // pzopt
         nextZ = z; // pzopt
      } // pzopt
   } // pzopt

   public static void pzoptSetNextChunkDepth(float d) { // pzopt
      pzopt.DrawRecorder r; // pzopt
      if (pzopt.DrawRecorder.recording && (r = pzopt.DrawRecorder.currentRecorder()) != null) { // pzopt
         r.depthSetChunkDepth(d); // pzopt
      } else { // pzopt
         nextChunkDepth = d; // pzopt
      } // pzopt
   } // pzopt

   public static float pzoptNextChunkDepth() { // pzopt
      pzopt.DrawRecorder r; // pzopt
      return pzopt.DrawRecorder.recording && (r = pzopt.DrawRecorder.currentRecorder()) != null ? r.nextChunkDepth : nextChunkDepth; // pzopt
   } // pzopt

   private static void pzoptTakeDepth(TextureDraw texd) { // pzopt
      pzopt.DrawRecorder r; // pzopt
      if (pzopt.DrawRecorder.recording && (r = pzopt.DrawRecorder.currentRecorder()) != null) { // pzopt
         r.depthTake(texd); // pzopt
         return; // pzopt
      } // pzopt
      texd.z = nextZ; // pzopt: stock
      texd.chunkDepth = nextChunkDepth; // pzopt
      nextZ = 0.0F; // pzopt
      nextChunkDepth = 0.0F; // pzopt
   } // pzopt
   public TextureDraw.Type type = TextureDraw.Type.glDraw;
   public boolean flipped;
   public int a;
   public int b;
   public float f1;
   public float[] vars;
   public int c;
   public int d;
   public int col0;
   public int col1;
   public int col2;
   public int col3;
   public float x0;
   public float x1;
   public float x2;
   public float x3;
   public float y0;
   public float y1;
   public float y2;
   public float y3;
   public float u0;
   public float u1;
   public float u2;
   public float u3;
   public float v0;
   public float v1;
   public float v2;
   public float v3;
   public float z;
   public float chunkDepth;
   public Texture tex;
   public Texture tex1;
   public Texture tex2;
   public byte useAttribArray;
   public float tex1U0;
   public float tex1U1;
   public float tex1U2;
   public float tex1U3;
   public float tex1V0;
   public float tex1V1;
   public float tex1V2;
   public float tex1V3;
   public int tex1Col0;
   public int tex1Col1;
   public int tex1Col2;
   public int tex1Col3;
   public float tex2U0;
   public float tex2U1;
   public float tex2U2;
   public float tex2U3;
   public float tex2V0;
   public float tex2V1;
   public float tex2V2;
   public float tex2V3;
   public boolean singleCol;
   public ImDrawData imDrawData;
   public PerformanceProfileProbe probe;
   public TextureDraw.GenericDrawer drawer;
   public Future<?> future;
   // Model slot initialization is awaited by the render path; keep it distinct from background file I/O.
   private static final AtomicInteger slotInitNext = new AtomicInteger();
   private static final ExecutorService slotInitExec = Executors.newFixedThreadPool(8,
         r -> new Thread(r, "pzopt-slotinit-" + slotInitNext.getAndIncrement()));

   public static void glStencilFunc(TextureDraw texd, int a, int b, int c) {
      texd.type = TextureDraw.Type.glStencilFunc;
      texd.a = a;
      texd.b = b;
      texd.c = c;
   }

   public static void glBuffer(TextureDraw texd, int a, int b) {
      texd.type = TextureDraw.Type.glBuffer;
      texd.a = a;
      texd.b = b;
   }

   public static void glStencilOp(TextureDraw texd, int a, int b, int c) {
      texd.type = TextureDraw.Type.glStencilOp;
      texd.a = a;
      texd.b = b;
      texd.c = c;
   }

   public static void glDisable(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glDisable;
      texd.a = a;
   }

   public static void glClear(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glClear;
      texd.a = a;
   }

   public static void glBindFramebuffer(TextureDraw texd, int binding, int fbo) {
      texd.type = TextureDraw.Type.glBindFramebuffer;
      texd.a = binding;
      texd.b = fbo;
   }

   public static void glClearDepth(TextureDraw texd, float d) {
      texd.type = TextureDraw.Type.glClearDepth;
      texd.u0 = d;
   }

   public static void glClearColor(TextureDraw texd, int r, int g, int b, int a) {
      texd.type = TextureDraw.Type.glClearColor;
      texd.col0 = r;
      texd.col1 = g;
      texd.col2 = b;
      texd.col3 = a;
   }

   public static void NewFrame(TextureDraw texd) {
      texd.type = TextureDraw.Type.NewFrame;
   }

   public static void glDepthFunc(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glDepthFunc;
      texd.a = a;
   }

   public static void glEnable(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glEnable;
      texd.a = a;
   }

   public static void glAlphaFunc(TextureDraw texd, int a, float b) {
      texd.type = TextureDraw.Type.glAlphaFunc;
      texd.a = a;
      texd.f1 = b;
   }

   public static void glColorMask(TextureDraw texd, int a, int b, int c, int d) {
      texd.type = TextureDraw.Type.glColorMask;
      texd.a = a;
      texd.b = b;
      texd.c = c;
      texd.x0 = d;
   }

   public static void glStencilMask(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glStencilMask;
      texd.a = a;
   }

   public static void glBlendFunc(TextureDraw texd, int a, int b) {
      texd.type = TextureDraw.Type.glBlendFunc;
      texd.a = a;
      texd.b = b;
   }

   public static void glBlendFuncSeparate(TextureDraw texd, int a, int b, int c, int d) {
      texd.type = TextureDraw.Type.glBlendFuncSeparate;
      texd.a = a;
      texd.b = b;
      texd.c = c;
      texd.d = d;
   }

   public static void glBlendEquation(TextureDraw texd, int a) {
      texd.type = TextureDraw.Type.glBlendEquation;
      texd.a = a;
   }

   public static void pushIsoView(TextureDraw texd, float ox, float oy, float oz, float useangle, boolean vehicle) {
      texd.type = TextureDraw.Type.pushIsoView;
      texd.u0 = ox;
      texd.u1 = oy;
      texd.u2 = oz;
      texd.u3 = useangle;
      texd.a = vehicle ? 1 : 0;
   }

   public static void popIsoView(TextureDraw texd) {
      texd.type = TextureDraw.Type.popIsoView;
   }

   public static void glDoEndFrame(TextureDraw texd) {
      texd.type = TextureDraw.Type.glDoEndFrame;
   }

   public static void glDoEndFrameFx(TextureDraw texd, int player) {
      texd.type = TextureDraw.Type.glDoEndFrameFx;
      texd.c = player;
   }

   public static void glIgnoreStyles(TextureDraw texd, boolean b) {
      texd.type = TextureDraw.Type.glIgnoreStyles;
      texd.a = b ? 1 : 0;
   }

   public static void glDoStartFrame(TextureDraw texd, int w, int h, float zoom, int player) {
      glDoStartFrame(texd, w, h, zoom, player, false);
   }

   public static void glDoStartFrameNoZoom(TextureDraw texd, int w, int h, float zoom, int player) {
      texd.type = TextureDraw.Type.glDoStartFrameNoZoom;
      texd.a = w;
      texd.b = h;
      texd.f1 = zoom;
      texd.c = player;
   }

   public static void glDoStartFrameFlipY(TextureDraw texd, int x, int y, float zoom, int player) {
      texd.type = TextureDraw.Type.glDoStartFrameFlipY;
      texd.a = x;
      texd.b = y;
      texd.f1 = zoom;
      texd.c = player;
   }

   public static void glDoStartFrame(TextureDraw texd, int w, int h, float zoom, int player, boolean isTextFrame) {
      if (isTextFrame) {
         texd.type = TextureDraw.Type.glDoStartFrameText;
      } else {
         texd.type = TextureDraw.Type.glDoStartFrame;
      }

      texd.a = w;
      texd.b = h;
      texd.f1 = zoom;
      texd.c = player;
   }

   public static void glDoStartFrameFx(TextureDraw texd, int w, int h, int player) {
      texd.type = TextureDraw.Type.glDoStartFrameFx;
      texd.a = w;
      texd.b = h;
      texd.c = player;
   }

   public static void glTexParameteri(TextureDraw texd, int a, int b, int c) {
      texd.type = TextureDraw.Type.glTexParameteri;
      texd.a = a;
      texd.b = b;
      texd.c = c;
   }

   public static void drawModel(TextureDraw texd, ModelSlot modelSlot) {
      texd.type = TextureDraw.Type.DrawModel;
      texd.a = modelSlot.id;
      texd.pzoptOutline = pzopt.OccludedOutline.eligible(modelSlot.character, zombie.iso.IsoCamera.frameState.playerIndex); // pzopt: occluded outlines, do not read mutable visibility on the render thread
      texd.b = pzopt.ObjectMotion.record(modelSlot); // pzopt: upscaler, the stencil id of the model's object motion (0 = none)
      pzopt.CapsuleShadow.tileFor(modelSlot, texd); // pzopt: sunShadowMeshes, the caster's atlas tile for its sun draw (-1 none)
      pzopt.Mirrors.planesFor(modelSlot, texd); // pzopt: mirrors, the reflectors this model shows in
      // pzopt: charDrawPrep. The draw data of a zombie the workers already built and initialised this frame
      // (pzopt.CharDraw, from FBORenderCell.renderMovingObjects) goes straight into the slot; nothing to init, no
      // future for the render thread to wait on. Everyone else takes the stock path below.
      ModelSlotRenderData pzoptPrepared = pzopt.CharDraw.take(modelSlot);
      if (pzoptPrepared != null) {
         texd.drawer = pzoptPrepared;
         texd.future = null;
         return;
      }

      ModelSlotRenderData slotData = ModelSlotRenderData.alloc();
      slotData.initModel(modelSlot);
      texd.drawer = slotData;
      if (DebugOptions.instance.threadModelSlotInit.getValue()) {
         long pzoptQueued = System.nanoTime(); // pzopt: dev timing (pzopt.ModelInitOrder)
         texd.future = slotInitExec.submit(() -> {
            long pzoptStart = System.nanoTime(); // pzopt: dev timing
            synchronized (slotData) {
               try { // pzopt: dev, which model's init fails (the 2026-10-02 playerData NPE)
                  pzopt.ModelInitOrder.devLocked(modelSlot, pzoptQueued, pzoptStart); // pzopt: dev timing
                  slotData.init(modelSlot);
                  pzopt.ModelInitOrder.devDone(modelSlot); // pzopt: dev timing
               } catch (RuntimeException pzoptE) { // pzopt: dev
                  pzopt.CharDraw.devInitFailed(modelSlot, pzoptE); // pzopt: dev
                  throw pzoptE; // pzopt: dev
               } // pzopt: dev
            }
         });
         pzopt.ModelInitOrder.queued(modelSlot, texd.future); // pzopt: ModelManager.Reset waits for it before re-dressing the slot
      } else {
         slotData.init(modelSlot);
      }
   }

   public static void drawSkyBox(TextureDraw texd, Shader shader, int userId, int apiId, int bufferId) {
      texd.type = TextureDraw.Type.DrawSkyBox;
      texd.a = shader.getID();
      texd.b = userId;
      texd.c = apiId;
      texd.d = bufferId;
      texd.drawer = null;
   }

   public static void drawWater(TextureDraw texd, Shader shader, int playerIndex, int firstSquare, int numSquares, boolean bShore) {
      texd.type = TextureDraw.Type.DrawWater;
      texd.a = shader.getID();
      texd.b = playerIndex;
      texd.c = firstSquare;
      texd.d = numSquares;
      texd.flipped = bShore;
      texd.drawer = null;
   }

   public static void drawPuddles(TextureDraw texd, int playerIndex, int z, int firstSquare, int numSquares) {
      texd.type = TextureDraw.Type.DrawPuddles;
      texd.a = playerIndex;
      texd.b = z;
      texd.c = firstSquare;
      texd.d = numSquares;
      texd.drawer = null;
   }

   public static void drawParticles(TextureDraw texd, int userId, int var1, int var2) {
      texd.type = TextureDraw.Type.DrawParticles;
      texd.b = userId;
      texd.c = var1;
      texd.d = var2;
      texd.drawer = null;
   }

   public static void StartShader(TextureDraw texd, int iD) {
      texd.type = TextureDraw.Type.StartShader;
      texd.a = iD;
      if (pzopt.Sway.frameOn) { // pzopt: foliage sway, a patched bake program gets its sway uniform
         ShaderUniformSetter pzoptU = pzopt.Sway.startShader(texd, iD, null); // pzopt
         if (pzoptU != null) texd.drawer = pzoptU; // pzopt
      } else texd.c = 0; // pzopt
   }

   public static void StartShader(TextureDraw texd, int iD, ShaderUniformSetter uniforms) {
      texd.type = TextureDraw.Type.StartShader;
      texd.a = iD;
      texd.drawer = pzopt.Sway.frameOn ? pzopt.Sway.startShader(texd, iD, uniforms) : uniforms; // pzopt: foliage sway, a patched bake program gets its sway uniform
      if (!pzopt.Sway.frameOn) texd.c = 0; // pzopt
   }

   public static void ShaderUpdate1i(TextureDraw texd, int shaderID, int uniform, int uniformValue) {
      texd.type = TextureDraw.Type.ShaderUpdate;
      texd.a = shaderID;
      texd.b = uniform;
      texd.c = -1;
      texd.d = uniformValue;
   }

   public static void ShaderUpdate1f(TextureDraw texd, int shaderID, int uniform, float uniformValue) {
      texd.type = TextureDraw.Type.ShaderUpdate;
      texd.a = shaderID;
      texd.b = uniform;
      texd.c = 1;
      texd.u0 = uniformValue;
   }

   public static void ShaderUpdate2f(TextureDraw texd, int shaderID, int uniform, float value1, float value2) {
      texd.type = TextureDraw.Type.ShaderUpdate;
      texd.a = shaderID;
      texd.b = uniform;
      texd.c = 2;
      texd.u0 = value1;
      texd.u1 = value2;
   }

   public static void ShaderUpdate3f(TextureDraw texd, int shaderID, int uniform, float value1, float value2, float value3) {
      texd.type = TextureDraw.Type.ShaderUpdate;
      texd.a = shaderID;
      texd.b = uniform;
      texd.c = 3;
      texd.u0 = value1;
      texd.u1 = value2;
      texd.u2 = value3;
   }

   public static void ShaderUpdate4f(TextureDraw texd, int shaderID, int uniform, float value1, float value2, float value3, float value4) {
      texd.type = TextureDraw.Type.ShaderUpdate;
      texd.a = shaderID;
      texd.b = uniform;
      texd.c = 4;
      texd.u0 = value1;
      texd.u1 = value2;
      texd.u2 = value3;
      texd.u3 = value4;
   }

   public static void FBORenderChunkStart(TextureDraw textureDraw, int index, boolean bClear) {
      textureDraw.type = TextureDraw.Type.FBORenderChunkStart;
      textureDraw.a = index;
      textureDraw.b = bClear ? 1 : 0;
   }

   public static void FBORenderChunkEnd(TextureDraw textureDraw) {
      textureDraw.type = TextureDraw.Type.FBORenderChunkEnd;
      textureDraw.drawer = pzopt.Sway.takeMask(); // pzopt: foliage sway, the bake's tile marks ride on its end draw
   }

   public static void releaseFBORenderChunkLock(TextureDraw textureDraw) {
      textureDraw.type = TextureDraw.Type.releaseFBORenderChunkLock;
   }

   public void run() {
      if (pzopt.UniformCache.ON && this.type != TextureDraw.Type.StartShader) pzopt.UniformCache.reset(); // pzopt: uniformCache, any other command may touch uniforms
      if (pzopt.Sway.mrt) pzopt.Sway.beforeOp(this); // pzopt: foliage sway, a model / generic drawer binds its own program: one draw buffer again
      switch (this.type) {
         case glBuffer:
            if (Core.getInstance().supportsFBO()) {
               if (this.a == 1) {
                  SpriteRenderer.instance.getRenderingState().fbo.startDrawing(false, false);
               } else if (this.a == 2) {
                  UIManager.uiFbo.startDrawing(true, true);
               } else if (this.a == 3) {
                  UIManager.uiFbo.endDrawing();
               } else if (this.a == 4) {
                  WeatherFxMask.getFboMask().startDrawing(true, true);
               } else if (this.a == 5) {
                  WeatherFxMask.getFboMask().endDrawing();
               } else if (this.a == 6) {
                  WeatherFxMask.getFboParticles().startDrawing(true, true);
               } else if (this.a == 7) {
                  WeatherFxMask.getFboParticles().endDrawing();
               } else if (this.a == 8) {
                  ViewConeTextureFBO.instance.startDrawing();
               } else if (this.a == 9) {
                  ViewConeTextureFBO.instance.stopDrawing();
               } else if (this.a == 10) {
                  FBORenderChunkManager.instance.startDrawingCombined();
               } else if (this.a == 11) {
                  FBORenderChunkManager.instance.endDrawingCombined();
               } else if (this.a == 12) {
                  if (Core.isUseGameViewport()) {
                     DebugContext.instance.endDrawing();
                  }
               } else {
                  SpriteRenderer.instance.getRenderingState().fbo.endDrawing();
                  if (Core.isUseGameViewport()) {
                     DebugContext.instance.startDrawing();
                  }
               }
            }
            break;
         case glStencilFunc:
            GLStateRenderThread.StencilFunc.set(this.a, this.b, this.c);
            break;
         case glAlphaFunc:
            GLStateRenderThread.AlphaFunc.set(this.a, this.f1);
            break;
         case glStencilOp:
            GLStateRenderThread.StencilOp.set(this.a, this.b, this.c);
            break;
         case glEnable:
            if (this.a == 3008) {
               GLStateRenderThread.AlphaTest.set(true);
            } else if (this.a == 3042) {
               GLStateRenderThread.Blend.set(true);
            } else if (this.a == 2929) {
               GLStateRenderThread.DepthTest.set(true);
            } else if (this.a == 3089) {
               GLStateRenderThread.ScissorTest.set(true);
            } else if (this.a == 2960) {
               GLStateRenderThread.StencilTest.set(true);
            } else {
               IndieGL.glEnableA(this.a);
            }
            break;
         case glDisable:
            if (this.a == 3008) {
               GLStateRenderThread.AlphaTest.set(false);
            } else if (this.a == 3042) {
               GLStateRenderThread.Blend.set(false);
            } else if (this.a == 2929) {
               GLStateRenderThread.DepthTest.set(false);
            } else if (this.a == 3089) {
               GLStateRenderThread.ScissorTest.set(false);
            } else if (this.a == 2960) {
               GLStateRenderThread.StencilTest.set(false);
            } else {
               IndieGL.glDisableA(this.a);
            }
            break;
         case glColorMask:
            GLStateRenderThread.ColorMask.set(this.a == 1, this.b == 1, this.c == 1, this.x0 == 1.0F);
            if (pzopt.Sway.mrt) pzopt.Sway.afterColorMask(); // pzopt: foliage sway, the attribute attachment keeps its own mask
            break;
         case glStencilMask:
            GLStateRenderThread.StencilMask.set(this.a);
            break;
         case glClear:
            IndieGL.glClearA(this.a);
            break;
         case glBindFramebuffer:
            GL30.glBindFramebuffer(this.a, this.b);
            break;
         case glBlendFunc:
            GLStateRenderThread.BlendFuncSeparate.set(this.a, this.b, this.a, this.b);
            break;
         case glDoStartFrame:
            Core.getInstance().DoStartFrameStuff(this.a, this.b, this.f1, this.c);
            pzopt.RenderScale.afterStartFrame(this.c); // pzopt: upscaler, the world pass at a fraction of the screen (scaled viewport + scissor)
            break;
         case glDoStartFrameText:
            Core.getInstance().DoStartFrameStuff(this.a, this.b, this.f1, this.c, true);
            break;
         case glDoEndFrame:
            Core.getInstance().DoEndFrameStuff(this.a, this.b);
            pzopt.RenderScale.afterEndFrame(); // pzopt: upscaler
            break;
         case glTexParameteri:
            IndieGL.glTexParameteriActual(this.a, this.b, this.c);
            break;
         case StartShader:
            int pzoptProgram = pzopt.Sway.remap(pzopt.SpriteFilter.remap(this.a), this); // pzopt: sprite filter, the chunk composite runs this frame's zoom variant; foliage sway, its variant for a texture with swaying plants
            if (pzopt.DrawStats.ON) pzopt.DrawStats.shaderStart(pzoptProgram); // pzopt: instrumented runs, draw-call census
            ShaderHelper.glUseProgramObjectARB(pzoptProgram); // pzopt: sprite filter
            pzopt.Sway.onProgram(pzoptProgram, this); // pzopt: foliage sway, both draw buffers while a patched program bakes into a texture with sway attributes
            boolean pzoptSame = pzopt.UniformCache.ON && pzopt.UniformCache.startProgram(pzoptProgram); // pzopt: uniformCache
            if (Shader.ShaderMap.containsKey(pzoptProgram)) { // pzopt: sprite filter
               Shader pzoptShader = (Shader)Shader.ShaderMap.get(pzoptProgram); // pzopt
               if (pzoptSame && pzoptShader instanceof zombie.tileDepth.TileDepthShader) { // pzopt: uniformCache, its samplers (0 / 1) are set on this program already
                  zombie.core.skinnedmodel.model.VertexBufferObject.setModelViewProjection(pzoptShader.getProgram()); // pzopt: the rest of TileDepthShader.startRenderThread
                  pzopt.UniformCache.samplerSkips++; // pzopt
               } else { // pzopt
                  pzoptShader.startRenderThread(this);
               } // pzopt
            }

            if (this.a == 0) {
               SpriteRenderer.ringBuffer.checkShaderChangedTexture1();
            }

            if (this.drawer instanceof ShaderUniformSetter uniforms) {
               if (pzopt.UniformCache.ON) { // pzopt: uniformCache
                  uniforms.pzoptInvokeAllCached(); // pzopt
               } else { // pzopt
                  uniforms.invokeAll();
               } // pzopt
            }
            break;
         case glLoadIdentity:
            GL11.glLoadIdentity();
         case glGenerateMipMaps:
         default:
            break;
         case glBind:
            GL11.glBindTexture(3553, this.a);
            Texture.lastlastTextureID = Texture.lastTextureID;
            Texture.lastTextureID = this.a;
            break;
         case glViewport:
            pzopt.RenderScale.requestedViewport(this.a, this.b, this.c, this.d); // pzopt: upscaler, a screen rect restore inside the scaled world pass is scaled too
            break;
         case DrawModel:
            pzopt.CarOccupant.markDrawModel(); // pzopt: carOccupant, dev timing (the slot init wait of an occupant's draw)
            if (this.drawer != null) {
               if (this.future != null) {
                  ProfileArea var13 = GameProfiler.getInstance().profile("Wait");

                  try {
                     this.GetFutureResultThrow();
                  } catch (Throwable var11) {
                     if (var13 != null) {
                        try {
                           var13.close();
                        } catch (Throwable var5) {
                           var11.addSuppressed(var5);
                        }
                     }

                     throw var11;
                  }

                  if (var13 != null) {
                     var13.close();
                  }
               }

               if (pzopt.CarOccupant.capture(this)) { // pzopt: carOccupant, a seated character drawn into its car's impostor tile instead of the world
                  break; // pzopt
               } // pzopt
               int pzoptMotionId = this.b > 0 && pzopt.RenderScale.inWorldPass() ? this.b : 0; // pzopt: upscaler, object motion vectors
               if (pzoptMotionId > 0) {
                  pzopt.ObjectMotion.beginStencil(pzoptMotionId);
               }
               boolean pzoptOutline = pzopt.OccludedOutline.beginModel(this); // pzopt: occluded outlines, the stencil codes this model's meshes write
               synchronized (this.drawer) {
                  this.drawer.render(this);
               }
               if (pzoptOutline) pzopt.OccludedOutline.endModel(); // pzopt
               if (pzoptMotionId > 0) {
                  pzopt.ObjectMotion.endStencil();
               }
               if (this.pzoptShadowTile >= 0) { // pzopt: sunShadowMeshes, the same model from the sun into its atlas tile
                  pzopt.ShadowAtlas.renderCaster(this, this.pzoptShadowTile, this.pzoptShadowX, this.pzoptShadowY, this.pzoptShadowZ, this.pzoptShadowHalf); // pzopt
               } // pzopt
               if (this.pzoptLampN > 0) { // pzopt: sunShadowLampMeshes, the same model from its lamps
                  pzopt.ShadowAtlas.renderLamps(this); // pzopt
               } // pzopt
               if (this.pzoptMirrorPlanes != 0) { // pzopt: mirrors, the same model through its reflectors' planes (drawn at the frame's flush)
                  pzopt.Mirrors.renderModel(this); // pzopt
               } // pzopt
               pzopt.RenderScale.afterModelDraw(); // pzopt: upscaler, an imposter card render restores an integer viewport: put the jittered one back
            }
            break;
         case DrawSkyBox:
            try {
               ModelManager.instance.RenderSkyBox(this, this.a, this.b, this.c, this.d);
            } catch (Exception ex) {
               DebugType.General.printException(ex, LogSeverity.Error);
            }
            break;
         case DrawWater:
            try {
               ModelManager.instance.RenderWater(this, this.a, this.b, this.c, this.d, this.flipped);
            } catch (Exception ex) {
               DebugType.General.printException(ex, LogSeverity.Error);
            }
            break;
         case DrawPuddles:
            try {
               ModelManager.instance.RenderPuddles(this.a, this.b, this.c, this.d);
            } catch (Exception ex) {
               DebugType.General.printException(ex, LogSeverity.Error);
            }
            break;
         case DrawParticles:
            try {
               ModelManager.instance.RenderParticles(this, this.b, this.c);
            } catch (Exception ex) {
               DebugType.General.printException(ex, LogSeverity.Error);
            }
            break;
         case ShaderUpdate:
            GL20.glUseProgram(this.a);
            if (this.c == 1) {
               GL20.glUniform1f(this.b, this.u0);
            }

            if (this.c == 2) {
               GL20.glUniform2f(this.b, this.u0, this.u1);
            }

            if (this.c == 3) {
               GL20.glUniform3f(this.b, this.u0, this.u1, this.u2);
            }

            if (this.c == 4) {
               GL20.glUniform4f(this.b, this.u0, this.u1, this.u2, this.u3);
            }

            if (this.c == -1) {
               GL20.glUniform1i(this.b, this.d);
            }
            break;
         case BindActiveTexture:
            GL13.glActiveTexture(this.a);
            if (this.b != -1) {
               GL11.glBindTexture(3553, this.b);
            }

            GL13.glActiveTexture(33984);
            break;
         case glBlendEquation:
            GL14.glBlendEquation(this.a);
            break;
         case glDoStartFrameFx:
            Core.getInstance().DoStartFrameStuffSmartTextureFx(this.a, this.b, this.c);
            break;
         case glDoEndFrameFx:
            Core.getInstance().DoEndFrameStuffFx(this.a, this.b, this.c);
            break;
         case glIgnoreStyles:
            RingBuffer.ignoreStyles = this.a == 1;
            break;
         case glClearColor:
            GL11.glClearColor(this.col0 / 255.0F, this.col1 / 255.0F, this.col2 / 255.0F, this.col3 / 255.0F);
            break;
         case glBlendFuncSeparate:
            GLStateRenderThread.BlendFuncSeparate.set(this.a, this.b, this.c, this.d);
            break;
         case glDepthMask:
            GLStateRenderThread.DepthMask.set(this.a == 1);
            break;
         case doCoreIntParam:
            Core.getInstance().floatParamMap.put(this.a, this.f1);
            break;
         case drawTerrain:
            IsoWorld.instance.renderTerrain();
            break;
         case pushIsoView:
            Core.getInstance().DoPushIsoStuff2D(this.u0, this.u1, this.u2, this.u3, this.a == 1);
            break;
         case popIsoView:
            Core.getInstance().DoPopIsoStuff();
            break;
         case FBORenderChunkEnd:
            if (pzopt.DrawStats.ON) pzopt.DrawStats.bakeEnd(); // pzopt: instrumented runs, draw-call census
            pzopt.Sway.chunkEnd(this); // pzopt: foliage sway, the bake's tile marks, back to one draw buffer while the chunk's FBO is still bound
            FBORenderChunkManager.instance.renderThreadChunkEnd();
            break;
         case FBORenderChunkStart:
            if (pzopt.DrawStats.ON) pzopt.DrawStats.bakeStart(); // pzopt: instrumented runs, draw-call census
            FBORenderChunkManager.instance.renderThreadChunkStart(this.a, this.b == 1);
            pzopt.Sway.chunkStart(this.b == 1); // pzopt: foliage sway, this texture's sway attributes (cleared with the bake)
            break;
         case glDoStartFrameNoZoom:
            Core.getInstance().DoStartFrameNoZoom(this.a, this.b, this.f1, this.c, false, false, false);
            pzopt.RenderScale.afterStartFrame(this.c); // pzopt: upscaler
            break;
         case glDoStartFrameFlipY:
            Core.getInstance().StartFrameFlipY(this.a, this.b, this.f1, this.c);
            break;
         case releaseFBORenderChunkLock:
            TIntObjectHashMap<FBORenderChunk> indexToChunk = SpriteRenderer.instance.getRenderingState().cachedRenderChunkIndexMap;
            indexToChunk.forEachValue(fboRenderChunk -> {
               fboRenderChunk.submitted = false;
               return true;
            });
            break;
         case glDepthFunc:
            GLStateRenderThread.DepthFunc.set(this.a);
            break;
         case glClearDepth:
            GL11.glClearDepth(this.u0);
            break;
         case NewFrame:
            Core.getInstance().projectionMatrixStack.clear();
            Core.getInstance().modelViewMatrixStack.clear();
            break;
         case DrawQueued:
            if (this.drawer != null && this.drawer instanceof ModelSlotRenderData modelSlotRenderData) {
               DrawQueued(modelSlotRenderData);
            }
            break;
         case RenderQueued:
            RenderList.RenderOpaque();
            RenderList.Reset();
            break;
         case DrawImGui:
            Display.drawImGuiDrawData(this.imDrawData);
            break;
         case BeginProfile:
            this.probe.start();
            break;
         case EndProfile:
            this.probe.end();
      }
   }

   private static void DrawQueued(ModelSlotRenderData slot) {
      slot.checkReady();
      if (slot.canRender()) {
         ModelInstanceRenderDataList modelData = slot.getModelData();

         for (int i = 0; i < modelData.size(); i++) {
            ModelInstanceRenderData inst = (ModelInstanceRenderData)modelData.get(i);
            RenderList.DrawQueued(slot, inst);
         }
      }
   }

   public static void glDepthMask(TextureDraw textureDraw, boolean b) {
      textureDraw.type = TextureDraw.Type.glDepthMask;
      textureDraw.a = b ? 1 : 0;
   }

   public static void doCoreIntParam(TextureDraw textureDraw, int id, float val) {
      textureDraw.type = TextureDraw.Type.doCoreIntParam;
      textureDraw.a = id;
      textureDraw.f1 = val;
   }

   @Override
   public String toString() {
      return this.getClass().getSimpleName()
         + "{ "
         + this.type
         + ", a:"
         + this.a
         + ", b:"
         + this.b
         + ", f1:"
         + this.f1
         + ", vars:"
         + (this.vars != null ? PZArrayUtil.arrayToString(this.vars, "{", "}", ", ") : "null")
         + ", c:"
         + this.c
         + ", d:"
         + this.d
         + ", col0:"
         + this.col0
         + ", col1:"
         + this.col1
         + ", col2:"
         + this.col2
         + ", col3:"
         + this.col3
         + ", x0:"
         + this.x0
         + ", x1:"
         + this.x1
         + ", x2:"
         + this.x2
         + ", x3:"
         + this.x3
         + ", x0:"
         + this.x0
         + ", x1:"
         + this.x1
         + ", x2:"
         + this.x2
         + ", x3:"
         + this.x3
         + ", y0:"
         + this.y0
         + ", y1:"
         + this.y1
         + ", y2:"
         + this.y2
         + ", y3:"
         + this.y3
         + ", u0:"
         + this.u0
         + ", u1:"
         + this.u1
         + ", u2:"
         + this.u2
         + ", u3:"
         + this.u3
         + ", v0:"
         + this.v0
         + ", v1:"
         + this.v1
         + ", v2:"
         + this.v2
         + ", v3:"
         + this.v3
         + ", tex:"
         + this.tex
         + ", tex1:"
         + this.tex1
         + ", useAttribArray:"
         + this.useAttribArray
         + ", tex1_u0:"
         + this.tex1U0
         + ", tex1_u1:"
         + this.tex1U1
         + ", tex1_u2:"
         + this.tex1U2
         + ", tex1_u3:"
         + this.tex1U3
         + ", tex1_v0:"
         + this.tex1V0
         + ", tex1_v1:"
         + this.tex1V1
         + ", tex1_v2:"
         + this.tex1V2
         + ", tex1_v3:"
         + this.tex1V3
         + ", tex1_col0:"
         + this.tex1Col0
         + ", tex1_col1:"
         + this.tex1Col1
         + ", tex1_col2:"
         + this.tex1Col2
         + ", tex1_col3:"
         + this.tex1Col3
         + ", tex2_u0:"
         + this.tex2U0
         + ", tex2_u1:"
         + this.tex2U1
         + ", tex2_u2:"
         + this.tex2U2
         + ", tex2_u3:"
         + this.tex2U3
         + ", tex2_v0:"
         + this.tex2V0
         + ", tex2_v1:"
         + this.tex2V1
         + ", tex2_v2:"
         + this.tex2V2
         + ", tex2_v3:"
         + this.tex2V3
         + ", bSingleCol:"
         + this.singleCol
         + " }";
   }

   public static TextureDraw Create(
      TextureDraw texd, Texture tex, float x, float y, float width, float height, float r, float g, float b, float a, Consumer<TextureDraw> texdModifier
   ) {
      int col0 = Color.colorToABGR(r, g, b, a);
      Create(texd, tex, x, y, x + width, y, x + width, y + height, x, y + height, col0, col0, col0, col0, texdModifier);
      return texd;
   }

   public static TextureDraw Create(
      TextureDraw texd,
      Texture tex,
      WallShaderTexRender wallSection,
      float x,
      float y,
      float width,
      float height,
      float r,
      float g,
      float b,
      float a,
      Consumer<TextureDraw> texdModifier
   ) {
      int col0 = Color.colorToABGR(r, g, b, a);
      float u0 = 0.0F;
      float v0 = 0.0F;
      float u1 = 1.0F;
      float v1 = 0.0F;
      float u2 = 1.0F;
      float v2 = 1.0F;
      float u3 = 0.0F;
      float v3 = 1.0F;
      float x0;
      float y0;
      float x1;
      float y1;
      float x2;
      float y2;
      float x3;
      float y3;
      switch (wallSection) {
         case LeftOnly:
            x3 = x;
            x0 = x;
            y1 = y;
            y0 = y;
            x1 = x2 = x + width / 2.0F;
            y2 = y3 = y + height;
            if (tex != null) {
               float xend = tex.getXEnd();
               float xstart = tex.getXStart();
               float yend = tex.getYEnd();
               float ystart = tex.getYStart();
               float uspan = 0.5F * (xend - xstart);
               u0 = xstart;
               u1 = xstart + uspan;
               u2 = xstart + uspan;
               u3 = xstart;
               v0 = ystart;
               v1 = ystart;
               v2 = yend;
               v3 = yend;
            }
            break;
         case RightOnly:
            x0 = x3 = x + width / 2.0F;
            y1 = y;
            y0 = y;
            x1 = x2 = x + width;
            y2 = y3 = y + height;
            if (tex != null) {
               float xend = tex.getXEnd();
               float xstart = tex.getXStart();
               float yend = tex.getYEnd();
               float ystart = tex.getYStart();
               float uspan = 0.5F * (xend - xstart);
               u0 = xstart + uspan;
               u1 = xend;
               u2 = xend;
               u3 = xstart + uspan;
               v0 = ystart;
               v1 = ystart;
               v2 = yend;
               v3 = yend;
            }
            break;
         case All:
         default:
            x3 = x;
            x0 = x;
            y1 = y;
            y0 = y;
            x1 = x2 = x + width;
            y2 = y3 = y + height;
            if (tex != null) {
               float xend = tex.getXEnd();
               float xstart = tex.getXStart();
               float yend = tex.getYEnd();
               float ystart = tex.getYStart();
               u0 = xstart;
               u1 = xend;
               u2 = xend;
               u3 = xstart;
               v0 = ystart;
               v1 = ystart;
               v2 = yend;
               v3 = yend;
            }
      }

      Create(texd, tex, x0, y0, x1, y1, x2, y2, x3, y3, col0, col0, col0, col0, u0, v0, u1, v1, u2, v2, u3, v3, texdModifier);
      pzoptTakeDepth(texd); // pzopt: tileRecordParallel, a recording thread's depth comes from its own scratch
      return texd;
   }

   public static TextureDraw Create(
      TextureDraw texd,
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
      int col = Color.colorToABGR(r, g, b, a);
      Create(texd, tex, x, y, x + width, y, x + width, y + height, x, y + height, col, col, col, col, u1, v1, u2, v2, u3, v3, u4, v4, texdModifier);
      return texd;
   }

   public static void Create(
      TextureDraw texd,
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
      int col0 = Color.colorToABGR(r1, g1, b1, a1);
      int col1 = Color.colorToABGR(r2, g2, b2, a2);
      int col2 = Color.colorToABGR(r3, g3, b3, a3);
      int col3 = Color.colorToABGR(r4, g4, b4, a4);
      Create(texd, tex, x1, y1, x2, y2, x3, y3, x4, y4, col0, col1, col2, col3, texdModifier);
   }

   public static void Create(
      TextureDraw texd, Texture tex, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, float r1, float g1, float b1, float a1
   ) {
      int col0 = Color.colorToABGR(r1, g1, b1, a1);
      Create(texd, tex, x1, y1, x2, y2, x3, y3, x4, y4, col0, col0, col0, col0, null);
   }

   public static void Create(
      TextureDraw texd, Texture tex, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int c1, int c2, int c3, int c4
   ) {
      Create(texd, tex, x1, y1, x2, y2, x3, y3, x4, y4, c1, c2, c3, c4, null);
   }

   public static TextureDraw Create(
      TextureDraw texd,
      Texture tex,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      float x4,
      float y4,
      int c1,
      int c2,
      int c3,
      int c4,
      Consumer<TextureDraw> texdModifier
   ) {
      float u1 = 0.0F;
      float v1 = 0.0F;
      float u2 = 1.0F;
      float v2 = 0.0F;
      float u3 = 1.0F;
      float v3 = 1.0F;
      float u4 = 0.0F;
      float v4 = 1.0F;
      if (tex != null) {
         float xend = tex.getXEnd();
         float xstart = tex.getXStart();
         float yend = tex.getYEnd();
         float ystart = tex.getYStart();
         u1 = xstart;
         v1 = ystart;
         u2 = xend;
         v2 = ystart;
         u3 = xend;
         v3 = yend;
         u4 = xstart;
         v4 = yend;
      }

      return Create(texd, tex, x1, y1, x2, y2, x3, y3, x4, y4, c1, c2, c3, c4, u1, v1, u2, v2, u3, v3, u4, v4, texdModifier);
   }

   public static TextureDraw Create(
      TextureDraw texd,
      Texture tex,
      float x0,
      float y0,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      int c0,
      int c1,
      int c2,
      int c3,
      float u0,
      float v0,
      float u1,
      float v1,
      float u2,
      float v2,
      float u3,
      float v3,
      Consumer<TextureDraw> texdModifier
   ) {
      texd.singleCol = c0 == c1 && c0 == c2 && c0 == c3;
      texd.tex = tex;
      texd.x0 = x0;
      texd.y0 = y0;
      texd.x1 = x1;
      texd.y1 = y1;
      texd.x2 = x2;
      texd.y2 = y2;
      texd.x3 = x3;
      texd.y3 = y3;
      texd.col0 = c0;
      texd.col1 = c1;
      texd.col2 = c2;
      texd.col3 = c3;
      texd.u0 = u0;
      texd.u1 = u1;
      texd.u2 = u2;
      texd.u3 = u3;
      texd.v0 = v0;
      texd.v1 = v1;
      texd.v2 = v2;
      texd.v3 = v3;
      if (tex != null) {
         texd.flipped = tex.flip;
      }

      if (texdModifier != null) {
         texdModifier.accept(texd);
         texd.singleCol = texd.col0 == texd.col1 && texd.col0 == texd.col2 && texd.col0 == texd.col3;
      }

      if (pzopt.Mirrors.capturingNow) { // pzopt: mirrors, a window / mirror tile drawing: its quad and texture
         pzopt.Mirrors.captured(texd); // pzopt
      } // pzopt
      pzoptTakeDepth(texd); // pzopt: tileRecordParallel, a recording thread's depth comes from its own scratch
      return texd;
   }

   public int getColor(int i) {
      if (this.singleCol) {
         return this.col0;
      } else if (i == 0) {
         return this.col0;
      } else if (i == 1) {
         return this.col1;
      } else if (i == 2) {
         return this.col2;
      } else {
         return i == 3 ? this.col3 : this.col0;
      }
   }

   /**
    * pzopt: tileRecordParallel, this entry becomes a copy of {@code o} (every field: an entry type reads only its own,
    * the rest stay as stale as they would). The splice copies a recorded entry into the frame's own slot instead of
    * swapping the objects: swapped, the slots of both lists ended up scattered over the heap and recording ran into
    * a cache miss per entry (2026-10-06, A1 profile).
    */
   public void pzoptCopyFrom(TextureDraw o) { // pzopt
      TextureDraw.Type t = o.type; // pzopt
      if (t != TextureDraw.Type.glDraw && t != TextureDraw.Type.DrawModel && t != TextureDraw.Type.DrawQueued) { // pzopt: a state entry: the fields the state commands read
         this.type = t; // pzopt
         this.a = o.a; // pzopt
         this.b = o.b; // pzopt
         this.c = o.c; // pzopt
         this.d = o.d; // pzopt
         this.f1 = o.f1; // pzopt
         this.x0 = o.x0; // pzopt
         this.u0 = o.u0; // pzopt
         this.u1 = o.u1; // pzopt
         this.u2 = o.u2; // pzopt
         this.u3 = o.u3; // pzopt
         this.col0 = o.col0; // pzopt
         this.col1 = o.col1; // pzopt
         this.col2 = o.col2; // pzopt
         this.col3 = o.col3; // pzopt
         this.flipped = o.flipped; // pzopt
         this.drawer = o.drawer; // pzopt
         this.future = o.future; // pzopt
         this.probe = o.probe; // pzopt
         this.tex = o.tex; // pzopt
         this.tex1 = o.tex1; // pzopt
         return; // pzopt
      } // pzopt
      this.pzoptOutline = o.pzoptOutline; // pzopt
      this.pzoptShadowTile = o.pzoptShadowTile; // pzopt
      this.pzoptShadowX = o.pzoptShadowX; // pzopt
      this.pzoptShadowY = o.pzoptShadowY; // pzopt
      this.pzoptShadowZ = o.pzoptShadowZ; // pzopt
      this.pzoptShadowHalf = o.pzoptShadowHalf; // pzopt
      this.pzoptMirrorPlanes = o.pzoptMirrorPlanes; // pzopt
      this.pzoptLampN = o.pzoptLampN; // pzopt
      this.pzoptLampDraw = o.pzoptLampDraw; // pzopt
      this.type = o.type; // pzopt
      this.flipped = o.flipped; // pzopt
      this.a = o.a; // pzopt
      this.b = o.b; // pzopt
      this.f1 = o.f1; // pzopt
      this.vars = o.vars; // pzopt
      this.c = o.c; // pzopt
      this.d = o.d; // pzopt
      this.col0 = o.col0; // pzopt
      this.col1 = o.col1; // pzopt
      this.col2 = o.col2; // pzopt
      this.col3 = o.col3; // pzopt
      this.x0 = o.x0; // pzopt
      this.x1 = o.x1; // pzopt
      this.x2 = o.x2; // pzopt
      this.x3 = o.x3; // pzopt
      this.y0 = o.y0; // pzopt
      this.y1 = o.y1; // pzopt
      this.y2 = o.y2; // pzopt
      this.y3 = o.y3; // pzopt
      this.u0 = o.u0; // pzopt
      this.u1 = o.u1; // pzopt
      this.u2 = o.u2; // pzopt
      this.u3 = o.u3; // pzopt
      this.v0 = o.v0; // pzopt
      this.v1 = o.v1; // pzopt
      this.v2 = o.v2; // pzopt
      this.v3 = o.v3; // pzopt
      this.z = o.z; // pzopt
      this.chunkDepth = o.chunkDepth; // pzopt
      this.tex = o.tex; // pzopt
      this.tex1 = o.tex1; // pzopt
      this.tex2 = o.tex2; // pzopt
      this.useAttribArray = o.useAttribArray; // pzopt
      this.tex1U0 = o.tex1U0; // pzopt
      this.tex1U1 = o.tex1U1; // pzopt
      this.tex1U2 = o.tex1U2; // pzopt
      this.tex1U3 = o.tex1U3; // pzopt
      this.tex1V0 = o.tex1V0; // pzopt
      this.tex1V1 = o.tex1V1; // pzopt
      this.tex1V2 = o.tex1V2; // pzopt
      this.tex1V3 = o.tex1V3; // pzopt
      this.tex1Col0 = o.tex1Col0; // pzopt
      this.tex1Col1 = o.tex1Col1; // pzopt
      this.tex1Col2 = o.tex1Col2; // pzopt
      this.tex1Col3 = o.tex1Col3; // pzopt
      this.tex2U0 = o.tex2U0; // pzopt
      this.tex2U1 = o.tex2U1; // pzopt
      this.tex2U2 = o.tex2U2; // pzopt
      this.tex2U3 = o.tex2U3; // pzopt
      this.tex2V0 = o.tex2V0; // pzopt
      this.tex2V1 = o.tex2V1; // pzopt
      this.tex2V2 = o.tex2V2; // pzopt
      this.tex2V3 = o.tex2V3; // pzopt
      this.singleCol = o.singleCol; // pzopt
      this.imDrawData = o.imDrawData; // pzopt
      this.probe = o.probe; // pzopt
      this.drawer = o.drawer; // pzopt
      this.future = o.future; // pzopt
   } // pzopt

   public void reset() {
      this.type = TextureDraw.Type.glDraw;
      this.flipped = false;
      this.tex = null;
      this.tex1 = null;
      this.tex2 = null;
      this.useAttribArray = -1;
      this.col0 = -1;
      this.col1 = -1;
      this.col2 = -1;
      this.col3 = -1;
      this.singleCol = true;
      this.x0 = this.x1 = this.x2 = this.x3 = this.y0 = this.y1 = this.y2 = this.y3 = -1.0F;
      this.z = 0.0F;
      this.chunkDepth = 0.0F;
      this.drawer = null;
      this.future = null;
      this.probe = null;
   }

   public static void glLoadIdentity(TextureDraw textureDraw) {
      textureDraw.type = TextureDraw.Type.glLoadIdentity;
   }

   public static void glGenerateMipMaps(TextureDraw textureDraw, int a) {
      textureDraw.type = TextureDraw.Type.glGenerateMipMaps;
      textureDraw.a = a;
   }

   public static void glBind(TextureDraw textureDraw, int a) {
      textureDraw.type = TextureDraw.Type.glBind;
      textureDraw.a = a;
   }

   public static void glViewport(TextureDraw textureDraw, int x, int y, int width, int height) {
      textureDraw.type = TextureDraw.Type.glViewport;
      textureDraw.a = x;
      textureDraw.b = y;
      textureDraw.c = width;
      textureDraw.d = height;
   }

   public static void DrawQueued(TextureDraw texd, ModelSlot model) {
      texd.type = TextureDraw.Type.DrawQueued;
      texd.a = model.id;
      ModelSlotRenderData slotData = ModelSlotRenderData.alloc();
      slotData.initModel(model);
      texd.drawer = slotData;
      if (DebugOptions.instance.threadModelSlotInit.getValue()) {
         texd.future = slotInitExec.submit(() -> {
            synchronized (slotData) {
               slotData.init(model);
            }
         });
         pzopt.ModelInitOrder.queued(model, texd.future); // pzopt: ModelManager.Reset waits for it before re-dressing the slot
      } else {
         slotData.init(model);
      }
   }

   public static void RenderQueued(TextureDraw texd) {
      texd.type = TextureDraw.Type.RenderQueued;
   }

   public static void BeginProfile(TextureDraw texd, PerformanceProfileProbe probe) {
      texd.type = TextureDraw.Type.BeginProfile;
      texd.probe = probe;
   }

   public static void EndProfile(TextureDraw texd, PerformanceProfileProbe probe) {
      texd.type = TextureDraw.Type.EndProfile;
      texd.probe = probe;
   }

   public void postRender() {
      if (this.type == TextureDraw.Type.StartShader) {
         Shader shader = (Shader)Shader.ShaderMap.get(this.a);
         if (shader != null) {
            shader.postRender(this);
         }
      }

      if (this.drawer != null) {
         if (this.future != null) {
            ProfileArea var6 = GameProfiler.getInstance().profile("Wait");

            try {
               this.GetFutureResultNoThrow();
            } catch (Throwable var5) {
               if (var6 != null) {
                  try {
                     var6.close();
                  } catch (Throwable var4) {
                     var5.addSuppressed(var4);
                  }
               }

               throw var5;
            }

            if (var6 != null) {
               var6.close();
            }
         }

         this.drawer.postRender();
         this.drawer = null;
      }

      this.tex = null;
      this.tex1 = null;
   }

   private void GetFutureResultThrow() {
      try {
         this.future.get();
      } catch (Throwable t) {
         throw new RuntimeException(t);
      } finally {
         this.future = null;
      }
   }

   private void GetFutureResultNoThrow() {
      try {
         this.future.get();
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
      } finally {
         this.future = null;
      }
   }

   public abstract static class GenericDrawer {
      public abstract void render();

      public void render(TextureDraw texd) {
         this.render();
      }

      public void postRender() {
      }
   }

   public enum Type {
      glDraw,
      glBuffer,
      glStencilFunc,
      glAlphaFunc,
      glStencilOp,
      glEnable,
      glDisable,
      glColorMask,
      glStencilMask,
      glClear,
      glBindFramebuffer,
      glBlendFunc,
      glDoStartFrame,
      glDoStartFrameText,
      glDoEndFrame,
      glTexParameteri,
      StartShader,
      glLoadIdentity,
      glGenerateMipMaps,
      glBind,
      glViewport,
      DrawModel,
      DrawSkyBox,
      DrawWater,
      DrawPuddles,
      DrawParticles,
      ShaderUpdate,
      BindActiveTexture,
      glBlendEquation,
      glDoStartFrameFx,
      glDoEndFrameFx,
      glIgnoreStyles,
      glClearColor,
      glBlendFuncSeparate,
      glDepthMask,
      doCoreIntParam,
      drawTerrain,
      pushIsoView,
      popIsoView,
      FBORenderChunkEnd,
      FBORenderChunkStart,
      glDoStartFrameNoZoom,
      glDoStartFrameFlipY,
      releaseFBORenderChunkLock,
      glDepthFunc,
      glClearDepth,
      NewFrame,
      DrawQueued,
      RenderQueued,
      DrawImGui,
      BeginProfile,
      EndProfile;
   }
}
