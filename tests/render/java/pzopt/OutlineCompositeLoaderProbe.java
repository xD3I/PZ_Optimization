package pzopt;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;

/** Links upstream lighting/Relief composites with sway and the filtered outline cache on EGL. */
public final class OutlineCompositeLoaderProbe {
  public static void main(String[] args) throws Throwable {
    System.setProperty("pzopt.userOptionsFile", "/dev/null");
    System.setProperty("pzopt.relief", "true");
    System.setProperty("pzopt.reliefSunMode", "composite");
    System.setProperty("pzopt.sunShadows", "true");
    System.setProperty("pzopt.cloudShadows", "true");
    try (var context = new OccludedOutlineLoaderProbe.EglContext()) {
      GL.createCapabilities();
      for (String field : new String[] {"CHUNK_FRAG", "CHUNK_BASE_FRAG"}) {
        String fragment = source(field);
        fragment = CloudShadow.patchShader("media/shaders/pzopt_chunkBase.frag", fragment);
        fragment = Relief.patchComposite("media/shaders/pzopt_chunkBase.frag", fragment);
        if (!fragment.contains("void pzReliefInner()")) {
          throw new AssertionError("Relief transform was not exercised");
        }
        fragment = Sway.patchComposite(fragment);
        if (fragment == null) throw new AssertionError("Sway rejected the composite");
        fragment = OutlinePlantShaders.patch("pzopt_chunkBase.frag", fragment);
        int vertex = compile(GL20.GL_VERTEX_SHADER, source("CHUNK_BASE_VERT"));
        int pixel = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, pixel);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
          throw new AssertionError(GL20.glGetProgramInfoLog(program));
        }
        GL20.glDeleteProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(pixel);
        System.out.println("Composite loader OK: " + field + " + Relief + sway + plant cache");
      }
    }
  }

  private static String source(String name) throws Exception {
    var field = PixelLight.class.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static int compile(int type, String source) {
    int shader = GL20.glCreateShader(type);
    GL20.glShaderSource(shader, source);
    GL20.glCompileShader(shader);
    if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
      throw new AssertionError(GL20.glGetShaderInfoLog(shader));
    }
    return shader;
  }
}
