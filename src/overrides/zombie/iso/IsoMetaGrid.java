package zombie.iso;

import gnu.trove.list.array.TIntArrayList;
import java.awt.Rectangle;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.MapGroups;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.buildingRooms.RemovedBuilding;
import zombie.characters.Faction;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoGameCharacter.Location;
import zombie.characters.animals.AnimalZone;
import zombie.characters.animals.AnimalZoneJunction;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.core.stash.StashSystem;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.gameStates.ChooseGameInfo;
import zombie.gameStates.ChooseGameInfo.Map;
import zombie.iso.RoomDef.RoomRect;
import zombie.iso.areas.DesignationZone;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.NonPvpZone;
import zombie.iso.areas.SafeHouse;
import zombie.iso.enums.MetaCellPresence;
import zombie.iso.objects.IsoMannequin.MannequinZone;
import zombie.iso.worldgen.WorldGenChunk;
import zombie.iso.worldgen.WorldGenParams;
import zombie.iso.worldgen.attachments.AttachmentsHandler;
import zombie.iso.worldgen.biomes.BiomeRegistry;
import zombie.iso.worldgen.blending.Blending;
import zombie.iso.worldgen.maps.BiomeMap;
import zombie.iso.worldgen.zombie.ZombieVoronoi;
import zombie.iso.worldgen.zones.WorldGenZone;
import zombie.iso.worldgen.zones.ZoneGenerator;
import zombie.iso.zones.RoomTone;
import zombie.iso.zones.VehicleZone;
import zombie.iso.zones.Zone;
import zombie.iso.zones.ZoneGeometryType;
import zombie.iso.zones.ZoneHandler;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.randomizedWorld.randomizedBuilding.RBBasic;
import zombie.util.BufferedRandomAccessFile;
import zombie.util.ByteBufferPooledObject;
import zombie.util.SharedStrings;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.io.RegExFilenameFilter;
import zombie.util.lambda.QuadConsumer;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.ClipperOffset;
import zombie.vehicles.ClipperOffset.EndType;
import zombie.vehicles.ClipperOffset.JoinType;

@UsedFromLua
public final class IsoMetaGrid {
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.IsoMetaGrid"); // loads inside IsoWorld's static init: no logging here
   }

   private static final int NUM_LOADER_THREADS = 8;
   public static final int ANY_Z = Integer.MAX_VALUE;
   public static ClipperOffset clipperOffset;
   public static ByteBuffer clipperBuffer;
   private static final ThreadLocal<ArrayList<Zone>> TL_ZoneList = ThreadLocal.withInitial(ArrayList::new);
   public static final ThreadLocal<Location> TL_Location = ThreadLocal.withInitial(Location::new);
   static Rectangle a = new Rectangle();
   static Rectangle b = new Rectangle();
   static ArrayList<RoomDef> roomChoices = new ArrayList<>(50);
   private final ArrayList<RoomDef> tempRooms = new ArrayList<>();
   private final ArrayList<Zone> tempZones1 = new ArrayList<>();
   private final ArrayList<Zone> tempZones2 = new ArrayList<>();
   private final IsoMetaGrid.MetaGridLoaderThread[] threads = new IsoMetaGrid.MetaGridLoaderThread[8];
   public int minX = 10000000;
   public int minY = 10000000;
   public int maxX = -10000000;
   public int maxY = -10000000;
   public int minNonProceduralX;
   public int minNonProceduralY;
   public int maxNonProceduralX;
   public int maxNonProceduralY;
   public final ArrayList<Zone> zones = new ArrayList<>();
   public final ArrayList<BuildingDef> buildings = new ArrayList<>();
   public final ArrayList<VehicleZone> vehiclesZones = new ArrayList<>();
   public final ZoneHandler<AnimalZone> animalZoneHandler = new ZoneHandler();
   private IsoMetaCell[][] grid;
   private final Set<IsoMetaCell> cellsToSave = new HashSet<>();
   public final ArrayList<IsoGameCharacter> metaCharacters = new ArrayList<>();
   final ArrayList<Vector2> highZombieList = new ArrayList<>();
   private int width;
   private int height;
   private final SharedStrings sharedStrings = new SharedStrings();
   private long createStartTime;
   private boolean loaded;
   private final ArrayList<RemovedBuilding> removedBuildings = new ArrayList<>();
   public static final int IDEAL_MAX_ZONE_SIZE = 512;

   public IsoMetaCell getCell(int x, int y) {
      return this.grid[x][y];
   }

   public IsoMetaCell getCellOrCreate(int x, int y) {
      if (!this.hasCell(x, y)) {
         IsoMetaCell metaCell = new IsoMetaCell(this.minX + x, this.minY + y);
         this.setCell(x, y, metaCell);
      }

      return this.getCell(x, y);
   }

   public void setCell(int x, int y, IsoMetaCell cell) {
      if (!Core.debug || cell == null || cell.getX() == this.minX + x && cell.getY() == this.minY + y) {
         this.grid[x][y] = cell;
      } else {
         throw new IllegalArgumentException("invalid IsoMetaCell coordinates");
      }
   }

   public boolean hasCell(int x, int y) {
      return this.grid[x][y] != null;
   }

   public int gridX() {
      return this.grid.length;
   }

   public int gridY() {
      return this.grid[0].length;
   }

   public void AddToMeta(IsoGameCharacter isoPlayer) {
      IsoWorld.instance.currentCell.Remove(isoPlayer);
      if (!this.metaCharacters.contains(isoPlayer)) {
         this.metaCharacters.add(isoPlayer);
      }
   }

   public void RemoveFromMeta(IsoPlayer isoPlayer) {
      this.metaCharacters.remove(isoPlayer);
      if (!IsoWorld.instance.currentCell.getObjectList().contains(isoPlayer)) {
         IsoWorld.instance.currentCell.getObjectList().add(isoPlayer);
      }
   }

   public int getMinX() {
      return this.minX;
   }

   public int getMinY() {
      return this.minY;
   }

   public int getMaxX() {
      return this.maxX;
   }

   public int getMaxY() {
      return this.maxY;
   }

   public Zone getZoneAt(int x, int y, int z) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x, y);
      return metaChunk != null ? metaChunk.getZoneAt(x, y, z) : null;
   }

   public ArrayList<Zone> getZonesAt(int x, int y, int z) {
      return this.getZonesAt(x, y, z, new ArrayList<>());
   }

   public ArrayList<Zone> getZonesAt(int x, int y, int z, ArrayList<Zone> result) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x, y);
      return metaChunk != null ? metaChunk.getZonesAt(x, y, z, result) : result;
   }

   public ArrayList<Zone> getZonesIntersecting(int x, int y, int z, int w, int h) {
      ArrayList<Zone> result = new ArrayList<>();
      return this.getZonesIntersecting(x, y, z, w, h, result);
   }

   public ArrayList<Zone> getZonesIntersecting(int x, int y, int z, int w, int h, ArrayList<Zone> result) {
      for (int yy = y / 256; yy <= (y + h) / 256; yy++) {
         for (int xx = x / 256; xx <= (x + w) / 256; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY && this.hasCell(xx - this.minX, yy - this.minY)) {
               this.getCell(xx - this.minX, yy - this.minY).getZonesIntersecting(x, y, z, w, h, result);
            }
         }
      }

      return result;
   }

   public Zone getZoneWithBoundsAndType(int x, int y, int z, int w, int h, String type) {
      ArrayList<Zone> zones = TL_ZoneList.get();
      zones.clear();
      this.getZonesIntersecting(x, y, z, w, h, zones);

      for (int i = 0; i < zones.size(); i++) {
         Zone zone = zones.get(i);
         if (zone.x == x && zone.y == y && zone.z == z && zone.w == w && zone.h == h && StringUtils.equalsIgnoreCase(zone.type, type)) {
            return zone;
         }
      }

      return null;
   }

   public VehicleZone getVehicleZoneAt(int x, int y, int z) {
      IsoMetaCell metaCell = this.getMetaGridFromTile(x, y);
      if (metaCell != null && !metaCell.vehicleZones.isEmpty()) {
         for (int i = 0; i < metaCell.vehicleZones.size(); i++) {
            VehicleZone zone = (VehicleZone)metaCell.vehicleZones.get(i);
            if (zone.contains(x, y, z)) {
               return zone;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public BuildingDef getBuildingAt(int x, int y) {
      for (int n = 0; n < this.buildings.size(); n++) {
         BuildingDef building = this.buildings.get(n);
         if (building.x <= x && building.y <= y && building.getW() > x - building.x && building.getH() > y - building.y) {
            return building;
         }
      }

      return null;
   }

   public BuildingDef getBuildingAt(int x, int y, int z) {
      RoomDef roomDef = this.getRoomAt(x, y, z);
      if (roomDef == null) {
         roomDef = this.getEmptyOutsideAt(x, y, z);
      }

      return roomDef == null ? null : roomDef.building;
   }

   public ArrayList<BuildingDef> getBuildings() {
      return this.buildings;
   }

   public ArrayList<RemovedBuilding> getRemovedBuildings() {
      return this.removedBuildings;
   }

   public BuildingDef getAssociatedBuildingAt(int x, int y) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x, y);
      if (metaChunk != null) {
         BuildingDef buildingDef = metaChunk.getAssociatedBuildingAt(x, y);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      int chunksPerWidth = 8;
      int xmod = PZMath.coordmodulo(x, 8);
      int ymod = PZMath.coordmodulo(y, 8);
      if (xmod == 7) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.E);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (xmod == 0) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.W);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (ymod == 0) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.N);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (xmod == 0 || ymod == 0) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.NW);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (xmod == 7 || ymod == 7) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.SE);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (xmod == 0 || ymod == 7) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.SW);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      if (xmod == 7 || ymod == 0) {
         BuildingDef buildingDef = this.getAssociatedBuildingAt(x, y, IsoDirections.NE);
         if (buildingDef != null) {
            return buildingDef;
         }
      }

      return null;
   }

   private BuildingDef getAssociatedBuildingAt(int x, int y, IsoDirections dir) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x + dir.dx(), y + dir.dy());
      return metaChunk == null ? null : metaChunk.getAssociatedBuildingAt(x, y);
   }

   public BuildingDef getBuildingAtRelax(int x, int y) {
      for (int n = 0; n < this.buildings.size(); n++) {
         BuildingDef zone = this.buildings.get(n);
         if (zone.x <= x + 1 && zone.y <= y + 1 && zone.getW() > x - zone.x - 1 && zone.getH() > y - zone.y - 1) {
            return zone;
         }
      }

      return null;
   }

   public RoomDef getRoomAt(int x, int y, int z) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x, y);
      return metaChunk != null ? metaChunk.getRoomAt(x, y, z) : null;
   }

   public RoomDef getEmptyOutsideAt(int x, int y, int z) {
      IsoMetaChunk metaChunk = this.getChunkDataFromTile(x, y);
      return metaChunk != null ? metaChunk.getEmptyOutsideAt(x, y, z) : null;
   }

   public RoomDef getRoomDefByID(long roomID) {
      int cellX = RoomID.getCellX(roomID);
      int cellY = RoomID.getCellY(roomID);
      IsoMetaCell metaCell = this.getCellData(cellX, cellY);
      return metaCell == null ? null : (RoomDef)metaCell.rooms.get(roomID);
   }

   public IsoRoom getRoomByID(long roomID) {
      int cellX = RoomID.getCellX(roomID);
      int cellY = RoomID.getCellY(roomID);
      IsoMetaCell metaCell = this.getCellData(cellX, cellY);
      if (metaCell == null) {
         return null;
      }

      RoomDef r = (RoomDef)metaCell.rooms.get(roomID);
      if (r == null) {
         return null;
      }

      if (!metaCell.isoRooms.containsKey(roomID)) {
         IsoRoom room = new IsoRoom();
         PZArrayUtil.addAll(room.rects, r.rects);
         room.roomDef = r.name;
         room.def = r;
         room.layer = r.level;
         IsoWorld.instance.currentCell.getRoomList().add(room);
         if (r.building == null) {
            r.building = new BuildingDef();
            r.building.id = BuildingID.makeID(cellX, cellY, this.buildings.size());
            r.building.rooms.add(r);
            r.building.CalculateBounds(new ArrayList());
            r.building.metaId = r.building.calculateMetaID(cellX, cellY);
            this.buildings.add(r.building);
         }

         long buildingID = r.building.id;
         metaCell.isoRooms.put(roomID, room);
         if (!metaCell.isoBuildings.containsKey(buildingID)) {
            room.building = new IsoBuilding();
            room.building.def = r.building;
            metaCell.isoBuildings.put(buildingID, room.building);
            room.building.CreateFrom(r.building, metaCell);
         } else {
            room.building = (IsoBuilding)metaCell.isoBuildings.get(buildingID);
            room.building.rooms.add(room);
         }

         return room;
      } else {
         return (IsoRoom)metaCell.isoRooms.get(roomID);
      }
   }

   public void getBuildingsIntersecting(int x, int y, int w, int h, ArrayList<BuildingDef> result) {
      for (int cy = y / 256; cy <= (y + this.height) / 256; cy++) {
         for (int cx = x / 256; cx <= (x + this.width) / 256; cx++) {
            if (cx >= this.minX && cx <= this.maxX && cy >= this.minY && cy <= this.maxY) {
               IsoMetaCell cell = this.getCell(cx - this.minX, cy - this.minY);
               if (cell != null) {
                  cell.getBuildingsIntersecting(x, y, w, h, result);
               }
            }
         }
      }
   }

   public void getRoomsIntersecting(int x, int y, int w, int h, ArrayList<RoomDef> roomDefs) {
      for (int cy = y / 256; cy <= (y + this.height) / 256; cy++) {
         for (int cx = x / 256; cx <= (x + this.width) / 256; cx++) {
            if (cx >= this.minX && cx <= this.maxX && cy >= this.minY && cy <= this.maxY) {
               IsoMetaCell cell = this.getCell(cx - this.minX, cy - this.minY);
               if (cell != null) {
                  cell.getRoomsIntersecting(x, y, w, h, roomDefs);
               }
            }
         }
      }
   }

   public int countRoomsIntersecting(int x, int y, int w, int h) {
      this.tempRooms.clear();

      for (int cy = y / 256; cy <= (y + this.height) / 256; cy++) {
         for (int cx = x / 256; cx <= (x + this.width) / 256; cx++) {
            if (cx >= this.minX && cx <= this.maxX && cy >= this.minY && cy <= this.maxY) {
               IsoMetaCell cell = this.getCell(cx - this.minX, cy - this.minY);
               if (cell != null) {
                  cell.getRoomsIntersecting(x, y, w, h, this.tempRooms);
               }
            }
         }
      }

      return this.tempRooms.size();
   }

   public int countNearbyBuildingsRooms(IsoPlayer isoPlayer) {
      int x = PZMath.fastfloor(isoPlayer.getX()) - 20;
      int y = PZMath.fastfloor(isoPlayer.getY()) - 20;
      int w = 40;
      int h = 40;
      return this.countRoomsIntersecting(x, y, 40, 40);
   }

   private boolean isInside(Zone r1, BuildingDef r2) {
      a.x = r1.x;
      a.y = r1.y;
      a.width = r1.w;
      a.height = r1.h;
      b.x = r2.x;
      b.y = r2.y;
      b.width = r2.getW();
      b.height = r2.getH();
      return a.contains(b);
   }

   private boolean isAdjacent(Zone r1, Zone r2) {
      if (r1 == r2) {
         return false;
      }

      a.x = r1.x;
      a.y = r1.y;
      a.width = r1.w;
      a.height = r1.h;
      b.x = r2.x;
      b.y = r2.y;
      b.width = r2.w;
      b.height = r2.h;
      a.x--;
      a.y--;
      a.width += 2;
      a.height += 2;
      b.x--;
      b.y--;
      b.width += 2;
      b.height += 2;
      return a.intersects(b);
   }

   public Zone registerZone(String name, String type, int x, int y, int z, int width, int height) {
      return this.registerZone(name, type, x, y, z, width, height, ZoneGeometryType.INVALID, null, 0);
   }

   public Zone registerZone(
      String name, String type, int x, int y, int z, int width, int height, ZoneGeometryType geometryType, TIntArrayList points, int polylineWidth
   ) {
      name = this.sharedStrings.get(name);
      type = this.sharedStrings.get(type);
      Zone newZone = new Zone(name, type, x, y, z, width, height);
      newZone.geometryType = geometryType;
      if (points != null) {
         newZone.points.addAll(points);
         newZone.polylineWidth = polylineWidth;
      }

      newZone.isPreferredZoneForSquare = Zone.isPreferredZoneForSquare(type);
      if (x >= this.minX * 256 - 100
         && y >= this.minY * 256 - 100
         && x + width <= (this.maxX + 1) * 256 + 100
         && y + height <= (this.maxY + 1) * 256 + 100
         && z >= -32
         && z <= 31
         && width <= 1202
         && height <= 1202) {
         this.addZone(newZone);
         return newZone;
      } else {
         DebugLog.log("111ERROR: not adding suspicious zone \"" + name + "\" \"" + type + "\" " + x + "," + y + "," + z + " " + width + "x" + height);
         return newZone;
      }
   }

   public Zone registerZone(Zone zone) {
      if (zone.x >= this.minX * 256 - 100
         && zone.y >= this.minY * 256 - 100
         && zone.x + this.width <= (this.maxX + 1) * 256 + 100
         && zone.y + this.height <= (this.maxY + 1) * 256 + 100
         && zone.z >= -32
         && zone.z <= 31
         && zone.w <= 1202
         && zone.h <= 1202) {
         this.addZone(zone);
         return zone;
      } else {
         DebugLog.log(
            "111ERROR: not adding suspicious zone \""
               + zone.name
               + "\" \""
               + zone.type
               + "\" "
               + zone.x
               + ","
               + zone.y
               + ","
               + zone.z
               + " "
               + zone.w
               + "x"
               + zone.h
         );
         return zone;
      }
   }

   public Zone registerGeometryZone(String name, String type, int z, String geometry, KahluaTable pointsTable, KahluaTable properties) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      TIntArrayList points = new TIntArrayList(pointsTable.len());

      for (int i = 0; i < pointsTable.len(); i += 2) {
         Object xObj = pointsTable.rawget(i + 1);
         Object yObj = pointsTable.rawget(i + 2);
         int x = ((Double)xObj).intValue();
         int y = ((Double)yObj).intValue();
         points.add(x);
         points.add(y);
         minX = Math.min(minX, x);
         minY = Math.min(minY, y);
         maxX = Math.max(maxX, x);
         maxY = Math.max(maxY, y);
      }
      ZoneGeometryType geometryType = switch (geometry) {
         case "point" -> ZoneGeometryType.Point;
         case "polygon" -> ZoneGeometryType.Polygon;
         case "polyline" -> ZoneGeometryType.Polyline;
         default -> throw new IllegalArgumentException("unknown zone geometry type");
      };
      Double widthObj = geometryType == ZoneGeometryType.Polyline && properties != null
         ? (Double)Type.tryCastTo(properties.rawget("LineWidth"), Double.class)
         : null;
      return this.registerGeometryZone(name, type, z, geometryType, points, properties, widthObj == null ? 0 : widthObj.intValue());
   }

   public Zone registerGeometryZone(String name, String type, int z, ZoneGeometryType geometryType, TIntArrayList points, KahluaTable properties, int width) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      if (width > 0) {
         int[] bounds = new int[4];
         this.calculatePolylineOutlineBounds(points, width, bounds);
         minX = bounds[0];
         minY = bounds[1];
         maxX = bounds[2];
         maxY = bounds[3];
      }

      if (type.equals("Animal")) {
         Zone zone = this.registerAnimalZone(name, type, minX, minY, z, maxX - minX + 1, maxY - minY + 1, properties);
         if (zone != null) {
            zone.geometryType = geometryType;
            zone.points.addAll(points);
            zone.polylineWidth = width;
         }

         return zone;
      } else if (type.equals("Vehicle") || type.equals("ParkingStall")) {
         Zone zone = this.registerVehiclesZone(name, type, minX, minY, z, maxX - minX + 1, maxY - minY + 1, properties);
         if (zone != null) {
            zone.geometryType = geometryType;
            zone.points.addAll(points);
            zone.polylineWidth = width;
         }

         return zone;
      } else if (type.equals("WorldGen")) {
         Zone zone = this.registerWorldGenZone(name, type, minX, minY, z, maxX - minX + 1, maxY - minY + 1, properties);
         if (zone != null) {
            zone.geometryType = geometryType;
            zone.points.addAll(points);
            zone.polylineWidth = width;
         }

         return zone;
      } else {
         Zone zone = this.registerZone(name, type, minX, minY, z, maxX - minX + 1, maxY - minY + 1, geometryType, points, width);
         points.clear();
         return zone;
      }
   }

   private void calculatePolylineOutlineBounds(TIntArrayList points, int polylineWidth, int[] bounds) {
      if (clipperOffset == null) {
         clipperOffset = new ClipperOffset();
         clipperBuffer = ByteBuffer.allocateDirect(3072);
      }

      clipperOffset.clear();
      clipperBuffer.clear();
      float dxy = polylineWidth % 2 == 0 ? 0.0F : 0.5F;

      for (int j = 0; j < points.size(); j += 2) {
         int x1 = points.get(j);
         int y1 = points.get(j + 1);
         clipperBuffer.putFloat(x1 + dxy);
         clipperBuffer.putFloat(y1 + dxy);
      }

      clipperBuffer.flip();
      clipperOffset.addPath(points.size() / 2, clipperBuffer, JoinType.Miter.ordinal(), EndType.Butt.ordinal());
      clipperOffset.execute(polylineWidth / 2.0F);
      int numPolys = clipperOffset.getPolygonCount();
      if (numPolys < 1) {
         DebugType.General.warn("Failed to generate polyline outline");
      } else {
         clipperBuffer.clear();
         clipperOffset.getPolygon(0, clipperBuffer);
         short pointCount = clipperBuffer.getShort();
         float minX = Float.MAX_VALUE;
         float minY = Float.MAX_VALUE;
         float maxX = -Float.MAX_VALUE;
         float maxY = -Float.MAX_VALUE;

         for (int k = 0; k < pointCount; k++) {
            float x = clipperBuffer.getFloat();
            float y = clipperBuffer.getFloat();
            minX = PZMath.min(minX, x);
            minY = PZMath.min(minY, y);
            maxX = PZMath.max(maxX, x);
            maxY = PZMath.max(maxY, y);
         }

         bounds[0] = (int)PZMath.floor(minX);
         bounds[1] = (int)PZMath.floor(minY);
         bounds[2] = (int)PZMath.ceil(maxX);
         bounds[3] = (int)PZMath.ceil(maxY);
      }
   }

   @Deprecated
   public Zone registerZoneNoOverlap(String name, String type, int x, int y, int z, int width, int height) {
      if (x >= this.minX * 256 - 100
         && y >= this.minY * 256 - 100
         && x + width <= (this.maxX + 1) * 256 + 100
         && y + height <= (this.maxY + 1) * 256 + 100
         && z >= 0
         && z < 8
         && width <= 601
         && height <= 601) {
         return this.registerZone(name, type, x, y, z, width, height);
      }

      DebugLog.log("YYYYERROR: not adding suspicious zone \"" + name + "\" \"" + type + "\" " + x + "," + y + "," + z + " " + width + "x" + height);
      return null;
   }

   public void addZone(Zone zone) {
      this.zones.add(zone);

      for (int yy = zone.y / 256; yy <= (zone.y + zone.h) / 256; yy++) {
         for (int xx = zone.x / 256; xx <= (zone.x + zone.w) / 256; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY) {
               this.getCellOrCreate(xx - this.minX, yy - this.minY).addZone(zone, xx * 256, yy * 256);
            }
         }
      }
   }

   public void removeZone(Zone zone) {
      this.zones.remove(zone);

      for (int yy = zone.y / 256; yy <= (zone.y + zone.h) / 256; yy++) {
         for (int xx = zone.x / 256; xx <= (zone.x + zone.w) / 256; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY && this.hasCell(xx - this.minX, yy - this.minY)) {
               this.getCell(xx - this.minX, yy - this.minY).removeZone(zone);
            }
         }
      }
   }

   private void removeZonesForCell(int cell300X, int cell300Y) {
      ArrayList<Zone> zones = this.tempZones1;
      zones.clear();
      this.getZonesIntersecting(cell300X * 300, cell300Y * 300, Integer.MAX_VALUE, 300, 300, zones);

      for (int i = 0; i < zones.size(); i++) {
         Zone cut = zones.get(i);
         ArrayList<Zone> diff = this.tempZones2;
         if (cut.difference(cell300X * 300, cell300Y * 300, Integer.MAX_VALUE, 300, 300, diff)) {
            this.removeZone(cut);

            for (int j = 0; j < diff.size(); j++) {
               this.addZone(diff.get(j));
            }
         }
      }

      int minX = PZMath.coorddivision(cell300X * 300, 256);
      int minY = PZMath.coorddivision(cell300Y * 300, 256);
      int maxX = PZMath.coorddivision((cell300X + 1) * 300, 256);
      int maxY = PZMath.coorddivision((cell300Y + 1) * 300, 256);

      for (int yy = minY; yy <= maxY; yy++) {
         for (int xx = minX; xx <= maxX; xx++) {
            IsoMetaCell metaCell = this.getCellData(cell300X, cell300Y);
            if (metaCell != null) {
               this.removeZonesOverlapping(metaCell.vehicleZones, cell300X, cell300Y);
               this.removeZonesOverlapping(metaCell.mannequinZones, cell300X, cell300Y);
               this.removeZonesOverlapping(metaCell.worldGenZones, cell300X, cell300Y);
            }
         }
      }
   }

   private void removeZonesOverlapping(List<? extends Zone> zones, int cell300X, int cell300Y) {
      if (zones != null && !zones.isEmpty()) {
         for (int i = zones.size() - 1; i >= 0; i--) {
            Zone zone = zones.get(i);
            if (zone.intersects(cell300X * 300, cell300Y * 300, Integer.MAX_VALUE, 300, 300)) {
               zones.remove(i);
            }
         }
      }
   }

   public void removeZonesForLotDirectory(String lotDir) {
      if (!this.zones.isEmpty()) {
         Optional<MapFiles> mapFilesOptional = IsoLot.MapFiles.stream().filter(mapFiles1 -> mapFiles1.mapDirectoryName.equalsIgnoreCase(lotDir)).findFirst();
         if (!mapFilesOptional.isEmpty()) {
            MapFiles mapFiles = mapFilesOptional.get();

            for (int cellY = mapFiles.minCell300Y; cellY <= mapFiles.maxCell300Y; cellY++) {
               for (int cellX = mapFiles.minCell300X; cellX <= mapFiles.maxCell300X; cellX++) {
                  if (mapFiles.hasCell300(cellX, cellY)) {
                     this.removeZonesForCell(cellX, cellY);
                  }
               }
            }
         }
      }
   }

   public void processZones() {
      int maxZone = 0;

      for (int x = this.minX; x <= this.maxX; x++) {
         for (int y = this.minY; y <= this.maxY; y++) {
            if (this.hasCell(x - this.minX, y - this.minY)) {
               IsoMetaCell cell = this.getCell(x - this.minX, y - this.minY);

               for (int cy = 0; cy < 32; cy++) {
                  for (int cx = 0; cx < 32; cx++) {
                     if (cell.hasChunk(cx, cy)) {
                        IsoMetaChunk chunk = cell.getChunk(cx, cy);
                        chunk.compactZoneArray();
                        chunk.compactRoomDefArray();
                        maxZone = Math.max(maxZone, chunk.getZonesSize());
                     }
                  }
               }
            }
         }
      }

      DebugLog.log("Max #ZONES on one chunk is " + maxZone);
   }

   public Zone registerVehiclesZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      if (!type.equals("Vehicle") && !type.equals("ParkingStall")) {
         return null;
      }

      name = this.sharedStrings.get(name);
      type = this.sharedStrings.get(type);
      VehicleZone newZone = new VehicleZone(name, type, x, y, z, width, height, properties);
      this.vehiclesZones.add(newZone);
      int cellMaxX = (int)Math.ceil((newZone.x + newZone.w) / 256.0F);
      int cellMaxY = (int)Math.ceil((newZone.y + newZone.h) / 256.0F);

      for (int yy = newZone.y / 256; yy < cellMaxY; yy++) {
         for (int xx = newZone.x / 256; xx < cellMaxX; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY) {
               this.getCellOrCreate(xx - this.minX, yy - this.minY).vehicleZones.add(newZone);
            }
         }
      }

      return newZone;
   }

   public Zone registerWorldGenZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      if (!type.equals("WorldGen")) {
         return null;
      }

      name = this.sharedStrings.get(name);
      type = this.sharedStrings.get(type);
      WorldGenZone newZone = new WorldGenZone(name, type, x, y, z, width, height, properties);
      int cellMaxX = (int)Math.ceil((newZone.x + newZone.w) / 256.0F);
      int cellMaxY = (int)Math.ceil((newZone.y + newZone.h) / 256.0F);

      for (int yy = newZone.y / 256; yy < cellMaxY; yy++) {
         for (int xx = newZone.x / 256; xx < cellMaxX; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY) {
               IsoMetaCell metaCell = this.getCellOrCreate(xx - this.minX, yy - this.minY);
               if (metaCell.worldGenZones == null) {
                  metaCell.worldGenZones = new ArrayList();
               }

               metaCell.worldGenZones.add(newZone);
            }
         }
      }

      return newZone;
   }

   // pzopt: retain all 32 bits of each stock duplicate-comparison component.
   private record PzoptVehicleZoneKey(int x, int y, int width, int height) { }

   public void checkVehiclesZones() {
      if (pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled()) {
         // pzopt: stock compares every zone with every earlier one (O(n^2)) and builds the debug string for each
         // duplicate whether or not DebugType.Vehicle logs; same outcome: the first zone of each (x, y, w, h) survives
         java.util.HashSet<PzoptVehicleZoneKey> seen = new java.util.HashSet<>(this.vehiclesZones.size() * 2);
         int removed = 0;
         int n = 0;

         while (n < this.vehiclesZones.size()) {
            Zone a = (Zone)this.vehiclesZones.get(n);
            PzoptVehicleZoneKey key = new PzoptVehicleZoneKey(a.getX(), a.getY(), a.w, a.h);
            if (seen.add(key)) {
               n++;
            } else {
               if (DebugType.Vehicle.isEnabled()) {
                  DebugType.Vehicle.debugln("checkVehiclesZones: ERROR! Zone '" + a.name + "':'" + a.type + "' (" + a.x + ", " + a.y + ") duplicate");
               }

               this.vehiclesZones.remove(n);
               removed++;
            }
         }

         pzopt.Log.info("checkVehiclesZones: " + this.vehiclesZones.size() + " vehicle zones, " + removed + " duplicates removed");
         return;
      }

      int i = 0;

      while (i < this.vehiclesZones.size()) {
         boolean isUnique = true;

         for (int k = 0; k < i; k++) {
            Zone a = (Zone)this.vehiclesZones.get(i);
            Zone b = (Zone)this.vehiclesZones.get(k);
            if (a.getX() == b.getX() && a.getY() == b.getY() && a.h == b.h && a.w == b.w) {
               isUnique = false;
               DebugType.Vehicle
                  .debugln(
                     "checkVehiclesZones: ERROR! Zone '"
                        + a.name
                        + "':'"
                        + a.type
                        + "' ("
                        + a.x
                        + ", "
                        + a.y
                        + ") duplicate with Zone '"
                        + b.name
                        + "':'"
                        + b.type
                        + "' ("
                        + b.x
                        + ", "
                        + b.y
                        + ")"
                  );
               break;
            }
         }

         if (isUnique) {
            i++;
         } else {
            this.vehiclesZones.remove(i);
         }
      }
   }

   public Zone registerAnimalZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      if (!"Animal".equals(type)) {
         return null;
      }

      name = this.sharedStrings.get(name);
      type = this.sharedStrings.get(type);
      return this.registerAnimalZone(new AnimalZone(name, type, x, y, z, width, height, properties));
   }

   public Zone registerAnimalZone(AnimalZone animalZone) {
      return this.registerAnimalZone(animalZone, true);
   }

   public Zone registerAnimalZone(AnimalZone animalZone, boolean bHotSave) {
      this.animalZoneHandler.addZone(animalZone);
      int cellMaxX = (int)Math.ceil((animalZone.x + animalZone.w) / 256.0F);
      int cellMaxY = (int)Math.ceil((animalZone.y + animalZone.h) / 256.0F);

      for (int yy = animalZone.y / 256; yy < cellMaxY; yy++) {
         for (int xx = animalZone.x / 256; xx < cellMaxX; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY) {
               IsoMetaCell cell = this.getCellOrCreate(xx - this.minX, yy - this.minY);
               cell.addAnimalZone(animalZone);
               if (bHotSave) {
                  this.addCellToSave(cell);
               }
            }
         }
      }

      return animalZone;
   }

   public Zone registerMannequinZone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      if (!"Mannequin".equals(type)) {
         return null;
      }

      name = this.sharedStrings.get(name);
      type = this.sharedStrings.get(type);
      MannequinZone newZone = new MannequinZone(name, type, x, y, z, width, height, properties);
      int cellMaxX = (int)Math.ceil((newZone.x + newZone.w) / 256.0F);
      int cellMaxY = (int)Math.ceil((newZone.y + newZone.h) / 256.0F);

      for (int yy = newZone.y / 256; yy < cellMaxY; yy++) {
         for (int xx = newZone.x / 256; xx < cellMaxX; xx++) {
            if (xx >= this.minX && xx <= this.maxX && yy >= this.minY && yy <= this.maxY) {
               this.getCellOrCreate(xx - this.minX, yy - this.minY).mannequinZones.add(newZone);
            }
         }
      }

      return newZone;
   }

   public void registerRoomTone(String name, String type, int x, int y, int z, int width, int height, KahluaTable properties) {
      if ("RoomTone".equals(type)) {
         IsoMetaCell cell = this.getCellData(x / 256, y / 256);
         RoomTone roomTone = new RoomTone();
         roomTone.x = x;
         roomTone.y = y;
         roomTone.z = z;
         roomTone.enumValue = properties.getString("RoomTone");
         roomTone.entireBuilding = Boolean.TRUE.equals(properties.rawget("EntireBuilding"));
         cell.roomTones.add(roomTone);
      }
   }

   public boolean isZoneAbove(Zone zone1, Zone zone2, int x, int y, int z) {
      if (zone1 != null && zone1 != zone2) {
         ArrayList<Zone> zones = TL_ZoneList.get();
         zones.clear();
         this.getZonesAt(x, y, z, zones);
         return zones.indexOf(zone1) > zones.indexOf(zone2);
      } else {
         return false;
      }
   }

   public void save(ByteBuffer output) {
      this.savePart(output, 0, false);
      this.savePart(output, 1, false);
   }


   public void savePart(ByteBuffer output, int part, boolean fromServer) {
      if (part == 0) {
         output.put((byte)77);
         output.put((byte)69);
         output.put((byte)84);
         output.put((byte)65);
         output.putInt(249);
         output.putInt(this.minX);
         output.putInt(this.minY);
         output.putInt(this.maxX);
         output.putInt(this.maxY);

         for (int x = 0; x < this.gridX(); x++) {
            for (int y = 0; y < this.gridY(); y++) {
               IsoMetaCell cell = this.grid[x][y];
               int numRooms = 0;
               if (cell != null && cell.info != null) {
                  numRooms = cell.getRoomCount(true);
               }

               output.putInt(numRooms);
               if (cell != null && cell.info != null) {
                  for (RoomDef roomDef : cell.roomList) {
                     if (!roomDef.userDefined) {
                        output.putLong(roomDef.metaId);
                        short flags = 0;
                        if (roomDef.explored) {
                           flags = (short)(flags | 1);
                        }

                        if (roomDef.lightsActive) {
                           flags = (short)(flags | 2);
                        }

                        if (roomDef.doneSpawn) {
                           flags = (short)(flags | 4);
                        }

                        if (roomDef.isRoofFixed()) {
                           flags = (short)(flags | 8);
                        }

                        output.putShort(flags);
                     }
                  }
               }

               if (cell != null && cell.info != null) {
                  output.putInt(cell.getBuildingCount(true));
               } else {
                  output.putInt(0);
               }

               if (cell != null && cell.info != null) {
                  for (BuildingDef entry : cell.buildings) {
                     if (!entry.isUserDefined()) {
                        output.putLong(entry.metaId);
                        output.put((byte)(entry.alarmed ? 1 : 0));
                        output.putInt(entry.getKeyId());
                        output.put((byte)(entry.seen ? 1 : 0));
                        output.put((byte)(entry.isHasBeenVisited() ? 1 : 0));
                        output.putInt(entry.lootRespawnHour);
                        output.putInt(entry.alarmDecay);
                     }
                  }
               }
            }
         }
      } else {
         output.putInt(SafeHouse.getSafehouseList().size());

         for (int n = 0; n < SafeHouse.getSafehouseList().size(); n++) {
            ((SafeHouse)SafeHouse.getSafehouseList().get(n)).save(output);
         }

         output.putInt(NonPvpZone.getAllZones().size());

         for (int n = 0; n < NonPvpZone.getAllZones().size(); n++) {
            ((NonPvpZone)NonPvpZone.getAllZones().get(n)).save(output);
         }

         output.putInt(Faction.getFactions().size());

         for (int n = 0; n < Faction.getFactions().size(); n++) {
            ((Faction)Faction.getFactions().get(n)).save(output);
         }

         output.putInt(DesignationZone.allZones.size());

         for (int n = 0; n < DesignationZone.allZones.size(); n++) {
            ((DesignationZone)DesignationZone.allZones.get(n)).save(output);
         }

         if (GameServer.server) {
            int position = output.position();
            output.putInt(0);
            StashSystem.save(output);
            output.putInt(position, output.position());
         } else if (!GameClient.client) {
            StashSystem.save(output);
         }

         output.putInt(RBBasic.getUniqueRDSSpawned().size());

         for (int n = 0; n < RBBasic.getUniqueRDSSpawned().size(); n++) {
            GameWindow.WriteString(output, (String)RBBasic.getUniqueRDSSpawned().get(n));
         }
      }
   }

   public void load() {
      File file = ZomboidFileSystem.instance.getFileInCurrentSave("map_meta.bin");

      try (
         FileInputStream fis = new FileInputStream(file);
         BufferedInputStream bis = new BufferedInputStream(fis);
      ) {
         synchronized (SliceY.SliceBufferLock) {
            SliceY.SliceBuffer.clear();
            int numBytes = bis.read(SliceY.SliceBuffer.array());
            SliceY.SliceBuffer.limit(numBytes);
            this.load(SliceY.SliceBuffer);
         }

         this.loaded = true;

         for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
               IsoMetaCell metaCell = this.getCellDataAbs(x, y);
               if (metaCell != null && metaCell.info != null) {
                  metaCell.buildingByMetaId.compact();
                  metaCell.roomByMetaId.compact();
               }
            }
         }
      } catch (FileNotFoundException var12) {
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
   }

   public void load(ByteBuffer input) {
      input.mark();
      byte b1 = input.get();
      byte b2 = input.get();
      byte b3 = input.get();
      byte b4 = input.get();
      int worldVersion = input.getInt();
      int x1 = input.getInt();
      int y1 = input.getInt();
      int x2 = input.getInt();
      int y2 = input.getInt();
      int w = x2 - x1 + 1;
      int h = y2 - y1 + 1;
      if (w != this.gridX() || h != this.gridY()) {
         DebugLog.log("map_meta.bin world size (" + w + "x" + h + ") does not match the current map size (" + this.gridX() + "x" + this.gridY() + ")");
      }

      int totalBuildings = 0;
      int totalAlarmed = 0;

      for (int x = x1; x <= x2; x++) {
         for (int y = y1; y <= y2; y++) {
            IsoMetaCell cell = this.getCellData(x, y);
            int numRooms = input.getInt();

            for (int n = 0; n < numRooms; n++) {
               long metaID = input.getLong();
               short flags = input.getShort();
               boolean bExplored = (flags & 1) != 0;
               boolean bLightsActive = (flags & 2) != 0;
               boolean bDoneSpawn = (flags & 4) != 0;
               boolean bRoofFixed = (flags & 8) != 0;
               if (cell != null && cell.info != null) {
                  RoomDef roomDef = (RoomDef)cell.roomByMetaId.get(metaID);
                  if (roomDef != null) {
                     roomDef.setExplored(bExplored);
                     roomDef.lightsActive = bLightsActive;
                     roomDef.doneSpawn = bDoneSpawn;
                     roomDef.setRoofFixed(bRoofFixed);
                  } else {
                     DebugType.General.error("invalid room metaID #" + metaID + " in cell " + x + "," + y + " while reading map_meta.bin");
                  }
               }
            }

            int numBuildings = input.getInt();
            totalBuildings += numBuildings;

            for (int n = 0; n < numBuildings; n++) {
               long metaID = input.getLong();
               boolean bAlarmed = input.get() != 0;
               int keyId = input.getInt();
               boolean seen = input.get() != 0;
               boolean hasBeenVisited = input.get() != 0;
               int lootRespawnHour = input.getInt();
               int bAlarmDecay = worldVersion >= 201 ? input.getInt() : 0;
               if (cell != null && cell.info != null) {
                  BuildingDef def = (BuildingDef)cell.buildingByMetaId.get(metaID);
                  if (def != null) {
                     if (bAlarmed) {
                        totalAlarmed++;
                     }

                     def.alarmed = bAlarmed;
                     def.setKeyId(keyId);
                     def.seen = seen;
                     def.hasBeenVisited = hasBeenVisited;
                     def.lootRespawnHour = lootRespawnHour;
                     def.alarmDecay = bAlarmDecay;
                  } else {
                     DebugType.General.error("invalid building metaID #" + metaID + " in cell " + x + "," + y + " while reading map_meta.bin");
                  }
               }
            }
         }
      }

      SafeHouse.clearSafehouseList();
      int nSafehouse = input.getInt();

      for (int n = 0; n < nSafehouse; n++) {
         SafeHouse.load(input, worldVersion);
      }

      NonPvpZone.nonPvpZoneList.clear();
      int nZone = input.getInt();

      for (int n = 0; n < nZone; n++) {
         NonPvpZone zone = new NonPvpZone();
         zone.load(input, worldVersion);
         NonPvpZone.getAllZones().add(zone);
      }

      Faction.factions = new ArrayList();
      int nFaction = input.getInt();

      for (int n = 0; n < nFaction; n++) {
         Faction faction = new Faction();
         faction.load(input, worldVersion);
         Faction.getFactions().add(faction);
      }

      int nDZone = input.getInt();

      for (int n = 0; n < nDZone; n++) {
         DesignationZone.load(input, worldVersion);
      }

      if (GameServer.server) {
         int position = input.getInt();
         StashSystem.load(input, worldVersion);
      } else if (GameClient.client) {
         int position = input.getInt();
         input.position(position);
      } else {
         StashSystem.load(input, worldVersion);
      }

      ArrayList<String> newlist = RBBasic.getUniqueRDSSpawned();
      newlist.clear();
      int numb = input.getInt();

      for (int n = 0; n < numb; n++) {
         newlist.add(GameWindow.ReadString(input));
      }
   }

   public int getWidth() {
      return this.width;
   }

   public int getHeight() {
      return this.height;
   }

   public boolean wasLoaded() {
      return this.loaded;
   }

   public IsoMetaCell getCellData(int x, int y) {
      return x - this.minX >= 0 && y - this.minY >= 0 && x - this.minX < this.width && y - this.minY < this.height
         ? this.getCell(x - this.minX, y - this.minY)
         : null;
   }

   public MetaCellPresence hasCellData(int x, int y) {
      if (x - this.minX >= 0 && y - this.minY >= 0 && x - this.minX < this.width && y - this.minY < this.height) {
         return this.hasCell(x - this.minX, y - this.minY) ? MetaCellPresence.LOADED : MetaCellPresence.NOT_LOADED;
      } else {
         return MetaCellPresence.OUT_OF_BOUNDS;
      }
   }

   public void setCellData(int x, int y, IsoMetaCell cell) {
      if (x - this.minX >= 0 && y - this.minY >= 0 && x - this.minX < this.width && y - this.minY < this.height) {
         this.setCell(x - this.minX, y - this.minY, cell);
      }
   }

   public IsoMetaCell getCellDataAbs(int x, int y) {
      return this.getCell(x, y);
   }

   public IsoMetaCell getCurrentCellData() {
      int wX = IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldX;
      int wY = IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldY;
      float fwx = wX;
      float fwy = wY;
      fwx /= 32.0F;
      fwy /= 32.0F;
      if (fwx < 0.0F) {
         fwx = PZMath.fastfloor(fwx - 1.0F);
      }

      if (fwy < 0.0F) {
         fwy = PZMath.fastfloor(fwy - 1.0F);
      }

      wX = PZMath.fastfloor(fwx);
      wY = PZMath.fastfloor(fwy);
      return this.getCellData(wX, wY);
   }

   public IsoMetaCell getMetaGridFromTile(int wx, int wy) {
      int wX = wx / 256;
      int wY = wy / 256;
      return this.getCellData(wX, wY);
   }

   public IsoMetaChunk getCurrentChunkData() {
      int wX = IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldX;
      int wY = IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldY;
      float fwx = wX;
      float fwy = wY;
      fwx /= 32.0F;
      fwy /= 32.0F;
      if (fwx < 0.0F) {
         fwx = PZMath.fastfloor(fwx) - 1;
      }

      if (fwy < 0.0F) {
         fwy = PZMath.fastfloor(fwy) - 1;
      }

      wX = PZMath.fastfloor(fwx);
      wY = PZMath.fastfloor(fwy);
      return this.getCellData(wX, wY)
         .getChunk(
            IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldX - wX * 32,
            IsoWorld.instance.currentCell.chunkMap[IsoPlayer.getPlayerIndex()].worldY - wY * 32
         );
   }

   public IsoMetaChunk getChunkData(int chunkX, int chunkY) {
      int cellX = PZMath.fastfloor(chunkX / 32.0F);
      int cellY = PZMath.fastfloor(chunkY / 32.0F);
      IsoMetaCell metaCell = this.getCellData(cellX, cellY);
      return metaCell == null ? null : metaCell.getChunk(chunkX - cellX * 32, chunkY - cellY * 32);
   }

   public IsoMetaChunk getChunkDataFromTile(int x, int y) {
      int chunkX = PZMath.fastfloor(x / 8.0F);
      int chunkY = PZMath.fastfloor(y / 8.0F);
      return this.getChunkData(chunkX, chunkY);
   }

   public boolean isValidSquare(int x, int y) {
      if (x < this.minX * 256) {
         return false;
      } else if (x >= (this.maxX + 1) * 256) {
         return false;
      } else {
         return y < this.minY * 256 ? false : y < (this.maxY + 1) * 256;
      }
   }

   public boolean isValidChunk(int wx, int wy) {
      wx *= 8;
      wy *= 8;
      if (wx < this.minX * 256) {
         return false;
      } else if (wx >= (this.maxX + 1) * 256) {
         return false;
      } else {
         return wy < this.minY * 256 ? false : wy < (this.maxY + 1) * 256;
      }
   }

   public void Create() {
      if (!this.loaded) {
         this.CreateStep1();
         this.CreateStep2();
      }
   }

   public void CreateStep1() {
      this.minX = 10000000;
      this.minY = 10000000;
      this.maxX = -10000000;
      this.maxY = -10000000;
      IsoLot.InfoHeaders.clear();
      IsoLot.InfoHeaderNames.clear();
      IsoLot.InfoFileNames.clear();
      IsoLot.MapFiles.clear();
      long start = System.currentTimeMillis();
      DebugLog.log("IsoMetaGrid.Create: begin scanning directories");
      ArrayList<String> lotDirs = this.getLotDirectories();
      DebugLog.log("Looking in these map folders:");

      for (int i = 0; i < lotDirs.size(); i++) {
         String lotDir = lotDirs.get(i);
         String path = ZomboidFileSystem.instance.getDirectoryString("media/maps/" + lotDir + "/");
         File file = new File(path);
         if (!file.isDirectory()) {
            DebugLog.log("    skipping non-existent map folder " + path);
         } else {
            MapFiles mapFiles = new MapFiles(lotDir, path, file.getAbsolutePath(), i);
            IsoLot.MapFiles.add(mapFiles);
            DebugType.DetailedInfo.trace("    " + mapFiles.mapDirectoryAbsolutePath);
         }
      }

      DebugLog.log("<End of map-folders list>");

      for (MapFiles mapFiles : IsoLot.MapFiles) {
         if (mapFiles.load()) {
            mapFiles.postLoad();
         }
      }

      for (int i = IsoLot.MapFiles.size() - 1; i >= 0; i--) {
         MapFiles mapFiles = (MapFiles)IsoLot.MapFiles.get(i);
         IsoLot.InfoFileNames.putAll(mapFiles.infoFileNames);
         IsoLot.InfoFileModded.putAll(mapFiles.infoFileModded);
         IsoLot.InfoHeaders.putAll(mapFiles.infoHeaders);
         IsoLot.InfoHeaderNames.removeAll(mapFiles.infoHeaderNames);
         IsoLot.InfoHeaderNames.addAll(mapFiles.infoHeaderNames);
         this.minX = PZMath.min(this.minX, mapFiles.minX);
         this.minY = PZMath.min(this.minY, mapFiles.minY);
         this.maxX = PZMath.max(this.maxX, mapFiles.maxX);
         this.maxY = PZMath.max(this.maxY, mapFiles.maxY);
      }

      if (this.minX > this.maxX) {
         this.minX = this.minY = 0;
         this.maxX = this.maxY = 1;
      }

      this.minNonProceduralX = this.minX;
      this.minNonProceduralY = this.minY;
      this.maxNonProceduralX = this.maxX;
      this.maxNonProceduralY = this.maxY;
      this.minX = Math.min(this.minX, WorldGenParams.INSTANCE.getMinXCell());
      this.minY = Math.min(this.minY, WorldGenParams.INSTANCE.getMinYCell());
      this.maxX = Math.max(this.maxX, WorldGenParams.INSTANCE.getMaxXCell());
      this.maxY = Math.max(this.maxY, WorldGenParams.INSTANCE.getMaxYCell());
      DebugLog.log("IsoMetaGrid.Create: X: [ " + this.minX + " " + this.maxX + " ], Y: [ " + this.minY + " " + this.maxY + " ]");
      DebugLog.log("World seed: " + WorldGenParams.INSTANCE.getSeedString() + " " + WorldGenParams.INSTANCE.getSeed());
      if (this.maxX >= this.minX && this.maxY >= this.minY) {
         this.grid = new IsoMetaCell[this.maxX - this.minX + 1][this.maxY - this.minY + 1];
         this.width = this.maxX - this.minX + 1;
         this.height = this.maxY - this.minY + 1;
         long dt = System.currentTimeMillis() - start;
         DebugLog.log("IsoMetaGrid.Create: finished scanning directories in " + (float)dt / 1000.0F + " seconds");
         IsoWorld.instance.setWgChunk(new WorldGenChunk(WorldGenParams.INSTANCE.getSeed()));
         IsoWorld.instance.setBlending(new Blending());
         IsoWorld.instance.setAttachmentsHandler(new AttachmentsHandler());
         IsoWorld.instance.setBiomeMap(new BiomeMap());
         IsoWorld.instance.setZoneGenerator(new ZoneGenerator(IsoWorld.instance.getBiomeMap()));
         IsoWorld.instance.setZombieVoronois(ZombieVoronoi.getVoronois(WorldGenParams.INSTANCE.getSeed()));
         BiomeRegistry.instance.reset();
         DebugLog.log("IsoMetaGrid.Create: begin loading");
         this.createStartTime = System.currentTimeMillis();

         for (int i = 0; i < 8; i++) {
            IsoMetaGrid.MetaGridLoaderThread thread = new IsoMetaGrid.MetaGridLoaderThread(this.minY + i);
            thread.setDaemon(true);
            thread.setName("MetaGridLoaderThread" + i);
            thread.start();
            this.threads[i] = thread;
         }
      } else {
         throw new IllegalStateException("Failed to find any .lotheader files");
      }
   }

   public void CreateStep2() {
      long progressMS = System.currentTimeMillis();
      boolean waiting = true;

      while (waiting) {
         waiting = false;

         for (int i = 0; i < 8; i++) {
            if (this.threads[i].isAlive()) {
               waiting = true;

               try {
                  Thread.sleep(100L);
               } catch (InterruptedException var7) {
               }
               break;
            }
         }

         long currentMS = System.currentTimeMillis();
         if (waiting && currentMS - progressMS > 1000L) {
            progressMS = currentMS;

            for (int i = 0; i < 8; i++) {
               if (this.threads[i].isAlive()) {
                  DebugType.General.println("thread %d/%d loading %s", new Object[]{i + 1, 8, this.threads[i].currentFile});
               }
            }
         }
      }

      this.consolidateBuildings();

      for (int i = 0; i < 8; i++) {
         this.threads[i].postLoad();
         this.threads[i] = null;
      }

      this.initIncompleteCells();

      for (int i = 0; i < this.buildings.size(); i++) {
         BuildingDef def = this.buildings.get(i);
         this.addRoomsToAdjacentCells(def);
         if (!Core.gameMode.equals("LastStand") && def.rooms.size() > 2) {
            int randAlarm = 11;
            if (SandboxOptions.instance.doesPowerGridExist()) {
               randAlarm = 9;
            }

            if (SandboxOptions.instance.alarm.getValue() == 1) {
               randAlarm = -1;
            } else if (SandboxOptions.instance.alarm.getValue() == 2) {
               randAlarm += 5;
            } else if (SandboxOptions.instance.alarm.getValue() == 3) {
               randAlarm += 3;
            } else if (SandboxOptions.instance.alarm.getValue() == 5) {
               randAlarm -= 3;
            } else if (SandboxOptions.instance.alarm.getValue() == 6) {
               randAlarm -= 5;
            }

            if (randAlarm > -1) {
               def.alarmed = Rand.Next(randAlarm) == 0;
            }

            if (def.alarmed) {
               def.alarmDecay = SandboxOptions.getInstance().randomAlarmDecay(SandboxOptions.getInstance().alarmDecay.getValue());
            }
         }
      }

      long dt = System.currentTimeMillis() - this.createStartTime;
      DebugLog.log("IsoMetaGrid.Create: finished loading in " + (float)dt / 1000.0F + " seconds");
   }

   private void initIncompleteCells() {
      for (MapFiles mapFiles : IsoLot.MapFiles) {
         this.initIncompleteCells(mapFiles);
      }
   }

   private void initIncompleteCells(MapFiles mapFiles) {
      for (int cellY = mapFiles.minY; cellY <= mapFiles.maxY; cellY++) {
         for (int cellX = mapFiles.minX; cellX <= mapFiles.maxX; cellX++) {
            LotHeader lotHeader = mapFiles.getLotHeader(cellX, cellY);
            if (lotHeader != null) {
               if (PZMath.coordmodulo(cellX * 256, 300) > 0) {
                  lotHeader.adjacentCells[IsoDirections.W.ordinal()] = mapFiles.hasCell(cellX - 1, cellY);
                  lotHeader.adjacentCells[IsoDirections.E.ordinal()] = mapFiles.hasCell(cellX + 1, cellY);
               }

               if (PZMath.coordmodulo(cellY * 256, 300) > 0) {
                  lotHeader.adjacentCells[IsoDirections.N.ordinal()] = mapFiles.hasCell(cellX, cellY - 1);
                  lotHeader.adjacentCells[IsoDirections.S.ordinal()] = mapFiles.hasCell(cellX, cellY + 1);
               }

               if (PZMath.coordmodulo(cellX * 256, 300) > 0 && PZMath.coordmodulo(cellY * 256, 300) > 0) {
                  lotHeader.adjacentCells[IsoDirections.NW.ordinal()] = mapFiles.hasCell(cellX - 1, cellY - 1);
                  lotHeader.adjacentCells[IsoDirections.NE.ordinal()] = mapFiles.hasCell(cellX + 1, cellY - 1);
                  lotHeader.adjacentCells[IsoDirections.SE.ordinal()] = mapFiles.hasCell(cellX + 1, cellY + 1);
                  lotHeader.adjacentCells[IsoDirections.SW.ordinal()] = mapFiles.hasCell(cellX - 1, cellY + 1);
               }
            }
         }
      }
   }

   public boolean isChunkLoaded(int wx, int wy) {
      IsoChunk chunk = GameServer.server ? ServerMap.instance.getChunk(wx, wy) : IsoWorld.instance.currentCell.getChunk(wx, wy);
      return chunk != null && chunk.loaded;
   }

   public void Dispose() {
      if (this.grid != null) {
         for (int x = 0; x < this.gridX(); x++) {
            IsoMetaCell[] column = this.grid[x];

            for (int y = 0; y < column.length; y++) {
               IsoMetaCell metaCell = column[y];
               if (metaCell != null) {
                  metaCell.Dispose();
               }
            }

            Arrays.fill(column, null);
         }

         Arrays.fill(this.grid, null);
         this.grid = null;

         for (BuildingDef buildingDef : this.buildings) {
            buildingDef.Dispose();
         }

         this.buildings.clear();
         this.vehiclesZones.clear();

         for (Zone zone : this.zones) {
            zone.Dispose();
         }

         this.zones.clear();
         this.animalZoneHandler.Dispose();
         this.sharedStrings.clear();
         this.removedBuildings.clear();
      }
   }

   public Vector2 getRandomIndoorCoord() {
      return null;
   }

   public RoomDef getRandomRoomBetweenRange(float x, float y, float min, float max) {
      return null;
   }

   public RoomDef getRandomRoomNotInRange(float x, float y, int range) {
      return null;
   }

   public void save() {
      long pzoptT0 = pzopt.Config.DEV_HOTSAVE_TIMING ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
      try {
         this.save("map_meta.bin", this::save);
         this.save("map_zone.bin", this::saveZone);
         this.save("map_animals.bin", this::saveAnimalZones);
         this.saveCells("metagrid", "metacell_%d_%d.bin", IsoMetaCell::save);
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
      if (pzoptT0 != 0L) { // pzopt: devHotsaveTiming
         pzopt.Log.info("hotsave timing: meta grid " + (System.nanoTime() - pzoptT0) / 1000L + " us, serialize " + pzoptSerNs / 1000L + " us, write " + pzoptWriteNs / 1000L + " us, cells " + this.cellsToSave.size() + ", " + pzoptParts); // pzopt: devHotsaveTiming
         pzoptSerNs = 0L; // pzopt: devHotsaveTiming
         pzoptWriteNs = 0L; // pzopt: devHotsaveTiming
         pzoptParts.setLength(0); // pzopt: devHotsaveTiming
      } // pzopt: devHotsaveTiming
   }

   private static long pzoptSerNs; // pzopt: devHotsaveTiming
   private static long pzoptWriteNs; // pzopt: devHotsaveTiming
   private static final StringBuilder pzoptParts = new StringBuilder(); // pzopt: devHotsaveTiming

   public void addCellToSave(IsoMetaCell cell) {
      this.cellsToSave.add(cell);
   }

   private void save(String outFilePath, Consumer<ByteBuffer> saveMethod) throws IOException {
      File outFile = ZomboidFileSystem.instance.getFileInCurrentSave(outFilePath);

      try (
         FileOutputStream fos = new FileOutputStream(outFile);
         BufferedOutputStream bos = new BufferedOutputStream(fos);
      ) {
         synchronized (SliceY.SliceBufferLock) {
            SliceY.SliceBuffer.clear();
            long pzoptT0 = pzopt.Config.DEV_HOTSAVE_TIMING ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
            saveMethod.accept(SliceY.SliceBuffer);
            long pzoptT1 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
            bos.write(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
            if (pzoptT0 != 0L) { // pzopt: devHotsaveTiming
               pzoptSerNs += pzoptT1 - pzoptT0; // pzopt: devHotsaveTiming
               pzoptWriteNs += System.nanoTime() - pzoptT1; // pzopt: devHotsaveTiming
               pzoptParts.append(outFilePath).append(' ').append((pzoptT1 - pzoptT0) / 1000L).append("us/").append(SliceY.SliceBuffer.position() / 1024).append("KB "); // pzopt: devHotsaveTiming
            } // pzopt: devHotsaveTiming
         }
      }
   }

   private void saveCells(String path, String filter, BiConsumer<IsoMetaCell, ByteBuffer> saveMethod) throws IOException {
      for (IsoMetaCell cell : new ArrayList<>(this.cellsToSave)) {
         String filteredFilePath = String.format(filter, cell.getX(), cell.getY());
         File outFile = ZomboidFileSystem.instance.getFileInCurrentSave(path, filteredFilePath);

         try (
            FileOutputStream fos = new FileOutputStream(outFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
         ) {
            synchronized (SliceY.SliceBufferLock) {
               SliceY.SliceBuffer.clear();
               saveMethod.accept(cell, SliceY.SliceBuffer);
               bos.write(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
            }
         }

         this.cellsToSave.remove(cell);
      }
   }

   public void saveToBufferMap(SaveBufferMap bufferMap) {
      long pzoptT0 = pzopt.Config.DEV_HOTSAVE_TIMING ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
      int pzoptCells = this.cellsToSave.size(); // pzopt: devHotsaveTiming
      long pzoptT1 = 0L; // pzopt: devHotsaveTiming
      long pzoptT2 = 0L; // pzopt: devHotsaveTiming
      long pzoptT3 = 0L; // pzopt: devHotsaveTiming
      try {
         this.saveToSaveBufferMap(bufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_meta.bin"), this::save);
         pzoptT1 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         this.saveToSaveBufferMap(bufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_zone.bin"), this::saveZone);
         pzoptT2 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         this.saveToSaveBufferMap(bufferMap, ZomboidFileSystem.instance.getFileNameInCurrentSave("map_animals.bin"), this::saveAnimalZones);
         pzoptT3 = pzoptT0 != 0L ? System.nanoTime() : 0L; // pzopt: devHotsaveTiming
         this.saveCellsToSaveBufferMap(bufferMap, "metagrid", "metacell_%d_%d.bin", IsoMetaCell::save);
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
      if (pzoptT0 != 0L && pzoptT3 != 0L) { // pzopt: devHotsaveTiming
         pzopt.Log.info("hotsave timing (meta grid parts): meta " + (pzoptT1 - pzoptT0) / 1000L + " us, zones " + (pzoptT2 - pzoptT1) / 1000L // pzopt: devHotsaveTiming
            + " us, animal zones " + (pzoptT3 - pzoptT2) / 1000L + " us, " + pzoptCells + " cells " + (System.nanoTime() - pzoptT3) / 1000L + " us"); // pzopt: devHotsaveTiming
      } // pzopt: devHotsaveTiming
   }

   public void saveToSaveBufferMap(SaveBufferMap bufferMap, String fileName, Consumer<ByteBuffer> saveMethod) {
      synchronized (SliceY.SliceBufferLock) {
         SliceY.SliceBuffer.clear();
         saveMethod.accept(SliceY.SliceBuffer);
         ByteBufferPooledObject buffer = bufferMap.allocate(SliceY.SliceBuffer.position());
         buffer.put(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
         bufferMap.put(fileName, buffer);
      }
   }

   public void saveCellsToSaveBufferMap(SaveBufferMap bufferMap, String path, String filter, BiConsumer<IsoMetaCell, ByteBuffer> saveMethod) {
      for (IsoMetaCell cell : new ArrayList<>(this.cellsToSave)) {
         String filteredFilePath = String.format(filter, cell.getX(), cell.getY());
         String outFile = ZomboidFileSystem.instance.getFileNameInCurrentSave(path, filteredFilePath);
         synchronized (SliceY.SliceBufferLock) {
            SliceY.SliceBuffer.clear();
            saveMethod.accept(cell, SliceY.SliceBuffer);
            ByteBufferPooledObject buffer = bufferMap.allocate(SliceY.SliceBuffer.position());
            buffer.put(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
            bufferMap.put(outFile, buffer);
         }

         this.cellsToSave.remove(cell);
      }
   }

   public void load(String inFilePath, BiConsumer<ByteBuffer, Integer> loadMethod) {
      File file = ZomboidFileSystem.instance.getFileInCurrentSave(inFilePath);

      try (
         FileInputStream fis = new FileInputStream(file);
         BufferedInputStream bis = new BufferedInputStream(fis);
      ) {
         synchronized (SliceY.SliceBufferLock) {
            SliceY.SliceBuffer.clear();
            int numBytes = bis.read(SliceY.SliceBuffer.array());
            SliceY.SliceBuffer.limit(numBytes);
            loadMethod.accept(SliceY.SliceBuffer, -1);
         }
      } catch (FileNotFoundException var14) {
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }
   }

   public void loadCells(String path, String filter, QuadConsumer<IsoMetaCell, IsoMetaGrid, ByteBuffer, Integer> loadMethod) {
      File directory = ZomboidFileSystem.instance.getFileInCurrentSave(path);
      Pattern pattern = Pattern.compile(filter);
      FilenameFilter regexFilter = new RegExFilenameFilter(pattern);
      String[] files = directory.list(regexFilter);
      if (files != null) {
         for (String file : files) {
            File filePath = ZomboidFileSystem.instance.getFileInCurrentSave(path, file);
            Matcher matcher = pattern.matcher(file);
            matcher.matches();
            int cellX = Integer.parseInt(matcher.group(1));
            int cellY = Integer.parseInt(matcher.group(2));
            IsoMetaCell cell = this.getCellOrCreate(cellX - this.minX, cellY - this.minY);

            try (
               FileInputStream fis = new FileInputStream(filePath);
               BufferedInputStream bis = new BufferedInputStream(fis);
            ) {
               synchronized (SliceY.SliceBufferLock) {
                  SliceY.SliceBuffer.clear();
                  int numBytes = bis.read(SliceY.SliceBuffer.array());
                  SliceY.SliceBuffer.limit(numBytes);
                  loadMethod.accept(cell, this, SliceY.SliceBuffer, -1);
               }
            } catch (FileNotFoundException var27) {
            } catch (Exception ex) {
               ExceptionLogger.logException(ex);
            }
         }
      }
   }

   public void loadZone(ByteBuffer input, int worldVersion) {
      if (worldVersion == -1) {
         byte b1 = input.get();
         byte b2 = input.get();
         byte b3 = input.get();
         byte b4 = input.get();
         if (b1 != 90 || b2 != 79 || b3 != 78 || b4 != 69) {
            DebugLog.log("ERROR: expected 'ZONE' at start of map_zone.bin");
            return;
         }

         worldVersion = input.getInt();
      }

      int oldNumZones = this.zones.size();

      for (Zone zone : this.zones) {
         zone.Dispose();
      }

      this.zones.clear();

      for (int y = 0; y < this.height; y++) {
         for (int x = 0; x < this.width; x++) {
            if (this.hasCell(x, y)) {
               IsoMetaCell metaCell = this.getCell(x, y);

               for (int cy = 0; cy < 32; cy++) {
                  for (int cx = 0; cx < 32; cx++) {
                     if (metaCell.hasChunk(cx + cy * 32)) {
                        metaCell.getChunk(cx + cy * 32).clearZones();
                        metaCell.clearChunk(cx + cy * 32);
                     }
                  }
               }
            }
         }
      }

      HashMap<Integer, String> stringMap = this.loadStringMap(input);
      int zoneSize = input.getInt();
      DebugLog.log("loading " + zoneSize + " zones from map_zone.bin");

      for (int i = 0; i < zoneSize; i++) {
         Zone newZone = new Zone().load(input, worldVersion, stringMap, this.sharedStrings);
         if (!"WorldGen".equalsIgnoreCase(newZone.type)) {
            this.registerZone(newZone);
         }
      }

      int spawnedZombieZoneSize = input.getInt();

      for (int i = 0; i < spawnedZombieZoneSize; i++) {
         String zoneName = GameWindow.ReadString(input);
         ArrayList<UUID> zoneIds = new ArrayList<>();
         int zoneIdsSize = input.getInt();

         for (int j = 0; j < zoneIdsSize; j++) {
            if (worldVersion >= 215) {
               zoneIds.add(GameWindow.ReadUUID(input));
            } else {
               zoneIds.add(UUID.randomUUID());
            }
         }

         IsoWorld.instance.getSpawnedZombieZone().put(zoneName, zoneIds);
      }
   }

   public void loadAnimalZones(ByteBuffer input, int worldVersion) {
      if (worldVersion == -1) {
         byte b1 = input.get();
         byte b2 = input.get();
         byte b3 = input.get();
         byte b4 = input.get();
         if (b1 != 90 || b2 != 79 || b3 != 78 || b4 != 69) {
            DebugLog.log("ERROR: expected 'ZONE' at start of map_animals.bin");
            return;
         }

         worldVersion = input.getInt();
      }

      for (AnimalZone zone : this.animalZoneHandler.getZones()) {
         zone.Dispose();
      }

      this.animalZoneHandler.Dispose();
      this.cellsToSave.clear();

      for (int y = 0; y < this.height; y++) {
         for (int x = 0; x < this.width; x++) {
            if (this.hasCell(x, y)) {
               this.getCell(x, y).clearAnimalZones();
            }
         }
      }

      HashMap<Integer, String> stringMap = this.loadStringMap(input);
      int zoneSize = input.getInt();
      DebugLog.log("loading " + zoneSize + " zones from map_animals.bin");

      for (int i = 0; i < zoneSize; i++) {
         this.registerAnimalZone(new AnimalZone().load(input, worldVersion, stringMap, this.sharedStrings), false);
      }

      int junctionSize = input.getInt();

      for (int i = 0; i < junctionSize; i++) {
         AnimalZoneJunction junction = AnimalZoneJunction.load(input, worldVersion);
         junction.zoneSelf.addJunction(junction);
      }
   }

   private HashMap<Integer, String> loadStringMap(ByteBuffer input) {
      int numStrings = input.getInt();
      HashMap<Integer, String> stringMap = new HashMap<>();

      for (int i = 0; i < numStrings; i++) {
         String str = GameWindow.ReadString(input);
         stringMap.put(i, str);
      }

      return stringMap;
   }

   public void saveZone(ByteBuffer output) {
      output.put((byte)90);
      output.put((byte)79);
      output.put((byte)78);
      output.put((byte)69);
      output.putInt(249);
      HashMap<String, Integer> stringMap = this.saveStringMap(output, this.zones);
      output.putInt(this.zones.size());
      this.zones.forEach(zone -> zone.save(output, stringMap));
      stringMap.clear();
      output.putInt(IsoWorld.instance.getSpawnedZombieZone().size());

      for (String zoneName : IsoWorld.instance.getSpawnedZombieZone().keySet()) {
         ArrayList<UUID> zonesId = (ArrayList<UUID>)IsoWorld.instance.getSpawnedZombieZone().get(zoneName);
         GameWindow.WriteString(output, zoneName);
         output.putInt(zonesId.size());

         for (int i = 0; i < zonesId.size(); i++) {
            GameWindow.WriteUUID(output, zonesId.get(i));
         }
      }
   }

   public void saveAnimalZones(ByteBuffer output) {
      output.put((byte)90);
      output.put((byte)79);
      output.put((byte)78);
      output.put((byte)69);
      output.putInt(249);
      ArrayList<AnimalZone> animalZones = new ArrayList<>(this.animalZoneHandler.getZones());
      HashMap<String, Integer> stringMap = this.saveStringMap(output, animalZones);
      output.putInt(animalZones.size());
      animalZones.forEach(animalZone -> animalZone.save(output, stringMap));
      Set<AnimalZoneJunction> junctions = new HashSet<>();

      for (AnimalZone zone : animalZones) {
         if (zone.junctions != null) {
            junctions.addAll(zone.junctions);
         }
      }

      output.putInt(junctions.size());
      junctions.forEach(junction -> junction.save(output));
   }

   private HashMap<String, Integer> saveStringMap(ByteBuffer output, List<? extends Zone> zones) {
      HashSet<String> stringSet = new HashSet<>();

      for (int i = 0; i < zones.size(); i++) {
         Zone z = zones.get(i);
         stringSet.add(z.getName());
         stringSet.add(z.getOriginalName());
         stringSet.add(z.getType());
      }

      ArrayList<String> stringArray = new ArrayList<>(stringSet);
      HashMap<String, Integer> stringMap = new HashMap<>();

      for (int i = 0; i < stringArray.size(); i++) {
         stringMap.put(stringArray.get(i), i);
      }

      if (stringArray.size() > 32767) {
         throw new IllegalStateException("IsoMetaGrid.saveZone() string table is too large");
      }

      output.putInt(stringArray.size());

      for (int i = 0; i < stringArray.size(); i++) {
         GameWindow.WriteString(output, stringArray.get(i));
      }

      return stringMap;
   }

   private void getLotDirectories(String mapName, ArrayList<String> result) {
      if (!result.contains(mapName)) {
         Map mapInfo = ChooseGameInfo.getMapDetails(mapName);
         if (mapInfo != null) {
            result.add(mapName);

            for (String lotDir : mapInfo.getLotDirectories()) {
               this.getLotDirectories(lotDir, result);
            }
         }
      }
   }

   public ArrayList<String> getLotDirectories() {
      if (GameClient.client) {
         Core.gameMap = GameClient.gameMap;
      }

      if (GameServer.server) {
         Core.gameMap = GameServer.gameMap;
      }

      if (Core.gameMap.equals("DEFAULT")) {
         MapGroups mapGroups = new MapGroups();
         mapGroups.createGroups();
         if (mapGroups.getNumberOfGroups() != 1) {
            throw new RuntimeException("GameMap is DEFAULT but there are multiple worlds to choose from");
         }

         mapGroups.setWorld(0);
      }

      ArrayList<String> result = new ArrayList<>();
      if (Core.gameMap.contains(";")) {
         String[] ss = Core.gameMap.split(";");

         for (int i = 0; i < ss.length; i++) {
            String lotDir = ss[i].trim();
            if (!lotDir.isEmpty() && !result.contains(lotDir)) {
               result.add(lotDir);
            }
         }
      } else {
         this.getLotDirectories(Core.gameMap, result);
      }

      return result;
   }

   public void addRoomsToAdjacentCells(BuildingDef buildingDef) {
      int cellX = buildingDef.x / 256;
      int cellY = buildingDef.y / 256;
      int cellX2 = (buildingDef.x2 - 1) / 256;
      int cellY2 = (buildingDef.y2 - 1) / 256;
      if (cellX2 != cellX || cellY2 != cellY) {
         for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  IsoMetaCell metaCell = this.getCellData(cellX + dx, cellY + dy);
                  if (metaCell != null) {
                     metaCell.addRooms(buildingDef.rooms, (cellX + dx) * 256, (cellY + dy) * 256);
                     metaCell.addRooms(buildingDef.emptyoutside, (cellX + dx) * 256, (cellY + dy) * 256);
                  }
               }
            }
         }
      }
   }

   public void addRoomsToAdjacentCells(BuildingDef buildingDef, ArrayList<RoomDef> roomDefs) {
      int cellX = PZMath.fastfloor(buildingDef.x / 256.0F);
      int cellY = PZMath.fastfloor(buildingDef.y / 256.0F);
      int cellX2 = (buildingDef.x2 - 1) / 256;
      int cellY2 = (buildingDef.y2 - 1) / 256;
      if (cellX2 != cellX || cellY2 != cellY) {
         for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
               if (dx != 0 || dy != 0) {
                  IsoMetaCell metaCell = this.getCellData(cellX + dx, cellY + dy);
                  if (metaCell != null) {
                     metaCell.addRooms(roomDefs, (cellX + dx) * 256, (cellY + dy) * 256);
                  }
               }
            }
         }
      }
   }

   public void removeRoomsFromAdjacentCells(BuildingDef buildingDef) {
      int cellX = buildingDef.getCellX();
      int cellY = buildingDef.getCellY();
      int cellX2 = buildingDef.getCellX2();
      int cellY2 = buildingDef.getCellY2();
      this.removeRoomsFromAdjacentCells(buildingDef.getRooms(), cellX, cellY, cellX2, cellY2, -1);
      this.removeRoomsFromAdjacentCells(buildingDef.getEmptyOutside(), cellX, cellY, cellX2, cellY2, -1);
   }

   public void removeRoomsFromAdjacentCells(ArrayList<RoomDef> rooms, int cellX1, int cellY1, int cellX2, int cellY2, int userDefined) {
      if (cellX2 != cellX1 || cellY2 != cellY1) {
         for (int cellY = cellY1; cellY <= cellY2; cellY++) {
            for (int cellX = cellX1; cellX <= cellX2; cellX++) {
               if (cellX != cellX1 || cellY != cellY1) {
                  IsoMetaCell metaCell = this.getCellData(cellX, cellY);
                  if (metaCell != null) {
                     metaCell.removeRooms(rooms, userDefined);
                  }
               }
            }
         }
      }
   }

   private void consolidateBuildings() {
      for (int i = 0; i < IsoLot.MapFiles.size(); i++) {
         MapFiles mapFiles = (MapFiles)IsoLot.MapFiles.get(i);

         for (LotHeader lotHeader : mapFiles.infoHeaders.values()) {
            LotHeader lotHeaderTopLevel = (LotHeader)IsoLot.InfoHeaders.get(lotHeader.fileName);
            IsoMetaCell metaCell = this.getCellData(lotHeader.cellX, lotHeader.cellY);

            for (int j = 0; j < metaCell.buildings.size(); j++) {
               BuildingDef buildingDef = (BuildingDef)metaCell.buildings.get(j);
               int cell300X = buildingDef.x / 300;
               int cell300Y = buildingDef.y / 300;
               if (this.higherPriority300x300CellExists(mapFiles.priority, cell300X, cell300Y)) {
                  boolean var14 = true;
               } else {
                  this.buildings.add(buildingDef);

                  for (RoomDef roomDef : buildingDef.rooms) {
                     metaCell.addRoom(roomDef, metaCell.getX() * 256, metaCell.getY() * 256);
                  }

                  for (RoomDef roomDef : buildingDef.emptyoutside) {
                     metaCell.addRoom(roomDef, metaCell.getX() * 256, metaCell.getY() * 256);
                  }
               }
            }
         }
      }
   }

   private boolean higherPriority300x300CellExists(int priority, int cell300X, int cell300Y) {
      for (int i = 0; i < priority; i++) {
         MapFiles mapFiles = (MapFiles)IsoLot.MapFiles.get(i);
         if (mapFiles.hasCell300(cell300X, cell300Y)) {
            return true;
         }
      }

      return false;
   }

   public List<Zone> getZones() {
      return this.zones;
   }

   private final class MetaGridLoaderThread extends Thread {
      final SharedStrings sharedStrings = new SharedStrings();
      final ArrayList<RoomDef> roomList = new ArrayList<>();
      final ArrayList<RoomDef> tempRooms = new ArrayList<>();
      int wY;
      final byte[] zombieIntensity = new byte[1024];
      String currentFile;

      MetaGridLoaderThread(final int wy) {
         this.wY = wy;
      }

      @Override
      public void run() {
         try {
            this.runInner();
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
      }

      void runInner() {
         for (int wY = this.wY; wY <= IsoMetaGrid.this.maxY; wY += 8) {
            for (int wX = IsoMetaGrid.this.minX; wX <= IsoMetaGrid.this.maxX; wX++) {
               this.loadCell(wX, wY);
            }
         }
      }

      void loadCell(int wX, int wY) {
         for (int i = 0; i < IsoLot.MapFiles.size(); i++) {
            MapFiles mapFiles = (MapFiles)IsoLot.MapFiles.get(i);
            this.loadCell(mapFiles, wX, wY);
         }
      }

      void loadCell(MapFiles mapFiles, int wX, int wY) {
         boolean hadError = false;
         String filenameheader = wX + "_" + wY + ".lotheader";
         if (mapFiles.infoFileNames.containsKey(filenameheader)) {
            LotHeader info = (LotHeader)mapFiles.infoHeaders.get(filenameheader);
            if (info != null) {
               File fo = new File((String)mapFiles.infoFileNames.get(filenameheader));
               if (fo.exists()) {
                  this.currentFile = filenameheader;
                  IsoMetaCell metaCell = IsoMetaGrid.this.getCell(wX - IsoMetaGrid.this.minX, wY - IsoMetaGrid.this.minY);
                  boolean bNewCell = metaCell == null;
                  if (metaCell == null) {
                     metaCell = new IsoMetaCell(wX, wY);
                     metaCell.info = info;
                     IsoMetaGrid.this.setCell(wX - IsoMetaGrid.this.minX, wY - IsoMetaGrid.this.minY, metaCell);
                  }

                  try {
                     BufferedRandomAccessFile in = new BufferedRandomAccessFile(fo.getAbsolutePath(), "r", 4096);

                     try {
                        label190: {
                           byte[] magic = new byte[4];
                           in.read(magic, 0, 4);
                           boolean bHasMagic = Arrays.equals(magic, LotHeader.LOTHEADER_MAGIC);
                           if (!bHasMagic) {
                              in.seek(0L);
                           }

                           info.version = IsoLot.readInt(in);
                           if (info.version >= 0 && info.version <= 1) {
                              int tilecount = IsoLot.readInt(in);

                              for (int n = 0; n < tilecount; n++) {
                                 String str = IsoLot.readString(in);
                                 info.tilesUsed.add(this.sharedStrings.get(str.trim()));
                              }

                              if (info.version == 0) {
                                 in.read();
                              }

                              info.width = IsoLot.readInt(in);
                              info.height = IsoLot.readInt(in);
                              if (info.width == 8 && info.height == info.width) {
                                 if (info.version == 0) {
                                    info.minLevel = 0;
                                    info.maxLevel = IsoLot.readInt(in) - 1;
                                 } else {
                                    info.minLevel = IsoLot.readInt(in);
                                    info.maxLevel = IsoLot.readInt(in);
                                 }

                                 this.roomList.clear();
                                 int numRooms = IsoLot.readInt(in);

                                 for (int n = 0; n < numRooms; n++) {
                                    String str = IsoLot.readString(in);
                                    long roomID = 0L;
                                    RoomDef roomDef = new RoomDef(0L, this.sharedStrings.get(str));
                                    roomDef.level = IsoLot.readInt(in);
                                    int rects = IsoLot.readInt(in);

                                    for (int rc = 0; rc < rects; rc++) {
                                       int rx = IsoLot.readInt(in);
                                       int ry = IsoLot.readInt(in);
                                       int rw = IsoLot.readInt(in);
                                       int rh = IsoLot.readInt(in);
                                       RoomRect rect = new RoomRect(rx + wX * 256, ry + wY * 256, rw, rh);
                                       roomDef.rects.add(rect);
                                    }

                                    roomDef.CalculateBounds();
                                    this.roomList.add(roomDef);
                                    int nObjects = IsoLot.readInt(in);

                                    for (int m = 0; m < nObjects; m++) {
                                       int e = IsoLot.readInt(in);
                                       int x = IsoLot.readInt(in);
                                       int y = IsoLot.readInt(in);
                                       roomDef.objects.add(new MetaObject(e, x + wX * 256 - roomDef.x, y + wY * 256 - roomDef.y, roomDef));
                                    }

                                    roomDef.lightsActive = Rand.Next(4) == 0;
                                 }

                                 for (int i = 0; i < this.roomList.size(); i++) {
                                    RoomDef roomDef = this.roomList.get(i);
                                    roomDef.id = RoomID.makeID(wX, wY, metaCell.roomList.size());
                                    metaCell.rooms.put(roomDef.id, roomDef);
                                    roomDef.metaId = roomDef.calculateMetaID(wX, wY);
                                    if (metaCell.roomByMetaId.contains(roomDef.metaId) && !hadError) {
                                       DebugType.General
                                          .error(
                                             "duplicate RoomDef.metaID for room at x=%d, y=%d, level=%d, filename=%s",
                                             new Object[]{roomDef.x, roomDef.y, roomDef.level, fo.getName()}
                                          );
                                       hadError = true;
                                    }

                                    metaCell.roomByMetaId.put(roomDef.metaId, roomDef);
                                    metaCell.roomList.add(roomDef);
                                 }

                                 int numBuildings = IsoLot.readInt(in);

                                 for (int n = 0; n < numBuildings; n++) {
                                    BuildingDef buildingDef = new BuildingDef();
                                    int numbRooms = IsoLot.readInt(in);
                                    buildingDef.id = BuildingID.makeID(wX, wY, n);

                                    for (int x = 0; x < numbRooms; x++) {
                                       int roomIndex = IsoLot.readInt(in);
                                       RoomDef roomDef = this.roomList.get(roomIndex);
                                       roomDef.building = buildingDef;
                                       if (roomDef.isEmptyOutside()) {
                                          buildingDef.emptyoutside.add(roomDef);
                                       } else {
                                          buildingDef.rooms.add(roomDef);
                                       }
                                    }

                                    buildingDef.CalculateBounds(this.tempRooms);
                                    int cell300X = buildingDef.x / 300;
                                    int cell300Y = buildingDef.y / 300;
                                    if (IsoMetaGrid.this.higherPriority300x300CellExists(mapFiles.priority, cell300X, cell300Y)) {
                                       boolean var50 = true;
                                    } else {
                                       metaCell.buildings.add(buildingDef);
                                       buildingDef.metaId = buildingDef.calculateMetaID(wX, wY);
                                       metaCell.buildingByMetaId.put(buildingDef.metaId, buildingDef);
                                    }
                                 }

                                 int nBytes = in.read(this.zombieIntensity);
                                 if (nBytes != this.zombieIntensity.length) {
                                    throw new EOFException(
                                       String.format("wx=%d, wy=%d, nBytes=%d, this.zombieIntensity.length=%d", wX, wY, nBytes, this.zombieIntensity.length)
                                    );
                                 }

                                 List<double[]> voronoisValues = IsoWorld.instance
                                    .getZombieVoronois()
                                    .stream()
                                    .map(vx -> pzopt.ZombieNoise.cellCutoff(vx, wX, wY)) // pzopt: voronoiFast, same values per sector instead of per sample
                                    .toList();

                                 for (int wx = 0; wx < 32; wx++) {
                                    for (int wy = 0; wy < 32; wy++) {
                                       int intensity = this.zombieIntensity[wx * 32 + wy] & 255;
                                       double v = 1.0;

                                       for (double[] values : voronoisValues) {
                                          v *= values[wy * 32 + wx];
                                       }

                                       intensity = (int)(intensity * v);
                                       intensity = PZMath.clamp(intensity, 0, 255);
                                       info.setZombieIntensity(wx + wy * 32, (byte)intensity);
                                       if (metaCell.hasChunk(wx, wy)
                                          && !IsoMetaGrid.this.higherPriority300x300CellExists(
                                             mapFiles.priority, (metaCell.getX() * 256 + wx * 8) / 300, (metaCell.getY() * 256 + wy * 8) / 300
                                          )) {
                                          metaCell.getChunk(wx, wy).setZombieIntensity((byte)intensity);
                                       }
                                    }
                                 }

                                 IsoWorld.instance.getZombieVoronois().forEach(zombieVoronoi -> zombieVoronoi.releaseCell(wX, wY));
                                 break label190;
                              }

                              throw new IOException("invalid chunk width %dx%d : %s".formatted(info.width, info.height, fo.getAbsolutePath()));
                           }

                           throw new IOException("Unsupported version " + info.version);
                        }
                     } catch (Throwable var28) {
                        try {
                           in.close();
                        } catch (Throwable var27) {
                           var28.addSuppressed(var27);
                        }

                        throw var28;
                     }

                     in.close();
                  } catch (Exception e) {
                     DebugLog.log("ERROR loading " + fo.getAbsolutePath());
                     ExceptionLogger.logException(e);
                  }
               }
            }
         }
      }

      void postLoad() {
         this.sharedStrings.clear();
         this.roomList.clear();
         this.tempRooms.clear();
      }
   }
}
