package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Voxel lighting: its three compute programs and the chunk composite programs it patches (pixelLight's full program, the
 * light-free variant, the dev variant; texel and screen normals) compile and link with the real driver, headless
 * (/tmp/vxlcheck, tools/voxel/vxlcheck.c). Skipped when the checker is missing.
 */
public class VoxelLightShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of("/tmp/vxlcheck");
      if (!Files.isExecutable(check)) {
         System.out.println("VoxelLightShaderTest skipped (no /tmp/vxlcheck)");
         return;
      }
      Path dir = Files.createTempDirectory("vxlcheck");
      int bad = 0;
      String defs = "#version 430\n#define R " + VoxelLight.R + "\n#define RZ " + VoxelLight.RZ + "\n#define N " + VoxelLight.N + "\n#define LZ " + VoxelLight.LZ + "\n#define TX "
         + VoxelLight.TX + "\n#define TZ " + VoxelLight.TZ + "\n#define BT " + VoxelLight.BS * VoxelLight.R + "\n#define LM_SIZE " + VoxelLight.LM_SIZE + "\n";
      String[][] cs = {{"direct", VoxelLight.DIRECT_CS}, {"inject", VoxelLight.INJECT_CS}, {"flood", VoxelLight.FLOOD_CS}, {"resolve", VoxelLight.RESOLVE_CS}, {"relight", VoxelLight.RELIGHT_CS}, {"torch", VoxelLight.TORCH_CS}, {"coarse", VoxelLight.COARSE_CS}, {"maps", VoxelLight.LMAP_CS}, {"cascade", VoxelLight.CASCADE_CS}, {"cube", VoxelLight.CUBE_CS}};
      for (String[] c : cs) {
         Path p = dir.resolve(c[0] + ".comp");
         Files.writeString(p, defs + VoxelLight.COMMON_CS + (c[0].equals("direct") || c[0].equals("torch") || c[0].equals("maps") || c[0].equals("inject") || c[0].equals("cascade") ? VoxelLight.TRACE_GLSL : "") + (c[0].equals("inject") || c[0].equals("cascade") ? VoxelLight.SKYDIR_GLSL : "") + c[1]);
         bad += run(check.toString(), "--cs", p.toString());
      }
      String tint = (String)field("TINT"), body = (String)field("CHUNK_FRAG_BODY"), vert = (String)field("CHUNK_BASE_VERT");
      Path v = dir.resolve("chunk.vert");
      Files.writeString(v, vert);
      String[][] frags = {
         {"full-texel", "#define PPL_VOXEL\n#define VXL_TEXEL_N\n"},
         {"base-texel", "#define PPL_BASE\n#define PPL_VOXEL\n#define VXL_TEXEL_N\n"},
         {"dev-texel", "#define PPL_DEV\n#define PPL_VOXEL\n#define VXL_TEXEL_N\n"},
         {"full-screen", "#define PPL_VOXEL\n#define VXL_SCREEN_N\n"},
         {"base-screen", "#define PPL_BASE\n#define PPL_VOXEL\n#define VXL_SCREEN_N\n"},
         {"base-none", "#define PPL_BASE\n#define PPL_VOXEL\n"},
         {"full-novoxel", ""},
      };
      for (String[] f : frags) {
         Path p = dir.resolve("chunk-" + f[0] + ".frag");
         Files.writeString(p, "#version 420\n" + f[1] + tint + body);
         bad += run(check.toString(), v.toString(), p.toString());
      }
      System.out.println(bad == 0 ? "VoxelLightShaderTest ok (" + dir + ")" : "VoxelLightShaderTest: " + bad + " programs failed (" + dir + ")");
      if (bad != 0) {
         System.exit(1);
      }
   }

   private static Object field(String name) throws Exception {
      java.lang.reflect.Field f = PixelLight.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(null);
   }

   private static int run(String... cmd) throws Exception {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      int rc = p.waitFor();
      if (rc != 0) {
         System.out.println(out);
      } else {
         System.out.println(cmd[cmd.length - 1] + ": ok");
      }
      return rc == 0 ? 0 : 1;
   }
}
