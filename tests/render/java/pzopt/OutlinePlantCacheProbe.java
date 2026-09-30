package pzopt;

import java.lang.reflect.Method;
import java.util.Map;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;

/**
 * Exercises the production cache clear, binding restoration and destruction on a real GL context.
 */
public final class OutlinePlantCacheProbe {
  public static void main(String[] args) throws Throwable {
    System.setProperty("pzopt.userOptionsFile", "/dev/null");
    try (var context = new OccludedOutlineLoaderProbe.EglContext()) {
      GL.createCapabilities();
      method("initialize").invoke(null);
      var texture = method("texture", int.class, int.class);
      int previous = (int) texture.invoke(null, 4, 3);
      var cacheType = Class.forName("pzopt.OutlinePlantDepth$Cache");
      var registry = method("cache", int.class, int.class, int.class, int.class);
      Object cache = registry.invoke(null, 456, 123, 4, 3);
      var textureField = cacheType.getDeclaredField("texture");
      textureField.setAccessible(true);
      int cached = textureField.getInt(cache);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, cached);
      GL11.glTexSubImage2D(
          GL11.GL_TEXTURE_2D,
          0,
          0,
          0,
          4,
          3,
          GL30.GL_RED_INTEGER,
          GL11.GL_UNSIGNED_INT,
          new int[12]);
      check(registry.invoke(null, 456, 123, 4, 3) == cache, "append reuses the cached resource");
      int[] appended = new int[12];
      GL11.glGetTexImage(
          GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, appended);
      for (int value : appended) check(value == 0, "append preserves existing cached depths");
      int fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(
          GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, previous, 0);
      GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
      GL11.glViewport(2, 3, 4, 3);
      GL11.glScissor(0, 0, 1, 1);
      GL11.glEnable(GL11.GL_SCISSOR_TEST);
      GL11.glColorMask(false, false, false, false);
      method("clear", cacheType).invoke(null, cache);
      check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == fbo, "restore draw FBO");
      check(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == fbo, "restore read FBO");
      check(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), "restore scissor enable");
      var masks = org.lwjgl.BufferUtils.createByteBuffer(4);
      GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, masks);
      for (int i = 0; i < 4; i++) check(masks.get(i) == 0, "restore colour write mask");
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, cached);
      int[] pixels = new int[12];
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, pixels);
      for (int pixel : pixels)
        check(pixel == -1, "clear every texel despite inherited scissor/mask");

      int sampler = GL33.glGenSamplers();
      GL13.glActiveTexture(GL13.GL_TEXTURE8);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
      GL33.glBindSampler(8, sampler);
      GL13.glActiveTexture(GL13.GL_TEXTURE3);
      GL42.glBindImageTexture(4, previous, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32UI);
      var mode = method("mode", int.class, cacheType);
      mode.invoke(null, 1, cache);
      check(
          GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 4) == cached,
          "bind independent cache image");
      GL13.glActiveTexture(GL13.GL_TEXTURE8);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, cached);
      GL33.glBindSampler(8, 0);
      GL13.glActiveTexture(GL13.GL_TEXTURE3);
      mode.invoke(null, 0, null);
      method("releaseBindings").invoke(null);
      check(
          GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE3,
          "retain active texture unit");
      check(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 4) == previous, "restore image texture");
      check(
          GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 4) == GL15.GL_READ_ONLY,
          "restore image access");
      check(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 8) == sampler, "restore sampler object");
      GL13.glActiveTexture(GL13.GL_TEXTURE8);
      check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == previous, "restore source texture");

      Object resized = registry.invoke(null, 456, 124, 8, 6);
      check(resized != cache, "resize replaces the old cache entry");
      check(!map("BY_DEPTH").containsKey(123), "resize removes the old depth association");
      int replacement = textureField.getInt(resized);
      check(
          replacement == cached || !GL11.glIsTexture(cached),
          "resize releases the old texture name unless GL recycles it");
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, replacement);
      check(
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) == 8,
          "resized cache width");
      check(
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) == 6,
          "resized cache height");
      OutlinePlantDepth.destroyed(456);
      check(!GL11.glIsTexture(replacement), "destroy paired cache texture");
      check(!map("BY_DEPTH").containsKey(124), "remove stale depth lookup");
      check(!map("CACHES").containsKey(456), "remove stale FBO lookup");
      check(GL11.glGetError() == GL11.GL_NO_ERROR, "no GL errors");
      GL30.glDeleteFramebuffers(fbo);
      GL33.glDeleteSamplers(sampler);
      GL11.glDeleteTextures(previous);
      System.out.println("OutlinePlantCacheProbe passed");
    }
  }

  private static Method method(String name, Class<?>... parameters) throws Exception {
    var method = OutlinePlantDepth.class.getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    return method;
  }

  @SuppressWarnings(
      "unchecked") // Reflecting the two private integer-keyed cache maps for lifecycle assertions.
  private static Map<Integer, Object> map(String name) throws Exception {
    var field = OutlinePlantDepth.class.getDeclaredField(name);
    field.setAccessible(true);
    return (Map<Integer, Object>) field.get(null);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
