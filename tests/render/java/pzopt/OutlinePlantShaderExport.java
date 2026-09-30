package pzopt;

import java.nio.charset.StandardCharsets;

/** Exports actual sway-generated shaders for the headless plant-cache regressions. */
public final class OutlinePlantShaderExport {
  public static void main(String[] args) throws Exception {
    var output = System.out;
    System.setOut(System.err); // Native initialization logs must not become GLSL source.
    String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    String name;
    switch (args[0]) {
      case "tree" -> {
        var field = Sway.class.getDeclaredField("TREE_FRAG");
        field.setAccessible(true);
        source = (String) field.get(null);
        name = "pzoptPlantTree.frag";
      }
      case "tile" -> {
        source = Sway.patchTile(source);
        name = "tileWithDepth.frag";
      }
      case "composite" -> {
        if (Boolean.getBoolean("pzopt.testSwayImages")) {
          var capability = Sway.class.getDeclaredField("mvImageCap");
          capability.setAccessible(true);
          capability.setInt(null, 1); // Generate the real image variant without a Java GL context.
        }
        Config.FOLIAGE_SWAY_TAPS = Integer.parseInt(args[1]);
        source = Sway.patchComposite(source, true);
        name = "chunkShader.frag";
      }
      default -> throw new IllegalArgumentException(args[0]);
    }
    if (source == null) throw new AssertionError("Native sway transform rejected the fixture");
    output.print(OutlinePlantShaders.patch(name, source));
  }
}
