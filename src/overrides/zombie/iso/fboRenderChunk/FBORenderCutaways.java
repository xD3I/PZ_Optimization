package zombie.iso.fboRenderChunk;

import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.properties.IsoPropertyType;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.input.Mouse;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoDoor;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteGrid;
import zombie.popman.ObjectPool;
import zombie.util.list.PZArrayUtil;

public final class FBORenderCutaways {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.fboRenderChunk.FBORenderCutaways");
   }

   // pzopt: mirrors (Config.MIRRORS_CUTAWAY_HOLD_MS). At a room corner the visitor's cut of a wall flips every 0.1-0.7 s
   // while the player walks (its points of interest are the squares the player can see, which change with every turn);
   // on a wall carrying a mirror the reflection made every flip a visible pop. Such a square (both its walls: at a corner
   // the one beside the mirror flapped as well) gets stock's own 750 ms cutaway lock back, which the fboRenderChunk path
   // never reads: its cut changes at most once per hold (the first cut at once), and the wall returns only after visits
   // have not wanted it for twice the hold. The visit runs only when something changed, so pzoptHoldTick applies a change that
   // became due without one (a forced visit gave another verdict than the last one: the wall came back for 0.1 s).
   private static final class PzoptMirrorCut {
      final boolean[] cut = new boolean[2], wanted = new boolean[2];
      final long[] changedAt = new long[2], wantedAt = new long[2]; // (wantedAt: when wanted last changed)
   }

   private final java.util.IdentityHashMap<IsoGridSquare, PzoptMirrorCut> pzoptMirrorCut = new java.util.IdentityHashMap<>();
   private long pzoptHoldNext;
   public static long pzoptMirrorCutsHeld, pzoptMirrorCutsDelayed, pzoptMirrorCutsReleased;

   /** The cut a mirror wall's bit should show now (k.cut updated, the change time stamped); false = no change. */
   private static boolean pzoptDecide(PzoptMirrorCut k, int b, long now, int hold) {
      boolean want = k.wanted[b];
      if (want == k.cut[b]) {
         return false;
      }
      boolean lockOver = now - k.changedAt[b] >= hold;
      if (want ? lockOver : lockOver && now - k.wantedAt[b] >= 2L * hold) { // (the wall returns after twice the hold unwanted: a corner wanted the cut 0.4 s, not 0.75 s, then again)
         k.cut[b] = want;
         k.changedAt[b] = now;
         return true;
      }
      return false;
   }

   private void pzoptHoldMirrorWalls(int playerIndex, FBORenderCutaways.PerPlayerData pd, long now) {
      int hold = pzopt.Overrides.enabled() ? pzopt.Config.MIRRORS_CUTAWAY_HOLD_MS : 0;
      if (playerIndex != 0) {
         return; // (split screen: the first player's view only)
      }
      if (hold <= 0) {
         this.pzoptHoldNext = 0L;
         this.pzoptMirrorCut.clear();
         return;
      }
      for (int b = 0; b < 2; b++) {
         for (IsoGridSquare square : b == 0 ? pd.cutawayVisitorResultsNorth : pd.cutawayVisitorResultsWest) {
            if (!this.pzoptMirrorCut.containsKey(square) && pzopt.Mirrors.mirrorWallBits(square) != 0) {
               this.pzoptMirrorCut.put(square, new PzoptMirrorCut());
            }
         }
      }
      long next = 0L;
      java.util.Iterator<java.util.Map.Entry<IsoGridSquare, PzoptMirrorCut>> it = this.pzoptMirrorCut.entrySet().iterator();
      while (it.hasNext()) {
         java.util.Map.Entry<IsoGridSquare, PzoptMirrorCut> e = it.next();
         IsoGridSquare square = e.getKey();
         PzoptMirrorCut k = e.getValue();
         boolean onLevel = square.z == PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
         for (int b = 0; b < 2; b++) {
            java.util.HashSet<IsoGridSquare> set = b == 0 ? pd.cutawayVisitorResultsNorth : pd.cutawayVisitorResultsWest;
            boolean want = set.contains(square) && onLevel;
            if (want != k.wanted[b]) {
               k.wanted[b] = want;
               k.wantedAt[b] = now;
            }
            pzoptDecide(k, b, now, hold);
            if (k.cut[b] && onLevel) {
               if (!want) {
                  pzoptMirrorCutsHeld++;
               }
               set.add(square); // cut: the visitor's sets are this frame's cut, and the next visit's squares to clear
            } else {
               if (want) {
                  pzoptMirrorCutsDelayed++;
               }
               set.remove(square);
               k.cut[b] = false;
            }
            if (k.cut[b] != k.wanted[b]) {
               long due = Math.max(k.changedAt[b], k.cut[b] ? k.wantedAt[b] + hold : 0L) + hold;
               next = next == 0L ? due : Math.min(next, due);
            }
         }
         if (!k.cut[0] && !k.cut[1] && !k.wanted[0] && !k.wanted[1] && now - Math.max(k.changedAt[0], k.changedAt[1]) >= hold) {
            it.remove();
         }
      }
      this.pzoptHoldNext = next;
   }

   /** pzopt: every frame, the first player: a mirror wall's cut change that became due without a visit is applied here. */
   public void pzoptHoldTick(long now) {
      if (this.pzoptHoldNext == 0L || now < this.pzoptHoldNext) {
         return;
      }
      FBORenderCutaways.PerPlayerData pd = this.perPlayerData[0];
      int hold = pzopt.Config.MIRRORS_CUTAWAY_HOLD_MS;
      long next = 0L;
      for (java.util.Map.Entry<IsoGridSquare, PzoptMirrorCut> e : this.pzoptMirrorCut.entrySet()) {
         IsoGridSquare square = e.getKey();
         PzoptMirrorCut k = e.getValue();
         for (int b = 0; b < 2; b++) {
            if (pzoptDecide(k, b, now, hold)) {
               int bit = b + 1;
               java.util.HashSet<IsoGridSquare> set = b == 0 ? pd.cutawayVisitorResultsNorth : pd.cutawayVisitorResultsWest;
               int flags = square.getPlayerCutawayFlag(0, 0L);
               int nf = k.cut[b] ? flags | bit : flags & ~bit;
               if (k.cut[b]) {
                  set.add(square);
               } else {
                  set.remove(square);
                  pzoptMirrorCutsReleased++;
               }
               if (nf != flags) {
                  square.setPlayerCutawayFlag(0, nf, now);
                  IsoChunk chunk = square.getChunk();
                  if (chunk != null) {
                     chunk.getRenderLevels(0).invalidateLevel(square.z, 2048L);
                  }
               }
            }
            if (k.cut[b] != k.wanted[b]) {
               long due = Math.max(k.changedAt[b], k.cut[b] ? k.wantedAt[b] + hold : 0L) + hold;
               next = next == 0L ? due : Math.min(next, due);
            }
         }
      }
      this.pzoptHoldNext = next;
   }

   // pzopt: cutaway change detection (Config.CUTAWAY_INVALIDATE_CHANGED): target flags of last frame's result
   // squares before they are cleared, and the chunks whose square flags really changed this visit
   private final java.util.IdentityHashMap<IsoGridSquare, Integer> pzoptOldFlags = new java.util.IdentityHashMap<>();
   private final ArrayList<IsoGridSquare> pzoptTouched = new ArrayList<>();
   private static final IsoDirections[] DIRECTIONS = IsoDirections.values();
   private static FBORenderCutaways instance;
   public static final byte CLDSF_NONE = 0;
   public static final byte CLDSF_SHOULD_RENDER = 1;
   public IsoCell cell;
   private final FBORenderCutaways.PerPlayerData[] perPlayerData = new FBORenderCutaways.PerPlayerData[4];
   private final HashSet<IsoChunk> invalidatedChunks = new HashSet<>();
   private final ArrayList<FBORenderCutaways.PointOfInterest> pointOfInterest = new ArrayList<>();
   private final ObjectPool<FBORenderCutaways.PointOfInterest> pointOfInterestStore = new ObjectPool(
      FBORenderCutaways.PointOfInterest::new, "FBORenderCutaways.pointOfInterestStore"
   );
   private final Rectangle buildingRectTemp = new Rectangle();
   public static final ObjectPool<FBORenderCutaways.CutawayWall> s_cutawayWallPool = new ObjectPool(
      FBORenderCutaways.CutawayWall::new, "FBORenderCutaways.s_cutawayWallPool", 8192
   );
   public static final ObjectPool<FBORenderCutaways.SlopedSurface> s_slopedSurfacePool = new ObjectPool(
      FBORenderCutaways.SlopedSurface::new, "FBORenderCutaways.s_slopedSurfacePool", 1024
   );

   public static FBORenderCutaways getInstance() {
      if (instance == null) {
         instance = new FBORenderCutaways();
      }

      return instance;
   }

   private FBORenderCutaways() {
      for (int i = 0; i < this.perPlayerData.length; i++) {
         this.perPlayerData[i] = new FBORenderCutaways.PerPlayerData();
      }
   }

   public boolean checkPlayerRoom(int playerIndex) {
      boolean bForceCutawayUpdate = false;
      IsoGridSquare playerSquare = IsoCamera.frameState.camCharacterSquare;
      if (playerSquare != null) {
         long roomID = playerSquare.getRoomID();
         if (roomID == -1L && getInstance().isRoofRoomSquare(playerSquare)) {
            roomID = playerSquare.associatedBuilding.getRoofRoomID(playerSquare.z);
         }

         FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         if (perPlayerData1.lastPlayerRoomId != -1L && roomID == -1L) {
            bForceCutawayUpdate = true;
            perPlayerData1.lastPlayerRoomId = -1L;
         } else if (roomID != -1L && perPlayerData1.lastPlayerRoomId != roomID) {
            bForceCutawayUpdate = true;
            perPlayerData1.lastPlayerRoomId = roomID;
         }
      }

      return bForceCutawayUpdate;
   }

   public boolean checkExteriorWalls(ArrayList<IsoChunk> onScreenChunks) {
      IsoGridSquare camCharacterSquare = IsoCamera.frameState.camCharacterSquare;
      if (camCharacterSquare == null) {
         return false;
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      int z = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      float zoom = Core.getInstance().getZoom(playerIndex);
      boolean bForceCutawayUpdate = false;

      for (int i = 0; i < onScreenChunks.size(); i++) {
         IsoChunk chunk = onScreenChunks.get(i);
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         if (renderLevels.isOnScreen(z)) {
            FBORenderCutaways.ChunkLevelData chunkLevelData = chunk.getCutawayData().getDataForLevel(z);
            if (chunkLevelData.adjacentChunkLoadedCounter != chunk.adjacentChunkLoadedCounter
                  && pzopt.ZoomRetain.waiting(chunk, playerIndex, renderLevels.getMinLevel(z))) {
               continue; // pzopt: zoomRetain, the level pair waits for the zoom plan; its walls are recreated in the frame it bakes
            }
            if (chunkLevelData.adjacentChunkLoadedCounter != chunk.adjacentChunkLoadedCounter) {
               chunkLevelData.adjacentChunkLoadedCounter = chunk.adjacentChunkLoadedCounter;
               chunkLevelData.orphanStructures.adjacentChunkLoadedCounter = chunk.adjacentChunkLoadedCounter;

               for (int z1 = renderLevels.getMinLevel(z); z1 <= renderLevels.getMaxLevel(z); z1++) {
                  chunk.getCutawayData().recreateLevel(z1);
               }

               bForceCutawayUpdate = true;
            } else {
               for (int z1 = chunk.getMinLevel(); z1 <= chunk.getMaxLevel(); z1++) {
                  if (renderLevels.isOnScreen(z1) && renderLevels.isDirty(z1, 192L, zoom)) {
                     // pzopt: zoomRetain. A level waiting for the zoom plan keeps its object dirt until it bakes; the recreate
                     // runs in the frame the plan allows it (before the chunk loop), not on every frame it waits
                     long pzoptBit = 1L << (renderLevels.getMinLevel(z1) + 32);
                     if ((chunk.pzoptZoomReturned[playerIndex] & pzoptBit) != 0L && (chunk.pzoptZoomAllowed[playerIndex] & pzoptBit) == 0L) {
                        continue;
                     }
                     chunk.getCutawayData().recreateLevel(z1);
                     bForceCutawayUpdate = true;
                  }
               }
            }

            bForceCutawayUpdate |= this.checkOrphanStructures(playerIndex, chunk);
            if (!chunkLevelData.exteriorWalls.isEmpty()) {
               boolean bInvalidate = false;

               for (int j = 0; j < chunkLevelData.exteriorWalls.size(); j++) {
                  FBORenderCutaways.CutawayWall wall = chunkLevelData.exteriorWalls.get(j);
                  if (wall.shouldCutawayFence()) {
                     if (!wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                        wall.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True);
                        wall.setPlayerCutawayFlag(playerIndex, true);
                        bInvalidate = true;
                     }
                  } else if (!wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False)) {
                     wall.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False);
                     wall.setPlayerCutawayFlag(playerIndex, false);
                     bInvalidate = true;
                  }
               }

               if (bInvalidate) {
                  renderLevels.invalidateLevel(z, 2048L);
               }
            }
         }
      }

      return bForceCutawayUpdate;
   }

   public boolean checkSlopedSurfaces(ArrayList<IsoChunk> onScreenChunks) {
      IsoGridSquare camCharacterSquare = IsoCamera.frameState.camCharacterSquare;
      if (camCharacterSquare == null) {
         return false;
      }

      int playerIndex = IsoCamera.frameState.playerIndex;
      int z = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      boolean bForceCutawayUpdate = false;

      for (int i = 0; i < onScreenChunks.size(); i++) {
         IsoChunk chunk = onScreenChunks.get(i);
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         if (renderLevels.isOnScreen(z)) {
            FBORenderCutaways.ChunkLevelData chunkLevelData = chunk.getCutawayData().getDataForLevel(z);
            if (!chunkLevelData.slopedSurfaces.isEmpty()) {
               boolean bInvalidate = false;

               for (int j = 0; j < chunkLevelData.slopedSurfaces.size(); j++) {
                  FBORenderCutaways.SlopedSurface slopedSurface = chunkLevelData.slopedSurfaces.get(j);
                  if (slopedSurface.shouldCutaway()) {
                     if (!slopedSurface.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                        slopedSurface.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True);
                        slopedSurface.setPlayerCutawayFlag(playerIndex, true);
                        bInvalidate = true;
                     }
                  } else if (!slopedSurface.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False)) {
                     slopedSurface.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False);
                     slopedSurface.setPlayerCutawayFlag(playerIndex, false);
                     bInvalidate = true;
                  }
               }

               if (bInvalidate) {
                  renderLevels.invalidateLevel(z, 2048L);
               }
            }
         }
      }

      return false;
   }

   public void squareChanged(IsoGridSquare square) {
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         this.perPlayerData[i].checkSquare = null;
      }
   }

   public boolean checkOccludedRooms(int playerIndex, ArrayList<IsoChunk> onScreenChunks) {
      IsoGridSquare camCharacterSquare = IsoCamera.frameState.camCharacterSquare;
      if (camCharacterSquare == null) {
         return false;
      }

      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      if (perPlayerData1.checkSquare == camCharacterSquare) {
         return false;
      }

      perPlayerData1.checkSquare = camCharacterSquare;
      int z = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      boolean bForceCutawayUpdate = false;

      for (int i = 0; i < onScreenChunks.size(); i++) {
         IsoChunk chunk = onScreenChunks.get(i);
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         if (renderLevels.isOnScreen(z)) {
            FBORenderCutaways.ChunkLevelData chunkLevelData = chunk.getCutawayData().getDataForLevel(z);
            if (!chunkLevelData.allWalls.isEmpty()) {
               boolean bInvalidate = false;

               for (int j = 0; j < chunkLevelData.allWalls.size(); j++) {
                  FBORenderCutaways.CutawayWall wall = chunkLevelData.allWalls.get(j);
                  if (wall.shouldCutawayBuilding(playerIndex)) {
                     if (wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                        int mask = wall.calculateOccludedSquaresMaskForSeenRooms(playerIndex);
                        if (mask != wall.occludedSquaresMaskForSeenRooms[playerIndex]) {
                           wall.occludedSquaresMaskForSeenRooms[playerIndex] = mask;
                           bForceCutawayUpdate = true;
                           bInvalidate = true;
                        }
                     } else {
                        wall.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True);
                        wall.occludedSquaresMaskForSeenRooms[playerIndex] = wall.calculateOccludedSquaresMaskForSeenRooms(playerIndex);
                        bForceCutawayUpdate = true;
                        bInvalidate = true;
                     }
                  } else if (!wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False)) {
                     wall.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False);
                     bForceCutawayUpdate = true;
                     bInvalidate = true;
                  }
               }

               if (bInvalidate) {
                  renderLevels.invalidateLevel(z, 2048L);
               }
            }
         }
      }

      return bForceCutawayUpdate;
   }

   boolean checkOrphanStructures(int playerIndex, IsoChunk chunk) {
      FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
      boolean bForceCutawayUpdate = false;

      for (int z2 = PZMath.max(1, chunk.minLevel); z2 <= chunk.maxLevel; z2++) {
         FBORenderCutaways.ChunkLevelData chunkLevelData = chunk.getCutawayData().getDataForLevel(z2);
         FBORenderCutaways.OrphanStructures orphanStructures = chunkLevelData.orphanStructures;
         if (orphanStructures.hasOrphanStructures) {
            // pzopt: Config.ROOF_HIDE_DEBOUNCE_FRAMES. The hide/show decision for a carport / pergola roof can change
            // every frame while the player stands on its edge (or is pushed across it); stock applies each change at
            // once and re-bakes the level, so the roof flickers. A change is applied only once the new answer has
            // held for that many consecutive frames; the first answer (PlayerInRange.Unset) is applied at once.
            boolean pzoptWantHide = orphanStructures.shouldCutaway();
            int pzoptDebounce = pzopt.Overrides.enabled() ? pzopt.Config.ROOF_HIDE_DEBOUNCE_FRAMES : 0;
            if (pzoptDebounce > 0 && !orphanStructures.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.Unset)) {
               boolean pzoptIsHidden = orphanStructures.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True);
               if (pzoptWantHide == pzoptIsHidden) {
                  orphanStructures.pzoptPendingFrames[playerIndex] = 0;
               } else if (++orphanStructures.pzoptPendingFrames[playerIndex] < pzoptDebounce) {
                  pzoptRoofFlipsHeld++;
                  continue; // not stable yet: keep the current state
               }
            }
            if (pzoptWantHide) {
               if (!orphanStructures.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                  orphanStructures.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True);
                  pzoptDevCutawayLog("orphan HIDE chunk " + chunk.wx + "," + chunk.wy + " level " + z2); // pzopt: dev log
                  bForceCutawayUpdate = true;
                  renderLevels.invalidateLevel(z2, 2048L);
                  if (z2 < chunk.getMaxLevel()) {
                     renderLevels.invalidateLevel(z2 + 1, 2048L);
                  }
               }
            } else if (!orphanStructures.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False)) {
               orphanStructures.setPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False);
               pzoptDevCutawayLog("orphan SHOW chunk " + chunk.wx + "," + chunk.wy + " level " + z2); // pzopt: dev log
               bForceCutawayUpdate = true;
               renderLevels.invalidateLevel(z2, 2048L);
               if (z2 < chunk.getMaxLevel()) {
                  renderLevels.invalidateLevel(z2 + 1, 2048L);
               }
            }
         }
      }

      return bForceCutawayUpdate;
   }

   public void doCutawayVisitSquares(int playerIndex, ArrayList<IsoChunk> onScreenChunks) {
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      perPlayerData1.lastCutawayVisitorResults.clear();
      perPlayerData1.lastCutawayVisitorResults.addAll(perPlayerData1.cutawayVisitorResultsNorth);
      perPlayerData1.lastCutawayVisitorResults.addAll(perPlayerData1.cutawayVisitorResultsWest);
      perPlayerData1.cutawayVisitorResultsNorth.clear();
      perPlayerData1.cutawayVisitorResultsWest.clear();
      perPlayerData1.cutawayVisitorVisitedNorth.clear();
      perPlayerData1.cutawayVisitorVisitedWest.clear();
      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      perPlayerData1.cutawayWalls.clear();

      for (int i = 0; i < onScreenChunks.size(); i++) {
         IsoChunk chunk = onScreenChunks.get(i);
         if (playerZ >= chunk.minLevel && playerZ <= chunk.maxLevel) {
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (renderLevels.isOnScreen(playerZ)) {
               FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayDataForLevel(playerZ);
               perPlayerData1.cutawayWalls.addAll(levelData.allWalls);
            }
         }
      }

      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
      long currentTimeMillis = System.currentTimeMillis();

      boolean pzoptPrefilter = pzopt.Config.CUTAWAY_VISIT_PREFILTER && pzopt.Overrides.enabled();
      for (int i = 0; i < this.pointOfInterest.size(); i++) {
         FBORenderCutaways.PointOfInterest poi = this.pointOfInterest.get(i);
         if (poi.z == playerZ) {
            IsoGridSquare sq = chunkMap.getGridSquare(poi.x, poi.y, poi.z);
            if (sq != null && !perPlayerData1.cutawayVisitorVisitedNorth.contains(sq) && !perPlayerData1.cutawayVisitorVisitedWest.contains(sq)) {
               this.doCutawayVisitSquares(sq, currentTimeMillis, onScreenChunks);
               // pzopt: the first visit marks every wall square at this level visited, so stock's later points of
               // interest never add a square (bVisited blocks them) and can only cost time; with the prefilter the
               // visited sets are no longer complete, so the equivalent rule is applied directly: one visit per frame
               if (pzoptPrefilter) {
                  break;
               }
            }
         }
      }

      this.invalidatedChunks.clear();
      // pzopt: with fboRenderChunk the chunk texture reads only the target cutaway flag of each square, so a chunk
      // level whose square flags end this visit exactly as they started has an unchanged texture. Stock invalidates
      // (2048) every chunk that held or gets a cutaway square on every visit, which while moving re-bakes all of them
      // every frame. Here the old target flags are remembered, the stock flag updates run unchanged, and only chunks
      // where some square's flag differs are invalidated (plus the stock off-screen seen-rooms mask reset for them).
      boolean pzoptChanged = pzopt.Config.CUTAWAY_INVALIDATE_CHANGED && pzopt.Overrides.enabled() && PerformanceSettings.fboRenderChunk;
      if (pzoptChanged) {
         this.pzoptOldFlags.clear();
         this.pzoptTouched.clear();
         for (int i = 0; i < perPlayerData1.lastCutawayVisitorResults.size(); i++) {
            IsoGridSquare square = perPlayerData1.lastCutawayVisitorResults.get(i);
            if (this.pzoptOldFlags.put(square, square.getPlayerCutawayFlag(playerIndex, 0L)) == null) {
               this.pzoptTouched.add(square);
            }
         }
      }

      for (int i = 0; i < perPlayerData1.lastCutawayVisitorResults.size(); i++) {
         IsoGridSquare square = perPlayerData1.lastCutawayVisitorResults.get(i);
         square.setPlayerCutawayFlag(playerIndex, 0, currentTimeMillis);
         IsoChunk chunk = square.getChunk();
         if (!pzoptChanged && chunk != null && !this.invalidatedChunks.contains(chunk)) {
            this.invalidatedChunks.add(chunk);
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            renderLevels.invalidateLevel(square.z, 2048L);
            if (!chunk.IsOnScreen(false)) {
               chunk.getCutawayData().invalidateOccludedSquaresMaskForSeenRooms(playerIndex, square.z);
            }
         }
      }

      for (int i = 0; i < onScreenChunks.size(); i++) {
         IsoChunk chunk = onScreenChunks.get(i);
         if (playerZ >= chunk.minLevel && playerZ <= chunk.maxLevel) {
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (renderLevels.isOnScreen(playerZ)) {
               FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayDataForLevel(playerZ);

               for (int j = 0; j < levelData.exteriorWalls.size(); j++) {
                  FBORenderCutaways.CutawayWall wall = levelData.exteriorWalls.get(j);
                  if (!wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.False)) {
                     wall.setVisitedSquares(perPlayerData1);
                  }
               }
            }
         }
      }

      this.pzoptHoldMirrorWalls(playerIndex, perPlayerData1, currentTimeMillis); // pzopt: mirrors, a mirror's wall stays cut through a corner's flapping

      if (pzoptChanged) { // pzopt: squares entering the result sets keep their pre-visit flag as the "old" value
         for (IsoGridSquare square : perPlayerData1.cutawayVisitorResultsNorth) {
            if (!this.pzoptOldFlags.containsKey(square)) {
               this.pzoptOldFlags.put(square, square.getPlayerCutawayFlag(playerIndex, 0L));
               this.pzoptTouched.add(square);
            }
         }
         for (IsoGridSquare square : perPlayerData1.cutawayVisitorResultsWest) {
            if (!this.pzoptOldFlags.containsKey(square)) {
               this.pzoptOldFlags.put(square, square.getPlayerCutawayFlag(playerIndex, 0L));
               this.pzoptTouched.add(square);
            }
         }
      }

      for (IsoGridSquare square : perPlayerData1.cutawayVisitorResultsNorth) {
         square.addPlayerCutawayFlag(playerIndex, 1, currentTimeMillis);
         IsoChunk chunk = square.getChunk();
         if (!pzoptChanged && chunk != null && !this.invalidatedChunks.contains(chunk)) {
            this.invalidatedChunks.add(chunk);
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            renderLevels.invalidateLevel(square.z, 2048L);
         }
      }

      for (IsoGridSquare square : perPlayerData1.cutawayVisitorResultsWest) {
         square.addPlayerCutawayFlag(playerIndex, 2, currentTimeMillis);
         IsoChunk chunk = square.getChunk();
         if (!pzoptChanged && chunk != null && !this.invalidatedChunks.contains(chunk)) {
            this.invalidatedChunks.add(chunk);
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            renderLevels.invalidateLevel(square.z, 2048L);
         }
      }

      if (pzoptChanged) { // pzopt: invalidate only the chunks where a square's target flag changed
         for (int i = 0; i < this.pzoptTouched.size(); i++) {
            IsoGridSquare square = this.pzoptTouched.get(i);
            int oldFlag = this.pzoptOldFlags.get(square);
            if (oldFlag == square.getPlayerCutawayFlag(playerIndex, 0L)) {
               continue;
            }
            pzoptCutawayChangedSquares++;
            IsoChunk chunk = square.getChunk();
            if (chunk != null && !this.invalidatedChunks.contains(chunk)) {
               this.invalidatedChunks.add(chunk);
               FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
               renderLevels.invalidateLevel(square.z, 2048L);
               if (!chunk.IsOnScreen(false)) {
                  chunk.getCutawayData().invalidateOccludedSquaresMaskForSeenRooms(playerIndex, square.z);
               }
            }
         }
         pzoptCutawayVisits++;
         pzoptCutawayChunksInvalidated += this.invalidatedChunks.size();
         this.pzoptOldFlags.clear();
         this.pzoptTouched.clear();
      }

      for (IsoChunk chunk : this.invalidatedChunks) {
         FBORenderCutaways.ChunkLevelData levelData = chunk.getCutawayData().getDataForLevel(playerZ);
         if (levelData != null) {
            boolean bHasCutawayNorthWallsOnWestEdge = this.hasAnyCutawayWalls(playerIndex, chunk, playerZ, (byte)1, 0, 0, 0, 7);
            if (bHasCutawayNorthWallsOnWestEdge != levelData.hasCutawayNorthWallsOnWestEdge) {
               levelData.hasCutawayNorthWallsOnWestEdge = bHasCutawayNorthWallsOnWestEdge;
               this.invalidateChunk(playerIndex, chunkMap, chunk.wx - 1, chunk.wy, playerZ);
            }

            boolean bHasCutawayNorthWallsOnEastEdge = this.hasAnyCutawayWalls(playerIndex, chunk, playerZ, (byte)1, 7, 0, 7, 7);
            if (bHasCutawayNorthWallsOnEastEdge != levelData.hasCutawayNorthWallsOnEastEdge) {
               levelData.hasCutawayNorthWallsOnEastEdge = bHasCutawayNorthWallsOnEastEdge;
               this.invalidateChunk(playerIndex, chunkMap, chunk.wx + 1, chunk.wy, playerZ);
            }

            boolean bHasCutawayWestWallsOnNorthEdge = this.hasAnyCutawayWalls(playerIndex, chunk, playerZ, (byte)2, 0, 0, 7, 0);
            if (bHasCutawayWestWallsOnNorthEdge != levelData.hasCutawayWestWallsOnNorthEdge) {
               levelData.hasCutawayWestWallsOnNorthEdge = bHasCutawayWestWallsOnNorthEdge;
               this.invalidateChunk(playerIndex, chunkMap, chunk.wx, chunk.wy - 1, playerZ);
            }

            boolean bHasCutawayWestWallsOnSouthEdge = this.hasAnyCutawayWalls(playerIndex, chunk, playerZ, (byte)2, 0, 7, 7, 7);
            if (bHasCutawayWestWallsOnSouthEdge != levelData.hasCutawayWestWallsOnSouthEdge) {
               levelData.hasCutawayWestWallsOnSouthEdge = bHasCutawayWestWallsOnNorthEdge;
               this.invalidateChunk(playerIndex, chunkMap, chunk.wx, chunk.wy + 1, playerZ);
            }
         }
      }
   }

   public static long pzoptRoofFlipsHeld; // pzopt: orphan-structure hide/show changes held by roofHideDebounceFrames
   // pzopt: counters for the log (visits, chunks invalidated, squares whose flag changed)
   public static long pzoptCutawayVisits;
   public static long pzoptCutawayChunksInvalidated;
   public static long pzoptCutawayChangedSquares;

   public static long pzoptWallsSkipped; // pzopt: counters for the log
   public static long pzoptWallsVisited;

   /** pzopt: the square-independent part of IsCutawaySquare; false means no square of the wall can be a cutaway now. */
   private static boolean pzoptWallCanCut(FBORenderCutaways.CutawayWall wall, ArrayList<Long> roomIds, boolean peeking, ArrayList<BuildingDef> collapse) {
      if (roomIds.isEmpty()) {
         if (IsoCamera.frameState.camCharacterSquare == null) {
            return false;
         }
         for (int i = 0; i < collapse.size(); i++) {
            if (wall.isPartOfBuilding(collapse.get(i))) {
               return true;
            }
         }
         return false;
      }
      if (peeking) {
         return true;
      }
      for (int i = 0; i < roomIds.size(); i++) {
         if (wall.occludedRoomIds.contains(roomIds.get(i))) {
            return true;
         }
      }
      return false;
   }

   private void invalidateChunk(int playerIndex, IsoChunkMap chunkMap, int wx, int wy, int z) {
      IsoChunk chunk = chunkMap.getChunk(wx - chunkMap.getWorldXMin(), wy - chunkMap.getWorldYMin());
      if (chunk != null) {
         FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
         renderLevels.invalidateLevel(z, 2048L);
      }
   }

   private boolean hasAnyCutawayWalls(int playerIndex, IsoChunk chunk, int z, byte pcf, int x1, int y1, int x2, int y2) {
      long currentTimeMs = 0L;

      for (int y = y1; y <= y2; y++) {
         for (int x = x1; x <= x2; x++) {
            IsoGridSquare square = chunk.getGridSquare(x, y, z);
            if (square != null && (square.getPlayerCutawayFlag(playerIndex, 0L) & pcf) != 0) {
               return true;
            }
         }
      }

      return false;
   }

   private void doCutawayVisitSquares(IsoGridSquare sq, long currentTimeMillis, ArrayList<IsoChunk> onScreenChunks) {
      this.cutawayVisit(sq, currentTimeMillis, onScreenChunks);
   }

   private boolean IsCutawaySquare(FBORenderCutaways.CutawayWall wall, IsoGridSquare poiSquare, IsoGridSquare square, long currentTimeMillis) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (square == null) {
         return false;
      }

      if (poiSquare.getZ() != square.getZ()) {
         return false;
      }

      if (poiSquare.getRoom() != null && square.getRoom() != null && poiSquare.getBuilding() != square.getRoom().building) {
      }

      ArrayList<Long> tempPlayerCutawayRoomIDs = (ArrayList<Long>)this.cell.tempPlayerCutawayRoomIds.get(playerIndex);
      if (tempPlayerCutawayRoomIDs.isEmpty()) {
         return this.IsCollapsibleBuildingSquare(wall, square);
      }

      if (this.isCutawayDueToPeeking(wall, square)) {
         return true;
      }

      IsoPlayer player = IsoPlayer.players[playerIndex];
      if (player.isClimbing()) {
         long roomID = player.getClimbData().targetGridSquare.getRoomID();
         if (wall.occludedRoomIds.contains(roomID)) {
            return true;
         }
      }

      for (int i = 0; i < tempPlayerCutawayRoomIDs.size(); i++) {
         long roomID = tempPlayerCutawayRoomIDs.get(i);
         if (wall.occludedRoomIds.contains(roomID)) {
            int bit = wall.isHorizontal() ? square.x - wall.x1 : square.y - wall.y1;
            return (wall.occludedSquaresMaskForSeenRooms[playerIndex] & 1 << bit) != 0;
         }
      }

      return false;
   }

   private boolean isCutawayDueToPeeking(FBORenderCutaways.CutawayWall wall, IsoGridSquare square) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      long peekRoomID = this.cell.playerWindowPeekingRoomId[playerIndex];
      if (peekRoomID == -1L) {
         return false;
      }

      int playerX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
      int playerY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
      if (wall.isHorizontal()) {
         if ((playerY == wall.y1 - 1 || playerY == wall.y1) && square.has(IsoPropertyType.GARAGE_DOOR)) {
            IsoObject garageDoor = square.getGarageDoor(true);
            if (garageDoor != null) {
               IsoObject first = garageDoor;
               IsoObject last = garageDoor;

               while (first != null) {
                  IsoObject prev = IsoDoor.getGarageDoorPrev(first);
                  if (prev == null) {
                     break;
                  }

                  first = prev;
               }

               while (last != null) {
                  IsoObject next = IsoDoor.getGarageDoorNext(last);
                  if (next == null) {
                     break;
                  }

                  last = next;
               }

               return square.x >= first.getX() && square.x <= last.getX();
            }
         }

         if ((playerY == wall.y1 - 1 || playerY == wall.y1) && square.x >= playerX && square.x <= playerX + 1) {
            return true;
         }
      } else {
         if ((playerX == wall.x1 - 1 || playerX == wall.x1) && square.has(IsoPropertyType.GARAGE_DOOR)) {
            IsoObject garageDoor = square.getGarageDoor(true);
            if (garageDoor != null) {
               IsoObject first = garageDoor;
               IsoObject last = garageDoor;

               while (first != null) {
                  IsoObject prev = IsoDoor.getGarageDoorPrev(first);
                  if (prev == null) {
                     break;
                  }

                  first = prev;
               }

               while (last != null) {
                  IsoObject next = IsoDoor.getGarageDoorNext(last);
                  if (next == null) {
                     break;
                  }

                  last = next;
               }

               return square.y >= first.getY() && square.y <= last.getY();
            }
         }

         if ((playerX == wall.x1 - 1 || playerX == wall.x1) && square.y >= playerY && square.y <= playerY + 1) {
            return true;
         }
      }

      return false;
   }

   private void cutawayVisit(IsoGridSquare poiSquare, long currentTimeMillis, ArrayList<IsoChunk> onScreenChunks) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoChunkMap chunkMap = this.cell.chunkMap[playerIndex];
      if (chunkMap != null && !chunkMap.ignore) {
         FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];

         // pzopt: Config.CUTAWAY_VISIT_PREFILTER. IsCutawaySquare can only be true for a wall that (a) with no cutaway
         // rooms: belongs to a building in buildingsToCollapse, or (b) with cutaway rooms: occludes one of them, or the
         // player is peeking through a window (per-square garage-door test, kept). Every other wall's square loop is a
         // no-op apart from filling the visited sets, whose only reader (the later points of interest) is handled in
         // doCutawayVisitSquares. Those walls are skipped before their squares are looked up.
         boolean pzoptPrefilter = pzopt.Config.CUTAWAY_VISIT_PREFILTER && pzopt.Overrides.enabled();
         ArrayList<Long> pzoptRoomIds = pzoptPrefilter ? this.cell.tempPlayerCutawayRoomIds.get(playerIndex) : null;
         boolean pzoptPeeking = pzoptPrefilter && this.cell.playerWindowPeekingRoomId[playerIndex] != -1L;
         ArrayList<BuildingDef> pzoptCollapse = pzoptPrefilter ? perPlayerData1.buildingsToCollapse.buildingsToCollapse : null;

         for (int j = 0; j < perPlayerData1.cutawayWalls.size(); j++) {
            FBORenderCutaways.CutawayWall wall = perPlayerData1.cutawayWalls.get(j);
            int level = wall.chunkLevelData.level;
            if (pzoptPrefilter && !pzoptWallCanCut(wall, pzoptRoomIds, pzoptPeeking, pzoptCollapse)) {
               pzoptWallsSkipped++;
               continue;
            }
            pzoptWallsVisited++;
            if (wall.isHorizontal()) {
               for (int x1 = wall.x1; x1 < wall.x2; x1++) {
                  IsoGridSquare test = chunkMap.getGridSquare(x1, wall.y1, poiSquare.z);
                  if (test != null) {
                     boolean bVisited = perPlayerData1.cutawayVisitorVisitedNorth.contains(test);
                     if (!bVisited) {
                        perPlayerData1.cutawayVisitorVisitedNorth.add(test);
                     }

                     FBORenderCutaways.ChunkLevelData levelData = test.chunk == wall.chunkLevelData.levelsData.chunk ? wall.chunkLevelData : test.chunk.getCutawayDataForLevel(level); // pzopt: the wall's own level data when the square is in its chunk (level == wall level)
                     if (levelData.shouldRenderSquare(playerIndex, test)
                        && !test.getObjects().isEmpty()
                        && !bVisited
                        && this.IsCutawaySquare(wall, poiSquare, test, currentTimeMillis)) {
                        perPlayerData1.cutawayVisitorResultsNorth.add(test);
                        if (test.has(IsoFlagType.WallSE)) {
                           perPlayerData1.cutawayVisitorResultsWest.add(test);
                        }
                     }
                  }
               }
            } else {
               for (int y1 = wall.y1; y1 < wall.y2; y1++) {
                  IsoGridSquare test = this.cell.getGridSquare(wall.x1, y1, poiSquare.z);
                  if (test != null) {
                     boolean bVisited = perPlayerData1.cutawayVisitorVisitedWest.contains(test);
                     if (!bVisited) {
                        perPlayerData1.cutawayVisitorVisitedWest.add(test);
                     }

                     FBORenderCutaways.ChunkLevelData levelData = test.chunk == wall.chunkLevelData.levelsData.chunk ? wall.chunkLevelData : test.chunk.getCutawayDataForLevel(level); // pzopt: the wall's own level data when the square is in its chunk (level == wall level)
                     if (levelData.shouldRenderSquare(playerIndex, test)
                        && !test.getObjects().isEmpty()
                        && !bVisited
                        && this.IsCutawaySquare(wall, poiSquare, test, currentTimeMillis)) {
                        perPlayerData1.cutawayVisitorResultsWest.add(test);
                        if (test.has(IsoFlagType.WallSE)) {
                           perPlayerData1.cutawayVisitorResultsNorth.add(test);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public boolean CalculateBuildingsToCollapse() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
      btc.buildingsToCollapse.clear();
      ArrayList<IsoBuilding> buildings = new ArrayList<>();
      boolean bOccludedByOrphanStructureFlag = false;

      for (int j = 0; j < this.pointOfInterest.size(); j++) {
         FBORenderCutaways.PointOfInterest poi = this.pointOfInterest.get(j);
         if (!poi.mousePointer) {
            IsoGridSquare square = this.cell.getGridSquare(poi.x, poi.y, poi.z);
            this.cell.GetBuildingsInFrontOfCharacter(buildings, square, false);
            bOccludedByOrphanStructureFlag |= this.cell.occludedByOrphanStructureFlag;
            if (buildings.isEmpty()) {
               this.cell.GetBuildingsInFrontOfCharacter(buildings, square, true);
               bOccludedByOrphanStructureFlag |= this.cell.occludedByOrphanStructureFlag;
            }

            for (int k = 0; k < buildings.size(); k++) {
               if (!btc.buildingsToCollapse.contains(buildings.get(k).def)) {
                  btc.buildingsToCollapse.add(buildings.get(k).def);
               }
            }
         }
      }

      this.cell.occludedByOrphanStructureFlag = bOccludedByOrphanStructureFlag;
      long peekedRoomID = this.cell.playerWindowPeekingRoomId[playerIndex];
      if (peekedRoomID != -1L) {
         IsoRoom room = IsoWorld.instance.metaGrid.getRoomByID(peekedRoomID);
         BuildingDef buildingDef = room.building.getDef();
         if (!btc.buildingsToCollapse.contains(buildingDef)) {
            btc.buildingsToCollapse.add(buildingDef);
         }
      }

      boolean changed = btc.tempLastBuildingsToCollapse.size() != btc.buildingsToCollapse.size();
      if (!changed) {
         for (int i = 0; i < btc.tempLastBuildingsToCollapse.size(); i++) {
            BuildingDef buildingDef = btc.tempLastBuildingsToCollapse.get(i);
            if (btc.buildingsToCollapse.get(i) != buildingDef) {
               changed = true;
               break;
            }
         }
      }

      if (changed) {
         int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);

         for (int i = 0; i < btc.tempLastBuildingsToCollapse.size(); i++) {
            BuildingDef buildingDef = btc.tempLastBuildingsToCollapse.get(i);
            if (!btc.buildingsToCollapse.contains(buildingDef)) {
               buildingDef.invalidateOverlappedChunkLevelsAbove(playerIndex, playerZ, 18432L);
            }
         }

         for (int i = 0; i < btc.buildingsToCollapse.size(); i++) {
            BuildingDef buildingDef = btc.buildingsToCollapse.get(i);
            if (!btc.tempLastBuildingsToCollapse.contains(buildingDef)) {
               buildingDef.invalidateOverlappedChunkLevelsAbove(playerIndex, playerZ, 18432L);
            }
         }
      }

      if (changed) { // pzopt: dev log
         StringBuilder sb = new StringBuilder("collapse list changed: ").append(btc.tempLastBuildingsToCollapse.size()).append(" -> ").append(btc.buildingsToCollapse.size()).append(" [");
         for (int i = 0; i < btc.buildingsToCollapse.size(); i++) {
            BuildingDef b = btc.buildingsToCollapse.get(i);
            sb.append(i > 0 ? " " : "").append(b.getX()).append(",").append(b.getY()).append("-").append(b.getX2()).append(",").append(b.getY2());
         }
         sb.append("] orphanFlag=").append(this.cell.occludedByOrphanStructureFlag).append(" poi=").append(this.pointOfInterest.size());
         pzoptDevCutawayLog(sb.toString());
      }
      btc.tempLastBuildingsToCollapse.clear();
      PZArrayUtil.addAll(btc.tempLastBuildingsToCollapse, btc.buildingsToCollapse);
      return changed;
   }

   // pzopt: Config.DEV_CUTAWAY_LOG, first 400 lines only
   private static int pzoptDevCutawayLines;

   static void pzoptDevCutawayLog(String what) {
      if (pzopt.Config.DEV_CUTAWAY_LOG && pzoptDevCutawayLines < 400) {
         pzoptDevCutawayLines++;
         pzopt.Log.info("cutaway dev f" + IsoWorld.instance.getFrameNo() + ": " + what);
      }
   }

   public ArrayList<BuildingDef> getCollapsedBuildings() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
      return btc.buildingsToCollapse;
   }

   public boolean isAnyBuildingCollapsed() {
      return !this.getCollapsedBuildings().isEmpty();
   }

   public boolean isBuildingCollapsed(BuildingDef buildingDef) {
      return this.getCollapsedBuildings().contains(buildingDef);
   }

   public boolean checkHiddenBuildingLevels() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
      boolean bForceCutawayUpdate = false;

      for (int i = 0; i < btc.buildingsToCollapse.size(); i++) {
         BuildingDef buildingDef = btc.buildingsToCollapse.get(i);
         if (btc.maxVisibleLevel.containsKey(buildingDef)) {
            if (btc.maxVisibleLevel.get(buildingDef) != playerZ) {
               int minLevel = PZMath.min(btc.maxVisibleLevel.get(buildingDef), playerZ);
               btc.maxVisibleLevel.put(buildingDef, playerZ);
               buildingDef.invalidateOverlappedChunkLevelsAbove(playerIndex, minLevel, 2048L);
               bForceCutawayUpdate = true;
            }
         } else {
            btc.maxVisibleLevel.put(buildingDef, playerZ);
            buildingDef.invalidateOverlappedChunkLevelsAbove(playerIndex, playerZ, 2048L);
         }
      }

      return bForceCutawayUpdate;
   }

   public boolean CanBuildingSquareOccludePlayer(IsoGridSquare square, int playerIndex) {
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;

      for (int i = 0; i < btc.buildingsToCollapse.size(); i++) {
         BuildingDef buildingDef = btc.buildingsToCollapse.get(i);
         int boundsX = buildingDef.getX();
         int boundsY = buildingDef.getY();
         int boundsWidth = buildingDef.getX2() - boundsX;
         int boundsHeight = buildingDef.getY2() - boundsY;
         this.buildingRectTemp.setBounds(boundsX - 1, boundsY - 1, boundsWidth + 2, boundsHeight + 2);
         if (this.buildingRectTemp.contains(square.getX(), square.getY())) {
            return true;
         }
      }

      return false;
   }

   public IsoObject getFirstMultiLevelObject(IsoGridSquare square) {
      if (square == null) {
         return null;
      }

      if (!square.has("SpriteGridPos")) {
         return null;
      }

      List<IsoObject> objects = square.getObjects();
      int size = objects.size();

      for (int i = 0; i < size; i++) {
         IsoObject object = objects.get(i);
         IsoSpriteGrid spriteGrid = object.getSpriteGrid();
         if (spriteGrid != null && spriteGrid.getLevels() > 1) {
            return object;
         }
      }

      return null;
   }

   public boolean isForceRenderSquare(int playerIndex, IsoGridSquare square) {
      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
      if (square.associatedBuilding != null && btc.buildingsToCollapse.contains(square.associatedBuilding) && square.z > playerZ) {
         IsoObject object = this.getFirstMultiLevelObject(square);
         if (object != null) {
            IsoSprite sprite = object.getSprite();
            if (square.z - object.getSpriteGrid().getSpriteGridPosZ(sprite) <= playerZ) {
               return true;
            }
         }
      }

      return false;
   }

   public boolean shouldHideElevatedFloor(int playerIndex, IsoObject object) {
      if (object != null && object.getProperties() != null) {
         if (!object.getProperties().has(IsoFlagType.FloorHeightOneThird) && !object.getProperties().has(IsoFlagType.FloorHeightTwoThirds)) {
            return false;
         }

         IsoGridSquare square = object.getSquare();
         if (square == null) {
            return false;
         }

         int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
         if (square.z != playerZ) {
            return false;
         }

         FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
         return square.associatedBuilding != null && btc.buildingsToCollapse.contains(square.associatedBuilding);
      } else {
         return false;
      }
   }

   public boolean shouldRenderBuildingSquare(int playerIndex, IsoGridSquare square) {
      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
      FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
      FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
      if (square.associatedBuilding != null && btc.buildingsToCollapse.contains(square.associatedBuilding) && square.z > playerZ) {
         IsoObject object = this.getFirstMultiLevelObject(square);
         if (object != null) {
            IsoSprite sprite = object.getSprite();
            if (square.z - object.getSpriteGrid().getSpriteGridPosZ(sprite) <= playerZ) {
               return true;
            }
         }

         return false;
      } else {
         if (square.z > playerZ && playerZ < 0) {
            return false;
         }

         if (square.z == playerZ + 1 && square.hasFloorAtTopOfStairs()) {
            return true;
         }

         if (square.z > playerZ) {
            FBORenderCutaways.ChunkLevelData chunkLevelData = square.chunk.getCutawayDataForLevel(square.z);
            if (chunkLevelData.orphanStructures.adjacentChunkLoadedCounter != square.chunk.adjacentChunkLoadedCounter) {
               chunkLevelData.orphanStructures.adjacentChunkLoadedCounter = square.chunk.adjacentChunkLoadedCounter;
               chunkLevelData.orphanStructures.calculate(square.chunk);
               pzoptDevCutawayLog("orphan recalc (adjacent counter) chunk " + square.chunk.wx + "," + square.chunk.wy + " level " + square.z); // pzopt: dev log
            }

            FBORenderCutaways.OrphanStructures orphanStructures = chunkLevelData.orphanStructures;
            if (orphanStructures.hasOrphanStructures
               && orphanStructures.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)
               && (orphanStructures.isOrphanStructureSquare(square) || orphanStructures.isAdjacentToOrphanStructure(square))) {
               return false;
            }
         }

         return true;
      }
   }

   // pzopt: pixelLight (pplCutEdge): !shouldRenderBuildingSquare without its lazy orphan recalculation, read-only (lattice pack workers)
   public boolean pzoptSquareHidden(int playerIndex, IsoGridSquare square) { // pzopt: new
      int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ); // pzopt: new
      if (square.z <= playerZ || square.chunk == null) { // pzopt: new
         return false; // pzopt: new
      } // pzopt: new
      FBORenderCutaways.BuildingsToCollapse btc = this.perPlayerData[playerIndex].buildingsToCollapse; // pzopt: new
      if (square.associatedBuilding != null && btc.buildingsToCollapse.contains(square.associatedBuilding)) { // pzopt: new
         IsoObject object = this.getFirstMultiLevelObject(square); // pzopt: new
         return object == null || square.z - object.getSpriteGrid().getSpriteGridPosZ(object.getSprite()) > playerZ; // pzopt: new
      } // pzopt: new
      if (playerZ < 0 || square.z == playerZ + 1 && square.hasFloorAtTopOfStairs()) { // pzopt: new
         return playerZ < 0; // pzopt: new
      } // pzopt: new
      FBORenderCutaways.OrphanStructures o = square.chunk.getCutawayDataForLevel(square.z).orphanStructures; // pzopt: new
      return o.hasOrphanStructures && o.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True) // pzopt: new
         && (o.isOrphanStructureSquare(square) || o.isAdjacentToOrphanStructure(square)); // pzopt: new
   }

   private boolean IsCollapsibleBuildingSquare(FBORenderCutaways.CutawayWall wall, IsoGridSquare square) {
      if (square.getProperties().has(IsoFlagType.forceRender)) {
         return false;
      }

      if (IsoCamera.frameState.camCharacterSquare != null) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         FBORenderCutaways.PerPlayerData perPlayerData1 = this.perPlayerData[playerIndex];
         FBORenderCutaways.BuildingsToCollapse btc = perPlayerData1.buildingsToCollapse;
         int size = btc.buildingsToCollapse.size();

         for (int i = 0; i < size; i++) {
            BuildingDef buildingDef = btc.buildingsToCollapse.get(i);
            if (wall.isPartOfBuilding(buildingDef)) {
               this.buildingRectTemp.setBounds(buildingDef.getX() - 1, buildingDef.getY() - 1, buildingDef.getW() + 2, buildingDef.getH() + 2);
               if (this.buildingRectTemp.contains(square.getX(), square.getY())) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public void CalculatePointsOfInterest() {
      this.pointOfInterestStore.releaseAll(this.pointOfInterest);
      this.pointOfInterest.clear();
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoPlayer player = IsoPlayer.players[playerIndex];
      this.AddPointOfInterest(player.getX(), player.getY(), player.getZ());
      if (player.isClimbing()) {
         this.AddPointOfInterest(
            player.getClimbData().targetGridSquare.getX(), player.getClimbData().targetGridSquare.getY(), player.getClimbData().targetGridSquare.getZ()
         );
      }

      if (playerIndex == 0 && player.isAiming() && player.getJoypadBind() == -1) {
         int mx = Mouse.getX();
         int my = Mouse.getY() + 104;
         float ix = IsoUtils.XToIso(mx, my, player.getZi());
         float iy = IsoUtils.YToIso(mx, my, player.getZi());
         this.AddPointOfInterest(ix, iy, player.getZ(), true);
      }

      if (player.getCurrentSquare() != null) {
         this.cell.gridSquaresTempLeft.clear();
         this.cell.gridSquaresTempRight.clear();
         this.cell.GetSquaresAroundPlayerSquare(player, player.getCurrentSquare(), this.cell.gridSquaresTempLeft, this.cell.gridSquaresTempRight);

         for (int i = 0; i < this.cell.gridSquaresTempLeft.size(); i++) {
            IsoGridSquare square = (IsoGridSquare)this.cell.gridSquaresTempLeft.get(i);
            if (square.isCouldSee(playerIndex) && (square.getBuilding() == null || square.getBuilding() == player.getBuilding())) {
               this.AddPointOfInterest(square.x, square.y, square.z);
               if (DebugOptions.instance.fboRenderChunk.renderMustSeeSquares.getValue()) {
                  LineDrawer.addRect(square.x, square.y, square.z, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
               }
            }
         }

         for (int i = 0; i < this.cell.gridSquaresTempRight.size(); i++) {
            IsoGridSquare square = (IsoGridSquare)this.cell.gridSquaresTempRight.get(i);
            if (square.isCouldSee(playerIndex) && (square.getBuilding() == null || square.getBuilding() == player.getBuilding())) {
               this.AddPointOfInterest(square.x, square.y, square.z);
               if (DebugOptions.instance.fboRenderChunk.renderMustSeeSquares.getValue()) {
                  LineDrawer.addRect(square.x, square.y, square.z, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
               }
            }
         }
      }
   }

   private void AddPointOfInterest(float x, float y, float z) {
      this.AddPointOfInterest(x, y, z, false);
   }

   private void AddPointOfInterest(float x, float y, float z, boolean mousePointer) {
      FBORenderCutaways.PointOfInterest p = (FBORenderCutaways.PointOfInterest)this.pointOfInterestStore.alloc();
      p.x = PZMath.fastfloor(x);
      p.y = PZMath.fastfloor(y);
      p.z = PZMath.fastfloor(z);
      p.mousePointer = mousePointer;
      this.pointOfInterest.add(p);
   }

   public boolean isRoofRoomSquare(IsoGridSquare square) {
      if (square == null) {
         return false;
      } else if (square.getZ() == 0) {
         return false;
      } else if (square.getRoomID() != -1L) {
         return false;
      } else {
         return square.associatedBuilding == null ? false : square.TreatAsSolidFloor();
      }
   }

   private static final class BuildingsToCollapse {
      final ArrayList<BuildingDef> buildingsToCollapse = new ArrayList<>();
      final ArrayList<BuildingDef> tempLastBuildingsToCollapse = new ArrayList<>();
      final TObjectIntHashMap<BuildingDef> maxVisibleLevel = new TObjectIntHashMap();
   }

   public static final class ChunkLevelData {
      public FBORenderCutaways.ChunkLevelsData levelsData;
      public final int level;
      public int adjacentChunkLoadedCounter;
      public final ArrayList<FBORenderCutaways.CutawayWall> exteriorWalls = new ArrayList<>();
      public final ArrayList<FBORenderCutaways.CutawayWall> allWalls = new ArrayList<>();
      public final FBORenderCutaways.OrphanStructures orphanStructures = new FBORenderCutaways.OrphanStructures();
      public final ArrayList<FBORenderCutaways.SlopedSurface> slopedSurfaces = new ArrayList<>();
      public final byte[][] squareFlags = new byte[4][64];
      public boolean hasCutawayNorthWallsOnWestEdge;
      public boolean hasCutawayNorthWallsOnEastEdge;
      public boolean hasCutawayWestWallsOnNorthEdge;
      public boolean hasCutawayWestWallsOnSouthEdge;
      public final long[] occludingSquares = new long[4];

      ChunkLevelData(int level) {
         this.level = level;
         this.orphanStructures.chunkLevelData = this;
      }

      public boolean shouldRenderSquare(int playerIndex, IsoGridSquare square) {
         if (square != null && square.chunk != null && square.z == this.level) {
            int lx = square.x - square.chunk.wx * 8;
            int ly = square.y - square.chunk.wy * 8;
            return (this.squareFlags[playerIndex][lx + ly * 8] & 1) != 0;
         } else {
            return false;
         }
      }

      public boolean calculateOccludingSquares(int playerIndex, int occludedX1, int occludedY1, int occludedX2, int occludedY2, int[] occludedGrid) {
         int occludedWidth = occludedX2 - occludedX1 + 1;
         long occludingOld = this.occludingSquares[playerIndex];
         this.occludingSquares[playerIndex] = 0L;
         IsoChunk chunk = this.levelsData.chunk;
         IsoGridSquare[] squares = chunk.getSquaresForLevel(this.level);
         int chunkSizeInSquares = 8;

         for (int i = 0; i < 64; i++) {
            IsoGridSquare square = squares[i];
            if (square != null) {
               int zeroX = square.x - square.z * 3 - occludedX1;
               int zeroY = square.y - square.z * 3 - occludedY1;
               if (zeroX >= 0
                  && zeroY >= 0
                  && zeroX <= occludedX2 - occludedX1
                  && zeroY <= occludedY2 - occludedY1
                  && this.shouldRenderSquare(playerIndex, square)
                  && (square.getVisionMatrix(0, 0, -1) || chunk.getGridSquare(i % 8, i / 8, square.z - 1) == null)) {
                  int zeroIndex = zeroX + zeroY * occludedWidth;
                  occludedGrid[zeroIndex] = PZMath.max(occludedGrid[zeroIndex], square.z);
                  int x = i % 8;
                  int y = i / 8;
                  int bit = 1 << x + y * 8;
                  this.occludingSquares[playerIndex] = this.occludingSquares[playerIndex] | bit;
               }
            }
         }

         return this.occludingSquares[playerIndex] != occludingOld;
      }

      void removeFromWorld() {
         this.adjacentChunkLoadedCounter = 0;
         FBORenderCutaways.s_cutawayWallPool.releaseAll(this.exteriorWalls);
         this.exteriorWalls.clear();
         FBORenderCutaways.s_cutawayWallPool.releaseAll(this.allWalls);
         this.allWalls.clear();

         for (int i = 0; i < 4; i++) {
            Arrays.fill(this.squareFlags[i], (byte)0);
            this.occludingSquares[i] = 0L;
         }

         this.orphanStructures.resetForStore();
         this.hasCutawayNorthWallsOnWestEdge = false;
         this.hasCutawayNorthWallsOnEastEdge = false;
         this.hasCutawayWestWallsOnNorthEdge = false;
         this.hasCutawayWestWallsOnSouthEdge = false;
      }

      void debugRender() {
         ArrayList<FBORenderCutaways.CutawayWall> walls = this.allWalls;

         for (int i = 0; i < walls.size(); i++) {
            FBORenderCutaways.CutawayWall wall = walls.get(i);
            if (wall.isHorizontal()) {
               for (int x = wall.x1; x < wall.x2; x++) {
                  IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, wall.y1, this.level);
                  float r = 0.0F;
                  float g = 1.0F;
                  float b = 0.0F;
                  if (square != null && (square.getPlayerCutawayFlag(IsoCamera.frameState.playerIndex, 0L) & 1) != 0) {
                     r = 1.0F;
                  }

                  if (wall.isPlayerInRange(IsoCamera.frameState.playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                     b = 1.0F;
                  }

                  LineDrawer.addLine(
                     x + (x == wall.x1 ? 0.05F : 0.0F), wall.y1, this.level, x + 1 - (x == wall.x2 - 1 ? 0.05F : 0.0F), wall.y2, this.level, r, 1.0F, b, 1.0F
                  );
               }
            } else {
               for (int y = wall.y1; y < wall.y2; y++) {
                  IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(wall.x1, y, this.level);
                  float r = 0.0F;
                  float g = 1.0F;
                  float b = 0.0F;
                  if (square != null && (square.getPlayerCutawayFlag(IsoCamera.frameState.playerIndex, 0L) & 2) != 0) {
                     r = 1.0F;
                  }

                  if (wall.isPlayerInRange(IsoCamera.frameState.playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                     b = 1.0F;
                  }

                  LineDrawer.addLine(
                     wall.x1, y + (y == wall.y1 ? 0.05F : 0.0F), this.level, wall.x1, y + 1 - (y == wall.y2 - 1 ? 0.05F : 0.0F), this.level, r, 1.0F, b, 1.0F
                  );
               }
            }
         }

         for (int i = 0; i < this.slopedSurfaces.size(); i++) {
            FBORenderCutaways.SlopedSurface slopedSurface = this.slopedSurfaces.get(i);
            if (slopedSurface.isHorizontal()) {
               for (int x = slopedSurface.x1; x < slopedSurface.x2; x++) {
                  IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, slopedSurface.y1, this.level);
                  float r = 0.0F;
                  float g = 1.0F;
                  float b = 0.0F;
                  if (square != null && (square.getPlayerCutawayFlag(IsoCamera.frameState.playerIndex, 0L) & 1) != 0) {
                     r = 1.0F;
                  }

                  LineDrawer.addLine(
                     x + (x == slopedSurface.x1 ? 0.05F : 0.0F),
                     slopedSurface.y1,
                     this.level,
                     x + 1 - (x == slopedSurface.x2 - 1 ? 0.05F : 0.0F),
                     slopedSurface.y2,
                     this.level,
                     r,
                     1.0F,
                     0.0F,
                     1.0F
                  );
               }
            } else {
               for (int y = slopedSurface.y1; y < slopedSurface.y2; y++) {
                  IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(slopedSurface.x1, y, this.level);
                  float r = 0.0F;
                  float g = 1.0F;
                  float b = 0.0F;
                  if (square != null && (square.getPlayerCutawayFlag(IsoCamera.frameState.playerIndex, 0L) & 2) != 0) {
                     r = 1.0F;
                  }

                  if (slopedSurface.isPlayerInRange(IsoCamera.frameState.playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                     b = 1.0F;
                  }

                  LineDrawer.addLine(
                     slopedSurface.x1,
                     y + (y == slopedSurface.y1 ? 0.05F : 0.0F),
                     this.level,
                     slopedSurface.x1,
                     y + 1 - (y == slopedSurface.y2 - 1 ? 0.05F : 0.0F),
                     this.level,
                     r,
                     1.0F,
                     b,
                     1.0F
                  );
               }
            }
         }
      }
   }

   public static final class ChunkLevelsData {
      public final IsoChunk chunk;
      public final TIntObjectHashMap<FBORenderCutaways.ChunkLevelData> levelData = new TIntObjectHashMap();

      public ChunkLevelsData(IsoChunk chunk) {
         this.chunk = chunk;
      }

      public FBORenderCutaways.ChunkLevelData getDataForLevel(int level) {
         if (level >= -32 && level <= 31) {
            int index = level + 32;
            FBORenderCutaways.ChunkLevelData levelData = (FBORenderCutaways.ChunkLevelData)this.levelData.get(index);
            if (levelData == null) {
               levelData = new FBORenderCutaways.ChunkLevelData(level);
               levelData.levelsData = this;
               this.levelData.put(index, levelData);
            }

            return levelData;
         } else {
            return null;
         }
      }

      public void recreateLevel(int level) {
         this.recreateLevel_ExteriorWalls(level);
         this.recreateLevel_AllWalls(level);
         this.recreateLevel_SlopedSurfaces(level);
         if (level > 0) {
            FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);
            levelData.orphanStructures.calculate(this.chunk);
         }
      }

      public void recreateLevel_ExteriorWalls(int level) {
         FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);
         this.clearPlayerCutawayFlags(level, levelData.exteriorWalls);
         FBORenderCutaways.s_cutawayWallPool.releaseAll(levelData.exteriorWalls);
         levelData.exteriorWalls.clear();
         if (level >= this.chunk.minLevel && level <= this.chunk.maxLevel) {
            IsoGridSquare[] squares = this.chunk.squares[this.chunk.squaresIndexOfLevel(level)];
            int chunksPerWidth = 8;

            for (int y = 0; y < 8; y++) {
               FBORenderCutaways.CutawayWall wall = null;

               for (int x = 0; x < 8; x++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (square != null
                     && square.getWall(true) != null
                     && (
                        square.has(IsoFlagType.WallN) || square.has(IsoFlagType.WallNW) || square.has(IsoFlagType.DoorWallN) || square.has(IsoFlagType.WindowN)
                     )
                     && !this.isAdjacentToRoom(square, IsoDirections.N)) {
                     if (wall == null) {
                        wall = (FBORenderCutaways.CutawayWall)FBORenderCutaways.s_cutawayWallPool.alloc();
                        wall.chunkLevelData = levelData;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                        Arrays.fill(wall.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                        wall.occludedRoomIds.resetQuick();
                        Arrays.fill(wall.occludedSquaresMaskForSeenRooms, 0);
                     }
                  } else if (wall != null) {
                     wall.x2 = this.chunk.wx * 8 + x;
                     wall.y2 = this.chunk.wy * 8 + y;
                     levelData.exteriorWalls.add(wall);
                     wall = null;
                  }
               }

               if (wall != null) {
                  wall.x2 = this.chunk.wx * 8 + 8;
                  wall.y2 = this.chunk.wy * 8 + y;
                  levelData.exteriorWalls.add(wall);
               }
            }

            for (int x = 0; x < 8; x++) {
               FBORenderCutaways.CutawayWall wall = null;

               for (int y = 0; y < 8; y++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (square != null
                     && square.getWall(false) != null
                     && (
                        square.has(IsoFlagType.WallW) || square.has(IsoFlagType.WallNW) || square.has(IsoFlagType.DoorWallW) || square.has(IsoFlagType.WindowW)
                     )
                     && !this.isAdjacentToRoom(square, IsoDirections.W)) {
                     if (wall == null) {
                        wall = (FBORenderCutaways.CutawayWall)FBORenderCutaways.s_cutawayWallPool.alloc();
                        wall.chunkLevelData = levelData;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                        Arrays.fill(wall.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                        wall.occludedRoomIds.resetQuick();
                        Arrays.fill(wall.occludedSquaresMaskForSeenRooms, 0);
                     }
                  } else if (wall != null) {
                     wall.x2 = this.chunk.wx * 8 + x;
                     wall.y2 = this.chunk.wy * 8 + y;
                     levelData.exteriorWalls.add(wall);
                     wall = null;
                  }
               }

               if (wall != null) {
                  wall.x2 = this.chunk.wx * 8 + x;
                  wall.y2 = this.chunk.wy * 8 + 8;
                  levelData.exteriorWalls.add(wall);
               }
            }
         }
      }

      public void recreateLevel_AllWalls(int level) {
         FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);
         this.clearPlayerCutawayFlags(level, levelData.allWalls);
         FBORenderCutaways.s_cutawayWallPool.releaseAll(levelData.allWalls);
         levelData.allWalls.clear();
         if (level >= this.chunk.minLevel && level <= this.chunk.maxLevel) {
            IsoGridSquare[] squares = this.chunk.squares[this.chunk.squaresIndexOfLevel(level)];
            int chunksPerWidth = 8;

            for (int y = 0; y < 8; y++) {
               FBORenderCutaways.CutawayWall wall = null;

               for (int x = 0; x < 8; x++) {
                  IsoGridSquare square = squares[x + y * 8];
                  boolean bWallLike = square != null
                     && (square.getWall(true) != null || square.has(IsoFlagType.WindowN))
                     && (
                        square.has(IsoFlagType.WallN) || square.has(IsoFlagType.WallNW) || square.has(IsoFlagType.DoorWallN) || square.has(IsoFlagType.WindowN)
                     );
                  if (!bWallLike && square != null) {
                     bWallLike |= square.getGarageDoor(true) != null;
                  }

                  if (bWallLike) {
                     if (wall == null) {
                        wall = (FBORenderCutaways.CutawayWall)FBORenderCutaways.s_cutawayWallPool.alloc();
                        wall.chunkLevelData = levelData;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                        Arrays.fill(wall.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                        wall.occludedRoomIds.resetQuick();
                        Arrays.fill(wall.occludedSquaresMaskForSeenRooms, 0);
                     }
                  } else if (wall != null) {
                     wall.x2 = this.chunk.wx * 8 + x;
                     wall.y2 = this.chunk.wy * 8 + y;
                     wall.calculateOccludedRooms();
                     levelData.allWalls.add(wall);
                     wall = null;
                  }
               }

               if (wall != null) {
                  wall.x2 = this.chunk.wx * 8 + 8;
                  wall.y2 = this.chunk.wy * 8 + y;
                  wall.calculateOccludedRooms();
                  levelData.allWalls.add(wall);
               }
            }

            for (int x = 0; x < 8; x++) {
               FBORenderCutaways.CutawayWall wall = null;

               for (int y = 0; y < 8; y++) {
                  IsoGridSquare square = squares[x + y * 8];
                  boolean bWallLike = square != null
                     && (square.getWall(false) != null || square.has(IsoFlagType.WindowW))
                     && (
                        square.has(IsoFlagType.WallW) || square.has(IsoFlagType.WallNW) || square.has(IsoFlagType.DoorWallW) || square.has(IsoFlagType.WindowW)
                     );
                  if (!bWallLike && square != null) {
                     bWallLike |= square.getGarageDoor(false) != null;
                  }

                  if (bWallLike) {
                     if (wall == null) {
                        wall = (FBORenderCutaways.CutawayWall)FBORenderCutaways.s_cutawayWallPool.alloc();
                        wall.chunkLevelData = levelData;
                        wall.x1 = square.x;
                        wall.y1 = square.y;
                        Arrays.fill(wall.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                        wall.occludedRoomIds.resetQuick();
                        Arrays.fill(wall.occludedSquaresMaskForSeenRooms, 0);
                     }
                  } else if (wall != null) {
                     wall.x2 = this.chunk.wx * 8 + x;
                     wall.y2 = this.chunk.wy * 8 + y;
                     wall.calculateOccludedRooms();
                     levelData.allWalls.add(wall);
                     wall = null;
                  }
               }

               if (wall != null) {
                  wall.x2 = this.chunk.wx * 8 + x;
                  wall.y2 = this.chunk.wy * 8 + 8;
                  wall.calculateOccludedRooms();
                  levelData.allWalls.add(wall);
               }
            }
         }
      }

      public void recreateLevel_SlopedSurfaces(int level) {
         FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);
         this.clearPlayerCutawayFlags2(level, levelData.slopedSurfaces);
         FBORenderCutaways.s_slopedSurfacePool.releaseAll(levelData.slopedSurfaces);
         levelData.slopedSurfaces.clear();
         if (level >= this.chunk.minLevel && level <= this.chunk.maxLevel) {
            IsoGridSquare[] squares = this.chunk.squares[this.chunk.squaresIndexOfLevel(level)];
            int chunksPerWidth = 8;

            for (int y = 0; y < 8; y++) {
               FBORenderCutaways.SlopedSurface slopedSurface = null;

               for (int x = 0; x < 8; x++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (square != null && (square.getSlopedSurfaceDirection() == IsoDirections.W || square.getSlopedSurfaceDirection() == IsoDirections.E)) {
                     if (slopedSurface == null) {
                        slopedSurface = (FBORenderCutaways.SlopedSurface)FBORenderCutaways.s_slopedSurfacePool.alloc();
                        slopedSurface.chunkLevelData = levelData;
                        slopedSurface.x1 = square.x;
                        slopedSurface.y1 = square.y;
                        Arrays.fill(slopedSurface.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                     }
                  } else if (slopedSurface != null) {
                     slopedSurface.x2 = this.chunk.wx * 8 + x;
                     slopedSurface.y2 = this.chunk.wy * 8 + y;
                     levelData.slopedSurfaces.add(slopedSurface);
                     slopedSurface = null;
                  }
               }

               if (slopedSurface != null) {
                  slopedSurface.x2 = this.chunk.wx * 8 + 8;
                  slopedSurface.y2 = this.chunk.wy * 8 + y;
                  levelData.slopedSurfaces.add(slopedSurface);
               }
            }

            for (int x = 0; x < 8; x++) {
               FBORenderCutaways.SlopedSurface slopedSurface = null;

               for (int y = 0; y < 8; y++) {
                  IsoGridSquare square = squares[x + y * 8];
                  if (square != null && (square.getSlopedSurfaceDirection() == IsoDirections.N || square.getSlopedSurfaceDirection() == IsoDirections.S)) {
                     if (slopedSurface == null) {
                        slopedSurface = (FBORenderCutaways.SlopedSurface)FBORenderCutaways.s_slopedSurfacePool.alloc();
                        slopedSurface.chunkLevelData = levelData;
                        slopedSurface.x1 = square.x;
                        slopedSurface.y1 = square.y;
                        Arrays.fill(slopedSurface.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
                     }
                  } else if (slopedSurface != null) {
                     slopedSurface.x2 = this.chunk.wx * 8 + x;
                     slopedSurface.y2 = this.chunk.wy * 8 + y;
                     levelData.slopedSurfaces.add(slopedSurface);
                     slopedSurface = null;
                  }
               }

               if (slopedSurface != null) {
                  slopedSurface.x2 = this.chunk.wx * 8 + x;
                  slopedSurface.y2 = this.chunk.wy * 8 + 8;
                  levelData.slopedSurfaces.add(slopedSurface);
               }
            }
         }
      }

      void clearPlayerCutawayFlags(int level, ArrayList<FBORenderCutaways.CutawayWall> walls) {
         int bInvalidate = 0;

         for (int i = 0; i < walls.size(); i++) {
            FBORenderCutaways.CutawayWall wall = walls.get(i);

            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               if (wall.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                  wall.setPlayerCutawayFlag(playerIndex, false);
                  bInvalidate |= 1 << playerIndex;
               }
            }
         }

         if (bInvalidate != 0) {
            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               if ((bInvalidate & 1 << playerIndex) != 0) {
                  this.chunk.getRenderLevels(playerIndex).invalidateLevel(level, 2048L);
               }
            }
         }
      }

      void clearPlayerCutawayFlags2(int level, ArrayList<FBORenderCutaways.SlopedSurface> slopedSurfaces) {
         int bInvalidate = 0;

         for (int i = 0; i < slopedSurfaces.size(); i++) {
            FBORenderCutaways.SlopedSurface slopedSurface = slopedSurfaces.get(i);

            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               if (slopedSurface.isPlayerInRange(playerIndex, FBORenderCutaways.PlayerInRange.True)) {
                  slopedSurface.setPlayerCutawayFlag(playerIndex, false);
                  bInvalidate |= 1 << playerIndex;
               }
            }
         }

         if (bInvalidate != 0) {
            for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
               if ((bInvalidate & 1 << playerIndex) != 0) {
                  this.chunk.getRenderLevels(playerIndex).invalidateLevel(level, 2048L);
               }
            }
         }
      }

      boolean isAdjacentToRoom(IsoGridSquare square, IsoDirections dir) {
         IsoGridSquare adj = square.getAdjacentSquare(dir);
         return adj == null || adj.getBuilding() == null && adj.roofHideBuilding == null && !this.hasRoomBelow(adj)
            ? square.getRoom() != null || square.roofHideBuilding != null || this.hasRoomBelow(square)
            : true;
      }

      boolean hasRoomBelow(IsoGridSquare square) {
         if (square == null || square.chunk == null) {
            return false;
         }

         if (square.getZ() == 0) {
            return false;
         }

         for (int z = square.z - 1; z >= square.chunk.minLevel; z--) {
            IsoGridSquare square1 = square.getCell().getGridSquare(square.x, square.y, z);
            if (square1 != null && (square1.getBuilding() != null || square1.roofHideBuilding != null)) {
               return true;
            }
         }

         return false;
      }

      public void invalidateOccludedSquaresMaskForSeenRooms(int playerIndex, int level) {
         FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);

         for (int i = 0; i < levelData.allWalls.size(); i++) {
            FBORenderCutaways.CutawayWall wall = levelData.allWalls.get(i);
            wall.occludedSquaresMaskForSeenRooms[playerIndex] = 0;
         }
      }

      public void invalidateAll() {
         for (int z = this.chunk.getMinLevel(); z <= this.chunk.getMaxLevel(); z++) {
            int index = z + 32;
            FBORenderCutaways.ChunkLevelData levelData = (FBORenderCutaways.ChunkLevelData)this.levelData.get(index);
            if (levelData != null) {
               levelData.adjacentChunkLoadedCounter = 0;
            }
         }
      }

      public void removeFromWorld() {
         for (FBORenderCutaways.ChunkLevelData levelData : this.levelData.valueCollection()) {
            levelData.removeFromWorld();
         }
      }

      public void debugRender(int level) {
         FBORenderCutaways.ChunkLevelData levelData = this.getDataForLevel(level);
         levelData.debugRender();
      }
   }

   public static final class CutawayWall {
      FBORenderCutaways.ChunkLevelData chunkLevelData;
      public int x1;
      public int y1;
      public int x2;
      public int y2;
      final FBORenderCutaways.PlayerInRange[] playerInRange = new FBORenderCutaways.PlayerInRange[4];
      final TLongArrayList occludedRoomIds = new TLongArrayList();
      final int[] occludedSquaresMaskForSeenRooms = new int[4];
      static final int[] NORTH_WALL_DXY = new int[]{-1, -1, -2, -2, -3, -3, 0, -1, -1, -2, -2, -3};
      static final int[] WEST_WALL_DXY = new int[]{-1, -1, -2, -2, -3, -3, -1, 0, -2, -1, -3, -2};

      boolean isHorizontal() {
         return this.y1 == this.y2;
      }

      void calculateOccludedRooms() {
         IsoCell cell = IsoWorld.instance.currentCell;
         if (this.isHorizontal()) {
            for (int x = this.x1; x < this.x2; x++) {
               for (int i = 0; i < NORTH_WALL_DXY.length - 1; i += 2) {
                  IsoGridSquare square = cell.getGridSquare(x + NORTH_WALL_DXY[i], this.y1 + NORTH_WALL_DXY[i + 1], this.chunkLevelData.level);
                  if (square != null) {
                     long roomID = square.getRoomID();
                     if (roomID == -1L && FBORenderCutaways.getInstance().isRoofRoomSquare(square)) {
                        roomID = square.associatedBuilding.getRoofRoomID(square.z);
                     }

                     if (roomID != -1L && !this.occludedRoomIds.contains(roomID)) {
                        this.occludedRoomIds.add(roomID);
                     }
                  }
               }
            }
         } else {
            for (int y = this.y1; y < this.y2; y++) {
               for (int i = 0; i < WEST_WALL_DXY.length - 1; i += 2) {
                  IsoGridSquare square = cell.getGridSquare(this.x1 + WEST_WALL_DXY[i], y + WEST_WALL_DXY[i + 1], this.chunkLevelData.level);
                  if (square != null) {
                     long roomID = square.getRoomID();
                     if (roomID == -1L && FBORenderCutaways.getInstance().isRoofRoomSquare(square)) {
                        roomID = square.associatedBuilding.getRoofRoomID(square.z);
                     }

                     if (roomID != -1L && !this.occludedRoomIds.contains(roomID)) {
                        this.occludedRoomIds.add(roomID);
                     }
                  }
               }
            }
         }
      }

      @Deprecated
      boolean isSquareOccludingRoom(IsoGridSquare square, long roomID) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         IsoCell cell = IsoWorld.instance.currentCell;

         for (int i = 1; i <= 3; i++) {
            IsoGridSquare square2 = cell.getGridSquare(square.x - i, square.y - i, square.z);
            if (square2 != null && square2.getRoomID() == roomID && square2.isCouldSee(playerIndex)) {
               return true;
            }
         }

         return false;
      }

      boolean shouldCutawayFence() {
         int max = 1;
         if (IsoCamera.frameState.playerIndex == 0 && IsoPlayer.players[0].isAiming()) {
            max = 2;
         }

         max = Math.min(max, FBORenderCutaways.instance.pointOfInterest.size());

         for (int i = 0; i < FBORenderCutaways.instance.pointOfInterest.size(); i++) {
            FBORenderCutaways.PointOfInterest poi = FBORenderCutaways.instance.pointOfInterest.get(i);
            if (i >= max) {
               if (this.shouldCutawayFence(poi.getSquare(), 3)) {
                  return true;
               }
            } else if (this.shouldCutawayFence(poi.getSquare(), 6)) {
               return true;
            }
         }

         return false;
      }

      boolean shouldCutawayFence(IsoGridSquare square, int range) {
         if (square == null) {
            return false;
         }

         if (!square.isCanSee(IsoCamera.frameState.playerIndex)) {
            return false;
         }

         assert square.z == this.chunkLevelData.level;
         return this.isHorizontal() ? square.y < this.y1 && square.y >= this.y1 - range : square.x < this.x1 && square.x >= this.x1 - range;
      }

      boolean shouldCutawayBuilding(int playerIndex) {
         ArrayList<Long> roomIDs = (ArrayList<Long>)FBORenderCutaways.instance.cell.tempPlayerCutawayRoomIds.get(playerIndex);

         for (int i = 0; i < roomIDs.size(); i++) {
            long roomID = roomIDs.get(i);
            if (this.occludedRoomIds.contains(roomID)) {
               return true;
            }
         }

         return false;
      }

      int calculateOccludedSquaresMask(int playerIndex, long roomID) {
         if (!this.occludedRoomIds.contains(roomID)) {
            return 0;
         }

         int playerZ = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(playerIndex);
         int mask = 0;
         if (this.isHorizontal()) {
            for (int x = this.x1; x < this.x2; x++) {
               for (int j = 0; j < NORTH_WALL_DXY.length - 1; j += 2) {
                  IsoGridSquare square = chunkMap.getGridSquare(x + NORTH_WALL_DXY[j], this.y1 + NORTH_WALL_DXY[j + 1], playerZ);
                  if (square != null && !square.getObjects().isEmpty() && square.isCouldSee(playerIndex)) {
                     long squareRoomID = square.getRoomID();
                     if (squareRoomID == -1L && FBORenderCutaways.getInstance().isRoofRoomSquare(square)) {
                        squareRoomID = square.associatedBuilding.getRoofRoomID(playerZ);
                     }

                     if (squareRoomID == roomID) {
                        mask |= 1 << x - this.x1;
                        break;
                     }
                  }
               }
            }
         } else {
            for (int y = this.y1; y < this.y2; y++) {
               for (int j = 0; j < WEST_WALL_DXY.length - 1; j += 2) {
                  IsoGridSquare square = chunkMap.getGridSquare(this.x1 + WEST_WALL_DXY[j], y + WEST_WALL_DXY[j + 1], playerZ);
                  if (square != null && !square.getObjects().isEmpty() && square.isCouldSee(playerIndex)) {
                     long squareRoomID = square.getRoomID();
                     if (squareRoomID == -1L && FBORenderCutaways.getInstance().isRoofRoomSquare(square)) {
                        squareRoomID = square.associatedBuilding.getRoofRoomID(playerZ);
                     }

                     if (squareRoomID == roomID) {
                        mask |= 1 << y - this.y1;
                        break;
                     }
                  }
               }
            }
         }

         return mask;
      }

      int calculateOccludedSquaresMaskForSeenRooms(int playerIndex) {
         int mask = 0;
         ArrayList<Long> roomIDs = (ArrayList<Long>)FBORenderCutaways.instance.cell.tempPlayerCutawayRoomIds.get(playerIndex);

         for (int i = 0; i < roomIDs.size(); i++) {
            long roomID = roomIDs.get(i);
            mask |= this.calculateOccludedSquaresMask(playerIndex, roomID);
         }

         return mask;
      }

      void setPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         this.playerInRange[playerIndex] = bInRange;
      }

      boolean isPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         return this.playerInRange[playerIndex] == bInRange;
      }

      void setPlayerCutawayFlag(int playerIndex, boolean bCutaway) {
         if (this.isHorizontal()) {
            for (int x = this.x1; x < this.x2; x++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, this.y1, this.chunkLevelData.level);
               if (square != null) {
                  if (bCutaway) {
                     square.addPlayerCutawayFlag(playerIndex, 1, 0L);
                  } else {
                     square.clearPlayerCutawayFlag(playerIndex, 1, 0L);
                  }
               }
            }
         } else {
            for (int y = this.y1; y < this.y2; y++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(this.x1, y, this.chunkLevelData.level);
               if (square != null) {
                  if (bCutaway) {
                     square.addPlayerCutawayFlag(playerIndex, 2, 0L);
                  } else {
                     square.clearPlayerCutawayFlag(playerIndex, 2, 0L);
                  }
               }
            }
         }
      }

      void setVisitedSquares(FBORenderCutaways.PerPlayerData perPlayerData1) {
         if (this.isHorizontal()) {
            for (int x = this.x1; x < this.x2; x++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, this.y1, this.chunkLevelData.level);
               if (square != null) {
                  perPlayerData1.cutawayVisitorResultsNorth.add(square);
               }
            }
         } else {
            for (int y = this.y1; y < this.y2; y++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(this.x1, y, this.chunkLevelData.level);
               if (square != null) {
                  perPlayerData1.cutawayVisitorResultsWest.add(square);
               }
            }
         }
      }

      boolean isPartOfBuilding(BuildingDef buildingDef) {
         int playerIndex = IsoCamera.frameState.playerIndex;
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(playerIndex);
         if (this.isHorizontal()) {
            if (this.y1 < buildingDef.getY() || this.y1 > buildingDef.getY2()) {
               return false;
            }

            for (int x = this.x1; x < this.x2; x++) {
               IsoGridSquare sq = chunkMap.getGridSquare(x, this.y1, this.chunkLevelData.level);
               if (sq != null && sq.getBuildingDef() == buildingDef) {
                  return true;
               }

               sq = chunkMap.getGridSquare(x, this.y1 - 1, this.chunkLevelData.level);
               if (sq != null && sq.getBuildingDef() == buildingDef) {
                  return true;
               }
            }
         } else {
            if (this.x1 < buildingDef.getX() || this.x1 > buildingDef.getX2()) {
               return false;
            }

            for (int y = this.y1; y < this.y2; y++) {
               IsoGridSquare sq = chunkMap.getGridSquare(this.x1, y, this.chunkLevelData.level);
               if (sq != null && sq.getBuildingDef() == buildingDef) {
                  return true;
               }

               sq = chunkMap.getGridSquare(this.x1 - 1, y, this.chunkLevelData.level);
               if (sq != null && sq.getBuildingDef() == buildingDef) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   public static final class OrphanStructures {
      FBORenderCutaways.ChunkLevelData chunkLevelData;
      final FBORenderCutaways.PlayerInRange[] playerInRange = new FBORenderCutaways.PlayerInRange[4];
      final int[] pzoptPendingFrames = new int[4]; // pzopt: consecutive frames the opposite hide/show answer has held (roofHideDebounceFrames)
      boolean hasOrphanStructures;
      long isOrphanStructureSquare;
      int adjacentChunkLoadedCounter;

      void calculate(IsoChunk chunk) {
         Arrays.fill(this.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
         Arrays.fill(this.pzoptPendingFrames, 0); // pzopt
         this.hasOrphanStructures = false;
         this.isOrphanStructureSquare = 0L;
         if (this.chunkLevelData.level >= chunk.minLevel && this.chunkLevelData.level <= chunk.maxLevel) {
            int index = chunk.squaresIndexOfLevel(this.chunkLevelData.level);
            IsoGridSquare[] squares = chunk.squares[index];

            for (int i = 0; i < squares.length; i++) {
               IsoGridSquare square = squares[i];
               if (this.calculateOrphanStructureSquare(square)) {
                  this.hasOrphanStructures = true;
                  this.isOrphanStructureSquare |= 1L << i;
               }
            }
         }
      }

      boolean calculateOrphanStructureSquare(IsoGridSquare square) {
         if (square == null) {
            return false;
         }

         IsoBuilding squareBuilding = square.getBuilding();
         if (squareBuilding == null) {
            squareBuilding = square.roofHideBuilding;
            if (squareBuilding != null && squareBuilding.isEntirelyEmptyOutside()) {
               return true;
            }
         }

         for (int dropZ = square.getZ() - 1; dropZ >= 0 && squareBuilding == null; dropZ--) {
            IsoGridSquare testDropSquare = square.getCell().getGridSquare(square.x, square.y, dropZ);
            if (testDropSquare != null) {
               squareBuilding = testDropSquare.getBuilding();
               if (squareBuilding == null) {
                  squareBuilding = testDropSquare.roofHideBuilding;
               }
            }
         }

         if (squareBuilding != null) {
            return false;
         }

         if (square.associatedBuilding != null) {
            return false;
         }

         if (this.isPlayerBuiltSquare(square)) {
            return true;
         }

         IsoGridSquare squareToNorth = square.getAdjacentSquare(IsoDirections.N);
         if (squareToNorth != null && squareToNorth.getBuilding() == null) {
            if (this.isPlayerBuiltSquare(squareToNorth)) {
               return true;
            }

            if (squareToNorth.HasStairsBelow()) {
               return true;
            }
         }

         IsoGridSquare squareToWest = square.getAdjacentSquare(IsoDirections.W);
         if (squareToWest != null && squareToWest.getBuilding() == null) {
            if (this.isPlayerBuiltSquare(squareToWest)) {
               return true;
            }

            if (squareToWest.HasStairsBelow()) {
               return true;
            }
         }

         if (square.has(IsoFlagType.WallSE)) {
            IsoGridSquare squareToNorthWest = square.getAdjacentSquare(IsoDirections.NW);
            if (squareToNorthWest != null && squareToNorthWest.getBuilding() == null) {
               if (this.isPlayerBuiltSquare(squareToNorthWest)) {
                  return true;
               }

               if (squareToNorthWest.HasStairsBelow()) {
                  return true;
               }
            }
         }

         return false;
      }

      boolean isPlayerBuiltSquare(IsoGridSquare square) {
         return square.getPlayerBuiltFloor() != null ? true : square.HasStairs() || square.getStairPillar() != null;
      }

      boolean isOrphanStructureSquare(IsoGridSquare square) {
         if (square == null) {
            return false;
         }

         IsoChunk chunk = square.getChunk();
         if (chunk == null) {
            return false;
         }

         if (chunk != this.chunkLevelData.levelsData.chunk) {
            int level = square.getZ();
            if (chunk.isValidLevel(level)) {
               FBORenderCutaways.ChunkLevelData data = chunk.getCutawayDataForLevel(level);
               if (data.orphanStructures.adjacentChunkLoadedCounter != chunk.adjacentChunkLoadedCounter) {
                  data.orphanStructures.adjacentChunkLoadedCounter = chunk.adjacentChunkLoadedCounter;
                  data.orphanStructures.calculate(chunk);
               }

               return data.orphanStructures.isOrphanStructureSquare(square);
            } else {
               return false;
            }
         } else {
            int lx = square.x - chunk.wx * 8;
            int ly = square.y - chunk.wy * 8;
            int squareIndex = lx + ly * 8;
            return (this.isOrphanStructureSquare & 1L << squareIndex) != 0L;
         }
      }

      boolean isAdjacentToOrphanStructure(IsoGridSquare square) {
         if (square == null) {
            return false;
         }

         for (int i = 0; i < FBORenderCutaways.DIRECTIONS.length; i++) {
            IsoDirections dir = FBORenderCutaways.DIRECTIONS[i];
            if (this.isOrphanStructureSquare(square.getAdjacentSquare(dir))) {
               return true;
            }
         }

         return false;
      }

      boolean shouldCutaway() {
         return IsoWorld.instance.currentCell.occludedByOrphanStructureFlag
            ? this.chunkLevelData.level > PZMath.fastfloor(IsoCamera.frameState.camCharacterZ)
            : false;
      }

      void setPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         this.playerInRange[playerIndex] = bInRange;
      }

      boolean isPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         return this.playerInRange[playerIndex] == bInRange;
      }

      void resetForStore() {
         Arrays.fill(this.playerInRange, FBORenderCutaways.PlayerInRange.Unset);
         Arrays.fill(this.pzoptPendingFrames, 0); // pzopt
         this.hasOrphanStructures = false;
         this.isOrphanStructureSquare = 0L;
         this.adjacentChunkLoadedCounter = 0;
      }
   }

   private static final class PerPlayerData {
      long lastPlayerRoomId = -1L;
      final HashSet<IsoGridSquare> cutawayVisitorResultsNorth = new HashSet<>();
      final HashSet<IsoGridSquare> cutawayVisitorResultsWest = new HashSet<>();
      final ArrayList<IsoGridSquare> lastCutawayVisitorResults = new ArrayList<>();
      final HashSet<IsoGridSquare> cutawayVisitorVisitedNorth = new HashSet<>();
      final HashSet<IsoGridSquare> cutawayVisitorVisitedWest = new HashSet<>();
      IsoGridSquare checkSquare;
      final ArrayList<FBORenderCutaways.CutawayWall> cutawayWalls = new ArrayList<>();
      private final FBORenderCutaways.BuildingsToCollapse buildingsToCollapse = new FBORenderCutaways.BuildingsToCollapse();
   }

   enum PlayerInRange {
      Unset,
      True,
      False;
   }

   public static final class PointOfInterest {
      public int x;
      public int y;
      public int z;
      public boolean mousePointer;

      public IsoGridSquare getSquare() {
         return IsoWorld.instance.getCell().getGridSquare(this.x, this.y, this.z);
      }
   }

   public static final class SlopedSurface {
      FBORenderCutaways.ChunkLevelData chunkLevelData;
      public int x1;
      public int y1;
      public int x2;
      public int y2;
      final FBORenderCutaways.PlayerInRange[] playerInRange = new FBORenderCutaways.PlayerInRange[4];

      boolean isHorizontal() {
         return this.x2 > this.x1;
      }

      void setPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         this.playerInRange[playerIndex] = bInRange;
      }

      boolean isPlayerInRange(int playerIndex, FBORenderCutaways.PlayerInRange bInRange) {
         return this.playerInRange[playerIndex] == bInRange;
      }

      boolean shouldCutaway() {
         int max = 1;
         if (IsoCamera.frameState.playerIndex == 0 && IsoPlayer.players[0].isAiming()) {
            max = 2;
         }

         max = Math.min(max, FBORenderCutaways.instance.pointOfInterest.size());

         for (int i = 0; i < FBORenderCutaways.instance.pointOfInterest.size(); i++) {
            FBORenderCutaways.PointOfInterest poi = FBORenderCutaways.instance.pointOfInterest.get(i);
            if (i >= max) {
               if (this.shouldCutaway(poi.getSquare(), 3)) {
                  return true;
               }
            } else if (this.shouldCutaway(poi.getSquare(), 6)) {
               return true;
            }
         }

         return false;
      }

      boolean shouldCutaway(IsoGridSquare square, int range) {
         if (square == null) {
            return false;
         }

         if (IsoCamera.frameState.camCharacterSquare != null && IsoCamera.frameState.camCharacterSquare.hasSlopedSurface()) {
            return false;
         }

         if (!square.isCanSee(IsoCamera.frameState.playerIndex)) {
            return false;
         }

         assert square.z == this.chunkLevelData.level;
         return this.isHorizontal()
            ? square.y < this.y1 && square.y >= this.y1 - range
            : square.x < this.x1 && square.x >= this.x1 - range && square.y >= this.y1 && square.y < this.y2;
      }

      void setPlayerCutawayFlag(int playerIndex, boolean bCutaway) {
         if (this.isHorizontal()) {
            for (int x = this.x1; x <= this.x2; x++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(x, this.y1, this.chunkLevelData.level);
               if (square != null) {
                  if (bCutaway) {
                     square.addPlayerCutawayFlag(playerIndex, 1, 0L);
                  } else {
                     square.clearPlayerCutawayFlag(playerIndex, 1, 0L);
                  }
               }
            }
         } else {
            for (int y = this.y1; y <= this.y2; y++) {
               IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(this.x1, y, this.chunkLevelData.level);
               if (square != null) {
                  if (bCutaway) {
                     square.addPlayerCutawayFlag(playerIndex, 2, 0L);
                  } else {
                     square.clearPlayerCutawayFlag(playerIndex, 2, 0L);
                  }
               }
            }
         }
      }
   }
}
