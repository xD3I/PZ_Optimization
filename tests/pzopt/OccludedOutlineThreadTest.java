package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;

/** FBO creation is allowed on the game thread; its feature predicate cannot query a GL context. */
public final class OccludedOutlineThreadTest {
  public static void main(String[] args) throws Exception {
    try (var input =
        OccludedOutlineThreadTest.class
            .getClassLoader()
            .getResourceAsStream("pzopt/OccludedOutline.class")) {
      if (input == null) throw new AssertionError("Missing built outline helper");
      var model = ClassFile.of().parse(input.readAllBytes());
      var eligibility =
          model.methods().stream()
              .filter(m -> m.methodName().equalsString("eligible"))
              .findFirst()
              .orElseThrow();
      boolean checksCarriedCorpse = false;
      for (var element : eligibility.code().orElseThrow()) {
        if (element instanceof InvokeInstruction call
            && call.name().equalsString("isReanimatedForGrappleOnly")) {
          checksCarriedCorpse = true;
        }
      }
      if (!checksCarriedCorpse) {
        throw new AssertionError("Outline eligibility must reject native carried-corpse proxies");
      }
      var method =
          model.methods().stream()
              .filter(m -> m.methodName().equalsString("depthTextureNeeded"))
              .findFirst()
              .orElseThrow();
      for (var element : method.code().orElseThrow()) {
        if (element instanceof InvokeInstruction call
            && call.owner().name().stringValue().startsWith("org/lwjgl/opengl/")) {
          throw new AssertionError("Game-thread FBO predicate calls OpenGL: " + call.name());
        }
      }
    }
    System.out.println("OccludedOutlineThreadTest passed");
  }
}
