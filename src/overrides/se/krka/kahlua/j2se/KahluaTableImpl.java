package se.krka.kahlua.j2se;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import se.krka.kahlua.vm.KahluaUtil;
import se.krka.kahlua.vm.LuaCallFrame;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.core.Core;
import zombie.ui.UIManager;

public final class KahluaTableImpl implements KahluaTable {
   static {
      pzopt.Overrides.onClassLoaded("se.krka.kahlua.j2se.KahluaTableImpl"); // pzopt
   }

   public final Map<Object, Object> delegate;
   private KahluaTable metatable;
   private boolean pzoptMeta; // pzopt: luaIndexCache, some table uses this one as its metatable (a class)
   /** pzopt: uiRetained, bumped by every rawset that changes a value (a UI element whose Lua table changed renders fresh). */
   public int pzoptWrites;
   /** pzopt: uiVerify, the id of the last UiDeps log that recorded a read of this table (one entry per log). */
   public int pzoptDepMark;
   /** pzopt: uiVerify, UiDeps.epoch when created during a recorded UI render (a local of that render, not tracked). */
   public int pzoptDepBorn;
   /** pzopt: uiVerify, this table's slot in the log pzoptDepMark names. */
   public int pzoptDepSlot;
   /** pzopt: uiVerify, the summed write counts of its class chain, valid while pzoptMetaVersion equals pzoptChainVer. */
   public long pzoptChainSum;
   public long pzoptChainVer = -1L;
   private java.util.HashMap<Object, KahluaTableImpl.PzoptChain> pzoptChain; // pzopt: luaIndexCache, key -> what rawget's metatable walk finds from here
   /** pzopt: luaIndexCache, bumped by every write to a table that is some table's metatable (any class change drops every cached walk). */
   public static long pzoptMetaVersion = 1L;
   private KahluaTable reloadReplace;
   private static final byte SBYT_NO_SAVE = -1;
   private static final byte SBYT_STRING = 0;
   private static final byte SBYT_DOUBLE = 1;
   private static final byte SBYT_TABLE = 2;
   private static final byte SBYT_BOOLEAN = 3;

   public KahluaTableImpl(Map<Object, Object> delegate) {
      this.delegate = delegate;
      if (pzopt.UiDeps.on) { // pzopt: uiVerify, a table created inside a recorded UI render
         pzopt.UiDeps.created(this); // pzopt: uiVerify
      } // pzopt: uiVerify
   }

   public void setMetatable(KahluaTable metatable) {
      this.metatable = metatable;
      if (metatable instanceof KahluaTableImpl pzoptMt) { // pzopt: luaIndexCache
         pzoptMt.pzoptMeta = true; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      pzoptMetaVersion++; // pzopt: luaIndexCache, an ancestry change
   }

   public KahluaTable getMetatable() {
      return this.metatable;
   }

   public int size() {
      return this.delegate.size();
   }

   public void rawset(Object key, Object value) {
      if (this.pzoptMeta) { // pzopt: luaIndexCache, a class table changed
         pzoptMetaVersion++; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      if (this.reloadReplace != null) {
         this.reloadReplace.rawset(key, value);
      }

      Object lastVal = null;
      if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key)) {
         lastVal = this.rawget(key);
      }

      if (value == null) {
         if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key) && lastVal != null) {
            UIManager.debugBreakpoint(LuaManager.thread.currentfile, LuaManager.thread.lastLine);
         }

         if (this.delegate.remove(key) != null) { // pzopt: uiRetained, a value removed
            this.pzoptWrites++; // pzopt: uiRetained
            if (pzopt.UiDeps.on) { // pzopt: uiVerify
               pzopt.UiDeps.write(this); // pzopt: uiVerify
            } // pzopt: uiVerify
         } // pzopt: uiRetained
      } else {
         if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasDataBreakpoint(this, key) && !value.equals(lastVal)) {
            int a = GlobalObject.getCurrentCoroutine().currentCallFrame().pc;
            if (a < 0) {
               a = 0;
            }

            UIManager.debugBreakpoint(LuaManager.thread.currentfile, GlobalObject.getCurrentCoroutine().currentCallFrame().closure.prototype.lines[a] - 1);
         }

         Object pzoptPrev = this.delegate.put(key, value); // pzopt: uiRetained, the previous value comes back for free
         if (pzoptPrev != value && (pzoptPrev == null || !pzoptPrev.equals(value))) { // pzopt: uiRetained
            this.pzoptWrites++; // pzopt: uiRetained
            if (pzopt.UiDeps.on) { // pzopt: uiVerify
               pzopt.UiDeps.write(this); // pzopt: uiVerify
            } // pzopt: uiVerify
         } // pzopt: uiRetained
      }
   }

   public Object rawget(Object key) {
      Object pzoptV = this.pzoptRawget(key); // pzopt: uiVerify, the stock lookup below; a recorded UI render's read is logged with its answer
      if (pzopt.UiDeps.on) { // pzopt: uiVerify
         pzopt.UiDeps.read(this, key, pzoptV); // pzopt: uiVerify
      } // pzopt: uiVerify
      return pzoptV; // pzopt: uiVerify
   } // pzopt: uiVerify

   /** pzopt: uiVerify, stock rawget(Object) (its body unchanged). */
   private Object pzoptRawget(Object key) {
      if (this.reloadReplace != null) {
         return this.reloadReplace.rawget(key);
      }

      if (key == null) {
         return null;
      }

      if (Core.debug && LuaManager.thread != null && LuaManager.thread.hasReadDataBreakpoint(this, key)) {
         int a = GlobalObject.getCurrentCoroutine().currentCallFrame().pc;
         if (a < 0) {
            a = 0;
         }

         UIManager.debugBreakpoint(LuaManager.thread.currentfile, GlobalObject.getCurrentCoroutine().currentCallFrame().closure.prototype.lines[a] - 1);
      }

      // pzopt: one hash lookup instead of containsKey + get. rawset never stores a null value (a nil assignment
      // removes the key), so a null from get means the key is absent, which is exactly the stock containsKey test.
      Object pzoptValue = this.delegate.get(key);
      if (pzoptValue == null && this.metatable instanceof KahluaTableImpl pzoptMt && pzopt.Config.LUA_INDEX_CACHE && !Core.debug) { // pzopt: luaIndexCache
         return pzoptMt.pzoptChainGet(key); // pzopt: luaIndexCache, one lookup for the class walk
      } // pzopt: luaIndexCache
      return pzoptValue == null && this.metatable != null ? this.metatable.rawget(key) : pzoptValue;
   }

   /** pzopt: luaIndexCache, one cached result of this.rawget(key) (this table and its metatable walk). */
   static final class PzoptChain {
      Object value; // pzopt: luaIndexCache
      long version; // pzopt: luaIndexCache
   }

   /** pzopt: luaIndexCache, this.rawget(key) remembered until any class table changes. */
   private Object pzoptChainGet(Object key) {
      if (this.reloadReplace != null) { // pzopt: luaIndexCache
         return this.rawget(key); // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      java.util.HashMap<Object, KahluaTableImpl.PzoptChain> m = this.pzoptChain; // pzopt: luaIndexCache
      if (m == null) { // pzopt: luaIndexCache
         m = new java.util.HashMap<>(); // pzopt: luaIndexCache
         this.pzoptChain = m; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      KahluaTableImpl.PzoptChain e = m.get(key); // pzopt: luaIndexCache
      long v = pzoptMetaVersion; // pzopt: luaIndexCache
      if (e != null && e.version == v) { // pzopt: luaIndexCache
         return e.value; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      Object value = this.rawget(key); // pzopt: luaIndexCache, the stock walk
      if (e == null) { // pzopt: luaIndexCache
         if (m.size() > 4096) { // pzopt: luaIndexCache, a table indexed by many distinct keys: stop caching new ones
            return value; // pzopt: luaIndexCache
         } // pzopt: luaIndexCache
         e = new KahluaTableImpl.PzoptChain(); // pzopt: luaIndexCache
         m.put(key, e); // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      e.value = value; // pzopt: luaIndexCache
      e.version = v; // pzopt: luaIndexCache
      return value; // pzopt: luaIndexCache
   }

   public void rawset(int key, Object value) {
      this.rawset(KahluaUtil.toDouble(key), value);
   }

   public String rawgetStr(Object key) {
      return (String)this.rawget(key);
   }

   public KahluaTableImpl rawgetTable(Object key) {
      return (KahluaTableImpl)this.rawget(key);
   }

   public int rawgetInt(Object key) {
      Object value = this.rawget(key);
      if (value instanceof Double doubleKey) {
         return doubleKey.intValue();
      } else {
         return value instanceof Integer intKey ? intKey : -1;
      }
   }

   public boolean rawgetBool(Object key) {
      return this.rawget(key) instanceof Boolean ? (Boolean)this.rawget(key) : false;
   }

   public float rawgetFloat(Object key) {
      Object value = this.rawget(key);
      if (value instanceof Double d) {
         return d.floatValue();
      } else {
         return value instanceof Float f ? f : -1.0F;
      }
   }

   public float tryGetFloat(Object key, float defaultValue) {
      return this.rawget(key) instanceof Double d ? d.floatValue() : defaultValue;
   }

   public Object rawget(int key) {
      return this.rawget(KahluaUtil.toDouble(key));
   }

   public int len() {
      int pzoptLen = KahluaUtil.len(this, 0, 2 * this.delegate.size()); // pzopt: uiVerify
      if (pzopt.UiDeps.on) { // pzopt: uiVerify, a recorded UI render read the length
         pzopt.UiDeps.read(this, pzopt.UiDeps.LEN, pzoptLen); // pzopt: uiVerify
      } // pzopt: uiVerify
      return pzoptLen; // pzopt: uiVerify
   }

   public KahluaTableIterator iterator() {
      if (pzopt.UiDeps.on) { // pzopt: uiVerify, a recorded UI render walks the table
         pzopt.UiDeps.read(this, pzopt.UiDeps.ITER, null); // pzopt: uiVerify
      } // pzopt: uiVerify
      final Object[] keys = this.delegate.isEmpty() ? null : this.delegate.keySet().toArray();
      return new KahluaTableIterator() {
         private Object curKey;
         private Object curValue;
         private int keyIndex;

         public int call(LuaCallFrame callFrame, int nArguments) {
            return this.advance() ? callFrame.push(this.getKey(), this.getValue()) : 0;
         }

         public boolean advance() {
            if (keys != null && this.keyIndex < keys.length) {
               this.curKey = keys[this.keyIndex];
               this.curValue = KahluaTableImpl.this.delegate.get(this.curKey);
               this.keyIndex++;
               return true;
            } else {
               this.curKey = null;
               this.curValue = null;
               return false;
            }
         }

         public Object getKey() {
            return this.curKey;
         }

         public Object getValue() {
            return this.curValue;
         }
      };
   }

   public boolean isEmpty() {
      return this.delegate.isEmpty();
   }

   public void wipe() {
      if (this.pzoptMeta) { // pzopt: luaIndexCache
         pzoptMetaVersion++; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      this.pzoptWrites++; // pzopt: uiRetained
      if (pzopt.UiDeps.on) { // pzopt: uiVerify
         pzopt.UiDeps.write(this); // pzopt: uiVerify
      } // pzopt: uiVerify
      this.delegate.clear();
   }

   @Override
   public String toString() {
      return this.rawget("Type") instanceof String Type ? Type + " 0x" + System.identityHashCode(this) : "table 0x" + System.identityHashCode(this);
   }

   public void save(ByteBuffer output) {
      KahluaTableIterator it = this.iterator();
      int count = 0;

      while (it.advance()) {
         if (canSave(it.getKey(), it.getValue())) {
            count++;
         }
      }

      it = this.iterator();
      output.putInt(count);

      while (it.advance()) {
         byte keyByte = getKeyByte(it.getKey());
         byte valueByte = getValueByte(it.getValue());
         if (keyByte != -1 && valueByte != -1) {
            this.save(output, keyByte, it.getKey());
            this.save(output, valueByte, it.getValue());
         }
      }
   }

   private void save(ByteBuffer output, byte sbyt, Object o) throws RuntimeException {
      output.put(sbyt);
      if (sbyt == 0) {
         GameWindow.WriteString(output, (String)o);
      } else if (sbyt == 1) {
         output.putDouble((Double)o);
      } else if (sbyt == 3) {
         output.put((byte)((Boolean)o ? 1 : 0));
      } else {
         if (sbyt != 2) {
            throw new RuntimeException("invalid lua table type " + sbyt);
         }

         ((KahluaTableImpl)o).save(output);
      }
   }

   public void save(DataOutputStream output) throws IOException {
      KahluaTableIterator it = this.iterator();
      int count = 0;

      while (it.advance()) {
         if (canSave(it.getKey(), it.getValue())) {
            count++;
         }
      }

      it = this.iterator();
      output.writeInt(count);

      while (it.advance()) {
         byte keyByte = getKeyByte(it.getKey());
         byte valueByte = getValueByte(it.getValue());
         if (keyByte != -1 && valueByte != -1) {
            this.save(output, keyByte, it.getKey());
            this.save(output, valueByte, it.getValue());
         }
      }
   }

   private void save(DataOutputStream output, byte sbyt, Object o) throws IOException, RuntimeException {
      output.writeByte(sbyt);
      if (sbyt == 0) {
         GameWindow.WriteString(output, (String)o);
      } else if (sbyt == 1) {
         output.writeDouble((Double)o);
      } else if (sbyt == 3) {
         output.writeByte((Boolean)o ? 1 : 0);
      } else {
         if (sbyt != 2) {
            throw new RuntimeException("invalid lua table type " + sbyt);
         }

         ((KahluaTableImpl)o).save(output);
      }
   }

   public void load(ByteBuffer input, int WorldVersion) {
      int count = input.getInt();
      this.wipe();
      if (WorldVersion >= 25) {
         for (int n = 0; n < count; n++) {
            byte keyByte = input.get();
            Object key = this.load(input, WorldVersion, keyByte);
            byte valueByte = input.get();
            Object value = this.load(input, WorldVersion, valueByte);
            this.rawset(key, value);
         }
      } else {
         for (int n = 0; n < count; n++) {
            byte valueByte = input.get();
            String key = GameWindow.ReadString(input);
            Object value = this.load(input, WorldVersion, valueByte);
            this.rawset(key, value);
         }
      }
   }

   public Object load(ByteBuffer input, int WorldVersion, byte sbyt) throws RuntimeException {
      if (sbyt == 0) {
         return GameWindow.ReadString(input);
      } else if (sbyt == 1) {
         return input.getDouble();
      } else if (sbyt == 3) {
         return input.get() != 0;
      } else if (sbyt == 2) {
         KahluaTableImpl v = (KahluaTableImpl)LuaManager.platform.newTable();
         v.load(input, WorldVersion);
         return v;
      } else {
         throw new RuntimeException("invalid lua table type " + sbyt);
      }
   }

   public void load(DataInputStream input, int WorldVersion) throws IOException {
      int count = input.readInt();
      if (WorldVersion >= 25) {
         for (int n = 0; n < count; n++) {
            byte keyByte = input.readByte();
            Object key = this.load(input, WorldVersion, keyByte);
            byte valueByte = input.readByte();
            Object value = this.load(input, WorldVersion, valueByte);
            this.rawset(key, value);
         }
      } else {
         for (int n = 0; n < count; n++) {
            byte valueByte = input.readByte();
            String key = GameWindow.ReadString(input);
            Object value = this.load(input, WorldVersion, valueByte);
            this.rawset(key, value);
         }
      }
   }

   public Object load(DataInputStream input, int WorldVersion, byte sbyt) throws IOException, RuntimeException {
      if (sbyt == 0) {
         return GameWindow.ReadString(input);
      } else if (sbyt == 1) {
         return input.readDouble();
      } else if (sbyt == 3) {
         return input.readByte() == 1;
      } else if (sbyt == 2) {
         KahluaTableImpl v = (KahluaTableImpl)LuaManager.platform.newTable();
         v.load(input, WorldVersion);
         return v;
      } else {
         throw new RuntimeException("invalid lua table type " + sbyt);
      }
   }

   public String getString(String string) {
      return (String)this.rawget(string);
   }

   public KahluaTableImpl getRewriteTable() {
      return (KahluaTableImpl)this.reloadReplace;
   }

   public void setRewriteTable(Object value) {
      pzoptMetaVersion++; // pzopt: luaIndexCache, a Lua reload redirects this table
      this.reloadReplace = (KahluaTableImpl)value;
   }

   private static byte getKeyByte(Object o) {
      if (o instanceof String) {
         return 0;
      } else {
         return (byte)(o instanceof Double ? 1 : -1);
      }
   }

   private static byte getValueByte(Object o) {
      if (o instanceof String) {
         return 0;
      } else if (o instanceof Double) {
         return 1;
      } else if (o instanceof Boolean) {
         return 3;
      } else {
         return (byte)(o instanceof KahluaTableImpl ? 2 : -1);
      }
   }

   public static boolean canSave(Object key, Object value) {
      return getKeyByte(key) != -1 && getValueByte(value) != -1;
   }
}
