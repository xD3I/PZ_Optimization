package zombie.iso.objects;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.properties.IsoPropertyType;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.Moveable;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoObject;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.RoomID;
import zombie.iso.areas.IsoRoom;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;

@UsedFromLua
public class IsoLightSwitch extends IsoObject {
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.objects.IsoLightSwitch"); // pzopt
   }

   public static final int DEFAULT_LIGHT_RADIUS = 8;
   public boolean activated;
   public final ArrayList<IsoLightSource> lights = new ArrayList<>();
   public boolean lightRoom;
   public long roomId = -1L;
   public boolean streetLight;
   private boolean canBeModified;
   private boolean useBattery;
   private boolean hasBattery;
   private String bulbItem = "Base.LightBulb";
   private float power;
   private float delta = 2.5E-4F;
   private float primaryR = 1.0F;
   private float primaryG = 1.0F;
   private float primaryB = 1.0F;
   private static final ArrayList<IsoObject> s_tempObjects = new ArrayList<>();
   protected long lastMinuteStamp = -1L;
   protected int bulbBurnMinutes = -1;
   protected int lastMin;
   protected int nextBreakUpdate = 60;

   public String getObjectName() {
      return "LightSwitch";
   }

   public IsoLightSwitch(IsoCell cell) {
      super(cell);
   }

   public IsoLightSwitch(IsoCell cell, IsoGridSquare sq, IsoSprite gid, long roomId) {
      super(cell, sq, gid);
      this.roomId = roomId;
      if (gid != null && gid.getProperties().has(IsoPropertyType.RED_LIGHT)) {
         if (gid.getProperties().has("IsMoveAble")) {
            this.canBeModified = true;
         }

         this.primaryR = Float.parseFloat(gid.getProperties().get(IsoPropertyType.RED_LIGHT)) / 255.0F;
         this.primaryG = Float.parseFloat(gid.getProperties().get(IsoPropertyType.GREEN_LIGHT)) / 255.0F;
         this.primaryB = Float.parseFloat(gid.getProperties().get(IsoPropertyType.BLUE_LIGHT)) / 255.0F;
      } else {
         this.lightRoom = true;
      }

      this.streetLight = gid != null && gid.getProperties().has("streetlight");
      IsoRoom room = this.square.getRoom();
      if (room != null && this.lightRoom) {
         if (!sq.haveElectricity() && !sq.hasGridPower()) {
            room.def.lightsActive = false;
         }

         this.activated = room.def.lightsActive;
         room.lightSwitches.add(this);
      } else {
         this.activated = true;
      }
   }

   public void addLightSourceFromSprite() {
      if (this.sprite != null && this.sprite.getProperties().has(IsoPropertyType.RED_LIGHT)) {
         float r = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.RED_LIGHT)) / 255.0F;
         float g = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.GREEN_LIGHT)) / 255.0F;
         float b = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.BLUE_LIGHT)) / 255.0F;
         this.setPrimaryR(r);
         this.setPrimaryG(g);
         this.setPrimaryB(b);
         this.activated = false;
         this.setActive(true, true);
         this.createLightSource();
      }
   }

   public void createLightSource() {
      this.createLightSource(this.primaryR, this.primaryG, this.primaryB);
   }

   public void createLightSource(float r, float g, float b) {
      if (!this.lights.isEmpty()) {
         this.lights.clear();
      }

      int radius = 8;
      if (this.sprite.getProperties().has(IsoPropertyType.LIGHT_RADIUS) && Integer.parseInt(this.sprite.getProperties().get(IsoPropertyType.LIGHT_RADIUS)) > 0) {
         radius = Integer.parseInt(this.sprite.getProperties().get(IsoPropertyType.LIGHT_RADIUS));
      }

      IsoLightSource l = new IsoLightSource(this.getXi(), this.getYi(), this.getZi(), r, g, b, radius);
      l.active = this.activated;
      l.wasActive = l.active;
      l.hydroPowered = true;
      l.switches.add(this);
      this.lights.add(l);
   }

   public boolean getCanBeModified() {
      return this.canBeModified;
   }

   public void setCanBeModified(boolean val) {
      this.canBeModified = val;
   }

   public float getPower() {
      return this.power;
   }

   public void setPower(float power) {
      this.power = power;
   }

   public void setDelta(float delta) {
      this.delta = delta;
   }

   public float getDelta() {
      return this.delta;
   }

   public void setUseBattery(boolean b) {
      this.setActive(false);
      this.useBattery = b;
      if (GameServer.server) {
         this.syncCustomizedSettings(null);
      }
   }

   public void setUseBatteryDirect(boolean b) {
      this.useBattery = b;
   }

   public boolean getUseBattery() {
      return this.useBattery;
   }

   public boolean getHasBattery() {
      return this.hasBattery;
   }

   public void setHasBattery(boolean val) {
      this.hasBattery = val;
   }

   public void setHasBatteryRaw(boolean b) {
      this.hasBattery = b;
   }

   public void addBattery(IsoGameCharacter chr, InventoryItem battery) {
      if (this.canBeModified && this.useBattery && !this.hasBattery && battery != null && battery.getFullType().equals("Base.Battery")) {
         this.power = battery.getCurrentUsesFloat();
         this.hasBattery = true;
         chr.removeFromHands(battery);
         chr.getInventory().Remove(battery);
         if (GameServer.server) {
            GameServer.sendRemoveItemFromContainer(chr.getInventory(), battery);
            this.syncCustomizedSettings(null);
         }
      }
   }

   public DrainableComboItem removeBattery(IsoGameCharacter chr) {
      if (this.canBeModified && this.useBattery && this.hasBattery) {
         DrainableComboItem battery = (DrainableComboItem)InventoryItemFactory.CreateItem("Base.Battery");
         if (battery != null) {
            this.hasBattery = false;
            battery.setCurrentUses(this.power >= 0.0F ? (int)(battery.getMaxUses() * this.power) : 0);
            this.power = 0.0F;
            this.setActive(false, false, true);
            chr.getInventory().AddItem(battery);
            if (GameServer.server) {
               GameServer.sendAddItemToContainer(chr.getInventory(), battery);
               this.syncCustomizedSettings(null);
            }

            return battery;
         }
      }

      return null;
   }

   public boolean hasLightBulb() {
      return this.bulbItem != null;
   }

   public String getBulbItem() {
      return this.bulbItem;
   }

   public void setBulbItemRaw(String item) {
      this.bulbItem = item;
   }

   public void addLightBulb(IsoGameCharacter chr, InventoryItem bulb) {
      if (!this.hasLightBulb() && bulb != null && bulb.getType().startsWith("LightBulb")) {
         IsoLightSource light = this.getPrimaryLight();
         if (light != null) {
            this.setPrimaryR(bulb.getColorRed());
            this.setPrimaryG(bulb.getColorGreen());
            this.setPrimaryB(bulb.getColorBlue());
            this.bulbItem = bulb.getFullType();
            chr.removeFromHands(bulb);
            chr.getInventory().Remove(bulb);
            if (GameServer.server) {
               GameServer.sendRemoveItemFromContainer(chr.getInventory(), bulb);
               this.syncCustomizedSettings(null);
            }
         }
      }
   }

   public InventoryItem removeLightBulb(IsoGameCharacter chr) {
      IsoLightSource light = this.getPrimaryLight();
      if (light != null && this.hasLightBulb()) {
         InventoryItem bulb = InventoryItemFactory.CreateItem(this.bulbItem);
         if (bulb != null) {
            bulb.setColorRed(this.getPrimaryR());
            bulb.setColorGreen(this.getPrimaryG());
            bulb.setColorBlue(this.getPrimaryB());
            bulb.setColor(new Color(light.r, light.g, light.b));
            this.bulbItem = null;
            chr.getInventory().AddItem(bulb);
            if (GameServer.server) {
               GameServer.sendAddItemToContainer(chr.getInventory(), bulb);
            }

            this.setActive(false, false, true);
            if (GameServer.server) {
               this.syncCustomizedSettings(null);
            }

            return bulb;
         }
      }

      return null;
   }

   private IsoLightSource getPrimaryLight() {
      return this.lights.isEmpty() ? null : this.lights.getFirst();
   }

   public float getPrimaryR() {
      return this.getPrimaryLight() != null ? this.getPrimaryLight().r : this.primaryR;
   }

   public float getPrimaryG() {
      return this.getPrimaryLight() != null ? this.getPrimaryLight().g : this.primaryG;
   }

   public float getPrimaryB() {
      return this.getPrimaryLight() != null ? this.getPrimaryLight().b : this.primaryB;
   }

   public void setPrimaryR(float r) {
      this.primaryR = r;
      if (this.getPrimaryLight() != null) {
         this.getPrimaryLight().r = r;
      }
   }

   public void setPrimaryG(float g) {
      this.primaryG = g;
      if (this.getPrimaryLight() != null) {
         this.getPrimaryLight().g = g;
      }
   }

   public void setPrimaryB(float b) {
      this.primaryB = b;
      if (this.getPrimaryLight() != null) {
         this.getPrimaryLight().b = b;
      }
   }

   public void load(ByteBuffer input, int worldVersion, boolean isDebugSave) throws IOException {
      super.load(input, worldVersion, isDebugSave);
      this.lightRoom = input.get() != 0;
      if (worldVersion >= 206) {
         this.roomId = input.getLong();
      } else {
         int roomIndex = input.getInt();
         this.roomId = RoomID.makeID(this.square.x / 256, this.square.y / 256, roomIndex);
      }

      this.activated = input.get() != 0;
      this.canBeModified = input.get() != 0;
      if (this.canBeModified) {
         this.useBattery = input.get() != 0;
         this.hasBattery = input.get() != 0;
         if (input.get() != 0) {
            this.bulbItem = GameWindow.ReadString(input);
         } else {
            this.bulbItem = null;
         }

         this.power = input.getFloat();
         this.delta = input.getFloat();
         this.setPrimaryR(input.getFloat());
         this.setPrimaryG(input.getFloat());
         this.setPrimaryB(input.getFloat());
      }

      this.lastMinuteStamp = input.getLong();
      this.bulbBurnMinutes = input.getInt();
      this.streetLight = this.sprite != null && this.sprite.getProperties().has("streetlight");
      if (this.square != null) {
         if (GameClient.client) {
            this.switchLight(this.activated);
         }

         IsoRoom room = this.square.getRoom();
         if (room != null && this.lightRoom) {
            this.activated = room.def.lightsActive;
            room.lightSwitches.add(this);
         } else {
            float r = 0.9F;
            float g = 0.8F;
            float b = 0.7F;
            if (this.sprite != null && this.sprite.getProperties().has(IsoPropertyType.RED_LIGHT)) {
               if (this.canBeModified) {
                  r = this.primaryR;
                  g = this.primaryG;
                  b = this.primaryB;
               } else {
                  r = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.RED_LIGHT)) / 255.0F;
                  g = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.GREEN_LIGHT)) / 255.0F;
                  b = Float.parseFloat(this.sprite.getProperties().get(IsoPropertyType.BLUE_LIGHT)) / 255.0F;
                  this.primaryR = r;
                  this.primaryG = g;
                  this.primaryB = b;
               }
            }

            this.createLightSource(r, g, b);
         }
      }
   }

   public void save(ByteBuffer output, boolean isDebugSave) throws IOException {
      super.save(output, isDebugSave);
      output.put((byte)(this.lightRoom ? 1 : 0));
      output.putLong(this.roomId);
      output.put((byte)(this.activated ? 1 : 0));
      output.put((byte)(this.canBeModified ? 1 : 0));
      if (this.canBeModified) {
         output.put((byte)(this.useBattery ? 1 : 0));
         output.put((byte)(this.hasBattery ? 1 : 0));
         output.put((byte)(this.hasLightBulb() ? 1 : 0));
         if (this.hasLightBulb()) {
            GameWindow.WriteString(output, this.bulbItem);
         }

         output.putFloat(this.power);
         output.putFloat(this.delta);
         output.putFloat(this.getPrimaryR());
         output.putFloat(this.getPrimaryG());
         output.putFloat(this.getPrimaryB());
      }

      output.putLong(this.lastMinuteStamp);
      output.putInt(this.bulbBurnMinutes);
   }

   public boolean onMouseLeftClick(int x, int y) {
      return false;
   }

   public boolean canSwitchLight() {
      if (this.bulbItem != null) {
         boolean electricityAround = this.hasElectricityAround();
         if (!this.useBattery && electricityAround || this.canBeModified && this.useBattery && this.hasBattery && this.power > 0.0F) {
            return true;
         }
      }

      return false;
   }

   // pzopt: LightingJNI.checkLights asks every light source's switch for power every frame; the answer (grid power,
   // generators, neighbouring squares) changes rarely, so it is reused for Config.LIGHT_SWITCH_CHECK_FRAMES frames
   private int pzoptElecFrame = -1;
   private boolean pzoptElec;

   private boolean hasElectricityAround() {
      int pzoptInterval = pzopt.Overrides.enabled() ? pzopt.Config.LIGHT_SWITCH_CHECK_FRAMES : 0;
      if (pzoptInterval <= 0 || IsoWorld.instance == null) {
         return this.pzoptHasElectricityAroundNow();
      }
      int now = IsoWorld.instance.getFrameNo();
      if (this.pzoptElecFrame >= 0 && now >= this.pzoptElecFrame && now - this.pzoptElecFrame < pzoptInterval) {
         return this.pzoptElec;
      }
      this.pzoptElec = this.pzoptHasElectricityAroundNow();
      this.pzoptElecFrame = now;
      return this.pzoptElec;
   }

   private boolean pzoptHasElectricityAroundNow() { // pzopt: the stock hasElectricityAround body
      if (this.getObjectIndex() == -1) {
         return false;
      }

      boolean isPreElecShut = this.hasGridPower();
      boolean electricityAround = isPreElecShut
         ? this.isBuildingSquare(this.square) || this.hasFasciaAdjacentToBuildingSquare(this.square) || this.streetLight
         : this.square.haveElectricity();
      if (!electricityAround && this.getCell() != null) {
         for (int dz = 0; dz >= -1 && this.getSquare().getZ() + dz >= -32; dz--) {
            for (int dx = -1; dx < 2; dx++) {
               for (int dy = -1; dy < 2; dy++) {
                  if (dx != 0 || dy != 0 || dz != 0) {
                     IsoGridSquare gs = this.getCell().getGridSquare(this.getSquare().getX() + dx, this.getSquare().getY() + dy, this.getSquare().getZ() + dz);
                     if (gs != null) {
                        if (isPreElecShut && (this.isBuildingSquare(gs) || this.hasFasciaAdjacentToBuildingSquare(gs))) {
                           return true;
                        }

                        if (gs.haveElectricity()) {
                           return true;
                        }
                     }
                  }
               }
            }
         }
      }

      return electricityAround;
   }

   private boolean isBuildingSquare(IsoGridSquare square) {
      if (square == null) {
         return false;
      } else {
         return square.getRoom() != null ? true : square.getRoofHideBuilding() != null;
      }
   }

   private boolean hasFasciaAdjacentToBuildingSquare(IsoGridSquare square) {
      IsoObject[] objects = (IsoObject[])square.getObjects().getElements();
      int i = 0;

      for (int n = square.getObjects().size(); i < n; i++) {
         IsoObject object = objects[i];
         if (object.isFascia()) {
            IsoGridSquare square1 = object.getFasciaAttachedSquare();
            return this.isBuildingSquare(square1);
         }
      }

      return false;
   }

   public boolean setActive(boolean active) {
      return this.setActive(active, false, false);
   }

   public boolean setActive(boolean active, boolean setActiveBoolOnly) {
      return this.setActive(active, setActiveBoolOnly, false);
   }

   public boolean setActive(boolean active, boolean setActiveBoolOnly, boolean ignoreSwitchCheck) {
      if (this.bulbItem == null) {
         active = false;
      }

      if (active == this.activated) {
         return this.activated;
      }

      if (this.square.getRoom() == null && !this.canBeModified) {
         return this.activated;
      }

      if (ignoreSwitchCheck || this.canSwitchLight()) {
         this.activated = active;
         if (!setActiveBoolOnly) {
            this.switchLight(this.activated);
            LightingJNI.doInvalidateGlobalLights(IsoPlayer.getPlayerIndex());
            this.syncIsoObject(false, (byte)(this.activated ? 1 : 0), null);
         }
      }

      return this.activated;
   }

   public boolean toggle() {
      return this.setActive(!this.activated);
   }

   public void switchLight(boolean activated) {
      if (this.lightRoom && this.square.getRoom() != null) {
         this.square.getRoom().def.lightsActive = activated;

         for (int i = 0; i < this.square.getRoom().lightSwitches.size(); i++) {
            ((IsoLightSwitch)this.square.getRoom().lightSwitches.get(i)).activated = activated;
         }

         if (GameServer.server) {
            int cellX = PZMath.fastfloor(this.square.getX() / 256);
            int cellY = PZMath.fastfloor(this.square.getY() / 256);
            long roomID = this.square.getRoom().def.id;
            if (!RoomID.isSameCell(roomID, cellX, cellY)) {
               GameServer.sendMetaGrid(RoomID.getCellX(roomID), RoomID.getCellY(roomID), RoomID.getIndex(roomID));
            } else {
               GameServer.sendMetaGrid(cellX, cellY, RoomID.getIndex(roomID));
            }
         }
      }

      for (int n = 0; n < this.lights.size(); n++) {
         IsoLightSource s = this.lights.get(n);
         s.active = activated;
      }

      this.getSpriteGridObjects(s_tempObjects);
      if (!s_tempObjects.isEmpty()) {
         for (int i = 0; i < s_tempObjects.size(); i++) {
            IsoObject object = s_tempObjects.get(i);
            if (object != this) {
               if (object instanceof IsoLightSwitch lightSwitch) {
                  if (lightSwitch.isActivated() != activated) {
                     lightSwitch.setActive(activated);
                  }
               } else if (object.getLightSource() != null) {
                  object.checkLightSourceActive();
               }
            }
         }
      }

      if (!GameServer.server) {
         IsoGridSquare.recalcLightTime = -1.0F;
         Core.dirtyGlobalLightsCount++;
         GameTime.instance.lightSourceUpdate = 100.0F;
         LightingJNI.doInvalidateGlobalLights(IsoPlayer.getPlayerIndex());
         if (this.hasAnimatedAttachments()) {
            this.invalidateRenderChunkLevel(1024L);
         }
      }

      IsoGenerator.updateGenerator(this.getSquare());
   }

   public void getCustomSettingsFromItem(InventoryItem item) {
      if (item instanceof Moveable i && i.isLight()) {
         this.useBattery = i.isLightUseBattery();
         this.hasBattery = i.isLightHasBattery();
         this.bulbItem = i.getLightBulbItem();
         this.power = i.getLightPower();
         this.delta = i.getLightDelta();
         this.setPrimaryR(i.getLightR());
         this.setPrimaryG(i.getLightG());
         this.setPrimaryB(i.getLightB());
      }
   }

   public void setCustomSettingsToItem(InventoryItem item) {
      if (item instanceof Moveable i) {
         i.setLightUseBattery(this.useBattery);
         i.setLightHasBattery(this.hasBattery);
         i.setLightBulbItem(this.bulbItem);
         i.setLightPower(this.power);
         i.setLightDelta(this.delta);
         i.setLightR(this.primaryR);
         i.setLightG(this.primaryG);
         i.setLightB(this.primaryB);
      }
   }

   public void syncCustomizedSettings(UdpConnection source) {
      if (GameClient.client) {
         INetworkPacket.send(PacketType.SyncCustomLightSettings, new Object[]{this});
      } else if (GameServer.server) {
         INetworkPacket.sendToAll(PacketType.SyncCustomLightSettings, source, new Object[]{this});
      }
   }

   public void syncIsoObjectSend(ByteBufferWriter b) {
      b.putInt(this.square.getX());
      b.putInt(this.square.getY());
      b.putInt(this.square.getZ());
      b.putByte(this.square.getObjects().indexOf(this));
      b.putBoolean(true);
      b.putBoolean(this.activated);
   }

   public void syncIsoObject(boolean bRemote, byte val, UdpConnection source, ByteBufferReader bb) {
      this.syncIsoObject(bRemote, val, source);
   }

   public void syncIsoObject(boolean bRemote, byte val, UdpConnection source) {
      if (this.square == null) {
         System.out.println("ERROR: " + this.getClass().getSimpleName() + " square is null");
      } else if (this.getObjectIndex() == -1) {
         System.out
            .println(
               "ERROR: " + this.getClass().getSimpleName() + " not found on square " + this.square.getX() + "," + this.square.getY() + "," + this.square.getZ()
            );
      } else {
         if (GameServer.server) {
            for (UdpConnection connection : GameServer.udpEngine.connections) {
               if (source != null) {
                  if (connection.getConnectedGUID() != source.getConnectedGUID()) {
                     ByteBufferWriter b = connection.startPacket();
                     PacketType.SyncIsoObject.doPacket(b);
                     this.syncIsoObjectSend(b);
                     PacketType.SyncIsoObject.send(connection);
                  }
               } else if (connection.isRelevantTo(this.square.x, this.square.y)) {
                  ByteBufferWriter b = connection.startPacket();
                  PacketType.SyncIsoObject.doPacket(b);
                  b.putInt(this.square.getX());
                  b.putInt(this.square.getY());
                  b.putInt(this.square.getZ());
                  int i = this.square.getObjects().indexOf(this);
                  if (i != -1) {
                     b.putByte(i);
                  } else {
                     b.putByte(this.square.getObjects().size());
                  }

                  b.putBoolean(true);
                  b.putBoolean(this.activated);
                  PacketType.SyncIsoObject.send(connection);
               }
            }
         } else if (GameClient.client && !bRemote) {
            ByteBufferWriter b = GameClient.connection.startPacket();
            PacketType.SyncIsoObject.doPacket(b);
            this.syncIsoObjectSend(b);
            PacketType.SyncIsoObject.send(GameClient.connection);
         } else if (bRemote) {
            if (val == 1) {
               this.switchLight(true);
               this.activated = true;
            } else {
               this.switchLight(false);
               this.activated = false;
            }
         }

         this.flagForHotSave();
      }
   }

   public void update() {
      if (GameServer.server || !GameClient.client) {
         boolean serverUpdateClients = false;
         if (!this.activated) {
            this.lastMinuteStamp = -1L;
         }

         if (!this.lightRoom && this.canBeModified && this.activated) {
            if (this.lastMinuteStamp == -1L) {
               this.lastMinuteStamp = GameTime.instance.getMinutesStamp();
            }

            if (GameTime.instance.getMinutesStamp() > this.lastMinuteStamp) {
               if (this.bulbBurnMinutes == -1) {
                  int elecDays = SandboxOptions.instance.getElecShutModifier() * 24 * 60;
                  if (this.square.isNoPower()) {
                     elecDays = 0;
                  }

                  if (this.lastMinuteStamp < elecDays) {
                     this.bulbBurnMinutes = (int)this.lastMinuteStamp;
                  } else {
                     this.bulbBurnMinutes = elecDays;
                  }
               }

               long diff = GameTime.instance.getMinutesStamp() - this.lastMinuteStamp;
               this.lastMinuteStamp = GameTime.instance.getMinutesStamp();
               boolean doLightBulbUpdate = false;
               boolean electricityAround = this.hasElectricityAround();
               boolean hasBatteryPower = this.useBattery && this.hasBattery && this.power > 0.0F;
               if (hasBatteryPower || !this.useBattery && electricityAround) {
                  doLightBulbUpdate = true;
               }

               double bulbLife = SandboxOptions.instance.lightBulbLifespan.getValue();
               if (bulbLife <= 0.0) {
                  doLightBulbUpdate = false;
               }

               if (this.activated && this.hasLightBulb() && doLightBulbUpdate) {
                  this.bulbBurnMinutes = (int)(this.bulbBurnMinutes + diff);
               }

               this.nextBreakUpdate = (int)(this.nextBreakUpdate - diff);
               if (this.nextBreakUpdate <= 0) {
                  if (this.activated && this.hasLightBulb() && doLightBulbUpdate) {
                     int multiplier = (int)(1000.0 * bulbLife);
                     if (multiplier < 1) {
                        multiplier = 1;
                     }

                     int rand = Rand.Next(0, multiplier);
                     int thresh = this.bulbBurnMinutes / 10000;
                     if (rand < thresh) {
                        this.bulbBurnMinutes = 0;
                        this.setActive(false, true, true);
                        this.bulbItem = null;
                        IsoWorld.instance.getFreeEmitter().playSound("LightbulbBurnedOut", this.square);
                        serverUpdateClients = true;
                        if (Core.debug) {
                           System.out.println("broke bulb at x=" + this.getX() + ", y=" + this.getY() + ", z=" + this.getZ());
                        }
                     }
                  }

                  this.nextBreakUpdate = 60;
               }

               if (this.activated && hasBatteryPower && this.hasLightBulb()) {
                  float pmod = this.power - this.power % 0.01F;
                  this.power = this.power - this.delta * (float)diff;
                  if (this.power < 0.0F) {
                     this.power = 0.0F;
                  }

                  if (diff == 1L || this.power < pmod) {
                     serverUpdateClients = true;
                  }
               }
            }

            if (this.useBattery && this.activated && (this.power <= 0.0F || !this.hasBattery)) {
               this.power = 0.0F;
               this.setActive(false, true, true);
               serverUpdateClients = true;
            }
         }

         if (this.activated && !this.hasLightBulb()) {
            this.setActive(false, true, true);
            serverUpdateClients = true;
         }

         if (serverUpdateClients && GameServer.server) {
            this.syncCustomizedSettings(null);
         }
      }
   }

   public boolean isActivated() {
      return this.activated;
   }

   public void setActivated(boolean val) {
      this.activated = val;
   }

   public void addToWorld() {
      if (!this.activated) {
         this.lastMinuteStamp = -1L;
      }

      if (!this.lightRoom && !this.lights.isEmpty()) {
         for (int i = 0; i < this.lights.size(); i++) {
            IsoWorld.instance.currentCell.getLamppostPositions().add(this.lights.get(i));
         }
      }

      if (this.getCell() != null && this.canBeModified && !this.lightRoom && (!GameServer.server && !GameClient.client || GameServer.server)) {
         this.getCell().addToStaticUpdaterObjectList(this);
      }

      this.checkAmbientSound();
      if (this.couldBePoweredByGenerator() && this.getChunk() != null) {
         this.getChunk().addObjectPoweredByGenerator(this);
      }
   }

   public void removeFromWorld() {
      if (!this.lightRoom && !this.lights.isEmpty()) {
         for (int i = 0; i < this.lights.size(); i++) {
            this.lights.get(i).setActive(false);
            IsoWorld.instance.currentCell.removeLamppost(this.lights.get(i));
         }

         this.lights.clear();
      }

      if (this.square != null && this.lightRoom) {
         IsoRoom room = this.square.getRoom();
         if (room != null) {
            room.lightSwitches.remove(this);
         }
      }

      this.clearOnOverlay();
      super.removeFromWorld();
   }

   public boolean couldBePoweredByGenerator() {
      return !this.streetLight;
   }

   public float getGeneratorPowerConsumption() {
      return this.isActivated() && !this.streetLight ? 0.002F : 0.0F;
   }

   public static void chunkLoaded(IsoChunk chunk) {
      ArrayList<IsoRoom> rooms = new ArrayList<>();

      for (int x = 0; x < 8; x++) {
         for (int y = 0; y < 8; y++) {
            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
               IsoGridSquare sq = chunk.getGridSquare(x, y, z);
               if (sq != null) {
                  IsoRoom r = sq.getRoom();
                  if (r != null && r.hasLightSwitches() && !rooms.contains(r)) {
                     rooms.add(r);
                  }
               }
            }
         }
      }

      for (int i = 0; i < rooms.size(); i++) {
         IsoRoom room = rooms.get(i);
         room.createLights(room.def.lightsActive);

         for (int j = 0; j < room.roomLights.size(); j++) {
            IsoRoomLight roomLight = (IsoRoomLight)room.roomLights.get(j);
            if (!chunk.roomLights.contains(roomLight)) {
               chunk.roomLights.add(roomLight);
            }
         }
      }
   }

   public ArrayList<IsoLightSource> getLights() {
      return this.lights;
   }

   // pzopt: tileRecordLightOrder. shouldShowOnOverlay's answer for a seen square, without asking isSeen (the square's lazy light
   // refresh): false here means false whatever the square's visibility, so the defer rule need not ask (pzopt.TileRecord).
   public boolean pzoptOverlayIfSeen() { // pzopt
      boolean electricityAround = this.hasElectricityAround(); // pzopt
      boolean hasBatteryPower = this.useBattery && this.hasBattery && this.power > 0.0F; // pzopt
      boolean bShowOverlay = this.isActivated() && (hasBatteryPower || !this.useBattery && electricityAround); // pzopt
      if (this.lightRoom) { // pzopt
         bShowOverlay = !bShowOverlay && electricityAround; // pzopt
      } // pzopt
      if (this.streetLight && (GameTime.getInstance().getNight() < 0.5F || !this.hasGridPower())) { // pzopt
         bShowOverlay = false; // pzopt
      } // pzopt
      return bShowOverlay; // pzopt
   } // pzopt

   public boolean shouldShowOnOverlay() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      if (this.getSquare() != null && this.getSquare().isSeen(playerIndex)) {
         boolean electricityAround = this.hasElectricityAround();
         boolean hasBatteryPower = this.useBattery && this.hasBattery && this.power > 0.0F;
         boolean bShowOverlay = this.isActivated() && (hasBatteryPower || !this.useBattery && electricityAround);
         if (this.lightRoom) {
            bShowOverlay = !bShowOverlay && electricityAround;
         }

         if (this.streetLight && (GameTime.getInstance().getNight() < 0.5F || !this.hasGridPower())) {
            bShowOverlay = false;
         }

         return bShowOverlay;
      } else {
         return false;
      }
   }
}
