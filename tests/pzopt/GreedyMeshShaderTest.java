package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Binary greedy meshing's GL side (2026-11-28) links on the real driver, headless (/tmp/glslcheck, tools/hdr/glslcheck.c):
 * the occupancy mesh's shadow map (solids, crowns), the god rays' visibility compute with the mesh lookup (mesh, mesh-dda). Skipped without the checker.
 */
public class GreedyMeshShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of(System.getenv().getOrDefault("GLSLCHECK", "/tmp/glslcheck"));
      Check.check(GodRays.VIS_CS.contains("#ifdef PZ_MESH") && GodRays.VIS_CS.contains("VIS(p)"), "the visibility compute has the mesh switch");
      if (!Files.isExecutable(check)) {
         System.out.println("GreedyMeshShaderTest ok (text only: no /tmp/glslcheck)");
         return;
      }
      link(check, "mesh map solids", OccMeshMap.VERT, OccMeshMap.DEPTH_FRAG);
      link(check, "mesh map crowns", OccMeshMap.VERT, OccMeshMap.CROWN_FRAG);
      comp(check, "visibility dda", GodRays.VIS_CS);
      comp(check, "visibility mesh", GodRays.VIS_CS.replaceFirst("#version 430\n", "#version 430\n#define PZ_MESH\n"));
      comp(check, "visibility mesh-dda", GodRays.VIS_CS.replaceFirst("#version 430\n", "#version 430\n#define PZ_MESH\n#define PZ_MESH_DDA\n"));
      System.out.println("GreedyMeshShaderTest ok (linked)");
   }

   private static void comp(Path check, String name, String src) throws Exception {
      Path c = Files.writeString(Files.createTempFile("pzgm", ".comp"), src);
      run(check, name, c.toString());
      Files.deleteIfExists(c);
   }

   private static void link(Path check, String name, String vert, String frag) throws Exception {
      Path v = Files.writeString(Files.createTempFile("pzgm", ".vert"), vert);
      Path f = Files.writeString(Files.createTempFile("pzgm", ".frag"), frag);
      run(check, name, v.toString(), f.toString());
      Files.deleteIfExists(v);
      Files.deleteIfExists(f);
   }

   private static void run(Path check, String name, String... files) throws Exception {
      String[] cmd = new String[files.length + 1];
      cmd[0] = check.toString();
      System.arraycopy(files, 0, cmd, 1, files.length);
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String log = new String(p.getInputStream().readAllBytes());
      int rc = p.waitFor();
      Check.check(rc == 0, name + " links: " + log);
   }
}
