package zombie;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.ZombiesZoneDefinition;
import zombie.characters.action.ActionGroup;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemPickerJava.KeyNamer;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaChunk;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.Vector2;
import zombie.iso.RoomDef.RoomRect;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoFireManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.pathfind.PolygonalMap2;
import zombie.popman.NetworkZombieSimulator;
import zombie.popman.ZombiePopulationManager;
import zombie.scripting.objects.ItemKey.Container;
import zombie.vehicles.BaseVehicle;

@UsedFromLua
public final class VirtualZombieManager {
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.VirtualZombieManager"); // pzopt: marker so the game log shows the loose class was loaded
   }

   private final ArrayDeque<IsoZombie> reusableZombies = new ArrayDeque<>();
   private final HashSet<IsoZombie> reusableZombieSet = new HashSet<>();
   private final ArrayList<IsoZombie> reusedThisFrame = new ArrayList<>();
   private final ArrayList<IsoZombie> recentlyRemoved = new ArrayList<>();
   public static VirtualZombieManager instance = new VirtualZombieManager();
   public int maxRealZombies = 1;
   private final ArrayList<IsoZombie> tempZombies = new ArrayList<>();
   public final ArrayList<IsoGridSquare> choices = new ArrayList<>();
   private final ArrayList<IsoGridSquare> bestchoices = new ArrayList<>();
   HandWeapon w;
   private static final int BLOCKED_N = 1;
   private static final int BLOCKED_S = 2;
   private static final int BLOCKED_W = 4;
   private static final int BLOCKED_E = 8;
   private static final int NO_SQUARE_N = 16;
   private static final int NO_SQUARE_S = 32;
   private static final int NO_SQUARE_W = 64;
   private static final int NO_SQUARE_E = 128;

   public float getKeySpawnChanceD100() {
      float chance = (float)SandboxOptions.getInstance().keyLootNew.getValue() * 50.0F;
      if (chance > 90.0F) {
         chance = 90.0F;
      }

      return chance;
   }

   public boolean removeZombieFromWorld(IsoZombie z) {
      boolean b = z.getCurrentSquare() != null;
      z.getEmitter().unregister();
      z.removeFromWorld();
      z.removeFromSquare();
      return b;
   }

   private void reuseZombie(IsoZombie z) {
      if (z != null) {
         assert !IsoWorld.instance.currentCell.getObjectList().contains(z);
         assert !IsoWorld.instance.currentCell.getZombieList().contains(z);
         assert z.getCurrentSquare() == null || !z.getCurrentSquare().getMovingObjects().contains(z);
         if (!this.reusableZombieSet.contains(z)) { // pzopt: zombieReuseSpread, the pool itself (isReused also answers for the queue)
            NetworkZombieSimulator.getInstance().remove(z);
            z.resetForReuse();
            this.addToReusable(z);
         }
      }
   }

   public void addToReusable(IsoZombie z) {
      if (z != null && !this.reusableZombieSet.contains(z)) {
         this.reusableZombies.addLast(z);
         this.reusableZombieSet.add(z);
      }
   }

   public boolean isReused(IsoZombie z) {
      return this.reusableZombieSet.contains(z) || pzopt.ReuseSpread.pending(z); // pzopt: zombieReuseSpread, a zombie queued for its reset counts as reused, as stock's would by the end of its frame
   }

   public void init() {
      if (!GameClient.client) {
         if (!IsoWorld.getZombiesDisabled()) {
            for (int n = 0; n < this.maxRealZombies; n++) {
               IsoZombie zombie = new IsoZombie(IsoWorld.instance.currentCell);
               zombie.getEmitter().unregister();
               this.addToReusable(zombie);
            }
         }
      }
   }

   public void Reset() {
      this.reusedThisFrame.addAll(pzopt.ReuseSpread.takeAll()); // pzopt: zombieReuseSpread, the queued zombies get the frame list's clean-up
      for (IsoZombie zombie : this.reusedThisFrame) {
         if (zombie.vocalEvent != 0L) {
            zombie.getEmitter().stopSoundLocal(zombie.vocalEvent);
            zombie.vocalEvent = 0L;
         }

         zombie.getAdvancedAnimator().reset();
         zombie.releaseAnimationPlayer();
      }

      this.bestchoices.clear();
      this.choices.clear();
      this.recentlyRemoved.clear();
      this.reusableZombies.clear();
      this.reusableZombieSet.clear();
      this.reusedThisFrame.clear();
   }

   public void update() {
      long pzoptT = pzopt.GtAb.begin(); // pzopt: Louisville 120 item 3 section timer
      try { // pzopt
         this.pzoptUpdateBody(); // pzopt
      } finally { // pzopt
         pzopt.GtAb.end(pzopt.GtAb.S_VZM, pzoptT); // pzopt
      } // pzopt
   }

   /** pzopt: the stock body of update (the section timer wraps it). */
   private void pzoptUpdateBody() {
      long currentMS = System.currentTimeMillis();

      for (int i = this.recentlyRemoved.size() - 1; i >= 0; i--) {
         IsoZombie zombie = this.recentlyRemoved.get(i);
         zombie.updateEmitter();
         if (currentMS - zombie.removedFromWorldMs > 5000L) {
            if (zombie.vocalEvent != 0L) {
               zombie.getEmitter().stopSoundLocal(zombie.vocalEvent);
               zombie.vocalEvent = 0L;
            }

            zombie.getEmitter().stopAll();
            this.recentlyRemoved.remove(i);
            this.reusedThisFrame.add(zombie);
         }
      }

      if (!GameClient.client && !GameServer.server) {
         for (int f = 0; f < IsoWorld.instance.currentCell.getZombieList().size(); f++) {
            IsoZombie z = (IsoZombie)IsoWorld.instance.currentCell.getZombieList().get(f);
            if (!z.keepItReal && z.getCurrentSquare() == null) {
               z.removeFromWorld();
               z.removeFromSquare();
               assert this.reusedThisFrame.contains(z);
               assert !IsoWorld.instance.currentCell.getZombieList().contains(z);
               f--;
            }
         }

         if (pzopt.ReuseSpread.enabled()) { // pzopt: zombieReuseSpread, queued and reset under a per-frame budget
            for (int i = 0; i < this.reusedThisFrame.size(); i++) { // pzopt: zombieReuseSpread
               pzopt.ReuseSpread.queue(this.reusedThisFrame.get(i)); // pzopt: zombieReuseSpread
            } // pzopt: zombieReuseSpread
            this.reusedThisFrame.clear(); // pzopt: zombieReuseSpread
            pzopt.ReuseSpread.drain(this::reuseZombie); // pzopt: zombieReuseSpread
            return; // pzopt: zombieReuseSpread
         } // pzopt: zombieReuseSpread
         for (int i = 0; i < this.reusedThisFrame.size(); i++) {
            IsoZombie z = this.reusedThisFrame.get(i);
            this.reuseZombie(z);
         }

         this.reusedThisFrame.clear();
      } else {
         for (int i = 0; i < this.reusedThisFrame.size(); i++) {
            IsoZombie z = this.reusedThisFrame.get(i);
            this.reuseZombie(z);
         }

         this.reusedThisFrame.clear();
      }
   }

   public IsoZombie createRealZombieAlways(IsoDirections dir, boolean bDead) {
      return this.createRealZombieAlways(dir, bDead, 0, 0);
   }

   public IsoZombie createRealZombieAlways(int descriptorId, IsoDirections dir, boolean bDead, int persistentId) {
      int outfitID = PersistentOutfits.instance.getOutfit(descriptorId);
      return this.createRealZombieAlways(dir, bDead, outfitID, persistentId);
   }

   public IsoZombie createRealZombieAlways(IsoDirections dir, boolean bDead, int outfitID, int persistentId) {
      IsoZombie zombie = this.createZombieOutsideWorld(dir, bDead, outfitID);
      if (zombie == null) {
         return null;
      }

      if (bDead) {
         zombie.DoZombieInventory();
         new IsoDeadBody(zombie, true);
         return zombie;
      }

      LuaEventManager.triggerEvent("OnZombieCreate", zombie);
      synchronized (IsoWorld.instance.currentCell.getZombieList()) {
         zombie.getEmitter().register();
         IsoWorld.instance.currentCell.getZombieList().add(zombie);
         if (GameClient.client) {
            zombie.remote = true;
         }

         if (GameServer.server) {
            zombie.onlineId = ServerMap.instance.getUniqueZombieId();
            if (zombie.onlineId == -1) {
               IsoWorld.instance.currentCell.getZombieList().remove(zombie);
               IsoWorld.instance.currentCell.getObjectList().remove(zombie);
               this.reusedThisFrame.add(zombie);
               return null;
            }

            ServerMap.instance.zombieMap.put(zombie.onlineId, zombie);
         }

         zombie.persistentId = ZombiePopulationManager.registerZombie(zombie, persistentId);
         this.trySpawnBuildingKey(zombie);
         return zombie;
      }
   }

   public IsoZombie createCorpseZombie(IsoDirections dir) {
      IsoZombie zombie = this.createZombieOutsideWorld(dir, true, 0);
      if (zombie == null) {
         return null;
      }

      this.trySpawnBuildingKey(zombie);
      return zombie;
   }

   private void trySpawnBuildingKey(IsoZombie zombie) {
      int roll = Math.min(Rand.Next(100), Rand.Next(100));
      if (roll < this.getKeySpawnChanceD100()) {
         this.checkAndSpawnZombieForBuildingKey(zombie);
      }
   }

   private IsoZombie createZombieOutsideWorld(IsoDirections dir, boolean bDead, int outfitID) {
      if (!SystemDisabler.doZombieCreation) {
         return null;
      }

      if (this.choices != null && !this.choices.isEmpty()) {
         IsoGridSquare choice = this.choices.get(Rand.Next(this.choices.size()));
         if (choice == null) {
            return null;
         }

         if (choice.isWaterSquare()) {
            return null;
         }

         if (this.w == null) {
            this.w = (HandWeapon)InventoryItemFactory.CreateItem("Base.Axe");
         }

         if ((GameServer.server || GameClient.client) && outfitID == 0) {
            outfitID = ZombiesZoneDefinition.pickPersistentOutfit(choice);
         }

         IsoZombie zombie;
         if (this.reusableZombies.isEmpty() && pzopt.ReuseSpread.enabled()) { // pzopt: zombieReuseSpread, the pool is empty: reset a queued zombie first
            pzopt.ReuseSpread.resetOne(this::reuseZombie); // pzopt: zombieReuseSpread
         } // pzopt: zombieReuseSpread
         if (this.reusableZombies.isEmpty()) {
            zombie = new IsoZombie(IsoWorld.instance.currentCell);
            zombie.dressInRandomOutfit = outfitID == 0;
            zombie.setPersistentOutfitID(outfitID);
            IsoWorld.instance.currentCell.getObjectList().add(zombie);
         } else {
            zombie = this.reusableZombies.removeFirst();
            this.reusableZombieSet.remove(zombie);
            zombie.getHumanVisual().clear();
            zombie.clearAttachedItems();
            zombie.clearItemsToSpawnAtDeath();
            zombie.dressInRandomOutfit = outfitID == 0;
            zombie.setPersistentOutfitID(outfitID);
            zombie.setSitAgainstWall(false);
            zombie.setOnDeathDone(false);
            zombie.setOnKillDone(false);
            zombie.setDoDeathSound(true);
            zombie.setKilledByFall(false);
            zombie.setHitTime(0);
            zombie.setFallOnFront(false);
            zombie.setFakeDead(false);
            zombie.setReanimatedPlayer(false);
            zombie.setStateMachineLocked(false);
            zombie.setDoRender(true);
            zombie.clearDiedBody();
            zombie.setDirectionAngle(Rand.Next(360));
            IsoWorld.instance.currentCell.getObjectList().add(zombie);
            zombie.walkVariant = "ZombieWalk";
            zombie.speedType = -1;
            zombie.inactive = GameTime.getInstance().isZombieInactivityPhase();
            zombie.DoZombieStats();
            if (zombie.isOnFire()) {
               IsoFireManager.RemoveBurningCharacter(zombie);
               zombie.setOnFire(false);
            }

            if (zombie.attachedAnimSprite != null) {
               zombie.attachedAnimSprite.clear();
            }

            zombie.thumpFlag = 0;
            zombie.thumpSent = false;
            zombie.soundSourceTarget = null;
            zombie.soundAttract = 0.0F;
            zombie.soundAttractTimeout = 0.0F;
            zombie.bodyToEat = null;
            zombie.eatBodyTarget = null;
            zombie.atlasTex = null;
            zombie.clearVariables();
            zombie.setStaggerBack(false);
            zombie.setKnockedDown(false);
            zombie.setKnifeDeath(false);
            zombie.setJawStabAttach(false);
            zombie.setCrawler(false);
            zombie.initializeStates();
            zombie.getActionContext().setGroup(ActionGroup.getActionGroup("zombie"));
            zombie.getAdvancedAnimator().OnAnimDataChanged(false);
            zombie.setDefaultState();
            zombie.getAnimationPlayer().resetBoneModelTransforms();
         }

         zombie.persistentId = 0;
         zombie.setForwardIsoDirection(dir);
         zombie.getInventory().setExplored(false);
         if (bDead) {
            zombie.dressInRandomOutfit = true;
         }

         zombie.target = null;
         zombie.timeSinceSeenFlesh = 100000.0F;
         if (!zombie.isFakeDead()) {
            if (SandboxOptions.instance.lore.toughness.getValue() == 1) {
               zombie.setHealth(3.5F + Rand.Next(0.0F, 0.3F));
            }

            if (SandboxOptions.instance.lore.toughness.getValue() == 2) {
               zombie.setHealth(1.5F + Rand.Next(0.0F, 0.3F));
            }

            if (SandboxOptions.instance.lore.toughness.getValue() == 3) {
               zombie.setHealth(0.5F + Rand.Next(0.0F, 0.3F));
            }

            if (SandboxOptions.instance.lore.toughness.getValue() == 4) {
               zombie.setHealth(Rand.Next(0.5F, 3.5F) + Rand.Next(0.0F, 0.3F));
            }
         } else {
            zombie.setHealth(0.5F + Rand.Next(0.0F, 0.3F));
         }

         float specX = Rand.Next(0, 1000);
         float specY = Rand.Next(0, 1000);
         specX /= 1000.0F;
         specY /= 1000.0F;
         specX += choice.getX();
         specY += choice.getY();
         zombie.setCurrent(choice);
         zombie.setMovingSquareNow();
         zombie.setX(specX);
         zombie.setY(specY);
         zombie.setZ(choice.getZ());
         if ((GameClient.client || GameServer.server) && zombie.getNetworkCharacterAI() != null) {
            zombie.getNetworkCharacterAI().reset();
            zombie.getPathFindBehavior2().reset();
         }

         if (bDead) {
            zombie.setDir(IsoDirections.getRandom());
            zombie.setForwardDirection(zombie.getForwardIsoDirection().ToVector());
            zombie.setFakeDead(false);
            zombie.setHealth(0.0F);
         }

         return zombie;
      } else {
         return null;
      }
   }

   private IsoGridSquare pickEatingZombieSquare(float bodyX, float bodyY, float zombieX, float zombieY, int z) {
      IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(zombieX, zombieY, z);
      if (square == null || !this.canSpawnAt(square.x, square.y, square.z) || square.HasStairs()) {
         return null;
      } else {
         return PolygonalMap2.instance.lineClearCollide(bodyX, bodyY, zombieX, zombieY, z, null, false, true) ? null : square;
      }
   }

   public void createEatingZombies(IsoDeadBody target, int nb) {
      if (!IsoWorld.getZombiesDisabled()) {
         for (int i = 0; i < nb; i++) {
            float zombieX = target.getX();
            float zombieY = target.getY();
            switch (i) {
               case 0:
                  zombieX -= 0.5F;
                  break;
               case 1:
                  zombieX += 0.5F;
                  break;
               case 2:
                  zombieY -= 0.5F;
                  break;
               case 3:
                  zombieY += 0.5F;
            }

            IsoGridSquare square = this.pickEatingZombieSquare(target.getX(), target.getY(), zombieX, zombieY, PZMath.fastfloor(target.getZ()));
            if (square != null) {
               this.choices.clear();
               this.choices.add(square);
               IsoZombie zombie = this.createRealZombieAlways(IsoDirections.NW, false);
               if (zombie != null) {
                  ZombieSpawnRecorder.instance.record(zombie, "createEatingZombies");
                  zombie.dressInRandomOutfit = true;
                  zombie.setX(zombieX);
                  zombie.setY(zombieY);
                  zombie.setZ(target.getZ());
                  zombie.faceLocationF(target.getX(), target.getY());
                  zombie.setEatBodyTarget(target, true);
               }
            }
         }
      }
   }

   private IsoZombie createRealZombie(IsoDirections dir, boolean bDead) {
      return GameClient.client ? null : this.createRealZombieAlways(dir, bDead);
   }

   public void AddBloodToMap(int nSize, IsoChunk chk) {
      for (int n = 0; n < nSize; n++) {
         int timeout = 0;

         IsoGridSquare sq;
         do {
            int x = Rand.Next(10);
            int y = Rand.Next(10);
            sq = chk.getGridSquare(x, y, 0);
            timeout++;
         } while (timeout < 100 && (sq == null || !sq.isFree(false)));

         if (sq != null) {
            int amount = 5;
            if (Rand.Next(10) == 0) {
               amount = 10;
            }

            if (Rand.Next(40) == 0) {
               amount = 20;
            }

            for (int m = 0; m < amount; m++) {
               float rx = Rand.Next(3000) / 1000.0F;
               float ry = Rand.Next(3000) / 1000.0F;
               rx -= 1.5F;
               ry -= 1.5F;
               chk.addBloodSplat(sq.getX() + rx, sq.getY() + ry, sq.getZ(), Rand.Next(12) + 8);
            }
         }
      }
   }

   public boolean shouldSpawnZombiesOnLevel(int level) {
      if (GameServer.server) {
         ArrayList<IsoPlayer> players = GameServer.getPlayers();

         for (int i = 0; i < players.size(); i++) {
            IsoPlayer player = players.get(i);
            if (PZMath.abs(level - PZMath.fastfloor(player.getZ())) <= 1) {
               return true;
            }
         }

         return false;
      } else {
         if (GameClient.client) {
            return false;
         }

         for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
            IsoPlayer player = IsoPlayer.players[playerIndex];
            if (player != null && PZMath.abs(level - PZMath.fastfloor(player.getZ())) <= 1) {
               return true;
            }
         }

         return false;
      }
   }

   public ArrayList<IsoZombie> addZombiesToMap(int nSize, RoomDef room) {
      return this.addZombiesToMap(nSize, room, true);
   }

   public ArrayList<IsoZombie> addZombiesToMap(int nSize, RoomDef room, boolean bAllowDead) {
      ArrayList<IsoZombie> result = new ArrayList<>();
      if (Core.getInstance().isTutorial()) {
         return result;
      }

      if (IsoWorld.getZombiesDisabled()) {
         return result;
      }

      this.choices.clear();
      this.bestchoices.clear();

      for (int n = 0; n < room.rects.size(); n++) {
         int z = room.level;
         RoomRect r = (RoomRect)room.rects.get(n);

         for (int x = r.x; x < r.getX2(); x++) {
            for (int y = r.y; y < r.getY2(); y++) {
               IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
               if (sq != null && this.canSpawnAt(x, y, z)) {
                  this.choices.add(sq);
                  boolean seen = false;

                  for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                     if (IsoPlayer.players[playerIndex] != null && sq.isSeen(playerIndex)) {
                        seen = true;
                     }
                  }

                  if (!seen) {
                     this.bestchoices.add(sq);
                  }
               }
            }
         }
      }

      nSize = Math.min(nSize, this.choices.size());
      if (!this.bestchoices.isEmpty()) {
         this.choices.addAll(this.bestchoices);
         this.choices.addAll(this.bestchoices);
      }

      for (int n = 0; n < nSize; n++) {
         if (!this.choices.isEmpty()) {
            room.building.alarmed = false;
            int randomDeadZed = 4;
            IsoZombie z = this.createRealZombie(IsoDirections.getRandom(), bAllowDead ? Rand.Next(4) == 0 : false);
            if (z != null && z.getSquare() != null) {
               if (!GameServer.server) {
                  z.dressInRandomOutfit = true;
               }

               z.setX(PZMath.fastfloor(z.getX()) + Rand.Next(2, 8) / 10.0F);
               z.setY(PZMath.fastfloor(z.getY()) + Rand.Next(2, 8) / 10.0F);
               this.choices.remove(z.getSquare());
               this.choices.remove(z.getSquare());
               this.choices.remove(z.getSquare());
               result.add(z);
            }
         } else {
            System.out.println("No choices for zombie.");
         }
      }

      this.bestchoices.clear();
      this.choices.clear();
      return result;
   }

   public void tryAddIndoorZombies(RoomDef room, boolean bAllowDead) {
   }

   private void addIndoorZombies(int nSize, RoomDef room, boolean bAllowDead) {
      this.choices.clear();
      this.bestchoices.clear();

      for (int n = 0; n < room.rects.size(); n++) {
         int z = room.level;
         RoomRect r = (RoomRect)room.rects.get(n);

         for (int x = r.x; x < r.getX2(); x++) {
            for (int y = r.y; y < r.getY2(); y++) {
               IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
               if (sq != null && this.canSpawnAt(x, y, z)) {
                  this.choices.add(sq);
               }
            }
         }
      }

      nSize = Math.min(nSize, this.choices.size());
      if (!this.bestchoices.isEmpty()) {
         this.choices.addAll(this.bestchoices);
         this.choices.addAll(this.bestchoices);
      }

      for (int n = 0; n < nSize; n++) {
         if (!this.choices.isEmpty()) {
            room.building.alarmed = false;
            int randomDeadZed = 4;
            IsoZombie z = this.createRealZombie(IsoDirections.getRandom(), bAllowDead ? Rand.Next(4) == 0 : false);
            if (z != null && z.getSquare() != null) {
               ZombieSpawnRecorder.instance.record(z, "addIndoorZombies");
               z.indoorZombie = true;
               z.setX(PZMath.fastfloor(z.getX()) + Rand.Next(2, 8) / 10.0F);
               z.setY(PZMath.fastfloor(z.getY()) + Rand.Next(2, 8) / 10.0F);
               this.choices.remove(z.getSquare());
               this.choices.remove(z.getSquare());
               this.choices.remove(z.getSquare());
            }
         } else {
            System.out.println("No choices for zombie.");
         }
      }

      this.bestchoices.clear();
      this.choices.clear();
   }

   public void addIndoorZombiesToChunk(IsoChunk chunk, IsoRoom room, int zombieCountForRoom, ArrayList<IsoZombie> zombies) {
      if (zombieCountForRoom > 0) {
         float areaPercent = room.getRoomDef().getAreaOverlapping(chunk);
         int count = (int)Math.ceil(zombieCountForRoom * areaPercent);
         if (count > 0) {
            int chunksPerWidth = 8;
            this.choices.clear();
            int z = room.def.level;

            for (int i = 0; i < room.rects.size(); i++) {
               RoomRect roomRect = (RoomRect)room.rects.get(i);
               int x1 = Math.max(chunk.wx * 8, roomRect.x);
               int y1 = Math.max(chunk.wy * 8, roomRect.y);
               int x2 = Math.min((chunk.wx + 1) * 8, roomRect.x + roomRect.w);
               int y2 = Math.min((chunk.wy + 1) * 8, roomRect.y + roomRect.h);

               for (int x = x1; x < x2; x++) {
                  for (int y = y1; y < y2; y++) {
                     IsoGridSquare sq = chunk.getGridSquare(x - chunk.wx * 8, y - chunk.wy * 8, z);
                     if (sq != null && this.canSpawnAt(x, y, z)) {
                        this.choices.add(sq);
                     }
                  }
               }
            }

            if (!this.choices.isEmpty()) {
               room.def.building.alarmed = false;
               count = Math.min(count, this.choices.size());

               for (int i = 0; i < count; i++) {
                  IsoZombie zombie = this.createRealZombie(IsoDirections.getRandom(), false);
                  if (zombie != null && zombie.getSquare() != null) {
                     if (!GameServer.server) {
                        zombie.dressInRandomOutfit = true;
                     }

                     zombie.setX(PZMath.fastfloor(zombie.getX()) + Rand.Next(2, 8) / 10.0F);
                     zombie.setY(PZMath.fastfloor(zombie.getY()) + Rand.Next(2, 8) / 10.0F);
                     this.choices.remove(zombie.getSquare());
                     zombies.add(zombie);
                  }
               }

               this.choices.clear();
            }
         }
      }
   }

   public void addIndoorZombiesToChunk(IsoChunk chunk, IsoRoom room) {
      if (room.def.spawnCount == -1) {
         room.def.spawnCount = this.getZombieCountForRoom(room);
      }

      this.tempZombies.clear();
      this.addIndoorZombiesToChunk(chunk, room, room.def.spawnCount, this.tempZombies);
      ZombieSpawnRecorder.instance.record(this.tempZombies, "addIndoorZombiesToChunk");
   }

   public void addDeadZombiesToMap(int nSize, RoomDef room) {
      this.choices.clear();
      this.bestchoices.clear();

      for (int n = 0; n < room.rects.size(); n++) {
         int z = room.level;
         RoomRect r = (RoomRect)room.rects.get(n);

         for (int x = r.x; x < r.getX2(); x++) {
            for (int y = r.y; y < r.getY2(); y++) {
               IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
               if (sq != null && sq.isFree(false)) {
                  this.choices.add(sq);
                  if (!GameServer.server) {
                     boolean seen = false;

                     for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                        if (IsoPlayer.players[playerIndex] != null && sq.isSeen(playerIndex)) {
                           seen = true;
                        }
                     }

                     if (!seen) {
                        this.bestchoices.add(sq);
                     }
                  }
               }
            }
         }
      }

      nSize = Math.min(nSize, this.choices.size());
      if (!this.bestchoices.isEmpty()) {
         this.choices.addAll(this.bestchoices);
         this.choices.addAll(this.bestchoices);
      }

      for (int n = 0; n < nSize; n++) {
         if (!this.choices.isEmpty()) {
            this.createRealZombie(IsoDirections.getRandom(), true);
         }
      }

      this.bestchoices.clear();
      this.choices.clear();
   }

   public void RemoveZombie(IsoZombie obj) {
      if (obj.isReanimatedPlayer()) {
         if (obj.vocalEvent != 0L) {
            obj.getEmitter().stopSoundLocal(obj.vocalEvent);
            obj.vocalEvent = 0L;
         }

         ReanimatedPlayers.instance.removeReanimatedPlayerFromWorld(obj);
      } else {
         if (obj.isDead()) {
            if (!this.recentlyRemoved.contains(obj)) {
               obj.removedFromWorldMs = System.currentTimeMillis();
               this.recentlyRemoved.add(obj);
            }
         } else if (!this.reusedThisFrame.contains(obj)) {
            this.reusedThisFrame.add(obj);
         }
      }
   }

   public void createHordeFromTo(float spawnX, float spawnY, float targetX, float targetY, int count) {
      ZombiePopulationManager.instance
         .createHordeFromTo(PZMath.fastfloor(spawnX), PZMath.fastfloor(spawnY), PZMath.fastfloor(targetX), PZMath.fastfloor(targetY), count);
   }

   public IsoZombie createRealZombie(float x, float y, float z) {
      this.choices.clear();
      this.choices.add(IsoWorld.instance.currentCell.getGridSquare(x, y, z));
      return !this.choices.isEmpty() ? this.createRealZombie(IsoDirections.getRandom(), true) : null;
   }

   public IsoZombie createRealZombieNow(float x, float y, float z) {
      this.choices.clear();
      IsoGridSquare c = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
      if (c == null) {
         return null;
      }

      this.choices.add(c);
      return !this.choices.isEmpty() ? this.createRealZombie(IsoDirections.getRandom(), false) : null;
   }

   private int getZombieCountForRoom(IsoRoom room) {
      if (IsoWorld.getZombiesDisabled()) {
         return 0;
      }

      if (GameClient.client) {
         return 0;
      }

      if (room.def != null && room.def.isEmptyOutside()) {
         return 0;
      }

      int ra = 7;
      if (SandboxOptions.instance.zombies.getValue() == 1) {
         ra = 3;
      } else if (SandboxOptions.instance.zombies.getValue() == 2) {
         ra = 4;
      } else if (SandboxOptions.instance.zombies.getValue() == 3) {
         ra = 6;
      } else if (SandboxOptions.instance.zombies.getValue() == 5) {
         ra = 15;
      }

      float zombieDensity = 0.0F;
      IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(room.def.x / 8, room.def.y / 8);
      if (metaChunk != null) {
         zombieDensity = metaChunk.getLootZombieIntensity();
         if (zombieDensity > 4.0F) {
            ra = (int)(ra - (zombieDensity / 2.0F - 2.0F));
         }
      }

      if (room.def.getArea() > 100) {
         ra -= 2;
      }

      ra = Math.max(2, ra);
      if (room.getBuilding() != null) {
         int roomArea = room.def.getArea();
         if (room.getBuilding().getRoomsNumber() > 100 && roomArea >= 20) {
            int number = room.getBuilding().getRoomsNumber() - 95;
            if (number > 20) {
               number = 20;
            }

            if (SandboxOptions.instance.zombies.getValue() == 1) {
               number += 10;
            } else if (SandboxOptions.instance.zombies.getValue() == 2) {
               number += 7;
            } else if (SandboxOptions.instance.zombies.getValue() == 3) {
               number += 5;
            } else if (SandboxOptions.instance.zombies.getValue() == 5) {
               number -= 10;
            }

            if (roomArea < 30) {
               number -= 6;
            }

            if (roomArea < 50) {
               number -= 10;
            }

            if (roomArea < 70) {
               number -= 13;
            }

            return Rand.Next(number, number + 10);
         }
      }

      if (Rand.Next(ra) == 0) {
         int baseNbr = 1;
         baseNbr = (int)(baseNbr + (zombieDensity / 2.0F - 2.0F));
         if (room.def.getArea() < 30) {
            baseNbr -= 4;
         }

         if (room.def.getArea() > 85) {
            baseNbr += 2;
         }

         if (room.getBuilding().getRoomsNumber() < 7) {
            baseNbr -= 2;
         }

         if (SandboxOptions.instance.zombies.getValue() == 1) {
            baseNbr += 3;
         } else if (SandboxOptions.instance.zombies.getValue() == 2) {
            baseNbr += 2;
         } else if (SandboxOptions.instance.zombies.getValue() == 3) {
            baseNbr++;
         } else if (SandboxOptions.instance.zombies.getValue() == 5) {
            baseNbr -= 2;
         }

         baseNbr = Math.max(0, baseNbr);
         baseNbr = Math.min(7, baseNbr);
         return Rand.Next(baseNbr, baseNbr + 2);
      } else {
         return 0;
      }
   }

   public void roomSpotted(IsoRoom room) {
      if (!GameClient.client) {
         room.def.forEachChunk((roomDef, chunk) -> chunk.addSpawnedRoom(roomDef.id));
         if (room.def.spawnCount == -1) {
            room.def.spawnCount = this.getZombieCountForRoom(room);
         }

         if (room.def.spawnCount > 0) {
            if (room.getBuilding().getDef().isFullyStreamedIn()) {
               ArrayList<IsoZombie> zombies = this.addZombiesToMap(room.def.spawnCount, room.def, false);
               ZombieSpawnRecorder.instance.record(zombies, "roomSpotted");
            } else {
               this.tempZombies.clear();
               room.def.forEachChunk((roomDef, chunk) -> this.addIndoorZombiesToChunk(chunk, room, room.def.spawnCount, this.tempZombies));
               ZombieSpawnRecorder.instance.record(this.tempZombies, "roomSpotted");
            }
         }
      }
   }

   private int getBlockedBits(IsoGridSquare sq) {
      int bits = 0;
      if (sq == null) {
         return bits;
      }

      if (sq.getAdjacentSquare(IsoDirections.N) == null) {
         bits |= 16;
      } else if (IsoGridSquare.getMatrixBit(sq.pathMatrix, 1, 0, 1)) {
         bits |= 1;
      }

      if (sq.getAdjacentSquare(IsoDirections.S) == null) {
         bits |= 32;
      } else if (IsoGridSquare.getMatrixBit(sq.pathMatrix, 1, 2, 1)) {
         bits |= 2;
      }

      if (sq.getAdjacentSquare(IsoDirections.W) == null) {
         bits |= 64;
      } else if (IsoGridSquare.getMatrixBit(sq.pathMatrix, 0, 1, 1)) {
         bits |= 4;
      }

      if (sq.getAdjacentSquare(IsoDirections.E) == null) {
         bits |= 128;
      } else if (IsoGridSquare.getMatrixBit(sq.pathMatrix, 2, 1, 1)) {
         bits |= 8;
      }

      return bits;
   }

   private boolean isBlockedInAllDirections(int x, int y, int z) {
      IsoGridSquare sq = GameServer.server ? ServerMap.instance.getGridSquare(x, y, z) : IsoWorld.instance.currentCell.getGridSquare(x, y, z);
      if (sq == null) {
         return false;
      }

      boolean blockedN = IsoGridSquare.getMatrixBit(sq.pathMatrix, 1, 0, 1) && sq.getAdjacentSquare(IsoDirections.N) != null;
      boolean blockedS = IsoGridSquare.getMatrixBit(sq.pathMatrix, 1, 2, 1) && sq.getAdjacentSquare(IsoDirections.S) != null;
      boolean blockedW = IsoGridSquare.getMatrixBit(sq.pathMatrix, 0, 1, 1) && sq.getAdjacentSquare(IsoDirections.W) != null;
      boolean blockedE = IsoGridSquare.getMatrixBit(sq.pathMatrix, 2, 1, 1) && sq.getAdjacentSquare(IsoDirections.E) != null;
      return blockedN && blockedS && blockedW && blockedE;
   }

   private boolean canPathOnlyN(IsoGridSquare sq) {
      while (true) {
         int bits = this.getBlockedBits(sq);
         if ((bits & 12) != 12) {
            return false;
         }

         if ((bits & 16) != 0) {
            return false;
         }

         if ((bits & 1) != 0) {
            return true;
         }

         sq = sq.getAdjacentSquare(IsoDirections.N);
      }
   }

   private boolean canPathOnlyS(IsoGridSquare sq) {
      while (true) {
         int bits = this.getBlockedBits(sq);
         if ((bits & 12) != 12) {
            return false;
         }

         if ((bits & 32) != 0) {
            return false;
         }

         if ((bits & 2) != 0) {
            return true;
         }

         sq = sq.getAdjacentSquare(IsoDirections.S);
      }
   }

   private boolean canPathOnlyW(IsoGridSquare sq) {
      while (true) {
         int bits = this.getBlockedBits(sq);
         if ((bits & 3) != 3) {
            return false;
         }

         if ((bits & 64) != 0) {
            return false;
         }

         if ((bits & 4) != 0) {
            return true;
         }

         sq = sq.getAdjacentSquare(IsoDirections.W);
      }
   }

   private boolean canPathOnlyE(IsoGridSquare sq) {
      while (true) {
         int bits = this.getBlockedBits(sq);
         if ((bits & 3) != 3) {
            return false;
         }

         if ((bits & 128) != 0) {
            return false;
         }

         if ((bits & 8) != 0) {
            return true;
         }

         sq = sq.getAdjacentSquare(IsoDirections.E);
      }
   }

   public boolean canSpawnAt(int x, int y, int z) {
      IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
      if (sq != null && sq.isFree(false)) {
         int bits = this.getBlockedBits(sq);
         if (bits == 15) {
            return false;
         } else {
            return (bits & 3) == 3 && this.canPathOnlyW(sq) && this.canPathOnlyE(sq)
               ? false
               : (bits & 12) != 12 || !this.canPathOnlyN(sq) || !this.canPathOnlyS(sq);
         }
      } else {
         return false;
      }
   }

   public int reusableZombiesSize() {
      return this.reusableZombies.size();
   }

   public boolean checkZombieKeyForBuilding(String outfitName, IsoGridSquare square) {
      if (outfitName == null) {
         return false;
      }

      if (square == null) {
         return false;
      }

      if (square.getBuilding() == null) {
         return false;
      }

      if (outfitName.contains("Survivalist") || outfitName.equals("Security")) {
         return true;
      }

      if (outfitName.equals("Young") || outfitName.equals("Student")) {
         return square.getBuilding().getRandomRoom("bedroom") != null
            && square.getBuilding().getRandomRoom("livingroom") != null
            && square.getBuilding().getRandomRoom("kitchen") != null;
      }

      if (!outfitName.contains("Generic") && !outfitName.contains("Dress")) {
         if (!outfitName.contains("Army") && !outfitName.contains("Ghillie")
            || square.getBuilding().getRandomRoom("armyhanger") == null && square.getBuilding().getRandomRoom("armystorage") == null) {
            if (outfitName.contains("Police") && square.getBuilding().getRandomRoom("policestorage") != null) {
               return true;
            }

            if (outfitName.contains("PrisonGuard") && square.getBuilding().getRandomRoom("prisoncells") != null) {
               return true;
            }

            if ((outfitName.contains("AmbulanceDriver") || outfitName.contains("Doctor") || outfitName.contains("Nurse") || outfitName.contains("Pharmacist"))
               && square.getBuilding().getRandomRoom("hospitalroom") != null) {
               return true;
            }

            if (outfitName.contains("Fireman")
               || outfitName.contains("Police")
               || outfitName.contains("Ranger")
               || outfitName.contains("AmbulanceDriver")
               || outfitName.contains("Ghillie")
               || outfitName.contains("PrisonGuard")
               || outfitName.contains("Tourist")) {
               return false;
            }

            if (outfitName.contains("Army")) {
               return square.getBuilding().getRandomRoom("bedroom") != null;
            }

            if (square.getBuilding().getRandomRoom("prisoncells") != null || square.getBuilding().getRandomRoom("policestorage") != null) {
               return false;
            }

            if (square.getBuilding().getRandomRoom("bedroom") != null
               && square.getBuilding().getRandomRoom("livingroom") != null
               && square.getBuilding().getRandomRoom("kitchen") != null) {
               return true;
            }

            if (square.getBuilding().getRandomRoom("storageunit") != null) {
               return true;
            }

            if (outfitName.contains("Trader") && square.getBuilding().getRandomRoom("bank") != null) {
               return true;
            }

            if (!outfitName.contains("OfficeWorker") && !outfitName.contains("Trader")
               || square.getBuilding().getRandomRoom("cardealershipoffice") == null && square.getBuilding().getRandomRoom("office") == null) {
               if (outfitName.contains("Priest") && square.getBuilding().getRandomRoom("church") != null) {
                  return true;
               }

               if (outfitName.contains("Teacher") && square.getBuilding().getRandomRoom("classroom") != null) {
                  return true;
               }

               if ((
                     outfitName.contains("ConstructionWorker")
                        || outfitName.contains("Foreman")
                        || outfitName.contains("Mechanic")
                        || outfitName.contains("MetalWorker")
                  )
                  && square.getBuilding().getRandomRoom("construction") != null) {
                  return true;
               }

               if (!outfitName.contains("Biker")
                     && !outfitName.contains("Punk")
                     && !outfitName.contains("Redneck")
                     && !outfitName.contains("Thug")
                     && !outfitName.contains("Veteran")
                  || square.getBuilding().getRandomRoom("druglab") == null && square.getBuilding().getRandomRoom("drugshack") == null) {
                  if (!outfitName.contains("Farmer")
                     || square.getBuilding().getRandomRoom("farmstorage") == null && square.getBuilding().getRandomRoom("producestorage") == null) {
                     if (outfitName.contains("Fireman") && square.getBuilding().getRandomRoom("firestorage") != null) {
                        return true;
                     }

                     if (outfitName.contains("Fossoil") && square.getBuilding().getRandomRoom("fossoil") != null) {
                        return true;
                     }

                     if ((outfitName.contains("Gas2Go") || outfitName.contains("ThunderGas")) && square.getBuilding().getRandomRoom("gasstore") != null) {
                        return true;
                     }

                     if ((outfitName.contains("GigaMart") || outfitName.contains("Cook_Generic")) && square.getBuilding().getRandomRoom("gigamart") != null) {
                        return true;
                     }

                     if (!outfitName.contains("McCoys") && !outfitName.contains("Foreman")
                        || square.getBuilding().getRandomRoom("loggingfactory") == null && square.getBuilding().getRandomRoom("loggingwarehouse") == null) {
                        if ((outfitName.contains("Mechanic") || outfitName.contains("MetalWorker")) && square.getBuilding().getRandomRoom("mechanic") != null) {
                           return true;
                        } else if ((outfitName.contains("Doctor") || outfitName.contains("Nurse")) && square.getBuilding().getRandomRoom("medical") != null) {
                           return true;
                        } else if (outfitName.contains("Doctor") && square.getBuilding().getRandomRoom("morgue") != null) {
                           return true;
                        } else if ((
                              outfitName.contains("Generic")
                                 || outfitName.contains("Dress")
                                 || outfitName.contains("Golfer")
                                 || outfitName.contains("Classy")
                                 || outfitName.contains("Tourist")
                           )
                           && square.getBuilding().getRandomRoom("motelroom") != null) {
                           return true;
                        } else if ((outfitName.contains("Waiter_PileOCrepe") || outfitName.contains("Chef"))
                           && square.getBuilding().getRandomRoom("pileocrepe") != null) {
                           return true;
                        } else if ((outfitName.contains("Waiter_PizzaWhirled") || outfitName.contains("Cook_Generic"))
                           && square.getBuilding().getRandomRoom("pizzawhirled") != null) {
                           return true;
                        } else if (outfitName.contains("Pharmacist") && square.getBuilding().getRandomRoom("pharmacy") != null) {
                           return true;
                        } else if (outfitName.contains("Postal") && square.getBuilding().getRandomRoom("post") != null) {
                           return true;
                        } else if ((outfitName.contains("Waiter_Restaurant") || outfitName.contains("Cook_Generic"))
                           && square.getBuilding().getRandomRoom("chineserestaurant") != null
                           && square.getBuilding().getRandomRoom("italianrestaurant") != null
                           && square.getBuilding().getRandomRoom("restaurant") != null) {
                           return true;
                        } else if (outfitName.contains("Spiffo") && square.getBuilding().getRandomRoom("spiffoskitchen") != null) {
                           return true;
                        } else if (outfitName.contains("WaiterStripper") && square.getBuilding().getRandomRoom("stripclub") != null) {
                           return true;
                        } else if (!outfitName.contains("Cook_Generic")
                           || square.getBuilding().getRandomRoom("bakerykitchen") == null
                              && square.getBuilding().getRandomRoom("burgerkitchen") == null
                              && square.getBuilding().getRandomRoom("cafekitchen") == null
                              && square.getBuilding().getRandomRoom("cafeteriakitchen") == null
                              && square.getBuilding().getRandomRoom("chinesekitchen") == null
                              && square.getBuilding().getRandomRoom("deepfry_kitchen") == null
                              && square.getBuilding().getRandomRoom("deepfry_kitchen") == null
                              && square.getBuilding().getRandomRoom("dinerkitchen") == null
                              && square.getBuilding().getRandomRoom("donut_kitchen") == null
                              && square.getBuilding().getRandomRoom("fishchipskitchen") == null
                              && square.getBuilding().getRandomRoom("gigamartkitchen") == null
                              && square.getBuilding().getRandomRoom("icecreamkitchen") == null
                              && square.getBuilding().getRandomRoom("italiankitchen") == null
                              && square.getBuilding().getRandomRoom("jayschicken_kitchen") == null
                              && square.getBuilding().getRandomRoom("restaurantkitchen") == null
                              && square.getBuilding().getRandomRoom("italiankitchen") == null
                              && square.getBuilding().getRandomRoom("jayschicken_kitchen") == null
                              && square.getBuilding().getRandomRoom("kitchen_crepe") == null
                              && square.getBuilding().getRandomRoom("mexicankitchen") == null
                              && square.getBuilding().getRandomRoom("pizzakitchen") == null
                              && square.getBuilding().getRandomRoom("restaurantkitchen") == null
                              && square.getBuilding().getRandomRoom("seafoodkitchen") == null
                              && square.getBuilding().getRandomRoom("sushikitchen") == null) {
                           return !outfitName.contains("ConstructionWorker")
                                    && !outfitName.contains("Foreman")
                                    && !outfitName.contains("Mechanic")
                                    && !outfitName.contains("MetalWorker")
                                 || square.getBuilding().getRandomRoom("batfactory") == null
                                    && square.getBuilding().getRandomRoom("batteryfactory") == null
                                    && square.getBuilding().getRandomRoom("brewery") == null
                                    && square.getBuilding().getRandomRoom("cabinetfactory") == null
                                    && square.getBuilding().getRandomRoom("dogfoodfactory") == null
                                    && square.getBuilding().getRandomRoom("factory") == null
                                    && square.getBuilding().getRandomRoom("fryshipping") == null
                                    && square.getBuilding().getRandomRoom("metalshop") == null
                                    && square.getBuilding().getRandomRoom("radiofactory") == null
                                    && square.getBuilding().getRandomRoom("warehouse") == null
                                    && square.getBuilding().getRandomRoom("warehouse") == null
                              ? true
                              : true;
                        } else {
                           return true;
                        }
                     } else {
                        return true;
                     }
                  } else {
                     return true;
                  }
               } else {
                  return true;
               }
            } else {
               return true;
            }
         } else {
            return true;
         }
      } else {
         return square.getBuilding().getRandomRoom("bedroom") != null
            && square.getBuilding().getRandomRoom("livingroom") != null
            && square.getBuilding().getRandomRoom("kitchen") != null;
      }
   }

   public boolean spawnBuildingKeyOnZombie(IsoZombie zombie) {
      IsoGridSquare square = zombie.getSquare();
      return square != null && square.getBuilding() != null && square.getBuilding().getDef() != null
         ? this.spawnBuildingKeyOnZombie(zombie, square.getBuilding().getDef())
         : false;
   }

   public boolean spawnBuildingKeyOnZombie(IsoZombie zombie, BuildingDef def) {
      if (Rand.Next(100) >= 1.0F * this.getKeySpawnChanceD100()) {
         String keyType = "Base.Key1";
         InventoryItem key = InventoryItemFactory.CreateItem("Base.Key1");
         if (key != null) {
            key.setKeyId(def.getKeyId());
            KeyNamer.nameKey(key, def.getFreeSquareInRoom());
            zombie.addItemToSpawnAtDeath(key);
            return true;
         }
      } else {
         InventoryItem keyringItem = InventoryItemFactory.CreateItem(Container.KEY_RING);
         if (keyringItem instanceof InventoryContainer keyring) {
            String keyType = "Base.Key1";
            InventoryItem key = keyring.getInventory().AddItem("Base.Key1");
            if (key != null) {
               KeyNamer.nameKey(key, def.getFreeSquareInRoom());
               key.setKeyId(def.getKeyId());
               if (Rand.Next(100) < 1.0F * this.getKeySpawnChanceD100()) {
                  Set<BaseVehicle> vehicles = IsoWorld.instance.currentCell.getVehicles();
                  if (!vehicles.isEmpty()) {
                     BaseVehicle vehicle = zombie.getNearVehicle();
                     boolean isGood = vehicle != null
                        && !vehicle.isPreviouslyMoved()
                        && !vehicle.getKeySpawned()
                        && vehicle.checkZombieKeyForVehicle(zombie, vehicle.getScriptName());
                     if (!isGood) {
                        vehicle = vehicles.stream().toList().get(Rand.Next(vehicles.size()));
                     }

                     isGood = vehicle != null
                        && !vehicle.getScript().neverSpawnKey()
                        && !vehicle.isPreviouslyMoved()
                        && !vehicle.getKeySpawned()
                        && vehicle.checkZombieKeyForVehicle(zombie, vehicle.getScriptName());
                     if (isGood) {
                        InventoryItem key2 = vehicle.createVehicleKey();
                        vehicle.keySpawned = 1;
                        vehicle.setPreviouslyMoved(true);
                        vehicle.keyNamerVehicle(key);
                        keyring.getInventory().AddItem(key2);
                     }
                  }
               }

               zombie.addItemToSpawnAtDeath(keyringItem);
               return true;
            }
         }
      }

      return false;
   }

   public boolean checkAndSpawnZombieForBuildingKey(IsoZombie zombie) {
      return this.checkAndSpawnZombieForBuildingKey(zombie, false);
   }

   public boolean checkAndSpawnZombieForBuildingKey(IsoZombie zombie, boolean bandits) {
      boolean key = zombie.shouldZombieHaveKey(bandits);
      if (!key) {
         return false;
      }

      IsoGridSquare square = null;
      boolean outside = false;
      BuildingDef def;
      if (zombie.getBuilding() != null && zombie.getBuilding().getDef() != null && zombie.getBuilding().getDef().getKeyId() != -1) {
         def = zombie.getBuilding().getDef();
         square = zombie.getSquare();
      } else {
         float px = zombie.getX();
         float py = zombie.getY();
         def = AmbientStreamManager.getNearestBuilding(px, py);
         if (def != null && !def.isAllExplored()) {
            outside = true;
            square = def.getFreeSquareInRoom();
         }
      }

      if (square != null && this.checkZombieKeyForBuilding(zombie.getOutfitName(), square)) {
         String roomName = null;
         boolean isKey = true;
         if (!outside && zombie.getSquare().getRoom() != null) {
            roomName = zombie.getSquare().getRoom().getName();
         }

         if (roomName != null) {
            String outfitName = zombie.getOutfitName();
            if (roomName.equals("cells") || roomName.equals("prisoncells") && !outfitName.contains("Police") && !outfitName.equals("PrisonGuard")) {
               isKey = false;
            }
         }

         if (isKey) {
            return this.spawnBuildingKeyOnZombie(zombie, def);
         }
      }

      return false;
   }

   private static float doKeySandboxSettings(int value) {
      switch (value) {
         case 1:
            return 0.0F;
         case 2:
            return 0.05F;
         case 3:
            return 0.2F;
         case 4:
            return 0.6F;
         case 5:
            return 1.0F;
         case 6:
            return 2.0F;
         case 7:
            return 2.4F;
         default:
            return 0.6F;
      }
   }
}
