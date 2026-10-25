package pzopt;

import static pzopt.Check.check;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sunShadowStaticLights: the falloff share (dark under the lamp, faint at the pool's edge, none past it), the strength as
 * the native gets it, the nearest-pool ordering, the per-caster pick of the two strongest; and the casters x lights
 * program (8 light slots, kind 3) links with the real driver when /tmp/glslcheck exists.
 */
public class StaticLightShadowTest {
   public static void main(String[] args) throws Exception {
      // share: saturates near the lamp, falls off to nothing at the reach, monotone
      check(StaticLightShadow.share(0F, 10F, 1F) == 1F, "full share under the lamp");
      check(StaticLightShadow.share(10F, 10F, 1F) == 0F && StaticLightShadow.share(12F, 10F, 1F) == 0F, "none at / past the reach");
      float prev = 2F;
      for (float d = 0F; d <= 10F; d += 0.5F) {
         float s = StaticLightShadow.share(d, 10F, 0.8F);
         check(s <= prev + 1e-6F && s >= 0F && s <= 1F, "monotone in 0..1 at d " + d);
         prev = s;
      }
      float edge = StaticLightShadow.share(8F, 10F, 1F), mid = StaticLightShadow.share(5F, 10F, 1F);
      check(edge > 0.05F && edge < 0.2F, "faint at the pool's edge: " + edge);
      check(mid > 0.5F, "strong half way: " + mid);
      check(StaticLightShadow.share(3F, 0F, 1F) == 0F, "no reach, no share");
      check(StaticLightShadow.share(0F, 10F, 0F) == 0F, "a dark light shades nothing");
      // strength: brightest channel x 2, clamped
      check(StaticLightShadow.strength(0.3F, 0.2F, 0.1F) == 0.6F, "strength = 2 x brightest");
      check(StaticLightShadow.strength(0.9F, 0.2F, 0.1F) == 1F, "strength clamped");
      // order: the pool holding the camera first
      check(StaticLightShadow.order(1F, 0F, 10F) < StaticLightShadow.order(3F, 0F, 4F), "the pool the camera stands in leads");
      check(StaticLightShadow.order(30F, 0F, 10F) > 0F, "outside the pool: positive");
      // pickTwo: the two strongest, strongest first, nothing below the floor
      int[] out = new int[2];
      StaticLightShadow.pickTwo(new float[] {0.3F, 0.9F, 0.5F, 0.01F}, 4, out);
      check(out[0] == 1 && out[1] == 2, "two strongest: " + out[0] + "," + out[1]);
      StaticLightShadow.pickTwo(new float[] {0.01F, 0.4F}, 2, out);
      check(out[0] == 1 && out[1] == -1, "one usable: " + out[0] + "," + out[1]);
      StaticLightShadow.pickTwo(new float[] {0F, 0F}, 2, out);
      check(out[0] == -1 && out[1] == -1, "none");
      StaticLightShadow.pickTwo(new float[] {0.2F, 0.7F, 0.9F}, 3, out);
      check(out[0] == 2 && out[1] == 1, "order kept when the strongest comes last: " + out[0] + "," + out[1]);

      // the line-test cache's value: none / open / a limit to 1/16 square
      check(StaticLightShadow.decode(StaticLightShadow.encode(StaticLightShadow.NONE)) < 0F, "none round trip");
      check(StaticLightShadow.decode(StaticLightShadow.encode(StaticLightShadow.OPEN)) == StaticLightShadow.OPEN, "open round trip");
      check(Math.abs(StaticLightShadow.decode(StaticLightShadow.encode(7.3F)) - 7.3F) < 0.07F, "limit round trip");
      check(StaticLightShadow.decode(StaticLightShadow.encode(0F)) == 0F, "a zero limit is not none");
      check(StaticLightShadow.decode(123L << 16 | StaticLightShadow.encode(5F)) == 5F, "the frame bits stay out of the value");

      String[] src = CapsuleShadow.lightProgramSources();
      check(src[0].contains("uniform vec4 lA[8];") && src[1].contains("uniform float lK[8];"), "eight light slots in both stages");
      check(src[1].contains("kind > 2.5"), "the point-light falloff in torch()");
      check(src[0].contains("lim = pair.w") && src[1].contains("flat in float lim"), "the shadow's end goes from the pair to the fragment");
      Path checker = Path.of("/tmp/glslcheck");
      if (Files.isExecutable(checker)) {
         Path v = Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "pzcs_light.vert"), src[0]);
         Path f = Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "pzcs_light.frag"), src[1]);
         Process p = new ProcessBuilder(checker.toString(), v.toString(), f.toString()).redirectErrorStream(true).start();
         String o = new String(p.getInputStream().readAllBytes());
         p.waitFor();
         check(o.contains("link ok"), "casters x lights program links:\n" + o);
         System.out.println("StaticLightShadowTest ok (light program linked)");
      } else {
         System.out.println("StaticLightShadowTest ok (no /tmp/glslcheck: link skipped)");
      }
   }
}
