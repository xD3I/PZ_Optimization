package zombie.core.skinnedmodel.model;

import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import zombie.EffectsManager;
import zombie.GameProfiler;
import zombie.GameProfiler.ProfileArea;
import zombie.ai.states.PlayerGetUpState;
import zombie.characters.Imposter;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderSettings;
import zombie.core.opengl.ShaderProgram;
import zombie.core.rendering.RenderTarget;
import zombie.core.rendering.RenderTexture;
import zombie.core.rendering.ShaderPropertyBlock;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.ModelManager.ModelSlot;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstance.EffectLight;
import zombie.core.skinnedmodel.model.ModelInstance.PlayerData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugOptions;
import zombie.gameStates.DebugChunkState;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.PlayerCamera;
import zombie.iso.IsoDepthHelper.Results;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderTracerEffects;
import zombie.network.GameServer;
import zombie.network.ServerGUI;
import zombie.popman.ObjectPool;
import zombie.scripting.objects.ModelAttachment;
import zombie.seating.SeatingManager;
import zombie.util.Pool;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Matrix4fObjectPool;
import zombie.vehicles.BaseVehicle.Vector3fObjectPool;

public final class ModelSlotRenderData extends GenericDrawer {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.model.ModelSlotRenderData");
   }

   /**
    * pzopt: hordeBatch (pzopt.HordeBatch). renderCharacter's prelude for a character whose model draws go into a batch:
    * the texture checks (checkReady) and the camera's depth mask, as the stock method does them; returns the list of
    * instance data performRenderCharacter would draw, or null when it would draw nothing. The caller has checked that
    * none of the debug, imposter and outline branches applies.
    */
   public ModelInstanceRenderDataList pzoptBatchBegin() { // pzopt
      this.checkReady(); // pzopt
      if (!this.ready && this.readyModelData.isEmpty()) { // pzopt
         return null; // pzopt
      } // pzopt
      ModelCamera.instance.depthMask = true; // pzopt
      return this.character != null && !this.ready ? this.readyModelData : this.modelData; // pzopt
   } // pzopt

   /**
    * pzopt: hordeBatch, whether checkReady would render a texture now (the clothes' composite or an item's texture):
    * the batch draws what is pending first, so every earlier model draws before the render as in the stock order (a
    * pooled combiner texture can be one an earlier character still has to draw with).
    */
   public boolean pzoptRendersTexture() { // pzopt
      if (this.textureCreator != null && !this.textureCreator.isRendered() && !this.pzoptSkipTexture) { // pzopt (zombieFirstSightSprite: a waiting composite renders nothing)
         return true; // pzopt
      } // pzopt
      for (int i = 0; i < this.modelData.size(); i++) { // pzopt
         ModelInstanceTextureInitializer miti = ((ModelInstanceRenderData)this.modelData.get(i)).modelInstance.getTextureInitializer(); // pzopt
         if (miti != null && !miti.isRendered()) { // pzopt
            return true; // pzopt
         } // pzopt
      } // pzopt
      return false; // pzopt
   } // pzopt

   /** pzopt: hordeBatch, the end of renderCharacter for a batched character (no player outline: zombies only). */
   public void pzoptBatchEnd() { // pzopt
      this.rendered = this.ready; // pzopt
   } // pzopt

   /** pzopt: hordeBatch, whether renderCharacter would draw the character's outline first. */
   public boolean pzoptHasOutline() { // pzopt
      return this.characterOutline; // pzopt
   } // pzopt

   /** pzopt: hordeBatch, whether a debug draw would follow the model draws (debug builds only). */
   public boolean pzoptHasDebugDraw() { // pzopt
      return this.debugRenderData != null; // pzopt
   } // pzopt

   private static final ObjectPool<ModelSlotRenderData> pool = new ObjectPool(ModelSlotRenderData::new, "ModelSlotRenderData.pool");
   private static final int[] vp = new int[4];
   private final ColorInfo tempColorInfo = new ColorInfo();
   private int playerIndex;
   private float camCharacterX;
   private float camCharacterY;
   private float fixJigglyModelsSquareX;
   private float fixJigglyModelsSquareY;
   public IsoGameCharacter character;
   public IsoMovingObject object;
   public ModelSlot modelSlot;
   public final ModelInstanceRenderDataList modelData = new ModelInstanceRenderDataList();
   private final ModelInstanceRenderDataList readyModelData = new ModelInstanceRenderDataList();
   public ModelInstanceTextureCreator textureCreator;
   public boolean pzoptSightEligible; // pzopt: zombieFirstSightSprite, a first composite the render thread's budget may hold a frame (the zombie is drawn as its sprite)
   public boolean pzoptSkipTexture; // pzopt: zombieFirstSightSprite, this frame the character's first clothing composite waits (pzopt.FirstSight): checkReady does not make it
   public AnimationPlayer animPlayer;
   public float animPlayerAngle;
   public float x;
   public float y;
   public float z;
   public float ambientR;
   public float ambientG;
   public float ambientB;
   public boolean outside;
   public float finalScale = 1.0F;
   public boolean debugChunkState;
   public final Matrix4f vehicleTransform = new Matrix4f();
   public boolean inVehicle;
   public float inVehicleX;
   public float inVehicleY;
   public float inVehicleZ;
   public float vehicleAngleX;
   public float vehicleAngleY;
   public float vehicleAngleZ;
   public float alpha;
   private boolean rendered;
   private boolean ready;
   public final EffectLight[] effectLights = new EffectLight[5];
   public float centerOfMassY;
   public boolean renderToTexture;
   public static Shader solidColor;
   public static Shader solidColorStatic;
   private boolean characterOutline;
   private final ColorInfo outlineColor = new ColorInfo(1.0F, 0.0F, 0.0F, 1.0F);
   private boolean outlineBehindPlayer = true;
   public float squareDepth;
   public final ShaderPropertyBlock properties = new ShaderPropertyBlock();
   private ModelSlotDebugRenderData debugRenderData;
   private boolean renderingToCard;
   private static VertexBufferObject cardQuad;

   public boolean IsRenderingToCard() {
      return this.renderingToCard;
   }

   public ModelSlotRenderData() {
      for (int i = 0; i < this.effectLights.length; i++) {
         this.effectLights[i] = new EffectLight();
      }
   }

   public void initModel(ModelSlot modelSlot) {
      int playerIndex = this.playerIndex = IsoCamera.frameState.playerIndex;
      this.camCharacterX = IsoCamera.frameState.camCharacterX;
      this.camCharacterY = IsoCamera.frameState.camCharacterY;
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      this.fixJigglyModelsSquareX = camera.fixJigglyModelsSquareX;
      this.fixJigglyModelsSquareY = camera.fixJigglyModelsSquareY;
      this.object = modelSlot.model.object;
      this.character = modelSlot.character;
      this.x = this.object.getX();
      this.y = this.object.getY();
      this.z = this.object.getZ();
      this.finalScale = 1.0F;
      this.debugChunkState = PerformanceSettings.fboRenderChunk && FBORenderCell.instance.renderDebugChunkState;
      if (this.debugChunkState) {
         this.finalScale = DebugChunkState.instance.getObjectAtCursorScale();
      }

      this.modelData.clear();
      this.modelSlot = modelSlot;
      ModelInstanceRenderData slotInstData = null;
      if (modelSlot.model.model.isReady() && (modelSlot.model.animPlayer == null || modelSlot.model.animPlayer.isReady())) {
         slotInstData = ModelInstanceRenderData.alloc();
         slotInstData.initModel(modelSlot.model, null);
         this.modelData.add(slotInstData);
      }

      this.initModelInst(modelSlot.sub, slotInstData);
      if (Core.debug) {
         this.debugRenderData = ModelSlotDebugRenderData.alloc();
         this.debugRenderData.initModel(this);
      }

      boolean bHasTextureCreators = false;

      for (int i = 0; i < this.modelData.size(); i++) {
         ModelInstanceRenderData instData = (ModelInstanceRenderData)this.modelData.get(i);
         if (instData.modelInstance != null && instData.modelInstance.hasTextureCreator()) {
            bHasTextureCreators = true;
            break;
         }
      }

      if (this.object instanceof BaseVehicle) {
         this.textureCreator = null;
      } else {
         this.textureCreator = this.character.getTextureCreator();
         if (this.textureCreator != null) {
            if (this.textureCreator.isRendered()) {
               this.textureCreator = null;
            } else {
               this.textureCreator.renderRefCount++;
            }
         }

         this.characterOutline = this.character.isOutlineHighlight(playerIndex);
      }
      this.pzoptSkipTexture = this.textureCreator != null && pzopt.FirstSight.deferred(this.character); // pzopt: zombieFirstSightSprite
      this.pzoptSightEligible = this.textureCreator != null && pzopt.FirstSight.eligible(this.character); // pzopt

      if (this.character != null && (this.textureCreator != null || bHasTextureCreators)) {
         assert this.readyModelData.isEmpty();
         ModelInstanceRenderData.release(this.readyModelData);
         this.readyModelData.clear();

         for (int i = 0; i < this.character.getReadyModelData().size(); i++) {
            ModelInstance modelInstance = (ModelInstance)this.character.getReadyModelData().get(i);
            ModelInstanceRenderData data = ModelInstanceRenderData.alloc();
            data.initModel(modelInstance, this.getParentData(modelInstance));
            this.readyModelData.add(data);
         }
      }

      if (this.object instanceof BaseVehicle vehicle) {
         float amb = RenderSettings.getInstance().getAmbientForPlayer(playerIndex);
         this.ambientR = this.ambientG = this.ambientB = amb;
         IsoGridSquare square = vehicle.getCurrentSquare();
         if (square != null) {
            ColorInfo lightInfo = this.tempColorInfo;
            square.interpolateLight(lightInfo, vehicle.getX() - square.getX(), vehicle.getY() - square.getY());
            this.ambientR = lightInfo.r;
            this.ambientG = lightInfo.g;
            this.ambientB = lightInfo.b;
         }

         if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
            this.ambientR = this.ambientG = this.ambientB = 1.0F;
         }
      }
   }

   private void initModelInst(ArrayList<ModelInstance> modelInstances, AnimatedModelInstanceRenderData parentData) {
      for (int i = 0; i < modelInstances.size(); i++) {
         ModelInstance modelInstance = modelInstances.get(i);
         if (modelInstance.model.isReady() && (modelInstance.animPlayer == null || modelInstance.animPlayer.isReady())) {
            ModelInstanceRenderData instData = ModelInstanceRenderData.alloc();
            instData.initModel(modelInstance, parentData);
            this.modelData.add(instData);
            if (this.character != null && instData.modelInstance == this.character.primaryHandModel) {
               EffectsManager.getInstance().initMuzzleFlashModel(instData, this.character, this.modelData, this.modelSlot);
            }

            this.initModelInst(modelInstance.sub, instData);
         }
      }
   }

   public synchronized ModelSlotRenderData init(ModelSlot modelSlot) {
      int playerIndex = this.playerIndex;
      this.modelSlot = modelSlot;
      Results results = IsoDepthHelper.getSquareDepthData(
         PZMath.fastfloor(this.camCharacterX),
         PZMath.fastfloor(this.camCharacterY),
         this.x + this.fixJigglyModelsSquareX,
         this.y + this.fixJigglyModelsSquareY,
         this.z
      );
      this.squareDepth = results.depthStart;
      if (this.object instanceof BaseVehicle vehicle) {
         this.animPlayer = vehicle.getAnimationPlayer();
         this.animPlayerAngle = Float.NaN;
         BaseVehicle.centerOfMassMagic = 0.7F;
         this.centerOfMassY = vehicle.jniTransform.origin.y - BaseVehicle.centerOfMassMagic;
         this.centerOfMassY = this.centerOfMassY - vehicle.getZ() * 3.0F * 0.8164967F;
         if (BaseVehicle.renderToTexture) {
            this.centerOfMassY = 0.0F - BaseVehicle.centerOfMassMagic;
         }

         this.squareDepth = IsoDepthHelper.getSquareDepthData(
               PZMath.fastfloor(this.camCharacterX), PZMath.fastfloor(this.camCharacterY), this.x, this.y, vehicle.jniTransform.origin.y / 2.44949F
            )
            .depthStart;
         this.alpha = this.object.getAlpha(playerIndex);
         VehicleModelInstance vehicleModelInstance = (VehicleModelInstance)modelSlot.model;
         EffectLight[] isoLights = vehicleModelInstance.getLights();

         for (int i = 0; i < this.effectLights.length; i++) {
            this.effectLights[i].clear();
         }

         Vector3f lightVec = (Vector3f)((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc();

         for (int i = 0; i < isoLights.length; i++) {
            EffectLight ls = isoLights[i];
            if (ls != null && ls.radius > 0) {
               Vector3f local = vehicle.getLocalPos(ls.x, ls.y, ls.z + 0.75F, lightVec);
               this.effectLights[i].set(local.x, local.y, local.z, ls.r, ls.g, ls.b, ls.radius);
            }
         }

         ((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(lightVec);
         this.vehicleTransform.set(vehicle.vehicleTransform);
      } else {
         IsoAnimal animal = (IsoAnimal)Type.tryCastTo(this.character, IsoAnimal.class);
         if (animal != null && animal.getData() != null) {
            this.finalScale = animal.getData().getSize();
         }

         PlayerData playerData = modelSlot.model.playerData[playerIndex];
         this.animPlayer = this.character.getAnimationPlayer();
         this.animPlayerAngle = this.animPlayer.getRenderedAngle();

         for (int i = 0; i < this.effectLights.length; i++) {
            if (this.debugChunkState) {
               this.effectLights[i].clear();
            } else {
               EffectLight el = playerData.effectLightsMain[i];
               this.effectLights[i].set(el.x, el.y, el.z, el.r, el.g, el.b, el.radius);
            }
         }

         this.outside = this.character.getCurrentSquare() != null && this.character.getCurrentSquare().isOutside();
         this.alpha = this.character.getAlpha(playerIndex);
         if (this.character instanceof zombie.characters.IsoZombie pzoptZ && pzoptZ.pzoptLodModelAlpha < 1.0F) { // pzopt: zombieLodFadeMs, the model's share during a crossfade
            this.alpha *= pzoptZ.pzoptLodModelAlpha; // pzopt
         } // pzopt
         if ((!Core.debug || !DebugOptions.instance.debugDrawSkipWorldShading.getValue()) && (!GameServer.server || !ServerGUI.isCreated())) {
            this.ambientR = playerData.currentAmbient.x;
            this.ambientG = playerData.currentAmbient.y;
            this.ambientB = playerData.currentAmbient.z;
         } else {
            this.ambientR = this.ambientG = this.ambientB = 1.0F;
         }

         if (FBORenderCell.instance.isBlackedOutBuildingSquare(this.character.getCurrentSquare())) {
            for (int i = 0; i < this.effectLights.length; i++) {
               this.effectLights[i].clear();
            }

            float fade = 1.0F - FBORenderCell.instance.getBlackedOutRoomFadeRatio(this.character.getRenderSquare());
            this.ambientR *= fade;
            this.ambientG *= fade;
            this.ambientB *= fade;
         }

         if (this.characterOutline) {
            this.outlineColor.setABGR(this.character.getOutlineHighlightCol(playerIndex));
            this.outlineBehindPlayer = this.character.isProne() || this.x + this.y < this.camCharacterX + this.camCharacterY;
         }

         this.adjustForSittingOnFurniture();
         this.inVehicle = this.character.isSeatedInVehicle();
         if (this.inVehicle) {
            this.animPlayerAngle = 0.0F;
            BaseVehicle vehicle1 = this.character.getVehicle();
            this.centerOfMassY = vehicle1.jniTransform.origin.y - BaseVehicle.centerOfMassMagic;
            this.x = vehicle1.getX();
            this.y = vehicle1.getY();
            this.z = vehicle1.getZ();
            Vector3f pos = (Vector3f)((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc();
            vehicle1.getPassengerLocalPos(vehicle1.getSeat(this.character), pos);
            this.inVehicleX = pos.x;
            this.inVehicleY = pos.y;
            this.inVehicleZ = pos.z;
            ((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(pos);
            Vector3f v = vehicle1.vehicleTransform.getEulerAnglesZYX((Vector3f)((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc());
            this.vehicleAngleZ = (float)Math.toDegrees(v.z);
            this.vehicleAngleY = (float)Math.toDegrees(v.y);
            this.vehicleAngleX = (float)Math.toDegrees(v.x);
            ((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(v);
         }

         FBORenderTracerEffects.getInstance().releaseWeaponTransform(this.character);
      }

      this.renderToTexture = BaseVehicle.renderToTexture;
      GameProfiler profiler = GameProfiler.getInstance();
      ProfileArea var18 = profiler.profile("Init Inst");

      try {
         this.initRenderData();
      } catch (Throwable var14) {
         if (var18 != null) {
            try {
               var18.close();
            } catch (Throwable var12) {
               var14.addSuppressed(var12);
            }
         }

         throw var14;
      }

      if (var18 != null) {
         var18.close();
      }

      var18 = profiler.profile("Set Lights");

      try {
         modelSlot.model.model.setLightsInst(this, 5);
      } catch (Throwable var13) {
         if (var18 != null) {
            try {
               var18.close();
            } catch (Throwable var11) {
               var13.addSuppressed(var11);
            }
         }

         throw var13;
      }

      if (var18 != null) {
         var18.close();
      }

      for (int i = 0; i < this.modelData.size(); i++) {
         ModelInstanceRenderData instData = (ModelInstanceRenderData)this.modelData.get(i);
         if (this.character != null && instData.modelInstance == this.character.primaryHandModel) {
            FBORenderTracerEffects.getInstance().storeWeaponTransform(this.character, instData.xfrm);
         }

         this.UpdateCharacter(instData.properties, instData.ignoreLighting);
      }

      for (int i = 0; i < this.readyModelData.size(); i++) {
         ModelInstanceRenderData data = (ModelInstanceRenderData)this.readyModelData.get(i);
         data.init();
         data.transformToParent(data.parent);
      }

      if (Core.debug) {
         this.debugRenderData.init(this);
      }

      this.rendered = false;
      return this;
   }

   private void adjustForSittingOnFurniture() {
      if (this.character.isSittingOnFurniture()) {
         IsoObject object = this.character.getSitOnFurnitureObject();
         if (object != null && object.getSprite() != null && object.getSprite().tilesetName != null) {
            IsoDirections sitDir = this.character.getSitOnFurnitureDirection();
            Vector3f xln = SeatingManager.getInstance().getTranslation(object.getSprite(), sitDir.name(), new Vector3f());
            float szf = xln.z;
            float lerp = 1.0F;
            String sitOnFurnitureDirection = this.character.getVariableString("SitOnFurnitureDirection");
            String animNodeName = "SitOnFurniture" + sitOnFurnitureDirection;
            if (this.character.isCurrentState(PlayerGetUpState.instance())) {
               String suffix = sitOnFurnitureDirection;
               if (this.character.getVariableBoolean("getUpQuick")) {
                  suffix = suffix + "Quick";
               }

               animNodeName = "fromSitOnFurniture" + suffix;
               lerp = 0.0F;
            }

            float animFraction = SeatingManager.getInstance().getAnimationTrackFraction(this.character, animNodeName);
            if (animFraction < 0.0F && !this.character.getVariableBoolean("SitOnFurnitureStarted")) {
               lerp = 1.0F - lerp;
            }

            if (animFraction >= 0.0F) {
               if (this.character.isCurrentState(PlayerGetUpState.instance())) {
                  float lerpStart = 0.48F;
                  float lerpEnd = 0.63F;
                  if (animFraction >= 0.63F) {
                     lerp = 1.0F;
                  } else if (animFraction >= 0.48F) {
                     lerp = (animFraction - 0.48F) / 0.15F;
                  } else {
                     lerp = 0.0F;
                  }

                  lerp = 1.0F - lerp;
               } else {
                  float lerpStart = 0.27F;
                  float lerpEnd = 0.43F;
                  if (animFraction >= 0.27F && animFraction <= 0.43F) {
                     lerp = (animFraction - 0.27F) / 0.16F;
                  } else if (animFraction >= 0.43F) {
                     lerp = 1.0F;
                  } else {
                     lerp = 0.0F;
                  }
               }
            }

            this.z = PZMath.lerp(this.z, object.square.z + szf / 2.44949F, lerp);
            Results results = IsoDepthHelper.getSquareDepthData(
               PZMath.fastfloor(this.camCharacterX),
               PZMath.fastfloor(this.camCharacterY),
               this.x + this.fixJigglyModelsSquareX,
               this.y + this.fixJigglyModelsSquareY,
               this.z
            );
            this.squareDepth = results.depthStart;
         }
      }
   }

   public void UpdateCharacter(ShaderPropertyBlock properties, boolean bIgnoreLighting) {
      if (bIgnoreLighting) {
         properties.SetVector3("AmbientColour", 1.0F, 1.0F, 1.0F);
      } else {
         properties.SetVector3("AmbientColour", this.ambientR * 0.45F, this.ambientG * 0.45F, this.ambientB * 0.45F);
      }

      properties.SetFloat("Alpha", this.alpha);
      if (properties.GetParameter("LightDirection") == null) {
         properties.SetVector3Array("LightDirection", new org.lwjgl.util.vector.Vector3f[5]);
      }

      if (properties.GetParameter("LightColour") == null) {
         properties.SetVector3Array("LightColour", new org.lwjgl.util.vector.Vector3f[5]);
      }

      properties.CopyParameters(this.properties);
   }

   private ModelInstanceRenderData getParentData(ModelInstance inst) {
      for (int i = 0; i < this.readyModelData.size(); i++) {
         ModelInstanceRenderData data = (ModelInstanceRenderData)this.readyModelData.get(i);
         if (data.modelInstance == inst.parent) {
            return data;
         }
      }

      return null;
   }

   private void initRenderData() {
      for (int i = 0; i < this.modelData.size(); i++) {
         ModelInstanceRenderData instData = (ModelInstanceRenderData)this.modelData.get(i);
         instData.init();
         instData.transformToParent(instData.parent);
      }
   }

   public synchronized void render() {
      if (this.character == null) {
         this.renderVehicle();
      } else {
         this.renderCharacter();
      }
   }

   public void renderDebug() {
      if (this.debugRenderData != null) {
         this.debugRenderData.render();
      }
   }

   private static VertexBufferObject GetCardQuad() {
      if (cardQuad == null) {
         VertexBufferObject.VertexFormat format = new VertexBufferObject.VertexFormat(2);
         format.setElement(0, VertexBufferObject.VertexType.VertexArray, 12);
         format.setElement(1, VertexBufferObject.VertexType.TextureCoordArray, 8);
         format.calculate();
         VertexBufferObject.VertexArray array = new VertexBufferObject.VertexArray(format, 4);
         array.setElement(0, 0, -0.5F, 1.0F, 0.0F);
         array.setElement(0, 1, 0.0F, 1.0F);
         array.setElement(1, 0, 0.5F, 1.0F, 0.0F);
         array.setElement(1, 1, 1.0F, 1.0F);
         array.setElement(2, 0, -0.5F, 0.0F, 0.0F);
         array.setElement(2, 1, 0.0F, 0.0F);
         array.setElement(3, 0, 0.5F, 0.0F, 0.0F);
         array.setElement(3, 1, 1.0F, 0.0F);
         cardQuad = new VertexBufferObject(array, new int[]{0, 1, 2, 1, 3, 2});
      }

      return cardQuad;
   }

   private void bindAndClearBlend() {
      Imposter.CreateBlend();
      Imposter.blendTexture.BindDraw();
      GL43.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
      GL43.glClearDepthf(1.0F);
      GL43.glClearStencil(0);
      GL11.glClear(17664);
   }

   private void setupImposterShader(Shader shader, RenderTexture rt) {
      ShaderProgram program = shader.getShaderProgram();
      ShaderProgram.Uniform tex = program.getUniform("tex", 35678);
      ShaderProgram.Uniform depth = program.getUniform("depth", 35678);
      shader.Start();
      if (tex != null) {
         GL43.glActiveTexture(33984 + tex.sampler);
         rt.BindTexture();
         GL43.glUniform1i(tex.loc, tex.sampler);
      }

      if (depth != null) {
         GL43.glActiveTexture(33984 + depth.sampler);
         rt.BindDepth();
         GL43.glUniform1i(depth.loc, depth.sampler);
      }
   }

   private void drawCardToBlend(Imposter imposter) {
      GL43.glStencilFunc(514, 0, 255);
      GL11.glStencilOp(7680, 7680, 7681);
      Imposter.blendTexture.BindDraw();
      Shader shader = ShaderManager.instance.getOrCreateShader("imposterBlend", true, false);
      this.setupImposterShader(shader, imposter.card);
      RenderTarget.DrawFullScreenTri();
      shader.End();
   }

   private void drawBlendToCard(Imposter imposter) {
      GL43.glStencilFunc(519, 0, 0);
      imposter.card.BindDraw();
      GL43.glClear(16640);
      Shader shader = ShaderManager.instance.getOrCreateShader("imposterUnstencil", true, false);
      ShaderProgram program = shader.getShaderProgram();
      ShaderProgram.Uniform stencil = program.getUniform("stencil", 36306);
      this.setupImposterShader(shader, Imposter.blendTexture);
      if (stencil != null) {
         GL43.glActiveTexture(33984 + stencil.sampler);
         Imposter.blendTexture.BindStencil();
         GL43.glUniform1i(stencil.loc, stencil.sampler);
      }

      RenderTarget.DrawFullScreenTri();
      shader.End();
   }

   private void blendImposter(Imposter imposter) {
      this.bindAndClearBlend();
      GL43.glEnable(2960);
      GL43.glStencilFunc(519, 1, 255);
      GL43.glStencilMask(255);
      GL11.glStencilOp(7681, 7681, 7681);
      this.renderCharacter();
      this.drawBlendToCard(imposter);
      GL43.glActiveTexture(33984);
      GL43.glCullFace(1029);
      GL43.glDisable(2884);
   }

   public void renderToImposterCard(Imposter imposter) {
      imposter.sinceLastUpdate = 0;
      this.renderingToCard = true;
      int[] stencilFunc = new int[3];
      GL11.glGetIntegerv(2962, stencilFunc);
      int currentDraw = GL11.glGetInteger(36006);
      GL11.glGetIntegerv(2978, vp);
      if (Core.debug && DebugOptions.instance.zombieImposterBlend.getValue()) {
         this.blendImposter(imposter);
      } else {
         imposter.card.BindDraw();
         GL43.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         GL43.glClear(16640);
         this.renderCharacter();
      }

      GL43.glBindFramebuffer(36009, currentDraw);
      GL43.glViewport(vp[0], vp[1], vp[2], vp[3]);
      imposter.cardRendered = true;
      this.renderingToCard = false;
   }

   public void renderCard(Imposter imposter) {
      GL11.glDisable(2884);
      GL11.glEnable(2929);
      GL11.glDepthFunc(515);
      Shader shader = ShaderManager.instance.getOrCreateShader("imposter", false, false);
      shader.Start();
      ShaderProgram program = shader.getShaderProgram();
      ShaderProgram.Uniform matrix = program.getUniform("transform", 35676);
      Vector3f scale = new Vector3f();
      Matrix4f modelView = Core.getInstance().modelViewMatrixStack.peek();
      Matrix4f billboard = Core.getInstance().modelViewMatrixStack.alloc();
      modelView.get(billboard);
      billboard.getScale(scale);
      scale.set(1.0F / scale.x, 1.0F / scale.y, 1.0F / scale.z);
      billboard.scale(scale);
      billboard.setTranslation(0.0F, 0.0F, 0.0F);
      shader.setMatrix(matrix.loc, billboard);
      ShaderProgram.Uniform texture = program.getUniform("tex", 35678);
      ShaderProgram.Uniform depth = program.getUniform("depth", 35678);
      if (texture != null) {
         GL43.glActiveTexture(33984 + texture.sampler);
         imposter.card.BindTexture();
         GL43.glUniform1i(texture.loc, texture.sampler);
      }

      if (depth != null) {
         GL43.glActiveTexture(33984 + depth.sampler);
         imposter.card.BindDepth();
         GL43.glUniform1i(depth.loc, depth.sampler);
      }

      ShaderProgram.Uniform frameFade = program.getUniform("frameFade", 5126);
      if (frameFade != null) {
         if (Core.debug && !DebugOptions.instance.zombieImposterBlend.getValue()) {
            GL43.glUniform1f(frameFade.loc, 1.0F);
         } else {
            float fade = 1.0F - imposter.sinceLastUpdate / 10.0F;
            GL43.glUniform1f(frameFade.loc, fade);
         }
      }

      VertexBufferObject quad = GetCardQuad();
      quad.Draw(shader);
      shader.End();
      GL43.glActiveTexture(33984);
      imposter.sinceLastUpdate++;
      if (Core.debug) {
         RenderTexture rt;
         if (DebugOptions.instance.zombieImposterPreview.getValue()) {
            rt = imposter.card;
         } else if (DebugOptions.instance.zombieBlendPreview.getValue()) {
            rt = Imposter.blendTexture;
         } else {
            rt = null;
         }

         if (rt != null) {
            int current = GL11.glGetInteger(36010);
            int w = rt.GetWidth();
            int h = rt.GetHeight();
            int hw = w / 2;
            int hh = h / 2;
            rt.BindRead();
            GL43.glBlitFramebuffer(0, 0, w, h, 960 - hw, 540 - hh, 960 + hw, 540 + hh, 16384, 9728);
            GL43.glBindFramebuffer(36008, current);
         }
      }

      if (Core.debug && DebugOptions.instance.renderTestFsQuad.getValue()) {
         Shader shader2 = ShaderManager.instance.getOrCreateShader("imposterTest", true, false);
         shader2.Start();
         RenderTarget.DrawFullScreenTri();
         shader2.End();
      }
   }

   public boolean checkReady() {
      this.ready = true;
      if (this.textureCreator != null && !this.textureCreator.isRendered()) {
         if (this.pzoptSkipTexture || this.pzoptSightEligible && pzopt.FirstSight.overBudget()) { // pzopt: zombieFirstSightSprite, the composite waits (the zombie is drawn as its sprite)
            this.ready = false; // pzopt
         } else { // pzopt
         long pzoptT0 = pzopt.FirstSight.TIMING ? System.nanoTime() : 0L; // pzopt: the composite's render-thread cost (pzopt.FirstSight)
         pzopt.FirstSight.composing(); // pzopt
         this.textureCreator.render();
         if (pzopt.FirstSight.TIMING) { // pzopt
            pzopt.FirstSight.composed(System.nanoTime() - pzoptT0, this.textureCreator.isRendered()); // pzopt
         } // pzopt
         if (!this.textureCreator.isRendered()) {
            this.ready = false;
         }
         } // pzopt
      }

      for (int i = 0; i < this.modelData.size(); i++) {
         ModelInstanceRenderData mird = (ModelInstanceRenderData)this.modelData.get(i);
         ModelInstanceTextureInitializer miti = mird.modelInstance.getTextureInitializer();
         if (miti != null && !miti.isRendered()) {
            miti.render();
            if (!miti.isRendered()) {
               this.ready = false;
            }
         }
      }

      return this.ready;
   }

   public boolean canRender() {
      return this.ready || !this.readyModelData.isEmpty();
   }

   public ModelInstanceRenderDataList getModelData() {
      return this.ready ? this.modelData : this.readyModelData;
   }

   private void renderCharacter() {
      if (Core.debug && DebugOptions.instance.zombieImposterRendering.getValue() && !this.renderingToCard && this.character instanceof IsoZombie zombie) {
         if (zombie.imposter.card == null) {
            zombie.imposter.create();
            this.renderToImposterCard(zombie.imposter);
         } else if (zombie.imposter.cardRendered && zombie.imposter.sinceLastUpdate >= 10) {
            this.renderToImposterCard(zombie.imposter);
         }
      }

      this.checkReady();
      if (this.ready || !this.readyModelData.isEmpty()) {
         GameProfiler profiler = GameProfiler.getInstance();
         if (this.characterOutline) {
            ModelCamera.instance.depthMask = false;
            ProfileArea var10 = profiler.profile("performRenderCharacterOutline");

            try {
               this.performRenderCharacterOutline();
            } catch (Throwable var8) {
               if (var10 != null) {
                  try {
                     var10.close();
                  } catch (Throwable var6) {
                     var8.addSuppressed(var6);
                  }
               }

               throw var8;
            }

            if (var10 != null) {
               var10.close();
            }
         }

         ModelCamera.instance.depthMask = true;
         ProfileArea var11 = profiler.profile("renderCharacter");

         try {
            this.performRenderCharacter();
         } catch (Throwable var7) {
            if (var11 != null) {
               try {
                  var11.close();
               } catch (Throwable var5) {
                  var7.addSuppressed(var5);
               }
            }

            throw var7;
         }

         if (var11 != null) {
            var11.close();
         }

         int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
         IsoPlayer player = (IsoPlayer)Type.tryCastTo(this.character, IsoPlayer.class);
         if (player != null && !this.characterOutline && player == IsoPlayer.players[playerIndex]) {
            ModelOutlines.instance.setPlayerRenderData(this);
         }

         this.rendered = this.ready;
      }
   }

   private void renderVehicleDebug() {
      if (Core.debug) {
         ModelCamera.instance.Begin();
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.peek();
         modelView.translate(0.0F, this.centerOfMassY, 0.0F);
         if (this.debugRenderData != null && !this.modelData.isEmpty()) {
            PZGLUtil.pushAndMultMatrix(5888, ((ModelInstanceRenderData)this.modelData.get(0)).xfrm);
            this.debugRenderData.render();
            PZGLUtil.popMatrix(5888);
         }

         if (DebugOptions.instance.model.render.attachments.getValue() && !this.modelData.isEmpty()) {
            BaseVehicle vehicle = (BaseVehicle)this.object;
            ModelInstanceRenderData instData = (ModelInstanceRenderData)this.modelData.get(0);
            PZGLUtil.pushAndMultMatrix(5888, this.vehicleTransform);
            float scale1 = vehicle.getScript().getModelScale();
            Matrix4f modelMatrix = (Matrix4f)((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).alloc();
            modelMatrix.scaling(1.0F / scale1);
            Matrix4f attachmentMatrix = (Matrix4f)((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).alloc();

            for (int i = 0; i < vehicle.getScript().getAttachmentCount(); i++) {
               ModelAttachment attachment = vehicle.getScript().getAttachment(i);
               instData.modelInstance.getAttachmentMatrix(attachment, attachmentMatrix);
               modelMatrix.mul(attachmentMatrix, attachmentMatrix);
               PZGLUtil.pushAndMultMatrix(5888, attachmentMatrix);
               Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 0.1F, 2.0F);
               PZGLUtil.popMatrix(5888);
            }

            ((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).release(attachmentMatrix);
            ((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).release(modelMatrix);
            PZGLUtil.popMatrix(5888);
         }

         if (Core.debug && DebugOptions.instance.model.render.axis.getValue() && !this.modelData.isEmpty()) {
            BaseVehicle vehicle = (BaseVehicle)this.object;
            float scale1 = vehicle.getScript().getModelScale();
            Matrix4f m = BaseVehicle.allocMatrix4f();
            m.set(this.vehicleTransform);
            m.scale(1.0F / scale1);
            PZGLUtil.pushAndMultMatrix(5888, m);
            Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 1.0F, 4.0F);
            PZGLUtil.popMatrix(5888);

            for (int i = 1; i < this.modelData.size(); i++) {
               m.set(((ModelInstanceRenderData)this.modelData.get(i)).xfrm);
               m.scale(1.0F / scale1);
               PZGLUtil.pushAndMultMatrix(5888, m);
               Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 1.0F, 4.0F);
               PZGLUtil.popMatrix(5888);
            }

            BaseVehicle.releaseMatrix4f(m);
         }

         ModelCamera.instance.End();
      }
   }

   private void performRenderCharacter() {
      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      GL11.glEnable(3042);
      GL11.glBlendFunc(770, 771);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GL11.glEnable(2929);
      GL11.glDisable(3089);
      ModelInstanceRenderDataList modelData = this.modelData;
      if (this.character != null && !this.ready) {
         modelData = this.readyModelData;
      }

      if (Core.debug && DebugOptions.instance.zombieImposterRendering.getValue()) {
         if (this.character instanceof IsoZombie zombie) {
            if (this.IsRenderingToCard()) {
               ModelCamera.instance.BeginImposter(zombie.imposter.card);
            } else {
               float angle = ModelCamera.instance.useAngle;
               ModelCamera.instance.useAngle = 0.0F;
               ModelCamera.instance.Begin();
               ModelCamera.instance.useAngle = angle;
            }
         } else {
            Model.CharacterModelCameraBegin(this);
         }
      } else {
         Model.CharacterModelCameraBegin(this);
      }

      float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
      this.modelSlot.model.targetDepth = this.squareDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F - 1.0E-4F;
      boolean usingCard = false;
      if (Core.debug
         && DebugOptions.instance.zombieImposterRendering.getValue()
         && !this.renderingToCard
         && this.character instanceof IsoZombie zombie
         && zombie.imposter.cardRendered) {
         this.renderCard(zombie.imposter);
         usingCard = true;
      }

      if (!usingCard) {
         for (int i = 0; i < modelData.size(); i++) {
            ModelInstanceRenderData data = (ModelInstanceRenderData)modelData.get(i);
            if (data.modelInstance != null) {
               data.modelInstance.targetDepth = this.modelSlot.model.targetDepth;
            }

            data.RenderCharacter(this);
         }
      }

      if (Core.debug) {
         this.renderDebug();

         for (int i = 0; i < modelData.size(); i++) {
            ModelInstanceRenderData instData = (ModelInstanceRenderData)modelData.get(i);
            instData.renderDebug();
         }
      }

      if (this.character instanceof IsoZombie) {
         if (this.IsRenderingToCard()) {
            ModelCamera.instance.EndImposter();
         } else {
            Model.CharacterModelCameraEnd();
         }
      } else {
         Model.CharacterModelCameraEnd();
      }

      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      GL11.glEnable(3553);
      SpriteRenderer.ringBuffer.restoreVbos = true;
      GL11.glDisable(2929);
      GL11.glEnable(3042);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GLStateRenderThread.restore();
   }

   private void performRenderCharacterOutline() {
      this.performRenderCharacterOutline(false, this.outlineColor, this.outlineBehindPlayer);
   }

   public void performRenderCharacterOutline(boolean bPlayerToMask, ColorInfo outlineColor, boolean bOutlineBehindPlayer) {
      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      GL11.glEnable(3042);
      GL11.glBlendFunc(770, 771);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GL11.glEnable(2929);
      GL11.glDisable(3089);
      ModelInstanceRenderDataList modelData = this.modelData;
      if (this.character != null && !this.ready) {
         modelData = this.readyModelData;
      }

      if (solidColor == null) {
         solidColor = new Shader("aim_outline_solid", false, false);
         solidColorStatic = new Shader("aim_outline_solid", true, false);
      }

      solidColor.Start();
      solidColor.getShaderProgram().setVector4("u_color", outlineColor.r, outlineColor.g, outlineColor.b, 1.0F);
      solidColor.End();
      solidColorStatic.Start();
      solidColorStatic.getShaderProgram().setVector4("u_color", outlineColor.r, outlineColor.g, outlineColor.b, 1.0F);
      solidColorStatic.End();
      boolean clear = ModelOutlines.instance.beginRenderOutline(outlineColor, bOutlineBehindPlayer, bPlayerToMask);
      ModelOutlines.instance.fboA.startDrawing(clear, true);
      Model.CharacterModelCameraBegin(this);

      for (int i = 0; i < modelData.size(); i++) {
         ModelInstanceRenderData data = (ModelInstanceRenderData)modelData.get(i);
         Shader effect = data.model.effect;

         try {
            data.model.effect = data.model.isStatic ? solidColorStatic : solidColor;
            data.RenderCharacter(this);
         } finally {
            data.model.effect = effect;
         }
      }

      Model.CharacterModelCameraEnd();
      ModelOutlines.instance.fboA.endDrawing();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      GL11.glEnable(3553);
      SpriteRenderer.ringBuffer.restoreVbos = true;
      GL11.glDisable(2929);
      GL11.glEnable(3042);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GLStateRenderThread.restore();
   }

   private void renderVehicle() {
      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      GL11.glEnable(3042);
      GL11.glBlendFunc(770, 771);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      if (this.renderToTexture) {
         GL11.glClear(256);
      }

      GL11.glEnable(2929);
      GL11.glDisable(3089);
      if (this.renderToTexture) {
         ModelManager.instance.bitmap.startDrawing(true, true);
         GL11.glViewport(0, 0, ModelManager.instance.bitmap.getWidth(), ModelManager.instance.bitmap.getHeight());
      }

      for (int i = 0; i < this.modelData.size(); i++) {
         ModelInstanceRenderData data = (ModelInstanceRenderData)this.modelData.get(i);
         data.RenderVehicle(this);
      }

      this.renderVehicleDebug();
      if (this.renderToTexture) {
         ModelManager.instance.bitmap.endDrawing();
      }

      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      GL11.glEnable(3553);
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      SpriteRenderer.ringBuffer.restoreVbos = true;
      GL11.glDisable(2929);
      GL11.glEnable(3042);
      GLStateRenderThread.restore();
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
   }

   private void doneWithTextureCreator(ModelInstanceTextureCreator tc) {
      if (tc != null) {
         if (tc.testNotReady > 0) {
            tc.testNotReady--;
         }

         if (tc.renderRefCount <= 0) {
            if (tc.isRendered()) {
               tc.postRender();
               if (tc == this.character.getTextureCreator()) {
                  this.character.setTextureCreator(null);
               }
            } else if (tc != this.character.getTextureCreator()) {
               tc.postRender();
            }
         }
      }
   }

   public void postRender() {
      assert this.modelSlot.renderRefCount > 0;
      this.modelSlot.renderRefCount--;
      if (this.textureCreator != null) {
         this.textureCreator.renderRefCount--;
         this.doneWithTextureCreator(this.textureCreator);
         this.textureCreator = null;
      }

      ModelInstanceRenderData.release(this.readyModelData);
      this.readyModelData.clear();
      if (this.rendered && this.character != null) {
         ModelManager.instance.derefModelInstances(this.character.getReadyModelData());
         this.character.getReadyModelData().clear();

         for (int i = 0; i < this.modelData.size(); i++) {
            ModelInstanceRenderData instData = (ModelInstanceRenderData)this.modelData.get(i);
            ModelInstance modelInstance = instData.modelInstance;
            if (EffectsManager.getInstance().postRender(this.character, modelInstance, instData, this.modelSlot)) {
               this.modelData.remove(i--);
            } else {
               modelInstance.renderRefCount++;
               this.character.getReadyModelData().add(modelInstance);
            }
         }
      }

      this.character = null;
      this.object = null;
      this.animPlayer = null;
      this.debugRenderData = (ModelSlotDebugRenderData)Pool.tryRelease(this.debugRenderData);
      ModelInstanceRenderData.release(this.modelData);
      pool.release(this);
   }

   public static ModelSlotRenderData alloc() {
      return (ModelSlotRenderData)pool.alloc();
   }
}
