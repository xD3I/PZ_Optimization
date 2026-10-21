/*
 * Decompiled with CFR 0.152.
 */
package zombie.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.VirtualZombieManager;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.ai.states.ZombieIdleState;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.ZombieGroup;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.zones.Zone;
import zombie.network.GameClient;
import zombie.network.GameServer;

public final class ZombieGroupManager {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.ai.ZombieGroupManager");
    }

    // pzopt: entityUpdateParallel. Every batched zombie's updateInternal calls update() here, so the frame
    // workers race the plain `groups` ArrayList (findNearestGroup iterates AND removes, update() adds and
    // removes members, preupdate() sweeps) and the shared tempVec2/tempVec3 scratch. Live failure: run
    // lou-replay-on frame 928, "Cannot read field idealSizeFactor because group is null" on a worker — a torn
    // slot mid-remove. Every group-touching section below runs under synchronized (this.groups), the same
    // idiom as the lccMain and soundList locks this repo already ships for PZMulticore's workers; uncontended
    // on the stock path, and the tick gate keeps the join block off 29 frames in 30.

    public static final ZombieGroupManager instance = new ZombieGroupManager();
    private final ArrayList<ZombieGroup> groups = new ArrayList();
    private final ArrayDeque<ZombieGroup> freeGroups = new ArrayDeque();
    private final Vector2 tempVec2 = new Vector2();
    private final Vector3 tempVec3 = new Vector3();
    private float tickCount = 30.0f;

    public void preupdate() {
        this.tickCount += GameTime.getInstance().getThirtyFPSMultiplier();
        if (this.tickCount >= 30.0f) {
            this.tickCount = 0.0f;
        }
        int groupSize = SandboxOptions.instance.zombieConfig.rallyGroupSize.getValue();
        if (pzopt.Config.INSTRUMENT || pzopt.Config.DEV_ZOMBIE_GROUP_CENSUS) { // pzopt: f9-burst census (one run in 17 spent ~16 % of its game thread in ZombieGroup.update)
            pzopt.ZombieGroupCensus.sample(this.groups, this.freeGroups.size()); // pzopt
        } // pzopt
        synchronized (this.groups) { // pzopt: entityUpdateParallel — the game thread's sweep compacts the same list the workers read; uncontended outside a batch window
        for (int i = 0; i < this.groups.size(); ++i) {
            ZombieGroup group = this.groups.get(i);
            group.update();
            if (!group.isEmpty()) continue;
            this.freeGroups.push(group);
            this.groups.remove(i--);
        }
        } // pzopt: entityUpdateParallel
        if (pzopt.Config.INSTRUMENT || pzopt.Config.DEV_ZOMBIE_GROUP_CENSUS) { // pzopt: f9-burst census, the sweep's time
            pzopt.ZombieGroupCensus.swept(); // pzopt
        } // pzopt
    }

    public void Reset() {
        synchronized (this.groups) { // pzopt: entityUpdateParallel — world load, uncontended; locked for completeness with the sweep and join
            this.freeGroups.addAll(this.groups);
            this.groups.clear();
        } // pzopt: entityUpdateParallel
    }

    public boolean shouldBeInGroup(IsoZombie zombie) {
        Zone zone;
        if (zombie == null) {
            return false;
        }
        if (SandboxOptions.instance.zombieConfig.rallyGroupSize.getValue() <= 1) {
            return false;
        }
        if (!Core.getInstance().isZombieGroupSound()) {
            return false;
        }
        if (zombie.isUseless()) {
            return false;
        }
        if (zombie.isDead() || zombie.isFakeDead()) {
            return false;
        }
        if (zombie.isSitAgainstWall()) {
            return false;
        }
        if (zombie.target != null) {
            return false;
        }
        if (zombie.getCurrentBuilding() != null) {
            return false;
        }
        if (VirtualZombieManager.instance.isReused(zombie)) {
            return false;
        }
        if (zombie.isReanimatedForGrappleOnly()) {
            return false;
        }
        IsoGridSquare sq = zombie.getSquare();
        Zone zone2 = zone = sq == null ? null : sq.getZone();
        return zone == null || !"Forest".equals(zone.getType()) && !"DeepForest".equals(zone.getType());
    }

    public void update(IsoZombie zombie) {
        if (GameClient.client && zombie.isRemoteZombie()) {
            return;
        }
        if (!this.shouldBeInGroup(zombie)) {
            if (zombie.group != null) {
                synchronized (this.groups) { // pzopt: entityUpdateParallel — membership only changes under the lock
                    zombie.group.remove(zombie);
                } // pzopt: entityUpdateParallel
            }
            return;
        }
        if (this.tickCount != 0.0f) {
            return;
        }
        synchronized (this.groups) { // pzopt: entityUpdateParallel — join, leader spread and member follow read and write the list, other groups' leaders and the shared tempVec scratch
        if (zombie.group == null) {
            ZombieGroup group = this.findNearestGroup(zombie.getX(), zombie.getY(), zombie.getZ());
            if (group == null) {
                group = this.freeGroups.isEmpty() ? new ZombieGroup() : this.freeGroups.pop().reset();
                group.add(zombie);
                this.groups.add(group);
                return;
            }
            group.add(zombie);
        }
        if (zombie.getCurrentState() != ZombieIdleState.instance()) {
            return;
        }
        if (zombie == zombie.group.getLeader()) {
            float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
            zombie.group.lastSpreadOutTime = Math.min(zombie.group.lastSpreadOutTime, worldAge);
            if (zombie.group.lastSpreadOutTime + 0.083333336f > worldAge) {
                return;
            }
            zombie.group.lastSpreadOutTime = worldAge;
            int groupSeparationDistance = SandboxOptions.instance.zombieConfig.rallyGroupSeparation.getValue();
            Vector2 c = this.tempVec2.set(0.0f, 0.0f);
            for (int i = 0; i < this.groups.size(); ++i) {
                ZombieGroup other = this.groups.get(i);
                if (other.getLeader() == null || other == zombie.group || PZMath.fastfloor(other.getLeader().getZ()) != PZMath.fastfloor(zombie.getZ())) continue;
                float otherX = other.getLeader().getX();
                float otherY = other.getLeader().getY();
                float dist = IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), otherX, otherY);
                if (dist > (float)(groupSeparationDistance * groupSeparationDistance)) continue;
                c.x = c.x - otherX + zombie.getX();
                c.y = c.y - otherY + zombie.getY();
            }
            int steps = this.lineClearCollideCount(zombie, zombie.getCell(), PZMath.fastfloor(zombie.getX() + c.x), PZMath.fastfloor(zombie.getY() + c.y), PZMath.fastfloor(zombie.getZ()), PZMath.fastfloor(zombie.getX()), PZMath.fastfloor(zombie.getY()), PZMath.fastfloor(zombie.getZ()), 10, this.tempVec3);
            if (steps < 1) {
                return;
            }
            if (!GameClient.client && !GameServer.server && IsoPlayer.getInstance().getHoursSurvived() < 2.0) {
                return;
            }
            if (this.tempVec3.x < 0.0f || this.tempVec3.y < 0.0f || !IsoWorld.instance.metaGrid.isValidChunk(PZMath.fastfloor(this.tempVec3.x) / 8, PZMath.fastfloor(this.tempVec3.y) / 8)) {
                return;
            }
            zombie.pathToLocation(PZMath.fastfloor(this.tempVec3.x + 0.5f), PZMath.fastfloor(this.tempVec3.y + 0.5f), PZMath.fastfloor(this.tempVec3.z));
            if (zombie.getCurrentState() == PathFindState.instance() || zombie.getCurrentState() == WalkTowardState.instance()) {
                zombie.setLastHeardSound(zombie.getPathTargetX(), zombie.getPathTargetY(), zombie.getPathTargetZ());
                zombie.allowRepathDelay = 400.0f;
            }
            return;
        }
        float leaderX = zombie.group.getLeader().getX();
        float leaderY = zombie.group.getLeader().getY();
        int memberDist = SandboxOptions.instance.zombieConfig.rallyGroupRadius.getValue();
        if (IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), leaderX, leaderY) < (float)(memberDist * memberDist)) {
            return;
        }
        if (!GameClient.client && !GameServer.server && IsoPlayer.getInstance().getHoursSurvived() < 2.0 && !Core.debug) {
            return;
        }
        int randX = PZMath.fastfloor(leaderX + (float)Rand.Next(-memberDist, memberDist));
        int randY = PZMath.fastfloor(leaderY + (float)Rand.Next(-memberDist, memberDist));
        if (randX < 0 || randY < 0 || !IsoWorld.instance.metaGrid.isValidChunk(randX / 8, randY / 8)) {
            return;
        }
        zombie.pathToLocation(randX, randY, PZMath.fastfloor(zombie.group.getLeader().getZ()));
        if (zombie.getCurrentState() == PathFindState.instance() || zombie.getCurrentState() == WalkTowardState.instance()) {
            zombie.setLastHeardSound(zombie.getPathTargetX(), zombie.getPathTargetY(), zombie.getPathTargetZ());
            zombie.allowRepathDelay = 400.0f;
        }
        } // pzopt: entityUpdateParallel — end of the tick-0 block's lock
    }

    public ZombieGroup findNearestGroup(float x, float y, float z) {
        synchronized (this.groups) { // pzopt: entityUpdateParallel — iterates and compacts the shared list; reentrant under update()'s lock
        ZombieGroup nearest = null;
        float minDist = Float.MAX_VALUE;
        int rallyDist = SandboxOptions.instance.zombieConfig.rallyTravelDistance.getValue();
        for (int i = 0; i < this.groups.size(); ++i) {
            float dist;
            ZombieGroup group = this.groups.get(i);
            int idealSize = (int)((float)SandboxOptions.instance.zombieConfig.rallyGroupSize.getValue() * group.idealSizeFactor);
            if (idealSize < 1) {
                idealSize = 1;
            }
            if (group.isEmpty()) {
                this.groups.remove(i--);
                continue;
            }
            if (PZMath.fastfloor(group.getLeader().getZ()) != PZMath.fastfloor(z) || group.size() >= idealSize || !((dist = IsoUtils.DistanceToSquared(x, y, group.getLeader().getX(), group.getLeader().getY())) < (float)(rallyDist * rallyDist)) || !(dist < minDist)) continue;
            minDist = dist;
            nearest = group;
        }
        return nearest;
        } // pzopt: entityUpdateParallel — end of findNearestGroup's lock
    }

    private int lineClearCollideCount(IsoMovingObject chr, IsoCell cell, int x1, int y1, int z1, int x0, int y0, int z0, int returnMin, Vector3 out) {
        int l = 0;
        int dy = y1 - y0;
        int dx = x1 - x0;
        int dz = z1 - z0;
        float t = 0.5f;
        float t2 = 0.5f;
        int lx = x0;
        int ly = y0;
        int lz = z0;
        IsoGridSquare b = cell.getGridSquare(lx, ly, lz);
        out.set(x0, y0, z0);
        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > Math.abs(dz)) {
            float m = (float)dy / (float)dx;
            float m2 = (float)dz / (float)dx;
            t += (float)y0;
            t2 += (float)z0;
            dx = dx < 0 ? -1 : 1;
            m *= (float)dx;
            m2 *= (float)dx;
            while (x0 != x1) {
                boolean bTest;
                IsoGridSquare a = cell.getGridSquare(x0 += dx, PZMath.fastfloor(t += m), PZMath.fastfloor(t2 += m2));
                if (a != null && b != null && (bTest = a.testCollideAdjacent(chr, b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()))) {
                    return l;
                }
                b = a;
                lx = x0;
                ly = PZMath.fastfloor(t);
                lz = PZMath.fastfloor(t2);
                out.set(lx, ly, lz);
                if (++l < returnMin) continue;
                return l;
            }
        } else if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) > Math.abs(dz)) {
            float m = (float)dx / (float)dy;
            float m2 = (float)dz / (float)dy;
            t += (float)x0;
            t2 += (float)z0;
            dy = dy < 0 ? -1 : 1;
            m *= (float)dy;
            m2 *= (float)dy;
            while (y0 != y1) {
                boolean bTest;
                IsoGridSquare a = cell.getGridSquare(PZMath.fastfloor(t += m), y0 += dy, PZMath.fastfloor(t2 += m2));
                if (a != null && b != null && (bTest = a.testCollideAdjacent(chr, b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()))) {
                    return l;
                }
                b = a;
                lx = PZMath.fastfloor(t);
                ly = y0;
                lz = PZMath.fastfloor(t2);
                out.set(lx, ly, lz);
                if (++l < returnMin) continue;
                return l;
            }
        } else {
            float m = (float)dx / (float)dz;
            float m2 = (float)dy / (float)dz;
            t += (float)x0;
            t2 += (float)y0;
            dz = dz < 0 ? -1 : 1;
            m *= (float)dz;
            m2 *= (float)dz;
            while (z0 != z1) {
                boolean bTest;
                IsoGridSquare a = cell.getGridSquare(PZMath.fastfloor(t += m), PZMath.fastfloor(t2 += m2), z0 += dz);
                if (a != null && b != null && (bTest = a.testCollideAdjacent(chr, b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()))) {
                    return l;
                }
                b = a;
                lx = PZMath.fastfloor(t);
                ly = PZMath.fastfloor(t2);
                lz = z0;
                out.set(lx, ly, lz);
                if (++l < returnMin) continue;
                return l;
            }
        }
        return l;
    }
}

