package zombie.core.skinnedmodel.model;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjglx.BufferUtils;
import zombie.GameProfiler;
import zombie.GameProfiler.ProfileArea;
import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetType;
import zombie.asset.AssetManager.AssetParams;
import zombie.characters.IsoGameCharacter;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.rendering.RenderList;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstance.EffectLight;
import zombie.core.skinnedmodel.model.ModelMesh.MeshAssetParams;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.Texture;
import zombie.debug.DebugOptions;
import zombie.iso.IsoLightSource;
import zombie.iso.Vector3;
import zombie.iso.sprite.SkyBox;
import zombie.scripting.objects.ModelScript;
import zombie.util.Lambda;
import zombie.util.StringUtils;
import zombie.util.Type;

public final class Model extends Asset {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.model.Model"); // pzopt
   }

   public static final AssetType ASSET_TYPE = new AssetType("Model");
   public String name;
   public final ModelMesh mesh;
   public Shader effect;
   public Object tag;
   public boolean isStatic;
   public Texture tex;
   public SoftwareModelMesh softwareMesh;
   public static final FloatBuffer m_staticReusableFloatBuffer = BufferUtils.createFloatBuffer(128);
   private static final Matrix4f IDENTITY = new Matrix4f();
   public static TObjectIntHashMap<Model> modelDrawCounts = new TObjectIntHashMap();
   public static int instancingThreshold = 10;
   public static final Color[] debugDrawColours = new Color[]{
      new Color(230, 25, 75),
      new Color(60, 180, 75),
      new Color(255, 225, 25),
      new Color(0, 130, 200),
      new Color(245, 130, 48),
      new Color(145, 30, 180),
      new Color(70, 240, 240),
      new Color(240, 50, 230),
      new Color(210, 245, 60),
      new Color(250, 190, 190),
      new Color(0, 128, 128),
      new Color(230, 190, 255),
      new Color(170, 110, 40),
      new Color(255, 250, 200),
      new Color(128, 0, 0),
      new Color(170, 255, 195),
      new Color(128, 128, 0),
      new Color(255, 215, 180),
      new Color(0, 0, 128),
      new Color(128, 128, 128),
      new Color(255, 255, 255),
      new Color(0, 0, 0)
   };
   public Model.ModelAssetParams assetParams;

   public Model(AssetPath path, AssetManager manager, Model.ModelAssetParams params) {
      super(path, manager);
      this.assetParams = params;
      this.isStatic = this.assetParams != null && this.assetParams.isStatic;
      MeshAssetParams meshParams = new MeshAssetParams();
      meshParams.isStatic = this.isStatic;
      meshParams.animationsMesh = this.assetParams == null ? null : this.assetParams.animationsModel;
      meshParams.postProcess = this.assetParams == null ? null : this.assetParams.postProcess;
      this.mesh = (ModelMesh)MeshAssetManager.instance.load(new AssetPath(params.meshName), meshParams);
      if (!StringUtils.isNullOrWhitespace(params.textureName)) {
         if (params.textureName.contains("media/")) {
            this.tex = Texture.getSharedTexture(params.textureName, params.textureFlags);
         } else {
            this.tex = Texture.getSharedTexture("media/textures/" + params.textureName + ".png", params.textureFlags);
         }
      }

      if (!StringUtils.isNullOrWhitespace(params.shaderName)) {
         this.CreateShader(params.shaderName);
      }

      this.onCreated(this.mesh.getState());
      this.addDependency(this.mesh);
      if (this.isReady()) {
         this.tag = this.mesh.skinningData;
         this.softwareMesh = this.mesh.softwareMesh;
         this.assetParams = null;
      }
   }

   public static void vectorToWorldCoords(float x, float y, float z, float angle, Vector3 vec) {
      vec.x = -vec.x;
      vec.rotatey(angle);
      float y1 = vec.y;
      vec.y = vec.z;
      vec.z = y1;
      vec.x *= 1.5F;
      vec.y *= 1.5F;
      vec.z *= 0.61237234F;
      vec.x += x;
      vec.y += y;
      vec.z += z;
   }

   public static void boneToWorldCoords(AnimationPlayer animPlayer, float x, float y, float z, float animalSize, int boneIndex, Vector3 vec) {
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      vec.x *= animalSize;
      vec.y *= animalSize;
      vec.z *= animalSize;
      vectorToWorldCoords(x, y, z, animPlayer.getRenderedAngle(), vec);
   }

   public static void vectorToWorldCoords(IsoGameCharacter character, Vector3 vec) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      float angle = animPlayer.getRenderedAngle();
      vectorToWorldCoords(character.getX(), character.getY(), character.getZ(), angle, vec);
   }

   public static void boneToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      if (character instanceof IsoAnimal isoAnimal) {
         vec.x = vec.x * isoAnimal.getAnimalSize();
         vec.y = vec.y * isoAnimal.getAnimalSize();
         vec.z = vec.z * isoAnimal.getAnimalSize();
      }

      vectorToWorldCoords(character, vec);
   }

   public static void boneToWorldCoords(IsoGameCharacter character, SkinningBone bone, Vector3 vec) {
      boneToWorldCoords(character, bone.index, vec);
   }

   public static void boneZDirectionToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec, float length) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m02 * length;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m12 * length;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m22 * length;
      vec.x = vec.x + animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = vec.y + animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = vec.z + animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(character, vec);
   }

   public static void boneZDirectionToWorldCoords(IsoGameCharacter character, SkinningBone bone, Vector3 vec, float length) {
      boneZDirectionToWorldCoords(character, bone.index, vec, length);
   }

   public static void boneYDirectionToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec, float length) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m01 * length;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m11 * length;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m21 * length;
      vec.x = vec.x + animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = vec.y + animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = vec.z + animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(character, vec);
   }

   public static void vectorToWorldCoords(ModelSlotRenderData slotData, Vector3 vec) {
      float angle = slotData.animPlayerAngle;
      vectorToWorldCoords(slotData.x, slotData.y, slotData.z, angle, vec);
   }

   public static void boneToWorldCoords(ModelSlotRenderData slotData, int boneIndex, Vector3 vec) {
      AnimationPlayer animPlayer = slotData.animPlayer;
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(slotData, vec);
   }

   public static void boneToWorldCoords(ModelSlotRenderData slotData, SkinningBone bone, Vector3 vec) {
      boneToWorldCoords(slotData, bone.index, vec);
   }

   public static void worldToModel(IsoGameCharacter isoGameCharacter, Vector3 vector3) {
      AnimationPlayer animPlayer = isoGameCharacter.getAnimationPlayer();
      float angle = animPlayer.getRenderedAngle();
      vector3.x = vector3.x - isoGameCharacter.getX();
      vector3.y = vector3.y - isoGameCharacter.getY();
      vector3.z = vector3.z - isoGameCharacter.getZ();
      vector3.x /= 1.5F;
      vector3.y /= 1.5F;
      float t = vector3.z;
      vector3.z = vector3.y;
      vector3.y = t / 0.6F;
      vector3.rotatey(-angle);
      vector3.x = -vector3.x;
   }

   public static void CharacterModelCameraBegin(ModelSlotRenderData slotData) {
      ModelCamera.instance.Begin();
      if (slotData.inVehicle) {
         Matrix4f mv = Core.getInstance().modelViewMatrixStack.peek();
         mv.translate(0.0F, slotData.centerOfMassY, 0.0F);
         mv.rotate(slotData.vehicleAngleZ * (float) (Math.PI / 180.0), 0.0F, 0.0F, 1.0F);
         mv.rotate(slotData.vehicleAngleY * (float) (Math.PI / 180.0), 0.0F, 1.0F, 0.0F);
         mv.rotate(slotData.vehicleAngleX * (float) (Math.PI / 180.0), 1.0F, 0.0F, 0.0F);
         mv.rotate((float) Math.PI, 0.0F, 1.0F, 0.0F);
         int rightToLeftHand = -1;
         mv.translate(slotData.inVehicleX, slotData.inVehicleY, slotData.inVehicleZ * -1.0F);
         mv.scale(1.5F, 1.5F, 1.5F);
      }
   }

   public static void CharacterModelCameraEnd() {
      ModelCamera.instance.End();
   }

   private void DrawWireframe(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      GL11.glPolygonMode(1032, 6913);
      GL11.glEnable(2848);
      GL11.glLineWidth(0.75F);
      Shader effect2 = ShaderManager.instance.getOrCreateShader("vehicle_wireframe", this.isStatic, false);
      if (effect2 != null) {
         effect2.Start();
         if (this.isStatic) {
            effect2.setTransformMatrix(instData.xfrm, true);
         } else {
            effect2.setMatrixPalette(instData.matrixPalette, true);
         }

         float targetDepth = PerformanceSettings.fboRenderChunk
            ? (instData.modelInstance.parent == null ? instData.modelInstance.targetDepth : instData.modelInstance.parent.targetDepth)
            : 0.5F;
         effect2.setTargetDepth(targetDepth);
         this.mesh.Draw(effect2);
         effect2.End();
      }

      GL11.glPolygonMode(1032, 6914);
      GL11.glDisable(2848);
   }

   public static void SwapInstancedBasic() {
      modelDrawCounts.forEachEntry(Model::SwapInstancedBasic);
   }

   private static boolean SwapInstancedBasic(Model key, int value) {
      if (Core.debug && DebugOptions.instance.zombieRenderInstanced.getValue()) {
         if ((key.tex == null || !key.tex.getUseAlphaChannel()) && key.effect.getName().equals("basicEffect")) {
            if (value < instancingThreshold && key.effect.isInstanced()) {
               key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), false);
            } else if (value >= instancingThreshold && !key.effect.isInstanced()) {
               key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), true);
            }
         }
      } else if (key.effect.isInstanced()) {
         key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), false);
      }

      return true;
   }

   // pzopt: hordeBatch (pzopt.HordeBatch). A mesh's vertex buffer as the batch needs it: VertexBufferObject's handle and
   // vertex format are package-private / private, so the batch reads them through these helpers (the format once per mesh).
   private static java.lang.reflect.Field pzoptFormatField; // pzopt

   /** pzopt: hordeBatch, whether the stock Draw would draw this buffer (a handle with vertex data). */
   public static boolean pzoptMeshDrawable(VertexBufferObject vb) { // pzopt
      VertexBufferObject.Vbo h = vb.handle; // pzopt
      return h != null && !h.faceDataOnly && h.vboId > 0 && h.eboId > 0 && h.numElements > 0; // pzopt
   } // pzopt

   /** pzopt: hordeBatch, the identity the batch keys a mesh's vertex array object on. */
   public static Object pzoptMeshHandle(VertexBufferObject vb) { // pzopt
      return vb.handle; // pzopt
   } // pzopt

   public static int pzoptMeshVbo(VertexBufferObject vb) { // pzopt
      return vb.handle.vboId; // pzopt
   } // pzopt

   public static int pzoptMeshEbo(VertexBufferObject vb) { // pzopt
      return vb.handle.eboId; // pzopt
   } // pzopt

   public static int pzoptMeshElements(VertexBufferObject vb) { // pzopt
      return vb.handle.numElements; // pzopt
   } // pzopt

   /**
    * pzopt: hordeBatch. VertexBufferObject.BeginDraw's buffer bindings, pointers and enables for a triangle draw, issued
    * into the vertex array object the caller has bound (the same switch over the format's element types).
    */
   public static void pzoptMeshPointers(VertexBufferObject vb) { // pzopt
      VertexBufferObject.VertexFormat vertexFormat; // pzopt
      try { // pzopt
         if (pzoptFormatField == null) { // pzopt
            java.lang.reflect.Field f = VertexBufferObject.class.getDeclaredField("vertexFormat"); // pzopt
            f.setAccessible(true); // pzopt
            pzoptFormatField = f; // pzopt
         } // pzopt
         vertexFormat = (VertexBufferObject.VertexFormat)pzoptFormatField.get(vb); // pzopt
      } catch (ReflectiveOperationException e) { // pzopt
         throw new IllegalStateException(e); // pzopt
      } // pzopt
      VertexBufferObject.Vbo handle = vb.handle; // pzopt
      int textureNumber = 33984; // pzopt
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, handle.vboId); // pzopt
      for (int i = 0; i < vertexFormat.elements.length; i++) { // pzopt
         VertexBufferObject.VertexElement element = vertexFormat.elements[i]; // pzopt
         switch (element.type.ordinal()) { // pzopt
            case 0: // pzopt
               org.lwjgl.opengl.GL20.glVertexAttribPointer(i, 3, 5126, false, handle.vertexStride, element.byteOffset); // pzopt
               org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
               break; // pzopt
            case 1: // pzopt
               org.lwjgl.opengl.GL20.glVertexAttribPointer(i, 3, 5126, true, handle.vertexStride, element.byteOffset); // pzopt
               org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
               break; // pzopt
            case 2: // pzopt
               org.lwjgl.opengl.GL20.glVertexAttribPointer(i, 3, 5121, true, handle.vertexStride, element.byteOffset); // pzopt
               org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
               break; // pzopt
            case 4: // pzopt
               GL13.glActiveTexture(textureNumber); // pzopt
               org.lwjgl.opengl.GL20.glVertexAttribPointer(i, 2, 5126, false, handle.vertexStride, element.byteOffset); // pzopt
               org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
               textureNumber++; // pzopt
               break; // pzopt
            case 6: // pzopt
            case 7: // pzopt
               org.lwjgl.opengl.GL20.glVertexAttribPointer(i, 4, 5126, false, handle.vertexStride, element.byteOffset); // pzopt
               org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
               break; // pzopt
            default: // pzopt
               break; // pzopt
         } // pzopt
      } // pzopt
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, handle.eboId); // pzopt
   } // pzopt

   /**
    * pzopt: hordeBatch (hordeBatchVao=format), the identity of a mesh's vertex layout: the stride and each element's type
    * and offset (meshes with the same layout share one vertex array object, the buffers bound per mesh).
    */
   public static String pzoptMeshLayout(VertexBufferObject vb) { // pzopt
      VertexBufferObject.VertexFormat vertexFormat = pzoptFormat(vb); // pzopt
      StringBuilder sb = new StringBuilder().append(vb.handle.vertexStride); // pzopt
      for (int i = 0; i < vertexFormat.elements.length; i++) { // pzopt
         VertexBufferObject.VertexElement element = vertexFormat.elements[i]; // pzopt
         sb.append(';').append(element.type.ordinal()).append('@').append(element.byteOffset); // pzopt
      } // pzopt
      return sb.toString(); // pzopt
   } // pzopt

   private static VertexBufferObject.VertexFormat pzoptFormat(VertexBufferObject vb) { // pzopt
      try { // pzopt
         if (pzoptFormatField == null) { // pzopt
            java.lang.reflect.Field f = VertexBufferObject.class.getDeclaredField("vertexFormat"); // pzopt
            f.setAccessible(true); // pzopt
            pzoptFormatField = f; // pzopt
         } // pzopt
         return (VertexBufferObject.VertexFormat)pzoptFormatField.get(vb); // pzopt
      } catch (ReflectiveOperationException e) { // pzopt
         throw new IllegalStateException(e); // pzopt
      } // pzopt
   } // pzopt

   /**
    * pzopt: hordeBatch (hordeBatchVao=format). pzoptMeshPointers' attributes as a separate format (GL 4.3 vertex attribute
    * binding) on buffer binding 0, into the vertex array object the caller has bound: the same sizes, types,
    * normalisation and offsets; the stride and buffers come with pzoptMeshBind.
    */
   public static void pzoptMeshFormat(VertexBufferObject vb) { // pzopt
      VertexBufferObject.VertexFormat vertexFormat = pzoptFormat(vb); // pzopt
      for (int i = 0; i < vertexFormat.elements.length; i++) { // pzopt
         VertexBufferObject.VertexElement element = vertexFormat.elements[i]; // pzopt
         int size; // pzopt
         int type = 5126; // pzopt
         boolean normalized = false; // pzopt
         switch (element.type.ordinal()) { // pzopt
            case 0: size = 3; break; // pzopt
            case 1: size = 3; normalized = true; break; // pzopt
            case 2: size = 3; type = 5121; normalized = true; break; // pzopt
            case 4: size = 2; break; // pzopt
            case 6: // pzopt
            case 7: size = 4; break; // pzopt
            default: continue; // pzopt
         } // pzopt
         org.lwjgl.opengl.GL43.glVertexAttribFormat(i, size, type, normalized, element.byteOffset); // pzopt
         org.lwjgl.opengl.GL43.glVertexAttribBinding(i, 0); // pzopt
         org.lwjgl.opengl.GL20.glEnableVertexAttribArray(i); // pzopt
      } // pzopt
   } // pzopt

   /** pzopt: hordeBatch (hordeBatchVao=format), a mesh's buffers into the bound format vertex array object. */
   public static void pzoptMeshBind(VertexBufferObject vb) { // pzopt
      VertexBufferObject.Vbo handle = vb.handle; // pzopt
      org.lwjgl.opengl.GL43.glBindVertexBuffer(0, handle.vboId, 0L, handle.vertexStride); // pzopt
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, handle.eboId); // pzopt
   } // pzopt

   public void EnsureEffect() {
      if (this.effect == null) {
         this.CreateShader("basicEffect");
      }
   }

   private void DrawSolid(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (this.effect == null) {
         this.CreateShader("basicEffect");
      }

      Shader effect = this.effect;
      int drawCount = modelDrawCounts.get(this);
      modelDrawCounts.put(this, drawCount + 1);
      if (this.effect.isInstanced()) {
         Matrix4f mvp = Core.getInstance().modelViewMatrixStack.alloc();
         Matrix4f mv = Core.getInstance().modelViewMatrixStack.peek();
         Matrix4f p = Core.getInstance().projectionMatrixStack.peek();
         p.mul(mv, mvp);
         instData.properties.SetMatrix4("mvp", mvp).transpose();
         if (slotData.IsRenderingToCard()) {
            RenderList.DrawImmediate(slotData, instData);
         } else {
            RenderList.DrawQueued(slotData, instData);
         }

         Core.getInstance().modelViewMatrixStack.release(mvp);
      } else {
         if (effect != null) {
            effect.Start();
            effect.startCharacter(slotData, instData);
            effect.setScale(slotData.finalScale);
            pzopt.EntityShadow.bind(effect, slotData, instData); // pzopt: entity shadows, the draw's shade uniforms
            if (pzopt.HordeDraw.extraPalette()) effect.setMatrixPalette(instData.matrixPalette); // pzopt: horde model draws, dev mode 2 (the palette twice)
         }

         if (!DebugOptions.instance.debugDrawSkipDrawNonSkinnedModel.getValue() && pzopt.HordeDraw.meshDraw(this.mesh.vb != null && this.mesh.vb.handle != null ? this.mesh.vb.handle.numElements : 0, instData.matrixPalette)) { // pzopt: horde model draws, dev counters / mode 1 (no draw call)
            ProfileArea mvp = GameProfiler.getInstance().profile("Mesh.Draw.Call");

            try {
               if (!pzopt.OccludedOutline.drawMesh(this.mesh, effect)) { // pzopt: occluded outlines, a character's stencil codes (its stock draw plus the hidden part)
                  this.mesh.Draw(effect);
               } // pzopt
               pzopt.EntityShadow.unbind(effect); // pzopt: entity shadows, the program's shade back to none
            } catch (Throwable var9) {
               if (mvp != null) {
                  try {
                     mvp.close();
                  } catch (Throwable var8) {
                     var9.addSuppressed(var8);
                  }
               }

               throw var9;
            }

            if (mvp != null) {
               mvp.close();
            }
         }

         if (effect != null) {
            effect.setScale(1.0F);
            effect.End();
         }
      }
   }

   public void DrawChar(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (!DebugOptions.instance.character.debug.render.skipCharacters.getValue()) {
         if (!(slotData.alpha < 0.01F)) {
            if (slotData.animPlayer != null) {
               if (this.effect == null) {
                  this.CreateShader("basicEffect");
               }

               ModelScript modelScript = instData.modelInstance.modelScript;
               if (modelScript == null || modelScript.cullFace == -1) {
                  GL11.glEnable(2884);
                  GL11.glCullFace(1028);
               } else if (modelScript.cullFace == 0) {
                  GL11.glDisable(2884);
               } else {
                  GL11.glEnable(2884);
                  GL11.glCullFace(modelScript.cullFace);
               }

               GL11.glEnable(2929);
               GL11.glEnable(3008);
               GL11.glDepthFunc(513);
               GL11.glAlphaFunc(516, 0.01F);
               GL11.glBlendFunc(770, 771);
               if (Core.debug && DebugOptions.instance.model.render.wireframe.getValue()) {
                  this.DrawWireframe(slotData, instData);
               } else {
                  this.DrawSolid(slotData, instData);
               }

               GLStateRenderThread.restore();
            }
         }
      }
   }

   private void drawVehicleLights(ModelSlotRenderData slotData) {
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setLineWidth(1.0F);
      vbor.setDepthTest(false);

      for (int i = 0; i < slotData.effectLights.length; i++) {
         EffectLight el = slotData.effectLights[i];
         if (!(el.radius <= 0.0F)) {
            float x = el.x;
            float y = el.y;
            float z = el.z;
            float swap = z;
            z = y;
            y = swap;
            x *= -54.0F;
            y *= 54.0F;
            z *= 54.0F;
            vbor.addLine(0.0F, 0.0F, 0.0F, x, y, z, 1.0F, 1.0F, 0.0F, 1.0F);
         }
      }

      vbor.endRun();
      vbor.flush();
      GL11.glEnable(2929);
   }

   public static void drawBoneMtx(org.lwjgl.util.vector.Matrix4f boneMtx) {
      drawBoneMtxInternal(boneMtx);
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   private static void drawBoneMtxInternal(org.lwjgl.util.vector.Matrix4f boneMtx) {
      float scale = 0.5F;
      float arrowHeadFraction = 0.15F;
      float arrowHeadRadiusFraction = 0.1F;
      float posx = boneMtx.m03;
      float posy = boneMtx.m13;
      float posz = boneMtx.m23;
      float xAxisx = boneMtx.m00;
      float xAxisy = boneMtx.m10;
      float xAxisz = boneMtx.m20;
      float yAxisx = boneMtx.m01;
      float yAxisy = boneMtx.m11;
      float yAxisz = boneMtx.m21;
      float zAxisx = boneMtx.m02;
      float zAxisy = boneMtx.m12;
      float zAxisz = boneMtx.m22;
      drawArrowInternal(posx, posy, posz, xAxisx, xAxisy, xAxisz, zAxisx, zAxisy, zAxisz, 0.5F, 0.15F, 0.1F, 1.0F, 0.0F, 0.0F);
      drawArrowInternal(posx, posy, posz, yAxisx, yAxisy, yAxisz, zAxisx, zAxisy, zAxisz, 0.5F, 0.15F, 0.1F, 0.0F, 1.0F, 0.0F);
      drawArrowInternal(posx, posy, posz, zAxisx, zAxisy, zAxisz, xAxisx, xAxisy, xAxisz, 0.5F, 0.15F, 0.1F, 0.0F, 0.0F, 1.0F);
   }

   private static void drawArrowInternal(
      float posx,
      float posy,
      float posz,
      float fwdAxisx,
      float fwdAxisy,
      float fwdAxisz,
      float leftAxisx,
      float leftAxisy,
      float leftAxisz,
      float scale,
      float arrowHeadLength,
      float arrowHeadRadius,
      float red,
      float green,
      float blue
   ) {
      float arrowHeadInvLength = 1.0F - arrowHeadLength;
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.addLine(posx, posy, posz, posx + fwdAxisx * scale, posy + fwdAxisy * scale, posz + fwdAxisz * scale, red, green, blue, 1.0F);
      vbor.addLine(
         posx + fwdAxisx * scale,
         posy + fwdAxisy * scale,
         posz + fwdAxisz * scale,
         posx + (fwdAxisx * arrowHeadInvLength + leftAxisx * arrowHeadRadius) * scale,
         posy + (fwdAxisy * arrowHeadInvLength + leftAxisy * arrowHeadRadius) * scale,
         posz + (fwdAxisz * arrowHeadInvLength + leftAxisz * arrowHeadRadius) * scale,
         red,
         green,
         blue,
         1.0F
      );
      vbor.addLine(
         posx + fwdAxisx * scale,
         posy + fwdAxisy * scale,
         posz + fwdAxisz * scale,
         posx + (fwdAxisx * arrowHeadInvLength - leftAxisx * arrowHeadRadius) * scale,
         posy + (fwdAxisy * arrowHeadInvLength - leftAxisy * arrowHeadRadius) * scale,
         posz + (fwdAxisz * arrowHeadInvLength - leftAxisz * arrowHeadRadius) * scale,
         red,
         green,
         blue,
         1.0F
      );
   }

   public void debugDrawLightSource(IsoLightSource ls, float cx, float cy, float cz, float radians) {
      debugDrawLightSource(ls.x, ls.y, ls.z, cx, cy, cz, radians);
   }

   public static void debugDrawLightSource(float lx, float ly, float lz, float cx, float cy, float cz, float radians) {
      float x = lx - cx + 0.5F;
      float y = ly - cy + 0.5F;
      float z = lz - cz + 0.0F;
      x *= 0.67F;
      y *= 0.67F;
      float vx = x;
      float vy = y;
      x = (float)(vx * Math.cos(radians) - vy * Math.sin(radians));
      y = (float)(vx * Math.sin(radians) + vy * Math.cos(radians));
      x *= -1.0F;
      float swap = z;
      z = y;
      y = swap;
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setDepthTest(false);
      vbor.setLineWidth(1.0F);
      vbor.addLine(x, y, z, x + 0.1F, y, z, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y + 0.1F, z, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y, z + 0.1F, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.endRun();
      vbor.flush();
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   public void DrawVehicle(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (!DebugOptions.instance.model.render.skipVehicles.getValue()) {
         ModelInstance inst = instData.modelInstance;
         float ambientR = slotData.ambientR;
         float ambientG = slotData.ambientG;
         float ambientB = slotData.ambientB;
         Texture instTex = instData.tex;
         float tintR = instData.tintR;
         float tintG = instData.tintG;
         float tintB = instData.tintB;
         PZGLUtil.checkGLErrorThrow("Model.drawVehicle Enter inst: %s, instTex: %s, slotData: %s", new Object[]{inst, instTex, slotData});
         GL11.glEnable(2884);
         GL11.glCullFace(inst.modelScript != null && inst.modelScript.invertX ? 1029 : 1028);
         GL11.glEnable(2929);
         GL11.glDepthFunc(513);
         ModelCamera.instance.Begin();
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.peek();
         modelView.translate(0.0F, slotData.centerOfMassY, 0.0F);
         float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
         instData.modelInstance.targetDepth = slotData.squareDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F;
         Shader effect = this.effect;
         PZGLUtil.pushAndMultMatrix(5888, instData.xfrm);
         if (Core.debug && DebugOptions.instance.model.render.wireframe.getValue()) {
            GL11.glPolygonMode(1032, 6913);
            GL11.glEnable(2848);
            GL11.glLineWidth(0.75F);
            effect = ShaderManager.instance.getOrCreateShader("vehicle_wireframe", this.isStatic, false);
            if (effect != null) {
               effect.Start();
               if (this.isStatic) {
                  effect.setTransformMatrix(IDENTITY, false);
               } else {
                  effect.setMatrixPalette(instData.matrixPalette, true);
               }

               float targetDepth = PerformanceSettings.fboRenderChunk ? instData.modelInstance.targetDepth : 0.5F;
               effect.setTargetDepth(targetDepth);
               this.mesh.Draw(effect);
               effect.End();
            }

            GL11.glDisable(2848);
            PZGLUtil.popMatrix(5888);
            ModelCamera.instance.End();
         } else {
            if (effect != null) {
               effect.Start();
               this.setLights(slotData, slotData.effectLights.length);
               VehicleModelInstance pzoptVmi = null; // pzopt: car glass
            if (effect.isVehicleShader()) {
                  VehicleModelInstance vmi = (VehicleModelInstance)Type.tryCastTo(inst, VehicleModelInstance.class);
                  if (inst instanceof VehicleSubModelInstance) {
                     vmi = (VehicleModelInstance)Type.tryCastTo(inst.parent, VehicleModelInstance.class);
                  }

                  effect.setTexture(vmi.tex, "Texture0", 0);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureRust, "TextureRust", 1);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureMask, "TextureMask", 2);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureLights, "TextureLights", 3);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage1Overlay, "TextureDamage1Overlay", 4);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage1Shell, "TextureDamage1Shell", 5);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage2Overlay, "TextureDamage2Overlay", 6);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage2Shell, "TextureDamage2Shell", 7);
                  GL11.glTexEnvi(8960, 8704, 7681);

                  try {
                     if (Core.getInstance().getPerfReflectionsOnLoad() && !Core.getInstance().getUseOpenGL21()) {
                        effect.setTexture((Texture)SkyBox.getInstance().getTextureCurrent(), "TextureReflectionA", 8);
                        PZGLUtil.checkGLError(false);
                     }
                  } catch (Throwable var17) {
                  }

                  try {
                     if (Core.getInstance().getPerfReflectionsOnLoad() && !Core.getInstance().getUseOpenGL21()) {
                        effect.setTexture((Texture)SkyBox.getInstance().getTexturePrev(), "TextureReflectionB", 9);
                        PZGLUtil.checkGLError(false);
                     }
                  } catch (Throwable var16) {
                  }

                  effect.setReflectionParam(SkyBox.getInstance().getTextureShift(), vmi.refWindows, vmi.refBody);
                  effect.setTextureUninstall1(vmi.textureUninstall1);
                  effect.setTextureUninstall2(vmi.textureUninstall2);
                  effect.setTextureLightsEnables1(vmi.textureLightsEnables1);
                  effect.setTextureLightsEnables2(vmi.textureLightsEnables2);
                  effect.setTextureDamage1Enables1(vmi.textureDamage1Enables1);
                  effect.setTextureDamage1Enables2(vmi.textureDamage1Enables2);
                  effect.setTextureDamage2Enables1(vmi.textureDamage2Enables1);
                  effect.setTextureDamage2Enables2(vmi.textureDamage2Enables2);
                  effect.setMatrixBlood1(vmi.matrixBlood1Enables1, vmi.matrixBlood1Enables2);
                  effect.setMatrixBlood2(vmi.matrixBlood2Enables1, vmi.matrixBlood2Enables2);
                  effect.setTextureRustA(vmi.textureRustA);
                  effect.setTexturePainColor(vmi.painColor, slotData.alpha);
                  pzoptVmi = vmi; // pzopt: car glass
                  if (this.isStatic) {
                     effect.setTransformMatrix(IDENTITY, false);
                  } else {
                     effect.setMatrixPalette(instData.matrixPalette, true);
                  }
               } else if (inst instanceof VehicleSubModelInstance) {
                  GL13.glActiveTexture(33984);
                  effect.setTexture(instTex, "Texture", 0);
                  effect.setShaderAlpha(slotData.alpha);
                  if (this.isStatic) {
                     effect.setTransformMatrix(IDENTITY, false);
                  }
               } else {
                  GL13.glActiveTexture(33984);
                  effect.setTexture(instTex, "Texture", 0);
               }

               effect.setAmbient(ambientR, ambientG, ambientB);
               effect.setTint(tintR, tintG, tintB);
               float targetDepth = PerformanceSettings.fboRenderChunk ? instData.modelInstance.targetDepth : 0.5F;
               effect.setTargetDepth(targetDepth);
               pzopt.EntityShadow.bind(effect, slotData, instData); // pzopt: entity shadows, the draw's shade uniforms
               boolean pzoptGlint = pzopt.HdrGlint.vehicleOn(effect.isVehicleShader()); // pzopt: HDR output, the car's speculars / lamps into the glint target
               this.mesh.Draw(effect);
               pzopt.EntityShadow.unbind(effect); // pzopt: entity shadows, the program's shade back to none
               if (pzoptGlint) {
                  pzopt.HdrGlint.vehicleOff(); // pzopt: HDR output
               }
               effect.End();
               Shader pzoptGlass = pzopt.CarGlass.glassFor(effect, slotData, instData, pzoptVmi); // pzopt: car glass, the windows again with the glass program
               if (pzoptGlass != null) { // pzopt
                  this.pzoptDrawGlass(pzoptGlass, slotData, instData, pzoptVmi, ambientR, ambientG, ambientB, tintR, tintG, tintB, targetDepth); // pzopt
               } // pzopt
            }

            if (Core.debug && DebugOptions.instance.model.render.lights.getValue() && instData == slotData.modelData.get(0)) {
               this.drawVehicleLights(slotData);
            }

            PZGLUtil.popMatrix(5888);
            ModelCamera.instance.End();
            PZGLUtil.checkGLErrorThrow("Model.drawVehicle Exit inst: %s, instTex: %s, slotData: %s", new Object[]{inst, instTex, slotData});
         }
      }
   }

   // pzopt: car glass (pzopt.CarGlass): the car body's mesh drawn once more with the glass program, which discards every
   // texel but the windows' and shades those as glass; the stock draw's uniforms set on it the same way, depth LEQUAL (the
   // stock draw wrote the same depth)
   private void pzoptDrawGlass(Shader glass, ModelSlotRenderData slotData, ModelInstanceRenderData instData, VehicleModelInstance vmi, float ambientR,
         float ambientG, float ambientB, float tintR, float tintG, float tintB, float targetDepth) {
      glass.Start();
      if (!pzopt.CarGlass.compact()) {
         // the stock-based glass program (carGlassCompact=false, A/B) reads the stock uniforms: set them as the stock draw did
         for (int i = 0; i < 5; i++) {
            EffectLight el = slotData.effectLights[i];
            glass.setLight(i, el.x, el.y, el.z, el.r, el.g, el.b, el.radius, slotData.animPlayerAngle, slotData.x, slotData.y, slotData.z, slotData.object);
         }
         glass.setTexture(vmi.tex, "Texture0", 0);
         glass.setTexture(vmi.textureRust, "TextureRust", 1);
         glass.setTexture(vmi.textureMask, "TextureMask", 2);
         glass.setTexture(vmi.textureLights, "TextureLights", 3);
         glass.setTexture(vmi.textureDamage1Overlay, "TextureDamage1Overlay", 4);
         glass.setTexture(vmi.textureDamage1Shell, "TextureDamage1Shell", 5);
         glass.setTexture(vmi.textureDamage2Overlay, "TextureDamage2Overlay", 6);
         glass.setTexture(vmi.textureDamage2Shell, "TextureDamage2Shell", 7);
         glass.setReflectionParam(SkyBox.getInstance().getTextureShift(), vmi.refWindows, vmi.refBody);
         glass.setTextureUninstall1(vmi.textureUninstall1);
         glass.setTextureUninstall2(vmi.textureUninstall2);
         glass.setTextureLightsEnables1(vmi.textureLightsEnables1);
         glass.setTextureLightsEnables2(vmi.textureLightsEnables2);
         glass.setTextureDamage1Enables1(vmi.textureDamage1Enables1);
         glass.setTextureDamage1Enables2(vmi.textureDamage1Enables2);
         glass.setTextureDamage2Enables1(vmi.textureDamage2Enables1);
         glass.setTextureDamage2Enables2(vmi.textureDamage2Enables2);
         glass.setMatrixBlood1(vmi.matrixBlood1Enables1, vmi.matrixBlood1Enables2);
         glass.setMatrixBlood2(vmi.matrixBlood2Enables1, vmi.matrixBlood2Enables2);
         glass.setTextureRustA(vmi.textureRustA);
         glass.setTexturePainColor(vmi.painColor, slotData.alpha);
         glass.setAmbient(ambientR, ambientG, ambientB);
         glass.setTint(tintR, tintG, tintB);
      }
      // (the compact program: the stock draw left the textures bound on their units; its samplers keep those units; the
      // windows' flags, the light and the alpha come with the glass data)
      if (this.isStatic) {
         glass.setTransformMatrix(IDENTITY, false);
      } else {
         glass.setMatrixPalette(instData.matrixPalette, true);
      }
      glass.setTargetDepth(targetDepth);
      pzopt.CarGlass.draw(glass, slotData, instData, vmi, ambientR, ambientG, ambientB, tintR, tintG, tintB);
      GL11.glDepthFunc(515);
      boolean pzoptOffset = pzopt.Config.CAR_GLASS_DEPTH_OFFSET; // pzopt: the glass over its own window whatever the driver's rounding (Mesa: the copy's depth a step off, LEQUAL failed per pixel)
      if (pzoptOffset) { // pzopt
         GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); // pzopt
         GL11.glPolygonOffset(-1.0F, -4.0F); // pzopt
      } // pzopt
      int[] tris = pzopt.CarGlass.glassTriangles(this.mesh, vmi);
      if (tris != null && this.mesh.vb != null) {
         boolean blend = this.mesh.vb.BeginDraw(glass);
         org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, tris[0]);
         GL11.glDrawElements(4, tris[1], 5125, 0L);
         this.mesh.vb.FinishDraw(glass, blend);
      } else {
         this.mesh.Draw(glass);
      }
      if (pzoptOffset) { // pzopt
         GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL); // pzopt
         GL11.glPolygonOffset(0.0F, 0.0F); // pzopt
      } // pzopt
      GL11.glDepthFunc(513);
      glass.End();
   }

   public static void debugDrawAxis(float x, float y, float z, boolean flipX, boolean flipY, boolean flipZ, float length, float thickness) {
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setDepthTest(false);
      vbor.setLineWidth(Math.min(thickness, 1.0F));
      vbor.addLine(x, y, z, x + length * (flipX ? -1 : 1), y, z, 1.0F, 0.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y + length * (flipY ? -1 : 1), z, 0.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y, z + length * (flipZ ? -1 : 1), 0.0F, 0.0F, 1.0F, 1.0F);
      vbor.endRun();
      vbor.flush();
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   public static void debugDrawAxis(float x, float y, float z, float length, float thickness) {
      debugDrawAxis(x, y, z, false, false, false, length, thickness);
   }

   public void setLights(ModelSlotRenderData slotData, int lightCount) {
      for (int i = 0; i < lightCount; i++) {
         EffectLight el = slotData.effectLights[i];
         this.effect.setLight(i, el.x, el.y, el.z, el.r, el.g, el.b, el.radius, slotData.animPlayerAngle, slotData.x, slotData.y, slotData.z, slotData.object);
      }

      for (int i = lightCount; i < 5; i++) {
         this.effect.setLight(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, null);
      }
   }

   public void setLightsInst(ModelSlotRenderData slotData, int lightCount) {
      for (int i = 0; i < lightCount; i++) {
         EffectLight el = slotData.effectLights[i];
         this.effect
            .setLightInst(i, el.x, el.y, el.z, el.r, el.g, el.b, el.radius, slotData.animPlayerAngle, slotData.x, slotData.y, slotData.z, slotData.properties);
      }

      for (int i = lightCount; i < 5; i++) {
         this.effect.setLightInst(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, null);
      }
   }

   public void CreateShader(String name) {
      if (!ModelManager.noOpenGL) {
         // pzopt: a shader an earlier model already created is taken from pzopt.ModelShaders; the blocking round trip to
         // pzopt: the render thread (one render step per model, 16.5 s for the 73 animal models on a laptop) is only paid
         // pzopt: by the first model per shader name (Config shaderCache)
         if (pzopt.ModelShaders.enabled()) {
            Shader cached = pzopt.ModelShaders.get(name, this.isStatic); // pzopt
            if (cached != null) { // pzopt
               this.effect = cached; // pzopt
               return; // pzopt
            }
         }

         long t0 = System.nanoTime(); // pzopt
         Lambda.invoke(
            RenderThread::invokeOnRenderContext,
            this,
            name,
            (lThis, lName) -> lThis.effect = ShaderManager.instance.getOrCreateShader(lName, lThis.isStatic, false)
         );
         pzopt.ModelShaders.created(name, this.isStatic, this.effect, System.nanoTime() - t0); // pzopt
      }
   }

   public AssetType getType() {
      return ASSET_TYPE;
   }

   protected void onBeforeReady() {
      super.onBeforeReady();
      this.tag = this.mesh.skinningData;
      this.softwareMesh = this.mesh.softwareMesh;
      this.assetParams = null;
   }

   public static final class ModelAssetParams extends AssetParams {
      public String meshName;
      public String textureName;
      public int textureFlags;
      public String shaderName;
      public boolean isStatic;
      public ModelMesh animationsModel;
      public String postProcess;
   }
}
