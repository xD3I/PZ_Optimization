package pzopt;

import java.lang.reflect.Method;

/**
 * The write barrier of the learned mode ({@code luaZombieWriters=watch}, 2026-10-25, worktree lua-zombie): while
 * pzopt.LuaZombie.dispatch runs a mod's OnZombieUpdate handler on zombie z, {@link #target} is z, and Kahlua's MethodCaller
 * (every Lua -> Java method call) counts a call of a state-changing method (LuaZombieScan's writer names: setX, setTarget,
 * setUseless, knockDown, Kill, addBlood ...) whose receiver is z. A handler that wrote z in its call runs every update on z
 * for luaZombieHotMs (an ECS change filter / GC write barrier rather than a before / after snapshot: it sees writes through
 * helpers and cached functions the static scan cannot). Game thread only (Lua runs there); a plain static, read with one
 * compare per Java call.
 */
public final class LuaZombieWatch {
   private LuaZombieWatch() {
   }

   /** The zombie whose handler is running (null outside LuaZombie.dispatch). */
   public static Object target;
   /** Writer calls on {@link #target} since dispatch reset it. */
   public static int writes;

   /** MethodCaller's constructor: is this method a write of the receiver's state (by name, as the static scan). */
   public static boolean isWriter(Method m) {
      String n = m.getName();
      if (n.equals("getEmitter") || n.startsWith("get") || n.startsWith("is")) {
         return false;
      }
      return LuaZombieScan.WRITER.matcher(n).matches();
   }

   /** MethodCaller.call: a writer method was called on {@code self}. */
   public static void call(Object self) {
      if (self == target && self != null) {
         writes++;
      }
   }
}
