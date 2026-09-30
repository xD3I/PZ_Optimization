package zombie.characters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import org.joml.Vector3f;
import zombie.CombatManager;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.IndieGL;
import zombie.PersistentOutfits;
import zombie.SandboxOptions;
import zombie.SoundManager;
import zombie.SystemDisabler;
import zombie.UsedFromLua;
import zombie.VirtualZombieManager;
import zombie.WorldSoundManager;
import zombie.Lua.LuaEventManager;
import zombie.SharedDescriptors.Descriptor;
import zombie.ai.State;
import zombie.ai.StateMachine; // pzopt: ecsLookupFast
import zombie.iso.objects.ShadowParams; // pzopt: shadowPrep
import zombie.characters.component.StateMachineComponent; // pzopt: ecsLookupFast
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator; // pzopt: ecsLookupFast
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandle; // pzopt: ecsLookupFast
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot; // pzopt: ecsLookupFast
import zombie.ai.ZombieGroupManager;
import zombie.ai.astar.Mover;
import zombie.ai.astar.AStarPathFinder.PathFindProgress;
import zombie.ai.states.AttackNetworkState;
import zombie.ai.states.AttackState;
import zombie.ai.states.BumpedState;
import zombie.ai.states.BurntToDeath;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbOverWallState;
import zombie.ai.states.ClimbThroughWindowPositioningParams;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.CrawlingZombieTurnState;
import zombie.ai.states.FakeDeadAttackState;
import zombie.ai.states.FakeDeadZombieState;
import zombie.ai.states.GenericDefaultState;
import zombie.ai.states.GrappledThrownIntoContainerState;
import zombie.ai.states.GrappledThrownOutWindowState;
import zombie.ai.states.GrappledThrownOverFenceState;
import zombie.ai.states.LungeNetworkState;
import zombie.ai.states.LungeState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.PlayerHitReactionState;
import zombie.ai.states.StaggerBackState;
import zombie.ai.states.ThumpState;
import zombie.ai.states.VehicleCollisionMinorStaggerState;
import zombie.ai.states.VehicleCollisionOnGroundState;
import zombie.ai.states.VehicleCollisionState;
import zombie.ai.states.WalkTowardNetworkState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieEatBodyState;
import zombie.ai.states.ZombieFaceTargetState;
import zombie.ai.states.ZombieFallDownState;
import zombie.ai.states.ZombieFallingState;
import zombie.ai.states.ZombieGetDownState;
import zombie.ai.states.ZombieGetUpFromCrawlState;
import zombie.ai.states.ZombieGetUpState;
import zombie.ai.states.ZombieHitReactionState;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.ZombieOnGroundState;
import zombie.ai.states.ZombieRagdollOnGroundState;
import zombie.ai.states.ZombieReanimateState;
import zombie.ai.states.ZombieSittingState;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.audio.parameters.ParameterCharacterInside;
import zombie.audio.parameters.ParameterCharacterMovementSpeed;
import zombie.audio.parameters.ParameterCharacterOnFire;
import zombie.audio.parameters.ParameterFootstepMaterial;
import zombie.audio.parameters.ParameterFootstepMaterial2;
import zombie.audio.parameters.ParameterPlayerDistance;
import zombie.audio.parameters.ParameterShoeType;
import zombie.audio.parameters.ParameterVehicleHitLocation;
import zombie.audio.parameters.ParameterZombieState;
import zombie.audio.parameters.ParameterCharacterMovementSpeed.MovementType;
import zombie.audio.parameters.ParameterVehicleHitLocation.HitLocation;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.IsoGameCharacter.Location;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionGroup;
import zombie.characters.component.NetworkZombieComponent;
import zombie.characters.skills.PerkFactory.Perks;
import zombie.core.BoxedStaticValues;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.math.interpolators.LerpType;
import zombie.core.opengl.RenderSettings;
import zombie.core.opengl.Shader;
import zombie.core.physics.RagdollController;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.DeadBodyAtlas.BodyTexture;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.sharedskele.SharedSkeleAnimationRepository;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.population.Outfit;
import zombie.core.skinnedmodel.population.OutfitManager;
import zombie.core.skinnedmodel.population.OutfitRNG;
import zombie.core.skinnedmodel.visual.BaseVisual;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.utils.BooleanGrid;
import zombie.core.utils.OnceEvery;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemSpawner;
import zombie.inventory.types.HandWeapon;
import zombie.iso.BentFences;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.LosUtil.TestResults;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoFireManager;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.IsoZombieGiblets.GibletType;
import zombie.iso.objects.interfaces.Thumpable;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombiePacket;
import zombie.network.statistics.data.ConnectionQueueStatistic;
import zombie.network.statistics.data.GameStatistic;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.NetworkZombieManager;
import zombie.popman.NetworkZombieSimulator;
import zombie.popman.ZombiePopulationManager;
import zombie.popman.ZombieStateFlags;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.CharacterTrait;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.ItemType;
import zombie.scripting.objects.ResourceLocation;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;
import zombie.statistics.StatisticsManager;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.lambda.PZOptional;
import zombie.util.list.PZArrayList;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.AttackVehicleState;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.BaseVehicle.HitVars;

@UsedFromLua
public final class IsoZombie extends IsoGameCharacter implements IHumanVisual {
   /**
    * pzopt: separateFast / separateParallel. The separation pass specialised for a zombie, split into a pure compute
    * and an apply. Stock's IsoMovingObject.separate() re-derives per neighbouring object whether the asker is a player
    * (it never is, here), which makes the charged-spear branch and the whole bump block dead code for a zombie, and
    * asks the grid "is my square blocked to that neighbour" once per character instead of once per square
    * (pzopt.SeparateMask caches that answer for the frame). Everything reachable for a zombie is kept exactly: the
    * solid / pushable gates, the order of the square and object walks, the early stop on a non-character non-vehicle
    * object, the contact, the running position the pushes accumulate into, Vector2.normalize()'s "already unit"
    * shortcut, the camera-distance gate and the collideWith calls in their original order.
    *
    * <p>The compute only reads the world (positions, widths, the grid, the state comparisons of the three
    * pushed-by predicates); every write — the position, the contact, the wasSeparated flag and the collideWith calls,
    * which run Lua events and can start a window climb or a thump — is recorded and applied here, on the game thread,
    * at the point in this zombie's update where stock would have done it. So with {@code separateParallel} the compute
    * runs on the frame workers before the update loop (pzopt.SeparateBatch) and the only difference from stock is that
    * the neighbours' positions are read at the top of the frame instead of as the loop advances.
    */
   @Override
   public void separate() {
      if (!pzopt.Config.SEPARATE_FAST || !pzopt.Overrides.enabled()) {
         super.separate();
         return;
      }

      if (this.pzoptSepFrame != pzopt.FrameTick.frame()) {
         this.pzoptSeparateCompute();
      }

      this.pzoptSeparateApply();
   }

   private int pzoptSepFrame; // no initialiser: FrameTick starts at 1, so 0 always means "not computed"
   private float pzoptSepDx;
   private float pzoptSepDy;
   private boolean pzoptSepWasSeparated;
   private IsoMovingObject pzoptSepContact;
   private IsoMovingObject[] pzoptSepCollide;
   private int pzoptSepCollideCount;

   private static final int PZOPT_SEP_MAX_COLLIDE = 32; // 12 spilled ~20 times in a 25 s Louisville run; the array is only allocated for a zombie that actually collides

   /** Worker or game thread: the separation of this zombie, read-only, recorded into the fields above. */
   public void pzoptSeparateCompute() {
      this.pzoptSepFrame = pzopt.FrameTick.frame();
      this.pzoptSepDx = 0.0F;
      this.pzoptSepDy = 0.0F;
      this.pzoptSepWasSeparated = false;
      this.pzoptSepContact = null;
      this.pzoptSepCollideCount = 0;
      if (!this.isSolidForSeparate() || !this.isPushableForSeparate()) {
         return;
      }

      IsoGridSquare current = this.getCurrentSquare();
      if (current == null) {
         return;
      }

      IsoGridSquare[] nav = current.getSurroundingSquares();
      float thisZ = this.getZ();
      float curX = this.getNextX();
      float curY = this.getNextY();
      boolean camGate = GameServer.server || this.distToNearestCamCharacter() < 60.0F;

      for (int i = 0; i <= 8; i++) {
         IsoGridSquare sq = i == 8 ? current : nav[i];
         if (sq == null) {
            continue;
         }

         java.util.ArrayList<IsoMovingObject> objects = sq.getMovingObjects();
         int size = objects.size();
         if (size == 0 || sq != current && pzopt.SeparateMask.blocked(current, sq, i)) {
            continue;
         }

         for (int n = 0; n < size; n++) {
            IsoMovingObject obj = objects.get(n);
            if (obj == this || !obj.isSolidForSeparate() || Math.abs(thisZ - obj.getZ()) > 0.3F) {
               continue;
            }

            boolean objIsCharacter = obj instanceof IsoGameCharacter;
            float twidth = this.width + obj.getWidth();
            float dx = curX - obj.getNextX();
            float dy = curY - obj.getNextY();
            float len = (float)Math.sqrt(dx * dx + dy * dy);
            if (!objIsCharacter && !(obj instanceof BaseVehicle)) {
               if (len < twidth) {
                  this.pzoptSepContact = obj;
               }

               return; // stock leaves the whole pass here, not just this object
            }

            if (!objIsCharacter || len >= twidth || !camGate) {
               continue;
            }

            this.pzoptSepWasSeparated = true;
            if (this.isPushedByForSeparate(obj)) {
               float pushLength = (len - twidth) / 8.0F;
               float lengthSq = dx * dx + dy * dy;
               if (!PZMath.equal(lengthSq, 1.0F, 1.0E-5F)) { // Vector2.normalize() leaves an already-unit vector alone
                  if (lengthSq == 0.0F) {
                     dx = 0.0F;
                     dy = 0.0F;
                  } else {
                     float length = (float)Math.sqrt(lengthSq);
                     dx /= length;
                     dy /= length;
                  }
               }

               curX -= dx * pushLength;
               curY -= dy * pushLength;
               this.pzoptSepDx -= dx * pushLength;
               this.pzoptSepDy -= dy * pushLength;
            }

            if (this.pzoptSepCollide == null) {
               this.pzoptSepCollide = new IsoMovingObject[PZOPT_SEP_MAX_COLLIDE];
            }

            if (this.pzoptSepCollideCount < PZOPT_SEP_MAX_COLLIDE) {
               this.pzoptSepCollide[this.pzoptSepCollideCount++] = obj;
            } else {
               pzopt.SeparateMask.collideSpills++; // more overlapping characters than the record holds: that one call is dropped
            }
         }
      }
   }

   /** Game thread, in loop order: the writes the compute recorded. */
   private void pzoptSeparateApply() {
      if (this.pzoptSepDx != 0.0F || this.pzoptSepDy != 0.0F) {
         this.setNextX(this.getNextX() + this.pzoptSepDx);
         this.setNextY(this.getNextY() + this.pzoptSepDy);
      }

      if (this.pzoptSepWasSeparated) {
         this.getECSComponent(NetworkZombieComponent.class).getNetworkAI().wasSeparated = true;
      }

      if (this.pzoptSepContact != null) {
         zombie.CollisionManager.instance.AddContact(this, this.pzoptSepContact);
         this.pzoptSepContact = null;
      }

      for (int i = 0; i < this.pzoptSepCollideCount; i++) {
         IsoMovingObject obj = this.pzoptSepCollide[i];
         this.pzoptSepCollide[i] = null;
         this.collideWith(obj);
      }

      this.pzoptSepCollideCount = 0;
      this.pzoptSepDx = 0.0F;
      this.pzoptSepDy = 0.0F;
      this.pzoptSepWasSeparated = false;
   }

   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.IsoZombie");
   }

   private static final int s_saveFormatVersion = 1;
   public static final byte SPEED_NONE = -1;
   public static final byte SPEED_SPRINTER = 1;
   public static final byte SPEED_FAST_SHAMBLER = 2;
   public static final byte SPEED_SHAMBLER = 3;
   public static final byte SPEED_RANDOM = 4;
   public static final byte HEARING_PINPOINT = 1;
   public static final byte HEARING_NORMAL = 2;
   public static final byte HEARING_POOR = 3;
   public static final byte HEARING_RANDOM = 4;
   public static final byte HEARING_NORMAL_OR_POOR = 5;
   public static final byte THUMP_FLAG_GENERIC = 1;
   public static final byte THUMP_FLAG_WINDOW_EXTRA = 2;
   public static final byte THUMP_FLAG_WINDOW = 3;
   public static final byte THUMP_FLAG_METAL = 4;
   public static final byte THUMP_FLAG_GARAGE_DOOR = 5;
   public static final byte THUMP_FLAG_CHAINLINK_FENCE = 6;
   public static final byte THUMP_FLAG_METAL_POLE_GATE = 7;
   public static final byte THUMP_FLAG_WOOD = 8;
   private static final ArrayList<IsoDeadBody> tempBodies = new ArrayList<>();
   private boolean alwaysKnockedDown;
   private boolean onlyJawStab;
   private boolean forceEatingAnimation;
   private boolean noTeeth;
   public static final int AllowRepathDelayMax = 120;
   public static final boolean SPRINTER_FIXES = true;
   public int lastTargetSeenX = -1;
   public int lastTargetSeenY = -1;
   public int lastTargetSeenZ = -1;
   public boolean ghost;
   public float lungeTimer;
   public long lungeSoundTime;
   public IsoMovingObject target;
   public float timeSinceSeenFlesh = 100000.0F;
   private float targetSeenTime;
   private boolean canSeeTarget;
   public int followCount;
   public int zombieId;
   public int persistentId;
   private float bonusSpotTime;
   public boolean staggerBack;
   private boolean knifeDeath;
   private boolean jawStabAttach;
   private boolean becomeCrawler;
   private boolean fakeDead;
   private boolean forceFakeDead;
   private boolean wasFakeDead;
   private boolean reanimate;
   public BodyTexture atlasTex;
   private boolean reanimatedPlayer;
   public boolean indoorZombie;
   public int thumpFlag;
   public boolean thumpSent;
   private float thumpCondition = 1.0F;
   public static final float EAT_BODY_DIST = 1.0F;
   public static final float EAT_BODY_TIME = 3600.0F;
   public static final float LUNGE_TIME = 180.0F;
   public static final float CRAWLER_DAMAGE_DOT = 0.9F;
   public static final float CRAWLER_DAMAGE_RANGE = 1.5F;
   private boolean useless;
   public int speedType = -1;
   public ZombieGroup group;
   public boolean inactive;
   public int strength = -1;
   public int cognition = -1;
   public int memory = -1;
   public int sight = -1;
   public int hearing = -1;
   private ArrayList<InventoryItem> itemsToSpawnAtDeath;
   private int voiceChoice = -1;
   private float soundReactDelay;
   private final Location delayedSound = new Location(-1, -1, -1);
   private boolean soundSourceRepeating;
   private boolean soundSourceIsPlayer;
   private boolean soundSourceIsPlayerBase;
   public Object soundSourceTarget;
   public float soundAttract;
   public float soundAttractTimeout;
   public boolean alerted;
   private String walkType;
   private float footstepVolume = 1.0F;
   private Descriptor sharedDesc;
   public boolean dressInRandomOutfit;
   public String pendingOutfitName;
   private final HumanVisual humanVisual = new HumanVisual(this);
   private int crawlerType;
   private String playerAttackPosition;
   private float eatSpeed = 1.0F;
   private boolean sitAgainstWall;
   private static final int CHECK_FOR_CORPSE_TIMER_MAX = 10000;
   private float checkForCorpseTimer = 10000.0F;
   public IsoDeadBody bodyToEat;
   public IsoMovingObject eatBodyTarget;
   private int hitTime;
   private int thumpTimer;
   private boolean hitLegsWhileOnFloor;
   public boolean collideWhileHit = true;
   private float characterTextureAnimTime;
   private float characterTextureAnimDuration = 1.0F;
   public int lastPlayerHit = -1;
   public static final float VISION_RADIUS_MAX = 20.0F;
   public static final float VISION_RADIUS_MIN = 10.0F;
   public float visionRadiusResult = 20.0F;
   public static final float VISION_FOG_PENALTY_MAX = 7.0F;
   public static final float VISION_RAIN_PENALTY_MAX = 2.5F;
   public static final float VISION_DARKNESS_PENALTY_MAX = 5.0F;
   public static final int HEARING_UNSEEN_OFFSET_MIN = 2;
   public static final int HEARING_UNSEEN_OFFSET_HEAVY_RAIN = 5;
   public static final int HEARING_UNSEEN_OFFSET_MAX = 10;
   protected final ItemVisuals itemVisuals = new ItemVisuals();
   private int hitHeadWhileOnFloor;
   private boolean attackDidDamage;
   private String attackOutcome = "";
   private boolean reanimatedForGrappleOnly;
   public Imposter imposter = new Imposter();
   public IsoMovingObject spottedLast;
   private BaseVehicle vehicle4testCollision;
   private int spotSoundDelay;
   public float movex;
   public float movey;
   private int stepFrameLast = -1;
   private final OnceEvery networkUpdate = new OnceEvery(1.0F);
   public short lastRemoteUpdate;
   public short onlineId = -1;
   public String spriteName = "BobZ";
   public static final int PALETTE_COUNT = 3;
   public final Vector2 vectorToTarget = new Vector2();
   public float allowRepathDelay;
   public boolean keepItReal;
   private boolean isSkeleton;
   public final ParameterCharacterInside parameterCharacterInside = new ParameterCharacterInside(this);
   private final ParameterCharacterMovementSpeed parameterCharacterMovementSpeed = new ParameterCharacterMovementSpeed(this);
   public final ParameterCharacterOnFire parameterCharacterOnFire = new ParameterCharacterOnFire(this);
   private final ParameterFootstepMaterial parameterFootstepMaterial = new ParameterFootstepMaterial(this);
   private final ParameterFootstepMaterial2 parameterFootstepMaterial2 = new ParameterFootstepMaterial2(this);
   public final ParameterPlayerDistance parameterPlayerDistance = new ParameterPlayerDistance(this);
   private final ParameterShoeType parameterShoeType = new ParameterShoeType(this);
   private final ParameterVehicleHitLocation parameterVehicleHitLocation = new ParameterVehicleHitLocation();
   public final ParameterZombieState parameterZombieState = new ParameterZombieState(this);
   public boolean scratch;
   public boolean laceration;
   public ZombiePacket zombiePacket = new ZombiePacket();
   public boolean zombiePacketUpdated;
   public long lastChangeOwner = -1L;
   public int bloodSplatAmount = 1000;
   private static final Vector2 lastPosition = new Vector2();
   private static final Vector2 currentPosition = new Vector2();
   public BodyPartType lastHitPart;
   public float timeSinceRespondToSound = 1000000.0F;
   private static final SharedSkeleAnimationRepository m_sharedSkeleRepo = new SharedSkeleAnimationRepository();
   public String walkVariantUse;
   public String walkVariant = "ZombieWalk";
   public boolean lunger;
   public boolean running;
   public boolean crawling;
   private boolean canCrawlUnderVehicle = true;
   private boolean canWalk = true;
   public boolean remote;
   private static final IsoZombie.FloodFill floodFill = new IsoZombie.FloodFill();
   public boolean immortalTutorialZombie;
   private final int palette;
   private final IsoZombie.Aggro[] aggroList = new IsoZombie.Aggro[4];
   private float unbalancedLevel;
   private static final Map<String, String> temporaryMapCloseSneakBonusDir = Map.ofEntries(
      Map.entry("trashcontainers_01_08", "NWSE"),
      Map.entry("trashcontainers_01_09", "NWSE"),
      Map.entry("trashcontainers_01_10", "NWSE"),
      Map.entry("trashcontainers_01_11", "NWSE"),
      Map.entry("trashcontainers_01_12", "NWSE"),
      Map.entry("trashcontainers_01_13", "NWSE"),
      Map.entry("trashcontainers_01_14", "NWSE"),
      Map.entry("trashcontainers_01_15", "NWSE"),
      Map.entry("f_bushes_1_96", "NWSE"),
      Map.entry("f_bushes_1_97", "NWSE"),
      Map.entry("f_bushes_1_98", "NWSE"),
      Map.entry("f_bushes_1_99", "NWSE"),
      Map.entry("f_bushes_1_100", "NWSE"),
      Map.entry("f_bushes_1_101", "NWSE"),
      Map.entry("f_bushes_1_102", "NWSE"),
      Map.entry("f_bushes_1_103", "NWSE"),
      Map.entry("f_bushes_1_104", "NWSE"),
      Map.entry("f_bushes_1_105", "NWSE"),
      Map.entry("f_bushes_1_106", "NWSE"),
      Map.entry("f_bushes_1_107", "NWSE"),
      Map.entry("f_bushes_1_108", "NWSE"),
      Map.entry("f_bushes_1_109", "NWSE"),
      Map.entry("f_bushes_1_110", "NWSE"),
      Map.entry("f_bushes_1_111", "NWSE"),
      Map.entry("f_bushes_2_0", "NWSE"),
      Map.entry("f_bushes_2_1", "NWSE"),
      Map.entry("f_bushes_2_2", "NWSE"),
      Map.entry("f_bushes_2_3", "NWSE"),
      Map.entry("f_bushes_2_4", "NWSE"),
      Map.entry("f_bushes_2_5", "NWSE"),
      Map.entry("f_bushes_2_6", "NWSE"),
      Map.entry("f_bushes_2_7", "NWSE"),
      Map.entry("f_bushes_2_10", "NWSE"),
      Map.entry("f_bushes_2_11", "NWSE"),
      Map.entry("f_bushes_2_12", "NWSE"),
      Map.entry("f_bushes_2_13", "NWSE"),
      Map.entry("f_bushes_2_14", "NWSE"),
      Map.entry("f_bushes_2_15", "NWSE"),
      Map.entry("f_bushes_2_16", "NWSE"),
      Map.entry("f_bushes_2_17", "NWSE"),
      Map.entry("vegetation_ornamental_01_0", "NWSE"),
      Map.entry("vegetation_ornamental_01_1", "NWSE"),
      Map.entry("vegetation_ornamental_01_2", "NWSE"),
      Map.entry("vegetation_ornamental_01_3", "NWSE"),
      Map.entry("vegetation_ornamental_01_4", "NWSE"),
      Map.entry("vegetation_ornamental_01_5", "NWSE"),
      Map.entry("vegetation_ornamental_01_6", "NWSE"),
      Map.entry("vegetation_ornamental_01_7", "NWSE"),
      Map.entry("vegetation_ornamental_01_10", "NWSE"),
      Map.entry("vegetation_ornamental_01_11", "NWSE"),
      Map.entry("vegetation_ornamental_01_12", "NWSE"),
      Map.entry("vegetation_ornamental_01_13", "NWSE"),
      Map.entry("fencing_01_96", "NWSE"),
      Map.entry("fencing_01_98", "NWSE"),
      Map.entry("fencing_01_100", "NWSE"),
      Map.entry("fencing_01_102", "NWSE"),
      Map.entry("fencing_01_32", "N"),
      Map.entry("fencing_01_33", "N"),
      Map.entry("fencing_01_34", "W"),
      Map.entry("fencing_01_35", "W"),
      Map.entry("fencing_01_36", "NW"),
      Map.entry("fencing_01_4", "W"),
      Map.entry("fencing_01_5", "N"),
      Map.entry("fencing_01_6", "NW"),
      Map.entry("carpentry_02_40", "W"),
      Map.entry("carpentry_02_41", "N"),
      Map.entry("carpentry_02_42", "NW"),
      Map.entry("carpentry_02_44", "W"),
      Map.entry("carpentry_02_45", "N"),
      Map.entry("carpentry_02_46", "NW"),
      Map.entry("carpentry_02_48", "W"),
      Map.entry("carpentry_02_49", "N"),
      Map.entry("carpentry_02_50", "NW"),
      Map.entry("fixtures_doors_fences_01_104", "W"),
      Map.entry("fixtures_doors_fences_01_105", "W"),
      Map.entry("fixtures_doors_fences_01_96", "W"),
      Map.entry("fixtures_doors_fences_01_97", "W"),
      Map.entry("fixtures_doors_fences_01_48", "W"),
      Map.entry("fixtures_doors_fences_01_49", "W"),
      Map.entry("fixtures_doors_fences_01_56", "W"),
      Map.entry("fixtures_doors_fences_01_57", "W"),
      Map.entry("fixtures_doors_fences_01_98", "N"),
      Map.entry("fixtures_doors_fences_01_99", "N"),
      Map.entry("fixtures_doors_fences_01_106", "N"),
      Map.entry("fixtures_doors_fences_01_107", "N"),
      Map.entry("fixtures_doors_fences_01_50", "N"),
      Map.entry("fixtures_doors_fences_01_51", "N"),
      Map.entry("fixtures_doors_fences_01_58", "N"),
      Map.entry("fixtures_doors_fences_01_59", "N"),
      Map.entry("fixtures_doors_fences_01_8", "W"),
      Map.entry("fixtures_doors_fences_01_9", "N"),
      Map.entry("fixtures_doors_fences_01_12", "W"),
      Map.entry("fixtures_doors_fences_01_13", "N"),
      Map.entry("fixtures_doors_fences_01_4", "W"),
      Map.entry("fixtures_doors_fences_01_5", "N")
   );
   private static final Map<String, Float> temporaryMapCloseSneakBonusValue = Map.ofEntries(
      Map.entry("trashcontainers_01_08", 100.0F),
      Map.entry("trashcontainers_01_09", 100.0F),
      Map.entry("trashcontainers_01_10", 100.0F),
      Map.entry("trashcontainers_01_11", 100.0F),
      Map.entry("trashcontainers_01_12", 100.0F),
      Map.entry("trashcontainers_01_13", 100.0F),
      Map.entry("trashcontainers_01_14", 100.0F),
      Map.entry("trashcontainers_01_15", 100.0F),
      Map.entry("f_bushes_1_96", 100.0F),
      Map.entry("f_bushes_1_97", 100.0F),
      Map.entry("f_bushes_1_98", 100.0F),
      Map.entry("f_bushes_1_99", 100.0F),
      Map.entry("f_bushes_1_100", 100.0F),
      Map.entry("f_bushes_1_101", 100.0F),
      Map.entry("f_bushes_1_102", 100.0F),
      Map.entry("f_bushes_1_103", 100.0F),
      Map.entry("f_bushes_1_104", 100.0F),
      Map.entry("f_bushes_1_105", 100.0F),
      Map.entry("f_bushes_1_106", 100.0F),
      Map.entry("f_bushes_1_107", 100.0F),
      Map.entry("f_bushes_1_108", 100.0F),
      Map.entry("f_bushes_1_109", 100.0F),
      Map.entry("f_bushes_1_110", 100.0F),
      Map.entry("f_bushes_1_111", 100.0F),
      Map.entry("f_bushes_2_0", 100.0F),
      Map.entry("f_bushes_2_1", 100.0F),
      Map.entry("f_bushes_2_2", 100.0F),
      Map.entry("f_bushes_2_3", 100.0F),
      Map.entry("f_bushes_2_4", 100.0F),
      Map.entry("f_bushes_2_5", 100.0F),
      Map.entry("f_bushes_2_6", 100.0F),
      Map.entry("f_bushes_2_7", 100.0F),
      Map.entry("f_bushes_2_10", 100.0F),
      Map.entry("f_bushes_2_11", 100.0F),
      Map.entry("f_bushes_2_12", 100.0F),
      Map.entry("f_bushes_2_13", 100.0F),
      Map.entry("f_bushes_2_14", 100.0F),
      Map.entry("f_bushes_2_15", 100.0F),
      Map.entry("f_bushes_2_16", 100.0F),
      Map.entry("f_bushes_2_17", 100.0F),
      Map.entry("vegetation_ornamental_01_0", 100.0F),
      Map.entry("vegetation_ornamental_01_1", 100.0F),
      Map.entry("vegetation_ornamental_01_2", 100.0F),
      Map.entry("vegetation_ornamental_01_3", 100.0F),
      Map.entry("vegetation_ornamental_01_4", 100.0F),
      Map.entry("vegetation_ornamental_01_5", 100.0F),
      Map.entry("vegetation_ornamental_01_6", 100.0F),
      Map.entry("vegetation_ornamental_01_7", 100.0F),
      Map.entry("vegetation_ornamental_01_10", 100.0F),
      Map.entry("vegetation_ornamental_01_11", 100.0F),
      Map.entry("vegetation_ornamental_01_12", 100.0F),
      Map.entry("vegetation_ornamental_01_13", 100.0F),
      Map.entry("fencing_01_96", 100.0F),
      Map.entry("fencing_01_98", 100.0F),
      Map.entry("fencing_01_100", 100.0F),
      Map.entry("fencing_01_102", 100.0F),
      Map.entry("fencing_01_32", 100.0F),
      Map.entry("fencing_01_33", 100.0F),
      Map.entry("fencing_01_34", 100.0F),
      Map.entry("fencing_01_35", 100.0F),
      Map.entry("fencing_01_36", 100.0F),
      Map.entry("fencing_01_4", 60.0F),
      Map.entry("fencing_01_5", 60.0F),
      Map.entry("fencing_01_6", 60.0F),
      Map.entry("carpentry_02_40", 100.0F),
      Map.entry("carpentry_02_41", 100.0F),
      Map.entry("carpentry_02_42", 100.0F),
      Map.entry("carpentry_02_44", 100.0F),
      Map.entry("carpentry_02_45", 100.0F),
      Map.entry("carpentry_02_46", 100.0F),
      Map.entry("carpentry_02_48", 100.0F),
      Map.entry("carpentry_02_49", 100.0F),
      Map.entry("carpentry_02_50", 100.0F),
      Map.entry("fixtures_doors_fences_01_104", 100.0F),
      Map.entry("fixtures_doors_fences_01_105", 100.0F),
      Map.entry("fixtures_doors_fences_01_96", 100.0F),
      Map.entry("fixtures_doors_fences_01_97", 100.0F),
      Map.entry("fixtures_doors_fences_01_48", 100.0F),
      Map.entry("fixtures_doors_fences_01_49", 100.0F),
      Map.entry("fixtures_doors_fences_01_56", 100.0F),
      Map.entry("fixtures_doors_fences_01_57", 100.0F),
      Map.entry("fixtures_doors_fences_01_98", 100.0F),
      Map.entry("fixtures_doors_fences_01_99", 100.0F),
      Map.entry("fixtures_doors_fences_01_106", 100.0F),
      Map.entry("fixtures_doors_fences_01_107", 100.0F),
      Map.entry("fixtures_doors_fences_01_50", 100.0F),
      Map.entry("fixtures_doors_fences_01_51", 100.0F),
      Map.entry("fixtures_doors_fences_01_58", 100.0F),
      Map.entry("fixtures_doors_fences_01_59", 100.0F),
      Map.entry("fixtures_doors_fences_01_8", 100.0F),
      Map.entry("fixtures_doors_fences_01_9", 100.0F),
      Map.entry("fixtures_doors_fences_01_12", 100.0F),
      Map.entry("fixtures_doors_fences_01_13", 100.0F),
      Map.entry("fixtures_doors_fences_01_4", 100.0F),
      Map.entry("fixtures_doors_fences_01_5", 100.0F)
   );

   public IsoZombie(IsoCell cell) {
      this(cell, null, -1);
   }

   public IsoZombie(IsoCell cell, SurvivorDesc desc, int palette) {
      super(cell, 0.0F, 0.0F, 0.0F);
      this.getWrappedGrappleable().setOnGrappledEndCallback(this::onZombieGrappleEnded);
      this.addOnDiedListener(this::onDied, false);
      if (!GameServer.server) {
         this.registerVariableCallbacks();
      }

      this.health = 1.8F + Rand.Next(0.0F, 0.3F);
      this.weight = 0.7F;
      this.setForwardIsoDirection(IsoDirections.getRandom());
      this.humanVisual.randomBlood();
      if (desc != null) {
         this.descriptor = desc;
         this.palette = palette;
      } else {
         this.descriptor = SurvivorFactory.CreateSurvivor();
         this.palette = Rand.Next(3) + 1;
      }

      this.setFemale(this.descriptor.isFemale());
      if (!this.descriptor.getVoicePrefix().contains("Zombie")) {
         this.descriptor.setVoicePrefix(Objects.equals(this.descriptor.getVoicePrefix(), "VoiceFemale") ? "FemaleZombie" : "MaleZombie");
      }

      this.spriteName = this.isFemale() ? "KateZ" : "BobZ";
      if (this.palette != 1) {
         this.spriteName = this.spriteName + this.palette;
      }

      this.InitSpritePartsZombie();
      this.sprite.def.tintr = 0.95F + Rand.Next(5) / 100.0F;
      this.sprite.def.tintg = 0.95F + Rand.Next(5) / 100.0F;
      this.sprite.def.tintb = 0.95F + Rand.Next(5) / 100.0F;
      this.setDefaultState(GenericDefaultState.instance());
      this.setFakeDead(false);
      this.inactive = GameTime.getInstance().isZombieInactivityPhase();
      this.DoZombieStats();
      this.width = 0.3F;
      this.setAlphaAndTarget(0.0F);
      this.finder.maxSearchDistance = 20;
      this.hurtSound = this.descriptor.getVoicePrefix() + "Hurt";
      this.initializeStates();
      this.getActionContext().setGroup(pzoptGroup(false));
      this.initWornItems("Human");
      this.initAttachedItems("Human");
      this.clearAggroList();
      this.initFMODParameters();
   }

   public void registerECSComponents() {
      super.registerECSComponents();
      this.setECSComponent(new NetworkZombieComponent(this));
   }

   public String toString() {
      return this.getClass().getSimpleName()
         + "{  Name:"
         + this.getName()
         + ",  ID:"
         + this.getID()
         + "  StateFlags:"
         + ZombieStateFlags.fromZombie(this)
         + "}";
   }

   public String getObjectName() {
      return "Zombie";
   }

   public short getOnlineID() {
      return this.onlineId;
   }

   public boolean isRemoteZombie() {
      return this.isRemote();
   }

   public UdpConnection getOwner() {
      return (UdpConnection)PZOptional.ifPresent(
         (NetworkZombieComponent)this.tryGetECSComponent(NetworkZombieComponent.class), (UdpConnection)null, NetworkZombieComponent::getAuthOwner
      );
   }

   public void setOwner(UdpConnection connection) {
      PZOptional.ifPresent((NetworkZombieComponent)this.tryGetECSComponent(NetworkZombieComponent.class), NetworkZombieComponent::setAuthOwner, connection);
   }

   public IsoPlayer getOwnerPlayer() {
      return (IsoPlayer)PZOptional.ifPresent(
         (NetworkZombieComponent)this.tryGetECSComponent(NetworkZombieComponent.class), (IsoPlayer)null, NetworkZombieComponent::getOwnerPlayer
      );
   }

   public void setOwnerPlayer(IsoPlayer player) {
      PZOptional.ifPresent((NetworkZombieComponent)this.tryGetECSComponent(NetworkZombieComponent.class), NetworkZombieComponent::setOwnerPlayer, player);
   }

   public void setVehicle4TestCollision(BaseVehicle vehicle) {
      this.vehicle4testCollision = vehicle;
   }

   public void initializeStates() {
      this.clearAIStateMap();
      this.registerAIState("idle", ZombieIdleState.instance());
      this.registerAIState("attack-network", AttackNetworkState.instance());
      this.registerAIState("attackvehicle-network", GenericDefaultState.instance());
      this.registerAIState("fakedead-attack-network", GenericDefaultState.instance());
      this.registerAIState("lunge-network", LungeNetworkState.instance());
      this.registerAIState("walktoward-network", WalkTowardNetworkState.instance());
      this.registerAIState("corpseThrownOutWindow-onback", GrappledThrownOutWindowState.instance());
      this.registerAIState("corpseThrownOutWindow-onfront", GrappledThrownOutWindowState.instance());
      this.registerAIState("corpseThrownOverFence-onback", GrappledThrownOverFenceState.instance());
      this.registerAIState("corpseThrownoverFence-onfront", GrappledThrownOverFenceState.instance());
      this.registerAIState("corpseThrownIntoContainer-onback", GrappledThrownIntoContainerState.instance());
      this.registerAIState("corpseThrownIntoContainer-onfront", GrappledThrownIntoContainerState.instance());
      if (this.crawling) {
         this.registerAIState("attack", AttackState.instance());
         this.registerAIState("fakedead", FakeDeadZombieState.instance());
         this.registerAIState("fakedead-attack", FakeDeadAttackState.instance());
         this.registerAIState("getup", ZombieGetUpFromCrawlState.instance());
         this.registerAIState("hitreaction", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-fencewindow", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-knockeddown", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-ragdoll", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-shothead-bwd", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-bwd-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd02", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd02-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-onfloor", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-gettingup", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-whilesitting", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-hit", ZombieHitReactionState.instance());
         this.registerAIState("onground", ZombieOnGroundState.instance());
         this.registerAIState("falldown-ragdoll", ZombieRagdollOnGroundState.instance());
         this.registerAIState("pathfind", PathFindState.instance());
         this.registerAIState("reanimate", ZombieReanimateState.instance());
         this.registerAIState("staggerback", StaggerBackState.instance());
         this.registerAIState("staggerback-knockeddown", StaggerBackState.instance());
         this.registerAIState("staggerback-ragdoll", StaggerBackState.instance());
         this.registerAIState("staggerback-knockeddown-ragdoll", StaggerBackState.instance());
         this.registerAIState("vehicleCollision-staggerback", StaggerBackState.instance());
         this.registerAIState("thump", ThumpState.instance());
         this.registerAIState("turn", CrawlingZombieTurnState.instance());
         this.registerAIState("walktoward", WalkTowardState.instance());
      } else {
         this.registerAIState("attack", AttackState.instance());
         this.registerAIState("attackvehicle", AttackVehicleState.instance());
         this.registerAIState("bumped", BumpedState.instance());
         this.registerAIState("climbfence", ClimbOverFenceState.instance());
         this.registerAIState("climbwindow", ClimbThroughWindowState.instance());
         this.registerAIState("eatbody", ZombieEatBodyState.instance());
         this.registerAIState("falldown", ZombieFallDownState.instance());
         this.registerAIState("vehicleCollision-falldown", ZombieFallDownState.instance());
         this.registerAIState("vehicleCollision-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("falldown-eatingbody", ZombieFallDownState.instance());
         this.registerAIState("falldown-knifedeath", ZombieFallDownState.instance());
         this.registerAIState("falldown-knifedeath-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("falldown-onknees", ZombieFallDownState.instance());
         this.registerAIState("falldown-whilesitting", ZombieFallDownState.instance());
         this.registerAIState("falldown-headleft", ZombieFallDownState.instance());
         this.registerAIState("falldown-headright", ZombieFallDownState.instance());
         this.registerAIState("falldown-headtop", ZombieFallDownState.instance());
         this.registerAIState("falldown-speardeath", ZombieFallDownState.instance());
         this.registerAIState("falldown-speardeath-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("falldown-speardeath1", ZombieFallDownState.instance());
         this.registerAIState("falldown-speardeath2", ZombieFallDownState.instance());
         this.registerAIState("falldown-uppercut", ZombieFallDownState.instance());
         this.registerAIState("falldown-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("falling", ZombieFallingState.instance());
         this.registerAIState("face-target", ZombieFaceTargetState.instance());
         this.registerAIState("fakedead", FakeDeadZombieState.instance());
         this.registerAIState("fakedead-attack", FakeDeadAttackState.instance());
         this.registerAIState("getdown", ZombieGetDownState.instance());
         this.registerAIState("getup", ZombieGetUpState.instance());
         this.registerAIState("getup-fromOnBack", ZombieGetUpState.instance());
         this.registerAIState("getup-fromOnFront", ZombieGetUpState.instance());
         this.registerAIState("getup-fromSitting", ZombieGetUpState.instance());
         this.registerAIState("hitreaction", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-fencewindow", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-knockeddown", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-ragdoll", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-shothead-bwd", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-bwd-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd02", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-shothead-fwd02-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("hitreaction-onfloor", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-gettingup", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-whilesitting", ZombieHitReactionState.instance());
         this.registerAIState("hitreaction-hit", ZombieHitReactionState.instance());
         this.registerAIState("idle", ZombieIdleState.instance());
         this.registerAIState("lunge", LungeState.instance());
         this.registerAIState("onground", ZombieOnGroundState.instance());
         this.registerAIState("falldown-ragdoll", ZombieFallDownState.instance());
         this.registerAIState("onground-ragdoll", ZombieRagdollOnGroundState.instance());
         this.registerAIState("pathfind", PathFindState.instance());
         this.registerAIState("sitting", ZombieSittingState.instance());
         this.registerAIState("staggerback", StaggerBackState.instance());
         this.registerAIState("staggerback-knockeddown", StaggerBackState.instance());
         this.registerAIState("staggerback-ragdoll", StaggerBackState.instance());
         this.registerAIState("staggerback-knockeddown-ragdoll", StaggerBackState.instance());
         this.registerAIState("knockeddown-headLeft", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-headRight", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-headTop", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotChestL", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotChestR", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotLegL", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotLegR", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotShoulderL", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-shotShoulderR", ZombieFallDownState.instance());
         this.registerAIState("knockeddown-uppercut", ZombieFallDownState.instance());
         this.registerAIState("vehicleCollision", VehicleCollisionState.instance());
         this.registerAIState("vehicleCollision-staggerback", StaggerBackState.instance());
         this.registerAIState("vehicleCollision-bumped", VehicleCollisionMinorStaggerState.instance());
         this.registerAIState("vehicleCollision-onground", VehicleCollisionOnGroundState.instance());
         this.registerAIState("vehicleCollision-onground-dead", VehicleCollisionOnGroundState.instance());
         this.registerAIState("thump", ThumpState.instance());
         this.registerAIState("turnalerted", ZombieTurnAlerted.instance());
         this.registerAIState("walktoward", WalkTowardState.instance());
         this.registerAIState("grappled", GenericDefaultState.instance());
      }
   }

   private void registerVariableCallbacks() {
      this.setVariable("bClient", () -> GameClient.client && this.isRemoteZombie(), owner -> "Is this multiplayer Zed a remote Zombie.");
      this.setVariable("bMultiplayer", () -> GameClient.client || GameServer.server, owner -> "Global. Is this a multiplayer session.");
      this.setVariable(
         "bMovingNetwork",
         () -> {
            NetworkZombieAI networkAi = this.getNetworkCharacterAI();
            return (this.isLocal() || !this.isBumped())
               && (IsoUtils.DistanceManhatten(networkAi.targetX, networkAi.targetY, this.getX(), this.getY()) > 0.5F || this.getZ() != networkAi.targetZ);
         },
         owner -> "Is this network Zed moving. <br />returns FALSE if bumped and non-local.."
      );
      this.setVariable("hitHeadType", this::getHitHeadWhileOnFloor, owner -> "Returns the hitHead while on the floor. Typically 0-1.");
      this.setVariable("realState", this::getRealState, owner -> "Network. Returns the Zed's network realState.toString");
      this.setVariable(
         "bIsReanimatedForGrappleOnly",
         this::isReanimatedForGrappleOnly,
         owner -> "Is this Zed just a reanimated corpse for the purposes of being dragged around."
      );
      this.setVariable("battack", this::getShouldAttack, owner -> "Returns TRUE if this Zed is intending an attack. getShouldAttack");
      this.setVariable("isFacingTarget", this::isFacingTarget, owner -> "Returns TRUE if this Zed has a target and is currently facing its target.");
      this.setVariable(
         "targetSeenTime", this::getTargetSeenTime, owner -> "Amount of time, in seconds. How long has this Zed been looking at its current target."
      );
      this.setVariable("battackvehicle", () -> {
         if (this.getVariableBoolean("bPathfind")) {
            return false;
         } else if (this.isMoving()) {
            return false;
         } else if (this.target == null) {
            return false;
         } else if (Math.abs(this.target.getZ() - this.getZ()) >= 0.8F) {
            return false;
         } else if (this.target instanceof IsoPlayer player && player.isGhostMode()) {
            return false;
         } else if (!(this.target instanceof IsoGameCharacter isoGameCharacter)) {
            return false;
         } else {
            BaseVehicle vehicle = isoGameCharacter.getVehicle();
            if (vehicle != null) {
               pzopt.AnimParallel.conditionalGuard("battackvehicle"); // pzopt: guardedCallbacks, the vehicle test stays on the game thread
            }

            return vehicle != null && vehicle.isCharacterAdjacentTo(this);
         }
      }, owner -> "Is this Zed targeting a vehicle.");
      this.setVariable("beatbodytarget", () -> {
         if (this.isForceEatingAnimation()) {
            return true;
         }

         if (!GameServer.server && (this.bodyToEat != null || this.eatBodyTarget != null)) {
            pzopt.AnimParallel.conditionalGuard("beatbodytarget"); // pzopt: guardedCallbacks, the eat-target update (a no-op without a body) stays on the game thread
         }

         if (!GameServer.server) {
            this.updateEatBodyTarget();
         }

         return this.getEatBodyTarget() != null;
      }, owner -> "Is this Zed targeting a body for eating.");
      this.setVariable("bbecomecrawler", this::isBecomeCrawler, this::setBecomeCrawler, owner -> "Is this Zed about to become a crawler.");
      this.setVariable("bfakedead", () -> this.fakeDead, owner -> "Is this Zed pretending to be dead.");
      this.setVariable("bHasTarget", () -> {
         if (this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.reanimatedCorpse != null) {
            pzopt.AnimParallel.conditionalGuard("bHasTarget"); // pzopt: animatorParallel, the side effect below never runs on a frame worker
            this.setTarget(null);
         }

         return this.target != null;
      }, owner -> "Does this Zed have a target.");
      this.setVariable("bCanSeeTarget", () -> this.canSeeTarget, owner -> "Can this Zed see its target.");
      this.setVariable("shouldSprint", () -> {
         if (this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.reanimatedCorpse != null) {
            pzopt.AnimParallel.conditionalGuard("shouldSprint"); // pzopt: animatorParallel, the side effect below never runs on a frame worker
            this.setTarget(null);
         }

         return this.target != null || this.soundSourceTarget != null && !(this.soundSourceTarget instanceof IsoZombie);
      }, owner -> "This Zed intends to sprint.");
      this.setVariable(
         "bknockeddown", this::isKnockedDown, owner -> "Has this Zed been knocked down. Set when a bump or attack is sufficient to knock them off their feet."
      );
      this.setVariable(
         "blunge",
         () -> {
            if (this.target == null) {
               return false;
            }

            if (PZMath.fastfloor(this.getZ()) != PZMath.fastfloor(this.target.getZ())) {
               return false;
            }

            if (this.target instanceof IsoGameCharacter gameCharacter) {
               if (gameCharacter.getVehicle() != null) {
                  return false;
               }

               if (gameCharacter.reanimatedCorpse != null) {
                  return false;
               }
            }

            if (this.target instanceof IsoPlayer player && player.isGhostMode()) {
               pzopt.AnimParallel.conditionalGuard("blunge"); // pzopt: guardedCallbacks, this branch never runs off the game thread
               this.setTarget(null);
               return false;
            } else {
               IsoGridSquare currentSquare = this.getCurrentSquare();
               IsoGridSquare targetCurrentSquare = this.target.getCurrentSquare();
               if (targetCurrentSquare != null
                  && targetCurrentSquare.isSomethingTo(currentSquare)
                  && this.getThumpTarget() != null
                  && !BentFences.getInstance().isUnbentObject(this.target)) {
                  return false;
               }

               if (this.isSideOfStaircaseBetweenSelfAndTarget()) {
                  pzopt.AnimParallel.conditionalGuard("blunge"); // pzopt: guardedCallbacks, this branch never runs off the game thread
                  this.lungeTimer = 0.0F;
                  return false;
               }

               if (this.isCurrentState(ZombieTurnAlerted.instance()) && !this.isFacingTarget()) {
                  return false;
               }

               float vecToTargetLength = this.vectorToTarget.getLength();
               if (!(vecToTargetLength > 3.5F)
                  || vecToTargetLength <= 4.0F && this.target instanceof IsoGameCharacter guardCharacter && guardCharacter.getVehicle() != null) {
                  pzopt.AnimParallel.conditionalGuard("blunge"); // pzopt: guardedCallbacks, the pathfind line test (shared point pool) stays on the game thread
               }

               return !(vecToTargetLength > 3.5F)
                     || vecToTargetLength <= 4.0F && this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.getVehicle() != null
                  ? !PolygonalMap2.instance
                     .lineClearCollide(
                        this.getX(), this.getY(), this.target.getX(), this.target.getY(), PZMath.fastfloor(this.getZ()), this.target, false, true
                     )
                  : false;
            }
         },
         owner -> "This Zed intends to lunge."
      );
      this.setVariable("bstoplunging", () -> {
         if (this.target == null) {
            return true;
         } else if (this.lungeTimer > 0.0F) {
            return false;
         } else {
            float distanceToTarget = this.vectorToTarget.getLength() - this.getWidth() + this.target.getWidth();
            if (this.lungeTimer <= 0.0F && distanceToTarget >= 0.8F) {
               return true;
            } else {
               return this.isSideOfStaircaseBetweenSelfAndTarget() ? true : false;
            }
         }
      }, owner -> "This Zed intends to stop lunging.");
      this.setVariable(
         "bpassengerexposed",
         () -> {
            if (this.target instanceof IsoGameCharacter targetCharacter && targetCharacter.getVehicle() != null) {
               pzopt.AnimParallel.conditionalGuard("bpassengerexposed"); // pzopt: animatorParallel, the vehicle-area path never runs on a frame worker
            }

            return AttackVehicleState.instance().isPassengerExposed(this);
         },
         owner -> "When attacking a vehicle, is the target inside the vehicle exposed to attack."
      );
      this.setVariable(
         "bistargetissmallvehicle",
         () -> this.target != null && this.target instanceof IsoPlayer player && player.getVehicle() != null
            ? player.getVehicle().getScript().isSmallVehicle
            : true,
         owner -> "When this Zed's target is a character inside a vehicle, is that vehicle a small vehicle."
      );
      this.setVariable(
         "breanimate",
         this::isReanimate,
         this::setReanimate,
         owner -> "Typically comes from being dead or on the ground. This Zed intends to reanimate from a dead corpse, or when it becomes a crawler."
      );
      this.setVariable("bstaggerback", this::isStaggerBack, owner -> "Is this Zed staggered backwards.");
      this.setVariable(
         "unbalancedLevel",
         this::getUnbalancedLevel,
         this::setUnbalancedLevel,
         owner -> "The level that this Zed is currently unbalanced, by being staggered back etc."
      );
      this.setVariable(
         "bthump",
         () -> {
            if (this.getThumpTarget() != null
               && (this.getThumpTimer() > 0
                  || this.getThumpTarget() instanceof IsoObject guardObj
                     && !(this.getThumpTarget() instanceof BaseVehicle)
                     && (guardObj.getSquare() == null || this.DistToSquared(guardObj.getX() + 0.5F, guardObj.getY() + 0.5F) > 9.0F))) {
               pzopt.AnimParallel.conditionalGuard("bthump"); // pzopt: guardedCallbacks, dropping the thump target stays on the game thread
            }

            if (this.getThumpTarget() instanceof IsoObject obj
               && !(this.getThumpTarget() instanceof BaseVehicle)
               && (obj.getSquare() == null || this.DistToSquared(obj.getX() + 0.5F, obj.getY() + 0.5F) > 9.0F)) {
               this.setThumpTarget(null);
            }

            if (this.getThumpTimer() > 0) {
               this.setThumpTarget(null);
            }

            return this.getThumpTarget() != null;
         },
         owner -> "Is this Zed attacking something that makes a thumping sound. Such as a Door."
      );
      this.setVariable("bundervehicle", () -> { pzopt.AnimParallel.impureGuard("bundervehicle"); return this.isUnderVehicle(); }, owner -> "Is this Zed currently under a vehicle.");
      this.setVariable("bBeingSteppedOn", () -> { pzopt.AnimParallel.impureGuard("bBeingSteppedOn"); return this.isBeingSteppedOn(); }, owner -> "Is this Zed currently being stepped on.");
      this.setVariable(
         "distancetotarget",
         () -> this.target == null ? -1.0F : this.vectorToTarget.getLength() - this.getWidth() + this.target.getWidth(),
         owner -> "How far away is this Zed's target, if it has one. Otherwise, it is -1."
      );
      this.setVariable("lasttargetseen", () -> this.lastTargetSeenX != -1, owner -> "Does this Zed remember its target's position.");
      this.setVariable("lungetimer", () -> this.lungeTimer, owner -> "Lunge timer countdown, in frames at 30fps. Starts at 180.0. Spins to Zero.");
      this.setVariable("reanimatetimer", this::getReanimateTimer, owner -> "Reanimate timer countdown, in frames at 30fps.");
      this.setVariable("turndirection", () -> {
         pzopt.AnimParallel.impureGuard("turndirection"); // pzopt: animatorParallel, never on a frame worker
         if (this.getPath2() != null) {
            return "";
         }

         if (this.target != null && this.vectorToTarget.getLength() != 0.0F) {
            if (GameClient.client && this.isRemoteZombie()) {
               NetworkZombieAI networkAi = this.getNetworkCharacterAI();
               tempo.set(networkAi.targetX - this.getX(), networkAi.targetY - this.getY());
            } else {
               tempo.set(this.vectorToTarget);
            }

            IsoDirections targetDir = IsoDirections.fromAngle(tempo);
            if (this.getForwardIsoDirection() == targetDir) {
               return "";
            }

            boolean left = CrawlingZombieTurnState.calculateDir(this, targetDir);
            return left ? "left" : "right";
         } else if (this.isCurrentState(WalkTowardState.instance())) {
            WalkTowardState.instance().calculateTargetLocation(this, tempo);
            tempo.x = tempo.x - this.getX();
            tempo.y = tempo.y - this.getY();
            IsoDirections targetDir = IsoDirections.fromAngle(tempo);
            if (this.getForwardIsoDirection() == targetDir) {
               return "";
            }

            boolean left = CrawlingZombieTurnState.calculateDir(this, targetDir);
            return left ? "left" : "right";
         } else {
            if (this.isCurrentState(PathFindState.instance())) {
            }

            return "";
         }
      }, owner -> "Is this Zed wanting to turn towards its target. Either Left or Right. If it is pathfinding, or has no target, returns blank \"\"");
      this.setVariable("alerted", () -> this.alerted, owner -> "Has this Zed been alerted to something.");
      this.setVariable("zombiewalktype", () -> this.walkType, owner -> "This Zed's walking type. slow1-3 if it's a shambler, or sprint1-5 if it's a sprinter.");
      this.setVariable("crawlertype", () -> this.crawlerType, owner -> "This Zed's crawler type. Typically 1 or 2. Or 0 if not set, or not a crawler.");
      this.setVariable("bGetUpFromCrawl", this::shouldGetUpFromCrawl, owner -> "Should this Zed get up from crawling.");
      this.setVariable(
         "playerattackposition",
         this::getPlayerAttackPosition,
         owner -> "From which direction is this Zed attacked from. FRONT, BEHIND, LEFT, or RIGHT. If not attacked, returns blank \"\""
      );
      this.setVariable("eatspeed", () -> this.eatSpeed, owner -> "When eating a body. The eating speed multiplier. 0.0 - 1.0");
      this.setVariable("issitting", this::isSitAgainstWall, owner -> "Is this Zed currently sitting.");
      this.setVariable("bKnifeDeath", this::isKnifeDeath, this::setKnifeDeath, owner -> "Has this Zed been attacked and killed by a knife.");
      this.setVariable("bJawStabAttach", this::isJawStabAttach, this::setJawStabAttach, owner -> "Was this Zed attacked and stabbed in the jaw.");
      this.setVariable(
         "bPathFindPrediction",
         () -> 2 == this.getNetworkCharacterAI().predictionType,
         owner -> "Network. The type of pathfinding prediction. One of: <br />  Moving, <br />  Static, <br />  Thump, <br />  Climb, <br />  Lunge, <br />  LungeHalf, <br />  Walk, <br />  WalkHalf, <br />  PathFind."
      );
      this.setVariable("bCrawling", this::isCrawling, this::setCrawler, owner -> "Is this Zed a crawler.");
      this.setVariable("AttackDidDamage", this::getAttackDidDamage, this::setAttackDidDamage, owner -> "Network. Did an attack do damage.");
      this.setVariable(
         "AttackOutcome",
         this::getAttackOutcome,
         this::setAttackOutcome,
         owner -> "The outcome of an attack. One of: <br />  start, <br />  interrupted, <br />  fail, <br />  success"
      );
   }

   protected void OnAnimEvent_IsAlmostUp(IsoGameCharacter owner) {
      super.OnAnimEvent_IsAlmostUp(owner);
      if (owner == this) {
         this.setSitAgainstWall(false);
      }
   }

   private boolean isIdleOrStaggering() {
      return this.isCurrentState(StaggerBackState.instance()) || this.isCurrentState(ZombieIdleState.instance());
   }

   protected boolean shouldSlideHeadAwayFromWalls() {
      if ((!this.isOnFloor() && !this.isKnockedDown() || this.isSitAgainstWall()) && !this.isIdleOrStaggering()) {
         return false;
      } else {
         return !this.isCrawling() || this.getPath2() == null && !this.isMoving()
            ? !this.isCurrentState(ClimbOverFenceState.instance()) && !this.isCurrentState(ClimbThroughWindowState.instance())
            : false;
      }
   }

   protected void slideHeadAwayFromWalls(boolean instant) {
      if (this.current != null) {
         if (this.shouldSlideHeadAwayFromWalls()) {
            float collisionAvoidanceRadius = 0.15F;
            this.slideAwayFromWalls(0.15F, instant, false);
         }
      }
   }

   public float getUnbalancedLevel() {
      return this.unbalancedLevel;
   }

   public void setUnbalancedLevel(float unbalancedLevel) {
      this.unbalancedLevel = unbalancedLevel;
   }

   private boolean getShouldAttack() {
      if (SystemDisabler.zombiesDontAttack) {
         return false;
      }

      if (this.isReanimatedForGrappleOnly()) {
         return false;
      }

      if (this.target != null && !(this.target instanceof IsoGameCharacter gameCharacter && gameCharacter.isZombiesDontAttack())) {
         if (this.target instanceof IsoGameCharacter isoGameCharacter) {
            if (this.target.isOnFloor() && isoGameCharacter.getCurrentState() != BumpedState.instance()) {
               pzopt.AnimParallel.conditionalGuard("battack"); // pzopt: guardedCallbacks, this branch never runs off the game thread
               this.setTarget(null);
               return false;
            }

            BaseVehicle vehicle = isoGameCharacter.getVehicle();
            if (vehicle != null) {
               return false;
            }

            if (isoGameCharacter.reanimatedCorpse != null) {
               return false;
            }

            if (isoGameCharacter.getStateMachine().getCurrent() == ClimbOverWallState.instance()) {
               return false;
            }
         }

         if (this.reanimate) {
            return false;
         } else if (Math.abs(this.target.getZ() - this.getZ()) >= 0.2F) {
            return false;
         } else if (this.target instanceof IsoPlayer player && player.isGhostMode()) {
            return false;
         } else {
            if (this.fakeDead) {
               pzopt.AnimParallel.conditionalGuard("battack"); // pzopt: guardedCallbacks, the vehicle walk stays on the game thread
               return !this.isUnderVehicle() && this.DistTo(this.target) < 1.3F;
            }

            if (!this.crawling) {
               IsoGridSquare currentSquare = this.getCurrentSquare();
               IsoGridSquare targetCurrentSquare = this.target.getCurrentSquare();
               if (currentSquare != null && currentSquare.isSomethingTo(targetCurrentSquare) && !BentFences.getInstance().isUnbentObject(this.target)) {
                  return false;
               }

               float attackRange = this.crawling ? 1.4F : 0.72F;
               float len = this.vectorToTarget.getLength();
               return len <= attackRange;
            } else {
               pzopt.AnimParallel.conditionalGuard("battack"); // pzopt: guardedCallbacks, the vehicle walk stays on the game thread
               return !this.isUnderVehicle() && this.DistTo(this.target) < 1.3F;
            }
         }
      } else {
         return false;
      }
   }

   public void actionStateChanged(ActionContext sender) {
      super.actionStateChanged(sender);
      NetworkZombieAI networkAi = this.getNetworkCharacterAI();
      if (networkAi != null && GameServer.server) {
         networkAi.extraUpdate();
      }
   }

   protected void onAnimPlayerCreated(AnimationPlayer animationPlayer) {
      super.onAnimPlayerCreated(animationPlayer);
      animationPlayer.setSharedAnimRepo(m_sharedSkeleRepo);
   }

   public String GetAnimSetName() {
      return this.crawling ? "zombie-crawler" : "zombie";
   }

   public void InitSpritePartsZombie() {
      SurvivorDesc d = this.descriptor;
      this.InitSpritePartsZombie(d);
   }

   public void InitSpritePartsZombie(SurvivorDesc desc) {
      this.sprite.disposeAnimation();
      this.legsSprite = this.sprite;
      this.legsSprite.name = desc.getTorso();
      this.zombieId = Rand.Next(10000);
      this.useParts = true;
   }

   public void pathToCharacter(IsoGameCharacter target) {
      if (!(this.allowRepathDelay > 0.0F)
         || !this.isCurrentState(PathFindState.instance())
            && !this.isCurrentState(WalkTowardState.instance())
            && !this.isCurrentState(WalkTowardNetworkState.instance())) {
         super.pathToCharacter(target);
      }
   }

   public void pathToLocationF(float x, float y, float z) {
      if (!(this.allowRepathDelay > 0.0F)
         || !this.isCurrentState(PathFindState.instance())
            && !this.isCurrentState(WalkTowardState.instance())
            && !this.isCurrentState(WalkTowardNetworkState.instance())) {
         super.pathToLocationF(x, y, z);
      }
   }

   public void load(ByteBuffer input, int worldVersion, boolean isDebugSave) throws IOException {
      super.load(input, worldVersion, isDebugSave);
      this.walkVariant = "ZombieWalk";
      this.spriteName = "BobZ";
      if (this.palette != 1) {
         this.spriteName = this.spriteName + this.palette;
      }

      SurvivorDesc desc = this.descriptor;
      this.setFemale(desc.isFemale());
      if (this.isFemale()) {
         if (this.palette == 1) {
            this.spriteName = "KateZ";
         } else {
            this.spriteName = "KateZ" + this.palette;
         }
      }

      this.hurtSound = desc.getVoicePrefix() + "Hurt";
      this.InitSpritePartsZombie(desc);
      this.sprite.def.tintr = 0.95F + Rand.Next(5) / 100.0F;
      this.sprite.def.tintg = 0.95F + Rand.Next(5) / 100.0F;
      this.sprite.def.tintb = 0.95F + Rand.Next(5) / 100.0F;
      this.setDefaultState(ZombieIdleState.instance());
      this.speedType = -1;
      this.inactive = GameTime.getInstance().isZombieInactivityPhase();
      this.DoZombieStats();
      int loadedFileVersion = input.getInt();
      this.setWidth(0.3F);
      this.timeSinceSeenFlesh = input.getInt();
      this.setAlpha(0.0F);
      int stateFlags = input.getInt();
      if (loadedFileVersion == 0) {
         DebugType.Zombie.debugln("Loading Zombie isFakeDead state:%d", new Object[]{stateFlags});
         this.setFakeDead(stateFlags == 1);
      } else if (loadedFileVersion >= 1) {
         ZombieStateFlags state = ZombieStateFlags.fromInt(stateFlags);
         DebugType.Zombie.debugln("Loading Zombie state flags:%s", new Object[]{state});
         if (state.isFakeDead()) {
            this.setFakeDead(true);
         } else if (state.isCrawling()) {
            this.setCrawler(true);
            this.setCanWalk(state.isCanWalk());
            this.setOnFloor(true);
            this.setFallOnFront(true);
            this.walkVariant = "ZombieWalk";
            this.DoZombieStats();
         }

         if (state.isInitialized()) {
            this.setCanCrawlUnderVehicle(state.isCanCrawlUnderVehicle());
         }

         this.setReanimatedForGrappleOnly(state.isReanimatedForGrappleOnly());
      }

      ArrayList<InventoryItem> items = this.savedInventoryItems;
      int wornItemCount = input.get();

      for (int i = 0; i < wornItemCount; i++) {
         ItemBodyLocation itemBodyLocation = ItemBodyLocation.get(ResourceLocation.of(GameWindow.ReadString(input)));
         short index = input.getShort();
         if (index >= 0 && index < items.size() && this.wornItems.getBodyLocationGroup().getLocation(itemBodyLocation) != null) {
            this.wornItems.setItem(itemBodyLocation, items.get(index));
         }
      }

      this.setStateMachineLocked(false);
      this.setDefaultState();
      this.getCell().getZombieList().add(this);
   }

   public void save(ByteBuffer output, boolean isDebugSave) throws IOException {
      DebugType.Saving.trace("Saving: %s", new Object[]{this});
      super.save(output, isDebugSave);
      output.putInt(1);
      output.putInt((int)this.timeSinceSeenFlesh);
      ZombieStateFlags state = ZombieStateFlags.fromZombie(this);
      output.putInt(state.asInt());
      if (this.wornItems.size() > 127) {
         throw new RuntimeException("too many worn items");
      }

      output.put((byte)this.wornItems.size());
      this.wornItems.forEach(wornItem -> {
         GameWindow.WriteString(output, wornItem.getLocation().toString());
         output.putShort((short)this.savedInventoryItems.indexOf(wornItem.getItem()));
      });
   }

   public void collideWith(IsoObject obj) {
      if (!this.ghost && obj != null) {
         if (obj.rerouteCollide != null) {
            obj = this.rerouteCollide;
         }

         State state = this.getCurrentState();
         boolean bMoving = this.isCurrentState(PathFindState.instance())
            || this.isCurrentState(LungeState.instance())
            || this.isCurrentState(LungeNetworkState.instance())
            || this.isCurrentState(WalkTowardState.instance())
            || this.isCurrentState(WalkTowardNetworkState.instance());
         IsoWindow window = (IsoWindow)Type.tryCastTo(obj, IsoWindow.class);
         if (window != null && window.canClimbThrough(this) && bMoving) {
            if (!this.isFacingObject(window, 0.8F)) {
               super.collideWith(obj);
               return;
            }

            if (state != PathFindState.instance() && !this.crawling) {
               this.climbThroughWindow(window);
            }
         } else {
            if (bMoving && !this.crawling && obj instanceof IsoWindowFrame windowFrame && windowFrame.canClimbThrough(this)) {
               if (!this.isFacingObject(windowFrame, 0.8F)) {
                  super.collideWith(obj);
                  return;
               }

               if (state != PathFindState.instance()) {
                  this.climbThroughWindowFrame(windowFrame);
               }

               return;
            }

            if (obj instanceof IsoThumpable isoThumpable && isoThumpable.canClimbThrough(this) && bMoving) {
               if (state != PathFindState.instance() && !this.crawling) {
                  this.climbThroughWindow(isoThumpable);
               }
            } else if ((!(obj instanceof IsoDoor) || !obj.isHoppable()) && obj != null && obj.getThumpableFor(this) != null && bMoving) {
               boolean chasingSound = (
                     this.isCurrentState(PathFindState.instance())
                        || this.isCurrentState(WalkTowardState.instance())
                        || this.isCurrentState(WalkTowardNetworkState.instance())
                  )
                  && this.getPathFindBehavior2().isGoalSound();
               if (!SandboxOptions.instance.lore.thumpNoChasing.getValue() && this.target == null && !chasingSound) {
                  this.setVariable("bPathfind", false);
                  this.setVariable("bMoving", false);
               } else {
                  if (obj instanceof IsoThumpable && !SandboxOptions.instance.lore.thumpOnConstruction.getValue()) {
                     return;
                  }

                  Thumpable thumpie = obj;
                  if (obj instanceof IsoWindow && obj.getThumpableFor(this) != null && obj.isDestroyed()) {
                     thumpie = obj.getThumpableFor(this);
                  }

                  this.setThumpTarget(thumpie);
               }

               this.setPath2(null);
            }
         }

         super.collideWith(obj);
      }
   }

   public float Hit(HandWeapon weapon, IsoGameCharacter wielder, float damageSplit, boolean bIgnoreDamage, float modDelta, boolean bRemote) {
      if ((Core.tutorial || Core.debug) && this.immortalTutorialZombie) {
         return 0.0F;
      }

      BodyPartType bodyPart = BodyPartType.FromIndex(
         Rand.Next(BodyPartType.ToIndex(BodyPartType.Torso_Upper), BodyPartType.ToIndex(BodyPartType.Torso_Lower) + 1)
      );
      if (Rand.NextBool(7)) {
         bodyPart = BodyPartType.Head;
      }

      if (wielder.isCriticalHit() && Rand.NextBool(3)) {
         bodyPart = BodyPartType.Head;
      }

      LuaEventManager.triggerEvent("OnHitZombie", this, wielder, bodyPart, weapon);
      this.lastHitPart = bodyPart;
      wielder.setLastHitCharacter(this);
      float damage = super.Hit(weapon, wielder, damageSplit, bIgnoreDamage, modDelta, bRemote);
      if (GameServer.server && !this.isRemoteZombie()) {
         this.addAggro(wielder, damage);
      }

      this.timeSinceSeenFlesh = 0.0F;
      if (!this.isDead()
         && !this.isOnFloor()
         && !bIgnoreDamage
         && weapon != null
         && wielder instanceof IsoPlayer
         && this.DistToProper(wielder) <= 0.9F
         && (
            this.isCurrentState(AttackState.instance())
               || this.isCurrentState(AttackNetworkState.instance())
               || this.isCurrentState(LungeState.instance())
               || this.isCurrentState(LungeNetworkState.instance())
         )) {
         this.setHitForce(0.5F);
         this.changeState(StaggerBackState.instance());
      }

      if (GameServer.server || GameClient.client && this.isDead()) {
         this.lastPlayerHit = wielder.getOnlineID();
      }

      return damage;
   }

   public void onMouseLeftClick() {
      if (IsoPlayer.getInstance() != null && !IsoPlayer.getInstance().isAiming()) {
         if (IsoPlayer.getInstance().IsAttackRange(this.getX(), this.getY(), this.getZ())) {
            Vector2 vec = new Vector2(this.getX(), this.getY());
            vec.x = vec.x - IsoPlayer.getInstance().getX();
            vec.y = vec.y - IsoPlayer.getInstance().getY();
            vec.normalize();
            IsoPlayer.getInstance().DirectionFromVector(vec);
            IsoPlayer.getInstance().AttemptAttack();
         }
      }
   }

   public void onZombieGrappleEnded() {
      if (this.isReanimatedForGrappleOnly()) {
         DebugType.Grapple.debugln("Reanimated Corpse Dropped.");
         if (GameClient.client) {
            this.setOnFloor(true);
            this.setHealth(0.0F);
         } else if (GameServer.server) {
            this.die();
         }
      }
   }

   private void renderAtlasTexture(float x, float y, float z) {
      if (this.atlasTex != null) {
         if (IsoSprite.globalOffsetX == -1.0F) {
            IsoSprite.globalOffsetX = -IsoCamera.frameState.offX;
            IsoSprite.globalOffsetY = -IsoCamera.frameState.offY;
         }

         float ssx = IsoUtils.XToScreen(x, y, z, 0);
         float ssy = IsoUtils.YToScreen(x, y, z, 0);
         this.sx = ssx;
         this.sy = ssy;
         ssx = this.sx + IsoSprite.globalOffsetX;
         ssy = this.sy + IsoSprite.globalOffsetY;
         ColorInfo col = inf.set(1.0F, 1.0F, 1.0F, 1.0F);
         if (this.getCurrentSquare() != null) {
            this.getCurrentSquare().interpolateLight(col, x - this.getCurrentSquare().getX(), y - this.getCurrentSquare().getY());
         }

         this.atlasTex.pzoptRenderWithOutline(pzopt.OccludedOutline.eligible(this, IsoCamera.frameState.playerIndex), x, y, z, (int)ssx, (int)ssy, col.r, col.g, col.b, col.a); // pzopt: current visibility accompanies the atlas draw without forcing a 3D model
      }
   }

   public void render(float x, float y, float z, ColorInfo col, boolean bDoChild, boolean bWallLightingPass, Shader shader) {
      if (this.getCurrentState() == FakeDeadZombieState.instance()) {
         if (this.dressInRandomOutfit) {
            ModelManager.instance.dressInRandomOutfit(this);
         }

         if (this.atlasTex == null) {
            if (this.hasAnimationPlayer()) {
               this.getAnimationPlayer().Update(0.0F);
            }

            this.atlasTex = DeadBodyAtlas.instance.getBodyTexture(this);
            DeadBodyAtlas.instance.render();
         }

         if (this.atlasTex != null) {
            this.renderAtlasTexture(x, y, z);
         }
      } else {
         if (this.atlasTex != null) {
            this.atlasTex = null;
         }

         if (IsoCamera.getCameraCharacter() != IsoPlayer.getInstance()) {
            this.setAlphaAndTarget(1.0F);
         }

         super.render(x, y, z, col, bDoChild, bWallLightingPass, shader);
      }
   }

   public void renderlast() {
      super.renderlast();
      if (DebugOptions.instance.zombieRenderCanCrawlUnderVehicle.getValue() && this.isCanCrawlUnderVehicle()) {
         this.renderTextureOverHead("media/ui/FavoriteStar.png");
      }

      if (DebugOptions.instance.zombieRenderMemory.getValue()) {
         String texName;
         if (this.target == null) {
            texName = "media/ui/Moodles/Moodle_Icon_Bored.png";
         } else if (this.bonusSpotTime == 0.0F) {
            texName = "media/ui/Moodles/Moodle_Icon_Angry.png";
         } else {
            texName = "media/ui/Moodles/Moodle_Icon_Zombie.png";
         }

         this.renderTextureOverHead(texName);
         int sx1 = (int)IsoUtils.XToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
         int sy1 = (int)IsoUtils.YToScreenExact(this.getX(), this.getY(), this.getZ(), 0);
         int fontHgt = TextManager.instance.getFontFromEnum(UIFont.Small).getLineHeight();
         int var5;
         TextManager.instance.DrawString(sx1, var5 = sy1 + fontHgt, "AllowRepathDelay : " + this.allowRepathDelay);
         TextManager.instance.DrawString(sx1, sy1 = var5 + fontHgt, "BonusSpotTime : " + this.bonusSpotTime);
         int var7;
         TextManager.instance.DrawString(sx1, var7 = sy1 + fontHgt, "TimeSinceSeenFlesh : " + this.timeSinceSeenFlesh);
      }

      if (DebugOptions.instance.zombieRenderViewDistance.getValue()) {
         if (this.target == null) {
            this.updateVisionRadius();
            LineDrawer.DrawIsoCircle(this.getX(), this.getY(), this.getZ(), this.visionRadiusResult, 32, 1.0F, 1.0F, 1.0F, 0.3F);
         } else if (this.bonusSpotTime == 0.0F) {
            LineDrawer.DrawIsoCircle(this.getX(), this.getY(), this.getZ(), this.visionRadiusResult, 32, 1.0F, 1.0F, 0.0F, 0.3F);
         } else {
            LineDrawer.DrawIsoCircle(this.getX(), this.getY(), this.getZ(), this.visionRadiusResult, 32, 1.0F, 0.0F, 0.0F, 0.3F);
         }
      }
   }

   protected boolean renderTextureInsteadOfModel(float x, float y) {
      boolean bMoving = this.isCurrentState(WalkTowardState.instance()) || this.isCurrentState(PathFindState.instance());
      String animSet = "zombie";
      String stateName = bMoving ? "walktoward" : "idle";
      int frames = 4;
      int frame = (int)(this.characterTextureAnimTime / this.characterTextureAnimDuration * 4.0F);
      float trackTime = (bMoving ? 0.67F : 1.0F) * (frame / 4.0F);
      BodyTexture tex = DeadBodyAtlas.instance.getBodyTexture(this.isFemale(), "zombie", stateName, this.getDir(), frame, trackTime);
      if (tex != null) {
         float sx = IsoUtils.XToScreen(x, y, this.getZ(), 0);
         float sy = IsoUtils.YToScreen(x, y, this.getZ(), 0);
         sx -= IsoCamera.getOffX();
         sy -= IsoCamera.getOffY();
         int playerIndex = IsoCamera.frameState.playerIndex;
         if (PerformanceSettings.fboRenderChunk) {
            IndieGL.enableDepthTest();
            IndieGL.glBlendFunc(770, 771);
            IndieGL.glDepthFunc(515);
            IndieGL.StartShader(0);
            TextureDraw.nextZ = IsoDepthHelper.getSquareDepthData(
                        PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                        PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                        x + 0.3F,
                        y + 0.3F,
                        this.getZ() + 0.3F
                     )
                     .depthStart
                  * 2.0F
               - 1.0F;
         }

         tex.render(x, y, this.getZ(), sx, sy, 0.0F, 0.0F, 0.0F, this.getAlpha(playerIndex));
      }

      if (DebugOptions.instance.character.debug.render.angle.getValue()) {
         tempo.set(this.getForwardIsoDirection().ToVector());
         this.drawDirectionLine(tempo, 1.2F, 0.0F, 1.0F, 0.0F);
      }

      return true;
   }

   /**
    * pzopt: zombieAtlasFast. The draw of a zombie without the virtual chain IsoZombie.render -> IsoGameCharacter.render
    * for the two cases that make up a horde: a culled zombie (no active model, drawn as an atlas sprite) and a zombie
    * whose model draw data the pre-pass built (pzopt.CharDraw.isPrepared). The same tests in the same order, the same
    * writes (the alpha rule when the camera is not on the player, the depth-mask state, the static light scratch, the
    * default facing, the last-rendered statics, the texture-creator check, the sprite scale, the item updaters, the
    * debug renders) and the same middle: renderTextureInsteadOfModel for the culled one, IsoSprite.renderActiveModel's
    * body (the lights call, which the frame stamp skips, the camera record, the model enqueue that takes the prepared
    * data) for the other. Returns false, having done nothing, for anything else (fire sprites, a non-parts sprite, the
    * non-fbo path, a debug mode, a fake-dead zombie, a model without prepared data): the caller then runs the stock
    * render. ~1,100 + ~480 of the 1,600 on-screen objects of the Louisville horde take this path.
    */
   public boolean pzoptRenderFlat(float x, float y, float z, ColorInfo col) {
      if (!pzopt.Overrides.enabled() || !pzopt.Config.ZOMBIE_ATLAS_FAST || Core.debug || !PerformanceSettings.fboRenderChunk) {
         return false;
      }
      if (this.attachedAnimSprite != null || !this.useParts || this.sprite == null || this.legsSprite == null) {
         return false;
      }
      boolean model = this.legsSprite.hasActiveModel();
      if (model && !pzopt.CharDraw.isPrepared(this.legsSprite.modelSlot)) {
         return false; // the stock chain builds its draw data
      }
      if (this.getCurrentState() == FakeDeadZombieState.instance()) {
         return false;
      }

      // IsoZombie.render, the non-fake-dead branch
      if (this.atlasTex != null) {
         this.atlasTex = null;
      }

      if (IsoCamera.getCameraCharacter() != IsoPlayer.getInstance()) {
         this.setAlphaAndTarget(1.0F);
      }

      // IsoGameCharacter.render
      if (!this.getDoRender() || this.isAlphaAndTargetZero()) {
         return true;
      }

      if (this.isSeatedInVehicle() && !this.getVehicle().showPassenger(this)) {
         return true;
      }

      if (this.isSpriteInvisible() || this.isAlphaZero()) {
         return true;
      }

      IndieGL.glDepthMask(true);
      IsoGridSquare currentSquare = this.getCurrentSquare();
      if (currentSquare != null) {
         currentSquare.interpolateLight(inf, x - currentSquare.getX(), y - currentSquare.getY());
      } else {
         inf.r = col.r;
         inf.g = col.g;
         inf.b = col.b;
         inf.a = col.a;
      }

      if (this.getForwardIsoDirection() == null) {
         this.setForwardIsoDirection(IsoDirections.N);
      }

      lastRenderedRendered = lastRendered;
      lastRendered = this;
      this.checkUpdateModelTextures();
      float scale = Core.tileScale;
      this.def.setScale(scale, scale);
      if (model) {
         // IsoSprite.renderActiveModel
         zombie.core.skinnedmodel.ModelManager.ModelSlot modelSlot = this.legsSprite.modelSlot;
         if (modelSlot.model.object != null && DebugOptions.instance.isoSprite.renderModels.getValue()) {
            try (zombie.GameProfiler.ProfileArea ignored = zombie.GameProfiler.getInstance().profile("Render Active Model")) {
               if (!zombie.iso.fboRenderChunk.FBORenderCell.instance.renderDebugChunkState) {
                  modelSlot.model.updateLights(); // returns at the frame stamp: the pre-pass ran it
               }

               zombie.core.skinnedmodel.ModelCameraRenderData cameraRenderData = pzopt.CharDraw.takeCamera(modelSlot); // built with the draw data
               if (cameraRenderData == null) {
                  cameraRenderData = zombie.core.skinnedmodel.ModelCameraRenderData.s_pool.alloc();
                  cameraRenderData.init(zombie.core.opengl.CharacterModelCamera.instance, modelSlot);
               }

               zombie.core.SpriteRenderer.instance.drawGeneric(cameraRenderData);
               zombie.core.SpriteRenderer.instance.drawModel(modelSlot);
            }
         }
      } else {
         this.renderTextureInsteadOfModel(x, y);
      }

      java.util.ArrayList<InventoryItem> items = this.inventory.items;
      for (int n = 0; n < items.size(); n++) {
         InventoryItem item = items.get(n);
         if (item instanceof zombie.interfaces.IUpdater iUpdater) {
            iUpdater.render();
         }
      }

      if (this.getRagdollController() != null && this.canRagdoll()) {
         this.getRagdollController().debugRender();
      }

      if (this.getBallisticsController() != null) {
         this.getBallisticsController().debugRender();
      }

      if (this.getBallisticsTarget() != null) {
         this.getBallisticsTarget().debugRender();
      }

      return true;
   }

   private void renderTextureOverHead(String textureName) {
      float renderX = this.getX();
      float renderY = this.getY();
      float sx = IsoUtils.XToScreen(renderX, renderY, this.getZ(), 0);
      float sy = IsoUtils.YToScreen(renderX, renderY, this.getZ(), 0);
      sx = sx - IsoCamera.getOffX() - this.offsetX;
      sy = sy - IsoCamera.getOffY() - this.offsetY;
      sy -= 128.0F / (2.0F / Core.tileScale);
      Texture tex = Texture.getSharedTexture(textureName);
      float zoom = Core.getInstance().getZoom(IsoCamera.frameState.playerIndex);
      zoom = Math.max(zoom, 1.0F);
      int texW = (int)(tex.getWidth() * zoom);
      int texH = (int)(tex.getHeight() * zoom);
      tex.render((int)sx - texW / 2.0F, (int)sy - texH, texW, texH);
   }

   protected void updateAlpha(int playerIndex, float mul, float div) {
      if (this.isFakeDead()) {
         this.setAlphaAndTarget(1.0F);
      } else {
         super.updateAlpha(playerIndex, mul, div);
      }
   }

   private void initFMODParameters() {
      if (!GameServer.server) {
         this.getFMODParameters().add(this.parameterFootstepMaterial);
      }
   }

   public boolean isRespondingToPlayerSound() {
      return this.timeSinceRespondToSound < 1000.0F && (this.soundSourceIsPlayer || this.soundSourceIsPlayerBase);
   }

   public boolean isMovingToPlayerSound() {
      return (this.getCurrentState() == PathFindState.instance() || this.getCurrentState() == WalkTowardState.instance())
            && this.getPathFindBehavior2().isGoalSound()
         ? this.soundSourceIsPlayer || this.soundSourceIsPlayerBase
         : false;
   }

   public void RespondToSound() {
      if (!this.ghost) {
         if (!this.isUseless()) {
            if (!GameServer.server) {
               if (!GameClient.client || !this.isRemoteZombie()) {
                  if ((this.getCurrentState() == PathFindState.instance() || this.getCurrentState() == WalkTowardState.instance())
                     && this.getPathFindBehavior2().isGoalSound()
                     && PZMath.fastfloor(this.getZ()) == this.getPathTargetZ()
                     && this.soundSourceRepeating) {
                     float dist = this.DistToSquared(this.getPathTargetX(), this.getPathTargetY());
                     if (dist < 16.0F
                        && LosUtil.lineClear(
                              this.getCell(),
                              PZMath.fastfloor(this.getX()),
                              PZMath.fastfloor(this.getY()),
                              PZMath.fastfloor(this.getZ()),
                              this.getPathTargetX(),
                              this.getPathTargetY(),
                              PZMath.fastfloor(this.getZ()),
                              false
                           )
                           != TestResults.Blocked
                        && !this.isNearSirenVehicle()) {
                        this.setVariable("bPathfind", false);
                        this.setVariable("bMoving", false);
                        this.setPath2(null);
                     }
                  }

                  if (this.soundReactDelay > 0.0F) {
                     this.soundReactDelay = this.soundReactDelay - GameTime.getInstance().getThirtyFPSMultiplier();
                     if (this.soundReactDelay < 0.0F) {
                        this.soundReactDelay = 0.0F;
                     }

                     if (this.soundReactDelay > 0.0F) {
                        return;
                     }
                  }

                  float tmpSoundAttract = 0.0F;
                  Object tmpSoundSourceTarget = null;
                  WorldSoundManager.WorldSound sound = WorldSoundManager.instance.getSoundZomb(this);
                  float attract = WorldSoundManager.instance.getSoundAttract(sound, this);
                  if (attract <= 0.0F) {
                     sound = null;
                  }

                  if (sound != null) {
                     tmpSoundAttract = attract;
                     tmpSoundSourceTarget = sound.source;
                     this.soundAttract = tmpSoundAttract;
                     this.soundAttractTimeout = 60.0F;
                  } else if (this.soundAttractTimeout > 0.0F) {
                     this.soundAttractTimeout = this.soundAttractTimeout - GameTime.getInstance().getThirtyFPSMultiplier();
                     if (this.soundAttractTimeout < 0.0F) {
                        this.soundAttractTimeout = 0.0F;
                     }
                  }

                  WorldSoundManager.ResultBiggestSound soundB = WorldSoundManager.instance
                     .getBiggestSoundZomb(PZMath.fastfloor(this.getX()), PZMath.fastfloor(this.getY()), PZMath.fastfloor(this.getZ()), true, this);
                  if (soundB.sound != null && (this.soundAttractTimeout == 0.0F || this.soundAttract * 2.0F < soundB.attract)) {
                     sound = soundB.sound;
                     tmpSoundAttract = soundB.attract;
                     tmpSoundSourceTarget = sound.source;
                  }

                  if (sound != null && sound.repeating && sound.z == PZMath.fastfloor(this.getZ())) {
                     float dist = this.DistToSquared(sound.x, sound.y);
                     if (dist < 25.0F
                        && LosUtil.lineClear(
                              this.getCell(),
                              PZMath.fastfloor(this.getX()),
                              PZMath.fastfloor(this.getY()),
                              PZMath.fastfloor(this.getZ()),
                              sound.x,
                              sound.y,
                              PZMath.fastfloor(this.getZ()),
                              false
                           )
                           != TestResults.Blocked) {
                        sound = null;
                     }
                  }

                  if (this.shouldStopThumpingToRespondToSound(sound)) {
                     this.setThumpTarget(null);
                  }

                  if (sound != null) {
                     this.soundAttract = tmpSoundAttract;
                     this.soundSourceTarget = tmpSoundSourceTarget;
                     this.soundReactDelay = Rand.Next(0, 16);
                     this.delayedSound.x = sound.x;
                     this.delayedSound.y = sound.y;
                     this.delayedSound.z = sound.z;
                     this.soundSourceRepeating = sound.repeating;
                     this.soundSourceIsPlayer = sound.sourceIsPlayer;
                     this.soundSourceIsPlayerBase = sound.sourceIsPlayerBase;
                  }

                  if (this.delayedSound.x != -1 && this.soundReactDelay == 0.0F) {
                     int soundX = this.delayedSound.x;
                     int soundY = this.delayedSound.y;
                     int soundZ = this.delayedSound.z;
                     this.delayedSound.x = -1;
                     float dist = IsoUtils.DistanceManhatten(this.getX(), this.getY(), soundX, soundY) / 2.5F;
                     soundX += Rand.Next((int)(-dist), (int)dist);
                     soundY += Rand.Next((int)(-dist), (int)dist);
                     if ((this.getCurrentState() == PathFindState.instance() || this.getCurrentState() == WalkTowardState.instance())
                        && (this.getPathFindBehavior2().isGoalLocation() || this.getPathFindBehavior2().isGoalSound())) {
                        if (!IsoUtils.isSimilarDirection(
                           this, soundX, soundY, this.getPathFindBehavior2().getTargetX(), this.getPathFindBehavior2().getTargetY(), 0.5F
                        )) {
                           this.setTurnAlertedValues(soundX, soundY);
                           this.pathToSound(soundX, soundY, soundZ);
                           this.setLastHeardSound(this.getPathTargetX(), this.getPathTargetY(), this.getPathTargetZ());
                           this.allowRepathDelay = 120.0F;
                           this.timeSinceRespondToSound = 0.0F;
                        }

                        return;
                     }

                     if (this.timeSinceRespondToSound < 60.0F) {
                        return;
                     }

                     if (!IsoUtils.isSimilarDirection(
                        this, soundX, soundY, this.getX() + this.getForwardDirectionX(), this.getY() + this.getForwardDirectionY(), 0.5F
                     )) {
                        this.setTurnAlertedValues(soundX, soundY);
                     }

                     if (LosUtil.lineClear(this.getCell(), this.getXi(), this.getYi(), this.getZi(), soundX, soundY, soundZ, false) != TestResults.Blocked) {
                        this.pathToSound(soundX, soundY, soundZ);
                        this.setLastHeardSound(this.getPathTargetX(), this.getPathTargetY(), this.getPathTargetZ());
                     } else {
                        int offsetHearing = 2;
                        if (ClimateManager.getInstance().getRainIntensity() > 0.5F) {
                           offsetHearing = 5;
                        }

                        if (SandboxOptions.instance.lore.hearing.getValue() == 1) {
                           offsetHearing -= 2;
                        }

                        if (SandboxOptions.instance.lore.hearing.getValue() == 3) {
                           offsetHearing += 2;
                        }

                        offsetHearing = Math.max(Math.min(10, offsetHearing), 2);
                        int heardX = -offsetHearing + (int)(Math.random() * (offsetHearing * 2 + 1));
                        int heardY = -offsetHearing + (int)(Math.random() * (offsetHearing * 2 + 1));
                        this.pathToSound(soundX + heardX, soundY + heardY, soundZ);
                        this.setLastHeardSound(this.getPathTargetX() + heardX, this.getPathTargetY() + heardY, this.getPathTargetZ());
                     }

                     this.allowRepathDelay = 120.0F;
                     this.timeSinceRespondToSound = 0.0F;
                  }
               }
            }
         }
      }
   }

   private boolean shouldStopThumpingToRespondToSound(WorldSoundManager.WorldSound sound) {
      if (sound == null) {
         return false;
      }

      if (sound.sourceIsZombie) {
         return false;
      }

      if (sound.radius < 10) {
         return false;
      }

      if (!this.isCurrentState(ThumpState.instance())) {
         return false;
      }

      if (this.getTarget() instanceof IsoGameCharacter targetChr) {
         if (this.isTargetLocationKnown() && this.getDotWithForwardDirection(targetChr.getX(), targetChr.getY()) > 0.0F) {
            return false;
         }

         if (!this.isTargetLocationKnown() && this.getDotWithForwardDirection(this.lastTargetSeenX, this.lastTargetSeenY) > 0.0F) {
            return false;
         }
      }

      return this.getDotWithForwardDirection(sound.x, sound.y) < 0.0F;
   }

   public void setTurnAlertedValues(int soundX, int soundY) {
      Vector2 dirOfSound = new Vector2(this.getX() - (soundX + 0.5F), this.getY() - (soundY + 0.5F));
      float radDirOfSound = dirOfSound.getDirectionNeg();
      if (radDirOfSound < 0.0F) {
         radDirOfSound = Math.abs(radDirOfSound);
      } else {
         radDirOfSound = (float)((Math.PI * 2) - radDirOfSound);
      }

      double degreeOfSound = Math.toDegrees(radDirOfSound);
      Vector2 zombieAngle = new Vector2(this.getDir().Rot180().ToVector().x, this.getDir().Rot180().ToVector().y);
      zombieAngle.normalize();
      float radDirOfZombie = zombieAngle.getDirectionNeg();
      if (radDirOfZombie < 0.0F) {
         radDirOfZombie = Math.abs(radDirOfZombie);
      } else {
         radDirOfZombie = (float) (Math.PI * 2) - radDirOfZombie;
      }

      double degreeOfZombie = Math.toDegrees(radDirOfZombie);
      if ((int)degreeOfZombie == 360) {
         degreeOfZombie = 0.0;
      }

      if ((int)degreeOfSound == 360) {
         degreeOfSound = 0.0;
      }

      String trueDegree = "0";
      if (degreeOfSound > degreeOfZombie) {
         int sumUpDegree = (int)(degreeOfSound - degreeOfZombie);
         if (sumUpDegree > 350 || sumUpDegree <= 35) {
            trueDegree = "45R";
         }

         if (sumUpDegree > 35 && sumUpDegree <= 80) {
            trueDegree = "90R";
         }

         if (sumUpDegree > 80 && sumUpDegree <= 125) {
            trueDegree = "135R";
         }

         if (sumUpDegree > 125 && sumUpDegree <= 170) {
            trueDegree = "180R";
         }

         if (sumUpDegree > 170 && sumUpDegree < 215) {
            trueDegree = "180L";
         }

         if (sumUpDegree >= 215 && sumUpDegree < 260) {
            trueDegree = "135L";
         }

         if (sumUpDegree >= 260 && sumUpDegree < 305) {
            trueDegree = "90L";
         }

         if (sumUpDegree >= 305 && sumUpDegree < 350) {
            trueDegree = "45L";
         }
      } else {
         int sumUpDegree = (int)(degreeOfZombie - degreeOfSound);
         if (sumUpDegree > 10 && sumUpDegree <= 55) {
            trueDegree = "45L";
         }

         if (sumUpDegree > 55 && sumUpDegree <= 100) {
            trueDegree = "90L";
         }

         if (sumUpDegree > 100 && sumUpDegree <= 145) {
            trueDegree = "135L";
         }

         if (sumUpDegree > 145 && sumUpDegree <= 190) {
            trueDegree = "180L";
         }

         if (sumUpDegree > 190 && sumUpDegree < 235) {
            trueDegree = "180R";
         }

         if (sumUpDegree >= 235 && sumUpDegree < 280) {
            trueDegree = "135R";
         }

         if (sumUpDegree >= 280 && sumUpDegree < 325) {
            trueDegree = "90R";
         }

         if (sumUpDegree >= 325 || sumUpDegree < 10) {
            trueDegree = "45R";
         }
      }

      this.setVariable("turnalertedvalue", trueDegree);
      ZombieTurnAlerted.instance().setParams(this, dirOfSound.set(soundX + 0.5F - this.getX(), soundY + 0.5F - this.getY()).getDirection());
      this.alerted = true;
      this.getNetworkCharacterAI().extraUpdate();
   }

   public boolean getAttackDidDamage() {
      return this.attackDidDamage;
   }

   public void setAttackDidDamage(boolean attackDidDamage) {
      this.attackDidDamage = attackDidDamage;
   }

   public String getAttackOutcome() {
      return this.attackOutcome;
   }

   public void setAttackOutcome(String attackOutcome) {
      this.attackOutcome = attackOutcome;
   }

   public void setReanimatedForGrappleOnly(boolean val) {
      this.reanimatedForGrappleOnly = val;
   }

   public boolean isReanimatedForGrappleOnly() {
      return this.reanimatedForGrappleOnly;
   }

   public void clearAggroList() {
      try {
         Arrays.fill(this.aggroList, null);
      } catch (Exception e) {
      }
   }

   private void processAggroList() {
      try {
         for (int i = 0; i < this.aggroList.length; i++) {
            if (this.aggroList[i] != null && this.aggroList[i].getAggro() <= 0.0F) {
               this.aggroList[i] = null;
               return;
            }
         }
      } catch (Exception e) {
      }
   }

   public void addAggro(IsoMovingObject other, float damage) {
      try {
         if (this.aggroList[0] == null) {
            this.aggroList[0] = new IsoZombie.Aggro(other, damage);
         } else {
            for (int i = 0; i < this.aggroList.length; i++) {
               if (this.aggroList[i] != null && this.aggroList[i].obj == other) {
                  this.aggroList[i].addDamage(damage);
                  return;
               }
            }

            for (int i = 0; i < this.aggroList.length; i++) {
               if (this.aggroList[i] == null) {
                  this.aggroList[i] = new IsoZombie.Aggro(other, damage);
                  return;
               }
            }
         }
      } catch (Exception e) {
      }
   }

   public boolean isLeadAggro(IsoMovingObject other) {
      try {
         if (this.aggroList[0] == null) {
            return false;
         }

         this.processAggroList();
         if (this.aggroList[0] == null) {
            return false;
         }

         IsoMovingObject lead = this.aggroList[0].obj;
         float leadAggro = this.aggroList[0].getAggro();

         for (int i = 1; i < this.aggroList.length; i++) {
            if (this.aggroList[i] != null) {
               if (leadAggro >= 1.0F && this.aggroList[i].getAggro() >= 1.0F) {
                  return false;
               }

               if (this.aggroList[i] != null && leadAggro < this.aggroList[i].getAggro()) {
                  lead = this.aggroList[i].obj;
                  leadAggro = this.aggroList[i].getAggro();
               }
            }
         }

         return other == lead && leadAggro == 1.0F;
      } catch (Exception e) {
         return false;
      }
   }

   private Float closeSneakBonusCoeff(IsoGridSquare sq, IsoDirections dir) {
      if (sq == null) {
         return null;
      }

      PZArrayList<IsoObject> list = sq.getObjects();

      for (int i = 0; i < list.size(); i++) {
         IsoSprite sprite = ((IsoObject)list.get(i)).getSprite();
         if (sprite != null
            && sprite.getName() != null
            && temporaryMapCloseSneakBonusDir.containsKey(sprite.getName())
            && temporaryMapCloseSneakBonusValue.containsKey(sprite.getName())) {
            try {
               if (dir == IsoDirections.N && temporaryMapCloseSneakBonusDir.get(sprite.getName()).contains("N")) {
                  return temporaryMapCloseSneakBonusValue.get(sprite.getName()) / 100.0F;
               }

               if (dir == IsoDirections.S && temporaryMapCloseSneakBonusDir.get(sprite.getName()).contains("S")) {
                  return temporaryMapCloseSneakBonusValue.get(sprite.getName()) / 100.0F;
               }

               if (dir == IsoDirections.W && temporaryMapCloseSneakBonusDir.get(sprite.getName()).contains("W")) {
                  return temporaryMapCloseSneakBonusValue.get(sprite.getName()) / 100.0F;
               }

               if (dir == IsoDirections.E && temporaryMapCloseSneakBonusDir.get(sprite.getName()).contains("E")) {
                  return temporaryMapCloseSneakBonusValue.get(sprite.getName()) / 100.0F;
               }
            } catch (Exception e) {
               DebugType.General.println("Problem with tile property CloseSneakBonus" + sprite.getName());
            }
         }
      }

      return null;
   }

   private float getObstacleMod(IsoGridSquare sq, IsoDirections dir) {
      Float currentSquareCoeff = this.closeSneakBonusCoeff(sq, dir);
      if (currentSquareCoeff != null) {
         return 1.0F - currentSquareCoeff;
      }

      IsoGridSquare nextSq = sq.getAdjacentSquare(dir);
      IsoDirections nextDir = dir == IsoDirections.S ? IsoDirections.N : (dir == IsoDirections.E ? IsoDirections.W : dir);
      Float nextSquareCoeff = this.closeSneakBonusCoeff(nextSq, nextDir);
      return nextSquareCoeff != null ? 1.0F - nextSquareCoeff : 1.0F;
   }

   public void spottedNew(IsoMovingObject other, boolean bForced) {
      if (GameClient.client && this.isRemoteZombie()) {
         if (this.getTarget() != null) {
            this.vectorToTarget.x = this.getTarget().getX();
            this.vectorToTarget.y = this.getTarget().getY();
            this.vectorToTarget.x = this.vectorToTarget.x - this.getX();
            this.vectorToTarget.y = this.vectorToTarget.y - this.getY();
            if (this.isCurrentState(LungeNetworkState.instance())) {
               Vector2 temp = new Vector2();
               temp.x = this.vectorToTarget.x;
               temp.y = this.vectorToTarget.y;
               temp.normalize();
               this.setForwardDirection(temp);
            }
         }
      } else if (this.getCurrentSquare() != null) {
         if (other.getCurrentSquare() != null) {
            if (GameClient.client && !GameClient.connection.isReady()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.isReanimatedForGrappleOnly()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.isUseless()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.getCurrentSquare().getProperties().has(IsoFlagType.smoke)) {
               this.setTarget(null);
               this.spottedLast = null;
            } else {
               IsoGameCharacter otherCharacter = (IsoGameCharacter)Type.tryCastTo(other, IsoGameCharacter.class);
               if (otherCharacter != null && !otherCharacter.isDead()) {
                  IsoPlayer otherPlayer = (IsoPlayer)Type.tryCastTo(other, IsoPlayer.class);
                  if (otherPlayer == null || !otherPlayer.isGhostMode()) {
                     if (!GameClient.client && !GameServer.server || otherPlayer == null || !otherPlayer.getNetworkCharacterAI().isDisconnected()) {
                        if (this.getCurrentSquare() == null) {
                           this.ensureOnTile();
                        }

                        if (other.getCurrentSquare() == null) {
                           other.ensureOnTile();
                        }

                        float chance = 100.0F;
                        int playerIndex = otherPlayer != null && !GameServer.server ? otherPlayer.playerIndex : 0;
                        float lightData = (
                              other.getCurrentSquare().lighting[playerIndex].lightInfo().r
                                 + other.getCurrentSquare().lighting[playerIndex].lightInfo().g
                                 + other.getCurrentSquare().lighting[playerIndex].lightInfo().b
                           )
                           / 3.0F;
                        lightData = Math.max(0.0F, Math.min(1.0F, lightData));
                        float lightMod = lightData;
                        chance *= lightMod;
                        if (other.getCurrentSquare().getZ() != this.current.getZ()) {
                           int dif = Math.abs(other.getCurrentSquare().getZ() - this.current.getZ()) * 5 + 1;
                           chance /= dif;
                        }

                        Vector2 thisToOther = IsoGameCharacter.tempo;
                        thisToOther.x = other.getX();
                        thisToOther.y = other.getY();
                        thisToOther.x = thisToOther.x - this.getX();
                        thisToOther.y = thisToOther.y - this.getY();
                        float viewDist = GameTime.getInstance().getViewDist();
                        if (!(thisToOther.getLength() > viewDist)) {
                           if (GameServer.server) {
                              this.indoorZombie = false;
                           }

                           if (thisToOther.getLength() < viewDist) {
                              viewDist = thisToOther.getLength();
                           }

                           viewDist *= 1.1F;
                           if (viewDist > GameTime.getInstance().getViewDistMax()) {
                              viewDist = GameTime.getInstance().getViewDistMax();
                           }

                           thisToOther.normalize();
                           this.updateVisionRadius();
                           if (this.DistTo(other) > this.visionRadiusResult) {
                              chance = 0.0F;
                           }

                           // pzopt: zombieSpotFast. The facing test below only scales the chance, so a chance already zero (the
                           // player beyond this zombie's vision radius, or standing in the dark) skips the look-vector trig; the
                           // vision-radius update moved above it (no dependence either way) so that is known first.
                           boolean pzoptSkipFacing = chance == 0.0F && !bForced && pzopt.Overrides.enabled() && pzopt.Config.ZOMBIE_SPOT_FAST;
                           if (viewDist > 0.5F && !pzoptSkipFacing) {
                              Vector2 thisForward = this.getLookVector(tempo2);
                              float cosAngle = thisForward.dot(thisToOther);
                              if (cosAngle < -0.4F) {
                                 chance = 0.0F;
                              } else if (cosAngle < -0.2F) {
                                 chance /= 8.0F;
                              } else if (cosAngle < -0.0F) {
                                 chance /= 4.0F;
                              } else if (cosAngle < 0.2F) {
                                 chance /= 2.0F;
                              } else if (cosAngle <= 0.4F) {
                                 chance *= 2.0F;
                              } else if (cosAngle <= 0.6F) {
                                 chance *= 8.0F;
                              } else if (cosAngle <= 0.8F) {
                                 chance *= 16.0F;
                              } else {
                                 chance *= 32.0F;
                              }
                           }

                           // pzopt: zombieSpotFast. A chance of zero here (the zombie faces away, or the player is beyond its vision
                           // radius) stays zero through every modifier below (each one multiplies or divides it) and the roll cannot
                           // succeed, so the zombie skips straight to the one early exit a zero chance still takes and to the
                           // failed-roll bookkeeping: the could-be-seen flag needs a chance above 20 and the sneak / lightfoot XP rolls
                           // do not read the chance. Saves the perk walk, two trait lookups, the vehicle test and the pow per zombie.
                           boolean success = false;
                           boolean pzoptZero = chance == 0.0F && !bForced && pzopt.Overrides.enabled() && pzopt.Config.ZOMBIE_SPOT_FAST;
                           if (pzoptZero) {
                              if (this.target != other && this.target != null) {
                                 float distOther = IsoUtils.DistanceManhatten(this.getX(), this.getY(), other.getX(), other.getY());
                                 float distCurrent = IsoUtils.DistanceManhatten(this.getX(), this.getY(), this.target.getX(), this.target.getY());
                                 if (distOther > distCurrent) {
                                    return;
                                 }
                              }
                           } else {
                              if (chance > 0.0F
                                 && this.target instanceof IsoPlayer player
                                 && !GameServer.server
                                 && player.remoteId == -1
                                 && this.current.isCanSee(player.playerIndex)) {
                                 player.targetedByZombie = true;
                                 player.lastTargeted = 0.0F;
                              }

                              float movementData = other.getMovementLastFrame().getLength();
                              float movementMod = 0.8F;
                              if (movementData == 0.5F) {
                                 movementMod = 1.0F;
                              } else if (movementData == 1.0F) {
                                 movementMod = 1.5F;
                              } else if (movementData == 1.5F) {
                                 movementMod = 2.0F;
                              }

                              chance *= movementMod;
                              if (viewDist < 5.0F
                                 && (!otherCharacter.isRunning() && !otherCharacter.isSneaking() && !otherCharacter.isAiming() || otherCharacter.isRunning())) {
                                 chance *= 3.0F;
                              }

                              float sneakingMod = otherCharacter.getSneakSpotMod();
                              if (otherPlayer != null && !otherPlayer.isSneaking()) {
                                 sneakingMod = 1.0F;
                              }

                              chance *= sneakingMod;
                              if (this.spottedLast == other && this.timeSinceSeenFlesh < 120.0F) {
                              }

                              if (this.target != other && this.target != null) {
                                 float distOther = IsoUtils.DistanceManhatten(this.getX(), this.getY(), other.getX(), other.getY());
                                 float distCurrent = IsoUtils.DistanceManhatten(this.getX(), this.getY(), this.target.getX(), this.target.getY());
                                 if (distOther > distCurrent) {
                                    return;
                                 }
                              }

                              if (bForced) {
                                 chance = 1000000.0F;
                              }

                              if (this.bonusSpotTime > 0.0F) {
                                 chance *= 5.0F;
                              }

                              if (this.sight == 1) {
                                 chance *= 2.5F;
                              }

                              if (this.sight == 3) {
                                 chance *= 0.45F;
                              }

                              if (this.inactive) {
                                 chance *= 0.25F;
                              }

                              float traitMod = 1.0F;
                              if (otherPlayer != null && otherPlayer.hasTrait(CharacterTrait.INCONSPICUOUS)) {
                                 traitMod = 0.8F;
                              }

                              if (otherPlayer != null && otherPlayer.hasTrait(CharacterTrait.CONSPICUOUS)) {
                                 traitMod = 1.2F;
                              }

                              chance *= traitMod;
                              float shelterMod = 1.0F;
                              if (this.getCurrentSquare() != other.getCurrentSquare() && otherPlayer != null && otherPlayer.isSneaking()) {
                                 int xDiff = Math.abs(this.getCurrentSquare().getX() - other.getCurrentSquare().getX());
                                 int yDiff = Math.abs(this.getCurrentSquare().getY() - other.getCurrentSquare().getY());
                                 if (xDiff > yDiff) {
                                    if (this.getCurrentSquare().getX() - other.getCurrentSquare().getX() > 0) {
                                       shelterMod = this.getObstacleMod(other.getCurrentSquare(), IsoDirections.E);
                                    } else {
                                       shelterMod = this.getObstacleMod(other.getCurrentSquare(), IsoDirections.W);
                                    }
                                 } else if (this.getCurrentSquare().getY() - other.getCurrentSquare().getY() > 0) {
                                    shelterMod = this.getObstacleMod(other.getCurrentSquare(), IsoDirections.S);
                                 } else {
                                    shelterMod = this.getObstacleMod(other.getCurrentSquare(), IsoDirections.N);
                                 }
                              }

                              chance *= shelterMod;
                              boolean intersectVision = this.isVehicleBetween(otherCharacter.getX(), otherCharacter.getY(), otherCharacter.getZ());
                              thisToOther.x = other.getX();
                              thisToOther.y = other.getY();
                              thisToOther.x = thisToOther.x - this.getX();
                              thisToOther.y = thisToOther.y - this.getY();
                              float carObstacleMod = intersectVision && otherCharacter.getVehicle() == null
                                 ? (thisToOther.getLength() < 1.5F ? 0.5F : 0.0F)
                                 : 1.0F;
                              chance *= carObstacleMod;
                              chance /= this.getWornItemsVisionModifier();
                              if (this.getEatBodyTarget() != null && chance > 0.0F) {
                                 chance *= 0.5F;
                              }

                              chance = PZMath.fastfloor(chance);
                              chance = Math.min(chance, 400.0F);
                              chance /= 400.0F;
                              chance = Math.max(0.0F, chance);
                              chance = Math.min(1.0F, chance);
                              float mp = GameTime.instance.getMultiplier();
                              chance = (float)(1.0 - Math.pow(1.0F - chance, mp));
                              chance *= 100.0F;
                              if (Rand.Next(10000) / 100.0F < chance) {
                                 success = true;
                              }
                           }

                           if (!GameClient.client && !GameServer.server || NetworkZombieManager.canSpotted(this) || other == this.target) {
                              if (!success) {
                                 if (chance > 20.0F && otherPlayer != null && viewDist < 15.0F) {
                                    otherPlayer.couldBeSeenThisFrame = true;
                                 }

                                 boolean canAddXPForPerk = otherPlayer != null
                                    && !otherPlayer.isbCouldBeSeenThisFrame()
                                    && !otherPlayer.isbSeenThisFrame()
                                    && otherPlayer.isSneaking()
                                    && otherPlayer.isJustMoved();
                                 float randomMultiplier = 1100.0F;
                                 if (GameServer.debug) {
                                    randomMultiplier = 100.0F;
                                 }

                                 if (canAddXPForPerk && Rand.Next((int)(randomMultiplier * GameTime.instance.getInvMultiplier())) == 0.0F) {
                                    if (GameServer.server) {
                                       GameServer.addXp((IsoPlayer)other, Perks.Sneak, 1.0F);
                                    } else if (!GameClient.client) {
                                       otherPlayer.getXp().AddXP(Perks.Sneak, 1.0F);
                                    }
                                 }

                                 if (canAddXPForPerk && Rand.Next((int)(randomMultiplier * GameTime.instance.getInvMultiplier())) == 0.0F) {
                                    if (GameServer.server) {
                                       GameServer.addXp((IsoPlayer)other, Perks.Lightfoot, 1.0F);
                                    } else if (!GameClient.client) {
                                       otherPlayer.getXp().AddXP(Perks.Lightfoot, 1.0F);
                                    }
                                 }
                              } else {
                                 if (otherPlayer != null) {
                                    otherPlayer.setbSeenThisFrame(true);
                                 }

                                 if (!bForced) {
                                    this.bonusSpotTime = 720.0F;
                                 }

                                 this.lastTargetSeenX = PZMath.fastfloor(other.getX());
                                 this.lastTargetSeenY = PZMath.fastfloor(other.getY());
                                 this.lastTargetSeenZ = PZMath.fastfloor(other.getZ());
                                 if (this.getStateMachine().getCurrent() != StaggerBackState.instance()) {
                                    if (this.target != other) {
                                       this.targetSeenTime = 0.0F;
                                       if (GameServer.server && !this.isRemoteZombie()) {
                                          this.addAggro(other, 1.0F);
                                       }
                                    }

                                    this.setTarget(other);
                                    this.vectorToTarget.x = other.getX();
                                    this.vectorToTarget.y = other.getY();
                                    this.vectorToTarget.x = this.vectorToTarget.x - this.getX();
                                    this.vectorToTarget.y = this.vectorToTarget.y - this.getY();
                                    float vecToTargetLength = this.vectorToTarget.getLength();
                                    if (!bForced) {
                                       this.timeSinceSeenFlesh = 0.0F;
                                       this.targetSeenTime = this.targetSeenTime + GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
                                       this.canSeeTarget = this.isTargetVisible();
                                    }

                                    if (this.target != this.spottedLast || this.getCurrentState() != LungeState.instance() || !(this.lungeTimer > 0.0F)) {
                                       if (this.target != this.spottedLast || this.getCurrentState() != AttackVehicleState.instance()) {
                                          if (PZMath.fastfloor(this.getZ()) == PZMath.fastfloor(this.target.getZ())
                                             && (
                                                vecToTargetLength <= 3.5F
                                                   || this.target instanceof IsoGameCharacter gameCharacter
                                                      && gameCharacter.getVehicle() != null
                                                      && vecToTargetLength <= 4.0F
                                             )
                                             && this.getStateEventDelayTimer() <= 0.0F
                                             && !PolygonalMap2.instance
                                                .lineClearCollide(this.getX(), this.getY(), other.getX(), other.getY(), PZMath.fastfloor(this.getZ()), other)) {
                                             this.setTarget(other);
                                             if (this.getCurrentState() == LungeState.instance()) {
                                                return;
                                             }
                                          }

                                          this.spottedLast = other;
                                          if (!this.ghost && !this.getCurrentSquare().getProperties().has(IsoFlagType.smoke)) {
                                             this.setTarget(other);
                                             if (this.allowRepathDelay > 0.0F) {
                                                return;
                                             }

                                             if (this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.getVehicle() != null) {
                                                if ((this.getCurrentState() == PathFindState.instance() || this.getCurrentState() == WalkTowardState.instance())
                                                   && this.getPathFindBehavior2().getTargetChar() == this.target) {
                                                   return;
                                                }

                                                if (this.getCurrentState() == AttackVehicleState.instance()) {
                                                   return;
                                                }

                                                BaseVehicle vehicle = isoGameCharacter.getVehicle();
                                                if (Math.abs(vehicle.getCurrentSpeedKmHour()) > 0.8F && this.DistToSquared(vehicle) <= 16.0F) {
                                                   return;
                                                }

                                                this.pathToCharacter(isoGameCharacter);
                                                this.allowRepathDelay = 10.0F;
                                                return;
                                             }

                                             this.pathToCharacter(otherCharacter);
                                             if (Rand.Next(5) == 0) {
                                                this.spotSoundDelay = 200;
                                             }

                                             this.allowRepathDelay = 480.0F;
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean isVehicleBetween(float targetX, float targetY, float targetZ) {
      boolean intersectVision = false;
      Vector3f start = BaseVehicle.allocVector3f();
      Vector3f end = BaseVehicle.allocVector3f();
      Vector3f intersect1 = BaseVehicle.allocVector3f();

      // pzopt: a bounding-circle test against the zombie->target segment before the exact box test. Stock transformed the
      // segment into every loaded vehicle's local space (three pooled vectors, two matrix multiplies) for every zombie
      // that could see the player, every frame: 6 % of the game thread in downtown Louisville (pzopt.VehicleCull).
      boolean pzoptCull = pzopt.Overrides.enabled() && pzopt.Config.VEHICLE_CULL;
      float pzoptZx = this.getX();
      float pzoptZy = this.getY();
      // pzopt: and since the player-LOS pass the walk covers only the vehicles near the target: every zombie asking in a
      // frame asks about the same player position, and spottedNew only asks within the view distance, so one per-frame
      // list of the vehicles whose circle reaches that disc (or this zombie's distance, whichever is larger) serves them all.
      java.util.Collection<BaseVehicle> pzoptVehicles = IsoWorld.instance.currentCell.getVehicles();
      if (pzoptCull) {
         float pzoptReach = Math.max(GameTime.getInstance().getViewDist(), IsoUtils.DistanceTo(pzoptZx, pzoptZy, targetX, targetY));
         pzoptVehicles = pzopt.VehicleCull.near(pzoptVehicles, targetX, targetY, pzoptReach, IsoWorld.instance.getFrameNo());
      }

      for (BaseVehicle vehicle : pzoptVehicles) {
         if (pzoptCull && !pzopt.VehicleCull.mayIntersect(vehicle, pzoptZx, pzoptZy, targetX, targetY)) {
            continue;
         }
         start.set(targetX, targetY, targetZ + 0.1F);
         end.set(this.getX(), this.getY(), this.getZ() + 0.1F);
         Vector3f intersect = vehicle.getIntersectPoint(start, end, intersect1);
         if (intersect != null) {
            intersectVision = true;
            break;
         }
      }

      BaseVehicle.releaseVector3f(start);
      BaseVehicle.releaseVector3f(end);
      BaseVehicle.releaseVector3f(intersect1);
      return intersectVision;
   }

   public void spottedOld(IsoMovingObject other, boolean bForced) {
      if (GameClient.client && this.isRemoteZombie()) {
         if (this.getTarget() != null) {
            this.vectorToTarget.x = this.getTarget().getX();
            this.vectorToTarget.y = this.getTarget().getY();
            this.vectorToTarget.x = this.vectorToTarget.x - this.getX();
            this.vectorToTarget.y = this.vectorToTarget.y - this.getY();
            if (this.isCurrentState(LungeNetworkState.instance())) {
               Vector2 temp = new Vector2();
               temp.x = this.vectorToTarget.x;
               temp.y = this.vectorToTarget.y;
               temp.normalize();
               this.setForwardDirection(temp);
            }
         }
      } else if (this.getCurrentSquare() != null) {
         if (other.getCurrentSquare() != null) {
            if (GameClient.client && !GameClient.connection.isReady()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.isReanimatedForGrappleOnly()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.isUseless()) {
               this.setTarget(null);
               this.spottedLast = null;
            } else if (this.getCurrentSquare().getProperties().has(IsoFlagType.smoke)) {
               this.setTarget(null);
               this.spottedLast = null;
            } else {
               IsoGameCharacter otherCharacter = (IsoGameCharacter)Type.tryCastTo(other, IsoGameCharacter.class);
               if (otherCharacter != null && !otherCharacter.isDead()) {
                  IsoPlayer otherPlayer = (IsoPlayer)Type.tryCastTo(other, IsoPlayer.class);
                  if (otherPlayer == null || !otherPlayer.isGhostMode()) {
                     if (!GameClient.client && !GameServer.server || otherPlayer == null || !otherPlayer.getNetworkCharacterAI().isDisconnected()) {
                        if (this.getCurrentSquare() == null) {
                           this.ensureOnTile();
                        }

                        if (other.getCurrentSquare() == null) {
                           other.ensureOnTile();
                        }

                        float chance = 200.0F;
                        int playerIndex = otherPlayer != null && !GameServer.server ? otherPlayer.playerIndex : 0;
                        float delta = (
                              other.getCurrentSquare().lighting[playerIndex].lightInfo().r
                                 + other.getCurrentSquare().lighting[playerIndex].lightInfo().g
                                 + other.getCurrentSquare().lighting[playerIndex].lightInfo().b
                           )
                           / 3.0F;
                        float delta2 = RenderSettings.getInstance().getAmbientForPlayer(playerIndex);
                        float mdelta = (
                              this.getCurrentSquare().lighting[playerIndex].lightInfo().r
                                 + this.getCurrentSquare().lighting[playerIndex].lightInfo().g
                                 + this.getCurrentSquare().lighting[playerIndex].lightInfo().b
                           )
                           / 3.0F;
                        mdelta = mdelta * mdelta * mdelta;
                        if (delta > 1.0F) {
                           delta = 1.0F;
                        }

                        if (delta < 0.0F) {
                           delta = 0.0F;
                        }

                        if (mdelta > 1.0F) {
                           mdelta = 1.0F;
                        }

                        if (mdelta < 0.0F) {
                           mdelta = 0.0F;
                        }

                        float difdelta = 1.0F - (delta - mdelta);
                        if (delta < 0.2F) {
                           delta = 0.2F;
                        }

                        if (delta2 < 0.2F) {
                           delta2 = 0.2F;
                        }

                        if (other.getCurrentSquare().getRoom() != this.getCurrentSquare().getRoom()) {
                           chance = 50.0F;
                           if (other.getCurrentSquare().getRoom() != null && this.getCurrentSquare().getRoom() == null
                              || other.getCurrentSquare().getRoom() == null && this.getCurrentSquare().getRoom() != null) {
                              chance = 20.0F;
                              if (!otherCharacter.isAiming() && !otherCharacter.isSneaking()) {
                                 if (other.getMovementLastFrame().getLength() <= 0.04F && delta < 0.4F) {
                                    chance = 10.0F;
                                 }
                              } else if (delta < 0.4F) {
                                 chance = 0.0F;
                              } else {
                                 chance = 10.0F;
                              }
                           }
                        }

                        Vector2 thisToOther = IsoGameCharacter.tempo;
                        thisToOther.x = other.getX();
                        thisToOther.y = other.getY();
                        thisToOther.x = thisToOther.x - this.getX();
                        thisToOther.y = thisToOther.y - this.getY();
                        if (other.getCurrentSquare().getZ() != this.current.getZ()) {
                           int dif = Math.abs(other.getCurrentSquare().getZ() - this.current.getZ()) * 5;
                           dif++;
                           chance /= dif;
                        }

                        float viewDist = GameTime.getInstance().getViewDist();
                        if (!(thisToOther.getLength() > viewDist)) {
                           if (GameServer.server) {
                              this.indoorZombie = false;
                           }

                           if (thisToOther.getLength() < viewDist) {
                              viewDist = thisToOther.getLength();
                           }

                           viewDist *= 1.1F;
                           if (viewDist > GameTime.getInstance().getViewDistMax()) {
                              viewDist = GameTime.getInstance().getViewDistMax();
                           }

                           thisToOther.normalize();
                           Vector2 thisForward = this.getLookVector(tempo2);
                           float cosAngle = thisForward.dot(thisToOther);
                           this.updateVisionRadius();
                           if (this.DistTo(other) > this.visionRadiusResult) {
                              chance -= 10000.0F;
                           }

                           if (viewDist > 0.5F) {
                              if (cosAngle < -0.4F) {
                                 chance = 0.0F;
                              } else if (cosAngle < -0.2F) {
                                 chance /= 8.0F;
                              } else if (cosAngle < -0.0F) {
                                 chance /= 4.0F;
                              } else if (cosAngle < 0.2F) {
                                 chance /= 2.0F;
                              } else if (cosAngle <= 0.4F) {
                                 chance *= 2.0F;
                              } else if (cosAngle <= 0.6F) {
                                 chance *= 8.0F;
                              } else if (cosAngle <= 0.8F) {
                                 chance *= 16.0F;
                              } else {
                                 chance *= 32.0F;
                              }
                           }

                           if (chance > 0.0F
                              && this.target instanceof IsoPlayer player
                              && !GameServer.server
                              && player.remoteId == -1
                              && this.current.isCanSee(player.playerIndex)) {
                              player.targetedByZombie = true;
                              player.lastTargeted = 0.0F;
                           }

                           chance *= difdelta;
                           int dif = PZMath.fastfloor(other.getZ()) - PZMath.fastfloor(this.getZ());
                           if (dif >= 1) {
                              chance /= dif * 3.0F;
                           }

                           float distAlpha = PZMath.clamp(viewDist / GameTime.getInstance().getViewDist(), 0.0F, 1.0F);
                           chance *= 1.0F - distAlpha;
                           chance *= 1.0F - distAlpha;
                           chance *= 1.0F - distAlpha;
                           float closeDelta = PZMath.clamp(viewDist / 10.0F, 0.0F, 1.0F);
                           chance *= 1.0F + (1.0F - closeDelta) * 10.0F;
                           float movement = other.getMovementLastFrame().getLength();
                           if (otherCharacter != null) {
                              if (otherCharacter.getTorchStrength() > 0.0F) {
                                 chance *= 3.0F;
                              }

                              if (movement < 0.01F) {
                                 chance *= 0.5F;
                              } else if (otherCharacter.isSneaking()) {
                                 chance *= 0.4F;
                              } else if (otherCharacter.isAiming()) {
                                 chance *= 0.75F;
                              } else if (movement < 0.06F) {
                                 chance *= 0.8F;
                              } else if (movement >= 0.06F) {
                                 chance *= 2.4F;
                              }

                              if (this.eatBodyTarget != null) {
                                 chance *= 0.6F;
                              }

                              if (viewDist < 5.0F
                                 && (!otherCharacter.isRunning() && !otherCharacter.isSneaking() && !otherCharacter.isAiming() || otherCharacter.isRunning())) {
                                 chance *= 3.0F;
                              }

                              if (this.spottedLast == other && this.timeSinceSeenFlesh < 120.0F) {
                                 chance = 1000.0F;
                              }

                              chance *= otherCharacter.getSneakSpotMod();
                              chance *= delta2;
                              if (this.target != other && this.target != null) {
                                 float distOther = IsoUtils.DistanceManhatten(this.getX(), this.getY(), other.getX(), other.getY());
                                 float distCurrent = IsoUtils.DistanceManhatten(this.getX(), this.getY(), this.target.getX(), this.target.getY());
                                 if (distOther > distCurrent) {
                                    return;
                                 }
                              }

                              chance *= 0.3F;
                              if (bForced) {
                                 chance = 1000000.0F;
                              }

                              if (this.bonusSpotTime > 0.0F) {
                                 chance = 1000000.0F;
                              }

                              chance *= 1.2F;
                              if (this.sight == 1) {
                                 chance *= 2.5F;
                              }

                              if (this.sight == 3) {
                                 chance *= 0.45F;
                              }

                              if (this.inactive) {
                                 chance *= 0.25F;
                              }

                              chance *= 0.25F;
                              if (otherPlayer != null && otherPlayer.hasTrait(CharacterTrait.INCONSPICUOUS)) {
                                 chance *= 0.5F;
                              }

                              if (otherPlayer != null && otherPlayer.hasTrait(CharacterTrait.CONSPICUOUS)) {
                                 chance *= 2.0F;
                              }

                              chance *= 1.6F;
                              if (this.getCurrentSquare() != other.getCurrentSquare() && otherPlayer != null && otherPlayer.isSneaking()) {
                                 IsoGridSquare solidSquareTest = null;
                                 int xDiff = Math.abs(this.getCurrentSquare().getX() - other.getCurrentSquare().getX());
                                 int yDiff = Math.abs(this.getCurrentSquare().getY() - other.getCurrentSquare().getY());
                                 IsoGridSquare squareTest;
                                 if (xDiff > yDiff) {
                                    if (this.getCurrentSquare().getX() - other.getCurrentSquare().getX() > 0) {
                                       squareTest = other.getCurrentSquare().getAdjacentSquare(IsoDirections.E);
                                    } else {
                                       squareTest = other.getCurrentSquare();
                                       solidSquareTest = other.getCurrentSquare().getAdjacentSquare(IsoDirections.W);
                                    }
                                 } else if (this.getCurrentSquare().getY() - other.getCurrentSquare().getY() > 0) {
                                    squareTest = other.getCurrentSquare().getAdjacentSquare(IsoDirections.S);
                                 } else {
                                    squareTest = other.getCurrentSquare();
                                    solidSquareTest = other.getCurrentSquare().getAdjacentSquare(IsoDirections.N);
                                 }

                                 if (squareTest != null && otherCharacter != null) {
                                    float sneakTileBonus = otherCharacter.checkIsNearWall();
                                    if (sneakTileBonus == 1.0F && solidSquareTest != null) {
                                       sneakTileBonus = solidSquareTest.getGridSneakModifier(true);
                                    }

                                    if (sneakTileBonus > 1.0F) {
                                       float distToTile = other.DistTo(squareTest.x, squareTest.y);
                                       if (distToTile > 1.0F) {
                                          sneakTileBonus /= distToTile;
                                       }

                                       chance /= sneakTileBonus;
                                    }
                                 }
                              }

                              chance /= this.getWornItemsVisionModifier();
                              if (this.getEatBodyTarget() != null && chance > 0.0F) {
                                 chance *= 0.5F;
                              }

                              chance = PZMath.fastfloor(chance);
                              boolean success = false;
                              chance = Math.min(chance, 400.0F);
                              chance /= 400.0F;
                              chance = Math.max(0.0F, chance);
                              chance = Math.min(1.0F, chance);
                              float mp = GameTime.instance.getMultiplier();
                              chance = (float)(1.0 - Math.pow(1.0F - chance, mp));
                              chance *= 100.0F;
                              if (Rand.Next(10000) / 100.0F < chance) {
                                 success = true;
                              }

                              if (!GameClient.client && !GameServer.server || NetworkZombieManager.canSpotted(this) || other == this.target) {
                                 if (!success) {
                                    if (chance > 20.0F && otherPlayer != null && viewDist < 15.0F) {
                                       otherPlayer.couldBeSeenThisFrame = true;
                                    }

                                    boolean canAddXPForPerk = otherPlayer != null
                                       && !otherPlayer.isbCouldBeSeenThisFrame()
                                       && !otherPlayer.isbSeenThisFrame()
                                       && otherPlayer.isSneaking()
                                       && otherPlayer.isJustMoved();
                                    float randomMultiplier = 1100.0F;
                                    if (GameServer.debug) {
                                       randomMultiplier = 100.0F;
                                    }

                                    if (canAddXPForPerk && Rand.Next((int)(randomMultiplier * GameTime.instance.getInvMultiplier())) == 0.0F) {
                                       if (GameServer.server) {
                                          GameServer.addXp((IsoPlayer)other, Perks.Sneak, 1.0F);
                                       } else if (!GameClient.client) {
                                          otherPlayer.getXp().AddXP(Perks.Sneak, 1.0F);
                                       }
                                    }

                                    if (canAddXPForPerk && Rand.Next((int)(randomMultiplier * GameTime.instance.getInvMultiplier())) == 0.0F) {
                                       if (GameServer.server) {
                                          GameServer.addXp((IsoPlayer)other, Perks.Lightfoot, 1.0F);
                                       } else if (!GameClient.client) {
                                          otherPlayer.getXp().AddXP(Perks.Lightfoot, 1.0F);
                                       }
                                    }
                                 } else {
                                    if (otherPlayer != null) {
                                       otherPlayer.setbSeenThisFrame(true);
                                    }

                                    if (!bForced) {
                                       this.bonusSpotTime = 120.0F;
                                    }

                                    this.lastTargetSeenX = PZMath.fastfloor(other.getX());
                                    this.lastTargetSeenY = PZMath.fastfloor(other.getY());
                                    this.lastTargetSeenZ = PZMath.fastfloor(other.getZ());
                                    if (this.getStateMachine().getCurrent() != StaggerBackState.instance()) {
                                       if (this.target != other) {
                                          this.targetSeenTime = 0.0F;
                                          if (GameServer.server && !this.isRemoteZombie()) {
                                             this.addAggro(other, 1.0F);
                                          }
                                       }

                                       this.setTarget(other);
                                       this.vectorToTarget.x = other.getX();
                                       this.vectorToTarget.y = other.getY();
                                       this.vectorToTarget.x = this.vectorToTarget.x - this.getX();
                                       this.vectorToTarget.y = this.vectorToTarget.y - this.getY();
                                       float vecToTargetLength = this.vectorToTarget.getLength();
                                       if (!bForced) {
                                          this.timeSinceSeenFlesh = 0.0F;
                                          this.targetSeenTime = this.targetSeenTime + GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
                                       }

                                       if (this.target != this.spottedLast || this.getCurrentState() != LungeState.instance() || !(this.lungeTimer > 0.0F)) {
                                          if (this.target != this.spottedLast || this.getCurrentState() != AttackVehicleState.instance()) {
                                             if (PZMath.fastfloor(this.getZ()) == PZMath.fastfloor(this.target.getZ())
                                                && (
                                                   vecToTargetLength <= 3.5F
                                                      || this.target instanceof IsoGameCharacter gameCharacter
                                                         && gameCharacter.getVehicle() != null
                                                         && vecToTargetLength <= 4.0F
                                                )
                                                && this.getStateEventDelayTimer() <= 0.0F
                                                && !PolygonalMap2.instance
                                                   .lineClearCollide(this.getX(), this.getY(), other.getX(), other.getY(), PZMath.fastfloor(this.getZ()), other)
                                                )
                                              {
                                                this.setTarget(other);
                                                if (this.getCurrentState() == LungeState.instance()) {
                                                   return;
                                                }
                                             }

                                             this.spottedLast = other;
                                             if (!this.ghost && !this.getCurrentSquare().getProperties().has(IsoFlagType.smoke)) {
                                                this.setTarget(other);
                                                if (this.allowRepathDelay > 0.0F) {
                                                   return;
                                                }

                                                if (this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.getVehicle() != null) {
                                                   if ((
                                                         this.getCurrentState() == PathFindState.instance()
                                                            || this.getCurrentState() == WalkTowardState.instance()
                                                      )
                                                      && this.getPathFindBehavior2().getTargetChar() == this.target) {
                                                      return;
                                                   }

                                                   if (this.getCurrentState() == AttackVehicleState.instance()) {
                                                      return;
                                                   }

                                                   BaseVehicle vehicle = isoGameCharacter.getVehicle();
                                                   if (Math.abs(vehicle.getCurrentSpeedKmHour()) > 0.8F && this.DistToSquared(vehicle) <= 16.0F) {
                                                      return;
                                                   }

                                                   this.pathToCharacter(isoGameCharacter);
                                                   this.allowRepathDelay = 10.0F;
                                                   return;
                                                }

                                                this.pathToCharacter(otherCharacter);
                                                if (Rand.Next(5) == 0) {
                                                   this.spotSoundDelay = 200;
                                                }

                                                this.allowRepathDelay = 480.0F;
                                             }
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void spotted(IsoMovingObject other, boolean bForced) {
      if (SandboxOptions.instance.lore.spottedLogic.getValue()) {
         this.spottedNew(other, bForced);
      } else {
         this.spottedOld(other, bForced);
      }
   }

   public void moveUnmodded(float dirX, float dirY) {
      if (this.speedType == 1
         && (
            this.isCurrentState(LungeState.instance())
               || this.isCurrentState(LungeNetworkState.instance())
               || this.isCurrentState(AttackState.instance())
               || this.isCurrentState(AttackNetworkState.instance())
               || this.isCurrentState(StaggerBackState.instance())
               || this.isCurrentState(ZombieHitReactionState.instance())
         )
         && this.target instanceof IsoGameCharacter) {
         float dx = this.target.getNextX() - this.getX();
         float dy = this.target.getNextY() - this.getY();
         float dist = (float)Math.sqrt(dx * dx + dy * dy);
         dist -= this.getWidth() + this.target.getWidth() - 0.1F;
         dist = Math.max(0.0F, dist);
         float dirLength = PZMath.getLength(dirX, dirY);
         if (dirLength > dist) {
            dirX = dirX * dist / dirLength;
            dirY = dirY * dist / dirLength;
         }
      }

      if (GameClient.client && this.isRemoteZombie()) {
         NetworkZombieAI networkAi = this.getNetworkCharacterAI();
         float dist = IsoUtils.DistanceTo(this.realx, this.realy, networkAi.targetX, networkAi.targetY);
         float movingScale = Math.abs(dist) < 0.01F
            ? 1.0F
            : 0.5F + IsoUtils.smoothstep(0.5F, 1.5F, IsoUtils.DistanceTo(this.getX(), this.getY(), networkAi.targetX, networkAi.targetY) / dist);
         dirX *= movingScale;
         dirY *= movingScale;
         if (networkAi.wasSeparated) {
            Vector2 diff = new Vector2(this.getNextX() - this.realx, this.getNextY() - this.realy);
            diff.setLength(diff.getLength() / 16.0F);
            this.setNextX(this.getNextX() - diff.x);
            this.setNextY(this.getNextY() - diff.y);
         }
      }

      super.moveUnmodded(dirX, dirY);
   }

   public void DoFootstepSound(String type) {
      MovementType movementType = MovementType.Walk;
      float speed = 0.5F;
      switch (type) {
         case "sneak_walk":
            speed = 0.25F;
            movementType = MovementType.SneakWalk;
            break;
         case "sneak_run":
            speed = 0.25F;
            movementType = MovementType.SneakRun;
            break;
         case "strafe":
            speed = 0.5F;
            movementType = MovementType.Strafe;
            break;
         case "walk":
            speed = 0.5F;
            movementType = MovementType.Walk;
            break;
         case "run":
            speed = 0.75F;
            movementType = MovementType.Run;
            break;
         case "sprint":
            speed = 1.0F;
            movementType = MovementType.Sprint;
      }

      this.addFootstepParametersIfNeeded();
      this.parameterCharacterMovementSpeed.setMovementType(movementType);
      this.DoFootstepSound(speed);
   }

   public void addFootstepParametersIfNeeded() {
      if (!GameServer.server && !this.getFMODParameters().parameterList.contains(this.parameterCharacterMovementSpeed)) {
         this.getFMODParameters().add(this.parameterCharacterMovementSpeed);
         this.getFMODParameters().add(this.parameterFootstepMaterial2);
         this.getFMODParameters().add(this.parameterShoeType);
      }
   }

   public void DoFootstepSound(float volume) {
      if (!GameServer.server) {
         if (!(volume <= 0.0F)) {
            if (this.getCurrentSquare() != null) {
               if (GameClient.client && this.isRemoteZombie()) {
                  if (this.def != null
                     && this.sprite != null
                     && this.sprite.currentAnim != null
                     && (this.sprite.currentAnim.name.contains("Run") || this.sprite.currentAnim.name.contains("Walk"))) {
                     int frame = (int)this.def.frame;
                     boolean bFootDown;
                     if (frame >= 0 && frame < 5) {
                        bFootDown = this.stepFrameLast < 0 || this.stepFrameLast > 5;
                     } else {
                        bFootDown = this.stepFrameLast < 5;
                     }

                     if (bFootDown) {
                        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
                           IsoPlayer player = IsoPlayer.players[i];
                           if (player != null && player.DistToSquared(this) < 225.0F) {
                              ZombieFootstepManager.instance.addCharacter(this);
                              break;
                           }
                        }
                     }

                     this.stepFrameLast = frame;
                  } else {
                     this.stepFrameLast = -1;
                  }
               } else {
                  boolean listener = SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 15.0F);
                  if (listener) {
                     this.footstepVolume = volume;
                     ZombieFootstepManager.instance.addCharacter(this);
                  }
               }
            }
         }
      }
   }

   public void preupdate() {
      if (GameServer.server && this.thumpSent) {
         this.thumpFlag = 0;
         this.thumpSent = false;
      }

      this.followCount = 0;
      if (GameClient.client) {
         NetworkZombieAI networkAi = this.getNetworkCharacterAI();
         if (networkAi.isVehicleHitTimeout()) {
            networkAi.hitByVehicle();
         }

         if (networkAi.isDeadBodyTimeout()) {
            networkAi.onDied();
         }

         if (!this.isLocal()) {
            networkAi.preupdate();
         } else if (this.isKnockedDown() && !this.isOnFloor()) {
            HitReactionNetworkAI hitReaction = this.getHitReactionNetworkAI();
            if (hitReaction.isSetup() && !hitReaction.isStarted()) {
               hitReaction.start();
            }
         }
      }

      if (this.isAnimationRecorderActive()) {
         this.getAnimationRecorder().logVariable("sim.isRemoteZombie", this.isRemoteZombie());
      }

      super.preupdate();
   }

   public boolean allowsInvisibleAnimationSkips() {
      if (GameClient.client && this.isRemoteZombie()) {
         return false;
      } else if (pzopt.UpdateBatch.pom(GameTime.getInstance()) > 1.0F) { // pzopt: entityUpdateParallel -- a batch task reads its bucket's dispatch-time multiplier through pom() (the game thread may have moved on to the next bucket's global write); the live field everywhere else
         return false;
      } else {
         return !DebugOptions.instance.zombieAnimationDelay.getValue() ? false : super.allowsInvisibleAnimationSkips();
      }
   }

   public void postupdate() {
      AbstractPerformanceProfileProbe var1 = IsoZombie.s_performance.postUpdate.profile();

      try {
         this.postUpdateInternal();
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

   private void postUpdateInternal() {
      if (this.target instanceof IsoPlayer isoPlayer) {
         isoPlayer.getStats().numChasingZombies++;
      }

      super.postupdate();
      if (this.unbalancedLevel > 0.0F) {
         float rateOfRegainBalance = 0.5F;
         this.unbalancedLevel = PZMath.clamp(
            PZMath.lerp(this.unbalancedLevel, 0.0F, 0.5F * GameTime.getInstance().getMultiplier(), LerpType.EaseOutQuad), 0.0F, 1.0F
         );
      }

      if (this.isReanimatedForGrappleOnly() && this.isBeingGrappled() && this.getGrappledBy().isMoving() && this.bloodSplatAmount > 0) {
         int corpseBloodSplatterAmount = 100;
         float invBleedChance = 2.0F * GameTime.instance.getInvMultiplier();
         if (Rand.Next((int)invBleedChance) < invBleedChance * 0.3F) {
            DebugType.Zombie.trace("Dragged corpse. Bleeding on the floor 1.");
            this.splatBloodFloor();
            this.bloodSplatAmount -= 2;
         }

         if (Rand.Next((int)invBleedChance) == 0) {
            DebugType.Zombie.trace("Dragged corpse. Bleeding on the floor 2.");
            this.splatBloodFloor();
            this.bloodSplatAmount -= 2;
         }

         this.getModData().rawset("_bloodSplatAmount", BoxedStaticValues.toDouble(this.bloodSplatAmount));
      }

      if (this.current == null && (!GameClient.client || !this.isRemoteZombie())) {
         this.removeFromWorld();
         this.removeFromSquare();
      }

      if (!GameServer.server) {
         IsoPlayer player = this.getReanimatedPlayer();
         if (player != null) {
            player.setX(this.getX());
            player.setY(this.getY());
            player.setZ(this.getZ());
            player.setDir(this.getDir());
            player.setForwardDirection(this.getForwardDirection());
            AnimationPlayer animPlr1 = this.getAnimationPlayer();
            AnimationPlayer animPlr2 = player.getAnimationPlayer();
            if (animPlr1 != null && animPlr1.isReady() && animPlr2 != null && animPlr2.isReady()) {
               animPlr2.setTargetAngle(animPlr1.getAngle());
               animPlr2.setAngleToTarget();
            }

            player.setCurrentSquareFromPosition();
            player.updateLightInfo();
            if (player.soundListener != null) {
               player.soundListener.setPos(player.getX(), player.getY(), player.getZ());
               player.soundListener.tick();
            }

            IsoPlayer oldinst = IsoPlayer.getInstance();
            IsoPlayer.setInstance(player);
            player.updateLOS();
            IsoPlayer.setInstance(oldinst);
            if (GameClient.client && this.isRemoteZombie() && this.networkUpdate.Check()) {
               GameClient.instance.sendPlayer(player);
            }

            player.dirtyRecalcGridStackTime = 2.0F;
         }
      }

      this.canSeeTarget = this.isTargetVisible();
      if (this.targetSeenTime > 0.0F && !this.canSeeTarget) {
         this.targetSeenTime = 0.0F;
      }
   }

   protected void handleLandingImpact(FallDamage fallDamage) {
      boolean isMoreThanLightFall = fallDamage.isMoreThanLightFall();
      boolean isDamagingFall = fallDamage.isDamagingFall();
      boolean isMoreThanHardFall = fallDamage.isMoreThanHardFall();
      if (isDamagingFall && !this.isClimbing()) {
         this.hitDir.x = this.hitDir.y = 0.0F;
         this.playHurtSound();
         float damageAlpha = fallDamage.getImpactIsoSpeed() / FallingConstants.zombieLethalFallThreshold;
         float damageAdjust = damageAlpha * Rand.Next(-0.25F, 0.25F);
         float impactDamage = PZMath.lerpFunc_EaseOutQuad(1.05F * damageAlpha);
         float damage = (impactDamage + damageAdjust) * (float)SandboxOptions.instance.lore.zombiesFallDamage.getValue();
         CombatManager.getInstance().applyDamage(this, damage);
         this.setAttackedBy(null);
         this.helmetFall(isMoreThanLightFall);
         if (isMoreThanLightFall) {
            if (isMoreThanHardFall && Rand.Next(0.0F, 1.0F) < damage) {
               if (this.isAlive()) {
                  this.setBecomeCrawler(true);
                  this.setCanWalk(false);
                  this.setCrawlerType(1);
               }

               this.setFallOnFront(true);
               this.playSound("FirstAidFracture");
               this.splatBloodFloorBig();
            }

            if (this.isAlive() && !this.isBecomeCrawler() && Rand.Next(0.0F, 1.0F) > 0.5F) {
               this.setFakeDead(true);
               this.setFallOnFront(true);
            }

            this.splatBloodFloorBig();
            this.playSound("LandHeavy");
         } else {
            this.playSound("LandLight");
         }

         DebugType.General.debugln("Damage incurred by fall: %f. isAlive: %s", new Object[]{damage, !this.isDead()});
      }
   }

   public boolean isSolidForSeparate() {
      if (this.getCurrentState() != FakeDeadZombieState.instance()
         && this.getCurrentState() != ZombieFallDownState.instance()
         && this.getCurrentState() != ZombieOnGroundState.instance()
         && this.getCurrentState() != ZombieGetUpState.instance()
         && (this.getCurrentState() != ZombieHitReactionState.instance() || this.speedType == 1)) {
         if (this.isSitAgainstWall()) {
            return false;
         } else {
            return this.isRagdoll() ? false : super.isSolidForSeparate();
         }
      } else {
         return false;
      }
   }

   public boolean isPushableForSeparate() {
      if (this.getCurrentState() == ThumpState.instance()
         || this.getCurrentState() == AttackState.instance()
         || this.getCurrentState() == AttackVehicleState.instance()
         || this.getCurrentState() == ZombieEatBodyState.instance()
         || this.getCurrentState() == ZombieFaceTargetState.instance()) {
         return false;
      } else {
         return this.isSitAgainstWall() ? false : super.isPushableForSeparate();
      }
   }

   public boolean isPushedByForSeparate(IsoMovingObject other) {
      if (other instanceof IsoZombie isoZombie && isoZombie.getCurrentState() == ZombieHitReactionState.instance() && !isoZombie.collideWhileHit) {
         return false;
      } else if (this.getCurrentState() == ZombieHitReactionState.instance() && !this.collideWhileHit) {
         return false;
      } else {
         return GameClient.client && other instanceof IsoZombie && !NetworkZombieSimulator.getInstance().isZombieSimulated(this.getOnlineID())
            ? false
            : super.isPushedByForSeparate(other);
      }
   }

   public void update() {
      pzopt.GtAb.zombieUpdates++; // pzopt: devGtAlternate, zombie updates per frame in pzopt-gtab.out
      AbstractPerformanceProfileProbe var1 = IsoZombie.s_performance.update.profile();

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

   private void updateActiveState() {
      this.makeInactive(GameTime.getInstance().isZombieInactivityPhase());
   }

   private void updateInternal() {
      if (Core.debug && this.getOutfitName() != null && this.getOutfitName().contains("Debug")) {
         String outfitName = this.getOutfitName();
         if (outfitName.contains("Immortal")) {
            this.setImmortalTutorialZombie(true);
         }

         if (outfitName.contains("Useless")) {
            this.setUseless(true);
         }
      }

      this.updateActiveState();
      if (this.persistentId != 0 && this.isDead()) {
         ZombiePopulationManager.releaseZombie(this);
         this.persistentId = 0;
      }

      if (GameServer.server || GameClient.client && !this.isRemoteZombie()) {
         GameStatistic.getInstance().zombiesUpdated.increase();
      }

      if (this.crawling) {
         if (this.getActionContext().getGroup() != pzoptGroup(true)) {
            this.getAdvancedAnimator().OnAnimDataChanged(false);
            this.initializeStates();
            this.getActionContext().setGroup(pzoptGroup(true));
         }
      } else if (this.getActionContext().getGroup() != pzoptGroup(false)) {
         this.getAdvancedAnimator().OnAnimDataChanged(false);
         this.initializeStates();
         this.getActionContext().setGroup(pzoptGroup(false));
      }

      if (this.getThumpTimer() > 0) {
         this.thumpTimer--;
      }

      BaseVehicle nearVehicle = this.getNearVehicle();
      if (nearVehicle != null) {
         if (this.target == null && nearVehicle.isSirening()) {
            VehiclePart part = nearVehicle.getUseablePart(this, false);
            if (part != null && part.getSquare().DistTo(this) < 0.7F) {
               this.setThumpTarget(nearVehicle);
            }
         }

         if (nearVehicle.isAlarmed() && !nearVehicle.isPreviouslyEntered() && Rand.Next(10000) < 1) {
            nearVehicle.triggerAlarm();
         }
      }

      this.doDeferredMovement();
      this.updateEmitter();
      if (this.spotSoundDelay > 0) {
         this.spotSoundDelay--;
      }

      if (GameClient.client && this.isRemoteZombie()) {
         if (this.lastRemoteUpdate > 800
            && this.legsSprite.hasAnimation()
            && (
               this.legsSprite.currentAnim.name.equals("ZombieDeath")
                  || this.legsSprite.currentAnim.name.equals("ZombieStaggerBack")
                  || this.legsSprite.currentAnim.name.equals("ZombieGetUp")
            )) {
            DebugLog.log(DebugType.Zombie, "removing stale zombie 800 id=" + this.onlineId);
            VirtualZombieManager.instance.removeZombieFromWorld(this);
            return;
         }

         if (GameClient.fastForward) {
            VirtualZombieManager.instance.removeZombieFromWorld(this);
            return;
         }
      }

      if (GameClient.client && this.isRemoteZombie() && this.lastRemoteUpdate < 2000 && this.lastRemoteUpdate + 1000 / PerformanceSettings.getLockFPS() > 2000) {
         DebugLog.log(DebugType.Zombie, "lastRemoteUpdate 2000+ id=" + this.onlineId);
      }

      this.lastRemoteUpdate = (short)(this.lastRemoteUpdate + (short)(1000 / PerformanceSettings.getLockFPS()));
      if (!GameClient.client || !this.isRemoteZombie() || this.remote && this.lastRemoteUpdate <= 5000) {
         this.sprite = this.legsSprite;
         if (this.sprite != null) {
            this.updateCharacterTextureAnimTime();
            if (GameServer.server && this.indoorZombie) {
               super.update();
            } else {
               this.bonusSpotTime = PZMath.clamp(this.bonusSpotTime - GameTime.instance.getMultiplier(), 0.0F, Float.MAX_VALUE);
               this.timeSinceSeenFlesh = PZMath.clamp(this.timeSinceSeenFlesh + GameTime.instance.getMultiplier(), 0.0F, Float.MAX_VALUE);
               this.checkZombieEntersPlayerBuilding();
               this.updateMovementStatistics();
               this.setCollidable(true);
               LuaEventManager.triggerEvent("OnZombieUpdate", this);
               if (Core.lastStand
                  && this.getStateMachine().getCurrent() != ThumpState.instance()
                  && this.getStateMachine().getCurrent() != AttackState.instance()
                  && this.timeSinceSeenFlesh > 120.0F
                  && Rand.Next(36000) == 0) {
                  IsoPlayer lowest = null;
                  float lowestDst = 1000000.0F;

                  for (int n = 0; n < IsoPlayer.numPlayers; n++) {
                     if (IsoPlayer.players[n] != null && IsoPlayer.players[n].DistTo(this) < lowestDst && !IsoPlayer.players[n].isDead()) {
                        lowestDst = IsoPlayer.players[n].DistTo(this);
                        lowest = IsoPlayer.players[n];
                     }
                  }

                  if (lowest != null) {
                     this.allowRepathDelay = -1.0F;
                     this.pathToCharacter(lowest);
                  }
               } else {
                  // pzopt: physicsDefer. The second Bullet escape from a batched entity's update, and the same
                  // hazard as the hitbox one: vehicleCollision reaches setRagdollBodyDynamics /
                  // resetRagdollBodyDynamics with a STATIC parameter array and runs BaseVehicle.testTouchingVehicle
                  // against a vehicle the game thread may be updating that instant. It needs a ragdolling zombie in
                  // contact with a car, which is why it had not crashed yet. On a batch task the call is queued with
                  // this vehicle (the field is nulled a few lines down, so the reference has to travel) and the game
                  // thread makes it at the join, by when the vehicles have finished updating.
                  if (this.isRagdoll() && !pzopt.UpdateBatch.deferRagdollVehicle(this, this.vehicle4testCollision)) { // pzopt: physicsDefer
                     RagdollController ragdollController = this.getRagdollController();
                     ragdollController.vehicleCollision(this, this.vehicle4testCollision);
                  }

                  if (!this.isLocal()) {
                     this.setVehicleCollision(this.vehicle4testCollision != null && this.vehicle4testCollision.updateNetworkHitByVehicle(this));
                  } else if (this.vehicle4testCollision != null) {
                     HitVars hitVars = this.vehicle4testCollision.checkNetworkCollision(this);
                     this.setVehicleCollision(hitVars != null);
                     if (this.isVehicleCollision()) {
                        this.doNetworkHitByVehicle(this.vehicle4testCollision, hitVars);
                     }
                  }

                  this.vehicle4testCollision = null;
                  if (this.bonusSpotTime > 0.0F && this.spottedLast != null && !((IsoGameCharacter)this.spottedLast).isDead()) {
                     this.spotted(this.spottedLast, true);
                  }

                  if (GameServer.server && this.getStateMachine().getCurrent() == BurntToDeath.instance()) {
                     DebugLog.log(DebugType.Zombie, "Zombie is burning " + this.onlineId);
                  }

                  super.update();
                  if (VirtualZombieManager.instance.isReused(this)) {
                     DebugLog.log(DebugType.Zombie, "Zombie added to ReusableZombies after super.update - RETURNING " + this);
                  } else if (this.getStateMachine().getCurrent() != ClimbThroughWindowState.instance()
                     && this.getStateMachine().getCurrent() != ClimbOverFenceState.instance()
                     && this.getStateMachine().getCurrent() != CrawlingZombieTurnState.instance()) {
                     this.ensureOnTile();
                     State cur = this.getStateMachine().getCurrent();
                     if (cur != StaggerBackState.instance()
                        && cur != BurntToDeath.instance()
                        && cur != FakeDeadZombieState.instance()
                        && cur != ZombieFallDownState.instance()
                        && cur != ZombieOnGroundState.instance()
                        && cur != ZombieHitReactionState.instance()
                        && cur != ZombieGetUpState.instance()) {
                        if (GameServer.server && this.onlineId == -1) {
                           this.onlineId = ServerMap.instance.getUniqueZombieId();
                        } else if (cur == PathFindState.instance() && this.finder.progress == PathFindProgress.notyetfound) {
                           if (this.crawling) {
                              this.PlayAnim("ZombieCrawl");
                              this.def.animFrameIncrease = 0.0F;
                           } else {
                              this.PlayAnim("ZombieIdle");
                              this.def.animFrameIncrease = 0.08F + Rand.Next(1000) / 8000.0F;
                              this.def.animFrameIncrease *= 0.5F;
                           }
                        } else if (cur != AttackState.instance()
                           && cur != AttackVehicleState.instance()
                           && (this.getNextX() != this.getX() || this.getNextY() != this.getY())) {
                           if (this.walkVariantUse == null || cur != LungeState.instance() && cur != LungeNetworkState.instance()) {
                              this.walkVariantUse = this.walkVariant;
                           }

                           if (this.crawling) {
                              this.walkVariantUse = "ZombieCrawl";
                           }

                           if (cur != ZombieIdleState.instance()
                              && cur != StaggerBackState.instance()
                              && cur != ThumpState.instance()
                              && cur != FakeDeadZombieState.instance()) {
                              if (this.running) {
                                 this.PlayAnim("Run");
                                 this.def.setFrameSpeedPerFrame(0.33F);
                              } else {
                                 this.PlayAnim(this.walkVariantUse);
                                 this.def.setFrameSpeedPerFrame(0.26F);
                                 this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
                              }

                              this.setShootable(true);
                           }
                        }

                        this.shootable = true;
                        this.solid = true;
                        if (pzopt.Config.ZOMBIE_CHECK_SPREAD <= 1 || !pzopt.Overrides.enabled()
                           || (pzopt.FrameTick.frame() + this.getID()) % pzopt.Config.ZOMBIE_CHECK_SPREAD == 1) {
                           this.tryThump(null); // pzopt: zombieCheckSpread, the "is something thumpable in front of me"
                                                // grid probe spread over N frames (a thump starts at most N-1 frames late)
                        }
                        this.damageSheetRope();
                        this.allowRepathDelay = PZMath.clamp(this.allowRepathDelay - GameTime.instance.getMultiplier(), 0.0F, Float.MAX_VALUE);
                        if (this.timeSinceSeenFlesh > this.memory && this.target != null) {
                           this.setTarget(null);
                        }

                        if (this.target instanceof IsoGameCharacter isoGameCharacter && isoGameCharacter.reanimatedCorpse != null) {
                           this.setTarget(null);
                        }

                        if (this.target != null) {
                           this.vectorToTarget.x = this.target.getX();
                           this.vectorToTarget.y = this.target.getY();
                           this.vectorToTarget.x = this.vectorToTarget.x - this.getX();
                           this.vectorToTarget.y = this.vectorToTarget.y - this.getY();
                           this.updateZombieTripping();
                        }

                        if (this.getSquare() != null && this.getSquare().hasFarmingPlant()) {
                           int chance = (int)(100.0F / GameTime.getInstance().getTrueMultiplier());
                           chance = Math.max(1, chance);
                           if (Rand.NextBool(chance)) {
                              this.getSquare().destroyFarmingPlant();
                           }
                        }

                        if (this.getSquare() != null && this.getSquare().hasLitCampfire() && this.isMoving()) {
                           int chance = (int)(10000.0F / GameTime.getInstance().getTrueMultiplier());
                           chance = Math.max(1, chance);
                           if (Rand.NextBool(chance)) {
                              this.getSquare().putOutCampfire();
                           }
                        }

                        if (this.getCurrentState() != PathFindState.instance()
                           && this.getCurrentState() != WalkTowardState.instance()
                           && this.getCurrentState() != ClimbThroughWindowState.instance()) {
                           this.setLastHeardSound(-1, -1, -1);
                        }

                        if (this.timeSinceSeenFlesh > 240.0F && this.timeSinceRespondToSound > 5.0F) {
                           this.RespondToSound();
                        }

                        this.timeSinceRespondToSound = this.timeSinceRespondToSound + GameTime.getInstance().getThirtyFPSMultiplier();
                        NetworkZombieAI networkAi = this.getNetworkCharacterAI();
                        networkAi.wasSeparated = false;
                        this.separate();
                        this.updateSearchForCorpse();
                        if (this.timeSinceSeenFlesh > 2000.0F && this.timeSinceRespondToSound > 2000.0F) {
                           ZombieGroupManager.instance.update(this);
                        }
                     }
                  }
               }
            }
         }
      } else {
         DebugLog.log(DebugType.Zombie, "removing stale zombie 5000 id=" + this.onlineId);
         DebugLog.log("Zombie: removing stale zombie 5000 id=" + this.onlineId);
         VirtualZombieManager.instance.removeZombieFromWorld(this);
      }
   }

   /**
    * pzopt: physicsDefer, the game thread's entry to one deferred ragdoll-versus-vehicle contact test at the join
    * (the drain lives in pzopt.UpdateBatch, and the vehicle travels with the queue entry because updateInternal
    * nulls vehicle4testCollision before the join). The controller is re-read here rather than carried along: the
    * rest of this frame may have released the ragdoll, and then there is nothing to tell Bullet.
    */
   public void pzoptVehicleCollision(BaseVehicle vehicle) {
      RagdollController ragdollController = this.getRagdollController();
      if (ragdollController != null) {
         ragdollController.vehicleCollision(this, vehicle);
      }
   }

   protected void calculateStats() {
   }

   private void updateZombieTripping() {
      if (this.speedType == 1
         && StringUtils.isNullOrEmpty(this.getBumpType())
         && this.target != null
         && Rand.NextBool(Rand.AdjustForFramerate(750))
         && !this.isRemoteZombie()) {
         this.setBumpType("trippingFromSprint");
      }
   }

   public int getVoiceChoice() {
      if (this.speedType == -1) {
         return 1;
      }

      if (this.voiceChoice == -1) {
         this.voiceChoice = Rand.Next(this.speedType == 1 ? 2 : 3) + 1;
      }

      if (this.speedType == 1 && this.voiceChoice == 3) {
         this.voiceChoice = Rand.Next(2) + 1;
      }

      return this.voiceChoice;
   }

   public String getVoiceSoundName() {
      String part1 = this.getDescriptor().getVoicePrefix();
      String part2 = this.speedType == 1 ? "Sprinter" : "";
      String part3 = "Voice";

      String part4 = switch (this.getVoiceChoice()) {
         case 2 -> "B";
         case 3 -> "C";
         default -> "A";
      };
      return part1 + part2 + "Voice" + part4;
   }

   public String getBiteSoundName() {
      String part1 = this.getDescriptor().getVoicePrefix();
      String part2 = this.speedType == 1 ? "Sprinter" : "";
      String part3 = "Bite";

      String part4 = switch (this.getVoiceChoice()) {
         case 2 -> "B";
         case 3 -> "C";
         default -> "A";
      };
      return part1 + part2 + "Bite" + part4;
   }

   public void updateVocalProperties() {
      if (!GameServer.server) {
         boolean bListenerInRange = SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 70.0F);
         if (this.vocalEvent != 0L && !this.getEmitter().isPlaying(this.vocalEvent)) {
            this.vocalEvent = 0L;
         }

         boolean bHasFunctioningVoice = !this.isDead() && !this.isReanimatedForGrappleOnly();
         if (bHasFunctioningVoice && !this.isFakeDead() && bListenerInRange) {
            ZombieVocalsManager.instance.addCharacter(this);
         }

         if (this.vocalEvent != 0L && bHasFunctioningVoice && this.isFakeDead() && this.getEmitter().isPlaying(this.vocalEvent)) {
            this.getEmitter().stopSoundLocal(this.vocalEvent);
            this.vocalEvent = 0L;
         }
      }
   }

   public void setVehicleHitLocation(BaseVehicle vehicle) {
      if (!this.getFMODParameters().parameterList.contains(this.parameterVehicleHitLocation)) {
         this.getFMODParameters().add(this.parameterVehicleHitLocation);
      }

      HitLocation location = ParameterVehicleHitLocation.calculateLocation(vehicle, this.getX(), this.getY(), this.getZ());
      this.parameterVehicleHitLocation.setLocation(location);
   }

   private void updateSearchForCorpse() {
      if (!this.crawling && this.target == null && this.eatBodyTarget == null) {
         if (this.bodyToEat != null) {
            if (this.bodyToEat.getStaticMovingObjectIndex() == -1) {
               this.bodyToEat = null;
            } else if (!this.isEatingOther(this.bodyToEat) && this.bodyToEat.getEatingZombies().size() >= 3) {
               this.bodyToEat = null;
            }
         }

         if (this.bodyToEat == null) {
            this.checkForCorpseTimer = this.checkForCorpseTimer - GameTime.getInstance().getThirtyFPSMultiplier();
            if (this.checkForCorpseTimer <= 0.0F) {
               this.checkForCorpseTimer = 10000.0F;
               tempBodies.clear();

               for (int x = -10; x < 10; x++) {
                  for (int y = -10; y < 10; y++) {
                     IsoGridSquare sq = this.getCell().getGridSquare(this.getX() + x, this.getY() + y, this.getZ());
                     if (sq != null) {
                        IsoDeadBody corpse = sq.getDeadBody();
                        if (corpse != null
                           && !corpse.isSkeleton()
                           && !corpse.isZombie()
                           && corpse.getEatingZombies().size() < 3
                           && !corpse.isAnimal()
                           && !corpse.isAnimalSkeleton()
                           && !PolygonalMap2.instance
                              .lineClearCollide(this.getX(), this.getY(), corpse.getX(), corpse.getY(), PZMath.fastfloor(this.getZ()), null, false, true)) {
                           tempBodies.add(corpse);
                        }
                     }
                  }
               }

               if (!tempBodies.isEmpty()) {
                  this.bodyToEat = (IsoDeadBody)PZArrayUtil.pickRandom(tempBodies);
                  tempBodies.clear();
               }
            }
         }

         if (this.bodyToEat != null && this.isCurrentState(ZombieIdleState.instance())) {
            if (this.DistToSquared(this.bodyToEat) > 1.0F) {
               Vector2 v = tempo.set(this.getX() - this.bodyToEat.getX(), this.getY() - this.bodyToEat.getY());
               v.setLength(0.5F);
               this.pathToLocationF(this.bodyToEat.getX() + v.x, this.bodyToEat.getY() + v.y, this.bodyToEat.getZ());
            }
         }
      } else {
         this.checkForCorpseTimer = 10000.0F;
         this.bodyToEat = null;
      }
   }

   private void damageSheetRope() {
      if (Rand.Next(30) == 0
         && this.current != null
         && (
            this.current.has(IsoFlagType.climbSheetN)
               || this.current.has(IsoFlagType.climbSheetE)
               || this.current.has(IsoFlagType.climbSheetS)
               || this.current.has(IsoFlagType.climbSheetW)
         )) {
         IsoObject sheetRope = this.current.getSheetRope();
         if (sheetRope != null) {
            sheetRope.sheetRopeHealth = sheetRope.sheetRopeHealth - Rand.Next(5, 15);
            if (GameClient.client) {
               sheetRope.sync();
            } else {
               this.current.damageSpriteSheetRopeFromBottom();
            }
         }
      }
   }

   public void getZombieWalkTowardSpeed(float speed, float dist, Vector2 temp) {
      float distMod = dist / 24.0F;
      if (distMod < 1.0F) {
         distMod = 1.0F;
      }

      if (distMod > 1.3F) {
         distMod = 1.3F;
      }

      temp.setLength((speed * this.getSpeedMod() + 0.006F) * distMod);
      if (SandboxOptions.instance.lore.speed.getValue() == 1 && !this.inactive || this.speedType == 1) {
         temp.setLength(0.08F);
         this.running = true;
      }

      if (temp.getLength() > dist) {
         temp.setLength(dist);
      }
   }

   public void getZombieLungeSpeed() {
      this.running = SandboxOptions.instance.lore.speed.getValue() == 1 && !this.inactive || this.speedType == 1;
   }

   public boolean tryThump(IsoGridSquare square) {
      if (this.ghost) {
         return false;
      }

      if (this.crawling) {
         return false;
      }

      boolean bMoving = this.isCurrentState(PathFindState.instance())
         || this.isCurrentState(LungeState.instance())
         || this.isCurrentState(LungeNetworkState.instance())
         || this.isCurrentState(WalkTowardState.instance())
         || this.isCurrentState(WalkTowardNetworkState.instance());
      if (!bMoving) {
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
         IsoDoor door = (IsoDoor)Type.tryCastTo(obj, IsoDoor.class);
         IsoThumpable thumpable = (IsoThumpable)Type.tryCastTo(obj, IsoThumpable.class);
         IsoWindow window = (IsoWindow)Type.tryCastTo(obj, IsoWindow.class);
         IsoWindowFrame windowFrame = (IsoWindowFrame)Type.tryCastTo(obj, IsoWindowFrame.class);
         if (window != null && window.canClimbThrough(this)) {
            if (!this.isFacingObject(window, 0.8F)) {
               return false;
            }

            this.climbThroughWindow(window);
            return true;
         } else if (windowFrame != null && windowFrame.canClimbThrough(this)) {
            if (!this.isFacingObject(windowFrame, 0.8F)) {
               return false;
            }

            this.climbThroughWindowFrame(windowFrame);
            return true;
         } else if (door != null && door.isHoppable() && !door.isTallHoppable()) {
            if (!this.isFacingObject(door, 0.8F)) {
               return false;
            }

            IsoDirections dir = this.getCardinalDirectionTo(door.getSquare(), door.getNorth());
            this.climbOverFence(dir);
            return true;
         } else {
            if (thumpable != null && thumpable.canClimbThrough(this)) {
               this.climbThroughWindow(thumpable);
               return true;
            }

            if (thumpable == null || !thumpable.isHoppable() || thumpable.isTallHoppable()) {
               if (thumpable != null && thumpable.getThumpableFor(this) != null
                  || window != null && window.getThumpableFor(this) != null
                  || windowFrame != null && windowFrame.getThumpableFor(this) != null
                  || door != null && door.getThumpableFor(this) != null) {
                  int difX = feeler.getX() - this.current.getX();
                  int difY = feeler.getY() - this.current.getY();
                  int absDifX = Math.abs(difX);
                  int absDifY = Math.abs(difY);
                  IsoDirections dir = IsoDirections.N;
                  if (difX < 0 && absDifX > absDifY) {
                     dir = IsoDirections.S;
                  }

                  if (difX < 0 && absDifX <= absDifY) {
                     dir = IsoDirections.SW;
                  }

                  if (difX > 0 && absDifX > absDifY) {
                     dir = IsoDirections.W;
                  }

                  if (difX > 0 && absDifX <= absDifY) {
                     dir = IsoDirections.SE;
                  }

                  if (difY < 0 && absDifX < absDifY) {
                     dir = IsoDirections.N;
                  }

                  if (difY < 0 && absDifX >= absDifY) {
                     dir = IsoDirections.NW;
                  }

                  if (difY > 0 && absDifX < absDifY) {
                     dir = IsoDirections.E;
                  }

                  if (difY > 0 && absDifX >= absDifY) {
                     dir = IsoDirections.NE;
                  }

                  if (this.getDir() == dir) {
                     boolean chasingSound = this.getPathFindBehavior2().isGoalSound()
                        && (
                           this.isCurrentState(PathFindState.instance())
                              || this.isCurrentState(WalkTowardState.instance())
                              || this.isCurrentState(WalkTowardNetworkState.instance())
                        );
                     if (SandboxOptions.instance.lore.thumpNoChasing.getValue() || this.target != null || chasingSound) {
                        if (window != null && window.getThumpableFor(this) != null) {
                           obj = (IsoObject)window.getThumpableFor(this);
                        }

                        this.setThumpTarget(obj);
                        this.setPath2(null);
                     }
                  }

                  return true;
               } else {
                  return false;
               }
            } else {
               if (!this.isFacingObject(thumpable, 0.8F)) {
                  return false;
               }

               IsoDirections dir = this.getCardinalDirectionTo(thumpable.getSquare(), thumpable.getNorth());
               this.climbOverFence(dir);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   public void Wander() {
      this.changeState(ZombieIdleState.instance());
   }

   public void DoZombieInventory() {
      this.DoZombieInventory(false);
   }

   public void DoCorpseInventory() {
      this.DoZombieInventory(true);
   }

   private void DoZombieInventory(boolean bRandomCorpse) {
      if (!this.isReanimatedPlayer() && !this.wasFakeDead()) {
         if (GameServer.server && !this.persistentOutfitInit) {
            this.dressInPersistentOutfitID(this.persistentOutfitId);
         }

         this.getInventory().removeAllItems();
         this.getInventory().setSourceGrid(this.getCurrentSquare());
         this.wornItems.setFromItemVisuals(this.itemVisuals);
         this.wornItems.addItemsToItemContainer(this.getInventory());

         for (int i = 0; i < this.attachedItems.size(); i++) {
            AttachedItem attachedItem = this.attachedItems.get(i);
            InventoryItem item = attachedItem.getItem();
            if (!this.getInventory().contains(item)) {
               if (item.hasTag(ItemTag.APPLY_OWNER_NAME) && this.getDescriptor() != null) {
                  item.nameAfterDescriptor(this.getDescriptor());
               } else if (item.hasTag(ItemTag.MONOGRAM_OWNER_NAME) && this.getDescriptor() != null) {
                  item.monogramAfterDescriptor(this.getDescriptor());
               }

               item.setContainer(this.getInventory());
               this.getInventory().getItems().add(item);
            }
         }

         if (this.itemsToSpawnAtDeath != null && !this.itemsToSpawnAtDeath.isEmpty()) {
            for (int i = 0; i < this.itemsToSpawnAtDeath.size(); i++) {
               InventoryItem spawnItem = this.itemsToSpawnAtDeath.get(i);
               if (spawnItem.hasTag(ItemTag.APPLY_OWNER_NAME) && this.getDescriptor() != null) {
                  spawnItem.nameAfterDescriptor(this.getDescriptor());
               } else if (spawnItem.hasTag(ItemTag.MONOGRAM_OWNER_NAME) && this.getDescriptor() != null) {
                  spawnItem.monogramAfterDescriptor(this.getDescriptor());
               }

               ItemSpawner.spawnItem(this.itemsToSpawnAtDeath.get(i), this.inventory);
            }

            this.itemsToSpawnAtDeath.clear();
         }
      }
   }

   public void DoZombieStats() {
      if (SandboxOptions.instance.lore.cognition.getValue() == 1) {
         this.cognition = 1;
      }

      if (SandboxOptions.instance.lore.cognition.getValue() == 4) {
         if (Rand.Next(100) < SandboxOptions.instance.lore.doorOpeningPercentage.getValue()) {
            this.cognition = 1;
         } else {
            this.cognition = 0;
         }
      }

      if (this.strength == -1 && SandboxOptions.instance.lore.strength.getValue() == 1) {
         this.strength = 5;
      }

      if (this.strength == -1 && SandboxOptions.instance.lore.strength.getValue() == 2) {
         this.strength = 3;
      }

      if (this.strength == -1 && SandboxOptions.instance.lore.strength.getValue() == 3) {
         this.strength = 1;
      }

      if (this.strength == -1 && SandboxOptions.instance.lore.strength.getValue() == 4) {
         this.strength = Rand.Next(1, 5);
      }

      int memorySetting = SandboxOptions.instance.lore.memory.getValue();
      int randomMemory = -1;
      if (memorySetting == 5) {
         randomMemory = Rand.Next(4);
      } else if (memorySetting == 6) {
         randomMemory = Rand.Next(3) + 1;
      }

      if (this.memory == -1 && memorySetting == 1 || randomMemory == 0) {
         this.memory = 1250;
      }

      if (this.memory == -1 && memorySetting == 2 || randomMemory == 1) {
         this.memory = 800;
      }

      if (this.memory == -1 && memorySetting == 3 || randomMemory == 2) {
         this.memory = 500;
      }

      if (this.memory == -1 && memorySetting == 4 || randomMemory == 3) {
         this.memory = 25;
      }

      if (SandboxOptions.instance.lore.sight.getValue() == 4) {
         this.sight = Rand.Next(3) + 1;
      } else if (SandboxOptions.instance.lore.sight.getValue() == 5) {
         this.sight = Rand.Next(2) + 2;
      } else {
         this.sight = SandboxOptions.instance.lore.sight.getValue();
      }

      if (SandboxOptions.instance.lore.hearing.getValue() == 4) {
         this.hearing = Rand.Next(3) + 1;
      } else if (SandboxOptions.instance.lore.hearing.getValue() == 5) {
         this.hearing = Rand.Next(2) + 2;
      } else {
         this.hearing = SandboxOptions.instance.lore.hearing.getValue();
      }

      this.doZombieSpeed();
      this.initCanCrawlUnderVehicle();
   }

   public void setWalkType(String walkType) {
      this.walkType = walkType;
   }

   public static int getSpeedTypeFromWalkType(String walkType) {
      if (StringUtils.isNullOrWhitespace(walkType)) {
         return 2;
      } else if (walkType.startsWith("sprint")) {
         return 1;
      } else {
         return walkType.startsWith("slow") ? 3 : 2;
      }
   }

   public void setSpeedTypeFromWalkType() {
      this.speedType = getSpeedTypeFromWalkType(this.getWalkType());
   }

   public void DoZombieSpeeds(float spMod) {
      if (this.crawling) {
         this.speedMod = spMod;
         this.def.animFrameIncrease *= 0.8F;
      } else if (Rand.Next(3) != 0 || SandboxOptions.instance.lore.speed.getValue() == 3) {
         this.speedMod = spMod;
         this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
         this.walkVariant = this.walkVariant + "1";
         this.def.setFrameSpeedPerFrame(0.24F);
         this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      } else if (SandboxOptions.instance.lore.speed.getValue() != 3) {
         this.lunger = true;
         this.speedMod = spMod;
         this.walkVariant = this.walkVariant + "2";
         this.def.setFrameSpeedPerFrame(0.24F);
         this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      }
   }

   public boolean isFakeDead() {
      if (SandboxOptions.instance.lore.disableFakeDead.getValue() == 3) {
         this.fakeDead = false;
      }

      if (this.getSquare() != null && this.getSquare().HasStairs() && this.fakeDead) {
         this.fakeDead = false;
      }

      return this.fakeDead;
   }

   public void setFakeDead(boolean bFakeDead) {
      if (this.getSquare() != null && this.getSquare().HasStairs()) {
         this.fakeDead = false;
      } else {
         if (bFakeDead && Rand.Next(2) == 0) {
            this.setCrawlerType(2);
         }

         this.fakeDead = bFakeDead;
      }
   }

   public boolean isForceFakeDead() {
      return this.forceFakeDead;
   }

   public void setForceFakeDead(boolean bForceFakeDead) {
      this.forceFakeDead = bForceFakeDead;
   }

   public float onHitByVehicle(BaseVehicle vehicle, float impactSpeed, Vector2 hitDir, Vector2 impactPosOnVehicle) {
      if ((this.isProne() || this.isGettingUp()) && this.isFakeDead()) {
         this.setFakeDead(false);
      }

      return super.onHitByVehicle(vehicle, impactSpeed, hitDir, impactPosOnVehicle);
   }

   protected void onHitByVehicleDriver(IsoGameCharacter vehicleDriver) {
      super.onHitByVehicleDriver(vehicleDriver);
      if (vehicleDriver != null) {
         this.setTarget(this.attackedBy);
      }
   }

   protected void postHitByVehicleUpdateStance(float speed, boolean knockDownAllowed) {
      if (!GameServer.server) {
         boolean staggerBack = this.isStaggerBack();
         boolean knockedDown = this.isKnockedDown();
         boolean becomeCrawler = this.isBecomeCrawler();
         float unbalancedLevel = this.getUnbalancedLevel();
         float unbalancedSpeedAlpha = PZMath.clamp(unbalancedLevel, 0.0F, 1.0F);
         float minSpeedToGuaranteedStagger = PZMath.lerp(2.0F, 0.1F, unbalancedSpeedAlpha);
         float minSpeedToPossibleKnockdown = PZMath.lerp(5.0F, 0.1F, unbalancedSpeedAlpha);
         float maxSpeedToPossibleKnockdown = PZMath.lerp(25.0F, 15.0F, unbalancedSpeedAlpha);
         float minSpeedToPossibleCrawler = this.isProne() ? 6.75F : 2.0F;
         float maxSpeedToPossibleCrawler = 20.5F;
         if (!staggerBack) {
            if (speed > minSpeedToGuaranteedStagger) {
               staggerBack = true;
            } else {
               float staggerRandAlpha = speed / minSpeedToGuaranteedStagger;
               float staggerRandf = PZMath.lerpFunc_EaseOutQuad(staggerRandAlpha);
               int staggerRand = (int)(staggerRandf * 100.0F);
               boolean shouldStagger = staggerRandAlpha > 1.0F || Rand.Next(100) <= staggerRand;
               if (shouldStagger) {
                  staggerBack = true;
               }
            }
         }

         if (staggerBack) {
            unbalancedLevel = PZMath.lerp(unbalancedLevel, 1.0F, 0.35F * GameTime.getInstance().getMultiplier());
            unbalancedLevel = PZMath.clamp(unbalancedLevel, 0.35F, 1.0F);
         }

         if (knockDownAllowed && !knockedDown && speed > minSpeedToPossibleKnockdown) {
            float randMultiplier = PZMath.lerp(1.0F, 10.0F, unbalancedLevel);
            float knockDownRandAlpha = randMultiplier * (speed - minSpeedToPossibleKnockdown) / (maxSpeedToPossibleKnockdown - minSpeedToPossibleKnockdown);
            float knockDownRandf = PZMath.lerpFunc_EaseInQuad(knockDownRandAlpha);
            int knockDownRand = (int)(knockDownRandf * 100.0F);
            boolean shouldKnockDown = knockDownRandAlpha > 1.0F || Rand.Next(100) <= knockDownRand;
            if (shouldKnockDown) {
               knockedDown = true;
            }
         }

         if (!becomeCrawler && knockedDown && speed > minSpeedToPossibleCrawler) {
            float becomeCrawlerRandAlpha = (speed - minSpeedToPossibleCrawler) / (20.5F - minSpeedToPossibleCrawler);
            float becomeCrawlerRandf = PZMath.lerpFunc_EaseOutQuad(becomeCrawlerRandAlpha);
            int becomeCrawlerRand = (int)(becomeCrawlerRandf * 100.0F);
            boolean shouldBecomeCrawler = becomeCrawlerRandAlpha > 1.0F || Rand.Next(100) <= becomeCrawlerRand;
            if (shouldBecomeCrawler) {
               becomeCrawler = true;
            }
         }

         this.setStaggerBack(staggerBack);
         this.setKnockedDown(knockedDown);
         this.setBecomeCrawler(becomeCrawler);
         this.setUnbalancedLevel(unbalancedLevel);
         unbalancedLevel = PZMath.lerp(unbalancedLevel, 1.0F, 0.1F * GameTime.getInstance().getMultiplier());
         unbalancedLevel = PZMath.clamp(unbalancedLevel, 0.2F, 1.0F);
      }
   }

   public void addBloodFromVehicleImpact(float speed) {
      if (!(Rand.Next(10) > speed)) {
         float dz = 0.6F;
         if (SandboxOptions.instance.bloodLevel.getValue() > 1) {
            int spn = Rand.Next(4, 10);
            if (spn < 1) {
               spn = 1;
            }

            if (Core.lastStand) {
               spn *= 3;
            }

            switch (SandboxOptions.instance.bloodLevel.getValue()) {
               case 2:
                  spn /= 2;
               case 3:
               default:
                  break;
               case 4:
                  spn *= 2;
                  break;
               case 5:
                  spn *= 5;
            }

            for (int n = 0; n < spn; n++) {
               this.splatBlood(2, 0.3F);
            }
         }

         if (SandboxOptions.instance.bloodLevel.getValue() > 1) {
            this.splatBloodFloorBig();
         }

         if (SandboxOptions.instance.bloodLevel.getValue() > 1) {
            this.playBloodSplatterSound();
            new IsoZombieGiblets(
               GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 1.5F, this.getHitDir().y * 1.5F
            );
            tempo.x = this.getHitDir().x;
            tempo.y = this.getHitDir().y;
            int rand = 3;
            int rand2 = 0;
            int nbRepeat = 1;
            switch (SandboxOptions.instance.bloodLevel.getValue()) {
               case 1:
                  nbRepeat = 0;
                  break;
               case 2:
                  nbRepeat = 1;
                  rand = 5;
                  rand2 = 2;
               case 3:
               default:
                  break;
               case 4:
                  nbRepeat = 3;
                  rand = 2;
                  break;
               case 5:
                  nbRepeat = 10;
                  rand = 0;
            }

            for (int i = 0; i < nbRepeat; i++) {
               if (Rand.Next(this.isCloseKilled() ? 8 : rand) == 0) {
                  new IsoZombieGiblets(
                     GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 1.5F, this.getHitDir().y * 1.5F
                  );
               }

               if (Rand.Next(this.isCloseKilled() ? 8 : rand) == 0) {
                  new IsoZombieGiblets(
                     GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 1.8F, this.getHitDir().y * 1.8F
                  );
               }

               if (Rand.Next(this.isCloseKilled() ? 8 : rand) == 0) {
                  new IsoZombieGiblets(
                     GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 1.9F, this.getHitDir().y * 1.9F
                  );
               }

               if (Rand.Next(this.isCloseKilled() ? 4 : rand2) == 0) {
                  new IsoZombieGiblets(
                     GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 3.9F, this.getHitDir().y * 3.9F
                  );
               }

               if (Rand.Next(this.isCloseKilled() ? 4 : rand2) == 0) {
                  new IsoZombieGiblets(
                     GibletType.A, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 3.8F, this.getHitDir().y * 3.8F
                  );
               }

               if (Rand.Next(this.isCloseKilled() ? 9 : 6) == 0) {
                  new IsoZombieGiblets(
                     GibletType.Eye, this.getCell(), this.getX(), this.getY(), this.getZ() + 0.6F, this.getHitDir().x * 0.8F, this.getHitDir().y * 0.8F
                  );
               }
            }
         }
      }
   }

   public void hitConsequences(HandWeapon weapon, IsoGameCharacter wielder, boolean bIgnoreDamage, float damage, boolean bRemote) {
      if (!this.isOnlyJawStab() || this.isCloseKilled()) {
         super.hitConsequences(weapon, wielder, bIgnoreDamage || GameClient.client && this.isRemoteZombie(), damage, bRemote);
         if (Core.debug) {
            boolean isCriticalHit = wielder.isCriticalHit();
            String hitType = isCriticalHit ? "got critically hit for" : "got hit for";
            DebugType.Combat.debugln("Zombie #%d %s %f", new Object[]{this.getID(), hitType, damage});
         }

         this.getActionContext().reportEvent("wasHit");
         if (!bRemote) {
            CombatManager.getInstance().processHit(weapon, wielder, this);
         }

         if (!GameClient.client
            || this.target == null
            || wielder == this.target
            || !(IsoUtils.DistanceToSquared(this.getX(), this.getY(), this.target.getX(), this.target.getY()) < 10.0F)) {
            this.setTarget(wielder);
         }

         if (!GameServer.server && !GameClient.client
            || GameClient.client && wielder instanceof IsoPlayer player && player.isLocalPlayer() && !this.isRemoteZombie()) {
            this.setKnockedDown(wielder.isCriticalHit() || this.isOnFloor() || this.isAlwaysKnockedDown());
         }

         this.checkClimbOverFenceHit();
         this.checkClimbThroughWindowHit();
         if (this.shouldBecomeCrawler(wielder)) {
            this.setBecomeCrawler(true);
         }
      }
   }

   public long playHurtSound() {
      this.parameterZombieState.setState(zombie.audio.parameters.ParameterZombieState.State.Hit);
      return 0L;
   }

   private void checkClimbOverFenceHit() {
      if (!this.isOnFloor()) {
         ClimbOverFenceState climbOverFenceState = ClimbOverFenceState.instance();
         if (this.isCurrentState(climbOverFenceState)
            && this.getVariableBoolean("ClimbFenceStarted")
            && !this.isVariable("ClimbFenceOutcome", "fall")
            && !this.getVariableBoolean("ClimbFenceFlopped")) {
            int endX = (Integer)this.get(ClimbOverFenceState.END_X);
            int endY = (Integer)this.get(ClimbOverFenceState.END_Y);
            this.climbFenceWindowHit(endX, endY);
         }
      }
   }

   private void checkClimbThroughWindowHit() {
      if (!this.isOnFloor()) {
         if (this.isCurrentState(ClimbThroughWindowState.instance())
            && this.getVariableBoolean("ClimbWindowStarted")
            && !this.isVariable("ClimbWindowOutcome", "fall")
            && !this.getVariableBoolean("ClimbWindowFlopped")) {
            ClimbThroughWindowPositioningParams params = ClimbThroughWindowState.instance().getPositioningParams(this);
            if (params != null) {
               this.climbFenceWindowHit(params.endX, params.endY);
            }
         }
      }
   }

   private void climbFenceWindowHit(int endX, int endY) {
      if (this.getDir() == IsoDirections.W) {
         this.setX(endX + 0.9F);
         this.setLastX(this.getX());
      } else if (this.getDir() == IsoDirections.E) {
         this.setX(endX + 0.1F);
         this.setLastX(this.getX());
      } else if (this.getDir() == IsoDirections.N) {
         this.setY(endY + 0.9F);
         this.setLastY(this.getY());
      } else if (this.getDir() == IsoDirections.S) {
         this.setY(endY + 0.1F);
         this.setLastY(this.getY());
      }

      this.setStaggerBack(false);
      this.setKnockedDown(true);
      this.setOnFloor(true);
      this.setFallOnFront(true);
      this.setHitReaction("FenceWindow");
   }

   private boolean shouldBecomeCrawler(IsoGameCharacter attacker) {
      if (this.isBecomeCrawler()) {
         return true;
      }

      if (this.isCrawling()) {
         return false;
      }

      if (Core.lastStand) {
         return false;
      }

      if (this.isDead()) {
         return false;
      }

      if (this.isCloseKilled()) {
         return false;
      }

      IsoPlayer player = (IsoPlayer)Type.tryCastTo(attacker, IsoPlayer.class);
      if (player != null && !player.isAimAtFloor() && player.isDoShove()) {
         return false;
      }

      int chance = 30;
      if (player != null && player.isAimAtFloor() && player.isDoShove()) {
         if (this.isHitLegsWhileOnFloor()) {
            chance = 7;
         } else {
            chance = 15;
         }
      }

      return Rand.NextBool(chance);
   }

   public void removeFromWorld() {
      this.getEmitter().stopOrTriggerSoundByName("BurningFlesh");
      this.clearAggroList();
      VirtualZombieManager.instance.RemoveZombie(this);
      this.setPath2(null);
      if (PathfindNative.useNativeCode) {
         PathfindNative.instance.cancelRequest(this);
      } else {
         PolygonalMap2.instance.cancelRequest(this);
      }

      if (this.getFinder().progress != PathFindProgress.notrunning && this.getFinder().progress != PathFindProgress.found) {
         this.getFinder().progress = PathFindProgress.notrunning;
      }

      if (this.group != null) {
         this.group.remove(this);
         this.group = null;
      }

      ZombiePopulationManager.releaseZombie(this);
      this.persistentId = 0;
      if (GameServer.server && this.onlineId != -1) {
         ServerMap.instance.zombieMap.remove(this.onlineId);
         this.onlineId = -1;
      }

      if (GameClient.client) {
         GameClient.instance.removeZombieFromCache(this);
      }

      this.getCell().getZombieList().remove(this);
      if (GameServer.server) {
         if (this.getOwner() != null || this.getOwnerPlayer() != null) {
            NetworkZombieManager.getInstance().moveZombie(this, null, null);
         }

         this.zombiePacketUpdated = false;
      }

      this.imposter.destroy();
      super.removeFromWorld();
   }

   public void resetForReuse() {
      this.setCrawler(false);
      this.initializeStates();
      this.getActionContext().setGroup(pzoptGroup(false));
      this.getAdvancedAnimator().OnAnimDataChanged(false);
      this.setStateMachineLocked(false);
      this.setDefaultState();
      if (this.vocalEvent != 0L) {
         this.getEmitter().stopSoundLocal(this.vocalEvent);
         this.vocalEvent = 0L;
      }

      this.parameterZombieState.setState(zombie.audio.parameters.ParameterZombieState.State.Idle);
      this.setSceneCulled(true);
      this.releaseAnimationPlayer();
      Arrays.fill(this.isVisibleToPlayer, false);
      this.setCurrent(null);
      this.setLast(null);
      this.setOnFloor(false);
      this.setCanWalk(true);
      this.setFallOnFront(false);
      this.setHitTime(0);
      this.strength = -1;
      this.setImmortalTutorialZombie(false);
      this.setOnlyJawStab(false);
      this.setAlwaysKnockedDown(false);
      this.setForceEatingAnimation(false);
      this.setNoTeeth(false);
      this.cognition = -1;
      this.inactive = GameTime.getInstance().isZombieInactivityPhase();
      this.speedType = -1;
      this.voiceChoice = -1;
      this.bodyToEat = null;
      this.checkForCorpseTimer = 10000.0F;
      this.clearAttachedItems();
      this.target = null;
      this.setEatBodyTarget(null, false);
      this.setSkeleton(false);
      this.setReanimatedPlayer(false);
      this.setBecomeCrawler(false);
      this.setWasFakeDead(false);
      this.setKnifeDeath(false);
      this.setJawStabAttach(false);
      this.setReanimate(false);
      this.DoZombieStats();
      this.alerted = false;
      this.bonusSpotTime = 0.0F;
      this.timeSinceSeenFlesh = 100000.0F;
      this.soundReactDelay = 0.0F;
      this.delayedSound.x = this.delayedSound.y = this.delayedSound.z = -1;
      this.soundSourceIsPlayerBase = false;
      this.soundSourceIsPlayer = false;
      this.soundSourceRepeating = false;
      this.soundSourceTarget = null;
      this.soundAttract = 0.0F;
      this.soundAttractTimeout = 0.0F;
      if (SandboxOptions.instance.lore.toughness.getValue() == 1) {
         this.setHealth(3.5F + Rand.Next(0.0F, 0.3F));
      }

      if (SandboxOptions.instance.lore.toughness.getValue() == 2) {
         this.setHealth(1.8F + Rand.Next(0.0F, 0.3F));
      }

      if (SandboxOptions.instance.lore.toughness.getValue() == 3) {
         this.setHealth(0.5F + Rand.Next(0.0F, 0.3F));
      }

      if (SandboxOptions.instance.lore.toughness.getValue() == 4) {
         this.setHealth(Rand.Next(0.5F, 3.5F) + Rand.Next(0.0F, 0.3F));
      }

      this.setCollidable(true);
      this.setShootable(true);
      if (this.isOnFire()) {
         IsoFireManager.RemoveBurningCharacter(this);
         this.setOnFire(false);
      }

      if (this.attachedAnimSprite != null) {
         this.attachedAnimSprite.clear();
      }

      this.onlineId = -1;
      this.indoorZombie = false;
      this.setVehicle4TestCollision(null);
      this.clearItemsToSpawnAtDeath();
      this.persistentOutfitId = 0;
      this.persistentOutfitInit = false;
      this.sharedDesc = null;
      this.imposter.destroy();
      if (this.hasModData()) {
         this.getModData().wipe();
      }

      this.setInvulnerable(false);
      this.getWrappedGrappleable().resetGrappleStateToDefault("");
      this.unbalancedLevel = 0.0F;
   }

   public boolean wasFakeDead() {
      return this.wasFakeDead;
   }

   public void setWasFakeDead(boolean wasFakeDead) {
      this.wasFakeDead = wasFakeDead;
   }

   public void setCrawler(boolean crawling) {
      this.crawling = crawling;
   }

   public boolean isBecomeCrawler() {
      return this.becomeCrawler;
   }

   public void setBecomeCrawler(boolean crawler) {
      if (!this.isInvulnerable()) {
         this.becomeCrawler = crawler;
      }
   }

   public boolean isReanimate() {
      return this.reanimate;
   }

   public void setReanimate(boolean reanimate) {
      this.reanimate = reanimate;
   }

   public boolean isReanimatedPlayer() {
      return this.reanimatedPlayer;
   }

   public void setReanimatedPlayer(boolean reanimated) {
      this.reanimatedPlayer = reanimated;
   }

   public IsoPlayer getReanimatedPlayer() {
      for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
         IsoPlayer player = IsoPlayer.players[playerIndex];
         if (player != null && player.reanimatedCorpse == this) {
            return player;
         }
      }

      return null;
   }

   public void setFemaleEtc(boolean female) {
      this.setFemale(female);
      if (this.getDescriptor() != null) {
         this.getDescriptor().setFemale(female);
         this.getDescriptor().setVoicePrefix(this.getCharacterGender() + "Zombie");
      }

      this.spriteName = female ? "KateZ" : "BobZ";
      this.hurtSound = this.getDescriptor().getVoicePrefix() + "Hurt";
      if (this.vocalEvent != 0L) {
         String soundName = this.getVoiceSoundName();
         if (this.getEmitter() != null && !this.getEmitter().isPlaying(soundName)) {
            this.getEmitter().stopSoundLocal(this.vocalEvent);
            this.vocalEvent = 0L;
         }
      }
   }

   public void addRandomBloodDirtHolesEtc() {
      if (!this.isSkeleton()) {
         this.addRandomVisualDamages();
         this.addRandomVisualBandages();
      }

      float damageFactor = SandboxOptions.instance.clothingDegradation.getValue();
      if (damageFactor != 1.0F) {
         damageFactor = (damageFactor - 1.0F) / 2.0F;
         damageFactor *= damageFactor;
         boolean allLayers = damageFactor >= 1.0F;
         this.addBlood(null, false, true, false);
         int worldAgeMonths = (int)(IsoWorld.instance.getWorldAgeDays() / 30.0F * damageFactor);
         this.addLotsOfDirt(null, (int)((Rand.Next(5, 20) + Math.min(worldAgeMonths, 24)) * damageFactor), allLayers);
         int rand = Math.max(8 - worldAgeMonths, 0);
         int rolls = PZMath.clamp(worldAgeMonths, 5, 10);
         rolls *= (int)damageFactor;

         for (int i = 0; i < rolls; i++) {
            if (OutfitRNG.NextBool(rand)) {
               this.addBlood(null, false, true, false);
            }

            if (OutfitRNG.NextBool(rand / 2)) {
               this.addLotsOfDirt(null, null, allLayers);
            }
         }

         rolls = PZMath.clamp(worldAgeMonths, 8, 16);
         rolls *= (int)damageFactor;
         rand /= 2;

         for (int i = 0; i < rolls; i++) {
            if (OutfitRNG.NextBool(rand)) {
               BloodBodyPartType part = BloodBodyPartType.FromIndex(OutfitRNG.Next(0, BloodBodyPartType.MAX.index()));
               this.addHole(part);
               this.addBlood(part, true, false, false);
            }
         }

         for (int i = 0; i < rolls; i++) {
            if (OutfitRNG.NextBool(rand)) {
               BloodBodyPartType part = BloodBodyPartType.FromIndex(OutfitRNG.Next(0, BloodBodyPartType.MAX.index()));
               this.addHole(part);
               this.addLotsOfDirt(part, 1, allLayers);
            }
         }
      }
   }

   public void useDescriptor(Descriptor sharedDesc) {
      this.getHumanVisual().clear();
      this.itemVisuals.clear();
      this.persistentOutfitId = sharedDesc == null ? 0 : sharedDesc.getPersistentOutfitID();
      this.persistentOutfitInit = true;
      this.sharedDesc = sharedDesc;
      if (sharedDesc != null) {
         this.setFemaleEtc(sharedDesc.isFemale());
         this.getHumanVisual().copyFrom(sharedDesc.getHumanVisual());
         this.getWornItems().setFromItemVisuals(sharedDesc.itemVisuals);
         this.onWornItemsChanged();
      }
   }

   public Descriptor getSharedDescriptor() {
      return this.sharedDesc;
   }

   public int getSharedDescriptorID() {
      return this.getPersistentOutfitID();
   }

   public int getScreenProperX(int playerIndex) {
      return (int)(IsoUtils.XToScreen(this.getX(), this.getY(), this.getZ(), 0) - IsoCamera.cameras[playerIndex].getOffX());
   }

   public int getScreenProperY(int playerIndex) {
      return (int)(IsoUtils.YToScreen(this.getX(), this.getY(), this.getZ(), 0) - IsoCamera.cameras[playerIndex].getOffY());
   }

   public BaseVisual getVisual() {
      return this.humanVisual;
   }

   public HumanVisual getHumanVisual() {
      return this.humanVisual;
   }

   public ItemVisuals getItemVisuals() {
      this.getItemVisuals(this.itemVisuals);
      return this.itemVisuals;
   }

   public void getItemVisuals(ItemVisuals itemVisuals) {
      if (this.isUsingWornItems()) {
         this.getWornItems().getItemVisuals(itemVisuals);
      } else if (itemVisuals != this.itemVisuals) {
         itemVisuals.clear();
         PZArrayUtil.addAll(itemVisuals, this.itemVisuals);
      }
   }

   public boolean isUsingWornItems() {
      return this.isOnKillDone() || this.isOnDeathDone() || this.isReanimatedPlayer() || this.wasFakeDead();
   }

   public void setAsSurvivor() {
      String outfitName = "Survivalist";
      switch (Rand.Next(5)) {
         case 1:
            outfitName = "Survivalist02";
            break;
         case 2:
            outfitName = "Survivalist03";
            break;
         case 3:
            outfitName = "Survivalist04";
            break;
         case 4:
            outfitName = "Survivalist05";
      }

      this.dressInPersistentOutfit(outfitName);
   }

   public void dressInRandomOutfit() {
      ZombiesZoneDefinition.dressInRandomOutfit(this);
   }

   public void dressInNamedOutfit(String outfitName) {
      this.wornItems.clear();
      this.getHumanVisual().clear();
      this.itemVisuals.clear();
      Outfit outfitSource = this.isFemale() ? OutfitManager.instance.FindFemaleOutfit(outfitName) : OutfitManager.instance.FindMaleOutfit(outfitName);
      if (outfitSource != null) {
         if (outfitSource.isEmpty()) {
            outfitSource.loadItems();
            this.pendingOutfitName = outfitName;
         } else {
            UnderwearDefinition.addRandomUnderwear(this);
            this.getDescriptor().setForename(SurvivorFactory.getRandomForename(this.isFemale()));
            this.getHumanVisual().dressInNamedOutfit(outfitName, this.itemVisuals, false);
            this.getHumanVisual().synchWithOutfit(this.getHumanVisual().getOutfit());
            this.onWornItemsChanged();
         }
      }
   }

   public void dressInPersistentOutfitID(int outfitID) {
      this.getHumanVisual().clear();
      this.itemVisuals.clear();
      this.persistentOutfitId = outfitID;
      this.persistentOutfitInit = true;
      if (outfitID != 0) {
         this.dressInRandomOutfit = false;
         PersistentOutfits.instance.dressInOutfit(this, outfitID);
         this.onWornItemsChanged();
      }
   }

   public void dressInClothingItem(String itemGUID) {
      this.wornItems.clear();
      this.getHumanVisual().dressInClothingItem(itemGUID, this.itemVisuals);
      this.onWornItemsChanged();
   }

   public boolean onDeath_ShouldDoSplatterAndSounds(HandWeapon weapon, IsoGameCharacter wielder, boolean isGory) {
      return this.isReanimatedForGrappleOnly() ? false : super.onDeath_ShouldDoSplatterAndSounds(weapon, wielder, isGory);
   }

   public void onWornItemsChanged() {
      super.onWornItemsChanged();
      this.parameterShoeType.setShoeType(null);
   }

   public void clothingItemChanged(String itemGuid) {
      super.clothingItemChanged(itemGuid);
      if (!StringUtils.isNullOrWhitespace(this.pendingOutfitName)) {
         Outfit outfitSource = this.isFemale()
            ? OutfitManager.instance.FindFemaleOutfit(this.pendingOutfitName)
            : OutfitManager.instance.FindMaleOutfit(this.pendingOutfitName);
         if (outfitSource != null && !outfitSource.isEmpty()) {
            this.dressInNamedOutfit(this.pendingOutfitName);
            this.pendingOutfitName = null;
            this.resetModelNextFrame();
         }
      }
   }

   public boolean WanderFromWindow() {
      if (this.getCurrentSquare() == null) {
         return false;
      } else {
         IsoZombie.FloodFill ff = floodFill;
         ff.calculate(this, this.getCurrentSquare());
         IsoGridSquare sq = ff.choose();
         ff.reset();
         if (sq != null) {
            this.pathToLocation(sq.getX(), sq.getY(), sq.getZ());
            return true;
         } else {
            return false;
         }
      }
   }

   public boolean isUseless() {
      return this.useless;
   }

   public void setUseless(boolean useless) {
      this.useless = useless;
   }

   public void setImmortalTutorialZombie(boolean immortal) {
      this.immortalTutorialZombie = immortal;
   }

   public boolean isTargetInCone(float dist, float dot) {
      if (this.target == null) {
         return false;
      }

      tempo.set(this.target.getX() - this.getX(), this.target.getY() - this.getY());
      float length = tempo.getLength();
      if (length == 0.0F) {
         return true;
      }

      if (length > dist) {
         return false;
      }

      tempo.normalize();
      this.getVectorFromDirection(tempo2);
      float facing = tempo.dot(tempo2);
      return facing >= dot;
   }

   public boolean isCrawling() {
      return this.crawling;
   }

   public boolean isCanCrawlUnderVehicle() {
      return this.canCrawlUnderVehicle;
   }

   public void setCanCrawlUnderVehicle(boolean b) {
      this.canCrawlUnderVehicle = b;
   }

   public boolean isCanWalk() {
      return this.canWalk;
   }

   public void setCanWalk(boolean bCanStand) {
      this.canWalk = bCanStand;
   }

   public void initCanCrawlUnderVehicle() {
      int chance = 100;
      switch (SandboxOptions.instance.lore.crawlUnderVehicle.getValue()) {
         case 1:
            chance = 0;
            break;
         case 2:
            chance = 5;
            break;
         case 3:
            chance = 10;
            break;
         case 4:
            chance = 25;
            break;
         case 5:
            chance = 50;
            break;
         case 6:
            chance = 75;
            break;
         case 7:
            chance = 100;
      }

      this.setCanCrawlUnderVehicle(Rand.Next(100) < chance);
   }

   public boolean shouldGetUpFromCrawl() {
      if (this.isCurrentState(ZombieGetUpFromCrawlState.instance())) {
         return true;
      }

      if (this.isCurrentState(ZombieGetUpState.instance())) {
         return this.getStateMachine().getPrevious() == ZombieGetUpFromCrawlState.instance();
      }

      if (!this.isCrawling()) {
         return false;
      }

      if (!this.isCanWalk()) {
         return false;
      }

      if (this.isCurrentState(PathFindState.instance())) {
         return this.getStateMachine().getPrevious() == ZombieGetDownState.instance() && ZombieGetDownState.instance().isNearStartXY(this)
            ? false
            : this.getPathFindBehavior2().shouldGetUpFromCrawl();
      }

      if (this.isCurrentState(WalkTowardState.instance())) {
         float targetX = this.getPathFindBehavior2().getTargetX();
         float targetY = this.getPathFindBehavior2().getTargetY();
         if (this.DistToSquared(targetX, targetY) > 0.010000001F
            && PolygonalMap2.instance.lineClearCollide(this.getX(), this.getY(), targetX, targetY, PZMath.fastfloor(this.getZ()), null)) {
            return false;
         }
      }

      return this.isCurrentState(ZombieGetDownState.instance())
         ? false
         : PolygonalMap2.instance.canStandAt(this.getX(), this.getY(), this.getZi(), null, false, true);
   }

   public void toggleCrawling() {
      boolean bCanCrawlUnderVehicle = this.canCrawlUnderVehicle;
      if (this.crawling) {
         this.setCrawler(false);
         this.setKnockedDown(false);
         this.setStaggerBack(false);
         this.setFallOnFront(false);
         this.setOnFloor(false);
         this.DoZombieStats();
      } else {
         this.setCrawler(true);
         this.setOnFloor(true);
         this.DoZombieStats();
         this.walkVariant = "ZombieWalk";
      }

      this.canCrawlUnderVehicle = bCanCrawlUnderVehicle;
   }

   public void knockDown(boolean hitFromBehind) {
      this.setKnockedDown(true);
      this.setStaggerBack(true);
      this.setHitReaction("");
      this.setPlayerAttackPosition(hitFromBehind ? "BEHIND" : null);
      this.setHitForce(1.0F);
      this.reportEvent("wasHit");
   }

   public void addItemToSpawnAtDeath(InventoryItem item) {
      if (item != null) {
         if (this.itemsToSpawnAtDeath == null) {
            this.itemsToSpawnAtDeath = new ArrayList<>();
         }

         if (!this.itemsToSpawnAtDeath.contains(item)) {
            this.itemsToSpawnAtDeath.add(item);
         }
      }
   }

   public void clearItemsToSpawnAtDeath() {
      if (this.itemsToSpawnAtDeath != null) {
         this.itemsToSpawnAtDeath.clear();
      }
   }

   public IsoMovingObject getEatBodyTarget() {
      return this.eatBodyTarget;
   }

   public float getEatSpeed() {
      return this.eatSpeed;
   }

   public void setEatBodyTarget(IsoMovingObject target, boolean force) {
      this.setEatBodyTarget(target, force, Rand.Next(0.64F, 0.96F));
   }

   public void setEatBodyTarget(IsoMovingObject target, boolean force, float eatSpeed) {
      if (target != this.eatBodyTarget) {
         if (force || target == null || target.getEatingZombies().size() < 3) {
            if (this.eatBodyTarget != null) {
               this.eatBodyTarget.getEatingZombies().remove(this);
            }

            this.eatBodyTarget = target;
            if (target != null) {
               this.eatBodyTarget.getEatingZombies().add(this);
               this.eatSpeed = eatSpeed;
            }
         }
      }
   }

   private void updateEatBodyTarget() {
      if (this.bodyToEat != null
         && this.isCurrentState(ZombieIdleState.instance())
         && this.DistToSquared(this.bodyToEat) <= 1.0F
         && PZMath.fastfloor(this.getZ()) == PZMath.fastfloor(this.bodyToEat.getZ())) {
         this.setEatBodyTarget(this.bodyToEat, false);
         this.bodyToEat = null;
      }

      if (this.eatBodyTarget != null) {
         if (this.eatBodyTarget instanceof IsoDeadBody && this.eatBodyTarget.getStaticMovingObjectIndex() == -1) {
            this.setEatBodyTarget(null, false);
         }

         if (this.target != null && !this.target.isOnFloor() && this.target != this.eatBodyTarget) {
            this.setEatBodyTarget(null, false);
         }

         IsoPlayer player = (IsoPlayer)Type.tryCastTo(this.eatBodyTarget, IsoPlayer.class);
         if (player != null && player.reanimatedCorpse != null) {
            this.setEatBodyTarget(null, false);
         }

         if (player != null && player.isAlive() && !player.isOnFloor() && !player.isCurrentState(PlayerHitReactionState.instance())) {
            this.setEatBodyTarget(null, false);
         }

         if (!this.isCurrentState(ZombieEatBodyState.instance()) && this.eatBodyTarget != null && this.DistToSquared(this.eatBodyTarget) > 1.0F) {
            this.setEatBodyTarget(null, false);
         }

         if (this.eatBodyTarget != null
            && this.eatBodyTarget.getSquare() != null
            && this.current != null
            && this.current.isSomethingTo(this.eatBodyTarget.getSquare())) {
            this.setEatBodyTarget(null, false);
         }
      }
   }

   private void updateCharacterTextureAnimTime() {
      boolean bMoving = this.isCurrentState(WalkTowardState.instance()) || this.isCurrentState(PathFindState.instance());
      this.characterTextureAnimDuration = bMoving ? 0.67F : 2.0F;
      this.characterTextureAnimTime = this.characterTextureAnimTime + GameTime.getInstance().getTimeDelta();
      if (this.characterTextureAnimTime > this.characterTextureAnimDuration) {
         this.characterTextureAnimTime = this.characterTextureAnimTime % this.characterTextureAnimDuration;
      }
   }

   public int getCrawlerType() {
      return this.crawlerType;
   }

   public void setCrawlerType(int crawlerType) {
      this.crawlerType = crawlerType;
   }

   public void addRandomVisualBandages() {
      if (!"Tutorial".equals(Core.getInstance().getGameMode())) {
         for (int i = 0; i < 5; i++) {
            if (OutfitRNG.Next(10) == 0) {
               BodyPartType bodyPart = BodyPartType.getRandom();
               String itemType = bodyPart.getBandageModel() + "_Blood";
               this.addBodyVisualFromItemType(itemType);
            }
         }
      }
   }

   public void addVisualBandage(BodyPartType bodyPart, boolean bloody) {
      String itemType = bodyPart.getBandageModel() + (bloody ? "_Blood" : "");
      this.addBodyVisualFromItemType(itemType);
   }

   public void addRandomVisualDamages() {
      for (int i = 0; i < 5; i++) {
         if (OutfitRNG.Next(5) == 0) {
            String visualDmg = (String)OutfitRNG.pickRandom(ScriptManager.instance.getZedDmgMap());
            this.addBodyVisualFromItemType("Base." + visualDmg);
         }
      }
   }

   public String getPlayerAttackPosition() {
      return this.playerAttackPosition;
   }

   public void setPlayerAttackPosition(String playerAttackPosition) {
      this.playerAttackPosition = playerAttackPosition;
   }

   public boolean isSitAgainstWall() {
      return this.sitAgainstWall;
   }

   public void setSitAgainstWall(boolean sitAgainstWall) {
      this.sitAgainstWall = sitAgainstWall;
      NetworkZombieAI networkAi = this.getNetworkCharacterAI();
      networkAi.extraUpdate();
   }

   public boolean isSkeleton() {
      if (Core.debug && DebugOptions.instance.model.forceSkeleton.getValue()) {
         this.getHumanVisual().setSkinTextureIndex(2);
         return true;
      } else {
         return this.isSkeleton;
      }
   }

   public boolean isZombie() {
      return true;
   }

   public void setSkeleton(boolean isSkeleton) {
      this.isSkeleton = isSkeleton;
      if (isSkeleton) {
         this.getHumanVisual().setHairModel("");
         this.getHumanVisual().setBeardModel("");
         ModelManager.instance.Reset(this);
      }
   }

   public int getHitTime() {
      return this.hitTime;
   }

   public void setHitTime(int hitTime) {
      this.hitTime = hitTime;
   }

   public int getThumpTimer() {
      return this.thumpTimer;
   }

   public void setThumpTimer(int thumpTimer) {
      this.thumpTimer = thumpTimer;
   }

   public IsoMovingObject getTarget() {
      return this.target;
   }

   public void setTargetSeenTime(float seconds) {
      this.targetSeenTime = seconds;
   }

   public float getTargetSeenTime() {
      return this.targetSeenTime;
   }

   public boolean isTargetVisible() {
      IsoPlayer player = (IsoPlayer)Type.tryCastTo(this.target, IsoPlayer.class);
      if (player != null && this.getCurrentSquare() != null) {
         return GameServer.server ? ServerLOS.instance.isCouldSee(player, this.getCurrentSquare()) : this.getCurrentSquare().isCouldSee(player.getIndex());
      } else {
         return false;
      }
   }

   public float getTurnDelta() {
      return this.turnDeltaNormal;
   }

   public boolean isAttacking() {
      return this.isZombieAttacking();
   }

   public boolean isZombieAttacking() {
      State currentState = this.getCurrentState();
      return currentState != null && currentState.isAttacking(this);
   }

   public boolean isZombieAttacking(IsoMovingObject other) {
      return GameClient.client && this.isRemoteZombie()
         ? this.legsSprite != null && this.legsSprite.currentAnim != null && "ZombieBite".equals(this.legsSprite.currentAnim.name)
         : other == this.target && this.isCurrentState(AttackState.instance());
   }

   public int getHitHeadWhileOnFloor() {
      return this.hitHeadWhileOnFloor;
   }

   public String getRealState() {
      return this.realState.toString();
   }

   public void setHitHeadWhileOnFloor(int hitHeadWhileOnFloor) {
      this.hitHeadWhileOnFloor = hitHeadWhileOnFloor;
      NetworkZombieAI networkAi = this.getNetworkCharacterAI();
      networkAi.extraUpdate();
   }

   public boolean isHitLegsWhileOnFloor() {
      return this.hitLegsWhileOnFloor;
   }

   public void setHitLegsWhileOnFloor(boolean hitLegsWhileOnFloor) {
      this.hitLegsWhileOnFloor = hitLegsWhileOnFloor;
   }

   public void makeInactive(boolean binactive) {
      if (binactive != this.inactive) {
         if (binactive) {
            this.walkType = Integer.toString(Rand.Next(3) + 1);
            this.walkType = "slow" + this.walkType;
            this.running = false;
            this.inactive = true;
            this.speedType = 3;
            this.doZombieSpeed();
         } else {
            this.speedType = -1;
            this.inactive = false;
            this.DoZombieStats();
         }
      }
   }

   public float getFootstepVolume() {
      return this.footstepVolume;
   }

   public boolean isFacingTarget() {
      if (this.target == null) {
         return false;
      }

      if (GameClient.client && !this.isLocal() && this.isBumped()) {
         return false;
      }

      // pzopt: actionEvalParallel. This is the "isFacingTarget" animation variable, read by the action-context transition
      // conditions, which evaluate on the frame workers; the two vectors are thread-local scratch instead of the shared
      // static pair the class inherits (same arithmetic).
      Vector2[] pzoptScratch = pzoptFacingScratch.get();
      Vector2 pzoptTo = pzoptScratch[0];
      Vector2 pzoptLook = pzoptScratch[1];
      pzoptTo.set(this.target.getX() - this.getX(), this.target.getY() - this.getY()).normalize();
      if (pzoptTo.getLength() == 0.0F) {
         return true;
      }

      this.getLookVector(pzoptLook);
      float dot = Vector2.dot(pzoptTo.x, pzoptTo.y, pzoptLook.x, pzoptLook.y);
      return dot >= 0.8F;
   }

   private static final ThreadLocal<Vector2[]> pzoptFacingScratch = ThreadLocal.withInitial(() -> new Vector2[]{new Vector2(), new Vector2()}); // pzopt: actionEvalParallel

   public boolean isTargetLocationKnown() {
      if (this.target == null) {
         return false;
      } else {
         return this.bonusSpotTime > 0.0F ? true : this.timeSinceSeenFlesh < 1.0F;
      }
   }

   protected int getSandboxMemoryDuration() {
      int sandbox = SandboxOptions.instance.lore.memory.getValue();
      int memory = 160;
      if (this.inactive) {
         memory = 5;
      } else if (sandbox == 1) {
         memory = 250;
      } else if (sandbox == 3) {
         memory = 100;
      } else if (sandbox == 4) {
         memory = 5;
      }

      return memory * 5;
   }

   public boolean shouldDoFenceLunge() {
      if (!SandboxOptions.instance.lore.zombiesFenceLunge.getValue()) {
         return false;
      } else if (Rand.NextBool(3)) {
         return false;
      } else {
         IsoGameCharacter character = (IsoGameCharacter)Type.tryCastTo(this.target, IsoGameCharacter.class);
         if (character == null || PZMath.fastfloor(character.getZ()) != PZMath.fastfloor(this.getZ())) {
            return false;
         } else {
            return character.getVehicle() != null ? false : this.DistTo(character) < 3.9F;
         }
      }
   }

   public boolean isProne() {
      if (this.isOnFloor()) {
         return true;
      } else if (this.isCrawling()) {
         return true;
      } else if (this.isCurrentState(ZombieEatBodyState.instance())) {
         return true;
      } else if (this.isCurrentState(FakeDeadZombieState.instance())) {
         return true;
      } else {
         return this.isCurrentState(ZombieOnGroundState.instance()) ? true : this.isSitAgainstWall();
      }
   }

   public boolean isGettingUp() {
      return this.getCurrentState() == ZombieGetUpState.instance();
   }

   public void setTarget(IsoMovingObject t) {
      if (this.target != t) {
         this.target = t;
         NetworkZombieAI networkAi = this.getNetworkCharacterAI();
         networkAi.extraUpdate();
      }
   }

   public boolean isAlwaysKnockedDown() {
      return this.alwaysKnockedDown;
   }

   public void setAlwaysKnockedDown(boolean alwaysKnockedDown) {
      this.alwaysKnockedDown = alwaysKnockedDown;
   }

   public void setDressInRandomOutfit(boolean dressInRandom) {
      this.dressInRandomOutfit = dressInRandom;
   }

   public void setBodyToEat(IsoDeadBody body) {
      this.bodyToEat = body;
   }

   public boolean isForceEatingAnimation() {
      return this.forceEatingAnimation;
   }

   public void setForceEatingAnimation(boolean forceEatingAnimation) {
      this.forceEatingAnimation = forceEatingAnimation;
   }

   public boolean isOnlyJawStab() {
      return this.onlyJawStab;
   }

   public void setOnlyJawStab(boolean onlyJawStab) {
      this.onlyJawStab = onlyJawStab;
   }

   public boolean isNoTeeth() {
      return this.noTeeth;
   }

   public boolean cantBite() {
      if (this.getWornItem(ItemBodyLocation.MASK) != null && !this.getWornItem(ItemBodyLocation.MASK).hasTag(ItemTag.CAN_EAT)) {
         return true;
      }

      if (this.getWornItem(ItemBodyLocation.MASK_EYES) != null && !this.getWornItem(ItemBodyLocation.MASK_EYES).hasTag(ItemTag.CAN_EAT)) {
         return true;
      }

      if (this.getWornItem(ItemBodyLocation.MASK_FULL) != null && !this.getWornItem(ItemBodyLocation.MASK_FULL).hasTag(ItemTag.CAN_EAT)) {
         return true;
      }

      if (this.getWornItem(ItemBodyLocation.FULL_HAT) != null && !this.getWornItem(ItemBodyLocation.FULL_HAT).hasTag(ItemTag.CAN_EAT)) {
         return true;
      }

      if (this.getWornItem(ItemBodyLocation.FULL_SUIT_HEAD) != null && !this.getWornItem(ItemBodyLocation.FULL_SUIT_HEAD).hasTag(ItemTag.CAN_EAT)) {
         return true;
      }

      ItemVisuals tempItemVisuals = new ItemVisuals();
      this.getItemVisuals(tempItemVisuals);

      for (int i = 0; i < tempItemVisuals.size(); i++) {
         ItemVisual itemVisual = (ItemVisual)tempItemVisuals.get(i);
         Item scriptItem = itemVisual.getScriptItem();
         if (scriptItem != null && scriptItem.isItemType(ItemType.CLOTHING)) {
            if (scriptItem.isBodyLocation(ItemBodyLocation.MASK) && !scriptItem.hasTag(ItemTag.CAN_EAT)) {
               return true;
            }

            if (scriptItem.isBodyLocation(ItemBodyLocation.MASK_EYES) && !scriptItem.hasTag(ItemTag.CAN_EAT)) {
               return true;
            }

            if (scriptItem.isBodyLocation(ItemBodyLocation.MASK_FULL) && !scriptItem.hasTag(ItemTag.CAN_EAT)) {
               return true;
            }

            if (scriptItem.isBodyLocation(ItemBodyLocation.FULL_HAT) && !scriptItem.hasTag(ItemTag.CAN_EAT)) {
               return true;
            }

            if (scriptItem.isBodyLocation(ItemBodyLocation.FULL_SUIT_HEAD) && !scriptItem.hasTag(ItemTag.CAN_EAT)) {
               return true;
            }
         }
      }

      return this.isNoTeeth();
   }

   public void setNoTeeth(boolean noTeeth) {
      this.noTeeth = noTeeth;
   }

   public void setThumpFlag(int v) {
      if (this.thumpFlag != v) {
         this.thumpFlag = v;
         NetworkZombieAI networkAi = this.getNetworkCharacterAI();
         networkAi.extraUpdate();
      }
   }

   public void setThumpCondition(float condition) {
      this.thumpCondition = PZMath.clamp_01(condition);
   }

   public void setThumpCondition(int condition, int maxCondition) {
      if (maxCondition <= 0) {
         this.thumpCondition = 0.0F;
      } else {
         condition = PZMath.clamp(condition, 0, maxCondition);
         this.thumpCondition = (float)condition / maxCondition;
      }
   }

   public float getThumpCondition() {
      return this.thumpCondition;
   }

   public boolean isStaggerBack() {
      return this.staggerBack;
   }

   public void setStaggerBack(boolean bStaggerBack) {
      this.staggerBack = bStaggerBack;
   }

   public boolean isKnifeDeath() {
      return this.knifeDeath;
   }

   public void setKnifeDeath(boolean bKnifeDeath) {
      this.knifeDeath = bKnifeDeath;
   }

   public boolean isJawStabAttach() {
      return this.jawStabAttach;
   }

   public void setJawStabAttach(boolean bJawStabAttach) {
      this.jawStabAttach = bJawStabAttach;
   }

   public void onKilled(IsoGameCharacter killer, HandWeapon handWeapon, boolean bGory) {
      super.onKilled(killer, handWeapon, bGory);
      if (GameServer.server) {
         ConnectionQueueStatistic.getInstance().zombiesKilledToday.increase();
         if (this.isOnFire()) {
            ConnectionQueueStatistic.getInstance().zombiesKilledByFireToday.increase();
         }
      }

      if (!GameClient.client) {
         this.DoZombieInventory();
      }

      LuaEventManager.triggerEvent("OnZombieDead", this);
      if (killer == null) {
         this.DoDeath(null, null, bGory);
      } else if (handWeapon != null) {
         this.DoDeath(handWeapon, killer, bGory);
      } else if (killer.getPrimaryHandItem() instanceof HandWeapon) {
         this.DoDeath((HandWeapon)killer.getPrimaryHandItem(), killer, bGory);
      } else {
         this.DoDeath(this.getUseHandWeapon(), killer, bGory);
      }
   }

   private void onDied(IsoGameCharacter sender, IsoDeadBody body) {
      if (GameServer.server) {
         GameServer.sendCharacterDeath(body);
      }
   }

   public NetworkZombieAI getNetworkCharacterAI() {
      return (NetworkZombieAI)PZOptional.ifPresent(
         (NetworkZombieComponent)this.tryGetECSComponent(NetworkZombieComponent.class), (NetworkZombieAI)null, NetworkZombieComponent::getNetworkAI
      );
   }

   public boolean isSkipResolveCollision() {
      return super.isSkipResolveCollision()
         || this.isCurrentState(ZombieHitReactionState.instance())
         || this.isCurrentState(ZombieFallDownState.instance())
         || this.isCurrentState(ZombieOnGroundState.instance())
         || this.isCurrentState(StaggerBackState.instance());
   }

   private void updateVisionRadius() {
      ClimateManager cm = ClimateManager.getInstance();
      float rainPenalty = cm.getRainIntensity() * 2.5F;
      float fogPenalty = cm.getFogIntensity() * 7.0F;
      IsoPlayer character = (IsoPlayer)Type.tryCastTo(this.target, IsoPlayer.class);
      float darknessPenalty;
      if (character != null && character.getCurrentSquare() != null) {
         darknessPenalty = (1.0F - this.target.getCurrentSquare().getLightLevel(character.getIndex())) * 5.0F;
      } else {
         darknessPenalty = (1.0F - Math.min(cm.getDayLightStrength(), cm.getAmbient())) * 5.0F;
      }

      float visionRadiusAdjusted = this.getVisionRadiusAdjusted(darknessPenalty, rainPenalty, fogPenalty);
      this.visionRadiusResult = PZMath.clamp(visionRadiusAdjusted, 10.0F, 20.0F);
   }

   private float getVisionRadiusAdjusted(float darknessPenalty, float rainPenalty, float fogPenalty) {
      float visionRadiusAdjusted = 20.0F - Math.max(darknessPenalty, rainPenalty + fogPenalty);
      if (this.sight == 1 || SandboxOptions.instance.lore.sight.getValue() == 1) {
         visionRadiusAdjusted *= 1.75F;
      }

      if (this.sight == 3 || SandboxOptions.instance.lore.sight.getValue() == 3) {
         visionRadiusAdjusted *= 0.35F;
      }

      visionRadiusAdjusted /= this.getWornItemsVisionModifier();
      if (this.getEatBodyTarget() != null) {
         visionRadiusAdjusted *= 0.5F;
      }

      return visionRadiusAdjusted;
   }

   public boolean shouldZombieHaveKey(boolean allowBandits) {
      String outfitName = this.getOutfitName();
      boolean isKey = true;
      if (this.isSkeleton() && this.getHumanVisual() != null) {
         return false;
      }

      if (this.getOutfitName() == null) {
         return isKey;
      }

      if (outfitName.contains("Inmate")) {
         isKey = false;
      } else if (outfitName.contains("Backpacker")) {
         isKey = false;
      } else if (outfitName.contains("Evacuee") && !allowBandits) {
         isKey = false;
      } else if (outfitName.contains("Stripper")) {
         isKey = false;
      } else if (outfitName.contains("Naked")) {
         isKey = false;
      } else if (outfitName.equals("Bandit") && !allowBandits) {
         isKey = false;
      } else if (outfitName.equals("Bathrobe")) {
         isKey = false;
      } else if (outfitName.equals("Bedroom")) {
         isKey = false;
      } else if (outfitName.equals("Hobbo")) {
         isKey = false;
      } else if (outfitName.equals("HockeyPyscho")) {
         isKey = false;
      } else if (outfitName.equals("HospitalPatient")) {
         isKey = false;
      } else if (outfitName.equals("Swimmer")) {
         isKey = false;
      }

      return isKey;
   }

   private void checkZombieEntersPlayerBuilding() {
      IsoPlayer player = (IsoPlayer)Type.tryCastTo(this.target, IsoPlayer.class);
      if (player != null && player.isLocalPlayer()) {
         if (this.getCurrentSquare() != null && this.getCurrentSquare().isCanSee(player.playerIndex)) {
            IsoBuilding building = this.getCurrentBuilding();
            if (building != null) {
               IsoBuilding playerBuilding = player.getCurrentBuilding();
               if (building == playerBuilding) {
                  Object object = this.getMusicIntensityEventModData("ZombieEntersPlayerBuilding");
                  if (object == null) {
                     this.setMusicIntensityEventModData("ZombieEntersPlayerBuilding", GameTime.getInstance().getWorldAgeHours());
                     player.triggerMusicIntensityEvent("ZombieEntersPlayerBuilding");
                  }
               }
            }
         }
      }
   }

   public void doZombieSpeed() {
      this.doZombieSpeedInternal(this.speedType);
   }

   public void doZombieSpeed(int zombieSpeed) {
      this.doZombieSpeedInternal(zombieSpeed);
   }

   private void doZombieSpeedInternal(int zombieSpeed) {
      zombieSpeed = this.determineZombieSpeed(zombieSpeed);
      if (this.crawling) {
         this.doCrawlerSpeed(zombieSpeed);
      } else if (SandboxOptions.instance.lore.speed.getValue() == 3 || zombieSpeed == 3) {
         this.doShambler();
      } else if (Rand.Next(3) != 0) {
         this.doFakeShambler(zombieSpeed);
      } else if (SandboxOptions.instance.lore.speed.getValue() != 2 && zombieSpeed != 2) {
         if (SandboxOptions.instance.lore.speed.getValue() == 1 || zombieSpeed == 1) {
            this.doSprinter();
         }
      } else {
         this.doFastShambler();
      }
   }

   public void doCrawlerSpeed(int zombieSpeed) {
      this.speedMod = 0.3F;
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.def.animFrameIncrease *= 0.8F;
      this.doZombieSpeedInternal2(zombieSpeed);
   }

   public void doSprinter() {
      this.lunger = true;
      this.speedMod = 0.85F;
      this.walkVariant = "ZombieWalk2";
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.def.setFrameSpeedPerFrame(0.24F);
      this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      this.speedType = 1;
      this.doZombieSpeedInternal2();
   }

   public void doFastShambler() {
      this.lunger = true;
      this.speedMod = 0.85F;
      this.walkVariant = "ZombieWalk2";
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.def.setFrameSpeedPerFrame(0.24F);
      this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      this.speedType = 2;
      this.doZombieSpeedInternal2();
   }

   private void doFakeShambler() {
      this.speedMod = 0.55F;
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.walkVariant = "ZombieWalk1";
      this.def.setFrameSpeedPerFrame(0.24F);
      this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      this.doZombieSpeedInternal2();
   }

   public void doFakeShambler(int zombieSpeed) {
      this.speedMod = 0.55F;
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.walkVariant = "ZombieWalk1";
      this.def.setFrameSpeedPerFrame(0.24F);
      this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      this.doZombieSpeedInternal2(zombieSpeed);
   }

   public void doShambler() {
      this.speedMod = 0.55F;
      this.speedMod = this.speedMod + Rand.Next(1500) / 10000.0F;
      this.walkVariant = "ZombieWalk1";
      this.def.setFrameSpeedPerFrame(0.24F);
      this.def.animFrameIncrease = this.def.animFrameIncrease * this.speedMod;
      this.speedType = 3;
      this.doZombieSpeedInternal2();
   }

   public int getSpeedType() {
      return this.speedType;
   }

   private void doZombieSpeedInternal2() {
      this.doZombieSpeedInternal2(this.speedType);
   }

   private void doZombieSpeedInternal2(int zombieSpeed) {
      if (zombieSpeed == 3) {
         this.walkType = Integer.toString(Rand.Next(3) + 1);
         this.walkType = "slow" + this.walkType;
      } else {
         this.walkType = Integer.toString(Rand.Next(5) + 1);
      }

      if (zombieSpeed == 1) {
         this.setTurnDelta(1.0F);
         this.walkType = "sprint" + this.walkType;
      }

      this.speedType = zombieSpeed;
   }

   private int determineZombieSpeed(int zombieSpeed) {
      if (zombieSpeed != -1) {
         return zombieSpeed;
      }

      zombieSpeed = SandboxOptions.instance.lore.speed.getValue();
      if (this.inactive) {
         zombieSpeed = 3;
      } else if (zombieSpeed == 4) {
         if (Rand.Next(100) < SandboxOptions.instance.lore.sprinterPercentage.getValue()) {
            zombieSpeed = 1;
         } else {
            zombieSpeed = Rand.Next(2) + 2;
         }
      }

      return zombieSpeed;
   }

   public String getLastHitPart() {
      return this.lastHitPart == null ? null : this.lastHitPart.toString();
   }

   public boolean shouldDressInRandomOutfit() {
      return this.dressInRandomOutfit;
   }

   public String getOutfitName() {
      if (this.shouldDressInRandomOutfit()) {
         ModelManager.instance.dressInRandomOutfit(this);
      }

      if (this.getHumanVisual() != null) {
         HumanVisual humanVisual = this.getHumanVisual();
         Outfit outfit = humanVisual.getOutfit();
         return outfit == null ? null : outfit.name;
      } else {
         return null;
      }
   }

   public IsoGridSquare getHeadSquare(IsoPlayer player) {
      if (!this.isCurrentState(ClimbThroughWindowState.instance())) {
         return null;
      }

      if (!this.hasAnimationPlayer()) {
         return null;
      }

      if (this.DistToSquared(player) > 10.0F) {
         return null;
      }

      AnimationPlayer animationPlayer = this.getAnimationPlayer();
      if (!animationPlayer.isReady()) {
         return null;
      }

      int playerIndex = player.getIndex();
      float x = this.getX();
      float y = this.getY();
      float z = this.getZ();
      Vector3 v = new Vector3();
      float animalSize = 1.0F;
      Model.boneToWorldCoords(animationPlayer, x, y, z, 1.0F, animationPlayer.getSkinningBoneIndex("Bip01_Head", -1), v);
      return this.getCell().getChunkMap(playerIndex).getGridSquare(PZMath.fastfloor(v.x), PZMath.fastfloor(v.y), PZMath.fastfloor(this.getZ()));
   }

   /**
    * pzopt: entityUpdateSafeStates. Whether this zombie's whole update may run on a frame worker (entityUpdateParallel):
    * a calm state (idle, walking toward a point, following a path), nothing that calls Bullet (a ballistics target, a
    * ragdoll), not falling (the slope and landing paths), not burning, not grappled, no animation player about to be
    * replaced, and no player within 12 squares (the lunge, attack and hit exchanges with a player happen there). Read on
    * the game thread before the batch; everything else updates inline as stock.
    */
   public boolean pzoptBatchSafe() { // pzopt
      if (this.getBallisticsTarget() != null || this.isRagdoll() || this.isFalling() || this.isOnFire() || this.isBeingGrappled() || this.isGrappling() // pzopt
         || this.getReanimatedPlayer() != null || this.pzoptAnimPlayerStale() || !this.pzoptRagdollFree()) { // pzopt
         return false; // pzopt
      } // pzopt
      zombie.ai.State state = this.getCurrentState(); // pzopt
      if (state != ZombieIdleState.instance() && state != WalkTowardState.instance() && state != PathFindState.instance()) { // pzopt
         return false; // pzopt
      } // pzopt
      for (int i = 0; i < IsoPlayer.numPlayers; i++) { // pzopt
         IsoPlayer player = IsoPlayer.players[i]; // pzopt
         if (player != null && this.DistToSquared(player) < 144.0F) { // pzopt
            return false; // pzopt
         } // pzopt
      } // pzopt
      return true; // pzopt
   } // pzopt

   public boolean couldSeeHeadSquare(IsoPlayer player) {
      int playerIndex = player.getIndex();
      IsoGridSquare headSquare = this.getHeadSquare(player);
      return headSquare != null && headSquare.isCouldSee(playerIndex);
   }

   public boolean canSeeHeadSquare(IsoPlayer player) {
      int playerIndex = player.getIndex();
      IsoGridSquare headSquare = this.getHeadSquare(player);
      return headSquare != null && headSquare.isCanSee(playerIndex);
   }

   private boolean isSideOfStaircaseBetweenSelfAndTarget() {
      if (this.target == null) {
         return false;
      } else {
         IsoGridSquare currentSquare = this.getCurrentSquare();
         if (currentSquare == null) {
            return false;
         } else {
            IsoGridSquare targetCurrentSquare = this.target.getCurrentSquare();
            if (targetCurrentSquare == null) {
               return false;
            } else {
               return currentSquare.getX() != targetCurrentSquare.getX() - 1 && currentSquare.getX() != targetCurrentSquare.getX() + 1
                     || !currentSquare.HasStairsNorth() && !targetCurrentSquare.HasStairsNorth()
                  ? (currentSquare.getY() == targetCurrentSquare.getY() - 1 || currentSquare.getY() == targetCurrentSquare.getY() + 1)
                     && (currentSquare.HasStairsWest() || targetCurrentSquare.HasStairsWest())
                  : true;
            }
         }
      }
   }

   private void updateMovementStatistics() {
      lastPosition.set(this.getLastX(), this.getLastY());
      currentPosition.set(this.getX(), this.getY());
      float distanceTraveled = PZMath.clamp(lastPosition.distanceTo(currentPosition), 0.0F, 0.5F);
      String statisticName = "Zombie's Distance Walked";
      if (this.crawling) {
         statisticName = "Zombie's Distance Crawled";
      } else if (this.running) {
         statisticName = "Zombie's Distance Ran";
      }

      if (pzopt.ZombieStats.add(statisticName, distanceTraveled)) { // pzopt: zombieStatsFold, the same float additions in the same order, written back after the loop
         return; // pzopt
      } // pzopt
      StatisticsManager.getInstance().incrementStatistic(StatisticType.Zombie, StatisticCategory.Travel, statisticName, distanceTraveled);
   }

   public String getWalkType() {
      return this.walkType;
   }

   public boolean helmetFallFromVisuals(boolean hitHead) {
      boolean removed = false;
      // pzopt: entityUpdateParallel. The only user of IsoGameCharacter's shared static scratch buffer outside that
      // class; it is one ItemVisuals per thread there now (pzoptTempItemVisuals), so this reads its own.
      ItemVisuals tempItemVisuals = pzoptTempItemVisuals.get(); // pzopt: entityUpdateParallel
      this.getItemVisuals(tempItemVisuals);

      for (int i = 0; i < tempItemVisuals.size(); i++) {
         ItemVisual itemVisual = (ItemVisual)tempItemVisuals.get(i);
         Item scriptItem = itemVisual.getScriptItem();
         if (scriptItem != null && scriptItem.isItemType(ItemType.CLOTHING) && scriptItem.getChanceToFall() > 0) {
            int chanceToFall = scriptItem.getChanceToFall();
            if (hitHead) {
               chanceToFall += 40;
            }

            if (Rand.Next(100) > chanceToFall) {
               InventoryItem item = InventoryItemFactory.CreateItem(scriptItem.getFullName());
               if (item != null) {
                  if (item.getVisual() != null) {
                     item.getVisual().copyFrom(itemVisual);
                     item.synchWithVisual();
                  }

                  tempItemVisuals.remove(i--);
                  this.itemVisuals.clear();
                  this.itemVisuals.addAll(tempItemVisuals);
                  this.resetModelNextFrame();
                  this.onWornItemsChanged();
                  removed = true;
                  this.createFallingItem(item);
                  if (GameServer.server) {
                     break;
                  }
               }
            }
         }
      }

      return removed;
   }

   private static class Aggro {
      private final IsoMovingObject obj;
      private float damage;
      private long lastDamage;

      public Aggro(IsoMovingObject obj, float damage) {
         this.obj = obj;
         this.damage = damage;
         this.lastDamage = System.currentTimeMillis();
      }

      public void addDamage(float damage) {
         this.damage += damage;
         this.lastDamage = System.currentTimeMillis();
      }

      public float getAggro() {
         float dt = (float)(System.currentTimeMillis() - this.lastDamage);
         float aggro = Math.min(1.0F, Math.max(0.0F, (10000.0F - dt) / 5000.0F));
         return Math.min(1.0F, Math.max(0.0F, aggro * this.damage * 0.5F));
      }
   }

   private static final class FloodFill {
      private IsoGridSquare start;
      private static final int FLOOD_SIZE = 11;
      private final BooleanGrid visited = new BooleanGrid(11, 11);
      private final Stack<IsoGridSquare> stack = new Stack<>();
      private IsoBuilding building;
      private Mover mover;
      private final ArrayList<IsoGridSquare> choices = new ArrayList<>(121);

      private void calculate(Mover mv, IsoGridSquare sq) {
         this.start = sq;
         this.mover = mv;
         if (this.start.getRoom() != null) {
            this.building = this.start.getRoom().getBuilding();
         }

         this.push(this.start.getX(), this.start.getY());

         while ((sq = this.pop()) != null) {
            int x = sq.getX();
            int y1 = sq.getY();

            while (this.shouldVisit(x, y1, x, y1 - 1)) {
               y1--;
            }

            boolean spanRight = false;
            boolean spanLeft = false;

            do {
               this.visited.setValue(this.gridX(x), this.gridY(y1), true);
               IsoGridSquare sq2 = IsoWorld.instance.currentCell.getGridSquare(x, y1, this.start.getZ());
               if (sq2 != null) {
                  this.choices.add(sq2);
               }

               if (!spanLeft && this.shouldVisit(x, y1, x - 1, y1)) {
                  this.push(x - 1, y1);
                  spanLeft = true;
               } else if (spanLeft && !this.shouldVisit(x, y1, x - 1, y1)) {
                  spanLeft = false;
               } else if (spanLeft && !this.shouldVisit(x - 1, y1, x - 1, y1 - 1)) {
                  this.push(x - 1, y1);
               }

               if (!spanRight && this.shouldVisit(x, y1, x + 1, y1)) {
                  this.push(x + 1, y1);
                  spanRight = true;
               } else if (spanRight && !this.shouldVisit(x, y1, x + 1, y1)) {
                  spanRight = false;
               } else if (spanRight && !this.shouldVisit(x + 1, y1, x + 1, y1 - 1)) {
                  this.push(x + 1, y1);
               }

               y1++;
            } while (this.shouldVisit(x, y1 - 1, x, y1));
         }
      }

      private boolean shouldVisit(int x1, int y1, int x2, int y2) {
         if (this.gridX(x2) < 11 && this.gridX(x2) >= 0) {
            if (this.gridY(y2) < 11 && this.gridY(y2) >= 0) {
               if (this.visited.getValue(this.gridX(x2), this.gridY(y2))) {
                  return false;
               } else {
                  IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x2, y2, this.start.getZ());
                  if (sq == null) {
                     return false;
                  } else if (sq.has(IsoObjectType.stairsBN) || sq.has(IsoObjectType.stairsMN) || sq.has(IsoObjectType.stairsTN)) {
                     return false;
                  } else if (sq.has(IsoObjectType.stairsBW) || sq.has(IsoObjectType.stairsMW) || sq.has(IsoObjectType.stairsTW)) {
                     return false;
                  } else if (sq.getRoom() != null && this.building == null) {
                     return false;
                  } else {
                     return sq.getRoom() == null && this.building != null
                        ? false
                        : !IsoWorld.instance.currentCell.blocked(this.mover, x2, y2, this.start.getZ(), x1, y1, this.start.getZ());
                  }
               }
            } else {
               return false;
            }
         } else {
            return false;
         }
      }

      private void push(int x, int y) {
         IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, this.start.getZ());
         this.stack.push(sq);
      }

      private IsoGridSquare pop() {
         return this.stack.isEmpty() ? null : this.stack.pop();
      }

      private int gridX(int x) {
         return x - (this.start.getX() - 5);
      }

      private int gridY(int y) {
         return y - (this.start.getY() - 5);
      }

      private int gridX(IsoGridSquare sq) {
         return sq.getX() - (this.start.getX() - 5);
      }

      private int gridY(IsoGridSquare sq) {
         return sq.getY() - (this.start.getY() - 5);
      }

      private IsoGridSquare choose() {
         if (this.choices.isEmpty()) {
            return null;
         }

         int n = Rand.Next(this.choices.size());
         return this.choices.get(n);
      }

      private void reset() {
         this.building = null;
         this.choices.clear();
         this.stack.clear();
         this.visited.clear();
      }
   }

   public enum ZombieSound {
      Burned(10),
      DeadCloseKilled(10),
      DeadNotCloseKilled(10),
      Hurt(10),
      Idle(15),
      Lunge(40),
      MAX(-1);

      private final int radius;
      private static final IsoZombie.ZombieSound[] values = values();

      ZombieSound(final int radius) {
         this.radius = radius;
      }

      public int radius() {
         return this.radius;
      }

      public static IsoZombie.ZombieSound fromIndex(int index) {
         return index >= 0 && index < values.length ? values[index] : MAX;
      }
   }

   // pzopt: ecsLookupFast. IsoGameCharacter reaches its StateMachineComponent through the entity-component map on every
   // getStateMachine / getActionContext / getCurrentState / isCurrentState / getVariable (a class walk, a HashMap probe and
   // a reflective cast per call; ~5 % of the game thread on the Louisville horde, most of it under the action-context
   // transition evaluation and the AI state checks). The component is registered once in the constructor and never
   // replaced, so a zombie keeps it in a field after the first lookup and the accessors become field reads. The field has
   // no initialiser on purpose: the IsoGameCharacter constructor already calls these accessors before this class's field
   // initialisers run, and an initialiser would wipe the cached value afterwards.
   private StateMachineComponent pzoptStateMachineComponent;

   private StateMachineComponent pzoptStateMachineComponent() {
      StateMachineComponent component = this.pzoptStateMachineComponent;
      if (component == null) {
         component = this.getStateMachineComponent();
         this.pzoptStateMachineComponent = component;
      }

      return component;
   }

   private static boolean pzoptEcsFast() {
      return pzopt.Overrides.enabled() && pzopt.Config.ECS_LOOKUP_FAST;
   }

   // pzopt: actionGroupCache. updateInternal asks ActionGroup for the "zombie" and "zombie-crawler" groups by name on
   // every zombie on every frame; the lookup lower-cases the name into a new String and probes a HashMap for a group
   // that is loaded once at startup and never replaced. The two groups are resolved here on first use instead.
   private static ActionGroup pzoptZombieGroup;
   private static ActionGroup pzoptCrawlerGroup;

   private static ActionGroup pzoptGroup(boolean crawler) {
      if (!pzopt.Config.ACTION_GROUP_CACHE || !pzopt.Overrides.enabled()) {
         return ActionGroup.getActionGroup(crawler ? "zombie-crawler" : "zombie");
      }

      if (crawler) {
         if (pzoptCrawlerGroup == null) {
            pzoptCrawlerGroup = ActionGroup.getActionGroup("zombie-crawler");
         }

         return pzoptCrawlerGroup;
      }

      if (pzoptZombieGroup == null) {
         pzoptZombieGroup = ActionGroup.getActionGroup("zombie");
      }

      return pzoptZombieGroup;
   }

   // pzopt: stateParamMemo. Every State.Param read (the AI states keep their per-character scratch there) goes through
   // getStateMachineParams(state class), which probes the entity-component map and then an IdentityHashMap keyed by the
   // class, before the param's own map probe; a state's execute() reads several params of the same class in a row.
   // The component is created once per zombie and its per-class map object is only ever cleared, never replaced, so the
   // last (class, map) pair is an exact memo. No field initialiser: the IsoGameCharacter constructor runs these
   // accessors before this class's initialisers would.
   private Class<?> pzoptParamClass;
   private Map<State.Param<?>, Object> pzoptParamMap;

   @Override
   public Map<State.Param<?>, Object> getStateMachineParams(Class<?> clazz) {
      if (!pzoptEcsFast() || !pzopt.Config.STATE_PARAM_MEMO) {
         return super.getStateMachineParams(clazz);
      }

      Map<State.Param<?>, Object> map = this.pzoptParamMap;
      if (map != null && this.pzoptParamClass == clazz) {
         return map;
      }

      map = this.pzoptStateMachineComponent().getStateMachineParams(clazz);
      this.pzoptParamClass = clazz;
      this.pzoptParamMap = map;
      return map;
   }

   @Override
   public ActionContext getActionContext() {
      return pzoptEcsFast() ? this.pzoptStateMachineComponent().getActionContext() : super.getActionContext(); // pzopt: ecsLookupFast
   }

   @Override
   public StateMachine getStateMachine() {
      return pzoptEcsFast() ? this.pzoptStateMachineComponent().getStateMachine() : super.getStateMachine(); // pzopt: ecsLookupFast
   }

   @Override
   public AdvancedAnimator getAdvancedAnimator() {
      return pzoptEcsFast() ? this.pzoptStateMachineComponent().getAdvancedAnimator() : super.getAdvancedAnimator(); // pzopt: ecsLookupFast
   }

   @Override
   public State getCurrentState() {
      return pzoptEcsFast() ? this.pzoptStateMachineComponent().getStateMachine().getCurrent() : super.getCurrentState(); // pzopt: ecsLookupFast
   }

   @Override
   public boolean isCurrentState(State state) {
      if (!pzoptEcsFast()) {
         return super.isCurrentState(state);
      }

      StateMachine stateMachine = this.pzoptStateMachineComponent().getStateMachine(); // pzopt: ecsLookupFast, one lookup instead of two
      return stateMachine.isSubstate(state) || stateMachine.getCurrent() == state;
   }

   @Override
   public IAnimationVariableSlot getVariable(AnimationVariableHandle handle) {
      if (pzoptEcsFast()) {
         // pzopt: ecsLookupFast, the action context from the field; the game-variable fallback is the inherited method
         ActionContext actionContext = this.pzoptStateMachineComponent().getActionContext();
         if (actionContext != null) {
            IAnimationVariableSlot actionSlot = actionContext.getVariable(handle);
            if (actionSlot != null) {
               return actionSlot;
            }
         }
      }

      return super.getVariable(handle);
   }

   // pzopt: shadowPrep. The shadow ellipse computed on the worker right after this zombie's bones (AnimationPlayer
   // override, pzopt.ShadowPrep) replaces the game-thread computation in renderShadow while the bones have not moved;
   // a zombie whose update did not go through the batch this frame (or whose animation did not update) takes the stock path.
   @Override
   public ShadowParams calculateShadowParams(ShadowParams sp) {
      if (pzopt.Overrides.enabled() && pzopt.Config.SHADOW_PREP && this.hasAnimationPlayer()) {
         AnimationPlayer animationPlayer = this.getAnimationPlayer();
         long packed = animationPlayer.pzoptShadowParams();
         if (packed != 0L && animationPlayer.isReady()) {
            pzopt.ShadowPrep.served++;
            return sp.set(0.45F, pzopt.ShadowPrep.fm(packed), pzopt.ShadowPrep.bm(packed));
         }

         pzopt.ShadowPrep.fallback++;
      }

      return super.calculateShadowParams(sp);
   }

   private static class s_performance {
      private static final PerformanceProfileProbe update = new PerformanceProfileProbe("IsoZombie.update");
      private static final PerformanceProfileProbe postUpdate = new PerformanceProfileProbe("IsoZombie.postUpdate");
   }
}
