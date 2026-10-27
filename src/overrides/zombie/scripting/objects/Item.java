package zombie.scripting.objects;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.Map.Entry;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.characterTextures.BloodClothingType;
import zombie.characters.HaloTextHelper;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Color;
import zombie.core.Translator;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.population.ClothingItem;
import zombie.core.skinnedmodel.population.OutfitRNG;
import zombie.core.textures.Texture;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.GameEntityFactory;
import zombie.entity.components.attributes.Attribute;
import zombie.entity.components.attributes.AttributeType;
import zombie.gameStates.GameLoadingState;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemConfigurator;
import zombie.inventory.ItemPickerJava;
import zombie.inventory.types.AlarmClock;
import zombie.inventory.types.AlarmClockClothing;
import zombie.inventory.types.AnimalInventoryItem;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.ComboItem;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.inventory.types.Key;
import zombie.inventory.types.Literature;
import zombie.inventory.types.MapItem;
import zombie.inventory.types.Moveable;
import zombie.inventory.types.Radio;
import zombie.inventory.types.WeaponPart;
import zombie.iso.InstanceTracker;
import zombie.iso.objects.IsoBulletTracerEffects;
import zombie.network.GameServer;
import zombie.radio.devices.DeviceData;
import zombie.scripting.ScriptLoadMode;
import zombie.scripting.ScriptManager;
import zombie.scripting.ScriptParser;
import zombie.scripting.ScriptType;
import zombie.scripting.entity.GameEntityScript;
import zombie.scripting.entity.components.crafting.CraftRecipe;
import zombie.scripting.itemConfig.ItemConfig;
import zombie.util.StringUtils;
import zombie.worldMap.MapDefinitions;

@UsedFromLua
public final class Item extends GameEntityScript {
   static {
      pzopt.Overrides.onClassLoaded("zombie.scripting.objects.Item");
   }

   private static final ThreadLocal<ArrayList<String>> TL_getUsedInRecipes = ThreadLocal.withInitial(ArrayList::new);
   public String clothingExtraSubmenu;
   public String displayName;
   public boolean hidden;
   public boolean cantEat;
   public String icon = "None";
   public boolean medical;
   public boolean cannedFood;
   public boolean survivalGear;
   public boolean mechanicsItem;
   public boolean useWorldItem;
   public float scaleWorldIcon = 1.0F;
   public String closeKillMove;
   public float weaponLength = 0.4F;
   public float actualWeight = 1.0F;
   public float weightWet;
   public float weightEmpty;
   public float hungerChange;
   public float criticalChance = 20.0F;
   public int count = 1;
   public static final int MAXIMUM_FOOD_AGE = 1000000000;
   public int daysFresh = 1000000000;
   public int daysTotallyRotten = 1000000000;
   public int minutesToCook = 60;
   public int minutesToBurn = 120;
   public boolean isCookable;
   private String cookingSound;
   public float stressChange;
   public float boredomChange;
   public float unhappyChange;
   public boolean alwaysWelcomeGift;
   public String replaceOnDeplete;
   public String replaceOnExtinguish;
   public boolean ranged;
   public boolean canStoreWater;
   public float maxRange = 1.0F;
   public float minRange;
   public float thirstChange;
   public float fatigueChange;
   public float minAngle = 1.0F;
   public boolean requiresEquippedBothHands;
   public float maxDamage = 1.5F;
   public float minDamage;
   public float minimumSwingTime;
   public String swingSound = "BaseballBatSwing";
   public String weaponSprite;
   public boolean angleFalloff;
   public int soundVolume;
   public float toHitModifier = 1.0F;
   public int soundRadius;
   public float otherCharacterVolumeBoost;
   private final Set<WeaponCategory> weaponCategories = new HashSet<>();
   private final Set<ItemTag> itemTags = new HashSet<>();
   public String impactSound = "BaseballBatHit";
   public float swingTime = 1.0F;
   public boolean knockBackOnNoDeath = true;
   public boolean splatBloodOnNoDeath;
   public float swingAmountBeforeImpact;
   private AmmoType ammoType;
   public int maxAmmo;
   public ArrayList<String> gunType;
   public int doorDamage = 1;
   public int conditionLowerChance = 10;
   public float headConditionLowerChanceMultiplier = 1.0F;
   public int conditionMax = 10;
   public boolean canBandage;
   public String name;
   public String moduleDotType;
   public int maxHitCount = 1000;
   public boolean useSelf;
   public boolean otherHandUse;
   public ItemTag otherHandRequire;
   public String physicsObject;
   public String swingAnim = "Rifle";
   public float weaponWeight = 1.0F;
   public float enduranceChange;
   public String idleAnim = "Idle";
   public String runAnim = "Run";
   public String attachmentType;
   public String makeUpType;
   public String consolidateOption;
   public ArrayList<String> requireInHandOrInventory;
   public String doorHitSound = "BaseballBatHit";
   public String replaceOnUse;
   public boolean dangerousUncooked;
   public boolean alcoholic;
   public float pushBackMod = 1.0F;
   public int splatNumber = 2;
   public float npcSoundBoost = 1.0F;
   public boolean rangeFalloff;
   public boolean useEndurance = true;
   public boolean multipleHitConditionAffected = true;
   public boolean shareEndurance;
   public boolean canBarricade;
   public boolean useWhileEquipped = true;
   public boolean useWhileUnequipped;
   public int ticksPerEquipUse = 30;
   public boolean disappearOnUse = true;
   public float useDelta = 0.03125F;
   public boolean alwaysKnockdown;
   public float enduranceMod = 1.0F;
   public float knockdownMod = 1.0F;
   public boolean cantAttackWithLowestEndurance;
   public String replaceOnUseOn;
   private String replaceTypes;
   private HashMap<String, String> replaceTypesMap;
   public ArrayList<String> attachmentsProvided;
   public String foodType;
   private boolean poison;
   private int poisonDetectionLevel;
   private int poisonPower;
   public KahluaTable defaultModData;
   public boolean isAimedFirearm;
   public boolean isAimedHandWeapon;
   public boolean canStack = true;
   public float aimingMod = 1.0F;
   public int projectileCount = 1;
   public float projectileSpread;
   public float projectileWeightCenter = 1.0F;
   public float hitAngleMod;
   private float splatSize = 1.0F;
   private float temperature;
   public int numberOfPages = -1;
   public int lvlSkillTrained = -1;
   public int numLevelsTrained = 1;
   public String skillTrained = "";
   public int capacity;
   public float maxItemSize;
   public int weightReduction;
   public String subCategory = "";
   public boolean activatedItem;
   public float lightStrength;
   public boolean torchCone;
   public int lightDistance;
   public ItemBodyLocation canBeEquipped;
   public boolean twoHandWeapon;
   public String customContextMenu;
   public String tooltip;
   public List<String> replaceOnCooked;
   public String displayCategory;
   public Boolean trap = false;
   public boolean obsolete;
   public boolean fishingLure;
   public boolean canBeWrite;
   public int aimingPerkCritModifier;
   public float aimingPerkRangeModifier;
   public float aimingPerkHitChanceModifier;
   public int hitChance;
   public float aimingPerkMinAngleModifier;
   public int recoilDelay;
   public boolean piercingBullets;
   public float soundGain = 1.0F;
   public boolean protectFromRainWhenEquipped;
   private float maxRangeModifier;
   public float minSightRange;
   public float maxSightRange;
   public float lowLightBonus;
   private final float minRangeRangedModifier = 0.0F;
   private float damageModifier;
   private float recoilDelayModifier;
   private int clipSizeModifier;
   private ArrayList<String> mountOn;
   private String partType;
   private String canAttachCallback;
   private String canDetachCallback;
   private String onAttachCallback;
   private String onDetachCallback;
   private int clipSize;
   public int reloadTime;
   private int reloadTimeModifier;
   public int aimingTime;
   private int aimingTimeModifier;
   private int hitChanceModifier;
   private final float angleModifier = 0.0F;
   private float projectileSpreadModifier;
   private float weightModifier;
   private int pageToWrite;
   private boolean removeNegativeEffectOnCooked;
   private int treeDamage;
   private float alcoholPower;
   private String putInSound;
   private String placeOneSound;
   private String placeMultipleSound;
   private String openSound;
   private String closeSound;
   private String breakSound;
   private String customEatSound;
   private String fillFromDispenserSound;
   private String fillFromLakeSound;
   private String fillFromTapSound;
   private String fillFromToiletSound;
   private String bulletOutSound;
   private String shellFallSound;
   private String dropSound;
   private HashMap<String, String> soundMap;
   private float bandagePower;
   private float reduceInfectionPower;
   private String onCooked;
   private String onlyAcceptCategory;
   private String acceptItemFunction;
   private boolean padlock;
   private boolean digitalPadlock;
   private List<String> learnedRecipes;
   private int triggerExplosionTimer;
   private boolean canBePlaced;
   private int explosionRange;
   private int explosionPower;
   private int fireRange;
   private int fireStartingChance;
   private int fireStartingEnergy;
   private int smokeRange;
   private int noiseRange;
   private int noiseDuration;
   private float extraDamage;
   private int explosionTimer;
   private int explosionDuration;
   private String placedSprite;
   private boolean canBeReused;
   private int sensorRange;
   private boolean canBeRemote;
   private boolean remoteController;
   private int remoteRange;
   private String countDownSound;
   private String explosionSound;
   private int fluReduction;
   private int foodSicknessChange;
   private int inverseCoughProbability;
   private int inverseCoughProbabilitySmoker;
   private int painReduction;
   public float torchDot = 0.96F;
   public int colorRed = 255;
   public int colorGreen = 255;
   public int colorBlue = 255;
   public boolean twoWay;
   public int transmitRange;
   public int micRange;
   public float baseVolumeRange;
   public boolean isPortable;
   public boolean isTelevision;
   public int minChannel = 88000;
   public int maxChannel = 108000;
   public boolean usesBattery;
   public boolean isHighTier;
   private String herbalistType;
   private float carbohydrates;
   private float lipids;
   private float proteins;
   private float calories;
   private boolean packaged;
   private boolean cantBeFrozen;
   public String evolvedRecipeName;
   private String replaceOnRotten;
   private float metalValue;
   private String alarmSound;
   private String itemWhenDry;
   private float wetCooldown;
   private boolean isWet;
   private String onEat;
   private boolean cantBeConsolided;
   private boolean badInMicrowave;
   private boolean goodHot;
   private boolean badCold;
   public String map;
   public int vehicleType;
   private ArrayList<VehiclePartModel> vehiclePartModels;
   private int maxCapacity = -1;
   private int itemCapacity = -1;
   private boolean conditionAffectsCapacity;
   private float brakeForce;
   private float durability;
   private int chanceToSpawnDamaged;
   private float conditionLowerNormal;
   private float conditionLowerOffroad;
   private float wheelFriction;
   private float suspensionDamping;
   private float suspensionCompression;
   private float engineLoudness;
   public String clothingItem;
   private ClothingItem clothingItemAsset;
   private String staticModel;
   private String staticModelOn;
   public String primaryAnimMask;
   public String secondaryAnimMask;
   public String primaryAnimMaskAttachment;
   public String secondaryAnimMaskAttachment;
   public String replaceInSecondHand;
   public String replaceInPrimaryHand;
   public String replaceWhenUnequip;
   public ItemReplacement replacePrimaryHand;
   public ItemReplacement replaceSecondHand;
   public String worldObjectSprite;
   public String itemName;
   public Texture normalTexture;
   public List<Texture> specialTextures = new ArrayList<>();
   public List<String> specialWorldTextureNames = new ArrayList<>();
   public String worldTextureName;
   public Texture worldTexture;
   public String eatType;
   public String pourType;
   public String readType;
   public String digType;
   private ArrayList<String> iconsForTexture;
   private float baseSpeed = 1.0F;
   private ArrayList<BloodClothingType> bloodClothingType;
   private float stompPower = 1.0F;
   public float runSpeedModifier = 1.0F;
   public float combatSpeedModifier = 1.0F;
   public ArrayList<String> clothingItemExtra;
   public ArrayList<String> clothingItemExtraOption;
   private Boolean removeOnBroken = true;
   public Boolean canHaveHoles = true;
   private boolean cosmetic;
   private String ammoBox;
   private String insertAmmoStartSound;
   private String insertAmmoSound;
   private String insertAmmoStopSound;
   private String ejectAmmoStartSound;
   private String ejectAmmoSound;
   private String ejectAmmoStopSound;
   private String rackSound;
   private String clickSound = "Stormy9mmClick";
   private String equipSound;
   private String unequipSound;
   private String bringToBearSound;
   private String aimReleaseSound;
   private String magazineType;
   private String weaponReloadType;
   private boolean rackAfterShoot;
   public float jamGunChance = 1.0F;
   private ArrayList<ModelWeaponPart> modelWeaponPart;
   private boolean haveChamber = true;
   public boolean needToBeClosedOnceReload;
   private boolean manuallyRemoveSpentRounds;
   private float biteDefense;
   private float scratchDefense;
   private float corpseSicknessDefense;
   private float bulletDefense;
   private String damageCategory;
   private boolean damageMakeHole;
   public float neckProtectionModifier = 1.0F;
   private String attachmentReplacement;
   private boolean insertAllBulletsReload;
   private int chanceToFall;
   public String fabricType;
   public boolean equippedNoSprint;
   public String worldStaticModel;
   public float critDmgMultiplier;
   public boolean isDung;
   private float insulation;
   private float windresist;
   private float waterresist;
   private String fireMode;
   private ArrayList<String> fireModePossibilities;
   public float cyclicRateMultiplier;
   public boolean removeUnhappinessWhenCooked;
   public float stopPower = 5.0F;
   private String recordedMediaCat;
   private byte acceptMediaType = -1;
   private boolean noTransmit;
   private boolean worldRender = true;
   private String luaCreate;
   private HashMap<String, String> soundParameterMap;
   public String milkReplaceItem;
   public int maxMilk;
   public String animalFeedType;
   public final ArrayList<String> evolvedRecipe = new ArrayList<>();
   private String itemConfigKey;
   private ItemConfig itemConfig;
   private String iconColorMask;
   private String iconFluidMask;
   public String withDrainable;
   public String withoutDrainable;
   private ArrayList<String> staticModelsByIndex;
   private ArrayList<String> worldStaticModelsByIndex;
   private ArrayList<String> weaponSpritesByIndex;
   public String spawnWith;
   public float visionModifier = 1.0F;
   public float hearingModifier = 1.0F;
   public float strainModifier = 1.0F;
   private String onBreak;
   public String damagedSound;
   public String bulletHitArmourSound;
   public String weaponHitArmourSound;
   private String shoutType;
   public float shoutMultiplier = 1.0F;
   public int eatTime;
   public boolean visualAid;
   public float discomfortModifier;
   public float fireFuelRatio;
   public String itemAfterCleaning;
   public boolean isCraftRecipeProduct;
   public boolean isCraftRecipeProductCheckedExtra;
   public boolean canBeForaged;
   public boolean canBeForagedCheckedExtra;
   public final HashSet<String> forageFocusCategories = new HashSet<>();
   public boolean canSpawnAsLoot;
   public boolean canSpawnAsLootCheckedExtra;
   public ArrayList<String> researchableRecipes = new ArrayList<>();
   public boolean isResearchableRecipesCheckedExtra;
   public ArrayList<String> usedInRecipes = new ArrayList<>();
   private String fileName;
   public boolean isUsedInBuildRecipes;
   private String openingRecipe;
   private String doubleClickRecipe;
   private ModelKey muzzleFlashModelKey;
   public final List<BookSubject> bookSubjects = new ArrayList<>();
   public final List<MagazineSubject> magazineSubjects = new ArrayList<>();
   public String hitSound = "BaseballBatHit";
   public String hitFloorSound = "BatOnFloor";
   private ItemBodyLocation bodyLocation;
   public Stack<String> paletteChoices = new Stack<>();
   public String spriteName;
   public String palettesStart = "";
   public static HashMap<Integer, String> netIdToItem = new HashMap<>();
   public static HashMap<String, Integer> netItemToId = new HashMap<>();
   private static int idMax;
   private ItemType itemType;
   private boolean spice;
   private int useForPoison;
   private final HashMap<String, ItemRecipe> itemRecipeMap = new HashMap<>();

   public Item() {
      super(ScriptType.Item);
   }

   public String getDisplayName() {
      return StringUtils.isNullOrEmpty(this.displayName) ? this.getFullName() : this.displayName;
   }

   public void setDisplayName(String displayName) {
      this.displayName = displayName;
   }

   public boolean isHidden() {
      return this.hidden;
   }

   public String getDisplayCategory() {
      return this.displayCategory;
   }

   public String getTooltip() {
      return this.tooltip;
   }

   public String getIcon() {
      return this.icon;
   }

   public void setIcon(String icon) {
      this.icon = icon;
   }

   public int getNoiseDuration() {
      return this.noiseDuration;
   }

   public Texture getNormalTexture() {
      return this.normalTexture;
   }

   public int getNumberOfPages() {
      return this.numberOfPages;
   }

   public float getActualWeight() {
      return this.actualWeight < 0.0F ? 0.0F : this.actualWeight;
   }

   public void setActualWeight(float actualWeight) {
      if (actualWeight < 0.0F) {
         actualWeight = 0.0F;
      }

      this.actualWeight = actualWeight;
   }

   public float getWeightWet() {
      return this.weightWet;
   }

   public void setWeightWet(float weight) {
      this.weightWet = weight;
   }

   public float getWeightEmpty() {
      return this.weightEmpty;
   }

   public void setWeightEmpty(float weight) {
      this.weightEmpty = weight;
   }

   public float getHungerChange() {
      return this.hungerChange;
   }

   public void setHungerChange(float hungerChange) {
      this.hungerChange = hungerChange;
   }

   public float getThirstChange() {
      return this.thirstChange;
   }

   public void setThirstChange(float thirstChange) {
      this.thirstChange = thirstChange;
   }

   @Deprecated
   public int getCount() {
      return this.count;
   }

   public void setCount(int count) {
      this.count = count;
   }

   public int getDaysFresh() {
      return this.daysFresh;
   }

   public void setDaysFresh(int daysFresh) {
      this.daysFresh = daysFresh;
   }

   public int getDaysTotallyRotten() {
      return this.daysTotallyRotten;
   }

   public void setDaysTotallyRotten(int daysTotallyRotten) {
      this.daysTotallyRotten = daysTotallyRotten;
   }

   public int getMinutesToCook() {
      return this.minutesToCook;
   }

   public void setMinutesToCook(int minutesToCook) {
      this.minutesToCook = minutesToCook;
   }

   public int getMinutesToBurn() {
      return this.minutesToBurn;
   }

   public void setMinutesToBurn(int minutesToBurn) {
      this.minutesToBurn = minutesToBurn;
   }

   public boolean isIsCookable() {
      return this.isCookable;
   }

   public void setIsCookable(boolean isCookable) {
      this.isCookable = isCookable;
   }

   public String getCookingSound() {
      return this.cookingSound;
   }

   public float getStressChange() {
      return this.stressChange;
   }

   public int getFoodSicknessChange() {
      return this.foodSicknessChange;
   }

   public void setStressChange(float stressChange) {
      this.stressChange = stressChange;
   }

   public float getBoredomChange() {
      return this.boredomChange;
   }

   public void setBoredomChange(float boredomChange) {
      this.boredomChange = boredomChange;
   }

   public float getUnhappyChange() {
      return this.unhappyChange;
   }

   public void setUnhappyChange(float unhappyChange) {
      this.unhappyChange = unhappyChange;
   }

   public boolean isAlwaysWelcomeGift() {
      return this.alwaysWelcomeGift;
   }

   public void setAlwaysWelcomeGift(boolean alwaysWelcomeGift) {
      this.alwaysWelcomeGift = alwaysWelcomeGift;
   }

   public boolean isRanged() {
      return this.ranged;
   }

   public void setRanged(boolean ranged) {
      this.ranged = ranged;
   }

   public float getMaxRange() {
      return this.maxRange;
   }

   public void setMaxRange(float maxRange) {
      this.maxRange = maxRange;
   }

   public float getMinAngle() {
      return this.minAngle;
   }

   public void setMinAngle(float minAngle) {
      this.minAngle = minAngle;
   }

   public float getMaxDamage() {
      return this.maxDamage;
   }

   public void setMaxDamage(float maxDamage) {
      this.maxDamage = maxDamage;
   }

   public float getMinDamage() {
      return this.minDamage;
   }

   public void setMinDamage(float minDamage) {
      this.minDamage = minDamage;
   }

   public float getMinimumSwingTime() {
      return this.minimumSwingTime;
   }

   public void setMinimumSwingTime(float minimumSwingTime) {
      this.minimumSwingTime = minimumSwingTime;
   }

   public String getSwingSound() {
      return this.swingSound;
   }

   public void setSwingSound(String swingSound) {
      this.swingSound = swingSound;
   }

   public String getWeaponSprite() {
      return this.weaponSprite;
   }

   public void setWeaponSprite(String weaponSprite) {
      this.weaponSprite = weaponSprite;
   }

   public boolean isAngleFalloff() {
      return this.angleFalloff;
   }

   public void setAngleFalloff(boolean angleFalloff) {
      this.angleFalloff = angleFalloff;
   }

   public int getSoundVolume() {
      return this.soundVolume;
   }

   public void setSoundVolume(int soundVolume) {
      this.soundVolume = soundVolume;
   }

   public float getToHitModifier() {
      return this.toHitModifier;
   }

   public void setToHitModifier(float toHitModifier) {
      this.toHitModifier = toHitModifier;
   }

   public int getSoundRadius() {
      return this.soundRadius;
   }

   public void setSoundRadius(int soundRadius) {
      this.soundRadius = soundRadius;
   }

   public float getOtherCharacterVolumeBoost() {
      return this.otherCharacterVolumeBoost;
   }

   public void setOtherCharacterVolumeBoost(float otherCharacterVolumeBoost) {
      this.otherCharacterVolumeBoost = otherCharacterVolumeBoost;
   }

   public boolean containsWeaponCategory(WeaponCategory weaponCategory) {
      return this.weaponCategories.contains(weaponCategory);
   }

   public Set<WeaponCategory> getWeaponCategories() {
      return this.weaponCategories;
   }

   public void setWeaponCategories(Set<WeaponCategory> categories) {
      this.weaponCategories.clear();
      this.weaponCategories.addAll(categories);
   }

   public Set<ItemTag> getTags() {
      return this.itemTags;
   }

   public String getImpactSound() {
      return this.impactSound;
   }

   public void setImpactSound(String impactSound) {
      this.impactSound = impactSound;
   }

   public float getSwingTime() {
      return this.swingTime;
   }

   public void setSwingTime(float swingTime) {
      this.swingTime = swingTime;
   }

   public boolean isKnockBackOnNoDeath() {
      return this.knockBackOnNoDeath;
   }

   public void setKnockBackOnNoDeath(boolean knockBackOnNoDeath) {
      this.knockBackOnNoDeath = knockBackOnNoDeath;
   }

   public boolean isSplatBloodOnNoDeath() {
      return this.splatBloodOnNoDeath;
   }

   public void setSplatBloodOnNoDeath(boolean splatBloodOnNoDeath) {
      this.splatBloodOnNoDeath = splatBloodOnNoDeath;
   }

   public float getSwingAmountBeforeImpact() {
      return this.swingAmountBeforeImpact;
   }

   public void setSwingAmountBeforeImpact(float swingAmountBeforeImpact) {
      this.swingAmountBeforeImpact = swingAmountBeforeImpact;
   }

   public AmmoType getAmmoType() {
      return this.ammoType;
   }

   public void setAmmoType(AmmoType ammoType) {
      this.ammoType = ammoType;
   }

   public int getDoorDamage() {
      return this.doorDamage;
   }

   public void setDoorDamage(int doorDamage) {
      this.doorDamage = doorDamage;
   }

   public int getConditionLowerChance() {
      return this.conditionLowerChance;
   }

   public void setConditionLowerChance(int conditionLowerChance) {
      this.conditionLowerChance = conditionLowerChance;
   }

   public int getConditionMax() {
      return this.conditionMax;
   }

   public void setConditionMax(int conditionMax) {
      this.conditionMax = conditionMax;
   }

   public boolean isCanBandage() {
      return this.canBandage;
   }

   public void setCanBandage(boolean canBandage) {
      this.canBandage = canBandage;
   }

   public boolean isCosmetic() {
      return this.cosmetic;
   }

   public String getName() {
      return this.name;
   }

   public String getModuleName() {
      return this.getModule().name;
   }

   public String getFullName() {
      return this.moduleDotType;
   }

   public void setName(String name) {
      this.name = name;
      this.moduleDotType = this.getModule().name + "." + name;
   }

   public int getMaxHitCount() {
      return this.maxHitCount;
   }

   public void setMaxHitCount(int maxHitCount) {
      this.maxHitCount = maxHitCount;
   }

   public boolean isUseSelf() {
      return this.useSelf;
   }

   public void setUseSelf(boolean useSelf) {
      this.useSelf = useSelf;
   }

   public boolean isOtherHandUse() {
      return this.otherHandUse;
   }

   public void setOtherHandUse(boolean otherHandUse) {
      this.otherHandUse = otherHandUse;
   }

   public ItemTag getOtherHandRequire() {
      return this.otherHandRequire;
   }

   public void setOtherHandRequire(ItemTag otherHandRequire) {
      this.otherHandRequire = otherHandRequire;
   }

   public String getPhysicsObject() {
      return this.physicsObject;
   }

   public void setPhysicsObject(String physicsObject) {
      this.physicsObject = physicsObject;
   }

   public String getSwingAnim() {
      return this.swingAnim;
   }

   public void setSwingAnim(String swingAnim) {
      this.swingAnim = swingAnim;
   }

   public float getWeaponWeight() {
      return this.weaponWeight;
   }

   public void setWeaponWeight(float weaponWeight) {
      this.weaponWeight = weaponWeight;
   }

   public float getEnduranceChange() {
      return this.enduranceChange;
   }

   public void setEnduranceChange(float enduranceChange) {
      this.enduranceChange = enduranceChange;
   }

   public String getBreakSound() {
      return this.breakSound;
   }

   public String getBulletOutSound() {
      return this.bulletOutSound;
   }

   public String getCloseSound() {
      return this.closeSound;
   }

   public String getClothingItem() {
      return this.clothingItem;
   }

   public void setClothingItemAsset(ClothingItem asset) {
      this.clothingItemAsset = asset;
   }

   public ClothingItem getClothingItemAsset() {
      return this.clothingItemAsset;
   }

   public ArrayList<String> getClothingItemExtra() {
      return this.clothingItemExtra;
   }

   public ArrayList<String> getClothingItemExtraOption() {
      return this.clothingItemExtraOption;
   }

   public String getFabricType() {
      return this.fabricType;
   }

   public ArrayList<String> getIconsForTexture() {
      return this.iconsForTexture;
   }

   public String getCustomEatSound() {
      return this.customEatSound;
   }

   public String getFillFromDispenserSound() {
      return this.fillFromDispenserSound;
   }

   public String getFillFromLakeSound() {
      return this.fillFromLakeSound;
   }

   public String getFillFromTapSound() {
      return this.fillFromTapSound;
   }

   public String getFillFromToiletSound() {
      return this.fillFromToiletSound;
   }

   public String getEatType() {
      return this.eatType;
   }

   public String getPourType() {
      return this.pourType;
   }

   public String getReadType() {
      return this.readType;
   }

   public String getDigType() {
      return this.digType;
   }

   public String getCountDownSound() {
      return this.countDownSound;
   }

   public String getBringToBearSound() {
      return this.bringToBearSound;
   }

   public String getAimReleaseSound() {
      return this.aimReleaseSound;
   }

   public String getEjectAmmoStartSound() {
      return this.ejectAmmoStartSound;
   }

   public String getEjectAmmoSound() {
      return this.ejectAmmoSound;
   }

   public String getEjectAmmoStopSound() {
      return this.ejectAmmoStopSound;
   }

   public String getInsertAmmoStartSound() {
      return this.insertAmmoStartSound;
   }

   public String getInsertAmmoSound() {
      return this.insertAmmoSound;
   }

   public String getInsertAmmoStopSound() {
      return this.insertAmmoStopSound;
   }

   public String getEquipSound() {
      return this.equipSound;
   }

   public String getUnequipSound() {
      return this.unequipSound;
   }

   public String getExplosionSound() {
      return this.explosionSound;
   }

   public String getStaticModel() {
      return this.staticModel;
   }

   public String getStaticModelOn() {
      return this.staticModelOn;
   }

   public String getWorldStaticModel() {
      return this.worldStaticModel;
   }

   public String getStaticModelException() {
      return this.hasTag(ItemTag.USE_WORLD_STATIC_MODEL) ? this.worldStaticModel : this.staticModel;
   }

   public String getOpenSound() {
      return this.openSound;
   }

   public String getPutInSound() {
      return this.putInSound;
   }

   public String getPlaceOneSound() {
      return this.placeOneSound;
   }

   public String getPlaceMultipleSound() {
      return this.placeMultipleSound;
   }

   public String getShellFallSound() {
      return this.shellFallSound;
   }

   public String getDropSound() {
      return this.dropSound;
   }

   public String getSoundByID(String id) {
      return this.soundMap == null ? null : this.soundMap.getOrDefault(id, null);
   }

   public String getSoundByID(SoundMapKey id) {
      return this.soundMap == null ? null : this.soundMap.getOrDefault(id.id(), null);
   }

   public String getSkillTrained() {
      return this.skillTrained;
   }

   public String getDoorHitSound() {
      return this.doorHitSound;
   }

   public void setDoorHitSound(String doorHitSound) {
      this.doorHitSound = doorHitSound;
   }

   public boolean isManuallyRemoveSpentRounds() {
      return this.manuallyRemoveSpentRounds;
   }

   public String getReplaceOnUse() {
      return this.replaceOnUse;
   }

   public void setReplaceOnUse(String replaceOnUse) {
      this.replaceOnUse = replaceOnUse;
   }

   public String getReplaceOnDeplete() {
      return this.replaceOnDeplete;
   }

   public void setReplaceOnDeplete(String replaceOnDeplete) {
      this.replaceOnDeplete = replaceOnDeplete;
   }

   public String getReplaceOnExtinguish() {
      return this.replaceOnExtinguish;
   }

   public void setReplaceOnExtinguish(String replaceOnExtinguish) {
      this.replaceOnExtinguish = replaceOnExtinguish;
   }

   public String getReplaceTypes() {
      return this.replaceTypes;
   }

   public HashMap<String, String> getReplaceTypesMap() {
      return this.replaceTypesMap;
   }

   public String getReplaceType(String key) {
      return this.replaceTypesMap == null ? null : this.replaceTypesMap.get(key);
   }

   public boolean hasReplaceType(String key) {
      return this.getReplaceType(key) != null;
   }

   public boolean isDangerousUncooked() {
      return this.dangerousUncooked;
   }

   public void setDangerousUncooked(boolean dangerousUncooked) {
      this.dangerousUncooked = dangerousUncooked;
   }

   public boolean isAlcoholic() {
      return this.alcoholic;
   }

   public void setAlcoholic(boolean alcoholic) {
      this.alcoholic = alcoholic;
   }

   public float getPushBackMod() {
      return this.pushBackMod;
   }

   public void setPushBackMod(float pushBackMod) {
      this.pushBackMod = pushBackMod;
   }

   public int getSplatNumber() {
      return this.splatNumber;
   }

   public void setSplatNumber(int splatNumber) {
      this.splatNumber = splatNumber;
   }

   public float getNPCSoundBoost() {
      return this.npcSoundBoost;
   }

   public void setNPCSoundBoost(float npcSoundBoost) {
      this.npcSoundBoost = npcSoundBoost;
   }

   public boolean isRangeFalloff() {
      return this.rangeFalloff;
   }

   public void setRangeFalloff(boolean rangeFalloff) {
      this.rangeFalloff = rangeFalloff;
   }

   public boolean isUseEndurance() {
      return this.useEndurance;
   }

   public void setUseEndurance(boolean useEndurance) {
      this.useEndurance = useEndurance;
   }

   public boolean isMultipleHitConditionAffected() {
      return this.multipleHitConditionAffected;
   }

   public void setMultipleHitConditionAffected(boolean multipleHitConditionAffected) {
      this.multipleHitConditionAffected = multipleHitConditionAffected;
   }

   public boolean isShareEndurance() {
      return this.shareEndurance;
   }

   public void setShareEndurance(boolean shareEndurance) {
      this.shareEndurance = shareEndurance;
   }

   public boolean isCanBarricade() {
      return this.canBarricade;
   }

   public void setCanBarricade(boolean canBarricade) {
      this.canBarricade = canBarricade;
   }

   public boolean isUseWhileEquipped() {
      return this.useWhileEquipped;
   }

   public void setUseWhileEquipped(boolean useWhileEquipped) {
      this.useWhileEquipped = useWhileEquipped;
   }

   public boolean isUseWhileUnequipped() {
      return this.useWhileUnequipped;
   }

   public void setUseWhileUnequipped(boolean useWhileUnequipped) {
      this.useWhileUnequipped = useWhileUnequipped;
   }

   public void setTicksPerEquipUse(int ticksPerEquipUse) {
      this.ticksPerEquipUse = ticksPerEquipUse;
   }

   public float getTicksPerEquipUse() {
      return this.ticksPerEquipUse;
   }

   public boolean isDisappearOnUse() {
      return this.disappearOnUse;
   }

   public boolean isKeepOnDeplete() {
      return !this.disappearOnUse;
   }

   public void setDisappearOnUse(boolean disappearOnUse) {
      this.disappearOnUse = disappearOnUse;
   }

   public void setKeepOnDeplete(boolean keepOnDeplete) {
      this.disappearOnUse = !keepOnDeplete;
   }

   public float getUseDelta() {
      return this.useDelta;
   }

   public void setUseDelta(float useDelta) {
      this.useDelta = useDelta;
   }

   public boolean isAlwaysKnockdown() {
      return this.alwaysKnockdown;
   }

   public void setAlwaysKnockdown(boolean alwaysKnockdown) {
      this.alwaysKnockdown = alwaysKnockdown;
   }

   public float getEnduranceMod() {
      return this.enduranceMod;
   }

   public void setEnduranceMod(float enduranceMod) {
      this.enduranceMod = enduranceMod;
   }

   public float getKnockdownMod() {
      return this.knockdownMod;
   }

   public void setKnockdownMod(float knockdownMod) {
      this.knockdownMod = knockdownMod;
   }

   public boolean isCantAttackWithLowestEndurance() {
      return this.cantAttackWithLowestEndurance;
   }

   public void setCantAttackWithLowestEndurance(boolean cantAttackWithLowestEndurance) {
      this.cantAttackWithLowestEndurance = cantAttackWithLowestEndurance;
   }

   public ItemBodyLocation getBodyLocation() {
      return this.bodyLocation;
   }

   public void setBodyLocation(ItemBodyLocation bodyLocation) {
      this.bodyLocation = bodyLocation;
   }

   public boolean isBodyLocation(ItemBodyLocation bodyLocation) {
      return this.getBodyLocation() == bodyLocation;
   }

   public Stack<String> getPaletteChoices() {
      return this.paletteChoices;
   }

   public void setPaletteChoices(Stack<String> paletteChoices) {
      this.paletteChoices = paletteChoices;
   }

   public String getSpriteName() {
      return this.spriteName;
   }

   public void setSpriteName(String spriteName) {
      this.spriteName = spriteName;
   }

   public String getPalettesStart() {
      return this.palettesStart;
   }

   public void setPalettesStart(String palettesStart) {
      this.palettesStart = palettesStart;
   }

   public ItemType getItemType() {
      return this.itemType;
   }

   public void setItemType(ItemType itemType) {
      this.itemType = itemType;
   }

   public boolean isItemType(ItemType itemType) {
      return this.itemType == itemType;
   }

   public String getMapID() {
      return this.map;
   }

   public ArrayList<String> getEvolvedRecipe() {
      return this.evolvedRecipe;
   }

   public void InitLoadPP(String name) {
      ScriptManager scriptMgr = ScriptManager.instance;
      this.fileName = scriptMgr.currentFileName;
      this.name = name;
      this.moduleDotType = this.getModule().name + "." + name;
      super.InitLoadPP(name);
      int id = idMax++;
      netIdToItem.put(id, this.moduleDotType);
      netItemToId.put(this.moduleDotType, id);
   }

   public void Load(String name, String totalFile) throws Exception {
      this.name = name;
      this.moduleDotType = this.getModule().name + "." + name;
      ScriptParser.Block block = ScriptParser.parse(totalFile);
      block = block.children.get(0);
      this.LoadCommonBlock(block);

      for (ScriptParser.BlockElement element : block.elements) {
         if (element.asValue() != null) {
            try {
               String s = element.asValue().string;
               if (!s.trim().isEmpty()) {
                  String[] p = s.split("=");
                  String param = p[0].trim();
                  String val = p[1].trim();
                  this.DoParam(param, val);
               }
            } catch (Exception e) {
               DebugType.General.printException(e, LogSeverity.Error);
               throw new InvalidParameterException(e.getMessage());
            }
         } else {
            ScriptParser.Block child = element.asBlock();
            if (child.type != null && child.type.equalsIgnoreCase("component")) {
               this.LoadComponentBlock(child);
            }
         }
      }

      if (!StringUtils.isNullOrWhitespace(this.replaceInPrimaryHand)) {
         String[] ss = this.replaceInPrimaryHand.trim().split("\\s+");
         if (ss.length == 2) {
            this.replacePrimaryHand = new ItemReplacement();
            this.replacePrimaryHand.clothingItemName = ss[0].trim();
            this.replacePrimaryHand.maskVariableValue = ss[1].trim();
            this.replacePrimaryHand.maskVariableName = "RightHandMask";
         }
      }

      if (!StringUtils.isNullOrWhitespace(this.replaceInSecondHand)) {
         String[] ss = this.replaceInSecondHand.trim().split("\\s+");
         if (ss.length == 2) {
            this.replaceSecondHand = new ItemReplacement();
            this.replaceSecondHand.clothingItemName = ss[0].trim();
            this.replaceSecondHand.maskVariableValue = ss[1].trim();
            this.replaceSecondHand.maskVariableName = "LeftHandMask";
         }
      }

      if (!StringUtils.isNullOrWhitespace(this.primaryAnimMask)) {
         this.replacePrimaryHand = new ItemReplacement();
         this.replacePrimaryHand.maskVariableValue = this.primaryAnimMask;
         this.replacePrimaryHand.maskVariableName = "RightHandMask";
         this.replacePrimaryHand.attachment = this.primaryAnimMaskAttachment;
      }

      if (!StringUtils.isNullOrWhitespace(this.secondaryAnimMask)) {
         this.replaceSecondHand = new ItemReplacement();
         this.replaceSecondHand.maskVariableValue = this.secondaryAnimMask;
         this.replaceSecondHand.maskVariableName = "LeftHandMask";
         this.replaceSecondHand.attachment = this.secondaryAnimMaskAttachment;
      }

      if (this.normalTexture == null && this.iconsForTexture != null && !this.iconsForTexture.isEmpty()) {
         this.normalTexture = Texture.trygetTexture("Item_" + this.iconsForTexture.get(0));
      }
   }

   public InventoryItem InstanceItem(String param) {
      return this.InstanceItem(param, true);
   }

   public InventoryItem InstanceItem(String param, boolean isFirstTimeCreated) {
      InventoryItem item = null;
      if (this.itemType == ItemType.KEY) {
         item = new Key(this.getModule().name, this.displayName, this.name, "Item_" + this.icon);
         ((Key)item).setDigitalPadlock(this.digitalPadlock);
         ((Key)item).setPadlock(this.padlock);
         if (((Key)item).isPadlock()) {
            ((Key)item).setNumberOfKey(2);
            item.setKeyId(Rand.Next(10000000));
         }
      } else if (this.itemType == ItemType.WEAPON_PART) {
         item = new WeaponPart(this.getModule().name, this.displayName, this.name, "Item_" + this.icon);
         WeaponPart wp = (WeaponPart)item;
         wp.setDamage(this.damageModifier);
         wp.setClipSize(this.clipSizeModifier);
         wp.setMaxRange(this.maxRangeModifier);
         wp.setMinSightRange(this.minSightRange);
         wp.setMaxSightRange(this.maxSightRange);
         wp.setRecoilDelay(this.recoilDelayModifier);
         wp.setMountOn(this.mountOn);
         wp.setPartType(this.partType);
         wp.setCanAttachCallback(this.canAttachCallback);
         wp.setCanDetachCallback(this.canDetachCallback);
         wp.setOnAttachCallback(this.onAttachCallback);
         wp.setOnDetachCallback(this.onDetachCallback);
         wp.setReloadTime(this.reloadTimeModifier);
         wp.setAimingTime(this.aimingTimeModifier);
         wp.setHitChance(this.hitChanceModifier);
         wp.setSpreadModifier(this.projectileSpreadModifier);
         wp.setWeightModifier(this.weightModifier);
         wp.setUseDelta(this.useDelta);
      } else if (this.itemType == ItemType.CONTAINER) {
         item = new InventoryContainer(this.getModule().name, this.displayName, this.name, "Item_" + this.icon);
         InventoryContainer invCont = (InventoryContainer)item;
         invCont.setItemCapacity(this.capacity);
         invCont.setCapacity(this.capacity);
         invCont.setWeightReduction(this.weightReduction);
         invCont.setCanBeEquipped(this.canBeEquipped);
         invCont.getInventory().setPutSound(this.putInSound);
         invCont.getInventory().setCloseSound(this.closeSound);
         invCont.getInventory().setOpenSound(this.openSound);
         invCont.getInventory().setOnlyAcceptCategory(this.onlyAcceptCategory);
         invCont.getInventory().setAcceptItemFunction(this.acceptItemFunction);
      } else if (this.itemType == ItemType.FOOD) {
         item = new Food(this.getModule().name, this.displayName, this.name, this);
         Food food = (Food)item;
         food.poison = this.poison;
         food.setPoisonLevelForRecipe(this.poisonDetectionLevel);
         food.setFoodType(this.foodType);
         food.setPoisonPower(this.poisonPower);
         food.setUseForPoison(this.useForPoison);
         food.setThirstChange(this.thirstChange / 100.0F);
         food.setHungChange(this.hungerChange / 100.0F);
         food.setBaseHunger(this.hungerChange / 100.0F);
         food.setEndChange(this.enduranceChange / 100.0F);
         food.setOffAge(this.daysFresh);
         food.setOffAgeMax(this.daysTotallyRotten);
         food.setIsCookable(this.isCookable);
         food.setMinutesToCook(this.minutesToCook);
         food.setMinutesToBurn(this.minutesToBurn);
         food.setbDangerousUncooked(this.dangerousUncooked);
         food.setReplaceOnUse(this.replaceOnUse);
         food.setReplaceOnCooked(this.replaceOnCooked);
         food.setSpice(this.spice);
         food.setRemoveNegativeEffectOnCooked(this.removeNegativeEffectOnCooked);
         food.setCustomEatSound(this.customEatSound);
         food.setOnCooked(this.onCooked);
         food.setFluReduction(this.fluReduction);
         food.setFoodSicknessChange(this.foodSicknessChange);
         food.setPainReduction(this.painReduction);
         food.setHerbalistType(this.herbalistType);
         food.setCarbohydrates(this.carbohydrates);
         food.setLipids(this.lipids);
         food.setProteins(this.proteins);
         food.setCalories(this.calories);
         food.setPackaged(this.packaged);
         food.setCanBeFrozen(!this.cantBeFrozen);
         food.setReplaceOnRotten(this.replaceOnRotten);
         food.setOnEat(this.onEat);
         food.setBadInMicrowave(this.badInMicrowave);
         food.setGoodHot(this.goodHot);
         food.setBadCold(this.badCold);
      } else if (this.itemType == ItemType.LITERATURE) {
         item = new Literature(this.getModule().name, this.displayName, this.name, this);
         Literature literature = (Literature)item;
         literature.setReplaceOnUse(this.replaceOnUse);
         literature.setNumberOfPages(this.numberOfPages);
         literature.setAlreadyReadPages(0);
         literature.setSkillTrained(this.skillTrained);
         literature.setLvlSkillTrained(this.lvlSkillTrained);
         literature.setNumLevelsTrained(this.numLevelsTrained);
         literature.setCanBeWrite(this.canBeWrite);
         literature.setPageToWrite(this.pageToWrite);
      } else if (this.itemType == ItemType.ALARM_CLOCK) {
         item = new AlarmClock(this.getModule().name, this.displayName, this.name, this);
         AlarmClock alarm = (AlarmClock)item;
         alarm.setAlarmSound(this.alarmSound);
         alarm.setSoundRadius(this.soundRadius);
      } else if (this.itemType == ItemType.ALARM_CLOCK_CLOTHING) {
         String col = "";
         String pal = null;
         if (!this.paletteChoices.isEmpty() || param != null) {
            int ran = Rand.Next(this.paletteChoices.size());
            pal = this.paletteChoices.get(ran);
            if (param != null) {
               pal = param;
            }

            col = "_" + pal.replace(this.palettesStart, "");
         }

         item = new AlarmClockClothing(this.getModule().name, this.displayName, this.name, "Item_" + this.icon.replace(".png", "") + col, pal, this.spriteName);
         AlarmClockClothing clothing = (AlarmClockClothing)item;
         clothing.setTemperature(this.temperature);
         clothing.setInsulation(this.insulation);
         clothing.setConditionLowerChance(this.conditionLowerChance);
         clothing.setStompPower(this.stompPower);
         clothing.setRunSpeedModifier(this.runSpeedModifier);
         clothing.setCombatSpeedModifier(this.combatSpeedModifier);
         clothing.setRemoveOnBroken(this.removeOnBroken);
         clothing.setCanHaveHoles(this.canHaveHoles);
         clothing.setWeightWet(this.weightWet);
         clothing.setBiteDefense(this.biteDefense);
         clothing.setBulletDefense(this.bulletDefense);
         clothing.setNeckProtectionModifier(this.neckProtectionModifier);
         clothing.setScratchDefense(this.scratchDefense);
         clothing.setChanceToFall(this.chanceToFall);
         clothing.setWindresistance(this.windresist);
         clothing.setWaterResistance(this.waterresist);
         clothing.setAlarmSound(this.alarmSound);
         clothing.setSoundRadius(this.soundRadius);
      } else if (this.itemType == ItemType.WEAPON) {
         item = new HandWeapon(this.getModule().name, this.displayName, this.name, this);
         HandWeapon weapon = (HandWeapon)item;
         weapon.setMultipleHitConditionAffected(this.multipleHitConditionAffected);
         weapon.setConditionLowerChance(this.conditionLowerChance);
         weapon.splatSize = this.splatSize;
         this.aimingMod = weapon.getAimingMod();
         weapon.setMinDamage(this.minDamage);
         weapon.setMaxDamage(this.maxDamage);
         weapon.setBaseSpeed(this.baseSpeed);
         weapon.setPhysicsObject(this.physicsObject);
         weapon.setOtherHandRequire(this.otherHandRequire);
         weapon.setOtherHandUse(this.otherHandUse);
         weapon.setMaxRange(this.maxRange);
         weapon.setMinRange(this.minRange);
         weapon.setMinSightRange(this.minSightRange);
         weapon.setMaxSightRange(this.maxSightRange);
         weapon.setShareEndurance(this.shareEndurance);
         weapon.setKnockdownMod(this.knockdownMod);
         weapon.isAimedFirearm = this.isAimedFirearm;
         weapon.runAnim = this.runAnim;
         weapon.idleAnim = this.idleAnim;
         weapon.hitAngleMod = (float)Math.toRadians(this.hitAngleMod);
         weapon.isAimedHandWeapon = this.isAimedHandWeapon;
         weapon.setCantAttackWithLowestEndurance(this.cantAttackWithLowestEndurance);
         weapon.setAlwaysKnockdown(this.alwaysKnockdown);
         weapon.setEnduranceMod(this.enduranceMod);
         weapon.setUseSelf(this.useSelf);
         weapon.setMaxHitCount(this.maxHitCount);
         weapon.setMinimumSwingTime(this.minimumSwingTime);
         weapon.setSwingTime(this.swingTime);
         weapon.setDoSwingBeforeImpact(this.swingAmountBeforeImpact);
         weapon.setMinAngle(this.minAngle);
         weapon.setDoorDamage(this.doorDamage);
         weapon.setTreeDamage(this.treeDamage);
         weapon.setDoorHitSound(this.doorHitSound);
         weapon.setHitFloorSound(this.hitFloorSound);
         weapon.setZombieHitSound(this.hitSound);
         weapon.setPushBackMod(this.pushBackMod);
         weapon.setWeight(this.weaponWeight);
         weapon.setImpactSound(this.impactSound);
         weapon.setSplatNumber(this.splatNumber);
         weapon.setKnockBackOnNoDeath(this.knockBackOnNoDeath);
         weapon.setSplatBloodOnNoDeath(this.splatBloodOnNoDeath);
         weapon.setSwingSound(this.swingSound);
         weapon.setBulletOutSound(this.bulletOutSound);
         weapon.setShellFallSound(this.shellFallSound);
         weapon.setAngleFalloff(this.angleFalloff);
         weapon.setSoundVolume(this.soundVolume);
         weapon.setSoundRadius(this.soundRadius);
         weapon.setToHitModifier(this.toHitModifier);
         weapon.setOtherBoost(this.npcSoundBoost);
         weapon.setRanged(this.ranged);
         weapon.setRangeFalloff(this.rangeFalloff);
         weapon.setUseEndurance(this.useEndurance);
         weapon.setCriticalChance(this.criticalChance);
         weapon.setCriticalDamageMultiplier(this.critDmgMultiplier);
         weapon.setCanBarracade(this.canBarricade);
         weapon.setWeaponSprite(this.weaponSprite);
         weapon.setOriginalWeaponSprite(this.weaponSprite);
         weapon.setSubCategory(this.subCategory);
         weapon.setWeaponCategories(this.weaponCategories);
         weapon.setSoundGain(this.soundGain);
         weapon.setAimingPerkCritModifier(this.aimingPerkCritModifier);
         weapon.setAimingPerkRangeModifier(this.aimingPerkRangeModifier);
         weapon.setAimingPerkHitChanceModifier(this.aimingPerkHitChanceModifier);
         weapon.setHitChance(this.hitChance);
         weapon.setRecoilDelay(this.recoilDelay);
         weapon.setAimingPerkMinAngleModifier(this.aimingPerkMinAngleModifier);
         weapon.setPiercingBullets(this.piercingBullets);
         weapon.setClipSize(this.clipSize);
         weapon.setReloadTime(this.reloadTime);
         weapon.setAimingTime(this.aimingTime);
         weapon.setTriggerExplosionTimer(this.triggerExplosionTimer);
         weapon.setSensorRange(this.sensorRange);
         weapon.setWeaponLength(this.weaponLength);
         weapon.setPlacedSprite(this.placedSprite);
         weapon.setExplosionTimer(this.explosionTimer);
         weapon.setExplosionDuration(this.explosionDuration);
         weapon.setCanBePlaced(this.canBePlaced);
         weapon.setCanBeReused(this.canBeReused);
         weapon.setExplosionRange(this.explosionRange);
         weapon.setExplosionPower(this.explosionPower);
         weapon.setFireRange(this.fireRange);
         weapon.setFireStartingEnergy(this.fireStartingEnergy);
         weapon.setFireStartingChance(this.fireStartingChance);
         weapon.setSmokeRange(this.smokeRange);
         weapon.setNoiseRange(this.noiseRange);
         weapon.setExtraDamage(this.extraDamage);
         weapon.setAmmoBox(this.ammoBox);
         weapon.setRackSound(this.rackSound);
         weapon.setClickSound(this.clickSound);
         weapon.setMagazineType(this.magazineType);
         weapon.setWeaponReloadType(WeaponReloadType.fromValue(this.weaponReloadType));
         weapon.setInsertAllBulletsReload(this.insertAllBulletsReload);
         weapon.setRackAfterShoot(this.rackAfterShoot);
         weapon.setJamGunChance(this.jamGunChance);
         weapon.setModelWeaponPart(this.modelWeaponPart);
         weapon.setHaveChamber(this.haveChamber);
         weapon.setDamageCategory(this.damageCategory);
         weapon.setDamageMakeHole(this.damageMakeHole);
         weapon.setFireMode(this.fireMode);
         weapon.setFireModePossibilities(this.fireModePossibilities);
         if (this.cyclicRateMultiplier > 0.0F) {
            weapon.setCyclicRateMultiplier(this.cyclicRateMultiplier);
         }

         weapon.setWeaponSpritesByIndex(this.weaponSpritesByIndex);
         weapon.setProjectileCount(this.projectileCount);
         weapon.setProjectileSpread(this.projectileSpread);
         weapon.setProjectileWeightCenter(this.projectileWeightCenter);
         weapon.setMuzzleFlashModelKey(this.muzzleFlashModelKey);
      } else if (this.itemType == ItemType.NORMAL) {
         item = new ComboItem(this.getModule().name, this.displayName, this.name, this);
      } else if (this.itemType == ItemType.CLOTHING) {
         String col = "";
         String pal = null;
         if (!this.paletteChoices.isEmpty() || param != null) {
            int ran = Rand.Next(this.paletteChoices.size());
            pal = this.paletteChoices.get(ran);
            if (param != null) {
               pal = param;
            }

            col = "_" + pal.replace(this.palettesStart, "");
         }

         item = new Clothing(this.getModule().name, this.displayName, this.name, "Item_" + this.icon.replace(".png", "") + col, pal, this.spriteName);
         Clothing clothing = (Clothing)item;
         clothing.setTemperature(this.temperature);
         clothing.setInsulation(this.insulation);
         clothing.setConditionLowerChance(this.conditionLowerChance);
         clothing.setStompPower(this.stompPower);
         clothing.setRunSpeedModifier(this.runSpeedModifier);
         clothing.setCombatSpeedModifier(this.combatSpeedModifier);
         clothing.setRemoveOnBroken(this.removeOnBroken);
         clothing.setCanHaveHoles(this.canHaveHoles);
         clothing.setWeightWet(this.weightWet);
         clothing.setBiteDefense(this.biteDefense);
         clothing.setBulletDefense(this.bulletDefense);
         clothing.setNeckProtectionModifier(this.neckProtectionModifier);
         clothing.setScratchDefense(this.scratchDefense);
         clothing.setChanceToFall(this.chanceToFall);
         clothing.setWindresistance(this.windresist);
         clothing.setWaterResistance(this.waterresist);
      } else if (this.itemType == ItemType.DRAINABLE) {
         item = new DrainableComboItem(this.getModule().name, this.displayName, this.name, this);
         DrainableComboItem drain = (DrainableComboItem)item;
         drain.setUseWhileEquiped(this.useWhileEquipped);
         drain.setUseWhileUnequiped(this.useWhileUnequipped);
         drain.setTicksPerEquipUse(this.ticksPerEquipUse);
         drain.setUseDelta(this.useDelta);
         drain.setReplaceOnDeplete(this.replaceOnDeplete);
         drain.setIsCookable(this.isCookable);
         drain.setReplaceOnCooked(this.replaceOnCooked);
         drain.setMinutesToCook(this.minutesToCook);
         drain.setOnCooked(this.onCooked);
         drain.setCanConsolidate(!this.cantBeConsolided);
         drain.setWeightEmpty(this.weightEmpty);
         drain.setOnEat(this.onEat);
      } else if (this.itemType == ItemType.RADIO) {
         item = new Radio(this.getModule().name, this.displayName, this.name, "Item_" + this.icon);
         Radio radio = (Radio)item;
         radio.setCanBeEquipped(this.canBeEquipped);
         DeviceData data = radio.getDeviceData();
         if (data != null) {
            if (this.displayName != null) {
               data.setDeviceName(this.displayName);
            }

            data.setIsTwoWay(this.twoWay);
            data.setTransmitRange(this.transmitRange);
            data.setMicRange(this.micRange);
            data.setBaseVolumeRange(this.baseVolumeRange);
            data.setIsPortable(this.isPortable);
            data.setIsTelevision(this.isTelevision);
            data.setMinChannelRange(this.minChannel);
            data.setMaxChannelRange(this.maxChannel);
            data.setIsBatteryPowered(this.usesBattery);
            data.setIsHighTier(this.isHighTier);
            data.setUseDelta(this.useDelta);
            data.setMediaType(this.acceptMediaType);
            data.setNoTransmit(this.noTransmit);
            data.generatePresets();
            data.setRandomChannel();
         }

         if (!StringUtils.isNullOrWhitespace(this.worldObjectSprite) && !radio.ReadFromWorldSprite(this.worldObjectSprite)) {
            DebugLog.log("Item -> Radio item = " + (this.moduleDotType != null ? this.moduleDotType : "unknown"));
         }
      } else if (this.itemType == ItemType.MOVEABLE) {
         item = new Moveable(this.getModule().name, this.displayName, this.name, this);
         Moveable moveable = (Moveable)item;
         moveable.ReadFromWorldSprite(this.worldObjectSprite);
         this.actualWeight = moveable.getActualWeight();
      } else if (this.itemType == ItemType.MAP) {
         MapItem mapItem = new MapItem(this.getModule().name, this.displayName, this.name, this);
         if (StringUtils.isNullOrWhitespace(this.map)) {
            mapItem.setMapID(MapDefinitions.getInstance().pickRandom());
         } else {
            mapItem.setMapID(this.map);
         }

         item = mapItem;
      } else if (this.itemType == ItemType.ANIMAL) {
         item = new AnimalInventoryItem(this.getModule().name, this.displayName, this.name, this);
      }

      if (this.colorRed < 255 || this.colorGreen < 255 || this.colorBlue < 255) {
         item.setColor(new Color(this.colorRed / 255.0F, this.colorGreen / 255.0F, this.colorBlue / 255.0F));
      }

      item.setAlcoholPower(this.alcoholPower);
      item.setConditionMax(this.conditionMax);
      item.setCondition(this.conditionMax, false);
      item.setCanBeActivated(this.activatedItem);
      item.setLightStrength(this.lightStrength);
      item.setTorchCone(this.torchCone);
      item.setLightDistance(this.lightDistance);
      item.setActualWeight(this.actualWeight);
      item.setWeight(this.actualWeight);
      item.setScriptItem(this);
      item.setBoredomChange(this.boredomChange);
      item.setStressChange(this.stressChange / 100.0F);
      item.setFoodSicknessChange(this.foodSicknessChange);
      item.setUnhappyChange(this.unhappyChange);
      item.setReplaceOnUseOn(this.replaceOnUseOn);
      item.setRequireInHandOrInventory(this.requireInHandOrInventory);
      item.setAttachmentsProvided(this.attachmentsProvided);
      item.setAttachmentReplacement(this.attachmentReplacement);
      item.canStack = this.canStack;
      item.copyModData(this.defaultModData);
      item.setCount(this.count);
      item.setFatigueChange(this.fatigueChange / 100.0F);
      item.setTooltip(this.tooltip);
      item.setDisplayCategory(this.displayCategory);
      item.setAlcoholic(this.alcoholic);
      item.requiresEquippedBothHands = this.requiresEquippedBothHands;
      item.setBreakSound(this.breakSound);
      item.setReplaceOnUse(this.replaceOnUse);
      item.setBandagePower(this.bandagePower);
      item.setReduceInfectionPower(this.reduceInfectionPower);
      item.setCanBeRemote(this.canBeRemote);
      item.setRemoteController(this.remoteController);
      item.setRemoteRange(this.remoteRange);
      item.setCountDownSound(this.countDownSound);
      item.setExplosionSound(this.explosionSound);
      item.setColorRed(this.colorRed / 255.0F);
      item.setColorGreen(this.colorGreen / 255.0F);
      item.setColorBlue(this.colorBlue / 255.0F);
      item.setEvolvedRecipeName(this.evolvedRecipeName);
      item.setMetalValue(this.metalValue);
      item.setWet(this.isWet);
      item.setWetCooldown(this.wetCooldown);
      item.setItemWhenDry(this.itemWhenDry);
      item.setItemCapacity(this.itemCapacity);
      item.setMaxCapacity(this.maxCapacity);
      item.setBrakeForce(this.brakeForce);
      item.setDurability(this.durability);
      item.setChanceToSpawnDamaged(this.chanceToSpawnDamaged);
      item.setConditionLowerNormal(this.conditionLowerNormal);
      item.setConditionLowerOffroad(this.conditionLowerOffroad);
      item.setWheelFriction(this.wheelFriction);
      item.setSuspensionCompression(this.suspensionCompression);
      item.setEngineLoudness(this.engineLoudness);
      item.setSuspensionDamping(this.suspensionDamping);
      item.setInverseCoughProbability(this.inverseCoughProbability);
      item.setInverseCoughProbabilitySmoker(this.inverseCoughProbabilitySmoker);
      if (this.customContextMenu != null) {
         item.setCustomMenuOption(Translator.getText("ContextMenu_" + this.customContextMenu, new Object[0]));
      }

      if (this.iconsForTexture != null && !this.iconsForTexture.isEmpty()) {
         item.setIconsForTexture(this.iconsForTexture);
      }

      item.setBloodClothingType(this.bloodClothingType);
      item.closeKillMove = this.closeKillMove;
      item.setAmmoType(this.ammoType);
      item.setMaxAmmo(this.maxAmmo);
      item.setGunType(this.gunType);
      item.setAttachmentType(this.attachmentType);
      if (this.iconColorMask != null) {
         item.setTextureColorMask("Item_" + this.iconColorMask);
      }

      if (this.iconFluidMask != null) {
         item.setTextureFluidMask("Item_" + this.iconFluidMask);
      }

      if (this.staticModelsByIndex != null && !this.staticModelsByIndex.isEmpty()) {
         item.setStaticModelsByIndex(this.staticModelsByIndex);
      }

      if (this.worldStaticModelsByIndex != null && !this.worldStaticModelsByIndex.isEmpty()) {
         item.setWorldStaticModelsByIndex(this.worldStaticModelsByIndex);
      }

      GameEntityFactory.CreateInventoryItemEntity(item, this, isFirstTimeCreated);
      long seed = OutfitRNG.getSeed();
      OutfitRNG.setSeed(Rand.Next(Integer.MAX_VALUE));
      item.synchWithVisual();
      OutfitRNG.setSeed(seed);
      item.setRegistry_id(this);
      ItemConfigurator.ConfigureItemOnCreate(item);
      Thread thread = Thread.currentThread();
      if ((thread == GameWindow.gameThread || thread == GameLoadingState.loader || thread == GameServer.mainThread) && !item.isInitialised()) {
         item.initialiseItem();
      }

      return item;
   }

   public void DoParam(String str) {
      if (!str.trim().isEmpty()) {
         try {
            String[] p = str.split("=");
            String param = p[0].trim();
            String val = p[1].trim();
            this.DoParam(param, val);
         } catch (Exception e) {
            DebugType.General.printException(e, LogSeverity.Error);
            throw new InvalidParameterException(e.getMessage());
         }
      }
   }

   public void DoParam(String param, String val) {
      if (pzopt.Config.ITEM_PARAM_SWITCH && pzopt.Overrides.enabled()) {
         this.pzoptDoParam(param, val); // pzopt: switch instead of the 361-comparison chain (docs/plan-instant-load.md B3)
         return;
      }

      try {
         AttributeType attributeType = Attribute.TypeFromName(param.trim());
         if (attributeType != null) {
            this.LoadAttribute(param.trim(), val.trim());
            if (attributeType == Attribute.HeadCondition) {
               this.LoadAttribute("TimesHeadRepaired", "0");
            }
         } else if (param.trim().equalsIgnoreCase("BodyLocation")) {
            String bodyLocation = val.trim();
            this.bodyLocation = ItemBodyLocation.get(ResourceLocation.of(bodyLocation));
         } else if (param.trim().equalsIgnoreCase("Palettes")) {
            String[] split = val.split("/");

            for (int n = 0; n < split.length; n++) {
               this.paletteChoices.add(split[n].trim());
            }
         } else if (param.trim().equalsIgnoreCase("HitSound")) {
            this.hitSound = val.trim();
            if (this.hitSound.equals("null")) {
               this.hitSound = null;
            }
         } else if (param.trim().equalsIgnoreCase("HitFloorSound")) {
            this.hitFloorSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("PalettesStart")) {
            this.palettesStart = val.trim();
         } else if (param.trim().equalsIgnoreCase("DisplayName")) {
            this.displayName = val.trim();
         } else if (param.trim().equalsIgnoreCase("MetalValue")) {
            this.metalValue = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("SpriteName")) {
            this.spriteName = val.trim();
         } else if (param.trim().equalsIgnoreCase("ItemType")) {
            this.itemType = ItemType.get(ResourceLocation.of(val.trim()));
         } else if (param.trim().equalsIgnoreCase("SplatSize")) {
            this.splatSize = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("CanStoreWater")) {
            this.canStoreWater = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("Poison")) {
            this.poison = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("FoodType")) {
            this.foodType = val.trim();
         } else if (param.trim().equalsIgnoreCase("PoisonDetectionLevel")) {
            this.poisonDetectionLevel = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("PoisonPower")) {
            this.poisonPower = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("UseForPoison")) {
            this.useForPoison = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("SwingAnim")) {
            this.swingAnim = val;
         } else if (param.trim().equalsIgnoreCase("Icon")) {
            this.icon = val;
            this.itemName = "Item_" + this.icon;
            this.normalTexture = Texture.trygetTexture(this.itemName);
            if (this.normalTexture == null) {
               this.normalTexture = Texture.getSharedTexture("media/inventory/Question_On.png");
            }

            this.worldTextureName = this.itemName.replace("Item_", "media/inventory/world/WItem_");
            this.worldTextureName = this.worldTextureName + ".png";
            this.worldTexture = Texture.getSharedTexture(this.worldTextureName);
            if (this.itemType == ItemType.FOOD) {
               Texture texRotten = Texture.trygetTexture(this.itemName + "Rotten");
               String wTexRotten = this.worldTextureName.replace(".png", "Rotten.png");
               if (texRotten == null) {
                  texRotten = Texture.trygetTexture(this.itemName + "Spoiled");
                  wTexRotten = wTexRotten.replace("Rotten.png", "Spoiled.png");
               }

               if (texRotten == null) {
                  texRotten = Texture.trygetTexture(this.itemName + "_Rotten");
                  wTexRotten = wTexRotten.replace("Rotten.png", "_Rotten.png");
               }

               this.specialWorldTextureNames.add(wTexRotten);
               this.specialTextures.add(texRotten);
               this.specialTextures.add(Texture.trygetTexture(this.itemName + "Cooked"));
               this.specialWorldTextureNames.add(this.worldTextureName.replace(".png", "Cooked.png"));
               Texture texOverdone = Texture.trygetTexture(this.itemName + "Overdone");
               String wTexOverdone = this.worldTextureName.replace(".png", "Overdone.png");
               if (texOverdone == null) {
                  texOverdone = Texture.trygetTexture(this.itemName + "Burnt");
                  wTexOverdone = wTexOverdone.replace("Overdone.png", "Burnt.png");
               }

               if (texOverdone == null) {
                  texOverdone = Texture.trygetTexture(this.itemName + "_Burnt");
                  wTexOverdone = wTexOverdone.replace("Overdone.png", "_Burnt.png");
               }

               this.specialTextures.add(texOverdone);
               this.specialWorldTextureNames.add(wTexOverdone);
            }
         } else if (param.trim().equalsIgnoreCase("UseWorldItem")) {
            this.useWorldItem = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("Medical")) {
            this.medical = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("CannedFood")) {
            this.cannedFood = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("MechanicsItem")) {
            this.mechanicsItem = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("SurvivalGear")) {
            this.survivalGear = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("ScaleWorldIcon")) {
            this.scaleWorldIcon = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("DoorHitSound")) {
            this.doorHitSound = val;
         } else if (param.trim().equalsIgnoreCase("Weight")) {
            this.actualWeight = Float.parseFloat(val);
            if (this.actualWeight < 0.0F) {
               this.actualWeight = 0.0F;
            }
         } else if (param.trim().equalsIgnoreCase("WeightWet")) {
            this.weightWet = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("WeightEmpty")) {
            this.weightEmpty = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("HungerChange")) {
            this.hungerChange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ThirstChange")) {
            this.thirstChange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("FatigueChange")) {
            this.fatigueChange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("EnduranceChange")) {
            this.enduranceChange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("Hidden")) {
            this.hidden = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("CriticalChance")) {
            this.criticalChance = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("critDmgMultiplier")) {
            this.critDmgMultiplier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("DaysFresh")) {
            this.daysFresh = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("MilkReplaceItem")) {
            this.milkReplaceItem = val;
         } else if (param.trim().equalsIgnoreCase("MaxMilk")) {
            this.maxMilk = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("AnimalFeedType")) {
            this.animalFeedType = val;
         } else if (param.trim().equalsIgnoreCase("DaysTotallyRotten")) {
            this.daysTotallyRotten = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("IsCookable")) {
            this.isCookable = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("CookingSound")) {
            this.cookingSound = val;
         } else if (param.trim().equalsIgnoreCase("MinutesToCook")) {
            this.minutesToCook = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("MinutesToBurn")) {
            this.minutesToBurn = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("BoredomChange")) {
            this.boredomChange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("StressChange")) {
            this.stressChange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("UnhappyChange")) {
            this.unhappyChange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("RemoveUnhappinessWhenCooked")) {
            this.removeUnhappinessWhenCooked = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("ReplaceOnDeplete")) {
            this.replaceOnDeplete = val;
         } else if (param.trim().equalsIgnoreCase("ReplaceOnExtinguish")) {
            this.replaceOnExtinguish = val;
         } else if (param.trim().equalsIgnoreCase("ReplaceOnUseOn")) {
            this.replaceOnUseOn = val;
            if (val.contains("-")) {
               String[] ss = val.split("-");
               String key = ss[0].trim();
               String type = ss[1].trim();
               if (!key.isEmpty() && !type.isEmpty()) {
                  if (this.replaceTypesMap == null) {
                     this.replaceTypesMap = new HashMap<>();
                  }

                  this.replaceTypesMap.put(key, type);
               }
            }
         } else if (param.trim().equalsIgnoreCase("ReplaceTypes")) {
            this.replaceTypes = val;
            String[] ss = val.split(";");

            for (String keyAndType : ss) {
               String[] ss2 = keyAndType.trim().split("\\s+");
               if (ss2.length == 2) {
                  String key = ss2[0].trim();
                  String type = ss2[1].trim();
                  if (!key.isEmpty() && !type.isEmpty()) {
                     if (this.replaceTypesMap == null) {
                        this.replaceTypesMap = new HashMap<>();
                     }

                     this.replaceTypesMap.put(key, type);
                  }
               }
            }
         } else if (param.trim().equalsIgnoreCase("Ranged")) {
            this.ranged = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("UseSelf")) {
            this.useSelf = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("OtherHandUse")) {
            this.otherHandUse = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("DangerousUncooked")) {
            this.dangerousUncooked = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("MaxRange")) {
            this.maxRange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MinRange")) {
            this.minRange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MinAngle")) {
            this.minAngle = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MaxDamage")) {
            this.maxDamage = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("BaseSpeed")) {
            this.baseSpeed = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("stompPower")) {
            this.stompPower = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("combatSpeedModifier")) {
            this.combatSpeedModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("runSpeedModifier")) {
            this.runSpeedModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("clothingItemExtra")) {
            this.clothingItemExtra = new ArrayList<>();
            String[] s = val.split(";");
            this.clothingItemExtra.addAll(Arrays.asList(s));
         } else if (param.trim().equalsIgnoreCase("clothingExtraSubmenu")) {
            this.clothingExtraSubmenu = val;
         } else if (param.trim().equalsIgnoreCase("removeOnBroken")) {
            this.removeOnBroken = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("canHaveHoles")) {
            this.canHaveHoles = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("Cosmetic")) {
            this.cosmetic = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("ammoBox")) {
            this.ammoBox = val;
         } else if (param.trim().equalsIgnoreCase("InsertAmmoStartSound")) {
            this.insertAmmoStartSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("InsertAmmoSound")) {
            this.insertAmmoSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("InsertAmmoStopSound")) {
            this.insertAmmoStopSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("EjectAmmoStartSound")) {
            this.ejectAmmoStartSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("EjectAmmoSound")) {
            this.ejectAmmoSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("EjectAmmoStopSound")) {
            this.ejectAmmoStopSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("rackSound")) {
            this.rackSound = val;
         } else if (param.trim().equalsIgnoreCase("clickSound")) {
            this.clickSound = val;
         } else if (param.equalsIgnoreCase("BringToBearSound")) {
            this.bringToBearSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.equalsIgnoreCase("AimReleaseSound")) {
            this.aimReleaseSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.equalsIgnoreCase("EquipSound")) {
            this.equipSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.equalsIgnoreCase("UnequipSound")) {
            this.unequipSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("magazineType")) {
            this.magazineType = val;
         } else if (param.trim().equalsIgnoreCase("jamGunChance")) {
            this.jamGunChance = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("modelWeaponPart")) {
            if (this.modelWeaponPart == null) {
               this.modelWeaponPart = new ArrayList<>();
            }

            String[] ss = val.split("\\s+");
            if (ss.length >= 2 && ss.length <= 4) {
               ModelWeaponPart mwp = null;

               for (int i = 0; i < this.modelWeaponPart.size(); i++) {
                  ModelWeaponPart mwp2 = this.modelWeaponPart.get(i);
                  if (mwp2.partType.equals(ss[0])) {
                     mwp = mwp2;
                     break;
                  }
               }

               if (mwp == null) {
                  mwp = new ModelWeaponPart();
               }

               mwp.partType = ss[0];
               mwp.modelName = ss[1];
               mwp.attachmentNameSelf = ss.length > 2 ? ss[2] : null;
               mwp.attachmentParent = ss.length > 3 ? ss[3] : null;
               if (!mwp.partType.contains(".")) {
                  mwp.partType = this.getModule().name + "." + mwp.partType;
               }

               if (!mwp.modelName.contains(".")) {
                  mwp.modelName = this.getModule().name + "." + mwp.modelName;
               }

               if ("none".equalsIgnoreCase(mwp.attachmentNameSelf)) {
                  mwp.attachmentNameSelf = null;
               }

               if ("none".equalsIgnoreCase(mwp.attachmentParent)) {
                  mwp.attachmentParent = null;
               }

               this.modelWeaponPart.add(mwp);
            }
         } else if (param.trim().equalsIgnoreCase("rackAfterShoot")) {
            this.rackAfterShoot = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("haveChamber")) {
            this.haveChamber = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("needtobeclosedoncereload")) {
            this.needToBeClosedOnceReload = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("isDung")) {
            this.isDung = Boolean.parseBoolean(val);
         } else if (param.equalsIgnoreCase("ManuallyRemoveSpentRounds")) {
            this.manuallyRemoveSpentRounds = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("biteDefense")) {
            this.biteDefense = Float.parseFloat(val);
            this.biteDefense = Math.min(this.biteDefense, 100.0F);
         } else if (param.trim().equalsIgnoreCase("bulletDefense")) {
            this.bulletDefense = Float.parseFloat(val);
            this.bulletDefense = Math.min(this.bulletDefense, 100.0F);
         } else if (param.trim().equalsIgnoreCase("scratchDefense")) {
            this.scratchDefense = Float.parseFloat(val);
            this.scratchDefense = Math.min(this.scratchDefense, 100.0F);
         } else if (param.trim().equalsIgnoreCase("neckProtectionModifier")) {
            this.neckProtectionModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("damageCategory")) {
            this.damageCategory = val;
         } else if (param.trim().equalsIgnoreCase("fireMode")) {
            this.fireMode = val;
         } else if (param.trim().equalsIgnoreCase("damageMakeHole")) {
            this.damageMakeHole = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("equippedNoSprint")) {
            this.equippedNoSprint = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("corpseSicknessDefense")) {
            this.corpseSicknessDefense = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("weaponReloadType")) {
            this.weaponReloadType = val;
         } else if (param.trim().equalsIgnoreCase("insertAllBulletsReload")) {
            this.insertAllBulletsReload = Boolean.parseBoolean(val);
         } else if (param.trim().equalsIgnoreCase("clothingItemExtraOption")) {
            this.clothingItemExtraOption = new ArrayList<>();
            String[] s = val.split(";");
            this.clothingItemExtraOption.addAll(Arrays.asList(s));
         } else if (param.trim().equalsIgnoreCase("ConditionLowerChanceOneIn")) {
            this.conditionLowerChance = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("HeadConditionLowerChanceMultiplier")) {
            this.headConditionLowerChanceMultiplier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MultipleHitConditionAffected")) {
            this.multipleHitConditionAffected = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("CanBandage")) {
            this.canBandage = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("ConditionMax")) {
            this.conditionMax = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("SoundGain")) {
            this.soundGain = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MinDamage")) {
            this.minDamage = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MinimumSwingTime")) {
            this.minimumSwingTime = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("SwingSound")) {
            this.swingSound = val;
         } else if (param.trim().equalsIgnoreCase("ReplaceOnUse")) {
            this.replaceOnUse = val;
         } else if (param.trim().equalsIgnoreCase("WeaponSprite")) {
            this.weaponSprite = val;
         } else if (param.trim().equalsIgnoreCase("weaponSpritesByIndex")) {
            this.weaponSpritesByIndex = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               this.weaponSpritesByIndex.add(split[i].trim());
            }
         } else if (param.trim().equalsIgnoreCase("AimingPerkCritModifier")) {
            this.aimingPerkCritModifier = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("AimingPerkRangeModifier")) {
            this.aimingPerkRangeModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("AimingPerkHitChanceModifier")) {
            this.aimingPerkHitChanceModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ProjectileSpreadModifier")) {
            this.projectileSpreadModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("WeightModifier")) {
            this.weightModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("AimingPerkMinAngleModifier")) {
            this.aimingPerkMinAngleModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("HitChance")) {
            this.hitChance = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("RecoilDelay")) {
            this.recoilDelay = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("StopPower")) {
            this.stopPower = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("PiercingBullets")) {
            this.piercingBullets = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("AngleFalloff")) {
            this.angleFalloff = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("SoundVolume")) {
            this.soundVolume = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ToHitModifier")) {
            this.toHitModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("SoundRadius")) {
            this.soundRadius = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("Categories")) {
            String[] s = val.split(";");

            for (int n = 0; n < s.length; n++) {
               this.weaponCategories.add(WeaponCategory.get(ResourceLocation.of(s[n].trim())));
            }
         } else if (param.trim().equalsIgnoreCase("Tags")) {
            String[] s = val.split(";");

            for (int n = 0; n < s.length; n++) {
               String tag = s[n].trim();
               this.itemTags.add(ItemTag.get(ResourceLocation.of(tag)));
            }
         } else if (param.trim().equalsIgnoreCase("OtherCharacterVolumeBoost")) {
            this.otherCharacterVolumeBoost = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ImpactSound")) {
            this.impactSound = val;
            if (this.impactSound.equals("null")) {
               this.impactSound = null;
            }
         } else if (param.trim().equalsIgnoreCase("SwingTime")) {
            this.swingTime = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("KnockBackOnNoDeath")) {
            this.knockBackOnNoDeath = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("Alcoholic")) {
            this.alcoholic = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("SplatBloodOnNoDeath")) {
            this.splatBloodOnNoDeath = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("SwingAmountBeforeImpact")) {
            this.swingAmountBeforeImpact = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("AmmoType")) {
            this.ammoType = AmmoType.get(ResourceLocation.of(val));
         } else if (param.trim().equalsIgnoreCase("maxAmmo")) {
            this.maxAmmo = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("GunType")) {
            this.gunType = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               this.gunType.add(split[i].trim());
            }
         } else if (param.trim().equalsIgnoreCase("HitAngleMod")) {
            this.hitAngleMod = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("OtherHandRequire")) {
            this.otherHandRequire = ItemTag.get(ResourceLocation.of(val));
         } else if (param.trim().equalsIgnoreCase("AlwaysWelcomeGift")) {
            this.alwaysWelcomeGift = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("CantAttackWithLowestEndurance")) {
            this.cantAttackWithLowestEndurance = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("EnduranceMod")) {
            this.enduranceMod = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("KnockdownMod")) {
            this.knockdownMod = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("DoorDamage")) {
            this.doorDamage = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("MaxHitCount")) {
            this.maxHitCount = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("PhysicsObject")) {
            this.physicsObject = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("Count")) {
            this.count = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("SwingAnim")) {
            this.swingAnim = val;
         } else if (param.trim().equalsIgnoreCase("WeaponWeight")) {
            this.weaponWeight = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("IdleAnim")) {
            this.idleAnim = val;
         } else if (param.trim().equalsIgnoreCase("RunAnim")) {
            this.runAnim = val;
         } else if (param.trim().equalsIgnoreCase("RequireInHandOrInventory")) {
            this.requireInHandOrInventory = new ArrayList<>(Arrays.asList(val.split("/")));
         } else if (param.trim().equalsIgnoreCase("fireModePossibilities")) {
            this.fireModePossibilities = new ArrayList<>(Arrays.asList(val.split("/")));
         } else if (param.trim().equalsIgnoreCase("CyclicRateMultiplier")) {
            this.cyclicRateMultiplier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("attachmentsProvided")) {
            this.attachmentsProvided = new ArrayList<>(Arrays.asList(val.split(";")));
         } else if (param.trim().equalsIgnoreCase("attachmentReplacement")) {
            this.attachmentReplacement = val.trim();
         } else if (param.trim().equalsIgnoreCase("PushBackMod")) {
            this.pushBackMod = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("NPCSoundBoost")) {
            this.npcSoundBoost = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("SplatNumber")) {
            this.splatNumber = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("RangeFalloff")) {
            this.rangeFalloff = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("UseEndurance")) {
            this.useEndurance = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("ShareEndurance")) {
            this.shareEndurance = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("AlwaysKnockdown")) {
            this.alwaysKnockdown = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("IsAimedFirearm")) {
            this.isAimedFirearm = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("bulletOutSound")) {
            this.bulletOutSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("ShellFallSound")) {
            this.shellFallSound = val.trim();
         } else if (param.equalsIgnoreCase("DropSound")) {
            this.dropSound = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("SoundMap")) {
            String[] ss = val.split("\\s+");
            if (ss.length == 2 && !ss[0].trim().isEmpty()) {
               if (this.soundMap == null) {
                  this.soundMap = new HashMap<>();
               }

               this.soundMap.put(ss[0].trim(), ss[1].trim());
            }
         } else if (param.trim().equalsIgnoreCase("IsAimedHandWeapon")) {
            this.isAimedHandWeapon = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("AimingMod")) {
            this.aimingMod = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ProjectileCount")) {
            this.projectileCount = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ProjectileSpread")) {
            this.projectileSpread = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ProjectileWeightCenter")) {
            this.projectileWeightCenter = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MuzzleFlashModelKey")) {
            this.muzzleFlashModelKey = new ModelKey(val);
         } else if (param.trim().equalsIgnoreCase("CanStack")) {
            this.canStack = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("HerbalistType")) {
            this.herbalistType = val.trim();
         } else if (param.trim().equalsIgnoreCase("CanBarricade")) {
            this.canBarricade = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("UseWhileEquipped")) {
            this.useWhileEquipped = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("UseWhileUnequipped")) {
            this.useWhileUnequipped = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("TicksPerEquipUse")) {
            this.ticksPerEquipUse = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("DisappearOnUse")) {
            this.disappearOnUse = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("Temperature")) {
            this.temperature = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("Insulation")) {
            this.insulation = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("WindResistance")) {
            this.windresist = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("WaterResistance")) {
            this.waterresist = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("CloseKillMove")) {
            this.closeKillMove = val.trim();
         } else if (param.trim().equalsIgnoreCase("UseDelta")) {
            this.useDelta = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("TorchDot")) {
            this.torchDot = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("NumberOfPages")) {
            this.numberOfPages = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("SkillTrained")) {
            this.skillTrained = val.trim();
         } else if (param.trim().equalsIgnoreCase("LvlSkillTrained")) {
            this.lvlSkillTrained = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("NumLevelsTrained")) {
            this.numLevelsTrained = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("Capacity")) {
            int capacity = Integer.parseInt(val.trim());
            if (capacity > 50.0F - this.actualWeight) {
               capacity = (int)(50.0F - this.actualWeight);
            }

            this.capacity = capacity;
         } else if (param.trim().equalsIgnoreCase("MaxItemSize")) {
            this.maxItemSize = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("MaxCapacity")) {
            this.maxCapacity = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("ItemCapacity")) {
            this.itemCapacity = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("ConditionAffectsCapacity")) {
            this.conditionAffectsCapacity = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("BrakeForce")) {
            this.brakeForce = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("Durability")) {
            this.durability = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("ChanceToSpawnDamaged")) {
            this.chanceToSpawnDamaged = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("WeaponLength")) {
            this.weaponLength = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("ClipSize")) {
            this.clipSize = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("ReloadTime")) {
            this.reloadTime = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("AimingTime")) {
            this.aimingTime = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("AimingTimeModifier")) {
            this.aimingTimeModifier = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("ReloadTimeModifier")) {
            this.reloadTimeModifier = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("HitChanceModifier")) {
            this.hitChanceModifier = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("WeightReduction")) {
            this.weightReduction = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("CanBeEquipped")) {
            this.canBeEquipped = ItemBodyLocation.get(ResourceLocation.of(val.trim()));
         } else if (param.trim().equalsIgnoreCase("SubCategory")) {
            this.subCategory = val.trim();
         } else if (param.trim().equalsIgnoreCase("ActivatedItem")) {
            this.activatedItem = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("ProtectFromRainWhenEquipped")) {
            this.protectFromRainWhenEquipped = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("LightStrength")) {
            this.lightStrength = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("TorchCone")) {
            this.torchCone = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("LightDistance")) {
            this.lightDistance = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("TwoHandWeapon")) {
            this.twoHandWeapon = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("Tooltip")) {
            this.tooltip = val.trim();
         } else if (param.trim().equalsIgnoreCase("DisplayCategory")) {
            this.displayCategory = val.trim();
         } else if (param.trim().equalsIgnoreCase("BadInMicrowave")) {
            this.badInMicrowave = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("GoodHot")) {
            this.goodHot = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("BadCold")) {
            this.badCold = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("AlarmSound")) {
            this.alarmSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("RequiresEquippedBothHands")) {
            this.requiresEquippedBothHands = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("ReplaceOnCooked")) {
            this.replaceOnCooked = Arrays.asList(val.trim().split(";"));
         } else if (param.trim().equalsIgnoreCase("CustomContextMenu")) {
            this.customContextMenu = val.trim();
         } else if (param.trim().equalsIgnoreCase("Trap")) {
            this.trap = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("Wet")) {
            this.isWet = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("WetCooldown")) {
            this.wetCooldown = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("ItemWhenDry")) {
            this.itemWhenDry = val.trim();
         } else if (param.trim().equalsIgnoreCase("FishingLure")) {
            this.fishingLure = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("CanBeWrite")) {
            this.canBeWrite = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("PageToWrite")) {
            this.pageToWrite = Integer.parseInt(val.trim());
         } else if (param.trim().equalsIgnoreCase("Spice")) {
            this.spice = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("RemoveNegativeEffectOnCooked")) {
            this.removeNegativeEffectOnCooked = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("ClipSizeModifier")) {
            this.clipSizeModifier = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("RecoilDelayModifier")) {
            this.recoilDelayModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MaxRangeModifier")) {
            this.maxRangeModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MinSightRange")) {
            this.minSightRange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("MaxSightRange")) {
            this.maxSightRange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("LowLightBonus")) {
            this.lowLightBonus = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("DamageModifier")) {
            this.damageModifier = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("Map")) {
            this.map = val.trim();
         } else if (param.trim().equalsIgnoreCase("PutInSound")) {
            this.putInSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("PlaceOneSound")) {
            this.placeOneSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("PlaceMultipleSound")) {
            this.placeMultipleSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("CloseSound")) {
            this.closeSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("OpenSound")) {
            this.openSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("BreakSound")) {
            this.breakSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("TreeDamage")) {
            this.treeDamage = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("CustomEatSound")) {
            this.customEatSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("FillFromDispenserSound")) {
            this.fillFromDispenserSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("FillFromLakeSound")) {
            this.fillFromLakeSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("FillFromTapSound")) {
            this.fillFromTapSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("FillFromToiletSound")) {
            this.fillFromToiletSound = StringUtils.discardNullOrWhitespace(val.trim());
         } else if (param.trim().equalsIgnoreCase("AlcoholPower")) {
            this.alcoholPower = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("BandagePower")) {
            this.bandagePower = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("ReduceInfectionPower")) {
            this.reduceInfectionPower = Float.parseFloat(val.trim());
         } else if (param.trim().equalsIgnoreCase("OnCooked")) {
            this.onCooked = val.trim();
         } else if (param.trim().equalsIgnoreCase("OnlyAcceptCategory")) {
            this.onlyAcceptCategory = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("AcceptItemFunction")) {
            this.acceptItemFunction = StringUtils.discardNullOrWhitespace(val);
         } else if (param.trim().equalsIgnoreCase("Padlock")) {
            this.padlock = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("DigitalPadlock")) {
            this.digitalPadlock = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("triggerExplosionTimer")) {
            this.triggerExplosionTimer = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("sensorRange")) {
            this.sensorRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("remoteRange")) {
            this.remoteRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("CountDownSound")) {
            this.countDownSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("explosionSound")) {
            this.explosionSound = val.trim();
         } else if (param.trim().equalsIgnoreCase("PlacedSprite")) {
            this.placedSprite = val.trim();
         } else if (param.trim().equalsIgnoreCase("explosionTimer")) {
            this.explosionTimer = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("explosionDuration")) {
            this.explosionDuration = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("explosionRange")) {
            this.explosionRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("explosionPower")) {
            this.explosionPower = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("fireRange")) {
            this.fireRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("fireStartingEnergy")) {
            this.fireStartingEnergy = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("fireStartingChance")) {
            this.fireStartingChance = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("canBePlaced")) {
            this.canBePlaced = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("CanBeReused")) {
            this.canBeReused = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("canBeRemote")) {
            this.canBeRemote = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("remoteController")) {
            this.remoteController = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("smokeRange")) {
            this.smokeRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("noiseRange")) {
            this.noiseRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("noiseDuration")) {
            this.noiseDuration = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("extraDamage")) {
            this.extraDamage = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("TwoWay")) {
            this.twoWay = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("TransmitRange")) {
            this.transmitRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("MicRange")) {
            this.micRange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("BaseVolumeRange")) {
            this.baseVolumeRange = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("IsPortable")) {
            this.isPortable = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("IsTelevision")) {
            this.isTelevision = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("MinChannel")) {
            this.minChannel = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("MaxChannel")) {
            this.maxChannel = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("UsesBattery")) {
            this.usesBattery = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("IsHighTier")) {
            this.isHighTier = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("WorldObjectSprite")) {
            this.worldObjectSprite = val.trim();
         } else if (param.trim().equalsIgnoreCase("fluReduction")) {
            this.fluReduction = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("FoodSicknessChange")) {
            this.foodSicknessChange = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("InverseCoughProbability")) {
            this.inverseCoughProbability = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("InverseCoughProbabilitySmoker")) {
            this.inverseCoughProbabilitySmoker = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("painReduction")) {
            this.painReduction = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ColorRed")) {
            this.colorRed = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ColorGreen")) {
            this.colorGreen = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ColorBlue")) {
            this.colorBlue = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("calories")) {
            this.calories = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("carbohydrates")) {
            this.carbohydrates = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("lipids")) {
            this.lipids = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("proteins")) {
            this.proteins = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("Packaged")) {
            this.packaged = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("CantBeFrozen")) {
            this.cantBeFrozen = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("EvolvedRecipeName")) {
            Translator.setDefaultItemEvolvedRecipeName(this.getFullName(), val);
            this.evolvedRecipeName = Translator.getItemEvolvedRecipeName(this.getFullName());
         } else if (param.trim().equalsIgnoreCase("ReplaceOnRotten")) {
            this.replaceOnRotten = val.trim();
         } else if (param.trim().equalsIgnoreCase("CantBeConsolided")) {
            this.cantBeConsolided = val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("OnEat")) {
            this.onEat = val.trim();
         } else if (param.trim().equalsIgnoreCase("KeepOnDeplete")) {
            this.disappearOnUse = !val.equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("VehicleType")) {
            this.vehicleType = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("ChanceToFall")) {
            this.chanceToFall = Integer.parseInt(val);
         } else if (param.trim().equalsIgnoreCase("conditionLowerOffroad")) {
            this.conditionLowerOffroad = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("ConditionLowerStandard")) {
            this.conditionLowerNormal = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("wheelFriction")) {
            this.wheelFriction = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("suspensionDamping")) {
            this.suspensionDamping = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("suspensionCompression")) {
            this.suspensionCompression = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("engineLoudness")) {
            this.engineLoudness = Float.parseFloat(val);
         } else if (param.trim().equalsIgnoreCase("attachmentType")) {
            this.attachmentType = val.trim();
         } else if (param.trim().equalsIgnoreCase("makeUpType")) {
            this.makeUpType = val.trim();
         } else if (param.trim().equalsIgnoreCase("consolidateOption")) {
            this.consolidateOption = val.trim();
         } else if (param.trim().equalsIgnoreCase("fabricType")) {
            this.fabricType = val.trim();
         } else if (param.trim().equalsIgnoreCase("LearnedRecipes")) {
            this.learnedRecipes = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               String name = split[i].trim();
               this.learnedRecipes.add(name);
               if (Translator.debug) {
                  Translator.getRecipeName(name);
               }
            }
         } else if (param.trim().equalsIgnoreCase("ResearchableRecipes")) {
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               String name = split[i].trim();
               this.addResearchableRecipe(name);
               if (Translator.debug) {
                  Translator.getRecipeName(name);
               }
            }
         } else if (param.trim().equalsIgnoreCase("MountOn")) {
            this.mountOn = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               this.mountOn.add(split[i].trim());
            }
         } else if (param.trim().equalsIgnoreCase("PartType")) {
            this.partType = val;
         } else if (param.trim().equalsIgnoreCase("CanAttach")) {
            this.canAttachCallback = val;
         } else if (param.trim().equalsIgnoreCase("CanDetach")) {
            this.canDetachCallback = val;
         } else if (param.trim().equalsIgnoreCase("OnAttach")) {
            this.onAttachCallback = val;
         } else if (param.trim().equalsIgnoreCase("OnDetach")) {
            this.onDetachCallback = val;
         } else if (param.trim().equalsIgnoreCase("ClothingItem")) {
            this.clothingItem = val;
         } else if (param.trim().equalsIgnoreCase("EvolvedRecipe")) {
            String[] split = val.split(";");
            String[] s = val.split(";");

            for (int n = 0; n < s.length; n++) {
               this.evolvedRecipe.add(split[n].trim());
            }

            for (int i = 0; i < split.length; i++) {
               String recipe = split[i];
               int use = 0;
               boolean cooked = false;
               String recipeName;
               if (!recipe.contains(":")) {
                  recipeName = recipe;
               } else {
                  recipeName = recipe.split(":")[0];
                  String useStr = recipe.split(":")[1];
                  if (!useStr.contains("|")) {
                     use = Integer.parseInt(recipe.split(":")[1]);
                  } else {
                     String[] splittedUse = useStr.split("\\|");

                     for (int j = 0; j < splittedUse.length; j++) {
                        if ("Cooked".equals(splittedUse[j])) {
                           cooked = true;
                        }
                     }

                     use = Integer.parseInt(splittedUse[0]);
                  }
               }

               if (recipeName.equals("RicePot") || recipeName.equals("RicePan")) {
                  recipeName = "Rice";
               }

               if (recipeName.equals("PastaPot") || recipeName.equals("PastaPan")) {
                  recipeName = "Pasta";
               }

               if (recipeName.equals("Roasted Vegetables")) {
                  recipeName = "Stir fry";
               }

               ItemRecipe itemRecipe = new ItemRecipe(this.name, this.getModule().getName(), use);
               itemRecipe.cooked = cooked;
               this.itemRecipeMap.put(recipeName, itemRecipe);
            }
         } else if (param.trim().equalsIgnoreCase("StaticModel")) {
            this.staticModel = val.trim();
         } else if (param.trim().equalsIgnoreCase("StaticModelOn")) {
            this.staticModelOn = val.trim();
         } else if (param.trim().equalsIgnoreCase("worldStaticModel")) {
            this.worldStaticModel = val.trim();
         } else if (param.trim().equalsIgnoreCase("primaryAnimMask")) {
            this.primaryAnimMask = val.trim();
         } else if (param.trim().equalsIgnoreCase("secondaryAnimMask")) {
            this.secondaryAnimMask = val.trim();
         } else if (param.trim().equalsIgnoreCase("primaryAnimMaskAttachment")) {
            this.primaryAnimMaskAttachment = val.trim();
         } else if (param.trim().equalsIgnoreCase("secondaryAnimMaskAttachment")) {
            this.secondaryAnimMaskAttachment = val.trim();
         } else if (param.trim().equalsIgnoreCase("replaceInSecondHand")) {
            this.replaceInSecondHand = val.trim();
         } else if (param.trim().equalsIgnoreCase("replaceInPrimaryHand")) {
            this.replaceInPrimaryHand = val.trim();
         } else if (param.trim().equalsIgnoreCase("replaceWhenUnequip")) {
            this.replaceWhenUnequip = val.trim();
         } else if (param.trim().equalsIgnoreCase("EatType")) {
            this.eatType = val.trim();
         } else if (param.trim().equalsIgnoreCase("PourType")) {
            this.pourType = val.trim();
         } else if (param.trim().equalsIgnoreCase("ReadType")) {
            this.readType = val.trim();
         } else if (param.trim().equalsIgnoreCase("book_subject")) {
            for (String s : val.trim().split(";")) {
               this.bookSubjects.add(BookSubject.get(ResourceLocation.of(s)));
            }
         } else if (param.trim().equalsIgnoreCase("magazine_subject")) {
            for (String s : val.trim().split(";")) {
               this.magazineSubjects.add(MagazineSubject.get(ResourceLocation.of(s)));
            }
         } else if (param.trim().equalsIgnoreCase("DigType")) {
            this.digType = val.trim();
         } else if (param.trim().equalsIgnoreCase("IconsForTexture")) {
            this.iconsForTexture = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               this.iconsForTexture.add(split[i].trim());
            }
         } else if (param.trim().equalsIgnoreCase("BloodLocation")) {
            this.bloodClothingType = new ArrayList<>();
            String[] split = val.split(";");

            for (int i = 0; i < split.length; i++) {
               this.bloodClothingType.add(BloodClothingType.fromString(split[i].trim()));
            }
         } else if (param.trim().equalsIgnoreCase("MediaCategory")) {
            this.recordedMediaCat = val.trim();
         } else if (param.trim().equalsIgnoreCase("AcceptMediaType")) {
            this.acceptMediaType = Byte.parseByte(val.trim());
         } else if (param.trim().equalsIgnoreCase("NoTransmit")) {
            this.noTransmit = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("WorldRender")) {
            this.worldRender = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("CantEat")) {
            this.cantEat = Boolean.parseBoolean(val.trim());
         } else if (param.trim().equalsIgnoreCase("OBSOLETE")) {
            this.obsolete = val.trim().equalsIgnoreCase("true");
         } else if (param.trim().equalsIgnoreCase("OnCreate")) {
            this.luaCreate = val.trim();
         } else if (param.trim().equalsIgnoreCase("OpeningRecipe")) {
            this.openingRecipe = val.trim();
         } else if (param.trim().equalsIgnoreCase("DoubleClickRecipe")) {
            this.doubleClickRecipe = val.trim();
         } else if (param.trim().equalsIgnoreCase("ItemConfig")) {
            this.itemConfigKey = val.trim();
         } else if (param.trim().equalsIgnoreCase("IconColorMask")) {
            this.iconColorMask = val.trim();
         } else if (param.trim().equalsIgnoreCase("IconFluidMask")) {
            this.iconFluidMask = val.trim();
         } else if (!param.trim().equalsIgnoreCase("GameEntityScript")) {
            if (param.trim().equalsIgnoreCase("SoundParameter")) {
               String[] ss = val.split("\\s+");
               if (this.soundParameterMap == null) {
                  this.soundParameterMap = new HashMap<>();
               }

               this.soundParameterMap.put(ss[0].trim(), ss[1].trim());
            } else if (param.trim().equalsIgnoreCase("VehiclePartModel")) {
               this.DoParam_VehiclePartModel(val);
            } else if (param.trim().equalsIgnoreCase("withDrainable")) {
               this.withDrainable = val;
            } else if (param.trim().equalsIgnoreCase("withoutDrainable")) {
               this.withoutDrainable = val;
            } else if (param.trim().equalsIgnoreCase("staticModelsByIndex")) {
               this.staticModelsByIndex = new ArrayList<>();
               String[] split = val.split(";");

               for (int i = 0; i < split.length; i++) {
                  this.staticModelsByIndex.add(split[i].trim());
               }
            } else if (param.trim().equalsIgnoreCase("worldStaticModelsByIndex")) {
               this.worldStaticModelsByIndex = new ArrayList<>();
               String[] split = val.split(";");

               for (int i = 0; i < split.length; i++) {
                  this.worldStaticModelsByIndex.add(split[i].trim());
               }
            } else if (param.trim().equalsIgnoreCase("spawnWith")) {
               this.spawnWith = val;
            } else if (param.trim().equalsIgnoreCase("visionModifier")) {
               this.visionModifier = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("hearingModifier")) {
               this.hearingModifier = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("strainModifier")) {
               this.strainModifier = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("OnBreak")) {
               this.onBreak = val.trim();
            } else if (param.trim().equalsIgnoreCase("DamagedSound")) {
               this.damagedSound = val.trim();
            } else if (param.trim().equalsIgnoreCase("BulletHitArmourSound")) {
               this.bulletHitArmourSound = StringUtils.discardNullOrWhitespace(val.trim());
            } else if (param.trim().equalsIgnoreCase("WeaponHitArmourSound")) {
               this.weaponHitArmourSound = StringUtils.discardNullOrWhitespace(val.trim());
            } else if (param.trim().equalsIgnoreCase("ShoutType")) {
               this.shoutType = val.trim();
            } else if (param.trim().equalsIgnoreCase("ShoutMultiplier")) {
               this.shoutMultiplier = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("EatTime")) {
               this.eatTime = Integer.parseInt(val);
            } else if (param.trim().equalsIgnoreCase("VisualAid")) {
               this.visualAid = Boolean.parseBoolean(val.trim());
            } else if (param.trim().equalsIgnoreCase("DiscomfortModifier")) {
               this.discomfortModifier = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("fireFuelRatio")) {
               this.fireFuelRatio = Float.parseFloat(val);
            } else if (param.trim().equalsIgnoreCase("ItemAfterCleaning")) {
               this.itemAfterCleaning = val.trim();
            } else {
               DebugType.DetailedInfo
                  .warn(
                     "adding unknown item param \""
                        + param.trim()
                        + "\" = \""
                        + val.trim()
                        + "\", script: "
                        + this.getScriptObjectFullType()
                        + ", path: "
                        + this.getFileAbsPath()
                  );
               if (this.defaultModData == null) {
                  this.defaultModData = LuaManager.platform.newTable();
               }

               try {
                  Double tryConv = Double.parseDouble(val.trim());
                  this.defaultModData.rawset(param.trim(), tryConv);
               } catch (Exception ex) {
                  this.defaultModData.rawset(param.trim(), val);
               }
            }
         }
      } catch (Exception ex) {
         throw new InvalidParameterException("Error: " + param.trim() + " is not a valid parameter in item: " + this.name);
      }
   }

   // pzopt: same branches as DoParam, dispatched by a switch on the lower-cased key; each case body is the stock
   // body verbatim, the default case is the stock tail of the chain (unknown parameter -> mod data)
   private void pzoptDoParam(String param, String val) {
      try {
         AttributeType attributeType = Attribute.TypeFromName(param.trim());
         if (attributeType != null) {
            this.LoadAttribute(param.trim(), val.trim());
            if (attributeType == Attribute.HeadCondition) {
               this.LoadAttribute("TimesHeadRepaired", "0");
            }
         } else {
            switch (param.trim().toLowerCase(java.util.Locale.ROOT)) {
               case "bodylocation": {
                  String bodyLocation = val.trim();
                  this.bodyLocation = ItemBodyLocation.get(ResourceLocation.of(bodyLocation));
                  break;
               }
               case "palettes": {
                  String[] split = val.split("/");

                  for (int n = 0; n < split.length; n++) {
                     this.paletteChoices.add(split[n].trim());
                  }
                  break;
               }
               case "hitsound": {
                  this.hitSound = val.trim();
                  if (this.hitSound.equals("null")) {
                     this.hitSound = null;
                  }
                  break;
               }
               case "hitfloorsound": {
                  this.hitFloorSound = val.trim();
                  break;
               }
               case "palettesstart": {
                  this.palettesStart = val.trim();
                  break;
               }
               case "displayname": {
                  this.displayName = val.trim();
                  break;
               }
               case "metalvalue": {
                  this.metalValue = Float.parseFloat(val.trim());
                  break;
               }
               case "spritename": {
                  this.spriteName = val.trim();
                  break;
               }
               case "itemtype": {
                  this.itemType = ItemType.get(ResourceLocation.of(val.trim()));
                  break;
               }
               case "splatsize": {
                  this.splatSize = Float.parseFloat(val);
                  break;
               }
               case "canstorewater": {
                  this.canStoreWater = val.equalsIgnoreCase("true");
                  break;
               }
               case "poison": {
                  this.poison = val.equalsIgnoreCase("true");
                  break;
               }
               case "foodtype": {
                  this.foodType = val.trim();
                  break;
               }
               case "poisondetectionlevel": {
                  this.poisonDetectionLevel = Integer.parseInt(val);
                  break;
               }
               case "poisonpower": {
                  this.poisonPower = Integer.parseInt(val);
                  break;
               }
               case "useforpoison": {
                  this.useForPoison = Integer.parseInt(val);
                  break;
               }
               case "swinganim": {
                  this.swingAnim = val;
                  break;
               }
               case "icon": {
                  this.icon = val;
                  this.itemName = "Item_" + this.icon;
                  this.normalTexture = Texture.trygetTexture(this.itemName);
                  if (this.normalTexture == null) {
                     this.normalTexture = Texture.getSharedTexture("media/inventory/Question_On.png");
                  }

                  this.worldTextureName = this.itemName.replace("Item_", "media/inventory/world/WItem_");
                  this.worldTextureName = this.worldTextureName + ".png";
                  this.worldTexture = Texture.getSharedTexture(this.worldTextureName);
                  if (this.itemType == ItemType.FOOD) {
                     Texture texRotten = Texture.trygetTexture(this.itemName + "Rotten");
                     String wTexRotten = this.worldTextureName.replace(".png", "Rotten.png");
                     if (texRotten == null) {
                        texRotten = Texture.trygetTexture(this.itemName + "Spoiled");
                        wTexRotten = wTexRotten.replace("Rotten.png", "Spoiled.png");
                     }

                     if (texRotten == null) {
                        texRotten = Texture.trygetTexture(this.itemName + "_Rotten");
                        wTexRotten = wTexRotten.replace("Rotten.png", "_Rotten.png");
                     }

                     this.specialWorldTextureNames.add(wTexRotten);
                     this.specialTextures.add(texRotten);
                     this.specialTextures.add(Texture.trygetTexture(this.itemName + "Cooked"));
                     this.specialWorldTextureNames.add(this.worldTextureName.replace(".png", "Cooked.png"));
                     Texture texOverdone = Texture.trygetTexture(this.itemName + "Overdone");
                     String wTexOverdone = this.worldTextureName.replace(".png", "Overdone.png");
                     if (texOverdone == null) {
                        texOverdone = Texture.trygetTexture(this.itemName + "Burnt");
                        wTexOverdone = wTexOverdone.replace("Overdone.png", "Burnt.png");
                     }

                     if (texOverdone == null) {
                        texOverdone = Texture.trygetTexture(this.itemName + "_Burnt");
                        wTexOverdone = wTexOverdone.replace("Overdone.png", "_Burnt.png");
                     }

                     this.specialTextures.add(texOverdone);
                     this.specialWorldTextureNames.add(wTexOverdone);
                  }
                  break;
               }
               case "useworlditem": {
                  this.useWorldItem = Boolean.parseBoolean(val);
                  break;
               }
               case "medical": {
                  this.medical = Boolean.parseBoolean(val);
                  break;
               }
               case "cannedfood": {
                  this.cannedFood = Boolean.parseBoolean(val);
                  break;
               }
               case "mechanicsitem": {
                  this.mechanicsItem = Boolean.parseBoolean(val);
                  break;
               }
               case "survivalgear": {
                  this.survivalGear = Boolean.parseBoolean(val);
                  break;
               }
               case "scaleworldicon": {
                  this.scaleWorldIcon = Float.parseFloat(val);
                  break;
               }
               case "doorhitsound": {
                  this.doorHitSound = val;
                  break;
               }
               case "weight": {
                  this.actualWeight = Float.parseFloat(val);
                  if (this.actualWeight < 0.0F) {
                     this.actualWeight = 0.0F;
                  }
                  break;
               }
               case "weightwet": {
                  this.weightWet = Float.parseFloat(val);
                  break;
               }
               case "weightempty": {
                  this.weightEmpty = Float.parseFloat(val);
                  break;
               }
               case "hungerchange": {
                  this.hungerChange = Float.parseFloat(val);
                  break;
               }
               case "thirstchange": {
                  this.thirstChange = Float.parseFloat(val);
                  break;
               }
               case "fatiguechange": {
                  this.fatigueChange = Float.parseFloat(val);
                  break;
               }
               case "endurancechange": {
                  this.enduranceChange = Float.parseFloat(val);
                  break;
               }
               case "hidden": {
                  this.hidden = Boolean.parseBoolean(val);
                  break;
               }
               case "criticalchance": {
                  this.criticalChance = Float.parseFloat(val);
                  break;
               }
               case "critdmgmultiplier": {
                  this.critDmgMultiplier = Float.parseFloat(val);
                  break;
               }
               case "daysfresh": {
                  this.daysFresh = Integer.parseInt(val);
                  break;
               }
               case "milkreplaceitem": {
                  this.milkReplaceItem = val;
                  break;
               }
               case "maxmilk": {
                  this.maxMilk = Integer.parseInt(val);
                  break;
               }
               case "animalfeedtype": {
                  this.animalFeedType = val;
                  break;
               }
               case "daystotallyrotten": {
                  this.daysTotallyRotten = Integer.parseInt(val);
                  break;
               }
               case "iscookable": {
                  this.isCookable = val.equalsIgnoreCase("true");
                  break;
               }
               case "cookingsound": {
                  this.cookingSound = val;
                  break;
               }
               case "minutestocook": {
                  this.minutesToCook = Integer.parseInt(val);
                  break;
               }
               case "minutestoburn": {
                  this.minutesToBurn = Integer.parseInt(val);
                  break;
               }
               case "boredomchange": {
                  this.boredomChange = Integer.parseInt(val);
                  break;
               }
               case "stresschange": {
                  this.stressChange = Integer.parseInt(val);
                  break;
               }
               case "unhappychange": {
                  this.unhappyChange = Integer.parseInt(val);
                  break;
               }
               case "removeunhappinesswhencooked": {
                  this.removeUnhappinessWhenCooked = Boolean.parseBoolean(val);
                  break;
               }
               case "replaceondeplete": {
                  this.replaceOnDeplete = val;
                  break;
               }
               case "replaceonextinguish": {
                  this.replaceOnExtinguish = val;
                  break;
               }
               case "replaceonuseon": {
                  this.replaceOnUseOn = val;
                  if (val.contains("-")) {
                     String[] ss = val.split("-");
                     String key = ss[0].trim();
                     String type = ss[1].trim();
                     if (!key.isEmpty() && !type.isEmpty()) {
                        if (this.replaceTypesMap == null) {
                           this.replaceTypesMap = new HashMap<>();
                        }

                        this.replaceTypesMap.put(key, type);
                     }
                  }
                  break;
               }
               case "replacetypes": {
                  this.replaceTypes = val;
                  String[] ss = val.split(";");

                  for (String keyAndType : ss) {
                     String[] ss2 = keyAndType.trim().split("\\s+");
                     if (ss2.length == 2) {
                        String key = ss2[0].trim();
                        String type = ss2[1].trim();
                        if (!key.isEmpty() && !type.isEmpty()) {
                           if (this.replaceTypesMap == null) {
                              this.replaceTypesMap = new HashMap<>();
                           }

                           this.replaceTypesMap.put(key, type);
                        }
                     }
                  }
                  break;
               }
               case "ranged": {
                  this.ranged = val.equalsIgnoreCase("true");
                  break;
               }
               case "useself": {
                  this.useSelf = val.equalsIgnoreCase("true");
                  break;
               }
               case "otherhanduse": {
                  this.otherHandUse = val.equalsIgnoreCase("true");
                  break;
               }
               case "dangerousuncooked": {
                  this.dangerousUncooked = val.equalsIgnoreCase("true");
                  break;
               }
               case "maxrange": {
                  this.maxRange = Float.parseFloat(val);
                  break;
               }
               case "minrange": {
                  this.minRange = Float.parseFloat(val);
                  break;
               }
               case "minangle": {
                  this.minAngle = Float.parseFloat(val);
                  break;
               }
               case "maxdamage": {
                  this.maxDamage = Float.parseFloat(val);
                  break;
               }
               case "basespeed": {
                  this.baseSpeed = Float.parseFloat(val);
                  break;
               }
               case "stomppower": {
                  this.stompPower = Float.parseFloat(val);
                  break;
               }
               case "combatspeedmodifier": {
                  this.combatSpeedModifier = Float.parseFloat(val);
                  break;
               }
               case "runspeedmodifier": {
                  this.runSpeedModifier = Float.parseFloat(val);
                  break;
               }
               case "clothingitemextra": {
                  this.clothingItemExtra = new ArrayList<>();
                  String[] s = val.split(";");
                  this.clothingItemExtra.addAll(Arrays.asList(s));
                  break;
               }
               case "clothingextrasubmenu": {
                  this.clothingExtraSubmenu = val;
                  break;
               }
               case "removeonbroken": {
                  this.removeOnBroken = Boolean.parseBoolean(val);
                  break;
               }
               case "canhaveholes": {
                  this.canHaveHoles = Boolean.parseBoolean(val);
                  break;
               }
               case "cosmetic": {
                  this.cosmetic = Boolean.parseBoolean(val);
                  break;
               }
               case "ammobox": {
                  this.ammoBox = val;
                  break;
               }
               case "insertammostartsound": {
                  this.insertAmmoStartSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "insertammosound": {
                  this.insertAmmoSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "insertammostopsound": {
                  this.insertAmmoStopSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "ejectammostartsound": {
                  this.ejectAmmoStartSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "ejectammosound": {
                  this.ejectAmmoSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "ejectammostopsound": {
                  this.ejectAmmoStopSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "racksound": {
                  this.rackSound = val;
                  break;
               }
               case "clicksound": {
                  this.clickSound = val;
                  break;
               }
               case "bringtobearsound": {
                  this.bringToBearSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "aimreleasesound": {
                  this.aimReleaseSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "equipsound": {
                  this.equipSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "unequipsound": {
                  this.unequipSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "magazinetype": {
                  this.magazineType = val;
                  break;
               }
               case "jamgunchance": {
                  this.jamGunChance = Float.parseFloat(val);
                  break;
               }
               case "modelweaponpart": {
                  if (this.modelWeaponPart == null) {
                     this.modelWeaponPart = new ArrayList<>();
                  }

                  String[] ss = val.split("\\s+");
                  if (ss.length >= 2 && ss.length <= 4) {
                     ModelWeaponPart mwp = null;

                     for (int i = 0; i < this.modelWeaponPart.size(); i++) {
                        ModelWeaponPart mwp2 = this.modelWeaponPart.get(i);
                        if (mwp2.partType.equals(ss[0])) {
                           mwp = mwp2;
                           break;
                        }
                     }

                     if (mwp == null) {
                        mwp = new ModelWeaponPart();
                     }

                     mwp.partType = ss[0];
                     mwp.modelName = ss[1];
                     mwp.attachmentNameSelf = ss.length > 2 ? ss[2] : null;
                     mwp.attachmentParent = ss.length > 3 ? ss[3] : null;
                     if (!mwp.partType.contains(".")) {
                        mwp.partType = this.getModule().name + "." + mwp.partType;
                     }

                     if (!mwp.modelName.contains(".")) {
                        mwp.modelName = this.getModule().name + "." + mwp.modelName;
                     }

                     if ("none".equalsIgnoreCase(mwp.attachmentNameSelf)) {
                        mwp.attachmentNameSelf = null;
                     }

                     if ("none".equalsIgnoreCase(mwp.attachmentParent)) {
                        mwp.attachmentParent = null;
                     }

                     this.modelWeaponPart.add(mwp);
                  }
                  break;
               }
               case "rackaftershoot": {
                  this.rackAfterShoot = Boolean.parseBoolean(val);
                  break;
               }
               case "havechamber": {
                  this.haveChamber = Boolean.parseBoolean(val);
                  break;
               }
               case "needtobeclosedoncereload": {
                  this.needToBeClosedOnceReload = Boolean.parseBoolean(val);
                  break;
               }
               case "isdung": {
                  this.isDung = Boolean.parseBoolean(val);
                  break;
               }
               case "manuallyremovespentrounds": {
                  this.manuallyRemoveSpentRounds = Boolean.parseBoolean(val);
                  break;
               }
               case "bitedefense": {
                  this.biteDefense = Float.parseFloat(val);
                  this.biteDefense = Math.min(this.biteDefense, 100.0F);
                  break;
               }
               case "bulletdefense": {
                  this.bulletDefense = Float.parseFloat(val);
                  this.bulletDefense = Math.min(this.bulletDefense, 100.0F);
                  break;
               }
               case "scratchdefense": {
                  this.scratchDefense = Float.parseFloat(val);
                  this.scratchDefense = Math.min(this.scratchDefense, 100.0F);
                  break;
               }
               case "neckprotectionmodifier": {
                  this.neckProtectionModifier = Float.parseFloat(val);
                  break;
               }
               case "damagecategory": {
                  this.damageCategory = val;
                  break;
               }
               case "firemode": {
                  this.fireMode = val;
                  break;
               }
               case "damagemakehole": {
                  this.damageMakeHole = Boolean.parseBoolean(val);
                  break;
               }
               case "equippednosprint": {
                  this.equippedNoSprint = Boolean.parseBoolean(val);
                  break;
               }
               case "corpsesicknessdefense": {
                  this.corpseSicknessDefense = Float.parseFloat(val);
                  break;
               }
               case "weaponreloadtype": {
                  this.weaponReloadType = val;
                  break;
               }
               case "insertallbulletsreload": {
                  this.insertAllBulletsReload = Boolean.parseBoolean(val);
                  break;
               }
               case "clothingitemextraoption": {
                  this.clothingItemExtraOption = new ArrayList<>();
                  String[] s = val.split(";");
                  this.clothingItemExtraOption.addAll(Arrays.asList(s));
                  break;
               }
               case "conditionlowerchanceonein": {
                  this.conditionLowerChance = Integer.parseInt(val);
                  break;
               }
               case "headconditionlowerchancemultiplier": {
                  this.headConditionLowerChanceMultiplier = Float.parseFloat(val);
                  break;
               }
               case "multiplehitconditionaffected": {
                  this.multipleHitConditionAffected = val.equalsIgnoreCase("true");
                  break;
               }
               case "canbandage": {
                  this.canBandage = val.equalsIgnoreCase("true");
                  break;
               }
               case "conditionmax": {
                  this.conditionMax = Integer.parseInt(val);
                  break;
               }
               case "soundgain": {
                  this.soundGain = Float.parseFloat(val);
                  break;
               }
               case "mindamage": {
                  this.minDamage = Float.parseFloat(val);
                  break;
               }
               case "minimumswingtime": {
                  this.minimumSwingTime = Float.parseFloat(val);
                  break;
               }
               case "swingsound": {
                  this.swingSound = val;
                  break;
               }
               case "replaceonuse": {
                  this.replaceOnUse = val;
                  break;
               }
               case "weaponsprite": {
                  this.weaponSprite = val;
                  break;
               }
               case "weaponspritesbyindex": {
                  this.weaponSpritesByIndex = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     this.weaponSpritesByIndex.add(split[i].trim());
                  }
                  break;
               }
               case "aimingperkcritmodifier": {
                  this.aimingPerkCritModifier = Integer.parseInt(val);
                  break;
               }
               case "aimingperkrangemodifier": {
                  this.aimingPerkRangeModifier = Float.parseFloat(val);
                  break;
               }
               case "aimingperkhitchancemodifier": {
                  this.aimingPerkHitChanceModifier = Float.parseFloat(val);
                  break;
               }
               case "projectilespreadmodifier": {
                  this.projectileSpreadModifier = Float.parseFloat(val);
                  break;
               }
               case "weightmodifier": {
                  this.weightModifier = Float.parseFloat(val);
                  break;
               }
               case "aimingperkminanglemodifier": {
                  this.aimingPerkMinAngleModifier = Float.parseFloat(val);
                  break;
               }
               case "hitchance": {
                  this.hitChance = Integer.parseInt(val);
                  break;
               }
               case "recoildelay": {
                  this.recoilDelay = Integer.parseInt(val);
                  break;
               }
               case "stoppower": {
                  this.stopPower = Float.parseFloat(val);
                  break;
               }
               case "piercingbullets": {
                  this.piercingBullets = val.equalsIgnoreCase("true");
                  break;
               }
               case "anglefalloff": {
                  this.angleFalloff = val.equalsIgnoreCase("true");
                  break;
               }
               case "soundvolume": {
                  this.soundVolume = Integer.parseInt(val);
                  break;
               }
               case "tohitmodifier": {
                  this.toHitModifier = Float.parseFloat(val);
                  break;
               }
               case "soundradius": {
                  this.soundRadius = Integer.parseInt(val);
                  break;
               }
               case "categories": {
                  String[] s = val.split(";");

                  for (int n = 0; n < s.length; n++) {
                     this.weaponCategories.add(WeaponCategory.get(ResourceLocation.of(s[n].trim())));
                  }
                  break;
               }
               case "tags": {
                  String[] s = val.split(";");

                  for (int n = 0; n < s.length; n++) {
                     String tag = s[n].trim();
                     this.itemTags.add(ItemTag.get(ResourceLocation.of(tag)));
                  }
                  break;
               }
               case "othercharactervolumeboost": {
                  this.otherCharacterVolumeBoost = Float.parseFloat(val);
                  break;
               }
               case "impactsound": {
                  this.impactSound = val;
                  if (this.impactSound.equals("null")) {
                     this.impactSound = null;
                  }
                  break;
               }
               case "swingtime": {
                  this.swingTime = Float.parseFloat(val);
                  break;
               }
               case "knockbackonnodeath": {
                  this.knockBackOnNoDeath = val.equalsIgnoreCase("true");
                  break;
               }
               case "alcoholic": {
                  this.alcoholic = val.equalsIgnoreCase("true");
                  break;
               }
               case "splatbloodonnodeath": {
                  this.splatBloodOnNoDeath = val.equalsIgnoreCase("true");
                  break;
               }
               case "swingamountbeforeimpact": {
                  this.swingAmountBeforeImpact = Float.parseFloat(val);
                  break;
               }
               case "ammotype": {
                  this.ammoType = AmmoType.get(ResourceLocation.of(val));
                  break;
               }
               case "maxammo": {
                  this.maxAmmo = Integer.parseInt(val);
                  break;
               }
               case "guntype": {
                  this.gunType = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     this.gunType.add(split[i].trim());
                  }
                  break;
               }
               case "hitanglemod": {
                  this.hitAngleMod = Float.parseFloat(val);
                  break;
               }
               case "otherhandrequire": {
                  this.otherHandRequire = ItemTag.get(ResourceLocation.of(val));
                  break;
               }
               case "alwayswelcomegift": {
                  this.alwaysWelcomeGift = val.equalsIgnoreCase("true");
                  break;
               }
               case "cantattackwithlowestendurance": {
                  this.cantAttackWithLowestEndurance = val.equalsIgnoreCase("true");
                  break;
               }
               case "endurancemod": {
                  this.enduranceMod = Float.parseFloat(val);
                  break;
               }
               case "knockdownmod": {
                  this.knockdownMod = Float.parseFloat(val);
                  break;
               }
               case "doordamage": {
                  this.doorDamage = Integer.parseInt(val);
                  break;
               }
               case "maxhitcount": {
                  this.maxHitCount = Integer.parseInt(val);
                  break;
               }
               case "physicsobject": {
                  this.physicsObject = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "count": {
                  this.count = Integer.parseInt(val);
                  break;
               }
               case "weaponweight": {
                  this.weaponWeight = Float.parseFloat(val);
                  break;
               }
               case "idleanim": {
                  this.idleAnim = val;
                  break;
               }
               case "runanim": {
                  this.runAnim = val;
                  break;
               }
               case "requireinhandorinventory": {
                  this.requireInHandOrInventory = new ArrayList<>(Arrays.asList(val.split("/")));
                  break;
               }
               case "firemodepossibilities": {
                  this.fireModePossibilities = new ArrayList<>(Arrays.asList(val.split("/")));
                  break;
               }
               case "cyclicratemultiplier": {
                  this.cyclicRateMultiplier = Float.parseFloat(val);
                  break;
               }
               case "attachmentsprovided": {
                  this.attachmentsProvided = new ArrayList<>(Arrays.asList(val.split(";")));
                  break;
               }
               case "attachmentreplacement": {
                  this.attachmentReplacement = val.trim();
                  break;
               }
               case "pushbackmod": {
                  this.pushBackMod = Float.parseFloat(val);
                  break;
               }
               case "npcsoundboost": {
                  this.npcSoundBoost = Float.parseFloat(val);
                  break;
               }
               case "splatnumber": {
                  this.splatNumber = Integer.parseInt(val);
                  break;
               }
               case "rangefalloff": {
                  this.rangeFalloff = val.equalsIgnoreCase("true");
                  break;
               }
               case "useendurance": {
                  this.useEndurance = val.equalsIgnoreCase("true");
                  break;
               }
               case "shareendurance": {
                  this.shareEndurance = val.equalsIgnoreCase("true");
                  break;
               }
               case "alwaysknockdown": {
                  this.alwaysKnockdown = val.equalsIgnoreCase("true");
                  break;
               }
               case "isaimedfirearm": {
                  this.isAimedFirearm = val.equalsIgnoreCase("true");
                  break;
               }
               case "bulletoutsound": {
                  this.bulletOutSound = val.trim();
                  break;
               }
               case "shellfallsound": {
                  this.shellFallSound = val.trim();
                  break;
               }
               case "dropsound": {
                  this.dropSound = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "soundmap": {
                  String[] ss = val.split("\\s+");
                  if (ss.length == 2 && !ss[0].trim().isEmpty()) {
                     if (this.soundMap == null) {
                        this.soundMap = new HashMap<>();
                     }

                     this.soundMap.put(ss[0].trim(), ss[1].trim());
                  }
                  break;
               }
               case "isaimedhandweapon": {
                  this.isAimedHandWeapon = val.equalsIgnoreCase("true");
                  break;
               }
               case "aimingmod": {
                  this.aimingMod = Float.parseFloat(val);
                  break;
               }
               case "projectilecount": {
                  this.projectileCount = Integer.parseInt(val);
                  break;
               }
               case "projectilespread": {
                  this.projectileSpread = Float.parseFloat(val);
                  break;
               }
               case "projectileweightcenter": {
                  this.projectileWeightCenter = Float.parseFloat(val);
                  break;
               }
               case "muzzleflashmodelkey": {
                  this.muzzleFlashModelKey = new ModelKey(val);
                  break;
               }
               case "canstack": {
                  this.canStack = val.equalsIgnoreCase("true");
                  break;
               }
               case "herbalisttype": {
                  this.herbalistType = val.trim();
                  break;
               }
               case "canbarricade": {
                  this.canBarricade = val.equalsIgnoreCase("true");
                  break;
               }
               case "usewhileequipped": {
                  this.useWhileEquipped = val.equalsIgnoreCase("true");
                  break;
               }
               case "usewhileunequipped": {
                  this.useWhileUnequipped = val.equalsIgnoreCase("true");
                  break;
               }
               case "ticksperequipuse": {
                  this.ticksPerEquipUse = Integer.parseInt(val.trim());
                  break;
               }
               case "disappearonuse": {
                  this.disappearOnUse = val.equalsIgnoreCase("true");
                  break;
               }
               case "temperature": {
                  this.temperature = Float.parseFloat(val);
                  break;
               }
               case "insulation": {
                  this.insulation = Float.parseFloat(val);
                  break;
               }
               case "windresistance": {
                  this.windresist = Float.parseFloat(val);
                  break;
               }
               case "waterresistance": {
                  this.waterresist = Float.parseFloat(val);
                  break;
               }
               case "closekillmove": {
                  this.closeKillMove = val.trim();
                  break;
               }
               case "usedelta": {
                  this.useDelta = Float.parseFloat(val);
                  break;
               }
               case "torchdot": {
                  this.torchDot = Float.parseFloat(val);
                  break;
               }
               case "numberofpages": {
                  this.numberOfPages = Integer.parseInt(val.trim());
                  break;
               }
               case "skilltrained": {
                  this.skillTrained = val.trim();
                  break;
               }
               case "lvlskilltrained": {
                  this.lvlSkillTrained = Integer.parseInt(val.trim());
                  break;
               }
               case "numlevelstrained": {
                  this.numLevelsTrained = Integer.parseInt(val.trim());
                  break;
               }
               case "capacity": {
                  int capacity = Integer.parseInt(val.trim());
                  if (capacity > 50.0F - this.actualWeight) {
                     capacity = (int)(50.0F - this.actualWeight);
                  }

                  this.capacity = capacity;
                  break;
               }
               case "maxitemsize": {
                  this.maxItemSize = Float.parseFloat(val.trim());
                  break;
               }
               case "maxcapacity": {
                  this.maxCapacity = Integer.parseInt(val.trim());
                  break;
               }
               case "itemcapacity": {
                  this.itemCapacity = Integer.parseInt(val.trim());
                  break;
               }
               case "conditionaffectscapacity": {
                  this.conditionAffectsCapacity = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "brakeforce": {
                  this.brakeForce = Integer.parseInt(val.trim());
                  break;
               }
               case "durability": {
                  this.durability = Float.parseFloat(val.trim());
                  break;
               }
               case "chancetospawndamaged": {
                  this.chanceToSpawnDamaged = Integer.parseInt(val.trim());
                  break;
               }
               case "weaponlength": {
                  this.weaponLength = Float.parseFloat(val.trim());
                  break;
               }
               case "clipsize": {
                  this.clipSize = Integer.parseInt(val.trim());
                  break;
               }
               case "reloadtime": {
                  this.reloadTime = Integer.parseInt(val.trim());
                  break;
               }
               case "aimingtime": {
                  this.aimingTime = Integer.parseInt(val.trim());
                  break;
               }
               case "aimingtimemodifier": {
                  this.aimingTimeModifier = Integer.parseInt(val.trim());
                  break;
               }
               case "reloadtimemodifier": {
                  this.reloadTimeModifier = Integer.parseInt(val.trim());
                  break;
               }
               case "hitchancemodifier": {
                  this.hitChanceModifier = Integer.parseInt(val.trim());
                  break;
               }
               case "weightreduction": {
                  this.weightReduction = Integer.parseInt(val.trim());
                  break;
               }
               case "canbeequipped": {
                  this.canBeEquipped = ItemBodyLocation.get(ResourceLocation.of(val.trim()));
                  break;
               }
               case "subcategory": {
                  this.subCategory = val.trim();
                  break;
               }
               case "activateditem": {
                  this.activatedItem = val.equalsIgnoreCase("true");
                  break;
               }
               case "protectfromrainwhenequipped": {
                  this.protectFromRainWhenEquipped = val.equalsIgnoreCase("true");
                  break;
               }
               case "lightstrength": {
                  this.lightStrength = Float.parseFloat(val.trim());
                  break;
               }
               case "torchcone": {
                  this.torchCone = val.equalsIgnoreCase("true");
                  break;
               }
               case "lightdistance": {
                  this.lightDistance = Integer.parseInt(val.trim());
                  break;
               }
               case "twohandweapon": {
                  this.twoHandWeapon = val.equalsIgnoreCase("true");
                  break;
               }
               case "tooltip": {
                  this.tooltip = val.trim();
                  break;
               }
               case "displaycategory": {
                  this.displayCategory = val.trim();
                  break;
               }
               case "badinmicrowave": {
                  this.badInMicrowave = val.equalsIgnoreCase("true");
                  break;
               }
               case "goodhot": {
                  this.goodHot = val.equalsIgnoreCase("true");
                  break;
               }
               case "badcold": {
                  this.badCold = val.equalsIgnoreCase("true");
                  break;
               }
               case "alarmsound": {
                  this.alarmSound = val.trim();
                  break;
               }
               case "requiresequippedbothhands": {
                  this.requiresEquippedBothHands = val.equalsIgnoreCase("true");
                  break;
               }
               case "replaceoncooked": {
                  this.replaceOnCooked = Arrays.asList(val.trim().split(";"));
                  break;
               }
               case "customcontextmenu": {
                  this.customContextMenu = val.trim();
                  break;
               }
               case "trap": {
                  this.trap = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "wet": {
                  this.isWet = val.equalsIgnoreCase("true");
                  break;
               }
               case "wetcooldown": {
                  this.wetCooldown = Float.parseFloat(val.trim());
                  break;
               }
               case "itemwhendry": {
                  this.itemWhenDry = val.trim();
                  break;
               }
               case "fishinglure": {
                  this.fishingLure = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "canbewrite": {
                  this.canBeWrite = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "pagetowrite": {
                  this.pageToWrite = Integer.parseInt(val.trim());
                  break;
               }
               case "spice": {
                  this.spice = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "removenegativeeffectoncooked": {
                  this.removeNegativeEffectOnCooked = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "clipsizemodifier": {
                  this.clipSizeModifier = Integer.parseInt(val);
                  break;
               }
               case "recoildelaymodifier": {
                  this.recoilDelayModifier = Float.parseFloat(val);
                  break;
               }
               case "maxrangemodifier": {
                  this.maxRangeModifier = Float.parseFloat(val);
                  break;
               }
               case "minsightrange": {
                  this.minSightRange = Float.parseFloat(val);
                  break;
               }
               case "maxsightrange": {
                  this.maxSightRange = Float.parseFloat(val);
                  break;
               }
               case "lowlightbonus": {
                  this.lowLightBonus = Float.parseFloat(val);
                  break;
               }
               case "damagemodifier": {
                  this.damageModifier = Float.parseFloat(val);
                  break;
               }
               case "map": {
                  this.map = val.trim();
                  break;
               }
               case "putinsound": {
                  this.putInSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "placeonesound": {
                  this.placeOneSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "placemultiplesound": {
                  this.placeMultipleSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "closesound": {
                  this.closeSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "opensound": {
                  this.openSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "breaksound": {
                  this.breakSound = val.trim();
                  break;
               }
               case "treedamage": {
                  this.treeDamage = Integer.parseInt(val);
                  break;
               }
               case "customeatsound": {
                  this.customEatSound = val.trim();
                  break;
               }
               case "fillfromdispensersound": {
                  this.fillFromDispenserSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "fillfromlakesound": {
                  this.fillFromLakeSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "fillfromtapsound": {
                  this.fillFromTapSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "fillfromtoiletsound": {
                  this.fillFromToiletSound = StringUtils.discardNullOrWhitespace(val.trim());
                  break;
               }
               case "alcoholpower": {
                  this.alcoholPower = Float.parseFloat(val.trim());
                  break;
               }
               case "bandagepower": {
                  this.bandagePower = Float.parseFloat(val.trim());
                  break;
               }
               case "reduceinfectionpower": {
                  this.reduceInfectionPower = Float.parseFloat(val.trim());
                  break;
               }
               case "oncooked": {
                  this.onCooked = val.trim();
                  break;
               }
               case "onlyacceptcategory": {
                  this.onlyAcceptCategory = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "acceptitemfunction": {
                  this.acceptItemFunction = StringUtils.discardNullOrWhitespace(val);
                  break;
               }
               case "padlock": {
                  this.padlock = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "digitalpadlock": {
                  this.digitalPadlock = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "triggerexplosiontimer": {
                  this.triggerExplosionTimer = Integer.parseInt(val);
                  break;
               }
               case "sensorrange": {
                  this.sensorRange = Integer.parseInt(val);
                  break;
               }
               case "remoterange": {
                  this.remoteRange = Integer.parseInt(val);
                  break;
               }
               case "countdownsound": {
                  this.countDownSound = val.trim();
                  break;
               }
               case "explosionsound": {
                  this.explosionSound = val.trim();
                  break;
               }
               case "placedsprite": {
                  this.placedSprite = val.trim();
                  break;
               }
               case "explosiontimer": {
                  this.explosionTimer = Integer.parseInt(val);
                  break;
               }
               case "explosionduration": {
                  this.explosionDuration = Integer.parseInt(val);
                  break;
               }
               case "explosionrange": {
                  this.explosionRange = Integer.parseInt(val);
                  break;
               }
               case "explosionpower": {
                  this.explosionPower = Integer.parseInt(val);
                  break;
               }
               case "firerange": {
                  this.fireRange = Integer.parseInt(val);
                  break;
               }
               case "firestartingenergy": {
                  this.fireStartingEnergy = Integer.parseInt(val);
                  break;
               }
               case "firestartingchance": {
                  this.fireStartingChance = Integer.parseInt(val);
                  break;
               }
               case "canbeplaced": {
                  this.canBePlaced = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "canbereused": {
                  this.canBeReused = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "canberemote": {
                  this.canBeRemote = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "remotecontroller": {
                  this.remoteController = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "smokerange": {
                  this.smokeRange = Integer.parseInt(val);
                  break;
               }
               case "noiserange": {
                  this.noiseRange = Integer.parseInt(val);
                  break;
               }
               case "noiseduration": {
                  this.noiseDuration = Integer.parseInt(val);
                  break;
               }
               case "extradamage": {
                  this.extraDamage = Float.parseFloat(val);
                  break;
               }
               case "twoway": {
                  this.twoWay = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "transmitrange": {
                  this.transmitRange = Integer.parseInt(val);
                  break;
               }
               case "micrange": {
                  this.micRange = Integer.parseInt(val);
                  break;
               }
               case "basevolumerange": {
                  this.baseVolumeRange = Float.parseFloat(val);
                  break;
               }
               case "isportable": {
                  this.isPortable = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "istelevision": {
                  this.isTelevision = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "minchannel": {
                  this.minChannel = Integer.parseInt(val);
                  break;
               }
               case "maxchannel": {
                  this.maxChannel = Integer.parseInt(val);
                  break;
               }
               case "usesbattery": {
                  this.usesBattery = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "ishightier": {
                  this.isHighTier = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "worldobjectsprite": {
                  this.worldObjectSprite = val.trim();
                  break;
               }
               case "flureduction": {
                  this.fluReduction = Integer.parseInt(val);
                  break;
               }
               case "foodsicknesschange": {
                  this.foodSicknessChange = Integer.parseInt(val);
                  break;
               }
               case "inversecoughprobability": {
                  this.inverseCoughProbability = Integer.parseInt(val);
                  break;
               }
               case "inversecoughprobabilitysmoker": {
                  this.inverseCoughProbabilitySmoker = Integer.parseInt(val);
                  break;
               }
               case "painreduction": {
                  this.painReduction = Integer.parseInt(val);
                  break;
               }
               case "colorred": {
                  this.colorRed = Integer.parseInt(val);
                  break;
               }
               case "colorgreen": {
                  this.colorGreen = Integer.parseInt(val);
                  break;
               }
               case "colorblue": {
                  this.colorBlue = Integer.parseInt(val);
                  break;
               }
               case "calories": {
                  this.calories = Float.parseFloat(val);
                  break;
               }
               case "carbohydrates": {
                  this.carbohydrates = Float.parseFloat(val);
                  break;
               }
               case "lipids": {
                  this.lipids = Float.parseFloat(val);
                  break;
               }
               case "proteins": {
                  this.proteins = Float.parseFloat(val);
                  break;
               }
               case "packaged": {
                  this.packaged = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "cantbefrozen": {
                  this.cantBeFrozen = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "evolvedrecipename": {
                  Translator.setDefaultItemEvolvedRecipeName(this.getFullName(), val);
                  this.evolvedRecipeName = Translator.getItemEvolvedRecipeName(this.getFullName());
                  break;
               }
               case "replaceonrotten": {
                  this.replaceOnRotten = val.trim();
                  break;
               }
               case "cantbeconsolided": {
                  this.cantBeConsolided = val.equalsIgnoreCase("true");
                  break;
               }
               case "oneat": {
                  this.onEat = val.trim();
                  break;
               }
               case "keepondeplete": {
                  this.disappearOnUse = !val.equalsIgnoreCase("true");
                  break;
               }
               case "vehicletype": {
                  this.vehicleType = Integer.parseInt(val);
                  break;
               }
               case "chancetofall": {
                  this.chanceToFall = Integer.parseInt(val);
                  break;
               }
               case "conditionloweroffroad": {
                  this.conditionLowerOffroad = Float.parseFloat(val);
                  break;
               }
               case "conditionlowerstandard": {
                  this.conditionLowerNormal = Float.parseFloat(val);
                  break;
               }
               case "wheelfriction": {
                  this.wheelFriction = Float.parseFloat(val);
                  break;
               }
               case "suspensiondamping": {
                  this.suspensionDamping = Float.parseFloat(val);
                  break;
               }
               case "suspensioncompression": {
                  this.suspensionCompression = Float.parseFloat(val);
                  break;
               }
               case "engineloudness": {
                  this.engineLoudness = Float.parseFloat(val);
                  break;
               }
               case "attachmenttype": {
                  this.attachmentType = val.trim();
                  break;
               }
               case "makeuptype": {
                  this.makeUpType = val.trim();
                  break;
               }
               case "consolidateoption": {
                  this.consolidateOption = val.trim();
                  break;
               }
               case "fabrictype": {
                  this.fabricType = val.trim();
                  break;
               }
               case "learnedrecipes": {
                  this.learnedRecipes = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     String name = split[i].trim();
                     this.learnedRecipes.add(name);
                     if (Translator.debug) {
                        Translator.getRecipeName(name);
                     }
                  }
                  break;
               }
               case "researchablerecipes": {
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     String name = split[i].trim();
                     this.addResearchableRecipe(name);
                     if (Translator.debug) {
                        Translator.getRecipeName(name);
                     }
                  }
                  break;
               }
               case "mounton": {
                  this.mountOn = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     this.mountOn.add(split[i].trim());
                  }
                  break;
               }
               case "parttype": {
                  this.partType = val;
                  break;
               }
               case "canattach": {
                  this.canAttachCallback = val;
                  break;
               }
               case "candetach": {
                  this.canDetachCallback = val;
                  break;
               }
               case "onattach": {
                  this.onAttachCallback = val;
                  break;
               }
               case "ondetach": {
                  this.onDetachCallback = val;
                  break;
               }
               case "clothingitem": {
                  this.clothingItem = val;
                  break;
               }
               case "evolvedrecipe": {
                  String[] split = val.split(";");
                  String[] s = val.split(";");

                  for (int n = 0; n < s.length; n++) {
                     this.evolvedRecipe.add(split[n].trim());
                  }

                  for (int i = 0; i < split.length; i++) {
                     String recipe = split[i];
                     int use = 0;
                     boolean cooked = false;
                     String recipeName;
                     if (!recipe.contains(":")) {
                        recipeName = recipe;
                     } else {
                        recipeName = recipe.split(":")[0];
                        String useStr = recipe.split(":")[1];
                        if (!useStr.contains("|")) {
                           use = Integer.parseInt(recipe.split(":")[1]);
                        } else {
                           String[] splittedUse = useStr.split("\\|");

                           for (int j = 0; j < splittedUse.length; j++) {
                              if ("Cooked".equals(splittedUse[j])) {
                                 cooked = true;
                              }
                           }

                           use = Integer.parseInt(splittedUse[0]);
                        }
                     }

                     if (recipeName.equals("RicePot") || recipeName.equals("RicePan")) {
                        recipeName = "Rice";
                     }

                     if (recipeName.equals("PastaPot") || recipeName.equals("PastaPan")) {
                        recipeName = "Pasta";
                     }

                     if (recipeName.equals("Roasted Vegetables")) {
                        recipeName = "Stir fry";
                     }

                     ItemRecipe itemRecipe = new ItemRecipe(this.name, this.getModule().getName(), use);
                     itemRecipe.cooked = cooked;
                     this.itemRecipeMap.put(recipeName, itemRecipe);
                  }
                  break;
               }
               case "staticmodel": {
                  this.staticModel = val.trim();
                  break;
               }
               case "worldstaticmodel": {
                  this.worldStaticModel = val.trim();
                  break;
               }
               case "primaryanimmask": {
                  this.primaryAnimMask = val.trim();
                  break;
               }
               case "secondaryanimmask": {
                  this.secondaryAnimMask = val.trim();
                  break;
               }
               case "primaryanimmaskattachment": {
                  this.primaryAnimMaskAttachment = val.trim();
                  break;
               }
               case "secondaryanimmaskattachment": {
                  this.secondaryAnimMaskAttachment = val.trim();
                  break;
               }
               case "replaceinsecondhand": {
                  this.replaceInSecondHand = val.trim();
                  break;
               }
               case "replaceinprimaryhand": {
                  this.replaceInPrimaryHand = val.trim();
                  break;
               }
               case "replacewhenunequip": {
                  this.replaceWhenUnequip = val.trim();
                  break;
               }
               case "eattype": {
                  this.eatType = val.trim();
                  break;
               }
               case "pourtype": {
                  this.pourType = val.trim();
                  break;
               }
               case "readtype": {
                  this.readType = val.trim();
                  break;
               }
               case "book_subject": {
                  for (String s : val.trim().split(";")) {
                     this.bookSubjects.add(BookSubject.get(ResourceLocation.of(s)));
                  }
                  break;
               }
               case "magazine_subject": {
                  for (String s : val.trim().split(";")) {
                     this.magazineSubjects.add(MagazineSubject.get(ResourceLocation.of(s)));
                  }
                  break;
               }
               case "digtype": {
                  this.digType = val.trim();
                  break;
               }
               case "iconsfortexture": {
                  this.iconsForTexture = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     this.iconsForTexture.add(split[i].trim());
                  }
                  break;
               }
               case "bloodlocation": {
                  this.bloodClothingType = new ArrayList<>();
                  String[] split = val.split(";");

                  for (int i = 0; i < split.length; i++) {
                     this.bloodClothingType.add(BloodClothingType.fromString(split[i].trim()));
                  }
                  break;
               }
               case "mediacategory": {
                  this.recordedMediaCat = val.trim();
                  break;
               }
               case "acceptmediatype": {
                  this.acceptMediaType = Byte.parseByte(val.trim());
                  break;
               }
               case "notransmit": {
                  this.noTransmit = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "worldrender": {
                  this.worldRender = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "canteat": {
                  this.cantEat = Boolean.parseBoolean(val.trim());
                  break;
               }
               case "obsolete": {
                  this.obsolete = val.trim().equalsIgnoreCase("true");
                  break;
               }
               case "oncreate": {
                  this.luaCreate = val.trim();
                  break;
               }
               case "openingrecipe": {
                  this.openingRecipe = val.trim();
                  break;
               }
               case "doubleclickrecipe": {
                  this.doubleClickRecipe = val.trim();
                  break;
               }
               case "itemconfig": {
                  this.itemConfigKey = val.trim();
                  break;
               }
               case "iconcolormask": {
                  this.iconColorMask = val.trim();
                  break;
               }
               case "iconfluidmask": {
                  this.iconFluidMask = val.trim();
                  break;
               }
               default: {
                  if (!param.trim().equalsIgnoreCase("GameEntityScript")) {
                     if (param.trim().equalsIgnoreCase("SoundParameter")) {
                        String[] ss = val.split("\\s+");
                        if (this.soundParameterMap == null) {
                           this.soundParameterMap = new HashMap<>();
                        }

                        this.soundParameterMap.put(ss[0].trim(), ss[1].trim());
                     } else if (param.trim().equalsIgnoreCase("VehiclePartModel")) {
                        this.DoParam_VehiclePartModel(val);
                     } else if (param.trim().equalsIgnoreCase("withDrainable")) {
                        this.withDrainable = val;
                     } else if (param.trim().equalsIgnoreCase("withoutDrainable")) {
                        this.withoutDrainable = val;
                     } else if (param.trim().equalsIgnoreCase("staticModelsByIndex")) {
                        this.staticModelsByIndex = new ArrayList<>();
                        String[] split = val.split(";");

                        for (int i = 0; i < split.length; i++) {
                           this.staticModelsByIndex.add(split[i].trim());
                        }
                     } else if (param.trim().equalsIgnoreCase("worldStaticModelsByIndex")) {
                        this.worldStaticModelsByIndex = new ArrayList<>();
                        String[] split = val.split(";");

                        for (int i = 0; i < split.length; i++) {
                           this.worldStaticModelsByIndex.add(split[i].trim());
                        }
                     } else if (param.trim().equalsIgnoreCase("spawnWith")) {
                        this.spawnWith = val;
                     } else if (param.trim().equalsIgnoreCase("visionModifier")) {
                        this.visionModifier = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("hearingModifier")) {
                        this.hearingModifier = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("strainModifier")) {
                        this.strainModifier = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("OnBreak")) {
                        this.onBreak = val.trim();
                     } else if (param.trim().equalsIgnoreCase("DamagedSound")) {
                        this.damagedSound = val.trim();
                     } else if (param.trim().equalsIgnoreCase("BulletHitArmourSound")) {
                        this.bulletHitArmourSound = StringUtils.discardNullOrWhitespace(val.trim());
                     } else if (param.trim().equalsIgnoreCase("WeaponHitArmourSound")) {
                        this.weaponHitArmourSound = StringUtils.discardNullOrWhitespace(val.trim());
                     } else if (param.trim().equalsIgnoreCase("ShoutType")) {
                        this.shoutType = val.trim();
                     } else if (param.trim().equalsIgnoreCase("ShoutMultiplier")) {
                        this.shoutMultiplier = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("EatTime")) {
                        this.eatTime = Integer.parseInt(val);
                     } else if (param.trim().equalsIgnoreCase("VisualAid")) {
                        this.visualAid = Boolean.parseBoolean(val.trim());
                     } else if (param.trim().equalsIgnoreCase("DiscomfortModifier")) {
                        this.discomfortModifier = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("fireFuelRatio")) {
                        this.fireFuelRatio = Float.parseFloat(val);
                     } else if (param.trim().equalsIgnoreCase("ItemAfterCleaning")) {
                        this.itemAfterCleaning = val.trim();
                     } else {
                        DebugType.DetailedInfo
                           .trace(
                              "adding unknown item param \""
                                 + param.trim()
                                 + "\" = \""
                                 + val.trim()
                                 + "\", script: "
                                 + this.getScriptObjectFullType()
                                 + ", path: "
                                 + this.getFileAbsPath()
                           );
                        if (this.defaultModData == null) {
                           this.defaultModData = LuaManager.platform.newTable();
                        }

                        try {
                           Double tryConv = Double.parseDouble(val.trim());
                           this.defaultModData.rawset(param.trim(), tryConv);
                        } catch (Exception ex) {
                           this.defaultModData.rawset(param.trim(), val);
                        }
                     }
                  }
               }
            }
         }
      } catch (Exception ex) {
         throw new InvalidParameterException("Error: " + param.trim() + " is not a valid parameter in item: " + this.name);
      }
   }

   private void DoParam_VehiclePartModel(String val) {
      if (this.vehiclePartModels == null) {
         this.vehiclePartModels = new ArrayList<>();
      }

      String[] ss = val.split("\\s+");
      if (ss.length == 3) {
         VehiclePartModel vpm = new VehiclePartModel();
         vpm.partId = ss[0];
         vpm.partModelId = ss[1];
         vpm.modelId = ss[2];
         this.vehiclePartModels.add(vpm);
      }
   }

   public void OnScriptsLoaded(ScriptLoadMode loadMode) throws Exception {
      ArrayList<EvolvedRecipe> evolvedRecipes = ScriptManager.instance.getAllEvolvedRecipesList();

      for (Entry<String, ItemRecipe> entry : this.itemRecipeMap.entrySet()) {
         boolean found = false;
         EvolvedRecipe recipe = ScriptManager.instance.getEvolvedRecipe(entry.getKey());
         if (recipe != null) {
            recipe.itemsList.put(this.name, entry.getValue());
            found = true;
         }

         for (EvolvedRecipe r : evolvedRecipes) {
            if (r.template.equalsIgnoreCase(entry.getKey())) {
               r.itemsList.put(this.name, entry.getValue());
               found = true;
            }
         }

         if (!found) {
            DebugType.General.error("Could not find evolved recipe or template: '" + entry.getKey() + "' in item = " + this.getFullName());
            throw new InvalidParameterException("Could not find evolved recipe or template: '" + entry.getKey() + "' in item: " + this.getFullName());
         }
      }

      this.displayName = Translator.getItemNameFromFullType(this.getFullName());
      if (this.itemConfigKey != null) {
         ItemConfig itemConfig = ScriptManager.instance.getItemConfig(this.itemConfigKey);
         if (itemConfig == null) {
            throw new Exception("Cannot set item config '" + this.getItemConfigKey() + "' to item: " + this.getFullName());
         }

         this.setItemConfig(itemConfig);
      }

      super.OnScriptsLoaded(loadMode);
   }

   public int getLevelSkillTrained() {
      return this.lvlSkillTrained;
   }

   public int getNumLevelsTrained() {
      return this.numLevelsTrained;
   }

   public int getMaxLevelTrained() {
      return this.lvlSkillTrained == -1 ? -1 : this.lvlSkillTrained + this.numLevelsTrained;
   }

   public List<String> getLearnedRecipes() {
      return this.learnedRecipes;
   }

   public float getTemperature() {
      return this.temperature;
   }

   public void setTemperature(float temperature) {
      this.temperature = temperature;
   }

   public boolean isConditionAffectsCapacity() {
      return this.conditionAffectsCapacity;
   }

   public int getChanceToFall() {
      return this.chanceToFall;
   }

   public float getInsulation() {
      return this.insulation;
   }

   public void setInsulation(float f) {
      this.insulation = f;
   }

   public float getWindresist() {
      return this.windresist;
   }

   public void setWindresist(float w) {
      this.windresist = w;
   }

   public float getWaterresist() {
      return this.waterresist;
   }

   public void setWaterresist(float w) {
      this.waterresist = w;
   }

   public boolean getObsolete() {
      return this.obsolete;
   }

   public String getAcceptItemFunction() {
      return this.acceptItemFunction;
   }

   public ArrayList<BloodClothingType> getBloodClothingType() {
      return this.bloodClothingType;
   }

   public String toString() {
      return this.getClass().getSimpleName()
         + "{Module: "
         + (this.getModule() != null ? this.getModule().name : "null")
         + ", Name:"
         + this.name
         + ", ItemType:"
         + this.itemType
         + "}";
   }

   public String getReplaceWhenUnequip() {
      return this.replaceWhenUnequip;
   }

   public void resolveItemTypes() {
      if (this.ammoType != null && !this.hasTag(ItemTag.FAKE_WEAPON)) {
         String resolvedItemType = ScriptManager.instance.resolveItemType(this.getModule(), this.ammoType.getItemKey());
         if (resolvedItemType != null) {
            AmmoType.getByItemKey(resolvedItemType).ifPresent(ammoType -> IsoBulletTracerEffects.getInstance().load(ammoType));
         }
      }

      this.magazineType = ScriptManager.instance.resolveItemType(this.getModule(), this.magazineType);
      this.physicsObject = ScriptManager.instance.resolveItemType(this.getModule(), this.physicsObject);
      if (this.requireInHandOrInventory != null) {
         for (int i = 0; i < this.requireInHandOrInventory.size(); i++) {
            String type = this.requireInHandOrInventory.get(i);
            type = ScriptManager.instance.resolveItemType(this.getModule(), type);
            this.requireInHandOrInventory.set(i, type);
         }
      }

      if (this.replaceTypesMap != null) {
         for (String key : this.replaceTypesMap.keySet()) {
            String replaceType = this.replaceTypesMap.get(key);
            this.replaceTypesMap.replace(key, ScriptManager.instance.resolveItemType(this.getModule(), replaceType));
         }
      }
   }

   public void resolveModelScripts() {
      this.staticModel = ScriptManager.instance.resolveModelScript(this.getModule(), this.staticModel);
      this.worldStaticModel = ScriptManager.instance.resolveModelScript(this.getModule(), this.worldStaticModel);
   }

   public String getRecordedMediaCat() {
      return this.recordedMediaCat;
   }

   public Boolean isWorldRender() {
      return this.worldRender;
   }

   public Boolean isCantEat() {
      return this.cantEat;
   }

   public String getLuaCreate() {
      return this.luaCreate;
   }

   public void setLuaCreate(String functionName) {
      this.luaCreate = functionName;
   }

   public String getOpeningRecipe() {
      return this.openingRecipe;
   }

   public void setOpeningRecipe(String recipeName) {
      this.openingRecipe = recipeName;
   }

   public String getDoubleClickRecipe() {
      return this.doubleClickRecipe;
   }

   public void setDoubleClickRecipe(String recipeName) {
      this.doubleClickRecipe = recipeName;
   }

   public String getSoundParameter(String parameterName) {
      return this.soundParameterMap == null ? null : this.soundParameterMap.get(parameterName);
   }

   public ArrayList<VehiclePartModel> getVehiclePartModels() {
      return this.vehiclePartModels;
   }

   public String getItemConfigKey() {
      return this.itemConfigKey;
   }

   public float getR() {
      return this.colorRed / 255.0F;
   }

   public float getColorRed() {
      return this.getR();
   }

   public float getG() {
      return this.colorGreen / 255.0F;
   }

   public float getColorGreen() {
      return this.getG();
   }

   public float getB() {
      return this.colorBlue / 255.0F;
   }

   public float getColorBlue() {
      return this.getB();
   }

   public void setItemConfig(ItemConfig itemConfig) {
      this.itemConfig = itemConfig;
   }

   public ItemConfig getItemConfig() {
      return this.itemConfig;
   }

   public boolean hasTag(ItemTag... tags) {
      for (ItemTag tag : tags) {
         if (this.hasTag(tag)) {
            return true;
         }
      }

      return false;
   }

   public boolean hasTag(ItemTag itemTag) {
      return this.itemTags.contains(itemTag);
   }

   public float getCorpseSicknessDefense() {
      return this.corpseSicknessDefense;
   }

   public String getWithDrainable() {
      return this.withDrainable;
   }

   public String getWithoutDrainable() {
      return this.withoutDrainable;
   }

   public String getSpawnWith() {
      return this.spawnWith;
   }

   public ArrayList<String> getStaticModelsByIndex() {
      return this.staticModelsByIndex;
   }

   public ArrayList<String> getWorldStaticModelsByIndex() {
      return this.worldStaticModelsByIndex;
   }

   public ArrayList<String> getWeaponSpritesByIndex() {
      return this.weaponSpritesByIndex;
   }

   public float getVisionModifier() {
      return this.visionModifier;
   }

   public float getHearingModifier() {
      return this.hearingModifier;
   }

   public String getWorldObjectSprite() {
      return this.worldObjectSprite;
   }

   public float getStrainModifier() {
      return this.strainModifier;
   }

   public float getMaxItemSize() {
      return this.maxItemSize;
   }

   public String getOnBreak() {
      return this.onBreak;
   }

   public float getHeadConditionLowerChanceMultiplier() {
      return this.headConditionLowerChanceMultiplier;
   }

   public String getDamagedSound() {
      return this.damagedSound;
   }

   public String getBulletHitArmourSound() {
      return this.bulletHitArmourSound;
   }

   public String getWeaponHitArmourSound() {
      return this.weaponHitArmourSound;
   }

   public String getShoutType() {
      return this.shoutType;
   }

   public float getShoutMultiplier() {
      return this.shoutMultiplier;
   }

   public int getEatTime() {
      return this.eatTime;
   }

   public boolean isVisualAid() {
      return this.visualAid;
   }

   public float getDiscomfortModifier() {
      return SandboxOptions.instance.discomfortFactor.getValue() <= 0.0
         ? 0.0F
         : (float)(this.discomfortModifier * SandboxOptions.instance.discomfortFactor.getValue());
   }

   public float getPoisonPower() {
      return this.poisonPower;
   }

   public int getPoisonDetectionLevel() {
      return this.poisonDetectionLevel;
   }

   public String getHerbalistType() {
      return this.herbalistType;
   }

   public float getFireFuelRatio() {
      return this.fireFuelRatio;
   }

   public boolean isMementoLoot() {
      return this.hasTag(ItemTag.IS_MEMENTO) || Objects.equals(this.getDisplayCategory(), "Memento");
   }

   public boolean isCookwareLoot() {
      return Objects.equals(this.getDisplayCategory(), "CookingWeapon") || Objects.equals(this.getDisplayCategory(), "Cooking");
   }

   public boolean isMaterialLoot() {
      return Objects.equals(this.getDisplayCategory(), "MaterialWeapon") || Objects.equals(this.getDisplayCategory(), "Material");
   }

   public boolean isFarmingLoot() {
      return Objects.equals(this.getDisplayCategory(), "GardeningWeapon")
         || Objects.equals(this.getDisplayCategory(), "Gardening")
         || this.hasTag(ItemTag.FARMING_LOOT);
   }

   public boolean isToolLoot() {
      return Objects.equals(this.getDisplayCategory(), "ToolWeapon") || Objects.equals(this.getDisplayCategory(), "Tool");
   }

   public boolean isSurvivalGearLoot() {
      return this.survivalGear
         || Objects.equals(this.getDisplayCategory(), "FishingWeapon")
         || Objects.equals(this.getDisplayCategory(), "Fishing")
         || Objects.equals(this.getDisplayCategory(), "Trapping")
         || Objects.equals(this.getDisplayCategory(), "Camping")
         || Objects.equals(this.getDisplayCategory(), "FireSource");
   }

   public boolean isMedicalLoot() {
      return this.medical || Objects.equals(this.getDisplayCategory(), "FirstAid") || Objects.equals(this.getDisplayCategory(), "FirstAidWeapon");
   }

   public boolean isMechanicsLoot() {
      return this.mechanicsItem
         || Objects.equals(this.getDisplayCategory(), "VehicleMaintenance")
         || Objects.equals(this.getDisplayCategory(), "VehicleMaintenanceWeapon");
   }

   public String getLootType() {
      return ItemPickerJava.getLootType(this);
   }

   public boolean ignoreZombieDensity() {
      return this.itemType == ItemType.FOOD || this.hasTag(ItemTag.IGNORE_ZOMBIE_DENSITY) || this.isMementoLoot();
   }

   public boolean isSpice() {
      return this.itemType == ItemType.FOOD || this.itemType == ItemType.DRAINABLE && this.spice;
   }

   public String getItemAfterCleaning() {
      return this.itemAfterCleaning;
   }

   public ArrayList<String> getResearchableRecipes() {
      if (!this.isResearchableRecipesCheckedExtra && this.getClothingItemExtra() != null) {
         for (int i = 0; i < this.getClothingItemExtra().size(); i++) {
            String itemString = this.getClothingItemExtra().get(i);
            if (ScriptManager.instance.getItem(itemString) != null) {
               Item extraItem = ScriptManager.instance.getItem(itemString);
               if (extraItem.researchableRecipes != null) {
                  this.addResearchableRecipes(extraItem.researchableRecipes);
               }
            }
         }
      }

      if (!this.isResearchableRecipesCheckedExtra && this.getItemAfterCleaning() != null && ScriptManager.instance.getItem(this.getItemAfterCleaning()) != null
         )
       {
         Item extraItem = ScriptManager.instance.getItem(this.getItemAfterCleaning());
         if (extraItem.researchableRecipes != null) {
            this.addResearchableRecipes(extraItem.researchableRecipes);
         }
      }

      this.isResearchableRecipesCheckedExtra = true;
      return this.researchableRecipes;
   }

   public ArrayList<String> getResearchableRecipes(IsoGameCharacter chr) {
      return this.getResearchableRecipes(chr, true);
   }

   public ArrayList<String> getResearchableRecipes(IsoGameCharacter chr, boolean blacklistKnown) {
      ArrayList<String> recipes = this.getResearchableRecipes();
      if (recipes.isEmpty()) {
         return recipes;
      }

      ArrayList<String> characterRecipes = new ArrayList<>();

      for (int i = 0; i < recipes.size(); i++) {
         String recipe = recipes.get(i);
         if (recipe != null && ScriptManager.instance.getCraftRecipe(recipe) != null) {
            CraftRecipe craftRecipe = ScriptManager.instance.getCraftRecipe(recipe);
            if (craftRecipe.canResearch(chr, blacklistKnown)) {
               characterRecipes.add(recipe);
            }
         }
      }

      return characterRecipes;
   }

   public boolean hasResearchableRecipes() {
      return this.researchableRecipes != null && !this.researchableRecipes.isEmpty();
   }

   public void addResearchableRecipe(String recipeName) {
      if (recipeName != null && !this.researchableRecipes.contains(recipeName)) {
         if (ScriptManager.instance.getCraftRecipe(recipeName) != null) {
            this.addResearchableRecipe(ScriptManager.instance.getCraftRecipe(recipeName));
         } else {
            this.researchableRecipes.add(recipeName);
         }
      }
   }

   public void addResearchableRecipes(ArrayList<String> recipeNames) {
      if (recipeNames != null && !recipeNames.isEmpty()) {
         for (int i = 0; i < recipeNames.size(); i++) {
            this.addResearchableRecipe(recipeNames.get(i));
         }
      }
   }

   public void addResearchableRecipe(CraftRecipe craftRecipe) {
      if (craftRecipe != null && !this.researchableRecipes.contains(craftRecipe.getName())) {
         if (craftRecipe.canBeResearched()) {
            this.researchableRecipes.add(craftRecipe.getName());
         }
      }
   }

   public boolean isCraftRecipeProduct() {
      if (!this.isCraftRecipeProduct && !this.isCraftRecipeProductCheckedExtra && this.getClothingItemExtra() != null) {
         for (int i = 0; i < this.getClothingItemExtra().size(); i++) {
            String itemString = this.getClothingItemExtra().get(i);
            if (ScriptManager.instance.getItem(itemString) != null) {
               Item extraItem = ScriptManager.instance.getItem(itemString);
               if (extraItem.isCraftRecipeProduct) {
                  this.setIsCraftRecipeProduct();
                  break;
               }
            }
         }
      }

      if (!this.isCraftRecipeProduct
         && !this.isCraftRecipeProductCheckedExtra
         && this.getItemAfterCleaning() != null
         && ScriptManager.instance.getItem(this.getItemAfterCleaning()) != null) {
         Item extraItem = ScriptManager.instance.getItem(this.getItemAfterCleaning());
         if (extraItem.isCraftRecipeProduct) {
            this.setIsCraftRecipeProduct();
         }
      }

      this.isCraftRecipeProductCheckedExtra = true;
      return this.isCraftRecipeProduct;
   }

   public void setIsCraftRecipeProduct(boolean isProduct) {
      this.isCraftRecipeProduct = isProduct;
   }

   public void setIsCraftRecipeProduct() {
      this.isCraftRecipeProduct = true;
   }

   public boolean canBeForaged() {
      if (!this.canBeForaged && !this.canBeForagedCheckedExtra && this.getClothingItemExtra() != null) {
         for (int i = 0; i < this.getClothingItemExtra().size(); i++) {
            String itemString = this.getClothingItemExtra().get(i);
            if (ScriptManager.instance.getItem(itemString) != null) {
               Item extraItem = ScriptManager.instance.getItem(itemString);
               if (extraItem.canBeForaged) {
                  this.setCanBeForaged(true);
                  break;
               }
            }
         }
      }

      if (!this.canBeForaged
         && !this.canBeForagedCheckedExtra
         && this.getItemAfterCleaning() != null
         && ScriptManager.instance.getItem(this.getItemAfterCleaning()) != null) {
         Item extraItem = ScriptManager.instance.getItem(this.getItemAfterCleaning());
         if (extraItem.canBeForaged) {
            this.setCanBeForaged(true);
         }
      }

      this.canBeForagedCheckedExtra = true;
      return this.canBeForaged;
   }

   public void setCanBeForaged(boolean canBe) {
      this.canBeForaged = canBe;
   }

   public void addForageFocusCategory(String categoryName) {
      this.forageFocusCategories.add(categoryName);
   }

   public void clearForageFocusCategories() {
      this.forageFocusCategories.clear();
   }

   public HashSet<String> getForageFocusCategories() {
      return this.forageFocusCategories;
   }

   public boolean canSpawnAsLoot() {
      if (!this.canSpawnAsLoot && !this.canSpawnAsLootCheckedExtra && this.getClothingItemExtra() != null) {
         for (int i = 0; i < this.getClothingItemExtra().size(); i++) {
            String itemString = this.getClothingItemExtra().get(i);
            if (ScriptManager.instance.getItem(itemString) != null) {
               Item extraItem = ScriptManager.instance.getItem(itemString);
               if (extraItem.canSpawnAsLoot) {
                  this.setCanSpawnAsLoot(true);
                  break;
               }
            }
         }
      }

      if (!this.canSpawnAsLoot
         && !this.canSpawnAsLootCheckedExtra
         && this.getItemAfterCleaning() != null
         && ScriptManager.instance.getItem(this.getItemAfterCleaning()) != null) {
         Item extraItem = ScriptManager.instance.getItem(this.getItemAfterCleaning());
         if (extraItem.canSpawnAsLoot) {
            this.setCanSpawnAsLoot(true);
         }
      }

      this.canSpawnAsLootCheckedExtra = true;
      return this.canSpawnAsLoot;
   }

   public void setCanSpawnAsLoot(boolean canSpawn) {
      this.canSpawnAsLoot = canSpawn;
   }

   public void researchRecipes(IsoGameCharacter character) {
      if (character != null) {
         ArrayList<String> researchList = this.getResearchableRecipes(character, true);
         if (!researchList.isEmpty()) {
            for (int i = 0; i < researchList.size(); i++) {
               String recipe = researchList.get(i);
               character.learnRecipe(recipe);
               if (ScriptManager.instance.getCraftRecipe(recipe) != null) {
                  CraftRecipe craftRecipe = ScriptManager.instance.getCraftRecipe(recipe);
                  HaloTextHelper.addGoodText(
                     (IsoPlayer)character,
                     Translator.getText("IGUI_HaloNote_LearnedRecipe", new Object[]{GlobalObject.getRecipeDisplayName(craftRecipe.getName())}),
                     "[br/]"
                  );
               }
            }
         }
      }
   }

   public boolean isUsedInRecipes() {
      return !this.getUsedInRecipes().isEmpty();
   }

   public boolean isUsedInRecipes(IsoGameCharacter character) {
      return !this.getUsedInRecipes(character).isEmpty();
   }

   public ArrayList<String> getUsedInRecipes() {
      return this.usedInRecipes;
   }

   public ArrayList<String> getUsedInRecipes(IsoGameCharacter character) {
      return this.getUsedInRecipes(character, new ArrayList<>());
   }

   public ArrayList<String> getUsedInRecipes(IsoGameCharacter character, ArrayList<String> recipesList) {
      recipesList.clear();
      if (this.usedInRecipes.isEmpty()) {
         return recipesList;
      }

      if (character == null) {
         return this.getUsedInRecipes();
      }

      for (int i = 0; i < this.getUsedInRecipes().size(); i++) {
         String recipe = this.getUsedInRecipes().get(i);
         CraftRecipe craftRecipe = ScriptManager.instance.getCraftRecipe(recipe);
         if (craftRecipe != null
            && (!craftRecipe.needToBeLearn() || SandboxOptions.instance.seeNotLearntRecipe.getValue() || character.isRecipeActuallyKnown(recipe))) {
            recipesList.add(recipe);
         }
      }

      return recipesList;
   }

   public ArrayList<String> getUsedInFavouriteRecipes(IsoPlayer player) {
      ArrayList<String> recipesList = new ArrayList<>();
      if (this.getUsedInRecipes().isEmpty()) {
         return recipesList;
      }

      ArrayList<String> testList = this.getUsedInRecipes(player, TL_getUsedInRecipes.get());

      for (int i = 0; i < testList.size(); i++) {
         String recipe = testList.get(i);
         if (player.isFavouriteRecipe(recipe)) {
            recipesList.add(recipe);
         }
      }

      return recipesList;
   }

   public boolean isFavouriteRecipeInput(IsoPlayer player) {
      ArrayList<String> testList = this.getUsedInRecipes(player, TL_getUsedInRecipes.get());

      for (int i = 0; i < testList.size(); i++) {
         String recipe = testList.get(i);
         if (player.isFavouriteRecipe(recipe)) {
            return true;
         }
      }

      return false;
   }

   public String getFileName() {
      return this.fileName;
   }

   public int getNumSpawned() {
      return InstanceTracker.get("Item Spawns", this.getScriptObjectFullType());
   }

   public boolean isUsedInBuildRecipes() {
      return this.isUsedInBuildRecipes;
   }

   public boolean isUsedInBuildRecipes(IsoGameCharacter character) {
      if (!this.isUsedInBuildRecipes()) {
         return false;
      }

      for (int i = 0; i < this.getUsedInRecipes().size(); i++) {
         String recipe = this.getUsedInRecipes().get(i);
         CraftRecipe buildRecipe = ScriptManager.instance.getBuildableRecipe(recipe);
         if (buildRecipe != null
            && (!buildRecipe.needToBeLearn() || SandboxOptions.instance.seeNotLearntRecipe.getValue() || character.isRecipeActuallyKnown(recipe))) {
            return true;
         }
      }

      return false;
   }

   public boolean isUnwanted(IsoPlayer player) {
      return player.isUnwanted(this.getScriptObjectFullType());
   }

   public void setUnwanted(IsoPlayer player, boolean unwanted) {
      player.setUnwanted(this.getScriptObjectFullType(), unwanted);
   }

   public void setUnwanted(IsoPlayer player) {
      this.setUnwanted(player, true);
   }

   public void setWanted(IsoPlayer player) {
      this.setUnwanted(player, false);
   }

   public ModelKey getMuzzleFlashModelKey() {
      return this.muzzleFlashModelKey;
   }

   public boolean isCantBeFrozen() {
      return this.cantBeFrozen;
   }

   public String getIconFluidMask() {
      return this.iconFluidMask;
   }
}
