package zombie.core.skinnedmodel.advancedanimation;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjglx.BufferUtils;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.GameProfiler.ProfileArea;
import zombie.characters.IsoGameCharacter;
import zombie.characters.SurvivorDesc;
import zombie.characters.AttachedItems.AttachedModelName;
import zombie.characters.AttachedItems.AttachedModelNames;
import zombie.characters.WornItems.BodyLocationGroup;
import zombie.characters.WornItems.BodyLocations;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.action.IActionStateChanged;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.DefaultShader;
import zombie.core.ImmutableColor;
import zombie.core.PerformanceSettings;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IModelCamera;
import zombie.core.opengl.VBORenderer;
import zombie.core.rendering.RenderList;
import zombie.core.rendering.ShaderParameter;
import zombie.core.rendering.ShaderPropertyBlock;
import zombie.core.skinnedmodel.IGrappleable;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.events.IAnimEventCallback;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.core.skinnedmodel.model.CharacterMask;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstanceTextureCreator;
import zombie.core.skinnedmodel.model.ModelInstanceTextureInitializer;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.model.VehicleModelInstance;
import zombie.core.skinnedmodel.model.VehicleSubModelInstance;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.model.CharacterMask.Part;
import zombie.core.skinnedmodel.population.BeardStyle;
import zombie.core.skinnedmodel.population.BeardStyles;
import zombie.core.skinnedmodel.population.ClothingItem;
import zombie.core.skinnedmodel.population.HairStyle;
import zombie.core.skinnedmodel.population.HairStyles;
import zombie.core.skinnedmodel.population.PopTemplateManager;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.visual.AnimalVisual;
import zombie.core.skinnedmodel.visual.BaseVisual;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.IAnimalVisual;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.Vector2;
import zombie.iso.IsoGridSquare.ResultLight;
import zombie.iso.objects.ShadowParams;
import zombie.popman.ObjectPool;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ModelAttachment;
import zombie.scripting.objects.ModelScript;
import zombie.scripting.objects.ResourceLocation;
import zombie.ui.UIManager;
import zombie.util.Lambda;
import zombie.util.Pool;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.Matrix4fObjectPool;

public final class AnimatedModel extends AnimationVariableSource implements IAnimatable, IAnimEventCallback, IActionStateChanged, IAnimalVisual, IHumanVisual {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.advancedanimation.AnimatedModel");
   }

   private String animSetName = "player-avatar";
   private String outfitName;
   private IsoGameCharacter character;
   private IGrappleable grappleable;
   private BaseVisual baseVisual;
   private final HumanVisual humanVisual = new HumanVisual(this);
   private final ItemVisuals itemVisuals = new ItemVisuals();
   private String primaryHandModelName;
   private String secondaryHandModelName;
   private final AttachedModelNames attachedModelNames = new AttachedModelNames();
   private ModelInstance modelInstance;
   private boolean female;
   private boolean zombie;
   private boolean skeleton;
   private String animalType;
   private float finalScale = 1.0F;
   private String state;
   private final Vector2 angle = new Vector2();
   private final Vector3f offset = new Vector3f(0.0F, -0.45F, 0.0F);
   private boolean isometric = true;
   private boolean flipY;
   private float alpha = 1.0F;
   private AnimationPlayer animPlayer;
   private final ActionContext actionContext = new ActionContext(this);
   private final AdvancedAnimator advancedAnimator = new AdvancedAnimator();
   private float trackTime;
   private final String uid;
   private float lightsOriginX;
   private float lightsOriginY;
   private float lightsOriginZ;
   private final ResultLight[] lights = new ResultLight[5];
   private final ColorInfo ambient = new ColorInfo();
   private float highResDepthMultiplier;
   private boolean outside = true;
   private boolean room;
   private boolean updateTextures;
   private boolean clothingChanged;
   private boolean animate = true;
   private boolean showBip01;
   private ModelInstanceTextureCreator textureCreator;
   private int cullFace = 1028;
   private final AnimatedModel.StateInfo[] stateInfos = new AnimatedModel.StateInfo[3];
   private boolean ready;
   private static final ObjectPool<AnimatedModel.AnimatedModelInstanceRenderData> instDataPool = new ObjectPool(
      AnimatedModel.AnimatedModelInstanceRenderData::new, "AnimatedModel.instDataPool"
   );
   private final AnimatedModel.UIModelCamera uiModelCamera = new AnimatedModel.UIModelCamera();
   private static final AnimatedModel.WorldModelCamera worldModelCamera = new AnimatedModel.WorldModelCamera();

   public IsoGameCharacter getCharacter() {
      return this.character;
   }

   public AnimatedModel() {
      this.uid = String.format("%s-%s", this.getClass().getSimpleName(), UUID.randomUUID());
      this.advancedAnimator.init(this, null);
      this.actionContext.addOnStateChanged(this);

      for (int i = 0; i < this.lights.length; i++) {
         this.lights[i] = new ResultLight();
      }

      for (int i = 0; i < this.stateInfos.length; i++) {
         this.stateInfos[i] = new AnimatedModel.StateInfo();
      }
   }

   public void setVisual(BaseVisual baseVisual) {
      this.baseVisual = baseVisual;
   }

   public BaseVisual getVisual() {
      return this.baseVisual;
   }

   public AnimalVisual getAnimalVisual() {
      return (AnimalVisual)Type.tryCastTo(this.baseVisual, AnimalVisual.class);
   }

   public String getAnimalType() {
      return this.animalType;
   }

   public float getAnimalSize() {
      return this.finalScale;
   }

   public HumanVisual getHumanVisual() {
      return (HumanVisual)Type.tryCastTo(this.baseVisual, HumanVisual.class);
   }

   public void getItemVisuals(ItemVisuals itemVisuals) {
      itemVisuals.clear();
   }

   public boolean isFemale() {
      return this.female;
   }

   public boolean isZombie() {
      return this.zombie;
   }

   public boolean isSkeleton() {
      return this.skeleton;
   }

   public void setAnimSetName(String name) {
      if (StringUtils.isNullOrWhitespace(name)) {
         throw new IllegalArgumentException("invalid AnimSet \"" + name + "\"");
      }

      this.animSetName = name;
   }

   public void setOutfitName(String name, boolean female, boolean zombie) {
      this.outfitName = name;
      this.female = female;
      this.zombie = zombie;
   }

   public void setCharacter(IsoGameCharacter character) {
      this.outfitName = null;
      if (this.baseVisual != null) {
         this.baseVisual.clear();
      }

      this.itemVisuals.clear();
      if (character instanceof IHumanVisual iHumanVisual) {
         character.getItemVisuals(this.itemVisuals);
         this.character = character;
         this.setGrappleable(character);
         if (character.getAttachedItems() != null) {
            this.attachedModelNames.initFrom(character.getAttachedItems());
         }

         if (character instanceof IsoAnimal isoAnimal) {
            this.setModelData(isoAnimal.getAnimalVisual(), this.itemVisuals, isoAnimal);
         } else {
            this.setModelData(iHumanVisual.getHumanVisual(), this.itemVisuals);
         }
      }
   }

   public void setGrappleable(IGrappleable grappleable) {
      this.grappleable = grappleable;
   }

   public void setSurvivorDesc(SurvivorDesc survivorDesc) {
      this.outfitName = null;
      if (this.baseVisual != null) {
         this.baseVisual.clear();
      }

      this.itemVisuals.clear();
      survivorDesc.getWornItems().getItemVisuals(this.itemVisuals);
      this.attachedModelNames.clear();
      this.setModelData(survivorDesc.getHumanVisual(), this.itemVisuals);
   }

   public void setPrimaryHandModelName(String name) {
      this.primaryHandModelName = name;
   }

   public void setSecondaryHandModelName(String name) {
      this.secondaryHandModelName = name;
   }

   public void setAttachedModelNames(AttachedModelNames attachedModelNames) {
      this.attachedModelNames.copyFrom(attachedModelNames);
   }

   public void setModelData(BaseVisual baseVisual, ItemVisuals itemVisuals) {
      this.setModelData(baseVisual, itemVisuals, null);
   }

   public void setModelData(BaseVisual baseVisual, ItemVisuals itemVisuals, IsoAnimal animal) {
      AnimationPlayer oldAnimPlayer = this.animPlayer;
      Model oldModel = this.animPlayer == null ? null : oldAnimPlayer.getModel();
      if (this.baseVisual != baseVisual) {
         if (this.baseVisual == null || this.baseVisual.getClass() != baseVisual.getClass()) {
            if (baseVisual instanceof AnimalVisual) {
               this.baseVisual = new AnimalVisual(this);
            }

            if (baseVisual instanceof HumanVisual) {
               this.baseVisual = new HumanVisual(this);
            }
         }

         this.baseVisual.copyFrom(baseVisual);
      }

      if (this.itemVisuals != itemVisuals) {
         this.itemVisuals.clear();
         PZArrayUtil.addAll(this.itemVisuals, itemVisuals);
      }

      if (this.baseVisual != baseVisual) {
         this.female = false;
         this.zombie = false;
         this.skeleton = false;
         this.animalType = null;
         this.finalScale = 1.0F;
         if (baseVisual instanceof AnimalVisual animalVisual) {
            this.animalType = animalVisual.getAnimalType();
            this.finalScale = animalVisual.getAnimalSize();
            this.skeleton = animalVisual.isSkeleton();
         }

         if (baseVisual instanceof HumanVisual humanVisual) {
            this.female = humanVisual.isFemale();
            this.zombie = humanVisual.isZombie();
            this.skeleton = humanVisual.isSkeleton();
         }
      }

      if (this.modelInstance != null) {
         ModelManager.instance.resetModelInstanceRecurse(this.modelInstance, this);
      }

      Model model = baseVisual.getModel();
      if (baseVisual instanceof AnimalVisual) {
         this.character = animal;
      }

      this.getAnimationPlayer().setModel(model);
      this.modelInstance = ModelManager.instance.newInstance(model, null, this.getAnimationPlayer());
      this.modelInstance.modelScript = baseVisual.getModelScript();
      this.modelInstance.setOwner(this);
      this.populateCharacterModelSlot();
      this.DoCharacterModelEquipped();
      boolean reset = false;
      if (this.animate) {
         AnimationSet animSet = AnimationSet.GetAnimationSet(this.GetAnimSetName(), false);
         if (animSet != this.advancedAnimator.animSet || oldAnimPlayer != this.getAnimationPlayer() || oldModel != model) {
            reset = true;
         }
      } else {
         reset = true;
      }

      if (reset) {
         this.advancedAnimator.OnAnimDataChanged(false);
      }

      if (this.animate) {
         ActionGroup actionGroup = ActionGroup.getActionGroup(this.GetAnimSetName());
         if (actionGroup != this.actionContext.getGroup()) {
            this.actionContext.setGroup(actionGroup);
         }

         String stateName = StringUtils.isNullOrWhitespace(this.state) ? this.actionContext.getCurrentStateName() : this.state;
         this.advancedAnimator.setState(stateName, PZArrayUtil.listConvert(this.actionContext.getChildStates(), state -> state.getName()));
      } else if (!StringUtils.isNullOrWhitespace(this.state)) {
         this.advancedAnimator.setState(this.state);
      }

      if (reset) {
         float fpsMultiplier = GameTime.getInstance().fpsMultiplier;
         GameTime.getInstance().fpsMultiplier = 100.0F;

         try {
            this.advancedAnimator.update(this.getAnimationTimeDelta());
         } finally {
            GameTime.getInstance().fpsMultiplier = fpsMultiplier;
         }
      }

      if (Core.debug && !this.animate && this.stateInfoMain().readyData.isEmpty()) {
         this.getAnimationPlayer().resetBoneModelTransforms();
      }

      this.trackTime = 0.0F;
      this.stateInfoMain().modelsReady = this.isReadyToRender();
   }

   private float getAnimationTimeDelta() {
      return this.character != null ? this.character.getAnimationTimeDelta() : GameTime.instance.getTimeDelta();
   }

   public void setAmbient(ColorInfo ambient, boolean outside, boolean room) {
      this.ambient.set(ambient.r, ambient.g, ambient.b, 1.0F);
      this.outside = outside;
      this.room = room;
   }

   public void setLights(ResultLight[] lights, float x, float y, float z) {
      this.lightsOriginX = x;
      this.lightsOriginY = y;
      this.lightsOriginZ = z;

      for (int i = 0; i < lights.length; i++) {
         this.lights[i].copyFrom(lights[i]);
      }
   }

   public void setState(String state) {
      this.state = state;
   }

   public String getState() {
      return this.state;
   }

   public void setAngle(Vector2 angle) {
      this.angle.set(angle);
   }

   public void setOffset(float x, float y, float z) {
      this.offset.set(x, y, z);
   }

   public void setOffsetWhileRendering(float x, float y, float z) {
      this.stateInfoRender().offset.set(x, y, z);
   }

   public void setIsometric(boolean iso) {
      this.isometric = iso;
   }

   public boolean isIsometric() {
      return this.isometric;
   }

   public void setFlipY(boolean flip) {
      this.flipY = flip;
   }

   public void setAlpha(float alpha) {
      this.alpha = alpha;
   }

   public void setTint(float r, float g, float b) {
      AnimatedModel.StateInfo stateInfo = this.stateInfoMain();
      stateInfo.tintR = r;
      stateInfo.tintG = g;
      stateInfo.tintB = b;
   }

   public void setTint(ColorInfo tint) {
      this.setTint(tint.r, tint.g, tint.b);
   }

   public void setTrackTime(float trackTime) {
      this.trackTime = trackTime;
   }

   public void setScale(float scale) {
      this.finalScale = PZMath.max(0.0F, scale);
   }

   public float getScale() {
      return this.finalScale;
   }

   public void setCullFace(int culLFace) {
      this.cullFace = culLFace;
   }

   public void setHighResDepthMultiplier(float m) {
      this.highResDepthMultiplier = m;
   }

   public void clothingItemChanged(String itemGuid) {
      this.clothingChanged = true;
   }

   public boolean isAnimate() {
      return this.animate;
   }

   public void setAnimate(boolean animate) {
      this.animate = animate;
   }

   public void setShowBip01(boolean show) {
      this.showBip01 = show;
   }

   private void initOutfit() {
      String outfitName = this.outfitName;
      this.outfitName = null;
      if (!StringUtils.isNullOrWhitespace(outfitName)) {
         ModelManager.instance.create();
         this.baseVisual.dressInNamedOutfit(outfitName, this.itemVisuals);
         this.setModelData(this.baseVisual, this.itemVisuals);
      }
   }

   private void populateCharacterModelSlot() {
      HumanVisual humanVisual = this.getHumanVisual();
      if (humanVisual == null) {
         this.updateTextures = true;
      } else {
         CharacterMask mask = HumanVisual.GetMask(this.itemVisuals);
         if (mask.isPartVisible(Part.Head)) {
            this.addHeadHair(this.itemVisuals.findHat());
         }

         for (int i = this.itemVisuals.size() - 1; i >= 0; i--) {
            ItemVisual itemVisual = (ItemVisual)this.itemVisuals.get(i);
            ClothingItem clothingItem = itemVisual.getClothingItem();
            if (clothingItem != null && clothingItem.isReady() && !this.isItemModelHidden(this.itemVisuals, itemVisual)) {
               this.addClothingItem(itemVisual, clothingItem);
            }
         }

         for (int i = humanVisual.getBodyVisuals().size() - 1; i >= 0; i--) {
            ItemVisual itemVisual = (ItemVisual)humanVisual.getBodyVisuals().get(i);
            ClothingItem clothingItem = itemVisual.getClothingItem();
            if (clothingItem != null && clothingItem.isReady()) {
               this.addClothingItem(itemVisual, clothingItem);
            }
         }

         this.updateTextures = true;
         Lambda.forEachFrom(PZArrayUtil::forEach, this.modelInstance.sub, this.modelInstance, (m, modelInstance) -> m.animPlayer = modelInstance.animPlayer);
      }
   }

   private void addHeadHair(ItemVisual hatVisual) {
      HumanVisual humanVisual = this.getHumanVisual();
      ImmutableColor col = humanVisual.getHairColor();
      ImmutableColor beardcol = humanVisual.getBeardColor();
      if (this.isFemale()) {
         HairStyle hairStyle = HairStyles.instance.FindFemaleStyle(humanVisual.getHairModel());
         if (hairStyle != null && hatVisual != null && hatVisual.getClothingItem() != null) {
            hairStyle = HairStyles.instance.getAlternateForHat(hairStyle, hatVisual.getClothingItem().hatCategory);
         }

         if (hairStyle != null && hairStyle.isValid()) {
            DebugType.Clothing.debugln("  Adding female hair: %s", new Object[]{hairStyle.name});
            this.addHeadHairItem(hairStyle.model, hairStyle.texture, col);
         }
      } else {
         HairStyle hairStyle = HairStyles.instance.FindMaleStyle(humanVisual.getHairModel());
         if (hairStyle != null && hatVisual != null && hatVisual.getClothingItem() != null) {
            hairStyle = HairStyles.instance.getAlternateForHat(hairStyle, hatVisual.getClothingItem().hatCategory);
         }

         if (hairStyle != null && hairStyle.isValid()) {
            DebugType.Clothing.debugln("  Adding male hair: %s", new Object[]{hairStyle.name});
            this.addHeadHairItem(hairStyle.model, hairStyle.texture, col);
         }

         BeardStyle beardStyle = BeardStyles.instance.FindStyle(humanVisual.getBeardModel());
         if (beardStyle != null && beardStyle.isValid()) {
            if (hatVisual != null
               && hatVisual.getClothingItem() != null
               && !StringUtils.isNullOrEmpty(hatVisual.getClothingItem().hatCategory)
               && hatVisual.getClothingItem().hatCategory.contains("nobeard")) {
               return;
            }

            DebugType.Clothing.debugln("  Adding beard: %s", new Object[]{beardStyle.name});
            this.addHeadHairItem(beardStyle.model, beardStyle.texture, beardcol);
         }
      }
   }

   private void addHeadHairItem(String modelFileName, String textureName, ImmutableColor tintColor) {
      if (StringUtils.isNullOrWhitespace(modelFileName)) {
         DebugType.Clothing.warn("No model specified.");
      } else {
         modelFileName = this.processModelFileName(modelFileName);
         ModelInstance inst = ModelManager.instance.newAdditionalModelInstance(modelFileName, textureName, null, this.modelInstance.animPlayer, null);
         if (inst != null) {
            this.postProcessNewItemInstance(this.modelInstance, inst, tintColor);
         }
      }
   }

   private void addClothingItem(ItemVisual itemVisual, ClothingItem clothingItem) {
      String modelFileName = clothingItem.getModel(this.female);
      if (StringUtils.isNullOrWhitespace(modelFileName)) {
         DebugType.Clothing.debugln("No model specified by item: %s", new Object[]{clothingItem.mame});
      } else {
         modelFileName = this.processModelFileName(modelFileName);
         String textureName = itemVisual.getTextureChoice(clothingItem);
         ImmutableColor tintColor = itemVisual.getTint(clothingItem);
         String attachBone = clothingItem.attachBone;
         String shaderName = clothingItem.shader;
         ModelInstance inst;
         if (attachBone != null && !attachBone.isEmpty()) {
            inst = this.addStatic(modelFileName, textureName, attachBone, shaderName);
         } else {
            inst = ModelManager.instance.newAdditionalModelInstance(modelFileName, textureName, null, this.modelInstance.animPlayer, shaderName);
         }

         if (inst != null) {
            this.postProcessNewItemInstance(this.modelInstance, inst, tintColor);
            inst.setItemVisual(itemVisual);
         }
      }
   }

   private boolean isItemModelHidden(ItemVisuals visuals, ItemVisual visual) {
      BodyLocationGroup bodyLocationGroup = BodyLocations.getGroup("Human");
      return PopTemplateManager.instance.isItemModelHidden(bodyLocationGroup, visuals, visual);
   }

   private String processModelFileName(String modelFileName) {
      modelFileName = modelFileName.replaceAll("\\\\", "/");
      return modelFileName.toLowerCase(Locale.ENGLISH);
   }

   private void postProcessNewItemInstance(ModelInstance parentInstance, ModelInstance modelInstance, ImmutableColor tintColor) {
      modelInstance.depthBias = 0.0F;
      modelInstance.matrixModel = this.modelInstance;
      modelInstance.tintR = tintColor.r;
      modelInstance.tintG = tintColor.g;
      modelInstance.tintB = tintColor.b;
      modelInstance.animPlayer = this.modelInstance.animPlayer;
      parentInstance.sub.add(modelInstance);
      modelInstance.setOwner(this);
   }

   private void DoCharacterModelEquipped() {
      if (!StringUtils.isNullOrWhitespace(this.primaryHandModelName)) {
         ModelInstance modelInstance2 = this.addStatic(this.primaryHandModelName, "Bip01_Prop1");
         this.postProcessNewItemInstance(this.modelInstance, modelInstance2, ImmutableColor.white);
      }

      if (!StringUtils.isNullOrWhitespace(this.secondaryHandModelName)) {
         ModelInstance modelInstance2 = this.addStatic(this.secondaryHandModelName, "Bip01_Prop2");
         this.postProcessNewItemInstance(this.modelInstance, modelInstance2, ImmutableColor.white);
      }

      for (int i = 0; i < this.attachedModelNames.size(); i++) {
         AttachedModelName amn = this.attachedModelNames.get(i);
         if (!ModelManager.instance.shouldHideModel(this.itemVisuals, ItemBodyLocation.get(ResourceLocation.of(amn.attachmentNameSelf)))
            && !ModelManager.instance.shouldHideModel(this.itemVisuals, ItemBodyLocation.get(ResourceLocation.of(amn.attachmentNameParent)))) {
            ModelInstance modelInstance1 = ModelManager.instance.addStatic(null, amn.modelName, amn.attachmentNameSelf, amn.attachmentNameParent);
            this.postProcessNewItemInstance(this.modelInstance, modelInstance1, ImmutableColor.white);
            if (amn.bloodLevel > 0.0F && !Core.getInstance().getOptionSimpleWeaponTextures()) {
               ModelInstanceTextureInitializer miti = ModelInstanceTextureInitializer.alloc();
               miti.init(modelInstance1, amn.bloodLevel);
               modelInstance1.setTextureInitializer(miti);
            }

            for (int j = 0; j < amn.getChildCount(); j++) {
               AttachedModelName amn2 = amn.getChildByIndex(j);
               ModelInstance subInstance = ModelManager.instance.addStatic(modelInstance1, amn2.modelName, amn2.attachmentNameSelf, amn2.attachmentNameParent);
               modelInstance1.sub.remove(subInstance);
               this.postProcessNewItemInstance(modelInstance1, subInstance, ImmutableColor.white);
            }
         }
      }
   }

   private ModelInstance addStatic(String staticModel, String boneName) {
      String meshName = staticModel;
      String texName = staticModel;
      String shaderName = null;
      ModelScript modelScript = ScriptManager.instance.getModelScript(staticModel);
      if (modelScript != null) {
         meshName = modelScript.getMeshName();
         texName = modelScript.getTextureName();
         shaderName = modelScript.getShaderName();
      }

      return this.addStatic(meshName, texName, boneName, shaderName);
   }

   private ModelInstance addStatic(String meshName, String texName, String boneName, String shaderName) {
      DebugType.ModelManager.debugln("Adding static Model: %s", new Object[]{meshName});
      Model model = ModelManager.instance.tryGetLoadedModel(meshName, texName, true, shaderName, false);
      if (model == null) {
         ModelManager.instance.loadStaticModel(meshName.toLowerCase(), texName, shaderName);
         model = ModelManager.instance.getLoadedModel(meshName, texName, true, shaderName);
         if (model == null) {
            DebugType.ModelManager.error("ModelManager.addStatic> Model not found. model:" + meshName + " tex:" + texName);
            return null;
         }
      }

      ModelInstance inst = ModelManager.instance.newInstance(model, null, this.modelInstance.animPlayer);
      inst.parent = this.modelInstance;
      if (this.modelInstance.animPlayer != null) {
         inst.parentBone = this.modelInstance.animPlayer.getSkinningBoneIndex(boneName, inst.parentBone);
         inst.parentBoneName = boneName;
      }

      return inst;
   }

   private AnimatedModel.StateInfo stateInfoMain() {
      int stateIndex = SpriteRenderer.instance.getMainStateIndex();
      return this.stateInfos[stateIndex];
   }

   private AnimatedModel.StateInfo stateInfoRender() {
      int stateIndex = SpriteRenderer.instance.getRenderStateIndex();
      return this.stateInfos[stateIndex];
   }

   public void update() {
      ProfileArea var1 = GameProfiler.getInstance().profile("AnimatedModel.Update");

      try {
         this.updateInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void updateInternal() {
      this.initOutfit();
      if (this.clothingChanged) {
         this.clothingChanged = false;
         this.setModelData(this.baseVisual, this.itemVisuals);
      }

      this.modelInstance.SetForceDir(this.angle);
      GameTime gameTime = GameTime.getInstance();
      float fpsMultiplier = gameTime.fpsMultiplier;
      float multiplier = gameTime.getTrueMultiplier();
      if (this.animate) {
         gameTime.setMultiplier(1.0F);
         if (UIManager.useUiFbo) {
            gameTime.fpsMultiplier = gameTime.fpsMultiplier * (GameWindow.averageFPS / Core.getInstance().getOptionUIRenderFPS());
         }

         this.actionContext.update();
         this.advancedAnimator.update(this.getAnimationTimeDelta());
         this.animPlayer.Update();
         int stateIndex = SpriteRenderer.instance.getMainStateIndex();
         AnimatedModel.StateInfo stateInfo = this.stateInfos[stateIndex];
         if (!stateInfo.readyData.isEmpty()) {
            ModelInstance modelInstance1 = stateInfo.readyData.get(0).modelInstance;
            if (modelInstance1 != this.modelInstance && modelInstance1.animPlayer != this.modelInstance.animPlayer) {
               modelInstance1.Update(this.getAnimationTimeDelta());
            }
         }

         gameTime.fpsMultiplier = fpsMultiplier;
         gameTime.setMultiplier(multiplier);
      } else {
         gameTime.fpsMultiplier = 100.0F;

         try {
            this.advancedAnimator.update(this.getAnimationTimeDelta());
         } finally {
            gameTime.fpsMultiplier = fpsMultiplier;
         }

         if (this.trackTime > 0.0F && this.animPlayer.getMultiTrack().getTrackCount() > 0) {
            ((AnimationTrack)this.animPlayer.getMultiTrack().getTracks().get(0)).setCurrentTimeValue(this.trackTime);
         }

         this.animPlayer.Update(0.0F);
      }
   }

   private boolean isModelInstanceReadyNoChildren(ModelInstance modelInstance) {
      return modelInstance.model == null || !modelInstance.model.isReady() ? false : modelInstance.model.mesh.isReady() && modelInstance.model.mesh.vb != null;
   }

   private boolean isModelInstanceReady(ModelInstance modelInstance) {
      if (!this.isModelInstanceReadyNoChildren(modelInstance)) {
         return false;
      }

      for (int i = 0; i < modelInstance.sub.size(); i++) {
         ModelInstance subInstance = (ModelInstance)modelInstance.sub.get(i);
         if (!this.isModelInstanceReady(subInstance)) {
            return false;
         }
      }

      return true;
   }

   private void incrementRefCount(ModelInstance modelInstance) {
      modelInstance.renderRefCount++;

      for (int i = 0; i < modelInstance.sub.size(); i++) {
         ModelInstance subInstance = (ModelInstance)modelInstance.sub.get(i);
         this.incrementRefCount(subInstance);
      }
   }

   private void initRenderData(AnimatedModel.StateInfo stateInfo, AnimatedModel.AnimatedModelInstanceRenderData parentData, ModelInstance modelInstance) {
      AnimatedModel.AnimatedModelInstanceRenderData renderData = (AnimatedModel.AnimatedModelInstanceRenderData)instDataPool.alloc();
      renderData.initModel(modelInstance, parentData);
      renderData.init();
      renderData.modelInstance.targetDepth = 0.5F;
      stateInfo.instData.add(renderData);
      renderData.transformToParent(parentData);

      for (int i = 0; i < modelInstance.sub.size(); i++) {
         ModelInstance subInstance = (ModelInstance)modelInstance.sub.get(i);
         this.initRenderData(stateInfo, renderData, subInstance);
      }
   }

   public boolean isReadyToRender() {
      return !this.animPlayer.isReady() ? false : this.isModelInstanceReady(this.modelInstance);
   }

   public int renderMain() {
      AnimatedModel.StateInfo stateInfo = this.stateInfoMain();
      if (this.modelInstance != null) {
         if (this.updateTextures) {
            this.updateTextures = false;
            this.textureCreator = ModelInstanceTextureCreator.alloc();
            this.textureCreator.init(this.getVisual(), this.itemVisuals, this.modelInstance);
         }

         this.incrementRefCount(this.modelInstance);
         instDataPool.release(stateInfo.instData);
         stateInfo.instData.clear();
         if (!stateInfo.modelsReady && this.isReadyToRender()) {
            float fpsMultiplier = GameTime.getInstance().fpsMultiplier;
            GameTime.getInstance().fpsMultiplier = 100.0F;

            try {
               this.advancedAnimator.update(this.getAnimationTimeDelta());
            } finally {
               GameTime.getInstance().fpsMultiplier = fpsMultiplier;
            }

            this.animPlayer.Update(0.0F);
            stateInfo.modelsReady = true;
         }

         this.initRenderData(stateInfo, null, this.modelInstance);
      }

      stateInfo.modelInstance = this.modelInstance;
      stateInfo.textureCreator = this.textureCreator != null && !this.textureCreator.isRendered() ? this.textureCreator : null;

      for (int i = 0; i < stateInfo.readyData.size(); i++) {
         AnimatedModel.AnimatedModelInstanceRenderData data = stateInfo.readyData.get(i);
         if (data.modelInstance.animPlayer == null || data.modelInstance.animPlayer.getModel() == data.modelInstance.model) {
            data.init();
            data.transformToParent(stateInfo.getParentData(data.modelInstance));
         }
      }

      stateInfo.offset.set(this.offset);
      stateInfo.rendered = false;
      return SpriteRenderer.instance.getMainStateIndex();
   }

   public boolean isRendered() {
      return this.stateInfoRender().rendered;
   }

   private void doneWithTextureCreator(ModelInstanceTextureCreator tc) {
      if (tc != null) {
         for (int i = 0; i < this.stateInfos.length; i++) {
            if (this.stateInfos[i].textureCreator == tc) {
               return;
            }
         }

         if (tc.isRendered()) {
            tc.postRender();
            if (tc == this.textureCreator) {
               this.textureCreator = null;
            }
         } else if (tc != this.textureCreator) {
            tc.postRender();
         }
      }
   }

   private void release(ArrayList<AnimatedModel.AnimatedModelInstanceRenderData> instData) {
      for (int i = 0; i < instData.size(); i++) {
         AnimatedModel.AnimatedModelInstanceRenderData data = instData.get(i);
         if (data.modelInstance.getTextureInitializer() != null) {
            data.modelInstance.getTextureInitializer().postRender();
         }

         ModelManager.instance.derefModelInstance(data.modelInstance);
      }

      instDataPool.release(instData);
   }

   public void postRender(boolean bRendered) {
      int stateIndex = SpriteRenderer.instance.getMainStateIndex();
      AnimatedModel.StateInfo stateInfo = this.stateInfos[stateIndex];
      ModelInstanceTextureCreator tc = stateInfo.textureCreator;
      stateInfo.textureCreator = null;
      this.doneWithTextureCreator(tc);
      stateInfo.modelInstance = null;
      if (this.animate && stateInfo.rendered) {
         this.release(stateInfo.readyData);
         stateInfo.readyData.clear();
         PZArrayUtil.addAll(stateInfo.readyData, stateInfo.instData);
         stateInfo.instData.clear();
      } else if (!this.animate) {
      }

      this.release(stateInfo.instData);
      stateInfo.instData.clear();
   }

   public void setTargetDepth(float targetDepth) {
      int stateIndex = SpriteRenderer.instance.getRenderStateIndex();
      AnimatedModel.StateInfo stateInfo = this.stateInfos[stateIndex];

      for (int i = 0; i < stateInfo.instData.size(); i++) {
         AnimatedModel.AnimatedModelInstanceRenderData mird = stateInfo.instData.get(i);
         mird.modelInstance.targetDepth = targetDepth;
      }
   }

   public void DoRender(IModelCamera camera) {
      int stateIndex = SpriteRenderer.instance.getRenderStateIndex();
      AnimatedModel.StateInfo stateInfo = this.stateInfos[stateIndex];
      this.ready = true;
      ModelInstanceTextureCreator textureCreator = stateInfo.textureCreator;
      if (textureCreator != null && !textureCreator.isRendered()) {
         textureCreator.render();
         if (!textureCreator.isRendered()) {
            this.ready = false;
         }
      }

      for (int i = 0; i < stateInfo.instData.size(); i++) {
         AnimatedModel.AnimatedModelInstanceRenderData mird = stateInfo.instData.get(i);
         if (!this.isModelInstanceReadyNoChildren(mird.modelInstance)) {
            this.ready = false;
         }

         ModelInstanceTextureInitializer miti = mird.modelInstance.getTextureInitializer();
         if (miti != null && !miti.isRendered()) {
            miti.render();
            if (!miti.isRendered()) {
               this.ready = false;
            }
         }
      }

      if (this.ready && !stateInfo.modelsReady) {
         this.ready = false;
      }

      if (this.ready || !stateInfo.readyData.isEmpty()) {
         GL11.glPushClientAttrib(-1);
         GL11.glPushAttrib(1048575);
         GL11.glEnable(3042);
         GL11.glBlendFunc(770, 771);
         GL11.glEnable(3008);
         GL11.glAlphaFunc(516, 0.0F);
         camera.Begin();
         this.StartCharacter();
         this.Render();
         boolean bDepthDebugBox = false;
         this.EndCharacter();
         camera.End();
         GL11.glDepthFunc(519);
         GL11.glPopAttrib();
         GL11.glPopClientAttrib();
         Texture.lastTextureID = -1;
         GLStateRenderThread.restore();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         stateInfo.rendered = this.ready;
      }
   }

   public void DoRender(int x, int y, int w, int h, float sizeV, float animPlayerAngle) {
      GL11.glClear(256);
      this.uiModelCamera.viewportX = x;
      this.uiModelCamera.viewportY = y;
      this.uiModelCamera.viewportW = w;
      this.uiModelCamera.viewportH = h;
      this.uiModelCamera.sizeV = sizeV;
      this.uiModelCamera.animPlayerAngle = animPlayerAngle;
      this.DoRender(this.uiModelCamera);
   }

   public void DoRenderToWorld(float x, float y, float z, float animPlayerAngle) {
      worldModelCamera.posX = x;
      worldModelCamera.posY = y;
      worldModelCamera.posZ = z;
      worldModelCamera.angle = animPlayerAngle;
      worldModelCamera.animatedModel = this;
      this.DoRender(worldModelCamera);
   }

   private void debugDrawAxes() {
      if (Core.debug && DebugOptions.instance.model.render.axis.getValue()) {
         Model.debugDrawAxis(0.0F, 0.0F, 0.0F, 1.0F, 4.0F);
      }
   }

   private void StartCharacter() {
      GL11.glEnable(2929);
      GL11.glEnable(3042);
      if (UIManager.useUiFbo) {
         GL14.glBlendFuncSeparate(770, 771, 1, 771);
      } else {
         GL11.glBlendFunc(770, 771);
      }

      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GL11.glDisable(3089);
      GL11.glDepthMask(true);
      if (pzopt.OccludedOutline.isAtlasLighting()) GL11.glDisable(3042); // pzopt: store surface light independently of material alpha; native depth/discard still select the surface
   }

   private void EndCharacter() {
      GL11.glDepthMask(false);
      GL11.glViewport(0, 0, Core.width, Core.height);
   }

   private void Render() {
      int stateIndex = SpriteRenderer.instance.getRenderStateIndex();
      AnimatedModel.StateInfo stateInfo = this.stateInfos[stateIndex];
      ModelInstance modelInstance = stateInfo.modelInstance;
      if (modelInstance == null) {
         boolean instData = true;
      } else {
         ArrayList<AnimatedModel.AnimatedModelInstanceRenderData> instData = this.ready ? stateInfo.instData : stateInfo.readyData;

         for (int i = 0; i < instData.size(); i++) {
            AnimatedModel.AnimatedModelInstanceRenderData instData1 = instData.get(i);
            this.DrawChar(instData1);
         }
      }

      this.debugDrawAxes();
   }

   private void DrawChar(AnimatedModel.AnimatedModelInstanceRenderData instData) {
      AnimatedModel.StateInfo stateInfo = this.stateInfoRender();
      ModelInstance inst = instData.modelInstance;
      FloatBuffer matrixPalette = instData.matrixPalette;
      if (inst != null) {
         if (inst.animPlayer != null) {
            if (inst.animPlayer.hasSkinningData()) {
               if (inst.model != null) {
                  if (inst.model.isReady()) {
                     if (inst.tex != null || inst.model.tex != null) {
                        if (inst.model.effect == null) {
                           inst.model.CreateShader("basicEffect");
                        }

                        GL11.glEnable(2884);
                        GL11.glCullFace(this.cullFace);
                        GL11.glEnable(2929);
                        GL11.glEnable(3008);
                        GL11.glDepthFunc(513);
                        GL11.glDepthRange(0.0, 1.0);
                        GL11.glAlphaFunc(516, 0.01F);
                        if (!inst.model.effect.isInstanced()) {
                           this.DrawCharSingular(instData);
                        } else {
                           instData.properties.SetFloat("Alpha", this.alpha);
                           instData.properties.SetVector3("AmbientColour", this.ambient.r * 0.45F, this.ambient.g * 0.45F, this.ambient.b * 0.45F);
                           instData.properties
                              .SetVector3(
                                 "TintColour",
                                 this.modelInstance.tintR * stateInfo.tintR,
                                 this.modelInstance.tintG * stateInfo.tintG,
                                 this.modelInstance.tintB * stateInfo.tintB
                              );
                           if (this.highResDepthMultiplier != 0.0F) {
                              instData.properties.SetFloat("HighResDepthMultiplier", this.highResDepthMultiplier);
                           }

                           Matrix4f mvp = Core.getInstance().modelViewMatrixStack.alloc();
                           VertexBufferObject.getModelViewProjection(mvp);
                           instData.properties.SetMatrix4("mvp", mvp).transpose();
                           Core.getInstance().modelViewMatrixStack.release(mvp);
                           RenderList.DrawImmediate(null, instData);
                           ShaderHelper.forgetCurrentlyBound();
                           ShaderHelper.glUseProgramObjectARB(0);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void DrawCharSingular(AnimatedModel.AnimatedModelInstanceRenderData instData) {
      AnimatedModel.StateInfo stateInfo = this.stateInfoRender();
      ModelInstance inst = instData.modelInstance;
      FloatBuffer matrixPalette = instData.matrixPalette;
      Shader effect = inst.model.effect;
      if (effect != null) {
         effect.Start();
         if (inst.model.isStatic) {
            effect.setTransformMatrix(instData.xfrm, true);
         } else {
            effect.setMatrixPalette(matrixPalette, true);
         }

         effect.setLight(0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
         effect.setLight(1, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
         effect.setLight(2, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
         effect.setLight(3, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
         effect.setLight(4, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
         float lightMul = 0.7F;

         for (int i = 0; i < this.lights.length; i++) {
            ResultLight light = this.lights[i];
            if (light.radius > 0) {
               effect.setLight(
                  i,
                  light.x + 0.5F,
                  light.y + 0.5F,
                  light.z + 0.5F,
                  light.r * 0.7F,
                  light.g * 0.7F,
                  light.b * 0.7F,
                  light.radius,
                  instData.animPlayerAngle,
                  this.lightsOriginX,
                  this.lightsOriginY,
                  this.lightsOriginZ,
                  null
               );
            }
         }

         if (inst.tex != null) {
            effect.setTexture(inst.tex, "Texture", 0);
         } else if (inst.model.tex != null) {
            effect.setTexture(inst.model.tex, "Texture", 0);
         }

         if (this.outside) {
            float mul = 1.7F;
            effect.setLight(
               3,
               this.lightsOriginX - 2.0F,
               this.lightsOriginY - 2.0F,
               this.lightsOriginZ + 1.0F,
               this.ambient.r * 1.7F / 4.0F,
               this.ambient.g * 1.7F / 4.0F,
               this.ambient.b * 1.7F / 4.0F,
               5000.0F,
               instData.animPlayerAngle,
               this.lightsOriginX,
               this.lightsOriginY,
               this.lightsOriginZ,
               null
            );
            effect.setLight(
               4,
               this.lightsOriginX + 2.0F,
               this.lightsOriginY + 2.0F,
               this.lightsOriginZ + 1.0F,
               this.ambient.r * 1.7F / 4.0F,
               this.ambient.g * 1.7F / 4.0F,
               this.ambient.b * 1.7F / 4.0F,
               5000.0F,
               instData.animPlayerAngle,
               this.lightsOriginX,
               this.lightsOriginY,
               this.lightsOriginZ,
               null
            );
         } else if (this.room) {
            float mul = 1.7F;
            effect.setLight(
               4,
               this.lightsOriginX + 2.0F,
               this.lightsOriginY + 2.0F,
               this.lightsOriginZ + 1.0F,
               this.ambient.r * 1.7F / 4.0F,
               this.ambient.g * 1.7F / 4.0F,
               this.ambient.b * 1.7F / 4.0F,
               5000.0F,
               instData.animPlayerAngle,
               this.lightsOriginX,
               this.lightsOriginY,
               this.lightsOriginZ,
               null
            );
         }

         float targetDepth = inst.targetDepth;
         effect.setTargetDepth(targetDepth);
         effect.setDepthBias(inst.depthBias / 50.0F);
         effect.setAmbient(this.ambient.r * 0.45F, this.ambient.g * 0.45F, this.ambient.b * 0.45F);
         effect.setLightingAmount(1.0F);
         effect.setHueShift(inst.hue);
         effect.setTint(inst.tintR * stateInfo.tintR, inst.tintG * stateInfo.tintG, inst.tintB * stateInfo.tintB);
         effect.setAlpha(this.alpha);
         effect.setScale(this.finalScale);
         if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
            for (int i = 0; i < 5; i++) {
               effect.setLight(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN, inst);
            }

            effect.setAmbient(1.0F, 1.0F, 1.0F);
         }

         if (this.highResDepthMultiplier != 0.0F) {
            effect.setHighResDepthMultiplier(this.highResDepthMultiplier);
         }
      }

      inst.model.mesh.Draw(effect);
      if (effect != null) {
         if (this.highResDepthMultiplier != 0.0F) {
            effect.setHighResDepthMultiplier(0.0F);
         }

         effect.setScale(1.0F);
         effect.End();
      }

      DefaultShader.isActive = false;
      ShaderHelper.forgetCurrentlyBound();
      GL20.glUseProgram(0);
      if (Core.debug && DebugOptions.instance.model.render.lights.getValue() && inst.parent == null) {
         if (this.lights[0].radius > 0) {
            Model.debugDrawLightSource(this.lights[0].x, this.lights[0].y, this.lights[0].z, 0.0F, 0.0F, 0.0F, -instData.animPlayerAngle);
         }

         if (this.lights[1].radius > 0) {
            Model.debugDrawLightSource(this.lights[1].x, this.lights[1].y, this.lights[1].z, 0.0F, 0.0F, 0.0F, -instData.animPlayerAngle);
         }

         if (this.lights[2].radius > 0) {
            Model.debugDrawLightSource(this.lights[2].x, this.lights[2].y, this.lights[2].z, 0.0F, 0.0F, 0.0F, -instData.animPlayerAngle);
         }
      }

      if (Core.debug && DebugOptions.instance.model.render.bones.getValue()) {
         VBORenderer vbor = VBORenderer.getInstance();
         vbor.startRun(vbor.formatPositionColor);
         vbor.setMode(1);
         vbor.setLineWidth(1.0F);
         vbor.setDepthTest(false);

         for (int i = 0; i < inst.animPlayer.getModelTransformsCount(); i++) {
            int parentIdx = (Integer)inst.animPlayer.getSkinningData().skeletonHierarchy.get(i);
            if (parentIdx >= 0) {
               org.lwjgl.util.vector.Matrix4f mtx1 = inst.animPlayer.getModelTransformAt(i);
               org.lwjgl.util.vector.Matrix4f mtx2 = inst.animPlayer.getModelTransformAt(parentIdx);
               Color c = Model.debugDrawColours[i % Model.debugDrawColours.length];
               vbor.addLine(mtx1.m03, mtx1.m13, mtx1.m23, mtx2.m03, mtx2.m13, mtx2.m23, c.r, c.g, c.b, 1.0F);
            }
         }

         vbor.endRun();
         vbor.flush();
         GL11.glColor3f(1.0F, 1.0F, 1.0F);
         GL11.glEnable(2929);
      }

      if (this.showBip01 && inst.animPlayer.getModelTransformsCount() > 0) {
         int boneIndex = inst.animPlayer.getSkinningBoneIndex("Bip01", -1);
         if (boneIndex != -1) {
            org.lwjgl.util.vector.Matrix4f mtx = inst.animPlayer.getModelTransformAt(boneIndex);
            Model.debugDrawAxis(mtx.m03, 0.0F * mtx.m13, mtx.m23, 0.1F, 4.0F);
         }
      }

      ShaderHelper.glUseProgramObjectARB(0);
   }

   public ShadowParams calculateShadowParams(ShadowParams sp, boolean bRagdoll) {
      return IsoGameCharacter.calculateShadowParams(this.getAnimationPlayer(), this.getAnimalSize(), bRagdoll, sp);
   }

   public void releaseAnimationPlayer() {
      if (this.animPlayer != null) {
         this.animPlayer = (AnimationPlayer)Pool.tryRelease(this.animPlayer);
      }
   }

   public void OnAnimEvent(AnimLayer sender, AnimationTrack track, AnimEvent event) {
      if (!StringUtils.isNullOrWhitespace(event.eventName)) {
         String stateName = AnimLayer.getCurrentStateName(sender);
         this.actionContext.reportEvent(stateName, event.eventName);
      }
   }

   public boolean hasAnimationPlayer() {
      return true;
   }

   public IGrappleable getGrappleable() {
      return this.grappleable;
   }

   public AnimationPlayer getAnimationPlayer() {
      Model model = this.getVisual().getModel();
      if (this.animPlayer != null && this.animPlayer.getModel() != model) {
         this.animPlayer = (AnimationPlayer)Pool.tryRelease(this.animPlayer);
      }

      if (this.animPlayer == null) {
         this.animPlayer = AnimationPlayer.alloc(model);
      }

      return this.animPlayer;
   }

   public void actionStateChanged(ActionContext sender) {
      this.advancedAnimator.setState(sender.getCurrentStateName(), PZArrayUtil.listConvert(sender.getChildStates(), state -> state.getName()));
   }

   public AnimationPlayerRecorder getAnimationRecorder() {
      return null;
   }

   public boolean isAnimationRecorderActive() {
      return false;
   }

   public ActionContext getActionContext() {
      return this.actionContext;
   }

   public AdvancedAnimator getAdvancedAnimator() {
      return this.advancedAnimator;
   }

   public ModelInstance getModelInstance() {
      return this.modelInstance;
   }

   public String GetAnimSetName() {
      return this.animSetName;
   }

   public String getUID() {
      return this.uid;
   }

   public static class AnimatedModelInstanceRenderData {
      public Model model;
      public Texture tex;
      public ModelInstance modelInstance;
      public FloatBuffer matrixPalette;
      private boolean matrixPaletteValid;
      public final Matrix4f xfrm = new Matrix4f();
      float animPlayerAngle;
      public boolean ignoreLighting;
      public final ShaderPropertyBlock properties = new ShaderPropertyBlock();
      public AnimatedModel.AnimatedModelInstanceRenderData parent;

      public void initMatrixPalette() {
         this.animPlayerAngle = Float.NaN;
         this.matrixPaletteValid = false;
         if (this.modelInstance.animPlayer != null && this.modelInstance.animPlayer.isReady()) {
            this.animPlayerAngle = this.modelInstance.animPlayer.getRenderedAngle();
            if (!this.modelInstance.model.isStatic) {
               SkinningData skinningData = (SkinningData)this.modelInstance.model.tag;
               if (Core.debug && skinningData == null) {
                  DebugType.General.warn("skinningData is null, matrixPalette may be invalid");
               }

               // pzopt: skinPalettePrecompute. When the bone worker already stored this set as a palette (AnimationPlayer
               // override), one bulk copy replaces the sixteen puts per matrix below.
               FloatBuffer pzoptReady = pzopt.Overrides.enabled() && pzopt.Config.SKIN_PALETTE_PRECOMPUTE
                  ? this.modelInstance.animPlayer.pzoptSkinPalette(skinningData)
                  : null;
               if (pzoptReady != null) {
                  int floats = pzoptReady.limit();
                  if (this.matrixPalette == null || this.matrixPalette.capacity() < floats) {
                     this.matrixPalette = BufferUtils.createFloatBuffer(floats);
                  }

                  this.matrixPalette.clear();
                  this.matrixPalette.put(pzoptReady);
                  this.matrixPalette.flip();
                  this.matrixPaletteValid = true;
                  return;
               }

               org.lwjgl.util.vector.Matrix4f[] skinTransforms = this.modelInstance.animPlayer.getSkinTransforms(skinningData);
               int matrixFloats = 16;
               if (this.matrixPalette == null || this.matrixPalette.capacity() < skinTransforms.length * 16) {
                  this.matrixPalette = BufferUtils.createFloatBuffer(skinTransforms.length * 16);
               }

               this.matrixPalette.clear();

               for (int i = 0; i < skinTransforms.length; i++) {
                  skinTransforms[i].store(this.matrixPalette);
               }

               this.matrixPalette.flip();
               this.matrixPaletteValid = true;
            }
         }
      }

      public AnimatedModel.AnimatedModelInstanceRenderData init() {
         if (this.matrixPaletteValid) {
            ShaderParameter palette = this.properties.GetParameter("MatrixPalette");
            org.lwjgl.util.vector.Matrix4f[] bones;
            if (palette == null) {
               bones = new org.lwjgl.util.vector.Matrix4f[60];
               this.properties.SetMatrix4Array("MatrixPalette", bones);
            } else {
               bones = palette.GetMatrix4Array();
            }

            int numBones = this.matrixPalette.limit() / 64;

            for (int i = 0; i < numBones; i++) {
               bones[i].load(this.matrixPalette);
            }

            this.matrixPalette.position(0);
         }

         if (this.modelInstance.getTextureInitializer() != null) {
            this.modelInstance.getTextureInitializer().renderMain();
         }

         this.UpdateCharacter(this.modelInstance.model.effect);
         return this;
      }

      public void initModel(ModelInstance modelInstance, AnimatedModel.AnimatedModelInstanceRenderData parentData) {
         this.xfrm.identity();
         this.modelInstance = modelInstance;
         this.parent = parentData;
         this.ignoreLighting = false;
         if (!modelInstance.model.isStatic && this.matrixPalette == null) {
            this.matrixPalette = BufferUtils.createFloatBuffer(960);
         }

         this.initMatrixPalette();
      }

      public void UpdateCharacter(Shader shader) {
         this.properties.SetFloat("Alpha", 1.0F);
         this.properties.SetVector2("UVScale", 1.0F, 1.0F);
         this.properties.SetFloat("targetDepth", this.modelInstance.targetDepth);
         this.properties.SetFloat("DepthBias", this.modelInstance.depthBias / 50.0F);
         this.properties.SetFloat("LightingAmount", 1.0F);
         this.properties.SetFloat("HueChange", this.modelInstance.hue);
         this.properties.SetVector3("TintColour", 1.0F, 1.0F, 1.0F);
         if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
            for (int i = 0; i < 4; i++) {
               this.properties.SetVector3ArrayElement("LightDirection", i, 0.0F, 1.0F, 0.0F);
            }

            this.properties.SetVector3("AmbientColour", 1.0F, 1.0F, 1.0F);
         }

         this.properties.SetMatrix4("transform", this.xfrm);
      }

      public AnimatedModel.AnimatedModelInstanceRenderData transformToParent(AnimatedModel.AnimatedModelInstanceRenderData parentData) {
         if (this.modelInstance instanceof VehicleModelInstance || this.modelInstance instanceof VehicleSubModelInstance) {
            return this;
         }

         if (parentData == null) {
            return this;
         }

         this.xfrm.set(parentData.xfrm);
         this.xfrm.transpose();
         Matrix4f attachmentXfrm = (Matrix4f)((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).alloc();
         ModelAttachment parentAttachment = parentData.modelInstance.getAttachmentById(this.modelInstance.attachmentNameParent);
         if (parentAttachment == null && this.modelInstance.parentBoneName != null) {
            parentAttachment = parentData.modelInstance.getAttachmentById(this.modelInstance.parentBoneName);
         }

         if (parentAttachment == null) {
            if (this.modelInstance.parentBoneName != null && parentData.modelInstance.animPlayer != null) {
               ModelInstanceRenderData.applyBoneTransform(parentData.modelInstance, this.modelInstance.parentBoneName, this.xfrm);
            }
         } else {
            ModelInstanceRenderData.applyBoneTransform(parentData.modelInstance, parentAttachment.getBone(), this.xfrm);
            ModelInstanceRenderData.makeAttachmentTransform(parentAttachment, attachmentXfrm);
            this.xfrm.mul(attachmentXfrm);
         }

         ModelAttachment selfAttachment = this.modelInstance.getAttachmentById(this.modelInstance.attachmentNameSelf);
         if (selfAttachment == null && this.modelInstance.parentBoneName != null) {
            selfAttachment = this.modelInstance.getAttachmentById(this.modelInstance.parentBoneName);
         }

         if (selfAttachment != null) {
            ModelInstanceRenderData.makeAttachmentTransform(selfAttachment, attachmentXfrm);
            if (ModelInstanceRenderData.invertAttachmentSelfTransform) {
               attachmentXfrm.invert();
            }

            this.xfrm.mul(attachmentXfrm);
         }

         ModelInstanceRenderData.postMultiplyMeshTransform(this.xfrm, this.modelInstance.model.mesh);
         if (this.modelInstance.scale != 1.0F) {
            this.xfrm.scale(this.modelInstance.scale);
         }

         this.xfrm.transpose();
         ((Matrix4fObjectPool)BaseVehicle.TL_matrix4f_pool.get()).release(attachmentXfrm);
         return this;
      }
   }

   public static final class StateInfo {
      ModelInstance modelInstance;
      ModelInstanceTextureCreator textureCreator;
      final ArrayList<AnimatedModel.AnimatedModelInstanceRenderData> instData = new ArrayList<>();
      final ArrayList<AnimatedModel.AnimatedModelInstanceRenderData> readyData = new ArrayList<>();
      boolean modelsReady;
      boolean rendered;
      float tintR = 1.0F;
      float tintG = 1.0F;
      float tintB = 1.0F;
      final Vector3f offset = new Vector3f();

      AnimatedModel.AnimatedModelInstanceRenderData getParentData(ModelInstance inst) {
         for (int i = 0; i < this.readyData.size(); i++) {
            AnimatedModel.AnimatedModelInstanceRenderData data = this.readyData.get(i);
            if (data.modelInstance == inst.parent) {
               return data;
            }
         }

         return null;
      }
   }

   private final class UIModelCamera extends ModelCamera {
      int viewportX;
      int viewportY;
      int viewportW;
      int viewportH;
      float sizeV;
      float animPlayerAngle;

      public void Begin() {
         GL11.glViewport(this.viewportX, this.viewportY, this.viewportW, this.viewportH);
         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         float xScale = (float)this.viewportW / this.viewportH;
         if (AnimatedModel.this.flipY) {
            projection.setOrtho(-this.sizeV * xScale, this.sizeV * xScale, this.sizeV, -this.sizeV, -100.0F, 100.0F);
         } else {
            projection.setOrtho(-this.sizeV * xScale, this.sizeV * xScale, -this.sizeV, this.sizeV, -100.0F, 100.0F);
         }

         float f = Math.sqrt(2048.0F);
         projection.scale(-f, f, f);
         Core.getInstance().projectionMatrixStack.push(projection);
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.identity();
         if (AnimatedModel.this.isometric) {
            modelView.rotate((float) (java.lang.Math.PI / 6), 1.0F, 0.0F, 0.0F);
            modelView.rotate(this.animPlayerAngle + (float) (java.lang.Math.PI / 4), 0.0F, 1.0F, 0.0F);
         } else {
            modelView.rotate(this.animPlayerAngle, 0.0F, 1.0F, 0.0F);
         }

         Vector3f offset = AnimatedModel.this.stateInfoRender().offset;
         modelView.translate(offset.x(), offset.y(), offset.z());
         Core.getInstance().modelViewMatrixStack.push(modelView);
      }

      public void End() {
         Core.getInstance().projectionMatrixStack.pop();
         Core.getInstance().modelViewMatrixStack.pop();
      }
   }

   private static final class WorldModelCamera extends ModelCamera {
      float posX;
      float posY;
      float posZ;
      float angle;
      AnimatedModel animatedModel;

      public void Begin() {
         Core.getInstance().DoPushIsoStuff(this.posX, this.posY, this.posZ, this.angle, false);
         GL11.glDepthMask(true);
         if (PerformanceSettings.fboRenderChunk) {
            float targetDepth = IsoDepthHelper.getSquareDepthData(
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterX), PZMath.fastfloor(IsoCamera.frameState.camCharacterY), this.posX, this.posY, this.posZ
               )
               .depthStart;
            float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
            targetDepth = targetDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F;
            this.animatedModel.setTargetDepth(targetDepth);
         } else {
            this.animatedModel.setTargetDepth(0.5F);
         }
      }

      public void End() {
         Core.getInstance().DoPopIsoStuff();
      }
   }
}
