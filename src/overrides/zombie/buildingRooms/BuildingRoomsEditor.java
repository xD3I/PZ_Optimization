package zombie.buildingRooms;

import zombie.core.Core;

import java.util.ArrayList;
import java.util.function.Consumer;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;
import zombie.Lua.LuaManager.Exposer;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.debug.DebugType;
import zombie.iso.BuildingDef;
import zombie.iso.BuildingID;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridOcclusionData;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLot;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoObject;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.LotHeader;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.objects.IsoLightSwitch;
import zombie.network.GameServer;
import zombie.pathfind.PolygonalMap2;
import zombie.popman.ObjectPool;
import zombie.ui.UIElement;
import zombie.ui.UIManager;

@UsedFromLua
public final class BuildingRoomsEditor {
   static {
      pzopt.Overrides.onClassLoaded("zombie.buildingRooms.BuildingRoomsEditor");
   }

   private static BuildingRoomsEditor instance;
   private final ArrayList<BREBuilding> buildings = new ArrayList<>();
   private final ArrayList<BuildingDef> preEditBuildings = new ArrayList<>();
   private final ArrayList<BuildingDef> tempBuildings = new ArrayList<>();
   private KahluaTable luaEditor;
   BREBuilding currentBuilding;
   private BRERoom currentRoom;
   private int highlightRectForDeletion = -1;
   private int currentLevel;
   private String invalidString;
   final ObjectPool<BuildingRoomsDrawer> drawerPool = new ObjectPool(BuildingRoomsDrawer::new, "BuildingRoomsEditor.drawerPool");

   public static BuildingRoomsEditor getInstance() {
      if (instance == null) {
         instance = new BuildingRoomsEditor();
      }

      return instance;
   }

   public void setLuaEditor(KahluaTable table) {
      this.luaEditor = table;
   }

   public int getBuildingCount() {
      return this.buildings.size();
   }

   public BREBuilding getBuildingByIndex(int index) {
      return this.buildings.get(index);
   }

   public BREBuilding createBuilding() {
      BREBuilding building = new BREBuilding();
      this.callLua("BeforeAddBuilding", building);
      this.buildings.add(building);
      this.callLua("AfterAddBuilding", building);
      return building;
   }

   public void init(int worldX, int worldY) {
      this.checkBuildingAndRoomIDs();
      this.buildings.clear();
      this.preEditBuildings.clear();
      int wh = 32;
      this.tempBuildings.clear();
      IsoWorld.instance.getMetaGrid().getBuildingsIntersecting(worldX - 16, worldY - 16, 32, 32, this.tempBuildings);

      for (BuildingDef buildingDef : this.tempBuildings) {
         this.copyExistingBuilding(buildingDef);
         this.preEditBuildings.add(buildingDef);
      }
   }

   public BREBuilding copyExistingBuilding(BuildingDef buildingDef2) {
      BREBuilding building = this.createBuilding();
      building.copyFrom(buildingDef2);
      return building;
   }

   public void removeBuilding(BREBuilding building) {
      this.callLua("BeforeRemoveBuilding", building);
      this.buildings.remove(building);
      this.callLua("AfterRemoveBuilding", building);
   }

   public boolean canAddRoomRectangle(BRERoom room, int x, int y, int w, int h, int z) {
      if (this.currentBuilding != null) {
         if (room == null) {
            if (this.currentBuilding.intersects(x, y, w, h, z)) {
               return false;
            }

            if (this.currentBuilding.hasNonEmptyRoomsOnLevel(z) && !this.currentBuilding.isAdjacent(x, y, w, h, z)) {
               return false;
            }
         } else {
            if (room.building.intersects(x, y, w, h, z)) {
               return false;
            }

            if (room.getRectangleCount() > 0 && !room.isAdjacent(x, y, w, h)) {
               return false;
            }

            boolean bNonEmptyRoom = room.building.hasNonEmptyRoomsOnLevel(z);
            if (room.getRectangleCount() == 0 && bNonEmptyRoom && !room.building.isAdjacent(x, y, w, h, z)) {
               return false;
            }
         }
      }

      for (BREBuilding building : this.buildings) {
         if (building != this.currentBuilding) {
            if (building.intersects(x, y, w, h, z)) {
               return false;
            }

            if (building.isAdjacent(x, y, w, h, z)) {
               return false;
            }
         }
      }

      this.tempBuildings.clear();
      IsoWorld.instance.metaGrid.getBuildingsIntersecting(x - 1, y - 1, w + 2, h + 2, this.tempBuildings);

      for (BuildingDef buildingDef : this.tempBuildings) {
         if (!this.preEditBuildings.contains(buildingDef)) {
            if (buildingDef.intersects(x, y, w, h, z)) {
               return false;
            }

            if (buildingDef.isAdjacent(x, y, w, h, z)) {
               return false;
            }
         }
      }

      return true;
   }

   public void callLua(String event, Object... args) {
      if (this.luaEditor != null) {
         Object functionObj = UIManager.tableget(this.luaEditor, "OnEvent");
         if (functionObj instanceof LuaClosure) {
            Object[] argsArray = new Object[2 + args.length];
            argsArray[0] = this.luaEditor;
            argsArray[1] = event;
            System.arraycopy(args, 0, argsArray, 2, args.length);
            LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), functionObj, argsArray);
         }
      }
   }

   public void setCurrentBuilding(BREBuilding building) {
      this.currentBuilding = building;
   }

   public void setCurrentRoom(BRERoom room) {
      this.currentRoom = room;
   }

   public void setHighlightRectForDeletion(int rectIndex) {
      this.highlightRectForDeletion = rectIndex;
   }

   public void setCurrentLevel(int level) {
      this.currentLevel = level;
   }

   public boolean isValid() {
      this.invalidString = null;

      for (BREBuilding building : this.buildings) {
         if (building.getRoomCount() == 0) {
            this.invalidString = Translator.getText("IGUI_BuildingRoomsEditor_Invalid_BuildingHasNoRooms", new Object[0]);
            return false;
         }

         for (BRERoom room : building.rooms) {
            if (room.getRectangleCount() == 0) {
               this.invalidString = Translator.getText("IGUI_BuildingRoomsEditor_Invalid_RoomHasNoRectangles", new Object[0]);
               return false;
            }
         }
      }

      return true;
   }

   public String getInvalidString() {
      return this.invalidString;
   }

   public void applyChanges(boolean bLoading) {
      if (this.isValid()) {
         this.checkBuildingAndRoomIDs();

         for (BuildingDef buildingDef : this.preEditBuildings) {
            if (!buildingDef.isUserDefined()) {
               RemovedBuilding removedBuilding = new RemovedBuilding().setFrom(buildingDef);
               IsoWorld.instance.getMetaGrid().getRemovedBuildings().add(removedBuilding);
            }

            BREBuilding.removeFromWorld(buildingDef, bLoading);
            IsoMetaCell metaCell = BREBuilding.getLotHeader(buildingDef);
            this.recalculateBuildingAndRoomIDs(metaCell);
         }

         this.checkBuildingAndRoomIDs();

         for (BREBuilding building : this.buildings) {
            BuildingDef buildingDef = building.buildingDef;
            BREBuilding.calculateBounds(buildingDef);
            IsoMetaCell metaCell = BREBuilding.getLotHeader(buildingDef);
            if (metaCell != null) {
               int cellX = metaCell.getX();
               int cellY = metaCell.getY();
               buildingDef.metaId = buildingDef.calculateMetaID(cellX, cellY);
               BuildingDef existing = (BuildingDef)metaCell.buildingByMetaId.get(buildingDef.metaId);
               if (existing != null) {
                  DebugType.General.error("duplicate BuildingDef.metaID for building at %d,%d", new Object[]{buildingDef.x, buildingDef.y});
                  BREBuilding.removeFromWorld(existing, bLoading);
                  this.recalculateBuildingAndRoomIDs(metaCell);
               }
            }
         }

         this.checkBuildingAndRoomIDs();

         for (BREBuilding building : this.buildings) {
            building.applyChanges(bLoading);
         }

         this.recalculateBuildingAndRoomIDs();
         if (!bLoading) {
            LightingJNI.buildingsChanged();
            this.updateSquares();
            IsoGridOcclusionData.SquareChanged();
            FBORenderCutaways.getInstance().squareChanged(null);
            this.callLua("BeforeClear");
            this.buildings.clear();
            this.callLua("AfterClear");
         }
      }
   }

   public void checkBuildingAndRoomIDs() {
      if (pzopt.Config.SKIP_ID_CHECKS && pzopt.Overrides.enabled() && !Core.debug) { // pzopt: skipIdChecks, a log-only consistency walk over every room (six per load)
         return;
      }
      for (LotHeader lotHeader : IsoLot.InfoHeaders.values()) {
         IsoMetaCell metaCell = IsoWorld.instance.getMetaGrid().getCellData(lotHeader.cellX, lotHeader.cellY);
         if (metaCell != null) {
            this.checkBuildingAndRoomIDs(metaCell);
         }
      }
   }

   public void checkBuildingAndRoomIDs(IsoMetaCell metaCell) {
      // pzopt: roomList.indexOf per room made this O(rooms^2) per cell (0.85 s per load over its three calls);
      // the identity map gives the same first index for every room (docs/plan-instant-load.md L3)
      java.util.IdentityHashMap<RoomDef, Integer> pzoptRoomIndex = null;
      if (pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled()) {
         pzoptRoomIndex = new java.util.IdentityHashMap<>(metaCell.roomList.size() * 2);
         for (int r = metaCell.roomList.size() - 1; r >= 0; r--) {
            pzoptRoomIndex.put(metaCell.roomList.get(r), r); // walking backwards leaves the first occurrence, as indexOf does
         }
      }
      int cellX = metaCell.getX();
      int cellY = metaCell.getY();

      for (int i = 0; i < metaCell.buildings.size(); i++) {
         BuildingDef buildingDef = metaCell.buildings.get(i);
         long expected = BuildingID.makeID(cellX, cellY, i);
         if (expected != buildingDef.id) {
            DebugType.General.error("buildingDef.ID=%d expected=%d", new Object[]{buildingDef.id, expected});
         }

         for (RoomDef roomDef : buildingDef.getRooms()) {
            int index = pzoptRoomIndex != null ? pzoptRoomIndex.getOrDefault(roomDef, -1) : metaCell.roomList.indexOf(roomDef); // pzopt: identity index instead of a linear search per room
            if (index == -1) {
               DebugType.General.error("roomDef missing from IsoMetaCell.RoomList");
            }

            expected = RoomID.makeID(cellX, cellY, index);
            if (expected != roomDef.id) {
               DebugType.General.error("roomDef.ID=%d expected=%d", new Object[]{roomDef.id, expected});
            }
         }

         for (RoomDef roomDef : buildingDef.getEmptyOutside()) {
            int index = pzoptRoomIndex != null ? pzoptRoomIndex.getOrDefault(roomDef, -1) : metaCell.roomList.indexOf(roomDef); // pzopt: identity index instead of a linear search per room
            if (index == -1) {
               DebugType.General.error("roomDef missing from IsoMetaCell.RoomList");
            }

            expected = RoomID.makeID(cellX, cellY, index);
            if (expected != roomDef.id) {
               DebugType.General.error("roomDef.ID=%d expected=%d", new Object[]{roomDef.id, expected});
            }
         }
      }

      for (int i = 0; i < metaCell.roomList.size(); i++) {
         RoomDef roomDef = metaCell.roomList.get(i);
         long expected = RoomID.makeID(cellX, cellY, i);
         if (expected != roomDef.id) {
            DebugType.General.error("roomDef.ID=%d expected=%d", new Object[]{roomDef.id, expected});
         }
      }

      if (metaCell.roomList.size() != metaCell.rooms.size()) {
         DebugType.General.error("lotHeader.RoomList.size()=%d IsoMetaCell.Rooms.size()=%d", new Object[]{metaCell.roomList.size(), metaCell.rooms.size()});
      }
   }

   private void recalculateBuildingAndRoomIDs() {
      for (LotHeader lotHeader : IsoLot.InfoHeaders.values()) {
         IsoMetaCell metaCell = IsoWorld.instance.getMetaGrid().getCellData(lotHeader.cellX, lotHeader.cellY);
         if (metaCell != null) {
            this.recalculateBuildingAndRoomIDs(metaCell);
         }
      }
   }

   void recalculateBuildingAndRoomIDs(IsoMetaCell metaCell) {
      if (metaCell != null) {
         int cellX = metaCell.getX();
         int cellY = metaCell.getY();
         metaCell.rooms.clear();

         for (int i = 0; i < metaCell.roomList.size(); i++) {
            RoomDef roomDef = metaCell.roomList.get(i);
            roomDef.id = RoomID.makeID(cellX, cellY, i);
            metaCell.rooms.put(roomDef.id, roomDef);
         }

         for (int i = 0; i < metaCell.buildings.size(); i++) {
            BuildingDef buildingDef = metaCell.buildings.get(i);
            buildingDef.id = BuildingID.makeID(cellX, cellY, i);
         }

         ArrayList<IsoBuilding> isoBuildings = new ArrayList<>(metaCell.isoBuildings.values());
         metaCell.isoBuildings.clear();

         for (IsoBuilding isoBuilding : isoBuildings) {
            metaCell.isoBuildings.put(isoBuilding.def.id, isoBuilding);
         }

         ArrayList<IsoRoom> isoRooms = new ArrayList<>(metaCell.isoRooms.values());
         metaCell.isoRooms.clear();

         for (IsoRoom isoRoom : isoRooms) {
            metaCell.isoRooms.put(isoRoom.def.id, isoRoom);
         }
      }
   }

   private void updateSquares() {
      IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
      this.forEachSquare(square -> {
         RoomDef roomDef = metaGrid.getRoomAt(square.x, square.y, square.z);
         long roomID = roomDef == null ? -1L : roomDef.id;
         square.setRoomID(roomID);
         roomDef = metaGrid.getEmptyOutsideAt(square.x, square.y, square.z);
         if (roomDef != null) {
            IsoRoom room = square.getChunk().getRoom(roomDef.id);
            square.roofHideBuilding = room == null ? null : room.building;
         }

         IsoRoom room = square.getRoom();
         if (roomID != -1L && room != null) {
            if (room.roomLights.isEmpty()) {
               room.createLights(room.getRoomDef().lightsActive);
               if (!GameServer.server) {
                  ArrayList<IsoRoomLight> roomLightsWorld = IsoWorld.instance.currentCell.roomLights;

                  for (int i = 0; i < room.roomLights.size(); i++) {
                     IsoRoomLight roomLight = (IsoRoomLight)room.roomLights.get(i);
                     if (!roomLightsWorld.contains(roomLight)) {
                        roomLightsWorld.add(roomLight);
                     }
                  }
               }
            }

            for (int i = 0; i < square.getObjects().size(); i++) {
               IsoObject object = square.getObjects().get(i);
               if (object instanceof IsoLightSwitch lightSwitch && !room.lightSwitches.contains(lightSwitch)) {
                  room.lightSwitches.add(lightSwitch);
               }
            }
         }
      });
      this.forEachSquare(square -> {
         square.associatedBuilding = IsoWorld.instance.getMetaGrid().getAssociatedBuildingAt(square.x, square.y);
         square.RecalcProperties();
         square.invalidateRenderChunkLevel(2112L);
         PolygonalMap2.instance.squareChanged(square);
      });
   }

   private void forEachSquare(Consumer<IsoGridSquare> consumer) {
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(i);
         if (chunkMap != null && !chunkMap.ignore) {
            for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
               for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
                  IsoChunk chunk = chunkMap.getChunk(cx, cy);
                  if (chunk != null) {
                     for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
                        IsoGridSquare[] squares = chunk.getSquaresForLevel(z);

                        for (int j = 0; j < squares.length; j++) {
                           IsoGridSquare square = squares[j];
                           if (square != null) {
                              consumer.accept(square);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void renderMain() {
      if (this.luaEditor != null) {
         if (this.luaEditor.rawget("javaObject") instanceof UIElement ui && ui.isVisible()) {
            if (!this.buildings.isEmpty()) {
               BuildingRoomsDrawer drawer = (BuildingRoomsDrawer)this.drawerPool.alloc();
               drawer.set(this.buildings, this.currentRoom, this.currentLevel, this.highlightRectForDeletion);
               SpriteRenderer.instance.drawGeneric(drawer);
            }
         }
      }
   }

   public void load() {
      this.buildings.clear();
      this.preEditBuildings.clear();
      PlayerRoomsFile file = new PlayerRoomsFile();
      file.load();
      this.handleRemovedBuildings(file);
      ArrayList<BuildingDef> buildingDefs = file.getBuildings();

      for (int i = 0; i < buildingDefs.size(); i++) {
         BuildingDef buildingDef = buildingDefs.get(i);
         this.copyExistingBuilding(buildingDef);
      }

      this.applyChanges(true);
   }

   private void handleRemovedBuildings(PlayerRoomsFile file) {
      IsoMetaGrid metaGrid = IsoWorld.instance.metaGrid;
      metaGrid.getRemovedBuildings().clear();
      ArrayList<RemovedBuilding> removedBuildings = file.getRemovedBuildings();

      for (int i = 0; i < removedBuildings.size(); i++) {
         RemovedBuilding removedBuilding = removedBuildings.get(i);
         BuildingDef buildingDef = metaGrid.getBuildingAt(removedBuilding.x, removedBuilding.y, removedBuilding.z);
         if (buildingDef != null) {
            metaGrid.getRemovedBuildings().add(removedBuilding);
            BREBuilding.removeFromWorld(buildingDef, true);
            IsoMetaCell metaCell = BREBuilding.getLotHeader(buildingDef);
            this.recalculateBuildingAndRoomIDs(metaCell);
         }
      }

      removedBuildings.clear();
   }

   public static void Reset() {
      if (instance != null) {
         instance.buildings.clear();
         instance.preEditBuildings.clear();
         instance.currentBuilding = null;
         instance.currentRoom = null;
         instance.currentLevel = 0;
         instance.luaEditor = null;
      }
   }

   public static void setExposed(Exposer exposer) {
      exposer.setExposed(BuildingRoomsEditor.class);
      exposer.setExposed(BREBuilding.class);
      exposer.setExposed(BRERoom.class);
   }
}
