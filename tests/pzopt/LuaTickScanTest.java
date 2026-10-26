package pzopt;

import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.MathLib;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.stdlib.StringLib;
import se.krka.kahlua.stdlib.TableLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;

/**
 * luaTickScan (pzopt.LuaTickScan): the handlers that must stay every frame are found through the calls a mod makes
 * (globals, local helpers as upvalues, functions handed to pcall, closures it defines with what they capture), and a
 * handler that only computes is not. The handler shapes come from the 650-mod list (NRE's fall guard, a key poll, a
 * numberTicks throttle, a work queue).
 */
public class LuaTickScanTest {
   static J2SEPlatform platform = J2SEPlatform.getInstance();

   static KahluaTable newEnv() {
      KahluaTable env = platform.newTable();
      env.rawset("_G", env);
      BaseLib.register(env);
      MathLib.register(platform, env);
      StringLib.register(platform, env);
      TableLib.register(platform, env);
      LuaCompiler.register(env);
      return env;
   }

   /** Runs src (which stores its handler in the global H) and scans H. */
   static String scan(String src) throws Exception {
      KahluaTable env = newEnv();
      FuncState.currentFile = "t.lua";
      FuncState.currentfullFile = "/mods/t/media/lua/client/t.lua";
      LuaClosure chunk = LuaCompiler.loadstring(src, "t.lua", env);
      KahluaThread t = new KahluaThread(platform, env);
      t.debugOwnerThread = Thread.currentThread();
      Object[] r = t.pcall(chunk, new Object[0]);
      Check.check(r.length > 0 && Boolean.TRUE.equals(r[0]), "chunk ran: " + java.util.Arrays.toString(r));
      Object h = env.rawget("H");
      Check.check(h instanceof LuaClosure, "H is a Lua function");
      return LuaTickScan.scan((LuaClosure)h, env);
   }

   static void expect(String what, String src, String reasonStart) throws Exception {
      String got = scan(src);
      boolean ok = reasonStart == null ? got == null : got != null && got.startsWith(reasonStart);
      Check.check(ok, what + ": expected " + reasonStart + ", got " + got);
   }

   static void expectWithout(String what, String src, String reasonStart) throws Exception {
      KahluaTable env = newEnv();
      LuaClosure chunk = LuaCompiler.loadstring(src, "t.lua", env);
      KahluaThread t = new KahluaThread(platform, env);
      t.debugOwnerThread = Thread.currentThread();
      t.pcall(chunk, new Object[0]);
      Check.check(LuaTickScan.scan((LuaClosure)env.rawget("H"), env) != null, what + ": pinned with input counted");
      String got = LuaTickScan.scanWithout((LuaClosure)env.rawget("H"), env, "input");
      boolean ok = reasonStart == null ? got == null : got != null && got.startsWith(reasonStart);
      Check.check(ok, what + " (input not counted): expected " + reasonStart + ", got " + got);
   }

   public static void main(String[] args) throws Exception {
      expect("pure work queue", """
            local q = {}
            for i = 1, 100 do q[i] = i end
            local head = 1
            local function step() local s = 0 for i = head, math.min(#q, head + 9) do s = s + q[i] end head = head + 10 return s end
            H = function() step() end
            """, null);
      expect("key poll through a global table", """
            Keys = { isKeyDown = function(k) return false end }
            H = function() if Keys.isKeyDown(5) then print("x") end end
            """, "input isKeyDown");
      expect("method call on an object", """
            H = function(player) player:setZ(1) end
            """, "move setZ");
      expect("numberTicks throttle", """
            H = function(ticks) if ticks % 10 == 0 then local x = 1 end end
            """, "ticks modulo");
      // NicksRideableElevators' fall guard: five calls deep, through pcall, a closure and two local helpers
      expect("fall guard chain", """
            local function repin(p, z) pcall(function() p:setZ(z) p:setLastZ(z) end) end
            local function guardStep(p) if p then repin(p, 1) end end
            local function driver()
               for i = 0, 0 do
                  local p = nil
                  pcall(function() guardStep(p) end)
               end
            end
            H = function() pcall(driver) end
            """, "move setZ");
      expect("helper defined after the handler (upvalue filled later)", """
            local helper
            H = function() helper() end
            helper = function() local ui = getUI() ui:bringToTop() end
            """, "ui bringToTop");
      // luaTickInputWake: input is the only reason of a key poll, not of a key poll that also moves the player
      expectWithout("key poll alone", """
            Keys = { isKeyDown = function(k) return false end }
            H = function() if Keys.isKeyDown(5) then print("x") end end
            """, null);
      expectWithout("key poll that moves the player", """
            H = function(player) if isKeyDown(5) then player:setZ(0) end end
            """, "move setZ");
      System.out.println("LuaTickScanTest: 8 handler shapes classified");
   }
}
