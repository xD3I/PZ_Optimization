package pzopt;

import java.util.regex.Pattern;

/** Shader instrumentation for cached outline depth without low vegetation. */
public final class OutlinePlantShaders {
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\n]*");
  private static final String HEADER =
      """
      layout(std140, binding = 9) uniform PzoptPlantControl {
          ivec4 pzoptPlantView;
          ivec4 pzoptPlantMode;
      };
      layout(binding = 4, r32ui) uniform uimage2D pzoptPlantTarget;
      void pzoptPlantStore(float depth, vec2 attributes) {
          if (isnan(depth) || isinf(depth) || depth < 0.0 || depth >= 1.0) return;
          ivec2 p = ivec2(gl_FragCoord.xy);
          if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, pzoptPlantView.zw))) return;
          uint d = uint(clamp(floor(depth * 65535.0 + 0.5), 0.0, 65535.0));
          uvec2 a = uvec2(clamp(floor(attributes * 255.0 + 0.5), 0.0, 255.0));
          imageAtomicMin(pzoptPlantTarget, p, (d << 16u) | (a.y << 8u) | a.x);
      }
      """;

  private OutlinePlantShaders() {}

  /** Exact private uniforms unsupported by the native model-property loader. */
  public static boolean ownsUniform(String name) {
    return "pzoptPlantView".equals(name)
        || "pzoptPlantMode".equals(name)
        || "pzoptPlantTarget".equals(name);
  }

  /** Include generated chunk composites, but never screen/post-processing programs. */
  public static boolean composite(String source) {
    return source.contains("uniform float chunkDepth")
        && Pattern.compile("uniform\\s+sampler2D\\s+DEPTH\\b").matcher(source).find();
  }

  /** Keep native outputs unchanged; the extra image has independent depth testing. */
  public static String patch(String file, String source) {
    if (source.contains("PzoptPlantControl")) return source;
    boolean composite = composite(source);
    boolean tree = file.equals("pzoptPlantTree.frag");
    if (!composite && !tree && !OccludedOutlineShaders.material(file)) return source;
    var main = MAIN.matcher(source);
    if (!main.find()) return source;
    if (source.contains("early_fragment_tests")) {
      throw new IllegalArgumentException("Early tests discard cached occluders in " + file);
    }
    String body = main.replaceFirst("void pzoptPlantOriginal()");
    var version = VERSION.matcher(body);
    if (!version.find()) throw new IllegalArgumentException("Missing GLSL version: " + file);
    body = version.replaceFirst("#version 430 compatibility");
    int header = declarationOffset(body);
    body = body.substring(0, header) + HEADER + body.substring(header);
    if (composite) {
      String lookup = compositeLookup(source);
      return body
          + lookup
          + """
          void main() {
              // Run before native discard: a grass hole must not lose the solid cache behind it.
              if (pzoptPlantMode.x == 2) {
                  vec2 uv = pzoptPlantUV();
                  float d = pzoptPlantDepth(uv).r;
                  if (d < 1.0) pzoptPlantStore(chunkDepth + d, vec2(0.0));
              }
              pzoptPlantOriginal();
          }
          """;
    }
    String colour = source.contains("gl_FragData[0]") ? "gl_FragData[0]" : "gl_FragColor";
    if (!source.contains(colour)) {
      if (Pattern.compile("\\bcolour\\s*=").matcher(source).find()) colour = "colour";
      else throw new IllegalArgumentException("Unknown cached material output: " + file);
    }
    String depth =
        Pattern.compile("\\bgl_FragDepth\\s*=").matcher(source).find()
            ? "gl_FragDepth"
            : "gl_FragCoord.z";
    // Sway encodes weight/class and phase/depth-check bits in its second colour output.
    String attributes =
        tree || source.contains("vec4 pzSwAttr(") ? "gl_FragData[1].rg" : "vec2(0.0)";
    return body
        + "\nvoid main() {\n    pzoptPlantOriginal();\n"
        + "    if (pzoptPlantMode.x == 1 && "
        + colour
        + ".a >= 0.999)\n"
        + "        pzoptPlantStore("
        + depth
        + ", "
        + attributes
        + ");\n}\n";
  }

  /** GLSL extensions must precede the generated uniform/function declarations. */
  static int declarationOffset(String source) {
    int offset = source.indexOf('\n') + 1;
    var extensions = Pattern.compile("(?m)^\\s*#extension[^\\n]*").matcher(source);
    while (extensions.find()) offset = Math.min(source.length(), extensions.end() + 1);
    return offset;
  }

  private static String compositeLookup(String source) {
    String helpers =
        """
        layout(binding = 8) uniform usampler2D pzoptPlantSource;
        uint pzoptPlantFetch(vec2 uv) {
            ivec2 size = textureSize(pzoptPlantSource, 0);
            return texelFetch(pzoptPlantSource, clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1), 0).r;
        }
        vec4 pzoptPlantDepth(vec2 uv) { return vec4(float(pzoptPlantFetch(uv) >> 16u) / 65535.0); }
        vec4 pzoptPlantDepth(vec2 uv, float lod) { return pzoptPlantDepth(uv); }
        vec4 pzoptPlantAux(vec2 uv) {
            uint p = pzoptPlantFetch(uv);
            if ((p >> 16u) == 65535u) return vec4(0.0);
            return vec4(float(p & 255u), float((p >> 8u) & 255u), 0.0, 255.0) / 255.0;
        }
        vec4 pzoptPlantAux(vec2 uv, float lod) { return pzoptPlantAux(uv); }
        vec4 pzoptPlantMask(vec2 uv) { return vec4(1.0); }
        """;
    int start = source.indexOf("void pzSway()");
    if (start < 0) return helpers + "vec2 pzoptPlantUV() { return texCoord; }\n";
    int brace = source.indexOf('{', start);
    int end = brace + 1;
    int nesting = 1;
    while (nesting != 0 && end < source.length()) {
      char c = source.charAt(end++);
      if (c == '{') nesting++;
      else if (c == '}') nesting--;
    }
    if (nesting != 0) throw new IllegalArgumentException("Unclosed sway function");
    String sway = source.substring(start, end).replace("void pzSway()", "void pzoptPlantSway()");
    // Reuse the exact wind/probe algorithm and uniforms, with independent depth and tree
    // attributes.
    // This lookup must not overwrite native motion images or mutate native colour-lookup state.
    sway = sway.replaceAll("imageStore\\(pzSwMv(?:Img|Tile),[^;]+;", "");
    for (String sampler : new String[] {"DEPTH", "pzSwAux", "pzSwMask"}) {
      String function =
          sampler.equals("DEPTH")
              ? "pzoptPlantDepth"
              : sampler.equals("pzSwAux") ? "pzoptPlantAux" : "pzoptPlantMask";
      sway =
          sway.replaceAll(
              "\\b(?:texture2D|texture|textureLod|texture2DLod)\\(\\s*" + sampler + "\\s*,",
              function + "(");
    }
    for (String name : new String[] {"T", "Lod", "C0", "Z0", "Moved", "Mv", "Tint"}) {
      sway = sway.replaceAll("\\bpzSw" + name + "\\b", "pzoptPlant" + name);
    }
    return helpers
        + """
        vec2 pzoptPlantT;
        float pzoptPlantLod;
        vec4 pzoptPlantC0, pzoptPlantZ0;
        bool pzoptPlantMoved = false;
        vec2 pzoptPlantMv = vec2(0.0);
        vec4 pzoptPlantTint = vec4(0.0);
        """
        + sway
        + "\nvec2 pzoptPlantUV() { pzoptPlantSway(); return pzoptPlantT; }\n";
  }
}
