package zombie;

import java.util.Stack;
import org.lwjgl.opengl.GL11;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLState;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.opengl.GLState.C2IntsValue;
import zombie.core.opengl.GLState.C3IntsValue;
import zombie.core.opengl.GLState.C4BooleansValue;
import zombie.core.opengl.GLState.C4IntsValue;
import zombie.core.opengl.GLState.CBooleanValue;
import zombie.core.opengl.GLState.CIntFloatValue;
import zombie.core.opengl.GLState.CIntValue;
import zombie.core.opengl.ShaderProgram.Uniform;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.util.Lambda;
import zombie.util.lambda.Invokers.Params1.ICallback;

public final class IndieGL {
   public static int nCount;
   private static final CIntValue tempInt = new CIntValue();
   private static final C2IntsValue temp2Ints = new C2IntsValue();
   private static final C3IntsValue temp3Ints = new C3IntsValue();
   private static final C4IntsValue temp4Ints = new C4IntsValue();
   private static final C4BooleansValue temp4Booleans = new C4BooleansValue();
   private static final CIntFloatValue tempIntFloat = new CIntFloatValue();
   private static final Stack<ShaderStackEntry> m_shaderStack = new Stack<>();
   private static final Object pzoptLock = new Object(); // pzopt: tileRecordParallel, the shader-stack entry pool is shared

   /** pzopt: tileRecordParallel, the temps and shader stack of a thread recording a tile draw unit (null on any other thread: the statics). */
   private static IndieGL.PzoptTemps pzoptT() { // pzopt
      if (!pzopt.DrawRecorder.recording) { // pzopt
         return null; // pzopt
      } // pzopt
      pzopt.DrawRecorder r = pzopt.DrawRecorder.currentRecorder(); // pzopt
      if (r == null) { // pzopt
         return null; // pzopt
      } // pzopt
      if (r.indieGlTemps == null) { // pzopt
         r.indieGlTemps = new IndieGL.PzoptTemps(); // pzopt
      } // pzopt
      return (IndieGL.PzoptTemps)r.indieGlTemps; // pzopt
   } // pzopt

   private static ShaderStackEntry pzoptAllocEntry(Shader shader, int playerIndex) { // pzopt: the entry pool is shared, a recording thread takes it under a lock
      if (pzoptT() == null) { // pzopt
         return ShaderStackEntry.alloc(shader, playerIndex); // pzopt
      } // pzopt
      synchronized (pzoptLock) { // pzopt
         return ShaderStackEntry.alloc(shader, playerIndex); // pzopt
      } // pzopt
   } // pzopt

   private static void pzoptRelease(ShaderStackEntry e) { // pzopt
      if (pzoptT() == null) { // pzopt
         e.release(); // pzopt
         return; // pzopt
      } // pzopt
      synchronized (pzoptLock) { // pzopt
         e.release(); // pzopt
      } // pzopt
   } // pzopt

   private static Stack<ShaderStackEntry> pzoptStack() { // pzopt
      IndieGL.PzoptTemps t = pzoptT(); // pzopt
      return t == null ? m_shaderStack : t.shaderStack; // pzopt
   } // pzopt

   private static final class PzoptTemps { // pzopt
      final CIntValue tempInt = new CIntValue(); // pzopt
      final C2IntsValue temp2Ints = new C2IntsValue(); // pzopt
      final C3IntsValue temp3Ints = new C3IntsValue(); // pzopt
      final C4IntsValue temp4Ints = new C4IntsValue(); // pzopt
      final C4BooleansValue temp4Booleans = new C4BooleansValue(); // pzopt
      final CIntFloatValue tempIntFloat = new CIntFloatValue(); // pzopt
      final Stack<ShaderStackEntry> shaderStack = new Stack<>(); // pzopt
   } // pzopt

   public static void glBlendFunc(int a, int b) {
      if (pzopt.DrawRecorder.blendEnabled()) { // pzopt: tileRecordWeather, a weather-mask recorder keeps its own flag
         GLState.BlendFuncSeparate.set((pzoptT() == null ? temp4Ints : pzoptT().temp4Ints).set(a, b, a, b)); // pzopt: tileRecordParallel, a recording thread uses its own temps
      }
   }

   public static void glBlendFuncSeparate(int a, int b, int c, int d) {
      if (pzopt.DrawRecorder.blendEnabled()) { // pzopt: tileRecordWeather, a weather-mask recorder keeps its own flag
         GLState.BlendFuncSeparate.set((pzoptT() == null ? temp4Ints : pzoptT().temp4Ints).set(a, b, c, d)); // pzopt: tileRecordParallel, a recording thread uses its own temps
      }
   }

   public static void restoreMainThreadValue_glBlendFuncSeparate() {
   }

   public static void glDefaultBlendFunc() {
      glBlendFunc(1, 771);
   }

   public static void glDefaultBlendFuncA() {
      GL11.glBlendFunc(1, 771);
   }

   public static void glDepthFunc(int a) {
      GLState.DepthFunc.set((pzoptT() == null ? tempInt : pzoptT().tempInt).set(a)); // pzopt: tileRecordParallel, a recording thread uses its own temps
   }

   public static void glDepthMask(boolean b) {
      GLState.DepthMask.set(b ? CBooleanValue.TRUE : CBooleanValue.FALSE);
   }

   public static void StartShader(Shader shader) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      StartShader(shader, playerIndex);
   }

   public static void StartShader(Shader shader, int playerIndex) {
      if (shader != null) {
         StartShader(shader.getID(), playerIndex);
      } else {
         EndShader();
      }
   }

   public static void StartShader(int id) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      StartShader(id, playerIndex);
   }

   public static void StartShader(int id, int playerIndex) {
      SpriteRenderer.instance.StartShader(id, playerIndex);
   }

   public static void StartShader(Shader shader, ShaderUniformSetter uniforms) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      StartShader(shader, playerIndex, uniforms);
   }

   public static void StartShader(Shader shader, int playerIndex, ShaderUniformSetter uniforms) {
      if (shader != null) {
         StartShader(shader.getID(), playerIndex, uniforms);
      } else {
         EndShader();
      }
   }

   public static void StartShader(int id, ShaderUniformSetter uniforms) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      StartShader(id, playerIndex, uniforms);
   }

   public static void StartShader(int id, int playerIndex, ShaderUniformSetter uniforms) {
      SpriteRenderer.instance.StartShader(id, playerIndex, uniforms);
   }

   public static void EndShader() {
      SpriteRenderer.instance.EndShader();
   }

   public static void pushShader(Shader shader) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      pzoptStack().push(pzoptAllocEntry(shader, playerIndex)); // pzopt: tileRecordParallel, a recording thread uses its own temps
      StartShader(shader, playerIndex);
   }

   public static void pushShader(Shader shader, ShaderUniformSetter uniforms) {
      int playerIndex = IsoCamera.frameState.playerIndex;
      pzoptStack().push(pzoptAllocEntry(shader, playerIndex)); // pzopt: tileRecordParallel, a recording thread uses its own temps
      StartShader(shader, playerIndex, uniforms);
   }

   public static void popShader(Shader shader) {
      if (pzoptStack().isEmpty()) { // pzopt: tileRecordParallel, a recording thread uses its own temps
         throw new RuntimeException("Push/PopShader mismatch. Cannot pop. Stack is empty.");
      }

      if (pzoptStack().peek().getShader() != shader) { // pzopt: tileRecordParallel, a recording thread uses its own temps
         throw new RuntimeException("Push/PopShader mismatch. The popped shader != the pushed shader.");
      }

      ShaderStackEntry topEntry = pzoptStack().pop(); // pzopt: tileRecordParallel, a recording thread uses its own temps
      pzoptRelease(topEntry); // pzopt: the entry pool is shared
      if (pzoptStack().isEmpty()) { // pzopt: tileRecordParallel, a recording thread uses its own temps
         EndShader();
      } else {
         ShaderStackEntry nextTopEntry = pzoptStack().peek(); // pzopt: tileRecordParallel, a recording thread uses its own temps
         StartShader(nextTopEntry.getShader(), nextTopEntry.getPlayerIndex());
      }
   }

   public static void bindShader(Shader shader, Runnable invoke) {
      pushShader(shader);

      try {
         invoke.run();
      } finally {
         popShader(shader);
      }
   }

   public static <T1> void bindShader(Shader shader, T1 val1, ICallback<T1> invoker) {
      Lambda.capture(shader, val1, invoker, (stack, lShader, lVal1, lInvoker) -> bindShader(lShader, stack.invoker(lVal1, lInvoker)));
   }

   public static <T1, T2> void bindShader(Shader shader, T1 val1, T2 val2, zombie.util.lambda.Invokers.Params2.ICallback<T1, T2> invoker) {
      Lambda.capture(shader, val1, val2, invoker, (stack, lShader, lVal1, lVal2, lInvoker) -> bindShader(lShader, stack.invoker(lVal1, lVal2, lInvoker)));
   }

   public static <T1, T2, T3> void bindShader(Shader shader, T1 val1, T2 val2, T3 val3, zombie.util.lambda.Invokers.Params3.ICallback<T1, T2, T3> invoker) {
      Lambda.capture(
         shader,
         val1,
         val2,
         val3,
         invoker,
         (stack, lShader, lVal1, lVal2, lVal3, lInvoker) -> bindShader(lShader, stack.invoker(lVal1, lVal2, lVal3, lInvoker))
      );
   }

   public static <T1, T2, T3, T4> void bindShader(
      Shader shader, T1 val1, T2 val2, T3 val3, T4 val4, zombie.util.lambda.Invokers.Params4.ICallback<T1, T2, T3, T4> invoker
   ) {
      Lambda.capture(
         shader,
         val1,
         val2,
         val3,
         val4,
         invoker,
         (stack, lShader, lVal1, lVal2, lVal3, lVal4, lInvoker) -> bindShader(lShader, stack.invoker(lVal1, lVal2, lVal3, lVal4, lInvoker))
      );
   }

   private static Uniform getShaderUniform(Shader shader, String uniformName, int uniformType) {
      if (shader == null) {
         return null;
      }

      ShaderProgram program = shader.getProgram();
      return program == null ? null : program.getUniform(uniformName, uniformType, false);
   }

   public static void shaderSetSamplerUnit(Shader shader, String loc, int textureUnit) {
      Uniform u = getShaderUniform(shader, loc, 35678);
      if (u != null) {
         u.sampler = textureUnit;
         ShaderUpdate1i(shader.getID(), u.loc, textureUnit);
      }
   }

   public static void shaderSetValue(Shader shader, String loc, float val) {
      Uniform u = getShaderUniform(shader, loc, 5126);
      if (u != null) {
         ShaderUpdate1f(shader.getID(), u.loc, val);
      }
   }

   public static void shaderSetValue(Shader shader, String loc, int val) {
      Uniform u = getShaderUniform(shader, loc, 5124);
      if (u != null) {
         ShaderUpdate1i(shader.getID(), u.loc, val);
      }
   }

   public static void shaderSetValue(Shader shader, String loc, Vector2 val) {
      shaderSetVector2(shader, loc, val.x, val.y);
   }

   public static void shaderSetValue(Shader shader, String loc, Vector3 val) {
      shaderSetVector3(shader, loc, val.x, val.y, val.z);
   }

   public static void shaderSetVector2(Shader shader, String loc, float valX, float valY) {
      Uniform u = getShaderUniform(shader, loc, 35664);
      if (u != null) {
         ShaderUpdate2f(shader.getID(), u.loc, valX, valY);
      }
   }

   public static void shaderSetVector3(Shader shader, String loc, float valX, float valY, float valZ) {
      Uniform u = getShaderUniform(shader, loc, 35665);
      if (u != null) {
         ShaderUpdate3f(shader.getID(), u.loc, valX, valY, valZ);
      }
   }

   public static void shaderSetVector4(Shader shader, String loc, float valX, float valY, float valZ, float valW) {
      Uniform u = getShaderUniform(shader, loc, 35666);
      if (u != null) {
         ShaderUpdate4f(shader.getID(), u.loc, valX, valY, valZ, valW);
      }
   }

   public static void ShaderUpdate1i(int shaderID, int uniform, int uniformValue) {
      SpriteRenderer.instance.ShaderUpdate1i(shaderID, uniform, uniformValue);
   }

   public static void ShaderUpdate1f(int shaderID, int uniform, float uniformValue) {
      SpriteRenderer.instance.ShaderUpdate1f(shaderID, uniform, uniformValue);
   }

   public static void ShaderUpdate2f(int shaderID, int uniform, float value1, float value2) {
      SpriteRenderer.instance.ShaderUpdate2f(shaderID, uniform, value1, value2);
   }

   public static void ShaderUpdate3f(int shaderID, int uniform, float value1, float value2, float value3) {
      SpriteRenderer.instance.ShaderUpdate3f(shaderID, uniform, value1, value2, value3);
   }

   public static void ShaderUpdate4f(int shaderID, int uniform, float value1, float value2, float value3, float value4) {
      SpriteRenderer.instance.ShaderUpdate4f(shaderID, uniform, value1, value2, value3, value4);
   }

   public static void glBlendFuncA(int a, int b) {
      GL11.glBlendFunc(a, b);
   }

   public static void glEnable(int a) {
      if (a == 3008) {
         enableAlphaTest();
      } else if (a == 3042) {
         enableBlend();
      } else if (a == 2929) {
         enableDepthTest();
      } else if (a == 3089) {
         enableScissorTest();
      } else if (a == 2960) {
         enableStencilTest();
      } else {
         SpriteRenderer.instance.glEnable(a);
      }
   }

   public static void glDoStartFrame(int w, int h, float zoom, int player) {
      glDoStartFrame(w, h, zoom, player, false);
   }

   public static void glDoStartFrame(int w, int h, float zoom, int player, boolean isTextFrame) {
      SpriteRenderer.instance.glDoStartFrame(w, h, zoom, player, isTextFrame);
   }

   public static void glDoEndFrame() {
      SpriteRenderer.instance.glDoEndFrame();
   }

   public static void glColorMask(boolean bln, boolean bln1, boolean bln2, boolean bln3) {
      GLState.ColorMask.set((pzoptT() == null ? temp4Booleans : pzoptT().temp4Booleans).set(bln, bln1, bln2, bln3)); // pzopt: tileRecordParallel, a recording thread uses its own temps
   }

   public static void glColorMaskA(boolean bln, boolean bln1, boolean bln2, boolean bln3) {
      GL11.glColorMask(bln, bln, bln3, bln3);
   }

   public static void glEnableA(int a) {
      GL11.glEnable(a);
   }

   public static void glAlphaFunc(int a, float b) {
      if (pzopt.DrawRecorder.blendEnabled()) { // pzopt: tileRecordWeather, a weather-mask recorder keeps its own flag
         GLState.AlphaFunc.set((pzoptT() == null ? tempIntFloat : pzoptT().tempIntFloat).set(a, b)); // pzopt: tileRecordParallel, a recording thread uses its own temps
      }
   }

   public static void glAlphaFuncA(int a, float b) {
      GL11.glAlphaFunc(a, b);
   }

   public static void glStencilFunc(int a, int b, int c) {
      GLState.StencilFunc.set((pzoptT() == null ? temp3Ints : pzoptT().temp3Ints).set(a, b, c)); // pzopt: tileRecordParallel, a recording thread uses its own temps
   }

   public static void glStencilFuncA(int a, int b, int c) {
      GL11.glStencilFunc(a, b, c);
   }

   public static void glStencilOp(int a, int b, int c) {
      GLState.StencilOp.set((pzoptT() == null ? temp3Ints : pzoptT().temp3Ints).set(a, b, c)); // pzopt: tileRecordParallel, a recording thread uses its own temps
   }

   public static void glStencilOpA(int a, int b, int c) {
      GL11.glStencilOp(a, b, c);
   }

   public static void glTexParameteri(int a, int b, int c) {
      SpriteRenderer.instance.glTexParameteri(a, b, c);
   }

   public static void glTexParameteriActual(int glTexture2d, int glTextureMagFilter, int glLinear) {
      GL11.glTexParameteri(glTexture2d, glTextureMagFilter, glLinear);
   }

   public static void glStencilMask(int a) {
      GLState.StencilMask.set((pzoptT() == null ? tempInt : pzoptT().tempInt).set(a)); // pzopt: tileRecordParallel, a recording thread uses its own temps
   }

   public static void glStencilMaskA(int a) {
      GL11.glStencilMask(a);
   }

   public static void glDisable(int a) {
      if (a == 3008) {
         disableAlphaTest();
      } else if (a == 3042) {
         disableBlend();
      } else if (a == 2929) {
         disableDepthTest();
      } else if (a == 3089) {
         disableScissorTest();
      } else if (a == 2960) {
         disableStencilTest();
      } else {
         SpriteRenderer.instance.glDisable(a);
      }
   }

   public static void glClear(int a) {
      SpriteRenderer.instance.glClear(a);
   }

   public static void glClearA(int a) {
      GL11.glClear(a);
   }

   public static void glDisableA(int a) {
      GL11.glDisable(a);
   }

   public static void glLoadIdentity() {
      SpriteRenderer.instance.glLoadIdentity();
   }

   public static void glBind(Texture offscreenTexture) {
      SpriteRenderer.instance.glBind(offscreenTexture.getID());
   }

   public static void enableAlphaTest() {
      GLState.AlphaTest.set(CBooleanValue.TRUE);
   }

   public static void disableAlphaTest() {
      GLState.AlphaTest.set(CBooleanValue.FALSE);
   }

   public static void enableBlend() {
      GLState.Blend.set(CBooleanValue.TRUE);
   }

   public static void disableBlend() {
      GLState.Blend.set(CBooleanValue.FALSE);
   }

   public static void enableDepthTest() {
      GLState.DepthTest.set(CBooleanValue.TRUE);
   }

   public static void disableDepthTest() {
      GLState.DepthTest.set(CBooleanValue.FALSE);
   }

   public static void enableScissorTest() {
      GLState.ScissorTest.set(CBooleanValue.TRUE);
   }

   public static void disableScissorTest() {
      GLState.ScissorTest.set(CBooleanValue.FALSE);
   }

   public static void enableStencilTest() {
      GLState.StencilTest.set(CBooleanValue.TRUE);
   }

   public static void disableStencilTest() {
      GLState.StencilTest.set(CBooleanValue.FALSE);
   }

   public static boolean isMaxZoomLevel() {
      return SpriteRenderer.instance.isMaxZoomLevel();
   }

   public static boolean isMinZoomLevel() {
      return SpriteRenderer.instance.isMinZoomLevel();
   }
}
