package pzopt;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-only instrumentation of world materials; no GL calls or game initialization. */
public final class OccludedOutlineShaders {
  private static final Set<String> MATERIALS =
      Set.of(
          "basiceffect",
          "basiceffect_instanced",
          // KI5 bodywork and wheels use DAMNLib names rather than the stock vehicle prefix.
          "damn_vehicle_shader",
          "damn_vehicle_noreflect_shader",
          "damn_wheel_shader",
          "tilewithdepth",
          "opaquewithdepth",
          "seamfix2",
          "floortile",
          "walltile",
          "cutawayattached",
          "vborenderer_positioncoloruv",
          "vborenderer_positioncoloruvdepth",
          "pzopt_sftile",
          "pzopt_sfopaque");
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\n]*");
  private static final Pattern WINDOW = Pattern.compile("\\bfloat\\s+windowAlpha\\s*=[^;]+;");
  private static final Pattern DEPTH_WRITE = Pattern.compile("\\bgl_FragDepth\\s*=");
  private static final Pattern LIGHT_OUTPUT =
      Pattern.compile("\\b(?:vec4\\s+fragCol\\b|colour\\s*=\\s*vec4\\b)");
  private static final Pattern COLOUR_WRITE =
      Pattern.compile("\\b(gl_FragColor|gl_FragData\\s*\\[\\s*0\\s*\\]|colour)\\s*=");

  private OccludedOutlineShaders() {}

  /**
   * These uniforms belong to the outline renderer, not the game's model property buffers. The stock
   * loader cannot represent image/ivec4 uniforms; it must neither construct parameters for these
   * entries nor reset their bindings when pushing ordinary model properties.
   */
  public static boolean ownsUniform(String name) {
    return "pzoptOcclusionView".equals(name)
        || "pzoptOcclusionFlags".equals(name)
        || "pzoptOpaqueDepth".equals(name)
        || OutlinePlantShaders.ownsUniform(name);
  }

  /** Whether this is a world material whose fragments can contribute opaque coverage. */
  public static boolean material(String file) {
    String name = file.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    name = name.substring(name.lastIndexOf('/') + 1);
    if (!name.endsWith(".frag")) return false;
    name = name.substring(0, name.length() - 5);
    // Character/corpse atlas sprites must never act as outline occluders. Shared basicEffect
    // shaders remain instrumented for world items and lighting export; character draws pause
    // capture, including their queued instances. Outline/debug programs are excluded too.
    return MATERIALS.contains(name)
        || name.startsWith("vehicle") && !name.equals("vehicle_wireframe");
  }

  /**
   * Keeps the original colour/depth computation and records only opaque fragments in an independent
   * image. Deliberately does not request early fragment tests: opaque surfaces behind glass must
   * contribute even when the ordinary scene depth rejects them. The caller reserves image unit 7
   * and uniform-buffer binding 8, and supplies an initialized control block even outside world
   * draws. Returns the original source for non-material units. Unsupported material layouts fail
   * explicitly.
   */
  public static String patch(String file, String source) {
    if (!material(file) || source.contains("PzoptOcclusionControl")) return source;
    if (source.contains("early_fragment_tests")) {
      throw new IllegalArgumentException("early fragment tests in " + file);
    }
    source = source.replace("\uFEFF", "");
    Matcher main = MAIN.matcher(source);
    if (!main.find()) return source; // A separately compiled include unit has no entry point.
    String inspected = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
    Matcher output = COLOUR_WRITE.matcher(inspected);
    String colour = null;
    while (output.find()) {
      colour = output.group(1);
      if (!colour.equals("colour"))
        break; // Prefer built-ins over a helper's local colour variable.
    }
    if (colour == null) throw new IllegalArgumentException("unknown fragment output in " + file);
    String depth = DEPTH_WRITE.matcher(source).find() ? "gl_FragDepth" : "gl_FragCoord.z";
    String patched = main.replaceFirst("void pzoptOcclusionMaterial()");
    // Atlas lighting is captured before albedo/tint multiplication, not inferred from dark
    // clothing.
    String baseName =
        file.replace('\\', '/').substring(file.replace('\\', '/').lastIndexOf('/') + 1);
    Matcher lightOutput = LIGHT_OUTPUT.matcher(patched);
    boolean surfaceLighting =
        (baseName.equalsIgnoreCase("basicEffect.frag")
                || baseName.equalsIgnoreCase("basicEffect_instanced.frag"))
            && source.contains("vec3 lighting")
            && lightOutput.find();
    if (surfaceLighting) {
      // Singular shaders declare fragCol; instanced shaders write colour directly.
      patched = lightOutput.replaceFirst("pzoptSurfaceLight = lighting * vertColour;\n    $0");
    }
    Matcher window = WINDOW.matcher(patched);
    // Legacy vehicle shaders use the same six window zones but never name their sum windowAlpha.
    if ((baseName.equalsIgnoreCase("vehicle.frag")
            || baseName.equalsIgnoreCase("vehicle_noreflect.frag"))
        && !window.find()) {
      String[] zones = {"1,2", "1,3", "2,0", "2,1", "2,2", "2,3"};
      for (String zone : zones) {
        String[] index = zone.split(",");
        if (!Pattern.compile(
                "texen1\\s*\\[\\s*" + index[0] + "\\s*\\]\\s*\\[\\s*" + index[1] + "\\s*\\]\\s*=")
            .matcher(source)
            .find())
          throw new IllegalArgumentException("unknown legacy vehicle window zones in " + file);
      }
      if (!patched.contains("float t4en"))
        throw new IllegalArgumentException("unknown legacy vehicle mask in " + file);
      patched =
          patched.replace(
              "float t4en",
              "float windowAlpha = clamp(texen1[1][2] + texen1[1][3] + texen1[2][0] + texen1[2][1]"
                  + " + texen1[2][2] + texen1[2][3], 0.0, 1.0);\n"
                  + "    float t4en");
    }
    window = WINDOW.matcher(patched);
    if (source.contains("TextureMask")
        && file.toLowerCase(java.util.Locale.ROOT).contains("vehicle")
        && !window.find()) {
      throw new IllegalArgumentException("unknown vehicle window mask in " + file);
    }
    window = WINDOW.matcher(patched);
    // DAMNLib includes the opaque roof zone (texen2[0][0]) in windowAlpha for its
    // colour/reflection treatment. Exclude only its six actual window zones as glass.
    boolean damnVehicle =
        baseName.equalsIgnoreCase("damn_vehicle_shader.frag")
            || baseName.equalsIgnoreCase("damn_vehicle_noreflect_shader.frag");
    String glassMask =
        damnVehicle
            ? "(texen1[1][2] + texen1[1][3] + texen1[2][0] + texen1[2][1] + texen1[2][2] +"
                + " texen1[2][3])"
            : "windowAlpha";
    patched = window.replaceFirst("$0\n    pzoptOcclusionGlass = " + glassMask + " > 0.5;");
    Matcher version = VERSION.matcher(patched);
    if (!version.find()) throw new IllegalArgumentException("missing GLSL version in " + file);
    patched = version.replaceFirst("#version 430 compatibility");
    Matcher header = VERSION.matcher(patched);
    if (!header.find())
      throw new IllegalArgumentException("missing upgraded GLSL version in " + file);
    int headerEnd = OutlinePlantShaders.declarationOffset(patched);
    patched = patched.substring(0, headerEnd) + "\n" + DECLARATIONS + patched.substring(headerEnd);
    return patched
        + "\nvoid main() {\n"
        + "    pzoptOcclusionMaterial();\n"
        + (surfaceLighting
            ? "    if (pzoptOcclusionFlags.y != 0) " + colour + ".rgb = pzoptSurfaceLight;\n"
            : "")
        + "    if (pzoptOcclusionFlags.x != 0 && !pzoptOcclusionGlass && "
        + colour
        + ".a >= 0.999) {\n"
        + "        ivec2 p = ivec2(gl_FragCoord.xy) - pzoptOcclusionView.xy;\n"
        + "        float d = "
        + depth
        + ";\n"
        + "        if (all(greaterThanEqual(p, ivec2(0))) && all(lessThan(p,"
        + " pzoptOcclusionView.zw))\n"
        + "            && !isnan(d) && !isinf(d) && d >= 0.0 && d <= 1.0)\n"
        + "            imageAtomicMin(pzoptOpaqueDepth, p, floatBitsToUint(d));\n"
        + "    }\n"
        + "}\n";
  }

  private static final String DECLARATIONS =
      """
      layout(std140, binding = 8) uniform PzoptOcclusionControl {
          ivec4 pzoptOcclusionView;
          ivec4 pzoptOcclusionFlags;
      };
      layout(binding = 7, r32ui) uniform uimage2D pzoptOpaqueDepth;
      bool pzoptOcclusionGlass = false;
      vec3 pzoptSurfaceLight = vec3(0.0);
      """;
}
