package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints pzopt.GroundBake's floor variant source for harness/ground/rig.py (2026-10-24): the exact text the game would
 * compile, from a stock tile fragment program and patch files.
 *   java -cp build/ground-tools:build/classes:<jar> pzopt.VariantSource <stock.frag> <view> <piece> [patch.glsl:texelFn:colorFn ...]
 * (texelFn / colorFn may be "-" for none; the patch id is the file's base name)
 */
public class VariantSource {
   public static void main(String[] args) throws Exception {
      String stock = Files.readString(Path.of(args[0]));
      int view = Integer.parseInt(args[1]), piece = Integer.parseInt(args[2]);
      List<GroundBake.FloorPatch> ps = new ArrayList<>();
      for (int i = 3; i < args.length; i++) {
         String[] f = args[i].split(":");
         String decl = Files.readString(Path.of(f[0]));
         String id = Path.of(f[0]).getFileName().toString().replaceAll("\\W.*", "");
         String tx = f.length > 1 && !f[1].equals("-") ? f[1] : null, co = f.length > 2 && !f[2].equals("-") ? f[2] : null;
         ps.add(new GroundBake.FloorPatch() {
            public String id() { return id; }
            public boolean active() { return true; }
            public String declarations() { return decl; }
            public String texel() { return tx; }
            public String color() { return co; }
         });
      }
      String v = GroundBake.variantSource(stock, ps, view, piece);
      if (v == null) {
         System.err.println("no variant (the stock source is not as expected)");
         System.exit(1);
      }
      System.out.print(v);
   }
}
