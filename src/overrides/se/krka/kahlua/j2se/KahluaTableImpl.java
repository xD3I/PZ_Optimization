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
   public static long pzoptMtSets; // pzopt: luaMetaVersionNarrow, setmetatable calls on tables no table uses as a metatable
   /** pzopt: luaMetaKeyVersion, a write to a class table bumps its key's bucket here instead of pzoptMetaVersion: a cached walk of another key stays. */
   public static final long[] pzoptKeyVer = new long[1024]; // pzopt: luaMetaKeyVersion
   public static long pzoptMetaWrites; // pzopt: luaMetaKeyVersion, every class-table write (uiVerify's chain sums)
   /** pzopt: luaJitInlineCache, a compiled site remembers a Java object method found through this table: any write to it changes pzoptJitEpoch. */
   public boolean pzoptJitWatched;
   /** pzopt: luaJitInlineCache, bumped by every write to a watched table (and every reload redirect): the compiled sites' remembered Java methods are dropped. */
   public static int pzoptJitEpoch;
   private KahluaTable reloadReplace;
   private static final byte SBYT_NO_SAVE = -1;
   private static final byte SBYT_STRING = 0;
   private static final byte SBYT_DOUBLE = 1;
   private static final byte SBYT_TABLE = 2;
   private static final byte SBYT_BOOLEAN = 3;
   public static final int MAX_NESTING = 256;
   public static final String NESTING_EXCEEDED_MESSAGE = "lua table nesting exceeded";

   public KahluaTableImpl(Map<Object, Object> delegate) {
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual, a table made during a recorded run
         pzopt.LuaJitDual.created(this); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      this.delegate = pzopt.Config.LUA_FAST_TABLE && delegate.getClass() == java.util.LinkedHashMap.class && delegate.isEmpty() ? new pzopt.LuaMap() : delegate; // pzopt: luaFastTable, the platform's new empty LinkedHashMap becomes a LuaMap
      if (pzopt.UiDeps.on) { // pzopt: uiVerify, a table created inside a recorded UI render
         pzopt.UiDeps.created(this); // pzopt: uiVerify
      } // pzopt: uiVerify
   }

   public void setMetatable(KahluaTable metatable) {
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual
         pzopt.LuaJitDual.change(this, "setmetatable", metatable); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      if (this.pzoptJitWatched) { // pzopt: luaJitInlineCache
         pzoptJitEpoch++; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
      this.metatable = metatable;
      if (metatable instanceof KahluaTableImpl pzoptMt) { // pzopt: luaIndexCache
         pzoptMt.pzoptMeta = true; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      if (this.pzoptMeta || !pzopt.LuaJit.META_NARROW) { // pzopt: luaMetaVersionNarrow, only a class table's own metatable is part of a cached chain
         pzoptMetaVersion++; // pzopt: luaIndexCache, an ancestry change
      } else { // pzopt: luaMetaVersionNarrow, an instance: no chain cache changes (the miss IC compares the instance's metatable)
         pzoptMtSets++; // pzopt: luaMetaVersionNarrow, UiDeps' chain sums still see it
      } // pzopt: luaMetaVersionNarrow
   }

   public KahluaTable getMetatable() {
      return this.metatable;
   }

   public int size() {
      return this.delegate.size();
   }

   /** pzopt: luaMetaKeyVersion, a class table's value for key changed (or may have): the cached walks of that key are stale. */
   public static void pzoptMetaChanged(Object key) { // pzopt: luaMetaKeyVersion
      if (pzopt.LuaJit.KEY_VERSION) { // pzopt: luaMetaKeyVersion
         pzoptKeyVer[pzoptKeyBucket(key)]++; // pzopt: luaMetaKeyVersion
         pzoptMetaWrites++; // pzopt: luaMetaKeyVersion
      } else { // pzopt: luaMetaKeyVersion
         pzoptMetaVersion++; // pzopt: luaMetaKeyVersion
      } // pzopt: luaMetaKeyVersion
   } // pzopt: luaMetaKeyVersion

   /** pzopt: luaMetaKeyVersion, keys that are equal have one bucket (equal keys have one hash code). */
   public static int pzoptKeyBucket(Object key) { // pzopt: luaMetaKeyVersion
      int h = key == null ? 0 : key.hashCode(); // pzopt: luaMetaKeyVersion
      return (h ^ (h >>> 16)) & 1023; // pzopt: luaMetaKeyVersion
   } // pzopt: luaMetaKeyVersion

   public void rawset(Object key, Object value) {
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual, a write during a recorded run
         pzopt.LuaJitDual.write(this, key, value); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      if (this.pzoptMeta) { // pzopt: luaIndexCache, a class table changed
         pzoptMetaChanged(key); // pzopt: luaMetaKeyVersion
      } // pzopt: luaIndexCache
      if (this.pzoptJitWatched) { // pzopt: luaJitInlineCache
         pzoptJitEpoch++; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
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
               pzopt.UiDeps.write(this, key, null); // pzopt: uiVerify (the key: lua-stream's order check)
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
         if (!pzopt.UiDeps.safeEquals(pzoptPrev, value)) { // pzopt: uiRetained, a value-changing write (an equals that cannot throw: a game list's does, 2026-10-19)
            this.pzoptWrites++; // pzopt: uiRetained
            if (pzopt.UiDeps.on) { // pzopt: uiVerify
               pzopt.UiDeps.write(this, key, value); // pzopt: uiVerify (the key: lua-stream's order check)
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

   /** pzopt: luaJitNumbers, rawget of the integer key d when it is in the table's leading 1..n run (a LuaMap), else null (then the caller takes the stock path). */
   public Object pzoptSeqGet(double d) { // pzopt: luaJitNumbers
      if (this.reloadReplace == null && this.delegate instanceof pzopt.LuaMap pzoptMap) { // pzopt: luaJitNumbers (LuaJit.getNum logs a recorded UI read)
         return pzoptMap.seqGet(d); // pzopt: luaJitNumbers
      } // pzopt: luaJitNumbers
      return null; // pzopt: luaJitNumbers
   } // pzopt: luaJitNumbers

   /** pzopt: luaJitInlineCache, the LuaMap behind this table when rawget is a plain lookup in it (no reload replacement, no UI read log), else null. */
   public pzopt.LuaMap pzoptJitMap() { // pzopt: luaJitInlineCache
      return this.reloadReplace == null && this.delegate instanceof pzopt.LuaMap pzoptMap ? pzoptMap : null; // pzopt: luaJitInlineCache (LuaJit logs a recorded UI read / write)
   } // pzopt: luaJitInlineCache

   /** pzopt: luaJitInlineCache, rawset of a non-null value over the non-null value at position s of this table's own map (from pzoptJitMap); false = not done, take the stock path. */
   public boolean pzoptJitSetSlot(pzopt.LuaMap pzoptMap, int s, Object value) { // pzopt: luaJitInlineCache
      if (Core.debug) { // pzopt: luaJitInlineCache, data breakpoints
         return false; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual
         pzopt.LuaJitDual.writeSlot(this, pzoptMap, s, value); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      if (this.pzoptMeta) { // pzopt: luaJitInlineCache, as rawset
         pzoptMetaChanged(pzoptMap.keyAt(s)); // pzopt: luaMetaKeyVersion
      } // pzopt: luaJitInlineCache
      if (this.pzoptJitWatched) { // pzopt: luaJitInlineCache
         pzoptJitEpoch++; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
      Object pzoptPrev = pzoptMap.setValueAt(s, value); // pzopt: luaJitInlineCache, LuaMap.put of a present key
      if (!pzopt.UiDeps.safeEquals(pzoptPrev, value)) { // pzopt: luaJitInlineCache, as rawset
         this.pzoptWrites++; // pzopt: luaJitInlineCache
         if (pzopt.UiDeps.on) { // pzopt: luaJitUiDeps, as rawset
            pzopt.UiDeps.write(this); // pzopt: luaJitUiDeps
         } // pzopt: luaJitUiDeps
      } // pzopt: luaJitInlineCache
      return true; // pzopt: luaJitInlineCache
   } // pzopt: luaJitInlineCache

   /** pzopt: luaJitInlineCache, rawget's answer for a non-null key this table's own map does not hold (the metatable part of pzoptRawget, outside Core.debug). */
   public Object pzoptMissGet(Object key) { // pzopt: luaJitInlineCache
      if (this.metatable instanceof KahluaTableImpl pzoptMt && pzopt.Config.LUA_INDEX_CACHE && !Core.debug) { // pzopt: luaJitInlineCache
         return pzoptMt.pzoptChainGet(key); // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
      return this.metatable != null ? this.metatable.rawget(key) : null; // pzopt: luaJitInlineCache
   } // pzopt: luaJitInlineCache

   /**
    * pzopt: luaJitInlineCache, pzoptMissGet through a compiled site's memory of its last metatable's answer for the key: valid
    * while that metatable is this table's and no class table changed (pzoptMetaVersion, the same rule as pzoptChainGet), so a
    * method call on an instance of the site's usual class skips the chain cache's hash lookup.
    */
   /**
    * pzopt: luaJitNilCache. True when KahluaThread.tableget(this, key) is nil (this table's own map already found without
    * the key, its metatable chain without it) and depends only on class tables past this one: every table the stock walk
    * visits after this one, every metatable it asks for __index, are pzoptMeta tables, so a change of any of them moves
    * pzoptMetaVersion or the key versions of key and "__index". False when the walk meets anything else.
    */
   public boolean pzoptNilProof(Object key) { // pzopt: luaJitNilCache
      KahluaTable pzoptCur = this; // pzopt: luaJitNilCache
      boolean pzoptFirst = true; // pzopt: luaJitNilCache
      for (int pzoptI = 100; pzoptI > 0; pzoptI--) { // pzopt: luaJitNilCache, the stock walk's bound
         if (!(pzoptCur instanceof KahluaTableImpl pzoptC) || pzoptC.reloadReplace != null) { // pzopt: luaJitNilCache
            return false; // pzopt: luaJitNilCache
         } // pzopt: luaJitNilCache
         if (!pzoptFirst && (!pzoptC.pzoptMeta || pzoptC.rawget(key) != null)) { // pzopt: luaJitNilCache, an __index table
            return false; // pzopt: luaJitNilCache
         } // pzopt: luaJitNilCache
         pzoptFirst = false; // pzopt: luaJitNilCache
         if (!(pzoptC.metatable instanceof KahluaTableImpl pzoptM)) { // pzopt: luaJitNilCache
            return pzoptC.metatable == null; // pzopt: luaJitNilCache, no metatable: the walk ends in nil
         } // pzopt: luaJitNilCache
         if (!pzoptM.pzoptMeta || pzoptM.reloadReplace != null) { // pzopt: luaJitNilCache
            return false; // pzopt: luaJitNilCache
         } // pzopt: luaJitNilCache
         Object pzoptOp = pzoptM.rawget("__index"); // pzopt: luaJitNilCache, getMetaOp(cur, "__index")
         if (pzoptOp == null) { // pzopt: luaJitNilCache
            return true; // pzopt: luaJitNilCache
         } // pzopt: luaJitNilCache
         pzoptCur = pzoptOp instanceof KahluaTable pzoptT ? pzoptT : null; // pzopt: luaJitNilCache, a function: not provable
      } // pzopt: luaJitNilCache
      return false; // pzopt: luaJitNilCache
   } // pzopt: luaJitNilCache

   /**
    * pzopt: luaJitNilCacheUi. pzoptNilProof's walk, its answer as the reads the stock walk past this table logs for a
    * recorded UI render (table, key, value triples in walk order: each metatable's "__index", each __index table's key),
    * null when pzoptNilProof is false. Called with UiDeps off (its own rawgets log nothing).
    */
   public Object[] pzoptNilChain(Object key) { // pzopt: luaJitNilCacheUi
      Object[] pzoptR = new Object[12]; // pzopt: luaJitNilCacheUi
      int pzoptN = 0; // pzopt: luaJitNilCacheUi
      KahluaTable pzoptCur = this; // pzopt: luaJitNilCacheUi
      boolean pzoptFirst = true; // pzopt: luaJitNilCacheUi
      for (int pzoptI = 100; pzoptI > 0; pzoptI--) { // pzopt: luaJitNilCacheUi, pzoptNilProof's loop
         if (!(pzoptCur instanceof KahluaTableImpl pzoptC) || pzoptC.reloadReplace != null) { // pzopt: luaJitNilCacheUi
            return null; // pzopt: luaJitNilCacheUi
         } // pzopt: luaJitNilCacheUi
         if (!pzoptFirst) { // pzopt: luaJitNilCacheUi
            if (!pzoptC.pzoptMeta || pzoptC.rawget(key) != null) { // pzopt: luaJitNilCacheUi
               return null; // pzopt: luaJitNilCacheUi
            } // pzopt: luaJitNilCacheUi
            if (pzoptN + 3 > pzoptR.length) { // pzopt: luaJitNilCacheUi
               pzoptR = java.util.Arrays.copyOf(pzoptR, pzoptR.length * 2); // pzopt: luaJitNilCacheUi
            } // pzopt: luaJitNilCacheUi
            pzoptR[pzoptN++] = pzoptC; // pzopt: luaJitNilCacheUi, the walk's rawget(cur, key)
            pzoptR[pzoptN++] = key; // pzopt: luaJitNilCacheUi
            pzoptR[pzoptN++] = null; // pzopt: luaJitNilCacheUi
         } // pzopt: luaJitNilCacheUi
         pzoptFirst = false; // pzopt: luaJitNilCacheUi
         if (!(pzoptC.metatable instanceof KahluaTableImpl pzoptM)) { // pzopt: luaJitNilCacheUi
            return pzoptC.metatable == null ? java.util.Arrays.copyOf(pzoptR, pzoptN) : null; // pzopt: luaJitNilCacheUi, no metatable: getMetaOp reads nothing
         } // pzopt: luaJitNilCacheUi
         if (!pzoptM.pzoptMeta || pzoptM.reloadReplace != null) { // pzopt: luaJitNilCacheUi
            return null; // pzopt: luaJitNilCacheUi
         } // pzopt: luaJitNilCacheUi
         Object pzoptOp = pzoptM.rawget("__index"); // pzopt: luaJitNilCacheUi, getMetaOp(cur, "__index")
         if (pzoptN + 3 > pzoptR.length) { // pzopt: luaJitNilCacheUi
            pzoptR = java.util.Arrays.copyOf(pzoptR, pzoptR.length * 2); // pzopt: luaJitNilCacheUi
         } // pzopt: luaJitNilCacheUi
         pzoptR[pzoptN++] = pzoptM; // pzopt: luaJitNilCacheUi
         pzoptR[pzoptN++] = "__index"; // pzopt: luaJitNilCacheUi
         pzoptR[pzoptN++] = pzoptOp; // pzopt: luaJitNilCacheUi
         if (pzoptOp == null) { // pzopt: luaJitNilCacheUi
            return java.util.Arrays.copyOf(pzoptR, pzoptN); // pzopt: luaJitNilCacheUi
         } // pzopt: luaJitNilCacheUi
         pzoptCur = pzoptOp instanceof KahluaTable pzoptT ? pzoptT : null; // pzopt: luaJitNilCacheUi, a function: not provable
      } // pzopt: luaJitNilCacheUi
      return null; // pzopt: luaJitNilCacheUi
   } // pzopt: luaJitNilCacheUi

   public Object pzoptMissGetIc(Object key, pzopt.LuaJitIc ic) { // pzopt: luaJitInlineCache
      if (this.metatable instanceof KahluaTableImpl pzoptMt && pzopt.Config.LUA_INDEX_CACHE && !Core.debug && pzoptMt.reloadReplace == null) { // pzopt: luaJitInlineCache
         pzopt.LuaJitIc.Miss pzoptE = ic.miss; // pzopt: luaJitInlineCache
         long pzoptV = pzoptMetaVersion; // pzopt: luaJitInlineCache
         int pzoptB = ic.bucket; // pzopt: luaMetaKeyVersion, the site's constant key's bucket
         if (pzoptB < 0) { // pzopt: luaMetaKeyVersion
            ic.bucket = pzoptB = pzoptKeyBucket(key); // pzopt: luaMetaKeyVersion
         } // pzopt: luaMetaKeyVersion
         long pzoptKv = pzoptKeyVer[pzoptB]; // pzopt: luaMetaKeyVersion
         if (pzoptE != null && pzoptE.mt() == pzoptMt && pzoptE.version() == pzoptV && pzoptE.kver() == pzoptKv) { // pzopt: luaJitInlineCache, luaMetaKeyVersion
            return pzoptE.value(); // pzopt: luaJitInlineCache
         } // pzopt: luaJitInlineCache
         pzopt.LuaJitIc.Miss pzoptE2 = ic.miss2; // pzopt: luaJitMissPoly, the site's other metatable
         if (pzoptE2 != null && pzoptE2.mt() == pzoptMt && pzoptE2.version() == pzoptV && pzoptE2.kver() == pzoptKv) { // pzopt: luaJitMissPoly
            ic.miss2 = pzoptE; // pzopt: luaJitMissPoly, the most recent first
            ic.miss = pzoptE2; // pzopt: luaJitMissPoly
            return pzoptE2.value(); // pzopt: luaJitMissPoly
         } // pzopt: luaJitMissPoly
         Object pzoptValue = pzoptMt.pzoptChainGet(key); // pzopt: luaJitInlineCache
         if (pzoptV == pzoptMetaVersion && pzoptKv == pzoptKeyVer[pzoptB]) { // pzopt: luaJitInlineCache, luaMetaKeyVersion
            if (pzopt.LuaJit.MISS_POLY && pzoptE != null && pzoptE.mt() != pzoptMt) { // pzopt: luaJitMissPoly
               ic.miss2 = pzoptE; // pzopt: luaJitMissPoly
            } // pzopt: luaJitMissPoly
            ic.miss = new pzopt.LuaJitIc.Miss(pzoptMt, pzoptV, pzoptKv, pzoptValue); // pzopt: luaJitInlineCache
         } // pzopt: luaJitInlineCache
         return pzoptValue; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
      return this.pzoptMissGet(key); // pzopt: luaJitInlineCache
   } // pzopt: luaJitInlineCache

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
      long kver; // pzopt: luaMetaKeyVersion, pzoptKeyVer of the key's bucket
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
      int pzoptB = pzoptKeyBucket(key); // pzopt: luaMetaKeyVersion
      long pzoptKv = pzoptKeyVer[pzoptB]; // pzopt: luaMetaKeyVersion
      if (e != null && e.version == v && e.kver == pzoptKv) { // pzopt: luaIndexCache, luaMetaKeyVersion
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
      e.kver = pzoptKv; // pzopt: luaMetaKeyVersion
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
      int pzoptLen = this.metatable == null && this.reloadReplace == null && this.delegate instanceof pzopt.LuaMap pzoptMap && pzoptMap.isSequence() // pzopt: luaFastTable, keys exactly 1..n: the binary search below ends at n
            ? pzoptMap.size() : KahluaUtil.len(this, 0, 2 * this.delegate.size()); // pzopt: uiVerify
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
      KahluaTableIterator pzoptIt = new KahluaTableIterator() { // pzopt: devLuaJitDual, named to register it
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
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual, an iterator made during a recorded run
         pzopt.LuaJitDual.createdIterator(pzoptIt); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      return pzoptIt; // pzopt: devLuaJitDual
   }

   public boolean isEmpty() {
      return this.delegate.isEmpty();
   }

   public void wipe() {
      if (pzopt.LuaJitDual.ON && pzopt.LuaJitDual.on) { // pzopt: devLuaJitDual
         pzopt.LuaJitDual.change(this, "wipe", null); // pzopt: devLuaJitDual
      } // pzopt: devLuaJitDual
      if (this.pzoptMeta) { // pzopt: luaIndexCache
         pzoptMetaVersion++; // pzopt: luaIndexCache
      } // pzopt: luaIndexCache
      if (this.pzoptJitWatched) { // pzopt: luaJitInlineCache
         pzoptJitEpoch++; // pzopt: luaJitInlineCache
      } // pzopt: luaJitInlineCache
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
      this.load(input, WorldVersion, 0);
   }

   public void load(ByteBuffer input, int WorldVersion, int depth) {
      int count = input.getInt();
      this.wipe();
      if (WorldVersion >= 25) {
         for (int n = 0; n < count; n++) {
            byte keyByte = input.get();
            Object key = this.load(input, WorldVersion, keyByte, depth);
            byte valueByte = input.get();
            Object value = this.load(input, WorldVersion, valueByte, depth);
            this.rawset(key, value);
         }
      } else {
         for (int n = 0; n < count; n++) {
            byte valueByte = input.get();
            String key = GameWindow.ReadString(input);
            Object value = this.load(input, WorldVersion, valueByte, depth);
            this.rawset(key, value);
         }
      }
   }

   public Object load(ByteBuffer input, int WorldVersion, byte sbyt, int depth) throws RuntimeException {
      if (sbyt == 0) {
         return GameWindow.ReadString(input);
      }

      if (sbyt == 1) {
         return input.getDouble();
      }

      if (sbyt == 3) {
         return input.get() != 0;
      }

      if (sbyt == 2) {
         if (depth + 1 >= 256) {
            throw new RuntimeException("lua table nesting exceeded");
         }

         KahluaTableImpl v = (KahluaTableImpl)LuaManager.platform.newTable();
         v.load(input, WorldVersion, depth + 1);
         return v;
      } else {
         throw new RuntimeException("invalid lua table type " + sbyt);
      }
   }

   public void load(DataInputStream input, int WorldVersion) throws IOException {
      this.load(input, WorldVersion, 0);
   }

   public void load(DataInputStream input, int WorldVersion, int depth) throws IOException {
      int count = input.readInt();
      if (WorldVersion >= 25) {
         for (int n = 0; n < count; n++) {
            byte keyByte = input.readByte();
            Object key = this.load(input, WorldVersion, keyByte, depth);
            byte valueByte = input.readByte();
            Object value = this.load(input, WorldVersion, valueByte, depth);
            this.rawset(key, value);
         }
      } else {
         for (int n = 0; n < count; n++) {
            byte valueByte = input.readByte();
            String key = GameWindow.ReadString(input);
            Object value = this.load(input, WorldVersion, valueByte, depth);
            this.rawset(key, value);
         }
      }
   }

   public Object load(DataInputStream input, int WorldVersion, byte sbyt, int depth) throws IOException, RuntimeException {
      if (sbyt == 0) {
         return GameWindow.ReadString(input);
      }

      if (sbyt == 1) {
         return input.readDouble();
      }

      if (sbyt == 3) {
         return input.readByte() == 1;
      }

      if (sbyt == 2) {
         if (depth + 1 >= 256) {
            throw new RuntimeException("lua table nesting exceeded");
         }

         KahluaTableImpl v = (KahluaTableImpl)LuaManager.platform.newTable();
         v.load(input, WorldVersion, depth + 1);
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
      pzoptJitEpoch++; // pzopt: luaJitInlineCache
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
