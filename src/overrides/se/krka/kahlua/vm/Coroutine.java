package se.krka.kahlua.vm;

import java.util.ArrayList;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;
import zombie.core.Core;

@UsedFromLua
public final class Coroutine {
   private final Platform platform;
   private KahluaThread thread;
   private Coroutine parent;
   public KahluaTable environment;
   public String stackTrace = "";
   private final ArrayList<UpValue> liveUpvalues = new ArrayList<>();
   private static final int MAX_STACK_SIZE = 3000;
   private static final int INITIAL_STACK_SIZE = 1000;
   private static final int MAX_CALL_FRAME_STACK_SIZE = 1000;
   private static final int INITIAL_CALL_FRAME_STACK_SIZE = 200;
   public Object[] objectStack;
   private int top;
   private LuaCallFrame[] callFrameStack;
   private int callFrameTop;

   public Coroutine() {
      this.platform = null;
   }

   public Coroutine getParent() {
      return this.parent;
   }

   public Coroutine(Platform platform, KahluaTable environment, KahluaThread thread) {
      this.platform = platform;
      this.environment = environment;
      this.thread = thread;
      this.objectStack = new Object[1000];
      this.callFrameStack = new LuaCallFrame[200];
   }

   public Coroutine(Platform platform, KahluaTable environment) {
      this(platform, environment, null);
   }

   public LuaCallFrame pushNewCallFrame(
      LuaClosure closure, JavaFunction javaFunction, int localBase, int returnBase, int nArguments, boolean fromLua, boolean insideCoroutine
   ) {
      this.setCallFrameStackTop(this.callFrameTop + 1);
      LuaCallFrame callFrame = this.currentCallFrame();
      callFrame.setup(closure, javaFunction, localBase, returnBase, nArguments, fromLua, insideCoroutine);
      return callFrame;
   }

   public void popCallFrame() {
      if (this.isDead()) {
         throw new RuntimeException("Stack underflow");
      }

      this.setCallFrameStackTop(this.callFrameTop - 1);
   }

   private void ensureCallFrameStackSize(int index) {
      if (index > 1000) {
         throw new RuntimeException("Stack overflow");
      }

      int oldSize = this.callFrameStack.length;
      int newSize = oldSize;

      while (newSize <= index) {
         newSize = 2 * newSize;
      }

      if (newSize > oldSize) {
         LuaCallFrame[] newStack = new LuaCallFrame[newSize];
         System.arraycopy(this.callFrameStack, 0, newStack, 0, oldSize);
         this.callFrameStack = newStack;
      }
   }

   public void setCallFrameStackTop(int newTop) {
      if (newTop > this.callFrameTop) {
         this.ensureCallFrameStackSize(newTop);
      } else {
         this.callFrameStackClear(newTop, this.callFrameTop - 1);
      }

      this.callFrameTop = newTop;
   }

   private void callFrameStackClear(int startIndex, int endIndex) {
      while (startIndex <= endIndex) {
         LuaCallFrame callFrame = this.callFrameStack[startIndex];
         if (callFrame != null) {
            this.callFrameStack[startIndex].closure = null;
            this.callFrameStack[startIndex].javaFunction = null;
         }

         startIndex++;
      }
   }

   private void ensureStacksize(int index) {
      if (index > 3000) {
         throw new RuntimeException("Stack overflow");
      }

      int oldSize = this.objectStack.length;
      int newSize = oldSize;

      while (newSize <= index) {
         newSize = 2 * newSize;
      }

      if (newSize > oldSize) {
         Object[] newStack = new Object[newSize];
         System.arraycopy(this.objectStack, 0, newStack, 0, oldSize);
         this.objectStack = newStack;
      }
   }

   public void setTop(int newTop) {
      if (this.top < newTop) {
         this.ensureStacksize(newTop);
      } else {
         this.stackClear(newTop, this.top - 1);
      }

      this.top = newTop;
   }

   public void stackCopy(int startIndex, int destIndex, int len) {
      if (len > 0 && startIndex != destIndex) {
         System.arraycopy(this.objectStack, startIndex, this.objectStack, destIndex, len);
         LuaCallFrame f = this.getParentNoAssert(1);
         if (Core.debug && this.thread == LuaManager.thread && f != null && f.pc > 0 && f.closure != null && f.closure.prototype.locvarlines != null) {
            Prototype prototype = f.closure.prototype;
            int[] locvarlines = prototype.locvarlines;

            for (int n = destIndex; n < destIndex + len; n++) {
               int line = prototype.lines[f.pc - 1];
               boolean bLastOnLine = prototype.lines[f.pc] != line;
               if (bLastOnLine) {
                  while (f.localsAssigned < locvarlines.length && line > locvarlines[f.localsAssigned] && locvarlines[f.localsAssigned] != 0) {
                     f.localsAssigned++;
                  }

                  if (f.localsAssigned < locvarlines.length && locvarlines[f.localsAssigned] == line) {
                     int m = f.localsAssigned++;
                     String name = prototype.locvars[m];
                     f.setLocalVarToStack(name, n);
                  }
               }
            }
         }
      }
   }

   public void stackCopyNoDebugStuff(int startIndex, int destIndex, int len) {
      if (len > 0 && startIndex != destIndex) {
         System.arraycopy(this.objectStack, startIndex, this.objectStack, destIndex, len);
      }
   }

   public void stackClear(int startIndex, int endIndex) {
      if (pzopt.Config.LUA_JIT_FRAME_LITE) { // pzopt: luaJitFrameLite
         Object[] pzoptStack = this.objectStack; // pzopt: luaJitFrameLite
         for (; startIndex <= endIndex; startIndex++) { // pzopt: luaJitFrameLite
            if (pzoptStack[startIndex] != null) { // pzopt: luaJitFrameLite, a nil slot is not written (no GC store barrier)
               pzoptStack[startIndex] = null; // pzopt: luaJitFrameLite
            } // pzopt: luaJitFrameLite
         } // pzopt: luaJitFrameLite
         return; // pzopt: luaJitFrameLite
      } // pzopt: luaJitFrameLite
      while (startIndex <= endIndex) {
         this.objectStack[startIndex] = null;
         startIndex++;
      }
   }

   public void closeUpvalues(int closeIndex) {
      int loopIndex = this.liveUpvalues.size();

      while (--loopIndex >= 0) {
         UpValue uv = this.liveUpvalues.get(loopIndex);
         if (uv.getIndex() < closeIndex) {
            return;
         }

         uv.close();
         this.liveUpvalues.remove(loopIndex);
      }
   }

   public UpValue findUpvalue(int scanIndex) {
      int loopIndex = this.liveUpvalues.size();

      while (--loopIndex >= 0) {
         UpValue uv = this.liveUpvalues.get(loopIndex);
         int index = uv.getIndex();
         if (index == scanIndex) {
            return uv;
         }

         if (index < scanIndex) {
            break;
         }
      }

      UpValue uv = new UpValue(this, scanIndex);
      this.liveUpvalues.add(loopIndex + 1, uv);
      return uv;
   }

   public Object getObjectFromStack(int n) {
      return this.objectStack[n];
   }

   public int getObjectStackSize() {
      return this.top;
   }

   public LuaCallFrame getParentCallframe() {
      int index = this.callFrameTop - 1;
      return index < 0 ? null : this.callFrameStack[index];
   }

   public LuaCallFrame currentCallFrame() {
      if (this.isDead()) {
         return null;
      }

      LuaCallFrame callFrame = this.callFrameStack[this.callFrameTop - 1];
      if (callFrame == null) {
         callFrame = new LuaCallFrame(this);
         this.callFrameStack[this.callFrameTop - 1] = callFrame;
      }

      return callFrame;
   }

   public int getTop() {
      return this.top;
   }

   public LuaCallFrame getParent(int level) {
      KahluaUtil.luaAssert(level >= 0, "Level must be non-negative");
      int index = this.callFrameTop - level - 1;
      KahluaUtil.luaAssert(index >= 0, "Level too high");
      return this.callFrameStack[index];
   }

   public LuaCallFrame getParentNoAssert(int level) {
      int index = this.callFrameTop - level - 1;
      return index < 0 ? null : this.callFrameStack[index];
   }

   public String getCurrentStackTrace(int level, int count, int haltAt) {
      if (level < 0) {
         level = 0;
      }

      if (count < 0) {
         count = 0;
      }

      StringBuilder buffer = new StringBuilder();

      for (int i = this.callFrameTop - 1 - level; i >= haltAt && count-- > 0; i--) {
         buffer.append(this.getStackTrace(this.callFrameStack[i]));
      }

      return buffer.toString();
   }

   public void cleanCallFrames(LuaCallFrame callerFrame) {
      while (true) {
         LuaCallFrame frame = this.currentCallFrame();
         if (frame == null || frame == callerFrame) {
            return;
         }

         this.addStackTrace(frame);
         this.popCallFrame();
      }
   }

   public void addStackTrace(LuaCallFrame frame) {
      this.stackTrace = this.stackTrace + this.getStackTrace(frame);
   }

   private String getStackTrace(LuaCallFrame frame) {
      if (frame.isLua()) {
         int[] lines = frame.closure.prototype.lines;
         if (lines != null) {
            int pc = frame.pc - 1;
            if (pc >= 0 && pc < lines.length) {
               return "at " + frame.closure.prototype + ":" + lines[pc] + "\n";
            }
         }

         return "";
      } else {
         return "at " + frame.javaFunction + "\n";
      }
   }

   public boolean isDead() {
      return this.callFrameTop == 0;
   }

   public Platform getPlatform() {
      return this.platform;
   }

   public String getStatus() {
      if (this.parent == null) {
         return this.isDead() ? "dead" : "suspended";
      } else {
         return "normal";
      }
   }

   public boolean atBottom() {
      return this.callFrameTop == 1;
   }

   public int getCallframeTop() {
      return this.callFrameTop;
   }

   public LuaCallFrame[] getCallframeStack() {
      return this.callFrameStack;
   }

   public LuaCallFrame getCallFrame(int index) {
      if (index < 0) {
         index += this.callFrameTop;
      }

      return this.callFrameStack[index];
   }

   public static void yieldHelper(LuaCallFrame callFrame, LuaCallFrame argsCallFrame, int nArguments) {
      KahluaUtil.luaAssert(callFrame.canYield, "Can not yield outside of a coroutine");
      Coroutine coroutine = callFrame.coroutine;
      KahluaThread thread = coroutine.getThread();
      Coroutine parent = coroutine.parent;
      KahluaUtil.luaAssert(parent != null, "Internal error, coroutine must be running");
      KahluaUtil.luaAssert(coroutine == thread.currentCoroutine, "Internal error, must yield current thread");
      coroutine.destroy();
      LuaCallFrame nextCallFrame = parent.currentCallFrame();
      if (nextCallFrame == null) {
         parent.setTop(nArguments + 1);
         parent.objectStack[0] = Boolean.TRUE;

         for (int i = 0; i < nArguments; i++) {
            parent.objectStack[i + 1] = argsCallFrame.get(i);
         }
      } else {
         nextCallFrame.push(Boolean.TRUE);

         for (int i = 0; i < nArguments; i++) {
            Object value = argsCallFrame.get(i);
            nextCallFrame.push(value);
         }
      }

      thread.currentCoroutine = parent;
   }

   public void resume(Coroutine parent) {
      this.parent = parent;
      this.thread = parent.thread;
   }

   public KahluaThread getThread() {
      return this.thread;
   }

   public void destroy() {
      this.parent = null;
      this.thread = null;
   }
}
