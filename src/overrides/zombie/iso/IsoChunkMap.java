package zombie.iso;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.UsedFromLua;
import zombie.GameProfiler.ProfileArea;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.physics.WorldSimulation;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.textures.ColorInfo;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.areas.IsoRoom;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.ui.TextManager;
import zombie.util.CappedConcurrentQueue;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleCache;
import zombie.vehicles.VehicleManager;

@UsedFromLua
public final class IsoChunkMap {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.IsoChunkMap");
   }

   public static final int LEVELS = 64;
   public static final int GROUND_LEVEL = 32;
   public static final int TOP_LEVEL = 31;
   public static final int BOTTOM_LEVEL = -32;
   public static final int OLD_CHUNKS_PER_WIDTH = 10;
   public static final int CHUNKS_PER_WIDTH = 8;
   public static final int CHUNK_SIZE_IN_SQUARES = 8;
   public static final HashMap<Integer, IsoChunk> SharedChunks = new HashMap<>();
   public static int mpWorldXa;
   public static int mpWorldYa;
   public static int mpWorldZa;
   public static int worldXa = 11702;
   public static int worldYa = 6896;
   public static int worldZa;
   public static final int[] SWorldX = new int[4];
   public static final int[] SWorldY = new int[4];
   public static final CappedConcurrentQueue<IsoChunk> chunkStore = new CappedConcurrentQueue(1024);
   public static final ReentrantLock bSettingChunk = new ReentrantLock(true);
   private static final int START_CHUNK_GRID_WIDTH = 13;
   public static int chunkGridWidth = 13;
   public static int chunkWidthInTiles = 8 * chunkGridWidth;
   private static final ColorInfo inf = new ColorInfo();
   private static final ArrayList<ArrayList<IsoFloorBloodSplat>> splatByType = new ArrayList<>();
   public int playerId;
   public boolean ignore;
   public int worldX = chunkMapSquareToChunkMapChunkXY(worldXa);
   public int worldY = chunkMapSquareToChunkMapChunkXY(worldYa);
   public final ArrayList<String> filenameServerRequests = new ArrayList<>();
   protected IsoChunk[] chunksSwapB;
   protected IsoChunk[] chunksSwapA;
   boolean readBufferA = true;
   int xMinTiles = -1;
   int yMinTiles = -1;
   int xMaxTiles = -1;
   int yMaxTiles = -1;
   private final IsoCell cell;
   private final UpdateLimit checkVehiclesFrequency = new UpdateLimit(3000L);
   private final UpdateLimit hotSaveFrequency = new UpdateLimit(1000L);
   public int maxHeight;
   public int minHeight;
   public static final PerformanceProfileProbe ppp_update;

   public IsoChunkMap(IsoCell cell) {
      this.cell = cell;
      pzopt.CellObjectSet.install(cell); // pzopt: animalLosWalk (tail pass), the cell's still-empty object set swapped for the counting subclass
      WorldReuserThread.instance.finished = false;
      this.chunksSwapB = new IsoChunk[chunkGridWidth * chunkGridWidth];
      this.chunksSwapA = new IsoChunk[chunkGridWidth * chunkGridWidth];
   }

   public static void CalcChunkWidth() {
      if (DebugOptions.instance.worldChunkMap13x13.getValue()) {
         chunkGridWidth = 13;
         chunkWidthInTiles = chunkGridWidth * 8;
      } else if (DebugOptions.instance.worldChunkMap11x11.getValue()) {
         chunkGridWidth = 11;
         chunkWidthInTiles = chunkGridWidth * 8;
      } else if (DebugOptions.instance.worldChunkMap9x9.getValue()) {
         chunkGridWidth = 9;
         chunkWidthInTiles = chunkGridWidth * 8;
      } else if (DebugOptions.instance.worldChunkMap7x7.getValue()) {
         chunkGridWidth = 7;
         chunkWidthInTiles = chunkGridWidth * 8;
      } else if (DebugOptions.instance.worldChunkMap5x5.getValue()) {
         chunkGridWidth = 5;
         chunkWidthInTiles = chunkGridWidth * 8;
      } else {
         float delx = Core.getInstance().getScreenWidth() / 1920.0F;
         float dely = Core.getInstance().getScreenHeight() / 1080.0F;
         float del = Math.max(delx, dely);
         if (del > 1.0F) {
            del = 1.0F;
         }

         chunkGridWidth = (int)(13.0F * del * 1.5);
         if (chunkGridWidth / 2 * 2 == chunkGridWidth) {
            chunkGridWidth++;
         }

         chunkGridWidth = PZMath.min(chunkGridWidth, 19);
         // pzopt: chunkGridWidth, the player's chunk grid size instead of the screen-size one (0 = the stock value above,
         // "auto" = wide enough to fill the screen at the widest zoom); odd like stock so the player's chunk stays the centre
         if (pzopt.Overrides.enabled() && (pzopt.Config.CHUNK_GRID_AUTO || pzopt.Config.CHUNK_GRID_WIDTH > 0)) { // pzopt
            int pzoptStock = chunkGridWidth; // pzopt
            pzopt.ChunkGrid.stock = pzoptStock; // pzopt: the cap of the height shift in ProcessChunkPos
            chunkGridWidth = pzopt.ChunkGrid.width(pzoptStock, pzopt.Config.CHUNK_GRID_AUTO, pzopt.Config.CHUNK_GRID_WIDTH, // pzopt
               Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), Core.getInstance().getMaxZoom()); // pzopt
            pzopt.Log.info("chunk grid: " + chunkGridWidth + " (setting " + pzopt.Config.CHUNK_GRID_SETTING + ", stock " + pzoptStock // pzopt
               + ", screen " + Core.getInstance().getScreenWidth() + "x" + Core.getInstance().getScreenHeight() + ", max zoom " + Core.getInstance().getMaxZoom() + ")"); // pzopt
         } // pzopt
         chunkWidthInTiles = chunkGridWidth * 8;
      }
   }

   public static void setWorldStartPos(int x, int y) {
      SWorldX[IsoPlayer.getPlayerIndex()] = chunkMapSquareToChunkMapChunkXY(x);
      SWorldY[IsoPlayer.getPlayerIndex()] = chunkMapSquareToChunkMapChunkXY(y);
   }

   public void Dispose() {
      IsoChunk.loadGridSquare.clear();
      this.chunksSwapA = null;
      this.chunksSwapB = null;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   public void setInitialPos(int wx, int wy) {
      this.worldX = wx;
      this.worldY = wy;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
      this.xMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMinTiles = -1;
      this.yMaxTiles = -1;
   }

   public void processAllLoadGridSquare() {
      // pzopt: centerFirstLoad, at world entry only the chunks around the player are handed over here (the rest go back
      // on the queue for update(), which hands chunks over a few per frame as it does while walking): 0.44 s of
      // boundary recalc on the main thread before the first world frame otherwise
      java.util.ArrayList<IsoChunk> pzoptLater = pzopt.CenterFirstLoad.enteredEarly() ? new java.util.ArrayList<>() : null;
      for (IsoChunk chunk = (IsoChunk)IsoChunk.loadGridSquare.poll(); chunk != null; chunk = (IsoChunk)IsoChunk.loadGridSquare.poll()) {
         if (pzoptLater != null && !pzopt.CenterFirstLoad.nearCenter(chunk.wx, chunk.wy)) {
            pzoptLater.add(chunk);
            continue;
         }
         bSettingChunk.lock();

         try {
            boolean loaded = false;

            for (int n = 0; n < IsoPlayer.numPlayers; n++) {
               IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[n];
               if (!cm.ignore && cm.setChunkDirect(chunk, false)) {
                  loaded = true;
               }
            }

            if (!loaded) {
               WorldReuserThread.instance.addReuseChunk(chunk);
            } else {
               chunk.doLoadGridsquare();
            }
         } finally {
            bSettingChunk.unlock();
         }
      }
      if (pzoptLater != null) {
         for (IsoChunk chunk : pzoptLater) {
            IsoChunk.loadGridSquare.add(chunk); // same order: nearest first (the streamer served them that way)
         }
      }
   }

   public void update() {
      AbstractPerformanceProfileProbe var1 = ppp_update.profile();

      try {
         long pzoptT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
         try { // pzopt
            this.updateInternal();
         } finally { // pzopt
            pzopt.GtAb.end(pzopt.GtAb.S_CHUNKMAP, pzoptT); // pzopt
         } // pzopt
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
      boolean bChanged = false;
      pzopt.LootDefer.drain(); // pzopt: lootDefer, this frame's share of the queued far containers
      pzopt.PoolWarm.frame(); // pzopt: renderPoolMax (render-data pool caps) and devPoolLog
      pzopt.ChunkPace.frame(IsoChunk.loadGridSquare.size() + (pzoptStaged != null ? 1 : 0)); // pzopt: chunkHandoffPaceMs, this frame's hand-off credit
      int count = IsoChunk.loadGridSquare.size();
      if (count != 0) {
         count = 1 + count * 3 / chunkGridWidth;
         // pzopt: chunk hand-off budget (Config.CHUNK_HANDOFF_DIVISOR). doLoadGridsquare (loot roll, erosion, recalc,
         // pathfind) is 1 to 5 ms per chunk on the game thread and stock hands off up to 1 + 3 * queue / gridWidth
         // per frame, so a chunk row arriving at once makes one 10 to 25 ms frame. Now at most 1 + queue / divisor
         // per frame: the same work spread over the following frames, the queue still drains faster than it fills.
         int pzoptDiv = pzopt.Overrides.enabled() ? pzopt.Config.CHUNK_HANDOFF_DIVISOR : 0;
         if (pzoptDiv > 0) {
            count = Math.min(count, 1 + IsoChunk.loadGridSquare.size() / pzoptDiv);
         }
         count = pzopt.ChunkPace.limit(count, IsoChunk.loadGridSquare.size()); // pzopt: chunkHandoffPaceMs, a steady rate instead of a row at once
         if (pzopt.ChunkHandoff.defer(IsoChunk.loadGridSquare.size())) { // pzopt: chunkHandoffSlack, wait for a frame with headroom
            count = 0; // pzopt
         } // pzopt
      }
      if (pzopt.Config.CHUNK_HANDOFF_SLACK_WORK && pzopt.Overrides.enabled() && pzopt.SlackWork.active()
            && IsoChunk.loadGridSquare.size() < pzopt.Config.CHUNK_HANDOFF_SLACK_BACKLOG && !GameClient.client && !GameServer.server) { // pzopt: chunkHandoffSlackWork,
         count = 0; // pzopt: the chunks join in the step's slack, one at a time (a world load / teleport backlog goes as above)
         pzopt.SlackWork.register(PZOPT_HANDOFF); // pzopt
      } else if (pzoptStaged != null) { // pzopt: a chunk the slack producer took off the queue goes first, in queue order
         IsoChunk pzoptChunk = pzoptStaged; // pzopt
         pzoptStaged = null; // pzopt
         if (this.pzoptHandOffChunk(pzoptChunk)) { // pzopt
            bChanged = true; // pzopt
         } // pzopt
      } // pzopt

      // pzopt: chunkHandoffFrameBudgetUs (f8-handoff), a backlog's in-frame hand-off stops once this frame's hand-offs took the
      // budget (at least one chunk, at most the stock count); the rest wait for the slack or the next frame
      long pzoptBudgetNs = pzopt.Config.CHUNK_HANDOFF_FRAME_BUDGET_US > 0 && pzopt.Overrides.enabled() && pzopt.GtAb.on(pzopt.GtAb.HANDOFF_SCHED) // pzopt
         && pzopt.SlackWork.active() && IsoChunk.loadGridSquare.size() < pzopt.Config.CHUNK_HANDOFF_PACE_BACKLOG ? pzopt.Config.CHUNK_HANDOFF_FRAME_BUDGET_US * 1000L : 0L; // pzopt
      long pzoptLoopT0 = System.nanoTime(); // pzopt
      while (count > 0) {
         if (pzoptBudgetNs > 0L && System.nanoTime() - pzoptLoopT0 >= pzoptBudgetNs) { // pzopt
            break; // pzopt
         } // pzopt
         IsoChunk chunk = (IsoChunk)IsoChunk.loadGridSquare.poll();
         if (chunk != null) {
            long pzoptT0 = System.nanoTime(); // pzopt: chunkHandoffCost=chunk learns from the in-frame hand-offs too
            if (this.pzoptHandOffChunk(chunk)) { // pzopt: the body moved to pzoptHandOffChunk
               bChanged = true; // pzopt
            } // pzopt
            PZOPT_CHUNK_COST.learn(System.nanoTime() - pzoptT0); // pzopt
         }

         count--;
      }

      if (bChanged) {
         this.calculateZExtentsForChunkMap();
      }

      boolean hotSave = !GameClient.client && !GameServer.server && this.hotSaveFrequency.Check();

      for (int y = 0; y < chunkGridWidth; y++) {
         for (int x = 0; x < chunkGridWidth; x++) {
            IsoChunk chunk = this.getChunk(x, y);
            if (chunk != null) {
               chunk.update();
               if (hotSave && chunk.requiresHotSave && ChunkSaveWorker.instance.toSaveQueue.size() < 10) {
                  ChunkSaveWorker.instance.AddHotSave(chunk);
                  chunk.requiresHotSave = false;
               }
            }
         }
      }

      if (GameClient.client && this.checkVehiclesFrequency.Check()) {
         this.checkVehicles();
      }
   }

   // pzopt: chunkHandoffSlackWork (2026-10-05, Louisville 120 fps pass). A chunk's hand-off (doLoadGridsquare: the border
   // recalc with its neighbours, building randomisation, vehicles, rats; 1 to 50 ms downtown) ran inside the frame, where a
   // chunk row's arrival also brought its lighting and its first bakes. At a cap the queued chunks now join in the step's
   // slack (pzopt.SlackWork), one at a time while the learned cost per level times the chunk's levels fits the time left.
   private static IsoChunk pzoptStaged; // pzopt: the next chunk, taken off the queue (it has no peek) to size it
   private static int pzoptStagedFrame; // pzopt
   private static final pzopt.SlackWork.Cost PZOPT_LEVEL_COST = new pzopt.SlackWork.Cost(20_000.0, 0.5); // pzopt: hand-off ns per square (levels of a downtown tower are mostly empty)
   private static int pzoptStagedSquares; // pzopt
   private static final pzopt.SlackWork.Cost PZOPT_CHUNK_COST = new pzopt.SlackWork.Cost(500_000.0, 0.0); // pzopt: chunkHandoffCost=chunk, a whole hand-off's running mean (slack and in-frame ones)
   private static final pzopt.SlackWork.Producer PZOPT_HANDOFF = new pzopt.SlackWork.Producer() { // pzopt
      public boolean pending() { // pzopt
         if (pzoptStaged == null) { // pzopt
            pzoptStaged = (IsoChunk)IsoChunk.loadGridSquare.poll(); // pzopt
            pzoptStagedFrame = pzopt.SlackWork.frame(); // pzopt
            pzoptStagedSquares = pzoptSquares(pzoptStaged); // pzopt
         } // pzopt
         return pzoptStaged != null && pzopt.ChunkPace.mayTake(IsoChunk.loadGridSquare.size() + 1); // pzopt: chunkHandoffPaceMs in the slack too
      } // pzopt
      public long nextCostNs() { // pzopt
         if (pzopt.Config.CHUNK_HANDOFF_COST_CHUNK && pzopt.GtAb.on(pzopt.GtAb.HANDOFF_SCHED)) { // pzopt: chunkHandoffCost=chunk (f8-handoff), the running mean of a whole hand-off
            return PZOPT_CHUNK_COST.estimate(); // pzopt
         } // pzopt
         return PZOPT_LEVEL_COST.estimate() * pzoptStagedSquares; // pzopt
      } // pzopt
      public boolean forceAnyFrame() { // pzopt: chunkHandoffSlackMaxWait with chunkHandoffForceAnyFrame (f8-handoff): an overdue chunk joins in the next slack, light or not
         return pzopt.Config.CHUNK_HANDOFF_FORCE_ANY_FRAME && pzopt.GtAb.on(pzopt.GtAb.HANDOFF_SCHED); // pzopt
      } // pzopt
      public int nextAgeFrames() { // pzopt
         return pzopt.SlackWork.frame() - pzoptStagedFrame; // pzopt
      } // pzopt
      public int maxWaitFrames() { // pzopt: chunkHandoffSlackMaxWait (f8-handoff), a staged chunk that does not fit the slack joins after this many frames (0 = slackMaxWaitFrames)
         return pzopt.Config.CHUNK_HANDOFF_SLACK_MAX_WAIT > 0 && pzopt.GtAb.on(pzopt.GtAb.HANDOFF_SCHED) ? pzopt.Config.CHUNK_HANDOFF_SLACK_MAX_WAIT : pzopt.Config.SLACK_MAX_WAIT_FRAMES; // pzopt
      } // pzopt
      public void runNext() { // pzopt
         IsoChunk chunk = pzoptStaged; // pzopt
         int squares = pzoptStagedSquares; // pzopt
         pzoptStaged = null; // pzopt
         IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[0]; // pzopt
         long t0 = System.nanoTime(); // pzopt
         pzopt.ChunkHandoff.inSlack = true; // pzopt: census
         boolean pzoptJoined; // pzopt
         try { // pzopt
            pzoptJoined = cm.pzoptHandOffChunk(chunk); // pzopt
         } finally { // pzopt
            pzopt.ChunkHandoff.inSlack = false; // pzopt
         } // pzopt
         if (pzoptJoined) { // pzopt
            for (int n = 0; n < IsoPlayer.numPlayers; n++) { // pzopt: the loop's calculateZExtentsForChunkMap, for each player's map
               IsoChunkMap m = IsoWorld.instance.currentCell.chunkMap[n]; // pzopt
               if (m != null && !m.ignore) { // pzopt
                  m.calculateZExtentsForChunkMap(); // pzopt
               } // pzopt
            } // pzopt
         } // pzopt
         long pzoptNs = System.nanoTime() - t0; // pzopt
         PZOPT_LEVEL_COST.learn(pzoptNs / squares); // pzopt
         PZOPT_CHUNK_COST.learn(pzoptNs); // pzopt: chunkHandoffCost=chunk
         pzopt.ChunkPace.took(); // pzopt: chunkHandoffPaceMs
      } // pzopt
   }; // pzopt

   private static int pzoptSquares(IsoChunk c) { // pzopt: the chunk's squares, its hand-off's size
      if (c == null || c.squares == null) { // pzopt
         return 64; // pzopt
      } // pzopt
      int n = 0; // pzopt
      for (IsoGridSquare[] level : c.squares) { // pzopt
         if (level != null) { // pzopt
            for (IsoGridSquare sq : level) { // pzopt
               if (sq != null) { // pzopt
                  n++; // pzopt
               } // pzopt
            } // pzopt
         } // pzopt
      } // pzopt
      return Math.max(16, n); // pzopt
   } // pzopt

   /** pzopt: one chunk's hand-off, the stock loop body of updateInternal (true when the chunk joined the world); also run from the frame's slack (chunkHandoffSlackWork). */
   private boolean pzoptHandOffChunk(IsoChunk chunk) { // pzopt
      boolean bChanged = false; // pzopt
      pzopt.ChunkHandoff.waited(chunk.pzoptQueuedNs); // pzopt: f8-handoff census
      if (pzopt.Config.DEV_HANDOFF_LOG && pzopt.Config.INSTRUMENT) { // pzopt: devHandoffLog, the chunk's shape before its hand-off
         int pzoptObj = 0; // pzopt
         if (chunk.squares != null) { // pzopt
            for (IsoGridSquare[] pzoptLevel : chunk.squares) { // pzopt
               if (pzoptLevel != null) { // pzopt
                  for (IsoGridSquare pzoptSq : pzoptLevel) { // pzopt
                     if (pzoptSq != null) { // pzopt
                        pzoptObj += pzoptSq.getObjects().size(); // pzopt
                     } // pzopt
                  } // pzopt
               } // pzopt
            } // pzopt
         } // pzopt
         pzopt.ChunkHandoff.lastShape = "levels " + (chunk.maxLevel - chunk.minLevel + 1) + " squares " + pzoptSquares(chunk) + " objects " + pzoptObj // pzopt
            + " new " + (chunk.isNewChunk() ? 1 : 0) + " at " + chunk.wx + "," + chunk.wy; // pzopt
      } // pzopt
            boolean loaded = false;

            for (int n = 0; n < IsoPlayer.numPlayers; n++) {
               IsoChunkMap cm = IsoWorld.instance.currentCell.chunkMap[n];
               if (!cm.ignore && cm.setChunkDirect(chunk, false)) {
                  loaded = true;
               }
            }

            if (!loaded) {
               WorldReuserThread.instance.addReuseChunk(chunk);
               return false; // pzopt: the stock loop's count-- / continue
            }

            chunk.loaded = true;
            bSettingChunk.lock();

            try {
               ProfileArea var17 = GameProfiler.getInstance().profile("IsoChunk.doLoadGridsquare");

               try {
                  long pzoptT0 = System.nanoTime(); // pzopt: chunkHandoff census
                  chunk.doLoadGridsquare();
                  pzopt.ChunkHandoff.done(System.nanoTime() - pzoptT0); // pzopt
                  bChanged = true;
               } catch (Throwable var13) {
                  if (var17 != null) {
                     try {
                        var17.close();
                     } catch (Throwable var12) {
                        var13.addSuppressed(var12);
                     }
                  }

                  throw var13;
               }

               if (var17 != null) {
                  var17.close();
               }

               if (GameClient.client) {
                  List<VehicleCache> vehicles = VehicleCache.vehicleGet(chunk.wx, chunk.wy);
                  if (vehicles != null) {
                     for (VehicleCache vehicle : vehicles) {
                        VehicleManager.instance.sendVehicleRequest(vehicle.id, (short)1);
                     }
                  }
               }
            } finally {
               bSettingChunk.unlock();
            }

            for (int var19 = 0; var19 < IsoPlayer.numPlayers; var19++) {
               IsoPlayer player = IsoPlayer.players[var19];
               if (player != null) {
                  player.dirtyRecalcGridStackTime = 20.0F;
               }
            }
      return bChanged; // pzopt
   } // pzopt

   private void checkVehicles() {
      for (int y = 0; y < chunkGridWidth; y++) {
         for (int x = 0; x < chunkGridWidth; x++) {
            IsoChunk chunk = this.getChunk(x, y);
            if (chunk != null && chunk.loaded) {
               List<VehicleCache> vehicles = VehicleCache.vehicleGet(chunk.wx, chunk.wy);
               if (vehicles != null && chunk.vehicles.size() != vehicles.size()) {
                  for (int i = 0; i < vehicles.size(); i++) {
                     short id = vehicles.get(i).id;
                     boolean hasID = false;

                     for (int k = 0; k < chunk.vehicles.size(); k++) {
                        if (chunk.vehicles.get(k).getId() == id) {
                           hasID = true;
                           break;
                        }
                     }

                     if (!hasID && VehicleManager.instance.getVehicleByID(id) == null) {
                        VehicleManager.instance.sendVehicleRequest(id, (short)1);
                     }
                  }
               }
            }
         }
      }
   }

   public void checkIntegrity() {
      IsoWorld.instance.currentCell.chunkMap[0].xMinTiles = -1;

      for (int x = IsoWorld.instance.currentCell.chunkMap[0].getWorldXMinTiles(); x < IsoWorld.instance.currentCell.chunkMap[0].getWorldXMaxTiles(); x++) {
         for (int y = IsoWorld.instance.currentCell.chunkMap[0].getWorldYMinTiles(); y < IsoWorld.instance.currentCell.chunkMap[0].getWorldYMaxTiles(); y++) {
            IsoGridSquare grid = IsoWorld.instance.currentCell.getGridSquare(x, y, 0);
            if (grid != null && (grid.getX() != x || grid.getY() != y)) {
               IsoChunk ch = new IsoChunk(IsoWorld.instance.currentCell);
               ch.refs.add(IsoWorld.instance.currentCell.chunkMap[0]);
               WorldStreamer.instance.addJob(ch, x / 8, y / 8, false);

               while (!ch.loaded) {
                  try {
                     Thread.sleep(13L);
                  } catch (InterruptedException e) {
                     DebugType.General.printException(e, LogSeverity.Error);
                  }
               }
            }
         }
      }
   }

   public void checkIntegrityThread() {
      IsoWorld.instance.currentCell.chunkMap[0].xMinTiles = -1;

      for (int x = IsoWorld.instance.currentCell.chunkMap[0].getWorldXMinTiles(); x < IsoWorld.instance.currentCell.chunkMap[0].getWorldXMaxTiles(); x++) {
         for (int y = IsoWorld.instance.currentCell.chunkMap[0].getWorldYMinTiles(); y < IsoWorld.instance.currentCell.chunkMap[0].getWorldYMaxTiles(); y++) {
            IsoGridSquare grid = IsoWorld.instance.currentCell.getGridSquare(x, y, 0);
            if (grid != null && (grid.getX() != x || grid.getY() != y)) {
               IsoChunk ch = new IsoChunk(IsoWorld.instance.currentCell);
               ch.refs.add(IsoWorld.instance.currentCell.chunkMap[0]);
               WorldStreamer.instance.addJobInstant(ch, x, y, x / 8, y / 8);
            }

            if (grid != null) {
            }
         }
      }
   }

   public void LoadChunk(int wx, int wy, int x, int y) {
      if (SharedChunks.containsKey((wx << 16) + wy)) {
         IsoChunk chunk = SharedChunks.get((wx << 16) + wy);
         chunk.setCache();
         this.setChunk(x, y, chunk);
         chunk.refs.add(this);
      } else {
         IsoChunk chunk = (IsoChunk)chunkStore.poll();
         if (chunk == null) {
            chunk = new IsoChunk(this.cell);
         }

         chunk.assignLoadID();
         SharedChunks.put((wx << 16) + wy, chunk);
         chunk.refs.add(this);
         WorldStreamer.instance.addJob(chunk, wx, wy, false);
      }
   }

   public IsoChunk LoadChunkForLater(int wx, int wy, int x, int y) {
      if (!IsoWorld.instance.getMetaGrid().isValidChunk(wx, wy)) {
         return null;
      }

      IsoChunk chunk;
      if (SharedChunks.containsKey((wx << 16) + wy)) {
         chunk = SharedChunks.get((wx << 16) + wy);
         if (!chunk.refs.contains(this)) {
            chunk.refs.add(this);
            chunk.checkLightingLater_OnePlayer_AllLevels(this.playerId);
         }

         if (!chunk.loaded) {
            return chunk;
         }

         this.setChunk(x, y, chunk);
      } else {
         chunk = (IsoChunk)chunkStore.poll();
         if (chunk == null) {
            chunk = new IsoChunk(this.cell);
         }

         chunk.assignLoadID();
         SharedChunks.put((wx << 16) + wy, chunk);
         chunk.refs.add(this);
         WorldStreamer.instance.addJob(chunk, wx, wy, true);
      }

      return chunk;
   }

   public IsoChunk getChunkForGridSquare(int worldSquareX, int worldSquareY) {
      int chunkMapSquareX = this.worldSquareToChunkMapSquareX(worldSquareX);
      int chunkMapSquareY = this.worldSquareToChunkMapSquareY(worldSquareY);
      if (!this.isChunkMapSquareOutOfRangeXY(chunkMapSquareX) && !this.isChunkMapSquareOutOfRangeXY(chunkMapSquareY)) {
         int chunkMapChunkX = chunkMapSquareToChunkMapChunkXY(chunkMapSquareX);
         int chunkMapChunkY = chunkMapSquareToChunkMapChunkXY(chunkMapSquareY);
         return this.getChunk(chunkMapChunkX, chunkMapChunkY);
      } else {
         return null;
      }
   }

   public IsoChunk getChunkCurrent(int x, int y) {
      if (x < 0 || x >= chunkGridWidth || y < 0 || y >= chunkGridWidth) {
         return null;
      } else {
         return !this.readBufferA ? this.chunksSwapA[chunkGridWidth * y + x] : this.chunksSwapB[chunkGridWidth * y + x];
      }
   }

   public void setGridSquare(IsoGridSquare square, int worldSquareX, int worldSquareY, int worldSquareZ) {
      assert square == null || square.x == worldSquareX && square.y == worldSquareY && square.z == worldSquareZ;
      int chunkMapSquareX = this.worldSquareToChunkMapSquareX(worldSquareX);
      int chunkMapSquareY = this.worldSquareToChunkMapSquareY(worldSquareY);
      if (GameServer.server
         || !this.isChunkMapSquareOutOfRangeXY(chunkMapSquareX)
            && !this.isChunkMapSquareOutOfRangeXY(chunkMapSquareY)
            && !this.isWorldSquareOutOfRangeZ(worldSquareZ)) {
         IsoChunk c;
         if (GameServer.server) {
            int chunkMapChunkX = chunkMapSquareToChunkMapChunkXY(worldSquareX);
            int chunkMapChunkY = chunkMapSquareToChunkMapChunkXY(worldSquareY);
            c = ServerMap.instance.getChunk(chunkMapChunkX, chunkMapChunkY);
         } else {
            int chunkMapChunkX = chunkMapSquareToChunkMapChunkXY(chunkMapSquareX);
            int chunkMapChunkY = chunkMapSquareToChunkMapChunkXY(chunkMapSquareY);
            c = this.getChunk(chunkMapChunkX, chunkMapChunkY);
         }

         if (c != null) {
            c.setSquare(this.chunkMapSquareToChunkSquareXY(chunkMapSquareX), this.chunkMapSquareToChunkSquareXY(chunkMapSquareY), worldSquareZ, square);
         }
      }
   }

   public IsoGridSquare getGridSquare(int worldSquareX, int worldSquareY, int worldSquareZ) {
      int chunkMapSquareX = this.worldSquareToChunkMapSquareX(worldSquareX);
      int chunkMapSquareY = this.worldSquareToChunkMapSquareY(worldSquareY);
      return this.getGridSquareDirect(chunkMapSquareX, chunkMapSquareY, worldSquareZ);
   }

   public IsoGridSquare getGridSquareDirect(int chunkMapSquareX, int chunkMapSquareY, int worldSquareZ) {
      if (pzopt.Config.CHUNK_MAP_FAST) { // pzopt: the same lookup without the helper calls (C1 code on few-core machines, jitMode)
         int w = chunkWidthInTiles; // pzopt
         if (chunkMapSquareX < 0 || chunkMapSquareX >= w || chunkMapSquareY < 0 || chunkMapSquareY >= w || worldSquareZ < -32 || worldSquareZ > 31) { // pzopt
            return null; // pzopt
         } // pzopt
         int gw = chunkGridWidth; // pzopt
         int cx = chunkMapSquareX >> 3; // pzopt: non-negative here, so / 8
         int cy = chunkMapSquareY >> 3; // pzopt
         if (cx >= gw || cy >= gw) { // pzopt
            return null; // pzopt
         } // pzopt
         IsoChunk c = (this.readBufferA ? this.chunksSwapA : this.chunksSwapB)[gw * cy + cx]; // pzopt
         if (c == null || !c.loaded) { // pzopt
            return null; // pzopt
         } // pzopt
         int half = gw / 2; // pzopt: mid-scroll guard as below
         if (c.wx != this.worldX - half + cx || c.wy != this.worldY - half + cy) { // pzopt
            return null; // pzopt
         } // pzopt
         if (worldSquareZ > c.maxLevel || worldSquareZ < c.minLevel) { // pzopt
            return null; // pzopt
         } // pzopt
         IsoGridSquare[][] sq = c.squares; // pzopt
         int zz = worldSquareZ - c.minLevel; // pzopt: IsoChunk.squaresIndexOfLevel
         return zz < sq.length ? sq[zz][(chunkMapSquareY & 7) * 8 + (chunkMapSquareX & 7)] : null; // pzopt
      } // pzopt
      if (!this.isChunkMapSquareOutOfRangeXY(chunkMapSquareX)
         && !this.isChunkMapSquareOutOfRangeXY(chunkMapSquareY)
         && !this.isWorldSquareOutOfRangeZ(worldSquareZ)) {
         int chunkMapChunkX = chunkMapSquareToChunkMapChunkXY(chunkMapSquareX);
         int chunkMapChunkY = chunkMapSquareToChunkMapChunkXY(chunkMapSquareY);
         IsoChunk c = this.getChunk(chunkMapChunkX, chunkMapChunkY);
         if (c == null) {
            return null;
         }

         if (!c.loaded) {
            return null;
         }

         // pzopt: mid-scroll guard. LoadLeft/Right/Up/Down move worldX/worldY (so the callers' origin) before
         // pzopt: SwapChunkBuffers publishes the shifted grid, so a streamer/recalc-thread lookup in that window
         // pzopt: indexes the old grid with the new origin and gets a square one chunk off. IsoGridSquare.isWallTo
         // pzopt: then recurses on that same wrong square until the stack overflows (its depth check is a no-op).
         // pzopt: A chunk that is not where the index says it is reads as not loaded, as it does at the map edge.
         if (c.wx != this.getWorldXMin() + chunkMapChunkX || c.wy != this.getWorldYMin() + chunkMapChunkY) {
            return null;
         }

         int chunkSquareX = this.chunkMapSquareToChunkSquareXY(chunkMapSquareX);
         int chunkSquareY = this.chunkMapSquareToChunkSquareXY(chunkMapSquareY);
         return c.getGridSquare(chunkSquareX, chunkSquareY, worldSquareZ);
      } else {
         return null;
      }
   }

   private int chunkMapSquareToChunkSquareXY(int chunkMapSquareXY) {
      return chunkMapSquareXY % 8;
   }

   private static int chunkMapSquareToChunkMapChunkXY(int chunkMapSquareXY) {
      return chunkMapSquareXY / 8;
   }

   private boolean isChunkMapSquareOutOfRangeXY(int chunkMapSquareXY) {
      return chunkMapSquareXY < 0 || chunkMapSquareXY >= this.getWidthInTiles();
   }

   private boolean isWorldSquareOutOfRangeZ(int tileZ) {
      return tileZ < -32 || tileZ > 31;
   }

   private int worldSquareToChunkMapSquareX(int worldSquareX) {
      return worldSquareX - (this.worldX - chunkGridWidth / 2) * 8;
   }

   private int worldSquareToChunkMapSquareY(int worldSquareY) {
      return worldSquareY - (this.worldY - chunkGridWidth / 2) * 8;
   }

   public IsoChunk getChunk(int chunkMapChunkX, int chunkMapChunkY) {
      if (chunkMapChunkX < 0 || chunkMapChunkX >= chunkGridWidth || chunkMapChunkY < 0 || chunkMapChunkY >= chunkGridWidth) {
         return null;
      } else {
         return this.readBufferA
            ? this.chunksSwapA[chunkGridWidth * chunkMapChunkY + chunkMapChunkX]
            : this.chunksSwapB[chunkGridWidth * chunkMapChunkY + chunkMapChunkX];
      }
   }

   public IsoChunk[] getChunks() {
      return this.readBufferA ? this.chunksSwapA : this.chunksSwapB;
   }

   private void setChunk(int x, int y, IsoChunk c) {
      if (!this.readBufferA) {
         this.chunksSwapA[chunkGridWidth * y + x] = c;
      } else {
         this.chunksSwapB[chunkGridWidth * y + x] = c;
      }
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   public boolean setChunkDirect(IsoChunk c, boolean bRequireLock) {
      long start = System.nanoTime();
      if (bRequireLock) {
         bSettingChunk.lock();
      }

      long start2 = System.nanoTime();
      int x = c.wx - this.worldX;
      int y = c.wy - this.worldY;
      x += chunkGridWidth / 2;
      y += chunkGridWidth / 2;
      if (c.jobType == IsoChunk.JobType.Convert) {
         x = 0;
         y = 0;
      }

      if (!c.refs.isEmpty() && x >= 0 && y >= 0 && x < chunkGridWidth && y < chunkGridWidth) {
         try {
            if (this.readBufferA) {
               this.chunksSwapA[chunkGridWidth * y + x] = c;
            } else {
               this.chunksSwapB[chunkGridWidth * y + x] = c;
            }
            pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed

            c.loaded = true;
            if (c.jobType == IsoChunk.JobType.None) {
               c.setCache();
               c.updateBuildings();
            }

            double duration1 = (System.nanoTime() - start2) / 1000000.0;
            double duration2 = (System.nanoTime() - start) / 1000000.0;
            if (LightingThread.debugLockTime && duration2 > 10.0) {
               DebugLog.log("setChunkDirect time " + duration1 + "/" + duration2 + " ms");
            }
         } finally {
            if (bRequireLock) {
               bSettingChunk.unlock();
            }
         }

         return true;
      } else {
         if (c.refs.contains(this)) {
            c.refs.remove(this);
            if (c.refs.isEmpty()) {
               SharedChunks.remove((c.wx << 16) + c.wy);
            }
         }

         if (bRequireLock) {
            bSettingChunk.unlock();
         }

         return false;
      }
   }

   public void drawDebugChunkMap() {
      int x = 64;

      for (int n = 0; n < chunkGridWidth; n++) {
         int y = 0;

         for (int m = 0; m < chunkGridWidth; m++) {
            y += 64;
            IsoChunk ch = this.getChunk(n, m);
            if (ch != null) {
               IsoGridSquare gr = ch.getGridSquare(0, 0, 0);
               if (gr == null) {
                  TextManager.instance.DrawString(x, y, "wx:" + ch.wx + " wy:" + ch.wy);
               }
            }
         }

         x += 128;
      }
   }

   private void LoadLeft() {
      pzopt.ChunkShiftTimer.start(); // pzopt: Louisville 120 item 3 dev timer
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.Left();
      pzopt.ChunkShiftTimer.mark(0); // pzopt
      WorldSimulation.instance.scrollGroundLeft(this.playerId);
      pzopt.ChunkShiftTimer.mark(1); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;

      for (int y = -(chunkGridWidth / 2); y <= chunkGridWidth / 2; y++) {
         this.LoadChunkForLater(this.worldX - chunkGridWidth / 2, this.worldY + y, 0, y + chunkGridWidth / 2);
      }

      pzopt.ChunkShiftTimer.mark(2); // pzopt
      this.SwapChunkBuffers();
      pzopt.ChunkShiftTimer.mark(3); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.UpdateCellCache();
      pzopt.ChunkShiftTimer.mark(4); // pzopt
      LightingThread.instance.scrollLeft(this.playerId);
      pzopt.ChunkShiftTimer.mark(5); // pzopt
      pzopt.ChunkShiftTimer.done("Left"); // pzopt
   }

   public void SwapChunkBuffers() {
      for (int n = 0; n < chunkGridWidth * chunkGridWidth; n++) {
         if (this.readBufferA) {
            this.chunksSwapA[n] = null;
         } else {
            this.chunksSwapB[n] = null;
         }
      }

      this.xMinTiles = this.xMaxTiles = -1;
      this.yMinTiles = this.yMaxTiles = -1;
      this.readBufferA = !this.readBufferA;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   private void setChunk(int n, IsoChunk c) {
      if (!this.readBufferA) {
         this.chunksSwapA[n] = c;
      } else {
         this.chunksSwapB[n] = c;
      }
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   private IsoChunk getChunk(int n) {
      return this.readBufferA ? this.chunksSwapA[n] : this.chunksSwapB[n];
   }

   private void LoadRight() {
      pzopt.ChunkShiftTimer.start(); // pzopt: Louisville 120 item 3 dev timer
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.Right();
      pzopt.ChunkShiftTimer.mark(0); // pzopt
      WorldSimulation.instance.scrollGroundRight(this.playerId);
      pzopt.ChunkShiftTimer.mark(1); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;

      for (int y = -(chunkGridWidth / 2); y <= chunkGridWidth / 2; y++) {
         this.LoadChunkForLater(this.worldX + chunkGridWidth / 2, this.worldY + y, chunkGridWidth - 1, y + chunkGridWidth / 2);
      }

      pzopt.ChunkShiftTimer.mark(2); // pzopt
      this.SwapChunkBuffers();
      pzopt.ChunkShiftTimer.mark(3); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.UpdateCellCache();
      pzopt.ChunkShiftTimer.mark(4); // pzopt
      LightingThread.instance.scrollRight(this.playerId);
      pzopt.ChunkShiftTimer.mark(5); // pzopt
      pzopt.ChunkShiftTimer.done("Right"); // pzopt
   }

   private void LoadUp() {
      pzopt.ChunkShiftTimer.start(); // pzopt: Louisville 120 item 3 dev timer
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.Up();
      pzopt.ChunkShiftTimer.mark(0); // pzopt
      WorldSimulation.instance.scrollGroundUp(this.playerId);
      pzopt.ChunkShiftTimer.mark(1); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;

      for (int x = -(chunkGridWidth / 2); x <= chunkGridWidth / 2; x++) {
         this.LoadChunkForLater(this.worldX + x, this.worldY - chunkGridWidth / 2, x + chunkGridWidth / 2, 0);
      }

      pzopt.ChunkShiftTimer.mark(2); // pzopt
      this.SwapChunkBuffers();
      pzopt.ChunkShiftTimer.mark(3); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.UpdateCellCache();
      pzopt.ChunkShiftTimer.mark(4); // pzopt
      LightingThread.instance.scrollUp(this.playerId);
      pzopt.ChunkShiftTimer.mark(5); // pzopt
      pzopt.ChunkShiftTimer.done("Up"); // pzopt
   }

   private void LoadDown() {
      pzopt.ChunkShiftTimer.start(); // pzopt: Louisville 120 item 3 dev timer
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.Down();
      pzopt.ChunkShiftTimer.mark(0); // pzopt
      WorldSimulation.instance.scrollGroundDown(this.playerId);
      pzopt.ChunkShiftTimer.mark(1); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;

      for (int x = -(chunkGridWidth / 2); x <= chunkGridWidth / 2; x++) {
         this.LoadChunkForLater(this.worldX + x, this.worldY + chunkGridWidth / 2, x + chunkGridWidth / 2, chunkGridWidth - 1);
      }

      pzopt.ChunkShiftTimer.mark(2); // pzopt
      this.SwapChunkBuffers();
      pzopt.ChunkShiftTimer.mark(3); // pzopt
      this.xMinTiles = -1;
      this.yMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMaxTiles = -1;
      this.UpdateCellCache();
      pzopt.ChunkShiftTimer.mark(4); // pzopt
      LightingThread.instance.scrollDown(this.playerId);
      pzopt.ChunkShiftTimer.mark(5); // pzopt
      pzopt.ChunkShiftTimer.done("Down"); // pzopt
   }

   private void UpdateCellCache() {
   }

   private void Up() {
      for (int x = 0; x < chunkGridWidth; x++) {
         for (int y = chunkGridWidth - 1; y > 0; y--) {
            IsoChunk ch = this.getChunk(x, y);
            if (ch == null && y == chunkGridWidth - 1) {
               int wx = this.worldX - chunkGridWidth / 2 + x;
               int wy = this.worldY - chunkGridWidth / 2 + y;
               ch = SharedChunks.get((wx << 16) + wy);
               if (ch != null) {
                  if (ch.refs.contains(this)) {
                     ch.refs.remove(this);
                     if (ch.refs.isEmpty()) {
                        SharedChunks.remove((ch.wx << 16) + ch.wy);
                     }
                  }

                  ch = null;
               }
            }

            if (ch != null && y == chunkGridWidth - 1) {
               ch.refs.remove(this);
               if (ch.refs.isEmpty()) {
                  SharedChunks.remove((ch.wx << 16) + ch.wy);
                  ch.removeFromWorld();
                  ChunkSaveWorker.instance.Add(ch);
               }
            }

            this.setChunk(x, y, this.getChunk(x, y - 1));
         }

         this.setChunk(x, 0, null);
      }

      this.worldY--;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   private void Down() {
      for (int x = 0; x < chunkGridWidth; x++) {
         for (int y = 0; y < chunkGridWidth - 1; y++) {
            IsoChunk ch = this.getChunk(x, y);
            if (ch == null && y == 0) {
               int wx = this.worldX - chunkGridWidth / 2 + x;
               int wy = this.worldY - chunkGridWidth / 2 + y;
               ch = SharedChunks.get((wx << 16) + wy);
               if (ch != null) {
                  if (ch.refs.contains(this)) {
                     ch.refs.remove(this);
                     if (ch.refs.isEmpty()) {
                        SharedChunks.remove((ch.wx << 16) + ch.wy);
                     }
                  }

                  ch = null;
               }
            }

            if (ch != null && y == 0) {
               ch.refs.remove(this);
               if (ch.refs.isEmpty()) {
                  SharedChunks.remove((ch.wx << 16) + ch.wy);
                  ch.removeFromWorld();
                  ChunkSaveWorker.instance.Add(ch);
               }
            }

            this.setChunk(x, y, this.getChunk(x, y + 1));
         }

         this.setChunk(x, chunkGridWidth - 1, null);
      }

      this.worldY++;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   private void Left() {
      for (int y = 0; y < chunkGridWidth; y++) {
         for (int x = chunkGridWidth - 1; x > 0; x--) {
            IsoChunk ch = this.getChunk(x, y);
            if (ch == null && x == chunkGridWidth - 1) {
               int wx = this.worldX - chunkGridWidth / 2 + x;
               int wy = this.worldY - chunkGridWidth / 2 + y;
               ch = SharedChunks.get((wx << 16) + wy);
               if (ch != null) {
                  if (ch.refs.contains(this)) {
                     ch.refs.remove(this);
                     if (ch.refs.isEmpty()) {
                        SharedChunks.remove((ch.wx << 16) + ch.wy);
                     }
                  }

                  ch = null;
               }
            }

            if (ch != null && x == chunkGridWidth - 1) {
               ch.refs.remove(this);
               if (ch.refs.isEmpty()) {
                  SharedChunks.remove((ch.wx << 16) + ch.wy);
                  ch.removeFromWorld();
                  ChunkSaveWorker.instance.Add(ch);
               }
            }

            this.setChunk(x, y, this.getChunk(x - 1, y));
         }

         this.setChunk(0, y, null);
      }

      this.worldX--;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   private void Right() {
      for (int y = 0; y < chunkGridWidth; y++) {
         for (int x = 0; x < chunkGridWidth - 1; x++) {
            IsoChunk ch = this.getChunk(x, y);
            if (ch == null && x == 0) {
               int wx = this.worldX - chunkGridWidth / 2 + x;
               int wy = this.worldY - chunkGridWidth / 2 + y;
               ch = SharedChunks.get((wx << 16) + wy);
               if (ch != null) {
                  if (ch.refs.contains(this)) {
                     ch.refs.remove(this);
                     if (ch.refs.isEmpty()) {
                        SharedChunks.remove((ch.wx << 16) + ch.wy);
                     }
                  }

                  ch = null;
               }
            }

            if (ch != null && x == 0) {
               ch.refs.remove(this);
               if (ch.refs.isEmpty()) {
                  SharedChunks.remove((ch.wx << 16) + ch.wy);
                  ch.removeFromWorld();
                  ChunkSaveWorker.instance.Add(ch);
               }
            }

            this.setChunk(x, y, this.getChunk(x + 1, y));
         }

         this.setChunk(chunkGridWidth - 1, y, null);
      }

      this.worldX++;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
   }

   public int getWorldXMin() {
      return this.worldX - chunkGridWidth / 2;
   }

   public int getWorldYMin() {
      return this.worldY - chunkGridWidth / 2;
   }

   public void ProcessChunkPos(IsoGameCharacter chr) {
      long pzoptT = pzopt.GtAb.begin(); // pzopt: Louisville 120 item 3 section timer (the grid shift and its chunk unloads)
      try { // pzopt
         this.pzoptProcessChunkPosBody(chr); // pzopt
      } finally { // pzopt
         pzopt.GtAb.end(pzopt.GtAb.S_CHUNK_POS, pzoptT); // pzopt
      } // pzopt
   }

   /** pzopt: the stock body of ProcessChunkPos (the section timer wraps it). */
   private void pzoptProcessChunkPosBody(IsoGameCharacter chr) {
      float x1 = chr.getX();
      float y1 = chr.getY();
      int z = PZMath.fastfloor(chr.getZ());
      if (IsoPlayer.getInstance() != null && IsoPlayer.getInstance().getVehicle() != null) {
         IsoPlayer p = IsoPlayer.getInstance();
         BaseVehicle v = p.getVehicle();
         float s = v.getCurrentSpeedKmHour() / 5.0F;
         if (!p.isDriving()) {
            s = Math.min(s * 2.0F, 20.0F);
         }

         x1 += Math.round(p.getForwardDirectionX() * s);
         y1 += Math.round(p.getForwardDirectionY() * s);
      }

      // pzopt: chunkGridFollowView, a grid wider than stock follows the ground point the camera looks at on an upper
      // floor (3 tiles north and west per level, capped so the player stays as deep inside as in a stock grid). Single
      // player only: a multiplayer server loads a player-centred area (ServerMap, LoadedAreas)
      if (pzopt.Config.CHUNK_GRID_FOLLOW_VIEW && pzopt.ChunkGrid.stock > 0 && pzopt.Overrides.enabled() && !GameClient.client && !GameServer.server) { // pzopt
         int pzoptShift = pzopt.ChunkGrid.heightShiftTiles(chr.getZ(), chunkGridWidth, pzopt.ChunkGrid.stock); // pzopt
         x1 -= pzoptShift; // pzopt
         y1 -= pzoptShift; // pzopt
      } // pzopt

      int x = PZMath.fastfloor(x1 / 8.0F);
      int y = PZMath.fastfloor(y1 / 8.0F);
      if (x != this.worldX || y != this.worldY) {
         long start = System.nanoTime();
         bSettingChunk.lock();
         long start2 = System.nanoTime();
         boolean changed = false;

         try {
            if (Math.abs(x - this.worldX) < chunkGridWidth && Math.abs(y - this.worldY) < chunkGridWidth) {
               if (x != this.worldX) {
                  if (x < this.worldX) {
                     this.LoadLeft();
                  } else {
                     this.LoadRight();
                  }

                  changed = true;
               } else if (y != this.worldY) {
                  if (y < this.worldY) {
                     this.LoadUp();
                  } else {
                     this.LoadDown();
                  }

                  changed = true;
               }
            } else {
               if (LightingJNI.init) {
                  LightingJNI.teleport(this.playerId, x - chunkGridWidth / 2, y - chunkGridWidth / 2);
               }

               this.Unload();
               IsoPlayer player = IsoPlayer.players[this.playerId];
               player.removeFromSquare();
               player.square = null;
               this.worldX = x;
               this.worldY = y;
               pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
               if (!GameServer.server) {
                  WorldSimulation.instance.activateChunkMap(this.playerId);
               }

               int minwx = this.worldX - chunkGridWidth / 2;
               int minwy = this.worldY - chunkGridWidth / 2;
               int maxwx = this.worldX + chunkGridWidth / 2;
               int maxwy = this.worldY + chunkGridWidth / 2;

               for (int xx = minwx; xx <= maxwx; xx++) {
                  for (int yy = minwy; yy <= maxwy; yy++) {
                     this.LoadChunkForLater(xx, yy, xx - minwx, yy - minwy);
                  }
               }

               this.SwapChunkBuffers();
               this.UpdateCellCache();
               IsoCell cell = IsoWorld.instance.getCell();
               if (!cell.getObjectList().contains(player) && !cell.getAddList().contains(player)) {
                  cell.addMovingObject(player);
               }

               changed = true;
            }
         } finally {
            bSettingChunk.unlock();
            if (changed) {
               this.calculateZExtentsForChunkMap();
            }
         }

         double duration1 = (System.nanoTime() - start2) / 1000000.0;
         double duration2 = (System.nanoTime() - start) / 1000000.0;
         if (LightingThread.debugLockTime && duration2 > 10.0) {
            DebugLog.log("ProcessChunkPos time " + duration1 + "/" + duration2 + " ms");
         }
      }
   }

   public void calculateZExtentsForChunkMap() {
      int max = 0;
      int min = 0;

      int n = pzopt.Config.CHUNK_MAP_FAST ? chunkGridWidth : this.chunksSwapA.length; // pzopt: stock loops length x length (the array already holds width x width chunks; getChunk is null past the width)
      for (int xx = 0; xx < n; xx++) { // pzopt
         for (int yy = 0; yy < n; yy++) { // pzopt
            IsoChunk c = this.getChunk(xx, yy);
            if (c != null) {
               max = Math.max(c.maxLevel, max);
               min = Math.min(min, c.minLevel);
            }
         }
      }

      this.maxHeight = max;
      this.minHeight = min;
   }

   public IsoRoom getRoom(int iD) {
      return null;
   }

   public int getWidthInTiles() {
      return chunkWidthInTiles;
   }

   public int getWorldXMinTiles() {
      if (this.xMinTiles != -1) {
         return this.xMinTiles;
      }

      this.xMinTiles = this.getWorldXMin() * 8;
      return this.xMinTiles;
   }

   public int getWorldYMinTiles() {
      if (this.yMinTiles != -1) {
         return this.yMinTiles;
      }

      this.yMinTiles = this.getWorldYMin() * 8;
      return this.yMinTiles;
   }

   public int getWorldXMaxTiles() {
      if (this.xMaxTiles != -1) {
         return this.xMaxTiles;
      }

      this.xMaxTiles = this.getWorldXMin() * 8 + this.getWidthInTiles();
      return this.xMaxTiles;
   }

   public int getWorldYMaxTiles() {
      if (this.yMaxTiles != -1) {
         return this.yMaxTiles;
      }

      this.yMaxTiles = this.getWorldYMin() * 8 + this.getWidthInTiles();
      return this.yMaxTiles;
   }

   public void Save() {
      if (!GameServer.server) {
         for (int x = 0; x < chunkGridWidth; x++) {
            for (int y = 0; y < chunkGridWidth; y++) {
               IsoChunk c = this.getChunk(x, y);
               if (c != null) {
                  try {
                     c.Save(true);
                  } catch (IOException e) {
                     ExceptionLogger.logException(e);
                  }
               }
            }
         }
      }
   }

   public void renderBloodForChunks(int zza) {
      if (DebugOptions.instance.terrain.renderTiles.bloodDecals.getValue()) {
         if (!(zza > IsoCamera.getCameraCharacterZ())) {
            int optionBloodDecals = Core.getInstance().getOptionBloodDecals();
            if (optionBloodDecals != 0) {
               float worldAge = (float)GameTime.getInstance().getWorldAgeHours();
               int playerIndex = IsoCamera.frameState.playerIndex;

               for (int n = 0; n < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length; n++) {
                  splatByType.get(n).clear();
               }

               for (int x = 0; x < chunkGridWidth; x++) {
                  for (int y = 0; y < chunkGridWidth; y++) {
                     IsoChunk ch = this.getChunk(x, y);
                     if (ch != null) {
                        for (int n = 0; n < ch.floorBloodSplatsFade.size(); n++) {
                           IsoFloorBloodSplat b = ch.floorBloodSplatsFade.get(n);
                           if ((b.index < 1 || b.index > 10 || IsoChunk.renderByIndex[optionBloodDecals - 1][b.index - 1] != 0)
                              && PZMath.fastfloor(b.z) == zza
                              && b.type >= 0
                              && b.type < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length) {
                              b.chunk = ch;
                              splatByType.get(b.type).add(b);
                           }
                        }

                        if (!ch.floorBloodSplats.isEmpty()) {
                           for (int n = 0; n < ch.floorBloodSplats.size(); n++) {
                              IsoFloorBloodSplat b = (IsoFloorBloodSplat)ch.floorBloodSplats.get(n);
                              if ((b.index < 1 || b.index > 10 || IsoChunk.renderByIndex[optionBloodDecals - 1][b.index - 1] != 0)
                                 && PZMath.fastfloor(b.z) == zza
                                 && b.type >= 0
                                 && b.type < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length) {
                                 b.chunk = ch;
                                 splatByType.get(b.type).add(b);
                              }
                           }
                        }
                     }
                  }
               }

               for (int n = 0; n < splatByType.size(); n++) {
                  ArrayList<IsoFloorBloodSplat> splats = splatByType.get(n);
                  if (!splats.isEmpty()) {
                     String type = IsoFloorBloodSplat.FLOOR_BLOOD_TYPES[n];
                     IsoSprite use;
                     if (!IsoFloorBloodSplat.spriteMap.containsKey(type)) {
                        IsoSprite sp = IsoSprite.CreateSprite(IsoSpriteManager.instance);
                        sp.LoadFramesPageSimple(type, type, type, type);
                        IsoFloorBloodSplat.spriteMap.put(type, sp);
                        use = sp;
                     } else {
                        use = (IsoSprite)IsoFloorBloodSplat.spriteMap.get(type);
                     }

                     for (int i = 0; i < splats.size(); i++) {
                        IsoFloorBloodSplat b = splats.get(i);
                        inf.r = 1.0F;
                        inf.g = 1.0F;
                        inf.b = 1.0F;
                        inf.a = 0.27F;
                        float aa = (b.x + b.y / b.x) * (b.type + 1);
                        float bb = aa * b.x / b.y * (b.type + 1) / (aa + b.y);
                        float cc = bb * aa * bb * b.x / (b.y + 2.0F);
                        aa *= 42367.543F;
                        bb *= 6367.123F;
                        cc *= 23367.133F;
                        aa %= 1000.0F;
                        bb %= 1000.0F;
                        cc %= 1000.0F;
                        aa /= 1000.0F;
                        bb /= 1000.0F;
                        cc /= 1000.0F;
                        if (aa > 0.25F) {
                           aa = 0.25F;
                        }

                        inf.r -= aa * 2.0F;
                        inf.g -= aa * 2.0F;
                        inf.b -= aa * 2.0F;
                        inf.r += bb / 3.0F;
                        inf.g -= cc / 3.0F;
                        inf.b -= cc / 3.0F;
                        float deltaAge = worldAge - b.worldAge;
                        if (deltaAge >= 0.0F && deltaAge < 72.0F) {
                           float f = 1.0F - deltaAge / 72.0F;
                           inf.r *= 0.2F + f * 0.8F;
                           inf.g *= 0.2F + f * 0.8F;
                           inf.b *= 0.2F + f * 0.8F;
                           inf.a *= 0.25F + f * 0.75F;
                        } else {
                           inf.r *= 0.2F;
                           inf.g *= 0.2F;
                           inf.b *= 0.2F;
                           inf.a *= 0.25F;
                        }

                        if (b.fade > 0) {
                           inf.a = inf.a * (b.fade / (PerformanceSettings.getLockFPS() * 5.0F));
                           if (--b.fade == 0) {
                              b.chunk.floorBloodSplatsFade.remove(b);
                           }
                        }

                        IsoGridSquare square = b.chunk.getGridSquare(PZMath.fastfloor(b.x), PZMath.fastfloor(b.y), PZMath.fastfloor(b.z));
                        if (square != null) {
                           int l0 = square.getVertLight(0, playerIndex);
                           int l1 = square.getVertLight(1, playerIndex);
                           int l2 = square.getVertLight(2, playerIndex);
                           int l3 = square.getVertLight(3, playerIndex);
                           float r0 = Color.getRedChannelFromABGR(l0);
                           float g0 = Color.getGreenChannelFromABGR(l0);
                           float b0 = Color.getBlueChannelFromABGR(l0);
                           float r1 = Color.getRedChannelFromABGR(l1);
                           float g1 = Color.getGreenChannelFromABGR(l1);
                           float b1 = Color.getBlueChannelFromABGR(l1);
                           float r2 = Color.getRedChannelFromABGR(l2);
                           float g2 = Color.getGreenChannelFromABGR(l2);
                           float b2 = Color.getBlueChannelFromABGR(l2);
                           float r3 = Color.getRedChannelFromABGR(l3);
                           float g3 = Color.getGreenChannelFromABGR(l3);
                           float b3 = Color.getBlueChannelFromABGR(l3);
                           inf.r *= (r0 + r1 + r2 + r3) / 4.0F;
                           inf.g *= (g0 + g1 + g2 + g3) / 4.0F;
                           inf.b *= (b0 + b1 + b2 + b3) / 4.0F;
                        }

                        use.renderBloodSplat(b.chunk.wx * 8 + b.x, b.chunk.wy * 8 + b.y, b.z, inf);
                     }
                  }
               }
            }
         }
      }
   }

   public void copy(IsoChunkMap from) {
      IsoChunkMap to = this;
      to.worldX = from.worldX;
      to.worldY = from.worldY;
      pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
      to.xMinTiles = -1;
      to.yMinTiles = -1;
      to.xMaxTiles = -1;
      to.yMaxTiles = -1;

      for (int n = 0; n < chunkGridWidth * chunkGridWidth; n++) {
         to.readBufferA = from.readBufferA;
         if (to.readBufferA) {
            if (from.chunksSwapA[n] != null) {
               from.chunksSwapA[n].refs.add(to);
               to.chunksSwapA[n] = from.chunksSwapA[n];
               pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
            }
         } else if (from.chunksSwapB[n] != null) {
            from.chunksSwapB[n].refs.add(to);
            to.chunksSwapB[n] = from.chunksSwapB[n];
            pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
         }
      }
   }

   private void releaseChunkBeingLoaded(int wx, int wy) {
      int chunkIndex = (wx << 16) + wy;
      IsoChunk ch = SharedChunks.get(chunkIndex);
      if (ch != null && ch.refs.remove(this)) {
         if (ch.refs.isEmpty()) {
            SharedChunks.remove(chunkIndex);
         }
      }
   }

   public void Unload() {
      for (int y = 0; y < chunkGridWidth; y++) {
         for (int x = 0; x < chunkGridWidth; x++) {
            IsoChunk ch = this.getChunk(x, y);
            if (ch == null) {
               this.releaseChunkBeingLoaded(this.worldX - chunkGridWidth / 2 + x, this.worldY - chunkGridWidth / 2 + y);
            } else {
               if (ch.refs.contains(this)) {
                  ch.refs.remove(this);
                  if (ch.refs.isEmpty()) {
                     SharedChunks.remove((ch.wx << 16) + ch.wy);
                     ch.removeFromWorld();
                     ChunkSaveWorker.instance.Add(ch);
                  }
               }

               this.chunksSwapA[y * chunkGridWidth + x] = null;
               this.chunksSwapB[y * chunkGridWidth + x] = null;
               pzopt.VehicleNear.changed(); // pzopt: nearVehicleGrid, the chunk map changed
            }
         }
      }

      WorldSimulation.instance.deactivateChunkMap(this.playerId);
      this.xMinTiles = -1;
      this.xMaxTiles = -1;
      this.yMinTiles = -1;
      this.yMaxTiles = -1;
      if (IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
         IsoWorld.instance.currentCell.clearCacheGridSquare(this.playerId);
      }
   }

   public static boolean isGridSquareOutOfRangeZ(int tileZ) {
      return tileZ < -32 || tileZ > 31;
   }

   static {
      for (int i = 0; i < IsoFloorBloodSplat.FLOOR_BLOOD_TYPES.length; i++) {
         splatByType.add(new ArrayList<>());
      }

      ppp_update = new PerformanceProfileProbe("IsoChunkMap.update");
   }
}
