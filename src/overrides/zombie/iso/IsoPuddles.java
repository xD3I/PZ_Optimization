package zombie.iso;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;
import org.lwjglx.BufferUtils;
import zombie.GameTime;
import zombie.UsedFromLua;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.SharedVertexBufferObjects;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.interfaces.ITexture;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameServer;
import zombie.popman.ObjectPool;
import zombie.tileDepth.TileSeamManager.Tiles;

@UsedFromLua
public final class IsoPuddles {
   static {
      pzopt.Overrides.onClassLoaded("zombie.iso.IsoPuddles");
   }

   public Shader effect;
   private float puddlesWindAngle;
   private float puddlesWindIntensity;
   private float puddlesTime;
   private final Vector2f puddlesParamWindInt;
   public static boolean leakingPuddlesInTheRoom;
   private Texture texHm;
   private ByteBuffer bufferHm;
   private int apiId;
   private static IsoPuddles instance;
   private static boolean isShaderEnable;
   static final int BYTES_PER_FLOAT = 4;
   static final int FLOATS_PER_VERTEX = 8;
   static final int BYTES_PER_VERTEX = 32;
   static final int VERTICES_PER_SQUARE = 4;
   public static final SharedVertexBufferObjects VBOs = new SharedVertexBufferObjects(32);
   private final IsoPuddles.RenderData[][] renderData = new IsoPuddles.RenderData[3][4];
   private final Vector4f shaderOffset = new Vector4f();
   private final Vector4f shaderOffsetMain = new Vector4f();
   private final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
   public static final int BOOL_MAX = 0;
   public static final int FLOAT_RAIN = 0;
   public static final int FLOAT_WETGROUND = 1;
   public static final int FLOAT_MUDDYPUDDLES = 2;
   public static final int FLOAT_PUDDLESSIZE = 3;
   public static final int FLOAT_RAININTENSITY = 4;
   public static final int FLOAT_MAX = 5;
   private IsoPuddles.PuddlesFloat rain;
   private IsoPuddles.PuddlesFloat wetGround;
   private IsoPuddles.PuddlesFloat muddyPuddles;
   private IsoPuddles.PuddlesFloat puddlesSize;
   private IsoPuddles.PuddlesFloat rainIntensity;
   private long lastDataTime;
   private float wetGroundNetwork;
   private float muddyPuddlesNetwork;
   private float puddlesSizeNetwork;
   private float wetGroundStart;
   private float muddyPuddlesStart;
   private float puddlesSizeStart;
   private final IsoPuddles.PuddlesFloat[] climateFloats = new IsoPuddles.PuddlesFloat[5];
   private final ObjectPool<IsoPuddles.RenderToChunkTexture> renderToChunkTexturePool = new ObjectPool(
      IsoPuddles.RenderToChunkTexture::new, "IsoPuddles.renderToChunkTexturePool"
   );

   public static synchronized IsoPuddles getInstance() {
      if (instance == null) {
         instance = new IsoPuddles();
      }

      return instance;
   }

   public boolean getShaderEnable() {
      return isShaderEnable;
   }

   public IsoPuddles() {
      if (GameServer.server && !GameServer.guiCommandline) {
         Core.getInstance().setPerfPuddles(3);
         this.applyPuddlesQuality();
         this.puddlesParamWindInt = new Vector2f(0.0F);
         this.setup();
      } else {
         this.texHm = Texture.getSharedTexture("media/textures/puddles_hm.png");
         RenderThread.invokeOnRenderContext(() -> {
            if (GL.getCapabilities().OpenGL30) {
               this.apiId = 1;
            }

            if (GL.getCapabilities().GL_ARB_framebuffer_object) {
               this.apiId = 2;
            }

            if (GL.getCapabilities().GL_EXT_framebuffer_object) {
               this.apiId = 3;
            }
         });
         this.applyPuddlesQuality();
         this.puddlesParamWindInt = new Vector2f(0.0F);

         for (int i = 0; i < this.renderData.length; i++) {
            for (int pn = 0; pn < 4; pn++) {
               this.renderData[i][pn] = new IsoPuddles.RenderData();
            }
         }

         this.setup();
      }
   }

   // pzopt: with Config.puddleEarlyZ the media/shaders/pzopt_puddles_*.vert|frag copies are used: same colour math,
   // depth from the vertex instead of a gl_FragDepth write, so occluded wet ground is rejected before shading
   private static String pzoptShaderName(String stock) {
      return pzopt.Overrides.enabled() && pzopt.Config.PUDDLE_EARLY_Z ? "pzopt_" + stock : stock;
   }

   private static void pzoptLogShader(Shader effect) { // pzopt: which puddle program is live and whether it compiled
      pzopt.Log.info("puddles: shader " + effect.getName() + " id=" + effect.getID()
         + " compiled=" + (effect.getProgram() != null && effect.getProgram().isCompiled()));
   }

   public void applyPuddlesQuality() {
      leakingPuddlesInTheRoom = Core.getInstance().getPerfPuddles() == 0;
      if (Core.getInstance().getPerfPuddles() == 3) {
         isShaderEnable = false;
      } else {
         isShaderEnable = true;
         if (PerformanceSettings.puddlesQuality == 2) {
            RenderThread.invokeOnRenderContext(() -> {
               this.effect = new PuddlesShader(pzoptShaderName("puddles_lq")); // pzopt: puddleEarlyZ variant
               this.effect.Start();
               this.effect.End();
               pzoptLogShader(this.effect); // pzopt
            });
         }

         if (PerformanceSettings.puddlesQuality == 1) {
            RenderThread.invokeOnRenderContext(() -> {
               this.effect = new PuddlesShader(pzoptShaderName("puddles_mq")); // pzopt: puddleEarlyZ variant
               this.effect.Start();
               this.effect.End();
               pzoptLogShader(this.effect); // pzopt
            });
         }

         if (PerformanceSettings.puddlesQuality == 0) {
            RenderThread.invokeOnRenderContext(() -> {
               this.effect = new PuddlesShader(pzoptShaderName("puddles_hq")); // pzopt: puddleEarlyZ variant
               this.effect.Start();
               this.effect.End();
               pzoptLogShader(this.effect); // pzopt
            });
         }
      }
   }

   public Vector4f getShaderOffset() {
      int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(playerIndex);
      float jx = -camera.fixJigglyModelsX * camera.zoom;
      float jy = -camera.fixJigglyModelsY * camera.zoom;
      return this.shaderOffset.set(camera.getOffX() + jx, camera.getOffY() + jy, camera.offscreenWidth, camera.offscreenHeight);
   }

   public Vector4f getShaderOffsetMain() {
      int playerIndex = IsoCamera.frameState.playerIndex;
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float jx = -camera.fixJigglyModelsX * camera.zoom;
      float jy = -camera.fixJigglyModelsY * camera.zoom;
      return this.shaderOffsetMain
         .set(camera.getOffX() + jx, camera.getOffY() + jy, IsoCamera.getOffscreenWidth(playerIndex), IsoCamera.getOffscreenHeight(playerIndex));
   }

   public boolean shouldRenderPuddles() {
      if (!DebugOptions.instance.weather.waterPuddles.getValue()) {
         return false;
      } else if (!this.getShaderEnable()) {
         return false;
      } else if (!Core.getInstance().getUseShaders()) {
         return false;
      } else {
         return Core.getInstance().getPerfPuddles() == 3 ? false : this.wetGround.getFinalValue() != 0.0 || this.puddlesSize.getFinalValue() != 0.0;
      }
   }

   public void render(ArrayList<IsoGridSquare> grid, int z) {
      if (DebugOptions.instance.weather.waterPuddles.getValue()) {
         int stateIndex = SpriteRenderer.instance.getMainStateIndex();
         int playerIndex = IsoCamera.frameState.playerIndex;
         IsoPuddles.RenderData threadData = this.renderData[stateIndex][playerIndex];
         if (!grid.isEmpty()) {
            if (this.getShaderEnable()) {
               if (Core.getInstance().getUseShaders()) {
                  if (Core.getInstance().getPerfPuddles() != 3) {
                     if (z <= 0 || Core.getInstance().getPerfPuddles() <= 0) {
                        if (this.wetGround.getFinalValue() != 0.0 || this.puddlesSize.getFinalValue() != 0.0) {
                           int firstSquare = threadData.numSquares;

                           for (int i = 0; i < grid.size(); i++) {
                              IsoPuddlesGeometry puddlesGeometry = grid.get(i).getPuddles();
                              if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
                                 puddlesGeometry.updateLighting(playerIndex);
                                 threadData.addSquare(z, puddlesGeometry, null);
                              }
                           }

                           int numSquares = threadData.numSquares - firstSquare;
                           if (numSquares > 0) {
                              SpriteRenderer.instance.drawPuddles(playerIndex, z, firstSquare, numSquares);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   // pzopt: the three pieces of render() that pzopt.PuddleCache needs separately: the guards, the stock
   // packing loop without the draw, and the draw; plus a bulk append of pre-packed squares (Config puddleCache).
   public boolean pzoptCanRender(int z) { // pzopt
      return DebugOptions.instance.weather.waterPuddles.getValue()
         && this.getShaderEnable()
         && Core.getInstance().getUseShaders()
         && Core.getInstance().getPerfPuddles() != 3
         && (z <= 0 || Core.getInstance().getPerfPuddles() <= 0)
         && (this.wetGround.getFinalValue() != 0.0 || this.puddlesSize.getFinalValue() != 0.0);
   }

   private IsoPuddles.RenderData pzoptThreadData() { // pzopt
      return this.renderData[SpriteRenderer.instance.getMainStateIndex()][IsoCamera.frameState.playerIndex];
   }

   public int pzoptNumSquares() { // pzopt
      return this.pzoptThreadData().numSquares;
   }

   public float[] pzoptData() { // pzopt: valid until the next pack/append (the array can be reallocated)
      return this.pzoptThreadData().data;
   }

   public void pzoptPack(ArrayList<IsoGridSquare> grid, int z) { // pzopt: the loop of render(), no draw
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoPuddles.RenderData threadData = this.pzoptThreadData();

      for (int i = 0; i < grid.size(); i++) {
         IsoPuddlesGeometry puddlesGeometry = grid.get(i).getPuddles();
         if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
            puddlesGeometry.updateLighting(playerIndex);
            threadData.addSquare(z, puddlesGeometry, null);
         }
      }
   }

   public int pzoptAppend(float[] packed, int count, int z) { // pzopt: returns the index of the first appended square
      IsoPuddles.RenderData threadData = this.pzoptThreadData();
      if (threadData.data == null) {
         threadData.data = new float[threadData.capacity * 4 * 8];
      }

      while (threadData.numSquares + count > threadData.capacity) {
         threadData.capacity += 128;
      }

      if (threadData.data.length < threadData.capacity * 4 * 8) {
         threadData.data = Arrays.copyOf(threadData.data, threadData.capacity * 4 * 8);
      }

      int first = threadData.numSquares;
      System.arraycopy(packed, 0, threadData.data, first * 32, count * 32);
      threadData.numSquares += count;
      threadData.squaresPerLevel[z + 32] += count;
      return first;
   }

   public void pzoptTruncate(int numSquares, int z) { // pzopt: drop squares packed after index numSquares (puddleVbo scratch use)
      IsoPuddles.RenderData threadData = this.pzoptThreadData();
      int drop = threadData.numSquares - numSquares;
      if (drop > 0) {
         threadData.numSquares = numSquares;
         threadData.squaresPerLevel[z + 32] -= drop;
      }
   }

   public void pzoptDraw(int z, int firstSquare, int numSquares) { // pzopt: the tail of render()
      SpriteRenderer.instance.drawPuddles(IsoCamera.frameState.playerIndex, z, firstSquare, numSquares);
   }

   public void puddlesProjection(Matrix4f projection) {
      int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
      PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(playerIndex);
      projection.setOrtho(camera.getOffX(), camera.getOffX() + camera.offscreenWidth, camera.getOffY() + camera.offscreenHeight, camera.getOffY(), -1.0F, 1.0F);
   }

   public void puddlesGeometry(int firstSquare, int numSquares) {
      while (numSquares > 0) {
         int some = this.renderSome(firstSquare, numSquares, false);
         firstSquare += some;
         numSquares -= some;
      }

      SpriteRenderer.ringBuffer.restoreVbos = true;
   }

   private int renderSome(int firstSquare, int numSquares, boolean bRenderToChunkTexture) {
      VBOs.next();
      FloatBuffer vertices = VBOs.vertices;
      ShortBuffer indices = VBOs.indices;
      int aVertex = 0;
      int aColor = 1;
      int aDirNE = 2;
      int aDirNW = 3;
      int aDirAll = 4;
      int aDirNone = 5;
      int aFragDepth = 6;
      GL20.glEnableVertexAttribArray(4);
      GL20.glEnableVertexAttribArray(5);
      GL20.glEnableVertexAttribArray(6);
      GL20.glVertexAttribPointer(2, 1, 5126, true, 32, 0L);
      GL20.glVertexAttribPointer(3, 1, 5126, true, 32, 4L);
      GL20.glVertexAttribPointer(4, 1, 5126, true, 32, 8L);
      GL20.glVertexAttribPointer(5, 1, 5126, true, 32, 12L);
      GL20.glVertexAttribPointer(0, 2, 5126, false, 32, 16L);
      GL20.glVertexAttribPointer(1, 4, 5121, true, 32, 24L);
      GL20.glVertexAttribPointer(6, 1, 5126, true, 32, 28L);
      int stateIndex = SpriteRenderer.instance.getRenderStateIndex();
      int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
      IsoPuddles.RenderData threadData = this.renderData[stateIndex][playerIndex];
      int numVertices = Math.min(numSquares * 4, VBOs.bufferSizeVertices);
      vertices.put(threadData.data, firstSquare * 4 * 8, numVertices * 8);
      int vertexCursor = 0;
      int indexCursor = 0;

      for (int i = 0; i < numVertices / 4; i++) {
         indices.put((short)vertexCursor);
         indices.put((short)(vertexCursor + 1));
         indices.put((short)(vertexCursor + 2));
         indices.put((short)vertexCursor);
         indices.put((short)(vertexCursor + 2));
         indices.put((short)(vertexCursor + 3));
         vertexCursor += 4;
         indexCursor += 6;
      }

      VBOs.unmap();
      if (bRenderToChunkTexture) {
         GL11.glDepthMask(false);
      } else {
         GL11.glDepthMask(false);
         GL11.glBlendFunc(770, 771);
      }

      GL11.glEnable(2929);
      GL11.glDepthFunc(515);
      boolean pzoptClamp = pzopt.Overrides.enabled() && pzopt.Config.PUDDLE_EARLY_Z; // pzopt: puddleEarlyZ, see PuddleVbo.Gl.draw
      if (pzoptClamp) {
         GL11.glEnable(0x864F); // GL_DEPTH_CLAMP: negative chunk-relative depths are clamped per fragment, not clipped
      }
      int start = 0;
      int length = vertexCursor;
      int startIndex = 0;
      int endIndex = indexCursor;
      GL12.glDrawRangeElements(4, 0, 0 + length, endIndex - 0, 5123, 0L);
      if (pzoptClamp) {
         GL11.glDisable(0x864F);
      }
      GL20.glDisableVertexAttribArray(4);
      GL20.glDisableVertexAttribArray(5);
      GL20.glDisableVertexAttribArray(6);
      return numVertices / 4;
   }

   public void update(ClimateManager cm) {
      this.puddlesWindAngle = cm.getCorrectedWindAngleIntensity();
      this.puddlesWindIntensity = cm.getWindIntensity();
      this.rain.setFinalValue(cm.getRainIntensity());
      float timeMultiplier = GameTime.getInstance().getThirtyFPSMultiplier();
      float dryingGround = 2.0E-5F * timeMultiplier * cm.getTemperature();
      float dryingPuddles = 2.0E-5F * timeMultiplier;
      float particleDeposition = 2.0E-4F * timeMultiplier;
      float rain = this.rain.getFinalValue();
      rain = rain * rain * 0.05F * timeMultiplier;
      this.rainIntensity.setFinalValue(this.rain.getFinalValue() * 2.0F);
      if (this.lastDataTime != 0L && System.currentTimeMillis() > this.lastDataTime) {
         float interpolation = Math.min(1.0F, (float)(System.currentTimeMillis() - this.lastDataTime) / 10000.0F);
         this.wetGround.setFinalValue(this.wetGroundStart + (this.wetGroundNetwork - this.wetGroundStart) * interpolation);
         this.puddlesSize.setFinalValue(this.puddlesSizeStart + (this.puddlesSizeNetwork - this.puddlesSizeStart) * interpolation);
         this.muddyPuddles.setFinalValue(this.muddyPuddlesStart + (this.muddyPuddlesNetwork - this.muddyPuddlesStart) * interpolation);
      } else {
         this.wetGround.addFinalValue(rain);
         this.muddyPuddles.addFinalValue(rain * 2.0F);
         this.puddlesSize.addFinalValueForMax(rain * 0.01F, 0.7F);
         if (rain == 0.0) {
            this.wetGround.addFinalValue(-dryingGround);
            this.muddyPuddles.addFinalValue(-particleDeposition);
         }

         if (this.wetGround.getFinalValue() == 0.0) {
            this.puddlesSize.addFinalValue(-dryingPuddles);
         }
      }

      this.puddlesTime = this.puddlesTime + 0.0166F * GameTime.getInstance().getMultiplier();
      this.puddlesParamWindInt
         .add(
            (float)Math.sin(this.puddlesWindAngle * 6.0F) * this.puddlesWindIntensity * 0.05F,
            (float)Math.cos(this.puddlesWindAngle * 6.0F) * this.puddlesWindIntensity * 0.05F
         );
   }

   public void applyNetworkUpdate(float wetGroundValue, float puddlesSizeValue, float muddyPuddlesValue) {
      this.wetGroundStart = this.wetGround.getFinalValue();
      this.puddlesSizeStart = this.puddlesSize.getFinalValue();
      this.muddyPuddlesStart = this.muddyPuddles.getFinalValue();
      this.wetGroundNetwork = wetGroundValue;
      this.puddlesSizeNetwork = puddlesSizeValue;
      this.muddyPuddlesNetwork = muddyPuddlesValue;
      this.lastDataTime = System.currentTimeMillis();
   }

   public float getMuddyPuddlesFinalValue() {
      return this.muddyPuddles.getFinalValue();
   }

   public float getShaderTime() {
      return this.puddlesTime;
   }

   public float getPuddlesSize() {
      return this.puddlesSize.getFinalValue();
   }

   public ITexture getHMTexture() {
      return this.texHm;
   }

   public ByteBuffer getHMTextureBuffer() {
      return this.bufferHm;
   }

   public void updateHMTextureBuffer() {
      if (PerformanceSettings.puddlesQuality == 2) {
         if (this.bufferHm == null) {
            try {
               int bufferSize = this.texHm.getWidthHW() * this.texHm.getHeightHW() * 4;
               this.bufferHm = MemoryUtil.memAlloc(bufferSize);
               GL21.glGetTexImage(3553, 0, 6408, 5121, this.bufferHm);
            } catch (Exception e) {
               DebugType.General
                  .printException(e, "IsoPuddles - Unable to create HMTextureBuffer. Low quality puddles will be non-interactable.", LogSeverity.Error);
               this.freeHMTextureBuffer();
            }
         }
      } else {
         this.freeHMTextureBuffer();
      }
   }

   public void freeHMTextureBuffer() {
      if (this.bufferHm != null) {
         MemoryUtil.memFree(this.bufferHm);
         this.bufferHm = null;
      }
   }

   public FloatBuffer getPuddlesParams(int z) {
      this.floatBuffer.clear();
      this.floatBuffer.put(this.puddlesParamWindInt.x);
      this.floatBuffer.put(this.muddyPuddles.getFinalValue());
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(this.puddlesParamWindInt.y);
      this.floatBuffer.put(this.wetGround.getFinalValue());
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(this.puddlesWindIntensity * 1.0F);
      this.floatBuffer.put(this.puddlesSize.getFinalValue());
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(z);
      this.floatBuffer.put(this.rainIntensity.getFinalValue());
      this.floatBuffer.put(0.0F);
      this.floatBuffer.put(0.0F);
      this.floatBuffer.flip();
      return this.floatBuffer;
   }

   public float getRainIntensity() {
      return this.rainIntensity.getFinalValue();
   }

   public int getFloatMax() {
      return 5;
   }

   public int getBoolMax() {
      return 0;
   }

   public IsoPuddles.PuddlesFloat getPuddlesFloat(int id) {
      if (id >= 0 && id < 5) {
         return this.climateFloats[id];
      }

      DebugLog.log("ERROR: Climate: cannot get float override id.");
      return null;
   }

   private IsoPuddles.PuddlesFloat initClimateFloat(int id, String name) {
      if (id >= 0 && id < 5) {
         return this.climateFloats[id].init(id, name);
      }

      DebugLog.log("ERROR: Climate: cannot get float override id.");
      return null;
   }

   private void setup() {
      for (int i = 0; i < this.climateFloats.length; i++) {
         this.climateFloats[i] = new IsoPuddles.PuddlesFloat();
      }

      this.rain = this.initClimateFloat(0, Translator.getText("IGUI_PuddlesControl_Rain", new Object[0]));
      this.wetGround = this.initClimateFloat(1, Translator.getText("IGUI_PuddlesControl_WetGround", new Object[0]));
      this.muddyPuddles = this.initClimateFloat(2, Translator.getText("IGUI_PuddlesControl_MudPuddle", new Object[0]));
      this.puddlesSize = this.initClimateFloat(3, Translator.getText("IGUI_PuddlesControl_PuddleSize", new Object[0]));
      this.rainIntensity = this.initClimateFloat(4, Translator.getText("IGUI_PuddlesControl_RainIntensity", new Object[0]));
   }

   public void clearThreadData() {
      int stateIndex = SpriteRenderer.instance.getMainStateIndex();
      int playerIndex = IsoCamera.frameState.playerIndex;
      IsoPuddles.RenderData threadData = this.renderData[stateIndex][playerIndex];
      threadData.clear();
   }

   public void renderToChunkTexture(ArrayList<IsoGridSquare> squares, int z) {
      if (!squares.isEmpty()) {
         if (z <= 0 || Core.getInstance().getPerfPuddles() <= 0) {
            int stateIndex = SpriteRenderer.instance.getMainStateIndex();
            int playerIndex = IsoCamera.frameState.playerIndex;
            IsoPuddles.RenderData threadData = this.renderData[stateIndex][playerIndex];
            int firstSquare = threadData.numSquares;

            for (int i = 0; i < squares.size(); i++) {
               IsoGridSquare square = squares.get(i);
               IsoPuddlesGeometry puddlesGeometry = square.getPuddles();
               if (puddlesGeometry != null && puddlesGeometry.shouldRender()) {
                  puddlesGeometry.updateLighting(playerIndex);
                  threadData.addSquare(z, puddlesGeometry, null);
                  if (DebugOptions.instance.fboRenderChunk.seamFix2.getValue()) {
                     int chunksPerWidth = 8;
                     if (PZMath.coordmodulo(square.x, 8) == 7) {
                        threadData.addSquare(z, puddlesGeometry, Tiles.FloorEast);
                     }

                     if (PZMath.coordmodulo(square.y, 8) == 7) {
                        threadData.addSquare(z, puddlesGeometry, Tiles.FloorSouth);
                     }
                  }
               }
            }

            if (threadData.numSquares != firstSquare) {
               IsoPuddles.RenderToChunkTexture rtct = (IsoPuddles.RenderToChunkTexture)this.renderToChunkTexturePool.alloc();
               FBORenderChunk renderChunk = FBORenderChunkManager.instance.renderChunk;
               rtct.renderChunkX = IsoUtils.XToScreen(renderChunk.chunk.wx * 8, renderChunk.chunk.wy * 8, z, 0);
               rtct.renderChunkY = IsoUtils.YToScreen(renderChunk.chunk.wx * 8, renderChunk.chunk.wy * 8, z, 0);
               rtct.renderChunkWidth = renderChunk.w;
               rtct.renderChunkHeight = renderChunk.h;
               int chunkFloorYSpan = 512;
               int numLevels = renderChunk.getTopLevel() - renderChunk.getMinLevel() + 1;
               rtct.renderChunkBottom = 512 + numLevels * 192;
               rtct.renderChunkBottom = rtct.renderChunkBottom + FBORenderLevels.extraHeightForJumboTrees(renderChunk.getMinLevel(), renderChunk.getTopLevel());
               rtct.renderChunkMinZ = renderChunk.getMinLevel();
               rtct.highRes = renderChunk.highRes;
               rtct.playerIndex = playerIndex;
               rtct.z = z;
               rtct.firstSquare = firstSquare;
               rtct.numSquares = threadData.numSquares - firstSquare;
               SpriteRenderer.instance.drawGeneric(rtct);
            }
         }
      }
   }

   public float getWetGroundFinalValue() {
      return this.wetGround.getFinalValue();
   }

   public float getPuddlesSizeFinalValue() {
      return this.puddlesSize.getFinalValue();
   }

   @UsedFromLua
   public static class PuddlesFloat {
      protected float finalValue;
      private boolean isAdminOverride;
      private float adminValue;
      private final float min = 0.0F;
      private final float max = 1.0F;
      private final float delta = 0.01F;
      private int id;
      private String name;

      public IsoPuddles.PuddlesFloat init(int id, String name) {
         this.id = id;
         this.name = name;
         return this;
      }

      public int getID() {
         return this.id;
      }

      public String getName() {
         return this.name;
      }

      public float getMin() {
         return 0.0F;
      }

      public float getMax() {
         return 1.0F;
      }

      public void setEnableAdmin(boolean b) {
         this.isAdminOverride = b;
      }

      public boolean isEnableAdmin() {
         return this.isAdminOverride;
      }

      public void setAdminValue(float f) {
         this.adminValue = Math.max(0.0F, Math.min(1.0F, f));
      }

      public float getAdminValue() {
         return this.adminValue;
      }

      public void setFinalValue(float f) {
         this.finalValue = Math.max(0.0F, Math.min(1.0F, f));
      }

      public void addFinalValue(float f) {
         this.finalValue = Math.max(0.0F, Math.min(1.0F, this.finalValue + f));
      }

      public void addFinalValueForMax(float f, float maximum) {
         this.finalValue = Math.max(0.0F, Math.min(maximum, this.finalValue + f));
      }

      public float getFinalValue() {
         return this.isAdminOverride ? this.adminValue : this.finalValue;
      }

      public void interpolateFinalValue(float f) {
         if (Math.abs(this.finalValue - f) < 0.01F) {
            this.finalValue = f;
         } else if (f > this.finalValue) {
            this.finalValue += 0.01F;
         } else {
            this.finalValue -= 0.01F;
         }
      }

      private void calculate() {
         if (this.isAdminOverride) {
            this.finalValue = this.adminValue;
         }
      }
   }

   private static final class RenderData {
      final int[] squaresPerLevel = new int[64];
      int numSquares;
      int capacity = 512;
      float[] data;

      RenderData() {
      }

      void clear() {
         this.numSquares = 0;
         Arrays.fill(this.squaresPerLevel, 0);
      }

      void addSquare(int z, IsoPuddlesGeometry pg, Tiles seamFix2) {
         int verticesPerSquare = 4;
         if (this.data == null) {
            this.data = new float[this.capacity * 4 * 8];
         }

         if (this.numSquares + 1 > this.capacity) {
            this.capacity += 128;
            this.data = Arrays.copyOf(this.data, this.capacity * 4 * 8);
         }

         int playerIndex = IsoCamera.frameState.playerIndex;
         PlayerCamera camera = IsoCamera.cameras[playerIndex];
         float jx = camera.fixJigglyModelsX * camera.zoom;
         float jy = camera.fixJigglyModelsY * camera.zoom;
         int n = this.numSquares * 4 * 8;

         for (int i = 0; i < 4; i++) {
            this.data[n++] = pg.pdne[i];
            this.data[n++] = pg.pdnw[i];
            this.data[n++] = pg.pda[i];
            this.data[n++] = pg.pnon[i];
            this.data[n++] = pg.x[i] + jx;
            this.data[n++] = pg.y[i] + jy;
            this.data[n++] = Float.intBitsToFloat(pg.color[i]);
            if (DebugOptions.instance.fboRenderChunk.nolighting.getValue()) {
               this.data[n - 1] = Float.intBitsToFloat(-1);
            }

            float dx = 0.0F;
            float dy = 0.0F;
            if (i == 2 || i == 3) {
               dx = 1.0F;
            }

            if (i == 1 || i == 2) {
               dy = 1.0F;
            }

            dx += camera.fixJigglyModelsSquareX;
            dy += camera.fixJigglyModelsSquareY;
            this.data[n++] = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                     PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                     pg.square.x + dx,
                     pg.square.y + dy,
                     pg.square.z
                  )
                  .depthStart
               - 1.0E-4F;
            if (FBORenderChunkManager.instance.isCaching()) {
               this.data[n - 1] = this.data[n - 1]
                  - IsoDepthHelper.getChunkDepthData(
                        PZMath.fastfloor(IsoCamera.frameState.camCharacterX / 8.0F),
                        PZMath.fastfloor(IsoCamera.frameState.camCharacterY / 8.0F),
                        pg.square.chunk.wx,
                        pg.square.chunk.wy,
                        z
                     )
                     .depthStart;
               if (PZMath.coordmodulo(pg.square.x, 8) == 0 && dx == 0.0F) {
                  this.data[n - 4] = this.data[n - 4] - 2.0F;
                  this.data[n - 3]--;
               }

               if (seamFix2 == Tiles.FloorSouth) {
                  this.data[n - 1] = this.data[n - 1] - 0.0028867084F;
                  this.data[n - 4] = this.data[n - 4] - (dy == 1.0F ? 6.0F : 64.0F);
                  this.data[n - 3] = this.data[n - 3] + (dy == 1.0F ? 3.0F : 32.0F);
               }

               if (seamFix2 == Tiles.FloorEast) {
                  this.data[n - 1] = this.data[n - 1] - 0.0028867084F;
                  this.data[n - 4] = this.data[n - 4] + (dx == 1.0F ? 6.0F : 64.0F);
                  this.data[n - 3] = this.data[n - 3] + (dx == 1.0F ? 3.0F : 32.0F);
               }
            }
         }

         this.numSquares++;
         this.squaresPerLevel[z + 32]++;
      }
   }

   private static final class RenderToChunkTexture extends GenericDrawer {
      float renderChunkX;
      float renderChunkY;
      int renderChunkWidth;
      int renderChunkHeight;
      int renderChunkBottom;
      int renderChunkMinZ;
      boolean highRes;
      int playerIndex;
      int z;
      int firstSquare;
      int numSquares;

      public void render() {
         GL11.glPushClientAttrib(-1);
         GL11.glPushAttrib(1048575);
         Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
         int chunkFloorYSpan = 512;
         if (this.highRes) {
            projection.setOrtho(-this.renderChunkWidth / 4.0F, this.renderChunkWidth / 4.0F, 0.0F - this.z * 192, 512.0F - this.z * 192, -1.0F, 1.0F);
         } else {
            projection.setOrtho(-this.renderChunkWidth / 2.0F, this.renderChunkWidth / 2.0F, 0.0F - this.z * 192, 512.0F - this.z * 192, -1.0F, 1.0F);
         }

         Core.getInstance().projectionMatrixStack.push(projection);
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.identity();
         Core.getInstance().modelViewMatrixStack.push(modelView);
         if (this.highRes) {
            GL11.glViewport(0, (this.renderChunkBottom - 512 - (this.z - this.renderChunkMinZ) * 192) * 2, this.renderChunkWidth, 1024);
         } else {
            GL11.glViewport(0, this.renderChunkBottom - 512 - (this.z - this.renderChunkMinZ) * 192, this.renderChunkWidth, 512);
         }

         int shaderID = IsoPuddles.getInstance().effect.getID();
         ShaderHelper.glUseProgramObjectARB(shaderID);
         Shader shader = (Shader)Shader.ShaderMap.get(shaderID);
         if (shader instanceof PuddlesShader puddlesShader) {
            puddlesShader.updatePuddlesParams(this.playerIndex, this.z);
            int waterOffset = GL20.glGetUniformLocation(shaderID, "WOffset");
            float offsetX = this.renderChunkX;
            float offsetY = -this.renderChunkY;
            if (this.highRes) {
               GL20.glUniform4f(waterOffset, offsetX - 90000.0F, offsetY - 640000.0F, this.renderChunkWidth / 2, 512.0F);
               int waterViewport = GL20.glGetUniformLocation(shaderID, "WViewport");
               GL20.glUniform4f(waterViewport, 0.0F, (this.renderChunkBottom - 512 - (this.z - this.renderChunkMinZ) * 192) * 2, this.renderChunkWidth, 1024.0F);
            } else {
               GL20.glUniform4f(waterOffset, offsetX - 90000.0F, offsetY - 640000.0F, this.renderChunkWidth, 512.0F);
               int waterViewport = GL20.glGetUniformLocation(shaderID, "WViewport");
               GL20.glUniform4f(waterViewport, 0.0F, this.renderChunkBottom - 512 - (this.z - this.renderChunkMinZ) * 192, this.renderChunkWidth, 512.0F);
            }
         }

         VertexBufferObject.setModelViewProjection(shader.getProgram());
         GL14.glBlendFuncSeparate(770, 771, 1, 1);

         while (this.numSquares > 0) {
            int some = IsoPuddles.instance.renderSome(this.firstSquare, this.numSquares, true);
            this.firstSquare += some;
            this.numSquares -= some;
         }

         SpriteRenderer.ringBuffer.restoreVbos = true;
         Core.getInstance().projectionMatrixStack.pop();
         Core.getInstance().modelViewMatrixStack.pop();
         ShaderHelper.glUseProgramObjectARB(0);
         Texture.lastTextureID = -1;
         GL11.glPopAttrib();
         GL11.glPopClientAttrib();
         ShaderHelper.glUseProgramObjectARB(0);
         Texture.lastTextureID = -1;
         GLStateRenderThread.restore();
      }

      public void postRender() {
         IsoPuddles.instance.renderToChunkTexturePool.release(this);
      }
   }
}
