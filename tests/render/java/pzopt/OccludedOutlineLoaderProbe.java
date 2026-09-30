package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import sun.misc.Unsafe;
import zombie.core.opengl.ShaderProgram;
import zombie.core.rendering.ShaderBufferData;
import zombie.core.skinnedmodel.shader.Shader;

/**
 * Headless Linux/EGL integration probe for the real model property-buffer constructor. No game,
 * window or save is opened. Run separately from JVM-only tests, with native access enabled.
 */
public final class OccludedOutlineLoaderProbe {
  public static void main(String[] args) throws Throwable {
    boolean expectStockFailure = args.length == 1 && args[0].equals("--expect-stock-failure");
    try (var context = new EglContext()) {
      GL.createCapabilities();
      System.out.println("Loader probe GPU: " + GL11.glGetString(GL11.GL_RENDERER));
      for (boolean instanced : new boolean[] {false, true}) {
        checkProgram(instanced, false, false);
        checkProgram(instanced, true, expectStockFailure);
      }
    }
    System.out.println("OccludedOutlineLoaderProbe passed");
  }

  private static void checkProgram(boolean instanced, boolean patched, boolean expectFailure)
      throws Exception {
    String vertex = "#version 430 compatibility\nout vec4 tint;\n";
    if (instanced) {
      vertex +=
          "struct Instance { vec4 TintColour; };\n"
              + "layout(std430, binding=0) buffer instancedData { Instance instances[128]; };\n";
    }
    vertex +=
        "void main() { gl_Position=vec4(0,0,0,1); tint="
            + (instanced ? "instances[gl_InstanceID].TintColour" : "vec4(1)")
            + "; }\n";
    String fragment =
        """
        #version 430 compatibility
        in vec4 tint;
        uniform float Alpha;
        uniform sampler2D Texture0;
        void main() { gl_FragColor = vec4(tint.rgb, Alpha) * texture2D(Texture0, vec2(0.5)); }
        """;
    if (patched) {
      fragment =
          OutlinePlantShaders.patch(
              "basicEffect.frag", OccludedOutlineShaders.patch("basicEffect.frag", fragment));
    }
    int program = GL20.glCreateProgram();
    try {
      attach(program, GL20.GL_VERTEX_SHADER, vertex);
      attach(program, GL20.GL_FRAGMENT_SHADER, fragment);
      GL20.glLinkProgram(program);
      require(
          GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(program));
      GL20.glUseProgram(program);
      int alpha = GL20.glGetUniformLocation(program, "Alpha");
      int texture = GL20.glGetUniformLocation(program, "Texture0");
      GL20.glUniform1f(alpha, 0.37f);
      GL20.glUniform1i(texture, 3);
      ShaderProgram wrapper =
          ShaderProgram.createShaderProgram("outline-loader-probe", false, instanced, false);
      setField(wrapper, "shaderId", program);
      // Only the Shader shell is uninitialized, avoiding its disk/engine startup constructor.
      // ShaderBufferData itself is constructed normally and executes the actual game's GL loader.
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      Shader shader = (Shader) ((Unsafe) field.get(null)).allocateInstance(Shader.class);
      setField(shader, "shaderProgram", wrapper);
      shader.instancedDataAttrib =
          instanced
              ? GL43.glGetProgramResourceIndex(
                  program, GL43.GL_SHADER_STORAGE_BLOCK, "instancedData")
              : -1;
      ShaderBufferData data;
      try {
        data = new ShaderBufferData(shader);
      } catch (NullPointerException failure) {
        if (!expectFailure) throw failure;
        require(
            failure.getStackTrace()[0].getClassName().equals(ShaderBufferData.class.getName()),
            "Failure did not originate in the model uniform loader");
        System.out.println("Reproduced stock loader NPE (instanced=" + instanced + ")");
        return;
      }
      require(!expectFailure, "Stock failure was not reproduced");
      require(
          data.parameters.containsKey("Alpha") && data.parameters.containsKey("Texture0"),
          "Ordinary model uniforms disappeared");
      require(
          data.parameters.keySet().stream().noneMatch(OccludedOutlineShaders::ownsUniform),
          "Model loader claimed private GPU state");
      if (instanced) require(data.GetSize() == 16, "Instance buffer layout changed");
      data.PushUniforms();
      require(Math.abs(GL20.glGetUniformf(program, alpha) - 0.37f) < 1e-6f, "Model alpha changed");
      require(GL20.glGetUniformi(program, texture) == 3, "Model sampler changed");
      if (patched) {
        int image = GL20.glGetUniformLocation(program, "pzoptOpaqueDepth");
        require(
            image >= 0 && GL20.glGetUniformi(program, image) == 7, "Private image binding changed");
      }
      require(GL11.glGetError() == GL11.GL_NO_ERROR, "Uniform initialization generated a GL error");
      System.out.println("Model loader OK (instanced=" + instanced + ", patched=" + patched + ")");
    } finally {
      GL20.glUseProgram(0);
      GL20.glDeleteProgram(program);
    }
  }

  private static void setField(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static void attach(int program, int type, String source) {
    int shader = GL20.glCreateShader(type);
    GL20.glShaderSource(shader, source);
    GL20.glCompileShader(shader);
    require(
        GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader));
    GL20.glAttachShader(program, shader);
    GL20.glDeleteShader(shader);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  /** Creates a surfaceless desktop OpenGL compatibility context through the system EGL library. */
  static final class EglContext implements AutoCloseable {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;
    private final Arena arena = Arena.ofConfined();
    private final SymbolLookup egl = SymbolLookup.libraryLookup("libEGL.so.1", arena);
    private MemorySegment display = MemorySegment.NULL;
    private MemorySegment surface = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;

    EglContext() throws Throwable {
      try {
        display =
            (MemorySegment)
                call(
                    "eglGetPlatformDisplay",
                    FunctionDescriptor.of(PTR, INT, PTR, PTR),
                    0x31DD,
                    MemorySegment.NULL,
                    MemorySegment.NULL);
        require(!display.equals(MemorySegment.NULL), "No surfaceless EGL display");
        ok(
            "eglInitialize",
            FunctionDescriptor.of(INT, PTR, PTR, PTR),
            display,
            arena.allocate(INT),
            arena.allocate(INT));
        ok("eglBindAPI", FunctionDescriptor.of(INT, INT), 0x30A2);
        var attributes =
            arena.allocateFrom(INT, 0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038);
        var config = arena.allocate(PTR);
        var count = arena.allocate(INT);
        ok(
            "eglChooseConfig",
            FunctionDescriptor.of(INT, PTR, PTR, PTR, INT, PTR),
            display,
            attributes,
            config,
            1,
            count);
        require(count.get(INT, 0) == 1, "No EGL OpenGL pbuffer configuration");
        var selected = config.get(PTR, 0);
        surface =
            (MemorySegment)
                call(
                    "eglCreatePbufferSurface",
                    FunctionDescriptor.of(PTR, PTR, PTR, PTR),
                    display,
                    selected,
                    arena.allocateFrom(INT, 0x3057, 1, 0x3056, 1, 0x3038));
        context =
            (MemorySegment)
                call(
                    "eglCreateContext",
                    FunctionDescriptor.of(PTR, PTR, PTR, PTR, PTR),
                    display,
                    selected,
                    MemorySegment.NULL,
                    arena.allocateFrom(INT, 0x3098, 4, 0x30FB, 3, 0x30FD, 2, 0x3038));
        require(
            !surface.equals(MemorySegment.NULL) && !context.equals(MemorySegment.NULL),
            "EGL context creation failed");
        ok(
            "eglMakeCurrent",
            FunctionDescriptor.of(INT, PTR, PTR, PTR, PTR),
            display,
            surface,
            surface,
            context);
      } catch (Throwable failure) {
        close();
        throw failure;
      }
    }

    private Object call(String name, FunctionDescriptor descriptor, Object... arguments)
        throws Throwable {
      return Linker.nativeLinker()
          .downcallHandle(egl.find(name).orElseThrow(), descriptor)
          .invokeWithArguments(arguments);
    }

    private void ok(String name, FunctionDescriptor descriptor, Object... arguments)
        throws Throwable {
      require((int) call(name, descriptor, arguments) != 0, name + " failed");
    }

    @Override
    public void close() {
      try {
        if (!display.equals(MemorySegment.NULL)) {
          call(
              "eglMakeCurrent",
              FunctionDescriptor.of(INT, PTR, PTR, PTR, PTR),
              display,
              MemorySegment.NULL,
              MemorySegment.NULL,
              MemorySegment.NULL);
          if (!context.equals(MemorySegment.NULL))
            call("eglDestroyContext", FunctionDescriptor.of(INT, PTR, PTR), display, context);
          if (!surface.equals(MemorySegment.NULL))
            call("eglDestroySurface", FunctionDescriptor.of(INT, PTR, PTR), display, surface);
          call("eglTerminate", FunctionDescriptor.of(INT, PTR), display);
        }
      } catch (Throwable failure) {
        throw new IllegalStateException("EGL cleanup failed", failure);
      } finally {
        arena.close();
      }
    }
  }
}
