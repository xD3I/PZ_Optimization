package pzopt;

/**
 * luaJitInlineCache: one table-read site of compiled Lua code ({@code t.name}, a global, {@code obj:name()} with a constant
 * String key). Remembers the dense position the key had in the last table read there: a {@link LuaMap} keeps a key at its
 * position, and tables built by the same constructor put their keys in the same order, so one position check
 * ({@code keys[slot] == key}) usually replaces the hash lookup, for every table the site sees. For a Java object (a square,
 * a player, a String) it remembers what the class metatable gave for the last class seen there, until a table on that path
 * is written (KahluaTableImpl.pzoptJitEpoch).
 */
public final class LuaJitIc {
   public int slot;
   /** luaJitSlot2: the position the key had in the previous table layout this site read (bimorphic instance layouts). */
   public int slot2;
   /** luaJitSlot4: the positions of the two layouts before those. */
   public int slot3, slot4;
   /**
    * A Java object's method (or field) found through its class metatable's __index table: valid for this class and thread
    * while KahluaTableImpl.pzoptJitEpoch is its epoch. One immutable entry written with one reference store, so a site
    * two threads run at once never pairs one thread's class with the other's value.
    */
   public Java java;

   public record Java(Class<?> cls, Object thread, Object value, int epoch, se.krka.kahlua.j2se.KahluaTableImpl mt, se.krka.kahlua.j2se.KahluaTableImpl idx) {
   }

   /**
    * A key the table read at this site lacked, answered by its metatable chain (an instance's method from its class):
    * the metatable, KahluaTableImpl.pzoptMetaVersion when it was read, and the answer (null included). One immutable
    * entry, one reference store (KahluaTableImpl.pzoptMissGetIc); luaJitMissCache.
    */
   public Miss miss;
   /** luaJitMissPoly: the entry of the site's previous metatable (a site that sees instances of two classes). */
   public Miss miss2;
   /** luaJitUiReadMemo: the table this site last logged for a recorded UI render, and that recording's log id. */
   public Object uiTable;
   public int uiLog;
   /** luaMetaKeyVersion: KahluaTableImpl.pzoptKeyBucket of the site's key, -1 until the first miss. */
   public int bucket = -1;
   /** luaJitNilCache: the metatable whose instances answer nil for this site's key, and the versions it was proven under. */
   public Object nilMt;
   public long nilVer, nilKv, nilIv;
   /**
    * luaJitNilCacheUi: the reads the proven walk logs for a recorded UI render (KahluaTableImpl.pzoptNilChain), and the chain
    * and recording this site last logged them for.
    */
   public Object[] nilChain;
   public Object[] nilUiChain;
   /** luaJitNilPoly: the proven nils of the site's last two metatables, the most recent first. */
   public Nil nilA, nilB;

   /**
    * luaJitNilPoly: a proven nil (the metatable, the versions it was proven under, its walk's reads for a recorded UI render)
    * and the recording that last logged them (luaJitNilCacheUi; written by the recording thread only).
    */
   public static final class Nil {
      public final Object mt;
      public final long ver, kv, iv;
      public final Object[] chain;
      public int uiLog;

      public Nil(Object mt, long ver, long kv, long iv, Object[] chain, int uiLog) {
         this.mt = mt;
         this.ver = ver;
         this.kv = kv;
         this.iv = iv;
         this.chain = chain;
         this.uiLog = uiLog;
      }
   }
   public int nilUiLog;

   /** kver: KahluaTableImpl.pzoptKeyVer of the key's bucket when it was read (luaMetaKeyVersion). */
   public record Miss(Object mt, long version, long kver, Object value) {
   }
}
