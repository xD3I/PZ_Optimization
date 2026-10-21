package zombie.core.skinnedmodel.model;

import gnu.trove.map.hash.TIntObjectHashMap;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjglx.BufferUtils;
import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.ImmutableColor;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IModelCamera;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.SpriteModel;
import zombie.iso.Vector2;
import zombie.iso.IsoDepthHelper.Results;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderItems;
import zombie.iso.fboRenderChunk.FBORenderObjectHighlight;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.popman.ObjectPool;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.AnimationsMesh;
import zombie.scripting.objects.ModelAttachment;
import zombie.scripting.objects.ModelScript;
import zombie.vehicles.BaseVehicle;

public final class IsoObjectModelDrawer extends GenericDrawer {
   private static final ObjectPool<IsoObjectModelDrawer> s_modelDrawerPool = new ObjectPool(IsoObjectModelDrawer::new, "IsoObjectModelDrawer.s_modelDrawerPool");
   private static final TIntObjectHashMap<IsoObjectModelDrawer.FloatBufferPool> s_matrixPalettePool = new TIntObjectHashMap();
   private static final Vector3f tempVector3f = new Vector3f(0.0F, 5.0F, -2.0F);
   private static final Vector2 tempVector2_1 = new Vector2();
   private SpriteModel spriteModel;
   private Model model;
   private ModelScript modelScript;
   private Texture texture;
   private AnimationPlayer animationPlayer;
   private FloatBuffer matrixPalette;
   private float hue;
   private float tintR;
   private float tintG;
   private float tintB;
   private float x;
   private float y;
   private float z;
   private final Vector3f angle = new Vector3f();
   private final Matrix4f transform = new Matrix4f();
   private float ambientR;
   private float ambientG;
   private float ambientB;
   private float alpha = 1.0F;
   private boolean renderToChunkTexture;
   private float squareDepth;
   private boolean outline;
   private final ColorInfo outlineColor = new ColorInfo();
   private boolean outlineBehindPlayer = true;
   static final IsoObjectModelDrawer.WorldModelCamera s_worldModelCamera = new IsoObjectModelDrawer.WorldModelCamera();

   public static IsoObjectModelDrawer.RenderStatus renderMain(SpriteModel spriteModel, float x, float y, float z, ColorInfo colorInfo, float renderYOffset) {
      return renderMain(spriteModel, x, y, z, colorInfo, renderYOffset, null, null, false, true);
   }

   public static IsoObjectModelDrawer.RenderStatus renderMainOutline(
      SpriteModel spriteModel, float x, float y, float z, ColorInfo colorInfo, float renderYOffset
   ) {
      return renderMain(spriteModel, x, y, z, colorInfo, renderYOffset, null, null, true, true);
   }

   public static IsoObjectModelDrawer.RenderStatus renderMain(
      SpriteModel spriteModel,
      float x,
      float y,
      float z,
      ColorInfo colorInfo,
      float renderYOffset,
      SpriteModel parentSpriteModel,
      Matrix4f attachmentWorldXfrm,
      boolean bOutline,
      boolean bApplySurfaceAlpha
   ) {
      ModelScript modelScript = ScriptManager.instance.getModelScript(spriteModel.modelScriptName);
      if (modelScript == null) {
         return IsoObjectModelDrawer.RenderStatus.NoModel;
      }

      String meshName = modelScript.getMeshName();
      String texName = modelScript.getTextureName();
      String shaderName = modelScript.getShaderName();
      ImmutableColor tint = ImmutableColor.white;
      float hue = 1.0F;
      boolean bStatic = modelScript.isStatic;
      Model model = ModelManager.instance.tryGetLoadedModel(modelScript, true);
      if (model == null && !bStatic && modelScript.animationsMesh != null) {
         AnimationsMesh animationsMesh = ScriptManager.instance.getAnimationsMesh(modelScript.animationsMesh);
         if (animationsMesh != null && animationsMesh.modelMesh != null) {
            model = ModelManager.instance.loadModel(meshName, texName, animationsMesh.modelMesh, shaderName);
         }
      }

      if (model == null) {
         ModelManager.instance.loadAdditionalModel(meshName, texName, bStatic, shaderName);
         model = ModelManager.instance.getLoadedModel(meshName, texName, bStatic, shaderName);
      }

      if (model == null) {
         return IsoObjectModelDrawer.RenderStatus.NoModel;
      }

      if (model.isFailure()) {
         return IsoObjectModelDrawer.RenderStatus.Failed;
      }

      if (model.isReady() && model.mesh != null && model.mesh.isReady()) {
         IsoObjectModelDrawer modelDrawer = pzoptAlloc(); // pzopt: tileRecordModels, the pool under a lock (recording threads draw models too)
         modelDrawer.spriteModel = spriteModel;
         modelDrawer.model = model;
         modelDrawer.modelScript = modelScript;
         modelDrawer.texture = null;
         if (spriteModel.getTextureName() != null) {
            if (spriteModel.getTextureName().contains("media/")) {
               modelDrawer.texture = Texture.getSharedTexture(spriteModel.getTextureName());
            } else {
               modelDrawer.texture = Texture.getSharedTexture("media/textures/" + spriteModel.getTextureName() + ".png");
            }

            IsoObjectModelDrawer.RenderStatus renderStatus = checkTextureStatus(modelDrawer.texture);
            if (renderStatus != IsoObjectModelDrawer.RenderStatus.Ready) {
               pzoptRelease(modelDrawer); // pzopt: tileRecordModels
               return renderStatus;
            }
         } else if (model.tex != null) {
            IsoObjectModelDrawer.RenderStatus renderStatus = checkTextureStatus(model.tex);
            if (renderStatus != IsoObjectModelDrawer.RenderStatus.Ready) {
               pzoptRelease(modelDrawer); // pzopt: tileRecordModels
               return renderStatus;
            }
         }

         modelDrawer.animationPlayer = null;
         modelDrawer.initMatrixPalette();
         modelDrawer.x = x;
         modelDrawer.y = y;
         modelDrawer.z = z;
         modelDrawer.tintR = tint.r;
         modelDrawer.tintG = tint.g;
         modelDrawer.tintB = tint.b;
         modelDrawer.hue = 1.0F;
         modelDrawer.transform.identity();
         float dy = renderYOffset / 96.0F * 2.44949F;
         if (parentSpriteModel != null) {
            modelDrawer.transform
               .translate(-parentSpriteModel.translate.x / 1.5F, (parentSpriteModel.translate.y + dy) / 1.5F, parentSpriteModel.translate.z / 1.5F);
            modelDrawer.transform
               .rotateXYZ(
                  parentSpriteModel.rotate.x * (float) (Math.PI / 180.0),
                  -parentSpriteModel.rotate.y * (float) (Math.PI / 180.0),
                  parentSpriteModel.rotate.z * (float) (Math.PI / 180.0)
               );
            ModelScript modelScript1 = ScriptManager.instance.getModelScript(parentSpriteModel.modelScriptName);
            if (modelScript1.scale != 1.0F) {
               modelDrawer.transform.scale(modelScript1.scale);
            }

            if (parentSpriteModel.scale != 1.0F) {
               modelDrawer.transform.scale(parentSpriteModel.scale);
            }

            if (attachmentWorldXfrm != null) {
               modelDrawer.transform.mul(attachmentWorldXfrm);
               if (modelScript.scale * spriteModel.scale * 1.5F != 1.0F) {
                  modelDrawer.transform.scale(modelScript.scale * spriteModel.scale * 1.5F);
               }
            }
         } else {
            modelDrawer.transform.translate(-spriteModel.translate.x / 1.5F, (spriteModel.translate.y + dy) / 1.5F, spriteModel.translate.z / 1.5F);
            modelDrawer.transform
               .rotateXYZ(
                  spriteModel.rotate.x * (float) (Math.PI / 180.0),
                  -spriteModel.rotate.y * (float) (Math.PI / 180.0),
                  spriteModel.rotate.z * (float) (Math.PI / 180.0)
               );
            if (modelScript.scale != 1.0F) {
               modelDrawer.transform.scale(modelScript.scale);
            }

            if (spriteModel.scale != 1.0F) {
               modelDrawer.transform.scale(spriteModel.scale);
            }
         }

         modelDrawer.angle.set(0.0F);
         ModelInstanceRenderData.postMultiplyMeshTransform(modelDrawer.transform, model.mesh);
         modelDrawer.ambientR = colorInfo.r;
         modelDrawer.ambientG = colorInfo.g;
         modelDrawer.ambientB = colorInfo.b;
         IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
         float zoff = PZMath.max(renderYOffset / 96.0F, z - PZMath.fastfloor(z));
         modelDrawer.alpha = bApplySurfaceAlpha && square != null ? IsoWorldInventoryObject.getSurfaceAlpha(square, zoff, false) : 1.0F;
         modelDrawer.alpha = modelDrawer.alpha * colorInfo.a;
         if (DebugOptions.instance.fboRenderChunk.forceAlphaAndTargetOne.getValue()) {
            modelDrawer.alpha = 1.0F;
         }

         float x1 = x;
         float y1 = y;
         float z1 = z;
         if (!modelScript.isStatic) {
            x1 += spriteModel.translate.x;
            y1 += spriteModel.translate.z;
            z1 += spriteModel.translate.y / 2.44949F;
         }

         PlayerCamera camera = IsoCamera.cameras[IsoCamera.frameState.playerIndex];
         if (PerformanceSettings.fboRenderChunk && !zombie.iso.fboRenderChunk.FBORenderCell.pzoptCaching()) { // pzopt: tileRecordModels, the calling thread's render mode
            x1 += camera.fixJigglyModelsSquareX;
            y1 += camera.fixJigglyModelsSquareY;
         }

         modelDrawer.renderToChunkTexture = !bOutline && PerformanceSettings.fboRenderChunk && zombie.iso.fboRenderChunk.FBORenderCell.pzoptCaching(); // pzopt: tileRecordModels
         Results results = IsoDepthHelper.getSquareDepthData(
            PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), x1, y1, z1
         );
         if (modelDrawer.renderToChunkTexture) {
            results.depthStart = results.depthStart
               - IsoDepthHelper.getChunkDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F),
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F),
                     PZMath.fastfloor(x / 8.0F),
                     PZMath.fastfloor(y / 8.0F),
                     PZMath.fastfloor(z)
                  )
                  .depthStart;
         }

         modelDrawer.squareDepth = results.depthStart;
         if (zombie.iso.fboRenderChunk.FBORenderCell.pzoptHighlightRendering()) { // pzopt: tileRecordModels, a recording thread is not in the highlight pass
            modelDrawer.squareDepth -= 5.0E-5F;
         }

         modelDrawer.outline = bOutline;
         modelDrawer.outlineColor.set(colorInfo);
         modelDrawer.outlineBehindPlayer = x + y < IsoCamera.frameState.camCharacterX + IsoCamera.frameState.camCharacterY;
         SpriteRenderer.instance.drawGeneric(modelDrawer);
         return IsoObjectModelDrawer.RenderStatus.Ready;
      } else {
         return IsoObjectModelDrawer.RenderStatus.Loading;
      }
   }

   public static IsoObjectModelDrawer.RenderStatus renderMain(
      SpriteModel spriteModel, float x, float y, float z, ColorInfo colorInfo, float renderYOffset, AnimationPlayer animationPlayer
   ) {
      ModelScript modelScript = ScriptManager.instance.getModelScript(spriteModel.modelScriptName);
      if (modelScript == null) {
         return IsoObjectModelDrawer.RenderStatus.NoModel;
      }

      ImmutableColor tint = ImmutableColor.white;
      float hue = 1.0F;
      Model model = animationPlayer.getModel();
      if (model == null) {
         return IsoObjectModelDrawer.RenderStatus.NoModel;
      }

      if (model.isFailure()) {
         return IsoObjectModelDrawer.RenderStatus.Failed;
      }

      if (model.isReady() && model.mesh != null && model.mesh.isReady()) {
         IsoObjectModelDrawer modelDrawer = pzoptAlloc(); // pzopt: tileRecordModels, the pool under a lock (recording threads draw models too)
         modelDrawer.model = model;
         modelDrawer.modelScript = modelScript;
         modelDrawer.texture = null;
         if (spriteModel.getTextureName() != null) {
            if (spriteModel.getTextureName().contains("media/")) {
               modelDrawer.texture = Texture.getSharedTexture(spriteModel.getTextureName());
            } else {
               modelDrawer.texture = Texture.getSharedTexture("media/textures/" + spriteModel.getTextureName() + ".png");
            }

            IsoObjectModelDrawer.RenderStatus renderStatus = checkTextureStatus(modelDrawer.texture);
            if (renderStatus != IsoObjectModelDrawer.RenderStatus.Ready) {
               pzoptRelease(modelDrawer); // pzopt: tileRecordModels
               return renderStatus;
            }
         } else if (model.tex != null) {
            IsoObjectModelDrawer.RenderStatus renderStatus = checkTextureStatus(model.tex);
            if (renderStatus != IsoObjectModelDrawer.RenderStatus.Ready) {
               pzoptRelease(modelDrawer); // pzopt: tileRecordModels
               return renderStatus;
            }
         }

         modelDrawer.animationPlayer = animationPlayer;
         modelDrawer.initMatrixPalette();
         modelDrawer.x = x;
         modelDrawer.y = y;
         modelDrawer.z = z;
         modelDrawer.tintR = tint.r;
         modelDrawer.tintG = tint.g;
         modelDrawer.tintB = tint.b;
         modelDrawer.hue = 1.0F;
         modelDrawer.transform.identity();
         float dy = renderYOffset / 96.0F * 2.44949F;
         modelDrawer.transform.translate(-spriteModel.translate.x / 1.5F, (spriteModel.translate.y + dy) / 1.5F, spriteModel.translate.z / 1.5F);
         modelDrawer.transform
            .rotateXYZ(
               spriteModel.rotate.x * (float) (Math.PI / 180.0),
               -spriteModel.rotate.y * (float) (Math.PI / 180.0),
               spriteModel.rotate.z * (float) (Math.PI / 180.0)
            );
         if (modelScript.scale != 1.0F) {
            modelDrawer.transform.scale(modelScript.scale);
         }

         if (spriteModel.scale != 1.0F) {
            modelDrawer.transform.scale(spriteModel.scale);
         }

         modelDrawer.angle.set(0.0F);
         ModelInstanceRenderData.postMultiplyMeshTransform(modelDrawer.transform, model.mesh);
         modelDrawer.ambientR = colorInfo.r;
         modelDrawer.ambientG = colorInfo.g;
         modelDrawer.ambientB = colorInfo.b;
         if (Core.debug) {
         }

         IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
         float zoff = PZMath.max(renderYOffset / 96.0F, z - PZMath.fastfloor(z));
         modelDrawer.alpha = square == null ? 1.0F : IsoWorldInventoryObject.getSurfaceAlpha(square, zoff, false);
         modelDrawer.alpha = modelDrawer.alpha * colorInfo.a;
         if (DebugOptions.instance.fboRenderChunk.forceAlphaAndTargetOne.getValue()) {
            modelDrawer.alpha = 1.0F;
         }

         modelDrawer.renderToChunkTexture = PerformanceSettings.fboRenderChunk && zombie.iso.fboRenderChunk.FBORenderCell.pzoptCaching(); // pzopt: tileRecordModels
         float x1 = x;
         float y1 = y;
         float z1 = z;
         if (!modelScript.isStatic) {
            x1 += spriteModel.translate.x;
            y1 += spriteModel.translate.z;
            z1 += spriteModel.translate.y / 2.44949F;
         }

         Results results = IsoDepthHelper.getSquareDepthData(
            PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), x1, y1, z1
         );
         if (modelDrawer.renderToChunkTexture) {
            results.depthStart = results.depthStart
               - IsoDepthHelper.getChunkDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F),
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F),
                     PZMath.fastfloor(x / 8.0F),
                     PZMath.fastfloor(y / 8.0F),
                     PZMath.fastfloor(z)
                  )
                  .depthStart;
         }

         modelDrawer.squareDepth = results.depthStart;
         modelDrawer.outline = false;
         SpriteRenderer.instance.drawGeneric(modelDrawer);
         return IsoObjectModelDrawer.RenderStatus.Ready;
      } else {
         return IsoObjectModelDrawer.RenderStatus.Loading;
      }
   }

   private static IsoObjectModelDrawer.RenderStatus checkTextureStatus(Texture texture) {
      if (texture == null || texture.isFailure()) {
         return IsoObjectModelDrawer.RenderStatus.Failed;
      } else {
         return !texture.isReady() ? IsoObjectModelDrawer.RenderStatus.Loading : IsoObjectModelDrawer.RenderStatus.Ready;
      }
   }

   private void initMatrixPalette() {
      SkinningData skinningData = (SkinningData)this.model.tag;
      if (skinningData == null) {
         this.matrixPalette = releaseMatrixPalette(this.matrixPalette);
      } else if (this.animationPlayer == null) {
         if (!this.model.isStatic && this.spriteModel.getAnimationName() != null) {
            synchronized (IsoObjectAnimations.class) { // pzopt: tileRecordModels, the frame cache's list and its shared buffers (position / relative get) under one lock
            FloatBuffer matrixPalette = IsoObjectAnimations.getInstance()
               .getMatrixPaletteForFrame(this.model, this.spriteModel.getAnimationName(), this.spriteModel.getAnimationTime());
            if (matrixPalette == null) {
               return;
            }

            matrixPalette.position(0);
            if (this.matrixPalette == null || this.matrixPalette.capacity() < matrixPalette.capacity()) {
               this.matrixPalette = releaseMatrixPalette(this.matrixPalette);
               this.matrixPalette = allocMatrixPalette(matrixPalette.capacity());
            }

            this.matrixPalette.clear();
            this.matrixPalette.put(matrixPalette);
            this.matrixPalette.flip();
            } // pzopt
         } else {
            this.matrixPalette = releaseMatrixPalette(this.matrixPalette);
         }
      } else {
         org.lwjgl.util.vector.Matrix4f[] skinTransforms = this.animationPlayer.getSkinTransforms(skinningData);
         int matrixFloats = 16;
         if (this.matrixPalette == null || this.matrixPalette.capacity() < skinTransforms.length * 16) {
            this.matrixPalette = releaseMatrixPalette(this.matrixPalette);
            this.matrixPalette = allocMatrixPalette(skinTransforms.length * 16);
         }

         this.matrixPalette.clear();

         for (int i = 0; i < skinTransforms.length; i++) {
            skinTransforms[i].store(this.matrixPalette);
         }

         this.matrixPalette.flip();
      }
   }

   private static synchronized FloatBuffer allocMatrixPalette(int capacity) { // pzopt: tileRecordModels, the pools under the class lock
      IsoObjectModelDrawer.FloatBufferPool buffers = (IsoObjectModelDrawer.FloatBufferPool)s_matrixPalettePool.get(capacity);
      if (buffers == null) {
         buffers = new IsoObjectModelDrawer.FloatBufferPool(capacity, "IsoObjectModelDrawer.s_matrixPalettePool." + capacity);
         s_matrixPalettePool.put(capacity, buffers);
      }

      return (FloatBuffer)buffers.alloc();
   }

   private static synchronized FloatBuffer releaseMatrixPalette(FloatBuffer matrixPalette) { // pzopt: tileRecordModels
      if (matrixPalette == null) {
         return null;
      }

      IsoObjectModelDrawer.FloatBufferPool buffers = (IsoObjectModelDrawer.FloatBufferPool)s_matrixPalettePool.get(matrixPalette.capacity());
      buffers.release(matrixPalette);
      return null;
   }

   private static IsoObjectModelDrawer pzoptAlloc() { // pzopt: tileRecordModels
      synchronized (IsoObjectModelDrawer.class) { // pzopt
         return (IsoObjectModelDrawer)s_modelDrawerPool.alloc(); // pzopt
      } // pzopt
   } // pzopt

   private static void pzoptRelease(IsoObjectModelDrawer d) { // pzopt: tileRecordModels
      synchronized (IsoObjectModelDrawer.class) { // pzopt
         s_modelDrawerPool.release(d); // pzopt
      } // pzopt
   } // pzopt

   public void render() {
      FBORenderChunk renderChunk = FBORenderChunkManager.instance.renderThreadCurrent;
      if (PerformanceSettings.fboRenderChunk && renderChunk != null) {
         this.angle.y += 180.0F;
         FBORenderItems.getInstance().setCamera(renderChunk, this.x, this.y, this.z, this.angle);
         this.angle.y -= 180.0F;
         this.renderToChunkTexture(FBORenderItems.getInstance().getCamera(), renderChunk.highRes);
      } else {
         this.renderToWorld();
      }
   }

   public void postRender() {
      pzoptRelease(this); // pzopt: tileRecordModels
   }

   private void renderToChunkTexture(IModelCamera camera, boolean bHighRes) {
      GL11.glPushAttrib(1048575);
      GL11.glPushClientAttrib(-1);
      camera.Begin();
      this.renderModel(bHighRes);
      camera.End();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      SpriteRenderer.ringBuffer.restoreVbos = true;
   }

   private void renderToWorld() {
      GL11.glPushAttrib(1048575);
      GL11.glPushClientAttrib(-1);
      if (this.outline) {
         boolean clear = ModelOutlines.instance.beginRenderOutline(this.outlineColor, this.outlineBehindPlayer, false);
         GL11.glDepthMask(true);
         ModelOutlines.instance.fboA.startDrawing(clear, true);
         if (ModelSlotRenderData.solidColor == null) {
            ModelSlotRenderData.solidColor = new Shader("aim_outline_solid", false, false);
            ModelSlotRenderData.solidColorStatic = new Shader("aim_outline_solid", true, false);
         }

         ModelSlotRenderData.solidColor.Start();
         ModelSlotRenderData.solidColor.getShaderProgram().setVector4("u_color", this.outlineColor.r, this.outlineColor.g, this.outlineColor.b, 1.0F);
         ModelSlotRenderData.solidColor.End();
         ModelSlotRenderData.solidColorStatic.Start();
         ModelSlotRenderData.solidColorStatic.getShaderProgram().setVector4("u_color", this.outlineColor.r, this.outlineColor.g, this.outlineColor.b, 1.0F);
         ModelSlotRenderData.solidColorStatic.End();
      }

      s_worldModelCamera.x = this.x;
      s_worldModelCamera.y = this.y;
      s_worldModelCamera.z = this.z;
      s_worldModelCamera.Begin();
      this.renderModel(false);
      s_worldModelCamera.End();
      if (this.outline) {
         ModelOutlines.instance.fboA.endDrawing();
      }

      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      GLStateRenderThread.restore();
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      SpriteRenderer.ringBuffer.restoreVbos = true;
   }

   private void renderModel(boolean bHighRes) {
      Model model = this.model;
      if (model.effect == null) {
         model.CreateShader("basicEffect");
      }

      Shader effect = model.effect;
      if (this.outline) {
         effect = model.isStatic ? ModelSlotRenderData.solidColorStatic : ModelSlotRenderData.solidColor;
      }

      if (effect != null && model.mesh != null && model.mesh.isReady()) {
         IndieGL.glDefaultBlendFuncA();
         GL11.glDepthFunc(513);
         GL11.glDepthMask(true);
         GL11.glDepthRange(0.0, 1.0);
         GL11.glEnable(2929);
         if (effect.getShaderProgram().getName().contains("door") && FBORenderChunkManager.instance.renderThreadCurrent == null) {
            GL11.glEnable(2884);
            GL11.glCullFace(1028);
         } else if (effect.getShaderProgram().getName().contains("door") && FBORenderChunkManager.instance.renderThreadCurrent != null) {
            GL11.glEnable(2884);
            GL11.glCullFace(1029);
         } else if (this.modelScript != null && this.modelScript.cullFace != -1) {
            if (this.modelScript.cullFace == 0) {
               GL11.glDisable(2884);
            } else {
               GL11.glEnable(2884);
               GL11.glCullFace(this.modelScript.cullFace);
            }
         }

         GL11.glColor3f(1.0F, 1.0F, 1.0F);
         boolean bPushTransform = false;
         if (!this.model.isStatic) {
            PZGLUtil.pushAndMultMatrix(5888, this.transform);
            bPushTransform = true;
            if (this.modelScript.meshName.contains("door1")) {
            }
         }

         effect.Start();
         Texture tex = model.tex;
         if (this.texture != null) {
            tex = this.texture;
         }

         if (this.outline && model.effect != null && model.effect.getName().contains("door")) {
            effect.setTexture(Texture.getWhite(), "Texture", 0);
         } else if (tex != null) {
            if (!tex.getTextureId().hasMipMaps()) {
               GL11.glBlendFunc(770, 771);
            }

            effect.setTexture(tex, "Texture", 0);
            if (effect.getShaderProgram().getName().equalsIgnoreCase("door")) {
               int widthHW = tex.getWidthHW();
               int heightHW = tex.getHeightHW();
               float x1 = tex.xStart * widthHW - tex.offsetX;
               float y1 = tex.yStart * heightHW - tex.offsetY;
               float x2 = x1 + tex.getWidthOrig();
               float y2 = y1 + tex.getHeightOrig();
               effect.getShaderProgram().setValue("UVOffset", tempVector2_1.set(x1 / widthHW, y1 / heightHW));
               effect.getShaderProgram().setValue("UVScale", tempVector2_1.set((x2 - x1) / widthHW, (y2 - y1) / heightHW));
            }
         }

         effect.setDepthBias(0.0F);
         float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
         float targetDepth = this.squareDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F;
         if (!PerformanceSettings.fboRenderChunk) {
            targetDepth = 0.5F;
         }

         effect.setTargetDepth(targetDepth);
         if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
            effect.setAmbient(1.0F, 1.0F, 1.0F);
         } else {
            effect.setAmbient(this.ambientR, this.ambientG, this.ambientB);
         }

         effect.setLightingAmount(1.0F);
         effect.setHueShift(this.hue);
         effect.setTint(this.tintR, this.tintG, this.tintB);
         effect.setAlpha(DebugOptions.instance.model.render.forceAlphaOne.getValue() ? 1.0F : this.alpha);

         for (int i = 0; i < 5; i++) {
            effect.setLight(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, 0.0F, 0.0F, 0.0F, null);
         }

         if (!DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
            Vector3f pos = tempVector3f;
            pos.x = 0.0F;
            pos.y = 5.0F;
            pos.z = -2.0F;
            pos.rotateY(this.angle.y * (float) (Math.PI / 180.0));
            targetDepth = 1.5F;
            effect.setLight(
               4,
               pos.x,
               pos.z,
               pos.y,
               this.ambientR / 4.0F * 1.5F,
               this.ambientG / 4.0F * 1.5F,
               this.ambientB / 4.0F * 1.5F,
               5000.0F,
               Float.NaN,
               0.0F,
               0.0F,
               0.0F,
               null
            );
         }

         if (model.isStatic) {
            effect.setTransformMatrix(this.transform, false);
         } else if (this.matrixPalette != null) {
            effect.setMatrixPalette(this.matrixPalette, true);
         }

         if (this.renderToChunkTexture && bHighRes) {
            effect.setHighResDepthMultiplier(0.5F);
         }

         model.mesh.Draw(effect);
         if (this.renderToChunkTexture && bHighRes) {
            effect.setHighResDepthMultiplier(0.0F);
         }

         effect.End();
         if (bPushTransform) {
            PZGLUtil.popMatrix(5888);
         }

         Matrix4f m = BaseVehicle.allocMatrix4f();
         m.set(this.transform);
         m.mul(this.model.mesh.transform);
         if (DebugOptions.instance.model.render.attachments.getValue()) {
            for (int i = 0; i < this.modelScript.getAttachmentCount(); i++) {
               ModelAttachment attachment = this.modelScript.getAttachment(i);
               Matrix4f attachmentMatrix = BaseVehicle.allocMatrix4f();
               ModelInstanceRenderData.makeAttachmentTransform(attachment, attachmentMatrix);
               m.mul(attachmentMatrix, attachmentMatrix);
               PZGLUtil.pushAndMultMatrix(5888, attachmentMatrix);
               BaseVehicle.releaseMatrix4f(attachmentMatrix);
               Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 0.1F, 1.0F);
               PZGLUtil.popMatrix(5888);
               VBORenderer vboRenderer = VBORenderer.getInstance();
               vboRenderer.cmdPushAndMultMatrix(5888, m);
               vboRenderer.startRun(vboRenderer.formatPositionColor);
               vboRenderer.setMode(1);
               vboRenderer.setLineWidth(2.0F);
               vboRenderer.addLine(attachment.getOffset().x, attachment.getOffset().y, attachment.getOffset().z, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
               vboRenderer.endRun();
               vboRenderer.cmdPopMatrix(5888);
               vboRenderer.flush();
            }
         }

         if (Core.debug && DebugOptions.instance.model.render.axis.getValue() && this.modelScript.name.contains("Sheet")) {
            m.scale(100.0F);
            PZGLUtil.pushAndMultMatrix(5888, m);
            Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 0.5F, 1.0F);
            PZGLUtil.popMatrix(5888);
         }

         BaseVehicle.releaseMatrix4f(m);
      }
   }

   private static final class FloatBufferPool extends ObjectPool<FloatBuffer> {
      final int capacity;

      FloatBufferPool(int capacity, String name) {
         super(name);
         this.capacity = capacity;
      }

      protected FloatBuffer makeObject() {
         return BufferUtils.createFloatBuffer(this.capacity);
      }
   }

   public enum RenderStatus {
      NoModel,
      Failed,
      Loading,
      Ready;
   }

   private static final class WorldModelCamera implements IModelCamera {
      float x;
      float y;
      float z;

      public void Begin() {
         float cx = (Float)Core.getInstance().floatParamMap.get(0);
         float cy = (Float)Core.getInstance().floatParamMap.get(1);
         float cz = (Float)Core.getInstance().floatParamMap.get(2);
         double x = cx;
         double y = cy;
         double z = cz;
         SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
         int playerIndex = renderState.playerIndex;
         PlayerCamera cam = renderState.playerCamera[playerIndex];
         float rcx = cam.rightClickX;
         float rcy = cam.rightClickY;
         float defx = cam.deferedX;
         float defy = cam.deferedY;
         float tx = (rcx + 2.0F * rcy) / (64.0F * Core.tileScale);
         float ty = (rcx - 2.0F * rcy) / (-64.0F * Core.tileScale);
         x += tx;
         y += ty;
         x += defx;
         y += defy;
         double screenWidth = cam.offscreenWidth / 1920.0F;
         double screenHeight = cam.offscreenHeight / 1920.0F;
         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         projection.setOrtho(-((float)screenWidth) / 2.0F, (float)screenWidth / 2.0F, -((float)screenHeight) / 2.0F, (float)screenHeight / 2.0F, -10.0F, 10.0F);
         Core.getInstance().projectionMatrixStack.push(projection);
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.scaling(Core.scale);
         modelView.scale(Core.tileScale / 2.0F);
         modelView.rotate((float) (Math.PI / 6), 1.0F, 0.0F, 0.0F);
         modelView.rotate((float) (Math.PI * 3.0 / 4.0), 0.0F, 1.0F, 0.0F);
         double difX = this.x - x;
         double difY = this.y - y;
         modelView.translate(-((float)difX), (float)(this.z - z) * 2.44949F, -((float)difY));
         modelView.scale(-1.5F, 1.5F, 1.5F);
         modelView.rotate((float) Math.PI, 0.0F, 1.0F, 0.0F);
         modelView.translate(0.0F, -0.48F, 0.0F);
         Core.getInstance().modelViewMatrixStack.push(modelView);
      }

      public void End() {
         Core.getInstance().projectionMatrixStack.pop();
         Core.getInstance().modelViewMatrixStack.pop();
      }
   }
}
