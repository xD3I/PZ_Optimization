/*
 * Decompiled with CFR 0.152.
 */
package zombie.iso;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.joml.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import zombie.AttackType;
import zombie.CollisionManager;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.MovingObjectUpdateScheduler;
import zombie.SoundManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.ai.astar.Mover;
import zombie.ai.states.AttackState;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.CollideWithWallState;
import zombie.ai.states.CrawlingZombieTurnState;
import zombie.ai.states.PathFindState;
import zombie.ai.states.StaggerBackState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.animals.AnimalAttackState;
import zombie.ai.states.animals.AnimalClimbOverFenceState;
import zombie.ai.states.animals.AnimalZoneState;
import zombie.audio.TreeSoundManager;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieAI;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.component.NetworkZombieComponent;
import zombie.characters.component.StateMachineComponent;
import zombie.characters.skills.PerkFactory;
import zombie.characters.traits.CharacterTraits;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.debug.DebugType;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;
import zombie.iso.BentFences;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.regions.IWorldRegion;
import zombie.iso.fboRenderChunk.FBORenderObjectOutline;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.RenderEffectType;
import zombie.iso.objects.interfaces.Thumpable;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.iso.zones.Zone;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.ServerOptions;
import zombie.pathfind.PathFindBehavior2;
import zombie.pathfind.PolygonalMap2;
import zombie.popman.ZombiePopulationManager;
import zombie.scripting.objects.CharacterTrait;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.MoodleType;
import zombie.ui.UIManager;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.lambda.PZOptional;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;

@UsedFromLua
public class IsoMovingObject
extends IsoObject
implements Mover {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.iso.IsoMovingObject");
    }

    // pzopt: entityUpdateParallel. The position-snapshot slot: while a batch is in flight, getX/getY/getZ of an
    // entity whose pzoptSnapshotFrame matches the batch answer from pzopt.UpdateBatch's frozen arrays instead of
    // the live fields another worker may be writing. No initializers on purpose — a fresh entity's frame stamp of
    // 0 never matches a batch (the counter starts at 1), so the constructors stay byte-identical to stock.
    public int pzoptSnapshotIndex; // pzopt: entityUpdateParallel
    public long pzoptSnapshotFrame; // pzopt: entityUpdateParallel
    // pzopt: entityUpdateParallel. The setMovingSquare argument latched during a batch window (last call wins),
    // applied by the game thread right after the join — see UpdateBatch.deferMovingSquare.
    public IsoGridSquare pzoptDeferredSquare; // pzopt: entityUpdateParallel
    public long pzoptDeferredSquareFrame; // pzopt: entityUpdateParallel
    public float pzoptRipX, pzoptRipY, pzoptRipT; // pzopt: puddle ripples, where / when the last ring was started (pzopt.Ripples)
    public int pzoptRipFoot; // pzopt: puddle ripples, the foot that stepped last
    // pzopt: postupdateParallel (Louisville 120 plan B). The frame stamp of a movement computed by pzopt.PostupdateBatch, the
    // square its setMovingSquare was latched with, and the fields saved before the task (restored on a hazard).
    public long pzoptMoveFrame; // pzopt: postupdateParallel
    public volatile long pzoptEsDrawn; // pzopt: entity shadows, the gather frame it was last drawn as a model (EntityShadow.bind); no initializer (0: never)
    public IsoGridSquare pzoptMoveSq; // pzopt: postupdateParallel
    public boolean pzoptMoveSqSet; // pzopt: postupdateParallel
    public pzopt.PostupdateBatch.Snap pzoptMoveSnap; // pzopt: postupdateParallel

    /** pzopt: postupdateParallel, the fields a postupdate writes, saved. */
    public void pzoptMoveSave(pzopt.PostupdateBatch.Snap s) {
        s.x = this.x; s.y = this.y; s.z = this.z; s.nx = this.nx; s.ny = this.ny; s.lx = this.lastX; s.ly = this.ly; s.lz = this.lz;
        s.impulsex = this.impulsex; s.impulsey = this.impulsey; s.current = this.current; s.last = this.last; s.square = this.square;
        s.collidedN = this.collidedN; s.collidedS = this.collidedS; s.collidedW = this.collidedW; s.collidedE = this.collidedE;
        s.collidedThisFrame = this.collidedThisFrame; s.collidedWithDoor = this.collidedWithDoor; s.collidedWithVehicle = this.collidedWithVehicle;
        s.altCollide = this.altCollide; s.firstUpdate = this.firstUpdate; s.collidedObject = this.collidedObject; s.collideType = this.collideType;
        s.timeSinceZombieAttack = this.timeSinceZombieAttack; s.lastTargettedBy = this.lastTargettedBy;
    }

    /** pzopt: postupdateParallel, the saved fields put back. */
    public void pzoptMoveRestore(pzopt.PostupdateBatch.Snap s) {
        this.x = s.x; this.y = s.y; this.z = s.z; this.nx = s.nx; this.ny = s.ny; this.lastX = s.lx; this.ly = s.ly; this.lz = s.lz;
        this.impulsex = s.impulsex; this.impulsey = s.impulsey; this.current = s.current; this.last = s.last; this.square = s.square;
        this.collidedN = s.collidedN; this.collidedS = s.collidedS; this.collidedW = s.collidedW; this.collidedE = s.collidedE;
        this.collidedThisFrame = s.collidedThisFrame; this.collidedWithDoor = s.collidedWithDoor; this.collidedWithVehicle = s.collidedWithVehicle;
        this.altCollide = s.altCollide; this.firstUpdate = s.firstUpdate; this.collidedObject = s.collidedObject; this.collideType = s.collideType;
        this.timeSinceZombieAttack = s.timeSinceZombieAttack; this.lastTargettedBy = s.lastTargettedBy;
    }

    /** pzopt: devPostupdateCheck, the first field that differs from a saved result, or null. */
    public String pzoptMoveDiff(pzopt.PostupdateBatch.Snap s) {
        if (Float.floatToIntBits(this.x) != Float.floatToIntBits(s.x) || Float.floatToIntBits(this.y) != Float.floatToIntBits(s.y) || Float.floatToIntBits(this.z) != Float.floatToIntBits(s.z)) return "xyz";
        if (Float.floatToIntBits(this.nx) != Float.floatToIntBits(s.nx) || Float.floatToIntBits(this.ny) != Float.floatToIntBits(s.ny)) return "next";
        if (Float.floatToIntBits(this.lastX) != Float.floatToIntBits(s.lx) || Float.floatToIntBits(this.ly) != Float.floatToIntBits(s.ly) || Float.floatToIntBits(this.lz) != Float.floatToIntBits(s.lz)) return "last xyz";
        if (this.impulsex != s.impulsex || this.impulsey != s.impulsey) return "impulse";
        if (this.current != s.current || this.last != s.last || this.square != s.square) return "squares";
        if (this.collidedN != s.collidedN || this.collidedS != s.collidedS || this.collidedW != s.collidedW || this.collidedE != s.collidedE
            || this.collidedThisFrame != s.collidedThisFrame || this.collidedWithDoor != s.collidedWithDoor || this.collidedWithVehicle != s.collidedWithVehicle) return "collided";
        if (this.altCollide != s.altCollide || this.firstUpdate != s.firstUpdate) return "altCollide/firstUpdate";
        if (this.collidedObject != s.collidedObject || this.collideType != s.collideType) return "collidedObject/type";
        if (this.timeSinceZombieAttack != s.timeSinceZombieAttack || this.lastTargettedBy != s.lastTargettedBy) return "attack bookkeeping";
        return null;
    }

    public static TreeSoundManager treeSoundMgr = new TreeSoundManager();
    public static final int MAX_ZOMBIES_EATING = 3;
    private static int idCount;
    private static final Vector2 tempo;
    public boolean noDamage;
    public IsoGridSquare last;
    private float lastX;
    private float ly;
    private float lz;
    private float nx;
    private float ny;
    private float x;
    private float y;
    private float z;
    public Vector2 reqMovement = new Vector2();
    public IsoSpriteInstance def;
    protected IsoGridSquare current;
    protected Vector2 hitDir = new Vector2();
    protected final int id;
    private final String uid;
    protected IsoGridSquare movingSq;
    protected boolean solid = true;
    protected float width = 0.24f;
    protected boolean shootable = true;
    protected boolean collidable = true;
    protected Vector2 movementLastFrame = new Vector2();
    protected float weight = 1.0f;
    boolean onFloor;
    private boolean closeKilled;
    private String collideType;
    private float lastCollideTime;
    private int timeSinceZombieAttack = 1000000;
    private boolean collidedE;
    private boolean collidedN;
    private IsoObject collidedObject;
    private boolean collidedS;
    private boolean collidedThisFrame;
    private boolean collidedW;
    private boolean collidedWithDoor;
    private boolean collidedWithVehicle;
    private boolean destroyed;
    private boolean firstUpdate = true;
    private float impulsex;
    private float impulsey;
    private float limpulsex;
    private float limpulsey;
    private float hitForce;
    private float hitFromAngle;
    private int pathFindIndex = -1;
    private float stateEventDelayTimer;
    private Thumpable thumpTarget;
    private boolean altCollide;
    private IsoZombie lastTargettedBy;
    private float feelersize = 0.5f;
    private final ArrayList<IsoZombie> eatingZombies = new ArrayList();
    private final AnimationPlayerRecorder animationRecorder;
    private boolean animPlayerRecordingExclusive;
    private UpdateSchedulerSimulationLevel currentSimulationLevel = UpdateSchedulerSimulationLevel.FULL;

    public IsoMovingObject() {
        this(null, true);
    }

    public IsoMovingObject(boolean bObjectListAdd) {
        this(null, bObjectListAdd);
    }

    public IsoMovingObject(IsoSprite spr, boolean bObjectListAdd) {
        this.id = idCount++;
        this.uid = String.format("%s-%s-%s", this.getClass().getSimpleName(), this.id, UUID.randomUUID());
        this.sprite = spr != null ? spr : IsoSprite.CreateSprite(IsoSpriteManager.instance);
        this.animationRecorder = new AnimationPlayerRecorder(this);
        if (bObjectListAdd) {
            this.getCell().addMovingObject(this);
        }
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{  Name:" + this.getName() + ",  ID:" + this.getID() + " }";
    }

    public static int getIDCount() {
        return idCount;
    }

    public static void setIDCount(int aIDCount) {
        idCount = aIDCount;
    }

    public boolean isAnimationRecorderActive() {
        return this.animationRecorder.isRecording();
    }

    public AnimationPlayerRecorder getAnimationRecorder() {
        return this.animationRecorder;
    }

    public void closeAnimationRecorder() {
        this.animationRecorder.close();
    }

    private void updateAnimationRecorder() {
        if (AnimationPlayerRecorder.isAnimRecorderDiscardTriggered()) {
            this.animationRecorder.discardRecording();
        }
        this.animationRecorder.updateObjHighlight();
        boolean isWorldRecording = AnimationPlayerRecorder.isAnimationRecorderActiveAll();
        if (isWorldRecording) {
            this.animPlayerRecordingExclusive = false;
        }
        this.animationRecorder.setRecording(this.shouldAnimRecorderBeActive());
        if (this.animationRecorder.isRecording() || this.animationRecorder.hasActiveLine()) {
            this.animationRecorder.newFrame(IsoWorld.instance.getFrameNo());
            this.animationRecorder.logCharacterPos();
            this.animationRecorder.logVariable("sim.min.level", this.getMinimumSimulationLevel().toString());
            this.animationRecorder.logVariable("sim.min.frameMod", this.getMinimumSimulationLevel().getFrameMod());
            this.animationRecorder.logVariable("sim.level", this.getCurrentSimulationLevel().toString());
            this.animationRecorder.logVariable("sim.frameMod", this.getCurrentSimulationLevel().getFrameMod());
        }
    }

    public void setAnimRecorderActive(boolean isActive, boolean isExclusive) {
        this.animPlayerRecordingExclusive = isExclusive && isActive;
        this.animationRecorder.setRecording(isActive);
    }

    public boolean shouldAnimRecorderBeActive() {
        return this.animPlayerRecordingExclusive || AnimationPlayerRecorder.isAnimationRecorderActive(this) && !this.isSceneCulled();
    }

    public IsoBuilding getBuilding() {
        if (this.current == null) {
            return null;
        }
        IsoRoom r = this.current.getRoom();
        if (r == null) {
            return null;
        }
        return r.building;
    }

    public IWorldRegion getMasterRegion() {
        if (this.current != null) {
            return this.current.getIsoWorldRegion();
        }
        return null;
    }

    public float getWeight() {
        return this.weight;
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    public float getWeight(float x, float y) {
        return this.weight;
    }

    @Override
    public void onMouseRightClick(int lx, int ly) {
        if (this.square.getZ() == PZMath.fastfloor(IsoPlayer.getInstance().getZ()) && this.DistToProper(IsoPlayer.getInstance()) <= 2.0f) {
            IsoPlayer.getInstance().setDragObject(this);
        }
    }

    @Override
    public String getObjectName() {
        return "IsoMovingObject";
    }

    public void collideWith(IsoObject obj) {
        pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_COLLIDE_WITH); // pzopt: postupdateParallel — the collide hooks and their Lua events stay on the game thread
        if (this instanceof IsoGameCharacter && obj instanceof IsoGameCharacter) {
            LuaEventManager.triggerEvent("OnCharacterCollide", this, obj);
        } else {
            LuaEventManager.triggerEvent("OnObjectCollide", this, obj);
        }
    }

    public void doStairs() {
        State state;
        IsoGridSquare below;
        if (this.current == null) {
            return;
        }
        if (this.last == null) {
            return;
        }
        if (!(this instanceof IsoGameCharacter && ((IsoGameCharacter)this).isAnimal() && ((IsoAnimal)this).canClimbStairs())) {
            // empty if block
        }
        if (this instanceof IsoPhysicsObject) {
            return;
        }
        IsoGridSquare current = this.current;
        if ((current.has(IsoObjectType.stairsTN) || current.has(IsoObjectType.stairsTW)) && this.getZ() - (float)PZMath.fastfloor(this.getZ()) < 0.1f && (below = IsoWorld.instance.currentCell.getGridSquare(current.x, current.y, current.z - 1)) != null && (below.has(IsoObjectType.stairsTN) || below.has(IsoObjectType.stairsTW))) {
            current = below;
        }
        if (this instanceof IsoGameCharacter && (this.last.has(IsoObjectType.stairsTN) || this.last.has(IsoObjectType.stairsTW))) {
            this.setZ(Math.round(this.getZ()));
        }
        float z = this.getZ();
        if (current.HasStairs()) {
            z = current.getApparentZ(this.getX() - (float)current.getX(), this.getY() - (float)current.getY());
        }
        float minPos = 0.2f;
        float maxPos = 0.8f;
        if (this.last.has(IsoObjectType.stairsTN) && this.last.u != null && this.last.u.has(IsoFlagType.WallN)) {
            if ((double)this.x - Math.floor(this.x) > (double)0.8f && (this.last.e == null || !this.last.e.has(IsoObjectType.stairsTN))) {
                this.setX((float)Math.floor(this.x) + 0.8f);
            }
            if (!current.HasStairs()) {
                this.setY(Math.round(this.getY()));
            }
        }
        if (this.last.has(IsoObjectType.stairsTW) && this.last.u != null && this.last.u.has(IsoFlagType.WallW)) {
            if ((double)this.y - Math.floor(this.y) > (double)0.8f && (this.last.s == null || !this.last.s.has(IsoObjectType.stairsTW))) {
                this.setY((float)Math.floor(this.y) + 0.8f);
            }
            if (!current.HasStairs()) {
                this.setX(Math.round(this.getX()));
            }
        }
        if (current.has(IsoFlagType.WallN) && current.d != null && current.d.has(IsoObjectType.stairsTN)) {
            if ((double)this.x - Math.floor(this.x) > (double)0.8f && (current.d.e == null || !current.d.e.has(IsoObjectType.stairsTN))) {
                this.setX((float)Math.floor(this.x) + 0.8f);
            }
            if ((double)this.x - Math.floor(this.x) < (double)0.2f && (current.d.w == null || !current.d.w.has(IsoObjectType.stairsTN))) {
                this.setX((float)Math.floor(this.x) + 0.2f);
            }
        }
        if (current.has(IsoFlagType.WallW) && current.d != null && current.d.has(IsoObjectType.stairsTW)) {
            if ((double)this.y - Math.floor(this.y) > (double)0.8f && (current.d.s == null || !current.d.s.has(IsoObjectType.stairsTW))) {
                this.setY((float)Math.floor(this.y) + 0.8f);
            }
            if ((double)this.y - Math.floor(this.y) < (double)0.2f && (current.d.n == null || !current.d.n.has(IsoObjectType.stairsTW))) {
                this.setY((float)Math.floor(this.y) + 0.2f);
            }
        }
        if (this instanceof IsoGameCharacter && ((state = ((IsoGameCharacter)this).getCurrentState()) == ClimbOverFenceState.instance() || state == ClimbThroughWindowState.instance())) {
            if (current.HasStairs() && this.getZ() > z) {
                this.setZ(Math.max(z, this.getZ() - 0.075f * GameTime.getInstance().getMultiplier()));
            }
            return;
        }
        if (Math.abs(z - this.getZ()) < 0.95f) {
            this.setZ(z);
        }
    }

    private void handleSlopedSurface() {
        float dz;
        float slopeHeightMax;
        if (this instanceof IsoPhysicsObject) {
            return;
        }
        if (this.current == null) {
            return;
        }
        if (this instanceof IsoGameCharacter && ((IsoGameCharacter)this).getVehicle() != null) {
            return;
        }
        if (this.last != null && this.last != this.current && this.last.hasSlopedSurface() && (slopeHeightMax = this.last.getSlopedSurfaceHeightMax()) == 1.0f) {
            this.setZ(Math.round(this.getZ()));
        }
        if ((dz = this.current.getSlopedSurfaceHeight(this.getX() % 1.0f, this.getY() % 1.0f)) <= 0.0f) {
            return;
        }
        this.setZ((float)this.current.z + dz);
    }

    @Override
    public int getID() {
        return this.id;
    }

    public String getUID() {
        return this.uid;
    }

    @Override
    public int getPathFindIndex() {
        return this.pathFindIndex;
    }

    public void setPathFindIndex(int pathFindIndex) {
        this.pathFindIndex = pathFindIndex;
    }

    public float getScreenX() {
        return IsoUtils.XToScreen(this.getX(), this.getY(), this.getZ(), 0);
    }

    public float getScreenY() {
        return IsoUtils.YToScreen(this.getX(), this.getY(), this.getZ(), 0);
    }

    public Thumpable getThumpTarget() {
        return this.thumpTarget;
    }

    public void setThumpTarget(Thumpable thumpTarget) {
        this.thumpTarget = thumpTarget;
    }

    public Vector2 getVectorFromDirection(Vector2 moveForwardVec) {
        return IsoMovingObject.getVectorFromDirection(moveForwardVec, this.getForwardIsoDirection());
    }

    public static Vector2 getVectorFromDirection(Vector2 moveForwardVec, IsoDirections dir) {
        if (moveForwardVec == null) {
            DebugType.General.warn("Supplied vector2 is null. Cannot be processed. Using fail-safe fallback.");
            moveForwardVec = new Vector2();
        }
        moveForwardVec.x = 0.0f;
        moveForwardVec.y = 0.0f;
        switch (dir) {
            case S: {
                moveForwardVec.x = 0.0f;
                moveForwardVec.y = 1.0f;
                break;
            }
            case N: {
                moveForwardVec.x = 0.0f;
                moveForwardVec.y = -1.0f;
                break;
            }
            case E: {
                moveForwardVec.x = 1.0f;
                moveForwardVec.y = 0.0f;
                break;
            }
            case W: {
                moveForwardVec.x = -1.0f;
                moveForwardVec.y = 0.0f;
                break;
            }
            case NW: {
                moveForwardVec.x = -1.0f;
                moveForwardVec.y = -1.0f;
                break;
            }
            case NE: {
                moveForwardVec.x = 1.0f;
                moveForwardVec.y = -1.0f;
                break;
            }
            case SW: {
                moveForwardVec.x = -1.0f;
                moveForwardVec.y = 1.0f;
                break;
            }
            case SE: {
                moveForwardVec.x = 1.0f;
                moveForwardVec.y = 1.0f;
            }
        }
        moveForwardVec.normalize();
        return moveForwardVec;
    }

    @Override
    public Vector3 getPosition(Vector3 position) {
        position.set(this.getX(), this.getY(), this.getZ());
        return position;
    }

    @Override
    public Vector3f getPosition(Vector3f out) {
        out.set(this.getX(), this.getY(), this.getZ());
        return out;
    }

    public Vector2 getPosition(Vector2 out) {
        out.set(this.getX(), this.getY());
        return out;
    }

    public void setPosition(float x, float y) {
        this.setX(x);
        this.setY(y);
    }

    public void setPosition(Vector2 pos) {
        this.setPosition(pos.x, pos.y);
    }

    public void setPosition(float x, float y, float z) {
        this.setX(x);
        this.setY(y);
        this.setZ(z);
    }

    @Override
    public float getX() {
        if (pzopt.UpdateBatch.frozen(this)) { // pzopt: entityUpdateParallel — a cross-entity read mid-batch is the frozen pre-batch position
            return pzopt.UpdateBatch.frozenX(this); // pzopt: entityUpdateParallel
        }
        return this.x;
    }

    public float setX(float x) {
        this.x = x;
        this.setNextX(x);
        return this.x;
    }

    public void setForceX(float x) {
        this.setX(x);
        this.setNextX(x);
        this.setLastX(x);
    }

    @Override
    public float getY() {
        if (pzopt.UpdateBatch.frozen(this)) { // pzopt: entityUpdateParallel — a cross-entity read mid-batch is the frozen pre-batch position
            return pzopt.UpdateBatch.frozenY(this); // pzopt: entityUpdateParallel
        }
        return this.y;
    }

    public float setY(float y) {
        this.y = y;
        this.setNextY(y);
        return this.y;
    }

    public void setForceY(float y) {
        this.setY(y);
        this.setNextY(y);
        this.setLastY(y);
    }

    @Override
    public float getZ() {
        if (pzopt.UpdateBatch.frozen(this)) { // pzopt: entityUpdateParallel — a cross-entity read mid-batch is the frozen pre-batch position
            return pzopt.UpdateBatch.frozenZ(this); // pzopt: entityUpdateParallel
        }
        return this.z;
    }

    public float setZ(float z) {
        z = Math.max(-32.0f, z);
        this.z = z = Math.min(31.0f, z);
        this.setLastZ(z);
        return this.z;
    }

    public IsoGridSquare getMovingSquare() {
        return this.movingSq;
    }

    public void setMovingSquare(IsoGridSquare newMovingSquare) {
        if (pzopt.PostupdateBatch.deferMove(this, newMovingSquare)) { // pzopt: postupdateParallel — latched in a movement task, committed at the zombie's place in the loop
            return; // pzopt: postupdateParallel
        }
        if (pzopt.UpdateBatch.deferMovingSquare(this, newMovingSquare)) { // pzopt: entityUpdateParallel — latched during the window, replayed on the game thread after the join
            return; // pzopt: entityUpdateParallel
        }
        if (newMovingSquare != this.movingSq && this.movingSq != null) {
            this.movingSq.getMovingObjects().remove(this);
        }
        this.movingSq = newMovingSquare;
        if (newMovingSquare != null && !newMovingSquare.getMovingObjects().contains(this)) {
            newMovingSquare.getMovingObjects().add(this);
        }
    }

    @Override
    public IsoGridSquare getSquare() {
        if (this.current != null) {
            return this.current;
        }
        return this.square;
    }

    public IsoGridSquare findCurrentGridSquare() {
        IsoGridSquare foundSquare;
        IsoCell cell = this.getCell();
        int xi = PZMath.fastfloor(this.getX());
        int yi = PZMath.fastfloor(this.getY());
        int zi = PZMath.fastfloor(this.getZ());
        int ziSign = PZMath.sign(zi);
        while ((foundSquare = cell.getGridSquare(xi, yi, zi)) == null && (zi -= ziSign) != -ziSign) {
        }
        return foundSquare;
    }

    public IsoBuilding getCurrentBuilding() {
        if (this.current == null) {
            return null;
        }
        if (this.current.getRoom() == null) {
            return null;
        }
        return this.current.getRoom().building;
    }

    public float Hit(HandWeapon weapon, IsoGameCharacter wielder, float damageSplit, boolean bIgnoreDamage, float modDelta) {
        return 0.0f;
    }

    public void moveUnmodded(float diffX, float diffY) {
        this.moveUnmoddedInternal(diffX, diffY);
    }

    protected final void moveUnmoddedInternal(float dirx, float diry) {
        this.setNextX(this.getNextX() + dirx);
        this.setNextY(this.getNextY() + diry);
        this.reqMovement.x = dirx;
        this.reqMovement.y = diry;
    }

    @Override
    public boolean isCharacter() {
        return this instanceof IsoGameCharacter;
    }

    public float DistTo(int x, int y) {
        return IsoUtils.DistanceManhatten(x, y, this.getX(), this.getY());
    }

    public float DistTo(IsoMovingObject other) {
        if (other == null) {
            return 0.0f;
        }
        return IsoUtils.DistanceManhatten(this.getX(), this.getY(), other.getX(), other.getY());
    }

    public float DistToProper(IsoObject other) {
        if (other instanceof IsoMovingObject) {
            IsoMovingObject movingObject = (IsoMovingObject)other;
            return PZMath.sqrt(this.DistToSquared(movingObject));
        }
        return IsoUtils.DistanceTo(this.getX(), this.getY(), other.getX(), other.getY());
    }

    public float DistToSquared(IsoMovingObject other) {
        BaseVehicle vehicleThis = Type.tryCastTo(this, BaseVehicle.class);
        BaseVehicle vehicleOther = Type.tryCastTo(other, BaseVehicle.class);
        if (vehicleThis != null && vehicleOther != null) {
            Vector2f p1 = BaseVehicle.allocVector2f();
            Vector2f p2 = BaseVehicle.allocVector2f();
            float distSq = vehicleThis.getClosestPointOnPoly(vehicleOther, p1, p2);
            BaseVehicle.releaseVector2f(p1);
            BaseVehicle.releaseVector2f(p2);
            return distSq;
        }
        if (vehicleThis != null) {
            Vector2f closest = BaseVehicle.allocVector2f();
            float distSq = vehicleThis.getClosestPointOnPoly(other.getX(), other.getY(), closest);
            BaseVehicle.releaseVector2f(closest);
            return distSq;
        }
        if (vehicleOther != null) {
            Vector2f closest = BaseVehicle.allocVector2f();
            float distSq = vehicleOther.getClosestPointOnPoly(this.getX(), this.getY(), closest);
            BaseVehicle.releaseVector2f(closest);
            return distSq;
        }
        return IsoUtils.DistanceToSquared(this.getX(), this.getY(), other.getX(), other.getY());
    }

    public float DistToSquared(float x, float y) {
        return IsoUtils.DistanceToSquared(x, y, this.getX(), this.getY());
    }

    public boolean isWithinRange(IsoMovingObject object, float minRange) {
        return object.getZi() == this.getZi() && this.DistTo(object) <= minRange;
    }

    public final <ObjectType extends IsoMovingObject> ObjectType getClosestObject(List<ObjectType> objects) {
        return (ObjectType)PZArrayUtil.getClosest(this, objects, IsoMovingObject::DistToSquared);
    }

    public final <ObjectType extends IsoMovingObject> ObjectType getClosestStaticMovingObjectInNearbySquares(Class<? extends ObjectType> objectType, BiPredicate<IsoGridSquare, IsoGridSquare> squareFilter, Predicate<ObjectType> objectFilter) {
        IsoGridSquare fromSquare = this.getSquare();
        if (fromSquare == null) {
            return null;
        }
        return this.getClosestObject(fromSquare.getStaticMovingObjectsInNearbySquares(objectType, squareFilter, objectFilter));
    }

    @Override
    public void load(ByteBuffer input, int worldVersion, boolean isDebugSave) throws IOException {
        float offsetX = input.getFloat();
        float offsetY = input.getFloat();
        this.setX(this.setLastX(this.setNextX(input.getFloat() + (float)(IsoWorld.saveoffsetx * 256))));
        this.setY(this.setLastY(this.setNextY(input.getFloat() + (float)(IsoWorld.saveoffsety * 256))));
        this.setZ(this.setLastZ(input.getFloat()));
        this.setForwardIsoDirection(IsoDirections.fromIndex(input.getInt()));
        if (input.get() != 0) {
            if (this.table == null) {
                this.table = LuaManager.platform.newTable();
            }
            this.table.load(input, worldVersion);
        }
    }

    public String getDescription(String separatorStr) {
        return this.getClass().getSimpleName() + " [" + separatorStr + "offset=(" + this.offsetX + ", " + this.offsetY + ") | " + separatorStr + "pos=(" + this.getX() + ", " + this.getY() + ", " + this.getZ() + ") | " + separatorStr + "dir=" + this.getForwardIsoDirection().name() + " ] ";
    }

    @Override
    public void save(ByteBuffer output, boolean isDebugSave) throws IOException {
        DebugType.Saving.trace("Saving: %s", this);
        output.put(this.Serialize() ? (byte)1 : 0);
        output.put(IsoObject.factoryGetClassID(this.getObjectName()));
        output.putFloat(this.offsetX);
        output.putFloat(this.offsetY);
        output.putFloat(this.getX());
        output.putFloat(this.getY());
        output.putFloat(this.getZ());
        output.putInt(this.getForwardIsoDirection().ordinal());
        if (this.table != null && !this.table.isEmpty()) {
            output.put((byte)1);
            this.table.save(output);
        } else {
            output.put((byte)0);
        }
    }

    @Override
    public void removeFromWorld() {
        IsoCell cell = this.getCell();
        if (cell.isSafeToAdd()) {
            cell.getObjectList().remove(this);
            cell.getRemoveList().remove(this);
        } else {
            cell.getRemoveList().add(this);
        }
        cell.getAddList().remove(this);
        MovingObjectUpdateScheduler.instance.removeObject(this);
        super.removeFromWorld();
    }

    @Override
    public void removeFromSquare() {
        pzopt.RagdollLedger.leftSquare(this); // pzopt: ragdollQuitSweep evidence, a live ragdoll's character leaving its square
        if (this.current != null) {
            this.current.getMovingObjects().remove(this);
        }
        if (this.last != null) {
            this.last.getMovingObjects().remove(this);
        }
        this.current = this.last = null; // pzopt: decompiler fix — the jar chains the two null stores (one aconst_null + dup_x1); CFR split them
        this.setMovingSquare(null);
        if (this.square != null) {
            this.square.getStaticMovingObjects().remove(this);
        }
        super.removeFromSquare();
    }

    public IsoGridSquare getFuturWalkedSquare() {
        IsoGridSquare feeler;
        if (this.current != null && (feeler = this.getFeelerTile(this.feelersize)) != null && feeler != this.current) {
            return feeler;
        }
        return null;
    }

    public float getGlobalMovementMod() {
        return this.getGlobalMovementMod(true);
    }

    public float getGlobalMovementMod(boolean bDoNoises) {
        boolean pzoptPush = pzopt.Sway.frameOn && pzopt.Sway.moved(this); // pzopt: foliage sway, this character bends the plants it walks through (instead of stock's rustle re-bakes)
        if (this.current != null && this.getZ() - (float)PZMath.fastfloor(this.getZ()) < 0.5f) {
            IsoGridSquare feeler;
            if (this.current.has(IsoObjectType.tree) || this.current.hasBush()) {
                pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_TREE); // pzopt: postupdateParallel — tree noises and the rustle stay on the game thread
                if (bDoNoises) {
                    this.doTreeNoises();
                }
                for (int i = 1; !pzoptPush && i < this.current.getObjects().size(); ++i) { // pzopt: foliage sway replaces the rustle
                    IsoObject obj = this.current.getObjects().get(i);
                    if (obj instanceof IsoTree) {
                        obj.setRenderEffect(RenderEffectType.Vegetation_Rustle);
                        continue;
                    }
                    if (!obj.isBush()) continue;
                    obj.setRenderEffect(RenderEffectType.Vegetation_Rustle);
                }
            }
            if ((feeler = this.getFeelerTile(this.feelersize)) != null && feeler != this.current && (feeler.has(IsoObjectType.tree) || feeler.hasBush())) {
                pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_TREE); // pzopt: postupdateParallel — tree noises and the rustle stay on the game thread
                if (bDoNoises) {
                    this.doTreeNoises();
                }
                for (int i = 1; !pzoptPush && i < feeler.getObjects().size(); ++i) { // pzopt: foliage sway replaces the rustle
                    IsoObject obj = feeler.getObjects().get(i);
                    if (obj instanceof IsoTree) {
                        obj.setRenderEffect(RenderEffectType.Vegetation_Rustle);
                        continue;
                    }
                    if (!obj.isBush()) continue;
                    obj.setRenderEffect(RenderEffectType.Vegetation_Rustle);
                }
            }
        }
        if (this.current != null && this.current.HasStairs()) {
            return 0.75f;
        }
        return 1.0f;
    }

    protected void doTreeNoises() {
        if (GameServer.server) {
            return;
        }
        if (this instanceof IsoPhysicsObject) {
            return;
        }
        if (this.current == null) {
            return;
        }
        if (!SoundManager.instance.isListenerInRange(this.getX(), this.getY(), 20.0f)) {
            return;
        }
        treeSoundMgr.addSquare(this.current);
    }

    public void postupdate() {
        float movementMod;
        IsoGameCharacter thisChr = Type.tryCastTo(this, IsoGameCharacter.class);
        IsoPlayer thisPlayer = Type.tryCastTo(this, IsoPlayer.class);
        IsoZombie thisZombie = Type.tryCastTo(this, IsoZombie.class);
        this.slideHeadAwayFromWalls(true);
        if (thisPlayer != null && thisPlayer.isLocalPlayer() && !(thisPlayer instanceof IsoAnimal)) {
            IsoPlayer.setInstance(thisPlayer);
            IsoCamera.setCameraCharacter(thisPlayer);
        }
        this.ensureOnTile();
        if (this.lastTargettedBy != null && this.lastTargettedBy.isDead()) {
            this.lastTargettedBy = null;
        }
        if (this.lastTargettedBy != null && this.timeSinceZombieAttack > 120) {
            this.lastTargettedBy = null;
        }
        ++this.timeSinceZombieAttack;
        if (thisPlayer != null) {
            thisPlayer.setLastCollidedW(this.collidedW);
            thisPlayer.setLastCollidedN(this.collidedN);
        }
        if (this.destroyed) {
            return;
        }
        this.collidedThisFrame = false;
        this.collidedN = false;
        this.collidedS = false;
        this.collidedW = false;
        this.collidedE = false;
        this.collidedWithDoor = false;
        this.last = this.current;
        this.collidedObject = null;
        this.setNextX(this.getNextX() + this.impulsex);
        this.setNextY(this.getNextY() + this.impulsey);
        Vector2 tempo = pzopt.PostupdateBatch.computing() ? pzopt.PostupdateBatch.tempo() : IsoMovingObject.tempo; // pzopt: postupdateParallel — the static scratch is per thread in a movement task
        tempo.set(this.getNextX() - this.getX(), this.getNextY() - this.getY());
        if (tempo.getLength() > 1.0f) {
            tempo.normalize();
            this.setNextX(this.getX() + tempo.getX());
            this.setNextY(this.getY() + tempo.getY());
        }
        this.impulsex = 0.0f;
        this.impulsey = 0.0f;
        if (thisZombie != null && PZMath.fastfloor(this.getZ()) == 0 && this.getCurrentBuilding() == null && !this.isInLoadedArea(PZMath.fastfloor(this.getNextX()), PZMath.fastfloor(this.getNextY())) && (thisZombie.isCurrentState(PathFindState.instance()) || thisZombie.isCurrentState(WalkTowardState.instance()))) {
            pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_VIRTUALIZE); // pzopt: postupdateParallel — the virtualisation stays on the game thread
            ZombiePopulationManager.instance.virtualizeZombie(thisZombie);
            return;
        }
        IsoAnimal thisAnimal = Type.tryCastTo(this, IsoAnimal.class);
        if (thisAnimal != null && this.getZi() == 0 && this.getCurrentBuilding() == null && !this.isInLoadedArea(this.getNextXi(), this.getNextYi()) && thisAnimal.isCurrentState(AnimalZoneState.instance())) {
            AnimalPopulationManager.getInstance().virtualizeAnimal(thisAnimal);
            return;
        }
        float oldNx = this.getNextX();
        float oldNy = this.getNextY();
        this.collidedWithVehicle = false;
        if (!(thisChr == null || this.isOnFloor() || thisChr.getVehicle() != null || !this.isCollidable() || thisPlayer != null && thisPlayer.isNoClip())) {
            int fromZ;
            int fromX = PZMath.fastfloor(this.getX());
            int fromY = PZMath.fastfloor(this.getY());
            int toX = PZMath.fastfloor(this.getNextX());
            int toY = PZMath.fastfloor(this.getNextY());
            int toZ = fromZ = PZMath.fastfloor(this.getZ());
            if (thisChr.getCurrentState() == null || !thisChr.getCurrentState().isIgnoreCollide(thisChr, fromX, fromY, fromZ, toX, toY, toZ)) {
                Vector2f v = pzopt.PostupdateBatch.computing() ? pzopt.PostupdateBatch.vehicleFree(thisChr, this.getNextX(), this.getNextY()) : PolygonalMap2.instance.resolveCollision(thisChr, this.getNextX(), this.getNextY(), L_postUpdate.vector2f); // pzopt: postupdateParallel — in a task: unchanged with no vehicle near (a hazard otherwise)
                if (v.x != this.getNextX() || v.y != this.getNextY()) {
                    this.setNextX(v.x);
                    this.setNextY(v.y);
                    this.collidedWithVehicle = true;
                }
            }
        }
        float onx = this.getNextX();
        float ony = this.getNextY();
        float len = 0.0f;
        boolean bDidCollide = false;
        if (this.collidable) {
            if (this.altCollide) {
                this.DoCollide(2);
            } else {
                this.DoCollide(1);
            }
            if (this.collidedN || this.collidedS) {
                this.setNextY(this.getLastY());
                this.DoCollideNorS();
            }
            if (this.collidedW || this.collidedE) {
                this.setNextX(this.getLastX());
                this.DoCollideWorE();
            }
            if (this.altCollide) {
                this.DoCollide(1);
            } else {
                this.DoCollide(2);
            }
            boolean bl = this.altCollide = !this.altCollide;
            if (this.collidedN || this.collidedS) {
                this.setNextY(this.getLastY());
                this.DoCollideNorS();
                bDidCollide = true;
            }
            if (this.collidedW || this.collidedE) {
                this.setNextX(this.getLastX());
                this.DoCollideWorE();
                bDidCollide = true;
            }
            len = Math.abs(this.getNextX() - this.getLastX()) + Math.abs(this.getNextY() - this.getLastY());
            float lnx = this.getNextX();
            float lny = this.getNextY();
            this.setNextX(onx);
            this.setNextY(ony);
            if (this.collidable && bDidCollide) {
                if (this.altCollide) {
                    this.DoCollide(2);
                } else {
                    this.DoCollide(1);
                }
                if (this.collidedN || this.collidedS) {
                    this.setNextY(this.getLastY());
                    this.DoCollideNorS();
                }
                if (this.collidedW || this.collidedE) {
                    this.setNextX(this.getLastX());
                    this.DoCollideWorE();
                }
                if (this.altCollide) {
                    this.DoCollide(1);
                } else {
                    this.DoCollide(2);
                }
                if (this.collidedN || this.collidedS) {
                    this.setNextY(this.getLastY());
                    this.DoCollideNorS();
                }
                if (this.collidedW || this.collidedE) {
                    this.setNextX(this.getLastX());
                    this.DoCollideWorE();
                }
                if (Math.abs(this.getNextX() - this.getLastX()) + Math.abs(this.getNextY() - this.getLastY()) < len) {
                    this.setNextX(lnx);
                    this.setNextY(lny);
                }
            }
        }
        if (this.collidedThisFrame) {
            this.setCurrent(this.last);
        }
        this.checkHitWall();
        if (!(thisPlayer == null || thisPlayer.isCurrentState(CollideWithWallState.instance()) || this.collidedN || this.collidedS || this.collidedW || this.collidedE)) {
            this.setCollideType(null);
        }
        float dx = this.getNextX() - this.getX();
        float dy = this.getNextY() - this.getY();
        float f = movementMod = Math.abs(dx) > 0.0f || Math.abs(dy) > 0.0f ? this.getGlobalMovementMod() : 0.0f;
        if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
            dx *= movementMod;
            dy *= movementMod;
        }
        this.setX(this.getX() + dx);
        this.setY(this.getY() + dy);
        this.doStairs();
        this.handleSlopedSurface();
        this.setCurrent(this.findCurrentGridSquare());
        this.snapZToCurrentSquare();
        this.setMovingSquare(this.current);
        if (this.current == null && this.last != null) {
            this.setCurrent(this.last);
            this.setX(this.current.getCenterX());
            this.setY(this.current.getCenterY());
            this.snapZToCurrentSquare();
        }
        this.ensureOnTile();
        this.square = this.current;
        this.firstUpdate = false;
    }

    protected void snapZToCurrentSquare() {
        if (this.current != null && this.shouldSnapZToCurrentSquare()) {
            this.setZ(this.setLastZ(PZMath.min(this.getZ(), (float)this.current.getZ() + 0.99999f)));
        }
    }

    protected void snapZToCurrentSquareExact() {
        if (this.current != null && this.shouldSnapZToCurrentSquare()) {
            this.setZ(this.current.getZ());
        }
    }

    public boolean shouldSnapZToCurrentSquare() {
        return true;
    }

    public void updateAnimation() {
    }

    public void ensureOnTile() {
        if (this.current == null) {
            if (!(this instanceof IsoPlayer)) {
                if (this instanceof IsoSurvivor) {
                    IsoWorld.instance.currentCell.Remove(this);
                    IsoWorld.instance.currentCell.getSurvivorList().remove(this);
                }
                return;
            }
            boolean bDo = true;
            if (this.last != null && (this.last.has(IsoObjectType.stairsTN) || this.last.has(IsoObjectType.stairsTW))) {
                this.setCurrent(this.getCell().getGridSquare(this.getXi(), this.getYi(), this.getZi() + 1));
                bDo = false;
            }
            if (this.current == null) {
                this.setCurrent(this.getCell().getGridSquare(this.getXi(), this.getYi(), this.getZi()));
                if (this.current == null) {
                    this.setCurrent(this.getCell().getGridSquare(this.getXi(), this.getYi(), this.getZi() + 1));
                    if (this.current != null) {
                        this.snapZToCurrentSquareExact();
                    }
                }
                return;
            }
            if (bDo) {
                this.setX(this.setNextX((float)this.current.getX() + 0.5f));
                this.setY(this.setNextY((float)this.current.getY() + 0.5f));
            }
            this.setZ(this.current.getZ());
        }
    }

    public void preupdate() {
        this.setNextX(this.getX());
        this.setNextY(this.getY());
        this.updateAnimationRecorder();
    }

    @Override
    public void renderlast() {
        int playerIndex = IsoCamera.frameState.playerIndex;
        if (this.isOutlineHighlight(playerIndex)) {
            if (PerformanceSettings.fboRenderChunk) {
                long timeRender = FBORenderObjectOutline.getInstance().getDuringUIRenderTime(playerIndex, this);
                long timeUpdate = FBORenderObjectOutline.getInstance().getDuringUIUpdateTime(playerIndex, this);
                if (timeRender != 0L && timeRender == UIManager.uiRenderTimeMS) {
                    return;
                }
                if (timeUpdate != 0L && timeUpdate == UIManager.uiUpdateTimeMS) {
                    return;
                }
            }
            this.setOutlineHighlight(playerIndex, false);
        }
    }

    public void spotted(IsoMovingObject other, boolean bForced) {
    }

    @Override
    public void update() {
        if (this.def == null) {
            this.def = IsoSpriteInstance.get(this.sprite);
        }
        this.movementLastFrame.x = this.getX() - this.getLastX();
        this.movementLastFrame.y = this.getY() - this.getLastY();
        this.setLastX(this.getX());
        this.setLastY(this.getY());
        this.setLastZ(this.getZ());
        this.square = this.current;
        if (this.sprite != null) {
            this.sprite.update(this.def);
        }
        this.stateEventDelayTimer -= GameTime.instance.getMultiplier();
    }

    private void Collided() {
        this.collidedThisFrame = true;
    }

    public int compareToY(IsoMovingObject other) {
        float osy;
        if (this.sprite == null && other.sprite == null) {
            return 0;
        }
        if (this.sprite != null && other.sprite == null) {
            return -1;
        }
        if (this.sprite == null) {
            return 1;
        }
        float sy = IsoUtils.YToScreen(this.getX(), this.getY(), this.getZ(), 0);
        if ((double)sy > (double)(osy = IsoUtils.YToScreen(other.getX(), other.getY(), other.getZ(), 0))) {
            return 1;
        }
        if ((double)sy < (double)osy) {
            return -1;
        }
        return 0;
    }

    public float distToNearestCamCharacter() {
        float dist = Float.MAX_VALUE;
        for (int i = 0; i < IsoPlayer.numPlayers; ++i) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player == null) continue;
            dist = Math.min(dist, this.DistTo(player));
        }
        return dist;
    }

    public boolean isSolidForSeparate() {
        if (this instanceof IsoZombieGiblets) {
            return false;
        }
        if (this.current == null) {
            return false;
        }
        if (!this.solid) {
            return false;
        }
        return !this.isOnFloor();
    }

    public boolean isPushableForSeparate() {
        return true;
    }

    public boolean isPushedByForSeparate(IsoMovingObject other) {
        if (this instanceof IsoAnimal && (!((IsoAnimal)this).adef.collidable || ((IsoAnimal)this).getBehavior().blockMovement)) {
            return false;
        }
        if (other instanceof IsoAnimal) {
            IsoAnimal isoAnimal = (IsoAnimal)other;
            if (!isoAnimal.adef.collidable || isoAnimal.getBehavior().blockMovement) {
                return false;
            }
        }
        return true;
    }

    public void separate() {
        if (!this.isSolidForSeparate()) {
            return;
        }
        if (!this.isPushableForSeparate()) {
            return;
        }
        IsoGameCharacter thisChr = Type.tryCastTo(this, IsoGameCharacter.class);
        IsoPlayer thisPlyr = Type.tryCastTo(this, IsoPlayer.class);
        IsoZombie thisZombie = Type.tryCastTo(this, IsoZombie.class);
        for (int i = 0; i <= 8; ++i) {
            IsoGridSquare sq;
            IsoGridSquare isoGridSquare = sq = i == 8 ? this.current : this.current.getSurroundingSquares()[i];
            if (sq == null || sq.getMovingObjects().isEmpty() || sq != this.current && (pzopt.Config.EDGE_TEST_FAST && pzopt.Overrides.enabled() ? pzopt.EdgeFast.isBlockedTo(this.current, sq) : this.current.isBlockedTo(sq))) continue; // pzopt: edgeTestFast, 42.21's edge-object isBlockedTo without its megamorphic predicates
            float maxWeaponRange = thisPlyr != null && thisPlyr.getPrimaryHandItem() instanceof HandWeapon ? ((HandWeapon)thisPlyr.getPrimaryHandItem()).getMaxRange() : 0.3f;
            int size = sq.getMovingObjects().size();
            for (int n = 0; n < size; ++n) {
                IsoMovingObject obj = sq.getMovingObjects().get(n);
                if (obj == this || !obj.isSolidForSeparate() || Math.abs(this.getZ() - obj.getZ()) > 0.3f) continue;
                IsoGameCharacter objChr = Type.tryCastTo(obj, IsoGameCharacter.class);
                IsoPlayer objPlyr = Type.tryCastTo(obj, IsoPlayer.class);
                IsoZombie objZombie = Type.tryCastTo(obj, IsoZombie.class);
                float twidth = this.width + obj.width;
                Vector2 diff = pzopt.WorkerScratch.v2(tempo, pzopt.WorkerScratch.MOVING_TEMPO); // pzopt: zombie pass static audit, per thread on a frame worker
                diff.x = this.getNextX() - obj.getNextX();
                diff.y = this.getNextY() - obj.getNextY();
                float len = diff.getLength();
                if (thisChr == null || objChr == null && !(obj instanceof BaseVehicle)) {
                    if (len < twidth) {
                        CollisionManager.instance.AddContact(this, obj);
                    }
                    return;
                }
                if (objChr == null) continue;
                if (thisPlyr != null && thisPlyr.getBumpedChr() != obj && len < twidth + maxWeaponRange && (double)thisPlyr.getForwardDirection().angleBetween(diff) > 2.6179938155736564 && thisPlyr.getBeenSprintingFor() >= 70.0f && WeaponType.getWeaponType(thisPlyr) == WeaponType.SPEAR && !thisPlyr.getPrimaryHandItem().hasTag(ItemTag.FAKE_SPEAR)) {
                    thisPlyr.reportEvent("ChargeSpearConnect");
                    thisPlyr.setAttackType(AttackType.CHARGE);
                    thisPlyr.setAttackStarted(true);
                    thisPlyr.setVariable("StartedAttackWhileSprinting", true);
                    thisPlyr.setBeenSprintingFor(0.0f);
                    return;
                }
                if (len >= twidth) continue;
                boolean bump = false;
                if (thisPlyr != null && thisPlyr.getVariableFloat("WalkSpeed", 0.0f) > 0.2f && thisPlyr.runningTime > 0.5f && thisPlyr.getBumpedChr() != obj) {
                    bump = true;
                }
                if (GameClient.client && thisPlyr != null && objChr instanceof IsoPlayer && !ServerOptions.getInstance().playerBumpPlayer.getValue()) {
                    bump = false;
                }
                if (thisZombie != null && thisZombie.isReanimatedForGrappleOnly()) {
                    bump = false;
                }
                if (objZombie != null && objZombie.isReanimatedForGrappleOnly()) {
                    bump = false;
                }
                if (bump && !thisPlyr.isAttackType(AttackType.CHARGE)) {
                    boolean wasBumped;
                    boolean bl = wasBumped = !(this.isOnFloor() || thisChr.getBumpedChr() == null && (System.currentTimeMillis() - thisPlyr.getLastBump()) / 100L >= 15L && !thisPlyr.isSprinting() || objPlyr != null && objPlyr.isNpc());
                    if (wasBumped) {
                        BodyPart part;
                        ++thisChr.bumpNbr;
                        int baseChance = 10 - thisChr.bumpNbr * 3;
                        baseChance += thisChr.getPerkLevel(PerkFactory.Perks.Fitness);
                        baseChance += thisChr.getPerkLevel(PerkFactory.Perks.Strength);
                        baseChance -= thisChr.getMoodles().getMoodleLevel(MoodleType.DRUNK) * 2;
                        CharacterTraits characterTraits = thisChr.getCharacterTraits();
                        if (characterTraits.get(CharacterTrait.CLUMSY)) {
                            baseChance -= 5;
                        }
                        if (characterTraits.get(CharacterTrait.GRACEFUL)) {
                            baseChance += 5;
                        }
                        if (characterTraits.get(CharacterTrait.VERY_UNDERWEIGHT)) {
                            baseChance -= 8;
                        }
                        if (characterTraits.get(CharacterTrait.UNDERWEIGHT)) {
                            baseChance -= 4;
                        }
                        if (characterTraits.get(CharacterTrait.OBESE)) {
                            baseChance -= 8;
                        }
                        if (characterTraits.get(CharacterTrait.OVERWEIGHT)) {
                            baseChance -= 4;
                        }
                        if ((part = thisChr.getBodyDamage().getBodyPart(BodyPartType.Torso_Lower)).getAdditionalPain(true) > 20.0f) {
                            baseChance = (int)((float)baseChance - (part.getAdditionalPain(true) - 20.0f) / 20.0f);
                        }
                        baseChance = Math.min(80, baseChance);
                        if (Rand.Next(baseChance = Math.max(1, baseChance)) == 0 || thisChr.isSprinting()) {
                            thisChr.setVariable("BumpDone", false);
                            thisChr.setBumpFall(true);
                            thisChr.setVariable("TripObstacleType", "zombie");
                        }
                    } else {
                        thisChr.bumpNbr = 0;
                    }
                    thisChr.setLastBump(System.currentTimeMillis());
                    thisChr.setBumpedChr(objChr);
                    thisChr.setBumpType(this.getBumpedType(objChr));
                    boolean fromBehind = thisChr.isBehind(objChr);
                    String zombieBump = thisChr.getBumpType();
                    if (fromBehind) {
                        zombieBump = zombieBump.equals("left") ? "right" : "left";
                    }
                    objChr.setBumpType(zombieBump);
                    objChr.setHitFromBehind(fromBehind);
                    if (wasBumped | GameClient.client) {
                        thisChr.getActionContext().reportEvent("wasBumped");
                    }
                }
                if (!GameServer.server && !(this.distToNearestCamCharacter() < 60.0f)) continue;
                if (this instanceof IsoZombie) {
                    NetworkZombieAI networkAi = this.getECSComponent(NetworkZombieComponent.class).getNetworkAI();
                    networkAi.wasSeparated = true;
                }
                if (this.isPushedByForSeparate(obj)) {
                    diff.setLength((len - twidth) / 8.0f);
                    this.setNextX(this.getNextX() - diff.x);
                    this.setNextY(this.getNextY() - diff.y);
                }
                this.collideWith(obj);
            }
        }
    }

    public String getBumpedType(IsoGameCharacter bumped) {
        float comparedX = this.getX() - bumped.getX();
        float comparedY = this.getY() - bumped.getY();
        String bumpType = "left";
        if (this.getForwardIsoDirection() == IsoDirections.S || this.getForwardIsoDirection() == IsoDirections.SE || this.getForwardIsoDirection() == IsoDirections.SW) {
            bumpType = comparedX < 0.0f ? "left" : "right";
        }
        if (this.getForwardIsoDirection() == IsoDirections.N || this.getForwardIsoDirection() == IsoDirections.NE || this.getForwardIsoDirection() == IsoDirections.NW) {
            bumpType = comparedX > 0.0f ? "left" : "right";
        }
        if (this.getForwardIsoDirection() == IsoDirections.E) {
            bumpType = comparedY > 0.0f ? "left" : "right";
        }
        if (this.getForwardIsoDirection() == IsoDirections.W) {
            bumpType = comparedY < 0.0f ? "left" : "right";
        }
        return bumpType;
    }

    public float getLastX() {
        return this.lastX;
    }

    public float setLastX(float lx) {
        this.lastX = lx;
        return this.lastX;
    }

    public float getLastY() {
        return this.ly;
    }

    public float setLastY(float ly) {
        this.ly = ly;
        return this.ly;
    }

    public float getLastZ() {
        return this.lz;
    }

    public float setLastZ(float lz) {
        this.lz = lz;
        return this.lz;
    }

    public float getNextX() {
        return this.nx;
    }

    public final int getNextXi() {
        return PZMath.fastfloor(this.getNextX());
    }

    public float setNextX(float nx) {
        this.nx = nx;
        return this.nx;
    }

    public float getNextY() {
        return this.ny;
    }

    public final int getNextYi() {
        return PZMath.fastfloor(this.getNextY());
    }

    public float setNextY(float ny) {
        this.ny = ny;
        return this.ny;
    }

    protected void slideHeadAwayFromWalls(boolean instant) {
    }

    protected boolean shouldSlideHeadAwayFromWalls() {
        return true;
    }

    protected void slideAwayFromWalls(float radius, boolean instant, boolean includePolyCollisions) {
    }

    public void slideAwayToCollisionPos(float collNewPosX, float collNewPosY, boolean instant) {
        float x1 = this.getX();
        float y1 = this.getY();
        float originalX2 = this.getNextX();
        float originalY2 = this.getNextY();
        float originalVX = originalX2 - x1;
        float originalVY = originalY2 - y1;
        int originalVXsign = PZMath.sign(originalVX);
        int originalVYsign = PZMath.sign(originalVY);
        float newPosX = collNewPosX;
        float newPosY = collNewPosY;
        float collidedVX = collNewPosX - x1;
        float collidedVY = collNewPosY - y1;
        int collidedVXsign = PZMath.sign(collidedVX);
        int collidedVYsign = PZMath.sign(collidedVY);
        if (originalVXsign == collidedVXsign) {
            float originalVXabs = originalVX * (float)originalVXsign;
            float collidedVXabs = collidedVX * (float)collidedVXsign;
            newPosX = x1 + PZMath.max(originalVXabs, collidedVXabs) * (float)originalVXsign;
        }
        if (originalVYsign == collidedVYsign) {
            float originalVYabs = originalVY * (float)originalVYsign;
            float collidedVYabs = collidedVY * (float)collidedVYsign;
            newPosY = y1 + PZMath.max(originalVYabs, collidedVYabs) * (float)originalVYsign;
        }
        this.setNextX(newPosX);
        this.setNextY(newPosY);
        if (instant) {
            this.setX(newPosX);
            this.setY(newPosY);
        }
    }

    private boolean DoCollide(int favour) {
        IsoGameCharacter chr = Type.tryCastTo(this, IsoGameCharacter.class);
        this.setCurrentSquareFromPosition(this.getNextX(), this.getNextY());
        if (chr != null && chr.isRagdollSimulationActive()) {
            return false;
        }
        if (this.current != null) {
            if (!this.current.TreatAsSolidFloor()) {
                this.setCurrentSquareFromPosition(this.getNextX(), this.getNextY(), this.getZ());
            }
            if (this.current == null) {
                return false;
            }
            this.setCurrentSquareFromPosition(this.getNextX(), this.getNextY(), this.getZ());
        }
        if (this.current != this.last && this.last != null && this.current != null) {
            if (chr != null && chr.getCurrentState() != null && chr.getCurrentState().isIgnoreCollide(chr, this.last.x, this.last.y, this.last.z, this.current.x, this.current.y, this.current.z)) {
                return false;
            }
            if (this == IsoCamera.getCameraCharacter()) {
                IsoWorld.instance.currentCell.lightUpdateCount = 10;
            }
            int dx = this.current.getX() - this.last.getX();
            int dy = this.current.getY() - this.last.getY();
            int dz = this.current.getZ() - this.last.getZ();
            boolean bCollide = false;
            if (this.last.testCollideAdjacent(this, dx, dy, dz) || this.current == null) {
                bCollide = true;
            }
            if (bCollide) {
                if (this.last.getX() < this.current.getX()) {
                    this.collidedE = true;
                }
                if (this.last.getX() > this.current.getX()) {
                    this.collidedW = true;
                }
                if (this.last.getY() < this.current.getY()) {
                    this.collidedS = true;
                }
                if (this.last.getY() > this.current.getY()) {
                    this.collidedN = true;
                }
                this.setCurrent(this.last);
                pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_FENCE); // pzopt: postupdateParallel — the fence climb / thump target after a collision stay on the game thread
                this.checkBreakHoppable();
                this.checkHitHoppable();
                this.checkBreakBendableFence(this.current);
                if (favour == 2) {
                    if ((this.collidedS || this.collidedN) && (this.collidedE || this.collidedW)) {
                        this.collidedS = false;
                        this.collidedN = false;
                    }
                } else if (favour == 1 && (this.collidedS || this.collidedN) && (this.collidedE || this.collidedW)) {
                    this.collidedW = false;
                    this.collidedE = false;
                }
                this.Collided();
                return true;
            }
        } else if (this.getNextX() != this.getLastX() || this.getNextY() != this.getLastY()) {
            PathFindBehavior2 pfb2;
            if (this instanceof IsoZombie && Core.gameMode.equals("Tutorial")) {
                return true;
            }
            if (this.current == null) {
                if (this.getNextX() < this.getLastX()) {
                    this.collidedW = true;
                }
                if (this.getNextX() > this.getLastX()) {
                    this.collidedE = true;
                }
                if (this.getNextY() < this.getLastY()) {
                    this.collidedN = true;
                }
                if (this.getNextY() > this.getLastY()) {
                    this.collidedS = true;
                }
                this.setNextX(this.getLastX());
                this.setNextY(this.getLastY());
                this.setCurrent(this.last);
                this.Collided();
                return true;
            }
            if (chr != null && chr.getPath2() != null && PZMath.fastfloor((pfb2 = chr.getPathFindBehavior2()).getTargetX()) == PZMath.fastfloor(this.getX()) && PZMath.fastfloor(pfb2.getTargetY()) == PZMath.fastfloor(this.getY()) && PZMath.fastfloor(pfb2.getTargetZ()) == PZMath.fastfloor(this.getZ())) {
                return false;
            }
            if (chr != null && chr.isSittingOnFurniture()) {
                return false;
            }
            IsoGridSquare feeler = this.getFeelerTile(this.feelersize);
            if (chr != null) {
                if (chr.isClimbing()) {
                    feeler = this.current;
                }
                if (feeler != null && feeler != this.current && chr.getPath2() != null && !chr.getPath2().crossesSquare(feeler.x, feeler.y, feeler.z)) {
                    feeler = this.current;
                }
            }
            if (feeler != null && feeler != this.current && this.current != null) {
                if (chr != null && chr.getCurrentState() != null && chr.getCurrentState().isIgnoreCollide(chr, this.current.x, this.current.y, this.current.z, feeler.x, feeler.y, feeler.z)) {
                    return false;
                }
                if (this.current.testCollideAdjacent(this, feeler.getX() - this.current.getX(), feeler.getY() - this.current.getY(), feeler.getZ() - this.current.getZ())) {
                    if (this.last != null) {
                        if (this.current.getX() < feeler.getX()) {
                            this.collidedE = true;
                        }
                        if (this.current.getX() > feeler.getX()) {
                            this.collidedW = true;
                        }
                        if (this.current.getY() < feeler.getY()) {
                            this.collidedS = true;
                        }
                        if (this.current.getY() > feeler.getY()) {
                            this.collidedN = true;
                        }
                        pzopt.PostupdateBatch.hazard(pzopt.PostupdateBatch.H_FENCE); // pzopt: postupdateParallel — the fence climb / thump target after a collision stay on the game thread
                        this.checkBreakHoppable();
                        this.checkHitHoppable();
                        this.checkBreakBendableFence(this.current);
                        if (favour == 2 && (this.collidedS || this.collidedN) && (this.collidedE || this.collidedW)) {
                            this.collidedS = false;
                            this.collidedN = false;
                        }
                        if (favour == 1 && (this.collidedS || this.collidedN) && (this.collidedE || this.collidedW)) {
                            this.collidedW = false;
                            this.collidedE = false;
                        }
                    }
                    this.Collided();
                    return true;
                }
            }
        }
        return false;
    }

    private void checkHitHoppableAnimal(IsoAnimal animal) {
        IsoGridSquare e;
        IsoGridSquare s;
        if (!animal.adef.canClimbFences) {
            return;
        }
        if (animal.isCurrentState(AnimalAttackState.instance()) || animal.isCurrentState(AnimalClimbOverFenceState.instance())) {
            return;
        }
        if (this.collidedW && !this.collidedN && !this.collidedS && this.last.has(IsoFlagType.HoppableW)) {
            animal.climbOverFence(IsoDirections.W);
        }
        if (this.collidedN && !this.collidedE && !this.collidedW && this.last.has(IsoFlagType.HoppableN)) {
            animal.climbOverFence(IsoDirections.N);
        }
        if (this.collidedS && !this.collidedE && !this.collidedW && (s = this.last.getAdjacentSquare(IsoDirections.S)) != null && s.has(IsoFlagType.HoppableN)) {
            animal.climbOverFence(IsoDirections.S);
        }
        if (this.collidedE && !this.collidedN && !this.collidedS && (e = this.last.getAdjacentSquare(IsoDirections.E)) != null && e.has(IsoFlagType.HoppableW)) {
            animal.climbOverFence(IsoDirections.E);
        }
    }

    private void checkHitHoppable() {
        IsoGridSquare e;
        IsoGridSquare s;
        IsoMovingObject isoMovingObject = this;
        if (isoMovingObject instanceof IsoAnimal) {
            IsoAnimal animal = (IsoAnimal)isoMovingObject;
            this.checkHitHoppableAnimal(animal);
            return;
        }
        IsoZombie zombie = Type.tryCastTo(this, IsoZombie.class);
        if (zombie == null || zombie.crawling) {
            return;
        }
        if (zombie.isCurrentState(AttackState.instance()) || zombie.isCurrentState(StaggerBackState.instance()) || zombie.isCurrentState(ClimbOverFenceState.instance()) || zombie.isCurrentState(ClimbThroughWindowState.instance())) {
            return;
        }
        if (this.collidedW && !this.collidedN && !this.collidedS) {
            IsoGridSquare w = this.last.getAdjacentSquare(IsoDirections.W);
            if (!(!this.last.has(IsoFlagType.HoppableW) || this.last.HasStairsNorth() || w != null && w.HasStairsNorth())) {
                zombie.climbOverFence(IsoDirections.W);
            }
        }
        if (this.collidedN && !this.collidedE && !this.collidedW) {
            IsoGridSquare n = this.last.getAdjacentSquare(IsoDirections.N);
            if (!(!this.last.has(IsoFlagType.HoppableN) || this.last.HasStairsWest() || n != null && n.HasStairsWest())) {
                zombie.climbOverFence(IsoDirections.N);
            }
        }
        if (this.collidedS && !this.collidedE && !this.collidedW && (s = this.last.getAdjacentSquare(IsoDirections.S)) != null && s.has(IsoFlagType.HoppableN) && !this.last.HasStairsWest() && !s.HasStairsWest()) {
            zombie.climbOverFence(IsoDirections.S);
        }
        if (this.collidedE && !this.collidedN && !this.collidedS && (e = this.last.getAdjacentSquare(IsoDirections.E)) != null && e.has(IsoFlagType.HoppableW) && !this.last.HasStairsNorth() && !e.HasStairsNorth()) {
            zombie.climbOverFence(IsoDirections.E);
        }
    }

    private void checkBreakBendableFence(IsoGridSquare square) {
        IsoMovingObject isoMovingObject = this;
        if (!(isoMovingObject instanceof IsoZombie)) {
            return;
        }
        IsoZombie zombie = (IsoZombie)isoMovingObject;
        if (zombie.isCurrentState(AttackState.instance()) || zombie.isCurrentState(StaggerBackState.instance()) || zombie.isCurrentState(CrawlingZombieTurnState.instance())) {
            return;
        }
        IsoDirections dir = null;
        if (this.collidedW && !this.collidedN && !this.collidedS) {
            dir = IsoDirections.W;
        }
        if (this.collidedN && !this.collidedE && !this.collidedW) {
            dir = IsoDirections.N;
        }
        if (this.collidedS && !this.collidedE && !this.collidedW) {
            dir = IsoDirections.S;
        }
        if (this.collidedE && !this.collidedN && !this.collidedS) {
            dir = IsoDirections.E;
        }
        if (dir == null) {
            return;
        }
        IsoObject bend = this.last.getBendableTo(this.last.getAdjacentSquare(dir));
        IsoThumpable thumpable = Type.tryCastTo(bend, IsoThumpable.class);
        if (BentFences.getInstance().isEnabled()) {
            if (thumpable != null && !thumpable.isThumpable()) {
                zombie.setThumpTarget(thumpable);
            } else if (bend != null && bend.getThumpableFor(zombie) != null) {
                zombie.setThumpTarget(bend);
            }
        }
    }

    private void checkBreakHoppable() {
        IsoZombie zombie = Type.tryCastTo(this, IsoZombie.class);
        if (zombie == null || !zombie.crawling) {
            return;
        }
        if (zombie.isCurrentState(AttackState.instance()) || zombie.isCurrentState(StaggerBackState.instance()) || zombie.isCurrentState(CrawlingZombieTurnState.instance())) {
            return;
        }
        IsoDirections dir = null;
        if (this.collidedW && !this.collidedN && !this.collidedS) {
            dir = IsoDirections.W;
        }
        if (this.collidedN && !this.collidedE && !this.collidedW) {
            dir = IsoDirections.N;
        }
        if (this.collidedS && !this.collidedE && !this.collidedW) {
            dir = IsoDirections.S;
        }
        if (this.collidedE && !this.collidedN && !this.collidedS) {
            dir = IsoDirections.E;
        }
        if (dir == null) {
            return;
        }
        IsoObject hop = this.last.getHoppableTo(this.last.getAdjacentSquare(dir));
        IsoThumpable thumpable = Type.tryCastTo(hop, IsoThumpable.class);
        if (thumpable != null && !thumpable.isThumpable()) {
            zombie.setThumpTarget(thumpable);
        } else if (hop != null && hop.getThumpableFor(zombie) != null) {
            zombie.setThumpTarget(hop);
        }
    }

    private void checkHitWall() {
        IsoObject isoObject;
        if (!(this.collidedN || this.collidedS || this.collidedE || this.collidedW)) {
            return;
        }
        if (this.current == null) {
            return;
        }
        IsoMovingObject isoMovingObject = this;
        if (!(isoMovingObject instanceof IsoPlayer)) {
            return;
        }
        IsoPlayer player = (IsoPlayer)isoMovingObject;
        if (!StringUtils.isNullOrEmpty(this.getCollideType())) {
            return;
        }
        boolean valid = false;
        int wallType = this.current.getWallType();
        if (this.isCollidedWithDoor() && (isoObject = this.getCollidedObject()) instanceof IsoDoor) {
            IsoDoor door = (IsoDoor)isoObject;
            if (door.north && (this.collidedN || this.collidedS) || !door.north && (this.collidedE || this.collidedW)) {
                valid = true;
            }
        }
        if ((wallType & 1) != 0 && this.collidedN && this.getDir() == IsoDirections.N) {
            valid = true;
        }
        if ((wallType & 2) != 0 && this.collidedS && this.getDir() == IsoDirections.S) {
            valid = true;
        }
        if ((wallType & 4) != 0 && this.collidedW && this.getDir() == IsoDirections.W) {
            valid = true;
        }
        if ((wallType & 8) != 0 && this.collidedE && this.getDir() == IsoDirections.E) {
            valid = true;
        }
        if (this.checkVaultOver()) {
            valid = false;
        }
        if (valid && player.isSprinting() && player.isLocalPlayer()) {
            this.setCollideType("wall");
            player.getActionContext().reportEvent("collideWithWall");
            this.lastCollideTime = 70.0f;
        }
    }

    private boolean checkVaultOver() {
        IsoPlayer player = (IsoPlayer)this;
        if (player.isCurrentState(ClimbOverFenceState.instance()) || player.isIgnoreAutoVault()) {
            return false;
        }
        if (!(player.IsRunning() || player.isSprinting() || player.isRemoteAndHasObstacleOnPath())) {
            return false;
        }
        IsoDirections dir = this.getDir();
        IsoGridSquare se = this.current.getAdjacentSquare(IsoDirections.SE);
        if (dir == IsoDirections.SE && se != null && se.has(IsoFlagType.HoppableN) && se.has(IsoFlagType.HoppableW)) {
            return false;
        }
        IsoGridSquare feeler = this.current;
        if (this.collidedS) {
            feeler = this.current.getAdjacentSquare(IsoDirections.S);
        } else if (this.collidedE) {
            feeler = this.current.getAdjacentSquare(IsoDirections.E);
        }
        if (feeler == null) {
            return false;
        }
        boolean vaultOver = false;
        if (this.current.getProperties().has(IsoFlagType.HoppableN) && this.collidedN && !this.collidedW && !this.collidedE && (dir == IsoDirections.NW || dir == IsoDirections.N || dir == IsoDirections.NE)) {
            dir = IsoDirections.N;
            vaultOver = true;
        }
        if (feeler.getProperties().has(IsoFlagType.HoppableN) && this.collidedS && !this.collidedW && !this.collidedE && (dir == IsoDirections.SW || dir == IsoDirections.S || dir == IsoDirections.SE)) {
            dir = IsoDirections.S;
            vaultOver = true;
        }
        if (this.current.getProperties().has(IsoFlagType.HoppableW) && this.collidedW && !this.collidedN && !this.collidedS && (dir == IsoDirections.NW || dir == IsoDirections.W || dir == IsoDirections.SW)) {
            dir = IsoDirections.W;
            vaultOver = true;
        }
        if (feeler.getProperties().has(IsoFlagType.HoppableW) && this.collidedE && !this.collidedN && !this.collidedS && (dir == IsoDirections.NE || dir == IsoDirections.E || dir == IsoDirections.SE)) {
            dir = IsoDirections.E;
            vaultOver = true;
        }
        if (vaultOver && !this.current.isPlayerAbleToHopWallTo(dir, feeler)) {
            return false;
        }
        if (vaultOver && player.isSafeToClimbOver(dir)) {
            ClimbOverFenceState.instance().setParams((IsoGameCharacter)player, dir);
            player.getActionContext().reportEvent("EventClimbFence");
            return true;
        }
        return false;
    }

    public void setMovingSquareNow() {
        this.setMovingSquare(this.current);
    }

    public IsoGridSquare getFeelerTile(float dist) {
        Vector2 vec = pzopt.PostupdateBatch.computing() ? pzopt.PostupdateBatch.tempo() : pzopt.WorkerScratch.v2(tempo, pzopt.WorkerScratch.MOVING_TEMPO); // pzopt: postupdateParallel — the static scratch is per thread in a movement task; zombie pass static audit — and on any frame worker (tryThump from a batched update)
        vec.x = this.getNextX() - this.getLastX();
        vec.y = this.getNextY() - this.getLastY();
        vec.setLength(dist);
        return this.getCell().getGridSquare(PZMath.fastfloor(this.getX() + vec.x), PZMath.fastfloor(this.getY() + vec.y), PZMath.fastfloor(this.getZ()));
    }

    public void DoCollideNorS() {
        this.setNextY(this.getLastY());
    }

    public void DoCollideWorE() {
        this.setNextX(this.getLastX());
    }

    public int getTimeSinceZombieAttack() {
        return this.timeSinceZombieAttack;
    }

    public void setTimeSinceZombieAttack(int timeSinceZombieAttack) {
        this.timeSinceZombieAttack = timeSinceZombieAttack;
    }

    public boolean isCollidedE() {
        return this.collidedE;
    }

    public void setCollidedE(boolean collidedE) {
        this.collidedE = collidedE;
    }

    public boolean isCollidedN() {
        return this.collidedN;
    }

    public void setCollidedN(boolean collidedN) {
        this.collidedN = collidedN;
    }

    public IsoObject getCollidedObject() {
        return this.collidedObject;
    }

    public void setCollidedObject(IsoObject collidedObject) {
        this.collidedObject = collidedObject;
    }

    public boolean isCollidedS() {
        return this.collidedS;
    }

    public void setCollidedS(boolean collidedS) {
        this.collidedS = collidedS;
    }

    public boolean isCollidedThisFrame() {
        return this.collidedThisFrame;
    }

    public void setCollidedThisFrame(boolean collidedThisFrame) {
        this.collidedThisFrame = collidedThisFrame;
    }

    public boolean isCollidedW() {
        return this.collidedW;
    }

    public void setCollidedW(boolean collidedW) {
        this.collidedW = collidedW;
    }

    public boolean isCollidedWithDoor() {
        return this.collidedWithDoor;
    }

    public void setCollidedWithDoor(boolean collidedWithDoor) {
        this.collidedWithDoor = collidedWithDoor;
    }

    public boolean isCollidedWithVehicle() {
        return this.collidedWithVehicle;
    }

    public IsoGridSquare getCurrentSquare() {
        return this.current;
    }

    public Zone getCurrentZone() {
        if (this.current != null) {
            return this.current.getZone();
        }
        return null;
    }

    public void setCurrent(IsoGridSquare current) {
        this.current = current;
    }

    public void setCurrentSquare(IsoGridSquare square) {
        this.setCurrent(square);
    }

    public void setCurrentSquareFromPosition() {
        float x1 = this.getX();
        float y1 = this.getY();
        float z1 = this.getZ();
        this.setCurrentSquareFromPosition(x1, y1, z1);
    }

    public void setCurrentSquareFromPosition(float x1, float y1) {
        float z1 = this.getZ();
        this.setCurrentSquareFromPosition(x1, y1, z1);
    }

    public void setCurrentSquareFromPosition(float x1, float y1, float z1) {
        IsoGridSquare current = this.getCell().getGridSquare(x1, y1, z1);
        if (current == null) {
            for (int n = PZMath.fastfloor(z1); n >= 0 && (current = this.getCell().getGridSquare(x1, y1, (double)n)) == null; --n) {
            }
        }
        this.setCurrent(current);
    }

    @Override
    public boolean isDestroyed() {
        return this.destroyed;
    }

    public void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
    }

    public boolean isFirstUpdate() {
        return this.firstUpdate;
    }

    public void setFirstUpdate(boolean firstUpdate) {
        this.firstUpdate = firstUpdate;
    }

    public Vector2 getHitDir() {
        return this.hitDir;
    }

    public void setHitDir(Vector2 hitDir) {
        this.hitDir.set(hitDir);
    }

    public float getImpulsex() {
        return this.impulsex;
    }

    public void setImpulsex(float impulsex) {
        this.impulsex = impulsex;
    }

    public float getImpulsey() {
        return this.impulsey;
    }

    public void setImpulsey(float impulsey) {
        this.impulsey = impulsey;
    }

    public float getLimpulsex() {
        return this.limpulsex;
    }

    public void setLimpulsex(float limpulsex) {
        this.limpulsex = limpulsex;
    }

    public float getLimpulsey() {
        return this.limpulsey;
    }

    public void setLimpulsey(float limpulsey) {
        this.limpulsey = limpulsey;
    }

    public float getHitForce() {
        return this.hitForce;
    }

    public void setHitForce(float hitForce) {
        this.hitForce = hitForce;
    }

    public float getHitFromAngle() {
        return this.hitFromAngle;
    }

    public void setHitFromAngle(float hitFromAngle) {
        this.hitFromAngle = hitFromAngle;
    }

    public IsoGridSquare getLastSquare() {
        return this.last;
    }

    public void setLast(IsoGridSquare last) {
        this.last = last;
    }

    public boolean getNoDamage() {
        return this.noDamage;
    }

    public void setNoDamage(boolean dmg) {
        this.noDamage = dmg;
    }

    public boolean isSolid() {
        return this.solid;
    }

    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    public float getStateEventDelayTimer() {
        return this.stateEventDelayTimer;
    }

    public void setStateEventDelayTimer(float stateEventDelayTimer) {
        this.stateEventDelayTimer = stateEventDelayTimer;
    }

    public float getWidth() {
        return this.width;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public boolean isbAltCollide() {
        return this.altCollide;
    }

    public void setbAltCollide(boolean altCollide) {
        this.altCollide = altCollide;
    }

    public boolean isShootable() {
        return this.shootable;
    }

    public void setShootable(boolean shootable) {
        this.shootable = shootable;
    }

    public IsoZombie getLastTargettedBy() {
        return this.lastTargettedBy;
    }

    public void setLastTargettedBy(IsoZombie lastTargettedBy) {
        this.lastTargettedBy = lastTargettedBy;
    }

    public boolean isCollidable() {
        return this.collidable;
    }

    public void setCollidable(boolean collidable) {
        this.collidable = collidable;
    }

    public Vector2 getMovementLastFrame() {
        return this.movementLastFrame;
    }

    public void setMovementLastFrame(Vector2 movementLastFrame) {
        this.movementLastFrame = movementLastFrame;
    }

    public float getFeelersize() {
        return this.feelersize;
    }

    public void setFeelersize(float feelersize) {
        this.feelersize = feelersize;
    }

    public boolean isOnFloor() {
        return this.onFloor;
    }

    public void setOnFloor(boolean onFloor) {
        this.onFloor = onFloor;
    }

    public final boolean isStanding() {
        return !this.isProne();
    }

    public boolean isProne() {
        return this.isOnFloor();
    }

    public boolean isGettingUp() {
        return false;
    }

    public boolean isCrawling() {
        return false;
    }

    public void Despawn() {
    }

    public boolean isCloseKilled() {
        return this.closeKilled;
    }

    public void setCloseKilled(boolean closeKilled) {
        this.closeKilled = closeKilled;
    }

    @Override
    public Vector2 getFacingPosition(Vector2 pos) {
        pos.set(this.getX(), this.getY());
        return pos;
    }

    private boolean isInLoadedArea(int x, int y) {
        if (GameServer.server) {
            for (int i = 0; i < ServerMap.instance.loadedCells.size(); ++i) {
                ServerMap.ServerCell serverCell = ServerMap.instance.loadedCells.get(i);
                if (x < serverCell.wx * 64 || x >= (serverCell.wx + 1) * 64 || y < serverCell.wy * 64 || y >= (serverCell.wy + 1) * 64) continue;
                return true;
            }
        } else {
            for (int i = 0; i < IsoPlayer.numPlayers; ++i) {
                IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[i];
                if (cm.ignore || x < cm.getWorldXMinTiles() || x >= cm.getWorldXMaxTiles() || y < cm.getWorldYMinTiles() || y >= cm.getWorldYMaxTiles()) continue;
                return true;
            }
        }
        return false;
    }

    public boolean isCollided() {
        return !StringUtils.isNullOrWhitespace(this.getCollideType());
    }

    public String getCollideType() {
        return this.collideType;
    }

    public void setCollideType(String collideType) {
        this.collideType = collideType;
    }

    public float getLastCollideTime() {
        return this.lastCollideTime;
    }

    public void setLastCollideTime(float lastCollideTime) {
        this.lastCollideTime = lastCollideTime;
    }

    public ArrayList<IsoZombie> getEatingZombies() {
        return this.eatingZombies;
    }

    public void setEatingZombies(ArrayList<IsoZombie> zeds) {
        this.eatingZombies.clear();
        this.eatingZombies.addAll(zeds);
    }

    public boolean isEatingOther(IsoMovingObject other) {
        if (other == null) {
            return false;
        }
        return other.eatingZombies.contains(this);
    }

    public float getDistanceSq(IsoMovingObject other) {
        float x = this.getX() - other.getX();
        float y = this.getY() - other.getY();
        x *= x;
        y *= y;
        return x + y;
    }

    public UpdateSchedulerSimulationLevel getMinimumSimulationLevel() {
        return PZOptional.ifPresent(this.tryGetECSComponent(StateMachineComponent.class), UpdateSchedulerSimulationLevel.minimum(), StateMachineComponent::getMinimumSimulationLevel);
    }

    public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel simulationLevel) {
        this.currentSimulationLevel = simulationLevel;
    }

    public UpdateSchedulerSimulationLevel getCurrentSimulationLevel() {
        return this.currentSimulationLevel;
    }

    public boolean isTransparentWallTo(IsoMovingObject target) {
        return target != null && target.getSquare() != null && this.getSquare() != null && target.getSquare().getTransparentWallTo(this.getSquare()) != null;
    }

    @Override
    public boolean isExistInTheWorld() {
        if (this.square != null) {
            return this.square.getMovingObjects().contains(this);
        }
        return false;
    }

    public boolean shouldIgnoreCollisionWithSquare(IsoGridSquare square) {
        return false;
    }

    public int getSurroundingThumpers() {
        if (this.getCurrentSquare() != null) {
            return (int)this.getCurrentSquare().getCell().getZombieList().stream().filter(z -> IsoUtils.DistanceTo(this.getX(), this.getY(), z.getX(), z.getY()) < 1.5f).count();
        }
        return 1;
    }

    static {
        tempo = new Vector2();
    }

    private static final class L_postUpdate {
        private static final Vector2f vector2f = new Vector2f();

        private L_postUpdate() {
        }
    }
}

