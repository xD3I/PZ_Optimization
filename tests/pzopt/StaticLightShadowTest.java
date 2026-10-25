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

      // the wall plane a static light's shadow stops at: x = c / y = c on a square edge, the side the shadow runs to
      int lx = 100, ly = 200, ox = 90, oy = 190; // the light's square, the frame's origin square
      int east = StaticLightShadow.planeCode(0, 3, true); // x = 103, the shadow running east
      float w = StaticLightShadow.planeW(east, lx, ly, ox, oy);
      check(StaticLightShadow.beyond(w, 103.5F - ox, 200.5F - oy) > 0.4F, "east of x = 103: beyond");
      check(StaticLightShadow.beyond(w, 102.9F - ox, 250F - oy) < 0F, "west of it: before, whatever the y (the wall face keeps its shadow along the wall)");
      check(Math.abs(StaticLightShadow.beyond(w, 103F - ox, 0F)) < 1e-4F, "on the plane: 0");
      int north = StaticLightShadow.planeCode(1, -2, false); // y = 198, the shadow running north (to smaller y)
      float wn = StaticLightShadow.planeW(north, lx, ly, ox, oy);
      check(StaticLightShadow.beyond(wn, 0F, 197.5F - oy) > 0.4F && StaticLightShadow.beyond(wn, 0F, 198.5F - oy) < 0F, "north plane: beyond = smaller y");
      check(StaticLightShadow.planeW(StaticLightShadow.OPEN, lx, ly, ox, oy) == StaticLightShadow.OPEN_W && StaticLightShadow.beyond(StaticLightShadow.OPEN_W, 0F, 0F) < -1e3F, "open: no plane");
      check(StaticLightShadow.planeCode(0, -64, false) > 0 && StaticLightShadow.planeCode(1, 63, true) < 0x7FFF, "codes stay inside the cache's 16 bits, never NONE or OPEN");
      // the plane's distance along the ray from the light's centre: a wall 3 squares east, the caster due east / 45 degrees
      float lcx = lx + 0.5F - ox, lcy = ly + 0.5F - oy;
      check(Math.abs(StaticLightShadow.planeDistance(w, lcx, lcy, lcx + 2F, lcy) - 2.5F) < 1e-4F, "due east: 2.5 to x = 103");
      check(Math.abs(StaticLightShadow.planeDistance(w, lcx, lcy, lcx + 1F, lcy + 1F) - 2.5F * (float)Math.sqrt(2)) < 1e-3F, "45 degrees: longer");
      check(StaticLightShadow.planeDistance(w, lcx, lcy, lcx - 1F, lcy) == StaticLightShadow.OPEN_W, "away from it: never");
      // edgePlane: a step east crosses x = max(px, nx); a diagonal step takes the edge the ray crosses last
      check(StaticLightShadow.edgePlane(104, 200, 105, 200, 102.5F, 200.5F, 1F, 0F, lx, ly) == StaticLightShadow.planeCode(0, 5, true), "a step east");
      check(StaticLightShadow.edgePlane(101, 200, 101, 199, 101.5F, 201.5F, 0F, -1F, lx, ly) == StaticLightShadow.planeCode(1, 0, false), "a step north: y = 200, running north");
      float vx = 0.8F, vy = 0.6F; // from (100.5, 200.5): crosses x = 102 at t 1.875, y = 201 at t 0.83 -> the step (101,200) -> (102,201) crosses x last
      check(StaticLightShadow.edgePlane(101, 200, 102, 201, 100.5F, 200.5F, vx, vy, lx, ly) == StaticLightShadow.planeCode(0, 2, true), "a diagonal step: the later crossing");

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
      check(StaticLightShadow.ROOM_KIND > 4.5F && StaticLightShadow.KIND_FIRE > 3.5F && StaticLightShadow.KIND_FIRE < 4.5F && StaticLightShadow.KIND < 3.5F && StaticLightShadow.KIND > 2.5F, "kinds 3, 4 and 5 apart");
      check(StaticLightShadow.CEIL_H < 2.4494897F && StaticLightShadow.CEIL_H > StaticLightShadow.LAMP_H, "the fixture under the ceiling, above a lamp");

      // fires: a light with no switch and no building (a campfire, a burning square, a burning character) is a fire, kind 4,
      // drawn with the flames' wider source; a lamp's kind and size stay
      zombie.iso.IsoLightSource campfire = new zombie.iso.IsoLightSource(10, 10, 0, 0.61F, 0.175F, 0F, 8);
      check(StaticLightShadow.fire(campfire), "a light with no switch and no building is a fire");
      check(StaticLightShadow.height(campfire) == StaticLightShadow.FIRE_H, "a fire's height");
      check(StaticLightShadow.BURN_H > StaticLightShadow.FIRE_H, "a burning character's flames sit higher than a campfire's");
      check(StaticLightShadow.FIRE_SIZE > StaticLightShadow.LAMP_SIZE, "a fire's source is wider than a lamp's");
      check(StaticLightShadow.KIND_FIRE > 3.5F && StaticLightShadow.KIND > 2.5F && StaticLightShadow.KIND < 3.5F, "kinds 3 / 4 as the shader splits them");
      // reach() of a fire on the caster's own square (a burning character's light): none, and why says so
      check(StaticLightShadow.reach(null, campfire, 10, 10, 0) == StaticLightShadow.NONE && StaticLightShadow.why == 'o', "a fire does not shade the caster on its square");
      check(StaticLightShadow.reach(null, campfire, 10, 10, 1) == StaticLightShadow.NONE && StaticLightShadow.why == 'z', "nor one on another level");
      // a lightbar's red / blue world lights by their colour (the vehicle on the square decides in game); a fire is not one
      check(StaticLightShadow.lightbarColour(0F, 0F, 1F) && StaticLightShadow.lightbarColour(1F, 0F, 0F), "lightbar blue / red");
      check(!StaticLightShadow.lightbarColour(0.61F, 0.175F, 0F) && !StaticLightShadow.lightbarColour(1F, 0.31F, 0.08F), "a fire, a street lamp: not a lightbar");
      String[] src = CapsuleShadow.lightProgramSources();
      check(src[1].contains("kind > 4.5") && src[1].contains("lK[light] > 4.5"), "the ceiling light's flat share and lamp size in the fragment stage");
      check(src[1].contains("lK[light] > 3.5 ? " + StaticLightShadow.FIRE_SIZE), "the fire's source size in the fragment program");
      check(src[0].contains("uniform vec4 lA[8];") && src[1].contains("uniform float lK[8];"), "eight light slots in both stages");
      check(src[1].contains("kind > 2.5"), "the point-light falloff in torch()");
      check(src[0].contains("lim = pair.w") && src[1].contains("flat in float lim"), "the shadow's end goes from the pair to the fragment");
      check(src[0].contains("float wallDist(") && src[1].contains("wallBeyond(P.xy, lim)"), "the wall plane cuts the quad and the fragment");
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
