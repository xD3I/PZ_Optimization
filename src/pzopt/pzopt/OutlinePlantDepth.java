package pzopt;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.objects.IsoTree;

/**
 * Cached outline depth excluding low vegetation. Each texel packs native DEPTH16 and the two
 * tree-sway attribute bytes into one R32UI value, so atomic minima retain depth and attributes
 * together. Caches follow their native framebuffer's clear, append, replacement and destruction
 * lifecycle.
 */
public final class OutlinePlantDepth {
  private static final int IMAGE = 4, UNIT = 8, BINDING = 9;
  private static final Map<Integer, Cache> CACHES = new HashMap<>();
  private static final Map<Integer, Cache> BY_DEPTH = new HashMap<>();
  private static final Map<Integer, TextureFBO> FBOS = new HashMap<>();
  private static final Map<Integer, Integer> SOURCE_UNIFORMS = new HashMap<>();
  private static final IntBuffer CONTROL = BufferUtils.createIntBuffer(8);
  private static int control, dummy, clearFbo, paused, compositeFrame;
  private static int currentMode = -1, currentTarget;
  private static boolean failed, compositing;
  private static Bindings borrowed;

  private OutlinePlantDepth() {}

  /** Startup policy, independent of live master enable so cached data remains usable afterward. */
  public static boolean enabled() {
    return Config.OCCLUDED_OUTLINE_IGNORE_PLANTS
        && OccludedOutline.separateTransparent()
        && !failed;
  }

  /** Pure classification; trees take precedence even if a mod gives them plant flags. */
  public static boolean lowVegetation(boolean tree, boolean bush, boolean removable) {
    return !tree && (bush || removable);
  }

  /**
   * Game thread: snapshot plant identity rather than reading a mutable object on the render thread.
   */
  public static boolean beginObject(IsoObject object) {
    if (!enabled() || object == null || object.sprite == null) return false;
    boolean tree = object instanceof IsoTree || object.sprite.getType() == IsoObjectType.tree;
    if (!lowVegetation(tree, object.sprite.isBush, object.sprite.canBeRemoved)) return false;
    SpriteRenderer.instance.drawGeneric(PAUSE);
    return true;
  }

  /** Game thread: close a scope when {@link #beginObject} returned true. */
  public static void endObject() {
    SpriteRenderer.instance.drawGeneric(RESUME);
  }

  private static final TextureDraw.GenericDrawer PAUSE =
      new TextureDraw.GenericDrawer() {
        public void render() {
          OccludedOutline.pauseCapture();
        }
      };
  private static final TextureDraw.GenericDrawer RESUME =
      new TextureDraw.GenericDrawer() {
        public void render() {
          OccludedOutline.resumeCapture();
        }
      };

  /** Render thread: scopes also exclude characters, shadows and offscreen model replays. */
  public static void pause() {
    if (!enabled()) return;
    paused++;
    mode(0, null);
  }

  /** Render thread: restore the enclosing cache scope after a matching pause. */
  public static void resume() {
    if (!enabled()) return;
    if (--paused < 0) throw new IllegalStateException("Unbalanced plant-depth capture scope");
    material(true);
  }

  /** Record the uninstrumented source for sway twins, then apply the outermost cache transform. */
  public static String recordAndPatch(
      zombie.core.opengl.ShaderProgram program, String file, String source) {
    return patchShader(file, Sway.recordSource(program, file, source));
  }

  /** Outermost shader transform, after sway source recording so twins are not patched twice. */
  public static String patchShader(String file, String source) {
    if (!enabled()) return source;
    try {
      String patched = OutlinePlantShaders.patch(file, source);
      if (!patched.equals(source)) initialize();
      return patched;
    } catch (RuntimeException error) {
      fail(error);
      return source; // Keep native rendering; the filtered outline pass fails closed.
    }
  }

  /** Render thread: track native owners; allocate only for a bake or a chunk composite. */
  public static void start(TextureFBO fbo, boolean clear) {
    if (!enabled()) return;
    try {
      FBOS.put(fbo.getBufferId(), fbo);
      Cache cache = CACHES.get(fbo.getBufferId());
      if (cache != null && clear) clear(cache);
      framebuffer();
    } catch (RuntimeException error) {
      fail(error);
    }
  }

  /** Render thread: stop capture in offscreen targets and restore the enclosing terrain bake. */
  public static void framebuffer() {
    if (!enabled()) return;
    mode(0, null);
    releaseBindings();
    material(true);
  }

  /** Game thread: scene/intermediate targets are fresh each frame; terrain caches persist. */
  public static void beforeComposite() {
    if (enabled()) SpriteRenderer.instance.drawGeneric(COMPOSITE_BEGIN);
  }

  private static final TextureDraw.GenericDrawer COMPOSITE_BEGIN =
      new TextureDraw.GenericDrawer() {
        public void render() {
          compositeFrame++;
          compositing = true;
          try {
            TextureFBO owner = FBOS.get(TextureFBO.lastID);
            if (owner == null) throw new IllegalStateException("Unregistered world framebuffer");
            Cache scene = cache(owner);
            clear(scene);
            scene.compositedFrame = compositeFrame;
          } catch (RuntimeException error) {
            fail(error);
          }
        }
      };

  /** Game thread: release the composite's temporary bindings before moving-object rendering. */
  public static void afterComposite() {
    if (enabled()) SpriteRenderer.instance.drawGeneric(COMPOSITE_END);
  }

  private static final TextureDraw.GenericDrawer COMPOSITE_END =
      new TextureDraw.GenericDrawer() {
        public void render() {
          endComposite();
        }
      };

  /** Render thread: restore borrowed bindings when the cached scene composite is complete. */
  public static void endComposite() {
    compositing = false;
    if (!enabled()) return;
    mode(0, null);
    releaseBindings();
  }

  /** Render thread: ordinary bake programs and depth-tested VBO/tree draws. */
  public static void material(boolean depthTest) {
    if (!enabled()) return;
    try {
      var chunk = FBORenderChunkManager.instance.renderThreadCurrent;
      if (paused != 0
          || !depthTest
          || chunk == null
          || chunk.fbo == null
          || chunk.fbo.getBufferId() != TextureFBO.lastID) {
        mode(0, null);
        return;
      }
      mode(1, cache(chunk.fbo));
    } catch (RuntimeException error) {
      fail(error);
    }
  }

  /** Render thread: called after shader remapping and native uniform uploads. */
  public static void program(TextureDraw draw) {
    if (!enabled()) return;
    try {
      int program = Ssr.boundProgram();
      int sourceUniform =
          SOURCE_UNIFORMS.computeIfAbsent(
              program, id -> id == 0 ? -1 : GL20.glGetUniformLocation(id, "pzoptPlantSource"));
      if (sourceUniform < 0 || draw.tex1 == null || paused != 0 || !compositing) {
        material(true);
        return;
      }
      Cache input = BY_DEPTH.get(draw.tex1.getID());
      TextureFBO owner = FBOS.get(TextureFBO.lastID);
      if (input == null || owner == null) {
        throw new IllegalStateException(
            "Missing filtered terrain cache for composite depth " + draw.tex1.getID());
      }
      Cache output = cache(owner);
      if (output.compositedFrame != compositeFrame) {
        clear(output);
        output.compositedFrame = compositeFrame;
      }
      if (input == output) throw new IllegalStateException("Plant-depth feedback loop");
      GL42.glMemoryBarrier(
          GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
      mode(2, output);
      int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, input.texture);
      GL33.glBindSampler(UNIT, 0);
      GL13.glActiveTexture(active);
      GL20.glUniform1i(sourceUniform, UNIT);
    } catch (RuntimeException error) {
      fail(error);
    }
  }

  /** Filtered, already-composited scene input for the outline seed pass. */
  public static int sceneTexture(int fbo) {
    if (!enabled()) throw new IllegalStateException("Filtered outline depth unavailable");
    Cache cache = CACHES.get(fbo);
    if (cache == null || cache.compositedFrame != compositeFrame)
      throw new IllegalStateException("Missing filtered world depth for this frame");
    GL42.glMemoryBarrier(
        GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    return cache.texture;
  }

  /** Render thread: remove cache ownership before the native framebuffer name can be reused. */
  public static void destroyed(int fbo) {
    FBOS.remove(fbo);
    Cache cache = CACHES.remove(fbo);
    if (cache != null) {
      BY_DEPTH.remove(cache.depth, cache);
      GL11.glDeleteTextures(cache.texture);
      if (currentTarget == cache.texture) {
        mode(0, null);
        releaseBindings();
      }
    }
  }

  private static Cache cache(TextureFBO fbo) {
    initialize();
    var depth = fbo.getDepthTexture();
    int id = fbo.getBufferId();
    // Chunk/combined FBOs own native depth textures. The main scene's renderbuffer is
    // replaced by FogPass, whose attachment is intentionally not a TextureFBO field.
    int depthId = depth != null ? depth.getID() : FogPass.sceneDepthTexture(id);
    int width = depth != null ? depth.getWidthHW() : FogPass.sceneDepthWidth(id);
    int height = depth != null ? depth.getHeightHW() : FogPass.sceneDepthHeight(id);
    if (depthId == 0 || width <= 0 || height <= 0)
      throw new IllegalStateException("Terrain framebuffer has no registered depth texture");
    Cache result = cache(id, depthId, width, height);
    FBOS.put(id, fbo);
    return result;
  }

  private static Cache cache(int id, int depthId, int width, int height) {
    Cache cache = CACHES.get(id);
    if (cache != null
        && (cache.depth != depthId || cache.width != width || cache.height != height)) {
      destroyed(id);
      cache = null;
    }
    if (cache == null) {
      cache = new Cache(depthId, width, height, texture(width, height));
      CACHES.put(id, cache);
      BY_DEPTH.put(depthId, cache);
      clear(cache);
    }
    return cache;
  }

  private static final class Cache {
    final int depth, width, height, texture;
    int compositedFrame = -1;

    Cache(int depth, int width, int height, int texture) {
      this.depth = depth;
      this.width = width;
      this.height = height;
      this.texture = texture;
    }
  }

  private static void initialize() {
    if (control != 0) return;
    if (!GL.getCapabilities().OpenGL43)
      throw new IllegalStateException("Plant-depth cache needs OpenGL 4.3");
    control = GL15.glGenBuffers();
    int old = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, control);
    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 32L, GL15.GL_DYNAMIC_DRAW);
    GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING, control);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, old);
    dummy = texture(1, 1);
    if (GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, IMAGE) == 0) {
      GL42.glBindImageTexture(IMAGE, dummy, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
    }
    clearFbo = GL30.glGenFramebuffers();
    mode(0, null);
  }

  private static int texture(int width, int height) {
    int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int id = GL11.glGenTextures();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32UI, width, height);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, old);
    return id;
  }

  private static void clear(Cache cache) {
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GL11.glPushAttrib(GL11.GL_SCISSOR_BIT | GL11.GL_COLOR_BUFFER_BIT);
    try {
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, clearFbo);
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, cache.texture, 0);
      GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
      if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("Incomplete plant-depth clear target");
      }
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glColorMask(true, true, true, true);
      GL30.glClearBufferuiv(GL11.GL_COLOR, 0, new int[] {-1, -1, -1, -1});
      GL42.glMemoryBarrier(
          GL42.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    } finally {
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GL11.glPopAttrib();
    }
  }

  private static void mode(int mode, Cache target) {
    if (control == 0) return;
    int id = target == null ? 0 : target.texture;
    if (currentMode == mode && currentTarget == id) return;
    if (mode != 0) {
      if (borrowed == null) borrowed = new Bindings();
      GL42.glBindImageTexture(IMAGE, id, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_R32UI);
    }
    currentMode = mode;
    currentTarget = id;
    CONTROL.clear();
    CONTROL
        .put(0)
        .put(0)
        .put(target == null ? 0 : target.width)
        .put(target == null ? 0 : target.height);
    CONTROL.put(mode).put(0).put(0).put(0).flip();
    int old = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, control);
    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, CONTROL, GL15.GL_STREAM_DRAW);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, old);
  }

  private static void releaseBindings() {
    if (borrowed != null) {
      borrowed.restore();
      borrowed = null;
    }
  }

  private static void fail(RuntimeException error) {
    mode(0, null);
    releaseBindings();
    failed = true;
    for (Cache cache : CACHES.values()) GL11.glDeleteTextures(cache.texture);
    CACHES.clear();
    BY_DEPTH.clear();
    FBOS.clear();
    Log.warn("Filtered outline depth unavailable: " + error);
  }

  private static final class Bindings {
    private final int[] image = new int[6];
    private final int texture, sampler;

    Bindings() {
      int[] names = {
        GL42.GL_IMAGE_BINDING_NAME,
        GL42.GL_IMAGE_BINDING_LEVEL,
        GL42.GL_IMAGE_BINDING_LAYERED,
        GL42.GL_IMAGE_BINDING_LAYER,
        GL42.GL_IMAGE_BINDING_ACCESS,
        GL42.GL_IMAGE_BINDING_FORMAT
      };
      for (int i = 0; i < names.length; i++) image[i] = GL30.glGetIntegeri(names[i], IMAGE);
      int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      sampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, UNIT);
      GL13.glActiveTexture(active);
    }

    void restore() {
      GL42.glBindImageTexture(
          IMAGE, image[0], image[1], image[2] != 0, image[3], image[4], image[5]);
      int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL33.glBindSampler(UNIT, sampler);
      GL13.glActiveTexture(active);
    }
  }
}
