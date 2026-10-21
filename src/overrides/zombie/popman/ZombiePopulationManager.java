package zombie.popman;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntLongHashMap;
import gnu.trove.set.hash.TIntHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import se.krka.kahlua.vm.KahluaTable;
import zombie.DebugFileWatcher;
import zombie.GameTime;
import zombie.MapCollisionData;
import zombie.PersistentOutfits;
import zombie.PredicatedFileWatcher;
import zombie.SandboxOptions;
import zombie.VirtualZombieManager;
import zombie.WorldSoundManager;
import zombie.ZomboidFileSystem;
import zombie.SandboxOptions.ZombieConfig;
import zombie.ai.states.PathFindState;
import zombie.ai.states.WalkTowardState;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo.SpawnOrigin;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.packets.service.PopmanDebugCommandPacket;
import zombie.pathfind.PolygonalMap2;
import zombie.util.PZXmlParserException;
import zombie.util.PZXmlUtil;
import zombie.util.list.PZArrayUtil;

public final class ZombiePopulationManager {
   static {
      pzopt.Overrides.onClassLoaded("zombie.popman.ZombiePopulationManager");
   }

   public static final ZombiePopulationManager instance = new ZombiePopulationManager();
   protected static final int SQUARES_PER_CHUNK = 8;
   protected static final int CHUNKS_PER_CELL = 32;
   protected static final int SQUARES_PER_CELL = 256;
   protected static final byte OLD_ZOMBIE_CRAWLER_CAN_WALK = 1;
   protected static final byte OLD_ZOMBIE_FAKE_DEAD = 2;
   protected static final byte OLD_ZOMBIE_CRAWLER = 3;
   protected static final byte OLD_ZOMBIE_WALKER = 4;
   public static final int INVALID_PATH_XY = Integer.MIN_VALUE;
   public static final int INVALID_PERSISTENT_ID = 0;
   private static final int UNREGISTERED_ZOMBIE_REPORT_THRESHOLD = 64;
   private static final int CELL_KEY_SHIFT = 16;
   private static final int CELL_KEY_MASK = 65535;
   private static final long REAL_ZOMBIE_UPDATE_INTERVAL_MS = 5000L;
   private static final long SAVE_CELL_INTERVAL_MS = 5000L;
   private static final long PUSH_BEFORE_SAVE_INTERVAL_MS = 1000L;
   protected int minX;
   protected int minY;
   protected int width;
   protected int height;
   protected boolean stopped;
   private final DebugCommands dbgCommands = new DebugCommands();
   public static boolean debugLoggingEnabled;
   public static final ReentrantLock saveLock = new ReentrantLock();
   private static final Set<Integer> pendingSaveCellKeys = ConcurrentHashMap.newKeySet();
   private static final TIntLongHashMap lastSaveCellTimeMs = new TIntLongHashMap();
   private static final RealZombieMap realZombies = new RealZombieMap();
   private final LoadedAreas loadedAreas = new LoadedAreas(false);
   private final LoadedAreas loadedServerCells = new LoadedAreas(true);
   private final PlayerSpawns playerSpawns = new PlayerSpawns();
   private int[] realZombieIdsAndStates;
   private float[] realZombiePositions;
   private long realZombieUpdateTimeMs;
   private int lastReportedUnregisteredCount;
   private final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024);
   private final TIntHashSet newChunks = new TIntHashSet();
   private final ArrayList<SpawnOrigin> spawnOrigins = new ArrayList<>();
   private float zombiesMinPerChunk;
   private float zombiesMaxPerChunk = 255.0F;
   public float[] radarXy;
   public int radarCount;
   public boolean radarRenderFlag;
   public boolean radarRequestFlag;
   private final ArrayList<IsoDirections> sittingDirections = new ArrayList<>();

   ZombiePopulationManager() {
      this.newChunks.setAutoCompactionFactor(0.0F);
   }

   private static native void n_init(boolean var0, boolean var1, int var2, int var3, int var4, int var5);

   private static native void n_config(float var0, float var1, float var2, int var3, float var4, float var5, float var6, float var7, int var8);

   private static native void n_configFloat(String var0, float var1);

   private static native void n_configInt(String var0, int var1);

   private static native void n_setSpawnOrigins(int[] var0);

   private static native void n_setOutfitNames(String[] var0);

   private static native void n_updateMain(float var0, double var1);

   private static native boolean n_hasDataForThread();

   private static native boolean n_readyToPause();

   private static native void n_updateThread();

   private static native boolean n_shouldWait();

   private static native void n_save();

   private static native void n_saveCell(int var0, int var1);

   private static native void n_stop();

   private static native void n_addZombie(float var0, float var1, float var2, byte var3, int var4, int var5, int var6, int var7, int var8);

   private static native int n_registerZombie(float var0, float var1, float var2, byte var3, int var4, int var5, int var6);

   private static native void n_releaseZombie(int var0);

   private static native void n_aggroTarget(int var0, int var1, int var2);

   private static native void n_loadChunk(int var0, int var1, boolean var2);

   private static native void n_loadedAreas(int var0, int[] var1, boolean var2);

   private static native void n_updateRealZombies(int var0, int[] var1, float[] var2);

   protected static native void n_spawnHorde(int var0, int var1, int var2, int var3, float var4, float var5, int var6);

   private static native void n_worldSound(int var0, int var1, int var2, int var3);

   private static native int n_getAddZombieCount();

   private static native int n_getAddZombieData(int var0, ByteBuffer var1);

   private static native boolean n_hasRadarData();

   private static native void n_requestRadarData();

   private static native int n_getRadarZombieData(float[] var0);

   public static int registerZombie(IsoZombie zombie, int persistentId) {
      if (GameClient.client) {
         return 0;
      }

      int id = n_registerZombie(
         zombie.getX(),
         zombie.getY(),
         zombie.getZ(),
         (byte)zombie.getForwardIsoDirection().ordinal(),
         zombie.getPersistentOutfitID(),
         ZombieStateFlags.intFromZombie(zombie),
         persistentId
      );
      realZombies.register(zombie, id);
      return id;
   }

   public static void releaseZombie(IsoZombie zombie) {
      if (!GameClient.client && zombie.persistentId != 0) {
         if (realZombies.unregister(zombie)) {
            n_releaseZombie(zombie.persistentId);
         }
      }
   }

   private static void noise(String s) {
      if (debugLoggingEnabled && (Core.debug || GameServer.server && GameServer.debug)) {
         DebugLog.log("ZPOP: " + s);
      }
   }

   public static void init() {
      String libSuffix = "";
      if ("1".equals(System.getProperty("zomboid.debuglibs.popman"))) {
         DebugLog.log("***** Loading debug version of PZPopMan");
         libSuffix = "d";
      }

      if (System.getProperty("os.name").contains("OS X")) {
         System.loadLibrary("PZPopMan");
      } else {
         System.loadLibrary("PZPopMan64" + libSuffix);
      }

      DebugFileWatcher.instance
         .add(new PredicatedFileWatcher(ZomboidFileSystem.instance.getMessagingDirSub("Trigger_Zombie.xml"), ZombiePopulationManager::onTriggeredZombieFile));
   }

   public void requestSaveCell(int popmanCellX, int popmanCellY) {
      if (!GameClient.client) {
         if (!this.stopped) {
            pendingSaveCellKeys.add(packCell(popmanCellX, popmanCellY));
         }
      }
   }

   public void processPendingSaveCells() {
      if (!GameClient.client && !pendingSaveCellKeys.isEmpty()) {
         long currentTimeMs = System.currentTimeMillis();
         Iterator<Integer> cells = pendingSaveCellKeys.iterator();

         while (cells.hasNext()) {
            int cellKey = cells.next();
            if (isCellDueForSave(cellKey, currentTimeMs)) {
               cells.remove();
               lastSaveCellTimeMs.put(cellKey, currentTimeMs);
               saveLock.lock();

               try {
                  n_saveCell(unpackCellX(cellKey), unpackCellY(cellKey));
               } finally {
                  saveLock.unlock();
               }
            }
         }
      }
   }

   private static boolean isCellDueForSave(int cellKey, long currentTimeMs) {
      return currentTimeMs - lastSaveCellTimeMs.get(cellKey) >= 5000L;
   }

   private static int packCell(int popmanCellX, int popmanCellY) {
      return popmanCellX << 16 | popmanCellY & 65535;
   }

   private static int unpackCellX(int cellKey) {
      return cellKey >> 16;
   }

   private static int unpackCellY(int cellKey) {
      return (short)(cellKey & 65535);
   }

   private static boolean isStaleRecord(int persistentId) {
      if (!realZombies.isLive(persistentId)) {
         return false;
      }

      DebugType.General.warn("ZPOP: dropped a stale record for zombie %d, it is already alive", new Object[]{persistentId});
      return true;
   }

   private static void onTriggeredZombieFile(String xmlFile) {
      DebugType.General.println("ZombiePopulationManager.onTriggeredZombieFile(" + xmlFile + ">");

      ZombieTriggerXmlFile triggerXml;
      try {
         triggerXml = (ZombieTriggerXmlFile)PZXmlUtil.parse(ZombieTriggerXmlFile.class, xmlFile);
      } catch (PZXmlParserException e) {
         System.err.println("ZombiePopulationManager.onTriggeredZombieFile> Exception thrown. " + e);
         DebugType.General.printException(e, LogSeverity.Error);
         return;
      }

      if (triggerXml.spawnHorde > 0) {
         processTriggerSpawnHorde(triggerXml);
      }

      if (triggerXml.setDebugLoggingEnabled && debugLoggingEnabled != triggerXml.debugLoggingEnabled) {
         debugLoggingEnabled = triggerXml.debugLoggingEnabled;
         DebugType.General.println("  bDebugLoggingEnabled: " + debugLoggingEnabled);
      }
   }

   private static void processTriggerSpawnHorde(ZombieTriggerXmlFile triggerXml) {
      DebugType.General.println("  spawnHorde: " + triggerXml.spawnHorde);
      if (IsoPlayer.getInstance() != null) {
         IsoPlayer player = IsoPlayer.getInstance();
         instance.createHordeFromTo(
            PZMath.fastfloor(player.getX()),
            PZMath.fastfloor(player.getY()),
            PZMath.fastfloor(player.getX()),
            PZMath.fastfloor(player.getY()),
            triggerXml.spawnHorde
         );
      }
   }

   public void init(IsoMetaGrid metaGrid) {
      if (!GameClient.client) {
         this.minX = metaGrid.getMinX();
         this.minY = metaGrid.getMinY();
         this.width = metaGrid.getWidth();
         this.height = metaGrid.getHeight();
         this.stopped = false;
         n_init(GameClient.client, GameServer.server, this.minX, this.minY, this.width, this.height);
         this.onConfigReloaded();
         String[] outfitNames = PersistentOutfits.instance.getOutfitNames().toArray(new String[0]);

         for (int i = 0; i < outfitNames.length; i++) {
            outfitNames[i] = outfitNames[i].toLowerCase();
         }

         n_setOutfitNames(outfitNames);
         TIntArrayList origins = new TIntArrayList();

         for (SpawnOrigin spawnOrigin : this.spawnOrigins) {
            origins.add(spawnOrigin.x);
            origins.add(spawnOrigin.y);
            origins.add(spawnOrigin.w);
            origins.add(spawnOrigin.h);
         }

         n_setSpawnOrigins(origins.toArray());
      }
   }

   public void onConfigReloaded() {
      ZombieConfig cfg = SandboxOptions.instance.zombieConfig;
      n_configFloat("PopulationMultiplier", (float)cfg.populationMultiplier.getValue());
      n_configFloat("PopulationStartMultiplier", (float)cfg.populationStartMultiplier.getValue());
      n_configFloat("PopulationPeakMultiplier", (float)cfg.populationPeakMultiplier.getValue());
      n_configInt("PopulationPeakDay", cfg.populationPeakDay.getValue());
      n_configFloat("RespawnHours", (float)cfg.respawnHours.getValue());
      n_configFloat("RespawnUnseenHours", (float)cfg.respawnUnseenHours.getValue());
      n_configFloat("RespawnMultiplier", (float)cfg.respawnMultiplier.getValue());
      n_configFloat("RedistributeHours", (float)cfg.redistributeHours.getValue());
      n_configInt("FollowSoundDistance", cfg.followSoundDistance.getValue());
      n_configFloat("MinZombiesPerChunk", this.zombiesMinPerChunk);
      n_configFloat("MaxZombiesPerChunk", this.zombiesMaxPerChunk);
      n_configFloat("UniformZombiesPerChunk", 0.2F);
   }

   public void registerSpawnOrigin(int x, int y, int width, int height, KahluaTable properties) {
      if (x >= 0 && y >= 0 && width >= 0 && height >= 0) {
         this.spawnOrigins.add(new SpawnOrigin(x, y, width, height));
      }
   }

   public void playerSpawnedAt(int x, int y, int z) {
      this.playerSpawns.addSpawn(x, y, z);
   }

   public void setZombiesMinPerChunk(float f) {
      this.zombiesMinPerChunk = f;
   }

   public void setZombiesMaxPerChunk(float f) {
      this.zombiesMaxPerChunk = f;
   }

   public void addChunkToWorld(IsoChunk chunk) {
      if (!GameClient.client) {
         if (chunk.isNewChunk()) {
            int key = chunk.wy << 16 | chunk.wx;
            this.newChunks.add(key);
         }

         n_loadChunk(chunk.wx, chunk.wy, true);
      }
   }

   public void removeChunkFromWorld(IsoChunk chunk) {
      if (!GameClient.client) {
         if (!this.stopped) {
            long pzoptT = pzopt.GtAb.TIMING ? System.nanoTime() : 0L; // pzopt: Louisville 120 item 3 dev timer (the native unload call)
            n_loadChunk(chunk.wx, chunk.wy, false);
            if (pzoptT != 0L) { // pzopt
               pzopt.GtAb.add(pzopt.GtAb.S_NATIVE_UNLOAD, System.nanoTime() - pzoptT); // pzopt
            } // pzopt

            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                     if (sq != null && !sq.getMovingObjects().isEmpty()) {
                        for (int i = 0; i < sq.getMovingObjects().size(); i++) {
                           IsoMovingObject mo = (IsoMovingObject)sq.getMovingObjects().get(i);
                           if (mo instanceof IsoZombie realZombie && !realZombie.isReanimatedPlayer()) {
                              if (realZombie.isDead()) {
                                 int movingObjects = sq.getMovingObjects().size();
                                 realZombie.die();
                                 if (sq.getMovingObjects().size() < movingObjects) {
                                    i--;
                                 }
                              } else {
                                 int state = ZombieStateFlags.intFromZombie(realZombie);
                                 saveLock.lock();

                                 try {
                                    if (z == 0 && sq.getRoom() == null && (realZombie.getCurrentState() == WalkTowardState.instance() || realZombie.getCurrentState() == PathFindState.instance())) { // pzopt: decompiler fix, the jar's branch order with a continue inside the try (one finally copy per exit, as javac emits it)
                                       DebugType.Zombie.debugln("Virtualizing moving Zombie: %s", new Object[]{realZombie});
                                       n_addZombie(
                                          realZombie.getX(),
                                          realZombie.getY(),
                                          realZombie.getZ(),
                                          (byte)realZombie.getForwardIsoDirection().ordinal(),
                                          realZombie.getPersistentOutfitID(),
                                          state,
                                          realZombie.getPathTargetX(),
                                          realZombie.getPathTargetY(),
                                          realZombie.persistentId
                                       );
                                       realZombie.removeFromWorld();
                                       realZombie.removeFromSquare();
                                       i--;
                                       continue; // pzopt: decompiler fix
                                    }

                                    DebugType.Zombie.debugln("Virtualizing stationary Zombie: %s", new Object[]{realZombie});
                                    n_addZombie(
                                       realZombie.getX(),
                                       realZombie.getY(),
                                       realZombie.getZ(),
                                       (byte)realZombie.getForwardIsoDirection().ordinal(),
                                       realZombie.getPersistentOutfitID(),
                                       state,
                                       Integer.MIN_VALUE,
                                       Integer.MIN_VALUE,
                                       realZombie.persistentId
                                    );
                                    realZombie.removeFromWorld();
                                    realZombie.removeFromSquare();
                                    i--;
                                    continue; // pzopt: decompiler fix
                                 } finally {
                                    saveLock.unlock();
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }

            int key = chunk.wy << 16 | chunk.wx;
            this.newChunks.remove(key);
            if (GameServer.server) {
               MapCollisionData.instance.notifyThread();
            }
         }
      }
   }

   public void virtualizeZombie(IsoZombie realZombie) {
      DebugType.Zombie.debugln("Virtualizing Zombie: %s", new Object[]{realZombie});
      int state = ZombieStateFlags.intFromZombie(realZombie);
      saveLock.lock();

      try {
         n_addZombie(
            realZombie.getX(),
            realZombie.getY(),
            realZombie.getZ(),
            (byte)realZombie.getForwardIsoDirection().ordinal(),
            realZombie.getPersistentOutfitID(),
            state,
            realZombie.getPathTargetX(),
            realZombie.getPathTargetY(),
            realZombie.persistentId
         );
         realZombie.removeFromWorld();
         realZombie.removeFromSquare();
      } finally {
         saveLock.unlock();
      }
   }

   public void setAggroTarget(int id, int x, int y) {
      n_aggroTarget(id, x, y);
   }

   public void createHordeFromTo(int spawnX, int spawnY, int targetX, int targetY, int count) {
      n_spawnHorde(spawnX, spawnY, 0, 0, targetX, targetY, count);
   }

   public void createHordeInAreaTo(int spawnX, int spawnY, int spawnW, int spawnH, int targetX, int targetY, int count) {
      n_spawnHorde(spawnX, spawnY, spawnW, spawnH, targetX, targetY, count);
   }

   public boolean readyToPause() {
      return n_readyToPause();
   }

   public void addWorldSound(WorldSoundManager.WorldSound sound, boolean doSend) {
      if (!GameClient.client) {
         if (sound.radius >= 50) {
            if (!sound.sourceIsZombie) {
               if (sound.stressZombies) {
                  int hearing = SandboxOptions.instance.lore.hearing.getValue();
                  if (hearing == 4 || hearing == 5) {
                     hearing = 2;
                  }

                  float radiusMultiplier = WorldSoundManager.instance.getHearingMultiplier(hearing);
                  n_worldSound(sound.x, sound.y, (int)PZMath.ceil(sound.radius * radiusMultiplier), sound.volume);
               }
            }
         }
      }
   }

   int packRealZombies(List<IsoZombie> zombies) {
      if (this.realZombieIdsAndStates == null || this.realZombieIdsAndStates.length < zombies.size() * 2) {
         this.realZombieIdsAndStates = new int[zombies.size() * 2];
         this.realZombiePositions = new float[zombies.size() * 3];
      }

      int count = 0;
      int unregistered = 0;

      for (int i = 0; i < zombies.size(); i++) {
         IsoZombie zombie = zombies.get(i);
         if (zombie.persistentId == 0) {
            if (!zombie.isReanimatedPlayer() && !zombie.isDead()) {
               unregistered++;
            }
         } else {
            this.realZombieIdsAndStates[count * 2] = zombie.persistentId;
            this.realZombieIdsAndStates[count * 2 + 1] = ZombieStateFlags.intFromZombie(zombie);
            this.realZombiePositions[count * 3] = zombie.getX();
            this.realZombiePositions[count * 3 + 1] = zombie.getY();
            this.realZombiePositions[count * 3 + 2] = zombie.getZ();
            count++;
         }
      }

      if (unregistered > 64 && unregistered > this.lastReportedUnregisteredCount) {
         this.lastReportedUnregisteredCount = unregistered;
         DebugType.General.error("ZPOP: %d zombies have no persistent id and will not be saved", new Object[]{unregistered});
      }

      return count;
   }

   public void updateRealZombies() {
      if (!GameClient.client) {
         int count = this.packRealZombies(IsoWorld.instance.currentCell.getZombieList());
         n_updateRealZombies(count, this.realZombieIdsAndStates, this.realZombiePositions);
      }
   }

   public void updateMain() { // pzopt: devGtAlternate section timer around the stock body
      long pzoptT = pzopt.GtAb.begin(); // pzopt
      try { // pzopt
         this.pzoptUpdateMainBody(); // pzopt
      } finally { // pzopt
         pzopt.GtAb.end(pzopt.GtAb.S_POPMAN, pzoptT); // pzopt
      } // pzopt
   } // pzopt

   private void pzoptUpdateMainBody() { // pzopt: the stock updateMain()
      if (!GameClient.client) {
         long currentTimeMs = System.currentTimeMillis();
         n_updateMain(GameTime.getInstance().getMultiplier(), GameTime.getInstance().getWorldAgeHours());
         int numStanding = 0;
         int numMoving = 0;
         boolean pzoptSpawnSpread = pzopt.Config.ZOMBIE_SPAWN_SPREAD && pzopt.Overrides.enabled() && pzopt.GtAb.on(pzopt.GtAb.SPAWN_SPREAD); // pzopt
         if (!pzoptSpawnSpread) { // pzopt: the key flipped off (A/B): what is queued goes now
            this.pzoptDrainSpawnsAll(); // pzopt
         } // pzopt
         int total = n_getAddZombieCount();
         int offset = 0;

         while (offset < total) {
            this.byteBuffer.clear();
            int count = n_getAddZombieData(offset, this.byteBuffer);
            offset += count;

            for (int i = 0; i < count; i++) {
               float x = this.byteBuffer.getFloat();
               float y = this.byteBuffer.getFloat();
               float z = this.byteBuffer.getFloat();
               IsoDirections dir = IsoDirections.fromIndex(this.byteBuffer.get());
               int descriptorID = this.byteBuffer.getInt();
               ZombieStateFlags state = ZombieStateFlags.fromInt(this.byteBuffer.getInt());
               int pathTargetX = this.byteBuffer.getInt();
               int pathTargetY = this.byteBuffer.getInt();
               int persistentId = this.byteBuffer.getInt();
               int wx = PZMath.fastfloor(x) / 8;
               int wy = PZMath.fastfloor(y) / 8;
               int key = wy << 16 | wx;
               if (this.newChunks.contains(key)) {
                  IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), PZMath.fastfloor(z));
                  if (sq != null && sq.roomId != -1L) {
                     continue;
                  }
               }

               if (pathTargetX != Integer.MIN_VALUE && this.loadedAreas.isOnEdge(PZMath.fastfloor(x), PZMath.fastfloor(y))) {
                  pathTargetX = Integer.MIN_VALUE;
                  pathTargetY = Integer.MIN_VALUE;
               }

               if (pzoptSpawnSpread && !pzopt.LootDefer.nearPlayer(x, y, pzopt.Config.ZOMBIE_SPAWN_NEAR)) { // pzopt: zombieSpawnSpread, a zombie away from
                  // every player is created from the queue below under the frame's budget; a near one at once, as stock
                  this.pzoptQueueSpawn(x, y, z, dir, descriptorID, state, pathTargetX, pathTargetY, persistentId); // pzopt
                  if (pathTargetX == Integer.MIN_VALUE) { // pzopt
                     numStanding++; // pzopt
                  } else { // pzopt
                     numMoving++; // pzopt
                  } // pzopt
                  continue; // pzopt
               } // pzopt

               if (pathTargetX == Integer.MIN_VALUE) {
                  this.addZombieStanding(x, y, z, dir, descriptorID, state, persistentId);
                  numStanding++;
               } else {
                  this.addZombieMoving(x, y, z, dir, descriptorID, state, pathTargetX, pathTargetY, persistentId);
                  numMoving++;
               }
            }
         }

         this.pzoptDrainSpawns(); // pzopt: zombieSpawnSpread

         if (numStanding > 0) {
            noise("unloaded -> real " + total);
         }

         if (numMoving > 0) {
            noise("virtual -> real " + total);
         }

         if (this.radarRenderFlag && this.radarXy != null) {
            if (this.radarRequestFlag) {
               if (n_hasRadarData()) {
                  this.radarCount = n_getRadarZombieData(this.radarXy);
                  this.radarRenderFlag = false;
                  this.radarRequestFlag = false;
               }
            } else {
               n_requestRadarData();
               this.radarRequestFlag = true;
            }
         }

         this.updateLoadedAreas();
         long pushInterval = pendingSaveCellKeys.isEmpty() ? 5000L : 1000L;
         if (this.realZombieUpdateTimeMs + pushInterval < currentTimeMs) {
            this.realZombieUpdateTimeMs = currentTimeMs;
            this.updateRealZombies();
         }

         if (GameServer.server) {
            MPDebugInfo.instance.serverUpdate();
         }

         boolean hasData1 = n_hasDataForThread();
         boolean hasData2 = MapCollisionData.instance.hasDataForThread();
         if (hasData1 || hasData2) {
            MapCollisionData.instance.notifyThread();
         }

         this.playerSpawns.update();
      }
   }

   // pzopt: zombieSpawnSpread (2026-10-05, Louisville 120 fps pass). The native population hands over every zombie that turns
   // real this frame (a chunk row arriving next to a horde: dozens at once, each an IsoZombie with an outfit), and stock
   // creates them all in that frame. They are decoded and filtered as stock does, queued, and created oldest first under
   // zombieSpawnBudgetUs a frame (at least zombieSpawnMin): a zombie at the loaded area's edge appears a few frames later.
   private float[] pzoptSpX = new float[256], pzoptSpY = new float[256], pzoptSpZ = new float[256]; // pzopt
   private IsoDirections[] pzoptSpDir = new IsoDirections[256]; // pzopt
   private ZombieStateFlags[] pzoptSpState = new ZombieStateFlags[256]; // pzopt
   private int[] pzoptSpDesc = new int[256], pzoptSpTx = new int[256], pzoptSpTy = new int[256], pzoptSpPid = new int[256], pzoptSpFrame = new int[256]; // pzopt
   private long[] pzoptSpNs = new long[256]; // pzopt: when each entry was queued
   private final pzopt.SlackWork.Cost pzoptSpawnSlackCost = new pzopt.SlackWork.Cost(300_000.0, 0.0); // pzopt: slackWork, one zombie's creation (the mean: one size of job)
   private final pzopt.SlackWork.Producer pzoptSpawnProducer = new pzopt.SlackWork.Producer() { // pzopt: slackWork, the queue below
      public boolean pending() { // pzopt
         return ZombiePopulationManager.this.pzoptSpHead < ZombiePopulationManager.this.pzoptSpTail; // pzopt
      } // pzopt
      public long nextCostNs() { // pzopt
         return ZombiePopulationManager.this.pzoptSpawnSlackCost.estimate(); // pzopt
      } // pzopt
      public int nextAgeFrames() { // pzopt
         return pzopt.SlackWork.frame() - ZombiePopulationManager.this.pzoptSpFrame[ZombiePopulationManager.this.pzoptSpHead]; // pzopt
      } // pzopt
      public void runNext() { // pzopt
         long t0 = System.nanoTime(); // pzopt
         ZombiePopulationManager.this.pzoptSpawnOne(); // pzopt
         ZombiePopulationManager.this.pzoptSpawnSlackCost.learn(System.nanoTime() - t0); // pzopt
      } // pzopt
   }; // pzopt
   private int pzoptSpHead, pzoptSpTail; // pzopt: queue [head, tail)
   public static long pzoptSpawnQueued, pzoptSpawnMax, pzoptSpawnAged; // pzopt

   private void pzoptQueueSpawn(float x, float y, float z, IsoDirections dir, int desc, ZombieStateFlags state, int tx, int ty, int pid) { // pzopt
      if (this.pzoptSpTail == this.pzoptSpX.length) { // pzopt: compact, then grow
         int n = this.pzoptSpTail - this.pzoptSpHead; // pzopt
         int cap = n * 2 >= this.pzoptSpX.length ? this.pzoptSpX.length * 2 : this.pzoptSpX.length; // pzopt
         this.pzoptSpX = java.util.Arrays.copyOfRange(this.pzoptSpX, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpY = java.util.Arrays.copyOfRange(this.pzoptSpY, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpZ = java.util.Arrays.copyOfRange(this.pzoptSpZ, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpDir = java.util.Arrays.copyOfRange(this.pzoptSpDir, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpState = java.util.Arrays.copyOfRange(this.pzoptSpState, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpDesc = java.util.Arrays.copyOfRange(this.pzoptSpDesc, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpTx = java.util.Arrays.copyOfRange(this.pzoptSpTx, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpTy = java.util.Arrays.copyOfRange(this.pzoptSpTy, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpPid = java.util.Arrays.copyOfRange(this.pzoptSpPid, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpFrame = java.util.Arrays.copyOfRange(this.pzoptSpFrame, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpNs = java.util.Arrays.copyOfRange(this.pzoptSpNs, this.pzoptSpHead, this.pzoptSpHead + cap); // pzopt
         this.pzoptSpTail = n; // pzopt
         this.pzoptSpHead = 0; // pzopt
      } // pzopt
      int i = this.pzoptSpTail++; // pzopt
      this.pzoptSpX[i] = x; this.pzoptSpY[i] = y; this.pzoptSpZ[i] = z; this.pzoptSpDir[i] = dir; this.pzoptSpState[i] = state; // pzopt
      this.pzoptSpDesc[i] = desc; this.pzoptSpTx[i] = tx; this.pzoptSpTy[i] = ty; this.pzoptSpPid[i] = pid; // pzopt
      this.pzoptSpFrame[i] = pzopt.SlackWork.frame(); // pzopt
      this.pzoptSpNs[i] = System.nanoTime(); // pzopt
      pzoptSpawnQueued++; // pzopt
      pzoptSpawnMax = Math.max(pzoptSpawnMax, this.pzoptSpTail - this.pzoptSpHead); // pzopt
   } // pzopt

   private void pzoptSpawnOne() { // pzopt: the stock call for the oldest queued zombie
      int i = this.pzoptSpHead++; // pzopt
      if (this.pzoptSpTx[i] == Integer.MIN_VALUE) { // pzopt
         this.addZombieStanding(this.pzoptSpX[i], this.pzoptSpY[i], this.pzoptSpZ[i], this.pzoptSpDir[i], this.pzoptSpDesc[i], this.pzoptSpState[i], this.pzoptSpPid[i]); // pzopt
      } else { // pzopt
         this.addZombieMoving(this.pzoptSpX[i], this.pzoptSpY[i], this.pzoptSpZ[i], this.pzoptSpDir[i], this.pzoptSpDesc[i], this.pzoptSpState[i], this.pzoptSpTx[i], this.pzoptSpTy[i], this.pzoptSpPid[i]); // pzopt
      } // pzopt
      this.pzoptSpDir[i] = null; // pzopt
      this.pzoptSpState[i] = null; // pzopt
      if (this.pzoptSpHead == this.pzoptSpTail) { // pzopt
         this.pzoptSpHead = this.pzoptSpTail = 0; // pzopt
      } // pzopt
   } // pzopt

   private long pzoptSpawnLogNs; // pzopt
   private long pzoptSpawnFirstNs; // pzopt: the first population update of the session
   private double pzoptSpawnCostNs = 300_000.0; // pzopt: mean creation cost of one zombie

   private void pzoptDrainSpawns() { // pzopt
      if (this.pzoptSpawnFirstNs == 0L) { // pzopt
         this.pzoptSpawnFirstNs = System.nanoTime(); // pzopt
      } // pzopt
      if (System.nanoTime() - this.pzoptSpawnFirstNs < pzopt.Config.ZOMBIE_SPAWN_LOAD_MS * 1_000_000L) { // pzopt: the world's first seconds (the load's
         this.pzoptDrainSpawnsAll(); // pzopt: mass spawn, under stock's own load fade): all at once, as stock
         return; // pzopt
      } // pzopt
      if (pzopt.Config.INSTRUMENT && System.nanoTime() - this.pzoptSpawnLogNs > 10_000_000_000L) { // pzopt
         this.pzoptSpawnLogNs = System.nanoTime(); // pzopt
         pzopt.Log.info("zombie spawn spread: queued " + pzoptSpawnQueued + " max queue " + pzoptSpawnMax + " now " + (this.pzoptSpTail - this.pzoptSpHead) + " aged " + pzoptSpawnAged); // pzopt
      } // pzopt
      long t0 = System.nanoTime(); // pzopt
      long budget = pzopt.Config.ZOMBIE_SPAWN_BUDGET_US * 1000L; // pzopt
      // the budget grows with the backlog so any queue drains within zombieSpawnDrainFrames frames (the load's mass spawn:
      // ~2,000 zombies in half a second instead of one 60 ms frame or a horde trickling in over many seconds)
      long backlogNs = (long)((this.pzoptSpTail - this.pzoptSpHead) * this.pzoptSpawnCostNs / Math.max(1, pzopt.Config.ZOMBIE_SPAWN_DRAIN_FRAMES)); // pzopt
      budget = Math.max(budget, Math.min(backlogNs, pzopt.Config.ZOMBIE_SPAWN_MAX_US * 1000L)); // pzopt: never more than zombieSpawnMaxUs a frame
      int pzoptMin = pzopt.Config.ZOMBIE_SPAWN_MIN; // pzopt
      if (pzopt.Config.ZOMBIE_SPAWN_SLACK_BUDGET_US > 0 && pzopt.Pacing.capIntervalNs() > 0L) { // pzopt: zombieSpawnSlackBudgetUs (f9-burst), at a cap the
         // pzopt: frame's budget is what the last step left of its interval (less this margin), up to zombieSpawnMaxUs: the queue drains in light
         // pzopt: frames and waits through heavy ones (the max-age rule still applies)
         budget = Math.max(0L, Math.min(pzopt.Config.ZOMBIE_SPAWN_MAX_US * 1000L, pzopt.Pacing.capIntervalNs() - pzopt.Pacing.lastWorkNs() - pzopt.Config.ZOMBIE_SPAWN_SLACK_BUDGET_US * 1000L)); // pzopt
         if (budget == 0L) { // pzopt
            pzoptMin = 0; // pzopt
         } // pzopt
      } // pzopt
      if (pzopt.Pacing.heavyStep()) { // pzopt: burstGovernor (f8-resid), a heavy step creates fewer
         budget = Math.min(budget, pzopt.Config.ZOMBIE_SPAWN_BURST_US * 1000L); // pzopt
         pzoptMin = pzopt.Config.ZOMBIE_SPAWN_HEAVY_MIN; // pzopt: 1 as before; zombieSpawnHeavyMin (f9-burst) 0 lets a heavy step create none
      } // pzopt
      int made = 0; // pzopt
      long pzoptOld = System.nanoTime() - pzopt.Config.ZOMBIE_SPAWN_MAX_AGE_MS * 1_000_000L; // pzopt: an entry older than this goes now, whatever the budget
      // pzopt: zombieSpawnAgedCapUs (f9-burst), the aged entries of one frame stop after this much time (a heavy step: after
      // zombieSpawnBurstUs) unless they waited three times zombieSpawnMaxAgeMs: what the route's first chunk rows queued
      // aged together and was all created in the first frames of the horde's first sight
      long pzoptAgedCap = pzopt.Config.ZOMBIE_SPAWN_AGED_CAP_US > 0 ? (pzopt.Pacing.heavyStep() ? Math.min(pzopt.Config.ZOMBIE_SPAWN_AGED_CAP_US, pzopt.Config.ZOMBIE_SPAWN_BURST_US) : pzopt.Config.ZOMBIE_SPAWN_AGED_CAP_US) * 1000L : Long.MAX_VALUE; // pzopt
      long pzoptVeryOld = pzoptOld - 2L * pzopt.Config.ZOMBIE_SPAWN_MAX_AGE_MS * 1_000_000L; // pzopt
      while (this.pzoptSpHead < this.pzoptSpTail
            && (made < pzoptMin || System.nanoTime() - t0 < budget
               || this.pzoptSpNs[this.pzoptSpHead] < pzoptOld && (System.nanoTime() - t0 < pzoptAgedCap || this.pzoptSpNs[this.pzoptSpHead] < pzoptVeryOld))) { // pzopt
         if (System.nanoTime() - t0 >= budget && made >= pzoptMin) { // pzopt: f9-burst census, created only because it was aged
            pzoptSpawnAged++; // pzopt
         } // pzopt
         this.pzoptSpawnOne(); // pzopt
         made++; // pzopt
      } // pzopt
      if (pzopt.Pacing.heavyStep() && this.pzoptSpHead < this.pzoptSpTail) { // pzopt: burstGovernor census
         pzopt.Pacing.spawnsHeld++; // pzopt
      } // pzopt
      if (made > 0) { // pzopt: the creation cost, a running mean
         double each = (double)(System.nanoTime() - t0) / made; // pzopt
         this.pzoptSpawnCostNs = this.pzoptSpawnCostNs * 0.9 + each * 0.1; // pzopt
      } // pzopt
   } // pzopt

   private void pzoptDrainSpawnsAll() { // pzopt
      while (this.pzoptSpHead < this.pzoptSpTail) { // pzopt
         this.pzoptSpawnOne(); // pzopt
      } // pzopt
   } // pzopt

   private void addZombieStanding(float x, float y, float z, IsoDirections dir, int descriptorID, ZombieStateFlags state, int persistentId) {
      if (!isStaleRecord(persistentId)) {
         IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), PZMath.fastfloor(z));
         if (sq != null && (sq.solidFloorCached ? sq.solidFloor : sq.TreatAsSolidFloor())) {
            if (!Core.lastStand && !this.playerSpawns.allowZombie(sq)) {
               noise("removed zombie near player spawn " + PZMath.fastfloor(x) + "," + PZMath.fastfloor(y) + "," + PZMath.fastfloor(z));
            } else {
               VirtualZombieManager.instance.choices.clear();
               IsoGridSquare sqWall = null;
               if (!state.isCrawling() && !state.isFakeDead() && Rand.Next(3) == 0) {
                  sqWall = this.getSquareForSittingZombie(x, y, PZMath.fastfloor(z));
               }

               if (sqWall != null) {
                  VirtualZombieManager.instance.choices.add(sqWall);
               } else {
                  VirtualZombieManager.instance.choices.add(sq);
               }

               IsoZombie realZombie = VirtualZombieManager.instance.createRealZombieAlways(descriptorID, dir, false, persistentId);
               if (realZombie == null) {
                  DebugType.Zombie.debugln("Failed to create standing Zombie.");
               } else {
                  if (sqWall != null) {
                     this.sitAgainstWall(realZombie, sqWall);
                  } else {
                     realZombie.setX(x);
                     realZombie.setY(y);
                  }

                  if (state.isFakeDead()) {
                     realZombie.setHealth(0.5F + Rand.Next(0.0F, 0.3F));
                     realZombie.sprite = realZombie.legsSprite;
                     realZombie.setFakeDead(true);
                  } else if (state.isCrawling()) {
                     realZombie.setCrawler(true);
                     realZombie.setCanWalk(state.isCanWalk());
                     realZombie.setOnFloor(true);
                     realZombie.setFallOnFront(true);
                     realZombie.walkVariant = "ZombieWalk";
                     realZombie.DoZombieStats();
                  }

                  if (state.isInitialized()) {
                     realZombie.setCanCrawlUnderVehicle(state.isCanCrawlUnderVehicle());
                  } else {
                     this.firstTimeLoaded(realZombie, state);
                  }

                  realZombie.setReanimatedForGrappleOnly(state.isReanimatedForGrappleOnly());
                  DebugType.Zombie.debugln("Created standing Zombie: %s", new Object[]{realZombie});
               }
            }
         } else {
            noise("real -> unloaded");
            n_addZombie(x, y, z, (byte)dir.ordinal(), descriptorID, state.asInt(), Integer.MIN_VALUE, Integer.MIN_VALUE, persistentId);
         }
      }
   }

   private IsoGridSquare getSquareForSittingZombie(float x, float y, int z) {
      int checkRange = 3;

      for (int dx = -3; dx < 3; dx++) {
         for (int dy = -3; dy < 3; dy++) {
            IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x) + dx, PZMath.fastfloor(y) + dy, z);
            if (square != null && square.isFree(true) && square.getBuilding() == null) {
               int wallType = square.getWallType();
               if (wallType != 0 && !PolygonalMap2.instance.lineClearCollide(x, y, square.x + 0.5F, square.y + 0.5F, square.z, null, false, true)) {
                  return square;
               }
            }
         }
      }

      return null;
   }

   public void sitAgainstWall(IsoZombie zombie, IsoGridSquare square) {
      float zedX = square.x + 0.5F;
      float zedY = square.y + 0.5F;
      zombie.setX(zedX);
      zombie.setY(zedY);
      zombie.setSitAgainstWall(true);
      int wallType = square.getWallType();
      if (wallType != 0) {
         this.sittingDirections.clear();
         if ((wallType & 1) != 0 && (wallType & 4) != 0) {
            this.sittingDirections.add(IsoDirections.SE);
         }

         if ((wallType & 1) != 0 && (wallType & 8) != 0) {
            this.sittingDirections.add(IsoDirections.SW);
         }

         if ((wallType & 2) != 0 && (wallType & 4) != 0) {
            this.sittingDirections.add(IsoDirections.NE);
         }

         if ((wallType & 2) != 0 && (wallType & 8) != 0) {
            this.sittingDirections.add(IsoDirections.NW);
         }

         if ((wallType & 1) != 0) {
            this.sittingDirections.add(IsoDirections.S);
         }

         if ((wallType & 2) != 0) {
            this.sittingDirections.add(IsoDirections.N);
         }

         if ((wallType & 4) != 0) {
            this.sittingDirections.add(IsoDirections.E);
         }

         if ((wallType & 8) != 0) {
            this.sittingDirections.add(IsoDirections.W);
         }

         IsoDirections dir = (IsoDirections)PZArrayUtil.pickRandom(this.sittingDirections);
         if (GameClient.client) {
            int index = (square.x & 1) + (square.y & 1);
            dir = this.sittingDirections.get(index % this.sittingDirections.size());
         }

         zombie.setDir(dir);
         zombie.setForwardDirection(dir.ToVector());
         if (zombie.getAnimationPlayer() != null) {
            zombie.getAnimationPlayer().setTargetAndCurrentDirection(zombie.getForwardDirectionX(), zombie.getForwardDirectionY());
         }
      }
   }

   private void addZombieMoving(
      float x, float y, float z, IsoDirections dir, int descriptorID, ZombieStateFlags state, int pathTargetX, int pathTargetY, int persistentId
   ) {
      if (!isStaleRecord(persistentId)) {
         IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), PZMath.fastfloor(z));
         if (sq != null && (sq.solidFloorCached ? sq.solidFloor : sq.TreatAsSolidFloor())) {
            if (!Core.lastStand && !this.playerSpawns.allowZombie(sq)) {
               noise("removed zombie near player spawn " + PZMath.fastfloor(x) + "," + PZMath.fastfloor(y) + "," + PZMath.fastfloor(z));
            } else {
               VirtualZombieManager.instance.choices.clear();
               VirtualZombieManager.instance.choices.add(sq);
               IsoZombie realZombie = VirtualZombieManager.instance.createRealZombieAlways(descriptorID, dir, false, persistentId);
               if (realZombie == null) {
                  DebugType.Zombie.debugln("Failed to create moving Zombie.");
               } else {
                  realZombie.setX(x);
                  realZombie.setY(y);
                  if (state.isCrawling()) {
                     realZombie.setCrawler(true);
                     realZombie.setCanWalk(state.isCanWalk());
                     realZombie.setOnFloor(true);
                     realZombie.setFallOnFront(true);
                     realZombie.walkVariant = "ZombieWalk";
                     realZombie.DoZombieStats();
                  }

                  if (state.isInitialized()) {
                     realZombie.setCanCrawlUnderVehicle(state.isCanCrawlUnderVehicle());
                  } else {
                     this.firstTimeLoaded(realZombie, state);
                  }

                  if (Math.abs(pathTargetX - x) > 1.0F || Math.abs(pathTargetY - y) > 1.0F) {
                     realZombie.allowRepathDelay = -1.0F;
                     realZombie.pathToLocation(pathTargetX, pathTargetY, 0);
                  }

                  realZombie.setReanimatedForGrappleOnly(state.isReanimatedForGrappleOnly());
                  DebugType.Zombie.debugln("Created moving Zombie: %s", new Object[]{realZombie});
               }
            }
         } else {
            noise("real -> virtual " + x + "," + y);
            n_addZombie(x, y, z, (byte)dir.ordinal(), descriptorID, state.asInt(), pathTargetX, pathTargetY, persistentId);
         }
      }
   }

   private void firstTimeLoaded(IsoZombie zombie, ZombieStateFlags state) {
   }

   public void updateThread() {
      n_updateThread();
   }

   public boolean shouldWait() {
      synchronized (MapCollisionData.instance.renderLock) {
         return n_shouldWait();
      }
   }

   public void updateLoadedAreas() {
      if (this.loadedAreas.set()) {
         n_loadedAreas(this.loadedAreas.count, this.loadedAreas.areas, false);
      }

      if (GameServer.server && this.loadedServerCells.set()) {
         n_loadedAreas(this.loadedServerCells.count, this.loadedServerCells.areas, true);
      }
   }

   public void dbgSpawnTimeToZero(int cellX, int cellY) {
      if (!GameClient.client) {
         DebugCommands.n_debugCommand(3, cellX, cellY);
      } else if (GameClient.connection.getRole().hasCapability(Capability.PopmanManage)) {
         PopmanDebugCommandPacket packet = new PopmanDebugCommandPacket();
         packet.setSpawnTimeToZero((short)cellX, (short)cellY);
         if (packet.isConsistent(GameClient.connection)) {
            packet.processClient(null);
         }
      }
   }

   public void dbgClearZombies(int cellX, int cellY) {
      if (!GameClient.client) {
         DebugCommands.n_debugCommand(4, cellX, cellY);
      } else if (GameClient.connection.getRole().hasCapability(Capability.PopmanManage)) {
         PopmanDebugCommandPacket packet = new PopmanDebugCommandPacket();
         packet.setClearZombies((short)cellX, (short)cellY);
         if (packet.isConsistent(GameClient.connection)) {
            packet.processClient(null);
         }
      }
   }

   public void dbgSpawnNow(int cellX, int cellY) {
      if (!GameClient.client) {
         DebugCommands.n_debugCommand(5, cellX, cellY);
      } else if (GameClient.connection.getRole().hasCapability(Capability.PopmanManage)) {
         PopmanDebugCommandPacket packet = new PopmanDebugCommandPacket();
         packet.setSpawnNow((short)cellX, (short)cellY);
         if (packet.isConsistent(GameClient.connection)) {
            packet.processClient(null);
         }
      }
   }

   public void save() {
      if (!GameClient.client) {
         n_save();
      }
   }

   public void stop() {
      if (!GameClient.client) {
         this.stopped = true;
         n_stop();
         realZombies.clear();
         pendingSaveCellKeys.clear();
         lastSaveCellTimeMs.clear();
         this.lastReportedUnregisteredCount = 0;
         this.loadedAreas.clear();
         this.newChunks.clear();
         this.spawnOrigins.clear();
         this.radarXy = null;
         this.radarCount = 0;
         this.radarRenderFlag = false;
         this.radarRequestFlag = false;
         this.zombiesMinPerChunk = 0.0F;
         this.zombiesMaxPerChunk = 255.0F;
      }
   }
}
