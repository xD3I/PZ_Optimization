package pzopt;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Opaque-only camera-occlusion contours. Game-thread hooks snapshot eligibility; render-thread
 * hooks retain the frame's prepared poses until the pre-fog finish command. Normal material draws
 * independently accumulate opaque depth, including depth-rejected surfaces behind glass.
 *
 * <p>Image unit 7 is borrowed after Sway's chunk composite and restored before later passes.
 * Uniform buffer binding 8 is reserved for the material capture control block. The startup switch
 * requires a restart because the window/translucent bake policy and compiled material programs
 * change together.
 */
public final class OccludedOutline {
  private static String colourSpec;
  private static int colourRgb = 0xFFC740;
  private static final int IMAGE_UNIT = 7;
  private static final int CONTROL_BINDING = 8;
  private static final ArrayList<ModelCandidate> MODELS = new ArrayList<>();
  private static final ArrayList<AtlasCandidate> ATLASES = new ArrayList<>();
  private static final Camera CAMERA = new Camera();
  private static final java.util.Map<Integer, java.util.Map<String, Integer>> LOCATIONS =
      new java.util.HashMap<>();
  private static FrameRequest gameFrame;

  // Published with the sprite command frame, like the model draw data; never reused across players.
  private static final class FrameRequest {
    boolean hasCandidates;
  }

  private static volatile boolean failed;
  private static boolean active;
  private static boolean worldCapture;
  private static boolean controlEnabled;
  private static boolean atlasLighting;
  private static int worldFramebuffer;
  private static int pauseDepth;
  private static int control;
  private static int dummyImage;
  private static int opaqueTexture, opaqueFbo;
  private static int candidateTexture, candidateDepth, candidateFbo;
  private static int maskTexture, maskFbo;
  private static int width, height;
  private static float jitterX, jitterY;
  private static int seedProgram, maskProgram, compositeProgram, atlasProgram, vao, meshVao;
  private static Shader captureShader, captureStaticShader;
  private static final int[] view = new int[4];
  private static final int[] oldImage = new int[6];
  private static int previousX, previousY, previousWidth, previousHeight;
  private static final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
  private static final IntBuffer controlData = BufferUtils.createIntBuffer(8);

  private OccludedOutline() {}

  /** Stable bake routing, even if this session's optional GPU pass subsequently fails. */
  public static boolean separateTransparent() {
    return Config.OCCLUDED_OUTLINES && Overrides.enabled();
  }

  /**
   * FBO creation may run on either thread; FogPass performs the capability check on the GL thread.
   */
  public static boolean depthTextureNeeded() {
    return separateTransparent() && !failed && !Core.getInstance().getUseOpenGL21();
  }

  /**
   * Game thread: normal visibility/detection, never a loaded-square or remembered-visibility test.
   */
  public static boolean eligible(IsoGameCharacter character, int player) {
    boolean visible =
        separateTransparent()
            && Config.ENHANCEMENTS_ENABLED
            && !failed
            && character instanceof IsoZombie zombie
            // Dragged/carried corpses are temporarily reanimated as otherwise living zombies.
            && !zombie.isReanimatedForGrappleOnly()
            && !character.isDead()
            && player >= 0
            && player < character.isVisibleToPlayer.length
            && character.isVisibleToPlayer[player]
            && character.getAlpha(player) >= 0.01F;
    if (visible && gameFrame != null) gameFrame.hasCandidates = true;
    return visible;
  }

  /** Render thread, invoked only while compiling a material. Failures leave its original source. */
  public static String patchShader(String file, String source) {
    if (!separateTransparent() || failed || !OccludedOutlineShaders.material(file)) return source;
    try {
      if (!GL.getCapabilities().OpenGL43 || Core.getInstance().getUseOpenGL21()) {
        throw new IllegalStateException("OpenGL 4.3 is required");
      }
      initializeControl();
      String patched = OccludedOutlineShaders.patch(file, source);
      if (patched.equals(source)) return source;
      int shader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      try {
        GL20.glShaderSource(shader, patched);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
          throw new IllegalStateException(file + ": " + GL20.glGetShaderInfoLog(shader, 4096));
        }
      } finally {
        GL20.glDeleteShader(shader);
      }
      return patched;
    } catch (Exception error) {
      fail(error);
      return source;
    }
  }

  /** Game thread: ordered after chunk compositing, before players and moving objects. */
  public static void begin(int player) {
    gameFrame = null;
    if (!separateTransparent() || !Config.ENHANCEMENTS_ENABLED || failed) return;
    FrameRequest request = new FrameRequest();
    gameFrame = request;
    SpriteRenderer.instance.drawGeneric(
        new TextureDraw.GenericDrawer() {
          @Override
          public void render() {
            beginRender(request.hasCandidates, player);
          }
        });
  }

  /** Game thread: ordered after all moving/translucent objects, before fog and object outlines. */
  public static void finish(int player) {
    if (gameFrame == null) return;
    gameFrame = null;
    SpriteRenderer.instance.drawGeneric(
        new TextureDraw.GenericDrawer() {
          @Override
          public void render() {
            finishRender();
          }
        });
  }

  /** Render thread, after a normal model draw. Slot ownership lasts until frame postRender. */
  public static void model(TextureDraw draw) {
    if (!active
        || failed
        || !draw.pzoptOccludedOutline
        || !(draw.drawer instanceof ModelSlotRenderData slot)
        || !slot.canRender()
        || slot.alpha < 0.01F
        || slot.inVehicle) return;
    ModelCamera camera = ModelCamera.instance;
    if (camera == null || !camera.useWorldIso) return;
    if (zombie.debug.DebugOptions.instance.character.debug.render.skipCharacters.getValue()
        || zombie.debug.DebugOptions.instance.debugDrawSkipDrawNonSkinnedModel.getValue()
        || Core.debug
            && (zombie.debug.DebugOptions.instance.zombieImposterRendering.getValue()
                || zombie.debug.DebugOptions.instance.model.render.wireframe.getValue())) return;
    MODELS.add(
        new ModelCandidate(
            slot,
            new ArrayList<>(slot.getModelData()),
            camera.x,
            camera.y,
            camera.z,
            camera.useAngle));
  }

  /**
   * Render thread: retain an atlas draw's actual transform, UVs and depth conversion, not an
   * estimated body shape.
   */
  public static void atlas(
      Texture texture,
      Texture depth,
      int lighting,
      float red,
      float green,
      float blue,
      float x,
      float y,
      float w,
      float h,
      float near,
      float far,
      float alpha) {
    if (!active || failed || alpha <= 0 || lighting == 0) return;
    Matrix4f matrix =
        new Matrix4f(Core.getInstance().projectionMatrixStack.peek())
            .mul(Core.getInstance().modelViewMatrixStack.peek());
    ATLASES.add(
        new AtlasCandidate(
            texture, depth, lighting, red, green, blue, x, y, w, h, near, far, alpha, matrix));
  }

  private record ModelCandidate(
      ModelSlotRenderData slot,
      ArrayList<ModelInstanceRenderData> data,
      float x,
      float y,
      float z,
      float angle) {}

  private record AtlasCandidate(
      Texture texture,
      Texture depth,
      int lighting,
      float red,
      float green,
      float blue,
      float x,
      float y,
      float w,
      float h,
      float near,
      float far,
      float alpha,
      Matrix4f matrix) {}

  /** True only during the lighting-only replay of an atlas bake, never an ordinary world draw. */
  public static boolean isAtlasLighting() {
    return atlasLighting;
  }

  /** Render-thread atlas resource cleanup; the caller retains ownership until reset. */
  public static void deleteAtlasLighting(int texture) {
    if (texture != 0)
      zombie.core.opengl.RenderThread.invokeOnRenderContext(() -> GL11.glDeleteTextures(texture));
  }

  /**
   * Bake albedo-independent lighting alongside the native atlas. Replays only when a native atlas
   * entry is baked, not per world frame. The callback repeats its prepared pose into the bitmap;
   * copying uses the same scaled, vertically flipped rectangle as DeadBodyAtlas.toBodyAtlas.
   */
  public static int captureAtlasLighting(
      int lighting,
      int atlasWidth,
      int atlasHeight,
      int entryX,
      int entryY,
      int entryWidth,
      int entryHeight,
      zombie.core.textures.TextureFBO bitmap,
      Runnable draw) {
    if (!depthTextureNeeded() || !GL.getCapabilities().OpenGL43) return lighting;
    int target = 0;
    ModelCamera camera = ModelCamera.instance;
    try (State state = new State()) {
      initializeControl();
      atlasLighting = true;
      controlEnabled = true; // Force the private mode bit into the control buffer.
      setControl(false);
      try {
        // The preceding atlas copy leaves world scissoring/state active. Clear the entire bitmap,
        // including depth, before replay; stale equal-depth fragments otherwise reject the pose.
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        draw.run();
      } finally {
        atlasLighting = false;
        controlEnabled = true;
        setControl(false);
      }
      if (lighting == 0)
        lighting =
            texture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlasWidth, atlasHeight);
      target = framebuffer(lighting, 0);
      GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glScissor(entryX, atlasHeight - entryY - entryHeight, entryWidth, entryHeight);
      GL11.glColorMask(true, true, true, true);
      GL11.glClearColor(0, 0, 0, 0);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
      int w = bitmap.getTexture().getWidth() / 8 * Core.tileScale;
      int h = bitmap.getTexture().getHeight() / 8 * Core.tileScale;
      int x = entryX - (w - entryWidth) / 2;
      int y = entryY - (h - entryHeight) / 2;
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bitmap.getBufferId());
      GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
      GL30.glBlitFramebuffer(
          0,
          0,
          bitmap.getWidth(),
          bitmap.getHeight(),
          x,
          atlasHeight - y,
          x + w,
          atlasHeight - y - h,
          GL11.GL_COLOR_BUFFER_BIT,
          GL11.GL_LINEAR);
      return lighting;
    } catch (Exception error) {
      if (lighting != 0) GL11.glDeleteTextures(lighting);
      fail(error);
      return 0;
    } finally {
      if (target != 0) GL30.glDeleteFramebuffers(target);
      ModelCamera.instance = camera;
      framebuffer(zombie.core.textures.TextureFBO.lastID);
    }
  }

  /** Render thread: TextureFBO's tracked binding excludes atlas-cache and offscreen draws. */
  public static void framebuffer(int id) {
    if (active) setControl(worldCapture && pauseDepth == 0 && id == worldFramebuffer);
  }

  /** Render thread: only depth-tested VBO geometry participates, never screen-space overlays. */
  public static void vboMaterial(boolean depthTest) {
    OutlinePlantDepth.material(depthTest);
    if (active)
      setControl(
          depthTest
              && worldCapture
              && pauseDepth == 0
              && zombie.core.textures.TextureFBO.lastID == worldFramebuffer);
  }

  /** Render thread: bracket raw-FBO model replays such as the sun-shadow atlas. */
  public static void pauseCapture() {
    OutlinePlantDepth.pause();
    pauseDepth++;
    if (active) setControl(false);
  }

  public static void resumeCapture() {
    OutlinePlantDepth.resume();
    pauseDepth--;
    if (pauseDepth < 0) throw new IllegalStateException("unbalanced outline capture scope");
    framebuffer(zombie.core.textures.TextureFBO.lastID);
  }

  /** Render thread: imposter/card model draws can bind a framebuffer outside TextureFBO. */
  public static void modelMaterial(ModelSlotRenderData slot) {
    if (active)
      setControl(
          worldCapture
              && pauseDepth == 0
              && !slot.IsRenderingToCard()
              && !slot.renderToTexture
              && ModelCamera.instance != null
              && ModelCamera.instance.useWorldIso
              && zombie.core.textures.TextureFBO.lastID == worldFramebuffer);
  }

  private static void fail(Exception error) {
    if (!failed) Log.warn("occluded outlines disabled: " + error);
    failed = true;
    if (control != 0) setControl(false);
    if (!active) releaseBuffers();
  }

  private static void initializeControl() {
    if (control != 0) return;
    int previous = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    control = GL15.glGenBuffers();
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, control);
    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 32L, GL15.GL_DYNAMIC_DRAW);
    GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, CONTROL_BINDING, control);
    controlEnabled = true; // Force initialization of every byte before a patched material can draw.
    setControl(false);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, previous);
    // Patched menu/atlas-cache programs must have valid bindings even before the first world frame.
    dummyImage = texture(GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, 1, 1);
    if (GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, IMAGE_UNIT) == 0) {
      GL42.glBindImageTexture(
          IMAGE_UNIT, dummyImage, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
    }
  }

  private static void setControl(boolean enabled) {
    if (controlEnabled == enabled) return;
    controlEnabled = enabled;
    controlData.clear();
    controlData.put(view).put(enabled ? 1 : 0).put(atlasLighting ? 1 : 0).put(0).put(0).flip();
    int previous = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, control);
    // Orphan this tiny store: earlier material draws may still be reading the previous scope.
    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, controlData, GL15.GL_STREAM_DRAW);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, previous);
  }

  private static void beginRender(boolean hasCandidates, int player) {
    // A previous interrupted frame must not retain recycled model slots or leave capture enabled.
    if (active) stopCapture();
    MODELS.clear();
    ATLASES.clear();
    if (failed || !hasCandidates) return;
    try (State state = new State()) {
      if (!GL.getCapabilities().OpenGL43 || Core.getInstance().getUseOpenGL21()) {
        throw new IllegalStateException("OpenGL 4.3 and the modern rendering path are required");
      }
      initializeControl();
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, view);
      jitterX = jitterY = 0F;
      if (RenderScale.inWorldPass()) {
        int[] rectangle = RenderScale.scaledRect(player);
        System.arraycopy(rectangle, 0, view, 0, 4);
        jitterX = RenderScale.frameJitterX();
        jitterY = RenderScale.frameJitterY();
      }
      if (view[2] <= 0 || view[3] <= 0 || Core.width <= 0 || Core.height <= 0) return;
      int sceneDepth =
          Config.OCCLUDED_OUTLINE_IGNORE_PLANTS
              ? OutlinePlantDepth.sceneTexture(state.drawFbo)
              : FogPass.sceneDepthTexture(state.drawFbo);
      if (sceneDepth == 0) throw new IllegalStateException("world depth texture unavailable");
      resize(view[2], view[3]);
      initializePrograms();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, opaqueFbo);
      screenState();
      use(seedProgram);
      bind(0, sceneDepth);
      uniform2(seedProgram, "sourceOrigin", view[0], view[1]);
      triangle();
      GL42.glMemoryBarrier(
          GL42.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
      GL11.glClearColor(0, 0, 0, 0);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, candidateFbo);
      GL11.glDepthMask(true);
      GL11.glClearDepth(1);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
      previousX = previousY = previousWidth = previousHeight = 0;
      saveImage();
      GL42.glBindImageTexture(
          IMAGE_UNIT, opaqueTexture, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
      worldFramebuffer = state.drawFbo;
      active = true;
      worldCapture = true;
      setControl(true);
    } catch (Exception error) {
      if (active) stopCapture();
      fail(error);
    }
  }

  private static void finishRender() {
    if (!active) return;
    if (failed) {
      stopCapture();
      MODELS.clear();
      ATLASES.clear();
      return;
    }
    try (State state = new State()) {
      worldCapture = false;
      setControl(false); // Our silhouette replays must not contribute to opaque world depth.
      GL42.glMemoryBarrier(
          GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
      for (ModelCandidate candidate : MODELS) renderModel(candidate);
      for (AtlasCandidate candidate : ATLASES) renderAtlas(candidate);
      if (!MODELS.isEmpty() || !ATLASES.isEmpty()) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, state.drawFbo);
        screenState();
        GL11.glViewport(view[0], view[1], width, height);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        use(compositeProgram);
        bind(0, maskTexture);
        uniform2(compositeProgram, "maskOrigin", 0, 0);
        GL20.glUniform4i(location(compositeProgram, "viewport"), view[0], view[1], width, height);
        int colour = currentColour();
        GL20.glUniform4f(
            location(compositeProgram, "outlineColor"),
            ((colour >> 16) & 255) / 255F,
            ((colour >> 8) & 255) / 255F,
            (colour & 255) / 255F,
            Config.OCCLUDED_OUTLINE_OPACITY / 100F);
        triangle();
      }
    } catch (Exception error) {
      fail(error);
    } finally {
      stopCapture();
      MODELS.clear();
      ATLASES.clear();
    }
  }

  private static void stopCapture() {
    setControl(false);
    GL42.glBindImageTexture(
        IMAGE_UNIT,
        oldImage[0],
        oldImage[1],
        oldImage[2] != 0,
        oldImage[3],
        oldImage[4],
        oldImage[5]);
    active = false;
    worldCapture = false;
    if (failed) releaseBuffers();
  }

  private static void saveImage() {
    int[] names = {
      GL42.GL_IMAGE_BINDING_NAME,
      GL42.GL_IMAGE_BINDING_LEVEL,
      GL42.GL_IMAGE_BINDING_LAYERED,
      GL42.GL_IMAGE_BINDING_LAYER,
      GL42.GL_IMAGE_BINDING_ACCESS,
      GL42.GL_IMAGE_BINDING_FORMAT
    };
    for (int i = 0; i < names.length; i++) oldImage[i] = GL30.glGetIntegeri(names[i], IMAGE_UNIT);
  }

  private static void renderModel(ModelCandidate candidate) {
    ModelSlotRenderData slot = candidate.slot;
    ModelCamera previous = ModelCamera.instance;
    CAMERA.x = candidate.x;
    CAMERA.y = candidate.y;
    CAMERA.z = candidate.z;
    CAMERA.useAngle = candidate.angle;
    ModelCamera.instance = CAMERA;
    try {
      Model.CharacterModelCameraBegin(slot);
      try {
        Matrix4f mvp =
            new Matrix4f(Core.getInstance().projectionMatrixStack.peek())
                .mul(Core.getInstance().modelViewMatrixStack.peek());
        OccludedOutlineBounds bounds = modelBounds(slot, candidate.data, mvp);
        if (bounds.empty()) return;
        clearCandidate(bounds);
        float depth =
            slot.squareDepth
                - (VertexBufferObject.getDepthValueAt(0, 0, 0) + 1F) / 2F
                + 0.5F
                - 1.0E-4F;
        for (ModelInstanceRenderData data : candidate.data) {
          if (data.model == null
              || data.model.mesh == null
              || data.tex == null && data.model.tex == null) continue;
          Shader shader = data.model.isStatic ? captureStaticShader : captureShader;
          // Follow DrawChar's per-material culling without touching Model.effect or gameplay state.
          var script = data.modelInstance.modelScript;
          if (script != null && script.cullFace == 0) GL11.glDisable(GL11.GL_CULL_FACE);
          else {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(
                script == null || script.cullFace == -1 ? GL11.GL_FRONT : script.cullFace);
          }
          shader.Start();
          try {
            shader.startCharacter(slot, data);
            shader.setScale(slot.finalScale);
            shader.setTargetDepth(depth);
            data.model.mesh.Draw(shader);
          } finally {
            shader.End();
          }
        }
        accumulate(bounds, slot.alpha);
      } finally {
        Model.CharacterModelCameraEnd();
      }
    } finally {
      ModelCamera.instance = previous;
    }
  }

  private static void renderAtlas(AtlasCandidate candidate) {
    OccludedOutlineBounds bounds = new OccludedOutlineBounds(width, height, jitterX, jitterY);
    bounds.point(candidate.matrix, candidate.x, candidate.y, 0);
    bounds.point(candidate.matrix, candidate.x + candidate.w, candidate.y, 0);
    bounds.point(candidate.matrix, candidate.x, candidate.y + candidate.h, 0);
    bounds.point(candidate.matrix, candidate.x + candidate.w, candidate.y + candidate.h, 0);
    bounds.finish();
    if (bounds.empty()) return;
    clearCandidate(bounds);
    GL11.glDisable(GL11.GL_CULL_FACE);
    use(atlasProgram);
    bind(0, candidate.texture.getID());
    bind(1, candidate.depth.getID());
    bind(2, candidate.lighting);
    GL20.glUniform3f(
        location(atlasProgram, "worldLight"), candidate.red, candidate.green, candidate.blue);
    candidate.matrix.get(matrixBuffer.clear());
    GL20.glUniformMatrix4fv(location(atlasProgram, "matrix"), false, matrixBuffer);
    GL20.glUniform4f(
        location(atlasProgram, "rectangle"), candidate.x, candidate.y, candidate.w, candidate.h);
    GL20.glUniform4f(
        location(atlasProgram, "uvRect"),
        candidate.texture.getXStart(),
        candidate.texture.getYStart(),
        candidate.texture.getXEnd(),
        candidate.texture.getYEnd());
    GL20.glUniform2f(location(atlasProgram, "depthRange"), candidate.near, candidate.far);
    GL30.glBindVertexArray(vao);
    GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    accumulate(bounds, candidate.alpha);
  }

  private static OccludedOutlineBounds modelBounds(
      ModelSlotRenderData slot, ArrayList<ModelInstanceRenderData> modelData, Matrix4f mvp) {
    OccludedOutlineBounds bounds = new OccludedOutlineBounds(width, height, jitterX, jitterY);
    Matrix4f transformed = new Matrix4f();
    Matrix4f bone = new Matrix4f();
    float[] values = new float[16];
    for (ModelInstanceRenderData data : modelData) {
      if (data.model == null || data.model.mesh == null) continue;
      Vector3f lo = data.model.mesh.minXyz;
      Vector3f hi = data.model.mesh.maxXyz;
      if (data.model.isStatic) {
        bone.set(data.xfrm).transpose();
        transformed.set(mvp).scale(slot.finalScale).mul(bone);
        bounds.box(transformed, lo, hi);
      } else if (data.matrixPalette != null) {
        FloatBuffer palette = data.matrixPalette;
        for (int offset = palette.position(); offset + 16 <= palette.limit(); offset += 16) {
          for (int i = 0; i < 16; i++) values[i] = palette.get(offset + i);
          bone.set(values)
              .transpose(); // Shader.setMatrixPalette uploads this buffer with transpose=true.
          transformed.set(mvp).scale(slot.finalScale).mul(bone);
          bounds.box(transformed, lo, hi);
        }
      } else bounds.full();
    }
    bounds.finish();
    return bounds;
  }

  private static void clearCandidate(OccludedOutlineBounds bounds) {
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, candidateFbo);
    GL30.glBindVertexArray(
        meshVao); // Mesh.Draw changes attribute pointers; never mutate the game's VAO.
    GL41.glViewportIndexedf(0, jitterX, jitterY, width, height);
    GL11.glEnable(GL11.GL_SCISSOR_TEST);
    // Everything outside the previous candidate is already clear. Clearing only its bounds avoids
    // touching the large empty rectangle between two distant zombies.
    GL11.glScissor(previousX, previousY, previousWidth, previousHeight);
    GL11.glColorMask(true, true, true, true);
    GL11.glDepthMask(true);
    GL11.glClearColor(0, 0, 0, 0);
    GL11.glClearDepth(1);
    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    GL11.glScissor(bounds.x, bounds.y, bounds.w, bounds.h);
    GL11.glEnable(GL11.GL_DEPTH_TEST);
    GL11.glDepthFunc(GL11.GL_LESS);
    GL11.glDisable(GL11.GL_BLEND);
    GL11.glDisable(GL11.GL_STENCIL_TEST);
    GL11.glDisable(GL11.GL_ALPHA_TEST);
    previousX = bounds.x;
    previousY = bounds.y;
    previousWidth = bounds.w;
    previousHeight = bounds.h;
  }

  private static void accumulate(OccludedOutlineBounds bounds, float opacity) {
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
    screenState();
    GL11.glEnable(GL11.GL_SCISSOR_TEST);
    GL11.glScissor(bounds.x, bounds.y, bounds.w, bounds.h);
    GL11.glEnable(GL11.GL_BLEND);
    GL14.glBlendEquation(GL14.GL_MAX);
    use(maskProgram);
    bind(0, candidateTexture);
    bind(1, candidateDepth);
    bind(2, opaqueTexture);
    uniform2(maskProgram, "candidateOrigin", 0, 0);
    uniform2(maskProgram, "depthOrigin", 0, 0);
    GL20.glUniform4i(location(maskProgram, "silhouetteRect"), 0, 0, width, height);
    GL20.glUniform4i(location(maskProgram, "viewport"), 0, 0, width, height);
    GL20.glUniform1i(location(maskProgram, "eligible"), 1);
    GL20.glUniform1f(
        location(maskProgram, "candidateOpacity"), Math.max(0F, Math.min(1F, opacity)));
    GL20.glUniform1i(location(maskProgram, "clipEdges"), 1);
    GL20.glUniform1i(location(maskProgram, "radius"), Config.OCCLUDED_OUTLINE_WIDTH);
    GL20.glUniform1f(location(maskProgram, "depthTolerance"), 2F / 65535F);
    triangle();
  }

  private static void screenState() {
    GL11.glViewport(0, 0, width, height);
    GL11.glDisable(GL11.GL_DEPTH_TEST);
    GL11.glDepthMask(false);
    GL11.glDisable(GL11.GL_STENCIL_TEST);
    GL11.glDisable(GL11.GL_ALPHA_TEST);
    GL11.glDisable(GL11.GL_CULL_FACE);
    GL11.glDisable(GL11.GL_SCISSOR_TEST);
    GL11.glDisable(GL11.GL_BLEND);
    GL11.glColorMask(true, true, true, true);
  }

  private static int texture(int internal, int format, int type, int w, int h) {
    int id = GL11.glGenTextures();
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexImage2D(
          GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (java.nio.ByteBuffer) null);
      if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != w
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != h) {
        throw new IllegalStateException(
            "Cannot allocate outline texture "
                + w
                + "x"
                + h
                + " (GL error 0x"
                + Integer.toHexString(GL11.glGetError())
                + ")");
      }
      return id;
    } catch (RuntimeException error) {
      GL11.glDeleteTextures(id);
      throw error;
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
    }
  }

  /** Render thread: read the live setting, decoding only when Apply changes its value. */
  static int currentColour() {
    String value = Config.OCCLUDED_OUTLINE_COLOUR;
    if (!java.util.Objects.equals(value, colourSpec)) {
      colourRgb = parseColour(value);
      colourSpec = value;
    }
    return colourRgb;
  }

  /** Decode picker RGB (RRGGBB or #RRGGBB); invalid values use the default amber colour. */
  static int parseColour(String value) {
    if (value == null) return 0xFFC740;
    String text = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (text.startsWith("#")) text = text.substring(1);
    return text.matches("[0-9a-f]{6}") ? Integer.parseInt(text, 16) : 0xFFC740;
  }

  private static int framebuffer(int colour, int depth) {
    int fbo = GL30.glGenFramebuffers();
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
    GL30.glFramebufferTexture2D(
        GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colour, 0);
    if (depth != 0)
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
    GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
    if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
      GL30.glDeleteFramebuffers(fbo);
      throw new IllegalStateException("incomplete outline framebuffer");
    }
    return fbo;
  }

  private static void resize(int w, int h) {
    if (w == width && h == height && opaqueFbo != 0) return;
    releaseBuffers();
    width = w;
    height = h;
    opaqueTexture = texture(GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, w, h);
    candidateTexture = texture(GL30.GL_RG8, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, w, h);
    candidateDepth =
        texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, w, h);
    maskTexture = texture(GL30.GL_RG8, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, w, h);
    opaqueFbo = framebuffer(opaqueTexture, 0);
    candidateFbo = framebuffer(candidateTexture, candidateDepth);
    maskFbo = framebuffer(maskTexture, 0);
  }

  private static void releaseBuffers() {
    for (int id : new int[] {opaqueTexture, candidateTexture, candidateDepth, maskTexture})
      if (id != 0) GL11.glDeleteTextures(id);
    for (int id : new int[] {opaqueFbo, candidateFbo, maskFbo})
      if (id != 0) GL30.glDeleteFramebuffers(id);
    opaqueTexture = candidateTexture = candidateDepth = maskTexture = 0;
    opaqueFbo = candidateFbo = maskFbo = 0;
    width = height = 0;
  }

  private static String source(String name) throws Exception {
    return Files.readString(
        Path.of(ZomboidFileSystem.instance.getMediaFile("shaders/" + name).getPath()));
  }

  private static void initializePrograms() throws Exception {
    if (seedProgram != 0) return;
    String vertex = source("pzopt_occludedOutline.vert");
    String seed =
        Config.OCCLUDED_OUTLINE_IGNORE_PLANTS
            ? SEED.replace("sampler2D sceneDepth", "usampler2D sceneDepth")
                .replace(
                    "texelFetch(sceneDepth, sourceOrigin + ivec2(gl_FragCoord.xy), 0).r",
                    "float(texelFetch(sceneDepth, sourceOrigin + ivec2(gl_FragCoord.xy), 0).r >>"
                        + " 16u) / 65535.0")
            : SEED;
    seedProgram = program("occluded seed", vertex, seed);
    maskProgram = program("occluded mask", vertex, source("pzopt_occludedOutline.frag"));
    compositeProgram =
        program("occluded composite", vertex, source("pzopt_occludedOutlineComposite.frag"));
    atlasProgram = program("occluded atlas", ATLAS_VERTEX, ATLAS_FRAGMENT);
    vao = GL30.glGenVertexArrays();
    meshVao = GL30.glGenVertexArrays();
    captureShader = new Shader("pzopt_occludedCapture", false, false);
    captureStaticShader = new Shader("pzopt_occludedCapture", true, false);
    if (!captureShader.getShaderProgram().isCompiled()
        || !captureStaticShader.getShaderProgram().isCompiled()) {
      throw new IllegalStateException("model capture shader unavailable");
    }
    use(seedProgram);
    GL20.glUniform1i(location(seedProgram, "sceneDepth"), 0);
    use(maskProgram);
    GL20.glUniform1i(location(maskProgram, "silhouette"), 0);
    GL20.glUniform1i(location(maskProgram, "silhouetteDepth"), 1);
    GL20.glUniform1i(location(maskProgram, "opaqueDepth"), 2);
    use(compositeProgram);
    GL20.glUniform1i(location(compositeProgram, "outlineMask"), 0);
    use(atlasProgram);
    GL20.glUniform1i(location(atlasProgram, "diffuse"), 0);
    GL20.glUniform1i(location(atlasProgram, "depth"), 1);
    GL20.glUniform1i(location(atlasProgram, "surfaceLighting"), 2);
  }

  private static int program(String name, String vertex, String fragment) {
    int id = Shaders.program(name, vertex, fragment);
    if (id == 0) throw new IllegalStateException(name + " failed to link");
    return id;
  }

  private static int location(int program, String name) {
    java.util.Map<String, Integer> locations =
        LOCATIONS.computeIfAbsent(program, ignored -> new java.util.HashMap<>());
    Integer cached = locations.get(name);
    if (cached != null) return cached;
    int location = GL20.glGetUniformLocation(program, name);
    if (location < 0) throw new IllegalStateException("Missing outline uniform: " + name);
    locations.put(name, location);
    return location;
  }

  private static void uniform2(int program, String name, int x, int y) {
    GL20.glUniform2i(location(program, name), x, y);
  }

  private static void use(int program) {
    ShaderHelper.glUseProgramObjectARB(program);
  }

  private static void bind(int unit, int texture) {
    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
  }

  private static void triangle() {
    GL30.glBindVertexArray(vao);
    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
  }

  private static final class Camera extends ModelCamera {
    @Override
    public void Begin() {
      Core.getInstance().DoPushIsoStuff(x, y, z, useAngle, false);
    }

    @Override
    public void End() {
      Core.getInstance().DoPopIsoStuff();
    }
  }

  /** Compatibility-context state guard. Queries occur once per pass, never per candidate. */
  private static final class State implements AutoCloseable {
    final int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    final int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    final int[] samplers = new int[3];

    State() {
      GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
      for (int unit = 0; unit < samplers.length; unit++) {
        samplers[unit] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit);
        GL33.glBindSampler(
            unit, 0); // A borrowed comparison/filter sampler must not reinterpret our depth.
      }
    }

    @Override
    public void close() {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
      GL30.glBindVertexArray(vertexArray);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
      GL11.glPopAttrib();
      use(program);
      for (int unit = 0; unit < samplers.length; unit++) GL33.glBindSampler(unit, samplers[unit]);
      GL13.glActiveTexture(activeTexture);
      Texture.lastTextureID = -1;
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      SpriteRenderer.ringBuffer.restoreVbos = true;
      GLStateRenderThread.restore();
    }
  }

  private static final String SEED =
      """
      #version 330 core
      uniform sampler2D sceneDepth;
      uniform ivec2 sourceOrigin;
      layout(location=0) out uint captured;
      void main() {
          float d = texelFetch(sceneDepth, sourceOrigin + ivec2(gl_FragCoord.xy), 0).r;
          captured = floatBitsToUint(d);
      }
      """;
  private static final String ATLAS_VERTEX =
      """
      #version 330 core
      uniform mat4 matrix;
      uniform vec4 rectangle;
      uniform vec4 uvRect;
      out vec2 uv;
      void main() {
          vec2 p = vec2(gl_VertexID & 1, gl_VertexID >> 1);
          gl_Position = matrix * vec4(rectangle.xy + p * rectangle.zw, 0, 1);
          uv = mix(uvRect.xy, uvRect.zw, p);
      }
      """;
  private static final String ATLAS_FRAGMENT =
      """
      #version 330 core
      uniform sampler2D diffuse;
      uniform sampler2D depth;
      uniform sampler2D surfaceLighting;
      uniform vec3 worldLight;
      uniform vec2 depthRange;
      in vec2 uv;
      layout(location=0) out vec2 captured;
      void main() {
          float d = texture(depth, uv).r;
          if (texture(diffuse, uv).a < 0.5 || d <= 0) discard;
          gl_FragDepth = mix(depthRange.x, depthRange.y, d);
          vec3 light = clamp(texture(surfaceLighting, uv).rgb * worldLight, 0.0, 1.0);
          captured = vec2(1.0, dot(light, vec3(0.2126, 0.7152, 0.0722)));
      }
      """;
}
