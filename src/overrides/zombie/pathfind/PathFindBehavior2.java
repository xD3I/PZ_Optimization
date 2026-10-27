/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  gnu.trove.TFloatCollection
 *  gnu.trove.list.array.TFloatArrayList
 *  org.joml.Vector2f
 *  org.joml.Vector3f
 *  org.joml.Vector3fc
 *  se.krka.kahlua.vm.KahluaTable
 *  zombie.GameTime
 *  zombie.SandboxOptions
 *  zombie.UsedFromLua
 *  zombie.ai.State
 *  zombie.ai.WalkingOnTheSpot
 *  zombie.ai.astar.AStarPathFinder$PathFindProgress
 *  zombie.ai.astar.Mover
 *  zombie.ai.states.ClimbOverFenceState
 *  zombie.ai.states.ClimbThroughWindowState
 *  zombie.ai.states.CollideWithWallState
 *  zombie.ai.states.LungeNetworkState
 *  zombie.ai.states.PlayerSitOnFurnitureState
 *  zombie.ai.states.PlayerStrafeState
 *  zombie.ai.states.WalkTowardState
 *  zombie.ai.states.ZombieGetDownState
 *  zombie.characters.IsoGameCharacter
 *  zombie.characters.IsoPlayer
 *  zombie.characters.IsoZombie
 *  zombie.characters.animals.AnimalDefinitions
 *  zombie.characters.animals.IsoAnimal
 *  zombie.core.Core
 *  zombie.core.math.PZMath
 *  zombie.core.skinnedmodel.animation.AnimationPlayer
 *  zombie.debug.DebugOptions
 *  zombie.debug.DebugType
 *  zombie.debug.LineDrawer
 *  zombie.iso.IsoCell
 *  zombie.iso.IsoDirections
 *  zombie.iso.IsoGridSquare
 *  zombie.iso.IsoMovingObject
 *  zombie.iso.IsoObject
 *  zombie.iso.IsoUtils
 *  zombie.iso.IsoWorld
 *  zombie.iso.LosUtil
 *  zombie.iso.LosUtil$TestResults
 *  zombie.iso.SpriteDetails.IsoFlagType
 *  zombie.iso.Vector2
 *  zombie.iso.Vector2ObjectPool
 *  zombie.iso.objects.IsoDeadBody
 *  zombie.iso.objects.IsoDoor
 *  zombie.iso.objects.IsoThumpable
 *  zombie.iso.objects.IsoWindow
 *  zombie.iso.objects.IsoWindowFrame
 *  zombie.network.GameClient
 *  zombie.network.GameServer
 *  zombie.pathfind.IPathfinder
 *  zombie.pathfind.PathFindRequest
 *  zombie.pathfind.PolygonalMap2
 *  zombie.pathfind.nativeCode.PathFindRequest
 *  zombie.pathfind.nativeCode.PathfindNative
 *  zombie.popman.ObjectPool
 *  zombie.scripting.objects.VehicleScript
 *  zombie.scripting.objects.VehicleScript$Area
 *  zombie.scripting.objects.VehicleScript$Position
 *  zombie.seating.SeatingManager
 *  zombie.util.Type
 *  zombie.vehicles.BaseVehicle
 *  zombie.vehicles.BaseVehicle$Vector3fObjectPool
 *  zombie.vehicles.VehiclePart
 */
package zombie.pathfind;

import gnu.trove.TFloatCollection;
import gnu.trove.list.array.TFloatArrayList;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.ai.WalkingOnTheSpot;
import zombie.ai.astar.AStarPathFinder;
import zombie.ai.astar.Mover;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.CollideWithWallState;
import zombie.ai.states.LungeNetworkState;
import zombie.ai.states.PlayerSitOnFurnitureState;
import zombie.ai.states.PlayerStrafeState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieGetDownState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.Vector2;
import zombie.iso.Vector2ObjectPool;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.pathfind.IPathfinder;
import zombie.pathfind.Path;
import zombie.pathfind.PathNode;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathFindRequest;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.ObjectPool;
import zombie.scripting.objects.VehicleScript;
import zombie.seating.SeatingManager;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

@UsedFromLua
public final class PathFindBehavior2
implements IPathfinder {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.pathfind.PathFindBehavior2");
    }

    private static final Vector2 tempVector2 = new Vector2();
    private static final Vector2 tempVector2_2 = new Vector2();
    private static final Vector3f tempVector3f_1 = new Vector3f();
    private static final PointOnPath pointOnPath = new PointOnPath();
    public boolean pathNextIsSet;
    public float pathNextX;
    public float pathNextY;
    private final IsoGameCharacter chr;
    private float startX;
    private float startY;
    private float startZ;
    private float targetX;
    private float targetY;
    private float targetZ;
    private final TFloatArrayList targetXyz = new TFloatArrayList();
    private final Path path = new Path();
    private int pathIndex;
    private boolean isCancel = true;
    private boolean startedMoving;
    public boolean stopping;
    private boolean turningToObstacle;
    public final WalkingOnTheSpot walkingOnTheSpot = new WalkingOnTheSpot();
    private final ArrayList<DebugPt> actualPos = new ArrayList();
    private static final ObjectPool<DebugPt> actualPool = new ObjectPool(DebugPt::new, "PathFindBehavior2.actualPool");
    private Goal goal = Goal.None;
    private IsoGameCharacter goalCharacter;
    private IsoObject goalSitOnFurnitureObject;
    private boolean goalSitOnFurnitureAnySpriteGridObject;
    private BaseVehicle goalVehicle;
    private String goalVehicleArea;
    private int goalVehicleSeat;

    public PathFindBehavior2(IsoGameCharacter chr) {
        this.chr = chr;
    }

    public boolean isGoalNone() {
        return this.goal == Goal.None;
    }

    public boolean isGoalCharacter() {
        return this.goal == Goal.Character;
    }

    public boolean isGoalLocation() {
        return this.goal == Goal.Location;
    }

    public boolean isGoalSound() {
        return this.goal == Goal.Sound;
    }

    public boolean isGoalSitOnFurniture() {
        return this.goal == Goal.SitOnFurniture;
    }

    public IsoObject getGoalSitOnFurnitureObject() {
        return this.goalSitOnFurnitureObject;
    }

    public boolean isGoalVehicleAdjacent() {
        return this.goal == Goal.VehicleAdjacent;
    }

    public boolean isGoalVehicleArea() {
        return this.goal == Goal.VehicleArea;
    }

    public boolean isGoalVehicleSeat() {
        return this.goal == Goal.VehicleSeat;
    }

    public void reset() {
        this.startX = this.chr.getX();
        this.startY = this.chr.getY();
        this.startZ = this.chr.getZ();
        this.targetX = this.startX;
        this.targetY = this.startY;
        this.targetZ = this.startZ;
        this.targetXyz.resetQuick();
        this.pathIndex = 0;
        this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.notrunning;
        this.walkingOnTheSpot.reset(this.startX, this.startY);
    }

    public void pathToCharacter(IsoGameCharacter target) {
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.Character;
        this.goalCharacter = target;
        if (target.getVehicle() != null) {
            Vector3f v = target.getVehicle().chooseBestAttackPosition(target, this.chr, pzopt.UpdateBatch.pathScratch3()); // pzopt: entityUpdateParallel, this thread's tempVector3f_1 — WalkTowardState calls this from every chasing zombie's update, so batch tasks shared the static
            if (v != null) {
                this.setData(v.x, v.y, PZMath.fastfloor((float)target.getVehicle().getZ()));
                return;
            }
            this.setData(target.getVehicle().getX(), target.getVehicle().getY(), PZMath.fastfloor((float)target.getVehicle().getZ()));
            if (this.chr.DistToSquared((IsoMovingObject)target.getVehicle()) < 100.0f) {
                IsoGameCharacter isoGameCharacter = this.chr;
                if (isoGameCharacter instanceof IsoZombie) {
                    IsoZombie zombie = (IsoZombie)isoGameCharacter;
                    zombie.allowRepathDelay = 100.0f;
                }
                this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.failed;
            }
        }
        if (target.isSittingOnFurniture() && (target.getSquare().isSolid() || target.getSquare().isSolidTrans())) {
            IsoDirections dir = target.getSitOnFurnitureDirection();
            int x = PZMath.fastfloor((float)target.getX());
            int y = PZMath.fastfloor((float)target.getY());
            int z = PZMath.fastfloor((float)target.getZ());
            float radius = 0.3f;
            switch (dir) {
                case N: {
                    IsoGridSquare sqN = IsoWorld.instance.getCell().getGridSquare((double)((float)x + 0.5f), (double)((float)y - 0.3f), (double)z);
                    if (!this.isGoodChairAdjacentSquare(target.getSquare(), sqN)) break;
                    this.setData((float)x + 0.5f, (float)y - 0.3f, z);
                    return;
                }
                case S: {
                    IsoGridSquare sqS = IsoWorld.instance.getCell().getGridSquare((double)((float)x + 0.5f), (double)((float)(y + 1) + 0.3f), (double)z);
                    if (!this.isGoodChairAdjacentSquare(target.getSquare(), sqS)) break;
                    this.setData((float)x + 0.5f, (float)(y + 1) + 0.3f, z);
                    return;
                }
                case W: {
                    IsoGridSquare sqW = IsoWorld.instance.getCell().getGridSquare((double)((float)x - 0.3f), (double)((float)y + 0.5f), (double)z);
                    if (!this.isGoodChairAdjacentSquare(target.getSquare(), sqW)) break;
                    this.setData((float)x - 0.3f, (float)y + 0.5f, z);
                    return;
                }
                case E: {
                    IsoGridSquare sqE = IsoWorld.instance.getCell().getGridSquare((double)((float)(x + 1) + 0.3f), (double)((float)y + 0.5f), (double)z);
                    if (!this.isGoodChairAdjacentSquare(target.getSquare(), sqE)) break;
                    this.setData((float)(x + 1) + 0.3f, (float)y + 0.5f, z);
                    return;
                }
                default: {
                    DebugType.General.warn((Object)"unhandled sitting direction");
                }
            }
            ArrayList<IsoGridSquare> testSquares = new ArrayList<IsoGridSquare>();
            for (int i = 0; i < 8; ++i) {
                if (IsoDirections.fromIndex((int)i) == dir) continue;
                IsoGridSquare testSquare = target.getSquare().getAdjacentSquare(IsoDirections.fromIndex((int)i));
                if (!this.isGoodChairAdjacentSquare(target.getSquare(), testSquare)) continue;
                testSquares.add(testSquare);
            }
            float closestDistance = 0.0f;
            IsoGridSquare closestSquare = null;
            if (!testSquares.isEmpty()) {
                for (IsoGridSquare testSquare : testSquares) {
                    float testDistance = this.chr.DistTo(testSquare.getX(), testSquare.getY());
                    if (closestDistance != 0.0f && (!(testDistance < closestDistance) || !this.isGoodChairAdjacentSquare(target.getSquare(), testSquare))) continue;
                    closestDistance = testDistance;
                    closestSquare = testSquare;
                }
            }
            if (closestSquare != null) {
                this.setData((float)closestSquare.getX() + 0.5f, (float)closestSquare.getY() + 0.5f, closestSquare.getZ());
                return;
            }
        }
        this.setData(target.getX(), target.getY(), target.getZ());
    }

    public void pathToLocation(int x, int y, int z) {
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.Location;
        this.setData((float)x + 0.5f, (float)y + 0.5f, z);
    }

    public void pathToLocationF(float x, float y, float z) {
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.Location;
        this.setData(x, y, z);
    }

    public void pathToSound(int x, int y, int z) {
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.Sound;
        this.setData((float)x + 0.5f, (float)y + 0.5f, z);
    }

    public void pathToNearest(TFloatArrayList locations) {
        if (locations == null || locations.isEmpty()) {
            throw new IllegalArgumentException("locations is null or empty");
        }
        if (locations.size() % 3 != 0) {
            throw new IllegalArgumentException("locations should be multiples of x,y,z");
        }
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.Location;
        this.setData(locations.get(0), locations.get(1), locations.get(2));
        for (int i = 3; i < locations.size(); i += 3) {
            this.targetXyz.add(locations.get(i));
            this.targetXyz.add(locations.get(i + 1));
            this.targetXyz.add(locations.get(i + 2));
        }
    }

    public void pathToNearestTable(KahluaTable locationsTable) {
        if (locationsTable == null || locationsTable.isEmpty()) {
            throw new IllegalArgumentException("locations table is null or empty");
        }
        if (locationsTable.len() % 3 != 0) {
            throw new IllegalArgumentException("locations table should be multiples of x,y,z");
        }
        TFloatArrayList locations = new TFloatArrayList(locationsTable.size());
        int len = locationsTable.len();
        for (int i = 1; i <= len; i += 3) {
            Double d1 = (Double)Type.tryCastTo((Object)locationsTable.rawget(i), Double.class);
            Double d2 = (Double)Type.tryCastTo((Object)locationsTable.rawget(i + 1), Double.class);
            Double d3 = (Double)Type.tryCastTo((Object)locationsTable.rawget(i + 2), Double.class);
            if (d1 == null || d2 == null || d3 == null) {
                throw new IllegalArgumentException("locations table should be multiples of x,y,z");
            }
            locations.add(d1.floatValue());
            locations.add(d2.floatValue());
            locations.add(d3.floatValue());
        }
        this.pathToNearest(locations);
    }

    public void pathToSitOnFurniture(IsoObject furniture, boolean bAnySpriteGridObject) {
        int i;
        TFloatArrayList locations = new TFloatArrayList(12);
        ArrayList<IsoObject> objects = new ArrayList<IsoObject>();
        if (bAnySpriteGridObject) {
            furniture.getSpriteGridObjectsExcludingSelf(objects);
        }
        objects.add(furniture);
        for (i = 0; i < objects.size(); ++i) {
            this.pathToSitOnFurnitureNoSpriteGrid((IsoObject)objects.get(i), locations);
        }
        if (locations.isEmpty()) {
            this.isCancel = false;
            this.startedMoving = false;
            this.goal = Goal.SitOnFurniture;
            this.goalSitOnFurnitureObject = furniture;
            this.goalSitOnFurnitureAnySpriteGridObject = bAnySpriteGridObject;
            this.setData(this.chr.getX(), this.chr.getY(), this.chr.getZ());
            this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.failed;
            return;
        }
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.SitOnFurniture;
        this.goalSitOnFurnitureObject = furniture;
        this.goalSitOnFurnitureAnySpriteGridObject = bAnySpriteGridObject;
        this.setData(locations.get(0), locations.get(1), locations.get(2));
        for (i = 3; i < locations.size(); i += 3) {
            this.targetXyz.add(locations.get(i));
            this.targetXyz.add(locations.get(i + 1));
            this.targetXyz.add(locations.get(i + 2));
        }
    }

    private void pathToSitOnFurnitureNoSpriteGrid(IsoObject furniture, TFloatArrayList locations) {
        Vector3f worldPos = new Vector3f();
        float radius = 0.3f;
        String[] directions = new String[]{"N", "S", "W", "E"};
        String[] sides = new String[]{"Front", "Left", "Right"};
        for (String direction : directions) {
            for (String side : sides) {
                LosUtil.TestResults testResults;
                boolean bValid = SeatingManager.getInstance().getAdjacentPosition(this.chr, furniture, direction, side, "sitonfurniture", "SitOnFurniture" + side, worldPos);
                if (!bValid) continue;
                IsoGridSquare square = furniture.getSquare();
                if ((square.isSolid() || square.isSolidTrans()) && this.isPointInSquare(worldPos.x, worldPos.y, square.getX(), square.getY())) {
                    float ox = worldPos.x;
                    float oy = worldPos.y;
                    if (direction == directions[0]) {
                        if (side == sides[0]) {
                            worldPos.y = (float)square.getY() - 0.3f;
                        } else if (side == sides[1]) {
                            worldPos.x = (float)square.getX() - 0.3f;
                        } else if (side == sides[2]) {
                            worldPos.x = (float)(square.getX() + 1) + 0.3f;
                        }
                    } else if (direction == directions[1]) {
                        if (side == sides[0]) {
                            worldPos.y = (float)(square.getY() + 1) + 0.3f;
                        } else if (side == sides[1]) {
                            worldPos.x = (float)(square.getX() + 1) + 0.3f;
                        } else if (side == sides[2]) {
                            worldPos.x = (float)square.getX() - 0.3f;
                        }
                    } else if (direction == directions[2]) {
                        if (side == sides[0]) {
                            worldPos.x = (float)square.getX() - 0.3f;
                        } else if (side == sides[1]) {
                            worldPos.y = (float)(square.getY() + 1) + 0.3f;
                        } else if (side == sides[2]) {
                            worldPos.y = (float)square.getY() - 0.3f;
                        }
                    } else if (direction == directions[3]) {
                        if (side == sides[0]) {
                            worldPos.x = (float)(square.getX() + 1) + 0.3f;
                        } else if (side == sides[1]) {
                            worldPos.y = (float)square.getY() - 0.3f;
                        } else if (side == sides[2]) {
                            worldPos.y = (float)(square.getY() + 1) + 0.3f;
                        }
                    }
                    LosUtil.TestResults testResults2 = LosUtil.lineClear((IsoCell)IsoWorld.instance.currentCell, (int)PZMath.fastfloor((float)worldPos.x), (int)PZMath.fastfloor((float)worldPos.y), (int)PZMath.fastfloor((float)worldPos.z), (int)PZMath.fastfloor((float)ox), (int)PZMath.fastfloor((float)oy), (int)PZMath.fastfloor((float)worldPos.z), (boolean)false);
                    if (testResults2 == LosUtil.TestResults.Blocked || testResults2 == LosUtil.TestResults.ClearThroughClosedDoor || testResults2 == LosUtil.TestResults.ClearThroughWindow) {
                        boolean dbg = true;
                        continue;
                    }
                } else if (!(this.isPointInSquare(worldPos.x, worldPos.y, square.getX(), square.getY()) || (testResults = LosUtil.lineClear((IsoCell)IsoWorld.instance.currentCell, (int)PZMath.fastfloor((float)worldPos.x), (int)PZMath.fastfloor((float)worldPos.y), (int)PZMath.fastfloor((float)worldPos.z), (int)square.getX(), (int)square.getY(), (int)square.getZ(), (boolean)false)) != LosUtil.TestResults.Blocked && testResults != LosUtil.TestResults.ClearThroughClosedDoor && testResults != LosUtil.TestResults.ClearThroughWindow)) {
                    boolean dbg = true;
                    continue;
                }
                if (!(square.isSolid() || square.isSolidTrans() || this.chr.canStandAt(worldPos.x, worldPos.y, worldPos.z))) {
                    boolean dbg = true;
                    continue;
                }
                locations.add(worldPos.x);
                locations.add(worldPos.y);
                locations.add(worldPos.z);
            }
        }
    }

    private boolean isPointInSquare(float x, float y, int squareX, int squareY) {
        return x >= (float)squareX && x < (float)squareX + 1.0f && y >= (float)squareY && y < (float)(squareY + 1);
    }

    private void fixSitOnFurniturePath(float targetX, float targetY) {
        if (this.goalSitOnFurnitureObject == null || this.goalSitOnFurnitureObject.getObjectIndex() == -1) {
            return;
        }
        Vector3f closest = new Vector3f();
        float closestDistSq = Float.MAX_VALUE;
        ArrayList<IsoObject> objects = new ArrayList<IsoObject>();
        if (this.goalSitOnFurnitureAnySpriteGridObject) {
            this.goalSitOnFurnitureObject.getSpriteGridObjectsExcludingSelf(objects);
        }
        objects.add(this.goalSitOnFurnitureObject);
        IsoObject closestObject = this.goalSitOnFurnitureObject;
        for (int i = 0; i < objects.size(); ++i) {
            IsoObject object = (IsoObject)objects.get(i);
            String[] directions = new String[]{"N", "S", "W", "E"};
            String[] sides = new String[]{"Front", "Left", "Right"};
            Vector3f worldPos = new Vector3f();
            for (String direction : directions) {
                for (String side : sides) {
                    float distSq;
                    boolean bValid = SeatingManager.getInstance().getAdjacentPosition(this.chr, object, direction, side, "sitonfurniture", "SitOnFurniture" + side, worldPos);
                    if (!bValid || !((distSq = IsoUtils.DistanceToSquared((float)targetX, (float)targetY, (float)worldPos.x, (float)worldPos.y)) < closestDistSq)) continue;
                    closest.set((Vector3fc)worldPos);
                    closestDistSq = distSq;
                    closestObject = object;
                }
            }
        }
        if (closestDistSq > 1.0f) {
            return;
        }
        this.goalSitOnFurnitureObject = closestObject;
        if (IsoUtils.DistanceToSquared((float)closest.x, (float)closest.y, (float)targetX, (float)targetY) > 0.0025000002f) {
            this.path.addNode(closest.x, closest.y, closest.z);
            this.targetX = closest.x;
            this.targetY = closest.y;
        }
    }

    public boolean shouldIgnoreCollisionWithSquare(IsoGridSquare square) {
        return this.goal == Goal.SitOnFurniture && this.goalSitOnFurnitureObject != null && this.goalSitOnFurnitureObject.getSquare() == square;
    }

    public void pathToVehicleAdjacent(BaseVehicle vehicle) {
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.VehicleAdjacent;
        this.goalVehicle = vehicle;
        VehicleScript script = vehicle.getScript();
        Vector3f ext = script.getExtents();
        Vector3f com = script.getCenterOfMassOffset();
        float width = ext.x;
        float length = ext.z;
        float radius = 0.3f;
        float minX = com.x - width / 2.0f - 0.3f;
        float minY = com.z - length / 2.0f - 0.3f;
        float maxX = com.x + width / 2.0f + 0.3f;
        float maxY = com.z + length / 2.0f + 0.3f;
        TFloatArrayList locations = new TFloatArrayList();
        Vector3f v = vehicle.getWorldPos(minX, com.y, com.z, tempVector3f_1);
        if (PolygonalMap2.instance.canStandAt(v.x, v.y, PZMath.fastfloor((float)this.targetZ), (IsoMovingObject)vehicle, false, true)) {
            locations.add(v.x);
            locations.add(v.y);
            locations.add(this.targetZ);
        }
        v = vehicle.getWorldPos(maxX, com.y, com.z, tempVector3f_1);
        if (PolygonalMap2.instance.canStandAt(v.x, v.y, PZMath.fastfloor((float)this.targetZ), (IsoMovingObject)vehicle, false, true)) {
            locations.add(v.x);
            locations.add(v.y);
            locations.add(this.targetZ);
        }
        v = vehicle.getWorldPos(com.x, com.y, minY, tempVector3f_1);
        if (PolygonalMap2.instance.canStandAt(v.x, v.y, PZMath.fastfloor((float)this.targetZ), (IsoMovingObject)vehicle, false, true)) {
            locations.add(v.x);
            locations.add(v.y);
            locations.add(this.targetZ);
        }
        v = vehicle.getWorldPos(com.x, com.y, maxY, tempVector3f_1);
        if (PolygonalMap2.instance.canStandAt(v.x, v.y, PZMath.fastfloor((float)this.targetZ), (IsoMovingObject)vehicle, false, true)) {
            locations.add(v.x);
            locations.add(v.y);
            locations.add(this.targetZ);
        }
        this.setData(locations.get(0), locations.get(1), locations.get(2));
        for (int i = 3; i < locations.size(); i += 3) {
            this.targetXyz.add(locations.get(i));
            this.targetXyz.add(locations.get(i + 1));
            this.targetXyz.add(locations.get(i + 2));
        }
    }

    public void pathToVehicleArea(BaseVehicle vehicle, String areaId) {
        Vector2 areaCenter = vehicle.getAreaCenter(areaId);
        if (areaCenter == null) {
            this.targetX = this.chr.getX();
            this.targetY = this.chr.getY();
            this.targetZ = this.chr.getZ();
            this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.failed;
            return;
        }
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.VehicleArea;
        this.goalVehicle = vehicle;
        this.goalVehicleArea = areaId;
        this.setData(areaCenter.getX(), areaCenter.getY(), PZMath.fastfloor((float)vehicle.getZ()));
        if (this.chr instanceof IsoPlayer && PZMath.fastfloor((float)this.chr.getZ()) == PZMath.fastfloor((float)this.targetZ) && !PolygonalMap2.instance.lineClearCollide(this.chr.getX(), this.chr.getY(), this.targetX, this.targetY, PZMath.fastfloor((float)this.targetZ), null)) {
            this.path.clear();
            this.path.addNode(this.chr.getX(), this.chr.getY(), this.chr.getZ());
            this.path.addNode(this.targetX, this.targetY, this.targetZ);
            this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.found;
        }
    }

    public void pathToVehicleSeat(BaseVehicle vehicle, int seat) {
        Vector2 areaPos;
        VehicleScript.Area area;
        Vector2 vector2;
        Vector3f worldPos;
        VehicleScript.Position posn = vehicle.getPassengerPosition(seat, "outside2");
        if (posn != null) {
            worldPos = (Vector3f)((BaseVehicle.Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc();
            if (posn.area == null) {
                vehicle.getPassengerPositionWorldPos(posn, worldPos);
            } else {
                vector2 = (Vector2)Vector2ObjectPool.get().alloc();
                area = vehicle.getScript().getAreaById(posn.area);
                areaPos = vehicle.areaPositionWorld4PlayerInteract(area, vector2);
                worldPos.x = areaPos.x;
                worldPos.y = areaPos.y;
                worldPos.z = 0.0f;
                Vector2ObjectPool.get().release(vector2); // pzopt: decompiler fix, CFR cast broke ObjectPool<Vector2>.release(T)
            }
            worldPos.sub(this.chr.getX(), this.chr.getY(), this.chr.getZ());
            if (worldPos.length() < 2.0f) {
                vehicle.getPassengerPositionWorldPos(posn, worldPos);
                this.setData(worldPos.x(), worldPos.y(), PZMath.fastfloor((float)worldPos.z()));
                if (this.chr instanceof IsoPlayer && PZMath.fastfloor((float)this.chr.getZ()) == PZMath.fastfloor((float)this.targetZ)) {
                    ((BaseVehicle.Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(worldPos); // pzopt: decompiler fix, CFR cast broke ObjectPool<Vector3f>.release(T)
                    this.path.clear();
                    this.path.addNode(this.chr.getX(), this.chr.getY(), this.chr.getZ());
                    this.path.addNode(this.targetX, this.targetY, this.targetZ);
                    this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.found;
                    return;
                }
            }
            ((BaseVehicle.Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(worldPos); // pzopt: decompiler fix, CFR cast broke ObjectPool<Vector3f>.release(T)
        }
        if ((posn = vehicle.getPassengerPosition(seat, "outside")) == null) {
            VehiclePart door = vehicle.getPassengerDoor(seat);
            if (door == null) {
                this.targetX = this.chr.getX();
                this.targetY = this.chr.getY();
                this.targetZ = this.chr.getZ();
                this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.failed;
                return;
            }
            this.pathToVehicleArea(vehicle, door.getArea());
            return;
        }
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.VehicleSeat;
        this.goalVehicle = vehicle;
        worldPos = (Vector3f)((BaseVehicle.Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).alloc();
        if (posn.area == null) {
            vehicle.getPassengerPositionWorldPos(posn, worldPos);
        } else {
            vector2 = (Vector2)Vector2ObjectPool.get().alloc();
            area = vehicle.getScript().getAreaById(posn.area);
            areaPos = vehicle.areaPositionWorld4PlayerInteract(area, vector2);
            worldPos.x = areaPos.x;
            worldPos.y = areaPos.y;
            worldPos.z = PZMath.fastfloor((float)(vehicle.jniTransform.origin.y / 2.44949f));
            Vector2ObjectPool.get().release(vector2); // pzopt: decompiler fix, CFR cast broke ObjectPool<Vector2>.release(T)
        }
        this.setData(worldPos.x(), worldPos.y(), PZMath.fastfloor((float)worldPos.z()));
        ((BaseVehicle.Vector3fObjectPool)BaseVehicle.TL_vector3f_pool.get()).release(worldPos); // pzopt: decompiler fix, CFR cast broke ObjectPool<Vector3f>.release(T)
        if (this.chr instanceof IsoPlayer && PZMath.fastfloor((float)this.chr.getZ()) == PZMath.fastfloor((float)this.targetZ) && !PolygonalMap2.instance.lineClearCollide(this.chr.getX(), this.chr.getY(), this.targetX, this.targetY, PZMath.fastfloor((float)this.targetZ), null)) {
            this.path.clear();
            this.path.addNode(this.chr.getX(), this.chr.getY(), this.chr.getZ());
            this.path.addNode(this.targetX, this.targetY, this.targetZ);
            this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.found;
        }
    }

    private void getGrabCorpseLocations(IsoDeadBody targetBody, List<Vector2f> possibleTargetPositions) {
        if (targetBody.isAnimal()) {
            AnimalDefinitions animalDef = AnimalDefinitions.getDef((String)targetBody.getAnimalType());
            float collisionSize = (animalDef == null ? 1.0f : PZMath.max((float)animalDef.collisionSize, (float)0.2f)) * targetBody.getAnimalSize();
            double radius = (double)collisionSize * 2.0;
            int segments = 12;
            for (int i = 0; i < 12; ++i) {
                double angle = Math.toRadians((double)i * 360.0 / 12.0);
                double cx = (double)targetBody.getX() + radius * Math.cos(angle);
                double cy = (double)targetBody.getY() + radius * Math.sin(angle);
                possibleTargetPositions.add(new Vector2f((float)cx, (float)cy));
            }
            return;
        }
        Vector2f targetPosHead = targetBody.getGrabHeadPosition(new Vector2f());
        if (targetBody.canBeGrabbedFrom(targetPosHead.x, targetPosHead.y)) {
            possibleTargetPositions.add(targetPosHead);
        }
        Vector2f targetPosLegs = targetBody.getGrabLegsPosition(new Vector2f());
        if (targetBody.canBeGrabbedFrom(targetPosLegs.x, targetPosLegs.y)) {
            possibleTargetPositions.add(targetPosLegs);
        }
        if (possibleTargetPositions.isEmpty()) {
            Vector2f targetPosBody = new Vector2f(targetBody.getX(), targetBody.getY());
            if (targetBody.canBeGrabbedFrom(targetPosBody.x, targetPosBody.y)) {
                possibleTargetPositions.add(targetPosBody);
            }
        }
    }

    @UsedFromLua
    public void pathToGrabCorpse(IsoDeadBody targetBody) {
        ArrayList<Vector2f> possibleTargetPositions = new ArrayList<Vector2f>();
        this.getGrabCorpseLocations(targetBody, possibleTargetPositions);
        if (possibleTargetPositions.isEmpty()) {
            DebugType.Grapple.error("Cannot find suitable point to grab from. %s", new Object[]{targetBody});
            return;
        }
        float targetPosZ = targetBody.getZ();
        Vector2f firstPos = (Vector2f)possibleTargetPositions.getFirst();
        this.isCancel = false;
        this.startedMoving = false;
        this.goal = Goal.GrabCorpse;
        this.setData(firstPos.x, firstPos.y, targetPosZ);
        for (int i = 1; i < possibleTargetPositions.size(); ++i) {
            Vector2f possiblePos = (Vector2f)possibleTargetPositions.get(i);
            this.targetXyz.add(possiblePos.x);
            this.targetXyz.add(possiblePos.y);
            this.targetXyz.add(targetPosZ);
        }
    }

    public void cancel() {
        this.isCancel = true;
    }

    public boolean getIsCancelled() {
        return this.isCancel;
    }

    public void setData(float targetX, float targetY, float targetZ) {
        this.startX = this.chr.getX();
        this.startY = this.chr.getY();
        this.startZ = this.chr.getZ();
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.targetXyz.resetQuick();
        this.pathIndex = 0;
        if (PathfindNative.useNativeCode) {
            PathfindNative.instance.cancelRequest((Mover)this.chr);
        } else {
            PolygonalMap2.instance.cancelRequest((Mover)this.chr);
        }
        this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.notrunning;
        this.stopping = false;
        actualPool.release(this.actualPos);
        this.actualPos.clear();
    }

    public float getTargetX() {
        return this.targetX;
    }

    public float getTargetY() {
        return this.targetY;
    }

    public float getTargetZ() {
        return this.targetZ;
    }

    public float getPathLength() {
        if (this.path == null || this.path.nodes.isEmpty()) {
            return (float)Math.sqrt((this.chr.getX() - this.targetX) * (this.chr.getX() - this.targetX) + (this.chr.getY() - this.targetY) * (this.chr.getY() - this.targetY));
        }
        if (this.pathIndex + 1 >= this.path.nodes.size()) {
            return (float)Math.sqrt((this.chr.getX() - this.targetX) * (this.chr.getX() - this.targetX) + (this.chr.getY() - this.targetY) * (this.chr.getY() - this.targetY));
        }
        float length = (float)Math.sqrt((this.chr.getX() - this.path.nodes.get((int)(this.pathIndex + 1)).x) * (this.chr.getX() - this.path.nodes.get((int)(this.pathIndex + 1)).x) + (this.chr.getY() - this.path.nodes.get((int)(this.pathIndex + 1)).y) * (this.chr.getY() - this.path.nodes.get((int)(this.pathIndex + 1)).y));
        for (int i = this.pathIndex + 2; i < this.path.nodes.size(); ++i) {
            length += (float)Math.sqrt((this.path.nodes.get((int)(i - 1)).x - this.path.nodes.get((int)i).x) * (this.path.nodes.get((int)(i - 1)).x - this.path.nodes.get((int)i).x) + (this.path.nodes.get((int)(i - 1)).y - this.path.nodes.get((int)i).y) * (this.path.nodes.get((int)(i - 1)).y - this.path.nodes.get((int)i).y));
        }
        return length;
    }

    public IsoGameCharacter getTargetChar() {
        return this.goal == Goal.Character ? this.goalCharacter : null;
    }

    public boolean isTargetLocation(float x, float y, float z) {
        return this.goal == Goal.Location && x == this.targetX && y == this.targetY && PZMath.fastfloor((float)z) == PZMath.fastfloor((float)this.targetZ);
    }

    public BehaviorResult update() {
        return this.update(1.0f);
    }

    public BehaviorResult update(float speedMul) {
        // pzopt: entityUpdateParallel. This method used to open with a defensive clone of this.path.nodes, on the
        // reading that PZ's pathfinding writes the list from its own thread while a batch task reads it. It does
        // not. Both pathfinders fill the REQUEST's own Path and queue the request; the only code that copies a
        // result into a character is Succeeded, whose only callers are PolygonalMap2.updateMain and
        // PathfindNative.updateMain, both on the game thread from IngameState.UpdateStuff, which updateInternal
        // reaches only after IsoWorld.update() has returned and the scheduler's finally has joined the flight.
        // this.path is private, so nothing outside this class can touch the list at all. The clone guarded a
        // writer that does not exist in the window and cost one ArrayList copy per batched zombie per frame, so
        // it is gone and every read below is the live list again, as stock.
        //
        // What did race is the four STATIC scratch objects this class shares between every thread running these
        // methods; each one is a per-thread object now (pathScratch/pathScratch2/pathScratch3/pathPointScratch).
        // The sharp one is pointOnPath, two lines below: closestPointOnPath writes it and the next statement
        // reads pathIndex back, so a second task landing in between handed this character an index derived from
        // ANOTHER character's path — out of range it threw (the counted escapes), in range it silently walked the
        // wrong segment. Each local below deliberately SHADOWS the static field of the same name, so every
        // unqualified use in the body reads this thread's object with no other change to the method; the
        // PathFindBehavior2.-qualified uses are rewritten one by one, and PathfindRaceGuardTest pins in bytecode
        // that no read of any of the four statics is left here, which is what makes a missed one a build failure.
        PointOnPath pointOnPath = pzopt.UpdateBatch.pathPointScratch(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 tempVector2 = pzopt.UpdateBatch.pathScratch(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 tempVector2_2 = pzopt.UpdateBatch.pathScratch2(); // pzopt: entityUpdateParallel, shadows the static
        try { // pzopt: entityUpdateParallel, the assertion at the bottom of the method
        if (this.chr.getFinder().progress == AStarPathFinder.PathFindProgress.notrunning) {
            this.initPathFindRequest(IsoPlayer.isPlayer(this.chr));
            this.updateWhileRunningPathfind();
            return BehaviorResult.Working;
        }
        if (this.chr.getFinder().progress == AStarPathFinder.PathFindProgress.notyetfound) {
            this.updateWhileRunningPathfind();
            return BehaviorResult.Working;
        }
        if (this.chr.getFinder().progress == AStarPathFinder.PathFindProgress.failed && this.chr.getFinder().avoidFarmingPlants) {
            this.initPathFindRequest(false);
            this.updateWhileRunningPathfind();
            return BehaviorResult.Working;
        }
        if (this.chr.getFinder().progress == AStarPathFinder.PathFindProgress.failed) {
            return BehaviorResult.Failed;
        }
        State state = this.chr.getCurrentState();
        if (Core.debug && DebugOptions.instance.pathfindRenderPath.getValue() && this.chr instanceof IsoPlayer && !this.chr.isAnimal()) {
            while (this.actualPos.size() > 100) {
                actualPool.release(this.actualPos.remove(0)); // pzopt: decompiler fix, CFR cast broke ObjectPool<DebugPt>.release(T)
            }
            this.actualPos.add(((DebugPt)actualPool.alloc()).init(this.chr.getX(), this.chr.getY(), this.chr.getZ(), state == ClimbOverFenceState.instance() || state == ClimbThroughWindowState.instance()));
        }
        if (state == ClimbOverFenceState.instance() || state == ClimbThroughWindowState.instance() || state == PlayerSitOnFurnitureState.instance()) {
            IsoPlayer isoPlayer;
            IsoGameCharacter isoGameCharacter;
            if (GameClient.client && (isoGameCharacter = this.chr) instanceof IsoPlayer && !(isoPlayer = (IsoPlayer)isoGameCharacter).isLocalPlayer()) {
                this.chr.getDeferredMovement(tempVector2_2);
                this.chr.moveUnmodded(tempVector2_2.x, tempVector2_2.y); // pzopt: entityUpdateParallel, the shadowing local
            }
            return BehaviorResult.Working;
        }
        if (this.chr.getVehicle() != null) {
            return BehaviorResult.Failed;
        }
        if (this.walkingOnTheSpot.check(this.chr)) {
            return BehaviorResult.Failed;
        }
        this.chr.setMoving(true);
        this.chr.setPath2(this.path);
        IsoZombie zombie = (IsoZombie)Type.tryCastTo((Object)this.chr, IsoZombie.class);
        if (this.goal == Goal.Character && zombie != null && this.goalCharacter != null && this.goalCharacter.getVehicle() != null && this.chr.DistToSquared(this.targetX, this.targetY) < 16.0f) {
            Vector3f v = this.goalCharacter.getVehicle().chooseBestAttackPosition(this.goalCharacter, this.chr, pzopt.UpdateBatch.pathScratch3()); // pzopt: entityUpdateParallel, this thread's tempVector3f_1
            if (v == null) {
                return BehaviorResult.Failed;
            }
            if (Math.abs(v.x - this.targetX) > 0.1f || Math.abs(v.y - this.targetY) > 0.1f) {
                if (Math.abs(this.goalCharacter.getVehicle().getCurrentSpeedKmHour()) > 0.8f) {
                    if (!PolygonalMap2.instance.lineClearCollide(this.chr.getX(), this.chr.getY(), v.x, v.y, PZMath.fastfloor((float)this.targetZ), (IsoMovingObject)this.goalCharacter)) {
                        this.path.clear();
                        this.path.addNode(this.chr.getX(), this.chr.getY(), this.chr.getZ());
                        this.path.addNode(v.x, v.y, v.z);
                    } else if (IsoUtils.DistanceToSquared((float)v.x, (float)v.y, (float)this.targetX, (float)this.targetY) > IsoUtils.DistanceToSquared((float)this.chr.getX(), (float)this.chr.getY(), (float)v.x, (float)v.y)) {
                        return BehaviorResult.Working;
                    }
                } else if (zombie.allowRepathDelay <= 0.0f) {
                    zombie.allowRepathDelay = 6.25f;
                    if (PolygonalMap2.instance.lineClearCollide(this.chr.getX(), this.chr.getY(), v.x, v.y, PZMath.fastfloor((float)this.targetZ), null)) {
                        this.setData(v.x, v.y, this.targetZ);
                        return BehaviorResult.Working;
                    }
                    this.path.clear();
                    this.path.addNode(this.chr.getX(), this.chr.getY(), this.chr.getZ());
                    this.path.addNode(v.x, v.y, v.z);
                }
            }
        }
        PathFindBehavior2.closestPointOnPath(this.chr.getX(), this.chr.getY(), this.chr.getZ(), (IsoMovingObject)this.chr, this.path, pointOnPath);
        this.pathIndex = pointOnPath.pathIndex; // pzopt: entityUpdateParallel, the shadowing local — an index this thread just derived from its OWN path
        if (this.pathIndex == this.path.nodes.size() - 2) {
            PathNode node = this.path.nodes.get(this.path.nodes.size() - 1);
            float distToEnd = IsoUtils.DistanceTo((float)this.chr.getX(), (float)this.chr.getY(), (float)node.x, (float)node.y);
            if (distToEnd <= 0.05f) {
                this.chr.getDeferredMovement(tempVector2);
                float lengthTest = 0.0f;
                IsoGameCharacter isoGameCharacter = this.chr;
                if (isoGameCharacter instanceof IsoAnimal) {
                    IsoAnimal isoAnimal = (IsoAnimal)isoGameCharacter;
                    lengthTest = isoAnimal.adef.animalSize;
                }
                if (tempVector2.getLength() > lengthTest) {
                    if (zombie != null || this.chr instanceof IsoPlayer) {
                        this.chr.setMoving(false);
                    }
                    tempVector2_2.set(node.x - this.chr.getX(), node.y - this.chr.getY());
                    tempVector2_2.setLength(PZMath.min((float)distToEnd, (float)(0.005f * GameTime.getInstance().getMultiplier())));
                    this.chr.moveUnmodded(tempVector2_2.x, tempVector2_2.y); // pzopt: entityUpdateParallel, the shadowing local
                    this.stopping = true;
                    return BehaviorResult.Working;
                }
                this.pathNextIsSet = false;
                return BehaviorResult.Succeeded;
            }
            this.stopping = false;
        } else if (this.pathIndex < this.path.nodes.size() - 2 && pointOnPath.dist > 0.999f) { // pzopt: entityUpdateParallel, the shadowing local
            ++this.pathIndex;
        }
        PathNode v1 = this.path.nodes.get(this.pathIndex);
        PathNode v2 = this.path.nodes.get(this.pathIndex + 1);
        this.pathNextX = v2.x;
        this.pathNextY = v2.y;
        this.pathNextIsSet = true;
        Vector2 dir = tempVector2.set(this.pathNextX - this.chr.getX(), this.pathNextY - this.chr.getY());
        dir.normalize();
        if (this.chr.isPerformingNoAimShortStrafe()) {
            float strafeSpeed = 1.0f;
            this.chr.set(PlayerStrafeState.STRAFE_SPEED, Float.valueOf(1.0f)); // pzopt: decompiler fix, CFR's (Object) cast broke set(Param<T>, T) inference
            this.chr.setVariable("StrafeSpeed", 1.0f);
        }
        this.chr.getDeferredMovement(tempVector2_2);
        if (!GameServer.server && !this.chr.isAnimationUpdatingThisFrame()) {
            tempVector2_2.set(0.0f, 0.0f);
        }
        float speed = tempVector2_2.getLength();
        speed *= speedMul;
        if (zombie != null) {
            zombie.running = false;
            if (SandboxOptions.instance.lore.speed.getValue() == 1) {
                zombie.running = true;
            }
        }
        float mult = 1.0f;
        float dist = speed * 1.0f;
        float distTo = IsoUtils.DistanceTo((float)this.pathNextX, (float)this.pathNextY, (float)this.chr.getX(), (float)this.chr.getY());
        if (dist >= distTo) {
            speed *= distTo / dist;
            ++this.pathIndex;
        }
        if (zombie != null) {
            this.checkCrawlingTransition(v1, v2, distTo);
        }
        if (zombie == null && distTo >= 0.5f) {
            if (this.checkDoorHoppableWindow(this.chr.getX() + dir.x * Math.max(0.5f, speed), this.chr.getY() + dir.y * Math.max(0.5f, speed), this.chr.getZ())) {
                return BehaviorResult.Failed;
            }
            if (state != this.chr.getCurrentState()) {
                return BehaviorResult.Working;
            }
        }
        if (speed <= 0.0f) {
            this.walkingOnTheSpot.reset(this.chr.getX(), this.chr.getY());
            return BehaviorResult.Working;
        }
        if (this.shouldBeMoving()) {
            tempVector2_2.set(dir);
            tempVector2_2.setLength(speed);
            this.chr.moveUnmodded(tempVector2_2.x, tempVector2_2.y); // pzopt: entityUpdateParallel, the shadowing local — this is the step another zombie's vector used to take
            this.startedMoving = true;
        }
        if (this.isStrafing()) {
            if ((this.goal == Goal.VehicleAdjacent || this.goal == Goal.VehicleArea || this.goal == Goal.VehicleSeat) && this.goalVehicle != null) {
                this.chr.faceThisObject((IsoObject)this.goalVehicle);
            }
        } else if (!this.chr.isAiming()) {
            if (this.isTurningToObstacle() && this.chr.shouldBeTurning()) {
                boolean bl = true;
            } else if (this.chr.isAnimatingBackwards()) {
                tempVector2.set(this.chr.getX() - this.pathNextX, this.chr.getY() - this.pathNextY);
                if (tempVector2.getLengthSquared() > 0.0f) {
                    this.chr.DirectionFromVector(tempVector2);
                    tempVector2.normalize();
                    this.chr.setForwardDirection(tempVector2.x, tempVector2.y); // pzopt: entityUpdateParallel, the shadowing local — the getLengthSquared guard above now covers the same object this reads
                    AnimationPlayer animationPlayer = this.chr.getAnimationPlayer();
                    if (animationPlayer != null && animationPlayer.isReady()) {
                        animationPlayer.updateForwardDirection(this.chr);
                    }
                }
            } else {
                this.chr.faceLocationF(this.pathNextX, this.pathNextY);
            }
        }
        return BehaviorResult.Working;
        // pzopt: entityUpdateParallel. This used to swallow the throw into BehaviorResult.Working on a batch task
        // and count it, which hid the disease instead of curing it: the throws came from the shared pointOnPath
        // and the shared vectors above, and for every one of them an unknown number of reads landed in range and
        // walked the wrong segment in silence. With the four scratch objects per-thread there is no cross-thread
        // writer left on this path, so nothing here should be able to fire for a concurrency reason. It is an
        // assertion now, not a guard: a batch task's throw is counted and the first one logs its whole stack, and
        // the exception is rethrown on every thread, which is exactly vanilla's behaviour (an empty path with the
        // finder reporting found throws in stock too, and must keep throwing). Off a batch nothing is counted,
        // because that throw is vanilla's own and not a race. pathfindRaceEscaped=0 in a route log is the reading
        // holding; anything else says it does not, and says where.
        } catch (IndexOutOfBoundsException | IllegalStateException e) { // pzopt: entityUpdateParallel
            if (pzopt.UpdateBatch.onBatchTaskNow()) { // pzopt: entityUpdateParallel
                pzopt.UpdateBatch.onPathfindRaceEscaped(e); // pzopt: entityUpdateParallel
            } // pzopt: entityUpdateParallel
            throw e; // pzopt: entityUpdateParallel
        } // pzopt: entityUpdateParallel
    }

    private void initPathFindRequest(boolean avoidFarmingPlants) {
        if (PathfindNative.useNativeCode) {
            PathFindRequest request = PathfindNative.instance.addRequest(this, this.chr, this.startX, this.startY, this.startZ, this.targetX, this.targetY, this.targetZ, avoidFarmingPlants);
            request.targetXyz.resetQuick();
            request.targetXyz.addAll(this.targetXyz);
        } else {
            zombie.pathfind.PathFindRequest request = PolygonalMap2.instance.addRequest(this, this.chr, this.startX, this.startY, this.startZ, this.targetX, this.targetY, this.targetZ);
            request.targetXyz.resetQuick();
            request.targetXyz.addAll(this.targetXyz);
        }
        this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.notyetfound;
        this.chr.getFinder().avoidFarmingPlants = avoidFarmingPlants;
        this.walkingOnTheSpot.reset(this.chr.getX(), this.chr.getY());
    }

    private void updateWhileRunningPathfind() {
        if (!this.pathNextIsSet) {
            return;
        }
        this.moveToPoint(this.pathNextX, this.pathNextY, 1.0f);
    }

    public void moveToPoint(float x, float y, float speedMul) {
        if (this.chr instanceof IsoPlayer && this.chr.getCurrentState() == CollideWithWallState.instance()) {
            return;
        }
        IsoZombie zombie = (IsoZombie)Type.tryCastTo((Object)this.chr, IsoZombie.class);
        // pzopt: entityUpdateParallel. Reached from update() through updateWhileRunningPathfind and from the walk
        // states, so a batch task runs it: the two locals shadow the statics of the same name (see update()).
        Vector2 tempVector2 = pzopt.UpdateBatch.pathScratch(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 tempVector2_2 = pzopt.UpdateBatch.pathScratch2(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 dir = tempVector2.set(x - this.chr.getX(), y - this.chr.getY());
        if (PZMath.fastfloor((float)x) == PZMath.fastfloor((float)this.chr.getX()) && PZMath.fastfloor((float)y) == PZMath.fastfloor((float)this.chr.getY()) && dir.getLength() <= 0.1f) {
            return;
        }
        dir.normalize();
        this.chr.getDeferredMovement(tempVector2_2);
        float speed = tempVector2_2.getLength();
        speed *= speedMul;
        boolean isRemoteZombieWithTarget = false;
        if (zombie != null) {
            zombie.running = SandboxOptions.instance.lore.speed.getValue() == 1;
            boolean bl = isRemoteZombieWithTarget = GameClient.client && zombie.isRemoteZombie() && zombie.getTarget() != null && zombie.isCurrentState((State)LungeNetworkState.instance());
        }
        if (speed <= 0.0f) {
            return;
        }
        tempVector2_2.set(dir);
        tempVector2_2.setLength(speed);
        this.chr.moveUnmodded(tempVector2_2.x, tempVector2_2.y); // pzopt: entityUpdateParallel, the shadowing local
        if (isRemoteZombieWithTarget) {
            return;
        }
        this.chr.faceLocation(x - 0.5f, y - 0.5f);
        this.chr.setForwardDirection(x - this.chr.getX(), y - this.chr.getY());
        this.chr.getForwardDirection().normalize();
    }

    public void moveToDir(IsoMovingObject target, float speedMul) {
        // pzopt: entityUpdateParallel. The lunge and bumped states reach this from a batched zombie's update, so
        // the two locals shadow the statics of the same name (see update()).
        Vector2 tempVector2 = pzopt.UpdateBatch.pathScratch(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 tempVector2_2 = pzopt.UpdateBatch.pathScratch2(); // pzopt: entityUpdateParallel, shadows the static
        Vector2 dir = tempVector2.set(target.getX() - this.chr.getX(), target.getY() - this.chr.getY());
        if (dir.getLength() <= 0.1f) {
            return;
        }
        dir.normalize();
        this.chr.getDeferredMovement(tempVector2_2);
        float speed = tempVector2_2.getLength();
        speed *= speedMul;
        IsoGameCharacter isoGameCharacter = this.chr;
        if (isoGameCharacter instanceof IsoZombie) {
            IsoZombie isoZombie = (IsoZombie)isoGameCharacter;
            isoZombie.running = false;
            if (SandboxOptions.instance.lore.speed.getValue() == 1) {
                isoZombie.running = true;
            }
        }
        if (speed <= 0.0f) {
            return;
        }
        tempVector2_2.set(dir);
        tempVector2_2.setLength(speed);
        this.chr.moveUnmodded(tempVector2_2.x, tempVector2_2.y); // pzopt: entityUpdateParallel, the shadowing local
        this.chr.faceLocation(target.getX() - 0.5f, target.getY() - 0.5f);
        this.chr.setForwardDirection(target.getX() - this.chr.getX(), target.getY() - this.chr.getY());
        this.chr.getForwardDirection().normalize();
    }

    private boolean checkDoorHoppableWindow(float nx, float ny, float z) {
        IsoGameCharacter isoGameCharacter;
        IsoWindow window;
        IsoThumpable door;
        this.turningToObstacle = false;
        IsoGridSquare current = this.chr.getCurrentSquare();
        if (current == null) {
            return false;
        }
        IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare((double)nx, (double)ny, (double)z);
        if (square == null || square == current) {
            return false;
        }
        int dx = square.x - current.x;
        int dy = square.y - current.y;
        if (dx != 0 && dy != 0) {
            return false;
        }
        IsoObject object = this.chr.getCurrentSquare().getDoorTo(square);
        if (object instanceof IsoDoor) {
            IsoDoor door2 = (IsoDoor)object;
            if (!door2.isOpen()) {
                IsoGameCharacter var12_10 = this.chr; // pzopt: decompiler fix, CFR dropped this local's declaration
                IsoPlayer player; // pzopt: decompiler fix, CFR dropped this local's declaration
                if (var12_10 instanceof IsoPlayer && !(player = (IsoPlayer)var12_10).isAnimal() && player.timeSinceCloseDoor < 50.0f) {
                    this.chr.setCollidable(false);
                } else {
                    if (!door2.couldBeOpen(this.chr)) {
                        door2.ToggleDoor(this.chr);
                        return true;
                    }
                    this.chr.setCollidable(true);
                    door2.ToggleDoor(this.chr);
                    if (!door2.isOpen()) {
                        return true;
                    }
                }
            }
        } else if (object instanceof IsoThumpable && (door = (IsoThumpable)object).isDoor() && !door.open) {
            IsoGameCharacter var12_10 = this.chr; // pzopt: decompiler fix, CFR dropped this local's declaration
            IsoPlayer player; // pzopt: decompiler fix, CFR dropped this local's declaration
            if (var12_10 instanceof IsoPlayer && !(player = (IsoPlayer)var12_10).isAnimal() && player.timeSinceCloseDoor < 50.0f) {
                this.chr.setCollidable(false);
            } else {
                if (!door.couldBeOpen(this.chr)) {
                    door.ToggleDoor(this.chr);
                    return true;
                }
                this.chr.setCollidable(true);
                door.ToggleDoor(this.chr);
                if (!door.open) {
                    return true;
                }
            }
        }
        if ((window = current.getWindowTo(square)) != null) {
            if (!window.canClimbThrough(this.chr) || window.isSmashed() && !window.isGlassRemoved()) {
                return true;
            }
            if (this.chr.isAiming()) {
                return false;
            }
            this.chr.faceThisObject((IsoObject)window);
            if (this.chr.shouldBeTurning()) {
                this.turningToObstacle = true;
                return false;
            }
            this.chr.climbThroughWindow(window);
            return false;
        }
        IsoThumpable windowThumpable = current.getWindowThumpableTo(square);
        if (windowThumpable != null) {
            if (windowThumpable.isBarricaded()) {
                return true;
            }
            if (this.chr.isAiming()) {
                return false;
            }
            this.chr.faceThisObject((IsoObject)windowThumpable);
            if (this.chr.shouldBeTurning()) {
                this.turningToObstacle = true;
                return false;
            }
            this.chr.climbThroughWindow(windowThumpable);
            return false;
        }
        IsoWindowFrame windowFrame = current.getWindowFrameTo(square);
        if (windowFrame != null) {
            this.chr.climbThroughWindowFrame(windowFrame);
            return false;
        }
        IsoDirections climbDir = null;
        if (dx > 0 && square.has(IsoFlagType.HoppableW)) {
            climbDir = IsoDirections.E;
        } else if (dx < 0 && current.has(IsoFlagType.HoppableW)) {
            climbDir = IsoDirections.W;
        } else if (dy < 0 && current.has(IsoFlagType.HoppableN)) {
            climbDir = IsoDirections.N;
        } else if (dy > 0 && square.has(IsoFlagType.HoppableN)) {
            climbDir = IsoDirections.S;
        }
        if (climbDir != null) {
            if (this.chr.isAiming()) {
                return false;
            }
            this.chr.faceDirection(climbDir);
            if (this.chr.shouldBeTurning()) {
                this.turningToObstacle = true;
                return false;
            }
            this.chr.climbOverFence(climbDir);
        }
        climbDir = null;
        if (dx > 0 && (square.has(IsoFlagType.TallHoppableW) || square.has(IsoFlagType.WallW) || square.has(IsoFlagType.WallWTrans))) {
            climbDir = IsoDirections.E;
        } else if (dx < 0 && (current.has(IsoFlagType.TallHoppableW) || current.has(IsoFlagType.WallW) || current.has(IsoFlagType.WallWTrans))) {
            climbDir = IsoDirections.W;
        } else if (dy < 0 && (current.has(IsoFlagType.TallHoppableN) || current.has(IsoFlagType.WallN) || current.has(IsoFlagType.WallNTrans))) {
            climbDir = IsoDirections.N;
        } else if (dy > 0 && (square.has(IsoFlagType.TallHoppableN) || square.has(IsoFlagType.WallN) || square.has(IsoFlagType.WallNTrans))) {
            climbDir = IsoDirections.S;
        }
        if (climbDir != null && (isoGameCharacter = this.chr) instanceof IsoPlayer) {
            IsoPlayer player = (IsoPlayer)isoGameCharacter;
            player.climbOverWall(climbDir);
            return false;
        }
        return false;
    }

    private void checkCrawlingTransition(PathNode v1, PathNode v2, float distTo) {
        IsoZombie zombie = (IsoZombie)this.chr;
        if (this.pathIndex < this.path.nodes.size() - 2) {
            v1 = this.path.nodes.get(this.pathIndex);
            v2 = this.path.nodes.get(this.pathIndex + 1);
            distTo = IsoUtils.DistanceTo((float)v2.x, (float)v2.y, (float)this.chr.getX(), (float)this.chr.getY());
        }
        if (zombie.isCrawling()) {
            if (!zombie.isCanWalk()) {
                return;
            }
            if (zombie.isBeingSteppedOn()) {
                // empty if block
            }
            if (zombie.getStateMachine().getPrevious() == ZombieGetDownState.instance() && ZombieGetDownState.instance().isNearStartXY((IsoGameCharacter)zombie)) {
                return;
            }
            // pzopt: entityUpdateParallel. update() calls this per crawling zombie, so this advanceAlongPath and
            // the read under it are a batch task's: this thread's PointOnPath, not the shared static. On one
            // thread it is the same object update() already used, exactly as the static was.
            PointOnPath pointOnPath = pzopt.UpdateBatch.pathPointScratch(); // pzopt: entityUpdateParallel, shadows the static
            this.advanceAlongPath(this.chr.getX(), this.chr.getY(), this.chr.getZ(), 0.5f, pointOnPath);
            if (!PolygonalMap2.instance.canStandAt(pointOnPath.x, pointOnPath.y, PZMath.fastfloor((float)zombie.getZ()), null, false, true)) { // pzopt: entityUpdateParallel, the shadowing local
                return;
            }
            if (!v2.hasFlag(1) && PolygonalMap2.instance.canStandAt(zombie.getX(), zombie.getY(), PZMath.fastfloor((float)zombie.getZ()), null, false, true)) {
                zombie.setVariable("ShouldStandUp", true);
            }
        } else {
            if (v1.hasFlag(1) && v2.hasFlag(1)) {
                zombie.setVariable("ShouldBeCrawling", true);
                ZombieGetDownState.instance().setParams(this.chr);
                return;
            }
            if (distTo < 0.4f && !v1.hasFlag(1) && v2.hasFlag(1)) {
                zombie.setVariable("ShouldBeCrawling", true);
                ZombieGetDownState.instance().setParams(this.chr);
            }
        }
    }

    public boolean shouldGetUpFromCrawl() {
        return this.chr.getVariableBoolean("ShouldStandUp");
    }

    public boolean shouldBeMoving() {
        if (this.stopping) {
            return false;
        }
        return !this.allowTurnAnimation() || !this.chr.shouldBeTurning();
    }

    public boolean hasStartedMoving() {
        return this.startedMoving;
    }

    public boolean allowTurnAnimation() {
        return !this.hasStartedMoving() || this.isTurningToObstacle();
    }

    public boolean isTurningToObstacle() {
        return this.turningToObstacle;
    }

    public boolean isStrafing() {
        IsoPlayer player;
        if (this.chr.isZombie()) {
            return false;
        }
        if (this.stopping) {
            return false;
        }
        IsoGameCharacter isoGameCharacter = this.chr;
        if (isoGameCharacter instanceof IsoPlayer && !(player = (IsoPlayer)isoGameCharacter).isLocal()) {
            return false;
        }
        return this.path.nodes.size() == 2 && IsoUtils.DistanceToSquared((float)this.startX, (float)this.startY, (float)(this.startZ * 3.0f), (float)this.targetX, (float)this.targetY, (float)(this.targetZ * 3.0f)) < 0.25f;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void closestPointOnPath(float x3, float y3, float z, IsoMovingObject mover, Path path, PointOnPath pop) {
        IsoCell cell = IsoWorld.instance.currentCell;
        pop.pathIndex = 0;
        float closestDist = Float.MAX_VALUE;
        for (int i = 0; i < path.nodes.size() - 1; ++i) {
            float dist;
            int dy;
            int dx;
            double yu;
            double xu;
            double u;
            PathNode node2;
            PathNode node1;
            block11: {
                node1 = path.nodes.get(i);
                node2 = path.nodes.get(i + 1);
                if (PZMath.fastfloor((float)node1.z) != PZMath.fastfloor((float)z) && PZMath.fastfloor((float)node2.z) != PZMath.fastfloor((float)z)) continue;
                float x1 = node1.x;
                float y1 = node1.y;
                float x2 = node2.x;
                float y2 = node2.y;
                u = (double)((x3 - x1) * (x2 - x1) + (y3 - y1) * (y2 - y1)) / (Math.pow(x2 - x1, 2.0) + Math.pow(y2 - y1, 2.0));
                xu = (double)x1 + u * (double)(x2 - x1);
                yu = (double)y1 + u * (double)(y2 - y1);
                if (u <= 0.0) {
                    xu = x1;
                    yu = y1;
                    u = 0.0;
                } else if (u >= 1.0) {
                    xu = x2;
                    yu = y2;
                    u = 1.0;
                }
                dx = PZMath.fastfloor((double)xu) - PZMath.fastfloor((float)x3);
                dy = PZMath.fastfloor((double)yu) - PZMath.fastfloor((float)y3);
                if ((dx != 0 || dy != 0) && Math.abs(dx) <= 1 && Math.abs(dy) <= 1) {
                    IsoGridSquare square1 = cell.getGridSquare(PZMath.fastfloor((float)x3), PZMath.fastfloor((float)y3), PZMath.fastfloor((float)z));
                    IsoGridSquare square2 = cell.getGridSquare(PZMath.fastfloor((double)xu), PZMath.fastfloor((double)yu), PZMath.fastfloor((float)z));
                    if (mover instanceof IsoZombie) {
                        IsoZombie isoZombie = (IsoZombie)mover;
                        boolean ghost = isoZombie.ghost;
                        isoZombie.ghost = true;
                        try {
                            if (square1 != null && square2 != null && square1.testCollideAdjacent(mover, dx, dy, 0)) {
                                continue;
                            }
                            break block11;
                        }
                        finally {
                            isoZombie.ghost = ghost;
                        }
                    }
                    if (square1 != null && square2 != null && square1.testCollideAdjacent(mover, dx, dy, 0)) continue;
                }
            }
            float closestZ = z;
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1) {
                IsoGridSquare square1 = cell.getGridSquare(PZMath.fastfloor((float)node1.x), PZMath.fastfloor((float)node1.y), PZMath.fastfloor((float)node1.z));
                IsoGridSquare square2 = cell.getGridSquare(PZMath.fastfloor((float)node2.x), PZMath.fastfloor((float)node2.y), PZMath.fastfloor((float)node2.z));
                float z1 = square1 == null ? node1.z : PolygonalMap2.instance.getApparentZ(square1);
                float z2 = square2 == null ? node2.z : PolygonalMap2.instance.getApparentZ(square2);
                closestZ = z1 + (z2 - z1) * (float)u;
            }
            if (!((dist = IsoUtils.DistanceToSquared((float)x3, (float)y3, (float)z, (float)((float)xu), (float)((float)yu), (float)closestZ)) < closestDist)) continue;
            closestDist = dist;
            pop.pathIndex = i;
            pop.dist = u == 1.0 ? 1.0f : (float)u;
            pop.x = (float)xu;
            pop.y = (float)yu;
        }
    }

    void advanceAlongPath(float x, float y, float z, float dist, PointOnPath pop) {
        PathFindBehavior2.closestPointOnPath(x, y, z, (IsoMovingObject)this.chr, this.path, pop);
        for (int i = pop.pathIndex; i < this.path.nodes.size() - 1; ++i) {
            PathNode node1 = this.path.nodes.get(i);
            PathNode node2 = this.path.nodes.get(i + 1);
            double dist2 = IsoUtils.DistanceTo2D((float)x, (float)y, (float)node2.x, (float)node2.y);
            if (!((double)dist > dist2)) {
                pop.pathIndex = i;
                pop.dist += dist / IsoUtils.DistanceTo2D((float)node1.x, (float)node1.y, (float)node2.x, (float)node2.y);
                pop.x = node1.x + pop.dist * (node2.x - node1.x);
                pop.y = node1.y + pop.dist * (node2.y - node1.y);
                return;
            }
            x = node2.x;
            y = node2.y;
            dist = (float)((double)dist - dist2);
            pop.dist = 0.0f;
        }
        pop.pathIndex = this.path.nodes.size() - 1;
        pop.dist = 1.0f;
        pop.x = this.path.nodes.get((int)pop.pathIndex).x;
        pop.y = this.path.nodes.get((int)pop.pathIndex).y;
    }

    public void render() {
        PathNode v1;
        int i;
        if (this.chr.getCurrentState() == WalkTowardState.instance()) {
            WalkTowardState.instance().calculateTargetLocation((IsoZombie)this.chr, tempVector2);
            PathFindBehavior2.tempVector2.x -= this.chr.getX();
            PathFindBehavior2.tempVector2.y -= this.chr.getY();
            tempVector2.setLength(Math.min(100.0f, tempVector2.getLength()));
            LineDrawer.addLine((float)this.chr.getX(), (float)this.chr.getY(), (float)this.chr.getZ(), (float)(this.chr.getX() + PathFindBehavior2.tempVector2.x), (float)(this.chr.getY() + PathFindBehavior2.tempVector2.y), (float)this.targetZ, (float)1.0f, (float)1.0f, (float)1.0f, null, (boolean)true);
            return;
        }
        if (this.chr.getPath2() == null) {
            return;
        }
        for (i = 0; i < this.path.nodes.size() - 1; ++i) {
            v1 = this.path.nodes.get(i);
            PathNode v2 = this.path.nodes.get(i + 1);
            float r = 1.0f;
            float g = 1.0f;
            if (PZMath.fastfloor((float)v1.z) != PZMath.fastfloor((float)v2.z)) {
                g = 0.0f;
            }
            LineDrawer.addLine((float)v1.x, (float)v1.y, (float)v1.z, (float)v2.x, (float)v2.y, (float)v2.z, (float)1.0f, (float)g, (float)0.0f, null, (boolean)true);
        }
        for (i = 0; i < this.path.nodes.size(); ++i) {
            v1 = this.path.nodes.get(i);
            float r = 1.0f;
            float g = 1.0f;
            float b = 0.0f;
            if (i == 0) {
                r = 0.0f;
                b = 1.0f;
            }
            LineDrawer.addLine((float)(v1.x - 0.05f), (float)(v1.y - 0.05f), (float)v1.z, (float)(v1.x + 0.05f), (float)(v1.y + 0.05f), (float)v1.z, (float)r, (float)1.0f, (float)b, null, (boolean)false);
        }
        PathFindBehavior2.closestPointOnPath(this.chr.getX(), this.chr.getY(), this.chr.getZ(), (IsoMovingObject)this.chr, this.path, pointOnPath);
        LineDrawer.addLine((float)(PathFindBehavior2.pointOnPath.x - 0.05f), (float)(PathFindBehavior2.pointOnPath.y - 0.05f), (float)this.chr.getZ(), (float)(PathFindBehavior2.pointOnPath.x + 0.05f), (float)(PathFindBehavior2.pointOnPath.y + 0.05f), (float)this.chr.getZ(), (float)0.0f, (float)1.0f, (float)0.0f, null, (boolean)false);
        for (i = 0; i < this.actualPos.size() - 1; ++i) {
            DebugPt v0 = this.actualPos.get(i);
            DebugPt v12 = this.actualPos.get(i + 1);
            LineDrawer.addLine((float)v0.x, (float)v0.y, (float)v0.z, (float)v12.x, (float)v12.y, (float)v12.z, (float)1.0f, (float)1.0f, (float)1.0f, null, (boolean)true);
            LineDrawer.addLine((float)(v0.x - 0.05f), (float)(v0.y - 0.05f), (float)v0.z, (float)(v0.x + 0.05f), (float)(v0.y + 0.05f), (float)v0.z, (float)1.0f, (float)(v0.climbing ? 1.0f : 0.0f), (float)0.0f, null, (boolean)false);
        }
    }

    public void Succeeded(Path path, Mover mover) {
        this.path.copyFrom(path);
        if (!this.isCancel) {
            this.chr.setPath2(this.path);
        }
        if (!path.isEmpty()) {
            PathNode node = path.nodes.get(path.nodes.size() - 1);
            this.targetX = node.x;
            this.targetY = node.y;
            this.targetZ = node.z;
            if (this.isGoalSitOnFurniture()) {
                this.fixSitOnFurniturePath(this.targetX, this.targetY);
            }
        }
        this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.found;
    }

    public void Failed(Mover mover) {
        this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.failed;
    }

    public boolean isMovingUsingPathFind() {
        return !this.stopping && !this.isGoalNone() && !this.isCancel;
    }

    public boolean isGoodChairAdjacentSquare(IsoGridSquare targetSquare, IsoGridSquare adjacentSquare) {
        return adjacentSquare != null && !adjacentSquare.isSolid() && !adjacentSquare.isSolidTrans() && (!adjacentSquare.getProperties().has(IsoFlagType.water) || adjacentSquare.hasFloorOverWater()) && adjacentSquare.canReachTo(targetSquare);
    }

    public static enum Goal {
        None,
        Character,
        Location,
        Sound,
        VehicleAdjacent,
        VehicleArea,
        VehicleSeat,
        SitOnFurniture,
        GrabCorpse;

    }

    @UsedFromLua
    public static enum BehaviorResult {
        Working,
        Failed,
        Succeeded;

    }

    private static final class DebugPt {
        float x;
        float y;
        float z;
        boolean climbing;

        private DebugPt() {
        }

        DebugPt init(float x, float y, float z, boolean climbing) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.climbing = climbing;
            return this;
        }
    }

    public static final class PointOnPath {
        int pathIndex;
        float dist;
        float x;
        float y;
    }
}
