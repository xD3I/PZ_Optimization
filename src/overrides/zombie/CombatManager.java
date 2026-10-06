package zombie;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.util.vector.Matrix4f;
import zombie.Lua.LuaEventManager;
import zombie.ai.states.AttackState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.FakeDeadZombieState;
import zombie.ai.states.FishingState;
import zombie.ai.states.PlayerHitReactionPVPState;
import zombie.ai.states.PlayerHitReactionState;
import zombie.ai.states.SwipeStatePlayer;
import zombie.ai.states.ZombieGetUpState;
import zombie.ai.states.ZombieOnGroundState;
import zombie.audio.parameters.ParameterMeleeHitSurface.Material;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.CharacterStat;
import zombie.characters.Faction;
import zombie.characters.HitReactionNetworkAI;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoLivingCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.skills.PerkFactory.Perks;
import zombie.combat.CombatConfig;
import zombie.combat.CombatConfigKey;
import zombie.combat.HitReaction;
import zombie.combat.MeleeTargetComparator;
import zombie.combat.RangeTargetComparator;
import zombie.combat.Rect3D;
import zombie.combat.ShotDirection;
import zombie.combat.TargetComparator;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.physics.BallisticsController;
import zombie.core.physics.BallisticsTarget;
import zombie.core.physics.RagdollBodyPart;
import zombie.core.physics.RagdollSettingsManager;
import zombie.core.physics.BallisticsTarget.CombatDamageData;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.textures.ColorInfo;
import zombie.debug.BaseDebugWindow;
import zombie.debug.DebugContext;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.debug.debugWindows.TargetHitInfoPanel;
import zombie.entity.components.combat.Durability;
import zombie.entity.util.TimSort;
import zombie.input.GameKeyboard;
import zombie.input.KeybindId;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Clothing;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoGridSquareCollisionData;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;
import zombie.iso.Vector2ObjectPool;
import zombie.iso.Vector3;
import zombie.iso.IsoObject.VisionResult;
import zombie.iso.LosUtil.TestResults;
import zombie.iso.areas.NonPvpZone;
import zombie.iso.enums.MaterialType;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoBulletTracerEffects;
import zombie.iso.objects.IsoCompost;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTrap;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.IsoBulletTracerEffects.Effect;
import zombie.iso.objects.IsoZombieGiblets.GibletType;
import zombie.iso.objects.interfaces.Thumpable;
import zombie.iso.sprite.IsoReticle;
import zombie.iso.sprite.IsoReticleMode;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.network.PacketTypes.PacketType;
import zombie.network.fields.hit.AttackVars;
import zombie.network.fields.hit.HitInfo;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.packets.INetworkPacket;
import zombie.pathfind.PolygonalMap2;
import zombie.popman.ObjectPool;
import zombie.scripting.objects.CharacterTrait;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.MoodleType;
import zombie.scripting.objects.WeaponCategory;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;
import zombie.statistics.StatisticsManager;
import zombie.ui.MoodlesUI;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.lambda.PZOptional;
import zombie.util.list.PZArrayList;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.BaseVehicle.Vector3fObjectPool;

public final class CombatManager {
   public static final int MIN_MULTI_HIT_COUNT = 3;
   private static final int POINT_BLANK_AIM_PENALTY_BONUS = 5;
   private static final float POINT_BLANK_HIT_CHANCE_BONUS = 1.1F;
   private final CombatConfig combatConfig = new CombatConfig();
   private static final ArrayList<HitInfo> HitList2 = new ArrayList<>();
   public final ObjectPool<HitInfo> hitInfoPool = new ObjectPool(HitInfo::new, "CombatManager.hitInfoPool");
   private static final int MINIMUM_WEAPON_LEVEL = 0;
   private static final int MAXIMUM_WEAPON_LEVEL = 10;
   private static final float BallisticsTargetsHighlightAlpha = 0.65F;
   private static final float MeleeTargetsHighlightAlpha = 1.0F;
   private static final float VehicleDamageScaleFactor = 50.0F;
   public static final int StrengthLevelOffset = 15;
   public static final float StrengthLevelMuscleStrainModifier = 10.0F;
   public static final float TwoHandedWeaponMuscleStrainModifier = 0.5F;
   private static final float PainThreshold = 10.0F;
   private static final float MinPainFactor = 1.0F;
   private static final float MaxPainFactor = 30.0F;
   private static final int StressLevelDamageReductionThreshold = 1;
   private static final int PanicLevelDamageReductionThreshold = 1;
   private static final float PanicLevelDamageSplitModifier = 0.1F;
   private static final float StressLevelDamageSplitModifier = 0.1F;
   private static final float MinBaseDamageSplitModifier = 0.1F;
   private static final float MinDamageSplit = 0.7F;
   private static final float MaxDamageSplit = 1.0F;
   private static final float StrengthPerkStompModifier = 0.2F;
   private static final float NoShoesDamageSplitModifier = 0.5F;
   private static final float EnduranceLevel1DamageSplitModifier = 0.5F;
   private static final float EnduranceLevel2DamageSplitModifier = 0.2F;
   private static final float EnduranceLevel3DamageSplitModifier = 0.1F;
   private static final float EnduranceLevel4DamageSplitModifier = 0.05F;
   private static final float TiredLevel1DamageSplitModifier = 0.5F;
   private static final float TiredLevel2DamageSplitModifier = 0.2F;
   private static final float TiredLevel3DamageSplitModifier = 0.1F;
   private static final float TiredLevel4DamageSplitModifier = 0.05F;
   private static final float BaseBodyPartClothingDefenseModifier = 0.5F;
   private static final int ZombieMaxDefense = 100;
   private static final int AxeVsTreeBonusModifier = 2;
   private static final float UseChargeDelta = 3.0F;
   private static final float CriticalHitSpeedMultiplier = 1.1F;
   private static final float BreakMultiplierBase = 1.0F;
   private static final float BreakMultiplierChargeModifier = 1.5F;
   private static final float MinAngleFloorModifier = 1.5F;
   private static final Integer PARAM_LOWER_CONDITION = 0;
   private static final Integer PARAM_ATTACKED = 1;
   private boolean hitOnlyTree;
   private IsoTree treeHit;
   private IsoObject objHit;
   private final ArrayList<Float> dotList = new ArrayList<>();
   private static final Vector3 tempVector3_1 = new Vector3();
   private static final Vector3 tempVector3_2 = new Vector3();
   private static final Vector2 tempVector2_1 = new Vector2();
   private static final Vector2 tempVector2_2 = new Vector2();
   private final Vector4f tempVector4f = new Vector4f();
   private static final Vector3 tempVectorBonePos = new Vector3();
   private static final float DefaultMaintenanceXP = 1.0F;
   private static final int ConditionLowerChance = 10;
   private static float ballisticsSpreadAngleScale = 1.0F;
   private static final Vector3 ballisticsDirectionVector = new Vector3();
   private static final Vector3 ballisticsStartPosition = new Vector3();
   private static final Vector3 ballisticsEndPosition = new Vector3();
   private static final String BreakLightBulbSound = "SmashWindow";
   private static final Color OccludedTargetDebugColor = Color.white;
   private static final Color TargetableDebugColor = Color.green;
   private static final float TargetDebugAlpha = 1.0F;
   private static final float VehicleTargetDebugRadius = 1.5F;
   private static final float CharacterTargetDebugRadius = 0.1F;
   private final Rect3D rect0 = new Rect3D();
   private final Rect3D rect1 = new Rect3D();
   private final MeleeTargetComparator meleeTargetComparator = new MeleeTargetComparator();
   private final RangeTargetComparator rangeTargetComparator = new RangeTargetComparator();
   private final CombatManager.WindowVisitor windowVisitor = new CombatManager.WindowVisitor();
   private final TimSort timSort = new TimSort();
   private final List<TracerInfo> tracers = new ArrayList<>();

   public CombatConfig getCombatConfig() {
      return this.combatConfig;
   }

   public static CombatManager getInstance() {
      return CombatManager.Holder.instance;
   }

   private CombatManager() {
   }

   public float calculateDamageToVehicle(IsoGameCharacter isoGameCharacter, float vehicleDurability, float damage, int doorDamage) {
      if (vehicleDurability == 0.0F) {
         return doorDamage;
      } else {
         return !(damage <= 0.0F) && doorDamage != 0 ? damage * 50.0F * (doorDamage / 10.0F) / vehicleDurability : 0.0F;
      }
   }

   private void setParameterCharacterHitResult(IsoGameCharacter owner, IsoZombie zombie, long zombieHitSound) {
      if (zombieHitSound != 0L) {
         int hitResult = 0;
         if (zombie != null) {
            if (zombie.isDead()) {
               hitResult = 2;
            } else if (zombie.isKnockedDown()) {
               hitResult = 1;
            }
         }

         owner.getEmitter().setParameterValueByName(zombieHitSound, "CharacterHitResult", hitResult);
      }
   }

   public HandWeapon getWeapon(IsoGameCharacter owner) {
      return owner.getAttackingWeapon();
   }

   private TestResults getResultLOS(IsoGridSquare square, IsoGameCharacter chr) {
      return LosUtil.lineClear(
         chr.getCell(),
         PZMath.fastfloor(square.getX()),
         PZMath.fastfloor(square.getY()),
         PZMath.fastfloor(square.getZ()),
         PZMath.fastfloor(chr.getX()),
         PZMath.fastfloor(chr.getY()),
         PZMath.fastfloor(chr.getZ()),
         false
      );
   }

   private boolean checkObjectHit(IsoGameCharacter owner, HandWeapon weapon, IsoGridSquare square, boolean north, boolean west) {
      if (square == null) {
         return false;
      }

      TestResults testResultsLOS = this.getResultLOS(square, owner);
      boolean canHitObject = testResultsLOS == TestResults.Clear;
      boolean canHitDoor = canHitObject || testResultsLOS == TestResults.ClearThroughClosedDoor;
      boolean canHitWindow = canHitObject || testResultsLOS == TestResults.ClearThroughWindow;
      boolean canHitBacksideOfWall = testResultsLOS == TestResults.Blocked && square.isAdjacentTo(owner.getSquare());

      for (int n = square.getSpecialObjects().size() - 1; n >= 0; n--) {
         IsoObject special = (IsoObject)square.getSpecialObjects().get(n);
         IsoDoor door = (IsoDoor)Type.tryCastTo(special, IsoDoor.class);
         IsoThumpable thumpable = (IsoThumpable)Type.tryCastTo(special, IsoThumpable.class);
         IsoWindow window = (IsoWindow)Type.tryCastTo(special, IsoWindow.class);
         IsoCompost compost = (IsoCompost)Type.tryCastTo(special, IsoCompost.class);
         if (door != null && (north && door.north || west && !door.north)) {
            Thumpable thumpable1 = door.getThumpableFor(owner);
            boolean canHitOtherSide = owner.getCurrentSquare() == door.getOppositeSquare();
            if (thumpable1 != null && (canHitDoor || canHitOtherSide)) {
               thumpable1.WeaponHit(owner, weapon);
               this.objHit = door;
               return true;
            }
         }

         if (thumpable != null) {
            if (!thumpable.isDoor() && !thumpable.isWindow() && !thumpable.isWall() && thumpable.isBlockAllTheSquare()) {
               Thumpable thumpable1 = thumpable.getThumpableFor(owner);
               if (thumpable1 != null && canHitObject) {
                  thumpable1.WeaponHit(owner, weapon);
                  this.objHit = thumpable;
                  return true;
               }
            } else if (north && thumpable.north || west && !thumpable.north) {
               Thumpable thumpable1 = thumpable.getThumpableFor(owner, weapon);
               boolean canHitOtherSide = owner.getCurrentSquare() == thumpable.getOppositeSquare();
               if (thumpable1 != null && (canHitDoor || canHitOtherSide || canHitWindow || thumpable.isWall() && canHitBacksideOfWall)) {
                  thumpable1.WeaponHit(owner, weapon);
                  this.objHit = thumpable;
                  return true;
               }
            }
         }

         if (window != null && (north && window.isNorth() || west && !window.isNorth())) {
            Thumpable thumpable1 = window.getThumpableFor(owner, weapon);
            if (thumpable1 != null && canHitWindow) {
               thumpable1.WeaponHit(owner, weapon);
               this.objHit = window;
               return true;
            }
         }

         if (compost != null) {
            Thumpable thumpable1 = compost.getThumpableFor(owner);
            if (thumpable1 != null && canHitObject) {
               thumpable1.WeaponHit(owner, weapon);
               this.objHit = compost;
               return true;
            }
         }
      }

      return false;
   }

   private boolean CheckObjectHit(IsoGameCharacter owner, HandWeapon weapon) {
      this.objHit = null;
      this.treeHit = null;
      this.hitOnlyTree = false;
      if (owner.isAimAtFloor()) {
         return false;
      }

      boolean hit = false;
      int hitCount = 0;
      int hitTreeCount = 0;
      IsoDirections dir = IsoDirections.fromAngle(owner.getForwardDirection());
      int x = 0;
      int y = 0;
      if (dir == IsoDirections.NE || dir == IsoDirections.N || dir == IsoDirections.NW) {
         y--;
      }

      if (dir == IsoDirections.SE || dir == IsoDirections.S || dir == IsoDirections.SW) {
         y++;
      }

      if (dir == IsoDirections.NW || dir == IsoDirections.W || dir == IsoDirections.SW) {
         x--;
      }

      if (dir == IsoDirections.NE || dir == IsoDirections.E || dir == IsoDirections.SE) {
         x++;
      }

      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare playerSq = owner.getCurrentSquare();
      IsoGridSquare next = cell.getGridSquare(playerSq.getX() + x, playerSq.getY() + y, playerSq.getZ());
      if (next != null) {
         if (this.checkObjectHit(owner, weapon, next, false, false)) {
            hit = true;
            hitCount++;
         }

         if (!next.isBlockedTo(playerSq)) {
            for (int n = 0; n < next.getObjects().size(); n++) {
               IsoObject object = (IsoObject)next.getObjects().get(n);
               if (object instanceof IsoTree isoTree) {
                  this.treeHit = isoTree;
                  hit = true;
                  hitCount++;
                  hitTreeCount++;
                  if (object.getObjectIndex() == -1) {
                     n--;
                  }
               }
            }
         }
      }

      if ((dir == IsoDirections.NE || dir == IsoDirections.N || dir == IsoDirections.NW) && this.checkObjectHit(owner, weapon, playerSq, true, false)) {
         hit = true;
         hitCount++;
      }

      if (dir == IsoDirections.SE || dir == IsoDirections.S || dir == IsoDirections.SW) {
         IsoGridSquare sq = cell.getGridSquare(playerSq.getX(), playerSq.getY() + 1, playerSq.getZ());
         if (this.checkObjectHit(owner, weapon, sq, true, false)) {
            hit = true;
            hitCount++;
         }
      }

      if (dir == IsoDirections.SE || dir == IsoDirections.E || dir == IsoDirections.NE) {
         IsoGridSquare sq = cell.getGridSquare(playerSq.getX() + 1, playerSq.getY(), playerSq.getZ());
         if (this.checkObjectHit(owner, weapon, sq, false, true)) {
            hit = true;
            hitCount++;
         }
      }

      if ((dir == IsoDirections.NW || dir == IsoDirections.W || dir == IsoDirections.SW) && this.checkObjectHit(owner, weapon, playerSq, false, true)) {
         hit = true;
         hitCount++;
      }

      this.hitOnlyTree = hit && hitCount == hitTreeCount;
      return hit;
   }

   public void splash(IsoMovingObject obj, HandWeapon weapon, IsoGameCharacter owner) {
      IsoGameCharacter isoGameCharacter = (IsoGameCharacter)obj;
      if (weapon != null && SandboxOptions.instance.bloodLevel.getValue() > 1) {
         int spn = weapon.getSplatNumber();
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
            isoGameCharacter.splatBlood(3, 0.3F);
         }
      }

      int rand = 3;
      int nbRepeat = 7;
      switch (SandboxOptions.instance.bloodLevel.getValue()) {
         case 1:
            nbRepeat = 0;
            break;
         case 2:
            nbRepeat = 4;
            rand = 5;
         case 3:
         default:
            break;
         case 4:
            nbRepeat = 10;
            rand = 2;
            break;
         case 5:
            nbRepeat = 15;
            rand = 0;
      }

      if (SandboxOptions.instance.bloodLevel.getValue() > 1) {
         isoGameCharacter.splatBloodFloorBig();
      }

      float dz = 0.5F;
      if (isoGameCharacter instanceof IsoZombie isoZombie && (isoZombie.crawling || isoGameCharacter.getCurrentState() == ZombieOnGroundState.instance())) {
         dz = 0.2F;
      }

      float addedDistX = Rand.Next(1.5F, 5.0F);
      float addedDistY = Rand.Next(1.5F, 5.0F);
      if (owner instanceof IsoPlayer isoPlayer && isoPlayer.isDoShove()) {
         addedDistX = Rand.Next(0.0F, 0.5F);
         addedDistY = Rand.Next(0.0F, 0.5F);
      }

      if (nbRepeat > 0) {
         isoGameCharacter.playBloodSplatterSound();
      }

      for (int i = 0; i < nbRepeat; i++) {
         if (Rand.Next(rand) == 0) {
            new IsoZombieGiblets(
               GibletType.A,
               isoGameCharacter.getCell(),
               isoGameCharacter.getX(),
               isoGameCharacter.getY(),
               isoGameCharacter.getZ() + dz,
               isoGameCharacter.getHitDir().x * addedDistX,
               isoGameCharacter.getHitDir().y * addedDistY
            );
         }
      }
   }

   private int DoSwingCollisionBoneCheck(IsoGameCharacter owner, HandWeapon weapon, IsoGameCharacter mover, int bone, float tempoLengthTest) {
      float weaponLength = weapon.weaponLength;
      weaponLength += 0.5F;
      if (owner.isAimAtFloor() && ((IsoLivingCharacter)owner).isDoShove()) {
         weaponLength = 0.3F;
      }

      Model.boneToWorldCoords(mover, bone, tempVectorBonePos);

      for (int x = 1; x <= 10; x++) {
         float delta = x / 10.0F;
         tempVector3_1.x = owner.getX();
         tempVector3_1.y = owner.getY();
         tempVector3_1.z = owner.getZ();
         tempVector3_1.x = tempVector3_1.x + owner.getForwardDirectionX() * weaponLength * delta;
         tempVector3_1.y = tempVector3_1.y + owner.getForwardDirectionY() * weaponLength * delta;
         tempVector3_1.x = tempVectorBonePos.x - tempVector3_1.x;
         tempVector3_1.y = tempVectorBonePos.y - tempVector3_1.y;
         tempVector3_1.z = 0.0F;
         boolean hit = tempVector3_1.getLength() < tempoLengthTest;
         if (hit) {
            return bone;
         }
      }

      return -1;
   }

   public void processMaintenanceCheck(IsoGameCharacter owner, HandWeapon weapon, IsoObject isoObject) {
      if (owner.isActuallyAttackingWithMeleeWeapon() && !GameClient.client && weapon != null) {
         float breakMultiplier = 1.0F;
         if (((IsoPlayer)owner).isAttackType(AttackType.CHARGE)) {
            breakMultiplier /= 1.5F;
         }

         if (isoObject instanceof IsoTree) {
            boolean axeBonus = weapon.getScriptItem().containsWeaponCategory(WeaponCategory.AXE);
            int axeBonusConditionModifier = axeBonus ? 2 : 1;
            if (Rand.Next(weapon.getConditionLowerChance() * axeBonusConditionModifier + owner.getMaintenanceMod()) == 0) {
               weapon.checkSyncItemFields(weapon.damageCheck(0, breakMultiplier));
            }

            LuaEventManager.triggerEvent("OnWeaponHitTree", owner, weapon);
         } else {
            weapon.checkSyncItemFields(weapon.damageCheck(0, breakMultiplier));
         }

         if (Rand.NextBool(2) && (!owner.isAimAtFloor() || !((IsoLivingCharacter)owner).isDoShove())) {
            if (weapon.isTwoHandWeapon() && (owner.getPrimaryHandItem() != weapon || owner.getSecondaryHandItem() != weapon) && Rand.NextBool(3)) {
               return;
            }

            if (!weapon.hasTag(ItemTag.NO_MAINTENANCE_XP) && !owner.isShoving() && !owner.isDoStomp()) {
               float amount = 2.0F;
               if (weapon.getConditionLowerChance() > 10) {
                  amount = amount * 10.0F / weapon.getConditionLowerChance();
               }

               if (GameServer.server) {
                  GameServer.addXp((IsoPlayer)owner, Perks.Maintenance, amount);
               } else {
                  owner.getXp().AddXP(Perks.Maintenance, amount);
               }
            }
         }
      }
   }

   private boolean processIsoBarricade(IsoGameCharacter isoGameCharacter, HandWeapon handWeapon, HitInfo hitInfo) {
      IsoWindow isoWindow = null;
      if (hitInfo.getObject() == null && hitInfo.getWindow() != null) {
         isoWindow = hitInfo.getWindow();
      }

      if (isoWindow == null) {
         return true;
      }

      IsoBarricade isoBarricade = isoWindow.getBarricadeForCharacter(isoGameCharacter);
      if (isoBarricade != null) {
         if (!isoBarricade.canAttackBypassIsoBarricade(isoGameCharacter, handWeapon)) {
            isoBarricade.WeaponHit(isoGameCharacter, handWeapon);
            return false;
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   private boolean processIsoBarricadeOpposite(IsoGameCharacter isoGameCharacter, HandWeapon handWeapon, HitInfo hitInfo) {
      IsoWindow isoWindow = null;
      if (hitInfo.getObject() == null && hitInfo.getWindow() != null) {
         isoWindow = hitInfo.getWindow();
      }

      if (isoWindow == null) {
         return true;
      }

      IsoBarricade isoBarricade = isoWindow.getBarricadeOppositeCharacter(isoGameCharacter);
      if (isoBarricade != null) {
         if (!isoBarricade.canAttackBypassIsoBarricade(isoGameCharacter, handWeapon)) {
            isoBarricade.WeaponHit(isoGameCharacter, handWeapon);
            return false;
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   private void processIsoWindow(IsoGameCharacter owner, HitInfo hitInfo) {
      IsoWindow isoWindow = null;
      if (hitInfo.getObject() == null && hitInfo.getWindow() != null) {
         isoWindow = hitInfo.getWindow();
      }

      if (isoWindow != null) {
         if (!isoWindow.isDestroyed()) {
            isoWindow.addBrokenGlass(owner);
         }

         isoWindow.smashWindow();
      }
   }

   private void processClientHit(
      List<CombatManager.TargetHitList> targetHitLists,
      IsoObject target,
      float damage,
      boolean ignoreDamage,
      float range,
      boolean hitHead,
      boolean hitLegs,
      boolean removeKnife,
      boolean helmetFall
   ) {
      if (GameClient.client) {
         WeaponHit hit = new WeaponHit();
         if (target instanceof IsoMovingObject movObject) {
            hit.set(damage, range, movObject.getHitForce(), movObject.getHitDir().x, movObject.getHitDir().y, hitHead, hitLegs, removeKnife, ignoreDamage);
         } else {
            hit.set(damage, range, 0.0F, 0.0F, 0.0F, hitHead, hitLegs, removeKnife, ignoreDamage);
         }

         for (CombatManager.TargetHitList hitList : targetHitLists) {
            if (hitList.target == target) {
               hitList.addHit(hit, helmetFall);
               return;
            }
         }

         CombatManager.TargetHitList hitList = new CombatManager.TargetHitList(target);
         hitList.addHit(hit, helmetFall);
         targetHitLists.add(hitList);
      }
   }

   public void attackCollisionCheck(IsoGameCharacter owner, HandWeapon weapon, SwipeStatePlayer swipeStatePlayer, AttackType attackTypeModifier) {
      boolean scoped = pzopt.SubframeCombat.beginAcceptedAimScope(owner);
      try {
         this.attackCollisionCheckNative(owner, weapon, swipeStatePlayer, attackTypeModifier);
      } finally {
         pzopt.SubframeCombat.endAcceptedAimScope(scoped);
      }
   }

   private void attackCollisionCheckNative(IsoGameCharacter owner, HandWeapon weapon, SwipeStatePlayer swipeStatePlayer, AttackType attackTypeModifier) {
      IsoLivingCharacter ownerLiving = (IsoLivingCharacter)owner;
      if (owner.isPerformingShoveAnimation()) {
         ownerLiving.setDoShove(true);
      }

      if (owner.isPerformingGrappleGrabAnimation()) {
         ownerLiving.setDoGrapple(true);
      }

      IsoPlayer ownerPlayer = (IsoPlayer)Type.tryCastTo(owner, IsoPlayer.class);
      if (GameServer.server) {
         DebugType.Combat.debugln("Player swing connects.");
      }

      if (GameClient.client && ownerPlayer != null && weapon.isAimedFirearm()) {
         ownerPlayer.getNetworkCharacterAI().onShot();
      }

      LuaEventManager.triggerEvent("OnWeaponSwingHitPoint", owner, weapon);
      if (weapon.getPhysicsObject() != null) {
         owner.Throw(weapon);
      }

      if (weapon.isUseSelf()) {
         weapon.Use();
      }

      if (weapon.isOtherHandUse() && owner.getSecondaryHandItem() != null) {
         owner.getSecondaryHandItem().Use();
      }

      boolean bIgnoreDamage = false;
      if (ownerLiving.isDoShove() && !owner.isShoveStompAnim()) {
         bIgnoreDamage = true;
      }

      boolean processConditionLoss = false;
      boolean helmetFall = false;
      IsoObject networkHitIsoObject = null;
      owner.getAttackVars().setWeapon(weapon);
      owner.getAttackVars().targetOnGround.set(ownerLiving.targetOnGround);
      owner.getAttackVars().aimAtFloor = owner.isAimAtFloor();
      owner.getAttackVars().doShove = ownerLiving.isDoShove() || owner.isPerformingShoveAnimation();
      owner.getAttackVars().doGrapple = ownerLiving.isDoGrapple() || ownerLiving.isPerformingGrappleGrabAnimation();
      this.calculateHitInfoList(owner);
      this.tracers.clear();
      if (weapon.isAimedFirearm()) {
         EffectsManager.getInstance().startMuzzleFlash(owner, 1);
         BallisticsController ballisticsController = owner.getBallisticsController();
         ballisticsController.update();
         this.fireWeapon(weapon, owner);
         StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Shots Fired", 1.0F);
      }

      List<HitInfo> hitInfoList = owner.getHitInfoList();
      int hitCount = hitInfoList.size();
      if (GameClient.client) {
         if (ownerPlayer != null) {
            GameClient.sendAttackCollisionCheck(ownerPlayer, weapon, hitCount);
         }
      } else {
         this.processWeaponEndurance(owner, weapon);
         owner.addCombatMuscleStrain(weapon, hitCount);
      }

      owner.setLastHitCount(hitCount);
      int split = 1;
      this.dotList.clear();
      if (hitCount == 0 && owner.getClickSound() != null && !ownerLiving.isDoShove()) {
         if (ownerPlayer == null || ownerPlayer.isLocalPlayer()) {
            owner.getEmitter().playSound(owner.getClickSound());
         }

         owner.setRecoilDelay(this.combatConfig.get(CombatConfigKey.RECOIL_DELAY));
      }

      List<CombatManager.TargetHitList> targetHitLists = new ArrayList<>();
      boolean shotgunXPAwarded = false;

      for (int i = 0; i < hitCount; i++) {
         boolean ignoreHitCountDamage = false;
         int hitHead = 0;
         boolean hitLegs = false;
         boolean removeKnife = false;
         HitInfo hitInfo = hitInfoList.get(i);
         IsoMovingObject hitObject = hitInfo.getObject();
         BaseVehicle hitVehicle = (BaseVehicle)Type.tryCastTo(hitObject, BaseVehicle.class);
         IsoGameCharacter hitCharacter = (IsoGameCharacter)Type.tryCastTo(hitObject, IsoGameCharacter.class);
         IsoZombie hitZombie = (IsoZombie)Type.tryCastTo(hitObject, IsoZombie.class);
         if (networkHitIsoObject == null && hitObject != null) {
            networkHitIsoObject = hitObject;
         }

         if (hitCharacter != null) {
            boolean isCharacterStanding = hitCharacter.isStanding();
            boolean isCharacterProne = hitCharacter.isProne();
            boolean isCharacterHit = attackTypeModifier == AttackType.NONE
               || isCharacterProne && attackTypeModifier.hasModifier(AttackTypeModifier.Prone)
               || isCharacterStanding && attackTypeModifier.hasModifier(AttackTypeModifier.Standing);
            if (!isCharacterHit) {
               continue;
            }
         }

         if (this.processIsoBarricade(owner, weapon, hitInfo)) {
            this.processIsoWindow(owner, hitInfo);
            if (this.processIsoBarricadeOpposite(owner, weapon, hitInfo) && hitObject != null) {
               if (this.isWindowBetween(owner, weapon, owner, hitObject)) {
                  this.smashWindowBetween(owner, hitObject, weapon);
               }

               boolean hit = Rand.Next(100) <= hitInfo.chance;
               if (!hit) {
                  StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Bullets Chance Missed", 1.0F);
                  if (SandboxOptions.instance.firearmUseDamageChance.getValue() != 3
                     && (SandboxOptions.instance.firearmUseDamageChance.getValue() != 2 || hitZombie == null)) {
                     continue;
                  }

                  StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Bullets Damage Ignored", 1.0F);
                  ignoreHitCountDamage = true;
               }

               StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Bullets Chance Hit", 1.0F);
               Vector2 oPos2 = tempVector2_1.set(owner.getX(), owner.getY());
               Vector2 tPos2 = tempVector2_2.set(hitObject.getX(), hitObject.getY());
               tPos2.x = tPos2.x - oPos2.x;
               tPos2.y = tPos2.y - oPos2.y;
               Vector2 angle = owner.getLookVector(tempVector2_1);
               angle.tangent();
               tPos2.normalize();
               float minimumWeaponDamage = weapon.getMinDamage();
               float maximumWeaponDamage = weapon.getMaxDamage();
               long zombieHitSound = 0L;
               if (!weapon.isRangeFalloff()) {
                  boolean piercedTargetDamageReduction = false;
                  float dot = angle.dot(tPos2);

                  for (int d = 0; d < this.dotList.size(); d++) {
                     float dots = this.dotList.get(d);
                     if (Math.abs(dot - dots) < 1.0E-4F) {
                        piercedTargetDamageReduction = true;
                        break;
                     }
                  }

                  if (this.dotList.isEmpty()) {
                     this.dotList.add(dot);
                  }

                  if (piercedTargetDamageReduction) {
                     minimumWeaponDamage /= this.combatConfig.get(CombatConfigKey.PIERCING_BULLET_DAMAGE_REDUCTION);
                     maximumWeaponDamage /= this.combatConfig.get(CombatConfigKey.PIERCING_BULLET_DAMAGE_REDUCTION);
                  }
               }

               if (owner.isAimAtFloor() && !weapon.isRanged() && owner.isNpc()) {
                  this.splash(hitObject, weapon, owner);
                  hitHead = Rand.Next(2);
               } else if (owner.isAimAtFloor() && !weapon.isRanged()) {
                  if (ownerPlayer == null || ownerPlayer.isLocalPlayer()) {
                     if (!StringUtils.isNullOrEmpty(weapon.getHitFloorSound())) {
                        owner.getEmitter().stopSoundByName(weapon.getSwingSound());
                        Material material = Material.Body;
                        if (ownerPlayer != null) {
                           ownerPlayer.setMeleeHitSurface(material);
                        }

                        zombieHitSound = owner.getEmitter().playSoundImpl(weapon.getHitFloorSound(), null);
                        if (GameClient.client) {
                           INetworkPacket.send(GameClient.connection, PacketType.PlaySound, new Object[]{weapon.getHitFloorSound(), (byte)0, owner, material});
                        }
                     } else if (!StringUtils.isNullOrEmpty(weapon.getZombieHitSound())) {
                        owner.getEmitter().stopSoundByName(weapon.getSwingSound());
                        Material material = Material.Body;
                        if (ownerPlayer != null) {
                           ownerPlayer.setMeleeHitSurface(material);
                        }

                        zombieHitSound = owner.getEmitter().playSoundImpl(weapon.getZombieHitSound(), null);
                        if (GameClient.client) {
                           INetworkPacket.send(GameClient.connection, PacketType.PlaySound, new Object[]{weapon.getZombieHitSound(), (byte)0, owner, material});
                        }
                     }
                  }

                  int bone = this.DoSwingCollisionBoneCheck(
                     owner,
                     this.getWeapon(owner),
                     (IsoGameCharacter)hitObject,
                     ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_Head", -1),
                     0.28F
                  );
                  if (bone == -1) {
                     bone = this.DoSwingCollisionBoneCheck(
                        owner,
                        this.getWeapon(owner),
                        (IsoGameCharacter)hitObject,
                        ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_Spine", -1),
                        0.28F
                     );
                     if (bone == -1) {
                        bone = this.DoSwingCollisionBoneCheck(
                           owner,
                           this.getWeapon(owner),
                           (IsoGameCharacter)hitObject,
                           ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_L_Calf", -1),
                           0.13F
                        );
                        if (bone == -1) {
                           bone = this.DoSwingCollisionBoneCheck(
                              owner,
                              this.getWeapon(owner),
                              (IsoGameCharacter)hitObject,
                              ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_R_Calf", -1),
                              0.13F
                           );
                        }

                        if (bone == -1) {
                           bone = this.DoSwingCollisionBoneCheck(
                              owner,
                              this.getWeapon(owner),
                              (IsoGameCharacter)hitObject,
                              ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_L_Foot", -1),
                              0.23F
                           );
                        }

                        if (bone == -1) {
                           bone = this.DoSwingCollisionBoneCheck(
                              owner,
                              this.getWeapon(owner),
                              (IsoGameCharacter)hitObject,
                              ((IsoGameCharacter)hitObject).getAnimationPlayer().getSkinningBoneIndex("Bip01_R_Foot", -1),
                              0.23F
                           );
                        }

                        if (bone == -1) {
                           continue;
                        }

                        hitLegs = true;
                     }
                  } else {
                     this.splash(hitObject, weapon, owner);
                     this.splash(hitObject, weapon, owner);
                     hitHead = Rand.Next(0, 3) + 1;
                  }
               }

               if (owner.getVariableBoolean("PistolWhipAnim")) {
                  zombieHitSound = owner.playSound(weapon.getZombieHitSound());
               }

               if (!owner.getAttackVars().aimAtFloor
                  && (!owner.getAttackVars().closeKill || !owner.isCriticalHit())
                  && !ownerLiving.isDoShove()
                  && hitCharacter != null
                  && (ownerPlayer == null || ownerPlayer.isLocalPlayer())) {
                  if (weapon.isRanged()) {
                     zombieHitSound = hitCharacter.getEmitter().playSoundImpl(weapon.getZombieHitSound(), null);
                     zombie.audio.parameters.ParameterBulletHitSurface.Material material = hitHead > 0
                        ? zombie.audio.parameters.ParameterBulletHitSurface.Material.Flesh_Hollow
                        : zombie.audio.parameters.ParameterBulletHitSurface.Material.Flesh;
                     hitCharacter.getEmitter().setParameterValueByName(zombieHitSound, "BulletHitSurface", material.label);
                     if (GameClient.client) {
                        INetworkPacket.send(
                           GameClient.connection, PacketType.PlaySound, new Object[]{weapon.getZombieHitSound(), (byte)0, hitCharacter, material}
                        );
                     }
                  } else {
                     owner.getEmitter().stopSoundByName(weapon.getSwingSound());
                     Material material = Material.Body;
                     if (ownerPlayer != null) {
                        ownerPlayer.setMeleeHitSurface(material);
                     }

                     zombieHitSound = owner.getEmitter().playSoundImpl(weapon.getZombieHitSound(), null);
                     if (GameClient.client) {
                        INetworkPacket.send(GameClient.connection, PacketType.PlaySound, new Object[]{weapon.getZombieHitSound(), (byte)0, owner, material});
                     }
                  }
               }

               if (weapon.isRanged() && hitZombie != null) {
                  Vector2 oPos = tempVector2_1.set(owner.getX(), owner.getY());
                  Vector2 tPos = tempVector2_2.set(hitObject.getX(), hitObject.getY());
                  tPos.x = tPos.x - oPos.x;
                  tPos.y = tPos.y - oPos.y;
                  Vector2 dir = hitZombie.getForwardDirection();
                  tPos.normalize();
                  dir.normalize();
                  float dot2 = tPos.dot(dir);
                  hitZombie.setHitFromBehind(dot2 > 0.5F);
               }

               if (hitZombie != null && hitZombie.isCurrentState(ZombieOnGroundState.instance())) {
                  hitZombie.setReanimateTimer(hitZombie.getReanimateTimer() + Rand.Next(10));
               }

               if (hitZombie != null && hitZombie.isCurrentState(ZombieGetUpState.instance())) {
                  hitZombie.setReanimateTimer(Rand.Next(60) + 30);
               }

               boolean isTwoHanded = !weapon.isTwoHandWeapon() || owner.isItemInBothHands(weapon);
               float damage = Rand.Next(minimumWeaponDamage, maximumWeaponDamage);
               if (!weapon.isRanged()) {
                  damage *= weapon.getDamageMod(owner) * owner.getHittingMod();
               }

               if (!isTwoHanded && !weapon.isRanged() && maximumWeaponDamage > minimumWeaponDamage) {
                  damage -= minimumWeaponDamage;
               }

               if (!weapon.isRanged()) {
                  if (owner.isAimAtFloor() && ownerLiving.isDoShove()) {
                     float legsPain = 0.0F;

                     for (int x = BodyPartType.ToIndex(BodyPartType.UpperLeg_L); x <= BodyPartType.ToIndex(BodyPartType.Foot_R); x++) {
                        legsPain += ((BodyPart)owner.getBodyDamage().getBodyParts().get(x)).getPain();
                     }

                     if (legsPain > 10.0F) {
                        damage /= PZMath.clamp(legsPain / 10.0F, 1.0F, 30.0F);
                        MoodlesUI.getInstance().wiggle(MoodleType.PAIN);
                        MoodlesUI.getInstance().wiggle(MoodleType.INJURED);
                     }
                  } else {
                     float armsPain = 0.0F;

                     for (int x = BodyPartType.ToIndex(BodyPartType.Hand_L); x <= BodyPartType.ToIndex(BodyPartType.UpperArm_R); x++) {
                        armsPain += ((BodyPart)owner.getBodyDamage().getBodyParts().get(x)).getPain();
                     }

                     if (armsPain > 10.0F) {
                        damage /= PZMath.clamp(armsPain / 10.0F, 1.0F, 30.0F);
                        MoodlesUI.getInstance().wiggle(MoodleType.PAIN);
                        MoodlesUI.getInstance().wiggle(MoodleType.INJURED);
                     }
                  }
               }

               if (!weapon.isRanged()) {
                  damage *= owner.getCharacterTraits().getTraitDamageDealtReductionModifier();
               }

               float damageSplit = damage;
               if (!weapon.isRanged()) {
                  damageSplit = damage / (split++ * 0.5F);
               }

               Vector2 oPos = tempVector2_1.set(owner.getX(), owner.getY());
               Vector2 tPos = tempVector2_2.set(hitObject.getX(), hitObject.getY());
               tPos.x = tPos.x - oPos.x;
               tPos.y = tPos.y - oPos.y;
               float dist2 = tPos.getLength();
               float rangeDel;
               if (weapon.isRangeFalloff()) {
                  rangeDel = 1.0F;
               } else if (weapon.isRanged()) {
                  rangeDel = 0.5F;
               } else {
                  rangeDel = dist2 / weapon.getMaxRange(owner);
               }

               rangeDel *= 2.0F;
               if (rangeDel < 0.3F) {
                  rangeDel = 1.0F;
               }

               if (!weapon.isRanged() && owner.getMoodles().getMoodleLevel(MoodleType.PANIC) > 1) {
                  damageSplit -= owner.getMoodles().getMoodleLevel(MoodleType.PANIC) * 0.1F;
                  MoodlesUI.getInstance().wiggle(MoodleType.PANIC);
               }

               if (!weapon.isRanged() && owner.getMoodles().getMoodleLevel(MoodleType.STRESS) > 1) {
                  damageSplit -= owner.getMoodles().getMoodleLevel(MoodleType.STRESS) * 0.1F;
                  MoodlesUI.getInstance().wiggle(MoodleType.STRESS);
               }

               if (damageSplit < 0.0F) {
                  damageSplit = 0.1F;
               }

               if (owner.isAimAtFloor() && ownerLiving.isDoShove()) {
                  damageSplit = Rand.Next(0.7F, 1.0F) + owner.getPerkLevel(Perks.Strength) * 0.2F;
                  Clothing shoes = (Clothing)owner.getWornItem(ItemBodyLocation.SHOES);
                  if (shoes == null) {
                     damageSplit *= 0.5F;
                  } else {
                     damageSplit *= shoes.getStompPower();
                  }
               }

               if (!weapon.isRanged()) {
                  switch (owner.getMoodles().getMoodleLevel(MoodleType.ENDURANCE)) {
                     case 0:
                     default:
                        break;
                     case 1:
                        damageSplit *= 0.5F;
                        MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
                        break;
                     case 2:
                        damageSplit *= 0.2F;
                        MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
                        break;
                     case 3:
                        damageSplit *= 0.1F;
                        MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
                        break;
                     case 4:
                        damageSplit *= 0.05F;
                        MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
                  }

                  switch (owner.getMoodles().getMoodleLevel(MoodleType.TIRED)) {
                     case 0:
                     default:
                        break;
                     case 1:
                        damageSplit *= 0.5F;
                        MoodlesUI.getInstance().wiggle(MoodleType.TIRED);
                        break;
                     case 2:
                        damageSplit *= 0.2F;
                        MoodlesUI.getInstance().wiggle(MoodleType.TIRED);
                        break;
                     case 3:
                        damageSplit *= 0.1F;
                        MoodlesUI.getInstance().wiggle(MoodleType.TIRED);
                        break;
                     case 4:
                        damageSplit *= 0.05F;
                        MoodlesUI.getInstance().wiggle(MoodleType.TIRED);
                  }
               }

               owner.knockbackAttackMod = 1.0F;
               if ("KnifeDeath".equals(owner.getVariableString("ZombieHitReaction"))) {
                  rangeDel *= 1000.0F;
                  owner.knockbackAttackMod = 0.0F;
                  owner.addWorldSoundUnlessInvisible(4, 4, false);
                  owner.getAttackVars().closeKill = true;
                  hitObject.setCloseKilled(true);
               } else {
                  owner.getAttackVars().closeKill = false;
                  hitObject.setCloseKilled(false);
                  owner.addWorldSoundUnlessInvisible(8, 8, false);
                  if (Rand.Next(3) != 0 && (!owner.isAimAtFloor() || !ownerLiving.isDoShove())) {
                     if (Rand.Next(7) == 0) {
                        owner.addWorldSoundUnlessInvisible(16, 16, false);
                     }
                  } else {
                     owner.addWorldSoundUnlessInvisible(10, 10, false);
                  }
               }

               hitObject.setHitFromAngle(hitInfo.dot);
               if (hitZombie != null) {
                  hitZombie.setHitFromBehind(owner.isBehind(hitZombie));
                  hitZombie.setPlayerAttackPosition(hitZombie.testDotSide(owner));
                  hitZombie.setHitHeadWhileOnFloor(hitHead);
                  hitZombie.setHitLegsWhileOnFloor(hitLegs);
                  processConditionLoss = true;
               }

               if (hitCharacter != null) {
                  if (weapon.isMelee()) {
                     int partHit;
                     if (hitHead > 0) {
                        partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Head), BodyPartType.ToIndex(BodyPartType.Neck) + 1);
                     } else if (hitLegs) {
                        partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Groin), BodyPartType.ToIndex(BodyPartType.Foot_R) + 1);
                     } else {
                        partHit = Rand.Next(BodyPartType.ToIndex(BodyPartType.Hand_L), BodyPartType.ToIndex(BodyPartType.Neck) + 1);
                     }

                     damageSplit = this.applyMeleeHitLocationDamage(hitCharacter, weapon, partHit, hitHead, hitLegs, damageSplit);
                     if (!GameClient.client) {
                        this.resolveSpikedArmorDamage(owner, weapon, hitCharacter, partHit);
                     }

                     if (hitZombie != null) {
                        removeKnife = this.applyKnifeDeathEffect(owner, hitZombie);
                     }
                  } else if (weapon.isRanged()) {
                     int bodyPart = this.processHit(weapon, owner, hitCharacter);
                     damageSplit = this.applyRangeHitLocationDamage(hitCharacter, weapon, bodyPart, damageSplit);
                  }

                  if (!GameClient.client && !GameServer.server || GameClient.client && IsoPlayer.isLocalPlayer(owner)) {
                     helmetFall = hitCharacter.helmetFall(hitHead > 0);
                  }
               }

               float hitDamage = 0.0F;
               if (hitVehicle == null && hitObject.getSquare() != null && owner.getSquare() != null) {
                  hitObject.setCloseKilled(owner.getAttackVars().closeKill);
                  if (ownerPlayer.isLocalPlayer() || owner.isNpc()) {
                     hitDamage = hitObject.Hit(weapon, owner, damageSplit, bIgnoreDamage, rangeDel);
                     if (hitObject instanceof IsoGameCharacter isoGameCharacter) {
                        this.applyMeleeEnduranceLoss(owner, isoGameCharacter, weapon, hitDamage);
                     }

                     this.setParameterCharacterHitResult(owner, hitZombie, zombieHitSound);
                  }

                  if (!ignoreHitCountDamage && !GameClient.client && !GameServer.server && (!weapon.isRangeFalloff() || !shotgunXPAwarded)) {
                     LuaEventManager.triggerEvent("OnWeaponHitXp", owner, weapon, hitObject, damageSplit, 1);
                     if (weapon.isRangeFalloff()) {
                        shotgunXPAwarded = true;
                     }
                  }

                  if ((!ownerLiving.isDoShove() || owner.isAimAtFloor())
                     && owner.DistToSquared(hitObject) < 2.0F
                     && Math.abs(owner.getZ() - hitObject.getZ()) < 0.5F) {
                     owner.addBlood(null, false, false, false);
                     if (GameClient.client && ownerPlayer != null) {
                        ownerPlayer.syncVisuals();
                     }
                  }

                  if (hitCharacter != null) {
                     processConditionLoss = true;
                     this.processMaintenanceCheck(owner, weapon, hitCharacter);
                     if (hitCharacter.isDead()) {
                        owner.getStats().remove(CharacterStat.STRESS, 0.02F);
                     } else if (!(hitCharacter instanceof IsoPlayer) && (!ownerLiving.isDoShove() || owner.isAimAtFloor())) {
                        this.splash(hitCharacter, weapon, owner);
                     }

                     HitReactionNetworkAI.calculateHitReactionFromWeapon(owner, hitCharacter, weapon);
                     if (hitCharacter instanceof IsoPlayer && hitCharacter.isCurrentGameClientState(ClimbThroughWindowState.instance())) {
                        hitCharacter.getNetworkCharacterAI().resetState();
                     }

                     this.processClientHit(
                        targetHitLists, hitCharacter, hitDamage, ignoreHitCountDamage, rangeDel, hitHead > 0, hitLegs, removeKnife, helmetFall
                     );
                  }
               } else if (hitVehicle != null) {
                  if (hitVehicle.processHit(owner, weapon, damageSplit)) {
                     processConditionLoss = true;
                     this.processMaintenanceCheck(owner, weapon, hitVehicle);
                     hitDamage = damageSplit;
                     this.processClientHit(targetHitLists, hitVehicle, hitDamage, ignoreHitCountDamage, rangeDel, hitHead > 0, hitLegs, removeKnife, helmetFall);
                  }
                  break;
               }

               bIgnoreDamage |= ignoreHitCountDamage;
            }
         }
      }

      if (this.processTreeHit(targetHitLists, owner, weapon)) {
         processConditionLoss = true;
      }

      processConditionLoss = this.checkForConditionLoss(owner, weapon, processConditionLoss);
      if (processConditionLoss && !weapon.isMelee()) {
         weapon.checkSyncItemFields(weapon.damageCheck(0, 1.0F, false, true, owner));
      }

      if (this.objHit != null) {
         this.processMaintenanceCheck(owner, weapon, this.objHit);
         this.processClientHit(targetHitLists, this.objHit, 0.0F, bIgnoreDamage, 1.0F, false, false, false, false);
      }

      if (!processConditionLoss) {
         this.processClientHit(targetHitLists, null, 0.0F, bIgnoreDamage, 1.0F, false, false, false, false);
         if (weapon.getPhysicsObject() != null && (!GameClient.client || owner.isLocal())) {
            IsoGridSquare sq = owner.getCurrentSquare();
            if (sq != null) {
               INetworkPacket.send(PacketType.PlayerDropHeldItems, new Object[]{owner, sq.x, sq.y, sq.z, false, true});
            }
         }
      }

      owner.set(SwipeStatePlayer.LOWER_CONDITION, processConditionLoss);
      owner.set(SwipeStatePlayer.ATTACKED, true);
      if (GameClient.client) {
         boolean isCriticalHit = owner.isCriticalHit();

         for (CombatManager.TargetHitList hitList : targetHitLists) {
            GameClient.sendPlayerHit(owner, hitList.target, weapon, isCriticalHit, this.tracers, hitList.hits, hitList.helmetFall);
         }
      }
   }

   private boolean checkForConditionLoss(IsoGameCharacter isoGameCharacter, HandWeapon handWeapon, boolean processConditionLoss) {
      if (handWeapon.isBareHands()) {
         return false;
      } else {
         boolean hasCondition = handWeapon.getCondition() > 0;
         if (hasCondition && handWeapon.isAimedFirearm()) {
            return processConditionLoss || isoGameCharacter.isRangedWeaponEmpty();
         } else {
            return !processConditionLoss ? false : hasCondition;
         }
      }
   }

   public void processWeaponEndurance(IsoGameCharacter isoGameCharacter, HandWeapon handWeapon) {
      if (handWeapon.isUseEndurance()) {
         float weight = handWeapon.getEffectiveWeight();
         float enduranceTwoHandsWeaponModifier = 0.0F;
         if (handWeapon.isTwoHandWeapon() && (isoGameCharacter.getPrimaryHandItem() != handWeapon || isoGameCharacter.getSecondaryHandItem() != handWeapon)) {
            enduranceTwoHandsWeaponModifier = weight / 1.5F / 10.0F;
         }

         float val = (
               weight * 0.18F * handWeapon.getFatigueMod(isoGameCharacter) * isoGameCharacter.getFatigueMod() * handWeapon.getEnduranceMod() * 0.3F
                  + enduranceTwoHandsWeaponModifier
            )
            * 0.04F;
         float mod = isoGameCharacter.getCharacterTraits().getTraitEnduranceLossModifier();
         isoGameCharacter.getStats().remove(CharacterStat.ENDURANCE, val * mod);
      }
   }

   private boolean processTreeHit(List<CombatManager.TargetHitList> targetHitLists, IsoGameCharacter isoGameCharacter, HandWeapon handWeapon) {
      this.treeHit = null;
      boolean objectHit = this.CheckObjectHit(isoGameCharacter, handWeapon);
      if (!objectHit) {
         return false;
      }

      if (this.hitOnlyTree) {
         this.processMaintenanceCheck(isoGameCharacter, handWeapon, this.treeHit);
         this.processClientHit(targetHitLists, this.treeHit, 0.0F, false, 1.0F, false, false, false, false);
      }

      if (this.treeHit != null) {
         this.treeHit.WeaponHit(isoGameCharacter, handWeapon);
      }

      return true;
   }

   public void releaseBallisticsTargets(IsoGameCharacter isoGameCharacter) {
      PZArrayList<HitInfo> hitInfoList = isoGameCharacter.getHitInfoList();

      for (int i = 0; i < hitInfoList.size(); i++) {
         HitInfo hitInfo = (HitInfo)hitInfoList.get(i);
         if (hitInfo.getObject() instanceof IsoZombie zombie) {
            BallisticsTarget ballisticsTarget = zombie.getBallisticsTarget();
            if (ballisticsTarget != null) {
               zombie.releaseBallisticsTarget();
            }
         }
      }
   }

   public void applyDamage(IsoGameCharacter isoGameCharacter, float damageAmount) {
      if (!isoGameCharacter.isInvulnerable()) {
         isoGameCharacter.applyDamage(damageAmount);
      }
   }

   public void applyDamage(BodyPart bodyPart, float damageAmount) {
      IsoGameCharacter isoGameCharacter = bodyPart.getParentChar();
      if (isoGameCharacter != null && !isoGameCharacter.isInvulnerable()) {
         bodyPart.ReduceHealth(damageAmount);
      }
   }

   public void calculateAttackVars(IsoLivingCharacter isoLivingCharacter) {
      boolean scoped = pzopt.SubframeCombat.beginAcceptedAimScope(isoLivingCharacter);
      try {
         this.calculateAttackVars(isoLivingCharacter, isoLivingCharacter.getAttackVars());
      } finally {
         pzopt.SubframeCombat.endAcceptedAimScope(scoped);
      }
   }

   private void calculateAttackVars(IsoLivingCharacter owner, AttackVars vars) {
      if (!vars.isProcessed) {
         HandWeapon weapon = (HandWeapon)Type.tryCastTo(owner.getPrimaryHandItem(), HandWeapon.class);
         if (weapon != null && weapon.getOtherHandRequire() != null) {
            InventoryItem secondary = owner.getSecondaryHandItem();
            if (secondary != null) {
               if (!secondary.hasTag(weapon.getOtherHandRequire()) || secondary.getCurrentUses() == 0) {
                  weapon = null;
               }
            } else {
               weapon = null;
            }
         }

         if (!GameClient.client || owner.isLocal()) {
            boolean bAttacking = owner.isPerformingHostileAnimation();
            vars.setWeapon(weapon == null ? owner.bareHands : weapon);
            vars.targetOnGround.set(null);
            vars.aimAtFloor = false;
            vars.closeKill = false;
            vars.doShove = owner.isDoShove();
            vars.doGrapple = owner.isDoGrapple();
            vars.useChargeDelta = owner.useChargeDelta;
            vars.recoilDelay = 0;
            if (vars.doGrapple) {
               vars.aimAtFloor = false;
               vars.setWeapon(owner.bareHands);
            } else if ((vars.getWeapon(owner) == owner.bareHands || vars.doShove) && !((IsoPlayer)owner).isAttackType(AttackType.CHARGE)) {
               vars.doShove = true;
               vars.aimAtFloor = false;
               vars.setWeapon(owner.bareHands);
            }

            this.calcValidTargets(owner, vars.getWeapon(owner), vars.targetsProne, vars.targetsStanding);
            HitInfo bestStanding = vars.targetsStanding.isEmpty() ? null : (HitInfo)vars.targetsStanding.get(0);
            HitInfo bestProne = vars.targetsProne.isEmpty() ? null : (HitInfo)vars.targetsProne.get(0);
            if (this.isProneTargetBetter(owner, bestStanding, bestProne)) {
               bestStanding = null;
            }

            if (!bAttacking) {
               owner.setAimAtFloor(false);
            }

            float lowestDistSq = Float.MAX_VALUE;
            if (bestStanding != null) {
               if (!bAttacking) {
                  owner.setAimAtFloor(false);
               }

               vars.aimAtFloor = false;
               vars.targetOnGround.set(null);
               vars.targetStanding.set(bestStanding.getObject());
               vars.targetDistance = PZMath.sqrt(bestStanding.distSq);
               lowestDistSq = bestStanding.distSq;
            } else if (bestProne != null && (Core.getInstance().isOptionAutoProneAtk() || owner.isDoShove())) {
               float targetDistance = PZMath.sqrt(bestProne.distSq);
               if (!bAttacking) {
                  owner.setAimAtFloor(true);
               }

               vars.aimAtFloor = !owner.isDoShove() || targetDistance < 0.6F;
               vars.targetOnGround.set(bestProne.getObject());
               vars.targetStanding.set(null);
               vars.targetDistance = targetDistance;
            }

            if (!(lowestDistSq >= vars.getWeapon(owner).getMinRange() * vars.getWeapon(owner).getMinRange())
               && (bestStanding == null || !this.isWindowBetween(null, null, owner, bestStanding.getObject()))) {
               if (owner.getStats().numChasingZombies <= 1 && WeaponType.getWeaponType(owner) == WeaponType.KNIFE) {
                  vars.closeKill = true;
                  return;
               }

               vars.doShove = true;
               IsoPlayer player = (IsoPlayer)Type.tryCastTo(owner, IsoPlayer.class);
               if (player != null && (!player.isAuthorizedHandToHand() || player.isAttackType(AttackType.CHARGE))) {
                  vars.doShove = false;
               }

               vars.aimAtFloor = false;
               if (owner.bareHands.getSwingAnim() != null) {
                  vars.useChargeDelta = 3.0F;
               }
            }

            int keyFloorAtk = GameKeyboard.whichKeyDown(KeybindId.MANUAL_FLOOR_ATTACK);
            int keySprint = GameKeyboard.whichKeyDown(KeybindId.SPRINT);
            boolean bStartedAttackWhileSprinting = owner.getVariableBoolean("StartedAttackWhileSprinting");
            if (owner.isManualFloorAtkButtonDown() && (keyFloorAtk != keySprint || !bStartedAttackWhileSprinting)) {
               vars.aimAtFloor = true;
               vars.doShove = owner.isMeleeButtonDown() || vars.getWeapon(owner) == owner.bareHands;
               owner.setDoShove(vars.doShove);
            }

            if (vars.aimAtFloor && SwipeStatePlayer.isStompingDisabled(owner, vars.doShove)) {
               vars.aimAtFloor = false;
            }

            if (vars.getWeapon(owner).isRanged() && !"Auto".equalsIgnoreCase(owner.getFireMode())) {
               vars.recoilDelay = vars.getWeapon(owner).getRecoilDelay(owner);
            }
         }
      }
   }

   public void calcValidTargets(IsoLivingCharacter owner, HandWeapon weapon, PZArrayList<HitInfo> targetsProne, PZArrayList<HitInfo> targetsStanding) {
      boolean useBallistics = false;
      BallisticsController ballisticsController = owner.getBallisticsController();
      if (ballisticsController != null) {
         useBallistics = weapon.isAimedFirearm();
      }

      TargetComparator targetComparator = this.meleeTargetComparator;
      this.hitInfoPool.release(targetsProne);
      this.hitInfoPool.release(targetsStanding);
      targetsProne.clear();
      targetsStanding.clear();
      float ignoreProneRange = Core.getInstance().getIgnoreProneZombieRange();
      float weaponRange = weapon.getMaxRange() * weapon.getRangeMod(owner);
      boolean applyLungeStateExtraRange = !useBallistics;
      float range = Math.max(ignoreProneRange, weaponRange + (applyLungeStateExtraRange ? 1.0F : 0.0F));
      float minAngle = weapon.getMinAngle();
      float maxAngle = weapon.getMaxAngle();
      HitInfo meleeTargetTooCloseTooShoot = null;
      if (useBallistics) {
         this.calculateBallistics(owner, range);
      }

      for (IsoMovingObject mov : IsoWorld.instance.currentCell.getObjectList()) {
         HitInfo hitInfo = this.calcValidTarget(owner, weapon, mov, range);
         if (hitInfo != null) {
            if (mov.isStanding()) {
               targetsStanding.add(hitInfo);
               if (useBallistics && this.isMeleeTargetTooCloseToShoot(owner, weapon, minAngle, maxAngle, hitInfo)) {
                  if (meleeTargetTooCloseTooShoot == null) {
                     meleeTargetTooCloseTooShoot = ((HitInfo)this.hitInfoPool.alloc()).init(hitInfo);
                  } else if (this.meleeTargetComparator.compare(hitInfo, meleeTargetTooCloseTooShoot) < 0) {
                     meleeTargetTooCloseTooShoot.init(hitInfo);
                  }
               }
            } else {
               targetsProne.add(hitInfo);
            }
         }
      }

      if (!useBallistics && !targetsProne.isEmpty() && this.shouldIgnoreProneZombies(owner, targetsStanding, ignoreProneRange)) {
         this.hitInfoPool.release(targetsProne);
         targetsProne.clear();
      }

      if (weapon.isRanged()) {
         targetComparator = this.rangeTargetComparator;
      }

      if (useBallistics) {
         this.removeUnhittableBallisticsTargets(
            owner, weapon, range, this.combatConfig.get(CombatConfigKey.BALLISTICS_CONTROLLER_DISTANCE_THRESHOLD), targetsStanding
         );
      } else {
         this.removeUnhittableMeleeTargets(owner, weapon, minAngle, maxAngle, targetsStanding);
      }

      if (weapon.isRanged() && ballisticsController != null) {
         this.removeUnhittableBallisticsTargets(
            owner, weapon, range, this.combatConfig.get(CombatConfigKey.BALLISTICS_CONTROLLER_DISTANCE_THRESHOLD), targetsProne
         );
      } else {
         minAngle /= 1.5F;
         this.removeUnhittableMeleeTargets(owner, weapon, minAngle, maxAngle, targetsProne);
      }

      if (meleeTargetTooCloseTooShoot != null) {
         this.hitInfoPool.releaseAll(targetsStanding);
         targetsStanding.clear();
         targetsStanding.add(meleeTargetTooCloseTooShoot);
      }

      targetComparator.setBallisticsController(ballisticsController);
      this.timSort.doSort(targetsStanding.getElements(), targetComparator, 0, targetsStanding.size());
      this.timSort.doSort(targetsProne.getElements(), targetComparator, 0, targetsProne.size());
   }

   private boolean shouldIgnoreProneZombies(IsoGameCharacter owner, PZArrayList<HitInfo> targetsStanding, float range) {
      if (range <= 0.0F) {
         return false;
      }

      boolean isInvisible = owner.isInvisible() || owner instanceof IsoPlayer isoPlayer && isoPlayer.isGhostMode();

      for (int i = 0; i < targetsStanding.size(); i++) {
         HitInfo hitInfo = (HitInfo)targetsStanding.get(i);
         IsoZombie zombie = (IsoZombie)Type.tryCastTo(hitInfo.getObject(), IsoZombie.class);
         if ((zombie == null || zombie.target != null || isInvisible) && !(hitInfo.distSq > range * range)) {
            boolean collideStand = PolygonalMap2.instance
               .lineClearCollide(
                  owner.getX(), owner.getY(), hitInfo.getObject().getX(), hitInfo.getObject().getY(), PZMath.fastfloor(owner.getZ()), owner, false, true
               );
            if (!collideStand) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean isPointWithinDistance(Vector3 start, Vector3 end, Vector3 target, float distanceThreshold) {
      float abX = end.x - start.x;
      float abY = end.y - start.y;
      float abZ = end.z - start.z;
      float apX = target.x - start.x;
      float apY = target.y - start.y;
      float apZ = target.z - start.z;
      float crossX = apY * abZ - apZ * abY;
      float crossY = apZ * abX - apX * abZ;
      float crossZ = apX * abY - apY * abX;
      float crossSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
      float abSquared = abX * abX + abY * abY + abZ * abZ;
      float dotProduct = abX * apX + abY * apY + abZ * apZ;
      return crossSquared <= distanceThreshold * distanceThreshold * abSquared && dotProduct > 0.0F;
   }

   private void removeUnhittableBallisticsTargets(
      IsoGameCharacter isoGameCharacter, HandWeapon weapon, float range, float distanceThreshold, PZArrayList<HitInfo> targets
   ) {
      for (int i = targets.size() - 1; i >= 0; i--) {
         HitInfo hitInfo = (HitInfo)targets.get(i);
         tempVector3_1.set(hitInfo.x, hitInfo.y, hitInfo.z);
         if (!this.isPointWithinDistance(ballisticsStartPosition, ballisticsEndPosition, tempVector3_1, distanceThreshold)) {
            this.hitInfoPool.release(hitInfo);
            targets.remove(i);
         } else if (Core.debug && DebugOptions.instance.character.debug.render.aimCone.getValue()) {
            LineDrawer.DrawIsoCircle(hitInfo.x, hitInfo.y, hitInfo.z, 0.1F, TargetableDebugColor.r, TargetableDebugColor.g, TargetableDebugColor.b, 1.0F);
         }
      }
   }

   private boolean isUnhittableMeleeTarget(IsoGameCharacter owner, HandWeapon weapon, float minAngle, float maxAngle, HitInfo hitInfo) {
      if (!(hitInfo.dot < minAngle) && !(hitInfo.dot > maxAngle)) {
         Vector3 targetPos = tempVectorBonePos.set(hitInfo.x, hitInfo.y, hitInfo.z);
         return !owner.isMeleeAttackRange(weapon, hitInfo.getObject(), targetPos);
      } else {
         return true;
      }
   }

   private boolean isMeleeTargetTooCloseToShoot(IsoGameCharacter owner, HandWeapon weapon, float minAngle, float maxAngle, HitInfo hitInfo) {
      if (this.isUnhittableMeleeTarget(owner, ((IsoLivingCharacter)owner).bareHands, minAngle, maxAngle, hitInfo)) {
         return false;
      }

      tempVector3_1.set(hitInfo.x, hitInfo.y, hitInfo.z);
      return !this.isPointWithinDistance(
         ballisticsStartPosition, ballisticsEndPosition, tempVector3_1, this.combatConfig.get(CombatConfigKey.BALLISTICS_CONTROLLER_DISTANCE_THRESHOLD)
      );
   }

   private void calculateBallistics(IsoGameCharacter isoGameCharacter, float range) {
      BallisticsController ballisticsController = isoGameCharacter.getBallisticsController();
      if (ballisticsController != null) {
         ballisticsController.calculateMuzzlePosition(ballisticsStartPosition, ballisticsDirectionVector);
         ballisticsDirectionVector.normalize();
         ballisticsEndPosition.set(
            ballisticsStartPosition.x + ballisticsDirectionVector.x * range,
            ballisticsStartPosition.y + ballisticsDirectionVector.y * range,
            ballisticsStartPosition.z + ballisticsDirectionVector.z * range
         );
      }
   }

   private boolean isHittableBallisticsTarget(BallisticsController ballisticsController, float distanceThreshold, Vector3 targetPos) {
      Vector3 isoAimingPosition = ballisticsController.getIsoAimingPosition();
      return isoAimingPosition.distanceTo(targetPos) < distanceThreshold;
   }

   private boolean isHittableBallisticsTarget(IsoGameCharacter isoGameCharacter, float range, float distanceThreshold, Vector3 targetPos) {
      BallisticsController ballisticsController = isoGameCharacter.getBallisticsController();
      boolean isReticleTarget = this.isHittableBallisticsTarget(ballisticsController, distanceThreshold, targetPos);
      if (Core.debug && DebugOptions.instance.character.debug.render.aimCone.getValue() && isReticleTarget) {
         LineDrawer.DrawIsoCircle(targetPos.x, targetPos.y, targetPos.z, 0.1F, TargetableDebugColor.r, TargetableDebugColor.g, TargetableDebugColor.b, 1.0F);
      }

      return isReticleTarget || this.isPointWithinDistance(ballisticsStartPosition, ballisticsEndPosition, targetPos, distanceThreshold);
   }

   private void removeUnhittableMeleeTargets(IsoGameCharacter owner, HandWeapon weapon, float minAngle, float maxAngle, PZArrayList<HitInfo> targets) {
      for (int i = targets.size() - 1; i >= 0; i--) {
         HitInfo hitInfo = (HitInfo)targets.get(i);
         if (this.isUnhittableMeleeTarget(owner, weapon, minAngle, maxAngle, hitInfo)) {
            this.hitInfoPool.release(hitInfo);
            targets.remove(i);
         }
      }
   }

   private boolean getNearestMeleeTargetPosAndDot(IsoGameCharacter owner, HandWeapon weapon, IsoMovingObject target, Vector4f out) {
      this.getNearestTargetPosAndDot(owner, target, out);
      float dot = out.w;
      float minAngle = weapon.getMinAngle();
      float maxAngle = weapon.getMaxAngle();
      if (target instanceof IsoGameCharacter targetChr && target.isProne()) {
         minAngle /= 1.5F;
      }

      if (!(dot < minAngle) && !(dot > maxAngle)) {
         Vector3 targetPos = tempVectorBonePos.set(out.x, out.y, out.z);
         return owner.isMeleeAttackRange(weapon, target, targetPos);
      } else {
         return false;
      }
   }

   private void getNearestTargetPosAndDot(IsoGameCharacter owner, IsoMovingObject target, Vector3 bonePos, Vector2 minDistSq, Vector4f out) {
      float dot = owner.getDotWithForwardDirection(bonePos);
      dot = PZMath.clamp(dot, -1.0F, 1.0F);
      out.w = Math.max(dot, out.w);
      float distSq = IsoUtils.DistanceToSquared(
         owner.getX(), owner.getY(), PZMath.fastfloor(owner.getZ()) * 3.0F, bonePos.x, bonePos.y, PZMath.fastfloor(target.getZ()) * 3.0F
      );
      if (distSq < minDistSq.x) {
         minDistSq.x = distSq;
         out.set(bonePos.x, bonePos.y, bonePos.z, out.w);
      }
   }

   private void getNearestTargetPosAndDot(IsoGameCharacter owner, IsoMovingObject target, String boneName, Vector2 minDistSq, Vector4f out) {
      Vector3 bonePos = getBoneWorldPos(target, boneName, tempVectorBonePos);
      this.getNearestTargetPosAndDot(owner, target, bonePos, minDistSq, out);
   }

   private void getNearestTargetPosAndDot(IsoGameCharacter owner, IsoMovingObject target, Vector4f out) {
      Vector2 minDistSq = tempVector2_1.set(Float.MAX_VALUE, Float.NaN);
      out.w = Float.NEGATIVE_INFINITY;
      if (!(target instanceof IsoGameCharacter targetChr)) {
         this.getNearestTargetPosAndDot(owner, target, (String)null, minDistSq, out);
      } else {
         Vector3 headPos = tempVector3_1;
         int boneIndex = getBoneIndex(target, "Bip01_Head");
         if (boneIndex != -1 && getBoneIndex(target, "Bip01_HeadNub") != -1) {
            getBoneWorldPos(target, "Bip01_Head", tempVector3_1);
            getBoneWorldPos(target, "Bip01_HeadNub", tempVector3_2);
            tempVector3_1.addToThis(tempVector3_2);
            tempVector3_1.div(2.0F);
         } else if (boneIndex != -1) {
            getPointAlongBoneXAxis(target, "Bip01_Head", 0.075F, headPos);
            Model.vectorToWorldCoords(targetChr, headPos);
         }

         if (target.isStanding()) {
            this.getNearestTargetPosAndDot(owner, target, headPos, minDistSq, out);
            this.getNearestTargetPosAndDot(owner, target, "Bip01_Pelvis", minDistSq, out);
            Vector3 targetPos = tempVectorBonePos.set(target.getX(), target.getY(), target.getZ());
            this.getNearestTargetPosAndDot(owner, target, targetPos, minDistSq, out);
         } else {
            this.getNearestTargetPosAndDot(owner, target, headPos, minDistSq, out);
            this.getNearestTargetPosAndDot(owner, target, "Bip01_Pelvis", minDistSq, out);
            boneIndex = getBoneIndex(target, "Bip01_DressFrontNub");
            if (boneIndex == -1) {
               boneIndex = getBoneIndex(target, "Bip01_DressFront02");
               if (boneIndex != -1) {
                  Vector3 bonePos = tempVector3_2;
                  getPointAlongBoneXAxis(target, "Bip01_DressFront02", 0.2F, bonePos);
                  Model.vectorToWorldCoords(targetChr, bonePos);
                  this.getNearestTargetPosAndDot(owner, target, bonePos, minDistSq, out);
               }
            } else {
               this.getNearestTargetPosAndDot(owner, target, "Bip01_DressFrontNub", minDistSq, out);
            }
         }
      }
   }

   private HitInfo calcValidTarget(IsoLivingCharacter owner, HandWeapon weapon, IsoMovingObject mov, float range) {
      if (mov == owner) {
         return null;
      }

      if (mov instanceof IsoGameCharacter isoGameCharacter) {
         if (mov.getCurrentSquare() == null) {
            return null;
         }

         if (isoGameCharacter.isGodMod() && !isoGameCharacter.isZombie()) {
            return null;
         }

         if (!isoGameCharacter.isAnimal() && !checkPVP(owner, mov, weapon.isMelee())) {
            return null;
         }

         if (weapon != null && !weapon.isRanged() && owner.DistToSquared(isoGameCharacter) > 9.0F) {
            return null;
         }

         float deltaZ = Math.abs(isoGameCharacter.getZ() - owner.getZ());
         if (!weapon.isRanged() && deltaZ >= 0.5F) {
            return null;
         }

         if (deltaZ > 3.3F) {
            return null;
         }

         if (!isoGameCharacter.isShootable()) {
            return null;
         }

         if (isoGameCharacter.isCurrentState(FakeDeadZombieState.instance())) {
            return null;
         }

         if (isoGameCharacter.isDead()) {
            return null;
         }

         if (isoGameCharacter instanceof IsoZombie && ((IsoZombie)Type.tryCastTo(isoGameCharacter, IsoZombie.class)).isReanimatedForGrappleOnly()) {
            return null;
         }

         if (isoGameCharacter.getHitReaction() != null && isoGameCharacter.getHitReaction().contains("Death")) {
            return null;
         }

         Vector4f posAndDot = this.tempVector4f;
         this.getNearestTargetPosAndDot(owner, isoGameCharacter, posAndDot);
         float dot = posAndDot.w;
         float distSq = IsoUtils.DistanceToSquared(
            owner.getX(), owner.getY(), PZMath.fastfloor(owner.getZ()) * 3, posAndDot.x, posAndDot.y, PZMath.fastfloor(isoGameCharacter.getZ()) * 3
         );
         if (dot < 0.0F) {
            return null;
         }

         if (distSq > range * range) {
            return null;
         }

         TestResults testResults = LosUtil.lineClear(
            owner.getCell(),
            PZMath.fastfloor(owner.getX()),
            PZMath.fastfloor(owner.getY()),
            PZMath.fastfloor(owner.getZ()),
            PZMath.fastfloor(isoGameCharacter.getX()),
            PZMath.fastfloor(isoGameCharacter.getY()),
            PZMath.fastfloor(isoGameCharacter.getZ()),
            false
         );
         return testResults != TestResults.Blocked && testResults != TestResults.ClearThroughClosedDoor
            ? ((HitInfo)this.hitInfoPool.alloc()).init(isoGameCharacter, dot, distSq, posAndDot.x, posAndDot.y, posAndDot.z)
            : null;
      } else {
         return null;
      }
   }

   public boolean isProneTargetBetter(IsoGameCharacter owner, HitInfo bestStanding, HitInfo bestProne) {
      if (bestStanding == null || bestStanding.getObject() == null) {
         return false;
      }

      if (bestProne == null || bestProne.getObject() == null) {
         return false;
      }

      if (bestStanding.distSq <= bestProne.distSq) {
         return false;
      }

      boolean collideStand = PolygonalMap2.instance
         .lineClearCollide(
            owner.getX(), owner.getY(), bestStanding.getObject().getX(), bestStanding.getObject().getY(), PZMath.fastfloor(owner.getZ()), null, false, true
         );
      if (!collideStand) {
         return false;
      }

      boolean collideProne = PolygonalMap2.instance
         .lineClearCollide(
            owner.getX(), owner.getY(), bestProne.getObject().getX(), bestProne.getObject().getY(), PZMath.fastfloor(owner.getZ()), null, false, true
         );
      return !collideProne;
   }

   public static boolean checkPVP(IsoMovingObject owner, IsoMovingObject target, boolean isMelee) {
      IsoPlayer player = getPVPPlayerFromObject(owner);
      IsoPlayer targetPlayer = getPVPPlayerFromObject(target);
      if ((Boolean)PZOptional.ifPresent(targetPlayer, false, IsoGameCharacter::isGodMod)) {
         return false;
      }

      if (player == null || targetPlayer == null) {
         return true;
      }

      if (!(player instanceof IsoAnimal) && !(targetPlayer instanceof IsoAnimal)) {
         if (GameClient.client) {
            if (!ServerOptions.instance.pvp.getValue() || targetPlayer.isGodMod()) {
               return false;
            }

            if (ServerOptions.instance.safetySystem.getValue() && player.getSafety().isEnabled() && targetPlayer.getSafety().isEnabled()) {
               return false;
            }

            if (NonPvpZone.getNonPvpZone(target.getXi(), target.getYi()) != null) {
               return false;
            }

            if (NonPvpZone.getNonPvpZone(owner.getXi(), owner.getYi()) != null) {
               return false;
            }

            if (!isMelee || !player.factionPvp && !targetPlayer.factionPvp) {
               Faction playerFaction = Faction.getPlayerFaction(player);
               Faction targetFaction = Faction.getPlayerFaction(targetPlayer);
               if (targetFaction != null && playerFaction == targetFaction) {
                  return false;
               }
            }
         }

         return GameClient.client || IsoPlayer.getCoopPVP();
      } else {
         return true;
      }
   }

   public static IsoPlayer getPVPPlayerFromObject(IsoMovingObject obj) {
      if (obj instanceof BaseVehicle vehicle) {
         IsoPlayer driver = vehicle.getPVPPlayerDriver();
         if (driver != null) {
            return driver;
         }
      }

      return (IsoPlayer)Type.tryCastTo(obj, IsoPlayer.class);
   }

   private void calcHitListShove(IsoGameCharacter owner, AttackVars attackVars, PZArrayList<HitInfo> hitList) {
      boolean attackingGround = attackVars.aimAtFloor;
      HandWeapon weapon = attackVars.getWeapon((IsoLivingCharacter)owner);

      for (IsoMovingObject obj : IsoWorld.instance.currentCell.getObjectList()) {
         if (obj != owner
            && !(obj instanceof BaseVehicle)
            && obj.getCurrentSquare() != null
            && obj instanceof IsoGameCharacter isoGameCharacter
            && (!isoGameCharacter.isGodMod() || isoGameCharacter.isZombie())
            && !isoGameCharacter.isDead()
            && (!isoGameCharacter.isProne() || attackingGround)
            && !(owner.DistToSquared(obj) > 9.0F)) {
            IsoZombie zombie = (IsoZombie)Type.tryCastTo(obj, IsoZombie.class);
            if ((zombie == null || !zombie.isCurrentState(FakeDeadZombieState.instance()))
               && (zombie == null || !zombie.isReanimatedForGrappleOnly())
               && checkPVP(owner, obj, true)) {
               boolean bHittable = obj == attackVars.targetOnGround.getObject()
                  || obj.isShootable() && obj.isStanding() && !attackVars.aimAtFloor
                  || obj.isShootable() && obj.isProne() && attackVars.aimAtFloor;
               if (bHittable) {
                  Vector4f posAndDot = this.tempVector4f;
                  if (this.getNearestMeleeTargetPosAndDot(owner, weapon, obj, posAndDot)) {
                     float dot = posAndDot.w;
                     float distSq = IsoUtils.DistanceToSquared(
                        owner.getX(), owner.getY(), PZMath.fastfloor(owner.getZ()) * 3.0F, posAndDot.x, posAndDot.y, PZMath.fastfloor(posAndDot.z) * 3
                     );
                     TestResults testResults = LosUtil.lineClear(
                        owner.getCell(),
                        PZMath.fastfloor(owner.getX()),
                        PZMath.fastfloor(owner.getY()),
                        PZMath.fastfloor(owner.getZ()),
                        PZMath.fastfloor(obj.getX()),
                        PZMath.fastfloor(obj.getY()),
                        PZMath.fastfloor(obj.getZ()),
                        false
                     );
                     if (testResults != TestResults.Blocked
                        && testResults != TestResults.ClearThroughClosedDoor
                        && (
                           obj.getCurrentSquare() == null
                              || owner.getCurrentSquare() == null
                              || obj.getCurrentSquare() == owner.getCurrentSquare()
                              || !obj.getCurrentSquare().isWindowBlockedTo(owner.getCurrentSquare())
                        )
                        && (obj.getSquare() == null || owner.getSquare() == null || obj.getSquare().getTransparentWallTo(owner.getSquare()) == null)) {
                        HitInfo hitInfo = ((HitInfo)this.hitInfoPool.alloc()).init(obj, dot, distSq, posAndDot.x, posAndDot.y, posAndDot.z);
                        if (attackVars.targetOnGround.getObject() == obj) {
                           hitList.clear();
                           hitList.add(hitInfo);
                           break;
                        }

                        hitList.add(hitInfo);
                     }
                  }
               }
            }
         }
      }
   }

   private void getNearbyGrappleTargets(IsoMovingObject owner, Predicate<IsoMovingObject> predicate, Collection<IsoMovingObject> foundTargets) {
      foundTargets.clear();
      int playerX = PZMath.fastfloor(owner.getX());
      int playerY = PZMath.fastfloor(owner.getY());
      int playerZ = PZMath.fastfloor(owner.getZ());

      for (int dy = -2; dy <= 2; dy++) {
         for (int dx = -2; dx <= 2; dx++) {
            IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(playerX + dx, playerY + dy, playerZ);
            if (square != null) {
               ArrayList<IsoMovingObject> movingObjects = square.getMovingObjects();

               for (int i = 0; i < movingObjects.size(); i++) {
                  IsoMovingObject object = movingObjects.get(i);
                  if (predicate.test(object)) {
                     foundTargets.add(object);
                  }
               }

               ArrayList<IsoMovingObject> staticMovingObjects = square.getStaticMovingObjects();

               for (int i = 0; i < staticMovingObjects.size(); i++) {
                  IsoMovingObject object = staticMovingObjects.get(i);
                  if (predicate.test(object)) {
                     foundTargets.add(object);
                  }
               }
            }
         }
      }
   }

   private void calcHitListGrapple(IsoGameCharacter owner, AttackVars attackVars, PZArrayList<HitInfo> hitList) {
      ArrayList<IsoMovingObject> foundObjects = CombatManager.CalcHitListGrappleReusables.foundObjects;
      foundObjects.clear();
      this.getNearbyGrappleTargets(owner, foundObjectx -> {
         if (foundObjectx instanceof BaseVehicle) {
            return false;
         } else {
            return !(foundObjectx instanceof IsoGameCharacter isoGameCharacter) ? true : !isoGameCharacter.isGodMod() || isoGameCharacter.isZombie();
         }
      }, foundObjects);
      HandWeapon weapon = attackVars.getWeapon((IsoLivingCharacter)owner);

      for (IsoMovingObject foundObject : foundObjects) {
         if (foundObject != owner) {
            Vector4f posAndDot = CombatManager.CalcHitListGrappleReusables.posAndDot;
            if (this.getNearestMeleeTargetPosAndDot(owner, weapon, foundObject, posAndDot)) {
               TestResults testResults = LosUtil.lineClear(
                  owner.getCell(),
                  PZMath.fastfloor(owner.getX()),
                  PZMath.fastfloor(owner.getY()),
                  PZMath.fastfloor(owner.getZ()),
                  PZMath.fastfloor(foundObject.getX()),
                  PZMath.fastfloor(foundObject.getY()),
                  PZMath.fastfloor(foundObject.getZ()),
                  false
               );
               if (testResults != TestResults.Blocked
                  && testResults != TestResults.ClearThroughClosedDoor
                  && (
                     foundObject.getCurrentSquare() == null
                        || owner.getCurrentSquare() == null
                        || foundObject.getCurrentSquare() == owner.getCurrentSquare()
                        || !foundObject.getCurrentSquare().isWindowBlockedTo(owner.getCurrentSquare())
                  )
                  && foundObject.getSquare().getTransparentWallTo(owner.getSquare()) == null) {
                  float dot = posAndDot.w;
                  float distSq = IsoUtils.DistanceToSquared(
                     owner.getX(), owner.getY(), PZMath.fastfloor(owner.getZ()) * 3.0F, posAndDot.x, posAndDot.y, PZMath.fastfloor(posAndDot.z) * 3.0F
                  );
                  HitInfo hitInfo = ((HitInfo)this.hitInfoPool.alloc()).init(foundObject, dot, distSq, posAndDot.x, posAndDot.y, posAndDot.z);
                  if (attackVars.targetOnGround.getObject() == foundObject) {
                     hitList.clear();
                     hitList.add(hitInfo);
                     break;
                  }

                  hitList.add(hitInfo);
               }
            }
         }
      }

      foundObjects.clear();
   }

   private boolean removeTargetObjects(IsoMovingObject obj, IsoGameCharacter owner, boolean isMelee) {
      if (obj instanceof IsoGameCharacter gameCharacter) {
         IsoGameCharacter isoGameCharacter = gameCharacter;
         if (isoGameCharacter.isDead() || isoGameCharacter.isGodMod() && !isoGameCharacter.isZombie()) {
            return true;
         }
      }

      if (obj instanceof IsoZombie zombie) {
         IsoZombie isoZombie = zombie;
         if (isoZombie.isCurrentState(FakeDeadZombieState.instance()) || isoZombie.isReanimatedForGrappleOnly()) {
            return true;
         }
      }

      if (obj == owner) {
         return true;
      } else {
         return obj.getCurrentSquare() == null ? true : !checkPVP(owner, obj, isMelee);
      }
   }

   private void calculateHitListWeapon(IsoGameCharacter owner, AttackVars attackVars, PZArrayList<HitInfo> hitList) {
      HandWeapon weapon = attackVars.getWeapon((IsoLivingCharacter)owner);
      float weaponRange = weapon.getMaxRange(owner) * weapon.getRangeMod(owner);
      ArrayList<IsoMovingObject> objects = new ArrayList<>(IsoWorld.instance.currentCell.getObjectList());
      if (weapon.isAimedFirearm()) {
         objects.removeIf(objx -> ballisticsStartPosition.distanceTo(objx.getX(), objx.getY(), objx.getZ()) > weaponRange);
      }

      if (!weapon.isAimedFirearm()) {
         boolean attackingGround = attackVars.aimAtFloor;
         if (!attackingGround) {
            objects.removeIf(IsoMovingObject::isProne);
         }
      }

      objects.removeIf(objx -> this.removeTargetObjects(objx, owner, weapon.isMelee()));

      for (int n = 0; n < objects.size(); n++) {
         IsoMovingObject obj = objects.get(n);
         IsoGameCharacter isoGameCharacter = (IsoGameCharacter)Type.tryCastTo(obj, IsoGameCharacter.class);
         if (!weapon.isAimedFirearm()) {
            boolean bHittable = obj == attackVars.targetOnGround.getObject()
               || obj.isShootable() && obj.isStanding() && !attackVars.aimAtFloor
               || obj.isShootable() && obj.isProne() && attackVars.aimAtFloor;
            if (!bHittable) {
               continue;
            }
         }

         Vector4f posAndDot = this.tempVector4f;
         if (obj instanceof BaseVehicle) {
            if (weapon.isRanged()) {
               tempVector3_1.set(obj.getX(), obj.getY(), obj.getZ());
               if (!this.isHittableBallisticsTarget(
                  owner, weapon.getMaxRange(), this.combatConfig.get(CombatConfigKey.BALLISTICS_CONTROLLER_DISTANCE_THRESHOLD), tempVector3_1
               )) {
                  if (Core.debug && DebugOptions.instance.character.debug.render.aimCone.getValue()) {
                     LineDrawer.DrawIsoCircle(
                        tempVector3_1.x,
                        tempVector3_1.y,
                        tempVector3_1.z,
                        1.5F,
                        OccludedTargetDebugColor.r,
                        OccludedTargetDebugColor.g,
                        OccludedTargetDebugColor.b,
                        1.0F
                     );
                  }
                  continue;
               }

               posAndDot.set(obj.getX(), obj.getY(), obj.getZ());
               if (Core.debug && DebugOptions.instance.character.debug.render.aimCone.getValue()) {
                  LineDrawer.DrawIsoCircle(
                     tempVector3_1.x, tempVector3_1.y, tempVector3_1.z, 1.5F, TargetableDebugColor.r, TargetableDebugColor.g, TargetableDebugColor.b, 1.0F
                  );
               }
            } else {
               if (owner.DistToSquared(obj) > 9.0F) {
                  continue;
               }

               float dot = owner.getDotWithForwardDirection(obj.getX(), obj.getY());
               if (dot < 0.8F) {
                  continue;
               }

               posAndDot.set(obj.getX(), obj.getY(), obj.getZ(), dot);
            }
         } else {
            if (isoGameCharacter == null || !weapon.isRanged() && owner.DistToSquared(obj) > 9.0F) {
               continue;
            }

            if (weapon.isRanged()) {
               tempVector3_1.set(isoGameCharacter.getX(), isoGameCharacter.getY(), isoGameCharacter.getZ());
               posAndDot.set(isoGameCharacter.getX(), isoGameCharacter.getY(), isoGameCharacter.getZ());
               if (!this.isHittableBallisticsTarget(
                  owner, weapon.getMaxRange(), this.combatConfig.get(CombatConfigKey.BALLISTICS_CONTROLLER_DISTANCE_THRESHOLD), tempVector3_1
               )) {
                  continue;
               }
            } else if (!this.getNearestMeleeTargetPosAndDot(owner, weapon, obj, posAndDot)) {
               continue;
            }
         }

         TestResults testResults = LosUtil.lineClear(
            owner.getCell(),
            PZMath.fastfloor(owner.getX()),
            PZMath.fastfloor(owner.getY()),
            PZMath.fastfloor(owner.getZ()),
            PZMath.fastfloor(obj.getX()),
            PZMath.fastfloor(obj.getY()),
            PZMath.fastfloor(obj.getZ()),
            false
         );
         if (testResults != TestResults.Blocked
            && testResults != TestResults.ClearThroughClosedDoor
            && (!obj.isTransparentWallTo(owner) || weapon.canAttackPierceTransparentWall(owner, weapon))) {
            IsoWindow window = this.getWindowBetween(owner, weapon, owner, obj);
            if (window == null || !window.isBarricaded() || window.canAttackBypassIsoBarricade(owner, weapon)) {
               float dot = posAndDot.w;
               float distSq = IsoUtils.DistanceToSquared(
                  owner.getX(), owner.getY(), PZMath.fastfloor(owner.getZ()) * 3.0F, posAndDot.x, posAndDot.y, PZMath.fastfloor(obj.getZ()) * 3.0F
               );
               HitInfo hitInfo = ((HitInfo)this.hitInfoPool.alloc()).init(obj, dot, distSq, posAndDot.x, posAndDot.y, posAndDot.z);
               hitInfo.window.setObject(window);
               hitList.add(hitInfo);
            }
         }
      }

      if (!hitList.isEmpty() && weapon.isRanged()) {
         this.processBallisticsTargets(owner, weapon, hitList);
      } else {
         this.CalcHitListWindow(owner, weapon, hitList);
      }
   }

   private boolean isOccluded(Vector3f origin, Vector3f direction, Vector3 position, float width, float height, ArrayList<HitInfo> hitList) {
      this.rect0.setMin(position.x - width, position.y - width, position.z);
      this.rect0.setMax(position.x + width, position.y + width, position.z + height);

      for (int i = 0; i < hitList.size(); i++) {
         HitInfo hitInfo = hitList.get(i);
         this.rect1.setMin(hitInfo.x - width, hitInfo.y - width, hitInfo.z);
         this.rect1.setMax(hitInfo.x + width, hitInfo.y + width, hitInfo.z + height);
         if (this.isOccluded(origin, direction, this.rect0, this.rect1)) {
            return true;
         }
      }

      return false;
   }

   private boolean isOccluded(Vector3f origin, Vector3f direction, Rect3D rect0, Rect3D rect1) {
      float t0 = rect0.rayIntersection(origin, direction);
      float t1 = rect1.rayIntersection(origin, direction);
      return t0 > t1;
   }

   private void processBallisticsTargets(IsoGameCharacter owner, HandWeapon weapon, PZArrayList<HitInfo> hitList) {
      if (!hitList.isEmpty()) {
         for (int i = 0; i < hitList.size(); i++) {
            HitInfo hitInfo = (HitInfo)hitList.get(i);
            if (!(hitInfo.getObject() instanceof IsoAnimal) && hitInfo.getObject() instanceof IsoGameCharacter character) {
               BallisticsTarget ballisticsTarget = character.ensureExistsBallisticsTarget(character);
               if (ballisticsTarget != null) {
                  this.highlightTarget(character, Color.yellow, 0.65F);
                  ballisticsTarget.add();
               }
            }
         }
      }

      BallisticsController ballisticsController = owner.getBallisticsController();
      if (ballisticsController != null) {
         if (weapon.isRanged()) {
            IsoReticle.trySetTargetReticleMode(IsoPlayer.getPlayerIndex(owner), IsoReticleMode.RETICLE);
            float range = weapon.getMaxRange(owner);
            ballisticsController.setRange(range);
            if (weapon.isRangeFalloff()) {
               int projectileCount = weapon.getProjectileCount();
               float projectileSpread = weapon.getProjectileSpread() * ballisticsSpreadAngleScale;
               float projectileWeightCenter = weapon.getProjectileWeightCenter();
               owner.getBallisticsController().getCameraTargets(range + 0.5F, true);
               owner.getBallisticsController().getSpreadData(range, projectileSpread, projectileWeightCenter, projectileCount);
            } else {
               owner.getBallisticsController().getCameraTargets(range + 0.5F, true);
               owner.getBallisticsController().getTargets(range);
            }
         }

         if (!hitList.isEmpty()) {
            for (int i = 0; i < hitList.size(); i++) {
               HitInfo hitInfo = (HitInfo)hitList.get(i);
               if (hitInfo.getObject() instanceof BaseVehicle baseVehicle) {
                  boolean wasVehicleHit = this.checkHitVehicle(owner, baseVehicle);
                  if (!wasVehicleHit) {
                     hitList.remove(i);
                     i--;
                  } else {
                     owner.getBallisticsController().setBallisticsTargetHitLocation(baseVehicle.getId(), hitInfo);
                     if (DebugOptions.instance.physicsRenderBallisticsTargets.getValue()) {
                        LineDrawer.DrawIsoCircle(
                           hitInfo.x, hitInfo.y, hitInfo.z, 0.1F, TargetableDebugColor.r, TargetableDebugColor.g, TargetableDebugColor.b, 1.0F
                        );
                     }
                  }
               } else if (hitInfo.getObject() instanceof IsoGameCharacter isoGameCharacter) {
                  owner.getBallisticsController().setBallisticsTargetHitLocation(isoGameCharacter.getID(), hitInfo);
                  owner.getBallisticsController().setBallisticsCameraTargetHitLocation(isoGameCharacter.getID(), hitInfo);
                  BallisticsTarget ballisticsTarget = isoGameCharacter.getBallisticsTarget();
                  if (ballisticsTarget != null) {
                     int id = isoGameCharacter.getID();
                     if (!ballisticsController.isValidTarget(id) && !ballisticsController.isValidCachedTarget(id)) {
                        this.highlightTarget(isoGameCharacter, Color.white, 0.65F);
                        hitList.remove(i);
                        i--;
                     } else {
                        if (ballisticsController.isCameraTarget(id) || ballisticsController.isCachedCameraTarget(id)) {
                           this.highlightTarget(isoGameCharacter, Color.red, 0.65F);
                        }

                        if (ballisticsController.isSpreadTarget(id) || ballisticsController.isCachedSpreadTarget(id)) {
                           this.highlightTarget(isoGameCharacter, Color.magenta, 0.65F);
                        }
                     }
                  }
               }
            }
         }

         if (weapon.isRangeFalloff()) {
            if (!hitList.isEmpty()) {
               int hitListSize = hitList.size();

               for (int i = 0; i < hitListSize; i++) {
                  HitInfo hitInfo = (HitInfo)hitList.get(i);
                  if (hitInfo.getObject() instanceof IsoGameCharacter isoGameCharacter) {
                     BallisticsTarget ballisticsTarget = isoGameCharacter.getBallisticsTarget();
                     if (ballisticsTarget != null) {
                        int count = ballisticsController.spreadCount(isoGameCharacter.getID());
                        if (count > 1) {
                           for (int j = 0; j < count - 1; j++) {
                              HitInfo copy = ((HitInfo)this.hitInfoPool.alloc()).init(hitInfo);
                              hitList.add(copy);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean isWindowInHitList(IsoWindow window, PZArrayList<HitInfo> hitList) {
      for (int i = 0; i < hitList.size(); i++) {
         HitInfo hitInfo1 = (HitInfo)hitList.get(i);
         if (hitInfo1.getWindow() == window) {
            return true;
         }
      }

      return false;
   }

   private void CalcHitListWindow(IsoGameCharacter owner, HandWeapon weapon, PZArrayList<HitInfo> hitList) {
      Vector2 lookVector = owner.getLookVector(tempVector2_1);
      lookVector.setLength(weapon.getMaxRange(owner) * weapon.getRangeMod(owner));
      HitInfo hitInfo = null;
      this.windowVisitor.init(owner, weapon);
      ArrayList<IsoWindow> windows = IsoWorld.instance.currentCell.getWindowList();

      for (int i = 0; i < windows.size(); i++) {
         IsoWindow window = windows.get(i);
         if (PZMath.fastfloor(window.getZ()) == PZMath.fastfloor(owner.getZ())
            && !this.isWindowInHitList(window, hitList)
            && this.windowVisitor.isHittable(window)) {
            float windowX1 = window.getX();
            float windowY1 = window.getY();
            float windowX2 = windowX1 + (window.getNorth() ? 1.0F : 0.0F);
            float windowY2 = windowY1 + (window.getNorth() ? 0.0F : 1.0F);
            if (Line2D.linesIntersect(
               owner.getX(), owner.getY(), owner.getX() + lookVector.x, owner.getY() + lookVector.y, windowX1, windowY1, windowX2, windowY2
            )) {
               IsoGridSquare square = window.getAddSheetSquare(owner);
               if (square != null && !LosUtil.lineClearCollide(owner.getXi(), owner.getYi(), owner.getZi(), square.x, square.y, square.z, false)) {
                  float distSq = IsoUtils.DistanceToSquared(
                     owner.getX(), owner.getY(), windowX1 + (windowX2 - windowX1) / 2.0F, windowY1 + (windowY2 - windowY1) / 2.0F
                  );
                  if (hitInfo == null || !(hitInfo.distSq < distSq)) {
                     float dot = 1.0F;
                     if (hitInfo == null) {
                        hitInfo = (HitInfo)this.hitInfoPool.alloc();
                     }

                     hitInfo.init(window, 1.0F, distSq);
                  }
               }
            }
         }
      }

      if (hitInfo != null) {
         hitList.add(hitInfo);
      }
   }

   public void calculateHitInfoList(IsoGameCharacter owner) {
      boolean scoped = pzopt.SubframeCombat.beginAcceptedAimScope(owner);
      try {
         this.calculateHitInfoListNative(owner);
      } finally {
         pzopt.SubframeCombat.endAcceptedAimScope(scoped);
      }
   }

   private void calculateHitInfoListNative(IsoGameCharacter owner) {
      if (GameClient.client && !owner.isLocal()) {
         owner.updateHasTargetFlag();
      } else {
         PZArrayList<HitInfo> hitInfoList = owner.getHitInfoList();
         if (!hitInfoList.isEmpty()) {
            owner.clearHitInfo();
         }

         AttackVars attackVars = owner.getAttackVars();
         HandWeapon weapon = attackVars.getWeapon((IsoLivingCharacter)owner);
         int maxHit = weapon.getMaxHitCount();
         if (attackVars.doShove) {
            maxHit = WeaponType.getWeaponType(owner) != WeaponType.UNARMED ? 3 : 1;
         }

         if (!weapon.isRanged() && !SandboxOptions.instance.multiHitZombies.getValue()) {
            maxHit = 1;
         }

         if (weapon == ((IsoPlayer)owner).bareHands && !(owner.getPrimaryHandItem() instanceof HandWeapon)) {
            maxHit = 1;
         }

         if (weapon == ((IsoPlayer)owner).bareHands && attackVars.targetOnGround.getObject() != null) {
            maxHit = 1;
         }

         if (maxHit <= 0) {
            owner.updateHasTargetFlag();
         } else {
            if (attackVars.doShove) {
               this.calcHitListShove(owner, attackVars, hitInfoList);
            } else if (attackVars.doGrapple) {
               this.calcHitListGrapple(owner, attackVars, hitInfoList);
            } else {
               this.calculateHitListWeapon(owner, attackVars, hitInfoList);
            }

            if (hitInfoList.size() == 1 && ((HitInfo)hitInfoList.get(0)).getObject() == null) {
               owner.updateHasTargetFlag();
            } else {
               float ownerZ = owner.getZ();
               if (weapon.isRanged() && !weapon.isRangeFalloff()) {
                  this.filterTargetsByZ(ownerZ, hitInfoList);
               }

               TargetComparator targetComparator = this.meleeTargetComparator;
               if (weapon.isRanged()) {
                  targetComparator = this.rangeTargetComparator;
               }

               BallisticsController ballisticsController = owner.getBallisticsController();
               targetComparator.setBallisticsController(ballisticsController);
               this.timSort.doSort(hitInfoList.getElements(), targetComparator, 0, hitInfoList.size());
               if (!weapon.isRanged()) {
                  while (hitInfoList.size() > maxHit) {
                     this.hitInfoPool.release((HitInfo)hitInfoList.removeLast());
                  }
               }

               if (weapon.isRanged()) {
                  HitList2.clear();
                  int hitCount = 0;
                  double referenceAngle = -1.0;

                  for (int i = 0; i < hitInfoList.size(); i++) {
                     HitInfo hitInfo = (HitInfo)hitInfoList.get(i);
                     IsoMovingObject obj = hitInfo.getObject();
                     int id = obj.getID();
                     if (!(obj instanceof BaseVehicle baseVehicle && !ballisticsController.isValidTarget(baseVehicle.vehicleId))
                        && (!(obj instanceof IsoGameCharacter) || obj instanceof IsoAnimal || ballisticsController.isValidTarget(id))) {
                        if (weapon.isPiercingBullets()) {
                           double angleDeg = Math.toDegrees(Math.atan2(owner.getY() - obj.getY(), obj.getX() - owner.getX()));
                           if (referenceAngle < 0.0) {
                              referenceAngle = angleDeg;
                           } else if (Math.abs(referenceAngle - angleDeg) >= 1.0) {
                              continue;
                           }
                        }

                        HitList2.add(hitInfo);
                        hitInfoList.remove(i--);
                        if (++hitCount >= maxHit) {
                           break;
                        }
                     }
                  }

                  this.hitInfoPool.release(hitInfoList);
                  hitInfoList.clear();
                  hitInfoList.addAll(HitList2);
                  HitList2.clear();
               }

               for (int i = 0; i < hitInfoList.size(); i++) {
                  HitInfo hitInfo = (HitInfo)hitInfoList.get(i);
                  CombatManager.HitChanceData hitChanceData = this.calculateHitChanceData(owner, weapon, hitInfo);
                  hitInfo.chance = (int)hitChanceData.hitChance;
                  if (DebugOptions.instance.character.debug.alwaysHitTarget.getValue()) {
                     hitInfo.chance = (int)this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE);
                  }
               }

               owner.updateHasTargetFlag();
            }
         }
      }
   }

   private void filterTargetsByZ(float ownerZ, PZArrayList<HitInfo> hitList) {
      float minDeltaZ = Float.MAX_VALUE;
      HitInfo hitInfoMinDeltaZ = null;

      for (int i = 0; i < hitList.size(); i++) {
         HitInfo hitInfo = (HitInfo)hitList.get(i);
         IsoMovingObject object = hitInfo.getObject();
         float targetZ = object == null ? hitInfo.z : object.getZ();
         float deltaZ = Math.abs(targetZ - ownerZ);
         if (deltaZ < minDeltaZ) {
            minDeltaZ = deltaZ;
            hitInfoMinDeltaZ = hitInfo;
         }
      }

      if (hitInfoMinDeltaZ != null) {
         for (int i = hitList.size() - 1; i >= 0; i--) {
            HitInfo hitInfo = (HitInfo)hitList.get(i);
            if (hitInfo != hitInfoMinDeltaZ) {
               IsoMovingObject object = hitInfo.getObject();
               float targetZ = object == null ? hitInfo.z : object.getZ();
               float deltaZ = Math.abs(targetZ - hitInfoMinDeltaZ.z);
               if (deltaZ > 0.5F) {
                  this.hitInfoPool.release(hitInfo);
                  hitList.remove(i);
               }
            }
         }
      }
   }

   private static int getBoneIndex(IsoMovingObject target, String boneName) {
      IsoGameCharacter isoGameCharacter = (IsoGameCharacter)Type.tryCastTo(target, IsoGameCharacter.class);
      if (isoGameCharacter != null && boneName != null) {
         AnimationPlayer animPlayer = isoGameCharacter.getAnimationPlayer();
         return animPlayer != null && animPlayer.isReady() ? animPlayer.getSkinningBoneIndex(boneName, -1) : -1;
      } else {
         return -1;
      }
   }

   public static Vector3 getBoneWorldPos(IsoMovingObject target, String boneName, Vector3 bonePos) {
      int boneIndex = getBoneIndex(target, boneName);
      if (boneIndex == -1) {
         return bonePos.set(target.getX(), target.getY(), target.getZ());
      }

      Model.boneToWorldCoords((IsoGameCharacter)target, boneIndex, bonePos);
      return bonePos;
   }

   private static Vector3 getPointAlongBoneXAxis(IsoMovingObject target, String boneName, float distance, Vector3 bonePos) {
      int boneIndex = getBoneIndex(target, boneName);
      if (boneIndex == -1) {
         return bonePos.set(target.getX(), target.getY(), target.getZ());
      }

      AnimationPlayer animPlayer = ((IsoGameCharacter)target).getAnimationPlayer();
      Matrix4f boneMtx = animPlayer.getModelTransformAt(boneIndex);
      float posx = boneMtx.m03;
      float posy = boneMtx.m13;
      float posz = boneMtx.m23;
      float xAxisx = boneMtx.m00;
      float xAxisy = boneMtx.m10;
      float xAxisz = boneMtx.m20;
      return bonePos.set(posx + xAxisx * distance, posy + xAxisy * distance, posz + xAxisz * distance);
   }

   public float getDistanceModifierSightless(float dist, boolean prone) {
      float pointBlankDistance = this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE);
      if (dist > pointBlankDistance) {
         return (pointBlankDistance - dist)
            * (
               this.combatConfig.get(CombatConfigKey.SIGHTLESS_TO_HIT_BASE_DISTANCE)
                  + (pointBlankDistance - dist) * -this.combatConfig.get(CombatConfigKey.POINT_BLANK_DROP_OFF_TO_HIT_PENALTY)
            );
      } else {
         return dist < pointBlankDistance
            ? (pointBlankDistance - dist)
               / pointBlankDistance
               * this.combatConfig.get(CombatConfigKey.POINT_BLANK_TO_HIT_MAXIMUM_BONUS)
               * (prone ? this.combatConfig.get(CombatConfigKey.SIGHTLESS_TO_HIT_PRONE_MODIFIER) : 1.0F)
            : 0.0F;
      }
   }

   public float getAimDelayPenaltySightless(float aimDelay, float dist) {
      float pointBlankDistance = this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE);
      if (dist < pointBlankDistance) {
         aimDelay *= dist / pointBlankDistance;
      } else if (dist > pointBlankDistance) {
         aimDelay *= 1.0F + (dist - pointBlankDistance * this.combatConfig.get(CombatConfigKey.SIGHTLESS_AIM_DELAY_TO_HIT_DISTANCE_MODIFIER));
      }

      return aimDelay;
   }

   public float getDistanceModifier(float dist, float min, float max, boolean prone) {
      if (dist < min) {
         return dist > this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE)
            ? (dist - min)
               * (
                  this.combatConfig.get(CombatConfigKey.OPTIMAL_RANGE_DROP_OFF_TO_HIT_PENALTY)
                     + (dist - min) * -this.combatConfig.get(CombatConfigKey.OPTIMAL_RANGE_DROP_OFF_TO_HIT_PENALTY_INCREMENT)
               )
            : 0.0F;
      }

      if (dist > max) {
         return -(
            (dist - max)
               * (
                  this.combatConfig.get(CombatConfigKey.OPTIMAL_RANGE_DROP_OFF_TO_HIT_PENALTY)
                     + (dist - max) * this.combatConfig.get(CombatConfigKey.OPTIMAL_RANGE_DROP_OFF_TO_HIT_PENALTY_INCREMENT)
               )
         );
      }

      float scale = (max - min) * 0.5F;
      return (float)(
         this.combatConfig.get(CombatConfigKey.OPTIMAL_RANGE_TO_HIT_MAXIMUM_BONUS)
            * Math.exp(-PZMath.pow(dist - (min + scale), 2.0F) / PZMath.pow(2.0F * ((max - min) / 7.0F), 2.0F))
      );
   }

   public static float getMovePenalty(IsoGameCharacter character, float dist) {
      float penalty = character.getBeenMovingFor() * (1.0F - (character.getPerkLevel(Perks.Aiming) + character.getPerkLevel(Perks.Nimble)) / 40.0F);
      if (dist < 10.0F) {
         penalty *= dist / 10.0F;
      } else {
         penalty *= 1.0F + (dist - 10.0F) * 0.07F;
      }

      return penalty;
   }

   public float getAimDelayPenalty(float delay, float dist, float min, float max) {
      if (min > -1.0F && dist >= min && dist <= max) {
         float scale = (max - min) * 0.5F;
         delay *= 1.0F - (1.0F - Math.abs(dist - (min + scale)) / scale) * 0.25F;
      } else if (dist > max) {
         delay *= 1.0F + (dist - max) * 0.1F;
      }

      return delay
         * (dist < this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE) ? dist / this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE) : 1.0F);
   }

   public float getMoodlesPenalty(IsoGameCharacter character, float distance) {
      return (
            character.getMoodles().getMoodleLevel(MoodleType.PANIC)
                  * (
                     this.combatConfig.get(CombatConfigKey.PANIC_TO_HIT_BASE_PENALTY)
                        + distance * this.combatConfig.get(CombatConfigKey.PANIC_TO_HIT_DISTANCE_MODIFIER)
                  )
               + character.getMoodles().getMoodleLevel(MoodleType.STRESS)
                  * (
                     this.combatConfig.get(CombatConfigKey.STRESS_TO_HIT_BASE_PENALTY)
                        + distance * this.combatConfig.get(CombatConfigKey.STRESS_TO_HIT_DISTANCE_MODIFIER)
                  )
               + character.getMoodles().getMoodleLevel(MoodleType.TIRED) * this.combatConfig.get(CombatConfigKey.TIRED_TO_HIT_BASE_PENALTY)
               + character.getMoodles().getMoodleLevel(MoodleType.ENDURANCE) * this.combatConfig.get(CombatConfigKey.ENDURANCE_TO_HIT_BASE_PENALTY)
               + character.getMoodles().getMoodleLevel(MoodleType.DRUNK)
                  * (
                     this.combatConfig.get(CombatConfigKey.DRUNK_TO_HIT_BASE_PENALTY)
                        + distance * this.combatConfig.get(CombatConfigKey.DRUNK_TO_HIT_DISTANCE_MODIFIER)
                  )
         )
         * (float)SandboxOptions.instance.firearmMoodleMultiplier.getValue();
   }

   public float getWeatherPenalty(IsoGameCharacter character, HandWeapon weapon, IsoGridSquare square, float distance) {
      float weatherPenalty = 0.0F;
      if (square == null) {
         return weatherPenalty;
      }

      boolean isThermal = weapon.getActiveSight() != null && weapon.getActiveSight().hasTag(ItemTag.THERMAL);
      if (square.isOutside()) {
         weatherPenalty += ClimateManager.getInstance().getWindIntensity()
            * (
               this.combatConfig.get(CombatConfigKey.WIND_INTENSITY_TO_HIT_PENALTY)
                  - character.getPerkLevel(Perks.Aiming) * this.combatConfig.get(CombatConfigKey.WIND_INTENSITY_TO_HIT_AIMING_MODIFIER)
            )
            * distance
            * (
               character.hasTrait(CharacterTrait.MARKSMAN)
                  ? this.combatConfig.get(CombatConfigKey.WIND_INTENSITY_TO_HIT_MINIMUM_MARKSMAN_MODIFIER)
                  : this.combatConfig.get(CombatConfigKey.WIND_INTENSITY_TO_HIT_MAXIMUM_MARKSMAN_MODIFIER)
            );
         weatherPenalty += ClimateManager.getInstance().getRainIntensity()
            * distance
            * this.combatConfig.get(CombatConfigKey.RAIN_INTENSITY_TO_HIT_DISTANCE_MODIFIER);
         weatherPenalty *= character.getCharacterTraits().getTraitWeatherPenaltyModifier();
         if (!isThermal) {
            weatherPenalty += ClimateManager.getInstance().getFogIntensity()
               * this.combatConfig.get(CombatConfigKey.FOG_INTENSITY_DISTANCE_MODIFIER)
               * distance;
         }

         weatherPenalty *= PZMath.min(
            distance / this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE),
            this.combatConfig.get(CombatConfigKey.POINT_BLANK_MAXIMUM_DISTANCE_MODIFIER)
         );
         weatherPenalty *= (float)SandboxOptions.instance.firearmWeatherMultiplier.getValue();
      }

      if (isThermal) {
         return weatherPenalty;
      }

      float light = square.getLightLevel(character instanceof IsoPlayer isoPlayer ? isoPlayer.getIndex() : -1);
      if (light < this.combatConfig.get(CombatConfigKey.LOW_LIGHT_THRESHOLD)) {
         float lightPenalty = PZMath.max(
            0.0F,
            this.combatConfig.get(CombatConfigKey.LOW_LIGHT_TO_HIT_MAXIMUM_PENALTY)
               * (1.0F - light / this.combatConfig.get(CombatConfigKey.LOW_LIGHT_THRESHOLD))
         );
         weatherPenalty += lightPenalty - weapon.getLowLightBonus();
      }

      return weatherPenalty;
   }

   public float getPainPenalty(IsoGameCharacter character) {
      float armsPain = 0.0F;

      for (int x = BodyPartType.ToIndex(BodyPartType.Hand_L); x <= BodyPartType.ToIndex(BodyPartType.UpperArm_R); x++) {
         armsPain += ((BodyPart)character.getBodyDamage().getBodyParts().get(x)).getPain();
      }

      return armsPain * this.combatConfig.get(CombatConfigKey.ARM_PAIN_TO_HIT_MODIFIER);
   }

   private CombatManager.HitChanceData calculateHitChanceData(IsoGameCharacter owner, HandWeapon weapon, HitInfo hitInfo) {
      CombatManager.HitChanceData hitChanceData = new CombatManager.HitChanceData();
      IsoMovingObject target = null;
      if (hitInfo != null) {
         target = hitInfo.getObject();
      }

      float hitChance = Math.min(weapon.getHitChance(), this.combatConfig.get(CombatConfigKey.MAXIMUM_START_TO_HIT_CHANCE));
      hitChance += weapon.getAimingPerkHitChanceModifier() * owner.getPerkLevel(Perks.Aiming);
      if (owner.getVehicle() != null && target != null) {
         BaseVehicle v = owner.getVehicle();
         Vector3f fwd = v.getForwardVector((Vector3f)((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc());
         Vector2 outwindowdirection = (Vector2)Vector2ObjectPool.get().alloc();
         outwindowdirection.x = fwd.x;
         outwindowdirection.y = fwd.z;
         outwindowdirection.normalize();
         Vector2 zombieDirection = (Vector2)Vector2ObjectPool.get().alloc();
         zombieDirection.x = target.getX();
         zombieDirection.y = target.getY();
         zombieDirection.x = zombieDirection.x - owner.getX();
         zombieDirection.y = zombieDirection.y - owner.getY();
         zombieDirection.normalize();
         boolean backwards = zombieDirection.dot(outwindowdirection) < 0.0F;
         int seat = v.getSeat(owner);
         int rotation = v.isPointLeftOfCenter(owner.getX(), owner.getY()) ? 90 : -90;
         outwindowdirection.rotate((float)Math.toRadians(rotation));
         outwindowdirection.normalize();
         float facing = zombieDirection.dot(outwindowdirection);
         Vector2ObjectPool.get().release(outwindowdirection);
         Vector2ObjectPool.get().release(zombieDirection);
         ((Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(fwd);
         if (facing > -0.6F && !weapon.isRanged()) {
            return hitChanceData;
         }

         if (facing > this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_MAXIMUM_ANGLE)) {
            return hitChanceData;
         }

         if (!weapon.isRanged()) {
            hitChanceData.hitChance = this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE);
            return hitChanceData;
         }

         if (owner.isDriving() && weapon.isTwoHandWeapon()) {
            return hitChanceData;
         }

         if (facing > this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_OPTIMAL_ANGLE)) {
            float maxFacing = this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_MAXIMUM_ANGLE);
            if (v.isDriver(owner)) {
               maxFacing -= 0.15F;
            }

            VehiclePart part = v.getPartForSeatContainer(seat);
            if (part != null) {
               maxFacing -= part.getItemContainer().getCapacityWeight() * (weapon.isTwoHandWeapon() ? 0.05F : 0.025F);
            }

            maxFacing -= owner.getInventory().getCapacityWeight() * 0.01F;
            if (backwards) {
               maxFacing -= weapon.isTwoHandWeapon() ? 0.15F : 0.1F;
            }

            float penalty = PZMath.clamp(
                  this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_TO_HIT_MAXIMUM_PENALTY)
                     * (1.0F - (facing - 0.1F) / (this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_OPTIMAL_ANGLE) - 0.1F)),
                  0.0F,
                  this.combatConfig.get(CombatConfigKey.DRIVEBY_DOT_TO_HIT_MAXIMUM_PENALTY)
               )
               * (weapon.isTwoHandWeapon() ? 1.5F : 1.0F)
               * (backwards ? 1.5F : 1.0F)
               * (facing <= maxFacing ? 1.0F : 3.0F);
            hitChance -= penalty;
            hitChanceData.aimPenalty += penalty;
         }

         float vehicleSpeedPenalty = Math.abs(v.getCurrentSpeedKmHour()) * (v.isDriver(owner) ? 3.0F : 2.0F);
         hitChance -= vehicleSpeedPenalty;
         hitChanceData.aimPenalty += vehicleSpeedPenalty;
      }

      if (!weapon.isRanged()) {
         hitChanceData.hitChance = this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE);
         return hitChanceData;
      }

      float max = weapon.getMaxSightRange(owner);
      float min = weapon.getMinSightRange(owner);
      float dist = hitInfo != null ? PZMath.sqrt(hitInfo.distSq) : max;
      boolean isProne = false;
      if (target != null) {
         isProne = target.isProne();
      }

      float delayPenaltySightless = this.getAimDelayPenaltySightless(PZMath.max(0.0F, owner.getAimingDelay()), dist);
      float delayPenaltySights = this.getAimDelayPenalty(PZMath.max(0.0F, owner.getAimingDelay()), dist, min, max);
      float distanceModifierSightless = this.getDistanceModifierSightless(dist, isProne);
      float distanceModifierSights = this.getDistanceModifier(dist, min, max, isProne);
      float combinedPenalty = 0.0F;
      float sightsPenalty = PZMath.max(distanceModifierSightless - delayPenaltySightless, distanceModifierSights - delayPenaltySights);
      hitChance += sightsPenalty;
      hitChanceData.aimPenalty -= sightsPenalty;
      float modifier = getMovePenalty(owner, dist);
      combinedPenalty += modifier;
      if (hitInfo != null && hitInfo.getObject() instanceof IsoPlayer plyr) {
         if (plyr.getVehicle() != null) {
            float vehicleSpeedPenalty = Math.abs(plyr.getVehicle().getCurrentSpeedKmHour()) * 2.0F;
            hitChance -= vehicleSpeedPenalty;
            hitChanceData.aimPenalty += vehicleSpeedPenalty;
         } else if (plyr.isSprinting()) {
            hitChance -= this.combatConfig.get(CombatConfigKey.SPRINTING_TO_HIT_PENALTY);
            hitChanceData.aimPenalty = hitChanceData.aimPenalty + this.combatConfig.get(CombatConfigKey.SPRINTING_TO_HIT_PENALTY);
         } else if (plyr.isRunning()) {
            hitChance -= this.combatConfig.get(CombatConfigKey.RUNNING_TO_HIT_PENALTY);
            hitChanceData.aimPenalty = hitChanceData.aimPenalty + this.combatConfig.get(CombatConfigKey.RUNNING_TO_HIT_PENALTY);
         } else if (plyr.isPlayerMoving()) {
            hitChance -= this.combatConfig.get(CombatConfigKey.MOVING_TO_HIT_PENALTY);
            hitChanceData.aimPenalty = hitChanceData.aimPenalty + this.combatConfig.get(CombatConfigKey.MOVING_TO_HIT_PENALTY);
         }
      }

      if (owner.hasTrait(CharacterTrait.MARKSMAN)) {
         hitChance += this.combatConfig.get(CombatConfigKey.MARKSMAN_TRAIT_TO_HIT_BONUS);
      }

      modifier = this.getPainPenalty(owner);
      combinedPenalty += modifier;
      IsoGridSquare isoGridSquare = owner.getSquare();
      if (target != null) {
         isoGridSquare = target.getSquare();
      }

      modifier = this.getWeatherPenalty(owner, weapon, isoGridSquare, dist);
      combinedPenalty += modifier;
      modifier = this.getMoodlesPenalty(owner, dist);
      combinedPenalty += modifier;
      if (SandboxOptions.instance.firearmHeadGearEffect.getValue()) {
         modifier = this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE)
            - this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE) / owner.getWornItemsVisionModifier();
         combinedPenalty += modifier;
      }

      if (dist < this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE)) {
         float baseChance = dist / this.combatConfig.get(CombatConfigKey.POINT_BLANK_DISTANCE);
         combinedPenalty *= baseChance / 5.0F;
         hitChance /= baseChance * 1.1F;
      }

      hitChance -= combinedPenalty;
      hitChanceData.aimPenalty += combinedPenalty;
      hitChanceData.hitChance = PZMath.clamp(
         (int)hitChance, this.combatConfig.get(CombatConfigKey.MINIMUM_TO_HIT_CHANCE), this.combatConfig.get(CombatConfigKey.MAXIMUM_TO_HIT_CHANCE)
      );
      return hitChanceData;
   }

   private TestResults los(int x0, int y0, int x1, int y1, int z, CombatManager.LOSVisitor visitor) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int z0 = z;
      int z1 = z;
      int dy = y1 - y0;
      int dx = x1 - x0;
      int dz = z1 - z0;
      float t = 0.5F;
      float t2 = 0.5F;
      IsoGridSquare b = cell.getGridSquare(x0, y0, z0);
      if (Math.abs(dx) > Math.abs(dy)) {
         float m = (float)dy / dx;
         float m2 = (float)dz / dx;
         t += y0;
         t2 += z0;
         dx = dx < 0 ? -1 : 1;
         m *= dx;
         m2 *= dx;

         while (x0 != x1) {
            x0 += dx;
            t += m;
            t2 += m2;
            IsoGridSquare a = cell.getGridSquare(x0, PZMath.fastfloor(t), PZMath.fastfloor(t2));
            if (visitor.visit(a, b)) {
               return visitor.getResult();
            }

            b = a;
         }
      } else {
         float m = (float)dx / dy;
         float m2 = (float)dz / dy;
         t += x0;
         t2 += z0;
         dy = dy < 0 ? -1 : 1;
         m *= dy;
         m2 *= dy;

         while (y0 != y1) {
            y0 += dy;
            t += m;
            t2 += m2;
            IsoGridSquare a = cell.getGridSquare(PZMath.fastfloor(t), y0, PZMath.fastfloor(t2));
            if (visitor.visit(a, b)) {
               return visitor.getResult();
            }

            b = a;
         }
      }

      return TestResults.Clear;
   }

   private IsoWindow getWindowBetween(IsoGameCharacter owner, HandWeapon weapon, int x0, int y0, int x1, int y1, int z) {
      this.windowVisitor.init(owner, weapon);
      this.los(x0, y0, x1, y1, z, this.windowVisitor);
      return this.windowVisitor.window;
   }

   private IsoWindow getWindowBetween(IsoGameCharacter owner, HandWeapon weapon, IsoMovingObject a, IsoMovingObject b) {
      return this.getWindowBetween(
         owner,
         weapon,
         PZMath.fastfloor(a.getX()),
         PZMath.fastfloor(a.getY()),
         PZMath.fastfloor(b.getX()),
         PZMath.fastfloor(b.getY()),
         PZMath.fastfloor(a.getZ())
      );
   }

   private boolean isWindowBetween(IsoGameCharacter owner, HandWeapon weapon, IsoMovingObject a, IsoMovingObject b) {
      return this.getWindowBetween(owner, weapon, a, b) != null;
   }

   private void smashWindowBetween(IsoGameCharacter owner, IsoMovingObject target, HandWeapon weapon) {
      if (target != null) {
         IsoWindow window = this.getWindowBetween(owner, weapon, owner, target);
         if (window != null && !window.IsOpen()) {
            window.smashWindow();
         }
      }
   }

   public void Reset() {
      this.hitInfoPool.forEach(hitInfo -> hitInfo.object = null);
   }

   private HitReaction resolveHitReaction(IsoGameCharacter wielder, IsoGameCharacter target, ShotDirection shotDirection) {
      boolean criticalHit = wielder.isCriticalHit();
      switch (shotDirection) {
         case NORTH:
            if (criticalHit) {
               return HitReaction.SHOT_HEAD_BWD;
            }

            return target.isHitFromBehind() ? HitReaction.SHOT_BELLY_STEP_BEHIND : (Rand.Next(2) == 0 ? HitReaction.SHOT_BELLY : HitReaction.SHOT_BELLY_STEP);
         case SOUTH:
            if (criticalHit) {
               return Rand.Next(2) == 0 ? HitReaction.SHOT_HEAD_FWD : HitReaction.SHOT_HEAD_FWD02;
            }

            return target.isHitFromBehind() ? HitReaction.SHOT_BELLY_STEP_BEHIND : (Rand.Next(2) == 0 ? HitReaction.SHOT_BELLY : HitReaction.SHOT_BELLY_STEP);
         case LEFT:
         case RIGHT:
            if (criticalHit && Rand.Next(4) == 0) {
               return HitReaction.SHOT_HEAD_BWD;
            } else {
               if (target.isHitFromBehind()) {
                  return switch (Rand.Next(3)) {
                     case 0 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_CHEST_L : HitReaction.SHOT_CHEST_R;
                     case 1 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_LEG_L : HitReaction.SHOT_LEG_R;
                     default -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_SHOULDER_STEP_L : HitReaction.SHOT_SHOULDER_STEP_R;
                  };
               }
               return switch (Rand.Next(5)) {
                  case 0 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_CHEST_L : HitReaction.SHOT_CHEST_R;
                  case 1 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_CHEST_STEP_L : HitReaction.SHOT_CHEST_STEP_R;
                  case 2 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_LEG_L : HitReaction.SHOT_LEG_R;
                  case 3 -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_SHOULDER_L : HitReaction.SHOT_SHOULDER_R;
                  default -> shotDirection == ShotDirection.LEFT ? HitReaction.SHOT_SHOULDER_STEP_L : HitReaction.SHOT_SHOULDER_STEP_R;
               };
            }
         default:
            return HitReaction.NONE;
      }
   }

   public int processHit(HandWeapon weapon, IsoGameCharacter wielder, IsoGameCharacter target) {
      HitReaction hitReaction = HitReaction.fromString(wielder.getVariableString("ZombieHitReaction"));
      this.setUsePhysicHitReaction(weapon, target);
      ShotDirection shotDirection = this.calculateShotDirection(wielder, target);
      if (hitReaction == HitReaction.SHOT) {
         wielder.setCriticalHit(Rand.Next(100) < ((IsoPlayer)wielder).calculateCritChance(target));
         BallisticsController ballisticsController = wielder.getBallisticsController();
         boolean isCameraTarget = ballisticsController != null && ballisticsController.isCameraTarget(target.getID());
         int targetedBodyPart = isCameraTarget ? ballisticsController.getCachedTargetedBodyPart(target.getID()) : RagdollBodyPart.BODYPART_COUNT.ordinal();
         if (isCameraTarget) {
            String bodyPartName = RagdollBodyPart.values()[targetedBodyPart].name();
            DebugType.Combat
               .debugln("CombatManager::ProcessHit %d isCameraTarget and hit BodyPart %d - %s", new Object[]{target.getID(), targetedBodyPart, bodyPartName});
         }

         if (targetedBodyPart != RagdollBodyPart.BODYPART_COUNT.ordinal()) {
            this.processTargetedHit(weapon, wielder, target, RagdollBodyPart.values()[targetedBodyPart], shotDirection);
            return targetedBodyPart;
         }

         hitReaction = this.resolveHitReaction(wielder, target, shotDirection);
      }

      this.applyBlood(weapon, target, hitReaction, shotDirection);
      if (target instanceof IsoZombie isoZombie && isoZombie.getEatBodyTarget() != null) {
         hitReaction = target.getVariableBoolean("onknees") ? HitReaction.ON_KNEES : HitReaction.EATING;
      }

      if (hitReaction == HitReaction.FLOOR && target.isCurrentState(ZombieGetUpState.instance()) && target.isFallOnFront()) {
         hitReaction = HitReaction.GETTING_UP_FRONT;
      }

      if (hitReaction != HitReaction.NONE && !target.isAnimalRunningToDeathPosition()) {
         target.setHitReaction(hitReaction.getValue());
      } else {
         if (target instanceof IsoZombie isoZombie) {
            isoZombie.setStaggerBack(true);
         }

         target.setHitReaction("");
      }

      RagdollBodyPart bodyPart = this.getBodyPart(hitReaction, shotDirection);
      this.createCombatData(weapon, wielder, target, bodyPart);
      return bodyPart.ordinal();
   }

   private RagdollBodyPart getBodyPart(HitReaction hitReaction, ShotDirection shotDirection) {
      switch (hitReaction) {
         case SHOT_HEAD_FWD:
         case SHOT_HEAD_FWD02:
         case SHOT_HEAD_BWD:
            return RagdollBodyPart.BODYPART_HEAD;
         case SHOT_CHEST:
         case SHOT_CHEST_L:
         case SHOT_CHEST_R:
         case SHOT_CHEST_STEP_L:
         case SHOT_CHEST_STEP_R:
            return RagdollBodyPart.BODYPART_SPINE;
         case SHOT_BELLY:
         case SHOT_BELLY_STEP:
         case SHOT_BELLY_STEP_BEHIND:
            return RagdollBodyPart.BODYPART_PELVIS;
         case SHOT_LEG_L:
         case SHOT_LEG_R:
            boolean lower = Rand.Next(2) == 0;
            if (shotDirection == ShotDirection.LEFT) {
               return lower ? RagdollBodyPart.BODYPART_LEFT_LOWER_LEG : RagdollBodyPart.BODYPART_LEFT_UPPER_LEG;
            }

            return lower ? RagdollBodyPart.BODYPART_RIGHT_LOWER_LEG : RagdollBodyPart.BODYPART_RIGHT_UPPER_LEG;
         case SHOT_SHOULDER_L:
         case SHOT_SHOULDER_STEP_L:
         case SHOT_SHOULDER_R:
         case SHOT_SHOULDER_STEP_R:
            boolean lowerArm = Rand.Next(2) == 0;
            if (shotDirection == ShotDirection.LEFT) {
               return lowerArm ? RagdollBodyPart.BODYPART_LEFT_LOWER_ARM : RagdollBodyPart.BODYPART_LEFT_UPPER_ARM;
            }

            return lowerArm ? RagdollBodyPart.BODYPART_RIGHT_LOWER_ARM : RagdollBodyPart.BODYPART_RIGHT_UPPER_ARM;
         default:
            return RagdollBodyPart.BODYPART_SPINE;
      }
   }

   private ShotDirection calculateShotDirection(IsoGameCharacter wielder, IsoGameCharacter target) {
      Vector2 playerFwd = wielder.getForwardDirection();
      Vector2 zombieFwd = target.getForwardDirection();
      double crossProduct = playerFwd.x * zombieFwd.y - playerFwd.y * zombieFwd.x;
      double crossProductSign = crossProduct >= 0.0 ? 1.0 : -1.0;
      double dotProduct = playerFwd.x * zombieFwd.x + playerFwd.y * zombieFwd.y;
      double angleBetween = Math.acos(dotProduct) * crossProductSign;
      if (angleBetween < 0.0) {
         angleBetween += Math.PI * 2;
      }

      double deg = Math.toDegrees(angleBetween);
      if (deg < 45.0) {
         int rng = Rand.Next(9);
         if (rng > 6) {
            return ShotDirection.LEFT;
         } else {
            return rng > 4 ? ShotDirection.RIGHT : ShotDirection.SOUTH;
         }
      } else {
         if (deg < 90.0) {
            return Rand.Next(4) == 0 ? ShotDirection.SOUTH : ShotDirection.RIGHT;
         }

         if (deg < 135.0) {
            return ShotDirection.RIGHT;
         }

         if (deg < 180.0) {
            return Rand.Next(4) == 0 ? ShotDirection.NORTH : ShotDirection.RIGHT;
         }

         if (deg < 225.0) {
            int rng = Rand.Next(9);
            if (rng > 6) {
               return ShotDirection.LEFT;
            } else {
               return rng > 4 ? ShotDirection.RIGHT : ShotDirection.NORTH;
            }
         } else if (deg < 270.0) {
            return Rand.Next(4) == 0 ? ShotDirection.NORTH : ShotDirection.LEFT;
         } else if (deg < 315.0) {
            return ShotDirection.LEFT;
         } else {
            return Rand.Next(4) == 0 ? ShotDirection.SOUTH : ShotDirection.LEFT;
         }
      }
   }

   private void applyBlood(HandWeapon weapon, IsoGameCharacter target, HitReaction hitReaction, ShotDirection shotDirection) {
      if (!GameClient.client || !(target instanceof IsoPlayer)) {
         boolean criticalHit = target.isCriticalHit();
         switch (hitReaction) {
            case SHOT_HEAD_FWD:
            case SHOT_HEAD_FWD02:
            case SHOT_HEAD_BWD:
               target.addBlood(BloodBodyPartType.Head, false, true, true);
               break;
            case SHOT_CHEST:
            case SHOT_CHEST_L:
            case SHOT_CHEST_R:
            case SHOT_CHEST_STEP_L:
            case SHOT_CHEST_STEP_R:
               target.addBlood(BloodBodyPartType.Torso_Upper, !criticalHit, criticalHit, true);
               break;
            case SHOT_BELLY:
            case SHOT_BELLY_STEP:
               target.addBlood(BloodBodyPartType.Torso_Lower, !criticalHit, criticalHit, true);
               break;
            case SHOT_BELLY_STEP_BEHIND:
            default:
               if (weapon.isOfWeaponCategory(WeaponCategory.BLUNT)) {
                  target.addBlood(
                     BloodBodyPartType.FromIndex(Rand.Next(BloodBodyPartType.UpperArm_L.index(), BloodBodyPartType.Groin.index())), false, false, true
                  );
               } else if (!weapon.isOfWeaponCategory(WeaponCategory.UNARMED)) {
                  target.addBlood(
                     BloodBodyPartType.FromIndex(Rand.Next(BloodBodyPartType.UpperArm_L.index(), BloodBodyPartType.Groin.index())), false, true, true
                  );
               }
               break;
            case SHOT_LEG_L:
            case SHOT_LEG_R:
               boolean lowerx = Rand.Next(2) == 0;
               if (shotDirection == ShotDirection.LEFT) {
                  target.addBlood(lowerx ? BloodBodyPartType.LowerLeg_L : BloodBodyPartType.UpperLeg_L, !criticalHit, criticalHit, true);
               } else {
                  target.addBlood(lowerx ? BloodBodyPartType.LowerLeg_R : BloodBodyPartType.UpperLeg_R, !criticalHit, criticalHit, true);
               }
               break;
            case SHOT_SHOULDER_L:
            case SHOT_SHOULDER_STEP_L:
            case SHOT_SHOULDER_R:
            case SHOT_SHOULDER_STEP_R:
               boolean lower = Rand.Next(2) == 0;
               if (shotDirection == ShotDirection.LEFT) {
                  target.addBlood(lower ? BloodBodyPartType.ForeArm_L : BloodBodyPartType.UpperArm_L, !criticalHit, criticalHit, true);
               } else {
                  target.addBlood(lower ? BloodBodyPartType.ForeArm_R : BloodBodyPartType.UpperArm_R, !criticalHit, criticalHit, true);
               }
         }
      }
   }

   public void highlightTarget(IsoGameCharacter isoGameCharacter, Color color, float alpha) {
      if (DebugOptions.instance.physicsRenderHighlightBallisticsTargets.getValue()) {
         isoGameCharacter.setOutlineHighlight(true);
         isoGameCharacter.setOutlineHighlightCol(color.r, color.g, color.b, alpha);
      }
   }

   private void highlightTargets(IsoPlayer isoPlayer) {
      boolean isRanged = false;
      if (isoPlayer.getPrimaryHandItem() instanceof HandWeapon handWeapon) {
         isRanged = handWeapon.isRanged();
      }

      if (DebugOptions.instance.character.debug.render.meleeOutline.getValue() && !isRanged) {
         this.highlightMeleeTargets(isoPlayer, Color.cyan.r, Color.cyan.g, Color.cyan.b, 0.65F);
      }

      if (!isRanged) {
         if (Core.getInstance().getOptionMeleeOutline()) {
            if (isoPlayer.isLocalPlayer() && !isoPlayer.isNpc() && isoPlayer.isAiming()) {
               ColorInfo badColor = Core.getInstance().getBadHighlitedColor();
               this.highlightMeleeTargets(isoPlayer, badColor.r, badColor.g, badColor.b, 1.0F);
            }
         }
      }
   }

   private void highlightMeleeTargets(IsoPlayer isoPlayer, float r, float g, float b, float a) {
      this.calculateAttackVars(isoPlayer);
      this.calculateHitInfoList(isoPlayer);
      PZArrayList<HitInfo> hitInfoList = isoPlayer.getHitInfoList();

      for (int i = 0; i < hitInfoList.size(); i++) {
         HitInfo hitInfo = (HitInfo)hitInfoList.get(i);
         if (hitInfo.getObject() instanceof IsoGameCharacter isoGameCharacter) {
            isoGameCharacter.setOutlineHighlight(isoPlayer.getIndex(), true);
            isoGameCharacter.setOutlineHighlightCol(r, g, b, a);
         }
      }
   }

   public void pressedAttack(IsoPlayer isoPlayer) {
      boolean isRemotePlayer = GameClient.client && !isoPlayer.isLocalPlayer();
      boolean bWasSprinting = isoPlayer.isSprinting();
      isoPlayer.setSprinting(false);
      isoPlayer.setForceSprint(false);
      if (!isoPlayer.isAttackStarted() && !isoPlayer.isCurrentState(PlayerHitReactionState.instance())) {
         if (!isoPlayer.isCurrentState(FishingState.instance())) {
            if (!GameClient.client
               || !isoPlayer.isCurrentState(PlayerHitReactionPVPState.instance())
               || ServerOptions.instance.pvpMeleeWhileHitReaction.getValue()) {
               if (!isoPlayer.canPerformHandToHandCombat()) {
                  isoPlayer.clearHandToHandAttack();
               } else if (isoPlayer.isDoShove() || isoPlayer.isWeaponReady()) {
                  if (!isoPlayer.isAttackStarted()) {
                     isoPlayer.setVariable("StartedAttackWhileSprinting", bWasSprinting);
                  }

                  isoPlayer.setInitiateAttack(true);
                  isoPlayer.setAttackStarted(true);
                  if (!isRemotePlayer) {
                     isoPlayer.setCriticalHit(false);
                  }

                  isoPlayer.setAttackFromBehind(false);
                  WeaponType weaponType = isoPlayer.isDoShove() ? WeaponType.UNARMED : WeaponType.getWeaponType(isoPlayer);
                  if (!GameClient.client || isoPlayer.isLocalPlayer()) {
                     isoPlayer.setAttackType((AttackType)weaponType.getPossibleAttack().getRandom());
                  }

                  if (!GameClient.client || isoPlayer.isLocalPlayer()) {
                     isoPlayer.setCombatSpeed(isoPlayer.calculateCombatSpeed());
                  }

                  this.calculateAttackVars(isoPlayer);
                  String animVariableWeapon = isoPlayer.getVariableString("Weapon");
                  if (animVariableWeapon != null && animVariableWeapon.equals("throwing") && !isoPlayer.getAttackVars().doShove) {
                     isoPlayer.setAttackAnimThrowTimer(2000L);
                     isoPlayer.setIsAiming(true);
                  }

                  if (isRemotePlayer) {
                     isoPlayer.getAttackVars().doShove = isoPlayer.isDoShove();
                     isoPlayer.getAttackVars().aimAtFloor = isoPlayer.isAimAtFloor();
                     isoPlayer.getAttackVars().doGrapple = isoPlayer.isDoGrapple();
                  }

                  if (isoPlayer.getAttackVars().doShove && !isoPlayer.isAuthorizedHandToHand()) {
                     isoPlayer.setDoShove(false);
                     isoPlayer.setInitiateAttack(false);
                     isoPlayer.setAttackStarted(false);
                     isoPlayer.setAttackType(AttackType.NONE);
                  } else if (isoPlayer.getAttackVars().doGrapple && !isoPlayer.isAuthorizedHandToHand()) {
                     isoPlayer.setDoGrapple(false);
                     isoPlayer.setInitiateAttack(false);
                     isoPlayer.setAttackStarted(false);
                     isoPlayer.setAttackType(AttackType.NONE);
                  } else {
                     HandWeapon handWeapon = isoPlayer.getAttackVars().getWeapon(isoPlayer);
                     isoPlayer.setUseHandWeapon(handWeapon);
                     isoPlayer.setAimAtFloor(isoPlayer.getAttackVars().aimAtFloor);
                     isoPlayer.setDoShove(isoPlayer.getAttackVars().doShove);
                     isoPlayer.setDoGrapple(isoPlayer.getAttackVars().doGrapple);
                     isoPlayer.targetOnGround = (IsoGameCharacter)isoPlayer.getAttackVars().targetOnGround.getObject();
                     if (handWeapon != null && weaponType.isRanged()) {
                        isoPlayer.setRecoilDelay(handWeapon.getRecoilDelay(isoPlayer));
                        if (isoPlayer.isLocalPlayer() && handWeapon.getCurrentAmmoCount() == 0) {
                           INetworkPacket.send(PacketType.PlayerEmptyShot, new Object[]{isoPlayer});
                        }
                     }

                     int variation = Rand.Next(0, 3);
                     if (variation == 0) {
                        isoPlayer.setAttackVariationX(Rand.Next(-1.0F, -0.5F));
                     }

                     if (variation == 1) {
                        isoPlayer.setAttackVariationX(0.0F);
                     }

                     if (variation == 2) {
                        isoPlayer.setAttackVariationX(Rand.Next(0.5F, 1.0F));
                     }

                     isoPlayer.setAttackVariationY(0.0F);
                     this.calculateHitInfoList(isoPlayer);
                     IsoGameCharacter closestHitTarget = null;
                     if (!isoPlayer.getHitInfoList().isEmpty()) {
                        closestHitTarget = (IsoGameCharacter)Type.tryCastTo(((HitInfo)isoPlayer.getHitInfoList().get(0)).getObject(), IsoGameCharacter.class);
                     }

                     if (closestHitTarget == null) {
                        if (isoPlayer.isAiming() && !isoPlayer.isMeleePressed() && handWeapon != isoPlayer.bareHands) {
                           isoPlayer.setDoShove(false);
                        }

                        if (isoPlayer.isAiming() && !isoPlayer.isGrapplePressed() && handWeapon != isoPlayer.bareHands) {
                           isoPlayer.setDoGrapple(false);
                        }

                        isoPlayer.setLastAttackWasHandToHand(isoPlayer.isDoHandToHandAttack());
                        if (weaponType.isCanMiss() && !isoPlayer.isAimAtFloor() && (!GameClient.client || isoPlayer.isLocalPlayer())) {
                           isoPlayer.setAttackType(AttackType.MISS);
                        }
                     } else {
                        if (weaponType == WeaponType.SPEAR && isoPlayer.isTransparentWallTo(closestHitTarget)) {
                           isoPlayer.setAttackType(AttackType.SPEAR_STAB);
                        }

                        if (!GameClient.client || isoPlayer.isLocalPlayer()) {
                           isoPlayer.setAttackFromBehind(isoPlayer.isBehind(closestHitTarget));
                        }

                        float closestDist = IsoUtils.DistanceTo(closestHitTarget.getX(), closestHitTarget.getY(), isoPlayer.getX(), isoPlayer.getY());
                        isoPlayer.setVariable("TargetDist", closestDist);
                        float criticalHitChance = isoPlayer.calculateCritChance(closestHitTarget);
                        IsoGameCharacter closestToTarget = isoPlayer.getClosestTo(closestHitTarget);
                        if (!isoPlayer.getAttackVars().aimAtFloor
                           && closestDist > 1.25F
                           && weaponType == WeaponType.SPEAR
                           && (
                              closestToTarget == null
                                 || IsoUtils.DistanceTo(closestHitTarget.getX(), closestHitTarget.getY(), closestToTarget.getX(), closestToTarget.getY())
                                    > 1.7F
                           )) {
                           if (!GameClient.client || isoPlayer.isLocalPlayer()) {
                              isoPlayer.setAttackType(AttackType.OVERHEAD);
                           }

                           if (isoPlayer.getPrimaryHandItem() == null || isoPlayer.getPrimaryHandItem().hasTag(ItemTag.FAKE_SPEAR)) {
                              criticalHitChance += 30.0F;
                           }
                        }

                        if (isoPlayer.isLocalPlayer() && !closestHitTarget.isOnFloor()) {
                           closestHitTarget.setHitFromBehind(isoPlayer.isAttackFromBehind());
                        }

                        if (isoPlayer.isAttackFromBehind()) {
                           if (!(
                              closestHitTarget instanceof IsoZombie isoZombie
                                 && isoZombie.target == null
                                 && (isoPlayer.getPrimaryHandItem() == null || isoPlayer.getPrimaryHandItem().hasTag(ItemTag.FAKE_SPEAR))
                           )) {
                              criticalHitChance += this.combatConfig.get(CombatConfigKey.ADDITIONAL_CRITICAL_HIT_CHANCE_DEFAULT);
                           } else {
                              criticalHitChance += this.combatConfig.get(CombatConfigKey.ADDITIONAL_CRITICAL_HIT_CHANCE_FROM_BEHIND);
                           }
                        }

                        if (closestHitTarget instanceof IsoPlayer && weaponType.isRanged() && !isoPlayer.isDoHandToHandAttack()) {
                           criticalHitChance = isoPlayer.getAttackVars().getWeapon(isoPlayer).getStopPower()
                              * (1.0F + isoPlayer.getPerkLevel(Perks.Aiming) / 15.0F);
                        }

                        if (isoPlayer.getPrimaryHandItem() != null && isoPlayer.getPrimaryHandItem().hasTag(ItemTag.NO_CRITICALS)) {
                           criticalHitChance = 0.0F;
                        }

                        if (!GameClient.client || isoPlayer.isLocalPlayer()) {
                           isoPlayer.setCriticalHit(Rand.Next(100) < criticalHitChance);
                           if (isoPlayer.isAttackFromBehind()
                              && isoPlayer.getAttackVars().closeKill
                              && closestHitTarget instanceof IsoZombie isoZombie
                              && isoZombie.target == null
                              && isoPlayer.getPrimaryHandItem() != null
                              && !isoPlayer.getPrimaryHandItem().hasTag(ItemTag.FAKE_SPEAR)) {
                              isoPlayer.setCriticalHit(true);
                           }

                           if (isoPlayer.isCriticalHit() && !isoPlayer.getAttackVars().closeKill && !isoPlayer.isDoShove() && weaponType == WeaponType.KNIFE) {
                              isoPlayer.setCriticalHit(false);
                           }

                           if (isoPlayer.getStats().numChasingZombies > 1
                              && isoPlayer.getAttackVars().closeKill
                              && !isoPlayer.isDoShove()
                              && weaponType == WeaponType.KNIFE) {
                              isoPlayer.setCriticalHit(false);
                           }
                        }

                        if (isoPlayer.getPrimaryHandItem() != null && isoPlayer.getPrimaryHandItem().hasTag(ItemTag.NO_CRITICALS)) {
                           isoPlayer.setCriticalHit(false);
                        }

                        if (isoPlayer.isCriticalHit()) {
                           isoPlayer.setCombatSpeed(isoPlayer.getCombatSpeed() * 1.1F);
                        }

                        if (Core.debug) {
                           DebugType.Combat
                              .debugln(
                                 "Attacked zombie: dist: "
                                    + closestDist
                                    + ", chance: ("
                                    + ((HitInfo)isoPlayer.getHitInfoList().get(0)).chance
                                    + "), crit: "
                                    + isoPlayer.isCriticalHit()
                                    + " ("
                                    + criticalHitChance
                                    + "%) from behind: "
                                    + isoPlayer.isAttackFromBehind()
                              );
                        }

                        isoPlayer.setLastAttackWasHandToHand(isoPlayer.isDoHandToHandAttack());
                     }
                  }
               }
            }
         }
      }
   }

   private void processTargetedHit(
      HandWeapon handWeapon, IsoGameCharacter wielder, IsoGameCharacter target, RagdollBodyPart targetedBodyPart, ShotDirection shotDirection
   ) {
      int randomHitReaction = Rand.Next(2);

      HitReaction hitReaction = switch (targetedBodyPart) {
         case BODYPART_PELVIS -> {
            if (shotDirection == ShotDirection.NORTH) {
               if (target.isHitFromBehind()) {
                  yield HitReaction.SHOT_BELLY_STEP_BEHIND;
               } else if (randomHitReaction == 0) {
                  yield HitReaction.SHOT_BELLY;
               }
            }
            yield HitReaction.SHOT_BELLY_STEP;
         }
         case BODYPART_SPINE -> {
            if (shotDirection == ShotDirection.LEFT) {
               yield randomHitReaction == 0 ? HitReaction.SHOT_CHEST_L : HitReaction.SHOT_CHEST_STEP_L;
            } else if (shotDirection == ShotDirection.RIGHT) {
               yield randomHitReaction == 0 ? HitReaction.SHOT_CHEST_R : HitReaction.SHOT_CHEST_STEP_R;
            }
            yield HitReaction.SHOT_CHEST;
         }
         case BODYPART_HEAD -> {
            if ((shotDirection == ShotDirection.LEFT || shotDirection == ShotDirection.RIGHT) && Rand.Next(4) == 0) {
               yield HitReaction.SHOT_HEAD_BWD;
            }
            yield randomHitReaction == 0 ? HitReaction.SHOT_HEAD_FWD : HitReaction.SHOT_HEAD_FWD02;
         }
         case BODYPART_LEFT_UPPER_LEG, BODYPART_LEFT_LOWER_LEG -> HitReaction.SHOT_LEG_L;
         case BODYPART_RIGHT_UPPER_LEG, BODYPART_RIGHT_LOWER_LEG -> HitReaction.SHOT_LEG_R;
         case BODYPART_LEFT_UPPER_ARM, BODYPART_LEFT_LOWER_ARM -> target.isHitFromBehind() ? HitReaction.SHOT_SHOULDER_STEP_L : HitReaction.SHOT_SHOULDER_L;
         case BODYPART_RIGHT_UPPER_ARM, BODYPART_RIGHT_LOWER_ARM -> target.isHitFromBehind() ? HitReaction.SHOT_SHOULDER_STEP_R : HitReaction.SHOT_SHOULDER_R;
         default -> throw new IllegalStateException("Unexpected value: " + targetedBodyPart);
      };
      this.applyBlood(handWeapon, target, hitReaction, shotDirection);
      if (target instanceof IsoZombie isoZombie && isoZombie.getEatBodyTarget() != null) {
         hitReaction = target.getVariableBoolean("onknees") ? HitReaction.ON_KNEES : HitReaction.EATING;
      }

      target.setHitReaction(hitReaction.getValue());
      this.createCombatData(handWeapon, wielder, target, targetedBodyPart);
      DebugType.Combat.debugln("hitReaction = %s", new Object[]{hitReaction});
   }

   private void createCombatData(HandWeapon weapon, IsoGameCharacter attacker, IsoGameCharacter target, RagdollBodyPart targetedBodyPart) {
      if (target.usePhysicHitReaction() && target.canRagdoll()) {
         BallisticsTarget ballisticsTarget = target.getBallisticsTarget();
         if (ballisticsTarget != null) {
            CombatDamageData combatDamageData = ballisticsTarget.getCombatDamageData();
            combatDamageData.event = target.getHitReaction();
            combatDamageData.target = target;
            combatDamageData.attacker = attacker;
            combatDamageData.handWeapon = weapon;
            combatDamageData.isoTrap = null;
            combatDamageData.bodyPart = targetedBodyPart;
            ballisticsTarget.setCombatDamageDataProcessed(false);
         }
      }
   }

   private void createCombatData(IsoTrap isoTrap, IsoGameCharacter target, RagdollBodyPart targetedBodyPart) {
      if (target.usePhysicHitReaction() && target.canRagdoll()) {
         BallisticsTarget ballisticsTarget = target.getBallisticsTarget();
         if (ballisticsTarget != null) {
            CombatDamageData combatDamageData = ballisticsTarget.getCombatDamageData();
            combatDamageData.event = target.getHitReaction();
            combatDamageData.target = target;
            combatDamageData.attacker = isoTrap.getAttacker();
            combatDamageData.handWeapon = isoTrap.getHandWeapon();
            combatDamageData.isoTrap = isoTrap;
            combatDamageData.bodyPart = targetedBodyPart;
            ballisticsTarget.setCombatDamageDataProcessed(false);
         }
      }
   }

   public void update(boolean doUpdate) {
      if (IsoPlayer.players[0] != null) {
         if (IsoPlayer.players[0].isWeaponReady()) {
            this.updateReticle(IsoPlayer.players[0]);
            this.highlightTargets(IsoPlayer.players[0]);
            this.debugUpdate();
         }
      }
   }

   private void debugUpdate() {
      if (DebugOptions.instance.thumpableResetCurrentCellWindows.getValue()) {
         IsoWindow.resetCurrentCellWindows();
         DebugOptions.instance.thumpableResetCurrentCellWindows.setValue(false);
      } else if (DebugOptions.instance.thumpableBarricadeCurrentCellWindowsFullPlanks.getValue()) {
         IsoBarricade.barricadeCurrentCellWithPlanks(4);
         DebugOptions.instance.thumpableBarricadeCurrentCellWindowsFullPlanks.setValue(false);
      } else if (DebugOptions.instance.thumpableBarricadeCurrentCellWindowsHalfPlanks.getValue()) {
         IsoBarricade.barricadeCurrentCellWithPlanks(2);
         DebugOptions.instance.thumpableBarricadeCurrentCellWindowsHalfPlanks.setValue(false);
      } else if (DebugOptions.instance.thumpableRemoveBarricadeCurrentCellWindows.getValue()) {
         IsoBarricade.barricadeCurrentCellWithPlanks(0);
         DebugOptions.instance.thumpableRemoveBarricadeCurrentCellWindows.setValue(false);
      } else if (DebugOptions.instance.thumpableBarricadeCurrentCellWindowsFullMetalBars.getValue()) {
         IsoBarricade.barricadeCurrentCellWithMetalBars();
         DebugOptions.instance.thumpableBarricadeCurrentCellWindowsFullMetalBars.setValue(false);
      } else if (DebugOptions.instance.thumpableBarricadeCurrentCellWindowsMetalPlate.getValue()) {
         IsoBarricade.barricadeCurrentCellWithMetalPlate();
         DebugOptions.instance.thumpableBarricadeCurrentCellWindowsMetalPlate.setValue(false);
      }
   }

   public void postUpdate(boolean doUpdate) {
   }

   public void updateReticle(IsoPlayer isoPlayer) {
      IsoReticle isoReticle = IsoReticle.getInstance(isoPlayer.getIndex());
      if (isoReticle != null && isoReticle.getTargetReticleMode() == IsoReticleMode.RETICLE) {
         if (isoPlayer.isLocalPlayer() && !isoPlayer.isNpc()) {
            if (isoPlayer.isAiming()) {
               HandWeapon weapon = (HandWeapon)Type.tryCastTo(isoPlayer.getPrimaryHandItem(), HandWeapon.class);
               if (weapon == null || weapon.getSwingAnim() == null || weapon.getCondition() <= 0) {
                  weapon = isoPlayer.bareHands;
               }

               if (weapon.isRanged()) {
                  boolean bDoShove1 = isoPlayer.isDoShove();
                  boolean bDoGrapple1 = isoPlayer.isDoGrapple();
                  HandWeapon weapon1 = isoPlayer.getUseHandWeapon();
                  isoPlayer.setDoShove(false);
                  isoPlayer.setDoGrapple(false);
                  isoPlayer.setUseHandWeapon(weapon);
                  this.calculateAttackVars(isoPlayer);
                  this.calculateHitInfoList(isoPlayer);
                  if (Core.debug) {
                     this.updateTargetHitInfoPanel(isoPlayer);
                  }

                  ColorInfo tempColorInfo = IsoGameCharacter.getInf();
                  CombatManager.HitChanceData hitChanceData = this.calculateHitChanceData(isoPlayer, weapon, null);
                  isoReticle.setChance((int)hitChanceData.hitChance);
                  isoReticle.setAimPenalty((int)hitChanceData.aimPenalty);
                  isoReticle.hasTarget(isoPlayer.getAimingMode().hasTarget);
                  isoReticle.setReticleColor(Core.getInstance().getNoTargetColor());
                  float closest = Float.MAX_VALUE;

                  for (int i = 0; i < isoPlayer.getHitInfoList().size(); i++) {
                     HitInfo hitInfo = (HitInfo)isoPlayer.getHitInfoList().get(i);
                     IsoMovingObject object = hitInfo.getObject();
                     if (hitInfo.distSq < closest) {
                        if (object instanceof IsoZombie || object instanceof IsoPlayer) {
                           float delta = hitInfo.chance < 70.0F ? hitInfo.chance / 140.0F : (hitInfo.chance - 70.0F) / 30.0F * 0.5F + 0.5F;
                           Core.getInstance().getBadHighlitedColor().interp(Core.getInstance().getGoodHighlitedColor(), delta, tempColorInfo);
                           closest = hitInfo.distSq;
                           isoReticle.setChance(hitInfo.chance);
                           isoReticle.setReticleColor(Core.getInstance().getTargetColor());
                        }

                        if (hitInfo.window.getObject() != null) {
                           hitInfo.window.getObject().setHighlightColor(0.8F, 0.1F, 0.1F, 0.5F);
                           hitInfo.window.getObject().setHighlighted(true);
                        }
                     }
                  }

                  isoReticle.setAimColor(tempColorInfo);
                  isoPlayer.setDoShove(bDoShove1);
                  isoPlayer.setDoGrapple(bDoGrapple1);
                  isoPlayer.setUseHandWeapon(weapon1);
               }
            }
         }
      }
   }

   private void updateTargetHitInfoPanel(IsoGameCharacter isoGameCharacter) {
      for (BaseDebugWindow baseDebugWindow : DebugContext.instance.getWindows()) {
         if (baseDebugWindow instanceof TargetHitInfoPanel targetHitInfoPanel) {
            targetHitInfoPanel.setIsoGameCharacter(isoGameCharacter);
            targetHitInfoPanel.hitInfoList.clear();
            PZArrayUtil.addAll(isoGameCharacter.getHitInfoList(), targetHitInfoPanel.hitInfoList);
            break;
         }
      }
   }

   private void addTracerEffect(IsoGameCharacter isoGameCharacter, float range) {
      IsoBulletTracerEffects.getInstance().addEffect(isoGameCharacter, range);
      if (GameClient.client) {
         TracerInfo tracer = new TracerInfo();
         tracer.set(range);
         this.tracers.add(tracer);
      }
   }

   private void addTracerEffect(IsoGameCharacter isoGameCharacter, float range, float endX, float endY, float endZ, IsoGridSquare square) {
      IsoBulletTracerEffects.getInstance().addEffect(isoGameCharacter, range, endX, endY, endZ, square);
      if (GameClient.client) {
         TracerInfo tracer = new TracerInfo();
         tracer.set(range, endX, endY, endZ, square);
         this.tracers.add(tracer);
      }
   }

   private void fireWeapon(HandWeapon handWeapon, IsoGameCharacter isoGameCharacter) {
      if (handWeapon != null) {
         float range = handWeapon.getMaxRange(isoGameCharacter);
         BallisticsController ballisticsController = isoGameCharacter.getBallisticsController();
         float muzzleOffsetDistance = ballisticsController.calculateMuzzlePosition(ballisticsStartPosition, ballisticsDirectionVector);
         ballisticsSpreadAngleScale = 1.0F / (1.0F + muzzleOffsetDistance);
         ballisticsDirectionVector.normalize();
         float firingVectorX = ballisticsStartPosition.x + ballisticsDirectionVector.x * range;
         float firingVectorY = ballisticsStartPosition.y + ballisticsDirectionVector.y * range;
         float firingVectorZ = ballisticsStartPosition.z + ballisticsDirectionVector.z * range;
         boolean checkHitVehicle = this.isVehicleHit(isoGameCharacter.getHitInfoList());
         if (handWeapon.isRangeFalloff()) {
            if (!ballisticsController.hasSpreadData()) {
               ballisticsController.setRange(range);
               ballisticsController.update();
               int projectileCount = handWeapon.getProjectileCount();
               float projectileSpread = handWeapon.getProjectileSpread() * ballisticsSpreadAngleScale;
               float projectileWeightCenter = handWeapon.getProjectileWeightCenter();
               ballisticsController.getSpreadData(range, projectileSpread, projectileWeightCenter, projectileCount);
            }

            int numberOfSpreadData = ballisticsController.getNumberOfSpreadData();
            float[] ballisticsSpreadData = ballisticsController.getBallisticsSpreadData();
            int arrayIndex = 0;
            Vector3f start = BaseVehicle.allocVector3f();
            Vector3f end = BaseVehicle.allocVector3f();
            Vector3f intersect = BaseVehicle.allocVector3f();

            for (int i = 0; i < numberOfSpreadData; i++) {
               float id = ballisticsSpreadData[arrayIndex++];
               float x = ballisticsSpreadData[arrayIndex++];
               float z = ballisticsSpreadData[arrayIndex++] / 2.44949F;
               float y = ballisticsSpreadData[arrayIndex++];
               start.set(ballisticsStartPosition.x, ballisticsStartPosition.y, ballisticsStartPosition.z);
               end.set(x, y, z);
               if (id == 0.0F) {
                  if (checkHitVehicle) {
                     Vector3f intersectPoint = this.checkHitVehicle(isoGameCharacter.getHitInfoList(), start, end, intersect);
                     if (intersectPoint != null) {
                        Vector3 closest = PZMath.closestVector3(
                           start.x(), start.y(), start.z(), end.x(), end.y(), end.z(), intersectPoint.x(), intersectPoint.y(), end.z()
                        );
                        this.addTracerEffect(isoGameCharacter, range, closest.x, closest.y, closest.z, null);
                        continue;
                     }
                  }

                  IsoGridSquareCollisionData isoGridSquareCollisionData = LosUtil.getFirstBlockingIsoGridSquare(
                     isoGameCharacter.getCell(),
                     PZMath.fastfloor(ballisticsStartPosition.x),
                     PZMath.fastfloor(ballisticsStartPosition.y),
                     PZMath.fastfloor(ballisticsStartPosition.z),
                     PZMath.fastfloor(x),
                     PZMath.fastfloor(y),
                     PZMath.fastfloor(z),
                     false
                  );
                  if (isoGridSquareCollisionData.testResults != TestResults.Clear
                     && isoGridSquareCollisionData.testResults != TestResults.ClearThroughClosedDoor) {
                     Vector3 closest = PZMath.closestVector3(
                        ballisticsStartPosition.x,
                        ballisticsStartPosition.y,
                        ballisticsStartPosition.z,
                        x,
                        y,
                        z,
                        isoGridSquareCollisionData.hitPosition.x,
                        isoGridSquareCollisionData.hitPosition.y,
                        z
                     );
                     this.addTracerEffect(isoGameCharacter, range, closest.x, closest.y, closest.z, isoGridSquareCollisionData.isoGridSquare);
                  } else {
                     this.addTracerEffect(isoGameCharacter, range, x, y, z, null);
                  }
               } else {
                  this.addTracerEffect(isoGameCharacter, range, x, y, z, null);
               }
            }

            BaseVehicle.releaseVector3f(start);
            BaseVehicle.releaseVector3f(end);
            BaseVehicle.releaseVector3f(intersect);
         } else {
            PZArrayList<HitInfo> hitInfoList = isoGameCharacter.getHitInfoList();
            if (hitInfoList.isEmpty()) {
               this.missedShot(isoGameCharacter, range, firingVectorX, firingVectorY, firingVectorZ);
            } else {
               assert hitInfoList.size() == 1;
               HitInfo hitInfo = (HitInfo)hitInfoList.get(0);
               if (checkHitVehicle) {
                  Vector3f start = BaseVehicle.allocVector3f();
                  Vector3f end = BaseVehicle.allocVector3f();
                  start.set(ballisticsStartPosition.x, ballisticsStartPosition.y, ballisticsStartPosition.z);
                  end.set(hitInfo.x, hitInfo.y, hitInfo.z);
                  Vector3f intersect = BaseVehicle.allocVector3f();
                  boolean bIntersect = this.checkHitVehicle(isoGameCharacter.getHitInfoList(), start, end, intersect) != null;
                  BaseVehicle.releaseVector3f(start);
                  BaseVehicle.releaseVector3f(end);
                  BaseVehicle.releaseVector3f(intersect);
                  if (!bIntersect) {
                     this.missedShot(isoGameCharacter, range, firingVectorX, firingVectorY, firingVectorZ);
                     return;
                  }
               }

               this.addTracerEffect(isoGameCharacter, range, hitInfo.x, hitInfo.y, hitInfo.z, null);
            }
         }
      }
   }

   private void missedShot(IsoGameCharacter isoGameCharacter, float range, float firingVectorX, float firingVectorY, float firingVectorZ) {
      IsoGridSquareCollisionData isoGridSquareCollisionData = LosUtil.getFirstBlockingIsoGridSquare(
         isoGameCharacter.getCell(),
         PZMath.fastfloor(ballisticsStartPosition.x),
         PZMath.fastfloor(ballisticsStartPosition.y),
         PZMath.fastfloor(ballisticsStartPosition.z),
         PZMath.fastfloor(firingVectorX),
         PZMath.fastfloor(firingVectorY),
         PZMath.fastfloor(firingVectorZ),
         false
      );
      if (isoGridSquareCollisionData.testResults != TestResults.Clear && isoGridSquareCollisionData.testResults != TestResults.ClearThroughClosedDoor) {
         Vector3 closest = PZMath.closestVector3(
            ballisticsStartPosition.x,
            ballisticsStartPosition.y,
            ballisticsStartPosition.z,
            firingVectorX,
            firingVectorY,
            firingVectorZ,
            isoGridSquareCollisionData.hitPosition.x,
            isoGridSquareCollisionData.hitPosition.y,
            firingVectorZ
         );
         this.addTracerEffect(isoGameCharacter, range, closest.x, closest.y, closest.z, isoGridSquareCollisionData.isoGridSquare);
      } else {
         this.addTracerEffect(isoGameCharacter, range);
      }
   }

   private boolean isVehicleHit(PZArrayList<HitInfo> hitInfoArrayList) {
      for (int i = 0; i < hitInfoArrayList.size(); i++) {
         IsoMovingObject hitObject = ((HitInfo)hitInfoArrayList.get(i)).getObject();
         if (hitObject != null && hitObject instanceof BaseVehicle hitVehicle) {
            return true;
         }
      }

      return false;
   }

   private boolean checkHitVehicle(IsoGameCharacter isoGameCharacter, BaseVehicle baseVehicle) {
      int id = baseVehicle.getId();
      BallisticsController ballisticsController = isoGameCharacter.getBallisticsController();
      return ballisticsController.isTarget(id) || ballisticsController.isSpreadTarget(id);
   }

   private Vector3f checkHitVehicle(PZArrayList<HitInfo> hitInfoArrayList, Vector3f start, Vector3f end, Vector3f result) {
      Vector3f intersectPoint = null;

      for (int i = 0; i < hitInfoArrayList.size(); i++) {
         if (((HitInfo)hitInfoArrayList.get(i)).getObject() instanceof BaseVehicle hitVehicle) {
            intersectPoint = hitVehicle.getIntersectPoint(start, end, result);
            if (intersectPoint != null) {
               return intersectPoint;
            }
         }
      }

      return intersectPoint;
   }

   public boolean hitIsoGridSquare(IsoGridSquare isoGridSquare, Vector3f hitLocation, Effect effect) {
      return this.shootPlacedItems(isoGridSquare, new Vector3(hitLocation.x(), hitLocation.y(), hitLocation.z()), effect);
   }

   private boolean shootPlacedItems(IsoGridSquare isoGridSquare, Vector3 hitPosition, Effect effect) {
      float nearestDistance = Float.MAX_VALUE;
      IsoWorldInventoryObject nearestIsoWorldInventoryObject = null;
      IsoObject[] objects = (IsoObject[])isoGridSquare.getObjects().getElements();
      int nObjects = isoGridSquare.getObjects().size();

      for (int i = 0; i < nObjects; i++) {
         IsoObject obj = objects[i];
         IsoWorldInventoryObject worldObj = (IsoWorldInventoryObject)Type.tryCastTo(obj, IsoWorldInventoryObject.class);
         IsoLightSwitch isoLightSwitch = (IsoLightSwitch)Type.tryCastTo(obj, IsoLightSwitch.class);
         if (worldObj != null || isoLightSwitch != null) {
            if (isoLightSwitch != null && !isoLightSwitch.lightRoom && isoLightSwitch.hasLightBulb()) {
               StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Lights Shot", 1.0F);
               SoundManager.instance.playImpactSound(isoGridSquare, null, MaterialType.Glass_Solid);
               SoundManager.instance.PlayWorldSound("SmashWindow", isoGridSquare, 0.2F, 20.0F, 1.0F, true);
               isoLightSwitch.setBulbItemRaw(null);
               isoLightSwitch.syncCustomizedSettings(null);
            }

            if (worldObj != null && worldObj.item != null) {
               Durability durability = worldObj.item.getDurabilityComponent();
               if (durability != null) {
                  Vector3 objectPosition = new Vector3(worldObj.getWorldPosX(), worldObj.getWorldPosY(), worldObj.getWorldPosZ());
                  float distance = hitPosition.distanceTo(objectPosition);
                  if (distance < nearestDistance) {
                     nearestDistance = distance;
                     nearestIsoWorldInventoryObject = worldObj;
                  }
               }
            }
         }
      }

      if (nearestIsoWorldInventoryObject != null) {
         Durability durability = nearestIsoWorldInventoryObject.item.getDurabilityComponent();
         SoundManager.instance.playImpactSound(isoGridSquare, null, durability.getMaterial());
         if (GameClient.client) {
            sendShotPlacedItem(effect.getCharacter(), nearestIsoWorldInventoryObject, nearestDistance);
         } else {
            isoGridSquare.transmitRemoveItemFromSquare(nearestIsoWorldInventoryObject);
         }

         return true;
      } else {
         return false;
      }
   }

   private static void sendShotPlacedItem(IsoGameCharacter shooter, IsoWorldInventoryObject worldObject, float distance) {
      if (shooter != null && shooter.getAttackingWeapon() != null) {
         WeaponHit hit = new WeaponHit();
         hit.set(0.0F, distance, 0.0F, 0.0F, 0.0F, false, false, false, false);
         GameClient.sendPlayerHit(shooter, worldObject, shooter.getAttackingWeapon(), false, null, List.of(hit), false);
      }
   }

   private void setUsePhysicHitReaction(HandWeapon handWeapon, IsoGameCharacter isoGameCharacter) {
      if (!isoGameCharacter.canRagdoll()) {
         isoGameCharacter.setUsePhysicHitReaction(false);
      } else {
         RagdollSettingsManager ragdollSettingsManager = RagdollSettingsManager.getInstance();
         isoGameCharacter.setUsePhysicHitReaction(ragdollSettingsManager.usePhysicHitReaction(isoGameCharacter) && handWeapon.isRanged());
      }
   }

   public void processInstantExplosion(IsoGameCharacter target, IsoTrap isoTrap) {
      target.setUsePhysicHitReaction(!target.canRagdoll());
      RagdollSettingsManager ragdollSettingsManager = RagdollSettingsManager.getInstance();
      target.setUsePhysicHitReaction(ragdollSettingsManager.usePhysicHitReaction(target));
      target.setHitReaction(HitReaction.SHOT_CHEST.getValue());
      BallisticsTarget ballisticsTarget = target.ensureExistsBallisticsTarget(target);
      ballisticsTarget.add();
      this.createCombatData(isoTrap, target, RagdollBodyPart.getRandomPart());
      if (DebugOptions.instance.character.debug.render.explosionHitDirection.getValue()) {
         HandWeapon handWeapon = isoTrap.getHandWeapon();
         IsoGridSquare isoGridSquare = handWeapon.getAttackTargetSquare(null);
         LineDrawer.addAlphaDecayingLine(
            isoGridSquare.getX(), isoGridSquare.getY(), isoGridSquare.getZ(), target.getX(), target.getY(), target.getZ(), 0.0F, 1.0F, 0.5F, 1.0F
         );
         LineDrawer.addAlphaDecayingIsoCircle(isoGridSquare.getX(), isoGridSquare.getY(), isoGridSquare.getZ(), 1.0F, 16, 0.0F, 1.0F, 0.5F, 1.0F);
      }
   }

   public float applyGlobalDamageReductionMultipliers(HandWeapon handWeapon, float damage) {
      if (handWeapon.isMelee()) {
         damage *= this.combatConfig.get(CombatConfigKey.GLOBAL_MELEE_DAMAGE_REDUCTION_MULTIPLIER);
      }

      return damage;
   }

   public float applyWeaponLevelDamageModifier(IsoGameCharacter isoGameCharacter, float damage) {
      int weaponLevel = Math.max(isoGameCharacter.getWeaponLevel(), 0);
      weaponLevel = Math.min(weaponLevel, 10);
      return damage
         * (
            this.combatConfig.get(CombatConfigKey.BASE_WEAPON_DAMAGE_MULTIPLIER)
               + weaponLevel * this.combatConfig.get(CombatConfigKey.WEAPON_LEVEL_DAMAGE_MULTIPLIER_INCREMENT)
         );
   }

   public float applyPlayerReceivedDamageModifier(IsoGameCharacter isoGameCharacter, float damage) {
      return damage
         * (
            isoGameCharacter instanceof IsoPlayer
               ? this.combatConfig.get(CombatConfigKey.PLAYER_RECEIVED_DAMAGE_MULTIPLIER)
               : this.combatConfig.get(CombatConfigKey.NON_PLAYER_RECEIVED_DAMAGE_MULTIPLIER)
         );
   }

   public float applyOneHandedDamagePenalty(IsoGameCharacter isoGameCharacter, HandWeapon weapon, float damage) {
      boolean usingTwoHandedWeaponIncorrectly = weapon.isTwoHandWeapon() && !isoGameCharacter.isItemInBothHands(weapon);
      return damage * (usingTwoHandedWeaponIncorrectly ? this.combatConfig.get(CombatConfigKey.DAMAGE_PENALTY_ONE_HANDED_TWO_HANDED_WEAPON_MULTIPLIER) : 1.0F);
   }

   public void applyMeleeEnduranceLoss(IsoGameCharacter attacker, IsoGameCharacter target, HandWeapon handWeapon, float damage) {
      if (handWeapon.isMelee()) {
         if (handWeapon.isUseEndurance()) {
            boolean usingTwoHandedIncorrectly = handWeapon.isTwoHandWeapon()
               && (attacker.getPrimaryHandItem() != handWeapon || attacker.getSecondaryHandItem() != handWeapon);
            float twoHandedPenalty = usingTwoHandedIncorrectly
               ? handWeapon.getWeight()
                  / this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_TWO_HANDED_PENALTY_DIVISOR)
                  / this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_TWO_HANDED_PENALTY_SCALE)
               : 0.0F;
            float enduranceLoss = (
                  handWeapon.getWeight()
                        * this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_BASE_SCALE)
                        * handWeapon.getFatigueMod(attacker)
                        * attacker.getFatigueMod()
                        * handWeapon.getEnduranceMod()
                        * this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_WEIGHT_MODIFIER)
                     + twoHandedPenalty
               )
               * this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_FINAL_MULTIPLIER);
            if (attacker instanceof IsoPlayer isoPlayer && attacker.isAimAtFloor() && isoPlayer.isDoShove()) {
               enduranceLoss *= this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_FLOOR_SHOVE_MULTIPLIER);
            }

            float dmgEnduranceModifier;
            if (damage <= 0.0F) {
               dmgEnduranceModifier = 1.0F;
            } else if (target.isCloseKilled()) {
               dmgEnduranceModifier = this.combatConfig.get(CombatConfigKey.ENDURANCE_LOSS_CLOSE_KILL_MODIFIER);
            } else {
               float realDmgLeft = Math.min(damage, target.getHealth());
               dmgEnduranceModifier = Math.min(realDmgLeft / handWeapon.getMaxDamage(), 1.0F);
            }

            attacker.getStats().remove(CharacterStat.ENDURANCE, enduranceLoss * dmgEnduranceModifier);
         }
      }
   }

   private float calculateTotalDefense(int partHit, IsoGameCharacter isoGameCharacter, HandWeapon weapon) {
      float totalDefense = isoGameCharacter.getBodyPartClothingDefense(partHit, false, weapon.isRanged()) * 0.5F;
      totalDefense += isoGameCharacter.getBodyPartClothingDefense(partHit, true, weapon.isRanged());
      totalDefense *= (float)SandboxOptions.instance.lore.zombiesArmorFactor.getValue();
      int maxDefense = SandboxOptions.instance.lore.zombiesMaxDefense.getValue();
      if (maxDefense > 100) {
         maxDefense = 100;
      }

      if (totalDefense > maxDefense) {
         totalDefense = maxDefense;
      }

      return totalDefense;
   }

   private float applyMeleeHitLocationDamage(IsoGameCharacter isoGameCharacter, HandWeapon weapon, int partHit, int hitHead, boolean hitLegs, float damageSplit) {
      if (hitHead > 0) {
         if (!GameClient.client) {
            isoGameCharacter.addBlood(BloodBodyPartType.Head, true, true, true);
            isoGameCharacter.addBlood(BloodBodyPartType.Torso_Upper, true, false, false);
            isoGameCharacter.addBlood(BloodBodyPartType.UpperArm_L, true, false, false);
            isoGameCharacter.addBlood(BloodBodyPartType.UpperArm_R, true, false, false);
         }

         damageSplit *= this.combatConfig.get(CombatConfigKey.HEAD_HIT_DAMAGE_SPLIT_MODIFIER);
      }

      if (hitLegs) {
         damageSplit *= this.combatConfig.get(CombatConfigKey.LEG_HIT_DAMAGE_SPLIT_MODIFIER);
      }

      float calculatedDamage = this.calculateHitLocationDamage(isoGameCharacter, weapon, partHit, damageSplit);
      StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Melee Damage", calculatedDamage);
      return calculatedDamage;
   }

   private float applyRangeHitLocationDamage(IsoGameCharacter isoGameCharacter, HandWeapon weapon, int bodyPart, float damageSplit) {
      if (RagdollBodyPart.isHead(bodyPart)) {
         if (!GameClient.client) {
            isoGameCharacter.addBlood(BloodBodyPartType.Head, true, true, true);
            isoGameCharacter.addBlood(BloodBodyPartType.Torso_Upper, true, false, false);
            isoGameCharacter.addBlood(BloodBodyPartType.UpperArm_L, true, false, false);
            isoGameCharacter.addBlood(BloodBodyPartType.UpperArm_R, true, false, false);
         }

         damageSplit *= this.combatConfig.get(CombatConfigKey.HEAD_HIT_DAMAGE_SPLIT_MODIFIER);
      }

      if (RagdollBodyPart.isLeg(bodyPart) || RagdollBodyPart.isArm(bodyPart)) {
         damageSplit *= this.combatConfig.get(CombatConfigKey.LEG_HIT_DAMAGE_SPLIT_MODIFIER);
      }

      int partHit = RagdollBodyPart.getBodyPartType(bodyPart);
      float calculatedDamage = this.calculateHitLocationDamage(isoGameCharacter, weapon, partHit, damageSplit);
      StatisticsManager.getInstance().incrementStatistic(StatisticType.Player, StatisticCategory.Combat, "Bullet Damage", calculatedDamage);
      return calculatedDamage;
   }

   private float applyTotalDefense(float damageSplit, float totalDefense) {
      return damageSplit * Math.abs(1.0F - totalDefense / 100.0F);
   }

   private float calculateHitLocationDamage(IsoGameCharacter isoGameCharacter, HandWeapon weapon, int partHit, float damageSplit) {
      float totalDefense = this.calculateTotalDefense(partHit, isoGameCharacter, weapon);
      float calculatedDamage = this.applyTotalDefense(damageSplit, totalDefense);
      if (Core.debug) {
         BodyPartType partType = BodyPartType.FromIndex(partHit);
         DebugType.Combat
            .debugln(
               "Zombie got hit in "
                  + BodyPartType.getDisplayName(partType)
                  + " with a "
                  + weapon.getFullType()
                  + " for "
                  + calculatedDamage
                  + " out of "
                  + damageSplit
                  + " after totalDef of "
                  + totalDefense
                  + "% was applied"
            );
      }

      return calculatedDamage;
   }

   public void resolveSpikedArmorDamage(IsoGameCharacter owner, HandWeapon weapon, IsoGameCharacter hitZombie, int partHit) {
      boolean shove = ((IsoLivingCharacter)owner).isDoShove();
      if (shove || WeaponType.getWeaponType(weapon) == WeaponType.KNIFE) {
         boolean behind;
         if (!owner.isAimAtFloor()) {
            behind = owner.isBehind(hitZombie);
         } else {
            behind = hitZombie.isFallOnFront();
         }

         boolean spikedPart;
         if (behind) {
            spikedPart = hitZombie.bodyPartIsSpikedBehind(partHit);
         } else {
            spikedPart = hitZombie.bodyPartIsSpiked(partHit);
         }

         boolean spikedFoot = spikedPart && owner.isAimAtFloor() && shove;
         boolean spikedPrimary = spikedPart && !spikedFoot && (owner.getPrimaryHandItem() == null || owner.getPrimaryHandItem() instanceof HandWeapon);
         boolean spikedSecondary = spikedPart
            && !spikedFoot
            && (owner.getSecondaryHandItem() == null || owner.getSecondaryHandItem() instanceof HandWeapon)
            && shove;
         if (spikedFoot) {
            hitZombie.addBlood(BloodBodyPartType.FromIndex(partHit), true, false, false);
            owner.spikePart(BodyPartType.Foot_R);
         }

         if (spikedPrimary) {
            hitZombie.addBlood(BloodBodyPartType.FromIndex(partHit), true, false, false);
            owner.spikePart(BodyPartType.Hand_R);
         }

         if (spikedSecondary) {
            hitZombie.addBlood(BloodBodyPartType.FromIndex(partHit), true, false, false);
            owner.spikePart(BodyPartType.Hand_L);
         }
      }
   }

   private boolean applyKnifeDeathEffect(IsoGameCharacter owner, IsoZombie hitZombie) {
      boolean removeKnife = false;
      if ("KnifeDeath".equals(owner.getVariableString("ZombieHitReaction")) && !"Tutorial".equals(Core.gameMode)) {
         int rand = 8;
         if (hitZombie.isCurrentState(AttackState.instance())) {
            rand = 3;
         }

         int knifeLvl = owner.getPerkLevel(Perks.SmallBlade) + 1;
         if (Rand.NextBool(rand + knifeLvl * 2)) {
            InventoryItem item = owner.getPrimaryHandItem();
            owner.getInventory().Remove(item);
            owner.removeFromHands(item);
            hitZombie.setAttachedItem("JawStab", item);
            hitZombie.setJawStabAttach(true);
            removeKnife = true;
         }

         hitZombie.setKnifeDeath(true);
      }

      return removeKnife;
   }

   public void setAimingDelay(IsoPlayer isoPlayer, HandWeapon handWeapon) {
      float aimingDelay = isoPlayer.getAimingDelay()
         + (
            handWeapon.getRecoilDelay(isoPlayer) * this.combatConfig.get(CombatConfigKey.POST_SHOT_AIMING_DELAY_RECOIL_MODIFIER)
               + handWeapon.getAimingTime() * this.combatConfig.get(CombatConfigKey.POST_SHOT_AIMING_DELAY_AIMING_MODIFIER)
         );
      float maximumAimingDelay = isoPlayer.getPrimaryHandItem() instanceof HandWeapon ? ((HandWeapon)isoPlayer.getPrimaryHandItem()).getAimingTime() : 0.0F;
      isoPlayer.setAimingDelay(PZMath.clamp(aimingDelay, 0.0F, maximumAimingDelay));
   }

   private static final class CalcHitListGrappleReusables {
      static final ArrayList<IsoMovingObject> foundObjects = new ArrayList<>();
      static final Vector4f posAndDot = new Vector4f();
   }

   private static class HitChanceData {
      public float hitChance = 0.0F;
      public float aimPenalty = 0.0F;

      public HitChanceData() {
      }
   }

   private static class Holder {
      private static final CombatManager instance = new CombatManager();
   }

   private interface LOSVisitor {
      boolean visit(IsoGridSquare var1, IsoGridSquare var2);

      TestResults getResult();
   }

   private static final class TargetHitList {
      public final IsoObject target;
      public final List<WeaponHit> hits = new ArrayList<>();
      public boolean helmetFall;

      public TargetHitList(IsoObject target) {
         this.target = target;
      }

      public void addHit(WeaponHit hit, boolean helmetFall) {
         this.hits.add(hit);
         this.helmetFall |= helmetFall;
      }
   }

   private static final class WindowVisitor implements CombatManager.LOSVisitor {
      private IsoGameCharacter owner;
      private HandWeapon weapon;
      private TestResults test;
      private IsoWindow window;

      private void init(IsoGameCharacter owner, HandWeapon weapon) {
         this.owner = owner;
         this.weapon = weapon;
         this.test = TestResults.Clear;
         this.window = null;
      }

      @Override
      public boolean visit(IsoGridSquare a, IsoGridSquare b) {
         if (a != null && b != null) {
            boolean bSpecialDiag = true;
            boolean bIgnoreDoors = false;
            TestResults newTest = a.testVisionAdjacent(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ(), true, false);
            if (newTest == TestResults.ClearThroughWindow) {
               IsoWindow window = a.getWindowTo(b);
               if (this.isHittable(window) && window.TestVision(a, b) == VisionResult.Unblocked) {
                  this.window = window;
                  return true;
               }
            }

            if (newTest != TestResults.Blocked
               && this.test != TestResults.Clear
               && (newTest != TestResults.ClearThroughWindow || this.test != TestResults.ClearThroughOpenDoor)) {
               if (newTest == TestResults.ClearThroughClosedDoor && this.test == TestResults.ClearThroughOpenDoor) {
                  this.test = newTest;
               }
            } else {
               this.test = newTest;
            }

            return this.test == TestResults.Blocked;
         } else {
            return false;
         }
      }

      @Override
      public TestResults getResult() {
         return this.test;
      }

      boolean isHittable(IsoWindow window) {
         if (window == null) {
            return false;
         } else if (!window.isDestroyed() && !window.IsOpen()) {
            return true;
         } else {
            return !window.isBarricaded() ? false : this.owner == null || this.weapon == null || !window.canAttackBypassIsoBarricade(this.owner, this.weapon);
         }
      }
   }
}

