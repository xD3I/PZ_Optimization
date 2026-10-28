package zombie.iso;

import gnu.trove.map.hash.TLongObjectHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.animals.AnimalZone;
import zombie.core.math.PZMath;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoMannequin.MannequinZone;
import zombie.iso.worldgen.zones.WorldGenZone;
import zombie.iso.zones.RoomTone;
import zombie.iso.zones.Trigger;
import zombie.iso.zones.VehicleZone;
import zombie.iso.zones.Zone;

@UsedFromLua
public final class IsoMetaCell {
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.IsoMetaCell");
   }

   public final ArrayList<VehicleZone> vehicleZones = new ArrayList<>();
   private final IsoMetaChunk[] chunkMap = new IsoMetaChunk[1024];
   private pzopt.LotHeaders.Cache pzoptLotHeaders; // pzopt: per-cell memo of MapFiles.getLotHeader (docs/plan-instant-load.md L2)

   private pzopt.LotHeaders.Cache pzoptLotHeaderCache() {
      if (this.pzoptLotHeaders == null || this.pzoptLotHeaders.header != this.info) {
         this.pzoptLotHeaders = new pzopt.LotHeaders.Cache(this.info);
      }

      return this.pzoptLotHeaders;
   }
   public LotHeader info;
   public final ArrayList<Trigger> triggers = new ArrayList<>();
   private final int wx;
   private final int wy;
   private final ArrayList<AnimalZone> animalZones = new ArrayList<>();
   private boolean animalZonesGenerated;
   public final ArrayList<MannequinZone> mannequinZones = new ArrayList<>();
   public ArrayList<WorldGenZone> worldGenZones;
   public final ArrayList<RoomTone> roomTones = new ArrayList<>();
   public final HashMap<Long, RoomDef> rooms = new HashMap<>();
   public final TLongObjectHashMap<RoomDef> roomByMetaId = new TLongObjectHashMap();
   public final ArrayList<RoomDef> roomList = new ArrayList<>();
   public final ArrayList<BuildingDef> buildings = new ArrayList<>();
   public final TLongObjectHashMap<BuildingDef> buildingByMetaId = new TLongObjectHashMap();
   public final HashMap<Long, IsoRoom> isoRooms = new HashMap<>();
   public final HashMap<Long, IsoBuilding> isoBuildings = new HashMap<>();

   public IsoMetaCell(int wx, int wy) {
      this.wx = wx;
      this.wy = wy;
   }

   public int getX() {
      return this.wx;
   }

   public int getY() {
      return this.wy;
   }

   public void addTrigger(BuildingDef def, int triggerRange, int zombieExclusionRange, String type) {
      this.triggers.add(new Trigger(def, triggerRange, zombieExclusionRange, type));
   }

   public void checkTriggers() {
      IsoGameCharacter isoGameCharacter = IsoCamera.getCameraCharacter();
      if (isoGameCharacter != null) {
         int x = PZMath.fastfloor(isoGameCharacter.getX());
         int y = PZMath.fastfloor(isoGameCharacter.getY());

         for (int n = 0; n < this.triggers.size(); n++) {
            Trigger tr = this.triggers.get(n);
            if (x >= tr.def.x - tr.triggerRange && x <= tr.def.x2 + tr.triggerRange && y >= tr.def.y - tr.triggerRange && y <= tr.def.y2 + tr.triggerRange) {
               if (!tr.triggered) {
                  LuaEventManager.triggerEvent("OnTriggerNPCEvent", tr.type, tr.data, tr.def);
               }

               LuaEventManager.triggerEvent("OnMultiTriggerNPCEvent", tr.type, tr.data, tr.def);
               tr.triggered = true;
            }
         }
      }
   }

   public IsoMetaChunk getChunk(int x, int y) {
      return y < 32 && x < 32 && x >= 0 && y >= 0 ? this.getChunk(y * 32 + x) : null;
   }

   public IsoMetaChunk getChunk(int i) {
      if (!this.hasChunk(i)) {
         this.chunkMap[i] = new IsoMetaChunk();
         int chunkX = i % 32;
         int chunkY = i / 32;
         int intensity = pzopt.Config.LOADER_CPU_FIXES && pzopt.Overrides.enabled()
            ? pzopt.LotHeaders.zombieIntensity(this.info, chunkX, chunkY, this.pzoptLotHeaderCache()) // pzopt: lot header resolved once per cell
            : LotHeader.getZombieIntensityForChunk(this.info, chunkX, chunkY);
         this.chunkMap[i].setZombieIntensity((byte)(intensity >= 0 ? intensity : 0));
      }

      return this.chunkMap[i];
   }

   public boolean hasChunk(int x, int y) {
      return y < 32 && x < 32 && x >= 0 && y >= 0 ? this.hasChunk(y * 32 + x) : false;
   }

   public boolean hasChunk(int i) {
      return this.chunkMap[i] != null;
   }

   public void clearChunk(int i) {
      if (this.chunkMap[i].getRoomsSize() == 0 && this.chunkMap[i].getZonesSize() == 0) {
         this.getChunk(i).Dispose();
         this.chunkMap[i] = null;
      }
   }

   public void addZone(Zone zone, int cellX, int cellY) {
      int xmin = zone.x / 8;
      int ymin = zone.y / 8;
      int xmax = (zone.x + zone.w) / 8;
      if ((zone.x + zone.w) % 8 == 0) {
         xmax--;
      }

      int ymax = (zone.y + zone.h) / 8;
      if ((zone.y + zone.h) % 8 == 0) {
         ymax--;
      }

      xmin = PZMath.clamp(xmin, cellX / 8, (cellX + 256) / 8);
      ymin = PZMath.clamp(ymin, cellY / 8, (cellY + 256) / 8);
      xmax = PZMath.clamp(xmax, cellX / 8, (cellX + 256) / 8 - 1);
      ymax = PZMath.clamp(ymax, cellY / 8, (cellY + 256) / 8 - 1);

      for (int yy = ymin; yy <= ymax; yy++) {
         for (int xx = xmin; xx <= xmax; xx++) {
            if (zone.intersects(xx * 8, yy * 8, zone.z, 8, 8)) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               this.getChunk(i).addZone(zone);
            }
         }
      }
   }

   public void removeZone(Zone zone) {
      int xmax = (zone.x + zone.w) / 8;
      if ((zone.x + zone.w) % 8 == 0) {
         xmax--;
      }

      int ymax = (zone.y + zone.h) / 8;
      if ((zone.y + zone.h) % 8 == 0) {
         ymax--;
      }

      int cellX = this.wx * 256;
      int cellY = this.wy * 256;

      for (int yy = zone.y / 8; yy <= ymax; yy++) {
         for (int xx = zone.x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               if (this.hasChunk(i)) {
                  this.getChunk(i).removeZone(zone);
                  this.clearChunk(i);
               }
            }
         }
      }
   }

   public void addRoom(RoomDef room, int cellX, int cellY) {
      int xmax = room.x2 / 8;
      if (room.x2 % 8 == 0) {
         xmax--;
      }

      int ymax = room.y2 / 8;
      if (room.y2 % 8 == 0) {
         ymax--;
      }

      for (int yy = room.y / 8; yy <= ymax; yy++) {
         for (int xx = room.x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               this.getChunk(i).addRoom(room);
            }
         }
      }
   }

   public void addRooms(ArrayList<RoomDef> rooms, int cellX, int cellY) {
      for (int i = 0; i < rooms.size(); i++) {
         RoomDef roomDef = rooms.get(i);
         this.addRoom(roomDef, cellX, cellY);
      }
   }

   public void removeRoom(RoomDef room) {
      int chunksPerWidth = 8;
      int css = 256;
      int xmax = room.x2 / 8;
      if (room.x2 % 8 == 0) {
         xmax--;
      }

      int ymax = room.y2 / 8;
      if (room.y2 % 8 == 0) {
         ymax--;
      }

      int cellX = this.getX() * 256;
      int cellY = this.getY() * 256;

      for (int yy = room.y / 8; yy <= ymax; yy++) {
         for (int xx = room.x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               this.getChunk(i).removeRoom(room);
            }
         }
      }
   }

   public void removeRooms(ArrayList<RoomDef> rooms) {
      this.removeRooms(rooms, -1);
   }

   public void removeRooms(ArrayList<RoomDef> rooms, int userDefined) {
      for (int i = 0; i < rooms.size(); i++) {
         RoomDef roomDef = rooms.get(i);
         if (userDefined == -1 || userDefined == 1 && roomDef.userDefined || userDefined == 0 && !roomDef.userDefined) {
            this.removeRoom(roomDef);
         }
      }
   }

   public void getZonesUnique(Set<Zone> result) {
      for (int i = 0; i < this.chunkMap.length; i++) {
         if (this.hasChunk(i)) {
            this.getChunk(i).getZonesUnique(result);
         }
      }
   }

   public void getZonesIntersecting(int x, int y, int z, int w, int h, ArrayList<Zone> result) {
      int xmax = (x + w) / 8;
      if ((x + w) % 8 == 0) {
         xmax--;
      }

      int ymax = (y + h) / 8;
      if ((y + h) % 8 == 0) {
         ymax--;
      }

      int cellX = this.wx * 256;
      int cellY = this.wy * 256;

      for (int yy = y / 8; yy <= ymax; yy++) {
         for (int xx = x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               if (this.hasChunk(i)) {
                  this.getChunk(i).getZonesIntersecting(x, y, z, w, h, result);
               }
            }
         }
      }
   }

   public void getBuildingsIntersecting(int x, int y, int w, int h, boolean includeEmptyOutside, ArrayList<BuildingDef> result) {
      int xmax = (x + w) / 8;
      if ((x + w) % 8 == 0) {
         xmax--;
      }

      int ymax = (y + h) / 8;
      if ((y + h) % 8 == 0) {
         ymax--;
      }

      int cellX = this.wx * 256;
      int cellY = this.wy * 256;

      for (int yy = y / 8; yy <= ymax; yy++) {
         for (int xx = x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               if (this.hasChunk(i)) {
                  this.getChunk(i).getBuildingsIntersecting(x, y, w, h, includeEmptyOutside, result);
               }
            }
         }
      }
   }

   public void getBuildingsIntersecting(int x, int y, int w, int h, ArrayList<BuildingDef> result) {
      this.getBuildingsIntersecting(x, y, w, h, false, result);
   }

   public void getRoomsIntersecting(int x, int y, int w, int h, boolean includeEmptyOutside, ArrayList<RoomDef> result) {
      int xmax = (x + w) / 8;
      if ((x + w) % 8 == 0) {
         xmax--;
      }

      int ymax = (y + h) / 8;
      if ((y + h) % 8 == 0) {
         ymax--;
      }

      int cellX = this.wx * 256;
      int cellY = this.wy * 256;

      for (int yy = y / 8; yy <= ymax; yy++) {
         for (int xx = x / 8; xx <= xmax; xx++) {
            if (xx >= cellX / 8 && xx < (cellX + 256) / 8 && yy >= cellY / 8 && yy < (cellY + 256) / 8) {
               int i = xx - cellX / 8 + (yy - cellY / 8) * 32;
               if (this.hasChunk(i)) {
                  this.getChunk(i).getRoomsIntersecting(x, y, w, h, includeEmptyOutside, result);
               }
            }
         }
      }
   }

   public void getRoomsIntersecting(int x, int y, int w, int h, ArrayList<RoomDef> result) {
      this.getRoomsIntersecting(x, y, w, h, false, result);
   }

   public void checkAnimalZonesGenerated(int chunkX, int chunkY) {
      if (!this.animalZonesGenerated) {
         this.animalZonesGenerated = true;
         IsoWorld.instance.getZoneGenerator().genAnimalsPath(chunkX, chunkY);
      }
   }

   public void Dispose() {
      for (int i = 0; i < this.chunkMap.length; i++) {
         if (this.hasChunk(i)) {
            this.getChunk(i).Dispose();
            this.chunkMap[i] = null;
         }
      }

      this.info = null;
      this.animalZones.clear();
      this.mannequinZones.clear();
      if (this.worldGenZones != null) {
         this.worldGenZones.clear();
      }

      this.roomTones.clear();
      this.rooms.clear();
      this.roomByMetaId.clear();
      this.roomList.clear();
      this.buildings.clear();
      this.buildingByMetaId.clear();
      this.isoRooms.clear();
      this.isoBuildings.clear();
   }

   public void save(ByteBuffer output) {
      output.put((byte)(this.animalZonesGenerated ? 1 : 0));
   }

   public void load(IsoMetaGrid grid, ByteBuffer input, int worldVersion) {
      this.animalZonesGenerated = input.get() != 0;
   }

   public int getAnimalZonesSize() {
      return this.animalZones.size();
   }

   public AnimalZone getAnimalZone(int index) {
      return this.animalZones.get(index);
   }

   public void addAnimalZone(AnimalZone animalZone) {
      this.animalZones.add(animalZone);
   }

   public void clearAnimalZones() {
      this.animalZones.clear();
   }

   public int getBuildingCount() {
      return this.getBuildingCount(false);
   }

   public int getBuildingCount(boolean bExcludeUserDefined) {
      if (bExcludeUserDefined) {
         int count = 0;

         for (int i = 0; i < this.buildings.size(); i++) {
            BuildingDef buildingDef = this.buildings.get(i);
            if (!buildingDef.isUserDefined()) {
               count++;
            }
         }

         return count;
      } else {
         return this.buildings.size();
      }
   }

   public int getRoomCount() {
      return this.getRoomCount(false);
   }

   public int getRoomCount(boolean bExcludeUserDefined) {
      if (bExcludeUserDefined) {
         int count = 0;

         for (int i = 0; i < this.roomList.size(); i++) {
            RoomDef roomDef = this.roomList.get(i);
            if (!roomDef.userDefined) {
               count++;
            }
         }

         return count;
      } else {
         return this.roomList.size();
      }
   }
}
