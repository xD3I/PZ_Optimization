package pzopt;

import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CPU checks for the conservative bounds used to scissor actual model replays. */
public final class OccludedOutlineBoundsTest {
  public static void main(String[] args) {
    OccludedOutlineBounds empty = bounds();
    empty.finish();
    check(
        empty.empty() && empty.x == 0 && empty.y == 0,
        "empty geometry must not overflow int bounds");

    OccludedOutlineBounds offscreen = bounds();
    offscreen.box(new Matrix4f().translation(1.0E30F, 0, 0), new Vector3f(-1), new Vector3f(1));
    offscreen.finish();
    check(offscreen.empty(), "very large offscreen geometry is empty, not wrapped around");

    OccludedOutlineBounds invalid = bounds();
    invalid.point(new Matrix4f().m33(0), 0, 0, 0);
    invalid.finish();
    check(
        invalid.x == 0 && invalid.y == 0 && invalid.w == 800 && invalid.h == 600,
        "projection-plane crossing keeps the full viewport");

    OccludedOutlineBounds overflow = bounds();
    overflow.point(new Matrix4f().m33(Float.MIN_VALUE), 1, 1, 0);
    overflow.finish();
    check(overflow.w == 800 && overflow.h == 600, "projection overflow is conservative");

    Matrix4f projection = new Matrix4f().scaling(0.3F, 0.4F, 0.2F);
    Matrix4f first = new Matrix4f(projection).translate(-0.5F, 0.3F, 0).rotateZ(0.4F);
    Matrix4f second = new Matrix4f(projection).translate(0.7F, -0.2F, 0).rotateZ(-0.8F);
    OccludedOutlineBounds skin = bounds();
    skin.box(first, new Vector3f(-1), new Vector3f(1));
    skin.box(second, new Vector3f(-1), new Vector3f(1));
    skin.finish();
    Random random = new Random(3792740760L);
    for (int i = 0; i < 1000; i++) {
      Vector4f vertex =
          new Vector4f(
              random.nextFloat() * 2 - 1,
              random.nextFloat() * 2 - 1,
              random.nextFloat() * 2 - 1,
              1);
      Vector4f a = first.transform(new Vector4f(vertex));
      Vector4f b = second.transform(new Vector4f(vertex));
      a.lerp(b, random.nextFloat());
      float x = (a.x / a.w + 1) * 400 + 0.25F;
      float y = (a.y / a.w + 1) * 300 - 0.25F;
      check(
          x >= skin.x && x <= skin.x + skin.w && y >= skin.y && y <= skin.y + skin.h,
          "weighted skin vertex escaped the capture rectangle");
    }
    try {
      new OccludedOutlineBounds(0, 600, 0, 0);
      throw new AssertionError("zero-width viewport accepted");
    } catch (IllegalArgumentException expected) {
      // Minimized/zero-sized views are skipped before allocation by the runtime hook.
    }
    System.out.println("OccludedOutlineBoundsTest passed");
  }

  private static OccludedOutlineBounds bounds() {
    return new OccludedOutlineBounds(800, 600, 0.25F, -0.25F);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
