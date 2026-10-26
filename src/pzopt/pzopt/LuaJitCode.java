package pzopt;

import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;

/**
 * luaJit: one Lua function compiled to JVM bytecode (a hidden class of the se.krka.kahlua.vm package, a nestmate of
 * KahluaThread, made by {@link LuaJitCompiler}). {@link #run} executes the function on a frame the caller pushed and
 * initialised exactly as the interpreter's OP_CALL does, from pc 0, and says how it ended (the {@code LuaJit.ST_*}
 * statuses): returned (results at the frame's returnBase, frame popped, as OP_RETURN), or handed over to the
 * interpreter with the Lua stack, pc and restoreTop of every frame exactly as the interpreter would have left them.
 */
public abstract class LuaJitCode {
   /** The Lua function's name and source, for logs. */
   public String name;
   /** luaJitSpecArith: the function, the code that replaced this one (run() hands frames to it), failed speculations. */
   public se.krka.kahlua.vm.Prototype proto;
   public LuaJitCode next;
   public int specFails;

   public abstract int run(KahluaThread t, LuaCallFrame f);
}
