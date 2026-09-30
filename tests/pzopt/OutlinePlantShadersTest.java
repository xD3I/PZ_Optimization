package pzopt;

/** Plant classification and source contracts that do not need a game or GL context. */
public final class OutlinePlantShadersTest {
  public static void main(String[] args) {
    for (boolean bush : new boolean[] {false, true}) {
      for (boolean removable : new boolean[] {false, true}) {
        check(
            !OutlinePlantDepth.lowVegetation(true, bush, removable),
            "Trees always remain occluders");
        check(
            OutlinePlantDepth.lowVegetation(false, bush, removable) == (bush || removable),
            "Only native low-vegetation flags are excluded");
      }
    }
    String source = "#version 330 compatibility\nvoid main() { gl_FragColor=vec4(1); }\n";
    check(
        OutlinePlantShaders.patch("screen.frag", source).equals(source),
        "Leave post-processing alone");
    check(
        OutlinePlantShaders.patch("DeadBodyAtlas.frag", source).equals(source),
        "Keep characters excluded");
    String patched = OutlinePlantShaders.patch("tileWithDepth.frag", source);
    check(patched.contains("void pzoptPlantOriginal()"), "Retain callable native main");
    check(
        patched.contains("imageAtomicMin"), "Independent depth retains surfaces hidden by plants");
    check(patched.contains("d << 16u"), "Depth and tree attributes commit atomically");
    check(
        OutlinePlantShaders.patch("tileWithDepth.frag", patched).equals(patched),
        "Idempotent instrumentation");
    System.out.println("OutlinePlantShadersTest passed");
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
