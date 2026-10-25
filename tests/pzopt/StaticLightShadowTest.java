package pzopt;

import static pzopt.Check.check;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sunShadowStaticLights: the falloff share (dark under the lamp, faint at the pool's edge, none past it), the strength as
 * the native gets it, the nearest-pool ordering, the per-caster pick of the two strongest; the room ceiling lights'
 * fixture points and flat share (sunShadowRoomLights); and the casters x lights program (8 light slots, kinds 3 and 4) links with the real driver when /tmp/glslcheck exists.
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

      // room ceiling lights (sunShadowRoomLights): the fixture points of a room rectangle
      float[] pts = new float[StaticLightShadow.ROOM_POINTS * 3];
      int k = StaticLightShadow.roomPoints(100, 200, 4, 4, pts);
      check(k == 1 && pts[0] == 102F && pts[1] == 202F, "a square room: one point at its centre: " + k + " " + pts[0] + "," + pts[1]);
      check(Math.abs(pts[2] - (0.5F * (float)Math.sqrt(32) + 2F)) < 1e-4F, "its reach: the half diagonal + 2: " + pts[2]);
      k = StaticLightShadow.roomPoints(10, 20, 6, 5, pts);
      check(k == 1 && pts[0] == 13F && pts[1] == 22.5F, "a living room: one point: " + k);
      k = StaticLightShadow.roomPoints(0, 0, 3, 8, pts);
      check(k == 2 && pts[0] == 1.5F && pts[1] == 2F && pts[3] == 1.5F && pts[4] == 6F, "a 3 x 8 hallway: two points along it: " + k + " " + pts[1] + "," + pts[4]);
      k = StaticLightShadow.roomPoints(0, 0, 12, 2, pts);
      check(k == 3 && pts[0] == 2F && pts[3] == 6F && pts[6] == 10F && pts[1] == 1F, "a 12 x 2 corridor: three points: " + k);
      k = StaticLightShadow.roomPoints(0, 0, 40, 1, pts);
      check(k == StaticLightShadow.ROOM_POINTS, "capped at ROOM_POINTS: " + k);
      check(pts[2] <= 14F, "reach capped: " + pts[2]);
      k = StaticLightShadow.roomPoints(0, 0, 12, 12, pts);
      check(k == 1 && pts[0] == 6F && pts[1] == 6F, "a big square shop: one point: " + k);
      check(StaticLightShadow.roomPoints(5, 5, 0, 0, pts) == 1 && pts[0] == 5.5F, "a degenerate rectangle: one point in its square");
      // a room is lit as a whole: an L-shaped room (two rectangles) has one fixture, inside it (its centroid lies outside)
      k = StaticLightShadow.roomPoints(new int[] {0, 0, 8, 2, 0, 2, 2, 6}, 2, pts);
      boolean inA = pts[0] >= 0.5F && pts[0] <= 7.5F && pts[1] >= 0.5F && pts[1] <= 1.5F;
      boolean inB = pts[0] >= 0.5F && pts[0] <= 1.5F && pts[1] >= 2.5F && pts[1] <= 7.5F;
      check(k == 1 && (inA || inB), "an L-shaped room: one point, inside the room: " + k + " " + pts[0] + "," + pts[1]);
      check(Math.abs(pts[0] - 76F / 28F) < 1e-3F || Math.abs(pts[1] - 76F / 28F) < 1e-3F, "moved in from the centroid along one axis only: " + pts[0] + "," + pts[1]);
      // a room cut into a strip and a block (the lamp-indoor1 living room: 8 x 1 + 5 x 3): one point, not one a strip
      k = StaticLightShadow.roomPoints(new int[] {8140, 11508, 8, 1, 8143, 11505, 5, 3}, 2, pts);
      check(k == 1 && pts[0] > 8143F && pts[0] < 8147F && pts[1] > 11505F && pts[1] < 11508.6F, "strip + block: one point in the block's span: " + k + " " + pts[0] + "," + pts[1]);
      // the room share: flat over the pool, fading to nothing at the reach, never above ROOM_SHARE
      check(StaticLightShadow.roomShare(0F, 6F) == StaticLightShadow.ROOM_SHARE, "flat under the fixture");
      check(StaticLightShadow.roomShare(3.9F, 6F) == StaticLightShadow.ROOM_SHARE, "flat over two thirds of the reach");
      check(StaticLightShadow.roomShare(6F, 6F) == 0F && StaticLightShadow.roomShare(7F, 6F) == 0F, "none at / past the reach");
      check(StaticLightShadow.roomShare(5F, 6F) > 0F && StaticLightShadow.roomShare(5F, 6F) < StaticLightShadow.ROOM_SHARE, "fading near the reach");
      check(StaticLightShadow.roomShare(2F, 0F) == 0F, "no reach, no share");
      prev = 2F;
      for (float d = 0F; d <= 7F; d += 0.25F) {
         float sr = StaticLightShadow.roomShare(d, 6F);
         check(sr <= prev + 1e-6F && sr >= 0F && sr <= StaticLightShadow.ROOM_SHARE, "room share monotone at d " + d);
         prev = sr;
      }
      // a room point's line answers are kept apart from a lamp's on the same square (key bit), and the light's kind
      check(StaticLightShadow.ROOM_KIND > 3.5F && StaticLightShadow.KIND < 3.5F && StaticLightShadow.KIND > 2.5F, "kinds 3 and 4 apart");
      check(StaticLightShadow.CEIL_H < 2.4494897F && StaticLightShadow.CEIL_H > StaticLightShadow.LAMP_H, "the fixture under the ceiling, above a lamp");

      String[] src = CapsuleShadow.lightProgramSources();
      check(src[1].contains("kind > 3.5") && src[1].contains("lK[light] > 3.5"), "the ceiling light's flat share and lamp size in the fragment stage");
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
