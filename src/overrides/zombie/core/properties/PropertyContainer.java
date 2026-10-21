package zombie.core.properties;

import gnu.trove.map.hash.TShortShortHashMap;
import gnu.trove.set.TShortSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import zombie.UsedFromLua;
import zombie.core.TilePropertyAliasMap;
import zombie.core.TilePropertyAliasMap.TileProperty;
import zombie.core.math.PZMath;
import zombie.iso.IsoDirections;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.util.StringUtils;

@UsedFromLua
public final class PropertyContainer extends TShortShortHashMap {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.properties.PropertyContainer");
   }

   private long spriteFlags1;
   private long spriteFlags2;
   private short[] keyArray;
   public static List<Object> sorted = Collections.synchronizedList(new ArrayList<>());
   private byte surface;
   private volatile byte surfaceFlags; // pzopt: the done flag is published last, so it has to carry the happens-before for the six values beside it (a plain store gives a reader none); free on the read path on x86, one fence per publish and per invalidation
   private short stackReplaceTileOffset;
   private byte itemHeight;
   private IsoDirections slopedSurfaceDirection;
   private byte slopedSurfaceHeightMin;
   private byte slopedSurfaceHeightMax;
   private static final byte SURFACE_VALID = 1;
   private static final byte SURFACE_ISOFFSET = 2;
   private static final byte SURFACE_ISTABLE = 4;
   private static final byte SURFACE_ISTABLETOP = 8;

   public PropertyContainer() {
      super(10, 0.5F, (short)-1, (short)-1);
      this.setAutoCompactionFactor(0.0F);
   }

   public void CreateKeySet() {
      if (this.isEmpty()) {
         this.keyArray = null;
      } else {
         TShortSet keySet = this.keySet();
         this.keyArray = keySet.toArray();
      }
   }

   private void recreateKeyArray() {
      if (this.keyArray != null) {
         this.CreateKeySet();
      }
   }

   public void AddProperties(PropertyContainer other) {
      if (other.keyArray != null) {
         boolean recreateKeyArray = false;

         for (int i1 = 0; i1 < other.keyArray.length; i1++) {
            short key = other.keyArray[i1];
            short oldValue = this.put(key, other.get(key));
            recreateKeyArray |= oldValue == this.getNoEntryValue();
         }

         if (recreateKeyArray) {
            this.recreateKeyArray();
         }
      }

      this.spriteFlags1 = this.spriteFlags1 | other.spriteFlags1;
      this.spriteFlags2 = this.spriteFlags2 | other.spriteFlags2;
   }

   /** pzopt: handoffRecalcFast stale check, the flags and the (key, value) entries sorted, into out from off; the count, -1 if too many. */
   public int pzoptKey(long[] out, int off) { // pzopt
      if (off + 2 + this.size() > out.length) { // pzopt
         return -1; // pzopt
      } // pzopt
      out[off] = this.spriteFlags1; // pzopt
      out[off + 1] = this.spriteFlags2; // pzopt
      int n = off + 2; // pzopt
      byte[] states = this._states; // pzopt
      short[] keys = this._set; // pzopt
      short[] vals = this._values; // pzopt
      for (int i = 0; i < states.length; i++) { // pzopt
         if (states[i] == 1) { // pzopt: FULL
            out[n++] = (long)keys[i] << 16 | vals[i] & 0xFFFFL; // pzopt
         } // pzopt
      } // pzopt
      java.util.Arrays.sort(out, off + 2, n); // pzopt
      return n - off; // pzopt
   } // pzopt

   /** pzopt: devHandoffRecalcCheck, a copy of every field (restored by pzoptSetFrom). */
   public PropertyContainer pzoptCopy() { // pzopt
      PropertyContainer c = new PropertyContainer(); // pzopt
      c.pzoptSetFrom(this); // pzopt
      return c; // pzopt
   } // pzopt

   /** pzopt: devHandoffRecalcCheck, this container becomes an exact copy of other. */
   public void pzoptSetFrom(PropertyContainer other) { // pzopt
      this.clear(); // pzopt
      byte[] states = other._states; // pzopt
      for (int i = 0; i < states.length; i++) { // pzopt
         if (states[i] == 1) { // pzopt
            this.put(other._set[i], other._values[i]); // pzopt
         } // pzopt
      } // pzopt
      this.spriteFlags1 = other.spriteFlags1; // pzopt
      this.spriteFlags2 = other.spriteFlags2; // pzopt
      this.keyArray = other.keyArray == null ? null : other.keyArray.clone(); // pzopt
      this.surface = other.surface; // pzopt
      this.stackReplaceTileOffset = other.stackReplaceTileOffset; // pzopt
      this.itemHeight = other.itemHeight; // pzopt
      this.slopedSurfaceDirection = other.slopedSurfaceDirection; // pzopt
      this.slopedSurfaceHeightMin = other.slopedSurfaceHeightMin; // pzopt
      this.slopedSurfaceHeightMax = other.slopedSurfaceHeightMax; // pzopt
      this.surfaceFlags = other.surfaceFlags; // pzopt
   } // pzopt

   public void Clear() {
      this.spriteFlags1 = 0L;
      this.spriteFlags2 = 0L;
      this.clear();
      this.keyArray = null;
      this.surfaceFlags = (byte)(this.surfaceFlags & -2);
   }

   public boolean has(IsoFlagType flag) {
      long flags = flag.index() < 64 ? this.spriteFlags1 : this.spriteFlags2;
      return (flags & 1L << (flag.index() & 63)) != 0L;
   }

   public boolean has(Double flag) {
      return this.has(IsoFlagType.fromIndex(flag.intValue()));
   }

   public void set(String tilePropertyKey) {
      this.set(tilePropertyKey, "");
   }

   public void set(IsoPropertyType type, String propValue) {
      this.set(type.getName(), propValue);
   }

   public void set(String propName, String propValue) {
      this.set(propName, propValue, true);
   }

   public void set(IsoPropertyType type, String propValue, boolean checkIsoFlagType) {
      this.set(type.getName(), propValue, checkIsoFlagType);
   }

   public void set(String propName, String propValue, boolean checkIsoFlagType) {
      if (propName != null) {
         if (checkIsoFlagType) {
            IsoFlagType e = IsoFlagType.FromString(propName);
            if (e != IsoFlagType.MAX) {
               this.set(e);
               return;
            }
         }

         int p = TilePropertyAliasMap.instance.getIDFromPropertyName(propName);
         if (p != -1) {
            int v = TilePropertyAliasMap.instance.getIDFromPropertyValue(p, propValue);
            this.surfaceFlags = (byte)(this.surfaceFlags & -2);
            short oldValue = this.put((short)p, (short)v);
            if (oldValue == this.getNoEntryValue()) {
               this.recreateKeyArray();
            }
         }
      }
   }

   public void set(IsoFlagType flag) {
      if (flag.index() / 64 == 0) {
         this.spriteFlags1 = this.spriteFlags1 | 1L << flag.index() % 64;
      } else {
         this.spriteFlags2 = this.spriteFlags2 | 1L << flag.index() % 64;
      }
   }

   public void set(IsoFlagType flag, String ignored) {
      this.set(flag);
   }

   public void unset(String propName) {
      int p = TilePropertyAliasMap.instance.getIDFromPropertyName(propName);
      short oldValue = this.remove((short)p);
      if (oldValue != this.getNoEntryValue()) {
         this.recreateKeyArray();
      }
   }

   public void unset(IsoFlagType flag) {
      if (flag.index() / 64 == 0) {
         this.spriteFlags1 = this.spriteFlags1 & ~(1L << flag.index() % 64);
      } else {
         this.spriteFlags2 = this.spriteFlags2 & ~(1L << flag.index() % 64);
      }
   }

   public String get(IsoPropertyType type) {
      if (pzopt.Config.PROP_ID_CACHE) { // pzopt: propIdCache
         int pzoptP = pzopt.PropIds.id(type); // pzopt: = get(name)
         return !this.containsKey((short)pzoptP) ? null : TilePropertyAliasMap.instance.getPropertyValueString(pzoptP, this.get((short)pzoptP)); // pzopt
      } // pzopt
      return this.get(type.getName());
   }

   public String get(String name) {
      int p = TilePropertyAliasMap.instance.getIDFromPropertyName(name);
      return !this.containsKey((short)p) ? null : TilePropertyAliasMap.instance.getPropertyValueString(p, this.get((short)p));
   }

   public boolean propertyEquals(IsoPropertyType type, String value) {
      return StringUtils.equalsIgnoreCase(this.get(type), value);
   }

   public boolean propertyEquals(String name, String value) {
      return StringUtils.equalsIgnoreCase(this.get(name), value);
   }

   public boolean has(IsoPropertyType isoPropertyType) {
      if (pzopt.Config.PROP_ID_CACHE) { // pzopt: propIdCache, the id kept per type for the alias map's generation (pzopt.PropIds)
         return this.containsKey((short)pzopt.PropIds.id(isoPropertyType)); // pzopt: = has(name): containsKey((short)getIDFromPropertyName(name))
      } // pzopt
      return this.has(isoPropertyType.getName());
   }

   public boolean has(IsoPropertyType... isoPropertyType) {
      for (int i = 0; i < isoPropertyType.length; i++) {
         if (this.has(isoPropertyType[i])) {
            return true;
         }
      }

      return false;
   }

   public boolean has(String isoPropertyType) {
      int p = TilePropertyAliasMap.instance.getIDFromPropertyName(isoPropertyType);
      return this.containsKey((short)p);
   }

   public ArrayList<IsoFlagType> getFlagsList() {
      ArrayList<IsoFlagType> ret = new ArrayList<>();

      for (int i = 0; i < 64; i++) {
         if ((this.spriteFlags1 & 1L << i) != 0L) {
            ret.add(IsoFlagType.fromIndex(i));
         }
      }

      for (int i = 0; i < 64; i++) {
         if ((this.spriteFlags2 & 1L << i) != 0L) {
            ret.add(IsoFlagType.fromIndex(64 + i));
         }
      }

      return ret;
   }

   public ArrayList<String> getPropertyNames() {
      ArrayList<String> list = new ArrayList<>();
      TShortSet s = this.keySet();
      s.forEach(i -> {
         list.add(((TileProperty)TilePropertyAliasMap.instance.properties.get(i)).propertyName);
         return true;
      });
      Collections.sort(list);
      return list;
   }

   private void initSurface() {
      if ((this.surfaceFlags & 1) == 0) {
         pzopt.SurfaceInit s = pzopt.SurfaceInit.scratch(); // pzopt: the walk accumulates off the object, stock set the done flag here and filled the fields afterwards — a second thread saw "done" over the reset defaults
         if (pzopt.Config.PROPERTY_SURFACE_NOALLOC) { // pzopt: forEachEntry's own loop without the capturing lambda (C1 allocates one per call)
            byte[] states = this._states; // pzopt
            short[] keys = this._set; // pzopt
            short[] values = this._values; // pzopt
            for (int k = keys.length; k-- > 0; ) { // pzopt: same order as forEachEntry
               if (states[k] == 1) { // pzopt: FULL
                  this.pzoptSurfaceEntry(keys[k], values[k], s); // pzopt
               } // pzopt
            } // pzopt
         } else { // pzopt
            this.forEachEntry((i, i1) -> { // pzopt
               this.pzoptSurfaceEntry(i, i1, s); // pzopt
               return true; // pzopt
            }); // pzopt
         } // pzopt
         this.surface = s.surface; // pzopt: one publish at the end, identical values to stock's in-walk writes
         this.stackReplaceTileOffset = s.stackReplaceTileOffset; // pzopt
         this.itemHeight = s.itemHeight; // pzopt
         this.slopedSurfaceDirection = s.slopedSurfaceDirection; // pzopt
         this.slopedSurfaceHeightMin = s.slopedSurfaceHeightMin; // pzopt
         this.slopedSurfaceHeightMax = s.slopedSurfaceHeightMax; // pzopt
         this.surfaceFlags = (byte)(s.flags | 1); // pzopt: the done flag LAST, in the same store as the accumulated bits (stock OR-ed each bit into the field, a read-modify-write that loses one when two walks overlap); volatile, so a reader seeing it set sees the six writes above
      }
   }

   private void pzoptSurfaceEntry(short i, short i1, pzopt.SurfaceInit s) { // pzopt: the former lambda body of initSurface, writing the accumulator instead of the fields
            List<TileProperty> all = TilePropertyAliasMap.instance.properties; // pzopt: hoisted for the range guard below
            if (i < 0 || i >= all.size()) { // pzopt: a torn view of the map paired a FULL state byte with the no-entry key (-1 here); stock handed it to the list and the live route died on "Index -1 out of bounds for length 235"
               pzopt.UpdateBatch.onSurfacePropertyRaceSkipped(); // pzopt
               return; // pzopt: transient, the next call re-derives the value off an untorn view
            } // pzopt
            TileProperty p = (TileProperty)all.get(i); // pzopt: through the hoisted list
            String key = p.propertyName;
            if (i1 < 0 || i1 >= p.possibleValues.size()) { // pzopt: the value half of the same torn read (the no-entry value is -1 too)
               pzopt.UpdateBatch.onSurfacePropertyRaceSkipped(); // pzopt
               return; // pzopt
            } // pzopt
            String val = (String)p.possibleValues.get(i1);
            switch (key) {
               case "Surface":
                  if (val != null) {
                     try {
                        int pixels = Integer.parseInt(val);
                        if (pixels >= 0 && pixels <= 127) {
                           s.surface = (byte)pixels; // pzopt
                        }
                     } catch (NumberFormatException var11) {
                     }
                  }
                  break;
               case "IsSurfaceOffset":
                  s.flags = (byte)(s.flags | 2); // pzopt
                  break;
               case "IsTable":
                  s.flags = (byte)(s.flags | 4); // pzopt
                  break;
               case "IsTableTop":
                  s.flags = (byte)(s.flags | 8); // pzopt
                  break;
               case "StackReplaceTileOffset":
                  try {
                     s.stackReplaceTileOffset = (short)Integer.parseInt(val); // pzopt
                  } catch (NumberFormatException var10) {
                  }
                  break;
               case "ItemHeight":
                  try {
                     int pixels = Integer.parseInt(val);
                     if (pixels >= 0 && pixels <= 127) {
                        s.itemHeight = (byte)pixels; // pzopt
                     }
                  } catch (NumberFormatException var9) {
                  }
                  break;
               case "SlopedSurfaceDirection":
                  s.slopedSurfaceDirection = IsoDirections.fromString(val); // pzopt
                  break;
               case "SlopedSurfaceHeightMin":
                  s.slopedSurfaceHeightMin = (byte)PZMath.clamp(PZMath.tryParseInt(val, 0), 0, 100); // pzopt
                  break;
               case "SlopedSurfaceHeightMax":
                  s.slopedSurfaceHeightMax = (byte)PZMath.clamp(PZMath.tryParseInt(val, 0), 0, 100); // pzopt
            }

   } // pzopt

   public int getSurface() {
      this.initSurface();
      return this.surface;
   }

   public boolean isSurfaceOffset() {
      this.initSurface();
      return (this.surfaceFlags & 2) != 0;
   }

   public boolean isTable() {
      this.initSurface();
      return (this.surfaceFlags & 4) != 0;
   }

   public boolean isTableTop() {
      this.initSurface();
      return (this.surfaceFlags & 8) != 0;
   }

   public int getStackReplaceTileOffset() {
      this.initSurface();
      return this.stackReplaceTileOffset;
   }

   public int getItemHeight() {
      this.initSurface();
      return this.itemHeight;
   }

   public IsoDirections getSlopedSurfaceDirection() {
      this.initSurface();
      return this.slopedSurfaceDirection;
   }

   public int getSlopedSurfaceHeightMin() {
      this.initSurface();
      return this.slopedSurfaceHeightMin;
   }

   public int getSlopedSurfaceHeightMax() {
      this.initSurface();
      return this.slopedSurfaceHeightMax;
   }

   public static class MostTested {
      public IsoFlagType flag;
      public int count;
   }

   private static class ProfileEntryComparitor implements Comparator<Object> {
      public ProfileEntryComparitor() {
      }

      @Override
      public int compare(Object o1, Object o2) {
         double dist1 = ((PropertyContainer.MostTested)o1).count;
         double dist2 = ((PropertyContainer.MostTested)o2).count;
         if (dist1 > dist2) {
            return -1;
         } else {
            return dist2 > dist1 ? 1 : 0;
         }
      }
   }
}
