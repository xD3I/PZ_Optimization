package pzopt;

import java.nio.charset.StandardCharsets;

/** Pure source-contract tests; --patch is also used by the EGL integration tests. */
public final class OccludedOutlineShadersTest {
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--plant")) {
      System.out.print(
          OutlinePlantShaders.patch(
              args[1], new String(System.in.readAllBytes(), StandardCharsets.UTF_8)));
      return;
    }
    if (args.length == 2 && args[0].equals("--patch")) {
      String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
      System.out.print(OccludedOutlineShaders.patch(args[1], source));
      return;
    }
    for (String name :
        new String[] {
          "pzoptOcclusionView",
          "pzoptOcclusionFlags",
          "pzoptOpaqueDepth",
          "pzoptPlantView",
          "pzoptPlantMode",
          "pzoptPlantTarget"
        }) {
      check(OccludedOutlineShaders.ownsUniform(name), "private GPU uniform excluded: " + name);
    }
    for (String name :
        new String[] {"Texture0", "Alpha", "MatrixPalette", "pzoptOpaqueDepthCustom"}) {
      check(!OccludedOutlineShaders.ownsUniform(name), "ordinary uniforms remain model properties");
    }
    check(!OccludedOutlineShaders.ownsUniform(null), "null is not an owned uniform");
    String fragment = "#version 120\nvoid main() { gl_FragColor = vec4(1); }\n";
    check(
        OccludedOutlineShaders.patch("water.frag", fragment).equals(fragment),
        "non-material unchanged");
    for (String name :
        new String[] {
          "damn_vehicle_shader", "damn_vehicle_noreflect_shader", "damn_wheel_shader"
        }) {
      String path = "mods/damnlib/common/media/shaders/" + name + ".frag";
      check(OccludedOutlineShaders.material(path), "KI5 material recognized: " + name);
      check(
          OccludedOutlineShaders.patch(path, fragment).contains("imageAtomicMin"),
          "KI5 material contributes depth: " + name);
    }
    check(
        !OccludedOutlineShaders.material("damn_unknown_shader.frag"),
        "Do not instrument unrelated mod shaders by prefix");
    check(
        !OccludedOutlineShaders.material("media/shaders/DeadBodyAtlas.frag"),
        "Character and corpse atlas sprites are not outline occluders");
    check(
        OccludedOutlineShaders.patch("DeadBodyAtlas.frag", fragment).equals(fragment),
        "Atlas appearance and ordinary depth remain untouched");
    check(
        OccludedOutlineShaders.material("basicEffect.frag"),
        "Shared model material retained for world items and lighting export");
    String patched = OccludedOutlineShaders.patch("tileWithDepth.frag", fragment);
    check(patched.contains("imageAtomicMin"), "independent opaque depth");
    check(patched.contains("float d = gl_FragCoord.z;"), "vertex-generated depth");
    check(
        !patched.contains("early_fragment_tests"), "depth-rejected opaque fragments must execute");
    check(
        OccludedOutlineShaders.patch("tileWithDepth.frag", patched).equals(patched),
        "idempotent variants");
    String depthFragment = fragment.replace("gl_FragColor =", "gl_FragDepth = 0.6; gl_FragColor =");
    check(
        OccludedOutlineShaders.patch("tileWithDepth.frag", depthFragment)
            .contains("float d = gl_FragDepth;"),
        "shader-written world depth");
    String vehicle =
        "\uFEFF#version 120\nvoid oldMain() { float windowAlpha = 1.0; gl_FragColor = vec4(1); }\n"
            + "void main() { oldMain(); }\n";
    patched = OccludedOutlineShaders.patch("vehicle_multiuv.frag", vehicle);
    check(
        patched.indexOf("bool pzoptOcclusionGlass") < patched.indexOf("void oldMain"),
        "HDR wrappers see glass state");
    check(
        patched.contains("pzoptOcclusionGlass = windowAlpha > 0.5"),
        "vehicle material, not final alpha");
    reject(
        "#version 430\nlayout(early_fragment_tests) in;\nvoid main() { gl_FragColor = vec4(1); }");
    reject("#version 330\nuniform sampler2D TextureMask;\nvoid main() { gl_FragColor = vec4(1); }");
    System.out.println("OccludedOutlineShadersTest passed");
  }

  private static void reject(String source) {
    try {
      OccludedOutlineShaders.patch("vehicle_multiuv.frag", source);
      throw new AssertionError("Unsupported material accepted");
    } catch (IllegalArgumentException expected) {
      // The runtime disables only this optional feature and retains the original material source.
    }
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
