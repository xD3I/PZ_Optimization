package zombie.characters.animals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.IndieGL;
import zombie.SandboxOptions;
import zombie.SoundManager;
import zombie.UsedFromLua;
import zombie.WorldSoundManager;
import zombie.ZomboidGlobals;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.WorldSoundManager.WorldSound;
import zombie.ai.states.animals.AnimalAlertedState;
import zombie.ai.states.animals.AnimalAttackState;
import zombie.ai.states.animals.AnimalClimbOverFenceState;
import zombie.ai.states.animals.AnimalEatState;
import zombie.ai.states.animals.AnimalFalldownState;
import zombie.ai.states.animals.AnimalFollowWallState;
import zombie.ai.states.animals.AnimalHitReactionState;
import zombie.ai.states.animals.AnimalIdleState;
import zombie.ai.states.animals.AnimalOnGroundState;
import zombie.ai.states.animals.AnimalPathFindState;
import zombie.ai.states.animals.AnimalWalkState;
import zombie.ai.states.animals.AnimalZoneState;
import zombie.characters.AnimalFootstepManager;
import zombie.characters.AnimalVocalsManager;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Position3D;
import zombie.characters.SurvivorDesc;
import zombie.characters.action.ActionGroup;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.characters.animals.datas.AnimalBreed;
import zombie.characters.animals.datas.AnimalData;
import zombie.characters.animals.datas.AnimalGrowStage;
import zombie.characters.animals.datas.AnimalBreed.Sound;
import zombie.characters.skills.PerkFactory.Perks;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.Translator;
import zombie.core.math.PZMath;
import zombie.core.properties.IsoPropertyType;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.advancedanimation.AnimationSet;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandles;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlotDescriptor;
import zombie.core.skinnedmodel.advancedanimation.LiveAnimNode;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.visual.AnimalVisual;
import zombie.core.skinnedmodel.visual.IAnimalVisual;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Food;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoButcherHook;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.Vector2ObjectPool;
import zombie.iso.Vector3;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.fboRenderChunk.FBORenderShadows;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.iso.objects.IsoHutch;
import zombie.iso.objects.IsoRadio;
import zombie.iso.objects.IsoTelevision;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.RainManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;
import zombie.popman.animal.AnimalInstanceManager;
import zombie.popman.animal.AnimalSynchronizationManager;
import zombie.scripting.objects.ModelAttachment;
import zombie.scripting.objects.ModelScript;
import zombie.ui.TextDrawObject;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.util.PZCalendar;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.BaseVehicle.HitVars;

@UsedFromLua
public class IsoAnimal extends IsoPlayer implements IAnimalVisual {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.animals.IsoAnimal");
   }

   public static final int INVALID_SQUARE_XY = Integer.MAX_VALUE;
   public static final float SOUND_RADIUS_MULTIPLIER_WILD = 3.0F;
   private static final float MIN_PLAYER_ACCEPTANCE_FOR_SOUND = 40.0F;
   private static final int FLEE_SOUND_DISTANCE_DEFAULT = 10;
   private static final int FLEE_SOUND_DISTANCE_WILD = 20;
   private static final float SOUND_RADIUS_STRESS_FACTOR = 0.05F;
   private static final float SOUND_RADIUS_FLEE_TIME_FACTOR = 2000.0F;
   private static final float LAST_ALERTED_FLEE_SOUND_TIME = 1000.0F;
   private static final long serialVersionUID = 1L;
   private static final Vector3f tempVector3f = new Vector3f();
   private static final Vector3 tempVector3 = new Vector3();
   public static final Vector2 tempVector2 = new Vector2();
   public int animalId = -1;
   public int itemId;
   public IsoMovingObject spottedChr;
   private String type;
   private BaseAnimalBehavior behavior;
   private AnimalData data;
   public long attackedTimer = -1L;
   private boolean invincible;
   public int attachBackToMother;
   private int attachBackToTreeX = Integer.MAX_VALUE;
   private int attachBackToTreeY = Integer.MAX_VALUE;
   public long timeSinceLastUpdate = -1L;
   private String customName;
   public boolean smallEnclosure;
   public AnimalDefinitions adef;
   public IsoAnimal mother;
   public int motherId;
   public int searchRadius = 3;
   private int milkRemoved;
   public IsoFeedingTrough eatFromTrough;
   public IsoWorldInventoryObject eatFromGround;
   public IsoFeedingTrough drinkFromTrough;
   public IsoGridSquare drinkFromRiver;
   public IsoGridSquare drinkFromPuddle;
   public IsoHutch hutch;
   public HashMap<String, AnimalGene> fullGenome = new HashMap<>();
   public IsoGameCharacter atkTarget;
   public IsoObject thumpTarget;
   public IsoGameCharacter fightingOpponent;
   private WorldSound lastSoundRespondedTo;
   private float timeSinceFleeFromSound;
   public float stressLevel;
   private final AnimalVisual animalVisual = new AnimalVisual(this);
   private AnimalZone animalZone;
   private boolean moveForwardOnZone;
   public int eggTimerInHutch;
   public int nestBox = -1;
   public HashMap<Short, Float> playerAcceptanceList = new HashMap<>();
   public IsoPlayer heldBy;
   public IsoPlayer luredBy;
   private float luredStartTimer = -1.0F;
   public boolean walkToCharLuring;
   public ArrayList<String> geneticDisorder = new ArrayList<>();
   private float petTimer;
   private DesignationZoneAnimal dZone;
   private final ArrayList<DesignationZoneAnimal> connectedDZone = new ArrayList<>();
   private float zoneCheckTimer;
   public InventoryItem movingToFood;
   public float movingToFoodTimer;
   private final HashMap<String, AnimalSoundState> animalSoundState = new HashMap<>();
   public ArrayList<IsoFeedingTrough> ignoredTrough = new ArrayList<>();
   public float attachBackToMotherTimer;
   public double virtualId;
   public String migrationGroup;
   public boolean wild;
   public boolean alerted;
   public IsoMovingObject alertedChr;
   public boolean fromMeta;
   private float thumpDelay = 20000.0F;
   private boolean shouldBeSkeleton;
   private ArrayList<IsoAnimal> babies;
   private float zoneAcceptance;
   public boolean followingWall;
   public boolean shouldFollowWall;
   private boolean onHook;
   private IsoButcherHook hook;
   public int attachBackToHookX;
   public int attachBackToHookY;
   public int attachBackToHookZ;
   private boolean roadKill;
   private int lastCellSavedToX = Integer.MIN_VALUE;
   private int lastCellSavedToY = Integer.MIN_VALUE;
   private boolean isAttackingOnClient;
   private static final Position3D L_renderCustomName = new Position3D();
   private String nextFootstepSound;
   private String forceNextIdleSound;

   public IsoAnimal(IsoCell cell) {
      this(cell, 0, 0, 0, null, "");
      this.registerVariableCallbacks();
      this.setDefaultState(AnimalIdleState.instance());
      this.addOnDiedListener(this::onDied, false);
      this.setCollidable(true);
      this.initAttachedItems("Animal");
   }

   public IsoAnimal(IsoCell cell, int x, int y, int z, String type, String breedName) {
      super(cell, new SurvivorDesc(), x, y, z, true);
      this.addOnDiedListener(this::onDied, false);
      if (!this.checkForChickenpocalypse(null) && !this.checkForWater()) {
         this.registerVariableCallbacks();
         this.setDefaultState(AnimalIdleState.instance());
         this.setCollidable(true);
         this.setIsAnimal(true);
         this.type = type;
         this.adef = AnimalDefinitions.getDef(this.getAnimalType());
         if (this.adef == null) {
            DebugType.Animal.debugln(type + " is not a valid Animal Type.");
         } else {
            AnimalBreed breed;
            if (!StringUtils.isNullOrEmpty(breedName)) {
               breed = this.adef.getBreedByName(breedName);
            } else {
               breed = this.adef.getRandomBreed();
            }

            this.init(breed);
            this.setDir(IsoDirections.getRandom());
            this.initAttachedItems("Animal");
         }
      }
   }

   public IsoAnimal(IsoCell cell, int x, int y, int z, String type, String breedName, boolean skeleton) {
      super(cell, new SurvivorDesc(), x, y, z, true);
      if (!this.checkForChickenpocalypse(null) && !this.checkForWater()) {
         this.shouldBeSkeleton = skeleton;
         this.registerVariableCallbacks();
         this.setDefaultState(AnimalIdleState.instance());
         this.setCollidable(true);
         this.setIsAnimal(true);
         this.type = type;
         AnimalBreed breed = null;
         if (!StringUtils.isNullOrEmpty(breedName)) {
            this.adef = AnimalDefinitions.getDef(this.getAnimalType());
            if (this.adef == null) {
               DebugType.Animal.debugln(type + " is not a valid Animal Type.");
               return;
            }

            breed = this.adef.getBreedByName(breedName);
         }

         this.init(breed);
         this.setDir(IsoDirections.getRandom());
         this.initAttachedItems("Animal");
      }
   }

   public boolean checkForChickenpocalypse(IsoAnimal replacingAnimal) {
      if (this.getSquare() == null) {
         return false;
      }

      for (int x = this.getSquare().getX() - 4; x < this.getSquare().getX() + 4; x++) {
         for (int y = this.getSquare().getY() - 4; y < this.getSquare().getY() + 4; y++) {
            IsoGridSquare sq = this.getSquare().getCell().getGridSquare(x, y, this.getSquare().getZ());
            if (sq != null) {
               for (IsoAnimal otherAnimal : sq.getAnimals()) {
                  if (otherAnimal != this && otherAnimal != replacingAnimal && otherAnimal.getAnimalID() == this.getAnimalID()) {
                     DebugType.Animal.println("Possible chickenpocalypse, deleting the newly created animal");
                     this.delete();
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   public boolean checkForWater() {
      return this.getSquare() != null && this.getSquare().isWaterSquare();
   }

   public IsoAnimal(IsoCell cell, int x, int y, int z, String type, AnimalBreed breed) {
      super(cell, new SurvivorDesc(), x, y, z, true);
      if (!this.checkForChickenpocalypse(null) && !this.checkForWater()) {
         this.registerVariableCallbacks();
         this.setDefaultState(AnimalIdleState.instance());
         this.setCollidable(true);
         this.setIsAnimal(true);
         this.type = type;
         this.init(breed);
         this.setDir(IsoDirections.getRandom());
         this.initAttachedItems("Animal");
      }
   }

   public IsoAnimal(IsoCell cell, int x, int y, int z, String type, AnimalBreed breed, boolean skeleton) {
      super(cell, new SurvivorDesc(), x, y, z, true);
      if (!this.checkForChickenpocalypse(null) && !this.checkForWater()) {
         this.shouldBeSkeleton = skeleton;
         this.registerVariableCallbacks();
         this.setDefaultState(AnimalIdleState.instance());
         this.setCollidable(true);
         this.setIsAnimal(true);
         this.type = type;
         this.init(breed);
         this.setDir(IsoDirections.getRandom());
         this.initAttachedItems("Animal");
      }
   }

   public String getObjectName() {
      return "Animal";
   }

   private void registerVariableCallbacks() {
      this.setVariable("bdead", this::isDead, IAnimationVariableSlotDescriptor.Null);
      this.setVariable("isAnimalEating", this::isAnimalEating, IAnimationVariableSlotDescriptor.Null);
      this.setVariable("isAnimalAttacking", this::isAnimalAttacking, IAnimationVariableSlotDescriptor.Null);
      this.setVariable("hasAnimalZone", this::hasAnimalZone, IAnimationVariableSlotDescriptor.Null);
      this.setVariable("isAlerted", this::isAlerted, IAnimationVariableSlotDescriptor.Null);
      this.setVariable("shouldFollowWall", this::shouldFollowWall, IAnimationVariableSlotDescriptor.Null);
   }

   public boolean canUseCurrentPoseForCorpse() {
      return !this.isOnHook() && super.canUseCurrentPoseForCorpse();
   }

   public AnimalVisual getAnimalVisual() {
      return this.animalVisual;
   }

   public void addToWorld() {
      super.addToWorld();
      if (this.isOnHook()) {
         this.setVariable("onhook", true);
      }
   }

   public String GetAnimSetName() {
      return this.adef == null ? "cow" : this.adef.animset;
   }

   public void playSoundDebug() {
      this.addWorldSoundUnlessInvisible(40, 30, false);
   }

   public void update() {
      if (this.isOnHook()) {
         this.reattachBackToHook();
         this.ensureCorrectSkin();
         if (GameServer.server) {
            AnimalInstanceManager.getInstance().update(this);
         }
      } else {
         if (this.getVariableBoolean("bPathfind") && this.getStateMachine().getCurrent() != AnimalPathFindState.instance()) {
            this.getStateMachine().changeState(AnimalPathFindState.instance(), null, false);
         }

         this.updateEmitter();
         if (this.getSquare() != null) {
            this.doDeferredMovement();
         }

         if (!this.isDead()) {
            this.updateInternal();
            if (GameTime.getInstance().getMultiplier() > 10.0F) {
               this.setTurnDelta(1.0F);
            } else {
               this.setTurnDelta(this.adef.turnDelta);
            }
         }
      }
   }

   private void updateZoneAcceptance() {
      if (!this.connectedDZone.isEmpty() && this.zoneAcceptance < 100.0F) {
         this.zoneAcceptance = this.zoneAcceptance + GameTime.getInstance().getMultiplier() / 1000.0F;
         if (this.zoneAcceptance > 100.0F) {
            this.zoneAcceptance = 100.0F;
         }
      }
   }

   public void test() {
      AnimationPlayer animPlayer = this.getAnimationPlayer();
      AnimationMultiTrack multiTrack = animPlayer.getMultiTrack();
      if (multiTrack != null && !multiTrack.getTracks().isEmpty()) {
         ((AnimationTrack)multiTrack.getTracks().getFirst()).setCurrentTimeValue(Rand.Next(100));
      }
   }

   private void updateInternal() {
      if (!this.fromMeta) {
         if (this.behavior != null && this.data != null) {
            if (GameClient.client) {
               this.width = this.adef.collisionSize * this.getAnimalSize();
               this.separate();
               this.reattachToTree();
               if (this.vehicle4testCollision != null && this.vehicle4testCollision.updateNetworkHitByVehicle(this)) {
                  super.update();
                  this.vehicle4testCollision = null;
               } else {
                  super.update();
               }
            } else {
               this.behavior.update();
               this.data.update();
               if (!this.isDead()) {
                  this.width = this.adef.collisionSize * this.getAnimalSize();
                  this.separate();
                  this.checkTreeExists();
                  this.reattachToTree();
                  this.checkZone();
                  this.reattachBackToMom();
                  this.respondToSound();
                  if (this.petTimer > 0.0F) {
                     this.petTimer = this.petTimer - GameTime.getInstance().getMultiplier();
                     if (this.petTimer < 0.0F) {
                        this.petTimer = 0.0F;
                     }
                  }

                  this.updateStress();
                  this.updateLured();
                  this.updateEmitter();
                  this.tryThump(null);
                  long pzoptLosT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
                  if (!pzopt.AnimalLosSkip.skip(this)) { // pzopt: animalLosSkip (f8-zomb3), an idle animal's walk that would change nothing is skipped
                     this.updateLOS();
                  } // pzopt
                  pzopt.GtAb.end(pzopt.GtAb.S_ANIMAL_LOS, pzoptLosT); // pzopt
                  if (this.vehicle4testCollision != null) {
                     this.setVehicleCollision(this.testCollideWithVehicles(this.vehicle4testCollision, null));
                     this.vehicle4testCollision = null;
                  }

                  super.update();
               }
            }
         }
      }
   }

   public boolean testCollideWithVehicles(BaseVehicle vehicle, HitVars hitVars) {
      if (!this.isAlive()) {
         return false;
      }

      if (vehicle.shouldCollideWithCharacters()) {
         Vector2 collisionPos = (Vector2)Vector2ObjectPool.get().alloc();
         if (vehicle.testCollisionWithCharacter(this, 0.3F, collisionPos) != null) {
            vehicle.hitAnimal(this);
            Vector3f impulse = new Vector3f();
            impulse.set(0.0F, 17.0F, 0.0F);
            vehicle.applyImpulseFromHitObject(this, 1.0F);
            if (hitVars != null) {
               hitVars.isPushedBack = true;
               hitVars.collision.set(collisionPos);
               hitVars.calc(this, vehicle);
            }

            Vector2ObjectPool.get().release(collisionPos);
            return true;
         }

         Vector2ObjectPool.get().release(collisionPos);
      }

      return false;
   }

   public float Hit(
      BaseVehicle vehicle,
      float speed,
      boolean isHitFromBehind,
      float hitDirX,
      float hitDirY,
      boolean pushedBack,
      float collisionPosOnVehicleX,
      float collisionPosOnVehicleY
   ) {
      tempVector2.set(hitDirX, hitDirY);
      return this.Hit(vehicle, speed, isHitFromBehind, tempVector2);
   }

   public float Hit(BaseVehicle vehicle, float speed, boolean isHitFromBehind, Vector2 hitDir) {
      float damage = 0.0F;
      this.setHitDir(hitDir);
      this.setHitForce(speed * 0.15F);
      int randSpeed = (int)(speed * 6.0F);
      this.setIsRoadKill(true);
      if (!this.isOnFloor() && this.getCurrentState() != AnimalOnGroundState.instance()) {
         damage = this.getHealth();
         if (!GameServer.server && !GameClient.client) {
            this.setHealth(0.0F);
         }
      } else {
         this.setHitReaction("Floor");
         if (!GameServer.server && !GameClient.client) {
            this.setHealth(0.0F);
         }
      }

      if (!GameServer.server && !GameClient.client) {
         this.addBloodFromVehicleImpact(speed);
      }

      return damage;
   }

   protected void onAnimPlayerCreated(AnimationPlayer animationPlayer) {
      super.onAnimPlayerCreated(animationPlayer);
      animationPlayer.setTwistBones(new String[]{"Bip01_Head"});
      animationPlayer.setCounterRotationBone("Bip01");
   }

   public boolean allowsTwist() {
      return false;
   }

   public float getPetTimer() {
      return this.petTimer;
   }

   protected boolean CanUsePathfindState() {
      return this.isLocalPlayer();
   }

   protected float getNetworkSpeedMul() {
      return 0.9F + 0.20000005F * IsoUtils.smoothstep(0.9F, 1.1F, this.getNetworkCharacterAI().prediction.speed);
   }

   private void reattachBackToMom() {
      if (this.attachBackToMother > 0 && this.getVehicle() != null) {
         for (int i = 0; i < this.getVehicle().getAnimals().size(); i++) {
            IsoAnimal mom = (IsoAnimal)this.getVehicle().getAnimals().get(i);
            if (mom.getAnimalID() == this.attachBackToMother) {
               this.setMother(mom);
               break;
            }
         }
      }

      if (this.isExistInTheWorld()) {
         if (this.attachBackToMother == 0 && (this.mother == null || !this.mother.isExistInTheWorld()) && this.motherId > 0) {
            this.attachBackToMother = this.motherId;
         }

         if (this.attachBackToMother > 0 && this.getVehicle() == null) {
            if (this.attachBackToMotherTimer < 50.0F) {
               this.attachBackToMotherTimer = this.attachBackToMotherTimer + GameTime.getInstance().getMultiplier();
               return;
            }

            this.attachBackToMotherTimer = 0.0F;

            for (int k = 0; k < this.connectedDZone.size(); k++) {
               if (this.findMotherAndAttach(this.connectedDZone.get(k).getAnimals())) {
                  return;
               }
            }

            this.findMotherAndAttach(this.getCell().getAnimals());
         }
      }
   }

   private boolean findMotherAndAttach(List<IsoAnimal> animals) {
      for (int i = 0; i < animals.size(); i++) {
         IsoAnimal animal = animals.get(i);
         if (animal.animalId == this.attachBackToMother) {
            this.setMother(animal);
            this.attachBackToMother = 0;
            this.motherId = this.mother.animalId;
            return true;
         }
      }

      return false;
   }

   private void checkZone() {
      if (this.zoneCheckTimer > 0.0F) {
         this.zoneCheckTimer = this.zoneCheckTimer - GameTime.getInstance().getMultiplier();
      } else {
         this.zoneCheckTimer = 2000.0F;
         DesignationZoneAnimal dZoneCurrent = DesignationZoneAnimal.getZoneF(this.getX(), this.getY(), this.getZ());
         this.setDZone(dZoneCurrent);
         this.connectedDZone.clear();
         DesignationZoneAnimal.getAllDZones(this.connectedDZone, this.dZone, null);
         if (!this.connectedDZone.isEmpty()) {
            this.setWild(false);
         }

         this.updateZoneAcceptance();
      }
   }

   public IsoGridSquare getRandomSquareInZone() {
      if (this.connectedDZone.isEmpty()) {
         return null;
      }

      DesignationZone zone = (DesignationZone)this.connectedDZone.get(Rand.Next(0, this.connectedDZone.size()));
      return zone.getRandomSquare();
   }

   public DesignationZone getZone() {
      return this.connectedDZone.isEmpty() ? null : (DesignationZone)this.connectedDZone.get(Rand.Next(0, this.connectedDZone.size()));
   }

   public void stopAllMovementNow() {
      this.setMoving(false);
      this.getPathFindBehavior2().reset();
      this.setPath2(null);
      this.getStateMachine().changeState(this.getDefaultState(), null, false);
      this.getData().resetEatingCheck();
   }

   public void cancelLuring() {
      this.stopAllMovementNow();
      if (this.luredBy != null) {
         this.luredBy.luredAnimals.remove(this);
      }

      this.luredBy = null;
      this.walkToCharLuring = false;
      this.luredStartTimer = -1.0F;
   }

   private void updateLured() {
      if (this.luredBy != null) {
         if (!this.luredBy.isLuringAnimals) {
            this.cancelLuring();
         } else if (this.walkToCharLuring) {
            if (!this.isAnimalMoving()) {
               if (!this.luredBy.getLuredAnimals().contains(this)) {
                  this.luredBy.getLuredAnimals().add(this);
               }

               this.setVariable(AnimationVariableHandles.animalRunning, false);
               this.pathToCharacter(this.luredBy);
            }
         } else {
            if (this.luredStartTimer > -1.0F) {
               this.luredStartTimer--;
               if (this.luredStartTimer == 0.0F) {
                  this.walkToCharLuring = true;
               }
            }
         }
      }
   }

   public void updateStress() {
      if (!this.isWild()) {
         boolean incStress = true;
         if (this.heldBy != null) {
            this.changeStress(-(GameTime.getInstance().getMultiplier() / 3000.0F));
            this.addAcceptance(this.heldBy, GameTime.getInstance().getMultiplier() / 10000.0F);
         }

         if (this.getVehicle() != null && this.getVehicle().getCurrentSpeedKmHour() > 5.0F) {
            this.changeStress(GameTime.getInstance().getMultiplier() / 50000.0F);
            incStress = false;
         }

         if (this.getStats().get(CharacterStat.HUNGER) > 0.8F || this.getStats().get(CharacterStat.THIRST) > 0.8F) {
            this.changeStress(GameTime.getInstance().getMultiplier() / 50000.0F);
            incStress = false;
         }

         if (this.shouldAnimalStressAboveGround()) {
            this.changeStress(GameTime.getInstance().getMultiplier() / 10000.0F);
            incStress = false;
         }

         if (this.adef.stressUnderRain && RainManager.isRaining() && this.getSquare() != null && !this.getSquare().haveRoof) {
            this.changeStress(GameTime.getInstance().getMultiplier() / 40000.0F);
            incStress = false;
         }

         if (incStress) {
            this.changeStress(-(GameTime.getInstance().getMultiplier() / 5500.0F));
         }
      }
   }

   private IsoObject getCanAttachAnimalObject(IsoGridSquare square) {
      if (square == null) {
         return null;
      }

      IsoObject tree = square.getTree();
      if (tree != null) {
         return tree;
      }

      for (int i = 0; i < square.getSpecialObjects().size(); i++) {
         IsoObject obj = (IsoObject)square.getSpecialObjects().get(i);
         if (obj.getProperties() != null && obj.getProperties().has(IsoPropertyType.CAN_ATTACH_ANIMAL)) {
            return obj;
         }
      }

      return null;
   }

   private void checkTreeExists() {
      IsoObject tree = this.getData().getAttachedTree();
      if (tree != null) {
         IsoGridSquare treeSq = this.getCell()
            .getGridSquare(this.getData().getAttachedTreeX(), this.getData().getAttachedTreeY(), PZMath.fastfloor(this.getZ()));
         if (treeSq != null) {
            tree = this.getCanAttachAnimalObject(treeSq);
            if (tree != null) {
               this.getData().setAttachedTree(tree);
            } else {
               this.getData().setAttachedTree(null);
               this.attachBackToTreeX = Integer.MAX_VALUE;
               this.attachBackToTreeY = Integer.MAX_VALUE;
            }
         }
      }
   }

   private void reattachToTree() {
      if (this.attachBackToTreeX != Integer.MAX_VALUE && this.attachBackToTreeY != Integer.MAX_VALUE && this.getSquare() != null) {
         IsoGridSquare treeSq = this.getSquare().getCell().getGridSquare(this.attachBackToTreeX, this.attachBackToTreeY, this.getSquare().getZ());
         if (treeSq == null) {
            return;
         }

         IsoObject tree = this.getCanAttachAnimalObject(treeSq);
         if (tree == null) {
            return;
         }

         this.getData().setAttachedTree(tree);
         this.attachBackToTreeX = Integer.MAX_VALUE;
         this.attachBackToTreeY = Integer.MAX_VALUE;
      }
   }

   public WorldSound getLastSoundRespondedTo() {
      return this.lastSoundRespondedTo;
   }

   public void respondToSound() {
      if (this.lastSoundRespondedTo != null) {
         this.timeSinceFleeFromSound = this.timeSinceFleeFromSound - GameTime.getInstance().getMultiplier();
         if (!(this.timeSinceFleeFromSound <= 0.0F) && this.isAnimalMoving()) {
            return;
         }

         this.setMoving(false);
         if (GameServer.server && this.lastSoundRespondedTo.source instanceof IsoPlayer player) {
            player.callOut = false;
         }

         this.lastSoundRespondedTo = WorldSoundManager.instance.release(this.lastSoundRespondedTo);
      }

      WorldSound sound = WorldSoundManager.instance.getSoundAnimal(this);
      float attract = WorldSoundManager.instance.getSoundAttractAnimal(sound, this);
      if (sound != null && !(attract <= 0.0F) && !(sound.source instanceof IsoRadio) && !(sound.source instanceof IsoTelevision)) {
         if (this.isWild() || !sound.sourceIsVehicle()) {
            if (sound.source instanceof IsoPlayer player && player.callOut && this.getPlayerAcceptance(player) >= 40.0F) {
               this.pathToLocation(player.getXi(), player.getYi(), player.getZi());
            } else {
               this.lastSoundRespondedTo = WorldSoundManager.instance.getNew().init(sound);
               this.timeSinceFleeFromSound = 2000.0F * sound.radius;
               tempVector2.set(this.getX() - sound.x, this.getY() - sound.y);
               float fleeDistance = sound.radius + 10;
               if (this.isWild()) {
                  fleeDistance = sound.radius * 3.0F + 20.0F;
                  this.setIsAlerted(false);
                  this.getBehavior().lastAlerted = 1000.0F;
               }

               tempVector2.setLength(fleeDistance - tempVector2.getLength());
               int goX = tempVector2.floorX();
               int goY = tempVector2.floorY();
               this.changeStress(sound.radius * 0.05F);
               this.setVariable(AnimationVariableHandles.animalRunning, true);
               this.pathToLocation(this.getXi() + goX, this.getYi() + goY, this.getZi());
            }
         }
      }
   }

   public float calcDamage() {
      float baseDmg = this.adef.baseDmg;
      AnimalAllele allele = this.getUsedGene("strength");
      if (allele != null) {
         baseDmg *= allele.currentValue;
      }

      return baseDmg;
   }

   public void HitByAnimal(IsoAnimal animal, boolean bIgnoreDamage) {
      float baseDmg = animal.adef.baseDmg;
      AnimalAllele allele = animal.getUsedGene("strength");
      if (allele != null) {
         baseDmg *= allele.currentValue;
      }

      this.setHitReaction("default");
      if (!bIgnoreDamage) {
         this.setHealth(this.getHealth() - baseDmg * this.getData().getHealthLoss(0.04F));
         if (this.getSquare() != null) {
            IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(this.getSquare().x, this.getSquare().y, this.getSquare().getZ());
            if (sq != null) {
               for (int i = 0; i < 10; i++) {
                  sq.getChunk().addBloodSplat(sq.x + Rand.Next(-0.8F, 0.8F), sq.y + Rand.Next(-0.8F, 0.8F), sq.z, Rand.Next(8));
               }
            }
         }
      }

      if (this.isDead()) {
         if (this.fightingOpponent instanceof IsoAnimal isoAnimal) {
            isoAnimal.fightingOpponent = null;
         }

         this.fightingOpponent = null;
      }
   }

   public void initializeStates() {
      this.clearAIStateMap();
      this.registerAIState("idle", AnimalIdleState.instance());
      this.registerAIState("eating", AnimalEatState.instance());
      this.registerAIState("attack", AnimalAttackState.instance());
      this.registerAIState("walk", AnimalWalkState.instance());
      this.registerAIState("pathfind", AnimalPathFindState.instance());
      this.registerAIState("followwall", AnimalFollowWallState.instance());
      this.registerAIState("hitreaction", AnimalHitReactionState.instance());
      this.registerAIState("falldown", AnimalFalldownState.instance());
      this.registerAIState("onground", AnimalOnGroundState.instance());
      this.registerAIState("zone", AnimalZoneState.instance());
      this.registerAIState("alerted", AnimalAlertedState.instance());
      this.registerAIState("climbfence", AnimalClimbOverFenceState.instance());
   }

   public void spotted(IsoMovingObject other, boolean bForced, float dist) {
      this.behavior.spotted(other, bForced, dist);
   }

   public void drawRope(IsoGameCharacter chr) {
      if (!this.isExistInTheWorld() && this.getData() != null && this.getData().getAttachedPlayer() != null) {
         this.getData().getAttachedPlayer().removeAttachedAnimal(this);
      }

      int bone = this.getAnimationPlayer().getSkinningBoneIndex(this.adef.ropeBone, -1);
      Model.boneToWorldCoords(this, bone, tempVector3);
      float r = 0.5F;
      float g = 0.37F;
      float b = 0.3F;
      float dist = chr.DistToProper(this);
      if (dist > 10.0F) {
         dist -= 10.0F;
         r += dist / 10.0F;
         g -= dist / 10.0F;
         b -= dist / 10.0F;
      }

      float sx0 = IsoUtils.XToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      sx0 -= this.getAnimalSize();
      float sy0 = IsoUtils.YToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      sy0 -= this.getAnimalSize();
      bone = chr.getAnimationPlayer().getSkinningBoneIndex("Bip01_R_Finger0", -1);
      Model.boneToWorldCoords(chr, bone, tempVector3);
      float sx1 = IsoUtils.XToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      float sy1 = IsoUtils.YToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      if (PerformanceSettings.fboRenderChunk) {
         IndieGL.glBlendFunc(770, 771);
         IndieGL.StartShader(0);
         IndieGL.disableDepthTest();
      }

      LineDrawer.drawLine(sx0, sy0, sx1, sy1, r, g, b, 1.0F, 2);
   }

   private void drawRope(IsoGridSquare sq) {
      float r = 0.5F;
      float g = 0.37F;
      float b = 0.3F;
      int bone = this.getAnimationPlayer().getSkinningBoneIndex("Bip01_Neck", -1);
      Model.boneToWorldCoords(this, bone, tempVector3);
      float dist = sq.DistToProper(this.getCurrentSquare());
      if (dist > 10.0F) {
         dist -= 10.0F;
         r += dist / 10.0F;
         g -= dist / 10.0F;
         b -= dist / 10.0F;
      }

      float sx0 = IsoUtils.XToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      sx0 -= this.getAnimalSize();
      float sy0 = IsoUtils.YToScreenExact(tempVector3.x, tempVector3.y, tempVector3.z, 0);
      sy0 -= this.getAnimalSize();
      float sx1 = IsoUtils.XToScreenExact(sq.x, sq.y, 0.1F, 0);
      float sy1 = IsoUtils.YToScreenExact(sq.x, sq.y, 0.1F, 0);
      if (PerformanceSettings.fboRenderChunk) {
         IndieGL.glBlendFunc(770, 771);
         IndieGL.StartShader(0);
         IndieGL.disableDepthTest();
      }

      LineDrawer.drawLine(sx0, sy0, sx1, sy1, r, g, b, this.getAlpha(IsoCamera.frameState.playerIndex), 2);
   }

   public void renderlast() {
      if (this.data != null) {
         if (!this.isAlphaZero(IsoCamera.frameState.playerIndex) && this.data.getAttachedTree() != null) {
            IsoGridSquare treeSq = this.getCell()
               .getGridSquare(this.getData().getAttachedTreeX(), this.getData().getAttachedTreeY(), PZMath.fastfloor(this.getZ()));
            if (treeSq != null) {
               this.drawRope(treeSq);
            }
         }

         this.doDebugString();
         this.renderCustomName();
         super.renderlast();
      }
   }

   private void renderCustomName() {
      IsoPlayer player = IsoPlayer.getInstance();
      if (player != null && !player.isDead()) {
         if (player.isSeeDesignationZone()) {
            if (!StringUtils.isNullOrWhitespace(this.getCustomName())) {
               float renderX = this.getX();
               float renderY = this.getY();
               Position3D p3d = this.getAttachmentWorldPos("head", L_renderCustomName);
               float renderZ;
               if (p3d == null) {
                  renderZ = 0.5F;
               } else {
                  renderZ = p3d.z() * 1.5F;
               }

               float sx = IsoUtils.XToScreen(renderX, renderY, renderZ, 0);
               float sy = IsoUtils.YToScreen(renderX, renderY, renderZ, 0);
               sx = sx - IsoCamera.getOffX() - this.offsetX;
               sy = sy - IsoCamera.getOffY() - this.offsetY;
               int playerIndex = IsoCamera.frameState.playerIndex;
               float zoom = Core.getInstance().getZoom(playerIndex);
               sx /= zoom;
               sy /= zoom;
               sx += IsoCamera.cameras[IsoCamera.frameState.playerIndex].fixJigglyModelsX;
               sy += IsoCamera.cameras[IsoCamera.frameState.playerIndex].fixJigglyModelsY;
               if (this.userName == null) {
                  this.userName = new TextDrawObject();
                  this.userName.setAllowAnyImage(true);
                  this.userName.setDefaultFont(UIFont.Small);
                  this.userName.setDefaultColors(255, 255, 255, 255);
               }

               if (!StringUtils.equals(this.userName.getOriginal(), this.customName)) {
                  this.userName.Clear();
                  this.userName.ReadString(this.customName);
               }

               sy -= this.userName.getHeight();
               this.userName.AddBatchedDraw(sx, sy);
            }
         }
      }
   }

   private void doDebugString() {
      IsoGameCharacter character = IsoCamera.frameState.camCharacter;
      if (character != null && character.isAnimalExtraValuesCheat() && !this.isOnHook()) {
         if (this.legsSprite != null && this.legsSprite.modelSlot != null && this.legsSprite.modelSlot.model != null) {
            ModelInstance modelInstance = this.legsSprite.modelSlot.model;
            ModelScript modelScript = modelInstance.modelScript;

            for (int i = 0; i < modelScript.getAttachmentCount(); i++) {
               ModelAttachment attach = modelScript.getAttachment(i);
               Position3D v = this.getAttachmentWorldPos(attach.getId());
               if (v != null) {
                  LineDrawer.DrawIsoCircle(v.x, v.y, v.z, 0.03F, 1.0F, 1.0F, 1.0F, 1.0F);
               }
            }
         }

         int sx = (int)IsoUtils.XToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
         int sy = (int)IsoUtils.YToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
         StringBuilder sb = new StringBuilder();
         sb.append(Translator.getText("IGUI_Name", new Object[0])).append(": ").append(this.getFullName()).append("\n");
         sb.append(Translator.getText("IGUI_StatsAndBody_Stress", new Object[0])).append(": ").append(Math.round(this.stressLevel)).append("\n");
         sb.append(Translator.getText("IGUI_HealthTooltip", new Object[0])).append(": ").append(this.getHealth() * 100.0F).append("\n");

         for (short id : this.playerAcceptanceList.keySet()) {
            if (id == IsoPlayer.getInstance().getOnlineID()) {
               sb.append(Translator.getText("IGUI_Acceptance", new Object[0])).append(": ").append(Math.round(this.playerAcceptanceList.get(id))).append("\n");
            }
         }

         if (this.getData().getMilkQuantity() > 0.0F) {
            sb.append(Translator.getText("ContextMenu_Milk", new Object[0])).append(": ").append(this.getData().getMilkQuantity()).append("\n");
         }

         IndieGL.enableBlend();
         IndieGL.glBlendFunc(770, 771);
         IndieGL.StartShader(0);
         IndieGL.disableDepthTest();
         TextManager.instance.DrawString(sx, sy, sb.toString());
      }
   }

   public void drawDirectionLine(Vector2 dir, float length, float r, float g, float b) {
      float x2 = this.getX() + dir.x * length;
      float y2 = this.getY() + dir.y * length;
      float sx = IsoUtils.XToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
      float sy = IsoUtils.YToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
      float sx2 = IsoUtils.XToScreenExact(x2, y2, this.getZ(), 0);
      float sy2 = IsoUtils.YToScreenExact(x2, y2, this.getZ(), 0);
      LineDrawer.drawLine(sx, sy, sx2, sy2, r, g, b, 0.5F, 2);
   }

   public void renderShadow(float x, float y, float z) {
      if (!this.isOnHook() || this.getHook() != null) {
         if (this.getHook() != null) {
            int playerIndex = IsoCamera.frameState.playerIndex;
            Vector3f forward = tempVector3f;
            Vector2 forward2 = this.getAnimForwardDirection(tempo2);
            forward.set(forward2.x + 0.3F, forward2.y + 0.3F, 0.0F);
            float alpha = GameServer.server ? 1.0F : this.alpha[playerIndex];
            ColorInfo lightInfo = this.getHook().getSquare().lighting[playerIndex].lightInfo();
            if (PerformanceSettings.fboRenderChunk) {
               FBORenderShadows.getInstance()
                  .addShadow(
                     this.getHook().getSquare().getX() + 0.35F,
                     this.getHook().getSquare().getY() + 0.9F,
                     this.getHook().getSquare().getZ(),
                     forward,
                     0.5F,
                     0.7F,
                     0.6F,
                     lightInfo.r,
                     lightInfo.g,
                     lightInfo.b,
                     alpha,
                     true
                  );
            } else {
               IsoDeadBody.renderShadow(
                  this.getHook().getSquare().getX(),
                  this.getHook().getSquare().getY(),
                  this.getHook().getSquare().getZ(),
                  forward,
                  0.7F,
                  0.8F,
                  0.8F,
                  lightInfo,
                  alpha,
                  true
               );
            }
         } else {
            IsoGridSquare currentSquare = this.getCurrentSquare();
            if (currentSquare != null) {
               int playerIndex = IsoCamera.frameState.playerIndex;
               Vector3f forward = tempVector3f;
               Vector2 forward2 = this.getAnimForwardDirection(tempo2);
               forward.set(forward2.x, forward2.y, 0.0F);
               float w = this.adef.shadoww * this.getData().getSize();
               float fm = this.adef.shadowfm * this.getData().getSize();
               float bm = this.adef.shadowbm * this.getData().getSize();
               float alpha = GameServer.server ? 1.0F : this.alpha[playerIndex];
               ColorInfo lightInfo = currentSquare.lighting[playerIndex].lightInfo();
               if (PerformanceSettings.fboRenderChunk) {
                  FBORenderShadows.getInstance().addShadow(x, y, z, forward, w, fm, bm, lightInfo.r, lightInfo.g, lightInfo.b, alpha, true);
               } else {
                  IsoDeadBody.renderShadow(x, y, z, forward, w, fm, bm, lightInfo, alpha, true);
               }
            }
         }
      }
   }

   public BaseAnimalBehavior getBehavior() {
      return this.behavior;
   }

   public void checkAlphaAndTargetAlpha(IsoPlayer other) {
      this.setAlphaAndTarget(other.playerIndex, 1.0F);
   }

   public boolean shouldBecomeZombieAfterDeath() {
      return false;
   }

   private void onDied(IsoGameCharacter sender, IsoDeadBody body) {
      if (this.getMother() != null) {
         this.getMother().removeBaby(this);
      }
   }

   public void OnDeath() {
      LuaEventManager.triggerEvent("OnCharacterDeath", this);
   }

   public void hitConsequences(HandWeapon weapon, IsoGameCharacter wielder, boolean bIgnoreDamage, float damage, boolean bRemote) {
      if (!GameClient.client && !bIgnoreDamage) {
         this.setHealth(this.getHealth() - damage * this.getData().getHealthLoss(0.025F));
      }

      if (this.isWild() && !bIgnoreDamage) {
         this.getBehavior().setWildAndHurt(true);
         float dropDeadTimer = Rand.Next(this.getAdef().getWildFleeTimeUntilDeadTimer() / 2.0F, this.getAdef().getWildFleeTimeUntilDeadTimer());
         dropDeadTimer /= 1.0F + wielder.getPerkLevel(Perks.Aiming) / 10.0F;
         this.getBehavior().setWildDropDeadTimer(dropDeadTimer);
      }

      if (!bIgnoreDamage) {
         this.splatBloodFloorBig();
      }

      this.setAttackedBy(wielder);
      this.attackedTimer = GameTime.getInstance().getCalender().getTimeInMillis();
      if (!GameClient.client) {
         this.setHitReaction("hitreact");
         if (wielder instanceof IsoPlayer player && this.getHealth() <= 0.0F) {
            this.killed(player);
         }
      }

      this.setDebugStress(this.getStress() + Rand.Next(20, 40));
      if (GameServer.server && (wielder instanceof IsoPlayer || wielder instanceof IsoZombie) && this.adef.attackBack) {
         this.atkTarget = wielder;
         this.getBehavior().goAttack(wielder);
      }
   }

   public void setHealth(float health) {
      if (!this.isInvincible() || !(health < this.health)) {
         this.health = health;
         if (GameServer.server && this.health <= 0.0F) {
            this.sendExtraUpdateToClients();
         }
      }
   }

   public void sendExtraUpdateToClients() {
      AnimalInstanceManager.getInstance().update(this);

      for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
         UdpConnection c = (UdpConnection)GameServer.udpEngine.connections.get(n);
         if (c.isRelevantTo(this.getX(), this.getY())) {
            AnimalSynchronizationManager.getInstance().setExtraUpdate(c, this.onlineId);
         }
      }
   }

   public void killed(IsoPlayer chr) {
      if (this.health <= 0.0F && this.dZone != null) {
         ArrayList<IsoAnimal> animals = this.dZone.getAnimalsConnected();

         for (int i = 0; i < animals.size(); i++) {
            IsoAnimal animal = animals.get(i);
            animal.changeStress(Rand.Next(10.0F, 30.0F));
            if (chr != null && animal.DistToProper(chr) < 10.0F) {
               animal.getBehavior().forceFleeFromChr(chr);
            }
         }
      }
   }

   public void removeFromWorld() {
      if (this.getData() != null && this.getData().getAttachedPlayer() != null) {
         this.getData().getAttachedPlayer().removeAttachedAnimal(this);
      }

      super.removeFromWorld();
      this.getCell().getRemoveList().add(this);
      this.getCell().getObjectList().remove(this);
      if (this.hutch != null) {
         this.hutch.animalOutside.remove(this);
      }

      if (this.dZone != null) {
         this.dZone.removeAnimal(this);
      }

      if (this.isOnHook() && this.hook != null && this.hook.getSquare() != null) {
         this.attachBackToHookX = this.hook.getSquare().getX();
         this.attachBackToHookY = this.hook.getSquare().getY();
         this.attachBackToHookZ = this.hook.getSquare().getZ();
      }

      AnimalManagerMain.getInstance().removeFromWorld(this);
      this.alertedChr = null;
      this.atkTarget = null;
      this.attackedBy = null;
      this.drinkFromPuddle = null;
      this.drinkFromRiver = null;
      this.drinkFromTrough = null;
      this.eatFromTrough = null;
      this.eatFromGround = null;
      this.fightingOpponent = null;
      this.followingWall = false;
      this.heldBy = null;
      this.ignoredTrough.clear();
      this.luredBy = null;
      this.luredStartTimer = -1.0F;
      this.movingToFood = null;
      this.shouldFollowWall = false;
      this.lastSoundRespondedTo = WorldSoundManager.instance.release(this.lastSoundRespondedTo);
      this.spottedChr = null;
      this.thumpTarget = null;
      this.walkToCharLuring = false;
      this.removedFromWorldMs = System.currentTimeMillis();
      AnimalPopulationManager.getInstance().addToRecentlyRemoved(this);
   }

   public AnimalData getData() {
      return this.data;
   }

   public String getInventoryIconTextureName() {
      return this.getData() == null ? null : this.getData().getInventoryIconTextureName();
   }

   public Texture getInventoryIconTexture() {
      String textureName = this.getInventoryIconTextureName();
      return !StringUtils.isNullOrWhitespace(textureName) ? Texture.getSharedTexture(textureName) : null;
   }

   public void save(ByteBuffer output, boolean isDebugSave) throws IOException {
      this.save(output, isDebugSave, true);
   }

   public void save(ByteBuffer output, boolean isDebugSave, boolean serialize) throws IOException {
      if (serialize) {
         output.put((byte)(this.Serialize() ? 1 : 0));
         output.put(IsoObject.factoryGetClassID(this.getObjectName()));
      }

      if (this.animalZone != null) {
         output.putLong(this.animalZone.id.getMostSignificantBits());
         output.putLong(this.animalZone.id.getLeastSignificantBits());
      } else {
         output.putLong(0L);
         output.putLong(0L);
      }

      output.putFloat(this.getX());
      output.putFloat(this.getY());
      output.putFloat(this.getZ());
      output.putInt(this.getForwardIsoDirection().ordinal());
      this.getStats().save(output);
      GameWindow.WriteString(output, this.type);
      GameWindow.WriteString(output, this.data.getBreed().name);
      GameWindow.WriteString(output, this.customName);
      this.getModData().save(output);
      output.putInt(this.itemId);
      output.put((byte)(this.getDescriptor().isFemale() ? 1 : 0));
      output.putInt(this.animalId);
      ArrayList<String> genes = new ArrayList<>(this.fullGenome.keySet());
      output.putInt(this.fullGenome.size());

      for (int i = 0; i < genes.size(); i++) {
         String gene = genes.get(i);
         this.fullGenome.get(gene).save(output, isDebugSave);
      }

      if (this.getData().getAttachedTree() != null) {
         output.put((byte)1);
         output.putInt(this.getData().getAttachedTreeX());
         output.putInt(this.getData().getAttachedTreeY());
      } else {
         output.put((byte)0);
      }

      output.putInt(this.getData().getAge());
      output.putDouble(this.getHoursSurvived());
      output.putLong(GameTime.getInstance().getCalender().getTimeInMillis());
      output.putFloat(this.getData().getSize());
      output.putInt(this.attachBackToMother);
      if (this.mother != null) {
         output.put((byte)1);
         output.putInt(this.mother.animalId);
      } else {
         output.put((byte)0);
      }

      output.put((byte)(this.getData().pregnant ? 1 : 0));
      if (this.getData().pregnant) {
         output.putInt(this.getData().pregnantTime);
      }

      output.put((byte)(this.getData().canHaveMilk() ? 1 : 0));
      output.putFloat(this.getData().getMilkQuantity());
      output.putFloat(this.getData().maxMilkActual);
      output.putInt(this.milkRemoved);
      output.put((byte)this.getData().getPreferredHutchPosition());
      if (this.getBreed().woolType != null && this.getData().getMaxWool() > 0.0F) {
         output.putFloat(this.getData().woolQty);
      }

      output.putInt(this.getData().fertilizedTime);
      output.put((byte)(this.getData().fertilized ? 1 : 0));
      if (this.adef.eggsPerDay > 0) {
         output.putInt(this.getData().eggsToday);
      }

      output.putFloat(this.stressLevel);
      output.putInt(this.playerAcceptanceList.size());

      for (Short pId : this.playerAcceptanceList.keySet()) {
         output.putShort(pId);
         output.putFloat(this.playerAcceptanceList.get(pId));
      }

      output.putFloat(this.getData().weight);
      output.putLong(this.getData().lastPregnancyTime);
      output.putLong(this.getData().lastMilkTimer);
      output.putInt(this.getData().lastImpregnateTime);
      output.putFloat(this.getHealth());
      output.putDouble(this.virtualId);
      GameWindow.WriteString(output, this.migrationGroup);
      output.putInt(this.getData().clutchSize);
      if (this.isOnHook() && this.hook != null && this.hook.getSquare() != null) {
         output.put((byte)1);
         if (this.attachBackToHookX > 0 && this.attachBackToHookY > 0) {
            output.putInt(this.attachBackToHookX);
            output.putInt(this.attachBackToHookY);
            output.putInt(this.attachBackToHookZ);
         } else {
            output.putInt(this.hook.getSquare().getX());
            output.putInt(this.hook.getSquare().getY());
            output.putInt(this.hook.getSquare().getZ());
         }
      } else {
         output.put((byte)0);
      }

      output.putFloat(this.petTimer);
      output.put((byte)(this.isWild() ? 1 : 0));
      output.putShort(this.getOnlineID());
   }

   public void load(ByteBuffer input, int worldVersion, boolean isDebugSave) throws IOException {
      this.setIsAnimal(true);
      UUID zoneID = new UUID(input.getLong(), input.getLong());
      this.animalZone = zoneID.getMostSignificantBits() == 0L && zoneID.getLeastSignificantBits() == 0L
         ? null
         : (AnimalZone)IsoWorld.instance.metaGrid.animalZoneHandler.getZone(zoneID);
      this.setX(this.setLastX(this.setNextX(input.getFloat() + IsoWorld.saveoffsetx * 300)));
      this.setY(this.setLastY(this.setNextY(input.getFloat() + IsoWorld.saveoffsety * 300)));
      this.setZ(this.setLastZ(input.getFloat()));
      this.setForwardIsoDirection(IsoDirections.fromIndex(input.getInt()));
      this.getStats().load(input, worldVersion);
      this.type = GameWindow.ReadString(input);
      this.adef = AnimalDefinitions.getDef(this.type);
      AnimalBreed breed = this.adef.getBreedByName(GameWindow.ReadString(input));
      String newName = GameWindow.ReadString(input);
      if (!StringUtils.isNullOrEmpty(newName)) {
         this.customName = newName;
      }

      if (this.table == null) {
         this.table = LuaManager.platform.newTable();
      }

      this.table.load(input, worldVersion);
      this.itemId = input.getInt();
      this.init(breed);
      this.getDescriptor().setFemale(input.get() != 0);
      this.animalId = input.getInt();
      int genomeSize = input.getInt();

      for (int i = 0; i < genomeSize; i++) {
         AnimalGene gene = new AnimalGene();
         gene.load(input, worldVersion, isDebugSave);
         this.fullGenome.put(gene.name, gene);
      }

      AnimalGene.checkGeneticDisorder(this);
      int treeX = Integer.MAX_VALUE;
      int treeY = Integer.MAX_VALUE;
      if (input.get() != 0) {
         treeX = input.getInt();
         treeY = input.getInt();
      }

      this.attachBackToTreeX = treeX;
      this.attachBackToTreeY = treeY;
      this.getData().setAge(input.getInt());
      this.setHoursSurvived(input.getDouble());
      this.timeSinceLastUpdate = input.getLong();
      this.data.setSize(input.getFloat());
      this.attachBackToMother = input.getInt();
      if (input.get() != 0) {
         this.attachBackToMother = input.getInt();
      }

      this.getData().pregnant = input.get() != 0;
      if (this.getData().pregnant) {
         this.getData().pregnantTime = input.getInt();
      }

      this.getData().canHaveMilk = input.get() != 0;
      float milkQty = input.getFloat();
      this.getData().maxMilkActual = input.getFloat();
      this.getData().setMilkQuantity(milkQty);
      this.milkRemoved = input.getInt();
      this.getData().setPreferredHutchPosition(input.get());
      if (this.getBreed().woolType != null && this.getData().getMaxWool() > 0.0F) {
         this.getData().woolQty = input.getFloat();
      }

      this.getData().fertilizedTime = input.getInt();
      this.getData().fertilized = input.get() != 0;
      if (this.adef.eggsPerDay > 0) {
         this.getData().eggsToday = input.getInt();
      }

      this.stressLevel = input.getFloat();
      int size = input.getInt();

      for (int i = 0; i < size; i++) {
         this.playerAcceptanceList.put(input.getShort(), input.getFloat());
      }

      this.getData().weight = input.getFloat();
      this.getData().lastPregnancyTime = input.getLong();
      this.getData().lastMilkTimer = input.getLong();
      this.getData().lastImpregnateTime = input.getInt();
      this.setHealth(input.getFloat());
      this.virtualId = input.getDouble();
      this.migrationGroup = GameWindow.ReadString(input);
      this.getData().clutchSize = input.getInt();
      this.setOnHook(input.get() != 0);
      if (this.isOnHook()) {
         this.attachBackToHookX = input.getInt();
         this.attachBackToHookY = input.getInt();
         this.attachBackToHookZ = input.getInt();
      }

      if (worldVersion >= 236) {
         this.petTimer = input.getFloat();
      }

      if (worldVersion >= 245) {
         this.setWild(input.get() != 0);
      }

      if (worldVersion >= 247) {
         short animalOnlineId = input.getShort();
         if (GameClient.client) {
            this.setOnlineID(animalOnlineId);
         }
      }
   }

   public void init(AnimalBreed breed) {
      this.adef = AnimalDefinitions.getDef(this.getAnimalType());
      if (this.adef != null) {
         this.getAdvancedAnimator().setAnimSet(AnimationSet.GetAnimationSet(this.GetAnimSetName(), false));
         this.initializeStates();
         this.initType(breed);
         this.animalId = Rand.Next(10000);
         this.InitSpriteParts(this.descriptor);
         this.setTurnDelta(this.adef.turnDelta);
         this.initAge();
         AnimalGene.initGenome(this);
         AnimalGene.checkGeneticDisorder(this);
         this.getData().init();
         this.initTexture();
         this.setStateEventDelayTimer(this.getBehavior().pickRandomWanderInterval());
         this.initStress();
         this.setWild(this.adef.wild);
         if (GameServer.server) {
            AnimalInstanceManager.getInstance().add(this, AnimalInstanceManager.getInstance().allocateID());
         }
      }
   }

   private void initStress() {
      AnimalAllele allele = this.getUsedGene("stress");
      float base = Rand.Next(0.01F, 0.15F);
      if (allele == null) {
         this.stressLevel = base;
      }

      float mod = 1.0F;
      if (allele != null) {
         mod = 1.0F - allele.currentValue + 1.0F;
      }

      if (!this.isBaby()) {
         base += (float)(GameTime.getInstance().getWorldAgeDaysSinceBegin() * 0.005F);
      }

      this.stressLevel = mod * base * 100.0F;
      this.stressLevel = Math.min(70.0F, this.stressLevel);
   }

   private void initTexture() {
      if (this.shouldBeSkeleton()) {
         this.getAnimalVisual().setSkinTextureName(this.adef.textureSkeleton);
      } else if (!StringUtils.isNullOrEmpty(this.adef.textureSkinned) && ((KahluaTableImpl)this.getModData()).rawgetBool("skinned")) {
         this.getAnimalVisual().setSkinTextureName(this.adef.textureSkinned);
      } else {
         if (!StringUtils.isNullOrEmpty(this.getData().currentStage.nextStage) && !StringUtils.isNullOrEmpty(this.getBreed().textureBaby)) {
            this.getAnimalVisual().setSkinTextureName(this.getBreed().textureBaby);
         } else if (this.getDescriptor().isFemale()) {
            this.getAnimalVisual().setSkinTextureName((String)this.getBreed().texture.get(Rand.Next(0, this.getBreed().texture.size())));
         } else {
            this.getAnimalVisual().setSkinTextureName(this.getBreed().textureMale);
         }
      }
   }

   private void initAge() {
      this.getData().setAge(1);
      if (this.adef.minAge > 0) {
         this.getData().setAge(this.adef.minAge);
      }

      this.setHoursSurvived(this.getData().getAge() * 24);
   }

   public boolean canGoThere(IsoGridSquare sq) {
      if (sq.isBlockedTo(this.getCurrentSquare())) {
         return false;
      }

      if (sq.isWindowTo(this.getCurrentSquare())) {
         return false;
      }

      int ropeLength = 15;
      if (this.getData().getAttachedPlayer() != null && sq.DistTo(this.getData().getAttachedPlayer().getCurrentSquare()) > 15.0F) {
         return false;
      }

      if (this.getData().getAttachedTree() != null) {
         int treeX = this.getData().getAttachedTreeX();
         int treeY = this.getData().getAttachedTreeY();
         if (IsoUtils.DistanceTo(treeX + 0.5F, treeY + 0.5F, sq.getX() + 0.5F, sq.getY() + 0.5F) > 15.0F) {
            return false;
         }
      }

      return true;
   }

   public String getAnimalType() {
      return this.type;
   }

   public float getAnimalSize() {
      return this.getData().getSize();
   }

   public float getAnimalOriginalSize() {
      return this.getData().getOriginalSize();
   }

   public void setAgeDebug(int newAge) {
      this.getData().setAge(Math.max(newAge, this.adef.minAge));
      this.setHoursSurvived(this.getData().getAge() * 24);
      this.getData().init();
   }

   public boolean haveEnoughMilkToFeedFrom() {
      return this.getData().getMilkQuantity() >= 0.02;
   }

   public IsoAnimal addBaby() {
      DebugType.Animal.debugln("Adding baby from mother: " + this.getFullName());
      if (this.adef.babyType == null) {
         return null;
      }

      AnimalDefinitions babyDef = AnimalDefinitions.getDef(this.adef.babyType);
      IsoAnimal baby = new IsoAnimal(
         this.getCell(), this.getXi(), this.getYi(), this.getZi(), this.adef.babyType, babyDef.getBreedByName(this.getBreed().getName())
      );
      baby.fullGenome = AnimalGene.initGenesFromParents(this.fullGenome, this.getData().maleGenome);
      AnimalGene.checkGeneticDisorder(baby);
      baby.getData().initSize();
      DebugType.Animal.debugln("Baby added: " + baby.getFullName());
      if (baby.geneticDisorder.contains("dieatbirth")) {
         DebugType.Animal.debugln("Baby died at birth");
         baby.setHealth(0.0F);
      }

      if (this.getVehicle() != null) {
         this.getVehicle().addAnimalInTrailer(baby);
      } else {
         baby.addToWorld();
      }

      this.getData().updateLastPregnancyTime();
      this.getData().maleGenome = null;
      this.getData().setFertilized(false);
      if (this.adef.udder) {
         this.getData().setCanHaveMilk(true);
         this.getData().updateLastTimeMilked();
      }

      baby.setMother(this);
      baby.motherId = this.animalId;
      baby.setIsInvincible(this.isInvincible());
      return baby;
   }

   private void initType(AnimalBreed breed) {
      this.behavior = new BaseAnimalBehavior(this);
      this.data = new AnimalData(this, breed);
      this.getActionContext().setGroup(ActionGroup.getActionGroup(this.adef.animset));
   }

   public void unloaded() {
      this.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
      if (this.getData().getAttachedTree() != null) {
         this.attachBackToTreeX = this.getData().getAttachedTreeX();
         this.attachBackToTreeY = this.getData().getAttachedTreeY();
      }

      this.getData().setAttachedTree(null);
      this.getAnimalSoundState("voice").setDesiredSoundName(null);
      this.getAnimalSoundState("voice").setDesiredSoundPriority(0);
      this.getAnimalSoundState("voice").stop();
   }

   public void updateLastTimeSinceUpdate() {
      this.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
   }

   public void debugAgeAway(int hour) {
      long hoursmilli = hour * 3600000L;
      long timeSinceLastUpdate2 = GameTime.getInstance().getCalender().getTimeInMillis();
      timeSinceLastUpdate2 -= hoursmilli;
      long delta = GameTime.getInstance().getCalender().getTimeInMillis() - timeSinceLastUpdate2;
      int age = this.getData().getAge();
      int hours = (int)(delta / 3600000L);
      int deltaAge = hours / 24;
      deltaAge *= (int)this.getData().getAgeGrowModifier();
      int newage = age + deltaAge;
      this.setHoursSurvived(this.getHoursSurvived() + hours);
      this.getData().lastHourCheck = GameTime.getInstance().getHour();
      Calendar cal = Calendar.getInstance();
      cal.setTimeInMillis(timeSinceLastUpdate2);

      for (int i = 0; i < hours; i++) {
         this.getData().hourGrow(true);
         timeSinceLastUpdate2 += 3600000L;
         cal.setTimeInMillis(timeSinceLastUpdate2);
         int realHour = cal.get(11);
         if (this.checkKilledByMetaPredator(realHour)) {
            return;
         }
      }
   }

   public void updateStatsAway(int hours) {
      this.fromMeta = false;
      if (!this.isWild()) {
         this.zoneCheckTimer = 0.0F;
         this.checkZone();
         int age = this.getData().getAge();
         int deltaAge = hours / 24;
         deltaAge *= (int)this.getData().getAgeGrowModifier();
         int newage = age + deltaAge;
         this.setHoursSurvived(newage * 24);
         this.getData().lastHourCheck = GameTime.getInstance().getHour();
         this.getData().setAge(newage);
         PZCalendar cal = PZCalendar.getInstance();
         cal.setTimeInMillis(this.timeSinceLastUpdate);

         for (int i = 0; i < hours; i++) {
            this.getData().hourGrow(true);
            this.timeSinceLastUpdate += 3600000L;
            cal.setTimeInMillis(this.timeSinceLastUpdate);
            int realHour = cal.get(11);
            this.getData().tryInseminateInMeta(cal);
            if (realHour == 0) {
               this.getData().growUp(true);
            }

            this.getData().checkEggs(cal, true);
            if (this.checkKilledByMetaPredator(realHour)) {
               return;
            }
         }

         this.getData().init();
      }
   }

   public boolean checkKilledByMetaPredator(int hour) {
      if (!SandboxOptions.instance.animalMetaPredator.getValue()) {
         return false;
      }

      if (this.adef.enterHutchTime == 0 && this.adef.exitHutchTime == 0) {
         return false;
      }

      if (this.adef.isInsideHutchTime(hour)) {
         int baseChance = 90;
         if (this.isBaby()) {
            baseChance = 45;
         }

         if (!this.isFemale()) {
            baseChance = 150;
         }

         if (Rand.NextBool(baseChance)) {
            if (this.hutch != null && !this.hutch.isDoorClosed()) {
               this.hutch.killAnimal(this);
               this.hutch.setHutchDirt(this.hutch.getHutchDirt() + Rand.Next(10, 20));
               IsoGridSquare sq = IsoWorld.instance
                  .currentCell
                  .getGridSquare(
                     this.hutch.getSquare().x + this.hutch.getEnterSpotX(), this.hutch.getSquare().y + this.hutch.getEnterSpotY(), this.hutch.getZ()
                  );
               if (sq != null) {
                  for (int i = 0; i < 20; i++) {
                     sq.getChunk().addBloodSplat(sq.x + Rand.Next(-0.8F, 0.8F), sq.y + Rand.Next(-0.8F, 0.8F), this.hutch.getZ(), Rand.Next(8));
                  }
               }
            } else {
               int nbr = 20;
               if (this.getCurrentSquare() != null) {
                  for (int i = 0; i < 20; i++) {
                     this.getCurrentSquare()
                        .getChunk()
                        .addBloodSplat(
                           this.getCurrentSquare().x + Rand.Next(-0.8F, 0.8F),
                           this.getCurrentSquare().y + Rand.Next(-0.8F, 0.8F),
                           this.getCurrentSquare().z,
                           Rand.Next(8)
                        );
                  }
               }

               this.hitConsequences(null, null, false, 666.0F, false);
            }

            return true;
         }
      }

      return false;
   }

   public boolean isBaby() {
      return this.getData().currentStage.nextStage != null;
   }

   public boolean shearAnimal(IsoGameCharacter chr, InventoryItem shear) {
      if (!(this.getData().woolQty < 1.0F) && !shear.isBroken()) {
         if (shear instanceof DrainableComboItem && shear.getCurrentUsesFloat() <= 0.0F) {
            return false;
         }

         this.getData().setWoolQuantity(this.getData().woolQty - 1.0F);
         InventoryItem wool = InventoryItemFactory.CreateItem(this.getBreed().woolType);
         this.getCurrentSquare().AddWorldInventoryItem(wool, Rand.Next(0.0F, 0.8F), Rand.Next(0.0F, 0.8F), 0.0F);
         if (Rand.NextBool((int)shear.getConditionLowerNormal())) {
            shear.setCondition(shear.getCondition() - 1);
         }

         if (shear instanceof DrainableComboItem) {
            shear.Use();
         }

         if (GameServer.server) {
            GameServer.addXp((IsoPlayer)chr, Perks.Husbandry, Rand.Next(2, 5));
         } else if (!GameClient.client) {
            chr.getXp().AddXP(Perks.Husbandry, Rand.Next(2, 5));
         }

         if (chr.getPerkLevel(Perks.Husbandry) <= 5 && Rand.NextBool(chr.getPerkLevel(Perks.Husbandry) + 3)) {
            this.changeStress(Rand.Next(6.0F - chr.getPerkLevel(Perks.Husbandry) * 2.0F, 6 - chr.getPerkLevel(Perks.Husbandry) * 6) / 8.0F);
         }

         return true;
      } else {
         return false;
      }
   }

   public String getMilkType() {
      return this.getBreed().milkType;
   }

   public InventoryItem addDebugBucketOfMilk(IsoGameCharacter chr) {
      InventoryItem newBucket = InventoryItemFactory.CreateItem("Base.BucketEmpty");
      newBucket.getFluidContainer().addFluid(this.getBreed().getMilkType(), newBucket.getFluidContainer().getCapacity());
      return newBucket;
   }

   public InventoryItem milkAnimal(IsoGameCharacter chr, InventoryItem bucket) {
      float minQty = Math.min(
         0.1F,
         !bucket.isEquipped() && !chr.isEquippedClothing(bucket)
            ? chr.getFreeInventoryCapacity()
            : chr.getFreeInventoryCapacity() / (float)ZomboidGlobals.equippedOrWornEncumbranceMultiplier
      );
      if (this.data.milkQty < minQty) {
         return null;
      }

      if (!bucket.getFluidContainer().isFull() && !(minQty <= 0.0F)) {
         this.getData().updateLastTimeMilked();
         this.getData().canHaveMilk = true;
         float quantity = bucket.getFluidContainer().getAmount();
         bucket.getFluidContainer().addFluid(this.getBreed().getMilkType(), minQty);
         quantity = bucket.getFluidContainer().getAmount() - quantity;
         this.getData().setMilkQuantity(this.data.milkQty - quantity);
         this.milkRemoved++;
         if (GameServer.server) {
            GameServer.addXp((IsoPlayer)chr, Perks.Husbandry, Rand.Next(2, 5));
         } else if (!GameClient.client) {
            chr.getXp().AddXP(Perks.Husbandry, Rand.Next(2, 5));
         }

         if (chr.getPerkLevel(Perks.Husbandry) <= 5 && Rand.NextBool(chr.getPerkLevel(Perks.Husbandry) + 3)) {
            this.changeStress(Rand.Next(6.0F - chr.getPerkLevel(Perks.Husbandry) * 2.0F, 6 - chr.getPerkLevel(Perks.Husbandry) * 6) / 8.0F);
         }

         if (this.milkRemoved >= 50) {
            this.milkRemoved = 0;
            this.getData().maxMilkActual = Math.min(this.getData().maxMilkActual + this.getData().getMaxMilk() * 0.001F, this.getData().getMaxMilk() * 1.3F);
         }

         return bucket;
      } else {
         return null;
      }
   }

   public void setMaxSizeDebug() {
      this.setAgeDebug(this.getData().getAge() + this.getData().currentStage.getAgeToGrow(this) - 3);
   }

   public boolean addEgg(boolean meta) {
      if (this.geneticDisorder.contains("noeggs")) {
         return false;
      }

      if (this.hutch != null) {
         return this.hutch.addAnimalInNestBox(this);
      }

      if (this.getVehicle() != null) {
         Food egg = this.createEgg();
         BaseVehicle vehicle = this.getVehicle();
         VehiclePart eggCont = vehicle.getPartById("TrailerAnimalEggs");
         if (eggCont != null && eggCont.getItemContainer() != null && eggCont.getItemContainer().hasRoomFor(null, egg)) {
            eggCont.getItemContainer().addItem(egg);
            return true;
         } else {
            VehiclePart foodCont = vehicle.getPartById("TrailerAnimalFood");
            if (foodCont != null && foodCont.getItemContainer() != null && foodCont.getItemContainer().hasRoomFor(null, egg)) {
               foodCont.getItemContainer().addItem(egg);
               return true;
            } else {
               InventoryItem obj = vehicle.getSquare().AddWorldInventoryItem(egg, Rand.Next(0.0F, 0.5F), Rand.Next(0.0F, 0.5F), 0.0F);
               IsoWorld.instance.getCell().addToProcessItems(obj);
               IsoWorld.instance.getCell().addToProcessItems(egg);
               return true;
            }
         }
      } else {
         Food egg = this.createEgg();
         IsoGridSquare sq = null;
         if (this.getContainer() != null) {
            this.getContainer().addItem(egg);
         } else if (meta && !this.connectedDZone.isEmpty()) {
            sq = this.getRandomSquareInZone();
         } else if (sq == null) {
            sq = this.getSquare();
         }

         if (sq != null) {
            InventoryItem obj = this.getSquare().AddWorldInventoryItem(egg, Rand.Next(0.0F, 0.5F), Rand.Next(0.0F, 0.5F), 0.0F);
            IsoWorld.instance.getCell().addToProcessItems(obj);
            IsoWorld.instance.getCell().addToProcessItems(egg);
         }

         return true;
      }
   }

   public Food createEgg() {
      Food egg = (Food)InventoryItemFactory.CreateItem(this.adef.eggType);
      if (egg == null) {
         DebugType.Animal.debugln("Error while creating egg: " + this.adef.eggType + " isn't a valid item.");
         return null;
      }

      float hungGene = this.getEggGeneMod();
      float hungMod = 1.0F + hungGene / 2.0F;
      float baseHung = 0.07F;
      baseHung *= hungMod;
      baseHung -= baseHung * 2.0F;
      if (this.getData().fertilized) {
         egg.setFertilized(true);
         egg.motherId = this.animalId;
         egg.setTimeToHatch(this.adef.timeToHatch);
         egg.setAnimalHatch(this.adef.babyType);
         egg.setBaseHunger(baseHung);
         egg.setHungChange(baseHung);
         egg.setAnimalHatchBreed(this.getBreed().getName());
         egg.eggGenome = AnimalGene.initGenesFromParents(this.fullGenome, this.getData().maleGenome);
      }

      if (this.getData().clutchSize > 0) {
         this.getData().clutchSize--;
      }

      return egg;
   }

   public void randomizeAge() {
      this.setAgeDebug(Rand.Next(this.adef.minAgeForBaby, this.adef.minAgeForBaby + this.getData().currentStage.getAgeToGrow(this) - 5));
   }

   public boolean isAnimalMoving() {
      return this.getCurrentState() == AnimalWalkState.instance()
         || this.getCurrentState() == AnimalPathFindState.instance()
         || this.getCurrentState() == AnimalFollowWallState.instance();
   }

   public boolean isGeriatric() {
      if (this.adef.maxAgeGeriatric <= 0) {
         return false;
      }

      float delta = this.getData().getMaxAgeGeriatric() - this.adef.minAge;
      return this.getData().getDaysSurvived() > delta * 0.8;
   }

   public String getAgeText(boolean cheat, int skillLvl) {
      String txt = "";
      String dbgInfo = "";
      if (this.isBaby()) {
         ArrayList<AnimalGrowStage> list = this.getData().getGrowStage();
         AnimalGrowStage stage = null;
         if (list != null && !list.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
               AnimalGrowStage stageCheck = list.get(i);
               if (stageCheck.stage.equals(this.getAnimalType()) && !StringUtils.isNullOrEmpty(stageCheck.nextStage)) {
                  stage = stageCheck;
                  break;
               }
            }
         }

         if (stage != null) {
            if (this.getData().getDaysSurvived() < stage.getAgeToGrow(this) / 2) {
               txt = Translator.getText("IGUI_Animal_Baby", new Object[0]);
               dbgInfo = "/ " + stage.getAgeToGrow(this) / 2;
            } else {
               txt = Translator.getText("IGUI_Animal_Juvenile", new Object[0]);
               dbgInfo = "/ " + stage.getAgeToGrow(this);
            }

            if (skillLvl < 4 && !Core.getInstance().animalCheat) {
               txt = Translator.getText("IGUI_Animal_Juvenile", new Object[0]);
            }
         }
      } else {
         float delta = this.getData().getMaxAgeGeriatric() - this.adef.minAge;
         if (this.getData().getDaysSurvived() < this.getMinAgeForBaby()) {
            txt = Translator.getText("IGUI_Animal_JuvenileWeaned", new Object[0]);
            dbgInfo = "/ " + this.getMinAgeForBaby();
         } else {
            txt = Translator.getText("IGUI_Animal_Adolescent", new Object[0]);
            dbgInfo = "/ " + this.adef.minAge * 2;
         }

         if (this.getData().getDaysSurvived() > this.adef.minAge * 2) {
            if (this.isGeriatric()) {
               txt = Translator.getText("IGUI_Animal_Geriatric", new Object[0]);
            } else {
               txt = Translator.getText("IGUI_Animal_Adult", new Object[0]);
            }

            dbgInfo = "/ " + Float.valueOf(delta * 0.8F).intValue();
         }

         if (skillLvl < 4 && !Core.getInstance().animalCheat) {
            txt = Translator.getText("IGUI_Animal_Adult", new Object[0]);
         }
      }

      if (cheat) {
         dbgInfo = " ( " + this.getData().getDaysSurvived() + dbgInfo + ")";
      } else {
         dbgInfo = "";
      }

      return txt + dbgInfo;
   }

   public String getHealthText(boolean cheat, int skillLvl) {
      String dbgTxt = "";
      if (cheat) {
         dbgTxt = " (" + PZMath.roundFloat(this.getHealth(), 2) + ")";
         if (this.isGeriatric()) {
            dbgTxt = dbgTxt + " losing health due to age.";
         }
      }

      String txt;
      if (this.getHealth() > 0.8) {
         txt = Translator.getText("IGUI_Animal_Healthy", new Object[0]);
      } else if (this.getHealth() > 0.55) {
         txt = Translator.getText("IGUI_Animal_OffColor", new Object[0]);
      } else if (this.getHealth() > 0.3) {
         txt = Translator.getText("IGUI_Animal_Sickly", new Object[0]);
      } else {
         txt = Translator.getText("IGUI_Animal_Dying", new Object[0]);
      }

      if (skillLvl < 5) {
         txt = Translator.getText("IGUI_Animal_Healthy", new Object[0]);
         if (this.getHealth() < 0.7) {
            txt = Translator.getText("IGUI_Animal_Sickly", new Object[0]);
         }
      }

      return txt + dbgTxt;
   }

   public String getAppearanceText(boolean cheat) {
      String dbgTxt = "";
      DecimalFormat df = new DecimalFormat("###.##");
      if (cheat) {
         dbgTxt = " (" + df.format(this.getHunger());
      }

      String hunger;
      if (this.getHunger() < 0.3) {
         hunger = Translator.getText("IGUI_Animal_WellFed", new Object[0]);
      } else if (this.getHunger() < 0.6) {
         hunger = Translator.getText("IGUI_Animal_Underfed", new Object[0]);
      } else {
         hunger = Translator.getText("IGUI_Animal_Starving", new Object[0]);
      }

      if (cheat) {
         dbgTxt = dbgTxt + "," + df.format(this.getThirst()) + ")";
      }

      String thirst;
      if (this.getThirst() < 0.3) {
         thirst = Translator.getText("IGUI_Animal_FullyWatered", new Object[0]);
      } else if (this.getThirst() < 0.6) {
         thirst = Translator.getText("IGUI_Animal_Thirsty", new Object[0]);
      } else {
         thirst = Translator.getText("IGUI_Animal_DyingThirst", new Object[0]);
      }

      return hunger + ", " + thirst + dbgTxt;
   }

   public void copyFrom(IsoAnimal animal) {
      LuaManager.copyTable(this.getModData(), animal.getModData());
      this.setHoursSurvived(animal.getHoursSurvived());
      this.getStats().set(CharacterStat.HUNGER, animal.getStats().get(CharacterStat.HUNGER));
      this.getStats().set(CharacterStat.THIRST, animal.getStats().get(CharacterStat.THIRST));
      this.customName = animal.customName;
      this.stressLevel = animal.stressLevel;
      this.playerAcceptanceList = animal.playerAcceptanceList;
      this.setHealth(animal.getHealth());
      this.animalId = animal.animalId;
      this.petTimer = animal.petTimer;
      this.fullGenome = animal.fullGenome;
      this.geneticDisorder = animal.geneticDisorder;
      this.attachBackToMother = animal.attachBackToMother;
      this.data = animal.getData();
      this.setFemale(animal.isFemale());
      this.data.parent = this;
      this.wild = animal.wild;
   }

   public void fertilize(IsoAnimal male, boolean force) {
      if (!force) {
         float maleFertility = male.getUsedGene("fertility").currentValue;
         if (male.geneticDisorder.contains("poorfertility")) {
            maleFertility = 1.0F;
         }

         if (male.geneticDisorder.contains("fertile")) {
            maleFertility = 100.0F;
         }

         if (male.geneticDisorder.contains("sterile")) {
            maleFertility = 0.0F;
         }

         maleFertility *= 1.0F - male.getData().getGeriatricPercentage();
         float femaleFertility = this.getUsedGene("fertility").currentValue;
         if (this.geneticDisorder.contains("poorfertility")) {
            femaleFertility = 1.0F;
         }

         if (this.geneticDisorder.contains("fertile")) {
            femaleFertility = 100.0F;
         }

         if (this.geneticDisorder.contains("sterile")) {
            femaleFertility = 0.0F;
         }

         femaleFertility *= 1.0F - this.getData().getGeriatricPercentage();
         float randValue = maleFertility * femaleFertility * 100.0F;
         if (male != null) {
            male.getData().lastImpregnateTime = 24;
            male.getData().animalToInseminate = new ArrayList();
         }

         if (Rand.Next(100) > randValue) {
            return;
         }
      }

      if (male == null) {
         this.getData().maleGenome = this.fullGenome;
      } else {
         this.getData().maleGenome = male.fullGenome;
      }

      if (this.adef.eggsPerDay > 0) {
         this.getData().fertilized = true;
         this.getData().fertilizedTime = 1;
      } else {
         this.getData().pregnantTime = 0;
         this.getData().pregnant = true;
      }
   }

   public boolean isAnimalEating() {
      return "eat".equals(this.getVariableString("idleAction"));
   }

   public boolean isAnimalAttacking() {
      return GameClient.client ? this.isAttackingOnClient : this.atkTarget != null || this.adef.canThump && this.thumpTarget != null;
   }

   public void setAnimalAttackingOnClient(boolean value) {
      this.isAttackingOnClient = value;
   }

   public boolean isAnimalSitting() {
      return "sit".equals(this.getVariableString("idleAction"));
   }

   public boolean isInvincible() {
      return this.invincible;
   }

   public boolean isAnimalRunningToDeathPosition() {
      return GameClient.client && this.getNetworkCharacterAI().getAnimalPacket().isDead() && this.getHealth() > 0.0F;
   }

   public void setIsInvincible(boolean b) {
      this.invincible = b;
   }

   public String getCustomName() {
      return this.customName;
   }

   public void setCustomName(String customName) {
      this.customName = customName;
   }

   public float getHunger() {
      return this.getStats().get(CharacterStat.HUNGER);
   }

   public float getThirst() {
      return this.getStats().get(CharacterStat.THIRST);
   }

   public String getBabyType() {
      return this.adef.babyType;
   }

   public boolean hasUdder() {
      return this.adef.udder;
   }

   public AnimalBreed getBreed() {
      return this.getData().getBreed();
   }

   public boolean canBeMilked() {
      return this.adef.canBeMilked;
   }

   public boolean canBeSheared() {
      return this.getBreed().woolType == null ? false : this.getData().getMaxWool() > 0.0F;
   }

   public int getEggsPerDay() {
      return this.adef.eggsPerDay;
   }

   public IsoHutch getHutch() {
      return this.hutch;
   }

   public int getNestBoxIndex() {
      return this.nestBox;
   }

   public void setData(AnimalData newData) {
      this.data = newData;
   }

   public boolean hasGeneticDisorder(String gd) {
      return this.geneticDisorder.contains(gd);
   }

   public String getFullName() {
      if (!StringUtils.isNullOrEmpty(this.customName)) {
         String txt = "";
         if (this.isWild()) {
            txt = txt + " (" + Translator.getText("IGUI_Animal_Wild", new Object[0]) + ")";
         }

         return this.customName + txt;
      } else {
         String name = Translator.getText("IGUI_AnimalType_" + this.getAnimalType(), new Object[0]);
         if (this.getData().getBreed() != null) {
            name = Translator.getText(
               "IGUI_AnimalFullName", new Object[]{Translator.getText("IGUI_Breed_" + this.getData().getBreed().getName(), new Object[0]), name}
            );
         }

         if (this.isWild()) {
            name = name + " (" + Translator.getText("IGUI_Animal_Wild", new Object[0]) + ")";
         }

         return name;
      }
   }

   public HashMap<String, AnimalGene> getFullGenome() {
      return this.fullGenome;
   }

   public void copyGenome(Collection<AnimalGene> genome) {
      this.fullGenome.clear();

      for (AnimalGene gene : genome) {
         this.fullGenome.put(gene.getName(), new AnimalGene(gene));
      }
   }

   public ArrayList<AnimalGene> getFullGenomeList() {
      ArrayList<AnimalGene> result = new ArrayList<>();

      for (String name : this.fullGenome.keySet()) {
         AnimalGene genes = this.fullGenome.get(name);
         result.add(genes);
      }

      return result;
   }

   public AnimalAllele getUsedGene(String name) {
      name = name.toLowerCase();
      AnimalGene geneSet = this.fullGenome.get(name);
      if (geneSet == null) {
         DebugType.Animal.debugln(name + " wasn't found in the full genome of the animal (" + this.getFullName() + ")");
         return null;
      } else {
         return geneSet.allele1.used ? geneSet.allele1 : geneSet.allele2;
      }
   }

   public int getAge() {
      return this.getData().getAge();
   }

   public boolean canDoAction() {
      return !this.isAnimalAttacking() && !this.isAnimalSitting() && !this.getBehavior().blockMovement && !this.isAnimalMoving();
   }

   public float getMeatRatio() {
      AnimalAllele allele = this.getUsedGene("meatRatio");
      return allele != null ? allele.currentValue : 1.0F;
   }

   public String getMate() {
      return this.adef.mate;
   }

   public AnimalZone getAnimalZone() {
      return this.animalZone;
   }

   public void setAnimalZone(AnimalZone zone) {
      this.animalZone = zone;
   }

   public boolean hasAnimalZone() {
      return this.getAnimalZone() != null;
   }

   public boolean isMoveForwardOnZone() {
      return this.moveForwardOnZone;
   }

   public void setMoveForwardOnZone(boolean b) {
      this.moveForwardOnZone = b;
   }

   public boolean isExistInTheWorld() {
      return this.square != null && this.square.getMovingObjects().contains(this);
   }

   public void changeStress(float inc) {
      if (!this.isInvincible() || !(inc < 0.0F)) {
         AnimalAllele allele = this.getUsedGene("stress");
         if (allele != null) {
            if (inc > 0.0F) {
               inc *= 1.0F + allele.currentValue;
            } else {
               inc *= allele.currentValue;
            }
         }

         if (this.geneticDisorder.contains("highstress")) {
            if (inc < 0.0F) {
               inc *= 10.0F;
            } else {
               inc /= 10.0F;
            }
         }

         this.stressLevel += inc;
         this.stressLevel = Math.min(100.0F, Math.max(0.0F, this.stressLevel));
      }
   }

   public float getEggGeneMod() {
      AnimalAllele allele = this.getUsedGene("eggSize");
      if (allele != null) {
         float value = allele.currentValue;
         if (this.geneticDisorder.contains("smalleggs")) {
            value = 0.1F;
         }

         return value;
      } else {
         return 1.0F;
      }
   }

   public void setDebugStress(float stress) {
      this.stressLevel = stress;
   }

   public void setDebugAcceptance(IsoPlayer chr, float acceptance) {
      this.playerAcceptanceList.put(chr.getOnlineID(), acceptance);
   }

   public ArrayList<InventoryItem> getAllPossibleFoodFromInv(IsoGameCharacter chr) {
      ArrayList<InventoryItem> result = new ArrayList<>();
      ArrayList<String> foodList = this.getEatTypePossibleFromHand();
      ArrayList<InventoryItem> foodInInv = chr.getInventory().getAllFoodsForAnimals();

      for (int i = 0; i < foodInInv.size(); i++) {
         InventoryItem foodInv = foodInInv.get(i);
         String testType = foodInv.getFullType();
         String testType2 = null;
         if (foodInv instanceof Food food && food.getMilkType() != null) {
            testType = food.getMilkType();
         }

         if (foodInv instanceof Food food) {
            testType2 = food.getFoodType();
         }

         if (foodInv.isAnimalFeed()) {
            testType = foodInv.getAnimalFeedType();
         }

         if (foodList.contains(testType) || foodList.contains(testType2)) {
            result.add(foodInv);
         }
      }

      return result;
   }

   public ArrayList<String> getEatTypePossibleFromHand() {
      ArrayList<String> result = new ArrayList<>();
      if (this.adef.feedByHandType != null) {
         result.addAll(this.adef.feedByHandType);
      }

      if (this.adef.eatTypeTrough != null) {
         result.addAll(this.adef.eatTypeTrough);
      }

      if (this.isBaby() && !StringUtils.isNullOrEmpty(this.getBreed().milkType)) {
         result.add(this.getBreed().milkType);
      }

      result.add("AnimalMilk");
      return result;
   }

   public void addAcceptance(IsoPlayer chr, float acceptance) {
      float newAcceptance = this.getAcceptanceLevel(chr);
      newAcceptance += acceptance;
      newAcceptance *= 1.0F + chr.getPerkLevel(Perks.Husbandry) / 10.0F;
      if (newAcceptance > 100.0F) {
         newAcceptance = 100.0F;
      }

      this.playerAcceptanceList.put(chr.getOnlineID(), newAcceptance);
   }

   public void feedFromHand(IsoPlayer chr, InventoryItem food) {
      float hunger = 0.0F;
      float thirst = 0.0F;
      this.getData().eatItem(food, false);
      if (GameServer.server) {
         GameServer.addXp(chr, Perks.Husbandry, Rand.Next(4, 10));
      } else if (!GameClient.client) {
         chr.getXp().AddXP(Perks.Husbandry, Rand.Next(4, 10));
      }

      this.changeStress(Rand.Next(-4, -1) - chr.getPerkLevel(Perks.Husbandry) / 5.0F);
      this.setDebugAcceptance(chr, Math.min(100.0F, this.getAcceptanceLevel(chr) + Rand.Next(1, 4)));
   }

   public boolean petTimerDone() {
      return this.petTimer <= 0.0F;
   }

   public void petAnimal(IsoPlayer chr) {
      if (!GameClient.client && this.petTimer == 0.0F) {
         this.changeStress(Rand.Next(-11, -7) - chr.getPerkLevel(Perks.Husbandry));
         this.setDebugAcceptance(chr, Math.min(100.0F, this.getAcceptanceLevel(chr) + Rand.Next(2, 7)));
         this.petTimer = Rand.Next(15000, 25000) - chr.getPerkLevel(Perks.Husbandry) * 200;
         if (GameServer.server) {
            GameServer.addXp(chr, Perks.Husbandry, Rand.Next(5, 10));
         } else {
            chr.getXp().AddXP(Perks.Husbandry, Rand.Next(5, 10));
         }
      }

      chr.petAnimal();
   }

   public float getStress() {
      return this.stressLevel;
   }

   public String getStressTxt(boolean cheat, int skillLvl) {
      String txt = Translator.getText("IGUI_Animal_Calm", new Object[0]);
      String dbgTxt = "";
      if (cheat) {
         dbgTxt = " (" + PZMath.roundFloat(this.stressLevel, 2) + ")";
      }

      if (this.stressLevel > 40.0F) {
         txt = Translator.getText("IGUI_Animal_Unnerved", new Object[0]);
      }

      if (this.stressLevel > 60.0F) {
         txt = Translator.getText("IGUI_Animal_Stressed", new Object[0]);
      }

      if (this.stressLevel > 80.0F) {
         txt = Translator.getText("IGUI_Animal_Agitated", new Object[0]);
      }

      if (skillLvl < 4) {
         txt = Translator.getText("IGUI_Animal_Calm", new Object[0]);
         if (this.stressLevel > 40.0F) {
            txt = Translator.getText("IGUI_Animal_Stressed", new Object[0]);
         }
      }

      return txt + dbgTxt;
   }

   public void fleeTo(IsoGridSquare sq) {
      if (this.getData().getAttachedPlayer() != null) {
         this.getData().getAttachedPlayer().getAttachedAnimals().remove(this);
         this.getData().setAttachedPlayer(null);
      }

      this.getData().setAttachedTree(null);
      this.setVariable(AnimationVariableHandles.animalRunning, true);
      this.pathToLocation(sq.x, sq.y, sq.z);
   }

   public float getAcceptanceLevel(IsoPlayer chr) {
      if (!this.playerAcceptanceList.containsKey(chr.getOnlineID())) {
         this.playerAcceptanceList.put(chr.getOnlineID(), Rand.Next(0.0F, 20.0F));
      }

      return this.playerAcceptanceList.get(chr.getOnlineID());
   }

   public boolean canBeFeedByHand() {
      return this.adef.canBeFeedByHand;
   }

   public void tryLure(IsoPlayer chr, InventoryItem item) {
      if (this.luredBy == null) {
         if (this.CanSee(chr)) {
            if (this.getPossibleLuringItems(chr).contains(item)) {
               float acceptance = 100.0F - (this.getAcceptanceLevel(chr) + 20.0F);
               acceptance *= 1.0F + chr.getPerkLevel(Perks.Husbandry) / 10.0F;
               if (Rand.Next(100) > acceptance) {
                  if (this.getStress() > 40.0F && Rand.NextBool(5)) {
                     return;
                  }

                  if (this.getStress() > 60.0F && Rand.NextBool(3)) {
                     return;
                  }

                  if (this.getStress() > 80.0F) {
                     return;
                  }

                  DebugType.DetailedInfo.trace("Animal id=%d lured by player \"%s\"", new Object[]{this.getOnlineID(), chr.getUsername()});
                  chr.luredAnimals.add(this);
                  this.luredBy = chr;
                  this.luredStartTimer = Rand.Next(100, 200);
                  if (GameServer.server) {
                     GameServer.addXp(chr, Perks.Husbandry, Rand.Next(5, 10));
                  } else if (!GameClient.client) {
                     chr.getXp().AddXP(Perks.Husbandry, Rand.Next(5, 10));
                  }
               }
            }
         }
      }
   }

   public ArrayList<InventoryItem> getPossibleLuringItems(IsoGameCharacter chr) {
      return this.getAllPossibleFoodFromInv(chr);
   }

   public void eatFromLured(IsoPlayer chr, InventoryItem item) {
      this.cancelLuring();
      boolean removeItem = false;
      if (item != null) {
         this.getData().eatItem(item, false);
         this.setVariable("idleAction", "eat");
         this.faceThisObject(chr);
         this.addAcceptance(chr, 5.0F);
      }
   }

   public Position3D getAttachmentWorldPos(String attachmentName, Position3D pos) {
      if (this.legsSprite != null && this.legsSprite.modelSlot != null && this.legsSprite.modelSlot.model != null) {
         ModelAttachment attach = this.legsSprite.modelSlot.model.getAttachmentById(attachmentName);
         if (attach == null) {
            return null;
         }

         Matrix4f xfrm = ModelInstanceRenderData.makeAttachmentTransform(attach, BaseVehicle.allocMatrix4f());
         ModelInstanceRenderData.applyBoneTransform(this.getModelInstance(), attach.getBone(), xfrm);
         Matrix4f chrXfrm = BaseVehicle.allocMatrix4f();
         chrXfrm.translation(this.getX(), this.getZ(), this.getY());
         chrXfrm.rotateY(-this.getAnimationPlayer().getRenderedAngle() + 0.0F);
         chrXfrm.scale(-1.5F * this.getAnimalSize(), 1.5F * this.getAnimalSize(), 1.5F * this.getAnimalSize());
         chrXfrm.mul(xfrm, xfrm);
         BaseVehicle.releaseMatrix4f(chrXfrm);
         xfrm.getTranslation(tempVector3f);
         BaseVehicle.releaseMatrix4f(xfrm);
         pos.x = tempVector3f.x;
         pos.y = tempVector3f.z;
         pos.z = PZMath.fastfloor(tempVector3f.y) + (tempVector3f.y + 32.0F) % 1.0F / 2.44949F;
         return pos;
      } else {
         return null;
      }
   }

   public Position3D getAttachmentWorldPos(String attachmentName) {
      return this.getAttachmentWorldPos(attachmentName, new Position3D());
   }

   public void carCrash(float delta, boolean front) {
      if (!(this.getHealth() < 0.0F) && !(delta < 2.0F)) {
         float divide = 15.0F;
         if (!front) {
            divide = 30.0F;
         }

         this.setHealth(this.getHealth() - delta * this.getData().getHealthLoss(divide));
      }
   }

   public String getMilkAnimPreset() {
      return this.adef.milkAnimPreset;
   }

   public void pathToCharacter(IsoGameCharacter target) {
      int ropeLength = 15;
      if (this.data.getAttachedPlayer() == null || !(this.data.getAttachedPlayer().DistToProper(target) > 15.0F)) {
         if (this.data.getAttachedTree() != null) {
            int treeX = this.data.getAttachedTreeX();
            int treeY = this.data.getAttachedTreeY();
            if (IsoUtils.DistanceTo(treeX + 0.5F, treeY + 0.5F, target.getX(), target.getY()) > 15.0F) {
               return;
            }
         }

         this.getPathFindBehavior2().pathToCharacter(target);
         this.pathToAux(target.getX(), target.getY(), target.getZ());
      }
   }

   public void pathToLocation(int x, int y, int z) {
      int ropeLength = 15;
      if (this.data.getAttachedPlayer() == null
         || this.data.getAttachedPlayer().getCurrentSquare() == null
         || !(this.data.getAttachedPlayer().getCurrentSquare().DistToProper(x, y) > 15.0F)) {
         if (this.data.getAttachedTree() != null) {
            int treeX = this.data.getAttachedTreeX();
            int treeY = this.data.getAttachedTreeY();
            if (IsoUtils.DistanceTo(treeX + 0.5F, treeY + 0.5F, x + 0.5F, y + 0.5F) > 15.0F) {
               return;
            }
         }

         this.getPathFindBehavior2().pathToLocation(x, y, z);
         this.pathToAux(x, y, z);
      }
   }

   public void pathToTrough(IsoFeedingTrough trough) {
      if (trough != null) {
         IsoGridSquare sq1 = IsoWorld.instance.currentCell.getGridSquare(trough.getX(), trough.getY() - 1.0F, trough.getZ());
         IsoGridSquare sq2 = IsoWorld.instance.currentCell.getGridSquare(trough.getX(), trough.getY() + 1.0F, trough.getZ());
         IsoGridSquare sq3 = IsoWorld.instance.currentCell.getGridSquare(trough.getX() - 1.0F, trough.getY(), trough.getZ());
         IsoGridSquare sq4 = IsoWorld.instance.currentCell.getGridSquare(trough.getX() + 1.0F, trough.getY(), trough.getZ());
         if (sq1 != null && sq2 != null && sq3 != null && sq4 != null) {
            IsoGridSquare choosenSq = null;
            if (!trough.north) {
               sq3 = sq1;
               sq4 = sq2;
               sq1 = IsoWorld.instance.currentCell.getGridSquare(trough.getX() + 1.0F, trough.getY(), trough.getZ());
               sq2 = IsoWorld.instance.currentCell.getGridSquare(trough.getX() - 1.0F, trough.getY(), trough.getZ());
            }

            boolean sq1Free = sq1.isFree(false) && !sq1.isWallTo(trough.square) && !sq1.isWindowTo(trough.square);
            boolean sq2Free = sq2.isFree(false) && !sq2.isWallTo(trough.square) && !sq2.isWindowTo(trough.square);
            boolean sq3Free = sq3.isFree(false) && !sq3.isWallTo(trough.square) && !sq3.isWindowTo(trough.square);
            boolean sq4Free = sq4.isFree(false) && !sq4.isWallTo(trough.square) && !sq4.isWindowTo(trough.square);
            if (sq1Free && (sq1.DistToProper(this) < sq2.DistToProper(this) || !sq2Free)) {
               choosenSq = sq1;
            }

            if (sq2Free && (sq2.DistToProper(this) < sq1.DistToProper(this) || !sq1Free)) {
               choosenSq = sq2;
            }

            if (choosenSq == null) {
               if (sq3Free && (sq3.DistToProper(this) < sq4.DistToProper(this) || !sq4Free)) {
                  choosenSq = sq3;
               }

               if (sq4Free && (sq4.DistToProper(this) < sq3.DistToProper(this) || !sq3Free)) {
                  choosenSq = sq4;
               }
            }

            if (choosenSq != null) {
               if (!choosenSq.isFree(false)) {
                  if (this.ignoredTrough.contains(trough)) {
                     this.ignoredTrough.add(trough);
                  }

                  return;
               }

               if (this.adef.distToEat < 1.0F) {
                  this.pathToLocation(trough.getXi(), trough.getYi(), trough.getZi());
               } else {
                  this.pathToLocation(choosenSq.getX(), choosenSq.getY(), choosenSq.getZ());
               }
            }
         }
      }
   }

   public boolean shouldBreakObstaclesDuringPathfinding() {
      return !this.adef.canThump ? false : this.getHunger() > 0.8F || this.getThirst() > 0.8F;
   }

   public float getFeelersize() {
      return 0.8F;
   }

   public boolean animalShouldThump() {
      if (!this.adef.canThump) {
         return false;
      }

      if (this.attackedBy != null) {
         return true;
      }

      if (!(this.getStats().get(CharacterStat.THIRST) >= 0.9F) && !(this.getStats().get(CharacterStat.HUNGER) >= 0.9F)) {
         this.thumpDelay = 20000.0F;
      } else {
         if (this.thumpDelay == 0.0F) {
            return true;
         }

         this.thumpDelay = this.thumpDelay - GameTime.getInstance().getMultiplier();
         if (this.thumpDelay < 0.0F) {
            this.thumpDelay = 0.0F;
         }
      }

      return this.getStress() >= 80.0F;
   }

   public boolean tryThump(IsoGridSquare square) {
      if (this.isAnimalAttacking() || !this.animalShouldThump()) {
         return false;
      }

      if (!this.isAnimalMoving()) {
         return false;
      }

      IsoGridSquare feeler;
      if (square != null) {
         feeler = square;
      } else {
         feeler = this.getFeelerTile(this.getFeelersize());
      }

      if (feeler != null && this.current != null) {
         IsoObject obj = this.current.testCollideSpecialObjects(feeler);
         if (obj == null) {
            obj = this.current.getHoppableTo(feeler);
         }

         IsoDoor door = (IsoDoor)Type.tryCastTo(obj, IsoDoor.class);
         IsoThumpable thumpable = (IsoThumpable)Type.tryCastTo(obj, IsoThumpable.class);
         if (thumpable == null && door == null) {
            return false;
         }

         this.thumpTarget = (IsoObject)(thumpable != null ? thumpable : door);
         this.setPath2(null);
         return true;
      } else {
         return false;
      }
   }

   public float getAnimalTrailerSize() {
      return this.adef.trailerBaseSize * this.getAnimalSize();
   }

   public boolean canBePet() {
      return this.adef.canBePet;
   }

   public void debugRandomIdleAnim() {
      if (this.isAnimalSitting() && this.adef.sittingTypeNbr > 0) {
         this.setVariable("sittingAnim", "sit" + Rand.Next(1, this.adef.sittingTypeNbr + 1));
      } else {
         this.setVariable("idleAction", "idle" + Rand.Next(1, this.adef.idleTypeNbr + 1));
      }
   }

   public void debugRandomHappyAnim() {
      this.setVariable("idleAction", "happy" + Rand.Next(1, this.adef.happyAnim + 1));
   }

   public DesignationZoneAnimal getDZone() {
      return this.dZone;
   }

   public void setDZone(DesignationZoneAnimal dZone) {
      if (this.dZone != null) {
         this.dZone.removeAnimal(this);
      }

      this.dZone = dZone;
      if (dZone != null) {
         dZone.addAnimal(this);
      }
   }

   public ArrayList<DesignationZoneAnimal> getConnectedDZone() {
      return this.connectedDZone;
   }

   public boolean haveMatingSeason() {
      return this.adef.matingPeriodStart != 0 && this.adef.matingPeriodEnd != 0;
   }

   public boolean isInMatingSeason() {
      if (SandboxOptions.instance.animalMatingSeason.getValue() && this.haveMatingSeason()) {
         int currentMonth = GameTime.getInstance().getMonth() + 1;
         int start = this.adef.matingPeriodStart;
         int end = this.adef.matingPeriodEnd;
         return start < end ? currentMonth >= start && currentMonth < end : currentMonth < end || currentMonth >= start;
      } else {
         return true;
      }
   }

   public int getMinAgeForBaby() {
      AnimalAllele allele = this.getUsedGene("ageToGrow");
      float aValue = 1.0F;
      if (allele != null) {
         aValue = allele.currentValue;
      }

      int baseValue = this.adef.minAgeForBaby;
      float modifier = 0.25F - aValue / 4.0F + 1.0F;
      return (int)(baseValue * modifier);
   }

   public boolean isHeld() {
      return this.heldBy != null;
   }

   public void pathFailed() {
      if (this.eatFromTrough != null) {
         this.ignoredTrough.add(this.eatFromTrough);
      }

      if (this.drinkFromTrough != null) {
         this.ignoredTrough.add(this.drinkFromTrough);
      }

      if (this.getBehavior().behaviorObject instanceof IsoFeedingTrough isoFeedingTrough) {
         this.ignoredTrough.add(isoFeedingTrough);
      }

      this.stopAllMovementNow();
   }

   public AnimalSoundState getAnimalSoundState(String slot) {
      Objects.requireNonNull(slot);
      AnimalSoundState ass = this.animalSoundState.get(slot);
      if (ass == null) {
         ass = new AnimalSoundState(this);
         this.animalSoundState.put(slot, ass);
      }

      return ass;
   }

   public void playDeadSound() {
      if (this.isCloseKilled()) {
         IsoGameCharacter attacker = this.getAttackedBy();
         if (attacker != null && attacker.getPrimaryHandItem() instanceof HandWeapon weapon) {
            this.getEmitter().playSoundImpl(weapon.getZombieHitSound(), this);
         } else {
            this.getEmitter().playSoundImpl("HeadStab", this);
         }
      }

      this.playBreedSound("death");
   }

   public void updateVocalProperties() {
      if (!GameServer.server) {
         AnimalSoundState ass = this.getAnimalSoundState("voice");
         if (ass.getEventInstance() != 0L && !this.getEmitter().isPlaying(ass.getEventInstance())) {
            ass.stop();
         }

         boolean bListenerInRange = SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 70.0F);
         if (this.isAlive() && bListenerInRange) {
            this.chooseIdleSound();
            AnimalVocalsManager.instance.addCharacter(this);
         }

         if (this.isDead() && bListenerInRange) {
            AnimalVocalsManager.instance.addCharacter(this);
         }
      }
   }

   public void playNextFootstepSound() {
      if (!StringUtils.isNullOrWhitespace(this.nextFootstepSound)) {
         this.playBreedSound(this.nextFootstepSound);
         this.nextFootstepSound = null;
      }
   }

   public void onPlayBreedSoundEvent(String id) {
      if (!"run".equalsIgnoreCase(id) && !"walk".equalsIgnoreCase(id) && !"walkFront".equalsIgnoreCase(id) && !"walkBack".equalsIgnoreCase(id)) {
         this.playBreedSound(id);
      } else {
         this.nextFootstepSound = id;
         AnimalFootstepManager.instance.addCharacter(this);
      }
   }

   public long playBreedSound(String id) {
      AnimalBreed breed = this.getBreed();
      if (breed == null) {
         return 0L;
      }

      Sound breedSound = breed.getSound(id);
      if (breedSound == null) {
         return 0L;
      }

      if (breedSound.slot != null) {
         AnimalSoundState ass = this.getAnimalSoundState(breedSound.slot);
         if (ass.isPlaying() && ass.getPriority() > breedSound.priority) {
            return 0L;
         }

         ass.setDesiredSoundName(breedSound.soundName);
         ass.setDesiredSoundPriority(breedSound.priority);
         if ("death".equalsIgnoreCase(id)) {
            ass.setIntervalExpireTime(breedSound.soundName, System.currentTimeMillis() + 10000L);
         }

         return ass.start(breedSound.soundName, breedSound.priority);
      } else {
         return this.playSoundLocal(breedSound.soundName);
      }
   }

   private void chooseIdleSound() {
      AnimalSoundState ass = this.getAnimalSoundState("voice");
      if (this.isDead()) {
         ass.setDesiredSoundName(null);
         ass.setDesiredSoundPriority(0);
         this.forceNextIdleSound = null;
      } else if (this.data != null && this.data.getBreed() != null) {
         String desiredSound = "idle";
         Sound absStressed = this.data.getBreed().getSound("stressed");
         if (absStressed != null && ass.isPlaying(absStressed.soundName)) {
            desiredSound = "stressed";
         } else if (this.getStress() > 50.0F && (absStressed != null && ass.isPlaying(absStressed.soundName) || Rand.Next(100) < this.getStress())) {
            desiredSound = "stressed";
         }

         if ("idle".equalsIgnoreCase(desiredSound) && this.data.getBreed().isSoundDefined("idle_walk") && this.isAnimalMoving()) {
            desiredSound = "idle_walk";
         }

         boolean bForced = this.forceNextIdleSound != null;
         if (bForced) {
            desiredSound = this.forceNextIdleSound;
         }

         Sound abs = this.data.getBreed().getSound(desiredSound);
         if (!ass.isPlaying() || abs == null || abs.priority >= ass.getPriority()) {
            if (abs == null) {
               ass.setDesiredSoundName(null);
               ass.setDesiredSoundPriority(0);
            } else {
               ass.setDesiredSoundName(abs.soundName);
               ass.setDesiredSoundPriority(abs.priority);
               if (ass.isPlayingDesiredSound() && abs.isIntervalValid()) {
                  long expireTime = ass.getIntervalExpireTime(abs.soundName);
                  if (expireTime < System.currentTimeMillis()) {
                     ass.setIntervalExpireTime(abs.soundName, System.currentTimeMillis() + Rand.Next(abs.intervalMin, abs.intervalMax) * 1000L);
                  }
               }

               if (bForced && ass.isPlaying(abs.soundName)) {
                  this.forceNextIdleSound = null;
               }
            }
         }
      } else {
         ass.setDesiredSoundName(null);
         ass.setDesiredSoundPriority(0);
         this.forceNextIdleSound = null;
      }
   }

   public void playStressedSound() {
      String desiredSound = "stressed";
      AnimalSoundState ass = this.getAnimalSoundState("voice");
      if (this.isDead()) {
         ass.setDesiredSoundName(null);
         ass.setDesiredSoundPriority(0);
      } else if (this.data != null && this.data.getBreed() != null) {
         Sound abs = this.data.getBreed().getSound("stressed");
         if (!ass.isPlaying() || abs == null || abs.priority >= ass.getPriority()) {
            if (abs == null) {
               ass.setDesiredSoundName(null);
               ass.setDesiredSoundPriority(0);
            } else {
               this.forceNextIdleSound = "stressed";
               ass.setIntervalExpireTime(abs.soundName, 0L);
               ass.setDesiredSoundName(abs.soundName);
               ass.setDesiredSoundPriority(abs.priority);
            }
         }
      } else {
         ass.setDesiredSoundName(null);
         ass.setDesiredSoundPriority(0);
      }
   }

   public void updateLoopingSounds() {
      this.updateRunLoopingSound();
      this.updateWalkLoopingSound();
   }

   public void updateRunLoopingSound() {
      Sound abs = this.getBreed().getSound("runloop");
      if (abs != null && abs.slot != null) {
         boolean isRunAnimPlaying = false;
         AnimLayer rootLayer = this.getAdvancedAnimator().getRootLayer();
         if (rootLayer != null) {
            for (LiveAnimNode animNode : rootLayer.getLiveAnimNodes()) {
               if ("runPathfind".equalsIgnoreCase(animNode.getName())) {
                  isRunAnimPlaying = true;
                  break;
               }

               if ("run".equalsIgnoreCase(animNode.getName())) {
                  isRunAnimPlaying = true;
                  break;
               }
            }
         }

         boolean bListenerInRange = SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 10.0F);
         if (!bListenerInRange) {
            isRunAnimPlaying = false;
         }

         AnimalSoundState ass = this.getAnimalSoundState(abs.slot);
         if (ass.isPlaying()) {
            if (!isRunAnimPlaying) {
               ass.stop();
            }
         } else if (isRunAnimPlaying) {
            ass.setDesiredSoundName(abs.soundName);
            ass.setDesiredSoundPriority(abs.priority);
            ass.start(abs.soundName, abs.priority);
         }
      }
   }

   public void updateWalkLoopingSound() {
      Sound abs = this.getBreed().getSound("walkloop");
      if (abs != null && abs.slot != null) {
         boolean isWalkAnimPlaying = false;
         AnimLayer rootLayer = this.getAdvancedAnimator().getRootLayer();
         if (rootLayer != null) {
            List<LiveAnimNode> animNodes = rootLayer.getLiveAnimNodes();

            for (int i = 0; i < animNodes.size(); i++) {
               LiveAnimNode animNode = animNodes.get(i);
               if ("defaultPathfind".equalsIgnoreCase(animNode.getName())) {
                  isWalkAnimPlaying = true;
                  break;
               }

               if ("defaultWalk".equalsIgnoreCase(animNode.getName())) {
                  isWalkAnimPlaying = true;
                  break;
               }
            }
         }

         boolean bListenerInRange = SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 10.0F);
         if (!bListenerInRange) {
            isWalkAnimPlaying = false;
         }

         AnimalSoundState ass = this.getAnimalSoundState(abs.slot);
         if (ass.isPlaying()) {
            if (!isWalkAnimPlaying) {
               ass.stop();
            }
         } else if (isWalkAnimPlaying) {
            ass.setDesiredSoundName(abs.soundName);
            ass.setDesiredSoundPriority(abs.priority);
            ass.start(abs.soundName, abs.priority);
         }
      }
   }

   public IsoAnimal getMother() {
      return this.mother;
   }

   public void setMother(IsoAnimal mom) {
      this.mother = mom;
      if (mom.getBabies() == null) {
         mom.babies = new ArrayList<>();
      }

      for (int i = 0; i < mom.getBabies().size(); i++) {
         if (mom.getBabies().get(i).getAnimalID() == this.getAnimalID() || mom.getBabies().get(i) == this) {
            mom.removeBaby(mom.getBabies().get(i));
            break;
         }
      }

      mom.getBabies().add(this);
   }

   public boolean canBePicked(IsoGameCharacter chr) {
      return !chr.isUnlimitedCarry() && !chr.isGodMod()
         ? this.adef.canBePicked && this.getData().getWeight() < 40 + chr.getPerkLevel(Perks.Strength) * 7
         : true;
   }

   public boolean canBeKilledWithoutWeapon() {
      return this.adef.canBeKilledWithoutWeapon;
   }

   public int getAnimalID() {
      return this.animalId;
   }

   public void setAnimalID(int id) {
      this.animalId = id;
   }

   public void setItemID(int itemId) {
      this.itemId = itemId;
   }

   public int getItemID() {
      return this.itemId;
   }

   public String getNextStageAnimalType() {
      if (this.getData().currentStage == null) {
         return "";
      } else {
         return this.isFemale() ? this.getData().currentStage.nextStage : this.getData().currentStage.nextStageMale;
      }
   }

   public void debugForceEgg() {
      this.getData().eggsToday = 0;
      this.getData().eggTime = Long.valueOf(GameTime.instance.getCalender().getTimeInMillis() / 1000L).intValue() - 10;
      this.getData().checkEggs(GameTime.instance.getCalender(), false);
   }

   public boolean isWild() {
      return this.wild;
   }

   public void setWild(boolean b) {
      if (b || this.adef.canBeDomesticated) {
         if (!b) {
            this.migrationGroup = null;
            this.virtualId = 0.0;
         } else {
            this.setDebugStress(Rand.Next(50.0F, 80.0F));
         }

         this.wild = b;
      }
   }

   public void alertOtherAnimals(IsoMovingObject chr, boolean alert) {
      for (int x = (int)this.getX() - 5; x < (int)this.getX() + 5; x++) {
         for (int y = (int)this.getY() - 5; y < (int)this.getY() + 5; y++) {
            IsoGridSquare sq = this.getSquare().getCell().getGridSquare(x, y, this.getZ());
            if (sq != null && !sq.getAnimals().isEmpty()) {
               for (int i = 0; i < sq.getAnimals().size(); i++) {
                  IsoAnimal animal = (IsoAnimal)sq.getAnimals().get(i);
                  if (animal != this) {
                     if (animal.adef.canBeAlerted && animal.getBehavior().lastAlerted <= 0.0F && alert) {
                        animal.setIsAlerted(true);
                        animal.alertedChr = this.alertedChr;
                     } else {
                        animal.spottedChr = this.spottedChr;
                     }
                  }
               }
            }
         }
      }
   }

   public void debugForceSit() {
      if (!this.isAnimalSitting()) {
         this.behavior.sitInTime = Long.valueOf(GameTime.instance.getCalender().getTimeInMillis() / 1000L).intValue() - 10;
         this.behavior.sitOutTime = this.behavior.sitInTime + 36000;
      } else {
         this.behavior.sitOutTime = Long.valueOf(GameTime.instance.getCalender().getTimeInMillis() / 1000L).intValue() - 10;
         this.behavior.sitInTime = 0;
      }

      this.behavior.checkSit();
   }

   public boolean isAlerted() {
      return this.alerted;
   }

   public void setIsAlerted(boolean b) {
      this.alerted = b;
   }

   public boolean shouldFollowWall() {
      return this.shouldFollowWall;
   }

   public void setShouldFollowWall(boolean b) {
      this.shouldFollowWall = b;
   }

   public boolean readyToBeMilked() {
      return this.data.getMilkQuantity() > 1.0F && this.canBeMilked();
   }

   public boolean readyToBeSheared() {
      return this.data.getWoolQuantity() > 1.0F && this.canBeSheared();
   }

   public boolean haveHappyAnim() {
      return this.adef.happyAnim > 0;
   }

   public boolean canHaveEggs() {
      return this.adef.eggsPerDay > 0;
   }

   public boolean needHutch() {
      return this.adef.hutches != null && !this.adef.hutches.isEmpty();
   }

   public boolean canPoop() {
      return this.adef.dung != null;
   }

   public int getMinClutchSize() {
      float mod = 1.0F;
      AnimalAllele clutchAllele = this.getUsedGene("eggClutch");
      if (clutchAllele != null) {
         mod = clutchAllele.currentValue;
      }

      return Float.valueOf(this.adef.minClutchSize * mod).intValue();
   }

   public int getMaxClutchSize() {
      float mod = 1.0F;
      AnimalAllele clutchAllele = this.getUsedGene("eggClutch");
      if (clutchAllele != null) {
         mod = clutchAllele.currentValue;
      }

      return Float.valueOf(this.adef.maxClutchSize * mod).intValue();
   }

   public int getCurrentClutchSize() {
      return this.getData().clutchSize;
   }

   public boolean attackOtherMales() {
      return !this.isBaby() && !this.getDescriptor().isFemale() && !this.adef.dontAttackOtherMale;
   }

   public boolean shouldAnimalStressAboveGround() {
      return this.adef.stressAboveGround && this.getZ() > 0.0F;
   }

   public boolean canClimbStairs() {
      return this.adef.canClimbStairs;
   }

   public void forceWanderNow() {
      this.setStateEventDelayTimer(0.0F);
   }

   public boolean canClimbFences() {
      return this.adef.canClimbFences;
   }

   public void climbOverFence(IsoDirections dir) {
      if (this.current != null && !this.getVariableBoolean("ClimbFence")) {
         IsoGridSquare oppositeSq = this.current.getAdjacentSquare(dir);
         AnimalClimbOverFenceState.instance().setParams(this, dir);
         this.setVariable("ClimbFence", true);
         if (GameClient.client && this.isLocalPlayer()) {
            INetworkPacket.send(PacketType.AnimalEvent, new Object[]{this, this.getX(), this.getY(), this.getZ()});
         }
      }
   }

   public boolean needMom() {
      return this.adef.needMom;
   }

   public int getFertilizedTimeMax() {
      return this.adef.fertilizedTimeMax;
   }

   public boolean isLocalPlayer() {
      return !GameClient.client;
   }

   public float getThirstBoost() {
      return this.adef.thirstBoost;
   }

   public float getHungerBoost() {
      return this.adef.hungerBoost;
   }

   public void removeBaby(IsoAnimal baby) {
      if (this.babies != null) {
         this.babies.remove(baby);
      }
   }

   public void remove() {
      if (this.getMother() != null) {
         this.getMother().removeBaby(this);
      }

      IsoPlayer player = this.getData().getAttachedPlayer();
      if (player != null) {
         player.getAttachedAnimals().remove(this);
      }

      this.getData().setAttachedPlayer(null);
      this.delete();
   }

   public void removeFromUpdateLists() {
      AnimalInstanceManager.getInstance().remove(this);
      if (GameServer.server && !this.isDead()) {
         AnimalSynchronizationManager.getInstance().delete(this.getOnlineID());
      }
   }

   public void delete() {
      DebugType.Animal.debugln("Animal delete id=%d", new Object[]{this.getOnlineID()});
      this.removeFromWorld();
      this.removeFromSquare();
      this.setSquare(null);
      this.removeFromUpdateLists();
   }

   public InventoryItem canEatFromTrough(IsoFeedingTrough trough) {
      if (this.adef.eatTypeTrough != null && trough.getContainer() != null) {
         for (int i = 0; i < trough.getContainer().getItems().size(); i++) {
            InventoryItem item = (InventoryItem)trough.getContainer().getItems().get(i);
            if (!(item instanceof Food food && food.isRotten())) {
               if (this.adef.eatTypeTrough.contains("All")
                  || this.adef.eatTypeTrough.contains(item.getFullType())
                  || this.adef.eatTypeTrough.contains(item.getAnimalFeedType())) {
                  return item;
               }

               if (item instanceof Food food && this.adef.eatTypeTrough.contains(food.getFoodType())) {
                  return item;
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public float getThumpDelay() {
      return this.thumpDelay;
   }

   public float getBloodQuantity() {
      float weightPerc = (this.getData().getWeight() - this.adef.minWeight) / (this.adef.maxWeight - this.getData().getWeight());
      return (this.adef.minBlood + (this.adef.maxBlood - this.adef.minBlood) * weightPerc) / 100.0F;
   }

   public int getFeatherNumber() {
      return !this.isBaby() && !StringUtils.isNullOrEmpty(this.getBreed().featherItem) && this.getBreed().maxFeather > 0
         ? (int)(this.getBreed().maxFeather * this.getData().getSize())
         : 0;
   }

   public String getFeatherItem() {
      return this.getBreed().featherItem;
   }

   public boolean isHappy() {
      String idleAction = this.getVariableString("idleAction");
      return !StringUtils.isNullOrEmpty(idleAction) && idleAction.startsWith("happy");
   }

   public boolean shouldBeSkeleton() {
      return this.shouldBeSkeleton;
   }

   public void setShouldBeSkeleton(boolean shouldBeSkeleton) {
      this.shouldBeSkeleton = shouldBeSkeleton;
   }

   public ArrayList<String> getGeneticDisorder() {
      return this.geneticDisorder;
   }

   public void copyGeneticDisorder(Collection<String> disorders) {
      this.geneticDisorder.clear();
      this.geneticDisorder.addAll(disorders);
   }

   public ArrayList<IsoAnimal> getBabies() {
      return this.babies;
   }

   public boolean canRagdoll() {
      return false;
   }

   public float getZoneAcceptance() {
      return this.zoneAcceptance;
   }

   public float getPlayerAcceptance(IsoPlayer chr) {
      if (this.playerAcceptanceList.isEmpty()) {
         return 0.0F;
      }

      if (!this.playerAcceptanceList.containsKey(chr.getOnlineID())) {
         this.playerAcceptanceList.put(chr.getOnlineID(), Rand.Next(0.0F, 20.0F));
      }

      return Math.round(this.playerAcceptanceList.get(chr.getOnlineID()));
   }

   public static void addAnimalPart(AnimalPart part, IsoPlayer player, IsoDeadBody carcass) {
      float minNb = part.minNb * carcass.getAnimalSize();
      float maxNb = part.maxNb * carcass.getAnimalSize();
      int nb = part.nb;
      float meatRatio = (float)((Double)carcass.getModData().rawget("meatRatio")).doubleValue();
      if (meatRatio <= 0.0F) {
         meatRatio = 1.0F;
      }

      InventoryItem item = InventoryItemFactory.CreateItem(part.item);
      if (item instanceof Food) {
         minNb *= meatRatio;
         maxNb *= meatRatio;
      }

      if (nb == -1) {
         nb = Rand.Next((int)minNb, (int)maxNb);
      }

      if (nb < 1) {
         nb = 1;
      }

      for (int i = 0; i < nb; i++) {
         item = InventoryItemFactory.CreateItem(part.item);
         if (item instanceof Food food) {
            modifyMeat(food, carcass.getAnimalSize(), meatRatio);
         }

         player.getInventory().AddItem(item);
         if (GameServer.server) {
            GameServer.sendAddItemToContainer(player.getInventory(), item);
         }
      }
   }

   public static void modifyMeat(Food item, float size, float meatRatio) {
      item.setHungChange(item.getBaseHunger() * size * meatRatio * Rand.Next(0.9F, 1.1F));
      item.setBaseHunger(item.getHungerChange());
      item.setCalories(item.getCalories() * size * meatRatio * Rand.Next(0.9F, 1.1F));
      item.setLipids(item.getLipids() * size * meatRatio * Rand.Next(0.9F, 1.1F));
      item.setProteins(item.getProteins() * size * meatRatio * Rand.Next(0.9F, 1.1F));
   }

   public boolean shouldStartFollowWall() {
      return this.getData().getAttachedPlayer() != null
         || this.getStress() > 20.0F && this.getVariableBoolean(AnimationVariableHandles.animalRunning)
         || this.isWild();
   }

   public float getCorpseSize() {
      return this.adef.corpseSize;
   }

   public float getCorpseLength() {
      return this.adef.corpseLength;
   }

   public void setOnHook(boolean onhook) {
      this.onHook = onhook;
   }

   public boolean isOnHook() {
      return this.onHook;
   }

   public AnimalDefinitions getAdef() {
      return this.adef;
   }

   public IsoButcherHook getHook() {
      return this.hook;
   }

   public void setHook(IsoButcherHook hook) {
      this.hook = hook;
   }

   public void reattachBackToHook() {
      if (this.attachBackToHookX != 0 || this.attachBackToHookY != 0) {
         if (this.getSquare() != null) {
            IsoGridSquare hookSq = this.getSquare().getCell().getGridSquare(this.attachBackToHookX, this.attachBackToHookY, this.attachBackToHookZ);
            if (hookSq != null && hookSq.getObjects() != null) {
               for (int i = 0; i < hookSq.getObjects().size(); i++) {
                  if (hookSq.getObjects().get(i) instanceof IsoButcherHook hook) {
                     hook.reattachAnimal(this);
                     this.attachBackToHookX = 0;
                     this.attachBackToHookY = 0;
                     this.attachBackToHookZ = 0;
                     return;
                  }
               }
            }
         }
      }
   }

   private void ensureCorrectSkin() {
      if (!StringUtils.isNullOrEmpty(AnimalDefinitions.getDef(this.getAnimalType()).textureSkinned)
         && ((KahluaTableImpl)this.getModData()).rawgetBool("skinned")
         && !this.getAnimalVisual().getSkinTexture().equalsIgnoreCase(AnimalDefinitions.getDef(this.getAnimalType()).textureSkinned)) {
         this.getAnimalVisual().setSkinTextureName(AnimalDefinitions.getDef(this.getAnimalType()).textureSkinned);
         this.resetModel();
         this.resetModelNextFrame();
      }
   }

   public String getTypeAndBreed() {
      return this.getAnimalType() + this.getBreed().getName();
   }

   public static IsoAnimal createAnimalFromCorpse(IsoDeadBody body) {
      IsoAnimal animal = new IsoAnimal(
         body.getSquare().getCell(),
         body.getSquare().getX(),
         body.getSquare().getY(),
         body.getSquare().getZ(),
         ((KahluaTableImpl)body.getModData()).rawgetStr("AnimalType"),
         ((KahluaTableImpl)body.getModData()).rawgetStr("AnimalBreed")
      );
      animal.setCustomName(body.getCustomName());
      animal.setModData(body.getModData());
      animal.getAnimalVisual().setSkinTextureName(body.getAnimalVisual().getSkinTexture());
      animal.copyGenome(body.getAnimalGenome());
      animal.copyGeneticDisorder(body.getAnimalGeneticDisorder());
      animal.getData().setWeight(body.getWeight());
      return animal;
   }

   public void updateLOS() {
      float locX = this.getX();
      float locY = this.getY();
      float locZ = this.getZ();
      this.spottedList.clear();

      // pzopt: animalLosFast. BaseAnimalBehavior.spotted with a zombie does nothing but its two first lines (spottedChr cleared,
      // lastAlerted ticked down to 0) when the animal does not flee zombies, or when the zombie is more than 10 squares away
      // (every branch that acts on a zombie needs it within 10, or within 3 for a wild animal; no random roll is reached).
      // Those zombies are counted as the walk passes them (the stock filters and the stock distance) and their ticks applied
      // in one step before the next spotted() call that can act and at the end, so the ticks keep their order against the
      // calls that can set lastAlerted. The rest of the walk is the stock body.
      zombie.characters.animals.behavior.BaseAnimalBehavior pzoptBehavior = this.getBehavior(); // pzopt
      boolean pzoptFold = pzopt.Config.ANIMAL_LOS_FAST && pzopt.Overrides.enabled() && pzopt.GtAb.on(pzopt.GtAb.ANIMAL_LOS) // pzopt
         && pzoptBehavior != null && this.adef != null; // pzopt
      boolean pzoptNoFlee = pzoptFold && !this.adef.fleeZombies; // pzopt
      int pzoptTicks = 0; // pzopt: zombies spotted since the last flush
      if (pzoptFold && pzopt.AnimalLosSnapshot.enabled()) { // pzopt: animalLosSnapshot, the same fold over the frame's snapshot
         this.pzoptUpdateLosSnapshot(pzoptBehavior, pzoptNoFlee, locX, locY); // pzopt
         return; // pzopt
      } // pzopt
      pzopt.CellObjectSet pzoptSet = pzoptFold ? pzopt.CellObjectSet.of(this.getCell().getObjectList()) : null; // pzopt: animalLosWalk (tail pass)
      if (pzoptSet != null && pzopt.AnimalLosWalk.enabled()) { // pzopt: the same fold, exact, over the set's cached order
         this.pzoptUpdateLosWalk(pzoptBehavior, pzoptNoFlee, locX, locY, pzoptSet); // pzopt
         return; // pzopt
      } // pzopt

      for (IsoMovingObject movingObject : this.getCell().getObjectList()) {
         if (pzoptFold && movingObject instanceof IsoZombie pzoptZombie) { // pzopt: animalLosFast, the stock filters for a zombie
            if (!pzoptZombie.isReanimatedForGrappleOnly() && !(PZMath.abs(pzoptZombie.getZ() - this.getZ()) > 1.0F) && pzoptZombie.getCurrentSquare() != null) { // pzopt
               float pzoptDist = pzoptNoFlee ? 0.0F : IsoUtils.DistanceTo(pzoptZombie.getX(), pzoptZombie.getY(), locX, locY); // pzopt: the distance stock passes
               if (pzoptNoFlee || pzoptDist > 10.0F) { // pzopt: nothing but the tick
                  pzoptTicks++; // pzopt
               } else { // pzopt: a near zombie: the stock call, after the ticks before it
                  if (pzoptTicks > 0) { // pzopt
                     this.pzoptSpottedTicks(pzoptBehavior, pzoptTicks); // pzopt
                     pzoptTicks = 0; // pzopt
                  } // pzopt
                  this.getBehavior().spotted(pzoptZombie, false, pzoptDist); // pzopt
               } // pzopt
            } // pzopt
            continue; // pzopt
         } // pzopt
         if (!(movingObject instanceof IsoPhysicsObject)
            && !(movingObject instanceof BaseVehicle)
            && !(movingObject instanceof IsoZombie zombie && zombie.isReanimatedForGrappleOnly())) {
            if (movingObject == this) {
               this.spottedList.add(movingObject);
            } else {
               float movingObjectX = movingObject.getX();
               float movingObjectY = movingObject.getY();
               float movingObjectZ = movingObject.getZ();
               if (!(PZMath.abs(movingObjectZ - this.getZ()) > 1.0F)) {
                  float distanceToMovingObject = IsoUtils.DistanceTo(movingObjectX, movingObjectY, locX, locY);
                  IsoGridSquare chrCurrentSquare = movingObject.getCurrentSquare();
                  if (chrCurrentSquare != null) {
                     IsoGameCharacter movingCharacter = (IsoGameCharacter)Type.tryCastTo(movingObject, IsoGameCharacter.class);
                     IsoPlayer movingPlayer = (IsoPlayer)Type.tryCastTo(movingCharacter, IsoPlayer.class);
                     IsoZombie movingZombie = (IsoZombie)Type.tryCastTo(movingCharacter, IsoZombie.class);
                     if (movingZombie != null) {
                        this.getBehavior().spotted(movingZombie, false, distanceToMovingObject);
                     }

                     if (!(movingCharacter instanceof IsoAnimal)
                        && movingCharacter instanceof IsoPlayer player
                        && !movingCharacter.isInvisible()
                        && !player.isGhostMode()) {
                        if (pzoptTicks > 0) { // pzopt: animalLosFast, the zombies' ticks before this call
                           this.pzoptSpottedTicks(pzoptBehavior, pzoptTicks); // pzopt
                           pzoptTicks = 0; // pzopt
                        } // pzopt
                        this.getBehavior().spotted(movingCharacter, false, distanceToMovingObject);
                     }
                  }
               }
            }
         }
      }
      if (pzoptTicks > 0) { // pzopt: animalLosFast
         this.pzoptSpottedTicks(pzoptBehavior, pzoptTicks); // pzopt
      } // pzopt
   }

   /**
    * pzopt: animalLosSnapshot. The walk above over {@link pzopt.AnimalLosSnapshot}'s arrays (the cell's object set in its
    * own order, zombies passing the stock filters with their positions, non-animal players, animals): zombies fold into ticks
    * or a near spotted call exactly as above, players take the stock per-object body with live reads, the animal's own
    * entry adds it to its spotted list. Other objects only had a distance computed and dropped, so they are left out.
    */
   private void pzoptUpdateLosSnapshot(zombie.characters.animals.behavior.BaseAnimalBehavior behavior, boolean noFlee, float locX, float locY) { // pzopt
      pzopt.AnimalLosSnapshot.ensure(this.getCell().getObjectList()); // pzopt
      final int n = pzopt.AnimalLosSnapshot.n; // pzopt
      final byte[] kind = pzopt.AnimalLosSnapshot.kind; // pzopt
      final float[] xs = pzopt.AnimalLosSnapshot.x; // pzopt
      final float[] ys = pzopt.AnimalLosSnapshot.y; // pzopt
      final float[] zs = pzopt.AnimalLosSnapshot.z; // pzopt
      final IsoMovingObject[] objs = pzopt.AnimalLosSnapshot.obj; // pzopt
      final float myZ = this.getZ(); // pzopt
      int ticks = 0; // pzopt
      for (int i = 0; i < n; i++) { // pzopt
         byte k = kind[i]; // pzopt
         if (k == pzopt.AnimalLosSnapshot.ZOMBIE) { // pzopt
            if (PZMath.abs(zs[i] - myZ) > 1.0F) { // pzopt: the stock level filter
               continue; // pzopt
            } // pzopt
            if (noFlee) { // pzopt
               ticks++; // pzopt
               continue; // pzopt
            } // pzopt
            float dist = IsoUtils.DistanceTo(xs[i], ys[i], locX, locY); // pzopt
            if (dist > 10.0F) { // pzopt
               ticks++; // pzopt
               continue; // pzopt
            } // pzopt
            if (ticks > 0) { // pzopt
               this.pzoptSpottedTicks(behavior, ticks); // pzopt
               ticks = 0; // pzopt
            } // pzopt
            this.getBehavior().spotted((IsoZombie)objs[i], false, dist); // pzopt
         } else if (k == pzopt.AnimalLosSnapshot.ANIMAL) { // pzopt
            if (objs[i] == this) { // pzopt
               this.spottedList.add(this); // pzopt
            } // pzopt
         } else { // pzopt: a non-animal player, the stock body with live reads
            IsoMovingObject movingObject = objs[i]; // pzopt
            if (PZMath.abs(movingObject.getZ() - this.getZ()) > 1.0F) { // pzopt
               continue; // pzopt
            } // pzopt
            float dist = IsoUtils.DistanceTo(movingObject.getX(), movingObject.getY(), locX, locY); // pzopt
            if (movingObject.getCurrentSquare() == null) { // pzopt
               continue; // pzopt
            } // pzopt
            IsoPlayer player = (IsoPlayer)movingObject; // pzopt
            if (!player.isInvisible() && !player.isGhostMode()) { // pzopt
               if (ticks > 0) { // pzopt
                  this.pzoptSpottedTicks(behavior, ticks); // pzopt
                  ticks = 0; // pzopt
               } // pzopt
               this.getBehavior().spotted(player, false, dist); // pzopt
            } // pzopt
         } // pzopt
      } // pzopt
      if (ticks > 0) { // pzopt
         this.pzoptSpottedTicks(behavior, ticks); // pzopt
      } // pzopt
   } // pzopt

   /**
    * pzopt: animalLosWalk (tail pass 2026-10-13). updateLOS's fold walk above, exact, over {@link pzopt.AnimalLosWalk}'s array
    * of the cell set's entries in its iteration order (rebuilt when the set changed): every test reads the live values stock
    * reads; a zombie whose fold tick cannot change the idle animal (spottedChr null, lastAlerted 0) is passed after reading its
    * x / y only; ticks of a non-idle animal are applied one by one where the fold applies them. A change of the set inside a
    * spotted() call throws as stock's iterator does.
    */
   private void pzoptUpdateLosWalk(zombie.characters.animals.behavior.BaseAnimalBehavior behavior, boolean noFlee, float locX, float locY, pzopt.CellObjectSet set) { // pzopt
      pzopt.AnimalLosWalk.ensure(set); // pzopt
      boolean check = pzopt.Config.DEV_ANIMAL_LOS_CHECK; // pzopt: devAnimalLosCheck, the stock events listed first
      IsoMovingObject chr0 = this.spottedChr; // pzopt
      float la0 = behavior.lastAlerted; // pzopt
      if (check) { // pzopt
         pzopt.AnimalLosWalk.checkBegin(this, set, noFlee, locX, locY); // pzopt
      } // pzopt
      final int n = pzopt.AnimalLosWalk.n; // pzopt
      final byte[] kind = pzopt.AnimalLosWalk.kind; // pzopt
      final IsoMovingObject[] objs = pzopt.AnimalLosWalk.obj; // pzopt
      final int version = set.version; // pzopt
      final int size = set.size(); // pzopt
      boolean selfAdded = false; // pzopt
      int far = 0; // pzopt
      for (int i = 0; i < n; i++) { // pzopt
         byte k = kind[i]; // pzopt
         IsoMovingObject movingObject = objs[i]; // pzopt
         if (k == pzopt.AnimalLosWalk.ZOMBIE) { // pzopt
            IsoZombie zombie = (IsoZombie)movingObject; // pzopt
            if (pzopt.AnimalLosWalk.idle(this, behavior)) { // pzopt: a tick would change nothing; only a call within 10 squares can
               if (noFlee) { // pzopt
                  continue; // pzopt
               } // pzopt
               float dx = zombie.getX() - locX; // pzopt
               float dy = zombie.getY() - locY; // pzopt
               if (dx > 10.01F || dx < -10.01F || dy > 10.01F || dy < -10.01F) { // pzopt: stock's distance is above 10 here
                  far++; // pzopt
                  continue; // pzopt
               } // pzopt
            } // pzopt
            if (zombie.isReanimatedForGrappleOnly() || PZMath.abs(zombie.getZ() - this.getZ()) > 1.0F || zombie.getCurrentSquare() == null) { // pzopt: the stock filters
               continue; // pzopt
            } // pzopt
            float dist = noFlee ? 0.0F : IsoUtils.DistanceTo(zombie.getX(), zombie.getY(), locX, locY); // pzopt: the distance stock passes
            if (noFlee || dist > 10.0F) { // pzopt: the fold's tick
               pzopt.AnimalLosWalk.tick(this, behavior); // pzopt
               continue; // pzopt
            } // pzopt
            pzopt.AnimalLosWalk.callBefore(this, behavior, zombie, dist); // pzopt
            this.getBehavior().spotted(zombie, false, dist); // pzopt
            pzopt.AnimalLosWalk.callAfter(this, behavior); // pzopt
            pzopt.AnimalLosWalk.calls++; // pzopt
         } else if (k == pzopt.AnimalLosWalk.PLAYER) { // pzopt: a non-animal player, the stock body with live reads
            if (PZMath.abs(movingObject.getZ() - this.getZ()) > 1.0F) { // pzopt
               continue; // pzopt
            } // pzopt
            float dist = IsoUtils.DistanceTo(movingObject.getX(), movingObject.getY(), locX, locY); // pzopt
            if (movingObject.getCurrentSquare() == null) { // pzopt
               continue; // pzopt
            } // pzopt
            IsoPlayer player = (IsoPlayer)movingObject; // pzopt
            if (player.isInvisible() || player.isGhostMode()) { // pzopt
               continue; // pzopt
            } // pzopt
            pzopt.AnimalLosWalk.callBefore(this, behavior, player, dist); // pzopt
            this.getBehavior().spotted(player, false, dist); // pzopt
            pzopt.AnimalLosWalk.callAfter(this, behavior); // pzopt
            pzopt.AnimalLosWalk.calls++; // pzopt
         } else { // pzopt
            if (movingObject == this) { // pzopt
               this.spottedList.add(this); // pzopt
               selfAdded = true; // pzopt
            } // pzopt
            continue; // pzopt
         } // pzopt
         if (i + 1 < n && (set.version != version || set.size() != size)) { // pzopt: the set changed inside the call: stock's next() throws
            throw new java.util.ConcurrentModificationException(); // pzopt
         } // pzopt
      } // pzopt
      pzopt.AnimalLosWalk.skippedFar += far; // pzopt
      if (check) { // pzopt
         pzopt.AnimalLosWalk.checkEnd(this, behavior, chr0, la0, selfAdded); // pzopt
      } // pzopt
   } // pzopt

   /**
    * pzopt: animalLosFast. What {@code n} calls of BaseAnimalBehavior.spotted with a zombie do for an animal that does not
    * flee zombies, in the same float steps: spottedChr cleared, lastAlerted ticked down by the multiplier and held at 0.
    */
   private void pzoptSpottedTicks(zombie.characters.animals.behavior.BaseAnimalBehavior b, int n) { // pzopt
      this.spottedChr = null; // pzopt
      for (int i = 0; i < n && b.lastAlerted > 0.0F; i++) { // pzopt: once it is 0 the rest of the calls change nothing
         b.lastAlerted = b.lastAlerted - GameTime.getInstance().getMultiplier(); // pzopt
         if (b.lastAlerted < 0.0F) { // pzopt
            b.lastAlerted = 0.0F; // pzopt
         } // pzopt
      } // pzopt
   } // pzopt

   public boolean canBePutInHutch(IsoHutch hutch) {
      return this.adef != null && this.adef.hutches != null ? this.adef.hutches.contains(hutch.type) : false;
   }

   public boolean shouldCreateZone() {
      return this.getDZone() == null
         && !this.isWild()
         && this.getData().getAttachedPlayer() == null
         && this.getData().getAttachedTree() == null
         && !this.getVariableBoolean(AnimationVariableHandles.animalRunning);
   }

   public void setIsRoadKill(boolean roadKill) {
      this.roadKill = roadKill;
   }

   public boolean isRoadKill() {
      return this.roadKill;
   }

   public int getLastCellSavedToX() {
      return this.lastCellSavedToX;
   }

   public int getLastCellSavedToY() {
      return this.lastCellSavedToY;
   }

   public void setLastCellSavedTo(int x, int y) {
      this.lastCellSavedToX = x;
      this.lastCellSavedToY = y;
   }

   public String getFeedByHandAnim() {
      return this.adef.feedByHandAnim;
   }
}
