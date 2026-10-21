package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * God rays through doors (2026-10-18): the light volume program (door leaf, character capsules, door dust), the local
 * lights' program with the spill, the bounce program and the chunk composite's haze patch on the stock GLSL 1.20
 * composite (it never compiled there before: 420pack needs a newer version) link and validate with the real driver,
 * headless (/tmp/glslcheck, tools/hdr/glslcheck.c). The bounce shader missed a uniform the first time and switched
 * itself off in game: this catches that. Skipped without the checker.
 */
public class GodRaysDoorShaderTest {
   public static void main(String[] args) throws Exception {
      Check.check(GodRays.APD_FRAG.contains("leafT(") && GodRays.APD_FRAG.contains("capsT(") && GodRays.APD_FRAG.contains("swirl("), "the light volume shader has the door leaf, the capsules and the dust");
      Check.check(GodRays.LL_FRAG.contains("uLP["), "the local lights carry the spill's frustum");
      Path check = Path.of(System.getenv().getOrDefault("GLSLCHECK", "/tmp/glslcheck"));
      if (!Files.isExecutable(check)) {
         System.out.println("GodRaysDoorShaderTest ok (text only: no /tmp/glslcheck)");
         return;
      }
      String[][] programs = {
         {"light volumes", GodRays.AP_VERT, GodRays.APD_FRAG},
         {"light volumes (two-pass)", GodRays.AP_VERT, GodRays.AP_FRAG},
         {"local lights + spill", GodRays.LL_VERT, GodRays.LL_FRAG},
         {"bounce", GodRays.LL_VERT, GodRays.BOUNCE_FRAG}};
      for (String[] p : programs) {
         link(check, p[0], p[1], p[2]);
      }
      Path dir = Path.of(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media", "shaders");
      if (Files.exists(dir.resolve("chunkShader.frag"))) {
         // as patchChunk builds it for the stock composite (#version 120)
         String code = Files.readString(dir.resolve("chunkShader.frag"));
         int nl = code.indexOf('\n');
         Check.check(code.startsWith("#version 120"), "the stock chunk composite is GLSL 1.20 (else this test's head is wrong)");
         String c = "#version 150 compatibility\n#extension GL_ARB_shading_language_420pack : enable\n#define PZG_OUT gl_FragColor\n#define PZG_TEX3 texture3D"
            + code.substring(nl).replace("void main()", "void pzGrHazeInner()") + "\n" + GodRays.CHUNK_GLSL + "\n";
         link(check, "chunk haze patch", Files.readString(dir.resolve("chunkShader.vert")), c);
      }
      System.out.println("GodRaysDoorShaderTest ok (linked)");
   }

   private static void link(Path check, String name, String vert, String frag) throws Exception {
      Path v = Files.writeString(Files.createTempFile("pzgrd", ".vert"), vert);
      Path f = Files.writeString(Files.createTempFile("pzgrd", ".frag"), frag);
      Process p = new ProcessBuilder(check.toString(), v.toString(), f.toString()).redirectErrorStream(true).start();
      String log = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      Files.deleteIfExists(v);
      Files.deleteIfExists(f);
      Check.check(log.contains("link ok") && log.contains("validate ok"), name + " links with the driver:\n" + log);
   }
}
